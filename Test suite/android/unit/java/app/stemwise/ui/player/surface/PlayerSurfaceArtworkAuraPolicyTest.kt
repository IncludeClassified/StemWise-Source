package app.stemwise.ui.player.surface

import app.stemwise.ui.ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_BRIGHT_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_FAR_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_NEAR_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_VISIBILITY_MULTIPLIER
import app.stemwise.ui.artworkAuraOpacity
import app.stemwise.ui.design.StemwiseSemanticMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The actual luminance/opacity draw and immutable raster reuse are exercised by
// ArtworkAuraPreparationInstrumentationTest.retainedAuraDrawSeparatesOpacityFromLuminanceWithoutPreparingMoreTextures.
// Visibility/clock recovery uses the real View in OutgoingArtworkAuraInstrumentationTest
// .pausedDegradedAuraResumesAfterBriefVisibilityLossWithoutFurtherInput and
// NativePlayerRenderingInstrumentationTest.pausedAmbientOwnerSurvivesConsecutiveFramesInsideTheSameCadenceBucket.
class PlayerSurfaceArtworkAuraPolicyTest {
    @Test
    fun `zero media alpha withdraws artwork aura and hit ownership`() {
        assertFalse(playerSurfaceMediaContentVisible(Float.NaN))
        assertFalse(playerSurfaceMediaContentVisible(0f))
        assertFalse(playerSurfaceMediaContentVisible(.001f))
        assertTrue(playerSurfaceMediaContentVisible(.0011f))
        assertTrue(playerSurfaceMediaContentVisible(1f))
    }

    @Test
    fun `expanded pager includes the lower of artwork and identity`() {
        assertEquals(
            186f,
            playerSurfaceExpandedPagerBottomPx(
                artworkTopPx = 74f,
                artworkSizePx = 112f,
                identityTopPx = 84f,
                identityHeightPx = 60f
            ),
            .0001f
        )
        assertEquals(
            262f,
            playerSurfaceExpandedPagerBottomPx(
                artworkTopPx = 80f,
                artworkSizePx = 112f,
                identityTopPx = 202f,
                identityHeightPx = 60f
            ),
            .0001f
        )
    }

    @Test
    fun `retained aura clipping clears the recording region`() {
        val bounded = playerSurfaceArtworkAuraClipBounds(
            pagerLeft = 0f,
            pagerTop = 74f,
            pagerRight = 432f,
            recordingBottom = 186f,
            surfaceWidth = 432f,
            surfaceHeight = 900f,
            density = 1f
        )
        assertEquals(244f, bounded.bottom, .0001f)
    }

    @Test
    fun `native aura retains the same complete field padding as compose`() {
        assertEquals(60f, playerSurfaceArtworkAuraRasterPaddingDp(56f), .0001f)
        assertEquals(22f, playerSurfaceArtworkAuraRasterPaddingDp(18f), .0001f)
    }

    @Test
    fun `native aura mirrors artwork ownership and breathing opacity calibration`() {
        assertEquals(.82f, ARTWORK_AURA_NEAR_BASE_OPACITY, 0f)
        assertEquals(.58f, ARTWORK_AURA_FAR_BASE_OPACITY, 0f)
        assertEquals(2f, ARTWORK_AURA_VISIBILITY_MULTIPLIER, 0f)
        val dimOpacity = artworkAuraOpacity(
            ARTWORK_AURA_NEAR_BASE_OPACITY,
            ARTWORK_AURA_DIM_OPACITY_OFFSET
        )
        val baselineOpacity = artworkAuraOpacity(ARTWORK_AURA_NEAR_BASE_OPACITY, 0f)
        val brightOpacity = artworkAuraOpacity(
            ARTWORK_AURA_NEAR_BASE_OPACITY,
            ARTWORK_AURA_BRIGHT_OPACITY_OFFSET
        )
        assertTrue(dimOpacity < baselineOpacity)
        assertTrue(baselineOpacity < brightOpacity)
        assertTrue(brightOpacity < 1f)
        assertEquals(ARTWORK_AURA_BRIGHT_OPACITY_OFFSET,
            playerSurfaceArtworkAuraOpacityOffset(1f), .0001f)
        assertEquals(ARTWORK_AURA_DIM_OPACITY_OFFSET,
            playerSurfaceArtworkAuraOpacityOffset(-.5f), .0001f)
        assertEquals(ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET,
            playerSurfaceArtworkAuraLuminanceOffset(1f), .0001f)
        assertEquals(ARTWORK_AURA_DIM_LUMINANCE_OFFSET,
            playerSurfaceArtworkAuraLuminanceOffset(-.5f), .0001f)
    }

