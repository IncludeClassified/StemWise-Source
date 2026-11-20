package app.stemwise.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.bridge.BridgeSongStore
import app.stemwise.bridge.SongBridgeRepository
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.integration.PlaylistLink
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class PlaylistPcHandoffInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun newlyAvailableEntriesCreateOneLocalAuthorizationDraft() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val owner = SupervisorJob()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            external.reconcile()
            val installed = checkNotNull(dao.findSongById(VIDEO_ID))
            val link = PlaylistLink("PL123456789012345", "https://music.youtube.com/playlist?list=PL123456789012345")
            dao.upsertSelectedPlaylist(SelectedPlaylistEntity(root.libraryId, link.id, link.canonicalUrl, "Playlist", null, null))
            val preferences = StemwisePreferences(context, database = database)
            preferences.activateLibraryFromRoot(root.libraryId)
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            fun song(id: String, state: String) = SongEntity(id, id, "item-$id", "https://music.youtube.com/watch?v=$id",
                id, "Artist", null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = true, highLevelState = state, acquiredAtEpochMs = null, createdAtEpochMs = 1L)
            val unavailable = song("NEW00000001", "NEEDS_ATTENTION")
            val deleted = song("NEW00000002", "NEEDS_ATTENTION")
            val acknowledged = song("OLD00000001", "DISCOVERED")
            dao.upsertSongs(listOf(acknowledged))
            val store = BridgeSongStore(java.io.File(context.noBackupFilesDir, "pc-song-jobs"))
            var remoteCalls = 0
            var scheduled = 0
            val bridge = SongBridgeRepository(context, roots,
                songImporter(context, roots, mutations, bindings, background, database), external, dao, background,
                producerReady = { true }, blockedIds = { setOf(deleted.id) }, prepareExplicitReadd = { _, _ -> },
                store = store, transportFor = { remoteCalls++; error("Discovery contacted Bridge") },
                scope = CoroutineScope(owner + Dispatchers.IO), schedule = { _, _ -> scheduled++ })
            val repository = PlaylistRepository(context, database, dao, preferences,
                YoutubeDlEngine(context, network, roots), SongWorkCoordinator(), roots, external, background, network,
                onPlaylistCommitted = { bridge.queuePlaylistSongs(it) })
            suspend fun refresh(items: List<SongEntity>) {
                val existing = dao.allSongs()
                val candidate = PlaylistCandidate(link, "Playlist", items, link.id,
                    existing.count { it.currentlyInSourcePlaylist }, existing.size,
                    existing.count { !it.currentlyInSourcePlaylist }, playlistMembershipSha256(existing),
                    PlaylistSessionToken(root.libraryId, root.treeUri.toString(), 0L))
                repository.persistPlaylistCandidate(candidate, PlaylistCommitMode.REFRESH_CURRENT)
                withTimeout(5_000L) { bridge.requestResume().join() }
                assertNull(bridge.state.value.error)
            }
            val initial = listOf(unavailable, deleted, acknowledged,
                installed.copy(highLevelState = "NEEDS_ATTENTION"))
            refresh(initial)
            assertTrue(store.records().isEmpty())
            assertEquals("NEEDS_ATTENTION", dao.findSongById(unavailable.id)?.highLevelState)
            assertFalse(dao.allRemotePlaylistItems().single { it.packageId == unavailable.id }.available)
            val available = initial.map { it.copy(highLevelState = "DISCOVERED") }
            refresh(available)
            val draft = BridgeSongStore(store.directory).records().single()
            assertEquals(unavailable.id, draft.task.videoId)
            assertTrue(draft.task.isDraft)
            assertNull(draft.approval)
            assertEquals("Waiting for authorization", bridge.tasks.value.single().stage)
            assertTrue(dao.allRemotePlaylistItems().single { it.packageId == unavailable.id }.available)
            repeat(2) { refresh(available) }
            assertEquals(listOf(draft.task.id), BridgeSongStore(store.directory).records().map { it.task.id })
            assertEquals(listOf(unavailable.id), dao.pendingPcSongs().map { it.id })
            assertEquals("DISCOVERED", dao.findSongById(acknowledged.id)?.highLevelState)
            assertEquals("DISCOVERED", dao.findSongById(deleted.id)?.highLevelState)
            assertEquals(installed.highLevelState, dao.findSongById(installed.id)?.highLevelState)
            assertEquals(installed.packageDocumentUri, dao.findSongById(installed.id)?.packageDocumentUri)
            bridge.forget(draft.task.id).getOrThrow()
            refresh(initial.map { if (it.id == unavailable.id) it.copy(playlistItemId = null) else it })
            assertFalse(dao.allRemotePlaylistItems().single { it.packageId == unavailable.id }.available)
            repeat(2) { refresh(available) }
            assertTrue(BridgeSongStore(store.directory).records().isEmpty())
            assertTrue(dao.pendingPcSongs().isEmpty())
            val duplicate = unavailable.copy(playlistItemId = "duplicate-member", highLevelState = "DISCOVERED")
            repeat(2) {
                refresh(available + duplicate)
                refresh(listOf(duplicate) + available)
            }
            assertTrue(BridgeSongStore(store.directory).records().isEmpty())
            assertTrue(dao.pendingPcSongs().isEmpty())
            assertEquals(unavailable.playlistItemId, dao.findSongById(unavailable.id)?.playlistItemId)
            // Removing the dismissed occurrence really does discover the other member.
            refresh(available.filterNot { it.id == unavailable.id } + duplicate)
            val replacementDraft = BridgeSongStore(store.directory).records().single()
            assertEquals(unavailable.id, replacementDraft.task.videoId)
            bridge.forget(replacementDraft.task.id).getOrThrow()
            // Missing provider IDs remain ambiguous rather than inventing replacement identity.
            val nullIds = available.map { if (it.id == unavailable.id) it.copy(playlistItemId = null) else it }
            refresh(nullIds + duplicate.copy(playlistItemId = null))
            assertTrue(BridgeSongStore(store.directory).records().isEmpty())
            // A completed removal followed by readdition is new even without a remote item ID.
            refresh(available.filterNot { it.id == unavailable.id })
            refresh(nullIds)
            val readdedDraft = BridgeSongStore(store.directory).records().single()
            assertEquals(unavailable.id, readdedDraft.task.videoId)
            bridge.forget(readdedDraft.task.id).getOrThrow()
            refresh(available)
            assertTrue(BridgeSongStore(store.directory).records().isEmpty())
            // A different playlist member is a new discovery, even with the same video.
            refresh(available.map { if (it.id == unavailable.id) it.copy(playlistItemId = "replacement-member") else it })
            assertEquals(listOf(unavailable.id), BridgeSongStore(store.directory).records().map { it.task.videoId })
            assertEquals(0, remoteCalls)
            assertEquals(0, scheduled)
        } finally { owner.cancelAndJoin(); database.close() }
    }

    @Test fun preparationOwnerReadsOnlyActiveJobsAsTheLibraryGrows() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        seedDirectMedia(withEnrichment = true)
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val stored = database.libraryDao()
            var factsCalls = 0
            var factsRows = 0
            var allSongRows = 0
            var sourceLookups = 0
            val counted = object : LibraryDao by stored {
                override suspend fun automaticPreparationOwnerFacts(): List<PreparationJobFact> =
                    stored.automaticPreparationOwnerFacts().also { factsCalls++; factsRows += it.size }
                override suspend fun allSongs(): List<SongEntity> =
                    stored.allSongs().also { allSongRows += it.size }
                override suspend fun currentSource(songId: String): SourceVersionEntity? =
                    stored.currentSource(songId).also { sourceLookups++ }
            }
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, counted)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, counted)
            val external = ExternalLibraryRepository(context, roots, counted, bindings, mutations, background)
            external.reconcile()
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val repository = PlaylistRepository(context, database, counted,
                StemwisePreferences(context, database = database), YoutubeDlEngine(context, network, roots),
                SongWorkCoordinator(), roots, external, background, network)
            val source = checkNotNull(stored.currentSource(VIDEO_ID))
            val plan = app.stemwise.integration.MvsepSourcePlan(listOf("bass"), listOf(
                app.stemwise.integration.MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                    setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            val authority = app.stemwise.integration.createPortableSourceJobAuthority(root.libraryId,
                VIDEO_ID, source.id, source.sha256, plan, 1L)
            roots.writeExactSongSidecar(root, VIDEO_ID, SelectedLibraryContract.SOURCE_JOB_AUTHORITY,
                "application/json", authority.toBytes(), app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
            stored.upsertJob(app.stemwise.integration.projectSourceJob(authority))
            val prototype = checkNotNull(stored.findSongById(VIDEO_ID))
            val providerCounts = mutableListOf<Long>()
            for (size in listOf(10, 10_000)) {
                stored.upsertSongs((0 until size).map { index ->
                    val id = "P${index.toString().padStart(10, '0')}"
                    prototype.copy(id = id, youtubeVideoId = id, canonicalExternal = false,
                        packageDocumentUri = null, highLevelState = "DISCOVERED")
                })
                factsCalls = 0; factsRows = 0; allSongRows = 0; sourceLookups = 0
                providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                val owner = repository.reconcilePreparationOwner(root) as PreparationOwnerDecision.Separation
                assertEquals(authority.jobId, owner.jobId)
                assertEquals(1, factsCalls)
                assertEquals(1, factsRows)
                assertEquals(0, allSongRows)
                assertEquals(0, sourceLookups)
                val metrics = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
                assertTrue(metrics.getStringArrayList("queryChildParents").orEmpty().none { it == "root" })
                providerCounts += metrics.getLong("returnedDocumentRows")
            }
            assertEquals(providerCounts.first(), providerCounts.last())
        } finally { database.close() }
    }

    @Test fun failedDiagnosticWriteCannotLeaveTheCompletedSyncRunning() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        seedDirectMedia(withEnrichment = true)
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val selected = SelectedPlaylistEntity(root.libraryId, "PL123456789012345",
                "https://music.youtube.com/playlist?list=PL123456789012345", "Fixture", null, null)
            dao.upsertSelectedPlaylist(selected)
            val preferences = StemwisePreferences(context, database = database)
            preferences.activateLibraryFromRoot(root.libraryId)
            val failure = java.io.IOException("network fixture unavailable")
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState(): AppNetworkState = throw failure
            }
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            external.reconcile()
            val beforeSongs = dao.allSongs()
            val repository = PlaylistRepository(context, database, dao, preferences,
                YoutubeDlEngine(context, network, roots), SongWorkCoordinator(), roots, external, background, network)
            database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_sync_diagnostic BEFORE INSERT ON selected_playlists
                BEGIN SELECT RAISE(ABORT, 'diagnostic fixture unavailable'); END""")
            val result = repository.sync()
            assertSame(failure, result.exceptionOrNull())
            assertFalse(repository.syncStatus.value.running)
            assertEquals("Could not reach YouTube Music", repository.syncStatus.value.error)
            assertTrue(failure.suppressed.isNotEmpty())
            assertEquals(selected, dao.selectedPlaylist(root.libraryId))
            assertEquals(beforeSongs, dao.allSongs())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_sync_diagnostic")
            assertSame(failure, repository.sync().exceptionOrNull())
            assertFalse(repository.syncStatus.value.running)
            assertEquals("Could not reach YouTube Music", dao.selectedPlaylist(root.libraryId)?.lastSyncError)
            assertEquals(beforeSongs, dao.allSongs())
        } finally { database.close() }
    }

    @Test fun secondSourceGenerationUsesActualPortableAuthorityDuringArbitration() = verifyGeneration(2L)
    @Test fun thirdSourceGenerationUsesActualPortableAuthorityDuringArbitration() = verifyGeneration(3L)

    private fun verifyGeneration(generation: Long) = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        seedDirectMedia(withEnrichment = true)
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            external.reconcile()
            val preferences = StemwisePreferences(context, database = database)
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val repository = PlaylistRepository(context, database, dao, preferences,
                YoutubeDlEngine(context, network, roots), SongWorkCoordinator(), roots, external, background, network)
            val source = checkNotNull(dao.currentSource(VIDEO_ID))
            val plan = app.stemwise.integration.MvsepSourcePlan(listOf("bass"), listOf(
                app.stemwise.integration.MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                    setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            var authority = app.stemwise.integration.createPortableSourceJobAuthority(root.libraryId,
                VIDEO_ID, source.id, source.sha256, plan, System.currentTimeMillis(), generation)
            suspend fun publish() {
                roots.writeExactSongSidecar(root, VIDEO_ID,
                    app.stemwise.integration.sourceAuthorityFileNames(generation).first,
                    "application/json", authority.toBytes(), app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
                dao.upsertJob(app.stemwise.integration.projectSourceJob(authority))
            }
            publish()
            val owner = repository.reconcilePreparationOwner(root) as PreparationOwnerDecision.Separation
            assertEquals(authority.jobId, owner.jobId)
            authority = app.stemwise.integration.transitionPortableSourceJob(authority,
                app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION, authority.updatedAtEpochMs + 1)
            authority = app.stemwise.integration.transitionPortableSourceJob(authority,
                app.stemwise.integration.SourceJobTransition.MARK_SUBMISSION_UNKNOWN, authority.updatedAtEpochMs + 1)
            publish()
            val blocked = repository.reconcilePreparationOwner(root) as PreparationOwnerDecision.Blocked
            assertEquals("PREPARATION_SUBMISSION_UNKNOWN", blocked.code)
            assertEquals(authority.jobId, blocked.recoverableJobId)
        } finally {
            database.close()
        }
    }

    @Test fun committedNewMembershipsSurviveRefreshAndFailureWithoutAdmittingOldBacklog() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        grantProviderTree()
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.libraryDao()
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, dao)
            roots.commitValidatedCandidateForDevelopment(
                roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val preferences = StemwisePreferences(context, database = database)
            preferences.activateLibraryFromRoot(root.libraryId)
            val network = object : AppNetworkAccess {
                override val state = MutableStateFlow(AppNetworkState(false, false, true))
                override fun currentState() = state.value
            }
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, dao)
            val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background)
            fun song(id: String, member: Boolean = true, state: String = "DISCOVERED") = SongEntity(
                id, id, "item-$id", "https://music.youtube.com/watch?v=$id", id, "Artist",
                null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = member, highLevelState = state,
                acquiredAtEpochMs = null, createdAtEpochMs = 1L)
            val backlog = song("OLD00000001")
            val returning = song("OLD00000002", member = false)
            val installed = song("OLD00000003", member = false, state = "READY")
                .copy(packageDocumentUri = "content://provider/existing-package")
            dao.upsertSongs(listOf(backlog, returning, installed))
            var notifications = 0
            var failNotification = false
            suspend fun commit(items: List<SongEntity>, mode: PlaylistCommitMode = PlaylistCommitMode.KEEP_AND_ADD): Result<PlaylistSyncSummary> {
                val existing = dao.allSongs()
                val repository = PlaylistRepository(context, database, dao, preferences,
                    YoutubeDlEngine(context, network, roots), SongWorkCoordinator(), roots, external,
                    background, network, onPlaylistCommitted = { authority ->
                        assertEquals(root.authority(), authority)
                        assertTrue(dao.pendingPcSongs().isNotEmpty())
                        notifications++
                        if (failNotification) error("NOTIFICATION_FAILED")
                    })
                val candidate = PlaylistCandidate(PlaylistLink("PL123456789012345", "https://music.youtube.com/playlist?list=PL123456789012345"),
                    "Playlist", items, preferences.playlist.value.link.id,
                    existing.count { it.currentlyInSourcePlaylist }, existing.size,
                    existing.count { !it.currentlyInSourcePlaylist }, playlistMembershipSha256(existing),
                    PlaylistSessionToken(root.libraryId, root.treeUri.toString(), 0L))
                if (mode == PlaylistCommitMode.REFRESH_CURRENT) {
                    return runCatching { repository.persistPlaylistCandidate(candidate, mode) }
                }
                val lease = repository.beginExclusivePlaylistCommit(candidate, root.authority())
                return try {
                    repository.commitPlaylistCandidate(candidate, mode, lease) {}
                } finally {
                    repository.finishExclusivePlaylistCommit(lease, reopenSession = true)
                }
            }
            val added = song("NEW00000001")
            val unavailable = song("NEW00000002", state = "NEEDS_ATTENTION")
            val discovered = listOf(backlog, returning.copy(currentlyInSourcePlaylist = true),
                installed.copy(currentlyInSourcePlaylist = true), added, unavailable)
            commit(discovered).getOrThrow()
            assertEquals(setOf(returning.id, added.id), dao.pendingPcSongs().map { it.id }.toSet())
            assertEquals("DISCOVERED", dao.findSongById(backlog.id)?.highLevelState)
            assertEquals("READY", dao.findSongById(installed.id)?.highLevelState)
            assertEquals("NEEDS_ATTENTION", dao.findSongById(unavailable.id)?.highLevelState)
            failNotification = true
            val committedWithPendingRefresh = commit(discovered).getOrThrow()
            assertTrue(committedWithPendingRefresh.projectionRefreshPending)
            assertEquals("PL123456789012345", dao.selectedPlaylist(root.libraryId)?.playlistId)
            preferences.activateLibraryFromRoot(root.libraryId)
            assertEquals("PL123456789012345", preferences.playlist.value.link.id)
            assertEquals(2, notifications)
            assertEquals(setOf(returning.id, added.id), dao.pendingPcSongs().map { it.id }.toSet())
            failNotification = false
            commit(discovered.filterNot { it.id == returning.id }).getOrThrow()
            assertEquals("AWAITING_PC", dao.findSongById(returning.id)?.highLevelState)
            assertFalse(checkNotNull(dao.findSongById(returning.id)).currentlyInSourcePlaylist)
            val beforeFailure = dao.allSongs()
            database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_playlist BEFORE INSERT ON selected_playlists
                BEGIN SELECT RAISE(ABORT, 'transaction fault'); END""")
            assertTrue(commit(discovered + song("NEW00000003")).isFailure)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_playlist")
            assertEquals(beforeFailure, dao.allSongs())
            assertEquals(3, notifications)
            assertEquals(1, dao.clearPendingPcSong(added.id))
            assertEquals(0, dao.clearPendingPcSong(added.id))
            assertEquals(0, dao.clearPendingPcSong(installed.id))
            assertEquals("READY", dao.findSongById(installed.id)?.highLevelState)
            commit(discovered.filterNot { it.id == returning.id }).getOrThrow()
            assertEquals("DISCOVERED", dao.findSongById(added.id)?.highLevelState)
            assertEquals(listOf(returning.id), dao.pendingPcSongs().map { it.id })
            val installedBefore = checkNotNull(dao.findSongById(installed.id))
            for (occurrences in listOf(listOf(installed, backlog, installed),
                listOf(installed, installed, backlog), listOf(installed, backlog),
                listOf(installed), emptyList())) {
                commit(occurrences, PlaylistCommitMode.REFRESH_CURRENT).getOrThrow()
                val stored = dao.allRemotePlaylistItems()
                assertEquals(occurrences.map { it.id }, stored.map { it.packageId })
                assertEquals(occurrences.indices.toList(), stored.map { it.position })
                assertEquals(stored.size, stored.map { it.remoteItemId }.distinct().size)
                assertEquals(1, dao.allSongs().count { it.id == installed.id })
                val retained = checkNotNull(dao.findSongById(installed.id))
                assertEquals(installedBefore.packageDocumentUri, retained.packageDocumentUri)
                assertEquals(installedBefore.manifestFileSha256, retained.manifestFileSha256)
                assertEquals(occurrences.any { it.id == installed.id }, retained.currentlyInSourcePlaylist)
            }
        } finally {
            database.close()
        }
    }
}
