package app.stemwise.ui.player.surface

import androidx.compose.animation.core.CubicBezierEasing
import app.stemwise.ui.HIGH_DENSITY_ARTWORK_SIZE_DP
import app.stemwise.ui.playerToolPanelContentDurationMs
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseSemanticMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceToolMotionPolicyTest {
    @Test
    fun statusBelowTheMotorsFollowsTheirBottomWhenControlHeightChanges() {
        for (targetBottom in listOf(680f, 780f)) {
            val translation = playerToolMotionHostTranslationY(
                role = PlayerToolMotionComposeHostRole.STATUS,
                motionGeneration = 17L,
                hostGeneration = 17L,
                targetBoundsValid = true,
                currentControlTopPx = 392f,
                currentControlBottomPx = 612f,
                targetControlTopPx = 510f,
                targetControlBottomPx = targetBottom
            )
            // Compose lays out the status at the target motor bottom. Native motion must
            // keep its displayed top at the current motor bottom for shrinking and growing stacks.
            assertEquals(612f, targetBottom + translation, 0f)
        }
    }

    @Test
    fun openMotionFollowsControlEnvelopeAndUsesTheIndependentContentClock() {
        assertEquals(
            -118f,
            playerToolMotionHostTranslationY(
                role = PlayerToolMotionComposeHostRole.STATUS,
                motionGeneration = 17L,
                hostGeneration = 17L,
                targetBoundsValid = true,
                currentControlTopPx = 392f,
                currentControlBottomPx = 612f,
                targetControlTopPx = 510f,
                targetControlBottomPx = 730f
            ),
            0f
        )
        assertEquals(
            -118f,
            playerToolMotionHostTranslationY(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                motionGeneration = 17L,
                hostGeneration = 17L,
                targetBoundsValid = true,
                currentControlTopPx = 392f,
                currentControlBottomPx = 612f,
                targetControlTopPx = 510f,
                targetControlBottomPx = 730f
            ),
            0f
        )
        assertEquals(
            0.35f,
            detailAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                selectedTool = PlayerSurfaceTool.TUNE,
                hostedTool = PlayerSurfaceTool.TUNE,
                transition = PlayerSurfacePanelTransition.OPEN,
                fraction = .35f
            ),
            0f
        )
        assertEquals(
            .987f,
            detailScale(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                selectedTool = PlayerSurfaceTool.TUNE,
                hostedTool = PlayerSurfaceTool.TUNE,
                transition = PlayerSurfacePanelTransition.OPEN,
                fraction = .35f
            ),
            .0001f
        )
    }

    @Test
    fun closeMotionKeepsAndFadesTheOutgoingDetail() {
        assertEquals(
            0.65f,
            detailAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_OUTGOING,
                selectedTool = PlayerSurfaceTool.NONE,
                hostedTool = PlayerSurfaceTool.LOOP,
                transition = PlayerSurfacePanelTransition.CLOSE,
                fraction = .35f
            ),
            0f
        )
    }

    @Test
    fun unresolvedTargetBoundsKeepOutgoingDetailUntilIncomingGeometryIsReady() {
        assertEquals(
            1f,
            playerToolMotionHostAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_OUTGOING,
                targetBoundsValid = false,
                contentAlpha = 1f
            ),
            0f
        )
        assertEquals(
            .65f,
            playerToolMotionHostAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_OUTGOING,
                targetBoundsValid = true,
                contentAlpha = .65f
            ),
            0f
        )
        assertEquals(
            1f,
            playerToolMotionHostAlpha(
                role = PlayerToolMotionComposeHostRole.STATUS,
                targetBoundsValid = false,
                contentAlpha = 1f
            ),
            0f
        )
    }

    @Test
    fun directReflowNeverProducesAnEmptyDetailFrame() {
        listOf(0f, .35f, .5f, 1f).forEach { fraction ->
            val incoming = detailAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                selectedTool = PlayerSurfaceTool.LYRICS,
                hostedTool = PlayerSurfaceTool.LYRICS,
                transition = PlayerSurfacePanelTransition.REFLOW,
                fraction = fraction
            )
            val outgoing = detailAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_OUTGOING,
                selectedTool = PlayerSurfaceTool.LYRICS,
                hostedTool = PlayerSurfaceTool.LOOP,
                transition = PlayerSurfacePanelTransition.REFLOW,
                fraction = fraction
            )
            assertEquals(1f, incoming + outgoing, .0001f)
            assertEquals(1f, detailScale(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                selectedTool = PlayerSurfaceTool.LYRICS,
                hostedTool = PlayerSurfaceTool.LYRICS,
                transition = PlayerSurfacePanelTransition.REFLOW,
                fraction = fraction
            ), 0f)
        }
    }

    @Test
    fun staleOwnerCannotMoveOrRevealEitherNewDetailLayer() {
        assertEquals(
            0f,
            playerToolMotionHostTranslationY(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                motionGeneration = 16L,
                hostGeneration = 17L,
                targetBoundsValid = true,
                currentControlTopPx = 392f,
                currentControlBottomPx = 612f,
                targetControlTopPx = 510f,
                targetControlBottomPx = 730f
            ),
            0f
        )
        assertEquals(
            0f,
            playerToolMotionDetailAlpha(
                role = PlayerToolMotionComposeHostRole.DETAIL_INCOMING,
                motionGeneration = 16L,
                motionSelectionEpoch = 99L,
                hostGeneration = 17L,
                hostSelectionEpoch = 1L,
                selectedTool = PlayerSurfaceTool.LOOP,
                hostedTool = PlayerSurfaceTool.LOOP,
                panelTransition = PlayerSurfacePanelTransition.OPEN,
                panelContentEasedFraction = 1f
            ),
            0f
        )
    }

    @Test
    fun panelContentDurationsFinishBeforeTheCurrentLayoutClock() {
        val token = StemwiseSemanticMotion.token(StemwiseMotionEvent.PLAYER_LAYOUT_PROFILE_CHANGE)
        val layoutDuration = token.durationMs.toLong()
        listOf(PlayerSurfacePanelTransition.OPEN, PlayerSurfacePanelTransition.CLOSE,
            PlayerSurfacePanelTransition.REFLOW).forEach { transition ->
            val durationMs = playerToolPanelContentDurationMs(transition, motionEnabled = true).toLong()
            assertTrue("Panel content must settle before its layout", durationMs < layoutDuration)
            assertEquals(
                1f,
                playerSurfacePanelContentProgress(
                    elapsedNanos = durationMs * 1_000_000L,
                    durationNanos = durationMs * 1_000_000L,
                    transition = transition
                ),
                0f
            )
            assertTrue(
                playerSurfaceTimedInteractionProgress(
                    elapsedMs = durationMs,
                    durationMs = token.durationMs,
                    easing = token.easing.curve
                ) < 1f
            )
        }
        assertEquals(
            1f,
            playerSurfacePanelContentProgress(
                elapsedNanos = layoutDuration * 1_000_000L,
                durationNanos = playerToolPanelContentDurationMs(
                    PlayerSurfacePanelTransition.OPEN, motionEnabled = true).toLong() * 1_000_000L,
                transition = PlayerSurfacePanelTransition.OPEN
            ),
            0f
        )
        assertTrue(
            playerSurfacePanelContentProgress(
                elapsedNanos = 60_000_000L,
                durationNanos = 120_000_000L,
                transition = PlayerSurfacePanelTransition.OPEN
            ) in 0f..<1f
        )
        assertEquals(
            1f,
            playerSurfacePanelContentProgress(
                elapsedNanos = 120_000_000L,
                durationNanos = 120_000_000L,
                transition = PlayerSurfacePanelTransition.OPEN
            ),
            0f
        )
    }

    @Test
    fun originalRigidVerticalTranslationRetainsControlLayers() {
        assertTrue(
            eligible(
                sourceMode = PlayerSurfaceSourceMode.ORIGINAL,
                startTop = 510f,
                startBottom = 730f,
                targetTop = 392f,
                targetBottom = 612f
            )
        )
    }

    @Test
    fun controlInputMaterializationKeepsTheRenderedPointerInOneCoordinateSpace() {
        val baseHandleY = 612f
        val retainedTranslationY = 84f
        val rawPointerY = baseHandleY + retainedTranslationY

        assertEquals(
            rawPointerY,
            playerSurfaceMaterializedRigidControlCoordinate(
                baseHandleY,
                retainedTranslationY
            ),
            0f
        )
        assertEquals(
            baseHandleY,
            rawPointerY - retainedTranslationY,
            0f
        )
        assertEquals(
            baseHandleY,
            playerSurfaceMaterializedRigidControlCoordinate(baseHandleY, Float.NaN),
            0f
        )
    }

    @Test
    fun resizingOrHorizontalMotionUsesGeneralGeometryPath() {
        assertFalse(
            eligible(
                sourceMode = PlayerSurfaceSourceMode.ORIGINAL,
                startTop = 510f,
                startBottom = 730f,
                targetTop = 392f,
                targetBottom = 640f
            )
        )
        assertFalse(
            playerSurfaceRigidOriginalToolMotionEligible(
                sourceMode = PlayerSurfaceSourceMode.ORIGINAL,
                controlsValid = true,
                controlGestureActive = false,
                startLeft = 20f,
                startTop = 510f,
                startRight = 1060f,
                startBottom = 730f,
                targetLeft = 22f,
                targetTop = 392f,
                targetRight = 1062f,
                targetBottom = 612f,
                startMixerCardHeightPx = 0f,
                targetMixerCardHeightPx = 0f,
                startMixerSpacingPx = 0f,
                targetMixerSpacingPx = 0f
            )
        )
    }

    @Test
    fun stemsOrLiveControlGestureCannotUseRigidOriginalPath() {
        assertFalse(
            eligible(
                sourceMode = PlayerSurfaceSourceMode.STEMS,
                startTop = 510f,
                startBottom = 730f,
                targetTop = 392f,
                targetBottom = 612f
            )
        )
        assertFalse(
            eligible(
                sourceMode = PlayerSurfaceSourceMode.ORIGINAL,
                controlGestureActive = true,
                startTop = 510f,
                startBottom = 730f,
                targetTop = 392f,
                targetBottom = 612f
            )
        )
    }

    @Test
    fun originalVisualProfileFollowsArtworkSizeContinuously() {
        val noPanel = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            276f
        )
        val lowDensity = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            252f
        )
        val highDensity = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            HIGH_DENSITY_ARTWORK_SIZE_DP
        )
        val midpoint = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            264f
        )
        val widthFilled = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            372f
        )

        assertEquals(70f, noPanel.artworkTopDp, .001f)
        assertEquals(384f, noPanel.titleBaselineDp, .001f)
        assertEquals(64f, lowDensity.artworkTopDp, .001f)
        assertEquals(353f, lowDensity.titleBaselineDp, .001f)
        assertEquals(70f, highDensity.artworkTopDp, .001f)
        assertEquals(326f, highDensity.titleBaselineDp, .001f)
        assertEquals(67f, midpoint.artworkTopDp, .001f)
        assertEquals(368.5f, midpoint.titleBaselineDp, .001f)
        assertEquals(396.5f, midpoint.artistBaselineDp, .001f)
        assertEquals(27f, midpoint.titleSizeSp, .001f)
        assertEquals(17.5f, midpoint.artistSizeSp, .001f)
        assertEquals(480f, widthFilled.titleBaselineDp, .001f)
        assertEquals(507f, widthFilled.artistBaselineDp, .001f)
    }

    @Test
    fun unrelatedFullPublicationDoesNotInvalidatePagerChildren() {
        assertFalse(
            playerSurfacePagerPublicationChanged(
                initialBind = false,
                ownerChanged = false,
                queueChanged = false,
                layoutChanged = false,
                sourceChanged = false,
                catalogContentChanged = false,
                visualIndexChanged = false
            )
        )
        listOf(0, 1, 2, 3, 4, 5, 6).forEach { changedIndex ->
            val changes = BooleanArray(7)
            changes[changedIndex] = true
            assertTrue(
                playerSurfacePagerPublicationChanged(
                    initialBind = changes[0],
                    ownerChanged = changes[1],
                    queueChanged = changes[2],
                    layoutChanged = changes[3],
                    sourceChanged = changes[4],
                    catalogContentChanged = changes[5],
                    visualIndexChanged = changes[6]
                )
            )
        }
    }

    private fun detailAlpha(
        role: PlayerToolMotionComposeHostRole,
        selectedTool: PlayerSurfaceTool,
        hostedTool: PlayerSurfaceTool,
        transition: PlayerSurfacePanelTransition,
        fraction: Float
    ): Float = playerToolMotionDetailAlpha(
        role = role,
        motionGeneration = 17L,
        motionSelectionEpoch = 9L,
        hostGeneration = 17L,
        hostSelectionEpoch = 9L,
        selectedTool = selectedTool,
        hostedTool = hostedTool,
        panelTransition = transition,
        panelContentEasedFraction = fraction
    )

    private fun detailScale(
        role: PlayerToolMotionComposeHostRole,
        selectedTool: PlayerSurfaceTool,
        hostedTool: PlayerSurfaceTool,
        transition: PlayerSurfacePanelTransition,
        fraction: Float
    ): Float = playerToolMotionDetailScale(
        role = role,
        motionGeneration = 17L,
        motionSelectionEpoch = 9L,
        hostGeneration = 17L,
        hostSelectionEpoch = 9L,
        selectedTool = selectedTool,
        hostedTool = hostedTool,
        panelTransition = transition,
        panelContentEasedFraction = fraction,
        spatialMotionEnabled = true
    )

    private fun eligible(
        sourceMode: PlayerSurfaceSourceMode,
        controlGestureActive: Boolean = false,
        startTop: Float,
        startBottom: Float,
        targetTop: Float,
        targetBottom: Float
    ): Boolean = playerSurfaceRigidOriginalToolMotionEligible(
        sourceMode = sourceMode,
        controlsValid = true,
        controlGestureActive = controlGestureActive,
        startLeft = 20f,
        startTop = startTop,
        startRight = 1060f,
        startBottom = startBottom,
        targetLeft = 20f,
        targetTop = targetTop,
        targetRight = 1060f,
        targetBottom = targetBottom,
        startMixerCardHeightPx = 0f,
        targetMixerCardHeightPx = 0f,
        startMixerSpacingPx = 0f,
        targetMixerSpacingPx = 0f
    )

}
