package app.stemwise.qa

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.stemwise.BuildConfig
import app.stemwise.data.SelectedLibraryContract
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Private filesystem-backed SAF provider included only in Development.
 *
 * It gives instrumentation the same DocumentFile boundary as a user-selected
 * folder without changing the real single-device Library selection. Release
 * builds do not contain this provider or its golden enrichment assets.
 */
class GoldenQaDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = rootDirectory().mkdirs() || rootDirectory().isDirectory

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(columns(projection, DEFAULT_ROOT_PROJECTION)).apply {
            newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(DocumentsContract.Root.COLUMN_TITLE, "StemWise golden QA")
                .add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                )
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDirectory().usableSpace)
        }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?
    ): Cursor {
        queryDocumentCalls.incrementAndGet()
        if (faultMode.get() == FAULT_DIRECTORY_DIRECT_LOOKUP_MISSING && documentId != ROOT_ID &&
            requireDocument(documentId).isDirectory) {
            throw FileNotFoundException("Injected direct lookup miss while parent still lists the directory")
        }
        if (
            faultMode.get() == FAULT_DIRECTORY_DELETE_FALSE_SUCCESS &&
            falseSuccessfulDirectoryDeleteId.get() == documentId
        ) {
            throw SecurityException("Injected stale exact-directory identity")
        }
        if (faultMode.get() == FAULT_DOCUMENT_SECURITY) {
            throw SecurityException("Injected document-metadata permission loss")
        }
        return MatrixCursor(columns(projection, DEFAULT_DOCUMENT_PROJECTION)).apply {
            val synthetic = syntheticDirectoryName(documentId)
            if (synthetic != null) includeDocument(this, documentId, rootDirectory(), synthetic,
                DocumentsContract.Document.MIME_TYPE_DIR)
            else includeDocument(this, documentId, requireDocument(documentId))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        queryChildCalls.incrementAndGet()
        queryChildParents.add(parentDocumentId)
        if (faultMode.get() == FAULT_BLOCK_STORAGE_QUERY && parentDocumentId == ROOT_ID &&
            projection?.size == 4 && DocumentsContract.Document.COLUMN_LAST_MODIFIED !in projection &&
            originalReadBlockClaimed.compareAndSet(false, true)) {
            importReadEntered.countDown()
            check(importReadReleased.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "QA_BLOCKED_CURSOR_TIMEOUT" }
        }
        when (faultMode.get()) {
            FAULT_THROW -> throw FileNotFoundException("Injected child-query failure")
            FAULT_NULL_CURSOR -> return null
            FAULT_NULL_POINTER_EXCEPTION -> throw NullPointerException("Injected child query exception")
            FAULT_SECURITY -> throw SecurityException("Injected persisted-permission loss")
        }
        val projectedColumns = columns(projection, DEFAULT_DOCUMENT_PROJECTION).let { columns ->
            if (faultMode.get() == FAULT_MISSING_COLUMN) {
                columns.filterNot {
                    it == DocumentsContract.Document.COLUMN_MIME_TYPE
                }.toTypedArray()
            } else {
                columns
            }
        }
        val cursor = MatrixCursor(projectedColumns).apply {
            setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        }
        if (faultMode.get() == FAULT_MISSING_COLUMN) return cursor
        if (faultMode.get() == FAULT_EMPTY) return cursor
        if (faultMode.get() == FAULT_EMPTY_WITH_ERROR) {
            cursor.setExtras(Bundle().apply {
                putString(DocumentsContract.EXTRA_ERROR, "Injected provider enumeration failure")
            })
            return cursor
        }
        if (faultMode.get() == FAULT_LOADING_CURSOR) {
            cursor.setExtras(Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, true)
            })
            return cursor
        }
        val syntheticRootRows = if (parentDocumentId == ROOT_ID) syntheticRootDirectoryCount() else 0
        if (syntheticRootRows > 0) {
            repeat(syntheticRootRows) { index ->
                val name = "Q${index.toString().padStart(10, '0')}"
                includeDocument(
                    cursor,
                    "$ROOT_ID/$name",
                    rootDirectory(),
                    name,
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
            if (faultMode.get() != FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED) {
                rootDirectory().listFiles().orEmpty().filter {
                    it.name in SelectedLibraryContract.rootAuthorityFiles
                }.sortedBy(File::getName).forEach { file ->
                    includeDocument(cursor, documentId(file), file)
                }
                return cursor
            }
        }
        if (syntheticDirectoryName(parentDocumentId) != null) return cursor
        if (
            faultMode.get() == FAULT_TOO_MANY_CHILD_ROWS &&
            parentDocumentId.count { it == '/' } == 1
        ) {
            val canonicalNames = SelectedLibraryContract.packageFiles.sorted()
            val stagingNames = canonicalNames.map { ".stemwise-asset-00000000000000000000000000000000-$it" }
            (canonicalNames + stagingNames + "overflow.bin")
                .forEach { name ->
                    includeDocument(
                        cursor,
                        "$parentDocumentId/$name",
                        rootDirectory(),
                        name,
                        "application/octet-stream"
                    )
                }
            return cursor
        }
        if (
            faultMode.get() in AUTO_RENAME_FAULTS &&
            parentDocumentId.count { it == '/' } == 1
        ) return cursor
        val parent = requireDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val children = parent.listFiles() ?: throw FileNotFoundException(
            "Could not enumerate $parentDocumentId"
        )
        val sorted = children
            .sortedWith(compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name })
        val visible = if (
            faultMode.get() == FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED &&
            parentDocumentId == ROOT_ID
        ) {
            sorted.filterNot { documentId(it) == capacityRaceHiddenDocumentId.get() }
        } else {
            sorted
        }
        val first = visible.firstOrNull()
        val recognizedDirectory = visible.firstOrNull {
            it.isDirectory && SelectedLibraryContract.isVideoId(it.name)
        }
        when (faultMode.get()) {
            FAULT_BLANK_ID -> first?.let { includeDocument(cursor, "", it) }
            FAULT_BLANK_NAME -> first?.let { includeDocument(cursor, documentId(it), it, "") }
            FAULT_BLANK_MIME -> first?.let {
                includeDocument(cursor, documentId(it), it, it.name, "")
            }
            FAULT_DUPLICATE_ID -> (recognizedDirectory ?: first)?.let {
                includeDocument(cursor, documentId(it), it)
                val alias = if (it.isDirectory && SelectedLibraryContract.isVideoId(it.name)) {
                    if (it.name == "Alias000001") "Alias000002" else "Alias000001"
                } else "${it.name}-alias"
                includeDocument(cursor, documentId(it), it, alias)
            }
            FAULT_DUPLICATE_NAME -> (recognizedDirectory ?: first)?.let {
                includeDocument(cursor, documentId(it), it)
                includeDocument(cursor, "${documentId(it)}/alias", it, it.name)
            }
            FAULT_DISAPPEARING_CHILD -> first?.takeIf {
                disappearanceInjected.compareAndSet(false, true)
            }?.let {
                includeDocument(cursor, documentId(it), it)
                check(if (it.isDirectory) it.deleteRecursively() else it.delete())
            } ?: visible.forEach { child -> includeDocument(cursor, documentId(child), child) }
            else -> visible.forEach { child -> includeDocument(cursor, documentId(child), child) }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (faultMode.get() == FAULT_OPEN_SECURITY) {
            throw SecurityException("Injected document-byte permission loss")
        }
        val file = requireDocument(documentId)
        if (file.isDirectory) throw FileNotFoundException(documentId)
        if (mode == "r" && (file.name in SelectedLibraryContract.customStemFiles &&
                faultMode.compareAndSet(FAULT_OPTIONAL_READ_ONCE, FAULT_NONE) ||
            file.name == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST &&
                faultMode.compareAndSet(FAULT_CUSTOM_MANIFEST_READ_ONCE, FAULT_NONE) ||
            file.name == SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP &&
                faultMode.compareAndSet(FAULT_CUSTOM_MANIFEST_BACKUP_READ_ONCE, FAULT_NONE))) {
            throw FileNotFoundException("Injected transient prepared-parts read failure")
        }
        if (mode == "r" && file.name in SelectedLibraryContract.originalFiles &&
            faultMode.compareAndSet(FAULT_ORIGINAL_READ_ONCE, FAULT_NONE)) {
            throw FileNotFoundException("Injected transient installed Original read failure")
        }
        if (mode == "r" && file.name in app.stemwise.integration.SOURCE_JOB_AUTHORITY_FILES &&
            faultMode.compareAndSet(FAULT_AUTHORITY_READ_ONCE, FAULT_NONE)) {
            throw FileNotFoundException("Injected transient portable owner read failure")
        }
        if (mode == "r" && file.name == SelectedLibraryContract.ENRICHMENT &&
            faultMode.compareAndSet(FAULT_MANIFEST_READ_ONCE, FAULT_NONE)) {
            throw FileNotFoundException("Injected transient manifest read failure")
        }
        if (mode == "r" && file.name in SelectedLibraryContract.originalFiles &&
            faultMode.get() == FAULT_ORIGINAL_SECOND_READ_ONCE && originalReadAttempts.incrementAndGet() == 2 &&
            faultMode.compareAndSet(FAULT_ORIGINAL_SECOND_READ_ONCE, FAULT_NONE)) {
            throw FileNotFoundException("Injected transient media validation read failure")
        }
        val tombstoneWriteFault = faultMode.get()
        val failTombstoneWrite = mode != "r" && (
            file.name == SelectedLibraryContract.DELETION_TOMBSTONES_TEMP &&
                tombstoneWriteFault in setOf(FAULT_TOMBSTONE_TEMP_EMPTY, FAULT_TOMBSTONE_TEMP_PARTIAL) ||
            file.name == SelectedLibraryContract.DELETION_TOMBSTONES &&
                tombstoneWriteFault == FAULT_TOMBSTONE_CANONICAL_PARTIAL)
        if (failTombstoneWrite) {
            val output = java.io.RandomAccessFile(file, "rw").apply { setLength(0L) }
            if (tombstoneWriteFault == FAULT_TOMBSTONE_TEMP_EMPTY) {
                output.close()
                throw FileNotFoundException("Injected interrupted tombstone creation")
            }
            val writer = android.os.HandlerThread("qa-tombstone-write").apply { start() }
            return try {
                checkNotNull(context).getSystemService(android.os.storage.StorageManager::class.java)
                    .openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                        object : android.os.ProxyFileDescriptorCallback() {
                            override fun onGetSize(): Long = output.length()
                            override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                                output.seek(offset)
                                output.write(data, 0, minOf(size, data.size).coerceAtLeast(1) / 2)
                                output.fd.sync()
                                throw android.system.ErrnoException("injected tombstone partial write", android.system.OsConstants.EIO)
                            }
                            override fun onRelease() { try { output.close() } finally { writer.quitSafely() } }
                        }, android.os.Handler(writer.looper))
            } catch (failure: Throwable) { output.close(); writer.quitSafely(); throw failure }
        }
        openDocumentCalls.incrementAndGet()
        if (mode == "r") openedDocumentIds.add(documentId)
        openedDocumentLengthBytes.addAndGet(file.length().coerceAtLeast(0L))
        if (file.name in SelectedLibraryContract.customStemFiles) openedOptionalMediaLengthBytes.addAndGet(file.length().coerceAtLeast(0L))
        val blockedImport = faultMode.get() == FAULT_BLOCK_OPTIONAL_READ && file.name in SelectedLibraryContract.customStemFiles &&
                originalReadBlockClaimed.compareAndSet(false, true) ||
            faultMode.get() == FAULT_BLOCK_IMPORT_READ && file.name == "blocked-import.zip" ||
            faultMode.get() == FAULT_BLOCK_ORIGINAL_READ && file.name in SelectedLibraryContract.originalFiles &&
                originalReadBlockClaimed.compareAndSet(false, true)
        val blockedReadEntered = importReadEntered
        val blockedReadReleased = importReadReleased
        val blockedReadRetired = importReadRetired
        if (faultMode.get() == FAULT_BLOCK_IMPORT_OPEN && file.name == "blocked-import.zip") {
            val cancellation = checkNotNull(signal) { "QA_IMPORT_CANCELLATION_SIGNAL_MISSING" }
            cancellation.setOnCancelListener { blockedReadReleased.countDown() }
            blockedReadEntered.countDown()
            try {
                check(blockedReadReleased.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "QA_BLOCKED_INPUT_TIMEOUT" }
                cancellation.throwIfCanceled()
            } finally {
                blockedReadRetired.countDown()
            }
        }
        if (mode != "r" || !measureReads.get() && !blockedImport) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        val input = java.io.RandomAccessFile(file, "r")
        val thread = android.os.HandlerThread("qa-document-reads").apply { start() }
        return try {
            checkNotNull(context).getSystemService(android.os.storage.StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : android.os.ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = input.length()
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (blockedImport) {
                            blockedReadEntered.countDown()
                            check(blockedReadReleased.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "QA_BLOCKED_INPUT_TIMEOUT" }
                        }
                        input.seek(offset)
                        val count = input.read(data, 0, minOf(size, data.size)).coerceAtLeast(0)
                        readDocumentBytes.addAndGet(count.toLong())
                        if (file.name in SelectedLibraryContract.allMediaFiles) readMediaBytes.addAndGet(count.toLong())
                        return count
                    }
                    override fun onRelease() {
                        try { input.close() } finally {
                            thread.quitSafely()
                            if (blockedImport) blockedReadRetired.countDown()
                        }
                    }
                }, android.os.Handler(thread.looper))
        } catch (failure: Throwable) { input.close(); thread.quitSafely(); throw failure }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        createDocumentCalls.incrementAndGet()
        when (faultMode.get()) {
            FAULT_CREATE_REFUSAL -> throw FileNotFoundException("Injected child-create refusal")
            FAULT_CREATE_SECURITY -> throw SecurityException("Injected child-create permission loss")
        }
        val parent = requireDocument(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        requireSafeName(displayName)
        val createdName = if (faultMode.get() == FAULT_EXTENSION_DERIVED_MIME) {
            // Known FileSystemProvider outcomes: a content MIME appends its missing extension.
            when {
                mimeType == "application/json" && !displayName.endsWith(".json") -> "$displayName.json"
                mimeType == "image/png" && !displayName.endsWith(".png") -> "$displayName.png"
                else -> displayName
            }
        } else displayName
        var child = File(parent, createdName).canonicalFile
        requireInsideRoot(child)
        if (
            child.exists() &&
            faultMode.get() in AUTO_RENAME_FAULTS
        ) {
            var suffix = 1
            do {
                child = File(parent, "$displayName ($suffix)").canonicalFile
                requireInsideRoot(child)
                suffix += 1
            } while (child.exists())
        } else if (child.exists()) {
            throw FileNotFoundException("Document already exists: $displayName")
        }
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            createDirectoryCalls.incrementAndGet()
        } else {
            createFileCalls.incrementAndGet()
        }
        val created = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            child.mkdir()
        } else {
            child.parentFile?.mkdirs()
            child.createNewFile()
        }
        if (!created) throw FileNotFoundException("Could not create $displayName")
        val createdId = documentId(child)
        if (faultMode.get() == FAULT_CREATE_DISAPPEAR) {
            check(if (child.isDirectory) child.deleteRecursively() else child.delete())
        } else if (
            faultMode.get() == FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED &&
            parentDocumentId == ROOT_ID &&
            mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        ) {
            capacityRaceHiddenDocumentId.set(createdId)
            check(File(parent, CAPACITY_RACE_SIBLING).mkdir())
        }
        return createdId
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = requireDocument(documentId)
        check(source.isFile && displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName)
        val destination = File(checkNotNull(source.parentFile), displayName).canonicalFile
        requireInsideRoot(destination)
        check(!destination.exists() && source.renameTo(destination)) { "Could not rename $documentId" }
        return documentId(destination)
    }

    override fun deleteDocument(documentId: String) {
        deleteDocumentCalls.incrementAndGet()
        if (faultMode.get() == FAULT_HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL) {
            throw FileNotFoundException("Injected exact delete refusal")
        }
        val target = requireDocument(documentId)
        if (target == rootDirectory()) throw FileNotFoundException("The QA root cannot be deleted")
        val deletingFile = target.isFile
        if (faultMode.get() == FAULT_DIRECTORY_DELETE_FALSE_SUCCESS && target.isDirectory) {
            falseSuccessfulDirectoryDeleteId.set(documentId)
            return
        }
        if (faultMode.get() == FAULT_FILE_DELETE_FALSE_SUCCESS && deletingFile) return
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!deleted) throw FileNotFoundException("Could not delete $documentId")
        if (deletingFile && target.parentFile != rootDirectory()) {
            faultMode.compareAndSet(FAULT_FILE_DELETE_CHILD_QUERY_UNAVAILABLE, FAULT_THROW)
        }
        if (
            faultMode.get() == FAULT_DELETE_ADDS_UNKNOWN_CHILD && deletingFile &&
            target.parentFile != rootDirectory()
        ) {
            val unknown = File(checkNotNull(target.parentFile), "unexpected-child.bin")
            if (!unknown.exists()) unknown.writeBytes("unexpected".toByteArray())
        }
    }

    override fun getDocumentType(documentId: String): String =
        if (syntheticDirectoryName(documentId) != null) DocumentsContract.Document.MIME_TYPE_DIR
        else mimeType(requireDocument(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_ID && syntheticDirectoryName(documentId) != null) return true
        val parent = runCatching { requireDocument(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { requireDocument(documentId) }.getOrNull() ?: return false
        return child != parent && child.path.startsWith(parent.path + File.separator)
    }

    override fun getDocumentMetadata(documentId: String): Bundle = Bundle().apply {
        putString("qaDisplayName", requireDocument(documentId).name)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method.startsWith("android:")) return super.call(method, arg, extras)
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid())
            throw SecurityException("QA controls require the Development app UID")
        if (method == METHOD_AWAIT_APPLICATION_STARTUP) {
            val application = checkNotNull(context).applicationContext
            if (application is app.stemwise.StemwiseApplication) kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(15_000L) { application.workerInitialization.await() }
            }
            return Bundle.EMPTY
        }
        if (method == METHOD_RESET) {
            faultMode.set(FAULT_NONE)
            measureReads.set(false)
            disappearanceInjected.set(false)
            falseSuccessfulDirectoryDeleteId.set(null)
            capacityRaceHiddenDocumentId.set(null)
            val root = rootDirectory()
            val children = root.listFiles() ?: error("Could not enumerate QA provider root")
            children.forEach { child ->
                check(if (child.isDirectory) child.deleteRecursively() else child.delete())
            }
            return Bundle.EMPTY
        }
        if (method == METHOD_MEASURE_READS) {
            measureReads.set(arg == "true")
            return Bundle.EMPTY
        }
        if (method == METHOD_AWAIT_IMPORT_READ) return Bundle().apply {
            putBoolean("entered", importReadEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        if (method == METHOD_RELEASE_IMPORT_READ) { importReadReleased.countDown(); return Bundle.EMPTY }
        if (method == METHOD_AWAIT_IMPORT_RETIREMENT) return Bundle().apply {
            putBoolean("retired", importReadRetired.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        if (method == METHOD_IMPORT_INPUT_STATE) return Bundle().apply {
            putBoolean("released", importReadReleased.count == 0L)
            putBoolean("retired", importReadRetired.count == 0L)
        }
        if (method == METHOD_SET_FAULT) {
            val requested = arg ?: FAULT_NONE
            check(requested in FAULTS) { "Unknown QA provider fault: $requested" }
            importReadReleased.countDown()
            importReadEntered = java.util.concurrent.CountDownLatch(1)
            importReadReleased = java.util.concurrent.CountDownLatch(1)
            importReadRetired = java.util.concurrent.CountDownLatch(1)
            originalReadBlockClaimed.set(false)
            originalReadAttempts.set(0)
            faultMode.set(requested)
            disappearanceInjected.set(false)
            falseSuccessfulDirectoryDeleteId.set(null)
            capacityRaceHiddenDocumentId.set(null)
            return Bundle.EMPTY
        }
        if (method == METHOD_RESET_METRICS) {
            resetMetrics()
            return Bundle.EMPTY
        }
        if (method == METHOD_METRICS) {
            return Bundle().apply {
                putLong("returnedDocumentRows", returnedDocumentRows.get())
                putLong("queryDocumentCalls", queryDocumentCalls.get())
                putLong("queryChildCalls", queryChildCalls.get())
                putStringArrayList("queryChildParents", ArrayList(queryChildParents))
                putLong("openDocumentCalls", openDocumentCalls.get())
                putStringArrayList("openedDocumentIds", ArrayList(openedDocumentIds))
                putLong("openedDocumentLengthBytes", openedDocumentLengthBytes.get())
                putLong("openedOptionalMediaLengthBytes", openedOptionalMediaLengthBytes.get())
                putLong("readDocumentBytes", readDocumentBytes.get())
                putLong("readMediaBytes", readMediaBytes.get())
                putLong("createDocumentCalls", createDocumentCalls.get())
                putLong("createDirectoryCalls", createDirectoryCalls.get())
                putLong("createFileCalls", createFileCalls.get())
                putLong("deleteDocumentCalls", deleteDocumentCalls.get())
            }
        }
        return super.call(method, arg, extras)
    }

    private fun includeDocument(
        cursor: MatrixCursor,
        id: String,
        file: File,
        displayName: String = if (id == ROOT_ID) "StemWiseGoldenQa" else file.name,
        contentType: String = mimeType(file)
    ) {
        val flags = if (file.isDirectory) {
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        } else {
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }
        returnedDocumentRows.incrementAndGet()
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                displayName
            )
            .add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (faultMode.get() == FAULT_OVERSIZED_MIME && id != ROOT_ID) {
                    "m".repeat(SelectedLibraryContract.MAXIMUM_MIME_TYPE_CHARACTERS + 1)
                } else {
                    contentType
                }
            )
            .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(
                DocumentsContract.Document.COLUMN_SIZE,
                if (file.isFile && faultMode.get() != FAULT_UNKNOWN_SIZE) file.length() else null
            )
    }

    private fun columns(
        requested: Array<out String>?,
        defaults: Array<String>
    ): Array<String> = requested?.map { it }?.toTypedArray() ?: defaults

    private fun syntheticRootDirectoryCount(): Int = when (faultMode.get()) {
        FAULT_TOO_MANY_ROOT_ROWS -> 8_192 + SelectedLibraryContract.rootAuthorityFiles.size + 1
        FAULT_AT_CAPACITY_ROOT_ROWS -> 8_192
        FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED -> 8_192 - 1
        else -> 0
    }

    private fun syntheticDirectoryName(documentId: String): String? {
        val name = documentId.removePrefix("$ROOT_ID/")
        if (documentId != "$ROOT_ID/$name" || name.length != 11 || name.firstOrNull() != 'Q') return null
        val index = name.drop(1).toIntOrNull() ?: return null
        return name.takeIf { index in 0 until syntheticRootDirectoryCount() &&
            name == "Q${index.toString().padStart(10, '0')}" }
    }

    private fun requireDocument(documentId: String): File {
        val root = rootDirectory()
        if (documentId == ROOT_ID) return root
        if (!documentId.startsWith("$ROOT_ID/")) throw FileNotFoundException(documentId)
        val relative = documentId.removePrefix("$ROOT_ID/")
        if (relative.isBlank()) throw FileNotFoundException(documentId)
        val file = File(root, relative).canonicalFile
        requireInsideRoot(file)
        if (!file.exists()) {
            if (faultMode.get() == FAULT_MISSING_DOCUMENT_SECURITY) throw SecurityException("Captured document no longer exists")
            throw FileNotFoundException(documentId)
        }
        return file
    }

    private fun requireInsideRoot(file: File) {
        val root = rootDirectory()
        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException("Document escaped the QA root")
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
        if (file == root) return ROOT_ID
        val relative = file.relativeTo(root).invariantSeparatorsPath
        return "$ROOT_ID/$relative"
    }

    private fun rootDirectory(): File = File(
        checkNotNull(context).noBackupFilesDir,
        "golden-enrichment-document-provider"
    ).canonicalFile

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val name = file.name.lowercase()
        if (faultMode.get() == FAULT_EXTENSION_DERIVED_MIME) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
        return when {
            name == "compressed-import.zip" -> "application/x-zip-compressed"
            name.endsWith(".json") || ".json." in name -> "application/json"
            name.endsWith(".png") || ".png." in name -> "image/png"
            name.endsWith(".opus") || ".opus." in name ||
                name.endsWith(".ogg") || ".ogg." in name -> "audio/ogg"
            name.endsWith(".wav") || ".wav." in name -> "audio/wav"
            name.endsWith(".flac") || ".flac." in name -> "audio/flac"
            name.endsWith(".wv") || ".wv." in name ->
                if (faultMode.get() == FAULT_GENERIC_REMAINING_MIME) "application/octet-stream" else "audio/wavpack"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
    }

    companion object {
        const val METHOD_AWAIT_APPLICATION_STARTUP = "await-application-startup"
        const val FAULT_GENERIC_REMAINING_MIME = "GENERIC_REMAINING_MIME"
        const val FAULT_EXTENSION_DERIVED_MIME = "EXTENSION_DERIVED_MIME"
        const val METHOD_RESET = "reset"
        const val METHOD_SET_FAULT = "set-fault"
        const val METHOD_RESET_METRICS = "reset-metrics"
        const val METHOD_METRICS = "metrics"
        const val METHOD_MEASURE_READS = "measure-reads"
        const val METHOD_AWAIT_IMPORT_READ = "await-import-read"
        const val METHOD_RELEASE_IMPORT_READ = "release-import-read"
        const val METHOD_AWAIT_IMPORT_RETIREMENT = "await-import-retirement"
        const val METHOD_IMPORT_INPUT_STATE = "import-input-state"
        const val FAULT_BLOCK_IMPORT_OPEN = "BLOCK_IMPORT_OPEN"
        const val FAULT_BLOCK_IMPORT_READ = "BLOCK_IMPORT_READ"
        const val FAULT_BLOCK_ORIGINAL_READ = "BLOCK_ORIGINAL_READ"
        const val FAULT_BLOCK_OPTIONAL_READ = "BLOCK_OPTIONAL_READ"
        const val FAULT_BLOCK_STORAGE_QUERY = "BLOCK_STORAGE_QUERY"
        const val FAULT_ORIGINAL_SECOND_READ_ONCE = "ORIGINAL_SECOND_READ_ONCE"
        const val FAULT_ORIGINAL_READ_ONCE = "ORIGINAL_READ_ONCE"
        const val FAULT_MISSING_DOCUMENT_SECURITY = "MISSING_DOCUMENT_SECURITY"
        const val FAULT_AUTHORITY_READ_ONCE = "AUTHORITY_READ_ONCE"
        const val FAULT_TOMBSTONE_TEMP_EMPTY = "TOMBSTONE_TEMP_EMPTY"
        const val FAULT_TOMBSTONE_TEMP_PARTIAL = "TOMBSTONE_TEMP_PARTIAL"
        const val FAULT_TOMBSTONE_CANONICAL_PARTIAL = "TOMBSTONE_CANONICAL_PARTIAL"
        const val FAULT_MANIFEST_READ_ONCE = "MANIFEST_READ_ONCE"
        const val FAULT_OPTIONAL_READ_ONCE = "OPTIONAL_READ_ONCE"
        const val FAULT_CUSTOM_MANIFEST_READ_ONCE = "CUSTOM_MANIFEST_READ_ONCE"
        const val FAULT_CUSTOM_MANIFEST_BACKUP_READ_ONCE = "CUSTOM_MANIFEST_BACKUP_READ_ONCE"
        const val FAULT_NONE = "NONE"
        const val FAULT_EMPTY = "EMPTY"
        const val FAULT_EMPTY_WITH_ERROR = "EMPTY_WITH_ERROR"
        const val FAULT_LOADING_CURSOR = "LOADING_CURSOR"
        const val FAULT_NULL_CURSOR = "NULL_CURSOR"
        const val FAULT_NULL_POINTER_EXCEPTION = "NULL_POINTER_EXCEPTION"
        const val FAULT_THROW = "THROW"
        const val FAULT_SECURITY = "SECURITY"
        const val FAULT_DOCUMENT_SECURITY = "DOCUMENT_SECURITY"
        const val FAULT_OPEN_SECURITY = "OPEN_SECURITY"
        const val FAULT_UNKNOWN_SIZE = "UNKNOWN_SIZE"
        const val FAULT_MISSING_COLUMN = "MISSING_COLUMN"
        const val FAULT_BLANK_ID = "BLANK_ID"
        const val FAULT_BLANK_NAME = "BLANK_NAME"
        const val FAULT_BLANK_MIME = "BLANK_MIME"
        const val FAULT_OVERSIZED_MIME = "OVERSIZED_MIME"
        const val FAULT_DUPLICATE_ID = "DUPLICATE_ID"
        const val FAULT_DUPLICATE_NAME = "DUPLICATE_NAME"
        const val FAULT_TOO_MANY_ROOT_ROWS = "TOO_MANY_ROOT_ROWS"
        const val FAULT_AT_CAPACITY_ROOT_ROWS = "AT_CAPACITY_ROOT_ROWS"
        const val FAULT_TOO_MANY_CHILD_ROWS = "TOO_MANY_CHILD_ROWS"
        const val FAULT_DISAPPEARING_CHILD = "DISAPPEARING_CHILD"
        const val FAULT_CREATE_REFUSAL = "CREATE_REFUSAL"
        const val FAULT_CREATE_SECURITY = "CREATE_SECURITY"
        const val FAULT_CREATE_DISAPPEAR = "CREATE_DISAPPEAR"
        const val FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED =
            "CREATE_ADDS_SIBLING_AND_HIDES_CREATED"
        const val CAPACITY_RACE_SIBLING = "External001"
        const val FAULT_HIDE_DIRECT_CHILDREN_AND_AUTORENAME =
            "HIDE_DIRECT_CHILDREN_AND_AUTORENAME"
        const val FAULT_HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL =
            "HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL"
        const val FAULT_DIRECTORY_DELETE_FALSE_SUCCESS = "DIRECTORY_DELETE_FALSE_SUCCESS"
        const val FAULT_DIRECTORY_DIRECT_LOOKUP_MISSING = "DIRECTORY_DIRECT_LOOKUP_MISSING"
        const val FAULT_FILE_DELETE_FALSE_SUCCESS = "FILE_DELETE_FALSE_SUCCESS"
        const val FAULT_FILE_DELETE_CHILD_QUERY_UNAVAILABLE = "FILE_DELETE_CHILD_QUERY_UNAVAILABLE"
        const val FAULT_DELETE_ADDS_UNKNOWN_CHILD = "DELETE_ADDS_UNKNOWN_CHILD"
        private const val ROOT_ID = "root"

        val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.golden.qa.documents"

        val treeUri: Uri
            get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        private val originalReadBlockClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val returnedDocumentRows = AtomicLong()
        @Volatile private var importReadEntered = java.util.concurrent.CountDownLatch(1)
        @Volatile private var importReadReleased = java.util.concurrent.CountDownLatch(1)
        @Volatile private var importReadRetired = java.util.concurrent.CountDownLatch(1)
        private val queryDocumentCalls = AtomicLong()
        private val queryChildCalls = AtomicLong()
        private val queryChildParents = ConcurrentLinkedQueue<String>()
        private val openDocumentCalls = AtomicLong()
        private val openedDocumentIds = java.util.concurrent.ConcurrentLinkedQueue<String>()
        private val openedDocumentLengthBytes = AtomicLong()
        private val openedOptionalMediaLengthBytes = AtomicLong()
        private val readDocumentBytes = AtomicLong()
        private val readMediaBytes = AtomicLong()
        private val measureReads = AtomicBoolean(false)
        private val createDocumentCalls = AtomicLong()
        private val createDirectoryCalls = AtomicLong()
        private val createFileCalls = AtomicLong()
        private val deleteDocumentCalls = AtomicLong()
        private val originalReadAttempts = java.util.concurrent.atomic.AtomicInteger()
        private val faultMode = AtomicReference(FAULT_NONE)
        private val disappearanceInjected = AtomicBoolean(false)
        private val falseSuccessfulDirectoryDeleteId = AtomicReference<String?>(null)
        private val capacityRaceHiddenDocumentId = AtomicReference<String?>(null)
        private val AUTO_RENAME_FAULTS = setOf(
            FAULT_HIDE_DIRECT_CHILDREN_AND_AUTORENAME,
            FAULT_HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL
        )
        private val FAULTS = setOf(
            FAULT_TOMBSTONE_TEMP_EMPTY, FAULT_TOMBSTONE_TEMP_PARTIAL, FAULT_TOMBSTONE_CANONICAL_PARTIAL,
            FAULT_MANIFEST_READ_ONCE, FAULT_OPTIONAL_READ_ONCE, FAULT_CUSTOM_MANIFEST_READ_ONCE, FAULT_CUSTOM_MANIFEST_BACKUP_READ_ONCE, FAULT_ORIGINAL_READ_ONCE, FAULT_MISSING_DOCUMENT_SECURITY, FAULT_AUTHORITY_READ_ONCE,
            FAULT_NONE,
            FAULT_EMPTY,
            FAULT_EMPTY_WITH_ERROR,
            FAULT_LOADING_CURSOR,
            FAULT_GENERIC_REMAINING_MIME,
            FAULT_EXTENSION_DERIVED_MIME,
            FAULT_NULL_CURSOR,
            FAULT_NULL_POINTER_EXCEPTION,
            FAULT_THROW,
            FAULT_SECURITY,
            FAULT_DOCUMENT_SECURITY,
            FAULT_OPEN_SECURITY,
            FAULT_BLOCK_IMPORT_READ,
            FAULT_BLOCK_ORIGINAL_READ, FAULT_BLOCK_OPTIONAL_READ, FAULT_BLOCK_STORAGE_QUERY, FAULT_ORIGINAL_SECOND_READ_ONCE,
            FAULT_BLOCK_IMPORT_OPEN,
            FAULT_UNKNOWN_SIZE,
            FAULT_MISSING_COLUMN,
            FAULT_BLANK_ID,
            FAULT_BLANK_NAME,
            FAULT_BLANK_MIME,
            FAULT_OVERSIZED_MIME,
            FAULT_DUPLICATE_ID,
            FAULT_DUPLICATE_NAME,
            FAULT_TOO_MANY_ROOT_ROWS,
            FAULT_AT_CAPACITY_ROOT_ROWS,
            FAULT_TOO_MANY_CHILD_ROWS,
            FAULT_DISAPPEARING_CHILD,
            FAULT_CREATE_REFUSAL,
            FAULT_CREATE_SECURITY,
            FAULT_CREATE_DISAPPEAR,
            FAULT_CREATE_ADDS_SIBLING_AND_HIDES_CREATED,
            FAULT_HIDE_DIRECT_CHILDREN_AND_AUTORENAME,
            FAULT_HIDE_DIRECT_CHILDREN_AUTORENAME_DELETE_REFUSAL,
            FAULT_DIRECTORY_DELETE_FALSE_SUCCESS,
            FAULT_DIRECTORY_DIRECT_LOOKUP_MISSING,
            FAULT_FILE_DELETE_FALSE_SUCCESS,
            FAULT_FILE_DELETE_CHILD_QUERY_UNAVAILABLE,
            FAULT_DELETE_ADDS_UNKNOWN_CHILD
        )

        private fun resetMetrics() {
            returnedDocumentRows.set(0L)
            queryDocumentCalls.set(0L)
            queryChildCalls.set(0L)
            queryChildParents.clear()
            openDocumentCalls.set(0L)
            openedDocumentIds.clear()
            openedDocumentLengthBytes.set(0L)
            openedOptionalMediaLengthBytes.set(0L)
            readDocumentBytes.set(0L)
            readMediaBytes.set(0L)
            createDocumentCalls.set(0L)
            createDirectoryCalls.set(0L)
            createFileCalls.set(0L)
            deleteDocumentCalls.set(0L)
        }

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