    @Test
    fun `native aura uses the accepted keyframes and event driven system policy`() {
        assertEquals(0f, playerSurfaceArtworkAuraKeyframe(0f), .0001f)
        assertEquals(1f, playerSurfaceArtworkAuraKeyframe(.35f), .0001f)
        assertEquals(-.5f, playerSurfaceArtworkAuraKeyframe(.68f), .0001f)
        assertEquals(0f, playerSurfaceArtworkAuraKeyframe(1f), .0001f)
        // First-segment quarter: exact cubic (0.45, 0, 0.55, 1), not linear 0.25.
        assertEquals(.1201546f, playerSurfaceArtworkAuraKeyframe(.0875f), .0005f)

        assertEquals(
            1_890_000_000L,
            StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS
        )

    }

    @Test
    fun `compact player keeps a 68 dp owner around the exact 64 dp visible body`() {
        assertEquals(68f, PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP, .0001f)
        assertEquals(12f, PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP, .0001f)
        assertEquals(2f, PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP, .0001f)
        assertEquals(18f, PLAYER_SURFACE_COMPACT_VISIBLE_RADIUS_DP, .0001f)
        assertEquals(20f, PLAYER_SURFACE_COMPACT_ARTWORK_LEFT_DP, .0001f)
        assertEquals(10f, PLAYER_SURFACE_COMPACT_ARTWORK_TOP_DP, .0001f)
        assertEquals(48f, PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP, .0001f)
        assertEquals(12f, PLAYER_SURFACE_COMPACT_ARTWORK_RADIUS_DP, .0001f)
        assertEquals(82f, PLAYER_SURFACE_COMPACT_IDENTITY_LEFT_DP, .0001f)
        assertEquals(20f, PLAYER_SURFACE_COMPACT_ACTION_RIGHT_INSET_DP, .0001f)
        assertEquals(48f, PLAYER_SURFACE_COMPACT_ACTION_SIZE_DP, .0001f)
    }

    @Test
    fun `native current artwork owns phase ordinal one and wraps globally`() {
        assertEquals(2_050L, playerSurfaceArtworkAuraPhaseMillis(0L, surfaceOrdinal = 1))
        assertEquals(0L, playerSurfaceArtworkAuraPhaseMillis(3_350L, surfaceOrdinal = 1))
        assertEquals(2_049L, playerSurfaceArtworkAuraPhaseMillis(-1L, surfaceOrdinal = 1))
    }

    @Test
    fun `collapsed playback redraws only the visible mini clock`() {
        assertEquals(
            PlayerSurfacePositionInvalidation(mini = true, timeline = false),
            playerSurfacePositionInvalidation(expansionProgress = 0f, miniFrameDue = true)
        )
        assertEquals(
            PlayerSurfacePositionInvalidation(mini = false, timeline = false),
            playerSurfacePositionInvalidation(expansionProgress = 0f, miniFrameDue = false)
        )
        assertEquals(
            PlayerSurfacePositionInvalidation(mini = false, timeline = true),
            playerSurfacePositionInvalidation(expansionProgress = 1f, miniFrameDue = true)
        )
    }

}
