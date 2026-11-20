package app.stemwise.playback.engine

import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeSourceActivationStateTest {
    private val owner = NativeRuntimeOwnership(11L, 21L)
    private fun publication(ownership: NativeRuntimeOwnership = owner, intent: Long = 2L,
        mode: EngineSourceMode = EngineSourceMode.STEMS, control: Long = 3L,
        native: NativeRuntimeSourceMode = NativeRuntimeSourceMode.STEMS) = SourceControlPublication(
        control, ownership, EngineSelectionToken(ownership.selectionToken), intent, mode, native, null, null, null)
    private fun evidence(publication: SourceControlPublication) = NativeRuntimeAudibleControlEvidence(
        1L, publication.ownership, publication.nativeControlRevision, publication.nativeMode,
        1L, 2L, 1L, 256L)
    private fun intent(publication: SourceControlPublication) =
        SourceActivationEndpoint(publication.desiredRevision, publication.requestedMode)

    @Test fun rejectedPublicationAndInvalidOrderingPreserveEveryAuthority() {
        val state = NativeSourceActivationState()
        val accepted = publication()
        assertFalse(state.prepare(owner, playing = false))
        assertFalse(state.observeAudible(owner, evidence(accepted), accepted, intent(accepted)))
        assertFalse(state.publish(NativeRuntimePublishStatus.INVALID_SNAPSHOT, accepted, 2L))
        assertNull(state.published)
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, accepted, 2L))
        assertTrue(state.prepare(owner, playing = false))
        assertTrue(state.observeAudible(owner, evidence(accepted), accepted, intent(accepted)))
        val snapshot = Triple(state.published, state.prepared, state.applied)
        for (rejected in listOf(publication(owner.copy(generation = 20L)),
            publication(owner.copy(selectionToken = 12L)), publication(intent = 1L),
            publication(control = 2L), publication(mode = EngineSourceMode.ORIGINAL))) {
            assertFalse(state.publish(NativeRuntimePublishStatus.ACCEPTED, rejected, 2L))
            assertEquals(snapshot, Triple(state.published, state.prepared, state.applied))
        }
        assertFalse(state.publish(NativeRuntimePublishStatus.ACCEPTED, accepted, 1L))
        assertEquals(snapshot, Triple(state.published, state.prepared, state.applied))
    }

    @Test fun pausedPreparationAndAudibleApplicationMayArriveInEitherOrder() {
        for (prepareFirst in listOf(false, true)) {
            val state = NativeSourceActivationState()
            val accepted = publication()
            assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, accepted, 2L))
            assertFalse(state.prepare(owner, playing = true))
            if (prepareFirst) assertTrue(state.prepare(owner, playing = false))
            assertTrue(state.observeAudible(owner, evidence(accepted), accepted, intent(accepted)))
            if (!prepareFirst) assertTrue(state.prepare(owner, playing = false))
            assertEquals(intent(accepted), state.applied)
            assertEquals(intent(accepted), state.prepared)
            assertFalse(state.prepare(owner, playing = false))
            assertFalse(state.observeAudible(owner, evidence(accepted), accepted, intent(accepted)))
        }
    }

    @Test fun fallbackAndWrongOwnerOrControlProofCannotApplyRequestedStems() {
        val state = NativeSourceActivationState()
        val fallback = publication(native = NativeRuntimeSourceMode.ORIGINAL)
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, fallback, 2L))
        assertFalse(state.prepare(owner, playing = false))
        assertFalse(state.observeAudible(owner, evidence(fallback), fallback, intent(fallback)))
        val accepted = publication(control = 4L)
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, accepted, 3L))
        assertFalse(state.prepare(owner.copy(generation = 20L), playing = false))
        val goodEvidence = evidence(accepted)
        for ((publicOwner, proof, currentIntent) in listOf(
            Triple(null, goodEvidence, intent(accepted)),
            Triple(owner.copy(generation = 20L), goodEvidence, intent(accepted)),
            Triple(owner, goodEvidence.copy(ownership = owner.copy(selectionToken = 12L)), intent(accepted)),
            Triple(owner, goodEvidence.copy(controlRevision = 3L), intent(accepted)),
            Triple(owner, goodEvidence.copy(actualSourceMode = NativeRuntimeSourceMode.ORIGINAL), intent(accepted)),
            Triple(owner, goodEvidence, SourceActivationEndpoint(3L, EngineSourceMode.STEMS)))) {
            assertFalse(state.observeAudible(publicOwner, proof, accepted, currentIntent))
            assertNull(state.applied)
        }
        assertTrue(state.observeAudible(owner, goodEvidence, accepted, intent(accepted)))
    }

    @Test fun retirementAndNewGenerationRequireFreshAcceptedPublicationAndExactProof() {
        val state = NativeSourceActivationState()
        val first = publication()
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, first, 2L))
        assertTrue(state.prepare(owner, playing = false))
        state.retirePhysicalEvidence()
        assertNull(state.prepared)
        assertFalse(state.prepare(owner, playing = false))
        assertFalse(state.observeAudible(owner, evidence(first), first, intent(first)))
        val next = publication(owner.copy(generation = 22L), control = 1L)
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, next, 2L))
        assertFalse(state.observeAudible(owner, evidence(first), first, intent(first)))
        assertTrue(state.observeAudible(next.ownership, evidence(next), next, intent(next)))
    }

    @Test fun newerGainPublicationRetainsCompatibleSourceProofButReversalRejectsIt() {
        val state = NativeSourceActivationState()
        val first = publication()
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, first, 2L))
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, publication(control = 4L), 3L))
        assertTrue(state.observeAudible(owner, evidence(first), first, intent(first)))
        val reversal = publication(intent = 4L, mode = EngineSourceMode.ORIGINAL,
            native = NativeRuntimeSourceMode.ORIGINAL, control = 5L)
        assertTrue(state.publish(NativeRuntimePublishStatus.ACCEPTED, reversal, 4L))
        assertFalse(state.observeAudible(owner, evidence(first), first, intent(first)))
        assertEquals(intent(first), state.applied)
        assertTrue(state.observeAudible(owner, evidence(reversal), reversal, intent(reversal)))
        assertEquals(intent(reversal), state.applied)
    }
}
