package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MetronomeOutputPolicyTest {
    @Test
    fun maximumVolumeUsesFullNativeGainAndEndpointsRemainBounded() {
        assertEquals(0f, metronomeClickAmplitude(-1f), 0f)
        assertEquals(.5f, metronomeClickAmplitude(.5f), .0001f)
        assertEquals(1f, metronomeClickAmplitude(1f), 0f)
        assertEquals(1f, metronomeClickAmplitude(2f), 0f)
    }

    @Test
    fun nonFiniteMetronomeInputsAreRejectedBeforeTheyReachTheNativeContract() {
        assertNull(normalizedMetronomeBpmOrNull(Float.NaN))
        assertNull(normalizedMetronomeBpmOrNull(Float.POSITIVE_INFINITY))
        assertNull(normalizedMetronomeVolumeOrNull(Float.NaN))
        assertNull(normalizedMetronomeVolumeOrNull(Float.NEGATIVE_INFINITY))
        assertEquals(20f, normalizedMetronomeBpmOrNull(-1f))
        assertEquals(1f, normalizedMetronomeVolumeOrNull(2f))
    }
}
