package app.stemwise.playback.engine

import android.content.res.AssetFileDescriptor
import android.media.MediaFormat
import java.nio.ByteBuffer

/** Compressed source I/O port; the decoder owns and releases each returned reader. */
interface AudioPacketReader {
    val trackCount: Int
    val sampleTime: Long
    val sampleFlags: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    fun seekTo(timeUs: Long, mode: Int)
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    fun advance(): Boolean
    fun release()
}

/** Framing consumes the pinned source lease, never reopens its pathname. */
fun interface AudioPacketReaderFactory {
    fun openFlac(descriptor: AssetFileDescriptor, checkpoint: () -> Unit): AudioPacketReader?
}
