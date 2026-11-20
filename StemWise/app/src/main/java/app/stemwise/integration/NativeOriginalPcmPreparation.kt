package app.stemwise.integration

import android.content.Context
import app.stemwise.playback.engine.consumeNativeOriginal48kPcm
import app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Materializes the exact pre-DSP Original lane, including native mono/SRC/EOS policy. */
internal suspend fun prepareNativeOriginal48kPcm(
    context: Context, original: File, output: File, expectedFrames: Long, maxBytes: Long
) = withContext(Dispatchers.IO) {
    require(expectedFrames in 1..app.stemwise.MAX_ENRICHMENT_PCM_FRAMES_48K && Math.multiplyExact(expectedFrames, 8L) <= maxBytes)
    require(original.canonicalFile != output.canonicalFile)
    check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
    app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(original)
    app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(output)
    try {
        consumeNativeOriginal48kPcm(context, original, output.outputStream().buffered(), expectedFrames,
            closeStream = true, packetReaderFactory = stemwiseAudioPacketReaderFactory)
    } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
        app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(original, output))
        throw error
    }
    if (output.length() != Math.multiplyExact(expectedFrames, 8L)) throw IllegalStateException(
        "SOURCE_TIMELINE_MISMATCH", IllegalStateException("Original PCM bytes=${output.length()}, expectedFrames=$expectedFrames"))
}

/** Validate actual decoded audio without retaining a full-song PCM intermediate. */
internal suspend fun verifyImportedOriginalPcm(context: Context, original: File, expectedFrames: Long) =
    withContext(Dispatchers.IO) {
    val sink = object : OutputStream() {
        override fun write(value: Int) = error("ORIGINAL_PCM_FORMAT_INVALID")
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            requireFiniteImportedOriginalPcm(bytes, offset, count)
        }
    }
    WorkerRetirementQuarantine.requireReleased(original)
    try {
        consumeNativeOriginal48kPcm(context, original, sink, expectedFrames, packetReaderFactory = stemwiseAudioPacketReaderFactory)
    } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
        WorkerRetirementQuarantine.retain(error, files = listOf(original))
        throw error
    }
}

internal fun requireFiniteImportedOriginalPcm(bytes: ByteArray, offset: Int, count: Int) {
    check(count > 0 && count % 8 == 0) { "ORIGINAL_PCM_FORMAT_INVALID" }
    val samples = ByteBuffer.wrap(bytes, offset, count).order(ByteOrder.LITTLE_ENDIAN)
    while (samples.hasRemaining()) check(samples.float.isFinite()) { "ORIGINAL_PCM_NONFINITE" }
}
