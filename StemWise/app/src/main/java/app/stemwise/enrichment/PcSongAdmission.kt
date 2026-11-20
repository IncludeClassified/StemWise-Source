package app.stemwise.enrichment

import app.stemwise.integration.MediaIdentity
import java.io.IOException

class EnrichmentImportException(
    val code: String,
    message: String = code
) : IOException(message)

/** Bridge delivery must match one exact requested song before any package is published. */
internal fun requirePcSongIdentity(videoIds: List<String?>, expectedVideoId: String) {
    if (!MediaIdentity.isYoutubeVideoId(expectedVideoId)) {
        throw EnrichmentImportException("SONG_ID_INVALID")
    }
    if (videoIds.size != 1) throw EnrichmentImportException("SONG_COUNT_INVALID")
    if (videoIds.single() != expectedVideoId) throw EnrichmentImportException("SONG_ID_MISMATCH")
}
