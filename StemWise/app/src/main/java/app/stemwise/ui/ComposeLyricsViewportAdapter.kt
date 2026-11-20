package app.stemwise.ui

import android.os.Debug
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.BuildConfig
import app.stemwise.model.TimedLyricLine
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

internal class LyricsViewportController(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val config: LyricsFollowConfig = LyricsFollowConfig(),
    private val diagnostics: LyricsFollowDiagnostics? = if (BuildConfig.DEBUG) LyricsFollowDiagnostics() else null
) {
    private val engine = LyricsViewportIntentEngine(config)
    private val synchronizer = LyricPlaybackSynchronizer()
    private val mutableMode = mutableStateOf(engine.state.mode)
    private val mutableDebugState = if (BuildConfig.DEBUG) mutableStateOf(engine.state) else null
    private var debugOverlayAttached = false
    private var sequence = 0L
    private var trackEpoch = 0L
    private var layoutEpoch = 0L
    private var trackKey: String? = null
    private var currentLines: List<TimedLyricLine> = emptyList()
    private var currentGestureId: Long? = null
    private var nextGestureId = 0L
    private var lastRawSnapshot: LyricsViewportSnapshot? = null
    private var settleProbe: Job? = null
    private var candidateProbe: Job? = null
    private var candidateProbeId: Long? = null
    private var touchExplorationEnabled = false
    private var pendingLyricTapIndex: Int? = null

    private val actuator = LyricsScrollActuator(
        listState = listState,
        scope = scope,
        config = config,
        snapshotProvider = { activeIndex -> captureSnapshot(activeIndex) },
        onStarted = { token, request ->
            dispatch(LyricsViewportEvent.AutoScrollStarted(token, request, meta()))
        },
        onCompleted = { token, request ->
            onViewportSnapshot(captureSnapshot())
            dispatch(LyricsViewportEvent.AutoScrollCompleted(token, request, meta()))
        },
        onCancelled = { token, reason ->
            dispatch(LyricsViewportEvent.AutoScrollCancelled(token, reason, meta()))
        }
    )

    val mode: State<LyricsViewportMode> = mutableMode
    val debugState: State<LyricsViewportEngineState> get() = checkNotNull(mutableDebugState)

    fun setDebugOverlayAttached(attached: Boolean) {
        if (!BuildConfig.DEBUG) return
        debugOverlayAttached = attached
        diagnostics?.setEnabled(attached)
        if (attached) mutableDebugState?.value = engine.state
    }

    fun updateEnvironment(
        touchExploration: Boolean,
        animationsEnabled: Boolean,
        lifecycleStarted: Boolean
    ) {
        actuator.animationsEnabled = animationsEnabled
        if (touchExplorationEnabled != touchExploration) {
            touchExplorationEnabled = touchExploration
            dispatch(
                LyricsViewportEvent.AccessibilityChanged(
                    touchExplorationEnabled = touchExploration,
                    meta = meta()
                )
            )
        }
        if (engine.state.suppression.lifecycleAtLeastStarted != lifecycleStarted) {
            if (!lifecycleStarted) actuator.cancel(LyricsAutoScrollCancelReason.LifecycleStopped)
            dispatch(LyricsViewportEvent.LifecycleChanged(lifecycleStarted, meta()))
        }
    }

    fun onLyricsLoaded(
        newTrackKey: String,
        lines: List<TimedLyricLine>,
        positionMs: Long
    ) {
        val changedTrack = trackKey != null && trackKey != newTrackKey
        val firstTrack = trackKey == null
        val contentChanged = !changedTrack && !firstTrack && currentLines != lines
        if (changedTrack) {
            trackEpoch += 1L
            actuator.cancel(LyricsAutoScrollCancelReason.TrackChanged)
            dispatch(LyricsViewportEvent.TrackChanged(meta()))
        } else if (contentChanged) {
            onLayoutChanged()
        }
        trackKey = newTrackKey
        currentLines = lines
        val update = synchronizer.replaceTrack(newTrackKey, lines, positionMs)
        dispatch(
            LyricsViewportEvent.ActiveIndexChanged(
                update.previousIndex,
                update.newIndex,
                if (firstTrack) LyricsActiveIndexChangeCause.LyricsLoaded else update.cause,
                meta()
            )
        )
    }

    fun onPlaybackPosition(positionMs: Long) {
        synchronizer.updatePosition(positionMs)?.let { update ->
            dispatch(
                LyricsViewportEvent.ActiveIndexChanged(
                    update.previousIndex,
                    update.newIndex,
                    update.cause,
                    meta()
                )
            )
            onViewportSnapshot(captureSnapshot(update.newIndex))
        }
    }

    fun onLyricTapRequested(index: Int) {
        if (index !in currentLines.indices) return
        if (engine.state.pointerDown || engine.state.directDragActive) {
            pendingLyricTapIndex = index
            return
        }
        dispatchLyricTap(index)
    }

    private fun dispatchLyricTap(index: Int) {
        dispatch(
            LyricsViewportEvent.ContextReposition(
                activeIndex = index,
                reason = LyricsContextRepositionReason.LyricTapSeek,
                meta = meta()
            )
        )
    }

    fun onPointerDown(contactId: Long) {
        actuator.cancel(LyricsAutoScrollCancelReason.PointerDown)
        cancelProbes()
        dispatch(LyricsViewportEvent.PointerDown(contactId, meta()))
    }

    fun onPointerUpOrCancel(contactId: Long) {
        dispatch(LyricsViewportEvent.PointerUpOrCancel(contactId, meta()))
        pendingLyricTapIndex?.let {
            pendingLyricTapIndex = null
            dispatchLyricTap(it)
        }
        scheduleSettleProbe()
    }

    fun onListInteraction(interaction: Any) {
        when (interaction) {
            is DragInteraction.Start -> startGesture(pointerSource())
            is DragInteraction.Stop,
            is DragInteraction.Cancel -> endGesture()
        }
    }

    private var discreteInputEnd: Job? = null

    fun onUserScrollIntent(source: LyricsUserInputSource) {
        startGesture(source)
        if (source in setOf(LyricsUserInputSource.Keyboard, LyricsUserInputSource.DPad,
                LyricsUserInputSource.MouseWheel)) {
            discreteInputEnd?.cancel()
            discreteInputEnd = scope.launch {
                delay(180L)
                onDiscreteInputEnded()
            }
        }
    }

    fun onDiscreteInputEnded() {
        discreteInputEnd?.cancel()
        discreteInputEnd = null
        if (!engine.state.pointerDown) endGesture()
    }

    fun onUserConsumedScrollDelta(deltaPx: Float, source: LyricsUserInputSource) {
        if (abs(deltaPx) <= 0f) return
        val gestureId = currentGestureId ?: startGesture(source)
        dispatch(
            LyricsViewportEvent.UserScrollDelta(
                gestureId = gestureId,
                source = source,
                consumedDeltaPx = deltaPx,
                meta = meta()
            )
        )
    }

    fun onViewportSnapshot(raw: LyricsViewportSnapshot) {
        val snapshot = raw.copy(
            sequence = sequence + 1L,
            capturedAtNanos = System.nanoTime(),
            layoutEpoch = layoutEpoch
        )
        val previous = lastRawSnapshot
        val activeToken = actuator.activeToken
        var scrollEndOwner: LyricsMotionOwner? = null
        var unownedMovementEnded = false
        if (previous?.isScrollInProgress != snapshot.isScrollInProgress) {
            val owner = when {
                activeToken != null -> LyricsMotionOwner.App
                snapshot.isScrollInProgress -> LyricsMotionOwner.User
                engine.state.motionOwner == LyricsMotionOwner.User -> LyricsMotionOwner.User
                else -> LyricsMotionOwner.None
            }
            if (snapshot.isScrollInProgress && owner == LyricsMotionOwner.User) {
                startGesture(pointerSource())
            }
            if (snapshot.isScrollInProgress) {
                dispatch(LyricsViewportEvent.ScrollProgressChanged(true, owner, meta()))
            } else {
                scrollEndOwner = owner
            }
        }
        if (previous != null && !snapshot.isScrollInProgress && activeToken == null &&
            !engine.state.pointerDown && engine.state.motionOwner == LyricsMotionOwner.None &&
            previous.layoutEpoch == snapshot.layoutEpoch && geometryMoved(previous, snapshot)
        ) {
            val source = LyricsUserInputSource.UnknownUserInput
            startGesture(source)
            val before = previous.anchor?.itemFocusOffsetFromPreferredPx
            val after = snapshot.anchor?.itemFocusOffsetFromPreferredPx
            if (before != null && after != null) onUserConsumedScrollDelta(after - before, source)
            unownedMovementEnded = true
        }
        lastRawSnapshot = snapshot
        dispatch(LyricsViewportEvent.GeometryChanged(snapshot, meta()))
        scrollEndOwner?.let { owner ->
            dispatch(LyricsViewportEvent.ScrollProgressChanged(false, owner, meta()))
            if (owner == LyricsMotionOwner.User) endGesture()
        }
        if (unownedMovementEnded) endGesture()
        scheduleCandidateProbeIfNeeded()
    }

    fun onLayoutChanged() {
        layoutEpoch += 1L
        lastRawSnapshot = null
        cancelProbes()
        dispatch(LyricsViewportEvent.LayoutEpochChanged(meta()))
    }

    fun onScreenVisibilityChanged(visible: Boolean) {
        if (!visible) {
            actuator.cancel(LyricsAutoScrollCancelReason.ScreenHidden)
            onDiscreteInputEnded()
        }
        dispatch(LyricsViewportEvent.ScreenVisibilityChanged(visible, meta()))
    }

    fun onAccessibilityFollowRequested(): Boolean {
        val activeIndex = engine.state.activeIndex
        if (activeIndex < 0) return false
        dispatch(
            LyricsViewportEvent.ContextReposition(
                activeIndex,
                LyricsContextRepositionReason.AccessibilityFollow,
                meta()
            )
        )
        return true
    }

    fun captureSnapshot(activeIndex: Int = engine.state.activeIndex): LyricsViewportSnapshot =
        buildLyricsViewportSnapshot(
            listState = listState,
            activeIndex = activeIndex,
            layoutEpoch = layoutEpoch,
            config = config
        )

    fun trace(): List<LyricsFollowTraceEntry> = diagnostics?.snapshot().orEmpty()

    private fun startGesture(source: LyricsUserInputSource): Long {
        if (currentGestureId != null && engine.state.directDragActive) return requireNotNull(currentGestureId)
        actuator.cancel(LyricsAutoScrollCancelReason.UserGestureStarted)
        cancelProbes()
        val id = ++nextGestureId
        currentGestureId = id
        dispatch(LyricsViewportEvent.DirectDragStarted(id, source, meta()))
        return id
    }

    private fun endGesture() {
        val id = currentGestureId
        currentGestureId = null
        if (id != null) dispatch(LyricsViewportEvent.DirectDragEnded(id, meta()))
        scheduleSettleProbe()
    }

    private fun scheduleSettleProbe() {
        settleProbe?.cancel()
        if (engine.state.mode !is LyricsViewportMode.Detached || engine.state.pointerDown ||
            engine.state.directDragActive || engine.state.scrollInProgress) return
        settleProbe = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Terminal ownership events clear stability after the last layout snapshot.
            onViewportSnapshot(captureSnapshot())
            delay(config.settleDwellMs)
            onViewportSnapshot(captureSnapshot())
        }
    }

    private fun scheduleCandidateProbeIfNeeded() {
        val candidate = engine.state.candidate ?: run {
            candidateProbe?.cancel()
            candidateProbe = null
            candidateProbeId = null
            return
        }
        if (candidateProbe?.isActive == true && candidateProbeId == candidate.id) return
        candidateProbe?.cancel()
        candidateProbeId = candidate.id
        val probe = scope.launch(start = CoroutineStart.LAZY) {
            val remainingNanos = (config.captureDwellMs * 1_000_000L -
                (System.nanoTime() - candidate.createdAtNanos)).coerceAtLeast(0L)
            delay(if (remainingNanos > 0L) 1L + (remainingNanos - 1L) / 1_000_000L else 0L)
            if (candidateProbe === coroutineContext[Job] && engine.state.candidate?.id == candidate.id) {
                // Handler time can wake before the engine's monotonic dwell deadline.
                if (System.nanoTime() - candidate.createdAtNanos < config.captureDwellMs * 1_000_000L) {
                    candidateProbe = null
                    candidateProbeId = null
                }
                onViewportSnapshot(captureSnapshot())
            }
        }
        candidateProbe = probe
        probe.start()
    }

    private fun cancelProbes() {
        settleProbe?.cancel()
        candidateProbe?.cancel()
        settleProbe = null
        candidateProbe = null
        candidateProbeId = null
    }

    private fun pointerSource(): LyricsUserInputSource =
        if (engine.state.pointerDown) LyricsUserInputSource.Touch
        else LyricsUserInputSource.UnknownUserInput

    private fun dispatch(event: LyricsViewportEvent) {
        val before = engine.state
        val reduction = engine.reduce(event)
        diagnostics?.record(event, before, reduction)
        if (mutableMode.value != reduction.state.mode) mutableMode.value = reduction.state.mode
        if (debugOverlayAttached) mutableDebugState?.value = reduction.state
        reduction.effects.forEach(::execute)
        if (BuildConfig.DEBUG) assertLyricsViewportInvariants(reduction.state)
    }

    private fun execute(effect: LyricsViewportEffect) {
        when (effect) {
            is LyricsViewportEffect.CancelOwnedScroll -> actuator.cancel(effect.reason)
            is LyricsViewportEffect.FollowBy -> actuator.followBy(effect.deltaPx, effect.soft)
            is LyricsViewportEffect.ContextPosition -> actuator.contextPosition(
                effect.activeIndex,
                effect.reason
            )
        }
    }

    private fun meta() = LyricsEventMeta(
        sequence = ++sequence,
        monotonicTimeNanos = System.nanoTime(),
        trackEpoch = trackEpoch,
        layoutEpoch = layoutEpoch
    )

    private fun geometryMoved(
        before: LyricsViewportSnapshot,
        after: LyricsViewportSnapshot
    ): Boolean {
        // Companion enter/exit changes row heights without a scroll gesture. Lazy-list anchor
        // compensation during that reflow must not create a manual ownership latch.
        if (before.viewportHeightPx != after.viewportHeightPx ||
            before.totalItemsCount != after.totalItemsCount ||
            before.visibleItems.any { prior ->
                after.visibleItems.firstOrNull { it.key == prior.key && it.index == prior.index }
                    ?.let { it.sizePx != prior.sizePx } == true
            }
        ) return false
        val first = before.anchor ?: return false
        val second = after.anchor ?: return false
        val sameKey = first.itemKey?.let { it == second.itemKey }
            ?: (first.itemIndex == second.itemIndex)
        return !sameKey || abs(
            first.itemFocusOffsetFromPreferredPx - second.itemFocusOffsetFromPreferredPx
        ) > config.geometryNoisePx
    }

}

