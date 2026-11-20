package app.stemwise.ui.player.surface

import androidx.compose.animation.core.CubicBezierEasing
import app.stemwise.ui.design.StemwiseAuraContract
import kotlin.math.abs

private val SELECTED_STATE_EASING = CubicBezierEasing(.2f, 0f, 0f, 1f)

internal fun playerSurfaceTimedInteractionProgress(
    elapsedMs: Long,
    durationMs: Int,
    easing: CubicBezierEasing
): Float {
    if (durationMs <= 0) return 1f
    val raw = (elapsedMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    return easing.transform(raw)
}

internal fun playerSurfaceSelectedStateProgress(
    startProgress: Float,
    selected: Boolean,
    elapsedMs: Long,
    reducedMotion: Boolean,
    durationMs: Int = StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS
): Float {
    val target = if (selected) 1f else 0f
    if (reducedMotion) return target
    val progress = playerSurfaceTimedInteractionProgress(
        elapsedMs = elapsedMs,
        durationMs = durationMs,
        easing = SELECTED_STATE_EASING
    )
    val start = startProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: target
    return start + (target - start) * progress
}

internal class NativePressBloomFrame {
    var visible = false
    var effectAlpha = 0f
    var needsAnotherFrame = false
}

internal fun playerSurfaceSelectedAuraProgress(selectedProgress: Float): Float = selectedProgress.coerceIn(0f, 1f)

/** Silence is a visual state; mute toggles always use the independent committed mute bit. */
internal fun playerSurfaceLaneSilent(muted: Boolean, volume: Float): Boolean = muted || volume <= 0f

/** Allocation-free selected-state envelope for one retained native glyph. */
internal class NativeSelectedStateEnvelope(
    private val durationMs: Int = StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS
) {
    private var initialized = false
    private var selected = false
    private var startedAtMs = Long.MIN_VALUE
    private var startProgress = 0f
    private var progress = 0f
    val value: Float get() = progress

    fun sample(targetSelected: Boolean, nowMs: Long, reducedMotion: Boolean): Float {
        if (!initialized) {
            initialized = true
            selected = targetSelected
            startedAtMs = Long.MIN_VALUE
            startProgress = if (selected) 1f else 0f
            progress = startProgress
            return progress
        }
        if (reducedMotion) {
            selected = targetSelected
            startedAtMs = Long.MIN_VALUE
            startProgress = if (selected) 1f else 0f
            progress = startProgress
            return progress
        }
        if (targetSelected != selected) {
            progress = currentProgress(nowMs)
            startProgress = progress
            selected = targetSelected
            startedAtMs = nowMs
        }
        progress = currentProgress(nowMs)
        return progress
    }

    fun needsAnotherFrame(nowMs: Long): Boolean = startedAtMs != Long.MIN_VALUE &&
        (
            nowMs - startedAtMs < durationMs ||
                abs(progress - if (selected) 1f else 0f) > .001f
            )

    fun reset() {
        initialized = false
        selected = false
        startedAtMs = Long.MIN_VALUE
        startProgress = 0f
        progress = 0f
    }

    private fun currentProgress(nowMs: Long): Float {
        val started = startedAtMs
        if (started == Long.MIN_VALUE) return progress
        return playerSurfaceSelectedStateProgress(
            startProgress = startProgress,
            selected = selected,
            elapsedMs = (nowMs - started).coerceAtLeast(0L),
            reducedMotion = false,
            durationMs = durationMs
        )
    }
}

/** A brief highlight after an accepted action; it never changes glyph scale or selection. */
internal class NativePressBloomState {
    private var startedAtMs = Long.MIN_VALUE

    fun start(nowMs: Long, @Suppress("UNUSED_PARAMETER") reducedMotion: Boolean) {
        startedAtMs = nowMs
    }

    fun reset() { startedAtMs = Long.MIN_VALUE }

    fun sample(nowMs: Long, output: NativePressBloomFrame): Boolean {
        val started = startedAtMs
        val progress = if (started == Long.MIN_VALUE) 1f else
            ((nowMs - started).coerceAtLeast(0L).toFloat() /
                StemwiseAuraContract.REDUCED_PRESS_HIGHLIGHT_MS).coerceIn(0f, 1f)
        output.visible = progress < 1f
        output.effectAlpha = 1f - progress
        output.needsAnotherFrame = output.visible
        if (!output.visible) reset()
        return started != Long.MIN_VALUE
    }
}

/** Static feedback belongs to the held control and ends with its pointer. */
internal class NativeHeldHighlightState {
    private var held = false
    private var changed = false

    fun pointerDown() { held = true; changed = true }

    fun pointerUpOrCancel() { held = false; changed = true }
    fun reset() { held = false; changed = false }
    fun advance(): Boolean = changed.also { changed = false }
    fun needsAnotherFrame(): Boolean = false
    fun isReducedHighlightVisible(): Boolean = held
}
