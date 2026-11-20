package app.stemwise.artwork

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * One process- and view-independent identity for the encoded artwork stored by Coil.
 *
 * Display/memory owners may still include dimensions or a player generation, but those transient
 * values must never partition the encoded disk entry. A blank manifest revision receives a stable
 * source/song fallback so re-binding the same song in a later player generation remains offline
 * cache-compatible.
 */
internal data class ArtworkDiskCacheIdentity(
    val canonicalKey: String,
    val normalizedPhysicalSource: String,
    val contentRevision: String
)

internal fun artworkDiskCacheIdentity(
    source: String,
    mediaRevision: String,
    songId: String
): ArtworkDiskCacheIdentity? {
    val rawSource = source.trim().takeIf(String::isNotEmpty) ?: return null
    val normalizedSource = normalizeArtworkPhysicalSource(rawSource) ?: return null
    val normalizedSongId = songId.trim().ifBlank { "unknown-song" }
    val declaredRevision = mediaRevision.trim()
    val revision = declaredRevision.ifBlank {
        "source-song-v1:${artworkSha256(tuple(normalizedSource, normalizedSongId))}"
    }
    val canonical = "stemwise-artwork-disk-v2:${artworkSha256(tuple(normalizedSource, revision))}"

    return ArtworkDiskCacheIdentity(
        canonicalKey = canonical,
        normalizedPhysicalSource = normalizedSource,
        contentRevision = revision
    )
}

/** Exact process-memory identity shared by library rows and the native player preview. */
internal fun artworkMemoryCacheKey(
    songId: String,
    mediaRevision: String,
    source: String
): String = "artwork:${songId.trim()}:${mediaRevision.trim()}:${source.trim()}"

/** Normalize the physical source without consulting a View, decoder size, or player generation. */
internal fun normalizeArtworkPhysicalSource(raw: String): String? {
    val candidate = raw.trim().takeIf(String::isNotEmpty) ?: return null
    val parsed = runCatching { URI(candidate) }.getOrNull()
    val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
    if (scheme == null || looksLikeWindowsPath(candidate, scheme)) {
        val path = File(candidate).absoluteFile.normalize().path.replace('\\', '/')
        return "file:$path"
    }
    if (scheme == "file") {
        val path = runCatching { File(parsed).absoluteFile.normalize().path }
            .getOrNull()
            ?.replace('\\', '/')
        if (path != null) return "file:$path"
    }

    val normalized = parsed.normalize()
    val authority = normalized.rawAuthority?.let { rawAuthority ->
        normalized.host?.let { host ->
            buildString {
                normalized.rawUserInfo?.let { append(it).append('@') }
                if (host.contains(':') && !host.startsWith('[')) {
                    append('[').append(host.lowercase(Locale.ROOT)).append(']')
                } else {
                    append(host.lowercase(Locale.ROOT))
                }
                val defaultPort = (scheme == "http" && normalized.port == 80) ||
                    (scheme == "https" && normalized.port == 443)
                if (normalized.port >= 0 && !defaultPort) append(':').append(normalized.port)
            }
        } ?: rawAuthority
    }
    return buildString(candidate.length + 8) {
        append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        val path = normalized.rawPath.orEmpty()
        if (authority != null && path.isEmpty() && scheme in setOf("http", "https")) {
            append('/')
        } else {
            append(path)
        }
        normalized.rawQuery?.let { append('?').append(it) }
        // URI fragments identify a client-side location, not different encoded artwork bytes.
    }
}

private fun looksLikeWindowsPath(value: String, parsedScheme: String): Boolean =
    parsedScheme.length == 1 && value.length >= 3 && value[1] == ':' &&
        (value[2] == '\\' || value[2] == '/')

private fun tuple(first: String, second: String): String =
    "${first.length}:$first|${second.length}:$second"

private fun artworkSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .toHexString()
