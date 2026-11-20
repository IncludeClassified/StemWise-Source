package app.stemwise.bridge

import app.stemwise.integration.runCancellableBlockingOperation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeOperationOwnerTest {
    @Test
    fun rootReplacementWaitsForActualBlockedSdkRetirementAndRejectsNewOperations() = runBlocking {
        withTimeout(5_000) {
            val owner = BridgeOperationOwner()
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = launch(Dispatchers.IO) {
                owner.run {
                    runCancellableBlockingOperation(cancelExact = { cancelled.countDown() }) {
                        started.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                        while (release.count > 0 && System.nanoTime() < deadline) {
                            try { release.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        }
                        check(release.count == 0L) { "Fixture retirement deadline exceeded" }
                    }
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
                val rootReplacement = async { owner.closeAndJoin() }
                assertTrue(withContext(Dispatchers.IO) { cancelled.await(2, TimeUnit.SECONDS) })
                assertFalse(rootReplacement.isCompleted)
                assertFalse(worker.isCompleted)
                val rejected = runCatching { owner.run { error("New root work started early") } }.exceptionOrNull()
                assertTrue(rejected is CancellationException)
                release.countDown()
                rootReplacement.await()
                worker.join()
                assertTrue(worker.isCompleted)
                owner.reopen()
                assertEquals("new exact-root operation", owner.run { "new exact-root operation" })
            } finally {
                release.countDown()
                worker.join()
            }
        }
    }
}
