package app.stemwise.ui.design

import app.stemwise.ui.design.generated.STEMWISE_ICON_FAMILY_COUNT
import app.stemwise.ui.design.generated.STEMWISE_ICON_MASTER_COUNT
import app.stemwise.ui.design.generated.STEMWISE_ICON_REDUCTION_COUNT
import app.stemwise.ui.design.generated.STEMWISE_ICON_ROLE_COUNT
import app.stemwise.ui.design.generated.StemwiseGeneratedGlyphCatalog
import app.stemwise.ui.design.generated.StemwiseIconRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StemwiseDesignSystemTest {
    @Test
    fun deferredBpmFeedbackTicksOnlyForAnAcceptedChangedInteger() {
        val feedback = StemwiseSliderFeedback()
        repeat(1_000) { index ->
            assertFalse(feedback.change(120f, 120f + index / 10_000f, true, 1f, true))
        }
        assertFalse(feedback.finish(true, true))
        repeat(1_000) { assertFalse(feedback.change(120f, 121f, true, 1f, true)) }
        assertTrue(feedback.finish(true, true))
        assertFalse(feedback.change(121f, 122f, true, 1f, true))
        assertFalse(feedback.finish(false, true))
    }

    @Test
    fun continuousFeedbackIsBoundedToAcceptedStartAndCommit() {
        val feedback = StemwiseSliderFeedback()
        var ticks = 0
        repeat(10_000) { index ->
            if (feedback.change(0f, (index + 1) / 10_000f, true, null, false)) ticks++
        }
        assertEquals(1, ticks)
        assertTrue(feedback.finish(true, false))
        assertFalse(feedback.change(1f, .5f, false, null, false))
        assertFalse(feedback.finish(true, false))
        assertTrue(feedback.change(1f, .5f, true, null, false))
        assertFalse(feedback.finish(false, false))
    }

    @Test
    fun acceptedDiscreteDetentsDoNotRepeatForDenseFractionalInputs() {
        val feedback = StemwiseSliderFeedback()
        var ticks = 0
        repeat(1_000) { if (feedback.change(0f, 1.01f, true, 1f, false)) ticks++ }
        assertEquals(1, ticks)
        assertFalse(feedback.change(1f, 2f, false, 1f, false))
        assertTrue(feedback.change(1f, 2f, true, 1f, false))
        assertFalse(feedback.finish(true, false))
    }

    @Test
    fun generatedCatalogKeepsTheCurrentIconInventoryAndSemanticCoverage() {
        assertEquals(144, STEMWISE_ICON_ROLE_COUNT)
        assertEquals(134, STEMWISE_ICON_MASTER_COUNT)
        assertEquals(29, STEMWISE_ICON_FAMILY_COUNT)
        assertEquals(10, STEMWISE_ICON_REDUCTION_COUNT)
        assertEquals(StemwiseIconRole.entries.size, StemwiseGeneratedGlyphCatalog.all.size)
        assertEquals(
            StemwiseIconRole.entries.toSet(),
            StemwiseGeneratedGlyphCatalog.all.map { it.role }.toSet()
        )
        assertTrue(StemwiseGeneratedGlyphCatalog.all.all { it.layers.isNotEmpty() })
        assertTrue(StemwiseGeneratedGlyphCatalog.all.all { glyph ->
            glyph.layers.map { it.order } == glyph.layers.map { it.order }.sorted()
        })
    }

    @Test
    fun onlyTheThreeApprovedRolesMirrorInRtl() {
        assertEquals(
            setOf(
                StemwiseIconRole.LIBRARY_QUEUE_MUSIC,
                StemwiseIconRole.LIBRARY_OPEN_EXTERNAL,
                StemwiseIconRole.SYSTEM_DISCLOSURE
            ),
            StemwiseGeneratedGlyphCatalog.all.filter { it.rtlMirror }.map { it.role }.toSet()
        )
    }

    @Test
    fun composeAdapterCachesEachImmutableRoleVector() {
        StemwiseIconRole.entries.forEach { role ->
            val first = StemwiseComposeGlyphs.icon(role)
            val second = StemwiseComposeGlyphs.icon(role)
            assertSame(first, second)
            assertEquals(24f, first.viewportWidth)
            assertEquals(24f, first.viewportHeight)
        }
    }


    @Test
    fun artworkAuraDegradesForSystemPolicyWithoutDisablingStateMotion() {
        assertTrue(artworkAuraSystemAllowed(true, powerSaveMode = false, thermalStatus = 2))
        assertFalse(artworkAuraSystemAllowed(true, powerSaveMode = true, thermalStatus = 0))
        assertFalse(artworkAuraSystemAllowed(true, powerSaveMode = false, thermalStatus = 3))
        assertFalse(artworkAuraSystemAllowed(false, powerSaveMode = false, thermalStatus = 0))
    }

    @Test
    fun routineMotionHasBoundedDurationsAndResponsiveExits() {
        StemwiseMotionEvent.entries.filter { it != StemwiseMotionEvent.ARTWORK_AURA_BREATHE }
            .forEach { event ->
                assertTrue("$event must finish within the interactive budget",
                    StemwiseSemanticMotion.token(event).durationMs in 0..280)
            }
        assertTrue("Exit feedback must start promptly",
            StemwiseMotionEasing.EXIT.curve.transform(.25f) > .5f)
    }

    @Test
    fun semanticMotionUsesCurrentFallbackTokens() {
        assertEquals(200, StemwiseSemanticMotion.token("unknown.future.event").durationMs)
        assertEquals(200, StemwiseSemanticMotion.token(StemwiseMotionEvent.STATE_CHANGE_DEFAULT).durationMs)
        assertEquals(200, StemwiseSemanticMotion.token(StemwiseMotionEvent.APP_CONTROLLED_EXIT).durationMs)
        assertEquals(250, StemwiseSemanticMotion.ARTWORK_LOAD_MS)
        assertEquals(5_400, StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS)
        assertEquals(2_050, StemwiseSemanticMotion.ARTWORK_AURA_PHASE_OFFSET_MS)
    }

    private fun uiSource(relativePath: String): String {
        return File(uiSourceRoot(), relativePath).readText()
    }

    private fun uiSourceRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        val project = generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project")
        return File(project, "app/src/main/java/app/stemwise/ui")
    }

}
