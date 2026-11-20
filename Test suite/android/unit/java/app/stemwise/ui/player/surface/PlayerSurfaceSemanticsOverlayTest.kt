package app.stemwise.ui.player.surface

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The actual Compose semantics owners are exercised by PlayerSurfaceSemanticsAccessibilityTest
// (compactIdentityOwnsKeyboardActivationAndWithdrawsItWithControls,
// movingLowerControlsWithdrawWithoutHidingStationarySemantics, unavailableNextIsDisabledAndCannotDispatch).
// Exact action/feedback ordering is covered through the real bridge and OwnedPlayerSemanticsSink
// by NativePlayerRenderingInstrumentationTest.unmountedSelectionAndSemanticFeedbackUseTheMountedOwner.
class PlayerSurfaceSemanticsOverlayTest {
    @Test
    fun onlyExpandedOriginalExposesArtworkSemantics() {
        assertTrue(playerSurfaceArtworkSemanticsVisible(
            compact = false,
            sourceMode = PlayerSurfaceSourceMode.ORIGINAL
        ))
        assertFalse(playerSurfaceArtworkSemanticsVisible(
            compact = false,
            sourceMode = PlayerSurfaceSourceMode.STEMS
        ))
        assertFalse(playerSurfaceArtworkSemanticsVisible(
            compact = true,
            sourceMode = PlayerSurfaceSourceMode.STEMS
        ))
        assertFalse(playerSurfaceArtworkSemanticsVisible(
            compact = true,
            sourceMode = PlayerSurfaceSourceMode.ORIGINAL
        ))
    }

    @Test
    fun fixedHeaderShieldsCoveredBodyMotors() {
        assertFalse(playerSurfaceBodyInputVisible(pointerY = 59f, headerHeightPx = 60f))
        assertTrue(playerSurfaceBodyInputVisible(pointerY = 60f, headerHeightPx = 60f))
    }

    @Test
    fun disabledStemsExplainLoadingAndAbsenceInsteadOfLookingBroken() {
        val loading = PlayerSurfaceSourceSemantics.from(
            PlayerSurfaceSourcePacket(
                revision = 1L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                phase = PlayerSurfaceSourcePhase.PREPARING,
                stemsAvailable = true
            )
        )
        assertEquals("Switching audio source", loading.stemsLabel)
        assertFalse(loading.stemsEnabled)

        val absent = PlayerSurfaceSourceSemantics.from(
            PlayerSurfaceSourcePacket(
                revision = 2L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                phase = PlayerSurfaceSourcePhase.SETTLED,
                stemsAvailable = false
            )
        )
        assertEquals("Prepare song parts", absent.stemsLabel)
        assertTrue(absent.stemsEnabled)

        val hydrating = PlayerSurfaceSourceSemantics.from(
            PlayerSurfaceSourcePacket(
                revision = 3L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                phase = PlayerSurfaceSourcePhase.SETTLED,
                // A stale positive availability value must not enable semantics before the
                // availability owner settles the current revision.
                stemsAvailable = true,
                stemsAvailabilitySettled = false
            )
        )
        assertEquals("Loading song parts", hydrating.stemsLabel)
        assertFalse(hydrating.stemsEnabled)
        assertFalse(
            playerSurfaceStemsActionEnabled(
                PlayerSurfaceSourcePacket(
                    revision = 3L,
                    presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                    audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                    phase = PlayerSurfaceSourcePhase.SETTLED,
                    stemsAvailable = true,
                    stemsAvailabilitySettled = false
                )
            )
        )

        val available = PlayerSurfaceSourceSemantics.from(
            PlayerSurfaceSourcePacket(
                revision = 4L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                phase = PlayerSurfaceSourcePhase.SETTLED,
                stemsAvailable = true,
                stemsAvailabilitySettled = true
            )
        )
        assertEquals("Stems ready", available.stemsLabel)
        assertTrue(available.stemsEnabled)
        assertTrue(playerSurfaceStemsActionEnabled(
            PlayerSurfaceSourcePacket(
                revision = 4L,
                presentedMode = PlayerSurfaceSourceMode.ORIGINAL,
                audibleMode = PlayerSurfaceSourceMode.ORIGINAL,
                phase = PlayerSurfaceSourcePhase.SETTLED,
                stemsAvailable = true,
                stemsAvailabilitySettled = true
            )
        ))
    }

