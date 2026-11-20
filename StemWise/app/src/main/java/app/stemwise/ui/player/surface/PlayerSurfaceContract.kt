package app.stemwise.ui.player.surface

import app.stemwise.model.StemSourceCatalogue

/** Maximum lane topology supported by the local StemWise stem contract. */
internal const val PLAYER_SURFACE_MAX_MIXER_LANES = StemSourceCatalogue.MAX_TARGETS + 1

/** Two state bits per lane must remain distinct beyond the sixteen-lane Int boundary. */
internal fun playerSurfaceMixerChromeStateMask(
    muted: BooleanArray,
    solo: BooleanArray,
    laneCount: Int
): Long {
    val count = laneCount.coerceIn(0, PLAYER_SURFACE_MAX_MIXER_LANES)
    require(muted.size >= count && solo.size >= count)
    var mask = 0L
    for (lane in 0 until count) {
        if (muted[lane]) mask = mask or (1L shl (lane * 2))
        if (solo[lane]) mask = mask or (1L shl (lane * 2 + 1))
    }
    return mask
}

/**
 * Exact owner of one native player-surface binding.
 *
 * [generation] is a process-local, strictly increasing binding token. The revision strings are
 * retained alongside it so a producer cannot accidentally bind metadata, audio, or stems from a
 * different version of the same song. A same-song reload must receive a new generation.
 */
internal data class PlayerSurfaceOwnerKey(
    val generation: Long,
    val songId: String,
    val mediaRevision: String,
    val audioRevision: String,
    val stemRevision: String
) {
    init {
        require(generation >= 0L)
        require(songId.isNotBlank())
    }

    internal fun sameIdentity(other: PlayerSurfaceOwnerKey): Boolean =
        songId == other.songId &&
            mediaRevision == other.mediaRevision &&
            audioRevision == other.audioRevision &&
            stemRevision == other.stemRevision
}

/**
 * Low-rate decoded-artwork palette publication from the native current-page owner.
 *
 * Every identity field is carried across the seam so Compose can reject a decode that completed
 * after the player moved to another generation, song, media revision, or physical source.
 */
internal fun interface PlayerSurfaceArtworkPaletteListener {
    fun onCurrentArtworkPalette(
        ownerGeneration: Long,
        songId: String,
        mediaRevision: String,
        physicalSource: String,
        primaryArgb: Int,
        secondaryArgb: Int,
        tertiaryArgb: Int,
        dominantArgb: Int
    )
}

internal enum class PlayerSurfaceSourceMode { ORIGINAL, STEMS }

internal enum class PlayerSurfaceSourcePhase { SETTLED, PREPARING, RETURNING, FAILED }

internal enum class PlayerSurfaceRepeatMode { OFF, ONE, ALL }

internal fun playerSurfaceRepeatMode(label: String): PlayerSurfaceRepeatMode = when (label) {
    "One" -> PlayerSurfaceRepeatMode.ONE
    "All" -> PlayerSurfaceRepeatMode.ALL
    else -> PlayerSurfaceRepeatMode.OFF
}

internal enum class PlayerSurfaceTool { NONE, LOOP, TUNE, LYRICS, METRONOME }

internal enum class PlayerSurfaceTransportCommand {
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    SHUFFLE_TOGGLE,
    REPEAT_CYCLE
}

/** Visual-only native button target for an already accepted semantic or keyboard action. */
internal sealed interface PlayerSurfaceSemanticFeedbackTarget {
    data object Collapse : PlayerSurfaceSemanticFeedbackTarget
    data object SongInfo : PlayerSurfaceSemanticFeedbackTarget
    data class Tool(val tool: PlayerSurfaceTool) : PlayerSurfaceSemanticFeedbackTarget
    data class Source(val mode: PlayerSurfaceSourceMode) : PlayerSurfaceSemanticFeedbackTarget
    data class Transport(
        val command: PlayerSurfaceTransportCommand
    ) : PlayerSurfaceSemanticFeedbackTarget
    data class MixerToggle(
        val laneIndex: Int,
        val mute: Boolean
    ) : PlayerSurfaceSemanticFeedbackTarget
}

internal enum class PlayerSurfaceLayoutDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/** Membership is copied once; selection packets share this immutable owner. */
internal class PlayerSurfaceQueueMembership(itemKeys: LongArray) {
    private val keys = itemKeys.copyOf()
    val size: Int get() = keys.size
    fun itemKeyAt(index: Int): Long = keys[index]
    fun copyItemKeys(): LongArray = keys.copyOf()
    fun hasSameItems(other: PlayerSurfaceQueueMembership): Boolean =
        this === other || keys.contentEquals(other.keys)
}

internal class PlayerSurfaceQueuePacket(
    val revision: Long,
    val membership: PlayerSurfaceQueueMembership,
    val currentIndex: Int
) {
    constructor(revision: Long, itemKeys: LongArray, currentIndex: Int) :
        this(revision, PlayerSurfaceQueueMembership(itemKeys), currentIndex)

    val itemCount: Int get() = membership.size

    init {
        require(revision >= 0L)
        require((itemCount == 0 && currentIndex == -1) || currentIndex in 0 until itemCount)
    }

    fun itemKeyAt(index: Int): Long = membership.itemKeyAt(index)
    fun copyItemKeys(): LongArray = membership.copyItemKeys()
    fun hasSameItems(other: PlayerSurfaceQueuePacket): Boolean = membership.hasSameItems(other.membership)
}

/** One atomic timeline publication; loop boundaries use -1 when unset. */
internal data class PlayerSurfaceTimelinePacket(
    val revision: Long,
    val durationMs: Long,
    val positionMs: Long,
    val loopStartMs: Long = -1L,
    val loopEndMs: Long = -1L,
    val waveformRevision: Long = 0L
) {
    init {
        require(revision >= 0L)
        require(durationMs >= 0L)
        require(positionMs in 0L..durationMs)
        require(loopStartMs == -1L || loopStartMs in 0L..durationMs)
        require(loopEndMs == -1L || loopEndMs in 0L..durationMs)
        require(waveformRevision >= 0L)
        require(
            (loopStartMs == -1L && loopEndMs == -1L) ||
                (loopStartMs >= 0L &&
                    (loopEndMs == -1L || loopEndMs > loopStartMs))
        )
    }
}

internal data class PlayerSurfaceSourcePacket(
    val revision: Long,
    val presentedMode: PlayerSurfaceSourceMode,
    val audibleMode: PlayerSurfaceSourceMode,
    val phase: PlayerSurfaceSourcePhase,
    val stemsAvailable: Boolean,
    /** False while the exact package is hydrating or its selected audio owner is still loading. */
    val stemsAvailabilitySettled: Boolean = true,
    val stemsDownloading: Boolean = false,
    val stemsPreparationFailed: Boolean = false
) {
    init {
        require(revision >= 0L)
    }
}

internal enum class PlayerSurfaceStemsStatus { DOWNLOAD, DOWNLOADING, PREPARING, SWITCHING, READY, FAILED }

internal fun playerSurfaceStemsStatus(source: PlayerSurfaceSourcePacket): PlayerSurfaceStemsStatus = when {
    source.phase == PlayerSurfaceSourcePhase.PREPARING ||
        source.phase == PlayerSurfaceSourcePhase.RETURNING -> PlayerSurfaceStemsStatus.SWITCHING
    source.phase == PlayerSurfaceSourcePhase.FAILED -> PlayerSurfaceStemsStatus.FAILED
    source.stemsAvailable && source.stemsAvailabilitySettled -> PlayerSurfaceStemsStatus.READY
    source.stemsPreparationFailed -> PlayerSurfaceStemsStatus.FAILED
    source.stemsDownloading -> PlayerSurfaceStemsStatus.DOWNLOADING
    !source.stemsAvailabilitySettled -> PlayerSurfaceStemsStatus.PREPARING
    else -> PlayerSurfaceStemsStatus.DOWNLOAD
}

/**
 * Atomic declared-lane mixer publication.
 *
 * [packageRevision] identifies lane topology/assets. A normal volume/mute/solo publication may
 * advance [revision] without changing the package and therefore must not interrupt a live drag.
 */
