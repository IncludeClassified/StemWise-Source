package app.stemwise.playback.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal const val NATIVE_DECODER_JOIN_WATCHDOG_MS = 2_000L

/** Retains resources across a bounded caller wait until the worker actually joins. */
internal class NativePreparationRetirement(
    stop: () -> Unit,
    isJoined: () -> Boolean,
    release: () -> Unit
) {
    private val finished = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()

    init {
        // This thread owns all captured resources even if codec cancellation itself blocks.
        thread(name = "StemWise-PreparationRetirement", isDaemon = true) {
            try {
                try { stop() } catch (error: Throwable) { failure.set(error) }
                while (true) {
                    try {
                        if (isJoined()) break
                    } catch (_: InterruptedException) {
                        // Interruption cannot prove the resource owner stopped.
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                        try { Thread.sleep(100) } catch (_: InterruptedException) { /* Still retained. */ }
                    }
                }
                release()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                finished.countDown()
            }
        }
    }

    fun await(timeout: Long, unit: TimeUnit): Boolean {
        if (!finished.await(timeout, unit)) return false
        failure.get()?.let { throw it }
        return true
    }
}
