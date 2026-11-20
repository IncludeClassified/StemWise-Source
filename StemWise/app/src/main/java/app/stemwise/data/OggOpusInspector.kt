package app.stemwise.data

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

data class OggOpusFacts(
    val channels: Int,
    val preSkipSamples: Int,
    val decodedAudibleFrameCount: Long,
    val durationUs: Long,
    val outputGainQ8: Int,
    val encodedFrameCount: Long,
    val endTrimFrameCount: Long,
    val initialGranuleOffset: Long
)

/** Framing/header facts for one complete Opus logical stream, not decoder validation. */
object OggOpusInspector {
    private const val OPUS_SAMPLE_RATE = 48_000L
    private const val INVALID = "MEDIA_UNSUPPORTED"

    fun inspect(file: File, checkpoint: () -> Unit = {}): OggOpusFacts {
        require(file.isFile && file.length() > 0L) { INVALID }
        return file.inputStream().use { inspect(it, file.length(), checkpoint) }
    }

    /** The caller owns the stream. Retains one bounded page, never complete metadata/audio packets. */
    fun inspect(input: InputStream, maximumBytes: Long = Long.MAX_VALUE, checkpoint: () -> Unit = {}): OggOpusFacts {
        require(maximumBytes > 0L) { INVALID }
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 128 * 1024)
        val body = ByteArray(255 * 255)
        val tags = CommentHeader()
        var bytesSeen = 0L
        var serial: Long? = null
        var sequence = 0L
        var channels = 0
        var preSkip = 0
        var outputGain = 0
        var packetNumber = 0L
        var packetBytes = 0L
        var firstAudioByte = 0
        var secondAudioByte = 0
        var continuing = false
        var ended = false
        var previousAudioGranule: Long? = null
        var initialGranuleOffset = 0L
        var encodedFrames = 0L
        while (true) {
            val header = buffered.readExactOrNull(27) ?: break
            checkpoint()
            check(!ended && header.copyOfRange(0, 4).contentEquals(OGG_CAPTURE) && header[4].toInt() == 0) { INVALID }
            val flags = header[5].unsigned()
            check(flags and 7 == flags) { INVALID }
            val pageSerial = header.u32(14)
            check(header.u32(18) == sequence) { INVALID }
            sequence = (sequence + 1) and 0xffffffffL
            if (serial == null) {
                check(flags == 2) { INVALID }
                serial = pageSerial
            } else check(pageSerial == serial && flags and 2 == 0) { INVALID }
            val lacing = buffered.readExact(header[26].unsigned())
            val bodySize = lacing.sumOf { it.unsigned() }
            bytesSeen = Math.addExact(bytesSeen, 27L + lacing.size + bodySize)
            check(bytesSeen <= maximumBytes) { "ASSET_SIZE_MISMATCH" }
            buffered.readExact(bodySize, body)
            check(checksum(header, lacing, body, bodySize) == header.u32(22)) { INVALID }
            val granule = header.i64(6)
            check(granule >= -1L) { INVALID }
            if (lacing.isNotEmpty()) check((flags and 1 != 0) == continuing) { INVALID }
            var offset = 0
            var completed = 0
            var pageSamples = 0L
            val firstPacketOnPage = packetNumber
            for ((index, lace) in lacing.withIndex()) {
                val size = lace.unsigned()
                when (packetNumber) {
                    0L -> { /* The identification packet must complete alone on the first page. */ }
                    1L -> repeat(size) { tags.accept(body[offset + it].unsigned()) }
                    else -> {
                        if (packetBytes == 0L && size > 0) firstAudioByte = body[offset].unsigned()
                        if (packetBytes <= 1L && packetBytes + size > 1L) {
                            secondAudioByte = body[offset + (1L - packetBytes).toInt()].unsigned()
                        }
                    }
                }
                packetBytes += size
                offset += size
                continuing = size == 255
                if (continuing) continue
                completed++
                when (packetNumber) {
                    0L -> {
                        check(sequence == 1L && index == lacing.lastIndex && packetBytes == bodySize.toLong() && granule == 0L) { INVALID }
                        channels = validateIdentification(body, bodySize)
                        preSkip = body.u16(10)
                        outputGain = body.u16(16).toShort().toInt()
                    }
                    1L -> {
                        tags.finish()
                        check(index == lacing.lastIndex && granule == 0L) { INVALID }
                    }
                    else -> pageSamples = Math.addExact(pageSamples, packetSamples(firstAudioByte, secondAudioByte, packetBytes))
                }
                packetNumber++
                packetBytes = 0L
            }
            if (completed == 0) check(granule == -1L) { INVALID }
            if (firstPacketOnPage == 0L) check(packetNumber == 1L && !continuing) { INVALID }
            if (pageSamples > 0L) {
                encodedFrames = Math.addExact(encodedFrames, pageSamples)
                check(granule >= 0) { INVALID }
                val previous = previousAudioGranule
                if (previous == null) {
                    check(flags and 4 != 0 || granule >= pageSamples) { INVALID }
                    initialGranuleOffset = maxOf(0L, granule - pageSamples)
                } else {
                    val expected = Math.addExact(previous, pageSamples)
                    check(if (flags and 4 != 0) granule in previous..expected else granule == expected) { INVALID }
                }
                previousAudioGranule = granule
            }
            if (flags and 4 != 0) {
                check(packetNumber >= 3L && !continuing && pageSamples > 0L) { INVALID }
                ended = true
            }
        }
        check(ended) { INVALID }
        val audibleFrames = checkNotNull(previousAudioGranule) - initialGranuleOffset - preSkip
        check(audibleFrames > 0L) { "TIMELINE_INVALID" }
        val endTrim = encodedFrames - (checkNotNull(previousAudioGranule) - initialGranuleOffset)
        check(endTrim >= 0L) { "TIMELINE_INVALID" }
        return OggOpusFacts(channels, preSkip, audibleFrames,
            Math.multiplyExact(audibleFrames, 1_000_000L) / OPUS_SAMPLE_RATE, outputGain,
            encodedFrames, endTrim, initialGranuleOffset)
    }

    private fun validateIdentification(body: ByteArray, size: Int): Int {
        check(size >= 19 && body.copyOfRange(0, 8).contentEquals(OPUS_HEAD)) { INVALID }
        val version = body[8].unsigned()
        val channels = body[9].unsigned()
        check(version in 1..15 && channels in 1..8) { INVALID }
        val required = when (body[18].unsigned()) {
            0 -> { check(channels <= 2) { INVALID }; 19 }
            1 -> {
                check(size >= 21 + channels) { INVALID }
                val streams = body[19].unsigned()
                val coupled = body[20].unsigned()
                check(streams >= 1 && coupled <= streams && streams + coupled <= 255) { INVALID }
                repeat(channels) {
                    val mapping = body[21 + it].unsigned()
                    check(mapping == 255 || mapping < streams + coupled) { INVALID }
                }
                21 + channels
            }
            else -> error(INVALID)
        }
        check(size >= required && (version != 1 || size == required)) { INVALID }
        return channels
    }

    /** TOC duration only; the platform decoder still validates the compressed audio itself. */
    private fun packetSamples(first: Int, second: Int, bytes: Long): Long {
        check(bytes > 0) { INVALID }
        val frames = when (first and 3) {
            0 -> 1
            1, 2 -> 2
            else -> { check(bytes >= 2) { INVALID }; second and 63 }
        }
        val samples = when {
            first and 128 != 0 -> 120 shl ((first ushr 3) and 3)
            first and 96 == 96 -> if (first and 8 != 0) 960 else 480
            else -> minOf(480 shl ((first ushr 3) and 3), 2880)
        }.toLong() * frames
        check(frames > 0 && samples <= 5760L) { INVALID }
        return samples
    }

    /** RFC 7845 comment lengths are parsed incrementally, including packets spanning pages. */
    private class CommentHeader {
        private var phase = 0 // signature, vendor length, vendor, count, comment length, comment, padding
        private var signatureBytes = 0
        private var lengthBytes = 0
        private var value = 0L
        private var remaining = 0L
        private var comments = 0L
        fun accept(byte: Int) {
            advance()
            when (phase) {
                0 -> {
                    check(byte == OPUS_TAGS[signatureBytes].unsigned()) { INVALID }
                    if (++signatureBytes == 8) phase = 1
                }
                1, 3, 4 -> {
                    value = value or (byte.toLong() shl (lengthBytes * 8))
                    if (++lengthBytes == 4) {
                        when (phase) {
                            1 -> { remaining = value; phase = 2 }
                            3 -> { comments = value; phase = if (comments == 0L) 6 else 4 }
                            4 -> { remaining = value; phase = 5 }
                        }
                        value = 0; lengthBytes = 0
                    }
                }
                2, 5 -> remaining--
            }
        }
        private fun advance() {
            if (phase == 2 && remaining == 0L) phase = 3
            if (phase == 5 && remaining == 0L) phase = if (--comments == 0L) 6 else 4
        }
        fun finish() { advance(); check(phase == 6) { INVALID } }
    }

    private fun checksum(header: ByteArray, lacing: ByteArray, body: ByteArray, size: Int): Long {
        var crc = 0
        fun consume(byte: Int) { crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor byte) and 255] }
        header.forEachIndexed { index, byte -> consume(if (index in 22..25) 0 else byte.unsigned()) }
        lacing.forEach { consume(it.unsigned()) }
        repeat(size) { consume(body[it].unsigned()) }
        return crc.toLong() and 0xffffffffL
    }

    private fun BufferedInputStream.readExactOrNull(count: Int): ByteArray? {
        val first = read()
        if (first < 0) return null
        return ByteArray(count).also { it[0] = first.toByte(); readExact(count, it, 1) }
    }
    private fun BufferedInputStream.readExact(count: Int, output: ByteArray = ByteArray(count), start: Int = 0): ByteArray {
        var offset = start
        while (offset < count) {
            val read = read(output, offset, count - offset)
            if (read < 0) throw EOFException("Truncated Ogg page")
            check(read > 0) { INVALID }
            offset += read
        }
        return output
    }
    private fun Byte.unsigned() = toInt() and 255
    private fun ByteArray.u16(offset: Int) = this[offset].unsigned() or (this[offset + 1].unsigned() shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun ByteArray.i64(offset: Int) = u32(offset) or (u32(offset + 4) shl 32)
    private val OGG_CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
    private val CRC_TABLE = IntArray(256) { value ->
        var crc = value shl 24
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        crc
    }
}
