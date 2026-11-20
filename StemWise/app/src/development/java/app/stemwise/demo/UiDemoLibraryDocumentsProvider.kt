package app.stemwise.demo

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.stemwise.BuildConfig
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.enrichment.ENRICHMENT_GENERATOR_NAME
import app.stemwise.enrichment.ENRICHMENT_GENERATOR_VERSION
import app.stemwise.enrichment.INSTALLED_ENRICHMENT_SCHEMA_VERSION
import app.stemwise.enrichment.INSTALLED_ENRICHMENT_CONTRACT
import app.stemwise.enrichment.installedEnrichmentDigest
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

internal fun uiDemoLibraryDurableMimeType(name: String): String? =
    name.lowercase().let {
        when {
            it.endsWith(".json") || ".json." in it -> "application/json"
            it.endsWith(".png") || ".png." in it -> "image/png"
            it.endsWith(".wv") || ".wv." in it -> "audio/wavpack"
            else -> null
        }
    }

internal fun uiDemoLibraryManifest(
    template: String,
    templateId: String,
    videoId: String,
    trackNumber: Int
): ByteArray {
    val manifest = JSONObject(template.replace(templateId, videoId))
    if (videoId != templateId) {
        manifest.getJSONObject("metadata").apply {
            put("title", "${getString("title")} · Demo ${trackNumber.toString().padStart(2, '0')}")
            getJSONObject("album").put("trackNumber", trackNumber).put("trackTotal", 10)
        }
    }
    manifest.getJSONObject("install").apply {
        put("contractVersion", INSTALLED_ENRICHMENT_CONTRACT)
        remove("generatedDigest")
        put("generatedDigest", installedEnrichmentDigest(manifest))
    }
    return manifest.toString().toByteArray(Charsets.UTF_8)
}

internal fun uiDemoLibrarySourceManifest(template: ByteArray, templateId: String, videoId: String): ByteArray {
    val sourceHash = JSONObject(template.toString(Charsets.UTF_8)).getString("sourceSha256")
    return parseCustomSourceManifest(template, templateId, sourceHash).copy(videoId = videoId).toBytes()
}

