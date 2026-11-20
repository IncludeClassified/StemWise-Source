package app.stemwise.ui.design

import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.stemwise.ui.theme.PrimaryPurple

/** Adds keyboard ownership without installing a second pointer gesture over the native surface. */
internal fun Modifier.stemwiseKeyboardAction(
    enabled: Boolean = true,
    onActivate: () -> Boolean
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val activate by rememberUpdatedState(onActivate)
    onFocusChanged { focused = it.isFocused }
        .onKeyEvent { event ->
            if (!enabled || event.key !in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)) false
            else {
                if (event.type == KeyEventType.KeyUp) activate()
                true
            }
        }
        .focusable(enabled)
        .drawWithContent {
            drawContent()
            if (focused) drawRect(PrimaryPurple, style = Stroke(2.dp.toPx()))
        }
}

/** Secondary details action shares the primary focus owner. */
internal fun Modifier.stemwiseKeyboardDetails(enabled: Boolean, onDetails: () -> Boolean): Modifier =
    onKeyEvent { event ->
        val detailsKey = event.key == Key.Menu || event.key == Key.F10 && event.isShiftPressed
        if (!enabled || !detailsKey) false
        else { if (event.type == KeyEventType.KeyUp) onDetails(); true }
    }

/** Keyboard range ownership for native sliders, preserving their one pointer owner. */
internal fun Modifier.stemwiseKeyboardRange(
    value: Float, range: ClosedFloatingPointRange<Float>, step: Float,
    enabled: Boolean = true, onStep: ((Float) -> Boolean)? = null, onValue: (Float) -> Boolean
): Modifier = composed {
    val currentValue by rememberUpdatedState(value)
    val currentAction by rememberUpdatedState(onValue)
    val stepAction by rememberUpdatedState(onStep)
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }.onKeyEvent { event ->
        val requested = when (event.key) {
            Key.DirectionLeft, Key.DirectionDown -> currentValue - step
            Key.DirectionRight, Key.DirectionUp -> currentValue + step
            Key.MoveHome -> range.start
            Key.MoveEnd -> range.endInclusive
            else -> return@onKeyEvent false
        }
        if (!enabled) false else {
            if (event.type == KeyEventType.KeyDown) {
                val direction = when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> -1f
                    Key.DirectionRight, Key.DirectionUp -> 1f
                    else -> 0f
                }
                if (direction != 0f && stepAction != null) stepAction?.invoke(direction * step)
                else currentAction(requested.coerceIn(range.start, range.endInclusive))
            }
            true
        }
    }.focusable(enabled).drawWithContent {
        drawContent()
        if (focused) drawRect(PrimaryPurple, style = Stroke(2.dp.toPx()))
    }
}
