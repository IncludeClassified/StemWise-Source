package app.stemwise.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import app.stemwise.integration.queryCancellable
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.model.StemSourceCatalogue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** The complete user-visible selected-folder contract for SW-LIBRARY-STORAGE-0001. */
object SelectedLibraryContract {
    const val MAXIMUM_METADATA_CHARACTERS = 4_096
    const val MAXIMUM_SONG_PACKAGE_FILES = 28 + 2 * StemSourceCatalogue.MAX_TARGETS
    const val MAXIMUM_MIME_TYPE_CHARACTERS = 256
    val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    const val ORIGINAL = "original.opus"
    const val ORIGINAL_FLAC = "original.flac"
    val originalFiles = setOf(ORIGINAL, ORIGINAL_FLAC)
    const val COVER = "cover.png"
    const val ENRICHMENT = "song.json"
    const val ENRICHMENT_TRANSACTION = ".stemwise-enrichment-transaction.json"
    const val ENRICHMENT_TRANSACTION_TEMP = ".stemwise-enrichment-transaction.json.tmp"
    const val ENRICHMENT_JSON_BACKUP = ".stemwise-enrichment.json.backup"
    const val ENRICHMENT_JSON_REPLACEMENT = ".stemwise-enrichment.json.replacement"
    const val ENRICHMENT_COVER_BACKUP = ".stemwise-cover.png.backup"
    const val ENRICHMENT_COVER_REPLACEMENT = ".stemwise-cover.png.replacement"
    const val CUSTOM_SOURCE_MANIFEST = ".stemwise-sources.json"
    const val CUSTOM_SOURCE_MANIFEST_TEMP = ".stemwise-sources.json.tmp"
    const val SOURCE_JOB_AUTHORITY = ".stemwise-source-job.json"
    const val SOURCE_JOB_AUTHORITY_TEMP = ".stemwise-source-job.json.tmp"
    const val REPLACEMENT_SOURCE_JOB_AUTHORITY = ".stemwise-replacement-source-job.json"
    const val REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP = ".stemwise-replacement-source-job.json.tmp"
    const val CUSTOM_SOURCE_MANIFEST_BACKUP = ".stemwise-sources.json.backup"

    const val ROOT_MANIFEST = ".stemwise-library.json"
    const val ROOT_MANIFEST_BACKUP = ".stemwise-library.json.bak"
    const val ROOT_MANIFEST_TEMP = ".stemwise-library.json.tmp"
    const val DELETION_TOMBSTONES = ".stemwise-deletion-tombstones.json"
    const val DELETION_TOMBSTONES_TEMP = ".stemwise-deletion-tombstones.json.tmp"
    // Permanent 3.7.7 schema-v1 owner filenames; future schemas must add new versioned names.
    const val YT_DLP_AUTHORITY = ".stemwise-ytdlp-authority.json"
    const val YT_DLP_AUTHORITY_BACKUP = ".stemwise-ytdlp-authority.json.bak"
    const val YT_DLP_AUTHORITY_TEMP = ".stemwise-ytdlp-authority.json.tmp"

    val customTargetFiles = (1..StemSourceCatalogue.MAX_TARGETS).map { "part-$it.flac" }
    const val REMAINING_FILE = "remaining.wv"
    val replacementTargetFiles = (1..StemSourceCatalogue.MAX_TARGETS).map { "replacement-part-$it.flac" }
    const val REPLACEMENT_REMAINING_FILE = "replacement-remaining.wv"
    val customStemFiles = customTargetFiles + REMAINING_FILE + replacementTargetFiles + REPLACEMENT_REMAINING_FILE
    val allMediaFiles = originalFiles + customStemFiles
    val enrichmentFiles = setOf(COVER, ENRICHMENT)
    val enrichmentTransactionMarkers = setOf(
        ENRICHMENT_TRANSACTION,
        ENRICHMENT_TRANSACTION_TEMP
    )
    val allowedFiles = (allMediaFiles + enrichmentFiles + CUSTOM_SOURCE_MANIFEST).toSet()
    val internalPackageFiles = setOf(
        ENRICHMENT_TRANSACTION,
        ENRICHMENT_TRANSACTION_TEMP,
        ENRICHMENT_JSON_BACKUP,
        ENRICHMENT_JSON_REPLACEMENT,
        ENRICHMENT_COVER_BACKUP,
        ENRICHMENT_COVER_REPLACEMENT,
        CUSTOM_SOURCE_MANIFEST_TEMP,
        SOURCE_JOB_AUTHORITY,
        SOURCE_JOB_AUTHORITY_TEMP,
        REPLACEMENT_SOURCE_JOB_AUTHORITY,
        REPLACEMENT_SOURCE_JOB_AUTHORITY_TEMP,
        CUSTOM_SOURCE_MANIFEST_BACKUP
    )
    val packageFiles = allowedFiles + internalPackageFiles
    val maximumPackageDirectoryEntries = packageFiles.size * 2
    val rootAuthorityFiles = setOf(
        ROOT_MANIFEST,
        ROOT_MANIFEST_BACKUP,
        ROOT_MANIFEST_TEMP,
        DELETION_TOMBSTONES,
        DELETION_TOMBSTONES_TEMP,
        YT_DLP_AUTHORITY,
        YT_DLP_AUTHORITY_BACKUP,
        YT_DLP_AUTHORITY_TEMP
    )

