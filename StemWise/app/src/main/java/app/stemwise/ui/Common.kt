package app.stemwise.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.Choreographer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toBitmap
import app.stemwise.artwork.ArtworkDiskCacheIdentity
import app.stemwise.artwork.artworkDiskCacheIdentity
import app.stemwise.artwork.artworkMemoryCacheKey
import app.stemwise.model.Song
import app.stemwise.ui.theme.Coral
import app.stemwise.ui.theme.Ink
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.Violet
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.semanticSpec
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import java.io.File
import java.lang.ref.WeakReference
import app.stemwise.BuildConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Every bounded one-line label gets the same overflow fallback; it is inert until text clips. */
@Composable
internal fun Modifier.stemwiseMarquee(enabled: Boolean = true): Modifier =
    if (enabled && LocalStemwiseMotion.current.enabled) {
        basicMarquee(iterations = Int.MAX_VALUE, velocity = 20.dp)
    } else {
        this
    }

internal fun commonArtworkDiskCacheIdentity(song: Song): ArtworkDiskCacheIdentity? =
    song.artworkSource?.let { source ->
        artworkDiskCacheIdentity(source, song.mediaRevision, song.id)
    }

internal data class ArtworkAuraPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val dominant: Color = primary
)

internal val MissingArtworkAuraPalette = ArtworkAuraPalette(
    primary = PrimaryPurple,
    secondary = Violet,
    tertiary = Coral
)

internal object ArtworkAuraPaletteCache {
    private val entries = LruCache<String, ArtworkAuraPalette>(96)
    private val observers = HashMap<
        String,
        WeakReference<androidx.compose.runtime.MutableState<ArtworkAuraPalette?>>
    >()

    @Synchronized
    fun get(key: String): ArtworkAuraPalette? = entries.get(key)

    /**
     * Observes only [key]. A global cache revision makes every decoded Library thumbnail
     * invalidate the compact player, even when its artwork owner did not change.
     */
    @Synchronized
    fun observe(key: String): androidx.compose.runtime.State<ArtworkAuraPalette?> =
        observers[key]?.get() ?: androidx.compose.runtime.mutableStateOf(entries.get(key)).also { state ->
            // Subscription lifetime belongs to the State, not an earlier equal String instance.
            observers.entries.removeAll { it.value.get() == null }
            observers[key] = WeakReference(state)
        }

    @Synchronized
    fun deriveAndPut(key: String, drawable: Drawable): ArtworkAuraPalette {
        entries.get(key)?.let { return it }
        // Hardware pixels cannot be CPU sampled. Some devices silently rasterize an empty
        // software copy, so leave this identity open for the native software decode instead.
        if (drawable is BitmapDrawable && drawable.bitmap.config == Bitmap.Config.HARDWARE) {
            return MissingArtworkAuraPalette
        }
        val derived = runCatching {
            deriveArtworkAuraPalette(drawable.toBitmap(24, 24, Bitmap.Config.ARGB_8888))
        }.getOrNull() ?: return MissingArtworkAuraPalette
        entries.put(key, derived)
        observers[key]?.get()?.value = derived
        return derived
    }

    @Synchronized
    fun deriveAndPut(key: String, bitmap: Bitmap): ArtworkAuraPalette {
        entries.get(key)?.let { return it }
        return deriveArtworkAuraPalette(bitmap).also { put(key, it) }
    }

    /** Publishes one already-constrained native decode without invalidating unrelated owners. */
    @Synchronized
    fun put(key: String, palette: ArtworkAuraPalette) {
        entries.put(key, palette)
        observers[key]?.get()?.value = palette
    }
}

internal fun artworkAuraPaletteKey(song: Song): String =
    commonArtworkDiskCacheIdentity(song)?.canonicalKey ?: "missing:${song.id}:${song.mediaRevision}"

internal fun sampleArtworkPalettePixels(width: Int, height: Int, read: (Int, Int) -> Int): IntArray {
    require(width > 0 && height > 0)
    return IntArray(24 * 24) { index ->
        read((index % 24 * (width - 1L) / 23L).toInt(),
            (index / 24 * (height - 1L) / 23L).toInt())
    }
}

internal fun deriveArtworkAuraPalette(bitmap: Bitmap): ArtworkAuraPalette {
    val pixels = sampleArtworkPalettePixels(bitmap.width, bitmap.height, bitmap::getPixel)
    fun average(points: IntArray): Color {
        var red = 0f
        var green = 0f
        var blue = 0f
        var weightTotal = 0f
        points.forEach { index ->
            val pixel = pixels[index]
            if (AndroidColor.alpha(pixel) > 24) {
                val pixelRed = AndroidColor.red(pixel) / 255f
                val pixelGreen = AndroidColor.green(pixel) / 255f
                val pixelBlue = AndroidColor.blue(pixel) / 255f
                val maximum = maxOf(pixelRed, pixelGreen, pixelBlue)
                val saturation = if (maximum <= .001f) 0f else
                    (maximum - minOf(pixelRed, pixelGreen, pixelBlue)) / maximum
                // Pale/black boundary pixels still contribute, but vivid cover colors own the glow.
                val weight = .20f + saturation * .60f + maximum * .20f
                red += pixelRed * weight
                green += pixelGreen * weight
                blue += pixelBlue * weight
                weightTotal += weight
            }
        }
        if (weightTotal <= 0f) return MissingArtworkAuraPalette.primary
        return Color(
            red = (red / weightTotal).coerceIn(0f, 1f),
            green = (green / weightTotal).coerceIn(0f, 1f),
            blue = (blue / weightTotal).coerceIn(0f, 1f),
            alpha = 1f
        )
    }
    val lastX = 23
    val lastY = 23
    val midX = lastX / 2
    val midY = lastY / 2
    // Aura color follows the image boundary that actually emits the glow; a center pixel can be
    // unrelated to the visible crop edge and previously let a hostile center dominate the frame.
    val sampledPrimary = average(IntArray(24 + midY) { index ->
        if (index < 24) index else (index - 23) * 24
    })
    val sampledSecondary = average(IntArray(24 + lastX - midX) { index ->
        if (index < 24) index * 24 + lastX else lastY * 24 + midX + index - 24
    })
    val sampledTertiary = average(IntArray(midX + 1 + lastY - midY + 1) { index ->
        if (index <= midX) lastY * 24 + index else (midY + index - midX - 1) * 24
    })
    return ArtworkAuraPalette(
        primary = artworkAuraEmissionColor(
            compatibleArtworkAuraColor(PrimaryPurple, sampledPrimary, .98f)
        ),
        secondary = artworkAuraEmissionColor(
            compatibleArtworkAuraColor(
                StemwisePalette.artworkAuraBlue,
                sampledSecondary,
                .98f
            )
        ),
        dominant = dominantArtworkColor(pixels),
        tertiary = artworkAuraEmissionColor(
            compatibleArtworkAuraColor(
                StemwisePalette.artworkAuraGraphite,
                sampledTertiary,
                .98f
            )
        )
    )
}

