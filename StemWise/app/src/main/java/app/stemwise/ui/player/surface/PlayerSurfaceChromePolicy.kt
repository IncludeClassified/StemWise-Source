package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot

import android.view.MotionEvent
import app.stemwise.ui.STEMS_VISIBLE_ROW_HEIGHT_DP

internal fun playerSurfaceSourceContentStartX(
    left: Float,
    right: Float,
    iconWidth: Float,
    labelWidth: Float,
    gap: Float
): Float = left + ((right - left) - iconWidth - gap - labelWidth) * .5f

/**
 * Exact 432dp-master centers, recentered for narrower supported viewports without scaling glyphs
 * or targets. Only the outer transport span compresses when a 48dp edge target would clip.
 */
internal fun playerSurfaceTransportCenterXDp(
    index: Int,
    viewportWidthDp: Float = 432f
): Float {
    require(viewportWidthDp.isFinite() && viewportWidthDp > 0f)
    val normalizedOffset = when (index) {
        0 -> -1f
        1 -> -82f / 160f
        2 -> 0f
        3 -> 82f / 160f
        4 -> 1f
        else -> error("Invalid transport index: $index")
    }
    val center = viewportWidthDp * .5f
    val halfSpan = (center - 24f).coerceIn(0f, 160f)
    return center + normalizedOffset * halfSpan
}

internal fun playerSurfaceToolCenterXDp(
    index: Int,
    viewportWidthDp: Float = 432f,
    outerVisibleHalfWidthDp: Float = 26f
): Float {
    require(viewportWidthDp.isFinite() && viewportWidthDp > 0f)
    require(outerVisibleHalfWidthDp.isFinite() && outerVisibleHalfWidthDp >= 26f)
    val masterOffset = when (index) {
        0 -> -150f
        1 -> -50f
        2 -> 50f
        3 -> 150f
        else -> error("Invalid tool index: $index")
    }
    val center = viewportWidthDp * .5f
    // Compact placement protects whichever is wider: the 52dp action target or the authored
    // visible label. At 432dp the 150dp cap preserves exact centers 66/166/266/366.
    val halfSpan = (center - outerVisibleHalfWidthDp).coerceIn(0f, 150f)
    return center + masterOffset / 150f * halfSpan
}

/** Fills retained targets; neighbor midpoints partition the complete row without allocations. */
internal fun fillPlayerSurfacePartitionedRowTargets(
    visualBounds: FloatArray,
    hitBounds: FloatArray,
    count: Int,
    outerLeft: Float,
    outerRight: Float,
    top: Float,
    bottom: Float
) {
    require(count > 0 && visualBounds.size >= count * 4 && hitBounds.size >= count * 4)
    require(outerLeft.isFinite() && outerRight.isFinite() && outerRight >= outerLeft)
    var left = outerLeft
    for (index in 0 until count) {
        val offset = index * 4
        val center = (visualBounds[offset] + visualBounds[offset + 2]) * .5f
        require(center.isFinite())
        val right = if (index == count - 1) outerRight else {
            val next = (visualBounds[offset + 4] + visualBounds[offset + 6]) * .5f
            require(next.isFinite() && next > center)
            (center + next) * .5f
        }
        hitBounds[offset] = left
        hitBounds[offset + 1] = top
        hitBounds[offset + 2] = right
        hitBounds[offset + 3] = bottom
        left = right
    }
}

internal fun playerSurfaceTransportIconSizeDp(index: Int): Float = when (index) {
    0, 4 -> 24f
    1, 3 -> 28f
    2 -> 31f
    else -> error("Invalid transport index: $index")
}

internal fun playerSurfaceTransportAvailabilityChanged(
    previous: PlayerSurfaceTransportPacket?,
    next: PlayerSurfaceTransportPacket
): Boolean = previous == null ||
    previous.canGoPrevious != next.canGoPrevious ||
    previous.canGoNext != next.canGoNext

internal const val PLAYER_SURFACE_TOOL_ICON_SIZE_DP = 23f
internal data class PlayerSurfaceMixerChromeContentKey(
    val ownerGeneration: Long,
    val sourceMode: PlayerSurfaceSourceMode,
    val packageRevision: Long,
    val laneCount: Int,
    val laneLabelsHash: Int,
    val laneColorsHash: Int
)

/** Content which may re-record mixer chrome; volume and timeline publications are excluded. */
internal fun playerSurfaceMixerChromeContentKey(
    profile: PlayerSurfaceProfilePacket,
    visuals: FullPlayerSnapshot,
    sourceMode: PlayerSurfaceSourceMode
): PlayerSurfaceMixerChromeContentKey = PlayerSurfaceMixerChromeContentKey(
    ownerGeneration = profile.owner.generation,
    sourceMode = sourceMode,
    packageRevision = profile.mixer.packageRevision,
    laneCount = profile.mixer.laneCount,
    laneLabelsHash = visuals.sourceMixer.lanes().fold(1) { hash, lane -> 31 * hash + lane.label.hashCode() },
    laneColorsHash = visuals.sourceMixer.lanes().fold(1) { hash, lane -> 31 * hash + lane.colorArgb }
)