internal class PlayerSurfaceMixerPacket(
    val revision: Long,
    val packageRevision: Long,
    laneKeys: IntArray,
    volumes: FloatArray,
    laneFlags: IntArray
) {
    private val keys = laneKeys.copyOf()
    private val values = volumes.copyOf()
    private val flags = laneFlags.copyOf()

    val laneCount: Int get() = keys.size

    init {
        require(revision >= 0L)
        require(packageRevision >= 0L)
        require(keys.size <= PLAYER_SURFACE_MAX_MIXER_LANES)
        require(values.size == keys.size)
        require(flags.size == keys.size)
        require(keys.distinct().size == keys.size)
        require(values.all { it.isFinite() && it in 0f..1f })
        require(flags.all { it and FLAG_MASK.inv() == 0 })
    }

    fun laneKeyAt(index: Int): Int = keys[index]

    fun volumeAt(index: Int): Float = values[index]

    fun mutedAt(index: Int): Boolean = flags[index] and FLAG_MUTED != 0

    fun soloAt(index: Int): Boolean = flags[index] and FLAG_SOLO != 0

    companion object {
        const val FLAG_MUTED: Int = 1
        const val FLAG_SOLO: Int = 1 shl 1
        private const val FLAG_MASK: Int = FLAG_MUTED or FLAG_SOLO
    }
}

internal data class PlayerSurfaceTransportPacket(
    val revision: Long,
    val playWhenReady: Boolean,
    val repeatMode: PlayerSurfaceRepeatMode,
    val shuffleEnabled: Boolean,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean
) {
    init {
        require(revision >= 0L)
    }
}

