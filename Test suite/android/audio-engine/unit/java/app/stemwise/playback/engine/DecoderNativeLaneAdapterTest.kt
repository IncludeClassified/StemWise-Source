package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class DecoderNativeLaneAdapterTest {
    @Test
    fun acceptedFractionalEosCompletesCoverageButMidstreamRemainsConservative() {
        for ((rate, frames, expected) in listOf(Triple(44_100, 44_101, 48_002L),
            Triple(44_100, 44_100, 48_000L), Triple(48_000, 48_001, 48_001L))) {
            val lane = adapter(FakeTargetFactory())
            val format = NativePcmFormat(rate, 2, NativePcmEncoding.PCM_16_BIT)
            lane.onOutputFormatChanged(format, 0L)
            assertEquals(frames * format.bytesPerFrame,
                lane.offer(block(format, frames, 0L, 0L, 0L)).acceptedBytes)
            val midstream = lane.diagnostics().toNativeDecoderHead()
            assertEquals(frames * 48_000L / rate, midstream.acceptedTimelineFrameExclusive48k)
            assertNull(midstream.endOfStreamTimelineFrameExclusive48k)
            lane.onEndOfStream(0L, null)
            val terminal = lane.diagnostics().toNativeDecoderHead()
            assertEquals(expected, terminal.acceptedTimelineFrameExclusive48k)
            assertEquals(expected, terminal.endOfStreamTimelineFrameExclusive48k)
            assertEquals(expected, terminal.acceptedTimelineFrames48k)
        }
    }

    @Test
    fun targetFailuresRetainTheirFirstOperationAndCauseThroughLaterSinkFailure() {
        for (operation in listOf("configure", "offer", "end-of-stream")) {
            val marker = "unique-$operation-cause"
            val target = object : DecoderNativeLaneTarget {
                override fun offer(block: NativePcmBlock): NativePcmSinkResult =
                    throw IllegalStateException(marker)
                override fun markEndOfStream(decoderSeekGeneration: Long,
                    finalSourceFrameExclusive: Long): NativeLaneEndOfStreamStatus =
                    throw IllegalStateException(marker)
            }
            val lane = DecoderNativeLaneAdapter("decoder", "lane", 1L,
                DecoderNativeLaneTargetFactory { _, _ ->
                    if (operation == "configure") error(marker)
                    target
                })
            lane.onOutputFormatChanged(PCM_48K_STEREO, 0L)
            if (operation == "offer") lane.offer(block(PCM_48K_STEREO, 1, 0L, 0L, 0L))
            if (operation == "end-of-stream") lane.onEndOfStream(0L, null)
            // MediaCodec may subsequently report the permanent stall. The original failure wins.
            assertTrue(lane.offer(block(PCM_48K_STEREO, 1, 0L, 0L, 0L)).reportsPermanentStall)
            val diagnostic = lane.diagnostics().toString()
            assertTrue("Missing $operation cause: $diagnostic", diagnostic.contains(marker))
            assertEquals(1, Regex(Regex.escape(marker)).findAll(diagnostic).count())
        }
    }

    @Test
    fun terminalLaneRejectsFinalAuthorizationWithoutThrowingOrAdvancing() {
        val adapter = adapter(FakeTargetFactory())
        val before = adapter.diagnostics()
        adapter.beginCancellation()
        assertFalse(adapter.tryAuthorizeDecoderGeneration(1L, 2_000L))
        assertEquals(before.nativeGeneration, adapter.diagnostics().nativeGeneration)
        assertEquals(before.decoderSeekGeneration, adapter.diagnostics().decoderSeekGeneration)
    }

    @Test
    fun emptySeekEndBeforeAuthorizationIsPublishedOnlyAfterAuthorization() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 1L)
        adapter.onEndOfStream(1L, null)
        assertEquals(0, fake.configureCalls)
        assertTrue(fake.target.eosBoundaries.isEmpty())

        adapter.authorizeDecoderGeneration(1L)
        assertEquals(1, fake.configureCalls)
        assertEquals(listOf(0L), fake.target.eosBoundaries)
        assertEquals(DecoderNativeLaneAdapterState.ENDED, adapter.diagnostics().state)
        adapter.authorizeDecoderGeneration(1L)
        assertEquals(1, fake.target.eosBoundaries.size)
    }

    @Test
    fun newerSeekDiscardsDeferredEndFromSupersededGeneration() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        adapter.onEndOfStream(1L, null)
        adapter.authorizeDecoderGeneration(2L)
        assertTrue(fake.target.eosBoundaries.isEmpty())
        assertEquals(DecoderNativeLaneAdapterState.STREAMING, adapter.diagnostics().state)
        adapter.beginCancellation()
        adapter.onEndOfStream(3L, null)
        assertTrue(fake.target.eosBoundaries.isEmpty())
    }

    @Test
    fun productionFactoryConfiguresFromActualDecoderFormatNotMetadataDefault() {
        val backend = RecordingIngressBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val adapter = DecoderNativeLaneAdapter.forNativeBank(
                decoderId = "decoder-original",
                laneId = "original",
                bank = bank,
                laneIndex = 0,
                generationBase = 700L
            )

            adapter.onOutputFormatChanged(FLOAT_96K_STEREO, 0L)

            assertEquals(96_000, backend.configuredSampleRateHz)
            assertEquals(700L, backend.configuredGeneration)
            assertEquals(FLOAT_96K_STEREO, adapter.diagnostics().configuredFormat)
            adapter.beginCancellation()
            assertFalse(adapter.diagnostics().safeToReleaseLane)
            adapter.confirmDecoderJoined()
            assertTrue(adapter.diagnostics().safeToReleaseLane)
            assertTrue(bank.releaseLane(0))
        }
    }

    @Test
    fun seekBeforeFirstFormatConfiguresAtAlreadyPublishedWholeSongGeneration() {
        val backend = RecordingIngressBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val adapter = DecoderNativeLaneAdapter.forNativeBank(
                decoderId = "decoder-original",
                laneId = "original",
                bank = bank,
                laneIndex = 0,
                generationBase = 700L
            )
            assertEquals(707L, adapter.authorizeDecoderGeneration(7L))

            adapter.onOutputFormatChanged(PCM_48K_STEREO, 7L)
            adapter.offer(block(PCM_48K_STEREO, 4, 240L, 5_000L, 7L))

            assertEquals(707L, backend.configuredGeneration)
            assertEquals(707L, backend.lastOfferGeneration)
            adapter.beginCancellation()
            adapter.confirmDecoderJoined()
            assertTrue(bank.releaseLane(0))
        }
    }

    @Test
    fun exactFrameSeamSurvivesPartialAcceptanceAndIgnoresRoundedEosTimestamp() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake, generationBase = 10_000L)
        adapter.onOutputFormatChanged(PCM_44K_MONO, 0L)
        fake.target.nextAcceptedFrames = 4

        val first = adapter.offer(block(
            format = PCM_44K_MONO,
            frames = 10,
            firstTimelineFrame = 1_000_003L,
            presentationTimeUs = 7L,
            seekGeneration = 0L
        ))
        assertEquals(4 * PCM_44K_MONO.bytesPerFrame, first.acceptedBytes)
        assertEquals(1_000_007L, adapter.diagnostics().acceptedSourceFrameExclusive)

        fake.target.nextAcceptedFrames = null
        val suffix = adapter.offer(block(
            format = PCM_44K_MONO,
            frames = 6,
            firstTimelineFrame = 1_000_007L,
            presentationTimeUs = 8L,
            seekGeneration = 0L
        ))
        assertEquals(6 * PCM_44K_MONO.bytesPerFrame, suffix.acceptedBytes)
        adapter.onEndOfStream(0L, finalPresentationTimeUs = 999_999_999L)

        assertEquals(listOf(1_000_013L), fake.target.eosBoundaries)
        val diagnostics = adapter.diagnostics()
        assertEquals(1_000_003L, diagnostics.firstAcceptedSourceFrame)
        assertEquals(1_000_013L, diagnostics.acceptedSourceFrameExclusive)
        assertEquals(10L, diagnostics.acceptedFrames)
        assertEquals(DecoderNativeLaneAdapterState.ENDED, diagnostics.state)
        assertEquals(NativeLaneEndOfStreamStatus.ACCEPTED, diagnostics.endOfStreamStatus)
    }

    @Test
    fun eosFailsClosedWhileAnyPcmSuffixRemainsUnaccepted() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        fake.target.nextAcceptedFrames = 0

        adapter.offer(block(PCM_48K_STEREO, 32, 200L, 4L, 0L))
        adapter.onEndOfStream(0L, finalPresentationTimeUs = 4L)

        assertTrue(fake.target.eosBoundaries.isEmpty())
        assertEquals(
            DecoderNativeLaneAdapterFailure.END_OF_STREAM_WITH_PENDING_PCM,
            adapter.diagnostics().failure
        )
        assertEquals(DecoderNativeLaneAdapterState.FAILED, adapter.diagnostics().state)
    }

    @Test
    fun onlyControllerAuthorizedGenerationCanReachNativeLane() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake, generationBase = 1_000L)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        adapter.offer(block(PCM_48K_STEREO, 8, 0L, 0L, 0L))
        assertEquals(1, fake.target.offerCalls)

        assertEquals(1_003L, adapter.authorizeDecoderGeneration(3L))
        val stale = adapter.offer(block(PCM_48K_STEREO, 5, 8L, 166L, 0L))
        assertEquals(5 * PCM_48K_STEREO.bytesPerFrame, stale.acceptedBytes)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        adapter.onEndOfStream(0L, 166L)
        assertEquals(1, fake.target.offerCalls)
        assertTrue(fake.target.eosBoundaries.isEmpty())

        val future = adapter.offer(block(PCM_48K_STEREO, 5, 500L, 2L, 4L))
        assertEquals(0, future.acceptedBytes)
        assertEquals(1, fake.target.offerCalls)

        adapter.offer(block(PCM_48K_STEREO, 5, 500L, 2L, 3L))
        val diagnostics = adapter.diagnostics()
        assertEquals(2, fake.target.offerCalls)
        assertEquals(3L, diagnostics.decoderSeekGeneration)
        assertEquals(1_003L, diagnostics.nativeGeneration)
        assertEquals(1L, diagnostics.discardedStaleOffers)
        assertEquals(1L, diagnostics.waitingFutureOffers)
        assertTrue(diagnostics.ignoredCallbacks >= 2L)
    }

    @Test
    fun oneSongLaneCannotSilentlyChangePcmLayoutOrSampleRate() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)

        adapter.onOutputFormatChanged(PCM_44K_MONO, 0L)

        assertEquals(1, fake.configureCalls)
        assertEquals(DecoderNativeLaneAdapterState.FAILED, adapter.diagnostics().state)
        assertEquals(
            DecoderNativeLaneAdapterFailure.OUTPUT_FORMAT_CHANGED,
            adapter.diagnostics().failure
        )
        assertFalse(adapter.tryAuthorizeDecoderGeneration(1L, 2_000L))
        assertEquals(0L, adapter.diagnostics().decoderSeekGeneration)
        assertTrue(
            adapter.offer(block(PCM_48K_STEREO, 2, 0L, 0L, 0L))
                .reportsPermanentStall
        )
    }

    @Test
    fun staleCallbacksRemainHarmlessThroughoutCancelJoinReleaseOrder() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)

        assertTrue(adapter.beginCancellation())
        assertFalse(adapter.beginCancellation())
        adapter.onOutputFormatChanged(FLOAT_96K_STEREO, 0L)
        val discarded = adapter.offer(block(FLOAT_96K_STEREO, 3, 0L, 0L, 0L))
        adapter.onCancelled(1L)

        assertEquals(3 * FLOAT_96K_STEREO.bytesPerFrame, discarded.acceptedBytes)
        assertEquals(0, fake.configureCalls)
        assertFalse(adapter.diagnostics().safeToReleaseLane)
        adapter.confirmDecoderJoined()
        assertTrue(adapter.diagnostics().safeToReleaseLane)
        adapter.onOutputFormatChanged(FLOAT_96K_STEREO, 0L)
        assertEquals(0, fake.configureCalls)

        assertThrows(IllegalStateException::class.java) {
            adapter(FakeTargetFactory()).confirmDecoderJoined()
        }
    }

    @Test
    fun configuredEmptyGenerationMarksAnExactZeroBoundary() {
        val fake = FakeTargetFactory()
        val adapter = adapter(fake)
        adapter.onOutputFormatChanged(PCM_48K_STEREO, 0L)

        adapter.onEndOfStream(0L, finalPresentationTimeUs = null)

        assertEquals(listOf(0L), fake.target.eosBoundaries)
        assertEquals(DecoderNativeLaneAdapterState.ENDED, adapter.diagnostics().state)
        assertNull(adapter.diagnostics().acceptedSourceFrameExclusive)
    }

    @Test
    fun nativeStaleEosIsIgnoredButBoundaryMismatchFailsClosed() {
        val staleFactory = FakeTargetFactory().apply {
            target.eosStatus = NativeLaneEndOfStreamStatus.STALE_GENERATION
        }
        val stale = adapter(staleFactory)
        stale.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        stale.onEndOfStream(0L, null)
        assertEquals(DecoderNativeLaneAdapterState.STREAMING, stale.diagnostics().state)
        assertNull(stale.diagnostics().failure)

        val mismatchFactory = FakeTargetFactory().apply {
            target.eosStatus = NativeLaneEndOfStreamStatus.BOUNDARY_MISMATCH
        }
        val mismatch = adapter(mismatchFactory)
        mismatch.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        mismatch.onEndOfStream(0L, null)
        assertEquals(DecoderNativeLaneAdapterState.FAILED, mismatch.diagnostics().state)
        assertEquals(
            DecoderNativeLaneAdapterFailure.END_OF_STREAM_REJECTED,
            mismatch.diagnostics().failure
        )
    }

    @Test
    fun factoryFailureAndPreparedIdentityMismatchAreTerminalFailures() {
        val failedFactory = DecoderNativeLaneTargetFactory { _, _ ->
            error("sensitive path that must not escape")
        }
        val configureFailure = DecoderNativeLaneAdapter(
            "decoder", "lane", 1L, failedFactory
        )
        configureFailure.onOutputFormatChanged(PCM_48K_STEREO, 0L)
        assertEquals(
            DecoderNativeLaneAdapterFailure.LANE_CONFIGURATION_FAILED,
            configureFailure.diagnostics().failure
        )

        val identity = adapter(FakeTargetFactory())
        identity.onPrepared(prepared(decoderId = "other", laneId = "lane"))
        assertEquals(
            DecoderNativeLaneAdapterFailure.PREPARED_IDENTITY_MISMATCH,
            identity.diagnostics().failure
        )
    }

    private fun adapter(
        factory: FakeTargetFactory,
        generationBase: Long = 100L
    ): DecoderNativeLaneAdapter = DecoderNativeLaneAdapter(
        decoderId = "decoder",
        laneId = "lane",
        generationBase = generationBase,
        targetFactory = factory
    )

    private fun block(
        format: NativePcmFormat,
        frames: Int,
        firstTimelineFrame: Long,
        presentationTimeUs: Long,
        seekGeneration: Long
    ): NativePcmBlock {
        val bytes = Math.multiplyExact(frames, format.bytesPerFrame)
        val data = ByteBuffer.allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .apply { limit(bytes) }
            .asReadOnlyBuffer()
            .order(ByteOrder.nativeOrder())
        return NativePcmBlock().reset(
            data = data,
            format = format,
            presentationTimeUs = presentationTimeUs,
            seekGeneration = seekGeneration,
            firstTimelineFrame = firstTimelineFrame
        )
    }

    private fun prepared(decoderId: String, laneId: String) = NativeDecoderPrepared(
        decoderId = decoderId,
        laneId = laneId,
        source = "redacted-source",
        codecName = "fake-codec",
        track = NativeAudioTrackInfo(
            trackIndex = 0,
            mimeType = "audio/fake",
            language = null,
            durationUs = null,
            sampleRateHz = null,
            channelCount = null,
            encoderDelayFrames = 0,
            encoderPaddingFrames = 0
        ),
        trackStartPresentationTimeUs = 0L
    )

    private class FakeTargetFactory : DecoderNativeLaneTargetFactory {
        val target = FakeTarget()
        var configureCalls = 0
        val formats = mutableListOf<NativePcmFormat>()

        override fun configure(
            format: NativePcmFormat,
            initialNativeGeneration: Long
        ): DecoderNativeLaneTarget {
            configureCalls++
            formats += format
            return target
        }
    }

    private class FakeTarget : DecoderNativeLaneTarget {
        var nextAcceptedFrames: Int? = null
        var eosStatus = NativeLaneEndOfStreamStatus.ACCEPTED
        var offerCalls = 0
        val eosBoundaries = mutableListOf<Long>()

        override fun offer(block: NativePcmBlock): NativePcmSinkResult {
            offerCalls++
            val acceptedFrames = nextAcceptedFrames ?: block.frameCount
            return NativePcmSinkResult.accepting(
                acceptedFrames * block.format.bytesPerFrame,
                if (acceptedFrames == block.frameCount) 0L else 500L
            )
        }

        override fun markEndOfStream(
            decoderSeekGeneration: Long,
            finalSourceFrameExclusive: Long
        ): NativeLaneEndOfStreamStatus {
            eosBoundaries += finalSourceFrameExclusive
            return eosStatus
        }
    }

    private class RecordingIngressBackend : NativeLaneIngressBackend {
        var configuredSampleRateHz = -1
        var configuredGeneration = -1L
        var lastOfferGeneration = -1L

        override fun createSongBank(
            queueCapacityFrames: Int,
            maximumInputQuantumFrames: Int
        ): Long = 1L

        override fun configureLane(
            bankHandle: Long,
            laneIndex: Int,
            sourceRateHz: Int,
            initialGeneration: Long
        ): Boolean {
            configuredSampleRateHz = sourceRateHz
            configuredGeneration = initialGeneration
            return true
        }

        override fun offer(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long,
            presentationTimeUs: Long,
            encodingCode: Int,
            channelCount: Int,
            directBuffer: ByteBuffer,
            byteOffset: Int,
            frameCount: Int
        ): Long {
            lastOfferGeneration = generation
            return frameCount.toLong()
        }

        override fun advanceGeneration(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long
        ): Boolean = true

        override fun markDiscontinuity(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long
        ): Boolean = true

        override fun markEndOfStream(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long,
            finalSourceFrameExclusive: Long
        ): Int = 0

        override fun readableSourceFrames(bankHandle: Long, laneIndex: Int): Int = 0
        override fun releaseLane(bankHandle: Long, laneIndex: Int) = Unit
        override fun closeSongBank(bankHandle: Long) = Unit
    }

    private companion object {
        val PCM_44K_MONO = NativePcmFormat(
            44_100, 1, NativePcmEncoding.PCM_16_BIT
        )
        val PCM_48K_STEREO = NativePcmFormat(
            48_000, 2, NativePcmEncoding.PCM_16_BIT
        )
        val FLOAT_96K_STEREO = NativePcmFormat(
            96_000, 2, NativePcmEncoding.PCM_FLOAT
        )
    }
}
