package app.stemwise.ui.player.surface

import app.stemwise.ui.PrecisionScrubMode
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.player.nativeplayer.FullPlayerTestFixtures
import app.stemwise.ui.player.nativeplayer.FullPlayerFineWindowPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
import app.stemwise.ui.player.nativeplayer.FullPlayerAdoptionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Layered guide/cursor pixels: NativePlayerRenderingInstrumentationTest
// .approvedNativeRolesRenderAndRemainActionable. Fine exit hit ownership:
// NativePlayerGestureInstrumentationTest.offscreenLoopMotorsRemainTouchableAtTheirPaintedPositionsDuringFineExit.
// Snapshot adoption is covered by PlayerSurfaceSnapshotUpdatePlannerTest and the real
// rendererRetainsTheExactImmutablePayloadAcrossTransportFineAndSourceUpdates test.
class PlayerSurfaceSeekerPolicyTest {
    @Test fun movingIdentityMustClearTheClockBeforeSeekerChromeIsPainted() {
        assertEquals(0f, playerSurfaceSeekerIdentityClearanceAlpha(99f, 100f, 1f), 0f)
        assertEquals(0f, playerSurfaceSeekerIdentityClearanceAlpha(100f, 100f, 1f), 0f)
        assertEquals(.5f, playerSurfaceSeekerIdentityClearanceAlpha(104f, 100f, 1f), 0f)
        assertEquals(1f, playerSurfaceSeekerIdentityClearanceAlpha(108f, 100f, 1f), 0f)
        assertEquals(1f, playerSurfaceSeekerIdentityClearanceAlpha(220f, 200f, 2f), 0f)
    }

    @Test
    fun onlyTheHeldLoopEndpointGrowsAndOffscreenEndpointsReturnDuringFineExit() {
        for (growth in listOf(0f, .5f, 1f, 2f)) {
            for (boundary in FullPlayerLoopBoundary.entries) {
                for (selected in FullPlayerLoopBoundary.entries + null) {
                    assertEquals(if (boundary == selected) growth.coerceIn(0f, 1f) else 0f,
                        playerSurfaceLoopHandleGrowth(boundary, selected, growth), 0f)
                }
            }
        }
        // A and B can be minutes outside the 5-second Fine viewport. Distance must not
        // delay either endpoint's return until the final frame of the transition.
        for (detailX in listOf(-50_000f, 50_000f)) {
            assertEquals(0f, playerSurfaceLoopBoundaryTransitionAlpha(detailX, 24f, 408f, 1f), 0f)
            for (fine in listOf(.99f, .9f, .5f, 0f)) {
                val x = playerSurfaceLoopBoundaryTransitionX(180f, detailX, 24f, 408f, fine)
                assertTrue(x in 24f..408f)
                assertEquals(1f - fine,
                    playerSurfaceLoopBoundaryTransitionAlpha(detailX, 24f, 408f, fine), .0001f)
            }
        }
        assertEquals(270f, playerSurfaceLoopBoundaryTransitionX(180f, 360f, 24f, 408f, .5f), 0f)
        assertEquals(1f, playerSurfaceLoopBoundaryTransitionAlpha(360f, 24f, 408f, 1f), 0f)
    }

    @Test
    fun fineViewportEndsBeforeTheRailAndHidesAnOutOfRangePlaybackCursor() {
        val viewport = playerSurfaceFineViewport(
            trackStartX = 20f,
            trackEndX = 412f,
            edgeInsetPx = 12f
        )
        assertEquals(32f, viewport.leftX, .001f)
        assertEquals(400f, viewport.rightX, .001f)
        assertFalse(playerSurfaceFinePlaybackCursorVisible(32f, viewport, 9f))
        assertFalse(playerSurfaceFinePlaybackCursorVisible(395f, viewport, 9f))
        assertTrue(playerSurfaceFinePlaybackCursorVisible(216f, viewport, 9f))

    }

