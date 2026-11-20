package app.stemwise.ui.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceMixerScrollPolicyTest {
    @Test fun viewportFitsOnlyCompleteRowsAndOnlyItsOffsetMoves() {
        assertEquals(5, playerSurfaceMixerVisibleLaneCount(17, 328f, 64f, 2f))
        assertEquals(3, playerSurfaceMixerVisibleLaneCount(3, 328f, 64f, 2f))
        assertEquals(792f, playerSurfaceMixerMaximumScrollPx(17, 64f, 2f, 328f), .001f)
        assertEquals(0f, playerSurfaceMixerMaximumScrollPx(5, 64f, 2f, 328f), .001f)
        val viewport = PlayerSurfaceBounds(20f, 80f, 400f, 408f)
        val last = PlayerSurfaceBounds(20f, 80f + 16 * 66f, 400f, 144f + 16 * 66f)
        assertEquals(PlayerSurfaceBounds(20f, 344f, 400f, 408f),
            playerSurfaceMixerScrolledBounds(last, viewport, 792f))
        assertEquals(0f, playerSurfaceMixerScrolledBounds(last, viewport, 0f).height, .001f)
        assertTrue(playerSurfaceMixerScrolledBounds(PlayerSurfaceBounds(20f,80f,400f,144f), viewport,0f).height > 0f)
    }

    @Test fun verticalAndHorizontalIntentsAreExclusiveBeforeGainAdmission() {
        assertEquals(PlayerSurfaceMixerDragIntent.PENDING, playerSurfaceMixerDragIntent(3f, 4f, 8f))
        assertEquals(PlayerSurfaceMixerDragIntent.SCROLL, playerSurfaceMixerDragIntent(4f, -30f, 8f))
        assertEquals(PlayerSurfaceMixerDragIntent.GAIN, playerSurfaceMixerDragIntent(30f, 4f, 8f))
        assertEquals(PlayerSurfaceMixerDragIntent.SCROLL, playerSurfaceMixerDragIntent(-8f, 9f, 8f))
        assertEquals(PlayerSurfaceMixerDragIntent.PENDING, playerSurfaceMixerDragIntent(9f, 8f, 8f))
    }
}
