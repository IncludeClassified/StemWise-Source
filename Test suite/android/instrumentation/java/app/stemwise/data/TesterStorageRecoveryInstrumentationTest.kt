package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tester-owned production-class fault probes for SW-LIBRARY-STORAGE-0001. */
@RunWith(AndroidJUnit4::class)
class TesterStorageRecoveryInstrumentationTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rootSelectionProjectionClearFaultRollsBackAcceptedJobSourceAndExactIdentity() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (table in listOf("processing_jobs", "songs")) {
            resetProvider(); grantProviderTree()
            val context = IsolatedTesterContext(targetContext, "room-root-clear-$table")
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            val otherTree = DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/root-clear-target")
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                    .getOrThrow()).getOrThrow()
                val oldRoot = checkNotNull(roots.captureHandle())
                val id = EXACT_VIDEO_ID
                val folder = File(providerRoot(), id).apply { check(mkdir()) }
                val media = CurrentSourcePublicationFixture.load(context, id)
                File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(media.original)
                seedCompleteOpusMetadata(folder, id, media.original)
                dao.upsertSongs(listOf(boundDeletionSong(id).copy(durationMs = media.manifest.audibleFrames / 48L)))
                val source = SourceVersionEntity("$id:retained", id, DocumentsContract.buildDocumentUriUsingTree(
                    GoldenQaDocumentsProvider.treeUri, "root/$id/${SelectedLibraryContract.ORIGINAL}").toString(),
                    sha256(File(folder, SelectedLibraryContract.ORIGINAL)), "opus", "ogg", 48000, 2,
                    "selected-folder-flat-v1", true, 1L)
                dao.insertSource(source)
                val accepted = ProcessingJobEntity("retained-complete", id, source.id, "SOURCE_SELECTION", "MVSEP",
                    "completed-plan", "COMPLETE", "accepted-completed-remote", 1, null, null, null, 1L, 1L, oldRoot.libraryId)
                dao.upsertJob(accepted)
                val priorBytes = treeSnapshot(folder)
                roots.reportRuntimeAccessFailure(oldRoot, SecurityException("isolated root loss"))
                val priorIdentity = dao.libraryRootIdentity()
                val priorSongs = dao.allSongs()
                File(providerRoot(), "root-clear-target").apply { check(mkdir()) }
                targetContext.grantUriPermission(targetContext.packageName, otherTree, grantFlags)
                val candidate = roots.validateCandidate(otherTree).getOrThrow()
                val sqlite = database.openHelper.writableDatabase
                sqlite.execSQL("""
                    CREATE TRIGGER fail_projection_clear BEFORE DELETE ON $table
                    BEGIN SELECT RAISE(ABORT, 'injected projection clear transaction'); END
                """.trimIndent())
                val failure = roots.commitValidatedCandidateForDevelopment(candidate).exceptionOrNull()
                assertTrue("Clear failure cause was lost: $failure", generateSequence(failure) { it.cause }
                    .any { it.message.orEmpty().contains("injected projection clear transaction") })
                assertEquals(priorIdentity, dao.libraryRootIdentity())
                assertEquals(priorSongs, dao.allSongs())
                assertEquals(source, dao.currentSource(id))
                assertEquals(accepted, dao.findJobById(accepted.id))
                assertEquals(priorBytes, treeSnapshot(folder))
                assertTrue(File(providerRoot(), "root-clear-target").listFiles().orEmpty().isEmpty())
                sqlite.execSQL("DROP TRIGGER fail_projection_clear")
                val restarted = LibraryRootManager(context, mutations, dao).initialize()
                assertTrue(restarted.ready)
                assertEquals(oldRoot.libraryId, restarted.libraryId)
                assertEquals(oldRoot.treeUri, restarted.treeUri)
                assertEquals(oldRoot.epoch, restarted.epoch)
                assertEquals(source, dao.currentSource(id))
                assertEquals(accepted, dao.findJobById(accepted.id))
            } finally {
                database.close()
                targetContext.revokeUriPermission(otherTree, grantFlags)
                revokeProviderTree(); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun exactRoomSelectionRepairsFirstManifestTemporaryWriteKilledMidCopy() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedTesterContext(targetContext, "root-manifest-copy-kill")
        var database: LibraryDatabase? = null
        try {
            database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                .getOrThrow()
            val committed = roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val root = providerRoot()
            val complete = File(root, SelectedLibraryContract.ROOT_MANIFEST).readBytes()
            assertTrue(File(root, SelectedLibraryContract.ROOT_MANIFEST).delete())
            assertTrue(File(root, SelectedLibraryContract.ROOT_MANIFEST_BACKUP).delete())
            File(root, SelectedLibraryContract.ROOT_MANIFEST_TEMP).writeBytes(
                complete.copyOf(complete.size / 2)
            )

            val songIds = List(32) { "R" + it.toString().padStart(10, '0') }
            songIds.forEach { check(File(root, it).mkdir()) }
            targetContext.contentResolver.call(Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_RESET_METRICS, null, null)
            val recovered = LibraryRootManager(context, mutations, database.libraryDao()).initialize()
            val metrics = checkNotNull(targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"), GoldenQaDocumentsProvider.METHOD_METRICS, null, null))
            val queriedParents = metrics.getStringArrayList("queryChildParents").orEmpty()
            assertEquals(setOf("root"), queriedParents.toSet())
            songIds.forEach { id -> assertEquals("Manifest repair enumerated song children for $id", 0,
                queriedParents.count { it == "root/$id" }) }

            assertTrue(recovered.ready)
            assertEquals(committed.libraryId, recovered.libraryId)
            assertEquals(committed.treeUri, recovered.treeUri)
            assertEquals(committed.epoch, recovered.epoch)
            songIds.forEach { assertTrue(File(root, it).isDirectory) }
            assertEquals(
                complete.toList(),
                File(root, SelectedLibraryContract.ROOT_MANIFEST).readBytes().toList()
            )
            assertEquals(
                complete.toList(),
                File(root, SelectedLibraryContract.ROOT_MANIFEST_BACKUP).readBytes().toList()
            )
            assertFalse(File(root, SelectedLibraryContract.ROOT_MANIFEST_TEMP).exists())
        } finally {
            database?.close()
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    @Test
    fun preparedDeletionIsDiscardedWhileForeignRootAndUnknownEvidenceRemainUntouched() =
        runBlocking {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            resetProvider()
            grantProviderTree()
            val context = IsolatedTesterContext(targetContext, "deletion-recovery")
            var database: LibraryDatabase? = null
            try {
                val mutations = PackageMutationCoordinator(context)
                database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                val roots = LibraryRootManager(context, mutations, database.libraryDao())
                val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri)
                    .getOrThrow()
                roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
                val handle = checkNotNull(roots.captureHandle())
                val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
                val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())

                val exactFolder = seedIncompleteSong(EXACT_VIDEO_ID)
                val foreignFolder = seedIncompleteSong(FOREIGN_VIDEO_ID)
                val unsafeFolder = seedIncompleteSong(UNSAFE_VIDEO_ID, includeUnknown = true)
                val unsafeBefore = unsafeFolder.listFiles().orEmpty().associate {
                    it.name to sha256(it)
                }
                database.libraryDao().insertSong(boundDeletionSong(UNSAFE_VIDEO_ID))
                listOf(EXACT_VIDEO_ID, FOREIGN_VIDEO_ID, UNSAFE_VIDEO_ID).forEach { videoId ->
                    seedVerifiedBinding(bindings, mutations, handle, "youtube-video:$videoId", videoId)
                }
                val bindingsBefore = database.libraryDao().sourceBindingsForLibrary(handle.libraryId)
                val exactBefore = treeSnapshot(exactFolder)
                val foreignBefore = treeSnapshot(foreignFolder)

                val exactJournal = writeDeletionJournal(
                    context,
                    handle,
                    EXACT_VIDEO_ID,
                    "tester-delete-exact",
                    foreignRoot = false
                )
                val foreignJournal = writeDeletionJournal(
                    context,
                    handle,
                    FOREIGN_VIDEO_ID,
                    "tester-delete-foreign",
                    foreignRoot = true
                )
                val foreignJournalBefore = foreignJournal.readBytes().toList()
                trash.recoverInterruptedDeletes()

                assertTrue(exactFolder.isDirectory)
                assertEquals(exactBefore, treeSnapshot(exactFolder))
                assertFalse(exactJournal.exists())
                assertTrue(foreignFolder.isDirectory)
                assertTrue(foreignJournal.isFile)
                assertFalse("youtube-video:$EXACT_VIDEO_ID" in bindings.blockedSourceIds(handle))
                assertFalse("youtube-video:$FOREIGN_VIDEO_ID" in bindings.blockedSourceIds(handle))

                val unsafeResult = trash.deletePermanently(handle, UNSAFE_VIDEO_ID)
                assertEquals(
                    "PACKAGE_DIRECTORY_CLEANUP_REFUSED",
                    unsafeResult.exceptionOrNull()?.message
                )
                assertTrue(unsafeFolder.isDirectory)
                assertEquals(
                    unsafeBefore,
                    unsafeFolder.listFiles().orEmpty().associate { it.name to sha256(it) }
                )
                assertFalse("youtube-video:$UNSAFE_VIDEO_ID" in bindings.blockedSourceIds(handle))
                val unsafeJournal = File(context.filesDir, "operation-journals")
                    .listFiles().orEmpty()
                    .mapNotNull { file ->
                        runCatching { JSONObject(file.readText()) }.getOrNull()
                            ?.takeIf {
                                it.optString("operation") == "DELETE_PERMANENT" &&
                                    it.optString("packageId") == UNSAFE_VIDEO_ID
                            }
                            ?.let { file }
                    }
                    .singleOrNull()
                assertNotNull("Unsafe deletion did not retain its uncommitted receipt", unsafeJournal)
                val unsafeReceipt = requireAtomicJournalEnvelope(
                    checkNotNull(readAtomicJournal(checkNotNull(unsafeJournal), "INVALID")), "INVALID")
                assertEquals("PREPARED", unsafeReceipt.state)
                assertEquals(handle.libraryId, unsafeReceipt.libraryId)
                assertEquals(handle.treeUri.toString(), unsafeReceipt.treeUri)
                assertEquals(handle.epoch, unsafeReceipt.rootEpoch)
                assertEquals(0, unsafeReceipt.details.length())
                assertEquals(bindingsBefore, database.libraryDao().sourceBindingsForLibrary(handle.libraryId))
                assertTrue(foreignFolder.isDirectory)
                assertEquals(foreignBefore, treeSnapshot(foreignFolder))
                assertTrue(foreignJournal.isFile)
                assertEquals(foreignJournalBefore, foreignJournal.readBytes().toList())
            } finally {
                database?.close()
                revokeProviderTree()
                resetProvider()
                context.destroy()
            }
        }

    private fun writeDeletionJournal(
        context: Context,
        handle: RootHandle,
        videoId: String,
        transactionId: String,
        foreignRoot: Boolean
    ): File {
        val directory = File(context.filesDir, "operation-journals").apply {
            check(isDirectory || mkdirs())
        }
        return File(directory, "$transactionId.json").apply {
            writeText(
                JSONObject()
                    .put("schemaVersion", 2)
                    .put("transactionId", transactionId)
                    .put("packageId", videoId)
                    .put("operation", "DELETE_PERMANENT")
                    .put(
                        "libraryId",
                        if (foreignRoot) "00000000-0000-4000-8000-000000000099" else
                            handle.libraryId
                    )
                    .put("treeUri", handle.treeUri.toString())
                    .put("rootEpoch", handle.epoch)
                    // PREPARED authorizes no deletion; only the exact current root may retire it.
                    .put("sequence", 1)
                    .put("state", "PREPARED")
                    .put("updatedAt", Instant.parse("2026-08-09T12:00:00Z").toString())
                    .put(
                        "details",
                        JSONObject()
                            .put("sourceId", "youtube-video:$videoId")
                            .put("libraryId", handle.libraryId)
                    )
                    .toString(),
                Charsets.UTF_8
            )
        }
    }

    private fun seedIncompleteSong(videoId: String, includeUnknown: Boolean = false): File =
        File(providerRoot(), videoId).apply {
            check(mkdir())
            File(this, SelectedLibraryContract.ORIGINAL).writeBytes(byteArrayOf(0x4f, 0x50))
            if (includeUnknown) File(this, "owner-unknown.bin").writeBytes(byteArrayOf(7, 8, 9))
        }

    private fun boundDeletionSong(videoId: String) = SongEntity(
        id = videoId,
        youtubeVideoId = videoId,
        playlistItemId = null,
        canonicalUrl = "https://music.youtube.com/watch?v=$videoId",
        sourceTitle = "Deletion recovery",
        sourceArtist = "StemWise",
        displayTitle = null,
        displayArtist = null,
        durationMs = 1L,
        artworkPath = null,
        currentlyInSourcePlaylist = false,
        highLevelState = "READY",
        acquiredAtEpochMs = 1L,
        createdAtEpochMs = 1L,
        canonicalExternal = true,
        packageDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            GoldenQaDocumentsProvider.treeUri,
            "root/$videoId"
        ).toString()
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun treeSnapshot(root: File): Map<String, String> {
        if (!root.exists()) return emptyMap()
        return root.walkTopDown()
            .filter { it != root }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .associate { entry ->
                val relative = entry.relativeTo(root).invariantSeparatorsPath
                relative to if (entry.isDirectory) {
                    "directory"
                } else {
                    "file:${entry.length()}:${sha256(entry)}"
                }
            }
    }

    private fun grantProviderTree() {
        grantProviderTrees(GoldenQaDocumentsProvider.treeUri)
    }

    private fun grantProviderTrees(vararg trees: Uri) {
        trees.forEach { tree ->
        targetContext.grantUriPermission(
            targetContext.packageName,
                tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        }
    }

    private fun revokeProviderTree() {
        revokeProviderTrees(GoldenQaDocumentsProvider.treeUri)
    }

    private fun revokeProviderTrees(vararg trees: Uri) {
        trees.forEach { tree ->
        targetContext.revokeUriPermission(
            targetContext.packageName,
                tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        }
    }

    private fun resetProvider() {
        checkNotNull(
            targetContext.contentResolver.call(
                Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
                GoldenQaDocumentsProvider.METHOD_RESET,
                null,
                null
            )
        )
    }

    private fun providerRoot(): File = File(
        targetContext.noBackupFilesDir,
        "golden-enrichment-document-provider"
    )

    private fun providerTreeUri(name: String): Uri = DocumentsContract.buildTreeDocumentUri(
        GoldenQaDocumentsProvider.AUTHORITY,
        "root/$name"
    )

    private class IsolatedTesterContext(base: Context, suffix: String) : ContextWrapper(base) {
        private val preferencePrefix = "tester-storage-$suffix-"
        private val preferenceNames = linkedSetOf<String>()
        private val root = File(base.cacheDir, "tester-storage/$suffix").canonicalFile.also {
            check(!it.exists() || it.deleteRecursively())
        }
        private val isolatedFiles = File(root, "files").apply(::requireDirectory)
        private val isolatedCache = File(root, "cache").apply(::requireDirectory)
        private val isolatedNoBackup = File(root, "no-backup").apply(::requireDirectory)

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$preferencePrefix$name"
            preferenceNames += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun preferenceSnapshot(): Map<String, Map<String, String>> = preferenceNames
            .sorted()
            .associateWith { name ->
                baseContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    .toSortedMap()
                    .mapValues { (_, value) -> value.toString() }
            }

        fun destroy() {
            preferenceNames.forEach(baseContext::deleteSharedPreferences)
            check(!root.exists() || root.deleteRecursively())
        }

        private companion object {
            fun requireDirectory(directory: File) {
                check(directory.isDirectory || directory.mkdirs())
            }
        }
    }

    private companion object {
        const val EXACT_VIDEO_ID = "TstExact001"
        const val FOREIGN_VIDEO_ID = "TstFrgn0001"
        const val UNSAFE_VIDEO_ID = "TstUnkn0001"
    }
}
