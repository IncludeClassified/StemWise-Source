package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySongRowPresentationTest {
    @Test
    fun flacLabelFollowsTheActualOriginalCodecAndIsAccessible() {
        val native = song().copy(sourceCodec = "flac", sourceContainer = "flac")
        assertEquals("Song, Artist, Album, FLAC, 0:01", librarySongRowPresentation(native).baseAccessibilityDescription)
        for (codec in listOf(null, "opus", "aac")) {
            val other = native.copy(title = "Song.flac", sourceCodec = codec, hasStems = true)
            assertEquals("Song.flac, Artist, Album, 0:01", librarySongRowPresentation(other).baseAccessibilityDescription)
        }
    }

    @Test
    fun derivesDetailDurationAndCompleteAccessibilityStrings() {
        val presentation = librarySongRowPresentation(
            song(
                title = "Night Drive",
                artist = "StemWise",
                album = "Local Sessions",
                durationMs = 185_999L
            )
        )

        assertEquals("StemWise", presentation.detail)
        assertEquals("StemWise", presentation.artistLine)
        assertEquals("Album · Local Sessions", presentation.albumLine)
        assertEquals("3:05", presentation.duration)
        assertEquals(
            "Night Drive, StemWise, Local Sessions, 3:05",
            presentation.baseAccessibilityDescription
        )
    }

    @Test
    fun detailKeepsThePreviousBlankArtistAndAlbumProjection() {
        val albumOnly = librarySongRowPresentation(song(artist = "", album = "Album only"))
        assertEquals("Album only", albumOnly.detail)
        assertEquals("", albumOnly.artistLine)
        assertEquals("Album · Album only", albumOnly.albumLine)

        val artistOnly = librarySongRowPresentation(song(artist = "Artist only", album = ""))
        assertEquals("Artist only", artistOnly.detail)
        assertEquals("Artist only", artistOnly.artistLine)
        assertEquals("", artistOnly.albumLine)

        val blank = librarySongRowPresentation(song(artist = "", album = ""))
        assertEquals("", blank.detail)
        assertEquals("", blank.artistLine)
        assertEquals("", blank.albumLine)
    }

    @Test
    fun nonPositiveDurationUsesTheSameClampedClockAndBaseDescription() {
        val presentation = librarySongRowPresentation(
            song(title = "Unknown", artist = "Local", durationMs = -500L)
        )

        assertEquals("0:00", presentation.duration)
        assertEquals("Unknown, Local, Album, 0:00", presentation.baseAccessibilityDescription)
    }

    private fun song(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 1_000L
    ): Song = Song(
        id = "$title-$artist-$album-$durationMs",
        videoId = "video",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        state = SongState.READY,
        accent = Color.Black,
        artworkGlyph = "S"
    )
}
