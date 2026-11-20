package app.stemwise.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import app.stemwise.analysis.WaveformWindow
import app.stemwise.data.SongTrimRange
import app.stemwise.model.Song
import app.stemwise.model.asClock
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackControlLease
import app.stemwise.playback.SongTrimPreviewLease
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.player.nativeplayer.*
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** A draft uses the complete source timeline; saving never rewrites the source or starts a loop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongTrimSheet(
    song: Song,
    sourceDurationMs: Long,
    snapshot: FullPlayerSnapshot,
    audioPlayback: AudioPlayback,
    controlLease: PlaybackControlLease?,
    sourcePeaks: List<Float>,
    readFineWindow: suspend (Long) -> WaveformWindow?,
    onDismiss: () -> Unit
) {
    val generation = remember(song.id, song.sourceHash) { snapshot.owner.generation }
    val currentGeneration by rememberUpdatedState(snapshot.owner.generation)
    val duration = sourceDurationMs.coerceAtLeast(1L)
    val currentLease by rememberUpdatedState(controlLease)
    var previewLease by remember(generation) { mutableStateOf<SongTrimPreviewLease?>(null) }
    var draft by remember(generation) { mutableStateOf(SongTrimRange(0L, duration)) }
    var loaded by remember(generation) { mutableStateOf(false) }
    var saving by remember(generation) { mutableStateOf(false) }
    var error by remember(generation) { mutableStateOf<String?>(null) }
    var fineCenter by remember(generation) { mutableStateOf<Long?>(null) }
    var fineWindow by remember(generation) { mutableStateOf<Triple<WaveformWindow, Long, Long>?>(null) }
    val currentRead by rememberUpdatedState(readFineWindow)
    val scope = rememberCoroutineScope()
    DisposableEffect(generation) {
        onDispose { previewLease?.let(audioPlayback::cancelSongTrimPreview) }
    }
    LaunchedEffect(generation) {
        try {
            draft = audioPlayback.savedSongTrim(song) ?: SongTrimRange(0L, duration)
            previewLease = currentLease?.let { audioPlayback.beginSongTrimPreview(song, it) }
            check(previewLease != null) { "This song is still opening. Close and try again." }
            loaded = true
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = failure.message ?: "Could not read the saved trim. Close and try again."
        }
    }
    LaunchedEffect(generation, fineCenter, snapshot.sourceMixer.source.revision, snapshot.sourceMixer.mixer.revision) {
        fineWindow = null
        val center = fineCenter
        if (center != null) try {
            fineWindow = currentRead(center)?.let {
                Triple(it, snapshot.sourceMixer.source.revision, snapshot.sourceMixer.mixer.revision)
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = "Fine waveform could not be read. You can still adjust the trim handles."
        }
    }
    val editable = loaded && !saving && previewLease?.let(audioPlayback::songTrimPreviewIsReady) == true
    val latestEditable by rememberUpdatedState(editable)
    val latestDraft by rememberUpdatedState(draft)
    fun moveBoundary(range: SongTrimRange, boundary: FullPlayerLoopBoundary): Boolean {
        val preview = previewLease ?: return false
        val position = if (boundary == FullPlayerLoopBoundary.START) range.startMs
            else (range.endMs - 1_000L).coerceAtLeast(range.startMs)
        if (!audioPlayback.previewSongTrimBoundary(preview, position)) return false
        draft = range
        return true
    }
    val currentMoveBoundary by rememberUpdatedState(::moveBoundary)
    val editorSink = remember(generation) {
        object : FullPlayerActionSink {
            override fun onOpenPlayer(generation: Long) = false
            override fun onCollapsePlayer(generation: Long) = false
            override fun onShowSongInfo(generation: Long) = false
            override fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float) = false
            override fun onPagerCommit(generation: Long, queueIndex: Int) = false
            override fun onPagerCancel(generation: Long) = Unit
            override fun onNavigate(generation: Long, direction: PlayerSurfaceTransportCommand) = -1
            override fun onArtworkSkip(generation: Long, deltaMs: Long) = false
            override fun onScrubBegin(generation: Long) = false
            override fun onScrubPreview(generation: Long, positionMs: Long) = false
            override fun onScrubCommit(generation: Long, positionMs: Long) = false
            override fun onScrubCancel(generation: Long) = Unit
            override fun onBeginVolume(generation: Long, laneIndex: Int): PlayerSurfaceVolumeGesture? = null
            override fun onPreviewVolumeBatch(generation: Long, changedMask: Int,
                gestures: Array<PlayerSurfaceVolumeGesture?>, volumes: FloatArray) = false
            override fun onPreviewVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float) = false
            override fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float) = false
            override fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture) = Unit
            override fun onSetMuted(generation: Long, laneIndex: Int, muted: Boolean) = false
            override fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean) = false
            override fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand) = false
            override fun onSource(generation: Long, target: PlayerSurfaceSourceMode) = false
            override fun onTool(generation: Long, tool: PlayerSurfaceTool) = false

            override fun onLoopBoundaryBegin(generation: Long, boundary: FullPlayerLoopBoundary, originalStartMs: Long, originalEndMs: Long) =
                generation == currentGeneration && latestEditable

            override fun onLoopBoundaryMove(generation: Long, boundary: FullPlayerLoopBoundary, startMs: Long, endMs: Long,
                transportPositionMs: Long, inputEventAtMonotonicMicros: Long, capturedBMs: Long): Boolean {
                if (generation != currentGeneration || !latestEditable) return false
                return currentMoveBoundary(SongTrimRange(startMs, endMs), boundary)
            }

            override fun onLoopBoundaryEnd(generation: Long, boundary: FullPlayerLoopBoundary, startMs: Long, endMs: Long,
                transportPositionMs: Long, inputEventAtMonotonicMicros: Long): Boolean =
                onLoopBoundaryMove(generation, boundary, startMs, endMs, transportPositionMs, inputEventAtMonotonicMicros, -1L)

            override fun onFineModeChanged(generation: Long, active: Boolean, centerPositionMs: Long) {
                if (generation == currentGeneration) fineCenter = centerPositionMs.takeIf { active }
            }
        }
    }
    val waveform = remember(generation, sourcePeaks) {
        FullPlayerWaveformPacket(nextPlayerSurfaceRevision(), song.mediaRevision, sourcePeaks.toFloatArray(), emptyList(), longArrayOf())
    }
    val timeline = remember(generation, draft, duration, waveform) {
        PlayerSurfaceTimelinePacket(nextPlayerSurfaceRevision(), duration, draft.startMs,
            draft.startMs, draft.endMs, waveform.revision)
    }
    val detail = remember(generation) {
        FullPlayerToolDetailPacket(nextPlayerSurfaceRevision(), PlayerSurfaceTool.NONE,
            snapshot.toolDetail.activity.copy(loopActive = true, loopAvailable = true), FullPlayerDetailState.None)
    }
    val finePacket = remember(generation, fineWindow, snapshot.sourceMixer) {
        fineWindow?.takeIf { it.second == snapshot.sourceMixer.source.revision && it.third == snapshot.sourceMixer.mixer.revision }?.let { bound ->
            val window = bound.first
            FullPlayerFineWindowPacket(nextPlayerSurfaceRevision(), song.mediaRevision,
                snapshot.sourceMixer.source.revision, snapshot.sourceMixer.mixer.revision,
                window.startMs, window.frameDurationMs, window.peaks.toFloatArray())
        }
    }
    val editorSnapshot = remember(snapshot, timeline, waveform, detail, finePacket) {
        snapshot.copy(revision = nextPlayerSurfaceRevision(), timeline = timeline, waveform = waveform,
            fineWindow = finePacket, toolDetail = detail)
    }
    val positionProvider = remember(generation) { PlayerSurfacePositionProvider {
        audioPlayback.state.value.takeIf { it.songId == song.id }
            ?.let { it.trimStartMs + it.positionMs } ?: latestDraft.startMs
    } }
    val density = LocalDensity.current
    val motion = LocalStemwiseMotion.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !saving || it != SheetValue.Hidden })
    val sheetMotion = remember(motion.enabled, density) {
        playerSheetMotionSpecs(motion.enabled, with(density) { 1.dp.toPx() })
    }
    val maximumContentHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() * .5f }
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !saving && fineCenter == null,
        containerColor = SurfaceRaised,
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = .16f)
    ) {
        SideEffect {
            sheetMotion.install(sheetState)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = maximumContentHeight)
            .testTag("song_trim_sheet").verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Trim song", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StemwiseTextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = editable,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary),
                    onClick = { draft = SongTrimRange(0L, duration) }) { Text("Reset") }
            }
            if (loaded) {
                val textClearanceDp = (density.fontScale - 1f).coerceAtLeast(0f) * 24f
                var size by remember { mutableStateOf(IntSize.Zero) }
                AndroidView(
                    factory = { PlayerSurfaceView(it, seekerOnly = true) },
                    update = { surface ->
                        val d = density.density
                        surface.updateRouteBaseMetrics(1f, 0f, 0f, 0f, 0f, 0f, true, 0f,
                            density.fontScale, (26f + textClearanceDp) * d, 48f * d, 0f, 0f)
                        surface.bind(editorSnapshot, editorSink, positionProvider, size.width, size.height, d,
                            PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                        surface.setControlBounds(24f * d, 0f, (size.width - 24f * d).coerceAtLeast(25f * d), size.height.toFloat())
                    },
                    modifier = Modifier.fillMaxWidth().height((128f + textClearanceDp).dp).onSizeChanged { size = it }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TrimBoundaryValue("A", draft.startMs, 0L, draft.endMs - 1L, editable, Modifier.weight(1f)) {
                        moveBoundary(draft.copy(startMs = it), FullPlayerLoopBoundary.START)
                    }
                    TrimBoundaryValue("B", draft.endMs, draft.startMs + 1L, duration, editable, Modifier.weight(1f)) {
                        moveBoundary(draft.copy(endMs = it), FullPlayerLoopBoundary.END)
                    }
                }
            } else if (error == null) Text("Loading trim…", color = TextSecondary)
            error?.let { Text(it, color = Coral, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StemwiseButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                    border = BorderStroke(1.dp, Outline), elevation = null,
                    enabled = !saving, onClick = onDismiss) { Text("Cancel") }
                StemwiseButton(modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                    border = BorderStroke(1.dp, Outline), elevation = null,
                    enabled = editable, onClick = {
                    saving = true
                    error = null
                    val range = draft.takeUnless { it.startMs == 0L && it.endMs == duration }
                    scope.launch {
                        val saveLease = previewLease?.let(audioPlayback::songTrimPreviewControlLease)
                        audioPlayback.setSongTrim(song, range, saveLease)
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Could not save trim. Try again." }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            }
        }
    }
}

@Composable
private fun TrimBoundaryValue(label: String, value: Long, minimum: Long, maximum: Long, enabled: Boolean,
    modifier: Modifier, onChange: (Long) -> Unit) {
    Text("$label  ${value.asClock(true)}", fontSize = 15.sp, fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center, modifier = modifier.heightIn(min = 48.dp)
        .padding(vertical = 12.dp)
        .semantics {
            contentDescription = "Trim point $label"
            stateDescription = spokenDuration(value)
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), minimum.toFloat()..maximum.toFloat())
            if (!enabled) disabled()
            setProgress { proposed ->
                if (!enabled || !proposed.isFinite()) false else {
                    onChange(proposed.toLong().coerceIn(minimum, maximum)); true
                }
            }
            customActions = listOf(-1_000L, -10L, 10L, 1_000L).map { delta ->
                CustomAccessibilityAction("${if (delta < 0) "Earlier" else "Later"} ${if (kotlin.math.abs(delta) == 10L) "10 milliseconds" else "1 second"}") {
                    if (!enabled) false else { onChange((value + delta).coerceIn(minimum, maximum)); true }
                }
            }
        }
        .onPreviewKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { onChange((value - 10L).coerceIn(minimum, maximum)); true }
                Key.DirectionRight -> { onChange((value + 10L).coerceIn(minimum, maximum)); true }
                else -> false
            }
        }.focusable(enabled))
}
