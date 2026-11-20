package app.stemwise.ui.player.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import app.stemwise.playback.STANDARD_SEEK_INCREMENT_MS
import app.stemwise.ui.design.stemwiseKeyboardRange
import app.stemwise.ui.design.stemwiseKeyboardAction
import app.stemwise.ui.quantizeSeekPosition
import app.stemwise.ui.spokenDuration
import app.stemwise.ui.stemSliderEffectiveFraction
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Committed, position-independent accessibility projection for [PlayerSurfaceView].
 *
 * The playback clock changes much more often than any of these fields. Keeping it out of this
 * model lets Compose retain the pager, source, mixer, transport, and tool semantics nodes while
 * the one timeline node publishes its coarse accessibility position.
 */
@Immutable
internal data class PlayerSurfaceStaticSemanticsModel(
    val generation: Long,
    val pager: PlayerSurfacePagerSemantics,
    val source: PlayerSurfaceSourceSemantics,
    val mixer: PlayerSurfaceMixerSemantics,
    val transport: PlayerSurfaceTransportSemantics,
    val tools: PlayerSurfaceToolSemantics
) {
    init {
        require(generation >= 0L)
        require(mixer.laneCount <= PLAYER_SURFACE_MAX_MIXER_LANES)
    }
}

@Immutable
internal data class PlayerSurfacePagerSemantics(
    val songId: String,
    val title: String,
    val artist: String,
    val isFlac: Boolean = false,
    val queueIndex: Int = 0,
    val queueSize: Int = 1,
    /** Matches the old immediate-open mini-player projection when true. */
    val compact: Boolean = false,
    /** False while route motion intentionally gives neither mini nor full controls input. */
    val controlsInteractive: Boolean = true
) {
    init {
        require(songId.isNotBlank())
        require(title.isNotBlank())
        require(queueSize > 0)
        require(queueIndex in 0 until queueSize)
    }
}

@Immutable
internal data class PlayerSurfaceTimelineSemantics(
    val durationMs: Long,
    val positionMs: Long,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val activeSectionLabel: String? = null
) {
    init {
        require(durationMs >= 0L)
        require(positionMs in 0L..durationMs)
        require(loopStartMs == null || loopStartMs in 0L..durationMs)
        require(loopEndMs == null || loopEndMs in 0L..durationMs)
    }
}

@Immutable
internal data class PlayerSurfaceSourceSemantics(
    val selectedMode: PlayerSurfaceSourceMode,
    val originalLabel: String = "Original",
    val stemsLabel: String = "Stems",
    val originalEnabled: Boolean = true,
    val stemsEnabled: Boolean
) {
    init {
        require(originalLabel.isNotBlank())
        require(stemsLabel.isNotBlank())
    }

    companion object {
        fun from(packet: PlayerSurfaceSourcePacket): PlayerSurfaceSourceSemantics =
            PlayerSurfaceSourceSemantics(
                selectedMode = packet.presentedMode,
                stemsLabel = when (playerSurfaceStemsStatus(packet)) {
                    PlayerSurfaceStemsStatus.PREPARING -> "Loading song parts"
                    PlayerSurfaceStemsStatus.SWITCHING -> "Switching audio source"
                    PlayerSurfaceStemsStatus.FAILED -> if (packet.phase == PlayerSurfaceSourcePhase.FAILED) {
                        "Source switch failed. Retry stems"
                    } else "Parts preparation failed. Open Activity for recovery"
                    PlayerSurfaceStemsStatus.DOWNLOADING -> "Preparing song parts. Open Activity for progress"
                    PlayerSurfaceStemsStatus.DOWNLOAD -> "Prepare song parts"
                    PlayerSurfaceStemsStatus.READY -> "Stems ready"
                },
                stemsEnabled = playerSurfaceStemsActionEnabled(packet)
            )
    }
}

@Immutable
internal class PlayerSurfaceMixerSemantics(
    lanes: List<PlayerSurfaceMixerLaneSemantics>) {
    private val committedLanes = lanes.toList()

    val laneCount: Int get() = committedLanes.size

    init {
        require(committedLanes.size <= PLAYER_SURFACE_MAX_MIXER_LANES)
    }

    fun laneAt(index: Int): PlayerSurfaceMixerLaneSemantics = committedLanes[index]
}

@Immutable
internal data class PlayerSurfaceMixerLaneSemantics(
    val label: String,
    val volume: Float,
    val muted: Boolean,
    val solo: Boolean,
    val enabled: Boolean = true
) {
    init {
        require(label.isNotBlank())
        require(volume.isFinite() && volume in 0f..1f)
    }
}

@Immutable
internal data class PlayerSurfaceTransportSemantics(
    val playWhenReady: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: PlayerSurfaceRepeatMode,
    /** Stable production targets normally remain enabled; the queue authority may no-op. */
    val previousEnabled: Boolean = true,
    val nextEnabled: Boolean = true
)