    internal fun publicationStagingFileName(value: String): String? =
        Regex("\\.stemwise-asset-[a-f0-9]{32}-(.+)").matchEntire(value)?.groupValues?.get(1)
            ?.takeIf(packageFiles::contains)

    internal fun isPublicationStagingName(value: String): Boolean = publicationStagingFileName(value) != null

    fun isVideoId(value: String): Boolean = videoIdPattern.matches(value)

}

/** One exact provider row. Identity and URI are carried instead of being looked up by name. */
internal data class SelectedDocumentSnapshot(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long?,
    val uri: Uri,
    val document: DocumentFile
) {
    val documentId: String
        get() = DocumentsContract.getDocumentId(uri)

    val directory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

internal data class SelectedSongSnapshot(
    val directory: SelectedDocumentSnapshot,
    val files: Map<String, SelectedDocumentSnapshot>,
    val inspection: SelectedSongFolderInspection
) {
    val videoId: String get() = directory.displayName
    val original: SelectedDocumentSnapshot? get() = inspection.originalFileName?.let(files::get)
    fun file(name: String): SelectedDocumentSnapshot? = files[name]
}

internal data class SelectedLibrarySnapshot(
    val rootUri: Uri,
    val rootDocumentId: String,
    val songDirectories: List<SelectedDocumentSnapshot>,
    val rootFiles: Map<String, SelectedDocumentSnapshot> = emptyMap(),
    val totalEntryCount: Int = songDirectories.size + rootFiles.size
)

internal data class ProviderDocumentIdentity(
    val sha256: String,
    val byteLength: Long
)

/** Provider metadata may omit size; the opened bytes remain authoritative. */
internal suspend fun measureProviderDocument(
    context: Context,
    uri: Uri,
    metadataSizeBytes: Long? = null,
    maximumBytes: Long = Long.MAX_VALUE,
    requireNonEmpty: Boolean = true,
    checkpoint: suspend () -> Unit = {}
): ProviderDocumentIdentity {
    require(maximumBytes > 0L)
    metadataSizeBytes?.let { check(it in (if (requireNonEmpty) 1L else 0L)..maximumBytes) { "ASSET_SIZE_MISMATCH" } }
    val digest = MessageDigest.getInstance("SHA-256")
    var byteLength = 0L
    context.contentResolver.withCancellableProviderInput(uri) { _, read ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            byteLength = Math.addExact(byteLength, count.toLong())
            check(byteLength <= maximumBytes) { "ASSET_SIZE_MISMATCH" }
            digest.update(buffer, 0, count)
            checkpoint()
        }
    } ?: error("ASSET_MISSING")
    if (requireNonEmpty) check(byteLength > 0L) { "ASSET_MISSING" }
    metadataSizeBytes?.let { expected ->
        check(byteLength == expected) { "ASSET_SIZE_MISMATCH" }
    }
    return ProviderDocumentIdentity(
        sha256 = digest.digest().toHexString(),
        byteLength = byteLength
    )
}

internal suspend fun measureSelectedLibraryDocument(
    context: Context,
    file: SelectedDocumentSnapshot,
    checkpoint: suspend () -> Unit = {}
): ProviderDocumentIdentity = measureProviderDocument(
    context = context,
    uri = file.uri,
    metadataSizeBytes = file.sizeBytes,
    checkpoint = checkpoint
)

