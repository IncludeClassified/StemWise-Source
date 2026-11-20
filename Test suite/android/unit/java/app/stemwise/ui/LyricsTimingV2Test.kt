package app.stemwise.ui

import app.stemwise.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimingV2Test {
    @Test
    fun retainedIndexPreservesOverlapsEqualStartsAndHalfOpenBoundaries() {
        val lines = List(128) { index ->
            val start = (index / 3) * 20L
            TimedLyricLine(start, start + (index * 17 % 500) + 1L, "line-$index")
        }
        val timing = LyricLineTimingIndex(lines)
        for (position in -1L..1_500L) {
            val expected = lines.indexOfLast { position >= requireNotNull(it.startMs) && position < requireNotNull(it.endMs) }
            assertEquals("position=$position", expected, timing.activeAt(position))
        }
    }

    @Test
    fun longTrackGapsUseLogarithmicReadsInBothTimingAndViewportSynchronization() {
        val source = List(8_192) { index ->
            TimedLyricLine(index * 1_000L, index * 1_000L + 100L, "line-$index")
        }
        var reads = 0
        val counted = object : AbstractList<TimedLyricLine>() {
            override val size: Int get() = source.size
            override fun get(index: Int): TimedLyricLine {
                reads++
                return source[index]
            }
        }
        val timing = LyricLineTimingIndex(counted)
        val synchronizer = LyricPlaybackSynchronizer()
        synchronizer.replaceTrack("long-track", counted, 0L)
        reads = 0
        repeat(100) { sample ->
            val position = (8_191 - sample * 37) * 1_000L + 500L
            assertEquals(-1, timing.activeAt(position))
            synchronizer.updatePosition(position)?.let { assertEquals(-1, it.newIndex) }
        }
        assertTrue("Gap lookups reread $reads cues", reads <= 100 * 2 * 15)
        val indexedReads = reads
        reads = 0
        repeat(100) { sample ->
            val position = (8_191 - sample * 37) * 1_000L + 500L
            repeat(2) { assertEquals(-1, findActiveLyricIndex(counted, position)) }
        }
        println("8192-cue gaps, 200 lookups: indexed reads=$indexedReads, full reads=$reads")
    }

    @Test
    fun replacingSameTrackRebuildsItsEndTimeIndex() {
        val synchronizer = LyricPlaybackSynchronizer()
        synchronizer.replaceTrack("song", listOf(TimedLyricLine(0, 100, "old")), 150)
        val replaced = synchronizer.replaceTrack("song", listOf(TimedLyricLine(0, 200, "new")), 150)
        assertEquals(0, replaced.newIndex)
        assertEquals(LyricsActiveIndexChangeCause.LyricsReplaced, replaced.cause)
        assertEquals(-1, synchronizer.updatePosition(200)?.newIndex)
    }

    @Test
    fun activeLineUsesHalfOpenIntervalsAndFallsBackAcrossOverlaps() {
        val lines = listOf(
            TimedLyricLine(0, 1_000, "long"),
            TimedLyricLine(200, 400, "short"),
            TimedLyricLine(200, 350, "same-start-later")
        )
        assertEquals(2, findActiveLyricIndex(lines, 200))
        assertEquals(1, findActiveLyricIndex(lines, 350))
        assertEquals(0, findActiveLyricIndex(lines, 400))
        assertEquals(-1, findActiveLyricIndex(lines, 1_000))
    }

}