/** Pure pixel bounds; the native View copies these values into fixed primitive arrays. */
internal data class PlayerSurfaceBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(right >= left)
        require(bottom >= top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Semantic content phase that runs alongside, but never retimes, the 520 ms profile morph. */
internal enum class PlayerSurfacePanelTransition {
    NONE,
    OPEN,
    CLOSE,
    REFLOW
}

/**
 * One low-frequency endpoint request for the portrait tool transition.
 *
 * Compose remains the endpoint oracle because it owns the production portrait policy and the
 * measured status/control envelope. The native surface owns time and every intermediate frame.
 * A newer [selectionEpoch] must run even when all geometry values are identical so the incoming
 * detail surface still receives one coherent latest-wins reveal.
 */
internal data class PlayerSurfaceToolMotionTarget(
    val generation: Long,
    val selectionEpoch: Long,
    val selectedTool: PlayerSurfaceTool,
    val durationMs: Int,
    val panelTransition: PlayerSurfacePanelTransition = PlayerSurfacePanelTransition.NONE,
    val panelContentDurationMs: Int = 0,
    val expandedArtworkSizePx: Float,
    val mixerCardHeightPx: Float,
    val mixerSpacingPx: Float,
    val listSpacingPx: Float,
    val seekerTrackHeightPx: Float,
    val transportHeightPx: Float,
    val controlBounds: PlayerSurfaceBounds,
    val detailViewportHeightPx: Float
) {
    init {
        require(generation >= 0L)
        require(selectionEpoch >= 0L)
        require(durationMs >= 0)
        require(panelContentDurationMs >= 0)
        require(expandedArtworkSizePx.isFinite() && expandedArtworkSizePx >= 0f)
        require(mixerCardHeightPx.isFinite() && mixerCardHeightPx >= 0f)
        require(mixerSpacingPx.isFinite() && mixerSpacingPx >= 0f)
        require(listSpacingPx.isFinite() && listSpacingPx >= 0f)
        require(seekerTrackHeightPx.isFinite() && seekerTrackHeightPx > 0f)
        require(transportHeightPx.isFinite() && transportHeightPx > 0f)
        require(controlBounds.width > 0f && controlBounds.height > 0f)
        require(detailViewportHeightPx.isFinite() && detailViewportHeightPx >= 0f)
    }
}

/** Layer-only follower for the one native tool-motion clock. */
internal interface PlayerSurfaceToolMotionListener {
    fun onToolMotionStarted(
        generation: Long,
        selectionEpoch: Long,
        currentControlTopPx: Float,
        currentControlBottomPx: Float
    )

    fun onToolMotionFrame(
        generation: Long,
        selectionEpoch: Long,
        panelContentEasedFraction: Float,
        currentControlTopPx: Float,
        currentControlBottomPx: Float
    )

    fun onToolMotionSettled(generation: Long, selectionEpoch: Long)
}

/** One measured native layout revision used to fence renderer profile adoption. */
internal data class PlayerSurfaceLayoutPacket(
    val revision: Long,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val direction: PlayerSurfaceLayoutDirection
) {
    init {
        require(revision >= 0L)
        require(widthPx > 0 && heightPx > 0)
        require(density.isFinite() && density > 0f)
    }
}

/**
 * One indivisible publication for the high-frequency player surface. No component may be read
 * from another packet while this one is being adopted.
 */
internal data class PlayerSurfaceProfilePacket(
    val profileRevision: Long,
    val owner: PlayerSurfaceOwnerKey,
    val queue: PlayerSurfaceQueuePacket,
    val timeline: PlayerSurfaceTimelinePacket,
    val source: PlayerSurfaceSourcePacket,
    val mixer: PlayerSurfaceMixerPacket,
    val transport: PlayerSurfaceTransportPacket,
    val layout: PlayerSurfaceLayoutPacket
) {
    init {
        require(profileRevision >= 0L)
    }
}

internal enum class FullPlayerLoopBoundary { START, END }

/** Complete generation-fenced command boundary for the full-player surface. */
/** One sink-owned volume transaction; reference identity fences delayed pointer/semantic events. */
internal class PlayerSurfaceVolumeGesture(val generation: Long, val laneIndex: Int)

internal interface FullPlayerActionSink {
    /** Opens the expanded player from the compact route without a delayed double-tap window. */
    fun onOpenPlayer(generation: Long): Boolean
    fun onCollapsePlayer(generation: Long): Boolean
    fun onShowSongInfo(generation: Long): Boolean

    /** [queueIndex] is the exact candidate; [offsetFraction] retains physical direction only. */
    fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float): Boolean
    fun onPagerCommit(generation: Long, queueIndex: Int): Boolean
    /** Native gestures identify the rendered song; queue indices can change before recomposition. */
    fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float, songId: String): Boolean =
        onPagerPreview(generation, queueIndex, offsetFraction)
    fun onPagerCommit(generation: Long, queueIndex: Int, songId: String): Boolean =
        onPagerCommit(generation, queueIndex)
    fun onPagerCancel(generation: Long)

    /** Returns the exact resulting queue index, or -1 for a true no-op/restart. */
    fun onNavigate(generation: Long, direction: PlayerSurfaceTransportCommand): Int

    /** Shared five-second seek for Original artwork and open Stems workspace gestures. */
    fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean

    fun onScrubBegin(generation: Long): Boolean
    fun onScrubPreview(generation: Long, positionMs: Long): Boolean
    fun onScrubCommit(generation: Long, positionMs: Long): Boolean
    fun onScrubCancel(generation: Long)

    fun onLoopBoundaryBegin(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        originalStartMs: Long,
        originalEndMs: Long
    ): Boolean
    fun onLoopBoundaryMove(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        transportPositionMs: Long,
        inputEventAtMonotonicMicros: Long,
        capturedBMs: Long
    ): Boolean
    fun onLoopBoundaryEnd(
        generation: Long,
        boundary: FullPlayerLoopBoundary,
        startMs: Long,
        endMs: Long,
        transportPositionMs: Long,
        inputEventAtMonotonicMicros: Long
    ): Boolean

    /** Low-frequency transition used to start/stop the imported 5 ms waveform window loader. */
    fun onFineModeChanged(generation: Long, active: Boolean, centerPositionMs: Long)


    fun onBeginVolume(generation: Long, laneIndex: Int): PlayerSurfaceVolumeGesture?

    /** Borrowed arrays are consumed before return and never retained. */
    fun onPreviewVolumeBatch(
        generation: Long,
        changedMask: Int,
        gestures: Array<PlayerSurfaceVolumeGesture?>,
        volumes: FloatArray
    ): Boolean {
        var accepted = true
        var lane = 0
        while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
            if (changedMask and (1 shl lane) != 0) {
                val gesture = gestures[lane]
                accepted = (gesture != null && gesture.generation == generation &&
                    onPreviewVolume(gesture, volumes[lane])) && accepted
            }
            lane++
        }
        return accepted
    }

    fun onPreviewVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean
    fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean
    fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture)
    fun onSetMuted(generation: Long, laneIndex: Int, muted: Boolean): Boolean
    fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean

    fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean
    fun onSource(generation: Long, target: PlayerSurfaceSourceMode): Boolean
    fun onTool(generation: Long, tool: PlayerSurfaceTool): Boolean
}
