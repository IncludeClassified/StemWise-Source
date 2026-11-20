package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.model.LyricTrack
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Host-side pressure coverage for the pure projections consumed by the retained Compose lyrics
 * surface. These tests deliberately assert exact cue/content ownership under scale and rapid
 * updates; they are not fixture-count smoke tests.
 */
class RetainedLyricsSurfacePressurePolicyTest {
    @Test
    fun twentyThousandTimedCuesKeepExactHalfOpenBoundariesUnderSeekPressure() {
        var nextStart = 137L
        val lines = List(20_000) { index ->
            val duration = 31L + index % 17L
            val start = nextStart
            val end = start + duration
            nextStart = end + if (index % 3 == 0) 11L else 0L
            TimedLyricLine(start, end, "cue-$index")
        }

        assertEquals(-1, findActiveLyricIndex(lines, requireNotNull(lines.first().startMs) - 1L))
        lines.forEachIndexed { index, line ->
            val end = requireNotNull(line.endMs)
            assertEquals("start of cue $index", index, findActiveLyricIndex(lines, requireNotNull(line.startMs)))
            assertEquals("tail of cue $index", index, findActiveLyricIndex(lines, end - 1L))
            val next = lines.getOrNull(index + 1)
            val expectedAtEnd = if (next?.startMs == end) index + 1 else -1
            assertEquals("exclusive end of cue $index", expectedAtEnd, findActiveLyricIndex(lines, end))
        }

        // Jump backward and forward through the whole projection. This catches a stale linear
        // cursor implementation as well as binary-search boundary regressions.
        repeat(60_000) { iteration ->
            val index = (iteration * 7_919) % lines.size
            val line = lines[index]
            val start = requireNotNull(line.startMs)
            val midpoint = start + (requireNotNull(line.endMs) - start) / 2L
            assertEquals("rapid seek $iteration", index, findActiveLyricIndex(lines, midpoint))
        }
        assertEquals(-1, findActiveLyricIndex(lines, requireNotNull(lines.last().endMs)))
    }

    @Test
    fun longIpaAndTranslationProjectIndependentlyAndTogetherWithoutDrift() {
        val original = timedOriginal(4_096)
        val ipa = syncedCompanion(
            id = "ipa",
            kind = "transliteration",
            prefix = "ipa",
            original = original,
        )
        val translation = syncedCompanion(
            id = "translation",
            kind = "translation",
            prefix = "translation",
            original = original
        )
        val allTracks = listOf(original, ipa, translation)
        val ipaProjection = effectiveTimedLyricsLines(ipa, allTracks)
        val translationProjection = effectiveTimedLyricsLines(translation, allTracks)

        assertEquals(original.lines.size, ipaProjection.size)
        assertEquals(original.lines.size, translationProjection.size)
        assertEquals("ipa-0", ipaProjection.first().text)
        assertEquals("ipa-4095", ipaProjection.last().text)
        assertEquals("translation-0", translationProjection.first().text)
        assertEquals("translation-4095", translationProjection.last().text)
        listOf(original.lines, ipaProjection, translationProjection).forEachIndexed {
                projectionIndex, projection ->
            repeat(16_384) { iteration ->
                val index = (iteration * 2_053) % projection.size
                val expectedTiming = original.lines[index]
                val projected = projection[index]
                assertEquals("projection $projectionIndex start $index", expectedTiming.startMs, projected.startMs)
                assertEquals("projection $projectionIndex end $index", expectedTiming.endMs, projected.endMs)
                assertEquals(
                    "projection $projectionIndex active $index",
                    index,
                    findActiveLyricIndex(projection, requireNotNull(projected.startMs))
                )
            }
        }
    }

    @Test
    fun missingOrMismatchedCompanionsNeverBorrowTheWrongCue() {
        val original = timedOriginal(1_024)
        val ipa = syncedCompanion(
            id = "ipa",
            kind = "transliteration",
            prefix = "ipa",
            original = original,
            scheme = "phonetic"
        )
        val shortTranslation = LyricTrack(
            id = "translation",
            kind = "translation",
            format = "plain",
            text = (0 until original.lines.lastIndex).joinToString("\n") { "translation-$it" },
            basedOnTrackId = original.id
        )
        val orphanIpa = LyricTrack(
            id = "orphan-ipa",
            kind = "transliteration",
            format = "plain",
            text = "orphan",
            basedOnTrackId = "missing-original",
        )
        val tracks = listOf(shortTranslation, original, orphanIpa, ipa)

        assertEquals(original.lines.size, effectiveTimedLyricsLines(ipa, tracks).size)
        assertEquals(emptyList<TimedLyricLine>(), effectiveTimedLyricsLines(shortTranslation, tracks))
        assertEquals(emptyList<TimedLyricLine>(), effectiveTimedLyricsLines(orphanIpa, tracks))
        assertNull(
            companionTextForPrimaryLine(
                track = shortTranslation,
                primaryIndex = 700
            )
        )
        assertEquals(
            listOf("original", "orphan-ipa", "ipa", "translation"),
            plainLyricsDisplayOrder(tracks).map(LyricTrack::id)
        )
    }

    @Test
    fun authoritativeTracksResolveWithoutReconstruction() {
        val empty = baseSong()
        assertEquals(emptyList<LyricTrack>(), resolvedLyricTracks(empty))

        val authoritative = LyricTrack(
            id = "imported-translation",
            kind = "translation",
            format = "plain",
            text = "lossless imported value"
        )
        val withTracks = empty.copy(
            lyricTracks = listOf(authoritative)
        )
        assertSame(authoritative, resolvedLyricTracks(withTracks).single())
    }

    private fun timedOriginal(count: Int): LyricTrack {
        var cursor = 250L
        val lines = List(count) { index ->
            val start = cursor
            val end = start + 73L + index % 19L
            cursor = end
            TimedLyricLine(start, end, "original-$index")
        }
        return LyricTrack(
            id = "original",
            kind = "original",
            format = "timed",
            lines = lines
        )
    }

    private fun syncedCompanion(
        id: String,
        kind: String,
        prefix: String,
        original: LyricTrack,
        scheme: String? = null
    ): LyricTrack = LyricTrack(
        id = id,
        kind = kind,
        format = "synced",
        lines = original.lines.mapIndexed { index, line -> line.copy(text = "$prefix-$index") },
        basedOnTrackId = original.id,
        language = if (scheme.equals("ipa", ignoreCase = true)) "und-fonipa" else "und-Latn"
    )

    private fun baseSong(): Song = Song(
        id = "retained-lyrics-host",
        videoId = "RetainHost1",
        title = "Retained lyrics host fixture",
        artist = "StemWise",
        durationMs = 1_000_000L,
        state = SongState.READY,
        accent = Color.Cyan,
        artworkGlyph = "RL"
    )
}
