package app.stemwise.playback

import kotlin.math.roundToLong
import java.security.MessageDigest

const val MINIMUM_PLAYBACK_TEMPO = .5f
const val MAXIMUM_PLAYBACK_TEMPO = 2f

enum class LoopPreparationPhase { IDLE, PREPARING, READY, REJECTED, FAILED }

data class LoopPreparationState(
    val phase: LoopPreparationPhase = LoopPreparationPhase.IDLE,
    val selectionToken: Long = 0L,
    val generation: Long = 0L,
    val requestRevision: Long = 0L,
    val reason: String? = null,
    val retryable: Boolean = false,
    val canConfigure: Boolean = false
)

data class LoopRange(val startMs: Long, val endMs: Long) {
    init {
        require(PlaybackLoopValidation.accepts(startMs, endMs))
    }

    val durationMs: Long get() = endMs - startMs
}

/** Fixed-width durable identity; provider locations must never inflate the playback snapshot. */
fun persistedStemPackageIdentity(packageSignature: String): String {
    require(packageSignature.isNotBlank())
    return playbackStableDigest("session-stem-package-v1|$packageSignature")
}

/** Preserve millisecond position precision independently of audio render batch size. */
fun playbackControlPositionMs(valueMs: Double, durationMs: Long): Long {
    if (durationMs <= 0L || !valueMs.isFinite()) return 0L
    return valueMs.coerceIn(0.0, durationMs.toDouble()).roundToLong()
        .coerceIn(0L, durationMs)
}

fun validatedLoopRange(
    pointA: Long?,
    pointB: Long?,
    durationMs: Long
): LoopRange? {
    if (pointA == null || pointB == null || durationMs < PlaybackLoopValidation.minimumDurationMs) {
        return null
    }
    val start = playbackControlPositionMs(pointA.toDouble(), durationMs)
    val end = playbackControlPositionMs(pointB.toDouble(), durationMs)
    if (!PlaybackLoopValidation.accepts(start, end)) return null
    return LoopRange(start, end)
}

fun playbackStableDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .toHexString()
