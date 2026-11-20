package app.stemwise.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseTextButton
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.theme.Coral
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DialogScrimAlpha = 184f / 255f
private const val DialogStartScale = 0.96f

internal fun stemwiseDialogHorizontalMarginDp(viewportWidthDp: Float): Float =
    (viewportWidthDp.coerceAtLeast(0f) * .5f).coerceAtMost(20f)

internal fun stemwiseDialogBodyWidthDp(viewportWidthDp: Float): Float {
    val safeWidth = viewportWidthDp.coerceAtLeast(0f)
    val margin = stemwiseDialogHorizontalMarginDp(safeWidth)
    return (safeWidth - margin * 2f).coerceAtLeast(0f).coerceAtMost(392f)
}

internal enum class StemwisePermanentDeleteAction {
    CANCEL,
    DELETE
}

/** Destructive dialogs keep the safe action physically left in every application locale. */
internal fun stemwisePermanentDeletePhysicalOrder(
    @Suppress("UNUSED_PARAMETER") layoutDirection: LayoutDirection
): List<StemwisePermanentDeleteAction> = listOf(
    StemwisePermanentDeleteAction.CANCEL,
    StemwisePermanentDeleteAction.DELETE
)

/**
 * One action-row owner shared by both permanent-delete entry points.
 *
 * Material's separate confirm/dismiss slots can reverse physical order in RTL. Mounting one
 * fixed-LTR row prevents the destructive action from moving to the safe-action position, while
 * restoring the application's direction inside each label preserves Arabic shaping.
 */
