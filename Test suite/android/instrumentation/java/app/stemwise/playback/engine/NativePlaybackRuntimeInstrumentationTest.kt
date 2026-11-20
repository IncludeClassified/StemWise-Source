package app.stemwise.playback.engine

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Production JNI smoke proof, including the definitive runtime-before-borrowed-owners fence. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativePlaybackRuntimeInstrumentationTest {
    @Test
    fun canonicalFlac44100AndGaplessOpusReadsMatchIndependentFullDecodeBitForBit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for ((asset, extension) in listOf(
            "encoder/flac16-stereo-44100.flac" to ".flac",
            "decoder/canonical-opus.ogg.base64" to ".ogg",
            "decoder/imported-mp3.mp3.base64" to ".mp3")) {
            val source = File.createTempFile("canonical-pcm-", extension, context.cacheDir)
            try {
                source.writeBytes(instrumentation.context.assets.open(asset).use { input ->
                    if (asset.endsWith(".base64")) {
                        Base64.getMimeDecoder().decode(input.bufferedReader(Charsets.UTF_8).readText())
                    } else input.readBytes()
                })
                NativeAudioOutput(16_384, 1L).use { output ->
                    NativeSongLaneIngressBank(131_072, 256).use { firstBank ->
                        NativeSongLaneIngressBank(131_072, 256).use { secondBank ->
                            val owner = NativeRuntimeOwnership(4_701L, 2L)
                            decodeRefillFixture(source, firstBank, owner.generation)
                            decodeRefillFixture(source, secondBank, owner.generation)
                            val baseline = drainRefillBaseline(secondBank, owner.generation)
                            assertTrue("Fixture must span multiple canonical read windows: $asset", baseline.size / 8 > 16_384)
                            NativePlaybackRuntime(output, firstBank, secondBank,
                                NativeRuntimeConfiguration(currentSongCacheDirectory = context.cacheDir.absolutePath)).use { runtime ->
                                val identity = NativeRuntimeBankIntent(1L, owner,
                                    songIdentityHash = 101L, originalContentHash = 201L,
                                    cacheFrameUpperBound = (baseline.size / 8).toLong())
                                assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                                    NativeRuntimeCommandSnapshot(1L, owner.generation,
                                        listOf(identity, NativeRuntimeBankIntent.unassigned(1L)),
                                        NativeRuntimeBank.FIRST, playing = true,
                                        controls = NativeRuntimeControls(1L, sourceIntentRevision = 1L,
                                            stemIntentRevision = 1L))))
                                assertTrue(runtime.start())
                                awaitDiagnostics(runtime) { it.audibleEnd?.ownership == owner }
                                awaitRenderReadiness(runtime) { it.currentSongCacheReadyForSelectedOwner }
                                assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                                    NativeRuntimeCommandSnapshot(2L, owner.generation,
                                        listOf(identity, NativeRuntimeBankIntent.unassigned(1L)),
                                        NativeRuntimeBank.FIRST, playing = false,
                                        controls = NativeRuntimeControls(1L, sourceIntentRevision = 1L,
                                            stemIntentRevision = 1L))))
                                output.pause()
                                val handle = (runtime.javaClass.getDeclaredField("handle").apply {
                                    isAccessible = true
                                }.get(runtime) as AtomicLong).get()
                                val key = longArrayOf(owner.selectionToken, owner.generation, 101L, 201L, 0L)
                                val frameCount = 1_024
                                val totalFrames = baseline.size / 8
                                for (firstFrame in listOf(0L, 4_096L, (totalFrames - frameCount).toLong())) {
                                    assertTrue(runtime.pcmCacheRangeReady(identity, 1,
                                        firstFrame, firstFrame + frameCount))
                                    val before = requireNotNull(runtime.diagnostics().canonicalPcmIo)
                                    val actual = NativeCanonicalPcmInstrumentationBackend.read(
                                        handle, key, 0, firstFrame, frameCount)
                                    assertEquals(frameCount * 2, actual.size)
                                    val expected = ByteBuffer.wrap(baseline).order(ByteOrder.nativeOrder())
                                        .asFloatBuffer().apply { position(firstFrame.toInt() * 2) }
                                    assertArrayEquals("$asset canonical read must preserve exact codec/SRC PCM",
                                        IntArray(actual.size) { expected.get().toRawBits() },
                                        IntArray(actual.size) { actual[it].toRawBits() })
                                    val after = requireNotNull(runtime.diagnostics().canonicalPcmIo)
                                    assertEquals(before.readOperations + 1L, after.readOperations)
                                    assertEquals(firstFrame * 8L, after.lastReadByteOffset)
                                    assertEquals(frameCount * 8L, after.lastReadBytes)
                                    assertEquals(before.readBytes + frameCount * 8L, after.readBytes)
                                }
                                assertFalse(runtime.pcmCacheRangeReady(identity.copy(
                                    ownership = owner.copy(generation = owner.generation + 1L)),
                                    1, 0L, frameCount.toLong()))
                                assertTrue(NativeCanonicalPcmInstrumentationBackend.read(handle,
                                    key.copyOf().apply { this[1] += 1L }, 0, 0L, frameCount).isEmpty())
                                runtime.requestStop()
                                runtime.join()
                            }
                        }
                    }
                }
            } finally {
                assertTrue("Could not remove owned refill fixture ${source.absolutePath}", source.delete())
            }
        }
    }

    private fun decodeRefillFixture(source: File, bank: NativeSongLaneIngressBank, generation: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminal = CountDownLatch(1)
        val decodeFailure = AtomicReference<String?>(null)
        val adapter = DecoderNativeLaneAdapter.forNativeBank("refill-fixture", "original", bank, 0, generation)
        NativeDecoderWorkerPool.dedicated("RefillBaseline").use { pool ->
            val decoder = MediaCodecTrackDecoder(context, "refill-fixture", "original",
                NativeMediaSource.LocalFile(source), adapter,
                listener = object : NativeMediaCodecDecoderListener by adapter {
                    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
                        adapter.onEndOfStream(seekGeneration, finalPresentationTimeUs)
                        terminal.countDown()
                    }
                    override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
                        adapter.onError(failure, diagnostics)
                        decodeFailure.set("${failure.code}: ${failure.message}")
                        terminal.countDown()
                    }
                }, workerPool = pool,
                    packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
            try {
                assertTrue(decoder.start())
                assertTrue("Fixture decode timed out", terminal.await(10L, TimeUnit.SECONDS))
                assertNull(decodeFailure.get())
                assertEquals(DecoderNativeLaneAdapterState.ENDED, adapter.diagnostics().state)
            } finally {
                adapter.beginCancellation()
                decoder.close()
                assertTrue(decoder.awaitClosed(5L, TimeUnit.SECONDS))
                adapter.confirmDecoderJoined()
            }
        }
    }

    private fun drainRefillBaseline(bank: NativeSongLaneIngressBank, generation: Long): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocateDirect(4_096 * 8).order(ByteOrder.nativeOrder())
        val bytes = ByteArray(buffer.capacity())
        val metadata = LongArray(3)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            val count = bank.pullOriginal48kForPreparation(generation, buffer, metadata)
            if (count > 0) {
                assertEquals((result.size() / 8).toLong(), metadata[0])
                buffer.position(0)
                buffer.get(bytes, 0, count * 8)
                result.write(bytes, 0, count * 8)
            }
            if (metadata[1] == 2L) return result.toByteArray()
            assertTrue("Unexpected baseline pull state ${metadata[1]}", metadata[1] == 0L || metadata[1] == 4L)
        }
        error("Fixture PCM baseline did not reach exact EOS")
    }

    @Test
    fun currentCommandPacketRoundTripsRealJniAndRejectsCorruptionWithoutMutation() {
        NativeAudioOutput(1_024, 1L).use { output ->
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        // Reach the real existing backend with this test's owned opaque handle;
                        // no alternate native method can accidentally test a different decoder.
                        val backendField = runtime.javaClass.getDeclaredField("backend").apply { isAccessible = true }
                        val handleField = runtime.javaClass.getDeclaredField("handle").apply { isAccessible = true }
                        val backend = backendField.get(runtime) as NativePlaybackRuntimeBackend
                        val handle = (handleField.get(runtime) as java.util.concurrent.atomic.AtomicLong).get()
                        val baseline = runtime.diagnostics().counters.commandSnapshotsPublished
                        val snapshots = listOf<Int?>(null, 0, 2, NativeRuntimeControls.STEM_COUNT).mapIndexed { index, stems ->
                            val revision = index + 1L
                            val assigned = stems != null
                            NativeRuntimeCommandSnapshot(revision, revision + 1L,
                                banks = listOf(if (assigned) NativeRuntimeBankIntent(revision,
                                    ownership = NativeRuntimeOwnership(700L + revision, revision + 1L),
                                    songIdentityHash = Long.MIN_VALUE + revision,
                                    originalContentHash = 2_000L + revision,
                                    stemPackageHash = if (stems == 0) 0L else 3_000L + revision,
                                    stemLaneCount = stems, cacheFrameUpperBound = 48_000L)
                                    else NativeRuntimeBankIntent.unassigned(revision),
                                    NativeRuntimeBankIntent.unassigned(revision)),
                                selectedBank = if (assigned) NativeRuntimeBank.FIRST else null,
                                playing = false,
                                controls = NativeRuntimeControls(revision,
                                    sourceIntentRevision = if (assigned) revision else 0L,
                                    stemIntentRevision = if (assigned) revision else 0L))
                        }
                        snapshots.forEach { assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(it)) }
                        assertEquals(baseline + 4L, runtime.diagnostics().counters.commandSnapshotsPublished)
                        val before = runtime.diagnostics().counters
                        val encoded = NativeRuntimeCommandCodec.encode(snapshots.last())
                        for (corrupt in listOf(encoded.first.copyOf().apply {
                                    this[NativePacketSchema.COMMAND_LONG_BANK_0_ASSIGNED] = 2L
                                }, encoded.first.copyOf().apply {
                                    this[NativePacketSchema.COMMAND_LONG_BANK_1_STEM_COUNT] = -1L
                                }, encoded.first.copyOf().apply {
                                    this[NativePacketSchema.COMMAND_LONG_BANK_0_STEM_COUNT] =
                                        NativeRuntimeControls.STEM_COUNT + 1L
                                }, encoded.first.copyOf(encoded.first.size - 1))) {
                            assertThrows(IllegalArgumentException::class.java) {
                                backend.publishCommand(handle, corrupt, encoded.second)
                            }
                            assertEquals(before, runtime.diagnostics().counters)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun currentSongCacheReadinessRequiresAllLanesOnTablet() {
        NativeAudioOutput(
            capacityFrames = 16_384,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(131_072, 256).use { firstBank ->
                NativeSongLaneIngressBank(131_072, 256).use { secondBank ->
                    val builderOwner = NativeRuntimeOwnership(1_700L, 2L)
                    val builderSinks = List(NativeSongLaneIngressBank.LANE_COUNT) { lane ->
                        firstBank.configureLane(lane, 48_000, builderOwner.generation)
                    }
                    offerToneAndEnd(builderSinks[0], 0, 0, 96_000)
                    val builderIntent = NativeRuntimeBankIntent(
                        revision = 1L,
                        ownership = builderOwner,
                        songIdentityHash = 101L,
                        originalContentHash = 201L,
                        stemPackageHash = 301L,
                        stemLaneCount = NativeRuntimeControls.STEM_COUNT,
                        cacheFrameUpperBound = 96_000L
                    )
                    NativePlaybackRuntime(output, firstBank, secondBank,
                        configuration = appCacheConfiguration(96_000L, builderSinks.size)
                    ).use { runtime ->
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publish(
                                NativeRuntimeCommandSnapshot(
                                    revision = 1L,
                                    outputGeneration = builderOwner.generation,
                                    banks = listOf(
                                        builderIntent,
                                        NativeRuntimeBankIntent.unassigned(1L)
                                    ),
                                    selectedBank = NativeRuntimeBank.FIRST,
                                    playing = false,
                                    controls = NativeRuntimeControls(
                                        revision = 1L,
                                        sourceIntentRevision = 1L,
                                        stemIntentRevision = 1L
                                    )
                                )
                            )
                        )
                        assertTrue(runtime.start())
                        val partial = awaitRenderReadiness(runtime) {
                            it.ownership == builderOwner &&
                                it.currentSongCacheBoundToSelectedOwner &&
                                it.currentSongCacheCompleteLaneMask == 1
                        }
                        assertFalse(partial.currentSongCacheReadyForSelectedOwner)

                        for (lane in 1 until NativeSongLaneIngressBank.LANE_COUNT) {
                            offerToneAndEnd(builderSinks[lane], lane, 0, 96_000)
                        }
                        val complete = awaitRenderReadiness(runtime) {
                            it.ownership == builderOwner &&
                                it.currentSongCacheReadyForSelectedOwner
                        }
                        assertTrue(complete.currentSongCacheBoundToSelectedOwner)
                        val allConfiguredLanesMask = (1 shl builderSinks.size) - 1
                        assertEquals(allConfiguredLanesMask, complete.currentSongCacheCompleteLaneMask)
                        assertEquals(allConfiguredLanesMask, complete.requiredSongLaneMask)
                        runtime.requestStop()
                        runtime.join()
                    }
                }
            }
        }
    }


    @Test
    fun renderWorkerRunsAtAndroidAudioPriority() {
        NativeAudioOutput(
            capacityFrames = 1_024,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        assertTrue(runtime.start())
                        val scheduling = awaitDiagnostics(runtime) {
                            it.renderWorkerStarted
                        }
                        assertTrue(scheduling.renderWorkerAudioPriorityRequested)
                        assertTrue(
                            "Native render worker priority request failed: " +
                                "nice=${scheduling.renderWorkerNiceValue}, " +
                                "errno=${scheduling.renderWorkerPriorityErrorCode}",
                            scheduling.renderWorkerAudioPriorityApplied
                        )
                        assertTrue(
                            scheduling.renderWorkerNiceValue <= Process.THREAD_PRIORITY_AUDIO
                        )
                        assertEquals(0, scheduling.renderWorkerPriorityErrorCode)
                        val workerTid = awaitRenderWorkerTid()
                        val priority = Process.getThreadPriority(workerTid)
                        assertTrue(
                            "Native render worker priority $priority is below the audio class " +
                                "${Process.THREAD_PRIORITY_AUDIO}",
                            priority <= Process.THREAD_PRIORITY_AUDIO
                        )
                        runtime.requestStop()
                        runtime.join()
                    }
                }
            }
        }
    }

    @Test
    fun productionJniStartsConsumesExactUnassignmentAndJoinsBeforeOwnersClose() {
        NativeAudioOutput(
            capacityFrames = 1_024,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        assertTrue(runtime.start())
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publishBeatGrid(
                                NativeRuntimeBeatGridUpdate(
                                    revision = 1L,
                                    bank = NativeRuntimeBank.FIRST,
                                    ownership = NativeRuntimeOwnership(77L, 2L),
                                    beatFrames = longArrayOf(0L, 12_000L, 24_000L),
                                    beatAccents = booleanArrayOf(false, true, false)
                                )
                            )
                        )
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publish(
                                NativeRuntimeCommandSnapshot(
                                    revision = 1L,
                                    outputGeneration = 2L,
                                    banks = listOf(
                                        NativeRuntimeBankIntent.unassigned(101L),
                                        NativeRuntimeBankIntent.unassigned(201L)
                                    ),
                                    selectedBank = null,
                                    playing = false,
                                    controls = NativeRuntimeControls(1L)
                                )
                            )
                        )

                        val applied = awaitDiagnostics(runtime) { diagnostics ->
                            diagnostics.status == NativeRuntimeStatus.RUNNING &&
                                diagnostics.counters.commandSnapshotsConsumed == 1L &&
                                diagnostics.banks[0].appliedIntentRevision == 101L &&
                                diagnostics.banks[1].appliedIntentRevision == 201L
                        }
                        assertFalse(applied.banks[0].assigned)
                        assertFalse(applied.banks[1].assigned)
                        assertTrue(runtime.hasAppliedUnassignment(NativeRuntimeBank.FIRST, 101L))
                        assertTrue(runtime.hasAppliedUnassignment(NativeRuntimeBank.SECOND, 201L))

                        val outputAdvancedByRuntime = awaitOutputDiagnostics(output) {
                            it.streamGeneration == 2L && it.appliedGeneration == 2L
                        }
                        assertEquals(2L, outputAdvancedByRuntime.streamGeneration)
                        // The runtime borrows the native output port and owns this transition.
                        // Kotlin must query that native authority instead of retaining generation 1.
                        assertEquals(2L, output.currentGeneration())

                        runtime.requestStop()
                        runtime.join()
                        assertEquals(NativeRuntimeStatus.STOPPED, runtime.diagnostics().status)
                    }
                    var flushed: Long? = null
                    val flushDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
                    while (flushed == null && System.nanoTime() < flushDeadline) {
                        flushed = output.tryFlush()
                        if (flushed == null) Thread.sleep(2L)
                    }
                    assertEquals("The production feeder must acknowledge the exact stopped flush", 3L, flushed)
                    assertFalse(output.isClosed())
                }
            }
        }
    }

    @Test
    fun deviceCallbackAcknowledgesOnlySettledSourceRevision() {
        NativeAudioOutput(
            capacityFrames = 16_384,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(65_536, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    val sinks = List(NativeSongLaneIngressBank.LANE_COUNT) { lane ->
                        firstBank.configureLane(lane, 48_000, 2L)
                    }
                    sinks.forEachIndexed { lane, sink ->
                        offerToneAndEnd(sink, lane, firstTimelineFrame = 0, frameCount = 49_152)
                    }
                    val owner = NativeRuntimeOwnership(700L, 2L)
                    val banks = listOf(
                        NativeRuntimeBankIntent(
                            revision = 1L,
                            ownership = owner,
                            songIdentityHash = 700L,
                            originalContentHash = 701L,
                            stemPackageHash = 702L,
                            stemLaneCount = NativeRuntimeControls.STEM_COUNT,
                            cacheFrameUpperBound = 49_152L
                        ),
                        NativeRuntimeBankIntent.unassigned(1L)
                    )
                    NativePlaybackRuntime(output, firstBank, secondBank,
                        configuration = appCacheConfiguration(49_152L, sinks.size)
                    ).use { runtime ->
                        val emptyGainEvidence = requireNotNull(runtime.stemGainCallbackSettlementDiagnostics())
                        assertFalse(emptyGainEvidence.coherent)
                        assertEquals(0L, emptyGainEvidence.settlementSequence)

                        val processInvalidRenderBaseline =
                            runtime.diagnostics().processOutputInvalidRenderResults
                        val processFlushFailureBaseline =
                            runtime.diagnostics().processOutputFlushFailures
                        val initial = NativeRuntimeControls(
                            revision = 1L,
                            sourceIntentRevision = 1L,
                            stemIntentRevision = 1L,
                            sourceMode = NativeRuntimeSourceMode.ORIGINAL,
                            sourceTransitionFrames = 512
                        )
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publish(
                                NativeRuntimeCommandSnapshot(
                                    revision = 1L,
                                    outputGeneration = 2L,
                                    banks = banks,
                                    selectedBank = NativeRuntimeBank.FIRST,
                                    playing = true,
                                    controls = initial
                                )
                            )
                        )
                        assertTrue(runtime.start())
                        val original = awaitDiagnostics(runtime) { diagnostics ->
                            diagnostics.ownership.committed == owner &&
                                diagnostics.audibleControl?.let {
                                    it.ownership == owner && it.controlRevision == 1L
                                } == true
                        }
                        assertEquals(
                            NativeRuntimeSourceMode.ORIGINAL,
                            original.audibleControl?.actualSourceMode
                        )

                        val beforeStemsSequence = original.audibleControl!!.sequence
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publish(
                                NativeRuntimeCommandSnapshot(
                                    revision = 2L,
                                    outputGeneration = 2L,
                                    banks = banks,
                                    selectedBank = NativeRuntimeBank.FIRST,
                                    playing = true,
                                    controls = initial.copy(
                                        revision = 2L,
                                        sourceIntentRevision = 2L,
                                        sourceMode = NativeRuntimeSourceMode.STEMS
                                    )
                                )
                            )
                        )
                        val stems = awaitDiagnostics(runtime) { diagnostics ->
                            diagnostics.audibleControl?.let {
                                it.sequence > beforeStemsSequence &&
                                    it.ownership == owner &&
                                    it.controlRevision == 2L &&
                                    it.actualSourceMode == NativeRuntimeSourceMode.STEMS
                            } == true
                        }
                        assertTrue(stems.audibleControl!!.firstFingerprintSequence > 0L)
                        assertTrue(stems.audibleControl.lastFingerprintSequence > 0L)
                        assertTrue(stems.audibleControl.callbackSequence > 0L)
                        assertTrue(stems.audibleControl.pcmFrames > 0L)

                        assertEquals(NativeRuntimePublishStatus.ACCEPTED,
                            runtime.publishStemGainPreview(
                                publicationSequence = 41L, coversOfferSequence = 51L,
                                ownership = owner, sourceIntentRevision = 2L, stemIntentRevision = 1L,
                                canonicalControlFloorRevision = 2L, activeMask = 1,
                                gains = FloatArray(NativeRuntimeControls.STEM_COUNT) { if (it == 0) .25f else 1f }))
                        awaitDiagnostics(runtime) {
                            runtime.stemGainCallbackSettlementDiagnostics()?.let { gain ->
                                gain.coherent && gain.ownerGeneration == owner.generation &&
                                    gain.ownerToken == owner.selectionToken && gain.publishedSequence == 41L &&
                                    gain.offeredSequence == 51L && gain.activeMask == 1 &&
                                    gain.gainBits[0] == .25f.toRawBits() && gain.taggedFrameCount > 0L &&
                                    gain.nonZeroPcmFrameCount > 0L
                            } == true
                        }

                        val outputDiagnostics = output.diagnostics()
                        assertEquals(0L, outputDiagnostics.invalidOwnershipTagFrames)
                        val finalRuntimeDiagnostics = runtime.diagnostics()
                        assertEquals(
                            processInvalidRenderBaseline,
                            finalRuntimeDiagnostics.processOutputInvalidRenderResults
                        )
                        assertEquals(
                            processFlushFailureBaseline,
                            finalRuntimeDiagnostics.processOutputFlushFailures
                        )
                        runtime.requestStop()
                        runtime.join()
                    }
                }
            }
        }
    }

    @Test
    fun pauseKeepsTheMultibusStreamWarmUntilRuntimeResume() {
        NativeAudioOutput(
            capacityFrames = 16_384,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(65_536, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    List(NativeSongLaneIngressBank.LANE_COUNT) { lane ->
                        firstBank.configureLane(lane, 48_000, 2L)
                    }.forEachIndexed { lane, sink ->
                        offerTone(sink, lane, frameCount = 32_768)
                    }
                    val owner = NativeRuntimeOwnership(800L, 2L)
                    val banks = listOf(
                        NativeRuntimeBankIntent(
                            revision = 1L,
                            ownership = owner,
                            songIdentityHash = 800L,
                            originalContentHash = 801L,
                            cacheFrameUpperBound = 32_768L
                        ),
                        NativeRuntimeBankIntent.unassigned(1L)
                    )
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        fun publish(revision: Long, playing: Boolean) = runtime.publish(
                            NativeRuntimeCommandSnapshot(
                                revision = revision,
                                outputGeneration = 2L,
                                banks = banks,
                                selectedBank = NativeRuntimeBank.FIRST,
                                playing = playing,
                                controls = NativeRuntimeControls(
                                    revision = revision,
                                    sourceIntentRevision = 1L,
                                    stemIntentRevision = 1L
                                )
                            )
                        )

                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, publish(1L, true))
                        assertTrue(runtime.start())
                        awaitOutputDiagnostics(output) {
                            it.lastPresentedSelectionToken == owner.selectionToken &&
                                it.effectiveRenderPlaying
                        }

                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, publish(2L, false))
                        val paused = awaitOutputDiagnostics(output) {
                            !it.effectiveRenderPlaying && it.queuedFrames > 0L
                        }
                        Thread.sleep(40L)
                        val stillPaused = output.diagnostics()
                        assertEquals(paused.queuedFrames, stillPaused.queuedFrames)
                        assertEquals(paused.reopenCount, stillPaused.reopenCount)

                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, publish(3L, true))
                        val resumed = awaitOutputDiagnostics(output) {
                            it.effectiveRenderPlaying && it.renderedFrames > stillPaused.renderedFrames
                        }
                        assertEquals(stillPaused.reopenCount, resumed.reopenCount)
                        assertEquals(owner.selectionToken, resumed.lastPresentedSelectionToken)
                        runtime.requestStop()
                        runtime.join()
                    }
                }
            }
        }
    }

    private fun offerTone(
        sink: NativeLaneIngressSink,
        lane: Int,
        frameCount: Int
    ) {
        val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
        var offeredFrames = 0
        while (offeredFrames < frameCount) {
            val frames = minOf(256, frameCount - offeredFrames)
            val firstFrame = offeredFrames
            val writable = ByteBuffer.allocateDirect(frames * format.bytesPerFrame)
                .order(ByteOrder.nativeOrder())
            repeat(frames) { offset ->
                val frame = firstFrame + offset
                val sample = (sin(
                    2.0 * PI * (220.0 + lane * 17.0) * frame / 48_000.0
                ) * .015).toFloat()
                writable.putFloat(sample)
                writable.putFloat(-sample)
            }
            writable.flip()
            val readOnly = writable.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            val block = NativePcmBlock().reset(
                data = readOnly,
                format = format,
                presentationTimeUs = firstFrame * 1_000_000L / 48_000L,
                seekGeneration = 0L,
                firstTimelineFrame = firstFrame.toLong()
            )
            val accepted = sink.offer(block)
            assertEquals(readOnly.remaining(), accepted.acceptedBytes)
            offeredFrames += frames
        }
    }

    private fun offerToneAndEnd(
        sink: NativeLaneIngressSink,
        lane: Int,
        firstTimelineFrame: Int,
        frameCount: Int
    ) {
        val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
        var offeredFrames = 0
        while (offeredFrames < frameCount) {
            val frames = minOf(256, frameCount - offeredFrames)
            val firstFrame = firstTimelineFrame + offeredFrames
            val writable = ByteBuffer.allocateDirect(frames * format.bytesPerFrame)
                .order(ByteOrder.nativeOrder())
            repeat(frames) { offset ->
                val frame = firstFrame + offset
                val sample = (sin(
                    2.0 * PI * (220.0 + lane * 17.0) * frame / 48_000.0
                ) * .015).toFloat()
                writable.putFloat(sample)
                writable.putFloat(-sample)
            }
            writable.flip()
            val readOnly = writable.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
            val accepted = sink.offer(
                NativePcmBlock().reset(
                    data = readOnly,
                    format = format,
                    presentationTimeUs = firstFrame * 1_000_000L / 48_000L,
                    seekGeneration = 0L,
                    firstTimelineFrame = firstFrame.toLong()
                )
            )
            assertEquals(readOnly.remaining(), accepted.acceptedBytes)
            offeredFrames += frames
        }
        assertEquals(
            NativeLaneEndOfStreamStatus.ACCEPTED,
            sink.markEndOfStream(0L, (firstTimelineFrame + frameCount).toLong())
        )
    }

    private fun appCacheConfiguration(frameCount: Long, laneCount: Int): NativeRuntimeConfiguration {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        check(directory.isDirectory || directory.mkdirs()) { "The app-owned PCM cache directory is unavailable" }
        val requiredBytes = frameCount * laneCount * 2L * Float.SIZE_BYTES + 512L * 1024 * 1024
        assertTrue(
            "The PCM fixture needs $requiredBytes free bytes in ${directory.absolutePath}; " +
                "available=${directory.usableSpace}",
            directory.usableSpace >= requiredBytes
        )
        return NativeRuntimeConfiguration(currentSongCacheDirectory = directory.absolutePath)
    }

    private fun awaitDiagnostics(
        runtime: NativePlaybackRuntime,
        predicate: (NativeRuntimeDiagnostics) -> Boolean
    ): NativeRuntimeDiagnostics {
        val deadline = System.nanoTime() + 3_000_000_000L
        var latest = runtime.diagnostics()
        while (!predicate(latest) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
            latest = runtime.diagnostics()
        }
        assertTrue("Native runtime did not reach the expected state: $latest", predicate(latest))
        return latest
    }

    private fun awaitRenderReadiness(
        runtime: NativePlaybackRuntime,
        predicate: (NativeRuntimeRenderReadinessDiagnostics) -> Boolean
    ): NativeRuntimeRenderReadinessDiagnostics {
        val deadline = System.nanoTime() + 10_000_000_000L
        var latest = runtime.renderReadinessDiagnostics()
        while ((latest == null || !predicate(latest)) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
            latest = runtime.renderReadinessDiagnostics()
        }
        val result = requireNotNull(latest) { "Native runtime published no render readiness" }
        assertTrue("Native runtime did not publish expected render readiness: $result", predicate(result))
        return result
    }

    private fun awaitOutputDiagnostics(
        output: NativeAudioOutput,
        predicate: (NativeAudioDiagnostics) -> Boolean
    ): NativeAudioDiagnostics {
        val deadline = System.nanoTime() + 3_000_000_000L
        var latest = output.diagnostics()
        while (!predicate(latest) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
            latest = output.diagnostics()
        }
        assertTrue("Native output did not reach the expected state: $latest", predicate(latest))
        return latest
    }

    private fun awaitRenderWorkerTid(): Int {
        val deadline = System.nanoTime() + 3_000_000_000L
        var workerTid: Int? = null
        while (workerTid == null && System.nanoTime() < deadline) {
            workerTid = File("/proc/self/task")
                .listFiles()
                .orEmpty()
                .firstOrNull { task ->
                    runCatching { File(task, "comm").readText().trim() }
                        .getOrNull() == "StemwiseRender"
                }
                ?.name
                ?.toIntOrNull()
            if (workerTid == null) Thread.sleep(10L)
        }
        return requireNotNull(workerTid) {
            "Native playback runtime did not expose its named render worker"
        }
    }
}

/** Audit-only JNI seam: uses the normal cache I/O owner; never injects PCM. */
internal object NativeCanonicalPcmInstrumentationBackend {
    external fun read(handle: Long, owner: LongArray, lane: Int, first: Long, count: Int): FloatArray
}
