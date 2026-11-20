package app.stemwise.bridge

import androidx.room.Room
import app.stemwise.data.ExternalLibraryRepository
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.SourceBindingRegistry
import app.stemwise.data.authority
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.playback.engine.JniNativeEngineEnvironment
import app.stemwise.playback.engine.NativeEngineSelectionRequest
import app.stemwise.playback.engine.NativePlaybackEngineController
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import local.phonepc.bridge.mobile.Client
import local.phonepc.bridge.mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real consumed SDK and production transport; no enrollment, network request or Bridge job. */
@app.stemwise.qualification.ManagedAudioTest
internal class BridgeCachedReceiveInstrumentationTest : GoldenEnrichmentFixture() {
    private class LocalNetwork : AppNetworkAccess {
        override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
        var reads = 0
        override fun currentState(): AppNetworkState {
            reads++
            return state.value
        }
    }

    private fun <T> operation(client: Client, block: (String) -> T): T {
        val operation = client.newOperation()
        return try { block(operation.token()) } finally { operation.close() }
    }

    private fun seedCachedFiles(state: File, bytes: ByteArray, count: Int): BridgeFile {
        val seed = Mobile.newClient(state.absolutePath, "stemwise-cache-fixture", "")
        val prepared = try {
            val files = seed.localFiles("cached-bridge.tailnet.ts.net", "cached-bridge")
            List(count) { operation(seed) { token ->
                BridgeFile.parse(JSONObject(files.prepare(token, "song.zip", bytes)))
            } }
        } finally { seed.close() }
        // SDK-created store objects model a previously completed incoming transfer.
        File(state, "bridge-outgoing").copyRecursively(File(state, "bridge-incoming"), overwrite = true)
        return prepared.maxBy { it.id }
    }

