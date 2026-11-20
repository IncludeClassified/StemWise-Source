package app.stemwise.ui.player.surface

import org.junit.Assert.*
import org.junit.Test

class PlayerSurfaceRouteGeometryTest {
    @Test fun compactAndExpandedRouteKeepTheirPaintedControlsInsideTheSharedHitGeometry() {
        val geometry = PlayerSurfaceRouteGeometry()
        geometry.rebuild(800, 1600, 2f, 0f, 148f, 224f, 168f, 80f)
        assertEquals(0f, geometry.pagerTop, 0f)
        assertEquals(96f, geometry.miniPlayRight - geometry.miniPlayLeft, 0f)
        assertTrue(geometry.miniPlayHitBounds.left <= geometry.miniPlayLeft)
        assertTrue(geometry.miniPlayHitBounds.right >= geometry.miniPlayRight)
        assertTrue(geometry.miniPlayHitBounds.right <= 800f)
        geometry.rebuildHeaderHits(800, 2f, 144f, 392f, 408f, 656f)
        assertTrue(geometry.collapseHitBounds.left <= geometry.collapseLeft)
        assertTrue(geometry.collapseHitBounds.right >= geometry.collapseRight)
        assertTrue(geometry.collapseHitBounds.right <= geometry.sourceOriginalHitBounds.left)
        assertTrue(geometry.sourceOriginalHitBounds.right <= geometry.sourceStemsHitBounds.left)
        assertTrue(geometry.sourceStemsHitBounds.right <= geometry.infoHitBounds.left)
        assertTrue(geometry.infoHitBounds.left <= geometry.infoLeft)
        assertTrue(geometry.infoHitBounds.right >= geometry.infoRight)
        geometry.rebuild(800, 1600, 2f, 1f, 148f, 224f, 168f, 80f)
        assertEquals(148f, geometry.pagerTop, 0f)
        assertEquals(372f, geometry.pagerBottom, 0f)
        assertEquals(800f, geometry.pagerRight, 0f)
    }
}
