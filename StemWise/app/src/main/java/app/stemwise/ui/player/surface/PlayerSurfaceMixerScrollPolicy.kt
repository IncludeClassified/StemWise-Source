package app.stemwise.ui.player.surface

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

internal fun playerSurfaceMixerVisibleLaneCount(
    laneCount: Int, viewportHeight: Float, rowHeight: Float, spacing: Float
): Int = floor(((viewportHeight + spacing) / (rowHeight + spacing).coerceAtLeast(1f)).toDouble())
    .toInt().coerceIn(0, laneCount.coerceAtLeast(0))

internal fun playerSurfaceMixerMaximumScrollPx(
    laneCount: Int, rowHeight: Float, spacing: Float, viewportHeight: Float
): Float = (laneCount.coerceAtLeast(0) * rowHeight + (laneCount - 1).coerceAtLeast(0) * spacing -
    viewportHeight).coerceAtLeast(0f)

internal fun playerSurfaceMixerScrolledBounds(
    bounds: PlayerSurfaceBounds, viewport: PlayerSurfaceBounds, offsetPx: Float
): PlayerSurfaceBounds {
    val top = max(bounds.top - offsetPx, viewport.top)
    val bottom = min(bounds.bottom - offsetPx, viewport.bottom)
    if (bottom <= top) return PlayerSurfaceBounds(0f, 0f, 0f, 0f)
    return PlayerSurfaceBounds(max(bounds.left, viewport.left), top, min(bounds.right, viewport.right), bottom)
}

internal enum class PlayerSurfaceMixerDragIntent { PENDING, GAIN, SCROLL }

internal fun playerSurfaceMixerDragIntent(dx: Float, dy: Float, touchSlop: Float): PlayerSurfaceMixerDragIntent =
    when {
        max(abs(dx), abs(dy)) <= touchSlop -> PlayerSurfaceMixerDragIntent.PENDING
        abs(dy) > abs(dx) -> PlayerSurfaceMixerDragIntent.SCROLL
        abs(dx) >= abs(dy) * 1.25f -> PlayerSurfaceMixerDragIntent.GAIN
        else -> PlayerSurfaceMixerDragIntent.PENDING
    }