/** Committed tool-row state exported by the Compose accessibility projection. */
@Immutable
internal data class PlayerSurfaceToolSemantics(
    val selectedTool: PlayerSurfaceTool,
    val lyricsAvailable: Boolean,
    val metronomeAvailable: Boolean,
    val loopAvailable: Boolean = true,
    val loopPreparation: app.stemwise.playback.LoopPreparationState = app.stemwise.playback.LoopPreparationState()
) {
    companion object {
        fun from(packet: app.stemwise.ui.player.nativeplayer.FullPlayerToolDetailPacket) =
            PlayerSurfaceToolSemantics(
                selectedTool = app.stemwise.ui.availablePlayerSurfaceTool(packet.selectedTool,
                    packet.activity.lyricsAvailable, packet.activity.loopAvailable),
                lyricsAvailable = packet.activity.lyricsAvailable,
                metronomeAvailable = packet.activity.metronomeAvailable,
                loopAvailable = packet.activity.loopAvailable,
                loopPreparation = packet.activity.loopPreparation
            )
    }
    init {
        require(selectedTool != PlayerSurfaceTool.LOOP || loopAvailable)
        require(selectedTool != PlayerSurfaceTool.LYRICS || lyricsAvailable)
    }

    fun isSelected(tool: PlayerSurfaceTool): Boolean = selectedTool == tool

    fun isEnabled(tool: PlayerSurfaceTool): Boolean = when (tool) {
        PlayerSurfaceTool.LOOP -> loopAvailable
        PlayerSurfaceTool.LYRICS -> lyricsAvailable
        else -> true
    }
}

/** Exact bounds for the three exported controls in one mixer lane. */
@Immutable
internal data class PlayerSurfaceMixerLaneSemanticsBounds(
    val volume: PlayerSurfaceBounds,
    val mute: PlayerSurfaceBounds,
    val solo: PlayerSurfaceBounds
)

/**
 * Pixel-exact semantics geometry shared with the native renderer.
 *
 * The native View owns broad high-frequency input routing. Keeping its exact title, transport,
 * and mixer sub-bounds here avoids estimating them from accessibility code.
 */
