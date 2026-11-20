package app.stemwise.ui.player.surface

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.ui.graphics.asAndroidColorFilter
import app.stemwise.ui.artworkAuraRasterDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.stemwise.ui.artworkAuraLuminanceColorFilter
import app.stemwise.ui.artworkAuraMaskBlurRadiusDp
import app.stemwise.ui.artworkAuraOpacity
import app.stemwise.ui.ARTWORK_AURA_FAR_BASE_OPACITY
import app.stemwise.ui.ARTWORK_AURA_NEAR_BASE_OPACITY
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Retains bounded aura masks; live frames change alpha/luminance and reuse their pixels. */
internal class PlayerArtworkAuraCache(
    private val density: Float,
    private val onReady: () -> Unit
) {
    private data class Request(val identity: Any?, val revision: Long)
    private data class Texture(val bitmap: Bitmap, val scale: Float, val width: Int, val height: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = arrayOfNulls<Request>(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT)
    private val jobs = arrayOfNulls<Job>(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT)
    private val ready = arrayOfNulls<Array<Texture>>(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT)
    private val artworkAuraBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artworkAuraBitmapSource = Rect()
    private val artworkAuraBitmapDestination = RectF()

    /** Frozen palette and slot owner; replacement never mutates a texture used by a display list. */
    fun prepare(slot: Int, identity: Any?, revision: Long, colors: IntArray) {
        val request = Request(identity, revision)
        if (requests[slot] == request) return
        requests[slot] = request
        jobs[slot]?.cancel()
        val palette = colors.copyOf()
        jobs[slot] = scope.launch {
            val textures = withContext(artworkAuraRasterDispatcher) {
                Array(ARTWORK_AURA_PROFILE_COUNT * 2) { index ->
                    ensureActive()
                    rasterize(palette, index / 2, far = index % 2 == 0)
                }.also { ensureActive() }
            }
            if (requests[slot] === request) {
                ready[slot] = textures
                onReady()
            }
        }
    }

    fun clearSlot(slot: Int) {
        jobs[slot]?.cancel()
        jobs[slot] = null
        requests[slot] = null
        ready[slot] = null
    }

    private fun rasterize(colors: IntArray, cornerProfile: Int, far: Boolean): Texture {
        val endpointReachDp = if (far) 56f else 18f
        val cornerRadiusDp = playerSurfaceArtworkAuraProfileCornerRadiusDp(cornerProfile)
        val contentPx = ARTWORK_AURA_CACHE_CONTENT_DP * density
        val paddingPx = playerSurfaceArtworkAuraRasterPaddingDp(endpointReachDp) * density
        val logicalEdge = contentPx + paddingPx * 2f
        val scale = min(1f, ARTWORK_AURA_CACHE_EDGE_PX / logicalEdge.coerceAtLeast(1f))
        val edge = ceil(logicalEdge * scale).toInt().coerceIn(1, ARTWORK_AURA_CACHE_EDGE_PX)
        val bitmap = Bitmap.createBitmap(ARTWORK_AURA_CACHE_EDGE_PX,
            ARTWORK_AURA_CACHE_EDGE_PX, Bitmap.Config.ARGB_8888)
        val center = edge * .5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(center, center,
                intArrayOf(playerSurfaceArtworkAuraEmissionColor(colors[0]),
                    playerSurfaceArtworkAuraEmissionColor(colors[1]),
                    playerSurfaceArtworkAuraEmissionColor(colors[2]),
                    playerSurfaceArtworkAuraEmissionColor(colors[0])), null)
            maskFilter = BlurMaskFilter((artworkAuraMaskBlurRadiusDp(endpointReachDp) *
                density * scale).coerceAtLeast(.5f), BlurMaskFilter.Blur.OUTER)
        }
        val half = contentPx * scale * .5f
        val radius = cornerRadiusDp.coerceAtLeast(0f) * density * scale
        Canvas(bitmap).drawRoundRect(center - half, center - half, center + half, center + half,
            radius, radius, paint)
        return Texture(bitmap, scale, edge, edge)
    }

    /** All supported API levels reuse bounded, fixed-softness blur endpoints. */
    fun drawPrecomposedArtworkAuraBitmaps(
        canvas: Canvas,
        slot: Int,
        centerX: Float,
        centerY: Float,
        liveWidth: Float,
        liveHeight: Float,
        liveCornerRadiusPx: Float,
        opacityOffset: Float,
        luminanceOffset: Float,
        contentAlpha: Float
    ) {
        if (slot !in ready.indices || liveWidth <= 0f || liveHeight <= 0f || ready[slot] == null) return
        val fullProfileAlpha = playerSurfaceArtworkAuraFullProfileAlpha(
            liveCornerRadiusPx,
            density
        )
        artworkAuraBitmapPaint.colorFilter =
            artworkAuraLuminanceColorFilter(luminanceOffset).asAndroidColorFilter()
        var cornerProfile = 0
        while (cornerProfile < ARTWORK_AURA_PROFILE_COUNT) {
            val profileAlpha = if (cornerProfile == ARTWORK_AURA_PROFILE_COMPACT) {
                1f - fullProfileAlpha
            } else {
                fullProfileAlpha
            }
            if (profileAlpha > .001f) {
                val profileCornerRadiusDp =
                    playerSurfaceArtworkAuraProfileCornerRadiusDp(cornerProfile)
                drawPrecomposedArtworkAuraBand(
                    canvas = canvas,
                    slot = slot,
                    cornerProfile = cornerProfile,
                    cornerRadiusDp = profileCornerRadiusDp,
                    far = true,
                    centerX = centerX,
                    centerY = centerY,
                    liveWidth = liveWidth,
                    liveHeight = liveHeight,
                    baseReachDp = 56f,
                    opacity = artworkAuraOpacity(
                        ARTWORK_AURA_FAR_BASE_OPACITY,
                        opacityOffset
                    ) * contentAlpha * profileAlpha
                )
                drawPrecomposedArtworkAuraBand(
                    canvas = canvas,
                    slot = slot,
                    cornerProfile = cornerProfile,
                    cornerRadiusDp = profileCornerRadiusDp,
                    far = false,
                    centerX = centerX,
                    centerY = centerY,
                    liveWidth = liveWidth,
                    liveHeight = liveHeight,
                    baseReachDp = 18f,
                    opacity = artworkAuraOpacity(
                        ARTWORK_AURA_NEAR_BASE_OPACITY,
                        opacityOffset
                    ) * contentAlpha * profileAlpha
                )
            }
            cornerProfile++
        }
        artworkAuraBitmapPaint.colorFilter = null
    }

    fun drawPrecomposedArtworkAuraBand(
        canvas: Canvas,
        slot: Int,
        cornerProfile: Int,
        cornerRadiusDp: Float,
        far: Boolean,
        centerX: Float,
        centerY: Float,
        liveWidth: Float,
        liveHeight: Float,
        baseReachDp: Float,
        opacity: Float
    ) {
        val texture = ready[slot]?.getOrNull(cornerProfile * 2 + if (far) 0 else 1) ?: return
        drawPrecomposedArtworkAuraBitmap(
            canvas = canvas,
            bitmap = texture.bitmap,
            cacheScale = texture.scale,
            cacheWidth = texture.width,
            cacheHeight = texture.height,
            centerX = centerX,
            centerY = centerY,
            liveWidth = liveWidth,
            liveHeight = liveHeight,
            endpointReachDp = baseReachDp,
            cornerRadiusDp = cornerRadiusDp,
            opacity = opacity
        )
    }

    fun drawPrecomposedArtworkAuraBitmap(
        canvas: Canvas,
        bitmap: Bitmap?,
        cacheScale: Float,
        cacheWidth: Int,
        cacheHeight: Int,
        centerX: Float,
        centerY: Float,
        liveWidth: Float,
        liveHeight: Float,
        endpointReachDp: Float,
        cornerRadiusDp: Float,
        opacity: Float
    ) {
        if (
            bitmap == null || bitmap.isRecycled || cacheScale <= 0f ||
            cacheWidth <= 0 || cacheHeight <= 0 || liveWidth <= 0f || liveHeight <= 0f ||
            opacity <= .001f
        ) return
        val geometry = playerSurfaceArtworkAuraNinePatchGeometry(
            cacheScale = cacheScale,
            endpointReachDp = endpointReachDp,
            cornerRadiusDp = cornerRadiusDp,
            density = density
        )
        val sourceLeft = 0
        val sourceTop = 0
        val sourceRight = cacheWidth
        val sourceBottom = cacheHeight
        val sourceHorizontalCap = geometry.sourceCapPx.roundToInt()
            .coerceIn(1, (sourceRight - sourceLeft) / 2)
        val sourceVerticalCap = geometry.sourceCapPx.roundToInt()
            .coerceIn(1, (sourceBottom - sourceTop) / 2)
        val sourceX1 = sourceLeft + sourceHorizontalCap
        val sourceX2 = sourceRight - sourceHorizontalCap
        val sourceY1 = sourceTop + sourceVerticalCap
        val sourceY2 = sourceBottom - sourceVerticalCap

        val reach = geometry.endpointReachPx
        val destinationLeft = centerX - liveWidth * .5f - reach
        val destinationTop = centerY - liveHeight * .5f - reach
        val destinationRight = centerX + liveWidth * .5f + reach
        val destinationBottom = centerY + liveHeight * .5f + reach
        val destinationHorizontalCap = geometry.destinationCapPx
            .coerceAtMost((destinationRight - destinationLeft) * .5f)
        val destinationVerticalCap = geometry.destinationCapPx
            .coerceAtMost((destinationBottom - destinationTop) * .5f)
        val destinationX1 = destinationLeft + destinationHorizontalCap
        val destinationX2 = destinationRight - destinationHorizontalCap
        val destinationY1 = destinationTop + destinationVerticalCap
        val destinationY2 = destinationBottom - destinationVerticalCap

        artworkAuraBitmapPaint.alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        val save = canvas.save()
        drawArtworkAuraPatch(canvas, bitmap, sourceLeft, sourceTop, sourceX1, sourceY1,
            destinationLeft, destinationTop, destinationX1, destinationY1)
        drawArtworkAuraPatch(canvas, bitmap, sourceX1, sourceTop, sourceX2, sourceY1,
            destinationX1, destinationTop, destinationX2, destinationY1)
        drawArtworkAuraPatch(canvas, bitmap, sourceX2, sourceTop, sourceRight, sourceY1,
            destinationX2, destinationTop, destinationRight, destinationY1)
        drawArtworkAuraPatch(canvas, bitmap, sourceLeft, sourceY1, sourceX1, sourceY2,
            destinationLeft, destinationY1, destinationX1, destinationY2)
        drawArtworkAuraPatch(canvas, bitmap, sourceX1, sourceY1, sourceX2, sourceY2,
            destinationX1, destinationY1, destinationX2, destinationY2)
        drawArtworkAuraPatch(canvas, bitmap, sourceX2, sourceY1, sourceRight, sourceY2,
            destinationX2, destinationY1, destinationRight, destinationY2)
        drawArtworkAuraPatch(canvas, bitmap, sourceLeft, sourceY2, sourceX1, sourceBottom,
            destinationLeft, destinationY2, destinationX1, destinationBottom)
        drawArtworkAuraPatch(canvas, bitmap, sourceX1, sourceY2, sourceX2, sourceBottom,
            destinationX1, destinationY2, destinationX2, destinationBottom)
        drawArtworkAuraPatch(canvas, bitmap, sourceX2, sourceY2, sourceRight, sourceBottom,
            destinationX2, destinationY2, destinationRight, destinationBottom)
        canvas.restoreToCount(save)
    }

    fun drawArtworkAuraPatch(
        canvas: Canvas,
        bitmap: Bitmap,
        sourceLeft: Int,
        sourceTop: Int,
        sourceRight: Int,
        sourceBottom: Int,
        destinationLeft: Float,
        destinationTop: Float,
        destinationRight: Float,
        destinationBottom: Float
    ) {
        if (
            sourceRight <= sourceLeft || sourceBottom <= sourceTop ||
            destinationRight <= destinationLeft || destinationBottom <= destinationTop
        ) return
        artworkAuraBitmapSource.set(sourceLeft, sourceTop, sourceRight, sourceBottom)
        artworkAuraBitmapDestination.set(
            destinationLeft,
            destinationTop,
            destinationRight,
            destinationBottom
        )
        canvas.drawBitmap(
            bitmap,
            artworkAuraBitmapSource,
            artworkAuraBitmapDestination,
            artworkAuraBitmapPaint
        )
    }

    fun recycle() {
        // RenderThread may still replay immutable textures. Release ownership without recycling.
        for (slot in ready.indices) clearSlot(slot)
    }

    private companion object {
        const val ARTWORK_AURA_CACHE_CONTENT_DP = 112f
        const val ARTWORK_AURA_CACHE_EDGE_PX = 256
        const val ARTWORK_AURA_PROFILE_COMPACT = 0
        const val ARTWORK_AURA_PROFILE_COUNT = 2
    }
}
