package app.stemwise.ui.player.surface

import app.stemwise.ui.PrecisionScrubMode
import app.stemwise.ui.design.StemwiseSeekerDesign
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The A/B motor is the boundary's only legal touch target, so it gets a full 56 dp lane. */
internal const val PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP =
    StemwiseSeekerDesign.LOOP_TOUCH_WIDTH_DP / 2f
internal const val PLAYER_SURFACE_SEEKER_RAIL_ENDPOINT_INSET_DP = 4f
internal const val PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP = 12f
internal const val PLAYER_SURFACE_SEEKER_CLOCK_ADDITIONAL_CLEARANCE_DP = 8f
internal const val PLAYER_SURFACE_SEEKER_CLOCK_LABEL_LIFT_DP = 8f
internal const val PLAYER_SURFACE_LOOP_CLOCK_ADDITIONAL_CLEARANCE_DP = 4f

internal data class PlayerSurfaceFineViewport(
    val leftX: Float,
    val rightX: Float
)

/** Fine waveform, value mapping and cursor admission share one visibly bounded viewport. */
internal fun playerSurfaceFineViewport(
    trackStartX: Float,
    trackEndX: Float,
    edgeInsetPx: Float
): PlayerSurfaceFineViewport {
    val start = minOf(trackStartX, trackEndX)
    val end = maxOf(trackStartX, trackEndX)
    val inset = edgeInsetPx.coerceAtLeast(0f).coerceAtMost((end - start) * .5f)
    return PlayerSurfaceFineViewport(start + inset, end - inset)
}

internal fun playerSurfaceFinePlaybackCursorVisible(
    cursorX: Float,
    viewport: PlayerSurfaceFineViewport,
    cursorRadiusPx: Float
): Boolean {
    val guard = cursorRadiusPx.coerceAtLeast(0f)
    return cursorX.isFinite() &&
        cursorX > viewport.leftX + guard && cursorX < viewport.rightX - guard
}

internal fun playerSurfaceSeekerClockBaselineY(
    railY: Float,
    fontAscent: Float,
    density: Float,
    loopPresence: Float = 0f
): Float = railY + fontAscent - (
    PLAYER_SURFACE_SEEKER_CLOCK_ADDITIONAL_CLEARANCE_DP +
        PLAYER_SURFACE_SEEKER_CLOCK_LABEL_LIFT_DP +
        PLAYER_SURFACE_LOOP_CLOCK_ADDITIONAL_CLEARANCE_DP *
        (loopPresence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f)
    ) * density

