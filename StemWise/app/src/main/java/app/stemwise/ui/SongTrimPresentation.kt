package app.stemwise.ui

import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.MeterSegment
import app.stemwise.analysis.SongSection
import app.stemwise.analysis.WaveformWindow
import app.stemwise.model.LyricTrack
import kotlin.math.ceil
import kotlin.math.floor

internal fun trimWaveformPeaks(peaks: List<Float>, sourceDurationMs: Long, startMs: Long, endMs: Long): List<Float> {
    if (peaks.isEmpty() || sourceDurationMs <= 0L || endMs <= startMs) return emptyList()
    if (startMs == 0L && endMs >= sourceDurationMs) return peaks
    val count = ceil(peaks.size.toDouble() * (endMs - startMs) / sourceDurationMs).toInt().coerceAtLeast(1)
    return List(count) { index ->
        val firstTime = startMs + (endMs - startMs).toDouble() * index / count
        val lastTime = startMs + (endMs - startMs).toDouble() * (index + 1) / count
        val first = floor(firstTime * peaks.size / sourceDurationMs).toInt().coerceIn(peaks.indices)
        val last = (ceil(lastTime * peaks.size / sourceDurationMs).toInt() - 1).coerceIn(first, peaks.lastIndex)
        var peak = 0f
        for (sourceIndex in first..last) peak = maxOf(peak, peaks[sourceIndex])
        peak
    }
}

internal fun trimSongSections(sections: List<SongSection>, startMs: Long, endMs: Long): List<SongSection> =
    sections.mapNotNull { section ->
        val start = maxOf(startMs, section.startMs)
        val end = minOf(endMs, section.endMs)
        section.copy(startMs = start - startMs, endMs = end - startMs).takeIf { end > start }
    }

internal fun trimBeatEvents(beats: List<BeatEvent>, startMs: Long, endMs: Long): List<BeatEvent> =
    beats.filter { it.timeMs >= startMs && it.timeMs < endMs }.map { it.copy(timeMs = it.timeMs - startMs) }

internal fun trimMeterSegments(segments: List<MeterSegment>, startMs: Long, endMs: Long): List<MeterSegment> =
    segments.mapNotNull { segment ->
        val start = maxOf(startMs, segment.startMs)
        val end = minOf(endMs, segment.endMs)
        segment.copy(startMs = start - startMs, endMs = end - startMs).takeIf { end > start }
    }

internal fun trimLyricTracks(tracks: List<LyricTrack>, startMs: Long, endMs: Long): List<LyricTrack> {
    val resolved = tracks.map { it.copy(lines = effectiveTimedLyricsLines(it, tracks)) }
    return resolved.map { track ->
        val original = resolved.singleOrNull { it.id == track.basedOnTrackId && it.kind == "original" }
        val projected = track.lines.mapIndexedNotNull clipLine@{ index, line ->
            // Companions retain the referenced occurrence indices, including plain aligned text.
            val occurrence = original?.lines?.takeIf { it.size == track.lines.size }?.get(index) ?: line
            if (occurrence.startMs?.let { it >= endMs } == true || occurrence.endMs?.let { it <= startMs } == true) return@clipLine null
            val start = line.startMs
            val end = line.endMs
            if (start == null || end == null) return@clipLine line
            if (end <= startMs || start >= endMs) return@clipLine null
            line.copy(
                startMs = maxOf(start, startMs) - startMs,
                endMs = minOf(end, endMs) - startMs,
                words = line.words.mapNotNull clipWord@{ word ->
                    val wordStart = word.startMs
                    val wordEnd = word.endMs
                    if (wordStart == null || wordEnd == null) return@clipWord word
                    if (wordEnd <= startMs || wordStart >= endMs) return@clipWord null
                    word.copy(startMs = maxOf(wordStart, startMs) - startMs, endMs = minOf(wordEnd, endMs) - startMs)
                }
            )
        }
        track.copy(lines = projected, text = if (track.lines.isNotEmpty()) projected.joinToString("\n") { it.text } else track.text)
    }
}

internal fun trimFineWindow(window: WaveformWindow, startMs: Long, endMs: Long): WaveformWindow? {
    if (window.frameDurationMs <= 0L) return null
    // Retain only complete source frames; a partly excluded frame must not paint outside the clip.
    val first = ceil((startMs - window.startMs).toDouble() / window.frameDurationMs).toInt().coerceIn(0, window.peaks.size)
    val last = floor((endMs - window.startMs).toDouble() / window.frameDurationMs).toInt().coerceIn(first, window.peaks.size)
    if (first == last) return null
    val shiftedStart = window.startMs + first * window.frameDurationMs - startMs
    return WaveformWindow(shiftedStart, shiftedStart + (last - first) * window.frameDurationMs,
        window.frameDurationMs, window.peaks.subList(first, last))
}
