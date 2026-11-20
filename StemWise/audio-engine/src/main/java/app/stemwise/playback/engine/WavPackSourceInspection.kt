package app.stemwise.playback.engine

import app.stemwise.data.PreparedSourceAudioFacts
import app.stemwise.data.inspectPreparedSourceAudio
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

fun inspectWavPackSlice(
    channel: FileChannel, sourceOffset: Long, sourceLength: Long, checkpoint: () -> Unit
): PreparedSourceAudioFacts {
    if (sourceOffset < 0 || sourceLength <= 0 || sourceOffset > channel.size() ||
        sourceLength > channel.size() - sourceOffset) throw IOException("Float audio descriptor extent is unavailable")
    val input = object : InputStream() {
        private var position = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == sourceLength) return -1
            checkpoint()
            val count = minOf(length.toLong(), sourceLength - position).toInt()
            val n = channel.read(ByteBuffer.wrap(bytes, offset, count), sourceOffset + position)
            if (n <= 0) throw EOFException("Float audio header is truncated")
            position += n
            return n
        }
        override fun skip(count: Long): Long {
            checkpoint()
            val actual = count.coerceIn(0, sourceLength - position)
            position += actual
            return actual
        }
    }
    return inspectPreparedSourceAudio(input, sourceLength, checkpoint = checkpoint).also {
        if (it.mime != "audio/wavpack") throw IOException("Remaining requires Float32 WavPack")
    }
}
