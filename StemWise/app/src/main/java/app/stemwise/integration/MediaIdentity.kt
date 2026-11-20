package app.stemwise.integration

/** One identity contract shared by playlist, acquisition, registry, and import boundaries. */
object MediaIdentity {
    private val youtubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val youtubePlaylistId = Regex("^[A-Za-z0-9_-]{10,128}$")

    fun isYoutubeVideoId(value: String): Boolean = youtubeVideoId.matches(value)

    fun isYoutubePlaylistId(value: String): Boolean = youtubePlaylistId.matches(value)

    fun isYoutubeSourceId(value: String): Boolean =
        value.startsWith(YOUTUBE_SOURCE_PREFIX) &&
            isYoutubeVideoId(value.removePrefix(YOUTUBE_SOURCE_PREFIX))

    const val YOUTUBE_SOURCE_PREFIX = "youtube-video:"
}
