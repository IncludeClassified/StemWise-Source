package app.stemwise.playback.session

import app.stemwise.playback.PlaybackInstrumentationRecorder
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.StemMixState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MediaSessionObservationGateTest {
    @Test
    fun ordinaryPlaybackUsesTheSameCadenceWithoutRecordingAccounting() {
        val initial = playingState(positionMs = 0L)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L)
        assertEquals(MediaSessionObservationWork.POSITION_FAST_PATH,
            gate.observe(initial.copy(positionMs = 16L), 16L))
        assertEquals(MediaSessionObservationWork.POSITION_CADENCE,
            gate.observe(initial.copy(positionMs = 256L), 256L))
        assertEquals(0L, gate.diagnostics().observationCount)
        assertEquals(0L, gate.diagnostics().requestedFullSnapshotCount)
    }

    @Test
    fun ordinaryPositionTicksRequestOneFullSnapshotPerCadenceInsteadOfPerTick() {
        val initial = playingState(positionMs = 0L)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })

        for (tick in 1L..15L) {
            assertEquals(
                MediaSessionObservationWork.POSITION_FAST_PATH,
                gate.observe(
                    initial.copy(positionMs = tick * 16L),
                    elapsedRealtimeMs = tick * 16L
                )
            )
        }
        assertEquals(
            MediaSessionObservationWork.POSITION_CADENCE,
            gate.observe(initial.copy(positionMs = 256L), elapsedRealtimeMs = 256L)
        )

        val diagnostics = gate.diagnostics()
        assertEquals(16L, diagnostics.observationCount)
        assertEquals(15L, diagnostics.fastPathCount)
        assertEquals(1L, diagnostics.requestedFullSnapshotCount)
        assertEquals(1L, diagnostics.cadenceSnapshotCount)
    }

    @Test
    fun mixerAndAppOnlyChangesStayOnPositionFastPath() {
        val initial = playingState(positionMs = 1_000L)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })

        for (tick in 1L..12L) {
            val state = initial.copy(
                positionMs = 1_000L + tick * 16L,
                stemMix = mapOf(
                    "vocals" to StemMixState(volume = tick / 12f, solo = tick % 2L == 0L)
                ),
                sourceMode = if (tick % 2L == 0L) {
                    app.stemwise.playback.PlaybackSourceMode.STEMS
                } else {
                    app.stemwise.playback.PlaybackSourceMode.ORIGINAL
                }
            )
            assertEquals(
                MediaSessionObservationWork.POSITION_FAST_PATH,
                gate.observe(state, elapsedRealtimeMs = tick * 16L)
            )
        }

        val diagnostics = gate.diagnostics()
        assertEquals(12L, diagnostics.fastPathCount)
        assertEquals(0L, diagnostics.requestedFullSnapshotCount)
        assertFalse(mediaSessionRelevantStateChanged(initial, initial.copy(stemMix = mapOf(
            "vocals" to StemMixState(volume = .3f)
        ))))
    }

    @Test
    fun sessionStructureStillRequestsAnImmediateFullSnapshot() {
        val initial = playingState(positionMs = 2_000L)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 10L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })

        assertEquals(
            MediaSessionObservationWork.STRUCTURAL,
            gate.observe(
                initial.copy(
                    songId = "song-b",
                    loading = true,
                    playing = false,
                    canGoPrevious = true
                ),
                elapsedRealtimeMs = 20L
            )
        )
        assertEquals(1L, gate.diagnostics().structuralSnapshotCount)
        assertTrue(mediaSessionRelevantStateChanged(initial, initial.copy(repeatMode = "One")))
        assertTrue(mediaSessionRelevantStateChanged(initial, initial.copy(tempo = 1.25f)))
    }

    @Test
    fun forwardSeekAndBackwardLoopWrapBypassTheCadence() {
        val initial = playingState(positionMs = 10_000L)
        val seekGate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })
        assertEquals(
            MediaSessionObservationWork.POSITION_DISCONTINUITY,
            seekGate.observe(initial.copy(positionMs = 15_000L), elapsedRealtimeMs = 16L)
        )

        val loopGate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })
        assertEquals(
            MediaSessionObservationWork.POSITION_DISCONTINUITY,
            loopGate.observe(initial.copy(positionMs = 2_000L), elapsedRealtimeMs = 16L)
        )
        assertEquals(1L, seekGate.diagnostics().discontinuitySnapshotCount)
        assertEquals(1L, loopGate.diagnostics().discontinuitySnapshotCount)
    }

    @Test
    fun pausedSeekIsImmediateEvenWhenItsDistanceIsSmall() {
        val initial = playingState(positionMs = 1_000L).copy(
            playing = false,
            playWhenReady = false
        )
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })

        assertEquals(
            MediaSessionObservationWork.POSITION_DISCONTINUITY,
            gate.observe(initial.copy(positionMs = 1_040L), elapsedRealtimeMs = 16L)
        )
    }

    @Test
    fun externalMetadataRefreshRestartsThePositionCadence() {
        val initial = playingState(positionMs = 0L)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })
        val beforeRefresh = initial.copy(positionMs = 240L)

        assertEquals(
            MediaSessionObservationWork.POSITION_FAST_PATH,
            gate.observe(beforeRefresh, elapsedRealtimeMs = 240L)
        )
        gate.recordExternalFullSnapshot(beforeRefresh, elapsedRealtimeMs = 240L)
        assertEquals(
            MediaSessionObservationWork.POSITION_FAST_PATH,
            gate.observe(initial.copy(positionMs = 256L), elapsedRealtimeMs = 256L)
        )
        assertEquals(1L, gate.diagnostics().externalFullSnapshotCount)
    }

    @Test
    fun ordinaryMaximumTempoProgressIsNotMisclassifiedAsASeek() {
        val initial = playingState(positionMs = 1_000L).copy(tempo = 2f)
        val gate = MediaSessionObservationGate(initial, initialElapsedRealtimeMs = 0L,
            instrumentation = PlaybackInstrumentationRecorder().apply { enable() })

        assertEquals(
            MediaSessionObservationWork.POSITION_FAST_PATH,
            gate.observe(initial.copy(positionMs = 1_032L), elapsedRealtimeMs = 16L)
        )
    }

    private fun playingState(positionMs: Long): AudioPlaybackState = AudioPlaybackState(
        songId = "song-a",
        playing = true,
        playWhenReady = true,
        positionMs = positionMs,
        durationMs = 120_000L,
        canGoNext = true
    )
}
