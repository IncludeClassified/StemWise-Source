package app.stemwise.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.activity.BackEventCompat
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class PlayerSheetMotionInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun upwardFlingAtFullNeverMovesTheSheetPastItsExpandedAnchor() {
        lateinit var state: SheetState
        compose.setContent {
            StemwiseTheme {
                val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
                val motion = playerSheetMotionSpecs(true, .5f)
                SideEffect { state = sheet }
                BottomSheetScaffold(
                    scaffoldState = rememberBottomSheetScaffoldState(sheet),
                    sheetPeekHeight = 68.dp, sheetDragHandle = null,
                    sheetContent = {
                        // Match production: Material installs its defaults before this child effect.
                        SideEffect { motion.install(sheet) }
                        Box(Modifier.fillMaxSize().testTag("full-sheet-swipe"))
                    }
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val expanded = compose.runOnIdle { state.requireOffset() }
        var minimumOffset = expanded
        var maximumOffset = expanded
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("full-sheet-swipe").performTouchInput {
                swipe(Offset(width * .5f, height * .75f), Offset(width * .5f, height * .2f), 160L)
            }
            repeat(40) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    minimumOffset = minOf(minimumOffset, state.requireOffset())
                    maximumOffset = maxOf(maximumOffset, state.requireOffset())
                }
            }
            compose.runOnIdle {
                assertTrue("Upward release moved the sheet above Expanded: $minimumOffset < $expanded",
                    minimumOffset >= expanded - 1f)
                assertTrue("Upward release rebounded away from Expanded: $maximumOffset > $expanded",
                    maximumOffset <= expanded + 1f)
                assertEquals(SheetValue.Expanded, state.currentValue)
                assertEquals(expanded, state.requireOffset(), 1f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun predictiveBackPreviewsReversesCancelsAndCommitsWithLyricsPrecedence() {
        lateinit var state: SheetState
        val lyrics = mutableStateOf(false)
        val preview = mutableStateOf(false)
        compose.setContent {
            StemwiseTheme {
                val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
                SideEffect { state = sheet }
                PlayerPredictiveBackHandler(
                    enabled = !lyrics.value && (preview.value || sheet.currentValue == SheetValue.Expanded ||
                        sheet.targetValue == SheetValue.Expanded), state = sheet,
                    motion = playerSheetMotionSpecs(true, .5f), onPreviewChanged = { preview.value = it })
                Box(Modifier.fillMaxSize()) {
                    BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(sheet),
                        sheetPeekHeight = 68.dp, sheetDragHandle = null,
                        sheetContent = { Box(Modifier.fillMaxSize()) }) { Box(Modifier.fillMaxSize()) }
                    ExpandedLyricsOverlay(visible = lyrics.value, onDismiss = { lyrics.value = false },
                        song = Song(id = "predictive", videoId = "Predictive1", title = "Predictive lyrics", artist = "Fixture",
                            durationMs = 30_000L, state = SongState.READY, accent = Color.Cyan, artworkGlyph = "P"),
                        presentationGeneration = 1L, positionState = remember { mutableLongStateOf(0L) }, onSeek = { _, _ -> true })
                }
            }
        }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        var expanded = 0f
        var collapsed = 0f
        compose.runOnIdle {
            expanded = state.requireOffset()
            collapsed = StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.PartiallyExpanded)
            dispatcher.dispatchOnBackStarted(backEvent(0f))
        }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.4f)) }
        compose.runOnIdle { assertEquals(expanded + (collapsed - expanded) * .4f, state.requireOffset(), 1f) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.2f)) }
        compose.runOnIdle { assertEquals(expanded + (collapsed - expanded) * .2f, state.requireOffset(), 1f) }
        compose.mainClock.autoAdvance = false
        try {
            val previewOffset = compose.runOnIdle { state.requireOffset() }
            compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            compose.runOnIdle {
                assertEquals("Cancellation must retain its rendered preview until the spring advances",
                    previewOffset, state.requireOffset(), 1f)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertTrue("Cancellation must animate back through an intermediate position",
                    state.requireOffset() > expanded + 1f && state.requireOffset() < previewOffset - 1f)
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle {
                assertEquals(expanded, state.requireOffset(), 1f)
                assertEquals(SheetValue.Expanded, state.currentValue)
                assertTrue(!preview.value)
            }
        } finally { compose.mainClock.autoAdvance = true }

        compose.runOnIdle { lyrics.value = true }
        val lyricsSheet = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Lyrics"))
        val before = lyricsSheet.getUnclippedBoundsInRoot()
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.2f)) }
        assertTrue(lyricsSheet.getUnclippedBoundsInRoot().top > before.top)
        compose.runOnIdle { assertEquals(expanded, state.requireOffset(), 1f); dispatcher.dispatchOnBackCancelled() }
        assertEquals(before, lyricsSheet.getUnclippedBoundsInRoot())
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.3f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("expanded_lyrics_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(SheetValue.Expanded, state.currentValue) }
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.3f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, state.currentValue); assertEquals(collapsed, state.requireOffset(), 1f) }
    }

    @Test fun reducedMotionBackStillFollowsTheFingerAndSettlesAtTheRealAnchor() {
        lateinit var state: SheetState
        compose.setContent {
            val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
            SideEffect { state = sheet }
            PlayerPredictiveBackHandler(true, sheet, playerSheetMotionSpecs(false, .5f))
            BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(sheet), sheetPeekHeight = 68.dp,
                sheetDragHandle = null, sheetContent = { Box(Modifier.fillMaxSize()) }) { Box(Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        var expanded = 0f
        var collapsed = 0f
        compose.runOnIdle {
            expanded = state.requireOffset()
            collapsed = StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.PartiallyExpanded)
            dispatcher.dispatchOnBackStarted(backEvent(0f))
        }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.5f)) }
        compose.runOnIdle { assertEquals((expanded + collapsed) / 2f, state.requireOffset(), 1f) }
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.runOnIdle {
            assertEquals(SheetValue.Expanded, state.currentValue)
            assertEquals(expanded, state.requireOffset(), 1f)
        }
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.5f)) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, state.currentValue); assertEquals(collapsed, state.requireOffset(), 1f) }
    }

    @Test fun committedBackSpringCanBeGrabbedAndReversedBeforeItSettles() {
        lateinit var state: SheetState
        val collecting = mutableStateOf(false)
        compose.setContent {
            val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
            SideEffect { state = sheet }
            PlayerPredictiveBackHandler(true, sheet, playerSheetMotionSpecs(true, .5f),
                onPreviewChanged = { collecting.value = it }, onGestureReleased = { collecting.value = false })
            Box(Modifier.fillMaxSize().testTag("predictive-root")) {
                BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(sheet), sheetPeekHeight = 68.dp,
                    sheetSwipeEnabled = !collecting.value, sheetDragHandle = null,
                    sheetContent = { Box(Modifier.fillMaxSize()) }) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.4f)) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("predictive-root").performTouchInput {
            swipe(Offset(width * .5f, height * .75f), Offset(width * .5f, height * .15f), 200L)
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(SheetValue.Expanded, state.currentValue) }
    }

    @Test fun cancelledBackSpringCannotRetakeTheSheetFromANewerGesture() {
        lateinit var state: SheetState
        val collecting = mutableStateOf(false)
        compose.setContent {
            val sheet = rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
            SideEffect { state = sheet }
            PlayerPredictiveBackHandler(true, sheet, playerSheetMotionSpecs(true, .5f),
                onPreviewChanged = { collecting.value = it }, onGestureReleased = { collecting.value = false })
            Box(Modifier.fillMaxSize().testTag("cancelled-back-root")) {
                BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(sheet), sheetPeekHeight = 68.dp,
                    sheetSwipeEnabled = !collecting.value, sheetDragHandle = null,
                    sheetContent = { Box(Modifier.fillMaxSize()) }) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        val collapsed = compose.runOnIdle {
            StemwiseSheetMotionBridge.anchorOffset(state, SheetValue.PartiallyExpanded)
        }
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.4f)) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            val interruptedOffset = compose.runOnIdle { state.requireOffset() }
            compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
            compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.2f)) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("The retired cancellation must not clear the new gesture's ownership", collecting.value)
                assertEquals(interruptedOffset + (collapsed - interruptedOffset) * .2f,
                    state.requireOffset(), 1f)
                dispatcher.dispatchOnBackCancelled()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("cancelled-back-root").performTouchInput {
                swipe(Offset(width * .5f, height * .5f), Offset(width * .5f, height * .95f), 200L)
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle {
                assertEquals(SheetValue.PartiallyExpanded, state.currentValue)
                assertEquals(collapsed, state.requireOffset(), 1f)
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle { assertEquals(collapsed, state.requireOffset(), 1f) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun replacingSheetRetiresItsCancelledBackRestoration() {
        lateinit var state: SheetState
        val revision = mutableLongStateOf(0L)
        val preview = mutableStateOf(false)
        compose.setContent {
            val sheet = key(revision.longValue) {
                rememberStandardBottomSheetState(initialValue = SheetValue.Expanded, skipHiddenState = true)
            }
            SideEffect { state = sheet }
            PlayerPredictiveBackHandler(true, sheet, playerSheetMotionSpecs(true, .5f),
                onPreviewChanged = { preview.value = it })
            BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(sheet), sheetPeekHeight = 68.dp,
                sheetDragHandle = null, sheetContent = { Box(Modifier.fillMaxSize()) }) { Box(Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(backEvent(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.4f)) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(32)
            val retiredSheet = compose.runOnIdle { state }
            compose.runOnIdle { revision.longValue++ }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue("The test did not replace the production sheet owner", retiredSheet !== state)
                assertTrue("Replacement retained the retired preview", !preview.value)
                dispatcher.dispatchOnBackStarted(backEvent(0f))
            }
            compose.runOnIdle { dispatcher.dispatchOnBackProgressed(backEvent(.2f)) }
            val replacementOffset = compose.runOnIdle { state.requireOffset() }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle {
                assertTrue("Retired restoration cleared the replacement gesture", preview.value)
                assertEquals(replacementOffset, state.requireOffset(), 1f)
                dispatcher.dispatchOnBackCancelled()
            }
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle {
                assertTrue(!preview.value)
                assertEquals(SheetValue.Expanded, state.currentValue)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    @Test fun realSheetReinstallsDirectionalMotionAndCancelledExpandCannotRetakeTheAnchor() {
        lateinit var state: SheetState
        lateinit var scope: CoroutineScope
        val motion = PlayerSheetMotionSpecs(
            expand = tween(1_200, easing = LinearEasing),
            collapse = tween(160, easing = LinearEasing),
            dragSettle = spring(dampingRatio = .82f, stiffness = 420f)
        )
        compose.setContent {
            val sheet = rememberStandardBottomSheetState(
                initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
            val compositionScope = rememberCoroutineScope()
            SideEffect { state = sheet; scope = compositionScope; motion.install(sheet) }
            BottomSheetScaffold(
                scaffoldState = rememberBottomSheetScaffoldState(sheet),
                sheetPeekHeight = 68.dp, sheetDragHandle = null,
                sheetContent = { Box(Modifier.fillMaxSize()) }
            ) { Box(Modifier.fillMaxSize()) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        var collapsedOffset = 0f
        lateinit var expanding: Job
        compose.runOnIdle {
            collapsedOffset = state.requireOffset()
            expanding = scope.launch { state.expandWithStemwiseMotion(motion) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle {
            assertTrue("The retained expand spec must still be in flight", expanding.isActive)
            assertTrue(state.requireOffset() in 1f..(collapsedOffset - 1f))
            expanding.cancel()
            scope.launch { state.collapseWithStemwiseMotion(motion) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(250)
        compose.runOnIdle {
            assertEquals(SheetValue.PartiallyExpanded, state.currentValue)
            assertEquals(collapsedOffset, state.requireOffset(), 1f)
        }
        // A late frame from the cancelled owner cannot restore its expanded destination.
        compose.mainClock.advanceTimeBy(1_500)
        compose.runOnIdle { assertEquals(SheetValue.PartiallyExpanded, state.currentValue) }
        compose.mainClock.autoAdvance = true
    }
}
