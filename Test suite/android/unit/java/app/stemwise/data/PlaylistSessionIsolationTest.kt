package app.stemwise.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSessionIsolationTest {
    @Test
    fun `a queued token becomes stale before the next Library activates`() {
        val sessions = PlaylistSessionEpoch()
        val old = sessions.capture(LIBRARY_A, "content://provider/old-root")

        sessions.invalidate()

        assertFalse(sessions.isCurrent(old, LIBRARY_A, "content://provider/old-root"))
        assertEquals(
            "LIBRARY_SWITCH_IN_PROGRESS",
            runCatching {
                sessions.capture(LIBRARY_A, "content://provider/old-root")
            }.exceptionOrNull()?.message
        )

        sessions.activate()
        val current = sessions.capture(LIBRARY_B, "content://provider/new-root")
        assertTrue(sessions.isCurrent(current, LIBRARY_B, "content://provider/new-root"))
        assertFalse(sessions.isCurrent(old, LIBRARY_B, "content://provider/new-root"))
        assertNotEquals(old.epoch, current.epoch)
    }

    @Test
    fun `exact URI reconnect still invalidates candidates from its old process epoch`() {
        val sessions = PlaylistSessionEpoch()
        val old = sessions.capture(LIBRARY_A, "content://provider/old-root")

        sessions.invalidate()
        sessions.activate()
        val reconnected = sessions.capture(LIBRARY_A, "content://provider/old-root")

        assertFalse(
            sessions.isCurrent(old, LIBRARY_A, "content://provider/old-root")
        )
        assertTrue(
            sessions.isCurrent(
                reconnected,
                LIBRARY_A,
                "content://provider/old-root"
            )
        )
    }

    private companion object {
        const val LIBRARY_A = "00000000-0000-4000-8000-000000000001"
        const val LIBRARY_B = "00000000-0000-4000-8000-000000000002"
    }
}