/** Quantized whole-cover majority; edge colors still independently drive the artwork glow. */
internal fun dominantArtworkColor(pixels: IntArray): Color {
    fun bucket(pixel: Int) = ((pixel ushr 20 and 15) shl 8) or
        ((pixel ushr 12 and 15) shl 4) or (pixel ushr 4 and 15)
    val counts = IntArray(4096)
    var winner = 0
    for (pixel in pixels) {
        if (pixel ushr 24 <= 24) continue
        val index = bucket(pixel)
        if (++counts[index] > counts[winner]) winner = index
    }
    if (counts[winner] == 0) return MissingArtworkAuraPalette.dominant
    var red = 0L
    var green = 0L
    var blue = 0L
    for (pixel in pixels) {
        if (pixel ushr 24 > 24 && bucket(pixel) == winner) {
            red += pixel ushr 16 and 255
            green += pixel ushr 8 and 255
            blue += pixel and 255
        }
    }
    val scale = counts[winner] * 255f
    return Color(red / scale, green / scale, blue / scale, 1f)
}

internal fun playerArtworkBackgroundColors(palette: ArtworkAuraPalette): List<Color> {
    val emission = artworkAuraEmissionColor(palette.dominant)
    // Keep the hue across the whole player without bright covers washing out secondary text.
    val scale = minOf(1f, .62f / maxOf(emission.red, emission.green, emission.blue).coerceAtLeast(.001f))
    return listOf(.42f, .38f, .34f).map { strength ->
        Color(emission.red * scale * strength, emission.green * scale * strength,
            emission.blue * scale * strength, 1f)
    }
}

internal fun compatibleArtworkAuraColor(base: Color, sampled: Color, influence: Float): Color {
    // The artwork edge owns the hue. Keep only a small theme contribution so distinct red, amber,
    // green and blue covers do not collapse back to the same purple aura.
    val amount = influence.coerceIn(0f, 1f)
    return Color(
        red = base.red + (sampled.red - base.red) * amount,
        green = base.green + (sampled.green - base.green) * amount,
        blue = base.blue + (sampled.blue - base.blue) * amount,
        alpha = 1f
    )
}

internal const val ARTWORK_AURA_MIN_EMISSION_PEAK = .55f

/** Lift only dark boundaries to a visible peak while retaining their sampled hue and saturation. */
internal fun artworkAuraEmissionColor(color: Color): Color {
    val peak = maxOf(color.red, color.green, color.blue)
    val scale = if (peak > .001f) maxOf(1f, ARTWORK_AURA_MIN_EMISSION_PEAK / peak) else 1f
    return Color(
        red = (color.red * scale).coerceIn(0f, 1f),
        green = (color.green * scale).coerceIn(0f, 1f),
        blue = (color.blue * scale).coerceIn(0f, 1f),
        alpha = 1f
    )
}

internal const val ARTWORK_AURA_THUMBNAIL_BASE_OPACITY = .44f
internal const val ARTWORK_AURA_NEAR_BASE_OPACITY = .82f
internal const val ARTWORK_AURA_FAR_BASE_OPACITY = .58f
internal const val ARTWORK_AURA_VISIBILITY_MULTIPLIER = 2f
internal const val ARTWORK_AURA_BRIGHT_OPACITY_OFFSET = .14f
internal const val ARTWORK_AURA_DIM_OPACITY_OFFSET = -.10f
internal const val ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET = .07f
internal const val ARTWORK_AURA_DIM_LUMINANCE_OFFSET = -.01f
internal const val ARTWORK_AURA_LUMINANCE_FILTER_MIN = -.015f
internal const val ARTWORK_AURA_LUMINANCE_FILTER_MAX = .07f
internal const val ARTWORK_AURA_LUMINANCE_FILTER_STEPS = 100

/** Two source-over passes make the aura 100% stronger without clipping its breathing to 1f. */
internal fun artworkAuraOpacity(baseOpacity: Float, opacityOffset: Float): Float {
    val sourceOpacity = (baseOpacity + opacityOffset).coerceIn(0f, 1f)
    return 1f - (1f - sourceOpacity).pow(ARTWORK_AURA_VISIBILITY_MULTIPLIER)
}

internal fun artworkAuraLuminanceFilterIndex(luminanceOffset: Float): Int {
    val normalized = (
        (luminanceOffset.coerceIn(
            ARTWORK_AURA_LUMINANCE_FILTER_MIN,
            ARTWORK_AURA_LUMINANCE_FILTER_MAX
        ) - ARTWORK_AURA_LUMINANCE_FILTER_MIN) /
            (ARTWORK_AURA_LUMINANCE_FILTER_MAX - ARTWORK_AURA_LUMINANCE_FILTER_MIN)
        )
    return (normalized * ARTWORK_AURA_LUMINANCE_FILTER_STEPS)
        .roundToInt()
        .coerceIn(0, ARTWORK_AURA_LUMINANCE_FILTER_STEPS)
}

