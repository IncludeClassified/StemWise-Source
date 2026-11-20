package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativePlaybackProcessRetirementTest {
    @Test
    fun fatalThenTaskRemovalWaitsForServiceCleanupAndTerminatesExactlyOnce() {
        var terminations = 0
        val policy = NativePlaybackProcessRetirement { terminations += 1 }

        assertFalse(policy.onNativeRestartRequired())
        assertFalse(policy.onTaskRemoved(cleanupAlreadyComplete = false))
        assertEquals(0, terminations)
        assertFalse(policy.onPlaybackServiceCleanupComplete())
        assertTrue(policy.onDurableTaskCleanupComplete(policy.currentTaskEpoch()))
        assertEquals(1, terminations)

        assertFalse(policy.onPlaybackServiceCleanupComplete())
        assertFalse(policy.onTaskRemoved(restartAlreadyRequired = true, cleanupAlreadyComplete = true))
        assertFalse(policy.onNativeRestartRequired())
        assertEquals(1, terminations)
    }

    @Test
    fun taskRemovalAndCleanupThenLateFatalStillTerminates() {
        var terminations = 0
        val policy = NativePlaybackProcessRetirement { terminations += 1 }

        assertFalse(policy.onTaskRemoved(cleanupAlreadyComplete = true))
        assertFalse(policy.onDurableTaskCleanupComplete(policy.currentTaskEpoch()))
        assertEquals(0, terminations)
        assertTrue(policy.onNativeRestartRequired())
        assertEquals(1, terminations)
    }

    @Test
    fun ordinaryRelaunchClearsOnlyANonfatalRemovalEdge() {
        var terminations = 0
        val policy = NativePlaybackProcessRetirement { terminations += 1 }

        assertFalse(policy.onTaskRemoved(cleanupAlreadyComplete = true))
        policy.onTaskAvailable()
        assertFalse(policy.onNativeRestartRequired())
        assertEquals(0, terminations)

        assertFalse(policy.onTaskRemoved(cleanupAlreadyComplete = true))
        assertTrue(policy.onDurableTaskCleanupComplete(policy.currentTaskEpoch()))
        assertEquals(1, terminations)
    }

    @Test
    fun failedDurableCleanupCanRetryTheSameFatalTaskAndTerminateOnce() {
        var terminations = 0
        val policy = NativePlaybackProcessRetirement { terminations += 1 }
        val claim = TaskEpochCleanupClaim()

        assertFalse(policy.onNativeRestartRequired())
        assertFalse(policy.onTaskRemoved(cleanupAlreadyComplete = true))
        val taskEpoch = policy.currentTaskEpoch()
        assertTrue(claim.claim(taskEpoch))
        assertFalse(claim.claim(taskEpoch))

        claim.releaseAfterFailure(taskEpoch)
        policy.onTaskAvailable()
        assertTrue(claim.claim(taskEpoch))
        assertTrue(policy.onDurableTaskCleanupComplete(taskEpoch))
        assertEquals(1, terminations)

        assertFalse(policy.onDurableTaskCleanupComplete(taskEpoch))
        assertEquals(1, terminations)
    }

    @Test
    fun lateFailureCannotReleaseANewerTaskEpochClaim() {
        val claim = TaskEpochCleanupClaim()

        assertTrue(claim.claim(7L))
        assertTrue(claim.claim(8L))
        claim.releaseAfterFailure(7L)

        assertFalse(claim.claim(8L))
    }
}
