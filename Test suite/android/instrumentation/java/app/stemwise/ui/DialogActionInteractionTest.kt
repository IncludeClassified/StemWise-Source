package app.stemwise.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual shared dialog actions, focus, close ordering and cancellation on a mounted surface. */
class DialogActionInteractionTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun dismissedPlaylistCheckAndRetryCallbacksCannotReopenAnOffer() {
        data class Offer(val id: Int, val motion: Boolean, val retry: Boolean)
        val offer = mutableStateOf<Offer?>(null)
        val dispatched = mutableListOf<Int>()
        val dismissed = mutableListOf<Int>()
        compose.setContent {
            val current = offer.value
            if (current != null) androidx.compose.runtime.key(current.id) {
                CompositionLocalProvider(app.stemwise.ui.design.LocalStemwiseMotion provides
                    app.stemwise.ui.design.StemwiseMotionSpec(current.motion, false)) {
                    StemwiseTheme { PlaylistOfferDialog(
                        presentation = null, checking = false, error = "Check this link",
                        initialEditableText = if (current.retry) null else "https://youtube.com/playlist?list=fixture",
                        onDismissRequest = { dismissed += current.id; offer.value = null },
                        onRetry = if (current.retry) ({ dispatched += current.id }) else null,
                        onCheckEdited = if (current.retry) null else ({ dispatched += current.id }),
                        keepAndAddEnabled = false, onKeepAndAdd = { error("No approval") },
                        onKeepAndAddClosed = { error("No approval") }) }
                }
            }
        }
        val retiredCallbacks = mutableListOf<() -> Boolean>()
        var id = 0
        for (motion in listOf(true, false)) for (retry in listOf(false, true)) for (cancelFirst in listOf(true, false)) {
            val current = Offer(++id, motion, retry)
            compose.runOnIdle { offer.value = current }
            val action = compose.onNodeWithText(if (retry) "Try again" else "Check link")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle {
                    retiredCallbacks.forEach { it() }
                    if (!cancelFirst) action()
                    cancel(); action()
                    assertEquals(if (cancelFirst) 0 else 1, dispatched.count { it == current.id })
                }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.waitForIdle()
                compose.runOnIdle { assertEquals(1, dismissed.count { it == current.id }) }
                retiredCallbacks += action
            } finally { compose.mainClock.autoAdvance = true }
        }
        assertEquals(4, dispatched.size)
    }

    @Test fun deleteActionsKeepSafeFocusAndPhysicalOrderUnderRtl() {
        var cancels = 0
        var deletes = 0
        val keyboardReady = mutableStateOf(false)
        lateinit var dialogInputMode: InputModeManager
        lateinit var dialogView: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StemwiseTheme {
                    StemwiseAlertDialog(onDismissRequest = {}, confirmButton = {
                        val inputMode = LocalInputModeManager.current
                        val view = LocalView.current
                        SideEffect { dialogInputMode = inputMode; dialogView = view }
                        if (keyboardReady.value) {
                            StemwisePermanentDeleteActions({ cancels++ }, { deletes++ })
                        }
                    })
                }
            }
        }
        // Programmatic keyboard mode needs a focusable child, which is intentionally not
        // mounted yet. A real navigation key leaves touch mode without choosing either action.
        compose.waitUntil(5_000L) { compose.runOnIdle { dialogView.hasWindowFocus() } }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5_000L) {
            compose.runOnIdle { dialogInputMode.inputMode == InputMode.Keyboard }
        }
        compose.runOnIdle {
            assertEquals("Dialog did not receive keyboard input", InputMode.Keyboard, dialogInputMode.inputMode)
            assertTrue("Dialog refused keyboard input mode", dialogInputMode.requestInputMode(InputMode.Keyboard))
            keyboardReady.value = true
        }
        val cancel = compose.onNodeWithText("Cancel")
        val delete = compose.onNodeWithText("Delete permanently")
        cancel.assertIsFocused()
        assertTrue(cancel.fetchSemanticsNode().boundsInRoot.center.x <
            delete.fetchSemanticsNode().boundsInRoot.center.x)
        cancel.performClick()
        delete.performClick()
        compose.runOnIdle { assertEquals(1, cancels); assertEquals(1, deletes) }
    }

    @Test fun acceptedPlaylistHandoffStartsOnceBeforeCloseAndSettlesAfterDisposal() {
        val visible = mutableStateOf(true)
        val events = mutableListOf<String>()
        compose.setContent {
            StemwiseTheme {
                if (visible.value) PlaylistOfferDialog(
                    presentation = playlistConfirmationPresentation("Fixture", 2, 0, 0, 0),
                    checking = false, error = null, initialEditableText = null,
                    onDismissRequest = { visible.value = false }, onRetry = null,
                    onCheckEdited = null, keepAndAddEnabled = true,
                    onKeepAndAdd = { events += "accepted"; true },
                    onKeepAndAddClosed = { events += "settled"; visible.value = false })
            }
        }
        val confirm = compose.onNodeWithText("Keep & add")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { confirm(); confirm(); assertEquals(listOf("accepted"), events) }
            // Dispose a closing presentation: accepted work must still receive its settlement.
            compose.runOnIdle { visible.value = false }
            // With auto-advance disabled, waitForIdle alone cannot apply the removal.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(listOf("accepted", "settled"), events) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun refusedPlaylistHandoffLeavesTheOfferVisibleAndRetryable() {
        var attempts = 0
        var settlements = 0
        compose.setContent {
            StemwiseTheme { PlaylistOfferDialog(
                presentation = playlistConfirmationPresentation("Fixture", 2, 0, 0, 0),
                checking = false, error = null, initialEditableText = null,
                onDismissRequest = {}, onRetry = null, onCheckEdited = null,
                keepAndAddEnabled = true, onKeepAndAdd = { attempts++; false },
                onKeepAndAddClosed = { settlements++ }) }
        }
        compose.onNodeWithText("Keep & add").performClick().assertIsEnabled()
        compose.onNodeWithText("Keep & add").performClick().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(0, settlements) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(1)
    }

    @Test fun pendingLibraryOwnerShowsRestartGuidanceWithoutRetryOrReconnectActions() {
        var retries = 0
        var closes = 0
        compose.setContent {
            StemwiseTheme { LibraryRecoveryGate(app.stemwise.data.LIBRARY_OWNER_RETIREMENT_PENDING,
                "The previous operation still owns its files.", { retries++ }, { closes++ }) }
        }
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        compose.onNodeWithText("Force stop StemWise, then reopen it before trying again. Your Library files were preserved.",
            useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close StemWise").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, retries); assertEquals(1, closes) }
    }

    @Test fun recoveryDetailsHaveNoFalseActionAndRetryDispatchesFromTheVisibleGate() {
        var retries = 0
        compose.setContent {
            StemwiseTheme { LibraryRecoveryGate("INTERRUPTED", "Recovery interrupted", { retries++ }, {}) }
        }
        compose.onNodeWithText("Recovery details", useUnmergedTree = true)
            .assertHasNoClickAction()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
