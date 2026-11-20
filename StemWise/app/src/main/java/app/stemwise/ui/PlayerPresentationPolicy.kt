package app.stemwise.ui

import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.SourceSwitchState
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.AudioPlaybackSuppression
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.player.surface.PLAYER_SURFACE_HEADER_HEIGHT_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_COMPACT_VISIBLE_RADIUS_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP
import app.stemwise.ui.player.surface.PLAYER_SURFACE_SEEKER_CLOCK_ADDITIONAL_CLEARANCE_DP
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import app.stemwise.ui.player.surface.FullPlayerLoopBoundary
import app.stemwise.ui.player.surface.playerSurfaceContinuousScrubSensitivityMsPerPx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The audio owner is the sole initial clock authority; the app shell never mirrors position. */
internal fun authoritativeInitialPlayerPosition(
    visibleSongId: String,
    audioSongId: String?,
    audioPositionMs: Long
): Long = if (visibleSongId == audioSongId) audioPositionMs.coerceAtLeast(0L) else 0L

internal const val PLAYER_DETAIL_TUNE = "Key"
internal const val STEMS_TIMING_UNVERIFIED_LABEL = "Timing unverified"
internal const val PLAYER_DETAIL_LOOP = "Loop"
internal const val PLAYER_LYRICS_OVERLAY = "Lyrics"
internal const val PLAYER_DETAIL_METRONOME = "Metronome"

/** Material owns gestures; this keeps fractional BPM motion on the main seeker's normal curve. */
internal class MetronomeBpmDrag(private val range: ClosedFloatingPointRange<Float>) {
    private var active = false
    private var dragging = false
    private var moved = false
    private var appliedX = 0f
    private var appliedTimeMs = 0L
    private var width = 1f
    private var density = 1f
    private var direction = 1f
    private var precise = 0.0
    private var sensitivity = 0.0

    fun begin(x: Float, timeMs: Long, value: Float, width: Float, density: Float, rtl: Boolean) {
        this.width = (width - 16f * density).coerceAtLeast(1f)
        this.density = density
        direction = if (rtl) -1f else 1f
        val fraction = (value - range.start) / (range.endInclusive - range.start)
        val thumbX = 8f * density + this.width * if (rtl) 1f - fraction else fraction
        val downFraction = ((x - 8f * density) / this.width).coerceIn(0f, 1f)
        precise = if (abs(x - thumbX) <= 24f * density) value.toDouble() else
            (range.start + (range.endInclusive - range.start) *
                if (rtl) 1f - downFraction else downFraction).toDouble()
        appliedX = x
        appliedTimeMs = timeMs
        sensitivity = 0.0
        dragging = false
        moved = false
        active = true
    }

    fun observe(x: Float, timeMs: Long): Float? {
        if (!active) return null
        val delta = x - appliedX
        if (delta == 0f) {
            if (!moved) appliedTimeMs = timeMs
            return null
        }
        val elapsed = (timeMs - appliedTimeMs).coerceAtLeast(1L)
        sensitivity = playerSurfaceContinuousScrubSensitivityMsPerPx(
            durationMs = ((range.endInclusive - range.start) * 1_000f).toLong(),
            trackWidthPx = width, density = density,
            speedDpPerSecond = abs(delta).toDouble() * 1_000.0 / density / elapsed,
            fineFraction = 0f, previousMsPerPx = sensitivity, elapsedMs = elapsed
        )
        precise = (precise + direction * delta * sensitivity / 1_000.0)
            .coerceIn(range.start.toDouble(), range.endInclusive.toDouble())
        appliedX = x
        appliedTimeMs = timeMs
        moved = true
        return if (dragging) precise.toFloat() else null
    }

    fun apply(requested: Float): Float {
        if (!active || !moved) return requested
        dragging = true
        return precise.toFloat()
    }

    fun finish() { active = false }
}

internal const val MIN_PLAYER_IDENTITY_HEIGHT_DP = 60f
internal const val PLAYER_IDENTITY_TIMELINE_CLEARANCE_DP = 16f
internal const val PLAYER_ARTWORK_HEADER_GAP_DP = 2f
internal const val HIGH_DENSITY_ARTWORK_SIZE_DP = 220f
internal const val LOW_DENSITY_ARTWORK_SIZE_DP = 252f
internal const val NO_PANEL_ARTWORK_SIZE_DP = 276f
/** Native player coordinates begin below the accepted 32dp status region. */
internal const val PLAYER_SURFACE_SCREEN_TOP_INSET_DP = 32f
internal const val STEMS_ROW_HEIGHT_DP = 64f
internal const val STEMS_VISIBLE_ROW_HEIGHT_DP = 58f
internal const val STEMS_ROW_SPACING_DP = 2f
internal const val STEMS_OPEN_ROW_HEIGHT_DP = 48f
internal const val STEMS_MIXER_TO_SEEKER_GAP_DP = 8f
internal const val PLAYER_TOOL_DETAIL_MIN_GAP_DP = 8f
internal const val PLAYER_NATIVE_MAX_TITLE_TEXT_SP = 28f
internal const val PLAYER_NATIVE_MAX_ARTIST_TEXT_SP = 18f
internal const val PLAYER_NATIVE_TOOL_LABEL_TEXT_SP = 11.5f

internal fun normalizedPlayerFontScale(fontScale: Float): Float =
    fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f

internal fun playerNativeTextSizePx(
    textSizeSp: Float,
    density: Float,
    fontScale: Float
): Float {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    return textSizeSp.coerceAtLeast(0f) * safeDensity * normalizedPlayerFontScale(fontScale)
}

/** The clock baseline is authored one ascent above the rail, so growth needs two ascent bands. */
internal fun playerSeekerClockTopClearanceDp(fontScale: Float): Float {
    val scale = normalizedPlayerFontScale(fontScale)
    return PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP *
        (1f + 2f * (scale - 1f).coerceAtLeast(0f)) +
        PLAYER_SURFACE_SEEKER_CLOCK_ADDITIONAL_CLEARANCE_DP
}

