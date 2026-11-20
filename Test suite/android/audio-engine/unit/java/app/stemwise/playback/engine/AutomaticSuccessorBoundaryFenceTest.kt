package app.stemwise.playback.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class AutomaticSuccessorBoundaryFenceTest {
    private val outgoing = NativeRuntimeOwnership(selectionToken = 814L, generation = 309L)
    private val successor = NativeRuntimeOwnership(selectionToken = 815L, generation = 310L)

    @Test
    fun presentedSuccessorRequiresCatchUpWhileRuntimeStillClaimsCommittedDrain() {
        assertTrue(needsCatchUp(NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED))
    }

    @Test
    fun callbackCrossingBetweenRuntimeAndOutputReadsAlsoFencesStagingSnapshot() {
        assertTrue(needsCatchUp(NativeRuntimeAutomaticSuccessorPhase.STAGING))
    }

    @Test
    fun outgoingOutputDoesNotFenceBeforeThePhysicalBoundary() {
        assertFalse(
            automaticSuccessorBoundaryNeedsRuntimeCatchUp(
                runtimeStatus = NativeRuntimeStatus.RUNNING,
                automatic = automatic(NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED),
                outputAppliedGeneration = outgoing.generation,
                outputPresentedGeneration = outgoing.generation,
                outputPresentedSelectionToken = outgoing.selectionToken,
                outputPresentedFingerprintSequence = 40L
            )
        )
    }

    @Test
    fun alreadyPublishedSuccessorNeverWaitsOnItself() {
        assertFalse(needsCatchUp(NativeRuntimeAutomaticSuccessorPhase.PRESENTED_AWAITING_SELECTION))
    }

    @Test
    fun stoppedRuntimeNeverEntersTheBoundaryFence() {
        assertFalse(
            automaticSuccessorBoundaryNeedsRuntimeCatchUp(
                runtimeStatus = NativeRuntimeStatus.STOPPED,
                automatic = automatic(NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED),
                outputAppliedGeneration = successor.generation,
                outputPresentedGeneration = successor.generation,
                outputPresentedSelectionToken = successor.selectionToken,
                outputPresentedFingerprintSequence = 41L
            )
        )
    }

    private fun needsCatchUp(phase: NativeRuntimeAutomaticSuccessorPhase): Boolean =
        automaticSuccessorBoundaryNeedsRuntimeCatchUp(
            runtimeStatus = NativeRuntimeStatus.RUNNING,
            automatic = automatic(phase),
            outputAppliedGeneration = successor.generation,
            outputPresentedGeneration = successor.generation,
            outputPresentedSelectionToken = successor.selectionToken,
            outputPresentedFingerprintSequence = 41L
        )

    private fun automatic(
        phase: NativeRuntimeAutomaticSuccessorPhase
    ) = NativeRuntimeAutomaticSuccessorDiagnostics(
        phase = phase,
        cancellationStatus = NativeRuntimeDrainCancelStatus.NOT_REQUESTED,
        revision = 91L,
        transitionRevision = 91L,
        outgoingOwnership = outgoing,
        successorOwnership = successor,
        successorQueuedFrames = 4_096L,
        firstFingerprintSequence = 41L,
        cancellationDesired = false,
        successorEndObserved = false
    )
}
