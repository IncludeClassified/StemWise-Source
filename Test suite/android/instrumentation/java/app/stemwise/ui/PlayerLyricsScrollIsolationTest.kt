package app.stemwise.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.stemwise.model.LyricTrack
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.TimedLyricLine
import app.stemwise.model.TimedLyricWord
import app.stemwise.ui.theme.StemwiseTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerLyricsScrollIsolationTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    private lateinit var pageProbe: PlayerPageScrollProbe
    private lateinit var pageListState: LazyListState

    @Test
    fun outgoingLyricActionsCannotSeekAfterTrackOrSameSongOwnerChange() {
        val generation = mutableLongStateOf(1L)
        val song = mutableStateOf(Song(id = "lyrics-owner", videoId = "LyricsOwn01",
            title = "Lyrics owner", artist = "StemWise", durationMs = 30_000L,
            state = SongState.READY, accent = Color.Cyan, artworkGlyph = "LS",
            lyricTracks = listOf(LyricTrack("original", "original", "en", "timed",
                lines = listOf(TimedLyricLine(1_000L, 3_000L, "Owned lyric"))))))
        val accepted = mutableListOf<Long>()
        compose.setContent {
            StemwiseTheme {
                ExpandedLyricsOverlay(visible = true, onDismiss = {}, song = song.value,
                    presentationGeneration = generation.longValue,
                    positionState = remember { mutableLongStateOf(0L) },
                    onSeek = { owner, _ -> accepted.add(owner) })
            }
        }
        compose.waitForIdle()
        for (nextGeneration in listOf(2L, 3L)) {
            val oldAction = compose.onNodeWithText("Owned lyric")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                generation.longValue = nextGeneration
                if (nextGeneration == 3L) song.value = song.value.copy(id = "lyrics-next")
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { oldAction() }
            assertTrue("Outgoing semantics reached the new owner's seek callback", accepted.isEmpty())
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
        compose.onNodeWithText("Owned lyric").performClick()
        compose.runOnIdle { assertEquals(listOf(3L), accepted) }
    }

    @Test
    fun wordTimedImportsRenderAsWholeLinesWithoutWordHighlightSpans() {
        setLyrics(listOf(LyricTrack(
            "original", "original", "en", "timed",
            lines = listOf(TimedLyricLine(
                startMs = 0L,
                endMs = 2_000L,
                text = "Whole lyric line",
                words = listOf(TimedLyricWord(0, 5, 0L, 1_000L))
            ))
        )))
        val text = compose.onNodeWithText("Whole lyric line", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("Whole lyric line", text.text)
        assertTrue("Word timing must not produce per-word display spans", text.spanStyles.isEmpty())
    }

    @Test
    fun androidBackDismissesLyricsWithoutASmallCloseControl() {
        var dismissed = false
        compose.setContent {
            StemwiseTheme {
                ExpandedLyricsOverlay(visible = true, onDismiss = { dismissed = true },
                    song = Song(id = "close-lyrics", videoId = "CloseLyrics", title = "Lyrics", artist = "StemWise",
                        durationMs = 30_000L, state = SongState.READY, accent = Color.Cyan, artworkGlyph = "LS"),
                    presentationGeneration = 1L, positionState = remember { mutableLongStateOf(0L) },
                    onSeek = { _, _ -> true })
            }
        }
        compose.onNodeWithContentDescription("Close lyrics").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun downwardDragAtListTopDismissesExpandedLyrics() {
        var dismissCount = 0
        compose.setContent {
            StemwiseTheme {
                val visible = remember { mutableStateOf(true) }
                ExpandedLyricsOverlay(
                    visible = visible.value,
                    onDismiss = { dismissCount++; visible.value = false },
                    song = Song(
                        id = "lyrics-dismiss",
                        videoId = "LyricsDismiss",
                        title = "Lyrics dismiss",
                        artist = "StemWise",
                        durationMs = 180_000L,
                        state = SongState.READY,
                        accent = Color.Cyan,
                        artworkGlyph = "LS",
                        lyricTracks = listOf(LyricTrack(
                            "original", "original", "en", "plain",
                            text = (1..120).joinToString("\n") { "Dismiss lyric $it" }
                        ))
                    ),
                    positionState = remember { mutableLongStateOf(0L) },
                    presentationGeneration = 1L,
                    onSeek = { _, _ -> true }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lyrics_content_scroll").performTouchInput {
            swipe(Offset(width * .5f, height * .15f), Offset(width * .5f, height * .65f), 300L)
        }
        compose.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun upwardEdgeDragStaysInsideAPlainLyricsViewport() {
        val end = "PLAIN_SCROLL_OWNER_END"
        val text = (1..120).joinToString("\n") { index ->
            if (index == 120) end else "Owned plain lyric $index"
        }
        setLyrics(
            listOf(LyricTrack("original", "original", "en", "plain", text = text))
        )

        val lyrics = compose.onNodeWithTag("lyrics_content_scroll")
        lyrics.performScrollToNode(hasText("Owned plain lyric 40"))
        compose.onNodeWithText("Owned plain lyric 40").assertIsDisplayed()

        lyrics.performScrollToNode(hasText(end))
        compose.onNodeWithText(end).assertIsDisplayed()
        assertUpwardEdgeDragDoesNotEscape(lyrics)
        compose.onNodeWithText(end).assertIsDisplayed()
    }

    @Test
    fun combinedCompanionFiltersKeepTheSameVerticalOwner() {
        val originals = longTrackText("Original", "ORIGINAL_SCROLL_OWNER_END")
        val ipa = longTrackText("IPA", "IPA_SCROLL_OWNER_END")
        val translation = longTrackText("Translation", "TRANSLATION_SCROLL_OWNER_END")
        setLyrics(
            listOf(
                LyricTrack("original", "original", "en", "plain", text = originals),
                LyricTrack(
                    "ipa",
                    "transliteration",
                    "en",
                    "plain",
                    text = ipa,
                    basedOnTrackId = "original",
                ),
                LyricTrack(
                    "translation",
                    "translation",
                    "es",
                    "plain",
                    text = translation,
                    basedOnTrackId = "original"
                )
            )
        )

        compose.onNodeWithTag("lyrics_track_group", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(lyricTrackTabTag("translation")).performScrollTo().performClick()
        compose.onNodeWithTag(lyricTrackTabTag("ipa")).performScrollTo().performClick()
        assertModeOwnsBottomEdge(trackId = null, endText = "TRANSLATION_SCROLL_OWNER_END")
        assertModeOwnsBottomEdge(trackId = "translation", endText = "IPA_SCROLL_OWNER_END")
        assertModeOwnsBottomEdge(trackId = "ipa", endText = "ORIGINAL_SCROLL_OWNER_END")
    }

    private fun assertModeOwnsBottomEdge(trackId: String?, endText: String) {
        if (trackId != null) {
            val tab = compose.onNodeWithTag(lyricTrackTabTag(trackId))
                .assert(
                    hasClickAction() and SemanticsMatcher.expectValue(
                        SemanticsProperties.Role,
                        Role.Checkbox
                    )
                )
            tab
                .performScrollTo()
                .performClick()
            compose.waitForIdle()
        }
        val lyrics = compose.onNodeWithTag("lyrics_content_scroll")
        lyrics.performScrollToNode(hasText(endText))
        compose.onNodeWithText(endText).assertIsDisplayed()
        assertUpwardEdgeDragDoesNotEscape(lyrics)
    }

    private fun assertUpwardEdgeDragDoesNotEscape(
        lyrics: androidx.compose.ui.test.SemanticsNodeInteraction
    ) {
        compose.runOnIdle { pageProbe.reset() }
        lyrics.performTouchInput {
            swipe(
                start = Offset(width * .5f, height * .82f),
                end = Offset(width * .5f, height * .12f),
                durationMillis = 140L
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(
                "The physical drag never entered the ancestor nested-scroll chain",
                pageProbe.preScrollDistance > 0f
            )
            assertEquals(
                "An upward lyric-edge drag escaped into the player/page owner",
                0f,
                pageProbe.escapedScrollDistance,
                .01f
            )
            assertEquals(
                "Unused lyric fling velocity escaped into the player/page owner",
                0f,
                pageProbe.escapedFlingVelocity,
                .01f
            )
            assertEquals(
                "The enclosing player list changed item while Lyrics owned the drag",
                1,
                pageListState.firstVisibleItemIndex
            )
            assertEquals(
                "The enclosing player list moved while Lyrics owned the drag",
                0,
                pageListState.firstVisibleItemScrollOffset
            )
        }
    }

    private fun setLyrics(tracks: List<LyricTrack>) {
        pageProbe = PlayerPageScrollProbe()
        val song = Song(
            id = "lyrics-scroll-owner",
            videoId = "LyricsScrollOwner",
            title = "Lyrics scroll owner",
            artist = "StemWise",
            durationMs = 180_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "LS",
            lyricTracks = tracks,
            mediaRevision = tracks.hashCode().toString()
        )
        compose.setContent {
            StemwiseTheme {
                val positionState = remember { mutableLongStateOf(0L) }
                pageListState = rememberLazyListState(initialFirstVisibleItemIndex = 1)
                LazyColumn(
                    Modifier
                        .size(width = 360.dp, height = 260.dp)
                        .nestedScroll(pageProbe),
                    state = pageListState
                ) {
                    item { Spacer(Modifier.height(80.dp)) }
                    item {
                        LyricsPanel(
                            song = song,
                            positionState = positionState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            onSeek = { true }
                        )
                    }
                    item { Spacer(Modifier.height(500.dp)) }
                }
            }
        }
    }

    private fun longTrackText(prefix: String, end: String): String =
        (1..80).joinToString("\n") { index ->
            if (index == 80) end else "$prefix lyric $index"
        }

    private class PlayerPageScrollProbe : NestedScrollConnection {
        var preScrollDistance = 0f
            private set
        var escapedScrollDistance = 0f
            private set
        var escapedFlingVelocity = 0f
            private set

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                preScrollDistance += abs(available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (source == NestedScrollSource.UserInput) {
                escapedScrollDistance += abs(available.y)
            }
            return available
        }

        override suspend fun onPostFling(
            consumed: Velocity,
            available: Velocity
        ): Velocity {
            escapedFlingVelocity += abs(available.y)
            return available
        }

        fun reset() {
            preScrollDistance = 0f
            escapedScrollDistance = 0f
            escapedFlingVelocity = 0f
        }
    }
}