private enum class ArtworkAuraBand(
    val reachDp: Float,
    val baseOpacity: Float
) {
    Thumbnail(reachDp = 5f, baseOpacity = ARTWORK_AURA_THUMBNAIL_BASE_OPACITY),
    Near(reachDp = 18f, baseOpacity = ARTWORK_AURA_NEAR_BASE_OPACITY),
    Far(reachDp = 56f, baseOpacity = ARTWORK_AURA_FAR_BASE_OPACITY)
}

internal const val ARTWORK_AURA_RETAINED_PADDING_SAFETY_DP = 4f
internal const val ARTWORK_AURA_MASK_FIELD_SAFETY_DP = 2f
internal const val ARTWORK_AURA_MASK_RADIUS_PER_REACH = .5f

/**
 * BlurMaskFilter's raster tail extends beyond its radius argument. Treat the contract's 5/18/56
 * values as the complete visible field, leaving a transparent boundary inside that field instead
 * of clipping a non-zero mask at the retained texture edge.
 */
internal fun artworkAuraMaskBlurRadiusDp(visibleReachDp: Float): Float {
    require(visibleReachDp >= 0f)
    return ((visibleReachDp - ARTWORK_AURA_MASK_FIELD_SAFETY_DP).coerceAtLeast(1f) *
        ARTWORK_AURA_MASK_RADIUS_PER_REACH)
}

internal fun artworkAuraRetainedPaddingDp(reachDp: Float): Float {
    require(reachDp >= 0f)
    return reachDp + ARTWORK_AURA_RETAINED_PADDING_SAFETY_DP
}

internal data class ArtworkAuraTextureSizing(
    val bitmapWidthPx: Int,
    val bitmapHeightPx: Int,
    val cacheScale: Float
)

internal fun artworkAuraTextureSizing(
    nodeWidthPx: Int,
    nodeHeightPx: Int,
    maxTextureEdgePx: Int = 256
): ArtworkAuraTextureSizing {
    require(maxTextureEdgePx > 0) { "Aura texture edge cap must be positive" }
    val safeWidth = nodeWidthPx.coerceAtLeast(1)
    val safeHeight = nodeHeightPx.coerceAtLeast(1)
    val longestEdge = maxOf(safeWidth, safeHeight)
    val cacheScale = minOf(1f, maxTextureEdgePx.toFloat() / longestEdge.toFloat())
    return ArtworkAuraTextureSizing(
        bitmapWidthPx = (safeWidth * cacheScale).roundToInt().coerceIn(1, maxTextureEdgePx),
        bitmapHeightPx = (safeHeight * cacheScale).roundToInt().coerceIn(1, maxTextureEdgePx),
        cacheScale = cacheScale
    )
}

/** One native VSYNC owner is shared by the near/far draw nodes of one artwork surface. */
internal const val ARTWORK_AURA_FRAME_INTERVAL_MS = 33L
private const val ARTWORK_AURA_SLOW_FRAME_THRESHOLD_NANOS = 100_000_000L

internal fun artworkAuraFrameDelayMs(
    uptimeMs: Long,
    intervalMs: Long = ARTWORK_AURA_FRAME_INTERVAL_MS
): Long {
    require(intervalMs > 0L)
    val remainder = Math.floorMod(uptimeMs, intervalMs)
    return if (remainder == 0L) intervalMs else intervalMs - remainder
}

/** Temporary suppression retains the sampled phase; reduced motion selects the static frame. */
internal class ArtworkAuraTimeline {
    var frameNanos: Long = StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS
        private set
    private var initialized = false
    private var previousFrameNanos: Long? = null
    private var enabled = false
    private var reducedMotion = false

    fun configure(enabled: Boolean, reducedMotion: Boolean, nowNanos: Long) {
        if (this.enabled == enabled && this.reducedMotion == reducedMotion) return
        this.enabled = enabled
        this.reducedMotion = reducedMotion
        if (reducedMotion) {
            frameNanos = StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS
            initialized = true
        } else if (enabled && !initialized) {
            frameNanos = nowNanos
            initialized = true
        }
        previousFrameNanos = if (enabled && !reducedMotion) nowNanos else null
    }

    fun advance(nowNanos: Long) {
        val previous = previousFrameNanos ?: return
        frameNanos += (nowNanos - previous).coerceAtLeast(0L)
        previousFrameNanos = nowNanos
    }
}

