package app.stemwise.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.PlaylistSettings
import app.stemwise.integration.PlaylistLinks
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.enrichment.SharedEnrichmentImportState
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QueueImportInteractionTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun dismissedImportAndDiscardCallbacksCannotDispatchOrDiscardThePendingOffer() {
        data class Offer(val id: Int, val motion: Boolean)
        val offer = mutableStateOf(Offer(0, true))
        val actions = mutableListOf<Pair<Int, String>>()
        var dismissals = 0
        compose.setContent {
            val current = offer.value
            key(current.id) {
                CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(current.motion, false)) {
                    StemwiseTheme { QueueScreen(
                        onImportEnrichment = {},
                        playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                        syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = BridgeUiState(),
                        bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                        onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                        onRetryJob = {}, sharedImportState = SharedEnrichmentImportState.AwaitingConfirmation(
                            listOf(android.net.Uri.parse("content://fixture/song-${current.id}.zip")),
                            operationId = "operation-${current.id}"),
                        onConfirmSharedImport = { actions += current.id to "import" },
                        onDiscardSharedImport = { operation ->
                            assertEquals("operation-${current.id}", operation)
                            actions += current.id to "discard"
                        },
                        onDismissSharedImport = { dismissals++ }) }
                }
            }
        }
        val retired = mutableListOf<() -> Boolean>()
        var id = 0
        for (motion in listOf(true, false)) for (back in listOf(false, true)) {
            val current = Offer(++id, motion)
            compose.runOnIdle { offer.value = current }
            compose.onNodeWithText("Review import").performScrollTo().performClick()
            val confirm = compose.onNodeWithText("Import", substring = false)
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val discard = compose.onNodeWithText("Discard import")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val defer = compose.onNodeWithText("Not now")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance = false
            try {
                if (back) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                else compose.runOnIdle { defer() }
                compose.runOnIdle {
                    retired.forEach { it() }
                    repeat(2) { confirm(); discard() }
                    assertEquals(emptyList<Pair<Int, String>>(), actions)
                    assertEquals(0, dismissals)
                }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
                compose.onNodeWithText("Review import").assertExists()
                retired += confirm
                retired += discard
            } finally { compose.mainClock.autoAdvance = true }
        }
        compose.runOnIdle {
            retired.forEach { it() }
            assertEquals(emptyList<Pair<Int, String>>(), actions)
            assertEquals(0, dismissals)
        }
    }

    @Test fun importAndDiscardEachAdmitOnceBeforeClosing() {
        val source = android.net.Uri.parse("content://fixture/song.zip")
        val operation = SharedEnrichmentImportState.AwaitingConfirmation(listOf(source))
        val actions = mutableListOf<String>()
        compose.setContent { StemwiseTheme { QueueScreen(
            onImportEnrichment = {},
            playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
            syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = BridgeUiState(),
            bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
            onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
            onRetryJob = {}, sharedImportState = operation,
            onConfirmSharedImport = { actions += "import" },
            onDiscardSharedImport = { assertEquals(operation.operationId, it); actions += "discard" }) } }
        for (label in listOf("Import", "Discard import")) {
            compose.onNodeWithText("Review import").performScrollTo().performClick()
            val action = compose.onNodeWithText(label, substring = false)
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { action(); action() }
                compose.runOnIdle { assertEquals(if (label == "Import") listOf("import") else listOf("import", "discard"), actions) }
                compose.mainClock.advanceTimeBy(1_000L)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun sharedFilesDiscloseLibraryMutationBeforeOneImportAction() {
        val source = android.net.Uri.parse("content://fixture/song.zip")
        val state = mutableStateOf(SharedEnrichmentImportState.AwaitingConfirmation(listOf(source)))
        val actions = mutableListOf<String>()
        compose.setContent {
            StemwiseTheme {
                QueueScreen(onImportEnrichment = {},
                    playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                        "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                    syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = BridgeUiState(),
                    bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                    onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                    onRetryJob = {}, sharedImportState = state.value,
                    onConfirmSharedImport = {
                        actions += "import"
                    })
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithText("Review import").performScrollTo().performClick()
        compose.onNodeWithText("Import shared files?").assertIsDisplayed()
        compose.onNodeWithText("adds or updates matching Library songs", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNodeWithText("Import", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("import"), actions) }
    }

    @Test fun syncAdmissionAndTaskSemanticsFollowTheRenderedCurrentState() {
        val configured = mutableStateOf(false)
        val sync = mutableStateOf(PlaylistSyncStatus())
        val lastChecked = mutableStateOf<Long?>(null)
        var syncs = 0
        val current = app.stemwise.model.QueueJob(id = "current", songTitle = "Current fixture", artist = "Artist",
            kind = app.stemwise.model.JobKind.SEPARATION, state = app.stemwise.model.JobState.RUNNING,
            progress = .4f, detail = "Processing fixture", accent = androidx.compose.ui.graphics.Color.Blue)
        val queued = current.copy(id = "queued", songTitle = "Queued fixture",
            state = app.stemwise.model.JobState.WAITING)
        val unavailable = current.copy(id = "unavailable", songTitle = "Unavailable fixture",
            kind = app.stemwise.model.JobKind.LIBRARY, state = app.stemwise.model.JobState.NEEDS_ATTENTION,
            stableCode = "PLAYLIST_ITEM_UNAVAILABLE")
        compose.setContent {
            StemwiseTheme {
                QueueScreen(onImportEnrichment = {},
                    playlistSettings = if (configured.value) PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                        "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", lastChecked.value, null, true)
                        else PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                        "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, false),
                    syncStatus = sync.value, onSync = { syncs++; true }, bridgeState = BridgeUiState(),
                    bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                    onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                    jobs = listOf(current, queued, unavailable), onRetryJob = {})
            }
        }
        compose.onNodeWithContentDescription("Sync playlist").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Not checked yet").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { configured.value = true }
        compose.onNodeWithContentDescription("Sync playlist").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, syncs) }
        compose.runOnIdle { lastChecked.value = 1_700_000_000_000L }
        val checked = "Last checked successfully: " + java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            .format(java.util.Date(1_700_000_000_000L))
        compose.onNodeWithText(checked).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not checked yet").assertDoesNotExist()
        val active = compose.onNodeWithText("Current fixture").performScrollTo().fetchSemanticsNode()
        assertEquals("Processing", active.config[SemanticsProperties.StateDescription])
        org.junit.Assert.assertFalse(active.config.contains(SemanticsProperties.LiveRegion))
        compose.onAllNodesWithText("Current fixture").assertCountEquals(1)
        val waiting = compose.onNodeWithText("Queued fixture").performScrollTo().fetchSemanticsNode()
        assertEquals("Queued", waiting.config[SemanticsProperties.StateDescription])
        compose.onAllNodesWithText("Queued fixture").assertCountEquals(1)
        compose.onNodeWithText("Unavailable fixture").assertDoesNotExist()
        compose.runOnIdle { sync.value = PlaylistSyncStatus(queued = true) }
        compose.onNodeWithContentDescription("Queued playlist sync").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(checked).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { sync.value = PlaylistSyncStatus(error = "Fixture sync error") }
        compose.onNodeWithText(checked).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, syncs) }
    }

    @Test fun retirementPendingImportShowsItsFailureWithoutOfferingAnotherCancel() {
        var cancels = 0
        compose.setContent {
            StemwiseTheme { QueueScreen(
                onImportEnrichment = {},
                playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890")),
                    "Fixture", null, null, true),
                syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = BridgeUiState(),
                bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                onRetryJob = {}, onCancelImport = { cancels++ },
                sharedImportState = SharedEnrichmentImportState.Running(total = 3,
                    phase = "The import has not stopped. Force stop and reopen StemWise.", retirementPending = true)) }
        }
        compose.onNodeWithText("Import needs attention").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The import has not stopped. Force stop and reopen StemWise.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel import").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, cancels) }
    }

    @Test fun runningImportLeavesNavigationAndPlaybackUsableAndCancelReachable() {
        val progress = mutableStateOf(SharedEnrichmentImportState.Running(total = 3, phase = "Copying first song",
            progressKind = app.stemwise.enrichment.ImportProgressKind.FILES, copiedBytes = 512L, totalBytes = 1_024L))
        val tasksVisible = mutableStateOf(true)
        var navigation = 0
        var pauses = 0
        var cancellations = 0
        compose.setContent {
            StemwiseTheme {
                Column {
                    TextButton(onClick = { tasksVisible.value = !tasksVisible.value; navigation++ }) { Text("Switch tab") }
                    TextButton(onClick = { pauses++ }) { Text("Pause playback") }
                    if (tasksVisible.value) QueueScreen(
                        onImportEnrichment = {},
                        playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890")),
                            "Fixture", null, null, true),
                        syncStatus = PlaylistSyncStatus(), onSync = { true }, bridgeState = BridgeUiState(),
                        bridgeTasks = emptyList(), onOpenAddSong = {}, onReviewDraft = {},
                        onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                        onRetryJob = {}, sharedImportState = progress.value, onCancelImport = { cancellations++ })
                    else Text("Library tab")
                }
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(0.5f, 0f..1f))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause playback").performClick()
        compose.onNodeWithText("Switch tab").performClick()
        compose.onNodeWithText("Library tab").assertIsDisplayed()
        compose.runOnIdle { progress.value = progress.value.copy(completed = 1, phase = "Copying second song") }
        compose.onNodeWithText("Library tab").assertIsDisplayed()
        compose.onNodeWithText("Switch tab").performClick()
        compose.onNodeWithText("Cancel import").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, navigation); assertEquals(1, pauses); assertEquals(1, cancellations) }
    }
}
