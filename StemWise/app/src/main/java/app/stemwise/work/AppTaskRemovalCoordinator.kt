package app.stemwise.work

import android.content.Context
import app.stemwise.StemwiseApplication

/**
 * Cancels work owned by the visible app task without creating a playback service or notification.
 * Playback remains PlaybackService's responsibility; this coordinator only owns durable work.
 */
internal object AppTaskRemovalCoordinator {
    private var cancellationOperations = emptyList<androidx.work.Operation>()

    @Synchronized
    fun onTaskAvailable(context: Context) {
        val application = context.applicationContext as? StemwiseApplication
        if (application != null) {
            application.services.manualTasks.onTaskAvailable()
            application.services.resumeBridgeTasks()
        } else {
            AppTaskWorkGate.instance.openForTask()
        }
    }

    @Synchronized
    fun onTaskRemoved(context: Context) {
        val operations = mutableListOf<androidx.work.Operation>()
        var accepted = false
        val application = context.applicationContext as? StemwiseApplication
        performTaskRemoval(
            closeEnqueueOwners = {
                if (application != null) {
                    // Close the enqueue gate before cancellation. A late retry completion
                    // therefore cannot recreate work after cancellation is accepted.
                    accepted = application.services.manualTasks.onTaskRemoved()
                    if (accepted) {
                        application.services.enrichmentImports.onTaskRemoved()
                    }
                } else {
                    accepted = AppTaskWorkGate.instance.closeForTaskRemoval()
                }
            },
            cancelSync = { if (accepted) operations += PlaylistSyncWorker.cancel(context) },
            cancelPreparation = {
                if (accepted) {
                    operations += MvsepPreparationWorker.cancel(context)
                    operations += BridgeSongWorker.cancel(context)
                }
            }
        )
        if (accepted) cancellationOperations = operations
    }

    /** Capture the departing task's requests; late cleanup must never cancel replacement work. */
    @Synchronized
    fun captureTaskRemovalCancellation(): suspend () -> Unit =
        awaitTaskRemovalOperations(cancellationOperations)
}

/** Fixed cancellation order: close producers, cancel sync, then preparation and MVSEP. */
internal fun performTaskRemoval(
    closeEnqueueOwners: () -> Unit,
    cancelSync: () -> Unit,
    cancelPreparation: () -> Unit
) {
    closeEnqueueOwners()
    cancelSync()
    cancelPreparation()
}

internal fun awaitTaskRemovalOperations(operations: List<androidx.work.Operation>): suspend () -> Unit {
    val pending = operations.map { it.result }
    return {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pending.forEach { it.get(15L, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }
}
