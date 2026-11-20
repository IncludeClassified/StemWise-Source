package app.stemwise.ui.player.surface

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.stemwise.ui.theme.COMPACT_PROGRESS_FILL_ARGB
import app.stemwise.ui.theme.PRIMARY_PURPLE_ARGB as PRIMARY_PURPLE
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Paints only supplied progress and geometry; owner, transport and animation scheduling stay in the View. */
internal class PlayerSurfaceMiniProgressPainter(
    private val density: Float,
    private val fillPaint: Paint,
    private val strokePaint: Paint,
    private val path: Path
) {
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float,
        progress: Float, alpha: Float, phase: Float) {
            val fillRight = left + (right - left) * progress.coerceIn(0f, 1f)
            if (fillRight <= left + .5f) {
                return
            }
            if (progress >= 1f) {
                fillPaint.color = withAlpha(COMPACT_PROGRESS_FILL_ARGB, alpha)
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
                return
            }

            val clipSave = canvas.save()
            path.reset()
            path.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            val amplitude = min(MINI_LIQUID_MAX_AMPLITUDE_DP * density, (fillRight - left) * .2f)
            path.reset()
            path.moveTo(left, top)
            var sample = 0
            while (sample < MINI_LIQUID_SAMPLE_COUNT) {
                val fraction = sample.toFloat() / (MINI_LIQUID_SAMPLE_COUNT - 1)
                val y = top + (bottom - top) * fraction
                val waveX = fillRight + sin(phase + fraction * Math.PI.toFloat() * 2f) * amplitude
                path.lineTo(waveX, y)
                sample++
            }
            path.lineTo(left, bottom)
            path.close()
            fillPaint.color = withAlpha(COMPACT_PROGRESS_FILL_ARGB, alpha)
            canvas.drawPath(path, fillPaint)

            path.reset()
            sample = 0
            while (sample < MINI_LIQUID_SAMPLE_COUNT) {
                val fraction = sample.toFloat() / (MINI_LIQUID_SAMPLE_COUNT - 1)
                val y = top + (bottom - top) * fraction
                val waveX = fillRight + sin(phase + fraction * Math.PI.toFloat() * 2f) * amplitude
                if (sample == 0) path.moveTo(waveX, y) else path.lineTo(waveX, y)
                sample++
            }
            strokePaint.shader = null
            strokePaint.strokeWidth = MINI_LIQUID_EDGE_STROKE_DP * density
            strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
            canvas.drawPath(path, strokePaint)
            canvas.restoreToCount(clipSave)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
}
