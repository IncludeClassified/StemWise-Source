package app.stemwise.playback.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class EngineStemMixPreviewMailboxTest {
    @Test
    fun disjointLaneOffersRetainEndpointsWithoutRevivingReplacedLeases() {
        val mailbox = EngineStemMixPreviewMailbox()
        val leases = beginAllLanes(mailbox)
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .25f }
        assertTrue(mailbox.offer(leases[0], 1, gains))
        gains[1] = .75f
        assertTrue(mailbox.offer(leases[1], 2, gains))
        val latest = EngineStemMixPreviewSnapshot()
        assertTrue(mailbox.readLatestInto(latest))
        assertEquals(3, latest.changedMask)
        assertEquals(.25f, latest.gains[0], 0f)
        assertEquals(.75f, latest.gains[1], 0f)

        assertTrue(mailbox.end(leases[0]))
        requireNotNull(mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, 0))
        assertTrue(mailbox.offer(leases[1], 2, gains))
        assertTrue(mailbox.readLatestInto(latest))
        assertEquals(2, latest.changedMask)
    }

    @Test
    fun hundredThousandOffersRemainOneLatestUntornPublication() {
        val mailbox = EngineStemMixPreviewMailbox()
        val leases = beginAllLanes(mailbox)
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
        repeat(100_000) { index ->
            val value = if (index and 1 == 0) .25f else .75f
            gains.fill(value)
            assertTrue(
                mailbox.offer(
                    lease = leases[0],
                    changedMask = ENGINE_STEM_MIX_PREVIEW_ALL_LANES_MASK,
                    gains = gains
                )
            )
        }

        val snapshot = EngineStemMixPreviewSnapshot()
        assertTrue(mailbox.readLatestInto(snapshot))
        assertEquals(100_000L, snapshot.sequence)
        assertTrue(snapshot.gains.all { it == .75f })
        assertFalse(mailbox.readLatestInto(snapshot))
    }

    @Test
    fun producerAndConsumerNeverObserveMixedLaneTuple() {
        assertConcurrentPublication()
    }

    @Test
    fun concurrentPublicationRejectsAConsumerThatNeverReads() {
        val failure = assertThrows(AssertionError::class.java) {
            assertConcurrentPublication(suppressReads = true, consumerWatchdogMs = 250L)
        }
        assertEquals("Consumer did not finish naturally", failure.message)
    }

    private fun assertConcurrentPublication(
        suppressReads: Boolean = false,
        consumerWatchdogMs: Long = TEST_WATCHDOG_MS
    ) {
        val mailbox = EngineStemMixPreviewMailbox()
        val leases = beginAllLanes(mailbox)
        val start = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val abort = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        var consumed = 0
        var observedSequence = 0L
        val producer = thread(name = "stem-preview-producer", isDaemon = true) {
            try {
                val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT)
                start.await()
                repeat(100_000) { index ->
                    if (abort.get()) return@thread
                    gains.fill(if (index and 1 == 0) .125f else .875f)
                    check(
                        mailbox.offer(
                            leases[0],
                            ENGINE_STEM_MIX_PREVIEW_ALL_LANES_MASK,
                            gains
                        )
                    )
                }
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
                abort.set(true)
            } finally {
                finished.set(true)
            }
        }
        val consumer = thread(name = "stem-preview-consumer", isDaemon = true) {
            try {
                val snapshot = EngineStemMixPreviewSnapshot()
                start.await()
                while (!abort.get() && (!finished.get() || snapshot.sequence < 100_000L)) {
                    if (suppressReads || !mailbox.readLatestInto(snapshot)) {
                        Thread.yield()
                        continue
                    }
                    val index = (snapshot.sequence - 1L).toInt()
                    val expected = if (index and 1 == 0) .125f else .875f
                    check(snapshot.gains.all { it == expected }) {
                        "Observed a torn gain tuple at sequence ${snapshot.sequence}"
                    }
                    consumed++
                    observedSequence = snapshot.sequence
                }
            } catch (caught: Throwable) {
                failure.compareAndSet(null, caught)
                abort.set(true)
            }
        }

        try {
            start.countDown()
            producer.join(TEST_WATCHDOG_MS)
            consumer.join(consumerWatchdogMs)
            assertFalse("Producer did not finish naturally", producer.isAlive)
            assertFalse("Consumer did not finish naturally", consumer.isAlive)
            assertTrue("Consumer observed no publications", consumed > 0)
            assertEquals(100_000L, observedSequence)
        } finally {
            abort.set(true)
            start.countDown()
            producer.interrupt()
            consumer.interrupt()
            producer.join(CLEANUP_WATCHDOG_MS)
            consumer.join(CLEANUP_WATCHDOG_MS)
        }
        assertFalse("Producer did not finish", producer.isAlive)
        assertFalse("Consumer did not finish", consumer.isAlive)
        assertNull(failure.get()?.stackTraceToString(), failure.get())
    }

    @Test
    fun terminalLaneEpochRejectsEveryQueuedOrLaterPreviewForThatLane() {
        val mailbox = EngineStemMixPreviewMailbox()
        val lease = requireNotNull(
            mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, laneIndex = 2)
        )
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .5f }
        assertTrue(mailbox.offer(lease, 1 shl 2, gains))
        assertTrue(mailbox.end(lease))
        assertEquals(0L, mailbox.activeLaneEpoch(lease.authority, 2))
        assertFalse(mailbox.offer(lease, 1 shl 2, gains))

        val queued = EngineStemMixPreviewSnapshot()
        assertTrue(mailbox.readLatestInto(queued))
        assertEquals(lease.laneEpoch, queued.laneEpochs[2])
        assertEquals(0L, mailbox.activeLaneEpoch(requireNotNull(queued.authority), 2))
    }

    @Test
    fun endingOneLaneDoesNotCancelAnotherHeldPointer() {
        val mailbox = EngineStemMixPreviewMailbox()
        val first = requireNotNull(
            mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, laneIndex = 0)
        )
        val second = requireNotNull(
            mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, laneIndex = 1)
        )
        assertTrue(first.authority === second.authority)
        assertTrue(mailbox.end(first))
        assertEquals(0, mailbox.activeMask(second.authority) and 1)
        assertEquals(1 shl 1, mailbox.activeMask(second.authority))

        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .6f }
        assertTrue(mailbox.offer(second, 1 shl 1, gains))
        assertFalse(mailbox.offer(first, 1, gains))
    }

    @Test
    fun lastAdmittedLaneKeepsItsOwnGainAndRetirementEpoch() {
        val mailbox = EngineStemMixPreviewMailbox()
        val first = requireNotNull(mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, 0))
        val last = requireNotNull(mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1))
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .5f }
        gains[ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1] = .17f
        assertTrue(mailbox.offer(last, 1 shl (ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1), gains))
        val snapshot = EngineStemMixPreviewSnapshot()
        assertTrue(mailbox.readLatestInto(snapshot))
        assertEquals(1 shl (ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1), snapshot.changedMask)
        assertEquals(.17f, snapshot.gains[ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1], 0f)
        assertTrue(mailbox.end(last))
        assertEquals(1, mailbox.activeMask(first.authority))
        assertFalse(mailbox.offer(last, 1 shl (ENGINE_STEM_MIX_PREVIEW_LANE_COUNT - 1), gains))
        assertTrue(mailbox.offer(first, 1, gains))
    }

    @Test
    fun replacingOwnerInvalidatesEveryPriorLease() {
        val mailbox = EngineStemMixPreviewMailbox()
        val old = requireNotNull(
            mailbox.begin(token(1L), ownership(1L), PACKAGE_SIGNATURE, 1L, laneIndex = 0)
        )
        val replacement = requireNotNull(
            mailbox.begin(token(2L), ownership(2L), "replacement", 2L, laneIndex = 0)
        )
        assertFalse(old.authority === replacement.authority)
        assertEquals(0, mailbox.activeMask(old.authority))
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .4f }
        assertFalse(mailbox.offer(old, 1, gains))
        assertTrue(mailbox.offer(replacement, 1, gains))
    }

    @Test
    fun newerRequestRevisionReplacesAuthorityEvenWhenPackageSignatureIsIdentical() {
        val mailbox = EngineStemMixPreviewMailbox()
        val old = requireNotNull(
            mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 11L, laneIndex = 0)
        )
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { .4f }
        assertTrue(mailbox.offer(old, 1, gains))

        val replacement = requireNotNull(
            mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 12L, laneIndex = 0)
        )
        assertFalse(old.authority === replacement.authority)
        assertEquals(12L, replacement.authority.packageRequestRevision)
        assertEquals(0, mailbox.activeMask(old.authority))
        assertFalse(mailbox.offer(old, 1, gains))
        assertTrue(mailbox.offer(replacement, 1, gains))

        val latest = EngineStemMixPreviewSnapshot()
        assertTrue(mailbox.readLatestInto(latest))
        assertTrue(latest.authority === replacement.authority)
        assertEquals(2L, latest.sequence)
    }

    private fun beginAllLanes(mailbox: EngineStemMixPreviewMailbox): List<EngineStemMixPreviewLease> =
        List(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { lane ->
            requireNotNull(mailbox.begin(token(), ownership(), PACKAGE_SIGNATURE, 1L, lane))
        }

    private fun token(value: Long = 71L) = EngineSelectionToken(value)
    private fun ownership(value: Long = 71L) = NativeRuntimeOwnership(value, generation = 103L)

    private companion object {
        const val PACKAGE_SIGNATURE = "source-v1[test]"
        const val TEST_WATCHDOG_MS = 10_000L
        const val CLEANUP_WATCHDOG_MS = 1_000L
    }
}
