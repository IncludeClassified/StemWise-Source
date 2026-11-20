package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.io.IOException
import app.stemwise.data.PreparedSourceAudioFacts

/** Worker-owned validation: a reopen must match the first exact descriptor slice and track. */
internal class NativeSourceAdmission(expectedContentSha256: String? = null) {
    private val expectedHash = expectedContentSha256?.lowercase(java.util.Locale.ROOT)
    private data class Bytes(val sha256: String, val length: Long)
    private var bytes: Bytes? = null
    private var track: NativeAudioTrackInfo? = null
    private var pcmFormat: NativePcmFormat? = null
    private var floatAudioFacts: PreparedSourceAudioFacts? = null

    @Synchronized fun floatAudioFacts(inspect: () -> PreparedSourceAudioFacts): PreparedSourceAudioFacts {
        check(bytes != null) { "Audio source bytes must be admitted before its header facts" }
        return floatAudioFacts ?: inspect().also { floatAudioFacts = it }
    }

    fun admitBytes(channel: FileChannel, offset: Long, length: Long, checkpoint: () -> Unit) {
        require(expectedHash == null || expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            "Source SHA-256 must contain 64 hexadecimal digits"
        }
        if (offset < 0L || length <= 0L || offset > channel.size() || length > channel.size() - offset)
            throw IOException("Audio descriptor slice is outside its readable extent")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = 0L
        while (position < length) {
            checkpoint()
            buffer.clear().limit(minOf(buffer.capacity().toLong(), length - position).toInt())
            val count = channel.read(buffer, Math.addExact(offset, position))
            if (count <= 0) throw IOException("Audio descriptor slice ended before its admitted extent")
            buffer.flip()
            digest.update(buffer)
            position += count
        }
        checkpoint()
        val actual = Bytes(digest.digest().joinToString("") { "%02x".format(it) }, length)
        if (expectedHash != null && expectedHash != actual.sha256)
            throw IOException("Audio source does not match its expected SHA-256")
        synchronized(this) {
            if (bytes != null && bytes != actual) throw IOException("Audio source bytes or extent changed since admission")
            bytes = actual
        }
    }

    @Synchronized fun admitTrack(actual: NativeAudioTrackInfo) {
        check(bytes != null) { "Audio source bytes must be admitted before its track" }
        check(track == null || track == actual) { "Audio source format or duration changed since admission" }
        track = actual
    }
    @Synchronized fun admitPcmFormat(actual: NativePcmFormat) {
        check(track != null) { "Audio source track must be admitted before its PCM format" }
        check(pcmFormat == null || pcmFormat == actual) { "Audio source decoded PCM format changed since admission" }
        pcmFormat = actual
    }
}
