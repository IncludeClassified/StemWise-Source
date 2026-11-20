package app.stemwise.ui.design

import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.ceil
import kotlin.math.min

internal object StemwiseAuraContract {
    const val LOOP_HANDLE_FIELD_PADDING_DP = 14f

    const val REDUCED_PRESS_HIGHLIGHT_MS = 144
    const val ACCESSIBILITY_HOLD_FEEDBACK_MS = 420L
    const val SELECTED_CORE_ALPHA = 1f
    const val SELECTED_NEAR_ALPHA = .78f
    const val SELECTED_FAR_ALPHA = .42f
    const val SELECTED_CORE_BLUR_RADIUS_DP = 1f
    const val SELECTED_NEAR_BLUR_RADIUS_DP = 8f
    const val SELECTED_FAR_BLUR_RADIUS_DP = 24f
    const val SELECTED_GLYPH_GLOW_REACH_DP = 32f
    const val SELECTED_TRANSITION_DURATION_MS = 360
    const val GLYPH_RADIUS_DP = 12f
    val FallbackArgb = 0xFFA855F7.toInt()
    val Lilac = Color(0xFFD7ADFF)
    val White = Color(0xFFFBF8FF)
    val LilacArgb = 0xFFD7ADFF.toInt()
    val WhiteArgb = 0xFFFBF8FF.toInt()
    val AcceptedPressEasing = CubicBezierEasing(.2f, 0f, .2f, 1f)
}

/** Consumes each accepted visual press exactly once across later state recompositions. */
internal class StemwiseAuraPressGate {
    private var consumedSequence = 0

    fun consume(sequence: Int, movable: Boolean): Boolean {
        if (movable || sequence == 0 || sequence == consumedSequence) return false
        consumedSequence = sequence
        return true
    }
}

internal fun stemwiseSelectedGlyphGlowAlpha(
    enabled: Boolean,
    selected: Boolean,
    anchor: AuraAnchor
): Float = stemwiseSelectedGlyphGlowAlpha(
    enabled = enabled,
    selectedProgress = if (selected) 1f else 0f,
    anchor = anchor
)

internal fun stemwiseSelectedGlyphGlowAlpha(
    enabled: Boolean,
    selectedProgress: Float,
    anchor: AuraAnchor
): Float = if (enabled && anchor == AuraAnchor.GlyphLocalized) {
    val progress = selectedProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    StemwiseAuraContract.SELECTED_CORE_ALPHA * progress
} else {
    0f
}

internal enum class AuraAnchor { VisibleControl, GlyphLocalized, Movable }

@Immutable
internal data class DragAuraState(
    val center: Offset,
    val active: Boolean = true,
    val visibleSize: Size = Size.Unspecified
)

/** Visible paint bounds inside a larger action/semantics target. */
@Immutable
internal data class AuraVisibleHost(
    val center: Offset,
    val size: Size
)

/** Immediate visual-only signal emitted by an outer action owner to its measured aura child. */
private class AuraPressSignal : Interaction

/** Starts visual feedback only from an enabled action callback that actually received the tap. */
internal fun MutableInteractionSource.emitAcceptedAuraFeedback(enabled: Boolean): Boolean =
    enabled && tryEmit(AuraPressSignal())

/** Runs press feedback only after the action owner confirms that it accepted the command. */
internal inline fun performAcceptedAuraAction(
    enabled: Boolean,
    action: () -> Boolean,
    onAccepted: () -> Unit
): Boolean {
    if (!enabled || !action()) return false
    onAccepted()
    return true
}

/**
 * Shared Compose aura modifier. It observes the control's existing [interactionSource] and the
 * same explicit drag state used by movable controls. Underlying actions remain immediate.
 */
