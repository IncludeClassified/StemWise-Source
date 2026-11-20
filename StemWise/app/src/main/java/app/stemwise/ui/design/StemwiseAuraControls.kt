@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.stemwise.ui.design

import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.Surface
import app.stemwise.ui.theme.Outline
import app.stemwise.ui.theme.TextPrimary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val STEMWISE_SLIDER_TRACK_HEIGHT_DP = 4f
private const val STEMWISE_SLIDER_TICK_DIAMETER_DP = 6f

internal object StemwiseSliderValueLabelMetrics {
    const val MIN_HEIGHT_DP = 28f
    const val MIN_WIDTH_DP = 44f
    const val HORIZONTAL_PADDING_DP = 8f
    const val VERTICAL_PADDING_DP = 4f
    const val CORNER_RADIUS_DP = 8f
    const val TEXT_SIZE_SP = 14f
    const val LINE_HEIGHT_SP = 20f
    const val THUMB_GAP_DP = 8f
    const val LIFT_DP = 4f
    const val FADE_DURATION_MS = 120
}

internal fun stemwiseSliderTickFractions(steps: Int): FloatArray =
    if (steps <= 0) FloatArray(0) else FloatArray(steps + 2) { index ->
        index.toFloat() / (steps + 1).toFloat()
    }

internal fun stemwiseSliderActiveTrackRange(
    progress: Float,
    centered: Boolean
): ClosedFloatingPointRange<Float> {
    val end = progress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val start = if (centered) .5f else 0f
    return min(start, end)..max(start, end)
}

/** Material Button with one interaction source shared by the control and its shape aura. */
@Composable
internal fun StemwiseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    paintInteractionAura: Boolean = true,
    emitAcceptedFeedback: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val ownedInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: ownedInteractionSource
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialButton(
            onClick = {
                if (emitAcceptedFeedback) {
                    resolvedInteractionSource.emitAcceptedAuraFeedback(enabled)
                }
                onClick()
            },
            modifier = if (paintInteractionAura) {
                modifier.stemWiseInteractionAura(
                    enabled = enabled,
                    selected = false,
                    interactionSource = resolvedInteractionSource,
                    anchor = AuraAnchor.VisibleControl,
                    reducedMotion = !LocalStemwiseMotion.current.enabled
                )
            } else {
                modifier
            },
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            contentPadding = contentPadding,
            interactionSource = resolvedInteractionSource,
            content = content
        )
    }
}

/** Material TextButton with one interaction source shared by the full pill and its aura. */
@Composable
internal fun StemwiseTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialTextButton(
            onClick = {
                interactionSource.emitAcceptedAuraFeedback(enabled)
                onClick()
            },
            modifier = modifier.stemWiseInteractionAura(
                    enabled = enabled,
                    selected = false,
                    interactionSource = interactionSource,
                    anchor = AuraAnchor.VisibleControl,
                    reducedMotion = !LocalStemwiseMotion.current.enabled
                ),
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content
        )
    }
}

/** Material icon action with a full-circle press aura and glyph-localized selected glow. */
@Composable
internal fun StemwiseIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) = StemwiseAcceptedIconButton(
    onClick = {
        onClick()
        true
    },
    modifier = modifier,
    enabled = enabled,
    selected = selected,
    colors = colors,
    content = content
)

/** Icon action whose authoritative owner can reject a command without producing press feedback. */
@Composable
internal fun StemwiseAcceptedIconButton(
    onClick: () -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        MaterialIconButton(
            onClick = {
                performAcceptedAuraAction(
                    enabled = enabled,
                    action = onClick,
                    onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
                )
            },
            modifier = modifier.stemWiseInteractionAura(
                    enabled = enabled,
                    selected = false,
                    interactionSource = interactionSource,
                    anchor = AuraAnchor.VisibleControl,
                    reducedMotion = !LocalStemwiseMotion.current.enabled
                ),
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
        ) {
            Box(
                Modifier.stemWiseInteractionAura(
                    enabled = enabled,
                    selected = selected,
                    interactionSource = interactionSource,
                    anchor = AuraAnchor.GlyphLocalized,
                    reducedMotion = !LocalStemwiseMotion.current.enabled,
                    paintTransientPress = false
                )
            ) { content() }
        }
    }
}

