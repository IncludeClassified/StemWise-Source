package app.stemwise.playback.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativePreparationRetirementTest {
    @Test fun blockedWaveformRetirementSuppressesAllReplacementWorkUntilExactJoin() {
        val gate = NativeSourceWindowReadGate()
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val releases = java.util.concurrent.atomic.AtomicInteger()
        assertTrue(gate.tryBegin())
        val retirement = NativePreparationRetirement(
            stop = { stopEntered.countDown(); check(releaseStop.await(2, TimeUnit.SECONDS)) },
            isJoined = { true }, release = { releases.incrementAndGet() })
        gate.retain(retirement)
        try {
            assertTrue(stopEntered.await(1, TimeUnit.SECONDS))
            assertFalse(retirement.await(10, TimeUnit.MILLISECONDS))
            gate.end()
            repeat(20) { assertFalse(gate.tryBegin()) }
            assertTrue(releases.get() == 0)
        } finally { releaseStop.countDown() }
        assertTrue(retirement.await(1, TimeUnit.SECONDS))
        assertTrue(gate.tryBegin())
        gate.end()
        assertTrue(releases.get() == 1)
    }

    @Test
    fun timedOutCallerLeavesBankOwnedUntilBlockedCancellationAndWorkerJoinFinish() {
        val cancellationEntered = CountDownLatch(1)
        val allowCancellation = CountDownLatch(1)
        val allowJoin = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val owner = NativePreparationRetirement(
            stop = { cancellationEntered.countDown(); check(allowCancellation.await(2, TimeUnit.SECONDS)) },
            isJoined = { allowJoin.await(10, TimeUnit.MILLISECONDS) },
            release = { released.set(true) }
        )
        try {
            assertTrue(cancellationEntered.await(1, TimeUnit.SECONDS))
            assertFalse(owner.await(20, TimeUnit.MILLISECONDS))
            assertFalse(released.get())
            allowCancellation.countDown()
            assertFalse(owner.await(20, TimeUnit.MILLISECONDS))
            assertFalse(released.get())
        } finally {
            allowCancellation.countDown()
            allowJoin.countDown()
            assertTrue(owner.await(1, TimeUnit.SECONDS))
        }
        assertTrue(released.get())
    }
}