internal suspend fun readBoundedProviderDocument(
    context: Context,
    uri: Uri,
    metadataSizeBytes: Long?,
    maximumBytes: Long,
    requireNonEmpty: Boolean = true,
    checkpoint: suspend () -> Unit = {}
): ByteArray {
    require(maximumBytes > 0L)
    metadataSizeBytes?.let {
        check(it in (if (requireNonEmpty) 1L else 0L)..maximumBytes) {
            "MANIFEST_TOO_LARGE"
        }
    }
    var copied = 0L
    val bytes = context.contentResolver.withCancellableProviderInput(uri) { _, read ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            check(copied <= maximumBytes) { "MANIFEST_TOO_LARGE" }
            output.write(buffer, 0, count)
            checkpoint()
        }
        output.toByteArray()
    } ?: error("ASSET_MISSING")
    metadataSizeBytes?.let { expected ->
        check(copied == expected) { "ASSET_SIZE_MISMATCH" }
    }
    if (requireNonEmpty) check(bytes.isNotEmpty()) { "MANIFEST_TOO_LARGE" }
    return bytes
}

/** A journal may observe a target between truncation and publication; provider failures still abort. */
internal suspend fun readEnrichmentRecoveryCandidate(
    context: Context,
    file: SelectedDocumentSnapshot,
    maximumBytes: Long,
    checkpoint: suspend () -> Unit = {}
): ByteArray? = try {
    readBoundedProviderDocument(context, file.uri, file.sizeBytes, maximumBytes,
        requireNonEmpty = false, checkpoint = checkpoint).takeIf { it.isNotEmpty() }
} catch (invalid: IllegalStateException) {
    if (invalid.message !in setOf("MANIFEST_TOO_LARGE", "ASSET_SIZE_MISMATCH")) throw invalid
    null
}

internal data class SelectedDocumentRow(
    val documentId: String?,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedEpochMs: Long? = null
)

/** A provider document is bound to one exact content authority and tree, not just a tree ID. */
internal fun selectedBoundUriMatchesRoot(rootUri: Uri, boundUri: Uri): Boolean {
    if (rootUri.scheme != "content" || boundUri.scheme != "content") return false
    val authority = rootUri.authority?.takeIf(String::isNotBlank) ?: return false
    if (boundUri.authority != authority) return false
    val rootTreeId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
    val boundTreeId = runCatching { DocumentsContract.getTreeDocumentId(boundUri) }.getOrNull()
    return rootTreeId != null &&
        selectedDocumentMetadataIsValid(rootTreeId) &&
        rootTreeId == boundTreeId
}

/** Pure validation shared by provider controls and the production cursor reader. */
internal fun validateUniqueDocumentRows(
    rows: List<SelectedDocumentRow>,
    maximumRows: Int = Int.MAX_VALUE
): List<SelectedDocumentRow> {
    require(maximumRows >= 0)
    check(rows.size <= maximumRows) { "ROOT_LAYOUT_UNSUPPORTED" }
    val ids = hashSetOf<String>()
    val names = hashSetOf<String>()
    return rows.map { row ->
        val id = row.documentId?.takeIf(::selectedDocumentMetadataIsValid)
            ?: error("ROOT_LAYOUT_UNSUPPORTED")
        val name = row.displayName?.takeIf(::selectedDocumentMetadataIsValid)
            ?: error("ROOT_LAYOUT_UNSUPPORTED")
        row.mimeType?.takeIf(::selectedMimeTypeMetadataIsValid)
            ?: error("ROOT_LAYOUT_UNSUPPORTED")
        check(ids.add(id) && names.add(name)) { "ROOT_LAYOUT_UNSUPPORTED" }
        row
    }
}

internal fun selectedDocumentMetadataIsValid(value: String): Boolean =
    value.isNotBlank() && value.length <= SelectedLibraryContract.MAXIMUM_METADATA_CHARACTERS

internal fun selectedMimeTypeMetadataIsValid(value: String): Boolean =
    value.isNotBlank() && value.length <= SelectedLibraryContract.MAXIMUM_MIME_TYPE_CHARACTERS

