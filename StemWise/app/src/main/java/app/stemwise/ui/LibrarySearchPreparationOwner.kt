package app.stemwise.ui

import app.stemwise.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PreparedLibrarySearch(
    val index: LibrarySongSearchIndex,
    val result: LibrarySongSearchResult?
)

internal suspend fun prepareLibrarySearch(
    snapshot: List<Song>,
    query: LibrarySearchQueryKey?,
    retainedIndex: LibrarySongSearchIndex?
): PreparedLibrarySearch = withContext(Dispatchers.Default) {
    val worker = currentCoroutineContext()
    val index = retainedIndex?.takeIf { it.snapshot === snapshot } ?: LibrarySongSearchIndex(
        snapshot, snapshot.mapIndexed { position, song ->
            if ((position and 63) == 0) worker.ensureActive()
            librarySongSearchKey(song)
        })
    val result = query?.let { key -> LibrarySongSearchResult(snapshot, key,
        snapshot.filterIndexed { position, _ ->
            if ((position and 255) == 0) worker.ensureActive()
            index.keys[position].matches(key)
        }) }
    PreparedLibrarySearch(index, result)
}

/** Composition owns its scope; only the latest exact snapshot/query may publish worker output. */
internal class LibrarySearchPreparationOwner(
    private val scope: CoroutineScope,
    private val prepare: suspend (List<Song>, LibrarySearchQueryKey?, LibrarySongSearchIndex?) -> PreparedLibrarySearch =
        ::prepareLibrarySearch
) {
    private var requestSerial = 0L
    private var snapshot: List<Song>? = null
    private var query: LibrarySearchQueryKey? = null
    private var requested = false
    private var job: Job? = null
    private var index: LibrarySongSearchIndex? = null
    private val mutableResult = MutableStateFlow<LibrarySongSearchResult?>(null)
    val result = mutableResult.asStateFlow()

    fun request(nextSnapshot: List<Song>, nextQuery: LibrarySearchQueryKey?, indexRequested: Boolean) {
        val nextRequested = indexRequested || nextQuery != null
        if (snapshot === nextSnapshot && query == nextQuery && requested == nextRequested) return
        snapshot = nextSnapshot
        query = nextQuery
        requested = nextRequested
        val serial = ++requestSerial
        job?.cancel()
        mutableResult.value = null
        if (index?.snapshot !== nextSnapshot) index = null
        if (!nextRequested) return
        job = scope.launch {
            val prepared = prepare(nextSnapshot, nextQuery, index)
            if (!isActive || serial != requestSerial) return@launch
            check(prepared.index.snapshot === nextSnapshot &&
                (prepared.result == null || prepared.result.snapshot === nextSnapshot && prepared.result.query == nextQuery))
            index = prepared.index
            mutableResult.value = prepared.result
        }
    }
}
