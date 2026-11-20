package app.stemwise.debug

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Fast, restart-safe writer for the physical Library gate's 16 PCM watermark sources. */
internal object LibraryPressureWaveFixture {
    const val SOURCE_CLASS_COUNT = 16
    const val DURATION_MS = 6_000L
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 2
    const val HEADER_BYTES = 44L

    @Synchronized
    fun ensure(file: File, sourceClass: Int): WriteResult {
        require(sourceClass in 0 until SOURCE_CLASS_COUNT)
        val dataSize = dataSizeBytes()
        if (hasExpectedIdentity(file, sourceClass, dataSize)) {
            return WriteResult(reused = true, byteCount = file.length())
        }

        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Could not create Library pressure fixture directory ${file.parentFile}"
        }
        val temporary = File(file.parentFile, ".${file.name}.building")
        try {
            writeCompleteWave(temporary, sourceClass, dataSize)
            check(hasExpectedIdentity(temporary, sourceClass, dataSize)) {
                "Generated Library pressure WAV failed validation: ${temporary.absolutePath}"
            }
            if (file.exists()) {
                check(file.delete()) {
                    "Could not replace invalid Library pressure WAV ${file.absolutePath}"
                }
            }
            check(temporary.renameTo(file)) {
                "Could not publish Library pressure WAV ${file.absolutePath}"
            }
            return WriteResult(reused = false, byteCount = file.length())
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun expectedByteCount(): Long = HEADER_BYTES + dataSizeBytes().toLong()

    private fun writeCompleteWave(file: File, sourceClass: Int, dataSize: Int) {
        DataOutputStream(
            BufferedOutputStream(FileOutputStream(file, false), OUTPUT_BUFFER_BYTES)
        ).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(CHANNEL_COUNT.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * CHANNEL_COUNT * Short.SIZE_BYTES))
            output.writeShort(
                java.lang.Short.reverseBytes((CHANNEL_COUNT * Short.SIZE_BYTES).toShort()).toInt()
            )
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))

            val pattern = watermarkPattern(sourceClass)
            val chunk = ByteArray(OUTPUT_CHUNK_BYTES) { offset -> pattern[offset % pattern.size] }
            var remaining = dataSize
            while (remaining > 0) {
                val byteCount = minOf(remaining, chunk.size)
                output.write(chunk, 0, byteCount)
                remaining -= byteCount
            }
        }
    }

    private fun hasExpectedIdentity(file: File, sourceClass: Int, dataSize: Int): Boolean {
        if (!file.isFile || file.length() != HEADER_BYTES + dataSize.toLong()) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val headerAndWatermark = ByteArray((HEADER_BYTES + WATERMARK_PATTERN_BYTES).toInt())
                input.readFully(headerAndWatermark)
                headerAndWatermark.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                    headerAndWatermark.copyOfRange(8, 16).toString(Charsets.US_ASCII) ==
                    "WAVEfmt " &&
                    headerAndWatermark.copyOfRange(36, 40).toString(Charsets.US_ASCII) == "data" &&
                    littleEndianInt(headerAndWatermark, 40) == dataSize &&
                    headerAndWatermark.copyOfRange(
                        HEADER_BYTES.toInt(),
                        (HEADER_BYTES + WATERMARK_PATTERN_BYTES).toInt()
                    ).contentEquals(watermarkPattern(sourceClass))
            }
        }.getOrDefault(false)
    }

    private fun watermarkPattern(sourceClass: Int): ByteArray {
        val left = 900 + sourceClass * 233
        val right = -(1_400 + sourceClass * 317)
        return ByteArray(WATERMARK_PATTERN_BYTES).also { bytes ->
            putLittleEndianShort(bytes, 0, left)
            putLittleEndianShort(bytes, 2, right)
            putLittleEndianShort(bytes, 4, -left)
            putLittleEndianShort(bytes, 6, -right)
        }
    }

    private fun putLittleEndianShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun littleEndianInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private fun dataSizeBytes(): Int {
        val frameCount = (DURATION_MS * SAMPLE_RATE / 1_000L).toInt()
        return frameCount * CHANNEL_COUNT * Short.SIZE_BYTES
    }

    data class WriteResult(val reused: Boolean, val byteCount: Long)

    private const val WATERMARK_PATTERN_BYTES = 8
    private const val OUTPUT_BUFFER_BYTES = 64 * 1024
    private const val OUTPUT_CHUNK_BYTES = 64 * 1024
}
