package app.stemwise.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMutationSafetyTest {
    @Test
    fun `confirmed Library mutation finishes after its UI owner is cancelled`() = runBlocking {
        withTimeout(TEST_HANG_GUARD_MS) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = AtomicBoolean(false)
            val owner = launch {
                completeConfirmedLibraryMutation {
                    entered.complete(Unit)
                    release.await()
                    completed.set(true)
                }
            }

            try {
                entered.await()
                owner.cancel(CancellationException("UI owner recreated"))
                release.complete(Unit)
                owner.join()

                assertTrue(completed.get())
            } finally {
                // A failed assertion must not strand the deliberately non-cancellable child.
                release.complete(Unit)
                owner.cancelAndJoin()
            }
        }
    }

    private companion object {
        const val TEST_HANG_GUARD_MS = 5_000L
    }
}