    @Test
    fun transientSixRowWorkspaceOverflowWithdrawsWholeGeometryInsteadOfCrashing() {
        val inside = PlayerSurfaceBounds(0f, 0f, 100f, 100f)
        // This reproduces the first bad base region from the device crash: the source packet had
        // switched to six-row Stems while the Original-height control envelope was still current,
        // placing the seeker below the 1,000 px native surface.
        val overflowSeeker = PlayerSurfaceBounds(10f, 1_040f, 990f, 1_104f)
        assertEquals(
            "seeker",
            PlayerSurfaceSemanticsGeometry.firstInvalidRegion(
                widthPx = 1_000,
                heightPx = 1_000,
                baseRegions = listOf(
                    "page" to inside,
                    "seeker" to overflowSeeker,
                    "transport" to PlayerSurfaceBounds(10f, 1_110f, 990f, 1_180f)
                ),
                mixerLanes = emptyList()
            )
        )
        assertNull(
            semanticsGeometry(
                widthPx = 1_000,
                heightPx = 1_000,
                seeker = overflowSeeker
            )
        )
        assertNull(
            semanticsGeometry(
                widthPx = 0,
                heightPx = 0,
                seeker = inside
            )
        )
        assertNotNull(
            semanticsGeometry(
                widthPx = 1_000,
                heightPx = 1_000,
                seeker = PlayerSurfaceBounds(10f, 800f, 990f, 864f)
            )
        )
    }

    @Test
    fun timelineDescriptionPreservesExactSeekerWordingAndOrder() {
        assertEquals(
            "1 minute 2 point 345 seconds of 3 minutes 5 seconds, " +
                "loop A 10 seconds, loop B 20 seconds, section Chorus",
            playerSurfaceTimelineStateDescription(
                PlayerSurfaceTimelineSemantics(
                    durationMs = 185_000L,
                    positionMs = 62_345L,
                    loopStartMs = 10_000L,
                    loopEndMs = 20_000L,
                    activeSectionLabel = "Chorus"
                )
            )
        )
    }

    @Test
    fun playPauseDescriptionFollowsTransportStateWithoutWaitingForTimelineClock() {
        assertEquals("Play", playerSurfacePlayPauseDescription(playWhenReady = false))
        assertEquals("Pause", playerSurfacePlayPauseDescription(playWhenReady = true))
    }

    @Test
    fun mixerProjectionDefensivelyOwnsItsCommittedLaneList() {
        val lanes = mutableListOf(
            PlayerSurfaceMixerLaneSemantics(
                label = "Vocals",
                volume = .72f,
                muted = false,
                solo = true
            )
        )
        val projection = PlayerSurfaceMixerSemantics(lanes)

        lanes.clear()

        assertEquals(1, projection.laneCount)
        assertEquals("Vocals", projection.laneAt(0).label)
        assertEquals(.72f, projection.laneAt(0).volume, 0f)
    }

    @Test
    fun mixerSemanticsDefersWhenNativeGeometryHasNotCaughtTheNewLaneModel() {
        // This is the physical frame-gate failure: Compose published four stem actions while the
        // native View still exposed the preceding zero-lane geometry. The overlay must disappear
        // for that handoff frame and return only after the native package is authoritative.
        assertFalse(
            playerSurfaceSemanticsMixerGeometryIsCompatible(
                modelLaneCount = 4,
                geometryLaneCount = 0
            )
        )
        assertTrue(
            playerSurfaceSemanticsMixerGeometryIsCompatible(
                modelLaneCount = 4,
                geometryLaneCount = 4
            )
        )
    }

    @Test
    fun mixerSemanticsAlsoDefersStaleActionsWhenNativeGeometryDropsLanesFirst() {
        assertFalse(
            playerSurfaceSemanticsMixerGeometryIsCompatible(
                modelLaneCount = 4,
                geometryLaneCount = 0
            )
        )
        assertFalse(
            playerSurfaceSemanticsMixerGeometryIsCompatible(
                modelLaneCount = 0,
                geometryLaneCount = 4
            )
        )
    }

    @Test
    fun delayedGeometryConvergenceContinuesCheaplyAfterFourFrameFastWindow() {
        repeat(4) { completedAttempts ->
            assertEquals(
                PlayerSurfaceSemanticsGeometryRetryMode.NEXT_DISPLAY_FRAME,
                playerSurfaceSemanticsGeometryRetryMode(completedAttempts)
            )
        }
        assertEquals(
            PlayerSurfaceSemanticsGeometryRetryMode.LOW_RATE_DELAY,
            playerSurfaceSemanticsGeometryRetryMode(completedAttempts = 4)
        )
        // A source handoff that takes far longer than four frames still has a recovery mode; there
        // is deliberately no terminal/expired state that can leave accessibility absent forever.
        assertEquals(
            PlayerSurfaceSemanticsGeometryRetryMode.LOW_RATE_DELAY,
            playerSurfaceSemanticsGeometryRetryMode(completedAttempts = 400)
        )
        assertFalse(
            playerSurfaceSemanticsGeometryIsReadyToSample(
                expectedLaneCount = 4,
                nativeLaneCount = null
            )
        )
        assertFalse(
            playerSurfaceSemanticsGeometryIsReadyToSample(
                expectedLaneCount = 4,
                nativeLaneCount = 0
            )
        )
        assertTrue(
            playerSurfaceSemanticsGeometryIsReadyToSample(
                expectedLaneCount = 4,
                nativeLaneCount = 4
            )
        )
    }

