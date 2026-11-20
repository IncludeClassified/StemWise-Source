package app.stemwise.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stemwise.StemwiseApplication
import app.stemwise.data.ColdOpenProducerAdmission
import app.stemwise.bridge.BridgeSongWorkProgress
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BridgeSongWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StemwiseApplication
        app.workerInitialization.await()
        val id = inputData.getString("bridge_job_id") ?: return Result.failure()
        val canAdvanceWithoutLibrary = try { app.services.songBridge.canAdvanceWithoutLibrary(id) }
        catch (_: IOException) { return Result.retry() }
        if (!canAdvanceWithoutLibrary) {
            val root = app.services.libraryRootManager.captureHandle() ?: return Result.retry()
            when (app.services.coldOpenCoordinator.producerAdmission(root)) {
                ColdOpenProducerAdmission.RUN -> Unit
                ColdOpenProducerAdmission.RETRY -> return Result.retry()
                ColdOpenProducerAdmission.COMPLETE -> return Result.success()
            }
        }
        val monitor = TaskRemovalMonitor.armForWorker(applicationContext, this.id.toString())
        return try {
            advanceBridgeSongWork(continuePending = { continueAfter(applicationContext, id) },
                observation = { app.services.songBridge.tasks.value.singleOrNull { it.id == id } }) {
                app.services.songBridge.advance(id)
            }
        } finally { monitor.close() }
    }
    companion object {
        private const val TAG = "stemwise-pc-song-jobs"
        internal fun enqueue(context: Context, id: String, replaceExisting: Boolean = false) {
            AppTaskWorkGate.instance.runIfActive {
                WorkManager.getInstance(context).enqueueUniqueWork("$TAG:$id",
                    if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<BridgeSongWorker>().setInputData(workDataOf("bridge_job_id" to id))
                        .setConstraints(localRecoveryWorkConstraints()).addTag(TAG).build())
            }
        }
        private fun continueAfter(context: Context, id: String) {
            AppTaskWorkGate.instance.runIfActive {
                WorkManager.getInstance(context).enqueueUniqueWork("$TAG:$id", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<BridgeSongWorker>().setInputData(workDataOf("bridge_job_id" to id))
                        .setInitialDelay(15, TimeUnit.SECONDS)
                        .setConstraints(localRecoveryWorkConstraints()).addTag(TAG).build())
            }
        }
        internal fun cancel(context: Context): androidx.work.Operation = WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        internal suspend fun cancelSchedulingAndAwait(context: Context) = withContext(Dispatchers.IO) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG).result.get()
        }
    }
}

/** The scheduled worker owns this bounded attempt; the outbox owns the request across retries. */
internal suspend fun advanceBridgeSongWork(
    waitForRemote: suspend (Long) -> Unit = { delay(it) },
    continuePending: () -> Unit = {},
    observation: () -> app.stemwise.bridge.BridgeSongTask? = { null },
    advance: suspend () -> BridgeSongWorkProgress
): ListenableWorker.Result = try {
    var progress = BridgeSongWorkProgress.PENDING
    var attempts = 0
    var previous: app.stemwise.bridge.BridgeSongTask? = null
    var unchanged = 0
    while (progress == BridgeSongWorkProgress.PENDING && attempts++ < 6) {
        progress = advance()
        if (progress == BridgeSongWorkProgress.PENDING && attempts < 6) {
            val current = observation()
            unchanged = if (current == previous) (unchanged + 1).coerceAtMost(3) else 0
            previous = current
            waitForRemote(15_000L shl unchanged)
        }
    }
    when (progress) {
        BridgeSongWorkProgress.RETRY -> ListenableWorker.Result.retry()
        BridgeSongWorkProgress.COMPLETE -> ListenableWorker.Result.success()
        BridgeSongWorkProgress.PENDING -> {
            continuePending()
            ListenableWorker.Result.success()
        }
    }
} catch (_: IOException) {
    // A diagnostic needs the same unavailable storage; WorkManager owns retry without that write.
    ListenableWorker.Result.retry()
}
