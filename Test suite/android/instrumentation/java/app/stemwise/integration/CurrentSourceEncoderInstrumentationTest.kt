package app.stemwise.integration

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.FileObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.OggOpusInspector
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.playback.engine.*
import app.stemwise.playback.PlaybackTestMediaProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class CurrentSourceEncoderInstrumentationTest {
    @Test
    fun aFinalOutputCloseFailureDoesNotPoisonTheRetiredNativeDecoderOrTheNextPreparation() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "original-close-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val original = File(directory, "original.opus")
            context.assets.open("demo_library/DEMOUI00001/original.opus").use { input ->
                original.outputStream().use { output -> input.copyTo(output) }
            }
            val frames = app.stemwise.data.inspectOriginalAudio(original).audibleFrames48k
            val closeFailure = java.io.IOException("Isolated final output flush failure")
            var written = 0L
            var closes = 0
            val sink = object : java.io.OutputStream() {
                override fun write(value: Int) = error("Expected native PCM blocks")
                override fun write(bytes: ByteArray, offset: Int, count: Int) { written += count }
                override fun close() { closes++; throw closeFailure }
            }
            val failure = runCatching {
                withTimeout(30_000L) {
                    consumeNativeOriginal48kPcm(context, original, sink, frames, closeStream = true,
                        packetReaderFactory = stemwiseAudioPacketReaderFactory)
                }
            }.exceptionOrNull()
            if (failure is WorkerRetirementException) {
                WorkerRetirementQuarantine.retain(failure, files = listOf(directory))
                throw failure
            }
            org.junit.Assert.assertSame(closeFailure, failure)
            assertEquals(1, closes)
            assertEquals(Math.multiplyExact(frames, 8L), written)
            org.junit.Assert.assertNull(WorkerRetirementQuarantine.failureFor(original))
            val next = File(directory, "next.f32")
            withTimeout(30_000L) {
                prepareNativeOriginal48kPcm(context, original, next, frames, Math.multiplyExact(frames, 8L))
            }
            assertEquals(Math.multiplyExact(frames, 8L), next.length())
            org.junit.Assert.assertNull(WorkerRetirementQuarantine.failureFor(next))
            next.inputStream().use { input ->
                val first = ByteArray(8)
                assertEquals(8, input.read(first))
                requireFiniteImportedOriginalPcm(first, 0, first.size)
            }
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test
    fun oneThroughEightDistinctTargetsMatchIndependentRemainingBitsAtEveryCanonicalFrame() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getArguments().getString("stemwiseMergerReference")?.let { reference ->
            captureExternalMergerReference(context, reference)
            return@runBlocking
        }
        val directory = File(context.cacheDir, "merger-oracle-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val oracle = MergerOracleFixture()
            val original = oracle.original(context, directory)
            val native = File(directory, "original48.f32")
            withTimeout(30_000L) {
                prepareNativeOriginal48kPcm(context, original, native, oracle.frameCount.toLong(), oracle.frameCount * 8L)
            }
            val captured = ByteBuffer.wrap(native.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            MergerOracleFixture.assertPcm("native Original48", oracle.originalPcm,
                FloatArray(captured.remaining()).also { captured.get(it) })
            val targets = oracle.targets(context, directory)
            targets.forEachIndexed { index, file ->
                val facts = inspectPreparedSourceAudio(file)
                assertEquals(16, facts.bitsPerSample)
                assertEquals(oracle.frameCount.toLong(), facts.audibleFrames)
                MergerOracleFixture.assertPcm("target-${index + 1}", oracle.targetPcm(index),
                    MergerOracleFixture.decode(context, file, oracle.frameCount))
            }
            for (count in 1..8) {
                val remaining = File(directory, "remaining-$count.wv")
                YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).renderRemaining(original, targets.take(count), remaining, 16L * 1024 * 1024, 2)
                assertEquals(oracle.frameCount.toLong(), inspectPreparedSourceAudio(remaining).audibleFrames)
                MergerOracleFixture.assertPcm("Remaining K=$count", oracle.remainingPcm(count),
                    MergerOracleFixture.decode(context, remaining, oracle.frameCount, directFloat = true))
                assertFalse(File(directory, CANONICAL_ORIGINAL_PCM_FILE).exists())
            }
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    private suspend fun captureExternalMergerReference(context: android.content.Context, basename: String) {
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, basename).canonicalFile
        check(basename.isNotBlank() && directory.name == basename && directory.parentFile == external && directory.isDirectory) {
            "REFERENCE_DIRECTORY_INVALID"
        }
        val reference = File(directory, "reference.json").canonicalFile
        check(reference.parentFile == directory && reference.isFile) {
            "REFERENCE_MANIFEST_INVALID: external=${external.path}; directory=${directory.path}; " +
                "reference=${reference.path}; parentMatches=${reference.parentFile == directory}; " +
                "exists=${reference.exists()}; isFile=${reference.isFile}; canRead=${reference.canRead()}; " +
                "directoryEntries=${directory.list()?.sorted()?.joinToString()}"
        }
        val referenceBytes = reference.readBytes()
        val manifest = org.json.JSONObject(referenceBytes.toString(Charsets.UTF_8))
        fun sha(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().toHexString()
        }
        val inputs = linkedMapOf<File, String>()
        fun input(value: org.json.JSONObject): File {
            val name = value.getString("file")
            val file = File(directory, name).canonicalFile
            val hash = value.getString("sha256")
            check(name.isNotBlank() && file.name == name && file.parentFile == directory && file.isFile &&
                hash.matches(Regex("[0-9a-f]{64}")) && sha(file) == hash) { "REFERENCE_INPUT_INVALID: $name" }
            check(file !in inputs) { "REFERENCE_INPUT_DUPLICATED: $name" }
            inputs[file] = hash
            return file
        }
        val original = input(manifest.getJSONObject("source"))
        val sourceFacts = app.stemwise.data.inspectOriginalAudio(original)
        val frames = Math.toIntExact(sourceFacts.audibleFrames48k)
        val targets = manifest.getJSONArray("targets")
        check(targets.length() in 1..8) { "REFERENCE_TARGET_COUNT_INVALID" }
        val roles = (0 until targets.length()).map { targets.getJSONObject(it).getString("role") }
        check(roles.all(String::isNotBlank) && roles.distinct().size == roles.size) { "REFERENCE_ROLES_INVALID" }
        val targetInputs = (0 until targets.length()).map { input(targets.getJSONObject(it)) }
        val other = manifest.optJSONObject("other")?.let(::input)
        val committed = File(directory, "capture")
        check(!committed.exists()) { "REFERENCE_CAPTURE_EXISTS" }
        val capture = File(directory, ".capture-pending-${java.util.UUID.randomUUID()}")
        check(capture.mkdir()) { "REFERENCE_CAPTURE_CREATE_FAILED" }
        var published = false
        try {
            val normalizationProfile = MergerOracleFixture.requireConversionProfile(context)
            val receipts = linkedMapOf<File, String>()
            fun record(file: File): org.json.JSONObject {
                val hash = sha(file)
                receipts[file] = hash
                return org.json.JSONObject().put("path", if (file.parentFile == capture) file.name else "../${file.name}")
                    .put("sha256", hash).put("byteLength", file.length())
            }
            fun capturePcm(name: String, file: File, extent: Long, rate: Int, channels: Int,
                directFloat: Boolean = false): org.json.JSONObject {
                val output = File(capture, name)
                val profile = MergerOracleFixture.captureDecoded(context, file, output, extent, rate, channels, directFloat)
                return record(output).also { receipt -> profile.keys().forEach { key -> receipt.put(key, profile.get(key)) } }
            }
            val rawOriginal = capturePcm("rawOriginal.f32", original, sourceFacts.audibleFramesNative,
                sourceFacts.sampleRateHz, sourceFacts.channels).put("clock", org.json.JSONObject()
                .put("origin", "first decoded audible sample").put("encodedFrames", sourceFacts.encodedFramesNative)
                .put("preSkipFrames", sourceFacts.preSkipFrames).put("endTrimFrames", sourceFacts.endTrimFrames)
                .put("initialGranuleOffset", sourceFacts.initialGranuleOffset)
                .put("sampleRate", sourceFacts.sampleRateHz).put("audibleFrames", sourceFacts.audibleFramesNative))
            val native = File(capture, "localcanonicalOriginal.f32")
            withTimeout(120_000L) {
                prepareNativeOriginal48kPcm(context, original, native, frames.toLong(), frames * 8L)
            }
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            val prepared = targetInputs.mapIndexed { index, file ->
                val facts = inspectReferenceOperand(file)
                requireSameSourceTimeline(facts.audibleFrames, facts.sampleRate, frames.toLong())
                val raw = capturePcm("target-${index + 1}-raw.f32", file, facts.audibleFrames, facts.sampleRate, facts.channels)
                    .put("clock", org.json.JSONObject().put("origin", "first decoded sample")
                        .put("preSkipFrames", 0).put("endTrimFrames", 0))
                val canonical = File(capture, "target-${index + 1}.flac")
                val preserve = facts.mime == "audio/flac" && facts.sampleRate == 48_000 && facts.channels == 2 && facts.audibleFrames == frames.toLong()
                if (preserve) {
                    file.copyTo(canonical)
                    check(sha(canonical) == inputs.getValue(file)) { "REFERENCE_CANONICAL_BYTES_CHANGED" }
                } else engine.encodeLosslessPart(file, canonical, capture.usableSpace, facts.channels,
                    originalFrames48k = frames.toLong().takeIf { facts.mime == "audio/flac" })
                val actual = inspectPreparedSourceAudio(canonical)
                check(actual.sampleRate == 48_000 && actual.channels == 2 && actual.bitsPerSample == 16 &&
                    actual.audibleFrames == frames.toLong()) { "SOURCE_TIMELINE_MISMATCH" }
                canonical to capturePcm("target-${index + 1}.f32", canonical, frames.toLong(), 48_000, 2)
                    .put("rawDecoded", raw).put("role", roles[index]).put("input", record(file)
                    .put("sampleRate", facts.sampleRate).put("channels", facts.channels)
                    .put("frames", facts.audibleFrames).put("bitsPerSample", 16).put("mime", facts.mime))
                    .put("prepared", record(canonical)).put("bytePreserving", preserve)
                    .put("conversionClock", org.json.JSONObject().put("originFrame", 0)
                        .put("inputRate", facts.sampleRate).put("outputRate", 48_000)
                        .put("channelMapping", if (facts.channels == 1) "mono duplicated at unity" else "left/right preserved")
                        .put("untrimmedCeilingFrames", (facts.audibleFrames * 48_000L + facts.sampleRate - 1) / facts.sampleRate)
                        .put("acceptedFrames", frames).put("pipeline", if (preserve) "byte-preserved" else "encodeLosslessPart"))
            }
            val remaining = File(capture, "remaining.wv")
            engine.renderRemaining(original, prepared.map { it.first }, remaining, capture.usableSpace, sourceFacts.channels)
            val remainingPcm = capturePcm("remaining.f32", remaining, frames.toLong(), 48_000, 2, directFloat = true)
            val providerOther = other?.let { otherFile ->
                val otherFacts = inspectReferenceOperand(otherFile)
                requireSameSourceTimeline(otherFacts.audibleFrames, otherFacts.sampleRate, frames.toLong())
                val canonicalOther = File(capture, "canonicalOther.flac")
                val preserveOther = otherFacts.mime == "audio/flac" && otherFacts.sampleRate == 48_000 &&
                    otherFacts.channels == 2 && otherFacts.audibleFrames == frames.toLong()
                if (preserveOther) {
                    otherFile.copyTo(canonicalOther)
                    check(sha(canonicalOther) == inputs.getValue(otherFile)) { "REFERENCE_CANONICAL_OTHER_BYTES_CHANGED" }
                } else engine.encodeLosslessPart(otherFile, canonicalOther, capture.usableSpace, otherFacts.channels,
                    originalFrames48k = frames.toLong().takeIf { otherFacts.mime == "audio/flac" })
                val otherPcm = capturePcm("canonicalOther.f32", canonicalOther, frames.toLong(), 48_000, 2)
                org.json.JSONObject().put("delivered", record(otherFile)
                    .put("sampleRate", otherFacts.sampleRate).put("channels", otherFacts.channels)
                    .put("frames", otherFacts.audibleFrames).put("bitsPerSample", otherFacts.bitsPerSample)
                    .put("encoding", "pcm16").put("mime", otherFacts.mime))
                    .put("canonical", otherPcm.put("sampleRate", 48_000).put("channels", 2).put("frames", frames)
                        .put("encoding", "f32le").put("prepared", record(canonicalOther))
                        .put("transformation", if (preserveOther) "Byte-preserved canonical FLAC16; native decode"
                            else "Android encodeLosslessPart: Original-bound stereo 48kHz PCM16 FLAC; native decode"))
            }
            inputs.forEach { (file, hash) -> check(sha(file) == hash) { "REFERENCE_INPUT_CHANGED: ${file.name}" } }
            check(referenceBytes.contentEquals(reference.readBytes())) { "REFERENCE_MANIFEST_CHANGED" }
            val metadata = org.json.JSONObject().put("schemaVersion", 1)
                .put("normalizationProfile", normalizationProfile)
                .put("classification", if (other == null) "LOCAL_CROSS_MODEL_MERGER" else "EXPORT_BOUNDARY_COMPARISON")
                .put("sampleRate", 48_000).put("channels", 2).put("frames", frames)
                .put("encoding", "f32le").put("source", record(original).put("sampleRate", sourceFacts.sampleRateHz)
                    .put("channels", sourceFacts.channels).put("mime", sourceFacts.mime))
                .put("rawOriginal", rawOriginal)
                .put("original", record(native).put("sampleRate", 48_000).put("channels", 2).put("frames", frames)
                    .put("encoding", "f32le").put("originFrame", 0).put("conversion", "prepareNativeOriginal48kPcm"))
                .put("targets", org.json.JSONArray(prepared.map { it.second }))
                .put("remaining", remainingPcm.put("encoded", record(remaining)))
                .put("providerOther", providerOther)
                .put("reference", manifest.optJSONObject("reference") ?: org.json.JSONObject())
            val encodedMetadata = metadata.toString().toByteArray(Charsets.UTF_8)
            val captureManifest = File(capture, "capture.json")
            captureManifest.writeBytes(encodedMetadata)
            check(encodedMetadata.contentEquals(captureManifest.readBytes())) { "REFERENCE_CAPTURE_MANIFEST_READBACK_FAILED" }
            receipts.forEach { (file, hash) -> check(sha(file) == hash) { "REFERENCE_CAPTURE_READBACK_FAILED: ${file.name}" } }
            check(!committed.exists() && capture.renameTo(committed)) { "REFERENCE_CAPTURE_COMMIT_FAILED" }
            published = true
            android.util.Log.i("MergerReferenceCapture", "Completed ${committed.absolutePath}: ${prepared.size} targets, $frames frames")
        } finally {
            if (!published && capture.exists()) {
                check(capture.canonicalFile.parentFile == directory) { "REFERENCE_CAPTURE_CLEANUP_OWNERSHIP_INVALID" }
                check(capture.deleteAfterWorkerRetirement()) { "REFERENCE_CAPTURE_CLEANUP_FAILED: ${capture.absolutePath}" }
            }
        }
    }

    private fun inspectReferenceOperand(file: File): app.stemwise.data.PreparedSourceAudioFacts {
        java.io.RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(4).also(input::readFully)
            if (signature.toString(Charsets.US_ASCII) != "RIFF") return inspectPreparedSourceAudio(file).also {
                check(it.mime == "audio/flac" && !it.isFloat && it.bitsPerSample == 16 && it.channels in 1..2) {
                    "REFERENCE_OPERAND_FORMAT_UNQUALIFIED"
                }
            }
            fun header(size: Int) = ByteBuffer.wrap(ByteArray(size).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
            val riff = header(8)
            check((riff.int.toLong() and 0xffffffffL) + 8 == input.length() && riff.int == 0x45564157) {
                "REFERENCE_WAVE_INVALID"
            }
            var rate = 0
            var channels = 0
            var dataBytes = 0L
            while (input.filePointer + 8 <= input.length()) {
                val chunk = header(8)
                val kind = chunk.int
                val length = chunk.int.toLong() and 0xffffffffL
                val next = Math.addExact(input.filePointer, length + (length and 1))
                check(next <= input.length()) { "REFERENCE_WAVE_TRUNCATED" }
                when (kind) {
                    0x20746d66 -> {
                        check(rate == 0 && length >= 16) { "REFERENCE_WAVE_INVALID" }
                        val format = header(16)
                        check(format.short.toInt() == 1) { "REFERENCE_OPERAND_FORMAT_UNQUALIFIED" }
                        channels = format.short.toInt()
                        rate = format.int
                        val byteRate = format.int
                        val blockAlign = format.short.toInt()
                        val bits = format.short.toInt()
                        check(rate in 8_000..384_000 && channels in 1..2 && bits == 16 &&
                            byteRate == rate * channels * 2 && blockAlign == channels * 2) {
                            "REFERENCE_OPERAND_FORMAT_UNQUALIFIED"
                        }
                    }
                    0x61746164 -> { check(dataBytes == 0L && length > 0); dataBytes = length }
                }
                input.seek(next)
            }
            check(input.filePointer == input.length() && rate > 0 && dataBytes > 0 && dataBytes % (channels * 2) == 0L) {
                "REFERENCE_WAVE_INVALID"
            }
            return app.stemwise.data.PreparedSourceAudioFacts(rate, channels, 16, false,
                dataBytes / (channels * 2), mime = "audio/wav")
        }
    }

    @Test
    fun externalReferenceInputDirectoryIsAppOwned() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val supplied = InstrumentationRegistry.getArguments().getString("stemwiseMergerReferencePrepare")
        val basename = supplied ?: "merger-reference-input-${java.util.UUID.randomUUID()}"
        val directory = File(external, basename).canonicalFile
        check(basename.isNotBlank() && directory.name == basename && directory.parentFile == external) {
            "REFERENCE_DIRECTORY_INVALID"
        }
        check(!directory.exists() && directory.mkdir()) { "REFERENCE_INPUT_DIRECTORY_EXISTS_OR_UNAVAILABLE" }
        var prepared = false
        try {
            assertEquals(android.os.Process.myUid(), android.system.Os.stat(directory.path).st_uid)
            assertTrue(directory.canRead() && directory.canWrite())
            val probe = File(directory, ".input-readback")
            try { probe.writeBytes(byteArrayOf(1)); assertTrue(probe.readBytes().contentEquals(byteArrayOf(1))) }
            finally { check(!probe.exists() || probe.delete()) }
            prepared = true
            android.util.Log.i("MergerReferencePreparation", "Prepared app-owned input directory: ${directory.path}")
        } finally {
            if (supplied == null || !prepared) {
                check(directory.canonicalFile.parentFile == external)
                check(directory.delete()) { "REFERENCE_INPUT_DIRECTORY_CLEANUP_FAILED: ${directory.path}" }
            }
        }
    }

    @Test
    fun externalReferenceWavInspectionRequiresCompletePcm16Frames() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("merger-reference-header-", ".wav", context.cacheDir)
        try {
            MergerOracleFixture.writeWave(file, IntArray(14) { it - 7 }, 16, 44_100)
            val healthy = file.readBytes()
            val facts = inspectReferenceOperand(file)
            assertEquals(44_100, facts.sampleRate)
            assertEquals(2, facts.channels)
            assertEquals(7L, facts.audibleFrames)
            file.writeBytes(healthy.copyOf().also { it[34] = 32 })
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { inspectReferenceOperand(file) }
            file.writeBytes(healthy.copyOf(healthy.size - 1))
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { inspectReferenceOperand(file) }
            assertTrue(file.exists())
        } finally { check(file.delete()) }
    }

    @Test
    fun externalReferenceCaptureFailurePreservesInputsAndAllowsAValidRetry() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, "merger-reference-retry-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val oracle = MergerOracleFixture()
            val original = oracle.original(context, directory)
            val targets = oracle.targets(context, directory)
            fun sha(file: File) = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()
            fun input(file: File) = org.json.JSONObject().put("file", file.name).put("sha256", sha(file))
            val target = targets.first()
            val healthy = target.readBytes()
            target.writeBytes(ByteArray(64))
            val reference = File(directory, "reference.json")
            fun writeReference() {
                reference.writeText(org.json.JSONObject().put("source", input(original))
                    .put("targets", org.json.JSONArray().put(input(target).put("role", oracle.roles.first())))
                    .put("other", input(targets[1])).toString(), Charsets.UTF_8)
            }
            writeReference()
            val before = listOf(original, target, targets[1], reference).associateWith(File::readBytes)
            val failure = runCatching { captureExternalMergerReference(context, directory.name) }.exceptionOrNull()
            assertEquals("SOURCE_AUDIO_FORMAT_INVALID", failure?.message)
            assertFalse(File(directory, "capture").exists())
            assertTrue(directory.listFiles()!!.none { it.name.startsWith(".capture-pending-") })
            before.forEach { (file, bytes) -> assertTrue("Input changed: ${file.name}", bytes.contentEquals(file.readBytes())) }
            target.writeBytes(healthy)
            writeReference()
            captureExternalMergerReference(context, directory.name)
            val capture = File(directory, "capture")
            val pcm = ByteBuffer.wrap(File(capture, "target-1.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            MergerOracleFixture.assertPcm("retried external target", oracle.targetPcm(0),
                FloatArray(pcm.remaining()).also { pcm.get(it) })
            val committed = capture.listFiles()!!.associate { it.name to sha(it) }
            val repeated = runCatching { captureExternalMergerReference(context, directory.name) }.exceptionOrNull()
            assertEquals("REFERENCE_CAPTURE_EXISTS", repeated?.message)
            assertEquals(committed, capture.listFiles()!!.associate { it.name to sha(it) })
            assertTrue(directory.listFiles()!!.none { it.name.startsWith(".capture-pending-") })
        } finally {
            check(directory.canonicalFile.parentFile == external)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test
    fun originalBoundConversionRetainsEveryPrefixSampleAndRejectsInvalidEndpoints() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, "merger-endpoint-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            fun wave(name: String, frames: Int, rate: Int, bits: Int = 16) = File(directory, name).also {
                MergerOracleFixture.writeWave(it, IntArray(frames * 2) { index ->
                    (index * 193 % 20_000) - 10_000
                }, bits, rate)
            }
            val original = File(directory, "original.flac")
            MergerOracleFixture.encodeFlac(context, wave("original.wav", 1_001, 48_000, 24), original,
                highPrecision = true)
            val input = File(directory, "target.flac")
            MergerOracleFixture.encodeFlac(context, wave("target.wav", 920, 44_100), input)
            val originalBytes = original.readBytes()
            val inputBytes = input.readBytes()
            val unbound = File(directory, "unbound.flac")
            engine.encodeLosslessPart(input, unbound, 16L * 1024 * 1024, 2)
            assertEquals(1_002L, inspectPreparedSourceAudio(unbound).audibleFrames)
            val bound = File(directory, "bound.flac")
            engine.encodeLosslessPart(input, bound, 16L * 1024 * 1024, 2, originalFrames48k = 1_001)
            assertEquals(1_001L, inspectPreparedSourceAudio(bound).audibleFrames)
            val expected = MergerOracleFixture.decode(context, unbound, 1_002).copyOf(1_001 * 2)
            MergerOracleFixture.assertPcm("Original-bound prefix", expected,
                MergerOracleFixture.decode(context, bound, 1_001))
            val rejected = File(directory, "rejected.flac")
            val prior = byteArrayOf(9, 8, 7)
            for ((frames, rate) in listOf(918 to 44_100, 924 to 44_100, 1_002 to 48_000)) {
                val invalid = File(directory, "invalid-$frames-$rate.flac")
                MergerOracleFixture.encodeFlac(context, wave("invalid-$frames-$rate.wav", frames, rate), invalid)
                val invalidBytes = invalid.readBytes()
                rejected.writeBytes(prior)
                val failure = runCatching {
                    engine.encodeLosslessPart(invalid, rejected, 16L * 1024 * 1024, 2, originalFrames48k = 1_001)
                }.exceptionOrNull()
                assertEquals("SOURCE_TIMELINE_MISMATCH", failure?.message)
                assertTrue(prior.contentEquals(rejected.readBytes()))
                assertTrue(invalidBytes.contentEquals(invalid.readBytes()))
            }
            fun inputRecord(file: File) = org.json.JSONObject().put("file", file.name).put("sha256",
                java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString())
            File(directory, "reference.json").writeText(org.json.JSONObject()
                .put("source", inputRecord(original))
                .put("targets", org.json.JSONArray().put(inputRecord(input).put("role", "test-operand")))
                .toString(), Charsets.UTF_8)
            captureExternalMergerReference(context, directory.name)
            val capture = File(directory, "capture")
            val metadata = org.json.JSONObject(File(capture, "capture.json").readText(Charsets.UTF_8))
            assertEquals(1_001, metadata.getInt("frames"))
            assertFalse(metadata.has("providerOther"))
            val capturedTarget = metadata.getJSONArray("targets").getJSONObject(0)
            assertEquals(920L, capturedTarget.getJSONObject("rawDecoded").getLong("frames"))
            assertEquals(44_100, capturedTarget.getJSONObject("rawDecoded").getInt("sampleRate"))
            assertEquals(1_002L, capturedTarget.getJSONObject("conversionClock").getLong("untrimmedCeilingFrames"))
            assertEquals(1_001L, capturedTarget.getJSONObject("conversionClock").getLong("acceptedFrames"))
            MergerOracleFixture.assertPcm("Captured Original-bound target", expected,
                MergerOracleFixture.decode(context, File(capture, "target-1.flac"), 1_001))
            assertTrue(originalBytes.contentEquals(original.readBytes()))
            assertTrue(inputBytes.contentEquals(input.readBytes()))
        } finally {
            check(directory.canonicalFile.parentFile == external)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test
    fun rawAndPreparedCaptureRetainsNativeClocksAndFirstMiddleFinalAnchors() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        MergerOracleFixture.requireConversionProfile(context)
        for ((rate, channels, inputFrames) in listOf(Triple(44_100, 1, 44_101), Triple(48_000, 2, 48_017))) {
            val inputDirectory = File(external, "timing-input-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
            try {
                val anchors = if (rate == 44_100) intArrayOf(0, 16_611, 28_959, 44_100)
                    else intArrayOf(0, 10_579, 27_713, 48_016)
                val codes = IntArray(inputFrames * channels)
                anchors.forEachIndexed { index, frame -> repeat(channels) { channel ->
                    codes[frame * channels + channel] = (12_000 + index * 1_000) * if (channel == 0) 1 else -1
                } }
                val wave = File(inputDirectory, "anchors.wav")
                MergerOracleFixture.writeWave(wave, codes, 16, rate, channels)
                val original = File(inputDirectory, "original.flac")
                MergerOracleFixture.encodeFlac(context, wave, original)
                val target = File(inputDirectory, "target.flac").also { original.copyTo(it) }
                fun input(file: File) = org.json.JSONObject().put("file", file.name).put("sha256",
                    java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString())
                File(inputDirectory, "reference.json").writeText(org.json.JSONObject().put("source", input(original))
                    .put("targets", org.json.JSONArray().put(input(target).put("role", "offline-known-anchors")))
                    .toString(), Charsets.UTF_8)
                captureExternalMergerReference(context, inputDirectory.name)
                val capture = File(inputDirectory, "capture")
                val metadata = org.json.JSONObject(File(capture, "capture.json").readText(Charsets.UTF_8))
                val targetRecord = metadata.getJSONArray("targets").getJSONObject(0)
                for (raw in listOf(metadata.getJSONObject("rawOriginal"), targetRecord.getJSONObject("rawDecoded"))) {
                    assertEquals(rate, raw.getInt("sampleRate")); assertEquals(channels, raw.getInt("channels"))
                    assertEquals(inputFrames.toLong(), raw.getLong("frames")); assertEquals(0, raw.getInt("originFrame"))
                    assertEquals(0, raw.getJSONObject("clock").getInt("preSkipFrames"))
                    assertEquals(0, raw.getJSONObject("clock").getInt("endTrimFrames"))
                    val pcm = ByteBuffer.wrap(File(capture, raw.getString("path")).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    codes.forEach { assertEquals(it / 32768f, pcm.float, 0f) }
                    assertFalse(pcm.hasRemaining())
                }
                val frames = metadata.getInt("frames")
                assertEquals((inputFrames * 48_000L + rate - 1) / rate, frames.toLong())
                val converted = MergerOracleFixture.decode(context, File(capture, "target-1.flac"), frames)
                anchors.filter { rate == 48_000 || it < inputFrames - 1 }.forEach { anchor -> repeat(2) { channel ->
                    val expectedFrame = (anchor * 48_000L / rate).toInt()
                    val peak = (maxOf(0, expectedFrame - 12)..minOf(frames - 1, expectedFrame + 12))
                        .maxBy { abs(converted[it * 2 + channel]) }
                    assertEquals("$rate channel=$channel anchor=$anchor", expectedFrame, peak)
                } }
                if (rate == 44_100) MergerOracleFixture().assertReflected44100Tail(inputFrames, codes.last(), converted)
                assertFalse(metadata.getJSONObject("reference").has("acceptedRemoteId"))
            } finally {
                check(inputDirectory.canonicalFile.parentFile == external); check(inputDirectory.deleteAfterWorkerRetirement())
            }
        }
    }

    @Test
    fun normalizationPreservesKnownDcChannelsAndExact96k44100MonoAndOpusTails() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "merger-conversion-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            MergerOracleFixture.requireConversionProfile(context)
            val oracle = MergerOracleFixture()
            for ((rate, channels, inputFrames) in listOf(
                Triple(96_000, 2, 96_035), Triple(44_100, 2, 44_248), Triple(48_000, 1, 48_017)
            )) {
                val label = "$rate-$channels"
                val wave = File(directory, "input-$label.wav")
                val codes = intArrayOf(12_345, -23_456)
                MergerOracleFixture.writeWave(wave, IntArray(inputFrames * channels) { codes[it % channels] },
                    16, rate, channels)
                val original = File(directory, "original-$label.flac")
                MergerOracleFixture.encodeFlac(context, wave, original)
                val frames = ((inputFrames.toLong() * 48_000 + rate - 1) / rate).toInt()
                assertEquals(frames.toLong(), app.stemwise.data.inspectOriginalAudio(original).audibleFrames48k)
                val target = File(directory, "target-$label.flac")
                engine.encodeLosslessPart(original, target, 16L * 1024 * 1024, channels)
                assertEquals(16, inspectPreparedSourceAudio(target).bitsPerSample)
                val expected = FloatArray(frames * 2) { codes[if (channels == 1) 0 else it % 2] / 32768f }
                val converted = MergerOracleFixture.decode(context, target, frames)
                if (rate == 48_000) MergerOracleFixture.assertPcm("normalized $label", expected, converted)
                else oracle.assertConvertedDc(rate, codes, converted)
                if (rate != 48_000) {
                    val sourceMarkers = if (rate == 44_100) intArrayOf(1_470, 2_940) else intArrayOf(9_600, 19_200)
                    val canonicalMarkers = if (rate == 44_100) intArrayOf(1_600, 3_200) else intArrayOf(4_800, 9_600)
                    val taggedWave = File(directory, "clock-$label.wav")
                    val tagged = IntArray(inputFrames * 2)
                    repeat(2) { channel -> tagged[sourceMarkers[channel] * 2 + channel] = 16_384 }
                    MergerOracleFixture.writeWave(taggedWave, tagged, 16, rate)
                    val taggedOriginal = File(directory, "clock-original-$label.flac")
                    MergerOracleFixture.encodeFlac(context, taggedWave, taggedOriginal)
                    val taggedTarget = File(directory, "clock-target-$label.flac")
                    engine.encodeLosslessPart(taggedOriginal, taggedTarget, 16L * 1024 * 1024, 2)
                    val pcm = MergerOracleFixture.decode(context, taggedTarget, frames)
                    repeat(2) { channel ->
                        val peak = (0 until frames).maxBy { abs(pcm[it * 2 + channel]) }
                        assertTrue("$label channel=$channel impulse vanished", pcm[peak * 2 + channel] > 0f)
                        assertEquals("$label channel=$channel rational impulse coordinate", canonicalMarkers[channel], peak)
                    }
                }
                val inputs = if (channels == 1) listOf(original, File(directory, "original-tail.opus").also {
                    app.stemwise.enrichment.GoldenSongMediaFixture.encodeOriginalOpus(context, wave, it, 16L * 1024 * 1024)
                }) else listOf(original)
                for (source in inputs) {
                    val facts = app.stemwise.data.inspectOriginalAudio(source)
                    assertEquals(frames.toLong(), facts.audibleFrames48k)
                    val native = File(directory, "native-$label-${source.extension}.f32")
                    withTimeout(30_000L) {
                        prepareNativeOriginal48kPcm(context, source, native, frames.toLong(), frames * 8L)
                    }
                    val captured = ByteBuffer.wrap(native.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val nativePcm = FloatArray(captured.remaining()).also { captured.get(it) }
                    if (source.extension == "flac" && rate == 48_000)
                        MergerOracleFixture.assertPcm("native mono unity", expected, nativePcm)
                    val zeroWave = File(directory, "zero-$label.wav")
                    MergerOracleFixture.writeWave(zeroWave, IntArray(frames * 2), 16)
                    val zero = File(directory, "zero-$label.flac")
                    engine.encodeLosslessPart(zeroWave, zero, 16L * 1024 * 1024, 2)
                    val remaining = File(directory, "remaining-$label-${source.extension}.wv")
                    engine.renderRemaining(source, listOf(zero), remaining, 16L * 1024 * 1024, facts.channels)
                    MergerOracleFixture.assertPcm("native ${source.name} residual/storage", nativePcm,
                        MergerOracleFixture.decode(context, remaining, frames, directFloat = true))
                }
            }
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test
    fun importedOriginalRequiresItsExactDecodedTimeline() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("stemwiseOriginalFixture")
        val original = if (supplied != null) File(checkNotNull(context.getExternalFilesDir(null)), supplied).also {
            check(it.canonicalFile.parentFile == context.getExternalFilesDir(null)!!.canonicalFile && it.isFile)
        } else File.createTempFile("original-timeline-", ".flac", context.cacheDir).also { file ->
            instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                file.outputStream().use(input::copyTo)
            }
        }
        try {
            val frames = app.stemwise.data.inspectOriginalAudio(original).audibleFrames48k
            withTimeout(120_000L) { verifyImportedOriginalPcm(context, original, frames) }
            val failure = runCatching {
                withTimeout(120_000L) { verifyImportedOriginalPcm(context, original, frames + 1L) }
            }.exceptionOrNull()
            assertTrue("Mismatched timeline accepted: $failure",
                failure is IllegalStateException && failure.message == "SOURCE_TIMELINE_MISMATCH")
            assertTrue(original.isFile)
        } finally { if (supplied == null) assertTrue(original.delete()) }
    }

    @Test
    fun localEncodingDoesNotInspectOrInitializeAnUnavailableExtractor() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "independent-encoder-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val extractorDirectory = File(context.noBackupFilesDir,
            "${com.yausername.youtubedl_android.YoutubeDL.baseName}/${com.yausername.youtubedl_android.YoutubeDL.ytdlpDirName}")
        check(extractorDirectory.isDirectory || extractorDirectory.mkdirs())
        val extractor = File(extractorDirectory, com.yausername.youtubedl_android.YoutubeDL.ytdlpBin)
        val retained = File(extractorDirectory, "encoder-test-${java.util.UUID.randomUUID()}.backup")
        val hadExtractor = extractor.exists()
        try {
            if (hadExtractor) check(extractor.isFile && extractor.renameTo(retained))
            check(extractor.mkdir())
            val input = File(directory, "input.flac")
            instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input.writeBytes(it.readBytes()) }
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            val part = File(directory, "part.flac")
            engine.encodeLosslessPart(input, part, 16L * 1024 * 1024, 2)
            val remaining = File(directory, "remaining.wv")
            engine.renderRemaining(input, listOf(part), remaining, 16L * 1024 * 1024, 2)
            assertTrue(part.length() > 0L && remaining.length() > 0L)
            assertEquals(EngineState.IDLE, engine.status.value.state)
            assertTrue("Encoding changed extractor state", extractor.isDirectory && extractor.listFiles()!!.isEmpty())
            assertEquals(48_000L, inspectPreparedSourceAudio(part).audibleFrames)
            assertEquals(48_000L, inspectPreparedSourceAudio(remaining).audibleFrames)
        } finally {
            if (extractor.isDirectory) check(extractor.delete())
            if (hadExtractor) check(retained.renameTo(extractor))
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test fun wavPackLocalAndContentSlicesPreserveEveryFloatBitThroughPartialOffersAndRepeatedSeeks() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "wavpack-exact-float-test.wv")
        val encoded = instrumentation.context.assets.open("encoder/remaining-float32.wv").use { it.readBytes() }
        fun bits(index: Int): Int {
            val boundary = intArrayOf(0, Int.MIN_VALUE, 0x3fc00000, 0xc0300000.toInt(),
                1, 0x80000001.toInt(), 0x0d800000, 0x8d800000.toInt(), 0x3f7fffff, 0x477fff00)
            return if (index < boundary.size) boundary[index] else
                ((index * 2654435761L).toInt() and 0x7fffff) or ((112 + index % 20) shl 23) or ((index and 1) shl 31)
        }
        try {
            input.writeBytes(encoded)
            requireNotNull(context.contentResolver.openAssetFileDescriptor(
                PlaybackTestMediaProvider.WAVPACK_SLICE_URI, "r")).use {
                assertEquals(PlaybackTestMediaProvider.SLICE_OFFSET.toLong(), it.startOffset)
                assertEquals(encoded.size.toLong(), it.declaredLength)
            }
            assertEquals("audio/wavpack", inspectPreparedSourceAudio(input).mime)
            for (source in listOf(NativeMediaSource.LocalFile(input),
                NativeMediaSource.ContentUri(PlaybackTestMediaProvider.WAVPACK_SLICE_URI))) {
                val eos = java.util.concurrent.LinkedBlockingQueue<Long>()
                val failure = AtomicReference<NativeDecoderFailure?>()
                val counts = java.util.concurrent.ConcurrentHashMap<Long, Long>()
                val decoder = MediaCodecTrackDecoder(context, "wavpack-exact", "remaining",
                    NativeMediaSource.Float32Audio(source), NativePcmSink { block ->
                        assertEquals(NativePcmEncoding.PCM_FLOAT, block.format.encoding)
                        val frames = minOf(block.frameCount, 256)
                        val pcm = block.data.duplicate().order(block.format.byteOrder)
                        repeat(frames * 2) { index ->
                            assertEquals(bits((block.firstTimelineFrame * 2 + index).toInt()), pcm.int)
                        }
                        counts.merge(block.seekGeneration, frames.toLong(), Long::plus)
                        NativePcmSinkResult.accepting(frames * 8)
                    }, listener = object : NativeMediaCodecDecoderListener {
                        override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) { eos.offer(seekGeneration) }
                        override fun onError(error: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                            failure.set(error); eos.offer(-1)
                        }
                    },
                        packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
                try {
                    assertTrue(decoder.start())
                    assertEquals(0L, eos.poll(10, TimeUnit.SECONDS))
                    org.junit.Assert.assertNull(failure.get())
                    assertEquals(48017L, counts[0])
                    assertEquals("native-wavpack-float32", decoder.diagnostics().codecName)
                    for (seekUs in listOf(125000L, 0L, 2_000_000L)) {
                        val generation = decoder.seekToUs(seekUs)
                        assertEquals(generation, eos.poll(10, TimeUnit.SECONDS))
                        org.junit.Assert.assertNull(failure.get())
                        val first = microsecondsToFramesCeil(seekUs, 48000).coerceAtMost(48016)
                        assertEquals(48017 - first, counts[generation])
                    }
                } finally { decoder.close(); assertTrue(decoder.awaitClosed(5, TimeUnit.SECONDS)) }
            }
        } finally {
            assertTrue(!input.exists() || input.delete())
            context.contentResolver.delete(PlaybackTestMediaProvider.WAVPACK_SLICE_URI, null, null)
        }
    }

    @Test
    fun native96kOriginalAndUnityPartsCloseAtTheActualPlaybackIngress() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "remaining-native-${java.util.UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        try {
            val original = File(directory, "original.flac")
            instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                original.outputStream().use(input::copyTo)
            }
            val nativeOriginal = File(directory, "native-original.f32")
            prepareNativeOriginal48kPcm(context, original, nativeOriginal, 48_000L, 48_000L * 8L)
            val expected = ByteBuffer.wrap(nativeOriginal.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            val target = File(directory, "part-1.flac")
            engine.encodeLosslessPart(original, target, 16L * 1024 * 1024, 2)
            val targetFacts = inspectPreparedSourceAudio(target)
            assertEquals(48_000, targetFacts.sampleRate)
            assertEquals(2, targetFacts.channels)
            assertEquals(16, targetFacts.bitsPerSample)
            assertEquals(48_000L, targetFacts.audibleFrames)
            val remaining = File(directory, "remaining.wv")
            engine.renderRemaining(original, listOf(target), remaining, 16L * 1024 * 1024, 2)
            val targetPcm = decodeStereo48k(context, target, directFloat = false)
            val remainingPcm = decodeStereo48k(context, remaining, directFloat = true)
            var peakError = 0f
            for (index in targetPcm.indices) {
                peakError = maxOf(peakError, abs(expected.get() - (targetPcm[index] + remainingPcm[index])))
            }
            assertTrue("Unity parts differ from native Original; peak=$peakError", peakError <= 1e-6f)
            assertTrue("Canonical preparation operand leaked", directory.listFiles().orEmpty().none {
                it.name == "active-original48.f32"
            })
        } finally {
            assertTrue(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            assertTrue(directory.deleteAfterWorkerRetirement())
        }
    }

    @Test
    fun canonicalMono48kIngressDuplicatesAtUnityAndDrainsTheExactEosBoundary() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "remaining-mono-${java.util.UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        try {
            val frames = 1001
            val wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            wave.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { wave.putShort(((it * 97) % 32768 - 16384).toShort()) }
            val original = File(directory, "mono.wav").apply { writeBytes(wave.array()) }
            val output = File(directory, "native.f32")
            prepareNativeOriginal48kPcm(context, original, output, frames.toLong(), frames * 8L)
            val pcm = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            repeat(frames) {
                val expected = ((it * 97) % 32768 - 16384) / 32768f
                assertEquals(expected, pcm.get(), 0f)
                assertEquals(expected, pcm.get(), 0f)
            }
            assertFalse(pcm.hasRemaining())
        } finally { assertTrue(directory.deleteAfterWorkerRetirement()) }
    }

    @Test
    fun remainingFailureAndCancellationRetireCanonicalScratchAndPreserveInputs() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
        for (cancel in listOf(false, true)) {
            val directory = File(context.cacheDir, "remaining-cleanup-${java.util.UUID.randomUUID()}")
            assertTrue(directory.mkdir())
            try {
                val original = File(directory, "original.flac")
                instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                    original.outputStream().use(input::copyTo)
                }
                val target = File(directory, "part-1.flac")
                engine.encodeLosslessPart(original, target, 16L * 1024 * 1024, 2)
                if (!cancel) target.writeBytes(byteArrayOf(1, 2, 3))
                val before = listOf(original, target).associateWith(File::readBytes)
                val remaining = File(directory, "remaining.wv")
                if (cancel) {
                    val job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        engine.renderRemaining(original, listOf(target), remaining, 16L * 1024 * 1024, 2)
                    }
                    val observer = object : FileObserver(directory.absolutePath, FileObserver.CREATE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path == CANONICAL_ORIGINAL_PCM_FILE) job.cancel()
                        }
                    }
                    observer.startWatching()
                    try {
                        job.start()
                        withTimeout(10_000L) { job.join() }
                        assertTrue("Preparation cancellation was not exercised", job.isCancelled)
                        assertFalse(remaining.exists())
                    } finally { observer.stopWatching(); job.cancel(); job.join() }
                } else {
                    val failure = runCatching {
                        engine.renderRemaining(original, listOf(target), remaining, 16L * 1024 * 1024, 2)
                    }.exceptionOrNull()
                    assertEquals("REMAINING_RENDER_FAILED", failure?.message)
                }
                assertFalse(File(directory, CANONICAL_ORIGINAL_PCM_FILE).exists())
                before.forEach { (file, bytes) -> assertTrue(bytes.contentEquals(file.readBytes())) }
            } finally { assertTrue(directory.deleteAfterWorkerRetirement()) }
        }
    }

    @Test
    fun remainingSubtractsLosslessTargetOnExactOriginalTimeline() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "remaining-render-${java.util.UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val input = File(directory, "input.flac")
        val original = File(directory, "original.opus")
        val target = File(directory, "target.flac")
        val repeatedTarget = File(directory, "same-target.flac")
        val remaining = File(directory, "remaining.wv")
        try {
            instrumentation.context.assets.open("encoder/flac16-stereo-44100.flac").use { source ->
                input.outputStream().use(source::copyTo)
            }
            val engine = YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null)
            app.stemwise.enrichment.GoldenSongMediaFixture.encodeOriginalOpus(context, input, original, 16L * 1024 * 1024)
            engine.encodeLosslessPart(original, target, 16L * 1024 * 1024, 2)
            engine.encodeLosslessPart(original, repeatedTarget, 16L * 1024 * 1024, 2)
            assertTrue("Identical canonical audio must retain the same encoded content identity",
                target.readBytes().contentEquals(repeatedTarget.readBytes()))
            val originalFacts = OggOpusInspector.inspect(original)
            engine.renderRemaining(original, listOf(target), remaining, 16L * 1024 * 1024,
                originalChannels = originalFacts.channels)
            val expectedFrames = originalFacts.decodedAudibleFrameCount
            assertEquals(expectedFrames, inspectPreparedSourceAudio(target).audibleFrames)
            assertEquals(expectedFrames, inspectPreparedSourceAudio(remaining).audibleFrames)
            val ended = CountDownLatch(1)
            val decoderFailure = AtomicReference<NativeDecoderFailure?>()
            var frames = 0L
            var peak = 0.0f
            val decoder = MediaCodecTrackDecoder(context, "remaining-test", "remaining",
                NativeMediaSource.fromLocation(remaining.absolutePath, directFloat32Audio = true), NativePcmSink { block ->
                    frames += block.frameCount
                    val samples = block.data.duplicate().order(block.format.byteOrder)
                    while (samples.hasRemaining()) {
                        val value = if (block.format.encoding == NativePcmEncoding.PCM_FLOAT)
                            samples.float else samples.short / 32768f
                        peak = maxOf(peak, abs(value))
                    }
                    NativePcmSinkResult.accepting(block.data.remaining())
                }, listener = object : NativeMediaCodecDecoderListener {
                    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) { ended.countDown() }
                    override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                        decoderFailure.set(failure); ended.countDown()
                    }
                },
                    packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
            try {
                assertTrue(decoder.start())
                assertTrue("Remaining decoder did not reach EOS", ended.await(10, TimeUnit.SECONDS))
                org.junit.Assert.assertNull(decoderFailure.get())
                assertEquals(expectedFrames, frames)
                assertTrue("Original minus its PCM16 lossless readback must differ by at most quantization; peak=$peak", peak <= 2f / 32768f)
            } finally { decoder.close(); assertTrue(decoder.awaitClosed(5, TimeUnit.SECONDS)) }
        } finally {
            assertTrue(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            assertTrue(directory.deleteAfterWorkerRetirement())
        }
    }

    private fun decodeStereo48k(context: android.content.Context, file: File, directFloat: Boolean): FloatArray {
        val samples = FloatArray(48_000 * 2)
        val ended = CountDownLatch(1)
        val failure = AtomicReference<NativeDecoderFailure?>()
        var frames = 0
        val decoder = MediaCodecTrackDecoder(context, "remaining-closure", "part",
            NativeMediaSource.fromLocation(file.absolutePath, directFloat32Audio = directFloat),
            NativePcmSink { block ->
                assertEquals(48_000, block.format.sampleRateHz)
                assertEquals(2, block.format.channelCount)
                assertEquals(frames.toLong(), block.firstTimelineFrame)
                val count = minOf(257, block.frameCount)
                val pcm = block.data.duplicate().order(block.format.byteOrder)
                repeat(count * 2) { index ->
                    samples[frames * 2 + index] = if (block.format.encoding == NativePcmEncoding.PCM_FLOAT)
                        pcm.float else pcm.short / 32768f
                }
                frames += count
                NativePcmSinkResult.accepting(count * block.format.bytesPerFrame)
            }, listener = object : NativeMediaCodecDecoderListener {
                override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) { ended.countDown() }
                override fun onError(error: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                    failure.set(error); ended.countDown()
                }
            },
                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        try {
            assertTrue(decoder.start())
            assertTrue("Closure decoder did not reach EOS", ended.await(10, TimeUnit.SECONDS))
            org.junit.Assert.assertNull(failure.get())
            assertEquals("${file.name}: facts=${inspectPreparedSourceAudio(file)}; " +
                "decoder=${decoder.diagnostics()}", 48_000, frames)
            return samples
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5, TimeUnit.SECONDS))
        }
    }
    @Test
    fun bundledFfmpegNormalizesFlac16ToCurrentLossless48kStereo() = runBlocking {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "qualified-stem-input.flac")
        val output = File(context.cacheDir, "current-stem-output.flac.part")
        try {
            instrumentation.context.assets.open("encoder/flac16-stereo-44100.flac").use { source ->
                input.outputStream().use(source::copyTo)
            }
            val flac = inspectFlac16(input)
            assertEquals(44_100, flac.sampleRateHz)
            assertEquals(2, flac.channelCount)
            assertEquals(1_000L, flac.durationMs)

            YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).encodeLosslessPart(input, output, 16L * 1024L * 1024L, channels = 2)
            val normalized = inspectPreparedSourceAudio(output)
            assertEquals("audio/flac", normalized.mime)
            assertEquals(16, normalized.bitsPerSample)
            assertFalse(normalized.isFloat)
            assertEquals(2, normalized.channels)
            assertEquals(48_000, normalized.sampleRate)
            assertEquals(48_000L, normalized.audibleFrames)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = extractor.getTrackFormat(0)
                // Some Android extractors expose decoded PCM for FLAC; the header above owns codec/precision.
                val decoderMime = format.getString(MediaFormat.KEY_MIME)
                assertTrue("Unsupported FLAC decoder route: $decoderMime",
                    decoderMime == "audio/flac" || decoderMime == "audio/raw")
                assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally {
                extractor.release()
            }
        } finally {
            assertTrue(!input.exists() || input.delete())
            assertTrue(!output.exists() || output.delete())
        }
    }
}
