package app.stemwise.playback

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackgroundWorkState(
    val heavyWorkActive: Boolean = false,
    val waitingWork: String? = null,
    val activeWork: String? = null
)

/**
 * Serializes process-heavy background mutations without observing playback state.
 * Checkpoints preserve coroutine cancellation in long copy/hash loops.
 */
class BackgroundWorkCoordinator {
    private val heavyWorkMutex = Mutex()
    private val mutableState = MutableStateFlow(BackgroundWorkState())
    val state: StateFlow<BackgroundWorkState> = mutableState.asStateFlow()
    private val requests = linkedMapOf<Any, String>()
    private var activeRequest: Any? = null

    private fun publishRequests() {
        mutableState.value = BackgroundWorkState(
            heavyWorkActive = activeRequest != null,
            activeWork = requests[activeRequest],
            waitingWork = requests.entries.firstOrNull { it.key !== activeRequest }?.value
        )
    }

    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
    }

    suspend fun <T> withHeavyWork(label: String, block: suspend () -> T): T {
        val request = Any()
        synchronized(requests) {
            requests[request] = label
            publishRequests()
        }
        try {
            return heavyWorkMutex.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(requests) {
                    activeRequest = request
                    publishRequests()
                }
                try { block() } finally {
                    synchronized(requests) {
                        activeRequest = null
                        requests.remove(request)
                        publishRequests()
                    }
                }
            }
        } finally {
            synchronized(requests) {
                requests.remove(request)
                publishRequests()
            }
        }
    }
}
