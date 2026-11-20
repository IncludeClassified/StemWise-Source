package app.stemwise.playback

/** Shared range admission; UI clamping happens before this strict invariant. */
object PlaybackLoopValidation {
    const val minimumDurationMs = 100L
    fun accepts(startMs: Long, endMs: Long): Boolean = startMs >= 0L &&
        endMs >= startMs && endMs - startMs >= minimumDurationMs
}
