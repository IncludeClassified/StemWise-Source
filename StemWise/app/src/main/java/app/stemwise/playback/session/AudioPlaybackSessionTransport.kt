package app.stemwise.playback.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackInstrumentationRecorder
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.AudioPlaybackSuppression
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-session adapter around the one app-owned playback controller.
 *
 * This object observes state and delegates commands only. It never obtains AudioPlayback's
 * renderer player, never creates a queue/decoder/output, and therefore remains stable when the
 * rendering implementation or current song changes.
 */
internal class AudioPlaybackSessionTransport(
    private val playback: AudioPlayback,
    private val instrumentation: PlaybackInstrumentationRecorder = playback.instrumentationRecorder.child()
) : MediaSessionTransport {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closed = AtomicBoolean(false)
    private val mediaDescriptionCache =
        ReferenceIdentityCache<MediaItem, SessionMediaDescription>()
    private val initialState = playback.state.value
    private val initializedAtElapsedMs = SystemClock.elapsedRealtime()
    private val initialTransportOwnerKey = currentTransportOwnerKey()
    private val mutableSnapshots = MutableStateFlow(
        snapshot(initialState, initialTransportOwnerKey)
    )
    override val snapshots: StateFlow<MediaSessionSnapshot> = mutableSnapshots.asStateFlow()
    private val observationGate = MediaSessionObservationGate(
        initialState = initialState,
        initialElapsedRealtimeMs = initializedAtElapsedMs,
        instrumentation = instrumentation
    )
    private var cachedTransportOwnerKey = initialTransportOwnerKey
    private val publicationCadence = MediaSessionPublicationCadence(initializedAtElapsedMs)
    private var delayedPublishScheduled = false

    private val refreshRunnable = Runnable {
        if (!closed.get()) refreshNow()
    }
    private val delayedPublishRunnable = Runnable {
        delayedPublishScheduled = false
        if (closed.get()) {
            publicationCadence.clear()
            return@Runnable
        }
        publicationCadence.drain(SystemClock.elapsedRealtime())?.let { mutableSnapshots.value = it }
    }

    init {
        scope.launch {
            playback.state.collect { state ->
                if (instrumentation.enabled) {
                    instrumentation.increment("stateObservationCount")
                    instrumentation.remember("lastObservedSongId", state.songId)
                    instrumentation.remember("lastObservedPositionMs", state.positionMs)
                }
                if (closed.get()) return@collect
                val decision = observationGate.observe(
                    state = state,
                    elapsedRealtimeMs = SystemClock.elapsedRealtime()
                )
                if (decision.requiresFullSnapshot) {
                    cachedTransportOwnerKey = currentTransportOwnerKey()
                    val next = snapshot(state, cachedTransportOwnerKey)
                    instrumentation.remember("lastComputedMediaId", next.media?.id)
                    publishSnapshot(
                        next,
                        forceImmediate = decision ==
                            MediaSessionObservationWork.POSITION_DISCONTINUITY
                    )
                }
            }
        }
    }

    /** Re-reads metadata when artwork/title changes without a structural playback-state change. */
    fun refresh() {
        if (closed.get()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshNow()
        } else {
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.post(refreshRunnable)
        }
    }

    /** Latest synchronous owner proof, including requests not yet emitted by the session cadence. */
    internal fun latestTransportOwnerKeyForNotification(): String? = currentTransportOwnerKey()

    internal fun diagnosticsForTest(): String =
        "observations=${instrumentation.count("stateObservationCount")}," +
            "observedSong=${instrumentation.observation("lastObservedSongId")}," +
            "observedPosition=${instrumentation.observation("lastObservedPositionMs")}," +
            "computedMedia=${instrumentation.observation("lastComputedMediaId")}," +
            "publishedMedia=${snapshots.value.media?.id}," +
            "fullSnapshots=${instrumentation.count("fullSnapshotComputationCount")}," +
            "ownerKeyReads=${instrumentation.count("ownerKeyComputationCount")}," +
            "mediaItemReads=${instrumentation.count("mediaItemLookupCount")}," +
            "metadataRefreshSnapshots=${instrumentation.count("metadataRefreshSnapshotCount")}," +
            "positionFastPaths=${observationGate.diagnostics().fastPathCount}," +
            "structuralSnapshots=${observationGate.diagnostics().structuralSnapshotCount}," +
            "discontinuitySnapshots=${observationGate.diagnostics().discontinuitySnapshotCount}," +
            "cadenceSnapshots=${observationGate.diagnostics().cadenceSnapshotCount}," +
            "closed=${closed.get()}," +
            "delayedPending=${publicationCadence.pending != null}," +
            "delayedScheduled=$delayedPublishScheduled"

    override fun play() {
        playback.play()
        refreshNow()
    }
    override fun pause() {
        playback.pause()
        refreshNow()
    }
    override fun stop() = playback.stop()
    override fun seekTo(positionMs: Long) {
        // MediaSession commands intentionally acquire AudioPlayback's current-owner lease at the
        // command boundary; rejection is reflected by unchanged session state, not a return value.
        playback.seekTo(positionMs)
    }
    override fun seekBy(deltaMs: Long) {
        playback.seekByForSession(deltaMs)
    }
    override fun next() { playback.nextTrack() }
    override fun previous() { playback.previousOrRestart() }

    override fun setRepeatMode(mode: SessionRepeatMode) {
        playback.setRepeatMode(
            when (mode) {
                SessionRepeatMode.OFF -> "Off"
                SessionRepeatMode.ONE -> "One"
                SessionRepeatMode.ALL -> "All"
            }
        )
    }

    override fun setShuffleEnabled(enabled: Boolean) { playback.setShuffle(enabled) }

    override fun onTaskRemoved() {
        playback.stopForTaskRemoval()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(delayedPublishRunnable)
        publicationCadence.clear()
        delayedPublishScheduled = false
        mediaDescriptionCache.clear()
        scope.cancel()
    }

    internal fun mediaDescriptionBuildCountForTest(): Long = instrumentation.count("mediaDescriptionBuildCount")

    private fun refreshNow() {
        val state = playback.state.value
        val now = SystemClock.elapsedRealtime()
        observationGate.recordExternalFullSnapshot(state, now)
        instrumentation.increment("metadataRefreshSnapshotCount")
        cachedTransportOwnerKey = currentTransportOwnerKey()
        val next = snapshot(state, cachedTransportOwnerKey)
        instrumentation.remember("lastComputedMediaId", next.media?.id)
        publishSnapshot(next)
    }

    private fun currentTransportOwnerKey(): String? {
        instrumentation.increment("ownerKeyComputationCount")
        return playback.sessionTransportOwnerKey()
    }

    private fun snapshot(
        state: AudioPlaybackState,
        transportOwnerKey: String?
    ): MediaSessionSnapshot {
        instrumentation.increment("fullSnapshotComputationCount")
        instrumentation.increment("mediaItemLookupCount")
        val item = playback.sessionMediaItem()
        val media = if (item == null) {
            mediaDescriptionCache.clear()
            null
        } else {
            mediaDescriptionCache.getOrCreate(item) {
                instrumentation.increment("mediaDescriptionBuildCount")
                val metadata = item.mediaMetadata
                SessionMediaDescription(
                    id = item.mediaId,
                    title = metadata.title?.toString()?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: item.mediaId,
                    artist = metadata.artist?.toString()?.trim()?.takeIf(String::isNotBlank),
                    revision = metadata.extras
                        ?.getString(SESSION_MEDIA_REVISION_KEY)
                        ?.trim()
                        ?.takeIf(String::isNotBlank),
                    artworkData = metadata.artworkData?.let(::SessionArtworkData),
                    artworkUri = metadata.artworkUri?.toString()
                )
            }
        }
        val playing = state.playing && media != null && state.error == null && !state.ended &&
            state.suppression == AudioPlaybackSuppression.NONE
        return MediaSessionSnapshot(
            media = media,
            transportOwnerKey = transportOwnerKey.takeIf { media != null },
            durationMs = state.durationMs.takeIf { media != null && it > 0L },
            positionMs = state.positionMs.coerceAtLeast(0L),
            playbackSpeed = state.tempo
                .takeIf(Float::isFinite)
                ?.coerceIn(AudioPlayback.MINIMUM_TEMPO, AudioPlayback.MAXIMUM_TEMPO)
                ?: 1f,
            playing = playing,
            playWhenReady = playing || state.playWhenReady,
            loading = state.loading,
            ended = !playing && state.ended,
            suppression = state.suppression.toSessionPlaybackSuppression(),
            error = state.error
                ?.takeIf { !playing && it.isNotBlank() }
                ?.let { SessionPlaybackError(message = it) },
            repeatMode = when (state.repeatMode) {
                "One" -> SessionRepeatMode.ONE
                "All" -> SessionRepeatMode.ALL
                else -> SessionRepeatMode.OFF
            },
            shuffleEnabled = state.shuffleEnabled,
            canGoNext = state.canGoNext,
            canGoPrevious = state.canGoPrevious,
            seekable = media != null
        )
    }

    private fun publishSnapshot(
        next: MediaSessionSnapshot,
        forceImmediate: Boolean = false
    ) {
        val delay = publicationCadence.offer(
            mutableSnapshots.value, next, SystemClock.elapsedRealtime(), forceImmediate)
        if (delay == null || delay == 0L) {
            mainHandler.removeCallbacks(delayedPublishRunnable)
            delayedPublishScheduled = false
            if (delay == 0L) mutableSnapshots.value = next
        } else if (!delayedPublishScheduled) {
            delayedPublishScheduled = true
            mainHandler.postDelayed(delayedPublishRunnable, delay)
        }
    }

}

