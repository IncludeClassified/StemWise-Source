package app.stemwise.ui


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.motionEventSpy
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.model.Song
import app.stemwise.model.asClock
import app.stemwise.analysis.BeatEvent
import app.stemwise.playback.METRONOME_BEAT_UNITS
import app.stemwise.playback.normalizedMetronomeBeatUnit
import app.stemwise.playback.normalizedMetronomeSubdivision
import app.stemwise.ui.theme.PrimaryBright
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.OutlineStrong
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.StemwisePalette
import app.stemwise.ui.theme.TextPrimary
import app.stemwise.ui.design.AuraAnchor
import app.stemwise.ui.design.StemwiseComposeGlyphs
import app.stemwise.ui.design.StemwiseAcceptedIconButton
import app.stemwise.ui.design.StemwiseSlider
import app.stemwise.ui.design.stemwiseAuraAcceptedClickableAction
import app.stemwise.ui.design.stemwiseAuraAcceptedSelectableAction
import app.stemwise.ui.design.stemwiseAuraAcceptedToggleableAction
import app.stemwise.ui.design.stemWiseInteractionAura
import app.stemwise.ui.theme.TextSecondary
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.semanticSpec
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.player.nativeplayer.*
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable
internal fun PlayerToolPresentationBridge(
    ownerKey: Any,
    selectedTool: State<String?>,
    onPresented: (String?) -> Unit
) {
    // This is the sole composition read of the button-row selection. Keeping it in this restart
    // scope prevents the immediate control acknowledgement from invalidating PlayerScreen.
    val selected = selectedTool.value
    val latestOnPresented by rememberUpdatedState(onPresented)
    LaunchedEffect(ownerKey, selected) {
        latestOnPresented(resolvedPlayerTool(selected))
    }
}

