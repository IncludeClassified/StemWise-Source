package app.stemwise.playback.session

import app.stemwise.playback.STANDARD_SEEK_INCREMENT_MS
import kotlinx.coroutines.flow.StateFlow
import app.stemwise.artwork.ArtworkAccessPolicy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable, renderer-independent state published to Android's media session.
 *
 * This contract deliberately contains no Media3 player, decoder, output, buffer, or clock.
 * The primary playback owner supplies authoritative snapshots and owns all queue decisions.
 */
internal data class MediaSessionSnapshot(
    val media: SessionMediaDescription? = null,
    /** Exact latest-request/committed transport owner; changes on a same-song reload too. */
    val transportOwnerKey: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0L,
    /** Actual native transport rate used by Android to extrapolate progress between snapshots. */
    val playbackSpeed: Float = 1f,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val loading: Boolean = false,
    val ended: Boolean = false,
    val error: SessionPlaybackError? = null,
    val suppression: SessionPlaybackSuppression = SessionPlaybackSuppression.NONE,
    val repeatMode: SessionRepeatMode = SessionRepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoPrevious: Boolean = false,
    val seekable: Boolean = true
) {
    init {
        require(media != null || transportOwnerKey == null) {
            "A session owner key requires a visible media identity"
        }
        require(playbackSpeed.isFinite() && playbackSpeed > 0f) {
            "Playback speed must be finite and positive"
        }
        require(!playing || playWhenReady) {
            "A playing session must also be play-when-ready"
        }
        require(!playing || media != null) {
            "A playing session must identify its current media"
        }
        require(!playing || (!ended && error == null)) {
            "A playing session cannot simultaneously end or fail"
        }
        require(!playing || suppression == SessionPlaybackSuppression.NONE) {
            "A playing session cannot be suppressed"
        }
    }
}

internal data class SessionMediaDescription(
    val id: String,
    val title: String,
    val artist: String? = null,
    /** Changes whenever same-location media presentation data is replaced. */
    val revision: String? = null,
    /** Bounded encoded image owned by the session; safe to send to SystemUI. */
    val artworkData: SessionArtworkData? = null,
    /** URI text only. Filesystem paths are not accepted as artwork URIs. */
    val artworkUri: String? = null
) {
    init {
        require(id.isNotBlank()) { "Media id must not be blank" }
    }
}

internal const val SESSION_MEDIA_REVISION_KEY = "app.stemwise.MEDIA_REVISION"
internal const val MAXIMUM_SESSION_ARTWORK_BYTES = 256 * 1024

/** Content-equal immutable wrapper; Kotlin arrays otherwise use reference equality. */
internal class SessionArtworkData(bytes: ByteArray) {
    private val encodedBytes: ByteArray = bytes.copyOf()

    /** Callers receive a copy so a published snapshot cannot be mutated behind equality checks. */
    val bytes: ByteArray
        get() = encodedBytes.copyOf()

    val size: Int
        get() = encodedBytes.size

    init {
        require(encodedBytes.isNotEmpty()) { "Session artwork must not be empty" }
        require(encodedBytes.size <= MAXIMUM_SESSION_ARTWORK_BYTES) {
            "Session artwork exceeds the bounded media-session payload"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is SessionArtworkData && encodedBytes.contentEquals(other.encodedBytes))

    override fun hashCode(): Int = encodedBytes.contentHashCode()
}

internal data class SessionPlaybackError(
    val category: SessionPlaybackErrorCategory = SessionPlaybackErrorCategory.UNKNOWN,
    val message: String
) {
    init {
        require(message.isNotBlank()) { "Playback error message must not be blank" }
    }
}

internal enum class SessionPlaybackErrorCategory {
    UNKNOWN,
    SOURCE_IO,
    DECODE,
    AUDIO_OUTPUT
}

internal enum class SessionPlaybackSuppression {
    NONE,
    TRANSIENT_AUDIO_FOCUS_LOSS,
    UNSUITABLE_AUDIO_ROUTE,
    UNSUITABLE_AUDIO_OUTPUT,
    SCRUBBING
}

internal enum class SessionRepeatMode { OFF, ONE, ALL }

/**
 * Small command/state boundary around the native-engine adapter.
 *
 * The facade invokes commands on Media3's application looper, so implementations must enqueue
 * work and return without blocking it. Implementations publish accepted state changes through
 * [snapshots]. Command methods must not create another player, queue, clock, buffering policy,
 * decoder, or audio output. Repeat, shuffle, and next/previous behavior stay with the primary
 * queue owner.
 */
internal interface MediaSessionTransport : AutoCloseable {
    val snapshots: StateFlow<MediaSessionSnapshot>

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)
    fun next()
    fun previous()
    fun setRepeatMode(mode: SessionRepeatMode)
    fun setShuffleEnabled(enabled: Boolean)

