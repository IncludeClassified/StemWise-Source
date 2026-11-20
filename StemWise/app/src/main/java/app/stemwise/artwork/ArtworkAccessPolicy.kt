package app.stemwise.artwork

import app.stemwise.network.AppNetworkState
import app.stemwise.network.AppOwnedNetworkPolicy
import java.net.URI

/**
 * One pure authority for artwork network access and Android-session publication.
 *
 * Memory and disk cache reads remain useful on constrained networks; only a new remote read is
 * denied. Android's media session receives either bounded embedded bytes elsewhere in playback or
 * a package-local android.resource URI, never a URL that lets SystemUI perform its own download.
 */
internal object ArtworkAccessPolicy {
    fun isRemoteSource(raw: String?): Boolean =
        parsedScheme(raw) in setOf("http", "https")

    fun allowRemoteNetworkRead(
        raw: String?,
        networkState: AppNetworkState
    ): Boolean = !isRemoteSource(raw) ||
        AppOwnedNetworkPolicy.allowsNetwork(networkState)

    fun publicSessionArtworkUri(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (parsed.scheme?.lowercase() != "android.resource") return null
        return candidate.takeIf { !parsed.authority.isNullOrBlank() }
    }

    private fun parsedScheme(raw: String?): String? {
        val candidate = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching { URI(candidate).scheme?.lowercase() }.getOrNull()
    }
}
