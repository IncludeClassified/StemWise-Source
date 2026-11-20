package app.stemwise.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.ui.portraitPlayerControlStackHeightPx
import app.stemwise.ui.portraitPlayerControlTopDp
import app.stemwise.ui.portraitPlayerLayout
import app.stemwise.ui.portraitPlayerToolMotionFrame
import app.stemwise.ui.player.nativeplayer.*
import app.stemwise.ui.player.surface.*

/** Development-only data enters the production native snapshot boundary; all actions stay local. */
@Composable
internal fun UiStressPlayerSurface(mode: UiStressDataMode) {
    if (mode == UiStressDataMode.EMPTY) {
        Text("No selected song. The native player requires a nonempty queue.")
        return
    }
    val context = LocalContext.current
    val catalog = remember(mode, context) { stressPlayerCatalog(mode, uiStressArtworkSources(context)) }
    val queue = remember(catalog) { FullPlayerQueuePacket(nextPlayerSurfaceRevision(), catalog.map { it.queueKey }.toLongArray()) }
    var index by remember(mode) { mutableIntStateOf(0) }
    var source by remember(mode) { mutableStateOf(PlayerSurfaceSourceMode.ORIGINAL) }
    var tool by remember(mode) { mutableStateOf(PlayerSurfaceTool.NONE) }
    var position by remember(mode) { mutableLongStateOf(0L) }
    var loop by remember(mode) { mutableStateOf<Pair<Long, Long>?>(null) }
    var shuffle by remember(mode) { mutableStateOf(false) }
    var repeat by remember(mode) { mutableStateOf(PlayerSurfaceRepeatMode.OFF) }
    val worst = mode == UiStressDataMode.WORST || mode == UiStressDataMode.LARGE
    val duration = when {
        mode == UiStressDataMode.ONE -> 0L
        worst -> 12L * 60L * 60L * 1_000L
        else -> 238_000L
    }
    val targetIds = remember(worst) {
        listOf("vocals", "drums", "bass", "guitar", "piano", "synth", "violin", "saxophone")
            .take(if (worst) StemSourceCatalogue.MAX_TARGETS else 5)
            .also { check(StemSourceCatalogue.selectionIssue(it) == null) }
    }
    val roles = remember(targetIds) {
        targetIds.map { FullPlayerStemRole.valueOf(it.uppercase(java.util.Locale.ROOT).replace('-', '_')) } +
            FullPlayerStemRole.REMAINING
    }
    var volumes by remember(mode) { mutableStateOf(List(roles.size) { if (worst && it == 0) 0f else 1f }) }
    var flags by remember(mode) { mutableStateOf(List(roles.size) { 0 }) }
    val generation = remember(mode, index) { nextPlayerSurfaceGeneration() }
    val currentGeneration by rememberUpdatedState(generation)
    val song = catalog[index]
    val laneCount = if (source == PlayerSurfaceSourceMode.STEMS) roles.size else 0
    val owner = PlayerSurfaceOwnerKey(generation, song.songId, song.mediaRevision,
        "stress-original-${song.mediaRevision}", "stress-stems-${song.mediaRevision}")
    val waveform = remember(song.mediaRevision, mode) {
        FullPlayerWaveformPacket(nextPlayerSurfaceRevision(), song.mediaRevision,
            if (duration == 0L) floatArrayOf() else FloatArray(128) { .08f + (it % 13) * .07f },
            if (duration == 0L) emptyList() else listOf(
                FullPlayerSection(0L, duration / 2L, if (worst) "المقدمة — Khúc dạo đầu 🎻" else "Intro"),
                FullPlayerSection(duration / 2L, duration, if (worst) "Finale — live orchestral reprise" else "Outro")))
    }
    val revision = nextPlayerSurfaceRevision()
    val detail = when (tool) {
        PlayerSurfaceTool.NONE -> FullPlayerDetailState.None
        PlayerSurfaceTool.LOOP -> FullPlayerDetailState.Loop(duration, loop?.first, loop?.second)
        PlayerSurfaceTool.TUNE -> FullPlayerDetailState.Tune(if (worst) "C♯ minor" else "D major", 1f, 0)
        PlayerSurfaceTool.LYRICS -> FullPlayerDetailState.Lyrics(song.mediaRevision, 0L, 0)
        PlayerSurfaceTool.METRONOME -> FullPlayerDetailState.Metronome(false, 120f, 1, 4, 4, 1f, false)
    }
    val snapshot = FullPlayerSnapshot(
        revision, owner, catalog = catalog, queue = queue,
        selection = FullPlayerSelectionPacket(revision, index),
        timeline = PlayerSurfaceTimelinePacket(revision, duration, position.coerceIn(0L, duration),
            loop?.first ?: -1L, loop?.second ?: -1L, waveform.revision),
        waveform = waveform, fineWindow = null,
        sourceMixer = FullPlayerSourceMixerPacket(revision,
            PlayerSurfaceSourcePacket(revision, source, source, PlayerSurfaceSourcePhase.SETTLED, true),
            PlayerSurfaceMixerPacket(revision, 0L, IntArray(laneCount) { it + 1 },
                FloatArray(laneCount) { volumes[it] }, IntArray(laneCount) { flags[it] }),
            roles.take(laneCount).mapIndexed { lane, role ->
                val label = if (role == FullPlayerStemRole.REMAINING) "Remaining"
                    else checkNotNull(StemSourceCatalogue.source(targetIds[lane])).label
                FullPlayerMixerLaneVisual(lane + 1, role, label, 0xFFB3A0FF.toInt())
            }),
        transport = PlayerSurfaceTransportPacket(revision, false, repeat, shuffle, index > 0, index < catalog.lastIndex),
        toolDetail = FullPlayerToolDetailPacket(revision, tool,
            FullPlayerToolActivity(loop != null, false, false, false, false, loopAvailable = duration > 0L), detail),
        status = FullPlayerStatusPacket(revision)
    ).also { check(FullPlayerSnapshotValidator.validate(it) == null) { "Invalid UI stress player snapshot" } }
    val sink = object : FullPlayerActionSink {
        fun owns(token: Long) = token == currentGeneration
        fun select(token: Long, target: Int): Boolean {
            if (!owns(token) || target !in catalog.indices) return false
            index = target; position = 0L; loop = null
            return true
        }
        override fun onOpenPlayer(generation: Long) = false
        override fun onCollapsePlayer(generation: Long) = false
        override fun onShowSongInfo(generation: Long) = false
        override fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float) =
            owns(generation) && queueIndex in catalog.indices
        override fun onPagerCommit(generation: Long, queueIndex: Int) = select(generation, queueIndex)
        override fun onPagerCancel(generation: Long) = Unit
        override fun onNavigate(generation: Long, direction: PlayerSurfaceTransportCommand): Int {
            val target = index + if (direction == PlayerSurfaceTransportCommand.PREVIOUS) -1 else 1
            return if (select(generation, target)) target else -1
        }
        override fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean {
            if (!owns(generation) || duration == 0L) return false
            position = (position + deltaMs).coerceIn(0L, duration); return true
        }
        override fun onScrubBegin(generation: Long) = owns(generation) && duration > 0L
        override fun onScrubPreview(generation: Long, positionMs: Long) = owns(generation) && positionMs in 0L..duration
        override fun onScrubCommit(generation: Long, positionMs: Long): Boolean {
            if (!onScrubPreview(generation, positionMs)) return false
            position = positionMs; return true
        }
        override fun onScrubCancel(generation: Long) = Unit
        override fun onLoopBoundaryBegin(generation: Long, boundary: FullPlayerLoopBoundary,
            originalStartMs: Long, originalEndMs: Long) = owns(generation) && duration > 0L
        override fun onLoopBoundaryMove(generation: Long, boundary: FullPlayerLoopBoundary, startMs: Long,
            endMs: Long, transportPositionMs: Long, inputEventAtMonotonicMicros: Long, capturedBMs: Long): Boolean {
            if (!owns(generation) || startMs < 0L || endMs <= startMs || endMs > duration) return false
            loop = startMs to endMs; return true
        }
        override fun onLoopBoundaryEnd(generation: Long, boundary: FullPlayerLoopBoundary, startMs: Long,
            endMs: Long, transportPositionMs: Long, inputEventAtMonotonicMicros: Long) =
            onLoopBoundaryMove(generation, boundary, startMs, endMs, transportPositionMs, inputEventAtMonotonicMicros, -1L)
        override fun onFineModeChanged(generation: Long, active: Boolean, centerPositionMs: Long) = Unit
        override fun onBeginVolume(generation: Long, laneIndex: Int) =
            if (owns(generation) && laneIndex in roles.indices) PlayerSurfaceVolumeGesture(generation, laneIndex) else null
        override fun onPreviewVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float) =
            owns(gesture.generation) && gesture.laneIndex in roles.indices && volume.isFinite() && volume in 0f..1f
        override fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
            if (!onPreviewVolume(gesture, volume)) return false
            volumes = volumes.mapIndexed { lane, old -> if (lane == gesture.laneIndex) volume else old }; return true
        }
        override fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture) = Unit
        override fun onSetMuted(generation: Long, laneIndex: Int, muted: Boolean) =
            updateFlag(generation, laneIndex, PlayerSurfaceMixerPacket.FLAG_MUTED, muted)
        override fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean) =
            updateFlag(generation, laneIndex, PlayerSurfaceMixerPacket.FLAG_SOLO, solo)
        fun updateFlag(token: Long, lane: Int, flag: Int, enabled: Boolean): Boolean {
            if (!owns(token) || lane !in roles.indices) return false
            flags = flags.mapIndexed { index, old -> if (index != lane) old else if (enabled) old or flag else old and flag.inv() }
            return true
        }
        override fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean {
            if (!owns(generation)) return false
            when (command) {
                PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE -> shuffle = !shuffle
                PlayerSurfaceTransportCommand.REPEAT_CYCLE -> repeat = when (repeat) {
                    PlayerSurfaceRepeatMode.OFF -> PlayerSurfaceRepeatMode.ALL
                    PlayerSurfaceRepeatMode.ALL -> PlayerSurfaceRepeatMode.ONE
                    PlayerSurfaceRepeatMode.ONE -> PlayerSurfaceRepeatMode.OFF
                }
                else -> return false
            }
            return true
        }
        override fun onSource(generation: Long, target: PlayerSurfaceSourceMode): Boolean {
            if (!owns(generation)) return false
            source = target; return true
        }
        override fun onTool(generation: Long, target: PlayerSurfaceTool): Boolean {
            if (!owns(generation) || target == PlayerSurfaceTool.LYRICS ||
                target == PlayerSurfaceTool.METRONOME || target == PlayerSurfaceTool.LOOP && duration == 0L) return false
            tool = if (tool == target) PlayerSurfaceTool.NONE else target
            if (tool == PlayerSurfaceTool.LOOP && loop == null) loop = duration / 4L to duration / 2L
            return true
        }
    }
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl)
        PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT else PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { source = PlayerSurfaceSourceMode.ORIGINAL }) { Text("Original") }
            TextButton(onClick = { source = PlayerSurfaceSourceMode.STEMS }) { Text("Stems (${roles.size})") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val width = with(density) { maxWidth.roundToPx() }
            val height = with(density) { maxHeight.roundToPx() }
            if (width > 0 && height > 0) AndroidView(
                factory = { PlayerSurfaceView(it) },
                update = { surface ->
                    val d = density.density
                    val stems = source == PlayerSurfaceSourceMode.STEMS
                    val clearance = if (loop != null) 1f else 0f
                    val frame = portraitPlayerToolMotionFrame(maxWidth.value, maxHeight.value, d,
                        detailHeightDp = 0f, mixerProgress = if (stems) 1f else 0f,
                        loopClearanceProgress = clearance, fontScale = density.fontScale, mixerLaneCount = laneCount)
                    val route = portraitPlayerLayout(maxWidth.value, maxHeight.value, PlaybackSourceMode.ORIGINAL,
                        panelExpanded = false, fontScale = density.fontScale, mixerLaneCount = laneCount)
                    surface.updateRouteBaseMetrics(1f, if (stems) 0f else 1f,
                        route.headerHeightDp * d, route.headerGapDp * d, route.identityHeightDp * d,
                        route.identityGapDp * d, route.compactChrome, route.listSpacingDp * d, density.fontScale,
                        route.seekerClockTopClearanceDp * d, route.seekerTrackHeightDp * d,
                        route.transportHeightDp * d, route.transportPlaySizeDp * d)
                    when (val adoption = surface.bind(snapshot, sink, PlayerSurfacePositionProvider { position }, width, height, d, direction)) {
                        FullPlayerAdoptionResult.APPLIED_INITIAL, FullPlayerAdoptionResult.APPLIED_NEW_OWNER,
                        FullPlayerAdoptionResult.APPLIED_UPDATE, FullPlayerAdoptionResult.UNCHANGED -> Unit
                        else -> error("UI stress snapshot was rejected: $adoption")
                    }
                    val layout = frame.layout
                    val top = portraitPlayerControlTopDp(layout, stems) * d
                    surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(generation, revision, tool, 0,
                        expandedArtworkSizePx = layout.artworkSizeDp * d,
                        mixerCardHeightPx = layout.mixerCardHeightDp * d,
                        mixerSpacingPx = layout.mixerSpacingDp * d,
                        listSpacingPx = layout.listSpacingDp * d,
                        seekerTrackHeightPx = layout.seekerTrackHeightDp * d,
                        transportHeightPx = layout.transportHeightDp * d,
                        controlBounds = PlayerSurfaceBounds(24f * d, top, width - 24f * d,
                            top + portraitPlayerControlStackHeightPx(frame, d, stems, clearance, laneCount)),
                        detailViewportHeightPx = 0f))
                    surface.updateAmbientMotionPolicy(false)
                },
                modifier = Modifier.fillMaxSize().testTag("ui-stress-native-player")
            )
        }
    }
}

