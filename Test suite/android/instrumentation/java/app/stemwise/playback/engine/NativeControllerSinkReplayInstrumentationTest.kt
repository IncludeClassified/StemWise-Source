package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.integration.MergerOracleFixture
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual controller, decoder, JNI, render and feeder; only the device sink is scripted. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeControllerSinkReplayInstrumentationTest {
    @Test fun delayedCanonicalCacheFallsBackThroughTheRealIndependentWindowReader() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("delayed-source-window-", ".wav", context.cacheDir)
        val codes = IntArray(8_192 * 2) { sample -> (sample * 31 % 12_001) - 6_000 }
        MergerOracleFixture.writeWave(source, codes, bits = 16, channels = 2)
        try {
            NativeAudioOutput(deferPhysicalOpen = true,
                sinkFactory = { error("An independent waveform read must not open physical output") }).use { output ->
                NativeSongLaneIngressBank().use { first ->
                    NativeSongLaneIngressBank().use { second ->
                        val retiredTickets = mutableListOf<Long>()
                        // Only optional cache I/O is delayed; construction, decoder, SRC and ingress remain real.
                        val backend = object : NativePlaybackRuntimeBackend by NativePlaybackRuntimeJniBackend {
                            override fun beginPcmWindowRead(handle: Long, owner: LongArray, lane: Int, first: Long, frames: Int) = 41L
                            override fun pollPcmWindowRead(handle: Long, ticket: Long, pcm: ByteBuffer, frames: Int) = 0
                            override fun cancelPcmWindowRead(handle: Long, ticket: Long) { retiredTickets.add(ticket) }
                        }
                        NativePlaybackRuntime(output.borrowNativeHandleForRuntime(), first.nativeHandleForRenderRuntime(),
                            second.nativeHandleForRenderRuntime(), NativeRuntimeConfiguration(), backend).use { runtime ->
                            val firstFrame = 512L
                            val count = 256
                            val expected = FloatArray(count * 2) { codes[firstFrame.toInt() * 2 + it] / 32768f }
                            val owner = longArrayOf(17L, 19L, 23L, 29L, 0L)
                            for (kind in listOf("delayed", "unsupported", "prefix")) {
                                val actual = readNativeSourcePcmWindow(context, NativeMediaSource.LocalFile(source), firstFrame, count,
                                    packetReaderFactory = stemwiseAudioPacketReaderFactory, readCached = {
                                        when (kind) {
                                            "delayed" -> runtime.readPcmWindow(owner, 0, firstFrame, count).also {
                                                assertEquals(SourcePcmWindowRead.Delayed, it)
                                                assertEquals(listOf(41L), retiredTickets)
                                            }
                                            "unsupported" -> SourcePcmWindowRead.Unsupported
                                            else -> SourcePcmWindowRead.Ready(expected.copyOf(64 * 2))
                                        }
                                    })
                                assertArrayEquals("$kind real decoder fallback must preserve absolute source PCM", expected, actual, 0f)
                            }
                            for (result in listOf(SourcePcmWindowRead.Stale, SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_READ_FAILED"))) {
                                val failure = runCatching {
                                    readNativeSourcePcmWindow(context, NativeMediaSource.LocalFile(source), firstFrame, count,
                                        packetReaderFactory = stemwiseAudioPacketReaderFactory, readCached = { result })
                                }.exceptionOrNull()
                                if (result == SourcePcmWindowRead.Stale) assertTrue(failure is kotlinx.coroutines.CancellationException)
                                else assertEquals("SOURCE_WINDOW_CACHE_READ_FAILED", failure?.message)
                            }
                        }
                    }
                }
            }
        } finally { check(source.delete()) }
    }

    @Test fun lowRateBoundedLoopAndTrimmedRepeatRetainRealPrefixBeforeExactAuthoredA() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (rate in listOf(11_025, 22_050)) {
            val wave = File.createTempFile("bounded-low-rate-", ".wav", context.cacheDir)
            val flac = File.createTempFile("bounded-low-rate-", ".flac", context.cacheDir)
            try {
                val codes = IntArray(rate * 6 * 2) { sample ->
                    (sin(2 * PI * 173 * (sample / 2).toDouble() / rate) * 4_000).roundToInt()
                }
                MergerOracleFixture.writeWave(wave, codes, bits = 16, rate = rate, channels = 2)
                MergerOracleFixture.encodeFlac(context, wave, flac)
                for (repeatOne in listOf(false, true)) {
                    val actual = JniNativeEngineEnvironment(context,
                        outputSinkFactory = { error("Paused cache preparation must not open physical output") },
                        packetReaderFactory = stemwiseAudioPacketReaderFactory)
                    val specs = Collections.synchronizedList(mutableListOf<NativeEngineLoopPreparationSpec>())
                    val intents = Collections.synchronizedList(mutableListOf<NativeRuntimeManagedLoopIntent>())
                    val environment = object : NativeEngineEnvironment by actual {
                        override fun beginLoopHeadPreparation(intent: NativeRuntimeManagedLoopIntent): NativeRuntimeLoopHeadBeginStatus {
                            intents.add(intent)
                            return actual.beginLoopHeadPreparation(intent)
                        }
                        override fun tryOpenLoopHeadPreparationDecoder(spec: NativeEngineLoopPreparationSpec): NativeEngineLoopPreparationDecoder? {
                            specs.add(spec)
                            return actual.tryOpenLoopHeadPreparationDecoder(spec)
                        }
                    }
                    val controller = NativePlaybackEngineController(environment)
                    val boundary = "bounded FLAC rate=$rate repeatOne=$repeatOne A=1010ms"
                    try {
                        val selection = NativeEngineSelectionRequest("bounded-$rate-$repeatOne", "fixture-v1",
                            flac.absolutePath, 6_000L, startPositionMs = 1_010L, autoplay = false,
                            playbackStartMs = if (repeatOne) 1_010L else 0L)
                        if (repeatOne) controller.setAutomaticEndPlan(
                            NativeEngineAutomaticEndPlan.RestartCurrent(selection.identity(), selection.durationMs))
                        val token = controller.select(selection)
                        if (!repeatOne) {
                            await("$boundary establishes the exact owner before authoring A/B") {
                                controller.diagnosticsSnapshot().controllerPublicOwnership?.selectionToken == token.value
                            }
                            val owner = requireNotNull(controller.diagnosticsSnapshot().controllerPublicOwnership)
                            assertTrue(controller.updateExplicitLoop(EngineExplicitLoopState(token, 1L,
                                EngineLoopConfig(1_010L, 5_010L), owner)))
                        }
                        await("$boundary commits actual decoder/JNI/cache preparation") {
                            actual.loopProtocolSnapshot().preparation.commits > 0L &&
                                controller.diagnosticsSnapshot().controllerPublishedLoopAvailable
                        }
                        val relevant = synchronized(intents) { intents.filter { it.cacheKey?.startTimelineFrame == 48_480L } }
                        assertTrue("$boundary must retain the exact authored cache key", relevant.isNotEmpty())
                        assertTrue("$boundary must decode real preroll", synchronized(specs) {
                            specs.any { it.initialPositionUs == 1_009_000L }
                        })
                        val diagnostics = controller.diagnosticsSnapshot()
                        val selected = requireNotNull(diagnostics.controllerPublicOwnership)
                        assertEquals(token.value, selected.selectionToken)
                        assertTrue("$boundary must write the complete real four-second cache",
                            actual.loopProtocolSnapshot().preparation.cacheFramesWritten >= 192_000L)
                        assertEquals("$boundary must not turn a real first-frame gap into cache success", 0L,
                            actual.loopProtocolSnapshot().preparation.failures)
                    } finally { controller.close() }
                }
            } finally { check(wave.delete()); check(flac.delete()) }
        }
    }

    @Test fun coldLowRateFlacStartsAtTheRequestedNativeCursorWithMatchingSignal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun signal(seconds: Double, channel: Int): Double = if (channel == 0)
            .08 * sin(2 * PI * 173 * seconds + .23) + .025 * cos(2 * PI * 331 * seconds + .41)
        else .07 * cos(2 * PI * 257 * seconds + .67) + .020 * sin(2 * PI * 419 * seconds + .90)
        for (rate in listOf(11_025, 22_050)) {
            val wave = File.createTempFile("cold-native-source-", ".wav", context.cacheDir)
            val flac = File.createTempFile("cold-native-source-", ".flac", context.cacheDir)
            try {
                val codes = IntArray(rate * 2 * 2) { sample ->
                    (signal((sample / 2).toDouble() / rate, sample % 2) * 32768).roundToInt()
                }
                MergerOracleFixture.writeWave(wave, codes, bits = 16, rate = rate, channels = 2)
                MergerOracleFixture.encodeFlac(context, wave, flac)
                for (positionMs in listOf(1_001L, 1_002L)) {
                    val sink = ScriptedSink().apply { allowWrites.set(true) }
                    val environment = JniNativeEngineEnvironment(context, outputSinkFactory = { sink },
                        packetReaderFactory = stemwiseAudioPacketReaderFactory)
                    val controller = NativePlaybackEngineController(environment)
                    val boundary = "AE-02 cold FLAC rate=$rate start=$positionMs"
                    try {
                        val token = controller.select(NativeEngineSelectionRequest("cold-$rate-$positionMs", "fixture-v1",
                            flac.absolutePath, 2_000L, startPositionMs = positionMs, autoplay = true))
                        await("$boundary accepts real decoder/SRC output") { sink.samples.size / 2 == 2_048 }
                        val actual = synchronized(sink.samples) { sink.samples.toList() }
                        // 512 output frames exceed the low-rate Speex filter support and the 240/96-frame startup edges.
                        for (frame in 512 until 2_048) for (channel in 0..1) {
                            val expected = .9 * signal(positionMs / 1_000.0 + frame / 48_000.0, channel)
                            val derivative = 2 * PI * if (channel == 0) (.08 * 173 + .025 * 331) else (.07 * 257 + .020 * 419)
                            // Source-start floor tags differ by less than one 48k frame; add PCM16/SRC quantization error.
                            val tolerance = .9 * derivative / 48_000 + .00005
                            assertEquals("$boundary frame=$frame channel=$channel", expected, actual[frame * 2 + channel].toDouble(), tolerance)
                        }
                        assertEquals("$boundary acceptance cannot claim presentation", 0L,
                            requireNotNull(controller.diagnosticsSnapshot().output).lastPresentedSelectionToken)
                        sink.head.set(2_048L)
                        await("$boundary exact source cursor requires the actual sink head") {
                            controller.diagnosticsSnapshot().output?.let {
                                it.lastPresentedSelectionToken == token.value &&
                                    it.lastPresentedTimelineFrame == positionMs * 48L + 2_047L
                            } == true
                        }
                        val stop = controller.stopForTaskRemoval()
                        await("$boundary retires decoder/JNI/output") { stop.isComplete() }
                        assertTrue(sink.closed.get())
                    } finally {
                        sink.allowWrites.set(true)
                        controller.close()
                    }
                }
            } finally {
                check(wave.delete())
                check(flac.delete())
            }
        }
    }

    @Test fun partialZeroPauseResumePreservesPcmAndWaitsForActualPresentation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("controller-sink-replay-", ".wav", context.cacheDir)
        val codes = IntArray(8_192 * 2) { sample ->
            val code = 1_000 + sample / 2 % 4_096
            if (sample % 2 == 0) code else -2 * code
        }
        MergerOracleFixture.writeWave(source, codes, bits = 16, channels = 2)
        val sink = ScriptedSink()
        val environment = JniNativeEngineEnvironment(context, outputSinkFactory = { sink }, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        val controller = NativePlaybackEngineController(environment)
        try {
            val token = controller.select(NativeEngineSelectionRequest("sink-replay", "fixture-v1",
                source.absolutePath, 171L, autoplay = true))
            assertTrue("AE-02 partial then zero write", sink.zeroWrite.await(5, TimeUnit.SECONDS))
            assertEquals(64, sink.samples.size / 2)
            assertNull("AE-03 acceptance is not public ownership", controller.diagnosticsSnapshot().controllerPublicOwnership)
            assertTrue(controller.updateDesired(token) { it.copy(playWhenReady = false) })
            await("AE-12 pause adopted before releasing held suffix") {
                controller.diagnosticsSnapshot().runtime.endOrdering.events.any {
                    it.kind == NativeRuntimeEndOrderingKind.OUTPUT_PLAYING_APPLIED &&
                        it.ownership?.selectionToken == token.value && !it.desiredPlaying && !it.outputPlaying
                }
            }
            assertEquals("AE-02 zero writes cannot consume the suffix", 64, sink.samples.size / 2)
            val pauseSequence = controller.diagnosticsSnapshot().runtime.endOrdering.events.single {
                it.kind == NativeRuntimeEndOrderingKind.OUTPUT_PLAYING_APPLIED
            }.sequence
            assertTrue(controller.updateDesired(token) { it.copy(playWhenReady = true) })
            await("AE-12 resume adopted before granting source write capacity") {
                controller.diagnosticsSnapshot().runtime.endOrdering.events.any {
                    it.kind == NativeRuntimeEndOrderingKind.OUTPUT_PLAYING_APPLIED &&
                        it.sequence > pauseSequence && it.desiredPlaying && it.outputPlaying
                }
            }
            sink.allowWrites.set(true)
            await("AE-02 resumed native PCM fills the scripted sink") { sink.samples.size / 2 == 2_048 }
            val actual = synchronized(sink.samples) { sink.samples.toList() }
            // The existing 240-frame startup transition is tested elsewhere; this region is settled.
            for (frame in 512 until 2_048) for (channel in 0..1) {
                val index = frame * 2 + channel
                assertEquals("AE-02 frame=$frame channel=$channel", codes[index] / 32768f * .9f,
                    actual[index], 0.000001f)
            }
            assertEquals("AE-03 a full accepted queue still is not presentation", 0L,
                requireNotNull(controller.diagnosticsSnapshot().output).lastPresentedSelectionToken)
            sink.head.set(2_048L)
            await("AE-03 exact native source is presented only after sink observation") {
                controller.diagnosticsSnapshot().output?.lastPresentedSelectionToken == token.value
            }
            val stop = controller.stopForTaskRemoval()
            await("AE-04 native and sink retirement acknowledge STOP") { stop.isComplete() }
            assertTrue(sink.closed.get())
        } finally {
            sink.allowWrites.set(true)
            controller.close()
            check(source.delete())
        }
    }

    private class ScriptedSink : NativeAudioTrackFeeder.Sink {
        override val configuration = NativeAudioTrackFeeder.Configuration(
            48_000, 2, android.media.AudioFormat.ENCODING_PCM_FLOAT, 2_048, 2_048, 512,
            android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY, 256)
        val samples = Collections.synchronizedList(mutableListOf<Float>())
        val zeroWrite = CountDownLatch(1)
        val allowWrites = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val head = AtomicLong(0L)
        @Volatile private var playing = false
        override fun write(pcm: ByteBuffer): Int {
            val accepted = samples.size / 2
            if (accepted >= 64 && !allowWrites.get()) { zeroWrite.countDown(); return 0 }
            // Withhold transport-silence capacity too: no accepted pause padding obscures this source oracle.
            val inspect = pcm.duplicate().order(pcm.order())
            var source = false
            while (inspect.hasRemaining()) if (inspect.float != 0f) { source = true; break }
            if (!source) return 0
            val frames = minOf(pcm.remaining() / 8, if (accepted == 0) 64 else 2_048 - accepted)
            repeat(frames * 2) { samples.add(pcm.float) }
            return frames * 8
        }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun observe(actual: IntArray, position: LongArray) {
            actual[NativePacketSchema.TRACK_ACTUAL_STATE] =
                (if (playing) NativeAudioStreamState.STARTED else NativeAudioStreamState.PAUSED).rawValue
            actual[NativePacketSchema.TRACK_ACTUAL_BUFFER] = configuration.bufferFrames
            actual[NativePacketSchema.TRACK_ACTUAL_CAPACITY] = configuration.capacityFrames
            actual[NativePacketSchema.TRACK_ACTUAL_RATE] = configuration.sampleRate
            actual[NativePacketSchema.TRACK_ACTUAL_PERFORMANCE] = configuration.performanceMode
            actual[NativePacketSchema.TRACK_ACTUAL_UNDERRUNS] = 0
            position[NativePacketSchema.TRACK_POSITION_HEAD] = head.get()
        }
        override fun close() { closed.set(true) }
    }

    private fun await(boundary: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(boundary, condition())
    }
}
