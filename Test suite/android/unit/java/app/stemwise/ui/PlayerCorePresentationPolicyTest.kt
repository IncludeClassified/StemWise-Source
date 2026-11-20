package app.stemwise.ui

import app.stemwise.model.asClock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Non-seeker policies previously stranded in the retired Compose seeker test fixture. */
class PlayerCorePresentationPolicyTest {
    @Test
    fun detailedClockAlwaysUsesTwoFractionalDigits() {
        assertEquals("00:00.00", 0L.asClock(includeMillis = true))
        assertEquals("00:00.00", 5L.asClock(includeMillis = true))
        assertEquals("00:00.01", 10L.asClock(includeMillis = true))
        assertEquals("00:00.99", 999L.asClock(includeMillis = true))
        assertEquals("00:01.00", 1_000L.asClock(includeMillis = true))
        assertEquals("01:00.00", 60_000L.asClock(includeMillis = true))
        assertEquals("100:00.00", 6_000_000L.asClock(includeMillis = true))
        assertEquals("100:00", 6_000_000L.asClock())
    }

    @Test
    fun keyTranspositionUnderstandsMusicalAccidentalSymbols() {
        assertEquals("B minor", transposedKey("B♭ minor", 1))
        assertEquals("A minor", transposedKey("B♭ minor", -1))
        assertEquals("G major", transposedKey("F♯ major", 1))
    }

    @Test
    fun everyAcceptedTonicTransposesAcrossBothDirectionsAndOctaves() {
        val naturals = listOf("C" to 0, "D" to 2, "E" to 4, "F" to 5,
            "G" to 7, "A" to 9, "B" to 11)
        val names = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        for ((note, pitch) in naturals) for ((accidental, delta) in listOf("" to 0, "#" to 1, "b" to -1)) {
            val tonic = note + accidental
            for (shift in listOf(-25, -12, -1, 1, 12, 25)) for (mode in listOf("major", "minor")) {
                val expected = names[Math.floorMod(pitch + delta + shift, 12)] + " " + mode
                assertEquals("$tonic $mode shift $shift", expected, transposedKey("$tonic $mode", shift))
            }
        }
        assertEquals("C minor", transposedKey("Cb minor", 1))
        assertEquals("invalid major", transposedKey("invalid major", 1))
    }

    @Test
    fun currentDecoderDurationOverridesPlaylistMetadata() {
        assertEquals(277_820L, effectiveTimelineDuration(true, 277_820L, 278_000L))
        assertEquals(278_000L, effectiveTimelineDuration(false, 277_820L, 278_000L))
        assertEquals(278_000L, effectiveTimelineDuration(true, 0L, 278_000L))
    }
}
