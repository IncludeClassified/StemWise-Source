package app.stemwise.playback.session

/** The pending slot always represents the latest observation, including a return to publication. */
internal class MediaSessionPublicationCadence(private var lastPublishedAtMs: Long) {
    var pending: MediaSessionSnapshot? = null
        private set

    /** null cancels a pending timer; zero publishes now; a positive delay arms/coalesces it. */
    fun offer(published: MediaSessionSnapshot, next: MediaSessionSnapshot,
              nowMs: Long, forceImmediate: Boolean = false): Long? {
        if (next == published) {
            pending = null
            return null
        }
        val elapsed = (nowMs - lastPublishedAtMs).coerceAtLeast(0L)
        val coalescibleOwner = mediaSessionPendingOwnerChangeCanCoalesce(published, next)
        if (forceImmediate || (!coalescibleOwner &&
                mediaSessionSnapshotNeedsImmediatePublication(published, next)) ||
            elapsed >= MEDIA_SESSION_POSITION_PUBLISH_INTERVAL_MS
        ) {
            pending = null
            lastPublishedAtMs = nowMs
            return 0L
        }
        pending = next
        return (MEDIA_SESSION_POSITION_PUBLISH_INTERVAL_MS - elapsed).coerceAtLeast(1L)
    }

    fun drain(nowMs: Long): MediaSessionSnapshot? = pending?.also {
        pending = null
        lastPublishedAtMs = nowMs
    }

    fun clear() { pending = null }
}
