package app.stemwise.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalProjectionPromotionTest {
    private lateinit var database: LibraryDatabase

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LibraryDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun automaticPreparationAdmitsOnlyCurrentSourceSelectionOwnersAndKeepsOtherJobRows() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertSongs(listOf(song(canonical = false, playlistItemId = null, inPlaylist = false)))
        val jobs = listOf("SOURCE_SELECTION" to "CREATED", "SOURCE_SELECTION" to "RUNNING",
            "SOURCE_SELECTION" to "FAILED", "SOURCE_SELECTION" to "COMPLETE", "SOURCE_SELECTION" to "OBSOLETE",
            "SOURCE_ACQUISITION" to "CREATED", "SOURCE_ACQUISITION" to "RUNNING").mapIndexed { index, (kind, state) ->
            ProcessingJobEntity("owner-$index", PACKAGE_ID, null, kind, null, null, state, null, 0,
                null, null, null, index.toLong(), index.toLong())
        }
        jobs.forEach { dao.upsertJob(it) }
        assertEquals(listOf("owner-0", "owner-1"), dao.automaticPreparationOwnerJobs().map { it.id })
        assertEquals(listOf("owner-0", "owner-1"), dao.automaticPreparationOwnerFacts().map { it.jobId })
        jobs.forEach { assertEquals(it, dao.findJobById(it.id)) }
    }

    @Test
    fun realRoomOrderingDistinguishesMissingPositionFromIntMaxAndUsesStableIds() = runBlocking {
        val dao = database.libraryDao()
        val base = song(canonical = true, playlistItemId = null, inPlaylist = true)
        val ids = listOf("missing", "real-max", "same-b", "same-a", "outside")
        dao.upsertSongs(ids.map { id -> base.copy(id = id, youtubeVideoId = id,
            createdAtEpochMs = if (id == "missing") 0L else 1L, currentlyInSourcePlaylist = id != "outside") })
        dao.upsertRemotePlaylist(RemotePlaylistEntity("p", "youtube", "https://example.invalid/p", "Fixture", 1, 1))
        dao.upsertRemotePlaylistItems(listOf(
            RemotePlaylistItemEntity("p", "max", "max", "real-max", Int.MAX_VALUE, true),
            RemotePlaylistItemEntity("p", "b", "b", "same-b", 8, true)))
        dao.upsertRemotePlaylist(RemotePlaylistEntity("q", "youtube", "https://example.invalid/q", "Fixture", 1, 1))
        dao.upsertRemotePlaylistItems(listOf(RemotePlaylistItemEntity("q", "a", "a", "same-a", 8, true)))
        assertEquals(listOf("same-a", "same-b", "real-max", "missing", "outside"),
            dao.observeSongSnapshots().first().map { it.song.id })
    }

    @Test
    fun installedUnavailableOccurrencesRetainTheirPositionAndSequentialNext() = runBlocking {
        val dao = database.libraryDao()
        val ids = listOf("ORDER000001", "ORDER000002", "ORDER000003")
        dao.upsertSongs(ids.map { id -> song(true, "item-$id", true).copy(id = id, youtubeVideoId = id) })
        dao.upsertRemotePlaylist(RemotePlaylistEntity("p", "youtube", "https://example.invalid/p", "Fixture", 1, 1))
        val items = ids.mapIndexed { index, id ->
            RemotePlaylistItemEntity("p", "item-$id", "source-$id", id, index, index != 1)
        }
        dao.upsertRemotePlaylistItems(items + items[1].copy(remoteItemId = "duplicate-b", position = 9))
        val ordered = dao.observeSongSnapshots().first().map { it.song.id }
        assertEquals(ids, ordered)
        assertFalse(dao.allRemotePlaylistItems().first { it.remoteItemId == "item-${ids[1]}" }.available)
        val queue = app.stemwise.playback.PlaybackQueueController()
        queue.start(ordered, ids.first(), shuffle = false)
        assertEquals(app.stemwise.playback.QueueDecision.Move(ids[1], 1),
            queue.decide(app.stemwise.playback.QueueTransition.EXPLICIT_NEXT))
    }

    @Test
    fun operationalRowPromotesWithoutLosingPlaylistOrRemoteJob() = runBlocking {
        val dao = database.libraryDao()
        val now = System.currentTimeMillis()
        val operational = song(canonical = false, playlistItemId = "playlist-item", inPlaylist = true)
        val scratchSource = source("scratch-source", "scratch/original.opus")
        val remoteJob = ProcessingJobEntity(
            id = "job-1",
            songId = PACKAGE_ID,
            sourceVersionId = scratchSource.id,
            kind = "SOURCE_SELECTION",
            provider = "MVSEP",
            modelId = "source-plan:${"b".repeat(64)}",
            state = "PUBLISHING",
            remoteJobId = "remote-123",

            attempts = 1,
            nextAttemptAtEpochMs = now,
            lastErrorCode = null,
            lastErrorMessage = null,
            updatedAtEpochMs = now,
            createdAtEpochMs = now
        )
        dao.upsertSongs(listOf(operational))
        dao.insertSource(scratchSource)
        dao.insertSeparationSet(
            completeSet(
                id = "scratch-complete",
                source = scratchSource,
                remoteJobId = "remote-123"
            )
        )
        dao.upsertJob(remoteJob)
        val canonicalSource = source("$PACKAGE_ID:original", "content://library/original.opus")

        dao.replaceCanonicalPackageProjection(
            song = song(canonical = true, playlistItemId = null, inPlaylist = false),
            source = canonicalSource,
            sets = listOf(
                completeSet(
                    id = "$PACKAGE_ID:primary",
                    source = canonicalSource,
                    remoteJobId = "remote-123"
                )
            ),
            stems = emptyList(),
            analysis = null
        )

        val promoted = requireNotNull(dao.findSongById(PACKAGE_ID))
        assertTrue(promoted.canonicalExternal)
        assertEquals("playlist-item", promoted.playlistItemId)
        assertTrue(promoted.currentlyInSourcePlaylist)
        val retainedJob = dao.findJobById("job-1")
        assertNotNull(retainedJob)
        assertEquals("remote-123", retainedJob?.remoteJobId)
        assertEquals("PUBLISHING", retainedJob?.state)
        assertEquals(null, retainedJob?.sourceVersionId)
        assertEquals(now, retainedJob?.nextAttemptAtEpochMs)
        assertEquals(null, retainedJob?.lastErrorCode)
        assertEquals(null, retainedJob?.lastErrorMessage)
    }

    @Test
    fun canonicalRefreshKeepsPlaylistMembershipAndHistory() = runBlocking {
        val dao = database.libraryDao()
        val original = song(
            canonical = true,
            playlistItemId = "playlist-item",
            inPlaylist = true
        ).copy(createdAtEpochMs = 11L, acquiredAtEpochMs = 22L,
            artworkPath = "https://i.ytimg.com/retained.jpg",
            additionalInfoJson = """{"artworkSourceUrl":"https://i.ytimg.com/retained.jpg"}""")
        dao.upsertSongs(listOf(original))
        dao.insertSource(source("$PACKAGE_ID:original", "content://library/original.opus"))

        dao.replaceCanonicalPackageProjection(
            song = song(canonical = true, playlistItemId = null, inPlaylist = false)
                .copy(createdAtEpochMs = 99L, acquiredAtEpochMs = 100L, contentRevision = 2L,
                    artworkPath = "content://library/cover.png"),
            source = source("$PACKAGE_ID:original", "content://library/original.opus"),
            sets = emptyList(),
            stems = emptyList(),
            analysis = null
        )

        val refreshed = requireNotNull(dao.findSongById(PACKAGE_ID))
        assertEquals("playlist-item", refreshed.playlistItemId)
        assertTrue(refreshed.currentlyInSourcePlaylist)
        assertEquals(11L, refreshed.createdAtEpochMs)
        assertEquals(22L, refreshed.acquiredAtEpochMs)
        assertEquals(2L, refreshed.contentRevision)
        assertEquals("content://library/cover.png", refreshed.artworkPath)
        assertFalse(JSONObject(refreshed.additionalInfoJson).has(ROOM_ARTWORK_SOURCE_URL_KEY))
    }




    @Test
    fun incompleteCanonicalPromotionPreservesPlaylistArtworkAndOperationalRows() = runBlocking {
        val dao = database.libraryDao()
        val remoteArtwork = "https://i.ytimg.com/vi/rZNpBqlrwkI/maxresdefault.jpg"
        val operational = song(canonical = false, playlistItemId = "playlist-item", inPlaylist = true)
            .copy(artworkPath = remoteArtwork,
                additionalInfoJson = """{"artworkSourceUrl":"$remoteArtwork"}""")
        dao.upsertSongs(listOf(operational))
        val attempted = source("$PACKAGE_ID:original", "content://library/original.opus")
        val failure = runCatching {
            dao.replaceCanonicalPackageProjection(
                song(true, null, false).copy(manifestState = "prepared"),
                attempted, emptyList(), emptyList(), null)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("COMPLETE_CANONICAL_PACKAGE_REQUIRED", failure?.message)
        assertEquals(operational, dao.findSongById(PACKAGE_ID))
        assertNull(dao.currentSource(PACKAGE_ID))
        assertEquals(remoteArtwork,
            JSONObject(requireNotNull(dao.findSongById(PACKAGE_ID)).additionalInfoJson)
                .getString(ROOM_ARTWORK_SOURCE_URL_KEY))
    }

    @Test
    fun completeCanonicalPackageCannotSettleCurrentJobsOrOtherSongs() = runBlocking {
        val dao = database.libraryDao()
        val source = source("current-source", "content://library/original.opus")
        val foreignSong = song(false, "other-item", true).copy(id = CONFLICT_OWNER_ID,
            youtubeVideoId = CONFLICT_OWNER_ID)
        dao.upsertSongs(listOf(song(false, "playlist-item", true), foreignSong))
        dao.insertSource(source)
        val current = ProcessingJobEntity("current-job", PACKAGE_ID, source.id,
            "SOURCE_SELECTION", "MVSEP", "source-plan:${"b".repeat(64)}", "PROCESSING",
            "accepted-current", 1, 9L, "WAIT", "Keep tracking", 2, 1)
        val foreign = current.copy(id = "foreign-job", songId = CONFLICT_OWNER_ID,
            sourceVersionId = null, remoteJobId = "accepted-foreign")
        dao.upsertJob(current)
        dao.upsertJob(foreign)
        dao.replaceCanonicalPackageProjection(song(true, null, false), source,
            listOf(completeSet("current-set", source, "accepted-current")), emptyList(), null)
        assertEquals(current, dao.findJobById(current.id))
        assertEquals(foreign, dao.findJobById(foreign.id))
        // Reconciliation is idempotent and cannot invent acknowledgement of either job.
        dao.replaceCanonicalPackageProjection(song(true, null, false), source,
            listOf(completeSet("current-set", source, "accepted-current")), emptyList(), null)
        assertEquals(current, dao.findJobById(current.id))
        assertEquals(foreign, dao.findJobById(foreign.id))
    }


    @Test
    fun incompleteProjectionCannotReplaceCanonicalMetadataAnalysisOrSource() = runBlocking {
        val dao = database.libraryDao()
        val latest = song(canonical = true, playlistItemId = "latest-item", inPlaylist = true)
            .copy(sourceTitle = "Latest source title", sourceArtist = "Latest source artist",
                displayTitle = "Current title", displayArtist = "Current artist",
                displayAlbum = "Current album", displayYear = "2026",
                additionalInfoJson = """{"owner":"latest"}""",
                artworkPath = "content://library/cover.png", durationMs = 9000L)
        dao.upsertSongs(listOf(latest))
        assertEquals(1, dao.bindPackageDocumentUri(PACKAGE_ID, "content://library/bound"))
        val before = requireNotNull(dao.findSongById(PACKAGE_ID))
        assertEquals(latest.copy(packageDocumentUri = "content://library/bound"), before)
        val currentSource = source("current-source", "content://library/original.opus")
        dao.insertSource(currentSource)
        val currentAnalysis = AnalysisEntity("current-analysis", PACKAGE_ID, "a".repeat(64),
            "current-v1", 120.0, "C", 1.0, "4/4", 0.0, 2L)
        dao.upsertAnalysis(currentAnalysis)
        val failure = runCatching {
            dao.replaceCanonicalPackageProjection(
                before.copy(displayTitle = null, displayArtist = null, displayAlbum = null,
                    displayYear = null, additionalInfoJson = "{}", artworkPath = null,
                    manifestState = "prepared"),
                source("replacement-source", "content://library/replaced-original.opus")
                    .copy(sha256 = "b".repeat(64)), emptyList(), emptyList(), null)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("COMPLETE_CANONICAL_PACKAGE_REQUIRED", failure?.message)
        assertEquals(before, dao.findSongById(PACKAGE_ID))
        assertEquals(currentSource, dao.currentSource(PACKAGE_ID))
        assertEquals(currentAnalysis, dao.latestAnalysis(PACKAGE_ID))
    }

    @Test
    fun operationalSourceIdentityConflictPreservesCanonicalProjectionAtomically() = runBlocking {
        val dao = database.libraryDao()
        val canonicalSong = song(
            canonical = true,
            playlistItemId = "canonical-item",
            inPlaylist = true
        )
        val canonicalSource = source("canonical-source", "content://library/canonical/original.opus")
        val canonicalSet = completeSet("canonical-set", canonicalSource, "canonical-remote")
        val canonicalJob = ProcessingJobEntity(
            id = "canonical-job",
            songId = PACKAGE_ID,
            sourceVersionId = canonicalSource.id,
            kind = "SOURCE_SELECTION",
            provider = "MVSEP",
            modelId = "source-plan:${"b".repeat(64)}",
            state = "COMPLETE",
            remoteJobId = "canonical-remote",

            attempts = 1,
            nextAttemptAtEpochMs = null,
            lastErrorCode = null,
            lastErrorMessage = null,
            updatedAtEpochMs = 1,
            createdAtEpochMs = 1
        )
        val incoming = source(
            "deterministic-source",
            "content://library/replacement/original.opus"
        ).copy(sha256 = "b".repeat(64))
        val conflictOwnerSong = song(canonical = false, playlistItemId = null, inPlaylist = false)
            .copy(id = CONFLICT_OWNER_ID, youtubeVideoId = CONFLICT_OWNER_ID)
        val conflictOwnerSource = incoming.copy(
            songId = CONFLICT_OWNER_ID,
            localPath = "content://library/conflict/original.opus"
        )
        dao.upsertSongs(listOf(canonicalSong, conflictOwnerSong))
        dao.insertSource(canonicalSource)
        dao.insertSeparationSet(canonicalSet)
        dao.upsertJob(canonicalJob)
        dao.insertSource(conflictOwnerSource)

        val failure = runCatching {
            dao.replaceCanonicalPackageProjection(canonicalSong.copy(contentRevision = 2L),
                incoming, emptyList(), emptyList(), null)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(canonicalSong, dao.findSongById(PACKAGE_ID))
        assertEquals(canonicalSource, dao.currentSource(PACKAGE_ID))
        assertEquals(canonicalSet, dao.separationSetById(canonicalSet.id))
        assertEquals(canonicalJob, dao.findJobById(canonicalJob.id))
        assertEquals(conflictOwnerSource, dao.sourcesForSong(CONFLICT_OWNER_ID).single())
    }




    @Test
    fun rootSwitchSafetyFactsRetainUnknownAndAcceptedButNotFinishedJobs() = runBlocking {
        val dao = database.libraryDao()
        val now = System.currentTimeMillis()
        val currentSource = source("switch-source", "scratch/switch-original.opus")
        dao.upsertSongs(
            listOf(song(canonical = false, playlistItemId = "playlist-item", inPlaylist = true))
        )
        dao.insertSource(currentSource)
        val job = ProcessingJobEntity(
            id = "switch-job",
            songId = PACKAGE_ID,
            sourceVersionId = currentSource.id,
            kind = "SOURCE_SELECTION",
            provider = "MVSEP",
            modelId = "source-plan:${"b".repeat(64)}",
            state = "CREATED",
            remoteJobId = null,

            attempts = 0,
            nextAttemptAtEpochMs = now,
            lastErrorCode = null,
            lastErrorMessage = null,
            updatedAtEpochMs = now,
            createdAtEpochMs = now,
            libraryId = "library-1"
        )
        dao.upsertJob(job)
        assertFalse(dao.hasRootSwitchBlockingSeparationSafetyFacts())

        dao.upsertJob(job.copy(state = "SUBMISSION_UNKNOWN", nextAttemptAtEpochMs = null))
        assertTrue(dao.hasRootSwitchBlockingSeparationSafetyFacts())

        dao.upsertJob(job.copy(state = "UNRECOGNIZED_CURRENT_STATE", remoteJobId = null))
        assertTrue(dao.hasRootSwitchBlockingSeparationSafetyFacts())

        dao.upsertJob(job.copy(state = "FAILED", remoteJobId = "accepted-remote"))
        assertTrue(dao.hasRootSwitchBlockingSeparationSafetyFacts())

        dao.upsertJob(job.copy(state = "COMPLETE", remoteJobId = "accepted-remote"))
        assertFalse(dao.hasRootSwitchBlockingSeparationSafetyFacts())
    }






    private fun song(canonical: Boolean, playlistItemId: String?, inPlaylist: Boolean) =
        SongEntity(
            id = PACKAGE_ID,
            youtubeVideoId = "rZNpBqlrwkI",
            playlistItemId = playlistItemId,
            canonicalUrl = "https://music.youtube.com/watch?v=rZNpBqlrwkI",
            sourceTitle = "Song",
            sourceArtist = "Artist",
            displayTitle = if (canonical) "Song" else null,
            displayArtist = if (canonical) "Artist" else null,
            durationMs = 10_000,
            artworkPath = null,
            currentlyInSourcePlaylist = inPlaylist,
            highLevelState = if (canonical) "READY" else "PREPARED",
            acquiredAtEpochMs = 1,
            createdAtEpochMs = 1,
            canonicalExternal = canonical,
            packageDocumentUri = if (canonical) "content://library/$PACKAGE_ID" else null,
            manifestState = if (canonical) "enriched" else null
        )

    private fun source(id: String, path: String) = SourceVersionEntity(
        id = id,
        songId = PACKAGE_ID,
        localPath = path,
        sha256 = "a".repeat(64),
        codec = "opus",
        container = "ogg",
        sampleRateHz = 48_000,
        channelCount = 2,

        engineVersion = "test",
        isCurrent = true,
        createdAtEpochMs = 1
    )

    private fun completeSet(
        id: String,
        source: SourceVersionEntity,
        remoteJobId: String
    ) = SeparationSetEntity(
        id = id,
        songId = PACKAGE_ID,
        sourceVersionId = source.id,
        sourceSha256 = source.sha256,
        provider = "MVSEP",
        modelId = "source-plan:${"b".repeat(64)}",
        modelName = "MVSEP render 63",
        remoteJobId = remoteJobId,

        state = "COMPLETE",
        isActive = true,
        createdAtEpochMs = 1
    )

    companion object {
        private const val PACKAGE_ID = "rZNpBqlrwkI"
        private const val CONFLICT_OWNER_ID = "CONFLICT001"
    }
}