private fun stressPlayerCatalog(mode: UiStressDataMode, artwork: List<String>): FullPlayerCatalogPacket {
    val titles = if (mode == UiStressDataMode.DEMO) listOf("Northern Lights", "Slow Morning", "Home") else listOf(
        "نور الهدى عبد الرحمن — تسجيل أوركسترالي حي في القاهرة / Đặng Thị Ngọc Hân 🎻 (Deluxe Anniversary Edition)",
        "PianoConcertoNo3InDMinorOp30LiveAtTheInternationalFestival2025Remastered.flac",
        "J", "👩🏽‍💻 Đặng Thị Ngọc Hân — Khúc nhạc chiều", "<b>Live & Unplugged</b>")
    val count = when (mode) {
        UiStressDataMode.LARGE -> 1_000
        UiStressDataMode.ONE -> 1
        else -> titles.size
    }
    return FullPlayerCatalogPacket(nextPlayerSurfaceRevision(), List(count) { index ->
        FullPlayerCatalogSong(index + 1L, "ui-stress-player-${mode.name}-$index", "ui-stress-media-${mode.name}-$index",
            if (mode == UiStressDataMode.ONE) "J" else titles[index % titles.size],
            if (mode == UiStressDataMode.ONE || index % 3 == 2) "" else if (mode == UiStressDataMode.DEMO) "StemWise Demo"
            else "الأوركسترا السيمفونية — Aleksandra Wiśniewska-Kowalczyk & Đặng Thị Ngọc Hân",
            when (mode) {
                UiStressDataMode.DEMO -> "file:///android_asset/demo_library/DEMOUI0000${index % 3 + 1}/cover.png"
                UiStressDataMode.ONE -> null
                else -> when (index % 5) {
                    0, 1, 2 -> artwork[index % 5]
                    3 -> "file:///android_asset/ui_stress_missing_cover.png"
                    else -> null
                }
            },
            0xFFA78BFA.toInt(), isFlac = index % 2 == 0)
    })
}
