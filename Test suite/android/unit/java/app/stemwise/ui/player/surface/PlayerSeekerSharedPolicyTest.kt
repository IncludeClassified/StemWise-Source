package app.stemwise.ui.player.surface

import app.stemwise.ui.LoopStartDetentState
import app.stemwise.ui.PrecisionScrubMode
import app.stemwise.ui.fineModeEntryThresholdPx
import app.stemwise.ui.fineModeExitThresholdPx
import app.stemwise.ui.loopStartDetentStep
import app.stemwise.ui.quantizeSeekPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the small policy seam shared by the mounted native player and its semantics. */
class PlayerSeekerSharedPolicyTest {
    @Test
    fun seekPositionsPreserveMillisecondPrecisionAndClampToTheSong() {
        assertEquals(0L, quantizeSeekPosition(-200.0, 1_000L))
        for (value in listOf(2L, 3L, 12L, 13L, 16L, 999L)) {
            assertEquals(value, quantizeSeekPosition(value.toDouble(), 1_000L))
        }
        assertEquals(12L, quantizeSeekPosition(12.4, 1_000L))
        assertEquals(13L, quantizeSeekPosition(12.5, 1_000L))
        assertEquals(1_002L, quantizeSeekPosition(1_002.0, 1_003L))
        assertEquals(1_003L, quantizeSeekPosition(1_004.0, 1_003L))
        assertEquals(0L, quantizeSeekPosition(Double.NaN, 1_000L))
        assertEquals(0L, quantizeSeekPosition(Double.POSITIVE_INFINITY, 1_000L))
        assertEquals(0L, quantizeSeekPosition(300.0, 0L))
    }

    @Test
    fun nativeFineThresholdsRemainDeliberateAndAsymmetric() {
        assertEquals(72f, fineModeEntryThresholdPx(touchSlopPx = 24f, density = 3f), .001f)
        assertEquals(100f, fineModeEntryThresholdPx(touchSlopPx = 40f, density = 2f), .001f)
        assertEquals(54f, fineModeExitThresholdPx(touchSlopPx = 24f, density = 3f), .001f)
    }

    @Test
    fun loopStartDetentCapturesEitherApproachAtExactA() {
        fun step(fromMs: Double, movementPx: Float) = loopStartDetentStep(
            state = LoopStartDetentState(),
            currentAccumulatorMs = fromMs,
            movementPx = movementPx,
            msPerPx = 10.0,
            durationMs = 60_000L,
            loopA = 10_003L,
            loopB = 20_000L,
            captureRadiusPx = 5f,
            breakoutDistancePx = 12f
        )

        listOf(
            step(fromMs = 9_000.0, movementPx = 96f),
            step(fromMs = 11_000.0, movementPx = -96f)
        ).forEach { captured ->
            assertTrue(captured.captured)
            assertTrue(captured.state.holding)
            assertEquals(10_003.0, captured.accumulatorMs, .001)
            assertEquals(10_003L, captured.previewPositionMs)
        }
    }

    @Test
    fun loopStartDetentConsumesOnlyBreakoutDistance() {
        val captured = detentStep(
            state = LoopStartDetentState(),
            currentMs = 9_900.0,
            movementPx = 10f
        )
        val held = detentStep(
            state = captured.state,
            currentMs = captured.accumulatorMs,
            movementPx = 11f
        )
        val released = detentStep(
            state = held.state,
            currentMs = held.accumulatorMs,
            movementPx = 3f
        )

        assertEquals(10_000L, held.previewPositionMs)
        assertTrue(held.state.holding)
        assertEquals(10_020.0, released.accumulatorMs, .001)
        assertEquals(10_020L, released.previewPositionMs)
        assertFalse(released.state.holding)
    }

    @Test
    fun singleHighVelocitySamplePreservesPostDetentOvershootInEitherDirection() {
        fun cross(fromMs: Double, movementPx: Float) = loopStartDetentStep(
            state = LoopStartDetentState(),
            currentAccumulatorMs = fromMs,
            movementPx = movementPx,
            msPerPx = 10.0,
            durationMs = 60_000L,
            loopA = 10_000L,
            loopB = 20_000L,
            captureRadiusPx = 5f,
            breakoutDistancePx = 12f
        )

        val leftToRight = cross(9_000.0, 150f)
        val rightToLeft = cross(11_000.0, -150f)
        assertEquals(10_380L, leftToRight.previewPositionMs)
        assertEquals(9_620L, rightToLeft.previewPositionMs)
        assertTrue(leftToRight.captured)
        assertTrue(rightToLeft.captured)
        assertFalse(leftToRight.state.holding)
        assertFalse(rightToLeft.state.holding)
    }

    @Test
    fun detentRearmsOnlyAfterLeavingItsCaptureZone() {
        val captured = detentStep(LoopStartDetentState(), 9_900.0, 10f)
        val justBrokenOut = detentStep(captured.state, captured.accumulatorMs, 13f)
        val jitterAcrossA = detentStep(justBrokenOut.state, justBrokenOut.accumulatorMs, -2f)
        val leavesZone = detentStep(jitterAcrossA.state, jitterAcrossA.accumulatorMs, 7f)
        val returns = detentStep(leavesZone.state, leavesZone.accumulatorMs, -6f)

        assertFalse(justBrokenOut.state.armed)
        assertFalse(jitterAcrossA.captured)
        assertTrue(leavesZone.state.armed)
        assertTrue(returns.captured)
        assertEquals(10_000L, returns.previewPositionMs)
    }

    @Test
    fun incompleteOrInvalidLoopBypassesTheDetentAndClampsNormally() {
        val incomplete = loopStartDetentStep(
            LoopStartDetentState(), 9_900.0, 20f, 10.0, 10_000L,
            10_000L, null, 5f, 12f
        )
        val invalid = loopStartDetentStep(
            LoopStartDetentState(), 9_900.0, -2_000f, 10.0, 10_000L,
            8_000L, 8_000L, 5f, 12f
        )

        assertFalse(incomplete.captured)
        assertEquals(10_000L, incomplete.previewPositionMs)
        assertFalse(invalid.captured)
        assertEquals(0L, invalid.previewPositionMs)
    }

    private fun detentStep(
        state: LoopStartDetentState,
        currentMs: Double,
        movementPx: Float
    ) = loopStartDetentStep(
        state = state,
        currentAccumulatorMs = currentMs,
        movementPx = movementPx,
        msPerPx = 10.0,
        durationMs = 60_000L,
        loopA = 10_000L,
        loopB = 20_000L,
        captureRadiusPx = 5f,
        breakoutDistancePx = 12f
    )


}
