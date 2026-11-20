package app.stemwise.playback.session

import android.net.Uri
import android.os.Bundle
import app.stemwise.playback.PlaybackInstrumentationRecorder
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service-lifetime command ceiling for every Android controller connection.
 *
 * A controller may connect while the owner is empty or at a queue boundary. Its connection must
 * still retain every command this facade can support later; [getState] publishes the smaller,
 * moment-to-moment set that is actually executable for the current owner snapshot.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal val STABLE_MEDIA_SESSION_COMMAND_CAPABILITIES: Player.Commands =
    Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_RELEASE
        )
        .build()

/**
 * Stable, service-lifetime Media3 facade for notification, Bluetooth, and OS controllers.
 *
 * The object observes the primary transport and invalidates its own [SimpleBasePlayer] state;
 * it is never replaced when a song, renderer, or queue changes. It owns no decoder, renderer,
 * PCM, audio output, media queue, buffering policy, or playback clock.
 */
@UnstableApi
internal class StableMediaSessionPlayer(
    private val transport: MediaSessionTransport,
    private val onCanonicalSnapshotApplied: (MediaSessionSnapshot) -> Unit = {},
    private val instrumentation: PlaybackInstrumentationRecorder = PlaybackInstrumentationRecorder()
) : SimpleBasePlayer(Looper.getMainLooper()), AutoCloseable {
    private val applicationHandler = Handler(applicationLooper)
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshots = SessionSnapshotPublication(transport.snapshots.value)
    private val lifecycle = SessionFacadeLifecycle()
    private val invalidationPosted = AtomicBoolean(false)

    private var cachedError: SessionPlaybackError? = null
    private var cachedPlaybackException: PlaybackException? = null
    private var cachedMetadataMedia: SessionMediaDescription? = null
    private var cachedMetadataDurationMs: Long? = null
    private var cachedMediaMetadata: MediaMetadata = MediaMetadata.EMPTY

    private val invalidationDrain = Runnable {
        if (lifecycle.isClosed()) {
            invalidationPosted.set(false)
            return@Runnable
        }
        try {
            snapshots.drain()?.let { canonicalSnapshot ->
                // SimpleBasePlayer performs the state diff and emits one canonical event batch.
                invalidateState()
                // The notification repair is downstream of the Player state application. It can
                // therefore never build song B's notification from song A's facade state.
                onCanonicalSnapshotApplied(canonicalSnapshot)
            }
        } finally {
            // A malformed platform projection must never leave the coalescing gate latched. If
            // Media3 rejects one state, a later valid owner still has to reach controllers.
            invalidationPosted.set(false)
            if (snapshots.hasPending()) scheduleInvalidation()
        }
    }

    init {
        observationScope.launch {
            transport.snapshots.collect { offerSnapshot(it) }
        }
    }

    override fun getState(): State {
        // SimpleBasePlayer re-reads state when a command completes, before the queued observer
        // drain. Use the transport's current canonical result so a following toggle cannot
        // see the previous Play intent. Observer invalidation remains conflated.
        val projection = MediaSessionSnapshotMapper.project(transport.snapshots.value)
        val metadata = mediaMetadata(projection)
        val timeline = projection.media?.let { media ->
            val mediaItem = MediaItem.Builder()
                .setMediaId(media.id)
                .setMediaMetadata(metadata)
                .build()
            CurrentSessionTimeline(
                mediaItem = mediaItem,
                durationMs = projection.durationMs,
                canGoPreviousOrRestart = projection.seekable,
                canGoNext = projection.canGoNext,
                seekable = projection.seekable
            )
        } ?: Timeline.EMPTY

        return State.Builder()
            .setAvailableCommands(availableCommands(projection))
            .setPlayWhenReady(
                projection.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
            )
            .setPlaybackState(projection.playbackPhase.toMedia3PlaybackState())
            .setPlaybackParameters(PlaybackParameters(projection.playbackSpeed))
            .setPlaybackSuppressionReason(projection.suppression.toMedia3SuppressionReason())
            .setPlayerError(playbackException(projection.error))
            .setRepeatMode(projection.repeatMode.toMedia3RepeatMode())
            .setShuffleModeEnabled(projection.shuffleEnabled)
            .setIsLoading(projection.loading)
            .setSeekBackIncrementMs(MediaSessionCommandPolicy.SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(MediaSessionCommandPolicy.SEEK_INCREMENT_MS)
            // Previous/restart policy belongs to the queue transport, never this facade.
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .setPlaylist(timeline, Tracks.EMPTY, metadata)
            // Media3 represents an empty playlist with an empty timeline and index zero. Passing
            // INDEX_UNSET here can make PlayerInfo reject the update and freeze subsequent
            // controller publications behind the invalidation coalescing gate.
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                if (projection.playing) {
                    val extrapolating = PositionSupplier.getExtrapolating(
                        projection.positionMs,
                        projection.playbackSpeed
                    )
                    PositionSupplier {
                        projection.durationMs?.let { duration ->
                            extrapolating.get().coerceAtMost(duration)
                        } ?: extrapolating.get()
                    }
                } else {
                    PositionSupplier.getConstant(projection.positionMs)
                }
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) transport.play() else transport.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        transport.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS -> transport.previous()
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT -> transport.next()
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> transport.seekTo(0L)
            Player.COMMAND_SEEK_BACK ->
                transport.seekBy(-MediaSessionCommandPolicy.SEEK_INCREMENT_MS)
            Player.COMMAND_SEEK_FORWARD ->
                transport.seekBy(MediaSessionCommandPolicy.SEEK_INCREMENT_MS)
            // The transport acquires the latest owner and clamps to its duration. This facade's
            // conflated timeline may still describe the outgoing, shorter song.
            else -> transport.seekTo(
                if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        transport.setRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> SessionRepeatMode.ONE
                Player.REPEAT_MODE_ALL -> SessionRepeatMode.ALL
                else -> SessionRepeatMode.OFF
            }
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        transport.setShuffleEnabled(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    /** Service integration calls this from [android.app.Service.onTaskRemoved]. */
    fun onTaskRemoved() {
        verifyApplicationThread()
        if (lifecycle.tryDeliverTaskRemoval()) {
            transport.onTaskRemoved()
        }
    }

    internal fun mediaMetadataBuildCountForTest(): Long = instrumentation.count("mediaMetadataBuildCount")

    /** Main-looper canonical snapshot used to reject a superseded notification refresh. */
    internal fun canonicalNotificationSnapshot(): MediaSessionSnapshot = snapshots.current()

    /** Service integration calls this once after releasing its MediaSession. */
    override fun close() {
        release()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (lifecycle.tryClose()) {
            observationScope.cancel()
            applicationHandler.removeCallbacks(invalidationDrain)
            invalidationPosted.set(false)
            cachedMetadataMedia = null
            cachedMetadataDurationMs = null
            cachedMediaMetadata = MediaMetadata.EMPTY
            transport.close()
        }
        return Futures.immediateVoidFuture()
    }

    private fun offerSnapshot(snapshot: MediaSessionSnapshot) {
        snapshots.offer(snapshot)
        scheduleInvalidation()
    }

    private fun scheduleInvalidation() {
        if (lifecycle.isClosed() || !invalidationPosted.compareAndSet(false, true)) return
        if (!applicationHandler.post(invalidationDrain)) {
            invalidationPosted.set(false)
        }
    }

    private fun mediaMetadata(projection: MediaSessionProjection): MediaMetadata {
        val media = projection.media ?: run {
            cachedMetadataMedia = null
            cachedMetadataDurationMs = null
            cachedMediaMetadata = MediaMetadata.EMPTY
            return MediaMetadata.EMPTY
        }
        if (cachedMetadataMedia == media && cachedMetadataDurationMs == projection.durationMs) {
            return cachedMediaMetadata
        }
        if (instrumentation.enabled) instrumentation.increment("mediaMetadataBuildCount")
        return MediaMetadata.Builder()
            .setTitle(media.title)
            .apply { media.artist?.let { setArtist(it) } }
            .apply { projection.durationMs?.let { setDurationMs(it) } }
            .apply {
                media.revision?.let { revision ->
                    setExtras(Bundle().apply {
                        putString(SESSION_MEDIA_REVISION_KEY, revision)
                    })
                }
            }
            .apply {
                media.artworkData?.let { artwork ->
                    setArtworkData(
                        artwork.bytes,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
                    )
                }
            }
            .apply { projection.artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
            .setIsPlayable(true)
            .build()
            .also { built ->
                cachedMetadataMedia = media
                cachedMetadataDurationMs = projection.durationMs
                cachedMediaMetadata = built
            }
    }

    private fun playbackException(error: SessionPlaybackError?): PlaybackException? {
        if (error == null) {
            cachedError = null
            cachedPlaybackException = null
            return null
        }
        if (cachedError != error) {
            cachedError = error
            cachedPlaybackException = PlaybackException(
                error.message,
                null,
                when (error.category) {
                    SessionPlaybackErrorCategory.UNKNOWN ->
                        PlaybackException.ERROR_CODE_UNSPECIFIED
                    SessionPlaybackErrorCategory.SOURCE_IO ->
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    SessionPlaybackErrorCategory.DECODE ->
                        PlaybackException.ERROR_CODE_DECODING_FAILED
                    SessionPlaybackErrorCategory.AUDIO_OUTPUT ->
                        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                }
            )
        }
        return cachedPlaybackException
    }

    private companion object {
        fun availableCommands(projection: MediaSessionProjection): Player.Commands =
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_STOP,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_RELEASE
                )
                .addIf(Player.COMMAND_PLAY_PAUSE, projection.media != null)
                .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, projection.seekable)
                .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, projection.seekable)
                .addIf(Player.COMMAND_SEEK_BACK, projection.seekable)
                .addIf(Player.COMMAND_SEEK_FORWARD, projection.seekable)
                .addIf(
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    projection.seekable
                )
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, projection.seekable)
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, projection.canGoNext)
                .addIf(Player.COMMAND_SEEK_TO_NEXT, projection.canGoNext)
                .build()
    }
}

