package app.stemwise.ui.player.surface

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import app.stemwise.ui.player.nativeplayer.FullPlayerDetailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

internal class PlayerPagerVerticalCancellationInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun interruptedSpringSettlesAfterVerticalMoveFinalUpOrParentCancel() {
        val durationScale = ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        val setDurationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        activityRule.scenario.onActivity { host ->
            val originalScale = durationScale.invoke(null) as Float
            try {
                for (reducedMotion in listOf(false, true)) {
                    setDurationScale.invoke(null, 1f)
                    for (compact in listOf(false, true)) for (sign in listOf(-1f, 1f)) {
                        for (priorCommit in listOf(false, true)) for (finish in listOf("move", "up", "cancel")) {
                            val base = completeOriginalLoopSnapshot()
                            val snapshot = base.copy(timeline = base.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                                toolDetail = base.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                                    detail = FullPlayerDetailState.None, activity = base.toolDetail.activity.copy(loopActive = false)))
                            val recorded = RecordingSink()
                            var preview = false
                            val sink = object : FullPlayerActionSink by recorded {
                                override fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float): Boolean {
                                    preview = true
                                    return true
                                }
                                override fun onPagerPreview(generation: Long, queueIndex: Int, offsetFraction: Float,
                                    songId: String): Boolean = onPagerPreview(generation, queueIndex, offsetFraction)
                                override fun onPagerCommit(generation: Long, queueIndex: Int, songId: String): Boolean {
                                    preview = false
                                    return recorded.onPagerCommit(generation, queueIndex, songId)
                                }
                                override fun onPagerCancel(generation: Long) { preview = false }
                            }
                            val (root, surface) = qualifiedSurface(host, snapshot, sink)
                            var interception = false
                            val parent = object : FrameLayout(host) {
                                override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
                                    interception = disallowIntercept
                                    super.requestDisallowInterceptTouchEvent(disallowIntercept)
                                }
                            }
                            root.removeView(surface)
                            root.addView(parent, ViewGroup.LayoutParams(1_200, 2_800))
                            parent.addView(surface, ViewGroup.LayoutParams(1_200, 2_800))
                            parent.measure(exactly(1_200), exactly(2_800))
                            parent.layout(0, 0, 1_200, 2_800)
                            try {
                                if (compact) surface.updateRouteExpansionProgress(0f)
                                val geometry = field(surface, "routeGeometry") as PlayerSurfaceRouteGeometry
                                val x = (geometry.pagerLeft + geometry.pagerRight) * .5f
                                val y = (geometry.pagerTop + geometry.pagerBottom) * .5f
                                val width = geometry.pagerRight - geometry.pagerLeft
                                val now = SystemClock.uptimeMillis()
                                val distance = width / 1.14f * if (priorCommit) -sign * .7f else sign * .3f
                                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, x, y)
                                dispatch(surface, now, now + 100L, MotionEvent.ACTION_MOVE, x + distance, y)
                                dispatch(surface, now, now + 200L,
                                    if (priorCommit) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, x + distance, y)
                                assertEquals(sign * .3f, field(surface, "pagerOffsetFraction") as Float, .001f)
                                val commits = recorded.pagerCommits.size
                                assertEquals(if (priorCommit) 1 else 0, commits)
                                if (reducedMotion) setDurationScale.invoke(null, 0f)
                                dispatch(surface, now + 210L, now + 210L, MotionEvent.ACTION_DOWN, x, y)
                                assertFalse(field(surface, "pagerAnimating") as Boolean)
                                if (finish == "move") {
                                    dispatch(surface, now + 210L, now + 230L, MotionEvent.ACTION_MOVE, x, y + 100f)
                                }
                                dispatch(surface, now + 210L, now + 250L,
                                    if (finish == "cancel") MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y + 100f)
                                assertEquals(MotionEvent.INVALID_POINTER_ID,
                                    (field(surface, "pagerGestures") as PlayerPagerGestureOwner).pointerId)
                                assertFalse("Parent remained intercepted: $compact/$finish", interception)
                                assertFalse("Cancelled selection preview survived", preview)
                                if (reducedMotion) assertEquals(0f, field(surface, "pagerOffsetFraction") as Float, 0f)
                                val frame = PlayerSurfaceView::class.java.getDeclaredMethod("doFrame", Long::class.javaPrimitiveType)
                                    .apply { isAccessible = true }
                                val start = System.nanoTime()
                                repeat(180) { frame.invoke(surface, start + it * 16_666_667L) }
                                assertEquals("Stranded offset: compact=$compact sign=$sign priorCommit=$priorCommit finish=$finish",
                                    0f, field(surface, "pagerOffsetFraction") as Float, 0f)
                                assertFalse(field(surface, "pagerAnimating") as Boolean)
                                assertEquals("Vertical cancellation committed a song", commits, recorded.pagerCommits.size)
                            } finally {
                                root.removeAllViews()
                                setDurationScale.invoke(null, 1f)
                            }
                        }
                    }
                }
            } finally { setDurationScale.invoke(null, originalScale) }
        }
    }

    private fun field(surface: PlayerSurfaceView, name: String): Any =
        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface)!!
}
