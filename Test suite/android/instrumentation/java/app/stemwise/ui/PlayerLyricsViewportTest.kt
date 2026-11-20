package app.stemwise.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.stemwise.model.LyricTrack
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.TimedLyricLine
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerLyricsViewportTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    private lateinit var renderedPosition: MutableLongState
    private var requestedSeekMs: Long? = null
    private lateinit var extentScope: CoroutineScope
    private lateinit var extentListState: LazyListState

    @Test fun partialLyricsShowTheirReasonWithoutEnablingCompanionTracks() {
        val reason = "52 performed lines retained; uncertain occurrences are unavailable."
        val partial = lyricsSong(listOf(
            LyricTrack("original", "original", "en", "plain",
                text = (1..52).joinToString("\n\n") { "Retained original $it" }),
            LyricTrack("translation", "translation", "es", "plain", text = "Hidden translation",
                basedOnTrackId = "original"),
            LyricTrack("ipa", "transliteration", "en", "plain", text = "Hidden pronunciation",
                basedOnTrackId = "original")
        )).copy(lyricsStatus = "partial", lyricsReason = reason)
        compose.setContent { StemwiseTheme {
            Box(Modifier.size(360.dp, 320.dp)) {
                LyricsPanel(partial, remember { mutableLongStateOf(0L) }, modifier = Modifier.fillMaxSize(),
                    onSeek = { true })
            }
        } }
        compose.onNodeWithText("Partial lyrics").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText("Hidden translation").assertDoesNotExist()
        compose.onNodeWithText("Hidden pronunciation").assertDoesNotExist()
        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToNode(hasText("Retained original 52", substring = true))
        compose.onNodeWithText("Retained original 52", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Partial lyrics").assertIsDisplayed()
    }

    @Test
    fun longPlainLyricsReachTheirFinalLineInsideTheBoundedViewport() {
        val end = "PLAIN_LYRICS_END"
        val text = (1..80).joinToString("\n") { index ->
            if (index == 80) end else "Plain lyric line $index"
        }
        setLyrics(listOf(LyricTrack("original", "original", "en", "plain", text = text)))

        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToNode(hasText(end, substring = true))
        compose.onNodeWithText(end, substring = true).assertIsDisplayed()
    }

    @Test
    fun longCombinedLyricsReachTheFinalCompanionBlock() {
        val end = "COMBINED_TRANSLATION_END"
        val original = (1..36).joinToString("\n") { "Original $it" }
        val ipa = (1..36).joinToString("\n") { "IPA $it" }
        val translation = (1..36).joinToString("\n") { index ->
            if (index == 36) end else "Translation $index"
        }
        setLyrics(
            listOf(
                LyricTrack("original", "original", "en", "plain", text = original),
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

        compose.onNodeWithText("Translation").performClick()
        compose.onNodeWithText("Pronunciation").performClick()
        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToNode(hasText(end, substring = true))
        compose.onNodeWithText(end, substring = true).assertIsDisplayed()
    }

    @Test
    fun originalRemainsVisibleWithoutTheRetiredAllMode() {
        setLyrics(
            listOf(
                LyricTrack("original", "original", "ja", "plain", text = "Original words"),
                LyricTrack(
                    "hepburn",
                    "transliteration",
                    "ja-Latn",
                    "plain",
                    text = "Hepburn words",
                    basedOnTrackId = "original",
                )
            ),
            viewportHeightDp = 220
        )

        compose.onNodeWithText("All").assertDoesNotExist()
        compose.onNodeWithText("Original words").assertIsDisplayed()
        compose.onNodeWithText("Hepburn words").assertDoesNotExist()
    }

    @Test
    fun companionsStartOffAndNeverImpersonateMissingOriginalLyrics() {
        setLyrics(listOf(LyricTrack("translation", "translation", "es", "plain", text = "Translated words")))
        compose.onNodeWithText("Translated words").assertDoesNotExist()
        compose.onNodeWithText("Original lyrics are unavailable. You can show Translation or Pronunciation below.").assertIsDisplayed()
        compose.onNodeWithText("Translation").performClick()
        compose.onNodeWithText("Translated words").assertIsDisplayed()
    }

    @Test
    fun onlyTheCurrentTimedBlockIsHighlightedIncludingSilentGaps() {
        val lines = listOf(
            TimedLyricLine(0L, 1_000L, "Past cue"),
            TimedLyricLine(1_000L, 2_000L, "Current cue"),
            TimedLyricLine(3_000L, 4_000L, "Future cue")
        )
        setLyrics(listOf(LyricTrack("original", "original", "en", "synced", lines = lines)),
            positionMs = 1_500L, viewportHeightDp = 700)
        fun color(text: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        assertEquals(TextPrimary, color("Current cue"))
        assertEquals(TextSecondary, color("Past cue"))
        assertEquals(TextSecondary, color("Future cue"))
        movePositionTo(2_500L)
        lines.forEach { assertEquals(TextSecondary, color(it.text)) }
        movePositionTo(3_500L)
        assertEquals(TextPrimary, color("Future cue"))
        assertEquals(TextSecondary, color("Current cue"))
    }

    @Test
    fun longTimedLyricsReachTheirFinalSeekableLine() {
        val end = "TIMED_LYRICS_END"
        val lines = (0 until 80).map { index ->
            TimedLyricLine(
                startMs = index * 1_000L,
                endMs = (index + 1) * 1_000L,
                text = if (index == 79) end else "Timed lyric ${index + 1}"
            )
        }
        setLyrics(listOf(LyricTrack("original", "original", "en", "synced", lines = lines)))

        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToNode(hasText(end))
        compose.onNodeWithText(end).assertIsDisplayed()
    }

    @Test
    fun tappingATimedLineSeeksToItsExactCanonicalStart() {
        requestedSeekMs = null
        val lines = listOf(
            TimedLyricLine(0L, 1_000L, "First cue"),
            TimedLyricLine(1_000L, 2_000L, "Second cue")
        )
        setLyrics(
            listOf(LyricTrack("original", "original", "en", "synced", lines = lines)),
            onSeek = { requestedSeekMs = it }
        )

        compose.onNodeWithContentDescription("Sync lyrics").assertDoesNotExist()
        compose.onNodeWithContentDescription("Seek to lyric: Second cue").performClick()
        compose.runOnIdle { assertEquals(1_000L, requestedSeekMs) }
    }

    @Test
    fun companionFiltersNeverHideTheCurrentOriginalCue() {
        val tracks = pressureTracks()
        setLyrics(tracks, positionMs = 14_250L, viewportHeightDp = 300)

        compose.onNodeWithText("Original 1.8").assertIsDisplayed()
        compose.onNodeWithText("Pronunciation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Original 1.8").assertIsDisplayed()
        compose.onNodeWithText("Translation").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Original 1.8").assertIsDisplayed()
        movePositionTo(14_400L)
        compose.onNodeWithText("Original 1.9").assertIsDisplayed()
    }

    @Test
    fun continuousViewPlacesOriginalAboveIpaAndTranslation() {
        val tracks = pressureTracks()
        setLyrics(tracks, positionMs = 14_250L, viewportHeightDp = 220)

        compose.onNodeWithText("Pronunciation").performClick()
        compose.onNodeWithText("Translation").performClick()
        val ipa = compose.onNodeWithText("/original 1.8/", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val original = compose.onNodeWithText("Original 1.8", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val translation = compose.onNodeWithText(
            "Translation Original 1.8",
            useUnmergedTree = true
        ).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("Original must render above IPA", original < ipa)
        assertTrue("Translation must render below IPA", ipa < translation)

        movePositionTo(14_400L)
        compose.onNodeWithText("/original 1.9/").assertIsDisplayed()
        compose.onNodeWithText("Original 1.9").assertIsDisplayed()
        compose.onNodeWithText("Translation Original 1.9").assertIsDisplayed()
    }

    @Test
    fun songInfoDoesNotRepeatLyricsOrDurationOwnedByThePlayer() {
        val song = lyricsSong(pressureTracks())
        compose.setContent {
            StemwiseTheme {
                SongInfoDialog(
                    song = song,
                    onDismiss = {},
                    onCopyVideoId = {},
                    onDeleteSong = {}
                )
            }
        }

        compose.onNodeWithText("LYRICS").assertDoesNotExist()
        compose.onNodeWithText("Duration").assertDoesNotExist()
        compose.onAllNodesWithText("Time-synced").assertCountEquals(0)
    }

    @Test
    fun songInfoCopyStaysOpenAndPublishesItsConfirmedState() {
        val song = lyricsSong(pressureTracks())
        var copyRequests = 0
        compose.setContent {
            StemwiseTheme {
                SongInfoDialog(
                    song = song,
                    onDismiss = {},
                    onCopyVideoId = { copyRequests++ },
                    onDeleteSong = {}
                )
            }
        }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Copy YouTube video ID")
            .assertHasClickAction()
            .performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithContentDescription("Copy YouTube video ID", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.runOnIdle { assertEquals(1, copyRequests) }
        compose.onNodeWithContentDescription("Copy YouTube video ID")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Copied"
                )
            )
            .assertIsDisplayed()
        compose.onNodeWithText("Song details").assertIsDisplayed()
    }

    @Test
    fun hidingLongPlainCompanionsRemovesTheirActualScrollExtent() = checkHiddenCompanionExtent(timed = false)

    @Test
    fun hidingLongTimedFallbackCompanionsRemovesTheirActualScrollExtent() = checkHiddenCompanionExtent(timed = true)

    private fun checkHiddenCompanionExtent(timed: Boolean) {
        val original = if (timed) LyricTrack("original", "original", "en", "synced",
            lines = List(120) { TimedLyricLine(it * 1_000L, (it + 1) * 1_000L, "Original $it") })
        else LyricTrack("original", "original", "en", "plain",
            text = List(120) { "Original $it" }.joinToString("\n"))
        // These plain tracks intentionally cannot attach to a timed line: they use the trailing path.
        val companions = listOf(
            LyricTrack("ipa", "transliteration", "mul-fonipa", "plain",
                text = List(120) { "/companion $it/" }.joinToString("\n")),
            LyricTrack("translation", "translation", "es", "plain",
                text = List(120) { "Translation $it" }.joinToString("\n")))
        val current = mutableStateOf(lyricsSong(listOf(original) + companions))
        val fixtureHeight = mutableStateOf(300.dp)
        lateinit var density: Density
        compose.setContent {
            StemwiseTheme {
                val position = remember { mutableLongStateOf(0L) }
                extentScope = rememberCoroutineScope()
                extentListState = rememberLazyListState()
                density = LocalDensity.current
                Box(Modifier.size(width = 360.dp, height = fixtureHeight.value)) {
                    LyricsPanel(current.value, position, Modifier.fillMaxSize(), scrollState = extentListState, onSeek = { true })
                }
            }
        }
        listOf(setOf("ipa"), setOf("translation"), setOf("ipa", "translation")).forEachIndexed { index, hidden ->
            compose.runOnIdle {
                fixtureHeight.value = 300.dp
                current.value = lyricsSong(listOf(original) + companions).copy(id = "hidden-$index")
            }
            if ("ipa" !in hidden) compose.onNodeWithText("Pronunciation").performClick()
            if ("translation" !in hidden) compose.onNodeWithText("Translation").performClick()
            compose.waitForIdle() // Exit animation must finish before the lazy items leave the model.
            val hiddenViewport = compose.onNodeWithTag("lyrics_content_scroll").fetchSemanticsNode().boundsInRoot
            val hiddenExtent = measuredContentExtent()
            compose.runOnIdle {
                current.value = lyricsSong(listOf(original) + companions.filter { it.id !in hidden })
                    .copy(id = "absent-$index")
            }
            if ("ipa" !in hidden) compose.onNodeWithText("Pronunciation").performClick()
            if ("translation" !in hidden) compose.onNodeWithText("Translation").performClick()
            val absentViewport = compose.onNodeWithTag("lyrics_content_scroll").fetchSemanticsNode().boundsInRoot
            // With both companions absent the filter row disappears. Compare equal actual viewports,
            // including the timed list's viewport-dependent bottom padding.
            compose.runOnIdle {
                fixtureHeight.value -= with(density) { (absentViewport.height - hiddenViewport.height).toDp() }
            }
            val normalizedViewport = compose.onNodeWithTag("lyrics_content_scroll").fetchSemanticsNode().boundsInRoot
            assertEquals("Comparison viewport width", hiddenViewport.width, normalizedViewport.width, 0f)
            assertEquals("Comparison viewport height", hiddenViewport.height, normalizedViewport.height, 0f)
            val absentExtent = measuredContentExtent()
            assertEquals("Hidden $hidden must occupy exactly the same rendered extent and keys as absent tracks", absentExtent, hiddenExtent)
        }
    }

    private data class MeasuredLyricRow(val key: Any, val offsetPx: Int, val sizePx: Int)
    private data class MeasuredLyricsExtent(
        val itemCount: Int, val spacingPx: Int, val paddingPx: Pair<Int, Int>,
        val startRows: List<MeasuredLyricRow>, val endRows: List<MeasuredLyricRow>, val endGapPx: Int
    )

    /** Measure actual laid-out rows and endpoint geometry, excluding animation/nested-scroll sums. */
    private fun measuredContentExtent(): MeasuredLyricsExtent {
        val scroll = compose.onNodeWithTag("lyrics_content_scroll")
        var measurement: Deferred<MeasuredLyricsExtent>? = null
        scroll.performTouchInput { down(center) } // Retire timed follow while this geometry probe owns the viewport.
        try {
            compose.runOnIdle {
                measurement = extentScope.async {
                    extentListState.scrollToItem(0)
                    assertEquals(0, extentListState.firstVisibleItemIndex)
                    assertEquals(0, extentListState.firstVisibleItemScrollOffset)
                    assertFalse(extentListState.canScrollBackward)
                    val start = extentListState.layoutInfo
                    val count = start.totalItemsCount
                    val startRows = start.visibleItemsInfo.map { MeasuredLyricRow(it.key, it.offset, it.size) }
                    extentListState.scrollToItem(count - 1)
                    assertFalse("List did not reach its actual end", extentListState.canScrollForward)
                    assertEquals(0f, extentListState.scrollBy(1f), 0f)
                    val end = extentListState.layoutInfo
                    assertEquals("Lazy item model changed during settled measurement", count, end.totalItemsCount)
                    val last = end.visibleItemsInfo.single { it.index == count - 1 }
                    MeasuredLyricsExtent(count, end.mainAxisItemSpacing,
                        end.beforeContentPadding to end.afterContentPadding, startRows,
                        end.visibleItemsInfo.map { MeasuredLyricRow(it.key, it.offset, it.size) },
                        end.viewportEndOffset - last.offset - last.size)
                }
            }
            compose.waitUntil(5_000) { measurement?.isCompleted == true }
            return runBlocking { withTimeout(5_000) { checkNotNull(measurement).await() } }
        } finally {
            measurement?.cancel()
            scroll.performTouchInput { cancel() }
            compose.waitForIdle()
        }
    }

    private fun pressureTracks(): List<LyricTrack> {
        val original = LyricTrack(
            id = "original",
            kind = "original",
            language = null,
            format = "timed",
            lines = List(10) { index ->
                TimedLyricLine(
                    startMs = index * 1_800L,
                    endMs = (index + 1) * 1_800L,
                    text = "Original 1.${index + 1}"
                )
            }
        )
        return listOf(
            original,
            LyricTrack(
                id = "ipa",
                kind = "transliteration",
                language = "mul-fonipa",
                format = "plain",
                text = original.lines.joinToString("\n") { "/${it.text.lowercase()}/" },
                basedOnTrackId = original.id
            ),
            LyricTrack(
                id = "translation",
                kind = "translation",
                language = null,
                format = "plain",
                text = original.lines.joinToString("\n") { "Translation ${it.text}" },
                basedOnTrackId = original.id
            )
        )
    }

    private fun lyricsSong(tracks: List<LyricTrack>) = Song(
            id = "lyrics-viewport",
            videoId = "LyricsView1",
            title = "Lyrics viewport fixture",
            artist = "StemWise",
            durationMs = 120_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "LV",
            lyricTracks = tracks,
            mediaRevision = tracks.hashCode().toString()
        )

    private fun setLyrics(
        tracks: List<LyricTrack>,
        positionMs: Long = 0L,
        viewportHeightDp: Int = 149,
        onSeek: (Long) -> Unit = {}
    ) {
        val song = lyricsSong(tracks)
        compose.setContent {
            StemwiseTheme {
                val position = remember { mutableLongStateOf(positionMs) }
                renderedPosition = position
                Box(Modifier.size(width = 360.dp, height = viewportHeightDp.dp)) {
                    LyricsPanel(
                        song = song,
                        positionState = position,
                        modifier = Modifier.fillMaxSize(),
                        onSeek = { onSeek(it); true }
                    )
                }
            }
        }
    }

    private fun movePositionTo(positionMs: Long) {
        compose.runOnIdle { renderedPosition.longValue = positionMs }
        compose.waitForIdle()
    }
}
