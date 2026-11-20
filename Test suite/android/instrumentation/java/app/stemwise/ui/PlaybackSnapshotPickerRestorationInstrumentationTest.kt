package app.stemwise.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.stemwise.bridge.BridgeUiState
import app.stemwise.data.LibraryRootState
import app.stemwise.data.LibraryStorageRefreshState
import app.stemwise.data.PlaylistSettings
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.PlaylistLinks
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class PlaybackSnapshotPickerRestorationInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun constructionDoesNotOpenStorageOnTheUiThread() = runBlocking {
        val context = prepareContext()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val guardedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): java.io.File {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                    "Snapshot export storage opened on the UI thread"
                }
                reads.incrementAndGet()
                return context.noBackupFilesDir
            }
        }
        lateinit var store: PlaybackSnapshotExportStore
        compose.runOnUiThread { store = PlaybackSnapshotExportStore(guardedContext) }
        assertEquals(0, reads.get())
        val id = store.prepare("background-storage-capture")
        assertEquals(1, reads.get())
        assertTrue(store.cancel(id))
        assertEquals(PlaybackSnapshotExportPhase.CANCELLED, store.current()?.phase)
    }

    @Test fun capturedBytesSurviveRecreationBeforeThePickerResultIsDelivered() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider()
        grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val registry = HoldingPickerRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        var captures = 0
        val snapshot = "{\"schema\":1,\"capturedOwner\":\"original-owner\",\"body\":\"" + "x".repeat(150_000) + "\"}"
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides context) {
                StemwiseTheme {
                    val export = rememberPlaybackSnapshotExport(capture = { captures++; snapshot })
                    FixtureSettings(export)
                }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Export playback snapshot"))
        compose.onNodeWithText("Export playback snapshot").performClick()
        compose.waitUntil(5_000) { registry.requestCode != null }
        restoration.emulateSavedInstanceStateRestore()
        val parent = DocumentsContract.buildDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root")
        val destination = checkNotNull(DocumentsContract.createDocument(targetContext.contentResolver,
            parent, "application/json", "recreated-playback-snapshot.json"))
        compose.runOnIdle { registry.dispatchResult(checkNotNull(registry.requestCode), destination) }
        compose.waitUntil(5_000) {
            targetContext.contentResolver.openInputStream(destination)!!.use { it.readBytes().size } == snapshot.toByteArray().size
        }
        val actual = targetContext.contentResolver.openInputStream(destination)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(snapshot, actual)
        assertEquals("Recreation must use the captured bytes, never recapture a newer owner", 1, captures)
    }

    @Test fun navigatingAwayRetainsTheRootPickerCallbackAndItsCapturedBytes() {
        val context = prepareContext()
        val registry = HoldingPickerRegistry()
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val showingSystem = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalContext provides context) {
                StemwiseTheme {
                    val export = rememberPlaybackSnapshotExport(capture = { "captured-before-navigation" })
                    if (showingSystem.value) FixtureSettings(export) else Text("Another tab")
                }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Export playback snapshot"))
        compose.onNodeWithText("Export playback snapshot").performClick()
        compose.waitUntil(5_000) { registry.requestCode != null }
        compose.runOnIdle { showingSystem.value = false }
        val destination = destination("after-navigation.json")
        compose.runOnIdle { registry.dispatchResult(checkNotNull(registry.requestCode), destination) }
        compose.waitUntil(5_000) { readDestination(destination) == "captured-before-navigation" }
        compose.onNodeWithText("Another tab").assertExists()
    }

    @Test fun freshOwnersResumeRecordedWritesAndRejectDuplicateAndOlderPickerResults() = runBlocking {
        val context = prepareContext()
        val first = PlaybackSnapshotExportStore(context)
        val id = first.prepare("immutable-first-capture")
        assertTrue(first.markPickerLaunched(id))
        val output = destination("after-process-reconstruction.json")
        // Reconstruct separately both before picker delivery and after destination admission.
        assertTrue(PlaybackSnapshotExportStore(context).acceptResult(id, output))
        assertEquals(PlaybackSnapshotExportPhase.SAVED, PlaybackSnapshotExportStore(context).finish(id)?.phase)
        assertEquals("immutable-first-capture", readDestination(output))
        val unrelated = destination("must-remain-empty.json")
        val restored = PlaybackSnapshotExportStore(context)
        assertFalse(restored.acceptResult(id, unrelated))
        val next = restored.prepare("second-capture")
        assertTrue(restored.markPickerLaunched(next))
        assertFalse(restored.acceptResult(id, unrelated))
        assertTrue(restored.acceptResult(next, null))
        assertEquals(PlaybackSnapshotExportPhase.CANCELLED, restored.current()?.phase)
        assertEquals("", readDestination(unrelated))
        assertTrue(context.noBackupFilesDir.resolve("playback-snapshot-export").listFiles().orEmpty()
            .none { it.name.startsWith("capture-") })
    }

    @Test fun cancelledAdmittedPickerCannotOpenAndItsStaleResultCannotWrite() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000L) {
            val context = prepareContext()
            val store = PlaybackSnapshotExportStore(context)
            val id = store.prepare("cancelled-capture")
            val picker = PlaybackSnapshotPickerLaunch()
            val admitted = kotlinx.coroutines.CompletableDeferred<Unit>()
            val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
            var launches = 0
            val effect = launch {
                assertTrue(store.markPickerLaunched(id))
                admitted.complete(Unit)
                resume.await()
                store.current()
                picker.launch { launches++ }
            }
            try {
                admitted.await()
                picker.cancel()
                assertTrue(store.cancel(id))
                assertEquals(PlaybackSnapshotExportPhase.CANCELLED, store.current()?.phase)
                resume.complete(Unit)
                effect.join()
                assertEquals(0, launches)
                val output = destination("cancelled-picker-must-not-write.json")
                assertFalse(store.acceptResult(id, output))
                assertEquals("", readDestination(output))
                val next = store.prepare("next-capture")
                assertTrue(store.markPickerLaunched(next))
                assertTrue(PlaybackSnapshotPickerLaunch().launch { launches++ })
                assertTrue(store.acceptResult(next, output))
                assertEquals(PlaybackSnapshotExportPhase.SAVED, store.finish(next)?.phase)
                assertEquals("next-capture", readDestination(output))
                assertEquals(1, launches)
            } finally {
                resume.complete(Unit)
                effect.cancel()
                effect.join()
            }
        }
    }

    @Test fun retainedDocumentGrantSurvivesLibraryReconnectAndANewExportOwner() = runBlocking {
        kotlinx.coroutines.withTimeout(10_000L) {
            val context = prepareContext()
            val database = androidx.room.Room.inMemoryDatabaseBuilder(context,
                app.stemwise.data.LibraryDatabase::class.java).allowMainThreadQueries().build()
            val output = destination("retained-across-library-reconnect.json")
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            targetContext.grantUriPermission(targetContext.packageName, output,
                flags or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try {
                val mutations = app.stemwise.data.PackageMutationCoordinator(context)
                val roots = app.stemwise.data.LibraryRootManager(context, mutations, database.libraryDao())
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(
                    GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
                val captured = "immutable capture before Library reconnect"
                val store = PlaybackSnapshotExportStore(context)
                val id = store.prepare(captured)
                assertTrue(store.markPickerLaunched(id))
                assertTrue(store.acceptResult(id, output))
                assertTrue("The production picker admission must acquire a real persisted grant",
                    checkNotNull(store.current()).ownsPersistentGrant)
                fun grantRetained() = targetContext.contentResolver.persistedUriPermissions.any {
                    it.uri == output && it.isReadPermission && it.isWritePermission
                }
                assertTrue(grantRetained())
                // Reconstruct the normal root owner and reconnect, then reconstruct the exporter.
                val restartedRoots = app.stemwise.data.LibraryRootManager(context, mutations, database.libraryDao())
                assertTrue(restartedRoots.reconnect().ready)
                assertTrue("Library grant reconciliation revoked the independent export destination", grantRetained())
                val restartedExport = PlaybackSnapshotExportStore(context)
                assertEquals(PlaybackSnapshotExportPhase.WRITING, restartedExport.current()?.phase)
                assertEquals(PlaybackSnapshotExportPhase.SAVED, restartedExport.finish(id)?.phase)
                assertEquals(captured, readDestination(output))
                assertFalse("Only the terminal exporter releases its own grant", grantRetained())
            } finally {
                database.close()
                if (targetContext.contentResolver.persistedUriPermissions.any { it.uri == output }) {
                    targetContext.contentResolver.releasePersistableUriPermission(output, flags)
                }
                targetContext.revokeUriPermission(output, flags)
            }
        }
    }

    @Test fun completingAnExportReleasesOnlyItsNewWriteGrantAndPreservesExistingRead() = runBlocking {
        val context = prepareContext()
        val output = destination("preexisting-read-grant.json")
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = context.contentResolver
        targetContext.grantUriPermission(targetContext.packageName, output,
            read or write or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        resolver.takePersistableUriPermission(output, read)
        try {
            assertTrue(resolver.persistedUriPermissions.any { it.uri == output && it.isReadPermission && !it.isWritePermission })
            val store = PlaybackSnapshotExportStore(context)
            val id = store.prepare("captured with an existing read owner")
            assertTrue(store.markPickerLaunched(id))
            assertTrue(store.acceptResult(id, output))
            assertEquals(write, checkNotNull(store.current()).acquiredGrantFlags)
            assertEquals(PlaybackSnapshotExportPhase.SAVED, PlaybackSnapshotExportStore(context).finish(id)?.phase)
            assertEquals("captured with an existing read owner", readDestination(output))
            assertTrue("Exporter released another owner's read grant", resolver.persistedUriPermissions.any {
                it.uri == output && it.isReadPermission && !it.isWritePermission
            })
        } finally {
            resolver.releasePersistableUriPermission(output, read or write)
            targetContext.revokeUriPermission(output, read or write)
        }
    }

    @Test fun providerFailureSettlesExplicitlyAndAllowsANewCapture() = runBlocking {
        val context = prepareContext()
        val store = PlaybackSnapshotExportStore(context)
        val id = store.prepare("capture-for-an-unavailable-destination")
        assertTrue(store.markPickerLaunched(id))
        assertTrue(store.acceptResult(id, Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}/document/missing")))
        assertEquals(PlaybackSnapshotExportPhase.FAILED, store.finish(id)?.phase)
        assertNotNull(store.current()?.message)
        val retry = store.prepare("new-capture-after-explicit-failure")
        assertNotEquals(id, retry)
        assertTrue(store.cancel(retry))
    }

    private fun prepareContext(): IsolatedQaContext {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        return IsolatedQaContext(targetContext).also { isolatedContext = it }
    }
    private fun destination(name: String): Uri = checkNotNull(DocumentsContract.createDocument(targetContext.contentResolver,
        DocumentsContract.buildDocumentUri(GoldenQaDocumentsProvider.AUTHORITY, "root"), "application/json", name))
    private fun readDestination(uri: Uri): String = targetContext.contentResolver.openInputStream(uri)!!.use {
        it.readBytes().toString(Charsets.UTF_8)
    }

    @Composable private fun FixtureSettings(export: PlaybackSnapshotExportUi) {
        SettingsScreen(
            playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
            mvsepState = MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED),
            bridgeState = BridgeUiState(), onSaveBridgeConnection = { Result.success(Unit) }, onEnrollBridge = { Result.success(null) },
            onRefreshBridge = { Result.success(Unit) }, onWakeBridgePc = { Result.success(Unit) },
            libraryRootState = LibraryRootState(), storageRefreshState = LibraryStorageRefreshState(),
            onChooseLibrary = {}, onRefreshStorage = {}, onUpdatePlaylistTools = { Result.success(Unit) },
            onSaveMvsepToken = { Result.success(Unit) }, onRetryMvsep = { Result.success(Unit) },
            onRemoveMvsepToken = { Result.success(Unit) }, playbackSnapshotExport = export)
    }

    private class HoldingPickerRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { this.requestCode = requestCode }
    }
}
