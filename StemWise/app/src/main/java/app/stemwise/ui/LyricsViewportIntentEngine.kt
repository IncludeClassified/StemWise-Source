package app.stemwise.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class LyricsUserInputSource {
    Touch,
    Stylus,
    MouseWheel,
    Trackpad,
    Scrollbar,
    Keyboard,
    DPad,
    Rotary,
    AccessibilityScroll,
    UnknownUserInput
}

internal enum class LyricsContextRepositionReason {
    TrackChanged,
    FreshScreenEntry,
    PlaybackSeekCommitted,
    LyricTapSeek,
    InitialLyricsLoaded,
    AccessibilityFollow,
    PlaybackFollowRecovery
}

internal enum class LyricsActiveIndexChangeCause {
    OrdinaryPlayback,
    SeekCommitted,
    PositionDiscontinuity,
    TrackChanged,
    LyricsLoaded,
    LyricsReplaced,
    CorrectionOfInvalidIndex
}

internal enum class LyricsActiveViewportRelation {
    AboveViewport,
    AboveCapture,
    InsideCapture,
    BelowCapture,
    BelowViewport,
    Unknown
}

internal enum class LyricsMotionOwner { None, App, User, Layout }

internal sealed interface LyricsResumeMode {
    data object Following : LyricsResumeMode
    data class SoftFollowing(val safeTransitionsRemaining: Int) : LyricsResumeMode
    data object Detached : LyricsResumeMode
}

internal enum class LyricsAutoScrollCancelReason {
    PointerDown,
    UserGestureStarted,
    Replaced,
    TrackChanged,
    LayoutChanged,
    LifecycleStopped,
    ScreenHidden,
    InvalidState
}

internal sealed interface LyricsReacquireCause {
    data class UserReturned(
        val gestureId: Long,
        val crossedPreferredSeam: Boolean,
        val enteredCaptureFromOutside: Boolean
    ) : LyricsReacquireCause

    data class PlaybackCaughtUp(
        val activeIndexAtDetach: Int,
        val activeIndexAtEntry: Int
    ) : LyricsReacquireCause
}

internal sealed interface LyricsViewportMode {
    data object Following : LyricsViewportMode

    data class PointerHold(
        val contactId: Long,
        val modeBeforeContact: LyricsResumeMode,
        val cancelledOwnedScroll: Boolean
    ) : LyricsViewportMode

    data class DirectManipulation(
        val gestureId: Long,
        val source: LyricsUserInputSource
    ) : LyricsViewportMode

    data class BallisticUserMotion(
        val gestureId: Long,
        val source: LyricsUserInputSource
    ) : LyricsViewportMode

    data class Detached(val latchId: Long) : LyricsViewportMode

    data class ReacquireCandidate(
        val candidateId: Long,
        val latchId: Long,
        val cause: LyricsReacquireCause
    ) : LyricsViewportMode

    data class SoftFollowing(
        val originatingLatchId: Long,
        val safeTransitionsRemaining: Int
    ) : LyricsViewportMode

    data class ContextPositioning(
        val actuatorToken: Long?,
        val reason: LyricsContextRepositionReason
    ) : LyricsViewportMode
}

internal data class LyricsEventMeta(
    val sequence: Long,
    val monotonicTimeNanos: Long,
    val trackEpoch: Long,
    val layoutEpoch: Long
)

internal data class LyricsScrollAnchor(
    val itemKey: String?,
    val itemIndex: Int,
    val itemFocusOffsetFromPreferredPx: Float,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int
)

internal data class VisibleLyricGeometry(
    val index: Int,
    val key: String?,
    val offsetPx: Int,
    val sizePx: Int,
    val focusYPx: Float,
    val visibleFraction: Float
)

internal data class LyricsViewportSnapshot(
    val sequence: Long,
    val capturedAtNanos: Long,
    val layoutEpoch: Long,
    val viewportStartPx: Int,
    val viewportEndPx: Int,
    val viewportHeightPx: Int,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int,
    val lastVisibleIndex: Int,
    val totalItemsCount: Int,
    val isScrollInProgress: Boolean,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
    val visibleItems: List<VisibleLyricGeometry>,
    val anchor: LyricsScrollAnchor?,
    val medianVisibleLineHeightPx: Float,
    val preferredYPx: Float,
    val followTopPx: Float,
    val followBottomPx: Float,
    val captureTopPx: Float,
    val captureBottomPx: Float,
    val preferredSeamTopPx: Float,
    val preferredSeamBottomPx: Float
) {
    fun activeFocusY(index: Int): Float? = visibleItems.firstOrNull { it.index == index }?.focusYPx

    fun relationOf(index: Int): LyricsActiveViewportRelation {
        if (index < 0 || totalItemsCount <= 0) return LyricsActiveViewportRelation.Unknown
        val visible = visibleItems.firstOrNull { it.index == index }
        if (visible != null) {
            return when {
                visible.focusYPx < viewportStartPx -> LyricsActiveViewportRelation.AboveViewport
                visible.focusYPx < captureTopPx -> LyricsActiveViewportRelation.AboveCapture
                visible.focusYPx <= captureBottomPx -> LyricsActiveViewportRelation.InsideCapture
                visible.focusYPx <= viewportEndPx -> LyricsActiveViewportRelation.BelowCapture
                else -> LyricsActiveViewportRelation.BelowViewport
            }
        }
        return when {
            visibleItems.isEmpty() -> LyricsActiveViewportRelation.Unknown
            index < firstVisibleIndex -> LyricsActiveViewportRelation.AboveViewport
            index > lastVisibleIndex -> LyricsActiveViewportRelation.BelowViewport
            else -> LyricsActiveViewportRelation.Unknown
        }
    }

    fun isInsideFollowBand(index: Int): Boolean = activeFocusY(index)?.let {
        it in followTopPx..followBottomPx
    } == true

    fun followDeltaPx(index: Int): Float? = activeFocusY(index)?.minus(preferredYPx)
}

