package app.stemwise.playback.session

import app.stemwise.artwork.artworkDiskCacheIdentity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class SessionArtworkOwnerTest {
    @Test fun replacedSameKeyFlightCannotCacheOrPublishAfterItsReplacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var loads = 0
        val old = SessionArtworkData(byteArrayOf(1))
        val replacement = SessionArtworkData(byteArrayOf(2))
        val owner = SessionArtworkOwner(scope, {
            if (++loads == 1) {
                withContext(NonCancellable) {
                    withTimeout(2_000) { release.await() }
                    finished.complete(Unit)
                }
                old
            } else replacement
        }, { 0L })
        val published = mutableListOf<SessionArtworkData?>()
        val key = key("same-song")
        try {
            owner.select(key) { published += owner.cached(it) }
            owner.cancelSelected()
            owner.select(key) { published += owner.cached(it) }
            assertEquals(listOf(replacement), published)
            release.complete(Unit)
            withTimeout(2_000) { finished.await() }
            yield()
            assertEquals(listOf(replacement), published)
            assertSame(replacement, owner.cached(key))
        } finally { release.complete(Unit); owner.close(); scope.cancel() }
    }

    @Test fun prefetchSharesCacheWhileFailuresExpireAtTheirMonotonicDeadline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 20L
        var loads = 0
        val data = SessionArtworkData(byteArrayOf(3))
        val owner = SessionArtworkOwner(scope, { if (++loads == 1) null else data }, { now }, 0L)
        val key = key("retry")
        val published = mutableListOf<SessionArtworkKey>()
        try {
            owner.select(key) { published += it }
            assertNull(owner.cached(key))
            now = 15_019L
            owner.prefetch(key) { published += it }
            assertEquals(1, loads)
            now = 15_020L
            owner.prefetch(key) { published += it }
            assertEquals(2, loads)
            assertSame(data, owner.cached(key))
            owner.select(key) { published += it }
            assertEquals(listOf(key, key), published)
            assertEquals(2, loads)
            owner.clear()
            assertNull(owner.cached(key))
            owner.select(key) { published += it }
            assertEquals(3, loads)
        } finally { owner.close(); scope.cancel() }
    }

    @Test fun cacheIsBoundedAndRevisionSpecificAndReleaseRetiresBothSlots() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val selectedFinished = CompletableDeferred<Unit>()
        val prefetchFinished = CompletableDeferred<Unit>()
        val data = SessionArtworkData(byteArrayOf(4))
        val owner = SessionArtworkOwner(scope, { key ->
            if (key.songId.startsWith("blocked")) withContext(NonCancellable) {
                withTimeout(2_000) { release.await() }
                if (key.songId == "blocked-selected") selectedFinished.complete(Unit)
                else prefetchFinished.complete(Unit)
            }
            data
        }, { 0L }, 0L)
        var publications = 0
        try {
            (0..8).forEach { owner.select(key("song-$it")) { publications++ } }
            assertNull(owner.cached(key("song-0")))
            assertSame(data, owner.cached(key("song-8")))
            assertNull(owner.cached(key("song-8").copy(mediaRevision = "replacement")))
            owner.select(key("blocked-selected")) { publications++ }
            owner.prefetch(key("blocked-next")) { publications++ }
            owner.close()
            assertTrue(scope.isActive)
            assertNull(owner.cached(key("song-8")))
            owner.select(key("after-close")) { publications++ }
            release.complete(Unit)
            withTimeout(2_000) { selectedFinished.await(); prefetchFinished.await() }
            yield()
            assertEquals(9, publications)
            assertNull(owner.cached(key("blocked-selected")))
            assertNull(owner.cached(key("blocked-next")))
        } finally { release.complete(Unit); owner.close(); scope.cancel() }
    }

    private fun key(id: String): SessionArtworkKey {
        val source = "/artwork/$id.png"
        return SessionArtworkKey(id, "revision-1", source,
            requireNotNull(artworkDiskCacheIdentity(source, "revision-1", id)))
    }
}
