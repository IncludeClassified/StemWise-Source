package app.stemwise.work

import androidx.work.NetworkType
import androidx.work.WorkInfo
import app.stemwise.data.PlaylistSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncWorkerPolicyTest {
    @Test fun extractorTimeoutRetriesButUnrelatedLocalIoDoesNot() {
        assertTrue(playlistSyncFailureNeedsRetry(IllegalStateException("TOOL_PROCESS_TIMEOUT")))
        assertTrue(playlistSyncFailureNeedsRetry(java.net.UnknownHostException("offline")))
        assertFalse(playlistSyncFailureNeedsRetry(java.io.IOException("local disk full")))
        assertFalse(playlistSyncFailureNeedsRetry(java.io.IOException("Could not resolve local filename")))
        assertTrue(playlistSyncFailureNeedsRetry(com.yausername.youtubedl_android.YoutubeDLException("HTTP Error 503")))
        assertFalse(playlistSyncFailureNeedsRetry(com.yausername.youtubedl_android.YoutubeDLException("Video unavailable")))
        assertFalse(playlistSyncFailureNeedsRetry(IllegalArgumentException("invalid playlist")))
    }

    @Test
    fun manualSyncIsOneTimeUniqueDurableAndRunsOnAnyConnectedNetwork() {
        val request = PlaylistSyncWorker.manualRequest(LIBRARY_ID)
        val constraints = request.workSpec.constraints

        assertEquals(PlaylistSyncWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(LIBRARY_ID, request.workSpec.input.getString("library_id"))
        assertTrue(request.workSpec.input.getBoolean(PlaylistSyncWorker.KEY_MANUAL, false))
        assertTrue(request.tags.contains(PlaylistSyncWorker.MANUAL_WORK_TAG))
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresStorageNotLow())
        assertEquals(
            "stemwise-manual-playlist-sync:$LIBRARY_ID",
            PlaylistSyncWorker.manualUniqueWorkName(LIBRARY_ID)
        )
        assertNotEquals(
            PlaylistSyncWorker.manualUniqueWorkName(LIBRARY_ID),
            PlaylistSyncWorker.manualUniqueWorkName("another-library")
        )
    }

    @Test
    fun manualUniqueWorkNamePreservesTheExactLibraryIdentityWithoutCollisions() {
        val identities = listOf(
            "a",
            "A",
            "library-1",
            "library_1",
            "library:1",
            "مكتبة",
            "library with spaces"
        )
        val names = identities.map { identity ->
            PlaylistSyncWorker.manualUniqueWorkName(identity)
        }

        assertEquals(identities.size, names.toSet().size)
        identities.zip(names).forEach { (identity, name) ->
            assertEquals("stemwise-manual-playlist-sync:$identity", name)
            assertTrue(name.endsWith(identity))
        }
    }

    @Test
    fun networkPolicyWaitRetriesInsteadOfBeingMistakenForCompletedSync() {
        assertTrue(
            playlistSyncFailureNeedsRetry(
                app.stemwise.network.UnmeteredNetworkRequiredException()
            )
        )
        assertFalse(
            playlistSyncFailureNeedsRetry(
                IllegalArgumentException("The configured playlist is invalid")
            )
        )
    }

    @Test
    fun durableManualWorkStateIgnoresHistoryAndReportsTheLiveUniqueJob() {
        assertEquals(
            ManualPlaylistSyncWorkState.QUEUED,
            manualPlaylistSyncWorkState(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.ENQUEUED)
            )
        )
        assertEquals(
            ManualPlaylistSyncWorkState.RUNNING,
            manualPlaylistSyncWorkState(
                listOf(WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
            )
        )
        assertEquals(
            ManualPlaylistSyncWorkState.IDLE,
            manualPlaylistSyncWorkState(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)
            )
        )
    }

    @Test
    fun queuedManualWorkProjectsTruthfulWaitingAndRunningStatus() {
        val waiting = projectManualPlaylistSyncStatus(
            status = PlaylistSyncStatus(),
            workState = ManualPlaylistSyncWorkState.QUEUED,
            networkAllowed = false
        )
        assertTrue(waiting.queued)
        assertTrue(waiting.waitingForNetwork)
        assertFalse(waiting.running)

        val running = projectManualPlaylistSyncStatus(
            status = waiting,
            workState = ManualPlaylistSyncWorkState.RUNNING,
            networkAllowed = true
        )
        assertTrue(running.running)
        assertFalse(running.queued)
        assertFalse(running.waitingForNetwork)
    }

    private companion object {
        const val LIBRARY_ID = "manual-sync-library"
    }
}
