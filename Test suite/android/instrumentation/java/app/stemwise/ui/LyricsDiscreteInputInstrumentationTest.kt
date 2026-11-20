package app.stemwise.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import app.stemwise.model.TimedLyricLine
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Drives the production modifier through the mounted Compose focus/pointer event path. */
class LyricsDiscreteInputInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()
    private lateinit var controller: LyricsViewportController
    private lateinit var list: LazyListState
    private lateinit var playbackPosition: MutableLongState
    private val listFocus = FocusRequester()
    private val outsideFocus = FocusRequester()
    private val firstItemFocus = FocusRequester()
    private var focusedItem = -1
    private var inputTraceStart = 0L

    @Test fun pausedCleanReturnReacquiresWithoutFurtherPlaybackOrLayoutEvents() {
        mount(realTimers = true)
        compose.runOnIdle { playbackPosition.longValue = 10_000L }
        compose.waitUntil(2_000) { list.firstVisibleItemIndex > 0 && controller.mode.value == LyricsViewportMode.Following }
        compose.waitForIdle()
        val before = position()
        // Keep the active row visible so both legs carry measured approach evidence.
        compose.onNodeWithTag("input_lyrics").performMouseInput { enter(center); scroll(.5f) }
        compose.waitUntil(2_000) { position() != before }
        compose.waitUntil(2_000) { controller.mode.value is LyricsViewportMode.Detached &&
            controller.debugState.value.currentRelation != LyricsActiveViewportRelation.InsideCapture }
        compose.onNodeWithTag("input_lyrics").performMouseInput { scroll(-.5f) }
        compose.waitUntil(2_000) { position() == before }
        try {
            compose.waitUntil(2_000) { controller.mode.value is LyricsViewportMode.SoftFollowing }
        } catch (failure: Throwable) {
            throw AssertionError("Settled return did not reacquire: ${controller.debugState.value}; ${controller.trace()}", failure)
        }
        assertEquals("Implicit follow must not move the returned viewport", before, position())
        assertEquals(10_000L, compose.runOnIdle { playbackPosition.longValue })
    }

    @Test fun topBoundaryKeysRepeatReleaseAndFocusLossEndWithoutInventingMovement() {
        mount()
        val before = position()
        for (key in listOf(AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_MOVE_HOME)) {
            compose.runOnIdle {
                key(key, AndroidKeyEvent.ACTION_DOWN)
                assertTrue("Key did not reach the production observer", controller.debugState.value.directDragActive)
                key(key, AndroidKeyEvent.ACTION_DOWN, repeat = 2)
                key(key, AndroidKeyEvent.ACTION_UP)
                assertFalse("Key-up did not terminate the session", controller.debugState.value.directDragActive)
            }
        }
        compose.runOnIdle {
            key(AndroidKeyEvent.KEYCODE_MOVE_HOME, AndroidKeyEvent.ACTION_DOWN)
            assertTrue(controller.debugState.value.directDragActive)
            outsideFocus.requestFocus()
            assertFalse("Focus loss did not terminate the session", controller.debugState.value.directDragActive)
            key(AndroidKeyEvent.KEYCODE_MOVE_HOME, AndroidKeyEvent.ACTION_UP)
        }
        compose.waitForIdle()
        assertEquals(before, position())
        assertFiniteBoundaryAndUsableFollow()
        compose.runOnIdle { playbackPosition.longValue = 10_000L }
        compose.waitUntil(2_000) { list.firstVisibleItemIndex > 0 }
    }

    @Test fun dpadFocusMovementHasARealTerminalPathWithoutListMovement() {
        mount()
        compose.runOnIdle { firstItemFocus.requestFocus() }
        val before = position()
        compose.runOnIdle {
            assertEquals(0, focusedItem)
            key(AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.ACTION_DOWN)
            key(AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.ACTION_UP)
            assertFalse(controller.debugState.value.directDragActive)
        }
        compose.runOnIdle { assertEquals("D-pad did not actually move focus", 1, focusedItem) }
        assertEquals(before, position())
        assertFiniteBoundaryAndUsableFollow()
    }

    @Test fun bottomBoundaryPageDownAndEndReleaseWithoutLeavingDirectManipulation() {
        mount(atBottom = true)
        val before = position()
        for (key in listOf(AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_MOVE_END)) {
            compose.runOnIdle {
                key(key, AndroidKeyEvent.ACTION_DOWN)
                assertTrue(controller.debugState.value.directDragActive)
                key(key, AndroidKeyEvent.ACTION_UP)
                assertFalse(controller.debugState.value.directDragActive)
            }
        }
        compose.waitForIdle()
        assertEquals(before, position())
        assertFiniteBoundaryAndUsableFollow()
    }

    @Test fun consumedKeyboardScrollEndsAndOffersTheExplicitDetachedFollowAction() {
        mount()
        val before = position()
        compose.runOnIdle { key(AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.ACTION_DOWN) }
        compose.waitUntil(2_000) { position() != before }
        compose.runOnIdle { key(AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.ACTION_UP) }
        compose.waitUntil(2_000) {
            !controller.debugState.value.directDragActive && controller.mode.value is LyricsViewportMode.Detached
        }
        val action = compose.onNodeWithTag("input_lyrics").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Follow current lyric" }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitUntil(2_000) { controller.mode.value == LyricsViewportMode.Following && position() == before }
    }

    @Test fun wheelBurstsAtTheBoundaryExpireWithoutFakeMotionOrPermanentDrag() {
        mount()
        val before = position()
        val traceStart = compose.runOnIdle { controller.trace().lastOrNull()?.eventSequence ?: 0L }
        compose.onNodeWithTag("input_lyrics").performMouseInput {
            enter(center)
            scroll(-12f)
            advanceEventTime(40)
            scroll(-12f)
        }
        compose.waitUntil(2_000) { !controller.debugState.value.directDragActive }
        compose.runOnIdle {
            val events = controller.trace().filter { it.eventSequence > traceStart }.map { it.eventType }
            assertTrue("Wheel did not reach the production observer", "DirectDragStarted" in events)
            assertTrue("Wheel burst never ended", "DirectDragEnded" in events)
        }
        assertEquals(before, position())
        assertFiniteBoundaryAndUsableFollow()
    }

    private fun mount(atBottom: Boolean = false, realTimers: Boolean = false) {
        val lines = List(80) { TimedLyricLine(it * 1_000L, (it + 1) * 1_000L, "Line $it") }
        compose.setContent {
            StemwiseTheme {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = if (atBottom) 79 else 0)
                val composedScope = rememberCoroutineScope()
                // The engine's dwell evidence uses the real monotonic clock, not virtual test time.
                val scope = remember(composedScope) { if (realTimers)
                    CoroutineScope(composedScope.coroutineContext + Dispatchers.Main.immediate) else composedScope }
                val owner = remember { LyricsViewportController(state, scope) }
                val position = remember { mutableLongStateOf(if (atBottom) 79_000L else 0L) }
                playbackPosition = position
                list = state; controller = owner
                owner.setDebugOverlayAttached(true)
                owner.updateEnvironment(false, false, true)
                LaunchedEffect(owner) { owner.onLyricsLoaded("input-fixture", lines, if (atBottom) 79_000L else 0L) }
                LaunchedEffect(owner) { snapshotFlow { position.longValue }.distinctUntilChanged().collect(owner::onPlaybackPosition) }
                LaunchedEffect(owner) { state.interactionSource.interactions.collect(owner::onListInteraction) }
                LaunchedEffect(owner) {
                    snapshotFlow { owner.captureSnapshot() }.distinctUntilChanged().collect(owner::onViewportSnapshot)
                }
                Column {
                    Box(Modifier.size(20.dp).focusRequester(outsideFocus).focusable())
                    LazyColumn(Modifier.size(300.dp, 200.dp).focusRequester(listFocus)
                        .observeLyricsViewport(owner).testTag("input_lyrics"), state = state) {
                        items(lines.indices.toList(), key = { it }) { index ->
                            val focus = if (index == 0) Modifier.focusRequester(firstItemFocus) else Modifier
                            Text(lines[index].text, Modifier.height(48.dp).then(focus)
                                .onFocusChanged { if (it.isFocused) focusedItem = index }.focusable())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { listFocus.requestFocus() }
        compose.waitForIdle()
        if (atBottom) {
            compose.onNodeWithTag("input_lyrics").performScrollToIndex(79)
            compose.waitUntil(2_000) { !list.canScrollForward }
            compose.waitForIdle()
        }
        inputTraceStart = compose.runOnIdle { controller.trace().lastOrNull()?.eventSequence ?: 0L }
    }
    private fun key(code: Int, action: Int, repeat: Int = 0) {
        val now = android.os.SystemClock.uptimeMillis()
        compose.activity.dispatchKeyEvent(AndroidKeyEvent(now, now, action, code, repeat))
    }
    private fun position() = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
    private fun assertFiniteBoundaryAndUsableFollow() {
        compose.runOnIdle {
            assertFalse(controller.debugState.value.directDragActive)
            assertFalse(controller.mode.value is LyricsViewportMode.DirectManipulation)
            assertTrue("Unconsumed input invented a scroll delta", controller.trace().none {
                it.eventSequence > inputTraceStart && it.eventType == "UserScrollDelta"
            })
        }
        if (controller.mode.value != LyricsViewportMode.Following) {
            val action = compose.onNodeWithTag("input_lyrics").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].single { it.label == "Follow current lyric" }
            compose.runOnIdle { assertTrue("Detached input cannot restore follow", action.action()) }
            compose.waitUntil(2_000) { controller.mode.value == LyricsViewportMode.Following }
        }
    }
}