internal fun playerExpandedIdentityHeightDp(baseHeightDp: Float, fontScale: Float): Float {
    val scale = normalizedPlayerFontScale(fontScale)
    val largeTextGrowth = (PLAYER_NATIVE_MAX_TITLE_TEXT_SP +
        PLAYER_NATIVE_MAX_ARTIST_TEXT_SP) * 1.2f * (scale - 1f).coerceAtLeast(0f)
    return baseHeightDp.coerceAtLeast(0f) + largeTextGrowth
}

internal fun playerToolRowHeightDp(usesStemsLayout: Boolean, fontScale: Float): Float {
    if (playerToolColumnCount(fontScale) == 2) {
        return 2f * maxOf(48f, PLAYER_NATIVE_TOOL_LABEL_TEXT_SP * 1.2f * normalizedPlayerFontScale(fontScale) + 8f)
    }
    val baseHeightDp = if (usesStemsLayout) 56f else PortraitPlayerLayout.TOOL_ROW_HEIGHT_DP
    val textGrowthDp = PLAYER_NATIVE_TOOL_LABEL_TEXT_SP * 1.2f *
        (normalizedPlayerFontScale(fontScale) - 1f).coerceAtLeast(0f)
    return baseHeightDp + textGrowthDp
}

internal fun playerToolColumnCount(fontScale: Float): Int =
    if (normalizedPlayerFontScale(fontScale) >= 1.3f) 2 else 4

internal fun playerToolLabelBaselineOffsetDp(fontScale: Float): Float =
    30f + PLAYER_NATIVE_TOOL_LABEL_TEXT_SP * .6f *
        (normalizedPlayerFontScale(fontScale) - 1f).coerceAtLeast(0f)

internal data class CompactPlayerVisualGeometry(
    val leftDp: Float,
    val topDp: Float,
    val rightDp: Float,
    val bottomDp: Float,
    val radiusDp: Float
) {
    val widthDp: Float get() = (rightDp - leftDp).coerceAtLeast(0f)
    val heightDp: Float get() = (bottomDp - topDp).coerceAtLeast(0f)
}

/**
 * Shared-container background geometry for the full-height player sheet.
 *
 * At MINI the only revealed owner is a closed 64 dp card inside its 68 dp envelope. Expansion
 * grows that same background to the full sheet instead of relying on an offscreen bottom edge.
 */
internal fun compactPlayerVisualGeometry(
    availableWidthDp: Float,
    availableHeightDp: Float,
    expansionProgress: Float
): CompactPlayerVisualGeometry {
    val width = availableWidthDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val height = availableHeightDp.takeIf(Float::isFinite)
        ?.coerceAtLeast(PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP)
        ?: PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP
    val progress = expansionProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val compactWeight = 1f - progress
    val horizontalInset = PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP * compactWeight
    val top = PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP * compactWeight
    val compactBottom = PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP -
        PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP
    return CompactPlayerVisualGeometry(
        leftDp = horizontalInset,
        topDp = top,
        rightDp = (width - horizontalInset).coerceAtLeast(horizontalInset),
        bottomDp = compactBottom + (height - compactBottom) * progress,
        radiusDp = PLAYER_SURFACE_COMPACT_VISIBLE_RADIUS_DP * compactWeight
    )
}

private val PLAYER_DETAILS = setOf(
    PLAYER_DETAIL_LOOP,
    PLAYER_DETAIL_TUNE,
    PLAYER_DETAIL_METRONOME
)

internal object CompactTuneMetrics {
    // The whole slider row is a forgiving drag target, not just the visible thumb/track.
    const val KEY_ROW_HEIGHT_DP = 104f
    const val SPEED_ROW_HEIGHT_DP = 104f
    const val COMPACT_ROW_HEIGHT_DP = 48f
}

internal const val MIN_TUNE_TEMPO = .5f
internal const val MAX_TUNE_TEMPO = 2f

/** Integer BPMs the tempo engine can actually reach from this song's detected grid. */
internal fun reachableMetronomeBpmRange(gridBpm: Float): ClosedFloatingPointRange<Float> {
    val base = gridBpm.takeIf { it.isFinite() && it > 0f } ?: 120f
    val minimum = ceil((base * MIN_TUNE_TEMPO).toDouble()).toFloat()
    val maximum = floor((base * MAX_TUNE_TEMPO).toDouble()).toFloat()
    return if (minimum <= maximum) minimum..maximum else
        base * MIN_TUNE_TEMPO..base * MAX_TUNE_TEMPO
}

internal object CompactMetronomeMetrics {
    const val METER_TOUCH_TARGET_SIZE_DP = 48f
    const val CARD_CORNER_RADIUS_DP = 20f
    const val TOTAL_HEIGHT_DP = 284f

    const val POWER_SIZE_DP = 52f
    const val POWER_CORNER_RADIUS_DP = 16f
    const val POWER_GLYPH_SIZE_DP = 24f

    const val SUBDIVISION_CARD_HEIGHT_DP = 52f
    const val SUBDIVISION_CARD_CORNER_RADIUS_DP = 16f

}

internal fun resolvedPlayerTool(requested: String?): String? =
    requested?.takeIf { it in PLAYER_DETAILS }

/** One identity mapping shared by the native snapshot and its transient tool-motion target. */
internal fun playerSurfaceToolForDetail(requested: String?): PlayerSurfaceTool = when (resolvedPlayerTool(requested)) {
    PLAYER_DETAIL_LOOP -> PlayerSurfaceTool.LOOP
    PLAYER_DETAIL_TUNE -> PlayerSurfaceTool.TUNE
    PLAYER_DETAIL_METRONOME -> PlayerSurfaceTool.METRONOME
    else -> PlayerSurfaceTool.NONE
}

