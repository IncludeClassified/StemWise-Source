package app.stemwise.model

import androidx.compose.ui.graphics.Color

enum class SongState {
    DISCOVERED, AWAITING_PC,
    READY, NEEDS_ATTENTION
}

enum class JobKind { SEPARATION, LIBRARY }
enum class JobState { WAITING, RUNNING, COMPLETE, NEEDS_ATTENTION }

enum class LyricTimingStatus { SUPPORTED, ESTIMATED, NONE }

data class TimedLyricLine(
    val startMs: Long?,
    val endMs: Long?,
    val text: String,
    val words: List<TimedLyricWord> = emptyList(),
    val timingStatus: LyricTimingStatus = LyricTimingStatus.SUPPORTED,
    val contentStatus: String = "admitted",
    val occurrenceStatus: String = "supported",
    val timingBasis: String? = null,
    val timingReason: String? = null
) {
    val hasPerformedTiming: Boolean get() = contentStatus == "admitted" && occurrenceStatus == "supported" &&
        timingStatus != LyricTimingStatus.NONE && startMs != null && endMs != null
    val supportedSeekMs: Long? get() = startMs.takeIf { hasPerformedTiming && timingStatus == LyricTimingStatus.SUPPORTED }
    val timingLabel: String? get() = when {
        contentStatus == "disputed" -> "Disputed lyrics"
        contentStatus == "source-only" -> "Source lyrics · occurrence unconfirmed"
        occurrenceStatus == "unknown" -> "Occurrence unconfirmed"
        timingStatus == LyricTimingStatus.ESTIMATED || words.any { it.timingStatus == LyricTimingStatus.ESTIMATED } -> "Approximate timing"
        timingStatus == LyricTimingStatus.NONE -> "Untimed lyrics"
        else -> null
    }
}

data class TimedLyricWord(
    val startUtf16: Int,
    val endUtf16: Int,
    val startMs: Long?,
    val endMs: Long?,
    val timingStatus: LyricTimingStatus = LyricTimingStatus.SUPPORTED,
    val timingBasis: String? = null,
    val timingReason: String? = null
)

data class LyricTrack(
    val id: String,
    val kind: String,
    val language: String? = null,
    val format: String,
    val text: String? = null,
    val lines: List<TimedLyricLine> = emptyList(),
    val basedOnTrackId: String? = null,
    val componentLanguages: List<String> = emptyList(),
    val script: String? = null
)

data class Song(
    val id: String,
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val state: SongState,
    val accent: Color,
    val year: String = "",
    val album: String = "",
    val bpm: Float? = null,
    val key: String? = null,
    val meter: String? = null,
    val progress: Float = 0f,
    val hasStems: Boolean = false,
    val artworkGlyph: String,
    val canonicalUrl: String = "",
    val sourceHash: String? = null,
    /** Complete ordered lyric-track contract, including synchronized line timing. */
    val lyricTracks: List<LyricTrack> = emptyList(),
    val additionalInfo: Map<String, String> = emptyMap(),
    val artworkSource: String? = null,
    val sourcePath: String? = null,
    val sourceCodec: String? = null,
    val sourceContainer: String? = null,
    /** Revision of playable bytes only; metadata/lyrics updates must not restart audio. */
    val audioRevision: String = "",
    /** Revision of the whole manifest, used by artwork/lyrics/analysis consumers. */
    val mediaRevision: String = "",
    /** Exact active playable stem package; unrelated JSON edits leave this stable. */
    val stemRevision: String = "",
    /** Physical optional part files/manifest, including incomplete packages retained for repair. */
    val hasSourceParts: Boolean = false,
    val lyricsStatus: String? = null,
    val lyricsReason: String? = null,
    val keyStatus: String? = null,
    val keyReason: String? = null
)

val Song.isFlac: Boolean
    get() = sourceCodec.equals("flac", ignoreCase = true)

data class Stem(
    val id: String,
    val label: String,
    val color: Color,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false
)

enum class QueueJobAction(val label: String) {
    VIEW_DETAILS("VIEW DETAILS"), CLEAN_AND_RETRY("CLEAN AND RETRY"), RETRY("TRY AGAIN"),
    DISCARD_FAILED_PREPARATION("Discard failed preparation");

    fun accessibilityDescription(songTitle: String): String = when (this) {
        VIEW_DETAILS -> "View details for $songTitle"
        CLEAN_AND_RETRY -> "Clean working files and retry $songTitle"
        RETRY -> "Retry $songTitle"
        DISCARD_FAILED_PREPARATION -> "Discard failed preparation for $songTitle"
    }
}

enum class QueueOperationPhase(val label: String) {
    SYNCING("Syncing"), PROCESSING("Processing"), VERIFYING("Verifying"), RETRYING("Retrying")
}

data class QueueJob(
    val id: String,
    val songTitle: String,
    val artist: String,
    val kind: JobKind,
    val state: JobState,
    val progress: Float,
    val detail: String,
    val accent: Color,
    val action: QueueJobAction? = null,
    val phase: QueueOperationPhase = if (kind == JobKind.SEPARATION) QueueOperationPhase.PROCESSING else QueueOperationPhase.SYNCING,
    val stableCode: String? = null,
    val automaticPreparation: Boolean = false
) {
    val actionLabel: String? get() = action?.label
}

fun Long.asClock(includeMillis: Boolean = false): String {
    val safeMs = this.coerceAtLeast(0)
    val totalSeconds = safeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (safeMs % 1000) / 10
    return buildString(if (includeMillis) 8 else 5) {
        if (includeMillis) appendAtLeastTwoDigits(minutes) else append(minutes)
        append(':')
        appendAtLeastTwoDigits(seconds)
        if (includeMillis) {
            append('.')
            appendAtLeastTwoDigits(hundredths)
        }
    }
}

private fun StringBuilder.appendAtLeastTwoDigits(value: Long) {
    if (value < 10L) append('0')
    append(value)
}

fun Long.asHoursMinutes(): String {
    val totalMinutes = (coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** Accepted enrichment tonic: one natural note and an optional sharp or flat. */
internal fun tonicPitchClass(tonic: String): Int? {
    if (tonic.length !in 1..2) return null
    val natural = when (tonic[0]) {
        'C' -> 0
        'D' -> 2
        'E' -> 4
        'F' -> 5
        'G' -> 7
        'A' -> 9
        'B' -> 11
        else -> return null
    }
    val accidental = when (tonic.getOrNull(1)) {
        null -> 0
        '#' -> 1
        'b' -> -1
        else -> return null
    }
    return (natural + accidental + 12) % 12
}
