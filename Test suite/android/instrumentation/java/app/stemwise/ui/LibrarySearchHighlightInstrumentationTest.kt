package app.stemwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.model.Song
import app.stemwise.model.SongState
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibrarySearchHighlightInstrumentationTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test
    fun playbackChangesReuseMountedSearchMetadataProjection() {
        val traversals = AtomicInteger()
        val metadata = mapOf("Composer" to "Aurelia")
        val countedMetadata = object : Map<String, String> by metadata {
            override val entries: Set<Map.Entry<String, String>>
                get() {
                    traversals.incrementAndGet()
                    return metadata.entries
                }
        }
        val song = Song(
            id = "search-projection-fixture",
            videoId = "SEARCH00002",
            title = "Violet Circuit",
            artist = "StemWise",
            album = "Executable Examples",
            durationMs = 12_000L,
            state = SongState.READY,
            accent = Color(0xFFA970FF),
            artworkGlyph = "VC",
            additionalInfo = countedMetadata
        )
        val songs = listOf(song)
        var playing by mutableStateOf(false)
        composeRule.setContent {
            StemwiseTheme {
                LibraryScreen(
                    songs = songs,
                    onSongClick = { true },
                    currentSongId = song.id,
                    currentSongPlaying = playing,
                    playlistName = "Projection fixture",
                    syncing = false,
                    syncError = null,
                    ambientMotionEnabled = false
                )
            }
        }
        composeRule.onNodeWithTag("library-search-field").performTextInput("Aurelia")
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("Composer · Aurelia").fetchSemanticsNodes().isNotEmpty()
        }
        var settledTraversals = 0
        composeRule.runOnIdle {
            settledTraversals = traversals.get()
            assertTrue(settledTraversals > 0)
            playing = true
        }
        composeRule.runOnIdle {
            assertEquals("Playback state must not repeat metadata sorting/search", settledTraversals, traversals.get())
        }
        composeRule.onNodeWithText("Violet Circuit", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("StemWise", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("library-search-field").performTextReplacement("Executable")
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("Album · Executable Examples", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Violet Circuit", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("StemWise", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Album · Executable Examples", useUnmergedTree = true).assertExists()
    }

    @Test
    fun emptyMountedSearchReceivesTheAcceptedFocusedPresentation() {
        var observedFocus = false
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            var focused by remember { mutableStateOf(false) }
            StemwiseTheme {
                PlaylistSearch(
                    query = query,
                    focused = focused,
                    onQueryChange = { query = it },
                    onFocusChange = {
                        focused = it
                        observedFocus = it
                    }
                )
            }
        }

        composeRule.onNodeWithTag("library-search-field")
            .performClick()
            .assertIsFocused()
        composeRule.runOnIdle {
            assertTrue(observedFocus)
            assertEquals(1.5f, librarySearchFieldPresentation(true).borderWidthDp, 0f)
            assertTrue(librarySearchFieldPresentation(true).focusedShadow)
        }
    }

    @Test
    fun arabicHighlightColorsOnlyExactMatchedCharacters() {
        val text = "ليلة النجوم"
        composeRule.setContent {
            StemwiseTheme {
                HighlightedSearchText(text = text, query = "نجوم", fontSize = 28.sp)
            }
        }
        val rendered = composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val span = rendered.spanStyles.single()
        assertEquals(7, span.start)
        assertEquals(11, span.end)
        assertEquals(Color.Unspecified, span.item.background)
        assertEquals(null, span.item.fontWeight)
        assertEquals(null, span.item.textDecoration)
    }

    @Test
    fun mountedArabicHighlightColorsGlyphsWithoutChangingTheirShape() {
        val text = "ليلة النجوم"
        var query by mutableStateOf("")
        composeRule.setContent {
            StemwiseTheme {
                Box(
                    Modifier
                        .testTag("arabic-highlight-canvas")
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    HighlightedSearchText(
                        text = text,
                        query = query,
                        color = Color.White,
                        fontSize = 28.sp,
                        modifier = Modifier.width(280.dp)
                    )
                }
            }
        }

        composeRule.onNodeWithText(text).assertExists()
        val plain = composeRule.onNodeWithTag("arabic-highlight-canvas")
            .captureToImage().toPixelMap()
        composeRule.runOnIdle { query = "نجوم" }
        val pixels = composeRule.onNodeWithTag("arabic-highlight-canvas")
            .captureToImage()
            .toPixelMap()
        var primaryGlyphPixels = 0
        var paintedOutsideGlyphs = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.blue - pixel.green > .15f && pixel.red - pixel.green > .15f) {
                    primaryGlyphPixels += 1
                    val before = plain[x, y]
                    if (before.red + before.green + before.blue < .02f) paintedOutsideGlyphs += 1
                }
            }
        }
        assertTrue(
            "Matched letters produced only $primaryGlyphPixels Primary pixels",
            primaryGlyphPixels >= 20
        )

        assertTrue("Highlight painted outside the existing glyphs", paintedOutsideGlyphs <= 8)

        // A new query changes color without changing glyph layout.
        composeRule.runOnIdle { query = "ليلة" }
        val replacement = composeRule.onNodeWithTag("arabic-highlight-canvas")
            .captureToImage()
            .toPixelMap()
        assertEquals(pixels.width, replacement.width)
        assertEquals(pixels.height, replacement.height)
        var changedPixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != replacement[x, y]) changedPixels += 1
            }
        }
        assertTrue("Changing the query must repaint its highlight", changedPixels >= 20)
    }
}
