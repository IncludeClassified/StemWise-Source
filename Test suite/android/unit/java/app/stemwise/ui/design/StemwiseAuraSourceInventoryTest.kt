package app.stemwise.ui.design

import app.stemwise.ui.artworkAuraTextureSizing
import app.stemwise.ui.artworkAuraMaskBlurRadiusDp
import app.stemwise.ui.artworkAuraRetainedPaddingDp
import app.stemwise.ui.ARTWORK_AURA_FAR_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_NEAR_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_THUMBNAIL_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_VISIBILITY_MULTIPLIER
import app.stemwise.ui.stableArtworkPhaseOffsetMs
import androidx.compose.foundation.interaction.MutableInteractionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Acceptance/order/full-body lifetime is exercised through the actual production control in
// PlayerVisualPolicyInstrumentationTest.acceptedComposeFeedbackOwnsOneFullBodyAndSurvivesLaterDisablement.
// Physical long-press classification: LibraryInteractionTest.longPressOpensInformationWithoutPlayingTheSong;
// status cadence: LibraryInteractionTest.currentSongBarsMoveOnlyWhilePlayingAndUncovered;
// real thumb ownership: DesignSystemComposeInstrumentationTest.sharedMetronomeSliderThumbExpandsDuringContactAndReturnsOnRelease.
// Immutable endpoint replacement/reuse: DesignSystemComposeInstrumentationTest
// .composeAuraReplacementKeepsReadyPixelsAndRepeatedDrawsDoNotRasterize.
class StemwiseAuraSourceInventoryTest {
    @Test
    fun acceptedButtonFeedbackIsAdmittedOnceAndSurvivesLaterDisablement() {
        val source = MutableInteractionSource()
        assertFalse("Disabled action emitted visual feedback", source.emitAcceptedAuraFeedback(false))
        assertTrue("Enabled accepted action did not emit visual feedback", source.emitAcceptedAuraFeedback(true))

        val gate = StemwiseAuraPressGate()
        assertFalse(gate.consume(sequence = 0, movable = false))
        assertTrue(gate.consume(sequence = 1, movable = false))
        assertFalse("Accepted sequence replayed", gate.consume(sequence = 1, movable = false))
        assertFalse("Movable owner consumed button feedback", gate.consume(sequence = 2, movable = true))
        assertTrue("Later accepted sequence was stranded", gate.consume(sequence = 2, movable = false))

    }

    @Test
    fun rejectionCapableActionEmitsFeedbackOnlyAfterOwnerAcceptance() {
        val rejectedEvents = mutableListOf<String>()
        assertFalse(
            performAcceptedAuraAction(
                enabled = true,
                action = {
                    rejectedEvents += "action"
                    false
                },
                onAccepted = { rejectedEvents += "feedback" }
            )
        )
        assertEquals(listOf("action"), rejectedEvents)

        val acceptedEvents = mutableListOf<String>()
        assertTrue(
            performAcceptedAuraAction(
                enabled = true,
                action = {
                    acceptedEvents += "action"
                    true
                },
                onAccepted = { acceptedEvents += "feedback" }
            )
        )
        assertEquals(listOf("action", "feedback"), acceptedEvents)
    }

    @Test
    fun selectedBloomIsGlyphLocalizedAndUsesItsStrongCoreBand() {
        assertEquals(
            1f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = true,
                selected = true,
                anchor = AuraAnchor.GlyphLocalized
            ),
            .0001f
        )
        assertEquals(
            0f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = true,
                selected = false,
                anchor = AuraAnchor.GlyphLocalized
            ),
            .0001f
        )
        assertEquals(
            0f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = true,
                selected = true,
                anchor = AuraAnchor.VisibleControl
            ),
            .0001f
        )
        assertEquals(
            0f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = true,
                selected = true,
                anchor = AuraAnchor.Movable
            ),
            .0001f
        )
        assertEquals(
            .5f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = true,
                selectedProgress = .5f,
                anchor = AuraAnchor.GlyphLocalized
            ),
            .0001f
        )
        assertEquals(
            0f,
            stemwiseSelectedGlyphGlowAlpha(
                enabled = false,
                selected = true,
                anchor = AuraAnchor.GlyphLocalized
            ),
            .0001f
        )
    }

    @Test
    fun retainedArtworkBandCalibrationKeepsItsAcceptedReachAndOpacity() {
        assertEquals(.44f, ARTWORK_AURA_THUMBNAIL_BASE_OPACITY, 0f)
        assertEquals(.82f, ARTWORK_AURA_NEAR_BASE_OPACITY, 0f)
        assertEquals(.58f, ARTWORK_AURA_FAR_BASE_OPACITY, 0f)
        assertEquals(2f, ARTWORK_AURA_VISIBILITY_MULTIPLIER, 0f)
        assertEquals(9f, artworkAuraRetainedPaddingDp(reachDp = 5f), 0f)
        assertEquals(1f, artworkAuraMaskBlurRadiusDp(visibleReachDp = 4f), 0f)
        assertEquals(2.5f, artworkAuraMaskBlurRadiusDp(visibleReachDp = 7f), 0f)
        assertEquals(8f, artworkAuraMaskBlurRadiusDp(visibleReachDp = 18f), 0f)
        assertEquals(27f, artworkAuraMaskBlurRadiusDp(visibleReachDp = 56f), 0f)
    }

    @Test
    fun artworkAuraTextureSizingCapsLongestEdgeAndPreservesScale() {
        val fullDensityHero = artworkAuraTextureSizing(nodeWidthPx = 2_048, nodeHeightPx = 1_024)
        assertEquals(256, fullDensityHero.bitmapWidthPx)
        assertEquals(128, fullDensityHero.bitmapHeightPx)
        assertEquals(.125f, fullDensityHero.cacheScale, 0f)

        val alreadyBounded = artworkAuraTextureSizing(nodeWidthPx = 320, nodeHeightPx = 480)
        assertEquals(171, alreadyBounded.bitmapWidthPx)
        assertEquals(256, alreadyBounded.bitmapHeightPx)
        assertEquals(256f / 480f, alreadyBounded.cacheScale, 0f)

        val portraitHero = artworkAuraTextureSizing(nodeWidthPx = 800, nodeHeightPx = 1_200)
        assertEquals(171, portraitHero.bitmapWidthPx)
        assertEquals(256, portraitHero.bitmapHeightPx)
        assertEquals(256f / 1_200f, portraitHero.cacheScale, 0f)
    }

    @Test
    fun artworkAuraOwnersAlignToOneGlobalCadence() {
        assertEquals(33L, app.stemwise.ui.artworkAuraFrameDelayMs(0L))
        assertEquals(32L, app.stemwise.ui.artworkAuraFrameDelayMs(1L))
        assertEquals(1L, app.stemwise.ui.artworkAuraFrameDelayMs(32L))
        assertEquals(33L, app.stemwise.ui.artworkAuraFrameDelayMs(33L))
    }

    @Test
    fun adjacentArtworkPhaseOrdinalsKeepExactSeparationAcrossFormerSlotBoundary() {
        val periodMs = StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS.toLong()
        val expectedSeparationMs = StemwiseSemanticMotion.ARTWORK_AURA_PHASE_OFFSET_MS.toLong()
        listOf(5 to 6, 6 to 7).forEach { (first, second) ->
            val cyclicSeparation = Math.floorMod(
                stableArtworkPhaseOffsetMs(second) - stableArtworkPhaseOffsetMs(first),
                periodMs
            )
            assertEquals(
                "Ordinals $first and $second lost exact cyclic phase separation",
                expectedSeparationMs,
                cyclicSeparation
            )
        }
    }

}