/** Transient song loading may withdraw a tool before the selected UI finishes its exit. */
internal fun availablePlayerSurfaceTool(
    selected: PlayerSurfaceTool,
    lyricsAvailable: Boolean,
    loopAvailable: Boolean
): PlayerSurfaceTool = selected.takeUnless {
    (it == PlayerSurfaceTool.LYRICS && !lyricsAvailable) ||
        (it == PlayerSurfaceTool.LOOP && !loopAvailable)
} ?: PlayerSurfaceTool.NONE

/** Moving detail controls are not public accessibility targets until pixels and hit motors agree. */
internal fun playerToolDetailSemanticsReady(
    targetDetail: String?,
    selectionEpoch: Long,
    settledEpoch: Long
): Boolean = targetDetail != null && settledEpoch >= selectionEpoch

/**
 * The four lower buttons select one detail surface. Selecting the already-visible surface closes
 * it; selecting another replaces it. Runtime Loop and Metronome activation is intentionally
 * separate, so closing a panel never changes sound or destroys A/B points.
 */
internal fun toggledPlayerDetail(current: String?, requested: String): String? {
    if (requested !in PLAYER_DETAILS) return current
    return if (current == requested) null else requested
}

/** An existing B remains editable after playback wraps back near A. */
internal fun loopPointBEnabled(positionMs: Long, loopA: Long?, loopB: Long?): Boolean =
    loopB != null || loopA?.let { start ->
        positionMs - start >= AudioPlayback.MINIMUM_LOOP_DURATION_MS
    } == true

/** One validation rule for an owned A/B handle from DOWN through exact terminal commit. */
internal fun loopBoundaryGestureRangeIsValid(
    boundary: FullPlayerLoopBoundary,
    startMs: Long,
    endMs: Long,
    durationMs: Long
): Boolean {
    val duration = durationMs.coerceAtLeast(0L)
    val minimum = AudioPlayback.MINIMUM_LOOP_DURATION_MS
    if (duration < minimum || startMs !in 0L..duration) return false
    if (endMs < 0L) {
        return boundary == FullPlayerLoopBoundary.START && startMs <= duration - minimum
    }
    return endMs <= duration && endMs - startMs >= minimum
}

/** Metronome measures its own overflow; an outer scroll owner would nest its fixed workspace. */
internal fun playerDetailOwnsBoundedScroll(
    detail: String?, fontScale: Float = 1f, viewportHeightDp: Float = Float.POSITIVE_INFINITY
): Boolean = detail == PLAYER_DETAIL_TUNE ||
    (detail == PLAYER_DETAIL_LOOP &&
        (fontScale >= 1.5f || viewportHeightDp < playerDetailReservedHeightDp(detail)))

/**
 * A new source needs callback proof before its workspace opens. Once opened, keep that workspace
 * across same-selection seek preparation; the source packet separately reports audible ownership.
 */
internal fun playerPresentedSourceMode(
    settledMode: PlaybackSourceMode,
    switchState: SourceSwitchState,
    retainedMode: PlaybackSourceMode = settledMode
): PlaybackSourceMode = when (switchState) {
    SourceSwitchState.Original -> PlaybackSourceMode.ORIGINAL
    is SourceSwitchState.Stems -> PlaybackSourceMode.STEMS
    is SourceSwitchState.PreparingStems,
    SourceSwitchState.ReturningOriginal -> retainedMode
    is SourceSwitchState.Failed -> switchState.retainedSource
}

internal data class PlayerStructuralAudioState(
    val audio: AudioPlaybackState,
    val failureHeading: String?
)

/** High-rate clock and mixer previews must not invalidate the full player composition. */
internal fun playerStructuralAudioState(state: AudioPlaybackState): PlayerStructuralAudioState =
    PlayerStructuralAudioState(state.copy(
        playing = false,
        playWhenReady = false,
        positionMs = 0L,
        ended = false,
        suppression = AudioPlaybackSuppression.NONE,
        stemMix = emptyMap(),
        loadedStemSignature = null,
        repeatMode = "Off",
        shuffleEnabled = false,
        canGoPrevious = false,
        canGoNext = false,
        // The queue has its own narrow player-track collector. Retaining it here would make a
        // Shuffle tap re-enter the entire PlayerScreen composition even though only the three
        // bounded artwork slots need the new stable-ID order.
        queueOrder = emptyList(),
        currentQueueSongId = null
    ), failureHeading = state.error?.let {
        playbackFailureHeading(fatalTransport = !state.playing && !state.playWhenReady)
    })

/**
 * Projects the authoritative playback queue onto the songs currently renderable by the player.
 *
 * Queue order wins for every known stable ID. Unknown/stale IDs are ignored, duplicate queue
 * entries cannot create duplicate pages, and newly imported songs remain reachable in library
 * order until the playback facade incorporates them into its next queue publication.
 */
internal fun orderedPlayerSongIds(
    availableSongIds: List<String>,
    queueOrder: List<String>
): List<String> {
    val available = availableSongIds.distinct()
    if (available.isEmpty()) return emptyList()
    val availableSet = available.toHashSet()
    val queued = queueOrder.asSequence()
        .filter(availableSet::contains)
        .distinct()
        .toList()
    if (queued.isEmpty()) return available
    val queuedSet = queued.toHashSet()
    return queued + available.filterNot(queuedSet::contains)
}

/** Minimal transport projection collected only by the two small control subtrees. */
internal data class PlayerTransportUiState(
    val playWhenReady: Boolean = false,
    val repeatMode: String = "Off",
    val shuffleEnabled: Boolean = false,
    val previousEnabled: Boolean = false,
    val canGoNext: Boolean = false,
    /** Queue intent may advance before callback-owned metadata; keep artwork on that exact target. */
    val currentQueueSongId: String? = null
)

internal fun playerPreviousOrRestartEnabled(
    state: AudioPlaybackState,
    currentQueueSongId: String?
): Boolean =
    state.canGoPrevious ||
        currentQueueSongId != null