private class LyricsScrollActuator(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val config: LyricsFollowConfig,
    private val snapshotProvider: (Int) -> LyricsViewportSnapshot,
    private val onStarted: (Long, LyricsAutoScrollRequest) -> Unit,
    private val onCompleted: (Long, LyricsAutoScrollRequest) -> Unit,
    private val onCancelled: (Long, LyricsAutoScrollCancelReason) -> Unit
) {
    private data class ActiveOperation(
        val token: Long,
        val request: LyricsAutoScrollRequest,
        val job: Job
    )

    private var generation = 0L
    private var active: ActiveOperation? = null
    var animationsEnabled: Boolean = true
    val activeToken: Long? get() = active?.token

    fun cancel(reason: LyricsAutoScrollCancelReason) {
        val operation = active ?: return
        active = null
        operation.job.cancel()
        onCancelled(operation.token, reason)
    }

    fun followBy(deltaPx: Float, soft: Boolean) {
        if (abs(deltaPx) <= config.geometryNoisePx) return
        val request = LyricsAutoScrollRequest.LocalFollow(deltaPx, soft)
        launch(request) {
            listState.scroll {
                if (!animationsEnabled) {
                    scrollBy(deltaPx)
                    return@scroll
                }
                var previous = 0f
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = followDurationMs(
                            deltaPx,
                            snapshotProvider(-1).viewportHeightPx
                        )
                    )
                ) { progress, _ ->
                    val frame = progress - previous
                    scrollBy(deltaPx * frame)
                    previous = progress
                }
            }
        }
    }

    fun contextPosition(activeIndex: Int, reason: LyricsContextRepositionReason) {
        if (activeIndex < 0) return
        val request = LyricsAutoScrollRequest.ContextPosition(activeIndex, reason)
        launch(request) {
            if (animationsEnabled) {
                listState.animateScrollToItem(activeIndex)
            } else {
                listState.scrollToItem(activeIndex)
            }
            withFrameNanos { }
            val correction = snapshotProvider(activeIndex).followDeltaPx(activeIndex) ?: 0f
            if (abs(correction) > config.geometryNoisePx) {
                if (!animationsEnabled) {
                    listState.scrollBy(correction)
                } else {
                    listState.scroll {
                        var previous = 0f
                        animate(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = followDurationMs(
                                    correction,
                                    snapshotProvider(activeIndex).viewportHeightPx
                                )
                            )
                        ) { progress, _ ->
                            scrollBy(correction * (progress - previous))
                            previous = progress
                        }
                    }
                }
            }
        }
    }

    private fun launch(request: LyricsAutoScrollRequest, block: suspend () -> Unit) {
        cancel(LyricsAutoScrollCancelReason.Replaced)
        val token = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            onStarted(token, request)
            try {
                block()
                if (active?.token == token) onCompleted(token, request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (active?.token == token) active = null
            }
        }
        active = ActiveOperation(token, request, job)
        job.start()
    }

    private fun followDurationMs(distancePx: Float, viewportHeightPx: Int): Int {
        if (viewportHeightPx <= 0) return config.followMinDurationMs
        val ratio = (abs(distancePx) / viewportHeightPx).coerceIn(0f, 1f)
        return (config.followMinDurationMs +
            (config.followMaxDurationMs - config.followMinDurationMs) * sqrt(ratio))
            .roundToInt()
    }
}

