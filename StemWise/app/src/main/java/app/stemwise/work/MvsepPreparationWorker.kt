package app.stemwise.work

import android.app.NotificationChannel
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stemwise.MainActivity
import app.stemwise.R
import app.stemwise.StemwiseApplication
import app.stemwise.data.ColdOpenProducerAdmission
import app.stemwise.data.RootHandle
import app.stemwise.data.RootAuthority
import app.stemwise.data.authority
import app.stemwise.data.PreparationOwnerDecision
import app.stemwise.integration.SeparationWorkResult
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MvsepPreparationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val application = appContext as StemwiseApplication
    private val services get() = application.services

    override suspend fun doWork(): Result {
        val monitor = TaskRemovalMonitor.armForWorker(applicationContext, id.toString())
        return try {
            retryUnavailablePreparationRead { runPreparation() }
        } finally {
            monitor.close()
        }
    }

    private suspend fun runPreparation(): Result {
        val expectedRoot = rootExpectation(inputData) ?: return Result.success()
        application.workerInitialization.await()
        val rootState = services.libraryRootManager.state.value
        val candidateRoot = services.libraryRootManager.captureHandle(rootState)
        when (workerRootAdmission(
            expected = expectedRoot,
            liveRoot = candidateRoot?.toWorkIdentity(),
            configuredLibraryId = rootState.libraryId,
            configuredTreeUri = rootState.treeUri?.toString(),
            configuredEpoch = rootState.epoch,
            configuredErrorCode = rootState.errorCode
        )) {
            WorkerRootAdmission.RETRY -> return Result.retry()
            WorkerRootAdmission.COMPLETE -> return Result.success()
            WorkerRootAdmission.RUN -> Unit
        }
        val rootHandle = checkNotNull(candidateRoot)
        val coldOpen = services.coldOpenCoordinator
        when (coldOpen.producerAdmission(rootHandle)) {
            ColdOpenProducerAdmission.RETRY -> return Result.retry()
            ColdOpenProducerAdmission.COMPLETE -> return Result.success()
            ColdOpenProducerAdmission.RUN -> Unit
        }
        val owner = services.playlistRepository.reconcilePreparationOwner(rootHandle)
        if (owner !is PreparationOwnerDecision.Separation) {
            return Result.success()
        }
        fun requireOwnership() {
            services.libraryRootManager.requireSameLibrary(rootHandle)
        }
        enterForegroundOrRetry()?.let { return it }
        // A background start may be denied before this foreground
        // handoff, so retry the notification-free Recents monitor afterwards.
        TaskRemovalMonitor.ensureStartedForOwners(applicationContext)
        requireOwnership()
        return runPreparationOwnerLoop(rootHandle, services.playlistRepository, services.separationRepository,
            services.backgroundWorkCoordinator, ::requireOwnership,
            tokenStored = { services.mvsepAccount.state.value.tokenStored },
            continuePending = { delay -> continueAfter(applicationContext, rootHandle, delay, TimeUnit.MILLISECONDS) })
    }

    internal suspend fun enterForegroundOrRetry(): Result? = try {
        setForeground(notification())
        null
    } catch (_: ForegroundServiceStartNotAllowedException) {
        Result.retry()
    }

    private fun notification(): ForegroundInfo {
        createChannel()
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Building your stem library")
            .setContentText("Preparing MVSEP stems")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Stem separation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "User-approved stem processing"
                }
            )
    }

    companion object {
        internal const val UNIQUE_WORK = "stemwise-mvsep-preparation-v2"
        private const val KEY_LIBRARY_ID = "library_id"
        private const val KEY_TREE_URI_IDENTITY = "tree_uri_sha256"
        private const val KEY_ROOT_EPOCH = "root_epoch"
        private const val CHANNEL_ID = "stemwise-separation"
        private const val NOTIFICATION_ID = 4103

        fun enqueue(
            context: Context,
            afterCurrent: Boolean = false,
            expectedRoot: RootAuthority? = null
        ): Boolean {
            var enqueued = false
            AppTaskWorkGate.instance.runIfActive {
                val app = context.applicationContext as? StemwiseApplication
                    ?: return@runIfActive
                val handle = app.services.libraryRootManager.captureHandle()
                    ?: return@runIfActive
                if (expectedRoot != null && handle.authority() != expectedRoot) {
                    return@runIfActive
                }
                if (!app.services.coldOpenCoordinator.isProducerAdmissionReady(handle)) {
                    return@runIfActive
                }
                if (!app.services.playlistRepository.preparationOwnerAllowsAutomaticEnqueue(handle)) {
                    return@runIfActive
                }
                val request = initialRequest(handle)
                enqueueRequest(WorkManager.getInstance(context), request, afterCurrent)
                enqueued = true
            }
            return enqueued
        }

        internal fun enqueueRequest(manager: WorkManager, request: OneTimeWorkRequest, afterCurrent: Boolean) =
            manager.enqueueUniqueWork(UNIQUE_WORK,
                if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)

        private fun continueAfter(
            context: Context,
            handle: RootHandle,
            delay: Long,
            unit: TimeUnit
        ) {
            AppTaskWorkGate.instance.runIfActive {
                val app = context.applicationContext as? StemwiseApplication
                    ?: return@runIfActive
                val current = app.services.libraryRootManager.captureHandle()
                    ?: return@runIfActive
                if (current.authority() != handle.authority()) return@runIfActive
                if (!app.services.coldOpenCoordinator.isProducerAdmissionReady(current)) {
                    return@runIfActive
                }
                if (!app.services.playlistRepository.preparationOwnerAllowsAutomaticEnqueue(current)) {
                    return@runIfActive
                }
                val request = continuationRequest(handle, delay, unit)
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )
            }
        }

        fun cancel(context: Context): androidx.work.Operation =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)

        suspend fun cancelSchedulingAndAwait(context: Context) = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK).result.get()
        }

        internal fun initialRequest(handle: RootHandle): OneTimeWorkRequest =
            initialRequest(handle.toWorkIdentity())

        internal fun initialRequest(identity: RootWorkIdentity): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MvsepPreparationWorker>()
                .setInputData(rootWorkData(identity))
                .setConstraints(localRecoveryWorkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 45, TimeUnit.SECONDS)
                .build()

        internal fun continuationRequest(
            handle: RootHandle,
            delay: Long,
            unit: TimeUnit
        ): OneTimeWorkRequest = continuationRequest(handle.toWorkIdentity(), delay, unit)

        internal fun continuationRequest(
            identity: RootWorkIdentity,
            delay: Long,
            unit: TimeUnit
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<MvsepPreparationWorker>()
            .setInputData(rootWorkData(identity))
            .setInitialDelay(delay, unit)
            .setConstraints(localRecoveryWorkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 45, TimeUnit.SECONDS)
            .build()

        internal fun rootExpectation(input: Data): WorkerRootExpectation? {
            val libraryId = input.getString(KEY_LIBRARY_ID) ?: return null
            return WorkerRootExpectation(libraryId, input.getString(KEY_TREE_URI_IDENTITY),
                input.getLong(KEY_ROOT_EPOCH, -1L)).takeIf { it.valid }
        }

        private fun rootWorkData(identity: RootWorkIdentity) = workDataOf(
            KEY_LIBRARY_ID to identity.libraryId,
            KEY_TREE_URI_IDENTITY to identity.treeUriIdentity,
            KEY_ROOT_EPOCH to identity.epoch
        )
    }
}