    /** Called once when removal from Recents is treated as the owner's explicit stop gesture. */
    fun onTaskRemoved()

    /** Releases only transport-adapter resources; the primary engine owns audio resources. */
    override fun close()
}

internal object MediaSessionCommandPolicy {
    /** Shared by the facade, Android notification, Bluetooth, and external controllers. */
    const val SEEK_INCREMENT_MS = STANDARD_SEEK_INCREMENT_MS


}

internal enum class SessionPlaybackPhase { IDLE, BUFFERING, READY, ENDED }

internal data class MediaSessionProjection(
    val media: SessionMediaDescription?,
    val artworkUri: String?,
    val durationMs: Long?,
    val positionMs: Long,
    val playbackSpeed: Float,
    val playbackPhase: SessionPlaybackPhase,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val loading: Boolean,
    val error: SessionPlaybackError?,
    val suppression: SessionPlaybackSuppression,
    val repeatMode: SessionRepeatMode,
    val shuffleEnabled: Boolean,
    val canGoNext: Boolean,
    val canGoPrevious: Boolean,
    val seekable: Boolean
)

/** Pure normalization used by the Android adapter and host-side contract tests. */
internal object MediaSessionSnapshotMapper {
    fun project(snapshot: MediaSessionSnapshot): MediaSessionProjection {
        val media = snapshot.media?.let { description ->
            description.copy(
                id = description.id.trim(),
                title = description.title.trim().ifBlank { description.id.trim() },
                artist = description.artist?.trim()?.takeIf(String::isNotBlank),
                revision = description.revision?.trim()?.takeIf(String::isNotBlank),
                artworkUri = validArtworkUri(description.artworkUri)
            )
        }
        val durationMs = snapshot.durationMs?.takeIf { media != null && it >= 0L }
        val positionMs = if (media == null) {
            0L
        } else {
            snapshot.positionMs.coerceAtLeast(0L).let { position ->
                durationMs?.let(position::coerceAtMost) ?: position
            }
        }
        val phase = when {
            media == null || snapshot.error != null -> SessionPlaybackPhase.IDLE
            snapshot.ended -> SessionPlaybackPhase.ENDED
            // isLoading can remain true while ready playback continues.
            snapshot.playing -> SessionPlaybackPhase.READY
            snapshot.loading -> SessionPlaybackPhase.BUFFERING
            // With no explicit suppression, play-when-ready but not playing is a pending start.
            snapshot.playWhenReady &&
                !snapshot.playing &&
                snapshot.suppression == SessionPlaybackSuppression.NONE ->
                SessionPlaybackPhase.BUFFERING
            else -> SessionPlaybackPhase.READY
        }
        // Media3 rejects isLoading=true while the facade is IDLE or ENDED. Startup can
        // legitimately report native/library work before any committed media identity exists,
        // and a stale loading bit can overlap an ended snapshot. Keep that internal work state
        // out of the Android session projection unless the session has an active loadable item.
        val sessionLoading = snapshot.loading &&
            phase != SessionPlaybackPhase.IDLE &&
            phase != SessionPlaybackPhase.ENDED
        return MediaSessionProjection(
            media = media,
            artworkUri = media?.artworkUri,
            durationMs = durationMs,
            positionMs = positionMs,
            playbackSpeed = snapshot.playbackSpeed.coerceIn(
                MINIMUM_SESSION_PLAYBACK_SPEED,
                MAXIMUM_SESSION_PLAYBACK_SPEED
            ),
            playbackPhase = phase,
            playing = snapshot.playing,
            playWhenReady = snapshot.playWhenReady,
            loading = sessionLoading,
            error = snapshot.error,
            suppression = snapshot.suppression,
            repeatMode = snapshot.repeatMode,
            shuffleEnabled = snapshot.shuffleEnabled,
            canGoNext = media != null && snapshot.canGoNext,
            canGoPrevious = media != null && snapshot.canGoPrevious,
            seekable = media != null && snapshot.seekable
        )
    }

