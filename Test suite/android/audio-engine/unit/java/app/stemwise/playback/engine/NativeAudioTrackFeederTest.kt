package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeAudioTrackFeederTest {
    @Test fun allocationFailureReportsInitializationAndCloseTerminatesWithoutOpeningASink() {
        for (failedAllocation in 1..3) {
            val engine = Engine()
            val allocations = AtomicInteger()
            val expected = OutOfMemoryError("injected feeder buffer allocation")
            val feeder = NativeAudioTrackFeeder(engine, { error("Allocation failure must precede sink opening") },
                allocateBuffer = { bytes ->
                    if (allocations.incrementAndGet() == failedAllocation) throw expected
                    ByteBuffer.allocateDirect(bytes)
                }, setAudioPriority = {})
            val failure = assertThrows(IllegalStateException::class.java) { feeder.start() }
            assertSame(expected, failure.cause)
            feeder.close()
            assertTrue(feeder.isInitialized)
            assertTrue(feeder.isRetired)
            assertEquals(1, engine.failures.get())
            assertEquals(0, engine.discards.get())
            assertTrue(engine.configurations.isEmpty())
        }
    }

    @Test fun freshPlayAndPhysicalRestartRefillBeforePotentiallyBlockingObservations() {
        for (targetPlay in 1..2) {
            val parked = CountDownLatch(1)
            val wake = java.util.concurrent.Semaphore(0)
            val engine = Engine().apply {
                repeat(if (targetPlay == 1) 5 else 4) { blocks.add(Block(512, it + 1, false)) }
                idleAwait = { parked.countDown(); check(wake.tryAcquire(2, TimeUnit.SECONDS)) }
                wakeControl = { wake.release() }
            }
            val actual = Sink(engine, bufferFrames = 2_048).apply {
                repeat(if (targetPlay == 1) 5 else 4) { writes.add(512) }
            }
            val awaitingRefill = AtomicBoolean(false)
            val firstRefill = CountDownLatch(1)
            val prematureObservation = CountDownLatch(1)
            val releaseObservation = CountDownLatch(1)
            val sink = object : NativeAudioTrackFeeder.Sink by actual {
                override fun play() {
                    actual.play()
                    if (actual.plays.get() == targetPlay) {
                        // The real platform can consume most of the prefill before play returns.
                        actual.head = actual.framesAtPlay - 128L
                        awaitingRefill.set(true)
                    }
                }
                override fun write(pcm: ByteBuffer): Int {
                    val bytes = actual.write(pcm)
                    if (bytes > 0 && awaitingRefill.compareAndSet(true, false)) firstRefill.countDown()
                    return bytes
                }
                override fun observe(values: IntArray, position: LongArray) {
                    if (awaitingRefill.get()) {
                        prematureObservation.countDown()
                        check(releaseObservation.await(2, TimeUnit.SECONDS))
                    }
                    actual.observe(values, position)
                }
            }
            val feeder = NativeAudioTrackFeeder(engine, { sink }, setAudioPriority = {})
            try {
                feeder.start()
                if (targetPlay == 2) {
                    await { actual.plays.get() == 1 }
                    engine.sessionActive.set(false); engine.playing.set(false)
                    assertTrue(parked.await(1, TimeUnit.SECONDS))
                    actual.head = actual.samples.size / 2L
                    repeat(5) { engine.blocks.add(Block(512, it + 5, false)); actual.writes.add(512) }
                    engine.sessionActive.set(true); engine.playing.set(true); engine.wake()
                }
                assertTrue("Play $targetPlay must refill before a potentially blocking observation",
                    firstRefill.await(1, TimeUnit.SECONDS))
                assertEquals("Observation ran before the accepted refill", 1L, prematureObservation.count)
                val blocks = if (targetPlay == 1) 5 else 9
                await { engine.accepted.size == blocks }
                assertEquals((1..blocks).flatMap { expected(it, 512) }, snapshot(actual.samples))
                assertEquals((1..blocks).map { it.toLong() to 512 }, snapshot(engine.accepted))
                assertEquals(2_048L, actual.queuedFramesAtPlay)
                assertNull(feeder.failureCause())
            } finally { releaseObservation.countDown(); feeder.close() }
            assertTrue(actual.closed.get())
        }
    }

    @Test fun positiveWritesRefillPromptlyWhenPlaybackHeadObservationLagsActualConsumption() {
        for (progress in listOf("accepted", "waiting-for-source", "backpressured")) {
            val blocks = if (progress == "waiting-for-source") 4 else 5
            val writes = if (progress == "accepted") 5 else 4
            val engine = Engine().apply { repeat(blocks) { this.blocks.add(Block(512, 1, false)) } }
            val sink = Sink(engine, bufferFrames = 2_048).apply { repeat(writes) { this.writes.add(512) } }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val observedWait = AtomicLong(-1L)
            val feeder = NativeAudioTrackFeeder(engine, { sink },
                park = { nanos ->
                    // The fifth attempt uses a deliberately lagging zero playback head.
                    if (engine.sourcePrepareCalls.get() == 5 && observedWait.compareAndSet(-1L, nanos)) {
                        entered.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                    } else LockSupport.parkNanos(nanos)
                }, setAudioPriority = {})
            try {
                feeder.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertEquals(0L, sink.head)
                assertEquals(writes * 512, sink.samples.size / 2)
                assertEquals("Healthy writes refill promptly; starvation and sink backpressure use cadence: $progress",
                    if (progress == "accepted") 500_000L else 5_000_000L, observedWait.get())
                assertNull(feeder.failureCause())
            } finally { release.countDown(); feeder.close() }
        }
    }

    @Test fun idleControlWaitStopsPollingAndCloseWakesTheSameOwner() {
        val entered = CountDownLatch(1)
        val release = java.util.concurrent.Semaphore(0)
        val engine = Engine().apply {
            sessionActive.set(false); playing.set(false)
            idleAwait = { entered.countDown(); check(release.tryAcquire(2, TimeUnit.SECONDS)) }
            wakeControl = { release.release() }
        }
        val sink = Sink(engine)
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val iterations = engine.controlPolls.get()
            assertFalse(CountDownLatch(1).await(30, TimeUnit.MILLISECONDS))
            assertEquals(iterations, engine.controlPolls.get())
            engine.sessionActive.set(true); engine.playing.set(true)
            engine.blocks.add(Block(512, 1, false)); sink.writes.add(512)
            engine.wake()
            await { sink.plays.get() == 1 }
            engine.sessionActive.set(false); engine.playing.set(false); engine.wake()
            await { !engine.active.get() }
        } finally { feeder.close() }
        assertTrue(sink.closed.get())
        assertNull(feeder.failureCause())
    }

    @Test fun measuredClampedBufferAndThresholdDrivePrefillAndOverBudgetFailsClosed() {
        val engine = Engine().apply { repeat(6) { blocks.add(Block(512, 1, false)) } }
        val sink = Sink(engine, bufferFrames = 3_072, startThresholdFrames = 1_024).apply { repeat(6) { writes.add(512) } }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            assertEquals(3_072L, sink.queuedFramesAtPlay)
            assertEquals(1_024, engine.configurations.single().startThresholdFrames)
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
        for ((buffer, threshold) in listOf(4_801 to 512, 2_048 to 0, 2_048 to 2_049)) {
            val rejectedEngine = Engine()
            val rejectedSink = Sink(rejectedEngine, bufferFrames = buffer, startThresholdFrames = threshold)
            val rejected = feeder(rejectedEngine, rejectedSink)
            try {
                assertThrows(IllegalStateException::class.java) { rejected.start() }
                assertTrue(rejectedEngine.configurations.isEmpty())
            } finally { rejected.close() }
            assertTrue(rejectedSink.closed.get())
        }
    }

    @Test
    fun normalPerformanceModePreservesTheActualConfiguration() {
        val engine = Engine().apply { blocks.add(Block(512, 1, false)) }
        val sink = Sink(engine, performanceMode = 0).apply { writes.add(512) }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            assertSame(sink.configuration, engine.configurations.single())
            assertEquals(0, engine.configurations.single().performanceMode)
            assertEquals(listOf(1L to 512), snapshot(engine.accepted))
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun powerSavingAndUnknownPerformanceModesFailBeforeNativeConfiguration() {
        for (mode in listOf(-1, 2, 42)) {
            val engine = Engine()
            val sink = Sink(engine, performanceMode = mode)
            val feeder = feeder(engine, sink)
            try {
                assertThrows(IllegalStateException::class.java) { feeder.start() }
                assertTrue(engine.configurations.isEmpty())
                assertTrue(engine.accepted.isEmpty())
                assertTrue(engine.failures.get() > 0)
            } finally { feeder.close() }
        }
    }

    @Test
    fun aLargerAcceptedBufferKeepsThe512FrameStartThresholdAndSourceBlock() {
        val engine = Engine().apply { repeat(4) { blocks.add(Block(512, 1, false)) } }
        val sink = Sink(engine, bufferFrames = 2_048).apply { repeat(4) { writes.add(512) } }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            assertEquals(2_048L, sink.queuedFramesAtPlay)
            assertEquals(512, sink.configuration.startThresholdFrames)
            assertEquals(listOf(1L to 512, 2L to 512, 3L to 512, 4L to 512), snapshot(engine.accepted))
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun persistentShortSourceFillsTheActualBufferWithAuthorizedTerminalBlocksBeforePlay() {
        val engine = Engine().apply {
            sessionActive.set(true)
            blocks.add(Block(512, 1, true, sourceFrames = 256))
            repeat(3) { blocks.add(Block(512, 0, true, sourceFrames = 0)) }
        }
        val sink = Sink(engine, bufferFrames = 2_048).apply { repeat(4) { writes.add(512) } }
        val feeder = NativeAudioTrackFeeder(engine, { sink }, setAudioPriority = {})
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            assertEquals("A finite source needs the same persistent startup reserve", 2_048L, sink.queuedFramesAtPlay)
            assertEquals(listOf(1L to 512, 2L to 512, 3L to 512, 4L to 512), snapshot(engine.accepted))
            assertEquals(expected(1, 256) + List(1_792 * 2) { 0f }, snapshot(sink.samples))
            assertTrue("EOF padding comes through Core preparation, not transport authorization", engine.transportAuthorizations.isEmpty())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun persistentTerminalMetadataCannotStartAnUnderfilledQueueWithoutMoreAcceptedPcm() {
        val engine = Engine().apply {
            sessionActive.set(true)
            blocks.add(Block(512, 1, true, sourceFrames = 256))
        }
        val sink = Sink(engine, bufferFrames = 2_048).apply { writes.add(512) }
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            park = { nanos ->
                if (engine.sourcePrepareCalls.get() >= 2) {
                    waiting.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                } else LockSupport.parkNanos(nanos)
            }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            assertEquals("Terminal metadata alone cannot waive the persistent reserve", 0, sink.plays.get())
            assertEquals(listOf(1L to 512), snapshot(engine.accepted))
            assertTrue(engine.transportAuthorizations.isEmpty())
            assertNull(feeder.failureCause())
        } finally { release.countDown(); feeder.close() }
    }

    @Test
    fun resumeRefillsTheCurrentlyQueuedThresholdAfterEarlierFramesHaveDrained() {
        val engine = Engine().apply {
            blocks.add(Block(512, 2, false))
            blocks.add(Block(512, 3, false))
        }
        val sink = Sink(engine).apply { writes.add(512) }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            sink.head = 512L
            await { engine.lastObservedHead.get() == 512L }
            engine.sessionActive.set(false); engine.playing.set(false)
            await { !engine.active.get() }
            sink.writes.add(128)
            engine.sessionActive.set(true); engine.playing.set(true)
            await { snapshot(engine.observations).any { it.submittedFrames == 640L } }
            assertEquals("Historical accepted frames cannot authorize an underfilled resume", 1, sink.plays.get())
            sink.writes.add(384)
            await { sink.plays.get() == 2 }
            assertEquals(512L, sink.queuedFramesAtPlay)
            assertEquals(listOf(1L to 512, 2L to 128, 2L to 384), snapshot(engine.accepted))
            assertEquals(expected(2, 512) + expected(3, 512), snapshot(sink.samples))
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun physicalRestartRefreshesThePausedHeadBeforeAdmittingAPartialWriteAsFullPrefill() {
        val paused = CountDownLatch(1)
        val wake = java.util.concurrent.Semaphore(0)
        val engine = Engine().apply {
            repeat(4) { blocks.add(Block(512, it + 1, false)) }
            idleAwait = { paused.countDown(); check(wake.tryAcquire(2, TimeUnit.SECONDS)) }
            wakeControl = { wake.release() }
        }
        val sink = Sink(engine, bufferFrames = 2_048).apply { repeat(4) { writes.add(512) } }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
            assertEquals(2_048L, sink.queuedFramesAtPlay)
            engine.sessionActive.set(false); engine.playing.set(false)
            assertTrue("The paused feeder must reach its final observation and park",
                paused.await(2, TimeUnit.SECONDS))
            assertEquals(0L, engine.lastObservedHead.get())

            // The platform pause can settle after that observation, while the feeder is asleep.
            sink.head = 1_792L
            repeat(4) { engine.blocks.add(Block(512, it + 5, false)) }
            sink.writes.add(128)
            engine.sessionActive.set(true); engine.playing.set(true); engine.wake()
            await { snapshot(engine.observations).any { it.submittedFrames == 2_176L } }
            assertEquals(1_792L, engine.lastObservedHead.get())
            assertEquals("A stale paused head must not start a physical queue containing only 384 frames",
                1, sink.plays.get())

            sink.writes.addAll(listOf(384, 512, 512, 256))
            await { sink.plays.get() == 2 }
            assertEquals("Restart requires the actual current 2048-frame reserve", 2_048L,
                sink.queuedFramesAtPlay)
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
        assertTrue(sink.closed.get())
    }

    @Test
    fun partialZeroAndRemainderWritesSubmitTheOriginalShortSourceExactlyOnce() {
        val engine = Engine().apply { blocks.add(Block(256, 1, true)) }
        val sink = Sink(engine).apply { writes.addAll(listOf(192, 0, 0, 64)) }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { engine.accepted.size == 2 }
            assertEquals(listOf(192, 64), engine.accepted.map { it.second })
            assertEquals(listOf(1L, 1L), engine.accepted.map { it.first })
            assertEquals(1, engine.prepared.get())
            assertEquals("Repeated zero writes reuse the prepared suffix; accepting a prefix prepares the new suffix",
                2, engine.writePreparations.get())
            assertEquals(expected(1, 256), sink.samples.toList())
            assertEquals("A short prefix needs authorized EOF padding before physical start", 0, sink.plays.get())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun pauseRetainsPartialSuffixAndResumeDoesNotRenderOrReplayThePrefix() {
        val engine = Engine().apply { blocks.add(Block(256, 2, true)) }
        val sink = Sink(engine).apply { writes.add(192) }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { engine.accepted.size == 1 }
            engine.sessionActive.set(false); engine.playing.set(false)
            await { !engine.active.get() }
            assertEquals(192 * 2, sink.samples.size)
            assertEquals(0, sink.plays.get())
            assertEquals(0, engine.discards.get())
            sink.writes.add(64)
            engine.sessionActive.set(true); engine.playing.set(true)
            await { engine.accepted.size == 2 }
            assertEquals(expected(2, 256), sink.samples.toList())
            assertEquals(listOf(192, 64), engine.accepted.map { it.second })
            assertEquals(1, engine.prepared.get())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun resetWaitsForAnInflightWriteThenDiscardsOnlyTheUnacceptedSuffixBeforeRebinding() {
        val engine = Engine().apply {
            blocks.add(Block(256, 3, true))
            blocks.add(Block(128, 4, true))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = Sink(engine).apply {
            writes.add(192)
            holdFirstWrite = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        }
        val replacement = Sink(engine)
        val sinks = LinkedBlockingQueue<Sink>().apply { add(sink); add(replacement) }
        val feeder = NativeAudioTrackFeeder(engine, { sinks.remove() }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            engine.sessionActive.set(false); engine.playing.set(false)
            engine.reset.set(1)
            assertTrue("In-flight writes retain consumer ownership", engine.active.get())
            assertEquals(0L, engine.completedReset.get())
            release.countDown()
            await { engine.completedReset.get() == 1L }
            assertFalse(engine.active.get())
            assertEquals(1, engine.discards.get())
            assertTrue("The replaced physical sink must retire", sink.closed.get())
            assertEquals(0, sink.plays.get())
            replacement.writes.add(128)
            engine.sessionActive.set(true); engine.playing.set(true)
            await { engine.accepted.size == 2 }
            assertEquals(expected(3, 192) + expected(4, 128), snapshot(sink.samples) + snapshot(replacement.samples))
            assertEquals(listOf(1L to 192, 2L to 128), engine.accepted.toList())
            assertNull(feeder.failureCause())
        } finally { release.countDown(); feeder.close() }
    }

    @Test
    fun negativeOrUnalignedPlatformReceiptsNeverBecomeAcceptedFrames() {
        for (bytes in listOf(-6, 3)) {
            val engine = Engine().apply { sessionActive.set(false); playing.set(false); blocks.add(Block(128, 5, true)) }
            val sink = Sink(engine).apply { forcedBytes = bytes }
            val feeder = NativeAudioTrackFeeder(engine, { sink }, setAudioPriority = {})
            try {
                feeder.start()
                engine.sessionActive.set(true); engine.playing.set(true)
                await { feeder.failureCause() != null }
                assertTrue(engine.accepted.isEmpty())
                assertEquals(0, sink.plays.get())
                assertTrue(engine.failures.get() > 0)
            } finally { feeder.close() }
        }
    }

    @Test
    fun freshFeederBaselinesARetiredResetWithoutReopeningItsSink() {
        val engine = Engine().apply {
            reset.set(9L); resetAction.set(0L); pendingSourceTarget.set(0L)
            blocks.add(Block(512, 17, false))
        }
        val sink = Sink(engine).apply { writes.add(512) }
        val feeder = feeder(engine, sink)
        try {
            feeder.start()
            await { engine.accepted.isNotEmpty() || feeder.failureCause() != null }
            assertNull(feeder.failureCause())
            assertEquals(listOf(1L to 512), snapshot(engine.accepted))
            assertEquals(expected(17, 512), snapshot(sink.samples))
            assertEquals(1, engine.configurations.size)
            assertFalse(sink.closed.get())
            assertEquals(0L, engine.completedReset.get())
            assertEquals(0, engine.discards.get())
        } finally { feeder.close() }
    }

    @Test
    fun freshFeederDoesNotBaselineAnAlreadyPendingReset() {
        for (action in listOf(1L, 2L)) {
            val engine = Engine().apply {
                playing.set(false); sessionActive.set(false)
                reset.set(9L); resetAction.set(action); pendingSourceTarget.set(4L)
            }
            val sinks = Collections.synchronizedList(mutableListOf<Sink>())
            val feeder = NativeAudioTrackFeeder(engine, { Sink(engine).also { sinks.add(it) } }, setAudioPriority = {})
            try {
                feeder.start()
                await { feeder.failureCause() != null ||
                    if (action == 1L) engine.completedReset.get() == 9L
                    else snapshot(engine.sourceTransitions) == listOf(9L to 4L) }
                assertNull(feeder.failureCause())
                if (action == 1L) {
                    assertEquals(2, sinks.size)
                    assertTrue(sinks.first().closed.get())
                    assertEquals(9L, engine.completedReset.get())
                } else {
                    assertEquals(1, sinks.size)
                    assertEquals(listOf(9L to 4L), snapshot(engine.sourceTransitions))
                    assertEquals(0L, engine.completedReset.get())
                }
                assertFalse(sinks.last().closed.get())
            } finally { feeder.close() }
        }
    }

    @Test
    fun resetPublishedAfterTheFirstRetiredSnapshotStillExecutes() {
        val engine = Engine().apply {
            playing.set(false); sessionActive.set(false)
            reset.set(9L); resetAction.set(0L); pendingSourceTarget.set(0L)
        }
        val enteredIdle = CountDownLatch(1)
        val releaseIdle = CountDownLatch(1)
        val firstIdle = AtomicBoolean(true)
        engine.idleAwait = {
            if (firstIdle.compareAndSet(true, false)) {
                enteredIdle.countDown()
                check(releaseIdle.await(2, TimeUnit.SECONDS)) { "Initial control snapshot was not released" }
            } else LockSupport.parkNanos(500_000L)
        }
        val sinks = Collections.synchronizedList(mutableListOf<Sink>())
        val feeder = NativeAudioTrackFeeder(engine, { Sink(engine).also { sinks.add(it) } }, setAudioPriority = {})
        try {
            feeder.start()
            await { enteredIdle.count == 0L || feeder.failureCause() != null }
            assertNull(feeder.failureCause())
            assertEquals(0L, enteredIdle.count)
            // The first coherent packet was consumed before this new request becomes visible.
            engine.pendingSourceTarget.set(4L); engine.resetAction.set(1L); engine.reset.set(10L)
            releaseIdle.countDown()
            await { engine.completedReset.get() == 10L || feeder.failureCause() != null }
            assertNull(feeder.failureCause())
            assertEquals(10L, engine.completedReset.get())
            assertEquals(2, sinks.size)
            assertTrue(sinks.first().closed.get())
            assertFalse(sinks.last().closed.get())
        } finally { releaseIdle.countDown(); feeder.close() }
    }

    @Test fun recreationReadinessAndWatchdogClockBelongToTheCurrentPhysicalOpen() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val feeder = NativeAudioTrackFeeder(engine, {
            if (opens.incrementAndGet() == 2) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS)) { "Replacement opener was not released" }
            }
            Sink(engine)
        }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(feeder.isReady)
            assertEquals(0L, feeder.sinkOpeningStartedNanos)
            val requestedAt = System.nanoTime()
            engine.reset.set(1L)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("The original ready latch cannot claim the replacement is ready", feeder.isReady)
            assertTrue("The new attempt gets its own watchdog origin", feeder.sinkOpeningStartedNanos >= requestedAt)
            assertEquals(0L, engine.completedReset.get())
            release.countDown()
            await { engine.completedReset.get() == 1L && feeder.isReady }
            assertEquals(0L, feeder.sinkOpeningStartedNanos)
            assertNull(feeder.failureCause())
        } finally { release.countDown(); feeder.close() }
    }

    @Test
    fun trackRecreationRetiresTheOldSinkAndPreservesItsRealFinalCounterObservation() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val first = Sink(engine).apply { underruns = 4 }
        val second = Sink(engine).apply { underruns = 7 }
        val sinks = LinkedBlockingQueue<Sink>().apply { add(first); add(second) }
        val record = NativeAudioTrackDiagnosticRecord(4_096)
        val feeder = NativeAudioTrackFeeder(engine, {
            val sink = sinks.remove()
            if (sink === second) assertTrue("Old track must retire before a replacement opens", first.closed.get())
            sink
        }, record = record, setAudioPriority = {})
        try {
            feeder.start()
            engine.recreate.set(true)
            engine.reset.set(1)
            await { engine.completedReset.get() == 1L }
            val instances = engine.instances.toList()
            assertEquals(2, instances.size)
            assertTrue(instances.first() > 0L && instances.last() > instances.first())
            assertTrue(snapshot(engine.observations).any { it.instance == instances.first() && it.complete && it.raw == 4 })
            assertTrue(snapshot(engine.observations).any { it.instance == instances.last() && it.raw == 7 })
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
        assertTrue(snapshot(engine.observations).any { it.instance == engine.instances.last() && it.complete && it.raw == 7 })
        val trace = record.snapshot()
        assertTrue(trace.complete)
        val newTrackEntry = trace.events.first { it.instance == engine.instances.last() &&
            it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.ENTER }
        assertFalse("An old track's head cannot become the replacement's observation", newTrackEntry.headValid)
        assertFalse("An old track's raw sample cannot become the replacement's observation", newTrackEntry.rawAvailable)
    }

    @Test
    fun missingFinalUnderrunSampleRemainsUnavailableRatherThanACompleteCleanInterval() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val sink = Sink(engine)
        val feeder = feeder(engine, sink)
        feeder.start()
        sink.underruns = -1
        feeder.close()
        val final = engine.observations.last()
        assertEquals(-1, final.raw)
        assertTrue("A required final sample remains explicit even when its reading is unavailable", final.complete)
        assertTrue(sink.closed.get())
    }

    @Test
    fun throwingPauseStillRetiresTheConsumerAndRecordsAnIncompleteFinalInterval() {
        val engine = Engine().apply { blocks.add(Block(512, 1, false)) }
        val sink = Sink(engine).apply { writes.add(512) }
        val pauseFailure = IllegalStateException("platform pause failed")
        val throwingSink = object : NativeAudioTrackFeeder.Sink by sink {
            override fun pause() { throw pauseFailure }
        }
        val feeder = NativeAudioTrackFeeder(engine, { throwingSink }, setAudioPriority = {})
        try {
            feeder.start()
            await { sink.plays.get() == 1 }
        } finally { feeder.close() }
        assertFalse("A platform pause exception cannot retain native consumer authority", engine.active.get())
        assertEquals(1, engine.discards.get())
        assertTrue(sink.closed.get())
        assertSame(pauseFailure, feeder.failureCause())
        assertTrue(engine.failures.get() > 0)
        assertTrue(engine.observations.last().complete)
        assertEquals("A failed physical pause has no complete final raw interval", -1, engine.observations.last().raw)
    }

    @Test
    fun failedConsumerRetirementCannotAuthorizeDeletionJustBecauseTheThreadJoined() {
        val retirementFailure = IllegalStateException("native retirement refused")
        val engine = Engine().apply {
            blocks.add(Block(512, 1, false))
            deactivateFailure = retirementFailure
        }
        val sink = Sink(engine).apply { writes.add(512) }
        val released = AtomicBoolean(false)
        val retiringSink = object : NativeAudioTrackFeeder.Sink by sink {
            override fun close() { released.set(true) }
        }
        val feeder = NativeAudioTrackFeeder(engine, { retiringSink }, setAudioPriority = {})
        feeder.start()
        await { sink.plays.get() == 1 }
        val closeFailure = assertThrows(IllegalStateException::class.java) { feeder.close() }
        assertTrue(closeFailure.message.orEmpty().contains("native"))
        assertSame(retirementFailure, closeFailure.cause)
        assertTrue("The platform resource must still be released", released.get())
        assertTrue(engine.active.get())
        assertEquals("Unacknowledged consumer authority cannot permit discard", 0, engine.discards.get())
        assertSame(retirementFailure, feeder.failureCause())
        assertTrue(engine.observations.last().complete)
        assertEquals(-1, engine.observations.last().raw)
        assertThrows(IllegalStateException::class.java) { feeder.close() }
    }

    @Test
    fun activationThatThrowsAfterTakingOwnershipStillReceivesTheRetirementFence() {
        val activationFailure = IllegalStateException("activation acknowledgement lost")
        val engine = Engine().apply { activateFailure = activationFailure }
        val sink = Sink(engine)
        val feeder = feeder(engine, sink)
        try {
            runCatching { feeder.start() }.exceptionOrNull()?.let { assertSame(activationFailure, it.cause) }
            await { engine.failures.get() > 0 }
        } finally { feeder.close() }
        assertFalse("Unknown activation must still be explicitly retired", engine.active.get())
        assertEquals(1, engine.discards.get())
        assertTrue(sink.closed.get())
        assertSame(activationFailure, feeder.failureCause())
    }

    @Test
    fun activationAndRetirementFailuresKeepTheOriginalCauseAndAnUnretiredNativeOwner() {
        val activationFailure = IllegalStateException("activation acknowledgement lost")
        val retirementFailure = IllegalStateException("native retirement refused")
        val engine = Engine().apply {
            activateFailure = activationFailure
            deactivateFailure = retirementFailure
        }
        val sink = Sink(engine)
        val released = AtomicBoolean(false)
        val releasingSink = object : NativeAudioTrackFeeder.Sink by sink {
            override fun close() { released.set(true) }
        }
        val feeder = NativeAudioTrackFeeder(engine, { releasingSink }, setAudioPriority = {})
        runCatching { feeder.start() }.exceptionOrNull()?.let { assertSame(activationFailure, it.cause) }
        await { engine.failures.get() > 0 }
        assertThrows(IllegalStateException::class.java) { feeder.close() }
        assertSame(activationFailure, feeder.failureCause())
        assertTrue(activationFailure.suppressed.contains(retirementFailure))
        assertTrue(engine.active.get())
        assertTrue(released.get())
        assertEquals(0, engine.discards.get())
    }

    @Test
    fun replacementFeederUsesANewPositiveTrackIdentityAfterOldConsumerRetirement() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val first = Sink(engine)
        val firstFeeder = feeder(engine, first)
        firstFeeder.start()
        firstFeeder.close()
        assertTrue(first.closed.get())
        val second = Sink(engine)
        val secondFeeder = feeder(engine, second)
        try {
            secondFeeder.start()
            val instances = engine.instances.toList()
            assertEquals(2, instances.size)
            assertTrue(instances.first() > 0L && instances.last() > instances.first())
            assertFalse(secondFeeder.hasFailed)
        } finally { secondFeeder.close() }
        assertTrue(second.closed.get())
    }

    @Test
    fun resetReportsConfirmedZeroPlaybackHeadBeforeAcknowledgingTheNewEpoch() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val sink = Sink(engine).apply { head = 77L }
        val replacement = Sink(engine)
        val sinks = LinkedBlockingQueue<Sink>().apply { add(sink); add(replacement) }
        val feeder = NativeAudioTrackFeeder(engine, { sinks.remove() }, setAudioPriority = {})
        try {
            feeder.start()
            await { engine.lastObservedHead.get() == 77L }
            engine.reset.set(1L)
            await { engine.completedReset.get() == 1L }
            assertEquals(0L, engine.lastObservedHead.get())
            assertTrue("The replaced physical sink must retire", sink.closed.get())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun playbackHeadWrapKeepsTheTimelineAndOnlyANewPhysicalInstanceResetsIt() {
        val position = AudioTrackFramePosition()
        assertEquals(0xffff_fff0L, position.head(0xffff_fff0L))
        assertEquals(0x1_0000_0008L, position.head(8L))
        assertEquals(0xffff_fff8L, position.timestamp(0xffff_fff8L, 0x1_0000_0008L))
        assertEquals(0x1_0000_0004L, position.timestamp(4L, 0x1_0000_0008L))
        assertThrows(IllegalStateException::class.java) { position.head(0L) }
        val replacement = AudioTrackFramePosition()
        assertEquals(0L, replacement.head(0L))
        assertEquals(512L, replacement.head(512L))
    }

    @Test
    fun firstPostPlayRefillPrecedesWaitingAndPreservesObservationBounds() {
        val engine = Engine().apply { blocks.add(Block(512, 6, false)); blocks.add(Block(512, 7, false)) }
        val sink = Sink(engine).apply { writes.add(512); writes.add(512) }
        val result = captureFirstWait(engine, sink)
        assertEquals(2, result.first)
        val events = result.second.events
        assertTrue(result.second.complete)
        assertEquals(0L, result.second.overflowCount)
        val firstWait = events.first { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PARK }
        assertEquals(NativeAudioTrackDiagnosticRecord.ParkReason.CURRENT, firstWait.parkReason)
        val beforeWait = events.takeWhile { it !== firstWait }
        val writes = beforeWait.withIndex().filter { it.value.operation == NativeAudioTrackDiagnosticRecord.Operation.WRITE &&
            it.value.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT && it.value.resultFrames > 0L }.map { it.index }
        assertEquals(2, writes.size)
        assertEquals(2, beforeWait.count { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT } - 1)
        val play = beforeWait.indexOfFirst { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLAY }
        val played = beforeWait.indexOfLast { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLAY }
        assertTrue(writes[0] < play && played < writes[1])
        assertTrue(beforeWait.subList(writes[0] + 1, play).any {
            it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE &&
                it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT
        })
        assertFalse(beforeWait.subList(played + 1, writes[1]).any {
            it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE
        })
        assertTrue(beforeWait.drop(writes[1] + 1).any {
            it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE &&
                it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT
        })
        val observations = beforeWait.filter { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT }
        fun assertValidObservations(values: List<NativeAudioTrackDiagnosticRecord.Event>) {
            assertEquals(3, values.size)
            assertTrue(values.all { it.headValid && it.rawAvailable &&
                it.headFrames in 0L..it.submittedFrames && it.headSampleNanos <= it.timeNanos &&
                it.headAgeNanos >= 0L && it.rawUnderruns == 0L && it.rawSampleNanos > 0L })
        }
        assertValidObservations(observations)
        assertThrows(AssertionError::class.java) { assertValidObservations(emptyList()) }
        assertThrows(AssertionError::class.java) { assertValidObservations(observations.map { it.copy(headValid = false) }) }
        assertThrows(AssertionError::class.java) { assertValidObservations(observations.map { it.copy(rawAvailable = false) }) }
    }

    @Test
    fun zeroProgressIdleAndExhaustedSourceKeepTheBoundedWait() {
        for (kind in listOf("zero", "idle", "exhausted")) {
            val engine = Engine().apply {
                if (kind == "idle") { sessionActive.set(false); playing.set(false) }
                if (kind == "zero") blocks.add(Block(512, 8, false))
            }
            val result = captureFirstWait(engine, Sink(engine), expectedWaitNanos =
                if (kind == "exhausted") 5_000_000L else 500_000L)
            assertEquals(0, result.first)
            val firstWait = result.second.events.first { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PARK }
            assertEquals(when (kind) {
                "zero" -> NativeAudioTrackDiagnosticRecord.ParkReason.ZERO_PROGRESS
                "idle" -> NativeAudioTrackDiagnosticRecord.ParkReason.IDLE
                else -> NativeAudioTrackDiagnosticRecord.ParkReason.EXHAUSTED
            }, firstWait.parkReason)
            assertTrue(result.second.complete)
        }
    }

    @Test
    fun cancellationObservedAfterTheWriteKeepsTheCurrentWaitAndNeverStartsCancelledPrefill() {
        val engine = Engine().apply { blocks.add(Block(512, 9, false)) }
        val sink = Sink(engine).apply {
            writes.add(512)
            holdFirstWrite = { engine.sessionActive.set(false); engine.playing.set(false) }
        }
        val result = captureFirstWait(engine, sink)
        assertEquals(1, result.first)
        assertEquals(0, sink.plays.get())
        assertEquals(NativeAudioTrackDiagnosticRecord.ParkReason.CURRENT,
            result.second.events.first { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PARK }.parkReason)
        assertTrue(result.second.complete)
    }

    @Test
    fun prePollGenerationRemainsUnavailableUntilTheControlObservationProvidesIt() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val trace = captureFirstWait(engine, Sink(engine)).second
        assertTrue(trace.complete)
        assertEquals("Opening observations precede the first native control poll", -1L,
            trace.events.first().producerGeneration)
        val firstWait = trace.events.first { it.operation == NativeAudioTrackDiagnosticRecord.Operation.PARK &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.ENTER }
        assertEquals("A returned producer generation is recorded without relabeling earlier samples", 3L,
            firstWait.producerGeneration)
        assertEquals(-1L, trace.events.first { it.operation == NativeAudioTrackDiagnosticRecord.Operation.NATIVE_PUBLICATION &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT }.producerGeneration)
    }

    @Test
    fun boundedRecordReportsOverflowAsIncompleteWithoutInventingAnAudioFailure() {
        val engine = Engine().apply { sessionActive.set(false); playing.set(false) }
        val result = captureFirstWait(engine, Sink(engine), capacity = 2)
        assertEquals(2, result.second.events.size)
        assertTrue(result.second.overflowCount > 0L)
        assertFalse(result.second.complete)
        assertEquals(0, engine.failures.get())
    }

    @Test
    fun persistentPauseHoldsTheExactSourceSuffixAndSilenceAdvancesOnlyTheOutputLedger() {
        val engine = Engine().apply { sessionActive.set(true); blocks.add(Block(256, 11, true)) }
        val sink = Sink(engine).apply { writes.add(192) }
        val sourcePark = CountDownLatch(1); val allowPause = CountDownLatch(1)
        val silencePark = CountDownLatch(1); val allowResume = CountDownLatch(1)
        val parks = AtomicInteger(0)
        val record = NativeAudioTrackDiagnosticRecord(4_096)
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            record = record, park = { nanos ->
                when (parks.incrementAndGet()) {
                    1 -> { sourcePark.countDown(); check(allowPause.await(2, TimeUnit.SECONDS)) }
                    2 -> { silencePark.countDown(); check(allowResume.await(2, TimeUnit.SECONDS)) }
                    else -> LockSupport.parkNanos(nanos)
                }
            }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(sourcePark.await(2, TimeUnit.SECONDS))
            engine.playing.set(false); sink.writes.add(512); allowPause.countDown()
            assertTrue(silencePark.await(2, TimeUnit.SECONDS))
            assertTrue("Silence still owns the real Core consumer", engine.active.get())
            assertEquals(listOf(1L to 192), engine.accepted.toList())
            assertEquals(512, engine.silenceAccepted.sumOf { it.second })
            assertEquals(1, engine.prepared.get())
            assertEquals(1, sink.plays.get())
            assertEquals(0, sink.pauses.get())
            engine.playing.set(true); sink.writes.add(64); allowResume.countDown()
            await { engine.accepted.size == 2 }
            assertEquals(expected(11, 192) + List(512 * 2) { 0f } + expected(11, 256).drop(192 * 2),
                sink.samples.toList())
            assertEquals(listOf(1L to 192, 1L to 64), engine.accepted.toList())
            assertEquals(1, engine.prepared.get())
            assertEquals(1, sink.plays.get())
            assertEquals(0, sink.pauses.get())
            assertFalse("Source transitions cannot retire the physical sink", sink.closed.get())
            assertNull(feeder.failureCause())
        } finally { allowPause.countDown(); allowResume.countDown(); feeder.close() }
        val trace = record.snapshot()
        assertTrue(trace.complete)
        assertTrue(trace.events.any { it.operation == NativeAudioTrackDiagnosticRecord.Operation.TRANSPORT_ACCEPT &&
            it.phase == NativeAudioTrackDiagnosticRecord.Phase.EXIT && it.pendingFrames == 64L &&
            it.pcmKind == NativeAudioTrackDiagnosticRecord.PcmKind.LOGICAL_PAUSE_SILENCE })
    }

    @Test
    fun cancelledSourceFenceAcknowledgesItsSerialWithoutDiscardingTheOldSuffix() {
        val engine = Engine().apply { blocks.add(Block(256, 17, false)) }
        val sink = Sink(engine).apply { writes.add(192) }
        val parked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val parks = AtomicInteger(0)
        val feeder = NativeAudioTrackFeeder(engine, { sink }, park = { nanos ->
            if (parks.incrementAndGet() == 1) {
                parked.countDown()
                check(resume.await(2, TimeUnit.SECONDS))
            } else LockSupport.parkNanos(nanos)
        }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(parked.await(2, TimeUnit.SECONDS))
            engine.resetAction.set(0L)
            engine.reset.set(1L)
            sink.writes.add(64)
            resume.countDown()
            await { engine.accepted.size == 2 }
            assertEquals(expected(17, 256), sink.samples.toList())
            assertEquals(listOf(1L to 192, 1L to 64), engine.accepted.toList())
            assertEquals(0, engine.discards.get())
            assertFalse(sink.closed.get())
            assertNull(feeder.failureCause())
        } finally { resume.countDown(); feeder.close() }
    }

    @Test
    fun persistentSeekRetiresOnlyTheUnacceptedSourceCopyWithoutRestartingTheTrack() {
        val engine = Engine().apply {
            sessionActive.set(true); blocks.add(Block(256, 12, false)); blocks.add(Block(256, 13, true))
        }
        val sink = Sink(engine).apply { writes.add(192) }
        val sourcePark = CountDownLatch(1); val allowTransition = CountDownLatch(1)
        val silencePark = CountDownLatch(1); val allowReplacement = CountDownLatch(1)
        val parks = AtomicInteger(0)
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            park = { nanos ->
                when (parks.incrementAndGet()) {
                    1 -> { sourcePark.countDown(); check(allowTransition.await(2, TimeUnit.SECONDS)) }
                    2 -> { silencePark.countDown(); check(allowReplacement.await(2, TimeUnit.SECONDS)) }
                    else -> LockSupport.parkNanos(nanos)
                }
            }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(sourcePark.await(2, TimeUnit.SECONDS))
            engine.pendingSourceTarget.set(4L); engine.resetAction.set(2L); engine.reset.set(1L)
            sink.writes.add(512); allowTransition.countDown()
            assertTrue(silencePark.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1L to 4L), engine.sourceTransitions.toList())
            assertEquals(1, engine.prepared.get())
            assertTrue(engine.active.get())
            assertEquals(0L, engine.completedReset.get())
            engine.producerGeneration.set(4L); engine.pendingSourceTarget.set(0L)
            sink.writes.add(256); allowReplacement.countDown()
            await { engine.accepted.size == 2 }
            assertEquals(expected(12, 192) + List(512 * 2) { 0f } + expected(13, 256), sink.samples.toList())
            assertEquals(listOf(1L to 192, 2L to 256), engine.accepted.toList())
            assertEquals(1, engine.silenceAccepted.size)
            assertFalse("Source transitions cannot retire the physical sink", sink.closed.get())
            assertEquals(0, sink.pauses.get())
            assertEquals(1, sink.plays.get())
            assertNull(feeder.failureCause())
        } finally { allowTransition.countDown(); allowReplacement.countDown(); feeder.close() }
    }

    @Test
    fun persistentPhysicalSuspendReallyQuiescesAndRetiresWithTheFinalRawCounter() {
        val engine = Engine().apply { playing.set(false); sessionActive.set(true) }
        val sink = Sink(engine).apply { writes.add(512) }
        val silentPark = CountDownLatch(1); val allowSuspend = CountDownLatch(1)
        val inactivePark = CountDownLatch(1); val allowRetire = CountDownLatch(1)
        val parks = AtomicInteger(0)
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            park = { nanos ->
                when (parks.incrementAndGet()) {
                    1 -> { silentPark.countDown(); check(allowSuspend.await(2, TimeUnit.SECONDS)) }
                    2 -> { inactivePark.countDown(); check(allowRetire.await(2, TimeUnit.SECONDS)) }
                    else -> LockSupport.parkNanos(nanos)
                }
            }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(silentPark.await(2, TimeUnit.SECONDS))
            assertTrue(engine.active.get())
            engine.sessionActive.set(false); allowSuspend.countDown()
            assertTrue(inactivePark.await(2, TimeUnit.SECONDS))
            assertFalse(engine.active.get())
            assertEquals(1, sink.pauses.get())
            assertEquals(0, engine.sourcePrepareCalls.get())
            assertEquals(512, engine.silenceAccepted.sumOf { it.second })
            sink.underruns = 2
        } finally { allowSuspend.countDown(); allowRetire.countDown(); feeder.close() }
        assertTrue(sink.closed.get())
        assertFalse(engine.active.get())
        assertEquals(1, sink.plays.get())
        assertEquals(2, engine.observations.last().raw)
        assertTrue(engine.observations.last().complete)
    }

    @Test
    fun persistentPlayingWithNoSourceNeverUsesCleanTransportSilence() {
        val engine = Engine().apply { sessionActive.set(true) }
        val sink = Sink(engine)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            park = { nanos -> entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); LockSupport.parkNanos(nanos) },
            setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, engine.sourcePrepareCalls.get())
            assertTrue(engine.transportAuthorizations.isEmpty())
            assertTrue(engine.silenceAccepted.isEmpty())
            assertTrue(sink.samples.isEmpty())
            assertEquals(0, sink.plays.get())
        } finally { release.countDown(); feeder.close() }
    }

    @Test
    fun persistentAuthorizedSilenceReceiptSurvivesResumeBeforeTheWriteReturns() {
        val engine = Engine().apply { playing.set(false); sessionActive.set(true); blocks.add(Block(256, 14, true)) }
        val sink = Sink(engine).apply {
            writes.add(512); writes.add(256)
            holdFirstWrite = { engine.playing.set(true) }
        }
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            setAudioPriority = {})
        try {
            feeder.start()
            await { engine.accepted.size == 1 }
            assertEquals(List(512 * 2) { 0f } + expected(14, 256), sink.samples.toList())
            assertEquals(512, engine.silenceAccepted.sumOf { it.second })
            assertEquals(listOf(1L to 256), engine.accepted.toList())
            assertEquals(1, sink.plays.get())
            assertEquals(0, sink.pauses.get())
            assertEquals(0, engine.failures.get())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    @Test
    fun persistentAuthorizedTransitionSilenceReceiptSurvivesSourceCommitBeforeWriteReturn() {
        val engine = Engine().apply {
            sessionActive.set(true); reset.set(1L); resetAction.set(2L); pendingSourceTarget.set(4L)
        }
        val sink = Sink(engine).apply {
            writes.add(512); writes.add(256)
            holdFirstWrite = {
                engine.producerGeneration.set(4L); engine.pendingSourceTarget.set(0L)
                engine.blocks.add(Block(256, 15, true))
            }
        }
        val feeder = NativeAudioTrackFeeder(engine, { sink },
            setAudioPriority = {})
        try {
            feeder.start()
            await { engine.accepted.size == 1 }
            assertEquals(List(512 * 2) { 0f } + expected(15, 256), sink.samples.toList())
            assertEquals(listOf(1L to 4L), engine.sourceTransitions.toList())
            assertEquals(512, engine.silenceAccepted.sumOf { it.second })
            assertEquals(listOf(1L to 256), engine.accepted.toList())
            assertEquals(1, sink.plays.get())
            assertEquals(0, sink.pauses.get())
            assertFalse("Source transitions cannot retire the physical sink", sink.closed.get())
            assertEquals(0, engine.failures.get())
            assertNull(feeder.failureCause())
        } finally { feeder.close() }
    }

    private fun captureFirstWait(engine: Engine, sink: Sink,
        capacity: Int = 4_096, expectedWaitNanos: Long = 500_000L): Pair<Int, NativeAudioTrackDiagnosticRecord.Snapshot> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waits = AtomicInteger(0)
        val acceptedAtWait = AtomicInteger(-1)
        val record = NativeAudioTrackDiagnosticRecord(capacity)
        val feeder = NativeAudioTrackFeeder(engine, { sink }, record = record,
            park = { nanos ->
                if (waits.incrementAndGet() == 1) {
                    assertEquals(expectedWaitNanos, nanos)
                    acceptedAtWait.set(engine.accepted.size)
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS)) { "Controlled service wait was not released" }
                } else LockSupport.parkNanos(nanos)
            }, setAudioPriority = {})
        try {
            feeder.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("A live prefix is readable but never complete evidence", record.snapshot().complete)
            engine.sessionActive.set(false)
            engine.playing.set(false)
            release.countDown()
            await { !engine.active.get() }
            assertNull(feeder.failureCause())
        } finally { release.countDown(); feeder.close() }
        return acceptedAtWait.get() to record.snapshot()
    }

    private fun feeder(engine: Engine, sink: Sink) = NativeAudioTrackFeeder(engine, { sink }, setAudioPriority = {})

    private data class Block(val frames: Int, val marker: Int, val terminal: Boolean, val sourceFrames: Int = frames)
    private data class Observation(val instance: Long, val raw: Int, val complete: Boolean, val submittedFrames: Long)

    private class Engine : NativeAudioTrackFeeder.Engine {
        val controlPolls = AtomicInteger()
        var idleAwait: ((Long) -> Unit)? = null
        var wakeControl: (() -> Unit)? = null
        override fun awaitControlChange(afterSequence: Long): Boolean {
            val wait = idleAwait ?: return false
            wait(afterSequence)
            return true
        }
        override fun wake() { wakeControl?.invoke() }
        val blocks = LinkedBlockingQueue<Block>()
        val playing = AtomicBoolean(true)
        val reset = AtomicLong(0)
        val recreate = AtomicBoolean(false)
        val sessionActive = AtomicBoolean(true)
        val resetAction = AtomicLong(1)
        val pendingSourceTarget = AtomicLong(0)
        val producerGeneration = AtomicLong(3)
        val sourcePrepareCalls = AtomicInteger(0)
        val sourceTransitions = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val transportAuthorizations = Collections.synchronizedList(mutableListOf<Pair<Long, NativeAudioTrackFeeder.TransportSilenceKind>>())
        val silenceAccepted = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        private val transportSequence = AtomicLong(0)
        val completedReset = AtomicLong(0)
        val lastObservedHead = AtomicLong(-1)
        val active = AtomicBoolean(false)
        var activateFailure: Throwable? = null
        var deactivateFailure: Throwable? = null
        val prepared = AtomicInteger(0)
        val writePreparations = AtomicInteger(0)
        val discards = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val accepted = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val observations = Collections.synchronizedList(mutableListOf<Observation>())
        val instances = Collections.synchronizedList(mutableListOf<Long>())
        val configurations = Collections.synchronizedList(mutableListOf<NativeAudioTrackFeeder.Configuration>())
        override fun pollControl(values: LongArray) {
            controlPolls.incrementAndGet()
            values[0] = if (playing.get()) 1 else 0
            values[1] = reset.get()
            values[2] = if (recreate.get()) 1 else resetAction.get()
            if (values.size > 3) values[3] = producerGeneration.get()
            if (values.size > 4) values[4] = if (sessionActive.get()) 1 else 0
            if (values.size > 5) values[5] = pendingSourceTarget.get()
        }
        override fun prepare(pcm: ByteBuffer, metadata: LongArray): Int {
            check(active.get())
            sourcePrepareCalls.incrementAndGet()
            val block = blocks.poll() ?: return 0
            metadata[0] = prepared.incrementAndGet().toLong()
            metadata[1] = if (block.terminal) 1 else 0
            for (frame in 0 until block.frames) repeat(2) {
                pcm.putFloat(if (frame < block.sourceFrames) (block.marker * 1000 + frame).toFloat() else 0f)
            }
            return block.frames
        }
        override fun accept(sequence: Long, frames: Int): Boolean {
            check(active.get())
            accepted.add(sequence to frames)
            return true
        }
        override fun prepareWrite(pcm: ByteBuffer, frames: Int, transport: Boolean) {
            writePreparations.incrementAndGet()
        }
        override fun beginSourceTransition(serial: Long, generation: Long): Boolean {
            check(active.get())
            if (serial != reset.get() || generation != pendingSourceTarget.get()) return false
            sourceTransitions.add(serial to generation)
            return true
        }
        override fun prepareTransportSilence(kind: NativeAudioTrackFeeder.TransportSilenceKind): Long {
            check(active.get())
            if (kind == NativeAudioTrackFeeder.TransportSilenceKind.LOGICAL_PAUSE && playing.get()) return 0L
            if (kind == NativeAudioTrackFeeder.TransportSilenceKind.SOURCE_TRANSITION && pendingSourceTarget.get() == 0L) return 0L
            val sequence = transportSequence.incrementAndGet()
            transportAuthorizations.add(sequence to kind)
            return sequence
        }
        override fun acceptTransportSilence(sequence: Long, frames: Int): Boolean {
            check(active.get() && frames > 0 && transportAuthorizations.any { it.first == sequence })
            silenceAccepted.add(sequence to frames)
            return true
        }
        override fun consumerActive(active: Boolean) {
            if (!active) deactivateFailure?.let { throw it }
            this.active.set(active)
            if (active) activateFailure?.let { throw it }
        }
        override fun discard() { check(!active.get()); discards.incrementAndGet() }
        override fun resetComplete(serial: Long) {
            check(!active.get() && lastObservedHead.get() == 0L)
            completedReset.set(serial)
        }
        override fun configure(instance: Long, configuration: NativeAudioTrackFeeder.Configuration) {
            check(instance > (instances.lastOrNull() ?: 0L))
            instances.add(instance)
            configurations.add(configuration)
        }
        override fun observe(instance: Long, actual: IntArray, position: LongArray, complete: Boolean) {
            lastObservedHead.set(position[0])
            observations.add(Observation(instance, actual[5], complete, position[3]))
        }
        override fun failed() { failures.incrementAndGet() }
    }

    private class Sink(private val engine: Engine, bufferFrames: Int = 512, performanceMode: Int = 1, startThresholdFrames: Int = 512) : NativeAudioTrackFeeder.Sink {
        override val configuration = NativeAudioTrackFeeder.Configuration(48_000, 2, 4, bufferFrames, bufferFrames, startThresholdFrames, performanceMode, -1)
        val writes = LinkedBlockingQueue<Int>()
        val samples = Collections.synchronizedList(mutableListOf<Float>())
        val plays = AtomicInteger(0)
        val pauses = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        @Volatile var framesAtPlay = 0
        @Volatile var queuedFramesAtPlay = 0L
        @Volatile var underruns = 0
        @Volatile var head = 0L
        @Volatile var forcedBytes: Int? = null
        var holdFirstWrite: (() -> Unit)? = null
        override fun write(pcm: ByteBuffer): Int {
            check(engine.active.get())
            forcedBytes?.let { return it }
            val frames = minOf(writes.poll() ?: 0, pcm.remaining() / 8)
            repeat(frames * 2) { samples.add(pcm.float) }
            holdFirstWrite?.also { holdFirstWrite = null }?.invoke()
            return frames * 8
        }
        override fun play() {
            framesAtPlay = samples.size / 2
            queuedFramesAtPlay = framesAtPlay - head
            plays.incrementAndGet()
        }
        override fun pause() { pauses.incrementAndGet() }
        override fun observe(actual: IntArray, position: LongArray) {
            actual[0] = 4; actual[1] = configuration.bufferFrames; actual[2] = configuration.capacityFrames
            actual[3] = 48_000; actual[4] = configuration.performanceMode; actual[5] = underruns
            position[0] = head
        }
        override fun close() { check(!engine.active.get()); closed.set(true) }
    }

    private fun <T> snapshot(values: MutableList<T>): List<T> = synchronized(values) { values.toList() }

    private fun expected(marker: Int, frames: Int) = (0 until frames).flatMap { listOf((marker * 1000 + it).toFloat(), (marker * 1000 + it).toFloat()) }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Feeder did not reach the required boundary", predicate())
    }
}
