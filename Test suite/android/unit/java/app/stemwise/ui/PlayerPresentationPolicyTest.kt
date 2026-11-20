package app.stemwise.ui

import app.stemwise.model.LyricTrack
import app.stemwise.model.TimedLyricLine
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.PlaybackQueueController
import app.stemwise.playback.SourceSwitchState
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.StemMixState
import app.stemwise.playback.METRONOME_BEAT_UNITS
import app.stemwise.playback.normalizedMetronomeBeatUnit
import app.stemwise.playback.normalizedMetronomeSubdivision
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.player.surface.FullPlayerLoopBoundary
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class PlayerPresentationPolicyTest {
    @Test fun everyVisibleWorkspaceFitsBelowHeaderInputAndAboveTheIdentityClockClearance() {
        for (source in PlaybackSourceMode.entries) for (scale in listOf(.8f, 1f, 1.3f, 2f)) {
            for (tool in listOf(null, PLAYER_DETAIL_LOOP, PLAYER_DETAIL_METRONOME)) {
                val detail = portraitPlayerDetailViewportHeightDp(490f, 978f, source, tool,
                    loopClearanceProgress = 1f, fontScale = scale)
                val frame = portraitPlayerToolMotionFrame(490f, 978f, 2.9375f, detail,
                    if (source == PlaybackSourceMode.STEMS) 1f else 0f,
                    loopClearanceProgress = 1f, fontScale = scale)
                val layout = frame.layout
                if (layout.artworkSizeDp <= .01f) continue
                val minimumBand = 60f + 2f + layout.artworkSizeDp + 10f + layout.identityHeightDp + 16f
                assertTrue("$source $tool scale=$scale cannot fit its actual native content band: $layout",
                    portraitPlayerControlTopDp(layout, source == PlaybackSourceMode.STEMS) >= minimumBand - .001f)
            }
        }
    }

    @Test fun measuredNineLaneMetronomeAndLoopFitTheActualSurfaceWithoutAnExternalFooter() {
        val density = 2.9375f
        val viewportWidthPx = 1440
        val viewportHeightPx = 2874
        val width = viewportWidthPx / density
        val height = viewportHeightPx / density
        for (fontScale in listOf(.8f, 1f, 1.3f)) {
            val detail = portraitPlayerDetailViewportHeightDp(width, height, PlaybackSourceMode.STEMS,
                PLAYER_DETAIL_METRONOME, loopClearanceProgress = 1f, fontScale = fontScale, mixerLaneCount = 9)
            assertEquals("Metronome retains its bounded viewport at scale $fontScale",
                playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME), detail, 0f)
            val frame = portraitPlayerToolMotionFrame(width, height, density, detail, 1f,
                loopClearanceProgress = 1f, fontScale = fontScale, mixerLaneCount = 9)
            val actualHeightPx = kotlin.math.ceil(playerSurfaceContentHeightDp(
                frame, density, true, 0f, 1f) * density).toInt()
            assertTrue("Scale $fontScale would install an overlapping footer: $actualHeightPx > $viewportHeightPx; ${frame.layout}",
                actualHeightPx <= viewportHeightPx)
            assertFalse(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_METRONOME, fontScale, detail))
            assertTrue(frame.layout.mixerCardHeightDp >= 48f)
            assertEquals(68f, frame.layout.transportPlaySizeDp, 0f)
        }
    }

    @Test fun shortUnavailableMetronomeRetainsEveryControlAndFitsItsCompactFallbackWithoutScroll() {
        for (source in PlaybackSourceMode.entries) for (fontScale in listOf(1f, 2f)) for (height in listOf(287f, 304f, 336f, 360f)) {
            val detail = portraitPlayerDetailViewportHeightDp(360f, height, source, PLAYER_DETAIL_METRONOME,
                fontScale = fontScale, naturalDetailHeightDp = 76f, minimumDetailHeightDp = 48f)
            val frame = portraitPlayerToolMotionFrame(360f, height, 1f, detail,
                if (source == PlaybackSourceMode.STEMS) 1f else 0f, fontScale = fontScale)
            val layout = frame.layout
            if (height >= 304f) assertEquals(76f, detail, .001f)
            else assertEquals(61f, detail, .001f)
            assertEquals(0f, layout.artworkSizeDp, .001f)
            assertEquals(0f, layout.identityHeightDp, .001f)
            assertEquals(48f, layout.toolRowHeightDp, .001f)
            assertEquals(68f, layout.transportPlaySizeDp, .001f)
            assertTrue(layout.seekerTrackHeightDp >= 48f)
            val bottom = portraitPlayerControlTopDp(layout, source == PlaybackSourceMode.STEMS) +
                portraitPlayerControlStackHeightPx(frame, 1f, source == PlaybackSourceMode.STEMS) +
                portraitPlayerToolDetailGapDp(layout) + detail
            assertTrue("$source at fontScale $fontScale clips its fallback at $bottom", bottom <= height)
            assertTrue("$source must not mount an external footer at fontScale $fontScale",
                playerSurfaceContentHeightDp(frame, 1f, source == PlaybackSourceMode.STEMS, 0f, 0f) <= height)
        }
    }

    @Test fun shortWindowsKeepTheCompleteControlAndToolEnvelopeReachableByScrolling() {
        for ((width, height) in listOf(800f to 360f, 640f to 480f)) {
            for (source in PlaybackSourceMode.entries) for (fontScale in listOf(1f, 2f)) {
                for (tool in listOf(null, PLAYER_DETAIL_LOOP, PLAYER_DETAIL_TUNE, PLAYER_DETAIL_METRONOME)) {
                    val detailHeight = portraitPlayerDetailViewportHeightDp(width, height, source, tool,
                        fontScale = fontScale, mixerLaneCount = 9)
                    val frame = portraitPlayerToolMotionFrame(width, height, 1f, detailHeight,
                        if (source == PlaybackSourceMode.STEMS) 1f else 0f,
                        fontScale = fontScale, mixerLaneCount = 9)
                    val mixer = source == PlaybackSourceMode.STEMS
                    val extent = maxOf(height, playerSurfaceContentHeightDp(frame, 1f, mixer, 0f, 0f))
                    val controlBottom = portraitPlayerControlTopDp(frame.layout, mixer) +
                        portraitPlayerControlStackHeightPx(frame, 1f, mixer)
                    assertTrue("$width x $height $source $tool scale $fontScale", controlBottom <= extent)
                    assertTrue(controlBottom + detailHeight <= extent)
                    if (tool != null) {
                        val minimumViewport = minOf(playerDetailReservedHeightDp(tool), 120f)
                        assertTrue("Open tool lost its usable viewport", detailHeight >= minimumViewport)
                    }
                    if (!mixer) assertTrue("Landscape artwork did not compact", frame.layout.artworkSizeDp <= 220f)
                    assertTrue(frame.layout.transportPlaySizeDp >= 68f)
                    if (mixer) {
                        if (frame.layout.artworkSizeDp == 0f) assertEquals(0f, frame.layout.mixerCardHeightDp, .001f)
                        else assertTrue(frame.layout.mixerCardHeightDp >= 48f)
                    }
                }
            }
        }
    }

    @Test fun structuralErrorHeadingPreservesRecoveryWhileDroppingPlaybackClocks() {
        val active = AudioPlaybackState(error = "Output recovering", playWhenReady = true)
        val projected = playerStructuralAudioState(active)
        assertEquals(playbackFailureHeading(false), projected.failureHeading)
        val stopped = playerStructuralAudioState(active.copy(playWhenReady = false))
        assertEquals(playbackFailureHeading(true), stopped.failureHeading)
    }

    @Test
    fun seekPreparationRetainsProvenWorkspaceAndNormalPlayerNeverScrolls() {
        assertEquals(
            PlaybackSourceMode.STEMS,
            playerPresentedSourceMode(
                PlaybackSourceMode.ORIGINAL,
                SourceSwitchState.PreparingStems("set"),
                retainedMode = PlaybackSourceMode.STEMS
            )
        )
        assertEquals(
            PlaybackSourceMode.ORIGINAL,
            playerPresentedSourceMode(PlaybackSourceMode.ORIGINAL, SourceSwitchState.PreparingStems("set"))
        )
        assertEquals(
            PlaybackSourceMode.ORIGINAL,
            playerPresentedSourceMode(PlaybackSourceMode.ORIGINAL, SourceSwitchState.Original, PlaybackSourceMode.STEMS)
        )
        assertFalse(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_METRONOME))
        assertFalse(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_LOOP))
        // The Metronome panel measures its own overflow; this outer viewport must not scroll too.
        assertFalse(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_METRONOME, 2f))
        for ((width, height, scale) in listOf(Triple(490f, 990f, .8f), Triple(392f, 876f, 1f))) {
            val viewport = portraitPlayerDetailViewportHeightDp(width, height, PlaybackSourceMode.STEMS,
                PLAYER_DETAIL_METRONOME, loopClearanceProgress = 1f, fontScale = scale)
            if (width == 490f) assertEquals("S23 keeps all four metronome rows", 296f, viewport, .01f)
            else assertTrue("Tighter displays must fit the compact metronome body", viewport >= 202f)
        }
    }

    @Test
    fun ownerDisplayKeepsRelatedPlayerControlsTogetherWhenToolsOpen() {
        val density = 470f / 160f
        val failures = mutableListOf<String>()
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            for (tool in listOf(null, PLAYER_DETAIL_LOOP, PLAYER_DETAIL_TUNE, PLAYER_DETAIL_METRONOME)) {
                val mixer = source == PlaybackSourceMode.STEMS
                val detail = portraitPlayerDetailViewportHeightDp(1440f / density, 990f, source, tool, fontScale = .8f)
                val layout = portraitPlayerToolMotionFrame(
                    1440f / density, 990f, density, detail, if (mixer) 1f else 0f, fontScale = .8f
                ).layout
                if (tool != null && (layout.listSpacingDp > 10f || layout.headerGapDp > 18f || layout.identityGapDp > 10f)) {
                    failures += "$source/$tool inflated authored control gaps: $layout"
                }
                if (mixer) {
                    assertTrue(layout.mixerCardHeightDp >= 48f)
                    assertEquals("Extra parts must stay inside the shared artwork workspace",
                        layout.initialChromeHeightDp(true, 6), layout.initialChromeHeightDp(true, 17), .001f)
                    if (layout.mixerSpacingDp > STEMS_ROW_SPACING_DP) {
                        failures += "$source/$tool has ${layout.mixerSpacingDp} dp blank strips between lanes"
                    }
                }
                assertEquals(68f, layout.transportPlaySizeDp, .001f)
                assertTrue(portraitPlayerContentHeightDp(layout, mixer, detail) <= 990.01f)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun boundedMetronomeViewportDoesNotShrinkWhenPhoneGainsOnePixelOfHeight() {
        val density = 3.5f
        val failures = mutableListOf<String>()
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            var previous = 0f
            for (heightPx in 2900..3100) {
                val detail = portraitPlayerDetailViewportHeightDp(
                    1440f / density, heightPx / density, source, PLAYER_DETAIL_METRONOME,
                    loopClearanceProgress = 1f, fontScale = 2f
                )
                if (detail * density < previous * density - 1f) {
                    failures += "$source Metronome shrank from $previous dp to $detail dp at height=$heightPx px"
                    break
                }
                previous = detail
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun boundedMetronomeViewportUsesSpaceThatFitsItsActualRenderedControlStack() {
        val density = 3.5f
        val failures = mutableListOf<String>()
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            val mixer = source == PlaybackSourceMode.STEMS
            for (heightPx in listOf(2924, 3000, 3066)) {
                val height = heightPx / density
                val detail = portraitPlayerDetailViewportHeightDp(
                    1440f / density, height, source, PLAYER_DETAIL_METRONOME,
                    loopClearanceProgress = 1f, fontScale = 2f
                )
                if (detail + 4f > playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME)) continue
                // Four more dp is larger than every per-child pixel-rounding allowance.
                val larger = portraitPlayerToolMotionFrame(
                    1440f / density, height, density, detail + 4f, if (mixer) 1f else 0f,
                    loopClearanceProgress = 1f, fontScale = 2f
                )
                val bottom = portraitPlayerControlTopDp(larger.layout, mixer) * density +
                    portraitPlayerControlStackHeightPx(larger, density, mixer, 1f) +
                    portraitPlayerToolDetailGapDp(larger.layout) * density + larger.detailHeightPx
                if (bottom <= heightPx + 1f) {
                    failures += "$source Metronome reserved only $detail dp at $heightPx px, but ${larger.detailHeightPx / density} dp fits (bottom=$bottom px)"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun originalLoopAndTuneFitTheirMeasuredControlEnvelope() {
        val density = 3.5f
        for (height in listOf(835.43f, 876f)) {
            for (tool in listOf(PLAYER_DETAIL_TUNE, PLAYER_DETAIL_METRONOME)) {
                val detail = portraitPlayerDetailViewportHeightDp(
                    411.43f, height, PlaybackSourceMode.ORIGINAL, tool,
                    loopClearanceProgress = 1f
                )
                assertEquals("$tool should keep every control visible", playerDetailReservedHeightDp(tool), detail, .01f)
                val frame = portraitPlayerToolMotionFrame(
                    411.43f, height, density, detail, 0f, loopClearanceProgress = 1f
                )
                val bottom = portraitPlayerControlTopDp(frame.layout, false) * density +
                    portraitPlayerControlStackHeightPx(frame, density, false, 1f) +
                    portraitPlayerToolDetailGapDp(frame.layout) * density + frame.detailHeightPx
                assertTrue("$tool bottom ${bottom / density} exceeds $height; layout=${frame.layout}",
                    bottom <= height * density + 2f)
                val visual = app.stemwise.ui.player.surface.playerSurfaceExpandedVisualProfile(
                    app.stemwise.ui.player.surface.PlayerSurfaceSourceMode.ORIGINAL, frame.layout.artworkSizeDp
                )
                assertTrue("Compact artwork must retain the artist above the clock",
                    visual.artistBaselineDp + visual.artistSizeSp * .3f <
                        portraitPlayerControlTopDp(frame.layout, false))
            }
        }
    }

    @Test
    fun unavailableToolIsWithdrawnDuringASongTransition() {
        assertEquals(
            PlayerSurfaceTool.NONE,
            availablePlayerSurfaceTool(
                selected = PlayerSurfaceTool.LYRICS,
                lyricsAvailable = false,
                loopAvailable = true
            )
        )
        assertEquals(
            PlayerSurfaceTool.LYRICS,
            availablePlayerSurfaceTool(
                selected = PlayerSurfaceTool.LYRICS,
                lyricsAvailable = true,
                loopAvailable = true
            )
        )
    }

    @Test
    fun loopBoundaryTransactionValidatesTheSameExactRangeAtEveryStage() {
        assertTrue(
            loopBoundaryGestureRangeIsValid(
                FullPlayerLoopBoundary.START,
                startMs = 1_000L,
                endMs = -1L,
                durationMs = 60_000L
            )
        )
        listOf(FullPlayerLoopBoundary.START, FullPlayerLoopBoundary.END).forEach { boundary ->
            assertTrue(
                loopBoundaryGestureRangeIsValid(
                    boundary,
                    startMs = 1_000L,
                    endMs = 20_000L,
                    durationMs = 60_000L
                )
            )
        }
        assertFalse(
            loopBoundaryGestureRangeIsValid(
                FullPlayerLoopBoundary.END,
                startMs = 1_000L,
                endMs = -1L,
                durationMs = 60_000L
            )
        )
        assertFalse(
            loopBoundaryGestureRangeIsValid(
                FullPlayerLoopBoundary.START,
                startMs = 59_950L,
                endMs = -1L,
                durationMs = 60_000L
            )
        )
        assertFalse(
            loopBoundaryGestureRangeIsValid(
                FullPlayerLoopBoundary.END,
                startMs = 1_000L,
                endMs = 60_001L,
                durationMs = 60_000L
            )
        )
    }

    @Test
    fun movingToolDetailsExposeSemanticsOnlyAtThePhysicalEndpoint() {
        assertFalse(playerToolDetailSemanticsReady("Tune", selectionEpoch = 9L, settledEpoch = 8L))
        assertTrue(playerToolDetailSemanticsReady("Tune", selectionEpoch = 9L, settledEpoch = 9L))
        assertFalse(playerToolDetailSemanticsReady(null, selectionEpoch = 10L, settledEpoch = 10L))
    }

    @Test
    fun toolMotionFrameUsesOneRoundedHeightForOriginalAndStemsPolicy() {
        val widthDp = 412f
        val heightDp = 900f
        val density = 2.625f
        val targetHeight = playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME)

        listOf(0f, .25f, .5f, .75f, 1f).forEach { fraction ->
            val requestedHeight = targetHeight * fraction
            val expectedHeightPx = (requestedHeight * density).roundToInt()
            val renderedHeightDp = expectedHeightPx / density
            listOf(0f, .25f, .5f, .75f, 1f).forEach { mixerProgress ->
                val frame = portraitPlayerToolMotionFrame(
                    availableWidthDp = widthDp,
                    availableHeightDp = heightDp,
                    density = density,
                    detailHeightDp = requestedHeight,
                    mixerProgress = mixerProgress
                )
                val expected = interpolatePortraitPlayerLayout(
                    original = portraitPlayerLayout(
                        widthDp,
                        heightDp,
                        PlaybackSourceMode.ORIGINAL,
                        panelExpanded = renderedHeightDp > .1f,
                        reservedDetailHeightDp = renderedHeightDp
                    ),
                    stems = portraitPlayerLayout(
                        widthDp,
                        heightDp,
                        PlaybackSourceMode.STEMS,
                        panelExpanded = renderedHeightDp > .1f,
                        reservedDetailHeightDp = renderedHeightDp
                    ),
                    progress = mixerProgress
                )

                assertEquals(expectedHeightPx, frame.detailHeightPx)
                assertEquals(expected, frame.layout)
            }
        }
    }

    @Test
    fun toolMotionControlStackCompactsSixCompleteStemsRowsToSafeTargets() {
        val density = 3f
        val stemsFrame = PortraitPlayerToolMotionFrame(
            detailHeightPx = 0,
            layout = portraitPlayerLayout(
                availableWidthDp = 360f,
                availableHeightDp = 592f,
                sourceMode = PlaybackSourceMode.STEMS,
                panelExpanded = true,
                reservedDetailHeightDp = 500f
            )
        )
        assertTrue(stemsFrame.layout.mixerCardHeightDp in 48f..64f)
        assertEquals(2f, stemsFrame.layout.mixerSpacingDp, .001f)

        assertEquals(
            ((stemsFrame.layout.initialChromeHeightDp(false) - portraitPlayerControlTopDp(stemsFrame.layout, false) - stemsFrame.layout.listBottomPaddingDp) * density).roundToInt(),
            portraitPlayerControlStackHeightPx(stemsFrame, density, includeMixer = true)
        )
    }


    @Test
    fun s23ReservesTheMaximumLoopEnvelopeOnceWithoutShrinkingMotors() {
        val stemsLayout = portraitPlayerLayout(
            availableWidthDp = 360f,
            availableHeightDp = 724f,
            sourceMode = PlaybackSourceMode.STEMS,
            panelExpanded = false
        )
        val frame = PortraitPlayerToolMotionFrame(0, stemsLayout)
        val withoutLoop = portraitPlayerControlStackHeightPx(
            frame, 1f, true, loopClearanceProgress = 0f
        )
        val withLoop = portraitPlayerControlStackHeightPx(
            frame, 1f, true, loopClearanceProgress = 1f
        )
        val loopClearance = loopLowerStackClearanceDp(stemsLayout, 1f)
        val expectedClearance = (stemsLayout.seekerTrackHeightDp * .38f).coerceIn(18f, 24f) + 2f + StemwiseSeekerDesign.LOOP_GUIDE_FINE_HEIGHT_DP +
            StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP + stemsLayout.listSpacingDp -
            (stemsLayout.seekerTrackHeightDp + stemsLayout.listSpacingDp)
        assertEquals(expectedClearance, loopClearance, .001f)
        assertEquals(loopClearance.roundToInt(), withLoop - withoutLoop)
        assertEquals(68f, stemsLayout.transportPlaySizeDp, .001f)

    }

    @Test
    fun originalControlReservationTracksNativeLoopClearance() {
        val layout = portraitPlayerLayout(432f, 926f, PlaybackSourceMode.ORIGINAL, false)
        val frame = PortraitPlayerToolMotionFrame(0, layout)
        val normal = portraitPlayerControlStackHeightPx(
            frame, 1f, includeMixer = false, loopClearanceProgress = 0f
        )
        val loopVisible = portraitPlayerControlStackHeightPx(
            frame, 1f, includeMixer = false, loopClearanceProgress = 1f
        )

        assertTrue(loopVisible > normal)
    }

    @Test
    fun s23UsesSixteenDpGuttersAndReservesNoHiddenPanelSurplus() {
        assertEquals(16f, portraitPlayerHorizontalPaddingDp(360f), .001f)
        assertEquals(16f, portraitPlayerHorizontalPaddingDp(391f), .001f)
        assertEquals(20f, portraitPlayerHorizontalPaddingDp(392f), .001f)

        val layout = portraitPlayerLayout(360f, 724f, PlaybackSourceMode.ORIGINAL, false)
        val baseTop = portraitPlayerControlTopDp(layout, includeMixer = false)
        assertEquals(layout.motionCoreHeightDp - 40f, baseTop, .001f)
        assertEquals(12f, PortraitPlayerLayout.SEEKER_CLOCK_HEIGHT_DP, .001f)
        assertEquals(68f, layout.transportPlaySizeDp, .001f)
    }

    @Test
    fun nativePlayerFontScaleUsesSpAndReservesEverySupportedVerticalEnvelope() {
        listOf(1f, 1.3f, 2f).forEach { fontScale ->
            assertEquals(
                12f * fontScale,
                playerNativeTextSizePx(12f, density = 1f, fontScale = fontScale),
                .001f
            )
            val original = portraitPlayerLayout(
                availableWidthDp = 360f,
                availableHeightDp = 724f,
                sourceMode = PlaybackSourceMode.ORIGINAL,
                panelExpanded = false,
                fontScale = fontScale
            )
            val stems = portraitPlayerLayout(
                availableWidthDp = 360f,
                availableHeightDp = 724f,
                sourceMode = PlaybackSourceMode.STEMS,
                panelExpanded = false,
                fontScale = fontScale
            )
            val identityTextEnvelope = (PLAYER_NATIVE_MAX_TITLE_TEXT_SP +
                PLAYER_NATIVE_MAX_ARTIST_TEXT_SP) * 1.2f * fontScale
            val toolTextEnvelope = PLAYER_NATIVE_TOOL_LABEL_TEXT_SP * 1.2f * fontScale

            assertEquals(fontScale, original.fontScale, .001f)
            assertEquals(playerSeekerClockTopClearanceDp(fontScale),
                original.seekerClockTopClearanceDp, .001f)
            assertTrue(original.identityHeightDp >= identityTextEnvelope)
            assertTrue(original.toolRowHeightDp >= 24f + 4f + toolTextEnvelope)
            assertTrue(stems.toolRowHeightDp >= 24f + 4f + toolTextEnvelope)
            assertTrue(
                portraitPlayerContentHeightDp(original, false, 0f) <= 724.01f
            )
            assertTrue(
                portraitPlayerContentHeightDp(stems, true, 0f) <= 724.01f
            )
        }

        assertEquals(20f, playerSeekerClockTopClearanceDp(1f), .001f)
        assertEquals(27.2f, playerSeekerClockTopClearanceDp(1.3f), .001f)
        assertEquals(44f, playerSeekerClockTopClearanceDp(2f), .001f)
    }

    @Test
    fun openOriginalToolPanelUsesAvailableArtworkWidthAndKeepsAuthoredSpacingOnTallScreens() {
        val detailHeight = playerDetailReservedHeightDp(PLAYER_DETAIL_TUNE)
        val layout = portraitPlayerLayout(
            availableWidthDp = 490f,
            availableHeightDp = 1_100f,
            sourceMode = PlaybackSourceMode.ORIGINAL,
            panelExpanded = true,
            reservedDetailHeightDp = detailHeight
        )
        val occupied = portraitPlayerContentHeightDp(layout, false, detailHeight)

        assertTrue("Open tool panel overflowed: $occupied", occupied <= 1_100.01f)
        assertEquals(18f, layout.headerGapDp, .001f)
        assertEquals(10f, layout.identityGapDp, .001f)
        assertEquals(10f, layout.listSpacingDp, .001f)
        assertEquals(450f, layout.artworkSizeDp, .001f)
    }

    @Test
    fun visibleToolHeightSelectsAFittingLayoutForBothSources() {
        listOf(360f to 724f, 412f to 900f, 490f to 1100f).forEach { (width, height) ->
            val target = portraitPlayerDetailViewportHeightDp(width, height, PlaybackSourceMode.STEMS, PLAYER_DETAIL_METRONOME)
            listOf(0f, .25f, .5f, .75f, 1f).forEach { fraction ->
                val reserved = target * fraction
                val original = portraitPlayerLayout(width, height, PlaybackSourceMode.ORIGINAL, reserved > .1f, reserved)
                val stems = portraitPlayerLayout(width, height, PlaybackSourceMode.STEMS, reserved > .1f, reserved)
                assertEquals(original, stems.copy(mixerCardHeightDp = 0f, mixerSpacingDp = 0f))
                assertTrue(portraitPlayerContentHeightDp(original, false, reserved) <= height + .01f)
                assertTrue(stems.mixerCardHeightDp in 48f..64f)
            }
        }
    }

    @Test
    fun existingLoopBRemainsEditableAfterPlaybackWrapsToA() {
        assertFalse(loopPointBEnabled(positionMs = 1_050L, loopA = 1_000L, loopB = null))
        assertTrue(loopPointBEnabled(positionMs = 1_050L, loopA = 1_000L, loopB = 4_000L))
        assertTrue(loopPointBEnabled(positionMs = 1_400L, loopA = 1_000L, loopB = null))
    }

    @Test
    fun stemsUsesOriginalArtworkWorkspaceAndLowerControlsWithoutUnusedBottomSpace() {
        listOf(360f to 592f, 360f to 800f, 412f to 915f, 490f to 1100f).forEach { (width, height) ->
            val original = portraitPlayerLayout(width, height, PlaybackSourceMode.ORIGINAL, false, mixerLaneCount = 9)
            val stems = portraitPlayerLayout(width, height, PlaybackSourceMode.STEMS, false, mixerLaneCount = 9)
            assertEquals(original, stems.copy(mixerCardHeightDp = 0f, mixerSpacingDp = 0f))
            assertTrue(stems.artworkSizeDp >= 96f)
            assertTrue(stems.mixerCardHeightDp in 48f..64f)
            val rows = app.stemwise.ui.player.surface.playerSurfaceMixerVisibleLaneCount(9,
                stems.artworkSizeDp, stems.mixerCardHeightDp, stems.mixerSpacingDp)
            assertTrue(rows in 1..9)
            assertEquals(portraitPlayerControlTopDp(original, false), portraitPlayerControlTopDp(stems, true), .001f)
            val used = stems.initialChromeHeightDp(true, 9)
            assertTrue("$width x $height leaves its lower tools offscreen", used <= height + .01f)
            assertTrue("$width x $height leaves unused bottom space", height - used <= 24.01f)
        }
    }



    @Test
    fun portraitBudgetIncludesEveryNestedGapAndTheFullToolRow() {
        listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS).forEach { source ->
            val includesMixer = source == PlaybackSourceMode.STEMS
            val height = 900f
            val layout = portraitPlayerLayout(412f, height, source, panelExpanded = false)
            val completeTreeHeight = if (includesMixer) {
                portraitPlayerControlTopDp(layout, includeMixer = true) +
                    (layout.initialChromeHeightDp(false) - portraitPlayerControlTopDp(layout, false))
            } else {
                portraitPlayerControlTopDp(layout, includeMixer = false) +
                    layout.seekerTrackHeightDp + layout.seekerClockTopClearanceDp +
                    layout.transportHeightDp + PortraitPlayerLayout.TOOL_ROW_HEIGHT_DP +
                    layout.listSpacingDp + layout.transportToolGapDp +
                    layout.listBottomPaddingDp
            }

            assertEquals(68f, PortraitPlayerLayout.TOOL_ROW_HEIGHT_DP, 0f)
            if (source == PlaybackSourceMode.ORIGINAL) {
                assertEquals(
                    completeTreeHeight,
                    layout.initialChromeHeightDp(includeSixStemMixer = false),
                    .001f
                )
                assertTrue("Original portrait chrome exceeded $height dp", completeTreeHeight <= height)
            } else {
                assertEquals(
                    portraitPlayerControlTopDp(layout, includeMixer = true) +
                        (layout.initialChromeHeightDp(false) - portraitPlayerControlTopDp(layout, false)),
                    layout.initialChromeHeightDp(includeSixStemMixer = true),
                    .001f
                )
                assertTrue(layout.mixerCardHeightDp in 48f..64f)
            }
        }
    }

    @Test
    fun closedPlayersDistributeSpareHeightAndKeepAllControlsReachable() {
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
          listOf(724f, 900f, 1_100f).forEach { height ->
            val layout = portraitPlayerLayout(
                availableWidthDp = 412f,
                availableHeightDp = height,
                sourceMode = source,
                panelExpanded = false
            )
            val used = layout.initialChromeHeightDp(source == PlaybackSourceMode.STEMS)
            assertTrue("$source exceeds $height", used <= height + .01f)
            if (source == PlaybackSourceMode.ORIGINAL) {
                assertTrue("$source leaves unused space at $height", height - used <= 24.01f)
            } else {
                assertTrue(layout.mixerCardHeightDp in 48f..64f)
                assertEquals(used, layout.initialChromeHeightDp(true, 17), .001f)
            }
          }
        }
    }

    @Test
    fun shortPortraitOriginalPreservesMoreArtworkAndPinsCoreControls() {
        val stems = portraitPlayerLayout(360f, 592f, PlaybackSourceMode.STEMS, false)
        val original = portraitPlayerLayout(360f, 592f, PlaybackSourceMode.ORIGINAL, false)

        assertTrue("Short Original keeps recognizable artwork", original.artworkSizeDp >= 96f)
        assertEquals("Primary transport keeps its accepted touch size", 68f, original.transportPlaySizeDp, .001f)
        assertEquals(original.artworkSizeDp, stems.artworkSizeDp, .001f)
        assertTrue(original.initialChromeHeightDp(includeSixStemMixer = false) <= 592f)
    }



    @Test
    fun originalToSixRowStemsAdmissionNeverInterpolatesLaneMotorHeight() {
        val surfaceHeightDp = 1_000f
        val original = portraitPlayerLayout(
            availableWidthDp = 490f,
            availableHeightDp = surfaceHeightDp,
            sourceMode = PlaybackSourceMode.ORIGINAL,
            panelExpanded = false
        )
        val stems = portraitPlayerLayout(
            availableWidthDp = 490f,
            availableHeightDp = surfaceHeightDp,
            sourceMode = PlaybackSourceMode.STEMS,
            panelExpanded = false
        )
        repeat(101) { step ->
            val progress = step / 100f
            val frame = interpolatePortraitPlayerLayout(original, stems, progress)
            assertEquals(if (step == 0) 0f else stems.mixerCardHeightDp,
                frame.mixerCardHeightDp, .001f)
            assertEquals(if (step == 0) 0f else stems.mixerSpacingDp,
                frame.mixerSpacingDp, .001f)
        }
    }


    @Test
    fun bothSourcesRebudgetTheSameWorkspaceWhileEveryDetailRetainsItsFixedViewport() {
        listOf(360f to 800f, 412f to 915f).forEach { (width, height) ->
            listOf(PLAYER_DETAIL_LOOP, PLAYER_DETAIL_TUNE, PLAYER_DETAIL_METRONOME).forEach { detail ->
                val reserved = portraitPlayerDetailViewportHeightDp(width, height, PlaybackSourceMode.STEMS, detail)
                val original = portraitPlayerLayout(width, height, PlaybackSourceMode.ORIGINAL, true, reserved, mixerLaneCount = 9)
                val stems = portraitPlayerLayout(width, height, PlaybackSourceMode.STEMS, true, reserved, mixerLaneCount = 9)
                assertEquals(original, stems.copy(mixerCardHeightDp = 0f, mixerSpacingDp = 0f))
                assertEquals(playerDetailReservedHeightDp(detail), reserved, .001f)
                assertTrue(stems.identityHeightDp >= MIN_PLAYER_IDENTITY_HEIGHT_DP)
                assertTrue(stems.mixerCardHeightDp in 48f..64f)
                assertTrue(portraitPlayerContentHeightDp(stems, true, reserved) <= height + .01f)
            }
        }
    }

    @Test
    fun preparingStemsDisablesOnlyTheDuplicateStemTarget() {
        val preparing = SourceSwitchState.PreparingStems("set-a")

        assertFalse(sourceTargetEnabled(PlaybackSourceMode.STEMS, true, preparing))
        assertTrue(sourceTargetEnabled(PlaybackSourceMode.ORIGINAL, true, preparing))
        assertFalse(
            sourceTargetEnabled(
                PlaybackSourceMode.STEMS,
                true,
                SourceSwitchState.Stems("set-a")
            )
        )
        assertFalse(sourceTargetEnabled(PlaybackSourceMode.ORIGINAL, true, SourceSwitchState.Original))
        assertFalse(sourceTargetEnabled(PlaybackSourceMode.ORIGINAL, true, SourceSwitchState.ReturningOriginal))
    }

    @Test
    fun pronunciationClassificationCannotDuplicateOriginalOrDropOtherCompanions() {
        val tracks = listOf(
            LyricTrack(id = "principal", kind = "original", format = "plain", text = "Hello"),
            LyricTrack(id = "phonetic", kind = "translation", format = "plain", text = "Hola"),
            LyricTrack(id = "translation-2", kind = "translation", format = "plain", text = "Bonjour"),
            LyricTrack(id = "principal-latin", kind = "transliteration", format = "plain", text = "Hello", language = "en-Latn"),
            LyricTrack(id = "pronunciation", kind = "transliteration", format = "plain", text = "həˈləʊ", language = "en-fonipa")
        )
        val projected = canonicalLyricModeTracks(tracks)
        assertEquals(tracks.map { it.id }, projected.map { it.id })
        assertEquals(projected.size, projected.map { it.id }.toSet().size)
    }

    @Test
    fun onlyExactlyAlignedPlainCompanionsInheritOriginalTiming() {
        val original = LyricTrack(
            id = "original",
            kind = "original",
            format = "synced",
            lines = listOf(
                TimedLyricLine(0L, 1_000L, "One"),
                TimedLyricLine(1_000L, 2_000L, "Two")
            )
        )
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            format = "plain",
            text = "wʌn\ntuː",
            basedOnTrackId = "original",
            language = "en-fonipa"
        )
        val unalignedTranslation = LyricTrack(
            id = "translation",
            kind = "translation",
            format = "plain",
            text = "One paragraph",
            basedOnTrackId = "original"
        )

        val tracks = listOf(original, ipa, unalignedTranslation)
        assertEquals(
            listOf("w\u028cn", "tu\u02d0"),
            effectiveTimedLyricsLines(ipa, tracks).map(TimedLyricLine::text)
        )
        assertTrue(effectiveTimedLyricsLines(unalignedTranslation, tracks).isEmpty())
    }

    @Test
    fun lyricTabLabelsStayLocalizedWhileIdentityTagsStayStable() {
        val spanish = LyricTrack(
            id = "translation",
            kind = "translation",
            language = "es",
            format = "plain",
            text = "Hola",
            basedOnTrackId = "original"
        )

        assertEquals("lyrics_track_tab_translation", lyricTrackTabTag(spanish.id))
    }

    @Test
    fun pitchControlHasAUsefulValueWithoutImportedKeyAnalysis() {
        assertEquals("0 st", tuneKeyValue(null, 0))
        assertEquals("+4 st", tuneKeyValue(null, 4))
        assertEquals("-12 st", tuneKeyValue("", -14))
        assertEquals("C major", tuneKeyValue("B♭ major", 2))
    }

    @Test
    fun noDetailPanelOpensItselfWhenTheSongChanges() {
        assertEquals("Key", PLAYER_DETAIL_TUNE)
        assertEquals("Metronome", PLAYER_DETAIL_METRONOME)
        assertEquals(null, resolvedPlayerTool(null))
        assertEquals(null, resolvedPlayerTool("Unknown"))
        assertEquals(PLAYER_DETAIL_TUNE, resolvedPlayerTool(PLAYER_DETAIL_TUNE))
        assertEquals("Loop", resolvedPlayerTool("Loop"))
        assertEquals(null, resolvedPlayerTool("Lyrics"))
    }

    @Test
    fun localDetailButtonsAreExclusiveAndTheSelectedButtonCloses() {
        var detail: String? = null

        detail = toggledPlayerDetail(detail, PLAYER_DETAIL_LOOP)
        assertEquals(PLAYER_DETAIL_LOOP, detail)
        detail = toggledPlayerDetail(detail, PLAYER_DETAIL_METRONOME)
        assertEquals(PLAYER_DETAIL_METRONOME, detail)
        detail = toggledPlayerDetail(detail, PLAYER_DETAIL_METRONOME)
        assertEquals(null, detail)
        detail = toggledPlayerDetail(detail, PLAYER_DETAIL_TUNE)
        assertEquals(PLAYER_DETAIL_TUNE, detail)
        assertEquals(PLAYER_DETAIL_TUNE, toggledPlayerDetail(detail, "Unknown"))
    }

    @Test
    fun loopHandlesWithoutAToolUseTheAvailablePlayerHeight() {
        for (source in PlaybackSourceMode.entries) {
            val frame = portraitPlayerToolMotionFrame(
                availableWidthDp = 490f, availableHeightDp = 950f, density = 1f,
                detailHeightDp = 0f,
                mixerProgress = if (source == PlaybackSourceMode.STEMS) 1f else 0f,
                loopClearanceProgress = 1f
            )
            val clearance = loopLowerStackClearanceDp(frame.layout, 1f)
            val expected = portraitPlayerLayout(
                490f, 950f, source, panelExpanded = false,
                reservedLoopClearanceHeightDp = clearance
            )
            assertEquals("Loop clearance must not select an open-panel layout for $source",
                expected, frame.layout)
        }
    }

    @Test
    fun openStemsFineLoopAndLargestToolFitTheS23ViewportWithoutScroll() {
        val closedLayout = portraitPlayerLayout(
            availableWidthDp = 392f,
            availableHeightDp = 876f,
            sourceMode = PlaybackSourceMode.STEMS,
            panelExpanded = false
        )
        val loopClearance = loopLowerStackClearanceDp(closedLayout, loopProgress = 1f)
        val detailHeight = portraitPlayerDetailViewportHeightDp(
            availableWidthDp = 392f,
            availableHeightDp = 876f,
            sourceMode = PlaybackSourceMode.STEMS,
            detail = PLAYER_DETAIL_METRONOME,
            loopClearanceProgress = 1f
        )
        val frame = portraitPlayerToolMotionFrame(
            availableWidthDp = 392f,
            availableHeightDp = 876f,
            density = 1f,
            detailHeightDp = detailHeight,
            mixerProgress = 1f,
            loopClearanceProgress = 1f
        )
        val layout = frame.layout
        val controlTop = portraitPlayerControlTopDp(layout, includeMixer = true)
        val controlHeight = portraitPlayerControlStackHeightPx(
            frame = frame,
            density = 1f,
            includeMixer = true,
            loopClearanceProgress = 1f
        )
        val detailBottom = controlTop + controlHeight +
            portraitPlayerToolDetailGapDp(layout) + detailHeight

        assertTrue(layout.artworkSizeDp >= 96f)
        assertTrue(layout.mixerCardHeightDp >= STEMS_OPEN_ROW_HEIGHT_DP)
        assertTrue(layout.mixerSpacingDp >= STEMS_ROW_SPACING_DP)
        assertTrue(detailBottom <= 876f)
        assertEquals(portraitPlayerToolDetailGapDp(portraitPlayerLayout(392f, 876f, PlaybackSourceMode.ORIGINAL, true, detailHeight)), portraitPlayerToolDetailGapDp(layout), .001f)
    }



    @Test
    fun toolsKeepTheirBoundedPanelsAndShortLoopPanelsCanScroll() {
        assertEquals(116f, playerDetailReservedHeightDp(PLAYER_DETAIL_LOOP), .001f)
        assertEquals(116f, playerDetailReservedHeightDp(PLAYER_DETAIL_TUNE), .001f)
        assertEquals(296f, playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME), .001f)
        assertTrue(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_LOOP, 1f, 100f))
        assertFalse(playerDetailOwnsBoundedScroll(null))
        listOf(PLAYER_DETAIL_LOOP).forEach { detail ->
            assertFalse(playerDetailOwnsBoundedScroll(detail))
            assertTrue(playerDetailOwnsBoundedScroll(detail, 2f))
        }
        assertFalse(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_METRONOME))
        assertTrue(playerDetailOwnsBoundedScroll(PLAYER_DETAIL_TUNE))
    }

    @Test
    fun s23BoundsStemsToolsLocallyWithoutClippingOrShrinkingSafeMixerRows() {
        listOf(
            PLAYER_DETAIL_LOOP,
            PLAYER_DETAIL_TUNE,
            PLAYER_DETAIL_METRONOME
        ).forEach { detail ->
            val viewport = portraitPlayerDetailViewportHeightDp(
                availableWidthDp = 360f,
                availableHeightDp = 724f,
                sourceMode = PlaybackSourceMode.STEMS,
                detail = detail
            )
            val natural = playerDetailReservedHeightDp(detail)
            assertTrue("S23 Stems $detail viewport must remain useful", viewport > 0f)
            assertTrue("S23 Stems $detail cannot exceed its content", viewport <= natural)
            val layout = portraitPlayerLayout(
                availableWidthDp = 360f,
                availableHeightDp = 724f,
                sourceMode = PlaybackSourceMode.STEMS,
                panelExpanded = true,
                reservedDetailHeightDp = viewport
            )
            assertTrue(
                "S23 Stems $detail keeps a safe motor height",
                layout.mixerCardHeightDp in STEMS_OPEN_ROW_HEIGHT_DP..STEMS_ROW_HEIGHT_DP
            )
            assertTrue(portraitPlayerContentHeightDp(layout, true, viewport) <= 724f + .01f)
            assertEquals(detail == PLAYER_DETAIL_TUNE, playerDetailOwnsBoundedScroll(detail))
        }
    }



    @Test
    fun closedOriginalPlayerUsesItsWidthWithoutLosingNativeIdentity() {
        val layout = portraitPlayerLayout(
            availableWidthDp = 412f,
            availableHeightDp = 900f,
            sourceMode = PlaybackSourceMode.ORIGINAL,
            panelExpanded = false
        )

        assertEquals(372f, layout.artworkSizeDp, .001f)
        assertTrue(layout.identityHeightDp >= MIN_PLAYER_IDENTITY_HEIGHT_DP)
        assertTrue(portraitPlayerContentHeightDp(layout, false, 0f) <= 900f)

        val profile = app.stemwise.ui.player.surface.playerSurfaceExpandedVisualProfile(
            app.stemwise.ui.player.surface.PlayerSurfaceSourceMode.ORIGINAL,
            layout.artworkSizeDp
        )
        assertTrue(profile.titleBaselineDp > profile.artworkTopDp + layout.artworkSizeDp)
        assertTrue(profile.artistBaselineDp > profile.titleBaselineDp)
    }



    @Test
    fun rapidDetailReversalAlwaysTargetsOneBoundedGeometryOwner() {
        val requests = listOf(
            null,
            PLAYER_DETAIL_METRONOME,
            PLAYER_DETAIL_TUNE,
            PLAYER_DETAIL_LOOP,
            null
        )
        val targets = requests.map { detail ->
            portraitPlayerDetailViewportHeightDp(
                360f,
                724f,
                PlaybackSourceMode.STEMS,
                detail
            )
        }
        assertEquals(0f, targets.first(), .001f)
        assertEquals(0f, targets.last(), .001f)
        assertTrue(targets[3] > .1f && targets[3] < 176f)
        assertTrue(targets.all { it in 0f..292f })
    }

    @Test
    fun positionTickerPressureDoesNotChangeTheStructuralPlayerState() {
        val baseline = AudioPlaybackState(
            songId = "song-a",
            playing = true,
            durationMs = 240_000L,
            sourceMode = PlaybackSourceMode.STEMS
        )
        val structuralStates = (0..5_000)
            .map { tick -> playerStructuralAudioState(baseline.copy(positionMs = tick * 16L)) }
            .distinct()

        assertEquals(1, structuralStates.size)
        assertEquals(0L, structuralStates.single().audio.positionMs)
    }

    @Test
    fun realtimeMixerPressureDoesNotChangeTheStructuralPlayerState() {
        val baseline = AudioPlaybackState(
            songId = "song-a",
            playing = true,
            durationMs = 240_000L,
            sourceMode = PlaybackSourceMode.STEMS,
            stemMix = mapOf("vocals" to StemMixState())
        )
        val structuralStates = (0..1_000)
            .map { tick ->
                playerStructuralAudioState(
                    baseline.copy(
                        stemMix = mapOf(
                            "vocals" to StemMixState(volume = tick / 1_000f)
                        )
                    )
                )
            }
            .distinct()

        assertEquals(1, structuralStates.size)
        assertTrue(structuralStates.single().audio.stemMix.isEmpty())
    }

    @Test
    fun transportCallbacksStayBelowTheStructuralPlayerRoot() {
        val baseline = AudioPlaybackState(songId = "song-a", durationMs = 240_000L)
        val variants = listOf(
            baseline,
            baseline.copy(playing = true, playWhenReady = true),
            baseline.copy(ended = true),
            baseline.copy(repeatMode = "All", canGoPrevious = true, canGoNext = true),
            baseline.copy(
                repeatMode = "One",
                shuffleEnabled = true,
                queueOrder = listOf("song-a", "song-c", "song-b")
            )
        )

        assertEquals(1, variants.map(::playerStructuralAudioState).distinct().size)
        assertTrue(playerStructuralAudioState(variants.last()).audio.queueOrder.isEmpty())
        assertNull(
            playerStructuralAudioState(
                variants.last().copy(currentQueueSongId = "song-c")
            ).audio.currentQueueSongId
        )
        assertEquals(
            PlayerTransportUiState(
                playWhenReady = true,
                repeatMode = "One",
                shuffleEnabled = true,
                previousEnabled = true,
                canGoNext = false,
                currentQueueSongId = "song-a"
            ),
            playerTransportUiState(
                variants.last().copy(playWhenReady = true),
                currentQueueSongId = "song-a"
            )
        )
        assertEquals(
            PlayerTransportUiState(
                playWhenReady = false,
                repeatMode = "Off",
                shuffleEnabled = false,
                previousEnabled = true,
                canGoNext = true,
                currentQueueSongId = "song-a"
            ),
            playerTransportUiState(
                baseline.copy(canGoPrevious = true, canGoNext = true),
                currentQueueSongId = "song-a"
            )
        )
        assertEquals(
            "song-c",
            playerTransportUiState(
                baseline.copy(currentQueueSongId = "song-c"),
                currentQueueSongId = "song-a"
            ).currentQueueSongId
        )
    }

    @Test
    fun previousIsAvailableForRestartEvenAtQueueStartOrDuringSelection() {
        val first = AudioPlaybackState(songId = "song-a", canGoPrevious = false)
        assertTrue(playerPreviousOrRestartEnabled(first, "song-a"))
        assertTrue(playerPreviousOrRestartEnabled(first.copy(positionMs = 1L), "song-a"))
        assertTrue(playerPreviousOrRestartEnabled(first, "song-b"))
        assertTrue(playerPreviousOrRestartEnabled(first.copy(songId = null), "song-a"))
        assertFalse(playerPreviousOrRestartEnabled(AudioPlaybackState(), null))
    }

    @Test
    fun queueProjectionPreservesAuthoritativeShuffleOrderWithoutLosingLibrarySongs() {
        assertEquals(
            listOf("current", "song-c", "song-a", "song-b"),
            orderedPlayerSongIds(
                availableSongIds = listOf("song-a", "current", "song-b", "song-c"),
                queueOrder = listOf("current", "song-c", "missing", "song-c", "song-a")
            )
        )
        assertEquals(
            listOf("song-a", "song-b"),
            orderedPlayerSongIds(
                availableSongIds = listOf("song-a", "song-a", "song-b"),
                queueOrder = emptyList()
            )
        )
    }

    @Test
    fun sourceTabsStayWithAudibleOwnerUntilNativeCommitAndRevertOnFailure() {
        assertEquals(
            PlaybackSourceMode.ORIGINAL,
            playerPresentedSourceMode(
                PlaybackSourceMode.ORIGINAL,
                SourceSwitchState.PreparingStems("set-a")
            )
        )
        assertEquals(
            PlaybackSourceMode.STEMS,
            playerPresentedSourceMode(
                PlaybackSourceMode.STEMS,
                SourceSwitchState.ReturningOriginal
            )
        )
        assertEquals(
            PlaybackSourceMode.STEMS,
            playerPresentedSourceMode(
                PlaybackSourceMode.ORIGINAL,
                SourceSwitchState.Failed(
                    retainedSource = PlaybackSourceMode.STEMS,
                    message = "kept the installed graph"
                )
            )
        )
    }

    @Test
    fun speedHighlightUsesTheSameResetTolerance() {
        assertFalse(tuneTempoActive(1f))
        assertFalse(tuneTempoActive(1.00005f))
        assertTrue(tuneTempoActive(.9f))
        assertTrue(tuneTempoActive(1.2f))
    }

    @Test fun loopPreparationNeverDescribesARejectionOrFailureAsPreparing() {
        val state = app.stemwise.playback.LoopPreparationState()
        assertEquals("Preparing loop", loopPreparationDescription(state.copy(phase = app.stemwise.playback.LoopPreparationPhase.PREPARING)))
        assertEquals("Loop ready", loopPreparationDescription(state.copy(phase = app.stemwise.playback.LoopPreparationPhase.READY)))
        assertEquals("Not enough storage", loopPreparationDescription(state.copy(
            phase = app.stemwise.playback.LoopPreparationPhase.REJECTED, reason = "Not enough storage")))
        assertEquals("Could not read loop", loopPreparationDescription(state.copy(
            phase = app.stemwise.playback.LoopPreparationPhase.FAILED, reason = "Could not read loop", retryable = true)))
    }

    @Test
    fun timedLyricSeekCannotEscapeOrDestroyAnActiveLoop() {
        assertEquals(1_000L, loopAwareLyricSeekTarget(500L, 1_000L, 2_000L, 5_000L))
        assertEquals(1_000L, loopAwareLyricSeekTarget(1_000L, 1_000L, 2_000L, 5_000L))
        assertEquals(1_500L, loopAwareLyricSeekTarget(1_500L, 1_000L, 2_000L, 5_000L))
        assertEquals(1_000L, loopAwareLyricSeekTarget(2_000L, 1_000L, 2_000L, 5_000L))
        assertEquals(1_000L, loopAwareLyricSeekTarget(4_000L, 1_000L, 2_000L, 5_000L))
        assertEquals(4_000L, loopAwareLyricSeekTarget(4_000L, null, null, 5_000L))
        assertEquals(5_000L, loopAwareLyricSeekTarget(9_000L, null, null, 5_000L))
        assertEquals(1_500L, loopAwareLyricSeekTarget(1_500L, 2_000L, 1_000L, 5_000L))
    }

    @Test
    fun stemSliderKeepsItsIdentityThroughTheEntireRange() {
        assertEquals(0f, stemSliderActiveFraction(-.2f), .001f)
        assertEquals(.99f, stemSliderActiveFraction(.99f), .001f)
        assertEquals(1f, stemSliderActiveFraction(1.2f), .001f)
        assertEquals(0f, stemSliderFractionFromPointer(-20f, 100f, 10f, false), .001f)
        assertEquals(0f, stemSliderFractionFromPointer(10f, 100f, 10f, false), .001f)
        assertEquals(.5f, stemSliderFractionFromPointer(50f, 100f, 10f, false), .001f)
        assertEquals(1f, stemSliderFractionFromPointer(90f, 100f, 10f, false), .001f)
        assertEquals(1f, stemSliderFractionFromPointer(120f, 100f, 10f, false), .001f)
        assertEquals(1f, stemSliderFractionFromPointer(-20f, 100f, 10f, true), .001f)
        assertEquals(1f, stemSliderFractionFromPointer(10f, 100f, 10f, true), .001f)
        assertEquals(.5f, stemSliderFractionFromPointer(50f, 100f, 10f, true), .001f)
        assertEquals(0f, stemSliderFractionFromPointer(90f, 100f, 10f, true), .001f)
        assertEquals(0f, stemSliderFractionFromPointer(120f, 100f, 10f, true), .001f)

        val visibleTrackWidth = 80f
        listOf(.17f, .41f, .73f).forEach { expected ->
            val leftToRightPointer = 10f + visibleTrackWidth * expected
            val rightToLeftPointer = 10f + visibleTrackWidth * (1f - expected)
            assertEquals(
                expected,
                stemSliderFractionFromPointer(leftToRightPointer, 100f, 10f, false),
                .001f
            )
            assertEquals(
                expected,
                stemSliderFractionFromPointer(rightToLeftPointer, 100f, 10f, true),
                .001f
            )
        }
    }

    @Test
    fun canceledParentPagerSyncKeepsItsGuardUntilTheExactTargetSettles() {
        assertEquals(
            "song-99",
            pagerSyncTargetAfterSettling(
                programmaticTargetId = "song-99",
                settledSongId = "song-17"
            )
        )
        assertEquals(
            "song-99",
            pagerSyncTargetAfterSettling(
                programmaticTargetId = "song-99",
                settledSongId = null
            )
        )
        assertEquals(
            null,
            pagerSyncTargetAfterSettling(
                programmaticTargetId = "song-99",
                settledSongId = "song-99"
            )
        )
    }

    @Test
    fun metronomeSliderReadoutKeepsTheActualBpm() {
        assertEquals("12 BPM", metronomeBpmLabel(12f))
        assertEquals("121 BPM", metronomeBpmLabel(120.6f))
        assertEquals("999 BPM", metronomeBpmLabel(999f))
    }



    @Test
    fun metronomeNotationMapsEverySubdivisionAndBeatUnitUnambiguously() {
        val notation = mapOf(
            1 to "Whole note",
            2 to "Half note",
            4 to "Quarter note",
            8 to "Eighth note",
            16 to "Sixteenth note",
            32 to "Thirty-second note",
            64 to "Sixty-fourth note",
            128 to "One-hundred-twenty-eighth note"
        )

        METRONOME_BEAT_UNITS.forEach { beatUnit ->
            val beatExpectation = notation.getValue(beatUnit)
            assertEquals(beatExpectation, beatUnitName(beatUnit))
            listOf(1, 2, 4).forEach { subdivision ->
                val clickExpectation = notation.getValue(beatUnit * subdivision)
                assertEquals(
                    "$beatUnit beat unit × $subdivision subdivision",
                    clickExpectation,
                    subdivisionNoteName(beatUnit, subdivision)
                )
            }
        }

        METRONOME_BEAT_UNITS.forEach {
            assertEquals(it, normalizedMetronomeBeatUnit(it))
        }
        listOf(1, 2, 4).forEach {
            assertEquals(it, normalizedMetronomeSubdivision(it))
        }
        listOf(-1, 0, 3, 5, 64).forEach {
            assertEquals(4, normalizedMetronomeBeatUnit(it))
            assertEquals(1, normalizedMetronomeSubdivision(it))
        }
    }

    @Test
    fun openToolsRetainTheClosedRoutesIdentityEnvelope() {
        for ((width, height) in listOf(360f to 724f, 490f to 978f)) {
            for (scale in listOf(.8f, 1f, 2f)) for (source in PlaybackSourceMode.entries) {
                val closed = portraitPlayerLayout(width, height, source,
                    panelExpanded = false, fontScale = scale)
                val opened = portraitPlayerLayout(width, height, source,
                    panelExpanded = true, reservedDetailHeightDp = 296f, fontScale = scale)
                assertEquals("$source at $width x $height and font $scale changed its live text envelope",
                    closed.identityHeightDp, opened.identityHeightDp, .001f)
            }
        }
    }

    @Test
    fun roundedToolFramesKeepTheOriginalViewportIdentityEnvelope() {
        for (height in 680..850) for (density in listOf(1f, 1.75f, 2.9375f)) {
            val viewport = height + .25f
            val closed = portraitPlayerLayout(490f, viewport, PlaybackSourceMode.ORIGINAL,
                panelExpanded = false, fontScale = 2f)
            val frame = portraitPlayerToolMotionFrame(490f, viewport, density, 120f, 1f,
                loopClearanceProgress = 1f, fontScale = 2f)
            assertEquals("Pixel rebudget changed the actual closed route at height $viewport density $density",
                closed.identityHeightDp, frame.layout.identityHeightDp, .001f)
        }
    }

    @Test
    fun normalPortraitKeepsOriginalInPlaceAndBoundsUnscaledStemsDetails() {
        listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS).forEach { source ->
            listOf(
                PLAYER_DETAIL_LOOP,
                PLAYER_DETAIL_TUNE,
                PLAYER_DETAIL_METRONOME
            ).forEach { detail ->
                val reserved = portraitPlayerDetailViewportHeightDp(
                    availableWidthDp = 412f,
                    availableHeightDp = 900f,
                    sourceMode = source,
                    detail = detail
                )
                val layout = portraitPlayerLayout(
                    availableWidthDp = 412f,
                    availableHeightDp = 900f,
                    sourceMode = source,
                    panelExpanded = true,
                    reservedDetailHeightDp = reserved
                )

                val includesMixer = source == PlaybackSourceMode.STEMS
                val contentHeight = portraitPlayerContentHeightDp(
                    layout,
                    includesMixer,
                    reserved
                )
                if (includesMixer) {
                    assertTrue(layout.mixerCardHeightDp >= STEMS_OPEN_ROW_HEIGHT_DP)
                    assertTrue(layout.mixerSpacingDp >= STEMS_ROW_SPACING_DP)
                    assertTrue(contentHeight > layout.initialChromeHeightDp(true))
                } else {
                    assertTrue("Original $detail must fit without outer scroll", contentHeight <= 900f)
                }
                // The shared frame retains its existing 24dp breathing envelope. A width-bound
                // square may leave more height; an uncapped workspace must stay within that envelope.
                assertTrue("$source $detail leaves unused workspace height: artwork=${layout.artworkSizeDp}, content=$contentHeight",
                    layout.artworkSizeDp >= 372f - .001f || 900f - contentHeight <= 24.01f)
                assertTrue("$source $detail exceeds the artwork width", layout.artworkSizeDp <= 372f)
                assertTrue("$source $detail must retain all controls", layout.transportPlaySizeDp >= 68f)
                assertTrue("$source $detail must fit without outer scroll", contentHeight <= 900.001f)
            }
        }
    }

    @Test
    fun originalStatusCardsReserveSpaceBeforeToolViewportAllocation() {
        listOf(360f to 724f, 412f to 900f).forEach { (widthDp, heightDp) ->
            listOf(PLAYER_DETAIL_LOOP, PLAYER_DETAIL_TUNE, PLAYER_DETAIL_METRONOME).forEach { detail ->
                val statusHeightDp = 72f
                val reserved = portraitPlayerDetailViewportHeightDp(
                    availableWidthDp = widthDp,
                    availableHeightDp = heightDp,
                    sourceMode = PlaybackSourceMode.ORIGINAL,
                    detail = detail,
                    statusHeightDp = statusHeightDp
                )
                val layout = portraitPlayerLayout(
                    availableWidthDp = widthDp,
                    availableHeightDp = heightDp,
                    sourceMode = PlaybackSourceMode.ORIGINAL,
                    panelExpanded = true,
                    reservedDetailHeightDp = reserved,
                    reservedStatusHeightDp = statusHeightDp
                )

                assertTrue(
                    "$widthDp x $heightDp Original $detail clips beneath its status card",
                    portraitPlayerContentHeightDp(
                        layout = layout,
                        includeSixStemMixer = false,
                        detailHeightDp = reserved,
                        statusHeightDp = statusHeightDp
                    ) <= heightDp + .001f
                )
            }
        }
    }

    @Test
    fun compactVisualOwnerIsAClosedSixtyFourDpCardInsideTheFullHeightSheet() {
        val compact = compactPlayerVisualGeometry(
            availableWidthDp = 432f,
            availableHeightDp = 900f,
            expansionProgress = 0f
        )
        assertEquals(12f, compact.leftDp, .001f)
        assertEquals(2f, compact.topDp, .001f)
        assertEquals(420f, compact.rightDp, .001f)
        assertEquals(66f, compact.bottomDp, .001f)
        assertEquals(408f, compact.widthDp, .001f)
        assertEquals(64f, compact.heightDp, .001f)
        assertEquals(18f, compact.radiusDp, .001f)

        val full = compactPlayerVisualGeometry(
            availableWidthDp = 432f,
            availableHeightDp = 900f,
            expansionProgress = 1f
        )
        assertEquals(0f, full.leftDp, .001f)
        assertEquals(0f, full.topDp, .001f)
        assertEquals(432f, full.rightDp, .001f)
        assertEquals(900f, full.bottomDp, .001f)
        assertEquals(0f, full.radiusDp, .001f)
    }
}
