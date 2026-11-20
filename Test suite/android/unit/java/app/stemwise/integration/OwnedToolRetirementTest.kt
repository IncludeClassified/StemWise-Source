package app.stemwise.integration

import app.stemwise.playback.engine.WorkerRetirementException
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OwnedToolRetirementTest {
    @Test fun unconfirmedRetirementRetainsStreamsIdentityAndRejectsRuntimeReuse() = runBlocking {
        for (throws in listOf(false, true)) {
            val runtime = BundledToolRuntime()
            var closed = false
            val signals = AtomicInteger()
            val owner = OwnedToolProcessOwner({ signals.incrementAndGet() }, { if (throws) error("reap failed") else false })
            owner.attach(101)
            val stream = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { closed = true }
            }
            val failure = runCatching {
                retireOwnedToolResources(signal = owner::signalOwned, reap = owner::reap,
                    readers = emptyList(), streams = listOf(stream), retainedResources = emptyList(),
                    runtime = runtime)
            }.exceptionOrNull()
            assertTrue(failure is WorkerRetirementException)
            assertFalse(closed)
            owner.signalOwned()
            assertEquals(2, signals.get())
            var reused = false
            assertSame(failure, runCatching { runtime.withLock { reused = true } }.exceptionOrNull())
            assertFalse(reused)
        }
    }

    @Test fun confirmedRetirementReleasesStreamsAndIdentityAndAllowsRuntimeReuse() = runBlocking {
        val runtime = BundledToolRuntime()
        val events = mutableListOf<String>()
        val owner = OwnedToolProcessOwner({ events += "signal" }, { events += "reap"; true })
        owner.attach(101)
        val stream = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { owner.signalOwned(); events += "close" }
        }
        retireOwnedToolResources(signal = owner::signalOwned, reap = owner::reap,
            readers = emptyList(), streams = listOf(stream), retainedResources = emptyList(),
            runtime = runtime)
        runtime.withLock { events += "reuse" }
        assertEquals(listOf("signal", "reap", "close", "reuse"), events)
    }

    @Test fun reapedPidCannotBeSignalledWhileReaderRetirementAndStreamCleanupAreHeld() {
        val signals = AtomicInteger()
        val reaped = CountDownLatch(1)
        val failReader = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val owner = OwnedToolProcessOwner({ signals.incrementAndGet() }, { reaped.countDown(); true })
        owner.attach(101)
        val failures = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                check(failReader.await(5L, TimeUnit.SECONDS))
                // The output-reader failure path uses the same signal owner as cancellation.
                owner.signalOwned()
            } catch (error: Throwable) { failures.compareAndSet(null, error) }
        }.apply { isDaemon = true; start() }
        val stream = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                closing.countDown()
                check(releaseClose.await(5L, TimeUnit.SECONDS))
            }
        }
        val retirement = Thread {
            try { retireOwnedToolResources(owner::signalOwned, owner::reap, listOf(reader), listOf(stream),
                listOf(owner), runtime = BundledToolRuntime()) }
            catch (error: Throwable) { failures.compareAndSet(null, error) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(reaped.await(5L, TimeUnit.SECONDS))
            owner.signalOwned()
            failReader.countDown()
            assertTrue(closing.await(5L, TimeUnit.SECONDS))
            owner.signalOwned()
            assertEquals(1, signals.get())
        } finally {
            failReader.countDown(); releaseClose.countDown()
            reader.join(5_000L); retirement.join(5_000L)
            assertFalse(reader.isAlive); assertFalse(retirement.isAlive)
        }
        failures.get()?.let { throw AssertionError("Retirement handshake failed", it) }
    }

    @Test fun cancellationCannotSignalBetweenNativeReapSuccessAndOwnershipRevocation() {
        val enteredReap = CountDownLatch(1)
        val releaseReap = CountDownLatch(1)
        val signalRequested = CountDownLatch(1)
        val signals = AtomicInteger()
        val failures = AtomicReference<Throwable?>()
        val owner = OwnedToolProcessOwner({ signals.incrementAndGet() }, {
            enteredReap.countDown(); check(releaseReap.await(5L, TimeUnit.SECONDS)); true
        })
        owner.attach(101)
        val retirement = Thread {
            try { assertTrue(owner.reap()) } catch (error: Throwable) { failures.compareAndSet(null, error) }
        }.apply { isDaemon = true; start() }
        val cancellation = Thread {
            signalRequested.countDown()
            try { owner.signalOwned() } catch (error: Throwable) { failures.compareAndSet(null, error) }
        }.apply { isDaemon = true }
        try {
            assertTrue(enteredReap.await(5L, TimeUnit.SECONDS))
            cancellation.start()
            assertTrue(signalRequested.await(5L, TimeUnit.SECONDS))
        } finally {
            releaseReap.countDown()
            retirement.join(5_000L); cancellation.join(5_000L)
            assertFalse(retirement.isAlive); assertFalse(cancellation.isAlive)
        }
        failures.get()?.let { throw AssertionError("Reap/signal handshake failed", it) }
        assertEquals(0, signals.get())
    }
}
