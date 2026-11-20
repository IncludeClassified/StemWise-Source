package app.stemwise.ui

import app.stemwise.analysis.*
import app.stemwise.model.*
import org.junit.Assert.*
import org.junit.Test

class SongTrimPresentationTest {
    @Test fun retainedRangeCropsAndRebasesEachPresentationConsumer() {
        assertEquals(listOf(.2f, .8f), trimWaveformPeaks(listOf(.9f, .2f, .8f, 1f), 4_000L, 1_000L, 3_000L))
        assertEquals(listOf(SongSection("Verse", 0L, 800L), SongSection("Chorus", 800L, 2_000L)),
            trimSongSections(listOf(SongSection("Intro", 0L, 500L), SongSection("Verse", 500L, 1_800L),
                SongSection("Chorus", 1_800L, 4_000L)), 1_000L, 3_000L))
        assertEquals(listOf(BeatEvent(0L, true), BeatEvent(1_000L, false)),
            trimBeatEvents(listOf(BeatEvent(500L, false), BeatEvent(1_000L, true), BeatEvent(2_000L, false), BeatEvent(3_000L, true)), 1_000L, 3_000L))
        assertEquals(listOf(MeterSegment(0L, 2_000L, 4, 4)),
            trimMeterSegments(listOf(MeterSegment(0L, 4_000L, 4, 4)), 1_000L, 3_000L))
    }

    @Test fun lyricsKeepTextAndAuthorityWhileExcludingOutsideTimedOccurrences() {
        val outside = TimedLyricLine(0L, 500L, "Earlier")
        val crossing = TimedLyricLine(500L, 2_500L, "One two three", words = listOf(
            TimedLyricWord(0, 3, 500L, 900L), TimedLyricWord(4, 7, 900L, 1_500L),
            TimedLyricWord(8, 13, 1_500L, 2_500L)))
        val untimed = TimedLyricLine(null, null, "Unconfirmed", timingStatus = LyricTimingStatus.NONE,
            occurrenceStatus = "unknown")
        val track = LyricTrack("original", "original", format = "timed", lines = listOf(outside, crossing, untimed))
        val projected = trimLyricTracks(listOf(track), 1_000L, 2_000L).single().lines
        assertEquals(2, projected.size)
        assertEquals("One two three", projected[0].text)
        assertEquals(0L, projected[0].startMs)
        assertEquals(1_000L, projected[0].endMs)
        assertEquals(listOf(0L, 500L), projected[0].words.map { it.startMs })
        assertEquals(listOf(500L, 1_000L), projected[0].words.map { it.endMs })
        assertEquals(untimed, projected[1])
    }

    @Test fun fineWindowUsesSourceFramesAndNeverPaintsExcludedAudio() {
        val source = WaveformWindow(990L, 1_030L, 5L, (0..7).map { it / 10f })
        val result = trimFineWindow(source, 1_002L, 1_023L)!!
        assertEquals(3L, result.startMs)
        assertEquals(18L, result.endMs)
        assertEquals(listOf(.3f, .4f, .5f), result.peaks)
        assertNull(trimFineWindow(source, 1_050L, 1_100L))
    }

    @Test fun plainCompanionKeepsTheRetainedOriginalOccurrenceAndExcludedTextCannotReturnAsPlainFallback() {
        val original = LyricTrack("original", "original", format = "timed", text = "Early\nMiddle\nLate", lines = listOf(
            TimedLyricLine(0L, 1_000L, "Early"), TimedLyricLine(1_000L, 2_000L, "Middle"),
            TimedLyricLine(2_000L, 3_000L, "Late")))
        val companion = LyricTrack("translation", "translation", format = "plain", text = "First\nSecond\nThird", basedOnTrackId = "original")
        val result = trimLyricTracks(listOf(original, companion), 1_000L, 2_000L)
        assertEquals(listOf("Middle"), result[0].lines.map { it.text })
        assertEquals("Middle", result[0].text)
        assertEquals("Second", result[1].text)
        assertEquals("Second", companionTextForPrimaryLine(result[1], 0))
        assertEquals(0L, effectiveTimedLyricsLines(result[1], result).single().startMs)
        val absent = trimLyricTracks(listOf(original), 4_000L, 5_000L).single()
        assertTrue(absent.lines.isEmpty())
        assertEquals("", absent.text)
    }
}
