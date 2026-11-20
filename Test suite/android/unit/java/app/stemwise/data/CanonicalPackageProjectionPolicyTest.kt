package app.stemwise.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalPackageProjectionPolicyTest {
    @Test
    fun incompleteAndNoncanonicalInputsCannotProduceAPublicProjection() {
        val existing = song().copy(playlistItemId = "retained-item", contentRevision = 8L)
        for (state in listOf(null, "prepared", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                promotedCanonicalPackageProjection(song().copy(manifestState = state), null, existing)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            promotedCanonicalPackageProjection(song().copy(canonicalExternal = false), null, existing)
        }
        assertEquals("retained-item", existing.playlistItemId)
        assertEquals(8L, existing.contentRevision)
    }

    @Test
    fun completeProjectionUsesCurrentMetadataAndRetiresThePlaylistArtworkFallback() {
        val prior = song().copy(youtubeVideoId = "retained-video", playlistItemId = "retained-item",
            canonicalUrl = "https://example.test/retained", sourceTitle = "Playlist title",
            sourceArtist = "Playlist artist", currentlyInSourcePlaylist = true,
            createdAtEpochMs = 11L, acquiredAtEpochMs = 22L,
            additionalInfoJson = """{"artworkSourceUrl":"https://i.ytimg.com/retained.jpg"}""")
        val incoming = song().copy(displayTitle = "Current title", displayArtist = "Current artist",
            displayAlbum = "Current album", displayYear = "2026", contentRevision = 9L,
            additionalInfoJson = """{"Channel":"Current channel"}""")
        val result = promotedCanonicalPackageProjection(incoming, null, prior)
        assertEquals(prior.youtubeVideoId, result.song.youtubeVideoId)
        assertEquals(prior.playlistItemId, result.song.playlistItemId)
        assertEquals(prior.canonicalUrl, result.song.canonicalUrl)
        assertEquals(prior.sourceTitle, result.song.sourceTitle)
        assertEquals(prior.sourceArtist, result.song.sourceArtist)
        assertEquals(prior.currentlyInSourcePlaylist, result.song.currentlyInSourcePlaylist)
        assertEquals(prior.createdAtEpochMs, result.song.createdAtEpochMs)
        assertEquals(prior.acquiredAtEpochMs, result.song.acquiredAtEpochMs)
        assertEquals("Current title", result.song.displayTitle)
        assertEquals("Current artist", result.song.displayArtist)
        assertEquals("Current album", result.song.displayAlbum)
        assertEquals("2026", result.song.displayYear)
        assertEquals(9L, result.song.contentRevision)
        assertEquals(incoming.artworkPath, result.song.artworkPath)
        val info = JSONObject(result.song.additionalInfoJson)
        assertEquals("Current channel", info.getString("Channel"))
        org.junit.Assert.assertFalse(info.has(ROOM_ARTWORK_SOURCE_URL_KEY))
        assertNull(result.analysis)
    }

    @Test
    fun currentCompleteAnalysisIsAuthoritativeAndUnavailableValuesRemainUnavailable() {
        val incoming = song()
        val analysis = AnalysisEntity("analysis", incoming.id, "a".repeat(64), "current-v1",
            123.0, "D", 1.0, "4/4", 0.0, 1L)
        val result = promotedCanonicalPackageProjection(incoming, analysis, null)
        assertSame(incoming, result.song)
        assertSame(analysis, result.analysis)
        assertNull(promotedCanonicalPackageProjection(incoming, null, song()).analysis)
    }

    private fun song() = SongEntity(
        id = "rZNpBqlrwkI", youtubeVideoId = "rZNpBqlrwkI", playlistItemId = null,
        canonicalUrl = "https://music.youtube.com/watch?v=rZNpBqlrwkI",
        sourceTitle = "Source title", sourceArtist = "Source artist", displayTitle = "Title",
        displayArtist = "Artist", durationMs = 10000L, artworkPath = "content://library/cover.png",
        currentlyInSourcePlaylist = false, highLevelState = "READY", acquiredAtEpochMs = 2L,
        createdAtEpochMs = 1L, canonicalExternal = true, packageDocumentUri = "content://library/song",
        manifestState = "enriched"
    )
}
