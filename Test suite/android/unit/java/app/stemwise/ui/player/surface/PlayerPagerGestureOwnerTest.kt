package app.stemwise.ui.player.surface

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class PlayerPagerGestureOwnerTest {
    @Test fun verticalAndTapOnlyContactsNeverBecomeHorizontalOwners() {
        val owner = PlayerPagerGestureOwner(8f, 1.14f)
        owner.begin(3, 100f, 100f, 0L, 0f)
        assertEquals(PlayerPagerPointerUpdate.CANCELLED_VERTICAL, owner.update(104f, 125f, 10L, 300f, 1, 3))
        assertEquals(MotionEvent.INVALID_POINTER_ID, owner.pointerId)
        assertFalse(owner.dragging)
        owner.begin(4, 100f, 100f, 20L, 0f, tapOnly = true)
        assertEquals(PlayerPagerPointerUpdate.TRACKING, owner.update(103f, 101f, 25L, 300f, 1, 3))
        assertEquals(PlayerPagerPointerUpdate.CANCELLED_TAP, owner.update(140f, 100f, 30L, 300f, 1, 3))
        assertEquals(4, owner.pointerId)
        assertTrue(owner.tapCancelled)
        assertEquals(PlayerPagerPointerUpdate.CANCELLED_TAP, owner.update(100f, 100f, 40L, 300f, 1, 3))
        owner.reset()
        assertEquals(MotionEvent.INVALID_POINTER_ID, owner.pointerId)
    }

    @Test fun horizontalSamplesKeepExactPointerAndQueueEdgeCannotAcquireVelocity() {
        val owner = PlayerPagerGestureOwner(8f, 1f)
        owner.begin(7, 100f, 100f, 0L, .1f)
        assertEquals(PlayerPagerPointerUpdate.DRAGGING, owner.update(160f, 103f, 20L, 300f, 1, 3))
        assertEquals(7, owner.pointerId)
        assertEquals(.3f, owner.offset, .0001f)
        assertTrue(owner.velocity > 0f)
        owner.reset()
        assertEquals(MotionEvent.INVALID_POINTER_ID, owner.pointerId)
        assertEquals(0f, owner.velocity, 0f)
        owner.begin(8, 100f, 100f, 30L, 0f)
        owner.update(160f, 100f, 50L, 300f, 0, 3)
        assertTrue(owner.offset > 0f && owner.offset < .2f)
        assertEquals(0f, owner.velocity, 0f)
    }

    @Test fun replacementStartsFromItsOwnCoordinatesAndTransportOffset() {
        val owner = PlayerPagerGestureOwner(8f, 1f)
        owner.begin(1, 0f, 0f, 1L, 0f)
        owner.update(100f, 0f, 21L, 400f, 1, 3)
        owner.begin(2, 200f, 300f, 30L, -.2f)
        assertFalse(owner.dragging)
        assertEquals(0f, owner.velocity, 0f)
        assertEquals(PlayerPagerPointerUpdate.DRAGGING, owner.update(180f, 301f, 50L, 400f, 1, 3))
        assertEquals(2, owner.pointerId)
        assertEquals(-.25f, owner.offset, .0001f)
    }
}
