package app.stemwise.data

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.integration.PlaylistLink
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AuthoritativePersistenceFailureInstrumentationTest {
    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rejectedRoomRootSelectionPreservesPriorAuthorityAndProjectionAtomically() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (hasPriorRoot in listOf(false, true)) {
            resetProvider(); grantProviderTree()
            val context = IsolatedPersistenceContext(targetContext)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            var extraTree: Uri? = null
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                val tree = if (hasPriorRoot) {
                    roots.commitValidatedCandidateForDevelopment(
                        roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                    dao.upsertSongs(listOf(SongEntity(
                        id = "ROOTKEEP001",
                        youtubeVideoId = "ROOTKEEP001",
                        playlistItemId = null,
                        canonicalUrl = "https://music.youtube.com/watch?v=ROOTKEEP001",
                        sourceTitle = "Retain",
                        sourceArtist = "Owner",
                        displayTitle = null,
                        displayArtist = null,
                        durationMs = 240L,
                        artworkPath = null,
                        currentlyInSourcePlaylist = false,
                        highLevelState = "READY",
                        acquiredAtEpochMs = 1L,
                        createdAtEpochMs = 1L
                    )))
                    roots.reportRuntimeAccessFailure(checkNotNull(roots.captureHandle()), SecurityException("isolated root loss"))
                    File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider/rejected-selection")
                        .apply { check(mkdir()) }
                    android.provider.DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY,
                        "root/rejected-selection").also {
                        targetContext.grantUriPermission(targetContext.packageName, it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        extraTree = it
                    }
                } else GoldenQaDocumentsProvider.treeUri
                val candidate = roots.validateCandidate(tree).getOrThrow()
                val priorIdentity = dao.libraryRootIdentity()
                val priorSongs = dao.allSongs()
                val sqlite = database.openHelper.writableDatabase
                for (action in listOf("INSERT", "UPDATE")) sqlite.execSQL("""
                    CREATE TRIGGER fail_root_${action.lowercase()} BEFORE $action ON library_root_identity
                    WHEN NEW.state = 'RESTORING'
                    BEGIN SELECT RAISE(ABORT, 'injected root selection transaction'); END
                """.trimIndent())
                val failure = roots.commitValidatedCandidateForDevelopment(candidate).exceptionOrNull()
                assertTrue("Local failure cause was lost: $failure", generateSequence(failure) { it.cause }
                    .any { it.message.orEmpty().contains("injected root selection transaction") })
                assertEquals(priorIdentity, dao.libraryRootIdentity())
                assertEquals(priorSongs, dao.allSongs())
                assertFalse(roots.state.value.ready)
                assertTrue(SelectedLibrarySnapshotReader(context).readRootEntries(candidate.rootDocument)
                    .none { it.displayName in SelectedLibraryContract.rootAuthorityFiles })
                for (action in listOf("insert", "update")) sqlite.execSQL("DROP TRIGGER fail_root_$action")
                val restarted = LibraryRootManager(context, mutations, dao).initialize()
                if (hasPriorRoot) {
                    assertTrue(restarted.ready)
                    assertEquals(priorIdentity!!.libraryId, restarted.libraryId)
                    assertEquals(priorIdentity.treeUri, restarted.treeUri.toString())
                    assertEquals(priorIdentity.epoch, restarted.epoch)
                    assertEquals(priorSongs, dao.allSongs())
                } else {
                    assertFalse(restarted.ready)
                    assertNull(dao.libraryRootIdentity())
                }
            } finally {
                database.close()
                extraTree?.let { targetContext.revokeUriPermission(it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                revokeProviderTree(); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun durableRoomSelectionRecoversExternalAndVerifiedCheckpointFailuresDespiteStalePreferences() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (manifestFailure in listOf(true, false)) {
            resetProvider(); grantProviderTree()
            val context = IsolatedPersistenceContext(targetContext)
            val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                val dao = database.libraryDao()
                val mutations = PackageMutationCoordinator(context)
                val roots = LibraryRootManager(context, mutations, dao)
                val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
                val sqlite = database.openHelper.writableDatabase
                if (manifestFailure) providerFault(GoldenQaDocumentsProvider.FAULT_CREATE_REFUSAL)
                else sqlite.execSQL("""
                    CREATE TRIGGER fail_root_verified BEFORE UPDATE ON library_root_identity
                    WHEN NEW.state = 'VERIFIED'
                    BEGIN SELECT RAISE(ABORT, 'injected verified checkpoint'); END
                """.trimIndent())
                val failure = roots.commitValidatedCandidateForDevelopment(candidate).exceptionOrNull()
                assertNotNull(failure)
                val durable = checkNotNull(dao.libraryRootIdentity())
                assertEquals(candidate.libraryId, durable.libraryId)
                assertEquals(candidate.treeUri.toString(), durable.treeUri)
                assertEquals("RESTORING", durable.state)
                assertFalse(roots.state.value.ready)
                assertEquals("ROOT_RESTORING", roots.state.value.errorCode)
                assertNull(roots.captureHandle())
                providerFault(GoldenQaDocumentsProvider.FAULT_NONE)
                if (!manifestFailure) sqlite.execSQL("DROP TRIGGER fail_root_verified")
                val stale = context.getSharedPreferences("stemwise_library_root", Context.MODE_PRIVATE)
                check(stale.edit().putString("library_id", "stale-library").putString("tree_uri", "content://stale/tree/other")
                    .putLong("root_epoch", durable.epoch + 100L).commit())
                val staleBefore = stale.all.toMap()
                val repaired = LibraryRootManager(context, mutations, dao).initialize()
                assertTrue("Durable Room selection failed to recover: $repaired", repaired.ready)
                assertEquals(durable.libraryId, repaired.libraryId)
                assertEquals(durable.treeUri, repaired.treeUri.toString())
                assertEquals(durable.epoch, repaired.epoch)
                assertEquals("VERIFIED", dao.libraryRootIdentity()?.state)
                assertEquals(staleBefore, stale.all.toMap())
                val folder = File(targetContext.noBackupFilesDir, "golden-enrichment-document-provider")
                for (name in listOf(SelectedLibraryContract.ROOT_MANIFEST, SelectedLibraryContract.ROOT_MANIFEST_BACKUP)) {
                    assertEquals(durable.libraryId, parsePortableLibraryId(File(folder, name).readBytes()))
                }
            } finally {
                providerFault(GoldenQaDocumentsProvider.FAULT_NONE)
                database.close(); revokeProviderTree(); resetProvider(); context.destroy()
            }
        }
    }

    @Test
    fun retainCommittedRootForRecoveryDemotesBeforeACompoundRoomFailure() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedPersistenceContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), dao)
            val committed = roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val before = dao.libraryRootIdentity()
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("""
                CREATE TRIGGER fail_root_restoring BEFORE UPDATE ON library_root_identity
                WHEN NEW.state = 'RESTORING'
                BEGIN SELECT RAISE(ABORT, 'injected recovery checkpoint'); END
            """.trimIndent())
            val activationFailure = IllegalStateException("ACTIVE_LIBRARY_COMMIT_FAILED")
            val recoveryFailure = runCatching { roots.retainCommittedRootForRecovery(committed) }.exceptionOrNull()
            val result = failedLibrarySwitchResult<Unit>(activationFailure, recoveryFailure)
            assertSame(activationFailure, result.exceptionOrNull())
            assertTrue(generateSequence(recoveryFailure) { it.cause }
                .any { it.message.orEmpty().contains("injected recovery checkpoint") })
            assertEquals(listOf(recoveryFailure), activationFailure.suppressed.toList())
            assertEquals(before, dao.libraryRootIdentity())
            assertFalse(roots.state.value.ready)
            assertEquals("ROOT_RESTORING", roots.state.value.errorCode)
            assertEquals(committed.libraryId, roots.state.value.libraryId)
            assertEquals(committed.epoch, roots.state.value.epoch)
            assertNull(roots.captureHandle())
        } finally {
            database.close(); revokeProviderTree(); resetProvider(); context.destroy()
        }
    }

    @Test
    fun runtimeRootDemotionRetainsTheOriginalFailureWhenPersistenceIsRejected() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedPersistenceContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val roots = LibraryRootManager(context, PackageMutationCoordinator(context), dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val before = dao.libraryRootIdentity()
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER fail_root_access BEFORE UPDATE ON library_root_identity
                BEGIN SELECT RAISE(ABORT, 'injected access demotion'); END
            """.trimIndent())
            val accessFailure = SecurityException("provider grant revoked")
            val demoted = roots.reportRuntimeAccessFailure(handle, accessFailure)
            assertFalse(demoted.ready)
            assertNull(roots.captureHandle())
            assertEquals("ROOT_PERMISSION_LOST", demoted.errorCode)
            assertEquals(before, dao.libraryRootIdentity())
            assertTrue(accessFailure.suppressed.any { suppressed ->
                generateSequence(suppressed) { it.cause }
                    .any { it.message.orEmpty().contains("injected access demotion") }
            })
        } finally {
            database.close(); revokeProviderTree(); resetProvider(); context.destroy()
        }
    }

    @Test
    fun sourceBindingCommitRetryRestartTombstoneAndMalformedAuthorityFailClosed() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedPersistenceContext(targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val mutations = PackageMutationCoordinator(context)
            val dao = database.libraryDao()
            val roots = LibraryRootManager(context, mutations, dao)
            val candidate = roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()
            roots.commitValidatedCandidateForDevelopment(candidate).getOrThrow()
            val handle = checkNotNull(roots.captureHandle())
            val videoId = "AbCdEf_12-3"
            val sourceId = "youtube-video:$videoId"
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                """
                CREATE TRIGGER fail_source_binding_insert
                BEFORE INSERT ON source_bindings
                BEGIN
                    SELECT RAISE(ABORT, 'source binding test failure');
                END
                """.trimIndent()
            )
            val registry = SourceBindingRegistry(context, roots, mutations, dao)

            assertEquals(
                "SOURCE_BINDING_COMMIT_FAILED",
                runCatching {
                    mutations.withPackageMutation(handle, videoId, "FLAT_MEDIA_COMPLETE", "activation-failed",
                        requireExactRoot = { roots.requireSameLibrary(handle) }) { journal ->
                        registry.activateUnderPackageLock(handle, sourceId, videoId, "activation-failed", journal)
                        journal.complete()
                    }
                }
                    .exceptionOrNull()?.message
            )
            assertTrue(registry.activePackageIdsBySource(handle).isEmpty())
            assertNull(dao.sourceBinding(handle.libraryId, sourceId))
            sqlite.execSQL("DROP TRIGGER fail_source_binding_insert")
            mutations.withPackageMutation(
                handle,
                videoId,
                "FLAT_MEDIA_COMPLETE",
                "activate-retry",
                requireExactRoot = { roots.requireSameLibrary(handle) }
            ) { journal ->
                registry.activateUnderPackageLock(
                    handle,
                    sourceId,
                    videoId,
                    "activate-retry",
                    journal
                )
                journal.complete(JSONObject().put("result", "FLAT_MEDIA_COMPLETE"))
            }
            assertEquals(videoId, registry.activePackageIdsBySource(handle)[sourceId])

            mutations.withPackageMutation(
                handle,
                videoId,
                "PACKAGE_DELETE",
                "delete-tombstone",
                requireExactRoot = { roots.requireSameLibrary(handle) }
            ) { journal ->
                registry.markDeletedUnderPackageLock(
                    handle,
                    sourceId,
                    videoId,
                    "delete-tombstone",
                    journal
                )
                journal.complete(JSONObject().put("sourceId", sourceId))
            }
            val restarted = SourceBindingRegistry(context, roots, mutations, dao)
            assertTrue(sourceId in restarted.blockedSourceIds(handle))

            // Current Room authority rejects malformed durable rows without erasing the tombstone.
            sqlite.execSQL("UPDATE source_bindings SET state = 'MALFORMED' WHERE libraryId = ? AND sourceId = ?",
                arrayOf(handle.libraryId, sourceId))
            assertEquals("SOURCE_BINDING_AUTHORITY_INVALID", runCatching {
                restarted.blockedSourceIds(handle)
            }.exceptionOrNull()?.message)
            assertEquals("MALFORMED", dao.sourceBinding(handle.libraryId, sourceId)?.state)
        } finally {
            database.close()
            revokeProviderTree()
            resetProvider()
            context.destroy()
        }
    }

    private fun playlist(id: String) = PlaylistLink(
        id = id,
        canonicalUrl = "https://music.youtube.com/playlist?list=$id"
    )

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

    private fun providerFault(fault: String) {
        checkNotNull(targetContext.contentResolver.call(Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
            GoldenQaDocumentsProvider.METHOD_SET_FAULT, fault, null))
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

    private class IsolatedPersistenceContext(base: Context) : ContextWrapper(base) {
        private val namespace = UUID.randomUUID().toString()
        private val preferenceNames = linkedSetOf<String>()
        private val root = File(base.cacheDir, "persistence-fault/$namespace").canonicalFile
        private val isolatedFiles = File(root, "files").apply { check(mkdirs()) }
        private val isolatedCache = File(root, "cache").apply { check(mkdirs()) }
        private val isolatedNoBackup = File(root, "no-backup").apply { check(mkdirs()) }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = isolatedFiles
        override fun getCacheDir(): File = isolatedCache
        override fun getNoBackupFilesDir(): File = isolatedNoBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "persistence-fault-$namespace-$name"
            preferenceNames += isolated
            return baseContext.getSharedPreferences(isolated, mode)
        }

        fun destroy() {
            preferenceNames.forEach(baseContext::deleteSharedPreferences)
            check(!root.exists() || root.deleteRecursively())
        }
    }
}
