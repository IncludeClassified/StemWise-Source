package app.stemwise.playback

/**
 * Stores only the newest position-like publication until its scheduled drain consumes it.
 *
 * Native playback may report its clock every few milliseconds. That cadence is useful to the
 * engine and deliberately remains native, but publishing every callback through StateFlow would
 * recompose the player for every native callback. The first offer asks its owner to schedule one
 * drain; later offers replace the pending value without requesting more work. The owner decides
 * whether that drain belongs to a display frame or a non-visual fallback cadence.
 */
internal class FrameConflatedPublication<T> {
    private var frameScheduled = false
    private var pending: T? = null

    /** Returns true only when the caller must schedule a drain. */
    fun offer(value: T): Boolean {
        pending = value
        if (frameScheduled) return false
        frameScheduled = true
        return true
    }

    /** Returns the newest pending value and opens admission for the next scheduled drain. */
    fun drain(): T? {
        val value = pending
        pending = null
        frameScheduled = false
        return value
    }

    /** Drops stale content while retaining an already-posted drain callback. */
    fun discardPending() {
        pending = null
    }

    internal fun frameScheduledForTest(): Boolean = frameScheduled
}
