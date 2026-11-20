package app.stemwise.ui.design

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

object StemwiseSpacing {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
}

object StemwiseRadii {
    val control = RoundedCornerShape(12.dp)
    val card = RoundedCornerShape(16.dp)
    val hero = RoundedCornerShape(24.dp)
}

@Immutable
data class StemwiseMotionSpec(
    val enabled: Boolean,
    val ambientMotionEnabled: Boolean = enabled
)

internal enum class StemwiseMotionEvent(val wireName: String) {
    APP_LAUNCH("app.launch"),
    APP_RESUME("app.resume"),
    APP_CONTROLLED_EXIT("app.controlled_exit"),
    DESTINATION_CHANGE("destination.change"),
    SCREEN_PUSH("screen.push"),
    SCREEN_POP("screen.pop"),
    PLAYER_SESSION_ENTER("player.session_enter"),
    PLAYER_SOURCE_CHANGE("player.source_change"),
    ARTWORK_CHANGE("artwork.change"),
    PALETTE_PROPAGATE("palette.propagate"),
    PLAYER_LAYOUT_PROFILE_CHANGE("player.layout_profile_change"),
    ARTWORK_AURA_BREATHE("artwork.aura_breathe"),
    LIST_INSERT("list.insert"),
    LIST_REMOVE("list.remove"),
    LIST_REORDER("list.reorder"),
    SEARCH_RESULTS_CHANGE("search.results_change"),
    PANEL_OPEN("panel.open"),
    PANEL_CLOSE("panel.close"),
    PANEL_CONTENT_REFLOW("panel.content_reflow"),
    DIALOG_OPEN("dialog.open"),
    DIALOG_CLOSE("dialog.close"),
    STATE_CHANGE_DEFAULT("stateChange.default"),
    BUTTON_RELEASE("button.release"),
    CONTROL_HOLD("control.hold"),
    CONTROL_DRAG("control.drag"),
    SEEKER_PRESS("seeker.press"),
    SEEKER_FINE_ENTER("seeker.fine_enter"),
    SEEKER_FINE_EXIT("seeker.fine_exit"),
    LOOP_HANDLE_PRESS("loop.handle_press"),
    LOOP_HANDLE_DRAG("loop.handle_drag"),
    TOGGLE_CHANGE("toggle.change"),
    PROGRESS_CHANGE("progress.change"),
    STATUS_CHANGE("status.change"),
    LYRICS_ACTIVE_LINE("lyrics.active_line"),
    LYRICS_SELECTION_CHANGE("lyrics.selection_change"),
    METRONOME_BEAT("metronome.beat"),
    GESTURE_SCROLL("gesture.scroll"),
    GESTURE_SWIPE("gesture.swipe"),
    GESTURE_LONG_PRESS("gesture.long_press"),
    GESTURE_PLAYER_DRAG("gesture.player_drag"),
    GESTURE_SLIDER_DRAG("gesture.slider_drag"),
    GESTURE_TEXT_INPUT("gesture.text_input"),
    GESTURE_KEYBOARD_INSET("gesture.keyboard_inset"),
    GESTURE_PULL_REFRESH("gesture.pull_refresh")
}

internal enum class StemwiseMotionEasing(val curve: CubicBezierEasing) {
    STANDARD(CubicBezierEasing(.2f, 0f, 0f, 1f)),
    ENTER(CubicBezierEasing(.16f, 1f, .3f, 1f)),
    EXIT(CubicBezierEasing(.23f, 1f, .32f, 1f)),
    EMPHASIZED(CubicBezierEasing(.2f, .8f, .2f, 1f)),
    LINEAR(CubicBezierEasing(0f, 0f, 1f, 1f))
}

@Immutable
internal data class StemwiseSemanticMotionToken(
    val durationMs: Int,
    val easing: StemwiseMotionEasing = StemwiseMotionEasing.STANDARD
)

