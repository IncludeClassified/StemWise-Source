package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class LibrarySearchPreparationOwnerTest {
    @Test fun idleBrowsingDoesNoWorkAndAQueryUsesActualMatchingAndRetainsItsIndex() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var indexBuilds = 0
        val owner = LibrarySearchPreparationOwner(scope) { snapshot, query, retained ->
            if (retained == null) indexBuilds++
            prepareLibrarySearch(snapshot, query, retained)
        }
        val songs = listOf(song("one", "Violet Circuit"), song("two", "Amber Sky"))
        try {
            owner.request(songs, null, false)
            assertEquals(0, indexBuilds)
            owner.request(songs, librarySearchQueryKey("violet"), true)
            assertEquals(listOf(songs[0]), withTimeout(2_000L) { owner.result.first { it != null } }!!.songs)
            owner.request(songs, librarySearchQueryKey("amber"), true)
            assertEquals(listOf(songs[1]), withTimeout(2_000L) { owner.result.first { it != null } }!!.songs)
            assertEquals(1, indexBuilds)
        } finally { scope.cancel() }
    }

    @Test fun cancelledOldSearchCannotPublishAfterTheNewQueryEvenIfWorkerFinishesLate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldEntered = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val oldQuery = librarySearchQueryKey("violet")
        val owner = LibrarySearchPreparationOwner(scope) { snapshot, query, retained ->
            if (query == oldQuery) withContext(NonCancellable) {
                oldEntered.complete(Unit)
                withTimeout(2_000L) { releaseOld.await() }
            }
            val result = withContext(NonCancellable) { prepareLibrarySearch(snapshot, query, retained) }
            if (query == oldQuery) oldFinished.complete(Unit)
            result
        }
        val songs = listOf(song("one", "Violet Circuit"), song("two", "Amber Sky"))
        try {
            owner.request(songs, oldQuery, true)
            withTimeout(2_000L) { oldEntered.await() }
            val newQuery = librarySearchQueryKey("amber")
            owner.request(songs, newQuery, true)
            val current = withTimeout(2_000L) { owner.result.first { it?.query == newQuery } }
            assertEquals(listOf(songs[1]), current!!.songs)
            releaseOld.complete(Unit)
            withTimeout(2_000L) { oldFinished.await() }
            yield()
            assertSame(current, owner.result.value)
            assertEquals(emptyList<Song>(), libraryVisibleSearchSongs(songs.toList(), newQuery, current))
        } finally { releaseOld.complete(Unit); scope.cancel() }
    }

    @Test fun disposalCancelsTheActualPreparationBeforeItCanPublish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val owner = LibrarySearchPreparationOwner(scope) { _, _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        owner.request(listOf(song("one", "Violet")), librarySearchQueryKey("violet"), true)
        withTimeout(2_000L) { entered.await() }
        scope.cancel()
        withTimeout(2_000L) { cancelled.await() }
        assertNull(owner.result.value)
    }

    private fun song(id: String, title: String) = Song(id = id, videoId = "SEARCH00001", title = title,
        artist = "StemWise", durationMs = 12_000L, state = SongState.READY,
        accent = Color(0xFFA970FF), artworkGlyph = "VC")
}
