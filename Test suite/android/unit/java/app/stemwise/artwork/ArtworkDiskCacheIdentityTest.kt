package app.stemwise.artwork

import androidx.compose.ui.graphics.Color
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.session.sessionArtworkDiskCacheIdentity
import app.stemwise.ui.commonArtworkDiskCacheIdentity
import app.stemwise.ui.player.surface.PlayerSurfaceArtworkRequest
import app.stemwise.ui.player.surface.PlayerSurfaceOwnerKey
import app.stemwise.ui.player.surface.playerSurfaceArtworkDiskCacheIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkDiskCacheIdentityTest {
    @Test
    fun libraryAndPlayerShareOneExactProcessMemoryKey() {
        val song = song(mediaRevision = "manifest-42")
        val source = requireNotNull(song.artworkSource)

        assertEquals(
            "artwork:song-1:manifest-42:$source",
            artworkMemoryCacheKey(" song-1 ", " manifest-42 ", " $source ")
        )
    }

    @Test
    fun onlinePrimeByOneConsumerIsTheSameDiskEntryForAllSongArtworkConsumers() {
        val song = song(mediaRevision = "manifest-42")
        val playerRequest = playerRequest(song, generation = 91L, widthPx = 1_080)

        val common = requireNotNull(commonArtworkDiskCacheIdentity(song))
        val player = requireNotNull(playerSurfaceArtworkDiskCacheIdentity(playerRequest))
        val session = requireNotNull(sessionArtworkDiskCacheIdentity(song))

        assertEquals(common.canonicalKey, player.canonicalKey)
        assertEquals(common.canonicalKey, session.canonicalKey)
        assertEquals(common.normalizedPhysicalSource, player.normalizedPhysicalSource)
        assertFalse(common.canonicalKey.contains("1080"))
        assertFalse(common.canonicalKey.contains("91"))


    }

    @Test
    fun blankRevisionFallbackIsStableAcrossPlayerGenerationsAndDecodeSizes() {
        val song = song(mediaRevision = "")
        val first = requireNotNull(
            playerSurfaceArtworkDiskCacheIdentity(
                playerRequest(song, generation = 1L, widthPx = 256)
            )
        )
        val replacement = requireNotNull(
            playerSurfaceArtworkDiskCacheIdentity(
                playerRequest(song, generation = 400L, widthPx = 1_536)
            )
        )

        assertEquals(first.canonicalKey, replacement.canonicalKey)
        assertEquals(
            requireNotNull(commonArtworkDiskCacheIdentity(song)).canonicalKey,
            first.canonicalKey
        )
        assertEquals(
            requireNotNull(sessionArtworkDiskCacheIdentity(song)).canonicalKey,
            first.canonicalKey
        )
        assertTrue(first.contentRevision.startsWith("source-song-v1:"))
    }

    @Test
    fun physicalSourceNormalizationIgnoresUriPresentationOnlyDifferences() {
        val first = requireNotNull(
            artworkDiskCacheIdentity(
                "  HTTPS://ART.EXAMPLE.test:443/covers/./song.png#player  ",
                "revision-1",
                "song-1"
            )
        )
        val second = requireNotNull(
            artworkDiskCacheIdentity(
                "https://art.example.test/covers/song.png",
                "revision-1",
                "song-1"
            )
        )

        assertEquals(second.normalizedPhysicalSource, first.normalizedPhysicalSource)
        assertEquals(second.canonicalKey, first.canonicalKey)
    }

    @Test
    fun changedRevisionAlwaysSelectsNewEncodedBytes() {
        val old = requireNotNull(artworkDiskCacheIdentity("https://art.test/cover.png", "old", "song"))
        val current = requireNotNull(artworkDiskCacheIdentity("https://art.test/cover.png", "new", "song"))
        assertNotEquals(old.canonicalKey, current.canonicalKey)
        assertNotEquals("https://art.test/cover.png", current.canonicalKey)
        assertEquals(current, artworkDiskCacheIdentity("https://art.test/cover.png", "new", "song"))
    }

    private fun song(mediaRevision: String): Song = Song(
        id = "song-1",
        videoId = "video-1",
        title = "Offline cover",
        artist = "StemWise",
        durationMs = 60_000L,
        state = SongState.READY,
        accent = Color.Black,
        artworkGlyph = "OC",
        artworkSource = "https://ART.EXAMPLE.test/covers/song.png",
        mediaRevision = mediaRevision
    )

    private fun playerRequest(
        song: Song,
        generation: Long,
        widthPx: Int
    ): PlayerSurfaceArtworkRequest = PlayerSurfaceArtworkRequest(
        owner = PlayerSurfaceOwnerKey(
            generation = generation,
            songId = song.id,
            mediaRevision = song.mediaRevision,
            audioRevision = "audio-1",
            stemRevision = "stems-1"
        ),
        source = requireNotNull(song.artworkSource),
        widthPx = widthPx,
        heightPx = widthPx
    )
}
