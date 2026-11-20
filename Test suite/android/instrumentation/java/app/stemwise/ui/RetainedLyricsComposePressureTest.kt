package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import app.stemwise.model.LyricTrack
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.TimedLyricLine
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Rendering/state pressure for the one Compose subtree intentionally retained above the native
 * player surface. Assertions cover visible ownership and exact seek state, not just data counts.
 */
class RetainedLyricsComposePressureTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    private lateinit var renderedSong: MutableState<Song>
    private lateinit var renderedPosition: MutableLongState
    private val seekEvents = mutableListOf<Long>()

    @Test
    fun rapidModeBurstOnVeryLongUntimedTracksKeepsOnlyTheLatestLazySurface() {
        val lineCount = 2_048
        setLyrics(
            song(
                revision = "plain-pressure",
                tracks = listOf(
                    plainTrack("original", "original", "original", lineCount),
                    plainTrack("ipa", "transliteration", "ipa", lineCount, scheme = "IPA"),
                    plainTrack("translation", "translation", "translation", lineCount)
                )
            ),
            viewportHeightDp = 220
        )

        tab("Translation").assertIsNotSelected().performClick()
        tab("Pronunciation").assertIsNotSelected().performClick()
        compose.mainClock.autoAdvance = false
        try {
            tab("Translation").performClick()
            tab("Pronunciation").performClick()
            tab("Translation").performClick()
            tab("Pronunciation").performClick()
            tab("Translation").performClick()
            compose.mainClock.advanceTimeBy(1_000L)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()

        tab("Pronunciation").assertIsSelected()
        tab("Translation").assertIsNotSelected()
        compose.onNodeWithText("original-0").assertIsDisplayed()
        compose.onNodeWithText("translation-0").assertDoesNotExist()
        // A retained LazyColumn must not eagerly materialize thousands of text/selection nodes.
        compose.onNodeWithText("ipa-1024").assertDoesNotExist()

        val finalLine = "ipa-${lineCount - 1}"
        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToKey("ipa:plain:${lineCount - 1}")
        compose.onNodeWithText(finalLine).assertIsDisplayed()
        compose.onNodeWithText("ipa-0").assertDoesNotExist()
    }

    @Test
    fun rapidSyncAndSeekUpdatesConvergeAcrossIpaTranslationAndAllModes() {
        val original = timedOriginal(4_096)
        val ipa = alignedCompanion("ipa", "transliteration", "ipa", original, "IPA")
        val translation = alignedCompanion(
            "translation",
            "translation",
            "translation",
            original
        )
        setLyrics(
            song("timed-pressure", listOf(original, ipa, translation)),
            positionMs = checkNotNull(original.lines[3].startMs),
            viewportHeightDp = 220
        )

        tab("Translation").assertIsNotSelected().performClick()
        tab("Pronunciation").assertIsNotSelected().performClick()
        // The position publisher may conflate many playback updates before the next frame. The
        // visible cue and automatic list anchor must resolve to the final value in either direction.
        tab("Translation").performClick()
        rapidPositionBurst(original, 3_700, 127)
        compose.onNodeWithContentDescription("Current lyric: original-127").assertIsDisplayed()
        compose.onNodeWithText("ipa-127").assertIsDisplayed()
        compose.onNodeWithText("translation-127").assertDoesNotExist()

        tab("Pronunciation").performClick()
        tab("Translation").performClick()
        rapidPositionBurst(original, 211, 3_333)
        compose.onNodeWithContentDescription("Current lyric: original-3333")
            .assertIsDisplayed()
        compose.onNodeWithText("translation-3333").assertIsDisplayed()
        compose.onNodeWithText("ipa-3333").assertDoesNotExist()

        val seekIndex = 1_777
        val seekText = "original-$seekIndex"
        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToKey("${original.lines[seekIndex].startMs}:$seekIndex")
        compose.onNodeWithContentDescription("Seek to lyric: $seekText").performClick()
        compose.waitForIdle()
        assertEquals(listOf(original.lines[seekIndex].startMs), seekEvents)
        compose.onNodeWithContentDescription("Current lyric: $seekText").assertIsDisplayed()

        tab("Pronunciation").performClick()
        rapidPositionBurst(original, 4_000, 2_048)
        compose.onNodeWithContentDescription("Current lyric: original-2048").assertIsDisplayed()
        compose.onNodeWithText("ipa-2048").assertIsDisplayed()
        compose.onNodeWithText("translation-2048").assertIsDisplayed()
    }

    @Test
    fun missingTrackPermutationsAndRevisionUpdatesRemoveEveryStaleSurface() {
        val empty = song("missing-0", emptyList())
        setLyrics(empty, viewportHeightDp = 220)
        compose.onNodeWithText("No lyrics to display").assertIsDisplayed()
        compose.onNodeWithTag("lyrics_content_scroll").assertDoesNotExist()

        val originalPlain = plainTrack("original", "original", "plain-original", 1_024)
        replaceSong(song("missing-1", listOf(originalPlain)))
        compose.onNodeWithText("No lyrics to display").assertDoesNotExist()
        tab("All").assertDoesNotExist()
        tab("Pronunciation").assertDoesNotExist()
        tab("Translation").assertDoesNotExist()
        compose.onNodeWithTag("lyrics_content_scroll")
            .performScrollToKey("original:plain:1023")
        compose.onNodeWithText("plain-original-1023").assertIsDisplayed()

        val timed = timedOriginal(512)
        val ipa = alignedCompanion("ipa", "transliteration", "only-ipa", timed, "IPA")
        replaceSong(song("missing-2", listOf(timed, ipa)), checkNotNull(timed.lines[400].startMs))
        compose.onNodeWithText("plain-original-1023").assertDoesNotExist()
        tab("Pronunciation").assertIsDisplayed().assertIsNotSelected().performClick()
        tab("Translation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Current lyric: original-400").assertIsDisplayed()
        compose.onNodeWithText("only-ipa-400").assertIsDisplayed()

        val translation = alignedCompanion(
            "translation",
            "translation",
            "only-translation",
            timed
        )
        replaceSong(song("missing-3", listOf(timed, translation)), checkNotNull(timed.lines[255].startMs))
        tab("Pronunciation").assertDoesNotExist()
        tab("Translation").assertIsDisplayed().assertIsNotSelected().performClick()
        compose.onNodeWithText("only-ipa-400").assertDoesNotExist()
        compose.onNodeWithContentDescription("Current lyric: original-255").assertIsDisplayed()
        compose.onNodeWithText("only-translation-255").assertIsDisplayed()

        replaceSong(empty.copy(mediaRevision = "missing-4"))
        tab("All").assertDoesNotExist()
        tab("Translation").assertDoesNotExist()
        compose.onNodeWithText("only-translation-255").assertDoesNotExist()
        compose.onNodeWithText("No lyrics to display").assertIsDisplayed()
    }

    private fun rapidPositionBurst(original: LyricTrack, firstIndex: Int, finalIndex: Int) {
        compose.runOnIdle {
            repeat(512) { iteration ->
                val index = (firstIndex + iteration * 97) % original.lines.size
                renderedPosition.longValue = checkNotNull(original.lines[index].startMs)
            }
            renderedPosition.longValue = checkNotNull(original.lines[finalIndex].startMs)
        }
        compose.waitForIdle()
    }

    private fun setLyrics(song: Song, positionMs: Long = 0L, viewportHeightDp: Int) {
        seekEvents.clear()
        compose.setContent {
            StemwiseTheme {
                val songState = remember { mutableStateOf(song) }
                val positionState = remember { mutableLongStateOf(positionMs) }
                renderedSong = songState
                renderedPosition = positionState
                Box(Modifier.size(width = 360.dp, height = viewportHeightDp.dp)) {
                    LyricsPanel(
                        song = songState.value,
                        positionState = positionState,
                        modifier = Modifier.fillMaxSize(),
                        onSeek = { target ->
                            seekEvents += target
                            positionState.longValue = target
                            true
                        }
                    )
                }
            }
        }
    }

    private fun replaceSong(song: Song, positionMs: Long = 0L) {
        compose.runOnIdle {
            renderedPosition.longValue = positionMs
            renderedSong.value = song
        }
        compose.waitForIdle()
    }

    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    )

    private fun plainTrack(
        id: String,
        kind: String,
        prefix: String,
        count: Int,
        scheme: String? = null
    ): LyricTrack = LyricTrack(
        id = id,
        kind = kind,
        format = "plain",
        text = (0 until count).joinToString("\n") { "$prefix-$it" },
        basedOnTrackId = if (id == "original") null else "original",
        language = if (scheme.equals("ipa", ignoreCase = true)) "und-fonipa" else "und-Latn"
    )

    private fun timedOriginal(count: Int): LyricTrack = LyricTrack(
        id = "original",
        kind = "original",
        format = "timed",
        lines = List(count) { index ->
            TimedLyricLine(
                startMs = index * 400L,
                endMs = (index + 1) * 400L,
                text = "original-$index"
            )
        }
    )

    private fun alignedCompanion(
        id: String,
        kind: String,
        prefix: String,
        original: LyricTrack,
        scheme: String? = null
    ): LyricTrack = LyricTrack(
        id = id,
        kind = kind,
        format = "plain",
        text = original.lines.indices.joinToString("\n") { "$prefix-$it" },
        basedOnTrackId = original.id,
        language = if (scheme.equals("ipa", ignoreCase = true)) "und-fonipa" else "und-Latn"
    )

    private fun song(revision: String, tracks: List<LyricTrack>): Song = Song(
        id = "retained-lyrics-compose",
        videoId = "RetainCompose1",
        title = "Retained lyrics Compose fixture",
        artist = "StemWise",
        durationMs = 2_000_000L,
        state = SongState.READY,
        accent = Color.Cyan,
        artworkGlyph = "RC",
        lyricTracks = tracks,
        mediaRevision = revision
    )
}
