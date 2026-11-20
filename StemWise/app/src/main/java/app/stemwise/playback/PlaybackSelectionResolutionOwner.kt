package app.stemwise.playback

import app.stemwise.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread owner of the incoming lookup and transport intent; outgoing audio stays separate. */
internal class PlaybackSelectionResolutionOwner {
    data class Request(val serial: Long, val song: Song, val positionMs: Long,
        val autoplay: Boolean, val forceReload: Boolean, val restartAtZero: Boolean = false,
        val preservePreviousPair: Boolean = false) {
        val songId get() = song.id
    }
    var current: Request? = null
        private set
    private var job: Job? = null

    fun begin(serial: Long, song: Song, positionMs: Long, autoplay: Boolean, forceReload: Boolean,
        restartAtZero: Boolean = false, preservePreviousPair: Boolean = false) {
        job?.cancel()
        current = Request(serial, song, positionMs, autoplay, forceReload, restartAtZero, preservePreviousPair)
    }

    fun updateTransport(serial: Long, autoplay: Boolean) {
        current?.takeIf { it.serial == serial }?.let { current = it.copy(autoplay = autoplay) }
    }

    fun resolve(scope: CoroutineScope, resolve: suspend () -> Pair<Song, String>?,
        isCurrent: () -> Boolean, onUnavailable: (Throwable?) -> Unit,
        onResolved: (Song, String, Request) -> Unit) {
        val request = requireNotNull(current)
        job = scope.launch {
            val resolved = resolveCurrentManualSelection(resolve,
                isCurrent = { current?.serial == request.serial && isCurrent() },
                onUnavailable = { failure -> clear(); onUnavailable(failure) }) ?: return@launch
            val intent = requireNotNull(current)
            clear()
            onResolved(resolved.first, resolved.second, intent)
        }
    }

    fun clear() { current = null }
    fun cancel() { job?.cancel(); job = null; clear() }
    suspend fun await() { job?.join() }
}
