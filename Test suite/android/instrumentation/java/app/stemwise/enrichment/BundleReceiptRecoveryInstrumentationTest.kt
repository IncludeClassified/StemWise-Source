package app.stemwise.enrichment

import android.net.Uri
import androidx.room.Room
import app.stemwise.data.*
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

internal class BundleReceiptRecoveryInstrumentationTest : GoldenEnrichmentFixture() {
    @Test fun unavailableExistingOriginalRemainsRetryableWithoutReplacementAdvice() = runBlocking {
        withLibrary { f ->
            assertTrue(f.importer().importPcSong(f.archive("a.zip", "2026-08-09T12:00:00Z", "A"), VIDEO_ID,
                f.root.authority()).getOrThrow().items.single().isSuccess)
            val before = f.packageBytes()
            val b = f.archive("b.zip", "2026-08-10T12:00:00Z", "B")
            setFault(GoldenQaDocumentsProvider.FAULT_ORIGINAL_READ_ONCE)
            val failed = f.importer().importPcSong(b, VIDEO_ID, f.root.authority()).getOrThrow().items.single()
            assertEquals("EXTERNAL_COMMIT_FAILED", failed.errorCode)
            assertEquals(app.stemwise.bridge.BridgeFailureDisposition.RETRY,
                app.stemwise.bridge.bridgeFailureDisposition(EnrichmentImportException(checkNotNull(failed.errorCode))))
            assertEquals(before, f.packageBytes())
            assertTrue(f.importer().importPcSong(b, VIDEO_ID, f.root.authority()).getOrThrow().items.single().isSuccess)
            assertEquals("B", f.external.verifyPlaybackSong(f.root, VIDEO_ID).title)
            assertArrayEquals(f.original.readBytes(), File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}").readBytes())
            val retained = f.packageBytes()
            val original = f.original.readBytes()
            for ((index, bytes) in listOf(original.copyOf(original.size - 1), original.copyOf(original.size + 1),
                original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }).withIndex()) {
                val different = File(f.context.cacheDir, "different-$index.opus").apply { writeBytes(bytes) }
                val failure = f.publisher.publishOriginal(f.root, VIDEO_ID, different, sha256(different)).exceptionOrNull()
                assertEquals("ORIGINAL_CONFLICT", failure?.message)
                assertTrue(different.isFile)
                assertEquals(retained, f.packageBytes())
            }
        }
    }
    @Test fun supersededVerifiedReceiptRetiresWithoutItsArchiveAndPreservesNewRevision() = runBlocking {
        withLibrary { f ->
            var failComplete = true
            f.mutations.journalWriteForTest = { operation, state ->
                if (operation == "SONG_BUNDLE_IMPORT_V1" && state == "COMPLETE" && failComplete) {
                    failComplete = false
                    throw java.io.IOException("Final receipt write unavailable")
                }
            }
            val a = f.archive("a.zip", "2026-08-09T12:00:00Z", "A")
            assertFalse(f.importer().importPcSong(a, VIDEO_ID, f.root.authority()).getOrThrow().items.single().isSuccess)
            assertEquals("BUNDLE_VERIFIED", f.bundleRecords().single().value.getString("state"))
            f.mutations.journalWriteForTest = null
            val b = f.archive("b.zip", "2026-08-10T12:00:00Z", "B")
            assertTrue(f.importer().importPcSong(b, VIDEO_ID, f.root.authority()).getOrThrow().items.single().isSuccess)
            val before = f.packageBytes()
            val restarted = f.importer()
            withTimeout(15_000L) { restarted.recoverIncompleteBundles(f.root); restarted.recoverIncompleteBundles(f.root) }
            assertEquals(before, f.packageBytes())
            assertTrue(f.bundleRecords().isEmpty())
            assertEquals("B", f.external.verifyPlaybackSong(f.root, VIDEO_ID).title)
        }
    }

    @Test fun cancellingFirstIntakeAfterOriginalDoesNotAuthorizeColdOpenReplay() = runBlocking {
        withLibrary { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val importer = f.importer { phase ->
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED") {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Original publication barrier timed out" }
                }
            }
            val coordinator = EnrichmentImportCoordinator(f.context, importer, f.external, f.background, f.roots, scope = f.scope)
            val archive = f.archive("cancel.zip", "2026-08-09T12:00:00Z", "Cancelled")
            try {
                coordinator.awaitStartupScratchReady()
                coordinator.offer(listOf(Uri.fromFile(archive))); coordinator.confirm()
                withTimeout(10_000L) { entered.await() }
                coordinator.requestCancel(); release.countDown()
                withTimeout(10_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Cancelled } }
                val original = File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}")
                assertArrayEquals(f.original.readBytes(), original.readBytes())
                withTimeout(15_000L) {
                    f.external.recoverForColdOpen(f.root)
                    f.importer().recoverIncompleteBundles(f.root)
                    f.external.reconcile()
                }
                assertTrue(f.external.songs.value.isEmpty())
                assertFalse(File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ENRICHMENT}").exists())
                assertArrayEquals(f.original.readBytes(), original.readBytes())
            } finally { release.countDown(); coordinator.cancelForLibraryChange() }
        }
    }

    @Test fun cancelledRetryDuringPreflightRetiresReceiptFromEarlierStorageFailure() = runBlocking {
        withLibrary { f ->
            assertTrue(f.importer().importPcSong(f.archive("a.zip", "2026-08-09T12:00:00Z", "A"), VIDEO_ID,
                f.root.authority()).getOrThrow().items.single().isSuccess)
            val before = f.packageBytes()
            var failCapacity = true
            val importer = f.importer { phase ->
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED" && failCapacity) {
                    failCapacity = false
                    error("STORAGE_RESERVE_LOW")
                }
            }
            val coordinator = EnrichmentImportCoordinator(f.context, importer, f.external, f.background, f.roots, scope = f.scope)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            try {
                coordinator.awaitStartupScratchReady()
                coordinator.offer(listOf(Uri.fromFile(f.archive("b.zip", "2026-08-10T12:00:00Z", "B"))))
                coordinator.confirm()
                withTimeout(15_000L) { coordinator.state.first { it is SharedEnrichmentImportState.AwaitingConfirmation } }
                assertEquals("BUNDLE_STAGED", f.bundleRecords().single().value.getString("state"))
                importer.privateStorageAvailableForTest = {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Retry preflight barrier timed out" }
                    Long.MAX_VALUE
                }
                coordinator.confirm()
                withTimeout(10_000L) { entered.await() }
                coordinator.requestCancel(); release.countDown()
                withTimeout(10_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Cancelled } }
                importer.privateStorageAvailableForTest = null
                f.importer().recoverIncompleteBundles(f.root)
                assertEquals(before, f.packageBytes())
                assertTrue(f.bundleRecords().isEmpty())
                assertEquals("A", f.external.verifyPlaybackSong(f.root, VIDEO_ID).title)
            } finally { release.countDown(); importer.privateStorageAvailableForTest = null; coordinator.cancelForLibraryChange() }
        }
    }

    @Test fun discardAfterCapacityFailureRetiresItsReceiptAndCannotReplayTheAbandonedRevision() = runBlocking {
        withLibrary { f ->
            assertTrue(f.importer().importPcSong(f.archive("a.zip", "2026-08-09T12:00:00Z", "A"), VIDEO_ID,
                f.root.authority()).getOrThrow().items.single().isSuccess)
            val before = f.packageBytes()
            var failCapacity = true
            val importer = f.importer { phase ->
                if (phase == "BUNDLE_ORIGINAL_PUBLISHED" && failCapacity) {
                    failCapacity = false
                    error("STORAGE_RESERVE_LOW")
                }
            }
            val coordinator = EnrichmentImportCoordinator(f.context, importer, f.external, f.background, f.roots, scope = f.scope)
            try {
                coordinator.awaitStartupScratchReady()
                coordinator.offer(listOf(Uri.fromFile(f.archive("abandoned-b.zip", "2026-08-10T12:00:00Z", "B"))))
                coordinator.confirm()
                val waiting = withTimeout(15_000L) { coordinator.state.first { it is SharedEnrichmentImportState.AwaitingConfirmation } }
                    as SharedEnrichmentImportState.AwaitingConfirmation
                assertEquals("BUNDLE_STAGED", f.bundleRecords().single().value.getString("state"))
                val blocker = File(f.context.noBackupFilesDir, "import-staging/unowned-directory").apply { check(mkdir()) }
                coordinator.discardPendingImport(waiting.operationId)
                val failed = withTimeout(5_000L) { coordinator.state.first {
                    it is SharedEnrichmentImportState.AwaitingConfirmation && it.message != waiting.message
                } } as SharedEnrichmentImportState.AwaitingConfirmation
                assertEquals(waiting.operationId, failed.operationId)
                assertTrue(failed.sources.all { File(checkNotNull(it.path)).isFile })
                assertEquals(before, f.packageBytes())
                check(blocker.delete())
                coordinator.discardPendingImport(failed.operationId)
                withTimeout(5_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Idle } }
                f.importer().recoverIncompleteBundles(f.root)
                assertTrue(f.bundleRecords().isEmpty())
                assertEquals(before, f.packageBytes())
                coordinator.offer(listOf(Uri.fromFile(f.archive("accepted-c.zip", "2026-08-11T12:00:00Z", "C"))))
                coordinator.confirm()
                withTimeout(15_000L) { coordinator.state.first { it is SharedEnrichmentImportState.Finished } }
                assertEquals("C", f.external.verifyPlaybackSong(f.root, VIDEO_ID).title)
            } finally { coordinator.cancelForLibraryChange() }
        }
    }

    private suspend fun withLibrary(block: suspend (Fixture) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); grantProviderTree()
        val f = Fixture()
        try { f.open(); block(f) } finally {
            withTimeout(5_000L) { f.scope.coroutineContext[Job]?.cancelAndJoin() }
            f.database.close(); f.context.destroy(); isolatedContext = null
        }
    }
    private inner class Fixture {
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, database.libraryDao())
        val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
        val background = BackgroundWorkCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background, scope = scope)
        val publisher = CanonicalPackagePublisher(context, roots, database.libraryDao(), mutations, bindings, external, background)
        val original = File(context.cacheDir, "original.opus").apply { writeBytes(GoldenSongMediaFixture.originalOpus(targetContext, 12_000L)) }
        val root get() = checkNotNull(roots.captureHandle())
        suspend fun open() { roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow() }
        fun importer(checkpoint: (String) -> Unit = {}) = SongEnrichmentImporter(context, roots, mutations, bindings, background,
            originalPublisher = { publisher }, commitCheckpoint = checkpoint)
        fun archive(name: String, at: String, title: String) = writeArchive(context, name, payload(at, title, true), COVER_PNG, original)
        fun packageBytes() = File(providerRoot(), VIDEO_ID).listFiles()!!.associate { it.name to it.readBytes().toList() }
        fun bundleRecords() = checkedAtomicJournalRecords(File(context.filesDir, "operation-journals"), "BUNDLE_RECOVERY_REQUIRED")
            .filter { it.value.getString("operation") == "SONG_BUNDLE_IMPORT_V1" }
    }
}
