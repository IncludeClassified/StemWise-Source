package app.stemwise.integration

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PlaylistLink(
    val id: String,
    val canonicalUrl: String
)

object PlaylistLinks {
    private val urlCandidate = Regex("""https?://[^\s<>"']+""")

    fun parse(sharedTextOrUrl: String?): PlaylistLink? {
        val raw = sharedTextOrUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_SHARED_TEXT_CHARS }
            ?: return null
        val candidates = urlCandidate.findAll(raw).map { it.value }.toList().ifEmpty { listOf(raw) }
        val playlists = candidates.mapNotNull(::parseCandidate).distinctBy { it.id }
        return playlists.singleOrNull()
    }

    private fun parseCandidate(candidate: String): PlaylistLink? {
        val uri = runCatching { URI(candidate.trimEnd('.', ',', ')', ']', '}')) }.getOrNull()
            ?: return null
        val host = normalizedYoutubeHost(uri.host) ?: return null
        if (host != "youtube.com" && host != "music.youtube.com" && host != "youtu.be") {
            return null
        }
        val playlistId = queryParameters(uri.rawQuery)?.get("list")
            ?.trim()
            ?.takeIf(MediaIdentity::isYoutubePlaylistId)
            ?: return null
        return PlaylistLink(
            id = playlistId,
            canonicalUrl = "https://music.youtube.com/playlist?list=$playlistId"
        )
    }

    /** Bounds untrusted share text while keeping it available for an explicit user correction. */
    fun retainForCorrection(sharedTextOrUrl: String?): String =
        sharedTextOrUrl.orEmpty().take(MAX_SHARED_TEXT_CHARS)

    private fun queryParameters(rawQuery: String?): Map<String, String>? {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return runCatching {
            val result = linkedMapOf<String, String>()
            rawQuery.split('&').forEach { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@forEach
                val key = decode(part.substring(0, separator))
                val value = decode(part.substring(separator + 1))
                if (key == "list") {
                    check(key !in result || result[key] == value) { "Conflicting playlist identities" }
                    result[key] = value
                }
            }
            result
        }.getOrNull()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    internal const val MAX_SHARED_TEXT_CHARS = 16_384
}

internal fun normalizedYoutubeHost(host: String?): String? =
    host?.lowercase()?.removePrefix("www.")?.let { if (it == "m.youtube.com") "youtube.com" else it }