internal fun playerTransportUiState(
    state: AudioPlaybackState,
    currentQueueSongId: String?
): PlayerTransportUiState {
    val acceptedQueueSongId = state.currentQueueSongId ?: currentQueueSongId
    return PlayerTransportUiState(
        playWhenReady = state.playWhenReady,
        repeatMode = state.repeatMode,
        shuffleEnabled = state.shuffleEnabled,
        previousEnabled = playerPreviousOrRestartEnabled(state, acceptedQueueSongId),
        canGoNext = state.canGoNext,
        currentQueueSongId = acceptedQueueSongId
    )
}

internal fun tuneTempoActive(tempo: Float): Boolean = kotlin.math.abs(tempo - 1f) > .0001f

internal fun loopPreparationDescription(state: app.stemwise.playback.LoopPreparationState): String =
    when (state.phase) {
        app.stemwise.playback.LoopPreparationPhase.IDLE -> "Loop is not prepared"
        app.stemwise.playback.LoopPreparationPhase.PREPARING -> "Preparing loop"
        app.stemwise.playback.LoopPreparationPhase.READY -> "Loop ready"
        app.stemwise.playback.LoopPreparationPhase.REJECTED -> state.reason ?: "Loop is unavailable"
        app.stemwise.playback.LoopPreparationPhase.FAILED -> state.reason ?: "Could not prepare loop"
    }

/**
 * Height reserved below the pinned control row, including the common 12 dp panel inset.
 */
internal fun playerDetailReservedHeightDp(detail: String?): Float = when (detail) {
    // Every panel is placed below the common 12 dp inset. Reserve that inset instead of clipping
    // the last row from fixed-height tool content.
    PLAYER_DETAIL_LOOP -> 116f
    PLAYER_DETAIL_TUNE -> CompactTuneMetrics.KEY_ROW_HEIGHT_DP + 12f
    PLAYER_DETAIL_METRONOME -> CompactMetronomeMetrics.TOTAL_HEIGHT_DP + 12f
    else -> 0f
}

/**
 * Returns a bounded detail viewport. Short windows retain a usable panel and let the shared
 * player scroll owner expose the complete native control and tool envelope.
 */
internal fun portraitPlayerDetailViewportHeightDp(
    availableWidthDp: Float,
    availableHeightDp: Float,
    sourceMode: PlaybackSourceMode,
    detail: String?,
    statusHeightDp: Float = 0f,
    loopClearanceProgress: Float = 0f,
    fontScale: Float = 1f,
    mixerLaneCount: Int = 6,
    naturalDetailHeightDp: Float? = null,
    minimumDetailHeightDp: Float = 120f
): Float {
    val naturalHeight = naturalDetailHeightDp ?: playerDetailReservedHeightDp(detail)
    if (naturalHeight <= 0f) return 0f
    val available = availableHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val status = statusHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val includesMixer = sourceMode == PlaybackSourceMode.STEMS
    fun fits(candidate: Float): Boolean {
        val frame = portraitPlayerToolMotionFrame(
            availableWidthDp = availableWidthDp,
            availableHeightDp = available,
            density = 1f,
            detailHeightDp = candidate,
            mixerProgress = if (includesMixer) 1f else 0f,
            statusHeightDp = status,
            loopClearanceProgress = loopClearanceProgress,
            fontScale = fontScale,
            mixerLaneCount = mixerLaneCount
        )
        return playerSurfaceContentHeightDp(frame, 1f, includesMixer, status,
            loopClearanceProgress) <= available + .001f
    }
    if (fits(naturalHeight)) return naturalHeight
    // The fit frame uses whole dp. Search and return that same extent; fractional candidates
    // alternated between fitting and overflowing when the frame rounded them in either direction.
    var lower = 0
    var upper = naturalHeight.toInt()
    while (lower < upper) {
        val candidate = lower + (upper - lower + 1) / 2
        if (fits(candidate.toFloat())) lower = candidate else upper = candidate - 1
    }
    return lower.toFloat().coerceAtLeast(minOf(naturalHeight, minimumDetailHeightDp))
}

/**
 * A timed-lyric tap must not let playback escape a live A-B loop. Lines inside [A, B) seek
 * normally; a line outside restarts at A while preserving both loop points.
 */
internal fun loopAwareLyricSeekTarget(
    requestedMs: Long,
    loopA: Long?,
    loopB: Long?,
    durationMs: Long
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    val target = requestedMs.coerceIn(0L, safeDuration)
    val start = loopA?.coerceIn(0L, safeDuration) ?: return target
    val end = loopB?.coerceIn(0L, safeDuration) ?: return target
    if (end <= start) return target
    return if (target < start || target >= end) start else target
}

/**
 * A canceled pager reconciliation must retain its guard until the exact stable-ID target
 * settles. Clearing it for an intermediate page lets that page become an unintended play action.
 */
internal fun pagerSyncTargetAfterSettling(
    programmaticTargetId: String?,
    settledSongId: String?
): String? = programmaticTargetId?.takeUnless { it == settledSongId }

internal fun stemSliderActiveFraction(volume: Float): Float = volume.coerceIn(0f, 1f)

internal fun stemSliderEffectiveFraction(volume: Float, muted: Boolean): Float =
    if (muted) 0f else stemSliderActiveFraction(volume)

/** Zero means mute; retain the positive gain so the glyph can restore it. */
internal fun stemSliderStoredVolume(volume: Float, previousVolume: Float): Float =
    if (volume > 0f) stemSliderActiveFraction(volume)
    else if (previousVolume > 0f) previousVolume else 1f


/** Maps the full forgiving touch lane onto the visible thumb-center range in either direction. */
internal fun stemSliderFractionFromPointer(
    pointerX: Float,
    widthPx: Float,
    thumbRadiusPx: Float,
    rightToLeft: Boolean
): Float {
    val safeRadius = thumbRadiusPx.coerceAtLeast(0f)
    val trackStart = safeRadius.coerceAtMost(widthPx.coerceAtLeast(0f) / 2f)
    val trackWidth = (widthPx - trackStart * 2f).coerceAtLeast(1f)
    val leftToRight = ((pointerX - trackStart) / trackWidth).coerceIn(0f, 1f)
    return if (rightToLeft) 1f - leftToRight else leftToRight
}

