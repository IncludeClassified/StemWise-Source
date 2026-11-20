package app.stemwise.ui.player.surface

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceRetainedLayerPolicyTest {
    @Test
    fun positionOnlyMoveKeepsExistingRecording() {
        assertFalse(
            playerSurfaceRetainedLayerSizeChanged(
                currentLeft = 10,
                currentTop = 20,
                currentRight = 210,
                currentBottom = 120,
                nextLeft = 35,
                nextTop = 55,
                nextRight = 235,
                nextBottom = 155
            )
        )
    }

    @Test
    fun widthChangeRequiresFreshRecording() {
        assertTrue(
            playerSurfaceRetainedLayerSizeChanged(
                currentLeft = 10,
                currentTop = 20,
                currentRight = 210,
                currentBottom = 120,
                nextLeft = 10,
                nextTop = 20,
                nextRight = 211,
                nextBottom = 120
            )
        )
    }

    @Test
    fun heightChangeRequiresFreshRecording() {
        assertTrue(
            playerSurfaceRetainedLayerSizeChanged(
                currentLeft = 10,
                currentTop = 20,
                currentRight = 210,
                currentBottom = 120,
                nextLeft = 10,
                nextTop = 20,
                nextRight = 210,
                nextBottom = 121
            )
        )
    }
}