internal data class LyricsFollowConfig(
    val preferredYRatio: Float = .42f,
    val followTopRatio: Float = .18f,
    val followBottomRatio: Float = .68f,
    val captureTopRatio: Float = .35f,
    val captureBottomRatio: Float = .52f,
    val preferredSeamHalfWidthRatio: Float = .045f,
    val settleDwellMs: Long = 120L,
    val captureDwellMs: Long = 160L,
    val stableSnapshotCount: Int = 2,
    val softFollowTransitions: Int = 2,
    val softFollowMaxCorrectionViewportRatio: Float = .16f,
    val softFollowMaxCorrectionLineHeights: Float = 1.25f,
    val normalFollowMaxLocalCorrectionViewportRatio: Float = .34f,
    val followMinDurationMs: Int = 105,
    val followMaxDurationMs: Int = 230,
    val geometryNoisePx: Float = 3f,
    val meaningfulApproachViewportRatio: Float = .055f,
    val meaningfulApproachLineHeights: Float = .45f,
    val candidateScoreThreshold: Int = 90,
    val softFollowScoreThreshold: Int = 92
) {
    init {
        require(followTopRatio < captureTopRatio)
        require(captureTopRatio < preferredYRatio)
        require(preferredYRatio < captureBottomRatio)
        require(captureBottomRatio < followBottomRatio)
        require(softFollowTransitions > 0)
        require(stableSnapshotCount > 0)
    }
}

internal data class LyricsSuppressionState(
    val touchExplorationEnabled: Boolean = false,
    val lyricsScreenVisible: Boolean = true,
    val lifecycleAtLeastStarted: Boolean = true,
    val geometryStable: Boolean = false
)

internal data class LyricsManualOwnershipLatch(
    val id: Long,
    val originatingGestureId: Long,
    val source: LyricsUserInputSource,
    val activatedAtNanos: Long,
    val trackEpoch: Long,
    val layoutEpochAtActivation: Long,
    val activeIndexAtActivation: Int,
    val activeRelationAtActivation: LyricsActiveViewportRelation,
    val distinctGestureCount: Int = 1,
    val totalTravelPx: Float = 0f,
    val netDisplacementPx: Float = 0f,
    val maxExcursionPx: Float = 0f,
    val directionReversals: Int = 0,
    val lastMeaningfulUserDirection: Int = 0,
    val lastUserMotionSequence: Long
)

internal data class LyricsGestureEvidence(
    val gestureId: Long,
    val source: LyricsUserInputSource,
    val startedAtNanos: Long,
    val startRelation: LyricsActiveViewportRelation,
    val startDistanceToPreferredPx: Float?,
    val totalTravelPx: Float = 0f,
    val netDisplacementPx: Float = 0f,
    val maximumExcursionPx: Float = 0f,
    val directionReversals: Int = 0,
    val lastMeaningfulDirection: Int = 0,
    val movedTowardCurrentPx: Float = 0f,
    val crossedCaptureEdge: Boolean = false,
    val crossedPreferredSeam: Boolean = false,
    val continuedThroughCapture: Boolean = false,
    val ended: Boolean = false
)

internal data class LyricsStabilityTracker(
    val anchor: LyricsScrollAnchor? = null,
    val stableSnapshotCount: Int = 0,
    val stableSinceNanos: Long? = null
) {
    fun isStable(nowNanos: Long, dwellMs: Long, count: Int): Boolean =
        stableSnapshotCount >= count &&
            stableSinceNanos?.let { nowNanos - it >= dwellMs * 1_000_000L } == true
}

internal data class LyricsReacquisitionCandidate(
    val id: Long,
    val latchId: Long,
    val cause: LyricsReacquireCause,
    val createdAtNanos: Long,
    val layoutEpoch: Long,
    val activeIndexAtCreation: Int,
    val anchorAtCreation: LyricsScrollAnchor?,
    val scoreAtCreation: Int
)

internal data class LyricsViewportEngineState(
    val mode: LyricsViewportMode = LyricsViewportMode.Following,
    val trackEpoch: Long = 0L,
    val layoutEpoch: Long = 0L,
    val activeIndex: Int = -1,
    val previousActiveIndex: Int = -1,
    val pointerDown: Boolean = false,
    val directDragActive: Boolean = false,
    val scrollInProgress: Boolean = false,
    val motionOwner: LyricsMotionOwner = LyricsMotionOwner.None,
    val activeActuatorToken: Long? = null,
    val manualLatch: LyricsManualOwnershipLatch? = null,
    val currentGesture: LyricsGestureEvidence? = null,
    val candidate: LyricsReacquisitionCandidate? = null,
    val lastSnapshot: LyricsViewportSnapshot? = null,
    val previousSnapshot: LyricsViewportSnapshot? = null,
    val settledAnchor: LyricsScrollAnchor? = null,
    val relationAtDetach: LyricsActiveViewportRelation = LyricsActiveViewportRelation.Unknown,
    val currentRelation: LyricsActiveViewportRelation = LyricsActiveViewportRelation.Unknown,
    val stabilityTracker: LyricsStabilityTracker = LyricsStabilityTracker(),
    val suppression: LyricsSuppressionState = LyricsSuppressionState(),
    val pendingPlaybackCatchUp: Boolean = false,
    val lastImplicitReacquireAtNanos: Long? = null
)

internal sealed interface LyricsViewportEvent {
    val meta: LyricsEventMeta

