package app.stemwise.playback.engine

import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import app.stemwise.data.inspectPreparedSourceAudio
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer

internal val stemwiseAudioPacketReaderFactory = AudioPacketReaderFactory(FlacFrameExtractor::openFlac)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class FlacFrameExtractor private constructor(
    private val stream: FileInputStream,
    private val startOffset: Long,
    private val length: Long,
    private val checkpoint: () -> Unit
) : AudioPacketReader, ExtractorOutput, TrackOutput {
    private val extractor = FlacExtractor(FlacExtractor.FLAG_DISABLE_ID3_METADATA)
    private val channel = stream.channel
    private val positionHolder = PositionHolder()
    private val assembling = ByteArrayOutputStream()
    private var packet: ByteArray? = null
    private var packetTimeUs = -1L
    private var format: MediaFormat? = null
    private var seekMap: SeekMap? = null
    private var ended = false
    private var input = inputAt(0L)

    init {
        extractor.init(this)
        while (format == null || seekMap == null) { check(!ended) { "FLAC_METADATA_MISSING" }; read() }
    }

    private fun inputAt(position: Long): DefaultExtractorInput {
        check(position in 0..length)
        channel.position(startOffset + position)
        return DefaultExtractorInput(DataReader { bytes, offset, count ->
            checkpoint()
            val remaining = length - (channel.position() - startOffset)
            if (remaining <= 0) -1 else stream.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
        }, position, length)
    }

    private fun read() {
        checkpoint()
        when (extractor.read(input, positionHolder)) {
            Extractor.RESULT_END_OF_INPUT -> ended = true
            Extractor.RESULT_SEEK -> input = inputAt(positionHolder.position)
        }
    }

    private fun requirePacket(): Boolean {
        while (packet == null && !ended) read()
        return packet != null
    }

    override val trackCount get() = 1
    override val sampleTime get() = if (requirePacket()) packetTimeUs else -1L
    override val sampleFlags get() = MediaExtractor.SAMPLE_FLAG_SYNC
    override fun getTrackFormat(index: Int): MediaFormat {
        require(index == 0)
        return checkNotNull(format).apply { setLong(MediaFormat.KEY_DURATION, checkNotNull(seekMap).durationUs) }
    }
    override fun selectTrack(index: Int) { require(index == 0) }
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        if (!requirePacket()) return -1
        val bytes = checkNotNull(packet)
        check(offset >= 0 && bytes.size <= buffer.capacity() - offset) { "FLAC_FRAME_TOO_LARGE" }
        buffer.position(offset)
        buffer.put(bytes)
        return bytes.size
    }
    override fun advance(): Boolean {
        packet = null
        return requirePacket()
    }
    override fun seekTo(timeUs: Long, mode: Int) {
        val point = checkNotNull(seekMap).getSeekPoints(timeUs.coerceAtLeast(0L)).first
        packet = null
        assembling.reset()
        packetTimeUs = -1L
        ended = false
        input = inputAt(point.position)
        extractor.seek(point.position, timeUs.coerceAtLeast(0L))
    }
    override fun release() {
        try { extractor.release() } finally { stream.close() }
    }
    override fun track(id: Int, type: Int): TrackOutput { check(type == C.TRACK_TYPE_AUDIO); return this }
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
    override fun format(format: Format) {
        check(format.sampleMimeType == "audio/flac" && format.channelCount in 1..2)
        this.format = MediaFormat.createAudioFormat("audio/flac", format.sampleRate, format.channelCount).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_BYTES)
            format.initializationData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
            }
        }
    }
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val bytes = ByteArray(minOf(length, 32 * 1024))
        val count = input.read(bytes, 0, bytes.size)
        if (count == -1) { check(allowEndOfInput); return -1 }
        check(assembling.size() + count <= MAX_FRAME_BYTES) { "FLAC_FRAME_TOO_LARGE" }
        assembling.write(bytes, 0, count)
        return count
    }
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        check(assembling.size() + length <= MAX_FRAME_BYTES) { "FLAC_FRAME_TOO_LARGE" }
        assembling.write(data.data, data.position, length)
        data.skipBytes(length)
    }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        check(packet == null && cryptoData == null && offset == 0 && assembling.size() == size) {
            "FLAC sample assembly: pending=${packet != null}, size=$size, buffered=${assembling.size()}, offset=$offset"
        }
        packet = assembling.toByteArray()
        assembling.reset()
        packetTimeUs = timeUs
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1024 * 1024

        /** Duplicate a provider descriptor so the reader owns and closes only its own lifetime. */
        fun openFlac(descriptor: AssetFileDescriptor, checkpoint: () -> Unit): FlacFrameExtractor? {
            val stream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
            try {
                val start = descriptor.startOffset
                val length = descriptor.declaredLength.takeIf { it >= 0L } ?: (stream.channel.size() - start)
                val magic = ByteBuffer.allocate(4)
                if (stream.channel.read(magic, start) != 4 || !magic.array().contentEquals("fLaC".toByteArray())) {
                    stream.close()
                    return null
                }
                stream.channel.position(start)
                inspectPreparedSourceAudio(stream, length, allowHighPrecisionFlac = true, checkpoint = checkpoint)
                return FlacFrameExtractor(stream, start, length, checkpoint)
            } catch (error: Throwable) {
                stream.close()
                throw error
            }
        }
    }
}