internal class LyricsNestedScrollObserver(
    private val controller: LyricsViewportController
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            controller.onUserScrollIntent(LyricsUserInputSource.UnknownUserInput)
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
            controller.onUserConsumedScrollDelta(
                consumed.y,
                LyricsUserInputSource.UnknownUserInput
            )
        }
        return Offset.Zero
    }
}

internal fun Modifier.observeLyricsViewport(
    controller: LyricsViewportController
): Modifier = nestedScroll(LyricsNestedScrollObserver(controller))
    // stemwise-aura-exclusion: passive input-source observer; it owns no user action.
    .pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val source = when (down.type) {
                PointerType.Stylus, PointerType.Eraser -> LyricsUserInputSource.Stylus
                else -> LyricsUserInputSource.Touch
            }
            controller.onPointerDown(down.id.value)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
            } finally {
                controller.onPointerUpOrCancel(down.id.value)
            }
        }
    }
    .pointerInput(controller) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll && event.changes.any { it.scrollDelta.y != 0f }) {
                    controller.onUserScrollIntent(LyricsUserInputSource.MouseWheel)
                }
            }
        }
    }
    .onFocusChanged { if (!it.hasFocus) controller.onDiscreteInputEnded() }
    .onPreviewKeyEvent { event ->
        if (event.isLyricsScrollKey()) {
            if (event.type == KeyEventType.KeyUp) controller.onDiscreteInputEnded()
            else if (event.type == KeyEventType.KeyDown) {
                val source = if (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                    LyricsUserInputSource.DPad else LyricsUserInputSource.Keyboard
                controller.onUserScrollIntent(source)
            }
        }
        false
    }
    .semantics {
        val mode = controller.mode.value
        stateDescription = mode::class.simpleName ?: "Lyrics viewport"
        if (mode is LyricsViewportMode.Detached || mode is LyricsViewportMode.Following) {
            customActions = listOf(
                CustomAccessibilityAction("Follow current lyric") {
                    controller.onAccessibilityFollowRequested()
                }
            )
        }
    }

private fun KeyEvent.isLyricsScrollKey(): Boolean = key in setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.PageUp,
    Key.PageDown,
    Key.MoveHome,
    Key.MoveEnd,
    Key.Spacebar
)

