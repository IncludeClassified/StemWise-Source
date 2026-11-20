package app.stemwise.integration

import android.content.ContentResolver
import android.content.ContentProviderClient
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import java.io.Closeable

/** Kept through descriptor/worker retirement, including a cancelled or failed open. */
class ProviderAssetOpening(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mode: String,
    private val signal: CancellationSignal
) : Closeable {
    private var provider: ContentProviderClient? = null

    fun open(): AssetFileDescriptor? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return resolver.openAssetFileDescriptor(uri, mode, signal)
        }
        // Resolver's read-only typed fallback drops DocumentsProvider's open signal.
        val client = checkNotNull(resolver.acquireContentProviderClient(uri)) { "Provider unavailable" }
        provider = client
        return client.openAssetFile(uri, mode, signal)
    }

    override fun close() { provider?.close() }
}