    @Test
    fun fineWaveformFollowsVerticalReflowWithoutRebuildingHorizontalSamples() {
        val geometry = PlayerSurfaceWaveformGeometry(1f, 512, 5_000L)
        val packet = FullPlayerTestFixtures.snapshot()
        geometry.rebuild(true, 20f, 412f, 0f, 100f)
        geometry.update(5_000L, 10_000L, packet)
        val count = geometry.visiblePointCount
        val before = geometry.points.copyOf(count)
        geometry.updateVerticalBounds(200f, 320f)
        geometry.update(5_000L, 10_000L, packet)
        assertEquals(count, geometry.visiblePointCount)
        for (point in 0 until count step 4) {
            assertEquals(before[point], geometry.points[point], 0f)
            assertEquals(before[point + 2], geometry.points[point + 2], 0f)
            assertTrue(geometry.points[point + 1] >= 200f)
            assertTrue(geometry.points[point + 3] <= 320f)
            assertEquals(260f, (geometry.points[point + 1] + geometry.points[point + 3]) / 2f, 0.001f)
        }
    }

    @Test
    fun fineWaveformIsEmptyOutsideTheRealSongInsteadOfRepeatingEndpointPlaceholders() {
        assertFalse(playerSurfaceFineWaveformSampleVisible(-1L, 10_000L))
        assertTrue(playerSurfaceFineWaveformSampleVisible(0L, 10_000L))
        assertTrue(playerSurfaceFineWaveformSampleVisible(10_000L, 10_000L))
        assertFalse(playerSurfaceFineWaveformSampleVisible(10_001L, 10_000L))
        assertFalse(playerSurfaceFineWaveformSampleVisible(0L, 0L))

        val geometry = PlayerSurfaceWaveformGeometry(density = 1f, maximumBars = 512, fineViewportMs = 5_000L)
        val packet = FullPlayerTestFixtures.snapshot()
        geometry.rebuild(true, 20f, 412f, 0f, 100f)
        geometry.update(5_000L, 10_000L, packet)
        val completeCount = geometry.visiblePointCount
        assertTrue(completeCount > 0)
        assertEquals(geometry.barCount * 4, completeCount)
        geometry.update(0L, 10_000L, packet)
        val beginningCount = geometry.visiblePointCount
        assertTrue(beginningCount in 4 until completeCount)
        val firstAtBeginning = geometry.points[0]
        geometry.update(10_000L, 10_000L, packet)
        assertEquals(beginningCount, geometry.visiblePointCount)
        assertTrue("Song-end samples must occupy the opposite half of the viewport",
            geometry.points[geometry.visiblePointCount - 4] < firstAtBeginning)
        for (outside in listOf(-10_000L, 20_000L)) {
            geometry.update(outside, 10_000L, packet)
            assertEquals("Out-of-song samples became endpoint placeholders", 0, geometry.visiblePointCount)
        }
        geometry.update(0L, 0L, packet)
        assertEquals(0, geometry.visiblePointCount)

    }

    @Test
    fun stemsNeverDisplayOriginalOrInventedWaveformsWhileActualMixIsUnavailable() {
        val geometry = PlayerSurfaceWaveformGeometry(1f, 512, 5_000L)
        geometry.rebuild(true, 20f, 412f, 0f, 100f)
        val original = FullPlayerTestFixtures.snapshot()
        assertTrue(geometry.hasWaveform(original, 1_000L, 60_000L))
        val stems = FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.STEMS)
        assertFalse(geometry.hasWaveform(stems, 1_000L, 60_000L))
        geometry.update(1_000L, 60_000L, stems)
        assertEquals(0, geometry.visiblePointCount)
        geometry.update(1_000L, 60_000L, null)
        assertEquals(0, geometry.visiblePointCount)

