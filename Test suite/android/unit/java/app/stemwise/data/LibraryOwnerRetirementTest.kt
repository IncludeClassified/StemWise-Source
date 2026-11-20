package app.stemwise.data

import app.stemwise.bridge.BridgeOperationOwner
import app.stemwise.integration.runCancellableBlockingOperation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LibraryOwnerRetirementTest {
    @Test fun timeoutReturnsWhileTheExactWorkerAndItsFencesRemainOwnedUntilRetirement() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val owner = BridgeOperationOwner()
            val retirement = LibraryOwnerRetirement(scope, 30L)
            val retired = CompletableDeferred<Unit>()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = scope.launch {
                owner.run {
                    runCancellableBlockingOperation(cancelExact = {}) {
                        started.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                        while (release.count > 0L && System.nanoTime() < deadline) {
                            try { release.await(20L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                        }
                        check(release.count == 0L) { "Fixture worker did not retire" }
                    }
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(1, TimeUnit.SECONDS) })
                val failure = runCatching { retirement.await { owner.closeAndJoin(); retired.complete(Unit) } }.exceptionOrNull()
                assertEquals(LIBRARY_OWNER_RETIREMENT_PENDING, (failure as? LibraryRootException)?.code)
                assertTrue(retirement.pending)
                assertFalse(worker.isCompleted)
                assertTrue(runCatching { owner.reopen() }.isFailure)
                assertTrue(runCatching { owner.run { error("New writer admitted") } }.exceptionOrNull() is CancellationException)
                release.countDown()
                worker.join()
                retired.await()
                retirement.await { error("Retirement was restarted instead of joined") }
                assertFalse(retirement.pending)
                owner.reopen()
                assertEquals("ready", owner.run { "ready" })
            } finally {
                release.countDown()
                withContext(NonCancellable) { withTimeout(2_000L) { worker.join() } }
                scope.cancel()
            }
        }
    }
}