internal fun checkedSelectedDocumentRows(
    maximumRows: Int = Int.MAX_VALUE,
    query: () -> List<SelectedDocumentRow>?
): List<SelectedDocumentRow> = try {
    validateUniqueDocumentRows(
        query() ?: throw LibraryRootException(
            "ROOT_UNAVAILABLE",
            "The selected folder provider returned no cursor"
        ),
        maximumRows
    )
} catch (error: SecurityException) {
    throw LibraryRootException(
        "ROOT_PERMISSION_LOST",
        "StemWise no longer has permission to read the Library"
    ).also { it.addSuppressed(error) }
} catch (error: LibraryRootException) {
    throw error
} catch (error: IllegalStateException) {
    if (error.message == "ROOT_LAYOUT_UNSUPPORTED") {
        throw LibraryRootException(
            "ROOT_LAYOUT_UNSUPPORTED",
            "The selected folder returned ambiguous document metadata"
        ).also { it.addSuppressed(error) }
    }
    throw LibraryRootException(
        "ROOT_UNAVAILABLE",
        "The selected folder provider could not be enumerated"
    ).also { it.addSuppressed(error) }
} catch (error: Throwable) {
    throw LibraryRootException(
        "ROOT_UNAVAILABLE",
        "The selected folder provider could not be enumerated"
    ).also { it.addSuppressed(error) }
}

/**
 * Some DocumentsProviders return an empty cursor plus EXTRA_ERROR when enumeration failed. That
 * cursor is not evidence of an empty directory and must never be promoted to VERIFIED_EMPTY.
 */
internal fun providerCursorReportedFailure(extras: Bundle): Boolean =
    extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
        extras.getString(DocumentsContract.EXTRA_ERROR)?.isNotBlank() == true

internal fun Cursor.requireSuccessfulSelectedLibraryQuery() {
    if (providerCursorReportedFailure(extras)) {
        throw LibraryRootException(
            "ROOT_UNAVAILABLE",
            "The selected folder provider reported an enumeration failure"
        )
    }
}

/**
 * Checked SAF enumeration. A null cursor/exception is provider unavailability, while complete
 * but ambiguous rows are an unsafe layout. Every later consumer receives the exact document URI
 * produced from the validated row; no name lookup participates in this snapshot.
 */