/** App-private fixture: ten enriched synthetic songs, each with eight lossless target lanes and Remaining. */
class UiDemoLibraryDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (method != INITIALIZE) return super.call(method, arg, extras)
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "DEMO_BACKGROUND_INITIALIZATION_REQUIRED" }
        synchronized(initializationLock) { seedMissingFixtures() }
        return android.os.Bundle.EMPTY
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(columns(projection, ROOT_PROJECTION)).apply {
            if (!rootDirectory().isDirectory) return@apply
            newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, ROOT_TITLE)
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDirectory().usableSpace)
        }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?
    ): Cursor = MatrixCursor(columns(projection, DOCUMENT_PROJECTION)).apply {
        includeDocument(this, documentId, requireDocument(documentId))
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = requireDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(columns(projection, DOCUMENT_PROJECTION)).apply {
            parent.listFiles()
                .orEmpty()
                .sortedWith(compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
                .forEach { child -> includeDocument(this, documentId(child), child) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = requireDocument(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val parent = requireDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        requireSafeName(displayName)
        val child = File(parent, displayName).canonicalFile
        requireInsideRoot(child)
        if (child.exists()) throw FileNotFoundException("Document already exists: $displayName")
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            child.mkdir()
        } else {
            child.parentFile?.mkdirs()
            child.createNewFile()
        }
        if (!created) throw FileNotFoundException("Could not create $displayName")
        return documentId(child)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = requireDocument(documentId)
        if (source == rootDirectory()) throw FileNotFoundException("The UI Demo Library cannot be renamed")
        requireSafeName(displayName)
        val destination = File(checkNotNull(source.parentFile), displayName).canonicalFile
        requireInsideRoot(destination)
        if (destination.exists() || !source.renameTo(destination)) throw FileNotFoundException("Could not rename $documentId")
        return documentId(destination)
    }

    override fun deleteDocument(documentId: String) {
        val target = requireDocument(documentId)
        if (target == rootDirectory()) throw FileNotFoundException("The UI Demo Library cannot be deleted")
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) throw FileNotFoundException("Could not delete $documentId")
    }

    override fun getDocumentType(documentId: String): String = mimeType(requireDocument(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = runCatching { requireDocument(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { requireDocument(documentId) }.getOrNull() ?: return false
        return child != parent && child.path.startsWith(parent.path + File.separator)
    }

    private fun seedMissingFixtures() {
        val installedRoot = rootDirectory()
        val root = if (installedRoot.isDirectory) installedRoot else
            File(installedRoot.parentFile, installedRoot.name + ".installing")
        check(root.mkdirs() || root.isDirectory)
        val assets = checkNotNull(context).assets
        FIXTURE_TEMPLATES.entries.forEachIndexed { index, (videoId, templateId) ->
            val folder = File(root, videoId)
            check(folder.mkdir() || folder.isDirectory)
            FIXTURE_FILES.forEach { name ->
                val target = File(folder, name)
                if (!target.isFile) {
                    val pending = File(folder, ".$name.pending")
                    // Android excludes dot-prefixed assets; retain the real package name in SAF.
                    val assetName = if (name == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST) "sources.json" else name
                    assets.open("$ASSET_ROOT/$templateId/$assetName").use { input ->
                        pending.outputStream().use { output ->
                            if (name == SelectedLibraryContract.ENRICHMENT) {
                                output.write(uiDemoLibraryManifest(
                                    template = input.bufferedReader().use { it.readText() },
                                    templateId = templateId,
                                    videoId = videoId,
                                    trackNumber = index + 1
                                ))
                            } else if (name == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST) {
                                output.write(uiDemoLibrarySourceManifest(input.readBytes(), templateId, videoId))
                            } else {
                                input.copyTo(output)
                            }
                        }
                    }
                    check(pending.renameTo(target))
                }
            }
        }
        if (root != installedRoot) check(root.renameTo(installedRoot)) { "DEMO_INITIALIZATION_FAILED" }
    }

    private fun includeDocument(cursor: MatrixCursor, id: String, file: File) {
        val directory = file.isDirectory
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                if (id == ROOT_ID) ROOT_TITLE else file.name
            )
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
            .add(
                DocumentsContract.Document.COLUMN_FLAGS,
                if (directory) {
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                        if (id == ROOT_ID) 0 else DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                } else {
                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                        DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                }
            )
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
    }

    private fun requireDocument(documentId: String): File {
        val root = rootDirectory()
        if (documentId == ROOT_ID) return root
        if (!documentId.startsWith("$ROOT_ID/")) throw FileNotFoundException(documentId)
        val file = File(root, documentId.removePrefix("$ROOT_ID/")).canonicalFile
        requireInsideRoot(file)
        if (!file.exists()) throw FileNotFoundException(documentId)
        return file
    }

    private fun requireInsideRoot(file: File) {
        val root = rootDirectory()
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Document escaped the UI Demo Library")
        }
    }

    private fun requireSafeName(name: String) {
        if (
            name.isBlank() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it == '\u0000' }
        ) throw FileNotFoundException("Unsafe display name")
    }

    private fun documentId(file: File): String {
        val root = rootDirectory()
        requireInsideRoot(file)
        return if (file == root) ROOT_ID else "$ROOT_ID/${file.relativeTo(root).invariantSeparatorsPath}"
    }

    private fun rootDirectory(): File = File(
        checkNotNull(context).noBackupFilesDir,
        "ui-demo-library-v$FIXTURE_VERSION-s$INSTALLED_ENRICHMENT_SCHEMA_VERSION-g$ENRICHMENT_GENERATOR_VERSION"
    ).canonicalFile

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        uiDemoLibraryDurableMimeType(file.name)?.let { return it }
        return when (file.extension.lowercase()) {
            "opus", "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
    }

    private fun columns(requested: Array<out String>?, defaults: Array<String>): Array<String> =
        requested?.map { it }?.toTypedArray() ?: defaults

    companion object {
        private val initializationLock = Any()
        private const val INITIALIZE = "initialize-demo-library"
        internal fun grantProcessAccess(context: android.content.Context) {
            context.grantUriPermission(context.packageName, treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        suspend fun initialize(context: android.content.Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            grantProcessAccess(context)
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), INITIALIZE, null, null)
                ?: error("DEMO_INITIALIZATION_FAILED")
        }
        private const val ROOT_ID = "root"
        private const val ROOT_TITLE = "StemWise UI Demo Library"
        private const val ASSET_ROOT = "demo_library"
        private const val FIXTURE_VERSION = 13
        internal val FIXTURE_FILES = listOf(
            SelectedLibraryContract.ORIGINAL, SelectedLibraryContract.COVER,
            SelectedLibraryContract.ENRICHMENT
        ) + SelectedLibraryContract.customTargetFiles + SelectedLibraryContract.REMAINING_FILE +
            SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST
        internal val FIXTURE_TEMPLATES = linkedMapOf(
            "DEMOUI00001" to "DEMOUI00001",
            "DEMOUI00002" to "DEMOUI00002",
            "DEMOUI00003" to "DEMOUI00003",
            "DEMOUI00004" to "DEMOUI00001",
            "DEMOUI00005" to "DEMOUI00002",
            "DEMOUI00006" to "DEMOUI00003",
            "DEMOUI00007" to "DEMOUI00001",
            "DEMOUI00008" to "DEMOUI00002",
            "DEMOUI00009" to "DEMOUI00003",
            "DEMOUI00010" to "DEMOUI00001"
        )
        val FIXTURE_IDS = FIXTURE_TEMPLATES.keys.toList()
        val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.ui.demo.library"
        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
