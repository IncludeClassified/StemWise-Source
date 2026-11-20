package app.stemwise.ui


import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import app.stemwise.model.Song
import app.stemwise.model.Stem
import app.stemwise.analysis.SongSection
import app.stemwise.analysis.WaveformWindow
import app.stemwise.playback.engine.EngineStemMixPreviewLease
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.StemMixState
import app.stemwise.playback.engine.SourceWaveformIdentity
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal class ExplicitTransportMotionState {
    val cursor = ExplicitTransportCommandCursor()
    var pagerJob: Job? = null
}

/**
 * Assigns one native-surface generation to one presentation owner/control-owner handoff.
 *
 * The presentation repository can publish the new song before AudioPlayback publishes its first
 * matching control lease. Treating `(owner, null)` and `(owner, firstLeaseEpoch)` as two owners
 * restarts the native surface in the middle of an otherwise ordinary page-settle spring. The first
 * lease admitted for a newly observed presentation owner therefore completes that same handoff.
 * Once an epoch has been admitted, however, a different epoch for the same presentation owner is
 * a real same-song reload and receives a fresh surface generation.
 */
internal class PlayerSurfaceGenerationTracker(
    private val allocateGeneration: () -> Long = ::nextPlayerSurfaceGeneration
) {
    private var owner: SongPresentationOwner? = null
    private var admittedLeaseEpoch: Long? = null
    private var generation = 0L

    fun resolve(
        nextOwner: SongPresentationOwner,
        matchingLeaseEpoch: Long?
    ): Long {
        val currentOwner = owner
        if (currentOwner == null || currentOwner != nextOwner) {
            generation = allocateGeneration()
            check(generation > 0L) { "Player surface generation must be positive" }
            owner = nextOwner
            admittedLeaseEpoch = matchingLeaseEpoch
            return generation
        }

        if (matchingLeaseEpoch != null) {
            val previousEpoch = admittedLeaseEpoch
            if (previousEpoch != null && previousEpoch != matchingLeaseEpoch) {
                generation = allocateGeneration()
                check(generation > 0L) { "Player surface generation must be positive" }
            }
            admittedLeaseEpoch = matchingLeaseEpoch
        }
        return generation
    }
}

private val playerToolMotionEpochSerial = AtomicLong(0L)

internal fun nextPlayerToolMotionEpoch(): Long =
    playerToolMotionEpochSerial.incrementAndGet().also {
        check(it > 0L) { "Player tool-motion epoch space exhausted" }
    }

/**
 * Narrow observable queue projection for the three-slot artwork track.
 *
 * PlayerScreen event handlers read the plain current maps on the main thread. Only
 * [orderedSongsState] is Snapshot state, and it is observed below the player-track restart scope;
 * a Shuffle publication therefore cannot invalidate the 4k-line PlayerScreen root.
 */
@Stable
internal class PlayerQueueProjection(
    availableSongs: List<Song>,
    queueOrder: List<String>
) {
    private var availableInput: List<Song>? = null
    private var orderInput: List<String>? = null
    private val observableOrderedSongs = mutableStateOf(emptyList<Song>())
    val orderedSongsState: State<List<Song>> = observableOrderedSongs

    var orderedSongIds: List<String> = emptyList()
        private set
    var orderedSongById: Map<String, Song> = emptyMap()
        private set
    var orderedSongPageById: Map<String, Int> = emptyMap()
        private set

    init {
        update(availableSongs, queueOrder)
    }

    fun update(availableSongs: List<Song>, queueOrder: List<String>) {
        if (availableInput === availableSongs && orderInput === queueOrder) return
        availableInput = availableSongs
        orderInput = queueOrder
        val songById = availableSongs.associateBy(Song::id)
        val projectedIds = orderedPlayerSongIds(availableSongs.map(Song::id), queueOrder)
        val projectedSongs = projectedIds.mapNotNull(songById::get)
        orderedSongIds = projectedSongs.map(Song::id)
        orderedSongById = projectedSongs.associateBy(Song::id)
        orderedSongPageById = buildMap(projectedSongs.size) {
            projectedSongs.forEachIndexed { index, song -> put(song.id, index) }
        }
        if (observableOrderedSongs.value != projectedSongs) {
            observableOrderedSongs.value = projectedSongs
        }
    }
}

@Composable
internal fun PlayerQueueContent(
    projection: PlayerQueueProjection,
    content: @Composable (List<Song>) -> Unit
) {
    // This is the sole composition read of queue order. Its restart boundary deliberately wraps
    // only the bounded artwork surface supplied by the caller.
    content(projection.orderedSongsState.value)
}

internal data class PlayerSemanticsSongSelection(
    val song: Song,
    val queueIndex: Int
)

/**
 * Immutable O(1) song/index projection for the accessibility pager.
 *
 * Construct this only when the ordered queue changes. Position publications can then resolve the
 * semantic owner without rescanning a maximum-size queue (twice) on every accessibility clock tick.
 */
