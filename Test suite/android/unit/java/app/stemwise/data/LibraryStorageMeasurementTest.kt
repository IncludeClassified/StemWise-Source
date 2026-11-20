package app.stemwise.data

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryStorageMeasurementTest {
    @Test
    fun providerByteTotalsFailClosedInsteadOfWrappingNegative() {
        assertEquals(3_000_000_000L, checkedLibraryByteTotal(sequenceOf(1_000_000_000L, 2_000_000_000L)))
        assertThrows(ArithmeticException::class.java) {
            checkedLibraryByteTotal(sequenceOf(Long.MAX_VALUE, 1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkedLibraryByteTotal(sequenceOf(-1L))
        }
    }

    @Test
    fun displayOrderingUsesRealUnicodeAndStableArtistTies() {
        val rows = listOf(
            Triple("istanbul", "İstanbul", "Artist"),
            Triple("greek", "Αθήνα", "Artist"),
            Triple("bjork", "BjÖrk", "Artist"),
            Triple("umlaut", "äpfel", "Artist"),
            Triple("zulu", "Zulu", "Artist"),
            Triple("alpha-z", "Alpha", "Zulu"),
            Triple("alpha-a", "alpha", "Alpha")
        )
        val songs = rows.map { (id, title, artist) -> Song(id = id, videoId = id,
            title = title, artist = artist, durationMs = 1L, state = SongState.DISCOVERED,
            accent = Color.Black, artworkGlyph = "♪") }
        assertEquals(listOf("alpha-a", "alpha-z", "bjork", "istanbul", "zulu", "umlaut", "greek"),
            SongDisplayIndex().apply { replaceAll(songs) }.snapshot().map(Song::id))
    }

    @Test
    fun maximumLibraryColdFileSequenceCanBeConsumedForBothInventoryValues() {
        val filesBySong = List(8_192) { songIndex ->
            List(9) { fileIndex ->
                if ((songIndex + fileIndex) % 17 == 0) null
                else (songIndex + fileIndex + 1).toLong()
            }
        }
        val allFiles = filesBySong.asSequence().flatMap { it.asSequence() }

        val measuredBytes = checkedLibraryByteTotal(
            allFiles.map { it?.coerceAtLeast(0L) ?: 0L }
        )
        val unknownSizes = allFiles.count { it == null }

        assertEquals(
            filesBySong.sumOf { files -> files.sumOf { it ?: 0L } },
            measuredBytes
        )
        assertEquals(filesBySong.sumOf { files -> files.count { it == null } }, unknownSizes)
    }

    @Test
    fun physicalJsonCountNeverPromotesUnverifiedJsonAndMarksTheSnapshotDirty() {
        val previous = LibraryInventory(
            verifiedSongPackages = 49,
            verifiedOriginalFiles = 49,
            verifiedStemFiles = 294,
            verifiedArtworkFiles = 49,
            verifiedJsonFiles = 49,
            pcEnrichedSongManifests = 3,
            verifiedTotalBytes = 2_900_000_000L,
            lastCheckedEpochMs = 10L
        )
        val refreshed = mergeStorageMeasurement(
            previous,
            LibraryStorageMeasurement(
                songsFound = 49,
                originalFilesFound = 49,
                stemFilesFound = 294,
                jsonFilesFound = 50,
                artworkFilesFound = 49,
                actualLibraryBytes = 3_000_000_000L,
                unknownSizeFiles = 0,
                unreadableDirectories = 0,
                appPrivateTotalBytes = 198_000_000L,
                appDatabaseBytes = 18_000_000L,
                appWorkingBytes = 170_000_000L,
                appCacheBytes = 10_000_000L,
                measuredAtEpochMs = 20L
            )
        )

        assertEquals(50, refreshed.jsonFilesFound)
        assertEquals(49, refreshed.verifiedJsonFiles)
        assertEquals(3, refreshed.pcEnrichedSongManifests)
        assertEquals(3_000_000_000L, refreshed.actualLibraryBytes)
        assertEquals(198_000_000L, refreshed.appPrivateTotalBytes)
        assertEquals(20L, refreshed.lastCheckedEpochMs)
        assertTrue(refreshed.snapshotDirty)
    }

}