/**
 * Material Slider whose aura center is measured from the rendered thumb. The same
 * [interactionSource] drives Material's gesture and the shared aura, and the thumb callback
 * publishes its rendered center during that layout frame.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun StemwiseSlider(
    value: Float,
    onValueChange: (Float) -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    centeredTrack: Boolean = false,
    tickDiameterDp: Float = STEMWISE_SLIDER_TICK_DIAMETER_DP,
    onValueChangeFinished: (() -> Boolean)? = null,
    hapticStep: Float? = null,
    hapticValue: (Float) -> Float = { it },
    deferHapticUntilCommit: Boolean = false,
    colors: SliderColors = SliderDefaults.colors(),
    valueLabel: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val feedback = remember { StemwiseSliderFeedback() }
    val feedbackStep = hapticStep ?: if (steps > 0) (valueRange.endInclusive - valueRange.start) / (steps + 1) else null
    val hapticView = LocalView.current
    val thumbAura = remember { mutableStateOf<DragAuraState?>(null) }
    var sliderCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val thumbScale by animateFloatAsState(
        targetValue = if (enabled && thumbAura.value?.active == true) 1.5f else 1f,
        animationSpec = tween(if (LocalStemwiseMotion.current.enabled) 120 else 0),
        label = "slider-thumb-contact"
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (enabled && valueLabel != null && thumbAura.value?.active == true) 1f else 0f,
        animationSpec = tween(if (LocalStemwiseMotion.current.enabled) StemwiseSliderValueLabelMetrics.FADE_DURATION_MS else 0),
        label = "slider-value-label"
    )
    LaunchedEffect(enabled) {
        if (!enabled) {
            thumbAura.value = thumbAura.value?.copy(active = false)
            feedback.finish(false, deferHapticUntilCommit)
        }
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    MaterialSlider(
        value = value,
        onValueChange = { next ->
            if (enabled && next != value) {
                val previousHapticValue = hapticValue(value)
                val accepted = onValueChange(next)
                if (feedback.change(previousHapticValue, hapticValue(next), accepted, feedbackStep, deferHapticUntilCommit)) {
                    hapticView.performStemwiseHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        },
        modifier = modifier
            .stemWiseInteractionAura(
                enabled = enabled,
                selected = false,
                interactionSource = interactionSource,
                dragState = thumbAura,
                paintTransientPress = false,
                anchor = AuraAnchor.Movable,
                reducedMotion = !LocalStemwiseMotion.current.enabled
            )
            .then(
                if (enabled) {
                    // stemwise-aura-exclusion: passive press lifecycle observer for the
                    // aura-owned Material slider; it owns no action or coordinate publisher.
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            var pointerId: PointerId? = null
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (pointerId == null) {
                                    event.changes.firstOrNull {
                                        it.pressed && !it.previousPressed
                                    }?.let { down ->
                                        pointerId = down.id
                                        thumbAura.value = thumbAura.value?.copy(active = true)
                                    }
                                } else {
                                    val tracked = event.changes.firstOrNull { it.id == pointerId }
                                    if (tracked == null || !tracked.pressed) {
                                        pointerId = null
                                        thumbAura.value = thumbAura.value?.copy(active = false)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .onGloballyPositioned { sliderCoordinates = it },
        enabled = enabled,
        onValueChangeFinished = {
            val accepted = onValueChangeFinished?.invoke() ?: true
            if (feedback.finish(accepted, deferHapticUntilCommit)) {
                hapticView.performStemwiseHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        },
        colors = colors,
        interactionSource = interactionSource,
        steps = steps,
        thumb = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .onGloballyPositioned { thumbCoordinates ->
                    val slider = sliderCoordinates
                    if (slider != null && slider.isAttached && thumbCoordinates.isAttached) {
                        val topLeft = slider.localPositionOf(thumbCoordinates, Offset.Zero)
                        val center = topLeft + Offset(
                            thumbCoordinates.size.width * .5f,
                            thumbCoordinates.size.height * .5f
                        )
                        val previous = thumbAura.value
                        val visibleSize = androidx.compose.ui.geometry.Size(
                            thumbCoordinates.size.width.toFloat(),
                            thumbCoordinates.size.height.toFloat()
                        )
                        if (
                            previous == null || previous.center != center ||
                            previous.visibleSize != visibleSize
                        ) {
                            thumbAura.value = DragAuraState(
                                center = center,
                                active = previous?.active == true,
                                visibleSize = visibleSize
                            )
                        }
                    }
                }
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 1f else .38f))
                    .border(
                        width = 2.dp,
                        color = PrimaryPurple.copy(alpha = if (enabled) 1f else .38f),
                        shape = CircleShape
                    )
            ) {
                if (valueLabel != null && labelAlpha > 0f) {
                    val metrics = StemwiseSliderValueLabelMetrics
                    val labelDensity = androidx.compose.ui.platform.LocalDensity.current.density
                    Popup(alignment = Alignment.BottomCenter,
                        offset = IntOffset(0, -((16f + metrics.THUMB_GAP_DP) * labelDensity).roundToInt()),
                        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                        Box(modifier = Modifier
                                .graphicsLayer {
                                    alpha = labelAlpha
                                    translationY = (1f - labelAlpha) * metrics.LIFT_DP * labelDensity
                                }
                                .clearAndSetSemantics { }
                                .heightIn(min = metrics.MIN_HEIGHT_DP.dp)
                                .widthIn(min = metrics.MIN_WIDTH_DP.dp)
                                .clip(RoundedCornerShape(metrics.CORNER_RADIUS_DP.dp))
                                .background(Surface)
                                .border(1.dp, Outline, RoundedCornerShape(metrics.CORNER_RADIUS_DP.dp))
                                .padding(horizontal = metrics.HORIZONTAL_PADDING_DP.dp, vertical = metrics.VERTICAL_PADDING_DP.dp),
                            contentAlignment = Alignment.Center) {
                            Text(valueLabel, color = TextPrimary, fontSize = metrics.TEXT_SIZE_SP.sp,
                                lineHeight = metrics.LINE_HEIGHT_SP.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        track = { state ->
            val tickFractions = remember(state.steps) {
                stemwiseSliderTickFractions(state.steps)
            }
            val range = valueRange.endInclusive - valueRange.start
            val progress = if (range.isFinite() && range > 0f) {
                ((value - valueRange.start) / range)
                    .takeIf(Float::isFinite)
                    ?.coerceIn(0f, 1f)
                    ?: 0f
            } else {
                0f
            }
            val activeRange = stemwiseSliderActiveTrackRange(progress, centeredTrack)
            val inactiveTrackColor = if (enabled) {
                colors.inactiveTrackColor
            } else {
                colors.disabledInactiveTrackColor
            }
            val activeTrackColor = if (enabled) {
                colors.activeTrackColor
            } else {
                colors.disabledActiveTrackColor
            }
            val inactiveTickColor = if (enabled) {
                colors.inactiveTickColor
            } else {
                colors.disabledInactiveTickColor
            }
            val activeTickColor = if (enabled) {
                colors.activeTickColor
            } else {
                colors.disabledActiveTickColor
            }

            // A plain Canvas has no Material corner alignment line, so its endpoints are the
            // physical thumb centers instead of a second inset render span.
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(tickDiameterDp.dp)
            ) {
                val trackHeight = STEMWISE_SLIDER_TRACK_HEIGHT_DP.dp.toPx()
                val trackTop = (size.height - trackHeight) * .5f
                val trackCorner = CornerRadius(trackHeight * .5f, trackHeight * .5f)
                val trackTopLeft = Offset(0f, trackTop)
                val trackSize = Size(size.width, trackHeight)
                drawRoundRect(
                    color = inactiveTrackColor,
                    topLeft = trackTopLeft,
                    size = trackSize,
                    cornerRadius = trackCorner
                )

                if (activeRange.endInclusive > activeRange.start) {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val activeLeft = size.width * if (rtl) {
                        1f - activeRange.endInclusive
                    } else {
                        activeRange.start
                    }
                    val activeRight = size.width * if (rtl) {
                        1f - activeRange.start
                    } else {
                        activeRange.endInclusive
                    }
                    clipRect(
                        left = activeLeft,
                        right = activeRight
                    ) {
                        drawRoundRect(
                            color = activeTrackColor,
                            topLeft = trackTopLeft,
                            size = trackSize,
                            cornerRadius = trackCorner
                        )
                    }
                }

                val rtl = layoutDirection == LayoutDirection.Rtl
                tickFractions.forEach { fraction ->
                    val xFraction = if (rtl) 1f - fraction else fraction
                    val x = size.width * xFraction
                    drawLine(
                        color = if (
                            activeRange.endInclusive > activeRange.start &&
                            fraction in activeRange
                        ) activeTickColor else inactiveTickColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        },
        valueRange = valueRange
    )
    }
}