internal fun Modifier.stemWiseInteractionAura(
    enabled: Boolean,
    selected: Boolean,
    interactionSource: InteractionSource,
    dragState: State<DragAuraState?>? = null,
    visibleHostState: State<AuraVisibleHost?>? = null,
    anchor: AuraAnchor = AuraAnchor.VisibleControl,
    reducedMotion: Boolean,
    paintTransientPress: Boolean = true
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val drag = dragState?.value
    val active = enabled && (pressed || dragged || drag?.active == true)
    val movable = anchor == AuraAnchor.Movable || dragState != null
    val tracksAcceptedPress = !movable && paintTransientPress
    val flashProgress = remember { Animatable(1f) }
    val selectedProgress = remember { Animatable(if (selected) 1f else 0f) }
    val pressGate = remember { StemwiseAuraPressGate() }
    var pressSequence by remember { mutableIntStateOf(0) }
    val view = LocalView.current

    LaunchedEffect(enabled, selected, anchor, reducedMotion) {
        val target = if (enabled && selected && anchor == AuraAnchor.GlyphLocalized) 1f else 0f
        if (reducedMotion) {
            selectedProgress.snapTo(target)
        } else {
            selectedProgress.animateTo(
                target,
                tween(
                    StemwiseAuraContract.SELECTED_TRANSITION_DURATION_MS,
                    easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
                )
            )
        }
    }

    // Acceptance already checks enabled. A later disable must not cancel accepted feedback.
    LaunchedEffect(interactionSource, tracksAcceptedPress) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is AuraPressSignal) {
                if (tracksAcceptedPress) {
                    pressSequence += 1
                    view.performStemwiseHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }
    }

    LaunchedEffect(pressSequence, tracksAcceptedPress, paintTransientPress) {
        if (!tracksAcceptedPress || !pressGate.consume(pressSequence, movable)) return@LaunchedEffect
        flashProgress.snapTo(0f)
        flashProgress.animateTo(1f, tween(StemwiseAuraContract.REDUCED_PRESS_HIGHLIGHT_MS))
    }
    LaunchedEffect(active, movable) {
        if (active && movable) view.performStemwiseHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    val auraLayer = graphicsLayer {
        // The aura is intentionally larger than the visible control and must remain visible
        // beyond its measured bounds.
        clip = false
    }.drawWithCache {
        val compact = anchor == AuraAnchor.GlyphLocalized
        val measuredHost = visibleHostState?.value?.takeIf { host ->
            host.center.x.isFinite() && host.center.y.isFinite() &&
                host.size.width.isFinite() && host.size.height.isFinite() &&
                host.size.width > 0f && host.size.height > 0f
        }
        val normalHostSize = measuredHost?.size ?: if (compact) {
            val glyphEdge = minOf(
                size.width,
                size.height,
                StemwiseAuraContract.GLYPH_RADIUS_DP * 2f * density
            )
            Size(glyphEdge, glyphEdge)
        } else {
            size
        }
        val normalHostCenter = measuredHost?.center ?: Offset(size.width * .5f, size.height * .5f)
        val selectedGlowReach = StemwiseAuraContract.SELECTED_GLYPH_GLOW_REACH_DP.dp.toPx()
        val selectedGlowPadding = ceil(selectedGlowReach).toInt()
        val selectedGlowLayerSize = IntSize(
            width = ceil(size.width).toInt() + selectedGlowPadding * 2,
            height = ceil(size.height).toInt() + selectedGlowPadding * 2
        )
        // Only glyph anchors can paint selected glow; slider movement rebuilds this cache.
        val selectedGlyphGlowLayers = if (compact) {
            listOf(
                Triple(
                    obtainGraphicsLayer(),
                    StemwiseAuraContract.SELECTED_CORE_BLUR_RADIUS_DP.dp.toPx(),
                    StemwiseAuraContract.SELECTED_CORE_ALPHA
                ),
                Triple(
                    obtainGraphicsLayer(),
                    StemwiseAuraContract.SELECTED_NEAR_BLUR_RADIUS_DP.dp.toPx(),
                    StemwiseAuraContract.SELECTED_NEAR_ALPHA
                ),
                Triple(
                    obtainGraphicsLayer(),
                    StemwiseAuraContract.SELECTED_FAR_BLUR_RADIUS_DP.dp.toPx(),
                    StemwiseAuraContract.SELECTED_FAR_ALPHA
                )
            ).onEach { (layer, radius, _) ->
                layer.topLeft = IntOffset(-selectedGlowPadding, -selectedGlowPadding)
                layer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            }
        } else {
            emptyList()
        }
        val movableSize = drag?.visibleSize?.takeIf {
            it.width.isFinite() && it.height.isFinite()
        } ?: Size(
            width = minOf(size.width, 50.dp.toPx()),
            height = minOf(size.height, 50.dp.toPx())
        )
        onDrawWithContent {
            val effectCenter = if (movable) dragState?.value?.center ?: center else normalHostCenter
            val selectedAuraProgress = selectedProgress.value
            val selectedGlowAlpha = stemwiseSelectedGlyphGlowAlpha(
                enabled = enabled,
                selectedProgress = selectedAuraProgress,
                anchor = anchor
            )
            if (selectedGlowAlpha > 0f) {
                // RenderEffect clips to its GraphicsLayer backing size, so each retained
                // 1/8/24dp glyph layer records into explicit 28dp padding and is placed back
                // at the owner's negative outset. Layout, semantics, and hit bounds stay put.
                val recordGlowContent: ContentDrawScope.() -> Unit = {
                    val recordedContent = this
                    withTransform({
                        translate(
                            selectedGlowPadding.toFloat(),
                            selectedGlowPadding.toFloat()
                        )
                    }) {
                        recordedContent.drawContent()
                    }
                }
                selectedGlyphGlowLayers.asReversed().forEach { (layer, _, alpha) ->
                    layer.alpha = alpha * selectedAuraProgress
                    layer.record(size = selectedGlowLayerSize, block = recordGlowContent)
                    drawLayer(layer)
                }
            }
            drawContent()
            // Paint the accepted-action response above the surface. Drawing it beneath an opaque
            // button made identical timings look missing or faster on different fills.
            if (paintTransientPress && !movable && flashProgress.value < 1f) {
                drawReducedPressHighlight(effectCenter, normalHostSize, 1f - flashProgress.value)
            }
            if (paintTransientPress && enabled && movable && active) {
                drawReducedPressHighlight(effectCenter, movableSize, .36f)
            }
        }
    }
    auraLayer
}