/**
 * Exact portrait dimensions used to keep the initial playback surface above the fold.
 *
 * [availableHeightDp] is measured after the navigation-bar inset. Non-lyric detail panels are
 * budgeted into this surface so their controls do not require outer-player scrolling.
 */
internal data class PortraitPlayerLayout(
    val fontScale: Float,
    val compactChrome: Boolean,
    val artworkSizeDp: Float,
    val headerHeightDp: Float,
    val headerGapDp: Float,
    val identityHeightDp: Float,
    val identityGapDp: Float,
    val listSpacingDp: Float,
    val listBottomPaddingDp: Float,
    val mixerCardHeightDp: Float,
    val mixerSpacingDp: Float,
    val seekerClockTopClearanceDp: Float,
    val seekerTrackHeightDp: Float,
    val transportHeightDp: Float,
    val transportPlaySizeDp: Float
) {
    val motionCoreHeightDp: Float
        get() = artworkSizeDp + headerHeightDp + headerGapDp +
            identityGapDp + identityHeightDp

    val transportToolGapDp: Float
        get() = listSpacingDp

    val toolRowHeightDp: Float
        get() = if (artworkSizeDp == 0f && identityHeightDp == 0f) 48f else playerToolRowHeightDp(false, fontScale)

    /** Exact no-detail chrome height. Detail space is added only while a panel is visible. */
    fun initialChromeHeightDp(includeSixStemMixer: Boolean, mixerLaneCount: Int = 6): Float {
        return portraitPlayerControlTopDp(this, includeSixStemMixer) +
            seekerTrackHeightDp + seekerClockTopClearanceDp +
            transportHeightDp + toolRowHeightDp +
            listSpacingDp + transportToolGapDp + listBottomPaddingDp
    }

    internal companion object {
        const val STEM_MIXER_ROW_COUNT = 6f
        const val SEEKER_CLOCK_HEIGHT_DP = PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP
        // Native tools own a 52 dp target plus their label baseline inside one 68 dp row.
        const val TOOL_ROW_HEIGHT_DP = 68f
    }
}

internal fun portraitPlayerToolDetailGapDp(layout: PortraitPlayerLayout): Float =
    maxOf(PLAYER_TOOL_DETAIL_MIN_GAP_DP, layout.listSpacingDp)

/**
 * One pixel-coherent portrait tool-motion frame. The animated detail height is rounded once before
 * it is consumed by the Compose envelope and native geometry, so the artwork, mixer and viewport
 * cannot disagree by a fractional-pixel frame.
 */
internal data class PortraitPlayerToolMotionFrame(
    val detailHeightPx: Int,
    val layout: PortraitPlayerLayout,
    val mixerLaneCount: Int = 6
)

/** Resolves the existing portrait policy from one rounded detail-height sample. */
internal fun portraitPlayerToolMotionFrame(
    availableWidthDp: Float,
    availableHeightDp: Float,
    density: Float,
    detailHeightDp: Float,
    mixerProgress: Float,
    statusHeightDp: Float = 0f,
    loopClearanceProgress: Float = 0f,
    fontScale: Float = 1f,
    mixerLaneCount: Int = 6
): PortraitPlayerToolMotionFrame {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeDetailHeightDp = detailHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
    val detailHeightPx = (safeDetailHeightDp * safeDensity).roundToInt().coerceAtLeast(0)
    val renderedDetailHeightDp = detailHeightPx / safeDensity
    val routeIdentityHeightDp = portraitPlayerLayout(availableWidthDp, availableHeightDp,
        PlaybackSourceMode.ORIGINAL, panelExpanded = false, fontScale = fontScale,
        mixerLaneCount = mixerLaneCount).identityHeightDp
    fun resolve(source: PlaybackSourceMode): PortraitPlayerLayout {
        var clearance = 0f
        var heightBudget = availableHeightDp
        // Loop clearance can compact the rail from 60 to 56 to 48dp. Rebudget against the
        // chosen profile, not the closed profile: its shorter rail needs MORE handle clearance.
        repeat(8) {
            val layout = portraitPlayerLayout(
                availableWidthDp, heightBudget, source, renderedDetailHeightDp > .1f,
                renderedDetailHeightDp, statusHeightDp, fontScale,
                reservedLoopClearanceHeightDp = clearance,
                mixerLaneCount = mixerLaneCount,
                retainedIdentityHeightDp = routeIdentityHeightDp
            )
            val required = loopLowerStackClearanceDp(layout, loopClearanceProgress)
            if (required > clearance + .001f) {
                clearance = required
            } else {
                // The native stack rounds each child independently. Let artwork absorb those
                // real pixels before a one-pixel overrun installs a separate transport footer.
                val frame = PortraitPlayerToolMotionFrame(detailHeightPx, layout, mixerLaneCount)
                val extentPx = kotlin.math.ceil(playerSurfaceContentHeightDp(frame, safeDensity,
                    source == PlaybackSourceMode.STEMS, statusHeightDp, loopClearanceProgress) * safeDensity).toInt()
                val overflowPx = extentPx - (availableHeightDp * safeDensity).roundToInt()
                if (detailHeightPx == 0 || overflowPx <= 0 || layout.artworkSizeDp <= 96.001f) return layout
                heightBudget -= overflowPx / safeDensity
            }
        }
        error("Player loop clearance failed to converge")
    }
    return PortraitPlayerToolMotionFrame(
        detailHeightPx = detailHeightPx,
        mixerLaneCount = mixerLaneCount,
        layout = when {
            mixerProgress <= 0f -> resolve(PlaybackSourceMode.ORIGINAL)
            mixerProgress >= 1f -> resolve(PlaybackSourceMode.STEMS)
            else -> interpolatePortraitPlayerLayout(
                resolve(PlaybackSourceMode.ORIGINAL), resolve(PlaybackSourceMode.STEMS), mixerProgress
            )
        }
    )
}

