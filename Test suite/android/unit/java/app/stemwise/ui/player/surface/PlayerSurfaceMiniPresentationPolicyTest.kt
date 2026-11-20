package app.stemwise.ui.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Real mini fill/phase pixels: DesignSystemNativePlayerInstrumentationTest
// .reducedMotionFreezesDecorativeMiniPhaseWhilePositionStillChangesTheFill.
class PlayerSurfaceMiniPresentationPolicyTest {
    @Test
    fun `passive mini keeps its paint cadence while interaction always gets the next frame`() {
        assertEquals(24L, playerSurfacePassiveFrameDelayMs(0f, false, 120f, 0L))
        assertEquals(16L, playerSurfacePassiveFrameDelayMs(0f, false, 60f, 0L))
        assertEquals(16L, playerSurfacePassiveFrameDelayMs(0f, false, Float.NaN, 0L))
        assertEquals(0L, playerSurfacePassiveFrameDelayMs(0f, true, 120f, 0L))
        assertEquals(0L, playerSurfacePassiveFrameDelayMs(.01f, false, 120f, 0L))
        assertEquals(0L, playerSurfacePassiveFrameDelayMs(1f, false, 120f, 0L))
    }

    @Test
    fun `early passive callbacks preserve every original mini paint frame`() {
        for (rate in listOf(60f, 90f, 120f, 144f, 240f)) {
            val vsyncNanos = (1_000_000_000.0 / rate).toLong()
            val expected = mutableListOf<Long>()
            val actual = mutableListOf<Long>()
            var lastExpected = 0L
            var lastActual = 0L
            var nextCallback = 1L
            var callbacks = 0
            for (frame in 1L..240L) {
                val now = frame * vsyncNanos
                if (now - lastExpected >= 33_000_000L) {
                    expected += frame
                    lastExpected = now
                }
                if (frame != nextCallback) continue
                callbacks++
                if (now - lastActual >= 33_000_000L) {
                    actual += frame
                    lastActual = now
                }
                val delayMs = playerSurfacePassiveFrameDelayMs(
                    0f, false, rate, (now - lastActual) / 1_000_000L)
                nextCallback = frame + kotlin.math.ceil(delayMs * 1_000_000.0 / vsyncNanos)
                    .toLong().coerceAtLeast(1L)
            }
            assertEquals("Mini cadence changed at $rate Hz", expected, actual)
            if (rate >= 90f) assertTrue("Passive callbacks were not reduced at $rate Hz", callbacks < 240)
        }
    }

    @Test
    fun `overflowing identity reverses smoothly while fitting identity stays still`() {
        assertTrue(playerSurfaceIdentityMarqueeMotionAllowed(true, true, true))
        assertFalse(playerSurfaceIdentityMarqueeMotionAllowed(false, true, true))
        assertFalse(playerSurfaceIdentityMarqueeMotionAllowed(true, false, true))
        assertFalse(playerSurfaceIdentityMarqueeMotionAllowed(true, true, false))
        assertEquals(
            0f,
            playerSurfaceIdentityMarqueeTranslationPx(
                contentWidthPx = 180f,
                viewportWidthPx = 200f,
                elapsedMs = 5_000L,
                speedPxPerSecond = 100f,
                rightToLeft = false
            ),
            .0001f
        )
        assertEquals(
            -100f,
            playerSurfaceIdentityMarqueeTranslationPx(
                contentWidthPx = 300f,
                viewportWidthPx = 100f,
                elapsedMs = 1_900L,
                speedPxPerSecond = 100f,
                rightToLeft = false
            ),
            .0001f
        )
        assertEquals(
            100f,
            playerSurfaceIdentityMarqueeTranslationPx(
                contentWidthPx = 300f,
                viewportWidthPx = 100f,
                elapsedMs = 1_900L,
                speedPxPerSecond = 100f,
                rightToLeft = true
            ),
            .0001f
        )
    }

    @Test
    fun `mini playback fraction is bounded and handles absent duration`() {
        assertEquals(0f, playerSurfaceMiniPlaybackFraction(10L, 0L), .0001f)
        assertEquals(0f, playerSurfaceMiniPlaybackFraction(-10L, 100L), .0001f)
        assertEquals(.5f, playerSurfaceMiniPlaybackFraction(50L, 100L), .0001f)
        assertEquals(1f, playerSurfaceMiniPlaybackFraction(200L, 100L), .0001f)
    }

}