internal enum class MediaSessionObservationWork(val requiresFullSnapshot: Boolean) {
    POSITION_FAST_PATH(false),
    STRUCTURAL(true),
    POSITION_DISCONTINUITY(true),
    POSITION_CADENCE(true)
}

internal data class MediaSessionObservationGateDiagnostics(
    val observationCount: Long,
    val fastPathCount: Long,
    val structuralSnapshotCount: Long,
    val discontinuitySnapshotCount: Long,
    val cadenceSnapshotCount: Long,
    val externalFullSnapshotCount: Long
) {
    val requestedFullSnapshotCount: Long
        get() = structuralSnapshotCount + discontinuitySnapshotCount + cadenceSnapshotCount
}

/**
 * Classifies the high-rate playback stream before any adapter/media-item lookup is performed.
 *
 * Android's session projection consumes only a small subset of [AudioPlaybackState]. Mixer,
 * waveform, source-panel and other app-only changes therefore share the same cheap path as an
 * ordinary position tick. True session structure, an observed seek/loop discontinuity, and the
 * bounded position cadence remain full-snapshot boundaries.
 */
internal class MediaSessionObservationGate(
    initialState: AudioPlaybackState,
    initialElapsedRealtimeMs: Long,
    private val instrumentation: PlaybackInstrumentationRecorder = PlaybackInstrumentationRecorder()
) {
    private var previousState = initialState
    private var previousObservedAtElapsedMs = initialElapsedRealtimeMs
    private var lastFullSnapshotAtElapsedMs = initialElapsedRealtimeMs

    fun observe(
        state: AudioPlaybackState,
        elapsedRealtimeMs: Long
    ): MediaSessionObservationWork {
        val now = elapsedRealtimeMs.coerceAtLeast(previousObservedAtElapsedMs)
        val elapsedSincePreviousMs = now - previousObservedAtElapsedMs
        val positionChanged = state.positionMs != previousState.positionMs
        val work = when {
            mediaSessionRelevantStateChanged(previousState, state) ->
                MediaSessionObservationWork.STRUCTURAL
            positionChanged && mediaSessionPositionDiscontinuity(
                previous = previousState,
                next = state,
                elapsedRealtimeDeltaMs = elapsedSincePreviousMs
            ) -> MediaSessionObservationWork.POSITION_DISCONTINUITY
            positionChanged &&
                now - lastFullSnapshotAtElapsedMs >=
                MEDIA_SESSION_POSITION_PUBLISH_INTERVAL_MS ->
                MediaSessionObservationWork.POSITION_CADENCE
            else -> MediaSessionObservationWork.POSITION_FAST_PATH
        }

        if (instrumentation.enabled) {
            instrumentation.increment("gate.observationCount")
            when (work) {
                MediaSessionObservationWork.POSITION_FAST_PATH -> instrumentation.increment("gate.fastPathCount")
                MediaSessionObservationWork.STRUCTURAL -> instrumentation.increment("gate.structuralSnapshotCount")
                MediaSessionObservationWork.POSITION_DISCONTINUITY ->
                    instrumentation.increment("gate.discontinuitySnapshotCount")
                MediaSessionObservationWork.POSITION_CADENCE -> instrumentation.increment("gate.cadenceSnapshotCount")
            }
        }
        if (work.requiresFullSnapshot) lastFullSnapshotAtElapsedMs = now
        previousState = state
        previousObservedAtElapsedMs = now
        return work
    }

    fun recordExternalFullSnapshot(
        state: AudioPlaybackState,
        elapsedRealtimeMs: Long
    ) {
        val now = elapsedRealtimeMs.coerceAtLeast(previousObservedAtElapsedMs)
        previousState = state
        previousObservedAtElapsedMs = now
        lastFullSnapshotAtElapsedMs = now
        instrumentation.increment("gate.externalFullSnapshotCount")
    }

    fun diagnostics(): MediaSessionObservationGateDiagnostics =
        MediaSessionObservationGateDiagnostics(
            observationCount = instrumentation.count("gate.observationCount"),
            fastPathCount = instrumentation.count("gate.fastPathCount"),
            structuralSnapshotCount = instrumentation.count("gate.structuralSnapshotCount"),
            discontinuitySnapshotCount = instrumentation.count("gate.discontinuitySnapshotCount"),
            cadenceSnapshotCount = instrumentation.count("gate.cadenceSnapshotCount"),
            externalFullSnapshotCount = instrumentation.count("gate.externalFullSnapshotCount")
        )
}

