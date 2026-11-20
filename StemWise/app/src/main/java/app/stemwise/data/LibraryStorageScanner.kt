package app.stemwise.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.stemwise.integration.queryCancellable
import app.stemwise.playback.BackgroundWorkCoordinator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Metadata measurement only; admission and publication remain with the root mutation owner. */
internal class LibraryStorageScanner(
    private val context: Context,
    private val backgroundWorkCoordinator: BackgroundWorkCoordinator
) {
    @Volatile internal var beforeCapturedDirectoryListingForTest: (suspend (String) -> Unit)? = null
    suspend fun scanRootStorage(
        root: DocumentFile
    ): RootStorageScan {
        repeat(3) { attempt ->
            try { return scanRootStorageAttempt(root) }
            catch (error: CapturedStorageDirectoryDisappeared) { if (attempt == 2) throw error }
        }
        error("STORAGE_SCAN_RETRY_EXHAUSTED")
    }

    private class CapturedStorageDirectoryDisappeared : IOException("A captured song directory disappeared; retry the metadata scan")

    private suspend fun scanRootStorageAttempt(
        root: DocumentFile
    ): RootStorageScan {
        val rootDocumentId = runCatching {
            DocumentsContract.getDocumentId(root.uri)
        }.getOrElse { error ->
            throw LibraryRootException(
                runtimeLibraryRootErrorCode(error),
                "Reconnect the Library folder"
            ).also { it.initCause(error) }
        }
        var rootBytes = 0L
        var rootUnknown = 0
        var unmeasuredRootDirectories = 0
        val folders = try {
            queryDocumentChildren(
                root.uri,
                rootDocumentId,
                Int.MAX_VALUE,
                retain = { row ->
                    if (row.directory && SelectedLibraryContract.isVideoId(row.displayName)) true
                    else {
                        if (row.directory) unmeasuredRootDirectories++
                        else if (row.sizeBytes == null) rootUnknown++
                        else rootBytes = Math.addExact(rootBytes, row.sizeBytes)
                        false
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw LibraryRootException(
                runtimeLibraryRootErrorCode(error),
                "Reconnect the Library folder"
            ).also { it.initCause(error) }
        }
        return scanFlatRootStorageMetadata(
            root.uri,
            folders, rootBytes, rootUnknown, unmeasuredRootDirectories
        )
    }

    private suspend fun scanFlatRootStorageMetadata(
        treeUri: android.net.Uri,
        folders: List<StorageDocumentMetadata>,
        rootBytes: Long,
        rootUnknown: Int,
        unmeasuredRootDirectories: Int
    ): RootStorageScan {
        var originals = 0
        var stems = 0
        var json = 0
        var artwork = 0
        var bytes = rootBytes
        var unknown = rootUnknown
        var unmeasuredDirectories = unmeasuredRootDirectories
        val measuredDocuments = hashSetOf<String>()
        fun account(file: StorageDocumentMetadata) {
            if (!measuredDocuments.add(file.documentId)) return
            if (file.directory) {
                unmeasuredDirectories++
            } else {
                val length = file.sizeBytes
                if (length == null) unknown++ else bytes = Math.addExact(bytes, length)
            }
        }
        folders.sortedBy(StorageDocumentMetadata::displayName)
            .chunked(STORAGE_SCAN_MAXIMUM_CONCURRENT_LISTINGS)
            .forEach { batch ->
                val listings = coroutineScope {
                    batch.map { folder ->
                        async {
                            storageScanCheckpoint()
                            beforeCapturedDirectoryListingForTest?.invoke(folder.displayName)
                            val files = try { queryDocumentChildren(
                                treeUri,
                                folder.documentId,
                                SelectedLibraryContract.maximumPackageDirectoryEntries
                            ) } catch (error: SecurityException) {
                                val present = queryDocumentChildren(treeUri, DocumentsContract.getDocumentId(treeUri),
                                    Int.MAX_VALUE, retain = { it.documentId == folder.documentId })
                                if (present.isEmpty()) throw CapturedStorageDirectoryDisappeared()
                                throw error
                            }
                            folder to files
                        }
                    }.awaitAll()
                }
                listings.forEach { (_, files) ->
                    files.forEach(::account)
                    val names = files.filter { !it.directory }.map(StorageDocumentMetadata::displayName)
                    originals += names.count { it in SelectedLibraryContract.originalFiles }
                    stems += names.count { it in SelectedLibraryContract.customStemFiles }
                    json += names.count { it == SelectedLibraryContract.ENRICHMENT }
                    artwork += names.count { it == SelectedLibraryContract.COVER }

                }
            }
        return RootStorageScan(
            songsFound = folders.size,
            originalFiles = originals,
            stemFiles = stems,
            artworkFiles = artwork,
            jsonFiles = json,
            actualBytes = bytes,
            unknownSizeFiles = unknown,
            unreadableDirectories = unmeasuredDirectories,
        )
    }

    /** One provider round-trip returns every child's id, name, kind, and byte size. */
    private suspend fun queryDocumentChildren(
        treeUri: android.net.Uri,
        directoryDocumentId: String,
        maximumRows: Int,
        retain: (StorageDocumentMetadata) -> Boolean = { true }
    ): List<StorageDocumentMetadata> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            directoryDocumentId
        )
        return context.contentResolver.queryCancellable(
            childrenUri,
            STORAGE_DOCUMENT_PROJECTION
        ) { cursor ->
                cursor.requireSuccessfulSelectedLibraryQuery()
                val idIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                )
                val nameIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                val mimeIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val sizeIndex = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE
                )
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    throw unsupportedStorageLayout(
                        "Document metadata omitted an identity column"
                    )
                }
                val documentIds = hashSetOf<String>()
                val displayNames = hashSetOf<String>()
                buildList<StorageDocumentMetadata> {
                    while (cursor.moveToNext()) {
                        if (size >= maximumRows) {
                            throw unsupportedStorageLayout(
                                "A selected Library directory exceeds its entry limit"
                            )
                        }
                        val documentId = cursor.getString(idIndex).orEmpty()
                        val displayName = cursor.getString(nameIndex).orEmpty()
                        val mimeType = cursor.getString(mimeIndex).orEmpty()
                        if (
                            !selectedDocumentMetadataIsValid(documentId) ||
                            !selectedDocumentMetadataIsValid(displayName) ||
                            !selectedMimeTypeMetadataIsValid(mimeType)
                        ) {
                            throw unsupportedStorageLayout(
                                "Document metadata identity is blank or ambiguous"
                            )
                        }
                        val directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (
                            !directory && sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                        ) {
                            cursor.getLong(sizeIndex).takeIf { it >= 0L }
                        } else {
                            null
                        }
                        val row = StorageDocumentMetadata(documentId, displayName, directory, size)
                        if (retain(row)) {
                            if (!documentIds.add(documentId) || !displayNames.add(displayName))
                                throw unsupportedStorageLayout("Document metadata identity is ambiguous")
                            add(row)
                        }
                    }
                    cursor.requireSuccessfulSelectedLibraryQuery()
                }
        } ?: throw IOException("Document provider returned no metadata cursor")
    }

    private fun unsupportedStorageLayout(message: String): LibraryRootException =
        LibraryRootException("ROOT_LAYOUT_UNSUPPORTED", message)

    suspend fun scanAppPrivateStorage(): AppPrivateScan {
        val databaseDirectory = context.getDatabasePath("stemwise.db").parentFile
        val sharedPreferencesDirectory = File(context.applicationInfo.dataDir, "shared_prefs")
        val roots = listOfNotNull(
            databaseDirectory?.let { it to "database" },
            context.filesDir to "working",
            context.noBackupFilesDir to "working",
            sharedPreferencesDirectory to "working",
            context.cacheDir to "cache",
            context.codeCacheDir to "cache"
        ).distinctBy { it.first.absolutePath }
        val visited = hashSetOf<String>()
        var databaseBytes = 0L
        var workingBytes = 0L
        var cacheBytes = 0L
        var inspectedFiles = 0
        roots.forEach { (directory, category) ->
            if (!directory.exists()) return@forEach
            for (file in directory.walkTopDown()) {
                if (!file.isFile) continue
                if (++inspectedFiles % 32 == 0) {
                    storageScanCheckpoint()
                }
                val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                if (!visited.add(path)) continue
                val bytes = file.length().coerceAtLeast(0L)
                when (category) {
                    "database" -> databaseBytes = Math.addExact(databaseBytes, bytes)
                    "cache" -> cacheBytes = Math.addExact(cacheBytes, bytes)
                    else -> workingBytes = Math.addExact(workingBytes, bytes)
                }
            }
        }
        return AppPrivateScan(
            totalBytes = Math.addExact(Math.addExact(databaseBytes, workingBytes), cacheBytes),
            databaseBytes = databaseBytes,
            workingBytes = workingBytes,
            cacheBytes = cacheBytes
        )
    }

    private suspend fun storageScanCheckpoint() {
        backgroundWorkCoordinator.checkpoint()
    }

    data class RootStorageScan(
        val songsFound: Int,
        val originalFiles: Int,
        val stemFiles: Int,
        val artworkFiles: Int,
        val jsonFiles: Int,
        val actualBytes: Long,
        val unknownSizeFiles: Int,
        val unreadableDirectories: Int,
    )

    data class AppPrivateScan(
        val totalBytes: Long,
        val databaseBytes: Long,
        val workingBytes: Long,
        val cacheBytes: Long
    )


    companion object {
        private val STORAGE_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
