package app.stemwise.ui

import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.ui.player.surface.PlayerSurfaceLayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mounted chrome and native bounds are covered by DesignSystemComposeInstrumentationTest and
 * PlayerSurfaceSemanticsAccessibilityTest; only the pure direction mapping belongs here. */
class StemwiseAppChromeSourcePolicyTest {
    @Test fun nativePlayerReceivesTheOwnedPhysicalDirection() {
        assertEquals(PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT,
            nativePlayerSurfaceLayoutDirection(LayoutDirection.Ltr))
        assertEquals(PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT,
            nativePlayerSurfaceLayoutDirection(LayoutDirection.Rtl))
    }
}
