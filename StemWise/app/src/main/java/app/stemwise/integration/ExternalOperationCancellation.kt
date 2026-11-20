package app.stemwise.integration

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.FutureTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

private fun AtomicReference<Closeable?>.closeOwned() {
    getAndSet(null)?.close()
}

/** One owner-controlled cancellation edge that can be attached before external work is visible. */
internal class ExternalOperationCancellation {
    private val lock = Any()
    private var cancelled = false
    private var attached: (() -> Unit)? = null

    fun cancel(): Boolean {
        val action = synchronized(lock) {
            if (cancelled) return false
            cancelled = true
            attached.also { attached = null }
        }
        action?.invoke()
        return true
    }

    fun requireActive() {
        if (synchronized(lock) { cancelled }) {
            throw CancellationException("External operation cancelled")
        }
    }

    internal fun attach(action: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) {
            false
        } else {
            check(attached == null) { "EXTERNAL_OPERATION_ALREADY_ATTACHED" }
            attached = action
            true
        }
    }

    internal fun detach(action: () -> Unit) = synchronized(lock) {
        if (attached === action) attached = null
    }
}

internal class ExternalOperationAttempt internal constructor(
    val ownerId: String,
    internal val cancellation: ExternalOperationCancellation = ExternalOperationCancellation()
)

/** Registers cancellation ownership before a queued operation can expose a stoppable state. */
internal class ExternalOperationAttemptRegistry {
    private val attempts = mutableMapOf<String, ExternalOperationAttempt>()

    @Synchronized
    fun prepare(ownerId: String): ExternalOperationAttempt {
        check(ownerId !in attempts) { "EXTERNAL_OPERATION_OWNER_ALREADY_ACTIVE" }
        return ExternalOperationAttempt(ownerId).also { attempts[ownerId] = it }
    }

    fun cancel(ownerId: String): Boolean = synchronized(this) {
        attempts[ownerId]
    }?.cancellation?.cancel() ?: false

    @Synchronized
    fun finish(attempt: ExternalOperationAttempt) {
        if (attempts[attempt.ownerId] === attempt) attempts.remove(attempt.ownerId)
    }
}

/**
 * Runs a blocking external operation without making its lifetime outlive the owning coroutine.
 * FutureTask closes the start/cancel race: cancellation prevents a queued operation from starting,
 * or interrupts the exact running thread while [cancelExact] terminates its external resource.
 */
internal suspend fun <T> runCancellableBlockingOperation(
    cancelExact: () -> Unit,
    dispatcher: ((Runnable) -> Unit)? = null,
    externalCancellation: ExternalOperationCancellation? = null,
    operation: () -> T
): T {
    val workerFinished = CompletableDeferred<Unit>()
    val retirementFailure = AtomicReference<app.stemwise.playback.engine.WorkerRetirementException?>()
    // 0 queued, 1 executing, 2 retired. Future cancellation is not worker quiescence.
    val workerState = java.util.concurrent.atomic.AtomicInteger(0)
    try {
        return suspendCancellableCoroutine { continuation ->
    val cancellationInvoked = AtomicBoolean(false)
    val settled = AtomicBoolean(false)
    lateinit var task: FutureTask<Unit>
    fun settle(result: Result<T>) {
        if (settled.compareAndSet(false, true)) continuation.resumeWith(result)
    }
    fun cancelExactOnce() {
        if (cancellationInvoked.compareAndSet(false, true)) runCatching(cancelExact)
        task.cancel(true)
        if (workerState.compareAndSet(0, 2)) workerFinished.complete(Unit)
    }
    lateinit var externalStop: () -> Unit
    externalStop = {
        cancelExactOnce()
        settle(Result.failure(CancellationException("External operation cancelled")))
    }
    task = FutureTask<Unit> {
        if (continuation.isActive) {
            val result = runCatching(operation)
            (result.exceptionOrNull() as? app.stemwise.playback.engine.WorkerRetirementException)?.let(retirementFailure::set)
            externalCancellation?.detach(externalStop)
            settle(result)
        }
    }
    if (externalCancellation != null && !externalCancellation.attach(externalStop)) {
        externalStop()
        return@suspendCancellableCoroutine
    }
    continuation.invokeOnCancellation {
        externalCancellation?.detach(externalStop)
        // Terminate a registered external resource (including its children) first. Interrupting
        // the runner second closes the narrow start-before-registration gap if the exact lookup
        // had not become visible yet.
        cancelExactOnce()
    }
    try {
        (dispatcher ?: { command ->
            Dispatchers.IO.dispatch(continuation.context, command)
        })(Runnable {
            if (workerState.compareAndSet(0, 1)) {
                val interruptedOnEntry = Thread.currentThread().isInterrupted
                try { task.run() } finally {
                    // After FutureTask's cancellation handshake, retire only the interrupt this task introduced.
                    if (task.isCancelled && !interruptedOnEntry) Thread.interrupted()
                    workerState.set(2)
                    workerFinished.complete(Unit)
                }
            }
        })
    } catch (error: Throwable) {
        externalCancellation?.detach(externalStop)
        if (workerState.compareAndSet(0, 2)) workerFinished.complete(Unit)
        settle(Result.failure(error))
    }
        }
    } finally {
        withContext(NonCancellable) { workerFinished.await() }
        retirementFailure.get()?.let { throw it }
    }
}

