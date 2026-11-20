package app.stemwise.ui

import kotlin.math.abs
import kotlin.math.max

/** Shared interaction states consumed by the mounted native [player.surface.PlayerSurfaceView]. */
internal enum class PrecisionScrubMode {
    READY,
    FAST_TRAVERSE,
    FINE
}

/**
 * Gesture-local resistance around the start of a complete A-B loop.
 *
 * [heldTravelPx] is physical finger displacement after capture. Keeping it in pixels makes the
 * detent feel the same in whole-song and Fine modes; only movement beyond the breakout distance
 * is converted back into timeline movement.
 */
internal data class LoopStartDetentState(
    val activeLoopA: Long? = null,
    val activeLoopB: Long? = null,
    val armed: Boolean = false,
    val holding: Boolean = false,
    val heldTravelPx: Float = 0f
)

internal data class LoopStartDetentStep(
    val state: LoopStartDetentState,
    val accumulatorMs: Double,
    val previewPositionMs: Long,
    val captured: Boolean = false
)

/**
 * Applies one horizontal drag delta without introducing a timer or a second scrub mode.
 *
 * A complete loop captures once when the raw cursor enters or crosses the physical zone around A.
 * The first [breakoutDistancePx] of post-capture travel is consumed, after which only excess
 * travel is carried into ordinary traversal, including excess contained in the crossing event
 * itself. The detent cannot capture again until the cursor has left its capture zone, which avoids
 * repeated trapping and haptic jitter around the exact boundary.
 */
internal fun loopStartDetentStep(
    state: LoopStartDetentState,
    currentAccumulatorMs: Double,
    movementPx: Float,
    msPerPx: Double,
    durationMs: Long,
    loopA: Long?,
    loopB: Long?,
    captureRadiusPx: Float,
    breakoutDistancePx: Float
): LoopStartDetentStep {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val current = currentAccumulatorMs
        .takeIf(Double::isFinite)
        ?.coerceIn(0.0, safeDuration.toDouble())
        ?: 0.0
    val movement = movementPx.takeIf(Float::isFinite) ?: 0f
    val sensitivity = msPerPx.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    fun moved(fromMs: Double, deltaPx: Float): Double =
        (fromMs + deltaPx.toDouble() * sensitivity)
            .coerceIn(0.0, safeDuration.toDouble())
    fun ordinary(nextMs: Double, nextState: LoopStartDetentState) = LoopStartDetentStep(
        state = nextState,
        accumulatorMs = nextMs,
        previewPositionMs = quantizeSeekPosition(nextMs, safeDuration)
    )

    val a = loopA
    val b = loopB
    val completeLoop = a != null && b != null && safeDuration > 0L &&
        a >= 0L && a < b && b <= safeDuration
    if (!completeLoop) {
        return ordinary(moved(current, movement), LoopStartDetentState())
    }
    val exactA = requireNotNull(a)
    val exactB = requireNotNull(b)
    val zoneMs = captureRadiusPx.coerceAtLeast(0f).toDouble() * sensitivity
    fun outsideZone(positionMs: Double): Boolean =
        abs(positionMs - exactA.toDouble()) > zoneMs

    var working = if (state.activeLoopA != exactA || state.activeLoopB != exactB) {
        LoopStartDetentState(
            activeLoopA = exactA,
            activeLoopB = exactB,
            armed = outsideZone(current)
        )
    } else {
        state
    }

    if (working.holding) {
        val heldTravel = working.heldTravelPx + movement
        val breakout = breakoutDistancePx.coerceAtLeast(0f)
        if (abs(heldTravel) <= breakout) {
            return LoopStartDetentStep(
                state = working.copy(heldTravelPx = heldTravel),
                accumulatorMs = exactA.toDouble(),
                previewPositionMs = exactA
            )
        }
        val consumed = if (heldTravel > 0f) breakout else -breakout
        val excessTravel = heldTravel - consumed
        val next = moved(exactA.toDouble(), excessTravel)
        return ordinary(
            next,
            working.copy(
                armed = outsideZone(next),
                holding = false,
                heldTravelPx = 0f
            )
        )
    }

    if (!working.armed && outsideZone(current)) {
        working = working.copy(armed = true)
    }
    val next = moved(current, movement)
    val crossedA = (current < exactA && next >= exactA) ||
        (current > exactA && next <= exactA)
    val enteredZone = !outsideZone(next)
    if (working.armed && (crossedA || enteredZone)) {
        // A high-velocity pointer event may contain travel on both sides of A. Preserve that
        // event's post-A portion: the detent consumes only its physical breakout distance, not the
        // whole event. An approach that merely enters the capture zone has no post-A overshoot and
        // therefore still settles exactly on A until a later event breaks out.
        val postCaptureTravelPx = if (crossedA && sensitivity > 0.0) {
            val travelToApx = (exactA.toDouble() - current) / sensitivity
            (movement.toDouble() - travelToApx).toFloat()
        } else {
            0f
        }
        val breakout = breakoutDistancePx.coerceAtLeast(0f)
        if (abs(postCaptureTravelPx) > breakout) {
            val consumed = if (postCaptureTravelPx > 0f) breakout else -breakout
            val continued = moved(exactA.toDouble(), postCaptureTravelPx - consumed)
            return LoopStartDetentStep(
                state = working.copy(
                    armed = outsideZone(continued),
                    holding = false,
                    heldTravelPx = 0f
                ),
                accumulatorMs = continued,
                previewPositionMs = quantizeSeekPosition(continued, safeDuration),
                captured = true
            )
        }
        return LoopStartDetentStep(
            state = working.copy(
                armed = false,
                holding = true,
                heldTravelPx = postCaptureTravelPx
            ),
            accumulatorMs = exactA.toDouble(),
            previewPositionMs = exactA,
            captured = true
        )
    }

    return ordinary(
        next,
        working.copy(
            armed = working.armed || outsideZone(next),
            holding = false,
            heldTravelPx = 0f
        )
    )
}

internal fun quantizeSeekPosition(valueMs: Double, durationMs: Long): Long =
    app.stemwise.playback.playbackControlPositionMs(valueMs, durationMs)

/** Fine-mode admission stays well beyond ordinary touch slop. */
internal fun fineModeEntryThresholdPx(touchSlopPx: Float, density: Float): Float =
    max(touchSlopPx.coerceAtLeast(0f) * 2.5f, 24f * density.coerceAtLeast(.1f))

/** Fine-mode exit also requires deliberate vertical travel, not normal horizontal thumb drift. */
internal fun fineModeExitThresholdPx(touchSlopPx: Float, density: Float): Float =
    max(touchSlopPx.coerceAtLeast(0f) * 2f, 18f * density.coerceAtLeast(.1f))

internal fun spokenDuration(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return buildString {
        if (minutes > 0L) {
            append(minutes)
            append(if (minutes == 1L) " minute " else " minutes ")
        }
        append(seconds)
        if (millis > 0L) {
            append(" point ")
            append(millis.toString().padStart(3, '0'))
        }
        append(if (seconds == 1L && millis == 0L) " second" else " seconds")
    }.trim()
}
