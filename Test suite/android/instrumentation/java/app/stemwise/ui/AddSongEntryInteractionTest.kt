package app.stemwise.ui

import androidx.compose.ui.test.*
import app.stemwise.bridge.BridgeUiState
import app.stemwise.bridge.BridgeModelCatalog
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AddSongEntryInteractionTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun activityImportPillOpensTheZipPickerDirectlyAndCancellationDoesNotImport() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pickerFilter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        val monitor = instrumentation.addMonitor(pickerFilter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        var imports = 0
        var pcReviews = 0
        try {
            compose.setContent { StemwiseTheme {
                QueueScreen(onImportEnrichment = { imports++ },
                    playlistSettings = app.stemwise.data.PlaylistSettings(checkNotNull(
                        app.stemwise.integration.PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890")),
                        "Fixture", null, null, false),
                    syncStatus = app.stemwise.data.PlaylistSyncStatus(), onSync = { false },
                    bridgeState = BridgeUiState(enrollmentRequired = true), bridgeTasks = emptyList(),
                    onOpenAddSong = { pcReviews++ }, onReviewDraft = {},
                    onRetryBridgeTask = { error("No retry") }, onForgetBridgeTask = { error("No forget") },
                    onRetryJob = {})
            } }
            compose.onAllNodesWithText("Import ZIP").assertCountEquals(1)
            compose.onNodeWithText("Send to PC").assertIsDisplayed()
            compose.onNodeWithText("Add a song").assertDoesNotExist()
            compose.onNodeWithText("No activity yet.").assertExists()
            compose.onNodeWithText("Your songs and their progress appear here").assertDoesNotExist()
            compose.onNodeWithText("Import ZIP").performClick()
            compose.waitUntil(2_000L) { monitor.hits == 1 }
            compose.waitForIdle()
            compose.onNodeWithText("Import ZIP").assertIsDisplayed()
            compose.onNodeWithText("Song link").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, imports); assertEquals(0, pcReviews) }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun openingPcReviewReadsOptionsButNeverWakesOrSubmits() {
        var catalogReads = 0
        var wakes = 0
        var submissions = 0
        compose.setContent { StemwiseTheme {
            BridgeSongDialog(BridgeUiState(), onSubmit = { submissions++; Result.success(Unit) },
                onLoadModels = { catalogReads++; Result.success(BridgeModelCatalog(emptyList())) },
                onWakeForOptions = { wakes++; Result.success(Unit) }, onDismiss = {})
        } }
        compose.onNodeWithText("Song link").assertExists()
        compose.onNodeWithText("Import prepared ZIP").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, catalogReads); assertEquals(0, wakes); assertEquals(0, submissions) }
    }
}
