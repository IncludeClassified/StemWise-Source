package app.stemwise.ui

import app.stemwise.playback.STANDARD_SEEK_INCREMENT_MS

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitHorizontalDragOrCancellation
import androidx.compose.foundation.gestures.awaitHorizontalPointerSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import android.view.HapticFeedbackConstants
import app.stemwise.ui.design.performStemwiseHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import app.stemwise.model.Song
import app.stemwise.model.isFlac
import app.stemwise.model.Stem
import app.stemwise.model.LyricTrack
import app.stemwise.model.TimedLyricLine
import app.stemwise.model.StemRoleContract
import app.stemwise.model.StemSource
import app.stemwise.model.asClock
import app.stemwise.data.PermanentDeletionOutcome
import app.stemwise.data.SeparationSetEntity
import app.stemwise.analysis.ImportedAnalysisRepository
import app.stemwise.analysis.ImportedAnalysisSummary
import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.WaveformWindow
import app.stemwise.analysis.SongSection
import app.stemwise.analysis.effectiveGridBpm
import app.stemwise.analysis.metronomeAvailableFor
import app.stemwise.integration.SeparationRepository
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.METRONOME_BEAT_UNITS
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.SourceSwitchState
import app.stemwise.playback.normalizedMetronomeBeatUnit
import app.stemwise.playback.normalizedMetronomeSubdivision
import app.stemwise.ui.theme.Amber
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.Ink
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.SurfaceLow
import app.stemwise.ui.theme.SurfaceRaised
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.DragAuraState
import app.stemwise.ui.design.StemwiseButton
import app.stemwise.ui.design.StemwiseAcceptedIconButton
import app.stemwise.ui.design.StemwiseAuraContract
import app.stemwise.ui.design.StemwiseIconButton
import app.stemwise.ui.design.StemwiseSlider
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.stemwiseAuraClickable
import app.stemwise.ui.design.stemwiseAuraAcceptedClickableAction
import app.stemwise.ui.design.stemwiseAuraAcceptedSelectableAction
import app.stemwise.ui.design.stemwiseKeyboardAction
import app.stemwise.ui.design.stemwiseAuraSelectable
import app.stemwise.ui.design.stemwiseAuraSelectableAction
import app.stemwise.ui.design.stemwiseAuraAcceptedToggleableAction
import app.stemwise.ui.design.stemwiseAuraToggleableAction
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.theme.Violet
import app.stemwise.ui.theme.StemwiseSystemFontFamily
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseMotionEasing
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.semanticSpec
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun semanticSelectedProgress(selected: Boolean, label: String): Float {
    val motion = LocalStemwiseMotion.current
    return animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = motion.semanticSpec(StemwiseMotionEvent.TOGGLE_CHANGE),
        visibilityThreshold = .001f,
        label = label
    ).value
}

internal fun Modifier.semanticSelectedSurface(
    progress: Float,
    shape: Shape,
    selectedBrush: Brush,
    baseColor: Color = Surface,
    baseOutline: Color = Outline,
    selectedOutline: Color = PrimaryPurple,
    selectedBorderWidthDp: Float = 1f
): Modifier = this
    .clip(shape)
    .background(baseColor)
    .drawBehind {
        drawRect(selectedBrush, alpha = progress.coerceIn(0f, 1f))
    }
    .border(
        width = (1f + (selectedBorderWidthDp - 1f) * progress.coerceIn(0f, 1f)).dp,
        color = lerp(baseOutline, selectedOutline, progress.coerceIn(0f, 1f)),
        shape = shape
    )

/** Copies exactly one public source identifier; metadata and hashes never hitchhike on this action. */
internal fun copyYouTubeVideoId(context: Context, song: Song) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(
            ClipData.newPlainText(
                "${song.title} YouTube ID",
                song.videoId
            )
        )
}

/** Lets only the newest source-selection request publish UI completion or failure state. */
internal class LatestStemActivationGate {
    private var latestToken = 0L

    fun issue(): Long {
        latestToken += 1L
        return latestToken
    }

    fun invalidate() {
        latestToken += 1L
    }

    fun owns(token: Long): Boolean = token == latestToken
}

internal fun nonDuplicatePlaybackError(
    primary: String?,
    candidate: String?
): String? {
    val error = candidate?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val existing = primary?.trim()?.takeIf(String::isNotEmpty)
    return error.takeUnless { existing != null && it.equals(existing, ignoreCase = true) }
}

/**
 * A local pager request can reach committed native audio before its parent Song state is applied.
 * Loading and that exact prewarmed commit both retain the explicit route; any other settled owner
 * is authoritative and may abandon it.
 */
internal fun explicitTransportStillAwaitingParent(
    desiredSongId: String,
    parentSongId: String,
    audioLoading: Boolean,
    committedAudioSongId: String?
): Boolean = parentSongId != desiredSongId &&
    (audioLoading || committedAudioSongId == desiredSongId)

internal data class PlayerTransportVisualTarget(
    val index: Int,
    val songId: String
)

/**
 * Dispatches through AudioPlayback's queue authority. The UI may animate the returned ID, but it
 * must never calculate or select an adjacent song itself.
 */
internal fun dispatchPlayerTransport(
    direction: TrackChangeDirection,
    nextTrack: () -> String?,
    previousOrRestart: () -> String?
): String? = when (direction) {
    TrackChangeDirection.NEXT -> nextTrack()
    TrackChangeDirection.PREVIOUS -> previousOrRestart()
}

internal fun playerTransportRestartedCurrent(
    direction: TrackChangeDirection,
    songIdBeforeCommand: String?,
    resultingSongId: String
): Boolean = direction == TrackChangeDirection.PREVIOUS &&
    songIdBeforeCommand != null &&
    songIdBeforeCommand == resultingSongId

/** O(1) presentation lookup for the stable ID returned by the authoritative queue command. */
internal fun playerTransportVisualTarget(
    authoritativeSongId: String?,
    pageBySongId: Map<String, Int>
): PlayerTransportVisualTarget? {
    val songId = authoritativeSongId ?: return null
    val page = pageBySongId[songId] ?: return null
    return PlayerTransportVisualTarget(index = page, songId = songId)
}

/** Plain UI-thread cursor; changing it never creates a second Compose animation clock. */
internal class ExplicitTransportCommandCursor {
    var desiredSongId: String? = null
        private set
    private var generation = 0L

    fun issue(songId: String): Long {
        generation += 1L
        desiredSongId = songId
        return generation
    }

    fun owns(token: Long, songId: String): Boolean =
        token == generation && desiredSongId == songId

    fun settle(songId: String): Boolean {
        if (desiredSongId != songId) return false
        desiredSongId = null
        return true
    }

    fun abandon() {
        generation += 1L
        desiredSongId = null
    }
}

private val PORTRAIT_RETAINED_TOOL_ORDER = listOf(
    PLAYER_DETAIL_LOOP,
    PLAYER_DETAIL_TUNE,
    PLAYER_DETAIL_METRONOME
)

private fun portraitRetainedToolBit(tool: String?): Int = when (tool) {
    PLAYER_DETAIL_LOOP -> 1 shl 0
    PLAYER_DETAIL_TUNE -> 1 shl 1
    PLAYER_DETAIL_METRONOME -> 1 shl 2
    else -> 0
}

internal fun playerToolPanelTransition(
    previousTool: String?,
    targetTool: String?
): PlayerSurfacePanelTransition = when {
    previousTool == targetTool -> PlayerSurfacePanelTransition.NONE
    previousTool == null && targetTool != null -> PlayerSurfacePanelTransition.OPEN
    previousTool != null && targetTool == null -> PlayerSurfacePanelTransition.CLOSE
    else -> PlayerSurfacePanelTransition.REFLOW
}

internal fun playerToolPanelContentDurationMs(
    transition: PlayerSurfacePanelTransition,
    motionEnabled: Boolean
): Int = when {
    transition == PlayerSurfacePanelTransition.NONE -> 0
    !motionEnabled -> 120
    transition == PlayerSurfacePanelTransition.CLOSE -> 180
    else -> 240
}

/**
 * Keeps every visited detail composition alive for the current song owner while measuring and
 * placing only the selected slot. Hidden slots have no semantics, pointer owner, draw pass, or
 * live position subscription. The first visit pays the ordinary composition cost; subsequent
 * switches reuse the same nodes instead of reconstructing a panel inside an active 120 Hz morph.
 */
