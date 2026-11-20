package app.stemwise.ui.player.surface

import org.junit.Assert.*
import org.junit.Test

class PlayerSurfaceSemanticGeometryProjectionTest {
    @Test fun projectionUsesRenderedBoundsAndCompactRouteRetiresHiddenControls() {
        val route = PlayerSurfaceRouteGeometry().apply {
            rebuild(800, 1600, 2f, 1f, 148f, 224f, 168f, 80f)
            rebuildHeaderHits(800, 2f, 144f, 392f, 408f, 656f)
        }
        val artwork = PlayerSurfaceBounds(50f, 160f, 250f, 360f)
        val identity = PlayerSurfaceBounds(300f, 170f, 600f, 270f)
        val seeker = PlayerSurfaceBounds(100f, 700f, 700f, 800f)
        val buttons = FloatArray(20) { index ->
            when (index % 4) { 0 -> index / 4 * 100f; 1 -> 900f; 2 -> index / 4 * 100f + 80f; else -> 980f }
        }
        val rows = floatArrayOf(100f, 420f, 700f, 480f, 100f, 580f, 700f, 640f)
        val registry = PlayerSurfaceControlGeometry().apply {
            buttons.copyInto(transport.hit)
            buttons.copyInto(tools.hit, endIndex = 16)
            rows.copyInto(sliders)
            rows.copyInto(mute.hit)
            rows.copyInto(solo.hit)
        }
        fun project(compact: Boolean) = checkNotNull(registry.projectSemantics(800, 1600, compact,
            route, artwork, identity, seeker, 2,
            PlayerSurfaceBounds(100f, 400f, 700f, 600f), 12f, 20f))
        val expanded = project(false)
        assertEquals(artwork, expanded.artwork)
        assertEquals(PlayerSurfaceBounds(300f, 170f, 600f, 220f), expanded.title)
        assertEquals(PlayerSurfaceBounds(300f, 220f, 600f, 270f), expanded.artist)
        assertEquals(seeker, expanded.seeker)
        assertEquals(PlayerSurfaceBounds(100f, 580f, 700f, 600f), expanded.mixerLaneAt(1).volume)
        assertEquals(PlayerSurfaceBounds(200f, 900f, 280f, 980f), expanded.playPause)
        registry.transport.hit[8] = 250f
        assertEquals(250f, project(false).playPause.left)
        val compact = project(true)
        assertEquals(artwork, compact.artwork)
        assertEquals(route.miniPlayHitBounds, compact.miniPlayPause)
        assertEquals(0f, compact.seeker.width, 0f)
        assertEquals(0f, compact.playPause.width, 0f)
        assertEquals(0, compact.mixerLaneCount)
        assertEquals(0f, compact.mixerScrollMaximumPx, 0f)
    }
}
