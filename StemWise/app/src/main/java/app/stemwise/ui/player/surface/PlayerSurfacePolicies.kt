package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogSong
import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogPacket

import android.graphics.Color
import android.view.View
import androidx.annotation.ColorInt
import androidx.compose.animation.core.CubicBezierEasing
import app.stemwise.playback.STANDARD_SEEK_INCREMENT_MS
import app.stemwise.ui.PlayerMotionPolicy
import app.stemwise.ui.HIGH_DENSITY_ARTWORK_SIZE_DP
import app.stemwise.ui.LOW_DENSITY_ARTWORK_SIZE_DP
import app.stemwise.ui.NO_PANEL_ARTWORK_SIZE_DP
import app.stemwise.ui.PLAYER_SURFACE_SCREEN_TOP_INSET_DP
import app.stemwise.ui.TrackVisualDirection
import app.stemwise.ui.artworkAuraRetainedPaddingDp
import app.stemwise.ui.ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_BRIGHT_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_FRAME_INTERVAL_MS
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.originalPlayerArtworkTopDp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val SEEKER_GROWTH_EASING = CubicBezierEasing(.2f, .9f, .25f, 1f)
internal val SEMANTIC_EMPHASIZED_EASING = CubicBezierEasing(.2f, .8f, .2f, 1f)
internal val PANEL_STANDARD_EASING = CubicBezierEasing(.2f, 0f, 0f, 1f)
internal val PANEL_ENTER_EASING = CubicBezierEasing(.16f, 1f, .3f, 1f)
internal val PANEL_EXIT_EASING = CubicBezierEasing(.4f, 0f, 1f, 1f)
internal val NATIVE_ARTWORK_AURA_EASING = CubicBezierEasing(.45f, 0f, .55f, 1f)
internal const val FINE_WAVEFORM_MORPH_DURATION_MS = 420
internal const val MINI_LIQUID_SAMPLE_COUNT = 9
internal const val MINI_LIQUID_PHASE_SPEED = 2.0667f
internal const val MINI_LIQUID_MAX_AMPLITUDE_DP = 2.5f
internal const val MINI_LIQUID_EDGE_STROKE_DP = 1.25f
internal const val PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS =
    ARTWORK_AURA_FRAME_INTERVAL_MS * 1_000_000L

internal fun playerSurfaceMiniPlaybackFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

/** Mini progress already paints at the ambient cadence; avoid empty intervening VSYNC work.
 * Expanded playback and every gesture/transition retain the next-frame path. */
internal fun playerSurfacePassiveFrameDelayMs(
    expansionProgress: Float,
    interactiveMotion: Boolean,
    refreshRateHz: Float,
    elapsedSinceMiniPaintMs: Long
): Long = if (expansionProgress <= 0f && !interactiveMotion) {
    val rate = refreshRateHz.takeIf { it.isFinite() && it > 0f } ?: 60f
    val remaining = ARTWORK_AURA_FRAME_INTERVAL_MS -
        elapsedSinceMiniPaintMs.coerceIn(0L, ARTWORK_AURA_FRAME_INTERVAL_MS)
    // An early callback must wait only the remainder, not restart the whole paint interval.
    (remaining - ceil(1_000.0 / rate).toLong()).coerceAtLeast(0L)
} else 0L

internal data class PlayerSurfacePositionInvalidation(
    val mini: Boolean,
    val timeline: Boolean
)

internal fun playerSurfacePositionInvalidation(
    expansionProgress: Float,
    miniFrameDue: Boolean
): PlayerSurfacePositionInvalidation = PlayerSurfacePositionInvalidation(
    mini = miniFrameDue && PlayerMotionPolicy.miniChromeAlpha(expansionProgress) > .001f,
    timeline = PlayerMotionPolicy.fullChromeAlpha(expansionProgress) > .001f
)
internal const val LOOP_CLEARANCE_MOTION_DURATION_MS = FINE_WAVEFORM_MORPH_DURATION_MS
internal const val PAGER_VELOCITY_TIME_CONSTANT_MS = 34.0

internal fun playerSurfaceIdentityMarqueeTranslationPx(
    contentWidthPx: Float,
    viewportWidthPx: Float,
    elapsedMs: Long,
    speedPxPerSecond: Float,
    rightToLeft: Boolean
): Float {
    val overflow = (contentWidthPx - viewportWidthPx).coerceAtLeast(0f)
    if (overflow <= .5f || speedPxPerSecond <= 0f || !speedPxPerSecond.isFinite()) return 0f
    val travelMs = overflow / speedPxPerSecond * 1_000f
    val openingPauseMs = 900f
    val farEdgePauseMs = 650f
    val cycleMs = openingPauseMs + travelMs + farEdgePauseMs + travelMs
    val cyclePositionMs = elapsedMs.coerceAtLeast(0L).toDouble().rem(cycleMs.toDouble()).toFloat()
    val distance = when {
        cyclePositionMs <= openingPauseMs -> 0f
        cyclePositionMs <= openingPauseMs + travelMs ->
            (cyclePositionMs - openingPauseMs) / travelMs * overflow
        cyclePositionMs <= openingPauseMs + travelMs + farEdgePauseMs -> overflow
        else -> overflow -
            (cyclePositionMs - openingPauseMs - travelMs - farEdgePauseMs) / travelMs * overflow
    }.coerceIn(0f, overflow)
    return if (rightToLeft) distance else -distance
}