internal fun playerSurfaceRetainedChromeEligible(
    hardwareAccelerated: Boolean,
    controlsValid: Boolean,
    expansionProgress: Float
): Boolean = hardwareAccelerated &&
    controlsValid &&
    expansionProgress >= .999f

internal fun playerSurfaceMixerVisibleRowCenterY(
    cardTop: Float,
    cardBottom: Float,
    density: Float
): Float = (cardTop + playerSurfaceMixerVisibleRowBottom(cardTop, cardBottom, density)) * .5f

internal fun playerSurfaceMixerVisibleRowBottom(
    cardTop: Float,
    cardBottom: Float,
    density: Float
): Float {
    val safeDensity = density.coerceAtLeast(.1f)
    val cardHeight = (cardBottom - cardTop).coerceAtLeast(0f)
    val authoredHeight = STEMS_VISIBLE_ROW_HEIGHT_DP * safeDensity
    val visibleHeight = minOf(
        cardHeight,
        maxOf(authoredHeight, cardHeight - 6f * safeDensity)
    )
    return cardTop + visibleHeight
}

/** Two non-overlapping motors around the accepted rectangular visible bodies. */
internal fun fillPlayerSurfaceMixerToggleTargets(
    cardTop: Float,
    cardRight: Float,
    cardBottom: Float,
    density: Float,
    cardLeft: Float,
    mute: FloatArray,
    solo: FloatArray,
    offset: Int
) {
    val safeDensity = density.coerceAtLeast(.1f)
    val halfWidth = 25f * safeDensity
    val centerY = playerSurfaceMixerVisibleRowCenterY(cardTop, cardBottom, safeDensity)
    val height = minOf(50f * safeDensity, (cardBottom - cardTop).coerceAtLeast(0f))
    val top = (centerY - height * .5f).coerceIn(cardTop, cardBottom - height)
    val muteCenterX = cardLeft + 31f * safeDensity
    val soloCenterX = cardRight - 31f * safeDensity
    mute[offset] = muteCenterX - halfWidth
    mute[offset + 1] = top
    mute[offset + 2] = muteCenterX + halfWidth
    mute[offset + 3] = top + height
    solo[offset] = soloCenterX - halfWidth
    solo[offset + 1] = top
    solo[offset + 2] = soloCenterX + halfWidth
    solo[offset + 3] = top + height
}

internal fun playerSurfaceCenteredTextBaselineY(
    centerY: Float,
    ascent: Float,
    descent: Float
): Float = centerY - (ascent + descent) * .5f

internal fun playerSurfaceBottomSafeTextBaselineY(
    authoredBaselineY: Float,
    bottomY: Float,
    descent: Float,
    bottomGapPx: Float
): Float = minOf(authoredBaselineY, bottomY - descent - bottomGapPx)

/**
 * Current visible body, centred inside (and independent from) the invariant 48 dp target.
 * The open 48 dp Stems row gently reduces the paint body to 40 dp; the closed 64 dp row keeps
 * the authored 42 x 44 dp body. The motor target never shrinks with it.
 */
internal fun fillPlayerSurfaceMixerToggleVisualBounds(
    target: FloatArray,
    destination: FloatArray,
    offset: Int,
    density: Float,
    mixerCardHeightPx: Float
) {
    val safeDensity = density.coerceAtLeast(.1f)
    val cardHeightDp = mixerCardHeightPx.takeIf(Float::isFinite)
        ?.div(safeDensity)?.coerceIn(48f, 64f) ?: 64f
    val closedFraction = (cardHeightDp - 48f) / 16f
    val halfWidth = (20f + closedFraction) * safeDensity
    val halfHeight = (20f + 2f * closedFraction) * safeDensity
    val centerX = (target[offset] + target[offset + 2]) * .5f
    val centerY = (target[offset + 1] + target[offset + 3]) * .5f
    destination[offset] = centerX - halfWidth
    destination[offset + 1] = centerY - halfHeight
    destination[offset + 2] = centerX + halfWidth
    destination[offset + 3] = centerY + halfHeight
}

internal enum class PlayerSurfaceActionPointerDisposition {
    KEEP,
    FINISH,
    CANCEL
}

