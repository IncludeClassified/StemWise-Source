package app.stemwise.work

import android.content.Context
import app.stemwise.data.ColdOpenCoordinator
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.RootAuthority
import app.stemwise.data.StemwisePreferences
import app.stemwise.data.authority
import app.stemwise.integration.SeparationRepository
import app.stemwise.integration.SeparationRecoveryReview
import app.stemwise.suspendResult
import app.stemwise.launchRecoverable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface ManualTaskActions {
    fun enqueueSync(): Boolean
    fun updatePeriodicSync(configured: Boolean)
    suspend fun retrySeparation(expectedRoot: RootAuthority, jobId: String): Result<Unit>
    suspend fun reviewRecovery(expectedRoot: RootAuthority, jobId: String, remoteJobId: String): Result<SeparationRecoveryReview> =
        Result.failure(IllegalStateException("REMOTE_RECOVERY_UNSUPPORTED"))
    suspend fun recoverSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> =
        Result.failure(IllegalStateException("REMOTE_RECOVERY_UNSUPPORTED"))
    suspend fun refreshPreparationOwner(expectedRoot: RootAuthority): Result<Unit> = Result.success(Unit)
    suspend fun queueSources(expectedRoot: RootAuthority, songId: String,
        selectedIds: List<String>): Result<Boolean> = Result.failure(
        IllegalStateException("SOURCE_SELECTION_UNSUPPORTED"))
    fun enqueueSeparation(expectedRoot: RootAuthority, afterCurrent: Boolean = false): Boolean
}

internal class AndroidManualTaskActions(
    context: Context,
    private val separationRepository: SeparationRepository,
    private val roots: LibraryRootManager,
    private val coldOpen: ColdOpenCoordinator
) : ManualTaskActions {
    private val applicationContext = context.applicationContext

    override fun enqueueSync() = PlaylistSyncWorker.enqueueManual(applicationContext)

    override fun updatePeriodicSync(configured: Boolean) =
        PlaylistSyncWorker.updateSchedule(applicationContext, configured)

    override suspend fun retrySeparation(
        expectedRoot: RootAuthority,
        jobId: String
    ): Result<Unit> = separationRepository.retry(expectedRoot, jobId)

    override suspend fun reviewRecovery(expectedRoot: RootAuthority, jobId: String, remoteJobId: String) =
        separationRepository.reviewRecovery(expectedRoot, jobId, remoteJobId)

    override suspend fun recoverSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> =
        separationRepository.recoverSubmission(review, sourceConfirmed)

    override suspend fun refreshPreparationOwner(expectedRoot: RootAuthority): Result<Unit> = suspendResult {
        roots.requireSameLibrary(expectedRoot)
        coldOpen.refreshPreparationAdmission(expectedRoot)
    }.map { Unit }

    override suspend fun queueSources(expectedRoot: RootAuthority, songId: String,
        selectedIds: List<String>): Result<Boolean> = suspendResult {
        val handle = roots.captureHandle()?.takeIf { it.authority() == expectedRoot }
            ?: error("LIBRARY_CHANGED")
        separationRepository.queueSources(handle, songId, selectedIds).getOrThrow().requiresWorker
    }

    override fun enqueueSeparation(expectedRoot: RootAuthority, afterCurrent: Boolean) =
        MvsepPreparationWorker.enqueue(applicationContext, afterCurrent = afterCurrent, expectedRoot = expectedRoot)
}

class ManualTaskRetryFailure(val jobId: String, val cause: Throwable)