internal class PlayerSemanticsQueueIndex(orderedSongs: List<Song>) {
    private val songs = orderedSongs
    private val indexBySongId = buildMap(orderedSongs.size) {
        orderedSongs.forEachIndexed { index, song ->
            if (!containsKey(song.id)) put(song.id, index)
        }
    }

    val size: Int get() = songs.size

    init {
        require(songs.isNotEmpty())
    }

    fun resolve(semanticSongId: String, fallback: Song): PlayerSemanticsSongSelection {
        val semanticIndex = indexBySongId[semanticSongId]
        if (semanticIndex != null) {
            return PlayerSemanticsSongSelection(songs[semanticIndex], semanticIndex)
        }
        val fallbackIndex = indexBySongId[fallback.id]
        return if (fallbackIndex != null) {
            PlayerSemanticsSongSelection(songs[fallbackIndex], fallbackIndex)
        } else {
            // Preserve the preceding projection during the brief queue/owner handoff. The queue is
            // non-empty, so index zero remains a valid accessibility position until both converge.
            PlayerSemanticsSongSelection(fallback, 0)
        }
    }
}

internal data class PlayerSemanticsTimelineInputs(
    val durationMs: Long,
    val loopStartMs: Long?,
    val loopEndMs: Long?,
    val sections: List<SongSection>,
    val playWhenReady: Boolean
)

/**
 * Stable mixer identity with independently observable controls.
 *
 * Native previews remain plain logical values. Only terminal volume, mute, and solo values are
 * observable, so pointer-rate input cannot invalidate unrelated Compose chrome.
 */
@Stable
internal class StemUiSlot private constructor(
    val id: String,
    val label: String,
    val color: Color,
    /** Accessibility/public terminal value; pointer previews must not rebuild semantics at 120 Hz. */
    val committedVolume: MutableFloatState,
    val muted: MutableState<Boolean>,
    val solo: MutableState<Boolean>
) {
    /** Main-owned logical values; native portrait MOVE updates these without Snapshot writes. */
    private var latestVolume = committedVolume.floatValue
    private var latestMuted = muted.value
    fun snapshot(): Stem = Stem(
        id = id,
        label = label,
        color = color,
        volume = latestVolume,
        muted = latestMuted,
        solo = solo.value
    )


    fun sliderSnapshot(volume: Float): Stem = snapshot().copy(
        volume = stemSliderStoredVolume(volume, latestVolume), muted = volume <= 0f)

    fun update(stem: Stem) {
        require(stem.id == id && stem.label == label && stem.color == color)
        latestVolume = stem.volume
        latestMuted = stem.muted
        committedVolume.floatValue = stem.volume
        muted.value = stem.muted
        solo.value = stem.solo
    }

    /** Native portrait surface already owns pixels; retain only exact cross-control logic. */
    fun previewSliderLogical(volume: Float) {
        latestVolume = stemSliderStoredVolume(volume, latestVolume)
        latestMuted = volume <= 0f
    }

    /** Native full-player terminal boundary for volume, mute, and solo as one value. */
    fun commitNative(stem: Stem) {
        require(stem.id == id && stem.label == label && stem.color == color)
        update(stem)
    }

    /** Reconcile a rejected native terminal command with the last public committed control. */
    fun rollbackVolumePreview() {
        latestVolume = committedVolume.floatValue
        latestMuted = muted.value
    }

    companion object {
        fun from(stem: Stem): StemUiSlot = StemUiSlot(
            id = stem.id,
            label = stem.label,
            color = stem.color,
            committedVolume = mutableFloatStateOf(stem.volume),
            muted = mutableStateOf(stem.muted),
            solo = mutableStateOf(stem.solo)
        )
    }
}

internal data class PlayerWaveformBinding(
    val owner: SongPresentationOwner,
    val sourceMode: PlaybackSourceMode,
    val sourceReady: Boolean,
    val stemAssets: List<app.stemwise.data.StemAssetEntity>,
    val stemMix: Map<String, StemMixState>
)

internal data class PlayerFineWaveformKey(
    val binding: PlayerWaveformBinding,
    val native: SourceWaveformIdentity?
) {
    val available: Boolean get() = binding.sourceReady &&
        (binding.sourceMode == PlaybackSourceMode.ORIGINAL || native != null)
}

internal data class PlayerFineWaveformWindow(val binding: PlayerWaveformBinding, val window: WaveformWindow)

internal class NativeFineWaveformOwner {
    var job: Job? = null
    var requestedBucket: Long = Long.MIN_VALUE
    private var requestToken = 0L

