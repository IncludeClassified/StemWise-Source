package app.stemwise.playback.engine

import app.stemwise.data.PreparedSourceAudioFacts
import java.io.FileDescriptor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native WavPack preserves raw IEEE bits, including finite headroom above unity. */
internal class WavPackReader(
    descriptor: FileDescriptor, offset: Long, length: Long,
    val facts: PreparedSourceAudioFacts,
    private val checkpoint: () -> Unit
) : Closeable {
    private var handle = 0L
    init {
        require(facts.mime == "audio/wavpack")
        checkpoint()
        handle = nativeOpen(descriptor, offset, length)
        try { check(handle != 0L && nativeFrames(handle) == facts.audibleFrames) }
        catch (error: Throwable) { close(); throw error }
    }
    fun seekFrame(positionUs: Long): Long {
        require(positionUs >= 0L)
        return microsecondsToFramesCeil(positionUs, facts.sampleRate).coerceAtMost(facts.audibleFrames - 1)
    }
    fun readFrames(firstFrame: Long, destination: ByteBuffer, maximumFrames: Int): Int {
        check(handle != 0L)
        require(firstFrame in 0L..facts.audibleFrames && maximumFrames in 1..65536 && destination.isDirect)
        val count = minOf(maximumFrames.toLong(), facts.audibleFrames - firstFrame).toInt()
        require(destination.capacity() >= count * 8)
        checkpoint()
        destination.clear()
        destination.order(ByteOrder.nativeOrder())
        val decoded = if (count == 0) 0 else nativeRead(handle, firstFrame, destination, count)
        checkpoint()
        check(decoded == count) { "WavPack audio is truncated" }
        destination.limit(decoded * 8)
        return decoded
    }
    override fun close() { if (handle != 0L) { nativeClose(handle); handle = 0L } }
    private external fun nativeOpen(descriptor: FileDescriptor, offset: Long, length: Long): Long
    private external fun nativeFrames(handle: Long): Long
    private external fun nativeRead(handle: Long, firstFrame: Long, destination: ByteBuffer, maximumFrames: Int): Int
    private external fun nativeClose(handle: Long)
    private companion object { init { System.loadLibrary("stemwise_audio_output") } }
}
