package app.stemwise.enrichment

import android.content.Context
import app.stemwise.integration.YoutubeDlEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Deterministic, copyright-free playable media for the complete enrichment contract song. */
internal object GoldenSongMediaFixture {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNEL_COUNT = 2
    private const val BYTES_PER_SAMPLE = 2

    fun originalOpus(context: Context, durationMs: Long, frequencyHz: Double = 220.0): ByteArray =
        encodedOpus(context, "original", durationMs, frequencyHz)

    suspend fun encodeOriginalOpus(context: Context, input: File, output: File, maximumBytes: Long) {
        require(maximumBytes > 0L)
        YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).ensureSourceEncodersReady()
        check(!output.exists() || output.delete()) { "PRIVATE_CLEANUP_FAILED" }
        val environment = app.stemwise.integration.bundledToolProcessEnvironment(context)
        val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val result = app.stemwise.integration.runOwnedToolProcess(
            goldenOriginalOpusEncodeCommand(executable, input, output, maximumBytes), environment,
            timeoutSeconds = 600L, maximumOutputBytes = 256L * 1024L)
        check(result.exitCode == 0 && output.isFile && output.length() > 0L) {
            "GOLDEN_ORIGINAL_ENCODE_FAILED: exit=${result.exitCode}; ${result.stderr.takeLast(4096)}"
        }
    }

    private fun encodedOpus(
        context: Context,
        label: String,
        durationMs: Long,
        frequencyHz: Double
    ): ByteArray {
        require(durationMs > 0L)
        val output = File.createTempFile("golden-complete-$label-", ".opus", context.cacheDir)
        val pcm = File.createTempFile("golden-complete-$label-", ".wav", context.cacheDir)
        try {
            pcm.writeBytes(pcmWav(durationMs, frequencyHz))
            // A fixture-only FFmpeg encoder supplies valid Ogg EOS; production parts use FLAC/WavPack.
            val runtimeContext = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking { encodeOriginalOpus(runtimeContext, pcm, output, maxOf(1_048_576L, durationMs * 64L)) }
            val bytes = output.readBytes()
            app.stemwise.data.OggOpusInspector.inspect(bytes.inputStream())
            check(bytes.size > 64 && bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray())) {
                "Golden Opus fixture was not muxed as Ogg"
            }
            return bytes
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(pcm, output))
            throw error
        } finally {
            if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(pcm) == null) {
            check(!pcm.exists() || pcm.delete()) { "Unable to delete temporary golden PCM fixture" }
            check(!output.exists() || output.delete()) {
                "Unable to delete temporary golden Opus fixture ${output.absolutePath}"
            }
            }
        }
    }

    private fun pcmWav(durationMs: Long, frequency: Double): ByteArray {
        val frameCount = durationMs * SAMPLE_RATE_HZ / 1_000L
        require(frameCount in 1..Int.MAX_VALUE.toLong())
        val dataBytes = Math.multiplyExact(
            frameCount.toInt(),
            CHANNEL_COUNT * BYTES_PER_SAMPLE
        )
        return ByteBuffer.allocate(44 + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putAscii("RIFF")
                putInt(36 + dataBytes)
                putAscii("WAVE")
                putAscii("fmt ")
                putInt(16)
                putShort(1.toShort())
                putShort(CHANNEL_COUNT.toShort())
                putInt(SAMPLE_RATE_HZ)
                putInt(SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE)
                putShort((CHANNEL_COUNT * BYTES_PER_SAMPLE).toShort())
                putShort(16.toShort())
                putAscii("data")
                putInt(dataBytes)
                repeat(frameCount.toInt()) { frame ->
                    val (left, right) = samplePair(frame.toLong(), frameCount, frequency)
                    putShort(left)
                    putShort(right)
                }
            }
            .array()
    }

    private fun samplePair(frame: Long, totalFrames: Long, frequencyHz: Double): Pair<Short, Short> {
        val seconds = frame.toDouble() / SAMPLE_RATE_HZ
        val edgeFrames = (SAMPLE_RATE_HZ * .02).toLong()
        val fade = minOf(
            1.0,
            frame.toDouble() / edgeFrames,
            (totalFrames - frame).coerceAtLeast(0L).toDouble() / edgeFrames
        )
        val left = (
            (sin(2.0 * PI * frequencyHz * seconds) * .20 +
                sin(2.0 * PI * frequencyHz * 1.5 * seconds) * .04) * fade
            )
        val right = (
            (sin(2.0 * PI * (frequencyHz + 7.0) * seconds) * .19 +
                sin(2.0 * PI * frequencyHz * .5 * seconds) * .035) * fade
            )
        return (left * Short.MAX_VALUE).toInt().coerceIn(-32_768, 32_767).toShort() to
            (right * Short.MAX_VALUE).toInt().coerceIn(-32_768, 32_767).toShort()
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }
}

private fun goldenOriginalOpusEncodeCommand(
    executable: File,
    input: File,
    output: File,
    maxOutputBytes: Long
): List<String> = listOf(
    executable.absolutePath,
    "-hide_banner",
    "-nostdin",
    "-y",
    "-loglevel",
    "error",
    "-nostats",
    "-i",
    input.absolutePath,
    "-map",
    "0:a:0",
    "-vn",
    "-sn",
    "-dn",
    "-ac",
    "2",
    "-ar",
    "48000",
    "-c:a",
    "libopus",
    "-b:a",
    "192k",
    "-vbr",
    "on",
    "-compression_level",
    "10",
    "-application",
    "audio",
    "-frame_duration",
    "20",
    "-map_metadata",
    "-1",
    "-fflags",
    "+bitexact",
    "-flags:a",
    "+bitexact",
    "-fs",
    maxOutputBytes.toString(),
    "-f",
    "ogg",
    output.absolutePath
)