    fun request(
        scope: CoroutineScope,
        bucket: Long,
        key: () -> PlayerFineWaveformKey,
        read: suspend (PlayerFineWaveformKey) -> WaveformWindow?,
        publish: (PlayerFineWaveformWindow?) -> Unit
    ): Job {
        if (requestedBucket == bucket && job?.isActive == true) return checkNotNull(job)
        job?.cancel()
        val token = ++requestToken
        requestedBucket = bucket
        return scope.launch {
            try {
                var previous: PlayerFineWaveformKey? = null
                var complete = false
                while (isActive) {
                    val current = key()
                    if (previous != current) {
                        if (requestToken == token) publish(null)
                        previous = current
                        complete = false
                    }
                    if (current.available && !complete) {
                        val window = try {
                            read(current)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (error !is java.io.IOException && error !is SecurityException &&
                                !(error is IllegalStateException && error.message == "LIBRARY_CHANGED")
                            ) throw error
                            null
                        }
                        currentCoroutineContext().ensureActive()
                        if (requestToken == token && key() == current) {
                            publish(window?.let { PlayerFineWaveformWindow(current.binding, it) })
                            complete = window != null
                        }
                    }
                    // Fine gestures alone observe callback acknowledgement and progressive PCM.
                    delay(100L)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (requestToken == token) requestedBucket = Long.MIN_VALUE
            }
        }.also { job = it }
    }

    fun cancel() {
        requestToken++
        job?.cancel()
        job = null
        requestedBucket = Long.MIN_VALUE
    }
}

internal data class PlayerControlFailure(val heading: String, val message: String) {
    companion object {
        fun source(message: String) = PlayerControlFailure("Source unavailable", message)
        fun control(message: String) = PlayerControlFailure("Control unavailable", message)
    }
}

internal fun playbackFailureHeading(fatalTransport: Boolean): String =
    if (fatalTransport) "Playback stopped" else "Playback error"

internal class NativeMixerGestureOwner {
    private val handles = arrayOfNulls<PlayerSurfaceVolumeGesture>(PLAYER_SURFACE_MAX_MIXER_LANES)
    private val canonicalLaneIndices = IntArray(PLAYER_SURFACE_MAX_MIXER_LANES) { -1 }
    private val previewLeases = arrayOfNulls<EngineStemMixPreviewLease>(PLAYER_SURFACE_MAX_MIXER_LANES)

    fun begin(gesture: PlayerSurfaceVolumeGesture, canonicalLaneIndex: Int,
        previewLease: EngineStemMixPreviewLease?): Boolean {
        val lane = gesture.laneIndex
        if (lane !in handles.indices || canonicalLaneIndex !in handles.indices || handles[lane] != null) return false
        handles[lane] = gesture
        previewLeases[lane] = previewLease
        canonicalLaneIndices[lane] = canonicalLaneIndex
        return true
    }

    fun owns(gesture: PlayerSurfaceVolumeGesture): Boolean =
        gesture.laneIndex in handles.indices && handles[gesture.laneIndex] === gesture

    fun handle(lane: Int): PlayerSurfaceVolumeGesture? = handles.getOrNull(lane)

    fun previewLease(gesture: PlayerSurfaceVolumeGesture): EngineStemMixPreviewLease? =
        if (owns(gesture)) previewLeases[gesture.laneIndex] else null

    fun canonicalLaneIndex(gesture: PlayerSurfaceVolumeGesture): Int =
        if (owns(gesture)) canonicalLaneIndices[gesture.laneIndex] else -1

    fun finish(gesture: PlayerSurfaceVolumeGesture): Boolean {
        if (!owns(gesture)) return false
        val lane = gesture.laneIndex
        handles[lane] = null
        previewLeases[lane] = null
        canonicalLaneIndices[lane] = -1
        return true
    }
}

/** Plain UI-thread lease for one directly manipulated A/B boundary. */
internal class NativeLoopBoundaryGestureOwner {
    private var boundary: FullPlayerLoopBoundary? = null
    private var acceptedRange: Pair<Long, Long>? = null
    private var heldBGestureId = 0L

    fun begin(
        candidate: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        durationMs: Long
    ): Boolean {
        if (boundary != null || !loopBoundaryGestureRangeIsValid(
                candidate,
                startMs,
                endMs,
                durationMs
            )
        ) {
            return false
        }
        boundary = candidate
        acceptedRange = startMs to endMs
        heldBGestureId = if (candidate == FullPlayerLoopBoundary.END) {
            nextPlayerToolMotionEpoch()
        } else {
            0L
        }
        return true
    }

    fun owns(candidate: FullPlayerLoopBoundary): Boolean = boundary == candidate

    fun accept(candidate: FullPlayerLoopBoundary, startMs: Long, endMs: Long) {
        if (!owns(candidate)) return
        acceptedRange = startMs to endMs
    }

    fun accepted(candidate: FullPlayerLoopBoundary): Pair<Long, Long>? {
        if (!owns(candidate)) return null
        return acceptedRange
    }

    fun gestureId(candidate: FullPlayerLoopBoundary): Long =
        heldBGestureId.takeIf { owns(candidate) } ?: 0L

    fun end(candidate: FullPlayerLoopBoundary): Pair<Long, Long>? {
        if (!owns(candidate)) return null
        val accepted = acceptedRange ?: return null
        clear()
        return accepted
    }

    fun clear() {
        boundary = null
        acceptedRange = null
        heldBGestureId = 0L
    }
}

/**
 * Endpoint-only Compose overlay for the native portrait tool clock. The status and active detail
 * body are measured once per target. During motion they follow the native control envelope through
 * RenderNode translation/alpha only; no animated value participates in composition or measure.
 */