@Immutable
internal class PlayerSurfaceSemanticsGeometry private constructor(
    val widthPx: Int,
    val heightPx: Int,
    val page: PlayerSurfaceBounds,
    val artwork: PlayerSurfaceBounds,
    val identity: PlayerSurfaceBounds,
    val title: PlayerSurfaceBounds,
    val artist: PlayerSurfaceBounds,
    val original: PlayerSurfaceBounds,
    val stems: PlayerSurfaceBounds,
    val seeker: PlayerSurfaceBounds,

    val shuffle: PlayerSurfaceBounds,
    val previous: PlayerSurfaceBounds,
    val playPause: PlayerSurfaceBounds,
    val miniPlayPause: PlayerSurfaceBounds = playPause,
    val next: PlayerSurfaceBounds,
    val repeat: PlayerSurfaceBounds,
    val collapse: PlayerSurfaceBounds,
    val info: PlayerSurfaceBounds,
    val loopTool: PlayerSurfaceBounds,
    val tuneTool: PlayerSurfaceBounds,
    val lyricsTool: PlayerSurfaceBounds,
    val metronomeTool: PlayerSurfaceBounds,
    mixerLanes: List<PlayerSurfaceMixerLaneSemanticsBounds>,
    val mixerViewport: PlayerSurfaceBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f),
    val mixerScrollOffsetPx: Float = 0f,
    val mixerScrollMaximumPx: Float = 0f
) {
    private val laneBounds = mixerLanes.toList()

    val mixerLaneCount: Int get() = laneBounds.size

    init {
        require(widthPx > 0 && heightPx > 0)
        require(laneBounds.size <= PLAYER_SURFACE_MAX_MIXER_LANES)
        check(firstInvalidRegion(widthPx, heightPx, baseRegions(), laneBounds) == null)
    }

    fun mixerLaneAt(index: Int): PlayerSurfaceMixerLaneSemanticsBounds = laneBounds[index]

    private fun baseRegions(): List<Pair<String, PlayerSurfaceBounds>> = listOf(
        "page" to page,
        "artwork" to artwork,
        "identity" to identity,
        "title" to title,
        "artist" to artist,
        "original" to original,
        "stems" to stems,
        "seeker" to seeker,

        "shuffle" to shuffle,
        "previous" to previous,
        "playPause" to playPause,
        "miniPlayPause" to miniPlayPause,
        "next" to next,
        "repeat" to repeat,
        "collapse" to collapse,
        "info" to info,
        "loopTool" to loopTool,
        "tuneTool" to tuneTool,
        "lyricsTool" to lyricsTool,
        "metronomeTool" to metronomeTool
    )

    internal companion object {
        /**
         * Publishes one exact geometry transaction or no transaction at all.
         *
         * Source/workspace and mini/full layout coordinates can legitimately be one display frame
         * apart. Returning null keeps the preceding semantics projection withdrawn until the
         * sampler observes one complete in-surface frame; it never clamps a target to coordinates
         * that the native renderer does not actually own.
         */
        fun createOrNull(
            widthPx: Int,
            heightPx: Int,
            page: PlayerSurfaceBounds,
            artwork: PlayerSurfaceBounds,
            identity: PlayerSurfaceBounds,
            title: PlayerSurfaceBounds,
            artist: PlayerSurfaceBounds,
            original: PlayerSurfaceBounds,
            stems: PlayerSurfaceBounds,
            seeker: PlayerSurfaceBounds,

            shuffle: PlayerSurfaceBounds,
            previous: PlayerSurfaceBounds,
            playPause: PlayerSurfaceBounds,
            miniPlayPause: PlayerSurfaceBounds = playPause,
            next: PlayerSurfaceBounds,
            repeat: PlayerSurfaceBounds,
            collapse: PlayerSurfaceBounds,
            info: PlayerSurfaceBounds,
            loopTool: PlayerSurfaceBounds,
            tuneTool: PlayerSurfaceBounds,
            lyricsTool: PlayerSurfaceBounds,
            metronomeTool: PlayerSurfaceBounds,
            mixerLanes: List<PlayerSurfaceMixerLaneSemanticsBounds>,
            mixerViewport: PlayerSurfaceBounds = PlayerSurfaceBounds(0f, 0f, 0f, 0f),
            mixerScrollOffsetPx: Float = 0f,
            mixerScrollMaximumPx: Float = 0f
        ): PlayerSurfaceSemanticsGeometry? {
            if (widthPx <= 0 || heightPx <= 0) return null
            if (mixerLanes.size > PLAYER_SURFACE_MAX_MIXER_LANES) return null
            if (!mixerViewport.isInside(widthPx, heightPx) ||
                !mixerScrollOffsetPx.isFinite() || !mixerScrollMaximumPx.isFinite() ||
                mixerScrollMaximumPx < 0f || mixerScrollOffsetPx !in 0f..mixerScrollMaximumPx ||
                (mixerScrollMaximumPx > 0f && mixerViewport.height <= 0f)
            ) return null
            val committedLanes = mixerLanes.toList()
            val base = listOf(
                "page" to page,
                "artwork" to artwork,
                "identity" to identity,
                "title" to title,
                "artist" to artist,
                "original" to original,
                "stems" to stems,
                "seeker" to seeker,

                "shuffle" to shuffle,
                "previous" to previous,
                "playPause" to playPause,
                "miniPlayPause" to miniPlayPause,
                "next" to next,
                "repeat" to repeat,
                "collapse" to collapse,
                "info" to info,
                "loopTool" to loopTool,
                "tuneTool" to tuneTool,
                "lyricsTool" to lyricsTool,
                "metronomeTool" to metronomeTool
            )
            if (firstInvalidRegion(widthPx, heightPx, base, committedLanes) != null) return null
            return PlayerSurfaceSemanticsGeometry(
                widthPx = widthPx,
                heightPx = heightPx,
                page = page,
                artwork = artwork,
                identity = identity,
                title = title,
                artist = artist,
                original = original,
                stems = stems,
                seeker = seeker,

                shuffle = shuffle,
                previous = previous,
                playPause = playPause,
                miniPlayPause = miniPlayPause,
                next = next,
                repeat = repeat,
                collapse = collapse,
                info = info,
                loopTool = loopTool,
                tuneTool = tuneTool,
                lyricsTool = lyricsTool,
                metronomeTool = metronomeTool,
                mixerLanes = committedLanes,
                mixerViewport = mixerViewport,
                mixerScrollOffsetPx = mixerScrollOffsetPx,
                mixerScrollMaximumPx = mixerScrollMaximumPx
            )
        }

        internal fun firstInvalidRegion(
            widthPx: Int,
            heightPx: Int,
            baseRegions: List<Pair<String, PlayerSurfaceBounds>>,
            mixerLanes: List<PlayerSurfaceMixerLaneSemanticsBounds>
        ): String? {
            if (widthPx <= 0 || heightPx <= 0) return "surface"
            baseRegions.firstOrNull { (_, bounds) ->
                !bounds.isInside(widthPx, heightPx)
            }?.let { return it.first }
            mixerLanes.forEachIndexed { index, lane ->
                if (!lane.volume.isInside(widthPx, heightPx)) return "mixer[$index].volume"
                if (!lane.mute.isInside(widthPx, heightPx)) return "mixer[$index].mute"
                if (!lane.solo.isInside(widthPx, heightPx)) return "mixer[$index].solo"
            }
            return null
        }

        private fun PlayerSurfaceBounds.isInside(widthPx: Int, heightPx: Int): Boolean =
            left >= 0f && top >= 0f && right <= widthPx && bottom <= heightPx
    }
}

internal fun playerSurfacePlayPauseDescription(playWhenReady: Boolean): String =
    if (playWhenReady) "Pause" else "Play"

/**
 * Generation-aware action boundary for semantics and accessibility commands.
 *
 * Implementations must validate [generation] against their current playback lease. Physical
 * touch does not call this sink; it falls through to the native View below the overlay.
 */