/** Default action seam used by Compose controls that do not require a custom gesture detector. */
internal fun Modifier.stemwiseAuraClickable(
    enabled: Boolean = true,
    selected: Boolean = false,
    role: Role? = Role.Button,
    anchor: AuraAnchor = AuraAnchor.VisibleControl,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    stemWiseInteractionAura(
        enabled = enabled,
        selected = selected,
        interactionSource = interactionSource,
        anchor = anchor,
        reducedMotion = !LocalStemwiseMotion.current.enabled
    ).clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = {
            interactionSource.emitAcceptedAuraFeedback(enabled)
            onClick()
        }
    )
}

/**
 * Installs only the action/semantics owner for a control whose measured visible child paints the
 * shared aura. This keeps one click target while preventing row-sized feedback geometry.
 */
internal fun Modifier.stemwiseAuraClickableAction(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onClick = {
            interactionSource.emitAcceptedAuraFeedback(enabled)
            onClick()
        }
    )

/** Action owner variant for commands that can be rejected by a playback/control lease. */
internal fun Modifier.stemwiseAuraAcceptedClickableAction(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Boolean
): Modifier = clickable(
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = role,
    onClickLabel = onClickLabel,
    onClick = {
        performAcceptedAuraAction(
            enabled = enabled,
            action = onClick,
            onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
        )
    }
)

/**
 * One honest gesture owner for a surface whose tap and long-press feedback are mutually exclusive.
 * A confirmed tap responds at release. Long press commits once at recognition, while still held.
 */
internal fun Modifier.stemwiseAuraClassifiedTapOrLongPressAction(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    onHoldAuraChanged: (Boolean) -> Unit,
    onTap: () -> Boolean,
    onLongPress: (() -> Unit)? = null
): Modifier = composed {
    val currentHoldAuraChanged by rememberUpdatedState(onHoldAuraChanged)
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val view = LocalView.current
    var accessibilityHoldSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(accessibilityHoldSequence, enabled) {
        if (!enabled || accessibilityHoldSequence == 0) return@LaunchedEffect
        currentHoldAuraChanged(true)
        try {
            delay(StemwiseAuraContract.ACCESSIBILITY_HOLD_FEEDBACK_MS)
        } finally {
            currentHoldAuraChanged(false)
        }
    }
    stemwiseKeyboardDetails(enabled && onLongPress != null) {
        currentLongPress?.invoke()
        currentLongPress != null
    }.stemwiseKeyboardAction(enabled) {
        performAcceptedAuraAction(enabled, currentTap) {
            interactionSource.emitAcceptedAuraFeedback(true)
        }
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onPress = {
                currentHoldAuraChanged(false)
                try {
                    tryAwaitRelease()
                } finally {
                    currentHoldAuraChanged(false)
                }
            },
            onTap = {
                performAcceptedAuraAction(
                    enabled = enabled,
                    action = currentTap,
                    onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
                )
            },
            onLongPress = if (onLongPress == null) null else {
                {
                    view.performStemwiseHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    currentLongPress?.invoke()
                }
            }
        )
    }.semantics(mergeDescendants = true) {
        if (role != null) this.role = role
        if (!enabled) disabled()
        onClick(label = onClickLabel) {
            performAcceptedAuraAction(
                enabled = enabled,
                action = currentTap,
                onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
            )
        }
        if (onLongPress != null) {
            onLongClick(label = onLongClickLabel) {
                if (!enabled) return@onLongClick false
                accessibilityHoldSequence += 1
                view.performStemwiseHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                currentLongPress?.invoke()
                true
            }
        }
    }
}