@Composable
internal fun PortraitRetainedToolDetailDeck(
    ownerGeneration: Long,
    incomingTool: String?,
    outgoingTool: String?,
    incomingSurfaceTool: PlayerSurfaceTool,
    incomingHeightPx: Int,
    outgoingHeightPx: Int,
    interactionEnabled: Boolean,
    selectionEpoch: Long,
    bridge: PlayerSurfaceHostBridge,
    modifier: Modifier = Modifier,
    content: @Composable (tool: String, active: Boolean) -> Unit
) {
    var retainedMask by remember(ownerGeneration) { mutableIntStateOf(0) }
    val composedMask = retainedMask or
        portraitRetainedToolBit(incomingTool) or
        portraitRetainedToolBit(outgoingTool)
    SideEffect {
        if (retainedMask != composedMask) retainedMask = composedMask
    }
    val composedTools = remember(composedMask) {
        PORTRAIT_RETAINED_TOOL_ORDER.filter { tool ->
            composedMask and portraitRetainedToolBit(tool) != 0
        }
    }
    Layout(
        modifier = modifier,
        content = {
            composedTools.forEach { tool ->
                key(tool) {
                    val incoming = tool == incomingTool
                    val outgoing = tool == outgoingTool && tool != incomingTool
                    PlayerToolMotionComposeHost(
                        role = when {
                            incoming -> PlayerToolMotionComposeHostRole.DETAIL_INCOMING
                            outgoing -> PlayerToolMotionComposeHostRole.DETAIL_OUTGOING
                            else -> null
                        },
                        generation = ownerGeneration,
                        selectionEpoch = selectionEpoch,
                        targetTool = if (incoming) incomingSurfaceTool else playerSurfaceToolForDetail(tool),
                        bridge = bridge,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .playerToolMotionInteractionGuard(
                                    enabled = incoming && interactionEnabled,
                                    generation = ownerGeneration,
                                    selectionEpoch = selectionEpoch
                                )
                                .then(if (!incoming && !outgoing) Modifier.clearAndSetSemantics { } else Modifier)
                        ) { content(tool, incoming) }
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
        val incomingIndex = composedTools.indexOf(incomingTool)
        val outgoingIndex = composedTools.indexOf(outgoingTool)
            .takeIf { it >= 0 && outgoingTool != incomingTool } ?: -1
        val incomingPlaceable = if (incomingIndex >= 0) {
            measurables[incomingIndex].measure(
                Constraints.fixed(width, incomingHeightPx.coerceIn(0, height))
            )
        } else {
            null
        }
        val outgoingPlaceable = if (outgoingIndex >= 0) {
            measurables[outgoingIndex].measure(
                Constraints.fixed(width, outgoingHeightPx.coerceIn(0, height))
            )
        } else {
            null
        }
        layout(width, height) {
            outgoingPlaceable?.placeRelative(0, 0)
            incomingPlaceable?.placeRelative(0, 0)
        }
    }
}

@Composable
private fun PortraitPlayerToolOverlay(
    ownerGeneration: Long,
    targetTool: String?,
    targetSurfaceTool: PlayerSurfaceTool,
    selectionEpoch: Long,
    layoutDurationMs: Int,
    motionEnabled: Boolean,
    targetFrame: PortraitPlayerToolMotionFrame,
    availableWidthDp: Float,
    availableHeightDp: Float,
    sourceMode: PlaybackSourceMode,
    density: Float,
    routeExpansionState: State<Float>,
    includeMixer: Boolean,
    loopClearanceProgress: Float,
    statusHeightDp: Float,
    bridge: PlayerSurfaceHostBridge,
    modifier: Modifier = Modifier,
    onStatusHeightChanged: (Int) -> Unit,
    statusContent: @Composable () -> Unit,
    content: @Composable (String, Boolean) -> Unit
) {
    val expansionProgress = routeExpansionState.value
    var previousTargetTool by remember { mutableStateOf(targetTool) }
    var settledSemanticsEpoch by remember(ownerGeneration) {
        mutableLongStateOf(Long.MIN_VALUE)
    }
    val epochOutgoingTool = remember(ownerGeneration, selectionEpoch) {
        previousTargetTool?.takeIf { previous -> previous != targetTool }
    }
    val panelTransition = remember(ownerGeneration, selectionEpoch) {
        playerToolPanelTransition(previousTargetTool, targetTool)
    }
    SideEffect { previousTargetTool = targetTool }
    LaunchedEffect(ownerGeneration, selectionEpoch) {
        bridge.awaitToolMotionSettled(ownerGeneration, selectionEpoch)
        settledSemanticsEpoch = selectionEpoch
    }
    val outgoingTool = epochOutgoingTool.takeIf { settledSemanticsEpoch < selectionEpoch }
    val panelContentDurationMs = playerToolPanelContentDurationMs(
        transition = panelTransition,
        motionEnabled = motionEnabled
    )

    SideEffect {
        bridge.setToolMotionTargetSpec(
            epoch = selectionEpoch,
            generation = ownerGeneration,
            selectedTool = targetSurfaceTool,
            durationMs = layoutDurationMs,
            panelTransition = panelTransition,
            panelContentDurationMs = panelContentDurationMs,
            spatialMotionEnabled = motionEnabled,
            expandedArtworkSizePx = targetFrame.layout.artworkSizeDp * density,
            mixerCardHeightPx = targetFrame.layout.mixerCardHeightDp * density,
            mixerSpacingPx = targetFrame.layout.mixerSpacingDp * density,
            listSpacingPx = targetFrame.layout.listSpacingDp * density,
            seekerTrackHeightPx = targetFrame.layout.seekerTrackHeightDp * density,
            transportHeightPx = targetFrame.layout.transportHeightDp * density,
            detailViewportHeightPx = targetFrame.detailHeightPx.toFloat()
        )
    }

    val sourceEndpointProgress = if (sourceMode == PlaybackSourceMode.STEMS) 1f else 0f
    val routeInteractive = PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)
    val toolMotionSettled = settledSemanticsEpoch >= selectionEpoch
    val detailSemanticsReady = playerToolDetailSemanticsReady(
        targetDetail = targetTool,
        selectionEpoch = selectionEpoch,
        settledEpoch = settledSemanticsEpoch
    ) && routeInteractive
    val outgoingFrame = outgoingTool?.let { displayed ->
        portraitPlayerToolMotionFrame(
            availableWidthDp = availableWidthDp,
            availableHeightDp = availableHeightDp,
            density = density,
            detailHeightDp = portraitPlayerDetailViewportHeightDp(
                availableWidthDp = availableWidthDp,
                availableHeightDp = availableHeightDp,
                sourceMode = sourceMode,
                detail = displayed,
                statusHeightDp = statusHeightDp,
                loopClearanceProgress = loopClearanceProgress,
                fontScale = targetFrame.layout.fontScale,
                mixerLaneCount = targetFrame.mixerLaneCount
            ),
            mixerProgress = sourceEndpointProgress,
            statusHeightDp = statusHeightDp,
            loopClearanceProgress = loopClearanceProgress,
            fontScale = targetFrame.layout.fontScale,
            mixerLaneCount = targetFrame.mixerLaneCount
        )
    }
    val retainedDetailHeightPx = maxOf(
        targetFrame.detailHeightPx.coerceAtLeast(0),
        outgoingFrame?.detailHeightPx?.coerceAtLeast(0) ?: 0
    )
    Layout(
        modifier = modifier
            .playerEndpointAlpha(PlayerMotionPolicy.fullChromeAlpha(expansionProgress))
            .then(
                if (routeInteractive) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics { }
                }
            )
            .clipToBounds(),
        content = {
            PlayerToolMotionComposeHost(
                role = PlayerToolMotionComposeHostRole.STATUS,
                generation = ownerGeneration,
                selectionEpoch = selectionEpoch,
                targetTool = targetSurfaceTool,
                bridge = bridge
            ) {
                Box(
                    Modifier
                        .onSizeChanged { size -> onStatusHeightChanged(size.height) }
                        .playerToolMotionInteractionGuard(
                            enabled = routeInteractive && toolMotionSettled,
                            generation = ownerGeneration,
                            selectionEpoch = selectionEpoch
                        )
                ) { statusContent() }
            }
            Spacer(
                Modifier.onGloballyPositioned(bridge::setControlCoordinates)
            )
            // Stable measurement slot only. Tool content follows the complete native control stack;
            // it never paints over or withdraws the declared mixer lanes.
            Spacer(Modifier)
            PortraitRetainedToolDetailDeck(
                ownerGeneration = ownerGeneration,
                incomingTool = targetTool,
                outgoingTool = outgoingTool,
                incomingSurfaceTool = targetSurfaceTool,
                incomingHeightPx = targetFrame.detailHeightPx.coerceAtLeast(0),
                outgoingHeightPx = outgoingFrame?.detailHeightPx?.coerceAtLeast(0) ?: 0,
                interactionEnabled = detailSemanticsReady,
                selectionEpoch = selectionEpoch,
                bridge = bridge,
                modifier = Modifier,
                content = content
            )
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
        val height = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
        val horizontalPaddingPx = (
            portraitPlayerHorizontalPaddingDp(availableWidthDp) * density
            ).roundToInt().coerceAtLeast(0)
        val innerWidth = (width - horizontalPaddingPx * 2).coerceAtLeast(0)
        val statusPlaceable = measurables[0].measure(
            Constraints(
                minWidth = innerWidth,
                maxWidth = innerWidth,
                minHeight = 0,
                maxHeight = height
            )
        )
        val controlHeightPx = portraitPlayerControlStackHeightPx(
            frame = targetFrame,
            density = density,
            includeMixer = includeMixer,
            loopClearanceProgress = loopClearanceProgress
        ).coerceAtLeast(1)
        val controlPlaceable = measurables[1].measure(
            Constraints.fixed(innerWidth, controlHeightPx)
        )
        val detailHeightPx = retainedDetailHeightPx
        val toolBackdropPlaceable = measurables[2].measure(
            Constraints.fixed(innerWidth, 0)
        )
        val detailPlaceable = measurables[3].measure(
            Constraints.fixed(innerWidth, detailHeightPx)
        )
        val compactCorePx = PLAYER_PEEK_HEIGHT_DP * density
        val spacingPx = (targetFrame.layout.listSpacingDp * density).roundToInt()
        val detailGapPx = if (retainedDetailHeightPx > 0) {
            (portraitPlayerToolDetailGapDp(targetFrame.layout) * density).roundToInt()
        } else {
            0
        }
        val compactControlTopPx = compactCorePx + spacingPx
        val baseExpandedControlTopDp = portraitPlayerControlTopDp(
            layout = targetFrame.layout,
            includeMixer = includeMixer
        )
        val expandedControlTopPx = baseExpandedControlTopDp * density
        val targetControlTopPx = (compactControlTopPx +
            (expandedControlTopPx - compactControlTopPx) *
            expansionProgress.coerceIn(0f, 1f)).roundToInt()
        val targetControlBottomPx = targetControlTopPx + controlHeightPx
        // The reserved status band follows the fixed native motors for both shared frames.
        // It cannot cover mixer glyphs or the identity that balances above the seeker.
        val targetDetailTopPx = targetControlBottomPx + statusPlaceable.height + detailGapPx
        val displayedDetailTopPx = targetDetailTopPx

        layout(width, height) {
            statusPlaceable.placeRelative(
                horizontalPaddingPx,
                targetControlBottomPx
            )
            controlPlaceable.placeRelative(horizontalPaddingPx, targetControlTopPx)
            toolBackdropPlaceable.placeRelative(horizontalPaddingPx, displayedDetailTopPx)
            detailPlaceable.placeRelative(horizontalPaddingPx, displayedDetailTopPx)
        }
    }
}

/**
 * A translated AndroidViewHolder has no matching moving Compose hit node. Withdraw both semantics
 * and pointer ownership until the native epoch reaches its exact endpoint; changing this key also
 * cancels a detail gesture if a rapid retarget begins while a finger is still down.
 */
internal fun Modifier.playerToolMotionInteractionGuard(
    enabled: Boolean,
    generation: Long,
    selectionEpoch: Long
): Modifier = if (enabled) {
    this
} else {
    this
        .clearAndSetSemantics { }
        // stemwise-aura-exclusion: this node only blocks stale translated input; it has no action.
        .pointerInput(generation, selectionEpoch, enabled) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                        change.consume()
                    }
                }
            }
        }
}

internal fun nativePlayerSurfaceLayoutDirection(
    layoutDirection: LayoutDirection
): PlayerSurfaceLayoutDirection = if (layoutDirection == LayoutDirection.Rtl) {
    PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT
} else {
    PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
}

