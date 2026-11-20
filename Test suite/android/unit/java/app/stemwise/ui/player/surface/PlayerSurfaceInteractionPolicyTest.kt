package app.stemwise.ui.player.surface

import app.stemwise.ui.design.StemwiseAuraContract
import app.stemwise.ui.TrackVisualDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Renderer admission, cancellation and body pixels are exercised by
// NativePlayerRenderingInstrumentationTest.approvedNativeRolesRenderAndRemainActionable;
// held/rebound ownership by NativePlayerGestureInstrumentationTest. The tests below
// independently exercise the real retained envelopes and coordinate policies.
class PlayerSurfaceInteractionPolicyTest {
    @Test
    fun fineMovementStaysInMillisecondsAcrossSpeedsDurationsAndDensities() {
        for (density in listOf(1f, 2.9375f, 4f)) {
            for (duration in listOf(60_000L, 240_000L, 14_400_000L)) {
                fun gain(speed: Double, previous: Double = 0.0) =
                    playerSurfaceContinuousScrubSensitivityMsPerPx(
                        duration, 392f * density, density, speed, 1f, previous, 16L)
                assertEquals(5.0, gain(20.0) * 50 * density, .001)
                assertEquals(100.0, gain(420.0) * 50 * density, .001)
                assertTrue(gain(180.0) in gain(20.0)..gain(420.0))
                assertTrue("Entering Fine cannot inherit full-song gain", gain(20.0, 100_000.0) * density <= 2.0)
            }
        }
    }

    @Test
    fun `blocked pager edges resist smoothly within six percent of a page`() {
        var previous = 0f
        for (drag in listOf(.01f, .1f, .5f, 1f, 10f)) {
            val resisted = playerSurfaceResistedPagerOffset(drag, true)
            assertTrue(resisted > previous && resisted < .06f)
            assertEquals(-resisted, playerSurfaceResistedPagerOffset(-drag, true), .0001f)
            previous = resisted
        }
        assertEquals(.5f, playerSurfaceResistedPagerOffset(.5f, false), 0f)
    }

    @Test
    fun `pager input belongs only to compact or visible destination chrome`() {
        assertTrue(playerSurfacePagerInputAdmitted(.179f, bodyInputVisible = false))
        listOf(.18f, .5f, .95f).forEach { progress ->
            assertFalse(playerSurfacePagerInputAdmitted(progress, bodyInputVisible = true))
        }
        assertFalse(playerSurfacePagerInputAdmitted(.96f, bodyInputVisible = false))
        assertTrue(playerSurfacePagerInputAdmitted(.96f, bodyInputVisible = true))
    }

    @Test
    fun `loop clearance moves one shared lower stack continuously`() {
        assertEquals(120f, playerSurfaceLowerStackTopPx(120f, 180f, 0f), .0001f)
        assertEquals(150f, playerSurfaceLowerStackTopPx(120f, 180f, .5f), .0001f)
        assertEquals(180f, playerSurfaceLowerStackTopPx(120f, 180f, 1f), .0001f)
        assertEquals(120f, playerSurfaceLowerStackTopPx(120f, 100f, 1f), .0001f)

        val moving = playerSurfaceLoopClearanceProgress(
            start = 0f,
            target = 1f,
            elapsedMs = LOOP_CLEARANCE_MOTION_DURATION_MS / 2L,
            reducedMotion = false
        )
        assertTrue(moving in .001f..<.999f)
        assertEquals(
            1f,
            playerSurfaceLoopClearanceProgress(0f, 1f, 0L, reducedMotion = true),
            .0001f
        )
    }

    @Test
    fun `stems loop reflow and non adjacent track changes stay animated`() {
        assertFalse(
            playerSurfaceLoopClearanceShouldSnap(
                initialBind = false,
                ownerChanged = false,
                sourceChanged = false,
                animatorsEnabled = true
            )
        )
    }

    @Test
    fun clockBaselineClearsTheRailAndTracksTheLoopLiftAcrossDensities() {
        for (density in listOf(1f, 2.9375f, 4f)) {
            val ascent = -18f * density
            val rail = 300f * density
            val ordinary = playerSurfaceSeekerClockBaselineY(rail, ascent, density)
            val loop = playerSurfaceSeekerClockBaselineY(rail, ascent, density, 1f)
            assertTrue(ordinary < rail + ascent)
            assertTrue(loop < ordinary)
            assertEquals((ordinary + loop) / 2f,
                playerSurfaceSeekerClockBaselineY(rail, ascent, density, .5f), .0001f)
            assertEquals(ordinary, playerSurfaceSeekerClockBaselineY(rail, ascent, density, Float.NaN), 0f)
        }
    }

