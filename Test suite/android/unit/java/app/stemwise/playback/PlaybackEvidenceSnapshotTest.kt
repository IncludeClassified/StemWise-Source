package app.stemwise.playback

import app.stemwise.data.RootAuthority
import app.stemwise.playback.engine.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackEvidenceSnapshotTest {
    @Test fun exportedPendingCommittedAndNativeOwnersRemainDistinctWithRealBoundedEventTail() {
        val history = PlaybackEventHistory().apply { setCaptureEnabled(true) }
        repeat(100) { history.record(it.toLong(), "event-${it + 1}") }
        val ownerA = NativeRuntimeOwnership(41, 7)
        val ownerB = NativeRuntimeOwnership(42, 8)
        val selectionA = selection(41)
        val selectionB = selection(42)
        val committed = PlaybackCommittedPublication(selectionA,
            PlaybackCommittedEngineOwner(ownerA, NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED, null),
            metadata(selectionA), PlaybackPublicationTransport(playWhenReady = false, requestedPositionMs = 4_321, presentedPositionMs = 4_321, durationMs = 10_000))
        val session = NativeAudioPlaybackSessionState(
            publication = PlaybackPublicationReducerState(committed = committed),
            latestRequest = NativeAudioPlaybackRequestedOwner(selectionB, metadata(selectionB), ownerB, controls()))
        val ordering = NativeRuntimeEndOrderingDiagnostics(listOf(
            event(NativeRuntimeEndOrderingKind.COMMAND_PUBLISHED, 20, ownerB),
            event(NativeRuntimeEndOrderingKind.PHYSICAL_DRAIN_OBSERVED, 24, ownerA)))
        val stage = NativeControllerPreparationStageDiagnostics(sequence = 1, ownership = ownerB,
            operation = NativeControllerPreparationOperation.STEM_PACKAGE, stage = "failed", startedAtMs = 100,
            elapsedMs = 37, cause = "DECODER_SOURCE_UNREADABLE")
        val json = JSONObject(PlaybackEvidenceSnapshot(123, 500,
            RootAuthority("library", "content://tree/library", 4), 19, session, true,
            NativeRuntimeOwnershipProgress(ownerB, ownerB, ownerA, 5), listOf(stage), ordering,
            history.snapshot(), StemPreparationDiagnostics(1, 2, 3, 37)).toJson())
        assertEquals(4, json.getJSONObject("root").getLong("epoch"))
        assertEquals(19, json.getLong("selectionOperationSerial"))
        assertEquals(42, json.getJSONObject("requested").getLong("selectionToken"))
        assertEquals(41, json.getJSONObject("committed").getLong("selectionToken"))
        assertEquals(41, json.getJSONObject("nativeOwnership").getJSONObject("committed").getLong("selectionToken"))
        assertEquals("controller scheduler monotonic milliseconds; independent of capture elapsed time",
            json.getString("preparationClock"))
        val stages = json.getJSONArray("preparationStages")
        assertEquals(100, stages.getJSONObject(0).getLong("startedAtMonotonicMs"))
        assertFalse(stages.getJSONObject(0).has("startedAtElapsedMs"))
        assertEquals("DECODER_SOURCE_UNREADABLE", stages.getJSONObject(0).getString("cause"))
        assertEquals(37, stages.getJSONObject(0).getLong("elapsedMs"))
        assertEquals(42, stages.getJSONObject(0).getJSONObject("ownership").getLong("selectionToken"))
        val events = json.getJSONArray("eventTail")
        assertEquals(64, events.length())
        assertTrue(events.getString(0).startsWith("37@"))
        assertTrue(events.getString(63).startsWith("100@"))
        val end = json.getJSONObject("nativeEndOrdering")
        assertFalse(end.getBoolean("completeHistory"))
        assertEquals(4, end.getJSONArray("missingKinds").length())
        assertEquals(20, end.getJSONArray("events").getJSONObject(0).getLong("sequence"))
        assertEquals(24, end.getJSONArray("events").getJSONObject(1).getLong("sequence"))
    }

    @Test fun unavailableEvidenceIsExplicitAndDiagnosticPayloadsRemainBounded() {
        val stages = (1L..40L).map { NativeControllerPreparationStageDiagnostics(it, null,
            NativeControllerPreparationOperation.ORIGINAL_OPEN, "failed", 0, 1, "x".repeat(3_000)) }
        val json = JSONObject(PlaybackEvidenceSnapshot(1, 2, null, 1,
            NativeAudioPlaybackSessionState(terminalFailure = "failure"), false, null, stages, null,
            emptyList(), StemPreparationDiagnostics(0, 0, 0, 0)).toJson())
        assertFalse(json.getBoolean("engineEvidenceAvailable"))
        assertTrue(json.isNull("nativeOwnership"))
        assertTrue(json.isNull("canonicalPcmIo"))
        assertTrue(json.isNull("nativeEndOrdering"))
        assertTrue(json.isNull("requested"))
        assertEquals("failure", json.getString("terminalCause"))
        assertEquals(32, json.getJSONArray("preparationStages").length())
        assertEquals(9, json.getJSONArray("preparationStages").getJSONObject(0).getLong("sequence"))
        assertEquals(1_024, json.getJSONArray("preparationStages").getJSONObject(31).getString("cause").length)
        assertFalse(json.getBoolean("preparationStageHistoryComplete"))
    }

    @Test fun canonicalCapacityAndLatestRefusalRemainDistinctInTheUserRequestedExport() {
        val io = NativeRuntimeCanonicalPcmIoDiagnostics(NativeRuntimeOwnership(41, 7),
            101, 102, 103, 5, 4_096, 1_024, 512, 8_192, 16_384, 8_192,
            3, 48_000, NativeCacheStorageStatus.INSUFFICIENT_SPACE)
        val json = JSONObject(PlaybackEvidenceSnapshot(1, 2, null, 1, NativeAudioPlaybackSessionState(),
            true, null, emptyList(), null, emptyList(), StemPreparationDiagnostics(0, 0, 0, 0), io).toJson())
            .getJSONObject("canonicalPcmIo")
        assertEquals(41, json.getJSONObject("ownership").getLong("selectionToken"))
        assertEquals(7, json.getJSONObject("ownership").getLong("generation"))
        assertEquals(101, json.getLong("songIdentityHash"))
        assertEquals(102, json.getLong("originalContentHash"))
        assertEquals(103, json.getLong("stemPackageHash"))
        assertEquals(3, json.getLong("admittedLaneCount"))
        assertEquals(48_000, json.getLong("admittedFrameUpperBound"))
        assertEquals(16_384, json.getLong("reservedBytes"))
        assertEquals("INSUFFICIENT_SPACE", json.getString("lastAdmissionStatus"))
        assertEquals(4_096, json.getLong("readBytes"))
        assertEquals(8_192, json.getLong("fileBytes"))
    }

    private fun event(kind: NativeRuntimeEndOrderingKind, sequence: Long, owner: NativeRuntimeOwnership) =
        NativeRuntimeEndOrderingEvent(kind, sequence, 17, 9, owner, false, false, true, false, 0, 0, true, true)
    private fun selection(token: Long) = EngineSelection(EngineSelectionToken(token), "song-$token", "audio-$token",
        "content://original-$token", 10_000, 4_321, false)
    private fun metadata(selection: EngineSelection) = PlaybackPublicationMetadata(selection.songId, selection.audioRevision, 1)
    private fun controls() = NativeAudioPlaybackControlSnapshot(1, false, 4_321, 0, EngineSourceMode.ORIGINAL,
        emptyList(), emptyMap(), 1f, 0, null, NativeAudioPlaybackMetronomeSnapshot(false, false, 120f, 0f, 1, 4, 4,
            emptyList(), emptyList(), emptyList()))
}
