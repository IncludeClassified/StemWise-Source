package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stemwise.bridge.BridgeSongPhase
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.integration.PlaylistLinks
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class BridgeTaskRelocationInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test
    fun sectionChangeMovesTheExistingTaskThroughAnIntermediatePosition() {
        val tasks = mutableStateOf(initialTasks())
        compose.setContent { Activity(tasks) }
        compose.waitForIdle()
        val before = movingTop()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.RUNNING) else it
            } }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val middle = movingTop()
            compose.mainClock.advanceTimeBy(300)
            val finished = movingTop()
            assertTrue("The fixture did not move between sections", before > finished + 20f)
            assertTrue("The task snapped instead of traversing its relocation",
                middle > finished + 1f && middle < before - 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun sameSectionPollingAndNewMountsDoNotLaunchRelocationOrArrivalFades() {
        val tasks = mutableStateOf(initialTasks())
        compose.setContent { Activity(tasks) }
        compose.waitForIdle()
        val before = movingTop()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.WAITING_PC, stage = "Polled") else it
            } }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Waiting for PC", useUnmergedTree = true).assertExists()
            assertEquals(before, movingTop(), 1f)
            compose.mainClock.advanceTimeBy(64)
            assertEquals(before, movingTop(), 1f)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(before, movingTop(), 1f)

            compose.runOnIdle { tasks.value = tasks.value + BridgeSongTask(
                "new", "wxyzabcdefg", "New task", BridgeSongPhase.QUEUED, "Queued", createdAtEpochMs = 3L) }
            compose.mainClock.advanceTimeByFrame()
            val newRow = compose.onNodeWithTag("activity-bridge-task-new")
            val arrivalBounds = newRow.fetchSemanticsNode().boundsInRoot
            val arrivalBrightness = rowBrightness("new")
            compose.mainClock.advanceTimeBy(300)
            assertEquals(arrivalBounds, newRow.fetchSemanticsNode().boundsInRoot)
            assertEquals("A newly mounted task faded despite having no previous visible owner",
                arrivalBrightness, rowBrightness("new"), .005f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun reducedMotionSnapsTheDestinationAndCrossfadesItsActualPixels() {
        val tasks = mutableStateOf(initialTasks())
        compose.setContent { Activity(tasks, reducedMotion = true) }
        compose.waitForIdle()
        val before = movingTop()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.RUNNING) else it
            } }
            compose.mainClock.advanceTimeByFrame()
            val destination = movingTop()
            val earlyBrightness = rowBrightness(MOVING_ID)
            compose.mainClock.advanceTimeBy(48)
            assertEquals("Reduced motion translated the card", destination, movingTop(), 1f)
            val middleBrightness = rowBrightness(MOVING_ID)
            compose.mainClock.advanceTimeBy(160)
            val finishedBrightness = rowBrightness(MOVING_ID)
            assertTrue("The fixture did not change groups", before > destination + 20f)
            assertEquals(destination, movingTop(), 1f)
            assertTrue("Reduced motion omitted its short opacity bridge",
                finishedBrightness > earlyBrightness + .005f)
            assertTrue("The card did not fade through visible intermediate pixels",
                middleBrightness > earlyBrightness && middleBrightness < finishedBrightness)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun heldPointerDefersRegroupingWhileCurrentTaskStatusRemainsLive() {
        val tasks = mutableStateOf(initialTasks())
        compose.setContent { Activity(tasks) }
        compose.waitForIdle()
        val before = movingTop()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            compose.onNodeWithText(MOVING_TITLE, useUnmergedTree = true).performTouchInput {
                down(Offset(2f, centerY))
            }
            held = true
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.RUNNING) else it
            } }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithText("Preparing song", useUnmergedTree = true).assertCountEquals(2)
            assertEquals("Regrouping moved a task underneath the held pointer", before, movingTop(), 1f)
            compose.onNodeWithText(MOVING_TITLE, useUnmergedTree = true).performTouchInput { up() }
            held = false
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val middle = movingTop()
            compose.mainClock.advanceTimeBy(300)
            val finished = movingTop()
            assertTrue("Releasing the pointer did not apply the pending group", before > finished + 20f)
            assertTrue("Deferred regrouping lost its relocation bridge",
                middle > finished + 1f && middle < before - 1f)
        } finally {
            if (held) compose.onNodeWithText(MOVING_TITLE, useUnmergedTree = true).performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun rapidSectionRetargetContinuesFromItsVisiblePositionAndSettlesTheLatestGroup() {
        val tasks = mutableStateOf(initialTasks())
        compose.setContent { Activity(tasks) }
        compose.waitForIdle()
        val original = movingTop()
        val upperDestination = compose.onNodeWithText("Existing running task", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val travel = original - upperDestination
        assertTrue("The fixture has no distinct section destinations", travel > 20f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.RUNNING) else it
            } }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            // Compare consecutive steps after the easing's initial acceleration, while still moving.
            compose.mainClock.advanceTimeBy(48)
            val previousFrame = movingTop()
            compose.mainClock.advanceTimeByFrame()
            val interrupted = movingTop()
            assertTrue("The first relocation did not start", interrupted < original - 1f)
            assertTrue("The first relocation already settled", interrupted > upperDestination + 1f)
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.WAITING_PC) else it
            } }
            compose.mainClock.advanceTimeByFrame()
            val retargeted = movingTop()
            // One clock frame may advance the outgoing tween, but must not add an anchor jump.
            assertTrue("Retargeting jumped: previous=$previousFrame interrupted=$interrupted retargeted=$retargeted original=$original target=$upperDestination",
                abs(retargeted - interrupted) <= abs(previousFrame - interrupted) + 1f)
            compose.mainClock.advanceTimeBy(64)
            assertTrue("The retargeted task did not reverse toward its latest group", movingTop() > retargeted)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(original, movingTop(), 1f)
            compose.mainClock.advanceTimeBy(300)
            assertEquals("The retired relocation took back the task", original, movingTop(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test
    fun heldCancelButtonKeepsItsTaskThroughLiveStatusUpdatesAndActsOnceOnRelease() {
        val tasks = mutableStateOf(initialTasks().map {
            if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.RUNNING) else it
        })
        val cancellations = mutableListOf<String>()
        compose.setContent { Activity(tasks, onCancel = { id ->
            cancellations += id
            Result.success(Unit)
        }) }
        compose.waitForIdle()
        val movingRow = hasAnyAncestor(hasTestTag("activity-bridge-task-$MOVING_ID"))
        compose.onNode(hasText("Task details") and movingRow).performClick()
        compose.waitForIdle()
        val cancelButton = compose.onNode(hasText("Cancel PC task") and movingRow)
        val heldBounds = cancelButton.fetchSemanticsNode().boundsInRoot
        val before = movingTop()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            cancelButton.performTouchInput { down(center) }
            held = true
            compose.runOnIdle { tasks.value = tasks.value.map {
                if (it.id == MOVING_ID) it.copy(phase = BridgeSongPhase.WAITING_PC, stage = "Polling update") else it
            } }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText("Waiting for PC", useUnmergedTree = true).assertExists()
            assertEquals(heldBounds, cancelButton.fetchSemanticsNode().boundsInRoot)
            assertEquals(before, movingTop(), 1f)
            compose.runOnIdle {
                assertTrue("A held button dispatched before release", cancellations.isEmpty())
                tasks.value = tasks.value.map {
                    if (it.id == MOVING_ID) it.copy(stage = "Another polling update") else it
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            assertEquals(heldBounds, cancelButton.fetchSemanticsNode().boundsInRoot)
            cancelButton.performTouchInput { up() }
            held = false
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertEquals(listOf(MOVING_ID), cancellations) }
            assertTrue("Release never applied the task's latest group", movingTop() > before + 20f)
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertEquals("The accepted action replayed during relocation",
                listOf(MOVING_ID), cancellations) }
        } finally {
            if (held) cancelButton.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun rowBrightness(id: String): Float {
        val pixels = compose.onNodeWithTag("activity-bridge-task-$id").captureToImage().toPixelMap()
        var total = 0f
        var count = 0
        for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
            val pixel = pixels[x, y]
            total += pixel.red + pixel.green + pixel.blue
            count++
        }
        return total / (count * 3f)
    }

    private fun movingTop(): Float = compose.onNodeWithText(MOVING_TITLE, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.top

    @Composable
    private fun Activity(
        tasks: MutableState<List<BridgeSongTask>>,
        reducedMotion: Boolean = false,
        onCancel: suspend (String) -> Result<Unit> = { error("No Bridge action expected") }
    ) {
        StemwiseTheme {
            CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(
                enabled = !reducedMotion, ambientMotionEnabled = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    QueueScreen(
                        onImportEnrichment = {},
                        playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, false),
                        syncStatus = PlaylistSyncStatus(), onSync = { false }, bridgeState = BridgeUiState(),
                        bridgeTasks = tasks.value,
                        onRetryBridgeTask = { error("No Bridge action expected") },
                        onForgetBridgeTask = { error("No Bridge action expected") },
                        onCancelBridgeTask = onCancel,
                        onRetryJob = {}, onOpenAddSong = {}, onReviewDraft = {}
                    )
                }
            }
        }
    }

    private fun initialTasks() = listOf(
        BridgeSongTask("other", "abcdefghijk", "Existing running task", BridgeSongPhase.RUNNING,
            "Working", createdAtEpochMs = 1L),
        BridgeSongTask(MOVING_ID, "lmnopqrstuv", MOVING_TITLE, BridgeSongPhase.QUEUED,
            "Queued", createdAtEpochMs = 2L)
    )

    companion object {
        private const val MOVING_ID = "moving"
        private const val MOVING_TITLE = "Relocating task"
    }
}
