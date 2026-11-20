package app.stemwise.ui

import app.stemwise.model.TimedLyricLine

/** Playback timing stays authoritative even when the lyric viewport belongs to the user. */
internal class LyricPlaybackSynchronizer {
    private var trackKey: String? = null
    private var lines: List<TimedLyricLine> = emptyList()
    private var timingIndex = LyricLineTimingIndex(lines)
    private var activeIndex: Int = -1
    private var lastMeaningfulIndex: Int = -1
    private var lastPositionMs: Long? = null
    private var gapDiscontinuity = false

    fun replaceTrack(
        newTrackKey: String,
        newLines: List<TimedLyricLine>,
        positionMs: Long
    ): LyricsActiveIndexUpdate {
        val previousTrack = trackKey
        val previousLines = lines
        val previousIndex = activeIndex
        trackKey = newTrackKey
        lines = newLines
        if (previousLines !== newLines) timingIndex = LyricLineTimingIndex(newLines)
        activeIndex = timingIndex.activeAt(positionMs)
        val continuityIndex = if (previousTrack == newTrackKey && previousLines == newLines) lastMeaningfulIndex else -1
        lastPositionMs = positionMs
        gapDiscontinuity = false
        val cause = when {
            previousTrack == null -> LyricsActiveIndexChangeCause.LyricsLoaded
            previousTrack != newTrackKey -> LyricsActiveIndexChangeCause.TrackChanged
            previousLines != newLines -> LyricsActiveIndexChangeCause.LyricsReplaced
            else -> LyricsActiveIndexChangeCause.OrdinaryPlayback
        }
        val resolved = positionCause(continuityIndex, activeIndex, cause)
        lastMeaningfulIndex = activeIndex.takeIf { it >= 0 } ?: continuityIndex
        return LyricsActiveIndexUpdate(previousIndex, activeIndex, resolved)
    }

    fun updatePosition(
        positionMs: Long,
        cause: LyricsActiveIndexChangeCause = LyricsActiveIndexChangeCause.OrdinaryPlayback
    ): LyricsActiveIndexUpdate? {
        val next = timingIndex.activeAt(positionMs)
        if (lastPositionMs?.let { positionMs < it } == true || cause != LyricsActiveIndexChangeCause.OrdinaryPlayback) {
            gapDiscontinuity = true
        }
        lastPositionMs = positionMs
        if (next == activeIndex) return null
        val resolved = if (gapDiscontinuity && cause == LyricsActiveIndexChangeCause.OrdinaryPlayback)
            LyricsActiveIndexChangeCause.PositionDiscontinuity else positionCause(lastMeaningfulIndex, next, cause)
        val update = LyricsActiveIndexUpdate(activeIndex, next, resolved)
        activeIndex = next
        if (next >= 0) {
            lastMeaningfulIndex = next
            gapDiscontinuity = false
        }
        return update
    }

    private fun positionCause(previous: Int, next: Int, cause: LyricsActiveIndexChangeCause) =
        if (cause == LyricsActiveIndexChangeCause.OrdinaryPlayback && next >= 0 &&
            (next < previous || next > previous + 1)
        ) {
            // A rewind, seek, or conflated multi-cue advance needs a new viewport destination.
            // This does not override a manually detached viewport; the intent engine owns that.
            LyricsActiveIndexChangeCause.PositionDiscontinuity
        } else cause
}

internal data class LyricsActiveIndexUpdate(
    val previousIndex: Int,
    val newIndex: Int,
    val cause: LyricsActiveIndexChangeCause
)

/** Retained for one immutable track so gaps never rescan its entire expired history. */
internal class LyricLineTimingIndex(private val lines: List<TimedLyricLine>) {
    private val timedIndices = lines.indices.filter { lines[it].hasPerformedTiming }.toIntArray()
    private val maximumEnds = LongArray(timedIndices.size)

    init {
        var maximumEnd = Long.MIN_VALUE
        for (index in timedIndices.indices) {
            maximumEnd = maxOf(maximumEnd, checkNotNull(lines[timedIndices[index]].endMs))
            maximumEnds[index] = maximumEnd
        }
    }

    fun activeAt(positionMs: Long): Int = findActiveLyricIndex(lines, positionMs, timedIndices, maximumEnds)
}

/**
 * Half-open cue lookup. Overlaps prefer the greatest start time; equal starts prefer the later
 * input entry. Gaps deliberately return -1 instead of making viewport position part of timing.
 */
internal fun findActiveLyricIndex(lines: List<TimedLyricLine>, positionMs: Long): Int =
    findActiveLyricIndex(lines, positionMs, timedIndices = null, maximumEnds = null)

private fun findActiveLyricIndex(
    lines: List<TimedLyricLine>,
    positionMs: Long,
    timedIndices: IntArray?,
    maximumEnds: LongArray?
): Int {
    var low = 0
    var high = timedIndices?.lastIndex ?: lines.lastIndex
    var candidate = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        var timed = middle
        var line = lines[timedIndices?.get(timed) ?: timed]
        while (timed >= low && !line.hasPerformedTiming) {
            timed--
            if (timed >= low) line = lines[timedIndices?.get(timed) ?: timed]
        }
        if (timed < low) {
            low = middle + 1
        } else if (checkNotNull(line.startMs) <= positionMs) {
            candidate = timed
            low = middle + 1
        } else {
            high = timed - 1
        }
    }
    for (index in candidate downTo 0) {
        // ponytail: overlapping history can still scan; use an interval tree only if that dominates.
        if (maximumEnds != null && maximumEnds[index] <= positionMs) return -1
        val sourceIndex = timedIndices?.get(index) ?: index
        val line = lines[sourceIndex]
        if (line.hasPerformedTiming && positionMs >= checkNotNull(line.startMs) &&
            positionMs < checkNotNull(line.endMs)) return sourceIndex
    }
    return -1
}
