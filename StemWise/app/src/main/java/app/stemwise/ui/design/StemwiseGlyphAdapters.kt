package app.stemwise.ui.design

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.MaskFilter
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.stemwise.ui.design.generated.StemwiseGeneratedGlyphCatalog
import app.stemwise.ui.design.generated.StemwiseGlyph
import app.stemwise.ui.design.generated.StemwiseGlyphElement
import app.stemwise.ui.design.generated.StemwiseGlyphLayer
import app.stemwise.ui.design.generated.StemwiseGlyphTransform
import app.stemwise.ui.design.generated.StemwiseIconRole
import app.stemwise.ui.design.generated.StemwiseStrokeLineCap
import app.stemwise.ui.design.generated.StemwiseStrokeLineJoin

/**
 * Cached Compose adapter over the renderer-neutral, generated glyph catalog.
 *
 * Parsing happens once per semantic role. Drawing an icon never reads JSON/SVG and never rebuilds
 * path geometry. The catalog's 24 dp viewport already contains its 20 dp optical envelope.
 */
internal object StemwiseComposeGlyphs {
    private val cache = arrayOfNulls<ImageVector>(StemwiseIconRole.entries.size)

    fun icon(role: StemwiseIconRole): ImageVector {
        cache[role.ordinal]?.let { return it }
        return synchronized(cache) {
            cache[role.ordinal] ?: build(StemwiseGeneratedGlyphCatalog.glyph(role)).also {
                cache[role.ordinal] = it
            }
        }
    }

    internal fun build(glyph: StemwiseGlyph): ImageVector {
        val builder = ImageVector.Builder(
            name = "StemWise:${glyph.role.wireName}",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = glyph.rtlMirror
        )
        var openGroupDepth = 0
        glyph.layers.forEach { layer ->
            while (openGroupDepth >= layer.depth) {
                builder.clearGroup()
                openGroupDepth -= 1
            }
            if (layer.element == StemwiseGlyphElement.GROUP) {
                builder.addStemwiseGroup(layer)
                openGroupDepth = layer.depth
            } else {
                val ownsTransform = !layer.transform.isIdentity()
                if (ownsTransform) builder.addStemwiseGroup(layer)
                builder.addPath(
                    pathData = PathParser().parsePathString(layer.pathData).toNodes(),
                    pathFillType = PathFillType.NonZero,
                    name = "layer-${layer.order}-${layer.element.name.lowercase()}",
                    fill = if (layer.fill) SolidColor(Color.Black) else null,
                    stroke = if (layer.stroke) SolidColor(Color.Black) else null,
                    strokeLineWidth = layer.strokeWidth,
                    strokeLineCap = layer.strokeLineCap.compose,
                    strokeLineJoin = layer.strokeLineJoin.compose
                )
                if (ownsTransform) builder.clearGroup()
            }
        }
        repeat(openGroupDepth) { builder.clearGroup() }
        return builder.build()
    }

    private fun ImageVector.Builder.addStemwiseGroup(layer: StemwiseGlyphLayer) {
        val value = layer.transform
        addGroup(
            name = "transform-${layer.order}",
            rotate = value.rotation,
            pivotX = value.pivotX,
            pivotY = value.pivotY,
            scaleX = value.scaleX,
            scaleY = value.scaleY,
            translationX = value.translationX,
            translationY = value.translationY
        )
    }
}

private val StemwiseStrokeLineCap.compose: StrokeCap
    get() = when (this) {
        StemwiseStrokeLineCap.BUTT -> StrokeCap.Butt
        StemwiseStrokeLineCap.ROUND -> StrokeCap.Round
        StemwiseStrokeLineCap.SQUARE -> StrokeCap.Square
    }

private val StemwiseStrokeLineJoin.compose: StrokeJoin
    get() = when (this) {
        StemwiseStrokeLineJoin.MITER -> StrokeJoin.Miter
        StemwiseStrokeLineJoin.ROUND -> StrokeJoin.Round
        StemwiseStrokeLineJoin.BEVEL -> StrokeJoin.Bevel
    }

private fun StemwiseGlyphTransform.isIdentity(): Boolean =
    translationX == 0f && translationY == 0f && scaleX == 1f && scaleY == 1f &&
        rotation == 0f

private data class StemwiseNativeLayer(
    val path: Path,
    val transform: Matrix,
    val fill: Boolean,
    val stroke: Boolean,
    val strokeWidth: Float,
    val cap: Paint.Cap,
    val join: Paint.Join
)