private class ArtworkAuraClock(
    private val phaseOffsetMs: Long
) {
    private val attachedNodes = linkedSetOf<ArtworkAuraNode>()
    private var ambientMotionEnabled = false
    private var reducedMotion = false
    private var timeline = ArtworkAuraTimeline()
    private var frozenFrameNanos = StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS
    private var previousFrameNanos = 0L
    private var consecutiveSlowFrames = 0
    private var slowFrameDegraded = false
    private var frameScheduled = false
    private var choreographer: Choreographer? = null
    private var recoveryHandler: Handler? = null
    private val frameCallback = Choreographer.FrameCallback(::onFrame)
    private val recoveryCallback = Runnable {
        slowFrameDegraded = false
        previousFrameNanos = 0L
        consecutiveSlowFrames = 0
        attachedNodes.forEach { node -> node.invalidateDraw() }
        if (attachedNodes.isNotEmpty() && ambientMotionEnabled) scheduleFrame()
    }

    fun attach(node: ArtworkAuraNode) {
        attachedNodes += node
        if (ambientMotionEnabled) scheduleFrame()
    }

    fun detach(node: ArtworkAuraNode) {
        attachedNodes -= node
        if (attachedNodes.isEmpty()) {
            stopFrame()
            recoveryHandler?.removeCallbacks(recoveryCallback)
            recoveryHandler = null
            choreographer = null
            resetMotion()
        }
    }

    fun configure(ambientMotionEnabled: Boolean, reducedMotion: Boolean) {
        val motionChanged = this.ambientMotionEnabled != ambientMotionEnabled ||
            this.reducedMotion != reducedMotion
        this.ambientMotionEnabled = ambientMotionEnabled
        this.reducedMotion = reducedMotion
        if (motionChanged) {
            recoveryHandler?.removeCallbacks(recoveryCallback)
            slowFrameDegraded = false
            timeline.configure(ambientMotionEnabled, reducedMotion, System.nanoTime())
            previousFrameNanos = 0L
            consecutiveSlowFrames = 0
            if (ambientMotionEnabled && attachedNodes.isNotEmpty()) scheduleFrame() else stopFrame()
        }
        attachedNodes.forEach { node -> node.invalidateDraw() }
    }

    fun phase(): ArtworkAuraPhase = artworkAuraPhase(
        // Degradation freezes all drift. The near band remains drawn at this static phase while
        // the expensive far band also stops moving; no VSYNC owner remains until the recovery probe.
        frameNanos = if (slowFrameDegraded) frozenFrameNanos else timeline.frameNanos,
        phaseOffsetMs = phaseOffsetMs
    )

    private fun onFrame(frameNanos: Long) {
        frameScheduled = false
        if (attachedNodes.isEmpty() || !ambientMotionEnabled) return
        if (slowFrameDegraded) return
        if (previousFrameNanos != 0L) {
            consecutiveSlowFrames = if (
                frameNanos - previousFrameNanos > ARTWORK_AURA_SLOW_FRAME_THRESHOLD_NANOS
            ) {
                consecutiveSlowFrames + 1
            } else {
                0
            }
            if (consecutiveSlowFrames >= 8) {
                slowFrameDegraded = true
                timeline.advance(frameNanos)
                frozenFrameNanos = timeline.frameNanos
                previousFrameNanos = 0L
                consecutiveSlowFrames = 0
                attachedNodes.forEach { node -> node.invalidateDraw() }
                val handler = recoveryHandler ?: Handler(Looper.getMainLooper()).also {
                    recoveryHandler = it
                }
                handler.postDelayed(recoveryCallback, 10_000L)
                return
            }
        }
        previousFrameNanos = frameNanos
        timeline.advance(frameNanos)
        attachedNodes.forEach { node -> node.invalidateDraw() }
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (
            frameScheduled || attachedNodes.isEmpty() || !ambientMotionEnabled
        ) return
        val owner = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameScheduled = true
        // Aura drift is deliberately slow; sampling it at 30 Hz looks continuous while avoiding
        // a permanent 120 Hz redraw tax on every visible Library artwork.
        // Separate artwork owners may mount on different frames. Align them to one global cadence
        // so six visible thumbnails cause one traversal, not six staggered traversals.
        owner.postFrameCallbackDelayed(
            frameCallback,
            artworkAuraFrameDelayMs(SystemClock.uptimeMillis())
        )
    }

    private fun stopFrame() {
        if (frameScheduled) choreographer?.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    private fun resetMotion() {
        timeline = ArtworkAuraTimeline().also {
            it.configure(ambientMotionEnabled, reducedMotion, System.nanoTime())
        }
        frozenFrameNanos = timeline.frameNanos
        previousFrameNanos = 0L
        consecutiveSlowFrames = 0
    }
}

private data class ArtworkAuraPhase(
    val opacityOffset: Float,
    val luminanceOffset: Float
)

/** Observable by the maintained instrumentation lane; generation is never tied to animation frames. */
internal object ArtworkAuraTextureGenerationDiagnostics {
    private val generations = if (BuildConfig.BUILD_TYPE == "development") AtomicLong(0L) else null
    @Volatile internal var textureObserverForTest: ((Bitmap) -> Unit)? = null

    fun snapshot(): Long = generations?.get() ?: 0L

    fun recordGeneration(bitmap: Bitmap) {
        if (BuildConfig.BUILD_TYPE != "development") return
        generations?.incrementAndGet()
        textureObserverForTest?.invoke(bitmap)
    }
}

/** Shared immutable lookup: exact opacity stays separate while luminance needs no frame allocation. */
private object ArtworkAuraLuminanceFilters {
    private val filters = Array(ARTWORK_AURA_LUMINANCE_FILTER_STEPS + 1) { index ->
        val luminance = ARTWORK_AURA_LUMINANCE_FILTER_MIN +
            (ARTWORK_AURA_LUMINANCE_FILTER_MAX - ARTWORK_AURA_LUMINANCE_FILTER_MIN) *
            index / ARTWORK_AURA_LUMINANCE_FILTER_STEPS
        val multiplier = if (luminance >= 0f) 1f - luminance else 1f + luminance
        val addition = if (luminance >= 0f) luminance * 255f else 0f
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    multiplier, 0f, 0f, 0f, addition,
                    0f, multiplier, 0f, 0f, addition,
                    0f, 0f, multiplier, 0f, addition,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    fun forOffset(luminanceOffset: Float): ColorFilter =
        filters[artworkAuraLuminanceFilterIndex(luminanceOffset)]
}

internal fun artworkAuraLuminanceColorFilter(luminanceOffset: Float): ColorFilter =
    ArtworkAuraLuminanceFilters.forOffset(luminanceOffset)

private val ArtworkAuraEasing = androidx.compose.animation.core.CubicBezierEasing(.45f, 0f, .55f, 1f)

private fun artworkAuraPhase(frameNanos: Long, phaseOffsetMs: Long): ArtworkAuraPhase {
    val periodMs = app.stemwise.ui.design.StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS
    val cycleMs = ((frameNanos / 1_000_000L + phaseOffsetMs) % periodMs).toFloat()
    val brighterMs = periodMs * .35f
    val dimmerMs = periodMs * .68f
    fun between(
        startMs: Float,
        endMs: Float,
        start: ArtworkAuraPhase,
        end: ArtworkAuraPhase
    ): ArtworkAuraPhase {
        val raw = ((cycleMs - startMs) / (endMs - startMs)).coerceIn(0f, 1f)
        val eased = ArtworkAuraEasing.transform(raw)
        return ArtworkAuraPhase(
            opacityOffset = start.opacityOffset + (end.opacityOffset - start.opacityOffset) * eased,
            luminanceOffset = start.luminanceOffset +
                (end.luminanceOffset - start.luminanceOffset) * eased
        )
    }
    val baseline = ArtworkAuraPhase(0f, 0f)
    val brighter = ArtworkAuraPhase(
        ARTWORK_AURA_BRIGHT_OPACITY_OFFSET,
        ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET
    )
    val dimmer = ArtworkAuraPhase(
        ARTWORK_AURA_DIM_OPACITY_OFFSET,
        ARTWORK_AURA_DIM_LUMINANCE_OFFSET
    )
    return when {
        cycleMs <= brighterMs -> between(0f, brighterMs, baseline, brighter)
        cycleMs <= dimmerMs -> between(brighterMs, dimmerMs, brighter, dimmer)
        else -> between(dimmerMs, periodMs.toFloat(), dimmer, baseline)
    }
}

private data class ArtworkAuraElement(
    val palette: ArtworkAuraPalette,
    val band: ArtworkAuraBand,
    val inset: Dp,
    val cornerRadius: Dp,
    val clock: ArtworkAuraClock,
    val ambientMotionEnabled: Boolean,
    val reducedMotion: Boolean
) : ModifierNodeElement<ArtworkAuraNode>() {
    override fun create(): ArtworkAuraNode = ArtworkAuraNode(
        palette = palette,
        band = band,
        inset = inset,
        cornerRadius = cornerRadius,
        clock = clock,
        ambientMotionEnabled = ambientMotionEnabled,
        reducedMotion = reducedMotion
    )

    override fun update(node: ArtworkAuraNode) {
        node.update(palette, band, inset, cornerRadius, clock, ambientMotionEnabled, reducedMotion)
    }
}

private class ArtworkAuraNode(
    private var palette: ArtworkAuraPalette,
    private var band: ArtworkAuraBand,
    private var inset: Dp,
    private var cornerRadius: Dp,
    private var clock: ArtworkAuraClock,
    private var ambientMotionEnabled: Boolean,
    private var reducedMotion: Boolean
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode {
    // One retained blur texture breathes through opacity/luminance without UI-thread reblurring.
    private var textureBitmap: Bitmap? = null
    private var textureImage: ImageBitmap? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var textureDensity = 0f
    private var measuredSize = IntSize.Zero
    private var textureRequestRevision = 0L
    private var textureJob: Job? = null

    override fun onAttach() {
        clock.configure(ambientMotionEnabled, reducedMotion)
        clock.attach(this)
        requestTexture()
    }

    override fun onRemeasured(size: IntSize) {
        measuredSize = size
        requestTexture()
    }

    override fun onDetach() {
        clock.detach(this)
        releaseTexture()
    }

    fun update(
        palette: ArtworkAuraPalette,
        band: ArtworkAuraBand,
        inset: Dp,
        cornerRadius: Dp,
        clock: ArtworkAuraClock,
        ambientMotionEnabled: Boolean,
        reducedMotion: Boolean
    ) {
        val textureChanged = this.palette != palette || this.band != band ||
            this.inset != inset || this.cornerRadius != cornerRadius
        if (this.clock !== clock && isAttached) {
            this.clock.detach(this)
            clock.attach(this)
        }
        this.palette = palette
        this.band = band
        this.inset = inset
        this.cornerRadius = cornerRadius
        this.clock = clock
        this.ambientMotionEnabled = ambientMotionEnabled
        this.reducedMotion = reducedMotion
        if (textureChanged && isAttached) requestTexture(force = true)
        clock.configure(ambientMotionEnabled, reducedMotion)
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val phase = clock.phase()
        val destinationWidth = ceil(size.width.toDouble()).toInt().coerceAtLeast(1)
        val destinationHeight = ceil(size.height.toDouble()).toInt().coerceAtLeast(1)
        val image = textureImage
        if (image == null) { drawContent(); return }
        val alpha = artworkAuraOpacity(band.baseOpacity, phase.opacityOffset)
        val luminanceFilter = ArtworkAuraLuminanceFilters.forOffset(phase.luminanceOffset)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(destinationWidth, destinationHeight),
            alpha = alpha,
            colorFilter = luminanceFilter,
            // The source is already blurred; bilinear filtering preserves it without the higher
            // sampling cost of a detail-oriented image filter.
            filterQuality = FilterQuality.Low
        )
        drawContent()
    }

    private fun requestTexture(force: Boolean = false) {
        if (!isAttached || measuredSize.width <= 0 || measuredSize.height <= 0) return
        val destinationWidth = measuredSize.width
        val destinationHeight = measuredSize.height
        val requestDensity = requireDensity().density
        if (!force && textureWidth == destinationWidth && textureHeight == destinationHeight &&
            textureDensity == requestDensity) return
        textureWidth = destinationWidth
        textureHeight = destinationHeight
        textureDensity = requestDensity
        val requestRevision = ++textureRequestRevision
        val requestPalette = palette
        val requestBand = band
        val requestInset = inset.value
        val requestCornerRadius = cornerRadius.value
        textureJob?.cancel()
        textureJob = coroutineScope.launch {
            val bitmap = withContext(artworkAuraRasterDispatcher) {
                ensureActive()
                val sizing = artworkAuraTextureSizing(destinationWidth, destinationHeight)
                val result = Bitmap.createBitmap(sizing.bitmapWidthPx, sizing.bitmapHeightPx,
                    Bitmap.Config.ARGB_8888)
                val canvas = AndroidCanvas(result)
                val insetPx = requestInset * requestDensity * sizing.cacheScale
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    isDither = true
                    shader = SweepGradient(sizing.bitmapWidthPx * .5f, sizing.bitmapHeightPx * .5f,
                        intArrayOf(requestPalette.primary.toArgb(), requestPalette.secondary.toArgb(),
                            requestPalette.tertiary.toArgb(), requestPalette.primary.toArgb()), null)
                    maskFilter = BlurMaskFilter(artworkAuraMaskBlurRadiusDp(requestBand.reachDp) *
                        requestDensity * sizing.cacheScale, BlurMaskFilter.Blur.OUTER)
                }
                canvas.drawRoundRect(RectF(insetPx, insetPx,
                    (sizing.bitmapWidthPx - insetPx).coerceAtLeast(insetPx + 1f),
                    (sizing.bitmapHeightPx - insetPx).coerceAtLeast(insetPx + 1f)),
                    requestCornerRadius * requestDensity * sizing.cacheScale,
                    requestCornerRadius * requestDensity * sizing.cacheScale, paint)
                ArtworkAuraTextureGenerationDiagnostics.recordGeneration(result)
                ensureActive()
                result
            }
            if (isAttached && requestRevision == textureRequestRevision) {
                textureBitmap = bitmap
                textureImage = bitmap.asImageBitmap()
                invalidateDraw()
            }
        }
    }

    private fun releaseTexture() {
        textureJob?.cancel()
        textureJob = null
        textureRequestRevision++
        textureImage = null
        // asImageBitmap() aliases the Android Bitmap. Compose/RenderThread may replay a retained
        // display list after this node detaches or changes size, so explicit recycle races that
        // draw and can blank the entire graphics layer or crash RecordingCanvas. Clearing our
        // references is enough; Bitmap/GC releases storage once the render pipeline is also done.
        textureBitmap = null
        textureWidth = 0
        textureHeight = 0
        textureDensity = 0f
    }

}

private fun Modifier.expandAuraLayer(padding: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    } else {
        val paddingPx = padding.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val expandedWidth = (width + paddingPx * 2).coerceAtMost(Constraints.Infinity)
        val expandedHeight = (height + paddingPx * 2).coerceAtMost(Constraints.Infinity)
        val placeable = measurable.measure(Constraints.fixed(expandedWidth, expandedHeight))
        layout(width, height) { placeable.placeRelative(-paddingPx, -paddingPx) }
    }
}

