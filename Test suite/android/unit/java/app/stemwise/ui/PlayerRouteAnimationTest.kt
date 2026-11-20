package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRouteAnimationTest {
    @Test
    fun chromeFollowsTheDocumentedNonOverlappingAdmissionWindows() {
        val travelSamples = (0..100).map { it / 100f }
        travelSamples.forEach { progress ->
            assertEquals(
                0f,
                PlayerMotionPolicy.miniChromeAlpha(progress) *
                    PlayerMotionPolicy.fullChromeAlpha(progress),
                .001f
            )
        }
        assertEquals(0f, PlayerMotionPolicy.miniChromeAlpha(.30f), .001f)
        assertEquals(0f, PlayerMotionPolicy.fullChromeAlpha(.54f), .001f)
        assertEquals(.5f, PlayerMotionPolicy.fullChromeAlpha(.72f), .001f)
        assertEquals(1f, PlayerMotionPolicy.fullChromeAlpha(.90f), .001f)
        assertEquals(0f, PlayerMotionPolicy.headerAlpha(.84f), .001f)
        assertEquals(.384_615_4f, PlayerMotionPolicy.headerAlpha(.89f), .001f)
        assertEquals(1f, PlayerMotionPolicy.headerAlpha(.97f), .001f)
        assertEquals(.5f, PlayerMotionPolicy.identityAlpha(.69f), .001f)
        assertEquals(0f, PlayerMotionPolicy.identityAlpha(.50f), .001f)
    }

    @Test
    fun reversingTheSameProgressProducesTheSameVisualState() {
        val forward = (0..100).map { it / 100f }
        val reverse = forward.reversed()
        val forwardByProgress = forward.associateWith(::visualState)

        reverse.forEach { progress ->
            assertEquals(forwardByProgress.getValue(progress), visualState(progress))
        }
    }

    @Test
    fun chromeInputNeverOverlapsAndTheTravelBandOwnsNoControls() {
        (0..100).forEach { sample ->
            val progress = sample / 100f
            assertFalse(
                PlayerMotionPolicy.miniChromeInteractive(progress) &&
                    PlayerMotionPolicy.destinationChromeInteractive(progress)
            )
        }
        assertTrue(PlayerMotionPolicy.miniChromeInteractive(0f))
        assertFalse(PlayerMotionPolicy.miniChromeInteractive(.18f))
        assertFalse(PlayerMotionPolicy.destinationChromeInteractive(.95f))
        assertTrue(PlayerMotionPolicy.destinationChromeInteractive(.96f))
        assertEquals(1f, PlayerMotionPolicy.headerAlpha(1f), .001f)
        assertEquals(1f, PlayerMotionPolicy.fullChromeAlpha(1f), .001f)
        assertEquals(1f, PlayerMotionPolicy.identityAlpha(1f), .001f)
    }

    private fun visualState(progress: Float): List<Float> = listOf(
        PlayerMotionPolicy.miniChromeAlpha(progress),
        PlayerMotionPolicy.headerAlpha(progress),
        PlayerMotionPolicy.fullChromeAlpha(progress),
        PlayerMotionPolicy.identityAlpha(progress)
    )
}
