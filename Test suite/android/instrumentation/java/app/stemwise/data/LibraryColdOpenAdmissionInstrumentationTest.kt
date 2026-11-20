package app.stemwise.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.stemwise.ui.PreparationRecoveryDialog
import app.stemwise.ui.QueueScreen
import app.stemwise.ui.projectPreparationOwnerQueueJob
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Rule

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.enrichment.EnrichmentImportCoordinator
import app.stemwise.enrichment.GoldenSongMediaFixture
import app.stemwise.enrichment.SongEnrichmentImporter
import app.stemwise.integration.MvsepAccountAccess
import app.stemwise.integration.MvsepConnectionState
import app.stemwise.integration.MvsepConnectionStatus
import app.stemwise.integration.MvsepCredential
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real SAF/Room orchestration; emulator timings are not handset qualification. */
class LibraryColdOpenAdmissionInstrumentationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aLiveToolLeasePreservesScratchWhileInstalledSongsRemainReadable() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var audio: app.stemwise.playback.AudioPlayback? = null
        var lease = -1
        try {
            fixture.open(); fixture.seed(1)
            fixture.imports.awaitStartupScratchReady()
            audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                app.stemwise.playback.AudioPlayback(fixture.context, fixture.playlist, fixture.separation, fixture.roots)
            }
            val manual = app.stemwise.work.ManualTaskCoordinator(fixture.context, fixture.separation,
                fixture.roots, fixture.cold, fixture.preferences)
            val bridge = app.stemwise.bridge.SongBridgeRepository(fixture.context, fixture.roots,
                fixture.importer, fixture.external, fixture.dao, fixture.governor, producerReady = { false },
                blockedIds = { emptySet() }, prepareExplicitReadd = { _, _ -> error("No Bridge re-add") },
                transportFor = { error("Recovery cannot contact Bridge") }, scope = bridgeScope,
                schedule = { _, _ -> error("Recovery cannot send to Bridge") })
            val coordinator = LibrarySessionCoordinator(fixture.context, fixture.roots, fixture.preferences,
                fixture.playlist, fixture.external, fixture.imports, fixture.importer, fixture.separation,
                checkNotNull(audio), fixture.cold, fixture.songWork, manual, bridge)
            val candidate = fixture.roots.validateCandidate(fixture.root.treeUri).getOrThrow()
            val scratch = File(fixture.context.noBackupFilesDir, "quarantined-input.pcm").apply {
                writeText("retained tool input", Charsets.UTF_8)
            }
            // An independent kernel lease, rather than a requested-state flag, blocks mutation.
            lease = app.stemwise.integration.OwnedToolNative.acquireLease(fixture.context.noBackupFilesDir.path)
            assertTrue(lease >= 0)
            checkNotNull(fixture.cold.start())
            withTimeout(20_000L) { fixture.cold.awaitCompletion() }
            val ready = fixture.cold.state.value as ColdOpenState.Ready
            assertTrue(ready.preparationBlocked)
            assertNotNull(ready.preparationRecovery)
            val recoveryRow = checkNotNull(app.stemwise.ui.projectPreparationRecoveryQueueJob(ready, fixture.root.authority()))
            assertEquals(app.stemwise.model.JobState.NEEDS_ATTENTION, recoveryRow.state)
            assertEquals(app.stemwise.model.QueueJobAction.RETRY, recoveryRow.action)
            assertNull(app.stemwise.ui.projectPreparationRecoveryQueueJob(ready, fixture.root.authority().copy(epoch = fixture.root.epoch + 1L)))
            assertEquals(ColdOpenProducerAdmission.RETRY, fixture.cold.producerAdmission(fixture.root))
            assertTrue(runCatching { fixture.cold.recheckPreparation(fixture.root.authority()) }
                .exceptionOrNull() is app.stemwise.playback.engine.WorkerRetirementException)
            assertEquals(ready, fixture.cold.state.value)
            assertTrue(coordinator.switchTo(candidate).exceptionOrNull() is app.stemwise.playback.engine.WorkerRetirementException)
            assertEquals(ready, fixture.cold.state.value)
            val song = fixture.playlist.verifyPlaybackSong("COLD0000000")
            assertNotNull(song.sourcePath)
            assertEquals(64, song.mediaRevision.length)
            assertEquals("retained tool input", scratch.readText(Charsets.UTF_8))
            assertTrue(app.stemwise.integration.OwnedToolNative.releaseLease(lease, fixture.context.noBackupFilesDir.path))
            lease = -1
            app.stemwise.integration.bundledToolRuntimeMutex.requireHealthy()
            var arbitrations = 0
            fixture.cold.beforePreparationArbitrationForTest = { arbitrations++ }
            withTimeout(20_000L) { coordinator.switchTo(candidate).getOrThrow() }
            val recovered = fixture.cold.state.value as ColdOpenState.Ready
            assertFalse(recovered.preparationBlocked)
            assertNull(recovered.preparationRecovery)
            assertTrue(fixture.cold.isProducerAdmissionReady(fixture.root))
            assertTrue(recovered.generation > ready.generation)
            assertEquals(1, arbitrations)
            assertEquals(song.mediaRevision, fixture.playlist.verifyPlaybackSong("COLD0000000").mediaRevision)
            assertTrue(runCatching { fixture.cold.recheckPreparation(fixture.root.authority().copy(epoch = fixture.root.epoch + 1L)) }.isFailure)
            assertEquals(recovered, fixture.cold.state.value)
            coordinator.switchTo(candidate).getOrThrow()
            assertEquals(recovered, fixture.cold.state.value)
            assertEquals(1, arbitrations)
        } finally {
            if (lease >= 0) assertTrue(app.stemwise.integration.OwnedToolNative.releaseLease(lease,
                fixture.context.noBackupFilesDir.path))
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { audio?.release() }
            withTimeout(10_000L) { bridgeScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            fixture.close()
        }
    }

    @Test fun sameProcessQuarantineCannotBeClearedByAHealthyRuntimeRecheck() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1); fixture.imports.awaitStartupScratchReady()
            val scratch = File(fixture.context.noBackupFilesDir, "preparation-active").apply { check(mkdir()) }
            val input = File(scratch, app.stemwise.integration.CANONICAL_ORIGINAL_PCM_FILE).apply { writeBytes(byteArrayOf(34, 55)) }
            val fatal = app.stemwise.playback.engine.WorkerRetirementException("ORIGINAL_PCM_RETIREMENT_FAILED")
            app.stemwise.integration.WorkerRetirementQuarantine.retain(fatal, files = listOf(scratch))
            checkNotNull(fixture.cold.start())
            withTimeout(20_000L) { fixture.cold.awaitCompletion() }
            val opened = fixture.cold.state.value
            assertTrue(opened.toString(), opened is ColdOpenState.Ready)
            assertNotNull((opened as ColdOpenState.Ready).preparationRecovery)
            assertSame(fatal, runCatching { withTimeout(20_000L) {
                fixture.cold.recheckPreparation(fixture.root.authority())
            } }.exceptionOrNull())
            assertFalse(fixture.cold.isProducerAdmissionReady(fixture.root))
            assertNotNull(fixture.playlist.verifyPlaybackSong("COLD0000000").sourcePath)
            assertArrayEquals(byteArrayOf(34, 55), input.readBytes())
        } finally { fixture.close() }
    }

    @Test fun playlistWorkerReturnsRetryOnlyForTransientSyncFailuresFromTheRealRepository() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication
        withTimeout(30_000L) { application.workerInitialization.await() }
        val fixture = Fixture()
        val services = application.services
        val replacements = linkedMapOf<String, Any>()
        fun replace(name: String, value: Any) {
            services.javaClass.getDeclaredField(name).apply { isAccessible = true }.also { field ->
                if (name !in replacements) replacements[name] = checkNotNull(field.get(services))
                field.set(services, value)
            }
        }
        try {
            fixture.open(); fixture.seed(1)
            val link = checkNotNull(app.stemwise.integration.PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890"))
            fixture.dao.upsertSelectedPlaylist(SelectedPlaylistEntity(fixture.root.libraryId, link.id,
                link.canonicalUrl, "Worker result fixture", null, null))
            fixture.preferences.activateLibraryFromRoot(fixture.root.libraryId)
            checkNotNull(fixture.cold.start())
            withTimeout(10_000L) { fixture.cold.awaitCompletion() }
            assertTrue(fixture.cold.state.value.toString(), fixture.cold.state.value is ColdOpenState.Ready)
            replace("libraryRootManager", fixture.roots)
            replace("preferences", fixture.preferences)
            replace("coldOpenCoordinator", fixture.cold)
            val before = fixture.dao.allSongs()
            for ((failure, retry) in listOf(
                IllegalStateException("TOOL_PROCESS_TIMEOUT") to true,
                java.net.UnknownHostException("fixture offline") to true,
                com.yausername.youtubedl_android.YoutubeDLException("HTTP Error 503") to true,
                com.yausername.youtubedl_android.YoutubeDLException("Video unavailable") to false,
                java.io.IOException("Could not resolve local filename") to false,
                IllegalArgumentException("invalid playlist input") to false)) {
                val attempts = java.util.concurrent.atomic.AtomicInteger()
                // Substitute only the unavailable external admission boundary. Actual sync
                // error retention, root checks and CoroutineWorker result handling stay real.
                val failingAccess = object : AppNetworkAccess {
                    override val state = MutableStateFlow(AppNetworkState(true, true, false, activeNetworkWifi = true))
                    override fun currentState(): AppNetworkState { attempts.incrementAndGet(); throw failure }
                }
                val repository = PlaylistRepository(fixture.context, fixture.database, fixture.dao,
                    fixture.preferences, fixture.engine, fixture.songWork, fixture.roots, fixture.external,
                    fixture.governor, failingAccess)
                replace("playlistRepository", repository)
                val worker = androidx.work.testing.TestListenableWorkerBuilder.from(application,
                    app.stemwise.work.PlaylistSyncWorker::class.java).setInputData(
                        androidx.work.workDataOf("library_id" to fixture.root.libraryId)).build()
                val result = withTimeout(5_000L) { worker.doWork() }
                assertEquals(failure.toString(), if (retry) androidx.work.ListenableWorker.Result.retry()
                    else androidx.work.ListenableWorker.Result.success(), result)
                assertEquals("Worker never reached the actual repository failure", 1, attempts.get())
                assertEquals(before, fixture.dao.allSongs())
                assertEquals(null, fixture.dao.selectedPlaylist(fixture.root.libraryId)?.lastSyncEpochMs)
            }
        } finally {
            replacements.forEach { (name, value) -> services.javaClass.getDeclaredField(name).apply {
                isAccessible = true }.set(services, value) }
            withTimeout(10_000L) { fixture.close() }
        }
    }

    @Test fun launcherDrawsItsOpeningShellWhileActualProviderRecoveryIsStillBlocked() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as app.stemwise.StemwiseApplication
        withTimeout(30_000L) { application.workerInitialization.await() }
        val fixture = Fixture()
        val barrierField = app.stemwise.StemwiseApplication::class.java.getDeclaredField("workerInitialization")
            .apply { isAccessible = true }
        val previousBarrier = barrierField.get(application)
        val delayedStartup = app.stemwise.work.WorkerInitializationBarrier()
        var recovery: kotlinx.coroutines.Deferred<Unit>? = null
        var activity: ActivityScenario<app.stemwise.MainActivity>? = null
        val drawn = java.util.concurrent.CountDownLatch(1)
        val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
        try {
            fixture.open(); fixture.seed(1)
            // Cold-open uses the checked six-column package snapshot, not the dashboard's
            // four-column size query. Hold its real Original verification instead.
            fixture.provider(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_BLOCK_ORIGINAL_READ)
            barrierField.set(application, delayedStartup)
            recovery = async(kotlinx.coroutines.Dispatchers.IO) {
                checkNotNull(fixture.cold.start(reconnectRoot = true))
                fixture.cold.awaitCompletion()
                delayedStartup.complete()
            }
            assertTrue(fixture.provider(GoldenQaDocumentsProvider.METHOD_AWAIT_IMPORT_READ).getBoolean("entered"))
            assertFalse("The provider delay did not retain actual startup work", checkNotNull(recovery).isCompleted)
            activity = ActivityScenario.launch(app.stemwise.MainActivity::class.java)
            activity.onActivity { host ->
                host.findViewById<android.view.View>(android.R.id.content).apply {
                    viewTreeObserver.addOnDrawListener(listener)
                    invalidate()
                }
            }
            assertTrue("The system splash held the first app frame behind provider recovery",
                drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("A drawable opening shell must not require provider completion", checkNotNull(recovery).isCompleted)
            fixture.provider(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
            withTimeout(10_000L) { checkNotNull(recovery).await() }
            assertTrue(fixture.cold.state.value.toString(), fixture.cold.state.value is ColdOpenState.Ready)
        } finally {
            fixture.provider(GoldenQaDocumentsProvider.METHOD_RELEASE_IMPORT_READ)
            barrierField.set(application, previousBarrier)
            activity?.onActivity { host -> host.findViewById<android.view.View>(android.R.id.content)
                .viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener) }
            activity?.close()
            withTimeout(10_000L) { recovery?.cancelAndJoin(); fixture.close() }
        }
    }

    @Test
    fun lateUnavailableReconciliationCannotClearSuccessfulRetryOfTheSameRoot() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val capturedFailure = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        var scan: kotlinx.coroutines.Deferred<Result<Unit>>? = null
        try {
            fixture.open(); fixture.seed(1)
            fixture.external.reconcile()
            val rows = fixture.dao.allSongs()
            fixture.roots.reportRuntimeAccessFailure(fixture.root, SecurityException("isolated root loss"))
            fixture.external.beforeReconcileFailureForTest = {
                capturedFailure.complete(Unit)
                withTimeout(15_000L) { releaseFailure.await() }
            }
            scan = async(kotlinx.coroutines.Dispatchers.IO) { runCatching { fixture.external.reconcile() } }
            withTimeout(5_000L) { capturedFailure.await() }
            fixture.cold.retry()
            withTimeout(10_000L) { fixture.cold.state.first { it is ColdOpenState.Ready } }
            fixture.cold.awaitCompletion()
            assertEquals(fixture.root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
            val recoveredSongs = fixture.external.songs.value
            val recoveredInventory = fixture.external.storageRefreshState.value
            assertEquals(rows.map { it.id }, recoveredSongs.map { it.id })
            assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
            releaseFailure.complete(Unit)
            assertTrue(withTimeout(5_000L) { scan.await() }.isFailure)
            assertTrue(fixture.roots.state.value.ready)
            assertEquals(recoveredSongs, fixture.external.songs.value)
            assertEquals(rows, fixture.dao.allSongs())
            assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
            assertEquals(recoveredInventory, fixture.external.storageRefreshState.value)
        } finally {
            releaseFailure.complete(Unit)
            fixture.external.beforeReconcileFailureForTest = null
            withTimeout(5_000L) { scan?.cancelAndJoin() }
            fixture.close()
        }
    }

    @Test
    fun unavailableMetadataRefreshReturnsCachedInventoryAndPreservesRows() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            fixture.external.reconcile()
            val rows = fixture.dao.allSongs()
            val inventory = fixture.external.storageRefreshState.value.inventory
            fixture.roots.reportRuntimeAccessFailure(fixture.root, SecurityException("isolated metadata root loss"))
            withTimeout(5_000L) { fixture.external.refreshStorageInventory() }
            assertFalse(fixture.roots.state.value.ready)
            assertEquals(LibraryProjectionAvailability.UNAVAILABLE, fixture.external.projectionAvailability.value)
            assertEquals(LibraryInventoryAvailability.UNAVAILABLE, fixture.external.storageRefreshState.value.availability)
            assertEquals(inventory, fixture.external.storageRefreshState.value.inventory)
            assertEquals(rows, fixture.dao.allSongs())
        } finally { fixture.close() }
    }

    @Test
    fun lateScheduledMetadataFailureCannotClearSuccessfulRetryOfTheSameRoot() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val delayedOwner = CompletableDeferred<kotlinx.coroutines.Job>()
        val capturedFailure = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        try {
            fixture.open(); fixture.seed(1)
            fixture.external.reconcile()
            val rows = fixture.dao.allSongs()
            fixture.external.beforeStorageRootCaptureForTest = {
                if (delayedOwner.complete(checkNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]))) {
                    fixture.roots.reportRuntimeAccessFailure(fixture.root, SecurityException("isolated scheduled root loss"))
                }
            }
            fixture.external.beforeStorageRootFailureForTest = {
                capturedFailure.complete(Unit)
                withTimeout(30_000L) { releaseFailure.await() }
            }
            // Use the repository's normal package-refresh scheduling boundary.
            fixture.external.reconcilePackage(fixture.root, "COLD0000000")
            withTimeout(10_000L) { capturedFailure.await() }
            val owner = withTimeout(5_000L) { delayedOwner.await() }
            assertTrue(owner.isActive)
            fixture.cold.retry()
            withTimeout(10_000L) { fixture.cold.state.first { it is ColdOpenState.Ready } }
            withTimeout(10_000L) { fixture.cold.awaitCompletion() }
            assertEquals(fixture.root.authority(), checkNotNull(fixture.roots.captureHandle()).authority())
            val recoveredSongs = fixture.external.songs.value
            val recoveredInventory = fixture.external.storageRefreshState.value
            assertEquals(rows.map { it.id }, recoveredSongs.map { it.id })
            assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
            releaseFailure.complete(Unit)
            withTimeout(5_000L) { owner.join() }
            assertFalse(owner.isCancelled)
            assertTrue(fixture.roots.state.value.ready)
            assertEquals(recoveredSongs, fixture.external.songs.value)
            assertEquals(rows, fixture.dao.allSongs())
            assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
            assertEquals(recoveredInventory, fixture.external.storageRefreshState.value)
        } finally {
            releaseFailure.complete(Unit)
            fixture.external.beforeStorageRootCaptureForTest = null
            fixture.external.beforeStorageRootFailureForTest = null
            withTimeout(5_000L) {
                if (delayedOwner.isCompleted) delayedOwner.await().cancelAndJoin()
            }
            fixture.close()
        }
    }

    @Test
    fun providerInvalidationDuringJoinedScanRetainsSongsAndJobsUntilNewestGenerationVerifies() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var scan: kotlinx.coroutines.Deferred<Unit>? = null
        var joining: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            fixture.open(); fixture.seed(2)
            fixture.external.reconcile()
            val ids = fixture.dao.allSongs().map { it.id }.toSet()
            val jobs = ids.mapIndexed { index, id ->
                ProcessingJobEntity("notify-job-$index", id, sourceVersionId = null,
                    kind = "SOURCE_SELECTION", provider = null, modelId = null, state = "CREATED",
                    remoteJobId = null, attempts = 0, nextAttemptAtEpochMs = null,
                    lastErrorCode = null, lastErrorMessage = null, createdAtEpochMs = 1L,
                    updatedAtEpochMs = 1L, libraryId = fixture.root.libraryId)
            }
            jobs.forEach { fixture.dao.upsertJob(it) }
            var turns = 0
            var starts = 0
            var obsoleteMetrics: Bundle? = null
            fixture.external.canonicalScanStartedForTest = {
                if (++starts == 2) obsoleteMetrics = fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
            }
            fixture.external.canonicalPackageVerifiedForTest = {
                turns++
                if (reached.complete(Unit)) withTimeout(10_000L) { release.await() }
            }
            scan = async(kotlinx.coroutines.Dispatchers.IO) { fixture.external.reconcile() }
            withTimeout(10_000L) { reached.await() }
            fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            fixture.external.invalidateProviderProjection()
            assertEquals(LibraryProjectionAvailability.UNAVAILABLE, fixture.external.projectionAvailability.value)
            joining = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { fixture.external.reconcile() }
            release.complete(Unit)
            withTimeout(30_000L) { scan.await(); joining.await() }
            assertEquals("Only the first old-generation package and two replacement packages may finish", 3, turns)
            assertEquals("An obsolete scan opened more package bytes", 0L, checkNotNull(obsoleteMetrics).getLong("openDocumentCalls"))
            assertEquals("An obsolete scan traversed later packages or storage", 0L, checkNotNull(obsoleteMetrics).getLong("queryChildCalls"))
            assertEquals(ids, fixture.external.songs.value.map { it.id }.toSet())
            assertEquals(ids, fixture.dao.allSongs().map { it.id }.toSet())
            jobs.forEach { assertEquals(it, fixture.dao.findJobById(it.id)) }
            assertEquals(LibraryProjectionAvailability.VERIFIED, fixture.external.projectionAvailability.value)
        } finally {
            release.complete(Unit)
            fixture.external.canonicalPackageVerifiedForTest = null
            fixture.external.canonicalScanStartedForTest = null
            scan?.cancel(); joining?.cancel()
            withTimeout(10_000L) { scan?.join(); joining?.join() }
            fixture.close()
        }
    }

    @Test
    fun releasedCanonicalTurnAllowsRealSelectionAndRejectsLateInventoryAfterRootEpochChange() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val songCount = 128
        val scanTimeoutMs = songCount * 1_000L
        val songIds = (0 until songCount).map { "COLD" + it.toString().padStart(7, '0') }
        for (changeRoot in listOf(false, true)) {
            val fixture = Fixture()
            val reached = CompletableDeferred<String>()
            val release = CompletableDeferred<Unit>()
            var scan: kotlinx.coroutines.Deferred<Result<Unit>>? = null
            try {
                fixture.open(); fixture.seed(songCount)
                withTimeout(scanTimeoutMs) { fixture.external.reconcile() }
                fixture.external.restoreInventorySnapshot(fixture.root.libraryId)
                val priorInventory = fixture.external.storageRefreshState.value.inventory
                // Keep committed rows for preservation checks, but force real verification this turn.
                songIds.forEach { fixture.dao.deleteAssetObservations(it) }
                assertTrue(fixture.dao.assetObservationsForPackages(songIds).isEmpty())
                val originalBytes = songIds.sumOf {
                    File(fixture.providerRoot, "$it/${SelectedLibraryContract.ORIGINAL}").length()
                }
                fixture.provider(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
                fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                fixture.external.canonicalPackageVerifiedForTest = { id ->
                    if (reached.complete(id)) release.await()
                }
                scan = async(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { fixture.external.reconcile() }
                }
                val scannedId = withTimeout(15_000L) { reached.await() }
                // The scan is still held before selection; this fixture contains no optional media.
                val scanReads = fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
                val firstOriginalBytes = File(fixture.providerRoot,
                    "$scannedId/${SelectedLibraryContract.ORIGINAL}").length()
                assertTrue(firstOriginalBytes > 0L)
                assertTrue("Uncached scan did not read Original bytes before selection",
                    scanReads.getLong("readMediaBytes") >= firstOriginalBytes)
                val selected = withTimeout(3_000L) {
                    fixture.playlist.verifyPlaybackSong(songIds.first { it != scannedId })
                }
                assertEquals(64, selected.mediaRevision.length)
                assertNotNull(selected.sourcePath)
                val healthyRows = fixture.dao.allSongs()
                val selectedSource = fixture.dao.currentSource(selected.id)
                var rowsAfterRootChange = healthyRows
                if (changeRoot) {
                    val oldRoot = fixture.root
                    File(fixture.providerRoot, "epoch-replacement").apply { check(mkdir()) }
                    val otherTree = android.provider.DocumentsContract.buildTreeDocumentUri(
                        GoldenQaDocumentsProvider.AUTHORITY, "root/epoch-replacement")
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    fixture.target.grantUriPermission(fixture.target.packageName, otherTree, flags)
                    try {
                        // Reconnecting the same tree preserves its epoch; a real A→B→A selection advances it.
                        fixture.roots.reportRuntimeAccessFailure(oldRoot, SecurityException("isolated root loss"))
                        assertEquals("Root access loss discarded committed healthy rows", healthyRows, fixture.dao.allSongs())
                        fixture.roots.commitValidatedCandidateForDevelopment(
                            fixture.roots.validateCandidate(otherTree).getOrThrow()).getOrThrow()
                        val intermediate = checkNotNull(fixture.roots.captureHandle())
                        fixture.roots.reportRuntimeAccessFailure(intermediate, SecurityException("return to original root"))
                        fixture.roots.commitValidatedCandidateForDevelopment(
                            fixture.roots.validateCandidate(oldRoot.treeUri).getOrThrow()).getOrThrow()
                    } finally { fixture.target.revokeUriPermission(otherTree, flags) }
                    fixture.root = checkNotNull(fixture.roots.captureHandle())
                    assertEquals(oldRoot.libraryId, fixture.root.libraryId)
                    assertEquals(oldRoot.treeUri, fixture.root.treeUri)
                    assertTrue(fixture.root.epoch > oldRoot.epoch)
                    rowsAfterRootChange = fixture.dao.allSongs()
                    assertTrue("Root selection did not clear the departed projection", rowsAfterRootChange.isEmpty())
                }
                release.complete(Unit)
                val result = withTimeout(scanTimeoutMs) { scan.await() }
                if (changeRoot) {
                    assertTrue("Old root scan published after epoch change", result.isFailure)
                    assertEquals("Old scan republished rows across root selection", rowsAfterRootChange, fixture.dao.allSongs())
                    fixture.external.restoreInventorySnapshot(fixture.root.libraryId)
                    assertEquals(priorInventory.lastCheckedEpochMs,
                        fixture.external.storageRefreshState.value.inventory.lastCheckedEpochMs)
                } else {
                    result.getOrThrow()
                    assertEquals(songCount, fixture.external.songs.value.size)
                    assertEquals(songIds.toSet(), fixture.external.songs.value.map { it.id }.toSet())
                    assertTrue("Complete uncached scan did not read the library's Original bytes",
                        fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
                            .getLong("readMediaBytes") >= originalBytes)
                    assertEquals(selectedSource, fixture.dao.currentSource(selected.id))
                }
            } finally {
                release.complete(Unit)
                fixture.external.canonicalPackageVerifiedForTest = null
                scan?.cancel()
                withTimeout(10_000L) { scan?.join() }
                withTimeout(20_000L) { fixture.close() }
            }
        }
    }

    @Test
    fun twoHealthySourceJournalsRecoverWhileOneCorruptAuthorityRetainsBytesAndBlocksOnlyPreparation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(3)
            val plan = app.stemwise.integration.MvsepSourcePlanner.plan(listOf("vocals"), listOf(
                app.stemwise.integration.MvsepAlgorithm(63, "Fixture",
                    listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), 1.0, 0)))
            val accepted = (0..1).map { index ->
                val id = "COLD" + index.toString().padStart(7, '0')
                val source = requireNotNull(fixture.dao.currentSource(id))
                val created = app.stemwise.integration.createPortableSourceJobAuthority(fixture.root.libraryId,
                    id, source.id, source.sha256, plan, 1L)
                val submitting = app.stemwise.integration.transitionPortableSourceJob(created,
                    app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION, 2L)
                app.stemwise.integration.transitionPortableSourceJob(submitting,
                    app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, 3L, "accepted-$index")
            }
            suspend fun journal(id: String, transaction: String, body: String) {
                fixture.mutations.withPackageMutation(fixture.root, id, "SOURCE_JOB_AUTHORITY_V1", transaction,
                    requireExactRoot = { fixture.roots.requireSameLibrary(fixture.root) }) {
                    it.write("AUTHORITY_WRITING", org.json.JSONObject().put("authorityJson", body))
                }
            }
            accepted.forEach { journal(it.videoId, "source-${it.jobId}", it.toBytes().toString(Charsets.UTF_8)) }
            journal("COLD0000002", "source-corrupt", "{")
            val corrupt = File(fixture.context.filesDir, "operation-journals/source-corrupt.json")
            val before = corrupt.readBytes()
            checkNotNull(fixture.cold.start())
            withTimeout(20_000L) { fixture.cold.awaitCompletion() }
            val ready = fixture.cold.state.value as ColdOpenState.Ready
            assertTrue(ready.preparationBlocked)
            assertEquals(ColdOpenProducerAdmission.RETRY, fixture.cold.producerAdmission(fixture.root))
            val blocked = fixture.playlist.preparationOwner.value as PreparationOwnerDecision.Blocked
            assertEquals(listOf("COLD0000002"), blocked.recoverySongIds)
            accepted.forEach { authority ->
                val recovered = app.stemwise.integration.parsePortableSourceJobAuthority(File(fixture.providerRoot,
                    "${authority.videoId}/${SelectedLibraryContract.SOURCE_JOB_AUTHORITY}").readBytes())
                assertEquals(authority.currentRemoteJobId, recovered.currentRemoteJobId)
                assertEquals(authority.currentRemoteJobId, fixture.dao.findJobById(authority.jobId)?.remoteJobId)
                val song = fixture.playlist.verifyPlaybackSong(authority.videoId)
                assertEquals(64, song.mediaRevision.length)
                assertNotNull(song.sourcePath)
            }
            assertArrayEquals(before, corrupt.readBytes())
            val job = checkNotNull(projectPreparationOwnerQueueJob(blocked, fixture.root.authority(), emptyMap()))
            val showRecovery = mutableStateOf(false)
            val recoveryReady = CompletableDeferred<Unit>()
            fixture.cold.setReadyListener { recoveryReady.complete(Unit) }
            val playlistSettings = fixture.preferences.playlist.value
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { StemwiseTheme {
                    QueueScreen(onImportEnrichment = {}, playlistSettings = playlistSettings,
                        syncStatus = PlaylistSyncStatus(), onSync = { false },
                        bridgeState = app.stemwise.bridge.BridgeUiState(), bridgeTasks = emptyList(),
                        onOpenAddSong = {}, onReviewDraft = {}, onRetryBridgeTask = { error("No retry") },
                        onForgetBridgeTask = { error("No discard") }, jobs = listOf(job),
                        onRetryJob = { selected ->
                            assertEquals(job, selected)
                            showRecovery.value = true
                        })
                    if (showRecovery.value) PreparationRecoveryDialog(blocked.recoverySongIds,
                        onDismiss = { showRecovery.value = false }, onRetry = {
                            showRecovery.value = false
                            fixture.cold.retry()
                        })
                } } }
                compose.onNodeWithText(job.actionLabel!!).performScrollTo().performClick()
                compose.onNodeWithText(fixture.context.getString(app.stemwise.R.string.preparation_recovery_title)).assertExists()
                compose.onNodeWithText(fixture.context.getString(app.stemwise.R.string.preparation_retry_recovery)).performClick()
                withTimeout(20_000L) { recoveryReady.await() }
                withTimeout(20_000L) { fixture.cold.awaitCompletion() }
                assertTrue((fixture.cold.state.value as ColdOpenState.Ready).preparationBlocked)
                assertEquals(listOf("COLD0000002"),
                    (fixture.playlist.preparationOwner.value as PreparationOwnerDecision.Blocked).recoverySongIds)
                assertArrayEquals(before, corrupt.readBytes())
                accepted.forEach { assertEquals(it.currentRemoteJobId, fixture.dao.findJobById(it.jobId)?.remoteJobId) }
            }
        } finally { fixture.close() }
    }

    @Test
    fun cancellationAndChangedStreamBytesPreserveThePriorPublishedPackage() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (cancel in listOf(true, false)) {
            val fixture = Fixture()
            var operation: kotlinx.coroutines.Deferred<*>? = null
            try {
                fixture.open(); fixture.seed(1)
                val id = "COLD0000000"
                val folder = File(fixture.providerRoot, id)
                val media = CurrentSourcePublicationFixture.load(fixture.context, id)
                File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(media.original)
                seedCompleteOpusMetadata(folder, id, media.original)
                media.parts.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
                File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).writeBytes(media.manifest.toBytes())
                assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
                assertEquals(media.manifest.sourceSha256, fixture.dao.currentSource(id)?.sha256)
                val prior = folder.listFiles()!!.associate { it.name to it.readBytes() }
                val bass = media.manifest.lanes.first { it.sourceId == "bass" }
                val input = File(fixture.context.cacheDir, "candidate.flac").apply {
                    writeBytes(media.parts.getValue(bass.fileName))
                }
                assertTrue("Fixture must cross the real 128 KiB copy boundary", input.length() > 128 * 1024)
                var injected = false
                fixture.publisher.copyProgressForTest = {
                    if (!injected) {
                        injected = true
                        if (cancel) operation!!.cancel()
                        else java.io.RandomAccessFile(input, "rw").use { stream ->
                            stream.seek(stream.length() - 1L)
                            val last = stream.readByte().toInt()
                            stream.seek(stream.length() - 1L); stream.writeByte(last xor 1)
                        }
                    }
                }
                val attempt = async(kotlinx.coroutines.Dispatchers.IO,
                    start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    runCatching { fixture.publisher.publishSourcePart(fixture.root, id, "replacement-part-1.flac", input,
                        bass.sha256, UUID.randomUUID().toString(), media.manifest.sourceSha256,
                        media.manifest.audibleFrames).getOrThrow() }
                }
                operation = attempt
                attempt.start()
                val failed = runCatching { withTimeout(15_000L) { attempt.await() } }
                    .getOrElse { Result.failure(it) }
                assertTrue("Copy fault was not reached: ${failed.exceptionOrNull()}", injected)
                if (cancel) assertTrue(failed.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                else assertEquals("ASSET_HASH_MISMATCH", failed.exceptionOrNull()?.message)
                prior.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
                assertTrue(input.isFile)
                assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            } finally {
                fixture.publisher.copyProgressForTest = null
                operation?.cancel()
                withTimeout(10_000L) { operation?.join() }
                fixture.close()
            }
        }
    }

    @Test
    fun storageRefreshAndCanonicalReadbackCountForeignDirectBytesAndReportUnmeasuredFolders() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open()
            fixture.seed(1)
            val songDirectory = File(fixture.providerRoot, "COLD0000000")
            File(fixture.providerRoot, "foreign.bin").writeBytes(ByteArray(11))
            File(songDirectory, "foreign.bin").writeBytes(ByteArray(7))
            File(fixture.providerRoot, "foreign-folder").apply { check(mkdir()) }
                .resolve("unmeasured.bin").writeBytes(ByteArray(19))
            File(songDirectory, "nested").apply { check(mkdir()) }
                .resolve("unmeasured.bin").writeBytes(ByteArray(23))
            fixture.external.reconcile()
            // Consume the canonical calculation through its real persisted inventory reader.
            fixture.external.restoreInventorySnapshot(fixture.root.libraryId)
            val canonical = fixture.external.storageRefreshState.value.inventory
            fixture.external.refreshStorageInventory()
            val refreshed = fixture.external.storageRefreshState.value.inventory
            val directFiles = fixture.providerRoot.listFiles()!!.filter(File::isFile) +
                songDirectory.listFiles()!!.filter(File::isFile)
            val expectedBytes = directFiles.sumOf(File::length)
            assertEquals(expectedBytes, canonical.actualLibraryBytes)
            assertEquals(expectedBytes, refreshed.actualLibraryBytes)
            assertEquals(2, canonical.unreadableDirectories)
            assertEquals(2, refreshed.unreadableDirectories)
            assertEquals(0, refreshed.unknownSizeFiles)
            fixture.provider(GoldenQaDocumentsProvider.METHOD_SET_FAULT, GoldenQaDocumentsProvider.FAULT_UNKNOWN_SIZE)
            val unknown = LibraryStorageScanner(fixture.context, fixture.governor)
                .scanRootStorage(fixture.root.rootDocument)
            assertEquals(0L, unknown.actualBytes)
            assertEquals(directFiles.size, unknown.unknownSizeFiles)
            assertEquals(2, unknown.unreadableDirectories)
        } finally { fixture.close() }
    }

    @Test
    fun realColdOpenDemotesProducerAdmissionBeforeDispatchAndBeforeCancellationJoin() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val ready = java.util.concurrent.CountDownLatch(1)
        val releaseReady = java.util.concurrent.CountDownLatch(1)
        var holder: kotlinx.coroutines.Job? = null
        var cancellation: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            fixture.open()
            holder = launch(kotlinx.coroutines.Dispatchers.IO) {
                fixture.mutations.withRootMutation {
                    mutationEntered.complete(Unit)
                    withTimeout(5_000L) { releaseMutation.await() }
                }
            }
            withTimeout(5_000L) { mutationEntered.await() }
            fixture.cold.setReadyListener {
                ready.countDown()
                check(releaseReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            checkNotNull(fixture.cold.start())
            assertTrue(fixture.cold.state.value is ColdOpenState.Recovering)
            assertFalse(fixture.cold.isProducerAdmissionReady(fixture.root))
            releaseMutation.complete(Unit)
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(fixture.cold.isProducerAdmissionReady(fixture.root))
            cancellation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { fixture.cold.cancelAndJoin() }
            assertFalse("Cancellation did not retain its running owner", cancellation.isCompleted)
            assertTrue(fixture.cold.state.value is ColdOpenState.Idle)
            assertFalse(fixture.cold.isProducerAdmissionReady(fixture.root))
            releaseReady.countDown()
            withTimeout(5_000L) { cancellation.await() }
        } finally {
            releaseMutation.complete(Unit)
            releaseReady.countDown()
            holder?.cancel()
            withTimeout(5_000L) { holder?.join(); cancellation?.join() }
            fixture.close()
        }
    }

    @Test
    fun completionWaiterStaysWithItsCapturedSessionWhileAReplacementRuns() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        val firstReady = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val secondReady = java.util.concurrent.CountDownLatch(1)
        val releaseSecond = java.util.concurrent.CountDownLatch(1)
        val turns = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                turns.add(block)
            }
        }
        var completed = false
        var waiter: kotlinx.coroutines.Job? = null
        try {
            fixture.open()
            fixture.seed(1)
            val sessions = java.util.concurrent.atomic.AtomicInteger()
            fixture.cold.setReadyListener {
                if (sessions.incrementAndGet() == 1) {
                    firstReady.countDown()
                    check(releaseFirst.await(10, java.util.concurrent.TimeUnit.SECONDS))
                } else {
                    secondReady.countDown()
                    check(releaseSecond.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            checkNotNull(fixture.cold.start())
            assertTrue(firstReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
            waiter = launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                fixture.cold.awaitCompletion()
                completed = true
            }
            assertFalse(completed)
            releaseFirst.countDown()
            withTimeout(10_000L) {
                while (fixture.cold.start() == null) kotlinx.coroutines.delay(10L)
            }
            assertTrue(secondReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
            withTimeout(5_000L) {
                while (turns.isEmpty()) kotlinx.coroutines.delay(10L)
            }
            while (true) (turns.poll() ?: break).run()
            assertTrue("Old completion waiter followed the replacement session", completed)
            assertEquals(1L, releaseSecond.count)
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            waiter?.cancel()
            while (true) (turns.poll() ?: break).run()
            fixture.close()
        }
    }

    @Test
    fun coldOpenRevealsRootBoundCachedRowsBeforeMediaReadsAndSelectionVerifiesBytes() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        data class ReadySnapshot(val metrics: Bundle, val elapsedMs: Long, val liveSongs: Int,
            val availability: LibraryProjectionAvailability)
        for (count in listOf(1, 100, 1000)) {
            val fixture = Fixture()
            val releaseReady = java.util.concurrent.CountDownLatch(1)
            val listenerRetired = CompletableDeferred<Boolean>()
            try {
                fixture.open()
                fixture.seed(count)
                val seededRows = fixture.dao.allSongs()
                fixture.provider(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
                fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                val visible = CompletableDeferred<ReadySnapshot>()
                val started = SystemClock.elapsedRealtime()
                fixture.cold.setReadyListener {
                    visible.complete(ReadySnapshot(fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS),
                        SystemClock.elapsedRealtime() - started, fixture.external.songs.value.size,
                        fixture.external.projectionAvailability.value))
                    listenerRetired.complete(releaseReady.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
                checkNotNull(fixture.cold.start())
                val ready = withTimeout(30_000L) { visible.await() }
                assertTrue(fixture.cold.state.value is ColdOpenState.Ready)
                assertEquals(count, ready.liveSongs)
                assertEquals(LibraryProjectionAvailability.PROJECTED, ready.availability)
                assertTrue(fixture.external.songs.value.all { it.sourcePath == null && it.sourceHash == null &&
                    it.audioRevision.isEmpty() && it.mediaRevision.isEmpty() && !it.hasStems })
                assertTrue(fixture.external.playbackSnapshot().snapshotReady)
                assertEquals(count, seededRows.size)
                assertEquals(seededRows, fixture.dao.allSongs())
                assertEquals("$count songs read media before Ready", 0L, ready.metrics.getLong("readMediaBytes"))
                android.util.Log.i("StemwiseColdOpen", "songs=$count firstReadyMs=${ready.elapsedMs} " +
                    "actualReadBytes=${ready.metrics.getLong("readDocumentBytes")} mediaReadBytes=${ready.metrics.getLong("readMediaBytes")}")
                releaseReady.countDown()
                assertTrue("Ready observation handshake expired", withTimeout(5_000L) { listenerRetired.await() })
                fixture.cold.cancelAndJoin()
                val selected = fixture.playlist.verifyPlaybackSong("COLD0000000")
                assertNotNull(selected.sourcePath)
                assertTrue("Selected package verification did not read its Original",
                    fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("readMediaBytes") > 0L)
                assertEquals("Unchanged verification republished a different Song", selected,
                    fixture.playlist.verifyPlaybackSong(selected.id))
                val priorSource = fixture.dao.currentSource(selected.id)
                val original = File(fixture.providerRoot, "${selected.id}/original.opus")
                original.writeBytes(byteArrayOf(0, 1, 2, 3))
                val rejected = runCatching { fixture.playlist.verifyPlaybackSong(selected.id) }
                assertTrue("Persisted sourcePath authorized corrupt audio", rejected.isFailure)
                assertEquals(priorSource, fixture.dao.currentSource(selected.id))
                assertArrayEquals(byteArrayOf(0, 1, 2, 3), original.readBytes())
            } finally { releaseReady.countDown(); fixture.close() }
        }
    }

    @Test
    fun requiredMetadataDamageRejectsPlaybackAndPreservesJobsAndForeignFiles() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val folder = File(fixture.providerRoot, id)
            val note = File(folder, "notes.txt").apply { writeText("owner note", Charsets.UTF_8) }
            val nested = File(folder, "notes").apply { check(mkdir()) }
            File(nested, "keep.txt").writeText("keep", Charsets.UTF_8)
            File(fixture.providerRoot, ".DS_Store").writeText("metadata", Charsets.UTF_8)
            assertEquals(64, fixture.external.verifyPlaybackSong(fixture.root, id).mediaRevision.length)
            File(folder, "song.json").writeText("broken required JSON", Charsets.UTF_8)
            File(folder, "cover.png").writeBytes(byteArrayOf(1))
            val oldSource = fixture.dao.currentSource(id)!!
            val job = ProcessingJobEntity("optional-peer", id, oldSource.id, "SOURCE_SELECTION", "MVSEP",
                "fixture-plan", "PROCESSING", "accepted-remote", 1, null, null, null, 1, 1,
                fixture.root.libraryId)
            fixture.dao.upsertJob(job)
            assertTrue(fixture.playlist.runCatching { verifyPlaybackSong(id) }.isFailure)
            assertEquals(oldSource, fixture.dao.currentSource(id))
            assertTrue(fixture.external.problems.value.any { it.packageId == id })
            assertEquals(job, fixture.dao.findJobById(job.id))
            val sourceId = "youtube-video:$id"
            val blockedBefore = fixture.bindings.blockedSourceIds(fixture.root)
            val bindingBefore = fixture.dao.sourceBinding(fixture.root.libraryId, sourceId)
            assertFalse(sourceId in blockedBefore)
            fun providerBytes() = fixture.providerRoot.walkTopDown().filter(File::isFile)
                .associate { it.relativeTo(fixture.providerRoot).invariantSeparatorsPath to it.readBytes().toList() }
            val bytesBefore = providerBytes()
            val refused = fixture.trash.deletePermanently(fixture.root, id)
            assertEquals("PACKAGE_DIRECTORY_CLEANUP_REFUSED", refused.exceptionOrNull()?.message)
            val records = checkedAtomicJournalRecords(File(fixture.context.filesDir, "operation-journals"), "INVALID")
            val receipt = requireAtomicJournalEnvelope(records.single(), "INVALID")
            assertEquals("DELETE_PERMANENT", receipt.operation)
            assertEquals(id, receipt.packageId)
            assertEquals("PREPARED", receipt.state)
            assertEquals(0, receipt.details.length())
            assertEquals(bytesBefore, providerBytes())
            assertEquals(job, fixture.dao.findJobById(job.id))
            assertEquals(oldSource, fixture.dao.currentSource(id))
            assertEquals(bindingBefore, fixture.dao.sourceBinding(fixture.root.libraryId, sourceId))
            assertEquals(blockedBefore, fixture.bindings.blockedSourceIds(fixture.root))
            fixture.trash.recoverInterruptedDeletes(fixture.root)
            assertFalse(records.single().file.exists())
            assertEquals(bytesBefore, providerBytes())
            assertEquals(job, fixture.dao.findJobById(job.id))
            assertEquals(oldSource, fixture.dao.currentSource(id))
            assertEquals(bindingBefore, fixture.dao.sourceBinding(fixture.root.libraryId, sourceId))
            assertEquals(blockedBefore, fixture.bindings.blockedSourceIds(fixture.root))
            assertEquals("owner note", note.readText(Charsets.UTF_8))
            assertEquals("keep", File(nested, "keep.txt").readText(Charsets.UTF_8))
            assertTrue(File(folder, "original.opus").exists())
            assertTrue(File(fixture.providerRoot, ".DS_Store").exists())
        } finally { fixture.close() }
    }

    @Test
    fun replacementPublicationKeepsActivePartsUntilValidatedSwapAndPreservesThemOnCancellation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val folder = File(fixture.providerRoot, id)
            val media = CurrentSourcePublicationFixture.load(fixture.context, id)
            File(folder, "original.opus").writeBytes(media.original)
            seedCompleteOpusMetadata(folder, id, media.original)
            media.parts.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
            val canonical = File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST)
            canonical.writeBytes(media.manifest.toBytes())
            val before = folder.listFiles()!!.associate { it.name to it.readBytes() }
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            assertEquals(media.manifest.sourceSha256, fixture.dao.currentSource(id)?.sha256)
            val bass = media.manifest.lanes.first { it.sourceId == "bass" }
            val oldRemaining = media.manifest.lanes.last()
            val replacementBass = bass.copy(fileName = "replacement-part-1.flac")
            val replacementRemaining = oldRemaining.copy(fileName = "replacement-remaining.wv")
            val replacement = CustomSourceManifest(id, media.manifest.sourceSha256, "f".repeat(64),
                listOf("bass"), media.manifest.audibleFrames, listOf(replacementBass, replacementRemaining))
            val owner = UUID.randomUUID().toString()
            val privatePart = File(fixture.context.cacheDir, "candidate.flac").apply { writeBytes(media.parts.getValue(bass.fileName)) }
            fixture.publisher.copyProgressForTest = { throw kotlinx.coroutines.CancellationException("injected cancellation") }
            val cancelled = runCatching { fixture.publisher.publishSourcePart(fixture.root, id, replacementBass.fileName,
                privatePart, replacementBass.sha256, owner, replacement.sourceSha256, replacement.audibleFrames).getOrThrow() }
            assertTrue("Publication stopped before injected cancellation: ${cancelled.exceptionOrNull()}",
                cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            fixture.publisher.copyProgressForTest = null
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
            fixture.publisher.recoverPublishedSourcePart(fixture.root, id, replacementBass.fileName, owner)
            if (privatePart.exists()) check(privatePart.delete())
            for (lane in replacement.lanes) {
                val old = if (lane.sourceId == "remaining") oldRemaining else bass
                val private = File(fixture.context.cacheDir, "candidate.${if (lane.sourceId == "remaining") "wv" else "flac"}")
                    .apply { writeBytes(media.parts.getValue(old.fileName)) }
                fixture.publisher.publishSourcePart(fixture.root, id, lane.fileName, private, lane.sha256,
                    owner, replacement.sourceSha256, replacement.audibleFrames).getOrThrow()
            }
            assertArrayEquals(before.getValue(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST), canonical.readBytes())
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            val invalid = replacement.copy(lanes = replacement.lanes.map {
                if (it.sourceId == "bass") it.copy(sha256 = "0".repeat(64)) else it
            })
            assertTrue(fixture.publisher.publishCustomSources(fixture.root, id, invalid, owner).isFailure)
            assertArrayEquals(before.getValue(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST), canonical.readBytes())
            fixture.publisher.sourceManifestWrittenForTest = {
                File(folder, replacementBass.fileName).writeBytes(byteArrayOf(0, 1, 2, 3))
            }
            assertTrue("Original fallback approved a damaged replacement", fixture.publisher.publishCustomSources(
                fixture.root, id, replacement, owner).isFailure)
            fixture.publisher.sourceManifestWrittenForTest = null
            assertArrayEquals(before.getValue(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST), canonical.readBytes())
            assertTrue("Failed replacement did not restore the prior playable parts", fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            File(folder, replacementBass.fileName).writeBytes(media.parts.getValue(bass.fileName))
            File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST_BACKUP)
                .writeBytes(before.getValue(SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST))
            canonical.writeBytes(replacement.toBytes().copyOf(17))
            assertTrue("Interrupted manifest copy discarded the prior playable bank", fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            fixture.publisher.publishCustomSources(fixture.root, id, replacement, owner).getOrThrow()
            assertEquals(listOf("bass"), parseCustomSourceManifest(canonical.readBytes(), id, replacement.sourceSha256).selectedSourceIds)
            assertTrue(replacement.lanes.all { File(folder, it.fileName).isFile })
            assertTrue(media.manifest.lanes.none { File(folder, it.fileName).exists() })
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
        } finally { fixture.close() }
    }

    @Test
    fun importedPreparedPartsUseInactiveOwnershipAndUnknownSubmissionCannotReplaceThem() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val folder = File(fixture.providerRoot, id)
            val media = CurrentSourcePublicationFixture.load(fixture.context, id)
            File(folder, "original.opus").writeBytes(media.original)
            seedCompleteOpusMetadata(folder, id, media.original)
            media.parts.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
            File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).writeBytes(media.manifest.toBytes())
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            assertEquals(media.manifest.sourceSha256, fixture.dao.currentSource(id)?.sha256)
            val before = folder.listFiles()!!.associate { it.name to it.readBytes() }
            val client = object : app.stemwise.integration.MvsepServiceClient {
                override suspend fun sourceAlgorithms() = listOf(app.stemwise.integration.MvsepAlgorithm(63,
                    "Fixture", listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), 1.0, 0))
                override suspend fun disablePremium(token: String): Unit = error("Admission must not submit")
                override suspend fun status(remoteJobId: String): app.stemwise.integration.MvsepRemoteStatus = error("Admission must not poll")
                override suspend fun download(remote: app.stemwise.integration.MvsepRemoteFile, target: File): Unit = error("Admission must not download")
            }
            val repository = SeparationRepository(fixture.context, fixture.dao, fixture.account, fixture.roots,
                fixture.mutations, fixture.songWork, fixture.external, fixture.publisher, fixture.governor,
                fixture.engine, fixture.network, client)
            val handoff = repository.queueSources(fixture.root, id, listOf("bass")).getOrThrow()
            assertTrue(handoff.requiresWorker)
            val pendingFile = File(folder, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY)
            val pending = app.stemwise.integration.parsePortableSourceJobAuthority(pendingFile.readBytes())
            assertEquals(2L, pending.generation)
            assertEquals(handoff.jobId, pending.jobId)
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
            val entered = app.stemwise.integration.transitionPortableSourceJob(pending,
                app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION, pending.updatedAtEpochMs + 1)
            val unknown = app.stemwise.integration.transitionPortableSourceJob(entered,
                app.stemwise.integration.SourceJobTransition.MARK_SUBMISSION_UNKNOWN, entered.updatedAtEpochMs + 1)
            fixture.mutations.withRootMutation {
                fixture.roots.writeExactSongSidecar(fixture.root, id, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY,
                    "application/json", unknown.toBytes(), app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
                fixture.dao.upsertJob(app.stemwise.integration.projectSourceJob(unknown))
            }
            assertTrue(repository.queueSources(fixture.root, id, listOf("piano")).isFailure)
            checkNotNull(fixture.cold.start())
            withTimeout(20_000L) { fixture.cold.awaitCompletion() }
            val ready = fixture.cold.state.value as ColdOpenState.Ready
            assertFalse(ready.preparationBlocked)
            assertEquals(ColdOpenProducerAdmission.RUN, fixture.cold.producerAdmission(fixture.root))
            val quarantined = fixture.playlist.preparationOwner.value as PreparationOwnerDecision.Blocked
            assertEquals(unknown.jobId, quarantined.recoverableJobId)
            assertEquals("PREPARATION_SUBMISSION_UNKNOWN", quarantined.code)
            assertFalse(fixture.playlist.preparationOwnerAllowsAutomaticEnqueue(fixture.root))
            assertEquals(unknown, app.stemwise.integration.parsePortableSourceJobAuthority(pendingFile.readBytes()))
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
        } finally { fixture.close() }
    }

    @Test
    fun coldOpenRecoversPreparedEnrichmentJournalAndPreservesUnrecoverableCommitBeforeReady() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (recoverable in listOf(true, false)) {
            val fixture = Fixture()
            try {
                fixture.open(); fixture.seed(1)
                val id = "COLD0000000"
                val transactionId = UUID.randomUUID().toString()
                fixture.mutations.withPackageMutation(fixture.root, id, "ENRICHMENT_INSTALL_V2", transactionId,
                    requireExactRoot = { fixture.roots.requireSameLibrary(fixture.root) }) { journal ->
                    if (!recoverable) journal.write("COMMITTING")
                }
                val journal = File(fixture.context.filesDir, "operation-journals/$transactionId.json")
                val beforeJournal = journal.readBytes()
                val original = File(fixture.providerRoot, "$id/original.opus")
                val beforeOriginal = original.readBytes()
                val ready = CompletableDeferred<Unit>()
                fixture.cold.setReadyListener { ready.complete(Unit) }
                checkNotNull(fixture.cold.start())
                if (recoverable) {
                    withTimeout(15_000L) { ready.await() }
                    assertFalse("Recovery journal was skipped before readiness", journal.exists())
                    fixture.cold.cancelAndJoin()
                } else {
                    withTimeout(15_000L) { fixture.cold.awaitCompletion() }
                    assertTrue(fixture.cold.state.value is ColdOpenState.Blocked)
                    assertFalse(ready.isCompleted)
                    assertArrayEquals(beforeJournal, journal.readBytes())
                }
                assertArrayEquals(beforeOriginal, original.readBytes())
            } finally { fixture.close() }
        }
    }

    @Test
    fun acceptedPortableJobRecoversItsExactSourceIdentityAfterPrivateProjectionLoss() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val source = fixture.dao.currentSource(id)!!
            val plan = app.stemwise.integration.MvsepSourcePlanner.plan(listOf("vocals"), listOf(
                app.stemwise.integration.MvsepAlgorithm(63, "Fixture",
                    listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), 1.0, 0)))
            val created = app.stemwise.integration.createPortableSourceJobAuthority(fixture.root.libraryId, id,
                source.id, source.sha256, plan, System.currentTimeMillis())
            val entered = app.stemwise.integration.transitionPortableSourceJob(created,
                app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION, created.updatedAtEpochMs + 1)
            val accepted = app.stemwise.integration.transitionPortableSourceJob(entered,
                app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, entered.updatedAtEpochMs + 1, "exact-accepted-job")
            fixture.mutations.withRootMutation {
                fixture.roots.writeExactSongSidecar(fixture.root, id, SelectedLibraryContract.SOURCE_JOB_AUTHORITY,
                    "application/json", accepted.toBytes(), app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
                fixture.dao.clearActiveLibraryProjection()
            }
            val ready = CompletableDeferred<Unit>()
            fixture.cold.setReadyListener { ready.complete(Unit) }
            checkNotNull(fixture.cold.start())
            withTimeout(15_000L) { ready.await() }
            fixture.cold.cancelAndJoin()
            assertEquals(source.id, fixture.dao.currentSource(id)?.id)
            assertEquals("exact-accepted-job", fixture.dao.findJobById(accepted.jobId)?.remoteJobId)
            assertTrue(fixture.playlist.verifyPlaybackSong(id).sourcePath != null)
            assertEquals(mapOf("youtube-video:$id" to id), fixture.bindings.activePackageIdsBySource(fixture.root))
        } finally { fixture.close() }
    }

    @Test fun missingCompletedPartStartsAnExplicitRepairGenerationAndRetainsItsAcceptedHistory() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val folder = File(fixture.providerRoot, id)
            val media = CurrentSourcePublicationFixture.load(fixture.context, id)
            val original = File(folder, "original.opus").apply { writeBytes(media.original) }
            seedCompleteOpusMetadata(folder, id, media.original)
            fixture.external.verifyPlaybackSong(fixture.root, id)
            val source = checkNotNull(fixture.dao.currentSource(id))
            fun provider(renderId: Int, roles: Set<String>, options: Map<String, String> = emptyMap()) =
                app.stemwise.integration.MvsepAlgorithm(renderId, "Fixture", roles.toList(), 1.0, 0,
                    app.stemwise.integration.MvsepCatalogueMetadata(roles, options.map { (name, value) ->
                        app.stemwise.integration.MvsepOptionField(name, name, setOf(value))
                    }, emptyList()))
            val algorithms = listOf(
                provider(63, setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")),
                provider(49, setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
                    mapOf("add_opt1" to "6", "add_opt2" to "0")),
                provider(66, setOf("acoustic-guitar", "other"), mapOf("add_opt2" to "0")),
                provider(81, setOf("electric-guitar", "other"), mapOf("add_opt2" to "0")),
                provider(72, setOf("harp", "other")),
                provider(74, setOf("mandolin", "other")))
            val plan = app.stemwise.integration.MvsepSourcePlanner.plan(media.manifest.selectedSourceIds, algorithms)
            assertEquals(media.manifest.selectedSourceIds, plan.selectedSourceIds)
            var authority = app.stemwise.integration.createPortableSourceJobAuthority(fixture.root.libraryId,
                id, source.id, source.sha256, plan, System.currentTimeMillis())
            fun step(transition: app.stemwise.integration.SourceJobTransition, remote: String? = null) {
                authority = app.stemwise.integration.transitionPortableSourceJob(authority, transition,
                    authority.updatedAtEpochMs + 1L, remote)
            }
            media.parts.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
            val bass = media.manifest.lanes.single { it.sourceId == "bass" }
            val part = File(folder, bass.fileName)
            val remainder = media.manifest.lanes.single { it.sourceId == "remaining" }
            val remainingBytes = media.parts.getValue(remainder.fileName)
            plan.operations.forEachIndexed { index, operation ->
                step(app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION)
                step(app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, "already-completed-exact-job-$index")
                step(app.stemwise.integration.SourceJobTransition.BEGIN_DOWNLOAD)
                operation.outputBindings.keys.forEach { sourceId ->
                    val asset = media.manifest.lanes.single { it.sourceId == sourceId }
                    authority = app.stemwise.integration.recordPortableSourceAsset(authority, asset, authority.updatedAtEpochMs + 1)
                }
                if (index < plan.operations.lastIndex) step(app.stemwise.integration.SourceJobTransition.ADVANCE_OPERATION)
            }
            step(app.stemwise.integration.SourceJobTransition.BEGIN_ASSEMBLY)
            authority = app.stemwise.integration.recordPortableSourceAsset(authority, remainder, authority.updatedAtEpochMs + 1)
            step(app.stemwise.integration.SourceJobTransition.BEGIN_PUBLICATION)
            step(app.stemwise.integration.SourceJobTransition.COMPLETE)
            val manifest = media.manifest.copy(planSha256 = authority.planSha256)
            File(folder, SelectedLibraryContract.CUSTOM_SOURCE_MANIFEST).writeBytes(manifest.toBytes())
            fixture.roots.writeExactSongSidecar(fixture.root, id, SelectedLibraryContract.SOURCE_JOB_AUTHORITY,
                "application/json", authority.toBytes(), app.stemwise.integration.PORTABLE_SOURCE_JOB_MAX_BYTES)
            fixture.dao.upsertJob(app.stemwise.integration.projectSourceJob(authority))
            assertTrue(fixture.external.verifyPlaybackSong(fixture.root, id).hasStems)
            val accepted = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).readBytes()
            val originalBytes = original.readBytes()
            val retainedParts = media.parts.filterKeys { it != bass.fileName }
            var catalogueReads = 0
            val client = object : app.stemwise.integration.MvsepServiceClient {
                override suspend fun sourceAlgorithms(): List<app.stemwise.integration.MvsepAlgorithm> {
                    catalogueReads++
                    return algorithms
                }
                override suspend fun disablePremium(token: String): Unit = error("No submission during admission")
                override suspend fun status(remoteJobId: String): app.stemwise.integration.MvsepRemoteStatus = error("No polling during admission")
                override suspend fun download(remote: app.stemwise.integration.MvsepRemoteFile, target: File): Unit = error("No download during admission")
            }
            val repository = SeparationRepository(fixture.context, fixture.dao, fixture.account, fixture.roots,
                fixture.mutations, fixture.songWork, fixture.external, fixture.publisher, fixture.governor,
                fixture.engine, fixture.network, client)
            val complete = repository.queueSources(fixture.root, id, plan.selectedSourceIds).getOrThrow()
            assertEquals(authority.jobId, complete.jobId)
            assertFalse(complete.requiresWorker)
            assertEquals("An intact completed owner must not be replanned", 0, catalogueReads)
            assertArrayEquals(accepted, File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).readBytes())
            check(part.delete())
            val broken = fixture.external.verifyPlaybackSong(fixture.root, id)
            assertFalse(broken.hasStems)
            assertTrue(broken.hasSourceParts)
            val repair = repository.queueSources(fixture.root, id, plan.selectedSourceIds).getOrThrow()
            assertEquals("Explicit repair must consult the current catalogue once", 1, catalogueReads)
            assertTrue(repair.requiresWorker)
            assertTrue(authority.jobId != repair.jobId)
            val next = app.stemwise.integration.parsePortableSourceJobAuthority(
                File(folder, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY).readBytes())
            assertEquals(2L, next.generation)
            assertEquals("CREATED", next.state)
            assertEquals(plan, next.plan)
            assertTrue(next.remoteJobIds.all { it == null })
            assertArrayEquals(accepted, File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).readBytes())
            assertArrayEquals(originalBytes, original.readBytes())
            assertArrayEquals(remainingBytes, File(folder, "remaining.wv").readBytes())
            retainedParts.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
        } finally { fixture.close() }
    }

    @Test fun interruptedCanonicalScanPublishesCompletedChangesBeforeReturningCancellation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (withdraw in listOf(true, false)) {
            val fixture = Fixture()
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var scan: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                fixture.open(); fixture.seed(2)
                fixture.external.reconcile()
                val id = "COLD0000000"
                val manifest = File(fixture.providerRoot, "$id/song.json")
                if (withdraw) manifest.writeText("{", Charsets.UTF_8) else {
                    val changed = org.json.JSONObject(manifest.readText(Charsets.UTF_8))
                    changed.put("metadata", org.json.JSONObject()
                        .put("matchStatus", "youtube-fallback")
                        .put("title", "Completed before cancellation")
                        .put("artists", org.json.JSONArray().put("Fixture"))
                        .put("sources", org.json.JSONArray().put(org.json.JSONObject()
                            .put("name", "YouTube Music")
                            .put("url", "https://music.youtube.com/watch?v=$id"))))
                    changed.getJSONObject("install").put("generatedDigest",
                        app.stemwise.enrichment.installedEnrichmentDigest(changed))
                    manifest.writeText(changed.toString(), Charsets.UTF_8)
                }
                fixture.external.canonicalPackageVerifiedForTest = { verified ->
                    if (verified == id) {
                        reached.complete(Unit)
                        withTimeout(5_000L) { release.await() }
                    }
                }
                scan = async(kotlinx.coroutines.Dispatchers.IO) { fixture.external.reconcile() }
                withTimeout(10_000L) { reached.await() }
                scan.cancel()
                withTimeout(5_000L) { scan.join() }
                val snapshot = fixture.external.playbackSnapshot()
                assertTrue(snapshot.snapshotReady)
                assertTrue("An interrupted scan removed its unvisited package",
                    snapshot.songs.any { it.id == "COLD0000001" })
                assertNotNull(fixture.dao.findSongById("COLD0000001"))
                if (withdraw) {
                    assertTrue(snapshot.songs.none { it.id == id })
                    assertTrue(fixture.external.songs.value.none { it.id == id })
                } else {
                    assertEquals("Completed before cancellation", snapshot.songs.single { it.id == id }.title)
                    assertEquals("Completed before cancellation", fixture.external.songs.value.single { it.id == id }.title)
                }
            } finally {
                release.complete(Unit)
                fixture.external.canonicalPackageVerifiedForTest = null
                scan?.cancel()
                withTimeout(5_000L) { scan?.join() }
                fixture.close()
            }
        }
    }

    @Test fun pendingOwnerRetirementCannotBeReopenedByColdRetryOrCancellationCleanup() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            fixture.cold.blockForPendingOwnerRetirement()
            val blocked = fixture.cold.state.value as ColdOpenState.Blocked
            assertEquals(LIBRARY_OWNER_RETIREMENT_PENDING, blocked.code)
            fixture.cold.retry()
            fixture.cold.cancelAndJoin()
            assertNull(fixture.cold.start(reconnectRoot = true))
            assertEquals(blocked, fixture.cold.state.value)
            assertEquals(ColdOpenProducerAdmission.COMPLETE, fixture.cold.producerAdmission(fixture.root))
        } finally { fixture.close() }
    }

    @Test fun aPortableDeletionWrittenBetweenScanTurnsInvalidatesTheScansAuthority() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(2)
            val removed = "COLD0000001"
            val folder = File(fixture.providerRoot, removed)
            val before = folder.listFiles()!!.associate { it.name to it.readBytes() }
            var changed = false
            fixture.external.canonicalPackageVerifiedForTest = {
                if (!changed) {
                    changed = true
                    fixture.mutations.withPackageMutation(fixture.root, removed, "TEST_TOMBSTONE", "scan-deletion",
                        requireExactRoot = { fixture.roots.requireSameLibrary(fixture.root) }) { journal ->
                        fixture.bindings.activateUnderPackageLock(fixture.root, "youtube-video:$removed", removed,
                            "scan-deletion", journal)
                        fixture.bindings.markDeletedUnderPackageLock(fixture.root, "youtube-video:$removed", removed,
                            "scan-deletion", journal)
                        journal.complete()
                    }
                }
            }
            fixture.external.reconcile()
            assertTrue(changed)
            assertEquals(listOf("COLD0000000"), fixture.external.songs.value.map { it.id })
            assertTrue("youtube-video:$removed" in fixture.bindings.blockedSourceIds(fixture.root))
            assertEquals("SOURCE_DELETED", runCatching { fixture.external.verifyPlaybackSong(fixture.root, removed) }.exceptionOrNull()?.message)
            before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
        } finally { fixture.external.canonicalPackageVerifiedForTest = null; fixture.close() }
    }

    @Test fun projectedValidationReadsPortableAuthorityOncePerScanAndScalesByReturnedRows() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val rows = mutableListOf<Long>()
        for (count in listOf(100, 1_000)) {
            val fixture = Fixture()
            try {
                fixture.open(); fixture.seed(count)
                fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
                val started = android.os.SystemClock.elapsedRealtime()
                withTimeout(120_000L) { fixture.external.validateProjectedLibrary(fixture.root) }
                val metrics = fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
                val parents = metrics.getStringArrayList("queryChildParents").orEmpty()
                assertTrue("Root was re-enumerated for individual songs: ${parents.count { it == "root" }}",
                    parents.count { it == "root" } <= 4)
                assertEquals(count, fixture.external.songs.value.size)
                assertTrue("Validation must open actual Original bytes", metrics.getLong("openedDocumentLengthBytes") > 0L)
                rows += metrics.getLong("returnedDocumentRows")
                android.util.Log.i("StemwiseStorageGate", "projectedValidation songs=$count rows=${rows.last()} " +
                    "queries=${metrics.getLong("queryChildCalls")} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            } finally { fixture.close() }
        }
        assertTrue("Ten times the songs must not require quadratic metadata rows: $rows", rows[1] <= rows[0] * 12L)
    }

    @Test fun damagedOptionalJobOnFreshProjectionKeepsOriginalAndAllowsOnlyRetiredExplicitDiscard() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(1)
            val id = "COLD0000000"
            val folder = File(fixture.providerRoot, id)
            val trio = folder.listFiles()!!.associate { it.name to it.readBytes() }
            val broken = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY).apply { writeText("{", Charsets.UTF_8) }
            fixture.dao.clearActiveLibraryProjection()
            fixture.external.reconcile()
            val song = fixture.external.songs.value.single()
            assertTrue(song.hasSourceParts)
            assertFalse(song.hasStems)
            assertNotNull(song.sourcePath)
            assertEquals(id, fixture.external.problems.value.single().packageId)
            assertTrue(fixture.playlist.verifyPlaybackSong(id).hasSourceParts)
            val source = checkNotNull(fixture.dao.currentSource(id))
            val plan = app.stemwise.integration.MvsepSourcePlanner.plan(listOf("vocals"), listOf(
                app.stemwise.integration.MvsepAlgorithm(63, "Fixture",
                    listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), 1.0, 0)))
            val retainedSourceId = "accepted-portable-source-identity"
            val created = app.stemwise.integration.createPortableSourceJobAuthority(fixture.root.libraryId, id,
                retainedSourceId, source.sha256, plan, 1L)
            val entered = app.stemwise.integration.transitionPortableSourceJob(created,
                app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION, 2L)
            val unknown = app.stemwise.integration.transitionPortableSourceJob(entered,
                app.stemwise.integration.SourceJobTransition.MARK_SUBMISSION_UNKNOWN, 3L)
            broken.writeText("not-json", Charsets.UTF_8)
            val survivingCopy = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY_TEMP).apply { writeBytes(unknown.toBytes()) }
            fixture.dao.clearActiveLibraryProjection()
            fixture.external.reconcile()
            assertEquals("A damaged sibling cannot replace an intact unknown operation's source identity",
                retainedSourceId, fixture.dao.currentSource(id)?.id)
            val deletion = PermanentDeletionCoordinator(fixture.trash, fixture.external, fixture.songWork)
            assertTrue("An intact unknown copy must block discard even beside malformed bytes",
                deletion.deleteStems(fixture.root, id).isFailure)
            assertArrayEquals(unknown.toBytes(), survivingCopy.readBytes())
            check(survivingCopy.delete())
            fixture.dao.upsertJob(app.stemwise.integration.projectSourceJob(unknown))
            assertTrue("A Room-owned unknown operation must block damaged-sidecar discard",
                deletion.deleteStems(fixture.root, id).isFailure)
            assertEquals("not-json", broken.readText(Charsets.UTF_8))
            fixture.dao.upsertJob(app.stemwise.integration.projectSourceJob(unknown).copy(state = "OBSOLETE"))
            deletion.deleteStems(fixture.root, id).getOrThrow()
            assertFalse(broken.exists())
            trio.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(folder, name).readBytes()) }
            assertNotNull(fixture.playlist.verifyPlaybackSong(id).sourcePath)
            assertFalse(fixture.external.songs.value.single().hasSourceParts)
            assertTrue(fixture.external.problems.value.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun oneSongMutationInHundredSongLibraryReadsOnlyThatSongsMediaButExplicitRefreshDetectsExternalEdits() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        try {
            fixture.open(); fixture.seed(100)
            withTimeout(120_000L) { fixture.external.reconcile() }
            val id = "COLD0000100"
            val added = File(fixture.providerRoot, id).apply { check(mkdir()) }
            val original = File(fixture.providerRoot, "COLD0000000/${SelectedLibraryContract.ORIGINAL}").readBytes()
            File(added, SelectedLibraryContract.ORIGINAL).writeBytes(original)
            seedCompleteOpusMetadata(added, id, original)
            val previousGeneration = fixture.external.storageRefreshState.value.generation
            fixture.provider(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
            fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            val started = SystemClock.elapsedRealtime()
            fixture.external.reconcilePackages(fixture.root, listOf(id))
            withTimeout(15_000L) { fixture.external.storageRefreshState.first {
                it.generation > previousGeneration && !it.refreshing
            } }
            val metrics = fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
            val mediaReads = metrics.getStringArrayList("openedDocumentIds").orEmpty().filter {
                it.substringAfterLast('/') in SelectedLibraryContract.allMediaFiles
            }
            assertTrue("No actual affected-song audio was verified", mediaReads.isNotEmpty())
            assertEquals("Unrelated media was reopened: $mediaReads", setOf("root/$id/${SelectedLibraryContract.ORIGINAL}"), mediaReads.toSet())
            assertTrue(metrics.getLong("readMediaBytes") > 0L)
            assertEquals(101, fixture.external.songs.value.size)
            assertEquals(101, fixture.external.storageRefreshState.value.inventory.verifiedSongPackages)
            assertEquals(101, fixture.external.storageRefreshState.value.inventory.pcEnrichedSongManifests)
            android.util.Log.i("StemwiseStorageGate", "scopedMutation songs=100 mediaBytes=${metrics.getLong("readMediaBytes")} " +
                "documentBytes=${metrics.getLong("readDocumentBytes")} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            val editedId = "COLD0000099"
            val edited = File(fixture.providerRoot, "$editedId/${SelectedLibraryContract.ORIGINAL}")
            val timestamp = edited.lastModified()
            val bytes = edited.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            edited.writeBytes(bytes)
            check(edited.setLastModified(timestamp))
            fixture.provider(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            withTimeout(120_000L) { fixture.external.reconcile() }
            assertFalse("Explicit refresh reused stale media authority", fixture.external.songs.value.any { it.id == editedId })
            assertEquals(100, fixture.external.songs.value.size)
            assertTrue(fixture.external.problems.value.any { it.packageId == editedId })
            assertArrayEquals(bytes, edited.readBytes())
            assertTrue(fixture.provider(GoldenQaDocumentsProvider.METHOD_METRICS)
                .getStringArrayList("openedDocumentIds").orEmpty().contains("root/$editedId/${SelectedLibraryContract.ORIGINAL}"))
            val generationBeforeDeletion = fixture.external.storageRefreshState.value.generation
            val deletion = PermanentDeletionCoordinator(fixture.trash, fixture.external, fixture.songWork)
                .deletePermanently(fixture.root, id).getOrThrow()
            assertFalse(deletion.projectionRefreshPending)
            assertEquals(99, fixture.external.songs.value.size)
            assertFalse(fixture.external.songs.value.any { it.id == id })
            assertEquals(99, fixture.dao.canonicalStorageTotals().packages)
            val refreshed = withTimeout(15_000L) { fixture.external.storageRefreshState.first {
                it.generation > generationBeforeDeletion && !it.refreshing
            } }
            assertNull(refreshed.errorCode)
            assertEquals(LibraryInventoryAvailability.VERIFIED, refreshed.availability)
            assertEquals(99, refreshed.inventory.verifiedSongPackages)
            assertEquals(99, refreshed.inventory.pcEnrichedSongManifests)
        } finally { fixture.close() }
    }

    @Test fun admittedPlaylistCommitCannotWriteAfterItsRootOrSessionOwnerRetires() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (changeRoot in listOf(false, true)) {
            val fixture = Fixture()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var commit: kotlinx.coroutines.Deferred<Result<PlaylistSyncSummary>>? = null
            var retirement: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                fixture.open()
                val id = "OWNED000001"
                val item = SongEntity(id, id, null, "https://music.youtube.com/watch?v=$id", "Reviewed", "Fixture",
                    null, null, durationMs = 1_000L, artworkPath = null, currentlyInSourcePlaylist = true,
                    highLevelState = "DISCOVERED", acquiredAtEpochMs = null, createdAtEpochMs = 1L)
                val link = app.stemwise.integration.PlaylistLink("PL123456789012345", "https://music.youtube.com/playlist?list=PL123456789012345")
                val candidate = PlaylistCandidate(link, "Reviewed", listOf(item), "", 0, 0, 0,
                    playlistMembershipSha256(emptyList()), PlaylistSessionToken(fixture.root.libraryId, fixture.root.treeUri.toString(), 0L))
                val lease = fixture.playlist.beginExclusivePlaylistCommit(candidate, fixture.root.authority())
                val owner = async(kotlinx.coroutines.Dispatchers.IO) {
                    fixture.playlist.commitPlaylistCandidate(candidate, PlaylistCommitMode.KEEP_AND_ADD, lease) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "COMMIT_TEST_RELEASE_TIMEOUT" }
                    }
                }
                commit = owner
                assertTrue("Commit did not reach the actual pre-write authorization boundary",
                    entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (changeRoot) {
                    retirement = async(kotlinx.coroutines.Dispatchers.IO) {
                        fixture.roots.reportRuntimeAccessFailure(fixture.root, SecurityException("isolated root loss"))
                        Unit
                    }
                    withTimeout(5_000L) { fixture.roots.state.first { !it.ready } }
                } else {
                    retirement = async(kotlinx.coroutines.Dispatchers.IO) { fixture.playlist.cancelSessionWorkForLibrarySwitch() }
                    withTimeout(5_000L) { while (owner.isActive) kotlinx.coroutines.yield() }
                }
                release.countDown()
                val result = runCatching { withTimeout(5_000L) { owner.await() } }
                if (changeRoot) assertEquals("LIBRARY_CHANGED", result.getOrThrow().exceptionOrNull()?.message)
                else assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                withTimeout(5_000L) { retirement?.await() }
                assertTrue(fixture.dao.allSongs().isEmpty())
                assertNull(fixture.dao.selectedPlaylist(fixture.root.libraryId))
            } finally {
                release.countDown()
                commit?.cancel(); retirement?.cancel()
                withTimeout(10_000L) { commit?.join(); retirement?.join() }
                fixture.close()
            }
        }
    }

    @Test fun normalLibraryCoordinatorKeepAndAddCommitsReviewedMembershipWithoutAuthorizingBridgeWork() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        app.stemwise.playback.requireDisposablePlaybackTarget()
        val fixture = Fixture()
        val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var audio: app.stemwise.playback.AudioPlayback? = null
        try {
            fixture.open()
            audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                app.stemwise.playback.AudioPlayback(fixture.context, fixture.playlist, fixture.separation, fixture.roots)
            }
            val manual = app.stemwise.work.ManualTaskCoordinator(fixture.context, fixture.separation,
                fixture.roots, fixture.cold, fixture.preferences)
            val bridge = app.stemwise.bridge.SongBridgeRepository(fixture.context, fixture.roots,
                fixture.importer, fixture.external, fixture.dao, fixture.governor, producerReady = { true },
                blockedIds = { emptySet() }, prepareExplicitReadd = { _, _ -> error("No Bridge re-add") },
                transportFor = { error("Playlist commit cannot contact Bridge") }, scope = bridgeScope, schedule = { _, _ -> error("Playlist commit cannot send to Bridge") })
            val coordinator = LibrarySessionCoordinator(fixture.context, fixture.roots, fixture.preferences,
                fixture.playlist, fixture.external, fixture.imports, fixture.importer, fixture.separation,
                checkNotNull(audio), fixture.cold, fixture.songWork, manual, bridge)
            val link = app.stemwise.integration.PlaylistLink("PL123456789012345", "https://music.youtube.com/playlist?list=PL123456789012345")
            val shares = app.stemwise.integration.ShareIntentCoordinator()
            val offer = shares.confirm(link)
            val authorization = checkNotNull(shares.claimPlaylistCommit(offer, link))
            val id = "COORD000001"
            val item = SongEntity(id, id, null, "https://music.youtube.com/watch?v=$id", "Reviewed song", "Fixture",
                null, null, durationMs = 1_000L, artworkPath = null, currentlyInSourcePlaylist = true,
                highLevelState = "DISCOVERED", acquiredAtEpochMs = null, createdAtEpochMs = 1L)
            val candidate = PlaylistCandidate(link, "Reviewed playlist", listOf(item), "", 0, 0, 0,
                playlistMembershipSha256(emptyList()), PlaylistSessionToken(fixture.root.libraryId, fixture.root.treeUri.toString(), 0L))
            val outcome = coordinator.commitPlaylist(candidate, PlaylistCommitMode.KEEP_AND_ADD, authorization)
            assertTrue(outcome.exceptionOrNull().toString(), outcome.isSuccess)
            assertEquals(link.id, fixture.dao.selectedPlaylist(fixture.root.libraryId)?.playlistId)
            assertEquals(link.id, fixture.preferences.playlist.value.link.id)
            assertEquals(listOf(id), fixture.dao.pendingPcSongs().map { it.id })
            assertTrue(bridge.tasks.value.isEmpty())
            assertFalse(coordinator.switching.value)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { audio?.release() }
            withTimeout(10_000L) { bridgeScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            app.stemwise.work.PlaylistSyncWorker.cancelSchedulingAndAwait(fixture.context)
            app.stemwise.work.MvsepPreparationWorker.cancelSchedulingAndAwait(fixture.context)
            fixture.close()
        }
    }

    @Test fun playlistRefreshCannotPruneAnOriginalPublicationBeforeItsDirectoryBinds() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        for (captureBeforePublication in listOf(false, true)) {
            val fixture = Fixture()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var publication: kotlinx.coroutines.Deferred<Result<Uri>>? = null
            try {
                fixture.open()
                val id = "IMPORT00001"
                val original = GoldenSongMediaFixture.originalOpus(fixture.context, 240L)
                val source = File(fixture.context.cacheDir, "incoming.opus").apply { writeBytes(original) }
                val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
                suspend fun candidate(): PlaylistCandidate {
                    val rows = fixture.dao.allSongs()
                    return PlaylistCandidate(app.stemwise.integration.PlaylistLink("PL123456789012345",
                        "https://music.youtube.com/playlist?list=PL123456789012345"), "Playlist", emptyList(), "",
                        rows.count { it.currentlyInSourcePlaylist }, rows.size,
                        rows.count { !it.currentlyInSourcePlaylist }, playlistMembershipSha256(rows),
                        PlaylistSessionToken(fixture.root.libraryId, fixture.root.treeUri.toString(), 0L))
                }
                val captured = if (captureBeforePublication) candidate() else null
                fixture.publisher.beforeDirectoryBindingForTest = { entered.complete(Unit); release.await() }
                publication = async(kotlinx.coroutines.Dispatchers.IO) {
                    fixture.publisher.publishOriginal(fixture.root, id, source, hash)
                }
                withTimeout(10_000L) { entered.await() }
                fixture.playlist.persistPlaylistCandidate(captured ?: candidate(), PlaylistCommitMode.REFRESH_CURRENT)
                release.complete(Unit)
                withTimeout(15_000L) { publication.await() }.getOrThrow()
                val stored = checkNotNull(fixture.dao.findSongById(id))
                assertTrue(!stored.packageDocumentUri.isNullOrBlank())
                assertFalse(stored.currentlyInSourcePlaylist)
                assertArrayEquals(original, File(fixture.providerRoot, "$id/original.opus").readBytes())
                fixture.dao.deleteUnreferencedDiscoverySongs()
                assertEquals(stored, fixture.dao.findSongById(id))
            } finally {
                release.complete(Unit); publication?.cancel()
                withTimeout(10_000L) { publication?.join() }
                fixture.close()
            }
        }
    }

    @Test fun confirmedSameFolderSelectionRestartsBlockedRecoveryAndHealthySelectionIsANoop() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        app.stemwise.playback.requireDisposablePlaybackTarget()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val controlPermission = "app.stemwise.testprovider.permission.CONTROL_FIXTURE"
        val permission = target.packageManager.getPermissionInfo(controlPermission, 0)
        assertEquals(target.packageName, permission.packageName)
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            target.packageManager.checkPermission(controlPermission, target.packageName))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            target.packageManager.checkPermission(controlPermission, "com.android.shell"))
        val fixture = Fixture()
        val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        var audio: app.stemwise.playback.AudioPlayback? = null
        var ownedPackage: androidx.documentfile.provider.DocumentFile? = null
        var grantHost: ActivityScenario<ComponentActivity>? = null
        val ownedId = "RC" + UUID.randomUUID().toString().replace("-", "").take(9)
        try {
            audio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                app.stemwise.playback.AudioPlayback(fixture.context, fixture.playlist, fixture.separation, fixture.roots)
            }
            val manual = app.stemwise.work.ManualTaskCoordinator(fixture.context, fixture.separation,
                fixture.roots, fixture.cold, fixture.preferences)
            val bridge = app.stemwise.bridge.SongBridgeRepository(fixture.context, fixture.roots,
                fixture.importer, fixture.external, fixture.dao, fixture.governor, producerReady = { false },
                blockedIds = { emptySet() }, prepareExplicitReadd = { _, _ -> error("No Bridge re-add") },
                transportFor = { error("Reconnect cannot contact Bridge") }, scope = bridgeScope,
                schedule = { _, _ -> error("Reconnect cannot send to Bridge") })
            val coordinator = LibrarySessionCoordinator(fixture.context, fixture.roots, fixture.preferences,
                fixture.playlist, fixture.external, fixture.imports, fixture.importer, fixture.separation,
                checkNotNull(audio), fixture.cold, fixture.songWork, manual, bridge)
            // Acquire the cross-UID provider's real result grant before normal root admission.
            // Keep its existing root/packages; this case owns only one uniquely named child.
            val tree = Uri.parse("content://app.stemwise.testprovider.documents/tree/root")
            val granted = CompletableDeferred<androidx.activity.result.ActivityResult>()
            grantHost = ActivityScenario.launch(ComponentActivity::class.java)
            checkNotNull(grantHost).onActivity { host ->
                val launcher = host.activityResultRegistry.register("reconnect-grant-$ownedId",
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
                    granted.complete(result)
                }
                launcher.launch(Intent().setComponent(android.content.ComponentName("app.stemwise.testprovider",
                    "app.stemwise.testprovider.GrantQualificationTreeActivity"))
                    .putExtra("stemwiseQaGrantTreeUri", tree.toString()))
            }
            val result = withTimeout(10_000L) { granted.await() }
            assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
            assertEquals(tree, result.data?.data)
            val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            assertEquals(requiredFlags, checkNotNull(result.data).flags and requiredFlags)
            coordinator.switchTo(fixture.roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            val first = withTimeout(30_000L) { fixture.cold.state.first { it is ColdOpenState.Ready } } as ColdOpenState.Ready
            withTimeout(30_000L) { fixture.cold.awaitCompletion() }
            fixture.root = checkNotNull(fixture.roots.captureHandle())
            assertTrue(fixture.target.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            })
            val baselineIds = fixture.external.songs.value.mapTo(hashSetOf()) { it.id }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val directory = checkNotNull(fixture.root.rootDocument.createDirectory(ownedId)).also { ownedPackage = it }
                fun publish(name: String, mime: String, bytes: ByteArray) {
                    val file = checkNotNull(directory.createFile(mime, name))
                    checkNotNull(fixture.context.contentResolver.openOutputStream(file.uri, "w")).use { it.write(bytes) }
                }
                val templateId = "DEMOUI00001"
                publish("original.opus", "audio/ogg", fixture.context.assets.open("demo_library/$templateId/original.opus").use { it.readBytes() })
                publish("cover.png", "image/png", fixture.context.assets.open("demo_library/$templateId/cover.png").use { it.readBytes() })
                val template = fixture.context.assets.open("demo_library/$templateId/song.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                publish("song.json", "application/json", app.stemwise.demo.uiDemoLibraryManifest(template, templateId, ownedId, 1))
                fixture.external.reconcilePackage(fixture.root, ownedId)
            }
            fixture.cold.blockStartup(java.io.IOException("Provider enumeration was temporarily unavailable"))
            assertTrue(fixture.roots.state.value.ready)
            assertTrue(fixture.cold.state.value is ColdOpenState.Blocked)
            val candidate = fixture.roots.validateCandidate(fixture.root.treeUri).getOrThrow()
            coordinator.switchTo(candidate).getOrThrow()
            val ready = withTimeout(30_000L) { fixture.cold.state.first { it is ColdOpenState.Ready } } as ColdOpenState.Ready
            withTimeout(30_000L) { fixture.cold.awaitCompletion() }
            fixture.root = checkNotNull(fixture.roots.captureHandle())
            assertTrue(ready.generation > first.generation)
            assertEquals(baselineIds + ownedId, fixture.external.songs.value.mapTo(hashSetOf()) { it.id })
            coordinator.switchTo(fixture.roots.validateCandidate(fixture.root.treeUri).getOrThrow()).getOrThrow()
            assertEquals(ready, fixture.cold.state.value)
        } finally {
            grantHost?.close()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { audio?.release() }
            withTimeout(10_000L) { bridgeScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
            app.stemwise.work.PlaylistSyncWorker.cancelSchedulingAndAwait(fixture.context)
            app.stemwise.work.MvsepPreparationWorker.cancelSchedulingAndAwait(fixture.context)
            fixture.cold.cancelAndJoin()
            fixture.imports.cancelForLibraryChange()
            fixture.external.retireScansForLibrarySwitch()
            ownedPackage?.let { directory ->
                val currentRoot = checkNotNull(fixture.roots.captureHandle())
                check(currentRoot.libraryId == fixture.root.libraryId && currentRoot.treeUri == fixture.root.treeUri)
                fixture.roots.requireSameLibrary(currentRoot)
                check(directory.delete()) { "Could not remove reconnect fixture ${directory.uri}" }
                fixture.external.removeDeletedPackage(currentRoot, ownedId)
            }
            fixture.database.close()
            fixture.context.destroy()
        }
    }

    @Test fun portableTerminalDeletionRecoversBeforeProjectionWithoutProviderCalls() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val fixture = Fixture()
        var cold: ColdOpenCoordinator? = null
        try {
            fixture.open(); fixture.seed(2)
            val id = "COLD0000000"
            val other = "COLD0000001"
            val folder = File(fixture.providerRoot, id)
            val media = CurrentSourcePublicationFixture.load(fixture.context, id)
            File(folder, SelectedLibraryContract.ORIGINAL).writeBytes(media.original)
            seedCompleteOpusMetadata(folder, id, media.original)
            fixture.external.reconcile()
            val source = checkNotNull(fixture.dao.currentSource(id))
            val plan = app.stemwise.integration.MvsepSourcePlan(listOf("bass"), listOf(
                app.stemwise.integration.MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                    setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            fun created(library: String = fixture.root.libraryId, hash: String = source.sha256, generation: Long = 1L) =
                app.stemwise.integration.createPortableSourceJobAuthority(library, id, source.id, hash, plan, 1L, generation)
            fun step(owner: app.stemwise.integration.PortableSourceJobAuthority,
                transition: app.stemwise.integration.SourceJobTransition, remote: String? = null) =
                app.stemwise.integration.transitionPortableSourceJob(owner, transition, owner.updatedAtEpochMs + 1, remote)
            val entered = step(created(), app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION)
            val processing = step(entered, app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, "retained-terminal")
            var complete = step(processing, app.stemwise.integration.SourceJobTransition.BEGIN_DOWNLOAD)
            for (role in listOf("bass", "remaining")) {
                if (role == "remaining") complete = step(complete, app.stemwise.integration.SourceJobTransition.BEGIN_ASSEMBLY)
                val asset = media.manifest.lanes.single { it.sourceId == role }.copy(
                    fileName = app.stemwise.integration.sourcePartFileName(1L, listOf("bass"), role), timingEvidence = null)
                File(folder, asset.fileName).writeBytes(media.parts.getValue(media.manifest.lanes.single { it.sourceId == role }.fileName))
                complete = app.stemwise.integration.recordPortableSourceAsset(complete, asset, complete.updatedAtEpochMs + 1)
            }
            complete = step(step(complete, app.stemwise.integration.SourceJobTransition.BEGIN_PUBLICATION),
                app.stemwise.integration.SourceJobTransition.COMPLETE)
            val authorityFile = File(folder, SelectedLibraryContract.SOURCE_JOB_AUTHORITY)
            authorityFile.writeBytes(complete.toBytes())
            var replacement = step(created(generation = 2L), app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION)
            replacement = step(replacement, app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, "retired-replacement")
            replacement = step(step(replacement, app.stemwise.integration.SourceJobTransition.REMOTE_FAILED),
                app.stemwise.integration.SourceJobTransition.DISCARD_FAILED)
            File(folder, SelectedLibraryContract.REPLACEMENT_SOURCE_JOB_AUTHORITY).writeBytes(replacement.toBytes())
            fixture.mutations.withPackageMutation(fixture.root, id, "PORTABLE_TOMBSTONE_TEST", "portable-terminal-delete",
                requireExactRoot = { fixture.roots.requireSameLibrary(fixture.root) }) { journal ->
                fixture.bindings.markDeletedUnderPackageLock(fixture.root, "youtube-video:$id", id, "portable-terminal-delete", journal)
                journal.complete(org.json.JSONObject().put("sourceId", "youtube-video:$id"))
            }
            fixture.imports.awaitStartupScratchReady()
            fixture.dao.clearActiveLibraryProjection()
            fixture.dao.deleteSourcesForSong(id)
            fixture.dao.deleteSourcesForSong(other)
            fixture.dao.deleteSourceBindings(fixture.root.libraryId, listOf("youtube-video:$id", "youtube-video:$other"))
            checkedAtomicJournalFiles(File(fixture.context.filesDir, "operation-journals"), "JOURNAL_ENUMERATION_FAILED")
                .forEach { deleteAtomicJournal(it, "JOURNAL_CLEANUP_PENDING") }
            assertNull(fixture.dao.currentSource(id))
            assertTrue(fixture.dao.allSongs().isEmpty())
            val originalBytes = File(folder, SelectedLibraryContract.ORIGINAL).readBytes()
            val rejected = listOf(entered,
                step(entered, app.stemwise.integration.SourceJobTransition.MARK_SUBMISSION_UNKNOWN), processing,
                complete.copy(libraryId = java.util.UUID.randomUUID().toString()),
                step(step(step(step(created(hash = "f".repeat(64)), app.stemwise.integration.SourceJobTransition.ENTER_SUBMISSION),
                    app.stemwise.integration.SourceJobTransition.ACKNOWLEDGE_REMOTE, "wrong-original"),
                    app.stemwise.integration.SourceJobTransition.REMOTE_FAILED), app.stemwise.integration.SourceJobTransition.DISCARD_FAILED))
            for (invalid in rejected) {
                authorityFile.writeBytes(invalid.toBytes())
                assertTrue(runCatching { fixture.trash.recoverInterruptedDeletes(fixture.root) }.isFailure)
                assertArrayEquals(originalBytes, File(folder, SelectedLibraryContract.ORIGINAL).readBytes())
                assertTrue(File(fixture.providerRoot, other).isDirectory)
            }
            authorityFile.writeBytes(complete.toBytes())
            val calls = java.util.concurrent.atomic.AtomicInteger()
            fun unexpected(): Nothing { calls.incrementAndGet(); error("Deletion recovery contacted the provider") }
            val client = object : app.stemwise.integration.MvsepServiceClient {
                override suspend fun sourceAlgorithms(): List<app.stemwise.integration.MvsepAlgorithm> = unexpected()
                override suspend fun disablePremium(token: String): Unit = unexpected()
                override suspend fun submitConfigured(token: String, source: File, operation: app.stemwise.integration.MvsepSourceOperation,
                    isAuthorized: () -> Boolean, onSubmissionEntered: suspend () -> Unit,
                    onAcknowledged: suspend (String) -> Unit, onRejected: suspend (String) -> Unit): String = unexpected()
                override suspend fun status(remoteJobId: String): app.stemwise.integration.MvsepRemoteStatus = unexpected()
                override suspend fun download(remote: app.stemwise.integration.MvsepRemoteFile, target: File): Unit = unexpected()
            }
            val separation = SeparationRepository(fixture.context, fixture.dao, fixture.account, fixture.roots,
                fixture.mutations, fixture.songWork, fixture.external, fixture.publisher, fixture.governor,
                fixture.engine, fixture.network, client)
            val recovery = ColdOpenCoordinator(fixture.roots, fixture.external, fixture.playlist, fixture.preferences,
                fixture.trash, separation, fixture.imports, fixture.importer).also { cold = it }
            checkNotNull(recovery.start())
            withTimeout(30_000L) { recovery.awaitCompletion() }
            assertTrue(recovery.state.value.toString(), recovery.state.value is ColdOpenState.Ready)
            assertFalse(folder.exists())
            assertEquals(listOf(other), fixture.external.songs.value.map { it.id })
            assertNotNull(fixture.playlist.verifyPlaybackSong(other).sourcePath)
            assertEquals(0, calls.get())
        } finally { cold?.cancelAndJoin(); fixture.close() }
    }

    private class Fixture {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = IsolatedContext(target)
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.libraryDao()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, dao)
        val bindings = SourceBindingRegistry(context, roots, mutations, dao)
        val governor = BackgroundWorkCoordinator()
        val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, governor)
        val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, governor)
        val trash = ExternalPackageTrash(context, roots, mutations, bindings, database.libraryDao())
        val preferences = StemwisePreferences(context, database = database)
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        val engine = YoutubeDlEngine(context, network, roots)
        val songWork = SongWorkCoordinator()
        val account = object : MvsepAccountAccess {
            override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED))
            override suspend fun validate(expectedCredential: MvsepCredential?) = Result.success(Unit)
            override fun credential(): MvsepCredential? = null
            override fun isCurrent(credential: MvsepCredential) = false
            override fun apiBase() = "https://mvsep.com"
        }
        val separation = SeparationRepository(context, dao, account, roots, mutations, songWork,
            external, publisher, governor, engine, network)
        val playlist = PlaylistRepository(context, database, dao, preferences, engine, songWork, roots,
            external, governor, network)
        val importer = SongEnrichmentImporter(context, roots, mutations, bindings, governor, { publisher })
        val imports = EnrichmentImportCoordinator(context, importer, external, governor, roots)
        val cold = ColdOpenCoordinator(roots, external, playlist, preferences, trash, separation, imports, importer)
        lateinit var root: RootHandle
        val providerRoot = File(target.noBackupFilesDir, "golden-enrichment-document-provider")
        private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        suspend fun open() {
            provider(GoldenQaDocumentsProvider.METHOD_RESET)
            target.grantUriPermission(target.packageName, GoldenQaDocumentsProvider.treeUri, flags)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            root = checkNotNull(roots.captureHandle())
            preferences.activateLibraryFromRoot(root.libraryId)
        }
        suspend fun seed(count: Int) {
            val original = GoldenSongMediaFixture.originalOpus(context, 240L)
            val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
            for (index in 0 until count) {
                val id = "COLD" + index.toString().padStart(7, '0')
                val folder = File(providerRoot, id).apply { check(mkdir()) }
                File(folder, "original.opus").writeBytes(original)
                seedCompleteOpusMetadata(folder, id, original)
                dao.upsertSongs(listOf(SongEntity(id, id, null, "https://music.youtube.com/watch?v=$id",
                    "Projected $id", "Fixture", "Projected $id", "Fixture", durationMs = 240L,
                    artworkPath = null, currentlyInSourcePlaylist = false, highLevelState = "READY",
                    acquiredAtEpochMs = 1L, createdAtEpochMs = 1L, canonicalExternal = true,
                    packageDocumentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        GoldenQaDocumentsProvider.treeUri, "root/$id").toString())))
                dao.insertSource(SourceVersionEntity("$id:source", id,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(GoldenQaDocumentsProvider.treeUri,
                        "root/$id/original.opus").toString(), hash, "opus", "ogg", 48000, 2,
                    "selected-folder-flat-v1", true, 1L))
            }
        }
        fun provider(method: String, arg: String? = null): Bundle = checkNotNull(target.contentResolver.call(
            Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"), method, arg, null))
        suspend fun close() {
            cold.cancelAndJoin()
            imports.cancelForLibraryChange()
            external.retireScansForLibrarySwitch()
            database.close()
            target.revokeUriPermission(GoldenQaDocumentsProvider.treeUri, flags)
            provider(GoldenQaDocumentsProvider.METHOD_RESET)
            context.destroy()
        }
    }
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "cold-open-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, prefix).apply { check(mkdir()) }
        private val files = File(root, "files").apply { check(mkdir()) }
        private val cache = File(root, "cache").apply { check(mkdir()) }
        private val noBackup = File(root, "no-backup").apply { check(mkdir()) }
        private val preferences = linkedSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("$prefix-$name".also(preferences::add), mode)
        fun destroy() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            check(root.deleteRecursively())
        }
    }
}