/** Shared semantic motion tokens. Unknown wire events use the defined 200 ms fallback. */
internal object StemwiseSemanticMotion {
    const val ARTWORK_LOAD_MS = 250
    const val ARTWORK_AURA_PERIOD_MS = 5_400
    const val ARTWORK_AURA_PHASE_OFFSET_MS = 2_050
    const val ARTWORK_AURA_STATIC_MID_FRAME_NANOS = 1_890_000_000L
    private val fallback = StemwiseSemanticMotionToken(200)
    private val tokens = mapOf(
        StemwiseMotionEvent.APP_LAUNCH to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.APP_CONTROLLED_EXIT to StemwiseSemanticMotionToken(
            200,
            StemwiseMotionEasing.EXIT
        ),
        StemwiseMotionEvent.DESTINATION_CHANGE to StemwiseSemanticMotionToken(200),
        StemwiseMotionEvent.SCREEN_PUSH to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.SCREEN_POP to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.EXIT),
        StemwiseMotionEvent.PLAYER_SESSION_ENTER to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.PLAYER_SOURCE_CHANGE to StemwiseSemanticMotionToken(280, StemwiseMotionEasing.LINEAR),
        StemwiseMotionEvent.ARTWORK_CHANGE to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.PALETTE_PROPAGATE to StemwiseSemanticMotionToken(250),
        StemwiseMotionEvent.PLAYER_LAYOUT_PROFILE_CHANGE to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.ARTWORK_AURA_BREATHE to StemwiseSemanticMotionToken(ARTWORK_AURA_PERIOD_MS),
        StemwiseMotionEvent.LIST_INSERT to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.LIST_REMOVE to StemwiseSemanticMotionToken(160, StemwiseMotionEasing.EXIT),
        StemwiseMotionEvent.PANEL_OPEN to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.PANEL_CLOSE to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.EXIT),
        StemwiseMotionEvent.PANEL_CONTENT_REFLOW to StemwiseSemanticMotionToken(250),
        StemwiseMotionEvent.DIALOG_OPEN to StemwiseSemanticMotionToken(250, StemwiseMotionEasing.ENTER),
        StemwiseMotionEvent.DIALOG_CLOSE to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.EXIT),
        StemwiseMotionEvent.STATE_CHANGE_DEFAULT to fallback,
        StemwiseMotionEvent.PROGRESS_CHANGE to fallback,
        StemwiseMotionEvent.SEEKER_PRESS to StemwiseSemanticMotionToken(160, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.LOOP_HANDLE_PRESS to StemwiseSemanticMotionToken(160, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.TOGGLE_CHANGE to StemwiseSemanticMotionToken(160),
        StemwiseMotionEvent.LYRICS_ACTIVE_LINE to StemwiseSemanticMotionToken(160, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.LYRICS_SELECTION_CHANGE to StemwiseSemanticMotionToken(200, StemwiseMotionEasing.EMPHASIZED),
        StemwiseMotionEvent.METRONOME_BEAT to StemwiseSemanticMotionToken(160)
    )

    fun token(event: StemwiseMotionEvent): StemwiseSemanticMotionToken = tokens[event] ?: fallback

    fun token(wireName: String): StemwiseSemanticMotionToken =
        StemwiseMotionEvent.entries.firstOrNull { it.wireName == wireName }?.let(::token) ?: fallback
}

/** Resolves mounted Compose motion through the semantic registry, including reduced motion. */
internal fun <T> StemwiseMotionSpec.semanticSpec(
    event: StemwiseMotionEvent,
    spatial: Boolean = false
): FiniteAnimationSpec<T> {
    if (!enabled && spatial) return snap()
    val token = StemwiseSemanticMotion.token(event)
    return tween(
        durationMillis = if (enabled) token.durationMs else 120,
        easing = token.easing.curve
    )
}

val LocalStemwiseMotion = staticCompositionLocalOf {
    StemwiseMotionSpec(enabled = true)
}

internal fun artworkAuraSystemAllowed(
    animatorsEnabled: Boolean,
    powerSaveMode: Boolean,
    thermalStatus: Int
): Boolean = animatorsEnabled && !powerSaveMode && thermalStatus < PowerManager.THERMAL_STATUS_SEVERE

@Composable
fun ProvideStemwiseMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = androidx.compose.ui.platform.LocalView.current
    var foregroundVisible by remember(lifecycleOwner, view) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            view.windowVisibility == android.view.View.VISIBLE && view.isShown)
    }
    DisposableEffect(lifecycleOwner, view) {
        fun refreshVisibility() {
            foregroundVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                view.windowVisibility == android.view.View.VISIBLE && view.isShown
        }
        val observer = LifecycleEventObserver { _, _ -> refreshVisibility() }
        val window = android.view.ViewTreeObserver.OnWindowFocusChangeListener { refreshVisibility() }
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { refreshVisibility() }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(window)
        view.viewTreeObserver.addOnGlobalLayoutListener(layout)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(window)
                view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            }
        }
    }
    var animatorsEnabled by remember {
        mutableStateOf(ValueAnimator.areAnimatorsEnabled())
    }
    DisposableEffect(lifecycleOwner) {
        fun refreshAnimatorPolicy() {
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAnimatorPolicy()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        val durationScaleListener = ValueAnimator.DurationScaleChangeListener { durationScale ->
            animatorsEnabled = durationScale > 0f
        }.also(ValueAnimator::registerDurationScaleChangeListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            ValueAnimator.unregisterDurationScaleChangeListener(durationScaleListener)
        }
    }
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    fun systemAllowsAmbientMotion(): Boolean = artworkAuraSystemAllowed(
        animatorsEnabled = true,
        powerSaveMode = powerManager.isPowerSaveMode,
        thermalStatus = powerManager.currentThermalStatus
    )
    var systemAmbientAllowed by remember(powerManager) {
        mutableStateOf(systemAllowsAmbientMotion())
    }
    DisposableEffect(context, powerManager) {
        fun refresh() {
            systemAmbientAllowed = systemAllowsAmbientMotion()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val thermalListener = PowerManager.OnThermalStatusChangedListener { refresh() }.also { listener ->
            powerManager.addThermalStatusListener(context.mainExecutor, listener)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            powerManager.removeThermalStatusListener(thermalListener)
        }
    }
    CompositionLocalProvider(
        LocalStemwiseMotion provides if (animatorsEnabled) {
            StemwiseMotionSpec(
                enabled = true,
                // Visible artwork owners are the only frame-sampling owners. Keeping this
                // provider event-driven lets an otherwise idle app remain completely idle.
                ambientMotionEnabled = systemAmbientAllowed && foregroundVisible
            )
        } else {
            // Reduced motion keeps state legible through a short non-spatial crossfade. Spatial
            // owners still consult `enabled` and snap translation/scale to their endpoints.
            StemwiseMotionSpec(enabled = false, ambientMotionEnabled = false)
        },
        content = content
    )
}