/** A short deliberate drag or a modest fling may commit; unavailable queue edges remain closed. */
internal fun playerSurfaceResistedPagerOffset(offset: Float, blocked: Boolean): Float =
    if (blocked) .06f * offset / (.15f + abs(offset)) else offset.coerceIn(-1f, 1f)

internal fun playerSurfaceEasyPagerReleaseDirection(
    offsetFraction: Float,
    velocityFractionPerSecond: Float,
    hasLower: Boolean,
    hasHigher: Boolean
): TrackVisualDirection? {
    val requested = when {
        abs(velocityFractionPerSecond) >= .72f -> if (velocityFractionPerSecond > 0f) {
            TrackVisualDirection.LOWER_INDEX
        } else {
            TrackVisualDirection.HIGHER_INDEX
        }
        abs(offsetFraction) >= .14f -> if (offsetFraction > 0f) {
            TrackVisualDirection.LOWER_INDEX
        } else {
            TrackVisualDirection.HIGHER_INDEX
        }
        else -> null
    }
    return when (requested) {
        TrackVisualDirection.LOWER_INDEX -> requested.takeIf { hasLower }
        TrackVisualDirection.HIGHER_INDEX -> requested.takeIf { hasHigher }
        null -> null
    }
}

/** Reuses the pager's direction contract for early Previous/Next button intent. */
internal fun playerSurfaceTransportPreviewOffset(
    command: PlayerSurfaceTransportCommand,
    positionMs: Long = 0L
): Float? = when {
    // Previous is decided by the shared transport's two-press clock on UP, not by song position.
    command == PlayerSurfaceTransportCommand.PREVIOUS -> null
    command == PlayerSurfaceTransportCommand.NEXT -> -1f
    else -> null
}

/** Exact target shared by swipe preview and Previous/Next DOWN; only buttons may wrap. */
internal fun playerSurfaceSelectionPreviewIndex(
    currentIndex: Int,
    itemCount: Int,
    offsetFraction: Float,
    repeatMode: PlayerSurfaceRepeatMode,
    allowRepeatAllWrap: Boolean
): Int? {
    if (currentIndex !in 0 until itemCount || !offsetFraction.isFinite() ||
        offsetFraction == 0f
    ) return null
    val candidate = currentIndex + if (offsetFraction > 0f) -1 else 1
    if (candidate in 0 until itemCount) return candidate
    if (!allowRepeatAllWrap || repeatMode != PlayerSurfaceRepeatMode.ALL || itemCount <= 1) {
        return null
    }
    return if (candidate < 0) itemCount - 1 else 0
}

/** Two hidden exact covers at most; Repeat-All's active edge is admitted before second-neighbours. */
internal fun playerSurfacePredictiveArtworkIndices(
    currentIndex: Int,
    itemCount: Int,
    repeatMode: PlayerSurfaceRepeatMode
): List<Int> {
    if (currentIndex !in 0 until itemCount) return emptyList()
    val result = LinkedHashSet<Int>(2)
    if (repeatMode == PlayerSurfaceRepeatMode.ALL && itemCount > 1) {
        when (currentIndex) {
            0 -> result += itemCount - 1
            itemCount - 1 -> result += 0
        }
    }
    listOf(currentIndex - 2, currentIndex + 2).forEach { index ->
        if (result.size < 2 && index in 0 until itemCount) result += index
    }
    return result.toList()
}

/** Time-aware low-pass filtering keeps a normal flick while a held stationary UP loses momentum. */
internal fun playerSurfacePagerVelocityAfterSample(
    previousVelocityFractionPerSecond: Float,
    instantaneousVelocityFractionPerSecond: Float,
    elapsedMs: Long
): Float {
    val instantaneous = instantaneousVelocityFractionPerSecond
        .takeIf(Float::isFinite) ?: 0f
    val previous = previousVelocityFractionPerSecond.takeIf(Float::isFinite) ?: 0f
    if (previous == 0f) return instantaneous
    val blend = 1.0 - exp(-elapsedMs.coerceAtLeast(1L).toDouble() /
        PAGER_VELOCITY_TIME_CONSTANT_MS)
    return (previous + (instantaneous - previous) * blend).toFloat()
}

/**
 * Normal seeking follows the song span. Fine uses 0.1–2 ms per dp, independent of song length.
 * Both respond to horizontal speed; visual waveform animation never controls physical gain.
 */
internal fun playerSurfaceContinuousScrubSensitivityMsPerPx(
    durationMs: Long,
    trackWidthPx: Float,
    density: Float,
    speedDpPerSecond: Double,
    fineFraction: Float,
    previousMsPerPx: Double,
    elapsedMs: Long
): Double {
    if (durationMs <= 0L || trackWidthPx <= 0f) return 0.0
    val safeDensity = density.coerceAtLeast(.1f)
    val fullTrack = durationMs.toDouble() / trackWidthPx.toDouble()
    val speed = speedDpPerSecond.coerceAtLeast(0.0)
    val velocityT = ((speed - 28.0) / 392.0).coerceIn(0.0, 1.0)
    val smoothVelocityT = velocityT * velocityT * (3.0 - 2.0 * velocityT)
    val deliberate = min(fullTrack, 100.0 / safeDensity)
    val ordinaryTarget = deliberate + (fullTrack - deliberate) * smoothVelocityT
    val fineMaximum = min(fullTrack, 2.0 / safeDensity)
    val fineTarget = min(fullTrack, (.1 + 1.9 * smoothVelocityT) / safeDensity)
    val fineT = fineFraction.coerceIn(0f, 1f).toDouble()
    val target = ordinaryTarget + (fineTarget - ordinaryTarget) * fineT
    val previous = if (fineT >= 1.0) previousMsPerPx.coerceAtMost(fineMaximum) else previousMsPerPx
    if (previous <= 0.0 || !previous.isFinite() || target >= previous) {
        return target
    }
    val blend = 1.0 - exp(-elapsedMs.coerceAtLeast(1L).toDouble() / 72.0)
    return previous + (target - previous) * blend
}

