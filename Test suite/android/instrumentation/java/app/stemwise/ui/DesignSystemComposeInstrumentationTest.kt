package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertSame

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.LibraryInventoryAvailability
import app.stemwise.integration.EngineState
import app.stemwise.integration.EngineStatus
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseSystemFontFamily
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Connected acceptance owns real product composables; no private demo action tree is accepted. */
class DesignSystemComposeInstrumentationTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test
    fun qualificationViewportRendersTheMeasuredPhoneFrame() {
        var density = 1f
        val simulatePhone = androidx.compose.runtime.mutableStateOf(false)
        composeRule.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            app.stemwise.StemwisePhoneTestViewport(enabled = simulatePhone.value) {
                Box(Modifier.fillMaxSize().testTag("actual-phone-frame"))
            }
        }
        assertEquals("Ordinary qualification must use the production window",
            composeRule.onRoot().fetchSemanticsNode().boundsInRoot,
            composeRule.onNodeWithTag("actual-phone-frame").fetchSemanticsNode().boundsInRoot)
        composeRule.runOnIdle { simulatePhone.value = true }
        val frame = composeRule.onNodeWithTag("actual-phone-frame").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val expected = app.stemwise.stemwisePhoneTestViewportGeometry(
            true, root.width / density, root.height / density)
        if (expected == null) {
            assertEquals(root, frame)
        } else {
            assertEquals(expected.widthDp * density, frame.width, 1f)
            assertEquals(expected.heightDp * density, frame.height, 1f)
            val image = composeRule.onRoot().captureToImage().toPixelMap()
            val edge = image[(frame.left + ((density - 1f) / 2f).coerceAtLeast(0f)).toInt(), frame.center.y.toInt()]
            val purple = app.stemwise.ui.theme.PrimaryPurple
            assertEquals(purple.red, edge.red, .02f)
            assertEquals(purple.green, edge.green, .02f)
            assertEquals(purple.blue, edge.blue, .02f)
        }
    }

    @Test
    fun launchContentKeepsItsCompositionAndViewportThroughRecomposition() {
        val revision = mutableIntStateOf(0)
        val owners = mutableListOf<Any>()
        var mounts = 0
        var disposals = 0
        composeRule.setContent {
            StemwiseLaunchChoreography {
                val owner = remember { Any() }
                DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                SideEffect { owners += owner }
                Text("Content ${revision.intValue}", Modifier.fillMaxSize().testTag("launch-content"))
            }
        }
        val bounds = composeRule.onNodeWithTag("launch-content").fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { revision.intValue++ }
        composeRule.onNodeWithText("Content 1").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            owners.forEach { assertSame(owners.first(), it) }
        }
        assertEquals(bounds, composeRule.onNodeWithTag("launch-content").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun determinateProgressPublishesClampedSemanticsBeforeItsPixelsFinishAnimating() {
        val progress = mutableFloatStateOf(.1f)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                StemwiseTheme {
                    Box(Modifier.width(200.dp).testTag("actual-progress")) {
                        ProgressTrack(progress.floatValue, Color.Red, 20.dp)
                    }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            fun filledPixels(): Int {
                val pixels = composeRule.onNodeWithTag("actual-progress").captureToImage().toPixelMap()
                return (0 until pixels.width).count { x ->
                    val color = pixels[x, pixels.height / 2]
                    color.red > .8f && color.green < .2f && color.blue < .2f
                }
            }
            fun assertProgress(value: Float) {
                composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(value, 0f..1f))).assertExists()
            }
            val before = filledPixels()
            composeRule.runOnIdle { progress.floatValue = .9f }
            composeRule.mainClock.advanceTimeByFrame()
            assertProgress(.9f)
            val early = filledPixels()
            val recompositions = Recomposer.runningRecomposers.value.associateWith { it.changeCount }
            assertTrue("No active composition was observed", recompositions.isNotEmpty())
            composeRule.mainClock.advanceTimeBy(80)
            val middle = filledPixels()
            assertTrue("The fill stopped before its target", middle > early)
            assertEquals("Progress pixels caused per-frame recomposition", recompositions,
                Recomposer.runningRecomposers.value.associateWith { it.changeCount })
            composeRule.mainClock.advanceTimeBy(500)
            val finished = filledPixels()
            assertTrue("The rendered fill did not advance", finished > before * 4)
            assertTrue("The fill snapped to its target", early < finished)
            for ((input, expected) in listOf(-1f to 0f, 2f to 1f)) {
                composeRule.runOnIdle { progress.floatValue = input }
                composeRule.mainClock.advanceTimeByFrame()
                assertProgress(expected)
            }
        } finally { composeRule.mainClock.autoAdvance = true }
    }

    @Test
    fun determinateProgressFillsFromTheRightInRtl() {
        composeRule.setContent {
            StemwiseTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(200.dp).background(Color.Black).testTag("rtl-progress")) {
                        ProgressTrack(.25f, Color.Red, 20.dp)
                    }
                }
            }
        }
        val pixels = composeRule.onNodeWithTag("rtl-progress").captureToImage().toPixelMap()
        val left = pixels[pixels.width / 8, pixels.height / 2]
        val right = pixels[pixels.width * 7 / 8, pixels.height / 2]
        assertTrue("RTL progress painted from the left: $left", left.red < .8f)
        assertTrue("RTL progress did not reach the right edge: $right", right.red > .8f && right.green < .2f)
    }

    @Test
    fun renderedLibraryGatesExposeTheirHeadingActionsAndCurrentFailureOnly() {
        val recovery = mutableStateOf(false)
        val error = mutableStateOf<String?>("Folder access was not granted. Choose a folder to continue.")
        var choices = 0
        composeRule.setContent {
            StemwiseTheme {
                if (recovery.value) LibraryRecoveryGate("ROOT_PERMISSION_LOST", "Fixture permission failure",
                    onPrimaryAction = { choices++ }, onNotNow = {})
                else LibraryRootGate(errorCode = null, operationalError = error.value,
                    onChoose = { choices++; error.value = null })
            }
        }
        composeRule.onNodeWithText("Choose your Library folder").assertIsDisplayed()
        composeRule.onNodeWithText(checkNotNull(error.value)).assertIsDisplayed()
        composeRule.onNodeWithText("Choose folder").performClick()
        composeRule.onNodeWithText("Folder access was not granted. Choose a folder to continue.").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, choices); recovery.value = true }
        composeRule.onNodeWithText("No music or metadata was deleted.").assertExists()
        composeRule.onNodeWithText("Reconnect").performClick()
        composeRule.runOnIdle { assertEquals(2, choices) }
        composeRule.onNodeWithText("What is stored here").assertDoesNotExist()
    }

    @Test
    fun composeAuraReplacementKeepsReadyPixelsAndRepeatedDrawsDoNotRasterize() {
        val palette = androidx.compose.runtime.mutableStateOf(
            ArtworkAuraPalette(Color.Green, Color.Green, Color.Green, Color.Green))
        composeRule.setContent {
            StemwiseTheme {
                androidx.compose.foundation.layout.Box(Modifier.size(220.dp).background(Color.Black)
                    .testTag("prepared-compose-aura"), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    ArtworkAuraLayer(palette.value, compact = false, phaseOrdinal = 1,
                        cornerRadius = 12.dp, ambientMotionEnabled = false, modifier = Modifier.size(112.dp))
                }
            }
        }
        fun colorEnergy(): Pair<Float, Float> {
            val pixels = composeRule.onNodeWithTag("prepared-compose-aura").captureToImage().toPixelMap()
            var red = 0f; var green = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                red += pixels[x, y].red; green += pixels[x, y].green
            }
            return red to green
        }
        composeRule.waitUntil(5_000) { colorEnergy().second > 1f }
        val readyGenerations = ArtworkAuraTextureGenerationDiagnostics.snapshot()
        repeat(3) { assertTrue(colorEnergy().second > 1f) }
        assertEquals(readyGenerations, ArtworkAuraTextureGenerationDiagnostics.snapshot())
        val workerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        val workerFinished = java.util.concurrent.CountDownLatch(1)
        artworkAuraRasterDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable {
            workerEntered.countDown()
            try { check(releaseWorker.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            finally { workerFinished.countDown() }
        })
        try {
            assertTrue(workerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            composeRule.runOnIdle { palette.value = ArtworkAuraPalette(Color.Red, Color.Red, Color.Red, Color.Red) }
            val pending = colorEnergy()
            assertTrue("Pending replacement lost the ready green texture", pending.second > pending.first)
        } finally { releaseWorker.countDown() }
        assertTrue(workerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        composeRule.waitUntil(5_000) { val output = colorEnergy(); output.first > output.second }
    }

    @Test
    fun sharedMetronomeSliderThumbExpandsDuringContactAndReturnsOnRelease() {
        val sliderValue = mutableFloatStateOf(.5f)
        composeRule.setContent {
            StemwiseTheme {
                app.stemwise.ui.design.StemwiseSlider(
                    value = sliderValue.floatValue,
                    onValueChange = { next ->
                        if (next != sliderValue.floatValue) {
                            sliderValue.floatValue = next
                            true
                        } else false
                    },
                    modifier = Modifier.testTag("held-slider").background(Color.Black)
                )
            }
        }
        val slider = composeRule.onNodeWithTag("held-slider")
        fun whiteArea(): Int {
            val pixels = slider.captureToImage().toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.red > .95f && pixel.green > .95f && pixel.blue > .95f) count++
            }
            return count
        }
        val idle = whiteArea()
        slider.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        assertTrue("Contact did not enlarge the thumb", whiteArea() > idle * 1.5f)
        slider.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(200)
        assertEquals(idle, whiteArea())
    }

    @Test
    fun systemRtlCannotReverseTheCurrentEnglishApplicationChrome() {
        var observedDirection = LayoutDirection.Rtl
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                StemwiseEnglishChrome {
                    observedDirection = LocalLayoutDirection.current
                    StemwiseTheme {
                        SignalNavigation(
                            selected = 0,
                            visibleAlpha = { 1f },
                            interactive = true,
                            onSelected = {}
                        )
                    }
                }
            }
        }

        composeRule.onRoot(useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { assertEquals(LayoutDirection.Ltr, observedDirection) }
        val library = composeRule.onNodeWithText("Library").fetchSemanticsNode().boundsInRoot
        val tasks = composeRule.onNodeWithText("Activity").fetchSemanticsNode().boundsInRoot
        val system = composeRule.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Library was not physically first", library.center.x < tasks.center.x)
        assertTrue("Settings was not physically last", tasks.center.x < system.center.x)
    }


    @Test
    fun pageRootRendersTrueOledBlackAcrossTheEntireCanvas() {
        composeRule.setContent {
            StemwiseTheme {
                PageBackground(Modifier.testTag("oled-page-root")) {}
            }
        }

        val pixels = composeRule.onNodeWithTag("oled-page-root")
            .captureToImage()
            .toPixelMap()
        listOf(
            0 to 0,
            (pixels.width - 1) to 0,
            0 to (pixels.height - 1),
            (pixels.width - 1) to (pixels.height - 1),
            (pixels.width / 2) to (pixels.height / 2)
        ).forEach { (x, y) ->
            assertEquals("Root red at $x,$y", 0f, pixels[x, y].red, 0f)
            assertEquals("Root green at $x,$y", 0f, pixels[x, y].green, 0f)
            assertEquals("Root blue at $x,$y", 0f, pixels[x, y].blue, 0f)
            assertEquals("Root alpha at $x,$y", 1f, pixels[x, y].alpha, 0f)
        }
    }

}
