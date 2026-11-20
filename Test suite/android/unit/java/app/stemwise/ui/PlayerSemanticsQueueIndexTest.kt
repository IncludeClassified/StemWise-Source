package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSemanticsQueueIndexTest {
    @Test
    fun maximumQueueUnchangedInputsDoNotRebuildProjectionOrVisualMembership() {
        val backing = List(50_000) { index -> song("song-$index") }
        val songs = CountingSongList(backing)
        val order = backing.map(Song::id)
        val projection = PlayerQueueProjection(songs, order)
        val reads = songs.readCount
        val projected = projection.orderedSongsState.value
        repeat(100) { projection.update(songs, order) }
        assertEquals(reads, songs.readCount)
        assertSame(projected, projection.orderedSongsState.value)
        val visual = PlayerVisualOwnerState("song-0")
        var membershipReads = 0
        val ids = object : AbstractList<String>() {
            override val size: Int get() = order.size
            override fun get(index: Int): String { membershipReads++; return order[index] }
        }
        visual.updateOrder(ids)
        val built = membershipReads
        repeat(100) { visual.updateOrder(ids) }
        assertEquals(built, membershipReads)
        val shuffled = order.reversed()
        projection.update(songs, shuffled)
        assertEquals("song-49999", projection.orderedSongsState.value.first().id)
    }

    @Test
    fun fourHundredSongQueueResolvesRepeatedPositionPublicationsWithoutQueueScans() {
        val backing = List(400) { index -> song("song-$index") }
        val songs = CountingSongList(backing)
        val queueIndex = PlayerSemanticsQueueIndex(songs)
        val constructionReads = songs.readCount

        repeat(10_000) {
            val selected = queueIndex.resolve("song-399", backing.first())
            assertSame(backing[399], selected.song)
            assertEquals(399, selected.queueIndex)
        }

        assertEquals(400, queueIndex.size)
        // One indexed read returns the selected Song. A linear lookup would add four million reads.
        assertEquals(constructionReads + 10_000, songs.readCount)
    }

    @Test
    fun staleSemanticOwnerFallsBackToCurrentSongAndItsRealQueueIndex() {
        val songs = List(400) { index -> song("song-$index") }
        val queueIndex = PlayerSemanticsQueueIndex(songs)

        val selected = queueIndex.resolve("retired-owner", songs[217])

        assertSame(songs[217], selected.song)
        assertEquals(217, selected.queueIndex)
    }

    @Test
    fun queueOwnerHandoffKeepsAValidIndexUntilTheFallbackJoinsTheQueue() {
        val songs = listOf(song("old-owner"), song("next-owner"))
        val incoming = song("not-published-yet")

        val selected = PlayerSemanticsQueueIndex(songs).resolve(
            semanticSongId = "retired-owner",
            fallback = incoming
        )

        assertSame(incoming, selected.song)
        assertEquals(0, selected.queueIndex)
    }

    @Test
    fun playingClockLeavesAnIdleWindowAndStaysAboutOneSecondCoarse() {
        assertEquals(
            61_250L,
            playerSurfaceSemanticPositionMs(
                exactPositionMs = 62_345L,
                durationMs = 180_000L,
                playWhenReady = true
            )
        )
        assertEquals(
            61_250L,
            playerSurfaceSemanticPositionMs(
                exactPositionMs = 62_499L,
                durationMs = 180_000L,
                playWhenReady = true
            )
        )
        assertEquals(
            62_500L,
            playerSurfaceSemanticPositionMs(
                exactPositionMs = 62_500L,
                durationMs = 180_000L,
                playWhenReady = true
            )
        )
        assertFalse(playerSurfaceRequiresExternalTimelineInvalidation(playWhenReady = true))
    }

    @Test
    fun pausedExternalSeekRemainsExactAndRequestsOneKeyedNativeInvalidation() {
        assertEquals(
            62_345L,
            playerSurfaceSemanticPositionMs(
                exactPositionMs = 62_345L,
                durationMs = 180_000L,
                playWhenReady = false
            )
        )
        assertTrue(playerSurfaceRequiresExternalTimelineInvalidation(playWhenReady = false))
    }

    private fun song(id: String): Song = Song(
        id = id,
        videoId = id,
        title = id,
        artist = "Artist",
        durationMs = 180_000L,
        state = SongState.READY,
        accent = Color.Blue,
        artworkGlyph = "S"
    )

    private class CountingSongList(
        private val delegate: List<Song>
    ) : AbstractList<Song>() {
        var readCount: Int = 0
            private set

        override val size: Int get() = delegate.size

        override fun get(index: Int): Song {
            readCount++
            return delegate[index]
        }
    }
}
