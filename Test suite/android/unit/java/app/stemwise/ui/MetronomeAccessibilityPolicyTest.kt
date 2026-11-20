package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetronomeAccessibilityPolicyTest {
    @Test
    fun bpmSetProgressRoundsToTheIntendedBoundedInteger() {
        assertEquals(40f, metronomeBpmSetProgressValue(-1f))
        assertEquals(137f, metronomeBpmSetProgressValue(136.6f))
        assertEquals(240f, metronomeBpmSetProgressValue(999f))
        assertEquals(64f, metronomeBpmSetProgressValue(40f, 64f..240f))
    }

    @Test
    fun bpmButtonsMoveOneIntegerAndStopAtTheReachableSongRange() {
        assertEquals(40f..160f, reachableMetronomeBpmRange(80f))
        val range = reachableMetronomeBpmRange(127.8f)
        assertEquals(64f, range.start)
        assertEquals(255f, range.endInclusive)
        assertEquals(127f, metronomeBpmStepValue(127.8f, -1, range))
        assertEquals(129f, metronomeBpmStepValue(127.8f, 1, range))
        assertEquals(64f, metronomeBpmStepValue(64f, -1, range))
        assertEquals(255f, metronomeBpmStepValue(255f, 1, range))
    }

    @Test
    fun volumeSetProgressPreservesTheIntendedNormalizedValue() {
        assertEquals(0f, metronomeVolumeSetProgressValue(-1f))
        assertEquals(.375f, metronomeVolumeSetProgressValue(.375f))
        assertEquals(1f, metronomeVolumeSetProgressValue(2f))
    }

    @Test
    fun nonFiniteAccessibilityValuesAreRejected() {
        assertNull(metronomeBpmSetProgressValue(Float.NaN))
        assertNull(metronomeBpmSetProgressValue(Float.POSITIVE_INFINITY))
        assertNull(metronomeVolumeSetProgressValue(Float.NEGATIVE_INFINITY))
    }
}
