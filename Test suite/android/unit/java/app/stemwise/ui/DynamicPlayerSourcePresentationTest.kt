package app.stemwise.ui

import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.SourceSwitchState
import app.stemwise.ui.player.nativeplayer.FullPlayerMixerLaneVisual
import app.stemwise.ui.player.nativeplayer.FullPlayerSourceMixerPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerStemRole
import app.stemwise.ui.player.nativeplayer.FullPlayerTestFixtures
import app.stemwise.ui.player.surface.PlayerSurfaceMixerPacket
import app.stemwise.ui.player.surface.PlayerSurfaceSourceMode
import app.stemwise.ui.player.surface.PLAYER_SURFACE_MAX_MIXER_LANES
import app.stemwise.ui.player.surface.playerSurfaceMixerChromeStateMask
import app.stemwise.ui.player.surface.playerSurfaceMixerMaximumScrollPx
import app.stemwise.ui.player.surface.playerSurfaceMixerVisibleLaneCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicPlayerSourcePresentationTest {
    @Test
    fun onlyAnAlreadySelectedCompleteMixerOpensPartActions() {
        assertTrue(sourcePartsActionsRequested(PlaybackSourceMode.STEMS, true, SourceSwitchState.Stems("set")))
        assertFalse(sourcePartsActionsRequested(PlaybackSourceMode.STEMS, true, SourceSwitchState.Original))
        assertFalse(sourcePartsActionsRequested(PlaybackSourceMode.STEMS, true,
            SourceSwitchState.PreparingStems("set")))
        assertFalse(sourcePartsActionsRequested(PlaybackSourceMode.STEMS, false, SourceSwitchState.Stems("set")))
        assertFalse(sourcePartsActionsRequested(PlaybackSourceMode.ORIGINAL, true, SourceSwitchState.Stems("set")))
    }

    @Test
    fun rendererProjectionPreservesEverySupportedLaneAndRejectsTheNextSize() {
        val original = FullPlayerTestFixtures.snapshot(sourceMode = PlayerSurfaceSourceMode.STEMS)
        val count = PLAYER_SURFACE_MAX_MIXER_LANES
        val labels = List(count - 1) { "Selected part ${it + 1}" } + "Remaining"
        val mixer = PlayerSurfaceMixerPacket(
            revision = 200L, packageRevision = 200L,
            laneKeys = IntArray(count) { it + 1 }, volumes = FloatArray(count) { 1f },
            laneFlags = IntArray(count)
        )
        val sourceMixer = FullPlayerSourceMixerPacket(
            revision = 200L, source = original.sourceMixer.source, mixer = mixer,
            lanes = labels.mapIndexed { index, label ->
                FullPlayerMixerLaneVisual(index + 1, FullPlayerStemRole.OTHER, label, -1)
            })
        val visuals = original.copy(
            revision = 201L, sourceMixer = sourceMixer
        )
        assertEquals(labels, visuals.sourceMixer.lanes().map { it.label })
        assertEquals(count, sourceMixer.laneCount)
        assertThrows(IllegalArgumentException::class.java) {
            PlayerSurfaceMixerPacket(202L, 202L, IntArray(count + 1) { it },
                FloatArray(count + 1) { 1f }, IntArray(count + 1))
        }
    }

    @Test
    fun sourceListsShareOriginalWorkspaceAndScrollOnlyActualRows() {
        val frame = portraitPlayerToolMotionFrame(
            availableWidthDp = 432f, availableHeightDp = 600f, density = 1f,
            detailHeightDp = 0f, mixerProgress = 1f
        )
        val fiveFrame = portraitPlayerToolMotionFrame(432f, 600f, 1f, 0f, 1f, mixerLaneCount = 5)
        val maximumFrame = portraitPlayerToolMotionFrame(432f, 600f, 1f, 0f, 1f,
            mixerLaneCount = PLAYER_SURFACE_MAX_MIXER_LANES)
        assertEquals("Hidden rows changed the responsive layout", fiveFrame.layout, frame.layout)
        assertEquals("Supported lanes changed the fixed viewport", fiveFrame.layout, maximumFrame.layout)
        val six = portraitPlayerContentHeightDp(frame.layout, true, 0f, mixerLaneCount = 6)
        val maximum = portraitPlayerContentHeightDp(frame.layout, true, 0f,
            mixerLaneCount = PLAYER_SURFACE_MAX_MIXER_LANES)
        val one = portraitPlayerContentHeightDp(frame.layout, true, 0f, mixerLaneCount = 1)
        val original = portraitPlayerToolMotionFrame(432f, 600f, 1f, 0f, 0f)
        assertTrue(frame.layout.mixerCardHeightDp in 48f..64f)
        assertEquals(2f, frame.layout.mixerSpacingDp, .001f)
        assertEquals(six, maximum, .001f)
        assertEquals("Lane count moved the shared player frame", six, one, .001f)
        assertEquals(portraitPlayerContentHeightDp(original.layout, false, 0f), one, .001f)
        val viewport = frame.layout.artworkSizeDp
        val row = frame.layout.mixerCardHeightDp
        val spacing = frame.layout.mixerSpacingDp
        assertEquals(1, playerSurfaceMixerVisibleLaneCount(1, viewport, row, spacing))
        assertEquals(0f, playerSurfaceMixerMaximumScrollPx(1, row, spacing, viewport), .001f)
        assertTrue(playerSurfaceMixerMaximumScrollPx(PLAYER_SURFACE_MAX_MIXER_LANES,
            row, spacing, viewport) > 0f)
        val sixControls = portraitPlayerControlStackHeightPx(frame, 1f, true, mixerLaneCount = 6)
        val maximumControls = portraitPlayerControlStackHeightPx(frame, 1f, true,
            mixerLaneCount = PLAYER_SURFACE_MAX_MIXER_LANES)
        assertEquals(0, maximumControls - sixControls)
        assertEquals(portraitPlayerControlStackHeightPx(original, 1f, false), sixControls)
    }

    @Test
    fun highestLaneMuteAndSoloNeverAliasTheFirstLane() {
        val count = PLAYER_SURFACE_MAX_MIXER_LANES
        val muted = BooleanArray(count)
        val solo = BooleanArray(count)
        val shift = (count - 1) * 2
        muted[count - 1] = true
        assertEquals(1L shl shift, playerSurfaceMixerChromeStateMask(muted, solo, count))
        solo[count - 1] = true
        assertEquals(3L shl shift, playerSurfaceMixerChromeStateMask(muted, solo, count))
        muted[0] = true
        solo[0] = true
        assertEquals(3L or (3L shl shift), playerSurfaceMixerChromeStateMask(muted, solo, count))
        assertEquals(3L, playerSurfaceMixerChromeStateMask(muted, solo, 6))
    }
}