internal fun stableArtworkPhaseOffsetMs(surfaceOrdinal: Int): Long {
    // Modulo the complete motion period, rather than a rounded slot count, so every adjacent
    // ordinal keeps the contract's exact 2,550 ms forward separation across every wrap.
    val periodMs = app.stemwise.ui.design.StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS.toLong()
    val offsetMs = app.stemwise.ui.design.StemwiseSemanticMotion.ARTWORK_AURA_PHASE_OFFSET_MS.toLong()
    return Math.floorMod(surfaceOrdinal.toLong() * offsetMs, periodMs)
}

@Composable
internal fun ArtworkAuraLayer(
    palette: ArtworkAuraPalette,
    compact: Boolean,
    showFarAura: Boolean = true,
    phaseOrdinal: Int,
    cornerRadius: Dp,
    ambientMotionEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val motion = app.stemwise.ui.design.LocalStemwiseMotion.current
    val phaseOffsetMs = remember(phaseOrdinal) { stableArtworkPhaseOffsetMs(phaseOrdinal) }
    // Palette/owner rebinds must not restart ambient breathing. Phase is allocated by the stable
    // surface ordinal and sampled from the global monotonic time base.
    val clock = remember(phaseOffsetMs) { ArtworkAuraClock(phaseOffsetMs) }
    Box(modifier.graphicsLayer { clip = false }) {
        if (!compact && showFarAura) {
            val farPadding = artworkAuraRetainedPaddingDp(ArtworkAuraBand.Far.reachDp).dp
            Canvas(
                Modifier
                    .fillMaxSize()
                    .expandAuraLayer(farPadding)
                    .then(
                        ArtworkAuraElement(
                            palette = palette,
                            band = ArtworkAuraBand.Far,
                            inset = farPadding,
                            cornerRadius = cornerRadius,
                            clock = clock,
                            ambientMotionEnabled = motion.ambientMotionEnabled && ambientMotionEnabled,
                            reducedMotion = !motion.enabled
                        )
                    )
            ) {}
        }
        val localBand = if (compact) ArtworkAuraBand.Thumbnail else ArtworkAuraBand.Near
        // The retained texture must outlive the +2 dp breathing endpoint. A uniform 4 dp safety
        // margin prevents the thumbnail's OUTER blur from touching and flattening its bitmap edge.
        val nearPadding = artworkAuraRetainedPaddingDp(localBand.reachDp).dp
        Canvas(
            Modifier
                .fillMaxSize()
                .expandAuraLayer(nearPadding)
                .then(
                    ArtworkAuraElement(
                        palette = palette,
                        band = localBand,
                        inset = nearPadding,
                        cornerRadius = cornerRadius,
                        clock = clock,
                        ambientMotionEnabled = motion.ambientMotionEnabled && ambientMotionEnabled,
                        reducedMotion = !motion.enabled
                    )
                )
        ) {}
    }
}

