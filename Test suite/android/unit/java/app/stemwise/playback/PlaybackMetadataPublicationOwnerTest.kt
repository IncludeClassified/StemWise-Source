package app.stemwise.playback

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.engine.NativeEngineMetadata
import app.stemwise.playback.engine.AudioContractProbe
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackMetadataPublicationOwnerTest {
    @Test fun sameAudioRefreshChangesOnlyMetadataRevisionAndEqualPayloadIsReused() {
        val owner = PlaybackMetadataPublicationOwner()
        val song = Song(id = "A", videoId = "METADATA001", title = "Before", artist = "Artist",
            durationMs = 12_000, state = SongState.READY, accent = Color.Red, artworkGlyph = "A")
        val payload = NativePlaybackMetadataPayload(song, "content://A", null)
        val first = owner.publish(payload, "audio-A")
        assertSame(first, owner.publish(payload.copy(), "audio-A"))
        val updatedPayload = payload.copy(song = song.copy(title = "After", mediaRevision = "cover-2"))
        val updated = owner.publish(updatedPayload, "audio-A")
        assertEquals(first.songId, updated.songId)
        assertEquals(first.audioRevision, updated.audioRevision)
        assertTrue(updated.revision > first.revision)
        assertEquals(updatedPayload, owner.payloadFor(updated))
        owner.clear()
        assertEquals(updatedPayload, owner.payloadFor(updated))
        assertTrue(owner.publish(payload, "audio-A").revision > updated.revision)
    }

    @Test fun liveQueueKeysSurviveTheRecentCacheAndRejectMismatchedIdentity() {
        val owner = PlaybackMetadataPublicationOwner()
        val publications = (0 until 128).map { index ->
            val song = Song(id = "song-$index", videoId = "METADATA001", title = "Title $index", artist = "Artist",
                durationMs = 12_000, state = SongState.READY, accent = Color.Red, artworkGlyph = "A")
            val payload = NativePlaybackMetadataPayload(song, "content://$index", null)
            payload to owner.publish(payload, "audio-$index")
        }
        for ((payload, publication) in publications) {
            val native = NativeEngineMetadata(publication.songId, publication.audioRevision,
                publication.revision, publication.payloadKey)
            val observed = AudioContractProbe.mapMetadata(native)
            assertEquals(payload, owner.payloadFor(observed))
            assertNull(owner.payloadFor(observed.copy(songId = "another-song")))
            assertNull(owner.payloadFor(observed.copy(revision = observed.revision + 1)))
            assertNull(owner.payloadFor(observed.copy(audioRevision = "another-audio")))
        }
    }
}