internal fun mediaSessionRelevantStateChanged(
    previous: AudioPlaybackState,
    next: AudioPlaybackState
): Boolean = previous.songId != next.songId ||
    previous.loading != next.loading ||
    previous.playing != next.playing ||
    previous.playWhenReady != next.playWhenReady ||
    previous.durationMs != next.durationMs ||
    previous.ended != next.ended ||
    previous.error != next.error ||
    previous.suppression != next.suppression ||
    previous.tempo != next.tempo ||
    previous.repeatMode != next.repeatMode ||
    previous.shuffleEnabled != next.shuffleEnabled ||
    previous.canGoPrevious != next.canGoPrevious ||
    previous.canGoNext != next.canGoNext

internal fun mediaSessionPositionDiscontinuity(
    previous: AudioPlaybackState,
    next: AudioPlaybackState,
    elapsedRealtimeDeltaMs: Long
): Boolean {
    if (previous.positionMs == next.positionMs) return false
    if (!previous.playing || !next.playing) return true
    val positionDeltaMs = next.positionMs.toDouble() - previous.positionMs.toDouble()
    if (positionDeltaMs < -MEDIA_SESSION_OBSERVATION_BACKWARD_JITTER_TOLERANCE_MS) return true
    val expectedDeltaMs = elapsedRealtimeDeltaMs.coerceAtLeast(0L).toDouble() *
        next.tempo.coerceIn(AudioPlayback.MINIMUM_TEMPO, AudioPlayback.MAXIMUM_TEMPO)
    return kotlin.math.abs(positionDeltaMs - expectedDeltaMs) >
        MEDIA_SESSION_POSITION_DISCONTINUITY_TOLERANCE_MS
}

private const val MEDIA_SESSION_POSITION_DISCONTINUITY_TOLERANCE_MS = 250L
private const val MEDIA_SESSION_OBSERVATION_BACKWARD_JITTER_TOLERANCE_MS = 50L

internal fun AudioPlaybackSuppression.toSessionPlaybackSuppression(): SessionPlaybackSuppression =
    when (this) {
        AudioPlaybackSuppression.NONE -> SessionPlaybackSuppression.NONE
        AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS ->
            SessionPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
        AudioPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE ->
            SessionPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE
        AudioPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT ->
            SessionPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT
        AudioPlaybackSuppression.SCRUBBING -> SessionPlaybackSuppression.SCRUBBING
    }
