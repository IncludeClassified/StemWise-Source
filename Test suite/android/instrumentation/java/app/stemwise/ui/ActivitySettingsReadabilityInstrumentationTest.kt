package app.stemwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.LibraryInventory
import app.stemwise.data.LibraryInventoryAvailability
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSettings
import app.stemwise.data.PlaylistSyncStatus
import app.stemwise.enrichment.ImportProgressKind
import app.stemwise.enrichment.SharedEnrichmentImportState
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.PlaylistLinks
import app.stemwise.model.JobKind
import app.stemwise.model.JobState
import app.stemwise.model.QueueJob
import app.stemwise.model.QueueJobAction
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.PrimaryPurple
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ActivitySettingsReadabilityInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test
    fun everyTaskStateOpensUntruncatedDetailsWithoutConsumingItsRetryAction() {
        val state = mutableStateOf(JobState.WAITING)
        var retries = 0
        compose.setContent {
            NarrowLargeText {
                Activity(jobs = listOf(QueueJob("details-job", LONG_TITLE, "Artist", JobKind.SEPARATION,
                    state.value, 0f, LONG_DETAIL, PrimaryPurple,
                    action = QueueJobAction.RETRY)), onRetry = { retries++ })
            }
        }
        val dialog = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)
        val detailsAction = SemanticsMatcher("View task details action") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "View task details"
        }
        for (next in listOf(JobState.WAITING, JobState.RUNNING, JobState.COMPLETE, JobState.NEEDS_ATTENTION)) {
            compose.runOnIdle { state.value = next }
            compose.onNode(hasText(LONG_TITLE) and detailsAction).performScrollTo().performClick()
            for (text in listOf(LONG_TITLE, LONG_DETAIL)) {
                val node = compose.onNode(hasText(text) and hasAnyAncestor(dialog), useUnmergedTree = true)
                val layout = textLayout(node)
                assertEquals(text, layout.layoutInput.text.text)
                assertFalse("Task details clipped their full text for $next", layout.hasVisualOverflow)
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
            }
            compose.onNode(hasText("Close") and hasAnyAncestor(dialog)).performClick()
            compose.onAllNodes(dialog).assertCountEquals(0)
        }
        compose.runOnIdle { assertEquals(0, retries) }
        compose.onNodeWithText("TRY AGAIN").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onAllNodes(dialog).assertCountEquals(0)
    }

    @Test
    fun disconnectedAndCheckingHeadersKeepWholeServiceNamesAtDoubleText() {
        val account = mutableStateOf(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED))
        compose.setContent { NarrowLargeText { Settings(account.value) } }
        for ((service, allowedLines) in listOf("YouTube Music" to 2, "MVSEP" to 1, "Bridge" to 1)) {
            val node = compose.onNodeWithText(service, useUnmergedTree = true).performScrollTo()
            val layout = textLayout(node)
            assertTrue("$service fragmented into letters", layout.lineCount <= allowedLines)
            assertFalse(layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) {
                val visible = service.substring(layout.getLineStart(line), layout.getLineEnd(line, true)).trim()
                assertTrue("A service word was split: $visible", visible in setOf(service, "YouTube", "Music"))
            }
        }
        compose.runOnIdle { account.value = MvsepConnectionState(MvsepConnectionStatus.CHECKING,
            tokenStored = true, verifying = true) }
        val mvsep = compose.onNodeWithText("MVSEP", useUnmergedTree = true).performScrollTo()
        assertEquals(1, textLayout(mvsep).lineCount)
        compose.onNode(hasText("MVSEP") and SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Checking")).assertExists()
    }

    @Test
    fun largeStorageTotalRendersBelowTheFixedRingAndRemainsFullyReadable() {
        val gib = 1_073_741_824L
        val inventory = LibraryInventory(actualLibraryBytes = 500 * gib, appPrivateTotalBytes = gib,
            lastCheckedEpochMs = 1_700_000_000_000L)
        var ringHeightPx = 0f
        compose.setContent {
            NarrowLargeText {
                ringHeightPx = with(LocalDensity.current) { 126.dp.toPx() }
                Settings(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED), inventory)
            }
        }
        val chart = compose.onNodeWithContentDescription("501 GB used").performScrollTo()
        val chartBounds = chart.fetchSemanticsNode().boundsInRoot
        assertTrue("The chart reserved no space below its ring", chartBounds.height > ringHeightPx + 1f)
        val pixels = chart.captureToImage().toPixelMap()
        var whiteInsideRing = 0
        var whiteBelowRing = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > .85f && pixel.green > .85f && pixel.blue > .85f) {
                if (y < ringHeightPx.toInt()) whiteInsideRing++ else whiteBelowRing++
            }
        }
        assertEquals("The total still paints over the ring", 0, whiteInsideRing)
        assertTrue("The readable total was not painted below the ring", whiteBelowRing > 0)
    }

    @Test
    fun importCopyProgressUsesTheTotalForZeroOneAndManyFiles() {
        val total = mutableStateOf(0)
        compose.setContent {
            NarrowLargeText {
                Activity(imports = SharedEnrichmentImportState.Running(
                    total = total.value, completed = if (total.value > 1) 1 else 0,
                    copiedBytes = if (total.value > 1) 1_024L else 0L,
                    progressKind = ImportProgressKind.FILES, operationId = "copy-count"))
            }
        }
        for ((count, expected) in listOf(0 to "Copied 0 of 0 files ·", 1 to "Copied 0 of 1 file ·",
            2 to "Copied 1 of 2 files ·")) {
            compose.runOnIdle { total.value = count }
            compose.onNodeWithText(expected, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single()
    }

    @Composable
    private fun NarrowLargeText(content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
            StemwiseTheme {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                    LocalStemwiseMotion provides StemwiseMotionSpec(false, false)) {
                    Box(Modifier.width(320.dp).fillMaxHeight().background(Color.Black)) { content() }
                }
            }
        }
    }

    @Composable
    private fun Activity(jobs: List<QueueJob> = emptyList(), onRetry: () -> Unit = {},
        imports: SharedEnrichmentImportState = SharedEnrichmentImportState.Idle) {
        QueueScreen(onImportEnrichment = {}, playlistSettings = playlist(), syncStatus = PlaylistSyncStatus(),
            onSync = { false }, bridgeState = BridgeUiState(), bridgeTasks = emptyList(),
            onRetryBridgeTask = { error("No Bridge operation expected") },
            onForgetBridgeTask = { error("No Bridge operation expected") }, jobs = jobs,
            onRetryJob = { onRetry() }, sharedImportState = imports, onOpenAddSong = {}, onReviewDraft = {})
    }

    @Composable
    private fun Settings(account: MvsepConnectionState, inventory: LibraryInventory = LibraryInventory()) {
        SettingsScreen(playlistSettings = playlist(), mvsepState = account, bridgeState = BridgeUiState(),
            onSaveBridgeConnection = { error("No Bridge operation expected") },
            onEnrollBridge = { error("No Bridge operation expected") },
            onRefreshBridge = { error("No Bridge operation expected") },
            onWakeBridgePc = { error("No Bridge operation expected") },
            libraryRootState = LibraryRootState(), storageRefreshState = LibraryStorageRefreshState(
                inventory = inventory, availability = LibraryInventoryAvailability.VERIFIED),
            onChooseLibrary = {}, onRefreshStorage = {}, onUpdatePlaylistTools = { error("No service operation expected") },
            onSaveMvsepToken = { error("No service operation expected") },
            onRetryMvsep = { error("No service operation expected") },
            onRemoveMvsepToken = { error("No service operation expected") })
    }

    private fun playlist() = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
        "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, false)

    companion object {
        private const val LONG_TITLE = "Aleksandra Wiśniewska-Kowalczyk & the Northwind Chamber Orchestra — Complete rehearsal, alternate take and director’s commentary (Warsaw, 2026)"
        private const val LONG_DETAIL = "Could not read content://documents.example.test/tree/primary%3AMusic%2FRehearsal-recordings%2FOriginal-and-stems-final-take-12.wav. The previously verified song is retained for recovery."
    }
}