internal fun metronomeVisualBeatOrdinal(
    positionMs: Long,
    beatEvents: List<BeatEvent>,
    bpm: Float,
    subdivision: Int
): Long {
    if (positionMs < 0L) return -1L
    val normalizedSubdivision = normalizedMetronomeSubdivision(subdivision)
    if (beatEvents.isNotEmpty()) {
        var low = 0
        var high = beatEvents.lastIndex
        var beatIndex = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (beatEvents[middle].timeMs <= positionMs) {
                beatIndex = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (beatIndex < 0) return -1L
        val beatStartMs = beatEvents[beatIndex].timeMs
        val beatDurationMs = when {
            beatIndex < beatEvents.lastIndex ->
                (beatEvents[beatIndex + 1].timeMs - beatStartMs).coerceAtLeast(1L)
            beatIndex > 0 ->
                (beatStartMs - beatEvents[beatIndex - 1].timeMs).coerceAtLeast(1L)
            else -> (60_000f / (bpm.takeIf { it.isFinite() && it > 0f } ?: 120f)).roundToInt().toLong()
        }
        val subdivisionDurationMs =
            (beatDurationMs.toDouble() / normalizedSubdivision.toDouble()).coerceAtLeast(1.0)
        val subdivisionIndex =
            ((positionMs - beatStartMs).toDouble() / subdivisionDurationMs).toLong()
        if (subdivisionIndex >= normalizedSubdivision) return -1L
        return beatIndex.toLong() * normalizedSubdivision.toLong() + subdivisionIndex
    }
    val intervalMs = (60_000.0 / (bpm.takeIf { it.isFinite() && it > 0f } ?: 120f).toDouble() /
        normalizedSubdivision.toDouble()).coerceAtLeast(1.0)
    return (positionMs.toDouble() / intervalMs).toLong()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetronomePanel(
    enabled: Boolean,
    bpm: Float,
    bpmRange: ClosedFloatingPointRange<Float> = 40f..240f,
    subdivision: Int,
    numerator: Int,
    denominator: Int,
    volume: Float,
    onToggle: () -> Boolean,
    onBpm: (Float) -> Boolean,
    onSubdivision: (Int) -> Boolean,
    onVolume: (Float) -> Boolean,
    positionState: State<Long>? = null,
    beatEvents: List<BeatEvent> = emptyList(),
    meterSegments: List<app.stemwise.analysis.MeterSegment> = emptyList(),
    beatFeedbackEnabled: Boolean = true,
    compactRows: Boolean = false
) {
    val authoredMeter by remember(positionState, meterSegments) {
        derivedStateOf {
            val position = positionState?.value ?: 0L
            meterSegments.lastOrNull { it.startMs <= position }
        }
    }
    val visibleNumerator = authoredMeter?.numerator ?: numerator
    val visibleDenominator = authoredMeter?.denominator ?: denominator
    var draftBpm by remember(bpm, bpmRange.start, bpmRange.endInclusive) {
        mutableFloatStateOf(bpm.coerceIn(bpmRange.start, bpmRange.endInclusive))
    }
    var draftVolume by remember(volume) { mutableFloatStateOf(volume) }
    var bpmDragging by remember { mutableStateOf(false) }
    var volumeDragging by remember { mutableStateOf(false) }
    val motion = LocalStemwiseMotion.current
    val animatedBpm by animateFloatAsState(
        targetValue = draftBpm,
        animationSpec = if (bpmDragging) tween(0)
        else motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT),
        visibilityThreshold = .01f,
        label = "metronome-bpm-value"
    )
    val animatedVolume by animateFloatAsState(
        targetValue = draftVolume,
        animationSpec = if (volumeDragging) tween(0)
        else motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT),
        visibilityThreshold = .001f,
        label = "metronome-volume-value"
    )
    val visibleBpm = if (bpmDragging) draftBpm else animatedBpm
    val visibleVolume = if (volumeDragging) draftVolume else animatedVolume
    val bpmDrag = remember(bpmRange) { MetronomeBpmDrag(bpmRange) }
    val latestVisibleBpm by rememberUpdatedState(visibleBpm)
    val updateDraftBpm by rememberUpdatedState<(Float) -> Unit>({
        draftBpm = it.roundToInt().toFloat()
    })
    val bpmRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val beatToken = StemwiseSemanticMotion.token(StemwiseMotionEvent.METRONOME_BEAT)
    val beatOrdinal by remember(positionState, beatEvents, bpm, subdivision, enabled, beatFeedbackEnabled, motion.enabled) {
        derivedStateOf {
            if (!enabled || !beatFeedbackEnabled || !motion.enabled) return@derivedStateOf -1L
            metronomeVisualBeatOrdinal(
                positionMs = positionState?.value ?: -1L,
                beatEvents = beatEvents,
                bpm = bpm,
                subdivision = subdivision
            )
        }
    }
    val beatScale = remember { Animatable(1f) }
    LaunchedEffect(enabled, beatFeedbackEnabled, beatOrdinal, motion.enabled) {
        if (!enabled || !beatFeedbackEnabled || beatOrdinal < 0L || !motion.enabled) {
            beatScale.snapTo(1f)
            return@LaunchedEffect
        }
        val halfDuration = beatToken.durationMs / 2
        beatScale.animateTo(
            targetValue = 1.08f,
            animationSpec = tween(
                durationMillis = halfDuration,
                easing = beatToken.easing.curve
            )
        )
        beatScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = beatToken.durationMs - halfDuration,
                easing = beatToken.easing.curve
            )
        )
    }
    val metrics = CompactMetronomeMetrics
    val metronomePowerInteractionSource = remember { MutableInteractionSource() }
    val selectedBrush = remember {
        Brush.linearGradient(
            listOf(
                StemwisePalette.selectedLibraryRowGradientStart,
                StemwisePalette.selectedLibraryRowGradientEnd
            )
        )
    }
    val powerSelectedProgress = semanticSelectedProgress(enabled, "metronome-power")
    val powerAndBpm: @Composable () -> Unit = {
        Row(
            Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val powerShape = remember {
                RoundedCornerShape(metrics.POWER_CORNER_RADIUS_DP.dp)
            }
            Box(
                Modifier
                    .size(metrics.POWER_SIZE_DP.dp)
                    .shadow(
                        elevation = (12f * powerSelectedProgress).dp,
                        shape = powerShape,
                        clip = false,
                        ambientColor = PrimaryPurple.copy(alpha = .18f),
                        spotColor = PrimaryPurple.copy(alpha = .32f)
                    )
                    .stemWiseInteractionAura(
                        enabled = true,
                        selected = false,
                        interactionSource = metronomePowerInteractionSource,
                        anchor = AuraAnchor.VisibleControl,
                        reducedMotion = !motion.enabled,
                    )
                    .stemwiseAuraAcceptedToggleableAction(
                        interactionSource = metronomePowerInteractionSource,
                        value = enabled,
                        role = Role.Switch,
                        onValueChange = { onToggle() }
                    )
                    .semanticSelectedSurface(
                        progress = powerSelectedProgress,
                        shape = powerShape,
                        selectedBrush = selectedBrush,
                        selectedBorderWidthDp = 1.5f
                    )
                    .semantics {
                        contentDescription = "Metronome"
                        stateDescription = if (enabled) "On" else "Off"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = StemwiseIcons.Metronome,
                    contentDescription = null,
                    modifier = Modifier
                        .size(metrics.POWER_GLYPH_SIZE_DP.dp)
                        .stemWiseInteractionAura(
                            enabled = true,
                            selected = enabled,
                            interactionSource = metronomePowerInteractionSource,
                            anchor = AuraAnchor.GlyphLocalized,
                            reducedMotion = !motion.enabled,
                            paintTransientPress = false,
                        )
                        .graphicsLayer {
                            scaleX = beatScale.value
                            scaleY = beatScale.value
                        },
                    tint = lerp(TextSecondary, PrimaryBright, powerSelectedProgress)
                )
            }
            Column(
                Modifier.widthIn(min = 52.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    visibleBpm.roundToInt().toString(),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    maxLines = 1
                )
                Text(
                    androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_bpm), color = TextSecondary, fontSize = 12.sp,
                    lineHeight = 16.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
    val bpmControls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Row(controlsModifier, verticalAlignment = Alignment.CenterVertically) {
            MeterStepIcon(
                description = "Decrease metronome BPM",
                icon = StemwiseIcons.BeatsDecrease,
                enabled = draftBpm > bpmRange.start,
                onClick = {
                    val updated = metronomeBpmStepValue(draftBpm, -1, bpmRange)
                    if (updated != draftBpm) {
                        onBpm(updated).also { accepted ->
                            if (accepted) draftBpm = updated
                        }
                    } else false
                }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clearAndSetSemantics {
                        val semanticBpm = draftBpm.roundToInt().toFloat()
                            .coerceIn(bpmRange.start, bpmRange.endInclusive)
                        contentDescription = "Metronome BPM"
                        stateDescription = metronomeBpmLabel(semanticBpm)
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = semanticBpm,
                            range = bpmRange,
                            steps = (bpmRange.endInclusive - bpmRange.start)
                                .roundToInt().minus(1).coerceAtLeast(0)
                        )
                        setProgress { requested ->
                            val normalized = metronomeBpmSetProgressValue(requested, bpmRange)
                                ?: return@setProgress false
                            if (normalized == semanticBpm) {
                                false
                            } else {
                                onBpm(normalized).also { accepted ->
                                    if (accepted) draftBpm = normalized
                                }
                            }
                        }
                    }
            ) {
                StemwiseSlider(
                    value = visibleBpm,
                    onValueChange = {
                        bpmDragging = true
                        draftBpm = bpmDrag.apply(it).roundToInt().toFloat()
                        true
                    },
                    onValueChangeFinished = {
                        bpmDrag.finish()
                        bpmDragging = false
                        val accepted = onBpm(draftBpm)
                        if (!accepted) draftBpm = bpm
                        accepted
                    },
                    hapticStep = 1f,
                    hapticValue = { draftBpm },
                    deferHapticUntilCommit = true,
                    valueRange = bpmRange,
                    colors = SliderDefaults.colors(
                        thumbColor = TextPrimary,
                        activeTrackColor = PrimaryPurple,
                        inactiveTrackColor = Outline
                    ),
                    modifier = Modifier.fillMaxSize().pointerInput(bpmDrag, bpmRtl) {
                        // Observe horizontal motion without consuming Material's gesture or aura.
                        awaitPointerEventScope {
                            var pointerId: androidx.compose.ui.input.pointer.PointerId? = null
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (pointerId == null) {
                                    event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                        ?.let { down ->
                                            pointerId = down.id
                                            bpmDrag.begin(down.position.x, down.uptimeMillis,
                                                latestVisibleBpm, size.width.toFloat(), density, bpmRtl)
                                        }
                                } else {
                                    val pointer = event.changes.firstOrNull { it.id == pointerId }
                                    if (pointer != null) {
                                        bpmDrag.observe(pointer.position.x, pointer.uptimeMillis)
                                            ?.let(updateDraftBpm)
                                    }
                                    if (pointer == null || !pointer.pressed) pointerId = null
                                }
                            }
                        }
                    }
                )
            }
            MeterStepIcon(
                description = "Increase metronome BPM",
                icon = StemwiseIcons.BeatsIncrease,
                enabled = draftBpm < bpmRange.endInclusive,
                onClick = {
                    val updated = metronomeBpmStepValue(draftBpm, 1, bpmRange)
                    if (updated != draftBpm) {
                        onBpm(updated).also { accepted ->
                            if (accepted) draftBpm = updated
                        }
                    } else false
                }
            )

        }
    }
    val fontScale = LocalDensity.current.fontScale
    val largeText = fontScale >= 1.5f
    val volumeControl: @Composable (Modifier) -> Unit = { volumeModifier ->
        Column(
                volumeModifier, verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_volume), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clearAndSetSemantics {
                            val semanticVolume = draftVolume.coerceIn(0f, 1f)
                            contentDescription = "Metronome volume"
                            stateDescription = "${(semanticVolume * 100).roundToInt()} percent"
                            progressBarRangeInfo = ProgressBarRangeInfo(semanticVolume, 0f..1f)
                            setProgress { requested ->
                                val normalized = metronomeVolumeSetProgressValue(requested)
                                    ?: return@setProgress false
                                if (normalized == semanticVolume) {
                                    false
                                } else {
                                    onVolume(normalized).also { accepted ->
                                        if (accepted) draftVolume = normalized
                                    }
                                }
                            }
                        }
                ) {
                    StemwiseSlider(
                        value = visibleVolume,
                        onValueChange = {
                            volumeDragging = true
                            draftVolume = it
                            true
                        },
                        onValueChangeFinished = {
                            volumeDragging = false
                            val accepted = onVolume(draftVolume)
                            if (!accepted) draftVolume = volume
                            accepted
                        },
                        valueRange = 0f..1f,
                        valueLabel = "${(visibleVolume * 100).roundToInt()}%",
                        modifier = Modifier.fillMaxSize(),
                        colors = SliderDefaults.colors(
                            thumbColor = TextPrimary,
                            activeTrackColor = PrimaryPurple,
                            inactiveTrackColor = Outline
                        )
                    )
                }
            }
    }
    val overflowScroll = rememberScrollState()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(overflowScroll, enabled = overflowScroll.maxValue > 0, overscrollEffect = null)
            .padding(vertical = if (compactRows) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactRows) 4.dp else 8.dp)
    ) {
        if (largeText) {
            powerAndBpm()
            bpmControls(Modifier.fillMaxWidth())
        } else {
            FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                powerAndBpm()
                bpmControls(Modifier.weight(1f).widthIn(min = (metrics.METER_TOUCH_TARGET_SIZE_DP * 4).dp))
            }
        }
        if (largeText) {
            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_subdivision), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!largeText) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_subdivision), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            MetronomeSubdivisionCards(
                selected = subdivision,
                beatUnitDenominator = visibleDenominator,
                onSelected = onSubdivision,
                horizontalGapDp = 4f,
                cardWidthDp = if (fontScale > 1f) 64f else 72f
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_beats), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text(visibleNumerator.toString(), color = TextPrimary, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_beat_unit), color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text(beatUnitName(normalizedMetronomeBeatUnit(visibleDenominator)), color = TextPrimary, fontSize = 18.sp)
            }
            if (compactRows && !largeText) volumeControl(Modifier.weight(1f))
        }
        if (!compactRows || largeText) volumeControl(Modifier.fillMaxWidth())
    }
}


