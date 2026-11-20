package app.stemwise.playback.engine

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import app.stemwise.integration.ProviderAssetOpening
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * A source that can configure a platform [MediaExtractor].
 *
 * The decoder deliberately accepts only local files and `content://` URIs. Network fetching and
 * HTTP headers belong to the acquisition layer; keeping them out of the realtime engine avoids an
 * unbounded network wait on a decoder worker.
 */
sealed interface NativeMediaSource {
    /** Redacted, human-readable identity suitable for diagnostics. */
    val diagnosticName: String

    /** Owns the exact provider client and descriptor until its blocking consumer retires. */
    fun <T> withDescriptor(
        context: Context,
        cancellationSignal: CancellationSignal,
        consume: (AssetFileDescriptor) -> T
    ): T

    /** Called once, on a decoder worker, before any extractor track is inspected. */
    fun setDataSource(extractor: MediaExtractor, descriptor: AssetFileDescriptor)

    /** Remaining uses lossless Float32 ingress, independent of platform codec support. */
    data class Float32Audio(val wrapped: NativeMediaSource) : NativeMediaSource {
        init { require(wrapped !is Float32Audio) }
        override val diagnosticName: String get() = wrapped.diagnosticName
        override fun <T> withDescriptor(
            context: Context,
            cancellationSignal: CancellationSignal,
            consume: (AssetFileDescriptor) -> T
        ): T = wrapped.withDescriptor(context, cancellationSignal, consume)
        override fun setDataSource(extractor: MediaExtractor, descriptor: AssetFileDescriptor) =
            error("Float Remaining must use the direct PCM reader")
    }

    data class LocalFile(val file: File, val expectedContentSha256: String? = null) : NativeMediaSource {
        init {
            require(file.isAbsolute) { "A native media file path must be absolute" }
        }

        internal var admission = NativeSourceAdmission(expectedContentSha256)

        override val diagnosticName: String = "file:${file.name.ifBlank { "<unnamed>" }}"

        override fun <T> withDescriptor(
            context: Context,
            cancellationSignal: CancellationSignal,
            consume: (AssetFileDescriptor) -> T
        ): T {
            if (!file.isFile || !file.canRead()) {
                throw FileNotFoundException("The local audio source is unavailable")
            }
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val descriptor = try { AssetFileDescriptor(fd, 0L, fd.statSize) }
                catch (failure: Throwable) { fd.close(); throw failure }
            return descriptor.use { admittedDescriptor(it, admission, cancellationSignal, consume) }
        }

        override fun setDataSource(extractor: MediaExtractor, descriptor: AssetFileDescriptor) {
            extractor.setDataSource(descriptor)
        }
    }

    data class ContentUri(val uri: Uri, val expectedContentSha256: String? = null) : NativeMediaSource {
        init {
            require(uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
                "A NativeMediaSource.ContentUri must use the content scheme"
            }
            require(!uri.authority.isNullOrBlank()) { "A content URI must have an authority" }
        }

        internal var admission = NativeSourceAdmission(expectedContentSha256)

        override val diagnosticName: String = "content://${uri.authority}/<redacted>"

        override fun <T> withDescriptor(
            context: Context,
            cancellationSignal: CancellationSignal,
            consume: (AssetFileDescriptor) -> T
        ): T = ProviderAssetOpening(context.applicationContext.contentResolver, uri, "r", cancellationSignal)
            .use { opening ->
                val descriptor = opening.open()
                    ?: throw FileNotFoundException("The content audio source is unavailable")
                descriptor.use { admittedDescriptor(it, admission, cancellationSignal, consume) }
            }

        override fun setDataSource(extractor: MediaExtractor, descriptor: AssetFileDescriptor) =
            extractor.setDataSource(descriptor)
    }

    companion object {
        /**
         * Converts the persisted locations already used by StemWise into an engine source.
         * Plain strings are treated as absolute local paths; only file/content URI schemes are
         * accepted so a malformed library entry cannot silently start network I/O.
         */
        fun fromLocation(location: String, directFloat32Audio: Boolean = false,
            expectedContentSha256: String? = null): NativeMediaSource {
            val value = location.trim()
            require(value.isNotEmpty()) { "An audio source location cannot be blank" }
            val uri = Uri.parse(value)
            val source = when (uri.scheme?.lowercase(Locale.ROOT)) {
                null -> LocalFile(File(value), expectedContentSha256)
                ContentResolver.SCHEME_FILE -> {
                    val path = requireNotNull(uri.path) { "A file URI must contain a path" }
                    LocalFile(File(path), expectedContentSha256)
                }
                ContentResolver.SCHEME_CONTENT -> ContentUri(uri, expectedContentSha256)
                else -> throw IllegalArgumentException(
                    "Only absolute local files and content URIs are valid native audio sources"
                )
            }
            return if (directFloat32Audio) Float32Audio(source) else source
        }
    }
}

internal fun NativeMediaSource.sourceAdmission(): NativeSourceAdmission = when (this) {
    is NativeMediaSource.LocalFile -> admission
    is NativeMediaSource.ContentUri -> admission
    is NativeMediaSource.Float32Audio -> wrapped.sourceAdmission()
}

private fun <T> admittedDescriptor(descriptor: AssetFileDescriptor, admission: NativeSourceAdmission,
    cancellation: CancellationSignal, consume: (AssetFileDescriptor) -> T): T {
    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
        val length = descriptor.declaredLength.takeIf { it >= 0L }
            ?: (input.channel.size() - descriptor.startOffset)
        admission.admitBytes(input.channel, descriptor.startOffset, length) { cancellation.throwIfCanceled() }
    }
    // The open descriptor, rather than its pathname, is the consumer's resource lease.
    return consume(descriptor)
}

/** Binds construction to the engine-owned admission retained across decoder reopenings. */
internal fun NativeMediaSource.withAdmission(owner: NativeSourceAdmission): NativeMediaSource = apply {
    when (this) {
        is NativeMediaSource.LocalFile -> admission = owner
        is NativeMediaSource.ContentUri -> admission = owner
        is NativeMediaSource.Float32Audio -> wrapped.withAdmission(owner)
    }
}
