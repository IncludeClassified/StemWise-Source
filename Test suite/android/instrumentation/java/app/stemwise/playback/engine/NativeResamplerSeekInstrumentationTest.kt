package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.integration.MergerOracleFixture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeResamplerSeekInstrumentationTest {
    @Test fun gaplessMp3ColdSeekAndNonzeroLoopHeadMatchTheZeroOriginDecode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("gapless-resampler-seek-", ".mp3", instrumentation.targetContext.cacheDir)
        try {
            // Genuine libmp3lame Xing delay/padding, encoded at 44.1 kHz from a three-second chirp.
            file.writeBytes(instrumentation.context.assets.open("decoder/gapless-mp3.mp3.base64").use {
                Base64.getMimeDecoder().decode(it.bufferedReader(Charsets.UTF_8).readText())
            })
            DecodeReadback(file, 0L).use { zero ->
                val baseline = zero.await(0L)
                val track = requireNotNull(zero.prepared.get()).track
                assertEquals(44_100, track.sampleRateHz)
                assertNotEquals("audio/opus", track.mimeType)
                assertTrue("Extractor must expose real encoder delay: $track", track.encoderDelayFrames > 0)
                for (targetMs in listOf(11L, 1_000L, 1_010L, 2_010L, 2_500L)) {
                    val generation = zero.decoder.seekToUs(targetMs * 1_000L)
                    assertWindow(baseline, zero.await(generation), targetMs * 48L, 1_024,
                        "gapless MP3 resident $targetMs")
                    DecodeReadback(file, targetMs * 1_000L).use { cold ->
                        val actual = cold.await(0L)
                        assertEquals("MP3 must derive sample positions from the real recording origin",
                            0L, cold.decoder.diagnostics().extractorStartPresentationTimeUs)
                        assertEquals("A flushed MP3 seek uses the same origin as a cold seek",
                            0L, zero.decoder.diagnostics().extractorStartPresentationTimeUs)
                        assertWindow(baseline, actual, targetMs * 48L, 1_024, "gapless MP3 cold $targetMs")
                    }
                }
            }
            val zeroOriginLoop = prepareGaplessLoop(file, decodeFromZero = true)
            val coldLoop = prepareGaplessLoop(file, decodeFromZero = false)
            assertEquals(zeroOriginLoop.key, coldLoop.key)
            assertEquals(zeroOriginLoop.frameCount, coldLoop.frameCount)
            assertEquals("The committed nonzero loop head must contain the zero-origin reference PCM",
                zeroOriginLoop.laneChecksums[0], coldLoop.laneChecksums[0])
        } finally { assertTrue(file.delete()) }
    }

    private fun prepareGaplessLoop(file: File, decodeFromZero: Boolean): NativeRuntimeLoopHeadCacheSnapshot {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JniNativeEngineEnvironment(context,
            outputSinkFactory = { error("Paused loop preparation must not open physical output") },
            packetReaderFactory = stemwiseAudioPacketReaderFactory).use { environment ->
            val owner = NativeRuntimeOwnership(1L, 2L)
            val original = environment.openDecoder(NativeRuntimeBank.FIRST, NativeSongBankRole.ACTIVE, 0,
                NativeEngineDecoderSpec("gapless-original", "original", file.absolutePath, 0L,
                    owner.generation, EngineSelectionToken(owner.selectionToken)))
            try {
                assertTrue(original.start())
                assertEquals(NativeRuntimePublishStatus.ACCEPTED, environment.publish(
                    NativeRuntimeCommandSnapshot(1L, owner.generation,
                        listOf(NativeRuntimeBankIntent(1L, owner, songIdentityHash = 101L,
                            originalContentHash = 201L, cacheFrameUpperBound = 144_000L),
                            NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST, playing = false,
                        controls = NativeRuntimeControls(1L, sourceIntentRevision = 1L, stemIntentRevision = 1L))))
                assertTrue(environment.startRuntime())
                await("Original supplies actual PCM to the selected native owner") {
                    check(original.failure() == null) { original.failure().toString() }
                    original.head().acceptedTimelineFrames48k >= 1_024L
                }
                await("Native runtime accepts the exact selected owner") {
                    environment.diagnostics().ownership.requested == owner
                }
                val key = NativeRuntimeManagedLoopContentKey(101L, 201L, 0L, 1L, 1L, 96_480L, 288_480L)
                val intent = NativeRuntimeManagedLoopIntent(1L, owner, true, key, 1)
                assertEquals(NativeRuntimePublishStatus.ACCEPTED, environment.publishManagedLoopIntent(intent))
                assertEquals(NativeRuntimeLoopHeadBeginStatus.ACCEPTED, environment.beginLoopHeadPreparation(intent))
                val decoder = requireNotNull(environment.tryOpenLoopHeadPreparationDecoder(
                    NativeEngineLoopPreparationSpec("gapless-loop", "original", 0, file.absolutePath,
                        if (decodeFromZero) 0L else 2_009_000L, 1L, 1L)))
                try {
                    assertTrue(decoder.start())
                    await("Gapless loop decoder completes actual native cache publication") {
                        decoder.snapshot().outcome != NativeEngineLoopPreparationOutcome.RUNNING
                    }
                    assertEquals(decoder.snapshot().toString(), NativeEngineLoopPreparationOutcome.CACHE_COMMITTED,
                        decoder.snapshot().outcome)
                    await("Native runtime adopts the exact committed gapless loop cache") {
                        environment.loopProtocolSnapshot().activeCache?.key == key
                    }
                    val snapshot = environment.loopProtocolSnapshot()
                    assertEquals(0L, snapshot.preparation.failures)
                    assertEquals(1L, snapshot.preparation.commits)
                    assertNull(original.failure())
                    return requireNotNull(snapshot.activeCache).also {
                        assertEquals(144_000 - 96_480, it.frameCount)
                        assertEquals(1, it.completedLaneMask)
                    }
                } finally {
                    assertEquals(NativeEngineDecoderJoinStatus.JOINED, decoder.cancelAndJoin(5_000L))
                    environment.releaseLoopHeadPreparationDecoder(decoder)
                }
            } finally {
                environment.stopRuntimeAndJoin()
                assertEquals(NativeEngineDecoderJoinStatus.JOINED, original.cancelAndJoin(5_000L))
                environment.releaseDecoder(NativeRuntimeBank.FIRST, original)
                assertEquals(0, environment.decoderLeaseSnapshot().totalDecoderCount)
            }
        }
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (!condition()) {
            check(System.nanoTime() < deadline) { label }
            Thread.sleep(5L)
        }
    }

    @Test fun actualDecoderAndJniKeepSameTagPcmAcrossColdResidentAndWindowReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (rate in listOf(8_000, 44_100, 48_000, 96_000)) {
            val file = File.createTempFile("resampler-seek-", ".wav", context.cacheDir)
            try {
                val frequency = minOf(10_000, rate / 3)
                MergerOracleFixture.writeWave(file, IntArray(rate * 3) { frame ->
                    (12_000.0 * sin(2.0 * Math.PI * frequency * frame / rate)).toInt()
                }, bits = 16, rate = rate, channels = 1)
                DecodeReadback(file, 0L).use { resident ->
                    val baseline = resident.await(0L)
                    if (rate == 44_100) DecodeReadback(file, 2_001_000L, retainHistory = false).use { old ->
                        val resetAtMillisecond = old.await(0L)
                        val first = 2_002 * 48 + 1_000
                        assertTrue("The unchanged decoder/SRC path must expose the old same-tag phase error",
                            (first until first + 512).any { frame ->
                                check(baseline.present[frame] && resetAtMillisecond.present[frame])
                                baseline.samples[frame * 2].toRawBits() != resetAtMillisecond.samples[frame * 2].toRawBits()
                            })
                    }
                    for (targetMs in listOf(11L, 2_001L, 2_002L, 2_017L, 2_978L)) {
                        val generation = resident.decoder.seekToUs((targetMs - 1L) * 1_000L)
                        assertWindow(baseline, resident.await(generation), targetMs * 48L, 1_024,
                            "$rate resident $targetMs")
                        DecodeReadback(file, (targetMs - 1L) * 1_000L).use { cold ->
                            assertWindow(baseline, cold.await(0L), targetMs * 48L, 1_024,
                                "$rate cold $targetMs")
                        }
                    }
                    val first = 2_002L * 48L
                    val window = runBlocking { readNativeSourcePcmWindow(context,
                        NativeMediaSource.LocalFile(file), first, 1_024,
                        stemwiseAudioPacketReaderFactory) }
                    for (i in window.indices) assertEquals("$rate independent window sample=$i",
                        baseline.samples[first.toInt() * 2 + i].toRawBits(), window[i].toRawBits())
                }
            } finally { assertTrue(file.delete()) }
        }
    }

    private fun assertWindow(expected: Capture, actual: Capture, first: Long, count: Int, label: String) {
        for (frame in first.toInt() until first.toInt() + count) {
            assertTrue("$label missing baseline frame=$frame", expected.present[frame])
            assertTrue("$label missing actual frame=$frame", actual.present[frame])
            repeat(2) { channel ->
                val index = frame * 2 + channel
                assertEquals("$label frame=$frame channel=$channel",
                    expected.samples[index].toRawBits(), actual.samples[index].toRawBits())
            }
        }
    }

    private class Capture {
        val samples = FloatArray(3 * 48_000 * 2)
        val present = BooleanArray(3 * 48_000)
    }

    private class DecodeReadback(file: File, initialPositionUs: Long, retainHistory: Boolean = true) : AutoCloseable {
        private val bank = NativeSongLaneIngressBank(16_384, 1_024)
        private val pool = NativeDecoderWorkerPool.dedicated("ResamplerReadback")
        private val completed = LinkedBlockingQueue<Long>()
        private val failures = LinkedBlockingQueue<Throwable>()
        private val captures = ConcurrentHashMap<Long, Capture>()
        private var lane: NativeLaneIngressSink? = null
        private var activeGeneration = -1L
        private var acceptedEnd = 0L
        private val output = ByteBuffer.allocateDirect(512 * 8).order(ByteOrder.nativeOrder())
        private val metadata = LongArray(3)
        val prepared = AtomicReference<NativeDecoderPrepared>()
        val decoder = MediaCodecTrackDecoder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "resampler-readback", "original", NativeMediaSource.LocalFile(file),
            NativePcmSink { block ->
                activate(block.seekGeneration)
                val accepted = requireNotNull(lane).offer(block)
                acceptedEnd = block.firstTimelineFrame + accepted.acceptedBytes / block.format.bytesPerFrame
                drain(block.seekGeneration)
                accepted
            },
            listener = object : NativeMediaCodecDecoderListener {
                override fun onPrepared(prepared: NativeDecoderPrepared) { this@DecodeReadback.prepared.set(prepared) }
                override fun onOutputFormatChanged(format: NativePcmFormat, seekGeneration: Long) {
                    if (lane == null) lane = bank.configureLane(0, format.sampleRateHz, 1L)
                    activate(seekGeneration)
                }
                override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
                    try {
                        check(requireNotNull(lane).markEndOfStream(seekGeneration, acceptedEnd) ==
                            NativeLaneEndOfStreamStatus.ACCEPTED)
                        drain(seekGeneration)
                        completed.put(seekGeneration)
                    } catch (failure: Throwable) { failures.put(failure); completed.put(-1L) }
                }
                override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                    failures.put(AssertionError("$failure; $diagnostics")); completed.put(-1L)
                }
            },
            config = MediaCodecTrackDecoderConfig(initialPositionUs = initialPositionUs,
                initialCopyBufferBytes = 4_096, retainResamplerHistory = retainHistory),
            workerPool = pool, packetReaderFactory = stemwiseAudioPacketReaderFactory
        ).also { check(it.start()) }

        private fun activate(generation: Long) {
            if (activeGeneration == generation) return
            check(requireNotNull(lane).advanceDecoderGeneration(generation))
            activeGeneration = generation
            acceptedEnd = 0L
        }

        private fun drain(generation: Long) {
            val capture = captures.getOrPut(generation) { Capture() }
            while (true) {
                output.clear()
                val count = bank.pullOriginal48kForPreparation(generation + 1L, output, metadata)
                check(metadata[1] in listOf(0L, 1L, 2L, 4L)) { "Native read failed: ${metadata.toList()}" }
                if (count == 0) {
                    if (metadata[2] > 0L) continue
                    return
                }
                val floats = output.asFloatBuffer()
                repeat(count) { offset ->
                    val frame = Math.toIntExact(metadata[0] + offset)
                    check(frame in capture.present.indices && !capture.present[frame])
                    capture.present[frame] = true
                    capture.samples[frame * 2] = floats.get()
                    capture.samples[frame * 2 + 1] = floats.get()
                }
            }
        }

        fun await(generation: Long): Capture {
            val actual = completed.poll(10L, TimeUnit.SECONDS)
            failures.poll()?.let { throw AssertionError("Decode failed", it) }
            assertEquals("Decoder must retire each exact generation", generation, actual)
            return requireNotNull(captures[generation])
        }

        override fun close() {
            decoder.close()
            check(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            bank.close()
            pool.close()
        }
    }
}
