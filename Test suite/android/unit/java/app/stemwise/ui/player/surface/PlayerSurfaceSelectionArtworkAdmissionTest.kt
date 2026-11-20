package app.stemwise.ui.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Retained admission/restore through the actual mounted bridge is covered by
// NativePlayerRenderingInstrumentationTest.unmountedSelectionAndSemanticFeedbackUseTheMountedOwner;
// decode preemption by PlayerSurfaceArtworkLoaderQosInstrumentationTest
// .newestCurrentPreemptsOldCurrentWhileStillOwnedCoverRestartsOffscreen.
class PlayerSurfaceSelectionArtworkAdmissionTest {
    @Test
    fun swipeAndTransportDownConvergeOnTheSameInteriorCandidate() {
        listOf(1f to 3, -1f to 5).forEach { (offset, expected) ->
            assertEquals(
                expected,
                playerSurfaceSelectionPreviewIndex(
                    currentIndex = 4,
                    itemCount = 9,
                    offsetFraction = offset,
                    repeatMode = PlayerSurfaceRepeatMode.ALL,
                    allowRepeatAllWrap = false
                )
            )
            assertEquals(
                expected,
                playerSurfaceSelectionPreviewIndex(
                    currentIndex = 4,
                    itemCount = 9,
                    offsetFraction = offset,
                    repeatMode = PlayerSurfaceRepeatMode.ALL,
                    allowRepeatAllWrap = true
                )
            )
        }
    }

    @Test
    fun previousDownKeepsCurrentArtworkWhenTheCommandWillRestartCurrent() {
        assertNull(
            playerSurfaceTransportPreviewOffset(
                PlayerSurfaceTransportCommand.PREVIOUS,
                3_000L
            )
        )
        assertNull(
            playerSurfaceTransportPreviewOffset(
                PlayerSurfaceTransportCommand.PREVIOUS,
                3_000L + 1L
            )
        )
        assertEquals(
            -1f,
            playerSurfaceTransportPreviewOffset(
                PlayerSurfaceTransportCommand.NEXT,
                3_000L + 1L
            )
        )
    }

    @Test
    fun repeatAllButtonsWarmBothWrapDirectionsWithoutMakingSwipeWrap() {
        assertEquals(
            7,
            playerSurfaceSelectionPreviewIndex(
                currentIndex = 0,
                itemCount = 8,
                offsetFraction = 1f,
                repeatMode = PlayerSurfaceRepeatMode.ALL,
                allowRepeatAllWrap = true
            )
        )
        assertEquals(
            0,
            playerSurfaceSelectionPreviewIndex(
                currentIndex = 7,
                itemCount = 8,
                offsetFraction = -1f,
                repeatMode = PlayerSurfaceRepeatMode.ALL,
                allowRepeatAllWrap = true
            )
        )
        assertNull(
            playerSurfaceSelectionPreviewIndex(
                currentIndex = 0,
                itemCount = 8,
                offsetFraction = 1f,
                repeatMode = PlayerSurfaceRepeatMode.ALL,
                allowRepeatAllWrap = false
            )
        )
        assertNull(
            playerSurfaceSelectionPreviewIndex(
                currentIndex = 7,
                itemCount = 8,
                offsetFraction = -1f,
                repeatMode = PlayerSurfaceRepeatMode.ALL,
                allowRepeatAllWrap = false
            )
        )
    }

    @Test
    fun automaticRepeatAllWrapJoinsTheExistingBoundedPredictiveSet() {
        assertEquals(
            listOf(7, 2),
            playerSurfacePredictiveArtworkIndices(
                currentIndex = 0,
                itemCount = 8,
                repeatMode = PlayerSurfaceRepeatMode.ALL
            )
        )
        assertEquals(
            listOf(0, 5),
            playerSurfacePredictiveArtworkIndices(
                currentIndex = 7,
                itemCount = 8,
                repeatMode = PlayerSurfaceRepeatMode.ALL
            )
        )
        assertEquals(
            listOf(2),
            playerSurfacePredictiveArtworkIndices(
                currentIndex = 0,
                itemCount = 8,
                repeatMode = PlayerSurfaceRepeatMode.ONE
            )
        )
    }

    @Test
    fun staleEpochIsRejectedAndRepeatedExactCandidateDoesNotReadmitDecode() {
        val gate = PlayerSurfaceArtworkPriorityGate<String>()

        assertEquals(
            PlayerSurfaceArtworkPriorityAdmission.APPLIED,
            gate.admit(candidateEpoch = 12L, candidateKey = "exact-b-1008")
        )
        repeat(400) {
            assertEquals(
                PlayerSurfaceArtworkPriorityAdmission.ALREADY_CURRENT,
                gate.admit(candidateEpoch = 12L, candidateKey = "exact-b-1008")
            )
        }
        assertEquals(
            PlayerSurfaceArtworkPriorityAdmission.REJECTED_STALE_EPOCH,
            gate.admit(candidateEpoch = 11L, candidateKey = "stale-a-1008")
        )
        assertEquals(
            PlayerSurfaceArtworkPriorityAdmission.APPLIED,
            gate.admit(candidateEpoch = 12L, candidateKey = "exact-c-1008")
        )
    }

}