    fun validArtworkUri(raw: String?): String? {
        return ArtworkAccessPolicy.publicSessionArtworkUri(raw)
    }
}

/** Reuses immutable projections while their upstream owner object is exactly unchanged. */
internal class ReferenceIdentityCache<K : Any, V : Any> {
    private var identity: K? = null
    private var value: V? = null

    fun getOrCreate(identity: K, create: () -> V): V {
        if (this.identity === identity) return requireNotNull(value)
        return create().also { created ->
            this.identity = identity
            value = created
        }
    }

    fun clear() {
        identity = null
        value = null
    }
}

/**
 * Android controllers do not need the Compose player's frame-rate position stream. Structural
 * state, explicit seeks and loop wraps still publish immediately; ordinary forward progress is
 * coalesced and [androidx.media3.common.SimpleBasePlayer.PositionSupplier] extrapolates between
 * those snapshots at [MediaSessionSnapshot.playbackSpeed].
 */
internal const val MEDIA_SESSION_POSITION_PUBLISH_INTERVAL_MS = 250L

internal fun mediaSessionSnapshotNeedsImmediatePublication(
    published: MediaSessionSnapshot,
    next: MediaSessionSnapshot
): Boolean {
    if (published.copy(positionMs = 0L) != next.copy(positionMs = 0L)) return true
    if (!published.playing || !next.playing) return true

    val deltaMs = next.positionMs - published.positionMs
    if (deltaMs < -MEDIA_SESSION_BACKWARD_JITTER_TOLERANCE_MS) return true

    val ordinaryWindowMs =
        (MEDIA_SESSION_POSITION_PUBLISH_INTERVAL_MS * next.playbackSpeed).toLong()
    return deltaMs > maxOf(MEDIA_SESSION_EXPLICIT_SEEK_THRESHOLD_MS, ordinaryWindowMs * 3L)
}

/**
 * The first pending owner and every committed owner remain immediate. Only pending-to-pending
 * identity churn can be conflated: publishing hundreds of songs that were superseded before decode
 * would otherwise serialize hundreds of notification artwork/title updates behind the final song.
 */
internal fun mediaSessionPendingOwnerChangeCanCoalesce(
    published: MediaSessionSnapshot,
    next: MediaSessionSnapshot
): Boolean = published.loading &&
    next.loading &&
    !published.playing &&
    !next.playing &&
    published.playWhenReady == next.playWhenReady &&
    published.media != null &&
    next.media != null &&
    published.media.id != next.media.id

private const val MINIMUM_SESSION_PLAYBACK_SPEED = .5f
private const val MAXIMUM_SESSION_PLAYBACK_SPEED = 2f
private const val MEDIA_SESSION_BACKWARD_JITTER_TOLERANCE_MS = 50L
private const val MEDIA_SESSION_EXPLICIT_SEEK_THRESHOLD_MS = 1_000L

/**
 * Conflates producer-thread snapshots and exposes distinct publications to the application looper.
 * [drain], [current], and [hasPending] are application-looper-only operations.
 */
