package app.stemwise.work

import androidx.work.ListenableWorker
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MvsepProviderReadRetryTest {
    @Test fun unavailableProviderReadRetainsWorkerRetryButDoesNotHideCancellationAccessOrIntegrityFailure(): Unit = runBlocking {
        assertEquals(ListenableWorker.Result.retry(), retryUnavailablePreparationRead { throw IOException("Provider unavailable") })
        val success = ListenableWorker.Result.success()
        assertSame(success, retryUnavailablePreparationRead { success })
        for (failure in listOf(CancellationException("Owner stopped"), SecurityException("Grant revoked"),
            IllegalStateException("PORTABLE_SOURCE_JOB_RECOVERY_REQUIRED"), IllegalStateException("LIBRARY_CHANGED"))) {
            val actual = runCatching { retryUnavailablePreparationRead { throw failure } }.exceptionOrNull()
            assertSame(failure, actual)
        }
    }
}