/** Keeps a native action leased to the pointer that began it across multi-touch events. */
internal fun playerSurfaceActionPointerDisposition(
    capturedPointerId: Int,
    actionMasked: Int,
    actionPointerId: Int,
    capturedPointerIndex: Int
): PlayerSurfaceActionPointerDisposition = when {
    capturedPointerId == MotionEvent.INVALID_POINTER_ID ->
        PlayerSurfaceActionPointerDisposition.KEEP
    actionMasked == MotionEvent.ACTION_CANCEL ->
        PlayerSurfaceActionPointerDisposition.CANCEL
    actionMasked == MotionEvent.ACTION_MOVE && capturedPointerIndex < 0 ->
        PlayerSurfaceActionPointerDisposition.CANCEL
    actionMasked == MotionEvent.ACTION_UP && actionPointerId != capturedPointerId ->
        PlayerSurfaceActionPointerDisposition.CANCEL
    (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP) &&
        actionPointerId == capturedPointerId ->
        PlayerSurfaceActionPointerDisposition.FINISH
    else -> PlayerSurfaceActionPointerDisposition.KEEP
}

/** Disabled targets cancel only a gesture they already acquired. */
internal fun playerSurfaceCancelDisabledAction(
    capturedPointerId: Int,
    cancelOwnedGesture: () -> Unit
): Boolean {
    if (capturedPointerId == MotionEvent.INVALID_POINTER_ID) return false
    cancelOwnedGesture()
    return true
}

internal enum class PlayerSurfaceSemanticFeedbackChannel {
    COLLAPSE,
    SONG_INFO,
    TOOL,
    SOURCE_ORIGINAL,
    SOURCE_STEMS,
    TRANSPORT,
    MINI_PLAY,
    MIXER_MUTE,
    MIXER_SOLO
}

internal data class PlayerSurfaceSemanticFeedbackRoute(
    val channel: PlayerSurfaceSemanticFeedbackChannel,
    val index: Int = 0
)

/** Maps public semantic intent to the existing private native bloom owner. */
internal fun playerSurfaceSemanticFeedbackRoute(
    target: PlayerSurfaceSemanticFeedbackTarget,
    compact: Boolean
): PlayerSurfaceSemanticFeedbackRoute? {
    if (compact) {
        return if (
            target is PlayerSurfaceSemanticFeedbackTarget.Transport &&
            target.command == PlayerSurfaceTransportCommand.PLAY_PAUSE
        ) {
            PlayerSurfaceSemanticFeedbackRoute(PlayerSurfaceSemanticFeedbackChannel.MINI_PLAY)
        } else {
            null
        }
    }
    return when (target) {
        PlayerSurfaceSemanticFeedbackTarget.Collapse -> PlayerSurfaceSemanticFeedbackRoute(
            PlayerSurfaceSemanticFeedbackChannel.COLLAPSE
        )
        PlayerSurfaceSemanticFeedbackTarget.SongInfo -> PlayerSurfaceSemanticFeedbackRoute(
            PlayerSurfaceSemanticFeedbackChannel.SONG_INFO
        )
        is PlayerSurfaceSemanticFeedbackTarget.Tool -> {
            val index = when (target.tool) {
                PlayerSurfaceTool.LOOP -> 0
                PlayerSurfaceTool.TUNE -> 1
                PlayerSurfaceTool.LYRICS -> 2
                PlayerSurfaceTool.METRONOME -> 3
                PlayerSurfaceTool.NONE -> return null
            }
            PlayerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackChannel.TOOL,
                index
            )
        }
        is PlayerSurfaceSemanticFeedbackTarget.Source -> PlayerSurfaceSemanticFeedbackRoute(
            if (target.mode == PlayerSurfaceSourceMode.ORIGINAL) {
                PlayerSurfaceSemanticFeedbackChannel.SOURCE_ORIGINAL
            } else {
                PlayerSurfaceSemanticFeedbackChannel.SOURCE_STEMS
            }
        )
        is PlayerSurfaceSemanticFeedbackTarget.Transport -> {
            val index = when (target.command) {
                PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE -> 0
                PlayerSurfaceTransportCommand.PREVIOUS -> 1
                PlayerSurfaceTransportCommand.PLAY_PAUSE -> 2
                PlayerSurfaceTransportCommand.NEXT -> 3
                PlayerSurfaceTransportCommand.REPEAT_CYCLE -> 4
            }
            PlayerSurfaceSemanticFeedbackRoute(
                PlayerSurfaceSemanticFeedbackChannel.TRANSPORT,
                index
            )
        }
        is PlayerSurfaceSemanticFeedbackTarget.MixerToggle -> PlayerSurfaceSemanticFeedbackRoute(
            if (target.mute) PlayerSurfaceSemanticFeedbackChannel.MIXER_MUTE
            else PlayerSurfaceSemanticFeedbackChannel.MIXER_SOLO,
            target.laneIndex
        )
    }
}