internal class SessionSnapshotPublication(initial: MediaSessionSnapshot) {
    private val pending = AtomicReference(initial)
    private var published = initial

    fun offer(snapshot: MediaSessionSnapshot) {
        pending.set(snapshot)
    }

    fun drain(): MediaSessionSnapshot? {
        val candidate = pending.get()
        if (candidate == published) return null
        published = candidate
        return candidate
    }

    fun current(): MediaSessionSnapshot = published

    fun hasPending(): Boolean = pending.get() != published
}

/**
 * Latest-wins gate for the service's explicit notification metadata repair.
 *
 * Media3 normally rebuilds its notification from Player events. A same-main-turn owner storm can
 * nevertheless leave the already-posted notification on the first pending owner even after the
 * stable Player and every external controller have converged. The service therefore schedules one
 * quiet-period refresh after [StableMediaSessionPlayer] has applied a canonical snapshot. This gate
 * keeps that repair identity-only, conflates superseded owners, and rejects a drain until the
 * caller proves that the same canonical media is still current.
 */
internal class SessionNotificationRefreshGate {
    private var pending: SessionNotificationIdentity? = null
    private var refreshed: SessionNotificationIdentity? = null

    /** Returns true only when the service needs to (re)schedule the quiet-period drain. */
    fun offer(snapshot: MediaSessionSnapshot): Boolean {
        val identity = snapshot.notificationIdentity()
        if (identity == null) {
            clear()
            return false
        }
        if (identity == pending || (pending == null && identity == refreshed)) return false
        pending = identity
        return true
    }

    /**
     * Returns the newest owner exactly once, and only if it still matches the stable Player.
     * A stale drain deliberately retains the pending owner so a later canonical check can retry it.
     */
    fun drain(
        currentCanonicalSnapshot: MediaSessionSnapshot?,
        latestTransportOwnerKey: String?
    ): SessionNotificationIdentity? {
        val candidate = pending ?: return null
        if (candidate != currentCanonicalSnapshot?.notificationIdentity() ||
            candidate.transportOwnerKey != latestTransportOwnerKey ||
            candidate == refreshed
        ) return null
        pending = null
        refreshed = candidate
        return candidate
    }

    fun clear() {
        pending = null
        refreshed = null
    }

    fun diagnostics(): SessionNotificationRefreshGateDiagnostics =
        SessionNotificationRefreshGateDiagnostics(
            pending = pending,
            refreshed = refreshed
        )
}

/** Exact identity used only at the notification publication boundary. */
internal data class SessionNotificationIdentity(
    val transportOwnerKey: String?,
    val media: SessionMediaDescription
)

internal data class SessionNotificationRefreshGateDiagnostics(
    val pending: SessionNotificationIdentity?,
    val refreshed: SessionNotificationIdentity?
)

private fun MediaSessionSnapshot.notificationIdentity(): SessionNotificationIdentity? =
    media?.let { SessionNotificationIdentity(transportOwnerKey, it) }

/** One-shot service-lifetime delivery gates that remain deterministic under concurrent teardown. */
internal class SessionFacadeLifecycle {
    private val state = AtomicInteger(0)

    fun isClosed(): Boolean = state.get() and CLOSED != 0

    fun tryDeliverTaskRemoval(): Boolean = setOnceUnlessClosed(TASK_REMOVED)

    fun tryClose(): Boolean {
        while (true) {
            val current = state.get()
            if (current and CLOSED != 0) return false
            if (state.compareAndSet(current, current or CLOSED)) return true
        }
    }

    private fun setOnceUnlessClosed(flag: Int): Boolean {
        while (true) {
            val current = state.get()
            if (current and (CLOSED or flag) != 0) return false
            if (state.compareAndSet(current, current or flag)) return true
        }
    }

    private companion object {
        const val CLOSED = 1
        const val TASK_REMOVED = 1 shl 1
    }
}
