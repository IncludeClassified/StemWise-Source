package app.stemwise.playback.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toUri
import app.stemwise.artwork.ArtworkAccessPolicy
import app.stemwise.artwork.ArtworkDiskCacheIdentity
import app.stemwise.artwork.artworkDiskCacheIdentity
import app.stemwise.integration.withCancellableProviderInput
import app.stemwise.model.Song
import app.stemwise.playback.playbackStableDigest
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SessionArtworkKey(
    val songId: String,
    val mediaRevision: String,
    val source: String,
    val diskCacheIdentity: ArtworkDiskCacheIdentity
)

internal fun sessionArtworkDiskCacheIdentity(song: Song): ArtworkDiskCacheIdentity? =
    song.artworkSource?.let { source ->
        artworkDiskCacheIdentity(source, song.mediaRevision, song.id)
    }

internal fun sessionArtworkKey(song: Song): SessionArtworkKey? {
    val source = song.artworkSource?.trim()?.takeIf(String::isNotBlank) ?: return null
    val revision = song.mediaRevision.trim().takeIf(String::isNotBlank)
        ?: playbackStableDigest("${song.id}|$source")
    val diskCacheIdentity = sessionArtworkDiskCacheIdentity(song) ?: return null
    return SessionArtworkKey(song.id, revision, source, diskCacheIdentity)
}

