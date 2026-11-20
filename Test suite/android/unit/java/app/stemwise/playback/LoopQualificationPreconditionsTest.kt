package app.stemwise.playback

import app.stemwise.playback.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class LoopQualificationPreconditionsTest {
    @Test fun acceptedAndHeldPcmAreExcludedWhileAnAlreadyQueuedFutureBoundaryIsAdmitted() {
        val window = queuedLoopBoundaryWindow(true, 2_048L, 3_072L, 2_048L, -1L, 512L, 0L)
        assertEquals(3_040L, window.minimumPrefix)
        assertEquals(4_608L, window.queuedPrefix)
        // The old ring-only guard admitted 2200 although accepted+held extends through 2560.
        assertFalse(window.contains(2_200L))
        // A physical distance beyond the unread-ring count can still be queued behind accepted PCM.
        assertTrue(window.contains(4_500L))
        assertFalse(window.contains(3_040L))
        assertTrue(window.contains(3_041L))
        assertTrue(window.contains(4_608L))
        assertFalse(window.contains(4_609L))
    }

    @Test fun sequentialReadAgeKeepsTheExistingSafetyMarginsAndRejectsAnIncoherentAcceptedClock() {
        val aged = queuedLoopBoundaryWindow(true, 2_048L, 3_072L, 2_048L, -1L, 512L, 139L)
        assertEquals(3_179L, aged.minimumPrefix)
        assertEquals(4_469L, aged.queuedPrefix)
        assertFalse(aged.contains(3_179L))
        assertTrue(aged.contains(3_180L))
        assertTrue(aged.contains(4_469L))
        assertFalse(aged.contains(4_470L))
        assertFalse(queuedLoopBoundaryWindow(true, -1L, 8_192L, 2_048L, -1L, 512L, 0L).contains(4_000L))
    }

    @Test fun callbackBackendRetainsItsMeasuredBufferAndUnreadQueueBounds() {
        val window = queuedLoopBoundaryWindow(false, -1L, 1_024L, 256L, 128L, 512L, 139L)
        assertEquals(256L, window.minimumPrefix)
        assertEquals(1_024L, window.queuedPrefix)
        assertFalse(window.contains(256L))
        assertTrue(window.contains(257L))
        assertTrue(window.contains(1_024L))
        assertFalse(window.contains(1_025L))
    }

    @Test fun aNewCommittedGenerationCannotAcknowledgeAHeldSeekCompletion() {
        val receipt = NativeAudioPlaybackSeekReceipt(EngineSelectionToken(41L), 7L, 1_000L)
        val held = session(completedSeekRevision = 6L)
        assertEquals(19L, held.publication.committed?.owner?.ownership?.generation)
        assertEquals(1_000L, held.latestRequest?.controls?.requestedPositionMs)
        assertFalse(seekReceiptIsCompleted(receipt, held))
        val committed = requireNotNull(held.publication.committed)
        val completed = held.copy(publication = held.publication.copy(committed = committed.copy(
            transport = committed.transport.copy(completedSeekRevision = receipt.seekRevision))))
        assertTrue(seekReceiptIsCompleted(receipt, completed))
        assertFalse(seekReceiptIsCompleted(receipt.copy(token = EngineSelectionToken(42L)), completed))
        assertFalse(seekReceiptIsCompleted(receipt.copy(seekRevision = 8L), completed))
        val newerRequest = completed.copy(latestRequest = requireNotNull(completed.latestRequest).let {
            it.copy(controls = it.controls.copy(seekRevision = 8L))
        })
        assertFalse(seekReceiptIsCompleted(receipt, newerRequest))
    }

    private fun session(completedSeekRevision: Long): NativeAudioPlaybackSessionState {
        val selection = EngineSelection(EngineSelectionToken(41L), "song", "audio", "content://original", 10_000L, 0L, false)
        val metadata = PlaybackPublicationMetadata(selection.songId, selection.audioRevision, 1L)
        val owner = NativeRuntimeOwnership(41L, 19L)
        val metronome = NativeAudioPlaybackMetronomeSnapshot(false, false, 120f, 0f, 1, 4, 4,
            emptyList(), emptyList(), emptyList())
        val controls = NativeAudioPlaybackControlSnapshot(8L, false, 1_000L, 7L, EngineSourceMode.ORIGINAL,
            emptyList(), emptyMap(), 1f, 0, null, metronome)
        val committed = PlaybackCommittedPublication(selection,
            PlaybackCommittedEngineOwner(owner, NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED, null),
            metadata, PlaybackPublicationTransport(playWhenReady = false, requestedPositionMs = 1_000L,
                presentedPositionMs = 1_000L, durationMs = 10_000L, completedSeekRevision = completedSeekRevision))
        return NativeAudioPlaybackSessionState(publication = PlaybackPublicationReducerState(committed = committed),
            latestRequest = NativeAudioPlaybackRequestedOwner(selection, metadata, owner, controls))
    }
}