/** Crossfades two immutable endpoint textures; transition frames never mutate/reblur a palette. */
@Composable
internal fun ArtworkAuraEndpointCrossfadeLayer(
    previousPalette: ArtworkAuraPalette,
    targetPalette: ArtworkAuraPalette,
    transitionProgress: Float,
    compact: Boolean,
    showFarAura: Boolean = true,
    phaseOrdinal: Int,
    cornerRadius: Dp,
    ambientMotionEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val progress = transitionProgress.coerceIn(0f, 1f)
    val transitioning = previousPalette != targetPalette && progress < 1f
    Box(modifier.graphicsLayer { clip = false }) {
        if (transitioning) {
            ArtworkAuraLayer(
                palette = previousPalette,
                compact = compact,
                showFarAura = showFarAura,
                phaseOrdinal = phaseOrdinal,
                cornerRadius = cornerRadius,
                ambientMotionEnabled = ambientMotionEnabled,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // Group-alpha buffers clip the glow that deliberately extends beyond artwork bounds.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = 1f - progress
                }
            )
        }
        ArtworkAuraLayer(
            palette = targetPalette,
            compact = compact,
            showFarAura = showFarAura,
            phaseOrdinal = phaseOrdinal,
            cornerRadius = cornerRadius,
            ambientMotionEnabled = ambientMotionEnabled,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                alpha = if (transitioning) progress else 1f
            }
        )
    }
}

