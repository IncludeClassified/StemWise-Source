package app.stemwise.ui.player.surface

import app.stemwise.ui.theme.INK_ARGB as INK
import app.stemwise.ui.theme.SURFACE_LOW_ARGB as SURFACE_LOW
import app.stemwise.ui.theme.SURFACE_ARGB as SURFACE
import app.stemwise.ui.theme.PRIMARY_PURPLE_ARGB as PRIMARY_PURPLE
import app.stemwise.ui.theme.PRIMARY_BRIGHT_ARGB as PRIMARY_BRIGHT
import app.stemwise.ui.theme.TEXT_PRIMARY_ARGB as TEXT_PRIMARY
import app.stemwise.ui.theme.TEXT_SECONDARY_ARGB as TEXT_SECONDARY
import app.stemwise.ui.theme.INACTIVE_ARGB as INACTIVE
import app.stemwise.ui.theme.OUTLINE_ARGB as OUTLINE
import app.stemwise.ui.theme.SELECTED_SHADOW_ARGB as SELECTED_SHADOW
import app.stemwise.ui.theme.ARTWORK_AURA_BLUE_ARGB
import app.stemwise.ui.theme.ARTWORK_AURA_GRAPHITE_ARGB
import app.stemwise.ui.theme.COMPACT_PROGRESS_FILL_ARGB
import app.stemwise.ui.theme.CORAL_ARGB

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Picture
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import app.stemwise.ui.design.performStemwiseHapticFeedback
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.VelocityTracker
import android.widget.OverScroller
import androidx.annotation.ColorInt
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.toArgb
import app.stemwise.playback.STANDARD_SEEK_INCREMENT_MS
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackQueueController
import app.stemwise.ui.PlayerMotionPolicy
import app.stemwise.ui.HIGH_DENSITY_ARTWORK_SIZE_DP
import app.stemwise.ui.LOW_DENSITY_ARTWORK_SIZE_DP
import app.stemwise.ui.NO_PANEL_ARTWORK_SIZE_DP
import app.stemwise.ui.PLAYER_IDENTITY_TIMELINE_CLEARANCE_DP
import app.stemwise.ui.PLAYER_ARTWORK_HEADER_GAP_DP
import app.stemwise.ui.PLAYER_SURFACE_SCREEN_TOP_INSET_DP
import app.stemwise.ui.PrecisionScrubMode
import app.stemwise.ui.normalizedPlayerFontScale
import app.stemwise.ui.playerNativeTextSizePx
import app.stemwise.ui.playerToolLabelBaselineOffsetDp
import app.stemwise.ui.playerToolRowHeightDp
import app.stemwise.ui.playerToolColumnCount
import app.stemwise.ui.TrackVisualDirection
import app.stemwise.ui.artworkAuraRetainedPaddingDp
import app.stemwise.ui.deriveArtworkAuraPalette
import app.stemwise.ui.ArtworkAuraPaletteCache
import app.stemwise.artwork.artworkDiskCacheIdentity
import app.stemwise.ui.ARTWORK_AURA_BRIGHT_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_BRIGHT_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_LUMINANCE_OFFSET
import app.stemwise.ui.ARTWORK_AURA_DIM_OPACITY_OFFSET
import app.stemwise.ui.ARTWORK_AURA_FRAME_INTERVAL_MS
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.design.StemwiseAuraContract
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogSong
import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerAdoptionResult
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import app.stemwise.ui.player.nativeplayer.FullPlayerStateStore
import app.stemwise.ui.acceptedTargetCarryOffset
import app.stemwise.ui.fineModeEntryThresholdPx
import app.stemwise.ui.originalPlayerArtworkTopDp
import app.stemwise.ui.fineModeExitThresholdPx
import app.stemwise.ui.loopStartDetentStep
import app.stemwise.ui.quantizeSeekPosition
import app.stemwise.ui.stemSliderActiveFraction
import app.stemwise.ui.stemSliderFractionFromPointer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.LinkedHashMap

/**
 * One Android View owns every expanded-player pixel that can change at pointer/frame frequency.
 * Compose remains responsible for status/detail content and a transparent semantics projection.
 */
internal class PlayerSurfaceView(context: Context, private val seekerOnly: Boolean = false) : ViewGroup(context) {
    private val seekerDrawing = SeekerDrawing()
    private val mixerDrawing = MixerDrawing()
    private val pagerDrawing = PagerDrawing()

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val artworkDoubleTapSlop =
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private val fineEntryThreshold = fineModeEntryThresholdPx(touchSlop, density)
    private val fineExitThreshold = fineModeExitThresholdPx(touchSlop, density)
    private val iconAtlas = PlayerSurfaceIconAtlas()
    private val fullPlayerState = FullPlayerStateStore()
    private val fullPlayerBindGate = PlayerSurfaceSnapshotBindGate()
    private val layoutOwner = PlayerSurfaceLayoutOwner()
    private val artworkLoader = PlayerSurfaceArtworkLoader(context)
    private val mixerChromeLayer = createPlayerSurfaceRetainedLayer("StemWise mixer chrome")
    private val transportChromeLayer = createPlayerSurfaceRetainedLayer("StemWise transport chrome")

