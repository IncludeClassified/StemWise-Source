package app.stemwise.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

/** Root replacement waits for actual coroutine/SDK retirement before touching import scratch. */
internal class BridgeOperationOwner {
    private val guard = Any()
    private var accepting = true
    private val active = mutableSetOf<Job>()

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(guard) {
            if (!accepting) throw CancellationException("LIBRARY_CHANGED")
            active += job
        }
        job.invokeOnCompletion { synchronized(guard) { active -= job } }
        block()
    }

    suspend fun closeAndJoin() {
        val departing = synchronized(guard) { accepting = false; active.toList() }
        departing.forEach { it.cancel() }
        withContext(NonCancellable) { departing.joinAll() }
    }

    fun reopen() = synchronized(guard) {
        if (!accepting) {
            check(active.isEmpty()) { "BRIDGE_WORK_NOT_RETIRED" }
            accepting = true
        }
    }
}
