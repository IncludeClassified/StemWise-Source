package app.stemwise.integration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.engine.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Inputs and expected bits come from the independent host oracle, never the renderer under test. */
internal class MergerOracleFixture {
    private val document = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("merger-oracle.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    private val residual = document.getJSONObject("residual")
    val roles = document.getJSONArray("roles").let { values -> (0 until values.length()).map(values::getString) }
    val frameCount = residual.getInt("frameCount")
    private val patternFrames = residual.getInt("patternFrames")
    private val originalCodes = residual.getJSONArray("originalPcm24")
    private val targetCodes = residual.getJSONArray("targetsPcm16")
    val originalPcm = FloatArray(frameCount * 2) { originalCodes.getInt(it % (patternFrames * 2)) / 8388608f }
    fun targetPcm(index: Int) = FloatArray(frameCount * 2) {
        targetCodes.getJSONArray(index).getInt(it % (patternFrames * 2)) / 32768f
    }
    fun remainingPcm(count: Int): FloatArray {
        val cases = residual.getJSONArray("cases")
        val expected = (0 until cases.length()).map(cases::getJSONObject)
            .single { it.getInt("targetCount") == count }.getJSONArray("remainingBits")
        return FloatArray(frameCount * 2) { Float.fromBits(expected.getLong(it % (patternFrames * 2)).toInt()) }
    }

    fun assertConvertedDc(inputRate: Int, codes: IntArray, pcm: FloatArray) {
        val cases = document.getJSONObject("conversion").getJSONArray("cases")
        val case = (0 until cases.length()).map(cases::getJSONObject)
            .single { it.getInt("inputRate") == inputRate }
        assertEquals(48_000, case.getInt("outputRate"))
        repeat(2) { channel -> assertEquals(codes[channel], case.getJSONArray("codes").getInt(channel)) }
        pcm.forEachIndexed { sample, value ->
            val location = "normalized $inputRate frame=${sample / 2} channel=${sample % 2}"
            assertTrue("$location nonfinite sample", value.isFinite())
            val code = value * 32768f
            assertEquals("$location not a PCM16 code", code.toInt().toFloat(), code, 0f)
            val accepted = case.getJSONArray("acceptedCodes").getJSONObject(sample % 2)
            val minimum = accepted.getInt("minimum")
            val maximum = accepted.getInt("maximum")
            assertTrue("$location actualCode=$code expectedCode=$minimum..$maximum",
                code >= minimum && code <= maximum)
        }
    }

    fun assertReflected44100Tail(inputFrames: Int, lastCode: Int, pcm: FloatArray) {
        val conversion = document.getJSONObject("conversion")
        val cases = conversion.getJSONArray("cases")
        val case = (0 until cases.length()).map(cases::getJSONObject).single { it.getInt("inputRate") == 44_100 }
        assertEquals(32, case.getInt("taps"))
        assertEquals(160, case.getInt("phases"))
        assertEquals("1", case.getString("factorNumerator"))
        assertEquals("1", case.getString("factorDenominator"))
        val quantizer = conversion.getJSONObject("profile").getDouble("quantizerAbsoluteErrorCodesUpper")
        val referenceCode = case.getJSONArray("codes").getInt(0)
        val referenceBound = case.getJSONArray("acceptedCodes").getJSONObject(0).getDouble("absoluteErrorUpper")
        val bound = quantizer + (referenceBound - quantizer) * kotlin.math.abs(lastCode.toDouble() / referenceCode)
        fun besselI0(argument: Double): Double {
            var term = 1.0
            var sum = term
            for (index in 1..24) { term *= argument * argument / (4.0 * index * index); sum += term }
            return sum
        }
        val normalization = besselI0(9.0)
        fun kernel(distance: Double): Double {
            val sinc = if (distance == 0.0) 1.0 else kotlin.math.sin(Math.PI * distance) / (Math.PI * distance)
            return sinc * besselI0(9.0 * kotlin.math.sqrt(maxOf(1.0 - distance * distance / 256.0, 0.0))) / normalization
        }
        // FFmpeg resample_flush reflects INCLUDING the last sample: the isolated endpoint impulse occurs twice.
        // Compare the qualified Kaiser-sinc shape, since that reflection can move its peak beyond the input event.
        val frames = pcm.size / 2
        for (frame in frames - 16 until frames) {
            val sourceTime = frame * 44_100.0 / 48_000
            val expected = lastCode * (kernel(inputFrames - 1 - sourceTime) + kernel(inputFrames - sourceTime))
            repeat(2) { channel ->
                val code = pcm[frame * 2 + channel] * 32768.0
                assertTrue("reflected tail frame=$frame channel=$channel actualCode=$code expected=$expected bound=$bound",
                    code.isFinite() && kotlin.math.abs(code - expected) <= bound)
            }
        }
    }

    suspend fun original(context: Context, directory: File): File {
        app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(directory)
        val wave = File(directory, "oracle-original.wav")
        writeWave(wave, IntArray(frameCount * 2) { originalCodes.getInt(it % (patternFrames * 2)) }, 24)
        val output = File(directory, "oracle-original.flac")
        try {
            encodeFlac(context, wave, output, highPrecision = true)
            return output
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(directory))
            throw error
        } finally {
            if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(wave) == null) check(wave.delete())
        }
    }

    suspend fun targets(context: Context, directory: File): List<File> = (0 until 8).map { index ->
        app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(directory)
        val wave = File(directory, "oracle-target-$index.wav")
        writeWave(wave, IntArray(frameCount * 2) {
            targetCodes.getJSONArray(index).getInt(it % (patternFrames * 2))
        }, 16)
        try {
            File(directory, "oracle-target-$index.flac").also {
                YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).encodeLosslessPart(wave, it, 2L * 1024 * 1024, 2)
            }
        } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
            app.stemwise.integration.WorkerRetirementQuarantine.retain(error, files = listOf(directory))
            throw error
        } finally {
            if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(wave) == null) check(wave.delete())
        }
    }

    companion object {
        suspend fun requireConversionProfile(context: Context): JSONObject {
            YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).ensureSourceEncodersReady()
            suspend fun probe(vararg arguments: String): String {
                val result = runOwnedToolProcess(listOf(
                    File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath) + arguments,
                    bundledToolProcessEnvironment(context), timeoutSeconds = 30L, maximumOutputBytes = 256L * 1024L)
                check(result.exitCode == 0) { "Conversion profile probe failed: ${result.stderr}" }
                return result.stdout + result.stderr
            }
            val version = probe("-version")
            android.util.Log.i("MergerConversionProfile", version)
            check(Regex("libswresample\\s+5\\.\\s*3\\.\\s*100").containsMatchIn(version)) {
                "Unsupported conversion numerical profile: $version"
            }
            val defaults = probe("-hide_banner", "-h", "filter=aresample")
            android.util.Log.i("MergerConversionProfile", defaults)
            for ((option, value) in listOf("filter_size" to "32", "phase_shift" to "10",
                "linear_interp" to "true", "exact_rational" to "true", "cutoff" to "0",
                "filter_type" to "kaiser", "kaiser_beta" to "9", "resampler" to "swr")) {
                check(defaults.lineSequence().any { line ->
                    line.trimStart().removePrefix("-").startsWith("$option ") && line.contains("(default $value)")
                }) { "Unqualified resampler default $option: $defaults" }
            }
            return JSONObject().put("toolVersion", version.lineSequence().first())
                .put("libswresample", "5.3.100").put("filterSize", 32).put("phaseShift", 10)
                .put("linearInterpolation", true).put("exactRational", true).put("cutoffOption", 0)
                .put("filterType", "kaiser").put("kaiserBeta", 9).put("resampler", "swr")
        }

        suspend fun encodeFlac(context: Context, wave: File, output: File, highPrecision: Boolean = false) {
            YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).ensureSourceEncodersReady()
            val result = runOwnedToolProcess(listOf(
                File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", wave.absolutePath,
                "-c:a", "flac", "-sample_fmt", if (highPrecision) "s32" else "s16",
                "-map_metadata", "-1", output.absolutePath),
                bundledToolProcessEnvironment(context), timeoutSeconds = 60L, maximumOutputBytes = 256L * 1024L)
            check(result.exitCode == 0 && output.isFile) { "Oracle input FLAC encoding failed: ${result.stderr}" }
        }

        fun writeWave(file: File, codes: IntArray, bits: Int, rate: Int = 48_000, channels: Int = 2) {
            require(bits in listOf(16, 24) && codes.size % channels == 0)
            val bytesPerSample = bits / 8
            val dataBytes = codes.size * bytesPerSample
            val wave = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataBytes)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1)
                .putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bytesPerSample)
                .putShort((channels * bytesPerSample).toShort()).putShort(bits.toShort())
                .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
            codes.forEach { code -> repeat(bytesPerSample) { wave.put((code shr (it * 8)).toByte()) } }
            file.writeBytes(wave.array())
        }

        fun assertPcm(role: String, expected: FloatArray, actual: FloatArray) {
            assertEquals("$role stereo sample count", expected.size, actual.size)
            expected.indices.forEach { sample ->
                assertEquals("$role frame=${sample / 2} channel=${sample % 2}",
                    expected[sample].toRawBits(), actual[sample].toRawBits())
            }
        }

        fun decode(context: Context, file: File, frames: Int, directFloat: Boolean = false,
            sampleRate: Int = 48_000, channels: Int = 2): FloatArray {
            val pcm = FloatArray(Math.multiplyExact(frames, channels))
            var offset = 0
            decodePcm(context, file, frames.toLong(), directFloat, sampleRate, channels) { block ->
                block.copyInto(pcm, offset); offset += block.size
            }
            return pcm
        }

        fun captureDecoded(context: Context, file: File, output: File, frames: Long,
            sampleRate: Int, channels: Int, directFloat: Boolean = false): JSONObject {
            app.stemwise.integration.WorkerRetirementQuarantine.requireReleased(output)
            val stream = output.outputStream().buffered()
            try {
                return decodePcm(context, file, frames, directFloat, sampleRate, channels) { block ->
                    val bytes = ByteBuffer.allocate(block.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    block.forEach(bytes::putFloat)
                    stream.write(bytes.array())
                }.also { stream.flush(); check(output.length() == Math.multiplyExact(frames, channels * 4L)) }
            } catch (error: app.stemwise.playback.engine.WorkerRetirementException) {
                app.stemwise.integration.WorkerRetirementQuarantine.retain(error, listOf(stream), listOf(file, output))
                throw error
            } finally {
                if (app.stemwise.integration.WorkerRetirementQuarantine.failureFor(output) == null) stream.close()
            }
        }

        private fun decodePcm(context: Context, file: File, frames: Long, directFloat: Boolean,
            sampleRate: Int, channels: Int, consume: (FloatArray) -> Unit): JSONObject {
            val ended = CountDownLatch(1)
            val problem = AtomicReference<Throwable?>()
            var written = 0L
            var observedFormat: NativePcmFormat? = null
            val decoder = MediaCodecTrackDecoder(context, "merger-oracle", file.name,
                NativeMediaSource.fromLocation(file.absolutePath, directFloat32Audio = directFloat), NativePcmSink { block ->
                    try {
                        assertEquals(sampleRate, block.format.sampleRateHz)
                        assertEquals(channels, block.format.channelCount)
                        observedFormat?.let { assertEquals(it, block.format) }
                        observedFormat = block.format
                        assertEquals(written, block.firstTimelineFrame)
                        val count = minOf(block.frameCount, 257)
                        check(written + count <= frames) { "${file.name}: extra decoded frames" }
                        val samples = block.data.duplicate().order(block.format.byteOrder)
                        val pcm = FloatArray(count * channels) {
                            val value = if (block.format.encoding == NativePcmEncoding.PCM_FLOAT)
                                samples.float else samples.short / 32768f
                            check(value.isFinite()) { "${file.name}: nonfinite decoded sample" }
                            value
                        }
                        consume(pcm)
                        written += count
                        NativePcmSinkResult.accepting(count * block.format.bytesPerFrame)
                    } catch (error: Throwable) { problem.set(error); ended.countDown(); throw error }
                }, listener = object : NativeMediaCodecDecoderListener {
                    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) { ended.countDown() }
                    override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                        problem.compareAndSet(null, IllegalStateException("${failure.code}: ${failure.message}"))
                        ended.countDown()
                    }
                },
                    packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
            try {
                assertTrue(decoder.start())
                assertTrue("${file.name}: decoder did not reach EOS", ended.await(120, TimeUnit.SECONDS))
                problem.get()?.let { throw it }
                assertNull(decoder.diagnostics().lastErrorCode)
                assertEquals("${file.name}: decoded extent", frames, written)
                val format = checkNotNull(observedFormat)
                return JSONObject().put("sampleRate", format.sampleRateHz).put("channels", format.channelCount)
                    .put("frames", written).put("encoding", "f32le").put("originFrame", 0)
                    .put("decoder", decoder.diagnostics().codecName).put("decoderPcmEncoding", format.encoding.name)
                    .put("decoderByteOrder", format.byteOrder.toString())
            } finally {
                try {
                    decoder.close()
                    if (!decoder.awaitClosed(5, TimeUnit.SECONDS))
                        throw app.stemwise.playback.engine.WorkerRetirementException("ORACLE_DECODER_RETIREMENT_FAILED")
                } catch (error: Throwable) {
                    val failure = error as? app.stemwise.playback.engine.WorkerRetirementException
                        ?: app.stemwise.playback.engine.WorkerRetirementException("ORACLE_DECODER_RETIREMENT_FAILED", error)
                    app.stemwise.integration.WorkerRetirementQuarantine.retain(failure, listOf(decoder, consume), listOf(file))
                    throw failure
                }
            }
        }
    }
}