internal fun buildLyricsViewportSnapshot(
    listState: LazyListState,
    activeIndex: Int,
    layoutEpoch: Long,
    config: LyricsFollowConfig = LyricsFollowConfig()
): LyricsViewportSnapshot {
    val info = listState.layoutInfo
    val viewportStart = info.viewportStartOffset
    val viewportEnd = info.viewportEndOffset
    val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(0)
    val rawItems = info.visibleItemsInfo
    val heights = rawItems.map { it.size }.sorted()
    val medianHeight = if (heights.isEmpty()) 0f else heights[heights.size / 2].toFloat()
    val preferredRatio = effectiveLyricsPreferredRatio(
        viewportHeight.toFloat(),
        medianHeight,
        config.preferredYRatio
    )
    val preferredY = viewportStart + viewportHeight * preferredRatio
    val visible = rawItems.map { item ->
        val top = item.offset.toFloat()
        val bottom = top + item.size
        val visiblePx = (minOf(bottom, viewportEnd.toFloat()) -
            maxOf(top, viewportStart.toFloat())).coerceAtLeast(0f)
        VisibleLyricGeometry(
            index = item.index,
            key = item.key.toString(),
            offsetPx = item.offset,
            sizePx = item.size,
            focusYPx = lyricsItemFocusY(top, item.size.toFloat(), medianHeight),
            visibleFraction = if (item.size > 0) visiblePx / item.size else 0f
        )
    }
    val anchorItem = visible.minByOrNull { abs(it.focusYPx - preferredY) }
    val anchor = anchorItem?.let {
        LyricsScrollAnchor(
            itemKey = it.key,
            itemIndex = it.index,
            itemFocusOffsetFromPreferredPx = it.focusYPx - preferredY,
            firstVisibleIndex = rawItems.firstOrNull()?.index ?: 0,
            firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset
        )
    }
    val captureTop = viewportStart + viewportHeight * config.captureTopRatio
    val captureBottom = viewportStart + viewportHeight * config.captureBottomRatio
    val seamHalf = viewportHeight * config.preferredSeamHalfWidthRatio
    return LyricsViewportSnapshot(
        sequence = 0L,
        capturedAtNanos = 0L,
        layoutEpoch = layoutEpoch,
        viewportStartPx = viewportStart,
        viewportEndPx = viewportEnd,
        viewportHeightPx = viewportHeight,
        firstVisibleIndex = rawItems.firstOrNull()?.index ?: 0,
        firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
        lastVisibleIndex = rawItems.lastOrNull()?.index ?: -1,
        totalItemsCount = info.totalItemsCount,
        isScrollInProgress = listState.isScrollInProgress,
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
        visibleItems = visible,
        anchor = anchor,
        medianVisibleLineHeightPx = medianHeight,
        preferredYPx = preferredY,
        followTopPx = viewportStart + viewportHeight * config.followTopRatio,
        followBottomPx = viewportStart + viewportHeight * config.followBottomRatio,
        captureTopPx = captureTop,
        captureBottomPx = captureBottom,
        preferredSeamTopPx = preferredY - seamHalf,
        preferredSeamBottomPx = preferredY + seamHalf
    ).also {
        // Access active geometry while the layout snapshot is fresh; no Compose object escapes.
        it.relationOf(activeIndex)
    }
}