    data class PointerDown(val contactId: Long, override val meta: LyricsEventMeta) :
        LyricsViewportEvent
    data class PointerUpOrCancel(val contactId: Long, override val meta: LyricsEventMeta) :
        LyricsViewportEvent
    data class DirectDragStarted(
        val gestureId: Long,
        val source: LyricsUserInputSource,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class UserScrollDelta(
        val gestureId: Long,
        val source: LyricsUserInputSource,
        val consumedDeltaPx: Float,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class DirectDragEnded(val gestureId: Long, override val meta: LyricsEventMeta) :
        LyricsViewportEvent
    data class ScrollProgressChanged(
        val inProgress: Boolean,
        val owner: LyricsMotionOwner,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class GeometryChanged(
        val snapshot: LyricsViewportSnapshot,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class ActiveIndexChanged(
        val previousIndex: Int,
        val newIndex: Int,
        val cause: LyricsActiveIndexChangeCause,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class ContextReposition(
        val activeIndex: Int,
        val reason: LyricsContextRepositionReason,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class TrackChanged(override val meta: LyricsEventMeta) : LyricsViewportEvent
    data class LayoutEpochChanged(override val meta: LyricsEventMeta) : LyricsViewportEvent
    data class AccessibilityChanged(
        val touchExplorationEnabled: Boolean,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class ScreenVisibilityChanged(val visible: Boolean, override val meta: LyricsEventMeta) :
        LyricsViewportEvent
    data class LifecycleChanged(val atLeastStarted: Boolean, override val meta: LyricsEventMeta) :
        LyricsViewportEvent
    data class AutoScrollStarted(
        val token: Long,
        val request: LyricsAutoScrollRequest,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class AutoScrollCompleted(
        val token: Long,
        val request: LyricsAutoScrollRequest,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
    data class AutoScrollCancelled(
        val token: Long,
        val reason: LyricsAutoScrollCancelReason,
        override val meta: LyricsEventMeta
    ) : LyricsViewportEvent
}

internal sealed interface LyricsAutoScrollRequest {
    data class LocalFollow(val deltaPx: Float, val soft: Boolean) : LyricsAutoScrollRequest
    data class ContextPosition(
        val activeIndex: Int,
        val reason: LyricsContextRepositionReason
    ) : LyricsAutoScrollRequest
}

internal sealed interface LyricsViewportEffect {
    data class CancelOwnedScroll(val reason: LyricsAutoScrollCancelReason) : LyricsViewportEffect
    data class FollowBy(val deltaPx: Float, val soft: Boolean) : LyricsViewportEffect
    data class ContextPosition(
        val activeIndex: Int,
        val reason: LyricsContextRepositionReason
    ) : LyricsViewportEffect
}

internal data class LyricsViewportReduction(
    val state: LyricsViewportEngineState,
    val effects: List<LyricsViewportEffect> = emptyList()
)

internal class LyricsViewportIntentEngine(
    private val config: LyricsFollowConfig = LyricsFollowConfig()
) {
    var state: LyricsViewportEngineState = LyricsViewportEngineState()
        private set

    private var nextLatchId = 0L
    private var nextCandidateId = 0L

    fun reduce(event: LyricsViewportEvent): LyricsViewportReduction {
        val before = state
        val reduction = when (event) {
            is LyricsViewportEvent.PointerDown -> reducePointerDown(before, event)
            is LyricsViewportEvent.PointerUpOrCancel -> reducePointerUp(before, event)
            is LyricsViewportEvent.DirectDragStarted -> reduceDragStarted(before, event)
            is LyricsViewportEvent.UserScrollDelta -> reduceUserDelta(before, event)
            is LyricsViewportEvent.DirectDragEnded -> reduceDragEnded(before, event)
            is LyricsViewportEvent.ScrollProgressChanged -> reduceScrollProgress(before, event)
            is LyricsViewportEvent.GeometryChanged -> reduceGeometry(before, event)
            is LyricsViewportEvent.ActiveIndexChanged -> reduceActiveIndex(before, event)
            is LyricsViewportEvent.ContextReposition -> reduceContext(before, event)
            is LyricsViewportEvent.TrackChanged -> resetForTrack(before, event)
            is LyricsViewportEvent.LayoutEpochChanged -> reduceLayoutEpoch(before, event)
            is LyricsViewportEvent.AccessibilityChanged -> reduceAccessibility(before, event)
            is LyricsViewportEvent.ScreenVisibilityChanged -> reduceVisibility(before, event)
            is LyricsViewportEvent.LifecycleChanged -> reduceLifecycle(before, event)
            is LyricsViewportEvent.AutoScrollStarted -> reduceAutoStart(before, event)
            is LyricsViewportEvent.AutoScrollCompleted -> reduceAutoComplete(before, event)
            is LyricsViewportEvent.AutoScrollCancelled -> reduceAutoCancelled(before, event)
        }
        state = failSafe(reduction.state)
        return reduction.copy(state = state)
    }

    private fun reducePointerDown(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.PointerDown
    ): LyricsViewportReduction {
        val resume = when (val mode = old.mode) {
            LyricsViewportMode.Following -> LyricsResumeMode.Following
            is LyricsViewportMode.SoftFollowing -> LyricsResumeMode.SoftFollowing(
                mode.safeTransitionsRemaining
            )
            else -> LyricsResumeMode.Detached
        }
        return LyricsViewportReduction(
            old.copy(
                pointerDown = true,
                activeActuatorToken = null,
                candidate = null,
                mode = LyricsViewportMode.PointerHold(
                    event.contactId,
                    resume,
                    old.activeActuatorToken != null
                )
            ),
            listOf(LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.PointerDown))
        )
    }

    private fun reducePointerUp(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.PointerUpOrCancel
    ): LyricsViewportReduction {
        val hold = old.mode as? LyricsViewportMode.PointerHold
        if (hold != null && hold.contactId == event.contactId && !old.directDragActive) {
            val restored = when (hold.modeBeforeContact) {
                LyricsResumeMode.Following -> LyricsViewportMode.Following
                is LyricsResumeMode.SoftFollowing -> LyricsViewportMode.SoftFollowing(
                    old.manualLatch?.id ?: 0L,
                    hold.modeBeforeContact.safeTransitionsRemaining
                )
                LyricsResumeMode.Detached -> LyricsViewportMode.Detached(
                    old.manualLatch?.id ?: ++nextLatchId
                )
            }
            return LyricsViewportReduction(old.copy(pointerDown = false, mode = restored))
        }
        return LyricsViewportReduction(old.copy(pointerDown = false))
    }

    private fun reduceDragStarted(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.DirectDragStarted
    ): LyricsViewportReduction {
        val relation = old.lastSnapshot?.relationOf(old.activeIndex)
            ?: LyricsActiveViewportRelation.Unknown
        val existing = old.manualLatch
        val latch = if (existing == null) {
            LyricsManualOwnershipLatch(
                id = ++nextLatchId,
                originatingGestureId = event.gestureId,
                source = event.source,
                activatedAtNanos = event.meta.monotonicTimeNanos,
                trackEpoch = event.meta.trackEpoch,
                layoutEpochAtActivation = event.meta.layoutEpoch,
                activeIndexAtActivation = old.activeIndex,
                activeRelationAtActivation = relation,
                lastUserMotionSequence = event.meta.sequence
            )
        } else {
            existing.copy(
                distinctGestureCount = existing.distinctGestureCount + 1,
                lastUserMotionSequence = event.meta.sequence
            )
        }
        val distance = old.lastSnapshot?.activeFocusY(old.activeIndex)?.let {
            abs(it - old.lastSnapshot.preferredYPx)
        }
        return LyricsViewportReduction(
            old.copy(
                directDragActive = true,
                currentRelation = relation,
                pendingPlaybackCatchUp = false,
                motionOwner = LyricsMotionOwner.User,
                activeActuatorToken = null,
                mode = LyricsViewportMode.DirectManipulation(event.gestureId, event.source),
                manualLatch = latch,
                currentGesture = LyricsGestureEvidence(
                    gestureId = event.gestureId,
                    source = event.source,
                    startedAtNanos = event.meta.monotonicTimeNanos,
                    startRelation = relation,
                    startDistanceToPreferredPx = distance
                ),
                candidate = null,
                stabilityTracker = LyricsStabilityTracker()
            ),
            listOf(
                LyricsViewportEffect.CancelOwnedScroll(
                    LyricsAutoScrollCancelReason.UserGestureStarted
                )
            )
        )
    }

    private fun reduceUserDelta(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.UserScrollDelta
    ): LyricsViewportReduction {
        val delta = event.consumedDeltaPx
        val direction = when {
            abs(delta) <= config.geometryNoisePx -> 0
            delta > 0f -> 1
            else -> -1
        }
        val gesture = old.currentGesture?.takeIf { it.gestureId == event.gestureId }?.let {
            val net = it.netDisplacementPx + delta
            it.copy(
                totalTravelPx = it.totalTravelPx + abs(delta),
                netDisplacementPx = net,
                maximumExcursionPx = max(it.maximumExcursionPx, abs(net)),
                directionReversals = it.directionReversals +
                    if (direction != 0 && it.lastMeaningfulDirection != 0 &&
                        direction != it.lastMeaningfulDirection
                    ) 1 else 0,
                lastMeaningfulDirection = if (direction == 0) it.lastMeaningfulDirection else direction
            )
        }
        val latch = old.manualLatch?.let {
            val net = it.netDisplacementPx + delta
            it.copy(
                totalTravelPx = it.totalTravelPx + abs(delta),
                netDisplacementPx = net,
                maxExcursionPx = max(it.maxExcursionPx, abs(net)),
                directionReversals = gesture?.directionReversals ?: it.directionReversals,
                lastMeaningfulUserDirection = if (direction == 0) {
                    it.lastMeaningfulUserDirection
                } else direction,
                lastUserMotionSequence = event.meta.sequence
            )
        }
        return LyricsViewportReduction(
            old.copy(
                motionOwner = LyricsMotionOwner.User,
                currentGesture = gesture,
                manualLatch = latch,
                candidate = null,
                stabilityTracker = LyricsStabilityTracker()
            )
        )
    }

    private fun reduceDragEnded(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.DirectDragEnded
    ): LyricsViewportReduction {
        val gesture = old.currentGesture?.takeIf { it.gestureId == event.gestureId }?.copy(ended = true)
            ?: old.currentGesture
        val latch = old.manualLatch ?: return LyricsViewportReduction(
            old.copy(directDragActive = false, motionOwner = LyricsMotionOwner.None)
        )
        val mode = if (old.scrollInProgress) {
            LyricsViewportMode.BallisticUserMotion(event.gestureId, gesture?.source ?: latch.source)
        } else LyricsViewportMode.Detached(latch.id)
        return LyricsViewportReduction(
            old.copy(
                directDragActive = false,
                motionOwner = if (old.scrollInProgress) LyricsMotionOwner.User else LyricsMotionOwner.None,
                mode = mode,
                currentGesture = gesture,
                relationAtDetach = old.currentRelation,
                settledAnchor = if (old.scrollInProgress) null else old.lastSnapshot?.anchor,
                stabilityTracker = LyricsStabilityTracker()
            )
        )
    }

    private fun reduceScrollProgress(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.ScrollProgressChanged
    ): LyricsViewportReduction {
        if (event.inProgress) {
            val mode = if (event.owner == LyricsMotionOwner.User && old.manualLatch != null &&
                !old.directDragActive
            ) {
                LyricsViewportMode.BallisticUserMotion(
                    old.currentGesture?.gestureId ?: old.manualLatch.originatingGestureId,
                    old.currentGesture?.source ?: old.manualLatch.source
                )
            } else old.mode
            return LyricsViewportReduction(
                old.copy(
                    scrollInProgress = true,
                    motionOwner = event.owner,
                    mode = mode,
                    stabilityTracker = LyricsStabilityTracker()
                )
            )
        }
        val latch = old.manualLatch
        val detached = event.owner == LyricsMotionOwner.User && latch != null && !old.directDragActive
        return LyricsViewportReduction(
            old.copy(
                scrollInProgress = false,
                motionOwner = LyricsMotionOwner.None,
                mode = if (detached) LyricsViewportMode.Detached(latch.id) else old.mode,
                relationAtDetach = if (detached) old.currentRelation else old.relationAtDetach,
                settledAnchor = if (detached) old.lastSnapshot?.anchor else old.settledAnchor,
                stabilityTracker = LyricsStabilityTracker()
            )
        )
    }

    private fun reduceGeometry(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.GeometryChanged
    ): LyricsViewportReduction {
        val snapshot = event.snapshot
        val relation = if (old.activeIndex < 0) old.currentRelation else snapshot.relationOf(old.activeIndex)
        val stable = !old.pointerDown && !old.directDragActive && !snapshot.isScrollInProgress &&
            old.activeActuatorToken == null && snapshot.layoutEpoch == old.layoutEpoch
        val priorAnchor = old.stabilityTracker.anchor
        val sameAnchor = anchorsMatch(priorAnchor, snapshot.anchor, config.geometryNoisePx)
        val stability = if (stable) {
            if (sameAnchor && priorAnchor != null) {
                old.stabilityTracker.copy(
                    anchor = snapshot.anchor,
                    stableSnapshotCount = old.stabilityTracker.stableSnapshotCount + 1
                )
            } else {
                LyricsStabilityTracker(snapshot.anchor, 1, event.meta.monotonicTimeNanos)
            }
        } else LyricsStabilityTracker()

        val gesture = updateGestureGeometry(old, snapshot, relation)
        var next = old.copy(
            previousSnapshot = old.lastSnapshot,
            lastSnapshot = snapshot,
            currentRelation = relation,
            currentGesture = gesture,
            stabilityTracker = stability,
            suppression = old.suppression.copy(
                geometryStable = stability.isStable(
                    event.meta.monotonicTimeNanos,
                    config.settleDwellMs,
                    config.stableSnapshotCount
                )
            )
        )

        val candidate = old.candidate
        if (old.mode is LyricsViewportMode.ReacquireCandidate && candidate != null) {
            val valid = hardGatesPass(next, snapshot) &&
                candidate.layoutEpoch == snapshot.layoutEpoch &&
                relation == LyricsActiveViewportRelation.InsideCapture &&
                anchorsMatch(candidate.anchorAtCreation, snapshot.anchor, config.geometryNoisePx)
            if (!valid) {
                val latch = old.manualLatch
                return LyricsViewportReduction(
                    next.copy(
                        mode = latch?.let { LyricsViewportMode.Detached(it.id) }
                            ?: LyricsViewportMode.Following,
                        candidate = null
                    )
                )
            }
            val dwellComplete = event.meta.monotonicTimeNanos - candidate.createdAtNanos >=
                config.captureDwellMs * 1_000_000L
            if (dwellComplete && stability.stableSnapshotCount >= config.stableSnapshotCount &&
                candidate.scoreAtCreation >= config.softFollowScoreThreshold
            ) {
                return LyricsViewportReduction(
                    next.copy(
                        mode = LyricsViewportMode.SoftFollowing(
                            candidate.latchId,
                            config.softFollowTransitions
                        ),
                        candidate = null,
                        pendingPlaybackCatchUp = false,
                        lastImplicitReacquireAtNanos = event.meta.monotonicTimeNanos
                    )
                )
            }
            return LyricsViewportReduction(next)
        }

        val latch = next.manualLatch
        if (next.mode is LyricsViewportMode.Detached && latch != null &&
            stability.isStable(
                event.meta.monotonicTimeNanos,
                config.settleDwellMs,
                config.stableSnapshotCount
            ) && hardGatesPass(next, snapshot)
        ) {
            val cause = freshCause(next, relation)
            if (cause != null) {
                val score = intentScore(intentEvidence(next, cause, relation, stability, event))
                if (score >= config.candidateScoreThreshold) {
                    val created = LyricsReacquisitionCandidate(
                        id = ++nextCandidateId,
                        latchId = latch.id,
                        cause = cause,
                        createdAtNanos = event.meta.monotonicTimeNanos,
                        layoutEpoch = snapshot.layoutEpoch,
                        activeIndexAtCreation = next.activeIndex,
                        anchorAtCreation = snapshot.anchor,
                        scoreAtCreation = score
                    )
                    next = next.copy(
                        mode = LyricsViewportMode.ReacquireCandidate(
                            created.id,
                            latch.id,
                            cause
                        ),
                        candidate = created
                    )
                }
            }
        }
        return LyricsViewportReduction(next)
    }

    private fun reduceActiveIndex(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.ActiveIndexChanged
    ): LyricsViewportReduction {
        val snapshot = old.lastSnapshot
        val ordinaryGap = event.newIndex < 0 && event.cause == LyricsActiveIndexChangeCause.OrdinaryPlayback
        val relation = if (ordinaryGap) old.currentRelation else
            snapshot?.relationOf(event.newIndex) ?: LyricsActiveViewportRelation.Unknown
        var next = old.copy(
            previousActiveIndex = event.previousIndex,
            activeIndex = event.newIndex,
            currentRelation = relation
        )
        if (event.newIndex < 0) return LyricsViewportReduction(next.copy(
            pendingPlaybackCatchUp = false))
        when (val mode = old.mode) {
            is LyricsViewportMode.ContextPositioning -> {
                // The destination is playback-owned until a user takes the viewport. A newer
                // cue/seek during the animation must replace its obsolete destination.
                return reduceContext(
                    next,
                    LyricsViewportEvent.ContextReposition(event.newIndex, mode.reason, event.meta)
                )
            }
            LyricsViewportMode.Following -> {
                if (event.cause != LyricsActiveIndexChangeCause.OrdinaryPlayback) {
                    return reduceContext(
                        next,
                        LyricsViewportEvent.ContextReposition(
                            event.newIndex,
                            contextReasonFor(event.cause),
                            event.meta
                        )
                    )
                }
                if (snapshot == null || snapshot.isInsideFollowBand(event.newIndex)) {
                    return LyricsViewportReduction(next)
                }
                val delta = snapshot.followDeltaPx(event.newIndex)
                val limit = snapshot.viewportHeightPx * config.normalFollowMaxLocalCorrectionViewportRatio
                return if (delta != null && abs(delta) <= limit) {
                    LyricsViewportReduction(next, listOf(LyricsViewportEffect.FollowBy(delta, false)))
                } else {
                    reduceContext(next, LyricsViewportEvent.ContextReposition(event.newIndex,
                        LyricsContextRepositionReason.PlaybackFollowRecovery, event.meta))
                }
            }
            is LyricsViewportMode.SoftFollowing -> {
                if (snapshot == null || snapshot.isInsideFollowBand(event.newIndex)) {
                    return LyricsViewportReduction(decrementSoftFollow(next, mode))
                }
                val delta = snapshot.followDeltaPx(event.newIndex)
                val cap = min(
                    snapshot.viewportHeightPx * config.softFollowMaxCorrectionViewportRatio,
                    snapshot.medianVisibleLineHeightPx * config.softFollowMaxCorrectionLineHeights
                )
                if (delta == null || abs(delta) > cap) {
                    return LyricsViewportReduction(detachWithExistingLatch(next, mode.originatingLatchId))
                }
                return LyricsViewportReduction(
                    decrementSoftFollow(next, mode),
                    listOf(LyricsViewportEffect.FollowBy(delta, true))
                )
            }
            is LyricsViewportMode.Detached -> {
                val latch = old.manualLatch
                val eligible = event.cause == LyricsActiveIndexChangeCause.OrdinaryPlayback &&
                    latch != null && event.newIndex > latch.activeIndexAtActivation &&
                    old.currentRelation in setOf(
                        LyricsActiveViewportRelation.AboveViewport,
                        LyricsActiveViewportRelation.AboveCapture
                    ) && old.relationAtDetach in setOf(
                        LyricsActiveViewportRelation.AboveViewport,
                        LyricsActiveViewportRelation.AboveCapture
                    )
                return LyricsViewportReduction(next.copy(pendingPlaybackCatchUp = eligible))
            }
            else -> return LyricsViewportReduction(next)
        }
    }

    private fun reduceContext(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.ContextReposition
    ): LyricsViewportReduction {
        if (old.pointerDown || old.directDragActive || old.motionOwner == LyricsMotionOwner.User) {
            return LyricsViewportReduction(old)
        }
        return LyricsViewportReduction(
            old.copy(
                activeIndex = event.activeIndex,
                mode = LyricsViewportMode.ContextPositioning(null, event.reason),
                manualLatch = null,
                currentGesture = null,
                candidate = null,
                pendingPlaybackCatchUp = false
            ),
            listOf(LyricsViewportEffect.ContextPosition(event.activeIndex, event.reason))
        )
    }

    private fun resetForTrack(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.TrackChanged
    ) = LyricsViewportReduction(
        LyricsViewportEngineState(
            mode = LyricsViewportMode.Following,
            trackEpoch = event.meta.trackEpoch,
            layoutEpoch = event.meta.layoutEpoch,
            activeIndex = old.activeIndex,
            suppression = old.suppression.copy(geometryStable = false)
        ),
        listOf(LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.TrackChanged))
    )

    private fun reduceLayoutEpoch(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.LayoutEpochChanged
    ): LyricsViewportReduction {
        val mode = when (old.mode) {
            is LyricsViewportMode.ReacquireCandidate,
            is LyricsViewportMode.SoftFollowing,
            is LyricsViewportMode.ContextPositioning -> old.manualLatch?.let {
                LyricsViewportMode.Detached(it.id)
            } ?: LyricsViewportMode.Following
            else -> old.mode
        }
        return LyricsViewportReduction(
            old.copy(
                mode = mode,
                layoutEpoch = event.meta.layoutEpoch,
                candidate = null,
                pendingPlaybackCatchUp = false,
                activeActuatorToken = null,
                lastSnapshot = null,
                previousSnapshot = null,
                currentRelation = LyricsActiveViewportRelation.Unknown,
                stabilityTracker = LyricsStabilityTracker(),
                suppression = old.suppression.copy(geometryStable = false)
            ),
            buildList {
                add(LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.LayoutChanged))
                if (old.mode is LyricsViewportMode.ContextPositioning && old.activeIndex >= 0) {
                    add(LyricsViewportEffect.ContextPosition(old.activeIndex, old.mode.reason))
                }
            }
        )
    }

    private fun reduceAccessibility(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.AccessibilityChanged
    ) = LyricsViewportReduction(
        old.copy(
            suppression = old.suppression.copy(
                touchExplorationEnabled = event.touchExplorationEnabled
            ),
            candidate = if (event.touchExplorationEnabled) null else old.candidate,
            mode = if (event.touchExplorationEnabled &&
                old.mode is LyricsViewportMode.ReacquireCandidate
            ) old.manualLatch?.let { LyricsViewportMode.Detached(it.id) } ?: old.mode else old.mode
        )
    )

    private fun reduceVisibility(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.ScreenVisibilityChanged
    ) = LyricsViewportReduction(
        old.copy(
            mode = if (!event.visible && old.mode is LyricsViewportMode.ReacquireCandidate) {
                old.manualLatch?.let { LyricsViewportMode.Detached(it.id) }
                    ?: LyricsViewportMode.Following
            } else old.mode,
            suppression = old.suppression.copy(
                lyricsScreenVisible = event.visible,
                geometryStable = false
            ),
            candidate = null,
            activeActuatorToken = if (event.visible) old.activeActuatorToken else null,
            lastSnapshot = if (event.visible) old.lastSnapshot else null,
            previousSnapshot = if (event.visible) old.previousSnapshot else null,
            currentRelation = if (event.visible) {
                old.currentRelation
            } else LyricsActiveViewportRelation.Unknown,
            stabilityTracker = LyricsStabilityTracker()
        ),
        if (event.visible) emptyList() else listOf(
            LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.ScreenHidden)
        )
    )

    private fun reduceLifecycle(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.LifecycleChanged
    ) = LyricsViewportReduction(
        old.copy(
            mode = if (!event.atLeastStarted &&
                old.mode is LyricsViewportMode.ReacquireCandidate
            ) {
                old.manualLatch?.let { LyricsViewportMode.Detached(it.id) }
                    ?: LyricsViewportMode.Following
            } else old.mode,
            suppression = old.suppression.copy(
                lifecycleAtLeastStarted = event.atLeastStarted,
                geometryStable = false
            ),
            candidate = null,
            activeActuatorToken = if (event.atLeastStarted) old.activeActuatorToken else null,
            lastSnapshot = if (event.atLeastStarted) old.lastSnapshot else null,
            previousSnapshot = if (event.atLeastStarted) old.previousSnapshot else null,
            currentRelation = if (event.atLeastStarted) {
                old.currentRelation
            } else LyricsActiveViewportRelation.Unknown,
            stabilityTracker = LyricsStabilityTracker()
        ),
        if (event.atLeastStarted) emptyList() else listOf(
            LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.LifecycleStopped)
        )
    )

    private fun reduceAutoStart(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.AutoScrollStarted
    ): LyricsViewportReduction {
        if (old.pointerDown || old.directDragActive || old.motionOwner == LyricsMotionOwner.User ||
            old.mode is LyricsViewportMode.Detached ||
            old.mode is LyricsViewportMode.ReacquireCandidate
        ) {
            return LyricsViewportReduction(
                old.copy(activeActuatorToken = null),
                listOf(LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.InvalidState))
            )
        }
        val mode = when (val request = event.request) {
            is LyricsAutoScrollRequest.ContextPosition -> LyricsViewportMode.ContextPositioning(
                event.token,
                request.reason
            )
            is LyricsAutoScrollRequest.LocalFollow -> old.mode
        }
        return LyricsViewportReduction(
            old.copy(activeActuatorToken = event.token, motionOwner = LyricsMotionOwner.App, mode = mode)
        )
    }

    private fun reduceAutoComplete(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.AutoScrollCompleted
    ): LyricsViewportReduction {
        if (old.activeActuatorToken != event.token) return LyricsViewportReduction(old)
        return LyricsViewportReduction(
            old.copy(
                activeActuatorToken = null,
                motionOwner = LyricsMotionOwner.None,
                mode = if (event.request is LyricsAutoScrollRequest.ContextPosition) {
                    LyricsViewportMode.Following
                } else old.mode,
                manualLatch = if (event.request is LyricsAutoScrollRequest.ContextPosition) {
                    null
                } else old.manualLatch
            )
        )
    }

    private fun reduceAutoCancelled(
        old: LyricsViewportEngineState,
        event: LyricsViewportEvent.AutoScrollCancelled
    ): LyricsViewportReduction {
        if (old.activeActuatorToken != null && old.activeActuatorToken != event.token) {
            return LyricsViewportReduction(old)
        }
        return LyricsViewportReduction(
            old.copy(
                activeActuatorToken = null,
                motionOwner = LyricsMotionOwner.None,
                mode = if (old.mode is LyricsViewportMode.ContextPositioning) {
                    LyricsViewportMode.Following
                } else old.mode
            )
        )
    }

    private fun decrementSoftFollow(
        state: LyricsViewportEngineState,
        mode: LyricsViewportMode.SoftFollowing
    ): LyricsViewportEngineState {
        val remaining = mode.safeTransitionsRemaining - 1
        return if (remaining <= 0) {
            state.copy(
                mode = LyricsViewportMode.Following,
                manualLatch = null,
                currentGesture = null
            )
        } else state.copy(mode = mode.copy(safeTransitionsRemaining = remaining))
    }

    private fun detachWithExistingLatch(
        state: LyricsViewportEngineState,
        latchId: Long
    ) = state.copy(mode = LyricsViewportMode.Detached(latchId), candidate = null)

    private fun updateGestureGeometry(
        old: LyricsViewportEngineState,
        snapshot: LyricsViewportSnapshot,
        relation: LyricsActiveViewportRelation
    ): LyricsGestureEvidence? {
        val gesture = old.currentGesture ?: return null
        if (!old.directDragActive && !old.scrollInProgress && gesture.ended) return gesture
        val previous = old.lastSnapshot
        val beforeY = previous?.activeFocusY(old.activeIndex)
        val afterY = snapshot.activeFocusY(old.activeIndex)
        val approach = if (beforeY != null && afterY != null) {
            (abs(beforeY - previous.preferredYPx) - abs(afterY - snapshot.preferredYPx))
                .coerceAtLeast(0f)
        } else 0f
        val previousRelation = previous?.relationOf(old.activeIndex)
            ?: LyricsActiveViewportRelation.Unknown
        val enteredCapture = previousRelation != LyricsActiveViewportRelation.InsideCapture &&
            relation == LyricsActiveViewportRelation.InsideCapture
        val crossedSeam = if (beforeY != null && afterY != null) {
            beforeY !in previous.preferredSeamTopPx..previous.preferredSeamBottomPx &&
                afterY in snapshot.preferredSeamTopPx..snapshot.preferredSeamBottomPx
        } else false
        val continued = gesture.crossedCaptureEdge &&
            relation != LyricsActiveViewportRelation.InsideCapture && old.directDragActive
        return gesture.copy(
            movedTowardCurrentPx = gesture.movedTowardCurrentPx + approach,
            crossedCaptureEdge = gesture.crossedCaptureEdge || enteredCapture,
            crossedPreferredSeam = gesture.crossedPreferredSeam || crossedSeam,
            continuedThroughCapture = gesture.continuedThroughCapture || continued
        )
    }

    private fun hardGatesPass(
        state: LyricsViewportEngineState,
        snapshot: LyricsViewportSnapshot
    ): Boolean = (state.mode is LyricsViewportMode.Detached ||
        state.mode is LyricsViewportMode.ReacquireCandidate) &&
        !state.pointerDown && !state.directDragActive && !snapshot.isScrollInProgress &&
        state.activeActuatorToken == null &&
        !state.suppression.touchExplorationEnabled && state.suppression.lyricsScreenVisible &&
        state.suppression.lifecycleAtLeastStarted &&
        state.layoutEpoch == snapshot.layoutEpoch

    private fun freshCause(
        state: LyricsViewportEngineState,
        relation: LyricsActiveViewportRelation
    ): LyricsReacquireCause? {
        val latch = state.manualLatch ?: return null
        val gesture = state.currentGesture
        if (gesture != null && gesture.ended && gesture.gestureId != latch.originatingGestureId &&
            relation == LyricsActiveViewportRelation.InsideCapture &&
            gesture.movedTowardCurrentPx >= meaningfulApproachThreshold(state.lastSnapshot) &&
            gesture.directionReversals <= 1 && !gesture.continuedThroughCapture &&
            (gesture.crossedCaptureEdge || gesture.crossedPreferredSeam)
        ) {
            return LyricsReacquireCause.UserReturned(
                gesture.gestureId,
                gesture.crossedPreferredSeam,
                gesture.crossedCaptureEdge
            )
        }
        if (state.pendingPlaybackCatchUp && relation == LyricsActiveViewportRelation.InsideCapture &&
            state.activeIndex > latch.activeIndexAtActivation
        ) {
            return LyricsReacquireCause.PlaybackCaughtUp(
                latch.activeIndexAtActivation,
                state.activeIndex
            )
        }
        return null
    }

    private fun meaningfulApproachThreshold(snapshot: LyricsViewportSnapshot?): Float {
        snapshot ?: return Float.MAX_VALUE
        return max(
            snapshot.viewportHeightPx * config.meaningfulApproachViewportRatio,
            snapshot.medianVisibleLineHeightPx * config.meaningfulApproachLineHeights
        )
    }

    private data class IntentEvidence(
        val activeInsideCapture: Boolean,
        val crossedSeam: Boolean,
        val enteredCapture: Boolean,
        val movedToward: Boolean,
        val playbackCaughtUp: Boolean,
        val distinctGesture: Boolean,
        val anchorStable: Boolean,
        val stableForDwell: Boolean,
        val continuedThrough: Boolean,
        val reversals: Int
    )

    private fun intentEvidence(
        state: LyricsViewportEngineState,
        cause: LyricsReacquireCause,
        relation: LyricsActiveViewportRelation,
        stability: LyricsStabilityTracker,
        event: LyricsViewportEvent.GeometryChanged
    ): IntentEvidence {
        val gesture = state.currentGesture
        return IntentEvidence(
            activeInsideCapture = relation == LyricsActiveViewportRelation.InsideCapture,
            crossedSeam = gesture?.crossedPreferredSeam == true,
            enteredCapture = cause is LyricsReacquireCause.PlaybackCaughtUp ||
                gesture?.crossedCaptureEdge == true,
            movedToward = gesture?.movedTowardCurrentPx?.let { it > 0f } == true,
            playbackCaughtUp = cause is LyricsReacquireCause.PlaybackCaughtUp,
            distinctGesture = cause is LyricsReacquireCause.UserReturned,
            anchorStable = stability.stableSnapshotCount >= config.stableSnapshotCount,
            stableForDwell = stability.isStable(
                event.meta.monotonicTimeNanos,
                config.settleDwellMs,
                config.stableSnapshotCount
            ),
            continuedThrough = gesture?.continuedThroughCapture == true,
            reversals = gesture?.directionReversals ?: 0
        )
    }

    private fun intentScore(e: IntentEvidence): Int {
        var score = 0
        if (e.activeInsideCapture) score += 30
        if (e.crossedSeam) score += 24
        if (e.enteredCapture) score += 22
        if (e.movedToward) score += 18
        if (e.playbackCaughtUp) score += 24
        if (e.distinctGesture) score += 12
        if (e.anchorStable) score += 8
        if (e.stableForDwell) score += 8
        if (e.continuedThrough) score -= 40
        if (e.reversals >= 2) score -= 25
        return score.coerceIn(0, 100)
    }

    private fun contextReasonFor(cause: LyricsActiveIndexChangeCause) = when (cause) {
        LyricsActiveIndexChangeCause.TrackChanged -> LyricsContextRepositionReason.TrackChanged
        LyricsActiveIndexChangeCause.SeekCommitted,
        LyricsActiveIndexChangeCause.PositionDiscontinuity ->
            LyricsContextRepositionReason.PlaybackSeekCommitted
        LyricsActiveIndexChangeCause.LyricsLoaded,
        LyricsActiveIndexChangeCause.LyricsReplaced,
        LyricsActiveIndexChangeCause.CorrectionOfInvalidIndex ->
            LyricsContextRepositionReason.InitialLyricsLoaded
        LyricsActiveIndexChangeCause.OrdinaryPlayback ->
            LyricsContextRepositionReason.PlaybackSeekCommitted
    }

    private fun syntheticLatch(
        state: LyricsViewportEngineState,
        meta: LyricsEventMeta
    ) = LyricsManualOwnershipLatch(
        id = ++nextLatchId,
        originatingGestureId = -meta.sequence,
        source = LyricsUserInputSource.UnknownUserInput,
        activatedAtNanos = meta.monotonicTimeNanos,
        trackEpoch = meta.trackEpoch,
        layoutEpochAtActivation = meta.layoutEpoch,
        activeIndexAtActivation = state.activeIndex,
        activeRelationAtActivation = state.currentRelation,
        lastUserMotionSequence = meta.sequence
    )

    private fun failSafe(candidate: LyricsViewportEngineState): LyricsViewportEngineState {
        val userOwns = candidate.pointerDown || candidate.directDragActive ||
            candidate.motionOwner == LyricsMotionOwner.User
        val forbiddenToken = userOwns || candidate.mode is LyricsViewportMode.Detached ||
            candidate.mode is LyricsViewportMode.ReacquireCandidate
        if (!forbiddenToken || candidate.activeActuatorToken == null) return candidate
        val latch = candidate.manualLatch ?: syntheticLatch(
            candidate,
            LyricsEventMeta(
                sequence = candidate.lastSnapshot?.sequence ?: 0L,
                monotonicTimeNanos = candidate.lastSnapshot?.capturedAtNanos ?: 0L,
                trackEpoch = candidate.trackEpoch,
                layoutEpoch = candidate.layoutEpoch
            )
        )
        return candidate.copy(
            activeActuatorToken = null,
            motionOwner = LyricsMotionOwner.None,
            mode = LyricsViewportMode.Detached(latch.id),
            candidate = null
        )
    }
}

private fun anchorsMatch(
    first: LyricsScrollAnchor?,
    second: LyricsScrollAnchor?,
    epsilonPx: Float
): Boolean {
    if (first == null || second == null) return first == null && second == null
    val sameItem = first.itemKey?.let { it == second.itemKey }
        ?: (first.itemIndex == second.itemIndex)
    return sameItem && abs(
        first.itemFocusOffsetFromPreferredPx - second.itemFocusOffsetFromPreferredPx
    ) <= epsilonPx
}

internal fun lyricsItemFocusY(
    itemTopPx: Float,
    itemHeightPx: Float,
    medianLineHeightPx: Float
): Float = itemTopPx + min(itemHeightPx * .5f, medianLineHeightPx * .85f)

internal fun effectiveLyricsPreferredRatio(
    viewportHeightPx: Float,
    medianLineHeightPx: Float,
    configured: Float = .42f
): Float {
    if (viewportHeightPx <= 0f || medianLineHeightPx <= 0f) return configured
    val maximumPreferredPx = viewportHeightPx - medianLineHeightPx * 2.2f
    return (maximumPreferredPx / viewportHeightPx).coerceIn(.36f, configured.coerceAtMost(.48f))
}
