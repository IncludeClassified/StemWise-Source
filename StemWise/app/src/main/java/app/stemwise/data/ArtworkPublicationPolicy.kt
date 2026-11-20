package app.stemwise.data

import org.json.JSONObject

internal const val ROOM_ARTWORK_SOURCE_URL_KEY = "artworkSourceUrl"

/**
 * Playlist drafts may display a remote thumbnail. Complete canonical songs use their verified local cover.
 */
internal fun publishedArtworkSource(
    primarySource: String?,
    additionalInfoJson: String,
    youtubeVideoId: String? = null
): String? = primarySource
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: additionalInfoJson
        .takeUnless { it.isBlank() || it.trim() == "{}" }
        ?.let { json ->
            runCatching {
                JSONObject(json)
                    .optString(ROOM_ARTWORK_SOURCE_URL_KEY)
                    .trim()
                    .takeIf { it.startsWith("https://", ignoreCase = true) }
            }.getOrNull()
        }
    ?: youtubeVideoId
        ?.trim()
        ?.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