@Stable
internal interface PlayerSurfaceSemanticsActionSink {
    fun onOpenPlayer(generation: Long): Boolean
    fun onCollapsePlayer(generation: Long): Boolean
    fun onShowSongInfo(generation: Long): Boolean
    fun onTool(generation: Long, tool: PlayerSurfaceTool): Boolean
    fun onPagerStep(generation: Long, delta: Int): Boolean
    fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean
    fun onSeekTo(generation: Long, positionMs: Long): Boolean
    fun onSeekBy(generation: Long, deltaMs: Long): Boolean
    fun onSource(generation: Long, target: PlayerSurfaceSourceMode): Boolean
    fun onMixerScroll(generation: Long, deltaPx: Float): Boolean = false
    fun onMixerVolume(generation: Long, laneIndex: Int, volume: Float): Boolean
    fun onMixerMute(generation: Long, laneIndex: Int, muted: Boolean): Boolean
    fun onMixerSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean
    fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean
}

/**
 * Transparent compatibility projection for tests, TalkBack, and Switch Access.
 *
 * This composable emits layout/semantics nodes only. It intentionally contains no Canvas,
 * draw modifier, pointerInput, clickable, toggleable, or selectable modifier, allowing every raw
 * DOWN/MOVE/UP stream to reach the native [PlayerSurfaceView] underneath it.
 */
@Composable
internal fun PlayerSurfaceSemanticsOverlay(
    model: PlayerSurfaceStaticSemanticsModel,
    timelineState: State<PlayerSurfaceTimelineSemantics>,
    geometry: PlayerSurfaceSemanticsGeometry,
    lowerControlsInteractive: Boolean = true,
    transportVisible: Boolean = true,
    actionSink: PlayerSurfaceSemanticsActionSink,

    modifier: Modifier = Modifier
) {
    // Geometry is sampled only at stable interaction phases. Keep the sampled geometry and its
    // semantic model in the same composition transaction: a source/package transition may bind
    // the native mixer one frame after Compose publishes the new lane model. During that brief
    // handoff no lane index is authoritative, so exporting no overlay is safer than exposing a
    // stale action (and must never turn the transient mismatch into a process crash).
    if (!playerSurfaceSemanticsMixerGeometryIsCompatible(
            modelLaneCount = model.mixer.laneCount,
            geometryLaneCount = geometry.mixerLaneCount
        )
    ) return
    PlayerSurfaceSemanticsOverlayContent(
        model = model,
        timelineState = timelineState,
        geometry = geometry,
        lowerControlsInteractive = lowerControlsInteractive,
        transportVisible = transportVisible,
        actionSink = actionSink,

        modifier = modifier
    )
}

internal fun playerSurfaceSemanticsMixerGeometryIsCompatible(
    modelLaneCount: Int,
    geometryLaneCount: Int
): Boolean {
    require(modelLaneCount in 0..PLAYER_SURFACE_MAX_MIXER_LANES)
    require(geometryLaneCount in 0..PLAYER_SURFACE_MAX_MIXER_LANES)
    return modelLaneCount == geometryLaneCount
}

internal fun playerSurfaceSemanticsGeometryIsReadyToSample(
    expectedLaneCount: Int,
    nativeLaneCount: Int?
): Boolean {
    require(expectedLaneCount in 0..PLAYER_SURFACE_MAX_MIXER_LANES)
    return nativeLaneCount == expectedLaneCount
}

internal enum class PlayerSurfaceSemanticsGeometryRetryMode {
    NEXT_DISPLAY_FRAME,
    LOW_RATE_DELAY
}

/**
 * Geometry normally catches the model in the next apply/frame. A decoder/source handoff can take
 * longer, so the recovery becomes cheap rather than terminal after the fast window.
 */
internal fun playerSurfaceSemanticsGeometryRetryMode(
    completedAttempts: Int
): PlayerSurfaceSemanticsGeometryRetryMode {
    require(completedAttempts >= 0)
    return if (completedAttempts < PLAYER_SURFACE_SEMANTICS_FAST_GEOMETRY_RETRIES) {
        PlayerSurfaceSemanticsGeometryRetryMode.NEXT_DISPLAY_FRAME
    } else {
        PlayerSurfaceSemanticsGeometryRetryMode.LOW_RATE_DELAY
    }
}

internal fun playerSurfaceArtworkSemanticsVisible(
    compact: Boolean,
    sourceMode: PlayerSurfaceSourceMode
): Boolean = !compact && sourceMode == PlayerSurfaceSourceMode.ORIGINAL

