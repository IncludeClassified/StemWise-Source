package app.stemwise.enrichment

import androidx.compose.ui.test.*
import android.content.Context
import androidx.concurrent.futures.await
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.ExecutionListener
import androidx.work.impl.model.WorkGenerationalId
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.stemwise.work.awaitApplicationWorkManager
import app.stemwise.data.*
import app.stemwise.integration.*
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.playback.BackgroundWorkCoordinator
import app.stemwise.qa.GoldenQaDocumentsProvider
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal class SeparationOwnerConcurrencyInstrumentationTest : GoldenEnrichmentFixture() {
    @get:Rule val compose = app.stemwise.ui.createStemwiseComposeRule()

    @Test fun fatalRetirementReportsTheExactOwnerAndNeverAdvancesToTheNextAcceptedJob() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, true, 1L)
            f.job(B, true, 2L)
            val authority = f.authorityFile(VIDEO_ID).readBytes()
            val scratch = File(f.context.noBackupFilesDir, "preparation-active")
            val retained = File(scratch, "retained-input.pcm")
            val fatal = app.stemwise.playback.engine.WorkerRetirementException("ORIGINAL_PCM_RETIREMENT_FAILED")
            f.beforeStatus = { retained.writeBytes(byteArrayOf(2, 3, 5)) }
            f.statusFailure = fatal
            assertSame(fatal, runCatching { withTimeout(10_000L) { f.runWorkerLoop() } }.exceptionOrNull())
            val failed = checkNotNull(f.dao.findJobById(a.jobId))
            assertEquals("FAILED", failed.state)
            assertEquals(fatal.message, failed.lastErrorCode)
            assertNull(failed.nextAttemptAtEpochMs)
            assertEquals("remote-$VIDEO_ID", failed.remoteJobId)
            assertArrayEquals(authority, f.authorityFile(VIDEO_ID).readBytes())
            assertArrayEquals(byteArrayOf(2, 3, 5), retained.readBytes())
            assertEquals(listOf("remote-$VIDEO_ID"), f.statusIds.toList())
            val blocked = f.playlist.preparationOwner.value as PreparationOwnerDecision.Blocked
            assertEquals(a.jobId, blocked.recoverableJobId)
            assertEquals(fatal.message, blocked.code)
            assertFalse(f.playlist.preparationOwnerAllowsAutomaticEnqueue(f.root))
            assertEquals(app.stemwise.model.JobState.NEEDS_ATTENTION,
                app.stemwise.ui.projectPreparationOwnerQueueJob(blocked, f.root.authority(), emptyMap())?.state)
            // Same-process quarantine is still authoritative even after the failed row was written.
            assertSame(fatal, runCatching { f.repository.process(a.jobId, f.root) }.exceptionOrNull())
            assertEquals(listOf("remote-$VIDEO_ID"), f.statusIds.toList())
        }
    }

    @Test fun anAlreadyQuarantinedScratchGuardStillPersistsFailureWithoutTouchingAcceptedWork() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, true, 1L)
            val before = f.authorityFile(VIDEO_ID).readBytes()
            val scratch = File(f.context.noBackupFilesDir, "preparation-active").apply { check(mkdir()) }
            val retained = File(scratch, "input").apply { writeBytes(byteArrayOf(8, 13)) }
            val fatal = app.stemwise.playback.engine.WorkerRetirementException("TOOL_PROCESS_RETIREMENT_UNCONFIRMED")
            WorkerRetirementQuarantine.retain(fatal, files = listOf(scratch))
            assertSame(fatal, runCatching { f.repository.process(a.jobId, f.root) }.exceptionOrNull())
            assertEquals("FAILED", f.dao.findJobById(a.jobId)?.state)
            assertEquals(fatal.message, f.dao.findJobById(a.jobId)?.lastErrorCode)
            assertArrayEquals(before, f.authorityFile(VIDEO_ID).readBytes())
            assertArrayEquals(byteArrayOf(8, 13), retained.readBytes())
            assertTrue(f.statusIds.isEmpty())
        }
    }

    @Test fun failedFatalReportingRetainsTheOriginalFailureAndQuarantinedFiles() = runBlocking {
        withFixture { f ->
            f.job(VIDEO_ID, true, 1L)
            f.job(B, true, 2L)
            val fatal = app.stemwise.playback.engine.WorkerRetirementException("TOOL_PROCESS_RETIREMENT_FAILED")
            val retained = File(f.context.noBackupFilesDir, "preparation-active/input")
            f.beforeStatus = { retained.writeBytes(byteArrayOf(21)); f.database.close() }
            f.statusFailure = fatal
            assertSame(fatal, runCatching { withTimeout(10_000L) { f.runWorkerLoop() } }.exceptionOrNull())
            assertTrue("Failed persistence was lost", fatal.suppressed.isNotEmpty())
            assertTrue(f.playlist.preparationOwner.value is PreparationOwnerDecision.Blocked)
            assertSame(fatal, WorkerRetirementQuarantine.failureFor(retained))
            assertArrayEquals(byteArrayOf(21), retained.readBytes())
            assertEquals(listOf("remote-$VIDEO_ID"), f.statusIds.toList())
        }
    }

    @Test fun lossOfTheExactRootCannotReplaceTheFatalOrRewriteItsOldJob() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, true, 1L)
            val before = f.dao.findJobById(a.jobId)
            val root = f.root
            val fatal = app.stemwise.playback.engine.WorkerRetirementException("ORIGINAL_PCM_RETIREMENT_FAILED")
            f.beforeStatus = { f.roots.reportRuntimeAccessFailure(root, SecurityException("Fixture grant lost")) }
            f.statusFailure = fatal
            assertSame(fatal, runCatching { withTimeout(10_000L) { f.runWorkerLoop() } }.exceptionOrNull())
            assertEquals(before, f.dao.findJobById(a.jobId))
            assertTrue(fatal.suppressed.isNotEmpty())
            assertTrue(f.playlist.preparationOwner.value.authority != null)
        }
    }

    @Test fun tasksRetryOfAcceptedFailureWaitsForActiveOwnerAndResumesTheSameRemoteId() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, accepted = true, createdAt = 1L)
            f.cold.start(); withTimeout(15_000L) { f.cold.awaitCompletion() }
            assertTrue(f.cold.isProducerAdmissionReady(f.root))
            f.failA = true
            repeat(4) { assertEquals(SeparationWorkResult.PENDING, f.repository.process(a.jobId, f.root)) }
            assertEquals(SeparationWorkResult.TERMINAL_FAILURE, f.repository.process(a.jobId, f.root))
            val failed = checkNotNull(f.dao.findJobById(a.jobId))
            assertEquals("FAILED", failed.state)
            val retained = f.authorityFile(VIDEO_ID).readBytes()
            val b = f.job(B, accepted = true, createdAt = 2L)
            val owner = f.playlist.reconcilePreparationOwner(f.root)
            assertEquals(b.jobId, (owner as PreparationOwnerDecision.Separation).jobId)
            val enqueues = AtomicInteger()
            val queued = CompletableDeferred<Unit>()
            val actual = AndroidManualTaskActions(f.context, f.repository, f.roots, f.cold)
            val actions = object : ManualTaskActions by actual {
                override fun enqueueSeparation(expectedRoot: RootAuthority, afterCurrent: Boolean): Boolean {
                    assertEquals(f.root.authority(), expectedRoot); assertTrue(afterCurrent)
                    enqueues.incrementAndGet(); queued.complete(Unit); return true
                }
            }
            val manual = ManualTaskCoordinator(f.scope, TaskWorkGate(), actions,
                captureReadyRoot = { f.root.authority().takeIf { f.cold.isProducerAdmissionReady(f.root) } },
                isReadyRoot = { it == f.root.authority() && f.cold.isProducerAdmissionReady(f.root) })
            val row = checkNotNull(app.stemwise.ui.projectPersistedPreparationQueueJob(failed, emptyMap(), b.jobId, false))
            assertEquals(app.stemwise.model.QueueJobAction.RETRY, row.action)
            compose.setContent { app.stemwise.ui.theme.StemwiseTheme {
                app.stemwise.ui.QueueScreen(onImportEnrichment = {},
                    playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse("https://music.youtube.com/playlist?list=PL1234567890")),
                        "Fixture", null, null, true), syncStatus = PlaylistSyncStatus(), onSync = { false },
                    bridgeState = app.stemwise.bridge.BridgeUiState(), bridgeTasks = emptyList(), jobs = listOf(row),
                    onRetryBridgeTask = { Result.success(Unit) }, onForgetBridgeTask = { Result.success(Unit) },
                    onRetryJob = { manual.retrySeparation(it.id) }, onOpenAddSong = {}, onReviewDraft = {})
            } }
            compose.onNodeWithContentDescription(checkNotNull(row.action).accessibilityDescription(row.songTitle)).performScrollTo().performClick()
            val refusal = withTimeout(5_000L) { manual.retryFailure.first { it != null } }
            assertTrue(checkNotNull(refusal).cause.message.orEmpty().contains("Another song"))
            assertEquals(failed, f.dao.findJobById(a.jobId))
            assertArrayEquals(retained, f.authorityFile(VIDEO_ID).readBytes())
            assertEquals(0, enqueues.get())
            f.failBRemotely = true
            assertEquals(androidx.work.ListenableWorker.Result.success(), f.runWorkerLoop())
            assertTrue(f.statusIds.contains("remote-$B"))
            assertEquals("REMOTE_FAILED", parsePortableSourceJobAuthority(f.authorityFile(B).readBytes()).state)
            f.failA = false
            compose.onNodeWithContentDescription(checkNotNull(row.action).accessibilityDescription(row.songTitle)).performClick()
            withTimeout(5_000L) { queued.await() }
            assertEquals(1, enqueues.get())
            assertEquals("remote-$VIDEO_ID", f.dao.findJobById(a.jobId)?.remoteJobId)
            assertEquals(androidx.work.ListenableWorker.Result.success(), f.runWorkerLoop())
            assertEquals("remote-$VIDEO_ID", f.statusIds.last())
            assertArrayEquals(retained, f.authorityFile(VIDEO_ID).readBytes())
        }
    }

    @Test fun coldReconciliationSerializesVerifiedAcceptedOwnersButRejectsMismatchingRemoteIdentity() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, true, 1L)
            val b = f.job(B, true, 2L)
            f.cold.start(); withTimeout(15_000L) { f.cold.awaitCompletion() }
            assertTrue(f.cold.isProducerAdmissionReady(f.root))
            assertEquals(a.jobId, (f.playlist.preparationOwner.value as PreparationOwnerDecision.Separation).jobId)
            val current = checkNotNull(f.dao.findJobById(b.jobId))
            f.dao.upsertJob(current.copy(remoteJobId = "unrelated-remote"))
            assertTrue(f.playlist.reconcilePreparationOwner(f.root) is PreparationOwnerDecision.Blocked)
            assertEquals("remote-$B", parsePortableSourceJobAuthority(f.authorityFile(B).readBytes()).currentRemoteJobId)
            f.dao.upsertJob(current)
            f.failARemotely = true
            assertEquals(androidx.work.ListenableWorker.Result.success(), f.runWorkerLoop())
            assertEquals(listOf("remote-$VIDEO_ID", "remote-$B"), f.statusIds.toList())
        }
    }

    @Test fun queuedOnlySuccessorDoesNotPreventRetryAndSimultaneousAcceptedRetriesAdmitOnlyOneOwner() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, true, 1L)
            val b = f.job(B, false, 2L)
            f.dao.upsertJob(projectSourceJob(a).copy(state = "FAILED"))
            f.repository.retry(f.root.authority(), a.jobId).getOrThrow()
            assertEquals(a.jobId, (f.playlist.reconcilePreparationOwner(f.root) as PreparationOwnerDecision.Separation).jobId)
            val acceptedB = transitionPortableSourceJob(transitionPortableSourceJob(b, SourceJobTransition.ENTER_SUBMISSION, 3L),
                SourceJobTransition.ACKNOWLEDGE_REMOTE, 4L, "remote-$B")
            f.authorityFile(B).writeBytes(acceptedB.toBytes())
            f.dao.upsertJob(projectSourceJob(a).copy(state = "FAILED"))
            f.dao.upsertJob(projectSourceJob(acceptedB).copy(state = "FAILED"))
            val attempts = listOf(a.jobId, acceptedB.jobId).map { id -> async(Dispatchers.IO) { f.repository.retry(f.root.authority(), id) } }.awaitAll()
            assertEquals(1, attempts.count { it.isSuccess })
            assertEquals(1, f.dao.automaticPreparationOwnerJobs().size)
            assertEquals(setOf("remote-$VIDEO_ID", "remote-$B"), listOf(f.authorityFile(VIDEO_ID), f.authorityFile(B))
                .map { parsePortableSourceJobAuthority(it.readBytes()).currentRemoteJobId }.toSet())
        }
    }

    @Test fun deletionBetweenWorkerSelectionAndOwnershipRetiresOnlyTheRemovedJob() = runBlocking<Unit> {
        withFixture { f ->
            val a = f.job(VIDEO_ID, false, 1L)
            val b = f.job(B, false, 2L)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val visited = CopyOnWriteArrayList<String>()
            f.repository.beforeProcessOwnershipForTest = { job ->
                visited += job.id
                if (job.id == a.jobId) { entered.complete(Unit); withTimeout(10_000L) { release.await() } }
            }
            val previous = awaitApplicationWorkManager(targetContext)
            val runs = AtomicInteger()
            val executions = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
            val factory = object : WorkerFactory() {
                override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? {
                    if (name != MvsepPreparationWorker::class.java.name) return null
                    // Replace application/foreground construction only; run the worker's production owner loop.
                    return object : CoroutineWorker(context, parameters) {
                        override suspend fun doWork(): Result {
                            f.workerJobs += currentCoroutineContext().job
                            currentCoroutineContext().ensureActive()
                            runs.incrementAndGet()
                            return f.runWorkerLoop()
                        }
                    }.also { executions.putIfAbsent(parameters.id, CompletableDeferred()) }
                }
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(targetContext,
                Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
            val manager = WorkManager.getInstance(targetContext) as WorkManagerImpl
            val listener = object : ExecutionListener {
                override fun onExecuted(id: WorkGenerationalId, needsReschedule: Boolean) {
                    executions[UUID.fromString(id.workSpecId)]?.complete(Unit)
                }
            }
            manager.processor.addExecutionListener(listener)
            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(targetContext))
            var failure: Throwable? = null
            try {
                val outgoing = MvsepPreparationWorker.initialRequest(f.root)
                awaitWorkManagerResult(MvsepPreparationWorker.enqueueRequest(manager, outgoing, false).result)
                driver.setAllConstraintsMet(outgoing.id)
                withTimeout(5_000L) { entered.await() }
                val successor = MvsepPreparationWorker.initialRequest(f.root)
                awaitWorkManagerResult(MvsepPreparationWorker.enqueueRequest(manager, successor, true).result)
                assertEquals(WorkInfo.State.BLOCKED, awaitWorkManagerResult(manager.getWorkInfoById(successor.id))?.state)
                f.deletion.deletePermanently(f.root, VIDEO_ID).getOrThrow()
                assertNull(f.dao.findJobById(a.jobId))
                release.complete(Unit)
                withTimeout(10_000L) { manager.getWorkInfoByIdFlow(outgoing.id).first { it?.state == WorkInfo.State.SUCCEEDED } }
                driver.setAllConstraintsMet(successor.id)
                withTimeout(10_000L) { manager.getWorkInfoByIdFlow(successor.id).first { it?.state == WorkInfo.State.SUCCEEDED } }
                assertEquals(2, runs.get())
                assertEquals(listOf(a.jobId, b.jobId, b.jobId), visited.toList())
                assertFalse(File(providerRoot(), VIDEO_ID).exists())
                assertEquals(setOf(VIDEO_ID), f.bindings.confirmedDeletedPackageIds(f.root))
                assertEquals(b.jobId, (f.playlist.reconcilePreparationOwner(f.root) as PreparationOwnerDecision.Separation).jobId)
                assertEquals("CREATED", f.dao.findJobById(b.jobId)?.state)
            } catch (error: Throwable) { failure = error }
            finally {
                release.complete(Unit)
                var cancellationSettled = false
                try {
                    withContext(NonCancellable) {
                        awaitWorkManagerResult(manager.cancelUniqueWork(MvsepPreparationWorker.UNIQUE_WORK).result)
                    }
                    cancellationSettled = true
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                var retired = false
                try {
                    withContext(NonCancellable) {
                        f.workerJobs.forEach { it.cancel() }
                        withTimeout(10_000L) {
                            executions.values.forEach { it.await() }
                            f.workerJobs.toList().joinAll()
                        }
                    }
                    retired = true
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                try { if (cancellationSettled && retired) WorkManagerTestInitHelper.closeWorkDatabase() }
                catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                } finally {
                    try { manager.processor.removeExecutionListener(listener) }
                    finally {
                        WorkManagerImpl.setDelegate(previous)
                        f.repository.beforeProcessOwnershipForTest = null
                    }
                }
            }
            failure?.let { throw it }
        }
    }

    @Test fun removedBeforeReadIsTerminalButAcceptedAndUnknownJobsCannotBeDeleted() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, false, 1L)
            f.deletion.deletePermanently(f.root, VIDEO_ID).getOrThrow()
            assertEquals(SeparationWorkResult.TERMINAL_FAILURE, f.repository.process(a.jobId, f.root))
            val b = f.job(B, true, 2L)
            val bytes = f.authorityFile(B).readBytes()
            assertTrue(f.deletion.deletePermanently(f.root, B).isFailure)
            assertArrayEquals(bytes, f.authorityFile(B).readBytes())
            assertNotNull(f.dao.findJobById(b.jobId))
            val unknown = transitionPortableSourceJob(createPortableSourceJobAuthority(f.root.libraryId, B,
                b.sourceVersionId, b.sourceSha256, b.plan, 3L), SourceJobTransition.ENTER_SUBMISSION, 4L).let {
                transitionPortableSourceJob(it, SourceJobTransition.MARK_SUBMISSION_UNKNOWN, 5L)
            }
            f.authorityFile(B).writeBytes(unknown.toBytes()); f.dao.upsertJob(projectSourceJob(unknown))
            assertTrue(f.deletion.deletePermanently(f.root, B).isFailure)
            assertArrayEquals(unknown.toBytes(), f.authorityFile(B).readBytes())
        }
    }

    @Test fun workerOwnershipFinishesBeforeDeletionAndFailedDeletionPreservesTheJob() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, false, 1L)
            val before = f.authorityFile(VIDEO_ID).readBytes()
            val failedDelete = f.deletion.deletePermanently(f.root, VIDEO_ID) {
                throw java.io.IOException("Isolated pre-commit provider unavailability")
            }
            assertTrue(failedDelete.exceptionOrNull() is java.io.IOException)
            assertNotNull(f.dao.findJobById(a.jobId))
            assertArrayEquals(before, f.authorityFile(VIDEO_ID).readBytes())
            assertTrue(f.bindings.confirmedDeletedPackageIds(f.root).isEmpty())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val accountActive = AtomicBoolean()
            f.beforeValidate = {
                accountActive.set(true); entered.complete(Unit)
                try { withTimeout(10_000L) { release.await() } } finally { accountActive.set(false) }
            }
            val worker = async(Dispatchers.IO) { f.repository.process(a.jobId, f.root) }
            var deletion: Deferred<Result<PermanentDeletionOutcome>>? = null
            try {
                withTimeout(5_000L) { entered.await() }
                deletion = async(Dispatchers.IO) {
                    f.deletion.deletePermanently(f.root, VIDEO_ID) {
                        assertFalse("Deletion entered while the worker owned the song/preparation locks", accountActive.get())
                    }
                }
                release.complete(Unit)
                assertEquals(SeparationWorkResult.WAITING_FOR_ACCOUNT, withTimeout(10_000L) { worker.await() })
                withTimeout(10_000L) { deletion.await() }.getOrThrow()
                assertNull(f.dao.findJobById(a.jobId))
                assertFalse(File(providerRoot(), VIDEO_ID).exists())
            } finally {
                release.complete(Unit)
                withTimeout(10_000L) { worker.cancelAndJoin(); deletion?.cancelAndJoin() }
                f.beforeValidate = null
            }
        }
    }

    @Test fun preparationRechecksRootAndKeepsGenuineAuthorityCorruptionVisible() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, false, 1L)
            val row = f.dao.findJobById(a.jobId)
            val before = f.authorityFile(VIDEO_ID).readBytes()
            f.repository.beforeProcessOwnershipForTest = {
                f.roots.reportRuntimeAccessFailure(f.root, SecurityException("Isolated selected-root grant loss"))
            }
            assertTrue(runCatching { f.repository.process(a.jobId, f.root) }.isFailure)
            assertEquals(row, f.dao.findJobById(a.jobId))
            assertArrayEquals(before, f.authorityFile(VIDEO_ID).readBytes())
            f.repository.beforeProcessOwnershipForTest = null
            f.roots.reconnect()
            val corrupt = "{broken-authority".toByteArray(Charsets.UTF_8)
            f.authorityFile(VIDEO_ID).writeBytes(corrupt)
            val failure = runCatching { f.repository.process(a.jobId, f.root) }.exceptionOrNull()
            assertNotNull("A corrupt authority must not become a deleted-selection success", failure)
            assertEquals(row, f.dao.findJobById(a.jobId))
            assertArrayEquals(corrupt, f.authorityFile(VIDEO_ID).readBytes())
        }
    }

    @Test fun preSubmissionFailureCanRetryAsQueuedWorkBehindAnAcceptedOwner() = runBlocking {
        withFixture { f ->
            val a = f.job(VIDEO_ID, false, 1L)
            val b = f.job(B, true, 2L)
            f.dao.upsertJob(projectSourceJob(a).copy(state = "FAILED"))
            f.repository.retry(f.root.authority(), a.jobId).getOrThrow()
            assertEquals("CREATED", f.dao.findJobById(a.jobId)?.state)
            assertEquals(b.jobId, (f.playlist.reconcilePreparationOwner(f.root) as PreparationOwnerDecision.Separation).jobId)
            assertNull(parsePortableSourceJobAuthority(f.authorityFile(VIDEO_ID).readBytes()).currentRemoteJobId)
            val downloading = transitionPortableSourceJob(b, SourceJobTransition.BEGIN_DOWNLOAD, 5L)
            f.authorityFile(B).writeBytes(downloading.toBytes()); f.dao.upsertJob(projectSourceJob(downloading))
            val acceptedA = transitionPortableSourceJob(transitionPortableSourceJob(a,
                SourceJobTransition.ENTER_SUBMISSION, 6L), SourceJobTransition.ACKNOWLEDGE_REMOTE, 7L, "remote-$VIDEO_ID")
            f.authorityFile(VIDEO_ID).writeBytes(acceptedA.toBytes())
            f.dao.upsertJob(projectSourceJob(acceptedA).copy(state = "FAILED"))
            assertTrue(f.repository.retry(f.root.authority(), a.jobId).isFailure)
            assertEquals("FAILED", f.dao.findJobById(a.jobId)?.state)
            assertArrayEquals(downloading.toBytes(), f.authorityFile(B).readBytes())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        resetProvider(); seedDirectMedia(withEnrichment = true); grantProviderTree()
        val original = File(providerRoot(), "$VIDEO_ID/${SelectedLibraryContract.ORIGINAL}").readBytes()
        val second = File(providerRoot(), B).apply { check(mkdir()) }
        File(second, SelectedLibraryContract.ORIGINAL).writeBytes(original)
        seedCompleteOpusMetadata(second, B, original)
        val f = Fixture()
        try { f.open(); block(f) } finally { f.close() }
    }

    private inner class Fixture {
        val context = IsolatedQaContext(targetContext).also { isolatedContext = it }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.libraryDao()
        val mutations = PackageMutationCoordinator(context)
        val roots = LibraryRootManager(context, mutations, dao)
        val bindings = SourceBindingRegistry(context, roots, mutations, dao)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val workerJobs = CopyOnWriteArrayList<Job>()
        val background = BackgroundWorkCoordinator()
        val external = ExternalLibraryRepository(context, roots, dao, bindings, mutations, background, scope = scope)
        val songWork = SongWorkCoordinator()
        val preferences = StemwisePreferences(context, database = database)
        val network = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        val engine = YoutubeDlEngine(context, network, roots)
        var beforeValidate: (suspend () -> Unit)? = null
        val account = object : MvsepAccountAccess {
            override val state = MutableStateFlow(MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED))
            override suspend fun validate(expectedCredential: MvsepCredential?): Result<Unit> {
                beforeValidate?.invoke()
                return Result.failure(IllegalStateException("Fixture has no submission credential"))
            }
            override fun credential(): MvsepCredential? = null
            override fun isCurrent(credential: MvsepCredential) = false
            override fun apiBase() = error("No provider submission")
        }
        val statusIds = CopyOnWriteArrayList<String>()
        var beforeStatus: (suspend () -> Unit)? = null
        var statusFailure: Throwable? = null
        var failA = false; var failARemotely = false; var failBRemotely = false
        val client = object : MvsepServiceClient {
            override suspend fun disablePremium(token: String) = error("No new submission")
            override suspend fun status(remoteJobId: String): MvsepRemoteStatus {
                statusIds += remoteJobId
                beforeStatus?.invoke()
                statusFailure?.let { throw it }
                if (remoteJobId == "remote-$VIDEO_ID" && failA) throw java.net.SocketTimeoutException("Isolated provider status timeout")
                val failed = remoteJobId == "remote-$VIDEO_ID" && failARemotely || remoteJobId == "remote-$B" && failBRemotely
                return MvsepRemoteStatus(if (failed) "failed" else "processing", null, emptyList())
            }
            override suspend fun download(remote: MvsepRemoteFile, target: File) = error("No model media")
        }
        val publisher = CanonicalPackagePublisher(context, roots, dao, mutations, bindings, external, background)
        val repository = SeparationRepository(context, dao, account, roots, mutations, songWork, external, publisher,
            background, engine, network, client)
        val playlist = PlaylistRepository(context, database, dao, preferences, engine, songWork, roots, external, background, network)
        val importer = songImporter(context, roots, mutations, bindings, background, database)
        val imports = EnrichmentImportCoordinator(context, importer, external, background, roots, scope)
        val trash = ExternalPackageTrash(context, roots, mutations, bindings, dao)
        val deletion = PermanentDeletionCoordinator(trash, external, songWork)
        val cold = ColdOpenCoordinator(roots, external, playlist, preferences, trash, repository, imports, importer)
        val root get() = checkNotNull(roots.captureHandle())
        suspend fun open() {
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(GoldenQaDocumentsProvider.treeUri).getOrThrow()).getOrThrow()
            preferences.activateLibraryFromRoot(root.libraryId)
            external.reconcile(); imports.awaitStartupScratchReady()
        }
        fun authorityFile(id: String) = File(providerRoot(), "$id/${SelectedLibraryContract.SOURCE_JOB_AUTHORITY}")
        suspend fun job(id: String, accepted: Boolean, createdAt: Long): PortableSourceJobAuthority {
            val source = checkNotNull(dao.currentSource(id))
            val plan = MvsepSourcePlan(listOf("bass"), listOf(MvsepSourceOperation("mvsep-63", 63, emptyMap(),
                setOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum"), mapOf("bass" to "bass"))))
            var authority = createPortableSourceJobAuthority(root.libraryId, id, source.id, source.sha256, plan, createdAt)
            if (accepted) authority = transitionPortableSourceJob(transitionPortableSourceJob(authority,
                SourceJobTransition.ENTER_SUBMISSION, createdAt + 1L), SourceJobTransition.ACKNOWLEDGE_REMOTE, createdAt + 2L, "remote-$id")
            authorityFile(id).writeBytes(authority.toBytes()); dao.upsertJob(projectSourceJob(authority)); return authority
        }
        suspend fun runWorkerLoop(): ListenableWorker.Result {
            val ownedRoot = root
            return runPreparationOwnerLoop(ownedRoot, playlist, repository, background,
                requireOwnership = { roots.requireSameLibrary(ownedRoot) }, tokenStored = { false }, continuePending = {})
        }
        suspend fun close() {
            repository.beforeProcessOwnershipForTest = null
            try { withTimeout(10_000L) { cold.cancelAndJoin(); imports.cancelForLibraryChange(); external.retireScansForLibrarySwitch() } }
            finally {
                withTimeout(5_000L) { scope.coroutineContext[Job]?.cancelAndJoin() }
                if (workerJobs.all { it.isCompleted }) database.close()
                // A timed-out live worker retains its actual Room database for diagnosis.
            }
        }
    }
    private companion object { const val B = "abcdefghijk" }
}

private suspend fun <T> awaitWorkManagerResult(result: com.google.common.util.concurrent.ListenableFuture<T>): T =
    withTimeout(5_000L) { result.await() }
