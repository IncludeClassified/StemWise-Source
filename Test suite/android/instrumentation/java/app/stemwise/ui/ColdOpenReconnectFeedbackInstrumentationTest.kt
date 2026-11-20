package app.stemwise.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import app.stemwise.StemwiseServices
import app.stemwise.data.ColdOpenState
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootException
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class ColdOpenReconnectFeedbackInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun readyRootColdReconnectShowsLatestCancelledAndInvalidPickerThenRecovers() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        lateinit var services: StemwiseServices
        val mounted = mutableStateOf(true)
        val registry = HoldingTreePickerRegistry()
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.runOnUiThread { services = StemwiseServices(context, network, database) }
        try {
            runBlocking { withTimeout(15_000L) {
                val roots = services.libraryRootManager
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                services.preferences.activateLibraryFromRoot(checkNotNull(roots.captureHandle()).libraryId)
            } }
            services.coldOpenCoordinator.blockStartup(LibraryRootException("ROOT_UNAVAILABLE", "Interrupted import could not open the root"))
            assertTrue(services.libraryRootManager.state.value.ready)
            val original = (services.coldOpenCoordinator.state.value as ColdOpenState.Blocked).message
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) {
                    StemwiseTheme { if (mounted.value) StemwiseApp(services) }
                }
            }
            fun awaitText(text: String) = compose.waitUntil(5_000L) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1
            }
            fun reconnect(result: Uri?) {
                registry.requestCode = null
                compose.onNodeWithText("Reconnect").performClick()
                compose.waitUntil(5_000L) { registry.requestCode != null }
                compose.onNodeWithText(original).assertExists()
                compose.runOnIdle { registry.dispatchResult(checkNotNull(registry.requestCode), result) }
            }
            awaitText(original)
            reconnect(null)
            val cancelled = "Folder access was not granted. Reconnect the same folder to continue."
            awaitText(cancelled)
            compose.onNodeWithText(original).assertDoesNotExist()
            assertTrue(services.libraryRootManager.state.value.ready)
            reconnect(DocumentsContract.buildTreeDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root/missing-candidate"))
            val invalid = "The selected library folder must be readable and writable"
            awaitText(invalid)
            compose.onNodeWithText(cancelled).assertDoesNotExist()
            assertTrue(services.libraryRootManager.state.value.ready)
            reconnect(GoldenQaDocumentsProvider.treeUri)
            awaitText("Use this non-empty folder?")
            compose.onNodeWithText("USE FOLDER").performClick()
            try { compose.waitUntil(30_000L) { services.coldOpenCoordinator.state.value is ColdOpenState.Ready } }
            catch (timeout: ComposeTimeoutException) {
                val semantics = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                    .getOrElse { "Semantics inspection failed: $it" }
                throw AssertionError("Reconnect did not finish. root=${services.libraryRootManager.state.value}; " +
                    "cold=${services.coldOpenCoordinator.state.value}; switching=${services.librarySessionCoordinator.switching.value}\n" +
                    semantics, timeout)
            }
            compose.onNodeWithText(cancelled).assertDoesNotExist()
            compose.onNodeWithText(invalid).assertDoesNotExist()
            compose.onNodeWithText("Your library is empty.").assertExists()
        } finally {
            compose.runOnIdle { mounted.value = false }
            runBlocking { withTimeout(10_000L) {
                services.coldOpenCoordinator.cancelAndJoin()
                services.enrichmentImports.cancelForLibraryChange()
                services.externalLibraryRepository.retireScansForLibrarySwitch()
                services.songBridge.cancelForLibraryChange()
                services.audioPlayback.clearLibrarySession()
            } }
            compose.runOnUiThread { services.audioPlayback.release() }
            database.close()
        }
    }

    private class HoldingTreePickerRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.OpenDocumentTree)
            this.requestCode = requestCode
        }
    }
}