internal class SelectedLibrarySnapshotReader(
    context: Context,
    private val queryOverride: ((treeUri: Uri, parentDocumentId: String) -> List<SelectedDocumentRow>?)? =
        null
) {
    private val applicationContext = context.applicationContext

    suspend fun read(root: DocumentFile): SelectedLibrarySnapshot = checkedSnapshotBoundary {
        val rootId = documentId(root.uri)
        val (entryCount, rootEntries) = readRootInventoryUnchecked(root)
        SelectedLibrarySnapshot(root.uri, rootId,
            rootEntries.filter { it.directory && SelectedLibraryContract.isVideoId(it.displayName) },
            rootEntries.filterNot(SelectedDocumentSnapshot::directory)
                .associateBy(SelectedDocumentSnapshot::displayName),
            totalEntryCount = entryCount)
    }

    /** Root-only identity/name snapshot for bounded absence and capacity checks. */
    suspend fun readRootDirectories(root: DocumentFile): List<SelectedDocumentSnapshot> =
        checkedSnapshotBoundary {
            readRootEntriesUnchecked(root).filter { it.directory && SelectedLibraryContract.isVideoId(it.displayName) }
        }

    suspend fun readRootEntries(root: DocumentFile): List<SelectedDocumentSnapshot> =
        checkedSnapshotBoundary { readRootEntriesUnchecked(root) }

    suspend fun readSong(
        root: DocumentFile,
        videoId: String,
    ): SelectedSongSnapshot? {
        check(SelectedLibraryContract.isVideoId(videoId)) { "PACKAGE_ID_INVALID" }
        return checkedSnapshotBoundary {
            readSongUnchecked(root, videoId)
        }
    }

    /**
     * Reopens one already-durable song directory by its provider document identity. This path
     * deliberately does not enumerate the root first: Android providers may transiently omit an
     * existing child from a parent listing even though its bound document URI remains readable.
     */
    suspend fun readBoundSong(
        root: DocumentFile,
        videoId: String,
        boundDirectoryUri: Uri,
    ): SelectedSongSnapshot {
        check(SelectedLibraryContract.isVideoId(videoId)) { "PACKAGE_ID_INVALID" }
        return checkedSnapshotBoundary {
            if (!selectedBoundUriMatchesRoot(root.uri, boundDirectoryUri)) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                    "The durable song directory belongs to a different selected tree"
                )
            }
            val expectedDocumentId = documentId(boundDirectoryUri)
            val directory = readExactDocument(root.uri, boundDirectoryUri)
            if (
                directory.documentId != expectedDocumentId ||
                !directory.directory ||
                directory.displayName != videoId
            ) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                    "The durable song directory identity no longer matches its package"
                )
            }
            val files = materialize(
                root.uri,
                checkedChildren(root.uri, directory.documentId)
            )
            val inspection = inspectSelectedSongEntryNames(
                videoId,
                files.filter { !it.directory }.map(SelectedDocumentSnapshot::displayName),
            )
            SelectedSongSnapshot(
                directory = directory,
                files = files.associateBy(SelectedDocumentSnapshot::displayName),
                inspection = inspection
            )
        }
    }

    /** Reads a just-created directory by returned URI so cleanup never relies on a name pattern. */
    suspend fun readBoundDirectory(
        root: DocumentFile,
        boundDirectoryUri: Uri
    ): SelectedDocumentSnapshot =
        checkedSnapshotBoundary {
            if (!selectedBoundUriMatchesRoot(root.uri, boundDirectoryUri)) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                    "The returned directory belongs to a different selected tree"
                )
            }
            val expectedDocumentId = documentId(boundDirectoryUri)
            readExactDocument(root.uri, boundDirectoryUri).also { directory ->
                if (directory.documentId != expectedDocumentId || !directory.directory) {
                    throw LibraryRootException(
                        "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                        "The returned provider identity changed or is not a directory"
                    )
                }
            }
        }

    suspend fun boundDirectoryIsEmpty(root: DocumentFile, boundDirectoryUri: Uri): Boolean =
        checkedSnapshotBoundary {
            val directory = readBoundDirectory(root, boundDirectoryUri)
            checkedChildren(root.uri, directory.documentId).isEmpty()
        }

    /** Exact document-ID presence only; names are neither interpreted nor used for cleanup. */
    suspend fun rootContainsBoundDirectoryIdentity(
        root: DocumentFile,
        boundDirectoryUri: Uri
    ): Boolean =
        checkedSnapshotBoundary {
            if (!selectedBoundUriMatchesRoot(root.uri, boundDirectoryUri)) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                    "The returned directory belongs to a different selected tree"
                )
            }
            val expectedDocumentId = documentId(boundDirectoryUri)
            checkedChildren(root.uri, documentId(root.uri), retain = { it.documentId == expectedDocumentId })
                .isNotEmpty()
        }

    private suspend fun readSongUnchecked(
        root: DocumentFile,
        videoId: String,
    ): SelectedSongSnapshot? {
        val rootRows = readRootEntriesUnchecked(root)
            .filter(SelectedDocumentSnapshot::directory)
        val directory = rootRows.singleOrNull { it.displayName == videoId } ?: return null
        val files = materialize(root.uri, checkedChildren(root.uri, directory.documentId))
        val inspection = inspectSelectedSongEntryNames(
            videoId,
            files.filter { !it.directory }.map(SelectedDocumentSnapshot::displayName),
        )
        return SelectedSongSnapshot(
            directory,
            files.associateBy(SelectedDocumentSnapshot::displayName),
            inspection
        )
    }

    private suspend fun readRootInventoryUnchecked(root: DocumentFile): Pair<Int, List<SelectedDocumentSnapshot>> {
        var entryCount = 0
        val rows = checkedChildren(root.uri, documentId(root.uri), retain = { row ->
            entryCount = Math.addExact(entryCount, 1)
            (row.mimeType == DocumentsContract.Document.MIME_TYPE_DIR &&
                row.displayName?.let(SelectedLibraryContract::isVideoId) == true) ||
                row.displayName in SelectedLibraryContract.rootAuthorityFiles
        })
        return entryCount to materialize(root.uri, rows)
    }

    private suspend fun readRootEntriesUnchecked(root: DocumentFile): List<SelectedDocumentSnapshot> =
        readRootInventoryUnchecked(root).second

    private suspend inline fun <T> checkedSnapshotBoundary(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SecurityException) {
        throw snapshotFailure(
            "ROOT_PERMISSION_LOST",
            "StemWise no longer has permission to read the Library",
            error
        )
    } catch (error: LibraryRootException) {
        throw error
    } catch (error: IllegalStateException) {
        if (error.message == "ROOT_LAYOUT_UNSUPPORTED") {
            throw snapshotFailure(
                "ROOT_LAYOUT_UNSUPPORTED",
                "The selected folder contains an unsupported or ambiguous layout",
                error
            )
        }
        throw snapshotFailure(
            "ROOT_UNAVAILABLE",
            "The selected folder changed before its checked snapshot completed",
            error
        )
    } catch (error: Throwable) {
        throw snapshotFailure(
            "ROOT_UNAVAILABLE",
            "The selected folder changed before its checked snapshot completed",
            error
        )
    }

    private fun snapshotFailure(
        code: String,
        message: String,
        cause: Throwable
    ): LibraryRootException = LibraryRootException(code, message).also {
        it.initCause(cause)
    }

    private suspend fun checkedChildren(
        treeUri: Uri,
        parentDocumentId: String,
        retain: (SelectedDocumentRow) -> Boolean = { true }
    ): List<SelectedDocumentRow> = try {
        val rootDocumentId = documentId(treeUri)
        val enumeratesRoot = parentDocumentId == rootDocumentId
        val maximumRows = if (enumeratesRoot) {
            Int.MAX_VALUE
        } else {
            SelectedLibraryContract.maximumPackageDirectoryEntries
        }
        val rows = queryRows(treeUri, parentDocumentId, maximumRows, retain)
        checkedSelectedDocumentRows(maximumRows) { rows }
    } catch (error: SecurityException) {
        val rootDocumentId = documentId(treeUri)
        if (
            parentDocumentId != rootDocumentId &&
            exactParentIsNowAbsent(treeUri, rootDocumentId, parentDocumentId)
        ) {
            throw snapshotFailure(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "A selected song folder disappeared during its checked snapshot",
                error
            )
        }
        throw error
    } catch (error: LibraryRootException) {
        val rootDocumentId = documentId(treeUri)
        if (
            error.code == "ROOT_PERMISSION_LOST" &&
            parentDocumentId != rootDocumentId &&
            exactParentIsNowAbsent(treeUri, rootDocumentId, parentDocumentId)
        ) {
            throw snapshotFailure(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "A selected song folder disappeared during its checked snapshot",
                error
            )
        }
        throw error
    }

    private suspend fun queryRows(
        treeUri: Uri,
        parentDocumentId: String,
        maximumRows: Int,
        retain: (SelectedDocumentRow) -> Boolean
    ): List<SelectedDocumentRow>? = if (queryOverride != null) {
        queryOverride.invoke(treeUri, parentDocumentId)?.filter { row ->
            validateUniqueDocumentRows(listOf(row), 1)
            retain(row)
        }
    } else {
        queryProvider(treeUri, parentDocumentId, maximumRows, retain)
    }

    /**
     * Android can report a now-missing descendant as a SecurityException while enforcing a tree
     * URI. Re-enumerating the still-readable root distinguishes that stale child identity from an
     * independently observed permission failure. Only an exact missing parent changes taxonomy.
     */
    private suspend fun exactParentIsNowAbsent(
        treeUri: Uri,
        rootDocumentId: String,
        expectedParentDocumentId: String
    ): Boolean = try {
        checkedChildren(treeUri, rootDocumentId, retain = { it.documentId == expectedParentDocumentId })
            .isEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LibraryRootException) {
        false
    } catch (_: Throwable) {
        false
    }

    private suspend fun queryProvider(
        treeUri: Uri,
        parentDocumentId: String,
        maximumRows: Int,
        retain: (SelectedDocumentRow) -> Boolean
    ): List<SelectedDocumentRow> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        )
        return applicationContext.contentResolver.queryCancellable(
            childrenUri,
            PROJECTION
        ) {
            it.requireSuccessfulSelectedLibraryQuery()
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = it.getColumnIndex(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            check(idIndex >= 0 && nameIndex >= 0 && mimeIndex >= 0) {
                "ROOT_LAYOUT_UNSUPPORTED"
            }
            buildList {
                while (it.moveToNext()) {
                    check(size < maximumRows) { "ROOT_LAYOUT_UNSUPPORTED" }
                    val documentId = it.getString(idIndex)
                    val displayName = it.getString(nameIndex)
                    val mimeType = it.getString(mimeIndex)
                    check(
                        documentId != null && selectedDocumentMetadataIsValid(documentId) &&
                            displayName != null && selectedDocumentMetadataIsValid(displayName) &&
                            mimeType != null && selectedMimeTypeMetadataIsValid(mimeType)
                    ) { "ROOT_LAYOUT_UNSUPPORTED" }
                    val row = SelectedDocumentRow(
                            documentId = documentId,
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                                it.getLong(sizeIndex).takeIf { value -> value >= 0L }
                            } else {
                                null
                            },
                            lastModifiedEpochMs = if (
                                modifiedIndex >= 0 && !it.isNull(modifiedIndex)
                            ) {
                                it.getLong(modifiedIndex).takeIf { value -> value >= 0L }
                            } else {
                                null
                            }
                        )
                    if (retain(row)) add(row)
                }
                it.requireSuccessfulSelectedLibraryQuery()
            }
        } ?: throw LibraryRootException(
            "ROOT_UNAVAILABLE",
            "The selected folder provider returned no cursor"
        )
    }

    private suspend fun readExactDocument(
        treeUri: Uri,
        exactUri: Uri
    ): SelectedDocumentSnapshot {
        val rows = try {
            applicationContext.contentResolver.queryCancellable(
                exactUri,
                PROJECTION
            ) {
                it.requireSuccessfulSelectedLibraryQuery()
                val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = it.getColumnIndex(
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                check(idIndex >= 0 && nameIndex >= 0 && mimeIndex >= 0) {
                    "ROOT_LAYOUT_UNSUPPORTED"
                }
                buildList<SelectedDocumentRow> {
                    while (it.moveToNext()) {
                        check(size < 1) { "ROOT_LAYOUT_UNSUPPORTED" }
                        val documentId = it.getString(idIndex)
                        val displayName = it.getString(nameIndex)
                        val mimeType = it.getString(mimeIndex)
                        check(
                            documentId != null && selectedDocumentMetadataIsValid(documentId) &&
                                displayName != null && selectedDocumentMetadataIsValid(displayName) &&
                            mimeType != null && selectedMimeTypeMetadataIsValid(mimeType)
                        ) { "ROOT_LAYOUT_UNSUPPORTED" }
                        add(
                            SelectedDocumentRow(
                                documentId = documentId,
                                displayName = displayName,
                                mimeType = mimeType,
                                sizeBytes = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                                    it.getLong(sizeIndex).takeIf { value -> value >= 0L }
                                } else null,
                                lastModifiedEpochMs = if (
                                    modifiedIndex >= 0 && !it.isNull(modifiedIndex)
                                ) {
                                    it.getLong(modifiedIndex).takeIf { value -> value >= 0L }
                                } else null
                            )
                        )
                    }
                }
            }
        } catch (error: SecurityException) {
            val rootDocumentId = documentId(treeUri)
            val expectedDocumentId = documentId(exactUri)
            if (
                expectedDocumentId != rootDocumentId &&
                exactParentIsNowAbsent(treeUri, rootDocumentId, expectedDocumentId)
            ) {
                throw LibraryRootException(
                    "PACKAGE_DIRECTORY_UNAVAILABLE",
                    "The exact song directory no longer exists"
                ).also { it.initCause(error) }
            }
            throw error
        } catch (error: FileNotFoundException) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "The exact song directory no longer exists"
            ).also { it.initCause(error) }
        } ?: throw LibraryRootException(
            "PACKAGE_DIRECTORY_UNAVAILABLE",
            "The durable song directory provider returned no cursor"
        )
        if (rows.isEmpty()) {
            throw LibraryRootException(
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                "The durable song directory is temporarily unavailable"
            )
        }
        return materialize(treeUri, validateUniqueDocumentRows(rows, maximumRows = 1)).single()
    }

    private fun materialize(
        treeUri: Uri,
        rows: List<SelectedDocumentRow>
    ): List<SelectedDocumentSnapshot> = rows.map { row ->
        val id = checkNotNull(row.documentId)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
        val document = DocumentFile.fromSingleUri(applicationContext, uri)
            ?: throw IOException("The selected document disappeared")
        SelectedDocumentSnapshot(
            displayName = checkNotNull(row.displayName),
            mimeType = checkNotNull(row.mimeType),
            sizeBytes = row.sizeBytes,
            lastModifiedEpochMs = row.lastModifiedEpochMs,
            uri = uri,
            document = document
        )
    }

    private fun documentId(uri: Uri): String = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrElse { DocumentsContract.getTreeDocumentId(uri) }
        .takeIf(::selectedDocumentMetadataIsValid) ?: error("ROOT_LAYOUT_UNSUPPORTED")

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}

