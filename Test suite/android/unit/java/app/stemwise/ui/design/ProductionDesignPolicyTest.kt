package app.stemwise.ui.design

import androidx.compose.ui.text.font.FontFamily
import app.stemwise.ui.theme.StemwiseSystemFontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static dependencies/assets only; rendered behavior belongs to the connected production owners. */
class ProductionDesignPolicyTest {
    @Test fun currentDesignOwnsItsFontAndIconAssets() {
        val project = projectRoot()
        val build = File(project, "app/build.gradle.kts").readText()
        assertFalse("Extended Material icon dependency remains", "material-icons-extended" in build)
        assertEquals(FontFamily.SansSerif, StemwiseSystemFontFamily)
        assertTrue(File(project, "design-system/stemwise-icons.json").isFile)
        assertEquals("Song details", stringResourceValue("ui_song_details"))
        assertEquals("Close", stringResourceValue("ui_close"))
        assertEquals("Delete stems only", stringResourceValue("ui_delete_stems_only"))
        assertEquals("Delete song with everything", stringResourceValue("ui_delete_song_with_everything"))
    }

    // Runtime replacements for the former body-shape assertions:
    // PlayerVisualPolicyInstrumentationTest: progress/error semantics, accepted-control pixels,
    // and actual SongInfo focus, physical action bounds, copy identity and large-text reflow.
    // CompactPlayerProductionIntegrationTest: mounted PlayerScreen/retained native surface,
    // real Clear loop action, and acceptedArtworkSkipRemainsAbsentFromSemanticsThroughoutItsVisibleAndExitLifetime.
    // NativePlayerRenderingInstrumentationTest.missingArtworkDrawsItsStableGlyphAtMiniAndFullSizes.
    // ArtworkAuraPreparationInstrumentationTest: publication/replacement, immutable raster reuse,
    // and independent opacity/luminance drawing.
    // DesignSystemComposeInstrumentationTest: launch retention, OLED canvas and Compose aura replacement.
    // MetronomeAccessibilityInstrumentationTest and PlayerLyricsViewportTest exercise the current
    // detail actions; source spelling/call counts are not evidence that those actions are reachable.

    private fun stringResourceValue(name: String): String {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(projectRoot(), "app/src/main/res/values/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == name }.textContent
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
