package app.stemwise.work

import androidx.work.ListenableWorker
import app.stemwise.bridge.BridgeSongWorkProgress
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BridgeSongWorkerTest {
    @Test fun unchangedStatusBacksOffAndAMeaningfulChangeResetsTheWait() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempt = 0
        var task = app.stemwise.bridge.BridgeSongTask("id", "video", "title",
            app.stemwise.bridge.BridgeSongPhase.RUNNING, "Source")
        advanceBridgeSongWork(waitForRemote = { waits += it }, observation = { task }) {
            attempt++
            if (attempt == 4) task = task.copy(stage = "Alignment")
            app.stemwise.bridge.BridgeSongWorkProgress.PENDING
        }
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 15_000L, 30_000L), waits)
    }

    @Test fun healthyPendingProgressNeverUsesFailureBackoff() = runBlocking {
        var polls = 0
        var waits = 0
        var continuations = 0
        val result = advanceBridgeSongWork(waitForRemote = { waits++ }, continuePending = { continuations++ }) { polls++; BridgeSongWorkProgress.PENDING }
        assertEquals(6, polls)
        assertEquals(5, waits)
        assertEquals(1, continuations)
        assertEquals("Healthy progress must schedule a bounded continuation, not failure backoff",
            ListenableWorker.Result.success(), result)
    }

    @Test fun cancellationAndPermanentRecordDefectsAreNotRetriedAsStorageFailures() = runBlocking {
        val cancellation = CancellationException("worker stopped")
        try { advanceBridgeSongWork { throw cancellation }; fail("Cancellation was swallowed") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        val defect = IllegalStateException("BRIDGE_OUTBOX_INVALID")
        try { advanceBridgeSongWork { throw defect }; fail("Permanent record defect was swallowed") }
        catch (actual: IllegalStateException) { assertSame(defect, actual) }
        assertEquals(ListenableWorker.Result.retry(), advanceBridgeSongWork { throw IOException("disk full") })
    }
}
