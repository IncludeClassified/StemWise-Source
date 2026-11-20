package app.stemwise.work

import android.content.Context
import androidx.concurrent.futures.await
import androidx.test.core.app.ApplicationProvider
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
import app.stemwise.StemwiseApplication
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationWorkerHandoffInstrumentationTest {
    @Test fun applicationRecoveryRetiresBeforeCapturingTheWorkManagerDelegate() = runBlocking<Unit> {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context as StemwiseApplication
        val coldOpen = application.services.coldOpenCoordinator
        withTimeout(30_000L) {
            application.workerInitialization.await()
            coldOpen.awaitCompletion()
        }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val previousHook = coldOpen.beforePreparationArbitrationForTest
        var capture: kotlinx.coroutines.Deferred<WorkManagerImpl>? = null
        try {
            coldOpen.beforePreparationArbitrationForTest = {
                entered.complete(Unit)
                check(release.await(10L, TimeUnit.SECONDS)) { "Application recovery release timed out" }
            }
            assertNotNull(coldOpen.start())
            withTimeout(5_000L) { entered.await() }
            val previous = WorkManager.getInstance(context)
            capture = async(start = CoroutineStart.UNDISPATCHED) { awaitApplicationWorkManager(context) }
            assertFalse("The temporary delegate must not overlap the application's recovery enqueue", capture.isCompleted)
            assertTrue(previous === WorkManager.getInstance(context))
            release.countDown()
            assertTrue(previous === withTimeout(30_000L) { capture.await() })
        } finally {
            release.countDown()
            try {
                withContext(NonCancellable) {
                    withTimeout(30_000L) {
                        coldOpen.awaitCompletion()
                        capture?.cancelAndJoin()
                    }
                }
            } finally {
                coldOpen.beforePreparationArbitrationForTest = previousHook
            }
        }
    }

    @Test fun cancellationAwaitDoesNotOccupyItsOwningSerialExecutor() = runBlocking<Unit> {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = awaitApplicationWorkManager(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        var failure: Throwable? = null
        try {
            withContext(manager.workTaskExecutor.serialTaskExecutor.asCoroutineDispatcher()) {
                awaitResult(manager.cancelAllWork().result)
            }
        } catch (error: Throwable) { failure = error }
        finally {
            var retired = false
            try {
                withContext(NonCancellable + Dispatchers.IO) { awaitResult(manager.cancelAllWork().result) }
                retired = true
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try { if (retired) WorkManagerTestInitHelper.closeWorkDatabase() }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally { WorkManagerImpl.setDelegate(previous) }
        }
        failure?.let { throw it }
    }

    @Test fun delayedTaskRemovalAcknowledgementCannotCancelRearmedPreparation() = runBlocking<Unit> {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = awaitApplicationWorkManager(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val root = RootWorkIdentity("same-library", "content://fixture/tree/same-root", 7L)
        var failure: Throwable? = null
        var stage = "enqueue-old"
        try {
            val old = MvsepPreparationWorker.initialRequest(root)
            awaitResult(MvsepPreparationWorker.enqueueRequest(manager, old, false).result)
            stage = "cancel-old"
            val cancellation = MvsepPreparationWorker.cancel(context)
            val acknowledgeOld = awaitTaskRemovalOperations(listOf(cancellation))
            awaitResult(cancellation.result)
            stage = "read-old-state"
            assertEquals(WorkInfo.State.CANCELLED, awaitResult(manager.getWorkInfoById(old.id))?.state)
            stage = "enqueue-replacement"
            val replacement = MvsepPreparationWorker.initialRequest(root)
            awaitResult(MvsepPreparationWorker.enqueueRequest(manager, replacement, false).result)
            stage = "read-replacement-before-acknowledgement"
            assertEquals(WorkInfo.State.ENQUEUED, awaitResult(manager.getWorkInfoById(replacement.id))?.state)
            stage = "acknowledge-old"
            withTimeout(5_000L) { acknowledgeOld(); acknowledgeOld() }
            stage = "read-replacement-after-acknowledgement"
            assertEquals(WorkInfo.State.ENQUEUED, awaitResult(manager.getWorkInfoById(replacement.id))?.state)
        } catch (error: Throwable) {
            failure = if (error is TimeoutCancellationException)
                AssertionError("Task-removal handoff timed out: $stage", error) else error
        }
        finally {
            var retired = false
            try {
                withContext(NonCancellable) { awaitResult(manager.cancelAllWork().result) }
                retired = true
            } catch (error: Throwable) {
                val cleanup = AssertionError("Task-removal cleanup did not settle", error)
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            }
            try { if (retired) WorkManagerTestInitHelper.closeWorkDatabase() }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            } finally { WorkManagerImpl.setDelegate(previous) }
        }
        failure?.let { throw it }
    }

    @Test fun retryDuringTerminalWorkerCompletionRunsItsSerializedSuccessorWithoutAnotherWake() = runBlocking<Unit> {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = awaitApplicationWorkManager(context)
        val runs = AtomicInteger()
        val createdWorkers = ConcurrentHashMap<UUID, ListenableWorker>()
        val bodyJobs = ConcurrentHashMap<UUID, Job>()
        val executions = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
        val gates = ConcurrentHashMap<UUID, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()
        val root = RootWorkIdentity("same-library", "content://fixture/tree/same-root", 7L)
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker? {
                if (name != MvsepPreparationWorker::class.java.name) return null
                return object : CoroutineWorker(context, parameters) {
                    override suspend fun doWork(): Result {
                        bodyJobs[id] = checkNotNull(currentCoroutineContext()[Job])
                        assertEquals(root.libraryId, MvsepPreparationWorker.rootExpectation(inputData)?.libraryId)
                        assertEquals(root.epoch, MvsepPreparationWorker.rootExpectation(inputData)?.epoch)
                        runs.incrementAndGet()
                        gates[id]?.let { (entered, release) ->
                            entered.complete(Unit)
                            withTimeout(5_000L) { release.await() }
                        }
                        return Result.success()
                    }
                }.also {
                    executions.putIfAbsent(parameters.id, CompletableDeferred())
                    createdWorkers[parameters.id] = it
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context) as WorkManagerImpl
        val listener = object : ExecutionListener {
            override fun onExecuted(id: WorkGenerationalId, needsReschedule: Boolean) {
                executions.computeIfAbsent(UUID.fromString(id.workSpecId)) { CompletableDeferred() }.complete(Unit)
            }
        }
        manager.processor.addExecutionListener(listener)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        val constraintsAccepted = linkedSetOf<UUID>()
        fun describe(info: WorkInfo?) = info?.let {
            "${it.id}/${it.state}/attempts=${it.runAttemptCount}/stop=${it.stopReason}"
        } ?: "absent"
        suspend fun workInfo(id: UUID?) = if (id == null) "not-created" else try {
            describe(awaitResult(manager.getWorkInfoById(id), 1_000L))
        } catch (error: Exception) { "lookup-failed:${error.javaClass.simpleName}" }
        suspend fun awaitStage(stage: String, afterCurrent: Boolean, outgoing: UUID, retry: UUID? = null,
            action: suspend () -> Unit) {
            try { withTimeout(5_000) { action() } }
            catch (timeout: TimeoutCancellationException) {
                val unique = try {
                    awaitResult(manager.getWorkInfosForUniqueWork(MvsepPreparationWorker.UNIQUE_WORK), 1_000L)
                        .joinToString { describe(it) }
                } catch (error: Exception) { "lookup-failed:${error.javaClass.simpleName}" }
                val workers = createdWorkers.entries.sortedBy { it.key.toString() }.joinToString { (id, worker) ->
                    "$id/stopped=${worker.isStopped}/stop=${worker.stopReason}"
                }
                throw AssertionError("Preparation handoff timeout: stage=$stage; afterCurrent=$afterCurrent; " +
                    "outgoing=$outgoing/${workInfo(outgoing)}; retry=$retry/${workInfo(retry)}; " +
                    "runs=${runs.get()}; constraintsAccepted=$constraintsAccepted; " +
                    "created=[$workers]; unique=[$unique]", timeout)
            }
        }
        var failure: Throwable? = null
        try {
            for (afterCurrent in listOf(false, true)) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                runs.set(0)
                val outgoing = MvsepPreparationWorker.initialRequest(root)
                gates[outgoing.id] = entered to release
                awaitResult(MvsepPreparationWorker.enqueueRequest(manager, outgoing, false).result)
                assertNotNull("TestDriver outgoing request was not admitted",
                    awaitResult(manager.getWorkInfoById(outgoing.id), 1_000L))
                driver.setAllConstraintsMet(outgoing.id)
                constraintsAccepted += outgoing.id
                awaitStage("outgoing-entered", afterCurrent, outgoing.id) { entered.await() }
                val retry = MvsepPreparationWorker.initialRequest(root)
                awaitResult(MvsepPreparationWorker.enqueueRequest(manager, retry, afterCurrent).result)
                if (afterCurrent) assertEquals(WorkInfo.State.BLOCKED,
                    awaitResult(manager.getWorkInfoById(retry.id))?.state)
                else assertNull("KEEP reproduces the lost wake-up while the old worker completes",
                    awaitResult(manager.getWorkInfoById(retry.id)))
                release.complete(Unit)
                awaitStage("outgoing-succeeded", afterCurrent, outgoing.id, retry.id) {
                    manager.getWorkInfoByIdFlow(outgoing.id).first { it?.state == WorkInfo.State.SUCCEEDED }
                }
                if (afterCurrent) {
                    assertNotNull("TestDriver successor request was not admitted",
                        awaitResult(manager.getWorkInfoById(retry.id), 1_000L))
                    driver.setAllConstraintsMet(retry.id)
                    constraintsAccepted += retry.id
                    awaitStage("retry-succeeded", afterCurrent, outgoing.id, retry.id) {
                        manager.getWorkInfoByIdFlow(retry.id).first { it?.state == WorkInfo.State.SUCCEEDED }
                    }
                }
                withTimeout(5_000L) {
                    createdWorkers.keys.forEach { executions.getValue(it).await() }
                    bodyJobs.values.toList().joinAll()
                }
                assertEquals(if (afterCurrent) 2 else 1, runs.get())
            }
        } catch (error: Throwable) { failure = error }
        finally {
            gates.values.forEach { it.second.complete(Unit) }
            var cancellationSettled = false
            var retired = false
            try {
                withContext(NonCancellable) { awaitResult(manager.cancelUniqueWork(MvsepPreparationWorker.UNIQUE_WORK).result) }
                cancellationSettled = true
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                withContext(NonCancellable) {
                    bodyJobs.values.forEach { it.cancel() }
                    withTimeout(5_000L) {
                        createdWorkers.keys.forEach { executions.getValue(it).await() }
                        bodyJobs.values.toList().joinAll()
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
                finally { WorkManagerImpl.setDelegate(previous) }
            }
        }
        failure?.let { throw it }
    }
}

internal suspend fun awaitApplicationWorkManager(context: Context): WorkManagerImpl {
    val application = context.applicationContext as StemwiseApplication
    // The ready listener enqueues through the global delegate; retire it before replacing that delegate.
    withTimeout(30_000L) {
        application.workerInitialization.await()
        application.services.coldOpenCoordinator.awaitCompletion()
    }
    return WorkManager.getInstance(context) as WorkManagerImpl
}

private suspend fun <T> awaitResult(result: ListenableFuture<T>, timeoutMs: Long = 5_000L): T =
    withTimeout(timeoutMs) { result.await() }
