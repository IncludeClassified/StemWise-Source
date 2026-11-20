package app.stemwise.playback.engine

import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.inspectPreparedSourceAudio
import app.stemwise.data.CurrentSourcePublicationFixture
import app.stemwise.integration.YoutubeDlEngine
import app.stemwise.playback.PlaybackTestMediaProvider
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class MediaCodecTrackDecoderInstrumentationTest {
    @Test fun localSourceLeasePinsBytesAndExtractorAcrossPathReplacement() {
        val source = File.createTempFile("source-lease-", ".wav", context.cacheDir)
        val replacement = File.createTempFile("source-replacement-", ".wav", context.cacheDir)
        try {
            app.stemwise.integration.MergerOracleFixture.writeWave(source, IntArray(4_800 * 2) { 1_000 }, bits = 16)
            app.stemwise.integration.MergerOracleFixture.writeWave(replacement, IntArray(9_600 * 2) { 2_000 }, bits = 16)
            val expected = source.readBytes()
            val media = NativeMediaSource.LocalFile(source)
            media.withDescriptor(context, android.os.CancellationSignal()) { opened ->
                val descriptor = requireNotNull(opened)
                check(replacement.renameTo(source))
                assertEquals(44 + 9_600 * 4L, source.length())
                val actual = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.readBytes() }
                org.junit.Assert.assertArrayEquals(expected, actual)
                var replacementConsumed = false
                org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                    media.withDescriptor(context, android.os.CancellationSignal()) { replacementConsumed = true }
                }
                assertFalse("Changed pathname bytes cannot enter the old source lease", replacementConsumed)
                val extractor = android.media.MediaExtractor()
                try {
                    media.setDataSource(extractor, descriptor)
                    assertEquals(100_000L, extractor.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION))
                } finally { extractor.release() }
            }
        } finally { source.delete(); replacement.delete() }
    }

    @Test fun localSourceLeaseRejectsSameExtentByteReplacementAndWrongExpectedHash() {
        val source = File.createTempFile("source-identity-", ".bin", context.cacheDir)
        try {
            source.writeBytes(byteArrayOf(1, 2, 3))
            val media = NativeMediaSource.LocalFile(source,
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
            media.withDescriptor(context, android.os.CancellationSignal()) {}
            source.writeBytes(byteArrayOf(3, 2, 1))
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                media.withDescriptor(context, android.os.CancellationSignal()) { error("Changed source admitted") }
            }
            val newLease = NativeMediaSource.LocalFile(source, "0".repeat(64))
            org.junit.Assert.assertThrows(java.io.IOException::class.java) {
                newLease.withDescriptor(context, android.os.CancellationSignal()) { error("Wrong expected hash admitted") }
            }
            source.writeBytes(byteArrayOf(1, 2, 3))
            media.withDescriptor(context, android.os.CancellationSignal()) {}
        } finally { check(source.delete()) }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ownedContentUris = linkedSetOf<Uri>()

    @Test
    fun generatedFloat32FixturePassesCanonicalAdmissionAndPreservesEveryRawBit() {
        val source = File.createTempFile("native-generated-wavpack-", ".wv", context.cacheDir)
        val bits = intArrayOf(0, Int.MIN_VALUE, 0x3fc00000, 0xc0300000.toInt(),
            1, 0x80000001.toInt(), 0x3e800000, 0xbf000000.toInt())
        val frames = 48_017
        try {
            app.stemwise.playback.PlaybackContentFixture.writeFloat32WavPack(context, source, frames) {
                repeat(frames * 2) { writeInt(Integer.reverseBytes(bits[it % bits.size])) }
            }
            val facts = inspectPreparedSourceAudio(source)
            assertEquals(frames.toLong(), facts.audibleFrames)
            assertEquals("audio/wavpack", facts.mime)
            java.io.FileInputStream(source).use { input ->
                WavPackReader(input.fd, 0L, source.length(), facts) {}.use { reader ->
                    val pcm = ByteBuffer.allocateDirect(frames * 8)
                    assertEquals(frames, reader.readFrames(0L, pcm, frames))
                    repeat(frames * 2) { assertEquals("Generated sample $it", bits[it % bits.size], pcm.int) }
                }
            }
        } finally { check(source.delete()) }
    }

    @Test
    fun firstAudioUsesItsRealExtractorIndexAndTextOnlyInputFails() {
        for (audio in listOf(true, false)) {
            val source = File.createTempFile("native-track-order-", ".mkv", context.cacheDir)
            val asset = if (audio) "leading-text-track.mkv" else "no-audio-track.mkv"
            instrumentation.context.assets.open("encoder/$asset").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val probe = DecoderProbe()
            val sink = RecordingSink(maximumFramesPerOffer = 257, backpressureOnce = false)
            val decoder = decoder("first-audio", "original", NativeMediaSource.LocalFile(source),
                sink, probe, NativeDecoderWorkerPool.shared)
            try {
                assertTrue(decoder.start())
                if (audio) {
                    assertEquals(0L, probe.requireEos(decoder).generation)
                    assertEquals(1, probe.prepared.get()?.track?.trackIndex)
                    assertEquals(1, decoder.diagnostics().selectedTrack?.trackIndex)
                    assertEquals(4_800L, sink.stats(0L).acceptedFrames)
                    assertNull(probe.errors.poll())
                } else {
                    assertEquals(NativeDecoderErrorCode.NO_AUDIO_TRACK, probe.errors.poll(5L, TimeUnit.SECONDS)?.code)
                    assertEquals(0, probe.preparedCount.get())
                    assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                }
            } finally {
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                check(source.delete())
            }
        }
    }

    @Test
    fun acceptedNativeEosCoversRealFractionalTailForSelectionAndPrewarm() {
        for ((rate, frames, expectedEnd) in listOf(Triple(44_100, 44_101, 48_002L),
            Triple(44_100, 44_100, 48_000L), Triple(48_000, 48_001, 48_001L))) {
            val source = File.createTempFile("native-fractional-eof-", ".wav", context.cacheDir)
            val environment = JniNativeEngineEnvironment(context, outputSinkFactory = {
                error("Decoder tail coverage does not require physical output")
            },
                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
            try {
                writeWaveHeader(source, rate, frames, 2, 16, 1) {
                    repeat(frames) { writeShort(0x0010); writeShort(0x00e0) }
                }
                // The environment retains ownership; this fixture is its only native consumer
                // until the controller below starts the render runtime.
                val bank = (JniNativeEngineEnvironment::class.java.getDeclaredField("banks")
                    .apply { isAccessible = true }.get(environment) as List<*>)[NativeRuntimeBank.FIRST.nativeCode]
                    as NativeSongLaneIngressBank
                for (role in listOf(NativeSongBankRole.ACTIVE, NativeSongBankRole.CANDIDATE)) {
                    val decoder = environment.openDecoder(NativeRuntimeBank.FIRST, role, 0,
                        NativeEngineDecoderSpec("fractional-$role", "original", source.absolutePath,
                            981_000L, 2L, EngineSelectionToken(1L)))
                    try {
                        assertTrue(decoder.start())
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        val output = ByteBuffer.allocateDirect(512 * 8).order(ByteOrder.nativeOrder())
                        val metadata = LongArray(3)
                        val expectedFirst = if (rate == 48_000) 47_088L else 0L
                        var nextFrame = expectedFirst
                        var checkedSampleFrames = 0
                        var drainedEos = false
                        while ((!drainedEos || decoder.head().endOfStreamTimelineFrameExclusive48k == null) &&
                            decoder.failure() == null && System.nanoTime() < deadline) {
                            if (!decoder.head().configured) {
                                Thread.sleep(5)
                                continue
                            }
                            output.clear()
                            val count = bank.pullOriginal48kForPreparation(2L, output, metadata)
                            assertTrue("Native pull failed: ${metadata.toList()}", metadata[1] in listOf(0L, 1L, 2L, 4L))
                            if (count > 0) {
                                assertEquals("Native PCM tags must remain contiguous", nextFrame, metadata[0])
                                val samples = output.asFloatBuffer()
                                repeat(count) { index ->
                                    val left = samples.get()
                                    val right = samples.get()
                                    val frame = metadata[0] + index
                                    // Known signed16 stereo values, away from the SRC edge response.
                                    if (frame in 47_088L until 47_344L) {
                                        assertEquals("Left sample at $frame", .125f, left, .00001f)
                                        assertEquals("Right sample at $frame", -.25f, right, .00001f)
                                        checkedSampleFrames++
                                    }
                                }
                                nextFrame += count
                            }
                            drainedEos = metadata[1] == 2L
                            if (count == 0) Thread.sleep(5)
                        }
                        assertNull(decoder.failure())
                        val head = decoder.head()
                        assertTrue("Native consumer did not reach EOS: ${decoder.pipelineDiagnostics()}", drainedEos)
                        assertEquals(256, checkedSampleFrames)
                        assertEquals(expectedEnd, nextFrame)
                        assertEquals(expectedFirst, head.firstTimelineFrame48k)
                        assertEquals(2L, head.nativeGeneration)
                        assertEquals(0L, head.discontinuities)
                        assertEquals(expectedEnd, head.endOfStreamTimelineFrameExclusive48k)
                        assertEquals(expectedEnd, head.acceptedTimelineFrameExclusive48k)
                        assertEquals(expectedEnd - expectedFirst,
                            head.acceptedTimelineFrames48k)
                    } finally {
                        assertEquals(NativeEngineDecoderJoinStatus.JOINED, decoder.cancelAndJoin(5_000L))
                        environment.releaseDecoder(NativeRuntimeBank.FIRST, decoder)
                    }
                }
                val events = CopyOnWriteArrayList<NativeEngineControllerEvent>()
                NativePlaybackEngineController(environment,
                    listener = NativeEngineControllerListener { events.add(it) }).use { controller ->
                    val durationMs = (frames * 1_000L + rate - 1L) / rate
                    val token = controller.select(NativeEngineSelectionRequest("fractional-tail", "exact-$rate-$frames",
                        source.absolutePath, durationMs, startPositionMs = 981L, autoplay = false))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var selectedHead: NativeEngineDecoderHead? = null
                    while (events.none { it is NativeEngineControllerEvent.SelectionFailed } && System.nanoTime() < deadline) {
                        val publication = events.filterIsInstance<NativeEngineControllerEvent.OwnerPublished>()
                            .lastOrNull { it.publication.selection.token == token }?.publication
                        val diagnostics = controller.diagnosticsSnapshot()
                        selectedHead = diagnostics.controllerSelectedDecoders.singleOrNull { it.laneIndex == 0 }
                            ?.head?.takeIf { publication != null &&
                                diagnostics.controllerPublicOwnership == publication.ownership &&
                                it.nativeGeneration == publication.ownership.generation }
                        if (selectedHead?.endOfStreamTimelineFrameExclusive48k != null) break
                        controller.signalNativeProgress()
                        Thread.sleep(5)
                    }
                    assertTrue("The real fractional tail did not settle: $events", events.any {
                        it is NativeEngineControllerEvent.OwnerPublished && it.publication.selection.token == token &&
                            it.publication.kind == NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED
                    })
                    val head = requireNotNull(selectedHead) { "The exact selected decoder was not published: $events" }
                    assertEquals(expectedEnd, head.endOfStreamTimelineFrameExclusive48k)
                    assertEquals(expectedEnd, head.acceptedTimelineFrameExclusive48k)
                }
            } finally {
                environment.close()
                check(source.delete())
            }
        }
    }

    @Before
    fun removeStaleDecoderArtifacts() = cleanupOwnedArtifacts()

    @After
    fun removeDecoderArtifacts() = cleanupOwnedArtifacts()

    @Test
    fun independentSourceWindowReadsAnExactNonzeroTenSecondStereoInterval() = runBlocking {
        val source = File(context.cacheDir, "native-decoder-window-stereo.wv")
        app.stemwise.playback.PlaybackContentFixture.writeFloat32WavPack(context, source, 14 * 48_000) {
            repeat(14 * 48_000) { frame ->
                writeInt(Integer.reverseBytes(((frame % 257 - 128) / 64f).toRawBits()))
                writeInt(Integer.reverseBytes(((frame % 127 - 63) / 32f).toRawBits()))
            }
        }
        val before = CRC32().apply { update(source.readBytes()) }.value
        val start = 3 * 48_000L + 12_345L
        val pcm = readNativeSourcePcmWindow(context,
            NativeMediaSource.Float32Audio(NativeMediaSource.LocalFile(source)), start, 480_000,
                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        assertEquals(960_000, pcm.size)
        for (frame in (0 until 480_000 step 257) + listOf(479_999)) {
            assertEquals(((start + frame) % 257 - 128) / 64f, pcm[frame * 2], 0f)
            assertEquals(((start + frame) % 127 - 63) / 32f, pcm[frame * 2 + 1], 0f)
        }
        assertEquals("Analysis must preserve source bytes", before, CRC32().apply { update(source.readBytes()) }.value)
    }

    @Test
    fun independentSourceWindowNormalizesNonzero44100MonoWithTheNativeClock() = runBlocking {
        val source = File(context.cacheDir, "native-decoder-window-mono.wav")
        writeWaveHeader(source, 44_100, 6 * 44_100, 1, bitsPerSample = 16, formatCode = 1) {
            repeat(6 * 44_100) { frame ->
                writeShort(java.lang.Short.reverseBytes((if (frame < 3 * 44_100) 8192 else -16384).toShort()).toInt())
            }
        }
        val pcm = readNativeSourcePcmWindow(context, NativeMediaSource.LocalFile(source),
            3 * 48_000L - 12_000L, 24_000,
                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        assertEquals(48_000, pcm.size)
        for (frame in listOf(0, 6_000, 11_000, 13_000, 18_000, 23_999)) {
            val expected = if (frame < 12_000) 0.25f else -0.5f
            assertEquals(expected, pcm[frame * 2], 0.0001f)
            assertEquals(expected, pcm[frame * 2 + 1], 0.0001f)
        }
        val transition = (1 until 24_000).first { frame ->
            pcm[(frame - 1) * 2] >= -0.125f && pcm[frame * 2] < -0.125f
        }
        assertTrue("The 3s source boundary drifted to $transition", transition in 11_998..12_002)
    }

    @Test
    fun independentSourceWindowPreservesThe2001stFrameAndRejectsPrematureEof() = runBlocking {
        val source = File(context.cacheDir, "native-decoder-window-tail.wv")
        app.stemwise.playback.PlaybackContentFixture.writeFloat32WavPack(context, source, 2001) {
            repeat(2001) { frame ->
                writeInt(Integer.reverseBytes((frame / 2048f).toRawBits()))
                writeInt(Integer.reverseBytes((-frame / 1024f).toRawBits()))
            }
        }
        val media = NativeMediaSource.Float32Audio(NativeMediaSource.LocalFile(source))
        val pcm = readNativeSourcePcmWindow(context, media, 1985L, 16, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        repeat(16) { frame ->
            assertEquals((1985 + frame) / 2048f, pcm[frame * 2], 0f)
            assertEquals(-(1985 + frame) / 1024f, pcm[frame * 2 + 1], 0f)
        }
        val failure = runCatching { readNativeSourcePcmWindow(context, media, 1985L, 17, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory) }.exceptionOrNull()
        assertTrue("A missing source frame cannot become padding: $failure",
            failure is IllegalStateException && failure.message == "SOURCE_WINDOW_PREMATURE_EOS")
        assertTrue(source.isFile)
    }

    @Test
    fun independentSourceWindowReadsExactWavPackDescriptorTail() = runBlocking {
        val source = NativeMediaSource.Float32Audio(
            NativeMediaSource.ContentUri(PlaybackTestMediaProvider.WAVPACK_SLICE_URI))
        assertEquals(48_017L, inspectNativeSourceFrameCount(context, source))
        val pcm = readNativeSourcePcmWindow(context, source, 48_000L, 17, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        assertEquals(34, pcm.size)
        pcm.forEachIndexed { offset, sample ->
            val index = 96_000 + offset
            val expectedBits = ((index * 2654435761L).toInt() and 0x7fffff) or
                ((112 + index % 20) shl 23) or ((index and 1) shl 31)
            assertEquals("WavPack tail sample $index", expectedBits, sample.toRawBits())
        }
    }

    @Test
    fun wavPackReaderRejectsTruncationAfterAdmissionAndHonorsCancellationBeforeDelivery() {
        val source = File.createTempFile("native-wavpack-reader-", ".wv", context.cacheDir)
        val encoded = instrumentation.context.assets.open("encoder/remaining-float32.wv").use { it.readBytes() }
        try {
            source.writeBytes(encoded)
            val facts = inspectPreparedSourceAudio(source)
            java.io.RandomAccessFile(source, "rw").use { input ->
                var cancelled = false
                WavPackReader(input.fd, 0L, input.length(), facts) {
                    if (cancelled) throw InterruptedException()
                }.use { reader ->
                    val buffer = ByteBuffer.allocateDirect(8)
                    cancelled = true
                    assertTrue(runCatching { reader.readFrames(0L, buffer, 1) }.exceptionOrNull() is InterruptedException)
                    cancelled = false
                    assertEquals(1, reader.readFrames(0L, buffer, 1))
                    assertEquals(0, reader.readFrames(facts.audibleFrames, buffer, 1))
                    input.setLength(32L)
                    val truncated = runCatching { reader.readFrames(facts.audibleFrames - 1L, buffer, 1) }.exceptionOrNull()
                    assertTrue("Expected a bounded WavPack read failure: $truncated", truncated is java.io.IOException &&
                        (truncated.message?.contains("seek failed") == true ||
                            truncated.message?.contains("truncated or corrupt") == true))
                }
            }
        } finally { check(source.delete()) }
    }

    @Test
    fun cancellingIndependentSourceWindowJoinsItsBlockedDescriptorOpen() = runBlocking {
        fun providerIsOpen() = context.contentResolver.query(
            PlaybackTestMediaProvider.BLOCKING_MEDIA_URI,
            arrayOf(PlaybackTestMediaProvider.BLOCKING_OPEN_STATUS), null, null, null
        )?.use { it.moveToFirst() && it.getInt(0) == 1 } == true
        for (inspectOnly in listOf(false, true)) {
            val work = launch(Dispatchers.Default) {
                val source = NativeMediaSource.ContentUri(PlaybackTestMediaProvider.BLOCKING_MEDIA_URI)
                if (inspectOnly) inspectNativeSourceFrameCount(context, NativeMediaSource.Float32Audio(source))
                else readNativeSourcePcmWindow(context, source, 144_000L, 48_000, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
            }
            try {
                withTimeout(5_000L) { while (!providerIsOpen()) delay(10L) }
                withTimeout(5_000L) { work.cancelAndJoin() }
                assertFalse("Returning cancellation must retire the owned provider open", providerIsOpen())
            } finally {
                withTimeout(5_000L) { work.cancelAndJoin() }
            }
        }
    }

    @Test
    fun fortySecondPcm16FlacPreservesEveryFrameThroughPartialOffers() = runBlocking {
        val wave = File(context.cacheDir, "native-decoder-window-long-input.wav")
        val source = File(context.cacheDir, "native-decoder-window-long.flac")
        val expectedFrames = 40 * 48_000
        writeWaveHeader(wave, 48_000, expectedFrames, 2, bitsPerSample = 16, formatCode = 1) {
            repeat(expectedFrames) { frame ->
                val left = (frame % 2048 - 1024) * 7
                writeShort(java.lang.Short.reverseBytes(left.toShort()).toInt())
                writeShort(java.lang.Short.reverseBytes((-left + 3).toShort()).toInt())
            }
        }
        YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).encodeLosslessPart(wave, source, 16L * 1024 * 1024, 2)
        val facts = inspectPreparedSourceAudio(source)
        assertEquals(16, facts.bitsPerSample)
        assertEquals(expectedFrames.toLong(), facts.audibleFrames)
        val probe = DecoderProbe()
        var acceptedFrames = 0L
        val mismatch = AtomicReference<String?>(null)
        val decoder = decoder("long-pcm16-flac", "original", NativeMediaSource.LocalFile(source),
            NativePcmSink { block ->
                if (block.firstTimelineFrame != acceptedFrames ||
                    block.format != NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)) {
                    mismatch.compareAndSet(null, "Unexpected block at $acceptedFrames: ${block.firstTimelineFrame}, ${block.format}")
                }
                val count = minOf(257, block.frameCount)
                val samples = block.data.duplicate().order(block.format.byteOrder).asFloatBuffer()
                repeat(count) { offset ->
                    val frame = acceptedFrames + offset
                    val left = ((frame % 2048 - 1024) * 7).toInt()
                    val actualLeft = samples.get()
                    val actualRight = samples.get()
                    if (actualLeft != left / 32768f || actualRight != (-left + 3) / 32768f) {
                        mismatch.compareAndSet(null, "PCM16 FLAC content mismatch at frame $frame")
                    }
                }
                acceptedFrames += count
                NativePcmSinkResult.accepting(count * block.format.bytesPerFrame)
            }, probe, NativeDecoderWorkerPool.shared)
        try {
            assertTrue(decoder.start())
            assertEquals(0L, probe.requireEos(decoder).generation)
            assertTrue(probe.errors.isEmpty())
            assertNull(mismatch.get())
            assertEquals("Grouped FLAC packets must not lose their later frames: ${decoder.diagnostics()}",
                expectedFrames.toLong(), acceptedFrames)
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun native24BitFlacPreservesLowBitsAndExactSeekFrames() = runBlocking {
        val source = File(context.cacheDir, "native-decoder-24bit.flac")
        instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
            source.outputStream().use(input::copyTo)
        }
        requireNotNull(context.contentResolver.openAssetFileDescriptor(PlaybackTestMediaProvider.FLAC_SLICE_URI, "r"))
            .use { descriptor ->
                assertEquals(PlaybackTestMediaProvider.SLICE_OFFSET.toLong(), descriptor.startOffset)
                assertEquals(source.length(), descriptor.declaredLength)
            }
        listOf(NativeMediaSource.LocalFile(source),
            NativeMediaSource.ContentUri(PlaybackTestMediaProvider.FLAC_SLICE_URI)).forEach { mediaSource ->
            val accepted = ConcurrentHashMap<Long, Long>()
            val firstFrames = ConcurrentHashMap<Long, Long>()
            val mismatch = AtomicReference<String?>(null)
            val probe = DecoderProbe()
            val sink = NativePcmSink { block ->
                val frames = min(257, block.frameCount)
                firstFrames.putIfAbsent(block.seekGeneration, block.firstTimelineFrame)
                if (block.format != NativePcmFormat(96_000, 2, NativePcmEncoding.PCM_FLOAT)) {
                    mismatch.compareAndSet(null, "24-bit FLAC was decoded as ${block.format}")
                } else {
                    val samples = block.data.duplicate().order(block.format.byteOrder).asFloatBuffer()
                    repeat(frames) { offset ->
                        val frame = block.firstTimelineFrame + offset
                        val left = ((frame % 2048L) - 1024L).toInt() * 257 + 7
                        val expected = floatArrayOf(left / 8388608f, (-left + 3) / 8388608f)
                        expected.forEach { value ->
                            val actual = samples.get()
                            if (actual != value) mismatch.compareAndSet(null, "Lost sample precision at frame $frame: $actual != $value")
                        }
                    }
                }
                accepted.merge(block.seekGeneration, frames.toLong(), Long::plus)
                NativePcmSinkResult.accepting(frames * block.format.bytesPerFrame, 0L)
            }
            val decoder = decoder("native-flac-original", "original", mediaSource,
                sink, probe, NativeDecoderWorkerPool.shared)
            try {
                assertTrue(decoder.start())
                assertEquals(0L, probe.requireEos(decoder).generation)
                assertNull("${probe.prepared.get()?.track}; ${decoder.diagnostics()}", mismatch.get())
                assertEquals(96_000L, accepted[0L])
                assertEquals(0L, firstFrames[0L])
                val generation = decoder.seekToUs(125_000L)
                assertEquals(generation, probe.requireEos(decoder).generation)
                assertNull(mismatch.get())
                assertEquals(12_000L, firstFrames[generation])
                assertEquals(84_000L, accepted[generation])
                assertTrue(probe.errors.isEmpty())
            } finally {
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun allAdmittedCanonicalFlacPumpsAndRemainingDescriptorRunTogetherAndSeekAfterEos() = runBlocking {
        val input = File(context.cacheDir, "native-decoder-selected-input.flac")
        val selected = File(context.cacheDir, "native-decoder-selected.flac")
        val decoders = mutableListOf<MediaCodecTrackDecoder>()
        try {
            instrumentation.context.assets.open("encoder/flac16-stereo-44100.flac").use { source ->
                input.outputStream().use(source::copyTo)
            }
            YoutubeDlEngine(context, (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as app.stemwise.StemwiseApplication).networkAccess, null).encodeLosslessPart(input, selected, 16L * 1024 * 1024, 2)
            val selectedFacts = inspectPreparedSourceAudio(selected)
            assertFalse(selectedFacts.isFloat)
            assertEquals(48_000, selectedFacts.sampleRate)
            assertEquals(2, selectedFacts.channels)
            assertEquals(16, selectedFacts.bitsPerSample)
            assertEquals(48_000L, selectedFacts.audibleFrames)
            val remainingBytes = instrumentation.context.assets.open("encoder/remaining-float32.wv").use { it.readBytes() }
            requireNotNull(context.contentResolver.openAssetFileDescriptor(
                PlaybackTestMediaProvider.WAVPACK_SLICE_URI, "r"
            )).use { descriptor ->
                assertEquals(PlaybackTestMediaProvider.SLICE_OFFSET.toLong(), descriptor.startOffset)
                assertEquals(remainingBytes.size.toLong(), descriptor.declaredLength)
            }
            val pool = NativeDecoderWorkerPool.shared
            val decoderCount = NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS
            val remainingIndex = decoderCount - 1
            val reachedFirstPcm = CountDownLatch(decoderCount)
            val writable = AtomicBoolean(false)
            val sinks = List(decoderCount) { RecordingSink(maximumFramesPerOffer = 256, backpressureOnce = false) }
            val probes = List(decoderCount) { DecoderProbe() }
            repeat(decoderCount) { index ->
                val firstOffer = AtomicBoolean(false)
                val sink = NativePcmSink { block ->
                    if (!writable.get()) {
                        if (firstOffer.compareAndSet(false, true)) reachedFirstPcm.countDown()
                        NativePcmSinkResult.backpressuredUntilSignal()
                    } else {
                        sinks[index].offer(block)
                    }
                }
                decoders += decoder(
                    id = "selected-source-$index",
                    lane = if (index == remainingIndex) "remaining" else "part-${index + 1}",
                    source = if (index == remainingIndex) {
                        NativeMediaSource.Float32Audio(
                            NativeMediaSource.ContentUri(PlaybackTestMediaProvider.WAVPACK_SLICE_URI)
                        )
                    } else {
                        NativeMediaSource.LocalFile(selected)
                    },
                    sink = sink,
                    probe = probes[index],
                    pool = pool
                )
            }
            decoders.forEach { assertTrue(it.start()) }
            val allPumpsReachedPcm = reachedFirstPcm.await(10L, TimeUnit.SECONDS)
            assertTrue(
                "All $decoderCount admitted pumps must reach PCM before any is released; " +
                    "pool=${pool.diagnostics()}, decoders=${decoders.map { it.diagnostics() }}, " +
                    "errors=${probes.map { it.errors.peek() }}",
                allPumpsReachedPcm
            )
            assertTrue("Persistent pumps cannot run serially", pool.diagnostics().runningTaskCount >= decoderCount)
            writable.set(true)
            decoders.forEach(MediaCodecTrackDecoder::signalSinkWritable)
            decoders.forEachIndexed { index, decoder ->
                assertEquals(0L, probes[index].requireEos(decoder).generation)
                assertEquals("Exact PCM frame count for lane $index",
                    if (index == remainingIndex) 48_017L else 48_000L, sinks[index].stats(0L).acceptedFrames)
                assertEquals(1, probes[index].preparedCount.get())
                assertEquals(48_000, requireNotNull(probes[index].formats.lastOrNull()).sampleRateHz)
                assertEquals(2, requireNotNull(probes[index].formats.lastOrNull()).channelCount)
                assertFalse(probes[index].callbackOnMain.get())
                assertFalse(sinks[index].callbackOnMain.get())
                assertFalse(sinks[index].nonNativeByteOrder.get())
                assertNull(probes[index].errors.poll())
                if (index < remainingIndex) {
                    assertEquals(sinks[0].stats(0L).pcmCrc32, sinks[index].stats(0L).pcmCrc32)
                }
            }
            val remainingDecoder = decoders.last()
            assertEquals("native-wavpack-float32", remainingDecoder.diagnostics().codecName)
            assertEquals(NativePcmEncoding.PCM_FLOAT, requireNotNull(probes.last().formats.lastOrNull()).encoding)
            fun assertRemainingSuffix(generation: Long, firstFrame: Int) {
                val stats = sinks.last().stats(generation)
                assertEquals(48_017L - firstFrame, stats.acceptedFrames)
                assertEquals(framesToMicroseconds(firstFrame.toLong(), 48_000), stats.firstPtsUs)
                assertTrue("Partial Float32 offers must preserve their clock", stats.maximumBoundaryErrorUs <= 1L)
            }
            assertRemainingSuffix(0L, 0)
            val seekGeneration = remainingDecoder.seekToUs(125_000L)
            assertEquals(seekGeneration, probes.last().requireEos(remainingDecoder).generation)
            assertRemainingSuffix(seekGeneration, 6_000)
            assertEquals(1, probes.last().preparedCount.get())
            assertNull(probes.last().errors.poll())
        } finally {
            decoders.forEach(MediaCodecTrackDecoder::close)
            decoders.forEach { assertTrue(it.awaitClosed(5L, TimeUnit.SECONDS)) }
            context.contentResolver.delete(PlaybackTestMediaProvider.WAVPACK_SLICE_URI, null, null)
        }
    }

    @Test
    fun localWavSupportsPartialBackpressureAndRepeatedSeekAfterEosWithoutRebuild() {
        val source = File(context.cacheDir, "native-decoder-loop.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 360L, channels = 1)
        val waveBytes = source.readBytes()
        fun assertPcmSuffix(stats: GenerationStats, targetUs: Long) {
            val firstFrame = microsecondsToFramesCeil(targetUs, 48_000)
            val byteOffset = 44 + firstFrame.toInt() * Short.SIZE_BYTES
            val expected = CRC32().apply {
                update(waveBytes, byteOffset, waveBytes.size - byteOffset)
            }.value
            assertEquals(17_280L - firstFrame, stats.acceptedFrames)
            assertEquals("accepted PCM suffix differs after seek to $targetUs us", expected, stats.pcmCrc32)
        }
        val pool = NativeDecoderWorkerPool.dedicated("DecoderLoopTest")
        val sink = RecordingSink(maximumFramesPerOffer = 127, backpressureOnce = true)
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "loop-decoder",
            lane = "original",
            source = NativeMediaSource.LocalFile(source),
            sink = sink,
            probe = probe,
            pool = pool
        )

        try {
            assertTrue(decoder.start())
            val firstEos = probe.requireEos(decoder)
            assertEquals(0L, firstEos.generation)
            assertNotNull(firstEos.finalPresentationTimeUs)
            assertEquals(NativeDecoderState.ENDED, decoder.diagnostics().state)
            assertEquals(17_280L, sink.stats(0L).acceptedFrames)
            assertPcmSuffix(sink.stats(0L), 0L)
            val originalCodecName = decoder.diagnostics().codecName

            listOf(120_123L, 40_000L, 210_777L, 80_000L).forEach { targetUs ->
                val seekGeneration = decoder.seekToUs(targetUs)
                val eos = probe.requireEos(decoder)
                assertEquals(seekGeneration, eos.generation)
                val stats = sink.stats(seekGeneration)
                assertPcmSuffix(stats, targetUs)
                assertTimestampStartsAtRequestedFrame(stats, targetUs, 48_000)
                assertTrue(
                    "partial offers at $targetUs us drifted ${stats.maximumBoundaryErrorUs} us; " +
                        "diagnostics=${decoder.diagnostics()}",
                    stats.maximumBoundaryErrorUs <= 1L
                )
                assertEquals(originalCodecName, decoder.diagnostics().codecName)
            }

            val emptyGeneration = decoder.seekToUs(460_000L)
            val emptyEos = probe.requireEos(decoder)
            assertEquals(emptyGeneration, emptyEos.generation)
            assertNull("empty generation must not reuse the old final PTS", emptyEos.finalPresentationTimeUs)
            assertEquals(0L, sink.stats(emptyGeneration).acceptedFrames)

            val resumedGeneration = decoder.seekToUs(0L)
            assertEquals(resumedGeneration, probe.requireEos(decoder).generation)
            assertEquals(17_280L, sink.stats(resumedGeneration).acceptedFrames)
            assertPcmSuffix(sink.stats(resumedGeneration), 0L)
            assertEquals(1, probe.preparedCount.get())
            assertFalse(probe.callbackOnMain.get())
            assertFalse(sink.callbackOnMain.get())
            assertFalse(sink.nonNativeByteOrder.get())
            assertTrue(decoder.diagnostics().sinkRetryCount >= 6L)
            assertTrue("the partial-delivery test must exercise many sink calls", sink.offerCount > 100)
            assertEquals(
                "the decoder must reuse one NativePcmBlock instead of allocating per offer",
                1,
                sink.blockIdentityCount
            )
            assertEquals(
                "the decoder must reuse its read-only delivery window after warmup",
                1,
                sink.dataWindowIdentityCount
            )
            assertNull(probe.errors.poll())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            decoder.close()
            pool.close()
        }
    }

    @Test
    fun pausedSinkHasNoWallClockFailureAndSeekGenerationUnparksIt() {
        val source = File(context.cacheDir, "native-decoder-signal-pause.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 240L, channels = 1)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderSignalPauseTest")
        val sink = SeekWokenSink()
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "paused-decoder",
            lane = "prewarm-original",
            source = NativeMediaSource.LocalFile(source),
            sink = sink,
            probe = probe,
            pool = pool
        )

        try {
            assertTrue(decoder.start())
            assertTrue(sink.firstGenerationParked.await(5L, TimeUnit.SECONDS))
            // A seek is also a wake signal. Generation zero remains unconsumed, but that is
            // cancellation—not a timeout or sink failure—regardless of how long it was paused.
            val seekGeneration = decoder.seekToUs(100_000L)
            assertEquals(seekGeneration, probe.requireEos(decoder).generation)
            assertTrue(sink.acceptedFramesForNewGeneration > 0L)
            assertTrue(decoder.diagnostics().sinkSignalParkCount >= 1L)
            assertNull(probe.errors.poll())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun explicitSignalCancelAndClosePromptlyWakeAParkedSink() {
        val source = File(context.cacheDir, "native-decoder-all-park-wakes.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 180L, channels = 1)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderAllParkWakes")
        val decodersToClose = mutableListOf<MediaCodecTrackDecoder>()

        fun newParkedDecoder(suffix: String): Triple<MediaCodecTrackDecoder, ManualSignalSink, DecoderProbe> {
            val sink = ManualSignalSink()
            val probe = DecoderProbe()
            val decoder = decoder(
                id = "parked-$suffix",
                lane = "original",
                source = NativeMediaSource.LocalFile(source),
                sink = sink,
                probe = probe,
                pool = pool
            )
            // Register ownership before any assertion can throw. Otherwise a failed start/park
            // setup leaves this local decoder unreachable and its worker alive until process exit.
            decodersToClose += decoder
            assertTrue(decoder.start())
            assertTrue(sink.parked.await(5L, TimeUnit.SECONDS))
            return Triple(decoder, sink, probe)
        }

        try {
            newParkedDecoder("signal").let { (decoder, sink, probe) ->
                sink.writable.set(true)
                decoder.signalSinkWritable()
                assertEquals(0L, probe.requireEos(decoder).generation)
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            }
            newParkedDecoder("cancel").let { (decoder, _, probe) ->
                val cancelGeneration = decoder.cancel()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                assertEquals(cancelGeneration, probe.cancelled.poll(5L, TimeUnit.SECONDS))
                assertEquals(NativeDecoderState.CANCELLED, decoder.diagnostics().state)
            }
            newParkedDecoder("close").let { (decoder, _, _) ->
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                assertEquals(NativeDecoderState.CLOSED, decoder.diagnostics().state)
            }
        } finally {
            try {
                decodersToClose.forEach(MediaCodecTrackDecoder::close)
                decodersToClose.forEach { decoder ->
                    decoder.awaitClosed(5L, TimeUnit.SECONDS)
                }
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun cancelAfterExecutorEntryCannotReportJoinedBeforeWorkerTeardown() {
        val source = File(context.cacheDir, "native-decoder-started-cancel-race.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 180L, channels = 1)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderStartedCancelRace")
        val wrapperEntered = CountDownLatch(1)
        val releaseWrapper = CountDownLatch(1)
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "started-cancel-race",
            lane = "original",
            source = NativeMediaSource.LocalFile(source),
            sink = NativePcmSink { NativePcmSinkResult.all(it) },
            probe = probe,
            pool = pool,
            workerStartedHook = {
                wrapperEntered.countDown()
                releaseWrapper.await()
            }
        )

        try {
            assertTrue(decoder.start())
            assertTrue(wrapperEntered.await(5L, TimeUnit.SECONDS))
            assertEquals(1, pool.diagnostics().runningTaskCount)

            val cancellationGeneration = decoder.cancel()
            assertFalse(
                "A running executor wrapper must not be reported joined before it can tear down",
                decoder.awaitClosed(100L, TimeUnit.MILLISECONDS)
            )
            assertEquals(1, pool.diagnostics().runningTaskCount)

            releaseWrapper.countDown()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            assertEquals(
                cancellationGeneration,
                probe.cancelled.poll(5L, TimeUnit.SECONDS)
            )
            assertEquals(NativeDecoderState.CANCELLED, decoder.diagnostics().state)
            assertEquals(0, probe.preparedCount.get())
            assertNull(probe.errors.poll())
            awaitPoolIdle(pool)
        } finally {
            releaseWrapper.countDown()
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun cancelWhileExecutorTaskIsQueuedJoinsWithoutOpeningTheSource() {
        val unavailableSource = File(context.cacheDir, "must-not-open-before-worker-entry.wav")
        unavailableSource.delete()
        val pool = NativeDecoderWorkerPool.dedicated(
            name = "DecoderQueuedCancelRace",
            workerCount = 1,
            queueCapacity = 1
        )
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val blocker = pool.submit(Runnable {
            blockerEntered.countDown()
            check(releaseBlocker.await(5L, TimeUnit.SECONDS)) {
                "The deterministic pool blocker was not released"
            }
        })
        val workerHookEntered = AtomicBoolean(false)
        val probe = DecoderProbe()
        var replacement: MediaCodecTrackDecoder? = null
        val decoder = decoder(
            id = "queued-cancel-race",
            lane = "prewarm-original",
            source = NativeMediaSource.LocalFile(unavailableSource),
            sink = NativePcmSink { NativePcmSinkResult.all(it) },
            probe = probe,
            pool = pool,
            workerStartedHook = { workerHookEntered.set(true) }
        )

        try {
            assertTrue(blockerEntered.await(5L, TimeUnit.SECONDS))
            assertTrue(decoder.start())
            assertEquals(1, pool.diagnostics().queuedTaskCount)

            val cancellationGeneration = decoder.cancel()
            assertTrue(
                "Cancellation before wrapper entry must be synchronously joinable",
                decoder.awaitClosed(100L, TimeUnit.MILLISECONDS)
            )
            assertEquals(
                cancellationGeneration,
                probe.cancelled.poll(5L, TimeUnit.SECONDS)
            )
            assertEquals(NativeDecoderState.CANCELLED, decoder.diagnostics().state)
            assertFalse(workerHookEntered.get())
            assertEquals(0, probe.preparedCount.get())
            assertEquals(0L, decoder.diagnostics().inputBufferCount)
            assertEquals(0L, decoder.diagnostics().outputBufferCount)
            assertNull(probe.errors.poll())
            assertEquals(
                "A synchronously joined queued decoder must release the pool slot immediately",
                0,
                pool.diagnostics().queuedTaskCount
            )

            val replacementDecoder = decoder(
                id = "queued-cancel-replacement",
                lane = "prewarm-original",
                source = NativeMediaSource.LocalFile(unavailableSource),
                sink = NativePcmSink { NativePcmSinkResult.all(it) },
                probe = DecoderProbe(),
                pool = pool
            )
            replacement = replacementDecoder
            assertTrue(
                "The next exact decoder must not be rejected by a cancelled queue entry",
                replacementDecoder.start()
            )
            assertEquals(1, pool.diagnostics().queuedTaskCount)
            replacementDecoder.cancel()
            assertTrue(replacementDecoder.awaitClosed(100L, TimeUnit.MILLISECONDS))
            assertEquals(0, pool.diagnostics().queuedTaskCount)

            releaseBlocker.countDown()
            blocker.get(5L, TimeUnit.SECONDS)
            awaitPoolIdle(pool)
            assertFalse(workerHookEntered.get())
            assertNull(probe.errors.poll())
        } finally {
            releaseBlocker.countDown()
            replacement?.close()
            replacement?.let { assertTrue(it.awaitClosed(5L, TimeUnit.SECONDS)) }
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun cancelOwnsAndCancelsABlockedContentProviderOpen() {
        val pool = NativeDecoderWorkerPool.dedicated("DecoderBlockedProviderOpen")
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "blocked-provider-open",
            lane = "original",
            source = NativeMediaSource.ContentUri(PlaybackTestMediaProvider.BLOCKING_MEDIA_URI),
            sink = NativePcmSink { NativePcmSinkResult.all(it) },
            probe = probe,
            pool = pool
        )

        try {
            assertTrue(decoder.start())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            var providerEntered = false
            while (!providerEntered && System.nanoTime() < deadline) {
                providerEntered = context.contentResolver.query(
                    PlaybackTestMediaProvider.BLOCKING_MEDIA_URI,
                    arrayOf(PlaybackTestMediaProvider.BLOCKING_OPEN_STATUS),
                    null,
                    null,
                    null
                )?.use { it.moveToFirst() && it.getInt(0) == 1 } == true
                if (!providerEntered) Thread.sleep(10L)
            }
            assertTrue("the test provider never entered its cancellable open", providerEntered)

            val cancellationGeneration = decoder.cancel()
            assertTrue(decoder.awaitClosed(2L, TimeUnit.SECONDS))
            assertEquals(
                cancellationGeneration,
                probe.cancelled.poll(2L, TimeUnit.SECONDS)
            )
            assertEquals(NativeDecoderState.CANCELLED, decoder.diagnostics().state)
            assertNull(probe.errors.poll())
            awaitPoolIdle(pool)
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun caughtSinkFailurePublishesOneBoundedTerminalCause() {
        val source = File(context.cacheDir, "native-decoder-caught-failure.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 180L, channels = 1)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderCaughtFailure")
        val probe = DecoderProbe()
        val decoder = decoder(id = "caught-decoder", lane = "remaining",
            source = NativeMediaSource.LocalFile(source),
            sink = NativePcmSink { throw java.io.IOException("isolated vendor sink failure") },
            probe = probe, pool = pool)
        try {
            assertTrue(decoder.start())
            val failure = requireNotNull(probe.errors.poll(5L, TimeUnit.SECONDS))
            assertEquals(NativeDecoderErrorCode.SINK_FAILED, failure.code)
            assertEquals("caught-decoder", failure.decoderId)
            assertEquals("remaining", failure.laneId)
            assertTrue(requireNotNull(failure.causeDiagnostic).contains("java.io.IOException: isolated vendor sink failure"))
            assertTrue(failure.causeDiagnostic!!.length <= 4096)
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            assertNull("One terminal fault emitted repeatedly", probe.errors.poll())
            assertTrue(requireNotNull(decoder.diagnostics().lastErrorMessage).contains("isolated vendor sink failure"))
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
            source.delete()
        }
    }

    @Test
    fun explicitPermanentSinkFailureTerminatesWithoutAClockDeadline() {
        val source = File(context.cacheDir, "native-decoder-explicit-stall.wav")
        writePcm16Wave(source, sampleRateHz = 48_000, durationMs = 180L, channels = 1)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderExplicitStall")
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "explicit-stall-decoder",
            lane = "prewarm-vocals",
            source = NativeMediaSource.LocalFile(source),
            sink = NativePcmSink { NativePcmSinkResult.permanentlyStalled() },
            probe = probe,
            pool = pool
        )

        try {
            assertTrue(decoder.start())
            val failure = probe.errors.poll(5L, TimeUnit.SECONDS)
            assertNotNull("an explicit sink failure must terminate immediately", failure)
            requireNotNull(failure)
            assertEquals(NativeDecoderErrorCode.SINK_STALLED, failure.code)
            assertEquals("explicit-stall-decoder", failure.decoderId)
            assertEquals("prewarm-vocals", failure.laneId)
            assertFalse(requireNotNull(failure.message).contains(source.absolutePath))
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            assertEquals(NativeDecoderState.FAILED, decoder.diagnostics().state)
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
        }
    }

    @Test
    fun contentUriPcm16WavProducesExplicitPcmFormats() {
        val sourceFile = File(context.cacheDir, "native-decoder-content.wav")
        writePcm16Wave(sourceFile, sampleRateHz = 48_000, durationMs = 180L, channels = 2)
        val contentUri = publishToMediaStore(sourceFile, OWNED_MEDIA_DISPLAY_NAME)
        val pool = NativeDecoderWorkerPool.dedicated("DecoderContentTest")
        val sink = RecordingSink(maximumFramesPerOffer = 256, backpressureOnce = false)
        val probe = DecoderProbe()
        val decoder = decoder(
            id = "content-decoder",
            lane = "stem-vocals",
            source = NativeMediaSource.ContentUri(contentUri),
            sink = sink,
            probe = probe,
            pool = pool
        )

        try {
            assertTrue(decoder.start())
            assertEquals(0L, probe.requireEos(decoder).generation)
            val output = probe.formats.last()
            assertEquals(48_000, output.sampleRateHz)
            assertEquals(2, output.channelCount)
            assertTrue(
                output.encoding == NativePcmEncoding.PCM_FLOAT ||
                    output.encoding == NativePcmEncoding.PCM_16_BIT
            )
            assertEquals(
                output.channelCount * output.encoding.bytesPerSample,
                output.bytesPerFrame
            )
            assertEquals(8_640L, sink.stats(0L).acceptedFrames)
            assertEquals("content://media/<redacted>", decoder.diagnostics().source)
            assertNull(probe.errors.poll())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
            deleteOwnedContentUri(contentUri)
        }
    }

    @Test
    fun demoOpusResidentSeeksPreserveTheExactAudibleEndpoint() {
        val fixture = CurrentSourcePublicationFixture.load(context, "DEMOUI00001")
        assertEquals(576_000L, fixture.manifest.audibleFrames)
        for (configuredPrerollUs in listOf(250_000L, 0L)) {
            val file = File(context.cacheDir, "native-decoder-demo-resident-seek.opus").apply { writeBytes(fixture.original) }
            val pool = NativeDecoderWorkerPool.dedicated("DemoOpusResidentSeek")
            val recording = RecordingSink(maximumFramesPerOffer = Int.MAX_VALUE, backpressureOnce = false)
            val reference = AtomicReference<ByteArray?>(null)
            val referenceFormat = AtomicReference<NativePcmFormat?>(null)
            val sink = NativePcmSink { block ->
                val accepted = recording.offer(block)
                if (block.seekGeneration == 0L && accepted.acceptedBytes > 0) {
                    if (reference.get() == null) {
                        reference.set(ByteArray(Math.multiplyExact(576_000, block.format.bytesPerFrame)))
                        referenceFormat.set(block.format)
                    }
                    val bytes = requireNotNull(reference.get())
                    val start = Math.toIntExact(Math.multiplyExact(block.firstTimelineFrame, block.format.bytesPerFrame.toLong()))
                    require(start >= 0 && start + accepted.acceptedBytes <= bytes.size)
                    for (index in 0 until accepted.acceptedBytes) bytes[start + index] = block.data.get(block.data.position() + index)
                }
                accepted
            }
            val probe = DecoderProbe()
            val decoder = MediaCodecTrackDecoder(
                context = context,
                decoderId = "demo-resident-seek",
                laneId = "original",
                source = NativeMediaSource.LocalFile(file),
                sink = sink,
                listener = probe,
                config = MediaCodecTrackDecoderConfig(seekPrerollUs = configuredPrerollUs),
                workerPool = pool,
                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory
            )
            try {
                assertTrue(decoder.start())
                fun assertEndpoint(generation: Long, targetUs: Long,
                    observedDecoder: MediaCodecTrackDecoder = decoder,
                    observedSink: RecordingSink = recording,
                    observedProbe: DecoderProbe = probe) {
                    assertEquals(generation, observedProbe.requireEos(observedDecoder).generation)
                    val stats = observedSink.stats(generation)
                    val format = requireNotNull(referenceFormat.get())
                    val first = microsecondsToFramesCeil(targetUs, format.sampleRateHz)
                    val expectedCrc = CRC32().apply {
                        val bytes = requireNotNull(reference.get())
                        val offset = Math.toIntExact(first * format.bytesPerFrame)
                        update(bytes, offset, bytes.size - offset)
                    }.value
                    val message = "configuredPrerollUs=$configuredPrerollUs seekUs=$targetUs generation=$generation first=${stats.firstSourceFrame}; " +
                        "accepted=${stats.acceptedFrames} end=${stats.acceptedSourceFrameExclusive}; " +
                        "initialSuffixCrc=$expectedCrc actualSuffixCrc=${stats.pcmCrc32}; " +
                        "prepared=${observedProbe.prepared.get()}; diagnostics=${observedDecoder.diagnostics()}"
                    assertEquals(message, 48_000, format.sampleRateHz)
                    assertEquals(message, 2, format.channelCount)
                    assertEquals(message, first, stats.firstSourceFrame)
                    assertEquals(message, 576_000L, stats.acceptedSourceFrameExclusive)
                    assertEquals(message, 576_000L - first, stats.acceptedFrames)
                    assertEquals(message, 0L, stats.maximumSourceBoundaryErrorFrames)
                    assertEquals("Sought PCM must match the independently retained initial decode; $message",
                        expectedCrc, stats.pcmCrc32)
                    assertNull(message, observedProbe.errors.peek())
                }
                assertEndpoint(0L, 0L)
                for (targetUs in listOf(1_500_000L, 4_000_000L, 1_500_000L, 0L)) {
                    assertEndpoint(decoder.seekToUs(targetUs), targetUs)
                }
                assertEquals(1, probe.preparedCount.get())
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                // Early seek replaces the decoder before the frame-zero cache seals.
                for (targetUs in listOf(4_000_000L, 1_500_000L, 20_000L)) {
                    val coldSink = RecordingSink(maximumFramesPerOffer = 193, backpressureOnce = false)
                    val coldProbe = DecoderProbe()
                    val coldDecoder = MediaCodecTrackDecoder(context = context,
                        decoderId = "demo-cold-seek", laneId = "original",
                        source = NativeMediaSource.LocalFile(file), sink = coldSink,
                        listener = coldProbe, workerPool = pool,
                        config = MediaCodecTrackDecoderConfig(initialPositionUs = targetUs,
                            seekPrerollUs = configuredPrerollUs),
                                packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
                    try {
                        assertTrue(coldDecoder.start())
                        assertEndpoint(0L, targetUs, coldDecoder, coldSink, coldProbe)
                        assertEndpoint(coldDecoder.seekToUs(0L), 0L, coldDecoder, coldSink, coldProbe)
                    } finally {
                        coldDecoder.close()
                        assertTrue(coldDecoder.awaitClosed(5L, TimeUnit.SECONDS))
                    }
                }
            } finally {
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                pool.close()
                file.delete()
            }
        }
    }

    @Test
    fun estimatedVbrMp3DurationNeverCutsDecodedPcmAndTailRemainsSeekable() {
        // libmp3lame -q:a 4 -write_xing 0, 10 s / 48 kHz; FFmpeg independently decodes
        // 418 MPEG frames to 481,536 PCM frames. No Xing/VBRI delay or duration authority.
        val file = decodeFixture(CompressedFixture("decoder/vbr-no-xing.mp3.base64",
            "vbr-no-xing.mp3", 48_000, 481_536L, false))
        val pool = NativeDecoderWorkerPool.dedicated("VbrNoXing")
        val recorded = RecordingSink(193, false)
        val pcm = ConcurrentHashMap<Long, ByteArrayOutputStream>()
        val sink = NativePcmSink { block ->
            recorded.offer(block).also { accepted ->
                val bytes = ByteArray(accepted.acceptedBytes)
                block.data.duplicate().get(bytes)
                pcm.computeIfAbsent(block.seekGeneration) { ByteArrayOutputStream() }.write(bytes)
            }
        }
        val probe = DecoderProbe()
        val decoder = decoder("vbr-no-xing", "original", NativeMediaSource.LocalFile(file), sink, probe, pool)
        try {
            assertTrue(decoder.start())
            assertEquals(0L, probe.requireEos(decoder).generation)
            val track = requireNotNull(probe.prepared.get()).track
            assertEquals(0, track.encoderDelayFrames)
            assertEquals(0, track.encoderPaddingFrames)
            assertTrue("Fixture must expose an underestimate: $track", requireNotNull(track.durationUs) < 2_000_000L)
            fun assertWhole(generation: Long) {
                val actual = recorded.stats(generation)
                assertEquals(481_536L, actual.acceptedFrames)
                assertEquals(0L, actual.firstSourceFrame)
                assertEquals(481_536L, actual.acceptedSourceFrameExclusive)
                assertEquals(0L, actual.maximumSourceBoundaryErrorFrames)
            }
            assertWhole(0L)
            val repeat = decoder.seekToUs(0L)
            assertEquals(repeat, probe.requireEos(decoder).generation)
            assertWhole(repeat)
            val baseline = requireNotNull(pcm[0L]).toByteArray()
            assertArrayEquals(baseline, requireNotNull(pcm[repeat]).toByteArray())
            val tail = decoder.seekToUs(9_000_000L)
            assertEquals(tail, probe.requireEos(decoder).generation)
            val actual = recorded.stats(tail)
            assertEquals(432_000L, actual.firstSourceFrame)
            assertEquals(481_536L, actual.acceptedSourceFrameExclusive)
            assertEquals(49_536L, actual.acceptedFrames)
            assertEquals(0L, actual.maximumSourceBoundaryErrorFrames)
            assertArrayEquals("Tail PCM must match its exact sequential sample positions",
                baseline.copyOfRange(432_000 * probe.formats.last().bytesPerFrame, baseline.size),
                requireNotNull(pcm[tail]).toByteArray())
            assertEquals(0L, decoder.diagnostics().discardedPaddingFrames)
            assertNull(probe.errors.poll())
        } finally {
            decoder.close()
            assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
            pool.close()
            assertTrue(file.delete())
        }
    }

    @Test
    fun canonicalOpusAndImportedMp3HaveGaplessContinuousTimelines() {
        val fixtures = listOf(
            CompressedFixture(
                asset = "decoder/canonical-opus.ogg.base64",
                fileName = "canonical-opus.ogg",
                sampleRateHz = 48_000,
                expectedFrames = 16_800L,
                requiresGaplessEndpoint = true
            ),
            CompressedFixture(
                asset = "decoder/imported-mp3.mp3.base64",
                fileName = "imported-cbr-no-xing.mp3",
                sampleRateHz = 44_100,
                expectedFrames = 17_280L,
                requiresGaplessEndpoint = false
            )
        )

        fixtures.forEachIndexed { index, fixture ->
            val file = decodeFixture(fixture)
            val pool = NativeDecoderWorkerPool.dedicated("CompressedFixture$index")
            val sink = RecordingSink(maximumFramesPerOffer = 193, backpressureOnce = false)
            val probe = DecoderProbe()
            val decoder = decoder(
                id = "compressed-$index",
                lane = if (index == 0) "canonical-original" else "imported-original",
                source = NativeMediaSource.LocalFile(file),
                sink = sink,
                probe = probe,
                pool = pool
            )
            try {
                assertTrue(decoder.start())
                assertEquals(0L, probe.requireEos(decoder).generation)
                val initial = sink.stats(0L)
                assertEquals("${fixture.fileName} must start at audible zero", 0L, initial.firstPtsUs)
                assertEquals(
                    "track=${probe.prepared.get()?.track}, diagnostics=${decoder.diagnostics()}",
                    fixture.expectedFrames,
                    initial.acceptedFrames
                )
                assertTrue(
                    "${fixture.fileName} PCM boundaries drifted by ${initial.maximumBoundaryErrorUs} us",
                    initial.maximumBoundaryErrorUs <= 1L
                )
                assertEquals(fixture.sampleRateHz, probe.formats.last().sampleRateHz)
                if (fixture.requiresGaplessEndpoint) {
                    assertTrue(
                        "Fixture must exercise a real gapless endpoint",
                        requireNotNull(probe.prepared.get()).track.let {
                            it.encoderDelayFrames > 0 || it.encoderPaddingFrames > 0 ||
                                it.durationUs == 350_000L
                        }
                    )
                }

                val seekTargetUs = 100_123L
                val seekGeneration = decoder.seekToUs(seekTargetUs)
                assertEquals(seekGeneration, probe.requireEos(decoder).generation)
                assertTimestampStartsAtRequestedFrame(
                    sink.stats(seekGeneration),
                    seekTargetUs,
                    fixture.sampleRateHz
                )
                assertEquals(1, probe.preparedCount.get())
                assertNull(probe.errors.poll())
            } finally {
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                pool.close()
            }
        }
    }

    private fun decoder(
        id: String,
        lane: String,
        source: NativeMediaSource,
        sink: NativePcmSink,
        probe: DecoderProbe,
        pool: NativeDecoderWorkerPool,
        workerStartedHook: (() -> Unit)? = null
    ) = MediaCodecTrackDecoder(
        context = context,
        decoderId = id,
        laneId = lane,
        source = source,
        sink = sink,
        listener = probe,
        workerPool = pool,
        workerStartedHook = workerStartedHook,
        packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory
    )

    private fun awaitPoolIdle(pool: NativeDecoderWorkerPool) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        var diagnostics = pool.diagnostics()
        while ((diagnostics.runningTaskCount != 0 || diagnostics.queuedTaskCount != 0) &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.sleep(10L)
            diagnostics = pool.diagnostics()
        }
        assertEquals("worker pool still running tasks: $diagnostics", 0, diagnostics.runningTaskCount)
        assertEquals("worker pool still has queued tasks: $diagnostics", 0, diagnostics.queuedTaskCount)
    }

    private fun assertTimestampStartsAtRequestedFrame(
        stats: GenerationStats,
        requestedUs: Long,
        sampleRateHz: Int
    ) {
        val expectedUs = framesToMicroseconds(
            microsecondsToFramesCeil(requestedUs, sampleRateHz),
            sampleRateHz
        )
        assertEquals(expectedUs, stats.firstPtsUs)
    }

    private fun decodeFixture(fixture: CompressedFixture): File {
        val encoded = instrumentation.context.assets.open(fixture.asset)
            .bufferedReader()
            .use { it.readText() }
        return File(context.cacheDir, fixture.fileName).apply {
            writeBytes(Base64.getMimeDecoder().decode(encoded))
        }
    }

    private fun publishToMediaStore(file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, OWNED_MEDIA_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, values))
        ownedContentUris += uri
        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        return uri
    }

    private fun deleteOwnedContentUri(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
        val stillExists = context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            null,
            null,
            null
        )?.use { it.moveToFirst() } == true
        check(!stillExists) { "Unable to delete instrumentation MediaStore row $uri" }
        ownedContentUris.remove(uri)
    }

    private fun cleanupOwnedArtifacts() {
        val failures = mutableListOf<Throwable>()
        ownedContentUris.toList().forEach { uri ->
            runCatching { deleteOwnedContentUri(uri) }.exceptionOrNull()?.let(failures::add)
        }
        runCatching(::deleteStaleOwnedMediaRows).exceptionOrNull()?.let(failures::add)
        OWNED_CACHE_FILES.forEach { name ->
            runCatching {
                val file = File(context.cacheDir, name)
                check(!file.exists() || file.delete()) {
                    "Unable to delete decoder fixture ${file.absolutePath}"
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        listOf(PlaybackTestMediaProvider.FLAC_SLICE_URI,
            PlaybackTestMediaProvider.WAVPACK_SLICE_URI).forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }.exceptionOrNull()?.let(failures::add)
        }
        if (failures.isNotEmpty()) {
            val failure = AssertionError("Decoder instrumentation cleanup failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun deleteStaleOwnedMediaRows() {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val staleUris = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(OWNED_MEDIA_DISPLAY_NAME, "$OWNED_MEDIA_RELATIVE_PATH%"),
            null
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }
        }.orEmpty()
        staleUris.forEach { uri ->
            context.contentResolver.delete(uri, null, null)
            val stillExists = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
            check(!stillExists) { "Unable to delete stale decoder MediaStore row $uri" }
        }
    }

    private fun writePcm16Wave(
        file: File,
        sampleRateHz: Int,
        durationMs: Long,
        channels: Int
    ) {
        val frames = (durationMs * sampleRateHz / 1_000L).toInt()
        writeWaveHeader(file, sampleRateHz, frames, channels, bitsPerSample = 16, formatCode = 1) {
            repeat(frames) { frame ->
                val sample = (sin(2.0 * Math.PI * 330.0 * frame / sampleRateHz) * 4_000.0)
                    .toInt()
                    .toShort()
                repeat(channels) {
                    writeShort(java.lang.Short.reverseBytes(sample).toInt())
                }
            }
        }
    }

    private fun writeWaveHeader(
        file: File,
        sampleRateHz: Int,
        frames: Int,
        channels: Int,
        bitsPerSample: Int,
        formatCode: Int,
        writeSamples: DataOutputStream.() -> Unit
    ) {
        val bytesPerFrame = channels * (bitsPerSample / 8)
        val dataSize = frames * bytesPerFrame
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(formatCode.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(sampleRateHz))
            output.writeInt(Integer.reverseBytes(sampleRateHz * bytesPerFrame))
            output.writeShort(java.lang.Short.reverseBytes(bytesPerFrame.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            output.writeSamples()
        }
    }

    private data class CompressedFixture(
        val asset: String,
        val fileName: String,
        val sampleRateHz: Int,
        val expectedFrames: Long,
        val requiresGaplessEndpoint: Boolean
    )

    private companion object {
        val OWNED_CACHE_FILES = setOf(
            "native-decoder-window-stereo.wv",
            "native-decoder-window-mono.wav",
            "native-decoder-window-tail.wv",
            "native-decoder-window-long-input.wav",
            "native-decoder-window-long.flac",
            "native-decoder-loop.wav",
            "native-decoder-signal-pause.wav",
            "native-decoder-all-park-wakes.wav",
            "native-decoder-started-cancel-race.wav",
            "must-not-open-before-worker-entry.wav",
            "native-decoder-explicit-stall.wav",
            "native-decoder-content.wav",
            "native-decoder-24bit.flac",
            "native-decoder-selected-input.flac",
            "native-decoder-selected.flac",
            "canonical-opus.ogg",
            "imported-cbr-no-xing.mp3",
            "vbr-no-xing.mp3"
        )
        const val OWNED_MEDIA_DISPLAY_NAME = "native-decoder-content.wav"
        const val OWNED_MEDIA_RELATIVE_PATH = "Music/StemWiseDecoderTests"
    }
}

private class RecordingSink(
    private val maximumFramesPerOffer: Int,
    private val backpressureOnce: Boolean
) : NativePcmSink {
    private val generations = ConcurrentHashMap<Long, GenerationStats>()
    private val backpressured = ConcurrentHashMap.newKeySet<Long>()
    private val blockIdentities = ConcurrentHashMap.newKeySet<Int>()
    private val dataWindowIdentities = ConcurrentHashMap.newKeySet<Int>()
    val callbackOnMain = AtomicBoolean(false)
    val nonNativeByteOrder = AtomicBoolean(false)
    val offerCount: Int get() = offers.get()
    val blockIdentityCount: Int get() = blockIdentities.size
    val dataWindowIdentityCount: Int get() = dataWindowIdentities.size
    private val offers = AtomicInteger(0)

    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        if (Looper.myLooper() == Looper.getMainLooper()) callbackOnMain.set(true)
        if (block.data.order() != block.format.byteOrder) nonNativeByteOrder.set(true)
        offers.incrementAndGet()
        blockIdentities += System.identityHashCode(block)
        dataWindowIdentities += System.identityHashCode(block.data)
        if (backpressureOnce && backpressured.add(block.seekGeneration)) {
            return NativePcmSinkResult.accepting(0, retryDelayUs = 100L)
        }
        val acceptedFrames = min(maximumFramesPerOffer, block.frameCount)
        val acceptedBytes = acceptedFrames * block.format.bytesPerFrame
        generations.computeIfAbsent(block.seekGeneration) { GenerationStats() }
            .record(block, acceptedFrames)
        return NativePcmSinkResult.accepting(acceptedBytes, retryDelayUs = 100L)
    }

    fun stats(generation: Long): GenerationStats =
        generations.computeIfAbsent(generation) { GenerationStats() }
}

private class SeekWokenSink : NativePcmSink {
    val firstGenerationParked = CountDownLatch(1)
    @Volatile var acceptedFramesForNewGeneration = 0L

    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        if (block.seekGeneration == 0L) {
            firstGenerationParked.countDown()
            return NativePcmSinkResult.backpressuredUntilSignal()
        }
        acceptedFramesForNewGeneration += block.frameCount
        return NativePcmSinkResult.all(block)
    }
}

private class ManualSignalSink : NativePcmSink {
    val parked = CountDownLatch(1)
    val writable = AtomicBoolean(false)

    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        if (!writable.get()) {
            parked.countDown()
            return NativePcmSinkResult.backpressuredUntilSignal()
        }
        return NativePcmSinkResult.all(block)
    }
}

private class GenerationStats {
    private val acceptedPcm = CRC32()
    val pcmCrc32: Long get() = acceptedPcm.value
    var acceptedFrames: Long = 0L
        private set
    var firstPtsUs: Long? = null
        private set
    var maximumBoundaryErrorUs: Long = 0L
        private set
    var firstSourceFrame: Long? = null
        private set
    var acceptedSourceFrameExclusive: Long? = null
        private set
    var maximumSourceBoundaryErrorFrames: Long = 0L
        private set
    private var expectedNextPtsUs: Long? = null

    @Synchronized
    fun record(block: NativePcmBlock, acceptedFrameCount: Int) {
        if (firstSourceFrame == null) firstSourceFrame = block.firstTimelineFrame
        acceptedSourceFrameExclusive?.let { expected ->
            maximumSourceBoundaryErrorFrames = maxOf(maximumSourceBoundaryErrorFrames, abs(block.firstTimelineFrame - expected))
        }
        acceptedSourceFrameExclusive = Math.addExact(block.firstTimelineFrame, acceptedFrameCount.toLong())
        if (firstPtsUs == null) firstPtsUs = block.presentationTimeUs
        expectedNextPtsUs?.let { expected ->
            maximumBoundaryErrorUs = maxOf(
                maximumBoundaryErrorUs,
                abs(block.presentationTimeUs - expected)
            )
        }
        acceptedFrames += acceptedFrameCount
        expectedNextPtsUs = block.presentationTimeUs + framesToMicroseconds(
            acceptedFrameCount.toLong(),
            block.format.sampleRateHz
        )
        // Hash only accepted bytes, without moving the decoder-owned view's position.
        val start = block.data.position()
        val end = start + acceptedFrameCount * block.format.bytesPerFrame
        for (index in start until end) acceptedPcm.update(block.data.get(index).toInt())
    }
}

private data class EosEvent(
    val generation: Long,
    val finalPresentationTimeUs: Long?
)

private class DecoderProbe : NativeMediaCodecDecoderListener {
    val preparedCount = AtomicInteger(0)
    val prepared = AtomicReference<NativeDecoderPrepared?>(null)
    val formats = CopyOnWriteArrayList<NativePcmFormat>()
    val errors = LinkedBlockingQueue<NativeDecoderFailure>()
    val cancelled = LinkedBlockingQueue<Long>()
    val callbackOnMain = AtomicBoolean(false)
    private val eos = LinkedBlockingQueue<EosEvent>()

    override fun onPrepared(prepared: NativeDecoderPrepared) {
        noteThread()
        preparedCount.incrementAndGet()
        this.prepared.set(prepared)
    }

    override fun onOutputFormatChanged(format: NativePcmFormat, seekGeneration: Long) {
        noteThread()
        formats += format
    }

    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
        noteThread()
        eos.put(EosEvent(seekGeneration, finalPresentationTimeUs))
    }

    override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
        noteThread()
        errors.put(failure)
    }

    override fun onCancelled(seekGeneration: Long) {
        noteThread()
        cancelled.put(seekGeneration)
    }

    fun requireEos(decoder: MediaCodecTrackDecoder): EosEvent {
        val event = eos.poll(10L, TimeUnit.SECONDS)
        assertNotNull(
            "Decoder did not reach EOS; diagnostics=${decoder.diagnostics()}, error=${errors.peek()}",
            event
        )
        return requireNotNull(event)
    }

    private fun noteThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) callbackOnMain.set(true)
    }
}
