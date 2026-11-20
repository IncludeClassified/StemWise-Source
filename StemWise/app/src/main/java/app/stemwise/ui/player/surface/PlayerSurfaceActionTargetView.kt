package app.stemwise.ui.player.surface

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

internal class PlayerSurfaceActionTargetView(
context: Context,
    private val action: () -> Boolean,
    private val invalidateVisual: () -> Unit,
    private val scheduleFrame: () -> Unit
) : View(context) {
    private val pressBloom = NativePressBloomState()
    val pressBloomFrame = NativePressBloomFrame()
    private var actionPointerId = MotionEvent.INVALID_POINTER_ID

    init {
        // Compose owns the one public accessibility projection for the whole native surface.
        // Exporting these transparent hit children as well creates duplicate TalkBack and
        // UiAutomation nodes on devices that expose AndroidView descendants. Do not attach a
        // description to this physical-only motor: some OEM bridges retain named descendants
        // even when IMPORTANT_FOR_ACCESSIBILITY_NO is set.
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            return playerSurfaceCancelDisabledAction(actionPointerId) {
                finishAction(perform = false)
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actionPointerId = event.getPointerId(event.actionIndex)
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(actionPointerId)
                if (playerSurfaceActionPointerDisposition(
                        capturedPointerId = actionPointerId,
                        actionMasked = event.actionMasked,
                        actionPointerId = MotionEvent.INVALID_POINTER_ID,
                        capturedPointerIndex = pointerIndex
                    ) == PlayerSurfaceActionPointerDisposition.CANCEL
                ) {
                    finishAction(perform = false)
                } else if (
                    pointerIndex >= 0 &&
                    !pointerInside(event.getX(pointerIndex), event.getY(pointerIndex))
                ) {
                    // A slide is navigation, not a deferred click. Once the pointer leaves the
                    // accepted motor this gesture cannot re-arm by crossing back over it.
                    finishAction(perform = false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val actionPointerIndex = event.actionIndex
                when (playerSurfaceActionPointerDisposition(
                    capturedPointerId = actionPointerId,
                    actionMasked = event.actionMasked,
                    actionPointerId = event.getPointerId(actionPointerIndex),
                    capturedPointerIndex = event.findPointerIndex(actionPointerId)
                )) {
                    PlayerSurfaceActionPointerDisposition.FINISH -> finishAction(
                        perform = pointerInside(
                            event.getX(actionPointerIndex),
                            event.getY(actionPointerIndex)
                        )
                    )
                    PlayerSurfaceActionPointerDisposition.CANCEL ->
                        finishAction(perform = false)
                    PlayerSurfaceActionPointerDisposition.KEEP -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                finishAction(perform = false)
            }
        }
        // Once DOWN is acquired, the target keeps the pointer lease through normal movement.
        return true
    }

    private fun finishAction(perform: Boolean) {
        if (actionPointerId == MotionEvent.INVALID_POINTER_ID) return
        actionPointerId = MotionEvent.INVALID_POINTER_ID
        isPressed = false
        if (perform) performClick()
    }

    fun cancelPendingAction() = finishAction(perform = false)

    private fun pointerInside(x: Float, y: Float): Boolean =
        x >= 0f && y >= 0f && x < width && y < height

    fun pulse(): Boolean {
        if (
            !isEnabled || visibility != VISIBLE ||
            actionPointerId != MotionEvent.INVALID_POINTER_ID
        ) return false
        val nowMs = SystemClock.uptimeMillis()
        pressBloom.start(nowMs, reducedMotion = !ValueAnimator.areAnimatorsEnabled())
        scheduleFrame()
        invalidateVisual()
        return true
    }

    fun samplePressBloom(nowMs: Long): Boolean {
        if (pressBloom.sample(nowMs, pressBloomFrame)) invalidateVisual()
        return pressBloomFrame.needsAnotherFrame
    }

    override fun performClick(): Boolean {
        super.performClick()
        val accepted = action()
        if (accepted) {
            pressBloom.start(
                SystemClock.uptimeMillis(),
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            )
            scheduleFrame()
            invalidateVisual()
        }
        return accepted
    }
}
