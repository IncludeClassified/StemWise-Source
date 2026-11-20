package app.stemwise.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryInventoryAvailability
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSettings
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.PlaylistLinks
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsDialogAdmissionInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun settingsReentryReusesOnlyTheCleanCurrentRootMeasurementAndExplicitRefreshAlwaysMeasures() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val inventoryOwner = app.stemwise.data.LibraryStorageRefreshLifecycle()
        val firstMeasurement = CompletableDeferred<Unit>()
        val firstRoot = LibraryRootState(android.net.Uri.parse("content://fixture/tree/root-a"),
            "00000000-0000-0000-0000-000000000001", "Fixture A", true, epoch = 1L)
        val root = mutableStateOf(firstRoot)
        val visible = mutableStateOf(true)
        val measuredOwners = mutableListOf<Pair<String?, Long>>()
        var failNextMeasurement = false
        val requests = app.stemwise.data.LibraryStorageRefreshRequestOwner(scope) {
            val currentRoot = root.value
            inventoryOwner.refresh {
                measuredOwners += currentRoot.libraryId to currentRoot.epoch
                if (measuredOwners.size == 1) withTimeout(5_000L) { firstMeasurement.await() }
                if (failNextMeasurement) {
                    failNextMeasurement = false
                    error("Fixture measurement failure")
                }
                LibraryInventory(lastCheckedEpochMs = measuredOwners.size * 1_000L)
            }
        }
        try {
            compose.setContent { StemwiseTheme {
                val storage by inventoryOwner.state.collectAsState()
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.TextButton(onClick = { visible.value = !visible.value }) {
                        androidx.compose.material3.Text(if (visible.value) "Leave settings" else "Open settings")
                    }
                    if (visible.value) SettingsScreen(
                        playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                        mvsepState = MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED),
                        bridgeState = BridgeUiState(), onSaveBridgeConnection = { error("No save") },
                        onEnrollBridge = { error("No enrollment") }, onRefreshBridge = { error("No Bridge refresh") },
                        onWakeBridgePc = { error("No wake") }, libraryRootState = root.value,
                        storageRefreshState = storage, onChooseLibrary = {}, onRefreshStorage = { requests.request() },
                        onUpdatePlaylistTools = { error("No tools update") }, onSaveMvsepToken = { error("No token save") },
                        onRetryMvsep = { error("No token retry") }, onRemoveMvsepToken = { error("No token removal") })
                }
            } }
            fun leaveAndReenter() {
                compose.onNodeWithText("Leave settings").performClick()
                compose.onNodeWithText("Open settings").performClick()
                compose.waitForIdle()
            }
            fun awaitMeasurements(count: Int) {
                compose.waitUntil(5_000L) { !inventoryOwner.state.value.refreshing &&
                    inventoryOwner.state.value.inventory.lastCheckedEpochMs == count * 1_000L }
                compose.runOnIdle { assertEquals(count, measuredOwners.size) }
            }
            compose.runOnIdle { assertEquals(1, measuredOwners.size) }
            leaveAndReenter()
            compose.runOnIdle { assertEquals(1, measuredOwners.size); firstMeasurement.complete(Unit) }
            awaitMeasurements(1)
            leaveAndReenter()
            compose.runOnIdle { assertEquals(1, measuredOwners.size) }
            compose.runOnIdle { inventoryOwner.markInventoryDirty() }
            leaveAndReenter()
            awaitMeasurements(2)
            compose.onNodeWithContentDescription("Refresh storage totals").performScrollTo().performClick()
            awaitMeasurements(3)
            compose.runOnIdle { failNextMeasurement = true }
            compose.onNodeWithContentDescription("Refresh storage totals").performScrollTo().performClick()
            compose.waitUntil(5_000L) { inventoryOwner.state.value.errorMessage == "Fixture measurement failure" }
            compose.runOnIdle {
                assertEquals(4, measuredOwners.size)
                assertEquals(3_000L, inventoryOwner.state.value.inventory.lastCheckedEpochMs)
            }
            leaveAndReenter()
            awaitMeasurements(5)
            compose.runOnIdle {
                inventoryOwner.beginRestoration()
                root.value = firstRoot.copy(epoch = 2L)
            }
            awaitMeasurements(6)
            compose.onNodeWithText("Leave settings").performClick()
            compose.runOnIdle {
                inventoryOwner.beginRestoration(LibraryInventory())
                root.value = firstRoot.copy(treeUri = android.net.Uri.parse("content://fixture/tree/root-b"),
                    libraryId = "00000000-0000-0000-0000-000000000002", epoch = 3L)
            }
            compose.onNodeWithText("Open settings").performClick()
            awaitMeasurements(7)
            compose.runOnIdle {
                assertEquals(List(5) { firstRoot.libraryId to 1L } +
                    listOf(firstRoot.libraryId to 2L, root.value.libraryId to 3L), measuredOwners)
            }
        } finally { firstMeasurement.complete(Unit); scope.cancel() }
    }

    @Test fun settingsSectionsStayOpenWithoutSavingEnrollingOrWaking() {
        val actions = mutableListOf<String>()
        compose.setContent { StemwiseTheme {
            SettingsScreen(
                playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                    "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                mvsepState = MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED),
                bridgeState = BridgeUiState(connection = app.stemwise.bridge.BridgeConnectionProfile(
                    "fixture.tailnet.ts.net", "fixture-pi", "fixture-pc")),
                onSaveBridgeConnection = { actions += "save-bridge"; Result.success(Unit) },
                onEnrollBridge = { actions += "enroll"; Result.success(null) },
                onRefreshBridge = { actions += "check-bridge"; Result.success(Unit) },
                onWakeBridgePc = { actions += "wake"; Result.success(Unit) },
                libraryRootState = LibraryRootState(), storageRefreshState = LibraryStorageRefreshState(),
                onChooseLibrary = { actions += "choose-library" }, onRefreshStorage = {},
                onUpdatePlaylistTools = { actions += "update-tools"; Result.success(Unit) },
                onSaveMvsepToken = { actions += "save-token"; Result.success(Unit) },
                onRetryMvsep = { actions += "check-token"; Result.success(Unit) },
                onRemoveMvsepToken = { actions += "remove-token"; Result.success(Unit) })
        } }
        for (label in listOf("Connections", "Library & Storage", "Troubleshooting")) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check playlist tools for updates"))
        compose.onNodeWithText("Check playlist tools for updates").assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Library folder"))
        compose.onNodeWithText("Library folder").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("MVSEP"))
        compose.onNodeWithText("MVSEP").assertExists()
        compose.onNodeWithText("Open YouTube Music").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect MVSEP").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bridge: fixture-pi · PC: fixture-pc").assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        compose.onAllNodesWithText("Open").assertCountEquals(0)
        compose.onAllNodesWithText("Hide").assertCountEquals(0)
    }

    @Test fun requestedSectionScrollsToItsAlwaysOpenContent() {
        mount(initialSection = SystemSurfaceSection.TROUBLESHOOTING) { Result.success(Unit) }
        compose.onNodeWithText("Check playlist tools for updates").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("MVSEP"))
        compose.onNodeWithText("MVSEP").assertIsDisplayed()
    }

    private fun openMvsepConnection() = compose.onNode(
        (hasText("Connect MVSEP") or hasText("Edit MVSEP connection")) and hasClickAction()
    ).performScrollTo().performClick()

    private fun tokenInput() = compose.onNode(hasSetTextAction() and
        hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)))

    @Test fun closingTokenDialogRejectsItsPreviouslyCapturedConnectAction() {
        var saves = 0
        mount { saves++; Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        tokenInput().performTextInput("test-token")
        val inDialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        val connect = compose.onNode(hasText("Connect") and inDialog)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cancel = compose.onNode(hasText("Cancel") and inDialog)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { cancel(); connect() }
        compose.runOnIdle { assertEquals("A closing dialog cannot admit a save", 0, saves) }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.mainClock.autoAdvance = true
    }
    @Test fun busyTokenSaveAdmitsOnceAndFailureReopensTheStillVisibleDialog() {
        val release = CompletableDeferred<Unit>()
        var saves = 0
        mount {
            saves++
            withTimeout(5_000L) { release.await() }
            Result.failure(IllegalStateException("Fixture failure"))
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        tokenInput().performTextInput("test-token")
        val inDialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        val connect = compose.onNode(hasText("Connect") and inDialog)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cancel = compose.onNode(hasText("Cancel") and inDialog)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        try {
            compose.runOnIdle { connect(); connect(); cancel() }
            compose.runOnIdle { assertEquals(1, saves) }
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(1)
            compose.runOnIdle { release.complete(Unit) }
            compose.onNodeWithText("Fixture failure").assertExists()
            compose.onNode(hasText("Cancel") and inDialog).performClick()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        } finally { release.complete(Unit) }
    }

    @Test fun cancelledTokenOperationSettlesBusyAdmissionAndAllowsDismissal() {
        mount { throw CancellationException("Fixture cancellation") }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        tokenInput().performTextInput("test-token")
        val inDialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        compose.onNode(hasText("Connect") and inDialog).performClick()
        compose.onNode(hasText("Connect") and inDialog).assertIsEnabled()
        compose.onNode(hasText("Cancel") and inDialog).performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
    }

    @Test fun manualPlaylistToolsCheckAdmitsOnceAndPublishesTheEndpointFailure() {
        val release = CompletableDeferred<Unit>()
        var checks = 0
        mount(update = {
            checks++
            withTimeout(5_000) { release.await() }
            Result.failure(IllegalStateException("Release endpoint unavailable"))
        }, rootReady = true, save = { Result.success(Unit) })
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check playlist tools for updates"))
        val button = compose.onNodeWithText("Check playlist tools for updates")
        val action = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        try {
            compose.runOnIdle { action(); action() }
            compose.runOnIdle { assertEquals(1, checks) }
            compose.onNodeWithText("Checking playlist tools…").assertIsNotEnabled()
            compose.runOnIdle { release.complete(Unit) }
            compose.onNodeWithText("Could not update playlist tools. Try again later.").assertExists()
            compose.onNodeWithText("Check playlist tools for updates").assertIsEnabled()
        } finally { release.complete(Unit) }
    }

    @Test fun recreationDiscardsTokenDraftRevealAndDialogWithoutSaving() {
        val restoration = StateRestorationTester(compose)
        var saves = 0
        mount(restoration = restoration) { saves++; Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        tokenInput().performTextInput("private-draft-token")
        compose.onNodeWithText("Show token").performClick()
        compose.onNodeWithText("Hide token").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        openMvsepConnection()
        tokenInput().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Show token").assertExists()
        compose.onNodeWithText("Hide token").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun savedTokenDialogOpensOfflineWithoutValidationOrReplacement() {
        var saves = 0
        var retries = 0
        mount(
            connection = MvsepConnectionState(MvsepConnectionStatus.SAVED_UNVERIFIED,
                tokenStored = true, waitingForUnmeteredNetwork = true),
            retry = { retries++; Result.success(Unit) }
        ) { saves++; Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(1)
        compose.onNodeWithText("Replace MVSEP token").assertExists()
        tokenInput().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, retries) }
    }

    @Test fun readyAccountOpensItsTokenEditorDirectlyWithoutVerification() {
        var saves = 0
        var retries = 0
        mount(connection = MvsepConnectionState(MvsepConnectionStatus.READY, accountName = "Fixture account", tokenStored = true),
            retry = { retries++; Result.success(Unit) }) { saves++; Result.success(Unit) }
        compose.onNodeWithText("Fixture account").performScrollTo().assertIsDisplayed()
        openMvsepConnection()
        compose.onNodeWithText("Replace MVSEP token").assertExists()
        tokenInput().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Replace").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, retries) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Fixture account").performScrollTo().assertIsDisplayed()
    }

    @Test fun tokenEditCancelPreservesTheSavedConnectionAndDiscardsTheDraft() {
        var saves = 0
        var retries = 0
        mount(connection = MvsepConnectionState(MvsepConnectionStatus.ERROR, tokenStored = true,
            error = "Fixture MVSEP connection error"), retry = { retries++; Result.success(Unit) }) {
            saves++; Result.success(Unit)
        }
        compose.onNodeWithText("API token saved on this device").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture MVSEP connection error").performScrollTo().assertIsDisplayed()
        openMvsepConnection()
        tokenInput().performTextInput("discarded-token-draft")
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.onNodeWithText("API token saved on this device").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture MVSEP connection error").performScrollTo().assertIsDisplayed()
        openMvsepConnection()
        tokenInput().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, retries) }
    }

    @Test fun failedSavedConnectionLoadOffersAnExplicitLocalRetry() {
        var retries = 0
        var saves = 0
        mount(connection = app.stemwise.integration.mvsepLocalLoadFailureState(),
            retry = { retries++; Result.success(Unit) }) { saves++; Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        compose.onNodeWithText("Retry saved connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, saves) }
    }

    @Test fun unavailableHelpHandlerShowsStableCopyWithoutExposingTheException() {
        val unavailable = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("private-handler-detail") }
        }
        mount(context = unavailable) { Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Connections"))
        openMvsepConnection()
        compose.onNodeWithText("MVSEP details & get token").performScrollTo().performClick()
        compose.onNodeWithText("Could not open MVSEP details on this device").assertExists()
        compose.onNodeWithText("private-handler-detail", substring = true).assertDoesNotExist()
    }

    @Test fun wholeSettingsScreenOwnsMergedStorageConnectionAndRefreshAnnouncements() {
        val inventory = LibraryInventory(actualLibraryBytes = 1024L, appPrivateTotalBytes = 2048L,
            verifiedArtworkFiles = 2, verifiedJsonFiles = 3, verifiedStemFiles = 7,
            lastCheckedEpochMs = 1_783_571_400_000L)
        val storage = mutableStateOf(LibraryStorageRefreshState(inventory = inventory,
            availability = LibraryInventoryAvailability.VERIFIED))
        mount(connection = MvsepConnectionState(MvsepConnectionStatus.SAVED_UNVERIFIED, tokenStored = true),
            storage = { storage.value }) { Result.success(Unit) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("MVSEP"))
        compose.onNode(hasText("MVSEP") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Verify")).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Edit MVSEP connection").performScrollTo().assertHasClickAction()
        compose.onAllNodesWithText("VERIFY").assertCountEquals(0)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Library & Storage"))
        compose.onNodeWithText("Library files").performScrollTo().assertTextContains("1.0 KB")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("App data").assertTextContains("2.0 KB")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Artwork + JSON").assertTextContains("5 items")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithContentDescription("3.0 KB used")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("7 stems verified").assertExists()
        val timestamp = SemanticsMatcher("storage timestamp owner") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.startsWith("Storage totals last updated ") } == true
        }
        compose.onAllNodes(timestamp).assertCountEquals(1)
        compose.onNode(timestamp).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { storage.value = storage.value.copy(errorMessage = "private-provider-detail", errorCode = "IO_FAILURE") }
        compose.onNodeWithText("Could not refresh storage totals").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNode(timestamp).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("private-provider-detail", substring = true).assertDoesNotExist()
    }

    private fun mount(
        update: suspend () -> Result<Unit> = { Result.success(Unit) },
        rootReady: Boolean = false,
        initialSection: SystemSurfaceSection? = null,
        connection: MvsepConnectionState = MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED),
        retry: suspend () -> Result<Unit> = { Result.success(Unit) },
        storage: () -> LibraryStorageRefreshState = { LibraryStorageRefreshState() },
        context: Context? = null,
        restoration: StateRestorationTester? = null,
        save: suspend (String) -> Result<Unit>
    ) {
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalContext provides (context ?: LocalContext.current)) {
                StemwiseTheme {
                    SettingsScreen(
                        playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                            "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                        mvsepState = connection,
                        bridgeState = BridgeUiState(), onSaveBridgeConnection = { Result.success(Unit) }, onEnrollBridge = { Result.success(null) },
                        onRefreshBridge = { Result.success(Unit) }, onWakeBridgePc = { Result.success(Unit) },
                        libraryRootState = if (rootReady) LibraryRootState(android.net.Uri.parse("content://fixture/tree/root"),
                            "fixture-library", "Fixture", true, epoch = 1) else LibraryRootState(), storageRefreshState = storage(),
                        onChooseLibrary = {}, onRefreshStorage = {}, onUpdatePlaylistTools = update,
                        onSaveMvsepToken = save,
                        onRetryMvsep = retry, onRemoveMvsepToken = { Result.success(Unit) },
                        initialSection = initialSection
                    )
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }
}
