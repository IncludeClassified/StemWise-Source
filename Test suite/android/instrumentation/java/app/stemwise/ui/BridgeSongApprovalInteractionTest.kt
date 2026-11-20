package app.stemwise.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.stemwise.bridge.BridgeModelCatalog
import app.stemwise.bridge.BridgeModelOption
import app.stemwise.bridge.BridgeServiceTier
import app.stemwise.bridge.BridgeSongSubmission
import app.stemwise.bridge.BridgeSongPhase
import app.stemwise.bridge.BridgeSongTask
import app.stemwise.bridge.BridgeUiState
import app.stemwise.bridge.BridgeConnectionProfile
import app.stemwise.bridge.BridgeSendPreparation
import app.stemwise.bridge.approveBridgeSong
import app.stemwise.bridge.newBridgeSong
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.data.RootAuthority
import app.stemwise.integration.PlaylistLinks
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred

class BridgeSongApprovalInteractionTest {
    @Test fun closeFirstRejectsAStaleSendWhileTheRealModalIsClosing() = closeFirstRejectsBackendAction(false)

    @Test fun closeFirstRejectsAStaleWakeWhileTheRealModalIsClosing() = closeFirstRejectsBackendAction(true)

    private fun closeFirstRejectsBackendAction(wake: Boolean) {
        val visible = mutableStateOf(true)
        val pending = CompletableDeferred<Result<Unit>>()
        var starts = 0
        var dismissals = 0
        compose.setContent { StemwiseTheme {
            if (visible.value) BridgeSongDialog(
                BridgeUiState(connection = BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")),
                initialText = firstLink,
                onLoadModels = { if (wake) Result.failure(IllegalStateException("Fixture offline")) else Result.success(catalog) },
                onSubmit = { starts++; kotlinx.coroutines.withTimeout(5_000L) { pending.await() } },
                onWakeForOptions = { starts++; kotlinx.coroutines.withTimeout(5_000L) { pending.await() } },
                onDismiss = { dismissals++; visible.value = false })
        } }
        val actionText = if (wake) "Wake PC and load options" else "Send to Bridge"
        val action = compose.onNodeWithText(actionText).performScrollTo().assertIsEnabled()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val close = compose.onNodeWithText("Close").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { close(); action(); action(); assertEquals(0, starts) }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
            compose.runOnIdle { assertEquals(1, dismissals); assertEquals(0, starts) }
        } finally {
            pending.complete(Result.failure(IllegalStateException("Fixture stopped")))
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun directPcPillOpensAllSettingsAndOnlySendAuthorizesTheReviewedSong() {
        val submissions = mutableListOf<BridgeSongSubmission>()
        var reads = 0
        val gate = CompletableDeferred<Result<Unit>>()
        val adding = mutableStateOf(false)
        compose.setContent { StemwiseTheme {
            QueueScreen(onImportEnrichment = {},
                playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                    "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                syncStatus = PlaylistSyncStatus(), onSync = { false }, bridgeState = BridgeUiState(),
                bridgeTasks = emptyList(), onOpenAddSong = { adding.value = true }, onReviewDraft = {},
                onRetryBridgeTask = { error("No retry") }, onForgetBridgeTask = { error("No delete") },
                onRetryJob = {})
            if (adding.value) BridgeSongDialog(BridgeUiState(),
                onSubmit = { submissions += it; gate.await() },
                onLoadModels = { reads++; Result.success(catalog) }, onDismiss = { adding.value = false })
        } }
        compose.onNodeWithText("Send to PC").assertIsDisplayed()
        compose.onNodeWithText("Song link").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads); assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Send to PC").performClick()
        val dialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement(firstLink)
        compose.onNode(dialog and hasText("Model: Fixture model")).assertExists()
        compose.onNode(dialog and hasText("Intelligence: Balanced")).assertExists()
        compose.onNode(dialog and hasText("Speed: Standard")).assertExists()
        compose.runOnIdle { assertEquals(1, reads); assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Close").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Send to PC").performClick()
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement(editedLink)
        val close = compose.onNodeWithText("Close").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val send = compose.onNode(dialog and hasText("Send to Bridge")).performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { send(); close() }
        compose.onNodeWithText("Close").assertIsNotEnabled()
        compose.onNodeWithText("Sending to Bridge…").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(BridgeSongSubmission(editedLink, "fixture-model", "medium", "standard")), submissions)
            gate.complete(Result.failure(IllegalStateException("Fixture send failed")))
        }
        compose.onNodeWithText("Fixture send failed").assertExists()
        compose.onNodeWithText("Close").assertIsEnabled()
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .assertTextContains(editedLink)
    }

    @Test fun bridgeFailureIsVisibleWithoutOpeningTaskDetailsAndExactResponseCanBeRead() {
        val original = "native_turn_failed: Unauthorized — fixture response kept in full"
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeTaskCard(BridgeSongTask("job", "abcdefghijk", "Add song · abcdefghijk",
                    BridgeSongPhase.FAILED, "native_turn_failed", message = original),
                    onRetry = { error("No retry") }, onForget = { error("No delete") },
                    onCancel = { error("No cancel") }, diagnostics = { error("No copy") })
            }
        } }
        compose.onNodeWithText("YouTube song").assertExists()
        compose.onNodeWithText("Could not prepare song").assertExists()
        compose.onNodeWithText("The PC needs you to sign in before it can prepare this song.").assertIsDisplayed()
        compose.onNodeWithText("Copy task diagnostics").assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText(original).assertExists()
        compose.onNodeWithText("Hide error details").performClick()
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText("The PC needs you to sign in before it can prepare this song.").assertExists()
    }

    @Test fun connectionSetupKeepsEditedFieldsUntilSaveSucceeds() {
        val state = mutableStateOf(BridgeUiState())
        var saved: app.stemwise.bridge.BridgeConnectionProfile? = null
        var gate = CompletableDeferred<Result<Unit>>()
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeConnectionPanel(state.value, onSaveConnection = { profile ->
                    saved = profile
                    kotlinx.coroutines.withTimeout(5_000L) { gate.await() }.onSuccess { state.value = BridgeUiState(connection = profile) }
                }, onEnroll = { error("No enrollment") }, onRefresh = { error("No refresh") }, onWakePc = { error("No wake") })
            }
        } }
        compose.onNodeWithText("Wake PC").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Edit connection").performScrollTo().performClick()
        for ((label, value) in listOf("Bridge Tailscale hostname" to "bridge.tailnet.ts.net", "Bridge identity" to "fixture-pi", "PC identity" to "fixture-pc")) {
            compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        }
        compose.onNodeWithText("Save connection").assertIsDisplayed().performClick()
        compose.onNodeWithText("Saving connection…").assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Result.failure(IllegalStateException("Fixture save failure"))) }
        compose.waitForIdle()
        compose.onNodeWithText("Save connection").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("bridge.tailnet.ts.net").assertExists()
        compose.runOnIdle { gate = CompletableDeferred() }
        compose.onNodeWithText("Save connection").performClick()
        compose.runOnIdle { gate.complete(Result.success(Unit)) }
        compose.waitForIdle()
        compose.onNodeWithText("Edit connection").performScrollTo().assertIsEnabled()
        assertEquals("bridge.tailnet.ts.net", saved?.host)
        assertEquals(saved, state.value.connection)
    }

    @Test fun connectionCardKeepsIdentityAndErrorsExpandedAndCancelDiscardsOnlyTheDraft() {
        val original = app.stemwise.bridge.BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")
        val state = mutableStateOf(BridgeUiState(connection = original, piStatus = "Online", pcStatus = "Asleep",
            observedAtElapsedMs = android.os.SystemClock.elapsedRealtime(), validForMs = 60_000L,
            error = "Fixture bridge error", connectionIssue = "Fixture connection issue"))
        var saves = 0
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeConnectionPanel(state.value, onSaveConnection = { saves++; Result.success(Unit) },
                    onEnroll = { error("No enrollment") }, onRefresh = { error("No refresh") }, onWakePc = { error("No wake") })
            }
        } }
        compose.onNodeWithText(original.host).assertExists()
        compose.onNodeWithText("Bridge: fixture-pi · PC: fixture-pc").assertExists()
        compose.onNodeWithText("Pi: Online · PC: Asleep").assertExists()
        compose.onNodeWithText("Fixture bridge error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture connection issue").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertCountEquals(0)
        compose.onNodeWithText("Edit connection").performScrollTo().performClick()
        compose.onNodeWithText("Bridge Tailscale hostname").performTextReplacement("changed.tailnet.ts.net")
        val staleSave = compose.onNodeWithText("Save connection").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { cancel(); staleSave() }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.mainClock.autoAdvance = true
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, saves); assertEquals(original, state.value.connection) }
        compose.onNodeWithText("Edit connection").performScrollTo().performClick()
        compose.onNodeWithText("Bridge Tailscale hostname").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(original.host)))
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { state.value = state.value.copy(observedAtElapsedMs = null) }
        compose.onNodeWithText("Pi: Unknown · PC: Unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture bridge error").assertExists()
        compose.onNodeWithText("Fixture connection issue").assertExists()
    }

    @Test fun unsupportedBridgeHostsStayInTheEditorWithoutSavingOrChangingTheConnection() {
        val original = app.stemwise.bridge.BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")
        val state = mutableStateOf(BridgeUiState(connection = original))
        var saves = 0
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeConnectionPanel(state.value, onSaveConnection = { saves++; Result.success(Unit) },
                    onEnroll = { error("No enrollment") }, onRefresh = { error("No refresh") }, onWakePc = { error("No wake") })
            }
        } }
        compose.onNodeWithText("Edit connection").performScrollTo().performClick()
        for (host in listOf("127.0.0.1", "bridge.example", "Bridge.tailnet.ts.net")) {
            compose.onNodeWithText("Bridge Tailscale hostname").performScrollTo().performTextReplacement(host)
            compose.onNodeWithText("Save connection").assertIsDisplayed().performClick()
            compose.onNodeWithText("Enter a lowercase Tailscale hostname ending in .ts.net, without a URL or port.")
                .assertExists()
            compose.onNodeWithText(host).assertExists()
            compose.onNodeWithText("Check connection").assertIsNotEnabled()
            compose.onNodeWithText("Wake PC").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(0, saves); assertEquals(original, state.value.connection) }
        }
    }

    @get:Rule val compose = createStemwiseComposeRule()
    private val firstLink = "https://youtu.be/abcdefghijk"
    private val editedLink = "https://youtu.be/lmnopqrstuv"
    private val catalog = BridgeModelCatalog(listOf(BridgeModelOption(
        id = "fixture-model", label = "Fixture model", efforts = listOf("medium", "high"),
        defaultEffort = "medium", serviceTiers = listOf(
            BridgeServiceTier("standard", "Standard", "Standard processing"),
            BridgeServiceTier("fast", "Fast", "Faster processing")
        ), defaultServiceTier = "standard", isDefault = true
    )))

    @Test fun taskActionsRejectRepeatedAndConflictingClicksBeforeRecompositionAndReleaseAfterFailure() {
        val task = mutableStateOf(BridgeSongTask("initial", "abcdefghijk", "Guarded task", BridgeSongPhase.WAITING, ""))
        lateinit var gate: CompletableDeferred<Result<Unit>>
        val calls = mutableListOf<String>()
        suspend fun invoke(kind: String): Result<Unit> { calls += kind; return gate.await() }
        compose.setContent { StemwiseTheme {
            BridgeTaskCard(task.value, onRetry = { invoke("resume") }, onForget = { invoke("forget") },
                onCancel = { invoke("cancel") }, diagnostics = { invoke("diagnostics").map { "Fixture diagnostics" } })
        } }
        for ((kind, label, pending) in listOf(
            Triple("resume", "Resume song", "Resuming…"),
            Triple("cancel", "Cancel PC task", "Requesting cancellation…"),
            Triple("forget", "Forget task", "Forgetting task…"),
            Triple("diagnostics", "Copy task diagnostics", "Copying task diagnostics…")
        )) {
            gate = CompletableDeferred()
            compose.runOnIdle {
                task.value = task.value.copy(id = "job-$kind", retryable = kind == "resume",
                    phase = when (kind) { "resume" -> BridgeSongPhase.WAITING; "cancel" -> BridgeSongPhase.RUNNING; else -> BridgeSongPhase.FAILED })
            }
            compose.onNodeWithText("Task details").performClick()
            val click = compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val otherLabel = if (kind == "diagnostics") "Forget task" else "Copy task diagnostics"
            val other = compose.onNodeWithText(otherLabel).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle { click(); click(); other() }
            compose.onNodeWithText(pending).assertIsNotEnabled()
            compose.onNodeWithText(otherLabel).assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(listOf(kind), calls)
                gate.complete(Result.failure(IllegalStateException("Fixture failure")))
            }
            compose.onNodeWithText("Fixture failure", substring = true).assertExists()
            compose.onNodeWithText(label).assertIsEnabled()
            gate = CompletableDeferred()
            compose.onNodeWithText(label).performClick()
            compose.runOnIdle {
                assertEquals(listOf(kind, kind), calls)
                // Keep diagnostics local: this test must not overwrite the owner's clipboard.
                gate.complete(if (kind == "diagnostics") Result.failure(IllegalStateException("Fixture failure")) else Result.success(Unit))
            }
            compose.onNodeWithText(label).assertIsEnabled()
            if (kind != "diagnostics") compose.onNodeWithText("Fixture failure", substring = true).assertDoesNotExist()
            compose.runOnIdle { calls.clear() }
        }
    }

    @Test fun connectionActionsAdmitOneRequestAndShowThePendingActionUntilRecovery() {
        var wakeCalls = 0
        var refreshCalls = 0
        var gate = CompletableDeferred<Result<Unit>>()
        compose.setContent { StemwiseTheme {
            BridgeConnectionPanel(BridgeUiState(connection = app.stemwise.bridge.BridgeConnectionProfile(
                "bridge.tailnet.ts.net", "fixture-pi", "fixture-pc"), connectionIssue = "native_auth_required"),
                onSaveConnection = { Result.success(Unit) }, onEnroll = { error("No enrollment requested") },
                onRefresh = { refreshCalls++; gate.await() }, onWakePc = { wakeCalls++; gate.await() })
        } }
        val wake = compose.onNodeWithText("Wake PC").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val refresh = compose.onNodeWithText("Check connection").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { wake(); wake(); refresh() }
        compose.onNodeWithText("Waking PC…").assertIsNotEnabled()
        compose.onNodeWithText("Check connection").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, wakeCalls); assertEquals(0, refreshCalls)
            gate.complete(Result.failure(IllegalStateException("Fixture connection failure")))
        }
        compose.onNodeWithText("Fixture connection failure", substring = true).assertExists()
        compose.onNodeWithText("Sign in to Codex on your PC before preparing this song.").assertExists()
        compose.onNodeWithText("Wake PC").assertIsEnabled()
        gate = CompletableDeferred()
        val retry = compose.onNodeWithText("Check connection").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { retry(); retry(); wake() }
        compose.onNodeWithText("Checking…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, refreshCalls); assertEquals(1, wakeCalls); gate.complete(Result.success(Unit)) }
        compose.onNodeWithText("Check connection").assertIsEnabled()
        compose.onNodeWithText("Fixture connection failure", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sign in to Codex on your PC before preparing this song.").assertExists()
    }

    @Test fun slowCatalogShowsStablePlaceholdersOnlyAfterTheDelay() {
        val loaded = CompletableDeferred<BridgeModelCatalog>()
        compose.mainClock.autoAdvance = false
        compose.setContent { StemwiseTheme {
            BridgeSongControls(BridgeUiState(connection = app.stemwise.bridge.BridgeConnectionProfile(
                "bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")),
                onLoadModels = { Result.success(loaded.await()) },
                onSubmit = { error("Loading never authorizes sending") })
        } }
        val before = compose.onNodeWithTag("bridge-choice-Model").getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("Model loading").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(150)
        for (label in listOf("Model", "Intelligence", "Speed")) {
            compose.onNodeWithContentDescription("$label loading").assertExists()
        }
        compose.runOnIdle { loaded.complete(catalog) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Model: Fixture model").assertExists()
        compose.onNodeWithContentDescription("Model loading").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("bridge-choice-Model").getUnclippedBoundsInRoot())
    }

    @Test fun fastCatalogNeverShowsLoadingPlaceholders() {
        compose.mainClock.autoAdvance = false
        compose.setContent { StemwiseTheme {
            BridgeSongControls(BridgeUiState(), onLoadModels = { Result.success(catalog) },
                onSubmit = { error("No send requested") })
        } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Model: Fixture model").assertExists()
        compose.onNodeWithContentDescription("Model loading").assertDoesNotExist()
    }

    @Test fun installedSongShowsSuccessWhileDeliveryConfirmationRemainsPending() {
        compose.setContent { StemwiseTheme {
            BridgeTaskCard(BridgeSongTask("job", "abcdefghijk", "Installed song",
                BridgeSongPhase.ACKNOWLEDGING, "Confirming delivery", locallyImported = true),
                onRetry = { error("No retry requested") }, onForget = { error("Must retain pending delivery") },
                onCancel = { error("Installed song cannot cancel") }, diagnostics = { Result.success("diagnostics") })
        } }
        compose.onNodeWithText("Song added").assertExists()
        compose.onNodeWithText("Delivery confirmation pending").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Song added")).assertExists()
        compose.onNodeWithText("Cancel PC task").assertDoesNotExist()
    }

    @Test fun editingProcessingOptionsNeverSubmitsUntilExplicitSend() {
        val submissions = mutableListOf<BridgeSongSubmission>()
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeSongControls(BridgeUiState(), initialText = firstLink,
                    onLoadModels = { Result.success(catalog) },
                    onSubmit = { submissions += it; Result.success(Unit) })
            }
        } }
        compose.onNodeWithText("Model: Fixture model").assertExists()
        compose.onNodeWithText("Intelligence: Balanced").performScrollTo().performClick()
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithText("Speed: Standard").performScrollTo().performClick()
        compose.onNodeWithText("Fast", substring = false).performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performScrollTo().performTextReplacement(editedLink)
        compose.onNodeWithText("Intelligence: High").assertExists()
        compose.onNodeWithText("Speed: Fast").assertExists()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BridgeSongSubmission(editedLink, "fixture-model", "high", "fast")), submissions)
        }
    }

    @Test fun noConnectionShowsOneFailureWithoutDeadRetryOrWakeActions() {
        var reads = 0
        var wakes = 0
        compose.setContent { StemwiseTheme {
            BridgeSongDialog(BridgeUiState(error = "Connect Bridge in Settings first."),
                onLoadModels = { reads++; Result.failure(IllegalStateException("Connect Bridge in Settings first.")) },
                onWakeForOptions = { wakes++; Result.success(Unit) },
                onSubmit = { error("No connection cannot submit") }, onDismiss = {})
        } }
        compose.onAllNodesWithText("Connect Bridge in Settings first.").assertCountEquals(1)
        compose.onNodeWithText("Retry Bridge options").assertDoesNotExist()
        compose.onNodeWithText("Wake PC and load options").assertDoesNotExist()
        compose.onNodeWithTag("bridge-choice-Model").assertDoesNotExist()
        compose.onNodeWithText("Send to Bridge").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, wakes) }
    }

    @Test fun configuredOfflineConnectionRetainsExplicitWakeAndReloadOptions() {
        var reads = 0
        var wakes = 0
        compose.setContent { StemwiseTheme {
            BridgeSongDialog(BridgeUiState(connection = app.stemwise.bridge.BridgeConnectionProfile(
                "bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")), initialText = firstLink,
                onLoadModels = { reads++; if (reads == 1) Result.failure(IllegalStateException("Catalog offline")) else Result.success(catalog) },
                onWakeForOptions = { wakes++; Result.success(Unit) },
                onSubmit = { error("Loading options cannot submit") }, onDismiss = {})
        } }
        val sendBounds = compose.onNodeWithText("Send to Bridge").performScrollTo().fetchSemanticsNode().boundsInRoot
        val retryBounds = compose.onNodeWithText("Retry Bridge options").performScrollTo().fetchSemanticsNode().boundsInRoot
        val wakeBounds = compose.onNodeWithText("Wake PC and load options").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertEquals(sendBounds.width, retryBounds.width, .5f)
        assertEquals(sendBounds.width, wakeBounds.width, .5f)
        val minimumButtonHeightPx = 48f * compose.density.density
        assertTrue(retryBounds.height >= minimumButtonHeightPx && wakeBounds.height >= minimumButtonHeightPx)
        compose.onNodeWithText("Retry Bridge options").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Wake PC and load options").performScrollTo().performClick()
        compose.onNodeWithText("Model: Fixture model").assertExists()
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, reads); assertEquals(1, wakes) }
    }

    @Test fun multipleLinksAreRejectedAndCatalogFailureRequiresRetry() {
        var reads = 0
        var submissions = 0
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeSongControls(BridgeUiState(connection = app.stemwise.bridge.BridgeConnectionProfile(
                    "bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")), initialText = "$firstLink $editedLink",
                    onLoadModels = {
                        reads++
                        if (reads == 1) Result.failure(IllegalStateException("Catalog offline"))
                        else Result.success(catalog)
                    }, onSubmit = { submissions++; Result.success(Unit) })
            }
        } }
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enter one song link. Multiple links cannot be sent together.").assertExists()
        compose.onNodeWithText("Retry Bridge options").performScrollTo().performClick()
        compose.onNodeWithText("Model: Fixture model").assertExists()
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, reads); assertEquals(0, submissions) }
    }

    @Test fun nativeDefaultCanBeExplicitlySelectedWhenOnlyFastIsAdvertised() {
        val submissions = mutableListOf<BridgeSongSubmission>()
        val fastOnly = BridgeModelCatalog(catalog.models.map { it.copy(
            serviceTiers = listOf(BridgeServiceTier("priority", "Fast", "Faster processing")), defaultServiceTier = null) })
        compose.setContent { StemwiseTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BridgeSongControls(BridgeUiState(), initialText = firstLink,
                    onLoadModels = { Result.success(fastOnly) },
                    onSubmit = { submissions += it; Result.success(Unit) })
            }
        } }
        compose.onNodeWithText("Speed: Default speed").performScrollTo().performClick()
        compose.onNodeWithText("Fast", substring = false).performClick()
        compose.onNodeWithText("Speed: Fast").performClick()
        compose.onNodeWithText("Default speed", substring = false).performClick()
        compose.onNodeWithText("Speed: Default speed").assertExists()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BridgeSongSubmission(firstLink, "fixture-model", "medium", "")), submissions)
        }
    }

    @Test fun obsoleteSavedOptionsCanBeCorrectedOnlyByExplicitReviewedSend() {
        val submissions = mutableListOf<BridgeSongSubmission>()
        val obsolete = BridgeSongSubmission(firstLink, "retired-model", "retired-effort", "retired-speed")
        val profile = BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")
        val approved = approveBridgeSong(newBridgeSong(RootAuthority("library", "content://fixture/root", 1L),
            firstLink, "Fixture guide", 1L), obsolete, "Fixture guide", 1L, profile)
        val review = approved.copy(preparation = BridgeSendPreparation(),
            task = approved.task.copy(phase = BridgeSongPhase.REVIEW_REQUIRED)).resumableTask
        assertTrue(review.canReplaceReviewedRequest)
        compose.setContent { StemwiseTheme {
            BridgeSongDialog(BridgeUiState(connection = profile), initialText = review.sourceUrl,
                initialSubmission = review.approvedSubmission, canReplaceSubmission = review.canReplaceReviewedRequest,
                title = "Review song request", onLoadModels = { Result.success(catalog) },
                onDismiss = {}, onSubmit = { submissions += it; Result.success(Unit) })
        } }
        val dialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Model: retired-model").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Fixture model", substring = false).performClick()
        compose.onNodeWithText("Intelligence: Balanced").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("High", substring = false).performClick()
        compose.onNodeWithText("Speed: Standard").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Fast", substring = false).performClick()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        compose.onNodeWithText("Send to Bridge").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BridgeSongSubmission(firstLink, "fixture-model", "high", "fast")), submissions)
        }
    }

    @Test fun submittedAndUncertainSavedOptionsRemainReadOnly() {
        val phase = mutableStateOf(BridgeSongPhase.RUNNING)
        val profile = BridgeConnectionProfile("bridge.tailnet.ts.net", "fixture-pi", "fixture-pc")
        val selected = BridgeSongSubmission(firstLink, "fixture-model", "high", "fast")
        val approved = approveBridgeSong(newBridgeSong(RootAuthority("library", "content://fixture/root", 1L),
            firstLink, "Fixture guide", 1L), selected, "Fixture guide", 1L, profile)
        compose.setContent { StemwiseTheme {
            val saved = approved.copy(submission = if (phase.value == BridgeSongPhase.RUNNING)
                app.stemwise.bridge.BridgeSubmission.ACKNOWLEDGED else app.stemwise.bridge.BridgeSubmission.UNKNOWN,
                preparation = BridgeSendPreparation(catalogValidated = true),
                task = approved.task.copy(phase = phase.value)).resumableTask
            BridgeSongDialog(BridgeUiState(connection = profile), initialText = saved.sourceUrl,
                initialSubmission = saved.approvedSubmission, canReplaceSubmission = saved.canReplaceReviewedRequest,
                onLoadModels = { Result.success(catalog) }, onDismiss = {}, onSubmit = { error("No send requested") })
        } }
        for (state in listOf(BridgeSongPhase.RUNNING, BridgeSongPhase.WAITING)) {
            compose.runOnIdle { phase.value = state }
            compose.onNodeWithText("Model: Fixture model").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Intelligence: High").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Speed: Fast").performScrollTo().assertIsNotEnabled()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsNotEnabled()
        }
    }

    @Test fun draftReviewPrefillsSameEditorAndCloseLeavesAuthorizationUntouched() {
        val authorizations = mutableListOf<Pair<String, BridgeSongSubmission>>()
        val reviewing = mutableStateOf<BridgeSongTask?>(null)
        compose.setContent { StemwiseTheme {
            QueueScreen(onImportEnrichment = {},
                playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                    "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                syncStatus = PlaylistSyncStatus(), onSync = { false }, bridgeState = BridgeUiState(),
                bridgeTasks = listOf(BridgeSongTask("draft-one", "abcdefghijk", "Discovered song", BridgeSongPhase.DRAFT, "",
                    sourceUrl = firstLink)), onOpenAddSong = { error("Manual form must not open") },
                onReviewDraft = { reviewing.value = it },
                onRetryBridgeTask = { error("Draft must not retry") }, onForgetBridgeTask = { Result.success(Unit) },
                onRetryJob = {})
            reviewing.value?.let { draft -> BridgeSongDialog(BridgeUiState(), initialText = draft.sourceUrl,
                title = "Review song", onLoadModels = { Result.success(catalog) },
                onDismiss = { reviewing.value = null }, onSubmit = { submission ->
                    authorizations += draft.id to submission
                    Result.success(Unit)
                }) }
        } }
        compose.onNodeWithText("Waiting for authorization").assertExists()
        compose.onNodeWithText("Review and send").performScrollTo().performClick()
        val dialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .assertTextContains(firstLink).performTextReplacement(editedLink)
        compose.runOnIdle { assertTrue(authorizations.isEmpty()) }
        compose.onNodeWithText("Close").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
        compose.runOnIdle { assertTrue(authorizations.isEmpty()) }
        compose.onNodeWithText("Review and send").performScrollTo().performClick()
        compose.onNode(dialog and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .assertTextContains(firstLink).performTextReplacement(editedLink)
        compose.onNode(dialog and hasText("Send to Bridge")).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("draft-one" to BridgeSongSubmission(editedLink, "fixture-model", "medium", "standard")), authorizations)
        }
    }
}
