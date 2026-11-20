package app.stemwise.data

import java.io.File
import java.io.InputStream

/** Header facts, not a claim that a decoder has successfully rendered the media. */
data class PreparedSourceAudioFacts(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val audibleFrames: Long,
    val dataOffset: Long = 0,
    val dataByteLength: Long = 0,
    val mime: String = if (isFloat) "audio/wav" else "audio/flac"
)

fun inspectPreparedSourceAudio(file: File): PreparedSourceAudioFacts =
    file.inputStream().buffered().use { inspectPreparedSourceAudio(it, file.length()) }

/** Provider FLAC, lossless Float32 WavPack, and direct-reader WAV fixtures. */
fun inspectPreparedSourceAudio(
    input: InputStream,
    maxBytes: Long,
    allowHighPrecisionFlac: Boolean = false,
    checkpoint: () -> Unit = {}
): PreparedSourceAudioFacts {
    val invalid = "SOURCE_AUDIO_FORMAT_INVALID"
    check(maxBytes >= 42) { invalid }
    var offset = 0L
    val skipBuffer by lazy { ByteArray(4096) }
    fun read(size: Int): ByteArray {
        check(size >= 0 && offset + size <= maxBytes) { invalid }
        val bytes = ByteArray(size)
        var done = 0
        while (done < size) {
            checkpoint()
            val n = input.read(bytes, done, size - done)
            check(n > 0) { invalid }
            done += n
        }
        offset += size
        return bytes
    }
    fun skip(size: Long) {
        check(size >= 0 && offset + size <= maxBytes && offset + size <= 1024 * 1024) { invalid }
        var left = size
        while (left > 0) left -= read(minOf(left, 4096L).toInt()).size
    }
    val magic = read(4).toString(Charsets.US_ASCII)
    if (magic == "fLaC") {
        val header = read(38)
        fun b(i: Int) = header[i].toInt() and 255
        check(b(0) and 127 == 0 && ((b(1) shl 16) or (b(2) shl 8) or b(3)) == 34) { invalid }
        val rate = (b(14) shl 12) or (b(15) shl 4) or (b(16) ushr 4)
        val channels = ((b(16) ushr 1) and 7) + 1
        val bits = (((b(16) and 1) shl 4) or (b(17) ushr 4)) + 1
        val frames = ((b(17).toLong() and 15) shl 32) or (b(18).toLong() shl 24) or
            (b(19).toLong() shl 16) or (b(20).toLong() shl 8) or b(21).toLong()
        check(rate > 0 && channels in 1..8 &&
            (bits == 16 || allowHighPrecisionFlac && bits == 24) && frames > 0) { invalid }
        return PreparedSourceAudioFacts(rate, channels, bits, false, frames)
    }
    if (magic == "wvpk") {
        var nextFrame = 0L
        var totalFrames = 0L
        do {
            val header = read(28)
            val blockBytes = header.u32(0) + 8L
            val frames = header.u32(16)
            val flags = header.u32(20)
            // Canonical 48 kHz, stereo, Float32, pure lossless, single-stream blocks.
            check(header.u16(4) in 0x402..0x410 && header.u16(6) == 0 &&
                blockBytes in 32..4L * 1024 * 1024 && offset + blockBytes - 32 <= maxBytes &&
                flags and 0x83 == 0x83L && flags and 8 == 0L && flags and 0x3e100 == 0L &&
                flags and 0x1800 == 0x1800L && flags and 0x80000000L == 0L &&
                (flags and 4 == 0L || flags and 0x40000000L != 0L) &&
                (flags ushr 23) and 15 == 10L) { invalid }
            if (totalFrames == 0L) totalFrames = header.u32(8)
            // FFmpeg writes the total in the first block and zero in following blocks.
            check(totalFrames in 1..0xfffffffeL && (header.u32(8) == 0L || header.u32(8) == totalFrames) &&
                header.u32(12) == nextFrame && frames > 0 && frames <= totalFrames - nextFrame) { invalid }
            // Skip compressed payload, retaining cancellation and exact slice bounds. No PCM scan.
            var left = blockBytes - 32
            while (left > 0) {
                checkpoint()
                val skipped = input.skip(left).also { check(it in 0..left) { invalid } }
                if (skipped == 0L) {
                    val count = input.read(skipBuffer, 0, minOf(left, skipBuffer.size.toLong()).toInt())
                    check(count > 0) { invalid }
                    offset += count
                    left -= count
                }
                else { offset += skipped; left -= skipped }
            }
            nextFrame += frames
            if (offset == maxBytes) break
            check(read(4).toString(Charsets.US_ASCII) == "wvpk") { invalid }
        } while (true)
        check(nextFrame == totalFrames) { invalid }
        return PreparedSourceAudioFacts(48_000, 2, 32, true, totalFrames, mime = "audio/wavpack")
    }
    check(magic == "RIFF") { invalid }
    val riff = read(8)
    check(riff.u32(0) + 8 == maxBytes && riff.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "WAVE") { invalid }
    var format: PreparedSourceAudioFacts? = null
    while (offset + 8 <= maxBytes && offset < 1024 * 1024) {
        val chunk = read(8)
        val name = chunk.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        val size = chunk.u32(4)
        check(offset + size + (size and 1) <= maxBytes) { invalid }
        when (name) {
            "fmt " -> {
                check(format == null && size in 16..64) { invalid }
                val fmt = read(size.toInt())
                val tag = fmt.u16(0)
                val extensibleFloat = tag == 0xfffe && size >= 40 && fmt.u16(16) >= 22 &&
                    fmt.u16(18) == 32 && fmt.copyOfRange(24, 40).contentEquals(byteArrayOf(
                        3, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113))
                check(tag == 3 || extensibleFloat) { invalid }
                check(fmt.u16(2) == 2 && fmt.u32(4) == 48_000L && fmt.u32(8) == 384_000L &&
                    fmt.u16(12) == 8 && fmt.u16(14) == 32) { invalid }
                format = PreparedSourceAudioFacts(48_000, 2, 32, true, 0)
            }
            "data" -> {
                val facts = checkNotNull(format) { invalid }
                check(size > 0 && size % 8 == 0L) { invalid }
                return facts.copy(audibleFrames = size / 8, dataOffset = offset, dataByteLength = size)
            }
            else -> skip(size)
        }
        if (size and 1 == 1L) read(1)
    }
    error(invalid)
}

private fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 255) or ((this[i + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