    @Test fun cachedSecondPageSurvivesCopyFailureAndCancellationBetweenCalls() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val owned = File(targetContext.cacheDir, "bridge-cache-${UUID.randomUUID()}").apply { check(mkdir()) }
        var client: Client? = null
        try {
            val bytes = "immutable cached ZIP fixture".toByteArray(Charsets.UTF_8)
            val descriptor = seedCachedFiles(File(owned, "node"), bytes, 101)
            val actual = Mobile.newClient(File(owned, "node").absolutePath, "stemwise-cache-fixture", "").also { client = it }
            val network = LocalNetwork()
            val transport = AndroidSongBridgeTransport(targetContext,
                BridgeConnectionProfile("cached-bridge.tailnet.ts.net", "cached-bridge", "cached-pc"), network) { actual }
            val destination = File(owned, "received.zip").apply { check(mkdir()) }
            val copyFailure = runCatching { transport.receive(descriptor, destination) }.exceptionOrNull()
            assertNotNull("A directory must reject the copy", copyFailure)
            assertFalse("Receipt lookup reused a one-shot SDK operation: $copyFailure",
                copyFailure.toString().contains("bridge_operation_closed"))
            check(destination.delete())
            val copying = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            transport.beforeReceivedCopyForTest = {
                copying.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Local copy barrier timed out" }
            }
            val receiving = async { transport.receive(descriptor, destination) }
            try {
                withTimeout(5_000L) { copying.await() }
                receiving.cancel()
                release.countDown()
                withTimeout(5_000L) { receiving.join() }
                assertTrue(receiving.isCancelled)
                assertFalse(destination.exists())
            } finally {
                release.countDown()
                transport.beforeReceivedCopyForTest = null
                withTimeout(5_000L) { receiving.cancelAndJoin() }
            }
            transport.receive(descriptor, destination)
            assertArrayEquals(bytes, destination.readBytes())
            assertTrue(descriptor.matches(destination))
            assertEquals(0, network.reads)
        } finally { client?.close(); check(owned.deleteRecursively()) }
    }

    @Test fun retiredTransfersAreRemovedLocallyWithoutNetworkAdmission() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val owned = File(targetContext.cacheDir, "bridge-offline-cleanup-${UUID.randomUUID()}").apply { check(mkdir()) }
        var client: Client? = null
        try {
            val state = File(owned, "node")
            val descriptor = seedCachedFiles(state, "retained transfer bytes".toByteArray(Charsets.UTF_8), 1)
            val actual = Mobile.newClient(state.absolutePath, "stemwise-cache-fixture", "").also { client = it }
            val profile = BridgeConnectionProfile("cached-bridge.tailnet.ts.net", "cached-bridge", "cached-pc")
            val offline = LocalNetwork().also { it.state.value = AppNetworkState(false, false, true) }
            val record = approveBridgeSong(newBridgeSong(app.stemwise.data.RootAuthority(UUID.randomUUID().toString(),
                "content://fixture/tree/root", 1L), "https://youtu.be/abcdefghijk", "Guide", 1L),
                BridgeSongSubmission("https://youtu.be/abcdefghijk", "fixture-model", "high", ""), "Guide", 2L, profile)
            val retired = record.copy(remoteRetired = true, output = descriptor,
                request = JSONObject(record.request.toString()).put("files", org.json.JSONArray().put(descriptor.json())))
            AndroidSongBridgeTransport(targetContext, profile, offline) { actual }.discardTransfers(retired)
            val local = actual.localFiles(profile.host, profile.bridgeId)
            val received = operation(actual) { local.received(it, "", 100) }
            assertEquals(0, JSONObject(received).getJSONArray("files").length())
            val pending = operation(actual) { local.pending(it, "", 100) }
            assertEquals(0, JSONObject(pending).getJSONArray("files").length())
            assertEquals(0, offline.reads)
        } finally { client?.close(); check(owned.deleteRecursively()) }
    }

    @Test fun cachedSdkReceiveImportsAndPresentsTheExactSongThroughProductionPlayback() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        var client: Client? = null
        try {
            val mutations = PackageMutationCoordinator(context)
            val roots = LibraryRootManager(context, mutations, database.libraryDao())
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            val root = checkNotNull(roots.captureHandle())
            val background = BackgroundWorkCoordinator()
            val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
            val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
            val importer = songImporter(context, roots, mutations, bindings, background, database)
            val archive = writeArchive(context, "bridge-source.zip",
                payload("2026-10-26T00:00:00Z", "Cached Bridge song", true), COVER_PNG)
            val state = File(context.filesDir, "bridge-node")
            val descriptor = seedCachedFiles(state, archive.readBytes(), 1)
            val actual = Mobile.newClient(state.absolutePath, "stemwise-cache-fixture", "").also { client = it }
            val received = File(context.cacheDir, "bridge-received.zip")
            AndroidSongBridgeTransport(context,
                BridgeConnectionProfile("cached-bridge.tailnet.ts.net", "cached-bridge", "cached-pc"), LocalNetwork()) { actual }.receive(descriptor, received)
            val imported = importer.importPcSong(received, VIDEO_ID, root.authority()).getOrThrow().items.single()
            assertTrue(imported.errorCode, imported.isSuccess)
            val song = external.verifyPlaybackSong(root, VIDEO_ID)
            NativePlaybackEngineController(JniNativeEngineEnvironment(context, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)).use { controller ->
                controller.setDiagnosticPayloadFingerprints(true)
                val token = controller.select(NativeEngineSelectionRequest(song.id, song.audioRevision,
                    checkNotNull(song.sourcePath), song.durationMs, autoplay = true))
                val presented = withTimeoutOrNull(15_000L) {
                    while (true) {
                        val output = controller.diagnosticsSnapshot().output
                        if (output?.lastPresentedSelectionToken == token.value && output.pcmLevelSelectionToken == token.value &&
                            output.pcmLevelFrames > 0L && output.pcmLevelPeak > 0f) return@withTimeoutOrNull true
                        delay(20L)
                    }
                }
                assertEquals("Imported song did not reach audible PCM: ${controller.diagnosticsSnapshot()}", true, presented)
            }
            assertTrue(descriptor.matches(received))
        } finally { client?.close(); database.close(); context.destroy(); isolatedContext = null }
    }
}