internal fun assertLyricsViewportInvariants(state: LyricsViewportEngineState) {
    val userOwns = state.pointerDown || state.directDragActive ||
        state.motionOwner == LyricsMotionOwner.User
    check(!userOwns || state.activeActuatorToken == null)
    check(state.mode !is LyricsViewportMode.Detached || state.activeActuatorToken == null)
    check(state.mode !is LyricsViewportMode.ReacquireCandidate ||
        (state.manualLatch != null && state.candidate != null && state.activeActuatorToken == null))
    check(state.mode !is LyricsViewportMode.DirectManipulation || state.manualLatch != null)
    check(state.mode !is LyricsViewportMode.BallisticUserMotion || state.manualLatch != null)
}

@Composable
internal fun LyricsViewportDebugOverlay(
    controller: LyricsViewportController,
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG || !Debug.isDebuggerConnected()) return
    DisposableEffect(controller) {
        controller.setDebugOverlayAttached(true)
        onDispose { controller.setDebugOverlayAttached(false) }
    }
    val state by controller.debugState
    val snapshot = state.lastSnapshot
    val latch = state.manualLatch
    val candidate = state.candidate
    Box(modifier.clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            snapshot ?: return@Canvas
            listOf(
                snapshot.followTopPx to Color(0x885D5FEF),
                snapshot.followBottomPx to Color(0x885D5FEF),
                snapshot.captureTopPx to Color(0x88F05DB8),
                snapshot.captureBottomPx to Color(0x88F05DB8),
                snapshot.preferredSeamTopPx to Color(0x88FFFFFF),
                snapshot.preferredSeamBottomPx to Color(0x88FFFFFF)
            ).forEach { (y, color) ->
                drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .76f))
                .padding(6.dp)
        ) {
            Text(
                "${state.mode::class.simpleName}  owner=${state.motionOwner}  " +
                    "pointer=${state.pointerDown} drag=${state.directDragActive} " +
                    "scroll=${state.scrollInProgress} token=${state.activeActuatorToken}",
                color = Color.White,
                fontSize = 9.sp
            )
            Text(
                "active=${state.activeIndex}/${state.currentRelation}  " +
                    "epochs=${state.trackEpoch}:${state.layoutEpoch}  " +
                    "anchor=${snapshot?.anchor?.itemKey}:${snapshot?.anchor?.itemIndex}:" +
                    "${snapshot?.anchor?.itemFocusOffsetFromPreferredPx}",
                color = Color.White,
                fontSize = 9.sp
            )
            Text(
                "latch=${latch?.id}/${latch?.originatingGestureId} " +
                    "travel=${latch?.totalTravelPx} net=${latch?.netDisplacementPx} " +
                    "excursion=${latch?.maxExcursionPx} reversals=${latch?.directionReversals}  " +
                    "candidate=${candidate?.cause}/${candidate?.scoreAtCreation}",
                color = Color.White,
                fontSize = 9.sp
            )
            Text(
                "soft=${(state.mode as? LyricsViewportMode.SoftFollowing)?.safeTransitionsRemaining} " +
                    "cancel=${controller.trace().lastOrNull()?.diagnosticReason}",
                color = Color.White,
                fontSize = 9.sp
            )
        }
    }
}