internal fun playerSurfaceSeekerIdentityClearanceAlpha(clockTop: Float, identityBottom: Float, density: Float): Float =
    ((clockTop - identityBottom) / (8f * density).coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * Fine pixels are owned by one live scrub contact. A stale morph value must never make the
 * centered fine waveform survive owner replacement, detachment, or timeline rebind. Ordinary UP
 * may retain only the explicitly owned exit clock; cancellation and hard reset still remove it.
 */
internal fun playerSurfaceFineLayerFraction(
    waveformMorph: Float,
    pointerActive: Boolean,
    scrubSessionStarted: Boolean,
    releaseAnimating: Boolean = false
): Float = if ((pointerActive && scrubSessionStarted) || releaseAnimating) {
    waveformMorph.coerceIn(0f, 1f)
} else {
    0f
}

internal fun playerSurfaceFineTimelineCenterMs(
    fineOwnerActive: Boolean,
    previewPositionMs: Long,
    renderedPositionMs: Long
): Long = if (fineOwnerActive) previewPositionMs else renderedPositionMs

/** Both clock labels enter and leave hundredths precision as one atomic visual state. */
internal fun playerSurfaceFineClockVisible(
    pointerActive: Boolean,
    scrubSessionStarted: Boolean,
    fineEngaged: Boolean,
    scrubMode: PrecisionScrubMode
): Boolean = pointerActive && scrubSessionStarted && fineEngaged &&
    scrubMode == PrecisionScrubMode.FINE

internal fun playerSurfaceCommittedLoopPreservesFineRelease(
    releaseActive: Boolean,
    pointerActive: Boolean,
    releaseBoundary: FullPlayerLoopBoundary?,
    previewStartMs: Long,
    previewEndMs: Long,
    previousDurationMs: Long,
    nextDurationMs: Long,
    nextStartMs: Long,
    nextEndMs: Long
): Boolean = releaseActive && !pointerActive && releaseBoundary != null &&
    previousDurationMs == nextDurationMs &&
    previewStartMs == nextStartMs && previewEndMs == nextEndMs

/** A live A/B MOVE echoed through Compose belongs to the pointer that authored it. */
internal fun playerSurfaceCommittedLoopPreservesActiveDrag(
    pointerActive: Boolean,
    scrubSessionStarted: Boolean,
    boundary: FullPlayerLoopBoundary?,
    previewStartMs: Long,
    previewEndMs: Long,
    previousDurationMs: Long,
    nextDurationMs: Long,
    nextStartMs: Long,
    nextEndMs: Long
): Boolean = pointerActive && scrubSessionStarted &&
    previousDurationMs == nextDurationMs && when (boundary) {
        FullPlayerLoopBoundary.START -> previewEndMs == nextEndMs
        FullPlayerLoopBoundary.END -> previewStartMs == nextStartMs
        null -> false
    }

/** One height calculation shared by retained A/B pixels and their Fine-release touch target. */
internal fun playerSurfaceLoopHandleHeightDp(selectionGrowth: Float): Float =
    StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP +
        (StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP -
            StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP) *
        selectionGrowth.coerceIn(0f, 1f)

/** Idle heads match; only the acquired endpoint grows. */
internal fun playerSurfaceLoopHandleGrowth(
    boundary: FullPlayerLoopBoundary,
    selectedBoundary: FullPlayerLoopBoundary?,
    selectionGrowth: Float
): Float = if (boundary == selectedBoundary) selectionGrowth.coerceIn(0f, 1f) else 0f

/** Off-screen endpoints fade through the visible edge during Fine entry and exit. */
internal fun playerSurfaceLoopBoundaryTransitionX(
    coarseX: Float, detailX: Float, trackLeft: Float, trackRight: Float, fine: Float
): Float = coarseX + (detailX.coerceIn(trackLeft, trackRight) - coarseX) * fine.coerceIn(0f, 1f)

internal fun playerSurfaceLoopBoundaryTransitionAlpha(
    detailX: Float, trackLeft: Float, trackRight: Float, fine: Float
): Float = if (detailX in trackLeft..trackRight) 1f else 1f - fine.coerceIn(0f, 1f)

/**
 * The player places the visible rail four dp inside the 20dp player gutters (x24..408 at 432dp). The
 * thumb is deliberately allowed to overhang that rail while its larger semantic hit owner remains
 * the complete seeker row; timeline endpoints must not be compressed by held-thumb diameter.
 */
internal fun playerSurfaceSeekerTrackStartX(
    contentLeft: Float,
    contentRight: Float,
    endpointInsetPx: Float
): Float {
    val left = minOf(contentLeft, contentRight)
    val right = maxOf(contentLeft, contentRight)
    val inset = endpointInsetPx.coerceAtLeast(0f).coerceAtMost((right - left) * .5f)
    return left + inset
}

internal fun playerSurfaceSeekerTrackEndX(
    contentLeft: Float,
    contentRight: Float,
    endpointInsetPx: Float
): Float {
    val left = minOf(contentLeft, contentRight)
    val right = maxOf(contentLeft, contentRight)
    val inset = endpointInsetPx.coerceAtLeast(0f).coerceAtMost((right - left) * .5f)
    return right - inset
}

/** Exact value-to-coordinate mapping on the allocated rail: 0 is its start and 1 is its end. */
internal fun playerSurfaceSeekerPositionX(
    progress: Float,
    trackStartX: Float,
    trackEndX: Float
): Float {
    val start = minOf(trackStartX, trackEndX)
    val end = maxOf(trackStartX, trackEndX)
    return start + progress.coerceIn(0f, 1f) * (end - start)
}

/** Exact inverse used by tap and drag admission, including the padded outer hit area. */
internal fun playerSurfaceSeekerProgressAtX(
    pointerX: Float,
    trackStartX: Float,
    trackEndX: Float
): Float {
    val start = minOf(trackStartX, trackEndX)
    val end = maxOf(trackStartX, trackEndX)
    if (end <= start) return 0f
    return ((pointerX - start) / (end - start)).coerceIn(0f, 1f)
}

/**
 * Fine-mode transitions are accumulated only from predominantly vertical pointer samples.
 * Horizontal and diagonal movement reset the candidate travel without changing the current mode,
 * so a long pass to either rail edge cannot accidentally enter or leave Fine mode. A later,
 * deliberate vertical return begins a fresh candidate and can still leave Fine without lifting.
 */
internal data class PlayerSurfaceFineIntentState(
    val qualifyingTravelPx: Float = 0f
)

internal data class PlayerSurfaceFineIntentStep(
    val mode: PrecisionScrubMode,
    val state: PlayerSurfaceFineIntentState
)

internal fun playerSurfaceFineIntentStep(
    currentMode: PrecisionScrubMode,
    state: PlayerSurfaceFineIntentState,
    deltaX: Float,
    deltaY: Float,
    fineEntryThresholdPx: Float,
    fineExitThresholdPx: Float,
    verticalDominanceRatio: Float = 2f
): PlayerSurfaceFineIntentStep {
    if (!deltaX.isFinite() || !deltaY.isFinite()) {
        return PlayerSurfaceFineIntentStep(currentMode, PlayerSurfaceFineIntentState())
    }
    val requiredDirectionTravel = if (currentMode == PrecisionScrubMode.FINE) {
        deltaY
    } else {
        -deltaY
    }
    val dominance = verticalDominanceRatio.coerceAtLeast(1f)
    val intentionalVertical = requiredDirectionTravel > 0f &&
        requiredDirectionTravel >= kotlin.math.abs(deltaX) * dominance
    if (!intentionalVertical) {
        return PlayerSurfaceFineIntentStep(currentMode, PlayerSurfaceFineIntentState())
    }
    val accumulated = state.qualifyingTravelPx.coerceAtLeast(0f) + requiredDirectionTravel
    val threshold = if (currentMode == PrecisionScrubMode.FINE) {
        fineExitThresholdPx
    } else {
        fineEntryThresholdPx
    }.coerceAtLeast(0f)
    val nextMode = if (accumulated >= threshold) {
        if (currentMode == PrecisionScrubMode.FINE) {
            PrecisionScrubMode.FAST_TRAVERSE
        } else {
            PrecisionScrubMode.FINE
        }
    } else {
        currentMode
    }
    return PlayerSurfaceFineIntentStep(
        mode = nextMode,
        state = if (nextMode == currentMode) {
            PlayerSurfaceFineIntentState(accumulated)
        } else {
            PlayerSurfaceFineIntentState()
        }
    )
}

internal data class PlayerSurfaceLoopBoundaryValues(
    val startMs: Long,
    val endMs: Long
)

/** Retains the active boundary until its held highlight has cleared. */
internal class PlayerSurfaceLoopHandleVisualOwner {
    var boundary: FullPlayerLoopBoundary? = null
        private set
    var pressed: Boolean = false
        private set

    fun pointerDown(boundary: FullPlayerLoopBoundary?) {
        this.boundary = boundary
        pressed = boundary != null
    }

    fun pointerUpOrCancel() {
        pressed = false
    }

    fun clearFinishedTail() {
        if (!pressed) boundary = null
    }
}

/** Pure clamp shared by live preview and terminal commit. */
internal fun playerSurfaceAdjustedLoopBoundary(
    boundary: FullPlayerLoopBoundary,
    proposedMs: Double,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    minimumLoopDurationMs: Long
): PlayerSurfaceLoopBoundaryValues {
    val duration = durationMs.coerceAtLeast(0L)
    val minimum = minimumLoopDurationMs.coerceAtLeast(1L).coerceAtMost(duration.coerceAtLeast(1L))
    val maximumStart = (duration - minimum).coerceAtLeast(0L)
    val safeStart = app.stemwise.ui.quantizeSeekPosition(startMs.toDouble(), duration)
        .coerceIn(0L, maximumStart)
    if (boundary == FullPlayerLoopBoundary.START && endMs < 0L) {
        val proposed = proposedMs.takeIf(Double::isFinite)?.let { app.stemwise.ui.quantizeSeekPosition(it, duration) } ?: safeStart
        return PlayerSurfaceLoopBoundaryValues(
            startMs = proposed.coerceIn(0L, maximumStart),
            endMs = -1L
        )
    }
    val safeEnd = endMs.coerceIn((safeStart + minimum).coerceAtMost(duration), duration)
    val proposed = proposedMs.takeIf(Double::isFinite)?.let { app.stemwise.ui.quantizeSeekPosition(it, duration) } ?: when (boundary) {
        FullPlayerLoopBoundary.START -> safeStart
        FullPlayerLoopBoundary.END -> safeEnd
    }
    return when (boundary) {
        FullPlayerLoopBoundary.START -> PlayerSurfaceLoopBoundaryValues(
            startMs = proposed.coerceIn(0L, (safeEnd - minimum).coerceAtLeast(0L)),
            endMs = safeEnd
        )
        FullPlayerLoopBoundary.END -> PlayerSurfaceLoopBoundaryValues(
            startMs = safeStart,
            endMs = proposed.coerceIn((safeStart + minimum).coerceAtMost(duration), duration)
        )
    }
}

/** Nearest labelled endpoint wins; retained ownership breaks only a true midpoint tie. */
internal fun playerSurfaceLoopBoundaryHit(
    pointerX: Float,
    startX: Float,
    endX: Float,
    radiusPx: Float,
    preferredBoundary: FullPlayerLoopBoundary? = null
): FullPlayerLoopBoundary? {
    if (!pointerX.isFinite()) return null
    val radius = radiusPx.coerceAtLeast(0f)
    val startDistance = if (startX.isFinite()) abs(pointerX - startX) else Float.POSITIVE_INFINITY
    val endDistance = if (endX.isFinite()) abs(pointerX - endX) else Float.POSITIVE_INFINITY
    val hitsStart = startDistance <= radius
    val hitsEnd = endDistance <= radius
    return when {
        // Coarse timelines can quantize distinct A/B values onto the same display pixel. Keep
        // both reachable: the left half owns A and the right half owns B.
        hitsStart && hitsEnd && startX == endX -> if (pointerX < startX) {
            FullPlayerLoopBoundary.START
        } else {
            FullPlayerLoopBoundary.END
        }
        hitsStart && hitsEnd && abs(startDistance - endDistance) <= .001f &&
            preferredBoundary != null -> preferredBoundary
        hitsStart && startDistance <= endDistance -> FullPlayerLoopBoundary.START
        hitsEnd -> FullPlayerLoopBoundary.END
        else -> null
    }
}

/**
 * Loop guides are deliberately visual-only. A/B adjustment begins exclusively inside the
 * labelled motor below the rail, leaving the rail and the playback cursor unambiguous even when
 * all three share one X coordinate.
 */
internal fun playerSurfaceLoopBoundaryGestureTarget(
    candidate: FullPlayerLoopBoundary?,
    pointerY: Float,
    endpointHandleTop: Float,
    endpointHandleBottom: Float,
    playheadExclusiveBottom: Float = Float.NEGATIVE_INFINITY
): FullPlayerLoopBoundary? {
    if (candidate == null) return null
    if (!pointerY.isFinite() || !endpointHandleTop.isFinite() ||
        !endpointHandleBottom.isFinite()
    ) return null
    val top = min(endpointHandleTop, endpointHandleBottom)
    val bottom = max(endpointHandleTop, endpointHandleBottom)
    return candidate.takeIf { pointerY > playheadExclusiveBottom && pointerY in top..bottom }
}

/** The rail/playhead keeps its full upper lane even when A or B shares the same X coordinate. */
internal fun playerSurfaceScrubPointerAdmitted(
    pointerX: Float,
    pointerY: Float,
    acquiredLoopBoundary: FullPlayerLoopBoundary?,
    seekerLeft: Float,
    seekerTop: Float,
    seekerRight: Float,
    seekerBottom: Float
): Boolean = acquiredLoopBoundary != null ||
    seekerRight > seekerLeft && seekerBottom > seekerTop &&
    pointerX in seekerLeft..seekerRight && pointerY in seekerTop..seekerBottom

/**
 * Allocation-free player clock formatter. Fine mode exposes every millisecond on both sides.
 */
internal fun formatPlayerSurfaceClock(
    valueMs: Long,
    includeMilliseconds: Boolean,
    output: CharArray
): Int {
    val safe = valueMs.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    var index = 0
    if (hours > 0L) {
        index = appendPlayerSurfaceUnsigned(hours, output, index, minimumDigits = 1)
        output[index++] = ':'
        index = appendPlayerSurfaceUnsigned(minutes, output, index, minimumDigits = 2)
    } else {
        index = appendPlayerSurfaceUnsigned(minutes, output, index, minimumDigits = 1)
    }
    output[index++] = ':'
    index = appendPlayerSurfaceUnsigned(seconds, output, index, minimumDigits = 2)
    if (includeMilliseconds) {
        output[index++] = '.'
        index = appendPlayerSurfaceUnsigned(
            safe % 1_000L,
            output,
            index,
            minimumDigits = 3
        )
    }
    return index
}

private fun appendPlayerSurfaceUnsigned(
    value: Long,
    output: CharArray,
    start: Int,
    minimumDigits: Int
): Int {
    var divisor = 1L
    var digits = 1
    while (value / divisor >= 10L && digits < 9) {
        divisor *= 10L
        digits++
    }
    while (digits < minimumDigits) {
        divisor *= 10L
        digits++
    }
    var index = start
    while (divisor > 0L && index < output.size) {
        output[index++] = ('0'.code + ((value / divisor) % 10L).toInt()).toChar()
        divisor /= 10L
    }
    return index
}