private fun hasCompleteSourceBank(present: Set<String>, replacement: Boolean): Boolean {
    val targets = if (replacement) SelectedLibraryContract.replacementTargetFiles else SelectedLibraryContract.customTargetFiles
    val remaining = if (replacement) SelectedLibraryContract.REPLACEMENT_REMAINING_FILE else SelectedLibraryContract.REMAINING_FILE
    val count = targets.count(present::contains)
    return SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST in present && remaining in present &&
        count in 1..StemSourceCatalogue.MAX_TARGETS && targets.take(count).all(present::contains)
}

internal fun inspectSelectedSongEntryNames(
    videoId: String,
    entryNames: List<String>,
): SelectedSongFolderInspection {
    val present = entryNames.toSet()
    val canonical = entryNames.filterTo(linkedSetOf()) {
        it in SelectedLibraryContract.allowedFiles
    }
    val unsupported = !SelectedLibraryContract.isVideoId(videoId) ||
        entryNames.size != present.size ||
        SelectedLibraryContract.originalFiles.count(present::contains) > 1
    val hasOriginal = SelectedLibraryContract.originalFiles.any(canonical::contains)
    val customFiles = canonical.intersect(SelectedLibraryContract.customStemFiles.toSet())
    val hasCustomNamespace = customFiles.isNotEmpty() ||
        SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST in present ||
        SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_TEMP in present
    val customComplete = hasCompleteSourceBank(present, false) || hasCompleteSourceBank(present, true)
    val state = when {
        unsupported -> SelectedSongFolderState.UNSUPPORTED
        hasCustomNamespace && !customComplete -> SelectedSongFolderState.INCOMPLETE
        hasOriginal ->
            SelectedSongFolderState.COMPLETE
        else -> SelectedSongFolderState.INCOMPLETE
    }
    return SelectedSongFolderInspection(videoId, state, canonical)
}

