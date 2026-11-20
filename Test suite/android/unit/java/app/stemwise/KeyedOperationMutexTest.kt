package app.stemwise

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KeyedOperationMutexTest {
    @Test fun holdersAndWaitersRetainOneExactKeyUntilTheLastOwnerRetires() = runBlocking {
        val owner = KeyedOperationMutex()
        withTimeout(5_000L) {
            repeat(100) { index ->
                val release = CompletableDeferred<Unit>()
                val entered = CompletableDeferred<Unit>()
                var inCriticalSection = 0
                val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.withKey("task-$index") { inCriticalSection++; entered.complete(Unit); release.await(); inCriticalSection-- }
                }
                entered.await()
                val cancelledWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.withKey("task-$index") { fail("Cancelled waiter entered") }
                }
                var followed = false
                val follower = launch(start = CoroutineStart.UNDISPATCHED) {
                    owner.withKey("task-$index") { assertEquals(0, inCriticalSection); followed = true }
                }
                cancelledWaiter.cancelAndJoin()
                assertEquals(1, owner.activeKeyCount())
                assertFalse(followed)
                release.complete(Unit)
                holder.join(); follower.join()
                assertTrue(followed)
                assertEquals(0, owner.activeKeyCount())
                owner.withKey("task-$index") { assertEquals(1, owner.activeKeyCount()) }
                assertEquals(0, owner.activeKeyCount())
            }
        }
    }
}
