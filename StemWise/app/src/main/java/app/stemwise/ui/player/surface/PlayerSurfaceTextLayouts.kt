package app.stemwise.ui.player.surface

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.ceil

/** Bounded shaping cache; a hit performs no Paint allocation or desired-width measurement. */
internal class PlayerSurfaceTextLayouts(private val maximumWidthPx: Int) {
    private data class Key(val value: String, val size: Int, val typeface: Typeface?,
        val letterSpacing: Int, val scaleX: Int, val fontFeatures: String?)
    private val layouts = object : LinkedHashMap<Key, StaticLayout>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, StaticLayout>?): Boolean = size > 48
    }

    fun clear() = layouts.clear()

    fun get(value: String, sourcePaint: TextPaint): StaticLayout {
        val key = Key(value, sourcePaint.textSize.toRawBits(), sourcePaint.typeface,
            sourcePaint.letterSpacing.toRawBits(), sourcePaint.textScaleX.toRawBits(), sourcePaint.fontFeatureSettings)
        layouts[key]?.let { it.paint.color = sourcePaint.color; return it }
        val paint = TextPaint(sourcePaint).apply { textAlign = Paint.Align.LEFT }
        val width = ceil(Layout.getDesiredWidth(value, paint).toDouble()).toInt().coerceIn(1, maximumWidthPx)
        return StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(1)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build().also { layouts[key] = it }
    }
}