    @Test
    fun `reduced motion makes scrub handle growth immediate`() {
        assertEquals(0L, playerSurfaceScrubGrowthDurationMs(null, reducedMotion = true))
        assertEquals(
            0L,
            playerSurfaceScrubGrowthDurationMs(
                FullPlayerLoopBoundary.START,
                reducedMotion = true
            )
        )
        assertTrue(playerSurfaceScrubGrowthDurationMs(null, reducedMotion = false) > 0)
        assertTrue(
            playerSurfaceScrubGrowthDurationMs(
                FullPlayerLoopBoundary.END,
                reducedMotion = false
            ) > 0
        )
    }

    @Test
    fun `full player pager accepts shorter drags and modest flings at valid queue edges`() {
        assertEquals(
            TrackVisualDirection.HIGHER_INDEX,
            playerSurfaceEasyPagerReleaseDirection(-.15f, 0f, hasLower = true, hasHigher = true)
        )
        assertEquals(
            TrackVisualDirection.LOWER_INDEX,
            playerSurfaceEasyPagerReleaseDirection(.05f, .8f, hasLower = true, hasHigher = true)
        )
        assertEquals(
            null,
            playerSurfaceEasyPagerReleaseDirection(.10f, .5f, hasLower = true, hasHigher = true)
        )
        assertEquals(
            null,
            playerSurfaceEasyPagerReleaseDirection(.2f, 0f, hasLower = false, hasHigher = true)
        )
    }

    @Test
    fun `transport down preview uses the same direction as pager movement`() {
        assertEquals(
            null,
            playerSurfaceTransportPreviewOffset(PlayerSurfaceTransportCommand.PREVIOUS)
        )
        assertEquals(
            -1f,
            playerSurfaceTransportPreviewOffset(PlayerSurfaceTransportCommand.NEXT)
        )
        listOf(
            PlayerSurfaceTransportCommand.PLAY_PAUSE,
            PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE,
            PlayerSurfaceTransportCommand.REPEAT_CYCLE
        ).forEach { command ->
            assertEquals(null, playerSurfaceTransportPreviewOffset(command))
        }
    }

    @Test
    fun `pager release velocity expires after a stationary pause`() {
        val immediateRelease = playerSurfacePagerVelocityAfterSample(
            previousVelocityFractionPerSecond = 2f,
            instantaneousVelocityFractionPerSecond = 0f,
            elapsedMs = 16L
        )
        val pausedRelease = playerSurfacePagerVelocityAfterSample(
            previousVelocityFractionPerSecond = 2f,
            instantaneousVelocityFractionPerSecond = 0f,
            elapsedMs = 200L
        )

        assertTrue(immediateRelease > .72f)
        assertTrue(pausedRelease < .72f)
        assertEquals(
            null,
            playerSurfaceEasyPagerReleaseDirection(
                offsetFraction = .1f,
                velocityFractionPerSecond = pausedRelease,
                hasLower = true,
                hasHigher = true
            )
        )
        assertEquals(
            1.25f,
            playerSurfacePagerVelocityAfterSample(0f, 1.25f, elapsedMs = 400L),
            .0001f
        )
    }