/**
 * Cached Android Canvas adapter used by [app.stemwise.ui.player.surface.PlayerSurfaceView].
 * Paths and transforms are materialized once; the native player's frame loop remains the only
 * owner of pointer-frequency player pixels.
 */
internal class StemwiseNativeGlyphAtlas(
    glyphProvider: (StemwiseIconRole) -> StemwiseGlyph = StemwiseGeneratedGlyphCatalog::glyph
) {
    private val glyphs = Array(StemwiseIconRole.entries.size) { glyphProvider(StemwiseIconRole.entries[it]) }
    private val roles = Array(StemwiseIconRole.entries.size) { index ->
        nativeLayers(glyphs[index])
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun draw(
        canvas: Canvas,
        role: StemwiseIconRole,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int,
        mirrorForRtl: Boolean = false,
        maskFilter: MaskFilter? = null
    ) {
        if (size <= 0f) return
        val glyph = glyphs[role.ordinal]
        val save = canvas.save()
        canvas.translate(centerX - size * .5f, centerY - size * .5f)
        val scale = size / 24f
        canvas.scale(scale, scale)
        if (mirrorForRtl && glyph.rtlMirror) {
            canvas.translate(24f, 0f)
            canvas.scale(-1f, 1f)
        }
        fillPaint.maskFilter = maskFilter
        strokePaint.maskFilter = maskFilter
        try {
            roles[role.ordinal].forEach { layer ->
                val layerSave = canvas.save()
                canvas.concat(layer.transform)
                try {
                    if (layer.fill) {
                        fillPaint.color = color
                        canvas.drawPath(layer.path, fillPaint)
                    }
                    if (layer.stroke) {
                        strokePaint.color = color
                        strokePaint.strokeWidth = layer.strokeWidth
                        strokePaint.strokeCap = layer.cap
                        strokePaint.strokeJoin = layer.join
                        canvas.drawPath(layer.path, strokePaint)
                    }
                } finally { canvas.restoreToCount(layerSave) }
            }
        } finally {
            fillPaint.maskFilter = null
            strokePaint.maskFilter = null
            canvas.restoreToCount(save)
        }
    }

    private fun nativeLayers(glyph: StemwiseGlyph): List<StemwiseNativeLayer> {
        val groupMatrices = arrayOfNulls<Matrix>(8)
        return buildList {
            glyph.layers.forEach { layer ->
                if (layer.element == StemwiseGlyphElement.GROUP) {
                    val parent = groupMatrices.getOrNull(layer.depth - 1)
                    groupMatrices[layer.depth] = composedMatrix(parent, layer.transform)
                    for (index in layer.depth + 1 until groupMatrices.size) {
                        groupMatrices[index] = null
                    }
                } else {
                    val parsed = PathParser()
                        .parsePathString(layer.pathData)
                        .toPath()
                        .asAndroidPath()
                    val parent = groupMatrices.getOrNull(layer.depth - 1)
                    val matrix = composedMatrix(parent, layer.transform)
                    add(
                        StemwiseNativeLayer(
                            path = parsed,
                            transform = matrix,
                            fill = layer.fill,
                            stroke = layer.stroke,
                            strokeWidth = layer.strokeWidth,
                            cap = layer.strokeLineCap.android,
                            join = layer.strokeLineJoin.android
                        )
                    )
                }
            }
        }
    }

    private fun composedMatrix(parent: Matrix?, transform: StemwiseGlyphTransform): Matrix {
        val own = Matrix().apply {
            setValues(
                floatArrayOf(
                    transform.a,
                    transform.c,
                    transform.e,
                    transform.b,
                    transform.d,
                    transform.f,
                    0f,
                    0f,
                    1f
                )
            )
        }
        return if (parent == null) own else Matrix(parent).apply { preConcat(own) }
    }
}

private val StemwiseStrokeLineCap.android: Paint.Cap
    get() = when (this) {
        StemwiseStrokeLineCap.BUTT -> Paint.Cap.BUTT
        StemwiseStrokeLineCap.ROUND -> Paint.Cap.ROUND
        StemwiseStrokeLineCap.SQUARE -> Paint.Cap.SQUARE
    }

private val StemwiseStrokeLineJoin.android: Paint.Join
    get() = when (this) {
        StemwiseStrokeLineJoin.MITER -> Paint.Join.MITER
        StemwiseStrokeLineJoin.ROUND -> Paint.Join.ROUND
        StemwiseStrokeLineJoin.BEVEL -> Paint.Join.BEVEL
    }