@Composable
private fun MeterStepIcon(
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(CompactMetronomeMetrics.METER_TOUCH_TARGET_SIZE_DP.dp)
            .stemWiseInteractionAura(
                enabled = enabled,
                selected = false,
                interactionSource = interactionSource,
                anchor = AuraAnchor.VisibleControl,
                reducedMotion = !motion.enabled,
            )
            .stemwiseAuraAcceptedClickableAction(
                interactionSource = interactionSource,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) TextPrimary else StemwisePalette.disabledStepContent,
            modifier = Modifier.size(24.dp)
        )
    }
}


@Composable
private fun MetronomeSubdivisionCards(
    selected: Int,
    beatUnitDenominator: Int,
    onSelected: (Int) -> Boolean,
    horizontalGapDp: Float,
    cardWidthDp: Float,
    modifier: Modifier = Modifier
) {
    val motion = LocalStemwiseMotion.current
    val selectedBrush = remember {
        Brush.linearGradient(
            listOf(
                StemwisePalette.selectedLibraryRowGradientStart,
                StemwisePalette.selectedLibraryRowGradientEnd
            )
        )
    }
    Row(
        modifier
            .width(
                (cardWidthDp * 3f +
                    horizontalGapDp * 2f).dp
            )
            .height(CompactMetronomeMetrics.SUBDIVISION_CARD_HEIGHT_DP.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(horizontalGapDp.dp)
    ) {
        listOf(1, 2, 4).forEach { value ->
            val active = selected == value
            val selectedProgress = semanticSelectedProgress(
                selected = active,
                label = "metronome-subdivision-$value"
            )
            val noteName = subdivisionNoteName(beatUnitDenominator, value)
            val interactionSource = remember(value) { MutableInteractionSource() }
            val shape = remember(value) {
                RoundedCornerShape(
                    CompactMetronomeMetrics.SUBDIVISION_CARD_CORNER_RADIUS_DP.dp
                )
            }
            Box(
                Modifier
                    .size(
                        width = cardWidthDp.dp,
                        height = CompactMetronomeMetrics.SUBDIVISION_CARD_HEIGHT_DP.dp
                    )
                    .stemWiseInteractionAura(
                        enabled = true,
                        selected = false,
                        interactionSource = interactionSource,
                        anchor = AuraAnchor.VisibleControl,
                        reducedMotion = !motion.enabled,
                    )
                    .stemwiseAuraAcceptedSelectableAction(
                        selected = active,
                        interactionSource = interactionSource,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) }
                    )
                    .semanticSelectedSurface(
                        progress = selectedProgress,
                        shape = shape,
                        selectedBrush = selectedBrush
                    )
                    .semantics {
                        contentDescription =
                            "$value click${if (value == 1) "" else "s"} per beat, " +
                                "each ${noteName.lowercase()}"
                        stateDescription = if (active) "Selected" else "Not selected"
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (value) {
                        1 -> StemwiseIcons.SubdivisionOne
                        2 -> StemwiseIcons.SubdivisionTwo
                        else -> StemwiseIcons.SubdivisionFour
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .stemWiseInteractionAura(
                            enabled = true,
                            selected = active,
                            interactionSource = interactionSource,
                            anchor = AuraAnchor.GlyphLocalized,
                            reducedMotion = !motion.enabled,
                            paintTransientPress = false,
                        ),
                    tint = lerp(TextPrimary, PrimaryBright, selectedProgress)
                )
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompactLoopPanel(
    loopBEnabled: Boolean,
    loopA: Long?,
    loopB: Long?,
    onSetA: () -> Boolean,
    onSetB: () -> Boolean,
    onClear: () -> Boolean
) {
    val motion = LocalStemwiseMotion.current
    val clearInteractionSource = remember { MutableInteractionSource() }
    val minimumPointWidth = 144f * normalizedPlayerFontScale(LocalDensity.current.fontScale)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LoopPointButton("A", loopA?.asClock(true), loopA != null,
                    Modifier.weight(1f).widthIn(min = minimumPointWidth.dp), onClick = onSetA)
                LoopPointButton("B", loopB?.asClock(true), loopB != null,
                    Modifier.weight(1f).widthIn(min = minimumPointWidth.dp), enabled = loopBEnabled, onClick = onSetB)
            }
            val clearEnabled = loopA != null || loopB != null
            Row(Modifier.fillMaxWidth().height(48.dp)
                .stemWiseInteractionAura(enabled = clearEnabled, selected = false,
                    interactionSource = clearInteractionSource, anchor = AuraAnchor.VisibleControl,
                    reducedMotion = !motion.enabled)
                .stemwiseAuraAcceptedClickableAction(interactionSource = clearInteractionSource,
                    enabled = clearEnabled, onClick = onClear),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(StemwiseIcons.LoopClear, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = if (clearEnabled) TextSecondary else StemwisePalette.disabledControlContent)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_clear_loop),
                    color = if (clearEnabled) TextPrimary else StemwisePalette.disabledControlContent,
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LoopPointButton(
    marker: String,
    time: String?,
    active: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Boolean
) {
    val motion = LocalStemwiseMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val selected = active && enabled
    val selectedProgress = semanticSelectedProgress(selected, "loop-point-$marker")
    Row(
        modifier
            .height(48.dp)
            .stemWiseInteractionAura(
                enabled = enabled,
                selected = false,
                interactionSource = interactionSource,
                anchor = AuraAnchor.VisibleControl,
                reducedMotion = !motion.enabled,
            )
            .stemwiseAuraAcceptedClickableAction(
                interactionSource = interactionSource,
                enabled = enabled,
                onClick = onClick
            )
            .semantics {
                contentDescription = "Set loop point $marker"
                stateDescription = time ?: "Not set"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val markerColor = when {
            !enabled -> StemwisePalette.disabledStepContent
            else -> lerp(TextSecondary, PrimaryBright, selectedProgress)
        }
        Icon(
            imageVector = if (marker == "A") {
                StemwiseIcons.LoopSetA
            } else {
                StemwiseIcons.LoopSetB
            },
            contentDescription = null,
            tint = markerColor,
            modifier = Modifier
                .size(24.dp)
                .stemWiseInteractionAura(
                    enabled = enabled,
                    selected = selected,
                    interactionSource = interactionSource,
                    anchor = AuraAnchor.GlyphLocalized,
                    reducedMotion = !motion.enabled,
                    paintTransientPress = false,
                )
        )
        Spacer(Modifier.width(12.dp))
        Text(time ?: "Set $marker", color = if (enabled) TextPrimary else StemwisePalette.disabledControlContent,
            fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    }
}

@Composable
internal fun KeyPanel(song: Song, pitch: Int, onPitch: (Int) -> Boolean, onValueChangeFinished: () -> Boolean = { true }) {
    val uncertainty = song.keyReason?.takeIf { song.keyStatus == "uncertain" }
    Column {
        CompactTuneValueRow(
            label = "Key",
            rowHeightDp = CompactTuneMetrics.KEY_ROW_HEIGHT_DP,
            valueText = tuneKeyValue(song.key, pitch),
            valueDescription = listOfNotNull(qualifiedAnalysisValue(tuneKeyValue(song.key, pitch), song.keyStatus), uncertainty)
                .joinToString(" · "),
            value = pitch.toFloat(),
            range = -12f..12f,
            color = PrimaryPurple,
            steps = 23,
            centeredTrack = true,
            tickDiameterDp = 6f,
            resetEnabled = pitch != 0,
            onValueChange = { onPitch(it.roundToInt()) },
            onReset = { onPitch(0) },
            onValueChangeFinished = onValueChangeFinished
        )
        if (song.keyStatus == "uncertain") Text(
            listOfNotNull("Uncertain", uncertainty).joinToString(" · "),
            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

internal fun qualifiedAnalysisValue(value: String, status: String?): String =
    if (status == "uncertain") "$value (uncertain)" else value

@Composable
internal fun SpeedPanel(tempo: Float, onTempo: (Float) -> Boolean,
    onValueChangeFinished: () -> Boolean = { true }, compactRows: Boolean = false) {
    CompactTuneValueRow(
        label = "Tempo",
        rowHeightDp = if (compactRows) CompactTuneMetrics.COMPACT_ROW_HEIGHT_DP else CompactTuneMetrics.SPEED_ROW_HEIGHT_DP,
        valueText = tuneSpeedValue(tempo),
        showValue = false,
        compactRows = compactRows,
        value = tempo,
        range = MIN_TUNE_TEMPO..MAX_TUNE_TEMPO,
        color = PrimaryPurple,
        resetEnabled = tuneTempoActive(tempo),
        onValueChange = onTempo,
        onReset = { onTempo(1f) },
        onValueChangeFinished = onValueChangeFinished
    )
}

internal fun tuneSpeedValue(tempo: Float): String =
    String.format(Locale.ROOT, "%.2f", tempo.takeIf(Float::isFinite)?.coerceIn(MIN_TUNE_TEMPO, MAX_TUNE_TEMPO) ?: 1f)
        .trimEnd('0').trimEnd('.') + "×"

/** A visible pitch result even when song metadata has no detected musical key. */
internal fun tuneKeyValue(originalKey: String?, pitch: Int): String {
    val shift = pitch.coerceIn(-12, 12)
    if (originalKey.isNullOrBlank()) {
        return when {
            shift > 0 -> "+$shift st"
            shift < 0 -> "$shift st"
            else -> "0 st"
        }
    }
    return transposedKey(originalKey, shift)
}

internal fun metronomeBpmLabel(bpm: Float): String =
    "${(bpm.takeIf { it.isFinite() && it > 0f } ?: 120f).roundToInt()} BPM"

internal fun metronomeBpmSetProgressValue(
    requested: Float,
    range: ClosedFloatingPointRange<Float> = 40f..240f
): Float? = requested
    .takeIf(Float::isFinite)
    ?.coerceIn(range.start, range.endInclusive)
    ?.roundToInt()
    ?.toFloat()
    ?.coerceIn(range.start, range.endInclusive)

internal fun metronomeBpmStepValue(
    current: Float,
    delta: Int,
    range: ClosedFloatingPointRange<Float> = 40f..240f
): Float = ((current.takeIf(Float::isFinite) ?: range.start).roundToInt() + delta)
    .toFloat()
    .coerceIn(range.start, range.endInclusive)

internal fun metronomeVolumeSetProgressValue(requested: Float): Float? = requested
    .takeIf(Float::isFinite)
    ?.coerceIn(0f, 1f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactTuneValueRow(
    label: String,
    rowHeightDp: Float,
    valueText: String,
    valueDescription: String = valueText,
    showValue: Boolean = true,
    compactRows: Boolean = false,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    color: Color,
    steps: Int = 0,
    centeredTrack: Boolean = false,
    tickDiameterDp: Float = 6f,
    resetEnabled: Boolean,
    onValueChange: (Float) -> Boolean,
    onReset: () -> Boolean,
    onValueChangeFinished: () -> Boolean = { true }
) {
    val motion = LocalStemwiseMotion.current
    var directManipulation by remember { mutableStateOf(false) }
    val animatedValue by animateFloatAsState(
        targetValue = value,
        animationSpec = if (directManipulation) tween(0)
        else motion.semanticSpec(StemwiseMotionEvent.STATE_CHANGE_DEFAULT),
        visibilityThreshold = .001f,
        label = "${label.lowercase(Locale.ROOT)}-slider-value"
    )
    val displayedValue = if (directManipulation) value else animatedValue
    val resetButton: @Composable () -> Unit = {
        StemwiseAcceptedIconButton(
            onClick = { onReset() && onValueChangeFinished() },
            enabled = resetEnabled,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = "Reset ${label.lowercase(Locale.ROOT)}"
                    role = Role.Button
                    if (!resetEnabled) disabled()
                }
        ) {
            Icon(
                StemwiseIcons.TuneReset,
                contentDescription = null,
                tint = if (resetEnabled) TextSecondary
                else StemwisePalette.disabledControlContent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = rowHeightDp.dp),
        maxItemsInEachRow = if (compactRows) 3 else 1
    ) {
        Row(
            Modifier.then(if (compactRows) Modifier.width(84.dp) else Modifier.fillMaxWidth())
                .heightIn(min = if (compactRows) 48.dp else 52.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                label, color = TextSecondary, fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f)
            )
            if (showValue) Text(
                valueText, color = TextPrimary, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1
            )
            if (!compactRows) resetButton()
        }
        Box(
            modifier = Modifier
                .height(48.dp)
                .then(if (compactRows) Modifier.weight(1f) else Modifier.fillMaxWidth())
                .clearAndSetSemantics {
                    val semanticValue = value.coerceIn(range.start, range.endInclusive)
                    contentDescription = "$label adjustment"
                    stateDescription = valueDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = semanticValue,
                        range = range,
                        steps = steps
                    )
                    setProgress { requested ->
                        if (!requested.isFinite()) return@setProgress false
                        val normalized = requested.coerceIn(range.start, range.endInclusive)
                        if (normalized == semanticValue) {
                            false
                        } else {
                            onValueChange(normalized) && onValueChangeFinished()
                        }
                    }
                }
        ) {
            StemwiseSlider(
                value = displayedValue,
                onValueChange = {
                    directManipulation = true
                    onValueChange(it)
                },
                onValueChangeFinished = {
                    directManipulation = false
                    onValueChangeFinished()
                },
                valueRange = range,
                steps = steps,
                centeredTrack = centeredTrack,
                tickDiameterDp = tickDiameterDp,
                colors = SliderDefaults.colors(
                    thumbColor = color,
                    activeTrackColor = color,
                    inactiveTrackColor = StemwisePalette.tuneSliderTrack,
                    activeTickColor = TextSecondary,
                    inactiveTickColor = TextSecondary
                ),
                modifier = Modifier.fillMaxSize()
            )
        }
        if (compactRows) resetButton()
    }
}

/** Body scrolling cannot move or cover the measured transport footer. */
@Composable
internal fun PlayerTransportViewport(
    transport: @Composable () -> Unit,
    body: @Composable (Modifier) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { body(Modifier.fillMaxSize()) }
        transport()
    }
}

/** Expanded tools use the same command owner and authoritative transport as the native surface. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerOverlayTransport(
    generation: Long,
    durationMs: Long,
    positionState: State<Long>,
    transportState: Flow<PlayerTransportUiState>,
    actionSink: FullPlayerActionSink
) {
    val transport by transportState.collectAsStateWithLifecycle(initialValue = PlayerTransportUiState())
    var preview by remember(generation, actionSink) { mutableStateOf<Long?>(null) }
    DisposableEffect(generation, actionSink) {
        onDispose { if (preview != null) actionSink.onScrubCancel(generation) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val position = (preview ?: positionState.value).coerceIn(0L, durationMs.coerceAtLeast(0L))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(position.asClock(), color = TextSecondary, fontSize = 12.sp)
            Text(if (durationMs > 0L) durationMs.asClock() else "—", color = TextSecondary, fontSize = 12.sp)
        }
        StemwiseSlider(
            value = position.toFloat(),
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L,
            onValueChange = { next ->
                val accepted = (preview != null || actionSink.onScrubBegin(generation)) &&
                    actionSink.onScrubPreview(generation, next.toLong())
                if (accepted) preview = next.toLong()
                accepted
            },
            onValueChangeFinished = {
                val target = preview
                preview = null
                target != null && actionSink.onScrubCommit(generation, target)
            },
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Playback position" }
                .motionEventSpy { event ->
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL && preview != null) {
                        actionSink.onScrubCancel(generation)
                        preview = null
                    }
                }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            StemwiseAcceptedIconButton(onClick = {
                actionSink.onTransport(generation, PlayerSurfaceTransportCommand.SHUFFLE_TOGGLE)
            }, selected = transport.shuffleEnabled, modifier = Modifier.size(48.dp).semantics {
                stateDescription = if (transport.shuffleEnabled) "On" else "Off"
            }) {
                Icon(StemwiseIcons.Shuffle, contentDescription = "Shuffle",
                    tint = if (transport.shuffleEnabled) PrimaryPurple else TextSecondary)
            }
            StemwiseAcceptedIconButton(onClick = {
                actionSink.onTransport(generation, PlayerSurfaceTransportCommand.PREVIOUS)
            }, enabled = transport.previousEnabled, modifier = Modifier.size(48.dp)) {
                Icon(StemwiseIcons.Previous, contentDescription = "Previous", tint = TextPrimary)
            }
            StemwiseAcceptedIconButton(onClick = {
                actionSink.onTransport(generation, PlayerSurfaceTransportCommand.PLAY_PAUSE)
            }, modifier = Modifier.size(68.dp)) {
                Icon(if (transport.playWhenReady) StemwiseIcons.Pause else StemwiseIcons.Play,
                    contentDescription = if (transport.playWhenReady) "Pause" else "Play",
                    tint = TextPrimary, modifier = Modifier.size(32.dp))
            }
            StemwiseAcceptedIconButton(onClick = {
                actionSink.onTransport(generation, PlayerSurfaceTransportCommand.NEXT)
            }, enabled = transport.canGoNext, modifier = Modifier.size(48.dp)) {
                Icon(StemwiseIcons.Next, contentDescription = "Next", tint = TextPrimary)
            }
            StemwiseAcceptedIconButton(onClick = {
                actionSink.onTransport(generation, PlayerSurfaceTransportCommand.REPEAT_CYCLE)
            }, selected = transport.repeatMode != "Off", modifier = Modifier.size(48.dp).semantics {
                stateDescription = transport.repeatMode
            }) {
                Icon(StemwiseComposeGlyphs.icon(playerSurfaceRepeatMode(transport.repeatMode).transportIcon().catalogRole()), contentDescription = "Repeat",
                    tint = if (transport.repeatMode != "Off") PrimaryPurple else TextSecondary)
            }
        }
    }
}
