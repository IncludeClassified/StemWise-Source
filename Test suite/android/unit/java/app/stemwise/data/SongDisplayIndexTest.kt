package app.stemwise.data

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.Random

class SongDisplayIndexTest {
    @Test fun eachPublishedSnapshotReusesOrderWithoutComparingTheWholeCatalog() {
        var comparisons = 0
        val index = SongDisplayIndex { comparisons++ }
        val insertionOrder = (0 until 512).toMutableList().apply { Collections.shuffle(this, Random(17L)) }
        for (number in insertionOrder) {
            val song = song(number.toString(), "Song ${number.toString().padStart(3, '0')}", "Artist")
            assertTrue(index.upsert(song))
            val beforePublication = comparisons
            val snapshot = index.snapshot()
            assertEquals("Publishing re-sorted the catalog", beforePublication, comparisons)
            assertEquals(insertionOrder.take(snapshot.size).sorted().map(Int::toString), snapshot.map(Song::id))
        }
        assertTrue("Insertion compared the whole growing catalog: $comparisons", comparisons <= 512 * 24)
    }

    @Test fun updatesRemovalsClearAndCachedRestorePreserveOrderAndPriorSnapshots() {
        var comparisons = 0
        val index = SongDisplayIndex { comparisons++ }
        val beta = song("b", "beta", "Artist")
        val alphaZed = song("z", "Alpha", "zed")
        val alphaAmyB = song("amy-b", "alpha", "Amy")
        val alphaAmyA = song("amy-a", "ALPHA", "amy")
        index.replaceAll(listOf(beta, alphaZed, alphaAmyB, alphaAmyA))
        val restored = index.snapshot()
        assertEquals(listOf("amy-a", "amy-b", "z", "b"), restored.map(Song::id))

        val beforeUnchanged = comparisons
        assertFalse(index.upsert(alphaAmyA.copy()))
        assertEquals(beforeUnchanged, comparisons)
        assertTrue(index.upsert(beta.copy(title = "Aardvark")))
        assertEquals(listOf("b", "amy-a", "amy-b", "z"), index.snapshot().map(Song::id))
        assertTrue(index.upsert(alphaZed.copy(artist = "Aaron")))
        assertEquals(listOf("b", "z", "amy-a", "amy-b"), index.snapshot().map(Song::id))
        assertTrue(index.upsert(alphaAmyA.copy(album = "New album")))
        assertEquals("New album", index["amy-a"]?.album)
        assertTrue(index.remove("z"))
        assertFalse(index.remove("absent"))
        assertEquals(listOf("b", "amy-a", "amy-b"), index.snapshot().map(Song::id))
        assertEquals(setOf("b", "amy-a", "amy-b"), index.keys)
        assertEquals(index.keys, index.values.map(Song::id).toSet())
        index.clear()
        assertTrue(index.snapshot().isEmpty())
        assertNull(index["b"])
        index.replaceAll(restored)
        assertEquals(restored, index.snapshot())
        assertEquals(listOf("amy-a", "amy-b", "z", "b"), restored.map(Song::id))
    }

    private fun song(id: String, title: String, artist: String) = Song(
        id, id, title, artist, 60_000L, SongState.READY, Color.Cyan, artworkGlyph = "S")
}
