package app.stemwise.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerStemActivationOwnerTest {
    @Test fun lateReplacedActivationCannotPublishFailureOrClearItsSuccessor() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val owner = PlayerStemActivationOwner()
        val failures = mutableListOf<String>()
        try {
            withTimeout(5_000L) {
                owner.start(this, {
                    withContext(NonCancellable) { first.await() }
                    Result.failure(IllegalStateException("stale"))
                }, { failures += it.message.orEmpty() })
                yield()
                owner.start(this, { second.await(); Result.success(Unit) }, { failures += "new" })
                yield()
                first.complete(Unit)
                yield()
                assertTrue(owner.pending)
                assertTrue(failures.isEmpty())
                second.complete(Unit)
                yield()
                assertFalse(owner.pending)
                owner.start(this, { CompletableDeferred<Unit>().await(); Result.success(Unit) }, { failures += "cancelled" })
                yield()
                owner.cancel()
                yield()
                assertFalse(owner.pending)
                assertTrue(failures.isEmpty())
            }
        } finally { first.complete(Unit); second.complete(Unit); owner.cancel() }
    }
}
