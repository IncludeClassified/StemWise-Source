package app.stemwise.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stemwise.StemwiseApplication
import app.stemwise.data.ColdOpenProducerAdmission
import app.stemwise.data.PlaylistSyncStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PlaylistSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val application = appContext as StemwiseApplication
    private val services get() = application.services

    override suspend fun doWork(): Result {
        val monitor = TaskRemovalMonitor.armForWorker(applicationContext, id.toString())
        return try {
            runSync()
        } finally {
            monitor.close()
        }
    }

    private suspend fun runSync(): Result {
        val expectedLibraryId = inputData.getString(KEY_LIBRARY_ID) ?: return Result.success()
        val expectedRoot = WorkerRootExpectation(expectedLibraryId)
        if (!expectedRoot.valid) return Result.success()
        application.workerInitialization.await()
        val rootState = services.libraryRootManager.state.value
        val rootHandle = services.libraryRootManager.captureHandle(rootState)
        when (workerRootAdmission(
            expected = expectedRoot,
            liveRoot = rootHandle?.toWorkIdentity(),
            configuredLibraryId = rootState.libraryId,
            configuredTreeUri = rootState.treeUri?.toString(),
            configuredEpoch = rootState.epoch,
            configuredErrorCode = rootState.errorCode
        )) {
            WorkerRootAdmission.RETRY -> return Result.retry()
            WorkerRootAdmission.COMPLETE -> return Result.success()
            WorkerRootAdmission.RUN -> Unit
        }
        val admittedRoot = checkNotNull(rootHandle)
        if (admittedRoot.libraryId != expectedLibraryId ||
            !services.preferences.playlist.value.configured) return Result.success()
        val coldOpen = services.coldOpenCoordinator
        when (coldOpen.producerAdmission(admittedRoot)) {
            ColdOpenProducerAdmission.RETRY -> return Result.retry()
            ColdOpenProducerAdmission.COMPLETE -> return Result.success()
            ColdOpenProducerAdmission.RUN -> Unit
        }
        services.libraryRootManager.requireSameLibrary(admittedRoot)
        val sync = services.playlistRepository.sync()
        services.libraryRootManager.requireSameLibrary(admittedRoot)
        if (sync.isFailure) {
            val error = sync.exceptionOrNull()
            return if (playlistSyncFailureNeedsRetry(error)) {
                Result.retry()
            } else {
                Result.success()
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "stemwise-periodic-playlist-sync"
        private const val UNIQUE_MANUAL_WORK_PREFIX = "stemwise-manual-playlist-sync"
        internal const val MANUAL_WORK_TAG = "stemwise-manual-playlist-sync"
        private const val KEY_LIBRARY_ID = "library_id"
        internal const val KEY_MANUAL = "manual"

        fun enqueueManual(context: Context): Boolean {
            var enqueued = false
            AppTaskWorkGate.instance.runIfActive {
                val app = context.applicationContext as? StemwiseApplication
                    ?: return@runIfActive
                val libraryId = app.services.libraryRootManager.state.value.libraryId
                    ?: return@runIfActive
                WorkManager.getInstance(context).enqueueUniqueWork(
                    manualUniqueWorkName(libraryId),
                    ExistingWorkPolicy.KEEP,
                    manualRequest(libraryId)
                )
                enqueued = true
            }
            return enqueued
        }

        fun schedule(context: Context) {
            AppTaskWorkGate.instance.runIfActive {
                val app = context.applicationContext as? StemwiseApplication
                    ?: return@runIfActive
                val libraryId = app.services.libraryRootManager.state.value.libraryId
                    ?: return@runIfActive
                val request = periodicRequest(libraryId)
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }

        fun cancel(context: Context): List<androidx.work.Operation> =
            WorkManager.getInstance(context).let {
                listOf(it.cancelUniqueWork(UNIQUE_WORK), it.cancelAllWorkByTag(MANUAL_WORK_TAG))
            }

        suspend fun cancelSchedulingAndAwait(context: Context) = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).run {
                cancelUniqueWork(UNIQUE_WORK).result.get()
                cancelAllWorkByTag(MANUAL_WORK_TAG).result.get()
            }
        }

        fun updateSchedule(context: Context, configured: Boolean) {
            if (configured) schedule(context) else cancel(context)
        }

        internal fun periodicRequest(libraryId: String): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<PlaylistSyncWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_LIBRARY_ID to libraryId))
                .setConstraints(automaticNetworkWorkConstraints())
                .build()

        internal fun manualRequest(libraryId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_LIBRARY_ID to libraryId,
                        KEY_MANUAL to true
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(MANUAL_WORK_TAG)
                .build()

        internal fun manualUniqueWorkName(libraryId: String): String =
            "$UNIQUE_MANUAL_WORK_PREFIX:$libraryId"

        internal fun observeManualSync(
            context: Context,
            libraryId: String
        ): Flow<ManualPlaylistSyncWorkState> = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(manualUniqueWorkName(libraryId))
            .map { work -> manualPlaylistSyncWorkState(work.map { it.state }) }
            .distinctUntilChanged()
    }
}

internal enum class ManualPlaylistSyncWorkState { IDLE, QUEUED, RUNNING }

internal fun manualPlaylistSyncWorkState(
    states: List<WorkInfo.State>
): ManualPlaylistSyncWorkState = when {
    WorkInfo.State.RUNNING in states -> ManualPlaylistSyncWorkState.RUNNING
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
        ManualPlaylistSyncWorkState.QUEUED
    else -> ManualPlaylistSyncWorkState.IDLE
}

internal fun projectManualPlaylistSyncStatus(
    status: PlaylistSyncStatus,
    workState: ManualPlaylistSyncWorkState,
    networkAllowed: Boolean
): PlaylistSyncStatus = when (workState) {
    ManualPlaylistSyncWorkState.RUNNING -> status.copy(
        running = true,
        queued = false,
        waitingForNetwork = false
    )
    ManualPlaylistSyncWorkState.QUEUED -> status.copy(
        queued = !status.running,
        waitingForNetwork = status.waitingForNetwork || (!status.running && !networkAllowed)
    )
    ManualPlaylistSyncWorkState.IDLE -> status.copy(queued = false)
}

internal fun playlistSyncFailureNeedsRetry(error: Throwable?): Boolean {
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    if (causes.any { it is app.stemwise.network.UnmeteredNetworkRequiredException ||
            it is java.net.SocketTimeoutException || it is java.net.UnknownHostException ||
            it.message == "TOOL_PROCESS_TIMEOUT" }) return true
    val message = causes.filterIsInstance<com.yausername.youtubedl_android.YoutubeDLException>()
        .firstOrNull()?.message ?: return false
    return message.contains("network", ignoreCase = true) ||
        message.contains("resolve", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true) ||
        message.contains("Unable to download", ignoreCase = true) ||
        Regex("HTTP Error (408|429|5[0-9]{2})\\b").containsMatchIn(message)
}