@Composable
private fun PlayerSurfaceSemanticsOverlayContent(
    model: PlayerSurfaceStaticSemanticsModel,
    timelineState: State<PlayerSurfaceTimelineSemantics>,
    geometry: PlayerSurfaceSemanticsGeometry,
    lowerControlsInteractive: Boolean,
    transportVisible: Boolean,
    actionSink: PlayerSurfaceSemanticsActionSink,

    modifier: Modifier
) {
    val targetCount = BASE_SEMANTICS_TARGET_COUNT + 1
    val artworkVisible = playerSurfaceArtworkSemanticsVisible(
        compact = model.pager.compact,
        sourceMode = model.source.selectedMode
    )
    val measuredTargets = remember(targetCount) { arrayOfNulls<Placeable>(targetCount) }
    val viewport = geometry.mixerViewport
    fun targetBounds(index: Int) = if (index == BASE_SEMANTICS_TARGET_COUNT) viewport
        else geometry.semanticsBoundsAt(index, model.pager.compact)
    Box(modifier) {
        Layout(
        modifier = Modifier
            .testTag("player_source_mode_group")
            .selectableGroup(),
        content = {
            SemanticsTarget(
                if (!model.pager.controlsInteractive) {
                    Modifier
                        .testTag("player_track_pager")
                        .clearAndSetSemantics { }
                } else {
                    Modifier
                        .testTag("player_track_pager")
                        .semantics {
                            stateDescription =
                                "Song ${model.pager.queueIndex + 1} of ${model.pager.queueSize}"
                            customActions = buildList {
                                if (model.pager.queueIndex > 0) {
                                    add(CustomAccessibilityAction("Previous song") {
                                        actionSink.onPagerStep(model.generation, -1)
                                    })
                                }
                                if (model.pager.queueIndex + 1 < model.pager.queueSize) {
                                    add(CustomAccessibilityAction("Next song") {
                                        actionSink.onPagerStep(model.generation, 1)
                                    })
                                }
                            }
                        }
                }
            )
            SemanticsTarget(Modifier.testTag("player_song_page_${model.pager.songId}"))
            SemanticsTarget(
                if (!model.pager.controlsInteractive || !artworkVisible) {
                    Modifier
                        .testTag("player_artwork_${model.pager.songId}")
                        .clearAndSetSemantics { }
                } else {
                    Modifier
                        .testTag("player_artwork_${model.pager.songId}")
                        .clearAndSetSemantics {
                            contentDescription = "${model.pager.title} artwork"
                            customActions = listOf(
                                CustomAccessibilityAction("Rewind 5 seconds") {
                                    actionSink.onArtworkSkip(
                                        model.generation,
                                        -STANDARD_SEEK_INCREMENT_MS
                                    )
                                },
                                CustomAccessibilityAction("Forward 5 seconds") {
                                    actionSink.onArtworkSkip(
                                        model.generation,
                                        STANDARD_SEEK_INCREMENT_MS
                                    )
                                }
                            )
                        }
                }
            )
            SemanticsTarget(
                if (!model.pager.controlsInteractive) {
                    Modifier
                        .testTag("player_identity_${model.pager.songId}")
                        .clearAndSetSemantics { }
                } else if (model.pager.compact) {
                    Modifier
                        .stemwiseKeyboardAction { actionSink.onOpenPlayer(model.generation) }
                        .testTag("player_identity_${model.pager.songId}")
                        .clearAndSetSemantics {
                            contentDescription =
                                "${model.pager.title}, ${model.pager.artist}"
                            onClick(label = "Open player") {
                                actionSink.onOpenPlayer(model.generation)
                            }
                        }
                } else {
                    Modifier.testTag("player_identity_${model.pager.songId}")
                }
            )
            SemanticsTarget(
                Modifier.clearAndSetSemantics {
                    if (!model.pager.compact) {
                        text = AnnotatedString(model.pager.title)
                        if (model.pager.isFlac) stateDescription = "FLAC"
                    }
                }
            )
            SemanticsTarget(
                Modifier.clearAndSetSemantics {
                    if (!model.pager.compact &&
                        model.pager.artist.isNotBlank()
                    ) {
                        text = AnnotatedString(model.pager.artist)
                    }
                }
            )
            SemanticsTarget(
                if (model.pager.compact) Modifier.clearAndSetSemantics { } else {
                    sourceSemanticsModifier(
                        generation = model.generation,
                        label = model.source.originalLabel,
                        selected = model.source.selectedMode == PlayerSurfaceSourceMode.ORIGINAL,
                        enabled = model.source.originalEnabled,
                        target = PlayerSurfaceSourceMode.ORIGINAL,
                        actionSink = actionSink
                    )
                }
            )
            SemanticsTarget(
                if (model.pager.compact) Modifier.clearAndSetSemantics { } else {
                    sourceSemanticsModifier(
                        generation = model.generation,
                        label = model.source.stemsLabel,
                        selected = model.source.selectedMode == PlayerSurfaceSourceMode.STEMS,
                        enabled = model.source.stemsEnabled,
                        target = PlayerSurfaceSourceMode.STEMS,
                        actionSink = actionSink
                    )
                }
            )
            PlayerSurfaceTimelineSemanticsTarget(
                generation = model.generation,
                compact = model.pager.compact || !transportVisible,
                timelineState = timelineState,
                actionSink = actionSink
            )
            SemanticsTarget(
                (if (model.pager.compact || !transportVisible) Modifier.clearAndSetSemantics { } else transportSemanticsModifier(
                    generation = model.generation,
                    description = "Shuffle: ${if (model.transport.shuffleEnabled) "On" else "Off"}",
                    enabled = true,
                    command = PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact || !transportVisible) Modifier.clearAndSetSemantics { } else transportSemanticsModifier(
                    generation = model.generation,
                    description = "Previous track or restart",
                    enabled = model.transport.previousEnabled,
                    command = PlayerSurfaceTransportCommand.PREVIOUS,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (!model.pager.controlsInteractive || (!model.pager.compact && !transportVisible)) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    transportSemanticsModifier(
                        generation = model.generation,
                        description = playerSurfacePlayPauseDescription(
                            model.transport.playWhenReady
                        ),
                        enabled = true,
                        command = PlayerSurfaceTransportCommand.PLAY_PAUSE,
                        actionSink = actionSink
                    )
                }).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact || !transportVisible) Modifier.clearAndSetSemantics { } else transportSemanticsModifier(
                    generation = model.generation,
                    description = "Next track",
                    enabled = model.transport.nextEnabled,
                    command = PlayerSurfaceTransportCommand.NEXT,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact || !transportVisible) Modifier.clearAndSetSemantics { } else transportSemanticsModifier(
                    generation = model.generation,
                    description = "Repeat mode: ${model.transport.repeatMode.spokenName}",
                    enabled = true,
                    command = PlayerSurfaceTransportCommand.REPEAT_CYCLE,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                if (model.pager.compact) Modifier.clearAndSetSemantics { } else playerActionSemanticsModifier(
                    description = "Collapse player",
                    onClick = { actionSink.onCollapsePlayer(model.generation) }
                )
            )
            SemanticsTarget(
                if (model.pager.compact) Modifier.clearAndSetSemantics { } else playerActionSemanticsModifier(
                    description = "Song details",
                    onClick = { actionSink.onShowSongInfo(model.generation) }
                )
            )
            SemanticsTarget(
                (if (model.pager.compact) Modifier.clearAndSetSemantics { } else toolSemanticsModifier(
                    generation = model.generation,
                    description = "A-B loop",
                    tool = PlayerSurfaceTool.LOOP,
                    tools = model.tools,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact) Modifier.clearAndSetSemantics { } else toolSemanticsModifier(
                    generation = model.generation,
                    description = app.stemwise.ui.PLAYER_DETAIL_TUNE,
                    tool = PlayerSurfaceTool.TUNE,
                    tools = model.tools,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact) Modifier.clearAndSetSemantics { } else toolSemanticsModifier(
                    generation = model.generation,
                    description = "Lyrics",
                    tool = PlayerSurfaceTool.LYRICS,
                    tools = model.tools,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            SemanticsTarget(
                (if (model.pager.compact) Modifier.clearAndSetSemantics { } else toolSemanticsModifier(
                    generation = model.generation,
                    description = app.stemwise.ui.PLAYER_DETAIL_METRONOME,
                    tool = PlayerSurfaceTool.METRONOME,
                    tools = model.tools,
                    actionSink = actionSink
                )).lowerControlSemantics(lowerControlsInteractive || model.pager.compact)
            )
            // The scroll owner contains its controls. A sibling covering this viewport hides
            // the lane nodes from Android accessibility even though their pixels remain visible.
            Layout(
                modifier = if (!model.pager.compact && geometry.mixerScrollMaximumPx > 0f) {
                    Modifier.testTag("player_mixer_list").semantics {
                        contentDescription = "Song parts"
                        verticalScrollAxisRange = ScrollAxisRange(
                            value = { geometry.mixerScrollOffsetPx }, maxValue = { geometry.mixerScrollMaximumPx }
                        )
                        scrollBy { _, y -> actionSink.onMixerScroll(model.generation, y) }
                        customActions = listOf(
                            CustomAccessibilityAction("Previous parts") {
                                actionSink.onMixerScroll(model.generation, -viewport.height)
                            },
                            CustomAccessibilityAction("Next parts") {
                                actionSink.onMixerScroll(model.generation, viewport.height)
                            }
                        )
                    }
                } else Modifier,
                content = {
                    var laneIndex = 0
                    while (laneIndex < model.mixer.laneCount) {
                        val lane = model.mixer.laneAt(laneIndex)
                        val semanticVolume = stemSliderEffectiveFraction(lane.volume, lane.muted)
                        val semanticMuted = lane.muted || lane.volume <= 0f
                        val exactLaneIndex = laneIndex
                        val laneVisible = geometry.mixerLaneAt(laneIndex).volume.height > 0f
                        SemanticsTarget(
                            if (model.pager.compact || !laneVisible) Modifier.clearAndSetSemantics { } else Modifier
                                .stemwiseKeyboardRange(semanticVolume, 0f..1f, .05f, lane.enabled) {
                                    actionSink.onMixerVolume(model.generation, exactLaneIndex, it)
                                }.clearAndSetSemantics {
                                contentDescription = "${lane.label} volume"
                                stateDescription = "${(semanticVolume * 100).roundToInt()} percent"
                                progressBarRangeInfo = ProgressBarRangeInfo(semanticVolume, 0f..1f)
                                if (!lane.enabled) {
                                    disabled()
                                } else {
                                    setProgress { requested ->
                                        val normalized = requested.coerceIn(0f, 1f)
                                        if (normalized == semanticVolume) {
                                            false
                                        } else {
                                            actionSink.onMixerVolume(
                                                model.generation,
                                                exactLaneIndex,
                                                normalized
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        SemanticsTarget(
                            if (model.pager.compact || !laneVisible) Modifier.clearAndSetSemantics { } else mixerToggleSemanticsModifier(
                                description =
                                    "Mute ${lane.label}: ${if (semanticMuted) "On" else "Off"}",
                                checked = semanticMuted,
                                enabled = lane.enabled,
                                onToggle = { enabled ->
                                    actionSink.onMixerMute(
                                        model.generation,
                                        exactLaneIndex,
                                        enabled
                                    )
                                }
                            )
                        )
                        SemanticsTarget(
                            if (model.pager.compact || !laneVisible) Modifier.clearAndSetSemantics { } else mixerToggleSemanticsModifier(
                                description =
                                    "Solo ${lane.label}: ${if (lane.solo) "On" else "Off"}",
                                checked = lane.solo,
                                enabled = lane.enabled,
                                onToggle = { enabled ->
                                    actionSink.onMixerSolo(
                                        model.generation,
                                        exactLaneIndex,
                                        enabled
                                    )
                                }
                            )
                        )
                        laneIndex++
                    }
                }
            ) { children, constraints ->
                val targets = children.mapIndexed { index, measurable ->
                    val bounds = geometry.semanticsBoundsAt(BASE_SEMANTICS_TARGET_COUNT + index, model.pager.compact)
                    measurable.measure(Constraints.fixed(
                        (ceil(bounds.right).toInt() - floor(bounds.left).toInt()).coerceAtLeast(0),
                        (ceil(bounds.bottom).toInt() - floor(bounds.top).toInt()).coerceAtLeast(0)
                    ))
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    targets.forEachIndexed { index, target ->
                        val bounds = geometry.semanticsBoundsAt(BASE_SEMANTICS_TARGET_COUNT + index, model.pager.compact)
                        target.place(floor(bounds.left).toInt() - floor(viewport.left).toInt(),
                            floor(bounds.top).toInt() - floor(viewport.top).toInt())
                    }
                }
            }
        }
    ) { measurables, constraints ->
        check(measurables.size == targetCount)
        var index = 0
        while (index < targetCount) {
            val bounds = targetBounds(index)
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            val width = (ceil(bounds.right).toInt() - left).coerceAtLeast(0)
            val height = (ceil(bounds.bottom).toInt() - top).coerceAtLeast(0)
            measuredTargets[index] = measurables[index].measure(Constraints.fixed(width, height))
            index++
        }
        val width = geometry.widthPx.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = geometry.heightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var placementIndex = 0
            while (placementIndex < targetCount) {
                val bounds = targetBounds(placementIndex)
                measuredTargets[placementIndex]?.place(
                    floor(bounds.left).toInt(),
                    floor(bounds.top).toInt()
                )
                placementIndex++
            }
        }
    }
}
}

/** The only restart scope that observes the playback-position accessibility clock. */
@Composable
private fun PlayerSurfaceTimelineSemanticsTarget(
    generation: Long,
    compact: Boolean,
    timelineState: State<PlayerSurfaceTimelineSemantics>,
    actionSink: PlayerSurfaceSemanticsActionSink
) {
    val timeline = timelineState.value
    val semanticDuration = timeline.durationMs.coerceAtLeast(0L)
    val semanticPosition = timeline.positionMs.coerceIn(0L, semanticDuration)
    SemanticsTarget(
        if (compact) Modifier.clearAndSetSemantics { } else Modifier
            .stemwiseKeyboardRange(semanticPosition.toFloat(), 0f..semanticDuration.toFloat(),
                STANDARD_SEEK_INCREMENT_MS.toFloat(), semanticDuration > 0L,
                onStep = { delta -> actionSink.onSeekBy(generation, delta.toLong()) }) {
                actionSink.onSeekTo(generation, quantizeSeekPosition(it.toDouble(), semanticDuration))
            }.clearAndSetSemantics {
            contentDescription = "Playback position"
            stateDescription = playerSurfaceTimelineStateDescription(timeline)
            if (semanticDuration <= 0L) {
                disabled()
            } else {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = semanticPosition.toFloat(),
                    range = 0f..semanticDuration.toFloat()
                )
                setProgress { requested ->
                    actionSink.onSeekTo(
                        generation,
                        quantizeSeekPosition(requested.toDouble(), semanticDuration)
                    )
                }
                customActions = listOf(
                    CustomAccessibilityAction("Seek backward 5 seconds") {
                        actionSink.onSeekBy(generation, -STANDARD_SEEK_INCREMENT_MS)
                    },
                    CustomAccessibilityAction("Seek forward 5 seconds") {
                        actionSink.onSeekBy(generation, STANDARD_SEEK_INCREMENT_MS)
                    },
                    CustomAccessibilityAction("Nudge backward 5 milliseconds") {
                        actionSink.onSeekBy(generation, -5L)
                    },
                    CustomAccessibilityAction("Nudge forward 5 milliseconds") {
                        actionSink.onSeekBy(generation, 5L)
                    }
                )
            }
        }
    )
}

@Composable
private fun SemanticsTarget(modifier: Modifier) {
    Layout(modifier = modifier, content = {}) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}

private fun sourceSemanticsModifier(
    generation: Long,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    target: PlayerSurfaceSourceMode,
    actionSink: PlayerSurfaceSemanticsActionSink
): Modifier {
    val activate = { actionSink.onSource(generation, target) }
    return Modifier.stemwiseKeyboardAction(enabled, activate).clearAndSetSemantics {
        text = AnnotatedString(label)
        this.selected = selected
        role = Role.Tab
        if (!enabled) disabled() else onClick { activate() }
    }
}

private fun transportSemanticsModifier(
    generation: Long,
    description: String,
    enabled: Boolean,
    command: PlayerSurfaceTransportCommand,
    actionSink: PlayerSurfaceSemanticsActionSink
): Modifier {
    val activate = { actionSink.onTransport(generation, command) }
    return Modifier.stemwiseKeyboardAction(enabled, activate).clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        if (!enabled) disabled() else onClick { activate() }
    }
}

private fun Modifier.lowerControlSemantics(interactive: Boolean): Modifier =
    if (interactive) this else Modifier.clearAndSetSemantics { }

private fun playerActionSemanticsModifier(
    description: String,
    onClick: () -> Boolean
): Modifier = Modifier.stemwiseKeyboardAction(onActivate = onClick).clearAndSetSemantics {
    contentDescription = description
    role = Role.Button
    onClick { onClick() }
}

private fun toolSemanticsModifier(
    generation: Long,
    description: String,
    tool: PlayerSurfaceTool,
    tools: PlayerSurfaceToolSemantics,
    actionSink: PlayerSurfaceSemanticsActionSink
): Modifier {
    val enabled = tools.isEnabled(tool)
    val activate = { actionSink.onTool(generation, tool) }
    return Modifier.stemwiseKeyboardAction(enabled, activate).clearAndSetSemantics {
        contentDescription = description
        role = Role.Button
        selected = tools.isSelected(tool)
        if (!enabled) {
            disabled()
            stateDescription = if (tool == PlayerSurfaceTool.LOOP) {
                app.stemwise.ui.loopPreparationDescription(tools.loopPreparation)
            } else {
                "Add a complete song ZIP with timing data to enable this control"
            }
        } else {
            if (tool == PlayerSurfaceTool.LOOP) {
                stateDescription = app.stemwise.ui.loopPreparationDescription(tools.loopPreparation)
            }
            onClick { activate() }
        }
    }
}

private fun mixerToggleSemanticsModifier(
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Boolean
): Modifier {
    val activate = { onToggle(!checked) }
    return Modifier.stemwiseKeyboardAction(enabled, activate).clearAndSetSemantics {
        contentDescription = description
        role = Role.Switch
        toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        if (!enabled) disabled() else onClick { activate() }
    }
}

internal fun playerSurfaceTimelineStateDescription(
    timeline: PlayerSurfaceTimelineSemantics
): String = buildString {
    append("${spokenDuration(timeline.positionMs)} of ${spokenDuration(timeline.durationMs)}")
    timeline.loopStartMs?.let { append(", loop A ${spokenDuration(it)}") }
    timeline.loopEndMs?.let { append(", loop B ${spokenDuration(it)}") }
    timeline.activeSectionLabel?.takeIf(String::isNotBlank)?.let {
        append(", section $it")
    }
}

private val PlayerSurfaceRepeatMode.spokenName: String
    get() = when (this) {
        PlayerSurfaceRepeatMode.OFF -> "Off"
        PlayerSurfaceRepeatMode.ONE -> "One"
        PlayerSurfaceRepeatMode.ALL -> "All"
    }

internal fun PlayerSurfaceSemanticsGeometry.semanticsBoundsAt(
    index: Int,
    compact: Boolean
): PlayerSurfaceBounds = when (index) {
    0 -> page
    1 -> page
    2 -> artwork
    3 -> identity
    4 -> title
    5 -> artist
    6 -> original
    7 -> stems
    8 -> seeker
    9 -> shuffle
    10 -> previous
    11 -> if (compact) miniPlayPause else playPause
    12 -> next
    13 -> repeat
    14 -> collapse
    15 -> info
    16 -> loopTool
    17 -> tuneTool
    18 -> lyricsTool
    19 -> metronomeTool
    else -> {
        val mixerIndex = index - BASE_SEMANTICS_TARGET_COUNT
        val lane = mixerIndex / MIXER_SEMANTICS_TARGETS_PER_LANE
        when (mixerIndex % MIXER_SEMANTICS_TARGETS_PER_LANE) {
            0 -> mixerLaneAt(lane).volume
            1 -> mixerLaneAt(lane).mute
            else -> mixerLaneAt(lane).solo
        }
    }
}

internal const val BASE_SEMANTICS_TARGET_COUNT = 20
private const val MIXER_SEMANTICS_TARGETS_PER_LANE = 3
internal const val PLAYER_SURFACE_SEMANTICS_GEOMETRY_RETRY_DELAY_MS = 50L
private const val PLAYER_SURFACE_SEMANTICS_FAST_GEOMETRY_RETRIES = 4