/** One shared aura and one selectable owner for tabs and radio rows. */
internal fun Modifier.stemwiseAuraSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    anchor: AuraAnchor = AuraAnchor.VisibleControl,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    stemWiseInteractionAura(
        enabled = enabled,
        selected = selected,
        interactionSource = interactionSource,
        anchor = anchor,
        reducedMotion = !LocalStemwiseMotion.current.enabled
    ).selectable(
        selected = selected,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = {
            interactionSource.emitAcceptedAuraFeedback(enabled)
            onClick()
        }
    )
}

/** Selectable action host for layouts that render the shared aura on a measured child glyph. */
internal fun Modifier.stemwiseAuraSelectableAction(
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier = selectable(
        selected = selected,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = {
            interactionSource.emitAcceptedAuraFeedback(enabled)
            onClick()
        }
    )

/** Selectable owner variant for controls whose authoritative command can be rejected. */
internal fun Modifier.stemwiseAuraAcceptedSelectableAction(
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Boolean
): Modifier = selectable(
    selected = selected,
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = role,
    onClick = {
        performAcceptedAuraAction(
            enabled = enabled,
            action = onClick,
            onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
        )
    }
)

/**
 * Installs only the toggle/semantics owner when a measured switch child paints the shared aura.
 * The surrounding tile stays a full accessibility target without becoming the visual host.
 */
internal fun Modifier.stemwiseAuraToggleableAction(
    interactionSource: MutableInteractionSource,
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.Switch,
    onValueChange: (Boolean) -> Unit
): Modifier = toggleable(
    value = value,
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = role,
    onValueChange = { next ->
        interactionSource.emitAcceptedAuraFeedback(enabled)
        onValueChange(next)
    }
)

/** Toggle owner variant for commands whose authoritative owner can reject the requested state. */
internal fun Modifier.stemwiseAuraAcceptedToggleableAction(
    interactionSource: MutableInteractionSource,
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.Switch,
    onValueChange: (Boolean) -> Boolean
): Modifier = toggleable(
    value = value,
    interactionSource = interactionSource,
    indication = null,
    enabled = enabled,
    role = role,
    onValueChange = { next ->
        performAcceptedAuraAction(
            enabled = enabled,
            action = { onValueChange(next) },
            onAccepted = { interactionSource.emitAcceptedAuraFeedback(true) }
        )
    }
)

private fun DrawScope.drawReducedPressHighlight(
    center: Offset,
    hostSize: Size,
    alpha: Float
) {
    if (alpha <= 0f) return
    drawControlBodyFlash(center, hostSize, alpha.coerceIn(0f, 1f))
}

/** The accepted flash covers the complete visible owner. */
private fun DrawScope.drawControlBodyFlash(
    center: Offset,
    hostSize: Size,
    alpha: Float
) {
    if (hostSize.width <= 0f || hostSize.height <= 0f || alpha <= 0f) return
    fun layer(insetFraction: Float, color: Color, layerAlpha: Float) {
        val inset = min(hostSize.width, hostSize.height) * insetFraction
        val layerSize = Size(
            (hostSize.width - inset * 2f).coerceAtLeast(0f),
            (hostSize.height - inset * 2f).coerceAtLeast(0f)
        )
        if (layerSize.width <= 0f || layerSize.height <= 0f) return
        drawRoundRect(
            color = color,
            topLeft = Offset(
                center.x - layerSize.width * .5f,
                center.y - layerSize.height * .5f
            ),
            size = layerSize,
            cornerRadius = CornerRadius(layerSize.minDimension * .5f),
            alpha = (alpha * layerAlpha).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus
        )
    }
    layer(0f, StemwiseAuraContract.Lilac, .08f)
    layer(0f, StemwiseAuraContract.White, .04f)
}
