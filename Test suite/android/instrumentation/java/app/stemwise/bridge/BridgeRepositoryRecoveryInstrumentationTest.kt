package app.stemwise.bridge

import androidx.room.Room
import app.stemwise.data.*
import app.stemwise.enrichment.GoldenEnrichmentFixture
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

internal class BridgeRepositoryRecoveryInstrumentationTest : GoldenEnrichmentFixture() {
    private val connection = BridgeConnectionProfile("bridge-a.tailnet.ts.net", "bridge-a", "pc-a")
    private fun selection(link: String = "https://youtu.be/abcdefghijk") =
        BridgeSongSubmission(link, "fixture-model", "high", "priority")

    private fun authorized(root: RootAuthority, text: String, guide: String, now: Long) =
        approveBridgeSong(newBridgeSong(root, text, guide, now), selection(text), guide, now, connection)

    @Test fun normalRepositoryConstructionOnMainDefersDefaultStoreAndSavedConnectionReadsToIo() = runBlocking {
        withRepository { fixture ->
            BridgeConnectionStore(File(fixture.context.noBackupFilesDir, "bridge-connection.json")).save(connection)
            val accesses = java.util.concurrent.atomic.AtomicInteger()
            val guarded = object : android.content.ContextWrapper(fixture.context) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getNoBackupFilesDir(): File {
                    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                        "Bridge repository opened private storage on the UI thread"
                    }
                    accesses.incrementAndGet()
                    return fixture.context.noBackupFilesDir
                }
            }
            lateinit var repository: SongBridgeRepository
            withContext(Dispatchers.Main) {
                repository = SongBridgeRepository(guarded, fixture.roots, fixture.importer, fixture.external,
                    fixture.database.libraryDao(), fixture.background, producerReady = { true }, blockedIds = { emptySet() },
                    prepareExplicitReadd = { _, _ -> }, transportFor = { error("Local startup contacted Bridge") },
                    scope = fixture.scope, schedule = { _, _ -> })
            }
            val loaded = withTimeout(5_000L) { repository.state.first { !it.loading } }
            assertEquals(connection, loaded.connection)
            withTimeout(5_000L) { repository.requestResume().join() }
            assertTrue("Normal construction never exercised its deferred storage", accesses.get() >= 2)
            assertEquals(0, fixture.submissions)
            assertTrue(repository.tasks.value.isEmpty())
        }
    }

    @Test fun explicitRepositoryPairAgainRetainsLibraryAndAllowsOneFreshReviewedJobAfterOldWorkRetires() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val before = File(providerRoot(), VIDEO_ID).listFiles()!!.associate { it.name to it.readBytes() }
            fixture.conflictingSubmitResponse = true
            fixture.bridge.submitSong(selection("https://youtu.be/lmnopqrstuv")).getOrThrow()
            val oldId = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(oldId))
            val old = checkNotNull(fixture.store.record(oldId))
            assertEquals(BridgeSubmission.UNKNOWN, old.submission)
            assertTrue(old.inputsVerified)
            assertEquals(1, fixture.submissions)
            val beforePairing = CanonicalJson.encode(old.json())
            assertTrue(fixture.bridge.pairAgain().isFailure)
            assertEquals(0, fixture.pairings)
            assertArrayEquals(beforePairing, CanonicalJson.encode(checkNotNull(fixture.store.record(oldId)).json()))
            // A durable conclusive retirement is the authority to allow explicit re-pair.
            fixture.store.save(old.copy(retired = true, remoteRetired = true,
                task = old.task.copy(phase = BridgeSongPhase.CANCELLED)))
            // The receiver reset is an external boundary; approved input preparation above was real.
            fixture.remote = null
            fixture.conflictingSubmitResponse = false
            fixture.bridge.pairAgain().getOrThrow()
            assertEquals(1, fixture.pairings)
            assertEquals(connection, fixture.connections.current())
            fixture.bridge.submitSong(selection()).getOrThrow()
            val fresh = fixture.store.records().single { !it.retired }
            assertEquals(connection, fresh.connection)
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(fresh.task.id))
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(fresh.task.id))
            assertEquals(2, fixture.submissions) // One original uncertain send and one newly reviewed send.
            assertEquals(root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
            before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(File(providerRoot(), VIDEO_ID), name).readBytes()) }
            assertEquals(VIDEO_ID, fixture.external.verifyPlaybackSong(root, VIDEO_ID).id)
        }
    }

    @Test fun observedTerminalReceiverLossCanForgetRePairAndExplicitlySendOneFreshRequest() = runBlocking {
        for (terminal in listOf("failed", "cancelled")) withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val bytes = File(providerRoot(), VIDEO_ID).listFiles().orEmpty().filter(File::isFile)
                .associate { it.name to it.readBytes() }
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            checkNotNull(fixture.remote).put("state", terminal)
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            assertTrue(checkNotNull(fixture.store.record(id)).remoteTerminalObserved)
            fixture.receiverFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
            fixture.remote = null
            fixture.registered = false
            fixture.allowRetirement = true
            val restored = fixture.repository()
            assertTrue(restored.pairAgain().isFailure)
            assertEquals(0, fixture.pairings)
            restored.forget(id).getOrThrow()
            assertTrue(fixture.store.records().isEmpty())
            assertEquals(1, fixture.submissions)
            assertFalse(fixture.network.any { it.startsWith("delete ") || it.startsWith("cancel ") })
            restored.pairAgain().getOrThrow()
            assertEquals(1, fixture.pairings)
            restored.submitSong(selection()).getOrThrow()
            val fresh = fixture.store.records().single()
            assertNotEquals(id, fresh.task.id)
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(fresh.task.id))
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(fresh.task.id))
            assertEquals(2, fixture.submissions)
            assertEquals(root.authority(), fixture.roots.captureHandle()?.authority())
            bytes.forEach { (name, expected) -> assertArrayEquals(expected, File(File(providerRoot(), VIDEO_ID), name).readBytes()) }
        }
    }

    @Test fun interruptedForgetRetainsCompletedObservationThenAllowsRePairAndOneReviewedSend() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val bytes = File(providerRoot(), VIDEO_ID).listFiles().orEmpty().filter(File::isFile)
                .associate { it.name to it.readBytes() }
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            checkNotNull(fixture.remote).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1L).put("code", "invalid-song-report"))
                .put("outputs", JSONArray().put(BridgeFile("c".repeat(32), "song.zip", 1L, "d".repeat(64)).json()))
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            val failed = checkNotNull(fixture.store.record(id))
            assertEquals(BridgeSongPhase.FAILED, failed.task.phase)
            assertFalse(failed.remoteTerminalObserved)
            val networkBefore = fixture.network.toList()
            val wakesBefore = fixture.wakeCalls
            fixture.bridge.submitSong(selection()).getOrThrow()
            assertEquals(id, fixture.store.records().single().task.id)
            assertEquals(networkBefore, fixture.network.toList())
            assertEquals(wakesBefore, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
            val inputs = listOf("md", "input").associateWith { fixture.store.file(id, it).readBytes() }
            fixture.allowRetirement = true
            fixture.deleteFailure = BridgeApiFailure(503, "job_cleanup_authorization_unconfirmed", "Cleanup pending", true)
            fixture.bridge.forget(id).getOrThrow()
            val restarted = BridgeSongStore(fixture.store.directory)
            val retained = checkNotNull(restarted.record(id))
            assertEquals(BridgeSongPhase.RETIRING, retained.task.phase)
            assertTrue(retained.remoteTerminalObserved)
            assertFalse(retained.remoteRetired)
            fixture.bridge.submitSong(selection()).getOrThrow()
            assertEquals(id, restarted.records().single().task.id)
            assertEquals(wakesBefore, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
            assertArrayEquals(CanonicalJson.encode(failed.request), CanonicalJson.encode(retained.request))
            inputs.forEach { (suffix, expected) -> assertArrayEquals(expected, restarted.file(id, suffix).readBytes()) }
            fixture.receiverFailure = BridgeApiFailure(409, "app_registration_required", "Receiver removed", false)
            fixture.remote = null
            fixture.registered = false
            val restored = fixture.repository(restarted)
            assertTrue(restored.pairAgain().isFailure)
            assertEquals(BridgeSongWorkProgress.COMPLETE, restored.advance(id))
            assertTrue(restarted.records().isEmpty())
            assertEquals(1, fixture.submissions)
            assertEquals(1, fixture.network.count { it == "delete $id" })
            assertFalse(fixture.network.any { it.startsWith("cancel ") })
            restored.pairAgain().getOrThrow()
            fixture.deleteFailure = null
            restored.submitSong(selection()).getOrThrow()
            val fresh = restarted.records().single()
            assertNotEquals(id, fresh.task.id)
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(fresh.task.id))
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(fresh.task.id))
            assertEquals(2, fixture.submissions)
            assertEquals(1, fixture.pairings)
            assertEquals(root.authority(), fixture.roots.captureHandle()?.authority())
            bytes.forEach { (name, expected) -> assertArrayEquals(expected, File(File(providerRoot(), VIDEO_ID), name).readBytes()) }
        }
    }

    @Test fun transientInitialReadRecoversTheSameApprovalThroughResumeRefreshWorkerAndNewSubmission() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val original = fixture.store.records().single()
            val neighbor = newBridgeSong(original.root, "https://youtu.be/lmnopqrstuv", "", 2L)
            fixture.store.save(neighbor)
            val reads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
            val unavailable = java.util.concurrent.atomic.AtomicBoolean(true)
            val store = BridgeSongStore(fixture.store.directory) { file ->
                reads.computeIfAbsent(file.name) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                if (file.name == "${original.task.id}.json" && unavailable.get()) throw java.io.IOException("Initial transient read")
                file.readText(Charsets.UTF_8)
            }
            val restored = fixture.repository(store)
            withTimeout(5_000L) { restored.state.first { it.error != null } }
            withTimeout(5_000L) { restored.requestResume().join() }
            assertEquals(0, fixture.submissions)
            val targetApplication = targetContext.applicationContext as app.stemwise.StemwiseApplication
            withTimeout(5_000L) { targetApplication.workerInitialization.await() }
            val services = targetApplication.services
            val bridgeField = services.javaClass.getDeclaredField("songBridge").apply { isAccessible = true }
            val prior = bridgeField.get(services)
            try {
                bridgeField.set(services, restored)
                val worker = androidx.work.testing.TestListenableWorkerBuilder.from(targetApplication,
                    app.stemwise.work.BridgeSongWorker::class.java)
                    .setInputData(androidx.work.workDataOf("bridge_job_id" to original.task.id)).build()
                assertEquals(androidx.work.ListenableWorker.Result.retry(), withTimeout(5_000L) { worker.doWork() })
            } finally { bridgeField.set(services, prior) }
            unavailable.set(false)
            restored.refreshConnection().getOrThrow()
            withTimeout(5_000L) { restored.requestResume().join() }
            val resumed = checkNotNull(store.record(original.task.id))
            assertEquals(original.approval, resumed.approval)
            assertArrayEquals(CanonicalJson.encode(original.request), CanonicalJson.encode(resumed.request))
            assertEquals(1, reads["${neighbor.task.id}.json"]?.get())
            assertTrue(store.damagedTasks().isEmpty())
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(original.task.id))
            assertEquals(BridgeSongWorkProgress.PENDING, restored.advance(original.task.id))
            assertEquals(1, fixture.submissions)
            restored.submitSong(selection("https://youtu.be/Q0000000001")).getOrThrow()
            assertEquals(3, store.records().size)
            assertEquals(1, fixture.submissions)
            assertEquals(original.approval, store.record(original.task.id)?.approval)
        }
    }

    @Test fun staleBridgeZipStopsAutomaticImportRetriesAndPreservesTheNewerVerifiedTrio() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle())
            val approved = authorized(root.authority(), "https://youtu.be/$VIDEO_ID", "Frozen guide", 1L)
            fixture.store.save(approved)
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(approved.task.id))
            val newer = writeArchive(fixture.context, "newer-before-bridge.zip",
                payload("2026-11-04T12:00:00Z", "Newer owner revision", true), COVER_PNG)
            val installed = fixture.importer.importPcSong(newer, VIDEO_ID, root.authority()).getOrThrow()
            assertTrue(installed.items.single().isSuccess)
            fixture.external.reconcilePackage(root, VIDEO_ID)
            val folder = File(providerRoot(), VIDEO_ID)
            val trio = listOf(SelectedLibraryContract.ORIGINAL, SelectedLibraryContract.ENRICHMENT,
                SelectedLibraryContract.COVER).associateWith { File(folder, it).readBytes() }
            val older = writeArchive(fixture.context, "older-bridge.zip",
                payload("2026-11-03T12:00:00Z", "Older Bridge revision", true), COVER_PNG)
            val bytes = older.readBytes()
            fixture.receivedArchive = bytes
            val output = BridgeFile("c".repeat(32), "older-bridge.zip", bytes.size.toLong(), sha256(older))
            checkNotNull(fixture.remote).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1L).put("code", "stemwise_song_ready"))
                .put("outputs", JSONArray().put(output.json()))
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(approved.task.id))
            val rejected = checkNotNull(fixture.store.record(approved.task.id))
            assertEquals(BridgeSongPhase.FAILED, rejected.task.phase)
            assertTrue(rejected.task.message.orEmpty().contains("ENRICHMENT_UPDATE_STALE"))
            assertFalse(rejected.imported)
            assertArrayEquals(bytes, fixture.store.file(approved.task.id, "zip").readBytes())
            fixture.scheduled.clear()
            val attempts = rejected.attempts
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            repeat(2) { assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(approved.task.id)) }
            assertEquals(attempts, fixture.store.record(approved.task.id)?.attempts)
            assertTrue(fixture.scheduled.isEmpty())
            assertEquals(1, fixture.submissions)
            assertFalse(fixture.network.any { it.startsWith("ack ") })
            trio.forEach { (name, preserved) -> assertArrayEquals(name, preserved, File(folder, name).readBytes()) }
            assertEquals("Newer owner revision", fixture.external.verifyPlaybackSong(root, VIDEO_ID).title)
        }
    }

    @Test fun cancellationPersistsWhileLookupIsStalledAndNeverSubmitsAfterItsNullReply() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            fixture.jobLookupStarted = CompletableDeferred()
            fixture.jobLookupGate = CompletableDeferred()
            val advancement = async(Dispatchers.IO) { fixture.bridge.advance(id) }
            try {
                withTimeout(5_000L) { fixture.jobLookupStarted!!.await() }
                withTimeout(1_000L) { fixture.bridge.cancel(id).getOrThrow() }
                assertTrue(BridgeSongStore(fixture.store.directory).records().single().task.cancelRequested)
                assertFalse(advancement.isCompleted)
                fixture.jobLookupGate!!.complete(Unit)
                assertEquals(BridgeSongWorkProgress.COMPLETE, withTimeout(5_000L) { advancement.await() })
                val saved = BridgeSongStore(fixture.store.directory).records().single()
                assertEquals(BridgeSongPhase.CANCELLED, saved.task.phase)
                assertTrue(saved.task.cancelRequested)
                assertEquals(0, fixture.submissions)
            } finally {
                fixture.jobLookupGate!!.complete(Unit)
                withTimeout(5_000L) { advancement.cancelAndJoin() }
            }
        }
    }

    @Test fun reviewedSendPersistsBeforeWakeAndCatalogAndKeepsItsOriginalProfile() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.loadModelCatalog(connection).getOrThrow().validate(selection())
            fixture.bridge.saveConnection(BridgeConnectionProfile("bridge-b.tailnet.ts.net", "bridge-b", "pc-b")).getOrThrow()
            fixture.network.clear()
            fixture.modelFailure = BridgeApiFailure(503, "model_catalog_unavailable", "PC is starting", true)
            coroutineScope { List(2) { async { fixture.bridge.submitSong(selection(), connection).getOrThrow() } }.awaitAll() }
            val frozen = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSongPhase.PREPARING, frozen.task.phase)
            assertFalse(checkNotNull(frozen.preparation).wakeClaimed)
            assertFalse(frozen.preparation!!.catalogValidated)
            assertEquals(selection(), frozen.approval)
            assertTrue(fixture.network.isEmpty())
            assertEquals(setOf(frozen.task.id), fixture.scheduled.map { it.first }.toSet())
            fixture.bridge.saveConnection(BridgeConnectionProfile("bridge-b.tailnet.ts.net", "bridge-b", "pc-b")).getOrThrow()
            assertEquals(BridgeSongWorkProgress.RETRY, fixture.bridge.advance(frozen.task.id))
            val waiting = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSongPhase.WAITING_PC, waiting.task.phase)
            assertTrue(checkNotNull(waiting.preparation).wakeClaimed)
            assertFalse(waiting.preparation!!.catalogValidated)
            assertEquals(BridgeSubmission.UNSENT, waiting.submission)
            assertFalse(waiting.inputsVerified)
            assertEquals(frozen.request.toString(), waiting.request.toString())
            assertEquals(frozen.connection, waiting.connection)
            assertEquals(frozen.root, waiting.root)
            assertEquals(1, fixture.wakeCalls)
            assertEquals(0, fixture.submissions)
            assertEquals(listOf("POST /v2/connection/wake", "register", "GET /v2/codex/models"), fixture.network.toList())
            assertTrue(fixture.contactedProfiles.all { it == connection })
        }
    }

    @Test fun cancellationBeforePreparationEffectsPreventsWakeAndCatalog() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val original = fixture.store.records().single()
            val captured = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            fixture.preparationCaptured = {
                captured.complete(Unit)
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Preparation gate timed out" }
            }
            val advancement = async(Dispatchers.IO) { fixture.bridge.advance(original.task.id) }
            try {
                withTimeout(5_000L) { captured.await() }
                withTimeout(1_000L) { fixture.bridge.cancel(original.task.id).getOrThrow() }
                assertTrue(checkNotNull(BridgeSongStore(fixture.store.directory).record(original.task.id)).task.cancelRequested)
                release.countDown()
                assertEquals(BridgeSongWorkProgress.COMPLETE, withTimeout(5_000L) { advancement.await() })
                val cancelled = checkNotNull(BridgeSongStore(fixture.store.directory).record(original.task.id))
                assertEquals(BridgeSongPhase.CANCELLED, cancelled.task.phase)
                assertEquals(original.approval, cancelled.approval)
                assertEquals(original.request.toString(), cancelled.request.toString())
                assertFalse(cancelled.preparation!!.wakeClaimed)
                assertTrue(fixture.network.isEmpty())
                assertEquals(0, fixture.submissions)
            } finally {
                release.countDown()
                withTimeout(5_000L) { advancement.cancelAndJoin() }
            }
        }
    }

    @Test fun cancellationDuringCatalogPreservesDeletionTombstone() = runBlocking {
        withRepository { fixture ->
            fixture.useAuthoritativeDeletion = true
            val handle = checkNotNull(fixture.roots.captureHandle())
            fixture.trash.deletePermanently(handle, VIDEO_ID).getOrThrow()
            assertTrue(VIDEO_ID in fixture.bindings.confirmedDeletedPackageIds(handle))
            fixture.bridge.submitSong(selection("https://youtu.be/$VIDEO_ID")).getOrThrow()
            val original = fixture.store.records().single()
            fixture.catalogStarted = CompletableDeferred()
            fixture.catalogGate = CompletableDeferred()
            val advancement = async(Dispatchers.IO) { fixture.bridge.advance(original.task.id) }
            try {
                withTimeout(5_000L) { fixture.catalogStarted!!.await() }
                withTimeout(1_000L) { fixture.bridge.cancel(original.task.id).getOrThrow() }
                fixture.catalogGate!!.complete(Unit)
                assertEquals(BridgeSongWorkProgress.COMPLETE, withTimeout(5_000L) { advancement.await() })
                assertTrue(VIDEO_ID in fixture.bindings.confirmedDeletedPackageIds(handle))
                val cancelled = checkNotNull(BridgeSongStore(fixture.store.directory).record(original.task.id))
                assertEquals(BridgeSongPhase.CANCELLED, cancelled.task.phase)
                assertFalse(cancelled.preparation!!.catalogValidated)
                assertEquals(original.request.toString(), cancelled.request.toString())
                assertEquals(0, fixture.submissions)
            } finally {
                fixture.catalogGate!!.complete(Unit)
                withTimeout(5_000L) { advancement.cancelAndJoin() }
            }
        }
    }

    @Test fun retryRetainsCancelledRetirementAndResumesFailedCleanup() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            fixture.bridge.cancel(id).getOrThrow()
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            assertTrue(checkNotNull(fixture.store.record(id)).remoteTerminalObserved)
            val inputs = listOf("md", "input").associateWith { fixture.store.file(id, it).readBytes() }
            fixture.allowRetirement = true
            fixture.deleteFailure = BridgeApiFailure(503, "cleanup_unavailable", "Retry cleanup", true)
            fixture.bridge.forget(id).getOrThrow()
            val retiring = checkNotNull(BridgeSongStore(fixture.store.directory).record(id))
            assertEquals(BridgeSongPhase.RETIRING, retiring.task.phase)
            assertTrue(retiring.task.cancelRequested && retiring.task.retryable)
            fixture.scheduled.clear()
            fixture.network.clear()
            val restarted = fixture.repository(BridgeSongStore(fixture.store.directory))
            restarted.retry(id).getOrThrow()
            val resumed = checkNotNull(BridgeSongStore(fixture.store.directory).record(id))
            assertEquals(BridgeSongPhase.RETIRING, resumed.task.phase)
            assertTrue(resumed.task.cancelRequested && resumed.remoteTerminalObserved)
            assertEquals(retiring.request.toString(), resumed.request.toString())
            assertEquals(retiring.task.message, resumed.task.message)
            inputs.forEach { (suffix, bytes) -> assertArrayEquals(bytes, fixture.store.file(id, suffix).readBytes()) }
            assertEquals(listOf(id to true), fixture.scheduled.toList())
            assertTrue(fixture.network.isEmpty())
            fixture.deleteFailure = null
            assertEquals(BridgeSongWorkProgress.COMPLETE, restarted.advance(id))
            assertNull(BridgeSongStore(fixture.store.directory).record(id))
            assertEquals(listOf("job $id", "delete $id", "discard $id"), fixture.network.toList())
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun lostWakeResponseAndRestartReadbackOnlyResumeTheSameCatalogAndSubmission() = runBlocking {
        withRepository { fixture ->
            fixture.wakeFailure = java.io.IOException("Wake acknowledgement lost")
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.RETRY, fixture.bridge.advance(id))
            val saved = BridgeSongStore(fixture.store.directory).records().single()
            assertTrue(checkNotNull(saved.preparation).wakeClaimed)
            assertFalse(saved.preparation!!.catalogValidated)
            assertEquals(BridgeSubmission.UNSENT, saved.submission)
            assertTrue(fixture.bridge.tasks.value.single().canRetryWake)
            val restarted = fixture.repository(BridgeSongStore(fixture.store.directory))
            fixture.wakeFailure = null
            fixture.modelFailure = BridgeApiFailure(503, "model_catalog_unavailable", "PC is starting", true)
            withTimeout(5_000L) { restarted.requestResume().join() }
            assertEquals(1, fixture.wakeCalls)
            assertEquals(BridgeSongWorkProgress.RETRY, restarted.advance(id))
            assertEquals(1, fixture.wakeCalls)
            restarted.retry(id).getOrThrow()
            assertEquals(BridgeSongPhase.PREPARING, BridgeSongStore(fixture.store.directory).records().single().task.phase)
            fixture.modelFailure = null
            assertEquals(BridgeSongWorkProgress.PENDING, restarted.advance(id))
            val accepted = BridgeSongStore(fixture.store.directory).records().single()
            assertTrue(accepted.preparation!!.catalogValidated)
            assertEquals(BridgeSubmission.ACKNOWLEDGED, accepted.submission)
            assertEquals(saved.approval, accepted.approval)
            assertEquals(saved.task.id, accepted.task.id)
            assertEquals(1, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
            fixture.network.clear()
            restarted.submitSong(selection()).getOrThrow()
            assertTrue(fixture.network.isEmpty())
            restarted.advance(id)
            assertEquals(listOf("job $id"), fixture.network.toList())
            assertEquals(1, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun onlyExplicitRetryWakeRearmsItsUnsubmittedReviewedOperation() = runBlocking {
        withRepository { fixture ->
            fixture.wakeFailure = java.io.IOException("Wake acknowledgement lost")
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            fixture.bridge.advance(id)
            fixture.bridge.retryWake(id).getOrThrow()
            assertFalse(fixture.store.records().single().preparation!!.wakeClaimed)
            assertTrue(fixture.bridge.retryWake(id).isFailure)
            fixture.wakeFailure = null
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            assertEquals(2, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
            assertTrue(fixture.bridge.retryWake(id).isFailure)
            fixture.bridge.advance(id)
            assertEquals(2, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun freshSameProfileReachabilitySkipsWakeButStillValidatesTheExactCatalog() = runBlocking {
        withRepository { fixture ->
            fixture.pcState = "reachable"
            fixture.bridge.refreshConnection().getOrThrow()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            fixture.network.clear()
            fixture.bridge.submitSong(selection()).getOrThrow()
            assertTrue(fixture.network.isEmpty())
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            assertEquals(0, fixture.wakeCalls)
            assertTrue(fixture.network.contains("GET /v2/codex/models"))
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun installedNoOpAndExistingUnknownRequestNeverStartANewWakeOrSendCycle() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection("https://youtu.be/$VIDEO_ID")).getOrThrow()
            assertTrue(fixture.store.records().isEmpty())
            assertTrue(fixture.network.isEmpty())
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val prior = authorized(root, selection().link, "Already approved current request", 1L)
            fixture.store.save(prior)
            fixture.conflictingSubmitResponse = true
            fixture.bridge.advance(prior.task.id)
            val unconfirmed = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSubmission.UNKNOWN, unconfirmed.submission)
            assertEquals(BridgeSongPhase.FAILED, unconfirmed.task.phase)
            assertNull(unconfirmed.preparation)
            fixture.network.clear()
            fixture.bridge.submitSong(selection()).getOrThrow()
            assertEquals(listOf(prior.task.id), fixture.store.records().map { it.task.id })
            assertTrue(fixture.network.isEmpty())
            assertEquals(0, fixture.wakeCalls)
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun nativeAuthenticationIssueSurvivesResumeAndClearsOnlyOnAnotherConnectionObservation() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val submitted = fixture.store.records().single()
            fixture.bridge.advance(submitted.task.id)
            val frozen = fixture.store.records().single().request.toString()
            for ((state, code) in listOf("blocked" to "native_auth_change_pending", "auth_required" to "native_auth_required")) {
                fixture.codexAuth = JSONObject().put("state", state).put("code", code).put("validForMs", 0)
                fixture.bridge.refreshConnection().getOrThrow()
                withTimeout(5_000L) { fixture.bridge.requestResume().join() }
                assertEquals(code, fixture.bridge.state.value.connectionIssue)
                assertEquals("Online", fixture.bridge.state.value.pcStatus)
                assertEquals(1, fixture.submissions)
                assertEquals(listOf(submitted.task.id), fixture.store.records().map { it.task.id })
                assertEquals(frozen, fixture.store.records().single().request.toString())
            }
            fixture.codexAuth = JSONObject().put("state", "blocked").put("validForMs", 0)
            fixture.bridge.refreshConnection().getOrThrow()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertEquals("blocked", fixture.bridge.state.value.connectionIssue)
            fixture.codexAuth = JSONObject().put("state", "ready").put("code", "native_auth_ready").put("validForMs", 0)
            fixture.bridge.refreshConnection().getOrThrow()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertNull(fixture.bridge.state.value.connectionIssue)
            fixture.codexAuth = JSONObject().put("state", "auth_required").put("code", "native_auth_required")
            fixture.bridge.refreshConnection().getOrThrow()
            fixture.codexAuth = null
            fixture.bridge.refreshConnection().getOrThrow()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertNull(fixture.bridge.state.value.connectionIssue)
            assertEquals(1, fixture.submissions)
        }
    }

    @Test fun explicitRetryImportsTheExactRetainedVerifiedZipWithoutContactingThePc() = runBlocking {
        withRepository { fixture ->
            val failed = retainedImportFailure(fixture)
            fixture.scheduled.clear(); fixture.network.clear()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            val displayed = fixture.bridge.tasks.value.single()
            assertTrue(displayed.canRetry)
            assertTrue(displayed.localImportPending)
            assertFalse(displayed.canCancel)
            assertEquals(failed.task.message, displayed.message)
            assertTrue(fixture.scheduled.isEmpty())
            assertTrue(fixture.network.isEmpty())
            fixture.online = false
            fixture.bridge.retry(failed.task.id).getOrThrow()
            val queued = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(failed.task.message, queued.task.message)
            assertEquals(failed.root, queued.root)
            assertEquals(failed.connection, queued.connection)
            assertEquals(failed.approval, queued.approval)
            assertEquals(failed.request.toString(), queued.request.toString())
            assertEquals(listOf(failed.task.id to true), fixture.scheduled.toList())
            assertFalse(fixture.bridge.tasks.value.single().canCancel)
            assertEquals(BridgeSongWorkProgress.RETRY, fixture.bridge.advance(failed.task.id))
            val imported = BridgeSongStore(fixture.store.directory).records().single()
            assertTrue(imported.imported)
            assertTrue(imported.outputVerified)
            assertEquals(BridgeSongPhase.ACKNOWLEDGING, imported.task.phase)
            assertTrue(fixture.bridge.tasks.value.single().importSucceeded)
            assertTrue(fixture.bridge.tasks.value.single().deliveryPending)
            assertTrue(fixture.network.isEmpty())
            assertEquals(1, fixture.submissions)
            assertEquals("Retained retry fixture", fixture.external.verifyPlaybackSong(
                checkNotNull(fixture.roots.captureHandle()), VIDEO_ID).title)
        }
    }

    @Test fun retryOfMissingOrChangedVerifiedZipPreservesTheFailedRecordAndBytes() = runBlocking {
        withRepository { fixture ->
            val failed = retainedImportFailure(fixture)
            val output = fixture.store.file(failed.task.id, "zip")
            val original = output.readBytes()
            val record = File(fixture.store.directory, "${failed.task.id}.json")
            val durable = record.readBytes()
            fixture.scheduled.clear(); fixture.network.clear()
            check(output.delete())
            assertTrue(fixture.bridge.retry(failed.task.id).isFailure)
            assertFalse(output.exists())
            assertArrayEquals(durable, record.readBytes())
            val changed = original + byteArrayOf(1)
            output.writeBytes(changed)
            assertTrue(fixture.bridge.retry(failed.task.id).isFailure)
            assertArrayEquals(changed, output.readBytes())
            assertArrayEquals(durable, record.readBytes())
            assertTrue(fixture.scheduled.isEmpty())
            assertTrue(fixture.network.isEmpty())
            assertEquals(1, fixture.submissions)
        }
    }

    private suspend fun retainedImportFailure(fixture: Fixture): BridgeSongRecord {
        val root = checkNotNull(fixture.roots.captureHandle()).authority()
        val approved = authorized(root, "https://www.youtube.com/watch?v=$VIDEO_ID", "Frozen guide", 1L)
        fixture.store.save(approved)
        assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(approved.task.id))
        val submitted = BridgeSongStore(fixture.store.directory).records().single()
        val archive = writeArchive(fixture.context, "retained-retry.zip",
            payload("2026-08-09T12:50:00Z", "Retained retry fixture", true), COVER_PNG)
        val output = fixture.store.file(approved.task.id, "zip")
        archive.copyTo(output)
        val descriptor = BridgeFile("c".repeat(32), "retained-retry.zip", output.length(), sha256(output))
        val failed = submitted.copy(task = submitted.task.copy(phase = BridgeSongPhase.FAILED,
            message = "SOURCE_TIMELINE_MISMATCH: original failure", retryable = false), output = descriptor, outputVerified = true)
        fixture.store.save(failed)
        return failed
    }

    @Test fun unknownFailedSubmissionNeedsExplicitResumeAndObservesTheSameFrozenRequest() = runBlocking {
        withRepository { fixture ->
            fixture.conflictingSubmitResponse = true
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            val failed = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSongPhase.FAILED, failed.task.phase)
            assertEquals(BridgeSubmission.UNKNOWN, failed.submission)
            assertTrue(failed.inputsVerified)
            assertFalse(failed.task.canRetry)
            assertTrue(failed.task.message.orEmpty().contains("does not match"))
            assertTrue(fixture.bridge.tasks.value.single().canRetry)
            val durable = File(fixture.store.directory, "$id.json").readBytes()
            val inputs = listOf("md", "input").associateWith { fixture.store.file(id, it).readBytes() }
            fixture.scheduled.clear()
            fixture.network.clear()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertTrue("Background resume must wait for explicit recovery", fixture.scheduled.isEmpty())
            assertTrue(fixture.network.isEmpty())
            assertArrayEquals(durable, File(fixture.store.directory, "$id.json").readBytes())

            fixture.bridge.saveConnection(BridgeConnectionProfile("bridge-b.tailnet.ts.net", "bridge-b", "pc-b")).getOrThrow()
            fixture.bridge.retry(id).getOrThrow()
            val resumed = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(id, resumed.task.id)
            assertEquals(failed.request.toString(), resumed.request.toString())
            assertEquals(failed.approval, resumed.approval)
            assertEquals(failed.connection, resumed.connection)
            assertEquals(failed.root, resumed.root)
            assertEquals(BridgeSubmission.UNKNOWN, resumed.submission)
            assertEquals(listOf(id to true), fixture.scheduled.toList())
            assertTrue(fixture.network.isEmpty())
            inputs.forEach { (suffix, bytes) -> assertArrayEquals(bytes, fixture.store.file(id, suffix).readBytes()) }

            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            assertEquals(listOf("job $id"), fixture.network.toList())
            assertEquals(1, fixture.submissions)
            assertEquals(connection, fixture.contactedProfiles.last())
            val acknowledged = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSubmission.ACKNOWLEDGED, acknowledged.submission)
            assertEquals(BridgeSongPhase.RUNNING, acknowledged.task.phase)
            assertEquals(failed.request.toString(), acknowledged.request.toString())
        }
    }

    @Test fun confirmedRemoteFailureAndRetiredOrUnsubmittedRecordsCannotResume() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            fixture.bridge.advance(id)
            checkNotNull(fixture.remote).put("state", "failed").put("diagnostic", "Confirmed remote failure")
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            val confirmed = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSubmission.ACKNOWLEDGED, confirmed.submission)
            assertEquals(BridgeSongPhase.FAILED, confirmed.task.phase)
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val unsubmitted = authorized(root, selection().link, "Frozen guide", 2L).let {
                it.copy(task = it.task.copy(phase = BridgeSongPhase.FAILED))
            }
            val unknown = authorized(root, selection().link, "Frozen guide", 3L).let {
                it.copy(task = it.task.copy(phase = BridgeSongPhase.FAILED), submission = BridgeSubmission.UNKNOWN,
                    inputsVerified = true)
            }
            val excluded = listOf(confirmed, unsubmitted,
                unknown.copy(retired = true),
                authorized(root, selection().link, "Frozen guide", 4L).let {
                    it.copy(task = it.task.copy(phase = BridgeSongPhase.FAILED), submission = BridgeSubmission.UNKNOWN,
                        inputsVerified = true, remoteRetired = true)
                },
                authorized(root, selection().link, "Frozen guide", 5L).let {
                    it.copy(task = it.task.copy(phase = BridgeSongPhase.FAILED), submission = BridgeSubmission.UNKNOWN)
                })
            excluded.drop(1).forEach { fixture.store.save(it) }
            fixture.scheduled.clear()
            fixture.network.clear()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            for (record in excluded) {
                val bytes = File(fixture.store.directory, "${record.task.id}.json").readBytes()
                assertFalse(record.task.id, fixture.bridge.tasks.value.single { it.id == record.task.id }.canRetry)
                assertTrue(record.task.id, fixture.bridge.retry(record.task.id).isFailure)
                assertArrayEquals(bytes, File(fixture.store.directory, "${record.task.id}.json").readBytes())
            }
            assertTrue(fixture.scheduled.isEmpty())
            assertTrue(fixture.network.isEmpty())
        }
    }

    @Test fun connectionChangesPreserveApprovedJobsAndRejectStaleConnectionStatus() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection()).getOrThrow()
            val first = fixture.store.records().single()
            assertEquals(connection, first.connection)
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(first.task.id))
            assertEquals(1, fixture.submissions)
            val replacement = BridgeConnectionProfile("bridge-b.tailnet.ts.net", "bridge-b", "pc-b")
            fixture.connectionQueryStarted = CompletableDeferred()
            fixture.connectionReplyGate = CompletableDeferred()
            coroutineScope {
                val refresh = async { fixture.bridge.refreshConnection().getOrThrow() }
                withTimeout(5_000L) { fixture.connectionQueryStarted!!.await() }
                fixture.bridge.saveConnection(replacement).getOrThrow()
                fixture.connectionReplyGate!!.complete(Unit)
                withTimeout(5_000L) { refresh.await() }
            }
            assertEquals(replacement, fixture.connections.current())
            assertEquals(replacement, fixture.bridge.state.value.connection)
            assertEquals("Unknown", fixture.bridge.state.value.piStatus)
            fixture.connectionReplyGate = null
            fixture.bridge.advance(first.task.id)
            assertEquals(connection, fixture.contactedProfiles.last())
            assertEquals(1, fixture.submissions)
            fixture.bridge.submitSong(selection("https://youtu.be/lmnopqrstuv")).getOrThrow()
            val second = fixture.store.records().single { it.task.id != first.task.id }
            assertEquals(replacement, second.connection)
            assertEquals(connection, BridgeSongStore(fixture.store.directory).records().single { it.task.id == first.task.id }.connection)
        }
    }

    @Test fun discoveriesRestoreAsDraftsAndOnlyExplicitApprovalCanSubmitTheSameJob() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val id = "abcdefghijk"
            val dao = fixture.database.libraryDao()
            dao.upsertSongs(listOf(SongEntity(id, id, "playlist-item", selection().link, "Discovered", "Artist",
                null, null, durationMs = 12_000L, artworkPath = null, currentlyInSourcePlaylist = true,
                highLevelState = "AWAITING_PC", acquiredAtEpochMs = null, createdAtEpochMs = 1L)))
            repeat(2) { withTimeout(5_000L) { fixture.bridge.requestResume().join() } }
            val draft = BridgeSongStore(fixture.store.directory).records().single()
            assertTrue(draft.task.isDraft)
            assertNull(draft.approval)
            assertEquals(selection().link, draft.task.sourceUrl)
            assertEquals("Waiting for authorization", fixture.bridge.tasks.value.single().stage)
            val before = File(fixture.store.directory, "${draft.task.id}.json").readBytes()
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(draft.task.id))
            assertFalse(fixture.bridge.cancel(draft.task.id).isSuccess)
            assertTrue(fixture.bridge.retry(draft.task.id).isFailure)
            assertArrayEquals(before, File(fixture.store.directory, "${draft.task.id}.json").readBytes())
            assertTrue(fixture.scheduled.isEmpty())
            assertTrue(fixture.network.isEmpty())
            assertEquals(listOf(id), dao.pendingPcSongs().map { it.id })

            val reviewed = selection()
            coroutineScope { List(2) { async { fixture.bridge.authorizeDraft(draft.task.id, reviewed).getOrThrow() } }.awaitAll() }
            val approved = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(draft.task.id, approved.task.id)
            assertEquals(reviewed, approved.approval)
            assertEquals(setOf(draft.task.id), fixture.scheduled.map { it.first }.toSet())
            assertEquals(0, fixture.submissions)
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(draft.task.id))
            assertEquals(1, fixture.submissions)
            val frozen = File(fixture.store.directory, "${draft.task.id}.json").readBytes()
            assertTrue(fixture.bridge.authorizeDraft(draft.task.id, reviewed.copy(effort = "medium")).isFailure)
            assertArrayEquals(frozen, File(fixture.store.directory, "${draft.task.id}.json").readBytes())
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(draft.task.id))
            assertEquals(1, fixture.submissions)
            assertEquals(reviewed.model, fixture.remote!!.getJSONObject("input").getString("model"))
            assertEquals(reviewed.effort, fixture.remote!!.getJSONObject("input").getString("effort"))
            assertEquals(reviewed.serviceTier, fixture.remote!!.getJSONObject("input").getString("serviceTier"))
        }
    }

    @Test fun discardingADiscoveredDraftNeverCallsTheBridgeOrRecreatesItOnResume() = runBlocking {
        withRepository { fixture ->
            val id = "abcdefghijk"
            fixture.database.libraryDao().upsertSongs(listOf(SongEntity(id, id, "playlist-item", selection().link,
                "Discovered", "Artist", null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = true, highLevelState = "AWAITING_PC", acquiredAtEpochMs = null, createdAtEpochMs = 1L)))
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            val draft = fixture.store.records().single()
            fixture.bridge.forget(draft.task.id).getOrThrow()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertTrue(BridgeSongStore(fixture.store.directory).records().isEmpty())
            assertTrue(fixture.database.libraryDao().pendingPcSongs().isEmpty())
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
        }
    }

    @Test fun forgettingWhileDiscoveryDrainIsCapturedCannotRecreateTheDraft() = runBlocking {
        withRepository { fixture ->
            val id = "abcdefghijk"
            fixture.database.libraryDao().upsertSongs(listOf(SongEntity(id, id, "playlist-item", selection().link,
                "Discovered", "Artist", null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = true, highLevelState = "AWAITING_PC", acquiredAtEpochMs = null, createdAtEpochMs = 1L)))
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            val draft = fixture.store.records().single()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.bridge.playlistDiscoveryCapturedForTest = { captured.complete(Unit); release.await() }
            val drain = fixture.bridge.requestResume()
            try {
                withTimeout(5_000L) { captured.await() }
                fixture.bridge.forget(draft.task.id).getOrThrow()
            } finally {
                fixture.bridge.playlistDiscoveryCapturedForTest = null
                release.complete(Unit)
                withTimeout(5_000L) { drain.join() }
            }
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertTrue(fixture.store.records().isEmpty())
            assertTrue(fixture.database.libraryDao().pendingPcSongs().isEmpty())
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
        }
    }

    @Test fun thousandLocalDiscoveriesNeverConsumeExplicitRemoteSubmissionCapacity() = runBlocking {
        withRepository { fixture ->
            val ids = List(1_000) { "Q" + it.toString().padStart(10, '0') }
            val dao = fixture.database.libraryDao()
            dao.upsertSongs(ids.mapIndexed { index, id -> SongEntity(id, id, "playlist-$index", "https://youtu.be/$id",
                "Discovery $index", "Artist", null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = true, highLevelState = "AWAITING_PC", acquiredAtEpochMs = null,
                createdAtEpochMs = index + 1L) })
            withTimeout(60_000L) { fixture.bridge.requestResume().join() }
            val restored = BridgeSongStore(fixture.store.directory).records()
            assertEquals(ids.toSet(), restored.map { it.task.videoId }.toSet())
            assertTrue(restored.all { it.task.isDraft })
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            fixture.bridge.submitSong(selection()).getOrThrow()
            val accepted = BridgeSongStore(fixture.store.directory).records()
            assertEquals(1_001, accepted.size)
            assertEquals(1_000, accepted.count { it.task.isDraft })
            assertEquals(1, accepted.count { it.approval != null })
            assertEquals(1, fixture.scheduled.size)
            assertTrue(fixture.network.isEmpty())
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun changedCatalogCorrectionReclaimsCapacityAndReplaysItsExactReplacement() = runBlocking {
        withRepository { fixture ->
            assertTrue(fixture.bridge.submitSong(selection("https://youtu.be/abcdefghijk https://youtu.be/lmnopqrstuv")).isFailure)
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.store.records().isEmpty())
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val draft = newBridgeSong(root, selection().link, "Draft guide", 1L)
            fixture.store.save(draft)
            fixture.catalogTiers = JSONArray()
            fixture.bridge.authorizeDraft(draft.task.id, selection()).getOrThrow()
            assertTrue(fixture.network.isEmpty())
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(draft.task.id))
            val waiting = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(BridgeSongPhase.REVIEW_REQUIRED, waiting.task.phase)
            assertEquals(selection(), waiting.approval)
            assertFalse(checkNotNull(waiting.preparation).catalogValidated)
            assertEquals(0, waiting.request.getJSONArray("files").length())
            assertEquals(0, fixture.submissions)
            fixture.scheduled.clear()
            fixture.network.clear()
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertTrue(fixture.scheduled.isEmpty())
            val frozen = CanonicalJson.encode(waiting.request)
            repeat(127) { index ->
                val retired = authorized(root, "https://youtu.be/Q${index.toString().padStart(10, '0')}", "Retired guide", index + 2L)
                fixture.store.save(retired.copy(imported = true, retired = true, remoteRetired = true,
                    task = retired.task.copy(phase = BridgeSongPhase.IMPORTED)))
            }
            assertEquals(128, fixture.store.records().size)
            val corrected = selection().copy(effort = "medium", serviceTier = "")
            fixture.bridge.authorizeDraft(draft.task.id, corrected).getOrThrow()
            val records = BridgeSongStore(fixture.store.directory).records()
            val previous = records.single { it.task.id == draft.task.id }
            val replacement = records.single { it.replacesRequestId == draft.task.id }
            assertEquals(129, records.size)
            assertEquals(BridgeSongPhase.CANCELLED, previous.task.phase)
            assertArrayEquals(frozen, CanonicalJson.encode(previous.request))
            assertEquals(selection(), previous.approval)
            assertEquals(corrected, replacement.approval)
            assertEquals(draft.task.id, replacement.replacesRequestId)
            assertFalse(checkNotNull(replacement.preparation).wakeClaimed)
            assertTrue(fixture.network.isEmpty())
            val restarted = fixture.repository(BridgeSongStore(fixture.store.directory))
            restarted.authorizeDraft(draft.task.id, corrected).getOrThrow()
            assertEquals(129, fixture.store.records().size)
            assertEquals(1, BridgeSongStore(fixture.store.directory).records().count { it.replacesRequestId == draft.task.id })
            assertTrue(fixture.bridge.authorizeDraft(draft.task.id, corrected.copy(effort = "high")).isFailure)
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(replacement.task.id))
            assertEquals(1, fixture.submissions)
            assertEquals(BridgeSubmission.UNSENT, fixture.store.records().single { it.task.id == draft.task.id }.submission)
        }
    }

    @Test fun expiredInstalledDeliveryRetiresAtCapacityAndAdmitsAnotherReviewedRequest() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            val document = JSONObject(payload("2026-08-09T12:00:00Z", "Expired delivery", true).toString()
                .replace(VIDEO_ID, "abcdefghijk"))
            val archive = writeArchive(fixture.context, "expired-delivery.zip", document, COVER_PNG)
            fixture.receivedArchive = archive.readBytes()
            val bytes = checkNotNull(fixture.receivedArchive)
            val output = BridgeFile("c".repeat(32), "expired-delivery.zip", bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
            checkNotNull(fixture.remote).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1L).put("code", "stemwise_song_ready"))
                .put("outputs", JSONArray().put(output.json()))
            fixture.ackFailure = BridgeApiFailure(410, "request_deleted", "Remote retention ended", false)
            assertEquals(BridgeSongWorkProgress.COMPLETE, fixture.bridge.advance(id))
            val installed = fixture.external.verifyPlaybackSong(checkNotNull(fixture.roots.captureHandle()), "abcdefghijk")
            assertFalse(installed.sourcePath.isNullOrBlank())
            val expired = fixture.store.records().single()
            assertEquals(BridgeSongPhase.EXPIRED, expired.task.phase)
            assertTrue(expired.imported && expired.resumableTask.deliveryUnavailable && expired.task.canForget)
            repeat(127) { index ->
                fixture.store.save(newBridgeSong(root, "https://youtu.be/Q${index.toString().padStart(10, '0')}", "Waiting draft", index + 2L))
            }
            assertEquals(128, fixture.store.records().size)
            val remoteCalls = fixture.network.toList()
            fixture.bridge.forget(id).getOrThrow()
            assertEquals(remoteCalls, fixture.network.toList())
            assertEquals(127, BridgeSongStore(fixture.store.directory).records().size)
            listOf("zip", "md", "input").forEach { suffix -> assertFalse(fixture.store.file(id, suffix).exists()) }
            assertFalse(File(fixture.store.directory, "$id.retained").exists())
            assertEquals(installed, fixture.external.verifyPlaybackSong(checkNotNull(fixture.roots.captureHandle()), "abcdefghijk"))
            fixture.bridge.submitSong(selection("https://youtu.be/lmnopqrstuv")).getOrThrow()
            assertEquals(128, BridgeSongStore(fixture.store.directory).records().size)
            assertEquals(1, fixture.submissions)
            assertEquals(1, fixture.network.count { it == "ack $id" })
        }
    }

    @Test fun deliveryConfirmationHoldsTheVerifiedPackageAndRootUntilTheTransportReturns() = runBlocking {
        withRepository { fixture ->
            val videoId = "abcdefghijk"
            fixture.bridge.submitSong(selection("https://youtu.be/$videoId")).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            val document = JSONObject(payload("2026-08-09T12:00:00Z", "Confirmation owner", true).toString()
                .replace(VIDEO_ID, videoId))
            fixture.receivedArchive = writeArchive(fixture.context, "owned-confirmation.zip", document, COVER_PNG).readBytes()
            val bytes = checkNotNull(fixture.receivedArchive)
            val output = BridgeFile("c".repeat(32), "owned-confirmation.zip", bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
            checkNotNull(fixture.remote).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1L).put("code", "stemwise_song_ready"))
                .put("outputs", JSONArray().put(output.json()))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.ackEntered = entered; fixture.ackGate = release
            val waitingForRoot = CompletableDeferred<Unit>()
            fixture.mutations.rootReplacementWaitingForTest = { waitingForRoot.complete(Unit) }
            val replacementEntered = CompletableDeferred<Unit>()
            val deletionEntered = CompletableDeferred<Unit>()
            val advance = async(Dispatchers.IO) { fixture.bridge.advance(id) }
            var replacement: Deferred<Unit>? = null
            var deletion: Deferred<Result<Unit>>? = null
            try {
                withTimeout(10_000L) { entered.await() }
                val root = checkNotNull(fixture.roots.captureHandle())
                assertTrue("Canonical ownership ended before SDK confirmation", fixture.mutations.withRootMutation {
                    fixture.mutations.packageIsOwnedUnderRootLock(root, videoId)
                })
                replacement = async(Dispatchers.IO) {
                    fixture.mutations.withRootReplacement { replacementEntered.complete(Unit); Unit }
                }
                withTimeout(5_000L) { waitingForRoot.await() }
                assertFalse("Root replacement entered while delivery still owned its verified package", replacementEntered.isCompleted)
                deletion = async(Dispatchers.IO) {
                    fixture.trash.deletePermanently(root, videoId, onExactRootAdmitted = { deletionEntered.complete(Unit) })
                }
                assertTrue(File(providerRoot(), "$videoId/song.json").isFile)
                assertFalse(deletionEntered.isCompleted)
                release.complete(Unit)
                withTimeout(10_000L) { advance.await(); checkNotNull(replacement).await(); checkNotNull(deletion).await().getOrThrow() }
                assertTrue(replacementEntered.isCompleted)
                assertTrue(deletionEntered.isCompleted)
                assertFalse(File(providerRoot(), videoId).exists())
                assertEquals(1, fixture.network.count { it == "ack $id" })
            } finally {
                release.complete(Unit)
                withTimeout(10_000L) { advance.cancelAndJoin(); replacement?.cancelAndJoin(); deletion?.cancelAndJoin() }
                fixture.mutations.rootReplacementWaitingForTest = null
                fixture.ackEntered = null; fixture.ackGate = null
            }
        }
    }

    @Test fun explicitDeletionAfterVerifiedImportSettlesPendingAckWithoutResurrectionAndAllowsReviewedReadd() = runBlocking {
        withRepository { fixture ->
            fixture.useAuthoritativeDeletion = true
            val videoId = "abcdefghijk"
            fixture.bridge.submitSong(selection("https://youtu.be/$videoId")).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(BridgeSongWorkProgress.PENDING, fixture.bridge.advance(id))
            val document = JSONObject(payload("2026-08-09T12:00:00Z", "Deleted delivery", true).toString()
                .replace(VIDEO_ID, videoId))
            val archive = writeArchive(fixture.context, "pending-ack.zip", document, COVER_PNG)
            fixture.receivedArchive = archive.readBytes()
            val bytes = checkNotNull(fixture.receivedArchive)
            val output = BridgeFile("c".repeat(32), "pending-ack.zip", bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
            checkNotNull(fixture.remote).put("state", "completed")
                .put("report", JSONObject().put("status", "completed").put("revision", 1L).put("code", "stemwise_song_ready"))
                .put("outputs", JSONArray().put(output.json()))
            fixture.ackFailure = java.io.IOException("Delivery acknowledgement unavailable")
            assertEquals(BridgeSongWorkProgress.RETRY, fixture.bridge.advance(id))
            assertTrue(fixture.store.records().single().installationVerified)
            val root = checkNotNull(fixture.roots.captureHandle())
            fixture.external.verifyPlaybackSong(root, videoId)
            val retainedZip = fixture.store.file(id, "zip").readBytes()
            fixture.trash.deletePermanently(root, videoId).getOrThrow()
            assertEquals(setOf(videoId), fixture.bindings.confirmedDeletedPackageIds(root))
            assertFalse(File(providerRoot(), videoId).exists())
            val before = fixture.network.toList()
            val restarted = fixture.repository()
            assertEquals(BridgeSongWorkProgress.COMPLETE, restarted.advance(id))
            assertEquals(BridgeSongPhase.REMOVED, fixture.store.records().single().task.phase)
            assertEquals(before, fixture.network.toList())
            assertArrayEquals(retainedZip, fixture.store.file(id, "zip").readBytes())
            assertFalse(File(providerRoot(), videoId).exists())
            // Explicit approval may create one new request while the old removed receipt remains forgettable.
            restarted.submitSong(selection("https://youtu.be/$videoId")).getOrThrow()
            assertEquals(2, fixture.store.records().size)
            assertEquals(1, fixture.submissions)
            restarted.forget(id).getOrThrow()
            assertEquals(1, fixture.store.records().size)
            assertEquals(before, fixture.network.toList())
            assertFalse(File(providerRoot(), videoId).exists())
        }
    }

    @Test fun interruptedReviewedReplacementResumesItsSavedSuccessorWithoutAnotherApproval() = runBlocking {
        withRepository { fixture ->
            fixture.catalogTiers = JSONArray()
            fixture.bridge.submitSong(selection()).getOrThrow()
            val old = fixture.store.records().single()
            fixture.bridge.advance(old.task.id)
            assertEquals(BridgeSongPhase.REVIEW_REQUIRED, fixture.store.records().single().task.phase)
            val corrected = selection().copy(serviceTier = "")
            fixture.bridge.reviewedReplacementSavedForTest = { throw java.io.IOException("Interrupted after replacement commit") }
            assertTrue(fixture.bridge.authorizeDraft(old.task.id, corrected).isFailure)
            val saved = BridgeSongStore(fixture.store.directory).records()
            assertEquals(2, saved.size)
            val replacement = saved.single { it.replacesRequestId == old.task.id }
            fixture.bridge.reviewedReplacementSavedForTest = null
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            assertEquals(BridgeSongPhase.CANCELLED, fixture.store.records().single { it.task.id == old.task.id }.task.phase)
            fixture.bridge.authorizeDraft(old.task.id, corrected).getOrThrow()
            assertEquals(2, fixture.store.records().size)
            assertTrue(fixture.scheduled.any { it.first == replacement.task.id })
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun editingADiscoveredLinkKeepsTheOriginalPlaylistSongOwedAndRebuildsExactInstructions() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val old = newBridgeSong(root, selection().link, "Draft guide", 1L)
            fixture.store.save(old)
            val id = "abcdefghijk"
            fixture.database.libraryDao().upsertSongs(listOf(SongEntity(id, id, "playlist-item", selection().link,
                "Original discovery", "Artist", null, null, durationMs = 12_000L, artworkPath = null,
                currentlyInSourcePlaylist = true, highLevelState = "AWAITING_PC", acquiredAtEpochMs = null, createdAtEpochMs = 1L)))
            val edited = selection("https://youtu.be/lmnopqrstuv")
            fixture.bridge.authorizeDraft(old.task.id, edited).getOrThrow()
            val actual = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals("lmnopqrstuv", actual.task.videoId)
            assertEquals(edited.link, actual.task.sourceUrl)
            assertTrue(actual.request.getString("instructions").contains("exact YouTube slot lmnopqrstuv"))
            assertFalse(actual.request.getString("instructions").contains("exact YouTube slot abcdefghijk"))
            assertEquals(edited, actual.approval)
            assertTrue(fixture.store.recordsFor(root, id).isEmpty())
            assertEquals(listOf(actual.task.id), fixture.store.recordsFor(root, "lmnopqrstuv").map { it.task.id })
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            val records = BridgeSongStore(fixture.store.directory).records()
            assertEquals(2, records.size)
            assertTrue(records.single { it.task.videoId == id }.task.isDraft)
            assertEquals(listOf(records.single { it.task.videoId == id }.task.id), fixture.store.recordsFor(root, id).map { it.task.id })
            assertEquals(listOf(actual.task.id), fixture.store.recordsFor(root, "lmnopqrstuv").map { it.task.id })
            assertEquals(listOf(id), fixture.database.libraryDao().pendingPcSongs().map { it.id })
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun reviewedDraftFromAnotherLibraryOrTreeCannotAuthorizeEvenAnInstalledSong() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            for (wrongRoot in listOf(root.copy(libraryId = "another-library"), root.copy(treeUri = "content://another/tree"))) {
                val draft = newBridgeSong(wrongRoot, selection().link, "Draft guide", 1L)
                fixture.store.save(draft)
                val file = File(fixture.store.directory, "${draft.task.id}.json")
                val before = file.readBytes()
                for (link in listOf(selection().link, "https://youtu.be/$VIDEO_ID")) {
                    val error = fixture.bridge.authorizeDraft(draft.task.id, selection(link)).exceptionOrNull()
                    assertEquals(BridgeFailureCode.LIBRARY_CHANGED, (error as? BridgeLocalFailure)?.code)
                    assertArrayEquals(before, file.readBytes())
                }
            }
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
        }
    }

    @Test fun reviewedAndAcceptedOrUncertainRequestsCannotBeRetargetedToAnotherSong() = runBlocking {
        for (state in listOf("review", "accepted", "uncertain")) withRepository { fixture ->
            if (state == "review") fixture.catalogTiers = JSONArray()
            fixture.conflictingSubmitResponse = state == "uncertain"
            fixture.bridge.submitSong(selection()).getOrThrow()
            val id = fixture.store.records().single().task.id
            assertEquals(if (state == "accepted") BridgeSongWorkProgress.PENDING else BridgeSongWorkProgress.COMPLETE,
                fixture.bridge.advance(id))
            val original = checkNotNull(BridgeSongStore(fixture.store.directory).record(id))
            assertEquals(when (state) {
                "review" -> BridgeSubmission.UNSENT
                "accepted" -> BridgeSubmission.ACKNOWLEDGED
                else -> BridgeSubmission.UNKNOWN
            }, original.submission)
            val file = File(fixture.store.directory, "$id.json")
            val before = file.readBytes()
            val inputs = listOf("md", "input").mapNotNull { suffix ->
                fixture.store.file(id, suffix).takeIf(File::isFile)?.let { suffix to it.readBytes() }
            }
            fixture.network.clear()
            fixture.scheduled.clear()
            for (link in listOf("https://youtu.be/lmnopqrstuv", "https://youtu.be/$VIDEO_ID")) {
                assertTrue(state, fixture.bridge.authorizeDraft(id, selection(link)).isFailure)
                assertArrayEquals(before, file.readBytes())
                inputs.forEach { (suffix, bytes) -> assertArrayEquals(bytes, fixture.store.file(id, suffix).readBytes()) }
                assertEquals(listOf(id), BridgeSongStore(fixture.store.directory).records().map { it.task.id })
            }
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            assertEquals(if (state == "review") 0 else 1, fixture.submissions)
        }
    }

    @Test fun editingADiscoveryCannotReplaceAnotherSavedSongRequest() = runBlocking {
        withRepository { fixture ->
            fixture.bridge.submitSong(selection("https://youtu.be/lmnopqrstuv")).getOrThrow()
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val draft = newBridgeSong(root, selection().link, "Draft guide", 1L)
            fixture.store.save(draft)
            val before = fixture.store.records().associate { record ->
                record.task.id to File(fixture.store.directory, "${record.task.id}.json").readBytes()
            }
            fixture.scheduled.clear()
            assertTrue(fixture.bridge.authorizeDraft(draft.task.id, selection("https://youtu.be/lmnopqrstuv")).isFailure)
            assertEquals(before.keys, BridgeSongStore(fixture.store.directory).records().map { it.task.id }.toSet())
            before.forEach { (id, bytes) -> assertArrayEquals(bytes, File(fixture.store.directory, "$id.json").readBytes()) }
            assertTrue(fixture.network.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
        }
    }

    @Test fun modelOptionsRegisterThisInstallationWithoutStartingASongJob() = runBlocking {
        withRepository { fixture ->
            val catalog = fixture.bridge.loadModelCatalog().getOrThrow()
            catalog.validate(selection())
            assertTrue(fixture.registered)
            assertEquals(listOf("register", "GET /v2/codex/models"), fixture.network.toList())
            assertTrue(fixture.store.records().isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun remoteClockSkewCannotInvalidateFreshLocalConnectivity() = runBlocking {
        withRepository { fixture ->
            for (skew in listOf(-600_000L, 600_000L)) {
                fixture.remoteObservedAt = System.currentTimeMillis() + skew
                fixture.elapsed.set(10_000L)
                fixture.bridge.refreshConnection().getOrThrow()
                assertEquals("Fresh response was rejected because of remote wall time", "Online", fixture.bridge.state.value.piStatus)
                assertEquals("Online", fixture.bridge.state.value.pcStatus)
                assertEquals(925L, fixture.bridge.state.value.validForMs)
            }
            fixture.validForMs = 50L
            fixture.bridge.refreshConnection().getOrThrow()
            assertEquals("Unknown", fixture.bridge.state.value.piStatus)
            assertEquals(0L, fixture.bridge.state.value.validForMs)
        }
    }

    @Test fun failedBackgroundResumePublishesErrorRetiresAndRebindsTheSameRequestOnRetry() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val record = authorized(root.copy(epoch = root.epoch + 10L),
                "https://www.youtube.com/watch?v=$VIDEO_ID", "Frozen fixture guide", 1L)
            fixture.store.save(record)
            val blocked = File(fixture.store.directory, "${record.task.id}.new").apply { check(mkdir()) }
            val before = File(fixture.store.directory, "${record.task.id}.json").readBytes()
            try {
                withTimeout(5_000L) { fixture.bridge.requestResume().join() }
                assertNotNull("Resume lost its recoverable persistence failure", fixture.bridge.state.value.error)
                assertTrue(checkNotNull(fixture.bridge.state.value.error).contains("could not be written"))
                assertNull("Background failure escaped its repository owner", fixture.escaped.get())
                assertArrayEquals(before, File(fixture.store.directory, "${record.task.id}.json").readBytes())
                assertTrue(fixture.scheduled.isEmpty())
            } finally { check(blocked.delete()) }
            withTimeout(5_000L) { fixture.bridge.requestResume().join() }
            val rebound = BridgeSongStore(fixture.store.directory).records().single()
            assertEquals(record.task.id, rebound.task.id)
            assertEquals(root, rebound.root)
            assertEquals(listOf(record.task.id to true), fixture.scheduled.toList())
            assertNull(fixture.escaped.get())
        }
    }

    @Test fun removedInstallationCreatesOneFreshRequestWhileInstalledLiveAndDeletedSongsDoNot() = runBlocking {
        withRepository { fixture ->
            val root = checkNotNull(fixture.roots.captureHandle()).authority()
            val prior = authorized(root, "https://www.youtube.com/watch?v=$VIDEO_ID", "Frozen fixture guide", 1L)
            fixture.store.save(prior.copy(task = prior.task.copy(phase = BridgeSongPhase.IMPORTED), imported = true, retired = true, remoteRetired = true))
            fixture.bridge.submitSong(selection("https://www.youtube.com/watch?v=$VIDEO_ID")).getOrThrow()
            assertTrue(fixture.scheduled.isEmpty())
            val folder = File(providerRoot(), VIDEO_ID)
            val bytes = folder.listFiles()!!.associate { it.name to it.readBytes() }
            folder.listFiles()!!.forEach { check(it.delete()) }; check(folder.delete())
            fixture.external.reconcile()
            fixture.bridge.submitSong(selection("https://www.youtube.com/watch?v=$VIDEO_ID")).getOrThrow()
            val fresh = BridgeSongStore(fixture.store.directory).records().single { it.task.id != prior.task.id }
            assertEquals(listOf(fresh.task.id to false), fixture.scheduled.toList())
            fixture.bridge.submitSong(selection("https://www.youtube.com/watch?v=$VIDEO_ID")).getOrThrow()
            assertEquals(1, fixture.scheduled.map { it.first }.distinct().size)
            assertEquals(2, BridgeSongStore(fixture.store.directory).records().size)
            fixture.blocked = setOf(VIDEO_ID)
            fixture.bridge.submitSong(selection("https://www.youtube.com/watch?v=$VIDEO_ID")).getOrThrow()
            fixture.bridge.advance(fresh.task.id)
            assertTrue(fixture.blocked.isEmpty())
            assertEquals(3, fixture.scheduled.size)
            check(folder.mkdir()); bytes.forEach { (name, value) -> File(folder, name).writeBytes(value) }
        }
    }

    private suspend fun withRepository(block: suspend (Fixture) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val fixture = Fixture()
        try { fixture.open(); block(fixture) } finally { fixture.close() }
    }

    private inner class Fixture {
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, database.libraryDao())
        val bindings = SourceBindingRegistry(context, roots, mutations, database.libraryDao())
        val background = BackgroundWorkCoordinator()
        val external = ExternalLibraryRepository(context, roots, database.libraryDao(), bindings, mutations, background)
        val importer = songImporter(context, roots, mutations, bindings, background, database)
        val escaped = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> escaped.set(error) })
        val elapsed = AtomicLong(10_000L)
        var remoteObservedAt = 0L
        var validForMs = 1_000L
        var pcState = "Online"
        var wakeCalls = 0
        var wakeFailure: Exception? = null
        var modelFailure: Exception? = null
        var preparationCaptured: (() -> Unit)? = null
        var catalogStarted: CompletableDeferred<Unit>? = null
        var catalogGate: CompletableDeferred<Unit>? = null
        var codexAuth: JSONObject? = null
        var blocked = emptySet<String>()
        var useAuthoritativeDeletion = false
        val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
        val scheduled = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val store = BridgeSongStore(File(context.noBackupFilesDir, "pc-song-jobs"))
        val network = CopyOnWriteArrayList<String>()
        var pairings = 0
        var registered = false
        var receiverFailure: Exception? = null
        var deleteFailure: Exception? = null
        var allowRetirement = false
        var submissions = 0
        var online = true
        var conflictingSubmitResponse = false
        var receivedArchive: ByteArray? = null
        var ackFailure: Exception? = null
        var ackEntered: CompletableDeferred<Unit>? = null
        var ackGate: CompletableDeferred<Unit>? = null
        var jobLookupStarted: CompletableDeferred<Unit>? = null
        var jobLookupGate: CompletableDeferred<Unit>? = null
        var connectionQueryStarted: CompletableDeferred<Unit>? = null
        var connectionReplyGate: CompletableDeferred<Unit>? = null
        var remote: JSONObject? = null
        var catalogTiers = JSONArray().put(JSONObject().put("id", "priority").put("name", "Fast").put("description", "Fixture speed"))
        val transport = object : SongBridgeTransport {
            override suspend fun pairAgain() { pairings++; receiverFailure = null; registered = true; network += "pair again" }
            override val networkAvailable get() = online
            override suspend fun status() = JSONObject().put("backendState", "Running")
            override suspend fun ensureApp() { registered = true; network += "register" }
            override suspend fun api(method: String, path: String, body: JSONObject?): JSONObject {
                network += "$method $path"
                if (method == "POST" && path == "/v2/connection/wake") {
                    assertTrue(BridgeSongStore(store.directory).records().any { it.preparation?.wakeClaimed == true && it.task.phase == BridgeSongPhase.WAKING })
                    wakeCalls++
                    wakeFailure?.let { throw it }
                    return JSONObject().put("state", "packet_sent").put("pcState", "unconfirmed")
                }
                if (path == "/v2/codex/models") check(registered) { "app_registration_required" }
                if (path == "/v2/codex/models") modelFailure?.let { throw it }
                if (method == "GET" && path == "/v2/codex/models") {
                    catalogStarted?.complete(Unit)
                    catalogGate?.await()
                    return JSONObject().put("nextCursor", JSONObject.NULL)
                    .put("data", JSONArray().put(JSONObject().put("id", "fixture-id").put("model", "fixture-model")
                        .put("isDefault", true).put("defaultReasoningEffort", "high")
                        .put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "high"))
                            .put(JSONObject().put("reasoningEffort", "medium")))
                        .put("serviceTiers", catalogTiers).put("defaultServiceTier", JSONObject.NULL)))
                }
                check(method == "GET" && path == "/v2/connection")
                connectionQueryStarted?.complete(Unit)
                connectionReplyGate?.await()
                elapsed.addAndGet(75L)
                return JSONObject().put("observedAt", remoteObservedAt).put("validForMs", validForMs)
                    .put("state", "Online").put("pcState", pcState)
                    .also { response -> codexAuth?.let { response.put("codexAuth", it) } }
            }
            override suspend fun prepareInput(source: File, name: String): BridgeFile {
                network += "prepare $name"
                val bytes = source.readBytes()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                return BridgeFile((if (name == "SEND_TO_AGENTS.md") "a" else "b").repeat(32), name, bytes.size.toLong(), sha)
            }
            override suspend fun ensureInput(file: BridgeFile, source: File) {
                check(file.matches(source))
                network += "input ${file.name}"
            }
            override suspend fun job(id: String): JSONObject? {
                network += "job $id"
                receiverFailure?.let { throw it }
                jobLookupStarted?.complete(Unit)
                jobLookupGate?.await()
                return remote
            }
            override suspend fun submit(request: JSONObject): JSONObject {
                network += "submit"
                submissions++
                check(remote == null) { "DUPLICATE_SUBMISSION" }
                return JSONObject().put("id", request.getString("requestId")).put("state", "running")
                    .put("input", JSONObject(request.toString())).also { remote = it }
                    .let { response -> if (conflictingSubmitResponse) JSONObject(response.toString()).also {
                        it.getJSONObject("input").put("model", "unexpected-model")
                    } else response }
            }
            override suspend fun cancelJob(id: String): JSONObject {
                network += "cancel $id"
                return checkNotNull(remote).put("state", "cancelled").put("cancelRequested", 1L).put("cancelRetired", 2L)
            }
            override suspend fun receive(file: BridgeFile, destination: File) {
                destination.writeBytes(checkNotNull(receivedArchive) { "No remote work in this fixture" })
                check(file.matches(destination))
            }
            override suspend fun acknowledge(id: String, file: BridgeFile, destination: File) {
                network += "ack $id"
                check(file.matches(destination))
                ackEntered?.complete(Unit)
                ackGate?.await()
                ackFailure?.let { throw it }
            }
            override suspend fun deleteJob(id: String) {
                check(allowRetirement) { "No remote work in this fixture" }
                network += "delete $id"
                deleteFailure?.let { throw it }
                remote = null
            }
            override suspend fun discardTransfers(record: BridgeSongRecord) {
                check(allowRetirement && record.remoteRetired) { "No remote work in this fixture" }
                network += "discard ${record.task.id}"
            }
        }
        val contactedProfiles = CopyOnWriteArrayList<BridgeConnectionProfile>()
        val connections = BridgeConnectionStore(File(context.noBackupFilesDir, "bridge-profile.json")).also { it.save(connection) }
        fun repository(selectedStore: BridgeSongStore = store) = SongBridgeRepository(context, roots, importer, external, database.libraryDao(), background,
            producerReady = { true }, blockedIds = {
                if (useAuthoritativeDeletion) bindings.confirmedDeletedPackageIds(checkNotNull(roots.captureHandle())) else blocked
            },
            prepareExplicitReadd = { root, id ->
                if (useAuthoritativeDeletion) trash.prepareExplicitReadd(root, id).getOrThrow() else blocked = blocked - id
            }, store = selectedStore, connections = connections, transportFor = { profile ->
                contactedProfiles.add(profile); transport
            }, scope = scope,
            elapsedRealtime = { preparationCaptured?.invoke(); elapsed.get() }, schedule = { id, replace -> scheduled.add(id to replace) })
        val bridge = repository()
        suspend fun open() {
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            external.reconcile()
        }
        fun close() { scope.cancel(); database.close() }
    }
}
