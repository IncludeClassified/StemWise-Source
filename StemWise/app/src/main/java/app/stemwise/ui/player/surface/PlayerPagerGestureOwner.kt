package app.stemwise.ui.player.surface

import android.view.MotionEvent
import kotlin.math.abs

internal enum class PlayerPagerPointerUpdate { TRACKING, DRAGGING, CANCELLED_TAP, CANCELLED_VERTICAL }

/** Owns one physical pointer; rendering and accepted queue selection stay with the surface. */
internal class PlayerPagerGestureOwner(private val touchSlop: Float, private val dragGain: Float) {
    var pointerId = MotionEvent.INVALID_POINTER_ID
        private set
    var dragging = false
        private set
    var velocity = 0f
        private set
    var offset = 0f
        private set
    private var tapOnly = false
    var tapCancelled = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastTimeMs = 0L
    private var origin = 0f

    fun begin(id: Int, x: Float, y: Float, timeMs: Long, initialOffset: Float, tapOnly: Boolean = false) {
        pointerId = id
        downX = x; downY = y; lastX = x; lastTimeMs = timeMs
        origin = initialOffset; offset = initialOffset
        this.tapOnly = tapOnly
        tapCancelled = false
        velocity = 0f; dragging = false
    }

    fun update(x: Float, y: Float, timeMs: Long, pageWidth: Float, currentIndex: Int,
        itemCount: Int?): PlayerPagerPointerUpdate {
        val totalX = x - downX
        val totalY = y - downY
        if (tapOnly) {
            if (tapCancelled || abs(totalX) > touchSlop || abs(totalY) > touchSlop) {
                tapCancelled = true
                return PlayerPagerPointerUpdate.CANCELLED_TAP
            }
            return PlayerPagerPointerUpdate.TRACKING
        }
        if (!dragging) {
            if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) {
                reset()
                return PlayerPagerPointerUpdate.CANCELLED_VERTICAL
            }
            if (abs(totalX) <= touchSlop || abs(totalX) <= abs(totalY)) {
                lastX = x; lastTimeMs = timeMs
                return PlayerPagerPointerUpdate.TRACKING
            }
            dragging = true
        }
        val width = pageWidth.coerceAtLeast(1f)
        val requested = origin + totalX / width * dragGain
        val edgeBlocked = itemCount != null && ((currentIndex <= 0 && requested > 0f) ||
            (currentIndex >= itemCount - 1 && requested < 0f))
        offset = playerSurfaceResistedPagerOffset(requested, edgeBlocked)
        val elapsedMs = (timeMs - lastTimeMs).coerceAtLeast(1L)
        velocity = if (edgeBlocked) 0f else playerSurfacePagerVelocityAfterSample(velocity,
            ((x - lastX) / width) * (1_000f / elapsedMs) * dragGain, elapsedMs)
        lastX = x; lastTimeMs = timeMs
        return PlayerPagerPointerUpdate.DRAGGING
    }

    fun reset() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        tapOnly = false; tapCancelled = false; dragging = false; origin = 0f; velocity = 0f
    }
}
