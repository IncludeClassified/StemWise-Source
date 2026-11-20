package app.stemwise.ui.player.surface

import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt

internal class PlayerSurfaceChromePainter(
    private val density: Float,
    private val iconAtlas: PlayerSurfaceIconAtlas,
    private val motion: PlayerSurfaceMotionPainter,
    private val textPrimary: Int,
    private val primaryBright: Int
) {
    fun drawHeader(canvas: Canvas, geometry: PlayerSurfaceRouteGeometry, alpha: Float,
        collapse: NativePressBloomFrame, info: NativePressBloomFrame) {
        if (alpha <= .001f) return
        motion.drawNativePressBloom(canvas, collapse, geometry.collapseLeft, geometry.collapseTop,
            geometry.collapseRight, geometry.collapseBottom, alpha)
        motion.drawNativePressBloom(canvas, info, geometry.infoLeft, geometry.infoTop,
            geometry.infoRight, geometry.infoBottom, alpha)
        iconAtlas.draw(canvas, PlayerSurfaceIcon.COLLAPSE,
            (geometry.collapseLeft + geometry.collapseRight) * .5f,
            (geometry.collapseTop + geometry.collapseBottom) * .5f, 24f * density, withAlpha(textPrimary, alpha))
        iconAtlas.draw(canvas, PlayerSurfaceIcon.INFO,
            (geometry.infoLeft + geometry.infoRight) * .5f,
            (geometry.infoTop + geometry.infoBottom) * .5f, 24f * density, withAlpha(primaryBright, alpha))
    }

    fun drawMiniPlay(canvas: Canvas, geometry: PlayerSurfaceRouteGeometry, alpha: Float,
        playing: Boolean, press: NativePressBloomFrame?) {
        if (alpha <= .001f || geometry.miniPlayRight <= geometry.miniPlayLeft) return
        val x = (geometry.miniPlayLeft + geometry.miniPlayRight) * .5f
        val y = (geometry.miniPlayTop + geometry.miniPlayBottom) * .5f
        val icon = if (playing) PlayerSurfaceIcon.PAUSE else PlayerSurfaceIcon.PLAY
        if (press != null) motion.drawNativePressBloom(canvas, press, geometry.miniPlayLeft,
            geometry.miniPlayTop, geometry.miniPlayRight, geometry.miniPlayBottom, alpha)
        motion.drawSelectedGlyphGlow(canvas, icon, x, y, 24f * density,
            alpha * playerSurfaceSelectedAuraProgress(selectedProgress = 1f))
        iconAtlas.draw(canvas, icon, x, y, 24f * density, withAlpha(primaryBright, alpha))
    }

    private fun withAlpha(color: Int, alpha: Float) = Color.argb(
        (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
}