/**
 * Exact pixel height of the transparent Compose reservation for native mixer/timeline/transport
 * controls. Each former child and spacing unit is rounded independently to preserve its old
 * layout, while a hidden zero-height mixer contributes neither a child nor an extra gap.
 */
internal fun portraitPlayerControlStackHeightPx(
    frame: PortraitPlayerToolMotionFrame,
    density: Float,
    includeMixer: Boolean,
    loopClearanceProgress: Float = 0f,
    mixerLaneCount: Int = frame.mixerLaneCount
): Int {
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    fun px(dp: Float): Int = (dp.coerceAtLeast(0f) * safeDensity).roundToInt()
    val layout = frame.layout
    return px(layout.seekerTrackHeightDp + layout.seekerClockTopClearanceDp) +
        px(layout.transportHeightDp) + px(layout.toolRowHeightDp) +
        px(layout.listSpacingDp) + px(layout.transportToolGapDp) +
        px(loopLowerStackClearanceDp(layout, loopClearanceProgress))
}

internal fun originalPlayerArtworkTopDp(artworkSizeDp: Float): Float {
    val size = artworkSizeDp.takeIf(Float::isFinite) ?: NO_PANEL_ARTWORK_SIZE_DP
    fun between(startSize: Float, startTop: Float, endSize: Float, endTop: Float): Float =
        startTop + (endTop - startTop) *
            ((size - startSize) / (endSize - startSize)).coerceIn(0f, 1f)
    return when {
        size <= HIGH_DENSITY_ARTWORK_SIZE_DP -> 70f
        size < LOW_DENSITY_ARTWORK_SIZE_DP -> between(
            HIGH_DENSITY_ARTWORK_SIZE_DP,
            70f,
            LOW_DENSITY_ARTWORK_SIZE_DP,
            64f
        )
        size < NO_PANEL_ARTWORK_SIZE_DP -> between(
            LOW_DENSITY_ARTWORK_SIZE_DP,
            64f,
            NO_PANEL_ARTWORK_SIZE_DP,
            70f
        )
        else -> 70f
    }
}

/** Absolute player control anchor with a real identity clearance for panel-open Original. */
internal fun portraitPlayerControlTopDp(
    layout: PortraitPlayerLayout,
    includeMixer: Boolean
): Float {
    if (layout.artworkSizeDp == 0f && layout.identityHeightDp == 0f) return layout.headerHeightDp
    val authoredTop = if (!layout.compactChrome) {
        layout.motionCoreHeightDp - 40f
    } else {
        layout.motionCoreHeightDp - 24f
    }.coerceAtLeast(0f)
    if (!layout.compactChrome && layout.artworkSizeDp >= NO_PANEL_ARTWORK_SIZE_DP - .01f) return authoredTop
    val identityBottom = originalPlayerArtworkTopDp(layout.artworkSizeDp) +
        layout.artworkSizeDp + 10f + layout.identityHeightDp
    val minimumWorkspaceTop = PLAYER_SURFACE_HEADER_HEIGHT_DP + PLAYER_ARTWORK_HEADER_GAP_DP
    val pressureSafeControlTop = minimumWorkspaceTop + layout.artworkSizeDp + 10f +
        layout.identityHeightDp + PLAYER_IDENTITY_TIMELINE_CLEARANCE_DP
    return maxOf(authoredTop, identityBottom + layout.listSpacingDp, pressureSafeControlTop)
}

internal fun loopLowerStackClearanceDp(
    layout: PortraitPlayerLayout,
    loopProgress: Float
): Float {
    val loop = loopProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    if (loop <= 0f) return 0f
    val railTop = (layout.seekerTrackHeightDp * .38f).coerceIn(18f, 24f)
    // Reserve the maximum handle envelope for the loop's lifetime. Cursor Fine movement remains
    // a render-only morph and cannot retarget transport/tool layout on every entry or exit.
    val requiredPlayTop = railTop + 2f + StemwiseSeekerDesign.LOOP_GUIDE_FINE_HEIGHT_DP +
        StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP + layout.listSpacingDp
    val basePlayTop = layout.seekerTrackHeightDp + layout.listSpacingDp
    return (requiredPlayTop - basePlayTop).coerceAtLeast(0f) * loop
}

/** Compact width uses 16 dp gutters; wider phone profiles retain the 20 dp gutter. */
internal fun portraitPlayerHorizontalPaddingDp(availableWidthDp: Float): Float =
    if (availableWidthDp.takeIf(Float::isFinite).orZero() < 392f) 16f else 20f

/** Complete unscaled body extent consumed by the between-bars outer scroll owner. */
internal fun portraitPlayerContentHeightDp(
    layout: PortraitPlayerLayout,
    includeSixStemMixer: Boolean,
    detailHeightDp: Float,
    statusHeightDp: Float = 0f,
    mixerLaneCount: Int = 6
): Float {
    val detailHeight = detailHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f).orZero()
    return layout.initialChromeHeightDp(includeSixStemMixer, mixerLaneCount) +
        detailHeight +
        (if (detailHeight > .1f) portraitPlayerToolDetailGapDp(layout) else 0f) +
        statusHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f).orZero()
}

private fun Float?.orZero(): Float = this ?: 0f

