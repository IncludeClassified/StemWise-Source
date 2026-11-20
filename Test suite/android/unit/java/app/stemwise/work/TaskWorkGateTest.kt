package app.stemwise.work

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWorkGateTest {
    @Test
    fun reopeningDoesNotMakeAnOlderLeaseCurrent() {
        val gate = TaskWorkGate()
        val oldLease = requireNotNull(gate.acquireLease())

        assertTrue(gate.closeForTaskRemoval())
        gate.openForTask()

        assertFalse(gate.runIfCurrent(oldLease) { error("stale enqueue ran") })
        val newLease = requireNotNull(gate.acquireLease())
        assertTrue(gate.runIfCurrent(newLease) { Unit })
    }

    @Test
    fun closingTheGateIsIdempotentAndRejectsDirectEnqueues() {
        val gate = TaskWorkGate()

        assertTrue(gate.closeForTaskRemoval())
        assertFalse(gate.closeForTaskRemoval())
        assertFalse(gate.runIfActive { error("closed gate accepted work") })
    }

    @Test
    fun librarySwitchInvalidatesOldLeasesWithoutChangingTaskRemovalState() {
        val gate = TaskWorkGate()
        val oldLease = requireNotNull(gate.acquireLease())

        gate.closeForLibrarySwitch()
        assertFalse(gate.isActiveForTest())
        assertFalse(gate.runIfCurrent(oldLease) { error("old-root enqueue ran") })

        gate.openAfterLibrarySwitch()
        assertTrue(gate.isActiveForTest())
        assertFalse(gate.runIfCurrent(oldLease) { error("old-root lease revived") })
        assertTrue(gate.runIfCurrent(requireNotNull(gate.acquireLease())) { Unit })
    }

    @Test
    fun closingWaitsUntilTheCurrentCommandPublishesItsJob() {
        val gate = TaskWorkGate()
        val lease = requireNotNull(gate.acquireLease())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = Job()
        val retired = AtomicReference<Job?>()
        var activeJob: Job? = null

        val commandFailure = AtomicReference<Throwable?>()
        val command = thread {
            try { gate.runIfCurrent(lease) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                activeJob = published
            } } catch (error: Throwable) { commandFailure.set(error) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val attempted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val removal = thread {
            try {
                attempted.countDown()
                assertTrue(gate.closeForTaskRemoval())
                retired.set(activeJob)
                activeJob = null
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (removal.isAlive && removal.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("Removal did not reach the production gate while publication held it", Thread.State.BLOCKED, removal.state)
        } finally {
            release.countDown()
            command.join(5_000)
            removal.join(5_000)
            published.cancel()
        }
        assertFalse(command.isAlive)
        assertFalse(removal.isAlive)
        commandFailure.get()?.let { throw AssertionError("Publication worker failed", it) }
        failure.get()?.let { throw AssertionError("Removal worker failed", it) }
        assertSame(published, retired.get())
    }
}
