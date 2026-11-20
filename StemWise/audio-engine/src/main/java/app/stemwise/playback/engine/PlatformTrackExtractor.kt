package app.stemwise.playback.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** Shares the existing codec pump while preserving one compressed FLAC frame per codec input. */
internal typealias NativeTrackExtractor = AudioPacketReader

internal class PlatformTrackExtractor(private val extractor: MediaExtractor) : NativeTrackExtractor {
    override val trackCount get() = extractor.trackCount
    override val sampleTime get() = extractor.sampleTime
    override val sampleFlags get() = extractor.sampleFlags
    override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
    override fun selectTrack(index: Int) = extractor.selectTrack(index)
    override fun seekTo(timeUs: Long, mode: Int) = extractor.seekTo(timeUs, mode)
    override fun readSampleData(buffer: ByteBuffer, offset: Int) = extractor.readSampleData(buffer, offset)
    override fun advance() = extractor.advance()
    override fun release() = extractor.release()
}