/** Owns user-started durable work after the UI hands off the request. */
class ManualTaskCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val taskGate: TaskWorkGate,
    private val actions: ManualTaskActions,
    private val captureReadyRoot: () -> RootAuthority?,
    private val isReadyRoot: (RootAuthority) -> Boolean,
    private val playlistConfigured: () -> Boolean = { false },
    private val captureRecoveryRoot: () -> RootAuthority? = captureReadyRoot
) {
    private val mutableRetryFailure = MutableStateFlow<ManualTaskRetryFailure?>(null)
    val retryFailure: StateFlow<ManualTaskRetryFailure?> = mutableRetryFailure.asStateFlow()

    fun consumeRetryFailure(expected: ManualTaskRetryFailure) {
        mutableRetryFailure.compareAndSet(expected, null)
    }

    private val rearmLock = Any()
    private var rearmedTaskGeneration = Long.MIN_VALUE
    private data class RecoveredSubmissionWake(val root: RootAuthority, val jobId: String)
    private var recoveredSubmissionWake: RecoveredSubmissionWake? = null
    private var recoveryWakeJob: Job? = null

    constructor(
        context: Context,
        separationRepository: SeparationRepository,
        roots: LibraryRootManager,
        coldOpen: ColdOpenCoordinator,
        preferences: StemwisePreferences
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        taskGate = AppTaskWorkGate.instance,
        actions = AndroidManualTaskActions(context, separationRepository, roots, coldOpen),
        captureReadyRoot = {
            roots.captureHandle()?.takeIf(coldOpen::isProducerAdmissionReady)?.authority()
        },
        isReadyRoot = { expected ->
            roots.captureHandle()
                ?.takeIf { it.authority() == expected }
                ?.let(coldOpen::isProducerAdmissionReady) == true
        },
        playlistConfigured = { preferences.playlist.value.configured },
        captureRecoveryRoot = { roots.captureHandle()?.authority() }
    )

    fun sync(): Boolean {
        var enqueued = false
        return taskGate.runIfActive { enqueued = actions.enqueueSync() } && enqueued
    }

    fun retrySeparation(jobId: String): Boolean {
        val (lease, expectedRoot) = acceptedRootAction() ?: return false
        mutableRetryFailure.value = null
        scope.launchRecoverable(onFailure = { error ->
            taskGate.runIfCurrent(lease) {
                if (isReadyRoot(expectedRoot)) mutableRetryFailure.value = ManualTaskRetryFailure(jobId, error)
            }
        }) {
            actions.retrySeparation(expectedRoot, jobId).getOrThrow()
            taskGate.runIfCurrent(lease) {
                if (isReadyRoot(expectedRoot)) actions.enqueueSeparation(expectedRoot, afterCurrent = true)
            }
        }
        return true
    }

    suspend fun retrySeparationAfterCleanup(jobId: String, cleanup: suspend () -> Unit): Boolean {
        val (lease, expectedRoot) = acceptedRootAction() ?: return false
        cleanup()
        if (!taskGate.isCurrent(lease) || !isReadyRoot(expectedRoot)) return false
        actions.retrySeparation(expectedRoot, jobId).getOrThrow()
        var enqueued = false
        taskGate.runIfCurrent(lease) {
            if (isReadyRoot(expectedRoot)) {
                enqueued = actions.enqueueSeparation(expectedRoot, afterCurrent = true)
            }
        }
        return enqueued
    }

    suspend fun reviewSeparationSubmission(jobId: String, remoteJobId: String, expectedRoot: RootAuthority): Result<SeparationRecoveryReview> {
        val lease = taskGate.acquireLease() ?: return Result.failure(IllegalStateException("Task is paused"))
        if (captureRecoveryRoot() != expectedRoot) return Result.failure(IllegalStateException("LIBRARY_CHANGED"))
        return actions.reviewRecovery(expectedRoot, jobId, remoteJobId).mapCatching {
            check(taskGate.isCurrent(lease) && captureRecoveryRoot() == expectedRoot) { "LIBRARY_CHANGED" }
            it
        }
    }

    suspend fun recoverSeparationSubmission(review: SeparationRecoveryReview, sourceConfirmed: Boolean): Result<Unit> {
        if (!sourceConfirmed) return Result.failure(IllegalStateException("SOURCE_CONFIRMATION_REQUIRED"))
        val expectedRoot = review.root
        val lease = taskGate.acquireLease() ?: return Result.failure(IllegalStateException("Task is paused"))
        val acceptedRoot = captureRecoveryRoot()
        if (acceptedRoot != expectedRoot) return Result.failure(IllegalStateException("LIBRARY_CHANGED"))
        return actions.recoverSubmission(review, sourceConfirmed).mapCatching {
            check(taskGate.isCurrent(lease) && captureRecoveryRoot() == expectedRoot) { "LIBRARY_CHANGED" }
            synchronized(rearmLock) {
                recoveredSubmissionWake = RecoveredSubmissionWake(expectedRoot, review.jobId)
            }
            resumeRecoveredSubmissionWake(lease, RecoveredSubmissionWake(expectedRoot, review.jobId))
        }
    }

    private fun resumeRecoveredSubmissionWake(lease: TaskWorkGate.Lease, pending: RecoveredSubmissionWake) {
        val root = pending.root
        recoveryWakeJob?.cancel()
        recoveryWakeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (taskGate.isCurrent(lease) && captureRecoveryRoot() == root &&
                synchronized(rearmLock) { recoveredSubmissionWake == pending }) {
                val failure = try { actions.refreshPreparationOwner(root).exceptionOrNull() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { error }
                currentCoroutineContext().ensureActive()
                if (failure is CancellationException) throw failure
                if (failure != null && failure !is java.io.IOException) {
                    if (taskGate.isCurrent(lease) && captureRecoveryRoot() == root)
                        mutableRetryFailure.value = ManualTaskRetryFailure(pending.jobId, failure)
                    return@launch
                }
                if (failure == null && isReadyRoot(root)) {
                    val enqueued = synchronized(rearmLock) {
                        var accepted = false
                        if (recoveredSubmissionWake == pending) taskGate.runIfCurrent(lease) {
                            if (isReadyRoot(root)) accepted = actions.enqueueSeparation(root, afterCurrent = true)
                        }
                        if (accepted) recoveredSubmissionWake = null
                        accepted
                    }
                    if (enqueued) return@launch
                }
                delay(1_000L)
            }
        }
    }

    suspend fun requestSources(songId: String, selectedIds: List<String>): Result<Unit> {
        val (lease, expectedRoot) = acceptedRootAction()
            ?: return Result.failure(IllegalStateException("LIBRARY_CHANGED"))
        val selection = selectedIds.toList()
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            actions.queueSources(expectedRoot, songId, selection).mapCatching { requiresWorker ->
                check(taskGate.isCurrent(lease) && isReadyRoot(expectedRoot)) { "LIBRARY_CHANGED" }
                if (requiresWorker) actions.enqueueSeparation(expectedRoot, afterCurrent = true)
            }
        }.await()
    }

    private fun acceptedRootAction(): Pair<TaskWorkGate.Lease, RootAuthority>? {
        val lease = taskGate.acquireLease() ?: return null
        val root = captureReadyRoot() ?: return null
        return if (taskGate.isCurrent(lease)) lease to root else null
    }

    internal fun onLibrarySwitchStarted() {
        // This generation is independent of task removal. Exact-root repository mutations remain
        // protected even when a callback deliberately ignores coroutine cancellation.
        taskGate.closeForLibrarySwitch()
        synchronized(rearmLock) { recoveredSubmissionWake = null }
        mutableRetryFailure.value = null
        scope.coroutineContext.cancelChildren()
    }

    internal fun onLibrarySwitchFinished() {
        taskGate.openAfterLibrarySwitch()
        rearmCurrentReadyTask()
    }

    internal fun onTaskRemoved(): Boolean {
        // Invalidate first: cancellation-cooperative and cancellation-ignoring repository calls
        // are both prevented from handing durable work back to WorkManager afterwards.
        val accepted = taskGate.closeForTaskRemoval()
        mutableRetryFailure.value = null
        scope.coroutineContext.cancelChildren()
        return accepted
    }

    internal fun onTaskAvailable() {
        taskGate.openForTask()
        rearmCurrentReadyTask()
        val pending = synchronized(rearmLock) { recoveredSubmissionWake }
        if (pending != null && captureRecoveryRoot() == pending.root) {
            taskGate.acquireLease()?.let { resumeRecoveredSubmissionWake(it, pending) }
        }
    }

    /** Called after every exact cold-open session that successfully reaches Ready. */
    internal fun onColdOpenFinished() {
        rearmCurrentReadyTask()
    }

    private fun rearmCurrentReadyTask() {
        val lease = taskGate.acquireLease() ?: return
        val expectedRoot = captureReadyRoot() ?: return
        if (!isReadyRoot(expectedRoot)) return
        synchronized(rearmLock) {
            val recovered = recoveredSubmissionWake?.root == expectedRoot
            if (rearmedTaskGeneration == lease.generation && !recovered) return
            var enqueued = false
            val applied = taskGate.runIfCurrent(lease) {
                actions.updatePeriodicSync(playlistConfigured())
                enqueued = actions.enqueueSeparation(expectedRoot, afterCurrent = recovered)
            }
            if (applied && enqueued) {
                rearmedTaskGeneration = lease.generation
                if (recovered) recoveredSubmissionWake = null
            }
        }
    }
}
