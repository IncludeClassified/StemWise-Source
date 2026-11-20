package app.stemwise.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.stemwise.ui.theme.StemwiseTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerNavigationVisibilityTest {
    @get:Rule
    val compose = createStemwiseComposeRule()

    @Test
    fun bottomSafeAreaHasNoDestinationHitTarget() {
        var selected = -1
        var insetPx = 0
        var rowHeightPx = 0
        compose.setContent {
            StemwiseTheme {
                val density = LocalDensity.current
                insetPx = with(density) { 24.dp.roundToPx() }
                rowHeightPx = with(density) { 72.dp.roundToPx() }
                Box(Modifier.size(width = 360.dp, height = 120.dp)) {
                    SignalNavigation(0, { 1f }, true,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag("safe-navigation"),
                        bottomInsetDp = with(density) { insetPx.toDp().value }, onSelected = { selected = it })
                }
            }
        }
        val nav = compose.onNodeWithTag("safe-navigation")
        val bounds = nav.fetchSemanticsNode().boundsInRoot
        val tasks = compose.onNodeWithText("Activity").fetchSemanticsNode().boundsInRoot
        assertEquals(rowHeightPx + insetPx, bounds.height.roundToInt())
        assertTrue("Destination $tasks overlaps the $insetPx px safe area in $bounds",
            tasks.bottom <= bounds.bottom - insetPx)
        nav.performTouchInput {
            for (fraction in listOf(1f / 6f, .5f, 5f / 6f)) {
                click(androidx.compose.ui.geometry.Offset(width * fraction, height - insetPx + .5f))
                click(androidx.compose.ui.geometry.Offset(width * fraction, height - 2f))
            }
        }
        compose.runOnIdle { assertEquals(-1, selected) }
        compose.onNodeWithText("Activity").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test
    fun expandedPlayerKeepsNavigationMeasuredButRemovesSemanticsAndInput() {
        var selected = -1
        var underlyingClicks = 0
        compose.setContent {
            StemwiseTheme {
                Box(Modifier.size(width = 360.dp, height = 80.dp)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("player-tool-underlay")
                            .clickable { underlyingClicks++ }
                    )
                    SignalNavigation(
                        selected = 0,
                        visibleAlpha = { 0f },
                        interactive = false,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelected = { selected = it }
                    )
                }
            }
        }

        compose.onNodeWithText("Library").assertDoesNotExist()
        compose.onNodeWithText("Activity").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithTag("player-tool-underlay", useUnmergedTree = true)
            .performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(-1, selected)
            assertEquals(1, underlyingClicks)
        }
    }

    @Test
    fun miniPlayerNavigationRemainsVisibleAndInteractive() {
        var selected = -1
        compose.setContent {
            StemwiseTheme {
                SignalNavigation(
                    selected = 0,
                    visibleAlpha = { 1f },
                    interactive = true,
                    onSelected = { selected = it }
                )
            }
        }

        compose.onNodeWithText("Activity").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test
    fun expandedPlayerHidesOnlyCoveredDestinationSemantics() {
        val modal = mutableStateOf(false)
        compose.setContent {
            StemwiseTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .testTag("covered-destination")
                            .clickable { }
                            .hideDestinationSemantics(modal.value)
                    ) { Text("Destination action") }
                    Button(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onClick = { modal.value = !modal.value }
                    ) { Text(if (modal.value) "Collapse player" else "Expand player") }
                }
            }
        }

        compose.onNodeWithText("Destination action").assertExists()
        compose.onNodeWithText("Expand player").performClick()
        compose.onNodeWithText("Destination action").assertDoesNotExist()
        compose.onNodeWithText("Collapse player").assertExists().performClick()
        compose.onNodeWithText("Destination action").assertExists()
    }
}