/** Independent panel-content clock; it never retimes the emphasized 520 ms layout profile. */
internal fun playerSurfacePanelContentProgress(
    elapsedNanos: Long,
    durationNanos: Long,
    transition: PlayerSurfacePanelTransition
): Float {
    if (transition == PlayerSurfacePanelTransition.NONE || durationNanos <= 0L) return 1f
    val raw = (elapsedNanos.coerceAtLeast(0L).toDouble() / durationNanos.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
    return when (transition) {
        PlayerSurfacePanelTransition.NONE -> 1f
        PlayerSurfacePanelTransition.OPEN -> PANEL_ENTER_EASING.transform(raw)
        PlayerSurfacePanelTransition.CLOSE -> PANEL_EXIT_EASING.transform(raw)
        PlayerSurfacePanelTransition.REFLOW -> PANEL_STANDARD_EASING.transform(raw)
    }
}

internal fun playerSurfaceFineWaveformMorphValue(
    start: Float,
    target: Float,
    elapsedMs: Long,
    reducedMotion: Boolean
): Float {
    val normalizedTarget = target.coerceIn(0f, 1f)
    if (reducedMotion) return normalizedTarget
    val raw = (elapsedMs.coerceAtLeast(0L).toFloat() /
        FINE_WAVEFORM_MORPH_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    val eased = PANEL_STANDARD_EASING.transform(raw)
    val normalizedStart = start.coerceIn(0f, 1f)
    return normalizedStart + (normalizedTarget - normalizedStart) * eased
}

internal fun playerSurfaceLoopClearanceProgress(
    start: Float,
    target: Float,
    elapsedMs: Long,
    reducedMotion: Boolean
): Float = playerSurfaceFineWaveformMorphValue(start, target, elapsedMs, reducedMotion)

internal fun playerSurfaceExpandedPagerBottomPx(
    artworkTopPx: Float,
    artworkSizePx: Float,
    identityTopPx: Float,
    identityHeightPx: Float
): Float = max(
    artworkTopPx + artworkSizePx,
    identityTopPx + identityHeightPx
)

internal fun playerSurfaceMediaContentVisible(alpha: Float): Boolean =
    alpha.isFinite() && alpha > .001f

internal fun playerSurfaceLowerStackTopPx(
    baseTopPx: Float,
    loopClearanceTopPx: Float,
    loopClearanceProgress: Float
): Float = baseTopPx +
    (max(baseTopPx, loopClearanceTopPx) - baseTopPx) *
    loopClearanceProgress.coerceIn(0f, 1f)

internal const val PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP = 68f
internal const val PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP = 12f
internal const val PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP = 2f
internal const val PLAYER_SURFACE_COMPACT_VISIBLE_RADIUS_DP = 18f
internal const val PLAYER_SURFACE_COMPACT_ARTWORK_LEFT_DP = 20f
internal const val PLAYER_SURFACE_COMPACT_ARTWORK_TOP_DP = 10f
internal const val PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP = 48f
internal const val PLAYER_SURFACE_COMPACT_ARTWORK_RADIUS_DP = 12f
internal const val PLAYER_SURFACE_COMPACT_IDENTITY_LEFT_DP = 82f
internal const val PLAYER_SURFACE_COMPACT_ACTION_RIGHT_INSET_DP = 20f
internal const val PLAYER_SURFACE_COMPACT_ACTION_SIZE_DP = 48f

/**
 * Vector-screen authority: mini artwork is r12; Stems is r10; 228/252/276dp hero art is
 * r21/r23/r25. Intermediate route frames interpolate those canonical silhouettes continuously.
 */
internal fun playerSurfaceArtworkCornerRadiusDp(shortEdgeDp: Float): Float {
    val edge = shortEdgeDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    fun between(
        startEdge: Float,
        startRadius: Float,
        endEdge: Float,
        endRadius: Float
    ): Float {
        val fraction = ((edge - startEdge) / (endEdge - startEdge)).coerceIn(0f, 1f)
        return startRadius + (endRadius - startRadius) * fraction
    }
    return when {
        edge <= PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP ->
            PLAYER_SURFACE_COMPACT_ARTWORK_RADIUS_DP
        edge <= 112f -> between(PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP, 12f, 112f, 10f)
        edge <= 228f -> between(112f, 10f, 228f, 21f)
        edge <= 252f -> between(228f, 21f, 252f, 23f)
        edge <= 276f -> between(252f, 23f, 276f, 25f)
        else -> 25f
    }
}

internal fun playerSurfaceShouldPublishFoldedLoopBoundary(
    initialStartMs: Long,
    initialEndMs: Long,
    latestStartMs: Long,
    latestEndMs: Long,
    capturedBMs: Long
): Boolean = capturedBMs >= 0L || latestStartMs != initialStartMs || latestEndMs != initialEndMs

internal fun playerSurfaceFineWaveformSampleVisible(
    sampleTimeMs: Long,
    durationMs: Long
): Boolean = durationMs > 0L && sampleTimeMs in 0L..durationMs

internal fun playerSurfaceArtworkCornerRadiusPx(
    widthPx: Float,
    heightPx: Float,
    density: Float
): Float {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val shortEdgeDp = min(widthPx, heightPx).coerceAtLeast(0f) / safeDensity
    val radiusDp = playerSurfaceArtworkCornerRadiusDp(shortEdgeDp)
    return min(radiusDp * safeDensity, min(widthPx, heightPx).coerceAtLeast(0f) * .5f)
}

/** Global, playback-independent phase allocation. Native current art owns ordinal 1. */
internal fun playerSurfaceArtworkAuraPhaseMillis(
    frameMillis: Long,
    surfaceOrdinal: Int
): Long = Math.floorMod(
    frameMillis + surfaceOrdinal.toLong() * StemwiseSemanticMotion.ARTWORK_AURA_PHASE_OFFSET_MS,
    StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS.toLong()
)

internal data class PlayerSurfaceArtworkAuraNinePatchGeometry(
    val endpointReachPx: Float,
    val sourceCapPx: Float,
    val destinationCapPx: Float
)

/** Source-space padding for one retained OUTER-blur endpoint. */
internal fun playerSurfaceArtworkAuraRasterPaddingDp(endpointReachDp: Float): Float =
    artworkAuraRetainedPaddingDp(endpointReachDp.coerceAtLeast(0f))

/**
 * Maps one fixed-blur endpoint texture to any live artwork size without scaling its softness.
 * Only the stretchable edge/centre strips change length; the selected profile corner and reach
 * remain fixed.
 */
internal fun playerSurfaceArtworkAuraNinePatchGeometry(
    cacheScale: Float,
    endpointReachDp: Float,
    cornerRadiusDp: Float,
    density: Float
): PlayerSurfaceArtworkAuraNinePatchGeometry {
    val safeScale = cacheScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val endpointReachPx = endpointReachDp.coerceAtLeast(0f) * safeDensity
    val rasterPaddingPx = playerSurfaceArtworkAuraRasterPaddingDp(endpointReachDp) * safeDensity
    val cornerRadiusPx = cornerRadiusDp.coerceAtLeast(0f) * safeDensity
    return PlayerSurfaceArtworkAuraNinePatchGeometry(
        endpointReachPx = endpointReachPx,
        sourceCapPx = (rasterPaddingPx + cornerRadiusPx) * safeScale,
        destinationCapPx = endpointReachPx + cornerRadiusPx
    )
}

internal fun playerSurfaceArtworkAuraProfileCornerRadiusDp(profileIndex: Int): Float =
    when (profileIndex) {
        0 -> 10f
        1 -> 25f
        else -> error("Invalid artwork aura corner profile: $profileIndex")
    }

internal fun playerSurfaceArtworkAuraFullProfileAlpha(
    liveCornerRadiusPx: Float,
    density: Float
): Float {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val liveCornerRadiusDp = liveCornerRadiusPx.coerceAtLeast(0f) / safeDensity
    return ((liveCornerRadiusDp - 10f) / 15f)
        .coerceIn(0f, 1f)
}

internal data class PlayerSurfaceExpandedVisualProfile(
    val artworkTopDp: Float,
    val titleBaselineDp: Float,
    val artistBaselineDp: Float,
    val titleSizeSp: Float,
    val artistSizeSp: Float
)

internal val STEMS_EXPANDED_VISUAL_PROFILE = PlayerSurfaceExpandedVisualProfile(
    artworkTopDp = 106f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    titleBaselineDp = 144f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    artistBaselineDp = 171f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    titleSizeSp = 25f,
    artistSizeSp = 17f
)
internal val ORIGINAL_HIGH_DENSITY_VISUAL_PROFILE = PlayerSurfaceExpandedVisualProfile(
    artworkTopDp = originalPlayerArtworkTopDp(HIGH_DENSITY_ARTWORK_SIZE_DP),
    titleBaselineDp = originalPlayerArtworkTopDp(HIGH_DENSITY_ARTWORK_SIZE_DP) +
        HIGH_DENSITY_ARTWORK_SIZE_DP + 36f,
    artistBaselineDp = originalPlayerArtworkTopDp(HIGH_DENSITY_ARTWORK_SIZE_DP) +
        HIGH_DENSITY_ARTWORK_SIZE_DP + 61f,
    titleSizeSp = 26f,
    artistSizeSp = 17f
)
internal val ORIGINAL_LOW_DENSITY_VISUAL_PROFILE = PlayerSurfaceExpandedVisualProfile(
    artworkTopDp = originalPlayerArtworkTopDp(LOW_DENSITY_ARTWORK_SIZE_DP),
    titleBaselineDp = 385f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    artistBaselineDp = 414f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    titleSizeSp = 28f,
    artistSizeSp = 18f
)
internal val ORIGINAL_NO_PANEL_VISUAL_PROFILE = PlayerSurfaceExpandedVisualProfile(
    artworkTopDp = originalPlayerArtworkTopDp(NO_PANEL_ARTWORK_SIZE_DP),
    titleBaselineDp = 416f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    artistBaselineDp = 443f - PLAYER_SURFACE_SCREEN_TOP_INSET_DP,
    titleSizeSp = 26f,
    artistSizeSp = 17f
)

internal fun interpolateExpandedVisualProfile(
    start: PlayerSurfaceExpandedVisualProfile,
    end: PlayerSurfaceExpandedVisualProfile,
    fraction: Float
): PlayerSurfaceExpandedVisualProfile {
    val p = fraction.coerceIn(0f, 1f)
    fun value(a: Float, b: Float): Float = a + (b - a) * p
    return PlayerSurfaceExpandedVisualProfile(
        artworkTopDp = value(start.artworkTopDp, end.artworkTopDp),
        titleBaselineDp = value(start.titleBaselineDp, end.titleBaselineDp),
        artistBaselineDp = value(start.artistBaselineDp, end.artistBaselineDp),
        titleSizeSp = value(start.titleSizeSp, end.titleSizeSp),
        artistSizeSp = value(start.artistSizeSp, end.artistSizeSp)
    )
}

/**
 * Exact 432dp-master endpoints translated from screen space into native surface coordinates.
 * Resolving from the live artwork size keeps artwork, identity baselines and type sizes on the
 * existing native tool-motion clock instead of snapping when the selected-tool state changes.
 */
internal fun playerSurfaceExpandedVisualProfile(
    sourceMode: PlayerSurfaceSourceMode,
    artworkSizeDp: Float
): PlayerSurfaceExpandedVisualProfile {
    if (sourceMode == PlayerSurfaceSourceMode.STEMS) {
        return STEMS_EXPANDED_VISUAL_PROFILE
    }
    val size = artworkSizeDp.takeIf(Float::isFinite) ?: NO_PANEL_ARTWORK_SIZE_DP
    return when {
        size < HIGH_DENSITY_ARTWORK_SIZE_DP -> ORIGINAL_HIGH_DENSITY_VISUAL_PROFILE.copy(
            titleBaselineDp = originalPlayerArtworkTopDp(size) + size + 36f,
            artistBaselineDp = originalPlayerArtworkTopDp(size) + size + 61f
        )
        size == HIGH_DENSITY_ARTWORK_SIZE_DP -> ORIGINAL_HIGH_DENSITY_VISUAL_PROFILE
        size < LOW_DENSITY_ARTWORK_SIZE_DP -> interpolateExpandedVisualProfile(
            ORIGINAL_HIGH_DENSITY_VISUAL_PROFILE,
            ORIGINAL_LOW_DENSITY_VISUAL_PROFILE,
            (size - HIGH_DENSITY_ARTWORK_SIZE_DP) /
                (LOW_DENSITY_ARTWORK_SIZE_DP - HIGH_DENSITY_ARTWORK_SIZE_DP)
        )
        size == LOW_DENSITY_ARTWORK_SIZE_DP -> ORIGINAL_LOW_DENSITY_VISUAL_PROFILE
        size < NO_PANEL_ARTWORK_SIZE_DP -> interpolateExpandedVisualProfile(
            ORIGINAL_LOW_DENSITY_VISUAL_PROFILE,
            ORIGINAL_NO_PANEL_VISUAL_PROFILE,
            (size - LOW_DENSITY_ARTWORK_SIZE_DP) /
                (NO_PANEL_ARTWORK_SIZE_DP - LOW_DENSITY_ARTWORK_SIZE_DP)
        )
        else -> ORIGINAL_NO_PANEL_VISUAL_PROFILE.copy(
            artworkTopDp = originalPlayerArtworkTopDp(size),
            titleBaselineDp = originalPlayerArtworkTopDp(size) + size + 38f,
            artistBaselineDp = originalPlayerArtworkTopDp(size) + size + 65f
        )
    }
}

internal const val PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT = 3

internal data class PlayerSurfaceArtworkAuraClipBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

internal fun playerSurfaceArtworkAuraClipBounds(
    pagerLeft: Float,
    pagerTop: Float,
    pagerRight: Float,
    recordingBottom: Float,
    surfaceWidth: Float,
    surfaceHeight: Float,
    density: Float
): PlayerSurfaceArtworkAuraClipBounds {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val maximumReachPx = 58f * safeDensity
    val left = (pagerLeft - maximumReachPx).coerceAtLeast(0f)
    val top = (pagerTop - maximumReachPx).coerceAtLeast(0f)
    val right = (pagerRight + maximumReachPx).coerceAtMost(surfaceWidth.coerceAtLeast(left))
    val auraBottom = recordingBottom + maximumReachPx
    val bottom = min(auraBottom, surfaceHeight).coerceAtLeast(top)
    return PlayerSurfaceArtworkAuraClipBounds(left, top, right, bottom)
}

internal fun playerSurfaceDoubleTapSeekDelta(
    sourceMode: PlayerSurfaceSourceMode,
    x: Float,
    y: Float,
    artwork: PlayerSurfaceBounds,
    stemsWorkspace: PlayerSurfaceBounds
): Long {
    val bounds = if (sourceMode == PlayerSurfaceSourceMode.STEMS) stemsWorkspace else artwork
    if (bounds.width <= 0f || bounds.height <= 0f ||
        x !in bounds.left..bounds.right || y !in bounds.top..bounds.bottom
    ) return 0L
    return if (x < (bounds.left + bounds.right) * .5f) {
        -STANDARD_SEEK_INCREMENT_MS
    } else {
        STANDARD_SEEK_INCREMENT_MS
    }
}

internal fun playerSurfaceArtworkDoubleTapMatches(
    firstTimeMs: Long,
    firstX: Float,
    firstY: Float,
    firstGeneration: Long,
    secondTimeMs: Long,
    secondX: Float,
    secondY: Float,
    secondGeneration: Long,
    timeoutMs: Long,
    slopPx: Float
): Boolean {
    if (
        firstTimeMs <= 0L || secondTimeMs < firstTimeMs ||
        secondTimeMs - firstTimeMs > timeoutMs || firstGeneration != secondGeneration
    ) return false
    val dx = secondX - firstX
    val dy = secondY - firstY
    return dx * dx + dy * dy <= slopPx * slopPx
}

internal fun playerSurfaceIdentityMarqueeMotionAllowed(
    animatorsEnabled: Boolean,
    overflowing: Boolean,
    currentPage: Boolean
): Boolean = animatorsEnabled && overflowing && currentPage

/** Body motors covered by the persistent app bar must never acquire the same pointer. */
internal fun playerSurfaceBodyInputVisible(
    pointerY: Float,
    headerHeightPx: Float
): Boolean = pointerY >=
    (headerHeightPx.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f)

internal fun playerSurfacePagerInputAdmitted(
    expansionProgress: Float,
    bodyInputVisible: Boolean,
    pagerHit: Boolean = true,
    backgroundSeekAllowed: Boolean = false
): Boolean = (PlayerMotionPolicy.miniChromeInteractive(expansionProgress) && pagerHit) ||
    (PlayerMotionPolicy.destinationChromeInteractive(expansionProgress) && bodyInputVisible &&
        (pagerHit || backgroundSeekAllowed))

internal fun playerSurfaceArtworkAuraKeyframe(fraction: Float): Float {
    val normalized = ((fraction % 1f) + 1f) % 1f
    return when {
        normalized <= .35f -> NATIVE_ARTWORK_AURA_EASING.transform(normalized / .35f)
        normalized <= .68f -> {
            val segment = NATIVE_ARTWORK_AURA_EASING.transform((normalized - .35f) / .33f)
            1f + (-.5f - 1f) * segment
        }
        else -> {
            val segment = NATIVE_ARTWORK_AURA_EASING.transform((normalized - .68f) / .32f)
            -.5f + .5f * segment
        }
    }
}

internal fun playerSurfaceArtworkAuraOpacityOffset(keyframe: Float): Float =
    if (keyframe >= 0f) {
        keyframe * ARTWORK_AURA_BRIGHT_OPACITY_OFFSET
    } else {
        (-keyframe / .5f).coerceIn(0f, 1f) * ARTWORK_AURA_DIM_OPACITY_OFFSET
    }

internal fun playerSurfaceArtworkAuraLuminanceOffset(keyframe: Float): Float =
    if (keyframe >= 0f) {
        keyframe * ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET
    } else {
        (-keyframe / .5f).coerceIn(0f, 1f) * ARTWORK_AURA_DIM_LUMINANCE_OFFSET
    }

/** Main-player art has much more black surround than thumbnails, so preserve hue and double output. */
internal fun playerSurfaceArtworkAuraEmissionColor(@ColorInt color: Int): Int {
    val peak = max(Color.red(color), max(Color.green(color), Color.blue(color)))
    if (peak <= 0) return color
    val scale = min(255f / peak, 2f)
    return Color.rgb(
        (Color.red(color) * scale).roundToInt(),
        (Color.green(color) * scale).roundToInt(),
        (Color.blue(color) * scale).roundToInt()
    )
}

/** One source-of-truth gate shared by native drawing, touch admission, release, and semantics. */
internal fun playerSurfaceStemsActionEnabled(source: PlayerSurfaceSourcePacket): Boolean =
    when (playerSurfaceStemsStatus(source)) {
        PlayerSurfaceStemsStatus.PREPARING, PlayerSurfaceStemsStatus.SWITCHING -> false
        else -> true
    }

/** Do not submit a fully clipped pager page (and its bitmap shader) to RenderThread. */
internal fun playerSurfacePageIntersectsViewport(
    pageLeft: Float,
    pageWidth: Float,
    viewportLeft: Float,
    viewportRight: Float
): Boolean = pageWidth > 0f && viewportRight > viewportLeft &&
    pageLeft < viewportRight && pageLeft + pageWidth > viewportLeft

/**
 * Original-mode tool endpoints move one rigid control stack. Keeping that stack on retained
 * RenderNodes avoids rebuilding and laying out the same timeline/transport/tool display lists on
 * every 120 Hz animation frame. Stems are deliberately excluded because their mixer rows resize.
 */
internal fun playerSurfaceRigidOriginalToolMotionEligible(
    sourceMode: PlayerSurfaceSourceMode,
    controlsValid: Boolean,
    controlGestureActive: Boolean,
    startLeft: Float,
    startTop: Float,
    startRight: Float,
    startBottom: Float,
    targetLeft: Float,
    targetTop: Float,
    targetRight: Float,
    targetBottom: Float,
    startMixerCardHeightPx: Float,
    targetMixerCardHeightPx: Float,
    startMixerSpacingPx: Float,
    targetMixerSpacingPx: Float,
    tolerancePx: Float = .5f
): Boolean {
    if (!controlsValid || controlGestureActive || sourceMode != PlayerSurfaceSourceMode.ORIGINAL) {
        return false
    }
    val values = floatArrayOf(
        startLeft,
        startTop,
        startRight,
        startBottom,
        targetLeft,
        targetTop,
        targetRight,
        targetBottom,
        startMixerCardHeightPx,
        targetMixerCardHeightPx,
        startMixerSpacingPx,
        targetMixerSpacingPx,
        tolerancePx
    )
    if (values.any { !it.isFinite() } || tolerancePx < 0f) return false
    val startWidth = startRight - startLeft
    val targetWidth = targetRight - targetLeft
    val startHeight = startBottom - startTop
    val targetHeight = targetBottom - targetTop
    return startWidth > 0f && startHeight > 0f &&
        abs(startLeft - targetLeft) <= tolerancePx &&
        abs(startRight - targetRight) <= tolerancePx &&
        abs(startWidth - targetWidth) <= tolerancePx &&
        abs(startHeight - targetHeight) <= tolerancePx &&
        abs(startMixerCardHeightPx - targetMixerCardHeightPx) <= tolerancePx &&
        abs(startMixerSpacingPx - targetMixerSpacingPx) <= tolerancePx
}

/** Commits a retained rigid-control coordinate into the View's ordinary local coordinate space. */
internal fun playerSurfaceMaterializedRigidControlCoordinate(
    baseCoordinatePx: Float,
    translationPx: Float
): Float {
    if (!baseCoordinatePx.isFinite()) return 0f
    val safeTranslation = translationPx.takeIf(Float::isFinite) ?: 0f
    return baseCoordinatePx + safeTranslation
}

/** Only fields that can change pager pixels or their transform basis may re-record pager children. */
internal fun playerSurfacePagerPublicationChanged(
    initialBind: Boolean,
    ownerChanged: Boolean,
    queueChanged: Boolean,
    layoutChanged: Boolean,
    sourceChanged: Boolean,
    catalogContentChanged: Boolean,
    visualIndexChanged: Boolean
): Boolean = initialBind || ownerChanged || queueChanged || layoutChanged || sourceChanged ||
    catalogContentChanged || visualIndexChanged

/** Exact visual bitmap identity; deliberately independent from playback-owner generation. */
internal data class PlayerSurfaceArtworkDisplayKey(
    val songId: String,
    val physicalSource: String,
    val mediaRevision: String,
    val decodeSizePx: Int,
    @param:ColorInt val accentArgb: Int
)

/**
 * Holds an accepted native page while the authoritative playback owner is catching up.
 *
 * A page/transport callback can synchronously accept a new queue item before Compose publishes
 * the next owner generation. During that short window, unrelated timeline/transport publications
 * still carry the previous owner and must not snap the native pager back to it. Queue keys are only
 * stable inside one owner generation. A newer authoritative owner may consume the pending target
 * only when its song, queue key, queue index, and complete logical queue still match exactly. That
 * one transition is allowed to retain the already-running release spring; every other owner or
 * queue change returns anchoring to the authoritative profile.
 */
internal data class PlayerSurfacePendingVisualResolution(
    val queueIndex: Int,
    val songId: String,
    val queueKey: Long,
    val authoritativeOwnerCaughtUp: Boolean
)

internal data class PlayerSurfacePagerSpring(
    val offsetFraction: Float,
    val velocityFraction: Float,
    val animating: Boolean
)

/** Exact compact-route callback evidence from the mounted native action owner. */
internal data class PlayerSurfaceCompactActionDiagnostics(
    val openAttemptCount: Long,
    val openAcceptedCount: Long,
    val miniPlayAttemptCount: Long,
    val miniPlayAcceptedCount: Long
)

internal fun preserveAcceptedPlayerSurfacePagerContinuity(
    ownerChanged: Boolean,
    queueChanged: Boolean,
    layoutChanged: Boolean,
    logicalQueueUnchanged: Boolean,
    visualSelectionSongId: String,
    visualQueueIndex: Int,
    visual: FullPlayerCatalogSong?
): Boolean = !layoutChanged &&
    (ownerChanged || queueChanged) &&
    logicalQueueUnchanged &&
    visualQueueIndex >= 0 &&
    visual != null &&
    visual.songId == visualSelectionSongId

internal fun playerSurfaceVisualSelectionIndex(
    requestedIndex: Int,
    visualSelectionSongId: String,
    songs: List<FullPlayerCatalogSong>
): Int = if (songs is FullPlayerCatalogPacket) {
    songs.indexOfSong(visualSelectionSongId) ?: requestedIndex
} else songs.indexOfFirst { it.songId == visualSelectionSongId }.takeIf { it >= 0 } ?: requestedIndex

internal fun playerSurfacePagerSpringAfterCancellation(
    offsetFraction: Float,
    velocityFraction: Float,
    wasAnimating: Boolean,
    preserveSpring: Boolean,
    reducedMotion: Boolean = false
): PlayerSurfacePagerSpring {
    if (reducedMotion) return PlayerSurfacePagerSpring(0f, 0f, animating = false)
    if (preserveSpring) {
        val continues = wasAnimating ||
            abs(offsetFraction) > .001f ||
            abs(velocityFraction) > .008f
        return if (continues) {
            PlayerSurfacePagerSpring(offsetFraction, velocityFraction, animating = true)
        } else {
            PlayerSurfacePagerSpring(0f, 0f, animating = false)
        }
    }
    return if (abs(offsetFraction) > .001f) {
        PlayerSurfacePagerSpring(offsetFraction, 0f, animating = true)
    } else {
        PlayerSurfacePagerSpring(0f, 0f, animating = false)
    }
}

internal fun playerSurfaceScrubGrowthDurationMs(
    boundary: FullPlayerLoopBoundary?,
    reducedMotion: Boolean
): Long = when {
    reducedMotion -> 0L
    boundary == null -> StemwiseSeekerDesign.PLAYHEAD_GROWTH_DURATION_MS
    else -> StemwiseSeekerDesign.LOOP_HANDLE_GROWTH_DURATION_MS
}

internal fun playerSurfaceLoopClearanceShouldSnap(
    initialBind: Boolean,
    ownerChanged: Boolean,
    sourceChanged: Boolean,
    animatorsEnabled: Boolean
): Boolean = initialBind || ownerChanged || sourceChanged || !animatorsEnabled

internal fun playerSurfacePagerRequiresReanchor(
    ownerChanged: Boolean,
    queueChanged: Boolean,
    preserveAcceptedSpring: Boolean,
    visualQueueIndex: Int,
    desiredIndex: Int,
    visualSongId: String?,
    desiredSongId: String?
): Boolean =
    ((ownerChanged || queueChanged) && !preserveAcceptedSpring) ||
        visualQueueIndex < 0 ||
        visualQueueIndex != desiredIndex ||
    visualSongId != desiredSongId

internal class PlayerSurfacePendingVisualSelection {
    private var ownerGeneration = NO_GENERATION
    private var membership: PlayerSurfaceQueueMembership? = null
    private var queueKey = 0L
    private var songId: String? = null
    private var queueIndex = -1

    fun recordAccepted(
        ownerGeneration: Long,
        queue: PlayerSurfaceQueuePacket,
        song: FullPlayerCatalogSong,
        queueIndex: Int
    ) {
        if (
            queueIndex !in 0 until queue.itemCount ||
            queue.itemKeyAt(queueIndex) != song.queueKey
        ) {
            clear()
            return
        }
        this.ownerGeneration = ownerGeneration
        this.membership = queue.membership
        this.queueKey = song.queueKey
        this.songId = song.songId
        this.queueIndex = queueIndex
    }

    /** Returns a valid pending/catch-up target, or null when the profile must reanchor. */
    fun resolve(
        ownerGeneration: Long,
        authoritativeSongId: String,
        queue: PlayerSurfaceQueuePacket,
        songs: List<FullPlayerCatalogSong>,
        visualSelectionSongId: String = authoritativeSongId
    ): PlayerSurfacePendingVisualResolution? {
        val expectedSongId = songId ?: return null
        if (visualSelectionSongId != expectedSongId) {
            clear()
            return null
        }
        val sameOwnerGeneration = ownerGeneration == this.ownerGeneration
        val newerOwnerGeneration = ownerGeneration > this.ownerGeneration
        if (!sameOwnerGeneration && !newerOwnerGeneration) {
            clear()
            return null
        }
        val resolvedIndex = queueIndex
        val candidate = songs.getOrNull(resolvedIndex)
        val logicalQueueUnchanged = queueMatchesRecordedKeys(queue)
        if (
            candidate == null ||
            resolvedIndex !in 0 until queue.itemCount ||
            queue.itemKeyAt(resolvedIndex) != queueKey ||
            candidate.queueKey != queueKey ||
            candidate.songId != expectedSongId ||
            !logicalQueueUnchanged
        ) {
            clear()
            return null
        }

        if (newerOwnerGeneration) {
            if (authoritativeSongId != expectedSongId) {
                // Decoder/presentation owners can legitimately walk through superseded requests
                // while rapid physical swipes have already moved the accepted visual intent
                // farther ahead. FullPlayer's queue currentIndex belongs to that complete hydrated
                // owner; it is intentionally not the synchronous visual-intent oracle. Retain the
                // target only while the separately owned intent ID still matches it. An external
                // or newer route changes visualSelectionSongId above and clears this lease.
                return PlayerSurfacePendingVisualResolution(
                    queueIndex = resolvedIndex,
                    songId = expectedSongId,
                    queueKey = queueKey,
                    authoritativeOwnerCaughtUp = false
                )
            }
            val caughtUp = PlayerSurfacePendingVisualResolution(
                queueIndex = resolvedIndex,
                songId = expectedSongId,
                queueKey = queueKey,
                authoritativeOwnerCaughtUp = true
            )
            clear()
            return caughtUp
        }

        return PlayerSurfacePendingVisualResolution(
            queueIndex = resolvedIndex,
            songId = expectedSongId,
            queueKey = queueKey,
            authoritativeOwnerCaughtUp = false
        )
    }

    private fun queueMatchesRecordedKeys(queue: PlayerSurfaceQueuePacket): Boolean =
        membership?.hasSameItems(queue.membership) == true

    fun clear() {
        ownerGeneration = NO_GENERATION
        membership = null
        queueKey = 0L
        songId = null
        queueIndex = -1
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}

internal fun interface PlayerSurfacePositionProvider {
    fun currentPositionMs(): Long
}
