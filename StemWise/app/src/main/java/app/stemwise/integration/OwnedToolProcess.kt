package app.stemwise.integration

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Session groups contain the fixed FFmpeg/Python/QuickJS tools, which do not create new sessions. */
internal object OwnedToolNative {
    init { System.loadLibrary("stemwise_owned_tool") }
    external fun acquireLease(directory: String): Int
    external fun releaseLease(fd: Int, directory: String): Boolean
    external fun spawn(command: Array<String>, environment: Array<String>, cwd: String,
        leaseFd: Int, leaseDirectory: String): IntArray?
    external fun poll(pid: Int): Int
    external fun killGroup(pid: Int)
    external fun reap(pid: Int, timeoutMs: Int): Boolean
}

internal data class OwnedToolResult(val exitCode: Int, val stdout: String, val stderr: String)

/** A successful reap releases the PID while signalling is excluded, before pipe cleanup. */
internal class OwnedToolProcessOwner(
    private val killGroup: (Int) -> Unit,
    private val reapProcess: (Int) -> Boolean
) {
    private var ownedPid: Int? = null
    @Synchronized fun attach(pid: Int) { check(ownedPid == null); ownedPid = pid }
    @Synchronized fun signalOwned() { ownedPid?.let(killGroup) }
    @Synchronized fun reap(): Boolean {
        val pid = ownedPid ?: return true
        return reapProcess(pid).also { retired -> if (retired) ownedPid = null }
    }
}

/** One deadline includes output draining; retirement is bounded separately and never reports success on failure. */
internal suspend fun runOwnedToolProcess(
    command: List<String>,
    environment: Map<String, String>,
    timeoutSeconds: Long,
    maximumOutputBytes: Long,
    cwd: java.io.File? = null,
    cancellation: ExternalOperationCancellation? = null,
    stdoutTailBytes: Int? = null
): OwnedToolResult {
    require(command.isNotEmpty() && timeoutSeconds > 0L && maximumOutputBytes > 0L)
    require(command.none { '\u0000' in it } && environment.all { (key, value) ->
        key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value
    })
    bundledToolRuntimeMutex.requireHealthy()
    val cancelled = AtomicBoolean(false)
    val owner = OwnedToolProcessOwner(OwnedToolNative::killGroup, { OwnedToolNative.reap(it, 2_000) })
    return runCancellableBlockingOperation(
        externalCancellation = cancellation,
        cancelExact = {
            cancelled.set(true)
            owner.signalOwned()
        }
    ) { bundledToolRuntimeMutex.withProcessLease { leaseFd, leaseDirectory ->
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val fields = checkNotNull(OwnedToolNative.spawn(command.toTypedArray(),
            (System.getenv() + environment).map { (key, value) -> "$key=$value" }.toTypedArray(),
            cwd?.absolutePath.orEmpty(), leaseFd, leaseDirectory)) { "TOOL_PROCESS_START_FAILED" }
        val pid = fields[0]
        owner.attach(pid)
        val output = ByteArrayOutputStream()
        val diagnostic = ByteArrayOutputStream()
        val failure = AtomicReference<Throwable?>()
        val readers = mutableListOf<Thread>()
        val streams = mutableListOf<InputStream>()
        try {
            ParcelFileDescriptor.adoptFd(fields[1]).close()
            fun drain(fd: Int, target: ByteArrayOutputStream, limit: Long, tail: Int?) {
                val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))
                streams += input
                readers += thread(name = "stemwise-tool-output", isDaemon = true) {
                    try { readBoundedYtDlpOutput(input, target, limit, tail) }
                    catch (error: Throwable) { failure.compareAndSet(null, error); owner.signalOwned() }
                }
            }
            drain(fields[2], output, maximumOutputBytes, stdoutTailBytes)
            drain(fields[3], diagnostic, Long.MAX_VALUE, 64 * 1024)
            var exit = -1
            while (exit == -1) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException("Tool cancelled")
                cancellation?.requireActive()
                failure.get()?.let { throw it }
                check(System.nanoTime() < deadline) { "TOOL_PROCESS_TIMEOUT" }
                exit = OwnedToolNative.poll(pid)
                check(exit != -2) { "TOOL_PROCESS_OWNERSHIP_LOST" }
                if (exit == -1) Thread.sleep(10L)
            }
            for (reader in readers) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                check(remaining > 0L) { "TOOL_PROCESS_TIMEOUT" }
                reader.join(remaining.coerceAtLeast(1L))
                check(!reader.isAlive) { "TOOL_PROCESS_TIMEOUT" }
            }
            failure.get()?.let { throw it }
            OwnedToolResult(exit, output.toString("UTF-8"), diagnostic.toString("UTF-8"))
        } finally {
            retireOwnedToolResources(
                signal = owner::signalOwned,
                reap = owner::reap,
                readers = readers, streams = streams,
                retainedResources = listOf(owner)
            )
        }
    } }
}

/** Do not close pipes, forget process identity or reuse libraries without retirement proof. */
internal fun retireOwnedToolResources(
    signal: () -> Unit,
    reap: () -> Boolean,
    readers: List<Thread>,
    streams: List<InputStream>,
    retainedResources: List<Any>,
    runtime: BundledToolRuntime = bundledToolRuntimeMutex
) {
    val interrupted = Thread.interrupted()
    try {
        signal()
        val retired = reap()
        val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        readers.forEach { reader ->
            val remaining = TimeUnit.NANOSECONDS.toMillis(cleanupDeadline - System.nanoTime())
            if (remaining > 0L) reader.join(remaining.coerceAtLeast(1L))
        }
        check(retired && readers.none(Thread::isAlive)) { "TOOL_PROCESS_RETIREMENT_FAILED" }
        streams.forEach { input -> runCatching(input::close) }
    } catch (error: Throwable) {
        val failure = error as? app.stemwise.playback.engine.WorkerRetirementException
            ?: app.stemwise.playback.engine.WorkerRetirementException("TOOL_PROCESS_RETIREMENT_FAILED", error)
        app.stemwise.integration.WorkerRetirementQuarantine.retain(failure,
            retainedResources + readers + streams)
        runtime.quarantine(failure)
        throw failure
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
