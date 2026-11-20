package app.stemwise.ui.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.ui.createStemwiseComposeRule
import app.stemwise.ui.design.generated.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class GlyphRendererParityInstrumentationTest {
    @get:Rule val composeRule = createStemwiseComposeRule()

    @Test fun everyStemHasDistinctVisibleGeometryInBothRenderers() {
        val ids = app.stemwise.model.StemSourceCatalogue.sources.map { it.id } + "remaining"
        val roles = ids.map { app.stemwise.ui.sourcePartIconRole(it).icon }
        lateinit var painters: List<Painter>
        composeRule.setContent {
            val current = roles.map { rememberVectorPainter(StemwiseComposeGlyphs.icon(it)) }
            SideEffect { painters = current }
        }
        composeRule.runOnIdle {
            val atlas = StemwiseNativeGlyphAtlas()
            // Small-size raster identity matters too; distinct paths can collapse at mixer size.
            for (size in listOf(24, 68)) {
                val identities = hashSetOf<List<Int>>()
                roles.forEachIndexed { index, role ->
                    val native = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val compose = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    try {
                        atlas.draw(Canvas(native), role, size / 2f, size / 2f, size.toFloat(), Color.WHITE)
                        val bounds = Size(size.toFloat(), size.toFloat())
                        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr,
                            androidx.compose.ui.graphics.Canvas(compose.asImageBitmap()), bounds) {
                            with(painters[index]) { draw(bounds) }
                        }
                        val pixels = IntArray(size * size)
                        native.getPixels(pixels, 0, size, 0, 0, size, size)
                        assertTrue("Empty stem glyph: ${ids[index]} at $size px",
                            pixels.count { Color.alpha(it) > 0 } > 100f * size * size / (68 * 68))
                        assertTrue("Shared stem geometry: ${ids[index]} at $size px", identities.add(pixels.toList()))
                        var error = 0L
                        for (y in 0 until size) for (x in 0 until size) {
                            error += abs(Color.alpha(native.getPixel(x, y)) - Color.alpha(compose.getPixel(x, y)))
                        }
                        assertTrue("Renderer mismatch: ${ids[index]} at $size px ($error)", error < size.toLong() * size * 3)
                    } finally { native.recycle(); compose.recycle() }
                }
            }
        }
    }

    @Test
    fun nestedNoncommutingGroupsAndScaledStrokeMatchComposeAndSvgGeometry() {
        val role = StemwiseIconRole.PLAYER_PLAY
        val base = StemwiseGeneratedGlyphCatalog.glyph(role)
        val identity = StemwiseGlyphTransform(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val template = base.layers.first()
        val glyph = base.copy(layers = listOf(
            template.copy(order = 0, depth = 1, element = StemwiseGlyphElement.GROUP,
                transform = identity.copy(translationX = 16f, translationY = 4f, e = 16f, f = 4f)),
            template.copy(order = 1, depth = 2, element = StemwiseGlyphElement.GROUP,
                transform = identity.copy(scaleX = 1.5f, scaleY = 1.5f, rotation = 90f,
                    a = 0f, b = 1.5f, c = -1.5f, d = 0f)),
            template.copy(order = 2, depth = 3, element = StemwiseGlyphElement.PATH,
                pathData = "M0 0H4V3H0Z", fill = true, stroke = true, strokeWidth = .75f,
                strokeLineCap = StemwiseStrokeLineCap.BUTT, strokeLineJoin = StemwiseStrokeLineJoin.MITER,
                transform = identity.copy(translationX = 1f, translationY = 2f, e = 1f, f = 2f))
        ))
        lateinit var painter: Painter
        composeRule.setContent {
            val current = rememberVectorPainter(StemwiseComposeGlyphs.build(glyph))
            SideEffect { painter = current }
        }
        composeRule.runOnIdle {
            fun bitmap() = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            val native = bitmap()
            StemwiseNativeGlyphAtlas { if (it == role) glyph else StemwiseGeneratedGlyphCatalog.glyph(it) }
                .draw(Canvas(native), role, 96f, 96f, 192f, Color.BLACK)
            val compose = bitmap()
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(compose.asImageBitmap()), Size(192f, 192f)) {
                with(painter) { draw(Size(192f, 192f)) }
            }
            // Independent SVG geometry: translate(16 4) rotate(90) scale(1.5), then
            // translate(1 2), rect(0 0 4 3), stroke-width=.75. Its final rectangle is
            // x=8.5..13, y=5.5..11.5 with stroke width1.125. No adapter matrix is reused.
            val reference = bitmap()
            Canvas(reference).apply {
                scale(8f, 8f)
                drawRect(8.5f, 5.5f, 13f, 11.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = 1.125f; strokeJoin = Paint.Join.MITER
                })
            }
            try {
                fun matches(actual: Bitmap) {
                    var error = 0L
                    var visible = 0
                    for (y in 0 until 192) for (x in 0 until 192) {
                        val expected = Color.alpha(reference.getPixel(x, y))
                        val observed = Color.alpha(actual.getPixel(x, y))
                        error += abs(expected - observed)
                        if (observed > 0) visible++
                    }
                    assertTrue("Renderer emitted no glyph", visible > 500)
                    assertTrue("SVG alpha error=$error", error < 192L * 192)
                }
                matches(compose)
                matches(native)
            } finally { native.recycle(); compose.recycle(); reference.recycle() }
        }
    }
}
