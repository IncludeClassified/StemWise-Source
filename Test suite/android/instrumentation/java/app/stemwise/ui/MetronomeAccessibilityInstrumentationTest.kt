package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import app.stemwise.analysis.MeterSegment
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stemwise.ui.theme.StemwiseTheme
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.analysis.BeatEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class MetronomeAccessibilityInstrumentationTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    @Test fun fixedOverlayKeepsBeatBackedControlsReachableAtLargeTextAndShortLandscapeSizes() {
        data class Host(val source: PlaybackSourceMode, val width: Float, val height: Float, val scale: Float)
        val host = mutableStateOf(Host(PlaybackSourceMode.ORIGINAL, 360f, 800f, 1f))
        var powerCommands = 0
        var bpmCommand = 0f
        var subdivisionCommand = 0
        var volumeCommand = 0f
        compose.setContent {
            // Keep the landscape host inside the device window; dp constraints and sp scaling
            // remain those of the production overlay, independent of physical pixel density.
            CompositionLocalProvider(LocalDensity provides Density(1f, host.value.scale)) {
                StemwiseTheme {
                    key(host.value) {
                        val current = host.value
                        val detail = portraitPlayerDetailViewportHeightDp(current.width, current.height,
                            current.source, PLAYER_DETAIL_METRONOME, fontScale = current.scale)
                        Box(Modifier.width((current.width - portraitPlayerHorizontalPaddingDp(current.width) * 2f).dp)
                            .height(detail.dp).testTag("metronome-fixed-overlay")) {
                            Box(Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp)) {
                                MetronomePanel(enabled = true, bpm = 120f, subdivision = 1,
                                    numerator = 4, denominator = 4, volume = .6f,
                                    compactRows = detail < playerDetailReservedHeightDp(PLAYER_DETAIL_METRONOME),
                                    beatEvents = listOf(BeatEvent(0L, true), BeatEvent(500L, false)),
                                    onToggle = { powerCommands++; true }, onBpm = { bpmCommand = it; true },
                                    onSubdivision = { subdivisionCommand = it; true }, onVolume = { volumeCommand = it; true })
                            }
                        }
                    }
                }
            }
        }
        for (source in PlaybackSourceMode.entries) for ((width, height) in listOf(360f to 800f, 640f to 360f, 640f to 800f)) {
            for (scale in listOf(1f, 1.5f, 2f)) {
                compose.runOnIdle {
                    host.value = Host(source, width, height, scale)
                    powerCommands = 0; bpmCommand = 0f; subdivisionCommand = 0; volumeCommand = 0f
                }
                if (width == 640f && height == 800f && scale == 1f) {
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy), useUnmergedTree = true)
                        .assertCountEquals(0)
                }
                fun reveal(matcher: SemanticsMatcher): androidx.compose.ui.test.SemanticsNodeInteraction {
                    val target = compose.onNode(matcher, useUnmergedTree = true)
                    if (compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()) target.performScrollTo()
                    val node = target.fetchSemanticsNode()
                    if (node.config.contains(SemanticsActions.SetProgress) || node.config.contains(SemanticsActions.OnClick)) {
                        assertTrue("$source $width/$height scale=$scale clipped control: ${node.boundsInRoot}",
                            node.boundsInRoot.height >= 48f && node.boundsInRoot.width >= 48f)
                    }
                    return target.assertIsDisplayed()
                }
                reveal(hasContentDescription("Metronome")).performClick()
                reveal(actionableProgressNode("Metronome BPM"))
                    .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(137f)) }
                reveal(hasContentDescription("2 clicks per beat", substring = true)).performClick()
                for (label in listOf("Beats", "4", "Beat unit", "Quarter note")) {
                    reveal(androidx.compose.ui.test.hasText(label))
                }
                reveal(actionableProgressNode("Metronome volume"))
                    .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.375f)) }
                compose.runOnIdle {
                    assertEquals("$source $width/$height scale=$scale", 1, powerCommands)
                    assertEquals(137f, bpmCommand, 0f)
                    assertEquals(2, subdivisionCommand)
                    assertEquals(.375f, volumeCommand, 0f)
                }
            }
        }
    }

    @Test fun volumePercentFeedbackFollowsPointerContactWithoutAddingAnAccessibilityAnnouncement() {
        val volume = mutableFloatStateOf(.6f)
        compose.setContent {
            StemwiseTheme {
                Box(Modifier.width(328.dp)) {
                    MetronomePanel(enabled = true, bpm = 120f, subdivision = 1, numerator = 4,
                        denominator = 4, volume = volume.floatValue, onToggle = { true }, onBpm = { true },
                        onSubdivision = { true }, onVolume = { volume.floatValue = it; true })
                }
            }
        }
        var pointerDown = false
        val slider = compose.onNode(actionableProgressNode("Metronome volume"), useUnmergedTree = true)
        val density = compose.density.density
        try {
            slider.performTouchInput {
                val thumbRadius = 8f * density
                val track = width - 2f * thumbRadius
                down(Offset(thumbRadius + track * .6f, centerY))
                // Cross Material's drag slop before returning to the exact middle of its track.
                moveTo(Offset(thumbRadius + track * .35f, centerY), 32L)
                moveTo(center, 32L)
            }
            pointerDown = true
            compose.waitUntil(5_000L) {
                slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current < .6f
            }
            val heldPreview = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertTrue("Drag must change the actual preview", heldPreview > 0f && heldPreview < .6f)
            compose.runOnIdle { assertEquals("Parent volume commits only on release", .6f, volume.floatValue, 0f) }
            val percentLabel = "${(heldPreview * 100).roundToInt()}%"
            try {
                compose.waitUntil(5_000L) {
                    try {
                        compose.onNodeWithText(percentLabel, useUnmergedTree = true).assertIsDisplayed()
                        true
                    } catch (_: AssertionError) { false }
                }
            } catch (failure: Throwable) {
                val labels = compose.onAllNodesWithText("%", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }
                throw AssertionError("Held volume feedback unavailable: preview=$heldPreview, committed=${volume.floatValue}, labels=$labels", failure)
            }
            compose.onNodeWithText(percentLabel, useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodesWithText(percentLabel, useUnmergedTree = false).assertCountEquals(0)
            slider.performTouchInput { up() }
            pointerDown = false
            compose.waitUntil(5_000L) {
                compose.onAllNodesWithText(percentLabel, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            compose.onAllNodesWithText(percentLabel, useUnmergedTree = true).assertCountEquals(0)
            compose.runOnIdle { assertEquals("Release commits the displayed preview", heldPreview, volume.floatValue, 0f) }
        } finally {
            if (pointerDown) slider.performTouchInput { cancel() }
        }
    }

    @Test
    fun enlargedLabelsFitTheirLineBoxesAndCompactControlsRetainTouchWidth() {
        val fontScale = mutableFloatStateOf(1f)
        val width = mutableFloatStateOf(328f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.floatValue)) {
                StemwiseTheme {
                    Box(Modifier.width(width.floatValue.dp).testTag("authored-meter-panel")) {
                        MetronomePanel(
                            enabled = true, bpm = 240f, subdivision = 1,
                            numerator = 32, denominator = 4, volume = .6f,
                            onToggle = { true }, onBpm = { true }, onSubdivision = { true },
                            onVolume = { true }
                        )
                    }
                }
            }
        }
        for (availableWidth in listOf(300f, 328f, 392f)) {
          for (scale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { width.floatValue = availableWidth; fontScale.floatValue = scale }
            for (label in listOf("240", "BPM", "Subdivision", "Beats", "Beat unit", "Volume")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("$label at $scale", 1, layouts.size)
                assertFalse("$label clips vertically at $scale", layouts.single().didOverflowHeight)
                val layout = layouts.single()
                assertTrue("$label clips horizontally at $scale: ${layout.size}",
                    layout.multiParagraph.maxIntrinsicWidth <= layout.size.width + 1f)
            }
            for (label in listOf("Metronome BPM", "Metronome volume")) {
                assertMinimumTarget(actionableProgressNode(label))
            }
            val panel = compose.onNodeWithTag("authored-meter-panel").fetchSemanticsNode().boundsInRoot
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { action ->
                    val bounds = action.boundsInRoot
                    assertTrue("Control outside actual $availableWidth-dp panel at $scale: $bounds",
                        bounds.left >= panel.left - 1f && bounds.right <= panel.right + 1f)
                }
            for (retired in listOf("Decrease beats", "Increase beats", "Previous beat unit, longer note", "Next beat unit, shorter note")) {
                compose.onAllNodes(hasContentDescription(retired), useUnmergedTree = true).assertCountEquals(0)
            }
          }
        }
    }

    @Test
    fun readOnlyMeterFollowsTheAuthoredSegmentAtThePlaybackCursor() {
        val position = mutableLongStateOf(0L)
        compose.setContent {
            StemwiseTheme {
                MetronomePanel(
                    enabled = true, bpm = 120f, subdivision = 1,
                    numerator = 7, denominator = 16, volume = .6f,
                    onToggle = { true }, onBpm = { true }, onSubdivision = { true }, onVolume = { true },
                    positionState = position,
                    meterSegments = listOf(MeterSegment(0L, 1000L, 4, 4), MeterSegment(1000L, 2000L, 3, 8))
                )
            }
        }
        compose.onNodeWithText("4", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Quarter note", useUnmergedTree = true).assertExists()
        compose.runOnIdle { position.longValue = 1500L }
        compose.onNodeWithText("3", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Eighth note", useUnmergedTree = true).assertExists()
        for (retired in listOf("Decrease beats", "Increase beats", "Previous beat unit, longer note", "Next beat unit, shorter note")) {
            compose.onAllNodes(hasContentDescription(retired), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test
    fun slidersExposeOneFortyEightDpNodeAndCommitSetProgress() {
        lateinit var bpm: MutableFloatState
        lateinit var volume: MutableFloatState
        compose.setContent {
            bpm = remember { mutableFloatStateOf(120f) }
            volume = remember { mutableFloatStateOf(.6f) }
            StemwiseTheme {
                MetronomePanel(
                    enabled = true,
                    bpm = bpm.floatValue,
                    subdivision = 1,
                    numerator = 4,
                    denominator = 4,
                    volume = volume.floatValue,
                    onToggle = { true },
                    onBpm = { bpm.floatValue = it; true },
                    onSubdivision = { true },
                    onVolume = { volume.floatValue = it; true }
                )
            }
        }

        val bpmMatcher = actionableProgressNode("Metronome BPM")
        val volumeMatcher = actionableProgressNode("Metronome volume")
        val decreaseBpm = hasContentDescription("Decrease metronome BPM")
        val increaseBpm = hasContentDescription("Increase metronome BPM")
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
            useUnmergedTree = false
        ).assertCountEquals(2)
        compose.onAllNodes(bpmMatcher, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(volumeMatcher, useUnmergedTree = true).assertCountEquals(1)
        assertMinimumTarget(bpmMatcher)
        assertMinimumTarget(volumeMatcher)
        assertMinimumTarget(decreaseBpm)
        assertMinimumTarget(increaseBpm)

        compose.onNode(decreaseBpm, useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(119f, bpm.floatValue, 0f) }
        compose.onNode(increaseBpm, useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(120f, bpm.floatValue, 0f) }

        compose.onNode(bpmMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set ->
                assertTrue(set(136.6f))
            }
        compose.onNode(volumeMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set ->
                assertTrue(set(.375f))
            }
        compose.runOnIdle {
            assertEquals(137f, bpm.floatValue, 0f)
            assertEquals(.375f, volume.floatValue, 0f)
        }

        compose.onNode(bpmMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(120f)) }
        compose.onNode(volumeMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set ->
                assertTrue(set(.6f))
            }
        compose.runOnIdle {
            assertEquals(120f, bpm.floatValue, 0f)
            assertEquals(.6f, volume.floatValue, 0f)
        }
    }

    @Test
    fun bpmDraggingUsesSpeedResistanceWithoutHoldOrVerticalFineMode() {
        lateinit var bpm: MutableFloatState
        compose.setContent {
            bpm = remember { mutableFloatStateOf(120f) }
            StemwiseTheme {
                Box(Modifier.width(360.dp)) {
                    MetronomePanel(
                        enabled = true, bpm = bpm.floatValue, subdivision = 1,
                        numerator = 4, denominator = 4, volume = .6f,
                        onToggle = { true }, onBpm = { bpm.floatValue = it; true },
                        onSubdivision = { true }, onVolume = { true }
                    )
                }
            }
        }
        val slider = compose.onNode(actionableProgressNode("Metronome BPM"))
        val density = compose.density.density
        fun drag(duration: Long, hold: Boolean = false, reverse: Boolean = false): Float {
            compose.runOnIdle { bpm.floatValue = 120f }
            compose.waitForIdle()
            slider.performTouchInput {
                val track = width - 16f * density
                val start = Offset(8f * density + track * .4f, centerY)
                down(start)
                if (hold) {
                    advanceEventTime(1_500L)
                    moveTo(start + Offset(0f, -60f * density))
                }
                val origin = start + if (hold) Offset(0f, -60f * density) else Offset.Zero
                for (step in 1..20) {
                    moveTo(origin + Offset(60f * density * step / 20f, 0f), duration / 20)
                }
                if (reverse) {
                    // Reverse after reaching the right clamp while still holding the pointer.
                    moveTo(origin + Offset(250f * density, 0f), 16L)
                    moveTo(origin + Offset(210f * density, 0f), 16L)
                }
                up()
            }
            compose.waitForIdle()
            return bpm.floatValue
        }
        val slow = drag(3_000L)
        val fast = drag(100L)
        assertTrue("Slow motion must retain resistance: $slow", slow in 121f..133f)
        assertTrue("Fast motion must cover more BPM: slow=$slow fast=$fast", fast > slow + 35f)
        val held = drag(100L, hold = true)
        assertEquals("Holding or moving vertically must not enter fine mode", fast, held, 4f)
        val reversed = drag(100L, reverse = true)
        assertTrue("Reversal must respond immediately from the clamp: $reversed", reversed < 225f)
        slider.performTouchInput { click(Offset(width - 8f * density, centerY)) }
        compose.runOnIdle { assertEquals(240f, bpm.floatValue, 0f) }
    }

    @Test
    fun rejectedProgressCommandsKeepAuthoritativeValues() {
        compose.setContent {
            StemwiseTheme {
                MetronomePanel(
                    enabled = true,
                    bpm = 120f,
                    subdivision = 1,
                    numerator = 4,
                    denominator = 4,
                    volume = .6f,
                    onToggle = { false },
                    onBpm = { false },
                    onSubdivision = { false },
                    onVolume = { false }
                )
            }
        }

        val bpmMatcher = actionableProgressNode("Metronome BPM")
        val volumeMatcher = actionableProgressNode("Metronome volume")
        compose.onNode(bpmMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set ->
                assertFalse(set(180f))
            }
        compose.onNode(volumeMatcher, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.SetProgress) { set ->
                assertFalse(set(.2f))
            }
        compose.onNode(
            bpmMatcher and SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "120 BPM"
            ),
            useUnmergedTree = true
        ).assertExists()
        compose.onNode(
            volumeMatcher and SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "60 percent"
            ),
            useUnmergedTree = true
        ).assertExists()
    }

    private fun actionableProgressNode(label: String): SemanticsMatcher =
        hasContentDescription(label) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private fun assertMinimumTarget(matcher: SemanticsMatcher) {
        val bounds = compose.onNode(matcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .resources
            .displayMetrics
            .density
        assertTrue("width was ${bounds.width / density} dp", bounds.width / density >= 48f)
        assertTrue("height was ${bounds.height / density} dp", bounds.height / density >= 48f)
    }
}
