package app.stemwise.artwork

import android.content.Context
import android.net.Uri
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.whileUnmetered
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import java.net.URL

/** Applies the artwork policy immediately before Coil resolves a request. */
internal class CoilArtworkNetworkInterceptor(
    context: Context,
    private val networkAccess: AppNetworkAccess
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val preparedRequest = chain.request
        val source = when (val data = preparedRequest.data) {
            is String -> data
            is Uri -> data.toString()
            is URL -> data.toString()
            else -> null
        }
        val allowNetworkRead = ArtworkAccessPolicy.allowRemoteNetworkRead(
            raw = source,
            networkState = networkAccess.currentState()
        )
        val request = if (allowNetworkRead) {
            preparedRequest
        } else {
            // Disabling only the network policy preserves both memory- and disk-cached artwork.
            preparedRequest.newBuilder()
                .networkCachePolicy(CachePolicy.DISABLED)
                .build()
        }
        return if (allowNetworkRead && ArtworkAccessPolicy.isRemoteSource(source)) {
            networkAccess.whileUnmetered { chain.proceed(request) }
        } else {
            chain.proceed(request)
        }
    }

}