/** Queries and consumes one exact provider cursor within the owning coroutine lifetime. */
internal suspend fun <T> ContentResolver.queryCancellable(
    uri: Uri,
    projection: Array<String>,
    consume: (Cursor) -> T
): T? {
    val cancelled = AtomicBoolean(false)
    val owned = AtomicReference<Cursor?>()
    val signal = CancellationSignal()
    return runCancellableBlockingOperation(
        cancelExact = {
            cancelled.set(true)
            signal.cancel()
            runCatching { owned.getAndSet(null)?.close() }
        }
    ) {
        val cursor = query(uri, projection, null, null, null, signal) ?: return@runCancellableBlockingOperation null
        owned.set(cursor)
        if (cancelled.get()) {
            if (owned.compareAndSet(cursor, null)) runCatching(cursor::close)
            throw kotlinx.coroutines.CancellationException()
        }
        Closeable {
            if (owned.compareAndSet(cursor, null)) cursor.close()
        }.use {
            val extras = cursor.extras
            check(
                !extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) &&
                    extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()
            ) { "PROVIDER_QUERY_INCOMPLETE" }
            consume(cursor)
        }
    }
}

/** Owns one provider descriptor and its exact blocking consumer for one coroutine. */
internal suspend fun <T> ContentResolver.withCancellableProviderAssetFileDescriptor(
    uri: Uri,
    mode: String = "r",
    consume: (AssetFileDescriptor) -> T
): T? {
    val cancelled = AtomicBoolean(false)
    val signal = CancellationSignal()
    return ProviderAssetOpening(this, uri, mode, signal).use { opening ->
        runCancellableBlockingOperation(
            cancelExact = {
                cancelled.set(true)
                signal.cancel()
            }
        ) {
            val descriptor = opening.open() ?: return@runCancellableBlockingOperation null
            if (cancelled.get()) {
                runCatching(descriptor::close)
                throw kotlinx.coroutines.CancellationException()
            }
            descriptor.use(consume)
        }
    }
}

/** Opens and consumes one provider input while cancellation owns both signal and descriptor. */
internal suspend fun <T> ContentResolver.withCancellableProviderInput(
    uri: Uri,
    consume: suspend (InputStream, suspend (ByteArray) -> Int) -> T
): T? {
    val cancelled = AtomicBoolean(false)
    val owned = AtomicReference<Closeable?>()
    val signal = CancellationSignal()
    return ProviderAssetOpening(this, uri, "r", signal).use { opening ->
        val descriptor = runCancellableBlockingOperation(
            cancelExact = {
                cancelled.set(true)
                try { signal.cancel() } finally { runCatching { owned.closeOwned() } }
            }
        ) {
            val opened = opening.open()
            opened?.also {
                owned.set(it)
                if (cancelled.get()) {
                    if (owned.compareAndSet(it, null)) runCatching(it::close)
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        } ?: return null
        Closeable { owned.closeOwned() }.use {
            val input = runCancellableBlockingOperation(
                cancelExact = {
                    cancelled.set(true)
                    try { signal.cancel() } finally { runCatching { owned.closeOwned() } }
                }
            ) {
                descriptor.createInputStream().buffered().also { opened ->
                    if (cancelled.get() || owned.get() !== descriptor) {
                        opened.close()
                        throw kotlinx.coroutines.CancellationException()
                    }
                }
            }
            // BufferedInputStream.close can wait on its blocked read's monitor. Cancel the raw
            // descriptor first, then close the wrapper only after the read worker has retired.
            input.use {
                consume(input) { buffer ->
                    runCancellableBlockingOperation(
                        cancelExact = {
                            cancelled.set(true)
                            try { signal.cancel() } finally { runCatching { owned.closeOwned() } }
                        }
                    ) { input.read(buffer) }
                }
            }
        }
    }
}

/** Opens and consumes one provider output while cancellation owns both signal and descriptor. */
internal suspend fun <T> ContentResolver.withCancellableProviderOutput(
    uri: Uri,
    mode: String = "wt",
    consume: suspend (
        OutputStream,
        suspend (ByteArray, Int, Int) -> Unit,
        suspend () -> Unit
    ) -> T
): T? {
    val cancelled = AtomicBoolean(false)
    val owned = AtomicReference<Closeable?>()
    val signal = CancellationSignal()
    val descriptor = runCancellableBlockingOperation(
        cancelExact = {
            cancelled.set(true)
            signal.cancel()
            runCatching { owned.closeOwned() }
        }
    ) {
        openAssetFileDescriptor(uri, mode, signal)?.also { opened ->
            owned.set(opened)
            if (cancelled.get()) {
                if (owned.compareAndSet(opened, null)) runCatching(opened::close)
                throw kotlinx.coroutines.CancellationException()
            }
        }
    } ?: return null
    return Closeable { owned.closeOwned() }.use {
        val output = runCancellableBlockingOperation(
            cancelExact = { runCatching { owned.closeOwned() } }
        ) {
            descriptor.createOutputStream().also { opened ->
                if (!owned.compareAndSet(descriptor, opened)) {
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        }
        consume(
            output,
            { bytes, offset, count ->
                runCancellableBlockingOperation(
                    cancelExact = { runCatching { owned.closeOwned() } }
                ) { output.write(bytes, offset, count) }
            },
            {
                runCancellableBlockingOperation(
                    cancelExact = { runCatching { owned.closeOwned() } }
                ) { output.flush() }
            }
        )
    }
}

/** Executes and consumes this exact OkHttp call without letting its consumer outlive its owner. */
internal suspend fun <T> Call.executeCancellable(
    consume: suspend (Response) -> T
): T {
    val response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, owned, _ -> owned.close() }
            }
        })
    }
    return supervisorScope {
        val consumer = async(Dispatchers.IO) {
            response.use { consume(it) }
        }
        try {
            consumer.await()
        } catch (cancelled: CancellationException) {
            runCatching { cancel() }
            runCatching { response.close() }
            withContext(NonCancellable) { consumer.join() }
            throw cancelled
        }
    }
}