    // System sans only. Cache the exact weights once; frame-time drawing never creates typefaces.
    private val systemRegularTypeface = Typeface.create(Typeface.DEFAULT, 400, false)
    private val systemMediumTypeface = Typeface.create(Typeface.DEFAULT, 500, false)
    private val systemSemiboldTypeface = Typeface.create(Typeface.DEFAULT, 600, false)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val motionDrawing = PlayerSurfaceMotionPainter(density, fillPaint, iconAtlas, PRIMARY_BRIGHT, SELECTED_SHADOW)
    private val chromeDrawing = PlayerSurfaceChromePainter(density, iconAtlas, motionDrawing, TEXT_PRIMARY, PRIMARY_BRIGHT)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }


    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = systemRegularTypeface
        fontFeatureSettings = "tnum"
    }
    private val sourceSelectedStates = Array(2) { NativeSelectedStateEnvelope() }
    private val mixerSelectedStates = Array(PLAYER_SURFACE_MAX_MIXER_LANES * 2) {
        NativeSelectedStateEnvelope()
    }
    private val transportSelectedStates = Array(5) { NativeSelectedStateEnvelope() }
    private val toolSelectedStates = Array(4) { NativeSelectedStateEnvelope() }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = systemSemiboldTypeface
    }
    private val formatLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = systemSemiboldTypeface
    }
    private val identityLayoutCache = PlayerSurfaceTextLayouts(MAX_IDENTITY_LAYOUT_WIDTH_PX)
    private val path = Path()
    private val miniProgressDrawing = PlayerSurfaceMiniProgressPainter(density, fillPaint, strokePaint, path)
    private val bitmapMatrix = Matrix()
    private val interactionMatrix = Matrix()
    private val clockChars = CharArray(16)
    private val durationChars = CharArray(16)
    private val toolDisplayLabels = TOOL_LABELS.copyOf()
    private val buttonBloom = NativePressBloomState()
    private val buttonBloomFrame = NativePressBloomFrame()
    private var buttonAuraKind = BUTTON_NONE
    private var buttonAuraIndex = -1
    private val mixerThumbGrowth = Array(PLAYER_SURFACE_MAX_MIXER_LANES) { NativeSelectedStateEnvelope() }
    private val mixerValueLabels = Array(PLAYER_SURFACE_MAX_MIXER_LANES) {
        NativeSelectedStateEnvelope(app.stemwise.ui.design.StemwiseSliderValueLabelMetrics.FADE_DURATION_MS)
            .apply { sample(false, 0L, true) }
    }
    private val scrubHeldHighlight = NativeHeldHighlightState()
    private val mixerHeldHighlights = Array(PLAYER_SURFACE_MAX_MIXER_LANES) { NativeHeldHighlightState() }
    private var loopHandleShadowBitmap: Bitmap? = null
    private val loopHandleShadowDestination = RectF()
    private val loopHandleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var boundaryPlaybackCursorBitmap: Bitmap? = null
    private var boundaryPlaybackCursorRadius = 0f
    private val boundaryPlaybackCursorDestination = RectF()
    private val boundaryPlaybackCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectedFillShader = LinearGradient(
        0f,
        0f,
        1f,
        1f,
        intArrayOf(0xB84C1A83.toInt(), 0x9E1B0D2B.toInt()),
        null,
        Shader.TileMode.CLAMP
    )
    private val artworkAuraPaletteRevision = LongArray(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT)

    private var profile: PlayerSurfaceProfilePacket? = null
    private var visuals: FullPlayerSnapshot? = null
    private var sink: FullPlayerActionSink? = null
    private var positionProvider: PlayerSurfacePositionProvider? = null

    private var expansionProgress = 1f
    private var artworkAlpha = 1f
    private var sourceWorkspaceProgress = 0f
    private var sourceMotionPending = false
    private var sourceMotionActive = false
    private var sourceMotionStartProgress = 0f
    private var sourceMotionTargetProgress = 0f
    private var sourceMotionStartSeekerTop = 0f
    private var sourceMotionTargetSeekerTop = 0f
    private var sourceMotionSeekerTop = Float.NaN
    private var outgoingMixerPicture: Picture? = null
    private val outgoingMixerBounds = RectF()
    private var outgoingMixerSeekerTop = 0f
    private var expandedArtworkSizePx = 0f
    private var expandedHeaderHeightPx = 0f
    private var expandedHeaderGapPx = 0f
    private var expandedIdentityHeightPx = 0f
    private var expandedIdentityGapPx = 0f
    private var compactChrome = false
    private var listSpacingPx = 0f
    private var fontScale = 1f
    private var seekerClockTopClearancePx = PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP * density
    private var mixerCardHeightPx = 0f
    private var mixerSpacingPx = 0f
    private var seekerTrackHeightPx = 0f
    private var transportHeightPx = 0f
    private var transportPlaySizePx = 0f

    private var controlLeft = 0f
    private var controlTop = 0f
    private var controlRight = 0f
    private var controlBottom = 0f
    private var controlsValid = false

    private val routeGeometry = PlayerSurfaceRouteGeometry()

    /** Source selector geometry. */
    private var sourceOriginalLeft = 0f
    private var sourceOriginalTop = 0f
    private var sourceOriginalRight = 0f
    private var sourceOriginalBottom = 0f
    private var sourceStemsLeft = 0f
    private var sourceStemsTop = 0f
    private var sourceStemsRight = 0f
    private var sourceStemsBottom = 0f

    /** Control geometry. */
    private var mixerHeaderTop = 0f
    private var mixerGridTop = 0f
    private var mixerGridBottom = 0f
    private var mixerGridLeft = 0f
    private var mixerGridRight = 0f
    private var mixerScrollOffsetPx = 0f
    private var mixerScrollMaximumPx = 0f
    private var mixerScrollListener: ((PlayerSurfaceSemanticsGeometry?) -> Unit)? = null
    private data class MixerListTouch(
        val owner: PlayerSurfaceOwnerKey, val packageRevision: Long, val lane: Int,
        val target: Int, val downX: Float, val downY: Float,
        var lastY: Float, var scrolling: Boolean = false, var cancelled: Boolean = false
    )
    private val mixerListTouches = SparseArray<MixerListTouch>()
    private var mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
    private val mixerScroller = OverScroller(context)
    private var mixerVelocityTracker: VelocityTracker? = null
    private var mixerFlingOwner: PlayerSurfaceOwnerKey? = null
    private var mixerFlingPackageRevision = 0L
    private var seekerTop = 0f
    private var seekerBottom = 0f
    private var seekerTrackLeft = 0f
    private var seekerTrackRight = 0f
    private var externalTransport = false

    fun setExternalTransport(external: Boolean) {
        if (externalTransport == external) return
        cancelAllGestures(sealMixer = true, hardResetScrub = true)
        externalTransport = external
        transportChromeDirty = true
        invalidateNonPagerRegions()
    }

    private var transportTop = 0f
    private var transportBottom = 0f
    private var toolTop = 0f
    private var toolBottom = 0f
    private val controlGeometry = PlayerSurfaceControlGeometry()

    private val mixerGestures = PlayerMixerGestureOwner(object : PlayerMixerGestureHost {
        override val profile get() = this@PlayerSurfaceView.profile
        override val sink get() = this@PlayerSurfaceView.sink
        override val liveSourceMode get() = this@PlayerSurfaceView.liveSourceMode
        override val laneSliderBounds get() = this@PlayerSurfaceView.controlGeometry.sliders
        override val thumbRadiusPx get() = MIXER_SLIDER_THUMB_RADIUS_DP * density
        override fun clearPendingArtworkTap() = this@PlayerSurfaceView.clearPendingArtworkTap()
        override fun scheduleFrame() = this@PlayerSurfaceView.scheduleFrame()
        override fun post(action: Runnable) = this@PlayerSurfaceView.post(action)
        override fun hold(lane: Int, down: Boolean) {
            if (down) mixerHeldHighlights[lane].pointerDown() else mixerHeldHighlights[lane].pointerUpOrCancel()
        }
        override fun tick() { performStemwiseHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        override fun disallowParentInterception() { parent?.requestDisallowInterceptTouchEvent(true) }
        override fun releaseParentInterceptionIfIdle() = this@PlayerSurfaceView.releaseParentInterceptionIfIdle()
        override fun invalidateMixerRegion() = this@PlayerSurfaceView.invalidateMixerRegion()
        override fun invalidateMixerRegionFromInput() = this@PlayerSurfaceView.invalidateMixerRegionFromInput()
    })
    private val mixerLaneIcons = Array(PLAYER_SURFACE_MAX_MIXER_LANES) {
        app.stemwise.ui.design.generated.StemwiseIconRole.PLAYER_STEM_OTHER
    }

    /** Pager gesture/spring state. */
    private val pendingVisualSelection = PlayerSurfacePendingVisualSelection()
    private var visualQueueIndex = -1
    private var pagerOffsetFraction = 0f
    private var pagerVelocityFraction = 0f
    private var pagerAnimating = false
    private val pagerGestures = PlayerPagerGestureOwner(touchSlop, PAGER_DRAG_GAIN)
    private var compactOpenAttemptCount = 0L
    private var compactOpenAcceptedCount = 0L
    private var compactMiniPlayAttemptCount = 0L
    private var compactMiniPlayAcceptedCount = 0L
    private var lastArtworkTapTime = 0L
    private var lastArtworkTapX = 0f
    private var lastArtworkTapY = 0f
    private var lastArtworkTapGeneration = Long.MIN_VALUE
    private var artworkFeedbackForward = true

    private val seekerGestures = PlayerSeekerGestureOwner(object : PlayerSeekerGestureHost {
        override val profile get() = this@PlayerSurfaceView.profile
        override val sink get() = this@PlayerSurfaceView.sink
        override val density get() = this@PlayerSurfaceView.density
        override val fineEntryThreshold get() = this@PlayerSurfaceView.fineEntryThreshold
        override val fineExitThreshold get() = this@PlayerSurfaceView.fineExitThreshold
        override val seekerTrackLeft get() = this@PlayerSurfaceView.seekerTrackLeft
        override val seekerTrackRight get() = this@PlayerSurfaceView.seekerTrackRight
        override val touchSlop get() = this@PlayerSurfaceView.touchSlop
        override val minimumBoundaryDurationMs get() = if (seekerOnly) 1L else AudioPlayback.MINIMUM_LOOP_DURATION_MS
        override var scrubFineReleaseActive
            get() = this@PlayerSurfaceView.scrubFineReleaseActive
            set(value) { this@PlayerSurfaceView.scrubFineReleaseActive = value }
        override var scrubFineReleaseBoundary
            get() = this@PlayerSurfaceView.scrubFineReleaseBoundary
            set(value) { this@PlayerSurfaceView.scrubFineReleaseBoundary = value }
        override fun frameNanos() = System.nanoTime()
        override fun uptimeMillis() = SystemClock.uptimeMillis()
        override fun clearPendingArtworkTap() = this@PlayerSurfaceView.clearPendingArtworkTap()
        override fun renderedPositionMs(current: PlayerSurfaceProfilePacket) = this@PlayerSurfaceView.renderedPositionMs(current)
        override fun clearFineReleaseVisual() = this@PlayerSurfaceView.clearFineReleaseVisual()
        override fun animateScrubGrowthTo(target: Float, nowMs: Long, boundary: FullPlayerLoopBoundary?) =
            this@PlayerSurfaceView.animateScrubGrowthTo(target, nowMs, boundary)
        override fun scheduleFrame() = this@PlayerSurfaceView.scheduleFrame()
        override fun animateWaveformMorphTo(target: Float) = this@PlayerSurfaceView.animateWaveformMorphTo(target)
        override fun invalidateTimelineRegion() = this@PlayerSurfaceView.invalidateTimelineRegion()
        override fun currentWaveformMorph(nowNanos: Long) = this@PlayerSurfaceView.currentWaveformMorph(nowNanos)
        override fun hardResetWaveformMorph() = this@PlayerSurfaceView.hardResetWaveformMorph()
        override fun tick() { performStemwiseHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        override fun holdLoopHandle(boundary: FullPlayerLoopBoundary?) = loopHandleVisualOwner.pointerDown(boundary)
        override fun releaseLoopHandle() = loopHandleVisualOwner.pointerUpOrCancel()
        override fun holdScrub(down: Boolean) {
            if (down) scrubHeldHighlight.pointerDown() else scrubHeldHighlight.pointerUpOrCancel()
        }
        override fun disallowParentInterception() { parent?.requestDisallowInterceptTouchEvent(true) }
    })
    private val loopHandleVisualOwner = PlayerSurfaceLoopHandleVisualOwner()
    private var scrubGrowthBoundary: FullPlayerLoopBoundary? = null
    private var scrubGrowthStartedAtMs = 0L
    private var scrubGrowthFrom = 0f
    private var scrubGrowthTo = 0f
    private var scrubGrowthDurationMs = StemwiseSeekerDesign.PLAYHEAD_GROWTH_DURATION_MS
    private var scrubFineReleaseActive = false
    private var scrubFineReleaseBoundary: FullPlayerLoopBoundary? = null
    private var waveformMorph = 0f
    private var waveformMorphStart = 0f
    private var waveformMorphTarget = 0f
    private var waveformMorphStartedAtNanos = 0L
    private var waveformMorphActive = false
    private var loopClearanceProgress = 0f
    private var loopClearanceStart = 0f
    private var loopClearanceTarget = 0f
    private var loopClearanceStartedAtNanos = 0L
    private var loopClearanceMotionActive = false
    private var visibleLoopStartMs = -1L
    private var visibleLoopEndMs = -1L

    /** One low-rate button pointer; mixer pointers remain independent. */
    private var buttonPointerId = MotionEvent.INVALID_POINTER_ID
    private var buttonKind = BUTTON_NONE
    private var buttonIndex = -1
    private var buttonDownX = 0f
    private var buttonDownY = 0f
    private var buttonDownEventTimeMs = 0L
    private var buttonPagerPreviewActive = false

    /** Immediate accepted-button acknowledgement; authoritative packets reconcile these bits. */
    private var liveSourceMode = PlayerSurfaceSourceMode.ORIGINAL
    private var livePlayWhenReady = false
    private var liveShuffleEnabled = false
    private var liveRepeatMode = PlayerSurfaceRepeatMode.OFF
    private var liveSelectedTool = PlayerSurfaceTool.NONE

    private var centerArtworkLeft = 0f
    private var centerArtworkTop = 0f
    private var centerArtworkRight = 0f
    private var centerArtworkBottom = 0f
    private var centerIdentityLeft = 0f
    private var centerIdentityTop = 0f
    private var centerIdentityRight = 0f
    private var centerIdentityBottom = 0f

    /** Three display-qualified loader slots plus one independently retained outgoing aura. */
    private val artworkDisplayKeys = arrayOfNulls<PlayerSurfaceArtworkDisplayKey>(3)
    private val artworkLoadProgress = FloatArray(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT) { 1f }
    private val artworkLoadStartedAtNanos = LongArray(3)
    private val artworkLoadTransitionActive = BooleanArray(3)
    private val artworkBitmaps = arrayOfNulls<Bitmap>(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT)
    private val artworkShaders = arrayOfNulls<BitmapShader>(3)
    private val artworkGradients = arrayOfNulls<LinearGradient>(3)
    private val artworkAuraColors = Array(PLAYER_SURFACE_ARTWORK_AURA_SLOT_COUNT) {
        intArrayOf(PRIMARY_PURPLE, ARTWORK_AURA_BLUE_ARGB, CORAL_ARGB)
    }
    private val artworkAuraCache = PlayerArtworkAuraCache(density) {
        invalidatePagerAuraRegion()
    }
    private var artworkDecodeSizePx = 0

    private val waveformGeometry = PlayerSurfaceWaveformGeometry(density, MAX_WAVEFORM_BARS, FINE_VIEWPORT_MS)
    private var lastFrameNanos = 0L
    private var lastRenderedPositionMs = Long.MIN_VALUE
    private var identityMarqueeSongId: String? = null
    private var identityMarqueeStartedAtMs = 0L
    private var identityMarqueeActive = false
    private var frameScheduled = false
    private var aggregatedVisible = false
    private var ambientMotionPolicySignal = false
    private var lastMiniPositionFrameNanos = Long.MIN_VALUE
    private val artworkAuraResumeRunnable = Runnable { scheduleFrame() }
    internal val artworkAuraMotion = PlayerArtworkAuraMotionOwner(
        requestFrame = { scheduleFrame() },
        invalidateAura = { invalidatePagerAuraRegion() },
        replaceWake = { delayMs -> removeCallbacks(artworkAuraResumeRunnable); postDelayed(artworkAuraResumeRunnable, delayMs); Unit },
        cancelWake = { removeCallbacks(artworkAuraResumeRunnable); Unit }
    )
    private var artworkPaletteListener: PlayerSurfaceArtworkPaletteListener? = null
    private var toolMotionListener: PlayerSurfaceToolMotionListener? = null
    private val toolMotion: PlayerToolMotionOwner = PlayerToolMotionOwner(object : PlayerToolMotionHost {
        override val generation get() = profile?.owner?.generation
        override val sourceMotionPending get() = this@PlayerSurfaceView.sourceMotionPending
        override val sourceMotionActive get() = this@PlayerSurfaceView.sourceMotionActive
        override val controlsValid get() = this@PlayerSurfaceView.controlsValid
        override fun targetEquals(target: PlayerSurfaceToolMotionTarget) = toolMotionTargetEquals(target)
        override fun captureRendered() = captureToolMotionStartFromRendered()
        override fun copyGeometryTarget(target: PlayerSurfaceToolMotionTarget) = copyToolMotionTarget(target)
        override fun configureRigid() = configureRigidToolControlMotionAfterTargetCopy()
        override fun applyEndpoint(target: PlayerSurfaceToolMotionTarget) = applyToolMotionEndpoint(target)
        override fun started(generation: Long, epoch: Long) {
            toolMotionListener?.onToolMotionStarted(generation, epoch, toolMotionStartControlTop, toolMotionStartControlBottom)
        }
        override fun scheduleFrame() = this@PlayerSurfaceView.scheduleFrame()
    }, System::nanoTime)
    private var toolMotionStartArtworkSizePx = 0f
    private var toolMotionStartMixerCardHeightPx = 0f
    private var toolMotionStartMixerSpacingPx = 0f
    private var toolMotionStartListSpacingPx = 0f
    private var toolMotionStartSeekerTrackHeightPx = 0f
    private var toolMotionStartTransportHeightPx = 0f
    private var toolMotionStartControlLeft = 0f
    private var toolMotionStartControlTop = 0f
    private var toolMotionStartControlRight = 0f
    private var toolMotionStartControlBottom = 0f
    private var toolMotionStartDetailHeightPx = 0f
    private var toolMotionTargetArtworkSizePx = 0f
    private var toolMotionTargetMixerCardHeightPx = 0f
    private var toolMotionTargetMixerSpacingPx = 0f
    private var toolMotionTargetListSpacingPx = 0f
    private var toolMotionTargetSeekerTrackHeightPx = 0f
    private var toolMotionTargetTransportHeightPx = 0f
    private var toolMotionTargetControlLeft = 0f
    private var toolMotionTargetControlTop = 0f
    private var toolMotionTargetControlRight = 0f
    private var toolMotionTargetControlBottom = 0f
    private var toolMotionTargetDetailHeightPx = 0f
    private var renderedToolDetailHeightPx = 0f
    private var rigidToolControlMotion = false
    private var rigidToolControlTranslationY = 0f
    private val frameCallback = Choreographer.FrameCallback(::doFrame)
    private var scheduledFrameDelayMs = 0L
    private var mixerChromeDirty = true
    private var transportChromeDirty = true
    private var mixerChromeContentKey: PlayerSurfaceMixerChromeContentKey? = null

    /**
     * The player has one state/gesture owner but several independently retained display lists.
     * A 120 Hz timeline tick must never re-record artwork, mixer chrome, or transport chrome.
     * Child canvases are translated back into the container coordinate space so all geometry
     * remains owned by this ViewGroup rather than being mirrored in Compose.
     */
    private enum class DrawRegion {
        PAGER_AURA,
        MINI_BODY,
        PAGER_ARTWORK,
        PAGER_IDENTITY,
        HEADER,
        SOURCE,
        MIXER,
        TIMELINE,
        TRANSPORT,
        MINI
    }

    private inner class RegionView(
        private val region: DrawRegion
    ) : View(context) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(-left.toFloat(), -top.toFloat())
            drawRegion(canvas, region)
            canvas.restoreToCount(save)
        }
    }

    private val pagerAuraRegion = RegionView(DrawRegion.PAGER_AURA)
    private val miniBodyRegion = RegionView(DrawRegion.MINI_BODY)
    private val pagerArtworkRegion = RegionView(DrawRegion.PAGER_ARTWORK)
    private val pagerIdentityRegion = RegionView(DrawRegion.PAGER_IDENTITY)
    private val headerRegion = RegionView(DrawRegion.HEADER)
    private val sourceRegion = RegionView(DrawRegion.SOURCE)
    private val mixerRegion = RegionView(DrawRegion.MIXER)
    private val timelineRegion = RegionView(DrawRegion.TIMELINE)
    private val transportRegion = RegionView(DrawRegion.TRANSPORT)
    private val miniRegion = RegionView(DrawRegion.MINI)
    private val nonPagerRegionViews = arrayOf(
        mixerRegion, timelineRegion, transportRegion, miniRegion, headerRegion, sourceRegion
    )
    private val regionViews = arrayOf(
        // The mini pill sits below its unscaled aura so the frame cannot cover the light field.
        miniBodyRegion,
        pagerAuraRegion,
        pagerArtworkRegion,
        pagerIdentityRegion,
        mixerRegion,
        timelineRegion,
        transportRegion,
        miniRegion,
        // The body scrolls beneath one opaque, persistent player app bar.
        headerRegion,
        sourceRegion
    )

    private fun actionTarget(action: () -> Boolean, invalidateVisual: () -> Unit) =
        PlayerSurfaceActionTargetView(context, action, invalidateVisual, ::scheduleFrame)

    private val collapseAction = actionTarget(
        ::performCollapse,
        ::invalidateHeaderRegion
    )
    private val infoAction = actionTarget(
        ::performShowInfo,
        ::invalidateHeaderRegion
    )
    private val toolActions = arrayOf(
        actionTarget({ performTool(PlayerSurfaceTool.LOOP) }, ::invalidateToolRegion),
        actionTarget({ performTool(PlayerSurfaceTool.TUNE) }, ::invalidateToolRegion),
        actionTarget({ performTool(PlayerSurfaceTool.LYRICS) }, ::invalidateToolRegion),
        actionTarget(
            { performTool(PlayerSurfaceTool.METRONOME) },
            ::invalidateToolRegion
        )
    )
    private val actionViews = arrayOf(*toolActions, collapseAction, infoAction)

    init {
        // The sibling Compose overlay is the complete accessibility surface. Hide this native
        // rendering/touch subtree as one unit so OEM AndroidView bridges cannot resurrect any of
        // its transparent physical hit motors as a second virtual tree.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        setWillNotDraw(true)
        regionViews.forEach(::addView)
        actionViews.forEach(::addView)
    }

    private fun applyProfile(
        profile: PlayerSurfaceProfilePacket,
        visuals: FullPlayerSnapshot,
        visualSelectionSongId: String = profile.owner.songId
    ) {
        require(visuals.owner.generation == profile.owner.generation)
        val previous = this.profile
        val previousMixerStateMask = liveMixerChromeStateMask(previous?.mixer?.laneCount ?: 0)
        val previousSourceMode = liveSourceMode
        val previousPlayWhenReady = livePlayWhenReady
        val previousShuffleEnabled = liveShuffleEnabled
        val previousRepeatMode = liveRepeatMode
        val previousVisualQueueIndex = visualQueueIndex
        val ownerChanged = previous?.owner?.generation != profile.owner.generation
        if (ownerChanged) {
            clearPendingArtworkTap()
        }
        val queueChanged = previous?.queue?.revision != profile.queue.revision
        val timelineChanged = previous?.timeline?.revision != profile.timeline.revision
        val mixerPackageChanged =
            previous?.mixer?.packageRevision != profile.mixer.packageRevision
        val mixerSourceChanged =
            previous?.source?.presentedMode != profile.source.presentedMode
        if (ownerChanged || mixerPackageChanged || mixerSourceChanged) {
            mixerValueLabels.forEach { it.reset(); it.sample(false, 0L, true) }
        }
        if (mixerSourceChanged) clearPendingArtworkTap()
        val layoutChanged = previous?.layout?.revision != profile.layout.revision
        val pagerContentChanged = this.visuals?.owner?.generation != visuals.owner.generation ||
            this.visuals?.catalog?.revision != visuals.catalog.revision
        val pendingResolution = pendingVisualSelection.resolve(
            ownerGeneration = profile.owner.generation,
            authoritativeSongId = profile.owner.songId,
            visualSelectionSongId = visualSelectionSongId,
            queue = profile.queue,
            songs = visuals.catalog
        )
        val preserveAcceptedPagerContinuity = preserveAcceptedPlayerSurfacePagerContinuity(
            ownerChanged = ownerChanged,
            queueChanged = queueChanged,
            layoutChanged = layoutChanged,
            logicalQueueUnchanged = previous?.queue?.hasSameItems(profile.queue) == true,
            visualSelectionSongId = visualSelectionSongId,
            visualQueueIndex = visualQueueIndex,
            visual = visuals.catalog.getOrNull(visualQueueIndex)
        )
        if (ownerChanged || layoutChanged) {
            if (ownerChanged && previous != null && toolMotion.toolMotionInitialized) {
                // Retire input ownership while carrying the rendered geometry into the next song.
                toolMotion.adoptGeneration(profile.owner.generation)
            } else if (ownerChanged) resetToolMotionController()
            if (preserveAcceptedPagerContinuity) {
                cancelNonPagerGestures(sealMixer = false)
            } else {
                cancelAllGestures(
                    sealMixer = false,
                    hardResetScrub = ownerChanged
                )
            }
        }
        else {
            if (queueChanged && !preserveAcceptedPagerContinuity) {
                cancelPagerGesture()
            }
            if (timelineChanged) reconcileTimelineGesture(previous.timeline, profile.timeline)
            if (mixerPackageChanged || mixerSourceChanged) {
                stopMixerFling()
                mixerGestures.cancelMixerGestures(seal = false)
            }
        }

        if (previous != null) prepareSourcePresentationChange(profile.source.presentedMode)
        else sourceWorkspaceProgress = if (profile.source.presentedMode == PlayerSurfaceSourceMode.STEMS) 1f else 0f
        if (ownerChanged || mixerPackageChanged) {
            mixerScrollOffsetPx = 0f
            mixerListTouches.clear()
            mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
        }
        this.profile = profile
        this.visuals = visuals
        liveSourceMode = profile.source.presentedMode
        applyTimelineVisibility(
            timeline = profile.timeline,
            snap = playerSurfaceLoopClearanceShouldSnap(
                initialBind = previous == null,
                ownerChanged = ownerChanged,
                sourceChanged = mixerSourceChanged,
                animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
            )
        )
        livePlayWhenReady = profile.transport.playWhenReady
        liveShuffleEnabled = profile.transport.shuffleEnabled
        liveRepeatMode = profile.transport.repeatMode

        val requestedIndex = profile.queue.currentIndex
        val pendingIndex = pendingResolution?.queueIndex
        val desiredIndex = pendingIndex ?: playerSurfaceVisualSelectionIndex(
            requestedIndex,
            visualSelectionSongId,
            visuals.catalog
        )
        val desiredSongId = if (pendingIndex != null) {
            visuals.catalog.getOrNull(pendingIndex)?.songId
        } else {
            visuals.catalog.getOrNull(desiredIndex)?.songId ?: profile.owner.songId
        }
        if (playerSurfacePagerRequiresReanchor(
                ownerChanged = ownerChanged,
                queueChanged = queueChanged,
                preserveAcceptedSpring = preserveAcceptedPagerContinuity,
                visualQueueIndex = visualQueueIndex,
                desiredIndex = desiredIndex,
                visualSongId = visuals.catalog.getOrNull(visualQueueIndex)?.songId,
                desiredSongId = desiredSongId
            )
        ) {
            // Programmatic selections publish immediately; only direct swipes carry pager motion.
            visualQueueIndex = desiredIndex.coerceIn(-1, visuals.catalog.lastIndex)
            pagerOffsetFraction = 0f
            pagerVelocityFraction = 0f
            pagerAnimating = false
        }
        syncMixerFromProfile(profile)
        val nextMixerContentKey = playerSurfaceMixerChromeContentKey(
            profile = profile,
            visuals = visuals,
            sourceMode = liveSourceMode
        )
        if (
            mixerChromeContentKey != nextMixerContentKey ||
            previousMixerStateMask != liveMixerChromeStateMask(profile.mixer.laneCount) ||
            previousSourceMode != liveSourceMode
        ) {
            var lane = 0
            while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
                mixerLaneIcons[lane] = visuals.sourceMixer.lanes().getOrNull(lane)?.role?.icon
                    ?: app.stemwise.ui.design.generated.StemwiseIconRole.PLAYER_STEM_OTHER
                lane++
            }
            mixerChromeContentKey = nextMixerContentKey
            mixerChromeDirty = true
        }
        if (
            ownerChanged ||
            previousPlayWhenReady != livePlayWhenReady ||
            previousShuffleEnabled != liveShuffleEnabled ||
            previousRepeatMode != liveRepeatMode ||
            playerSurfaceTransportAvailabilityChanged(previous.transport, profile.transport)
        ) transportChromeDirty = true
        val pagerPublicationChanged = playerSurfacePagerPublicationChanged(
            initialBind = previous == null,
            ownerChanged = ownerChanged,
            queueChanged = queueChanged,
            layoutChanged = layoutChanged,
            sourceChanged = mixerSourceChanged,
            catalogContentChanged = pagerContentChanged,
            visualIndexChanged = previousVisualQueueIndex != visualQueueIndex
        )
        if (pagerPublicationChanged) bindArtworkSlots()
        rebuildGeometry()
        rebuildWaveformGeometry()
        relayoutRegions()
        if (pagerPublicationChanged) invalidatePagerRegions()
        invalidateNonPagerRegions()
        scheduleFrame()
    }

    /**
     * The state store adopts or rejects a complete publication before renderer state changes.
     * Full and narrow updates share one settlement boundary; live fields hold optimistic input.
     */
    fun bind(
        snapshot: FullPlayerSnapshot,
        actionSink: FullPlayerActionSink,
        positionProvider: PlayerSurfacePositionProvider,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        direction: PlayerSurfaceLayoutDirection
    ): FullPlayerAdoptionResult {
        if (fullPlayerBindGate.isAcceptedUnchanged(
                snapshot,
                widthPx,
                heightPx,
                density,
                direction
            )
        ) {
            refreshFullPlayerReferences(actionSink, positionProvider)
            return FullPlayerAdoptionResult.UNCHANGED
        }
        val previousFullSnapshot = fullPlayerState.snapshot
        val adoption = fullPlayerState.adopt(snapshot)
        when (adoption) {
            FullPlayerAdoptionResult.APPLIED_INITIAL,
            FullPlayerAdoptionResult.APPLIED_NEW_OWNER,
            FullPlayerAdoptionResult.APPLIED_UPDATE,
            FullPlayerAdoptionResult.UNCHANGED -> Unit
            else -> return adoption
        }
        refreshFullPlayerReferences(actionSink, positionProvider)
        liveSelectedTool = snapshot.toolDetail.selectedTool
        val currentProfile = profile
        val plan = if (
            adoption == FullPlayerAdoptionResult.APPLIED_UPDATE &&
            previousFullSnapshot != null && currentProfile != null && visuals != null &&
            currentProfile.layout.widthPx == widthPx.coerceAtLeast(1) &&
            currentProfile.layout.heightPx == heightPx.coerceAtLeast(1) &&
            currentProfile.layout.density.toRawBits() == density.toRawBits() &&
            currentProfile.layout.direction == direction
        ) PlayerSurfaceSnapshotUpdatePlanner.classify(previousFullSnapshot, snapshot)
        else PlayerSurfaceSnapshotUpdatePlan.Full
        when (plan) {
            PlayerSurfaceSnapshotUpdatePlan.TransportOnly ->
                applyFullPlayerTransportOnlyUpdate(snapshot, checkNotNull(currentProfile))
            PlayerSurfaceSnapshotUpdatePlan.TimelineOnly ->
                applyFullPlayerTimelineOnlyUpdate(checkNotNull(previousFullSnapshot), snapshot,
                    checkNotNull(currentProfile))
            PlayerSurfaceSnapshotUpdatePlan.FineWindowOnly -> applyFullPlayerFineWindowOnlyUpdate(snapshot)
            PlayerSurfaceSnapshotUpdatePlan.ToolDetailOnly -> applyFullPlayerToolDetailOnlyUpdate()
            is PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly ->
                applyFullPlayerSourceMixerOnlyUpdate(snapshot, checkNotNull(currentProfile), plan.work)
            PlayerSurfaceSnapshotUpdatePlan.Full -> applyProfile(
                profile = layoutOwner.profile(snapshot, widthPx, heightPx, density, direction),
                visuals = snapshot,
                visualSelectionSongId = snapshot.visualSelectionSongId
            )
        }
        completeFullPlayerAdoption(
            previousFullSnapshot, snapshot,
            widthPx,
            heightPx,
            density,
            direction
        )
        return adoption
    }

    /** Shared settlement for full and narrow packets; redraw scope remains with each update. */
    private fun completeFullPlayerAdoption(
        previous: FullPlayerSnapshot?, snapshot: FullPlayerSnapshot,
        widthPx: Int, heightPx: Int, density: Float, direction: PlayerSurfaceLayoutDirection
    ) {
        if (previous?.transport?.repeatMode != snapshot.transport.repeatMode) bindArtworkSlots()
        updateNativeActionStates()
        fullPlayerBindGate.recordStructuralAdoption(snapshot, widthPx, heightPx, density, direction)
        scheduleFrame()
    }

    private fun applyFullPlayerTransportOnlyUpdate(
        snapshot: FullPlayerSnapshot,
        currentProfile: PlayerSurfaceProfilePacket
    ) {
        val previousPlayWhenReady = livePlayWhenReady
        val nextProfile = currentProfile.copy(
            profileRevision = snapshot.revision,
            transport = snapshot.transport
        )
        profile = nextProfile
        livePlayWhenReady = snapshot.transport.playWhenReady
        liveShuffleEnabled = snapshot.transport.shuffleEnabled
        liveRepeatMode = snapshot.transport.repeatMode
        transportChromeDirty = true
        invalidateTransportRegion()
        if (previousPlayWhenReady != livePlayWhenReady) {
            invalidateMiniBodyRegion()
            invalidateMiniRegion()
        }
    }

    private fun applyFullPlayerTimelineOnlyUpdate(
        previousSnapshot: FullPlayerSnapshot,
        snapshot: FullPlayerSnapshot,
        currentProfile: PlayerSurfaceProfilePacket
    ) {
        reconcileTimelineGesture(currentProfile.timeline, snapshot.timeline)
        profile = currentProfile.copy(
            profileRevision = snapshot.revision,
            timeline = snapshot.timeline
        )
        applyTimelineVisibility(snapshot.timeline, snap = false)
        if (previousSnapshot.toolDetail.activity != snapshot.toolDetail.activity) {
            invalidateToolRegion()
        }
        invalidateTimelineRegion()
    }

    private fun reconcileTimelineGesture(
        previous: PlayerSurfaceTimelinePacket,
        next: PlayerSurfaceTimelinePacket
    ) {
        val pointerActive = seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID
        if (!playerSurfaceCommittedLoopPreservesActiveDrag(
                pointerActive = pointerActive,
                scrubSessionStarted = seekerGestures.scrubSessionStarted,
                boundary = seekerGestures.scrubLoopBoundary,
                previewStartMs = seekerGestures.scrubPreviewLoopStartMs,
                previewEndMs = seekerGestures.scrubPreviewLoopEndMs,
                previousDurationMs = previous.durationMs,
                nextDurationMs = next.durationMs,
                nextStartMs = next.loopStartMs,
                nextEndMs = next.loopEndMs
            ) &&
            !playerSurfaceCommittedLoopPreservesFineRelease(
                releaseActive = scrubFineReleaseActive,
                pointerActive = pointerActive,
                releaseBoundary = scrubFineReleaseBoundary,
                previewStartMs = seekerGestures.scrubPreviewLoopStartMs,
                previewEndMs = seekerGestures.scrubPreviewLoopEndMs,
                previousDurationMs = previous.durationMs,
                nextDurationMs = next.durationMs,
                nextStartMs = next.loopStartMs,
                nextEndMs = next.loopEndMs
            )
        ) seekerGestures.cancelScrubGesture(commit = false, hardResetMorph = true)
    }

    private fun applyTimelineVisibility(
        timeline: PlayerSurfaceTimelinePacket,
        snap: Boolean
    ) {
        if (timeline.loopStartMs >= 0L) {
            visibleLoopStartMs = timeline.loopStartMs
            visibleLoopEndMs = timeline.loopEndMs
        }
        retargetLoopClearance(present = timeline.loopStartMs >= 0L, snap = snap)
    }

    private fun applyFullPlayerFineWindowOnlyUpdate(
        snapshot: FullPlayerSnapshot
    ) {
        visuals = snapshot
        invalidateTimelineRegion()
    }

    private fun applyFullPlayerToolDetailOnlyUpdate() {
        invalidateToolRegion()
    }

    private fun applyFullPlayerSourceMixerOnlyUpdate(
        snapshot: FullPlayerSnapshot,
        currentProfile: PlayerSurfaceProfilePacket,
        work: PlayerSurfaceSourceMixerUpdateWork
    ) {
        val previousMixerStateMask = liveMixerChromeStateMask(currentProfile.mixer.laneCount)
        val packageChanged = currentProfile.mixer.packageRevision !=
            snapshot.sourceMixer.mixer.packageRevision
        val sourceChanged = currentProfile.source.presentedMode !=
            snapshot.sourceMixer.source.presentedMode
        if (sourceChanged) prepareSourcePresentationChange(snapshot.sourceMixer.source.presentedMode)
        if (packageChanged || sourceChanged) mixerGestures.cancelMixerGestures(seal = false)
        val nextProfile = currentProfile.copy(
            profileRevision = snapshot.revision,
            source = snapshot.sourceMixer.source,
            mixer = snapshot.sourceMixer.mixer
        )
        profile = nextProfile
        liveSourceMode = snapshot.sourceMixer.source.presentedMode

        when (work) {
            PlayerSurfaceSourceMixerUpdateWork.PHASE_ONLY -> invalidateSourceRegion()

            PlayerSurfaceSourceMixerUpdateWork.MIXER_STATE_ONLY -> {
                syncMixerFromProfile(nextProfile)
                if (
                    previousMixerStateMask !=
                    liveMixerChromeStateMask(nextProfile.mixer.laneCount)
                ) mixerChromeDirty = true
                invalidateMixerRegion()
            }

            PlayerSurfaceSourceMixerUpdateWork.PRESENTATION_OR_TOPOLOGY -> {
                val nextVisuals = snapshot
                visuals = nextVisuals
                syncMixerFromProfile(nextProfile)
                var lane = 0
                while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
                    mixerLaneIcons[lane] = nextVisuals.sourceMixer.lanes().getOrNull(lane)?.role?.icon
                        ?: app.stemwise.ui.design.generated.StemwiseIconRole.PLAYER_STEM_OTHER
                    lane++
                }
                mixerChromeContentKey = playerSurfaceMixerChromeContentKey(
                    profile = nextProfile,
                    visuals = nextVisuals,
                    sourceMode = liveSourceMode
                )
                mixerChromeDirty = true
                transportChromeDirty = true
                rebuildControlGeometry()
                relayoutControlRegions()
                invalidateSourceRegion()
                invalidateMixerRegion()
                invalidateTimelineRegion()
                invalidateTransportRegion()
                invalidateToolRegion()
            }
        }
    }

    /** Route-only frames replace live delegates without touching structural publication state. */
    private fun refreshFullPlayerReferences(
        actionSink: FullPlayerActionSink,
        positionProvider: PlayerSurfacePositionProvider
    ) {
        this.positionProvider = positionProvider
        sink = actionSink
    }

    fun updateAmbientMotionPolicy(allowed: Boolean) {
        if (ambientMotionPolicySignal == allowed) return
        ambientMotionPolicySignal = allowed
        scheduleFrame()
    }

    fun updateRouteBaseMetrics(
        expansionProgress: Float,
        artworkAlpha: Float,
        expandedHeaderHeightPx: Float,
        expandedHeaderGapPx: Float,
        expandedIdentityHeightPx: Float,
        expandedIdentityGapPx: Float,
        compactChrome: Boolean,
        listSpacingPx: Float,
        fontScale: Float,
        seekerClockTopClearancePx: Float,
        seekerTrackHeightPx: Float,
        transportHeightPx: Float,
        transportPlaySizePx: Float
    ) {
        val nextExpansionProgress = expansionProgress.coerceIn(0f, 1f)
        val nextArtworkAlpha = if (sourceMotionPending || sourceMotionActive) {
            PlayerMotionPolicy.artworkAlpha(nextExpansionProgress, sourceWorkspaceProgress)
        } else artworkAlpha.coerceIn(0f, 1f)
        val nextExpandedHeaderHeightPx = expandedHeaderHeightPx.coerceAtLeast(0f)
        val nextExpandedHeaderGapPx = expandedHeaderGapPx.coerceAtLeast(0f)
        val nextExpandedIdentityHeightPx = expandedIdentityHeightPx.coerceAtLeast(0f)
        val nextExpandedIdentityGapPx = expandedIdentityGapPx.coerceAtLeast(0f)
        // Once laid out, the tool clock owns compact control metrics. Route recomposition must
        // not restore closed-panel spacing over the moving compact target.
        val nextListSpacingPx = if (toolMotion.toolMotionInitialized) this.listSpacingPx else listSpacingPx.coerceAtLeast(0f)
        val nextFontScale = normalizedPlayerFontScale(fontScale)
        val nextSeekerClockTopClearancePx = seekerClockTopClearancePx.coerceAtLeast(0f)
        val nextSeekerTrackHeightPx = if (toolMotion.toolMotionInitialized) this.seekerTrackHeightPx else seekerTrackHeightPx.coerceAtLeast(0f)
        val nextTransportHeightPx = if (toolMotion.toolMotionInitialized) this.transportHeightPx else transportHeightPx.coerceAtLeast(0f)
        val nextTransportPlaySizePx = transportPlaySizePx.coerceAtLeast(0f)
        val routeChanged = this.expansionProgress != nextExpansionProgress ||
            this.artworkAlpha != nextArtworkAlpha
        val controlMetricsUnchanged =
            this.compactChrome == compactChrome &&
                this.listSpacingPx == nextListSpacingPx &&
                this.fontScale == nextFontScale &&
                this.seekerClockTopClearancePx == nextSeekerClockTopClearancePx &&
                this.seekerTrackHeightPx == nextSeekerTrackHeightPx &&
                this.transportHeightPx == nextTransportHeightPx &&
                this.transportPlaySizePx == nextTransportPlaySizePx
        val endpointMetricsUnchanged =
            this.expandedHeaderHeightPx == nextExpandedHeaderHeightPx &&
            this.expandedHeaderGapPx == nextExpandedHeaderGapPx &&
            this.expandedIdentityHeightPx == nextExpandedIdentityHeightPx &&
            this.expandedIdentityGapPx == nextExpandedIdentityGapPx &&
                controlMetricsUnchanged
        if (!routeChanged && endpointMetricsUnchanged) {
            return
        }
        this.expansionProgress = nextExpansionProgress
        this.artworkAlpha = nextArtworkAlpha
        this.expandedHeaderHeightPx = nextExpandedHeaderHeightPx
        this.expandedHeaderGapPx = nextExpandedHeaderGapPx
        this.expandedIdentityHeightPx = nextExpandedIdentityHeightPx
        this.expandedIdentityGapPx = nextExpandedIdentityGapPx
        this.compactChrome = compactChrome
        this.listSpacingPx = nextListSpacingPx
        if (this.fontScale != nextFontScale) identityLayoutCache.clear()
        this.fontScale = nextFontScale
        this.seekerClockTopClearancePx = nextSeekerClockTopClearancePx
        this.seekerTrackHeightPx = nextSeekerTrackHeightPx
        this.transportHeightPx = nextTransportHeightPx
        this.transportPlaySizePx = nextTransportPlaySizePx
        if (endpointMetricsUnchanged) {
            // A pure route frame changes only pager/header geometry and alpha. In particular it
            // must not rebuild the fine-waveform sampling grid or restart artwork requests.
            rebuildRouteGeometry()

            // Control bounds arrive synchronously from the live coordinate bridge when their
            // Compose envelope genuinely moves. Relaying out every RegionView here as well did
            // duplicate the same 120 Hz work and could submit N/N-1 display-list geometry.
            updateNativeActionStates()
            invalidateEveryRegionForStructuralChange()
            return
        } else if (!routeChanged && controlMetricsUnchanged) {
            // Only stable header/identity endpoint metrics changed. Tool-height artwork/mixer
            // fields are exclusively owned by applyToolMotionFrame and cannot be overwritten here.
            rebuildRouteGeometry()

            invalidatePagerRegions()
            return
        } else {
            mixerChromeDirty = true
            transportChromeDirty = true
            rebuildGeometry()
            rebuildWaveformGeometry()
        }
        relayoutRegions()

        updateNativeActionStates()
        invalidateEveryRegionForStructuralChange()
    }

    /**
     * High-rate outer-scroll publication. This path changes only four retained header children;
     * it never enters the full-player snapshot bind gate or rebuilds body geometry.
     */


    internal fun updateRouteExpansionProgress(progress: Float) = updateRouteBaseMetrics(
        expansionProgress = progress,
        artworkAlpha = PlayerMotionPolicy.artworkAlpha(progress, sourceWorkspaceProgress),
        expandedHeaderHeightPx = expandedHeaderHeightPx,
        expandedHeaderGapPx = expandedHeaderGapPx,
        expandedIdentityHeightPx = expandedIdentityHeightPx,
        expandedIdentityGapPx = expandedIdentityGapPx,
        compactChrome = compactChrome,
        listSpacingPx = listSpacingPx,
        fontScale = fontScale,
        seekerClockTopClearancePx = seekerClockTopClearancePx,
        seekerTrackHeightPx = seekerTrackHeightPx,
        transportHeightPx = transportHeightPx,
        transportPlaySizePx = transportPlaySizePx
    )

    fun setControlBounds(left: Float, top: Float, right: Float, bottom: Float) {
        val valid = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top
        if (!valid) {
            controlsValid = false
            updateNativeActionStates()
            return
        }
        if (
            controlLeft == left && controlTop == top && controlRight == right &&
            controlBottom == bottom && controlsValid
        ) return
        val horizontalBoundsChanged = !controlsValid || controlLeft != left || controlRight != right
        val artworkAnchorChanged = !controlsValid || controlTop != top
        // The bounds move on every mini/full route-animation frame. Pointer coordinates are local
        // to this View, so reprojecting them through the newest geometry is safe; cancelling here
        // made an otherwise valid pager, scrub, or mixer gesture disappear mid-transition.
        controlLeft = left
        controlTop = top
        controlRight = right
        controlBottom = bottom
        controlsValid = true
        // Artwork balances against the live control anchor; both retained paint layers must move.
        if (artworkAnchorChanged) {
            rebuildRouteGeometry()
            invalidatePagerRegions()
        }
        rebuildControlGeometry()
        if (horizontalBoundsChanged) rebuildWaveformGeometry()
        relayoutControlRegions()
        updateNativeActionStates()
        invalidateMixerRegion()
        invalidateTimelineRegion()
        invalidateTransportRegion()
        invalidateToolRegion()
    }

    fun setToolMotionListener(listener: PlayerSurfaceToolMotionListener?) {
        toolMotionListener = listener
        if (listener != null && toolMotion.toolMotionInitialized) {
            listener.onToolMotionStarted(
                generation = toolMotion.toolMotionGeneration,
                selectionEpoch = toolMotion.toolMotionEpoch,
                currentControlTopPx = controlTop,
                currentControlBottomPx = controlBottom
            )
            listener.onToolMotionFrame(
                generation = toolMotion.toolMotionGeneration,
                selectionEpoch = toolMotion.toolMotionEpoch,
                panelContentEasedFraction = toolMotion.lastToolMotionPanelContentEasedFraction,
                currentControlTopPx = controlTop,
                currentControlBottomPx = controlBottom
            )
            if (!toolMotion.toolMotionActive) {
                publishToolMotionSettled(toolMotion.toolMotionGeneration, toolMotion.toolMotionEpoch)
            }
        }
    }

    fun setArtworkPaletteListener(listener: PlayerSurfaceArtworkPaletteListener?) {
        artworkPaletteListener = listener
        if (listener != null) publishCurrentArtworkPalette()
    }

    private fun publishToolMotionSettled(generation: Long, selectionEpoch: Long) {
        toolMotionListener?.onToolMotionSettled(generation, selectionEpoch)
    }

    /**
     * Retargets the one native portrait tool clock from the pixels currently on screen. A newer
     * selection always receives a fresh motion epoch, including equal-geometry tool switches.
     */
    fun retargetToolMotion(target: PlayerSurfaceToolMotionTarget): Boolean = toolMotion.retarget(target)

    private fun toolMotionTargetEquals(target: PlayerSurfaceToolMotionTarget): Boolean {
        val bounds = target.controlBounds
        return toolMotion.toolMotionSelectedTool == target.selectedTool &&
            toolMotion.toolMotionPanelTransition == target.panelTransition &&
            toolMotion.toolMotionPanelContentDurationNanos ==
                target.panelContentDurationMs.toLong() * 1_000_000L &&
            toolMotionTargetArtworkSizePx.toRawBits() ==
                target.expandedArtworkSizePx.toRawBits() &&
            toolMotionTargetMixerCardHeightPx.toRawBits() ==
                target.mixerCardHeightPx.toRawBits() &&
            toolMotionTargetMixerSpacingPx.toRawBits() == target.mixerSpacingPx.toRawBits() &&
            toolMotionTargetListSpacingPx == target.listSpacingPx &&
            toolMotionTargetSeekerTrackHeightPx == target.seekerTrackHeightPx &&
            toolMotionTargetTransportHeightPx == target.transportHeightPx &&
            toolMotionTargetControlLeft.toRawBits() == bounds.left.toRawBits() &&
            toolMotionTargetControlTop.toRawBits() == bounds.top.toRawBits() &&
            toolMotionTargetControlRight.toRawBits() == bounds.right.toRawBits() &&
            toolMotionTargetControlBottom.toRawBits() == bounds.bottom.toRawBits() &&
            toolMotionTargetDetailHeightPx.toRawBits() ==
                target.detailViewportHeightPx.toRawBits()
    }

    private fun captureToolMotionStartFromRendered() {
        toolMotionStartArtworkSizePx = expandedArtworkSizePx
        toolMotionStartMixerCardHeightPx = mixerCardHeightPx
        toolMotionStartMixerSpacingPx = mixerSpacingPx
        toolMotionStartListSpacingPx = listSpacingPx
        toolMotionStartSeekerTrackHeightPx = seekerTrackHeightPx
        toolMotionStartTransportHeightPx = transportHeightPx
        toolMotionStartControlLeft = controlLeft
        toolMotionStartControlTop = controlTop + rigidToolControlTranslationY
        toolMotionStartControlRight = controlRight
        toolMotionStartControlBottom = controlBottom + rigidToolControlTranslationY
        toolMotionStartDetailHeightPx = renderedToolDetailHeightPx
    }

    private fun prepareSourcePresentationChange(next: PlayerSurfaceSourceMode) {
        if (seekerOnly) return
        val current = profile ?: return
        if (liveSourceMode == next || !controlsValid) return
        materializeRigidToolControlTransformForInput()
        sourceMotionPending = true
        sourceMotionSeekerTop = seekerTop
        outgoingMixerPicture = if (liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
            mixerGridBottom > mixerHeaderTop && width > 0 && height > 0
        ) {
            outgoingMixerBounds.set(controlLeft, mixerHeaderTop, controlRight, mixerGridBottom)
            outgoingMixerSeekerTop = seekerTop
            Picture().apply {
                val recording = beginRecording(this@PlayerSurfaceView.width, this@PlayerSurfaceView.height)
                mixerDrawing.drawMixer(recording, 1f, current.mixer.laneCount, current.layout.direction == PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT)
                endRecording()
            }
        } else null
    }

    private fun copyToolMotionTarget(target: PlayerSurfaceToolMotionTarget) {
        val bounds = target.controlBounds
        if (sourceMotionPending || sourceMotionActive) {
            sourceMotionPending = false
            sourceMotionActive = true
            sourceMotionStartProgress = sourceWorkspaceProgress
            sourceMotionTargetProgress = if (liveSourceMode == PlayerSurfaceSourceMode.STEMS) 1f else 0f
            sourceMotionStartSeekerTop = sourceMotionSeekerTop.takeIf(Float::isFinite) ?: seekerTop
            sourceMotionTargetSeekerTop = bounds.top +
                if (target.expandedArtworkSizePx <= .01f) 0f else seekerClockTopClearancePx
        }
        toolMotionTargetArtworkSizePx = target.expandedArtworkSizePx
        toolMotionTargetMixerCardHeightPx = target.mixerCardHeightPx
        toolMotionTargetMixerSpacingPx = target.mixerSpacingPx
        toolMotionTargetListSpacingPx = target.listSpacingPx
        toolMotionTargetSeekerTrackHeightPx = target.seekerTrackHeightPx
        toolMotionTargetTransportHeightPx = target.transportHeightPx
        toolMotionTargetControlLeft = bounds.left
        toolMotionTargetControlTop = bounds.top
        toolMotionTargetControlRight = bounds.right
        toolMotionTargetControlBottom = bounds.bottom
        toolMotionTargetDetailHeightPx = target.detailViewportHeightPx
    }

    /**
     * Selects the retained-layer path only when the complete control stack is a rigid vertical
     * translation. If a source/layout change invalidates that invariant mid-flight, materialize the
     * exact currently rendered geometry once before returning to the general path.
     */
    private fun configureRigidToolControlMotionAfterTargetCopy() {
        val wasRigid = rigidToolControlMotion
        val nextRigid = toolMotionStartListSpacingPx == toolMotionTargetListSpacingPx &&
            toolMotionStartSeekerTrackHeightPx == toolMotionTargetSeekerTrackHeightPx &&
            toolMotionStartTransportHeightPx == toolMotionTargetTransportHeightPx &&
            playerSurfaceRigidOriginalToolMotionEligible(
            sourceMode = liveSourceMode,
            controlsValid = controlsValid,
            controlGestureActive = seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID ||
                buttonPointerId != MotionEvent.INVALID_POINTER_ID ||
                mixerGestures.mixerPointerIds.any { it != MotionEvent.INVALID_POINTER_ID },
            startLeft = toolMotionStartControlLeft,
            startTop = toolMotionStartControlTop,
            startRight = toolMotionStartControlRight,
            startBottom = toolMotionStartControlBottom,
            targetLeft = toolMotionTargetControlLeft,
            targetTop = toolMotionTargetControlTop,
            targetRight = toolMotionTargetControlRight,
            targetBottom = toolMotionTargetControlBottom,
            startMixerCardHeightPx = toolMotionStartMixerCardHeightPx,
            targetMixerCardHeightPx = toolMotionTargetMixerCardHeightPx,
            startMixerSpacingPx = toolMotionStartMixerSpacingPx,
            targetMixerSpacingPx = toolMotionTargetMixerSpacingPx
        )
        if (wasRigid && !nextRigid) {
            clearRigidToolControlTranslation()
            applyToolMotionGeometryFrame(
                generation = toolMotion.toolMotionGeneration,
                expandedArtworkSizePx = expandedArtworkSizePx,
                mixerCardHeightPx = mixerCardHeightPx,
                mixerSpacingPx = mixerSpacingPx,
                left = toolMotionStartControlLeft,
                top = toolMotionStartControlTop,
                right = toolMotionStartControlRight,
                bottom = toolMotionStartControlBottom
            )
        }
        rigidToolControlMotion = nextRigid
    }

    private fun applyRigidToolControlTranslation(translationY: Float) {
        val resolved = translationY.takeIf(Float::isFinite) ?: 0f
        if (rigidToolControlTranslationY.toRawBits() == resolved.toRawBits()) return
        rigidToolControlTranslationY = resolved
        rebuildRouteGeometry()
        invalidatePagerRegions()
        timelineRegion.translationY = resolved
        transportRegion.translationY = resolved
        toolActions.forEach { action -> action.translationY = resolved }
    }

    private fun clearRigidToolControlTranslation() {
        applyRigidToolControlTranslation(0f)
    }

    /**
     * A retained Original-tool translation and ordinary control geometry are two different Y
     * spaces. Once a control pointer is admitted, commit the exact rendered bounds before storing
     * its DOWN sample so a later tool-motion endpoint cannot appear as fake vertical finger travel.
     */
    private fun materializeRigidToolControlTransformForInput(): Boolean {
        if (!rigidToolControlMotion || !controlsValid) return false
        val renderedTop = playerSurfaceMaterializedRigidControlCoordinate(
            controlTop,
            rigidToolControlTranslationY
        )
        val renderedBottom = playerSurfaceMaterializedRigidControlCoordinate(
            controlBottom,
            rigidToolControlTranslationY
        )
        clearRigidToolControlTranslation()
        rigidToolControlMotion = false
        applyToolMotionGeometryFrame(
            generation = toolMotion.toolMotionGeneration,
            expandedArtworkSizePx = expandedArtworkSizePx,
            mixerCardHeightPx = mixerCardHeightPx,
            mixerSpacingPx = mixerSpacingPx,
            left = controlLeft,
            top = renderedTop,
            right = controlRight,
            bottom = renderedBottom
        )
        return true
    }

    private fun applyToolMotionArtworkFrame(generation: Long, artworkSizePx: Float) {
        if (profile?.owner?.generation != generation) return
        val nextArtworkSizePx = artworkSizePx.coerceAtLeast(0f)
        if (expandedArtworkSizePx == nextArtworkSizePx) return
        expandedArtworkSizePx = nextArtworkSizePx
        rebuildRouteGeometry()
        updateCenterBounds()
        invalidatePagerRegions()
    }

    private fun applyToolMotionEndpoint(target: PlayerSurfaceToolMotionTarget) {
        sourceMotionPending = false
        sourceMotionActive = false
        sourceMotionSeekerTop = Float.NaN
        outgoingMixerPicture = null
        sourceWorkspaceProgress = if (liveSourceMode == PlayerSurfaceSourceMode.STEMS) 1f else 0f
        artworkAlpha = PlayerMotionPolicy.artworkAlpha(expansionProgress, sourceWorkspaceProgress)
        applyToolMotionArtworkFrame(target.generation, target.expandedArtworkSizePx)
        clearRigidToolControlTranslation()
        rigidToolControlMotion = false
        applyToolMotionGeometryFrame(
            generation = target.generation,
            expandedArtworkSizePx = target.expandedArtworkSizePx,
            mixerCardHeightPx = target.mixerCardHeightPx,
            mixerSpacingPx = target.mixerSpacingPx,
            listSpacingPx = target.listSpacingPx,
            seekerTrackHeightPx = target.seekerTrackHeightPx,
            transportHeightPx = target.transportHeightPx,
            left = target.controlBounds.left,
            top = target.controlBounds.top,
            right = target.controlBounds.right,
            bottom = target.controlBounds.bottom
        )
        renderedToolDetailHeightPx = target.detailViewportHeightPx
        toolMotionListener?.onToolMotionFrame(
            generation = target.generation,
            selectionEpoch = target.selectionEpoch,
            panelContentEasedFraction = 1f,
            currentControlTopPx = controlTop,
            currentControlBottomPx = controlBottom
        )
        publishToolMotionSettled(target.generation, target.selectionEpoch)
    }

    /**
     * Applies the visible tool-height frame atomically after Compose layout. Reserved detail height
     * changes artwork, mixer rows and control spacing together. Vertical-only motion leaves waveform
     * sampling and artwork loading untouched.
     */
    private fun applyToolMotionGeometryFrame(
        generation: Long,
        expandedArtworkSizePx: Float,
        mixerCardHeightPx: Float,
        mixerSpacingPx: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        listSpacingPx: Float = this.listSpacingPx,
        seekerTrackHeightPx: Float = this.seekerTrackHeightPx,
        transportHeightPx: Float = this.transportHeightPx
    ) {
        if (profile?.owner?.generation != generation) return
        val validBounds = left.isFinite() && top.isFinite() && right.isFinite() &&
            bottom.isFinite() && right > left && bottom > top
        if (!validBounds) {
            controlsValid = false
            updateNativeActionStates()
            return
        }

        val nextArtworkSizePx = expandedArtworkSizePx.coerceAtLeast(0f)
        val nextMixerCardHeightPx = mixerCardHeightPx.coerceAtLeast(0f)
        val nextMixerSpacingPx = mixerSpacingPx.coerceAtLeast(0f)
        val artworkChanged = this.expandedArtworkSizePx != nextArtworkSizePx
        val artworkAnchorChanged = !controlsValid || controlTop != top
        val mixerMetricsChanged = this.mixerCardHeightPx != nextMixerCardHeightPx ||
            this.mixerSpacingPx != nextMixerSpacingPx
        val boundsChanged = !controlsValid || controlLeft != left || controlTop != top ||
            controlRight != right || controlBottom != bottom
        val controlMetricsChanged = this.listSpacingPx != listSpacingPx ||
            this.seekerTrackHeightPx != seekerTrackHeightPx || this.transportHeightPx != transportHeightPx
        if (!artworkChanged && !mixerMetricsChanged && !boundsChanged && !controlMetricsChanged) {
            return
        }

        val horizontalBoundsChanged = !controlsValid || controlLeft != left || controlRight != right
        this.expandedArtworkSizePx = nextArtworkSizePx
        this.mixerCardHeightPx = nextMixerCardHeightPx
        this.mixerSpacingPx = nextMixerSpacingPx
        this.listSpacingPx = listSpacingPx
        this.seekerTrackHeightPx = seekerTrackHeightPx
        this.transportHeightPx = transportHeightPx
        controlLeft = left
        controlTop = top
        controlRight = right
        controlBottom = bottom
        controlsValid = true

        if (artworkChanged || artworkAnchorChanged) rebuildRouteGeometry()
        if (mixerMetricsChanged || boundsChanged || controlMetricsChanged) {
            if (mixerMetricsChanged) mixerChromeDirty = true
            rebuildControlGeometry()
            if (horizontalBoundsChanged) rebuildWaveformGeometry()
            relayoutControlRegions()
        }
        updateNativeActionStates()
        if (artworkChanged || artworkAnchorChanged) invalidatePagerRegions()
        if (mixerMetricsChanged || boundsChanged || controlMetricsChanged) {
            invalidateMixerRegion()
            invalidateTimelineRegion()
            invalidateTransportRegion()
            invalidateToolRegion()
        }
    }

    /** Makes a paused seek performed outside this View visible on the next display frame. */
    fun onExternalTimelinePositionChanged() {
        lastRenderedPositionMs = Long.MIN_VALUE
        invalidateTimelineRegion()
        scheduleFrame()
    }

    private fun setArtworkBitmap(
        slot: Int,
        displayKey: PlayerSurfaceArtworkDisplayKey,
        bitmap: Bitmap?
    ) {
        if (slot !in 0..2 || artworkDisplayKeys[slot] != displayKey) return
        // The callback can arrive in the middle of a tool morph. Record the replacement at exact
        // live geometry instead of recording at the old base and applying a stale scale twice.
        val animateArrival = bitmap != null && artworkBitmaps[slot] !== bitmap &&
            ValueAnimator.areAnimatorsEnabled()
        artworkBitmaps[slot] = bitmap
        artworkShaders[slot] = bitmap?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val colors = bitmap?.let { deriveNativeArtworkAuraColors(it, artworkDisplayKeys[slot]) }
            ?: intArrayOf(PRIMARY_PURPLE, ARTWORK_AURA_BLUE_ARGB, CORAL_ARGB)
        artworkAuraColors[slot] = colors
        artworkAuraPaletteRevision[slot]++
        if (bitmap != null) {
            artworkAuraCache.prepare(slot, displayKey, artworkAuraPaletteRevision[slot], colors)
        } else artworkAuraCache.clearSlot(slot)
        artworkLoadProgress[slot] = if (animateArrival) 0f else 1f
        artworkLoadStartedAtNanos[slot] = if (animateArrival) System.nanoTime() else 0L
        artworkLoadTransitionActive[slot] = animateArrival
        if (slot == PlayerSurfaceArtworkLoader.SLOT_CURRENT) publishCurrentArtworkPalette()
        invalidatePagerAuraRegion()
        invalidatePagerArtworkRegion()
        if (animateArrival) scheduleFrame()
    }

    private fun publishCurrentArtworkPalette() {
        val owner = profile?.owner ?: return
        val slot = PlayerSurfaceArtworkLoader.SLOT_CURRENT
        val key = artworkDisplayKeys.getOrNull(slot) ?: return
        if (
            key.songId != owner.songId ||
            key.mediaRevision != owner.mediaRevision.trim() ||
            artworkBitmaps.getOrNull(slot)?.isRecycled != false
        ) return
        val colors = artworkAuraColors[slot]
        artworkPaletteListener?.onCurrentArtworkPalette(
            ownerGeneration = owner.generation,
            songId = key.songId,
            mediaRevision = key.mediaRevision,
            physicalSource = key.physicalSource,
            primaryArgb = colors[0],
            secondaryArgb = colors[1],
            tertiaryArgb = colors[2],
            dominantArgb = colors.getOrElse(3) { colors[0] }
        )
    }

    private fun deriveNativeArtworkAuraColors(bitmap: Bitmap, key: PlayerSurfaceArtworkDisplayKey?): IntArray {
        val identity = key?.let { artworkDiskCacheIdentity(it.physicalSource, it.mediaRevision, it.songId) }
        val palette = if (identity != null) ArtworkAuraPaletteCache.deriveAndPut(identity.canonicalKey, bitmap)
            else deriveArtworkAuraPalette(bitmap)
        return palette.let {
            intArrayOf(
                palette.primary.toArgb(),
                palette.secondary.toArgb(),
                palette.tertiary.toArgb(),
                palette.dominant.toArgb()
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mixerChromeDirty = true
        transportChromeDirty = true
        rebuildGeometry()
        rebuildWaveformGeometry()
        invalidateEveryRegionForStructuralChange()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        regionViews.forEach { child -> child.measure(childWidthSpec, childHeightSpec) }
        actionViews.forEach { child -> child.measure(childWidthSpec, childHeightSpec) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (changed) rebuildGeometry()
        layoutRegionChildren()
    }

    /** Geometry is native-owned; update child display-list bounds without restarting Compose. */
    private fun relayoutRegions() {
        if (width > 0 && height > 0 && isLaidOut) {
            layoutRegionChildren()
        } else {
            requestLayout()
        }
    }

    /** Source/mixer topology moves only the control stack; pager/header/mini bounds stay fixed. */
    private fun relayoutControlRegions() {
        if (width > 0 && height > 0 && isLaidOut) {
            layoutControlRegionChildren(ceil(NATIVE_TRANSIENT_VISUAL_OVERFLOW_DP * density).toInt())
        } else {
            requestLayout()
        }
    }

    private fun layoutRegionChildren() {
        if (seekerOnly) {
            regionViews.forEach { it.layout(0, 0, 0, 0) }
            actionViews.forEach { it.layout(0, 0, 0, 0) }
            timelineRegion.layout(0, 0, width, height)
            return
        }
        val padding = ceil(NATIVE_TRANSIENT_VISUAL_OVERFLOW_DP * density).toInt()
        // Artwork is the one continuous element across compact and full layouts. Keep its child
        // RenderNode at one stable size and clip to the exact live pager geometry inside drawPager.
        // Relaying out this child on the final route frame could expose the parent background for
        // one submitted frame, which appeared as an artwork flash after the morph had settled.
        pagerAuraRegion.layout(0, 0, width, height)
        layoutRegion(
            miniBodyRegion,
            PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP * density,
            PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP * density,
            width - PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP * density,
            (PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP -
                PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP) * density,
            0
        )
        pagerArtworkRegion.layout(0, 0, width, height)
        pagerIdentityRegion.layout(0, 0, width, height)
        layoutRegion(headerRegion, 0f, 0f, width.toFloat(), PLAYER_SURFACE_HEADER_HEIGHT_DP * density, padding)
        layoutRegion(
            sourceRegion,
            min(sourceOriginalLeft, sourceStemsLeft),
            min(sourceOriginalTop, sourceStemsTop),
            max(sourceOriginalRight, sourceStemsRight),
            max(sourceOriginalBottom, sourceStemsBottom),
            padding
        )
        layoutControlRegionChildren(padding)
        layoutRegion(
            miniRegion,
            routeGeometry.miniPlayLeft,
            routeGeometry.miniPlayTop,
            routeGeometry.miniPlayRight,
            routeGeometry.miniPlayBottom,
            padding
        )
        layoutRegion(
            collapseAction,
            routeGeometry.collapseHitBounds.left,
            routeGeometry.collapseHitBounds.top,
            routeGeometry.collapseHitBounds.right,
            routeGeometry.collapseHitBounds.bottom,
            0
        )
        layoutRegion(
            infoAction,
            routeGeometry.infoHitBounds.left,
            routeGeometry.infoHitBounds.top,
            routeGeometry.infoHitBounds.right,
            routeGeometry.infoHitBounds.bottom,
            0
        )

    }

    private fun layoutControlRegionChildren(padding: Int) {
        if (seekerOnly) {
            timelineRegion.layout(0, 0, width, height)
            return
        }
        val current = profile
        val mixerVisible = controlsValid &&
            liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
            (current?.mixer?.laneCount ?: 0) > 0 &&
            mixerCardHeightPx > .01f
        if (mixerVisible) {
            layoutRegion(
                mixerRegion,
                controlLeft,
                mixerHeaderTop,
                controlRight,
                mixerGridBottom,
                max(padding, ceil(mixerValueLabelHeightPx() +
                    (MIXER_SLIDER_THUMB_RADIUS_DP +
                        app.stemwise.ui.design.StemwiseSliderValueLabelMetrics.THUMB_GAP_DP + 1f) * density).toInt())
            )
        } else if (outgoingMixerPicture != null) {
            val offset = seekerTop - outgoingMixerSeekerTop
            layoutRegion(mixerRegion, outgoingMixerBounds.left, outgoingMixerBounds.top + offset,
                outgoingMixerBounds.right, outgoingMixerBounds.bottom + offset, padding)
        } else {
            mixerRegion.layout(0, 0, 0, 0)
        }
        if (controlsValid) {
            val maximumLoopVisualBottom = loopEndpointHandleTop(
                seekerRailY(),
                fine = 1f
            ) + StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP * density +
                StemwiseAuraContract.LOOP_HANDLE_FIELD_PADDING_DP * density
            layoutRegion(
                timelineRegion,
                controlLeft,
                seekerTop,
                controlRight,
                max(seekerBottom + 20f * density, maximumLoopVisualBottom),
                padding
            )
            layoutRegion(
                transportRegion,
                controlLeft,
                transportTop,
                controlRight,
                // Transport and tools are one lower-chrome visual owner. Keeping both in this
                // retained child prevents the tool row from being withdrawn when the route swaps
                // its transient expansion display lists for the settled full-player endpoint.
                max(transportBottom, toolBottom),
                padding
            )
        } else {
            timelineRegion.layout(0, 0, 0, 0)
            transportRegion.layout(0, 0, 0, 0)
        }
        var toolIndex = 0
        while (toolIndex < toolActions.size) {
            val base = toolIndex * 4
            layoutRegion(
                toolActions[toolIndex],
                controlGeometry.tools.hit[base],
                controlGeometry.tools.hit[base + 1],
                controlGeometry.tools.hit[base + 2],
                controlGeometry.tools.hit[base + 3],
                0
            )
            toolIndex++
        }
    }

    private fun layoutRegion(
        view: View,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        padding: Int
    ) {
        if (right <= left || bottom <= top || width <= 0 || height <= 0) {
            view.layout(0, 0, 0, 0)
            return
        }
        val childLeft = (floor(left).toInt() - padding).coerceIn(0, width)
        val childTop = (floor(top).toInt() - padding).coerceIn(0, height)
        val childRight = (ceil(right).toInt() + padding).coerceIn(childLeft, width)
        val childBottom = (ceil(bottom).toInt() + padding).coerceIn(childTop, height)
        view.layout(childLeft, childTop, childRight, childBottom)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        artworkLoader.attach()
        bindArtworkSlots()
        if (aggregatedVisible) artworkAuraMotion.show() else artworkAuraMotion.hide()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        if (isVisible) {
            artworkAuraMotion.show()
        } else {
            if (frameScheduled) {
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                frameScheduled = false
            }
            artworkAuraMotion.hide()
        }
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        cancelAllGestures(sealMixer = true, hardResetScrub = true)
        removeCallbacks(mixerGestures.mixerPreviewDrainRunnable)
        mixerGestures.mixerPreviewDrainPosted = false
        mixerGestures.mixerPendingPreviewMask = 0
        mixerGestures.mixerDrainPreviewMask = 0
        // A temporary window detach must retain the selected tool and its settled geometry.
        artworkLoader.detach()
        mixerChromeLayer.discard()
        transportChromeLayer.discard()
        artworkAuraCache.recycle()
        // Temporary detach keeps the host subscription; AndroidView.onRelease clears it.
        mixerChromeDirty = true
        transportChromeDirty = true
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            frameScheduled = false
        }
        artworkAuraMotion.hide()
        super.onDetachedFromWindow()
    }

    private fun resetToolMotionController() {
        sourceMotionPending = false
        sourceMotionActive = false
        sourceMotionSeekerTop = Float.NaN
        outgoingMixerPicture = null
        clearRigidToolControlTranslation()
        rigidToolControlMotion = false
        toolMotion.reset()
    }

    private fun rebuildGeometry() {
        if (width <= 0 || height <= 0) return
        rebuildRouteGeometry()
        rebuildSourceGeometry()
        rebuildHeaderHitGeometry()
        rebuildControlGeometry()
    }

    /** Geometry that genuinely moves on each mini/full route frame. */
    private fun rebuildRouteGeometry() = routeGeometry.rebuild(
        width, height, density, expansionProgress, expandedArtworkTopPx(), expandedArtworkSizePx,
        expandedIdentityTopPx(expandedArtworkSizePx), visibleExpandedIdentityHeightPx())


    private fun rebuildHeaderHitGeometry() = routeGeometry.rebuildHeaderHits(
        width, density, sourceOriginalLeft, sourceOriginalRight, sourceStemsLeft, sourceStemsRight)


    private fun drawRegion(canvas: Canvas, region: DrawRegion) {
        val renderProfile = profile ?: return
        val catalog = visuals?.catalog ?: return
        if (seekerOnly && region != DrawRegion.TIMELINE) return
        when (region) {
            DrawRegion.PAGER_AURA -> pagerDrawing.drawPagerAura(canvas, catalog)
            DrawRegion.PAGER_ARTWORK -> pagerDrawing.drawPagerArtwork(canvas, catalog)
            DrawRegion.PAGER_IDENTITY -> pagerDrawing.drawPagerIdentity(canvas, catalog)
            DrawRegion.HEADER -> drawHeader(canvas)
            DrawRegion.SOURCE -> drawSource(canvas)
            DrawRegion.MIXER -> {
                if (!controlsValid || fullChromeAlpha(expansionProgress) <= .001f) return
                val mixerAlpha = if (sourceMotionPending || sourceMotionActive) {
                    PlayerMotionPolicy.sourceMixerAlpha(sourceWorkspaceProgress)
                } else 1f
                if (liveSourceMode == PlayerSurfaceSourceMode.ORIGINAL) {
                    outgoingMixerPicture?.let { picture ->
                        val saved = canvas.saveLayerAlpha(null, (255f * mixerAlpha *
                            fullChromeAlpha(expansionProgress)).roundToInt().coerceIn(0, 255))
                        canvas.translate(0f, seekerTop - outgoingMixerSeekerTop)
                        canvas.drawPicture(picture)
                        canvas.restoreToCount(saved)
                    }
                    return
                }
                val current = profile ?: return
                if (
                    liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
                    current.mixer.laneCount > 0 &&
                    mixerCardHeightPx > .01f
                ) {
                    val alpha = fullChromeAlpha(expansionProgress) * mixerAlpha
                    val mixerClip = canvas.save()
                    canvas.clipRect(mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)
                    val selectedAnimation = mixerDrawing.sampleMixerSelectedAnimations(
                        SystemClock.uptimeMillis(), current.mixer.laneCount
                    )
                    if (selectedAnimation) {
                        mixerChromeDirty = true
                        invalidateMixerRegion()
                    }
                    val retained = playerSurfaceRetainedChromeEligible(
                        hardwareAccelerated = canvas.isHardwareAccelerated,
                        controlsValid = controlsValid,
                        expansionProgress = expansionProgress
                    )
                    val transientMixerImpact = buttonBloomFrame.visible &&
                        (buttonAuraKind == BUTTON_MIXER_MUTE ||
                            buttonAuraKind == BUTTON_MIXER_SOLO)
                    if (
                        mixerAlpha >= .999f && !selectedAnimation && !transientMixerImpact && retained &&
                        mixerDrawing.drawRetainedMixerChrome(canvas, current.mixer.laneCount)
                    ) {
                        mixerDrawing.drawMixerVolumeOverlay(canvas, alpha, current.mixer.laneCount, current.layout.direction == PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT)
                    } else {
                        mixerDrawing.drawMixer(canvas, alpha, current.mixer.laneCount, current.layout.direction == PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT)
                    }
                    mixerDrawing.drawMixerButtonAuraOverlay(canvas, alpha, current.mixer.laneCount)
                    canvas.restoreToCount(mixerClip)
                    mixerDrawing.drawMixerValueLabels(canvas, alpha, current.mixer.laneCount, current.layout.direction == PlayerSurfaceLayoutDirection.RIGHT_TO_LEFT)
                }
            }
            DrawRegion.MINI_BODY -> pagerDrawing.drawMiniPlayerBody(canvas)
            DrawRegion.TIMELINE -> {
                if (controlsValid && !externalTransport) seekerDrawing.drawSeeker(canvas, seekerChromeAlpha(expansionProgress), renderProfile.timeline.durationMs, renderedPositionMs(renderProfile))
            }
            DrawRegion.TRANSPORT -> {
                if (!controlsValid || fullChromeAlpha(expansionProgress) <= .001f) return
                val alpha = fullChromeAlpha(expansionProgress)
                val selectedAnimation = sampleTransportSelectedAnimations(
                    SystemClock.uptimeMillis()
                )
                if (selectedAnimation) transportChromeDirty = true
                val retained = playerSurfaceRetainedChromeEligible(
                    hardwareAccelerated = canvas.isHardwareAccelerated,
                    controlsValid = controlsValid,
                    expansionProgress = expansionProgress
                )
                val transientTransportImpact = buttonBloomFrame.visible &&
                    buttonAuraKind == BUTTON_TRANSPORT
                if (!externalTransport) {
                    if (selectedAnimation || transientTransportImpact || !retained ||
                        !drawRetainedTransportChrome(canvas)) drawTransport(canvas, alpha)
                    drawTransportAuraOverlay(canvas, alpha)
                }
                // The row uses its unchanged authored bounds and separate transparent hit motors,
                // but is submitted with transport so both survive the exact same full endpoint.
                drawToolRow(canvas)
            }
            DrawRegion.MINI -> drawMiniPlay(canvas)
        }
    }

    private fun invalidateNonPagerRegions() {
        nonPagerRegionViews.forEach { it.postInvalidateOnAnimation() }
    }

    private fun invalidateEveryRegionForStructuralChange() {
        invalidateMiniBodyRegion()
        invalidatePagerRegions()
        invalidateNonPagerRegions()
    }

    private fun invalidatePagerAuraRegion() = pagerAuraRegion.postInvalidateOnAnimation()

    private fun invalidatePagerArtworkRegion() = pagerArtworkRegion.postInvalidateOnAnimation()

    private fun invalidatePagerIdentityRegion() = pagerIdentityRegion.postInvalidateOnAnimation()

    private fun invalidatePagerRegions() {
        // Tool geometry already advances inside Choreographer. Posting another animation
        // invalidation leaves artwork one frame behind the newly laid-out controls.
        pagerAuraRegion.invalidate()
        pagerArtworkRegion.invalidate()
        pagerIdentityRegion.invalidate()
    }
    private fun invalidateHeaderRegion() = headerRegion.postInvalidateOnAnimation()
    private fun invalidateSourceRegion() = sourceRegion.postInvalidateOnAnimation()
    private fun mixerValueLabelHeightPx(): Float {
        val metrics = app.stemwise.ui.design.StemwiseSliderValueLabelMetrics
        return max(metrics.MIN_HEIGHT_DP * density,
            scaledTextSizePx(metrics.LINE_HEIGHT_SP) + 2f * metrics.VERTICAL_PADDING_DP * density)
    }

    private fun invalidateMixerRegion() = mixerRegion.postInvalidateOnAnimation()
    private fun invalidateMixerRegionFromInput() = mixerRegion.invalidate()
    private fun invalidateTimelineRegion() = timelineRegion.postInvalidateOnAnimation()
    private fun invalidateTransportRegion() = transportRegion.postInvalidateOnAnimation()
    private fun invalidateToolRegion() = transportRegion.postInvalidateOnAnimation()

    private fun sampleTransportSelectedAnimations(nowMs: Long): Boolean {
        val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        var active = false
        var index = 0
        while (index < transportSelectedStates.size) {
            val target = when (index) {
                PlayerSurfaceTransportButtons.SHUFFLE -> liveShuffleEnabled
                PlayerSurfaceTransportButtons.PLAY -> true
                PlayerSurfaceTransportButtons.REPEAT -> liveRepeatMode != PlayerSurfaceRepeatMode.OFF
                else -> false
            }
            val state = transportSelectedStates[index]
            state.sample(target, nowMs, reducedMotion)
            active = active || state.needsAnotherFrame(nowMs)
            index++
        }
        return active
    }
    private fun invalidateMiniRegion() = miniRegion.postInvalidateOnAnimation()

    private fun invalidateMiniBodyRegion() = miniBodyRegion.postInvalidateOnAnimation()

    /** Visual-only feedback. Action dispatch remains exclusively in the semantic sink. */
    fun pulseSemanticFeedback(
        generation: Long,
        target: PlayerSurfaceSemanticFeedbackTarget
    ): Boolean {
        val current = profile ?: return false
        if (current.owner.generation != generation) return false
        val compact = PlayerMotionPolicy.miniChromeInteractive(expansionProgress)
        val full = PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)
        if (!compact && !full) return false
        val route = playerSurfaceSemanticFeedbackRoute(target, compact) ?: return false
        return when (route.channel) {
            PlayerSurfaceSemanticFeedbackChannel.COLLAPSE -> collapseAction.pulse()
            PlayerSurfaceSemanticFeedbackChannel.SONG_INFO -> infoAction.pulse()
            PlayerSurfaceSemanticFeedbackChannel.TOOL ->
                toolActions.getOrNull(route.index)?.pulse() == true
            PlayerSurfaceSemanticFeedbackChannel.SOURCE_ORIGINAL -> controlsValid &&
                pulseButtonFeedback(BUTTON_SOURCE_ORIGINAL, 0)
            PlayerSurfaceSemanticFeedbackChannel.SOURCE_STEMS -> {
                if (!controlsValid || !playerSurfaceStemsActionEnabled(current.source)) false
                else pulseButtonFeedback(BUTTON_SOURCE_STEMS, 0)
            }
            PlayerSurfaceSemanticFeedbackChannel.TRANSPORT -> {
                val enabled = controlsValid && !externalTransport &&
                    transportActionEnabled(current.transport, route.index)
                enabled && pulseButtonFeedback(
                    BUTTON_TRANSPORT,
                    route.index
                )
            }
            PlayerSurfaceSemanticFeedbackChannel.MINI_PLAY -> pulseButtonFeedback(
                BUTTON_MINI_PLAY,
                0
            )
            PlayerSurfaceSemanticFeedbackChannel.MIXER_MUTE,
            PlayerSurfaceSemanticFeedbackChannel.MIXER_SOLO -> {
                val enabled = controlsValid && liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
                    route.index in 0 until current.mixer.laneCount
                enabled && pulseButtonFeedback(
                    if (route.channel == PlayerSurfaceSemanticFeedbackChannel.MIXER_MUTE) {
                        BUTTON_MIXER_MUTE
                    } else {
                        BUTTON_MIXER_SOLO
                    },
                    route.index
                )
            }
        }
    }

    fun pulseCompactOpenFeedback(generation: Long): Boolean {
        val current = profile ?: return false
        if (current.owner.generation != generation ||
            !PlayerMotionPolicy.miniChromeInteractive(expansionProgress)
        ) return false
        return pulseButtonFeedback(BUTTON_MINI_BODY, 0)
    }

    private fun pulseButtonFeedback(
        kind: Int,
        index: Int
    ): Boolean {
        if (buttonPointerId != MotionEvent.INVALID_POINTER_ID) return false
        startButtonBloom(kind, index, SystemClock.uptimeMillis())
        return true
    }

    private fun updateNativeActionStates() {
        if (seekerOnly) {
            actionViews.forEach { it.visibility = INVISIBLE }
            return
        }
        val interactive = PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)
        collapseAction.visibility = if (interactive) VISIBLE else INVISIBLE
        infoAction.visibility = if (interactive) VISIBLE else INVISIBLE
        val activity = fullPlayerState.snapshot?.toolDetail?.activity
        var index = 0
        while (index < toolActions.size) {
            val tool = TOOL_ORDER[index]
            toolActions[index].visibility = if (interactive && controlsValid) VISIBLE else INVISIBLE
            toolActions[index].isSelected = liveSelectedTool == tool
            val available = when (tool) {
                PlayerSurfaceTool.LOOP -> activity?.loopAvailable == true
                PlayerSurfaceTool.LYRICS -> activity?.lyricsAvailable == true
                else -> true
            }
            toolActions[index].isEnabled = available
            // Accessibility state belongs exclusively to PlayerSurfaceSemanticsOverlay. Never
            // repopulate a physical hit motor's description during a bind: Samsung's AndroidView
            // bridge can resurrect that one named child even under a hidden native subtree.
            toolActions[index].contentDescription = null
            androidx.core.view.ViewCompat.setStateDescription(toolActions[index], null)
            index++
        }
    }

    private fun performCollapse(): Boolean {
        val generation = profile?.owner?.generation ?: return false
        if (!PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)) return false
        return sink?.onCollapsePlayer(generation) == true
    }

    private fun performShowInfo(): Boolean {
        val generation = profile?.owner?.generation ?: return false
        if (!PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)) return false
        return sink?.onShowSongInfo(generation) == true
    }

    private fun performTool(tool: PlayerSurfaceTool): Boolean {
        val generation = profile?.owner?.generation ?: return false
        if (!PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)) return false
        val activity = fullPlayerState.snapshot?.toolDetail?.activity
        if (tool == PlayerSurfaceTool.LOOP && activity?.loopAvailable != true) return false
        if (tool == PlayerSurfaceTool.LYRICS && activity?.lyricsAvailable != true) return false
        if (sink?.onTool(generation, tool) == true) {
            liveSelectedTool = if (liveSelectedTool == tool) PlayerSurfaceTool.NONE else tool
            updateNativeActionStates()
            invalidateToolRegion()
            return true
        }
        return false
    }

    private fun pagerRecordingArtworkSizePx(): Float = expandedArtworkSizePx

    private fun expandedVisualProfile(): PlayerSurfaceExpandedVisualProfile {
        val profile = playerSurfaceExpandedVisualProfile(
            PlayerSurfaceSourceMode.ORIGINAL,
            expandedArtworkSizePx / density.coerceAtLeast(.0001f)
        )
        // Balance artwork inside the space above the live seeker. Reading the moving control
        // anchor keeps this offset on the same native frame as a tool opening or closing.
        val topDp = (controlTop + rigidToolControlTranslationY) / density.coerceAtLeast(.0001f)
        val balancedOffset = ((topDp - profile.artistBaselineDp - 14f) * .5f).coerceAtLeast(0f)
        // Large text owns the whole reserved identity envelope. Under pressure the artwork
        // and identity move up together before their lower edge can meet the live clock.
        val identityBottomDp = profile.artworkTopDp + expandedArtworkSizePx / density +
            10f + expandedIdentityHeightPx / density
        // A compact route anchor cannot pull the full-size endpoint above its source header.
        val offset = minOf(balancedOffset, topDp - identityBottomDp - PLAYER_IDENTITY_TIMELINE_CLEARANCE_DP)
            .coerceAtLeast(PLAYER_SURFACE_HEADER_HEIGHT_DP + PLAYER_ARTWORK_HEADER_GAP_DP - profile.artworkTopDp)
        return profile.copy(
            artworkTopDp = profile.artworkTopDp + offset,
            titleBaselineDp = profile.titleBaselineDp + offset,
            artistBaselineDp = profile.artistBaselineDp + offset
        )
    }

    private fun expandedArtworkLeftPx(pageWidth: Float, artworkSizePx: Float): Float =
        ((pageWidth - artworkSizePx) * .5f).coerceAtLeast(0f)

    private fun expandedArtworkTopPx(): Float = expandedVisualProfile().artworkTopDp * density

    private fun expandedIdentityLeftPx(pageWidth: Float, artworkSizePx: Float): Float = min(20f * density, pageWidth)

    private fun expandedIdentityWidthPx(pageWidth: Float, identityLeftPx: Float): Float =
        (pageWidth - identityLeftPx * 2f).coerceAtLeast(0f)

    private fun expandedIdentityTranslationPx(artworkSizePx: Float): Float {
        val identityBottom = expandedArtworkTopPx() + artworkSizePx + 10f * density +
            visibleExpandedIdentityHeightPx()
        val room = controlTop + rigidToolControlTranslationY - identityBottom - PLAYER_IDENTITY_TIMELINE_CLEARANCE_DP * density
        return room.coerceIn(0f, 24f * density)
    }

    private fun expandedIdentityTopPx(artworkSizePx: Float): Float =
        expandedArtworkTopPx() + artworkSizePx + 10f * density + expandedIdentityTranslationPx(artworkSizePx)

    private fun visibleExpandedIdentityHeightPx(): Float =
        if (expandedArtworkSizePx <= .01f) 0f else expandedIdentityHeightPx

    private fun pagerRecordingBottom(artworkSizePx: Float): Float {
        val expandedBottom = playerSurfaceExpandedPagerBottomPx(
            artworkTopPx = expandedArtworkTopPx(),
            artworkSizePx = artworkSizePx,
            identityTopPx = expandedIdentityTopPx(artworkSizePx),
            identityHeightPx = visibleExpandedIdentityHeightPx()
        )
        return lerp(68f * density, expandedBottom, expansionProgress)
    }

    private fun identityLineLayout(value: String, sourcePaint: TextPaint): StaticLayout =
        identityLayoutCache.get(value, sourcePaint)

    private fun drawHeader(canvas: Canvas) = chromeDrawing.drawHeader(canvas, routeGeometry,
        headerAlpha(expansionProgress), collapseAction.pressBloomFrame, infoAction.pressBloomFrame)

    private fun drawSource(canvas: Canvas) {
        val current = profile ?: return
        val alpha = headerAlpha(expansionProgress)
        if (alpha <= .001f) return
        // Keep the proven workspace selected while seeking temporarily disables source actions.
        val visualSourceMode = liveSourceMode
        val outerLeft = sourceOriginalLeft
        val outerTop = sourceOriginalTop
        val outerRight = sourceStemsRight
        val outerBottom = sourceStemsBottom
        val outerRadius = 16f * density
        fillPaint.shader = null
        fillPaint.alpha = 255
        fillPaint.color = withAlpha(SURFACE_LOW, alpha * .12f)
        canvas.drawRoundRect(
            outerLeft,
            outerTop,
            outerRight,
            outerBottom,
            outerRadius,
            outerRadius,
            fillPaint
        )
        strokePaint.shader = null
        strokePaint.strokeWidth = density
        strokePaint.color = withAlpha(OUTLINE, alpha)
        canvas.drawRoundRect(
            outerLeft + .5f * density,
            outerTop + .5f * density,
            outerRight - .5f * density,
            outerBottom - .5f * density,
            outerRadius - .5f * density,
            outerRadius - .5f * density,
            strokePaint
        )
        drawSourceButton(
            canvas,
            sourceOriginalLeft,
            sourceOriginalTop,
            sourceOriginalRight,
            sourceOriginalBottom,
            selected = visualSourceMode == PlayerSurfaceSourceMode.ORIGINAL,
            enabled = true,
            icon = PlayerSurfaceIcon.ORIGINAL,
            label = "Original",
            alpha = alpha
        )
        drawSourceButton(
            canvas,
            sourceStemsLeft,
            sourceStemsTop,
            sourceStemsRight,
            sourceStemsBottom,
            selected = visualSourceMode == PlayerSurfaceSourceMode.STEMS &&
                playerSurfaceStemsStatus(current.source) == PlayerSurfaceStemsStatus.READY,
            enabled = playerSurfaceStemsActionEnabled(current.source),
            icon = PlayerSurfaceIcon.STEMS,
            label = when (playerSurfaceStemsStatus(current.source)) {
                PlayerSurfaceStemsStatus.SWITCHING -> "Stems"
                PlayerSurfaceStemsStatus.DOWNLOADING, PlayerSurfaceStemsStatus.PREPARING -> "Stems"
                PlayerSurfaceStemsStatus.FAILED -> if (current.source.phase == PlayerSurfaceSourcePhase.FAILED) {
                    "Retry stems"
                } else "Parts failed"
                else -> "Stems"
            },
            alpha = alpha,
            stemStatus = playerSurfaceStemsStatus(current.source)
        )
    }

    private fun drawSourceButton(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        selected: Boolean,
        enabled: Boolean,
        icon: PlayerSurfaceIcon,
        label: String,
        alpha: Float,
        stemStatus: PlayerSurfaceStemsStatus? = null
    ) {
        if (right <= left || bottom <= top) return
        val auraKind = if (icon == PlayerSurfaceIcon.ORIGINAL) {
            BUTTON_SOURCE_ORIGINAL
        } else {
            BUTTON_SOURCE_STEMS
        }
        val transientPress = enabled && buttonAuraKind == auraKind && buttonBloomFrame.visible
        val selectedState = sourceSelectedStates[
            if (icon == PlayerSurfaceIcon.ORIGINAL) 0 else 1
        ]
        val selectedNowMs = SystemClock.uptimeMillis()
        val selectedProgress = selectedState.sample(
            targetSelected = selected,
            nowMs = selectedNowMs,
            reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        )
        val selectedAuraProgress = playerSurfaceSelectedAuraProgress(
            selectedProgress)
        if (selectedState.needsAnotherFrame(selectedNowMs)) invalidateSourceRegion()
        val ready = stemStatus == PlayerSurfaceStemsStatus.READY
        val unavailable = stemStatus != null && !ready
        val contentAlpha = alpha * if (unavailable) .62f else 1f
        val glyph = if (stemStatus == PlayerSurfaceStemsStatus.DOWNLOAD) {
            PlayerSurfaceIcon.DOWNLOAD
        } else icon
        val contentCenterY = (top + bottom) * .5f
        val sourceIconSize = when (icon) {
            PlayerSurfaceIcon.ORIGINAL -> 21f
            PlayerSurfaceIcon.STEMS -> 23f
            else -> error("Source icon size requested for non-source role: $icon")
        } * density
        textPaint.typeface = systemSemiboldTypeface
        textPaint.textSize = scaledTextSizePx(if (label.length > 5) 10.5f else 12f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = withAlpha(
            blendOpaqueColors(TEXT_SECONDARY, TEXT_PRIMARY, selectedProgress * .2f),
            contentAlpha
        )
        val labelWidth = textPaint.measureText(label)
        val availableWidth = right - left - 16f * density
        val showIcon = labelWidth + sourceIconSize + 8f * density <= availableWidth
        val iconSize = if (showIcon) sourceIconSize else 0f
        val labelGap = if (showIcon) 8f * density else 0f
        val displayedLabel = ellipsizedLabel(
            label,
            availableWidth - iconSize - labelGap
        )
        val contentStartX = playerSurfaceSourceContentStartX(
            left = left,
            right = right,
            iconWidth = iconSize,
            labelWidth = textPaint.measureText(displayedLabel),
            gap = labelGap
        )
        val iconCenterX = contentStartX + iconSize * .5f
        val iconCenterY = contentCenterY
        val labelX = contentStartX + iconSize + labelGap
        val labelBaseline = playerSurfaceCenteredTextBaselineY(
            contentCenterY,
            textPaint.ascent(),
            textPaint.descent()
        )
        val radius = 16f * density
        if (selectedAuraProgress > .001f) {
            motionDrawing.drawSelectedBodyShadow(
                canvas,
                left,
                top,
                right,
                bottom,
                radius,
                alpha * selectedAuraProgress
            )
        }
        if (selectedProgress > .001f) {
            interactionMatrix.reset()
            interactionMatrix.setScale(right - left, bottom - top)
            interactionMatrix.postTranslate(left, top)
            selectedFillShader.setLocalMatrix(interactionMatrix)
            fillPaint.shader = selectedFillShader
            fillPaint.alpha =
                (alpha.coerceIn(0f, 1f) * selectedProgress * .25f * 255f).roundToInt()
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
            fillPaint.shader = null
            fillPaint.alpha = 255
            strokePaint.shader = null
            strokePaint.strokeWidth = lerp(1f, 1.4f, selectedProgress) * density
            strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha * selectedProgress * .65f)
            canvas.drawRoundRect(
                left + .7f * density,
                top + .7f * density,
                right - .7f * density,
                bottom - .7f * density,
                radius - .7f * density,
                radius - .7f * density,
                strokePaint
            )
        }
        if (ready && selectedProgress < .999f) {
            // A steady, restrained rim signals readiness without another perpetual animation.
            motionDrawing.drawSelectedBodyShadow(canvas, left, top, right, bottom, radius,
                alpha * .28f * (1f - selectedProgress))
            strokePaint.shader = null
            strokePaint.strokeWidth = density
            strokePaint.color = withAlpha(PRIMARY_BRIGHT, alpha * .48f)
            canvas.drawRoundRect(left + density, top + density, right - density,
                bottom - density, radius, radius, strokePaint)
        }
        val iconColor = withAlpha(
            blendOpaqueColors(TEXT_SECONDARY, PRIMARY_BRIGHT, selectedProgress * .2f),
            contentAlpha
        )
        if (showIcon && selectedAuraProgress > .001f) {
            motionDrawing.drawSelectedGlyphGlow(
                canvas,
                icon,
                iconCenterX,
                iconCenterY,
                iconSize,
                alpha * selectedAuraProgress
            )
        }
        if (transientPress) {
            motionDrawing.drawNativePressBloom(
                canvas,
                buttonBloomFrame,
                left,
                top,
                right,
                bottom,
                alpha
            )
        }

        if (showIcon && stemStatus == PlayerSurfaceStemsStatus.DOWNLOADING) {
            val rotating = ValueAnimator.areAnimatorsEnabled()
            val angle = if (rotating) (SystemClock.uptimeMillis() % 1_200L) * .3f else -90f
            val half = iconSize * .36f
            strokePaint.shader = null
            strokePaint.strokeWidth = 2f * density
            strokePaint.color = iconColor
            canvas.drawArc(iconCenterX - half, iconCenterY - half,
                iconCenterX + half, iconCenterY + half, angle, 260f, false, strokePaint)
            if (rotating) invalidateSourceRegion()
        } else if (showIcon) {
            iconAtlas.draw(canvas, glyph, iconCenterX, iconCenterY, iconSize, iconColor)
        }
        canvas.drawText(
            displayedLabel,
            labelX,
            labelBaseline,
            textPaint
        )
    }

    private fun drawRetainedTransportChrome(canvas: Canvas): Boolean {
        val layer = transportChromeLayer
        val padding = ceil(2f * density).toInt()
        val left = (floor(controlLeft).toInt() - padding).coerceAtLeast(0)
        val top = (floor(transportTop).toInt() - padding).coerceAtLeast(0)
        val right = (ceil(controlRight).toInt() + padding).coerceAtMost(width)
        val bottom = (ceil(transportBottom).toInt() + padding).coerceAtMost(height)
        if (right <= left || bottom <= top) return false
        if (layer.setBounds(left, top, right, bottom)) transportChromeDirty = true
        if (transportChromeDirty || !layer.hasRecording()) {
            val recording = layer.beginRecording()
            try {
                val save = recording.save()
                recording.translate(-left.toFloat(), -top.toFloat())
                drawTransport(recording, 1f)
                recording.restoreToCount(save)
            } finally {
                layer.endRecording()
            }
            transportChromeDirty = false
        }
        layer.draw(canvas)
        return true
    }

    private fun rebuildSourceGeometry() {
        // Keep the authored 248x48 selector and recenter it inside the live viewport. At the
        // supported 360dp minimum it meets, but never overlaps, the two 48dp header targets.
        val selectorHalfWidth = 124f * density
        val center = width * .5f
        val left = (center - selectorHalfWidth).coerceAtLeast(routeGeometry.collapseRight)
        val right = (center + selectorHalfWidth).coerceAtMost(routeGeometry.infoLeft).coerceAtLeast(left)
        val half = (right - left) * .5f
        sourceOriginalLeft = left
        sourceOriginalTop = 6f * density
        sourceOriginalRight = left + half
        sourceOriginalBottom = sourceOriginalTop + 48f * density
        sourceStemsLeft = sourceOriginalRight
        sourceStemsTop = sourceOriginalTop
        sourceStemsRight = right
        sourceStemsBottom = sourceOriginalBottom
    }

    private fun rebuildControlGeometry() {
        controlGeometry.cards.fill(0f)
        controlGeometry.sliders.fill(0f)
        controlGeometry.mute.hit.fill(0f)
        controlGeometry.solo.hit.fill(0f)
        controlGeometry.mute.visual.fill(0f)
        controlGeometry.solo.visual.fill(0f)
        controlGeometry.transport.visual.fill(0f)
        controlGeometry.transport.hit.fill(0f)
        controlGeometry.tools.visual.fill(0f)
        controlGeometry.tools.hit.fill(0f)
        // Compose may publish a valid control envelope before Android's first View layout.
        // Preserve it, but wait for onSizeChanged/onLayout to supply the real viewport width.
        if (!controlsValid || width <= 0 || height <= 0) return
        val current = profile ?: return
        var y = controlTop
        val mixerVisible = !seekerOnly && liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
            current.mixer.laneCount > 0 &&
            mixerCardHeightPx > .01f
        if (mixerVisible) {
            val viewportHeight = expandedArtworkSizePx.coerceAtLeast(0f)
            mixerHeaderTop = expandedArtworkTopPx()
            mixerGridTop = mixerHeaderTop
            mixerGridLeft = controlLeft + 24f * density
            mixerGridRight = (controlRight - 24f * density).coerceAtLeast(mixerGridLeft)
            mixerScrollMaximumPx = playerSurfaceMixerMaximumScrollPx(
                current.mixer.laneCount, mixerCardHeightPx, mixerSpacingPx, viewportHeight
            )
            mixerScrollOffsetPx = mixerScrollOffsetPx.coerceIn(0f, mixerScrollMaximumPx)
            val cardWidth = (mixerGridRight - mixerGridLeft).coerceAtLeast(0f)
            textPaint.textSize = scaledTextSizePx(14f)
            var lane = 0
            while (lane < current.mixer.laneCount) {
                val left = mixerGridLeft
                val top = mixerGridTop + lane * (mixerCardHeightPx + mixerSpacingPx) - mixerScrollOffsetPx
                putBounds(controlGeometry.cards, lane, left, top, left + cardWidth, top + mixerCardHeightPx)
                val offset = lane * 4
                fillPlayerSurfaceMixerToggleTargets(
                    cardTop = top, cardLeft = left, cardRight = left + cardWidth,
                    cardBottom = top + mixerCardHeightPx, density = density,
                    mute = controlGeometry.mute.hit, solo = controlGeometry.solo.hit, offset = offset
                )
                fillPlayerSurfaceMixerToggleVisualBounds(controlGeometry.mute.hit, controlGeometry.mute.visual,
                    offset, density, mixerCardHeightPx)
                fillPlayerSurfaceMixerToggleVisualBounds(controlGeometry.solo.hit, controlGeometry.solo.visual,
                    offset, density, mixerCardHeightPx)
                val sliderLeft = controlGeometry.mute.hit[offset + 2] + 12f * density
                val sliderRight = controlGeometry.solo.hit[offset] - 12f * density
                putBounds(
                    controlGeometry.sliders,
                    lane,
                    sliderLeft,
                    top,
                    sliderRight,
                    top + mixerCardHeightPx
                )
                lane++
            }
            mixerGridBottom = mixerGridTop + viewportHeight
        } else {
            mixerScrollMaximumPx = 0f
            mixerHeaderTop = 0f
            mixerGridTop = 0f
            mixerGridBottom = 0f
        }
        if (seekerOnly || expandedArtworkSizePx > .01f) y += seekerClockTopClearancePx
        if (sourceMotionSeekerTop.isFinite()) y = sourceMotionSeekerTop
        seekerTop = y
        seekerBottom = y + seekerTrackHeightPx
        val seekerRailEndpointInset = PLAYER_SURFACE_SEEKER_RAIL_ENDPOINT_INSET_DP * density
        seekerTrackLeft = playerSurfaceSeekerTrackStartX(
            controlLeft,
            controlRight,
            seekerRailEndpointInset
        )
        seekerTrackRight = playerSurfaceSeekerTrackEndX(
            controlLeft,
            controlRight,
            seekerRailEndpointInset
        )
        y = seekerBottom + listSpacingPx
        if (loopClearanceProgress > .001f) {
            val maximumLoopBodyBottom = loopEndpointHandleTop(
                seekerRailY(),
                fine = 1f
            ) + StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP * density
            y = playerSurfaceLowerStackTopPx(
                baseTopPx = y,
                loopClearanceTopPx = maximumLoopBodyBottom + listSpacingPx,
                loopClearanceProgress = loopClearanceProgress
            )
        }
        transportTop = y
        transportBottom = y + transportHeightPx
        rebuildTransportGeometry()
        // The profile spacing is interpolated by the live tool/source motion clock; no fixed
        // 10dp pocket survives between transport and tools on compact endpoints.
        y = transportBottom + listSpacingPx
        toolTop = y
        toolBottom = y + if (expandedArtworkSizePx <= .01f) 48f * density
            else playerToolRowHeightDp(false, fontScale) * density
        rebuildToolGeometry()
    }

    private fun rebuildTransportGeometry() {
        val viewportWidthDp = width / density.coerceAtLeast(.0001f)
        var index = 0
        while (index < 5) {
            val size = if (index == 2) transportPlaySizePx else 48f * density
            val centerX = playerSurfaceTransportCenterXDp(index, viewportWidthDp) * density
            val left = centerX - size * .5f
            val top = transportTop + (transportHeightPx - size) * .5f
            putBounds(controlGeometry.transport.visual, index, left, top, left + size, top + size)
            index++
        }
        rebuildPartitionedRowHitGeometry(
            visualBounds = controlGeometry.transport.visual,
            hitBounds = controlGeometry.transport.hit,
            count = 5,
            rowTop = transportTop,
            rowBottom = transportBottom
        )
    }

    private fun rebuildToolGeometry() {
        val columns = if (expandedArtworkSizePx <= .01f) 4 else playerToolColumnCount(fontScale)
        val size = (if (columns == 2) PLAYER_SURFACE_TOOL_ICON_SIZE_DP else if (expandedArtworkSizePx <= .01f) 48f else 52f) * density
        val rowHeight = (toolBottom - toolTop) / (4 / columns)
        val viewportWidthDp = width / density.coerceAtLeast(.0001f)
        // Measure the widest authored label in its widest rendered weight. A compact screen must
        // reserve the complete visible text, not merely the 52dp motor around the icon.
        textPaint.typeface = systemSemiboldTypeface
        textPaint.textSize = scaledTextSizePx(11.5f)
        val widestLabelHalfWidthDp = TOOL_LABELS.maxOf(textPaint::measureText) * .5f /
            density.coerceAtLeast(.0001f) + 1f
        val outerVisibleHalfWidthDp = max(26f, widestLabelHalfWidthDp)
        var index = 0
        while (index < 4) {
            val centerX = if (columns == 2) index % 2 * width * .5f + 2f * density + size * .5f else playerSurfaceToolCenterXDp(
                index = index,
                viewportWidthDp = viewportWidthDp,
                outerVisibleHalfWidthDp = outerVisibleHalfWidthDp
            ) * density
            val left = centerX - size * .5f
            val rowTop = toolTop + index / columns * rowHeight
            val top = rowTop + if (columns == 2) (rowHeight - size) * .5f else 0f
            putBounds(controlGeometry.tools.visual, index, left, top, left + size, top + size)
            if (columns == 2) putBounds(controlGeometry.tools.hit, index,
                index % 2 * width * .5f, rowTop, (index % 2 + 1) * width * .5f, rowTop + rowHeight)
            index++
        }
        if (columns == 4) fillPlayerSurfacePartitionedRowTargets(
            visualBounds = controlGeometry.tools.visual,
            hitBounds = controlGeometry.tools.hit,
            count = 4,
            outerLeft = 0f,
            outerRight = width.toFloat(),
            top = toolTop,
            bottom = toolBottom
        )
        index = 0
        while (index < TOOL_LABELS.size) {
            val base = index * 4
            toolDisplayLabels[index] = ellipsizedLabel(
                TOOL_LABELS[index],
                if (columns == 2) controlGeometry.tools.hit[base + 2] - controlGeometry.tools.visual[base + 2] - 4f * density
                else controlGeometry.tools.hit[base + 2] - controlGeometry.tools.hit[base] - 8f * density
            )
            index++
        }
    }

    private fun scaledTextSizePx(textSizeSp: Float): Float =
        playerNativeTextSizePx(textSizeSp, density, fontScale)

    private fun ellipsizedLabel(label: String, maximumWidthPx: Float): String =
        TextUtils.ellipsize(
            label,
            textPaint,
            maximumWidthPx.coerceAtLeast(0f),
            TextUtils.TruncateAt.END
        ).toString()

    private fun rebuildPartitionedRowHitGeometry(
        visualBounds: FloatArray,
        hitBounds: FloatArray,
        count: Int,
        rowTop: Float,
        rowBottom: Float
    ) {
        fillPlayerSurfacePartitionedRowTargets(
            visualBounds, hitBounds, count,
            min(controlLeft, visualBounds[0]),
            max(controlRight, visualBounds[(count - 1) * 4 + 2]), rowTop, rowBottom
        )
    }

    private fun syncMixerFromProfile(profile: PlayerSurfaceProfilePacket) {
        var lane = 0
        while (lane < PLAYER_SURFACE_MAX_MIXER_LANES) {
            if (lane < profile.mixer.laneCount) {
                val volume = profile.mixer.volumeAt(lane)
                val muted = profile.mixer.mutedAt(lane) || volume <= 0f
                mixerGestures.committedMixerVolumes[lane] = volume
                mixerGestures.committedMixerMuted[lane] = muted
                if (mixerGestures.mixerPointerIds[lane] == MotionEvent.INVALID_POINTER_ID) {
                    mixerGestures.liveMixerVolumes[lane] = app.stemwise.ui.stemSliderEffectiveFraction(volume, muted)
                    mixerGestures.mixerMuted[lane] = muted
                }
                mixerGestures.mixerSolo[lane] = profile.mixer.soloAt(lane)
            } else {
                mixerGestures.committedMixerVolumes[lane] = 0f
                mixerGestures.committedMixerMuted[lane] = false
                mixerGestures.liveMixerVolumes[lane] = 0f
                mixerGestures.mixerMuted[lane] = false
                mixerGestures.mixerSolo[lane] = false
            }
            lane++
        }
    }

    private fun liveMixerChromeStateMask(laneCount: Int): Long =
        playerSurfaceMixerChromeStateMask(mixerGestures.mixerMuted, mixerGestures.mixerSolo, laneCount)

    private fun artworkRequestForQueueIndex(queueIndex: Int): PlayerSurfaceArtworkRequest? {
        val item = visuals?.catalog?.getOrNull(queueIndex) ?: return null
        return artworkRequest(item.songId, item.mediaRevision, item.artworkSource)
    }

    private fun artworkRequest(
        songId: String,
        mediaRevision: String,
        artworkSource: String?
    ): PlayerSurfaceArtworkRequest? {
        val currentProfile = profile ?: return null
        val source = artworkSource?.takeIf(String::isNotBlank) ?: return null
        val decodeSize = playerSurfaceArtworkDecodeSizePx(
            surfaceWidthPx = currentProfile.layout.widthPx,
            density = currentProfile.layout.density
        )
        return PlayerSurfaceArtworkRequest(
            owner = currentProfile.owner.copy(
                songId = songId,
                mediaRevision = mediaRevision
            ),
            source = source,
            widthPx = decodeSize,
            heightPx = decodeSize
        )
    }

    /** Promotes the exact playback target into this loader's sole current decode lane. */
    fun prioritizeSelectionArtwork(
        songId: String,
        mediaRevision: String,
        artworkSource: String?
    ): Boolean {
        val current = profile ?: return false
        val request = artworkRequest(songId, mediaRevision, artworkSource) ?: return false
        return artworkLoader.prioritize(current.owner.generation, request)
    }

    fun restoreSelectionArtworkPriority() {
        profile?.owner?.generation?.let(artworkLoader::restoreCurrentPriority)
    }

    /** One target admission starts exact artwork and Original readiness from the same callback. */
    private fun previewPagerSelection(
        generation: Long,
        targetIndex: Int?,
        offsetFraction: Float
    ): Boolean {
        val current = profile ?: return false
        if (current.owner.generation != generation || targetIndex == null) {
            cancelPagerSelectionPreview(generation)
            return false
        }
        artworkRequestForQueueIndex(targetIndex)?.let { request ->
            artworkLoader.prioritize(generation, request)
        }
        val songId = visuals?.catalog?.getOrNull(targetIndex)?.songId ?: return false
        val accepted = sink?.onPagerPreview(generation, targetIndex, offsetFraction, songId) == true
        if (!accepted) artworkLoader.restoreCurrentPriority(generation)
        return accepted
    }

    private fun cancelPagerSelectionPreview(generation: Long) {
        artworkLoader.restoreCurrentPriority(generation)
        sink?.onPagerCancel(generation)
    }

    /** Accessibility uses the same artwork + Original admission as touch navigation. */
    fun performSemanticNavigation(
        generation: Long,
        targetIndex: Int?,
        offsetFraction: Float,
        commit: () -> Boolean
    ): Boolean {
        val exactTarget = targetIndex ?: playerSurfaceSelectionPreviewIndex(
            currentIndex = visualQueueIndex,
            itemCount = visuals?.catalog?.size ?: 0,
            offsetFraction = offsetFraction,
            repeatMode = liveRepeatMode,
            allowRepeatAllWrap = true
        )
        if (!previewPagerSelection(generation, exactTarget, offsetFraction)) return false
        val accepted = commit()
        if (!accepted) cancelPagerSelectionPreview(generation)
        return accepted
    }

    private fun bindArtworkSlots() {
        if (seekerOnly) return
        val currentProfile = profile ?: return
        val items = visuals?.catalog ?: return
        val center = visualQueueIndex
        // Decode once for the largest portrait cover this surface can display. Tool-panel
        // animations may resize the live rectangle on every frame; keying Coil requests to that
        // animated size restarted/cancelled decode work and could briefly expose a placeholder.
        // The shader scales this stable bitmap continuously for mini/full and tool morphs.
        val decodeSize = playerSurfaceArtworkDecodeSizePx(
            surfaceWidthPx = currentProfile.layout.widthPx,
            density = currentProfile.layout.density
        )
        artworkDecodeSizePx = decodeSize
        // Snapshot the previous three residents before changing any destination. Adjacent commits
        // shift two exact covers between lower/current/higher; clearing destinations one by one
        // destroyed those still-valid bitmaps and exposed a placeholder for at least one frame.
        val previousKeys = artworkDisplayKeys.copyOf()
        val previousBitmaps = artworkBitmaps.copyOf()
        val desiredItems = arrayOfNulls<FullPlayerCatalogSong>(3)
        val desiredKeys = arrayOfNulls<PlayerSurfaceArtworkDisplayKey>(3)
        var desiredSlot = 0
        while (desiredSlot < 3) {
            val item = items.getOrNull(center + desiredSlot - 1)
            desiredItems[desiredSlot] = item
            val source = item?.artworkSource?.trim().orEmpty()
            desiredKeys[desiredSlot] = if (item != null && source.isNotEmpty()) {
                PlayerSurfaceArtworkDisplayKey(
                    songId = item.songId,
                    physicalSource = source,
                    mediaRevision = item.mediaRevision.trim(),
                    decodeSizePx = decodeSize,
                    accentArgb = item.accentArgb
                )
            } else {
                null
            }
            desiredSlot++
        }

        desiredSlot = 0
        while (desiredSlot < 3) {
            val desiredKey = desiredKeys[desiredSlot]
            var resident: Bitmap? = artworkBitmaps[desiredSlot]
                ?.takeIf { artworkDisplayKeys[desiredSlot] == desiredKey && !it.isRecycled }
            val keepArrivalTransition = artworkDisplayKeys[desiredSlot] == desiredKey &&
                artworkLoadTransitionActive[desiredSlot]
            if (resident == null && desiredKey != null) {
                var previousSlot = 0
                while (previousSlot < 3 && resident == null) {
                    resident = previousBitmaps[previousSlot]?.takeIf {
                        previousKeys[previousSlot] == desiredKey && !it.isRecycled
                    }
                    previousSlot++
                }
            }
            artworkDisplayKeys[desiredSlot] = desiredKey
            artworkBitmaps[desiredSlot] = resident
            if (!keepArrivalTransition) {
                artworkLoadProgress[desiredSlot] = 1f
                artworkLoadStartedAtNanos[desiredSlot] = 0L
                artworkLoadTransitionActive[desiredSlot] = false
            }
            // BitmapShader has mutable local-matrix state; never share one between destination
            // slots even when both legitimately reuse the same immutable Bitmap.
            artworkShaders[desiredSlot] = resident?.let {
                BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val auraColors = resident?.let { deriveNativeArtworkAuraColors(it, desiredKey) }
                ?: intArrayOf(PRIMARY_PURPLE, ARTWORK_AURA_BLUE_ARGB, CORAL_ARGB)
            val auraChanged = !artworkAuraColors[desiredSlot].contentEquals(auraColors)
            artworkAuraColors[desiredSlot] = auraColors
            if (auraChanged) artworkAuraPaletteRevision[desiredSlot]++
            if (resident != null) {
                artworkAuraCache.prepare(desiredSlot, desiredKey,
                    artworkAuraPaletteRevision[desiredSlot], auraColors)
            } else artworkAuraCache.clearSlot(desiredSlot)
            artworkGradients[desiredSlot] = desiredItems[desiredSlot]?.let {
                val artworkPurple = blendArtworkTint(PRIMARY_PURPLE, it.accentArgb, .16f)
                val artworkDeepBlue = blendArtworkTint(0xFF303DAD.toInt(), it.accentArgb, .10f)
                LinearGradient(
                    0f,
                    0f,
                    max(1f, expandedArtworkSizePx),
                    max(1f, expandedArtworkSizePx),
                    intArrayOf(
                        withAlpha(artworkPurple, .62f),
                        withAlpha(artworkDeepBlue, .34f),
                        INK
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            desiredSlot++
        }
        publishCurrentArtworkPalette()
        // Admit the current cover before hidden neighbours. It owns an urgent decode lane while
        // 1536 px side/predictive covers share a background lane, so a newly selected song never
        // waits behind speculative artwork work.
        var slotOrderIndex = 0
        while (slotOrderIndex < 3) {
            val slot = when (slotOrderIndex) {
                0 -> PlayerSurfaceArtworkLoader.SLOT_CURRENT
                1 -> PlayerSurfaceArtworkLoader.SLOT_LOWER
                else -> PlayerSurfaceArtworkLoader.SLOT_HIGHER
            }
            val item = desiredItems[slot]
            val displayKey = desiredKeys[slot]
            val source = item?.artworkSource
            if (item == null || displayKey == null || source.isNullOrBlank()) {
                artworkLoader.cancelSlot(slot)
            } else {
                val request = PlayerSurfaceArtworkRequest(
                    owner = currentProfile.owner.copy(
                        songId = item.songId,
                        mediaRevision = item.mediaRevision
                    ),
                    source = source,
                    widthPx = decodeSize,
                    heightPx = decodeSize
                )
                artworkLoader.request(slot, request) { result ->
                    if (
                        result.request.owner.generation == profile?.owner?.generation &&
                        result.request.widthPx == artworkDecodeSizePx
                    ) {
                        setArtworkBitmap(
                            result.slot,
                            displayKey,
                            result.bitmap
                        )
                    }
                }
            }
            slotOrderIndex++
        }
        artworkLoader.restoreCurrentPriority(currentProfile.owner.generation)
        val predictive = ArrayList<PlayerSurfaceArtworkRequest>(2)
        val predictiveIndices = playerSurfacePredictiveArtworkIndices(
            currentIndex = center,
            itemCount = items.size,
            repeatMode = liveRepeatMode
        )
        var predictiveIndex = 0
        while (predictiveIndex < predictiveIndices.size) {
            val item = items.getOrNull(predictiveIndices[predictiveIndex])
            val source = item?.artworkSource
            if (item != null && !source.isNullOrBlank()) {
                predictive += PlayerSurfaceArtworkRequest(
                    owner = currentProfile.owner.copy(
                        songId = item.songId,
                        mediaRevision = item.mediaRevision
                    ),
                    source = source,
                    widthPx = decodeSize,
                    heightPx = decodeSize
                )
            }
            predictiveIndex++
        }
        artworkLoader.prefetch(predictive)
    }

    private fun putBounds(array: FloatArray, index: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val base = index * 4
        array[base] = left
        array[base + 1] = top
        array[base + 2] = right
        array[base + 3] = bottom
    }

    private fun startButtonBloom(kind: Int, index: Int, nowMs: Long) {
        performStemwiseHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // The old region may otherwise retain its last highlight after this shared owner moves.
        invalidateButtonBloomRegion(buttonAuraKind)
        buttonAuraKind = kind
        buttonAuraIndex = index
        buttonBloom.start(
            nowMs,
            reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        )
        invalidateButtonBloomRegion(kind)
        scheduleFrame()
    }

    private fun invalidateButtonBloomRegion(kind: Int) {
        when (kind) {
            BUTTON_SOURCE_ORIGINAL,
            BUTTON_SOURCE_STEMS -> sourceRegion.invalidate()
            BUTTON_TRANSPORT -> invalidateTransportRegion()
            BUTTON_MINI_PLAY -> invalidateMiniRegion()
            BUTTON_MINI_BODY -> invalidateMiniBodyRegion()
            BUTTON_ARTWORK_SKIP -> invalidatePagerArtworkRegion()
            BUTTON_MIXER_MUTE,
            BUTTON_MIXER_SOLO -> invalidateMixerRegion()
        }
    }

    /** A glyph-shaped halo for rectangular owners; it cannot read as a selected circle. */
    private fun sampleAuraFrames(nowMs: Long): Boolean {
        var active = false
        if (buttonBloom.sample(nowMs, buttonBloomFrame)) {
            invalidateButtonBloomRegion(buttonAuraKind)
        }
        if (buttonBloomFrame.needsAnotherFrame) active = true
        if (scrubHeldHighlight.advance()) {
            invalidateTimelineRegion()
        } else {
            loopHandleVisualOwner.clearFinishedTail()
        }
        if (scrubHeldHighlight.needsAnotherFrame()) active = true
        val growthElapsed = nowMs - scrubGrowthStartedAtMs
        if (scrubGrowthStartedAtMs > 0L && growthElapsed < scrubGrowthDurationMs) {
            active = true
            invalidateTimelineRegion()
        } else if (scrubGrowthTo == 0f) {
            scrubGrowthBoundary = null
        }
        var lane = 0
        while (lane < mixerHeldHighlights.size) {
            if (mixerHeldHighlights[lane].advance()) {
                invalidateMixerRegion()
            }
            val growth = mixerThumbGrowth[lane]
            growth.sample(isMixerSliderPressed(lane),
                nowMs, !ValueAnimator.areAnimatorsEnabled())
            if (growth.needsAnotherFrame(nowMs)) {
                active = true
                invalidateMixerRegion()
            }
            val valueLabel = mixerValueLabels[lane]
            valueLabel.sample(mixerGestures.mixerPointerIds[lane] != MotionEvent.INVALID_POINTER_ID,
                nowMs, !ValueAnimator.areAnimatorsEnabled())
            if (valueLabel.needsAnotherFrame(nowMs)) {
                active = true
                invalidateMixerRegion()
            }
            lane++
        }
        var actionIndex = 0
        while (actionIndex < actionViews.size) {
            if (actionViews[actionIndex].samplePressBloom(nowMs)) active = true
            actionIndex++
        }
        return active
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun currentScrubGrowth(nowMs: Long): Float {
        if (scrubGrowthStartedAtMs <= 0L || scrubGrowthDurationMs <= 0) return scrubGrowthTo
        val fraction = ((nowMs - scrubGrowthStartedAtMs).coerceAtLeast(0L).toFloat() /
            scrubGrowthDurationMs).coerceIn(0f, 1f)
        val eased = SEEKER_GROWTH_EASING.transform(fraction)
        return lerp(scrubGrowthFrom, scrubGrowthTo, eased)
    }

    private fun animateScrubGrowthTo(
        target: Float,
        nowMs: Long,
        boundary: FullPlayerLoopBoundary?
    ) {
        val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        val continuesSameOwner = scrubGrowthBoundary == boundary
        val currentGrowth = currentScrubGrowth(nowMs)
        scrubGrowthTo = target.coerceIn(0f, 1f)
        scrubGrowthFrom = when {
            reducedMotion -> scrubGrowthTo
            continuesSameOwner -> currentGrowth
            else -> 0f
        }
        scrubGrowthStartedAtMs = if (reducedMotion) 0L else nowMs
        scrubGrowthDurationMs = playerSurfaceScrubGrowthDurationMs(boundary, reducedMotion)
        if (target > 0f || scrubGrowthBoundary == null) scrubGrowthBoundary = boundary
        if (scrubGrowthDurationMs > 0) {
            scheduleFrame()
        } else {
            if (scrubGrowthTo == 0f) scrubGrowthBoundary = null
            invalidateTimelineRegion()
        }
    }

    private fun prepareBoundaryPlaybackCursor(radius: Float) {
        if (boundaryPlaybackCursorBitmap != null && boundaryPlaybackCursorRadius == radius) return
        // The cursor first enters the Fine viewport during a crossing. Rasterize its
        // fixed fill and outline before that input, avoiding cold circle shaders there.
        val size = ceil(2f * (radius + 2f * density)).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_PRIMARY }
        canvas.drawCircle(size * .5f, size * .5f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = PRIMARY_PURPLE
        canvas.drawCircle(size * .5f, size * .5f, radius, paint)
        bitmap.prepareToDraw()
        boundaryPlaybackCursorBitmap = bitmap
        boundaryPlaybackCursorRadius = radius
    }

    private fun prepareLoopHandleShadow() {
        val padding = 24f * density
        val width = StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_WIDTH_DP * density
        val height = StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP * density
        val bitmapWidth = ceil(width + padding * 2f).toInt()
        val bitmapHeight = ceil(height + padding * 2f).toInt()
        val previous = loopHandleShadowBitmap
        if (previous?.width == bitmapWidth && previous.height == bitmapHeight) return
        // Rasterize once before dragging. Live hardware blur can compile a shader while
        // the UI thread waits for RenderThread, delaying the next endpoint MOVE.
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(SELECTED_SHADOW, .32f)
            maskFilter = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
        }
        val radius = StemwiseSeekerDesign.LOOP_HANDLE_RADIUS_DP * density
        Canvas(bitmap).drawRoundRect(padding, padding, padding + width, padding + height,
            radius, radius, paint)
        bitmap.prepareToDraw()
        loopHandleShadowBitmap = bitmap
    }

    private fun seekerRailY(): Float {
        val height = (seekerBottom - seekerTop).coerceAtLeast(0f)
        val originalOffset = min(24f * density, max(18f * density, height * .38f))
        return seekerTop + originalOffset
    }

    private fun seekerRailBottomY(railY: Float): Float = railY + 2f * density

    // The ordinary playback thumb belongs on the rail. Only A/B endpoint motors sit below it.
    private fun seekerPlayheadCursorY(railY: Float): Float = railY

    private fun loopEndpointHandleTop(railY: Float, fine: Float): Float = seekerRailBottomY(railY) + lerp(
        StemwiseSeekerDesign.LOOP_GUIDE_NORMAL_HEIGHT_DP,
        StemwiseSeekerDesign.LOOP_GUIDE_FINE_HEIGHT_DP,
        fine
    ) * density

    private fun loopEndpointHandleBottom(handleTop: Float, selectionGrowth: Float): Float =
        handleTop + playerSurfaceLoopHandleHeightDp(selectionGrowth) * density

    private fun loopEndpointTouchTop(handleTop: Float, handleBottom: Float): Float =
        (handleTop + handleBottom) * .5f -
            StemwiseSeekerDesign.LOOP_TOUCH_HEIGHT_DP * density * .5f

    private fun loopEndpointTouchBottom(handleTop: Float, handleBottom: Float): Float =
        (handleTop + handleBottom) * .5f +
            StemwiseSeekerDesign.LOOP_TOUCH_HEIGHT_DP * density * .5f

    private fun timelineX(timeMs: Long, durationMs: Long, centerMs: Long, fine: Float): Float =
        waveformGeometry.timelineX(timeMs, durationMs, centerMs, fine, seekerTrackLeft, seekerTrackRight)

    private fun drawTransport(canvas: Canvas, alpha: Float) {
        val current = profile ?: return
        var index = 0
        while (index < 5) {
            val base = index * 4
            val left = controlGeometry.transport.visual[base]
            val top = controlGeometry.transport.visual[base + 1]
            val right = controlGeometry.transport.visual[base + 2]
            val bottom = controlGeometry.transport.visual[base + 3]
            if (right > left && bottom > top) {
                val cx = (left + right) * .5f
                val cy = (top + bottom) * .5f
                val transientPress = buttonAuraKind == BUTTON_TRANSPORT &&
                    buttonAuraIndex == index && buttonBloomFrame.visible
                val enabled = transportActionEnabled(current.transport, index)
                val active = when (index) {
                    PlayerSurfaceTransportButtons.SHUFFLE -> liveShuffleEnabled
                    PlayerSurfaceTransportButtons.REPEAT -> liveRepeatMode != PlayerSurfaceRepeatMode.OFF
                    else -> false
                }
                if (index == PlayerSurfaceTransportButtons.PLAY) {
                    val playRadius = min(right - left, bottom - top) * .5f *
                        1f
                    val playLeft = cx - playRadius
                    val playTop = cy - playRadius
                    val playRight = cx + playRadius
                    val playBottom = cy + playRadius
                    motionDrawing.drawSelectedBodyShadow(
                        canvas,
                        playLeft,
                        playTop,
                        playRight,
                        playBottom,
                        playRadius,
                        alpha * playerSurfaceSelectedAuraProgress(
                            selectedProgress = 1f)
                    )
                    interactionMatrix.reset()
                    interactionMatrix.setScale(playRight - playLeft, playBottom - playTop)
                    interactionMatrix.postTranslate(playLeft, playTop)
                    selectedFillShader.setLocalMatrix(interactionMatrix)
                    fillPaint.shader = selectedFillShader
                    fillPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
                    canvas.drawCircle(cx, cy, playRadius, fillPaint)
                    fillPaint.shader = null
                    fillPaint.alpha = 255
                    strokePaint.shader = null
                    strokePaint.strokeWidth = 2f * density
                    strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
                    canvas.drawCircle(
                        cx,
                        cy,
                        playRadius - density,
                        strokePaint
                    )
                }
                val icon = when (index) {
                    PlayerSurfaceTransportButtons.SHUFFLE -> PlayerSurfaceIcon.SHUFFLE
                    PlayerSurfaceTransportButtons.PREVIOUS -> PlayerSurfaceIcon.PREVIOUS
                    PlayerSurfaceTransportButtons.PLAY -> if (livePlayWhenReady) {
                        PlayerSurfaceIcon.PAUSE
                    } else {
                        PlayerSurfaceIcon.PLAY
                    }
                    PlayerSurfaceTransportButtons.NEXT -> PlayerSurfaceIcon.NEXT
                    else -> liveRepeatMode.transportIcon()
                }
                val iconAlpha = alpha * if (enabled) 1f else .42f
                val size = playerSurfaceTransportIconSizeDp(index) * density
                val selectedState = transportSelectedStates[index]
                val selectedNowMs = SystemClock.uptimeMillis()
                val selectedProgress = selectedState.sample(
                    targetSelected = index == PlayerSurfaceTransportButtons.PLAY || active,
                    nowMs = selectedNowMs,
                    reducedMotion = !ValueAnimator.areAnimatorsEnabled()
                )
                val selectedAuraProgress = playerSurfaceSelectedAuraProgress(
                    selectedProgress)
                if (selectedState.needsAnotherFrame(selectedNowMs)) {
                    transportChromeDirty = true
                    invalidateTransportRegion()
                }
                val color = if (index == PlayerSurfaceTransportButtons.PLAY) {
                    TEXT_PRIMARY
                } else {
                    blendOpaqueColors(TEXT_PRIMARY, PRIMARY_BRIGHT, selectedProgress * .2f)
                }
                if (selectedAuraProgress > .001f) {
                    motionDrawing.drawSelectedGlyphGlow(
                        canvas,
                        icon,
                        cx,
                        cy,
                        size,
                        alpha * selectedAuraProgress
                    )
                }
                if (!transientPress) {
                    iconAtlas.draw(canvas, icon, cx, cy, size, withAlpha(color, iconAlpha))
                }
            }
            index++
        }
    }

    private fun drawTransportAuraOverlay(canvas: Canvas, alpha: Float) {
        var index = 0
        while (index < 5) {
            val base = index * 4
            val left = controlGeometry.transport.visual[base]
            val top = controlGeometry.transport.visual[base + 1]
            val right = controlGeometry.transport.visual[base + 2]
            val bottom = controlGeometry.transport.visual[base + 3]
            if (right > left && bottom > top) {
                if (
                    buttonAuraKind == BUTTON_TRANSPORT &&
                    buttonAuraIndex == index &&
                    buttonBloomFrame.visible
                ) {
                    val cx = (left + right) * .5f
                    val cy = (top + bottom) * .5f
                    // B is an above-body, glyph-local field. Redraw only the foreground glyph so
                    // the body does not hide the authored bloom and no selected glow is doubled.
                    val icon = when (index) {
                        PlayerSurfaceTransportButtons.SHUFFLE -> PlayerSurfaceIcon.SHUFFLE
                        PlayerSurfaceTransportButtons.PREVIOUS -> PlayerSurfaceIcon.PREVIOUS
                        PlayerSurfaceTransportButtons.PLAY -> if (livePlayWhenReady) {
                            PlayerSurfaceIcon.PAUSE
                        } else {
                            PlayerSurfaceIcon.PLAY
                        }
                        PlayerSurfaceTransportButtons.NEXT -> PlayerSurfaceIcon.NEXT
                        else -> liveRepeatMode.transportIcon()
                    }
                    val active = index == PlayerSurfaceTransportButtons.SHUFFLE && liveShuffleEnabled ||
                        index == PlayerSurfaceTransportButtons.REPEAT && liveRepeatMode != PlayerSurfaceRepeatMode.OFF
                    val selectedState = transportSelectedStates[index]
                    val selectedNowMs = SystemClock.uptimeMillis()
                    val selectedProgress = selectedState.sample(
                        targetSelected = index == PlayerSurfaceTransportButtons.PLAY || active,
                        nowMs = selectedNowMs,
                        reducedMotion = !ValueAnimator.areAnimatorsEnabled()
                    )
                    if (selectedState.needsAnotherFrame(selectedNowMs)) {
                        transportChromeDirty = true
                        invalidateTransportRegion()
                    }
                    motionDrawing.drawNativePressBloom(
                        canvas,
                        buttonBloomFrame,
                        left,
                        top,
                        right,
                        bottom,
                        alpha
                    )
                    val size = playerSurfaceTransportIconSizeDp(index) * density *
                        1f
                    val iconColor = if (index == PlayerSurfaceTransportButtons.PLAY) {
                        TEXT_PRIMARY
                    } else {
                        blendOpaqueColors(TEXT_PRIMARY, PRIMARY_BRIGHT, selectedProgress * .2f)
                    }
                    iconAtlas.draw(
                        canvas,
                        icon,
                        cx,
                        cy,
                        size,
                        withAlpha(iconColor, alpha)
                    )
                }
            }
            index++
        }
    }

    /** Brief highlight with fixed geometry, shared with Compose controls. */
    private fun drawToolRow(canvas: Canvas) {
        if (!controlsValid) return
        val alpha = fullChromeAlpha(expansionProgress)
        if (alpha <= .001f) return
        val activity = fullPlayerState.snapshot?.toolDetail?.activity
        var index = 0
        while (index < TOOL_ORDER.size) {
            val base = index * 4
            val left = controlGeometry.tools.visual[base]
            val top = controlGeometry.tools.visual[base + 1]
            val right = controlGeometry.tools.visual[base + 2]
            val bottom = controlGeometry.tools.visual[base + 3]
            val tool = TOOL_ORDER[index]
            val available = when (tool) {
                PlayerSurfaceTool.LOOP -> activity?.loopAvailable == true
                PlayerSurfaceTool.LYRICS -> activity?.lyricsAvailable == true
                else -> true
            }
            val selected = liveSelectedTool == tool
            val active = when (tool) {
                PlayerSurfaceTool.LOOP -> activity?.loopActive == true
                PlayerSurfaceTool.TUNE -> activity?.tuneActive == true
                PlayerSurfaceTool.LYRICS -> false
                PlayerSurfaceTool.METRONOME -> activity?.let { it.metronomeActive || it.speedActive } == true
                PlayerSurfaceTool.NONE -> false
            }
            val centerX = (left + right) * .5f
            val centerY = (top + bottom) * .5f
            val selectedState = toolSelectedStates[index]
            val selectedNowMs = SystemClock.uptimeMillis()
            val selectedProgress = selectedState.sample(
                targetSelected = selected || active,
                nowMs = selectedNowMs,
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            )
            val selectedAuraProgress = playerSurfaceSelectedAuraProgress(
                selectedProgress)
            if (selectedState.needsAnotherFrame(selectedNowMs)) invalidateToolRegion()

            if (available) {
                motionDrawing.drawNativePressBloom(
                    canvas,
                    toolActions[index].pressBloomFrame,
                    left,
                    top,
                    right,
                    bottom,
                    alpha
                )
            }
            if (selectedAuraProgress > .001f) {
                motionDrawing.drawSelectedGlyphGlow(
                    canvas,
                    TOOL_ICONS[index],
                    centerX,
                    centerY,
                    PLAYER_SURFACE_TOOL_ICON_SIZE_DP * density,
                    alpha * selectedAuraProgress
                )
            }
            iconAtlas.draw(
                canvas,
                TOOL_ICONS[index],
                centerX,
                centerY,
                PLAYER_SURFACE_TOOL_ICON_SIZE_DP * density,
                withAlpha(
                    blendOpaqueColors(TEXT_SECONDARY, PRIMARY_BRIGHT, selectedProgress * .2f),
                    alpha * lerp(.78f, 1f, selectedProgress * .2f)
                )
            )
            if (expandedArtworkSizePx <= .01f) {
                index++
                continue
            }
            val inlineLabel = playerToolColumnCount(fontScale) == 2
            textPaint.textAlign = if (inlineLabel) Paint.Align.LEFT else Paint.Align.CENTER
            textPaint.typeface = if (selected || active) {
                systemSemiboldTypeface
            } else {
                systemMediumTypeface
            }
            textPaint.textSize = scaledTextSizePx(11.5f)
            textPaint.color = withAlpha(
                blendOpaqueColors(TEXT_SECONDARY, PRIMARY_BRIGHT, selectedProgress * .2f),
                alpha
            )
            val labelBaseline = if (inlineLabel) playerSurfaceCenteredTextBaselineY(
                centerY, textPaint.ascent(), textPaint.descent()
            ) else playerSurfaceBottomSafeTextBaselineY(
                authoredBaselineY = centerY + playerToolLabelBaselineOffsetDp(fontScale) * density,
                bottomY = controlGeometry.tools.hit[base + 3],
                descent = textPaint.descent(),
                bottomGapPx = 4f * density
            )
            canvas.drawText(
                toolDisplayLabels[index],
                if (inlineLabel) right + 2f * density else centerX,
                labelBaseline,
                textPaint
            )
            index++
        }
    }

    private fun drawMiniPlay(canvas: Canvas) = chromeDrawing.drawMiniPlay(canvas, routeGeometry,
        PlayerMotionPolicy.miniChromeAlpha(expansionProgress), livePlayWhenReady,
        buttonBloomFrame.takeIf { buttonAuraKind == BUTTON_MINI_PLAY && it.visible })

    private fun rebuildWaveformGeometry() = waveformGeometry.rebuild(
        controlsValid, seekerTrackLeft, seekerTrackRight, seekerTop, seekerBottom)

    private fun updateFineWaveformGeometry(centerPositionMs: Long, durationMs: Long) {
        waveformGeometry.updateVerticalBounds(seekerTop, seekerBottom)
        waveformGeometry.update(centerPositionMs, durationMs, fullPlayerState.snapshot)
    }

    private fun renderedPositionMs(current: PlayerSurfaceProfilePacket): Long {
        val duration = current.timeline.durationMs
        return if (seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID &&
            (seekerGestures.scrubDragging || seekerGestures.scrubSessionStarted) && seekerGestures.scrubLoopBoundary == null
        ) {
            seekerGestures.scrubPreviewPositionMs.coerceIn(0L, duration)
        } else {
            (positionProvider?.currentPositionMs() ?: current.timeline.positionMs)
                .coerceIn(0L, duration)
        }
    }

    private fun scheduleFrame(delayMs: Long = 0L) {
        if (!isAttachedToWindow || !aggregatedVisible) return
        if (frameScheduled) {
            if (delayMs != 0L || scheduledFrameDelayMs == 0L) return
            // Input and new state preempt an ambient delayed frame immediately.
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        frameScheduled = true
        scheduledFrameDelayMs = delayMs
        if (delayMs == 0L) Choreographer.getInstance().postFrameCallback(frameCallback)
        else Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMs)
    }

    private fun advanceToolMotion(frameTimeNanos: Long) {
        if (!toolMotion.advance(frameTimeNanos)) return
        val rawFraction = toolMotion.rawFraction
        val easedFraction = if (sourceMotionActive) {
            sourceWorkspaceProgress = lerp(sourceMotionStartProgress, sourceMotionTargetProgress,
                rawFraction)
            artworkAlpha = PlayerMotionPolicy.artworkAlpha(expansionProgress, sourceWorkspaceProgress)
            val startLayout = PlayerMotionPolicy.sourceLayoutProgress(sourceMotionStartProgress)
            val targetLayout = PlayerMotionPolicy.sourceLayoutProgress(sourceMotionTargetProgress)
            val layout = PlayerMotionPolicy.sourceLayoutProgress(sourceWorkspaceProgress)
            val fraction = if (abs(targetLayout - startLayout) > .001f) {
                ((layout - startLayout) / (targetLayout - startLayout)).coerceIn(0f, 1f)
            } else rawFraction
            sourceMotionSeekerTop = lerp(sourceMotionStartSeekerTop, sourceMotionTargetSeekerTop, fraction)
            invalidatePagerRegions()
            invalidateMixerRegion()
            fraction
        } else SEMANTIC_EMPHASIZED_EASING.transform(rawFraction)
        val panelContentEasedFraction = toolMotion.lastToolMotionPanelContentEasedFraction
        val artworkSize = lerp(
            toolMotionStartArtworkSizePx,
            toolMotionTargetArtworkSizePx,
            easedFraction
        )
        val mixerCardHeight = lerp(
            if (sourceMotionActive && toolMotionTargetMixerCardHeightPx > .01f) {
                min(toolMotionTargetMixerCardHeightPx, app.stemwise.ui.STEMS_ROW_HEIGHT_DP * density)
            } else toolMotionStartMixerCardHeightPx,
            toolMotionTargetMixerCardHeightPx,
            easedFraction
        )
        val mixerSpacing = lerp(
            toolMotionStartMixerSpacingPx,
            toolMotionTargetMixerSpacingPx,
            easedFraction
        )
        val left = lerp(toolMotionStartControlLeft, toolMotionTargetControlLeft, easedFraction)
        val top = lerp(toolMotionStartControlTop, toolMotionTargetControlTop, easedFraction)
        val right = lerp(toolMotionStartControlRight, toolMotionTargetControlRight, easedFraction)
        val bottom = lerp(
            toolMotionStartControlBottom,
            toolMotionTargetControlBottom,
            easedFraction
        )
        if (rigidToolControlMotion) {
            applyToolMotionArtworkFrame(toolMotion.toolMotionGeneration, artworkSize)
            applyRigidToolControlTranslation(top - controlTop)
        } else {
            applyToolMotionGeometryFrame(
                generation = toolMotion.toolMotionGeneration,
                expandedArtworkSizePx = artworkSize,
                mixerCardHeightPx = mixerCardHeight,
                mixerSpacingPx = mixerSpacing,
                listSpacingPx = lerp(toolMotionStartListSpacingPx, toolMotionTargetListSpacingPx, easedFraction),
                seekerTrackHeightPx = lerp(toolMotionStartSeekerTrackHeightPx, toolMotionTargetSeekerTrackHeightPx, easedFraction),
                transportHeightPx = lerp(toolMotionStartTransportHeightPx, toolMotionTargetTransportHeightPx, easedFraction),
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
        }
        renderedToolDetailHeightPx = lerp(
            toolMotionStartDetailHeightPx,
            toolMotionTargetDetailHeightPx,
            easedFraction
        )
        val rigidEndpoint = rawFraction >= 1f && rigidToolControlMotion
        if (rigidEndpoint) {
            // Lerp(1f) is not guaranteed to reproduce the target's exact Float bits. Drive the
            // artwork to the exact target before settling the control geometry.
            applyToolMotionArtworkFrame(toolMotion.toolMotionGeneration, toolMotionTargetArtworkSizePx)
            clearRigidToolControlTranslation()
            rigidToolControlMotion = false
            applyToolMotionGeometryFrame(
                generation = toolMotion.toolMotionGeneration,
                expandedArtworkSizePx = toolMotionTargetArtworkSizePx,
                mixerCardHeightPx = toolMotionTargetMixerCardHeightPx,
                mixerSpacingPx = toolMotionTargetMixerSpacingPx,
                left = toolMotionTargetControlLeft,
                top = toolMotionTargetControlTop,
                right = toolMotionTargetControlRight,
                bottom = toolMotionTargetControlBottom
            )
            renderedToolDetailHeightPx = toolMotionTargetDetailHeightPx
        }
        toolMotionListener?.onToolMotionFrame(
            generation = toolMotion.toolMotionGeneration,
            selectionEpoch = toolMotion.toolMotionEpoch,
            panelContentEasedFraction = panelContentEasedFraction,
            currentControlTopPx = if (rigidToolControlMotion) top else controlTop,
            currentControlBottomPx = if (rigidToolControlMotion) bottom else controlBottom
        )
        if (rawFraction >= 1f && panelContentEasedFraction >= 1f) {
            if (sourceMotionActive) {
                sourceMotionActive = false
                sourceMotionSeekerTop = Float.NaN
                outgoingMixerPicture = null
                rebuildGeometry()
                relayoutControlRegions()
                invalidateMixerRegion()
            }
            toolMotion.finishClock()
            publishToolMotionSettled(toolMotion.toolMotionGeneration, toolMotion.toolMotionEpoch)
        }
    }

    private fun currentWaveformMorph(nowNanos: Long): Float {
        if (!waveformMorphActive || waveformMorphStartedAtNanos <= 0L) return waveformMorph
        return playerSurfaceFineWaveformMorphValue(
            start = waveformMorphStart,
            target = waveformMorphTarget,
            elapsedMs = ((nowNanos - waveformMorphStartedAtNanos).coerceAtLeast(0L) /
                1_000_000L),
            reducedMotion = false
        )
    }

    private fun currentLoopClearanceProgress(nowNanos: Long): Float {
        if (!loopClearanceMotionActive || loopClearanceStartedAtNanos <= 0L) {
            return loopClearanceProgress
        }
        return playerSurfaceLoopClearanceProgress(
            start = loopClearanceStart,
            target = loopClearanceTarget,
            elapsedMs = ((nowNanos - loopClearanceStartedAtNanos).coerceAtLeast(0L) /
                1_000_000L),
            reducedMotion = false
        )
    }

    private fun retargetLoopClearance(present: Boolean, snap: Boolean) {
        val target = if (present) 1f else 0f
        if (!snap && target == loopClearanceTarget) return
        val nowNanos = System.nanoTime()
        loopClearanceProgress = currentLoopClearanceProgress(nowNanos)
        loopClearanceStart = loopClearanceProgress
        loopClearanceTarget = target
        if (
            snap || !ValueAnimator.areAnimatorsEnabled() ||
            abs(loopClearanceProgress - target) <= .001f
        ) {
            loopClearanceProgress = target
            loopClearanceStart = target
            loopClearanceStartedAtNanos = 0L
            loopClearanceMotionActive = false
            if (target == 0f) {
                visibleLoopStartMs = -1L
                visibleLoopEndMs = -1L
            }
        } else {
            loopClearanceStartedAtNanos = nowNanos
            loopClearanceMotionActive = true
            scheduleFrame()
        }
    }

    private fun animateWaveformMorphTo(target: Float) {
        val resolvedTarget = target.coerceIn(0f, 1f)
        val nowNanos = System.nanoTime()
        waveformMorph = currentWaveformMorph(nowNanos)
        waveformMorphStart = waveformMorph
        waveformMorphTarget = resolvedTarget
        if (!ValueAnimator.areAnimatorsEnabled() || abs(waveformMorph - resolvedTarget) <= .001f) {
            waveformMorph = resolvedTarget
            waveformMorphStart = resolvedTarget
            waveformMorphStartedAtNanos = 0L
            waveformMorphActive = false
            if (resolvedTarget == 0f) clearFineReleaseVisual()
        } else {
            waveformMorphStartedAtNanos = nowNanos
            waveformMorphActive = true
            scheduleFrame()
        }
        invalidateTimelineRegion()
    }

    private fun hardResetWaveformMorph() {
        waveformMorph = 0f
        waveformMorphStart = 0f
        waveformMorphTarget = 0f
        waveformMorphStartedAtNanos = 0L
        waveformMorphActive = false
        clearFineReleaseVisual()
    }

    private fun clearFineReleaseVisual() {
        scrubFineReleaseActive = false
        scrubFineReleaseBoundary = null
        // Animation cleanup may run during a live A/B drag, whose accepted range still owns input.
        if (seekerGestures.scrubPointerId == MotionEvent.INVALID_POINTER_ID) {
            seekerGestures.scrubPreviewLoopStartMs = -1L
            seekerGestures.scrubPreviewLoopEndMs = -1L
        }
    }

    private fun refreshLowerStackGeometry() {
        if (!controlsValid) return
        rebuildControlGeometry()
        relayoutControlRegions()
        transportChromeDirty = true
        invalidateTransportRegion()
        invalidateToolRegion()
    }

    private fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        scheduledFrameDelayMs = 0L
        advanceMixerFling()
        seekerGestures.scrubLoopBoundary?.let(seekerGestures::retryPendingLoopBoundaryMove)
        mixerGestures.stageMixerPreviewsForPostFrameDrain()
        advanceToolMotion(frameTimeNanos)
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        val deltaSeconds = if (previous == 0L) {
            0f
        } else {
            ((frameTimeNanos - previous).coerceIn(0L, 50_000_000L) / 1_000_000_000.0)
                .toFloat()
        }
        var artworkArrivalActive = false
        var artworkArrivalChanged = false
        var artworkSlot = 0
        while (artworkSlot < artworkLoadTransitionActive.size) {
            if (artworkLoadTransitionActive[artworkSlot]) {
                artworkArrivalChanged = true
                val raw = ((frameTimeNanos - artworkLoadStartedAtNanos[artworkSlot])
                    .coerceAtLeast(0L).toDouble() /
                    (StemwiseSemanticMotion.ARTWORK_LOAD_MS * 1_000_000.0))
                    .toFloat().coerceIn(0f, 1f)
                artworkLoadProgress[artworkSlot] = SEMANTIC_EMPHASIZED_EASING.transform(raw)
                if (raw >= 1f) {
                    artworkLoadProgress[artworkSlot] = 1f
                    artworkLoadStartedAtNanos[artworkSlot] = 0L
                    artworkLoadTransitionActive[artworkSlot] = false
                } else {
                    artworkArrivalActive = true
                }
            }
            artworkSlot++
        }
        if (artworkArrivalChanged) {
            invalidatePagerAuraRegion()
            invalidatePagerArtworkRegion()
        }
        val ambientArtworkActive = artworkAuraMotion.update(frameTimeNanos, previous,
            playerSurfaceMediaContentVisible(artworkAlpha), ambientMotionPolicySignal, visuals?.catalog?.isNotEmpty() == true)
        var pagerChanged = false
        var timelineChanged = false
        var lowerStackChanged = false

        if (pagerAnimating && deltaSeconds > 0f) {
            val acceleration = -PAGER_SPRING_STIFFNESS * pagerOffsetFraction -
                PAGER_SPRING_DAMPING * pagerVelocityFraction
            pagerVelocityFraction += acceleration * deltaSeconds
            pagerOffsetFraction += pagerVelocityFraction * deltaSeconds
            if (abs(pagerOffsetFraction) < .001f && abs(pagerVelocityFraction) < .008f) {
                pagerOffsetFraction = 0f
                pagerVelocityFraction = 0f
                pagerAnimating = false
            }
            pagerChanged = true
        }

        if (waveformMorphActive) {
            waveformMorph = currentWaveformMorph(frameTimeNanos)
            val elapsedNanos =
                (frameTimeNanos - waveformMorphStartedAtNanos).coerceAtLeast(0L)
            if (elapsedNanos >= FINE_WAVEFORM_MORPH_DURATION_MS * 1_000_000L) {
                waveformMorph = waveformMorphTarget
                waveformMorphStart = waveformMorphTarget
                waveformMorphStartedAtNanos = 0L
                waveformMorphActive = false
                if (waveformMorphTarget == 0f) clearFineReleaseVisual()
            }
            timelineChanged = true
        }

        if (loopClearanceMotionActive) {
            loopClearanceProgress = currentLoopClearanceProgress(frameTimeNanos)
            val elapsedNanos =
                (frameTimeNanos - loopClearanceStartedAtNanos).coerceAtLeast(0L)
            if (elapsedNanos >= LOOP_CLEARANCE_MOTION_DURATION_MS * 1_000_000L) {
                loopClearanceProgress = loopClearanceTarget
                loopClearanceStart = loopClearanceTarget
                loopClearanceStartedAtNanos = 0L
                loopClearanceMotionActive = false
                if (loopClearanceTarget == 0f) {
                    visibleLoopStartMs = -1L
                    visibleLoopEndMs = -1L
                }
            }
            lowerStackChanged = true
        }

        if (lowerStackChanged) refreshLowerStackGeometry()

        val nowMs = frameTimeNanos / 1_000_000L
        val auraActive = sampleAuraFrames(nowMs)
        val current = profile
        if (current != null) {
            val position = renderedPositionMs(current)
            if (position != lastRenderedPositionMs) {
                lastRenderedPositionMs = position
                val miniFrameDue = lastMiniPositionFrameNanos == Long.MIN_VALUE ||
                    frameTimeNanos - lastMiniPositionFrameNanos >=
                    PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS
                val invalidation = playerSurfacePositionInvalidation(
                    expansionProgress,
                    miniFrameDue
                )
                if (invalidation.mini) {
                    lastMiniPositionFrameNanos = frameTimeNanos
                    invalidateMiniBodyRegion()
                }
                timelineChanged = invalidation.timeline
            }
        }
        if (pagerChanged) {
            invalidatePagerRegions()
        } else if (identityMarqueeActive && expansionProgress > 0f) {
            invalidatePagerIdentityRegion()
        }
        if (ambientArtworkActive) {
            invalidatePagerAuraRegion()
        }
        if (timelineChanged) invalidateTimelineRegion()
        val interactiveMotion = pagerAnimating ||
            waveformMorphActive || loopClearanceMotionActive || toolMotion.toolMotionActive || !mixerScroller.isFinished ||
            mixerGestures.mixerPendingPreviewMask != 0 || auraActive ||
            artworkArrivalActive || (identityMarqueeActive && expansionProgress > 0f)
        if (livePlayWhenReady || interactiveMotion) {
            scheduleFrame(playerSurfacePassiveFrameDelayMs(
                expansionProgress, interactiveMotion, display?.refreshRate ?: 60f,
                if (lastMiniPositionFrameNanos == Long.MIN_VALUE) 0L else
                    (frameTimeNanos - lastMiniPositionFrameNanos).coerceAtLeast(0L) / 1_000_000L))
        } else if (artworkAuraMotion.motionEnabled) {
            val remainingNanos = PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS -
                frameTimeNanos % PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS
            scheduleFrame((remainingNanos / 1_000_000L).coerceAtLeast(1L))
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopMixerFling()
            mixerVelocityTracker?.recycle()
            mixerVelocityTracker = null
        }
        mixerVelocityTracker?.addMovement(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (profile == null || visuals == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                return handlePointerDown(event, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, event.actionIndex)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updatePointers(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(event, event.actionIndex, cancelled = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                handlePointerUp(event, event.actionIndex, cancelled = false)
                mixerVelocityTracker?.recycle()
                mixerVelocityTracker = null
                releaseParentInterceptionIfIdle()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAllGestures(sealMixer = false)
                return true
            }
        }
        return true
    }

    private var lyricsOpenPointerId = MotionEvent.INVALID_POINTER_ID
    private var lyricsOpenDownX = 0f
    private var lyricsOpenDownY = 0f
    private val lyricsVisibleBounds = Rect()

    private fun handlePointerDown(event: MotionEvent, pointerIndex: Int): Boolean {
        val generation = profile?.owner?.generation ?: return false
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (seekerOnly) {
            if (seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID) return false
            val boundary = loopBoundaryGestureTargetAt(x, y) ?: return false
            return seekerGestures.beginScrubPointer(pointerId, x, y, event.eventTime, boundary)
                .also { if (it) requestUnbufferedDispatch(event) }
        }
        var controlY = controlSpaceY(y)
        val headerY = y
        val bodyInputVisible = playerSurfaceBodyInputVisible(
            pointerY = y,
            headerHeightPx = PLAYER_SURFACE_HEADER_HEIGHT_DP * density
        )
        if (pointerOwned(pointerId)) return true
        if (doubleTapSeekDeltaAt(x, y) == 0L) clearPendingArtworkTap()

        if (PlayerMotionPolicy.destinationChromeInteractive(expansionProgress) && controlsValid) {
            if (liveSourceMode == PlayerSurfaceSourceMode.STEMS && (profile?.mixer?.laneCount ?: 0) > 0 &&
                bodyInputVisible && !sourceMotionPending && !sourceMotionActive &&
                contains(x, controlY, mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)
            ) {
                val actualLaneCount = profile?.mixer?.laneCount ?: 0
                val hitLane = hitBounds(controlGeometry.cards, actualLaneCount, x, controlY)
                val lane = if (hitLane >= 0) hitLane else
                    ((controlY - mixerGridTop + mixerScrollOffsetPx) /
                        (mixerCardHeightPx + mixerSpacingPx)).toInt().coerceIn(0, actualLaneCount - 1)
                if (lane >= 0) {
                    if (materializeRigidToolControlTransformForInput()) controlY = y
                    val target = when {
                        containsBounds(controlGeometry.mute.hit, lane, x, controlY) -> BUTTON_MIXER_MUTE
                        containsBounds(controlGeometry.solo.hit, lane, x, controlY) -> BUTTON_MIXER_SOLO
                        containsBounds(controlGeometry.sliders, lane, x, controlY) -> BUTTON_NONE
                        else -> MIXER_LIST_SCROLL_ONLY
                    }
                    if (target != MIXER_LIST_SCROLL_ONLY) clearPendingArtworkTap()
                    val current = profile ?: return false
                    mixerListTouches.put(pointerId, MixerListTouch(current.owner, current.mixer.packageRevision,
                        lane, target, x, y, y))
                    if (mixerVelocityTracker == null) mixerVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    if (target == BUTTON_NONE) {
                        invalidateMixerRegionFromInput()
                        scheduleFrame()
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            val muteLane = if (bodyInputVisible && !sourceMotionPending && !sourceMotionActive &&
                contains(x, controlY, mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)) {
                hitBounds(
                    controlGeometry.mute.hit,
                    profile?.mixer?.laneCount ?: 0,
                    x,
                    controlY
                )
            } else {
                -1
            }
            if (muteLane >= 0) {
                if (materializeRigidToolControlTransformForInput()) controlY = y
                if (beginButton(pointerId, BUTTON_MIXER_MUTE, muteLane, x, controlY)) return true
            }
            val soloLane = if (bodyInputVisible && !sourceMotionPending && !sourceMotionActive &&
                contains(x, controlY, mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)) {
                hitBounds(
                    controlGeometry.solo.hit,
                    profile?.mixer?.laneCount ?: 0,
                    x,
                    controlY
                )
            } else {
                -1
            }
            if (soloLane >= 0) {
                if (materializeRigidToolControlTransformForInput()) controlY = y
                if (beginButton(pointerId, BUTTON_MIXER_SOLO, soloLane, x, controlY)) return true
            }
            // Toggle bounds intentionally overlap the forgiving lower slider lane on compact
            // cards; explicit buttons win, while every remaining pixel still adjusts gain.
            val lane = if (bodyInputVisible) hitMixerSlider(x, controlY) else -1
            if (lane >= 0) {
                if (materializeRigidToolControlTransformForInput()) controlY = y
                if (mixerGestures.beginMixerPointer(generation, pointerId, lane, x)) return true
            }
            val seekerVisible = seekerChromeAlpha(expansionProgress) > .001f
            val acquiredLoopBoundary = if (bodyInputVisible && seekerVisible) {
                loopBoundaryGestureTargetAt(x, controlY)
            } else {
                null
            }
            if (!externalTransport && seekerVisible && seekerGestures.scrubPointerId == MotionEvent.INVALID_POINTER_ID && bodyInputVisible &&
                pointerCanBeginScrub(x, controlY, acquiredLoopBoundary)
            ) {
                if (materializeRigidToolControlTransformForInput()) controlY = y
                val accepted = seekerGestures.beginScrubPointer(
                    pointerId,
                    x,
                    controlY,
                    event.eventTime,
                    acquiredLoopBoundary
                )
                if (accepted && acquiredLoopBoundary != null) requestUnbufferedDispatch(event)
                return true
            }
            if (contains(
                    x,
                    headerY,
                    routeGeometry.sourceOriginalHitBounds.left,
                    routeGeometry.sourceOriginalHitBounds.top,
                    routeGeometry.sourceOriginalHitBounds.right,
                    routeGeometry.sourceOriginalHitBounds.bottom
                ) && beginButton(pointerId, BUTTON_SOURCE_ORIGINAL, 0, x, y)
            ) return true
            if (contains(
                    x,
                    headerY,
                    routeGeometry.sourceStemsHitBounds.left,
                    routeGeometry.sourceStemsHitBounds.top,
                    routeGeometry.sourceStemsHitBounds.right,
                    routeGeometry.sourceStemsHitBounds.bottom
                ) && profile?.source?.let(::playerSurfaceStemsActionEnabled) == true &&
                beginButton(pointerId, BUTTON_SOURCE_STEMS, 0, x, y)
            ) return true
            val transport = if (bodyInputVisible && !externalTransport) {
                hitBounds(controlGeometry.transport.hit, 5, x, controlY)
            } else {
                -1
            }
            if (transport >= 0 &&
                profile?.transport?.let { transportActionEnabled(it, transport) } == true
            ) {
                if (materializeRigidToolControlTransformForInput()) controlY = y
                if (beginButton(
                    pointerId,
                    BUTTON_TRANSPORT,
                    transport,
                    x,
                    y
                )) {
                    beginTransportPagerPreview(generation, transport)
                    return true
                }
            }
        }

        // Controls above have first refusal; the remaining full-width bottom strip opens lyrics.
        if (PlayerMotionPolicy.destinationChromeInteractive(expansionProgress) &&
            fullPlayerState.snapshot?.toolDetail?.activity?.lyricsAvailable == true &&
            lyricsOpenPointerId == MotionEvent.INVALID_POINTER_ID &&
            getLocalVisibleRect(lyricsVisibleBounds) &&
            x in lyricsVisibleBounds.left.toFloat()..lyricsVisibleBounds.right.toFloat() &&
            y in (lyricsVisibleBounds.bottom - 96f * density)..lyricsVisibleBounds.bottom.toFloat()
        ) {
            lyricsOpenPointerId = pointerId
            lyricsOpenDownX = x
            lyricsOpenDownY = y
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        if (PlayerMotionPolicy.miniChromeInteractive(expansionProgress) &&
            contains(
                x,
                y,
                routeGeometry.miniPlayHitBounds.left,
                routeGeometry.miniPlayHitBounds.top,
                routeGeometry.miniPlayHitBounds.right,
                routeGeometry.miniPlayHitBounds.bottom
            ) &&
            beginButton(pointerId, BUTTON_MINI_PLAY, 0, x, y)
        ) return true

        val pagerHit = contains(x, y, routeGeometry.pagerLeft, routeGeometry.pagerTop, routeGeometry.pagerRight, routeGeometry.pagerBottom)
        if (
            playerSurfacePagerInputAdmitted(expansionProgress, bodyInputVisible, pagerHit,
                backgroundSeekAllowed = liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
                    doubleTapSeekDeltaAt(x, y) != 0L) &&
            pagerGestures.pointerId == MotionEvent.INVALID_POINTER_ID
        ) {
            pagerGestures.begin(pointerId, x, y, event.eventTime, pagerOffsetFraction,
                tapOnly = liveSourceMode == PlayerSurfaceSourceMode.STEMS || !pagerHit)
            pagerAnimating = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    /**
     * The 56x96 A/B acquisition field must be admitted before the ordinary seeker rectangle.
     * This is deliberately narrower than widening the whole scrub gate below the rail: only a
     * real endpoint motor can own pixels outside the normal seeker band.
     */
    private fun pointerCanBeginScrub(
        x: Float,
        y: Float,
        acquiredLoopBoundary: FullPlayerLoopBoundary?
    ): Boolean = playerSurfaceScrubPointerAdmitted(
        pointerX = x,
        pointerY = y,
        acquiredLoopBoundary = acquiredLoopBoundary,
        seekerLeft = controlLeft,
        seekerTop = seekerTop,
        seekerRight = controlRight,
        seekerBottom = seekerBottom
    )

    private fun loopBoundaryGestureTargetAt(
        x: Float,
        y: Float
    ): FullPlayerLoopBoundary? {
        val current = profile ?: return null
        val duration = current.timeline.durationMs
        // Hit-test the exact retained pixels. During Fine exit the visible A/B motors still use
        // the preview center/range; coarse authoritative coordinates would miss that motor and
        // incorrectly admit the same DOWN as an ordinary playback scrub.
        val releaseActive = scrubFineReleaseActive
        val releaseBoundary = scrubFineReleaseBoundary.takeIf { releaseActive }
        val nowMs = SystemClock.uptimeMillis()
        val retainedBoundary = releaseBoundary ?: scrubGrowthBoundary?.takeIf {
            currentScrubGrowth(nowMs) > 0f
        }
        val start = if (releaseBoundary != null) {
            seekerGestures.scrubPreviewLoopStartMs
        } else {
            current.timeline.loopStartMs
        }
        if (duration <= 0L || start < 0L) return null
        val end = if (releaseBoundary != null) {
            seekerGestures.scrubPreviewLoopEndMs
        } else {
            current.timeline.loopEndMs
        }
        val position = playerSurfaceFineTimelineCenterMs(
            releaseActive,
            seekerGestures.scrubPreviewPositionMs,
            renderedPositionMs(current)
        )
        val fine = playerSurfaceFineLayerFraction(
            waveformMorph = waveformMorph,
            pointerActive = false,
            scrubSessionStarted = false,
            releaseAnimating = releaseActive
        )
        val candidate = playerSurfaceLoopBoundaryHit(
            pointerX = x,
            startX = loopBoundaryHitX(start, duration, position, fine),
            endX = if (end > start) {
                loopBoundaryHitX(end, duration, position, fine)
            } else {
                Float.NaN
            },
            radiusPx = PLAYER_SURFACE_LOOP_MOTOR_HIT_RADIUS_DP * density,
            preferredBoundary = retainedBoundary
        )
        if (candidate == null) return null
        val handleTop = loopEndpointHandleTop(seekerRailY(), fine)
        val handleBottom = loopEndpointHandleBottom(
            handleTop,
            playerSurfaceLoopHandleGrowth(candidate, retainedBoundary, currentScrubGrowth(nowMs))
        )
        return playerSurfaceLoopBoundaryGestureTarget(
            candidate,
            pointerY = y,
            endpointHandleTop = loopEndpointTouchTop(handleTop, handleBottom),
            endpointHandleBottom = loopEndpointTouchBottom(handleTop, handleBottom),
            playheadExclusiveBottom = seekerRailY() +
                StemwiseSeekerDesign.PLAYHEAD_TOUCH_BELOW_RAIL_DP * density
        )
    }

    private fun loopBoundaryTransitionX(time: Long, duration: Long, position: Long, fine: Float): Float =
        playerSurfaceLoopBoundaryTransitionX(
            timelineX(time, duration, position, 0f), timelineX(time, duration, position, 1f),
            seekerTrackLeft, seekerTrackRight, fine
        )

    private fun loopBoundaryHitX(time: Long, duration: Long, position: Long, fine: Float): Float {
        val alpha = playerSurfaceLoopBoundaryTransitionAlpha(
            timelineX(time, duration, position, 1f), seekerTrackLeft, seekerTrackRight, fine
        )
        return if (alpha > .001f) loopBoundaryTransitionX(time, duration, position, fine) else Float.NaN
    }

    private fun updatePointers(event: MotionEvent) {
        updateMixerListTouches(event)
        transferPlayHorizontalDragToPager(event)
        if (pagerGestures.pointerId != MotionEvent.INVALID_POINTER_ID) {
            val index = event.findPointerIndex(pagerGestures.pointerId)
            if (index >= 0) updatePagerPointer(event, index)
        }
        if (seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID) {
            val pointerId = seekerGestures.scrubPointerId
            val index = event.findPointerIndex(pointerId)
            if (index >= 0) {
                val boundary = seekerGestures.scrubLoopBoundary
                val initialLoopStartMs = seekerGestures.scrubPreviewLoopStartMs
                val initialLoopEndMs = seekerGestures.scrubPreviewLoopEndMs
                val foldedTransportMs = profile?.let(::renderedPositionMs) ?: 0L
                var foldedBArmed = boundary == FullPlayerLoopBoundary.END &&
                    foldedTransportMs in initialLoopStartMs..initialLoopEndMs
                var foldedCapturedBMs = seekerGestures.scrubPendingLoopMove?.capturedBMs ?: -1L
                var foldedCaptureInputAtMicros =
                    seekerGestures.scrubPendingLoopMove?.inputEventAtMonotonicMicros ?: 0L
                var historyIndex = 0
                while (historyIndex < event.historySize && seekerGestures.scrubPointerId == pointerId) {
                    seekerGestures.updateScrubPointer(
                        event.getHistoricalX(index, historyIndex),
                        controlSpaceY(event.getHistoricalY(index, historyIndex)),
                        event.getHistoricalEventTime(historyIndex),
                        publishLoopBoundaryMove = boundary == null
                    )
                    if (boundary == FullPlayerLoopBoundary.END && foldedCapturedBMs < 0L) {
                        if (foldedBArmed && foldedTransportMs >= seekerGestures.scrubPreviewLoopEndMs) {
                            foldedCapturedBMs = seekerGestures.scrubPreviewLoopEndMs
                            foldedCaptureInputAtMicros =
                                event.getHistoricalEventTime(historyIndex) * 1_000L
                        } else if (foldedTransportMs in
                            seekerGestures.scrubPreviewLoopStartMs..seekerGestures.scrubPreviewLoopEndMs
                        ) {
                            foldedBArmed = true
                        }
                    }
                    historyIndex++
                }
                if (seekerGestures.scrubPointerId == pointerId) {
                    seekerGestures.updateScrubPointer(
                        event.getX(index),
                        controlSpaceY(event.getY(index)),
                        event.eventTime,
                        publishLoopBoundaryMove = boundary == null
                    )
                    if (boundary == FullPlayerLoopBoundary.END && foldedCapturedBMs < 0L) {
                        if (foldedBArmed && foldedTransportMs >= seekerGestures.scrubPreviewLoopEndMs) {
                            foldedCapturedBMs = seekerGestures.scrubPreviewLoopEndMs
                            foldedCaptureInputAtMicros = event.eventTime * 1_000L
                        } else if (foldedTransportMs in
                            seekerGestures.scrubPreviewLoopStartMs..seekerGestures.scrubPreviewLoopEndMs
                        ) {
                            foldedBArmed = true
                        }
                    }
                }
                // Android may batch several physical samples into one MOVE. Fold their geometry
                // locally, then publish only the newest A/B value so stale JNI calls cannot delay
                // the boundary that is actually under the finger.
                if (boundary != null && seekerGestures.scrubPointerId == pointerId &&
                    playerSurfaceShouldPublishFoldedLoopBoundary(
                        initialLoopStartMs,
                        initialLoopEndMs,
                        seekerGestures.scrubPreviewLoopStartMs,
                        seekerGestures.scrubPreviewLoopEndMs,
                        foldedCapturedBMs
                    )
                ) {
                    // The DOWN lease owns the complete gesture. A transient engine rejection must
                    // not discard the pointer; the next MOVE retries with the newest folded value.
                    seekerGestures.moveLoopBoundary(
                        boundary,
                        seekerGestures.scrubPreviewLoopStartMs,
                        seekerGestures.scrubPreviewLoopEndMs,
                        foldedTransportMs,
                        if (foldedCapturedBMs >= 0L) {
                            foldedCaptureInputAtMicros
                        } else {
                            event.eventTime * 1_000L
                        },
                        foldedCapturedBMs
                    )
                }
            }
        }
        var lane = 0
        while (lane < mixerGestures.mixerPointerIds.size) {
            val pointerId = mixerGestures.mixerPointerIds[lane]
            if (pointerId != MotionEvent.INVALID_POINTER_ID) {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    mixerGestures.updateMixerPointer(lane, event.getX(index))
                }
            }
            lane++
        }
    }

    /** Play remains a tap, but a horizontal move begun on either play target belongs to the pager. */
    private fun transferPlayHorizontalDragToPager(event: MotionEvent) {
        if (!buttonOwnsTransferablePlay() || buttonPointerId == MotionEvent.INVALID_POINTER_ID ||
            pagerGestures.pointerId != MotionEvent.INVALID_POINTER_ID
        ) return
        val index = event.findPointerIndex(buttonPointerId)
        if (index < 0) return
        transferPlayHorizontalDragToPager(
            pointerId = buttonPointerId,
            x = event.getX(index),
            y = event.getY(index),
            eventTimeMs = event.eventTime
        )
    }

    private fun transferPlayHorizontalDragToPager(
        pointerId: Int,
        x: Float,
        y: Float,
        eventTimeMs: Long
    ) {
        if (
            pointerId != buttonPointerId ||
            !buttonOwnsTransferablePlay() ||
            pagerGestures.pointerId != MotionEvent.INVALID_POINTER_ID
        ) return
        val totalX = x - buttonDownX
        val totalY = y - buttonDownY
        if (abs(totalX) <= touchSlop || abs(totalX) <= abs(totalY)) return
        val transferredButtonKind = buttonKind
        buttonPointerId = MotionEvent.INVALID_POINTER_ID
        buttonKind = BUTTON_NONE
        buttonIndex = -1
        pagerGestures.begin(pointerId, buttonDownX, buttonDownY,
            buttonDownEventTimeMs.takeIf { it > 0L } ?: eventTimeMs, pagerOffsetFraction)
        pagerAnimating = false
        if (transferredButtonKind == BUTTON_MINI_PLAY) invalidateMiniRegion()
        else invalidateTransportRegion()
    }

    private fun buttonOwnsTransferablePlay(): Boolean =
        buttonKind == BUTTON_MINI_PLAY ||
            (buttonKind == BUTTON_TRANSPORT && buttonIndex == PlayerSurfaceTransportButtons.PLAY)

    private fun handlePointerUp(event: MotionEvent, pointerIndex: Int, cancelled: Boolean) {
        if (!cancelled) updateMixerListTouches(event)
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (!cancelled && pointerId == buttonPointerId && buttonOwnsTransferablePlay()) {
            transferPlayHorizontalDragToPager(pointerId, x, y, event.eventTime)
        }
        when {
            mixerListTouches[pointerId] != null -> finishMixerListTouch(pointerId, x, y,
                cancelled, allowFling = event.actionMasked == MotionEvent.ACTION_UP)
            pointerId == lyricsOpenPointerId -> {
                lyricsOpenPointerId = MotionEvent.INVALID_POINTER_ID
                val upwardDistance = lyricsOpenDownY - y
                if (!cancelled && upwardDistance >= maxOf(touchSlop, 48f * density) &&
                    upwardDistance > abs(x - lyricsOpenDownX)
                ) performTool(PlayerSurfaceTool.LYRICS)
            }
            pointerId == pagerGestures.pointerId -> finishPagerPointer(x, y, event.eventTime, cancelled)
            pointerId == seekerGestures.scrubPointerId -> seekerGestures.finishScrubPointer(
                x,
                controlSpaceY(y),
                event.eventTime,
                cancelled
            )
            pointerId == buttonPointerId -> finishButton(x, y, cancelled)
            else -> {
                var lane = 0
                while (lane < mixerGestures.mixerPointerIds.size) {
                    if (mixerGestures.mixerPointerIds[lane] == pointerId) {
                        mixerGestures.finishMixerPointer(lane, x, cancelled)
                        break
                    }
                    lane++
                }
            }
        }
        releaseParentInterceptionIfIdle()
    }

    private fun updatePagerPointer(event: MotionEvent, pointerIndex: Int) {
        updatePagerPointer(
            x = event.getX(pointerIndex),
            y = event.getY(pointerIndex),
            eventTimeMs = event.eventTime
        )
    }

    private fun updatePagerPointer(x: Float, y: Float, eventTimeMs: Long) {
        val packet = visuals
        when (pagerGestures.update(x, y, eventTimeMs,
            routeGeometry.pagerRight - routeGeometry.pagerLeft, visualQueueIndex, packet?.catalog?.size)) {
            PlayerPagerPointerUpdate.CANCELLED_TAP -> {
                clearPendingArtworkTap()
                return
            }
            PlayerPagerPointerUpdate.CANCELLED_VERTICAL -> {
                finishPagerPointer(x, y, eventTimeMs, cancelled = true)
                releaseParentInterceptionIfIdle()
                return
            }
            PlayerPagerPointerUpdate.TRACKING -> return
            PlayerPagerPointerUpdate.DRAGGING -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        val offset = pagerGestures.offset
        pagerOffsetFraction = offset
        val generation = profile?.owner?.generation ?: return
        previewPagerSelection(generation,
            playerSurfaceSelectionPreviewIndex(visualQueueIndex, packet?.catalog?.size ?: 0,
                offset, liveRepeatMode, allowRepeatAllWrap = false), offset)
        invalidatePagerRegions()
    }

    private fun finishPagerPointer(x: Float, y: Float, eventTimeMs: Long, cancelled: Boolean) {
        val generation = profile?.owner?.generation ?: return resetPagerPointer()
        if (cancelled) {
            clearPendingArtworkTap()
            cancelPagerSelectionPreview(generation)
            val spring = playerSurfacePagerSpringAfterCancellation(
                offsetFraction = pagerOffsetFraction.coerceIn(-1f, 1f),
                velocityFraction = pagerGestures.velocity,
                wasAnimating = true,
                preserveSpring = true,
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            )
            pagerOffsetFraction = spring.offsetFraction
            pagerVelocityFraction = spring.velocityFraction
            pagerAnimating = spring.animating
            resetPagerPointer(keepAnimation = true)
            if (pagerAnimating) scheduleFrame() else invalidatePagerRegions()
            return
        }
        // ACTION_UP owns the user's final physical coordinate. Android is allowed to coalesce
        // intermediate MOVE events under load, so deciding from the preceding MOVE can turn a
        // complete human swipe into a silent cancel even though the finger crossed the page.
        updatePagerPointer(x, y, eventTimeMs)
        if (pagerGestures.pointerId == MotionEvent.INVALID_POINTER_ID || pagerGestures.tapCancelled) {
            clearPendingArtworkTap()
            resetPagerPointer(keepAnimation = true)
            return
        }
        if (pagerGestures.dragging) {
            clearPendingArtworkTap()
            val packet = visuals
            val direction = playerSurfaceEasyPagerReleaseDirection(
                offsetFraction = pagerOffsetFraction,
                velocityFractionPerSecond = pagerGestures.velocity,
                hasLower = visualQueueIndex > 0,
                hasHigher = packet != null && visualQueueIndex < packet.catalog.lastIndex
            )
            val target = when (direction) {
                TrackVisualDirection.LOWER_INDEX -> visualQueueIndex - 1
                TrackVisualDirection.HIGHER_INDEX -> visualQueueIndex + 1
                null -> visualQueueIndex
            }
            val commitAccepted = if (direction != null) {
                packet?.catalog?.getOrNull(target)?.songId?.let { songId ->
                    sink?.onPagerCommit(generation, target, songId) == true
                } == true
            } else {
                false
            }
            if (commitAccepted && direction != null) {
                val currentProfile = profile
                val targetSong = packet?.catalog?.getOrNull(target)
                if (currentProfile != null && targetSong != null) {
                    pendingVisualSelection.recordAccepted(
                        ownerGeneration = generation,
                        queue = currentProfile.queue,
                        song = targetSong,
                        queueIndex = target
                    )
                }
                pagerOffsetFraction = acceptedTargetCarryOffset(pagerOffsetFraction, direction)
                visualQueueIndex = target
                bindArtworkSlots()
            } else {
                cancelPagerSelectionPreview(generation)
            }
            val spring = playerSurfacePagerSpringAfterCancellation(
                offsetFraction = pagerOffsetFraction,
                velocityFraction = pagerGestures.velocity,
                wasAnimating = true,
                preserveSpring = true,
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            )
            pagerOffsetFraction = spring.offsetFraction
            pagerVelocityFraction = spring.velocityFraction
            pagerAnimating = spring.animating
            resetPagerPointer(keepAnimation = true)
            if (pagerAnimating) scheduleFrame() else invalidatePagerRegions()
            return
        }

        if (PlayerMotionPolicy.miniChromeInteractive(expansionProgress)) {
            compactOpenAttemptCount++
            if (sink?.onOpenPlayer(generation) == true) {
                compactOpenAcceptedCount++
                startButtonBloom(BUTTON_MINI_BODY, 0, SystemClock.uptimeMillis())
            }
        } else {
            finishDoubleTapSeek(generation, x, y)
        }
        resetPagerPointer()
    }

    private fun finishDoubleTapSeek(generation: Long, x: Float, y: Float) {
        val delta = doubleTapSeekDeltaAt(x, y)
        if (delta != 0L) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = playerSurfaceArtworkDoubleTapMatches(
                firstTimeMs = lastArtworkTapTime,
                firstX = lastArtworkTapX,
                firstY = lastArtworkTapY,
                firstGeneration = lastArtworkTapGeneration,
                secondTimeMs = now,
                secondX = x,
                secondY = y,
                secondGeneration = generation,
                timeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
                slopPx = artworkDoubleTapSlop
            )
            if (doubleTap) {
                val accepted = sink?.onArtworkSkip(
                    generation,
                    delta
                ) == true
                if (accepted) {
                    if (liveSourceMode == PlayerSurfaceSourceMode.ORIGINAL) {
                        artworkFeedbackForward = delta > 0L
                        startButtonBloom(BUTTON_ARTWORK_SKIP, 0, SystemClock.uptimeMillis())
                    }
                }
                clearPendingArtworkTap()
            } else {
                lastArtworkTapTime = now
                lastArtworkTapX = x
                lastArtworkTapY = y
                lastArtworkTapGeneration = generation
            }
        } else {
            clearPendingArtworkTap()
        }
    }

    private fun doubleTapSeekDeltaAt(x: Float, y: Float): Long {
        if (!PlayerMotionPolicy.destinationChromeInteractive(expansionProgress) ||
            (liveSourceMode == PlayerSurfaceSourceMode.ORIGINAL &&
                !playerSurfaceMediaContentVisible(artworkAlpha)) ||
            (profile?.timeline?.durationMs ?: 0L) <= 0L
        ) return 0L
        if (liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
            (!controlsValid || sourceMotionPending || sourceMotionActive)
        ) return 0L
        return playerSurfaceDoubleTapSeekDelta(
            liveSourceMode, x, y,
            PlayerSurfaceBounds(centerArtworkLeft, centerArtworkTop, centerArtworkRight, centerArtworkBottom),
            PlayerSurfaceBounds(0f, mixerGridTop, width.toFloat(), mixerGridBottom)
        )
    }

    private fun resetPagerPointer(keepAnimation: Boolean = false) {
        pagerGestures.reset()
        if (!keepAnimation) {
            pagerOffsetFraction = 0f
            pagerVelocityFraction = 0f
            pagerAnimating = false
        }
    }

    private fun clearPendingArtworkTap() {
        lastArtworkTapTime = 0L
        lastArtworkTapX = 0f
        lastArtworkTapY = 0f
        lastArtworkTapGeneration = Long.MIN_VALUE
    }

    fun setMixerScrollListener(listener: ((PlayerSurfaceSemanticsGeometry?) -> Unit)?) {
        mixerScrollListener = listener
    }

    fun scrollMixerBy(generation: Long, deltaPx: Float): Boolean {
        if (profile?.owner?.generation != generation || !deltaPx.isFinite() ||
            liveSourceMode != PlayerSurfaceSourceMode.STEMS ||
            !PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)
        ) return false
        val next = (mixerScrollOffsetPx + deltaPx).coerceIn(0f, mixerScrollMaximumPx)
        if (next == mixerScrollOffsetPx) return false
        mixerScrollOffsetPx = next
        rebuildControlGeometry()
        mixerChromeDirty = true
        invalidateMixerRegionFromInput()
        mixerScrollListener?.invoke(semanticsGeometry())
        return true
    }

    private fun stopMixerFling() {
        mixerScroller.forceFinished(true)
        mixerFlingOwner = null
    }

    private fun advanceMixerFling() {
        val current = profile ?: return stopMixerFling()
        if (current.owner != mixerFlingOwner || current.mixer.packageRevision != mixerFlingPackageRevision ||
            liveSourceMode != PlayerSurfaceSourceMode.STEMS || !ValueAnimator.areAnimatorsEnabled() ||
            !PlayerMotionPolicy.destinationChromeInteractive(expansionProgress)) {
            stopMixerFling()
            return
        }
        if (mixerScroller.computeScrollOffset()) {
            scrollMixerBy(current.owner.generation, mixerScroller.currY - mixerScrollOffsetPx)
        }
    }

    /** A pending slider contact gets pressed feedback without acquiring gain ownership. */
    private fun isMixerSliderPressed(lane: Int): Boolean {
        if (mixerGestures.mixerPointerIds[lane] != MotionEvent.INVALID_POINTER_ID) return true
        val current = profile ?: return false
        if (liveSourceMode != PlayerSurfaceSourceMode.STEMS) return false
        var slot = 0
        while (slot < mixerListTouches.size()) {
            val touch = mixerListTouches.valueAt(slot)
            if (touch.lane == lane && touch.target == BUTTON_NONE &&
                !touch.cancelled && !touch.scrolling && current.owner == touch.owner &&
                current.mixer.packageRevision == touch.packageRevision
            ) return true
            slot++
        }
        return false
    }

    private fun updateMixerListTouches(event: MotionEvent) {
        var slot = 0
        while (slot < mixerListTouches.size()) {
            val pointerId = mixerListTouches.keyAt(slot)
            val touch = mixerListTouches.valueAt(slot)
            val index = event.findPointerIndex(pointerId)
            val current = profile
            if (index < 0 || current == null || current.owner != touch.owner ||
                current.mixer.packageRevision != touch.packageRevision ||
                liveSourceMode != PlayerSurfaceSourceMode.STEMS
            ) {
                mixerListTouches.removeAt(slot)
                if (mixerScrollPointerId == pointerId) mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
                invalidateMixerRegionFromInput()
                scheduleFrame()
                continue
            }
            val x = event.getX(index)
            val y = event.getY(index)
            val intent = playerSurfaceMixerDragIntent(x - touch.downX, y - touch.downY, touchSlop)
            if (!touch.cancelled && !touch.scrolling && intent == PlayerSurfaceMixerDragIntent.SCROLL &&
                (mixerScrollPointerId == MotionEvent.INVALID_POINTER_ID || mixerScrollPointerId == pointerId)
            ) {
                clearPendingArtworkTap()
                touch.scrolling = true
                mixerScrollPointerId = pointerId
                invalidateMixerRegionFromInput()
                scheduleFrame()
            }
            if (touch.scrolling) {
                scrollMixerBy(touch.owner.generation, touch.lastY - y)
                touch.lastY = y
            } else if (!touch.cancelled && intent == PlayerSurfaceMixerDragIntent.GAIN) {
                if (touch.target == BUTTON_NONE) {
                    mixerListTouches.removeAt(slot)
                    invalidateMixerRegionFromInput()
                    scheduleFrame()
                    mixerGestures.beginMixerPointer(touch.owner.generation, pointerId, touch.lane, x)
                    continue
                }
                touch.cancelled = true
            }
            slot++
        }
    }

    private fun finishMixerListTouch(pointerId: Int, x: Float, y: Float, cancelled: Boolean, allowFling: Boolean) {
        val touch = mixerListTouches[pointerId] ?: return
        mixerListTouches.remove(pointerId)
        if (mixerScrollPointerId == pointerId) mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
        invalidateMixerRegionFromInput()
        scheduleFrame()
        val current = profile ?: return
        if (allowFling && !cancelled && !touch.cancelled && touch.scrolling && current.owner == touch.owner &&
            current.mixer.packageRevision == touch.packageRevision && liveSourceMode == PlayerSurfaceSourceMode.STEMS &&
            mixerListTouches.size() == 0 && ValueAnimator.areAnimatorsEnabled()) {
            val configuration = ViewConfiguration.get(context)
            mixerVelocityTracker?.computeCurrentVelocity(1_000, configuration.scaledMaximumFlingVelocity.toFloat())
            val velocity = -(mixerVelocityTracker?.getYVelocity(pointerId) ?: 0f)
            if (abs(velocity) >= configuration.scaledMinimumFlingVelocity) {
                mixerFlingOwner = touch.owner
                mixerFlingPackageRevision = touch.packageRevision
                mixerScroller.fling(0, mixerScrollOffsetPx.toInt(), 0, velocity.toInt(),
                    0, 0, 0, mixerScrollMaximumPx.toInt())
                scheduleFrame()
            }
        }
        if (mixerListTouches.size() == 0) {
            mixerVelocityTracker?.recycle()
            mixerVelocityTracker = null
        }
        if (cancelled || touch.cancelled || touch.scrolling || liveSourceMode != PlayerSurfaceSourceMode.STEMS ||
            current.owner != touch.owner ||
            current.mixer.packageRevision != touch.packageRevision ||
            playerSurfaceMixerDragIntent(x - touch.downX, y - touch.downY, touchSlop) !=
                PlayerSurfaceMixerDragIntent.PENDING
        ) {
            clearPendingArtworkTap()
            return
        }
        if (touch.target == MIXER_LIST_SCROLL_ONLY) {
            finishDoubleTapSeek(touch.owner.generation, x, y)
            return
        }
        if (touch.target == BUTTON_NONE) {
            if (mixerGestures.beginMixerPointer(touch.owner.generation, pointerId, touch.lane, x))
                mixerGestures.finishMixerPointer(touch.lane, x, cancelled = false)
        } else if (beginButton(pointerId, touch.target, touch.lane, x, controlSpaceY(y))) {
            finishButton(x, y, cancelled = false)
        }
    }

    private fun beginButton(
        pointerId: Int,
        kind: Int,
        index: Int,
        x: Float,
        y: Float
    ): Boolean {
        clearPendingArtworkTap()
        if (buttonPointerId != MotionEvent.INVALID_POINTER_ID) return false
        buttonPointerId = pointerId
        buttonKind = kind
        buttonIndex = index
        buttonDownX = x
        buttonDownY = y
        buttonDownEventTimeMs = SystemClock.uptimeMillis()
        return true
    }

    /** Starts the existing single candidate at DOWN; selection still belongs exclusively to UP. */
    private fun beginTransportPagerPreview(generation: Long, index: Int) {
        val command = when (index) {
            PlayerSurfaceTransportButtons.PREVIOUS -> PlayerSurfaceTransportCommand.PREVIOUS
            PlayerSurfaceTransportButtons.NEXT -> PlayerSurfaceTransportCommand.NEXT
            else -> return
        }
        val offset = playerSurfaceTransportPreviewOffset(
            command = command,
            positionMs = positionProvider?.currentPositionMs()
                ?: profile?.timeline?.positionMs
                ?: 0L
        ) ?: return
        buttonPagerPreviewActive = previewPagerSelection(
            generation = generation,
            targetIndex = playerSurfaceSelectionPreviewIndex(
                currentIndex = visualQueueIndex,
                itemCount = visuals?.catalog?.size ?: 0,
                offsetFraction = offset,
                repeatMode = liveRepeatMode,
                allowRepeatAllWrap = true
            ),
            offsetFraction = offset
        ) == true
    }

    private fun cancelButtonPagerPreview(generation: Long?) {
        if (!buttonPagerPreviewActive) return
        buttonPagerPreviewActive = false
        if (generation != null) cancelPagerSelectionPreview(generation)
    }

    private fun finishButton(x: Float, y: Float, cancelled: Boolean) {
        val current = profile
        val kind = buttonKind
        val index = buttonIndex
        val generation = current?.owner?.generation
        val releasedOnAction = buttonStillInside(kind, index, x, y)
        val accepted = !cancelled && current != null && generation != null && releasedOnAction &&
            when (kind) {
                BUTTON_SOURCE_ORIGINAL -> sink?.onSource(generation, PlayerSurfaceSourceMode.ORIGINAL) == true
                BUTTON_SOURCE_STEMS -> playerSurfaceStemsActionEnabled(current.source) &&
                    sink?.onSource(generation, PlayerSurfaceSourceMode.STEMS) == true
                BUTTON_TRANSPORT -> dispatchTransportButton(generation, index)
                BUTTON_MINI_PLAY -> {
                    compactMiniPlayAttemptCount++
                    val miniAccepted = sink?.onTransport(
                        generation,
                        PlayerSurfaceTransportCommand.PLAY_PAUSE
                    ) == true
                    if (miniAccepted) {
                        compactMiniPlayAcceptedCount++
                        livePlayWhenReady = !livePlayWhenReady
                        transportChromeDirty = true
                    }
                    miniAccepted
                }
                BUTTON_MIXER_MUTE -> toggleMixerButton(generation, index, mute = true)
                BUTTON_MIXER_SOLO -> toggleMixerButton(generation, index, mute = false)
                else -> false
            }
        if (accepted) {
            startButtonBloom(kind, index, SystemClock.uptimeMillis())
        }
        if (accepted && buttonPagerPreviewActive) {
            // dispatchTransportButton synchronously bound the authoritative target as current.
            buttonPagerPreviewActive = false
        } else {
            cancelButtonPagerPreview(generation)
        }
        scheduleFrame()
        buttonPointerId = MotionEvent.INVALID_POINTER_ID
        buttonKind = BUTTON_NONE
        buttonIndex = -1
        // Releasing one button cannot change geometry. Redraw only the region that owned its
        // pressed or optimistic state; the authoritative snapshot later invalidates every region
        // whose actual state changed. The former relayout plus eight-region invalidation submitted
        // unrelated artwork, waveform, and tool display lists after every control tap.
        when (kind) {
            BUTTON_SOURCE_ORIGINAL,
            BUTTON_SOURCE_STEMS -> sourceRegion.invalidate()
            BUTTON_TRANSPORT -> {
                invalidateTransportRegion()
                if (index == PlayerSurfaceTransportButtons.PLAY) invalidateMiniRegion()
            }
            BUTTON_MINI_PLAY -> {
                invalidateMiniRegion()
                invalidateTransportRegion()
            }
            BUTTON_MIXER_MUTE,
            BUTTON_MIXER_SOLO -> invalidateMixerRegion()
        }
    }

    private fun dispatchTransportButton(generation: Long, index: Int): Boolean {
        val transport = profile?.transport ?: return false
        if (!transportActionEnabled(transport, index)) return false
        return when (index) {
            PlayerSurfaceTransportButtons.SHUFFLE -> {
                val accepted = sink?.onTransport(
                    generation,
                    PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE
                ) == true
                if (accepted) {
                    liveShuffleEnabled = !liveShuffleEnabled
                    transportChromeDirty = true
                }
                accepted
            }
            PlayerSurfaceTransportButtons.PREVIOUS, PlayerSurfaceTransportButtons.NEXT -> {
                val command = if (index == PlayerSurfaceTransportButtons.PREVIOUS) {
                    PlayerSurfaceTransportCommand.PREVIOUS
                } else {
                    PlayerSurfaceTransportCommand.NEXT
                }
                val target = sink?.onNavigate(generation, command) ?: -1
                if (target < 0) return false
                if (target >= 0 && target != visualQueueIndex &&
                    target in 0..(visuals?.catalog?.lastIndex ?: -1)
                ) {
                    val currentProfile = profile
                    val targetSong = visuals?.catalog?.getOrNull(target)
                    if (currentProfile != null && targetSong != null) {
                        pendingVisualSelection.recordAccepted(
                            ownerGeneration = generation,
                            queue = currentProfile.queue,
                            song = targetSong,
                            queueIndex = target
                        )
                    }
                    visualQueueIndex = target
                    pagerOffsetFraction = 0f
                    pagerVelocityFraction = 0f
                    pagerAnimating = false
                    bindArtworkSlots()
                    invalidatePagerRegions()
                    scheduleFrame()
                }
                true
            }
            PlayerSurfaceTransportButtons.PLAY -> {
                val accepted = sink?.onTransport(
                    generation,
                    PlayerSurfaceTransportCommand.PLAY_PAUSE
                ) == true
                if (accepted) {
                    livePlayWhenReady = !livePlayWhenReady
                    transportChromeDirty = true
                }
                accepted
            }
            PlayerSurfaceTransportButtons.REPEAT -> {
                val accepted = sink?.onTransport(
                    generation,
                    PlayerSurfaceTransportCommand.REPEAT_CYCLE
                ) == true
                if (accepted) {
                    liveRepeatMode = when (liveRepeatMode) {
                        PlayerSurfaceRepeatMode.OFF -> PlayerSurfaceRepeatMode.ALL
                        PlayerSurfaceRepeatMode.ALL -> PlayerSurfaceRepeatMode.ONE
                        PlayerSurfaceRepeatMode.ONE -> PlayerSurfaceRepeatMode.OFF
                    }
                    bindArtworkSlots()
                    transportChromeDirty = true
                }
                accepted
            }
            else -> false
        }
    }

    private fun transportActionEnabled(
        transport: PlayerSurfaceTransportPacket,
        index: Int
    ): Boolean = when (index) {
        PlayerSurfaceTransportButtons.PREVIOUS -> transport.canGoPrevious
        PlayerSurfaceTransportButtons.NEXT -> transport.canGoNext
        PlayerSurfaceTransportButtons.SHUFFLE, PlayerSurfaceTransportButtons.PLAY, PlayerSurfaceTransportButtons.REPEAT -> true
        else -> false
    }

    private fun toggleMixerButton(generation: Long, lane: Int, mute: Boolean): Boolean {
        val current = profile ?: return false
        if (lane !in 0 until current.mixer.laneCount) return false
        val nextMuted = if (mute) !mixerGestures.mixerMuted[lane] else mixerGestures.mixerMuted[lane]
        val nextSolo = if (mute) mixerGestures.mixerSolo[lane] else !mixerGestures.mixerSolo[lane]
        val accepted = if (mute) sink?.onSetMuted(generation, lane, nextMuted)
            else sink?.onSetSolo(generation, lane, nextSolo)
        if (accepted == true) {
            acceptMixerFlags(generation, lane, mixerGestures.committedMixerVolumes[lane],
                if (mute) nextMuted else mixerGestures.committedMixerMuted[lane],
                if (mute) mixerGestures.mixerSolo[lane] else nextSolo)
            mixerChromeDirty = true
            return true
        }
        return false
    }

    fun acceptMixerFlags(generation: Long, lane: Int, volume: Float, muted: Boolean, solo: Boolean): Boolean {
        val current = profile ?: return false
        if (current.owner.generation != generation || lane !in 0 until current.mixer.laneCount) return false
        mixerGestures.acceptMixerFlags(lane, volume, muted, solo)
        mixerChromeDirty = true
        return true
    }

    private fun controlSpaceY(y: Float): Float = if (rigidToolControlMotion) {
        y - rigidToolControlTranslationY
    } else {
        y
    }

    private fun buttonStillInside(kind: Int, index: Int, x: Float, y: Float): Boolean {
        val resolvedY = when (kind) {
            BUTTON_SOURCE_ORIGINAL,
            BUTTON_SOURCE_STEMS -> y
            BUTTON_TRANSPORT,
            BUTTON_MIXER_MUTE,
            BUTTON_MIXER_SOLO -> controlSpaceY(y)
            else -> y
        }
        return when (kind) {
        BUTTON_SOURCE_ORIGINAL -> contains(
            x,
            resolvedY,
            routeGeometry.sourceOriginalHitBounds.left,
            routeGeometry.sourceOriginalHitBounds.top,
            routeGeometry.sourceOriginalHitBounds.right,
            routeGeometry.sourceOriginalHitBounds.bottom
        )
        BUTTON_SOURCE_STEMS -> contains(
            x,
            resolvedY,
            routeGeometry.sourceStemsHitBounds.left,
            routeGeometry.sourceStemsHitBounds.top,
            routeGeometry.sourceStemsHitBounds.right,
            routeGeometry.sourceStemsHitBounds.bottom
        )
        BUTTON_TRANSPORT -> containsBounds(controlGeometry.transport.hit, index, x, resolvedY)
        BUTTON_MINI_PLAY -> contains(
            x,
            resolvedY,
            routeGeometry.miniPlayHitBounds.left,
            routeGeometry.miniPlayHitBounds.top,
            routeGeometry.miniPlayHitBounds.right,
            routeGeometry.miniPlayHitBounds.bottom
        )
        BUTTON_MIXER_MUTE -> containsBounds(controlGeometry.mute.hit, index, x, resolvedY)
        BUTTON_MIXER_SOLO -> containsBounds(controlGeometry.solo.hit, index, x, resolvedY)
        else -> false
    }
    }

    private fun hitMixerSlider(x: Float, y: Float): Int {
        if (liveSourceMode != PlayerSurfaceSourceMode.STEMS || mixerCardHeightPx <= .01f ||
            sourceMotionPending || sourceMotionActive
        ) return -1
        if (!contains(x, y, mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)) return -1
        return hitBounds(controlGeometry.sliders, profile?.mixer?.laneCount ?: 0, x, y)
    }

    private fun hitBounds(bounds: FloatArray, count: Int, x: Float, y: Float): Int {
        var index = 0
        while (index < count) {
            if (containsBounds(bounds, index, x, y)) return index
            index++
        }
        return -1
    }

    private fun containsBounds(bounds: FloatArray, index: Int, x: Float, y: Float): Boolean {
        if (index < 0 || index * 4 + 3 >= bounds.size) return false
        val base = index * 4
        return contains(x, y, bounds[base], bounds[base + 1], bounds[base + 2], bounds[base + 3])
    }

    private fun contains(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean = right > left && bottom > top && x >= left && x <= right && y >= top && y <= bottom

    private fun pointerOwned(pointerId: Int): Boolean {
        if (mixerListTouches[pointerId] != null) return true
        if (pointerId == lyricsOpenPointerId) return true
        if (pointerId == pagerGestures.pointerId || pointerId == seekerGestures.scrubPointerId || pointerId == buttonPointerId) {
            return true
        }
        var lane = 0
        while (lane < mixerGestures.mixerPointerIds.size) {
            if (mixerGestures.mixerPointerIds[lane] == pointerId) return true
            lane++
        }
        return false
    }

    private fun cancelAllGestures(
        sealMixer: Boolean,
        preservePagerSpring: Boolean = false,
        hardResetScrub: Boolean = false
    ) {
        stopMixerFling()
        mixerVelocityTracker?.recycle()
        mixerVelocityTracker = null
        actionViews.forEach { it.cancelPendingAction() }
        mixerListTouches.clear()
        mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
        lyricsOpenPointerId = MotionEvent.INVALID_POINTER_ID
        cancelPagerGesture(preserveSpring = preservePagerSpring)
        seekerGestures.cancelScrubGesture(commit = false, hardResetMorph = hardResetScrub)
        mixerGestures.cancelMixerGestures(sealMixer)
        cancelButtonPagerPreview(profile?.owner?.generation)
        scheduleFrame()
        buttonPointerId = MotionEvent.INVALID_POINTER_ID
        buttonKind = BUTTON_NONE
        buttonIndex = -1
        releaseParentInterceptionIfIdle()
    }

    /** Owner migration keeps an exact pager lease but invalidates every owner-bound control. */
    private fun cancelNonPagerGestures(sealMixer: Boolean) {
        stopMixerFling()
        mixerVelocityTracker?.recycle()
        mixerVelocityTracker = null
        mixerListTouches.clear()
        mixerScrollPointerId = MotionEvent.INVALID_POINTER_ID
        actionViews.forEach { it.cancelPendingAction() }
        lyricsOpenPointerId = MotionEvent.INVALID_POINTER_ID
        seekerGestures.cancelScrubGesture(commit = false, hardResetMorph = true)
        mixerGestures.cancelMixerGestures(sealMixer)
        cancelButtonPagerPreview(profile?.owner?.generation)
        scheduleFrame()
        buttonPointerId = MotionEvent.INVALID_POINTER_ID
        buttonKind = BUTTON_NONE
        buttonIndex = -1
        releaseParentInterceptionIfIdle()
    }

    private fun cancelPagerGesture(preserveSpring: Boolean = false) {
        clearPendingArtworkTap()
        val generation = profile?.owner?.generation
        if (pagerGestures.pointerId != MotionEvent.INVALID_POINTER_ID && pagerGestures.dragging && generation != null) {
            cancelPagerSelectionPreview(generation)
        }
        resetPagerPointer(keepAnimation = true)
        val spring = playerSurfacePagerSpringAfterCancellation(
            offsetFraction = pagerOffsetFraction,
            velocityFraction = pagerVelocityFraction,
            wasAnimating = pagerAnimating,
            preserveSpring = preserveSpring,
            reducedMotion = !ValueAnimator.areAnimatorsEnabled()
        )
        pagerOffsetFraction = spring.offsetFraction
        pagerVelocityFraction = spring.velocityFraction
        pagerAnimating = spring.animating
        if (pagerAnimating) scheduleFrame() else invalidatePagerRegions()
    }

    private fun releaseParentInterceptionIfIdle() {
        if (
            pagerGestures.pointerId == MotionEvent.INVALID_POINTER_ID && !seekerGestures.scrubDragging && lyricsOpenPointerId == MotionEvent.INVALID_POINTER_ID &&
            mixerListTouches.size() == 0 && mixerGestures.mixerPointerIds.none { it != MotionEvent.INVALID_POINTER_ID }
        ) parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Low-frequency exact bounds for the transparent accessibility/test projection. */
    fun semanticsMixerLaneCount(): Int? = if (
        PlayerMotionPolicy.destinationChromeInteractive(expansionProgress) && mixerCardHeightPx > .01f
    ) {
        profile?.mixer?.laneCount
    } else {
        0
    }

    fun compactActionDiagnosticsForQualification(): PlayerSurfaceCompactActionDiagnostics =
        PlayerSurfaceCompactActionDiagnostics(
            openAttemptCount = compactOpenAttemptCount,
            openAcceptedCount = compactOpenAcceptedCount,
            miniPlayAttemptCount = compactMiniPlayAttemptCount,
            miniPlayAcceptedCount = compactMiniPlayAcceptedCount
        )

    internal fun frameScheduledForQualification(): Boolean = frameScheduled

    internal fun frameTimeNanosForQualification(): Long = lastFrameNanos

    internal fun loopBoundaryGestureTargetForQualification(
        x: Float,
        y: Float
    ): FullPlayerLoopBoundary? = loopBoundaryGestureTargetAt(x, y)

    /** Low-frequency exact bounds for the transparent accessibility/test projection. */
    fun semanticsGeometry(): PlayerSurfaceSemanticsGeometry? {
        if (width <= 0 || height <= 0 || !controlsValid) return null
        updateCenterBounds()
        return controlGeometry.projectSemantics(width, height,
            compact = !PlayerMotionPolicy.destinationChromeInteractive(expansionProgress),
            route = routeGeometry,
            artwork = if (playerSurfaceMediaContentVisible(artworkAlpha))
                PlayerSurfaceBounds(centerArtworkLeft, centerArtworkTop, centerArtworkRight, centerArtworkBottom)
                else PlayerSurfaceBounds(0f, 0f, 0f, 0f),
            identity = PlayerSurfaceBounds(centerIdentityLeft, centerIdentityTop, centerIdentityRight, centerIdentityBottom),
            seeker = if (seekerChromeAlpha(expansionProgress) > .001f)
                PlayerSurfaceBounds(controlLeft, seekerTop, controlRight, seekerBottom)
            else PlayerSurfaceBounds(0f, 0f, 0f, 0f),
            laneCount = semanticsMixerLaneCount() ?: 0,
            mixerViewport = PlayerSurfaceBounds(mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom),
            mixerOffset = mixerScrollOffsetPx, mixerMaximum = mixerScrollMaximumPx)
    }

    private fun updateCenterBounds() {
        val p = expansionProgress
        val artworkProgress = PlayerMotionPolicy.artworkProgress(p)
        val pageWidth = routeGeometry.pagerRight - routeGeometry.pagerLeft
        val pageHeight = routeGeometry.pagerBottom - routeGeometry.pagerTop
        val artworkSize = lerp(
            PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP * density,
            expandedArtworkSizePx,
            artworkProgress
        )
            .coerceIn(0f, min(pageWidth, pageHeight))
        val collapsedArtworkX = PLAYER_SURFACE_COMPACT_ARTWORK_LEFT_DP * density
        val expandedArtworkX = expandedArtworkLeftPx(pageWidth, artworkSize)
        centerArtworkLeft = routeGeometry.pagerLeft +
            lerp(collapsedArtworkX, expandedArtworkX, artworkProgress)
        centerArtworkTop = lerp(
            routeGeometry.pagerTop + PLAYER_SURFACE_COMPACT_ARTWORK_TOP_DP * density,
            expandedArtworkTopPx(),
            artworkProgress
        )
        centerArtworkRight = centerArtworkLeft + artworkSize
        centerArtworkBottom = centerArtworkTop + artworkSize
        val identityHeight = lerp(50f * density, visibleExpandedIdentityHeightPx(), p)
            .coerceIn(0f, pageHeight)
        val collapsedIdentityX = PLAYER_SURFACE_COMPACT_IDENTITY_LEFT_DP * density
        val collapsedIdentityWidth = (pageWidth - collapsedIdentityX - 8f * density)
            .coerceAtLeast(0f)
        val expandedIdentityX = expandedIdentityLeftPx(pageWidth, artworkSize)
        val expandedIdentityWidth = expandedIdentityWidthPx(pageWidth, expandedIdentityX)
        centerIdentityLeft = routeGeometry.pagerLeft + lerp(collapsedIdentityX, expandedIdentityX, p)
        centerIdentityTop = PlayerMotionPolicy.identityTopPx(
            compactTopPx = routeGeometry.pagerTop + 10f * density,
            expandedTopPx = expandedIdentityTopPx(artworkSize),
            progress = p
        )
        centerIdentityRight = centerIdentityLeft +
            lerp(collapsedIdentityWidth, expandedIdentityWidth, p).coerceAtLeast(0f)
        centerIdentityBottom = centerIdentityTop + identityHeight
    }

    private fun identityAlpha(progress: Float): Float = PlayerMotionPolicy.identityAlpha(progress)

    private fun headerAlpha(progress: Float): Float = PlayerMotionPolicy.headerAlpha(progress)

    private fun fullChromeAlpha(progress: Float): Float = PlayerMotionPolicy.fullChromeAlpha(progress)

    private fun seekerChromeAlpha(progress: Float): Float {
        val alpha = fullChromeAlpha(progress)
        if (progress >= 1f || expandedArtworkSizePx <= .01f || alpha <= 0f) return alpha
        updateCenterBounds()
        textPaint.typeface = systemMediumTypeface
        textPaint.textSize = scaledTextSizePx(PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP)
        val ascent = textPaint.ascent()
        val clockTop = playerSurfaceSeekerClockBaselineY(seekerRailY(), ascent, density, loopClearanceProgress) + ascent
        return alpha * playerSurfaceSeekerIdentityClearanceAlpha(clockTop, centerIdentityBottom, density)
    }

    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int {
        val resolved = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt()
        return Color.argb(resolved, Color.red(color), Color.green(color), Color.blue(color))
    }

    @ColorInt
    private fun blendArtworkTint(base: Int, artwork: Int, influence: Float): Int =
        playerSurfaceArtworkTint(base, artwork, influence)

    @ColorInt
    private fun blendOpaqueColors(base: Int, destination: Int, progress: Float): Int =
        playerSurfaceInterpolateOpaqueColors(base, destination, progress)


    private inner class PagerDrawing {
        fun drawPagerAura(canvas: Canvas, catalog: FullPlayerCatalogPacket) =
            pagerDrawing.drawPagerArtworkLayer(canvas, catalog, auraOnly = true)

        fun drawPagerArtwork(canvas: Canvas, catalog: FullPlayerCatalogPacket) {
            pagerDrawing.drawPagerArtworkLayer(canvas, catalog, auraOnly = false)
            if (liveSourceMode == PlayerSurfaceSourceMode.STEMS) return
            if (buttonAuraKind != BUTTON_ARTWORK_SKIP || !buttonBloomFrame.visible) return
            val middle = (centerArtworkLeft + centerArtworkRight) * .5f
            motionDrawing.drawNativePressBloom(
                canvas,
                buttonBloomFrame,
                if (artworkFeedbackForward) middle else centerArtworkLeft,
                centerArtworkTop,
                if (artworkFeedbackForward) centerArtworkRight else middle,
                centerArtworkBottom,
                artworkAlpha.coerceIn(0f, 1f)
            )
        }

        fun drawPagerArtworkLayer(canvas: Canvas, catalog: FullPlayerCatalogPacket, auraOnly: Boolean) {
            if (!playerSurfaceMediaContentVisible(artworkAlpha)) return
            if (visualQueueIndex !in catalog.indices || routeGeometry.pagerRight <= routeGeometry.pagerLeft) return
            val recordingArtworkSizePx = if (auraOnly) {
                expandedArtworkSizePx
            } else {
                pagerRecordingArtworkSizePx()
            }
            val recordingBottom = pagerRecordingBottom(recordingArtworkSizePx)
            // A retained pager top may outlive the outgoing source's collapsed artwork for one
            // rebind. An empty recording viewport has no pixels; never size a page from it.
            if (recordingBottom <= routeGeometry.pagerTop) return
            val save = canvas.save()
            if (auraOnly) {
                val auraClip = playerSurfaceArtworkAuraClipBounds(
                    pagerLeft = routeGeometry.pagerLeft,
                    pagerTop = routeGeometry.pagerTop,
                    pagerRight = routeGeometry.pagerRight,
                    recordingBottom = recordingBottom,
                    surfaceWidth = width.toFloat(),
                    surfaceHeight = height.toFloat(),
                    density = density
                )
                val compactClearRight = (routeGeometry.miniPlayLeft - 2f * density).coerceAtLeast(routeGeometry.pagerLeft)
                val visualRight = lerp(
                    minOf(auraClip.right, compactClearRight),
                    auraClip.right,
                    1f - PlayerMotionPolicy.miniChromeAlpha(expansionProgress)
                )
                canvas.clipRect(auraClip.left, auraClip.top, visualRight, auraClip.bottom)
            } else {
                // Control clearance applies only to the blurred aura. Opaque artwork owns its full
                // pager bounds; clipping it to controlTop truncated the lower 40dp of Stems art.
                val compactClearRight = (routeGeometry.miniPlayLeft - 2f * density).coerceAtLeast(routeGeometry.pagerLeft)
                val visualRight = lerp(
                    minOf(routeGeometry.pagerRight, compactClearRight),
                    routeGeometry.pagerRight,
                    1f - PlayerMotionPolicy.miniChromeAlpha(expansionProgress)
                )
                canvas.clipRect(routeGeometry.pagerLeft, routeGeometry.pagerTop, visualRight, recordingBottom)
            }
            val pageWidth = routeGeometry.pagerRight - routeGeometry.pagerLeft
            var relative = -1
            while (relative <= 1) {
                val item = catalog.getOrNull(visualQueueIndex + relative)
                val pageX = routeGeometry.pagerLeft + (relative + pagerOffsetFraction) * pageWidth
                if (
                    item != null &&
                    playerSurfacePageIntersectsViewport(pageX, pageWidth, routeGeometry.pagerLeft, routeGeometry.pagerRight)
                ) {
                    pagerDrawing.drawSongArtworkPage(
                        canvas = canvas,
                        item = item,
                        artworkSlot = relative + 1,
                        pageLeft = pageX,
                        pageWidth = pageWidth,
                        pageHeight = recordingBottom - routeGeometry.pagerTop,
                        recordingArtworkSizePx = recordingArtworkSizePx,
                        auraOnly = auraOnly
                    )
                }
                relative++
            }
            canvas.restoreToCount(save)
        }

        fun drawMiniPlayerBody(canvas: Canvas) {
            val alpha = PlayerMotionPolicy.miniChromeAlpha(expansionProgress)
            if (alpha <= .001f || width <= 0) return
            val left = PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP * density
            val top = PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP * density
            val right = width - PLAYER_SURFACE_COMPACT_VISIBLE_HORIZONTAL_INSET_DP * density
            val bottom = (PLAYER_SURFACE_COMPACT_ENVELOPE_HEIGHT_DP -
                PLAYER_SURFACE_COMPACT_VISIBLE_VERTICAL_INSET_DP) * density
            val radius = PLAYER_SURFACE_COMPACT_VISIBLE_RADIUS_DP * density
            if (right <= left || bottom <= top) return

            // The shared Compose backdrop supplies the same cover gradient in mini and full modes.
            fillPaint.shader = null
            val current = profile ?: return
            val progress = playerSurfaceMiniPlaybackFraction(
                positionMs = renderedPositionMs(current),
                durationMs = current.timeline.durationMs
            )
            miniProgressDrawing.draw(canvas, left, top, right, bottom, radius, progress, alpha,
                if (ambientMotionPolicySignal) SystemClock.uptimeMillis() / 1_000f * MINI_LIQUID_PHASE_SPEED else 0f)
            pagerDrawing.drawMiniBodyPressBloom(canvas, left, top, right, bottom, alpha)
        }

        fun drawMiniBodyPressBloom(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            alpha: Float
        ) {
            if (buttonAuraKind == BUTTON_MINI_BODY && buttonBloomFrame.visible) {
                motionDrawing.drawNativePressBloom(canvas, buttonBloomFrame, left, top, right, bottom, alpha)
            }
        }

        fun drawSongArtworkPage(
            canvas: Canvas,
            item: FullPlayerCatalogSong,
            artworkSlot: Int,
            pageLeft: Float,
            pageWidth: Float,
            pageHeight: Float,
            recordingArtworkSizePx: Float,
            auraOnly: Boolean = false
        ) {
            val artworkProgress = PlayerMotionPolicy.artworkProgress(expansionProgress)
            val artworkSize = lerp(
                PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP * density,
                recordingArtworkSizePx,
                artworkProgress
            )
                .coerceIn(0f, min(pageWidth, pageHeight))
            val collapsedArtworkX = PLAYER_SURFACE_COMPACT_ARTWORK_LEFT_DP * density
            val expandedArtworkX = expandedArtworkLeftPx(pageWidth, artworkSize)
            val unscaledArtworkLeft =
                pageLeft + lerp(collapsedArtworkX, expandedArtworkX, artworkProgress)
            val unscaledArtworkTop = lerp(
                routeGeometry.pagerTop + PLAYER_SURFACE_COMPACT_ARTWORK_TOP_DP * density,
                expandedArtworkTopPx(),
                artworkProgress
            )
            val artworkLeft = unscaledArtworkLeft
            val artworkTop = unscaledArtworkTop
            val artworkRight = artworkLeft + artworkSize
            val artworkBottom = artworkTop + artworkSize
            if (!auraOnly && artworkSlot == 1) {
                centerArtworkLeft = artworkLeft
                centerArtworkTop = artworkTop
                centerArtworkRight = artworkRight
                centerArtworkBottom = artworkBottom
            }
            if (auraOnly) {
                pagerDrawing.drawArtworkAura(
                    canvas = canvas,
                    slot = artworkSlot,
                    left = artworkLeft,
                    top = artworkTop,
                    right = artworkRight,
                    bottom = artworkBottom,
                    radius = playerSurfaceArtworkCornerRadiusPx(
                        artworkRight - artworkLeft,
                        artworkBottom - artworkTop,
                        density
                    ),
                    contentAlpha = artworkAlpha.coerceIn(0f, 1f)
                )
            } else {
                pagerDrawing.drawArtwork(
                    canvas,
                    item,
                    artworkSlot,
                    artworkLeft,
                    artworkTop,
                    artworkRight,
                    artworkBottom
                )
            }
        }

        fun drawPagerIdentity(canvas: Canvas, catalog: FullPlayerCatalogPacket) {
            identityMarqueeActive = false
            if (visualQueueIndex !in catalog.indices || routeGeometry.pagerRight <= routeGeometry.pagerLeft) return
            val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
            val recordingArtworkSizePx = pagerRecordingArtworkSizePx()
            val recordingBottom = pagerRecordingBottom(recordingArtworkSizePx)
            if (recordingBottom <= routeGeometry.pagerTop) return
            val save = canvas.save()
            canvas.clipRect(routeGeometry.pagerLeft, routeGeometry.pagerTop, routeGeometry.pagerRight, recordingBottom)
            val pageWidth = routeGeometry.pagerRight - routeGeometry.pagerLeft
            var relative = -1
            while (relative <= 1) {
                val item = catalog.getOrNull(visualQueueIndex + relative)
                val pageX = routeGeometry.pagerLeft + (relative + pagerOffsetFraction) * pageWidth
                if (
                    item != null &&
                    playerSurfacePageIntersectsViewport(pageX, pageWidth, routeGeometry.pagerLeft, routeGeometry.pagerRight)
                ) {
                    pagerDrawing.drawSongIdentityPage(
                        canvas = canvas,
                        item = item,
                        artworkSlot = relative + 1,
                        pageLeft = pageX,
                        pageWidth = pageWidth,
                        pageHeight = recordingBottom - routeGeometry.pagerTop,
                        recordingArtworkSizePx = recordingArtworkSizePx,
                        isCurrentPage = relative == 0,
                        animateIdentityMarquee = relative == 0 && animatorsEnabled
                    )
                }
                relative++
            }
            canvas.restoreToCount(save)
            if (identityMarqueeActive) scheduleFrame()
        }

        fun drawSongIdentityPage(
            canvas: Canvas,
            item: FullPlayerCatalogSong,
            artworkSlot: Int,
            pageLeft: Float,
            pageWidth: Float,
            pageHeight: Float,
            recordingArtworkSizePx: Float,
            isCurrentPage: Boolean,
            animateIdentityMarquee: Boolean,
        ) {
            val p = expansionProgress
            val artworkProgress = PlayerMotionPolicy.artworkProgress(p)
            val artworkSize = lerp(
                PLAYER_SURFACE_COMPACT_ARTWORK_SIZE_DP * density,
                recordingArtworkSizePx,
                artworkProgress
            )
                .coerceIn(0f, min(pageWidth, pageHeight))
            val collapsedArtworkX = PLAYER_SURFACE_COMPACT_ARTWORK_LEFT_DP * density
            val identityHeight = lerp(50f * density, visibleExpandedIdentityHeightPx(), p)
                .coerceIn(0f, pageHeight)
            val collapsedIdentityX = PLAYER_SURFACE_COMPACT_IDENTITY_LEFT_DP * density
            val collapsedIdentityWidth = (pageWidth - collapsedIdentityX - 8f * density)
                .coerceAtLeast(0f)
            val expandedIdentityX = expandedIdentityLeftPx(pageWidth, artworkSize)
            val expandedIdentityWidth = expandedIdentityWidthPx(pageWidth, expandedIdentityX)
            val identityLeft = pageLeft + lerp(collapsedIdentityX, expandedIdentityX, p)
            val identityWidth = lerp(collapsedIdentityWidth, expandedIdentityWidth, p)
                .coerceAtLeast(0f)
            val identityTop = PlayerMotionPolicy.identityTopPx(
                compactTopPx = routeGeometry.pagerTop + 10f * density,
                expandedTopPx = expandedIdentityTopPx(artworkSize),
                progress = p
            )
            val identityRight = identityLeft + identityWidth
            val identityBottom = identityTop + identityHeight
            if (artworkSlot == 1) {
                centerIdentityLeft = identityLeft
                centerIdentityTop = identityTop
                centerIdentityRight = identityRight
                centerIdentityBottom = identityBottom
            }
            val identityAlpha = identityAlpha(p)
            if (identityAlpha <= 0f || identityWidth <= 0f) return

            if (
                isCurrentPage &&
                (identityMarqueeSongId != item.songId || !animateIdentityMarquee)
            ) {
                identityMarqueeSongId = item.songId
                identityMarqueeStartedAtMs = SystemClock.uptimeMillis()
            }
            val marqueeElapsedMs = if (isCurrentPage) {
                (SystemClock.uptimeMillis() - identityMarqueeStartedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }

            val visualProfile = expandedVisualProfile()
            val titleSize = lerp(scaledTextSizePx(14f), scaledTextSizePx(visualProfile.titleSizeSp), p)
            val artistSize = lerp(scaledTextSizePx(12f), scaledTextSizePx(visualProfile.artistSizeSp), p)
            titlePaint.textSize = titleSize
            titlePaint.typeface = systemSemiboldTypeface
            titlePaint.color = withAlpha(TEXT_PRIMARY, identityAlpha)
            textPaint.textSize = artistSize
            textPaint.color = withAlpha(PRIMARY_BRIGHT, identityAlpha)
            textPaint.typeface = systemMediumTypeface
            titlePaint.textAlign = Paint.Align.LEFT
            textPaint.textAlign = Paint.Align.LEFT
            val compactTitleBaseline = if (fontScale <= 1f) {
                routeGeometry.pagerTop + 33f * density
            } else {
                playerSurfaceCenteredTextBaselineY(
                    routeGeometry.pagerTop + 32f * density,
                    titlePaint.ascent(),
                    titlePaint.descent()
                )
            }
            val compactArtistBaseline = routeGeometry.pagerTop + 50f * density
            val identityTranslation = expandedIdentityTranslationPx(artworkSize)
            val authoredExpandedTitleBaseline = visualProfile.titleBaselineDp * density + identityTranslation
            val authoredExpandedArtistBaseline = visualProfile.artistBaselineDp * density + identityTranslation
            val expandedIdentityTop = expandedIdentityTopPx(artworkSize)
            val expandedTitleBaseline = maxOf(
                authoredExpandedTitleBaseline,
                expandedIdentityTop - titlePaint.ascent() + 2f * density
            )
            val expandedArtistBaseline = maxOf(
                authoredExpandedArtistBaseline,
                expandedTitleBaseline + titlePaint.descent() + 4f * density - textPaint.ascent()
            )
            val titleBaseline = lerp(
                compactTitleBaseline,
                expandedTitleBaseline,
                p
            )
            val artistBaseline = lerp(
                compactArtistBaseline,
                expandedArtistBaseline,
                p
            )
            val stackedTitleBaseline = maxOf(
                titleBaseline,
                identityTop - titlePaint.ascent() + 2f * density
            )
            val stackedArtistBaseline = maxOf(
                artistBaseline,
                stackedTitleBaseline + titlePaint.descent() + 4f * density - textPaint.ascent()
            )
            val artistFits = stackedArtistBaseline + textPaint.descent() + 2f * density <=
                identityBottom
            val responsiveTitleBaseline = if (artistFits) stackedTitleBaseline else {
                playerSurfaceCenteredTextBaselineY(
                    (identityTop + identityBottom) * .5f,
                    titlePaint.ascent(),
                    titlePaint.descent()
                )
            }
            val clip = canvas.save()
            canvas.clipRect(identityLeft, identityTop, identityRight, identityBottom)
            val titleLayout = identityLineLayout(item.title, titlePaint)
            formatLabelPaint.textSize = scaledTextSizePx(11f)
            formatLabelPaint.color = withAlpha(TEXT_SECONDARY, identityAlpha * p)
            val formatLabelWidth = if (item.isFlac) formatLabelPaint.measureText("FLAC") else 0f
            val titleRight = identityRight - if (item.isFlac) (formatLabelWidth + 8f * density) * p else 0f
            val artistColor = textPaint.color
            val artistLayout = identityLineLayout(item.artist, textPaint)
            pagerDrawing.drawIdentityLine(
                canvas = canvas,
                layout = titleLayout,
                viewportLeft = identityLeft,
                viewportTop = identityTop,
                viewportRight = titleRight,
                viewportBottom = identityBottom,
                baseline = responsiveTitleBaseline,
                color = titlePaint.color,
                expansionProgress = p,
                marqueeElapsedMs = marqueeElapsedMs,
                animateMarquee = animateIdentityMarquee
            )
            if (item.isFlac && p > 0f) {
                canvas.drawText("FLAC", identityRight - formatLabelWidth,
                    responsiveTitleBaseline, formatLabelPaint)
            }
            if (artistFits) {
                pagerDrawing.drawIdentityLine(
                    canvas = canvas,
                    layout = artistLayout,
                    viewportLeft = identityLeft,
                    viewportTop = identityTop,
                    viewportRight = identityRight,
                    viewportBottom = identityBottom,
                    baseline = stackedArtistBaseline,
                    color = artistColor,
                    expansionProgress = p,
                    marqueeElapsedMs = marqueeElapsedMs,
                    animateMarquee = animateIdentityMarquee
                )
            }
            canvas.restoreToCount(clip)
        }

        /**
         * One shaping paragraph owns the complete title/artist. The full value remains shaped as one
         * bidi paragraph and overflowing pixels move behind a viewport clip; no substring ticker may
         * split or reorder a mixed Arabic/Latin value.
         */
        fun drawIdentityLine(
            canvas: Canvas,
            layout: StaticLayout,
            viewportLeft: Float,
            viewportTop: Float,
            viewportRight: Float,
            viewportBottom: Float,
            baseline: Float,
            @ColorInt color: Int,
            expansionProgress: Float,
            marqueeElapsedMs: Long,
            animateMarquee: Boolean
        ) {
            if (viewportRight <= viewportLeft) return
            layout.paint.color = color
            val contentWidth = layout.width.toFloat()
            val viewportWidth = viewportRight - viewportLeft
            val overflowing = contentWidth - viewportWidth > .5f
            val rightToLeft = layout.getParagraphDirection(0) < 0
            val originX = if (overflowing) {
                if (rightToLeft) viewportRight - contentWidth else viewportLeft
            } else {
                playerSurfaceIdentityTextOriginX(
                    viewportLeft,
                    viewportRight,
                    contentWidth,
                    expansionProgress
                )
            }
            val marqueeTranslation = if (playerSurfaceIdentityMarqueeMotionAllowed(
                    animatorsEnabled = animateMarquee,
                    overflowing = overflowing,
                    currentPage = animateMarquee
                )
            ) {
                identityMarqueeActive = true
                playerSurfaceIdentityMarqueeTranslationPx(
                    contentWidthPx = contentWidth,
                    viewportWidthPx = viewportWidth,
                    elapsedMs = marqueeElapsedMs,
                    speedPxPerSecond = IDENTITY_MARQUEE_SPEED_DP_PER_SECOND * density,
                    rightToLeft = rightToLeft
                )
            } else {
                0f
            }
            val save = canvas.save()
            canvas.clipRect(viewportLeft, viewportTop, viewportRight, viewportBottom)
            canvas.translate(originX + marqueeTranslation, baseline - layout.getLineBaseline(0))
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }

        fun drawArtwork(
            canvas: Canvas,
            item: FullPlayerCatalogSong,
            slot: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ) {
            val alpha = artworkAlpha.coerceIn(0f, 1f)
            if (right <= left || bottom <= top || alpha <= .001f) return
            val radius = playerSurfaceArtworkCornerRadiusPx(right - left, bottom - top, density)
            val shader = artworkShaders.getOrNull(slot)
            val bitmap = artworkBitmaps.getOrNull(slot)
            val loadProgress = artworkLoadProgress.getOrElse(slot) { 1f }
            var artworkDrawn = false
            if (shader != null) {
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    if (loadProgress < .999f) {
                        fillPaint.shader = artworkGradients.getOrNull(slot)
                        fillPaint.alpha = (alpha * 255f).roundToInt()
                        if (fillPaint.shader == null) fillPaint.color = ARTWORK_AURA_GRAPHITE_ARGB
                        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
                        fillPaint.shader = null
                        fillPaint.alpha = 255
                    }
                    val scale = max((right - left) / bitmap.width, (bottom - top) / bitmap.height)
                    val dx = left + ((right - left) - bitmap.width * scale) * .5f
                    val dy = top + ((bottom - top) - bitmap.height * scale) * .5f
                    bitmapMatrix.reset()
                    bitmapMatrix.setScale(scale, scale)
                    bitmapMatrix.postTranslate(dx, dy)
                    shader.setLocalMatrix(bitmapMatrix)
                    fillPaint.shader = shader
                    fillPaint.alpha = (alpha * loadProgress * 255f).roundToInt()
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
                    fillPaint.shader = null
                    fillPaint.alpha = 255
                    artworkDrawn = true
                }
            }
            if (!artworkDrawn) {
                fillPaint.shader = artworkGradients.getOrNull(slot)
                fillPaint.alpha = (alpha * 255f).roundToInt()
                if (fillPaint.shader == null) fillPaint.color = ARTWORK_AURA_GRAPHITE_ARGB
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
                fillPaint.shader = null
                fillPaint.alpha = 255
            }
            if (!artworkDrawn || loadProgress < .999f) {
                iconAtlas.draw(
                    canvas = canvas,
                    icon = PlayerSurfaceIcon.MISSING_ARTWORK,
                    centerX = (left + right) * .5f,
                    centerY = (top + bottom) * .5f,
                    size = min(right - left, bottom - top) * .42f,
                    color = withAlpha(TEXT_SECONDARY, alpha * if (artworkDrawn) (1f - loadProgress) else 1f)
                )
            }
        }

        fun drawArtworkAura(
            canvas: Canvas,
            slot: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            contentAlpha: Float
        ) {
            if (artworkBitmaps.getOrNull(slot) == null) return
            val arrivalAlpha = artworkLoadProgress.getOrElse(slot) { 1f }
            val resolvedContentAlpha = contentAlpha * arrivalAlpha
            if (!playerSurfaceMediaContentVisible(resolvedContentAlpha)) return
            val frameNanos = lastFrameNanos.takeIf { it > 0L }
                ?: SystemClock.uptimeMillis() * 1_000_000L
            val phaseFrameNanos = if (artworkAuraMotion.motionEnabled) {
                frameNanos
            } else {
                artworkAuraMotion.frozenFrameNanos.takeIf { it > 0L } ?: frameNanos
            }
            val phaseMs = playerSurfaceArtworkAuraPhaseMillis(
                frameMillis = phaseFrameNanos / 1_000_000L,
                surfaceOrdinal = slot
            )
            val current = profile
            val beatPulse = if (slot == PlayerSurfaceArtworkLoader.SLOT_CURRENT &&
                current != null && expansionProgress > .01f && artworkAuraMotion.motionEnabled &&
                visuals?.catalog?.getOrNull(visualQueueIndex)?.songId == current.owner.songId
            ) fullPlayerState.snapshot?.waveform?.beatPulseAt(renderedPositionMs(current)) else null
            val keyframe = if (beatPulse != null) {
                if (livePlayWhenReady) beatPulse * .65f else 0f
            } else playerSurfaceArtworkAuraKeyframe(
                phaseMs.toFloat() / StemwiseSemanticMotion.ARTWORK_AURA_PERIOD_MS
            )
            val opacityOffset = playerSurfaceArtworkAuraOpacityOffset(keyframe)
            val luminanceOffset = playerSurfaceArtworkAuraLuminanceOffset(keyframe)
            val centerX = (left + right) * .5f
            val centerY = (top + bottom) * .5f
            val width = right - left
            val height = bottom - top
            artworkAuraCache.drawPrecomposedArtworkAuraBitmaps(
                canvas = canvas,
                slot = slot,
                centerX = centerX,
                centerY = centerY,
                liveWidth = width,
                liveHeight = height,
                liveCornerRadiusPx = radius,
                opacityOffset = opacityOffset,
                luminanceOffset = luminanceOffset,
                contentAlpha = resolvedContentAlpha
            )
        }


    }

    private inner class MixerDrawing {
        fun sampleMixerSelectedAnimations(nowMs: Long, laneCount: Int): Boolean {
            val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            val visibleLaneCount = laneCount.coerceAtMost(PLAYER_SURFACE_MAX_MIXER_LANES)
            var active = false
            var lane = 0
            while (lane < visibleLaneCount) {
                val mute = mixerSelectedStates[lane * 2]
                val solo = mixerSelectedStates[lane * 2 + 1]
                mute.sample(playerSurfaceLaneSilent(mixerGestures.mixerMuted[lane], mixerGestures.liveMixerVolumes[lane]), nowMs, reducedMotion)
                solo.sample(mixerGestures.mixerSolo[lane], nowMs, reducedMotion)
                active = active || mute.needsAnotherFrame(nowMs) || solo.needsAnotherFrame(nowMs)
                lane++
            }
            return active
        }

        fun drawMixer(canvas: Canvas, alpha: Float, laneCount: Int, rightToLeft: Boolean) {
            val save = canvas.save()
            canvas.clipRect(mixerGridLeft, mixerGridTop, mixerGridRight, mixerGridBottom)
            mixerDrawing.drawMixerChrome(canvas, alpha, laneCount)
            mixerDrawing.drawMixerVolumeOverlay(canvas, alpha, laneCount, rightToLeft)
            canvas.restoreToCount(save)
        }

        fun drawMixerButtonAuraOverlay(canvas: Canvas, alpha: Float, laneCount: Int) {
            var lane = 0
            while (lane < laneCount) {
                mixerDrawing.drawMixerButtonAura(
                    canvas,
                    controlGeometry.mute.visual,
                    lane,
                    BUTTON_MIXER_MUTE,
                    alpha
                )
                mixerDrawing.drawMixerButtonAura(
                    canvas,
                    controlGeometry.solo.visual,
                    lane,
                    BUTTON_MIXER_SOLO,
                    alpha
                )
                lane++
            }
        }

        fun drawMixerButtonAura(
            canvas: Canvas,
            bounds: FloatArray,
            lane: Int,
            kind: Int,
            alpha: Float
        ) {
            val base = lane * 4
            val left = bounds[base]
            val top = bounds[base + 1]
            val right = bounds[base + 2]
            val bottom = bounds[base + 3]
            val pressed = buttonAuraKind == kind && buttonAuraIndex == lane &&
                buttonBloomFrame.visible
            if (kind != BUTTON_MIXER_MUTE && !pressed) return
            val centerX = (left + right) * .5f
            val centerY = (top + bottom) * .5f
            val selectedState = mixerSelectedStates[lane * 2 +
                if (kind == BUTTON_MIXER_MUTE) 0 else 1]
            val selectedProgress = selectedState.value
            if (kind == BUTTON_MIXER_MUTE) {
                iconAtlas.draw(canvas, mixerLaneIcons[lane], centerX, centerY,
                    34f * density, withAlpha(TEXT_SECONDARY, alpha))
                if (selectedProgress > 0f) {
                    val radius = 13f * density
                    strokePaint.shader = null
                    strokePaint.strokeWidth = 2f * density
                    strokePaint.color = withAlpha(TEXT_SECONDARY, alpha * selectedProgress * .2f)
                    canvas.drawLine(centerX - radius, centerY - radius,
                        centerX - radius + 2f * radius * selectedProgress,
                        centerY - radius + 2f * radius * selectedProgress, strokePaint)
                }
                return
            }
            motionDrawing.drawNativePressBloom(
                canvas,
                buttonBloomFrame,
                left,
                top,
                right,
                bottom,
                alpha
            )
            iconAtlas.draw(
                canvas,
                PlayerSurfaceIcon.SOLO,
                centerX,
                centerY,
                24f * density * 1f,
                withAlpha(
                    blendOpaqueColors(TEXT_SECONDARY, PRIMARY_BRIGHT, selectedProgress * .2f),
                    alpha
                )
            )
        }

        fun drawMixerChrome(canvas: Canvas, alpha: Float, laneCount: Int) {
            var lane = 0
            while (lane < laneCount) {
                mixerDrawing.drawMixerLaneChrome(canvas, lane, alpha)
                lane++
            }
        }

        fun drawMixerLaneChrome(canvas: Canvas, lane: Int, alpha: Float) {
            val base = lane * 4
            val left = controlGeometry.cards[base]
            val top = controlGeometry.cards[base + 1]
            val right = controlGeometry.cards[base + 2]
            val touchBottom = controlGeometry.cards[base + 3]
            val bottom = playerSurfaceMixerVisibleRowBottom(top, touchBottom, density)
            if (right <= left || bottom <= top) return
            fillPaint.shader = null
            fillPaint.color = withAlpha(SURFACE_LOW, alpha)
            canvas.drawRoundRect(left, top, right, bottom, 16f * density, 16f * density, fillPaint)
            strokePaint.shader = null
            strokePaint.strokeWidth = density
            strokePaint.color = withAlpha(OUTLINE, alpha)
            canvas.drawRoundRect(
                left + .5f * density,
                top + .5f * density,
                right - .5f * density,
                bottom - .5f * density,
                15.5f * density,
                15.5f * density,
                strokePaint
            )
            val rowCenterY = playerSurfaceMixerVisibleRowCenterY(top, touchBottom, density)
            val sliderLeft = controlGeometry.sliders[base]
            val sliderRight = controlGeometry.sliders[base + 2]
            val thumbRadius = MIXER_SLIDER_THUMB_RADIUS_DP * density
            val trackLeft = sliderLeft + thumbRadius
            val trackRight = (sliderRight - thumbRadius).coerceAtLeast(trackLeft)
            val centerY = rowCenterY
            fillPaint.color = withAlpha(OUTLINE, alpha)
            canvas.drawRoundRect(trackLeft, centerY - 2f * density, trackRight,
                centerY + 2f * density, 2f * density, 2f * density, fillPaint)
            mixerDrawing.drawMixerToggle(
                canvas,
                controlGeometry.solo.visual,
                lane,
                PlayerSurfaceIcon.SOLO,
                mixerGestures.mixerSolo[lane],
                alpha
            )
        }

        fun drawMixerVolumeOverlay(canvas: Canvas, alpha: Float, laneCount: Int, rightToLeft: Boolean) {
            val thumbRadius = MIXER_SLIDER_THUMB_RADIUS_DP * density
            var lane = 0
            while (lane < laneCount) {
                val base = lane * 4
                val sliderLeft = controlGeometry.sliders[base]
                val sliderRight = controlGeometry.sliders[base + 2]
                val trackLeft = sliderLeft + thumbRadius
                val trackRight = (sliderRight - thumbRadius).coerceAtLeast(trackLeft)
                val trackWidth = trackRight - trackLeft
                val centerY = playerSurfaceMixerVisibleRowCenterY(
                    controlGeometry.cards[base + 1],
                    controlGeometry.cards[base + 3],
                    density
                )
                val fraction = stemSliderActiveFraction(mixerGestures.liveMixerVolumes[lane])
                val thumbX = if (rightToLeft) {
                    trackRight - trackWidth * fraction
                } else {
                    trackLeft + trackWidth * fraction
                }
                val origin = if (rightToLeft) trackRight else trackLeft
                val activeLeft = min(origin, thumbX)
                val activeRight = max(origin, thumbX)
                fillPaint.color = withAlpha(PRIMARY_PURPLE, alpha)
                if (activeRight > activeLeft) {
                    canvas.drawRoundRect(
                        activeLeft,
                        centerY - 2f * density,
                        activeRight,
                        centerY + 2f * density,
                        2f * density,
                        2f * density,
                        fillPaint
                    )
                }
                val growth = mixerThumbGrowth[lane].sample(
                    isMixerSliderPressed(lane),
                    SystemClock.uptimeMillis(), !ValueAnimator.areAnimatorsEnabled()
                )
                val visibleRadius = thumbRadius + 4f * density * growth
                fillPaint.color = withAlpha(TEXT_PRIMARY, alpha)
                canvas.drawCircle(thumbX, centerY, visibleRadius, fillPaint)
                strokePaint.shader = null
                strokePaint.strokeWidth = 2f * density
                strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
                canvas.drawCircle(thumbX, centerY, visibleRadius - density, strokePaint)
                lane++
            }
        }

        fun drawMixerValueLabels(canvas: Canvas, alpha: Float, laneCount: Int, rightToLeft: Boolean) {
            val metrics = app.stemwise.ui.design.StemwiseSliderValueLabelMetrics
            textPaint.typeface = systemMediumTypeface
            textPaint.textSize = scaledTextSizePx(metrics.TEXT_SIZE_SP)
            textPaint.textAlign = Paint.Align.CENTER
            for (lane in 0 until laneCount) {
                val progress = mixerValueLabels[lane].value
                if (progress <= .001f) continue
                val base = lane * 4
                val centerY = playerSurfaceMixerVisibleRowCenterY(
                    controlGeometry.cards[base + 1], controlGeometry.cards[base + 3], density)
                if (centerY < mixerGridTop || centerY > mixerGridBottom) continue
                val fraction = stemSliderActiveFraction(mixerGestures.liveMixerVolumes[lane])
                val thumbRadius = MIXER_SLIDER_THUMB_RADIUS_DP * density
                val start = controlGeometry.sliders[base] + thumbRadius
                val end = controlGeometry.sliders[base + 2] - thumbRadius
                val thumbX = lerp(start, end,
                    if (rightToLeft) 1f - fraction else fraction)
                val label = "${(fraction * 100f).roundToInt()}%"
                val labelWidth = max(metrics.MIN_WIDTH_DP * density,
                    textPaint.measureText(label) + 2f * metrics.HORIZONTAL_PADDING_DP * density)
                val labelHeight = mixerValueLabelHeightPx()
                val left = (thumbX - labelWidth * .5f)
                    .coerceIn(mixerGridLeft, max(mixerGridLeft, mixerGridRight - labelWidth))
                val bottom = centerY - thumbRadius - metrics.THUMB_GAP_DP * density +
                    metrics.LIFT_DP * density * (1f - progress)
                val top = bottom - labelHeight
                val radius = metrics.CORNER_RADIUS_DP * density
                fillPaint.shader = null
                fillPaint.color = withAlpha(SURFACE, alpha * progress)
                canvas.drawRoundRect(left, top, left + labelWidth, bottom, radius, radius, fillPaint)
                strokePaint.shader = null
                strokePaint.strokeWidth = density
                strokePaint.color = withAlpha(OUTLINE, alpha * progress)
                canvas.drawRoundRect(left, top, left + labelWidth, bottom, radius, radius, strokePaint)
                textPaint.color = withAlpha(TEXT_PRIMARY, alpha * progress)
                canvas.drawText(label, left + labelWidth * .5f,
                    playerSurfaceCenteredTextBaselineY((top + bottom) * .5f, textPaint.ascent(), textPaint.descent()), textPaint)
            }
        }

        fun drawRetainedMixerChrome(canvas: Canvas, laneCount: Int): Boolean {
            val layer = mixerChromeLayer
            val padding = ceil(2f * density).toInt()
            val left = (floor(controlLeft).toInt() - padding).coerceAtLeast(0)
            val top = (floor(mixerHeaderTop).toInt() - padding).coerceAtLeast(0)
            val right = (ceil(controlRight).toInt() + padding).coerceAtMost(width)
            val bottom = (ceil(mixerGridBottom).toInt() + padding).coerceAtMost(height)
            if (right <= left || bottom <= top) return false
            if (layer.setBounds(left, top, right, bottom)) mixerChromeDirty = true
            if (mixerChromeDirty || !layer.hasRecording()) {
                val recording = layer.beginRecording()
                try {
                    val save = recording.save()
                    recording.translate(-left.toFloat(), -top.toFloat())
                    mixerDrawing.drawMixerChrome(recording, 1f, laneCount)
                    recording.restoreToCount(save)
                } finally {
                    layer.endRecording()
                }
                mixerChromeDirty = false
            }
            layer.draw(canvas)
            return true
        }

        fun drawMixerToggle(
            canvas: Canvas,
            bounds: FloatArray,
            lane: Int,
            icon: PlayerSurfaceIcon,
            active: Boolean,
            alpha: Float
        ) {
            val base = lane * 4
            val left = bounds[base]
            val top = bounds[base + 1]
            val right = bounds[base + 2]
            val bottom = bounds[base + 3]
            val visualCenterX = (left + right) * .5f
            val visualCenterY = (top + bottom) * .5f
            val cornerRadius = 14f * density
            val kind = if (icon == PlayerSurfaceIcon.MUTE) BUTTON_MIXER_MUTE else BUTTON_MIXER_SOLO
            val transientPress = buttonAuraKind == kind && buttonAuraIndex == lane &&
                buttonBloomFrame.visible
            val selectedState = mixerSelectedStates[lane * 2 +
                if (icon == PlayerSurfaceIcon.MUTE) 0 else 1]
            val selectedProgress = selectedState.value
            val selectedAuraProgress = playerSurfaceSelectedAuraProgress(selectedProgress)
            fillPaint.shader = null
            fillPaint.color = withAlpha(SURFACE, alpha)
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, fillPaint)
            if (selectedAuraProgress > .001f) {
                motionDrawing.drawSelectedBodyShadow(
                    canvas,
                    left,
                    top,
                    right,
                    bottom,
                    cornerRadius,
                    alpha * selectedAuraProgress
                )
            }
            if (selectedProgress > .001f) {
                interactionMatrix.reset()
                interactionMatrix.setScale(right - left, bottom - top)
                interactionMatrix.postTranslate(left, top)
                selectedFillShader.setLocalMatrix(interactionMatrix)
                fillPaint.shader = selectedFillShader
                fillPaint.alpha =
                    (alpha.coerceIn(0f, 1f) * selectedProgress * 255f).roundToInt()
                canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, fillPaint)
                fillPaint.shader = null
                fillPaint.alpha = 255
            }
            strokePaint.shader = null
            strokePaint.strokeWidth = density
            strokePaint.color = withAlpha(
                blendOpaqueColors(OUTLINE, PRIMARY_PURPLE, selectedProgress * .2f),
                alpha
            )
            canvas.drawRoundRect(
                left + .5f * density,
                top + .5f * density,
                right - .5f * density,
                bottom - .5f * density,
                (cornerRadius - .5f * density).coerceAtLeast(0f),
                (cornerRadius - .5f * density).coerceAtLeast(0f),
                strokePaint
            )
            if (selectedAuraProgress > .001f) {
                motionDrawing.drawSelectedGlyphGlow(
                    canvas,
                    icon,
                    visualCenterX,
                    visualCenterY,
                    24f * density,
                    alpha * selectedAuraProgress
                )
            }
            if (!transientPress) {
                iconAtlas.draw(
                    canvas,
                    icon,
                    visualCenterX,
                    visualCenterY,
                    24f * density,
                    withAlpha(
                        blendOpaqueColors(TEXT_SECONDARY, PRIMARY_BRIGHT, selectedProgress * .2f),
                        alpha
                    )
                )
            }
        }


    }

    private inner class SeekerDrawing {
        fun drawSeeker(canvas: Canvas, alpha: Float, duration: Long, position: Long) {
            if (seekerBottom <= seekerTop || controlRight <= controlLeft) return
            val pointerActive = seekerGestures.scrubPointerId != MotionEvent.INVALID_POINTER_ID
            val activeBoundary = seekerGestures.scrubLoopBoundary.takeIf { pointerActive }
                ?: scrubFineReleaseBoundary.takeIf { scrubFineReleaseActive }
            val fineOwnerActive = (pointerActive && seekerGestures.scrubSessionStarted) || scrubFineReleaseActive
            val timelineCenter = playerSurfaceFineTimelineCenterMs(
                fineOwnerActive,
                seekerGestures.scrubPreviewPositionMs,
                position
            )
            val loopStart = if (activeBoundary != null) {
                seekerGestures.scrubPreviewLoopStartMs
            } else {
                visibleLoopStartMs
            }
            val loopEnd = if (activeBoundary != null) {
                seekerGestures.scrubPreviewLoopEndMs
            } else {
                visibleLoopEndMs
            }
            val loopPresenceAlpha = alpha * loopClearanceProgress.coerceIn(0f, 1f)
            val growth = currentScrubGrowth(SystemClock.uptimeMillis())
            val fine = playerSurfaceFineLayerFraction(
                waveformMorph = waveformMorph,
                pointerActive = pointerActive,
                scrubSessionStarted = seekerGestures.scrubSessionStarted,
                releaseAnimating = scrubFineReleaseActive
            )
            val playheadHeld = (pointerActive && activeBoundary == null) ||
                (scrubGrowthBoundary == null && growth > 0f)
            val coarseThumbRadius = lerp(
                StemwiseSeekerDesign.PLAYHEAD_IDLE_DIAMETER_DP,
                StemwiseSeekerDesign.PLAYHEAD_HELD_DIAMETER_DP,
                if (playheadHeld) growth else 0f
            ) * density * .5f
            if (loopStart >= 0L) {
                prepareBoundaryPlaybackCursor(StemwiseSeekerDesign.PLAYHEAD_IDLE_DIAMETER_DP * density * .5f)
            }
            val coarseX = playerSurfaceSeekerPositionX(
                progress = if (duration > 0L) {
                    (position.toDouble() / duration.toDouble()).toFloat()
                } else {
                    0f
                },
                trackStartX = seekerTrackLeft,
                trackEndX = seekerTrackRight
            )
            val fineX = (seekerTrackLeft + seekerTrackRight) * .5f
            val fineViewport = playerSurfaceFineViewport(
                seekerTrackLeft,
                seekerTrackRight,
                StemwiseSeekerDesign.FINE_EDGE_INSET_DP * density
            )
            val playheadX = timelineX(position, duration, timelineCenter, fine)
            val activeCursorX = if (activeBoundary != null) {
                timelineX(seekerGestures.scrubPreviewPositionMs, duration, timelineCenter, fine)
            } else {
                playheadX
            }

            seekerDrawing.drawLoopRange(
                canvas,
                duration,
                timelineCenter,
                fine,
                loopPresenceAlpha,
                loopStart,
                loopEnd
            )
            val railY = seekerRailY()
            val playheadCursorY = lerp(
                seekerPlayheadCursorY(railY),
                (seekerTop + seekerBottom) * .5f,
                fine
            )
            val playheadThumbRadius = lerp(coarseThumbRadius, 3f * density, fine)
            val fineCursorWidth = (
                if (pointerActive) StemwiseSeekerDesign.FINE_CURSOR_HELD_WIDTH_DP
                else StemwiseSeekerDesign.FINE_CURSOR_IDLE_WIDTH_DP
                ) * density
            val coarseAlpha = alpha * if (waveformGeometry.hasWaveform(fullPlayerState.snapshot, timelineCenter,
                    duration)) (1f - fine) else 1f
            if (coarseAlpha > .001f) {
                // Normal mode is one unambiguous straight rail. Waveform bars belong exclusively to
                // the live Fine contact and are cross-faded in below.
                strokePaint.strokeWidth = 4f * density
                strokePaint.color = withAlpha(OUTLINE, coarseAlpha)
                canvas.drawLine(seekerTrackLeft, railY, seekerTrackRight, railY, strokePaint)
                strokePaint.color = withAlpha(PRIMARY_PURPLE, coarseAlpha)
                canvas.drawLine(
                    seekerTrackLeft,
                    railY,
                    if (activeBoundary == null) activeCursorX else coarseX,
                    railY,
                    strokePaint
                )
            }

            if (fine > .001f) {
                updateFineWaveformGeometry(timelineCenter, duration)
                strokePaint.strokeWidth = if (compactChrome) 1.35f * density else 1.65f * density
                strokePaint.color = withAlpha(TEXT_SECONDARY, alpha * .34f * fine)
                canvas.drawLines(waveformGeometry.points, 0, waveformGeometry.visiblePointCount, strokePaint)
                strokePaint.strokeWidth = density
                strokePaint.color = withAlpha(TEXT_SECONDARY, alpha * .46f * fine)
                val capTop = railY - StemwiseSeekerDesign.FINE_EDGE_CAP_HEIGHT_DP * density
                if (waveformGeometry.visiblePointCount > 0) {
                    val firstX = waveformGeometry.points[0]
                    val lastX = waveformGeometry.points[waveformGeometry.visiblePointCount - 4]
                    canvas.drawLine(firstX, capTop, firstX, railY, strokePaint)
                    canvas.drawLine(lastX, capTop, lastX, railY, strokePaint)
                }
                val save = canvas.save()
                canvas.clipRect(fineViewport.leftX, seekerTop, fineX, seekerBottom)
                strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha * fine)
                canvas.drawLines(waveformGeometry.points, 0, waveformGeometry.visiblePointCount, strokePaint)
                canvas.restoreToCount(save)
            }

            // Keep the full guide behind both the handle cursor and the actual playback cursor.
            seekerDrawing.drawLoopBoundaryGuides(canvas, duration, timelineCenter, fine, loopPresenceAlpha,
                loopStart, loopEnd)

            if (fine > .001f && activeBoundary != null) {
                strokePaint.strokeWidth = fineCursorWidth
                strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha * fine)
                canvas.drawLine(
                    activeCursorX,
                    seekerTop + 2f * density,
                    activeCursorX,
                    railY,
                    strokePaint
                )
            }

            if (activeBoundary == null) {
                strokePaint.strokeWidth = lerp(2f * density, fineCursorWidth, fine)
                strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
                canvas.drawLine(
                    activeCursorX,
                    lerp(railY, min(railY, seekerTop + 2f * density), fine),
                    activeCursorX,
                    railY,
                    strokePaint
                )
                if (playheadThumbRadius > .001f) {
                    fillPaint.shader = null
                    fillPaint.color = withAlpha(TEXT_PRIMARY, alpha)
                    canvas.drawCircle(activeCursorX, playheadCursorY, playheadThumbRadius, fillPaint)
                    strokePaint.strokeWidth = min(2f * density, playheadThumbRadius)
                    strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
                    canvas.drawCircle(activeCursorX, playheadCursorY, playheadThumbRadius, strokePaint)
                }
            }

            seekerDrawing.drawLoopBoundaryHandles(
                canvas = canvas,
                durationMs = duration,
                position = timelineCenter,
                fine = fine,
                alpha = loopPresenceAlpha,
                start = loopStart,
                end = loopEnd,
                selectedBoundary = activeBoundary ?: scrubGrowthBoundary?.takeIf { growth > 0f },
                selectionGrowth = growth
            )

            val boundaryPlaybackCursorVisible = activeBoundary != null && (
                fine <= .001f || playerSurfaceFinePlaybackCursorVisible(
                    playheadX,
                    fineViewport,
                    coarseThumbRadius
                )
                )
            if (boundaryPlaybackCursorVisible) {
                // A/B owns the Fine center. The real playhead is mapped into that same local viewport
                // and disappears once it is outside the visible proximity instead of becoming a
                // misleading full-song marker.
                val playbackCursorX = if (fine <= .001f) coarseX else playheadX
                val cursor = boundaryPlaybackCursorBitmap
                if (cursor != null) {
                    val half = cursor.width * .5f
                    boundaryPlaybackCursorDestination.set(playbackCursorX - half,
                        playheadCursorY - half, playbackCursorX + half, playheadCursorY + half)
                    boundaryPlaybackCursorPaint.alpha = (255f * alpha).roundToInt().coerceIn(0, 255)
                    canvas.drawBitmap(cursor, null, boundaryPlaybackCursorDestination,
                        boundaryPlaybackCursorPaint)
                }
            }

            if (!seekerOnly && expandedArtworkSizePx <= .01f) return
            textPaint.typeface = systemMediumTypeface
            textPaint.textSize = scaledTextSizePx(PLAYER_SURFACE_SEEKER_CLOCK_TEXT_SIZE_DP)
            textPaint.color = withAlpha(TEXT_SECONDARY, alpha)
            val detailedClock = playerSurfaceFineClockVisible(
                pointerActive = pointerActive,
                scrubSessionStarted = seekerGestures.scrubSessionStarted,
                fineEngaged = seekerGestures.scrubFineEngaged,
                scrubMode = seekerGestures.scrubMode
            )
            val displayedClockPosition = if (activeBoundary != null) seekerGestures.scrubPreviewPositionMs else position
            val clockLength = formatPlayerSurfaceClock(displayedClockPosition, detailedClock, clockChars)
            val durationLength = formatPlayerSurfaceClock(duration, detailedClock, durationChars)
            val baseline = playerSurfaceSeekerClockBaselineY(
                railY,
                textPaint.ascent(),
                density,
                loopPresenceAlpha
            )
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(clockChars, 0, clockLength, seekerTrackLeft, baseline, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(durationChars, 0, durationLength, seekerTrackRight, baseline, textPaint)

        }

        fun drawLoopRange(
            canvas: Canvas,
            durationMs: Long,
            position: Long,
            fine: Float,
            alpha: Float,
            start: Long,
            end: Long
        ) {
            if (start < 0L || durationMs <= 0L) return
            val left = timelineX(start, durationMs, position, fine)
            val right = if (end > start) {
                timelineX(end, durationMs, position, fine)
            } else {
                Float.NaN
            }
            if (right.isFinite()) {
                val visibleLeft = min(left, right).coerceIn(seekerTrackLeft, seekerTrackRight)
                val visibleRight = max(left, right).coerceIn(seekerTrackLeft, seekerTrackRight)
                if (visibleRight > visibleLeft) {
                    val rangeHeight = lerp(
                        StemwiseSeekerDesign.RANGE_NORMAL_HEIGHT_DP,
                        StemwiseSeekerDesign.RANGE_FINE_HEIGHT_DP,
                        fine
                    ) * density
                    val rangeAlpha = lerp(
                        StemwiseSeekerDesign.RANGE_NORMAL_ALPHA,
                        StemwiseSeekerDesign.RANGE_FINE_ALPHA,
                        fine
                    )
                    val railY = seekerRailY()
                    fillPaint.color = withAlpha(PRIMARY_PURPLE, alpha * rangeAlpha)
                    canvas.drawRoundRect(
                        visibleLeft,
                        railY - rangeHeight * .5f,
                        visibleRight,
                        railY + rangeHeight * .5f,
                        rangeHeight * .5f,
                        rangeHeight * .5f,
                        fillPaint
                    )
                }
            }
        }

        /**
         * Paint endpoint motors after the rail/waveform so their only draggable region stays obvious.
         * The thin vertical guides are deliberately passive; only the labelled A/B motors below the
         * playback cursor band own boundary gestures.
         */
        fun drawLoopBoundaryHandles(
            canvas: Canvas,
            durationMs: Long,
            position: Long,
            fine: Float,
            alpha: Float,
            start: Long,
            end: Long,
            selectedBoundary: FullPlayerLoopBoundary?,
            selectionGrowth: Float
        ) {
            if (start < 0L || durationMs <= 0L) return
            val top = loopEndpointHandleTop(seekerRailY(), fine)
            prepareLoopHandleShadow()
            fun drawEndpoint(time: Long, boundary: FullPlayerLoopBoundary, marker: String) {
                val detailX = timelineX(time, durationMs, position, 1f)
                val x = loopBoundaryTransitionX(time, durationMs, position, fine)
                val visibleAlpha = alpha * playerSurfaceLoopBoundaryTransitionAlpha(detailX,
                    seekerTrackLeft, seekerTrackRight, fine)
                if (visibleAlpha <= .001f) return
                val growth = playerSurfaceLoopHandleGrowth(boundary, selectedBoundary, selectionGrowth)
                val handleWidth = lerp(StemwiseSeekerDesign.LOOP_HANDLE_WIDTH_DP,
                    StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_WIDTH_DP, growth) * density
                seekerDrawing.drawLoopBoundaryHandle(canvas, x, marker, selectedBoundary == boundary, growth,
                    handleWidth, top, loopEndpointHandleBottom(top, growth), visibleAlpha)
            }
            drawEndpoint(start, FullPlayerLoopBoundary.START, "A")
            if (end > start) drawEndpoint(end, FullPlayerLoopBoundary.END, "B")
        }

        fun drawLoopBoundaryGuides(
            canvas: Canvas,
            durationMs: Long,
            position: Long,
            fine: Float,
            alpha: Float,
            start: Long,
            end: Long
        ) {
            if (start < 0L || durationMs <= 0L) return
            strokePaint.shader = null
            strokePaint.strokeWidth = 1.5f * density
            val railY = seekerRailY()
            val guideBottom = loopEndpointHandleTop(railY, fine)
            fun drawGuide(time: Long) {
                val detailX = timelineX(time, durationMs, position, 1f)
                val x = loopBoundaryTransitionX(time, durationMs, position, fine)
                val visibleAlpha = alpha * playerSurfaceLoopBoundaryTransitionAlpha(detailX,
                    seekerTrackLeft, seekerTrackRight, fine)
                strokePaint.color = withAlpha(PRIMARY_PURPLE, visibleAlpha * .88f)
                canvas.drawLine(x, railY, x, guideBottom, strokePaint)
            }
            drawGuide(start)
            if (end > start) drawGuide(end)
        }

        fun drawLoopBoundaryHandle(
            canvas: Canvas,
            x: Float,
            marker: String,
            selected: Boolean,
            selectionGrowth: Float,
            handleWidth: Float,
            handleTop: Float,
            handleBottom: Float,
            alpha: Float
        ) {
            if (selected && selectionGrowth > .001f) {
                val shadow = loopHandleShadowBitmap
                if (shadow != null) {
                    // ponytail: scale the held-size mask during growth; rerasterize only if exact intermediate blur is required.
                    val horizontalPadding = 24f * handleWidth /
                        StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_WIDTH_DP
                    val verticalPadding = 24f * (handleBottom - handleTop) /
                        StemwiseSeekerDesign.LOOP_HANDLE_DRAGGED_HEIGHT_DP
                    loopHandleShadowDestination.set(x - handleWidth * .5f - horizontalPadding,
                        handleTop + 8f * density - verticalPadding,
                        x + handleWidth * .5f + horizontalPadding,
                        handleBottom + 8f * density + verticalPadding)
                    loopHandleShadowPaint.alpha = (255f * alpha * selectionGrowth * .72f)
                        .roundToInt().coerceIn(0, 255)
                    canvas.drawBitmap(shadow, null, loopHandleShadowDestination, loopHandleShadowPaint)
                }
            }
            fillPaint.shader = null
            fillPaint.color = withAlpha(SURFACE, alpha)
            canvas.drawRoundRect(
                x - handleWidth * .5f,
                handleTop,
                x + handleWidth * .5f,
                handleBottom,
                StemwiseSeekerDesign.LOOP_HANDLE_RADIUS_DP * density,
                StemwiseSeekerDesign.LOOP_HANDLE_RADIUS_DP * density,
                fillPaint
            )
            strokePaint.strokeWidth = 1.5f * density
            strokePaint.color = withAlpha(PRIMARY_PURPLE, alpha)
            canvas.drawRoundRect(
                x - handleWidth * .5f,
                handleTop,
                x + handleWidth * .5f,
                handleBottom,
                StemwiseSeekerDesign.LOOP_HANDLE_RADIUS_DP * density,
                StemwiseSeekerDesign.LOOP_HANDLE_RADIUS_DP * density,
                strokePaint
            )
            textPaint.typeface = systemSemiboldTypeface
            textPaint.textSize = scaledTextSizePx(StemwiseSeekerDesign.LOOP_LABEL_MINIMUM_SP)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = withAlpha(PRIMARY_BRIGHT, alpha)
            canvas.drawText(
                marker,
                x,
                (handleTop + handleBottom) * .5f -
                    (textPaint.ascent() + textPaint.descent()) * .5f,
                textPaint
            )
        }

    }

    private companion object {
        val TOOL_ORDER = arrayOf(
            PlayerSurfaceTool.LOOP,
            PlayerSurfaceTool.TUNE,
            PlayerSurfaceTool.LYRICS,
            PlayerSurfaceTool.METRONOME
        )
        val TOOL_ICONS = arrayOf(
            PlayerSurfaceIcon.LOOP_AB,
            PlayerSurfaceIcon.TUNE,
            PlayerSurfaceIcon.LYRICS,
            PlayerSurfaceIcon.METRONOME
        )
        val TOOL_LABELS = arrayOf("Loop", app.stemwise.ui.PLAYER_DETAIL_TUNE, "Lyrics", app.stemwise.ui.PLAYER_DETAIL_METRONOME)
        const val MAX_WAVEFORM_BARS = 256
        const val FINE_VIEWPORT_MS = StemwiseSeekerDesign.FINE_WINDOW_MS
        const val MIXER_SLIDER_THUMB_RADIUS_DP = 7f
        const val PLACEHOLDER_WAVEFORM_AMPLITUDE = .08f
        const val MAX_IDENTITY_LAYOUT_WIDTH_PX = 16_384
        const val IDENTITY_MARQUEE_SPEED_DP_PER_SECOND = 20f


        const val BUTTON_NONE = 0
        const val BUTTON_SOURCE_ORIGINAL = 1
        const val BUTTON_SOURCE_STEMS = 2
        const val BUTTON_TRANSPORT = 3
        const val BUTTON_MINI_PLAY = 4
        const val MIXER_LIST_SCROLL_ONLY = -1
        const val BUTTON_MIXER_MUTE = 5
        const val BUTTON_MIXER_SOLO = 6
        const val BUTTON_MINI_BODY = 8
        const val BUTTON_ARTWORK_SKIP = 9

        const val PAGER_DRAG_GAIN = 1.14f
        const val PAGER_SPRING_STIFFNESS = 300f
        // Unit-mass damping coefficient: 2 * ratio * sqrt(stiffness).
        const val PAGER_SPRING_DAMPING = 31.1769f
        // A blur has no useful 512px edge detail. Quarter the allocation/upload work while the
        // same palette, opacity, spread and animation remain authoritative at draw time.
        const val NATIVE_TRANSIENT_VISUAL_OVERFLOW_DP = 38f
    }
}
