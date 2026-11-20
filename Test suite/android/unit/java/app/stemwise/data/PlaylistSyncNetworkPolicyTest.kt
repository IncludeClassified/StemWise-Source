package app.stemwise.data

import app.stemwise.network.AppNetworkState
import app.stemwise.network.UnmeteredNetworkRequiredException
import app.stemwise.network.WAITING_FOR_UNMETERED_NETWORK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncNetworkPolicyTest {
    @Test
    fun `typed network wait survives a lost connection and clears on validated wifi`() {
        assertEquals(
            PlaylistSyncStatus(),
            projectPlaylistSyncStatusForNetwork(
                PlaylistSyncStatus(waitingForNetwork = true),
                ALLOWED
            )
        )
        val genuinelyBlocked = projectPlaylistSyncStatusForNetwork(
            playlistSyncFailureHandling(UnmeteredNetworkRequiredException(), "Any translated wait").status,
            ALLOWED.copy(activeNetworkMetered = true)
        )
        assertTrue(genuinelyBlocked.waitingForNetwork)
        assertNull(genuinelyBlocked.error)
    }

    @Test
    fun `display text cannot invent a network wait or erase a genuine failure`() {
        for (message in listOf(WAITING_FOR_UNMETERED_NETWORK, "Waiting for an unmetered connection")) {
            val genuineFailure = PlaylistSyncStatus(error = message)
            assertEquals(genuineFailure, projectPlaylistSyncStatusForNetwork(genuineFailure, ALLOWED))
            assertEquals(genuineFailure, projectPlaylistSyncStatusForNetwork(genuineFailure,
                ALLOWED.copy(hasActiveNetwork = false)))
        }
    }

    @Test
    fun `network policy retry has no persistent error while an ordinary failure remains durable`() {
        val networkWait = playlistSyncFailureHandling(
            error = UnmeteredNetworkRequiredException(operationStarted = true),
            displayMessage = WAITING_FOR_UNMETERED_NETWORK
        )
        assertTrue(networkWait.status.waitingForNetwork)
        assertNull(networkWait.status.error)
        assertNull(networkWait.persistentError)

        val ordinary = playlistSyncFailureHandling(
            error = IllegalStateException("playlist response was malformed"),
            displayMessage = "Playlist response was malformed"
        )
        assertFalse(ordinary.status.waitingForNetwork)
        assertEquals("Playlist response was malformed", ordinary.status.error)
        assertEquals("Playlist response was malformed", ordinary.persistentError)
    }

    private companion object {
        val ALLOWED = AppNetworkState(
            hasActiveNetwork = true,
            activeNetworkValidated = true,
            activeNetworkMetered = false,
            activeNetworkWifi = true
        )
    }
}