private fun SessionPlaybackPhase.toMedia3PlaybackState(): Int = when (this) {
    SessionPlaybackPhase.IDLE -> Player.STATE_IDLE
    SessionPlaybackPhase.BUFFERING -> Player.STATE_BUFFERING
    SessionPlaybackPhase.READY -> Player.STATE_READY
    SessionPlaybackPhase.ENDED -> Player.STATE_ENDED
}

private fun SessionPlaybackSuppression.toMedia3SuppressionReason(): Int = when (this) {
    SessionPlaybackSuppression.NONE -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
    SessionPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS ->
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
    SessionPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE ->
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
    SessionPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT ->
        Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT
    SessionPlaybackSuppression.SCRUBBING -> Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING
}

private fun SessionRepeatMode.toMedia3RepeatMode(): Int = when (this) {
    SessionRepeatMode.OFF -> Player.REPEAT_MODE_OFF
    SessionRepeatMode.ONE -> Player.REPEAT_MODE_ONE
    SessionRepeatMode.ALL -> Player.REPEAT_MODE_ALL
}

/** One-item timeline whose navigation answers come from the primary queue owner's snapshot. */
@androidx.annotation.OptIn(UnstableApi::class)
private class CurrentSessionTimeline(
    private val mediaItem: MediaItem,
    private val durationMs: Long?,
    private val canGoPreviousOrRestart: Boolean,
    private val canGoNext: Boolean,
    private val seekable: Boolean
) : Timeline() {
    private val windowUid = SessionWindowUid(mediaItem.mediaId)
    private val periodUid = SessionPeriodUid(mediaItem.mediaId)
    private val durationUs = durationMs?.toSafeMicroseconds() ?: C.TIME_UNSET

    override fun getWindowCount(): Int = 1

    override fun getPeriodCount(): Int = 1

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        checkWindowIndex(windowIndex)
        return window.set(
            windowUid,
            mediaItem,
            null,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            seekable,
            false,
            null,
            0L,
            durationUs,
            0,
            0,
            0L
        )
    }

    override fun getPeriod(
        periodIndex: Int,
        period: Period,
        setIds: Boolean
    ): Period {
        checkPeriodIndex(periodIndex)
        return period.set(
            if (setIds) periodUid else null,
            periodUid,
            0,
            durationUs,
            0L
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int =
        if (uid == periodUid) 0 else C.INDEX_UNSET

    override fun getUidOfPeriod(periodIndex: Int): Any {
        checkPeriodIndex(periodIndex)
        return periodUid
    }

    override fun getNextWindowIndex(
        windowIndex: Int,
        repeatMode: Int,
        shuffleModeEnabled: Boolean
    ): Int {
        checkWindowIndex(windowIndex)
        return if (canGoNext) 0 else C.INDEX_UNSET
    }

    override fun getPreviousWindowIndex(
        windowIndex: Int,
        repeatMode: Int,
        shuffleModeEnabled: Boolean
    ): Int {
        checkWindowIndex(windowIndex)
        return if (canGoPreviousOrRestart) 0 else C.INDEX_UNSET
    }

    override fun equals(other: Any?): Boolean =
        other is CurrentSessionTimeline &&
            mediaItem == other.mediaItem &&
            durationMs == other.durationMs &&
            canGoPreviousOrRestart == other.canGoPreviousOrRestart &&
            canGoNext == other.canGoNext &&
            seekable == other.seekable

    override fun hashCode(): Int {
        var result = mediaItem.hashCode()
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + canGoPreviousOrRestart.hashCode()
        result = 31 * result + canGoNext.hashCode()
        result = 31 * result + seekable.hashCode()
        return result
    }

    private fun checkWindowIndex(index: Int) {
        if (index != 0) throw IndexOutOfBoundsException("Window index $index")
    }

    private fun checkPeriodIndex(index: Int) {
        if (index != 0) throw IndexOutOfBoundsException("Period index $index")
    }
}

private data class SessionWindowUid(val mediaId: String)
private data class SessionPeriodUid(val mediaId: String)

private fun Long.toSafeMicroseconds(): Long =
    coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
