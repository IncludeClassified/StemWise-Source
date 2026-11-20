package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StemwiseShellStageTest {
    @Test fun unselectedRootStaysInStartupUntilApplicationPreparationCompletes() {
        for (complete in listOf(false, true)) {
            assertEquals(if (complete) StemwiseShellStage.ROOT_SELECTION else StemwiseShellStage.STARTUP,
                stemwiseShellStage(false, null, false, false, false, true, null,
                    applicationStartupComplete = complete))
        }
    }

    @Test fun completedStartupPreservesRecoveryAndBlockedOutcomes() {
        mapOf("PLAYLIST_SELECTION_RECOVERY_REQUIRED" to StemwiseShellStage.ROOT_RECOVERY,
            "ROOT_PERMISSION_LOST" to StemwiseShellStage.ROOT_RECONNECT).forEach { (error, expected) ->
            assertEquals(StemwiseShellStage.STARTUP,
                stemwiseShellStage(false, error, false, true, false, false, null,
                    applicationStartupComplete = false))
            assertEquals(expected, stemwiseShellStage(false, error, false, true, false, false, null,
                applicationStartupComplete = true))
        }
        assertEquals(StemwiseShellStage.COLD_OPEN_RECOVERY,
            stemwiseShellStage(true, null, false, true, false, false, "RECOVERY_INTERRUPTED",
                applicationStartupComplete = true))
    }

    @Test fun pendingOwnerRetirementAlwaysReachesTheRestartGate() {
        for (rootReady in listOf(false, true)) for (switching in listOf(false, true)) {
            assertEquals(StemwiseShellStage.COLD_OPEN_RECOVERY, stemwiseShellStage(
                rootReady, "ROOT_CHECKING", false, true, switching, true,
                app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING))
        }
        val presentation = libraryRecoveryPresentation(app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING, "Pending")
        assertTrue(presentation.restartRequired)
        assertFalse(presentation.reconnectsFolder)
        assertEquals("Close StemWise", presentation.actionLabel)
    }

    @Test
    fun `one stage owner covers startup recovery opening and ready shell`() {
        fun stage(
            rootReady: Boolean = true,
            rootError: String? = null,
            candidateError: Boolean = false,
            rememberedRoot: Boolean = true,
            switching: Boolean = false,
            opening: Boolean = false,
            blocked: String? = null
        ) = stemwiseShellStage(
            rootReady = rootReady,
            rootErrorCode = rootError,
            rootCandidateErrorPresent = candidateError,
            hasRememberedRoot = rememberedRoot,
            switchingLibrary = switching,
            openingLibrary = opening,
            coldOpenBlockedCode = blocked
        )

        assertEquals(
            StemwiseShellStage.STARTUP,
            stage(rootReady = false, rootError = "ROOT_CHECKING")
        )
        assertEquals(
            StemwiseShellStage.ROOT_SELECTION,
            stage(rootReady = false, rootError = "ROOT_CHECKING", candidateError = true)
        )
        assertEquals(
            StemwiseShellStage.ROOT_RECOVERY,
            stage(rootReady = false, rootError = "PLAYLIST_SELECTION_RECOVERY_REQUIRED")
        )
        assertEquals(
            StemwiseShellStage.ROOT_RECONNECT,
            stage(rootReady = false, rootError = "ROOT_PERMISSION_LOST")
        )
        assertEquals(StemwiseShellStage.OPENING, stage(switching = true))
        assertEquals(StemwiseShellStage.STARTUP, stage(opening = true))
        assertEquals(
            StemwiseShellStage.COLD_OPEN_RECONNECT,
            stage(blocked = "ROOT_UNAVAILABLE")
        )
        assertEquals(
            StemwiseShellStage.COLD_OPEN_RECOVERY,
            stage(blocked = "RECOVERY_INTERRUPTED")
        )
        assertEquals(StemwiseShellStage.READY, stage())
    }

}
