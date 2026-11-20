package app.stemwise.ui.player.surface

import org.junit.Assert.*
import org.junit.Test

class PlayerArtworkAuraMotionOwnerTest {
    @Test fun pausedVisibleAuraRestoresItsOriginalDegradationDeadlineAfterBriefHide() {
        var now = 1_000_000_000L
        var pending: Long? = null
        var requestedFrames = 0
        val owner = PlayerArtworkAuraMotionOwner({ requestedFrames++ }, {},
            { delay -> pending = now + delay * 1_000_000L }, { pending = null })
        owner.show()
        repeat(8) { owner.update(now, now - 100_000_000L, true, true, true) }
        val deadline = checkNotNull(pending)
        assertFalse(owner.motionEnabled)
        owner.hide()
        assertNull(pending)
        val hiddenFrames = requestedFrames
        now += 500_000_000L
        assertEquals(hiddenFrames, requestedFrames)
        owner.show()
        owner.update(now, now - 1L, true, true, true)
        assertEquals("The remaining degradation window lost its only wakeup", deadline, pending)
        now = checkNotNull(pending)
        owner.update(now, now - 1L, true, true, true)
        assertTrue(owner.motionEnabled)
        owner.hide()
        assertNull(pending)
    }
}