/** Owns both notification-artwork flights and their bounded cache for one playback lifetime. */
internal class SessionArtworkOwner(
    parentScope: CoroutineScope,
    private val load: suspend (SessionArtworkKey) -> SessionArtworkData?,
    private val elapsedRealtimeMs: () -> Long,
    private val prefetchDelayMs: Long = LIKELY_NEXT_ARTWORK_PREFETCH_DELAY_MS
) {
    constructor(context: Context, parentScope: CoroutineScope) : this(
        parentScope,
        SessionArtworkLoader(context.applicationContext).let { loader ->
            { key -> withContext(Dispatchers.IO) { loader.loadBoundedSessionArtwork(key) } }
        },
        android.os.SystemClock::elapsedRealtime
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val cache = linkedMapOf<SessionArtworkKey, SessionArtworkData>()
    /** Monotonic deadlines let a transient offline/Data Saver miss recover without changing identity. */
    private val failures = linkedMapOf<SessionArtworkKey, Long>()
    private class Flight {
        var key: SessionArtworkKey? = null
        var job: Job? = null
        var generation = 0L
        fun cancel() {
            generation++
            job?.cancel()
            job = null
            key = null
        }
    }
    private val selected = Flight()
    private val likelyNext = Flight()
    private var closed = false

    fun cached(key: SessionArtworkKey): SessionArtworkData? = cache[key]

    fun select(key: SessionArtworkKey?, ready: (SessionArtworkKey) -> Unit) =
        schedule(selected, key, 0L, notifyCached = true, ready)

    fun prefetch(key: SessionArtworkKey?, ready: (SessionArtworkKey) -> Unit) =
        schedule(likelyNext, key, prefetchDelayMs, notifyCached = false, ready)

    fun cancelSelected() = selected.cancel()
    fun cancelPrefetch() = likelyNext.cancel()

    fun clear() {
        cancelSelected()
        cancelPrefetch()
        cache.clear()
        failures.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        clear()
        scope.cancel()
    }

    private fun schedule(
        flight: Flight,
        key: SessionArtworkKey?,
        delayMs: Long,
        notifyCached: Boolean,
        ready: (SessionArtworkKey) -> Unit
    ) {
        if (closed) return
        if (key == null || ArtworkAccessPolicy.publicSessionArtworkUri(key.source) != null) {
            flight.cancel()
            return
        }
        val cached = cache.containsKey(key)
        val retryAt = failures[key]
        if (cached || (retryAt != null && elapsedRealtimeMs() < retryAt)) {
            if (flight.key != key) flight.cancel()
            if (cached && notifyCached) ready(key)
            return
        }
        failures.remove(key)
        if (flight.key == key && flight.job?.isActive == true) return
        flight.cancel()
        flight.key = key
        val generation = flight.generation
        flight.job = scope.launch {
            // The current Original and visible artwork settle before next-owner notification work.
            if (delayMs > 0L) delay(delayMs)
            val artwork = load(key)
            currentCoroutineContext().ensureActive()
            if (closed || flight.generation != generation || flight.key != key) return@launch
            flight.key = null
            flight.job = null
            if (artwork == null) {
                failures[key] = elapsedRealtimeMs() + SESSION_ARTWORK_FAILURE_BACKOFF_MS
            } else {
                cache[key] = artwork
                failures.remove(key)
            }
            while (cache.size > MAXIMUM_SESSION_ARTWORK_CACHE_ENTRIES) cache.remove(cache.keys.first())
            while (failures.size > MAXIMUM_SESSION_ARTWORK_CACHE_ENTRIES) failures.remove(failures.keys.first())
            if (artwork != null) ready(key)
        }
    }
}

private class SessionArtworkLoader(private val applicationContext: Context) {
    suspend fun loadBoundedSessionArtwork(key: SessionArtworkKey): SessionArtworkData? {
        currentCoroutineContext().ensureActive()
        val source = key.source
        val uri = resolveArtworkUri(source)
        if (uri.scheme?.lowercase() in setOf("http", "https")) {
            // Use the application's configured Coil loader so remote notification artwork follows
            // the exact same Android Data Saver/background-data policy as visible artwork. Cached
            // images remain available while blocked; SystemUI never receives a network URL.
            val result = try {
                applicationContext.imageLoader.execute(
                    ImageRequest.Builder(applicationContext)
                        .data(uri)
                        .size(SESSION_ARTWORK_EDGE_PX, SESSION_ARTWORK_EDGE_PX)
                        .scale(Scale.FIT)
                        .precision(Precision.INEXACT)
                        .allowHardware(false)
                        .crossfade(false)
                        .diskCacheKey(key.diskCacheIdentity.canonicalKey)
                        .tag(key.diskCacheIdentity)
                        .build()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                ?: return null
            currentCoroutineContext().ensureActive()
            return normalizeDecodedSessionArtwork(bitmap)
        }
        val encodedSource = try {
            when (uri.scheme?.lowercase()) {
                "file" -> uri.path?.let { path ->
                    File(path).inputStream().use { input ->
                        readBoundedArtworkSource { buffer -> input.read(buffer) }
                    }
                }
                "content", "android.resource" ->
                    applicationContext.contentResolver.withCancellableProviderInput(uri) { _, read ->
                        readBoundedArtworkSource(read)
                    }
                else -> null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null
        currentCoroutineContext().ensureActive()
        return normalizeSessionArtwork(encodedSource)
    }

    private suspend fun readBoundedArtworkSource(read: suspend (ByteArray) -> Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_ARTWORK_READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > MAXIMUM_SESSION_ARTWORK_SOURCE_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray().takeIf(ByteArray::isNotEmpty)
    }

    private fun normalizeSessionArtwork(source: ByteArray): SessionArtworkData? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (largestDimension / sampleSize > SESSION_ARTWORK_DECODE_BOUND_PX) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null
        return try {
            normalizeDecodedSessionArtwork(decoded)
        } finally {
            decoded.recycle()
        }
    }

    /** Copies a decoder- or Coil-owned bitmap into one bounded, notification-safe JPEG payload. */
    private fun normalizeDecodedSessionArtwork(decoded: Bitmap): SessionArtworkData? {
        if (decoded.isRecycled || decoded.width <= 0 || decoded.height <= 0) return null
        return try {
            val scale = minOf(
                1f,
                SESSION_ARTWORK_EDGE_PX.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
            )
            val width = maxOf(1, (decoded.width * scale).toInt())
            val height = maxOf(1, (decoded.height * scale).toInt())
            val normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(normalized).apply {
                    drawColor(Color.rgb(18, 18, 18))
                    drawBitmap(
                        decoded,
                        null,
                        Rect(0, 0, width, height),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                }
                var encodedArtwork: SessionArtworkData? = null
                for (quality in SESSION_ARTWORK_JPEG_QUALITIES) {
                    val candidate = ByteArrayOutputStream().use { output ->
                        if (!normalized.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            null
                        } else {
                            output.toByteArray()
                                .takeIf { it.size in 1..MAXIMUM_SESSION_ARTWORK_BYTES }
                                ?.let(::SessionArtworkData)
                        }
                    }
                    if (candidate != null) {
                        encodedArtwork = candidate
                        break
                    }
                }
                encodedArtwork
            } finally {
                normalized.recycle()
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun resolveArtworkUri(location: String): Uri =
        location.toUri().takeIf { it.scheme != null } ?: Uri.fromFile(File(location))
}

private const val MAXIMUM_SESSION_ARTWORK_CACHE_ENTRIES = 8
private const val LIKELY_NEXT_ARTWORK_PREFETCH_DELAY_MS = 1_300L
private const val SESSION_ARTWORK_FAILURE_BACKOFF_MS = 15_000L
private const val MAXIMUM_SESSION_ARTWORK_SOURCE_BYTES = 10 * 1024 * 1024
private const val DEFAULT_ARTWORK_READ_BUFFER_BYTES = 16 * 1024
private const val SESSION_ARTWORK_DECODE_BOUND_PX = 768
private const val SESSION_ARTWORK_EDGE_PX = 384
private val SESSION_ARTWORK_JPEG_QUALITIES = intArrayOf(86, 72, 56)
