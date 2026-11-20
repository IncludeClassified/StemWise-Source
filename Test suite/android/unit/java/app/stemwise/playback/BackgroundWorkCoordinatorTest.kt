package app.stemwise.playback

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class BackgroundWorkCoordinatorTest {
    @Test
    fun heavyWorkIsSerializedWithoutPlaybackState() = runBlocking {
        withTimeout(TEST_WATCHDOG_MS) {
            val coordinator = BackgroundWorkCoordinator()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()

            val first = launch {
                coordinator.withHeavyWork("First") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstStarted.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withHeavyWork("Second") {
                    secondStarted.complete(Unit)
                }
            }

            assertTrue(coordinator.state.value.heavyWorkActive)
            assertFalse(secondStarted.isCompleted)
            releaseFirst.complete(Unit)
            first.join()
            second.join()
            assertTrue(secondStarted.isCompleted)
            assertFalse(coordinator.state.value.heavyWorkActive)
            assertNull(coordinator.state.value.waitingWork)
            assertNull(coordinator.state.value.activeWork)
        }
    }

    @Test
    fun cancelledWaitersCannotEraseTheNextOwnedRequest() = runBlocking {
        withTimeout(TEST_WATCHDOG_MS) {
            val coordinator = BackgroundWorkCoordinator()
            val releaseFirst = CompletableDeferred<Unit>()
            val releaseThird = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withHeavyWork("A") { releaseFirst.await() }
            }
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withHeavyWork("B") { error("Cancelled waiter started") }
            }
            val third = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.withHeavyWork("C") { releaseThird.await() }
            }
            assertEquals("B", coordinator.state.value.waitingWork)
            second.cancelAndJoin()
            assertEquals("C", coordinator.state.value.waitingWork)
            assertEquals("A", coordinator.state.value.activeWork)
            releaseFirst.complete(Unit)
            first.join()
            assertEquals("C", coordinator.state.value.activeWork)
            assertNull(coordinator.state.value.waitingWork)
            releaseThird.complete(Unit)
            third.join()
            assertEquals(BackgroundWorkState(), coordinator.state.value)
        }
    }

    private companion object {
        const val TEST_WATCHDOG_MS = 5_000L
    }
}
