package app.stemwise.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One remembered screen owner retires its exact activation and ignores stale completions. */
internal class PlayerStemActivationOwner {
    private val gate = LatestStemActivationGate()
    private var job: Job? = null
    var pending by mutableStateOf(false)
        private set

    fun start(scope: CoroutineScope, activate: suspend () -> Result<Unit>, onFailure: (Throwable) -> Unit) {
        val token = gate.issue()
        job?.cancel()
        pending = true
        job = scope.launch {
            try {
                activate().onFailure { if (gate.owns(token)) onFailure(it) }
            } finally {
                if (gate.owns(token)) { pending = false; job = null }
            }
        }
    }

    fun cancel() {
        gate.invalidate()
        job?.cancel()
        job = null
        pending = false
    }
}
