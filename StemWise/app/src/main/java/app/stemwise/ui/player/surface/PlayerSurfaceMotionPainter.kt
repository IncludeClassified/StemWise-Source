package app.stemwise.ui.player.surface

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.stemwise.ui.design.StemwiseAuraContract
import kotlin.math.min
import kotlin.math.roundToInt

internal class PlayerSurfaceMotionPainter(
    private val density: Float,
    private val fillPaint: Paint,
    private val iconAtlas: PlayerSurfaceIconAtlas,
    private val primaryBright: Int,
    private val selectedShadow: Int
) {
    // Retained exact selected-state glyph blur bands. No per-frame mask allocation and no
    // positional ghost copies around asymmetric icons.
    private val selectedGlyphGlowFilters = arrayOf(
        BlurMaskFilter(24f * density, BlurMaskFilter.Blur.NORMAL),
        BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL),
        BlurMaskFilter(1f * density, BlurMaskFilter.Blur.NORMAL)
    )
    private val selectedGlyphGlowAlphas = floatArrayOf(
        StemwiseAuraContract.SELECTED_FAR_ALPHA,
        StemwiseAuraContract.SELECTED_NEAR_ALPHA,
        StemwiseAuraContract.SELECTED_CORE_ALPHA
    )
    private val selectedBodyShadowFilter = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
    fun drawSelectedGlyphGlow(
        canvas: Canvas,
        icon: PlayerSurfaceIcon,
        centerX: Float,
        centerY: Float,
        size: Float,
        contentAlpha: Float
    ) {
        if (size <= 0f || contentAlpha <= .001f) return
        // Exact 1/8/24dp selected-state blur contract. Each pass repaints the real glyph
        // silhouette; it cannot become a circle or a ring of displaced ghost icons.
        var pass = 0
        while (pass < selectedGlyphGlowFilters.size) {
            iconAtlas.draw(
                canvas = canvas,
                icon = icon,
                centerX = centerX,
                centerY = centerY,
                size = size,
                color = withAlpha(
                    primaryBright,
                    contentAlpha * selectedGlyphGlowAlphas[pass]
                ),
                maskFilter = selectedGlyphGlowFilters[pass]
            )
            pass++
        }
    }

    /** Attached selected-body shadow (0/8/12, violet at 32%); never a press-origin disc. */
    fun drawSelectedBodyShadow(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        contentAlpha: Float
    ) {
        if (right <= left || bottom <= top || contentAlpha <= .001f) return
        fillPaint.shader = null
        fillPaint.maskFilter = selectedBodyShadowFilter
        fillPaint.color = withAlpha(selectedShadow, contentAlpha * .32f)
        canvas.drawRoundRect(
            left,
            top + 8f * density,
            right,
            bottom + 8f * density,
            radius,
            radius,
            fillPaint
        )
        fillPaint.maskFilter = null
        fillPaint.alpha = 255
    }

    fun drawNativePressBloom(
        canvas: Canvas,
        frame: NativePressBloomFrame,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        contentAlpha: Float
    ) {
        if (!frame.visible || right <= left || bottom <= top || contentAlpha <= .001f) return
        val minimum = min(right - left, bottom - top)
        val alpha = frame.effectAlpha.coerceIn(0f, 1f) * contentAlpha
        if (minimum <= 0f || alpha <= .001f) return
        fun drawBodyLayer(insetFraction: Float, color: Int, layerAlpha: Float) {
            val inset = minimum * insetFraction
            val layerLeft = left + inset
            val layerTop = top + inset
            val layerRight = right - inset
            val layerBottom = bottom - inset
            if (layerRight <= layerLeft || layerBottom <= layerTop) return
            val radius = min(layerRight - layerLeft, layerBottom - layerTop) * .5f
            fillPaint.shader = null
            fillPaint.maskFilter = null
            fillPaint.color = withAlpha(color, layerAlpha)
            canvas.drawRoundRect(
                layerLeft,
                layerTop,
                layerRight,
                layerBottom,
                radius,
                radius,
                fillPaint
            )
        }
        drawBodyLayer(0f, StemwiseAuraContract.LilacArgb, alpha * .08f)
        drawBodyLayer(0f, StemwiseAuraContract.WhiteArgb, alpha * .04f)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(),
        Color.red(color), Color.green(color), Color.blue(color))
}