@Composable
internal fun StemwisePermanentDeleteActions(
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val contentLayoutDirection = LocalLayoutDirection.current
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocus()
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            stemwisePermanentDeletePhysicalOrder(contentLayoutDirection).forEach { action ->
                when (action) {
                    StemwisePermanentDeleteAction.CANCEL -> StemwiseTextButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .focusRequester(cancelFocusRequester)
                    ) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides contentLayoutDirection
                        ) {
                            Text(androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_cancel), modifier = Modifier.stemwiseMarquee(), maxLines = 1)
                        }
                    }

                    StemwisePermanentDeleteAction.DELETE -> StemwiseTextButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides contentLayoutDirection
                        ) {
                            Text(
                                androidx.compose.ui.res.stringResource(app.stemwise.R.string.ui_delete_permanently),
                                modifier = Modifier.stemwiseMarquee(),
                                color = Coral,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Explicit action dispatch for content mounted inside a [StemwiseAlertDialog].
 *
 * Actions that keep the dialog open run normally. UI-only terminal changes use [dismiss] or
 * [closeThen]. Backend work uses [closeAfterStarting] so its durable owner accepts the intent once
 * before the close animation becomes cancellable presentation.
 */
@Stable
internal interface StemwiseDialogActionScope {
    val actionsEnabled: Boolean
    fun runIfOpen(action: () -> Unit): Boolean
    fun beginBusyAction(): Boolean
    fun endBusyAction()
    fun dismiss()

    fun closeThen(action: () -> Unit)

    fun closeAfterStarting(
        startAction: () -> Boolean,
        afterClose: () -> Unit
    )
}

/** Main-thread gate: one accepted action starts once, even under rapid repeated activation. */
internal class StemwiseDialogCloseGate {
    private enum class Phase { OPEN, BUSY, CLOSING }
    private var phase by mutableStateOf(Phase.OPEN)
    val actionsEnabled: Boolean get() = phase == Phase.OPEN

    fun runIfOpen(action: () -> Unit): Boolean {
        if (!actionsEnabled) return false
        action()
        return true
    }

    fun beginBusyAction(): Boolean {
        if (!actionsEnabled) return false
        phase = Phase.BUSY
        return true
    }

    fun endBusyAction() {
        if (phase == Phase.BUSY) phase = Phase.OPEN
    }

    fun accept(startAction: () -> Boolean = { true }): Boolean {
        if (!actionsEnabled) return false
        phase = Phase.CLOSING
        return try {
            startAction().also { started ->
                if (!started) phase = Phase.OPEN
            }
        } catch (error: Throwable) {
            phase = Phase.OPEN
            throw error
        }
    }

    suspend fun finish(
        animateClose: suspend () -> Unit,
        afterClose: () -> Unit
    ) {
        try {
            animateClose()
        } finally {
            // Disposal may cancel the presentation, never the accepted action's UI settlement.
            withContext(NonCancellable) { afterClose() }
        }
    }
}

/**
 * Product AlertDialog with platform-managed display cadence.
 *
 * Compose dialogs own a separate Window/ViewRoot, so the Activity decor's API 36 propagation does
 * not reach them. Wrapping the required confirm slot guarantees this effect executes from inside
 * every dialog hierarchy while preserving Material's standard layout, semantics and behavior.
 */
@Composable
internal fun StemwiseAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable StemwiseDialogActionScope.() -> Unit,
    modifier: Modifier = Modifier,
    dismissEnabled: Boolean = true,
    dismissButton: (@Composable StemwiseDialogActionScope.() -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable StemwiseDialogActionScope.() -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    properties: DialogProperties = DialogProperties()
) {
    val motion = LocalStemwiseMotion.current
    val openToken = StemwiseSemanticMotion.token(StemwiseMotionEvent.DIALOG_OPEN)
    val closeToken = StemwiseSemanticMotion.token(StemwiseMotionEvent.DIALOG_CLOSE)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeGate = remember { StemwiseDialogCloseGate() }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = if (motion.enabled) openToken.durationMs else 120,
                easing = openToken.easing.curve
            )
        )
    }

    fun requestAnimatedClose(
        afterClose: () -> Unit,
        startAction: () -> Boolean = { true }
    ) {
        if (!closeGate.accept(startAction)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            closeGate.finish(
                animateClose = {
                    // The dialog owns only the visible close portion of an accepted button bloom.
                    progress.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = if (motion.enabled) closeToken.durationMs else 120,
                            easing = closeToken.easing.curve
                        )
                    )
                },
                afterClose = afterClose
            )
        }
    }

    fun requestAnimatedDismiss() {
        if (dismissEnabled) requestAnimatedClose(onDismissRequest)
    }

    val actionScope = object : StemwiseDialogActionScope {
        override val actionsEnabled: Boolean get() = closeGate.actionsEnabled
        override fun runIfOpen(action: () -> Unit): Boolean = closeGate.runIfOpen(action)
        override fun beginBusyAction(): Boolean = closeGate.beginBusyAction()
        override fun endBusyAction() = closeGate.endBusyAction()
        // Explicit dialog actions must always be able to finish the close transition. The
        // dismissEnabled gate belongs only to platform Back/outside-tap dismissal; using it here
        // would make a guarded dialog's own Cancel/Done/Close action a no-op.
        override fun dismiss() = requestAnimatedClose(onDismissRequest)

        override fun closeThen(action: () -> Unit) = requestAnimatedClose(action)

        override fun closeAfterStarting(
            startAction: () -> Boolean,
            afterClose: () -> Unit
        ) = requestAnimatedClose(afterClose, startAction)
    }

    val dialogProgress = progress.value
    val viewportWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp().value
    }
    val horizontalMargin = stemwiseDialogHorizontalMarginDp(viewportWidthDp).dp
    val maximumBodyWidth = stemwiseDialogBodyWidthDp(viewportWidthDp).dp
    val animatedModifier = modifier
        .padding(horizontal = horizontalMargin)
        .widthIn(max = maximumBodyWidth)
        .fillMaxWidth()
        .graphicsLayer {
            alpha = dialogProgress
            val spatialProgress = if (motion.enabled) dialogProgress else 1f
            scaleX = DialogStartScale + ((1f - DialogStartScale) * spatialProgress)
            scaleY = DialogStartScale + ((1f - DialogStartScale) * spatialProgress)
            translationY = 8.dp.toPx() * (1f - spatialProgress)
        }

    MaterialAlertDialog(
        onDismissRequest = ::requestAnimatedDismiss,
        confirmButton = {
            val dialogHostView = LocalView.current
            SideEffect {
                (dialogHostView.parent as? DialogWindowProvider)
                    ?.window
                    ?.also { window ->
                        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        window.attributes = window.attributes.apply {
                            dimAmount = DialogScrimAlpha * dialogProgress
                        }
                    }
            }
            actionScope.confirmButton()
        },
        modifier = animatedModifier,
        dismissButton = dismissButton?.let { content ->
            { actionScope.content() }
        },
        title = title,
        text = text?.let { content -> { actionScope.content() } },
        shape = RoundedCornerShape(24.dp),
        containerColor = containerColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        properties = DialogProperties(
            dismissOnBackPress = dismissEnabled && properties.dismissOnBackPress,
            dismissOnClickOutside = dismissEnabled && properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows
        )
    )
}
