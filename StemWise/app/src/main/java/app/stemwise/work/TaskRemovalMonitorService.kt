package app.stemwise.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.stemwise.StemwiseApplication
import app.stemwise.playback.PlaybackService

/**
 * A notification-free Recents callback for app tasks that never created PlaybackService.
 *
 * This deliberately remains a plain, non-sticky service: it owns no notification, wake lock,
 * playback session, or WorkManager request. Active work only retains the callback until Android
 * delivers task removal; the callback then closes the enqueue gate and cancels that work.
 */
internal class TaskRemovalMonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskRemovalMonitor.onServiceStarted(applicationContext)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppTaskRemovalCoordinator.onTaskRemoved(applicationContext)
        val restartRequired = (application as? StemwiseApplication)
            ?.services
            ?.audioPlayback
            ?.processRestartRequiredAfterTaskRemoval() == true
        PlaybackService.onAppTaskRemoved(applicationContext, restartRequired)
        TaskRemovalMonitor.onSystemTaskRemoved(applicationContext)
        stopSelf()
    }

    override fun onDestroy() {
        // An owner can arrive after stopService() was accepted but before this callback. Clear the
        // obsolete instance and request its replacement in one registry boundary.
        TaskRemovalMonitor.onServiceDestroyed(applicationContext)
        super.onDestroy()
    }
}

/** State machine kept independent of Android so stop/destroy races stay regression-testable. */
internal class TaskRemovalMonitorRegistry {
    private val lock = Any()
    private var taskOwner = false
    private val workerOwners = mutableSetOf<String>()
    private var startRequested = false

    fun armForTask(start: () -> Boolean) = synchronized(lock) {
        taskOwner = true
        ensureStartedLocked(start)
    }

    fun disarmTask(stop: () -> Unit) = synchronized(lock) {
        taskOwner = false
        stopIfUnownedLocked(stop)
    }

    fun armForWorker(ownerId: String, start: () -> Boolean) = synchronized(lock) {
        workerOwners += ownerId
        ensureStartedLocked(start)
    }

    fun disarmWorker(ownerId: String, stop: () -> Unit) = synchronized(lock) {
        workerOwners -= ownerId
        stopIfUnownedLocked(stop)
    }

    fun onSystemTaskRemoved(stop: () -> Unit) = synchronized(lock) {
        taskOwner = false
        workerOwners.clear()
        stopLocked(stop)
    }

    fun onServiceStarted(stop: () -> Unit) = synchronized(lock) {
        startRequested = true
        // stopService() may win before a pending start is delivered. Do not let that late
        // onStartCommand resurrect an ownerless monitor.
        if (!taskOwner && workerOwners.isEmpty()) stopLocked(stop)
    }

    fun onServiceDestroyed(start: () -> Boolean) = synchronized(lock) {
        startRequested = false
        if (taskOwner || workerOwners.isNotEmpty()) ensureStartedLocked(start)
    }

    /** Retry a denied/reclaimed start once WorkManager has entered foreground execution. */
    fun ensureStartedForOwners(start: () -> Boolean) = synchronized(lock) {
        if (taskOwner || workerOwners.isNotEmpty()) {
            ensureStartedLocked(start)
        }
    }

    private fun ensureStartedLocked(start: () -> Boolean) {
        if (startRequested) return
        startRequested = runCatching(start).getOrDefault(false)
    }

    private fun stopIfUnownedLocked(stop: () -> Unit) {
        if (!taskOwner && workerOwners.isEmpty()) stopLocked(stop)
    }

    private fun stopLocked(stop: () -> Unit) {
        if (startRequested) runCatching(stop)
        startRequested = false
    }
}

/** Process-local owner registry for the non-sticky monitor service. */
internal object TaskRemovalMonitor {
    private val registry = TaskRemovalMonitorRegistry()

    fun armForTask(context: Context) = registry.armForTask { start(context) }

    fun disarmTask(context: Context) = registry.disarmTask { stop(context) }

    fun armForWorker(context: Context, ownerId: String): AutoCloseable {
        registry.armForWorker(ownerId) { start(context) }
        return AutoCloseable { registry.disarmWorker(ownerId) { stop(context) } }
    }

    fun onSystemTaskRemoved(context: Context) = registry.onSystemTaskRemoved { stop(context) }

    fun onServiceStarted(context: Context) = registry.onServiceStarted { stop(context) }

    fun onServiceDestroyed(context: Context) = registry.onServiceDestroyed { start(context) }

    fun ensureStartedForOwners(context: Context) =
        registry.ensureStartedForOwners { start(context) }

    private fun start(context: Context): Boolean {
        val applicationContext = context.applicationContext
        return applicationContext.startService(
            Intent(applicationContext, TaskRemovalMonitorService::class.java)
        ) != null
    }

    private fun stop(context: Context) {
        val applicationContext = context.applicationContext
        applicationContext.stopService(
            Intent(applicationContext, TaskRemovalMonitorService::class.java)
        )
    }
}
