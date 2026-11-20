package app.stemwise.playback

import app.stemwise.artwork.ArtworkAccessPolicy
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.stemwise.playback.session.SESSION_MEDIA_REVISION_KEY
import app.stemwise.playback.session.sessionArtworkKey
import java.io.File
import app.stemwise.model.Song
import app.stemwise.playback.engine.PlaybackPublicationMetadata
import app.stemwise.playback.session.SessionArtworkData

internal data class NativePlaybackMetadataPayload(
    val song: Song,
    val originalLocation: String,
    val sessionArtwork: SessionArtworkData?
)

/** Owns only bounded metadata revisions; selection and audio authority stay in the session. */
internal class PlaybackMetadataPublicationOwner {
    private data class Record(val payload: NativePlaybackMetadataPayload, val publication: PlaybackPublicationMetadata)
    private data class RetainedPayload(val revision: Long, val audioRevision: String, val payload: NativePlaybackMetadataPayload)
    private var revision = 0L
    private val records = linkedMapOf<Pair<String, String>, Record>()
    // Live engine/queue metadata retains its key; retiring it releases the app payload without a callback race.
    private val retained = java.util.WeakHashMap<String, RetainedPayload>()

    fun publish(payload: NativePlaybackMetadataPayload, audioRevision: String): PlaybackPublicationMetadata {
        val key = payload.song.id to audioRevision
        records[key]?.takeIf { it.payload == payload }?.let { return it.publication }
        check(revision < Long.MAX_VALUE) { "metadata revision exhausted" }
        revision++
        val payloadKey = "metadata:$revision"
        retained[payloadKey] = RetainedPayload(revision, audioRevision, payload)
        return PlaybackPublicationMetadata(payload.song.id, audioRevision, revision, payloadKey).also {
            records[key] = Record(payload, it)
            while (records.size > 16) records.remove(records.keys.first())
        }
    }

    fun payloadFor(metadata: PlaybackPublicationMetadata?): NativePlaybackMetadataPayload? {
        val record = metadata?.payloadKey?.let(retained::get) ?: return null
        return record.payload.takeIf { it.song.id == metadata.songId &&
            record.audioRevision == metadata.audioRevision && record.revision == metadata.revision }
    }

    fun clear() = records.clear()
    fun mediaItem(payload: NativePlaybackMetadataPayload): MediaItem = MediaItem.Builder()
        .setUri(resolveMediaUri(payload.originalLocation))
        .setMediaId(payload.song.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(payload.song.title)
                .setArtist(payload.song.artist)
                .setExtras(Bundle().apply {
                    val revision = sessionArtworkKey(payload.song)?.mediaRevision
                        ?: payload.song.mediaRevision.trim().takeIf(String::isNotBlank)
                    revision?.let { putString(SESSION_MEDIA_REVISION_KEY, it) }
                })
                .apply {
                    payload.sessionArtwork?.let { artwork ->
                        setArtworkData(
                            artwork.bytes,
                            MediaMetadata.PICTURE_TYPE_FRONT_COVER
                        )
                    } ?: publicSessionArtworkUri(payload.song.artworkSource)?.let(::setArtworkUri)
                }
                .build()
        )
        .build()

    private fun resolveMediaUri(location: String): Uri =
        location.toUri().takeIf { it.scheme != null } ?: Uri.fromFile(File(location))

    private fun publicSessionArtworkUri(location: String?): Uri? = location
        .let(ArtworkAccessPolicy::publicSessionArtworkUri)
        ?.let(Uri::parse)

}
