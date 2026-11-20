package app.stemwise.ui

import app.stemwise.analysis.effectiveGridBpm
import app.stemwise.analysis.metronomeAvailableFor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMetronomeAvailabilityTest {
    @Test
    fun `absent or single beat timing cannot define a metronome interval`() {
        for (count in listOf(0, 1)) assertFalse(metronomeAvailableFor(count))
    }

    @Test
    fun `two or more usable beat events enable metronome`() {
        for (count in listOf(2, 4, 16)) assertTrue(metronomeAvailableFor(count))
    }

    @Test
    fun `audible beat grid is the bpm authority when it is usable`() {
        assertEquals(
            120f,
            effectiveGridBpm(
                metadataBpm = 96f,
                beatTimesMs = listOf(0L, 500L, 1_000L, 1_500L, 2_000L)
            ),
            0.001f
        )
    }

    @Test
    fun actualGridReferencePreservesSlowFastAndVariableIntervalsAcrossTheTempoRange() {
        for ((interval, expectedBpm) in listOf(5_000L to 12f, 200L to 300f, 100L to 600f)) {
            val reference = effectiveGridBpm(120f, (0L..4L).map { it * interval })
            assertEquals(expectedBpm, reference, 0.001f)
            val range = reachableMetronomeBpmRange(reference)
            assertEquals(expectedBpm * 0.5f, range.start, 0.001f)
            assertEquals(expectedBpm * 2f, range.endInclusive, 0.001f)
            val requested = metronomeBpmSetProgressValue(range.start, range)!!
            assertEquals(0.5f, requested / reference, 0.001f)
        }
        assertEquals(300f, effectiveGridBpm(120f, listOf(0L, 190L, 390L, 600L)), 0.001f)
        assertEquals(150f..600f, reachableMetronomeBpmRange(300f))
    }

    @Test
    fun `metronome bpm falls back to metadata then safe default for sparse grids`() {
        assertEquals(96f, effectiveGridBpm(96f, listOf(0L)), 0.001f)
        assertEquals(120f, effectiveGridBpm(null, emptyList()), 0.001f)
    }

    @Test fun speedReadoutsRemainIndependentOfSparseOrUsableBeatTiming() {
        for (count in listOf(0, 1)) assertFalse(metronomeAvailableFor(count))
        assertTrue(metronomeAvailableFor(4))
        assertEquals("0.5×", tuneSpeedValue(.5f))
        assertEquals("1×", tuneSpeedValue(1f))
        assertEquals("2×", tuneSpeedValue(2f))
        val gridBpm = effectiveGridBpm(96f, listOf(0, 500, 1_000, 1_500).map(Int::toLong))
        assertEquals("240 BPM", metronomeBpmLabel(gridBpm * 2f))
        assertEquals("120 BPM", metronomeBpmLabel(gridBpm))
    }

}