/** A retained credential means the account is waiting on a transient provider/network condition. */
internal fun waitingForAccountNeedsRetry(tokenStored: Boolean): Boolean = tokenStored

/** A local read failure keeps the same WorkManager owner; integrity, access and cancellation still fail closed. */
internal suspend fun retryUnavailablePreparationRead(operation: suspend () -> androidx.work.ListenableWorker.Result): androidx.work.ListenableWorker.Result =
    try { operation() } catch (_: java.io.IOException) { androidx.work.ListenableWorker.Result.retry() }

/** Shared worker orchestration; foreground and WorkManager scheduling remain external boundaries. */
internal suspend fun runPreparationOwnerLoop(
    rootHandle: RootHandle,
    playlist: app.stemwise.data.PlaylistRepository,
    separation: app.stemwise.integration.SeparationRepository,
    backgroundWork: app.stemwise.playback.BackgroundWorkCoordinator,
    requireOwnership: () -> Unit,
    tokenStored: () -> Boolean,
    continuePending: (Long) -> Unit
): androidx.work.ListenableWorker.Result {
    while (true) {
        requireOwnership()
        val owner = playlist.reconcilePreparationOwner(rootHandle)
        val admitted = owner as? PreparationOwnerDecision.Separation
            ?: return androidx.work.ListenableWorker.Result.success()
        val job = separation.openSeparationJobs(rootHandle)
            .firstOrNull { it.id == admitted.jobId }
        if (job == null) {
            separation.nextAttemptAt(
                rootHandle,
                admitted.jobId
            )?.let { dueAt ->
                val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
                continuePending(delay)
            }
            return androidx.work.ListenableWorker.Result.success()
        }
        when (
            backgroundWork.withHeavyWork(
                "Preparing stems for ${job.songId.take(8)}"
            ) {
                requireOwnership()
                try {
                    separation.process(job.id, rootHandle)
                        .also { requireOwnership() }
                } catch (fatal: app.stemwise.playback.engine.WorkerRetirementException) {
                    try {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            playlist.blockPreparationAfterRetirement(rootHandle, job.id, job.songId,
                                fatal.message?.takeIf { it.matches(Regex("^[A-Z][A-Z0-9_]+$")) }
                                    ?: "PREPARATION_WORKER_RETIREMENT_FAILED")
                        }
                    } catch (reporting: Throwable) {
                        if (reporting !== fatal) fatal.addSuppressed(reporting)
                    }
                    throw fatal
                }
            }
        ) {
            SeparationWorkResult.COMPLETE -> {
                playlist.reconcilePreparationOwner(rootHandle)
                continue
            }
            SeparationWorkResult.NEXT_OPERATION -> continue
            SeparationWorkResult.PENDING -> {
                val delay = separation.nextAttemptAt(rootHandle, job.id)
                    ?.let { (it - System.currentTimeMillis()).coerceAtLeast(1_000L) }
                    ?: 45_000L
                continuePending(delay)
                return androidx.work.ListenableWorker.Result.success()
            }
            SeparationWorkResult.WAITING_FOR_ACCOUNT -> return if (
                waitingForAccountNeedsRetry(tokenStored())
            ) {
                androidx.work.ListenableWorker.Result.retry()
            } else {
                androidx.work.ListenableWorker.Result.success()
            }
            SeparationWorkResult.TERMINAL_FAILURE -> {
                val next = playlist.reconcilePreparationOwner(rootHandle)
                if (next is PreparationOwnerDecision.Separation && next.jobId != job.id) continue
                return androidx.work.ListenableWorker.Result.success()
            }
            SeparationWorkResult.SUBMISSION_UNKNOWN -> {
                playlist.reconcilePreparationOwner(rootHandle)
                return androidx.work.ListenableWorker.Result.success()
            }
        }
    }
    return androidx.work.ListenableWorker.Result.success()
}
