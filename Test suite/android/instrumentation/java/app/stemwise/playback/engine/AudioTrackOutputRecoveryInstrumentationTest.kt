package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.integration.MergerOracleFixture
import app.stemwise.qualification.outputHasNeverOpened
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated output faults use the real JNI owner and Java AudioTrack opener, without media or app resets. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class AudioTrackOutputRecoveryInstrumentationTest {
    @Test fun routeReopenHonorsAnAlreadyPendingHigherResetGeneration() {
        NativeAudioOutput(16_384, 1L).use { output ->
            val originalGeneration = output.currentGeneration()
            val pendingGeneration = originalGeneration + 3L
            assertFalse(output.tryFlushToGeneration(pendingGeneration))
            assertEquals(originalGeneration, output.currentGeneration())
            await("Route reopening must retire the existing higher reset", { output.diagnostics().toString() }) {
                output.reopenForRouteChange()
            }
            assertTrue(output.currentGeneration() >= pendingGeneration)
            assertEquals(1L, output.diagnostics().reopenCount)
            assertFalse(output.needsRecovery())
        }
    }

    @Test fun stopPollsSlowSinkRetirementWithoutHoldingSelectionOrSilenceGates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("slow-stop-", ".wav", context.cacheDir)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val controlsReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val actual = JniNativeEngineEnvironment(context, outputSinkFactory = {
            val sink = AndroidAudioTrackSink()
            object : NativeAudioTrackFeeder.Sink by sink {
                override fun close() {
                    closeEntered.countDown()
                    try { check(allowClose.await(5L, TimeUnit.SECONDS)) }
                    finally { sink.close() }
                }
            }
        },
            packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        val controller = NativePlaybackEngineController(actual)
        try {
            MergerOracleFixture.writeWave(source, IntArray(48_000 * 4) { 1_000 }, bits = 16, channels = 1)
            val first = controller.select(NativeEngineSelectionRequest("slow-stop", "slow-stop-wave",
                source.absolutePath, 4_000L, autoplay = true))
            await { controller.diagnosticsSnapshot().output?.lastPresentedSelectionToken == first.value }
            val stop = controller.stopForTaskRemoval()
            assertTrue(closeEntered.await(2L, TimeUnit.SECONDS))
            val controls = thread {
                try {
                    controller.select(NativeEngineSelectionRequest("after-slow-stop", "slow-stop-wave",
                        source.absolutePath, 4_000L, autoplay = false))
                    actual.engageImmediateSilenceGate()
                } catch (error: Throwable) { failure.set(error) }
                finally { controlsReturned.countDown() }
            }
            try {
                assertTrue("Sink close must not hold synchronous controls", controlsReturned.await(500L, TimeUnit.MILLISECONDS))
                assertNull(failure.get())
                assertFalse("STOP cannot acknowledge a live sink", stop.isComplete())
                assertNotNull(controller.diagnosticsSnapshot().output)
            } finally { allowClose.countDown(); controls.join(2_000L) }
            await { stop.isComplete() }
        } finally {
            allowClose.countDown()
            controller.close()
            source.delete()
        }
    }

    @Test fun pausedNativePreparationCommitsWithoutEverOpeningThePhysicalSink() {
        val opens = AtomicInteger()
        NativeAudioOutput(16_384, 1L, deferPhysicalOpen = true,
            sinkFactory = { opens.incrementAndGet(); error("Paused preparation cannot open AudioTrack") }).use { output ->
            NativeSongLaneIngressBank(16_384, 256).use { first ->
                NativeSongLaneIngressBank(256, 256).use { second ->
                    val owner = NativeRuntimeOwnership(8_991L, 2L)
                    val lane = first.configureLane(0, 48_000, owner.generation)
                    val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
                    repeat(32) { chunk ->
                        val pcm = java.nio.ByteBuffer.allocateDirect(256 * 8).order(java.nio.ByteOrder.nativeOrder())
                        repeat(256 * 2) { pcm.putFloat(.01f) }
                        pcm.flip()
                        assertEquals(256 * 8, lane.offer(NativePcmBlock().reset(
                            pcm.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder()), format,
                            chunk * 256_000_000L / 48_000, 0L, chunk * 256L)).acceptedBytes)
                    }
                    assertEquals(NativeLaneEndOfStreamStatus.ACCEPTED, lane.markEndOfStream(0L, 8_192L))
                    val pausedRuntime = NativePlaybackRuntime(output, first, second).use { runtime ->
                        val intent = NativeRuntimeBankIntent(1L, owner, songIdentityHash = 8_991L,
                            originalContentHash = 8_992L, cacheFrameUpperBound = 8_192L)
                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                            NativeRuntimeCommandSnapshot(1L, owner.generation,
                                listOf(intent, NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST,
                                playing = false, controls = NativeRuntimeControls(1L,
                                    sourceIntentRevision = 1L, stemIntentRevision = 1L))))
                        assertTrue(runtime.start())
                        await { runtime.diagnostics().ownership.committed == owner }
                        assertEquals(owner.generation, output.currentGeneration())
                        assertFalse(output.diagnostics().playing)
                        assertEquals(0, opens.get())
                        assertTrue("Prepared canonical PCM is not a physical sink opening",
                            NativeEngineDiagnosticsSnapshot(runtime.diagnostics(), output.diagnostics()).outputHasNeverOpened())
                        runtime.diagnostics()
                    }
                    assertNotNull(output.retireForStop(output.currentGeneration() + 1L))
                    assertTrue("Administrative STOP of an unopened sink must not invent an observation",
                        NativeEngineDiagnosticsSnapshot(pausedRuntime, output.diagnostics()).outputHasNeverOpened())
                }
            }
        }
    }

    @Test fun deferredSlowOpenAndAThrowingReplacementRemainRetryableWithOneLiveSink() {
        val entered = CountDownLatch(1)
        val allowOpen = CountDownLatch(1)
        val opens = AtomicInteger()
        val live = AtomicInteger()
        val successfulSinks = AtomicInteger()
        val closes = AtomicInteger()
        val observations = AtomicInteger()
        val failObservation = AtomicBoolean()
        val opened = AtomicReference<Boolean>()
        val openFailure = AtomicReference<Throwable>()
        val output = NativeAudioOutput(16_384, 1L,
            deferPhysicalOpen = true,
            sinkFactory = {
                when (opens.incrementAndGet()) {
                    1 -> { entered.countDown(); check(allowOpen.await(3, TimeUnit.SECONDS)) }
                    2 -> throw IllegalStateException("Injected AudioTrack replacement open failure")
                }
                assertEquals("The previous physical sink must retire before opening its replacement", 0, live.get())
                val actual = AndroidAudioTrackSink()
                successfulSinks.incrementAndGet()
                live.incrementAndGet()
                val retired = AtomicBoolean()
                object : NativeAudioTrackFeeder.Sink by actual {
                    override fun observe(configuration: IntArray, position: LongArray) {
                        observations.incrementAndGet()
                        if (failObservation.getAndSet(false)) throw IllegalStateException("Injected old output failure")
                        actual.observe(configuration, position)
                    }
                    override fun close() {
                        assertTrue("Each opened physical sink must retire exactly once", retired.compareAndSet(false, true))
                        actual.close()
                        live.decrementAndGet()
                        closes.incrementAndGet()
                    }
                }
            })
        assertEquals("Constructing the library audio owner cannot open the physical sink", 0, opens.get())
        val worker = thread(name = "AudioTrackOpenRegression") {
            try { opened.set(output.start()) } catch (failure: Throwable) { openFailure.set(failure) }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull("A slow opener must remain off the caller/library thread", opened.get())
            val controlsAdmitted = CountDownLatch(1)
            val controlsFailure = AtomicReference<Throwable>()
            val controls = thread(name = "AudioTrackControlAdmission") {
                try {
                    output.armManualSelectionGateAndGetGeneration()
                    output.pause()
                    output.setSessionSuspended(true)
                    controlsAdmitted.countDown()
                } catch (failure: Throwable) { controlsFailure.set(failure) }
            }
            try {
                assertTrue("Selection/stop admission must finish while the physical opener is held",
                    controlsAdmitted.await(200, TimeUnit.MILLISECONDS))
                controlsFailure.get()?.let { throw AssertionError("Control admission failed", it) }
            } finally {
                allowOpen.countDown()
                controls.join(2_000L)
                assertFalse("Control caller must retire", controls.isAlive)
            }
            allowOpen.countDown()
            worker.join(2_000L)
            assertFalse(worker.isAlive)
            openFailure.get()?.let { throw AssertionError("Initial opening failed", it) }
            assertEquals(true, opened.get())
            assertEquals(NativeAudioBackend.AUDIO_TRACK, output.diagnostics().backend)
            assertEquals("The initial accepted sink is not a reopen", 0L, output.diagnostics().reopenCount)
            await { observations.get() >= 2 }
            val idleObservations = observations.get()
            assertFalse(CountDownLatch(1).await(30, TimeUnit.MILLISECONDS))
            assertEquals("The real idle feeder must wait without polling platform state", idleObservations, observations.get())
            failObservation.set(true)
            output.setSessionSuspended(false)
            output.play()
            await { output.needsRecovery() && live.get() == 0 }
            val generation = output.currentGeneration()
            assertFalse(output.reopenForRouteChange())
            assertEquals(generation, output.currentGeneration())
            assertEquals(0, live.get())
            assertEquals("A failed replacement cannot count as an installed sink", 0L, output.diagnostics().reopenCount)
            await { output.reopenForRouteChange() }
            assertTrue(output.currentGeneration() > generation)
            assertFalse(output.needsRecovery())
            assertEquals(1, live.get())
            assertEquals("One accepted replacement counts once across native recovery retries", 1L, output.diagnostics().reopenCount)
            output.pause()
            output.play()
            assertEquals("Ordinary pause/resume keeps the same installed sink", 1L, output.diagnostics().reopenCount)
            assertEquals(successfulSinks.get() - 1, closes.get())
            assertEquals("Exactly one opening attempt failed", successfulSinks.get() + 1, opens.get())
        } finally {
            allowOpen.countDown()
            worker.join(2_000L)
            assertFalse("Opener must retire before deleting its native owner", worker.isAlive)
            output.close()
        }
        assertEquals(0, live.get())
        assertEquals("Recovery must retain its first accepted replacement", 2, successfulSinks.get())
        assertEquals(successfulSinks.get(), closes.get())
    }

    @Test fun failedPhysicalRouteRecreationUsesOneReplacementAndHealthyRoutesStillRecreate() {
        val opens = AtomicInteger()
        val successful = AtomicInteger()
        val live = AtomicInteger()
        val closes = AtomicInteger()
        val output = NativeAudioOutput(16_384, 1L, sinkFactory = {
            if (opens.incrementAndGet() == 2) throw IllegalStateException("Injected pending route recreation failure")
            assertEquals("A replacement cannot overlap its prior physical sink", 0, live.get())
            val actual = AndroidAudioTrackSink()
            successful.incrementAndGet(); live.incrementAndGet()
            val retired = AtomicBoolean()
            object : NativeAudioTrackFeeder.Sink by actual {
                override fun close() {
                    assertTrue(retired.compareAndSet(false, true))
                    actual.close(); live.decrementAndGet(); closes.incrementAndGet()
                }
            }
        })
        try {
            assertEquals(0L, output.diagnostics().reopenCount)
            val initialGeneration = output.currentGeneration()
            assertFalse(output.reopenForRouteChange())
            await("The failed pending recreation must retire its physical sink") { output.needsRecovery() && live.get() == 0 }
            assertEquals(initialGeneration, output.currentGeneration())
            assertEquals(0L, output.diagnostics().reopenCount)
            await("A restarted feeder must acknowledge the pending reset without another opening") { output.reopenForRouteChange() }
            assertEquals(3, opens.get())
            assertEquals(2, successful.get())
            assertEquals(1, live.get())
            assertEquals(1L, output.diagnostics().reopenCount)
            assertFalse(output.needsRecovery())
            val recoveredGeneration = output.currentGeneration()
            assertTrue(recoveredGeneration > initialGeneration)
            await("A healthy explicit route change still replaces its sink once") { output.reopenForRouteChange() }
            assertTrue(output.currentGeneration() > recoveredGeneration)
            assertEquals(4, opens.get())
            assertEquals(3, successful.get())
            assertEquals(2L, output.diagnostics().reopenCount)
            output.pause(); output.play(); output.pause()
            assertEquals("Ordinary pause/resume cannot count another replacement", 2L, output.diagnostics().reopenCount)
            assertEquals(1, live.get())
        } finally { output.close() }
        assertEquals(0, live.get())
        assertEquals(successful.get(), closes.get())
    }

    @Test fun failedOutputCloseRetiresActualQueuedRingsAfterItsConsumerWithoutOpeningAnotherSink() =
        exerciseFailedOutputRetirement(reuse = false)

    @Test fun failedOutputStopRetiresActualQueueAndTheSameHandleServesTheNextOwner() =
        exerciseFailedOutputRetirement(reuse = true)

    private fun exerciseFailedOutputRetirement(reuse: Boolean) {
        val opens = AtomicInteger()
        val sinkClosed = AtomicBoolean()
        val finalObservation = AtomicReference<Result<NativeAudioDiagnostics>>()
        var queuedBeforeClose = 0L
        var opensBeforeClose = 0
        fun queueOriginal(bank: NativeSongLaneIngressBank, owner: NativeRuntimeOwnership) {
            val lane = bank.configureLane(0, 48_000, owner.generation)
            val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
            repeat(32) { chunk ->
                val pcm = java.nio.ByteBuffer.allocateDirect(256 * 8).order(java.nio.ByteOrder.nativeOrder())
                repeat(256 * 2) { pcm.putFloat(.025f) }
                pcm.flip()
                assertEquals(256 * 8, lane.offer(NativePcmBlock().reset(
                    pcm.asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder()), format,
                    chunk * 256_000_000L / 48_000, 0L, chunk * 256L)).acceptedBytes)
            }
            assertEquals(NativeLaneEndOfStreamStatus.ACCEPTED, lane.markEndOfStream(0L, 8_192L))
        }
        val output = NativeAudioOutput(16_384, 1L,
            sinkFactory = {
                val failWrites = opens.incrementAndGet() == 1
                val actual = AndroidAudioTrackSink()
                object : NativeAudioTrackFeeder.Sink by actual {
                    override fun write(pcm: java.nio.ByteBuffer): Int = if (failWrites)
                        throw IllegalStateException("Injected first queued AudioTrack write failure")
                        else actual.write(pcm)
                    override fun close() {
                        actual.close()
                        sinkClosed.set(true)
                    }
                }
            }, finalObservation = finalObservation::set)
        try {
            NativeSongLaneIngressBank(16_384, 256).use { first ->
                NativeSongLaneIngressBank(256, 256).use { second ->
                    val owner = NativeRuntimeOwnership(8_993L, 2L)
                    queueOriginal(first, owner)
                    NativePlaybackRuntime(output, first, second).use { runtime ->
                        val intent = NativeRuntimeBankIntent(1L, owner, songIdentityHash = 8_993L,
                            originalContentHash = 8_994L, cacheFrameUpperBound = 8_192L)
                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                            NativeRuntimeCommandSnapshot(1L, owner.generation,
                                listOf(intent, NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST,
                                playing = true, controls = NativeRuntimeControls(1L,
                                    sourceIntentRevision = 1L, stemIntentRevision = 1L))))
                        assertTrue(runtime.start())
                        await("Injected sink failure must retire its real native consumer", diagnostics = {
                            "output=${output.diagnostics()}, native=${runtime.diagnostics().status}, opens=${opens.get()}"
                        }) { output.needsRecovery() && sinkClosed.get() }
                        runtime.requestStop()
                        runtime.join()
                        assertEquals(NativeRuntimeStatus.STOPPED, runtime.diagnostics().status)
                        val failed = output.diagnostics()
                        queuedBeforeClose = failed.queuedFrames
                        assertTrue("The regression needs retained native PCM, not merely Java pending bytes",
                            queuedBeforeClose > 0L)
                        assertEquals(1, opens.get())
                    }
                    if (reuse) {
                        val failed = output.diagnostics()
                        var retiredGeneration: Long? = null
                        await { output.retireForStop(output.currentGeneration() + 1L)
                            .also { retiredGeneration = it } != null }
                        val retired = output.diagnostics()
                        assertFalse(output.isClosed())
                        assertEquals(retiredGeneration, retired.streamGeneration)
                        assertEquals(0L, retired.queuedFrames)
                        assertEquals("STOP must not open a replacement sink", 1, opens.get())
                        assertTrue("Retirement must preserve the actual output failure", retired.recoveryRequired)
                        assertEquals(failed.lastErrorCode, retired.lastErrorCode)
                        assertEquals(failed.lastErrorMonotonicMillis, retired.lastErrorMonotonicMillis)
                        assertEquals(failed.processIncompleteStreamObservationCount,
                            retired.processIncompleteStreamObservationCount)
                        await("A new owner can explicitly reopen the retired failed output") {
                            output.reopenForRouteChange()
                        }
                        val next = NativeRuntimeOwnership(8_995L, output.currentGeneration() + 1L)
                        assertTrue("The joined prior runtime must release its old ingress lane before reconfiguration",
                            first.releaseLane(0))
                        queueOriginal(first, next)
                        NativePlaybackRuntime(output, first, second).use { runtime ->
                            runtime.setDiagnosticPayloadFingerprints(true)
                            val intent = NativeRuntimeBankIntent(1L, next, songIdentityHash = 8_995L,
                                originalContentHash = 8_994L, cacheFrameUpperBound = 8_192L)
                            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                                NativeRuntimeCommandSnapshot(1L, next.generation,
                                    listOf(intent, NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST,
                                    playing = true, controls = NativeRuntimeControls(1L,
                                        sourceIntentRevision = 1L, stemIntentRevision = 1L))))
                            assertTrue(runtime.start())
                            await("The next owner must present its own nonzero PCM", diagnostics = {
                                "runtime=${runtime.diagnostics().status}, output=${output.diagnostics()}"
                            }) {
                                val actual = output.diagnostics()
                                actual.lastPresentedSelectionToken == next.selectionToken &&
                                    actual.pcmLevelSelectionToken == next.selectionToken &&
                                    actual.pcmLevelFrames > 0L && actual.pcmLevelPeak > 0f
                            }
                            runtime.requestStop()
                            runtime.join()
                        }
                    }
                }
            }
        } finally {
            opensBeforeClose = opens.get()
            output.close()
        }
        val retired = requireNotNull(finalObservation.get()).getOrThrow()
        assertTrue(sinkClosed.get())
        assertEquals("Closing an output cannot open a replacement sink", opensBeforeClose, opens.get())
        assertEquals("Retired consumer left actual native PCM queued (before=$queuedBeforeClose)",
            0L, retired.queuedFrames)
        assertFalse(retired.playing)
        assertFalse(retired.effectiveRenderPlaying)
    }

    @Test fun administrativeStopRetiresJoinedNativeBanksWithoutReopeningOutputAndNextOwnerCanPlay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("joined-route-stop-", ".wav", context.cacheDir)
        val actual = JniNativeEngineEnvironment(context, packetReaderFactory = app.stemwise.playback.engine.stemwiseAudioPacketReaderFactory)
        val holdRouteReopen = AtomicBoolean(true)
        val reopenAttempts = AtomicInteger()
        val environment = object : NativeEngineEnvironment by actual {
            override fun reopenOutputForRouteChange(): Boolean {
                reopenAttempts.incrementAndGet()
                return !holdRouteReopen.get() && actual.reopenOutputForRouteChange()
            }
        }
        val events = ConcurrentLinkedQueue<NativeEngineControllerEvent>()
        try {
            MergerOracleFixture.writeWave(source,
                IntArray(48_000 * 4) { frame ->
                    (kotlin.math.sin(2.0 * Math.PI * 330.0 * frame / 48_000) * 2_000).toInt()
                }, bits = 16, channels = 1)
            NativePlaybackEngineController(environment,
                listener = NativeEngineControllerListener { events.add(it) }).use { controller ->
                controller.setDiagnosticPayloadFingerprints(true)
                val first = controller.select(NativeEngineSelectionRequest(
                    "joined-route-before-stop", "joined-route-wave", source.absolutePath, 4_000L,
                    autoplay = true))
                await("The first owner must configure and present through a real physical sink") {
                    val initial = controller.diagnosticsSnapshot()
                    initial.runtime.ownership.committed?.selectionToken == first.value &&
                        initial.output?.lastPresentedSelectionToken == first.value
                }
                assertTrue(controller.diagnosticsSnapshot().runtime.banks.any { it.assigned })

                controller.requestRouteRecovery()
                await { reopenAttempts.get() > 0 &&
                    controller.diagnosticsSnapshot().runtime.status == NativeRuntimeStatus.STOPPED }
                assertTrue(controller.holdActorForSelectionBurstForTest())
                val before = controller.diagnosticsSnapshot()
                assertTrue("The joined runtime must still own the bank being retired",
                    before.runtime.banks.any { it.assigned })
                val outputBefore = requireNotNull(before.output)
                val attemptsBeforeStop = reopenAttempts.get()
                val stop = controller.stopForTaskRemoval()
                controller.releaseActorForSelectionBurstForTest()
                await { stop.isComplete() && events.any {
                    it is NativeEngineControllerEvent.StopCompleted && it.requestId == stop.id
                } }

                assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
                val stopped = controller.diagnosticsSnapshot()
                assertEquals(NativeRuntimeStatus.STOPPED, stopped.runtime.status)
                assertTrue("Completed STOP retained an assigned native bank: ${stopped.runtime.banks}",
                    stopped.runtime.banks.none { it.assigned })
                assertEquals(0, requireNotNull(stopped.decoderLeases).totalDecoderCount)
                assertEquals("STOP cannot retry the unavailable physical output", attemptsBeforeStop,
                    reopenAttempts.get())
                val stoppedOutput = requireNotNull(stopped.output)
                assertEquals(outputBefore.reopenCount, stoppedOutput.reopenCount)
                assertEquals(outputBefore.processConstructionCount, stoppedOutput.processConstructionCount)
                assertEquals(outputBefore.processLiveInstanceCount, stoppedOutput.processLiveInstanceCount)
                assertFalse(stoppedOutput.playing)
                assertFalse(stoppedOutput.effectiveRenderPlaying)
                assertEquals(listOf(stop.id), events.filterIsInstance<NativeEngineControllerEvent.StopCompleted>()
                    .map { it.requestId })

                holdRouteReopen.set(false)
                val replacement = controller.select(NativeEngineSelectionRequest(
                    "joined-route-after-stop", "joined-route-wave", source.absolutePath, 4_000L,
                    autoplay = false))
                val replacementDiagnostics = {
                    val snapshot = controller.diagnosticsSnapshot()
                    val output = snapshot.output
                    "runtime=${snapshot.runtime.status}, ownership=${snapshot.runtime.ownership}, " +
                        "banks=${snapshot.runtime.banks}, leases=${snapshot.decoderLeases}, " +
                        "reopenAttempts=${reopenAttempts.get()}, fatal=${snapshot.controllerFatalQuarantined}; " +
                        "output(state=${output?.streamState}, backend=${output?.backend}, " +
                        "generation=${output?.streamGeneration}, reopen=${output?.reopenCount}, " +
                        "recovery=${output?.recoveryRequired}, playing=${output?.playing}, " +
                        "effective=${output?.effectiveRenderPlaying}, queued=${output?.queuedFrames}, " +
                        "presentedToken=${output?.lastPresentedSelectionToken}, " +
                        "pcmToken=${output?.pcmLevelSelectionToken}, pcmFrames=${output?.pcmLevelFrames}, " +
                        "pcmPeak=${output?.pcmLevelPeak}); events=" + events.filter {
                            it is NativeEngineControllerEvent.Failure ||
                                it is NativeEngineControllerEvent.FatalRecoveryRequired ||
                                it is NativeEngineControllerEvent.OwnerPublished ||
                                it is NativeEngineControllerEvent.StopCompleted
                        }.takeLast(8).joinToString { it.toString().take(240) }
                }
                await("Paused replacement must commit without opening a physical sink",
                    diagnostics = replacementDiagnostics) {
                    controller.diagnosticsSnapshot().runtime.ownership.committed?.selectionToken == replacement.value
                }
                val pausedOutput = requireNotNull(controller.diagnosticsSnapshot().output)
                assertEquals(NativeAudioStreamState.CLOSED, pausedOutput.streamState)
                assertEquals(stoppedOutput.reopenCount, pausedOutput.reopenCount)
                assertEquals(attemptsBeforeStop, reopenAttempts.get())
                assertFalse(pausedOutput.playing)
                assertFalse(pausedOutput.effectiveRenderPlaying)
                assertEquals(outputBefore.lastErrorMonotonicMillis, pausedOutput.lastErrorMonotonicMillis)
                assertEquals(outputBefore.lastErrorCode, pausedOutput.lastErrorCode)
                assertTrue(controller.updateDesiredWithAudibleTransport(replacement, true) {
                    it.copy(playWhenReady = true)
                })
                await("Explicit Play must present replacement token ${replacement.value} nonzero PCM",
                    diagnostics = replacementDiagnostics) {
                    val output = controller.diagnosticsSnapshot().output
                    output?.lastPresentedSelectionToken == replacement.value &&
                        output.pcmLevelSelectionToken == replacement.value &&
                        output.pcmLevelFrames > 0L && output.pcmLevelPeak > 0f
                }
                val replacementOutput = requireNotNull(controller.diagnosticsSnapshot().output)
                assertEquals(outputBefore.processLiveInstanceCount, replacementOutput.processLiveInstanceCount)
                assertEquals("The next owner must not require feeder-failure retries",
                    outputBefore.lastErrorMonotonicMillis, replacementOutput.lastErrorMonotonicMillis)
                assertEquals(outputBefore.lastErrorCode, replacementOutput.lastErrorCode)
                assertFalse(events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
            }
        } finally {
            try { actual.close() } finally {
                check(source.delete()) { "Could not delete owned joined-route STOP fixture" }
            }
        }
    }

    private fun await(
        description: String = "Exact output owner did not settle",
        diagnostics: () -> String = { "" },
        condition: () -> Boolean
    ) {
        val deadline = System.nanoTime() + 3_000_000_000L
        do {
            if (condition()) return
            Thread.sleep(2)
        } while (System.nanoTime() < deadline)
        fail("$description: ${diagnostics()}")
    }
}