/** One reversible geometry interpolation for Original <-> Stems workspace changes. */
internal fun interpolatePortraitPlayerLayout(
    original: PortraitPlayerLayout,
    stems: PortraitPlayerLayout,
    progress: Float
): PortraitPlayerLayout {
    val p = progress.coerceIn(0f, 1f)
    if (p == 0f) return original
    if (p == 1f) return stems
    fun between(start: Float, end: Float): Float = start + (end - start) * p
    val candidate = PortraitPlayerLayout(
        fontScale = between(original.fontScale, stems.fontScale),
        compactChrome = if (p < .5f) original.compactChrome else stems.compactChrome,
        artworkSizeDp = between(original.artworkSizeDp, stems.artworkSizeDp),
        headerHeightDp = between(original.headerHeightDp, stems.headerHeightDp),
        headerGapDp = between(original.headerGapDp, stems.headerGapDp),
        identityHeightDp = between(original.identityHeightDp, stems.identityHeightDp),
        identityGapDp = between(original.identityGapDp, stems.identityGapDp),
        listSpacingDp = between(original.listSpacingDp, stems.listSpacingDp),
        listBottomPaddingDp = between(original.listBottomPaddingDp, stems.listBottomPaddingDp),
        // Mount the declared lanes at once; endpoint interpolation keeps every motor at or above its
        // 48 dp floor while a bounded detail viewport absorbs any remaining height pressure.
        mixerCardHeightDp = if (p <= .001f) 0f else stems.mixerCardHeightDp,
        mixerSpacingDp = if (p <= .001f) 0f else stems.mixerSpacingDp,
        seekerClockTopClearanceDp = between(
            original.seekerClockTopClearanceDp,
            stems.seekerClockTopClearanceDp
        ),
        seekerTrackHeightDp = between(original.seekerTrackHeightDp, stems.seekerTrackHeightDp),
        transportHeightDp = between(original.transportHeightDp, stems.transportHeightDp),
        transportPlaySizeDp = between(original.transportPlaySizeDp, stems.transportPlaySizeDp)
    )
    return candidate
}

