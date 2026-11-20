package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.ui.player.surface.PlayerSurfaceTextLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuditedReadabilityInstrumentationTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    @Test
    fun recoveryHeadingsAndParagraphsWrapWithoutClippingAtLargeFontScales() {
        val fontScale = mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                StemwiseTheme {
                    Box(Modifier.width(328.dp)) {
                        LibraryRecoveryGate("ROOT_UNAVAILABLE", "Storage disconnected", onPrimaryAction = {}, onNotNow = {})
                    }
                }
            }
        }
        val heading = compose.activity.getString(app.stemwise.R.string.ui_stemwise_needs_attention)
        for (scale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            for ((text, policy) in listOf(heading to LineBreak.Heading,
                "The Library folder could not be opened." to LineBreak.Paragraph)) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                assertEquals(policy, layout.layoutInput.style.lineBreak)
                assertFalse("$text clips vertically at $scale", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse(layout.isLineEllipsized(line))
                    // Compose's diagnostic paragraph can retain the parent's width for wrap-content text.
                    assertTrue("$text line $line clips at $scale",
                        layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1)
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1)
                }
                if (text == heading && scale == 2f) assertTrue(layout.lineCount > 1)
            }
        }
    }

    @Test
    fun numericReadoutsHaveEqualDigitAdvancesAndShapingCacheKeepsFontFeatures() {
        compose.setContent {
            StemwiseTheme {
                Column {
                    Text("1111", style = MaterialTheme.typography.titleMedium)
                    Text("8888", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val widths = listOf("1111", "8888").map { value ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals("tnum", layout.layoutInput.style.fontFeatureSettings)
            layout.getLineRight(0) - layout.getLineLeft(0)
        }
        assertEquals(widths[0], widths[1], .1f)
        val paint = android.text.TextPaint().apply { textSize = 32f; fontFeatureSettings = "pnum" }
        val cache = PlayerSurfaceTextLayouts(1024)
        val proportional = cache.get("1818", paint)
        paint.fontFeatureSettings = "tnum"
        val tabular = cache.get("1818", paint)
        assertTrue(proportional !== tabular)
        assertEquals("tnum", tabular.paint.fontFeatureSettings)
        assertEquals(tabular.paint.measureText("1111"), tabular.paint.measureText("8888"), .1f)
    }

    @Test
    fun tuneTextFitsAtEveryScaleAndPrimaryContentHasContrast() {
        val fontScale = mutableFloatStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)
            ) {
                StemwiseTheme {
                    val colors = MaterialTheme.colorScheme
                    assertTrue(ColorUtils.calculateContrast(
                        colors.onPrimary.toArgb(), colors.primary.toArgb()
                    ) >= 4.5)
                    Box(Modifier.width(328.dp)) {
                        KeyPanel(song("tune", 12_000L), 0, { true })
                        SpeedPanel(1f, { true })
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            compose.onNodeWithText("Speed").assertDoesNotExist()
            compose.onNodeWithText("1×").assertDoesNotExist()
            for (label in listOf("Key", "C# Minor", "Tempo")) {
                assertTextFits(label, scale)
            }
            for (label in listOf("Key adjustment", "Reset key", "Tempo adjustment", "Reset tempo")) {
                val bounds = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
                assertTrue("$label at $scale", bounds.right - bounds.left >= 48.dp &&
                    bounds.bottom - bounds.top >= 48.dp)
            }
        }
    }

    @Test
    fun shortAndHourLongLibraryDurationsStayOnOneUnclippedLine() {
        val fontScale = mutableFloatStateOf(1f)
        val songs = listOf(song("short", 12_000L), song("long", 3_723_000L))
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)
            ) {
                StemwiseTheme {
                    Box(Modifier.width(360.dp)) {
                        LibraryScreen(
                            songs = songs, onSongClick = { true },
                            currentSongId = songs.first().id, currentSongPlaying = true,
                            playlistName = "Readability fixture", syncing = false,
                            syncError = null, ambientMotionEnabled = false
                        )
                    }
                }
            }
        }
        for (scale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            for (song in songs) assertTextFits(librarySongRowPresentation(song).duration, scale)
        }
    }

    private fun assertTextFits(label: String, scale: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("$label line count at $scale", 1, layout.lineCount)
        assertFalse("$label clips vertically at $scale", layout.didOverflowHeight)
        assertTrue("$label line box clips at $scale", layout.getLineBottom(0) <= layout.size.height + 1)
        assertTrue("$label clips horizontally at $scale: ${layout.size}, line end ${layout.getLineRight(0)}",
            layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1)
        assertFalse("$label is ellipsized at $scale", layout.isLineEllipsized(0))
    }

    private fun song(id: String, duration: Long) = Song(
        id = id, videoId = id, title = "Readability $id", artist = "Fixture",
        durationMs = duration, state = SongState.READY, accent = Color.Cyan,
        artworkGlyph = "R", key = "C# Minor"
    )
}