internal fun canonicalMediaMimeMatches(fileName: String, mime: String): Boolean = when (fileName) {
    SelectedLibraryContract.ORIGINAL -> mime == "audio/opus"
    SelectedLibraryContract.ORIGINAL_FLAC -> mime == "audio/flac"
    in SelectedLibraryContract.customTargetFiles, in SelectedLibraryContract.replacementTargetFiles -> mime == "audio/flac"
    SelectedLibraryContract.REMAINING_FILE, SelectedLibraryContract.REPLACEMENT_REMAINING_FILE -> mime == "audio/wavpack"
    else -> false
}

enum class SelectedSongFolderState { COMPLETE, INCOMPLETE, UNSUPPORTED }

data class SelectedSongFolderInspection(
    val videoId: String,
    val state: SelectedSongFolderState,
    val presentFiles: Set<String> = emptySet()
) {
    val originalFileName: String?
        get() = SelectedLibraryContract.originalFiles.singleOrNull(presentFiles::contains)

    val hasOriginal: Boolean
        get() = originalFileName != null

    val hasCustomSourceMedia: Boolean
        get() = SelectedLibraryContract.customStemFiles.any(presentFiles::contains)

    val hasCustomSourceManifest: Boolean
        get() = SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST in presentFiles

    /** Filename shape only. The manifest and each decoded asset still need full validation. */
    val hasStructurallyCompleteCustomSourceSet: Boolean
        get() = hasCustomSourceManifest &&
            (hasCompleteSourceBank(presentFiles, false) || hasCompleteSourceBank(presentFiles, true))



    val enriched: Boolean
        get() = SelectedLibraryContract.enrichmentFiles.all(presentFiles::contains)
}