    @Test
    fun `seeker velocity and fine depth share one continuous sensitivity curve`() {
        val deliberate = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 20.0, 0f, 0.0, 16L
        )
        val ordinary = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 420.0, 0f, 0.0, 16L
        )
        val fine = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 20.0, 1f, 0.0, 16L
        )
        val smoothed = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 420.0, 0f, deliberate, 16L
        )

        assertTrue(fine < deliberate)
        assertTrue(deliberate < ordinary)
        assertEquals(ordinary, smoothed, .001)
        val smoothSlowdown = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 20.0, 0f, ordinary, 16L
        )
        assertTrue(smoothSlowdown > deliberate)
        assertTrue(smoothSlowdown < ordinary)
    }

    @Test
    fun `seeker gain settles to the new speed after a real pointer pause`() {
        val fast = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 420.0, 0f, 0.0, 16L
        )
        val slowTarget = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 20.0, 0f, 0.0, 16L
        )
        val afterPause = playerSurfaceContinuousScrubSensitivityMsPerPx(
            240_000L, 1_000f, 2f, 20.0, 0f, fast, 1_000L
        )

        assertEquals(slowTarget, afterPause, .001)
    }

    @Test
    fun `native selected envelope fades both directions and honors reduced motion`() {
        assertEquals(
            1f,
            playerSurfaceSelectedStateProgress(1f, false, 0L, reducedMotion = false),
            .0001f
        )
        val midway = playerSurfaceSelectedStateProgress(
            1f,
            selected = false,
            elapsedMs = StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS / 2L,
            reducedMotion = false
        )
        assertTrue(midway in 0f..1f)
        assertTrue(midway < 1f)
        assertEquals(
            0f,
            playerSurfaceSelectedStateProgress(
                1f,
                selected = false,
                elapsedMs = StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS.toLong(),
                reducedMotion = false
            ),
            .0001f
        )
        assertEquals(
            1f,
            playerSurfaceSelectedStateProgress(0f, true, 1L, reducedMotion = true),
            .0001f
        )

        val envelope = NativeSelectedStateEnvelope()
        assertEquals(1f, envelope.sample(true, 0L, reducedMotion = false), .0001f)
        assertEquals(1f, envelope.sample(false, 10L, reducedMotion = false), .0001f)
        assertTrue(envelope.needsAnotherFrame(10L))
        val settledAt = 10L + StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS
        assertEquals(0f, envelope.sample(false, settledAt, reducedMotion = false), .0001f)
        assertFalse(envelope.needsAnotherFrame(settledAt))
        envelope.reset()
        assertEquals(0f, envelope.sample(false, settledAt + 1L, reducedMotion = false), .0001f)
        assertFalse(envelope.needsAnotherFrame(settledAt + 1L))

        val press = NativePressBloomFrame().apply {
            visible = true
        }
        assertEquals(
            1f,
            playerSurfaceSelectedAuraProgress(1f),
            .0001f
        )
        press.visible = false
        assertEquals(
            1f,
            playerSurfaceSelectedAuraProgress(1f),
            .0001f
        )
    }

    @Test
    fun `volume value label fades from its current position and reduced motion snaps`() {
        val duration = app.stemwise.ui.design.StemwiseSliderValueLabelMetrics.FADE_DURATION_MS
        val envelope = NativeSelectedStateEnvelope(duration)
        envelope.sample(false, 0L, reducedMotion = false)
        assertEquals(0f, envelope.sample(true, 10L, reducedMotion = false), .0001f)
        val appearing = envelope.sample(true, 10L + duration / 2L, reducedMotion = false)
        assertTrue(appearing in .01f.. .99f)
        assertEquals(appearing, envelope.sample(false, 10L + duration / 2L, reducedMotion = false), .0001f)
        assertEquals(0f, envelope.sample(false, 10L + duration * 2L, reducedMotion = false), .0001f)
        assertFalse(envelope.needsAnotherFrame(10L + duration * 2L))
        assertEquals(1f, envelope.sample(true, 500L, reducedMotion = true), .0001f)
        assertEquals(0f, envelope.sample(false, 501L, reducedMotion = true), .0001f)
        assertFalse(envelope.needsAnotherFrame(501L))
    }

    @Test
    fun `native press is brief fixed size and preserves selected colour`() {
        for (reducedMotion in listOf(false, true)) {
            val state = NativePressBloomState()
            val frame = NativePressBloomFrame()
            state.start(nowMs = 0L, reducedMotion = reducedMotion)
            assertTrue(state.sample(0L, frame))
            assertTrue(frame.visible)
            assertTrue(state.sample(72L, frame))
            assertEquals(.5f, frame.effectAlpha, .0001f)
            assertEquals(1f, playerSurfaceSelectedAuraProgress(1f), .0001f)
            assertTrue(state.sample(144L, frame))
            assertFalse(frame.visible)
            assertFalse(frame.needsAnotherFrame)
            assertFalse(state.sample(145L, frame))
        }
    }

    @Test
    fun `reduced bloom is bounded while movable highlight follows pointer ownership`() {
        val bloom = NativePressBloomState()
        val frame = NativePressBloomFrame()
        bloom.start(nowMs = 10L, reducedMotion = true)
        assertTrue(bloom.sample(nowMs = 10L, output = frame))
        assertTrue(frame.visible)
        assertTrue(frame.needsAnotherFrame)
        assertTrue(
            bloom.sample(
                nowMs = 10L + StemwiseAuraContract.REDUCED_PRESS_HIGHLIGHT_MS,
                output = frame
            )
        )
        assertFalse(frame.visible)

        val sparkle = NativeHeldHighlightState()
        sparkle.pointerDown()
        assertTrue(sparkle.advance())
        assertTrue(sparkle.isReducedHighlightVisible())
        assertFalse(sparkle.needsAnotherFrame())
    }

    @Test
    fun stemsGutterSeekRemainsAdmittedAlongsideTheSharedArtworkAndSongHeader() {
        val layout = app.stemwise.ui.portraitPlayerLayout(432f, 860f,
            app.stemwise.playback.PlaybackSourceMode.STEMS, panelExpanded = false)
        val original = app.stemwise.ui.portraitPlayerLayout(432f, 860f,
            app.stemwise.playback.PlaybackSourceMode.ORIGINAL, panelExpanded = false)
        assertEquals(original.artworkSizeDp, layout.artworkSizeDp, 0f)
        assertEquals(original.identityHeightDp, layout.identityHeightDp, 0f)
        assertTrue(layout.artworkSizeDp > 0f && layout.identityHeightDp > 0f)
        assertTrue(playerSurfacePagerInputAdmitted(1f, true,
            pagerHit = false, backgroundSeekAllowed = true))
        assertFalse(playerSurfacePagerInputAdmitted(1f, false,
            pagerHit = false, backgroundSeekAllowed = true))
        assertFalse(playerSurfacePagerInputAdmitted(.5f, true,
            pagerHit = false, backgroundSeekAllowed = true))
        assertFalse(playerSurfacePagerInputAdmitted(1f, true,
            pagerHit = false, backgroundSeekAllowed = false))
    }

    @Test
    fun stemsBackgroundDoubleTapSeeksFiveSecondsWithoutArtworkOrMixerTargets() {
        val absentArtwork = PlayerSurfaceBounds(0f, 0f, 0f, 0f)
        val header = PlayerSurfaceBounds(0f, 60f, 432f, 172f)
        assertEquals(-5_000L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.STEMS, 30f, 100f, absentArtwork, header))
        assertEquals(5_000L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.STEMS, 400f, 100f, absentArtwork, header))
        assertEquals(0L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.STEMS, 30f, 200f, absentArtwork, header))
        assertEquals(0L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.STEMS, 30f, 40f, absentArtwork, header))
        assertEquals(0L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.ORIGINAL, 30f, 100f, absentArtwork, header))
        val artwork = PlayerSurfaceBounds(80f, 90f, 352f, 362f)
        assertEquals(-5_000L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.ORIGINAL, 100f, 200f, artwork, header))
        assertEquals(5_000L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.ORIGINAL, 300f, 200f, artwork, header))
        assertEquals(0L, playerSurfaceDoubleTapSeekDelta(
            PlayerSurfaceSourceMode.ORIGINAL, 30f, 100f, artwork, header))
    }

    @Test
    fun `artwork double tap requires nearby coordinates and the same owner`() {
        fun matches(y: Float, generation: Long) = playerSurfaceArtworkDoubleTapMatches(
            100L, 20f, 30f, 7L,
            200L, 24f, y, generation,
            timeoutMs = 300L,
            slopPx = 12f
        )

        assertTrue(matches(y = 35f, generation = 7L))
        assertFalse(matches(y = 80f, generation = 7L))
        assertFalse(matches(y = 35f, generation = 8L))
    }

    @Test
    fun `hold highlight ends immediately on release without an animation clock`() {
        val state = NativeHeldHighlightState()
        state.pointerDown()
        assertTrue(state.isReducedHighlightVisible())
        assertTrue(state.advance())
        assertFalse(state.needsAnotherFrame())
        assertFalse(state.advance())
        state.pointerUpOrCancel()
        assertFalse(state.isReducedHighlightVisible())
        assertTrue(state.advance())
        assertFalse(state.advance())
    }

}
