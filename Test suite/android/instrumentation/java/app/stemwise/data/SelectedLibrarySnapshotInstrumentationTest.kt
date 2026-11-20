package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedLibrarySnapshotInstrumentationTest {
    @Test fun staleChildSecurityWithFreshRootAbsenceDoesNotBecomeRootPermissionLoss() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val root = checkNotNull(DocumentFile.fromTreeUri(targetContext, GoldenQaDocumentsProvider.treeUri))
        val rootId = DocumentsContract.getDocumentId(root.uri)
        val id = "AbCdEf_12-3"
        var rootReads = 0
        val reader = SelectedLibrarySnapshotReader(targetContext) { _, parent ->
            if (parent == rootId) {
                if (++rootReads == 1) listOf(SelectedDocumentRow("$rootId/$id", id,
                    DocumentsContract.Document.MIME_TYPE_DIR, null)) else emptyList()
            } else throw SecurityException("Captured child disappeared")
        }
        val failure = runCatching { reader.readSong(root, id) }.exceptionOrNull()
        assertEquals("PACKAGE_DIRECTORY_UNAVAILABLE", (failure as? LibraryRootException)?.code)
        assertEquals(null, canonicalProjectionRootAccessFailure(checkNotNull(failure)))
        assertEquals(2, rootReads)
        val denied = SelectedLibrarySnapshotReader(targetContext) { _, _ -> throw SecurityException("Grant revoked") }
        val denial = runCatching { denied.readSong(root, id) }.exceptionOrNull()
        assertEquals("ROOT_PERMISSION_LOST", (denial as? LibraryRootException)?.code)
        assertEquals("ROOT_PERMISSION_LOST", canonicalProjectionRootAccessFailure(checkNotNull(denial))?.code)
    }
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun boundDocumentsRejectASecondProviderWithTheSameTreeIdBeforeQuery() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        val root = checkNotNull(DocumentFile.fromTreeUri(
            targetContext,
            GoldenQaDocumentsProvider.treeUri
        ))
        val foreign = GoldenQaDocumentsProvider.treeUri.buildUpon()
            .authority("foreign.stemwise.provider")
            .appendPath("document")
            .appendPath("AbCdEf_12-3")
            .build()
        val reader = SelectedLibrarySnapshotReader(targetContext)

        listOf<suspend () -> Unit>(
            { reader.readBoundSong(root, "AbCdEf_12-3", foreign) },
            { reader.readBoundDirectory(root, foreign) },
            { reader.rootContainsBoundDirectoryIdentity(root, foreign) }
        ).forEach { operation ->
            val failure = runCatching { operation() }.exceptionOrNull()
            assertEquals(
                "PACKAGE_DIRECTORY_IDENTITY_CHANGED",
                (failure as? LibraryRootException)?.code
            )
        }
        assertEquals(
            0L,
            providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("queryChildCalls")
        )
    }

    @Test
    fun checkedProviderSnapshotDistinguishesEmptyUnavailableAndAmbiguousRows() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        val context = SnapshotContext(targetContext)
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), context.database.libraryDao())

            setFault(GoldenQaDocumentsProvider.FAULT_EMPTY)
            val empty = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            assertEquals(0, empty.existingEntryCount)
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            roots.commitValidatedCandidateForDevelopment(empty).getOrThrow()
            val admittedRoot = checkNotNull(roots.captureHandle())
            val priorSong = File(providerRoot(), "Q0000000000").apply { check(mkdir()) }
            File(priorSong, "original.opus").writeBytes(byteArrayOf(1, 2, 3))
            val beforeCapacity = providerRoot().walkTopDown().filter(File::isFile)
                .associate { it.relativeTo(providerRoot()).path to it.readBytes().toList() }

            setFault(GoldenQaDocumentsProvider.FAULT_AT_CAPACITY_ROOT_ROWS)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val root = checkNotNull(DocumentFile.fromTreeUri(context, GoldenQaDocumentsProvider.treeUri))
            val capacity = SelectedLibrarySnapshotReader(context).readRootDirectories(root)
            assertEquals(8_192, capacity.size)
            assertEquals(
                1L,
                providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
                    .getLong("queryChildCalls")
            )
            val created = roots.createSongDirectoryDocument(admittedRoot, "ZyXwVu_98-7")
            assertTrue(created.toString().contains("ZyXwVu_98-7"))
            assertEquals(1L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("createDocumentCalls"))
            assertTrue(File(providerRoot(), "ZyXwVu_98-7").isDirectory)
            assertEquals(beforeCapacity, providerRoot().walkTopDown().filter(File::isFile)
                .associate { it.relativeTo(providerRoot()).path to it.readBytes().toList() })
            resetProvider()

            val names = listOf("AbCdEf_12-3", "abCdEf_12-3")
            names.forEach { name -> check(File(providerRoot(), name).mkdir()) }
            val exactCase = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            assertEquals(2, exactCase.existingSongCount)

            setFault(GoldenQaDocumentsProvider.FAULT_TOO_MANY_ROOT_ROWS)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val large = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            assertEquals(8_192 + SelectedLibraryContract.rootAuthorityFiles.size + 1, large.existingSongCount)
            assertEquals(1L, providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("queryChildCalls"))

            setFault(GoldenQaDocumentsProvider.FAULT_TOO_MANY_CHILD_ROWS)
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val childOverflow = runCatching { roots.checkedSongSnapshot(admittedRoot, names.first()) }
                .exceptionOrNull()
            assertEquals(
                "ROOT_LAYOUT_UNSUPPORTED",
                (childOverflow as? LibraryRootException)?.code
            )
            assertEquals(
                2L,
                providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
                    .getLong("queryChildCalls")
            )

            setFault(GoldenQaDocumentsProvider.FAULT_EMPTY_WITH_ERROR)
            val emptyFailure = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .exceptionOrNull()
            assertEquals(
                describeThrowable(emptyFailure),
                "ROOT_UNAVAILABLE",
                (emptyFailure as? LibraryRootException)?.code
            )
            assertEquals(names, providerRoot().list()?.sorted())

            setFault(GoldenQaDocumentsProvider.FAULT_LOADING_CURSOR)
            val loadingFailure = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .exceptionOrNull()
            assertEquals(
                describeThrowable(loadingFailure),
                "ROOT_UNAVAILABLE",
                (loadingFailure as? LibraryRootException)?.code
            )
            assertEquals(names, providerRoot().list()?.sorted())

            val expected = linkedMapOf(
                GoldenQaDocumentsProvider.FAULT_THROW to "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_NULL_CURSOR to "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_NULL_POINTER_EXCEPTION to "ROOT_UNAVAILABLE",
                GoldenQaDocumentsProvider.FAULT_BLANK_ID to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_BLANK_NAME to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_BLANK_MIME to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_OVERSIZED_MIME to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_DUPLICATE_ID to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_DUPLICATE_NAME to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_MISSING_COLUMN to "ROOT_LAYOUT_UNSUPPORTED",
                GoldenQaDocumentsProvider.FAULT_SECURITY to "ROOT_PERMISSION_LOST"
            )
            expected.forEach { (fault, code) ->
                setFault(fault)
                val failure = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                    .exceptionOrNull()
                assertEquals(fault, code, (failure as? LibraryRootException)?.code)
                assertEquals(names, providerRoot().list()?.sorted())
            }

            setFault(GoldenQaDocumentsProvider.FAULT_OVERSIZED_MIME)
            val exactDirectoryFailure = runCatching {
                SelectedLibrarySnapshotReader(context).readBoundDirectory(
                    checkNotNull(DocumentFile.fromTreeUri(
                        context,
                        GoldenQaDocumentsProvider.treeUri
                    )),
                    DocumentsContract.buildDocumentUriUsingTree(
                        GoldenQaDocumentsProvider.treeUri,
                        "root/${names.first()}"
                    )
                )
            }.exceptionOrNull()
            assertEquals(
                "ROOT_LAYOUT_UNSUPPORTED",
                (exactDirectoryFailure as? LibraryRootException)?.code
            )

            val selectedRoot = roots.state.value
            val siblingBytes = byteArrayOf(2, 5, 7)
            val siblingOriginal = File(File(providerRoot(), names.last()), SelectedLibraryContract.ORIGINAL)
                .apply { writeBytes(siblingBytes) }
            setFault(GoldenQaDocumentsProvider.FAULT_DISAPPEARING_CHILD)
            val disappeared = runCatching { roots.checkedSongSnapshot(admittedRoot, names.first()) }
                .exceptionOrNull()
            assertEquals(
                describeThrowable(disappeared),
                "PACKAGE_DIRECTORY_UNAVAILABLE",
                (disappeared as? LibraryRootException)?.code
            )
            assertTrue(describeThrowable(disappeared), disappeared?.cause is SecurityException)
            assertEquals(null, canonicalProjectionRootAccessFailure(checkNotNull(disappeared)))
            assertTrue(providerRoot().isDirectory)
            assertTrue(!File(providerRoot(), names.first()).exists())
            assertEquals(listOf(names.last()), SelectedLibrarySnapshotReader(context)
                .readRootDirectories(admittedRoot.rootDocument).map(SelectedDocumentSnapshot::displayName))
            val sibling = checkNotNull(roots.checkedSongSnapshot(admittedRoot, names.last()))
            assertEquals(names.last(), sibling.directory.displayName)
            assertEquals(setOf(SelectedLibraryContract.ORIGINAL), sibling.files.keys)
            assertTrue(siblingBytes.contentEquals(siblingOriginal.readBytes()))
            assertEquals(selectedRoot, roots.state.value)
            assertEquals(admittedRoot.authority(), checkNotNull(roots.captureHandle()).authority())

            setFault(GoldenQaDocumentsProvider.FAULT_SECURITY)
            val denied = runCatching { roots.checkedSongSnapshot(admittedRoot, names.last()) }
                .exceptionOrNull()
            assertEquals(describeThrowable(denied), "ROOT_PERMISSION_LOST", (denied as? LibraryRootException)?.code)
            assertTrue(describeThrowable(denied), denied?.cause is SecurityException)
            assertEquals("ROOT_PERMISSION_LOST", canonicalProjectionRootAccessFailure(checkNotNull(denied))?.code)
            val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals(0L, metrics.getLong("openDocumentCalls"))
            assertEquals(0L, metrics.getLong("openedDocumentLengthBytes"))
            assertEquals(0L, metrics.getLong("createDocumentCalls"))
            assertEquals(0L, metrics.getLong("deleteDocumentCalls"))
        } finally {
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun fileSystemMimeRulesPreserveRootAuthorityNamesAndExistingSong() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = SnapshotContext(targetContext)
        val originalBytes = byteArrayOf(3, 5, 8, 13)
        val original = File(File(providerRoot(), "AbCdEf_12-3").apply { check(mkdir()) },
            SelectedLibraryContract.ORIGINAL).apply { writeBytes(originalBytes) }
        try {
            setFault(GoldenQaDocumentsProvider.FAULT_EXTENSION_DERIVED_MIME)
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), context.database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            assertEquals(1, candidate.existingSongCount)
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val entries = SelectedLibrarySnapshotReader(context).readRootEntries(handle.rootDocument)
                .filterNot(SelectedDocumentSnapshot::directory).associateBy(SelectedDocumentSnapshot::displayName)
            assertEquals(setOf(SelectedLibraryContract.ROOT_MANIFEST, SelectedLibraryContract.ROOT_MANIFEST_BACKUP),
                entries.keys)
            assertEquals("application/json", entries.getValue(SelectedLibraryContract.ROOT_MANIFEST).mimeType)
            assertEquals(MimeTypeMap.getSingleton().getMimeTypeFromExtension("bak") ?: "application/octet-stream",
                entries.getValue(SelectedLibraryContract.ROOT_MANIFEST_BACKUP).mimeType)
            val canonical = checkNotNull(targetContext.contentResolver.openInputStream(
                entries.getValue(SelectedLibraryContract.ROOT_MANIFEST).uri)).use { it.readBytes() }
            val backup = checkNotNull(targetContext.contentResolver.openInputStream(
                entries.getValue(SelectedLibraryContract.ROOT_MANIFEST_BACKUP).uri)).use { it.readBytes() }
            assertTrue(canonical.contentEquals(backup))
            assertEquals(handle.libraryId, parsePortableLibraryId(canonical))
            assertTrue(originalBytes.contentEquals(original.readBytes()))
        } finally {
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun fileSystemMimeRulesPreserveInternalSidecarsAndVerifyTheirActualBytes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = SnapshotContext(targetContext)
        val packageId = "AbCdEf_12-3"
        val originalBytes = byteArrayOf(3, 5, 8, 13)
        val folder = File(providerRoot(), packageId).apply { check(mkdir()) }
        val original = File(folder, SelectedLibraryContract.ORIGINAL).apply { writeBytes(originalBytes) }
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), context.database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            setFault(GoldenQaDocumentsProvider.FAULT_EXTENSION_DERIVED_MIME)
            for (name in SelectedLibraryContract.internalPackageFiles) {
                val image = ".png." in name
                val bytes = if (image) byteArrayOf(1, 3, 5, 7) else "{}".toByteArray(Charsets.UTF_8)
                roots.writeExactSongSidecar(handle, packageId, name,
                    if (image) "image/png" else "application/json", bytes, 1_024L)
                val entry = checkNotNull(roots.checkedSongSnapshot(handle, packageId)).files.getValue(name)
                assertEquals(MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.'))
                    ?: "application/octet-stream", entry.mimeType)
                assertTrue(bytes.contentEquals(checkNotNull(targetContext.contentResolver.openInputStream(entry.uri))
                    .use { it.readBytes() }))
                // Reopening an existing extension-typed sidecar must preserve its exact URI as well.
                roots.writeExactSongSidecar(handle, packageId, name,
                    if (image) "image/png" else "application/json", bytes + 10.toByte(), 1_024L)
                val updated = checkNotNull(roots.checkedSongSnapshot(handle, packageId)).files.getValue(name)
                assertEquals(entry.uri, updated.uri)
                assertTrue((bytes + 10.toByte()).contentEquals(File(folder, name).readBytes()))
            }
            assertEquals(SelectedLibraryContract.internalPackageFiles + SelectedLibraryContract.ORIGINAL,
                folder.list()!!.toSet())
            assertTrue(originalBytes.contentEquals(original.readBytes()))
        } finally {
            setFault(GoldenQaDocumentsProvider.FAULT_NONE)
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun rootAuthorityTemporaryAndBackupRetainJsonMimeAfterCreation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val root = checkNotNull(DocumentFile.fromTreeUri(
            targetContext,
            GoldenQaDocumentsProvider.treeUri
        ))
        try {
            val created = listOf(
                SelectedLibraryContract.ROOT_MANIFEST_TEMP,
                SelectedLibraryContract.ROOT_MANIFEST_BACKUP
            ).associateWith { name ->
                checkNotNull(DocumentsContract.createDocument(
                    targetContext.contentResolver,
                    root.uri,
                    "application/json",
                    name
                ))
            }
            val entries = SelectedLibrarySnapshotReader(targetContext)
                .readRootEntries(root)
                .associateBy(SelectedDocumentSnapshot::displayName)
            created.forEach { (name, uri) ->
                val entry = checkNotNull(entries[name])
                assertEquals(uri, entry.uri)
                assertEquals("application/json", entry.mimeType)
                assertTrue(!entry.directory)
            }
        } finally {
            revokeProviderTree()
            resetProvider()
        }
    }

    @Test
    fun uiDemoProviderPreservesTransactionMimeAndCommitsItsRootManifest() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        UiDemoLibraryDocumentsProvider.initialize(targetContext)
        val treeUri = UiDemoLibraryDocumentsProvider.treeUri
        targetContext.grantUriPermission(
            targetContext.packageName,
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        val root = checkNotNull(DocumentFile.fromTreeUri(targetContext, treeUri))
        assertEquals(
            UiDemoLibraryDocumentsProvider.FIXTURE_IDS.toSet(),
            root.listFiles().filter(DocumentFile::isDirectory).mapNotNull(DocumentFile::getName)
                .toSet()
        )
        val song = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            "root/DEMOUI00001"
        )
        val created = mutableListOf<Uri>()
        val context = SnapshotContext(targetContext)
        // Other UI tests may already have adopted this shared synthetic root. Keep its
        // authority bytes intact while testing a fresh transaction, including on reruns.
        val savedAuthority = root.listFiles()
            .filter { it.name in SelectedLibraryContract.rootAuthorityFiles }
            .associate { document ->
                checkNotNull(document.name) to checkNotNull(
                    targetContext.contentResolver.openInputStream(document.uri)
                ).use { it.readBytes() }
            }
        try {
            root.listFiles().filter { it.name in savedAuthority }.forEach {
                assertTrue(it.delete())
            }
            val compound =
                SelectedLibraryContract.rootAuthorityFiles.filter { ".json." in it }
                    .map { Triple(it, "application/json", root.uri) } +
                SelectedLibraryContract.internalPackageFiles.filter { ".json." in it }
                    .map { Triple(it, "application/json", song) } +
                listOf(
                    SelectedLibraryContract.ENRICHMENT_COVER_BACKUP,
                    SelectedLibraryContract.ENRICHMENT_COVER_REPLACEMENT
                ).map { Triple(it, "image/png", song) }
            compound.forEach { (name, mime, parent) ->
                val uri = checkNotNull(DocumentsContract.createDocument(
                    targetContext.contentResolver,
                    parent,
                    mime,
                    name
                )).also(created::add)
                val queried = queryDemoChild(parent, name)
                assertEquals(uri, queried.first)
                assertEquals(mime, queried.second)
                assertEquals(mime, targetContext.contentResolver.getType(uri))
            }
            created.asReversed().forEach { uri ->
                assertTrue(DocumentsContract.deleteDocument(targetContext.contentResolver, uri))
            }
            created.clear()

            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), context.database.libraryDao())
            val candidate = roots.validateCandidate(treeUri).getOrThrow()
            roots.commitValidatedCandidate(candidate).getOrThrow()
            val entries = SelectedLibrarySnapshotReader(targetContext)
                .readRootEntries(root)
                .associateBy(SelectedDocumentSnapshot::displayName)
            assertEquals(
                "application/json",
                entries[SelectedLibraryContract.ROOT_MANIFEST]?.mimeType
            )
            assertEquals(
                "application/json",
                entries[SelectedLibraryContract.ROOT_MANIFEST_BACKUP]?.mimeType
            )
            assertEquals(null, entries[SelectedLibraryContract.ROOT_MANIFEST_TEMP])
        } finally {
            created.asReversed().forEach { uri ->
                runCatching { DocumentsContract.deleteDocument(targetContext.contentResolver, uri) }
            }
            runCatching {
                SelectedLibrarySnapshotReader(targetContext).readRootEntries(root)
                    .filter { it.displayName in SelectedLibraryContract.rootAuthorityFiles }
                    .forEach {
                        DocumentsContract.deleteDocument(targetContext.contentResolver, it.uri)
                    }
            }
            savedAuthority.forEach { (name, bytes) ->
                val restored = checkNotNull(root.createFile("application/json", name))
                checkNotNull(targetContext.contentResolver.openOutputStream(restored.uri, "wt"))
                    .use { it.write(bytes) }
            }
            context.destroy()
        }
    }

    private fun queryDemoChild(parent: Uri, name: String): Pair<Uri, String> {
        val treeUri = UiDemoLibraryDocumentsProvider.treeUri
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parent)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        targetContext.contentResolver.query(children, projection, null, null, null).use { cursor ->
            checkNotNull(cursor)
            val idIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            val mimeIndex = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(idIndex)
                    ) to cursor.getString(mimeIndex)
                }
            }
        }
        error("Missing UI Demo Library child $name")
    }

    @Test
    fun rootInventoryRetainsRecognizedIdentityWithoutMaterializingPackageOrUnrelatedRows() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val root = checkNotNull(DocumentFile.fromTreeUri(targetContext, GoldenQaDocumentsProvider.treeUri))
        val rootId = DocumentsContract.getTreeDocumentId(root.uri)
        val directories = List(8_193) { index ->
            SelectedDocumentRow("root/$index", "Q${index.toString().padStart(10, '0')}",
                DocumentsContract.Document.MIME_TYPE_DIR, null)
        }
        val unrelated = List(10_000) { index -> SelectedDocumentRow("junk/$index",
            "$index".padEnd(4_096, 'n'), "application/octet-stream", 1L) }
        var queries = 0
        val reader = SelectedLibrarySnapshotReader(targetContext) { _, parent ->
            queries++
            check(parent == rootId) { "Root inventory unexpectedly opened a song package" }
            directories + unrelated
        }
        val snapshot = reader.read(root)
        assertEquals(1, queries)
        assertEquals(18_193, snapshot.totalEntryCount)
        assertEquals(8_193, snapshot.songDirectories.size)
        assertTrue(snapshot.rootFiles.isEmpty())
    }

    @Test
    fun candidateConfirmationCountsEveryRootEntryIndependentlyOfSongRecognition() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        grantProviderTree()
        val context = SnapshotContext(targetContext)
        try {
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), context.database.libraryDao())
            for (shape in listOf("empty", "file", "directory", "mixed")) {
                resetProvider()
                if (shape == "file" || shape == "mixed") File(providerRoot(), "notes.txt").writeText("owner notes", Charsets.UTF_8)
                if (shape == "directory") check(File(providerRoot(), "Unrelated folder").mkdir())
                if (shape == "mixed") check(File(providerRoot(), "AbCdEf_12-3").mkdir())
                val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
                assertEquals(shape, when (shape) { "empty" -> 0; "mixed" -> 2; else -> 1 }, candidate.existingEntryCount)
                assertEquals(shape, if (shape == "mixed") 1 else 0, candidate.existingSongCount)
                assertEquals(shape, shape != "empty", candidate.requiresNonEmptyFolderConfirmation)
            }
        } finally { revokeProviderTree(); resetProvider(); context.destroy() }
    }

    private fun providerRoot() = File(
        targetContext.noBackupFilesDir,
        "golden-enrichment-document-provider"
    )

    private fun setFault(fault: String) {
        providerCall(GoldenQaDocumentsProvider.METHOD_SET_FAULT, fault)
    }

    private fun resetProvider() {
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET)
    }

    private fun providerCall(method: String, arg: String? = null) = checkNotNull(
        targetContext.contentResolver.call(
            Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
            method,
            arg,
            null
        )
    )

    private fun describeThrowable(error: Throwable?): String {
        if (error == null) return "throwable=<null>"
        val causes = generateSequence(error) { it.cause }
            .joinToString(" -> ") {
                it::class.java.name + "(message=" + it.message + ",code=" +
                    (it as? LibraryRootException)?.code + ")"
            }
        val suppressed = error.suppressed.joinToString {
            it::class.java.name + ":" + it.message
        }
        return "throwable=" + causes + "; suppressed=[" + suppressed + "]"
    }

    private fun grantProviderTree() {
        targetContext.grantUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }

    private fun revokeProviderTree() {
        targetContext.revokeUriPermission(
            targetContext.packageName,
            GoldenQaDocumentsProvider.treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }

    private class SnapshotContext(base: Context) : ContextWrapper(base) {
        private val namespace = UUID.randomUUID().toString()
        private val root = File(base.cacheDir, "snapshot-test/$namespace").apply {
            check(mkdirs() || isDirectory)
        }
        private val files = File(root, "files").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(this, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files

        fun destroy() {
            database.close()
            check(!root.exists() || root.deleteRecursively())
        }
    }
}
