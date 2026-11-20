package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryLargeTextInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test
    fun flacTitleDurationAndMenuRemainVisibleAt320dpWithDoubleText() {
        val song = song()
        var played = 0
        compose.setContent {
            NarrowDoubleText {
                LibraryScreen(songs = listOf(song), onSongClick = { played++; true },
                    playlistName = "Practice", syncing = false, syncError = null,
                    ambientMotionEnabled = false)
            }
        }
        val row = compose.onNodeWithTag("library-song:${song.id}")
        if (!row.isDisplayed()) row.performScrollTo()
        row.assertIsDisplayed()
        for (text in listOf("J", "FLAC", "62:03")) {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            node.assertIsDisplayed()
            assertCompleteText(node, text)
        }
        val menu = compose.onNodeWithTag("library-song-menu:${song.id}")
        val bounds = menu.fetchSemanticsNode().boundsInRoot
        val minimumTargetPx = 48f * compose.activity.resources.displayMetrics.density
        assertTrue(bounds.width >= minimumTargetPx && bounds.height >= minimumTargetPx)
        menu.performClick()
        compose.onNodeWithText("Song details").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Menu must not play the row", 0, played) }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Song details").assertDoesNotExist()
        row.performClick()
        compose.runOnIdle { assertEquals("Row still plays its song", 1, played) }
    }

    @Test
    fun heroActionsFitAndStillInvokeTheirCallbacksWithoutMarquee() {
        var shuffled = 0
        var played = 0
        compose.setContent {
            NarrowDoubleText {
                LibraryScreen(songs = listOf(song()), onSongClick = { true },
                    onShuffle = { shuffled++ }, onPlayAll = { played++ },
                    playlistName = "Practice", syncing = false, syncError = null,
                    ambientMotionEnabled = false)
            }
        }
        for (text in listOf("Shuffle", "Play")) {
            val action = compose.onNodeWithText(text)
            if (!action.isDisplayed()) action.performScrollTo()
            action.assertIsDisplayed()
            assertCompleteText(compose.onNodeWithText(text, useUnmergedTree = true), text)
            val bounds = action.fetchSemanticsNode().boundsInRoot
            val minimumTargetPx = 48f * compose.activity.resources.displayMetrics.density
            assertTrue(bounds.width >= minimumTargetPx && bounds.height >= minimumTargetPx)
            action.performClick()
        }
        compose.runOnIdle { assertEquals(1, shuffled); assertEquals(1, played) }
    }

    @Test
    fun songDetailsExposeCompleteTitleAndArtistInTheScrollableBody() {
        val song = song().copy(
            title = "A Night at the Royal Albert Hall - Complete Concert, Encore and Rehearsal (25th Anniversary Remastered Edition)",
            artist = "Aleksandra Wisniewska-Kowalczyk feat. Christopher Alexander Montgomery III")
        compose.setContent {
            NarrowDoubleText {
                SongInfoDialog(song = song, onDismiss = {}, onCopyVideoId = {}, onDeleteSong = {})
            }
        }
        for (text in listOf(song.title, song.artist)) {
            val bodyText = compose.onNode(hasText(text) and hasAnyAncestor(hasScrollAction()),
                useUnmergedTree = true)
            bodyText.performScrollTo()
            assertCompleteText(bodyText, text)
        }
        compose.onNodeWithText("Artist").performScrollTo().assertIsDisplayed()
    }

    @Composable
    private fun NarrowDoubleText(content: @Composable () -> Unit) {
        StemwiseTheme {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 2f),
                LocalStemwiseMotion provides StemwiseMotionSpec(false)
            ) {
                Box(Modifier.width(320.dp)) { content() }
            }
        }
    }

    private fun assertCompleteText(node: SemanticsNodeInteraction, text: String) {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("$text clips vertically", layout.didOverflowHeight)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse("$text is ellipsized", layout.isLineEllipsized(line))
            assertTrue("$text clips horizontally", layout.getLineLeft(line) >= -1f &&
                layout.getLineRight(line) <= layout.size.width + 1f)
        }
    }

    private fun song() = Song(id = "large-flac", videoId = "large-flac", title = "J", artist = "Jo",
        durationMs = 3_723_000L, state = SongState.READY, accent = Color.Cyan,
        artworkGlyph = "J", sourceCodec = "flac")
}
