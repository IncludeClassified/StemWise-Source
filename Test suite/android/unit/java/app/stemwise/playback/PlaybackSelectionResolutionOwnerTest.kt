package app.stemwise.playback

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackSelectionResolutionOwnerTest {
    private val song = Song(id = "A", videoId = "RESOLVE0001", title = "A", artist = "Artist",
        durationMs = 12_000, state = SongState.READY, accent = Color.Red, artworkGlyph = "A")

    @Test fun supersededProviderCannotPublishOrClearTheNewSelection() = runBlocking {
        val owner = PlaybackSelectionResolutionOwner()
        val blocked = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()
        owner.begin(1L, song, 4321L, true, true)
        owner.resolve(this, resolve = {
            withContext(NonCancellable) { blocked.await() }
            song to "old-path"
        }, isCurrent = { true }, onUnavailable = { error("stale provider altered current selection") },
            onResolved = { _, path, _ -> publications += path })
        yield()
        try {
            owner.begin(2L, song.copy(id = "B"), 3210L, false, true)
            owner.resolve(this, resolve = { song.copy(id = "B") to "verified-new-path" },
                isCurrent = { true }, onUnavailable = { error("new selection failed") },
                onResolved = { _, path, intent ->
                    assertFalse(intent.autoplay)
                    assertEquals(3210L, intent.positionMs)
                    assertTrue(intent.forceReload)
                    publications += path
                })
            owner.await()
        } finally { blocked.complete(Unit) }
        yield()
        assertEquals(listOf("verified-new-path"), publications)
        assertNull(owner.current)
    }

    @Test fun transportIntentBelongsToTheExactIncomingRequestAndFailureClearsIt() = runBlocking {
        val owner = PlaybackSelectionResolutionOwner()
        val blocked = CompletableDeferred<Unit>()
        owner.begin(3L, song, 5678L, true, false)
        var autoplay: Boolean? = null
        owner.resolve(this, resolve = { blocked.await(); song to "freshly-verified" },
            isCurrent = { true }, onUnavailable = { error("unexpected failure") },
            onResolved = { _, _, playing -> autoplay = playing.autoplay })
        yield()
        owner.updateTransport(2L, false)
        assertTrue(requireNotNull(owner.current).autoplay)
        owner.updateTransport(3L, false)
        blocked.complete(Unit)
        owner.await()
        assertEquals(false, autoplay)
        assertNull(owner.current)
        val rejected = IllegalStateException("unreadable Original")
        var observed: Throwable? = null
        owner.begin(4L, song, 0L, true, false)
        owner.resolve(this, resolve = { throw rejected }, isCurrent = { true },
            onUnavailable = { observed = it }, onResolved = { _, _, _ -> error("failure published") })
        owner.await()
        assertSame(rejected, observed)
        assertNull(owner.current)
    }
}
