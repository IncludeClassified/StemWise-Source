package app.stemwise.ui


import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.model.Song
import app.stemwise.model.isFlac
import app.stemwise.model.Stem
import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.SongSection
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.SourceSwitchState
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
internal fun PortraitNativePlayerSurface(
    orderedSongs: List<Song>,
    activeSong: Song,
    semanticSongIdState: State<String>,
    owner: SongPresentationOwner,
    generation: Long,
    audioPlayback: AudioPlayback,
    transportAudioState: Flow<PlayerTransportUiState>,
    sourceMode: PlaybackSourceMode,
    settledSourceMode: PlaybackSourceMode,
    presentedSourceMode: PlaybackSourceMode,
    sourceSwitchState: SourceSwitchState,
    hasCompleteStemSet: Boolean,
    loopAvailable: Boolean,
    loopPreparation: app.stemwise.playback.LoopPreparationState,
    stemsAvailabilitySettled: Boolean,
    stemsDownloading: Boolean,
    stemsPreparationFailed: Boolean = false,
    stems: List<StemUiSlot>,
    displayedStemSetId: String?,


    durationMs: Long,
    loopA: Long?,
    loopB: Long?,
    waveformPeaks: List<Float>,
    beatEvents: List<BeatEvent>,
    fineWindowState: State<PlayerFineWaveformWindow?>,
    fineWaveformBinding: PlayerWaveformBinding,
    sections: List<SongSection>,
    selectedTool: String?,
    tempo: Float,
    pitch: Int,
    metronomeEnabled: Boolean,
    metronomeAvailable: Boolean,
    metronomeBpm: Float,
    metronomeSubdivision: Int,
    metronomeNumerator: Int,
    metronomeDenominator: Int,
    metronomeVolume: Float,
    statusPacket: FullPlayerStatusPacket,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    layoutDirection: LayoutDirection,
    routeExpansionState: State<Float>,
    mixerWorkspaceProgressState: State<Float>,
    originalPortraitLayout: PortraitPlayerLayout,
    stemsPortraitLayout: PortraitPlayerLayout,
    bridge: PlayerSurfaceHostBridge,
    semanticsGeometryState: State<PlayerSurfaceSemanticsGeometry?>,
    lowerControlsInteractive: Boolean,
    externalTransport: Boolean = false,
    actionSink: FullPlayerActionSink,
    semanticsSink: PlayerSurfaceSemanticsActionSink,
    positionProvider: PlayerSurfacePositionProvider,
    positionState: State<Long>,

    artworkPaletteListener: PlayerSurfaceArtworkPaletteListener,
    ambientMotionAllowed: Boolean,
    trimEditor: (@Composable (FullPlayerSnapshot) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (widthPx <= 0 || heightPx <= 0 || orderedSongs.isEmpty()) return
    val mixerWorkspaceProgress = mixerWorkspaceProgressState.value
    val portraitLayout = interpolatePortraitPlayerLayout(
        originalPortraitLayout, stemsPortraitLayout,
        PlayerMotionPolicy.sourceLayoutProgress(mixerWorkspaceProgress)
    )
    val transport by transportAudioState.collectAsStateWithLifecycle(
        initialValue = playerTransportUiState(
            audioPlayback.currentStateSnapshot(),
            audioPlayback.currentQueueSongId()
        )
    )
    val indexBySongId = remember(orderedSongs) {
        orderedSongs.withIndex().associate { it.value.id to it.index }
    }
    val currentIndex = indexBySongId[activeSong.id] ?: 0
    val queueSelectionSongId = transport.currentQueueSongId?.takeIf(indexBySongId::containsKey)
    // The cache-gated visual target can lead the queue while source verification is pending.
    val visualSelectionSongId = semanticSongIdState.value.takeIf(indexBySongId::containsKey)
        ?: queueSelectionSongId ?: activeSong.id
    val ownerPacket = remember(generation, owner) {
        PlayerSurfaceOwnerKey(
            generation = generation,
            songId = owner.songId,
            mediaRevision = owner.mediaRevision,
            audioRevision = owner.audioRevision,
            stemRevision = owner.stemRevision
        )
    }
    val catalogPacket = remember(orderedSongs) {
        FullPlayerCatalogPacket(
            revision = nextPlayerSurfaceRevision(),
            songs = orderedSongs.map { song ->
                FullPlayerCatalogSong(
                    queueKey = playerSurfaceQueueKey(song.id),
                    songId = song.id,
                    mediaRevision = song.mediaRevision,
                    title = song.title.ifBlank { "Untitled" },
                    artist = song.artist,
                    artworkSource = song.artworkSource,
                    accentArgb = song.accent.toArgb(),
                    isFlac = song.isFlac
                )
            }
        )
    }
    val queuePacket = remember(orderedSongs) {
        val itemKeys = LongArray(orderedSongs.size) { index ->
            playerSurfaceQueueKey(orderedSongs[index].id)
        }
        check(itemKeys.toSet().size == itemKeys.size) {
            "Player surface queue keys collided"
        }
        FullPlayerQueuePacket(
            revision = nextPlayerSurfaceRevision(),
            itemKeys = itemKeys
        )
    }
    val selectionPacket = remember(generation, currentIndex) {
        FullPlayerSelectionPacket(
            revision = nextPlayerSurfaceRevision(),
            currentIndex = currentIndex
        )
    }
    val waveformRevision = remember(generation, owner.mediaRevision, waveformPeaks, sections, beatEvents) {
        nextPlayerSurfaceRevision()
    }
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val timelinePacket = remember(generation, safeDurationMs, loopA, loopB, waveformRevision) {
        PlayerSurfaceTimelinePacket(
            revision = nextPlayerSurfaceRevision(),
            durationMs = safeDurationMs,
            positionMs = positionProvider.currentPositionMs().coerceIn(0L, safeDurationMs),
            loopStartMs = loopA ?: -1L,
            loopEndMs = loopB ?: -1L,
            waveformRevision = waveformRevision
        )
    }
    val surfaceSourceMode = if (sourceMode == PlaybackSourceMode.STEMS) {
        PlayerSurfaceSourceMode.STEMS
    } else {
        PlayerSurfaceSourceMode.ORIGINAL
    }
    val audibleSourceMode = if (settledSourceMode == PlaybackSourceMode.STEMS) {
        PlayerSurfaceSourceMode.STEMS
    } else {
        PlayerSurfaceSourceMode.ORIGINAL
    }
    val sourcePhase = when (sourceSwitchState) {
        SourceSwitchState.Original, is SourceSwitchState.Stems ->
            PlayerSurfaceSourcePhase.SETTLED
        is SourceSwitchState.PreparingStems -> PlayerSurfaceSourcePhase.PREPARING
        SourceSwitchState.ReturningOriginal -> PlayerSurfaceSourcePhase.RETURNING
        is SourceSwitchState.Failed -> PlayerSurfaceSourcePhase.FAILED
    }
    val sourcePacket = remember(
        generation,
        surfaceSourceMode,
        audibleSourceMode,
        sourcePhase,
        hasCompleteStemSet,
        stemsAvailabilitySettled,
        stemsDownloading,
        stemsPreparationFailed
    ) {
        PlayerSurfaceSourcePacket(
            revision = nextPlayerSurfaceRevision(),
            presentedMode = surfaceSourceMode,
            audibleMode = audibleSourceMode,
            phase = sourcePhase,
            stemsAvailable = hasCompleteStemSet,
            stemsAvailabilitySettled = stemsAvailabilitySettled,
            stemsDownloading = stemsDownloading,
            stemsPreparationFailed = stemsPreparationFailed
        )
    }
    val mixerVisible = presentedSourceMode == PlaybackSourceMode.STEMS
    val mixerSnapshots = if (mixerVisible) {
        stems.map { slot ->
            // These are terminal observable values. Native MOVE previews never enter this read.
            Stem(
                id = slot.id,
                label = slot.label,
                color = slot.color,
                volume = slot.committedVolume.floatValue,
                muted = slot.muted.value,
                solo = slot.solo.value
            )
        }
    } else {
        emptyList()
    }
    val mixerPackageRevision = remember(
        generation,
        displayedStemSetId,
        mixerSnapshots.map { it.id }
    ) { nextPlayerSurfaceRevision() }
    val mixerPacket = remember(generation, mixerPackageRevision, mixerSnapshots) {
        PlayerSurfaceMixerPacket(
            revision = nextPlayerSurfaceRevision(),
            packageRevision = mixerPackageRevision,
            laneKeys = IntArray(mixerSnapshots.size) { it + 1 },
            volumes = FloatArray(mixerSnapshots.size) { mixerSnapshots[it].volume },
            laneFlags = IntArray(mixerSnapshots.size) { index ->
                (if (mixerSnapshots[index].muted || mixerSnapshots[index].volume <= 0f) PlayerSurfaceMixerPacket.FLAG_MUTED else 0) or
                    (if (mixerSnapshots[index].solo) PlayerSurfaceMixerPacket.FLAG_SOLO else 0)
            }
        )
    }
    val sourceMixerPacket = remember(
        generation,
        sourcePacket,
        mixerPacket,
        mixerSnapshots,
    ) {
        FullPlayerSourceMixerPacket(
            revision = nextPlayerSurfaceRevision(),
            source = sourcePacket,
            mixer = mixerPacket,
            lanes = mixerSnapshots.mapIndexed { index, stem ->
                FullPlayerMixerLaneVisual(
                    laneKey = mixerPacket.laneKeyAt(index),
                    role = fullPlayerStemRole(stem.id),
                    label = stem.label.ifBlank { "Stem ${index + 1}" },
                    colorArgb = stem.color.toArgb()
                )
            })
    }
    val repeatMode = playerSurfaceRepeatMode(transport.repeatMode)
    val transportPacket = remember(
        generation,
        transport.playWhenReady,
        transport.shuffleEnabled,
        repeatMode,
        transport.previousEnabled,
        transport.canGoNext
    ) {
        PlayerSurfaceTransportPacket(
            revision = nextPlayerSurfaceRevision(),
            playWhenReady = transport.playWhenReady,
            repeatMode = repeatMode,
            shuffleEnabled = transport.shuffleEnabled,
            canGoPrevious = transport.previousEnabled,
            canGoNext = transport.canGoNext
        )
    }
    val surfaceTool = playerSurfaceToolForDetail(selectedTool)
        .takeUnless { it == PlayerSurfaceTool.LOOP && !loopAvailable }
        ?: PlayerSurfaceTool.NONE
    val fineWindow = fineWindowState.value?.takeIf { it.binding == fineWaveformBinding }?.window
    val waveformPacket = remember(
        waveformRevision,
        owner.mediaRevision,
        waveformPeaks,
        sections,
        beatEvents
    ) {
        FullPlayerWaveformPacket(
            revision = waveformRevision,
            mediaRevision = owner.mediaRevision,
            peaks = waveformPeaks.toFloatArray(),
            sections = sections.map { section ->
                FullPlayerSection(section.startMs, section.endMs, section.label)
            },
            beatTimesMs = beatEvents.map { it.timeMs }.toLongArray()
        )
    }
    val fineWindowPacket = remember(generation, owner.mediaRevision, sourcePacket.revision, mixerPacket.revision, fineWindow) {
        fineWindow?.takeIf { it.frameDurationMs > 0L }?.let { window ->
            FullPlayerFineWindowPacket(
                revision = nextPlayerSurfaceRevision(),
                mediaRevision = owner.mediaRevision,
                sourceRevision = sourcePacket.revision,
                mixerRevision = mixerPacket.revision,
                startMs = window.startMs.coerceAtLeast(0L),
                frameDurationMs = window.frameDurationMs,
                peaks = window.peaks.toFloatArray()
            )
        }
    }
    val lyricTrackCount = activeSong.lyricTracks.size
    val lyricContentRevision = remember(owner.mediaRevision, activeSong.lyricTracks) {
        nextPlayerSurfaceRevision()
    }
    val toolDetailPacket = remember(
        generation,
        surfaceTool,
        loopAvailable,
        loopPreparation,
        safeDurationMs,
        loopA,
        loopB,
        activeSong.key,
        tempo,
        pitch,
        owner.mediaRevision,
        lyricContentRevision,
        lyricTrackCount,
        metronomeEnabled,
        metronomeAvailable,
        metronomeBpm,
        metronomeSubdivision,
        metronomeNumerator,
        metronomeDenominator,
        metronomeVolume,
    ) {
        val detail = when (surfaceTool) {
            PlayerSurfaceTool.LOOP -> FullPlayerDetailState.Loop(
                durationMs = safeDurationMs,
                loopStartMs = loopA,
                loopEndMs = loopB
            )
            PlayerSurfaceTool.TUNE -> FullPlayerDetailState.Tune(
                originalKey = activeSong.key,
                tempo = tempo,
                semitoneShift = pitch
            )
            PlayerSurfaceTool.LYRICS -> FullPlayerDetailState.Lyrics(
                mediaRevision = owner.mediaRevision,
                contentRevision = lyricContentRevision,
                trackCount = lyricTrackCount
            )
            PlayerSurfaceTool.METRONOME -> FullPlayerDetailState.Metronome(
                enabled = metronomeEnabled,
                bpm = metronomeBpm,
                subdivision = metronomeSubdivision,
                numerator = metronomeNumerator,
                denominator = metronomeDenominator,
                volume = metronomeVolume,
                timingAvailable = metronomeAvailable,
                tempo = tempo
            )
            PlayerSurfaceTool.NONE -> FullPlayerDetailState.None
        }
        FullPlayerToolDetailPacket(
            revision = nextPlayerSurfaceRevision(),
            selectedTool = surfaceTool,
            activity = FullPlayerToolActivity(
                loopActive = loopA != null && loopB != null,
                tuneActive = pitch != 0,
                speedActive = tuneTempoActive(tempo),
                lyricsAvailable = lyricTrackCount > 0,
                metronomeActive = metronomeEnabled && metronomeAvailable,
                metronomeAvailable = metronomeAvailable,
                loopAvailable = loopAvailable,
                loopPreparation = loopPreparation
            ),
            detail = detail
        )
    }
    val snapshot = remember(
        ownerPacket,
        visualSelectionSongId,
        catalogPacket,
        queuePacket,
        selectionPacket,
        timelinePacket,
        waveformPacket,
        fineWindowPacket,
        sourceMixerPacket,
        transportPacket,
        toolDetailPacket,
        statusPacket
    ) {
        FullPlayerSnapshot(
            revision = nextPlayerSurfaceRevision(),
            owner = ownerPacket,
            visualSelectionSongId = visualSelectionSongId,
            catalog = catalogPacket,
            queue = queuePacket,
            selection = selectionPacket,
            timeline = timelinePacket,
            waveform = waveformPacket,
            fineWindow = fineWindowPacket,
            sourceMixer = sourceMixerPacket,
            transport = transportPacket,
            toolDetail = toolDetailPacket,
            status = statusPacket
        )
    }

    PlayerSurfaceNativeLayer(
        snapshot = snapshot,
        actionSink = actionSink,
        positionProvider = positionProvider,
        bridge = bridge,
        widthPx = widthPx,
        heightPx = heightPx,
        density = density,
        fontScale = portraitLayout.fontScale,
        direction = nativePlayerSurfaceLayoutDirection(layoutDirection),
        expansionProgress = 1f,
        artworkAlpha = 1f,
        expandedHeaderHeightPx = portraitLayout.headerHeightDp * density,
        expandedHeaderGapPx = portraitLayout.headerGapDp * density,
        expandedIdentityHeightPx = portraitLayout.identityHeightDp * density,
        expandedIdentityGapPx = portraitLayout.identityGapDp * density,
        compactChrome = portraitLayout.compactChrome,
        listSpacingPx = portraitLayout.listSpacingDp * density,
        seekerClockTopClearancePx = portraitLayout.seekerClockTopClearanceDp * density,
        seekerTrackHeightPx = portraitLayout.seekerTrackHeightDp * density,
        transportHeightPx = portraitLayout.transportHeightDp * density,
        transportPlaySizePx = portraitLayout.transportPlaySizeDp * density,
        ambientMotionAllowed = ambientMotionAllowed,
        externalTransport = externalTransport,
        artworkPaletteListener = artworkPaletteListener,
        routeExpansionState = routeExpansionState,
        mixerWorkspaceProgress = mixerWorkspaceProgress,
        modifier = modifier
    )

    val routeSemantics by remember(routeExpansionState) {
        derivedStateOf { playerRouteSemanticsPresentation(routeExpansionState.value) }
    }
    PortraitNativePlayerSemantics(
        generation = generation,
        activeSong = activeSong,
        semanticSongIdState = semanticSongIdState,
        orderedSongs = orderedSongs,
        positionState = positionState,
        durationMs = safeDurationMs,
        loopA = loopA,
        loopB = loopB,
        sections = sections,
        sourcePacket = sourcePacket,
        mixerSnapshots = mixerSnapshots,


        transportPacket = transportPacket,
        toolDetail = toolDetailPacket,
        compact = routeSemantics.compact,
        controlsInteractive = routeSemantics.controlsInteractive,
        bridge = bridge,
        geometryState = semanticsGeometryState,
        lowerControlsInteractive = lowerControlsInteractive,
        externalTransport = externalTransport,
        actionSink = semanticsSink,

        modifier = modifier
    )
    trimEditor?.invoke(snapshot)
}

// Android accessibility consumers need a continuous one-second quiet interval before they can
// query an idle hierarchy. Keep only the PLAYING position projection slightly slower than that
// interval, bounding its lag below 1.25 seconds while native rendering and MediaSession progress
// remain on their existing high-rate clocks. Paused positions stay exact below.
private const val PLAYER_SEMANTICS_POSITION_QUANTUM_MS = 1_250L

internal fun playerSurfaceSemanticPositionMs(
    exactPositionMs: Long,
    durationMs: Long,
    playWhenReady: Boolean
): Long {
    require(durationMs >= 0L)
    val exact = exactPositionMs.coerceIn(0L, durationMs)
    return if (playWhenReady) {
        (exact / PLAYER_SEMANTICS_POSITION_QUANTUM_MS) *
            PLAYER_SEMANTICS_POSITION_QUANTUM_MS
    } else {
        exact
    }
}

internal fun playerSurfaceRequiresExternalTimelineInvalidation(
    playWhenReady: Boolean
): Boolean = !playWhenReady

/** Stable semantic icon role; never infer lane identities from localized display order. */
private fun fullPlayerStemRole(id: String): FullPlayerStemRole {
    return sourcePartIconRole(id)
}

@Composable
internal fun PortraitNativePlayerSemantics(
    generation: Long,
    activeSong: Song,
    semanticSongIdState: State<String>,
    orderedSongs: List<Song>,
    positionState: State<Long>,
    durationMs: Long,
    loopA: Long?,
    loopB: Long?,
    sections: List<SongSection>,
    sourcePacket: PlayerSurfaceSourcePacket,
    mixerSnapshots: List<Stem>,


    transportPacket: PlayerSurfaceTransportPacket,
    toolDetail: FullPlayerToolDetailPacket,
    compact: Boolean,
    controlsInteractive: Boolean,
    bridge: PlayerSurfaceHostBridge,
    geometryState: State<PlayerSurfaceSemanticsGeometry?>,
    lowerControlsInteractive: Boolean,
    externalTransport: Boolean = false,
    actionSink: PlayerSurfaceSemanticsActionSink,

    modifier: Modifier
) {
    val geometry = geometryState.value ?: return
    val semanticsGeometry = geometry
    val semanticsMixerSnapshots = if (compact) emptyList() else mixerSnapshots
    val latestTimelineInputs = rememberUpdatedState(
        PlayerSemanticsTimelineInputs(
            durationMs = durationMs,
            loopStartMs = loopA,
            loopEndMs = loopB,
            sections = sections,
            playWhenReady = transportPacket.playWhenReady
        )
    )
    // This State is handed intact to the one timeline semantics restart scope. This parent never
    // reads the playback clock, so a position publication cannot recreate the other 20 base nodes
    // or any of the (up to 18) mixer nodes.
    val semanticTimelineState = remember(positionState) {
        derivedStateOf {
            val inputs = latestTimelineInputs.value
            val position = playerSurfaceSemanticPositionMs(
                exactPositionMs = positionState.value,
                durationMs = inputs.durationMs,
                playWhenReady = inputs.playWhenReady
            )
            PlayerSurfaceTimelineSemantics(
                durationMs = inputs.durationMs,
                positionMs = position,
                loopStartMs = inputs.loopStartMs,
                loopEndMs = inputs.loopEndMs,
                activeSectionLabel = inputs.sections.firstOrNull {
                    position >= it.startMs && position < it.endMs
                }?.label
            )
        }
    }
    val semanticSongId = semanticSongIdState.value
    val semanticsQueueIndex = remember(orderedSongs) {
        PlayerSemanticsQueueIndex(orderedSongs)
    }
    val semanticSelection = remember(orderedSongs, semanticSongId, activeSong.id) {
        semanticsQueueIndex.resolve(semanticSongId, activeSong)
    }
    val model = remember(
        generation,
        semanticSelection,
        semanticsQueueIndex.size,
        compact,
        controlsInteractive,
        sourcePacket,
        semanticsMixerSnapshots,
        transportPacket,
        toolDetail
    ) {
        PlayerSurfaceStaticSemanticsModel(
            generation = generation,
            pager = PlayerSurfacePagerSemantics(
                songId = semanticSelection.song.id,
                title = semanticSelection.song.title,
                artist = semanticSelection.song.artist,
                isFlac = semanticSelection.song.isFlac,
                queueIndex = semanticSelection.queueIndex,
                queueSize = semanticsQueueIndex.size,
                compact = compact,
                controlsInteractive = controlsInteractive
            ),
            source = PlayerSurfaceSourceSemantics.from(sourcePacket),
            mixer = PlayerSurfaceMixerSemantics(
                lanes = semanticsMixerSnapshots.map { stem ->
                    PlayerSurfaceMixerLaneSemantics(
                        label = stem.label,
                        volume = stemSliderEffectiveFraction(stem.volume, stem.muted),
                        muted = stem.muted || stem.volume <= 0f,
                        solo = stem.solo
                    )
                }),
            transport = PlayerSurfaceTransportSemantics(
                playWhenReady = transportPacket.playWhenReady,
                shuffleEnabled = transportPacket.shuffleEnabled,
                repeatMode = transportPacket.repeatMode,
                previousEnabled = transportPacket.canGoPrevious,
                nextEnabled = transportPacket.canGoNext
            ),
            tools = PlayerSurfaceToolSemantics.from(toolDetail)
        )
    }
    PausedPlayerSurfaceTimelineInvalidation(
        playWhenReady = transportPacket.playWhenReady,
        timelineState = semanticTimelineState,
        bridge = bridge
    )
    PlayerSurfaceSemanticsOverlay(
        model = model,
        timelineState = semanticTimelineState,
        geometry = semanticsGeometry,
        lowerControlsInteractive = lowerControlsInteractive,
        transportVisible = !externalTransport,
        actionSink = actionSink,

        modifier = modifier.fillMaxSize()
    )
}

/**
 * The native View already polls [PlayerSurfacePositionProvider] every VSYNC while playing. Only a
 * paused/external seek needs an explicit redraw, and its exact position is the effect key so an
 * unrelated semantics recomposition cannot send a duplicate notification.
 */
@Composable
private fun PausedPlayerSurfaceTimelineInvalidation(
    playWhenReady: Boolean,
    timelineState: State<PlayerSurfaceTimelineSemantics>,
    bridge: PlayerSurfaceHostBridge
) {
    if (!playerSurfaceRequiresExternalTimelineInvalidation(playWhenReady)) return
    val exactPausedPosition = timelineState.value.positionMs
    LaunchedEffect(bridge, exactPausedPosition) {
        bridge.notifyTimelinePositionChanged()
    }
}
