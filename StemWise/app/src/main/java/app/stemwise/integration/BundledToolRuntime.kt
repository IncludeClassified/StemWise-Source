package app.stemwise.integration

import app.stemwise.playback.engine.WorkerRetirementException
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Installation, subprocesses and scratch cleanup share exact native process ownership. */
internal class BundledToolRuntime {
    private val mutex = Mutex()
    private val directories = linkedSetOf<File>()
    private var activeProcess = false
    private var failure: WorkerRetirementException? = null

    @Synchronized fun bind(directory: File) {
        val exact = directory.canonicalFile
        check(!activeProcess || exact in directories) { "TOOL_RUNTIME_OWNERSHIP_UNAVAILABLE" }
        directories.remove(exact)
        directories += exact
    }

    @Synchronized fun requireHealthy() {
        failure?.let { throw it }
        if (!activeProcess) directories.forEach { root ->
            try {
                java.nio.file.Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: java.nio.file.NoSuchFileException) { return@forEach }
            val lease = acquire(root)
            if (!OwnedToolNative.releaseLease(lease, root.path)) {
                throw WorkerRetirementException("TOOL_PROCESS_RETIREMENT_UNCONFIRMED")
            }
        }
    }

    private fun acquire(root: File): Int = OwnedToolNative.acquireLease(root.path).also {
        if (it < 0) throw WorkerRetirementException("TOOL_PROCESS_RETIREMENT_UNCONFIRMED")
    }

    @Synchronized fun quarantine(error: WorkerRetirementException) {
        if (failure == null) failure = error
    }

    fun <T> withProcessLease(action: (Int, String) -> T): T {
        val root: File
        val lease: Int
        synchronized(this) {
            requireHealthy()
            check(!activeProcess) { "TOOL_RUNTIME_OWNERSHIP_UNAVAILABLE" }
            root = checkNotNull(directories.lastOrNull()) { "TOOL_RUNTIME_OWNERSHIP_UNAVAILABLE" }
            lease = acquire(root)
            activeProcess = true
        }
        var primary: Throwable? = null
        try {
            return action(lease, root.path)
        } catch (error: Throwable) {
            primary = error
            if (error is WorkerRetirementException) quarantine(error)
            throw error
        } finally {
            synchronized(this) {
                try {
                    // A failed retirement retains the descriptor until process death, as well as its receipt.
                    if (failure == null && !OwnedToolNative.releaseLease(lease, root.path)) {
                        throw WorkerRetirementException("TOOL_PROCESS_RETIREMENT_UNCONFIRMED")
                    }
                } catch (cleanupError: Throwable) {
                    if (cleanupError is WorkerRetirementException) quarantine(cleanupError)
                    val original = primary
                    if (original == null) throw cleanupError
                    if (cleanupError !== original) original.addSuppressed(cleanupError)
                } finally { activeProcess = false }
            }
        }
    }

    suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock {
        requireHealthy()
        action()
    }
}
internal val bundledToolRuntimeMutex = BundledToolRuntime()