private fun providerDocumentRole(name: String): String? =
    name.takeIf { it in SelectedLibraryContract.packageFiles || it in SelectedLibraryContract.rootAuthorityFiles }
        ?: SelectedLibraryContract.publicationStagingFileName(name)

private fun opaqueInternalSidecar(name: String, requested: String): Boolean =
    (name in SelectedLibraryContract.internalPackageFiles || name in SelectedLibraryContract.rootAuthorityFiles) &&
        (requested == "application/json" && name.contains(".json.") ||
            requested == "image/png" && name.contains(".png."))

/** File providers otherwise append the content extension to internal transaction filenames. */
internal fun providerDocumentCreationMime(name: String, requested: String, extensionMime: String? = null): String =
    if (providerDocumentRole(name)?.let { opaqueInternalSidecar(it, requested) } == true) {
        extensionMime ?: "application/octet-stream"
    } else {
        requested
    }

/** Provider metadata is separate from the subsequent byte/codec readback authority. */
internal fun providerCreatedDocumentMimeMatches(
    name: String,
    requested: String,
    reported: String,
    extensionMime: String? = null,
): Boolean {
    val canonicalName = providerDocumentRole(name) ?: return false
    return requested == reported ||
        opaqueInternalSidecar(canonicalName, requested) &&
            (reported == "application/octet-stream" || reported == extensionMime) ||
        canonicalName in setOf(SelectedLibraryContract.REMAINING_FILE, SelectedLibraryContract.REPLACEMENT_REMAINING_FILE) && requested == "audio/wavpack" &&
            reported in setOf("application/octet-stream", "audio/x-wavpack")
}
