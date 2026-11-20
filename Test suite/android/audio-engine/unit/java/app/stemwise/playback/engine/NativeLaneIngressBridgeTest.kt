package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeLaneIngressBridgeTest {
    @Test
    fun formatGenerationAndDirectWindowReachNativeWithoutPerFrameCalls() {
        val backend = FakeBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val sink = bank.configureLane(0, 44_100, 1_000)
            val storage = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
            repeat(8) { storage.putFloat(it * 4, it / 10f) }
            val view = storage.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply {
                position(8)
                limit(32)
            }
            backend.nextStatus = NativeLaneIngressSink.STATUS_ACCEPTED
            backend.nextAcceptedFrames = 3

            val result = sink.offer(block(
                data = view,
                format = NativePcmFormat(44_100, 2, NativePcmEncoding.PCM_FLOAT),
                presentationTimeUs = 123_456,
                seekGeneration = 7
            ))

            assertEquals(24, result.acceptedBytes)
            assertEquals(0L, result.retryDelayUs)
            assertEquals(1, backend.offerCalls)
            assertEquals(0, backend.lastLaneIndex)
            assertEquals(1_007L, backend.lastGeneration)
            assertEquals(123_456L, backend.lastPresentationTimeUs)
            assertEquals(NativeLaneIngressSink.ENCODING_FLOAT_32, backend.lastEncoding)
            assertEquals(2, backend.lastChannels)
            assertEquals(8, backend.lastByteOffset)
            assertEquals(3, backend.lastFrameCount)
            assertTrue(backend.lastBuffer === view)
        }
    }

    @Test
    fun partialBackpressureAndStaleResultsObeyDecoderSinkProtocol() {
        val backend = FakeBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val sink = bank.configureLane(1, 48_000, 50)
            val pcm = directReadOnly(16 * Short.SIZE_BYTES)
            val block = block(
                pcm,
                NativePcmFormat(48_000, 1, NativePcmEncoding.PCM_16_BIT),
                presentationTimeUs = 0,
                seekGeneration = 0
            )

            backend.nextStatus = NativeLaneIngressSink.STATUS_PARTIAL
            backend.nextAcceptedFrames = 5
            val partial = sink.offer(block)
            assertEquals(10, partial.acceptedBytes)
            assertEquals(500L, partial.retryDelayUs)
            assertFalse(partial.waitsForSignal)

            backend.nextStatus = NativeLaneIngressSink.STATUS_BACKPRESSURED
            backend.nextAcceptedFrames = 0
            val backpressured = sink.offer(block)
            assertEquals(0, backpressured.acceptedBytes)
            assertEquals(500L, backpressured.retryDelayUs)
            assertFalse(backpressured.waitsForSignal)

            backend.nextStatus = NativeLaneIngressSink.STATUS_STALE_GENERATION
            backend.nextAcceptedFrames = block.frameCount
            val stale = sink.offer(block)
            assertEquals(block.data.remaining(), stale.acceptedBytes)
            assertEquals(0L, stale.retryDelayUs)
        }
    }

    @Test
    fun explicitGenerationAndDiscontinuityUseTheSamePositiveMapping() {
        val backend = FakeBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val sink = bank.configureLane(6, 8_000, 9_000)

            assertTrue(sink.advanceDecoderGeneration(11))
            assertEquals(9_011L, backend.lastGeneration)
            assertTrue(sink.markDiscontinuity(11))
            assertEquals(9_011L, backend.lastGeneration)
            backend.nextEndOfStreamStatus = 0
            assertEquals(
                NativeLaneEndOfStreamStatus.ACCEPTED,
                sink.markEndOfStream(11, 1_234)
            )
            assertEquals(9_011L, backend.lastGeneration)
            assertEquals(1_234L, backend.lastFinalSourceFrameExclusive)
        }
    }

    @Test
    fun eosNativeStatusesAreExplicitAndBoundaryIsNonNegative() {
        val backend = FakeBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            val sink = bank.configureLane(0, 48_000, 100)
            val expected = listOf(
                NativeLaneEndOfStreamStatus.ACCEPTED,
                NativeLaneEndOfStreamStatus.ALREADY_MARKED,
                NativeLaneEndOfStreamStatus.STALE_GENERATION,
                NativeLaneEndOfStreamStatus.BOUNDARY_MISMATCH,
                NativeLaneEndOfStreamStatus.INVALID_ARGUMENT
            )
            expected.forEachIndexed { code, status ->
                backend.nextEndOfStreamStatus = code
                assertEquals(status, sink.markEndOfStream(2, 55))
                assertEquals(102L, backend.lastGeneration)
                assertEquals(55L, backend.lastFinalSourceFrameExclusive)
            }
            assertFails<IllegalArgumentException> { sink.markEndOfStream(0, -1) }
        }
    }

    @Test
    fun twoResidentBanksKeepOneActivePackagePlusOneCandidateAndOnePreparationLease() {
        val laneCount = NativeSongLaneIngressBank.LANE_COUNT
        assertEquals(10, laneCount)
        val backend = FakeBackend()
        NativeSongLaneIngressBank(64, 32, backend).use { active ->
            NativeSongLaneIngressBank(64, 32, backend).use { candidate ->
                repeat(laneCount) { lane ->
                    active.configureLane(lane, 48_000, 100L + lane)
                    candidate.configureLane(lane, 48_000, 200L + lane)
                }
                assertEquals(2 * laneCount, backend.configuredLaneCount())

                val leases = NativeDecoderLeaseCoordinator()
                val activeLeases = (0 until laneCount).map { lane ->
                    requireNotNull(
                        leases.tryAcquire(NativeSongBankRole.ACTIVE, lane, "active-$lane")
                    )
                }
                val candidateOriginal = requireNotNull(
                    leases.tryAcquire(NativeSongBankRole.CANDIDATE, 0, "candidate-original")
                )
                val loopPreparation = requireNotNull(
                    leases.tryAcquire(
                        NativeSongBankRole.LOOP_PREPARATION,
                        0,
                        "repeat-head-original"
                    )
                )
                assertEquals(NativeDecoderLeaseSnapshot(laneCount, 1, 1), leases.snapshot())
                assertNull(
                    leases.tryAcquire(NativeSongBankRole.CANDIDATE, 1, "candidate-stem-1")
                )
                assertNull(
                    leases.tryAcquire(
                        NativeSongBankRole.LOOP_PREPARATION,
                        1,
                        "parallel-loop-preparation"
                    )
                )
                assertNull(
                    leases.tryAcquire(NativeSongBankRole.ACTIVE, 0, "duplicate-active-lane")
                )

                assertTrue(leases.release(candidateOriginal))
                repeat(laneCount - 1) { stemIndex ->
                    val sequential = requireNotNull(
                        leases.tryAcquire(
                            NativeSongBankRole.CANDIDATE,
                            stemIndex + 1,
                            "candidate-stem-$stemIndex"
                        )
                    )
                    assertEquals(laneCount + 2, leases.snapshot().totalDecoderCount)
                    assertTrue(leases.release(sequential))
                }
                assertEquals(NativeDecoderLeaseSnapshot(laneCount, 0, 1), leases.snapshot())
                assertTrue(leases.release(loopPreparation))
                assertEquals(NativeDecoderLeaseSnapshot(laneCount, 0, 0), leases.snapshot())
                activeLeases.forEach { assertTrue(leases.release(it)) }
                assertEquals(NativeDecoderLeaseSnapshot(0, 0, 0), leases.snapshot())
            }
        }
        assertEquals(2, backend.closedBanks)
    }

    @Test
    fun fourHundredSongCandidatePressureNeverRetainsMoreThanOneCandidateDecoder() {
        val laneCount = NativeSongLaneIngressBank.LANE_COUNT
        val leases = NativeDecoderLeaseCoordinator()
        val active = (0 until laneCount).map { lane ->
            requireNotNull(leases.tryAcquire(NativeSongBankRole.ACTIVE, lane, "active-$lane"))
        }
        val loopPreparation = requireNotNull(
            leases.tryAcquire(NativeSongBankRole.LOOP_PREPARATION, 0, "repeat-head")
        )
        var maximumObserved = 0
        repeat(400) { song ->
            repeat(laneCount) { lane ->
                val candidate = requireNotNull(
                    leases.tryAcquire(
                        NativeSongBankRole.CANDIDATE,
                        lane,
                        "candidate-$song-$lane"
                    )
                )
                maximumObserved = maxOf(maximumObserved, leases.snapshot().totalDecoderCount)
                assertNull(
                    leases.tryAcquire(
                        NativeSongBankRole.CANDIDATE,
                        (lane + 1) % laneCount,
                        "illegal-parallel-$song-$lane"
                    )
                )
                assertTrue(leases.release(candidate))
            }
        }
        assertEquals(laneCount + 2, maximumObserved)
        assertEquals(NativeDecoderLeaseSnapshot(laneCount, 0, 1), leases.snapshot())
        assertTrue(leases.release(loopPreparation))
        active.forEach { assertTrue(leases.release(it)) }
    }

    @Test
    fun releaseAndCloseInvalidateSinksBeforeNativeStorageIsFreed() {
        val backend = FakeBackend()
        val bank = NativeSongLaneIngressBank(64, 32, backend)
        val released = bank.configureLane(2, 48_000, 1)
        assertTrue(bank.releaseLane(2))
        assertFalse(bank.releaseLane(2))
        assertEquals(1, backend.releasedLanes)
        assertFails<IllegalStateException> {
            released.offer(block(
                directReadOnly(4),
                NativePcmFormat(48_000, 1, NativePcmEncoding.PCM_16_BIT),
                0,
                0
            ))
        }

        val closed = bank.configureLane(3, 48_000, 2)
        bank.close()
        bank.close()
        assertEquals(1, backend.closedBanks)
        assertFails<IllegalStateException> {
            closed.advanceDecoderGeneration(1)
        }
    }

    @Test
    fun readableFramesQueryRetainsNativeStorageUntilLaneReleaseCanProceed() {
        readableFramesQueryRetainsNativeStorage(closeBank = false)
    }

    @Test
    fun readableFramesQueryRetainsNativeStorageUntilBankCloseCanProceed() {
        readableFramesQueryRetainsNativeStorage(closeBank = true)
    }

    private fun readableFramesQueryRetainsNativeStorage(closeBank: Boolean) {
        val backend = FakeBackend()
        val bank = NativeSongLaneIngressBank(64, 32, backend)
        bank.configureLane(0, 48_000, 1)
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val retirementStarted = CountDownLatch(1)
        val storageRetired = AtomicBoolean(false)
        val readable = AtomicInteger(-1)
        val readFailure = AtomicReference<Throwable?>(null)
        val retirementFailure = AtomicReference<Throwable?>(null)
        backend.onReadableSourceFrames = {
            readEntered.countDown()
            check(releaseRead.await(2, TimeUnit.SECONDS)) { "Readable query was not released" }
            check(!storageRetired.get()) { "Native storage was freed during its readable-frame query" }
            7
        }
        backend.onRetireNativeStorage = { storageRetired.set(true) }
        val reader = Thread {
            try { readable.set(bank.readableSourceFrames(0)) }
            catch (failure: Throwable) { readFailure.set(failure) }
        }
        val retirement = Thread {
            retirementStarted.countDown()
            try { if (closeBank) bank.close() else check(bank.releaseLane(0)) }
            catch (failure: Throwable) { retirementFailure.set(failure) }
        }
        try {
            reader.start()
            assertTrue("Query must enter the actual backend", readEntered.await(2, TimeUnit.SECONDS))
            retirement.start()
            assertTrue(retirementStarted.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (retirement.isAlive && retirement.state != Thread.State.BLOCKED && !storageRetired.get()) {
                check(System.nanoTime() < deadline) { "Retirement did not reach the bank ownership boundary" }
                Thread.yield()
            }
            assertFalse("Retirement cannot free storage while its native query is executing", storageRetired.get())
            assertEquals(Thread.State.BLOCKED, retirement.state)
        } finally {
            releaseRead.countDown()
            reader.join(2_000)
            retirement.join(2_000)
            assertFalse("Readable query must retire", reader.isAlive)
            assertFalse("Storage retirement must retire", retirement.isAlive)
            bank.close()
        }
        assertNull(readFailure.get())
        assertNull(retirementFailure.get())
        assertEquals(7, readable.get())
        assertTrue(storageRetired.get())
        assertEquals(if (closeBank) 0 else 1, backend.releasedLanes)
        assertEquals(1, backend.closedBanks)
    }

    @Test
    fun invalidTopologyAndFormatChangesFailBeforeNativeOffer() {
        val backend = FakeBackend()
        assertFails<IllegalArgumentException> {
            NativeSongLaneIngressBank(63, 32, backend)
        }
        NativeSongLaneIngressBank(64, 32, backend).use { bank ->
            assertFails<IllegalArgumentException> { bank.configureLane(18, 48_000, 1) }
            val sink = bank.configureLane(0, 48_000, 1)
            assertFails<IllegalArgumentException> {
                sink.offer(block(
                    directReadOnly(4),
                    NativePcmFormat(44_100, 1, NativePcmEncoding.PCM_16_BIT),
                    0,
                    0
                ))
            }
            assertEquals(0, backend.offerCalls)
        }
    }

    private fun block(
        data: ByteBuffer,
        format: NativePcmFormat,
        presentationTimeUs: Long,
        seekGeneration: Long
    ): NativePcmBlock = NativePcmBlock().reset(
        data = data,
        format = format,
        presentationTimeUs = presentationTimeUs,
        seekGeneration = seekGeneration,
        firstTimelineFrame = microsecondsToFramesNearest(
            presentationTimeUs,
            format.sampleRateHz
        )
    )

    private fun directReadOnly(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .apply { limit(bytes) }
            .asReadOnlyBuffer()
            .order(ByteOrder.nativeOrder())

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.simpleName}",
            failure is T
        )
    }

    private class FakeBackend : NativeLaneIngressBackend {
        private val nextHandle = AtomicLong(0)
        private val configured = mutableMapOf<Long, MutableSet<Int>>()
        var nextStatus = NativeLaneIngressSink.STATUS_ACCEPTED
        var nextAcceptedFrames = 0
        var offerCalls = 0
        var lastLaneIndex = -1
        var lastGeneration = -1L
        var lastPresentationTimeUs = -1L
        var lastEncoding = -1
        var lastChannels = -1
        var lastByteOffset = -1
        var lastFrameCount = -1
        var lastFinalSourceFrameExclusive = -1L
        var lastBuffer: ByteBuffer? = null
        var releasedLanes = 0
        var closedBanks = 0
        var nextEndOfStreamStatus = 0
        var onReadableSourceFrames: (() -> Int)? = null
        var onRetireNativeStorage: (() -> Unit)? = null

        override fun createSongBank(
            queueCapacityFrames: Int,
            maximumInputQuantumFrames: Int
        ): Long = nextHandle.incrementAndGet().also { configured[it] = mutableSetOf() }

        override fun configureLane(
            bankHandle: Long,
            laneIndex: Int,
            sourceRateHz: Int,
            initialGeneration: Long
        ): Boolean = configured.getValue(bankHandle).add(laneIndex)

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
            offerCalls++
            lastLaneIndex = laneIndex
            lastGeneration = generation
            lastPresentationTimeUs = presentationTimeUs
            lastEncoding = encodingCode
            lastChannels = channelCount
            lastByteOffset = byteOffset
            lastFrameCount = frameCount
            lastBuffer = directBuffer
            return pack(nextStatus, nextAcceptedFrames)
        }

        override fun advanceGeneration(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long
        ): Boolean {
            lastGeneration = generation
            return true
        }

        override fun markDiscontinuity(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long
        ): Boolean {
            lastGeneration = generation
            return true
        }

        override fun markEndOfStream(
            bankHandle: Long,
            laneIndex: Int,
            generation: Long,
            finalSourceFrameExclusive: Long
        ): Int {
            lastGeneration = generation
            lastFinalSourceFrameExclusive = finalSourceFrameExclusive
            return nextEndOfStreamStatus
        }

        override fun readableSourceFrames(bankHandle: Long, laneIndex: Int): Int =
            onReadableSourceFrames?.invoke() ?: 0

        override fun releaseLane(bankHandle: Long, laneIndex: Int) {
            onRetireNativeStorage?.invoke()
            if (configured.getValue(bankHandle).remove(laneIndex)) releasedLanes++
        }

        override fun closeSongBank(bankHandle: Long) {
            onRetireNativeStorage?.invoke()
            if (configured.remove(bankHandle) != null) closedBanks++
        }

        fun configuredLaneCount(): Int = configured.values.sumOf { it.size }

        private fun pack(status: Int, acceptedFrames: Int): Long =
            (status.toLong() shl Int.SIZE_BITS) or acceptedFrames.toLong()
    }
}
