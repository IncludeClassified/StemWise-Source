package app.stemwise.playback

import app.stemwise.playback.engine.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class StemActivationTerminalTest {
    @Test
    fun retainedOwnerCannotLeaveActivationWaitingAfterQuarantineClosureOrOutputLoss() = runBlocking {
        val selection = EngineSelection(EngineSelectionToken(41), "song", "revision", "/original", 1000, 0, false)
        val lease = NativeAudioPlaybackCommandLease(selection.token, selection.songId, selection.audioRevision)
        val metadata = PlaybackPublicationMetadata(selection.songId, selection.audioRevision, 1)
        val metronome = NativeAudioPlaybackMetronomeSnapshot(false, false, 120f, 0f, 1, 4, 4, emptyList(), emptyList(), emptyList())
        val controls = NativeAudioPlaybackControlSnapshot(1, false, 0, 0, EngineSourceMode.STEMS, emptyList(), emptyMap(), 1f, 0, null, metronome)
        val committed = PlaybackCommittedPublication(
            selection,
            PlaybackCommittedEngineOwner(NativeRuntimeOwnership(41, 1), NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED, null),
            metadata,
            PlaybackPublicationTransport(playWhenReady=false, requestedPositionMs=0, presentedPositionMs=0, durationMs=1000),
            source=PlaybackPublicationSourceState(desiredRevision=3, desiredMode=EngineSourceMode.STEMS),
            stems=PlaybackPublicationStemState(PlaybackStemPackageRequest("package", "stems", 1), phase=PlaybackStemPackagePhase.PREPARING)
        )
        val retained = NativeAudioPlaybackSessionState(
            publication=PlaybackPublicationReducerState(committed=committed),
            latestRequest=NativeAudioPlaybackRequestedOwner(selection, metadata, committed.owner.ownership, controls)
        )
        for (terminal in listOf(
            retained.copy(lifecycle=NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED),
            retained.copy(lifecycle=NativeAudioPlaybackSessionLifecycle.CLOSED),
            retained.copy(publication=retained.publication.copy(committed=committed.copy(
                issue=PlaybackPublicationIssue("output-unavailable", "Output stopped", false, selection.token)
            )))
        )) {
            val states=MutableStateFlow(retained)
            assertNull(stemActivationSettlement(retained, lease, 3, "package", "stems"))
            val activation=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                awaitStemActivationTerminalSettlement(states.map { stemActivationSettlement(it, lease, 3, "package", "stems") })
            }
            assertFalse(activation.isCompleted)
            states.value=terminal
            try {
                assertTrue(withTimeout(1000) { activation.await() } is StemActivationSettlement.Failed)
            } finally { activation.cancel() }
        }
    }
}