@Composable
fun Artwork(
    song: Song,
    size: Dp,
    modifier: Modifier = Modifier,
    fillAvailable: Boolean = false,
    crossfadeOnLoad: Boolean = true,
    phaseOrdinal: Int = 0,
    localAuraOnly: Boolean = false,
    showFarAura: Boolean = true,
    auraMotionEnabled: Boolean = true,
    cornerRadius: Dp = 10.dp
) {
    val motion = app.stemwise.ui.design.LocalStemwiseMotion.current
    val artworkShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val artworkChangeToken = app.stemwise.ui.design.StemwiseSemanticMotion.token(
        app.stemwise.ui.design.StemwiseMotionEvent.ARTWORK_CHANGE
    )
    val artworkChangeDurationMs = if (motion.enabled) artworkChangeToken.durationMs else 120
    val artworkLoadDurationMs = if (motion.enabled) {
        app.stemwise.ui.design.StemwiseSemanticMotion.ARTWORK_LOAD_MS
    } else {
        120
    }
    val diskIdentity = remember(song.id, song.mediaRevision, song.artworkSource) {
        commonArtworkDiskCacheIdentity(song)
    }
    val paletteKey = remember(song.id, song.mediaRevision, song.artworkSource, diskIdentity) {
        artworkAuraPaletteKey(song)
    }
    var mountedPaletteKey by remember { mutableStateOf(paletteKey) }
    val ownerChangedInComposition = mountedPaletteKey != paletteKey
    var previousAuraPalette by remember {
        mutableStateOf(ArtworkAuraPaletteCache.get(paletteKey) ?: MissingArtworkAuraPalette)
    }
    var targetAuraPalette by remember {
        mutableStateOf(ArtworkAuraPaletteCache.get(paletteKey) ?: MissingArtworkAuraPalette)
    }
    var decodedAuraPalette by remember(paletteKey) {
        mutableStateOf<ArtworkAuraPalette?>(null)
    }
    var auraReady by remember(paletteKey) {
        mutableStateOf(song.artworkSource == null || ArtworkAuraPaletteCache.get(paletteKey) != null)
    }
    var animatePaletteDecode by remember { mutableStateOf(crossfadeOnLoad) }
    val artworkScale = remember { Animatable(1f) }
    val auraPropagation = remember { Animatable(1f) }
    val paletteTransition = remember { Animatable(1f) }

    LaunchedEffect(paletteKey, crossfadeOnLoad, motion.enabled) {
        val ownerChanged = mountedPaletteKey != paletteKey
        mountedPaletteKey = paletteKey
        animatePaletteDecode = crossfadeOnLoad || ownerChanged
        val cachedPalette = ArtworkAuraPaletteCache.get(paletteKey)
        auraReady = song.artworkSource == null || cachedPalette != null
        val nextPalette = cachedPalette ?: MissingArtworkAuraPalette
        if (nextPalette != targetAuraPalette) {
            previousAuraPalette = targetAuraPalette
            targetAuraPalette = nextPalette
            if (motion.enabled && (crossfadeOnLoad || ownerChanged)) {
                launch {
                    paletteTransition.snapTo(0f)
                    paletteTransition.animateTo(
                        1f,
                        tween(
                            durationMillis = artworkChangeToken.durationMs,
                            easing = app.stemwise.ui.design.StemwiseMotionEasing.STANDARD.curve
                        )
                    )
                }
            } else {
                paletteTransition.snapTo(1f)
            }
        } else {
            previousAuraPalette = nextPalette
            paletteTransition.snapTo(1f)
        }
        if (ownerChanged) {
            if (motion.enabled) {
                artworkScale.snapTo(.98f)
                auraPropagation.snapTo(0f)
                coroutineScope {
                    launch {
                        artworkScale.animateTo(
                            1f,
                            tween(
                                durationMillis = artworkChangeToken.durationMs,
                                easing = artworkChangeToken.easing.curve
                            )
                        )
                    }
                    launch {
                        auraPropagation.animateTo(
                            1f,
                            tween(
                                durationMillis = artworkChangeToken.durationMs,
                                easing = artworkChangeToken.easing.curve
                            )
                        )
                    }
                }
            } else {
                artworkScale.snapTo(1f)
                auraPropagation.snapTo(1f)
            }
        } else {
            artworkScale.snapTo(1f)
            auraPropagation.snapTo(1f)
        }
    }
    LaunchedEffect(decodedAuraPalette, paletteKey, motion.enabled, animatePaletteDecode) {
        val decoded = decodedAuraPalette ?: return@LaunchedEffect
        if (decoded == targetAuraPalette) return@LaunchedEffect
        if (!auraReady) {
            previousAuraPalette = decoded
            targetAuraPalette = decoded
            paletteTransition.snapTo(1f)
            auraReady = true
            return@LaunchedEffect
        }
        previousAuraPalette = targetAuraPalette
        targetAuraPalette = decoded
        if (motion.enabled && animatePaletteDecode) {
            paletteTransition.snapTo(0f)
            paletteTransition.animateTo(
                1f,
                tween(
                    durationMillis = artworkLoadDurationMs,
                    easing = app.stemwise.ui.design.StemwiseMotionEasing.STANDARD.curve
                )
            )
        } else {
            paletteTransition.snapTo(1f)
        }
    }
    val artworkBackground = remember {
        Brush.linearGradient(
            listOf(
                MissingArtworkAuraPalette.primary.copy(alpha = .28f),
                MissingArtworkAuraPalette.tertiary.copy(alpha = .44f),
                Ink
            )
        )
    }
    Box(
        modifier = modifier
            .then(if (fillAvailable) Modifier.fillMaxSize() else Modifier.size(size))
            .graphicsLayer { clip = false },
        contentAlignment = Alignment.Center
    ) {
        if (auraReady) ArtworkAuraEndpointCrossfadeLayer(
            previousPalette = previousAuraPalette,
            targetPalette = targetAuraPalette,
            transitionProgress = paletteTransition.value,
            compact = localAuraOnly,
            showFarAura = showFarAura,
            phaseOrdinal = phaseOrdinal,
            cornerRadius = cornerRadius,
            ambientMotionEnabled = auraMotionEnabled,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val propagation = auraPropagation.value
                    val scale = .96f + .04f * propagation
                    scaleX = scale
                    scaleY = scale
                }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = artworkScale.value
                    scaleY = artworkScale.value
                }
                .clip(artworkShape)
                .background(artworkBackground),
            contentAlignment = Alignment.Center
        ) {
        // This is the explicit placeholder for the current owner. It is installed before any
        // asynchronous decode, so a rapid owner/revision switch exposes the new accent surface
        // immediately instead of retaining the outgoing bitmap for one more frame.
        Icon(
            imageVector = StemwiseIcons.MissingArtwork,
            contentDescription = null,
            tint = StemwisePalette.artworkPlaceholderHighlight,
            modifier = Modifier.fillMaxSize(.42f)
        )
        var artworkFailed by remember(song.id, song.artworkSource, song.mediaRevision) {
            mutableStateOf(false)
        }
        var artworkRetry by remember(song.id, song.artworkSource, song.mediaRevision) { mutableStateOf(0) }
        val artworkLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(artworkLifecycle, song.id, song.artworkSource, song.mediaRevision) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                    artworkRetry = 0
                    artworkFailed = false
                }
            }
            artworkLifecycle.lifecycle.addObserver(observer)
            onDispose { artworkLifecycle.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(artworkFailed, artworkRetry) {
            if (artworkFailed && artworkRetry < 2) {
                kotlinx.coroutines.delay(1_000L * (artworkRetry + 1))
                artworkRetry += 1
                artworkFailed = false
            }
        }
        val context = LocalContext.current
        DisposableEffect(context, song.artworkSource, artworkFailed) {
            val uri = song.artworkSource?.toUri()?.takeIf { it.scheme == "content" }
            val observer = if (artworkFailed && uri?.authority != null) {
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        artworkRetry = 0
                        artworkFailed = false
                    }
                }.let { observer ->
                    runCatching {
                        context.contentResolver.registerContentObserver(
                            android.net.Uri.Builder().scheme("content").authority(uri.authority).build(), true, observer
                        )
                    }.fold(onSuccess = { observer }, onFailure = { null })
                }
            } else null
            onDispose { observer?.let(context.contentResolver::unregisterContentObserver) }
        }
        song.artworkSource?.let { source ->
            val model: Any = remember(source) {
                val parsed = source.toUri()
                when {
                    parsed.scheme == "content" -> parsed
                    source.startsWith("http") -> source
                    else -> File(source)
                }
            }
            if (!artworkFailed) key(song.id, song.mediaRevision, source, artworkRetry) {
                val request = remember(
                    context,
                    model,
                    song.mediaRevision,
                    diskIdentity,
                    crossfadeOnLoad,
                    ownerChangedInComposition,
                    artworkLoadDurationMs
                ) {
                    val builder = ImageRequest.Builder(context)
                        .allowHardware(false)
                        .data(model)
                        .memoryCacheKey(
                            artworkMemoryCacheKey(song.id, song.mediaRevision, source)
                        )
                        .apply {
                            diskIdentity?.let { identity ->
                                diskCacheKey(identity.canonicalKey)
                                tag(identity)
                            }
                        }
                    if (crossfadeOnLoad || ownerChangedInComposition) {
                        builder.crossfade(artworkLoadDurationMs)
                    } else {
                        // Avoid constructing even a zero-duration transition for virtualized
                        // thumbnail rows; their final bitmap is already owner-qualified.
                        builder.crossfade(false)
                    }
                    builder.build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = {
                        artworkFailed = true
                    },
                    onSuccess = { state: AsyncImagePainter.State.Success ->
                        decodedAuraPalette = ArtworkAuraPaletteCache.deriveAndPut(
                            paletteKey,
                            state.result.drawable
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        }
    }
}

@Composable
fun ProgressTrack(progress: Float, color: Color = PrimaryPurple, height: Dp = 5.dp) {
    val targetProgress = progress.coerceIn(0f, 1f)
    val displayedProgress = animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = LocalStemwiseMotion.current.semanticSpec(
            event = StemwiseMotionEvent.PROGRESS_CHANGE
        ),
        label = "stemwise-determinate-progress"
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    // Semantics expose the current source value immediately; only rendered pixels
                    // interpolate so assistive feedback never lags behind real task progress.
                    current = targetProgress,
                    range = 0f..1f
                )
            }
            .clip(RoundedCornerShape(50))
            .background(StemwisePalette.progressTrack)
    ) {
        val fillWidth = size.width * displayedProgress.value.coerceIn(0f, 1f)
        drawRect(
            color = color,
            topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - fillWidth else 0f, 0f),
            size = size.copy(width = fillWidth)
        )
    }
}

@Composable
fun PageBackground(
    modifier: Modifier = Modifier,
    backgroundAlpha: Float = 1f,
    backgroundModifier: Modifier = Modifier,
    backgroundOverlayBrush: Brush? = null,
    content: @Composable () -> Unit
) {
    val resolvedBackgroundAlpha = backgroundAlpha.coerceIn(0f, 1f)
    val pageBackgroundColor = Color.Black.copy(alpha = resolvedBackgroundAlpha)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Transparent,
        contentColor = TextPrimary
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // The surface is a sibling of screen chrome so it can participate in a
            // shared container transform without stretching or remeasuring the UI.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(backgroundModifier)
                    .background(pageBackgroundColor)
            )
            backgroundOverlayBrush?.let { overlay ->
                Box(
                    Modifier
                        .matchParentSize()
                        .then(backgroundModifier)
                        .background(overlay)
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
            ) {
                content()
            }
        }
    }
}