internal fun portraitPlayerLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    sourceMode: PlaybackSourceMode,
    panelExpanded: Boolean,
    reservedDetailHeightDp: Float = 0f,
    reservedStatusHeightDp: Float = 0f,
    fontScale: Float = 1f,
    reservedLoopClearanceHeightDp: Float = 0f,
    mixerLaneCount: Int = 6,
    retainedIdentityHeightDp: Float? = null
): PortraitPlayerLayout {
    val safeWidth = availableWidthDp.coerceAtLeast(120f)
    val safeHeight = availableHeightDp.coerceAtLeast(1f)
    val contentHeight = (
        safeHeight - reservedStatusHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f).orZero() -
            reservedLoopClearanceHeightDp.takeIf(Float::isFinite)?.coerceAtLeast(0f).orZero()
        ).coerceAtLeast(1f)
    val safeFontScale = normalizedPlayerFontScale(fontScale)
    val seekerClockTopClearanceDp = playerSeekerClockTopClearanceDp(safeFontScale)
    val includesMixer = sourceMode == PlaybackSourceMode.STEMS
    if (includesMixer) {
        val original = portraitPlayerLayout(availableWidthDp, availableHeightDp, PlaybackSourceMode.ORIGINAL,
            panelExpanded, reservedDetailHeightDp, reservedStatusHeightDp, fontScale,
            reservedLoopClearanceHeightDp, mixerLaneCount, retainedIdentityHeightDp)
        if (original.artworkSizeDp <= .01f) return original
        val viewport = original.artworkSizeDp
            .coerceAtLeast(STEMS_OPEN_ROW_HEIGHT_DP)
        val rows = app.stemwise.ui.player.surface.playerSurfaceMixerVisibleLaneCount(
            mixerLaneCount, viewport, STEMS_OPEN_ROW_HEIGHT_DP, STEMS_ROW_SPACING_DP).coerceAtLeast(1)
        return original.copy(mixerCardHeightDp =
            ((viewport - STEMS_ROW_SPACING_DP * (rows - 1)) / rows).coerceIn(48f, 64f),
            mixerSpacingDp = STEMS_ROW_SPACING_DP)
    }
    // Tool motion retains the closed route's text metrics while artwork absorbs pressure.
    val routeIdentityHeightDp = retainedIdentityHeightDp ?: if (panelExpanded) portraitPlayerLayout(
        availableWidthDp, availableHeightDp, PlaybackSourceMode.ORIGINAL,
        panelExpanded = false, fontScale = safeFontScale, mixerLaneCount = mixerLaneCount
    ).identityHeightDp else null
    val compactBase = PortraitPlayerLayout(
        fontScale = safeFontScale,
        compactChrome = true,
        artworkSizeDp = 0f,
        // The compact header places collapse, source and info controls in one 48 dp row.
        headerHeightDp = 60f,
        headerGapDp = 3f,
        // headlineSmall plus the artist line need more than their visible glyph bounds.
        // Keep breathing room here and let the artwork absorb the height reduction.
        identityHeightDp = routeIdentityHeightDp ?: playerExpandedIdentityHeightDp(
            MIN_PLAYER_IDENTITY_HEIGHT_DP,
            safeFontScale
        ),
        identityGapDp = 3f,
        listSpacingDp = 2f,
        listBottomPaddingDp = 0f,
        mixerCardHeightDp = 0f,
        mixerSpacingDp = 0f,
        seekerClockTopClearanceDp = seekerClockTopClearanceDp,
        seekerTrackHeightDp = 48f,
        transportHeightDp = 74f,
        transportPlaySizeDp = 68f
    )
    if (panelExpanded && safeHeight < 480f) {
        return compactBase.copy(headerHeightDp = 54f, headerGapDp = 0f,
            identityHeightDp = 0f, identityGapDp = 0f, listSpacingDp = 0f, transportHeightDp = 68f,
            seekerClockTopClearanceDp = 0f)
    }
    val regularBase = PortraitPlayerLayout(
        fontScale = safeFontScale,
        compactChrome = false,
        artworkSizeDp = 0f,
        // The regular layout still uses comfortable 48+ dp targets, but it cannot spend so much
        // fixed height that opening the full four-row metronome crushes the artwork or introduces
        // outer-player scrolling on the owner's portrait viewport.
        headerHeightDp = 100f,
        // The native balance cannot move the square into the header's 60dp input band.
        headerGapDp = 18f,
        identityHeightDp = routeIdentityHeightDp ?: playerExpandedIdentityHeightDp(64f, safeFontScale),
        identityGapDp = 10f,
        listSpacingDp = 10f,
        // One spacing unit used to be hidden in this trailing padding. The real tree now counts
        // its final controls -> detail gap explicitly, so keep the same complete envelope while
        // reserving that space where the 48 dp tool row actually needs it.
        listBottomPaddingDp = 10f,
        mixerCardHeightDp = 0f,
        mixerSpacingDp = 0f,
        seekerClockTopClearanceDp = seekerClockTopClearanceDp,
        seekerTrackHeightDp = 60f,
        transportHeightDp = 74f,
        transportPlaySizeDp = 68f
    )
    val mediumBase = PortraitPlayerLayout(
        fontScale = safeFontScale,
        compactChrome = true,
        artworkSizeDp = 0f,
        headerHeightDp = 72f,
        headerGapDp = 6f,
        identityHeightDp = routeIdentityHeightDp ?: playerExpandedIdentityHeightDp(
            MIN_PLAYER_IDENTITY_HEIGHT_DP,
            safeFontScale
        ),
        identityGapDp = 6f,
        listSpacingDp = 6f,
        listBottomPaddingDp = 8f,
        mixerCardHeightDp = 0f,
        mixerSpacingDp = 0f,
        seekerClockTopClearanceDp = seekerClockTopClearanceDp,
        seekerTrackHeightDp = 56f,
        transportHeightDp = 74f,
        transportPlaySizeDp = 68f
    )
    // Hidden tools reserve nothing. The shared native tool clock animates between the selected
    // endpoint profiles when a detail panel actually opens or closes.
    val detailEnvelope = reservedDetailHeightDp.takeIf { panelExpanded && it.isFinite() }
        ?.coerceAtLeast(0f) ?: 0f
    val detailGap = if (detailEnvelope > .1f) PLAYER_TOOL_DETAIL_MIN_GAP_DP else 0f
    val regularOpenBase = regularBase.copy(transportHeightDp = 74f, transportPlaySizeDp = 68f)
    val mediumOpenBase = mediumBase.copy(transportHeightDp = 74f, transportPlaySizeDp = 68f)
    val minimumArtwork = if (safeHeight < 600f) 96f else HIGH_DENSITY_ARTWORK_SIZE_DP
    val regularEnvelopeHeight = regularOpenBase.initialChromeHeightDp(false) +
        minimumArtwork +
        detailEnvelope + detailGap
    val mediumEnvelopeHeight = mediumOpenBase.initialChromeHeightDp(false) +
        minimumArtwork + detailEnvelope + detailGap
    val base = when {
        contentHeight >= regularEnvelopeHeight -> regularBase
        contentHeight >= mediumEnvelopeHeight -> mediumBase
        else -> compactBase
    }
    val widthCap = (
        safeWidth - portraitPlayerHorizontalPaddingDp(safeWidth) * 2f
        ).coerceAtLeast(minimumArtwork)
    // Every expanded/full-player profile owns the accepted 68 dp primary transport. Height
    // pressure is absorbed by artwork sizing or the outer scroll, never by shrinking this body.
    val resolvedBase = base

    val withoutArtwork = resolvedBase.initialChromeHeightDp(false)
    val heightCap = (
        contentHeight - withoutArtwork - detailEnvelope -
            if (detailEnvelope > .1f) portraitPlayerToolDetailGapDp(resolvedBase) else 0f
        )
        // Artwork absorbs pressure before a fixed-height tool loses controls below the screen.
        // The workspace uses the full remaining square envelope beside the fixed controls.
        .coerceAtLeast(if (panelExpanded) 96f else minimumArtwork)
    val layout = resolvedBase.copy(artworkSizeDp = minOf(heightCap, widthCap))
    if (panelExpanded) return layout
    val spareHeight = (contentHeight - layout.initialChromeHeightDp(false) - 24f)
        .coerceAtLeast(0f)
    val extraSpacing = minOf(14f, spareHeight / 4f)
    return layout.copy(
        headerGapDp = layout.headerGapDp + spareHeight - 2f * extraSpacing,
        listSpacingDp = layout.listSpacingDp + extraSpacing
    )
}

/** Repeated selection manages an installed package without publishing another source intent. */
internal fun sourcePartsActionsRequested(
    target: PlaybackSourceMode,
    hasCompleteStemSet: Boolean,
    switchState: SourceSwitchState
): Boolean = target == PlaybackSourceMode.STEMS && hasCompleteStemSet && switchState is SourceSwitchState.Stems

internal fun sourceTargetEnabled(
    target: PlaybackSourceMode,
    hasCompleteStemSet: Boolean,
    switchState: SourceSwitchState
): Boolean = when (target) {
    PlaybackSourceMode.ORIGINAL -> switchState !is SourceSwitchState.Original &&
        switchState !is SourceSwitchState.ReturningOriginal
    PlaybackSourceMode.STEMS ->
        hasCompleteStemSet && switchState !is SourceSwitchState.PreparingStems &&
            switchState !is SourceSwitchState.Stems
}

/** Scroll extent from the actual native control anchor and the mounted detail viewport. */
internal fun playerSurfaceContentHeightDp(
    frame: PortraitPlayerToolMotionFrame,
    density: Float,
    includeMixer: Boolean,
    statusHeightDp: Float,
    loopClearanceProgress: Float
): Float = portraitPlayerControlTopDp(frame.layout, includeMixer) +
    portraitPlayerControlStackHeightPx(frame, density, includeMixer, loopClearanceProgress) / density +
    frame.detailHeightPx / density +
    (if (frame.detailHeightPx > 0) portraitPlayerToolDetailGapDp(frame.layout) else 0f) +
    statusHeightDp.coerceAtLeast(0f)