        val mixed = stems.copy(fineWindow = FullPlayerFineWindowPacket(stems.revision + 1L,
            stems.owner.mediaRevision, stems.sourceMixer.source.revision, stems.sourceMixer.mixer.revision,
            1_000L, 5L, FloatArray(200) { if (it % 2 == 0) .2f else .6f }))
        assertEquals(.2f, geometry.amplitude(mixed, 1_000L, 60_000L), 0f)
        assertTrue(geometry.hasWaveform(mixed, 1_000L, 60_000L))
        assertFalse(geometry.hasWaveform(mixed, 2_000L, 60_000L))
        geometry.update(1_000L, 60_000L, mixed)
        assertTrue(geometry.visiblePointCount in 4 until geometry.barCount * 4)
    }

    @Test
    fun mixOrSourceChangesRejectOldFineWindowsWithoutReplacingTheAcceptedSnapshot() {
        val geometry = PlayerSurfaceWaveformGeometry(1f, 512, 5_000L)
        val seed = FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.STEMS)
        val initial = seed.copy(revision = seed.revision + 2L, fineWindow = FullPlayerFineWindowPacket(seed.revision + 1L,
            seed.owner.mediaRevision, seed.sourceMixer.source.revision, seed.sourceMixer.mixer.revision,
            1_000L, 5L, floatArrayOf(.7f)))
        val store = FullPlayerStateStore()
        assertEquals(FullPlayerAdoptionResult.APPLIED_INITIAL, store.adopt(initial))
        for (changed in listOf(
            FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.STEMS, baseRevision = 100L),
            FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.ORIGINAL, baseRevision = 100L)
        )) {
            val stale = changed.copy(fineWindow = initial.fineWindow)
            assertEquals(FullPlayerAdoptionResult.REJECTED_INCOHERENT, store.adopt(stale))
            assertEquals(initial, store.snapshot)
            if (changed.sourceMixer.source.presentedMode == PlayerSurfaceSourceMode.STEMS)
                assertFalse(geometry.hasWaveform(stale, 1_000L, 60_000L))
        }
    }

    @Test
    fun fineLayerAllowsOwnedReleaseAnimationButRejectsStaleMorphs() {
        assertEquals(
            1f,
            playerSurfaceFineLayerFraction(
                waveformMorph = 1f,
                pointerActive = true,
                scrubSessionStarted = true
            ),
            .001f
        )
        assertEquals(
            0f,
            playerSurfaceFineLayerFraction(
                waveformMorph = 1f,
                pointerActive = false,
                scrubSessionStarted = false
            ),
            .001f
        )
        assertEquals(
            .42f,
            playerSurfaceFineLayerFraction(
                waveformMorph = .42f,
                pointerActive = false,
                scrubSessionStarted = false,
                releaseAnimating = true
            ),
            .001f
        )
        assertEquals(
            0f,
            playerSurfaceFineLayerFraction(
                waveformMorph = 1f,
                pointerActive = true,
                scrubSessionStarted = false
            ),
            .001f
        )
    }

    @Test
    fun fineReleaseKeepsRenderedAndHitTestTimelineCentersIdentical() {
        assertEquals(12_000L, playerSurfaceFineTimelineCenterMs(true, 12_000L, 18_000L))
        assertEquals(18_000L, playerSurfaceFineTimelineCenterMs(false, 12_000L, 18_000L))

    }

    @Test
    fun fineClockRequiresTheSameLiveFineContactAsTheWaveform() {
        assertTrue(
            playerSurfaceFineClockVisible(
                pointerActive = true,
                scrubSessionStarted = true,
                fineEngaged = true,
                scrubMode = PrecisionScrubMode.FINE
            )
        )
        assertFalse(
            playerSurfaceFineClockVisible(
                pointerActive = false,
                scrubSessionStarted = false,
                fineEngaged = true,
                scrubMode = PrecisionScrubMode.FINE
            )
        )
        assertFalse(
            playerSurfaceFineClockVisible(
                pointerActive = true,
                scrubSessionStarted = true,
                fineEngaged = false,
                scrubMode = PrecisionScrubMode.FAST_TRAVERSE
            )
        )
    }

    @Test
    fun matchingLoopCommitCannotCancelItsFineReleaseAnimation() {
        assertTrue(
            playerSurfaceCommittedLoopPreservesFineRelease(
                releaseActive = true,
                pointerActive = false,
                releaseBoundary = FullPlayerLoopBoundary.START,
                previewStartMs = 4_000L,
                previewEndMs = 12_000L,
                previousDurationMs = 60_000L,
                nextDurationMs = 60_000L,
                nextStartMs = 4_000L,
                nextEndMs = 12_000L
            )
        )
        assertFalse(
            playerSurfaceCommittedLoopPreservesFineRelease(
                releaseActive = true,
                pointerActive = false,
                releaseBoundary = FullPlayerLoopBoundary.START,
                previewStartMs = 4_000L,
                previewEndMs = 12_000L,
                previousDurationMs = 60_000L,
                nextDurationMs = 61_000L,
                nextStartMs = 4_000L,
                nextEndMs = 12_000L
            )
        )
    }

    @Test
    fun fineClockFormatsBothSidesAtExactMillisecondPrecision() {
        assertEquals("0:00.005", formattedClock(5L, detailed = true))
        assertEquals("0:00.010", formattedClock(10L, detailed = true))
        assertEquals("0:00.999", formattedClock(999L, detailed = true))
        assertEquals("0:01.999", formattedClock(1_999L, detailed = true))
        assertEquals("4:00.000", formattedClock(240_000L, detailed = true))
        assertEquals("1:00:00.000", formattedClock(3_600_000L, detailed = true))
        assertEquals("4:00", formattedClock(240_999L, detailed = false))
    }

    @Test
    fun seekerUsesExactR5RailEndpointsWithoutHeldThumbCompression() {
        val start = playerSurfaceSeekerTrackStartX(20f, 412f, 4f)
        val end = playerSurfaceSeekerTrackEndX(20f, 412f, 4f)
        assertEquals(24f, start, .001f)
        assertEquals(408f, end, .001f)
        assertEquals(
            24f,
            playerSurfaceSeekerPositionX(0f, start, end),
            .001f
        )
        assertEquals(
            408f,
            playerSurfaceSeekerPositionX(1f, start, end),
            .001f
        )
        assertEquals(
            216f,
            playerSurfaceSeekerPositionX(.5f, start, end),
            .001f
        )
        assertEquals(0f, playerSurfaceSeekerProgressAtX(20f, start, end), .001f)
        assertEquals(0f, playerSurfaceSeekerProgressAtX(start, start, end), .001f)
        assertEquals(1f, playerSurfaceSeekerProgressAtX(end, start, end), .001f)
        assertEquals(1f, playerSurfaceSeekerProgressAtX(412f, start, end), .001f)

        // Degenerate and reversed content bounds remain deterministic without moving a semantic
        // endpoint to some unrelated coordinate.
        assertEquals(15f, playerSurfaceSeekerTrackStartX(20f, 10f, 50f), .001f)
        assertEquals(15f, playerSurfaceSeekerTrackEndX(20f, 10f, 50f), .001f)
        assertEquals(15f, playerSurfaceSeekerPositionX(.8f, 15f, 15f), .001f)
        assertEquals(0f, playerSurfaceSeekerProgressAtX(99f, 15f, 15f), .001f)
        assertEquals(12f, PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP, .001f)
        assertEquals(8f, PLAYER_SURFACE_SEEKER_CLOCK_ADDITIONAL_CLEARANCE_DP, .001f)
        assertEquals(72f, playerSurfaceSeekerClockBaselineY(100f, -12f, 1f), .001f)
        assertEquals(68f, playerSurfaceSeekerClockBaselineY(100f, -12f, 1f, 1f), .001f)
        assertEquals(4f, PLAYER_SURFACE_SEEKER_RAIL_ENDPOINT_INSET_DP, .001f)
    }

    @Test
    fun diagonalAndHorizontalTravelCannotToggleFineMode() {
        var state = PlayerSurfaceFineIntentState()
        var mode = PrecisionScrubMode.FAST_TRAVERSE
        val ambiguousDiagonal = playerSurfaceFineIntentStep(
            currentMode = mode,
            state = state,
            deltaX = 10f,
            deltaY = -14f,
            fineEntryThresholdPx = 12f,
            fineExitThresholdPx = 12f
        )
        assertEquals(PrecisionScrubMode.FAST_TRAVERSE, ambiguousDiagonal.mode)
        assertEquals(0f, ambiguousDiagonal.state.qualifyingTravelPx, .001f)

        repeat(8) {
            val step = playerSurfaceFineIntentStep(
                currentMode = mode,
                state = state,
                deltaX = 8f,
                deltaY = -8f,
                fineEntryThresholdPx = 40f,
                fineExitThresholdPx = 40f
            )
            mode = step.mode
            state = step.state
        }
        assertEquals(PrecisionScrubMode.FAST_TRAVERSE, mode)
        assertEquals(0f, state.qualifyingTravelPx, .001f)

        mode = PrecisionScrubMode.FINE
        repeat(12) {
            val step = playerSurfaceFineIntentStep(
                currentMode = mode,
                state = state,
                deltaX = 12f,
                deltaY = 7f,
                fineEntryThresholdPx = 40f,
                fineExitThresholdPx = 40f
            )
            mode = step.mode
            state = step.state
        }
        assertEquals(PrecisionScrubMode.FINE, mode)
        assertEquals(0f, state.qualifyingTravelPx, .001f)
    }

    @Test
    fun deliberateVerticalTravelEntersAndReturnsFromFineWithFreshHysteresis() {
        var state = PlayerSurfaceFineIntentState()
        var mode = PrecisionScrubMode.FAST_TRAVERSE
        repeat(3) {
            val step = playerSurfaceFineIntentStep(
                currentMode = mode,
                state = state,
                deltaX = 1f,
                deltaY = -14f,
                fineEntryThresholdPx = 40f,
                fineExitThresholdPx = 40f
            )
            mode = step.mode
            state = step.state
        }
        assertEquals(PrecisionScrubMode.FINE, mode)
        assertEquals(0f, state.qualifyingTravelPx, .001f)

        // A full horizontal pass neither exits Fine nor contributes to a later exit.
        var step = playerSurfaceFineIntentStep(
            currentMode = mode,
            state = state,
            deltaX = 300f,
            deltaY = 5f,
            fineEntryThresholdPx = 40f,
            fineExitThresholdPx = 40f
        )
        assertEquals(PrecisionScrubMode.FINE, step.mode)
        assertEquals(0f, step.state.qualifyingTravelPx, .001f)

        state = step.state
        repeat(3) {
            step = playerSurfaceFineIntentStep(
                currentMode = mode,
                state = state,
                deltaX = 1f,
                deltaY = 14f,
                fineEntryThresholdPx = 40f,
                fineExitThresholdPx = 40f
            )
            mode = step.mode
            state = step.state
        }
        assertEquals(PrecisionScrubMode.FAST_TRAVERSE, mode)
    }

    @Test
    fun fineReturnsToTheSameContinuousHorizontalMode() {
        val exited = playerSurfaceFineIntentStep(
            currentMode = PrecisionScrubMode.FINE,
            state = PlayerSurfaceFineIntentState(35f),
            deltaX = 0f,
            deltaY = 10f,
            fineEntryThresholdPx = 40f,
            fineExitThresholdPx = 40f
        )
        assertEquals(PrecisionScrubMode.FAST_TRAVERSE, exited.mode)
    }

    @Test
    fun loopBoundaryClampPreventsCrossingAndCancelBaselineIsUnchanged() {
        assertEquals(PlayerSurfaceLoopBoundaryValues(1_203L, 2_307L),
            playerSurfaceAdjustedLoopBoundary(FullPlayerLoopBoundary.START, 1_203.0,
                10_007L, 1_200L, 2_307L, 100L))
        assertEquals(PlayerSurfaceLoopBoundaryValues(1_203L, 2_309L),
            playerSurfaceAdjustedLoopBoundary(FullPlayerLoopBoundary.END, 2_309.0,
                10_007L, 1_203L, 2_307L, 100L))
        val original = PlayerSurfaceLoopBoundaryValues(10_000L, 20_000L)
        val start = playerSurfaceAdjustedLoopBoundary(
            FullPlayerLoopBoundary.START,
            proposedMs = 25_000.0,
            durationMs = 60_000L,
            startMs = original.startMs,
            endMs = original.endMs,
            minimumLoopDurationMs = 100L
        )
        assertEquals(PlayerSurfaceLoopBoundaryValues(19_900L, 20_000L), start)
        val end = playerSurfaceAdjustedLoopBoundary(
            FullPlayerLoopBoundary.END,
            proposedMs = 4_000.0,
            durationMs = 60_000L,
            startMs = original.startMs,
            endMs = original.endMs,
            minimumLoopDurationMs = 100L
        )
        assertEquals(PlayerSurfaceLoopBoundaryValues(10_000L, 10_100L), end)
        // The pure preview never mutates its caller-owned baseline; CANCEL restores this tuple.
        assertEquals(PlayerSurfaceLoopBoundaryValues(10_000L, 20_000L), original)
    }

    @Test
    fun loopLineHitChoosesNearestAndSupportsAnIncompleteA() {
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryHit(101f, 100f, 110f, 14f)
        )
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryHit(111f, 100f, 110f, 14f)
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryHit(100f, 100f, Float.NaN, 14f)
        )

        // The production motor has a full 56 dp horizontal lane. Overlap does not make B
        // unreachable: distance, rather than declaration order, remains authoritative.
        assertEquals(28f, PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP, 0f)
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryHit(
                pointerX = 121f,
                startX = 100f,
                endX = 120f,
                radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryHit(
                pointerX = 103f,
                startX = 100f,
                endX = 106f,
                radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP,
                preferredBoundary = FullPlayerLoopBoundary.END
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryHit(
                pointerX = 100f,
                startX = 100f,
                endX = 200f,
                radiusPx = 14f,
                preferredBoundary = FullPlayerLoopBoundary.END
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryHit(
                pointerX = 101f,
                startX = 100f,
                endX = 106f,
                radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP,
                preferredBoundary = FullPlayerLoopBoundary.END
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryHit(
                pointerX = 99f,
                startX = 100f,
                endX = 100f,
                radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryHit(
                pointerX = 101f,
                startX = 100f,
                endX = 100f,
                radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP
            )
        )

        // The guide and rail are passive even when they share the endpoint X. Only the labelled
        // under-rail A/B motor admits exact boundary adjustment.
        assertEquals(
            null,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.START,
                pointerY = 40f,
                endpointHandleTop = 50f,
                endpointHandleBottom = 68f
            )
        )
        assertEquals(
            null,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.START,
                pointerY = 49.9f,
                endpointHandleTop = 50f,
                endpointHandleBottom = 68f
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.START,
                pointerY = 58f,
                endpointHandleTop = 50f,
                endpointHandleBottom = 68f
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.END,
                pointerY = 68f,
                endpointHandleTop = 68f,
                endpointHandleBottom = 50f
            )
        )
        assertEquals(
            null,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.START,
                pointerY = 58f,
                endpointHandleTop = 50f,
                endpointHandleBottom = 96f,
                playheadExclusiveBottom = 60f
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.START,
            playerSurfaceLoopBoundaryGestureTarget(
                FullPlayerLoopBoundary.START,
                pointerY = 61f,
                endpointHandleTop = 50f,
                endpointHandleBottom = 96f,
                playheadExclusiveBottom = 60f
            )
        )
    }

    @Test
    fun playheadKeepsUpperLaneWhenBothLoopHandlesShareItsX() {
        val sameXBoundary = playerSurfaceLoopBoundaryHit(
            pointerX = 100f,
            startX = 100f,
            endX = 100f,
            radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP
        )
        val playheadLaneBoundary = playerSurfaceLoopBoundaryGestureTarget(
            candidate = sameXBoundary,
            pointerY = 78f,
            endpointHandleTop = 62f,
            endpointHandleBottom = 160f,
            playheadExclusiveBottom = 78f
        )
        assertEquals(null, playheadLaneBoundary)
        assertTrue(
            playerSurfaceScrubPointerAdmitted(
                pointerX = 100f,
                pointerY = 78f,
                acquiredLoopBoundary = playheadLaneBoundary,
                seekerLeft = 20f,
                seekerTop = 40f,
                seekerRight = 180f,
                seekerBottom = 90f
            )
        )
        assertEquals(
            FullPlayerLoopBoundary.END,
            playerSurfaceLoopBoundaryGestureTarget(
                candidate = sameXBoundary,
                pointerY = 79f,
                endpointHandleTop = 62f,
                endpointHandleBottom = 160f,
                playheadExclusiveBottom = 78f
            )
        )
    }

    @Test
    fun fineReleaseHitHeightTracksTheRenderedLoopHandleGrowth() {
        assertEquals(
            StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP,
            playerSurfaceLoopHandleHeightDp(0f),
            0f
        )
        assertEquals(
            (StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP +
                StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP) * .5f,
            playerSurfaceLoopHandleHeightDp(.5f),
            0f
        )
        assertEquals(
            StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP,
            playerSurfaceLoopHandleHeightDp(1f),
            0f
        )
    }

    @Test
    fun liveLoopEchoDoesNotCancelItsOwningHandle() {
        assertTrue(
            playerSurfaceCommittedLoopPreservesActiveDrag(
                pointerActive = true,
                scrubSessionStarted = true,
                boundary = FullPlayerLoopBoundary.END,
                previewStartMs = 1_000L,
                previewEndMs = 3_000L,
                previousDurationMs = 10_000L,
                nextDurationMs = 10_000L,
                nextStartMs = 1_000L,
                nextEndMs = 3_000L
            )
        )
        assertTrue(
            playerSurfaceCommittedLoopPreservesActiveDrag(
                pointerActive = true,
                scrubSessionStarted = true,
                boundary = FullPlayerLoopBoundary.END,
                previewStartMs = 1_000L,
                previewEndMs = 3_000L,
                previousDurationMs = 10_000L,
                nextDurationMs = 10_000L,
                nextStartMs = 1_000L,
                nextEndMs = 3_100L
            )
        )
        assertFalse(
            playerSurfaceCommittedLoopPreservesActiveDrag(
                pointerActive = true,
                scrubSessionStarted = true,
                boundary = FullPlayerLoopBoundary.END,
                previewStartMs = 1_000L,
                previewEndMs = 3_000L,
                previousDurationMs = 10_000L,
                nextDurationMs = 10_000L,
                nextStartMs = 1_100L,
                nextEndMs = 3_000L
            )
        )
    }

    @Test
    fun batchedBCrossingPublishesEvenWhenNewestEndpointReturnsToItsInitialValue() {
        assertFalse(playerSurfaceShouldPublishFoldedLoopBoundary(5_000, 15_000, 5_000, 15_000, -1))
        assertTrue(playerSurfaceShouldPublishFoldedLoopBoundary(5_000, 15_000, 5_000, 15_000, 9_000))
    }

    @Test
    fun loopHandleVisualOwnerPressesOnDownAndReleasesOnUpOrCancel() {
        val owner = PlayerSurfaceLoopHandleVisualOwner()

        owner.pointerDown(FullPlayerLoopBoundary.START)
        assertEquals(FullPlayerLoopBoundary.START, owner.boundary)
        assertTrue(owner.pressed)
        owner.pointerUpOrCancel() // ACTION_UP
        assertFalse(owner.pressed)
        assertEquals(FullPlayerLoopBoundary.START, owner.boundary)
        owner.clearFinishedTail()
        assertEquals(null, owner.boundary)

        owner.pointerDown(FullPlayerLoopBoundary.END)
        assertEquals(FullPlayerLoopBoundary.END, owner.boundary)
        assertTrue(owner.pressed)
        owner.pointerUpOrCancel() // ACTION_CANCEL
        assertFalse(owner.pressed)
        owner.clearFinishedTail()
        assertEquals(null, owner.boundary)
    }

    private fun formattedClock(valueMs: Long, detailed: Boolean): String {
        val output = CharArray(16)
        val length = formatPlayerSurfaceClock(valueMs, detailed, output)
        return output.concatToString(0, length)
    }

}
