package app.stemwise.ui.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfaceColorPolicyTest {
    @Test
    fun ordinaryTransitionsReachBothEndpointsAndArtworkKeepsItsOwnCap() {
        val black = 0xff000000.toInt()
        val white = 0xffffffff.toInt()
        assertEquals(black, playerSurfaceInterpolateOpaqueColors(black, white, 0f))
        assertEquals(white, playerSurfaceInterpolateOpaqueColors(black, white, 1f))
        assertEquals(0xff808080.toInt(), playerSurfaceInterpolateOpaqueColors(black, white, .5f))
        val samples = (0..10).map { playerSurfaceInterpolateOpaqueColors(black, white, it / 10f) and 255 }
        assertTrue(samples.zipWithNext().all { (first, second) -> first < second })
        assertEquals(0xff333333.toInt(), playerSurfaceArtworkTint(black, white, 1f))
        assertEquals(playerSurfaceArtworkTint(black, white, .2f), playerSurfaceArtworkTint(black, white, 1f))
    }
}
