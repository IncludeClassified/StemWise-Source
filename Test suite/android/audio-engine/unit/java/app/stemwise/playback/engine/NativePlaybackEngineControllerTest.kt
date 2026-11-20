package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import app.stemwise.playback.resolveCurrentManualSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.emptyLoopProtocolSnapshot
import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.request
import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.metadata
import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.queueItem
import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.currentStems
import app.stemwise.playback.engine.NativePlaybackControllerTestSupport.Companion.msToFrameForTest

@app.stemwise.qualification.ManagedAudioTest
internal class NativePlaybackEngineControllerTest : NativePlaybackControllerTestSupport() {
    @Test fun detailedOutputCaptureRemainsOffUntilExplicitlyRequestedAndCanStopOnTheLiveOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("capture-owner"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.environment.detailedCaptureChanges.isEmpty())
        val owner = fixture.environment.lastSelected()
        assertTrue(fixture.controller.setDetailedDiagnosticCapture(true))
        assertTrue(fixture.controller.setDetailedDiagnosticCapture(false))
        assertEquals(listOf(true, false), fixture.environment.detailedCaptureChanges)
        assertEquals(owner, fixture.environment.lastSelected())
    }

    @Test fun loopBoundaryObserverPreservesExactNativeFieldsWithoutRequestingDiagnosticWork() {
        val fixture = Fixture()
        fixture.controller.select(request("passive-boundary"))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(owner, timelineFrame = 48_321L)
        val position = requireNotNull(fixture.environment.controlProgress().presentedPosition)
        val output = NativeAudioDiagnosticsCodec.decode(NativePacketGoldenFixture.packet("OUTPUT_DIAGNOSTICS"))
            .copy(lastPresentedGeneration = owner.generation, lastPresentedSelectionToken = owner.selectionToken,
                callbackFrames = position.lastOutputFrame + 2_049L, queuedFrames = 4_096L)
        fixture.environment.observedOutput = output
        val runtimeReads = fixture.environment.detailedRuntimeReads
        val readinessReads = fixture.environment.renderReadinessDiagnosticReads
        val decoderReads = fixture.environment.decoderLeaseDiagnosticReads
        val outputReads = fixture.environment.outputDiagnosticReads
        repeat(2) {
            val observed = fixture.controller.loopBoundaryObservationForTest()
            assertSame(position, observed.first)
            assertSame(output, observed.second)
        }
        assertEquals(outputReads + 2, fixture.environment.outputDiagnosticReads)
        assertEquals(runtimeReads, fixture.environment.detailedRuntimeReads)
        assertEquals(readinessReads, fixture.environment.renderReadinessDiagnosticReads)
        assertEquals(decoderReads, fixture.environment.decoderLeaseDiagnosticReads)
    }

    @Test fun initialTuningAndMetronomeReachTheFirstNativeSelectionIncludingManualPrewarmClaims() {
        for (prewarm in listOf(false, true)) {
            val fixture = Fixture()
            if (prewarm) {
                val outgoing = fixture.controller.select(request("initial-outgoing"))
                fixture.scheduler.runNext()
                fixture.presentLatest()
                fixture.controller.setManualPrewarm(NativeEngineManualPrewarmRequest(
                    outgoing, queueItem("initial-target")))
                fixture.scheduler.runSteps(2)
            }
            val beats = longArrayOf(0L, 500L, 1_000L)
            val downbeats = booleanArrayOf(true, false, false)
            val initial = NativeEngineInitialControls(.75f, 5,
                EngineMetronomeConfig(true, true, 120f, .23f, 2, 3, 4, beats, downbeats))
            val preparedDecoders = fixture.environment.decoders.toList()
            val admission = fixture.controller.selectAdmission(request("initial-target").copy(initialControls = initial))
            assertEquals(if (prewarm) NativeEngineSelectionAdmissionKind.CLAIMED_PREWARM
                else NativeEngineSelectionAdmissionKind.NEW, admission.kind)
            beats[1] = 750L
            downbeats[0] = false
            fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == admission.token.value }
            val first = fixture.environment.snapshots.first {
                it.banks.any { bank -> bank.ownership?.selectionToken == admission.token.value } && it.selectedBank != null &&
                    it.banks[requireNotNull(it.selectedBank).nativeCode].ownership?.selectionToken == admission.token.value
            }
            if (prewarm) {
                assertEquals("Claiming saved controls reopened the decoded Original", preparedDecoders, fixture.environment.decoders)
            }
            assertEquals(.75f, first.controls.tempo, 0f)
            assertEquals(5f, first.controls.pitchSemitones, 0f)
            assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID, first.controls.metronome.mode)
            assertEquals(.23f, first.controls.metronome.gain, 0f)
            assertEquals(2, first.controls.metronome.subdivision)
            assertEquals(3, first.controls.metronome.beatsPerBar)
            val grid = fixture.environment.beatGridUpdates.first { it.ownership.selectionToken == admission.token.value }
            org.junit.Assert.assertArrayEquals(longArrayOf(0L, 24_000L, 48_000L), grid.beatFrames)
            org.junit.Assert.assertArrayEquals(booleanArrayOf(true, false, false), grid.beatAccents)
        }
    }

    @Test fun invalidInitialTimingCannotInvalidateAnExistingOwnerAndDefaultsRemainNeutral() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("initial-kept"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val first = fixture.environment.snapshots.first { it.selectedBank != null }
        assertEquals(1f, first.controls.tempo, 0f)
        assertEquals(0f, first.controls.pitchSemitones, 0f)
        assertEquals(NativeRuntimeMetronomeMode.OFF, first.controls.metronome.mode)
        val operations = fixture.environment.operations.toList()
        val beats = longArrayOf(0L, 500L)
        val controls = NativeEngineInitialControls(metronome = EngineMetronomeConfig(
            beatGridMs = beats, downbeats = booleanArrayOf(true, false)))
        beats[1] = 0L
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            fixture.controller.select(request("initial-rejected").copy(initialControls = controls))
        }
        assertEquals(operations, fixture.environment.operations)
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(fixture.controller.updateDesired(token) { it.copy(tempo = 1.25f) })
    }

    @Test fun oneMillisecondWindowAdmitsOnlyCompleteMatchingStemPcmAndNativeHorizon() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("one-ms").copy(playbackStartMs = 500L, playbackEndMs = 501L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = listOf("vocals", "remaining").map {
            EngineStemLane(it, it, "/one-ms-$it.wav", "sha-$it")
        }
        assertTrue(fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
            stemLanes = stems, stemRevision = "one-ms-v1",
            stemControls = stems.associate { lane -> lane.id to EngineStemControl() }) })
        fixture.scheduler.runNext()
        fixture.environment.decoders.forEach {
            it.setAcceptedCoverage(24_000L, 24_048L)
            it.exactEndOfStreamFrame = null
        }
        val lastPart = fixture.environment.decoders.last()
        lastPart.setAcceptedCoverage(24_000L, 24_047L)
        fixture.environment.setNativeStemRenderReadiness(owner, 24_000L,
            readyFrames = fixture.environment.requiredNativeStemRenderAheadFrames,
            rawEndTimelineFrameExclusive = 24_048L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertFalse(requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness).ready)
        lastPart.setAcceptedCoverage(24_000L, 24_048L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness).ready)
    }

    @Test fun automaticSuccessorStartsAndPublishesItsOwnPlaybackWindow() {
        val fixture = Fixture()
        fixture.controller.select(request("window-outgoing"), metadata("window-outgoing", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        val next = queueItem("window-next").let { it.copy(identity = it.identity.copy(
            playbackStartMs = 1_003L, playbackEndMs = 8_007L)) }
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(next))
        fixture.scheduler.runSteps(2)
        val snapshot = fixture.environment.snapshots.last { it.automaticSuccessor != null }
        val staged = requireNotNull(snapshot.automaticSuccessor)
        val bank = snapshot.banks[staged.successorBank.nativeCode]
        assertEquals(48_144L, bank.firstTimelineFrame)
        assertEquals(384_336L, bank.playbackEndFrameExclusive)
        assertTrue(fixture.environment.decoders.any {
            it.spec.location == "/window-next.ogg" && it.spec.initialPositionUs == 1_002_000L
        })
        fixture.environment.present(staged.successorOwnership, timelineFrame = 48_144L)
        fixture.scheduler.runNext()
        assertEquals(next.identity, fixture.ownerPublications().last().selection.audioIdentity())
        assertEquals(1_003L, fixture.ownerPublications().last().selection.startPositionMs)
    }

    @Test fun playbackWindowReachesNativeAssignmentAndRejectsOutsideLoops() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("trimmed").copy(
            durationMs = 10_000L, playbackStartMs = 1_003L, playbackEndMs = 8_007L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_144L)
        fun selectedBank() = fixture.environment.snapshots.last().let { it.banks[requireNotNull(it.selectedBank).nativeCode] }
        assertEquals(48_144L, selectedBank().firstTimelineFrame)
        assertEquals(384_336L, selectedBank().playbackEndFrameExclusive)
        assertEquals(482_048L, selectedBank().cacheFrameUpperBound)
        assertFalse(fixture.setLoop(token, EngineLoopConfig(1_002L, 2_000L)))
        assertFalse(fixture.setLoop(token, EngineLoopConfig(2_000L, 8_008L)))
        assertTrue(fixture.controller.updateDesired(token) { it.copy(requestedPositionMs = 10_000L,
            seekRevision = it.seekRevision + 1L) })
        fixture.scheduler.runNext()
        assertEquals(384_288L, selectedBank().firstTimelineFrame)
        assertEquals(384_336L, selectedBank().playbackEndFrameExclusive)
    }

    @Test
    fun steadyPlaybackPollsControlProgressWithoutRebuildingDetailedTelemetry() {
        val fixture = Fixture()
        fixture.controller.select(request("compact-progress"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.scheduler.runSteps(4)
        val runtimeBefore = fixture.environment.detailedRuntimeReads
        val readinessBefore = fixture.environment.renderReadinessDiagnosticReads
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val allocationBean = Class.forName("com.sun.management.ThreadMXBean")
        assertTrue(allocationBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        val bytesMethod = allocationBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val cpuMethod = Class.forName("java.lang.management.ThreadMXBean")
            .getMethod("getThreadCpuTime", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        val bytesBefore = bytesMethod.invoke(bean, thread) as Long
        val cpuBefore = cpuMethod.invoke(bean, thread) as Long
        fixture.scheduler.runSteps(64)
        val cpuAfter = cpuMethod.invoke(bean, thread) as Long
        val bytesAfter = bytesMethod.invoke(bean, thread) as Long
        println("steady actor allocated-bytes-per-turn=${(bytesAfter - bytesBefore) / 64L} " +
            "cpu-ns-per-turn=${(cpuAfter - cpuBefore) / 64L} " +
            "detail-runtime-reads=${fixture.environment.detailedRuntimeReads - runtimeBefore} " +
            "detail-readiness-reads=${fixture.environment.renderReadinessDiagnosticReads - readinessBefore}")
        assertTrue("Steady polls must use control progress, not the full diagnostic packet",
            fixture.environment.detailedRuntimeReads - runtimeBefore <= 1)
        assertTrue("Steady readiness details need only a bounded health refresh",
            fixture.environment.renderReadinessDiagnosticReads - readinessBefore <= 8)
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        val cpuInterface = Class.forName("java.lang.management.ThreadMXBean")
        val cpuSupported = cpuInterface.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) as Boolean
        val cpuEnabled = cpuInterface.getMethod("isThreadCpuTimeEnabled").invoke(bean) as Boolean
        if (cpuSupported) {
            val setCpuEnabled = cpuInterface.getMethod("setThreadCpuTimeEnabled", Boolean::class.javaPrimitiveType)
            if (!cpuEnabled) setCpuEnabled.invoke(bean, true)
            try {
                val startCpu = cpuMethod.invoke(bean, thread) as Long
                fixture.scheduler.runSteps(512)
                val endCpu = cpuMethod.invoke(bean, thread) as Long
                println("steady actor host-cpu-ns-per-turn-512=${(endCpu - startCpu) / 512L}")
            } finally { if (!cpuEnabled) setCpuEnabled.invoke(bean, false) }
        } else println("steady actor host CPU clock unavailable")
    }

    @Test
    fun stableUnsealedMaximumPlayablePartsReuseReadinessDetailsUntilAnOwnedInputChanges() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("stable-parts"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stems = listOf("vocals", "drums", "bass", "guitar", "piano", "flute", "organ", "crowd", "remaining")
            .map { EngineStemLane(it, it, "/stable-$it.flac", "sha-stable-$it") }
        assertTrue(fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
            stemLanes = stems, stemRevision = "stable-v1",
            stemControls = stems.associate { lane -> lane.id to EngineStemControl() }) })
        fixture.scheduler.runNext()
        fixture.scheduler.runSteps(2) // Ready event and its publication share the settled sample.
        val before = requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness)
        assertTrue(before.ready)
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val allocationBean = Class.forName("com.sun.management.ThreadMXBean")
        assertTrue(allocationBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) as Boolean)
        val bytesMethod = allocationBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        val bytesBefore = bytesMethod.invoke(bean, thread) as Long
        fixture.scheduler.runSteps(64)
        val bytesAfter = bytesMethod.invoke(bean, thread) as Long
        println("stable playable-nine-parts allocated-bytes-per-actor-turn=${(bytesAfter - bytesBefore) / 64L}")
        org.junit.Assert.assertSame("Unchanged readiness cannot rebuild its rich observation on every poll",
            before, fixture.controller.diagnosticsSnapshot().controllerStemReadiness)
        fixture.environment.decoders.single { it.laneIndex == 9 }.setAcceptedCoverage(0L, 128L)
        fixture.scheduler.runNext()
        val after = requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness)
        assertFalse(after.ready)
        assertFalse(after === before)
        assertTrue(after.failureReasons.any { it == "lane-9-short" })
    }

    @Test
    fun fullMusicalBeatGridsPreserveEveryEventThroughTheNativeMaximumEndpoint() {
        for (count in listOf(32_768, 32_769, 72_001)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("full-grid-$count").copy(durationMs = 7_200_000L, playbackEndMs = 7_200_000L))
            val grid = LongArray(count) { it * 100L }
            val accents = BooleanArray(count) { it % 4 == 0 }
            assertTrue(fixture.controller.updateDesired(token) { it.copy(metronome = EngineMetronomeConfig(
                enabled = true, songSynced = true, beatGridMs = grid, downbeats = accents)) })
            fixture.scheduler.runNext()
            val published = fixture.environment.beatGridUpdates.last()
            assertEquals(count, published.beatFrames.size)
            assertTrue(published.beatFrames.contentEquals(LongArray(count) { grid[it] * 48L }))
            assertTrue(published.beatAccents.contentEquals(accents))
            assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID, fixture.environment.snapshots.last().controls.metronome.mode)
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test
    fun oversizedMetronomeProfileIsRejectedWithoutRetiringAudioOrTheNextSong() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("dense-grid"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) { it.copy(metronome = EngineMetronomeConfig(
            enabled = true, songSynced = true, beatGridMs = LongArray(72_002) { it.toLong() },
            downbeats = BooleanArray(72_002))) })
        fixture.scheduler.runNext()
        assertEquals(NativeRuntimeMetronomeMode.OFF, fixture.environment.snapshots.last().controls.metronome.mode)
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.BeatGridNotReady && it.token == token })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertTrue(fixture.controller.updateDesired(token) { it.copy(tempo = 1.25f) })
        fixture.scheduler.runNext()
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
        val next = fixture.controller.select(request("after-dense-grid"))
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.environment.lastSelected()?.selectionToken == next.value
        }
        assertEquals(next.value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun unresolvedResolverFailureOrCancellationRestoresAutomaticSuccessorOnlyForCurrentRequest() {
        for (outcome in listOf("unavailable", "failure", "cancelled", "superseded")) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("resolver-owner-$outcome"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val plan = NativeEngineAutomaticEndPlan.NextOwner(queueItem("resolver-next-$outcome"))
            fixture.controller.setAutomaticEndPlan(plan)
            fixture.scheduler.runSteps(2)
            assertNotNull(fixture.environment.snapshots.last().automaticSuccessor)
            fixture.controller.beginManualSelectionIntentAdmission()
            fixture.controller.setAutomaticEndPlan(null)
            fixture.scheduler.runSteps(2)
            assertTrue(fixture.environment.manualSelectionGateArmed)
            assertNull(fixture.environment.snapshots.last().automaticSuccessor)
            var cleanups = 0
            val cancelled = CancellationException("resolver cancelled")

            val completion = runCatching { runBlocking {
                resolveCurrentManualSelection<String>(
                    resolve = {
                        when (outcome) {
                            "failure" -> throw java.io.IOException("provider unavailable")
                            "cancelled" -> throw cancelled
                            else -> null
                        }
                    },
                    isCurrent = { outcome != "superseded" },
                    onUnavailable = {
                        cleanups++
                        fixture.controller.completeManualSelectionIntentWithoutReplacement(token)
                        fixture.controller.setAutomaticEndPlan(plan)
                    }
                )
            } }
            if (outcome == "cancelled") assertTrue(completion.exceptionOrNull() === cancelled)
            else assertTrue(completion.isSuccess)
            if (outcome == "superseded") {
                assertEquals(0, cleanups)
                assertTrue(fixture.environment.manualSelectionGateArmed)
                assertNull(fixture.environment.snapshots.last().automaticSuccessor)
            } else {
                assertEquals(1, cleanups)
                fixture.scheduler.runNext()
                fixture.environment.acceptNonEmptyWithoutPresentation(requireNotNull(fixture.environment.lastSelected()))
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil {
                    fixture.environment.snapshots.last().automaticSuccessor != null &&
                        !fixture.environment.manualSelectionGateArmed
                }
                assertEquals(token.value, requireNotNull(fixture.environment.lastSelected()).selectionToken)
                assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
            }
        }
    }

    @Test
    fun optionalNativeReadFailureDetachesOnlyThePackageAndPreservesTheOriginalOwner() {
        for (mode in EngineSourceMode.entries) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("optional-native-$mode"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            var owner = requireNotNull(fixture.environment.lastSelected())
            for (attempt in 1..2) {
                val stems = currentStems("optional-retry-$attempt")
                assertTrue(fixture.controller.updateDesired(token) { it.copy(
                    stemLanes = stems, stemRevision = "optional-read-$attempt", sourceMode = mode,
                    stemControls = stems.associate { lane -> lane.id to EngineStemControl() }
                ) })
                if (attempt > 1) {
                    val retiredOwner = owner
                    fixture.scheduler.runUntil { fixture.environment.lastSelected() != retiredOwner }
                    owner = requireNotNull(fixture.environment.lastSelected())
                    assertTrue(owner.generation > retiredOwner.generation)
                    fixture.environment.present(owner, 0L)
                    fixture.environment.setNativeStemRenderReadiness(owner, 0L)
                    fixture.controller.signalNativeProgress()
                }
                fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
                val original = fixture.environment.decoders.single { !it.released && it.laneIndex == 0 }
                fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                    ingressFailureGeneration = owner.generation, ingressFailureLane = 3)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 1 }
                assertFalse(original.cancelAttempted)
                assertFalse(original.released)
                assertEquals(owner, fixture.environment.lastSelected())
                val failure = fixture.events.filterIsInstance<NativeEngineControllerEvent.DecoderFailed>().also { assertEquals(attempt, it.size) }.last()
                assertEquals(3, failure.laneIndex)
                assertTrue(failure.recoverable)
                assertTrue(fixture.events.any { it is NativeEngineControllerEvent.StemPackageFailed })
                assertFalse(fixture.events.any { it is NativeEngineControllerEvent.SelectionFailed ||
                    it is NativeEngineControllerEvent.FatalRecoveryRequired })
                assertEquals(0, fixture.environment.snapshots.last().banks[
                    requireNotNull(fixture.environment.snapshots.last().selectedBank).nativeCode].stemLaneCount)
                // Repeated stale native evidence has no authority after this package was detached.
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runNext()
                assertFalse(original.cancelAttempted)
                fixture.environment.setNativeStemRenderReadiness(owner, 0L)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runNext()

            }
        }
    }

    @Test
    fun nativeIngressFailureSettlesOnlyItsExactGeneration() {
        val fixture = Fixture()
        fixture.controller.select(request("native-ingress-failure"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            ingressFailureGeneration = owner.generation - 1L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.DecoderFailed })
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            ingressFailureGeneration = owner.generation)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val failure = fixture.events.filterIsInstance<NativeEngineControllerEvent.DecoderFailed>().single()
        assertEquals(owner.generation, failure.generation)
        assertFalse(failure.recoverable)
        assertTrue(fixture.environment.decoders.all { it.cancelAttempted && it.released })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun coldOriginalStartPrerollsDecoderWithoutMovingTheRequestedNativeCursor() {
        for (startMs in listOf(0L, 1L, 1_001L, 1_002L)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.controller.select(request("cold-quantized-$startMs").copy(startPositionMs = startMs))
            fixture.scheduler.runNext()
            val decoder = fixture.environment.decoders.first()
            assertEquals((startMs - 1L).coerceAtLeast(0L) * 1_000L, decoder.spec.initialPositionUs)
            val snapshot = fixture.environment.snapshots.last()
            val bank = snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode]
            assertEquals(startMs * 48L, bank.firstTimelineFrame)
            assertTrue(requireNotNull(decoder.head().firstTimelineFrame48k) <= bank.firstTimelineFrame)
            assertTrue(requireNotNull(decoder.head().acceptedTimelineFrameExclusive48k) > bank.firstTimelineFrame)
        }
    }

    @Test
    fun pendingCandidateKeepsLatestSeekAnchorThroughAdmission() {
        for (rebase in listOf(false, true)) {
            val fixture = Fixture()
            fixture.controller.select(request("seek-a"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.environment.newDecodersExposePcmHead = false
            val token = fixture.controller.select(request("seek-b"))
            fixture.scheduler.runNext()
            val decoder = fixture.environment.decoders.last()
            assertFalse(fixture.environment.lastSelected()?.selectionToken == token.value)
            assertTrue(fixture.controller.updateDesired(token) {
                it.copy(requestedPositionMs = 30_000L, seekRevision = it.seekRevision + 1L)
            })
            fixture.scheduler.runNext()
            if (rebase) fixture.environment.outputGeneration = 100L
            decoder.exposePcmHead = true
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
            val snapshot = fixture.environment.snapshots.last()
            assertEquals(1_440_000L,
                snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
            assertEquals(1_439_952L, decoder.head().firstTimelineFrame48k)
        }
    }

    @Test
    fun earlySeekAdmitsVerifiedShortEosTail() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("short-tail").copy(durationMs = 1_000L, playbackEndMs = 1_000L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 999L, seekRevision = it.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val decoder = fixture.environment.decoders.last()
        decoder.setAcceptedCoverage(47_952L, 48_000L)
        decoder.exactEndOfStreamFrame = 48_000L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(47_952L, fixture.environment.snapshots.last().let {
            it.banks[requireNotNull(it.selectedBank).nativeCode].firstTimelineFrame
        })
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun terminalCoverageMismatchSettlesCandidateAndPrewarmWithoutWaitingForMorePcm() {
        for (prewarm in listOf(false, true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = prewarm)
            val token = fixture.controller.select(request("fractional-tail").copy(durationMs = 1_001L, playbackEndMs = 1_001L))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.environment.newDecodersExposePcmHead = false
            if (prewarm) fixture.controller.setManualPrewarm(NativeEngineManualPrewarmRequest(
                token, queueItem("fractional-next")))
            else assertTrue(fixture.controller.updateDesired(token) {
                it.copy(requestedPositionMs = 981L, seekRevision = it.seekRevision + 1L)
            })
            fixture.scheduler.runNext()
            val decoder = fixture.environment.decoders.last()
            decoder.setAcceptedCoverage(47_088L, 48_001L)
            decoder.exactEndOfStreamFrame = 48_002L
            decoder.exposePcmHead = true
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
            assertTrue("Terminal coverage cannot wait for another decoder callback", decoder.cancelAttempted)
            fixture.scheduler.runUntil { decoder.released }
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test
    fun earlySeekUsesTheFirstAcceptedFrameWhenTheCodecStartsAfterTheRequestedSample() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("quantized-seek").copy(durationMs = 8_000L, playbackEndMs = 8_000L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.newDecodersExposePcmHead = false
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 7_500L, seekRevision = it.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val decoder = fixture.environment.decoders.last()
        assertEquals(listOf(decoder.decoderId), fixture.controller.diagnosticsSnapshot()
            .controllerCandidateDecoders.map { it.decoderId })
        val priming = fixture.environment.snapshots.last().banks[decoder.bank.nativeCode]
        val primingOwner = requireNotNull(priming.ownership)
        assertEquals(360_000L, priming.firstTimelineFrame)
        decoder.setAcceptedCoverage(360_001L, 384_000L)
        decoder.exactEndOfStreamFrame = 384_000L
        decoder.exposePcmHead = true
        assertEquals(360_001L, decoder.head().firstTimelineFrame48k)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val snapshot = fixture.environment.snapshots.last()
        val adopted = snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode]
        val adoptedOwner = requireNotNull(adopted.ownership)
        assertEquals(360_001L, adopted.firstTimelineFrame)
        assertEquals(primingOwner.selectionToken, adoptedOwner.selectionToken)
        assertTrue("Changing a primed bank's anchor requires a fresh native generation",
            adoptedOwner.generation > primingOwner.generation)
        assertEquals(listOf(adoptedOwner.generation), decoder.requestedSeekTargets)
        assertEquals(adoptedOwner.generation, decoder.head().nativeGeneration)
        assertEquals(1, decoder.nativeAdvanceCount)
        assertEquals(1, decoder.authorizationCount)
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun emptyEosCandidateSettlesAsFailureAndReleasesItsDecoder() {
        val fixture = Fixture()
        fixture.environment.newDecodersExposePcmHead = false
        val token = fixture.controller.select(request("empty-eos"))
        fixture.scheduler.runNext()
        val decoder = fixture.environment.decoders.single()
        decoder.exactEndOfStreamFrame = 0L

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue(decoder.cancelAttempted && decoder.released)
        assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == token })
        assertFalse(fixture.ownerPublications().any { it.selection.token == token })
    }

    @Test
    fun fakeNativeRejectsRegressingOutputGenerationWithoutAnOptIn() {
        val fixture = Fixture()
        fixture.controller.select(request("generation-fence"), metadata("generation-fence", 1L))
        fixture.scheduler.runNext()
        val accepted = fixture.environment.snapshots.last()
        assertEquals(NativeRuntimePublishStatus.ACCEPTED,
            fixture.environment.publish(accepted.copy(
                outputGeneration=accepted.outputGeneration + 2L, selectedBank=null, playing=false,
                controls=accepted.controls.copy(sourceIntentRevision=0L, stemIntentRevision=0L),
                automaticSuccessor=null
            )))
        assertEquals(NativeRuntimePublishStatus.INVALID_SNAPSHOT, fixture.environment.publish(accepted))
    }

    @Test
    fun gainCannotExposeAnAcceptedSeekBeforeItsCallbackFenceIsArmed() {
        val fixture = Fixture()
        val stems = currentStems("seek-gain-order")
        val token = fixture.controller.select(request("seek-gain-order"))
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(stemLanes = stems, stemRevision = "seek-gain-order")
        })
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val oldOwner = requireNotNull(fixture.environment.lastSelected())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var accepted = false
        fixture.controller.seekAdmissionBeforeGateArmForTest = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val seeker = thread(start = false, isDaemon = true) {
            try {
                accepted = fixture.controller.updateDesired(token) {
                    it.copy(requestedPositionMs = 1_010L, seekRevision = it.seekRevision + 1L)
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val actor = thread(start = false, isDaemon = true) {
            try { fixture.scheduler.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        var gainAccepted = false
        val gain = thread(start = false, isDaemon = true) {
            try {
                gainAccepted = fixture.controller.updateDesired(token) {
                    it.copy(stemControls = stems.associate { lane -> lane.id to EngineStemControl(gain = .5f) })
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            seeker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gain.start()
            actor.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((actor.state != Thread.State.BLOCKED || gain.state != Thread.State.BLOCKED) &&
                actor.isAlive && gain.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals("The actor cannot consume gain carrying an unfenced seek", Thread.State.BLOCKED, actor.state)
            assertEquals("A newer gain cannot publish ahead of the accepted seek fence", Thread.State.BLOCKED, gain.state)
        } finally {
            release.countDown()
            for (worker in listOf(seeker, gain, actor)) {
                if (worker.state != Thread.State.NEW) worker.join(TEST_THREAD_CLEANUP_MS)
                if (worker.isAlive) { worker.interrupt(); worker.join(TEST_THREAD_CLEANUP_MS) }
            }
            fixture.controller.seekAdmissionBeforeGateArmForTest = null
        }
        assertFalse(seeker.isAlive)
        assertFalse(gain.isAlive)
        assertFalse(actor.isAlive)
        failure.get()?.let { throw AssertionError("Seek/gain admission failed", it) }
        assertTrue(accepted)
        assertTrue(gainAccepted)
        fixture.scheduler.runUntil {
            fixture.environment.snapshots.last().controls.stems.take(stems.size).all { it.gain == .5f }
        }
        val sought = requireNotNull(fixture.environment.lastSelected())
        assertEquals(token.value, sought.selectionToken)
        assertTrue(sought.generation > oldOwner.generation)
        val snapshot = fixture.environment.snapshots.last()
        assertEquals(msToFrameForTest(1_010L), snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
        assertTrue(snapshot.controls.stems.take(stems.size).all { it.gain == .5f })
        fixture.environment.present(sought, msToFrameForTest(1_010L))
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { !fixture.environment.manualSelectionGateArmed }
        assertEquals(sought, fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
    }

    @Test
    fun acceptedTransportAfterMailboxDrainCannotBeLostAtAutomaticPromotion() {
        for (action in listOf("pause", "seek", "play")) {
            val seek = action == "seek"
            val fixture = Fixture()
            val token = fixture.controller.select(request("late-transport-a"))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 48_000L)
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(queueItem("late-transport-b")))
            fixture.scheduler.runUntil { fixture.environment.snapshots.last().automaticSuccessor != null }
            val staged = requireNotNull(fixture.environment.snapshots.last().automaticSuccessor)
            if (action == "play") assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, false) {
                it.copy(playWhenReady = false)
            })
            fixture.environment.onNextControlProgress = {
                if (seek) {
                    assertTrue(fixture.controller.updateDesired(token) {
                        it.copy(requestedPositionMs = 250L, seekRevision = it.seekRevision + 1L)
                    })
                    assertTrue("Accepted seek must already fence callback rendering", fixture.environment.manualSelectionGateArmed)
                } else assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, action == "play") {
                    it.copy(playWhenReady = action == "play")
                })
                if (action == "play") fixture.environment.installPausedAutomaticSuccessor(staged)
                else fixture.environment.present(staged.successorOwnership, 0L)
            }
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
            if (seek) {
                assertFalse(fixture.ownerPublications().any { it.selection.songId == "late-transport-b" })
                fixture.scheduler.runUntil { fixture.environment.lastSelected()?.let {
                    it.selectionToken == token.value && it.generation > staged.successorOwnership.generation
                } == true }
                val sought = requireNotNull(fixture.environment.lastSelected())
                fixture.environment.present(sought, msToFrameForTest(250L))
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runSteps(3)
                assertEquals(sought, fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
                assertFalse(fixture.ownerPublications().any { it.selection.songId == "late-transport-b" })
            } else {
                assertEquals(staged.successorOwnership, fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
                assertEquals(action == "play", fixture.environment.snapshots.last().playing)
            }
            assertTrue(outgoing.generation < staged.successorOwnership.generation)
        }
    }

    @Test
    fun automaticPromotionCannotSplitOwnerAndControlAdmission() {
        for (paused in listOf(false, true)) for (action in listOf("select-c", "reselect-b", "stop")) {
            val fixture = Fixture()
            val outgoing = fixture.controller.select(request("transaction-a"), metadata("transaction-a", 1L))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 24_000L)
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(queueItem("transaction-b")))
            fixture.scheduler.runSteps(2)
            val staged = requireNotNull(fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }?.automaticSuccessor)
            if (paused) {
                assertTrue(fixture.controller.updateDesiredWithAudibleTransport(outgoing, false) { it.copy(playWhenReady = false) })
                fixture.environment.installPausedAutomaticSuccessor(staged)
            } else fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val attempted = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            fixture.controller.automaticPromotionBeforeInstallForTest = {
                accepted.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val actor = thread(start = false, isDaemon = true) {
                try { fixture.scheduler.runNext() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
            var manual: EngineSelectionToken? = null
            var stop: NativeEngineStopRequest? = null
            var selector: Thread? = null
            try {
                actor.start()
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                selector = thread(isDaemon = true) {
                    try {
                        attempted.countDown()
                        if (action == "stop") stop = fixture.controller.stopForTaskRemoval()
                        else {
                            val song = if (action == "reselect-b") "transaction-b" else "transaction-c"
                            val nextRequest = request(song).let {
                                if (action == "reselect-b") it.copy(forceReload = true) else it
                            }
                            manual = fixture.controller.select(nextRequest, metadata(song, 1L))
                        }
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                }
                assertTrue(attempted.await(5, TimeUnit.SECONDS))
                val activeSelector = requireNotNull(selector)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (activeSelector.state != Thread.State.BLOCKED && activeSelector.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Manual selection must share the promotion transaction", Thread.State.BLOCKED, activeSelector.state)
            } finally {
                release.countDown()
                for (worker in listOfNotNull(actor, selector)) {
                    worker.join(TEST_THREAD_CLEANUP_MS)
                    if (worker.isAlive) { worker.interrupt(); worker.join(TEST_THREAD_CLEANUP_MS) }
                }
                fixture.controller.automaticPromotionBeforeInstallForTest = null
            }
            assertFalse(actor.isAlive)
            assertFalse(selector?.isAlive ?: false)
            failure.get()?.let { throw AssertionError("Controller interleaving failed", it) }
            if (action == "stop") {
                fixture.scheduler.runNext()
                assertEquals(NativeEngineStopStatus.COMPLETED, checkNotNull(stop).status())
            } else assertTrue(fixture.controller.updateDesired(checkNotNull(manual)) { it.copy(tempo = 1.25f) })
            assertFalse(fixture.controller.updateDesired(staged.successorOwnership.selectionToken.let(::EngineSelectionToken)) { it })
        }
    }

    @Test
    fun audibleEndListenerRunsAfterControllerAdmissionIsReleased() {
        lateinit var fixture: Fixture
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        var selector: Thread? = null
        var completedInsideCallback = false
        fixture = Fixture(onEvent = { event ->
            if (event is NativeEngineControllerEvent.AudibleEnd) {
                val finished = CountDownLatch(1)
                selector = thread(isDaemon = true) {
                    try { fixture.controller.select(request("end-c"), metadata("end-c", 1L)) }
                    catch (error: Throwable) { failure.compareAndSet(null, error) }
                    finally { finished.countDown() }
                }
                completedInsideCallback = finished.await(2L, TimeUnit.SECONDS)
            }
        })
        try {
            fixture.controller.select(request("end-a"), metadata("end-a", 1L))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 24_000L)
            fixture.environment.end(checkNotNull(fixture.environment.lastSelected()))
            fixture.scheduler.runNext()
        } finally {
            selector?.let { worker ->
                worker.join(TEST_THREAD_CLEANUP_MS)
                if (worker.isAlive) { worker.interrupt(); worker.join(TEST_THREAD_CLEANUP_MS) }
            }
        }
        failure.get()?.let { throw AssertionError("Selection failed", it) }
        assertTrue("End callback retained the controller gate", completedInsideCallback)
        assertFalse(selector?.isAlive ?: true)
    }

    @Test
    fun endpointSeekKeepsNativeAnchorWhileBothDecoderPathsRetainPreroll() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("endpoint").copy(
            durationMs = 1_000L, playbackEndMs = 1_000L, startPositionMs = 1_000L))
        fixture.scheduler.runNext()
        assertEquals(998_000L, fixture.environment.decoders.single().spec.initialPositionUs)
        val snapshot = fixture.environment.snapshots.last()
        assertEquals(47_952L, snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
        fixture.presentLatest(timelineFrame = 47_952L)
        assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
            requestedPositionMs = 1_000L, seekRevision = previous.seekRevision + 1L) })
        fixture.scheduler.runNext()
        assertEquals(47_904L, fixture.environment.decoders.first().head().firstTimelineFrame48k)
        val after = fixture.environment.snapshots.last()
        assertEquals(47_952L, after.banks[requireNotNull(after.selectedBank).nativeCode].firstTimelineFrame)
    }

    @Test
    fun selectedEarlySeekDecoderFailureMustNotRegressNativeOutputGeneration() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.enforceNativeOutputGeneration = true
        val token = fixture.controller.select(request("failed-early-seek"))
        fixture.scheduler.runNext()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.presentLatest(timelineFrame = 0L)
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 10_000L, seekRevision = it.seekRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != outgoing }
        val replacement = requireNotNull(fixture.environment.lastSelected())
        assertTrue(replacement.generation > outgoing.generation)
        fixture.environment.decoders.single {
            !it.released && it.spec.initialPositionUs == 10_000_000L
        }.failAsync("replacement decoder failed before presentation")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val fatals = fixture.events.filterIsInstance<NativeEngineControllerEvent.FatalRecoveryRequired>()
        assertTrue("Recoverable replacement failure quarantined playback: $fatals", fatals.isEmpty())
        fixture.scheduler.runUntil {
            (fixture.environment.lastSelected()?.generation ?: 0L) > replacement.generation
        }
        val recovered = requireNotNull(fixture.environment.lastSelected())
        assertEquals(token.value, recovered.selectionToken)
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.head().firstTimelineFrame48k == msToFrameForTest(9_999L)
        })
    }

    @Test
    fun exclusiveEosBoundaryUsesNativeCompatibleCeilAt44100Hz() {
        assertEquals(0L, sourceBoundaryToNativeTimelineCeil(0L, 44_100))
        assertEquals(2L, sourceBoundaryToNativeTimelineCeil(1L, 44_100))
        assertEquals(48_000L, sourceBoundaryToNativeTimelineCeil(44_100L, 44_100))
        assertEquals(
            (12_345L * 48_000L + 44_099L) / 44_100L,
            sourceBoundaryToNativeTimelineCeil(12_345L, 44_100)
        )
    }

    @Test
    fun initialIdleWaitsForAnOwnerWithoutPublishingAnEqualGenerationFlush() {
        val fixture = Fixture()
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.isEmpty())
        assertTrue("Library construction needs no runtime or physical output", fixture.environment.operations.isEmpty())

        val token = fixture.controller.select(request("paused-first-owner", autoplay = false))
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.operations.contains("start-runtime"))
        assertFalse(fixture.environment.operations.contains("prepare-output"))
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(fixture.environment.snapshots.isNotEmpty())
        assertTrue(fixture.environment.snapshots.all { it.outputGeneration > 1L && !it.playing })
    }

    @Test
    fun immediateCommandPreemptsDelayedPollAndPublishesWithoutWaitingForItsDeadline() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preempt-poll"))
        fixture.scheduler.runNext()
        assertEquals(10L, fixture.scheduler.scheduledDelays.last())

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(playWhenReady = false)
        })
        fixture.scheduler.runNext()

        assertTrue(fixture.scheduler.cancelledDelayedTasks >= 1)
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun commandArrivingAfterDelayedPollStartsCannotLoseTheOnlyActorWake() {
        val fixture = Fixture()
        fixture.controller.select(request("outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.scheduler.markNextDelayedTaskStarted()

        val latest = fixture.controller.select(request("latest"))
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.lastSelected()?.selectionToken == latest.value
        }

        assertEquals(latest.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(fixture.scheduler.activeTaskCount() > 0)
    }

    @Test
    fun transportAndSuccessorStagingDoNotRepublishUnchangedAudioControls() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stable-controls"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val initialRevision = fixture.environment.snapshots.last().controls.revision

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = false
        ) { previous -> previous.copy(playWhenReady = false) })
        fixture.scheduler.runNext()
        assertEquals(initialRevision, fixture.environment.snapshots.last().controls.revision)

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = true
        ) { previous -> previous.copy(playWhenReady = true) })
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("stable-controls-next"))
        )
        fixture.scheduler.runSteps(3)
        val staged = fixture.environment.snapshots.last { it.automaticSuccessor != null }
        assertEquals(initialRevision, staged.controls.revision)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(tempo = 1.1f)
        })
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.last().controls.revision > initialRevision)
    }

    @Test
    fun pollingCadenceIsFastForTransitionsModerateWhilePlayingAndSlowWhenPaused() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("poll-cadence"))
        fixture.scheduler.runNext()
        assertEquals(10L, fixture.scheduler.scheduledDelays.last())

        fixture.presentLatest()
        assertEquals(10L, fixture.scheduler.scheduledDelays.last())
        val owner = requireNotNull(fixture.environment.lastSelected())
        val initialSnapshot = fixture.environment.snapshots.last()
        fixture.environment.presentAudibleControl(
            owner,
            initialSnapshot.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil(maximumSteps = 4) {
            fixture.scheduler.scheduledDelays.lastOrNull() == 20L
        }
        assertEquals(20L, fixture.scheduler.scheduledDelays.last())

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(playWhenReady = false)
        })
        fixture.scheduler.runNext()
        assertEquals(250L, fixture.scheduler.scheduledDelays.last())

        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 4_000L)))
        fixture.scheduler.runNext()
        assertEquals(250L, fixture.scheduler.scheduledDelays.last())
    }

    @Test
    fun stablePauseQuiescesOnceThenResumeReusesOwnerWithoutIdlePolling() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("quiesced-owner"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())
        val playingSnapshot = fixture.environment.snapshots.last()
        fixture.environment.presentAudibleControl(
            owner,
            playingSnapshot.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.scheduler.scheduledDelays.lastOrNull() == 20L
        }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(playWhenReady = false)
        })
        fixture.scheduler.runNext()

        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.quiesceOutputCallCount == 1 &&
                fixture.scheduler.activeTaskCount() == 0
        }
        assertTrue(
            "quiescence did not settle; delays=${fixture.scheduler.scheduledDelays} " +
                "operations=${fixture.environment.operations}",
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
        )
        val settled = fixture.controller.diagnosticsSnapshot()
        val readinessReads = fixture.environment.renderReadinessDiagnosticReads
        val actorTurns = settled.controllerActorTurnCount
        assertEquals(1L, settled.controllerOutputQuiesceCount)
        assertEquals(1, fixture.environment.quiesceOutputCallCount)
        assertTrue(fixture.environment.decoders.filterNot { it.released }.all {
            it.sinkRetriesThrottled
        })
        assertEquals(actorTurns, fixture.controller.diagnosticsSnapshot().controllerActorTurnCount)
        assertEquals(readinessReads + 1, fixture.environment.renderReadinessDiagnosticReads)
        assertEquals(0, fixture.scheduler.activeTaskCount())

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(playWhenReady = true)
        })
        fixture.scheduler.runNextImmediate()
        val resumed = fixture.controller.diagnosticsSnapshot()
        assertFalse(resumed.controllerOutputPhysicallyQuiesced)
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.filterNot { it.released }.none {
            it.sinkRetriesThrottled
        })
        assertEquals(1, fixture.environment.quiesceOutputCallCount)
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        assertFalse(fixture.environment.operations.contains("recreate-runtime"))
    }

    @Test
    fun focusLossPollsPhysicalQuiescenceImmediatelyAndWaitsForItsActualAcknowledgement() {
        val fixture = Fixture()
        fixture.environment.outputWarmPauseGraceMs = 60_000L
        fixture.controller.select(request("persistent-focus-proof"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.quiesceOutputReady = false
        val beforeFocusLoss = fixture.scheduler.monotonicTimeMs()
        fixture.controller.suspendForAudioFocusLoss()
        fixture.scheduler.runNextImmediate()
        assertEquals(listOf(true), fixture.environment.outputSessionSuspensions)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals("Focus suspension cannot wait for the practice-session idle grace", 1,
            fixture.environment.quiesceOutputCallCount)
        assertFalse("A pause request is not physical-consumer acknowledgement",
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        assertEquals(owner, fixture.environment.lastSelected())
        fixture.environment.quiesceOutputReady = true
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
        }
        assertEquals(2, fixture.environment.quiesceOutputCallCount)
        assertTrue("Only the short acknowledgement retry may elapse",
            fixture.scheduler.monotonicTimeMs() - beforeFocusLoss < 60_000L)
        assertEquals(owner, fixture.environment.lastSelected())
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        assertFalse(fixture.environment.operations.contains("recreate-runtime"))
    }

    @Test
    fun focusSuspendedReplacementKeepsImmediateRetirementWithoutRestartingTheIdleGrace() {
        val fixture = Fixture()
        fixture.environment.outputWarmPauseGraceMs = 60_000L
        fixture.controller.select(request("focus-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.quiesceOutputReady = false
        fixture.controller.suspendForAudioFocusLoss()
        fixture.scheduler.runNextImmediate()
        assertEquals(1, fixture.environment.quiesceOutputCallCount)
        val beforeReplacementSnapshots = fixture.environment.snapshots.size
        val replacement = fixture.controller.select(request("focus-replacement"))
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        assertTrue("Normal replacement first publishes the outgoing owner's unassignment",
            fixture.environment.snapshots.drop(beforeReplacementSnapshots).any { it.selectedBank == null })
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals("A newly accepted paused owner must not restart the idle grace while focus is suspended",
            2, fixture.environment.quiesceOutputCallCount)
        assertFalse(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        val acceptedAt = fixture.scheduler.monotonicTimeMs()
        assertTrue(fixture.controller.updateDesired(replacement) { it.copy(tempo = 1.1f) })
        fixture.scheduler.runNextImmediate()
        assertEquals(replacement.value, fixture.environment.snapshots.last().selectedOwnership?.selectionToken)
        assertEquals(1.1f, fixture.environment.snapshots.last().controls.tempo, 0f)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals("Repeated accepted pause snapshots keep the existing acknowledgement cadence",
            2, fixture.environment.quiesceOutputCallCount)
        fixture.environment.quiesceOutputReady = true
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
        }
        assertEquals(3, fixture.environment.quiesceOutputCallCount)
        assertTrue(fixture.scheduler.monotonicTimeMs() - acceptedAt < 60_000L)
        assertEquals(listOf(true), fixture.environment.outputSessionSuspensions)
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun sessionPauseBudgetIsDistinctFromImmediateFocusSuspension() {
        val fixture = Fixture()
        fixture.environment.outputWarmPauseGraceMs = 60_000L
        val token = fixture.controller.select(request("persistent-owner"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
        fixture.scheduler.runNextImmediate()
        fixture.scheduler.advanceTimeBy(1_500L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()
        assertEquals("An ordinary pause retains the explicitly configured session", 0,
            fixture.environment.quiesceOutputCallCount)

        fixture.controller.suspendForAudioFocusLoss()
        fixture.scheduler.runNextImmediate()
        assertEquals(listOf(true), fixture.environment.outputSessionSuspensions)
        assertEquals("Focus loss polls actual physical retirement immediately", 1, fixture.environment.quiesceOutputCallCount)
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        fixture.controller.resumeAfterAudioFocusGain()
        fixture.scheduler.runNextImmediate()
        assertEquals(listOf(true, false), fixture.environment.outputSessionSuspensions)

        // Restart transport, then independently prove that an ordinary new pause gets its full idle grace.
        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) })
        fixture.scheduler.runNextImmediate()
        assertFalse(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
        fixture.scheduler.runNextImmediate()
        fixture.scheduler.advanceTimeBy(59_999L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()
        assertEquals("The practice session keeps its ordinary-pause grace", 1, fixture.environment.quiesceOutputCallCount)
        fixture.scheduler.advanceTimeBy(1L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()
        assertEquals("The distinct idle boundary still ends the session", 2, fixture.environment.quiesceOutputCallCount)
    }

    @Test
    fun quiescedSeekWaitsForTheNewOwnersAsynchronousReadinessBeforeReturningToIdle() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("paused-seek-readiness", autoplay = false))
        fixture.scheduler.runNext()
        val originalOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(originalOwner)
        fixture.environment.sealCurrentSongCacheFor(originalOwner)
        fixture.environment.setNativeStemRenderReadiness(originalOwner, 0L)
        fixture.scheduler.runUntil(maximumSteps = 12) { fixture.scheduler.activeTaskCount() == 0 }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 2_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val seekOwner = requireNotNull(fixture.environment.lastSelected())
        assertNotEquals(originalOwner, seekOwner)
        fixture.environment.installPaused(seekOwner)
        fixture.scheduler.runNext()
        assertTrue("A paused seek stopped observing before its asynchronous readiness arrived",
            fixture.scheduler.activeTaskCount() > 0)
        fixture.environment.sealCurrentSongCacheFor(seekOwner)
        fixture.environment.setNativeStemRenderReadiness(seekOwner, 96_000L)
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.scheduler.activeTaskCount() == 0 }
        assertEquals(seekOwner, fixture.controller.diagnosticsSnapshot().controllerPublishedLoopOwnership)
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals(0, fixture.scheduler.activeTaskCount())
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun pausedCacheObservesBindingAfterItsSameOwnerUnboundSample() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("paused-cache-late-bind", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
        }
        val before = fixture.controller.diagnosticsSnapshot()
        assertEquals(owner, before.controllerObservedRenderReadiness?.ownership)
        assertFalse(requireNotNull(before.controllerObservedRenderReadiness).currentSongCacheBoundToSelectedOwner)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L..8_191L
        fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = true,
            cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        assertTrue("An admitted cache must keep observing its not-yet-bound exact owner",
            fixture.scheduler.activeTaskCount() > 0)
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable
        }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals(0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun completePausedCachePublishesRangeRetryBeforeReturningToIdle() {
        val fixture = Fixture()
        fixture.controller.select(request("paused-complete-cache-probe", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.scheduler.runUntil(maximumSteps = 12) { fixture.scheduler.activeTaskCount() == 0 }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        fixture.environment.holdCacheRangeProof = true
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val pending = fixture.controller.diagnosticsSnapshot()
        assertFalse(pending.controllerPublishedLoopAvailable)
        assertTrue(requireNotNull(pending.controllerObservedRenderReadiness).currentSongCacheCompleteLaneMask != 0)
        assertEquals(true, pending.controllerCacheReadinessPending)
        assertTrue(fixture.scheduler.activeTaskCount() > 0)
        fixture.environment.releaseCacheRangeProof()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable
        }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals(0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun pausedCacheRetiresItsObserverOnlyForTheExactOwnersTypedStorageFailure() {
        for (status in listOf(NativeCacheStorageStatus.LIMIT_EXCEEDED,
                NativeCacheStorageStatus.INSUFFICIENT_SPACE, NativeCacheStorageStatus.IO_FAILURE)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.controller.select(request("paused-cache-failure-$status", autoplay = false))
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.installPaused(owner)
            fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
            fixture.scheduler.runUntil(maximumSteps = 12) {
                fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
            }
            fixture.environment.setNativeStemRenderReadiness(owner.copy(generation = owner.generation + 1L),
                0L, cacheStorageStatus = status)
            fixture.scheduler.runNext()
            assertTrue("Another owner's failure cannot settle this cache request",
                fixture.scheduler.activeTaskCount() > 0)
            fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheStorageStatus = status)
            fixture.scheduler.runUntil(maximumSteps = 8) { fixture.scheduler.activeTaskCount() == 0 }
            val failed = fixture.controller.diagnosticsSnapshot()
            assertFalse(failed.controllerPublishedLoopAvailable)
            assertEquals(false, failed.controllerCacheReadinessPending)
            assertEquals(status, failed.controllerObservedRenderReadiness?.cacheStorageStatus)
            assertEquals(0, fixture.scheduler.activeTaskCount())
        }
    }

    @Test
    fun pausedCachePublishesReadinessBeforeItsObserverCanPark() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("paused-cache-probe-race", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = true)
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
        }
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L..8_191L
        fixture.environment.holdCacheRangeProof = true
        fixture.scheduler.runNext()
        assertFalse(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals(true, fixture.controller.diagnosticsSnapshot().controllerCacheReadinessPending)
        // The real progress listener publishes readiness before the pending observer can park.
        fixture.environment.releaseCacheRangeProof()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .any { it.ownership == owner && it.available }
        }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals("Published readiness settles the paused observer", 0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun pausedOriginalCacheKeepsItsObserverUntilCurrentRangeReadyOrFailed() {
        for (succeeds in listOf(true, false)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.controller.select(request("paused-cache-$succeeds", autoplay = false))
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.installPaused(owner)
            fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = true)
            fixture.scheduler.runUntil(maximumSteps = 12) {
                fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced
            }
            assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
            assertTrue("Original cache priming lost its paused observer", fixture.scheduler.activeTaskCount() > 0)
            if (succeeds) {
                fixture.environment.residentCacheOwner = owner
                fixture.environment.residentCacheRange = 0L..8_191L
            }
            fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = succeeds,
                cacheStorageStatus = if (succeeds) NativeCacheStorageStatus.ADMITTED else NativeCacheStorageStatus.IO_FAILURE)
            fixture.scheduler.runUntil(maximumSteps = 8) { fixture.scheduler.activeTaskCount() == 0 }
            assertEquals(succeeds, fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
            assertEquals("Settled or failed cache must not keep polling", 0, fixture.scheduler.activeTaskCount())
        }
    }

    @Test
    fun fastBContractionAcrossCursorRemainsArmed() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-crossed-b"))
        fixture.scheduler.runNext()

        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 2_000L, parkUntilRangeEntry = false),
            heldBGestureId = 41L
        ))
        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 1_000L, parkUntilRangeEntry = true),
            heldBGestureId = 41L,
            capturedBMs = 1_000L,
            captureReferencePositionMs = 1_500L
        ))

        val crossing = fixture.environment.directLoopPolicies.last()
        assertTrue(crossing.boundaryArmedAtPublication)
        assertTrue(crossing.boundaryCrossedAtPublication)
        assertEquals(41L, crossing.heldBGestureId)
        assertEquals(48_000L, crossing.capturedBTimelineFrameExclusive)
        assertEquals(72_000L, crossing.captureReferenceTimelineFrame)
    }

    @Test
    fun playbackOvertakingRightMovingBRemainsArmed() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-cursor-overtakes-b"))
        fixture.scheduler.runNext()

        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 1_000L, parkUntilRangeEntry = false)
        ))
        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 1_100L, parkUntilRangeEntry = true)
        ))

        val crossing = fixture.environment.directLoopPolicies.last()
        assertTrue(crossing.boundaryArmedAtPublication)
        assertTrue(crossing.boundaryCrossedAtPublication)
    }

    @Test
    fun foldedHistoricalBCrossingKeepsNewestVisibleBAndCapturedBoundary() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-folded-b"))
        fixture.scheduler.runNext()

        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 2_000L, parkUntilRangeEntry = false),
            heldBGestureId = 43L
        ))
        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 1_800L, parkUntilRangeEntry = false),
            heldBGestureId = 43L,
            capturedBMs = 1_000L,
            captureReferencePositionMs = 1_200L
        ))

        val crossing = fixture.environment.directLoopPolicies.last()
        assertEquals(msToFrameForTest(1_800L), crossing.bTimelineFrameExclusive)
        assertEquals(msToFrameForTest(1_000L), crossing.capturedBTimelineFrameExclusive)
        assertEquals(msToFrameForTest(1_200L), crossing.captureReferenceTimelineFrame)
        assertTrue(crossing.boundaryArmedAtPublication)
        assertTrue(crossing.boundaryCrossedAtPublication)
    }

    @Test
    fun fourThousandManualSelectionsFromAudibleOwnerUseOneSilenceEdgeAndOnlyFinalDecoder() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val outgoing = request("storm-outgoing")
        fixture.controller.select(outgoing, metadata(outgoing.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(outgoing.identity(), outgoing.durationMs)
        )
        fixture.scheduler.runUntil {
            fixture.environment.openLoopPreparationCount == 1
        }

        var finalToken: EngineSelectionToken? = null
        repeat(4_000) { index ->
            finalToken = fixture.controller.select(request("song-${index % 400}"))
        }

        fixture.scheduler.runNext()

        assertEquals(finalToken?.value, fixture.environment.lastSelected()?.selectionToken)
        assertEquals(2, fixture.environment.openedDecoderCount)
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
        assertEquals(
            1L,
            fixture.controller.diagnosticsSnapshot()
                .manualSelectionSilenceGenerationCaptureCount
        )
        assertEquals(0, fixture.environment.openLoopPreparationCount)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertEquals("/song-399.ogg", fixture.environment.decoders.single {
            !it.released
        }.spec.location)
        assertEquals(listOf("storm-outgoing"), fixture.ownerPublications().map {
            it.selection.songId
        })
    }

    @Test
    fun fourHundredAndFourThousandStaleMetadataUpdatesRetainOnlyTwoLiveTokens() {
        listOf(400, 4_000).forEach { updateCount ->
            val fixture = Fixture()
            val public = fixture.controller.select(
                request("metadata-public-$updateCount"),
                metadata("metadata-public-$updateCount", 1L)
            )
            fixture.scheduler.runNext()
            fixture.presentLatest()

            val pending = fixture.controller.select(
                request("metadata-pending-$updateCount"),
                metadata("metadata-pending-$updateCount", 1L)
            )
            repeat(updateCount) { index ->
                fixture.controller.updateMetadata(
                    metadata("metadata-stale-$updateCount-$index", index + 1L)
                )
            }

            val retained = fixture.controller.retainedMetadataForTest()
            assertTrue(
                "$updateCount stale identity updates retained ${retained.size} metadata records",
                retained.size <= 2
            )
            assertEquals(setOf(public.value, pending.value), retained.keys)
            fixture.scheduler.runNext()
            assertEquals(
                fixture.controller.retainedMetadataForTest().size,
                fixture.controller.diagnosticsSnapshot().retainedMetadataTokenCount
            )
        }
    }

    @Test
    fun metadataUpdatesBindIndependentlyToPublicAAndPendingB() {
        val fixture = Fixture()
        val public = fixture.controller.select(
            request("metadata-public-a"),
            metadata("metadata-public-a", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val pending = fixture.controller.select(
            request("metadata-pending-b"),
            metadata("metadata-pending-b", 1L)
        )
        fixture.controller.updateMetadata(metadata("metadata-public-a", 5L))
        fixture.controller.updateMetadata(metadata("metadata-pending-b", 6L))

        val retainedWhilePending = fixture.controller.retainedMetadataForTest()
        assertEquals(5L, retainedWhilePending[public.value]?.revision)
        assertEquals(6L, retainedWhilePending[pending.value]?.revision)
        assertEquals(2, retainedWhilePending.size)

        fixture.scheduler.runNext()
        fixture.presentLatest()
        val publication = fixture.ownerPublications().last()
        assertEquals(pending, publication.selection.token)
        assertEquals(6L, publication.metadata?.revision)
    }

    @Test
    fun ownerWithoutInitialMetadataResetsRevisionAndIgnoresLowerOrEqualUpdates() {
        val fixture = Fixture()
        fixture.controller.select(
            request("metadata-high-owner"),
            metadata("metadata-high-owner", 100L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val token = fixture.controller.select(request("metadata-empty-owner"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertNull(fixture.ownerPublications().last().metadata)

        val eventStart = fixture.events.size
        fixture.controller.updateMetadata(
            NativeEngineMetadata(
                "metadata-empty-owner",
                "rev-metadata-empty-owner",
                5L,
                "accepted-five"
            )
        )
        fixture.scheduler.runNext()

        fixture.controller.updateMetadata(
            NativeEngineMetadata(
                "metadata-empty-owner",
                "rev-metadata-empty-owner",
                4L,
                "rejected-four"
            )
        )
        fixture.controller.updateMetadata(
            NativeEngineMetadata(
                "metadata-empty-owner",
                "rev-metadata-empty-owner",
                5L,
                "rejected-equal-five"
            )
        )
        fixture.scheduler.runNext()
        assertEquals(
            "accepted-five",
            fixture.controller.retainedMetadataForTest()[token.value]?.payloadKey
        )

        fixture.controller.updateMetadata(
            NativeEngineMetadata(
                "metadata-empty-owner",
                "rev-metadata-empty-owner",
                6L,
                "accepted-six"
            )
        )
        fixture.scheduler.runNext()

        val revisions = fixture.events.drop(eventStart)
            .filterIsInstance<NativeEngineControllerEvent.MetadataUpdated>()
            .map { it.metadata.revision }
        assertEquals(listOf(5L, 6L), revisions)
        assertEquals(
            "accepted-six",
            fixture.controller.retainedMetadataForTest()[token.value]?.payloadKey
        )
    }

    @Test
    fun metadataForUnselectedAudioRevisionCannotLeakIntoLaterSameSongOwner() {
        val fixture = Fixture()
        val songId = "metadata-same-song"
        val oldRequest = request(songId).copy(
            audioRevision = "same-song-audio-v1",
            originalLocation = "/metadata-same-song-v1.ogg"
        )
        val oldMetadata = NativeEngineMetadata(
            songId,
            oldRequest.audioRevision,
            1L,
            "old-audio"
        )
        fixture.controller.select(oldRequest, oldMetadata)
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val neverSelectedUpdate = NativeEngineMetadata(
            songId,
            "same-song-audio-v2",
            999L,
            "must-not-leak"
        )
        fixture.controller.updateMetadata(neverSelectedUpdate)
        fixture.scheduler.runNext()

        val newRequest = oldRequest.copy(
            audioRevision = "same-song-audio-v2",
            originalLocation = "/metadata-same-song-v2.ogg"
        )
        val selectedMetadata = NativeEngineMetadata(
            songId,
            newRequest.audioRevision,
            1L,
            "selected-audio"
        )
        val token = fixture.controller.select(newRequest, selectedMetadata)
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val publication = fixture.ownerPublications().last()
        assertEquals(token, publication.selection.token)
        assertEquals("selected-audio", publication.metadata?.payloadKey)
        assertEquals(
            "selected-audio",
            fixture.controller.retainedMetadataForTest()[token.value]?.payloadKey
        )
        assertFalse(
            fixture.events.filterIsInstance<NativeEngineControllerEvent.MetadataUpdated>()
                .any { it.metadata == neverSelectedUpdate }
        )
    }

    @Test
    fun manualStormReleasesActiveAndPrewarmThroughOneLogicalEmptyGeneration() {
        val fixture = Fixture()
        fixture.controller.select(request("epoch-outgoing"), metadata("epoch-outgoing", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("epoch-prewarm"))
        )
        fixture.scheduler.runSteps(2)
        assertNotNull(fixture.environment.snapshots.lastOrNull {
            it.automaticSuccessor != null
        })
        val snapshotStart = fixture.environment.snapshots.size

        var finalToken: EngineSelectionToken? = null
        repeat(400) { index ->
            finalToken = fixture.controller.select(request("epoch-target-$index"))
        }
        fixture.scheduler.runNext()

        val newSnapshots = fixture.environment.snapshots.drop(snapshotStart)
        val unselected = newSnapshots.filter { it.selectedBank == null }
        assertTrue(
            "Both assigned banks must publish their exact unassignment revisions",
            unselected.size >= 2
        )
        assertTrue(
            "Every unselected controller snapshot must carry exact 0/0 fences",
            unselected.all {
                it.controls.sourceIntentRevision == 0L &&
                    it.controls.stemIntentRevision == 0L && !it.playing
            }
        )
        assertEquals(
            "One selected-to-empty interval must reuse one physical invalidation generation",
            1,
            unselected.map { it.outputGeneration }.distinct().size
        )
        val emptyGeneration = unselected.first().outputGeneration
        val finalSelected = requireNotNull(newSnapshots.lastOrNull {
            it.selectedOwnership?.selectionToken == finalToken?.value
        })
        assertTrue(finalSelected.controls.sourceIntentRevision > 0L)
        assertTrue(finalSelected.controls.stemIntentRevision > 0L)
        assertTrue(finalSelected.outputGeneration > emptyGeneration)
    }

    @Test
    fun concurrentManualSelectorsShareOneSilenceGateAndGenerationCapture() {
        val fixture = Fixture()
        fixture.controller.select(request("concurrent-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()

        val start = CountDownLatch(1)
        val complete = CountDownLatch(32)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val selectors = List(32) { index ->
            thread(name = "selection-$index", isDaemon = true) {
                try {
                    start.await()
                    fixture.controller.select(request("concurrent-$index"))
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    complete.countDown()
                }
            }
        }
        var completed = false
        try {
            start.countDown()
            completed = complete.await(TEST_THREAD_WATCHDOG_MS, TimeUnit.MILLISECONDS)
        } finally {
            start.countDown()
            selectors.forEach(Thread::interrupt)
            selectors.forEach { it.join(TEST_THREAD_CLEANUP_MS) }
        }

        assertTrue("Concurrent selectors did not complete", completed)
        assertTrue("Concurrent selector thread survived cleanup", selectors.none { it.isAlive })
        assertTrue("Concurrent selectors failed: $failures", failures.isEmpty())
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
        val diagnostics = fixture.controller.diagnosticsSnapshot()
        assertEquals(1L, diagnostics.manualSelectionSilenceEdgeCount)
        assertEquals(1L, diagnostics.manualSelectionSilenceGenerationCaptureCount)
    }

    @Test
    fun failedManualTargetNeverRestoresOutgoingAudibleOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("failure-outgoing"), metadata("failure-outgoing", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.failNextOpenAtLane(0)

        val failed = fixture.controller.select(
            request("failure-target"),
            metadata("failure-target", 2L)
        )
        fixture.scheduler.runNext()

        assertNull(fixture.environment.lastSelected())
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
        assertEquals(listOf("failure-outgoing"), fixture.ownerPublications().map {
            it.selection.songId
        })
        assertEquals(listOf(failed), fixture.events.filterIsInstance<
            NativeEngineControllerEvent.SelectionFailed
        >().map { it.token })

        fixture.environment.present(outgoing, timelineFrame = 10_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertNull(fixture.environment.lastSelected())
        assertEquals(1, fixture.ownerPublications().size)
    }

    @Test
    fun localStartPresentationCancelsTheExactSettlementDeadline() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("deadline-success"))
        fixture.scheduler.runNext()

        fixture.presentLatest(timelineFrame = 48_000L)
        fixture.scheduler.advanceTimeBy(15_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()

        assertTrue(fixture.ownerPublications().any { it.selection.token == token })
        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == token })
    }

    @Test
    fun localStartWithoutPresentationFailsAtTheExactFifteenSecondBoundary() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("deadline-expiry"))
        fixture.scheduler.runNext()
        val ownership = requireNotNull(fixture.environment.lastSelected())

        fixture.scheduler.advanceTimeBy(14_999L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()
        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == token })

        // The actor shortened its existing transition poll to the one remaining millisecond.
        fixture.scheduler.runNext()
        val failure = fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .single { it.token == token }
        assertEquals(15_000L, fixture.scheduler.monotonicTimeMs())
        assertEquals("Playback could not start within 15 seconds.", failure.cause)
        assertEquals(ownership.selectionToken, token.value)
        assertNull(fixture.environment.lastSelected())
    }

    @Test
    fun pauseCancelsPendingLocalStartSettlement() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("deadline-pause"))
        fixture.scheduler.runNext()

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, false) { current ->
            current.copy(playWhenReady = false)
        })
        fixture.scheduler.runNextImmediate()
        fixture.scheduler.advanceTimeBy(15_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()

        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == token })
    }

    @Test
    fun supersededStartDeadlineCannotFailTheNewerToken() {
        val fixture = Fixture()
        val stale = fixture.controller.select(request("deadline-stale"))
        fixture.scheduler.runNext()
        fixture.scheduler.advanceTimeBy(10_000L)

        val latest = fixture.controller.select(request("deadline-latest"))
        fixture.scheduler.runNextImmediate()
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == latest.value
        }
        fixture.scheduler.advanceTimeBy(5_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNextImmediate()

        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == stale || it.token == latest })
        fixture.presentLatest()
        assertTrue(fixture.ownerPublications().any { it.selection.token == latest })
    }

    @Test
    fun committedBIsNeverAuthorizedAfterCBecomesTheLatestManualSelection() {
        val fixture = Fixture()
        fixture.controller.select(request("gate-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()

        val b = fixture.controller.select(request("gate-b"))
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == b.value
        }
        val bOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(bOwnership)

        val c = fixture.controller.select(request("gate-c"))
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.manualSelectionGateAuthorizations.isEmpty())
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == c.value
        }
        val cOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(cOwnership)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(cOwnership.generation)
        }

        assertEquals(listOf(cOwnership.generation),
            fixture.environment.manualSelectionGateAuthorizations)
        assertFalse(fixture.environment.manualSelectionGateArmed)
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
    }

    @Test
    fun failedBHandsItsArmedGateToPausedValidCWithoutAnotherPhysicalArm() {
        val fixture = Fixture()
        fixture.controller.select(request("failed-gate-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()
        fixture.environment.failNextOpenAtLane(0)

        val b = fixture.controller.select(request("failed-gate-b"))
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
                .any { it.token == b }
        }
        assertTrue(fixture.environment.manualSelectionGateArmed)

        val c = fixture.controller.select(request("failed-gate-c", autoplay = false))
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == c.value
        }
        val cOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(cOwnership)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.ownerPublications().any { it.selection.token == c }
        }

        assertEquals(listOf(cOwnership.generation),
            fixture.environment.manualSelectionGateAuthorizations)
        assertFalse(fixture.environment.manualSelectionGateArmed)
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
        assertEquals(NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            fixture.ownerPublications().last().kind)
    }

    @Test
    fun newerUnselectedEarlySeekReplacementStillVetoesTheOlderPendingGeneration() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(
            request("newer-early-seek-gate").copy(startPositionMs = 5_000L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val initial = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.manualSelectionGateAuthorizations.clear()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 0L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val pending = requireNotNull(fixture.environment.lastSelected())
        assertTrue(pending.generation > initial.generation)
        fixture.environment.acceptNonEmptyWithoutPresentation(pending)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 12_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val replacement = requireNotNull(fixture.environment.lastSelected())
        assertTrue(replacement.generation > pending.generation)
        assertEquals(1, fixture.environment.decoders.count {
            !it.released && it.role == NativeSongBankRole.CANDIDATE && it.laneIndex == 0
        })
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertTrue(fixture.environment.manualSelectionGateAuthorizations.isEmpty())

        fixture.environment.acceptNonEmptyWithoutPresentation(replacement)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(
                replacement.generation
            )
        }
        assertEquals(
            listOf(replacement.generation),
            fixture.environment.manualSelectionGateAuthorizations
        )
    }

    @Test
    fun earlySeekReplacementNeverAuthorizesTheOutgoingGeneration() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("early-seek-gate"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.manualSelectionGateAuthorizations.clear()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 12_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val replacement = requireNotNull(fixture.environment.lastSelected())

        assertTrue(replacement.generation > outgoing.generation)
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertTrue(fixture.environment.manualSelectionGateAuthorizations.isEmpty())

        fixture.environment.acceptNonEmptyWithoutPresentation(replacement)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(
                replacement.generation
            )
        }

        assertEquals(
            listOf(replacement.generation),
            fixture.environment.manualSelectionGateAuthorizations
        )
    }

    @Test
    fun rapidSeekAfterAuthorizationRearmsAndRevokesTheUnpresentedGeneration() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("authorized-rapid-seek"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.environment.operations.clear()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 4_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val authorizedButUnpresented = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.acceptNonEmptyWithoutPresentation(authorizedButUnpresented)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(
                authorizedButUnpresented.generation
            )
        }
        assertFalse(fixture.environment.manualSelectionGateArmed)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 15_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val latest = requireNotNull(fixture.environment.lastSelected())

        assertTrue(latest.generation > authorizedButUnpresented.generation)
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertEquals(2, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })

        fixture.environment.acceptNonEmptyWithoutPresentation(latest)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(latest.generation)
        }
        assertEquals(
            listOf(authorizedButUnpresented.generation, latest.generation),
            fixture.environment.manualSelectionGateAuthorizations
        )
        assertFalse(fixture.environment.manualSelectionGateArmed)
    }

    @Test
    fun authorizedButUncommittedFailureRearmsGateForTheNextValidSelection() {
        val fixture = Fixture()
        fixture.controller.select(request("authorized-failure-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()

        val failed = fixture.controller.select(request("authorized-failure-b"))
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == failed.value
        }
        val failedOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.acceptNonEmptyWithoutPresentation(failedOwnership)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(
                failedOwnership.generation
            )
        }
        val failedDecoder = fixture.environment.decoders.single {
            !it.released && it.spec.selectionToken == failed && it.laneIndex == 0
        }
        failedDecoder.failAsync("accepted block then decoder failed")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
                .any { it.token == failed }
        }

        val recovered = fixture.controller.select(request("authorized-failure-c"))
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.lastSelected()?.selectionToken == recovered.value
        }
        val recoveredOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.acceptNonEmptyWithoutPresentation(recoveredOwnership)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(
                recoveredOwnership.generation
            )
        }

        assertEquals(
            listOf(failedOwnership.generation, recoveredOwnership.generation),
            fixture.environment.manualSelectionGateAuthorizations
        )
        assertEquals(2, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
    }

    @Test
    fun stalePresentationCannotPublishMetadataAndPausedNonemptyInstallCan() {
        val fixture = Fixture()
        val stale = fixture.controller.select(request("stale"), metadata("stale", 1L))
        val latest = fixture.controller.select(
            request("paused", autoplay = false),
            metadata("paused", 2L)
        )
        fixture.scheduler.runNext()
        val latestOwnership = requireNotNull(fixture.environment.lastSelected())

        fixture.environment.present(
            NativeRuntimeOwnership(stale.value, latestOwnership.generation),
            timelineFrame = 0L
        )
        fixture.scheduler.runNext()
        assertTrue(fixture.ownerPublications().isEmpty())

        fixture.environment.installPaused(latestOwnership)
        fixture.scheduler.runNext()

        val publication = fixture.ownerPublications().single()
        assertEquals(latest, publication.selection.token)
        assertEquals(latestOwnership, publication.ownership)
        assertEquals(NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            publication.kind)
        assertNull(publication.evidence)
    }

    @Test
    fun assignedAutomaticSuccessorClaimedWhileReprimePendingSelectsAfterExactPrime() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(
            request("assigned-reprime-a"),
            metadata("assigned-reprime-a", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("assigned-reprime-b"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        val successorDecoder = fixture.environment.decoders.single {
            it.spec.decoderId.startsWith("2-")
        }
        successorDecoder.exposePcmHead = false

        assertTrue(fixture.controller.updateDesired(outgoing) { previous ->
            previous.copy(
                requestedPositionMs = 12_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        assertEquals(outgoing.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(successorDecoder.requestedSeekTargets.any {
            it > staged.successorOwnership.generation
        })

        val claimed = fixture.controller.select(
            request("assigned-reprime-b"),
            metadata("assigned-reprime-b", 2L)
        )
        fixture.scheduler.runNext()
        assertNull(fixture.environment.lastSelected())

        successorDecoder.exposePcmHead = true
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(3)

        assertEquals(claimed.value, fixture.environment.lastSelected()?.selectionToken)
        assertNull(fixture.environment.snapshots.last().automaticSuccessor)
        val selected = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(selected, timelineFrame = 0L)
        fixture.scheduler.runNext()
        assertEquals(claimed, fixture.ownerPublications().last().selection.token)
    }

    @Test
    fun assignedSuccessorClaimRebasesAboveAcceptedEmptyEpochBeforeOutputObservationCatchesUp() {
        val fixture = Fixture()
        fixture.controller.select(
            request("accepted-epoch-a"),
            metadata("accepted-epoch-a", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("accepted-epoch-b"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        val successorDecoder = fixture.environment.decoders.single {
            it.spec.decoderId.startsWith("2-")
        }
        val snapshotStart = fixture.environment.snapshots.size

        // Production command publication is synchronous, while the native render worker applies
        // the output epoch asynchronously. Model that exact interval instead of letting the fake
        // environment advance both sides together and hide a producer-ordering regression.
        fixture.environment.holdOutputGenerationObservationOnPublish = true
        val claimed = fixture.controller.select(
            request("accepted-epoch-b"),
            metadata("accepted-epoch-b", 2L)
        )
        fixture.scheduler.runNext()

        val publications = fixture.environment.snapshots.drop(snapshotStart)
        val emptyEpoch = requireNotNull(publications.firstOrNull {
            it.selectedBank == null
        }).outputGeneration
        val selected = requireNotNull(publications.lastOrNull {
            it.selectedOwnership?.selectionToken == claimed.value
        })
        assertTrue(
            "A claimed prewarm must be newer than the already accepted empty owner epoch",
            selected.outputGeneration > emptyEpoch
        )
        assertTrue(selected.outputGeneration > staged.successorOwnership.generation)
        assertTrue(successorDecoder.requestedSeekTargets.contains(selected.outputGeneration))

        fixture.environment.holdOutputGenerationObservationOnPublish = false
        fixture.environment.outputGeneration = selected.outputGeneration
        val selectedOwnership = requireNotNull(selected.selectedOwnership)
        fixture.environment.present(selectedOwnership, timelineFrame = 0L)
        fixture.scheduler.runUntil {
            fixture.ownerPublications().lastOrNull()?.selection?.token == claimed
        }
        assertEquals(claimed, fixture.ownerPublications().last().selection.token)
        assertEquals(selectedOwnership, fixture.ownerPublications().last().ownership)
        assertTrue(
            fixture.environment.manualSelectionGateAuthorizations.contains(
                selected.outputGeneration
            )
        )
    }

    @Test
    fun automaticSuccessorStaysDormantThenPromotesOnlyFromExactCallbackEvidence() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(request("auto-a"), metadata("auto-a", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("auto-b"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        assertTrue(fixture.environment.decoders.any {
            it.spec.location == "/auto-b.ogg" && it.laneIndex == 0
        })

        assertEquals(outgoing.value, staged.outgoingOwnership.selectionToken)
        assertEquals(outgoing.value, fixture.environment.lastSelected()?.selectionToken)
        assertEquals(listOf("auto-a"), fixture.ownerPublications().map { it.selection.songId })
        assertTrue(fixture.controller.updateDesired(outgoing) { it.copy(tempo = 1.25f) })
        fixture.scheduler.runNext()

        fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()

        val promoted = fixture.ownerPublications().last()
        assertEquals("auto-b", promoted.selection.songId)
        assertEquals(staged.successorOwnership, promoted.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            promoted.kind
        )
        assertNotNull(promoted.evidence)
        assertEquals(staged.successorOwnership, fixture.environment.lastSelected())
        assertFalse(fixture.controller.updateDesired(outgoing) { it.copy(tempo = .75f) })
        assertTrue(fixture.controller.updateDesired(promoted.selection.token) {
            it.copy(tempo = .75f)
        })
    }

    @Test
    fun exactPublishedAutomaticSuccessorAdmissionDoesNotRearmOrAllocate() {
        val fixture = Fixture()
        fixture.controller.select(request("already-current-a"), metadata("already-current-a", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("already-current-b"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()
        val promoted = fixture.ownerPublications().last()
        val diagnosticsBefore = fixture.controller.diagnosticsSnapshot()
        val decoderCountBefore = fixture.environment.decoders.size
        val publicationCountBefore = fixture.ownerPublications().size

        val admission = fixture.controller.selectAdmission(
            request("already-current-b"),
            metadata("already-current-b", 2L)
        )

        val diagnosticsAfter = fixture.controller.diagnosticsSnapshot()
        assertEquals(NativeEngineSelectionAdmissionKind.ALREADY_CURRENT, admission.kind)
        assertEquals(promoted.selection.token, admission.token)
        assertEquals(decoderCountBefore, fixture.environment.decoders.size)
        assertEquals(publicationCountBefore, fixture.ownerPublications().size)
        assertEquals(
            diagnosticsBefore.manualSelectionSilenceEdgeCount,
            diagnosticsAfter.manualSelectionSilenceEdgeCount
        )
        assertEquals(
            diagnosticsBefore.manualSelectionSilenceGenerationCaptureCount,
            diagnosticsAfter.manualSelectionSilenceGenerationCaptureCount
        )
        assertEquals(promoted.ownership, fixture.environment.lastSelected())
    }

    @Test
    fun forcedSameSongSelectionCreatesANewNeutralOwner() {
        val fixture = Fixture()
        val request = request("forced-current")
        val first = fixture.controller.select(request, metadata("forced-current", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)

        val admission = fixture.controller.selectAdmission(
            request.copy(forceReload = true),
            metadata("forced-current", 2L)
        )

        assertEquals(NativeEngineSelectionAdmissionKind.NEW, admission.kind)
        assertNotEquals(first, admission.token)
        fixture.scheduler.runNext()
        org.junit.Assert.assertSame("Reselection cannot reopen a pathname under fresh byte authority",
            fixture.environment.decoders.first().spec.sourceAdmission,
            fixture.environment.decoders.last().spec.sourceAdmission)
    }

    @Test
    fun distinctSongsSharingAudioRevisionPrewarmAndPromoteWithoutIdentityCollision() {
        val fixture = Fixture()
        val sharedRevision = "shared-canonical-audio-v1"
        val outgoingRequest = NativeEngineSelectionRequest(
            songId = "canonical-a",
            audioRevision = sharedRevision,
            originalLocation = "/canonical/a/original.flac",
            durationMs = 180_000L
        )
        val successorRequest = NativeEngineSelectionRequest(
            songId = "canonical-b",
            audioRevision = sharedRevision,
            originalLocation = "/canonical/b/original.flac",
            durationMs = 181_000L
        )
        val outgoingMetadata = NativeEngineMetadata(
            songId = outgoingRequest.songId,
            audioRevision = sharedRevision,
            revision = 1L,
            payloadKey = "metadata-a"
        )
        val successorMetadata = NativeEngineMetadata(
            songId = successorRequest.songId,
            audioRevision = sharedRevision,
            revision = 2L,
            payloadKey = "metadata-b"
        )

        val outgoing = fixture.controller.select(outgoingRequest, outgoingMetadata)
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        val outgoingOwnership = requireNotNull(fixture.environment.lastSelected())
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(successorRequest.identity(), successorMetadata)
            )
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        val candidate = fixture.environment.decoders.single {
            !it.released && it.spec.location == successorRequest.originalLocation
        }

        assertNotEquals(outgoingRequest.identity(), successorRequest.identity())
        assertEquals(outgoing.value, staged.outgoingOwnership.selectionToken)
        assertNotEquals(staged.outgoingOwnership, staged.successorOwnership)
        assertEquals(staged.successorOwnership.selectionToken, candidate.spec.selectionToken.value)
        assertEquals(successorRequest.originalLocation, candidate.spec.location)
        assertEquals(listOf("canonical-a"), fixture.ownerPublications().map { it.selection.songId })

        fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()

        val successorPublications = fixture.ownerPublications().filter {
            it.selection.songId == successorRequest.songId
        }
        assertEquals(1, successorPublications.size)
        val promoted = successorPublications.single()
        assertEquals(sharedRevision, promoted.selection.audioRevision)
        assertEquals(successorRequest.originalLocation, promoted.selection.originalLocation)
        assertEquals(staged.successorOwnership, promoted.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            promoted.kind
        )
        assertEquals(successorMetadata, promoted.metadata)
        assertFalse(fixture.controller.updateDesired(outgoing) { it.copy(tempo = .75f) })
        assertTrue(fixture.controller.updateDesired(promoted.selection.token) {
            it.copy(tempo = .75f)
        })

        val publicationCount = fixture.ownerPublications().size
        fixture.environment.present(outgoingOwnership, timelineFrame = 48_000L)
        fixture.scheduler.runNext()
        assertEquals(publicationCount, fixture.ownerPublications().size)
        assertEquals(promoted.ownership, fixture.environment.lastSelected())
    }

    @Test
    fun preparedSuccessorMetadataRefreshKeepsItsAudioRevisionAndExactGeneration() {
        val fixture = Fixture()
        fixture.controller.select(request("metadata-current"), metadata("metadata-current", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 47_990L)
        val item = queueItem("metadata-successor")
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(item))
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(fixture.environment.snapshots.lastOrNull {
            it.automaticSuccessor != null
        }?.automaticSuccessor)
        val decoder = fixture.environment.decoders.single {
            it.spec.selectionToken.value == staged.successorOwnership.selectionToken
        }
        val opens = fixture.environment.openedDecoderCount
        val requests = fixture.controller.diagnosticsSnapshot().controllerAutomaticEndPlanRequestCount
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(
            item.copy(metadata = item.metadata.copy(revision = item.metadata.revision + 1L,
                payloadKey = "updated title"))))
        fixture.scheduler.runSteps(4)
        assertEquals(opens, fixture.environment.openedDecoderCount)
        assertFalse(decoder.cancelAttempted)
        assertTrue(decoder.requestedSeekTargets.isEmpty())
        assertEquals(staged, fixture.environment.snapshots.last().automaticSuccessor)
        assertEquals(requests, fixture.controller.diagnosticsSnapshot().controllerAutomaticEndPlanRequestCount)
        fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()
        assertEquals("updated title", requireNotNull(fixture.ownerPublications().last().metadata).payloadKey)
    }

    @Test
    fun drainStageProducerHighWaterDoesNotRebaseItsOwnExactSuccessor() {
        val fixture = Fixture()
        fixture.controller.select(request("drain-high-water"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("drain-high-water-next"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )
        val candidateDecoder = fixture.environment.decoders.single {
            it.spec.decoderId.startsWith("${staged.successorOwnership.selectionToken}-")
        }

        // beginDrainHandoff() reserves the successor ring and deliberately advances this producer
        // high-water value before the callback makes the successor audible.
        fixture.environment.outputGeneration = staged.successorOwnership.generation
        fixture.scheduler.runSteps(5)

        assertTrue(candidateDecoder.requestedSeekTargets.isEmpty())
        assertTrue(
            fixture.environment.snapshots.mapNotNull { it.automaticSuccessor }
                .all { it.successorOwnership == staged.successorOwnership }
        )
    }

    @Test
    fun pauseSerializedAtAutomaticBoundaryGovernsThePromotedSuccessor() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(request("pause-boundary"), metadata("pause-boundary", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("pause-successor"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = outgoing,
            playWhenReady = false
        ) { it.copy(playWhenReady = false) })
        // Make callback evidence visible before the actor turn. The actor must serialize Pause
        // first and must not let the natural boundary recreate an autoplay=true successor.
        fixture.environment.present(staged.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()

        val promoted = fixture.ownerPublications().last()
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            promoted.kind
        )
        assertFalse(promoted.selection.autoplay)
        var promotedDesired: EngineDesiredState? = null
        assertTrue(fixture.controller.updateDesired(promoted.selection.token) {
            promotedDesired = it
            it
        })
        assertFalse(requireNotNull(promotedDesired).playWhenReady)
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun appliedPausedAutomaticSuccessorCommitsExactReservationWithoutPresentationEvidence() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(
            request("paused-adoption-outgoing"),
            metadata("paused-adoption-outgoing", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("paused-adoption-successor"))
        )
        fixture.scheduler.runSteps(2)
        val staged = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = outgoing,
            playWhenReady = false
        ) { it.copy(playWhenReady = false) })
        // Native can claim the already-staged bank while applying the serialized Pause command.
        // No successor PCM is emitted, so the controller must use only the exact native ownership
        // tuple and must not manufacture presentation evidence.
        fixture.environment.installPausedAutomaticSuccessor(staged)
        fixture.scheduler.runNext()

        val publication = fixture.ownerPublications().last()
        assertEquals("paused-adoption-successor", publication.selection.songId)
        assertEquals(staged.successorOwnership, publication.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED,
            publication.kind
        )
        assertNull(publication.evidence)
        assertFalse(publication.selection.autoplay)
        assertEquals(staged.successorOwnership, fixture.environment.lastSelected())
        assertFalse(fixture.environment.snapshots.last().playing)
        var successorDesired: EngineDesiredState? = null
        assertTrue(fixture.controller.updateDesired(publication.selection.token) { desired ->
            successorDesired = desired
            desired
        })
        assertFalse(requireNotNull(successorDesired).playWhenReady)
    }

    @Test
    fun newerManualSelectionDefeatsStaleAppliedPausedSuccessorAdoption() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(
            request("stale-paused-outgoing"),
            metadata("stale-paused-outgoing", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("stale-paused-successor"))
        )
        fixture.scheduler.runSteps(2)
        val stale = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = outgoing,
            playWhenReady = false
        ) { it.copy(playWhenReady = false) })
        fixture.environment.installPausedAutomaticSuccessor(stale)
        val manual = fixture.controller.select(
            request("newer-manual-selection"),
            metadata("newer-manual-selection", 1L)
        )

        // The actor observes the newer manual owner request in the same turn as stale native
        // adoption diagnostics. Its newer token invalidates the old reservation before commit.
        fixture.scheduler.runNext()
        assertTrue(fixture.ownerPublications().none {
            it.selection.songId == "stale-paused-successor"
        })

        fixture.scheduler.runSteps(2)
        val manualOwnership = requireNotNull(fixture.environment.lastSelected())
        assertEquals(manual.value, manualOwnership.selectionToken)
        fixture.environment.present(manualOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()

        val publication = fixture.ownerPublications().last()
        assertEquals(manual, publication.selection.token)
        assertEquals("newer-manual-selection", publication.selection.songId)
        assertEquals(NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED, publication.kind)
        assertTrue(fixture.ownerPublications().none {
            it.selection.songId == "stale-paused-successor"
        })
    }

    @Test
    fun callbackAudibleSuccessorWinsOverLikelyNextHintChangedBeforeActorPoll() {
        val fixture = Fixture()
        val outgoing = fixture.controller.select(request("hint-outgoing"), metadata("hint-outgoing", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("audible-a"))
        )
        fixture.scheduler.runSteps(2)
        val stagedA = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor
        )

        // The native callback has already crossed into A. Before the controller actor polls that
        // evidence, queue policy advances its hint to B.
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("next-b"))
        )
        fixture.environment.present(stagedA.successorOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()

        val publication = fixture.ownerPublications().last()
        assertEquals(outgoing.value, stagedA.outgoingOwnership.selectionToken)
        assertEquals("audible-a", publication.selection.songId)
        assertEquals(stagedA.successorOwnership, publication.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            publication.kind
        )
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.spec.location == "/next-b.ogg"
        })
    }

    @Test
    fun explicitSelectionCancelsDormantAutomaticSuccessorBeforeBoundaryEvidence() {
        val fixture = Fixture()
        fixture.controller.select(request("cancel-a"), metadata("cancel-a", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("cancel-b"))
        )
        fixture.scheduler.runSteps(2)
        val staleSuccessor = requireNotNull(
            fixture.environment.snapshots.lastOrNull { it.automaticSuccessor != null }
                ?.automaticSuccessor?.successorOwnership
        )

        val explicit = fixture.controller.select(request("cancel-c"), metadata("cancel-c", 1L))
        fixture.scheduler.runNext()
        fixture.environment.present(staleSuccessor, 0L)
        fixture.scheduler.runNext()
        assertTrue(fixture.ownerPublications().none { it.selection.songId == "cancel-b" })

        fixture.presentLatest()
        assertEquals(explicit, fixture.ownerPublications().last().selection.token)
        assertEquals("cancel-c", fixture.ownerPublications().last().selection.songId)
    }

    @Test
    fun restartCurrentMetadataUnderOrOverEstimateNeverChangesDecodedEosIdentity() {
        listOf(2_500L, 600_000L).forEach { metadataDurationMs ->
            val fixture = Fixture()
            fixture.environment.holdLoopPreparation = true
            val selectedRequest = request("repeat-$metadataDurationMs").copy(
                durationMs = metadataDurationMs, playbackEndMs = metadataDurationMs
            )
            fixture.controller.select(
                selectedRequest,
                metadata(selectedRequest.songId, 1L)
            )
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.controller.setAutomaticEndPlan(
                NativeEngineAutomaticEndPlan.RestartCurrent(
                    selectedRequest.identity(),
                    selectedRequest.durationMs
                )
            )
            fixture.scheduler.runSteps(2)

            assertFalse(fixture.environment.managedLoopIntents.last().enabled)
            val intent = requireNotNull(fixture.environment.latestPreparationIntent)
            val key = requireNotNull(intent.cacheKey)
            assertEquals(NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS, intent.boundaryMode)
            assertEquals(0L, key.startTimelineFrame)
            assertEquals(
                NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES,
                key.endTimelineFrameExclusive
            )
            assertTrue(msToFrameForTest(metadataDurationMs) != key.endTimelineFrameExclusive)
        }
    }

    @Test
    fun corruptLikelyNextIsLatchedPerAudioIdentityAndRuntimeButForegroundSelectionStillRuns() {
        val fixture = Fixture()
        val current = request("bad-next-current")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val badV1 = request("bad-next")
        fixture.environment.failAllOpensAtLocation(badV1.originalLocation)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(badV1.identity(), metadata(badV1.songId, 1L))
            )
        )
        fixture.scheduler.runNext()
        assertEquals(1, fixture.environment.openAttemptCount(badV1.originalLocation))

        // Polling and presentation-only metadata revisions cannot reopen the same corrupt audio.
        fixture.scheduler.runSteps(8)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(badV1.identity(), metadata(badV1.songId, 2L))
            )
        )
        fixture.scheduler.runSteps(4)
        assertEquals(1, fixture.environment.openAttemptCount(badV1.originalLocation))
        assertEquals(current.songId, fixture.ownerPublications().single().selection.songId)

        // A real audio revision gets one fresh attempt, then is independently latched.
        val badV2 = badV1.copy(audioRevision = "rev-bad-next-v2")
        val badV2Metadata = NativeEngineMetadata(
            songId = badV2.songId,
            audioRevision = badV2.audioRevision,
            revision = 3L,
            payloadKey = "bad-v2"
        )
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(badV2.identity(), badV2Metadata)
            )
        )
        fixture.scheduler.runSteps(4)
        assertEquals(2, fixture.environment.openAttemptCount(badV1.originalLocation))

        // Route/runtime recreation is a deliberate new decode environment and permits one retry.
        val recreationsBefore = fixture.environment.operations.count { it == "recreate-runtime" }
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.operations.count { it == "recreate-runtime" } > recreationsBefore
        }
        fixture.scheduler.runSteps(4)
        assertEquals(3, fixture.environment.openAttemptCount(badV1.originalLocation))

        // The prewarm quarantine is not a foreground-selection blacklist. The explicit request is
        // attempted, reports its own failure, and a following valid owner can still commit.
        val failedToken = fixture.controller.select(badV2, badV2Metadata)
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
                .any { it.token == failedToken }
        }
        assertEquals(4, fixture.environment.openAttemptCount(badV1.originalLocation))

        val valid = request("valid-after-bad-next")
        val validToken = fixture.controller.select(valid, metadata(valid.songId, 4L))
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == validToken.value
        }
        fixture.presentLatest()
        assertEquals(valid.songId, fixture.ownerPublications().last().selection.songId)
    }

    @Test
    fun emptyLikelyNextIsLatchedUntilItsAudioIdentityChanges() {
        val fixture = Fixture()
        val current = request("empty-next-current")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.newDecodersExposePcmHead = false
        val likely = request("empty-next")
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(
            NativeEngineQueueItem(likely.identity(), metadata(likely.songId, 1L))))
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.decoders.any { !it.released && it.spec.location == likely.originalLocation }
        }
        val empty = fixture.environment.decoders.single { !it.released && it.spec.location == likely.originalLocation }
        fixture.scheduler.advanceTimeBy(37L)
        empty.exactEndOfStreamFrame = 0L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(16)
        assertTrue(empty.cancelAttempted && empty.released)
        val failureStage = fixture.controller.diagnosticsSnapshot().controllerPreparationStages.single {
            it.operation == NativeControllerPreparationOperation.SUCCESSOR_PREWARM &&
                it.ownership?.selectionToken == empty.spec.selectionToken.value
        }
        assertEquals("failed", failureStage.stage)
        assertTrue(failureStage.elapsedMs >= 37L)
        assertEquals("Original ended without playable PCM", failureStage.cause)
        assertEquals(1, fixture.environment.openAttemptCount(likely.originalLocation))
        assertEquals(current.songId, fixture.ownerPublications().single().selection.songId)
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(
            NativeEngineQueueItem(likely.identity(), metadata(likely.songId, 2L))))
        fixture.scheduler.runSteps(8)
        assertEquals(1, fixture.environment.openAttemptCount(likely.originalLocation))
        val revised = likely.copy(audioRevision = "rev-empty-next-v2")
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(
            NativeEngineQueueItem(revised.identity(), NativeEngineMetadata(revised.songId,
                revised.audioRevision, 3L, "revised"))))
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.openAttemptCount(likely.originalLocation) == 2
        }
    }

    @Test
    fun asynchronousLikelyNextOriginalFailureIsLatchedAfterCandidateRelease() {
        val fixture = Fixture()
        val current = request("async-bad-next-current")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val likely = request("async-bad-next")
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(likely.identity(), metadata(likely.songId, 1L))
            )
        )
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.decoders.any {
                !it.released && it.role == NativeSongBankRole.CANDIDATE &&
                    it.spec.location == likely.originalLocation
            }
        }
        val failedCandidate = fixture.environment.decoders.single {
            !it.released && it.role == NativeSongBankRole.CANDIDATE &&
                it.spec.location == likely.originalLocation
        }
        assertEquals(1, fixture.environment.openAttemptCount(likely.originalLocation))

        failedCandidate.failAsync("synthetic asynchronous corrupt Original")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(16)

        assertTrue(failedCandidate.released)
        assertEquals(
            "Actor polling reopened an asynchronously failed likely-next Original",
            1,
            fixture.environment.openAttemptCount(likely.originalLocation)
        )
        assertEquals(current.songId, fixture.ownerPublications().single().selection.songId)

        val revised = likely.copy(audioRevision = "rev-async-bad-next-v2")
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    revised.identity(),
                    NativeEngineMetadata(
                        revised.songId,
                        revised.audioRevision,
                        2L,
                        "revised-audio"
                    )
                )
            )
        )
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.openAttemptCount(likely.originalLocation) == 2
        }
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.role == NativeSongBankRole.CANDIDATE &&
                it.spec.location == likely.originalLocation
        })
    }

    @Test
    fun asynchronousForegroundOriginalFailureSettlesTheExactSelectionAndAllowsRecovery() {
        val fixture = Fixture()
        val failedRequest = request("async-foreground-failure")
        val failedToken = fixture.controller.select(failedRequest)
        fixture.scheduler.runNext()
        val failedDecoder = fixture.environment.decoders.single {
            !it.released && it.spec.location == failedRequest.originalLocation
        }

        failedDecoder.failAsync("synthetic foreground Original failure")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
                .any { it.token == failedToken }
        }

        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.DecoderFailed && it.token == failedToken
        })
        assertNull(fixture.environment.lastSelected())
        fixture.scheduler.runUntil(maximumSteps = 8) { failedDecoder.released }

        val recovered = fixture.controller.select(request("valid-after-async-failure"))
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.lastSelected()?.selectionToken == recovered.value
        }
        assertEquals(recovered.value, fixture.environment.lastSelected()?.selectionToken)
    }

    @Test
    fun pausedNearEndSeekThenPlayKeepsTheRequestedAnchorWhenCommandsCoalesce() {
        for (sealed in listOf(false, true)) assertPausedNearEndSeekThenPlay(sealed, coalesced = true)
    }

    @Test
    fun pausedNearEndSeekThenPlayKeepsTheRequestedAnchorDuringPriming() {
        for (sealed in listOf(false, true)) assertPausedNearEndSeekThenPlay(sealed, coalesced = false)
    }

    private fun assertPausedNearEndSeekThenPlay(sealed: Boolean, coalesced: Boolean) {
        val fixture = Fixture(currentSongCacheSealedByDefault = sealed)
        val token = fixture.controller.select(request("near-end-$sealed-$coalesced", autoplay = false)
            .copy(durationMs = 12_000L, playbackEndMs = 12_000L))
        fixture.scheduler.runNext()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(outgoing)
        fixture.scheduler.runNext()
        assertEquals(NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            fixture.ownerPublications().last().kind)
        val original = fixture.environment.decoders.single()
        original.exposePcmHead = false
        fixture.environment.newDecodersExposePcmHead = false
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 11_000L, seekRevision = it.seekRevision + 1L)
        })
        fun play() = assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, true) {
            it.copy(playWhenReady = true)
        })
        if (coalesced) play()
        fixture.scheduler.runUntil(maximumSteps = 8) {
            if (sealed) original.requestedSeekTargets.size == 1 else fixture.environment.decoders.size == 2
        }
        val decoder = fixture.environment.decoders.last()
        if (sealed) {
            assertSame(original, decoder)
            decoder.exposePcmHead = true
            assertEquals(527_952L, decoder.head().firstTimelineFrame48k)
            decoder.exposePcmHead = false
        } else {
            assertEquals(11_000_000L, decoder.spec.initialPositionUs)
            assertTrue(original.requestedSeekTargets.isEmpty())
        }
        val target = requireNotNull(fixture.environment.snapshots.last().banks[decoder.bank.nativeCode].ownership)
        assertEquals(token.value, target.selectionToken)
        assertTrue(target.generation > outgoing.generation)
        assertEquals(528_000L, fixture.environment.snapshots.last().banks[decoder.bank.nativeCode].firstTimelineFrame)
        // A real 44.1 kHz cold decoder can expose a 9 s prefix while 11 s is still uncovered.
        decoder.setAcceptedCoverage(432_000L, 449_832L)
        decoder.exposePcmHead = true
        fixture.scheduler.runSteps(2)
        if (!coalesced) {
            play()
            fixture.scheduler.runSteps(2)
        }
        val priming = fixture.environment.snapshots.last()
        assertEquals(528_000L, priming.banks[decoder.bank.nativeCode].firstTimelineFrame)
        if (!sealed) {
            assertEquals("Play cannot select retained history before the requested cursor is covered",
                outgoing, fixture.environment.lastSelected())
        }
        assertEquals(outgoing, fixture.ownerPublications().last().ownership)
        decoder.extendCoverageThrough(540_000L)
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.lastSelected() == target && fixture.environment.snapshots.last().playing
        }
        val selected = fixture.environment.snapshots.last()
        assertEquals(target, selected.selectedOwnership)
        assertEquals(528_000L, selected.banks[requireNotNull(selected.selectedBank).nativeCode].firstTimelineFrame)
        fixture.environment.present(target, 528_000L)
        fixture.scheduler.runNext()
        val publication = fixture.ownerPublications().last()
        assertEquals(target, publication.ownership)
        assertEquals(1L, publication.completedSeekRevision)
        assertNotNull(publication.evidence)
        assertEquals(528_000L, fixture.environment.controlProgress().presentedPosition?.lastTimelineFrame)
        assertNull(fixture.controller.diagnosticsSnapshot().controllerPendingSeekOwnership)
    }

    @Test
    fun earlySeekPrimesRetainedHistoryWithoutSelectingItBeforeTheRequestedCursorIsCovered() {
        for (playingAfterCoverage in listOf(false, true)) {
            assertEarlySeekPriming(playingAfterCoverage)
        }
    }

    private fun assertEarlySeekPriming(playingAfterCoverage: Boolean) {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("early-seek-history").copy(autoplay = false))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.newDecodersExposePcmHead = false
        assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
            requestedPositionMs = 5_900L, seekRevision = previous.seekRevision + 1L) })
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.environment.decoders.size == 2 }
        val replacement = fixture.environment.decoders.last()
        assertEquals(5_900_000L, replacement.spec.initialPositionUs)
        // Real 44.1 kHz cold seeks retain history from 4s, more than the bounded ingress holds.
        replacement.setAcceptedCoverage(192_000L, 209_832L)
        replacement.exposePcmHead = true
        fixture.scheduler.runSteps(2)
        val priming = fixture.environment.snapshots.last().banks[replacement.bank.nativeCode]
        assertTrue("An unselected replacement needs the native consumer to drain SRC history", priming.assigned)
        assertEquals(283_200L, priming.firstTimelineFrame)
        assertEquals(outgoing, fixture.environment.lastSelected())
        assertFalse(fixture.ownerPublications().any { it.ownership == priming.ownership })
        for (playing in listOf(true, false)) {
            assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, playing) {
                it.copy(playWhenReady = playing)
            })
            fixture.scheduler.runSteps(2)
            assertEquals("Transport cannot select a priming bank", outgoing, fixture.environment.lastSelected())
        }
        assertTrue(fixture.controller.updateDesired(token) { it.copy(semitoneShift = 2) })
        fixture.scheduler.runSteps(2)
        assertEquals("Tuning cannot select a priming bank", outgoing, fixture.environment.lastSelected())
        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, playingAfterCoverage) {
            it.copy(playWhenReady = playingAfterCoverage)
        })
        fixture.scheduler.runSteps(2)
        assertEquals(outgoing, fixture.environment.lastSelected())
        replacement.extendCoverageThrough(294_000L)
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.environment.lastSelected() != outgoing }
        val selected = fixture.environment.snapshots.last()
        assertEquals(283_200L, selected.banks[requireNotNull(selected.selectedBank).nativeCode].firstTimelineFrame)
        assertEquals(token.value, selected.selectedOwnership?.selectionToken)
        assertEquals(outgoing, fixture.ownerPublications().last().ownership)
        val replacementOwner = requireNotNull(selected.selectedOwnership)
        if (playingAfterCoverage) fixture.environment.present(replacementOwner, 283_200L)
        else fixture.environment.installPaused(replacementOwner)
        fixture.scheduler.runNext()
        assertEquals(replacementOwner, fixture.ownerPublications().last().ownership)
        assertFalse(fixture.environment.decoders.first().released)
        assertTrue(fixture.environment.decoders.first().requestedSeekTargets.isEmpty())
    }

    @Test
    fun anotherEarlySeekDuringPrimingStillWaitsForItsOwnCoverage() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("early-seek-again").copy(autoplay = false))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.newDecodersExposePcmHead = false
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            requestedPositionMs = 5_900L, seekRevision = it.seekRevision + 1L) })
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.environment.decoders.size == 2 }
        val replacement = fixture.environment.decoders.last()
        replacement.setAcceptedCoverage(192_000L, 209_832L)
        replacement.exposePcmHead = true
        fixture.scheduler.runSteps(2)
        replacement.exposePcmHead = false
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            requestedPositionMs = 7_500L, seekRevision = it.seekRevision + 1L) })
        fixture.scheduler.runSteps(2)
        assertEquals(outgoing, fixture.environment.lastSelected())
        assertEquals(1, replacement.requestedSeekTargets.size)
        val priming = fixture.environment.snapshots.last().banks[replacement.bank.nativeCode]
        assertEquals(replacement.head().nativeGeneration, priming.ownership?.generation)
        assertEquals(360_000L, priming.firstTimelineFrame)
        replacement.setAcceptedCoverage(336_000L, 350_000L)
        replacement.exposePcmHead = true
        fixture.scheduler.runSteps(2)
        assertEquals(outgoing, fixture.environment.lastSelected())
        replacement.extendCoverageThrough(368_192L)
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.environment.lastSelected() != outgoing }
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.scheduler.runNext()
        assertEquals(owner, fixture.ownerPublications().last().ownership)
        assertTrue(fixture.environment.decoders.first().requestedSeekTargets.isEmpty())
    }

    @Test
    fun earlySeekRetainsFrameZeroBuilderWithoutRoutingControlsOrExceedingEightDecoders() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val stems = currentStems("early-seek")
        val token = fixture.controller.select(request("early-seek-cache"))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "early-seek-v1",
                stemControls = stems.associate { it.id to EngineStemControl(muted = true) }
            )
        })
        fixture.scheduler.runNext()
        val frameZeroOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(frameZeroOwner, timelineFrame = 0L)
        fixture.scheduler.runUntil {
            fixture.environment.decoders.count { !it.released } == 7
        }
        val builderDecoders = fixture.environment.decoders.filter { !it.released }
        assertEquals(setOf(0L), builderDecoders.map { it.spec.initialPositionUs }.toSet())

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 10_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected() != frameZeroOwner
        }
        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        assertEquals(8, fixture.environment.decoders.count { !it.released })
        assertTrue(builderDecoders.all { it.requestedSeekTargets.isEmpty() })
        fixture.environment.present(consumerOwner, timelineFrame = msToFrameForTest(10_000L))
        fixture.scheduler.runNext()

        assertEquals(NativeRuntimeSourceMode.ORIGINAL,
            fixture.environment.snapshots.last().controls.sourceMode)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 4_000L)))
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.managedLoopIntents.lastOrNull()?.enabled != true)
        assertEquals(0, fixture.environment.openLoopPreparationCount)

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertTrue(builderDecoders.all {
            it.head().firstTimelineFrame48k == 0L && it.requestedSeekTargets.size == 1
        })
        val consumerDecoder = fixture.environment.decoders.single {
            !it.released && it !in builderDecoders
        }
        assertEquals(msToFrameForTest(9_999L), consumerDecoder.head().firstTimelineFrame48k)

        fixture.controller.setManualPrewarm(
            NativeEngineManualPrewarmRequest(token, queueItem("visual-only-preview"))
        )
        fixture.scheduler.runSteps(2)
        assertFalse(fixture.environment.decoders.any {
            !it.released && it.spec.location == "/visual-only-preview.ogg"
        })
        assertTrue(builderDecoders.none { it.released })

        fixture.controller.select(request("manual-preempts-builder"))
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.decoders.any {
                !it.released && it.spec.location == "/manual-preempts-builder.ogg"
            }
        }
        assertTrue(builderDecoders.all { it.released })
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 8)
    }

    @Test
    fun newSelectionWaitsForEarlySeekCandidateLeaseWhenBuilderReleasesFirst() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("candidate-lease-owner"))
        val stems = currentStems("candidate-lease-owner")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "candidate-lease-owner-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val builderOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(builderOwner, timelineFrame = 0L)
        fixture.scheduler.runUntil {
            fixture.environment.decoders.count { !it.released } == 7
        }

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 10_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builderOwner }
        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(consumerOwner, timelineFrame = msToFrameForTest(10_000L))
        fixture.scheduler.runNext()
        val consumerBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().candidateDecoderCount)

        fixture.environment.heldUnassignmentAcknowledgementBanks += consumerBank
        val replacement = request("candidate-lease-replacement")
        fixture.controller.select(replacement)
        fixture.scheduler.runSteps(2)

        assertEquals(0, fixture.environment.openAttemptCount(replacement.originalLocation))
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })

        fixture.environment.heldUnassignmentAcknowledgementBanks.clear()
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.openAttemptCount(replacement.originalLocation) == 1
        }
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().candidateDecoderCount)
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 8)
    }

    @Test
    fun releasingSameTokenOwnerCannotConsumeAndLoseTheLatestSeek() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val request = request("releasing-seek-owner")
        val token = fixture.controller.select(request)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val frameZeroOwner = requireNotNull(fixture.environment.lastSelected())

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 10_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected() != frameZeroOwner
        }
        val liveOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(liveOwner, msToFrameForTest(10_000L))
        fixture.scheduler.runNext()

        val selectedBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val liveDecoder = fixture.environment.decoders.single {
            !it.released && it.bank == selectedBank && it.laneIndex == 0
        }
        val opensBeforeFailedSeek = fixture.environment.openAttemptCount(
            request.originalLocation
        )
        liveDecoder.failNativeAdvance = true
        fixture.environment.holdUnassignmentAcknowledgement = true

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 20_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        assertFalse(liveDecoder.released)

        fixture.environment.holdUnassignmentAcknowledgement = false
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(
            opensBeforeFailedSeek + 1,
            fixture.environment.openAttemptCount(request.originalLocation)
        )
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 2)
    }

    @Test
    fun selectedOwnerAdoptsSealedCacheAfterBuilderWasPreempted() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val stems = currentStems("cache-rebound")
        val current = request("cache-rebound")
        val token = fixture.controller.select(current)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "cache-rebound-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val frameZeroOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(frameZeroOwner, 0L)
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
        val builderDecoders = fixture.environment.decoders.filter { !it.released }
        val builderBank = builderDecoders.map { it.bank }.toSet().single()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 160_000L,
                seekRevision = previous.seekRevision + 1L,
                tempo = 2f
            )
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != frameZeroOwner }
        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(consumerOwner, msToFrameForTest(160_000L))
        fixture.scheduler.runNext()
        val openedBeforeAdoption = fixture.environment.openedDecoderCount
        assertEquals(8, openedBeforeAdoption)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("cache-rebound-next"))
        )
        fixture.scheduler.runNext()
        assertTrue(builderDecoders.none { it.released })
        assertFalse(
            fixture.environment.snapshots.last().banks[builderBank.nativeCode].assigned
        )
        fixture.environment.sealCurrentSongCacheFor(consumerOwner)
        val snapshotsBeforeAdoption = fixture.environment.snapshots.size
        fixture.controller.setAutomaticEndPlan(null)
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.size > snapshotsBeforeAdoption)
        assertEquals(
            NativeRuntimeSourceMode.STEMS,
            fixture.environment.snapshots.last().controls.sourceMode
        )
        fixture.scheduler.runUntil(maximumSteps = 16) { builderDecoders.all { it.released } }

        val finalSnapshot = fixture.environment.snapshots.last()
        assertEquals(consumerOwner, finalSnapshot.selectedOwnership)
        assertEquals(openedBeforeAdoption, fixture.environment.openedDecoderCount)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertEquals(
            setOf(0),
            fixture.environment.decoders.filter { !it.released }.map { it.laneIndex }.toSet()
        )
        assertFalse(fixture.environment.decoders.any {
            it.spec.location == "/cache-rebound-next.ogg"
        })
        val sourcePublication = requireNotNull(
            fixture.controller.diagnosticsSnapshot().controllerSourceControlPublications
                .lastOrNull { it.nativeControlRevision == finalSnapshot.controls.revision }
        )
        assertEquals(consumerOwner, sourcePublication.ownership)
        assertEquals(EngineSourceMode.STEMS, sourcePublication.requestedMode)
        assertEquals(NativeRuntimeSourceMode.STEMS, sourcePublication.nativeMode)
        assertNotNull(sourcePublication.stemPackageSignature)
        assertEquals("cache-rebound-v1", sourcePublication.stemRevision)
        assertNotNull(sourcePublication.stemRequestRevision)
    }

    @Test
    fun uncertainFiniteTailsCannotBypassDecoderOrNativeCoverage() {
        val scenarios = listOf("unknown-original-eof", "unknown-part-eof", "mismatched-eof",
            "truncated-pcm", "stale-eof-generation", "short-native-proof")
        scenarios.forEach { scenario ->
            val fixture = finiteStemTailFixture(scenario)
            val owner = requireNotNull(fixture.environment.lastSelected())
            val original = fixture.environment.decoders.single { it.laneIndex == 0 }
            val lastPart = fixture.environment.decoders.single { it.laneIndex == 2 }
            when (scenario) {
                "unknown-original-eof" -> original.exactEndOfStreamFrame = null
                "unknown-part-eof" -> lastPart.exactEndOfStreamFrame = null
                "mismatched-eof" -> lastPart.exactEndOfStreamFrame = 47_999L
                "truncated-pcm" -> lastPart.setAcceptedCoverage(47_280L, 47_999L)
                "stale-eof-generation" -> lastPart.reportedNativeGenerationOverride = owner.generation - 1L
                "short-native-proof" -> fixture.environment.setNativeStemRenderReadiness(
                    owner, 47_280L, shortLane = 2,
                    shortLaneAvailableFrames = fixture.environment.requiredNativeStemRenderAheadFrames - 1L,
                    rawEndTimelineFrameExclusive = 48_000L)
            }

            fixture.scheduler.runNext()

            assertEquals(scenario, NativeRuntimeSourceMode.ORIGINAL,
                fixture.environment.snapshots.last().controls.sourceMode)
            assertFalse(scenario, requireNotNull(fixture.controller.diagnosticsSnapshot()
                .controllerStemReadiness).ready)
            assertEquals(scenario, owner, fixture.environment.lastSelected())
            assertFalse(scenario, original.released)
            assertFalse(scenario, fixture.events.any {
                it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible ||
                    it is NativeEngineControllerEvent.FatalRecoveryRequired
            })
        }
    }

    @Test
    fun downMoveUpBeforeAnyActorTurnPublishesOneNonZeroSequencedRetirement() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-no-actor-turn"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-no-actor-turn")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-no-actor-turn-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val packageEvent = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-no-actor-turn-v1" }
        val commandCount = fixture.environment.snapshots.size
        val previewCount = fixture.environment.stemGainPreviews.size
        val lease = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageEvent.packageSignature,
                packageEvent.requestRevision,
                laneIndex = 4
            )
        )
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }.also {
            it[4] = .19f
        }

        // No scheduler turn is allowed between DOWN, MOVE, and UP.
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1 shl 4, gains))
        assertTrue(fixture.controller.endStemMixPreview(lease))
        fixture.scheduler.runNextImmediate()

        assertEquals(commandCount, fixture.environment.snapshots.size)
        assertEquals(previewCount + 1, fixture.environment.stemGainPreviews.size)
        val retirement = fixture.environment.stemGainPreviews.last()
        assertEquals(1L, retirement.coversOfferSequence)
        assertEquals(0, retirement.activeMask)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun pausedOriginalReturnPublishesPreparedButNotAudibleEvidence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("paused-original-return", autoplay = false))
        fixture.scheduler.runNext()
        fixture.environment.installPaused(requireNotNull(fixture.environment.lastSelected()))
        fixture.scheduler.runNext()
        val stems = currentStems("paused-original-return")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "paused-original-r1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.ORIGINAL)
        })
        fixture.scheduler.runNext()

        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals(
            NativeRuntimeSourceMode.ORIGINAL,
            fixture.environment.snapshots.last().controls.sourceMode
        )
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible &&
                it.token == token && it.sourceMode == EngineSourceMode.ORIGINAL
        })
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.SourceModeApplied })
        assertNull(fixture.environment.diagnostics().audibleControl)
    }

    @Test
    fun staleOldGenerationFailureHasNoAuthorityAndProducesNoError() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stale-decoder"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val original = fixture.environment.decoders.single()
        val staleGeneration = requireNotNull(fixture.environment.lastSelected()).generation
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 1_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        original.failAsync("late old seek failure", generation = staleGeneration)

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.DecoderFailed })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageFailed })
    }

    @Test
    fun rapidV2V3SupersessionNeverMixesAndOnlyV3BecomesReady() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("supersede"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val v1 = currentStems("v1")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = v1, stemRevision = "stem-v1")
        })
        fixture.scheduler.runNext()

        val v2 = currentStems("v2")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = v2, stemRevision = "stem-v2")
        })
        fixture.scheduler.runNext()
        val v3 = currentStems("v3")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = v3, stemRevision = "stem-v3")
        })
        fixture.scheduler.runNext()

        assertEquals(1, fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageFailed
        >().count { it.stemRevision == "stem-v2" })
        val fallback = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(fallback, timelineFrame = 0L)
        fixture.scheduler.runUntil(maximumSteps = 32) {
            fixture.events.any {
                it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                    it.stemRevision == "stem-v3"
            }
        }

        val v2Decoders = fixture.environment.decoders.filter {
            it.laneIndex in 1..6 && it.spec.location.startsWith("/v2-")
        }
        val v3Decoders = fixture.environment.decoders.filter {
            it.laneIndex in 1..6 && it.spec.location.startsWith("/v3-")
        }
        val liveStemLocations = fixture.environment.decoders.filter {
            !it.released && it.laneIndex in 1..6
        }.map { it.spec.location }
        assertTrue(v2Decoders.all { it.released })
        assertEquals(6, v3Decoders.size)
        assertTrue(liveStemLocations.isEmpty() || liveStemLocations.size == 6)
        assertTrue(liveStemLocations.toString(), liveStemLocations.all { it.startsWith("/v3-") })
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "stem-v2"
        })
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "stem-v3"
        })
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
    }

    @Test
    fun metronomeV2PreservesDenominatorPickupAndVariableMeterAccents() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("meter"))
        val grid = LongArray(10) { index -> index * 500L }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                metronome = EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 120f,
                    subdivision = 2,
                    numerator = 32,
                    denominator = 32,
                    beatGridMs = grid,
                    downbeats = BooleanArray(grid.size),
                    meterSegments = listOf(
                        EngineMeterSegment(0L, 1_500L, 3, 4),
                        EngineMeterSegment(1_500L, 4_000L, 5, 8)
                    )
                )
            )
        })
        fixture.scheduler.runNext()

        val metronome = fixture.environment.snapshots.last().controls.metronome
        assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID, metronome.mode)
        assertEquals(32, metronome.beatsPerBar)
        assertEquals(32, metronome.beatUnitDenominator)
        assertEquals(2, metronome.subdivision)
        val derived = requireNotNull(fixture.environment.beatGridUpdates.last().beatAccents)
        assertTrue(
            derived.contentEquals(
                booleanArrayOf(true, false, false, true, false, false, false, false, false, false)
            )
        )
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.BeatGridNotReady })

        val publicationsBeforeControls = fixture.environment.beatGridUpdates.size
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(semitoneShift = 2)
        })
        fixture.scheduler.runNext()
        assertEquals(publicationsBeforeControls, fixture.environment.beatGridUpdates.size)

        val explicitPickup = booleanArrayOf(
            false, true, false, false, true, false, false, false, false, true
        )
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                metronome = previous.metronome.copy(downbeats = explicitPickup)
            )
        })
        fixture.scheduler.runNext()

        val authored = requireNotNull(fixture.environment.beatGridUpdates.last().beatAccents)
        assertTrue(authored.contentEquals(explicitPickup))
    }

    @Test
    fun freeMetronomeCarriesSixEightBeatUnitBeforeSubdivision() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("free-meter"))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                metronome = EngineMetronomeConfig(
                    enabled = true,
                    songSynced = false,
                    bpm = 120f,
                    subdivision = 2,
                    numerator = 6,
                    denominator = 8
                )
            )
        })
        fixture.scheduler.runNext()

        val metronome = fixture.environment.snapshots.last().controls.metronome
        assertEquals(NativeRuntimeMetronomeMode.FREE_BPM, metronome.mode)
        assertEquals(6, metronome.beatsPerBar)
        assertEquals(8, metronome.beatUnitDenominator)
        assertEquals(2, metronome.subdivision)
    }

    @Test
    fun audibleEndOnlyNotifiesAndDoesNotStealQueuePolicy() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("first"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("next"))
        )
        fixture.scheduler.runSteps(2)
        val selectionsBefore = fixture.environment.snapshots.count { it.selectedBank != null }

        fixture.environment.end(requireNotNull(fixture.environment.lastSelected()))
        fixture.scheduler.runNext()

        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.AudibleEnd && it.token == token
        })
        assertEquals(selectionsBefore,
            fixture.environment.snapshots.count { it.selectedBank != null })
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
    }

    @Test
    fun wholeBankNativeAdvanceFailureAuthorizesNoAdapterAndRequiresRebuild() {
        val environment = FakeEnvironment()
        val first = environment.fakeDecoder(0, generation = 3L)
        val second = environment.fakeDecoder(1, generation = 3L).apply {
            failNativeAdvance = true
        }
        val coordinator = NativeEngineWholeBankGenerationCoordinator()
        val status = coordinator.commit(
            mapOf(
                0 to (first to NativeEngineDecoderSeekTicket(1L, 9L)),
                1 to (second to NativeEngineDecoderSeekTicket(1L, 9L))
            )
        )

        assertEquals(NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED, status)
        assertEquals(0, first.authorizationCount)
        assertEquals(0, second.authorizationCount)
        assertEquals(1, first.nativeAdvanceCount)
        assertEquals(1, second.nativeAdvanceCount)
    }
    @Test
    fun asynchronousFailureAtAnyFinalAuthorizationRequiresWholeBankRebuild() {
        for (failedLane in 0..1) {
            val environment = FakeEnvironment()
            val decoders = (0..1).map { lane -> environment.fakeDecoder(lane, generation = 3L)
                .apply { failAuthorization = lane == failedLane } }
            val status = NativeEngineWholeBankGenerationCoordinator().commit(
                decoders.mapIndexed { lane, decoder -> lane to
                    (decoder to NativeEngineDecoderSeekTicket(1L, 9L)) }.toMap()
            )
            assertEquals(NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED, status)
            assertEquals(listOf(1, 1), decoders.map { it.nativeAdvanceCount })
            assertEquals(failedLane, decoders.sumOf { it.authorizationCount })
        }
    }

    @Test
    fun laterLaneAuthorizationFailureReachesControllerRebuildAndAllowsAnotherSelection() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("authorization-race"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
            stemLanes = currentStems("authorization-race"), stemRevision = "authorization-race") }
        fixture.scheduler.runNext()
        val bank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val original = fixture.environment.decoders.single { !it.released && it.bank == bank && it.laneIndex == 0 }
        val later = fixture.environment.decoders.single { !it.released && it.bank == bank && it.laneIndex == 2 }
        val before = original.authorizationCount
        later.failAuthorization = true
        fixture.controller.updateDesired(token) { it.copy(requestedPositionMs = 500L,
            seekRevision = it.seekRevision + 1L) }
        fixture.scheduler.runNext()
        assertEquals(before + 1, original.authorizationCount)
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.Failure &&
            it.operation == "seek-generation" })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        val next = fixture.controller.select(request("after-authorization-race"))
        fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == next.value }
        fixture.presentLatest()
        assertEquals(next.value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

}
