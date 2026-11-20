package app.stemwise.ui

import app.stemwise.analysis.BeatEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class MetronomeVisualTimingTest {
    private val beats = listOf(BeatEvent(0L, true), BeatEvent(500L, false))

    @Test fun finiteGridDoesNotCreateOutroOrSeekPulses() {
        assertEquals(0L, metronomeVisualBeatOrdinal(0L, beats, 120f, 1))
        assertEquals(1L, metronomeVisualBeatOrdinal(500L, beats, 120f, 1))
        for (position in listOf(1_000L, 1_500L, 50_000L)) {
            assertEquals("Exhausted grid at $position", -1L,
                metronomeVisualBeatOrdinal(position, beats, 120f, 1))
        }
    }

    @Test fun finalBeatRetainsItsRealSubdivisionsThenExhausts() {
        assertEquals(4L, metronomeVisualBeatOrdinal(500L, beats, 120f, 4))
        assertEquals(5L, metronomeVisualBeatOrdinal(625L, beats, 120f, 4))
        assertEquals(6L, metronomeVisualBeatOrdinal(750L, beats, 120f, 4))
        assertEquals(7L, metronomeVisualBeatOrdinal(875L, beats, 120f, 4))
        assertEquals(-1L, metronomeVisualBeatOrdinal(1_000L, beats, 120f, 4))
        assertEquals(0L, metronomeVisualBeatOrdinal(0L, beats, 120f, 4))
    }
}
