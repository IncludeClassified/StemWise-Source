package app.stemwise

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecoverableBackgroundWorkTest {
    @Test fun backgroundFailurePublishesAndRetiresWithoutEscapingThenRetrySucceeds() = runBlocking {
        var escaped: Throwable? = null
        var published: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> escaped = error })
        try {
            val failed = IOException("provider or outbox unavailable")
            val first = scope.launchRecoverable({ published = it }) { throw failed }
            first.join()
            assertSame("The owned launch lost its recoverable failure", failed, published)
            assertNull("Recoverable failure reached the uncaught handler", escaped)
            assertTrue(first.isCompleted)
            var completed = false
            scope.launchRecoverable({ fail("Successful retry failed") }) { completed = true }.join()
            assertTrue(completed)
            val cancelled = scope.launchRecoverable({ fail("Cancellation became a failure") }) { throw CancellationException("departed root") }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            assertSame(failed, published)
        } finally { scope.cancel() }
    }
}