    @Test
    fun nativeHeaderAndToolActionsHaveDedicatedComposeAccessibilityBounds() {
        val base = PlayerSurfaceBounds(0f, 0f, 10f, 10f)
        val actions = List(6) { index ->
            val left = 20f + index * 10f
            PlayerSurfaceBounds(left, 20f, left + 8f, 28f)
        }
        val geometry = PlayerSurfaceSemanticsGeometry.createOrNull(
            widthPx = 100,
            heightPx = 100,
            page = base,
            artwork = base,
            identity = base,
            title = base,
            artist = base,
            original = base,
            stems = base,
            seeker = base,

            shuffle = base,
            previous = base,
            playPause = base,
            miniPlayPause = base,
            next = base,
            repeat = base,
            collapse = actions[0],
            info = actions[1],
            loopTool = actions[2],
            tuneTool = actions[3],
            lyricsTool = actions[4],
            metronomeTool = actions[5],
            mixerLanes = emptyList()
        )!!

        assertEquals(20, BASE_SEMANTICS_TARGET_COUNT)
        actions.forEachIndexed { index, expected ->
            assertEquals(expected, geometry.semanticsBoundsAt(14 + index, compact = false))
        }
    }

    @Test
    fun toolAccessibilityStateMatchesTheCommittedNativeToolPacket() {
        val tools = PlayerSurfaceToolSemantics(
            selectedTool = PlayerSurfaceTool.TUNE,
            lyricsAvailable = false,
            metronomeAvailable = false,
            loopAvailable = false
        )

        assertTrue(tools.isSelected(PlayerSurfaceTool.TUNE))
        assertFalse(tools.isSelected(PlayerSurfaceTool.LOOP))
        assertFalse(tools.isEnabled(PlayerSurfaceTool.LOOP))
        assertFalse(tools.isEnabled(PlayerSurfaceTool.LYRICS))
        assertTrue(tools.isEnabled(PlayerSurfaceTool.METRONOME))
    }

    @Test
    fun retiredStemVersionsExposeNoAction() {
        val source = locateSemanticsOverlaySource()
        assertFalse("Retired internal selector returned", "Choose stem version" in source || "onChooseStemVersion" in source)

    }

    @Test
    fun accessibilityProjectionKeepsNativeTouchOwnershipAndNoInvisibleHardwareFocus() {
        val source = locateSemanticsOverlaySource()

        listOf(".clickable(", ".pointerInput(", ".toggleable(", ".selectable(").forEach { owner ->
            assertFalse("Semantics overlay acquired native pointer ownership through $owner", owner in source)
        }
        listOf(".focusable(", ".onFocusChanged(", ".onKeyEvent(", ".onPreviewKeyEvent(").forEach { owner ->
            assertFalse("Transparent projection can create invisible hardware focus through $owner", owner in source)
        }
    }

    private fun semanticsGeometry(
        widthPx: Int,
        heightPx: Int,
        seeker: PlayerSurfaceBounds
    ): PlayerSurfaceSemanticsGeometry? {
        val inside = PlayerSurfaceBounds(0f, 0f, 100f, 100f)
        return PlayerSurfaceSemanticsGeometry.createOrNull(
            widthPx = widthPx,
            heightPx = heightPx,
            page = inside,
            artwork = inside,
            identity = inside,
            title = inside,
            artist = inside,
            original = inside,
            stems = inside,
            seeker = seeker,

            shuffle = inside,
            previous = inside,
            playPause = inside,
            miniPlayPause = inside,
            next = inside,
            repeat = inside,
            collapse = inside,
            info = inside,
            loopTool = inside,
            tuneTool = inside,
            lyricsTool = inside,
            metronomeTool = inside,
            mixerLanes = emptyList()
        )
    }

    private fun locateSemanticsOverlaySource(): String {
        return locateProductionSource(
            "app/stemwise/ui/player/surface/PlayerSurfaceSemanticsOverlay.kt"
        )
    }

    private fun locateProductionSource(relativePath: String): String {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .map { root -> File(root, "app/src/main/java/$relativePath") }
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Could not locate $relativePath")
    }
}
