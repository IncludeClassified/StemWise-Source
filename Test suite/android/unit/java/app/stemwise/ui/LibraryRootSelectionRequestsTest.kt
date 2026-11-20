package app.stemwise.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LibraryRootSelectionRequestsTest {
    @Test fun earlierValidationCannotPublishOrConfirmAfterTheNewerChoice() = runBlocking {
        val requests = LibraryRootSelectionRequests()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pending: Pair<Long, String>? = null
        val first = requests.begin()
        val validation = async {
            entered.complete(Unit)
            withTimeout(3_000L) { release.await() }
            if (requests.isCurrent(first)) pending = first to "A"
        }
        try {
            withTimeout(3_000L) { entered.await() }
            val second = requests.begin()
            if (requests.isCurrent(second)) pending = second to "B"
            release.complete(Unit)
            withTimeout(3_000L) { validation.await() }
            assertEquals("B", pending?.second)
            assertTrue(requests.isCurrent(checkNotNull(pending).first))
            assertFalse(requests.isCurrent(first))
            requests.begin()
            assertFalse(requests.isCurrent(second))
        } finally {
            release.complete(Unit)
            validation.cancelAndJoin()
        }
    }
}
