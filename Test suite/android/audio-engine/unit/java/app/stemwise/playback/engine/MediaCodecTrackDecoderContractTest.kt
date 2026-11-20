package app.stemwise.playback.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MediaCodecTrackDecoderContractTest {
    @Test fun opusNanosecondMetadataRecoversTheExactIntegerPreskip() {
        for (frames in listOf(0L, 1L, 311L, 312L, 313L, 314L, 65_535L)) {
            val extractorNanos = frames * 1_000_000_000L / 48_000L
            assertEquals("Opus pre-skip=$frames", frames, opusCodecDelayFrames(extractorNanos))
        }
        assertThrows(IllegalArgumentException::class.java) { opusCodecDelayFrames(-1L) }
        assertThrows(ArithmeticException::class.java) { opusCodecDelayFrames(Long.MAX_VALUE) }
    }

    @Test
    fun codecProgressDeadlineBoundsStartupAndPostOutputStallsButRebasesIntentionalParking() {
        val deadline = NativeCodecProgressDeadline(15_000L)
        deadline.reset(1_000_000L)
        assertFalse(deadline.expired(15_000_999_999L))
        assertTrue(deadline.expired(15_001_000_000L))
        for (now in listOf(20_000_000_000L, 60_000_000_000L, 500_000_000_000L)) {
            deadline.reset(now)
            assertFalse(deadline.expired(now))
            assertFalse(deadline.expired(now + 14_999_999_999L))
            assertTrue(deadline.expired(now + 15_000_000_000L))
        }
    }

    @Test
    fun terminalFailurePreservesBoundedNativeCauseAndOperationIdentity() {
        val vendor = java.io.IOException("WavPack seek failed: truncated block")
        val failure = NativeDecoderFailure(NativeDecoderErrorCode.SOURCE_OPEN_FAILED,
            "decoder-73", "remaining", "Float source open", vendor)
        assertEquals("decoder-73", failure.decoderId)
        assertEquals("remaining", failure.laneId)
        val detail = requireNotNull(failure.causeDiagnostic)
        assertTrue(detail.contains("java.io.IOException"))
        assertTrue(detail.contains("WavPack seek failed: truncated block"))
        assertTrue(detail.contains("terminalFailurePreservesBoundedNativeCauseAndOperationIdentity"))
        assertTrue(requireNotNull(NativeDecoderFailure(NativeDecoderErrorCode.CODEC_RUNTIME_FAILED,
            "decoder-74", "original", "decode", IllegalStateException("x".repeat(20_000)))
            .causeDiagnostic).length <= 4096)
    }

    @Test
    fun allAdmittedPersistentDecoderPumpsCanStartWithoutQueuedStarvation() {
        val pool = NativeDecoderWorkerPool.shared
        val admittedPumps = NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS
        assertEquals(12, admittedPumps)
        val started = CountDownLatch(admittedPumps)
        val release = CountDownLatch(1)
        val tasks = mutableListOf<Future<*>>()
        try {
            repeat(admittedPumps) {
                tasks += pool.submit(Runnable {
                    started.countDown()
                    release.await()
                })
            }
            assertTrue("An admitted decoder pump remained queued behind another persistent pump",
                started.await(5, TimeUnit.SECONDS))
            assertEquals(admittedPumps, pool.diagnostics().runningTaskCount)
            assertEquals(0, pool.diagnostics().queuedTaskCount)
        } finally {
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun extractorPartialFramesNeverBecomeCodecEndOfStream() {
        assertEquals(0, codecInputFlagsForExtractorSample(0))
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME,
            codecInputFlagsForExtractorSample(MediaExtractor.SAMPLE_FLAG_SYNC))
        val partial = codecInputFlagsForExtractorSample(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(MediaCodec.BUFFER_FLAG_PARTIAL_FRAME, partial)
        assertEquals(0, partial and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            codecInputFlagsForExtractorSample(
                MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
        assertThrows(IllegalArgumentException::class.java) {
            codecInputFlagsForExtractorSample(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        }
    }

    @Test
    fun pcmFormatMakesFrameLayoutExplicitAndRejectsImplicitDownmix() {
        val monoPcm16 = NativePcmFormat(48_000, 1, NativePcmEncoding.PCM_16_BIT)
        assertEquals(
            2,
            monoPcm16.bytesPerFrame
        )
        assertEquals(ByteOrder.nativeOrder(), monoPcm16.byteOrder)
        assertEquals(
            8,
            NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT).bytesPerFrame
        )
        assertThrows(IllegalArgumentException::class.java) {
            NativePcmFormat(48_000, 6, NativePcmEncoding.PCM_FLOAT)
        }
    }

    @Test
    fun negativeContainerPtsAndEncoderDelayRebaseToAudibleZero() {
        val timeline = NativeGaplessTimeline(
            trackStartPresentationTimeUs = -20_000L,
            encoderDelayFrames = 960,
            encoderPaddingFrames = 480
        )

        assertEquals(0L, timeline.absoluteFrameForPresentationTime(-20_000L, 48_000))
        assertEquals(960L, timeline.absoluteFrameForPresentationTime(0L, 48_000))
        assertEquals(960L, timeline.absoluteFrameForTimelinePosition(0L, 48_000))
        assertEquals(0L, timeline.extractorPositionForTimelineUs(0L, 48_000))
    }

    @Test
    fun previousSyncDecodeDiscardsToTheExactRequestedFrame() {
        val timeline = NativeGaplessTimeline(
            trackStartPresentationTimeUs = -20_000L,
            encoderDelayFrames = 960,
            encoderPaddingFrames = 480
        )
        val targetUs = 20_833L
        val targetAbsoluteFrame = timeline.absoluteFrameForTimelinePosition(targetUs, 48_000)
        val previousSyncAbsoluteFrame = 1_800L
        val previousSyncPtsUs = -20_000L + framesToMicroseconds(
            previousSyncAbsoluteFrame,
            48_000
        )
        val decodedAbsoluteFrame = timeline.absoluteFrameForPresentationTime(
            previousSyncPtsUs,
            48_000
        )

        assertEquals(1_960L, targetAbsoluteFrame)
        assertEquals(previousSyncAbsoluteFrame, decodedAbsoluteFrame)
        assertEquals(160L, targetAbsoluteFrame - decodedAbsoluteFrame)
        assertEquals(20_833L, framesToMicroseconds(targetAbsoluteFrame - 960L, 48_000))
    }

    @Test
    fun signedFrameConversionsStayExactAtFrameBoundaries() {
        assertEquals(1_000L, microsecondsToFramesCeil(20_833L, 48_000))
        assertEquals(-1_000L, microsecondsToFramesNearest(-20_833L, 48_000))
        assertEquals(20_833L, framesToMicroseconds(1_000L, 48_000))
    }

    @Test
    fun sinkResultIsAllocationFreePackedProtocolData() {
        val result = NativePcmSinkResult.accepting(4_096, retryDelayUs = 2_500L)

        assertEquals(4_096, result.acceptedBytes)
        assertEquals(2_500L, result.retryDelayUs)
    }

    @Test
    fun pausedSinkUsesAnExplicitWakeWithNoThirtySecondDeadline() {
        val pausedForAnyDuration = NativePcmSinkResult.backpressuredUntilSignal()
        val actualFailure = NativePcmSinkResult.permanentlyStalled()

        assertEquals(0, pausedForAnyDuration.acceptedBytes)
        assertEquals(0L, pausedForAnyDuration.retryDelayUs)
        assertEquals(true, pausedForAnyDuration.waitsForSignal)
        assertEquals(false, pausedForAnyDuration.reportsPermanentStall)
        assertEquals(true, actualFailure.reportsPermanentStall)
        assertEquals(false, actualFailure.waitsForSignal)
    }

}