/** Narrow restart boundary for the one native portrait player surface. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(
    song: Song,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    library: List<Song> = listOf(song),
    onSongChanged: (Song) -> Unit = {},
    audioPlayback: AudioPlayback,
    surfaceBridge: PlayerSurfaceHostBridge? = null,
    separationRepository: SeparationRepository,
    analysisRepository: ImportedAnalysisRepository,
    onDeleteSong: suspend (Song) -> Result<PermanentDeletionOutcome>,
    onRequestStems: suspend (Song, List<String>) -> Result<Unit> = { _, _ ->
        Result.failure(IllegalStateException("Stem preparation is unavailable"))
    },
    onLoadStemSources: suspend () -> List<StemSource> = { emptyList() },
    onDeleteStems: suspend (Song) -> Result<PermanentDeletionOutcome> = {
        Result.failure(IllegalStateException("Stem deletion is unavailable"))
    },
    onShuffleChange: (Boolean) -> Unit = {},
    onMessage: (String) -> Unit = {},
    expansionProgress: Float = 1f,
    expansionProgressState: State<Float>? = null,
    onExpand: () -> Unit = {},
    onExpandedLyricsVisibilityChanged: (Boolean) -> Unit = {},
    stemJobState: String? = null,
    onOpenTasks: () -> Unit = {}
) {
    val routeExpansionState = expansionProgressState ?: rememberUpdatedState(expansionProgress)
    // AudioPlayback publishes its transport clock frequently. Project it out before collecting at
    // this 4k-line composition root; only the seeker/clock/visible lyric surface observes the
    // high-rate position state below.
    val structuralAudioState = remember(audioPlayback) {
        audioPlayback.state
            .map(::playerStructuralAudioState)
            .distinctUntilChanged()
    }
    val structuralPresentation by structuralAudioState.collectAsStateWithLifecycle(
        initialValue = playerStructuralAudioState(audioPlayback.currentStateSnapshot())
    )
    val audioState = structuralPresentation.audio
    val transportAudioState = remember(audioPlayback) {
        audioPlayback.state
            .map { state ->
                playerTransportUiState(state, audioPlayback.currentQueueSongId())
            }
            .distinctUntilChanged()
    }
    var requestedPresentationSong by remember { mutableStateOf(song) }
    val settingsRevision by audioPlayback.songSettingsRevision.collectAsStateWithLifecycle()
    // Observe invalidation without memoizing identity-sensitive answers across a root change.
    val readSongSettings = remember(audioPlayback, settingsRevision) { audioPlayback::cachedSongPlaybackSettings }
    var previousPreparedSong by remember(audioPlayback) { mutableStateOf<Song?>(null) }
    val previousPreparedSettings = previousPreparedSong?.takeIf {
        analysisRepository.preparedPresentation(it) != null
    }?.let(readSongSettings)
    val settingsCandidate = when {
        previousPreparedSong == null -> song
        previousPreparedSettings == null && requestedPresentationSong == previousPreparedSong -> song
        else -> requestedPresentationSong
    }
    val presentationPreparationRevision by analysisRepository.preparationRevision.collectAsStateWithLifecycle()
    val candidatePresentation = remember(settingsCandidate, presentationPreparationRevision, analysisRepository) {
        analysisRepository.preparedPresentation(settingsCandidate)
    }
    val candidateSettings = readSongSettings(settingsCandidate).takeIf { candidatePresentation != null }
    val preparedSong = preparedPlayerSong(settingsCandidate, candidateSettings,
        previousPreparedSong, previousPreparedSettings)
    if (preparedSong == null) {
        BackHandler(onBack = onBack)
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(audioState.error ?: audioState.notice?.message ?: "Opening song…", color = TextSecondary)
                StemwiseTextButton(onClick = onBack) { Text("Back") }
            }
        }
        return
    }
    SideEffect {
        previousPreparedSong = preparedSong
        if (previousPreparedSettings == null) requestedPresentationSong = settingsCandidate
    }
    var presentationRetryKey by remember(SongPresentationOwner.from(preparedSong)) { mutableIntStateOf(0) }
    val presentationSnapshot by rememberSongPresentationSnapshot(
        song = preparedSong,
        separationRepository = separationRepository,
        analysisRepository = analysisRepository,
        retryKey = presentationRetryKey
    )
    val publishedControlLease by audioPlayback.controlLease.collectAsStateWithLifecycle()
    val controlLease = publishedControlLease?.takeIf(presentationSnapshot.owner::owns)
    // Every metadata, artwork, lyric, analysis, and repository-backed stem read below shares this
    // one revision owner. The separately clocked native lease is admitted only when that owner
    // accepts its song/audio revision; its ownerEpoch also changes on a same-song reload.
    // `requestedPresentationSong` is only an intent cursor; it is never rendered independently.
    val activeSong = presentationSnapshot.song
    val cachedSettings = requireNotNull(candidateSettings ?: previousPreparedSettings)
    val settingsAudioState = audioPlayback.currentStateSnapshot()
    val isCurrentAudio = settingsAudioState.songId == activeSong.id &&
        audioPlayback.currentSongPlaybackSettingsAreAuthoritative(activeSong)
    val settingsProjection = projectPlayerSongSettings(activeSong, settingsAudioState, isCurrentAudio, cachedSettings)
    val playerSurfaceGenerationTracker = remember { PlayerSurfaceGenerationTracker() }
    val playerSurfaceGeneration = remember(
        presentationSnapshot.owner,
        controlLease?.ownerEpoch
    ) {
        playerSurfaceGenerationTracker.resolve(
            nextOwner = presentationSnapshot.owner,
            matchingLeaseEpoch = controlLease?.ownerEpoch
        )
    }
    val playerSurfaceBridge = surfaceBridge ?: remember { PlayerSurfaceHostBridge() }
    // The accepted visual ID outlives intermediate owner/lease generations. Recreating this slot
    // for each native generation can rewind accessibility and the visible ownership projection to
    // the outgoing song while a rapid pager route is already on its next page.
    val nativeSemanticSongIdState = remember {
        mutableStateOf(activeSong.id)
    }
    val positionState = remember(presentationSnapshot.owner, controlLease?.ownerEpoch, isCurrentAudio) {
        val currentAudio = audioPlayback.currentStateSnapshot()
        mutableLongStateOf(
            authoritativeInitialPlayerPosition(
                visibleSongId = activeSong.id,
                audioSongId = currentAudio.songId.takeIf { isCurrentAudio },
                audioPositionMs = currentAudio.positionMs
            )
        )
    }
    val analysisBundle = presentationSnapshot.analysis
    val beatEvents = analysisBundle.beatEvents
    val meterSegments = analysisBundle.meterSegments
    val waveformPeaks = analysisBundle.waveformPeaks
    val songSections = analysisBundle.songSections
    val lyricsAvailable = activeSong.lyricTracks.any { track ->
        !track.text.isNullOrBlank() || track.lines.isNotEmpty()
    }
    val metronomeAvailable = metronomeAvailableFor(beatEvents.size)
    var metronomeRequested by remember(presentationSnapshot.owner, controlLease?.ownerEpoch,
        isCurrentAudio, settingsProjection.metronome.enabled) {
        mutableStateOf(settingsProjection.metronome.enabled)
    }
    var metronomeSubdivision by remember(presentationSnapshot.owner, controlLease?.ownerEpoch,
        isCurrentAudio, settingsProjection.metronome.subdivision) {
        mutableIntStateOf(settingsProjection.metronome.subdivision)
    }
    var metronomeNumerator by remember(presentationSnapshot.owner, controlLease?.ownerEpoch,
        isCurrentAudio, settingsProjection.metronome.numerator) {
        mutableIntStateOf(settingsProjection.metronome.numerator)
    }
    var metronomeDenominator by remember(presentationSnapshot.owner, controlLease?.ownerEpoch,
        isCurrentAudio, settingsProjection.metronome.denominator) {
        mutableIntStateOf(settingsProjection.metronome.denominator)
    }
    // The row owns this observable slot. PlayerScreen deliberately never reads it: selecting a
    // tool can therefore repaint the 48 dp control without first restarting this 4k-line scope.
    val selectedToolState = rememberSaveable(activeSong.id) {
        mutableStateOf<String?>(null)
    }
    // Presentation stays in a narrow restart boundary. Interactive callbacks co-publish this with
    // selectedToolState; the bridge below only reconciles noninteractive owner/reset changes.
    var presentedTool by remember(activeSong.id) {
        mutableStateOf(resolvedPlayerTool(selectedToolState.value))
    }
    PlayerToolPresentationBridge(
        ownerKey = presentationSnapshot.owner,
        selectedTool = selectedToolState,
        onPresented = { presentedTool = it }
    )
    var skipFeedback by remember { mutableStateOf<String?>(null) }
    val isScrubbingState = remember(playerSurfaceGeneration) { mutableStateOf(false) }
    var showSongInfo by remember(activeSong.id) {
        mutableStateOf(false)
    }
    var showTrim by remember(activeSong.id, activeSong.sourceHash) { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    val ownedStemJobState = stemJobState.takeIf { song.id == activeSong.id }
    var stemRequestPending by remember(activeSong.id) { mutableStateOf(false) }
    val stemsDownloading = stemRequestPending ||
        (ownedStemJobState != null && ownedStemJobState !in setOf("FAILED", "SUBMISSION_UNKNOWN", "COMPLETE", "OBSOLETE"))
    var stemDownloadRequest by rememberSaveable(activeSong.id) { mutableStateOf<String?>(null) }
    var stemSourceLoadAttempt by remember(activeSong.id) { mutableIntStateOf(0) }
    var availableStemSources by remember(activeSong.id) { mutableStateOf(emptyList<StemSource>()) }
    var stemSourceLoadError by remember(activeSong.id) { mutableStateOf<String?>(null) }
    var stemSourceLoadFinishedFor by remember(activeSong.id) {
        mutableStateOf<Pair<String, Int>?>(null)
    }
    PlayerSourceCatalogueEffect(
        request = stemDownloadRequest,
        attempt = stemSourceLoadAttempt,
        loadSources = onLoadStemSources,
        isCurrentRequest = { targetId, attempt -> stemDownloadRequest == targetId && stemSourceLoadAttempt == attempt },
        onLoaded = { sources, token ->
            availableStemSources = sources
            stemSourceLoadError = null
            stemSourceLoadFinishedFor = token
        },
        onFailed = { token ->
            stemSourceLoadError = "Could not load song parts. Try again."
            stemSourceLoadFinishedFor = token
        }
    )
    var stemDeletionRequest by rememberSaveable(activeSong.id) { mutableStateOf<String?>(null) }
    var deletionRequest by rememberSaveable(activeSong.id) { mutableStateOf<String?>(null) }
    var showStemActions by remember(activeSong.id) { mutableStateOf(false) }
    var sourceError by remember(activeSong.id) { mutableStateOf<PlayerControlFailure?>(null) }
    val stemActivation = remember(activeSong.id) { PlayerStemActivationOwner() }
    val activeStemAssets = presentationSnapshot.separation.activeStemAssets
    val usableSeparationSets = presentationSnapshot.separation.usableSets
    val activeSeparationSet = presentationSnapshot.separation.activeSet
    // Each control owns one state slot. Updating one part must not rebuild the other sliders,
    // toggle semantics trees, icons, and cards on every pointer MOVE.
    val hapticView = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cancelStemActivation: () -> Unit = stemActivation::cancel
    val latestCancelStemActivation by rememberUpdatedState(cancelStemActivation)
    DisposableEffect(presentationSnapshot.owner, controlLease?.native) {
        // External queue advances and same-song reloads also retire the old activation.
        // Capture this owner's cancellation callback, not its replacement from a later frame.
        onDispose { cancelStemActivation() }
    }
    val motion = LocalStemwiseMotion.current
    val settledSourceMode = if (isCurrentAudio) {
        settingsAudioState.sourceMode
    } else {
        PlaybackSourceMode.ORIGINAL
    }
    val settledSourceSwitchState = if (isCurrentAudio) {
        settingsAudioState.sourceSwitchState
    } else {
        SourceSwitchState.Original
    }
    val sourceSwitchState = if (
        settledSourceSwitchState is SourceSwitchState.Original && stemActivation.pending
    ) {
        SourceSwitchState.PreparingStems(targetSetId = null)
    } else {
        settledSourceSwitchState
    }
    var retainedSourceMode by remember(playerSurfaceGeneration) {
        mutableStateOf(settledSourceMode)
    }
    val sourceMode = playerPresentedSourceMode(
        settledSourceMode, sourceSwitchState, retainedSourceMode
    )
    SideEffect { retainedSourceMode = sourceMode }
    // Source identity, mixer package and geometry publish as one presentation transaction. A
    // one-frame split briefly exposed Original chrome with the retained Stems layout (and the
    // reverse), which looked like controls flashing in and disappearing during a source change.
    val presentedSourceMode = sourceMode
    // The mixer describes the playback facade's canonical package: exact paused preparation or
    // audible installation, never a newer database emission that was not explicitly selected.
    val playbackStemPackagePresent =
        isCurrentAudio && settingsAudioState.loadedStemAssets.isNotEmpty()
    val playbackStemSet = if (playbackStemPackagePresent) {
        usableSeparationSets.firstOrNull { set -> set.id == settingsAudioState.loadedStemSetId }
    } else {
        null
    }
    val ownedPlaybackStemAssets = if (playbackStemPackagePresent) {
        coherentStemAssetsForOwner(
            owner = presentationSnapshot.owner,
            owningSet = playbackStemSet,
            activeStems = settingsAudioState.loadedStemAssets
        )
    } else {
        emptyList()
    }
    val displayedStemAssets = if (playbackStemPackagePresent) {
        ownedPlaybackStemAssets
    } else {
        activeStemAssets
    }
    val displayedStemSetId = if (playbackStemPackagePresent) {
        playbackStemSet?.id?.takeIf { ownedPlaybackStemAssets.isNotEmpty() }
    } else {
        activeSeparationSet?.id
    }
    val mixerSettingsKey = if (isCurrentAudio) controlLease?.ownerEpoch else cachedSettings.stemMix
    val stems = remember(presentationSnapshot.owner, displayedStemSetId, displayedStemAssets, isCurrentAudio, mixerSettingsKey) {
        mutableStateListOf<StemUiSlot>().apply {
            addAll(playerSettingsStemRows(displayedStemAssets,
                if (isCurrentAudio) audioPlayback.currentStateSnapshot().stemMix else emptyMap(),
                cachedSettings.stemMix).map(StemUiSlot::from))
        }
    }
    val stemControllerReady = controlLease != null && !settingsAudioState.loading
    val stemAvailability = remember(displayedStemAssets, playbackStemPackagePresent,
        presentationSnapshot.contentLoaded, usableSeparationSets.isNotEmpty(), isCurrentAudio, stemControllerReady) {
        playerStemAvailability(displayedStemAssets, playbackStemPackagePresent,
            presentationSnapshot.contentLoaded, usableSeparationSets.isNotEmpty(), isCurrentAudio, stemControllerReady)
    }
    val hasCompleteStemSet = stemAvailability.hasCompleteSet
    val loopPreparation = settingsAudioState.loopPreparation.takeIf { isCurrentAudio }
        ?: app.stemwise.playback.LoopPreparationState()
    val loopAvailable = isCurrentAudio && loopPreparation.canConfigure
    val loopA = settingsProjection.loopA
    val loopB = settingsProjection.loopB
    var requestedTempo by remember(presentationSnapshot.owner, controlLease?.ownerEpoch, isCurrentAudio, settingsProjection.tempo) {
        mutableFloatStateOf(settingsProjection.tempo)
    }
    var requestedPitch by remember(presentationSnapshot.owner, controlLease?.ownerEpoch, isCurrentAudio, settingsProjection.semitones) {
        mutableIntStateOf(settingsProjection.semitones)
    }
    val tempo = requestedTempo
    val pitch = requestedPitch
    val gridMetronomeBpm = remember(activeSong.bpm, beatEvents) {
        effectiveGridBpm(activeSong.bpm, beatEvents.map { it.timeMs })
    }
    val effectiveMetronomeBpm =
        gridMetronomeBpm * tempo
    val metronomeBpmRange = remember(gridMetronomeBpm) {
        reachableMetronomeBpmRange(gridMetronomeBpm)
    }
    // Unlike an effect-only request, this bit changes only after the exact native lease accepts the
    // command below. It is therefore safe to paint on the first frame without waiting for the
    // lifecycle StateFlow collector to make a second round trip through PlayerScreen.
    val metronome = metronomeRequested
    val timelineDuration = settingsProjection.durationMs
    val trimStartMs = settingsProjection.trimStartMs
    val sourceDurationMs = settingsProjection.sourceDurationMs
    val trimEndMs = trimStartMs + timelineDuration
    val hasTrim = trimStartMs > 0L || trimEndMs < sourceDurationMs
    val displayWaveformPeaks = remember(waveformPeaks, sourceDurationMs, trimStartMs, trimEndMs, hasTrim) {
        if (hasTrim) trimWaveformPeaks(waveformPeaks, sourceDurationMs, trimStartMs, trimEndMs) else waveformPeaks
    }
    val displaySections = remember(songSections, trimStartMs, trimEndMs, hasTrim) {
        if (hasTrim) trimSongSections(songSections, trimStartMs, trimEndMs) else songSections
    }
    val displayBeats = remember(beatEvents, trimStartMs, trimEndMs, hasTrim) {
        if (hasTrim) trimBeatEvents(beatEvents, trimStartMs, trimEndMs) else beatEvents
    }
    val displayMeters = remember(meterSegments, trimStartMs, trimEndMs, hasTrim) {
        if (hasTrim) trimMeterSegments(meterSegments, trimStartMs, trimEndMs) else meterSegments
    }
    val lyricsSong = remember(activeSong, trimStartMs, trimEndMs, hasTrim) {
        if (hasTrim) activeSong.copy(lyricTracks = trimLyricTracks(activeSong.lyricTracks, trimStartMs, trimEndMs)) else activeSong
    }

    LaunchedEffect(presentationSnapshot.owner, meterSegments, cachedSettings.metronome) {
        meterSegments.firstOrNull()?.takeIf { cachedSettings.metronome == null }?.let { meter ->
            metronomeNumerator = meter.numerator
            metronomeDenominator = meter.denominator
        }
    }

    // Keep the audible owner renderable while a library refresh removes or replaces rows. Audio
    // decides when ownership moves; a transient database snapshot must never blank the player.
    val libraryById = remember(library) { library.associateBy(Song::id) }
    val songs = remember(library, activeSong) {
        when (libraryById[activeSong.id]) {
            activeSong -> library
            null -> listOf(activeSong) + library
            else -> library.map { if (it.id == activeSong.id) activeSong else it }
        }
    }
    LaunchedEffect(playerSurfaceGeneration, libraryById, activeSong.id) {
        if (nativeSemanticSongIdState.value !in libraryById &&
            nativeSemanticSongIdState.value != activeSong.id) {
            nativeSemanticSongIdState.value = activeSong.id
        }
    }
    val availableSongIds = remember(songs) { songs.map(Song::id) }
    val trackState = rememberPlayerVisualOwnerState(activeSong.id)
    val queueProjection = remember(audioPlayback) {
        PlayerQueueProjection(
            availableSongs = songs,
            queueOrder = audioPlayback.currentStateSnapshot().queueOrder
        )
    }

    // A library/owner change already recomposes this root. Reconcile it after apply without
    // making queue order a root-observed Snapshot value.
    SideEffect {
        queueProjection.update(
            availableSongs = songs,
            queueOrder = audioPlayback.currentStateSnapshot().queueOrder
        )
        trackState.updateOrder(queueProjection.orderedSongIds)
    }
    // Shuffle order has a dedicated imperative collector. It updates the stable-ID track before
    // its narrow artwork composition observes the new song list, so a rapid Next/Previous command
    // cannot animate against a stale visual order. Neither this collector nor its keys change for
    // an ordinary queue reorder, so the PlayerScreen root remains outside the invalidation path.
    LaunchedEffect(audioPlayback, songs, queueProjection, trackState) {
        audioPlayback.state
            .map { state -> state.queueOrder }
            .distinctUntilChanged()
            .collect { queueOrder ->
                // Queue identity is authoritative input state, not delayed presentation work.
                // Publish it before a same-frame Next/Previous gesture can consult the visual map;
                // only layout/drawing may consume the following frame.
                queueProjection.update(songs, queueOrder)
                trackState.updateOrder(queueProjection.orderedSongIds)
            }
    }
    val latestRequestedSongIdState = rememberUpdatedState(song.id)
    val latestRequestedSongId by latestRequestedSongIdState
    val pagerSyncTargetState = remember { mutableStateOf<String?>(null) }
    var pagerSyncTargetId by pagerSyncTargetState
    val explicitTransport = remember { ExplicitTransportMotionState() }
    val onUserPagerSongSelected: (String) -> Boolean = userSelection@{ selectedId ->
        val selected = queueProjection.orderedSongById[selectedId] ?: return@userSelection false
        val accepted = audioPlayback.selectSong(
            selected,
            // A settled manual swipe is a direct song choice, not a paused queue preview. Match a
            // Library-row selection: the new owner starts even when the outgoing owner was paused.
            autoplay = true,
            positionMs = 0L
        )
        // An unresolved/unavailable destination keeps the outgoing visual and queue owner. The
        // audio-session collector will publish a later resolved owner as one normal transaction.
        if (!accepted) return@userSelection false
        explicitTransport.pagerJob?.cancel()
        explicitTransport.pagerJob = null
        explicitTransport.cursor.issue(selected.id)
        pagerSyncTargetId = selected.id
        latestCancelStemActivation()
        requestedPresentationSong = selected
        onSongChanged(selected)
        if (audioPlayback.cachedSongPlaybackSettings(selected) == null ||
            analysisRepository.preparedPresentation(selected) == null) return@userSelection false
        nativeSemanticSongIdState.value = selected.id
        positionState.longValue = 0L
        selectedToolState.value = null
        presentedTool = null
        true
    }
    val requestTrackChange: (TrackChangeDirection) -> Int = change@{ direction ->
        val songIdBeforeCommand = audioPlayback.currentQueueSongId()
        val authoritativeSongId = dispatchPlayerTransport(
            direction = direction,
            nextTrack = audioPlayback::nextTrack,
            previousOrRestart = audioPlayback::previousOrRestart
        ) ?: return@change -1

        val target = playerTransportVisualTarget(
            authoritativeSongId = authoritativeSongId,
            pageBySongId = queueProjection.orderedSongPageById
        ) ?: return@change -1
        if (playerTransportRestartedCurrent(direction, songIdBeforeCommand, authoritativeSongId)) {
            positionState.longValue = audioPlayback.currentStateSnapshot().positionMs
            return@change target.index
        }
        val selected = queueProjection.orderedSongById[target.songId]
            ?: return@change -1

        explicitTransport.pagerJob?.cancel()
        val commandToken = explicitTransport.cursor.issue(selected.id)
        pagerSyncTargetId = selected.id
        latestCancelStemActivation()

        // AudioPlayback has already advanced the one queue and requested the audible owner. The
        // returned stable ID only drives artwork/identity presentation; never select it again.
        requestedPresentationSong = selected
        onSongChanged(selected)
        if (audioPlayback.cachedSongPlaybackSettings(selected) == null ||
            analysisRepository.preparedPresentation(selected) == null) {
            // Transport accepted the target; keep the coherent outgoing profile until its cache is ready.
            return@change queueProjection.orderedSongPageById[activeSong.id] ?: -1
        }
        nativeSemanticSongIdState.value = selected.id
        positionState.longValue = 0L
        selectedToolState.value = null
        presentedTool = null
        val pagerJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                trackState.snapToSong(target.songId)
            } finally {
                if (
                    explicitTransport.cursor.owns(commandToken, selected.id) &&
                    trackState.settledSongId == selected.id &&
                    latestRequestedSongId == selected.id
                ) {
                    explicitTransport.cursor.settle(selected.id)
                    if (pagerSyncTargetId == selected.id) pagerSyncTargetId = null
                    explicitTransport.pagerJob = null
                }
            }
        }
        explicitTransport.pagerJob = pagerJob
        pagerJob.start()
        target.index
    }

    PlayerSelectionSynchronization(
        song, activeSong.id, songs, availableSongIds, audioState.loading, audioState.songId,
        audioPlayback, queueProjection, trackState, explicitTransport, pagerSyncTargetState,
        nativeSemanticSongIdState, latestRequestedSongIdState,
        settingsRevision = Math.addExact(settingsRevision, presentationPreparationRevision),
        settingsReady = { audioPlayback.cachedSongPlaybackSettings(it) != null &&
            analysisRepository.preparedPresentation(it) != null },
        onCancelStemActivation = cancelStemActivation,
        onRequestedSongChanged = { requestedPresentationSong = it }
    )

    LaunchedEffect(skipFeedback) {
        if (skipFeedback != null) {
            delay(650)
            skipFeedback = null
        }
    }

    val playerLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(audioPlayback, activeSong.id, timelineDuration, playerLifecycleOwner, isScrubbingState, isCurrentAudio) {
        playerLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            audioPlayback.state.collect { state ->
                if (state.songId == activeSong.id &&
                    audioPlayback.currentSongPlaybackSettingsAreAuthoritative(activeSong) && !isScrubbingState.value) {
                    val publishedPosition = state.positionMs.coerceIn(0L, timelineDuration)
                    if (positionState.longValue != publishedPosition) {
                        positionState.longValue = publishedPosition
                    }
                }
            }
        }
    }

    PlayerMetronomeProfile(
        audioPlayback, isCurrentAudio, controlLease, presentationSnapshot.contentLoaded && !presentationSnapshot.contentReadFailed,
        metronomeAvailable, effectiveMetronomeBpm,
        metronomeSubdivision, metronomeNumerator, metronomeDenominator,
        beatEvents, meterSegments
    )

    val onArtworkSkip: (Long) -> Boolean = artworkSkip@{ delta ->
        val callbackLease = controlLease
        val receipt = callbackLease?.let { audioPlayback.seekByWithReceipt(delta, it) }
        var admitted = false
        if (receipt != null) {
            positionState.longValue = receipt.positionMs
            hapticView.performStemwiseHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            admitted = true
            skipFeedback = if (delta < 0) "−5 sec" else "+5 sec"
        }
        admitted
    }
    val onSourceModeSelected: (PlaybackSourceMode) -> Boolean = sourceMode@{ selectedMode ->
        sourceError = null
        if (sourcePartsActionsRequested(selectedMode, hasCompleteStemSet || activeSong.hasSourceParts, sourceSwitchState)) {
            showStemActions = true
            return@sourceMode true
        }
        if (selectedMode == PlaybackSourceMode.STEMS && !hasCompleteStemSet) {
            if (sourceSwitchState is SourceSwitchState.PreparingStems) return@sourceMode false
            if (ownedStemJobState != null || stemRequestPending) {
                onOpenTasks()
                return@sourceMode true
            }
            stemSourceLoadAttempt++
            stemDownloadRequest = activeSong.id
            return@sourceMode true
        }
        if (!sourceTargetEnabled(selectedMode, hasCompleteStemSet, sourceSwitchState)) {
            return@sourceMode false
        }
        val callbackLease = controlLease
        if (callbackLease == null) {
            sourceError = PlayerControlFailure.control("The player is still switching songs")
            return@sourceMode false
        }
        if (selectedMode == PlaybackSourceMode.ORIGINAL) {
            cancelStemActivation()
            return@sourceMode audioPlayback.switchToOriginal(callbackLease)
        } else if (
            sourceTargetEnabled(
                PlaybackSourceMode.STEMS,
                hasCompleteStemSet,
                sourceSwitchState
            )
        ) {
            val requestedSong = activeSong
            val requestedStems = displayedStemAssets.toList()
            val requestedLease = callbackLease
            stemActivation.start(scope, activate = {
                audioPlayback.activateStemsForCurrentSong(requestedSong, requestedStems, requestedLease)
            }, onFailure = {
                sourceError = PlayerControlFailure.source(it.message ?: "Could not prepare the stem mixer")
            })
            return@sourceMode true
        }
        false
    }
    val displayedStemAssetByRole = remember(
        presentationSnapshot.owner,
        displayedStemSetId,
        displayedStemAssets
    ) {
        displayedStemAssets.associateBy { asset ->
            asset.normalizedCapability
                ?.takeIf(String::isNotBlank)
                ?: asset.providerLabel.lowercase(Locale.ROOT)
        }
    }
    val compactMixerLaneKey = stems.joinToString(separator = "\u0000") { slot ->
        // Slot identity is part of the binding. Reconciliation may replace a slot while retaining
        // the same normalized role; an old listener must never keep writing to that detached slot.
        "${slot.id}\u0001${slot.label}\u0001${slot.color.value}\u0001" +
            System.identityHashCode(slot)
    }
    val fineWaveformBinding = PlayerWaveformBinding(
        owner = presentationSnapshot.owner,
        sourceMode = presentedSourceMode,
        sourceReady = sourceSwitchState is SourceSwitchState.Original || sourceSwitchState is SourceSwitchState.Stems,
        stemAssets = if (presentedSourceMode == PlaybackSourceMode.STEMS) displayedStemAssets else emptyList(),
        stemMix = if (presentedSourceMode == PlaybackSourceMode.STEMS) stems.associate { slot ->
            slot.id to app.stemwise.playback.StemMixState(slot.committedVolume.floatValue, slot.muted.value, slot.solo.value)
        } else emptyMap()
    )
    val latestFineWaveformBinding = rememberUpdatedState(fineWaveformBinding)
    val nativeFineWindowState = remember(playerSurfaceGeneration) {
        mutableStateOf<PlayerFineWaveformWindow?>(null)
    }
    val nativeFineOwner = remember(playerSurfaceGeneration) { NativeFineWaveformOwner() }
    val nativeMixerGestureOwner = remember(playerSurfaceGeneration) {
        NativeMixerGestureOwner()
    }
    val nativeLoopBoundaryOwner = remember(playerSurfaceGeneration) {
        NativeLoopBoundaryGestureOwner()
    }
    DisposableEffect(playerSurfaceGeneration) {
        onDispose {
            nativeFineOwner.cancel()
            nativeLoopBoundaryOwner.clear()
        }
    }
    val latestPagerSelection = rememberUpdatedState(onUserPagerSongSelected)
    val latestTrackChange = rememberUpdatedState(requestTrackChange)
    val latestArtworkSkip = rememberUpdatedState(onArtworkSkip)
    val latestSourceSelection = rememberUpdatedState(onSourceModeSelected)
    val latestExpand = rememberUpdatedState(onExpand)
    val latestShuffleChange = rememberUpdatedState(onShuffleChange)
    val latestCollapsePlayer by rememberUpdatedState(onBack)
    val latestShowSongInfo by rememberUpdatedState<() -> Unit>({ showSongInfo = true })
    val latestNativeToolSelection by rememberUpdatedState<(PlayerSurfaceTool) -> Boolean>({ tool ->
        if (tool == PlayerSurfaceTool.LOOP && !loopAvailable) {
            onMessage(loopPreparationDescription(loopPreparation))
            return@rememberUpdatedState false
        }
        if (tool == PlayerSurfaceTool.LYRICS) {
            if (showLyricsSheet) return@rememberUpdatedState true
            selectedToolState.value = null
            presentedTool = null
            showLyricsSheet = true
            return@rememberUpdatedState true
        }
        val target = when (tool) {
            PlayerSurfaceTool.LOOP -> PLAYER_DETAIL_LOOP
            PlayerSurfaceTool.TUNE -> PLAYER_DETAIL_TUNE
            PlayerSurfaceTool.LYRICS -> null
            PlayerSurfaceTool.METRONOME -> PLAYER_DETAIL_METRONOME
            PlayerSurfaceTool.NONE -> null
        }
        val next = if (target == null) {
            null
        } else {
            toggledPlayerDetail(selectedToolState.value, target)
        }
        // One user action publishes one endpoint composition and no second state wave.
        selectedToolState.value = next
        presentedTool = resolvedPlayerTool(next)
        true
    })
    val fullPlayerActionSink = remember(
        playerSurfaceGeneration,
        compactMixerLaneKey,
        controlLease,
        sourceSwitchState,
        hasCompleteStemSet,
        activeSong.id,
        activeSong.mediaRevision,
        displayedStemAssetByRole,
        timelineDuration,
        trimStartMs,
        hasTrim,
        usableSeparationSets.size,
        playerSurfaceBridge
    ) {
        val expectedGeneration = playerSurfaceGeneration
        val callbackLease = controlLease
        val callbackSlots = stems.toTypedArray()
        val callbackAssetIds = Array<String?>(callbackSlots.size) { index ->
            displayedStemAssetByRole[callbackSlots[index].id]?.id
        }
        val callbackRoles = callbackSlots.map { it.id }
        val canonicalRoles = if (StemRoleContract.isPlayable(callbackRoles)) {
            StemRoleContract.orderedPlayableRoles(callbackRoles)
        } else emptyList()
        val callbackCanonicalLaneIndices = IntArray(callbackSlots.size) { index ->
            canonicalRoles.indexOf(StemRoleContract.normalize(callbackSlots[index].id))
        }
        OwnedPlayerActionSink(
            expectedGeneration, callbackLease, callbackSlots, callbackAssetIds,
            callbackCanonicalLaneIndices, audioPlayback, activeSong, timelineDuration,
            queueProjection, positionState, isScrubbingState, nativeFineOwner,
            nativeFineWindowState, nativeMixerGestureOwner, nativeLoopBoundaryOwner, scope,
            readFineWindowKey = {
                val binding = latestFineWaveformBinding.value
                val native = if (binding.sourceMode == PlaybackSourceMode.STEMS) {
                    audioPlayback.sourceWaveformIdentity()?.takeIf { identity ->
                        identity.songId == binding.owner.songId &&
                            identity.audioRevision == binding.owner.audioRevision.ifBlank { binding.owner.sourceHash.orEmpty() } &&
                            identity.sourceMode == app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS
                    }
                } else null
                PlayerFineWaveformKey(binding, native)
            },
            readFineWindow = { center, key ->
                if (key.binding.sourceMode == PlaybackSourceMode.ORIGINAL) {
                    analysisRepository.fineWindow5ms(
                        songId = activeSong.id, centerMs = center + trimStartMs, mediaRevision = activeSong.mediaRevision,
                        sourceSha256 = activeSong.sourceHash.orEmpty()
                    )?.let { if (hasTrim) trimFineWindow(it, trimStartMs, trimEndMs) else it }
                } else key.native?.let { identity ->
                    audioPlayback.readSourceWaveformWindow(identity, center)?.let { window ->
                        WaveformWindow(window.startMs, window.endMs, window.frameDurationMs, window.peaks)
                    }
                }
            },
            expandPlayer = { latestExpand.value() },
            collapsePlayer = { latestCollapsePlayer() },
            showSongInfo = { latestShowSongInfo() },
            selectPagerSong = { latestPagerSelection.value(it) },
            changeTrack = { latestTrackChange.value(it) },
            skipArtwork = { latestArtworkSkip.value(it) },
            changeShuffle = { latestShuffleChange.value(it) },
            selectSource = { latestSourceSelection.value(it) },
            selectTool = { latestNativeToolSelection(it) },
            onControlFailure = { sourceError = it },
            playerSurfaceBridge = playerSurfaceBridge
        )
    }
    DisposableEffect(fullPlayerActionSink) {
        onDispose { fullPlayerActionSink.retireScrub() }
    }
    val playerSurfaceSemanticsSink = remember(
        playerSurfaceGeneration,
        fullPlayerActionSink,
        playerSurfaceBridge
    ) {
        val expectedGeneration = playerSurfaceGeneration
        OwnedPlayerSemanticsSink(
            expectedGeneration, fullPlayerActionSink, playerSurfaceBridge, queueProjection,
            nativeSemanticSongIdState, positionState, timelineDuration, stems
        )
    }
    fun applyTuning(updatedTempo: Float, updatedPitch: Int, persist: Boolean = true): Boolean {
        // Never let a terminal callback from an outgoing song reselect or retune the newest
        // owner. The tune controls become live as soon as this visible song owns the latest
        // native request; imported analysis is not required.
        val callbackLease = controlLease
        val accepted = callbackLease != null && audioPlayback.setTempoAndPitch(
                updatedTempo,
                updatedPitch,
                callbackLease,
                persist = persist
            )
        if (accepted) {
            requestedTempo = updatedTempo
            requestedPitch = updatedPitch
        }
        return accepted
    }
    val setOwnedLoop: (Long?, Long?, Long) -> Boolean = { pointA, pointB, transportPositionMs ->
        controlLease?.let { callbackLease ->
            audioPlayback.setLoop(
                pointA,
                pointB,
                callbackLease,
                transportPositionMs
            )
        } == true
    }
    val seekOwnedSong: (Long) -> app.stemwise.playback.engine.NativeAudioPlaybackSeekReceipt? = { targetMs ->
        controlLease?.let { callbackLease ->
            audioPlayback.seekToWithReceipt(targetMs, callbackLease)
        }
    }
    val setOwnedMetronomeVolume: (Float) -> Boolean = { volume ->
        controlLease?.let { callbackLease ->
            audioPlayback.setMetronomeVolume(volume, callbackLease)
        } == true
    }
    val setOwnedMetronomeProfile: (Int, Int, Int) -> Boolean = profile@{
            subdivision, numerator, denominator ->
        val callbackLease = controlLease ?: return@profile false
        val normalizedSubdivision = normalizedMetronomeSubdivision(subdivision)
        val normalizedNumerator = numerator.coerceIn(1, 32)
        val normalizedDenominator = normalizedMetronomeBeatUnit(denominator)
        val accepted = audioPlayback.setMetronome(
            enabled = metronomeAvailable && metronomeRequested,
            bpm = effectiveMetronomeBpm,
            songSynced = metronomeAvailable,
            beatEvents = beatEvents,
            meterSegments = meterSegments,
            subdivision = normalizedSubdivision,
            numerator = normalizedNumerator,
            denominator = normalizedDenominator,
            controlLease = callbackLease
        )
        if (accepted) {
            metronomeSubdivision = normalizedSubdivision
            metronomeNumerator = normalizedNumerator
            metronomeDenominator = normalizedDenominator
        }
        accepted
    }
    val toggleOwnedMetronome: () -> Boolean = {
        val callbackLease = controlLease
        if (callbackLease != null && isCurrentAudio && metronomeAvailable) {
            val requestedEnabled = !metronomeRequested
            val accepted = audioPlayback.setMetronome(
                    enabled = requestedEnabled,
                    bpm = effectiveMetronomeBpm,
                    songSynced = true,
                    beatEvents = beatEvents,
                    meterSegments = meterSegments,
                    subdivision = metronomeSubdivision,
                    numerator = metronomeNumerator,
                    denominator = metronomeDenominator,
                    controlLease = callbackLease
                )
            if (accepted) {
                // Accepted desired state is already queued atomically for this lease. Local paint
                // cannot diverge: rejection leaves the value untouched and the StateFlow effect
                // above reconciles external/native changes for the same owner.
                metronomeRequested = requestedEnabled
            }
            accepted
        } else {
            false
        }
    }
    val ownedAudioMessage = nonDuplicatePlaybackError(
        primary = sourceError?.message,
        candidate = (audioState.error ?: audioState.notice?.message).takeIf { isCurrentAudio }
    )
    val fullPlayerStatusPacket = remember(
        sourceError,
        ownedAudioMessage,
        audioState.error,
        audioState.notice,
        structuralPresentation.failureHeading
    ) {
        when {
            sourceError != null -> FullPlayerStatusPacket(
                revision = nextPlayerSurfaceRevision(),
                kind = FullPlayerStatusKind.ERROR,
                primaryText = sourceError?.heading,
                secondaryText = sourceError?.message
            )
            ownedAudioMessage != null -> FullPlayerStatusPacket(
                revision = nextPlayerSurfaceRevision(),
                kind = if (audioState.error != null) FullPlayerStatusKind.ERROR else FullPlayerStatusKind.NOTICE,
                primaryText = if (audioState.error != null) structuralPresentation.failureHeading else audioState.notice?.title,
                secondaryText = ownedAudioMessage
            )
            else -> FullPlayerStatusPacket(revision = nextPlayerSurfaceRevision())
        }
    }
    val statusContent: @Composable () -> Unit = {
        Column {
            AnimatedContent(
                targetState = fullPlayerStatusPacket,
                contentKey = { packet ->
                    Triple(packet.kind, packet.primaryText, packet.secondaryText)
                },
                transitionSpec = {
                    fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        .togetherWith(
                            fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE))
                        )
                },
                label = "player-status"
            ) { packet ->
                when (packet.kind) {
                    FullPlayerStatusKind.ERROR ->
                        PlaybackErrorCard(packet)
                    FullPlayerStatusKind.NOTICE,
                    FullPlayerStatusKind.PROGRESS -> PlaybackStatusCard(packet)
                    FullPlayerStatusKind.NONE -> Spacer(Modifier.height(0.dp))
                }
            }
            PlayerPresentationRetry(presentationSnapshot.contentReadFailed) { presentationRetryKey++ }
            LoopPreparationNotice(loopPreparation) {
                controlLease?.let { audioPlayback.retryLoopPreparation(it, loopPreparation) } ?: false
            }
        }
    }
    val compactPaletteKey = remember(
        activeSong.id,
        activeSong.mediaRevision,
        activeSong.artworkSource
    ) {
        artworkAuraPaletteKey(activeSong)
    }
    val nativeArtworkPaletteListener = remember(
        playerSurfaceGeneration,
        activeSong.id,
        activeSong.mediaRevision,
        activeSong.artworkSource,
        compactPaletteKey
    ) {
        val expectedSource = activeSong.artworkSource?.trim().orEmpty()
        val expectedMediaRevision = activeSong.mediaRevision.trim()
        PlayerSurfaceArtworkPaletteListener {
            ownerGeneration,
            songId,
            mediaRevision,
            physicalSource,
            primaryArgb,
            secondaryArgb,
            tertiaryArgb,
            dominantArgb ->
            if (
                ownerGeneration == playerSurfaceGeneration &&
                songId == activeSong.id &&
                mediaRevision == expectedMediaRevision &&
                physicalSource == expectedSource
            ) {
                ArtworkAuraPaletteCache.put(
                    compactPaletteKey,
                    ArtworkAuraPalette(
                        primary = Color(primaryArgb),
                        secondary = Color(secondaryArgb),
                        tertiary = Color(tertiaryArgb),
                        dominant = Color(dominantArgb)
                    )
                )
            }
        }
    }
    val backgroundPalette = remember(compactPaletteKey) {
        ArtworkAuraPaletteCache.observe(compactPaletteKey)
    }
    val backgroundColors = remember(backgroundPalette.value) {
        playerArtworkBackgroundColors(backgroundPalette.value ?: MissingArtworkAuraPalette)
    }
    PlayerLyricsModalHost(
        visible = showLyricsSheet,
        modifier = modifier,
        onPresenceChanged = onExpandedLyricsVisibilityChanged,
        onDismiss = { showLyricsSheet = false },
        song = lyricsSong,
        presentationGeneration = playerSurfaceGeneration,
        positionState = positionState,
        transport = {
            PlayerOverlayTransport(
                generation = playerSurfaceGeneration,
                durationMs = timelineDuration,
                positionState = positionState,
                transportState = transportAudioState,
                actionSink = fullPlayerActionSink
            )
        },
        onSeek = lyricSeek@{ expectedGeneration, target ->
            if (expectedGeneration != playerSurfaceGeneration) return@lyricSeek false
            val effectiveTarget = loopAwareLyricSeekTarget(
                requestedMs = target,
                loopA = loopA,
                loopB = loopB,
                durationMs = timelineDuration
            )
            val receipt = seekOwnedSong(effectiveTarget) ?: return@lyricSeek false
            positionState.longValue = receipt.positionMs
            true
        }
    ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // One visual background morphs from the closed 64 dp compact card to the full sheet.
        // The PageBackground below is transparent, so neither its fill nor overlay can leak
        // through the compact envelope's 2 dp top/bottom margins.
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val geometry = compactPlayerVisualGeometry(
                        availableWidthDp = size.width / density,
                        availableHeightDp = size.height / density,
                        expansionProgress = routeExpansionState.value
                    )
                    val topLeft = Offset(
                        geometry.leftDp.dp.toPx(),
                        geometry.topDp.dp.toPx()
                    )
                    val bodySize = Size(
                        geometry.widthDp.dp.toPx(),
                        geometry.heightDp.dp.toPx()
                    )
                    val corner = geometry.radiusDp.dp.toPx()
                    val cornerRadius = CornerRadius(corner, corner)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            backgroundColors,
                            startY = topLeft.y,
                            endY = topLeft.y + bodySize.height
                        ),
                        topLeft = topLeft,
                        size = bodySize,
                        cornerRadius = cornerRadius
                    )
                }
        )
        PageBackground(
            modifier = Modifier.fillMaxSize(),
            backgroundAlpha = 0f
        ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                )
        ) {
        val portraitAvailableWidthDp = maxWidth.value
        val portraitAvailableHeightDp = maxHeight.value
        val surfaceLocalDensity = LocalDensity.current
        val surfaceDensity = surfaceLocalDensity.density
        val surfaceFontScale = surfaceLocalDensity.fontScale
        val statusHeightPxState = remember(playerSurfaceGeneration) { mutableIntStateOf(0) }
        // Native motion interpolates one layout endpoint; do not rebuild this tree every frame.
        val loopClearanceProgress = if (loopA != null) 1f else 0f
        val targetMixerWorkspaceProgress =
            if (presentedSourceMode == PlaybackSourceMode.STEMS) 1f else 0f
        val sourceChangeMotion = StemwiseSemanticMotion.token(
            StemwiseMotionEvent.PLAYER_SOURCE_CHANGE
        )
        val mixerWorkspaceProgressState = animateFloatAsState(
            targetValue = targetMixerWorkspaceProgress,
            animationSpec = tween(
                durationMillis = if (motion.enabled) sourceChangeMotion.durationMs else 120,
                easing = sourceChangeMotion.easing.curve
            ),
            visibilityThreshold = .002f,
            label = "player-mixer-workspace"
        )
        // Native/structural parameters use the stable no-detail route. The animated tool override
        // is applied once from post-layout below, so a target recomposition cannot snap artwork or
        // mixer rows to the endpoint before the shared height tween reaches it.
        val originalPortraitLayout = portraitPlayerLayout(
            availableWidthDp = portraitAvailableWidthDp,
            availableHeightDp = portraitAvailableHeightDp,
            sourceMode = PlaybackSourceMode.ORIGINAL,
            panelExpanded = false,
            reservedDetailHeightDp = 0f,
            fontScale = surfaceFontScale,
            mixerLaneCount = stems.size
        )
        val stemsPortraitLayout = portraitPlayerLayout(
            availableWidthDp = portraitAvailableWidthDp,
            availableHeightDp = portraitAvailableHeightDp,
            sourceMode = PlaybackSourceMode.STEMS,
            panelExpanded = false,
            reservedDetailHeightDp = 0f,
            fontScale = surfaceFontScale,
            mixerLaneCount = stems.size
        )
        val compactTempoFallback = presentedTool == PLAYER_DETAIL_METRONOME &&
            !metronomeAvailable && portraitAvailableHeightDp < 480f
        val detailTargetHeightDp = portraitPlayerDetailViewportHeightDp(
            availableWidthDp = portraitAvailableWidthDp,
            availableHeightDp = portraitAvailableHeightDp,
            sourceMode = presentedSourceMode,
            detail = presentedTool,
            statusHeightDp = statusHeightPxState.intValue / surfaceDensity,
            loopClearanceProgress = loopClearanceProgress,
            fontScale = surfaceFontScale,
            mixerLaneCount = stems.size,
            naturalDetailHeightDp = if (compactTempoFallback) 76f else null,
            minimumDetailHeightDp = if (compactTempoFallback) CompactTuneMetrics.COMPACT_ROW_HEIGHT_DP else 120f
        )
        val targetToolMotionFrame = portraitPlayerToolMotionFrame(
            availableWidthDp = portraitAvailableWidthDp,
            availableHeightDp = portraitAvailableHeightDp,
            density = surfaceDensity,
            detailHeightDp = detailTargetHeightDp,
            // A tool request is one immutable endpoint. Feeding the live source tween here would
            // retarget the native tool clock on every 120 Hz source frame.
            mixerProgress = targetMixerWorkspaceProgress,
            statusHeightDp = statusHeightPxState.intValue / surfaceDensity,
            loopClearanceProgress = loopClearanceProgress,
            fontScale = surfaceFontScale,
            mixerLaneCount = stems.size
        )
        val includeStemsMixer =
            presentedSourceMode == PlaybackSourceMode.STEMS && stems.isNotEmpty()
        val playerSurfaceSemanticsGeometry = remember {
            mutableStateOf<PlayerSurfaceSemanticsGeometry?>(null)
        }
        DisposableEffect(playerSurfaceGeneration, playerSurfaceBridge) {
            playerSurfaceBridge.setMixerScrollListener { geometry ->
                if (geometry != null) playerSurfaceSemanticsGeometry.value = geometry
            }
            onDispose { playerSurfaceBridge.setMixerScrollListener(null) }
        }
        val lowerControlSemanticsInteractive = remember(playerSurfaceGeneration) {
            mutableStateOf(true)
        }
        var sampledRouteSemanticsPresentation by remember(playerSurfaceGeneration) {
            mutableStateOf<PlayerRouteSemanticsPresentation?>(null)
        }
        val surfaceWidthPx = constraints.maxWidth
        val surfaceViewportHeightPx = constraints.maxHeight
        val surfaceHeightPx = maxOf(
            surfaceViewportHeightPx,
            kotlin.math.ceil(playerSurfaceContentHeightDp(
                frame = targetToolMotionFrame,
                density = surfaceDensity,
                includeMixer = includeStemsMixer,
                statusHeightDp = statusHeightPxState.intValue / surfaceDensity,
                loopClearanceProgress = loopClearanceProgress
            ) * surfaceDensity).toInt()
        )
        val externalTransport = surfaceHeightPx > surfaceViewportHeightPx &&
            routeExpansionState.value >= .999f
        val renderedOuterPlayerContentHeightDp = surfaceHeightPx / surfaceDensity
        val surfaceLayoutDirection = LocalLayoutDirection.current
        val toolMotionEpoch = remember(
            playerSurfaceGeneration,
            presentedTool,
            presentedSourceMode,
            loopClearanceProgress,
            surfaceWidthPx,
            surfaceViewportHeightPx,
            surfaceDensity,
            surfaceFontScale,
            stems.isNotEmpty(),
            fullPlayerStatusPacket.kind != FullPlayerStatusKind.NONE
        ) { nextPlayerToolMotionEpoch() }
        val surfacePositionProvider = remember(
            audioPlayback,
            activeSong.id,
            timelineDuration,
            positionState
        ) {
            PlayerSurfacePositionProvider {
                val snapshot = audioPlayback.currentStateSnapshot()
                if (snapshot.songId == activeSong.id) {
                    snapshot.positionMs.coerceIn(0L, timelineDuration)
                } else {
                    positionState.longValue.coerceIn(0L, timelineDuration)
                }
            }
        }
        val stableRouteEndpoint by remember(routeExpansionState) {
            derivedStateOf {
                val progress = routeExpansionState.value
                progress <= .001f || progress >= .999f
            }
        }
        val routeSemanticsPresentation by remember(routeExpansionState) {
            derivedStateOf { playerRouteSemanticsPresentation(routeExpansionState.value) }
        }
        val destinationChromeInteractive by remember(routeExpansionState) {
            derivedStateOf {
                PlayerMotionPolicy.destinationChromeInteractive(routeExpansionState.value)
            }
        }
        val expectedSemanticsMixerLaneCount =
            if (
                !routeSemanticsPresentation.compact &&
                presentedSourceMode == PlaybackSourceMode.STEMS &&
                targetToolMotionFrame.layout.mixerCardHeightDp > .01f
            ) stems.size else 0
        val lowerControlGeometryEndpoint = if (loopA != null) 1 else 0
        var sampledLowerControlGeometryEndpoint by remember(playerSurfaceGeneration) {
            mutableIntStateOf(lowerControlGeometryEndpoint)
        }
        var sampledToolMotionEpoch by remember(playerSurfaceGeneration) {
            mutableLongStateOf(Long.MIN_VALUE)
        }
        LaunchedEffect(
            playerSurfaceGeneration,
            activeSong.id,
            presentedSourceMode,
            displayedStemSetId,
            compactMixerLaneKey,
            expectedSemanticsMixerLaneCount,
            lowerControlGeometryEndpoint,
            toolMotionEpoch,
            presentedTool,
            surfaceWidthPx,
            surfaceHeightPx,
            surfaceFontScale,
            stableRouteEndpoint,
            routeSemanticsPresentation,
            destinationChromeInteractive,
            sourceError,
            audioState.notice.takeIf { isCurrentAudio },
            audioState.error.takeIf { isCurrentAudio }
        ) {
            val lowerControlsMoving =
                sampledLowerControlGeometryEndpoint != lowerControlGeometryEndpoint
            if (lowerControlsMoving) {
                lowerControlSemanticsInteractive.value = false
                if (motion.enabled) {
                    delay(LOOP_CLEARANCE_MOTION_DURATION_MS.toLong())
                }
                withFrameNanos { }
            }
            if (sampledRouteSemanticsPresentation != routeSemanticsPresentation) {
                playerSurfaceSemanticsGeometry.value = null
            }
            // Sample only at the few interaction phases where semantics ownership can change:
            // route start, compact/full threshold, and route end. This keeps exact hit geometry
            // without publishing Snapshot state on every 120 Hz animation frame.
            // A native package bind can trail the Compose stem model by one frame. Withdraw an
            // incompatible projection immediately, then publish only exact lane geometry; this
            // makes source/package handoff atomic to accessibility and instrumentation.
            if (
                playerSurfaceSemanticsGeometry.value?.mixerLaneCount !=
                expectedSemanticsMixerLaneCount
            ) {
                playerSurfaceSemanticsGeometry.value = null
            }
            // Lane topology can become valid on the first Stems frame while the native controls
            // are still travelling. Sampling that intermediate frame leaves TalkBack and test
            // targets permanently behind the pixels. Observe the two real animation owners and
            // publish only their endpoint geometry, followed by one platform-layout frame.
            snapshotFlow {
                kotlin.math.abs(
                    mixerWorkspaceProgressState.value - targetMixerWorkspaceProgress
                ) <= .002f
            }.first { settled -> settled }
            playerSurfaceBridge.awaitToolMotionSettled(
                generation = playerSurfaceGeneration,
                epoch = toolMotionEpoch
            )
            withFrameNanos { }
            var geometry: PlayerSurfaceSemanticsGeometry? = null
            var completedAttempts = 0
            while (geometry == null) {
                when (playerSurfaceSemanticsGeometryRetryMode(completedAttempts)) {
                    PlayerSurfaceSemanticsGeometryRetryMode.NEXT_DISPLAY_FRAME ->
                        withFrameNanos { }
                    PlayerSurfaceSemanticsGeometryRetryMode.LOW_RATE_DELAY ->
                        delay(PLAYER_SURFACE_SEMANTICS_GEOMETRY_RETRY_DELAY_MS)
                }
                // Do not allocate the bounds/lane projection while the native package is stale.
                // This loop writes no Snapshot state until exact geometry is ready and is
                // automatically cancelled when any LaunchedEffect ownership key changes.
                if (playerSurfaceSemanticsGeometryIsReadyToSample(
                        expectedLaneCount = expectedSemanticsMixerLaneCount,
                        nativeLaneCount = playerSurfaceBridge.semanticsMixerLaneCount()
                    )
                ) {
                    geometry = playerSurfaceBridge.semanticsGeometry()?.takeIf { candidate ->
                        candidate.mixerLaneCount == expectedSemanticsMixerLaneCount
                    }
                }
                if (completedAttempts < Int.MAX_VALUE) completedAttempts++
            }
            playerSurfaceSemanticsGeometry.value = geometry
            sampledRouteSemanticsPresentation = routeSemanticsPresentation
            sampledLowerControlGeometryEndpoint = lowerControlGeometryEndpoint
            sampledToolMotionEpoch = toolMotionEpoch
            lowerControlSemanticsInteractive.value = true
        }
        val playerViewportScroll = rememberScrollState()
        val playerBodyScrollEnabled = presentedSourceMode != PlaybackSourceMode.STEMS &&
            presentedTool != PLAYER_DETAIL_METRONOME
        LaunchedEffect(destinationChromeInteractive, playerBodyScrollEnabled) {
            if (!destinationChromeInteractive || !playerBodyScrollEnabled) playerViewportScroll.scrollTo(0)
        }
        PlayerTransportViewport(transport = {
            if (externalTransport) {
                PlayerOverlayTransport(
                    generation = playerSurfaceGeneration,
                    durationMs = timelineDuration,
                    positionState = positionState,
                    transportState = transportAudioState,
                    actionSink = fullPlayerActionSink
                )
            }
        }) { bodyModifier ->
        Box(
            bodyModifier
                .then(if (playerBodyScrollEnabled) Modifier.verticalScroll(
                    playerViewportScroll,
                    enabled = stableRouteEndpoint && destinationChromeInteractive,
                    overscrollEffect = null
                ) else Modifier)
        ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(renderedOuterPlayerContentHeightDp.dp)
        ) {
            PlayerQueueContent(queueProjection) { orderedSongs ->
                PortraitNativePlayerSurface(
                    orderedSongs = orderedSongs,
                    activeSong = lyricsSong,
                    semanticSongIdState = nativeSemanticSongIdState,
                    owner = presentationSnapshot.owner,
                    generation = playerSurfaceGeneration,
                    audioPlayback = audioPlayback,
                    transportAudioState = transportAudioState,
                    sourceMode = sourceMode,
                    settledSourceMode = settledSourceMode,
                    presentedSourceMode = presentedSourceMode,
                    sourceSwitchState = sourceSwitchState,
                    hasCompleteStemSet = hasCompleteStemSet,
                    stemsDownloading = stemsDownloading,
                    stemsPreparationFailed = ownedStemJobState == "FAILED",
                    loopAvailable = loopAvailable,
                    loopPreparation = loopPreparation,
                    stemsAvailabilitySettled = stemAvailability.settled,
                    stems = stems,
                    displayedStemSetId = displayedStemSetId,


                    durationMs = timelineDuration,
                    loopA = loopA,
                    loopB = loopB,
                    waveformPeaks = displayWaveformPeaks,
                    beatEvents = displayBeats,
                    fineWindowState = nativeFineWindowState,
                    fineWaveformBinding = fineWaveformBinding,
                    sections = displaySections,
                    selectedTool = if (showLyricsSheet) {
                        PLAYER_LYRICS_OVERLAY
                    } else {
                        presentedTool
                    },
                    tempo = tempo,
                    pitch = pitch,
                    metronomeEnabled = metronome,
                    metronomeAvailable = metronomeAvailable,
                    metronomeBpm = effectiveMetronomeBpm,
                    metronomeSubdivision = metronomeSubdivision,
                    metronomeNumerator = metronomeNumerator,
                    metronomeDenominator = metronomeDenominator,
                    metronomeVolume = settingsProjection.metronome.volume,
                    statusPacket = fullPlayerStatusPacket,
                    widthPx = surfaceWidthPx,
                    heightPx = surfaceHeightPx,
                    density = surfaceDensity,
                    layoutDirection = surfaceLayoutDirection,
                    routeExpansionState = routeExpansionState,
                    mixerWorkspaceProgressState = mixerWorkspaceProgressState,
                    originalPortraitLayout = originalPortraitLayout,
                    stemsPortraitLayout = stemsPortraitLayout,
                    bridge = playerSurfaceBridge,
                    semanticsGeometryState = playerSurfaceSemanticsGeometry,
                    // Old geometry remains available for layout while native controls travel,
                    // but only the current settled epoch may expose actionable lower targets.
                    lowerControlsInteractive = lowerControlSemanticsInteractive.value &&
                        sampledToolMotionEpoch == toolMotionEpoch,
                    externalTransport = externalTransport,
                    actionSink = fullPlayerActionSink,
                    semanticsSink = playerSurfaceSemanticsSink,
                    positionProvider = surfacePositionProvider,
                    positionState = positionState,

                    artworkPaletteListener = nativeArtworkPaletteListener,
                    ambientMotionAllowed = motion.ambientMotionEnabled && !showLyricsSheet,
                    trimEditor = { nativeSnapshot ->
                        if (showTrim) SongTrimSheet(
                            song = activeSong,
                            sourceDurationMs = sourceDurationMs,
                            snapshot = nativeSnapshot,
                            audioPlayback = audioPlayback,
                            controlLease = controlLease,
                            sourcePeaks = waveformPeaks,
                            readFineWindow = { center ->
                                val binding = latestFineWaveformBinding.value
                                if (binding.sourceMode == PlaybackSourceMode.ORIGINAL) {
                                    analysisRepository.fineWindow5ms(activeSong.id, center,
                                        mediaRevision = activeSong.mediaRevision, sourceSha256 = activeSong.sourceHash.orEmpty())
                                } else audioPlayback.sourceWaveformIdentity()?.takeIf { identity ->
                                    identity.songId == activeSong.id &&
                                        identity.audioRevision == binding.owner.audioRevision.ifBlank { binding.owner.sourceHash.orEmpty() } &&
                                        identity.sourceMode == app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS
                                }?.let { identity ->
                                    audioPlayback.readSourceWaveformWindowAbsolute(identity, center)?.let { window ->
                                        WaveformWindow(window.startMs, window.endMs, window.frameDurationMs, window.peaks)
                                    }
                                }
                            },
                            onDismiss = { showTrim = false }
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        PortraitPlayerToolOverlay(
            ownerGeneration = playerSurfaceGeneration,
            targetTool = presentedTool,
            targetSurfaceTool = playerSurfaceToolForDetail(presentedTool),
            selectionEpoch = toolMotionEpoch,
            layoutDurationMs = if (motion.enabled) {
                StemwiseSemanticMotion.token(
                    StemwiseMotionEvent.PLAYER_LAYOUT_PROFILE_CHANGE
                ).durationMs
            } else {
                0
            },
            motionEnabled = motion.enabled,
            targetFrame = targetToolMotionFrame,
            availableWidthDp = portraitAvailableWidthDp,
            availableHeightDp = portraitAvailableHeightDp,
            sourceMode = presentedSourceMode,
            density = surfaceDensity,
            routeExpansionState = routeExpansionState,
            includeMixer = includeStemsMixer,
            loopClearanceProgress = loopClearanceProgress,
            statusHeightDp = statusHeightPxState.intValue / surfaceDensity,
            bridge = playerSurfaceBridge,
            // Inactive destination chrome owns neither semantics nor the compact route's physical
            // stream. The overlay applies its alpha and semantics from the stable route owner.
            modifier = Modifier.fillMaxSize(),
            onStatusHeightChanged = { heightPx ->
                if (statusHeightPxState.intValue != heightPx) {
                    statusHeightPxState.intValue = heightPx
                }
            },
            statusContent = statusContent
        ) { tool, toolActive ->
                                val localScroll = rememberScrollState()
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (playerDetailOwnsBoundedScroll(tool, surfaceFontScale, detailTargetHeightDp)) {
                                                Modifier.verticalScroll(localScroll)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(
                                            top = if (compactTempoFallback && tool == PLAYER_DETAIL_METRONOME) 0.dp else 12.dp,
                                            start = 12.dp, end = 12.dp
                                        )
                                ) {
                                when (tool) {
                                PLAYER_DETAIL_LOOP -> CompactLoopPanel(
                                    loopBEnabled = remember(
                                        loopA,
                                        loopB,
                                        positionState,
                                        toolActive
                                    ) {
                                        derivedStateOf {
                                            toolActive && loopPointBEnabled(
                                                positionState.longValue,
                                                loopA,
                                                loopB
                                            )
                                        }
                                    }.value,
                                    loopA = loopA,
                                    loopB = loopB,
                                    onSetA = {
                                        setOwnedLoop(
                                            positionState.longValue.coerceAtMost(
                                                (timelineDuration - 100L).coerceAtLeast(0L)
                                            ),
                                            null,
                                            positionState.longValue
                                        )
                                    },
                                    onSetB = {
                                        val pointB = positionState.longValue
                                        setOwnedLoop(loopA, pointB, pointB)
                                    },
                                    onClear = {
                                        setOwnedLoop(null, null, positionState.longValue)
                                    }
                                )
                                PLAYER_DETAIL_TUNE -> key(controlLease?.ownerEpoch) { KeyPanel(
                                    song = activeSong,
                                    pitch = pitch,
                                    onPitch = { updatedPitch ->
                                        applyTuning(requestedTempo, updatedPitch, persist = false)
                                    },
                                    onValueChangeFinished = { audioPlayback.commitTuningSettings(controlLease) }
                                ) }
                                PLAYER_DETAIL_METRONOME -> Column {
                                    if (metronomeAvailable) MetronomePanel(
                                    compactRows = detailTargetHeightDp < playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME),
                                    enabled = metronome,
                                    bpm = effectiveMetronomeBpm,
                                    bpmRange = metronomeBpmRange,
                                    subdivision = metronomeSubdivision,
                                    numerator = metronomeNumerator,
                                    denominator = metronomeDenominator,
                                    volume = settingsProjection.metronome.volume,
                                    positionState = positionState,
                                    beatEvents = displayBeats,
                                    meterSegments = displayMeters,
                                    beatFeedbackEnabled = toolActive,
                                    onToggle = toggleOwnedMetronome,
                                    onBpm = { requestedBpm ->
                                        applyTuning(
                                            (requestedBpm / gridMetronomeBpm)
                                                .coerceIn(MIN_TUNE_TEMPO, MAX_TUNE_TEMPO),
                                            pitch
                                        )
                                    },
                                    onSubdivision = { subdivision ->
                                        setOwnedMetronomeProfile(
                                            subdivision,
                                            metronomeNumerator,
                                            metronomeDenominator
                                        )
                                    },
                                    onVolume = setOwnedMetronomeVolume
                                ) else {
                                    key(controlLease?.ownerEpoch) {
                                        SpeedPanel(tempo = tempo,
                                            onTempo = { applyTuning(it, requestedPitch, persist = false) },
                                            onValueChangeFinished = { audioPlayback.commitTuningSettings(controlLease) },
                                            compactRows = compactTempoFallback)
                                    }
                                    if (!compactTempoFallback || detailTargetHeightDp >= 76f) Text(
                                        if (compactTempoFallback) "No verified beats" else METRONOME_TIMING_UNAVAILABLE_MESSAGE,
                                        color = TextSecondary,
                                        fontSize = if (compactTempoFallback) 14.sp else 16.sp,
                                        lineHeight = if (compactTempoFallback) 14.sp else 24.sp,
                                        maxLines = if (compactTempoFallback) 1 else Int.MAX_VALUE,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (compactTempoFallback) Modifier else Modifier.padding(vertical = 12.dp))
                                }
                                }
                                else -> Spacer(Modifier.height(0.dp))
                                }
                                }
                        }
            }
            }
        }
        }
        }
    }

    AnimatedVisibility(
        visible = skipFeedback != null,
        enter = fadeIn(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)),
        exit = fadeOut(motion.semanticSpec(StemwiseMotionEvent.STATUS_CHANGE)),
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 42.dp),
            contentAlignment = if (skipFeedback?.startsWith("+") == true) {
                Alignment.CenterEnd
            } else {
                Alignment.CenterStart
            }
        ) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(StemwisePalette.busyOverlayFill)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    skipFeedback.orEmpty(),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    }

    var resetMixTarget by remember(activeSong.id, activeSong.mediaRevision, displayedStemSetId) {
        mutableStateOf<OwnedPlayerActionSink?>(null)
    }
    resetMixTarget?.let { target ->
        StemwiseAlertDialog(
            onDismissRequest = { resetMixTarget = null },
            title = { Text("Reset mix?") },
            text = { Text("Restore all part volumes to 100% and clear Mute and Solo for ${activeSong.title}. Speed, key, loop, and chosen parts stay as they are.") },
            confirmButton = {
                StemwiseTextButton(onClick = {
                    closeThen {
                        resetMixTarget = null
                        if (target === fullPlayerActionSink) target.resetMix(playerSurfaceGeneration)
                        else onMessage("The song or parts changed. Open Reset mix again.")
                    }
                }) { Text("Reset mix") }
            },
            dismissButton = { StemwiseTextButton(onClick = { dismiss() }) { Text("Cancel") } },
            containerColor = SurfaceRaised
        )
    }
    if (showStemActions) {
        StemwiseAlertDialog(
            onDismissRequest = { showStemActions = false },
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_song_parts)) },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StemwiseButton(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                        elevation = null,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
                        onClick = {
                        closeThen {
                            showStemActions = false
                            stemSourceLoadAttempt++
                            stemDownloadRequest = activeSong.id
                        }
                    }) { Text("Choose parts") }
                    StemwiseButton(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                        onClick = {
                        closeThen {
                            showStemActions = false
                            resetMixTarget = fullPlayerActionSink
                        }
                    }) { Text("Reset mix") }
                    StemwiseTextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        onClick = { dismiss() }) {
                        Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel))
                    }
                }
            },
            containerColor = SurfaceRaised
        )
    }

    playerDeletionTarget(stemDownloadRequest, songs)?.let { targetSong ->
        val loadFinished = stemSourceLoadFinishedFor == (targetSong.id to stemSourceLoadAttempt)
        SourceSelectionDialog(
            songId = targetSong.id,
            songTitle = targetSong.title,
            initialSelection = if (displayedStemAssets.isEmpty()) {
                app.stemwise.model.StemSourceCatalogue.defaultSelection
            } else displayedStemAssets.mapNotNull { it.normalizedCapability }.filterNot { it == "remaining" },
            availableSources = availableStemSources,
            loading = !loadFinished,
            error = stemSourceLoadError.takeIf { loadFinished },
            onRetry = { stemSourceLoadAttempt++ },
            onConfirm = { selectedIds ->
                if (stemRequestPending) {
                    false
                } else {
                    stemRequestPending = true
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            onRequestStems(targetSong, selectedIds)
                                .onSuccess { onMessage("Preparing parts. Open Activity for progress.") }
                                .onFailure {
                                    onMessage(it.message ?: "Could not start preparing parts")
                                }
                        } finally {
                            stemRequestPending = false
                        }
                    }
                    true
                }
            },
            onDismiss = { stemDownloadRequest = null }
        )
    }

    if (showSongInfo) {
        SongInfoDialog(
            song = activeSong,
            analysisSummary = analysisBundle.summary,
            onDismiss = { showSongInfo = false },
            onCopyVideoId = {
                copyYouTubeVideoId(context, activeSong)
            },
            onTrim = {
                showSongInfo = false
                if (controlLease != null && sourceDurationMs > 0L) showTrim = true
                else onMessage("Wait for this song to finish loading before trimming.")
            },
            onDeleteSong = {
                showSongInfo = false
                deletionRequest = activeSong.id
            },
            onDeleteStems = {
                showSongInfo = false
                stemDeletionRequest = activeSong.id
            }
        )
    }

    playerDeletionTarget(stemDeletionRequest, songs)?.let { targetSong ->
        StemwiseAlertDialog(
            onDismissRequest = { stemDeletionRequest = null },
            dismissEnabled = false,
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_this_song_s_stems)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_this_removes_only_the_prepared_song_parts)
                )
            },
            confirmButton = {
                StemwisePermanentDeleteActions(
                    onCancel = { dismiss() },
                    onDelete = {
                        closeAfterStarting(
                            startAction = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    onDeleteStems(targetSong)
                                        .onSuccess { outcome ->
                                            onMessage(stemDeletionSuccessMessage(outcome))
                                            onBack()
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "Could not delete the stems")
                                        }
                                }
                                true
                            },
                            afterClose = { stemDeletionRequest = null }
                        )
                    }
                )
            },
            containerColor = SurfaceRaised
        )
    }

    val deletionTarget = playerDeletionTarget(deletionRequest, songs)
    LaunchedEffect(deletionRequest, deletionTarget) {
        if (deletionRequest != null && deletionTarget == null) {
            deletionRequest = null
            onMessage("This song is no longer available")
        }
    }
    deletionTarget?.let { targetSong ->
        StemwiseAlertDialog(
            // Destructive confirmation owns its own lifetime: outside-tap and Back never decide.
            onDismissRequest = { deletionRequest = null },
            dismissEnabled = false,
            title = { Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_this_song_permanently)) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_this_permanently_removes_the_original_all_stems)
                )
            },
            confirmButton = {
                StemwisePermanentDeleteActions(
                    onCancel = { dismiss() },
                    onDelete = {
                        closeAfterStarting(
                            startAction = {
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    onDeleteSong(targetSong)
                                        .onSuccess { outcome ->
                                            onMessage(permanentDeletionSuccessMessage(outcome))
                                            onBack()
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "Could not delete these files")
                                        }
                                }
                                true
                            },
                            afterClose = {
                                deletionRequest = null
                            }
                        )
                    }
                )
            },
            containerColor = SurfaceRaised
        )
    }


}

internal fun permanentDeletionSuccessMessage(outcome: PermanentDeletionOutcome): String =
    if (outcome.projectionRefreshPending) {
        "Song deleted · Library refresh pending"
    } else {
        "Song deleted permanently"
    }

internal fun stemDeletionSuccessMessage(outcome: PermanentDeletionOutcome): String =
    if (outcome.projectionRefreshPending) {
        "Stems deleted · Library refresh pending"
    } else {
        "Stems deleted"
    }

internal fun playerDeletionTarget(
    requestedSongId: String?,
    availableSongs: List<Song>
): Song? = requestedSongId?.let { id -> availableSongs.firstOrNull { it.id == id } }




@Composable
internal fun LoopPreparationNotice(state: app.stemwise.playback.LoopPreparationState, onRetry: () -> Boolean) {
    if (state.phase == app.stemwise.playback.LoopPreparationPhase.REJECTED ||
        state.phase == app.stemwise.playback.LoopPreparationPhase.FAILED
    ) {
        Text(loopPreparationDescription(state), color = TextSecondary, fontSize = 13.sp)
        if (state.retryable) StemwiseTextButton(onClick = { onRetry() }) { Text("Retry loop preparation") }
    }
}

@Composable
internal fun SongInfoDialog(
    song: Song,
    analysisSummary: ImportedAnalysisSummary? = null,
    hydration: LibrarySongHydration? = null,
    onRetryDetail: () -> Unit = {},
    onRetrySummary: () -> Unit = {},
    onDismiss: () -> Unit,
    onCopyVideoId: () -> Unit,
    onDeleteSong: () -> Unit,
    onDeleteStems: () -> Unit = {},
    onTrim: (() -> Unit)? = null
) {
    val motion = LocalStemwiseMotion.current
    val largeText = LocalDensity.current.fontScale >= 1.3f
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .92f
    var copyConfirmed by remember(song.id, song.videoId) { mutableStateOf(false) }
    val closeFocusRequester = remember(song.id) { FocusRequester() }
    LaunchedEffect(copyConfirmed) {
        if (copyConfirmed) {
            delay(1_800L)
            copyConfirmed = false
        }
    }
    val metadataRows = remember(song, analysisSummary) {
        songInfoMetadataRows(song, analysisSummary)
    }

    StemwiseAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = maximumHeight),
        title = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .semantics(mergeDescendants = true) {
                        heading()
                        paneTitle = "Song details"
                        contentDescription = buildString {
                            append("Song details. ")
                            append(song.title)
                            append(" by ")
                            append(song.artist.ifBlank { "Unknown artist" })
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.padding(8.dp)) {
                    Artwork(
                        song = song,
                        size = if (largeText) 64.dp else 80.dp,
                        phaseOrdinal = 9,
                        cornerRadius = 8.dp
                    )
                }
                Column(
                    Modifier.weight(1f).padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        song.title,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (largeText) 3 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist.ifBlank { "Unknown artist" },
                        color = StemwisePalette.playerAccentLabel,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        songInfoHeroSummary(song),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maximumHeight * .48f)
                    .animateContentSize(
                        animationSpec = motion.semanticSpec(
                            StemwiseMotionEvent.PANEL_CONTENT_REFLOW
                        )
                    ),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_song_details),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                SongDetailDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    SongDetailRow(label = "Title", value = song.title)
                    SongDetailRow(label = "Artist", value = song.artist.ifBlank { "Unknown artist" })
                    hydration?.let { state ->
                        for ((phase, label, retry) in listOf(
                            Triple(state.detailPhase, "details", onRetryDetail),
                            Triple(state.summaryPhase, "analysis", onRetrySummary)
                        )) {
                            when (phase) {
                                SongInfoReadPhase.LOADING -> Text("Loading $label…", color = TextSecondary, fontSize = 13.sp)
                                SongInfoReadPhase.FAILED -> {
                                    Text("Could not read $label", color = Coral, fontSize = 13.sp)
                                    StemwiseTextButton(onClick = retry) { Text("Retry $label") }
                                }
                                SongInfoReadPhase.READY -> Unit
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = metadataRows,
                        transitionSpec = {
                            fadeIn(
                                motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT)
                            ).togetherWith(
                                fadeOut(
                                    motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT)
                                )
                            )
                        },
                        label = "song-detail-hydration"
                    ) { rows ->
                        Column {
                            rows.forEach { (label, value) ->
                                SongDetailRow(label = label, value = value)
                            }
                        }
                    }
                    SongDetailRow(
                        label = "YouTube video ID",
                        value = song.videoId.ifBlank { "Not available" },
                        trailing = {
                            StemwiseIconButton(
                                enabled = song.videoId.isNotBlank(),
                                onClick = {
                                    onCopyVideoId()
                                    copyConfirmed = true
                                },
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Surface)
                                    .border(1.dp, Outline, RoundedCornerShape(14.dp))
                                    .semantics {
                                        stateDescription = if (copyConfirmed) "Copied" else "Ready to copy"
                                        if (copyConfirmed) liveRegion = LiveRegionMode.Polite
                                    }
                            ) {
                                Icon(
                                    if (copyConfirmed) StemwiseIcons.Copied else StemwiseIcons.Copy,
                                    contentDescription = "Copy YouTube video ID",
                                    tint = PrimaryPurple,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            // Dialog owns a separate focus root. The outer screen's composition can finish
            // before that window or its first action is ready to receive keyboard focus.
            val windowFocused = LocalWindowInfo.current.isWindowFocused
            val keyboardInput = LocalInputModeManager.current.inputMode == InputMode.Keyboard
            var closePlaced by remember(song.id) { mutableStateOf(false) }
            var initialFocusEstablished by remember(song.id) { mutableStateOf(false) }
            LaunchedEffect(song.id, windowFocused, keyboardInput, closePlaced) {
                if (!initialFocusEstablished && windowFocused && keyboardInput && closePlaced) {
                    initialFocusEstablished = closeFocusRequester.requestFocus()
                }
            }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    onTrim?.let { trim ->
                        StemwiseButton(
                            onClick = { closeThen(trim) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = TextPrimary),
                            elevation = null,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
                        ) { Text("Trim song") }
                    }
                    val closeButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                        StemwiseButton(
                            onClick = { dismiss() },
                            modifier = buttonModifier
                                .heightIn(min = 52.dp)
                                .focusRequester(closeFocusRequester)
                                .onGloballyPositioned { closePlaced = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Surface,
                                contentColor = TextPrimary
                            ),
                            elevation = null,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
                        ) {
                            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_close), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    val deleteStemsButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                        StemwiseButton(
                            enabled = song.hasStems || song.hasSourceParts,
                            onClick = { closeThen(onDeleteStems) },
                            modifier = buttonModifier.heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Surface,
                                contentColor = Coral
                            ),
                            elevation = null,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Coral)
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_stems_only),
                                maxLines = 2,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    closeButton(Modifier.fillMaxWidth())
                    deleteStemsButton(Modifier.fillMaxWidth())
                    StemwiseButton(
                        onClick = { closeThen(onDeleteSong) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StemwisePalette.destructiveActionFill,
                            contentColor = Coral
                        ),
                        elevation = null,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Coral)
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_song_with_everything),
                            maxLines = 2,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
        },
        containerColor = SurfaceRaised,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary
    )
}

@Composable
private fun SongDetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                value,
                color = valueColor,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        trailing?.invoke()
    }
}

internal fun songInfoMetadataRows(
    song: Song,
    analysis: ImportedAnalysisSummary? = null
): List<Pair<String, String>> = buildList {
    if (song.hasStems) add("Stem timing" to STEMS_TIMING_UNVERIFIED_LABEL)
    song.album.takeIf(String::isNotBlank)?.let { add("Album" to it) }
    song.year.takeIf(String::isNotBlank)?.let { add("Year" to it) }
    song.bpm?.takeIf(Float::isFinite)?.let { bpm ->
        val rounded = bpm.roundToInt()
        add(
            "Tempo" to if (kotlin.math.abs(bpm - rounded) < .05f) "$rounded BPM"
            else String.format(Locale.ROOT, "%.1f BPM", bpm)
        )
    }
    val keyStatus = analysis?.keyStatus ?: song.keyStatus
    song.key?.takeIf(String::isNotBlank)?.let { add("Key" to qualifiedAnalysisValue(it, keyStatus)) }
    (analysis?.keyReason ?: song.keyReason)?.takeIf { keyStatus == "uncertain" && it.isNotBlank() }
        ?.let { add("Key uncertainty" to it) }
    song.meter?.takeIf(String::isNotBlank)?.let { add("Meter" to it) }
    analysis?.tuningFrequencyHz?.takeIf(Double::isFinite)?.let { frequency ->
        val cents = analysis.tuningCentsFromA440?.takeIf(Double::isFinite)
        val value = buildString {
            append(String.format(Locale.ROOT, "%.2f Hz", frequency))
            cents?.let { append(String.format(Locale.ROOT, " · %+.2f cents from A440", it)) }
        }
        add("Tuning" to qualifiedAnalysisValue(value, analysis.tuningStatus))
        analysis.tuningReason?.takeIf { analysis.tuningStatus == "uncertain" && it.isNotBlank() }
            ?.let { add("Tuning uncertainty" to it) }
    }
    analysis?.integratedLufs?.takeIf(Double::isFinite)?.let {
        add("Loudness" to String.format(Locale.ROOT, "%.1f LUFS", it))
    }
    analysis?.loudnessRangeLu?.takeIf(Double::isFinite)?.let {
        add("Loudness range" to String.format(Locale.ROOT, "%.1f LU", it))
    }
    analysis?.truePeakDbtp?.takeIf(Double::isFinite)?.let {
        add("True peak" to String.format(Locale.ROOT, "%.1f dBTP", it))
    }
    analysis?.sectionCount?.takeIf { it > 0 }?.let { add("Structure" to "$it sections") }
    song.additionalInfo.forEach { (label, value) ->
        val normalizedLabel = normalizeSongInfoKey(label)
        val duplicateYear = song.year.isNotBlank() &&
            (normalizedLabel == "year" || normalizedLabel == "releaseyear")
        if (!duplicateYear && label.isNotBlank() && value.isNotBlank()) add(label to value)
    }
}

internal fun songInfoHeroSummary(song: Song): String = buildString {
    append(song.durationMs.asClock())
    append(" · Original")
}

@Composable
private fun SongDetailDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
}

internal fun displayLanguageName(language: String?): String? {
    val raw = language?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (raw.length > 8 || raw.any { it.isWhitespace() }) {
        return raw.replaceFirstChar { it.titlecase() }
    }
    val locale = Locale.forLanguageTag(raw.replace('_', '-'))
    val displayName = locale.getDisplayLanguage(Locale.getDefault())
    return displayName
        .takeIf { it.isNotBlank() && !it.equals(raw, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase() }
        ?: raw.uppercase(Locale.ROOT)
}

internal fun normalizeSongInfoKey(key: String): String = key
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

@Composable
internal fun PlaybackStatusCard(status: FullPlayerStatusPacket) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = StemwiseIcons.Downloading,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = PrimaryPurple
                )
                Text(
                    status.primaryText.orEmpty(),
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    fontWeight = FontWeight.Bold
                )
                if (status.kind == FullPlayerStatusKind.PROGRESS) {
                    Text(
                        "${((status.progress ?: 0f) * 100f).roundToInt()}%",
                        color = PrimaryPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                }
            }
            Text(status.secondaryText.orEmpty(), color = TextSecondary, fontSize = 12.sp)
            if (status.kind == FullPlayerStatusKind.PROGRESS) {
                ProgressTrack(status.progress ?: 0f, PrimaryPurple, 4.dp)
            }
    }
}

@Composable
internal fun PlaybackErrorCard(status: FullPlayerStatusPacket) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
            }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            Icon(
                imageVector = StemwiseIcons.Error,
                contentDescription = null,
                tint = Coral,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(status.primaryText.orEmpty(), fontWeight = FontWeight.Bold)
                Text(status.secondaryText.orEmpty(), color = TextSecondary, fontSize = 12.sp)
            }
    }
}

internal fun transposedKey(originalKey: String?, shift: Int): String {
    if (originalKey.isNullOrBlank()) return "—"
    if (shift == 0) return originalKey
    val normalizedKey = originalKey
        .replace("♯", "#")
        .replace("♭", "b")
    val keyParts = normalizedKey.trim().split(Regex("\\s+"), limit = 2)
    val pitchClass = app.stemwise.model.tonicPitchClass(keyParts.first()) ?: return originalKey
    val names = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    val target = names[((pitchClass + shift % 12) % 12 + 12) % 12]
    val mode = when {
        normalizedKey.contains("minor", ignoreCase = true) -> "minor"
        normalizedKey.contains("major", ignoreCase = true) -> "major"
        else -> ""
    }
    return "$target $mode".trim()
}

internal fun effectiveTimelineDuration(
    isCurrentAudio: Boolean,
    decodedDurationMs: Long,
    metadataDurationMs: Long
): Long = when {
    isCurrentAudio && decodedDurationMs > 0L -> decodedDurationMs
    metadataDurationMs > 0L -> metadataDurationMs
    else -> 0L
}
