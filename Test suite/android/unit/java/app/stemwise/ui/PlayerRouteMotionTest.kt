package app.stemwise.ui

import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRouteMotionTest {
    @Test
    fun sheetSpringRetargetsWithItsIncomingVelocityAndReducedMotionSnaps() {
        val motion = playerSheetMotionSpecs(enabled = true, visibilityThreshold = .5f)
        val forward = motion.expand.vectorize(Float.VectorConverter)
        val start = Float.VectorConverter.convertToVector(300f)
        val end = Float.VectorConverter.convertToVector(0f)
        val velocity = Float.VectorConverter.convertToVector(-400f)
        val position = forward.getValueFromNanos(80_000_000L, start, end, velocity)
        val incoming = forward.getVelocityFromNanos(80_000_000L, start, end, velocity)
        val reverse = motion.collapse.vectorize(Float.VectorConverter)
        assertEquals(position.value, reverse.getValueFromNanos(0L, position, start, incoming).value, .01f)
        assertEquals(incoming.value, reverse.getVelocityFromNanos(0L, position, start, incoming).value, .01f)
        assertTrue(reverse.getValueFromNanos(1_000_000L, position, start, incoming).value < position.value)
        val reduced = playerSheetMotionSpecs(enabled = false, visibilityThreshold = .5f).collapse
            .vectorize(Float.VectorConverter)
        assertEquals(300f, reduced.getValueFromNanos(0L, position, start, incoming).value, 0f)
    }

    @Test
    fun travellingArtworkIsTheOnlyChromeVisibleThroughTheCollisionWindow() {
        assertEquals(1f, PlayerMotionPolicy.miniChromeAlpha(0f), 0f)
        assertEquals(0f, PlayerMotionPolicy.miniChromeAlpha(.18f), 0f)
        assertEquals(0f, PlayerMotionPolicy.identityAlpha(.5f), 0f)
        assertTrue(PlayerMotionPolicy.identityAlpha(.53f) > 0f)
        assertEquals(.5f, PlayerMotionPolicy.identityAlpha(.69f), .001f)
        assertEquals(1f, PlayerMotionPolicy.identityAlpha(.86f), 0f)
        assertEquals(0f, PlayerMotionPolicy.headerAlpha(.84f), 0f)
        assertEquals(.5f, PlayerMotionPolicy.headerAlpha(.905f), .001f)
        assertEquals(1f, PlayerMotionPolicy.headerAlpha(.97f), 0f)
        assertEquals(0f, PlayerMotionPolicy.fullChromeAlpha(.54f), 0f)
        assertEquals(.5f, PlayerMotionPolicy.fullChromeAlpha(.72f), .001f)
        assertEquals(1f, PlayerMotionPolicy.fullChromeAlpha(.90f), 0f)
        assertTrue(!PlayerMotionPolicy.destinationChromeInteractive(.95f))
        assertTrue(PlayerMotionPolicy.destinationChromeInteractive(.96f))
        assertEquals(1f, PlayerMotionPolicy.headerAlpha(1f), 0f)
        assertEquals(1f, PlayerMotionPolicy.fullChromeAlpha(1f), 0f)
        assertTrue(PlayerMotionPolicy.destinationChromeInteractive(1f))

        // Compact is the worst header geometry: 60 dp header + 3 dp gap, with artwork starting
        // at 11 dp in mini. At the first non-zero header-alpha checkpoint its top has already
        // cleared the 54 dp source/action band.
        val compactArtworkTopAtHeaderAdmission =
            (60f + 3f) * .84f + 11f * (1f - .84f)
        assertTrue(compactArtworkTopAtHeaderAdmission >= 54f)

        val progress = listOf(0f, .2f, .5f, .54f, .69f, .72f, .9f, .96f, 1f)
        val expanding = progress.map(PlayerMotionPolicy::fullChromeAlpha)
        val collapsing = progress.asReversed().map(PlayerMotionPolicy::fullChromeAlpha)
        // One stateless curve in both directions: interruption/reversal cannot produce a second
        // alpha phase or an endpoint flash.
        assertEquals(expanding, collapsing.asReversed())
    }

    @Test
    fun routeSemanticsWithdrawControlActionsAcrossTheInteractionDeadZone() {
        val mini = playerRouteSemanticsPresentation(.17f)
        assertTrue(mini.compact)
        assertTrue(mini.controlsInteractive)

        listOf(.18f, .5f, .95f).forEach { progress ->
            val transition = playerRouteSemanticsPresentation(progress)
            assertTrue(transition.compact)
            assertTrue(!transition.controlsInteractive)
        }

        val full = playerRouteSemanticsPresentation(.96f)
        assertTrue(!full.compact)
        assertTrue(full.controlsInteractive)
    }

    @Test
    fun destinationIdentityNeverReappearsOverTheTravellingArtwork() {
        val compactArtworkSize = 48f
        val expandedArtworkSize = 300f
        val expandedIdentityGap = 3f
        val visibleCheckpoints = listOf(.53f, .6f, .69f, .78f, .9f, 1f)

        visibleCheckpoints.forEach { progress ->
            val artworkProgress = PlayerMotionPolicy.artworkProgress(progress)
            val artworkSize =
                compactArtworkSize + (expandedArtworkSize - compactArtworkSize) * artworkProgress
            val artworkTop = 11f * (1f - artworkProgress)
            val identityTop = PlayerMotionPolicy.identityTopPx(
                compactTopPx = 10f,
                expandedTopPx = artworkTop + artworkSize + expandedIdentityGap,
                progress = progress
            )
            assertTrue(PlayerMotionPolicy.identityAlpha(progress) > 0f)
            assertTrue(identityTop >= artworkTop + artworkSize)
        }

        assertEquals(10f, PlayerMotionPolicy.identityTopPx(10f, 240f, .06f), 0f)
        assertEquals(240f, PlayerMotionPolicy.identityTopPx(10f, 240f, .5f), 0f)
    }

    @Test
    fun fullStemsMediaFadesWhileTheCompactSharedElementRemainsVisible() {
        assertEquals(1f, PlayerMotionPolicy.artworkAlpha(0f, 1f), 0f)
        assertEquals(1f, PlayerMotionPolicy.artworkAlpha(1f, 0f), 0f)
        assertEquals(.5f, PlayerMotionPolicy.artworkAlpha(1f, .175f), .001f)
        assertEquals(0f, PlayerMotionPolicy.artworkAlpha(1f, .35f), 0f)
        assertEquals(0f, PlayerMotionPolicy.sourceMixerAlpha(.35f), 0f)
        assertEquals(.5f, PlayerMotionPolicy.sourceMixerAlpha(.5f), .001f)
        assertEquals(1f, PlayerMotionPolicy.sourceMixerAlpha(.65f), 0f)
        assertEquals(0f, PlayerMotionPolicy.sourceLayoutProgress(.65f), 0f)
        assertEquals(.5f, PlayerMotionPolicy.sourceLayoutProgress(.825f), .001f)
        assertEquals(1f, PlayerMotionPolicy.sourceLayoutProgress(1f), 0f)
        assertEquals(0f, PlayerMotionPolicy.artworkAlpha(1f, 1f), 0f)

        val progress = listOf(0f, .2f, .5f, .8f, 1f)
        val fadingOut = progress.map { PlayerMotionPolicy.artworkAlpha(1f, it) }
        val fadingIn = progress.asReversed().map { PlayerMotionPolicy.artworkAlpha(1f, it) }
        assertEquals(fadingOut, fadingIn.asReversed())
    }

    @Test
    fun compactIdentityIsFullyGoneBeforeArtworkLeavesMiniGeometry() {
        (0..1_000).forEach { step ->
            val progress = step / 2_000f
            if (PlayerMotionPolicy.identityAlpha(progress) > 0f) {
                assertEquals(0f, PlayerMotionPolicy.artworkProgress(progress), 0f)
            }
        }
        assertEquals(0f, PlayerMotionPolicy.identityAlpha(.022f), 0f)
        assertEquals(0f, PlayerMotionPolicy.artworkProgress(.025f), 0f)
        assertTrue(PlayerMotionPolicy.artworkProgress(.026f) > 0f)
    }

    @Test
    fun bottomNavigationStaysMountedButCannotLeakThroughTheExpandedPlayer() {
        assertEquals(0f, playerNavigationAlpha(true, 1f), 0f)
        assertEquals(0f, playerNavigationAlpha(true, .5f), 0f)
        assertEquals(0f, playerNavigationAlpha(true, .08f), 0f)
        assertEquals(.5f, playerNavigationAlpha(true, .04f), .001f)
        assertEquals(1f, playerNavigationAlpha(true, 0f), 0f)
        assertEquals(1f, playerNavigationAlpha(false, 1f), 0f)

    }

    @Test
    fun bottomNavigationRevealIsLateMonotonicAndExactlyReversible() {
        val expansion = (0..100).map { it / 100f }
        val expanding = expansion.map { playerNavigationAlpha(true, it) }
        val collapsing = expansion.asReversed().map { playerNavigationAlpha(true, it) }

        assertTrue(expanding.zipWithNext().all { (before, after) -> after <= before })
        assertTrue(collapsing.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(expanding, collapsing.asReversed())
        assertTrue(expansion.zip(expanding).all { (route, alpha) ->
            route < PLAYER_NAVIGATION_REVEAL_MAX_EXPANSION || alpha == 0f
        })
    }

    @Test
    fun navigationSurfaceUsesExactR5ThirdsAndVerticalGeometryAtBothWidths() {
        val compact = signalNavigationGeometry(viewportWidthDp = 360f)
        val reference = signalNavigationGeometry(viewportWidthDp = 432f)

        assertEquals(72f, compact.surfaceHeightDp, 0f)
        assertEquals(72f, reference.surfaceHeightDp, 0f)
        assertEquals(72f, compact.itemHeightDp, 0f)
        assertEquals(11f, compact.iconTopDp, 0f)
        assertEquals(52f, compact.labelBaselineDp, 0f)
        assertEquals(listOf(60f, 180f, 300f), compact.itemCentersDp)
        assertEquals(listOf(72f, 216f, 360f), reference.itemCentersDp)
        assertEquals(72f, playerShellLayout(hasActiveSession = false).sheetPeekHeightDp, 0f)
        assertEquals(138f, playerShellLayout(hasActiveSession = true).sheetPeekHeightDp, 0f)
    }

    @Test
    fun compactCardBottomMeetsNavigationWithoutAHostStripAtBothViewports() {
        val shell = playerShellLayout(hasActiveSession = true)
        listOf(
            Triple(960f, 824f, 888f),
            Triple(772f, 636f, 700f)
        ).forEach { (viewportHeight, expectedTop, expectedBottom) ->
            val cardTop = viewportHeight - shell.sheetPeekHeightDp + 2f
            val cardBottom = cardTop + 64f
            val navigationTop = viewportHeight - SIGNAL_NAVIGATION_HEIGHT_DP
            assertEquals(expectedTop, cardTop, 0f)
            assertEquals(expectedBottom, cardBottom, 0f)
            assertEquals(0f, navigationTop - cardBottom, 0f)
        }
    }

    @Test
    fun snackbarOffsetsDoNotAddTheBottomGestureTwice() {
        assertEquals(96f, playerSnackbarBottomOffsetDp(
            playerModal = false,
            hasActiveSession = false,
            bottomGestureInsetDp = 24f
        ), 0f)
        assertEquals(162f, playerSnackbarBottomOffsetDp(
            playerModal = false,
            hasActiveSession = true,
            bottomGestureInsetDp = 24f
        ), 0f)
        assertEquals(24f, playerSnackbarBottomOffsetDp(
            playerModal = true,
            hasActiveSession = true,
            bottomGestureInsetDp = 24f
        ), 0f)
    }

    @Test
    fun navigationInsetIsReservedOnceForPageAndPlayerAtEitherEndpoint() {
        for (active in listOf(false, true)) {
            val zero = playerShellLayout(active)
            val inset = playerShellLayout(active, 24f)
            assertEquals(zero.pageBottomReserveDp + 24f, inset.pageBottomReserveDp, 0f)
            assertEquals(zero.sheetPeekHeightDp + 24f, inset.sheetPeekHeightDp, 0f)
            assertEquals(zero, playerShellLayout(active, -1f))
        }
    }

    @Test
    fun backHandlerOwnsAnExpansionAsSoonAsExpandedIsTheTarget() {
        assertTrue(
            playerModalActive(
                hasActiveSession = true,
                currentExpanded = false,
                targetExpanded = true
            )
        )
        assertTrue(
            !playerModalActive(
                hasActiveSession = true,
                currentExpanded = false,
                targetExpanded = false
            )
        )
        assertTrue(
            !playerModalActive(
                hasActiveSession = false,
                currentExpanded = true,
                targetExpanded = true
            )
        )
    }

    @Test
    fun sheetSwipeGateDependsOnlyOnStableRouteState() {
        assertTrue(playerSheetSwipeEnabled(true, false))
        assertTrue(!playerSheetSwipeEnabled(true, true))
        assertTrue(!playerSheetSwipeEnabled(false, false))
    }

    @Test fun settledMissingPlayerRetiresOnlyAfterAudioAndOpenHandoffsFinish() {
        assertTrue(playerOwnerRemoved("removed", emptySet(), true, null, null))
        assertTrue(!playerOwnerRemoved("removed", emptySet(), false, null, null))
        assertTrue(!playerOwnerRemoved("removed", emptySet(), true, "replacement", null))
        assertTrue(!playerOwnerRemoved("removed", emptySet(), true, null, "replacement"))
        assertTrue(!playerOwnerRemoved("retained", setOf("retained"), true, null, null))
        assertTrue(!playerOwnerRemoved(null, emptySet(), true, null, null))
        assertTrue(!playerModalActive(false, currentExpanded = true, targetExpanded = true))
    }

}
