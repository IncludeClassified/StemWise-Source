package app.stemwise.playback.engine

import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
internal class NativePlaybackControllerLifecycleTest : NativePlaybackControllerTestSupport() {
    @Test
    fun routeSeekFailStopRebuildRetainsCapturedCursorUnlessNewerSeekSupersedesIt() {
        for (occupied in listOf(false, true)) for (newerSeek in listOf(false, true)) {
            val fixture = Fixture()
            val song = "route-fail-stop-$occupied-$newerSeek"
            val token = fixture.controller.select(request(song))
            val stems = currentStems(song)
            assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
                sourceMode = EngineSourceMode.STEMS, stemLanes = stems, stemRevision = song,
                stemControls = stems.associate { it.id to EngineStemControl() }) })
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = msToFrameForTest(60_000L))
            fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
            val original = fixture.environment.decoders.single { it.laneIndex == 0 }
            val failedStem = fixture.environment.decoders.first { it.laneIndex != 0 }
            if (occupied) {
                fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(queueItem("occupied-spare")))
                fixture.scheduler.runUntil { fixture.environment.decoders.any { it.spec.location == "/occupied-spare.ogg" } }
                fixture.environment.holdUnassignmentAcknowledgement = true
                fixture.controller.setAutomaticEndPlan(null)
                fixture.scheduler.runNext()
            }
            fixture.environment.operations.clear()
            val beforeRoute = fixture.environment.snapshots.size
            var failedAtAuthorization = false
            failedStem.seekAuthorizationOverride = { ticket ->
                assertTrue("Fault is inside the recreated route transaction",
                    fixture.environment.operations.contains("recreate-runtime"))
                assertEquals(ticket.nativeGeneration, original.requestedSeekTargets.last())
                failedAtAuthorization = true
                failedStem.seekAuthorizationOverride = null
                if (newerSeek) assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
                    requestedPositionMs = 73_000L, seekRevision = previous.seekRevision + 1L) })
                false
            }
            fixture.controller.requestRouteRecovery()
            fixture.scheduler.runNext()
            assertTrue(failedAtAuthorization)
            assertTrue("Route seek was prepared at callback-owned position", fixture.environment.snapshots
                .drop(beforeRoute).any { it.banks[original.bank.nativeCode].firstTimelineFrame == msToFrameForTest(60_000L) })
            if (occupied) {
                assertFalse("Both occupied banks remain retained until exact release acknowledgement", original.released)
                assertFalse(fixture.environment.decoders.any { it !== original && it.spec.location == "/$song.ogg" })
                fixture.environment.holdUnassignmentAcknowledgement = false
                fixture.controller.signalNativeProgress()
            }
            fixture.scheduler.runUntil(maximumSteps = 24) {
                fixture.environment.decoders.any { it !== original && it.spec.location == "/$song.ogg" }
            }
            val replacement = fixture.environment.decoders.last { it !== original && it.spec.location == "/$song.ogg" }
            val expectedMs = if (newerSeek) 73_000L else 60_000L
            assertEquals("Clean Original covers the exact recovery cursor with one millisecond preroll: occupied=$occupied newerSeek=$newerSeek",
                (expectedMs - 1L) * 1_000L, replacement.spec.initialPositionUs)
            fixture.scheduler.runUntil { fixture.environment.lastSelected()?.let { owner ->
                owner.selectionToken == token.value && fixture.environment.snapshots.last().selectedBank == replacement.bank
            } == true }
            fixture.presentLatest(timelineFrame = msToFrameForTest(expectedMs))
            assertEquals(token, fixture.ownerPublications().last().selection.token)
            assertEquals(msToFrameForTest(expectedMs), fixture.environment.snapshots.last()
                .banks[replacement.bank.nativeCode].firstTimelineFrame)
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test fun unavailableOutputReportsEachExactReplacementRequestOnce() {
        val fixture = Fixture()
        fixture.environment.outputPreparation = NativeOutputPreparationState.UNAVAILABLE
        val first = fixture.controller.select(request("first-unavailable"))
        fixture.scheduler.runSteps(3)
        val second = fixture.controller.select(request("second-unavailable"))
        fixture.scheduler.runUntil { fixture.events.filterIsInstance<NativeEngineControllerEvent.OutputAvailabilityChanged>()
            .any { it.token == second && !it.available } }
        fixture.scheduler.runSteps(3)
        assertEquals(listOf(first, second), fixture.events
            .filterIsInstance<NativeEngineControllerEvent.OutputAvailabilityChanged>()
            .filter { !it.available }.map { it.token })
    }

    @Test fun pauseAndFocusLossStopInitialOpenRetriesUntilCurrentPlayDemandReturns() {
        for (focusLoss in listOf(false, true)) {
            val fixture = Fixture()
            fixture.environment.runtimeStartFailures = 100
            val token = fixture.controller.select(request("initial-open-demand"))
            fixture.scheduler.runNext()
            val failures = fixture.events.count { it is NativeEngineControllerEvent.OutputAvailabilityChanged && !it.available }
            val attempts = fixture.environment.operations.count { it == "start-runtime" }
            if (focusLoss) fixture.controller.suspendForAudioFocusLoss()
            else fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) }
            repeat(8) { if (fixture.scheduler.activeTaskCount() > 0) fixture.scheduler.runNext() }
            assertEquals("No new physical demand after Pause/focus loss", attempts,
                fixture.environment.operations.count { it == "start-runtime" })
            assertEquals("Unavailable output reports one state change", failures,
                fixture.events.count { it is NativeEngineControllerEvent.OutputAvailabilityChanged && !it.available })
            fixture.environment.runtimeStartFailures = 0
            if (focusLoss) fixture.controller.resumeAfterAudioFocusGain()
            else fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) }
            fixture.scheduler.runUntil { fixture.environment.lastSelected() != null }
            fixture.presentLatest()
            assertEquals(token, fixture.ownerPublications().last().selection.token)
        }
    }

    @Test fun physicalOpenFailureAllowsPausedPreparationAndRetriesOnlyWithCurrentPlayPermission() {
        for (focusLoss in listOf(false, true)) {
            val fixture = Fixture()
            fixture.environment.outputPreparation = NativeOutputPreparationState.UNAVAILABLE
            val token = fixture.controller.select(request("physical-open-demand"))
            fixture.scheduler.runNext()
            assertNotNull("Native preparation does not require a physical AudioTrack", fixture.environment.lastSelected())
            if (focusLoss) fixture.controller.suspendForAudioFocusLoss()
            else fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) }
            fixture.scheduler.runNext()
            val attempts = fixture.environment.operations.count { it == "prepare-output" }
            fixture.scheduler.runSteps(12)
            assertEquals(attempts, fixture.environment.operations.count { it == "prepare-output" })
            assertEquals(1, fixture.events.count { it is NativeEngineControllerEvent.OutputAvailabilityChanged && !it.available })
            fixture.environment.outputPreparation = NativeOutputPreparationState.READY
            if (focusLoss) fixture.controller.resumeAfterAudioFocusGain()
            else fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) }
            fixture.scheduler.runUntil { fixture.environment.operations.count { it == "prepare-output" } > attempts }
            assertEquals(listOf(token), fixture.events
                .filterIsInstance<NativeEngineControllerEvent.OutputAvailabilityChanged>()
                .filter { it.available }.map { it.token })
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test fun pausedSelectionAfterStopPreparesWithoutOpeningAndReopensBeforeLaterPlay() {
        for (retainedRecovery in listOf(false, true)) {
            val fixture = Fixture()
            fixture.controller.select(request("before-paused-stop"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.environment.outputRecoveryRequired = retainedRecovery
            val stop = fixture.controller.stopForTaskRemoval()
            fixture.scheduler.runUntil { stop.isComplete() }
            val opens = fixture.environment.operations.count { it == "prepare-output" }
            val reopens = fixture.environment.operations.count { it == "reopen-output" }
            val token = fixture.controller.select(request("paused-after-stop", autoplay = false))
            fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == token.value }
            fixture.scheduler.runSteps(4)
            assertEquals(opens, fixture.environment.operations.count { it == "prepare-output" })
            assertEquals(reopens, fixture.environment.operations.count { it == "reopen-output" })
            assertFalse(fixture.environment.snapshots.last().playing)
            assertEquals("Paused preparation must retain the prior physical failure until Play",
                retainedRecovery, fixture.environment.outputRecoveryRequired)
            fixture.environment.outputPreparation = NativeOutputPreparationState.OPENING
            assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) })
            fixture.scheduler.runSteps(4)
            assertEquals("An opening sink cannot complete the deferred route", reopens,
                fixture.environment.operations.count { it == "reopen-output" })
            assertFalse(fixture.environment.snapshots.last().playing)
            fixture.environment.outputPreparation = NativeOutputPreparationState.READY
            fixture.scheduler.runUntil { fixture.environment.operations.count { it == "reopen-output" } > reopens }
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test fun coldStopBeforeFirstSelectionStillPreparesPausedTrimAndTuningWithoutOpeningOutput() {
        val fixture = Fixture()
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runUntil { stop.isComplete() }
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertFalse(fixture.environment.operations.contains("start-runtime"))

        val token = fixture.controller.select(request("paused-trim-after-cold-stop", autoplay = false).copy(
            durationMs = 6_000L, startPositionMs = 1_000L,
            playbackStartMs = 1_000L, playbackEndMs = 5_000L,
            initialControls = NativeEngineInitialControls(tempo = 1.25f, semitoneShift = 2)
        ))
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.lastSelected()?.selectionToken == token.value
        }
        fixture.presentLatest(timelineFrame = msToFrameForTest(1_000L))

        val paused = fixture.ownerPublications().last()
        assertEquals(token, paused.selection.token)
        assertEquals(1_000L, paused.selection.playbackStartMs)
        assertEquals(5_000L, paused.selection.playbackEndMs)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals(1.25f, fixture.environment.snapshots.last().controls.tempo)
        assertEquals(2f, fixture.environment.snapshots.last().controls.pitchSemitones)
        assertFalse(fixture.environment.operations.contains("prepare-output"))
        assertFalse(fixture.environment.operations.contains("reopen-output"))

        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) })
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.operations.contains("reopen-output")
        }
        fixture.presentLatest(timelineFrame = msToFrameForTest(1_000L))
        assertTrue(fixture.environment.snapshots.last().playing)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test fun pausedReplacementDuringRouteRetryRetiresTheOldOwnerWithoutDemandingOutput() {
        val fixture = Fixture()
        fixture.controller.select(request("playing-trim-preview"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.recreateRuntimeSucceeds = false
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        val reopens = fixture.environment.operations.count { it == "reopen-output" }

        val saved = fixture.controller.select(request("saved-trim", autoplay = false).copy(
            durationMs = 6_000L, startPositionMs = 1_500L,
            playbackStartMs = 1_000L, playbackEndMs = 3_000L
        ))
        fixture.scheduler.runNext()
        assertTrue("The pending paused owner lost its runtime-rebuild retry",
            fixture.scheduler.activeTaskCount() > 0)
        assertEquals(reopens, fixture.environment.operations.count { it == "reopen-output" })
        fixture.environment.recreateRuntimeSucceeds = true
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.lastSelected()?.selectionToken == saved.value
        }
        fixture.presentLatest(timelineFrame = msToFrameForTest(1_500L))

        assertEquals("saved-trim", fixture.ownerPublications().last().selection.songId)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals("Paused restoration must not reopen the sink", reopens,
            fixture.environment.operations.count { it == "reopen-output" })
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })

        assertTrue(fixture.controller.updateDesired(saved) { it.copy(playWhenReady = true) })
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.operations.count { it == "reopen-output" } > reopens
        }
    }

    @Test fun steadyPositionTicksRetainThePublishedDecoderOwnershipGraph() {
        val fixture = Fixture()
        fixture.controller.select(request("steady-diagnostics"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owners = fixture.controller.publishedDecoderOwnersForTest()
        assertFalse(owners.isEmpty())
        fixture.scheduler.runSteps(5)
        assertSame(owners, fixture.controller.publishedDecoderOwnersForTest())
        fixture.controller.select(request("replacement-diagnostics"))
        fixture.scheduler.runUntil { fixture.controller.publishedDecoderOwnersForTest() !== owners }
        assertFalse(fixture.controller.publishedDecoderOwnersForTest().any { it in owners })
    }

    @Test fun removingTheTaskBeforeOrDuringFirstOpenFailureDoesNotOpenOutputToStopIt() {
        for (requested in listOf(false, true)) {
            val fixture = Fixture()
            fixture.environment.runtimeStartFailures = 10
            if (requested) {
                fixture.controller.select(request("unavailable-at-stop"))
                fixture.scheduler.runNext()
            }
            val attempts = fixture.environment.operations.count { it == "start-runtime" }
            val stop = fixture.controller.stopForTaskRemoval()
            fixture.scheduler.runUntil { stop.isComplete() }
            assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
            assertEquals(attempts, fixture.environment.operations.count { it == "start-runtime" })
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test fun libraryConstructionDoesNotOpenOutputAndFailedFirstDemandRetriesWithoutQuarantine() {
        val fixture = Fixture()
        fixture.scheduler.runNext()
        assertFalse(fixture.environment.operations.contains("start-runtime"))
        fixture.environment.runtimeStartFailures = 1
        val token = fixture.controller.select(request("retry-output"))
        fixture.scheduler.runNext()
        assertEquals(listOf("start-runtime"), fixture.environment.operations.filter { it == "start-runtime" })
        assertTrue(fixture.environment.decoders.isEmpty())
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.OutputAvailabilityChanged && !it.available })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != null }
        fixture.presentLatest()
        assertEquals(token, fixture.ownerPublications().single().selection.token)
        assertEquals(2, fixture.environment.operations.count { it == "start-runtime" })
    }

    @Test
    fun selectionRetirementWaitsForExactUnassignmentAndWorkerReleaseWithoutStoppingNewOwner() {
        val fixture = Fixture()
        assertNull(fixture.controller.selectionRetirementTokens("retirement-a"))
        val first = fixture.controller.select(request("retirement-a"))
        assertFalse(fixture.controller.isSelectionRetired(first))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val firstDecoder = fixture.environment.decoders.single { !it.released }
        fixture.environment.heldUnassignmentAcknowledgementBanks += firstDecoder.bank
        val second = fixture.controller.select(request("retirement-b"))
        fixture.scheduler.runSteps(4)
        assertEquals(setOf(first), fixture.controller.selectionRetirementTokens("retirement-a"))
        assertFalse(fixture.controller.isSelectionRetired(first))
        assertFalse(firstDecoder.cancelAttempted)
        firstDecoder.joinWatchdogExpired = true
        fixture.environment.heldUnassignmentAcknowledgementBanks -= firstDecoder.bank
        fixture.scheduler.runSteps(2)
        assertTrue(firstDecoder.cancelAttempted)
        assertFalse(firstDecoder.released)
        assertFalse(fixture.controller.isSelectionRetired(first))
        firstDecoder.joinWatchdogExpired = false
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { firstDecoder.released }
        assertTrue(fixture.controller.isSelectionRetired(first))
        assertEquals(emptySet<EngineSelectionToken>(), fixture.controller.selectionRetirementTokens("retirement-a"))
        assertEquals(setOf(second), fixture.controller.selectionRetirementTokens("retirement-b"))
        assertFalse(fixture.controller.isSelectionRetired(second))
        assertFalse(fixture.controller.isSelectionRetired(EngineSelectionToken(second.value + 100L)))
        assertTrue(fixture.environment.decoders.any { it.spec.selectionToken == second && !it.released })
    }

    @Test
    fun repeatedBankRetirementPollsKeepOneDeadlineAndRetainTheStuckLease() {
        val fixture = Fixture()
        fixture.controller.select(request("retirement-deadline-a"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val departing = fixture.environment.decoders.single { !it.released }
        departing.joinWatchdogExpired = true
        fixture.controller.select(request("retirement-deadline-b"))
        fixture.scheduler.runUntil { departing.cancelAttempted }
        fixture.scheduler.runSteps(3)
        assertFalse(departing.released)
        assertTrue(departing.joinTimeouts.all { it == 0L })
        fixture.scheduler.advanceTimeBy(NATIVE_DECODER_JOIN_WATCHDOG_MS - 1L)
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertFalse(departing.released)
        assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, fixture.controller.stopForTaskRemoval().status())
    }

    @Test
    fun fatalRetiresHealthyWorkersWhileRetainingOnlyTheStuckLane() {
        val fixture = Fixture()
        fixture.controller.select(request("fatal-live"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.newDecodersExposePcmHead = false
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("fatal-pending"))
        )
        fixture.scheduler.runSteps(2)
        val workers = fixture.environment.decoders.filterNot { it.released }
        assertEquals(2, workers.size)
        val stuck = workers.last().also { it.joinWatchdogExpired = true }

        assertTrue(fixture.controller.injectFatalActorFaultForTest("unrelated actor fault"))
        fixture.scheduler.runNext()

        assertTrue(workers.all { it.cancelAttempted })
        assertFalse(stuck.released)
        assertTrue(workers.filterNot { it === stuck }.all { it.released })
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        val stop = fixture.controller.stopForTaskRemoval()
        assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
    }

    @Test
    fun failedRuntimeJoinNeverPermitsBorrowedLanesToBeReleased() {
        val fixture = Fixture()
        fixture.controller.select(request("fatal-runtime-join"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val workers = fixture.environment.decoders.filterNot { it.released }
        fixture.environment.runtimeJoinFailure = IllegalStateException("join did not complete")

        assertTrue(fixture.controller.injectFatalActorFaultForTest("unrelated actor fault"))
        fixture.scheduler.runNext()

        assertTrue(workers.all { it.cancelAttempted })
        assertTrue(workers.none { it.released })
        assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.FatalRecoveryRequired>()
            .single().diagnostic.contains("native runtime join failed"))
    }

    @Test
    fun speculativeAuthorizationFailureDuringRouteRecoveryDoesNotQuarantineForeground() {
        val fixture = Fixture()
        fixture.controller.select(request("route-foreground"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("route-speculation"))
        )
        fixture.scheduler.runSteps(2)
        val speculative = fixture.environment.decoders.single {
            it.spec.location.contains("route-speculation")
        }
        assertNotNull(fixture.environment.snapshots.last().automaticSuccessor)
        speculative.failNativeAdvance = true

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertTrue(speculative.cancelAttempted)
        assertTrue(speculative.released)
        assertEquals(1L, requireNotNull(fixture.environment.lastSelected()).selectionToken)
    }

    @Test
    fun autoplayReplacementAuthorizesBeforePresentationCommitToAvoidClosedGateDeadlock() {
        val fixture = Fixture()
        fixture.controller.select(request("gate-autoplay-outgoing"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val replacement = fixture.controller.select(request("gate-autoplay-replacement"))
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        val ownership = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.acceptNonEmptyWithoutPresentation(ownership)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(ownership.generation)
        }

        assertEquals(listOf(ownership.generation),
            fixture.environment.manualSelectionGateAuthorizations)
        assertFalse(fixture.environment.manualSelectionGateArmed)
        assertFalse(
            "Gate authorization must not invent callback presentation/commit evidence",
            fixture.ownerPublications().any { it.selection.token == replacement }
        )
        repeat(3) {
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
        }
        assertEquals(
            "An already-authorized generation must not retry JNI authorization every poll",
            1,
            fixture.environment.operations.count {
                it.startsWith("manual-gate-authorize:${ownership.generation}:")
            }
        )
    }

    @Test
    fun playingInPlaceSeekClosesGateUntilExactRebasedGenerationHasPcm() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("seek-gate"), metadata("seek-gate", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.operations.clear()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 12_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val rebased = requireNotNull(fixture.environment.lastSelected())

        assertEquals(outgoing.selectionToken, rebased.selectionToken)
        assertTrue(rebased.generation > outgoing.generation)
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })
        val armOperation = fixture.environment.operations.indexOfFirst {
            it.startsWith("manual-gate-arm:")
        }
        val decoderSeekOperation = fixture.environment.operations.indexOfFirst {
            it.startsWith("decoder-seek:${token.value}:")
        }
        assertTrue(fixture.environment.operations.toString(),
            armOperation >= 0 && decoderSeekOperation > armOperation)
        assertTrue(fixture.environment.manualSelectionGateAuthorizations.isEmpty())

        fixture.environment.acceptNonEmptyWithoutPresentation(rebased)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(rebased.generation)
        }

        assertEquals(listOf(rebased.generation),
            fixture.environment.manualSelectionGateAuthorizations)
        assertFalse(fixture.environment.manualSelectionGateArmed)
        assertEquals(listOf("seek-gate"), fixture.ownerPublications().map {
            it.selection.songId
        })
    }

    @Test
    fun rapidPlayingSeeksKeepOneGateClosedUntilOnlyLatestGenerationHasPcm() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("rapid-seek-gate"))
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
        val superseded = requireNotNull(fixture.environment.lastSelected())
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 15_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val latest = requireNotNull(fixture.environment.lastSelected())
        assertTrue(latest.generation > superseded.generation)
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("manual-gate-arm:")
        })

        fixture.environment.acceptNonEmptyWithoutPresentation(superseded)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertTrue(fixture.environment.manualSelectionGateAuthorizations.isEmpty())

        fixture.environment.acceptNonEmptyWithoutPresentation(latest)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(latest.generation)
        }

        assertEquals(listOf(latest.generation),
            fixture.environment.manualSelectionGateAuthorizations)
        assertFalse(fixture.environment.manualSelectionGateArmed)
    }

    @Test
    fun coalescedPauseAndSeekStillClosesPhysicalGateBeforeDecoderRebase() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("pause-seek-gate"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        fixture.environment.operations.clear()

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                playWhenReady = false,
                requestedPositionMs = 9_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val rebased = requireNotNull(fixture.environment.lastSelected())
        val armOperation = fixture.environment.operations.indexOfFirst {
            it.startsWith("manual-gate-arm:")
        }
        val decoderSeekOperation = fixture.environment.operations.indexOfFirst {
            it.startsWith("decoder-seek:${token.value}:")
        }

        assertTrue(fixture.environment.operations.toString(),
            armOperation >= 0 && decoderSeekOperation > armOperation)
        assertTrue(fixture.environment.manualSelectionGateArmed)
        assertFalse(fixture.environment.snapshots.last().playing)

        fixture.environment.acceptNonEmptyWithoutPresentation(rebased)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.manualSelectionGateAuthorizations.contains(rebased.generation)
        }
        assertEquals(listOf(rebased.generation),
            fixture.environment.manualSelectionGateAuthorizations)

        fixture.environment.installPaused(rebased)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.ownerPublications().lastOrNull()?.ownership == rebased
        }
        val pausedRebase = fixture.ownerPublications().last()
        assertEquals(token, pausedRebase.selection.token)
        assertEquals(NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            pausedRebase.kind)
        assertNull(pausedRebase.evidence)
        assertFalse(fixture.environment.snapshots.last().playing)
        val rebaseEvent = fixture.events.indexOfLast {
            it is NativeEngineControllerEvent.OwnerPublished && it.publication.ownership == rebased
        }
        assertTrue("Paused source preparation must survive the reducer's owner rebase",
            fixture.events.drop(rebaseEvent + 1).any {
                it is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible && it.token == token
            })

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = true
        ) { previous -> previous.copy(playWhenReady = true) })
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.last().playing)
        assertEquals(rebased, fixture.environment.snapshots.last().selectedOwnership)
    }

    @Test
    fun pendingPausedOutputRetirementSurvivesANewerPausedSelection() =
        pendingPausedOutputRetirementSurvivesReplacement(playBeforeAcknowledgement = false)

    @Test
    fun pendingPausedOutputRetirementFinishesBeforeANewerPlayCanReopen() =
        pendingPausedOutputRetirementSurvivesReplacement(playBeforeAcknowledgement = true)

    private fun pendingPausedOutputRetirementSurvivesReplacement(playBeforeAcknowledgement: Boolean) {
        val fixture = Fixture()
        fixture.controller.select(request("retiring-output-old", autoplay = false))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        fixture.environment.operations.clear()
        fixture.environment.outputRetirementPendingPolls = 100
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertFalse(fixture.environment.operations.contains("recreate-runtime"))
        val next = fixture.controller.select(request("retiring-output-next", autoplay = false))
        if (playBeforeAcknowledgement) {
            assertTrue(fixture.controller.updateDesiredWithAudibleTransport(next, true) {
                it.copy(playWhenReady = true)
            })
        }
        fixture.scheduler.runSteps(6)
        assertTrue("The controlled native retirement acknowledgement is still pending",
            fixture.environment.outputRetirementPendingPolls > 0)
        assertFalse("A newer owner cannot abandon accepted output retirement: ${fixture.environment.operations}",
            fixture.environment.operations.contains("recreate-runtime"))
        assertFalse("Play cannot reopen a feeder whose retirement has not acknowledged",
            fixture.environment.operations.contains("reopen-output"))
        assertFalse(fixture.environment.snapshots.any { it.selectedOwnership?.selectionToken == next.value })

        val acknowledgementBoundary = fixture.environment.operations.size
        fixture.environment.outputRetirementPendingPolls = 0
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 30) {
            fixture.environment.lastSelected()?.selectionToken == next.value
        }
        val operations = fixture.environment.operations
        val recreated = operations.indexOf("recreate-runtime")
        val acknowledgedRetirement = operations.drop(acknowledgementBoundary)
            .indexOfFirst { it.startsWith("retire-output:") }.let { if (it < 0) -1 else it + acknowledgementBoundary }
        assertTrue(operations.toString(), acknowledgedRetirement >= acknowledgementBoundary && recreated > acknowledgedRetirement)
        val installed = requireNotNull(fixture.environment.lastSelected())
        if (playBeforeAcknowledgement) {
            fixture.scheduler.runUntil(maximumSteps = 30) { operations.contains("reopen-output") }
            assertTrue(operations.toString(), operations.indexOf("reopen-output") > acknowledgedRetirement)
        } else {
            assertFalse(operations.contains("reopen-output"))
            fixture.environment.installPaused(installed)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil { fixture.ownerPublications().lastOrNull()?.selection?.token == next }
            assertEquals(NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                fixture.ownerPublications().last().kind)
            assertFalse(fixture.environment.snapshots.last().playing)
        }
    }

    @Test
    fun pausedRouteRecoveryFinishesConsumerRetirementAndLogicalPreparationWithoutOpeningASink() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("paused-route-ack").copy(autoplay = false))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        fixture.environment.operations.clear()
        fixture.environment.reopenOutputSucceeds = false
        fixture.environment.outputRetirementPendingPolls = 2
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.operations.toString(), fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        fixture.scheduler.runUntil(maximumSteps = 20) {
            fixture.environment.outputRetirementPendingPolls == 0 && fixture.environment.operations.contains("recreate-runtime")
        }
        assertTrue(fixture.controller.updateDesired(token) { it.copy(requestedPositionMs = 3_000L,
            seekRevision = it.seekRevision + 1L, tempo = 1.25f) })
        fixture.scheduler.runSteps(3)
        val state = fixture.environment.snapshots.last()
        assertEquals(token.value, state.selectedOwnership?.selectionToken)
        assertFalse(state.playing)
        assertEquals(144_000L, state.banks[checkNotNull(state.selectedBank).nativeCode].firstTimelineFrame)
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        fixture.environment.reopenOutputSucceeds = true
        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, true) { it.copy(playWhenReady = true) })
        fixture.scheduler.runSteps(4)
        assertTrue(fixture.environment.operations.contains("reopen-output"))
    }

    @Test
    fun pausedRouteRecoveryRetainsItsRequestAcrossPendingLoopWorkerRetirement() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.holdLoopPreparation = true
        val song = request("paused-route-loop-retirement", autoplay = false)
        fixture.controller.select(song)
        fixture.scheduler.runNext()
        val before = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(before)
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(song.identity(), song.durationMs))
        fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }
        fixture.environment.operations.clear()
        fixture.environment.loopPreparationPendingJoinPolls = 4
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertTrue("Route recovery must first cancel the actual loop worker",
            fixture.environment.loopPreparationCancelRequestCount > 0)
        assertFalse("A pending worker still owns the runtime", fixture.environment.operations.contains("stop-join-runtime"))
        fixture.environment.holdLoopPreparation = false
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 24) { fixture.environment.operations.contains("recreate-runtime") }
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertNotEquals(before, fixture.environment.lastSelected())
        assertFalse(fixture.environment.operations.contains("prepare-output"))
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        assertFalse(fixture.environment.snapshots.last().playing)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun explicitPausedRouteAfterColdStopRebasesTheOwnerWithoutConsumingItsDeferredPlayLatch() {
        val fixture = Fixture()
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runUntil { stop.isComplete() }
        val token = fixture.controller.select(request("cold-stop-paused-route", autoplay = false).copy(
            durationMs = 6_000L, startPositionMs = 1_700L, playbackEndMs = 6_000L))
        fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == token.value }
        val before = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(before)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        fixture.environment.operations.clear()
        fixture.environment.outputRetirementPendingPolls = 2
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runUntil(maximumSteps = 24) { fixture.environment.operations.contains("recreate-runtime") }
        val after = requireNotNull(fixture.environment.lastSelected())
        assertEquals(token.value, after.selectionToken)
        assertTrue(after.generation > before.generation)
        val paused = fixture.environment.snapshots.last()
        assertEquals(81_600L, paused.banks[requireNotNull(paused.selectedBank).nativeCode].firstTimelineFrame)
        assertFalse(paused.playing)
        assertFalse(fixture.environment.operations.contains("prepare-output"))
        assertFalse(fixture.environment.operations.contains("reopen-output"))
        fixture.environment.installPaused(after)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, true) { it.copy(playWhenReady = true) })
        fixture.scheduler.runUntil(maximumSteps = 24) { fixture.environment.operations.contains("reopen-output") }
    }

    @Test
    fun routeRecoveryReplaysAccentedBeatGridWithoutAnotherDesiredChange() {
        for (paused in listOf(false, true)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("route-grid-$paused", autoplay = !paused).copy(
                initialControls = NativeEngineInitialControls(metronome = EngineMetronomeConfig(
                    enabled = true, songSynced = true,
                    beatGridMs = longArrayOf(0L, 500L, 1_000L),
                    downbeats = booleanArrayOf(true, false, true)
                ))
            ))
            fixture.scheduler.runNext()
            val original = requireNotNull(fixture.environment.lastSelected())
            if (paused) {
                fixture.environment.installPaused(original)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runNext()
            } else fixture.presentLatest(timelineFrame = 0L)
            val before = fixture.environment.beatGridUpdates.last()
            val beforeCount = fixture.environment.beatGridUpdates.size

            fixture.controller.requestRouteRecovery()
            fixture.scheduler.runUntil { fixture.environment.operations.contains("recreate-runtime") }

            assertEquals(beforeCount + 1, fixture.environment.beatGridUpdates.size)
            val replay = fixture.environment.beatGridUpdates.last()
            val recovered = requireNotNull(fixture.environment.lastSelected())
            assertNotEquals(original, recovered)
            assertEquals(recovered, replay.ownership)
            assertEquals(before.bank, replay.bank)
            assertTrue(replay.revision > before.revision)
            assertTrue(replay.beatFrames.contentEquals(longArrayOf(0L, 24_000L, 48_000L)))
            assertTrue(requireNotNull(replay.beatAccents).contentEquals(booleanArrayOf(true, false, true)))
            assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID,
                fixture.environment.snapshots.last().controls.metronome.mode)
            if (paused) {
                assertFalse(fixture.environment.snapshots.last().playing)
                fixture.environment.installPaused(recovered)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runNext()
                assertTrue(fixture.controller.updateDesiredWithAudibleTransport(token, true) {
                    it.copy(playWhenReady = true)
                })
                fixture.scheduler.runUntil { fixture.environment.lastSelected() != recovered }
                val resumed = requireNotNull(fixture.environment.lastSelected())
                fixture.environment.installPaused(resumed)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runNext()
                assertTrue(fixture.environment.snapshots.last().playing)
                assertEquals(beforeCount + 2, fixture.environment.beatGridUpdates.size)
                assertEquals(resumed, fixture.environment.beatGridUpdates.last().ownership)
                assertTrue(fixture.environment.beatGridUpdates.last().beatFrames.contentEquals(replay.beatFrames))
                assertTrue(requireNotNull(fixture.environment.beatGridUpdates.last().beatAccents)
                    .contentEquals(requireNotNull(replay.beatAccents)))
            }
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.BeatGridNotReady })
        }
    }

    @Test
    fun routeRecoveryReportsRejectedBeatGridAndDoesNotMarkItPublished() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("route-grid-rejected").copy(
            initialControls = NativeEngineInitialControls(metronome = EngineMetronomeConfig(
                enabled = true, songSynced = true, beatGridMs = longArrayOf(0L, 500L),
                downbeats = booleanArrayOf(true, false)
            ))
        ))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val beforeCount = fixture.environment.beatGridUpdates.size
        fixture.environment.beatGridPublishStatus = NativeRuntimePublishStatus.INVALID_SNAPSHOT

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()

        assertEquals(beforeCount + 1, fixture.environment.beatGridUpdates.size)
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.BeatGridNotReady && it.token == token })
        fixture.environment.beatGridPublishStatus = NativeRuntimePublishStatus.ACCEPTED
        assertTrue(fixture.controller.updateDesired(token) { it.copy(semitoneShift = 1) })
        fixture.scheduler.runNext()
        assertEquals(beforeCount + 2, fixture.environment.beatGridUpdates.size)
        assertEquals(fixture.environment.lastSelected(), fixture.environment.beatGridUpdates.last().ownership)
    }

    @Test
    fun routeRecoveryUsesJoinReopenRecreateRefillThenNewerEpoch() {
        val fixture = Fixture()
        fixture.controller.select(request("route"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        fixture.environment.operations.clear()
        val before = fixture.environment.outputGeneration

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()

        val stop = fixture.environment.operations.indexOf("stop-join-runtime")
        val reopen = fixture.environment.operations.indexOf("reopen-output")
        val recreate = fixture.environment.operations.indexOf("recreate-runtime")
        val publish = fixture.environment.operations.indexOfFirst { it.startsWith("publish:") }
        assertTrue(stop in 0 until reopen)
        assertTrue(reopen in 0 until recreate)
        assertTrue(recreate in 0 until publish)
        val routeOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(routeOwner.generation > before + 1L)

        fixture.environment.present(routeOwner, timelineFrame = 96_000L)
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun nonzeroRouteReplayCannotBecomeAFrameZeroCacheBuilderOnLaterSeek() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("route-cache-builder"))
        val stems = currentStems("route-cache-builder")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = stems,
                stemRevision = "route-cache-builder-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        fixture.scheduler.runUntil {
            fixture.environment.decoders.count { !it.released } == 7
        }
        val decoders = fixture.environment.decoders.filter { !it.released }

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertTrue(decoders.all { it.requestedSeekTargets.size == 1 })

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 10_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()

        assertEquals(7, fixture.environment.decoders.count { !it.released })
        assertTrue(decoders.all { it.requestedSeekTargets.size == 2 })
        assertFalse(fixture.environment.decoders.any {
            !it.released && it.spec.initialPositionUs == 10_000_000L
        })
    }

    @Test
    fun seekAndPauseDuringRouteRecoveryRetargetTheExactOwnerWithoutStaleResume() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("route-seek-pause"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        val obsoleteRouteOwner = requireNotNull(fixture.environment.lastSelected())
        assertFalse(fixture.environment.snapshots.last().playing)
        fixture.environment.operations.clear()

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = false
        ) { previous ->
            previous.copy(
                playWhenReady = false,
                requestedPositionMs = 7_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val exactSeekOwner = requireNotNull(fixture.environment.lastSelected())

        assertTrue(exactSeekOwner.generation > obsoleteRouteOwner.generation)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertTrue(fixture.environment.manualSelectionGateArmed)

        fixture.environment.installPaused(exactSeekOwner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.ownerPublications().lastOrNull()?.ownership == exactSeekOwner
        }

        assertEquals(exactSeekOwner, fixture.ownerPublications().last().ownership)
        assertFalse(
            "The pre-recovery Play intent must not override the Pause that arrived in flight",
            fixture.environment.snapshots.last().playing
        )
        assertTrue(fixture.environment.snapshots.none {
            it.selectedOwnership == obsoleteRouteOwner && it.playing
        })

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = true
        ) { previous -> previous.copy(playWhenReady = true) })
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.snapshots.last().playing)
        assertEquals(exactSeekOwner, fixture.environment.snapshots.last().selectedOwnership)
    }

    @Test
    fun routeRecoveryDuringUnpresentedSeekKeepsTheRequestedCursor() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("route-after-seek"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = msToFrameForTest(2_000L))

        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = true
        ) { previous ->
            previous.copy(
                requestedPositionMs = 7_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val unpresentedSeekOwner = requireNotNull(fixture.environment.lastSelected())

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()

        val recovered = fixture.environment.snapshots.last()
        val recoveredBank = requireNotNull(recovered.selectedBank)
        assertTrue(recovered.selectedOwnership != unpresentedSeekOwner)
        assertEquals(
            msToFrameForTest(7_000L),
            recovered.banks[recoveredBank.nativeCode].firstTimelineFrame
        )
    }

    @Test
    fun routeReopenAndRuntimeRecreateFailuresStaySilentAndRetryWithoutFatalQuarantine() {
        listOf("reopen", "runtime").forEach { failurePoint ->
            val fixture = Fixture()
            val token = fixture.controller.select(request("route-retry-$failurePoint"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.environment.operations.clear()
            if (failurePoint == "reopen") {
                fixture.environment.reopenOutputSucceeds = false
            } else {
                fixture.environment.recreateRuntimeSucceeds = false
            }

            fixture.controller.requestRouteRecovery()
            fixture.scheduler.runNext()

            assertFalse(fixture.events.any {
                it is NativeEngineControllerEvent.FatalRecoveryRequired
            })
            assertEquals(1, fixture.events.count {
                it is NativeEngineControllerEvent.Failure &&
                    it.operation == "route-recovery"
            })
            val silence = fixture.environment.operations.indexOfFirst {
                it.startsWith("immediate-silence:")
            }
            val stop = fixture.environment.operations.indexOf("stop-join-runtime")
            assertTrue(fixture.environment.operations.toString(), silence in 0 until stop)
            assertTrue(fixture.scheduler.activeTaskCount() > 0)
            assertTrue(
                fixture.scheduler.scheduledDelays.contains(250L)
            )

            fixture.environment.reopenOutputSucceeds = true
            fixture.environment.recreateRuntimeSucceeds = true
            fixture.scheduler.runNext()

            assertFalse(fixture.events.any {
                it is NativeEngineControllerEvent.FatalRecoveryRequired
            })
            assertTrue(fixture.environment.operations.contains("recreate-runtime"))
            val routeOwner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.present(routeOwner, timelineFrame = 0L)
            fixture.scheduler.runNext()
            assertTrue(fixture.environment.snapshots.last().playing)
            assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
                token = token,
                playWhenReady = false
            ) { previous -> previous.copy(playWhenReady = false) })
            fixture.scheduler.runNext()
            assertFalse(fixture.environment.snapshots.last().playing)
            assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
                token = token,
                playWhenReady = true
            ) { previous -> previous.copy(playWhenReady = true) })
            fixture.scheduler.runNext()
            assertTrue(fixture.environment.snapshots.last().playing)
            assertEquals(
                if (failurePoint == "reopen") 2 else 1,
                fixture.environment.operations.count { it == "reopen-output" }
            )
        }
    }

    @Test
    fun nativeOutputRecoveryFlagUsesTheRouteTransactionWithoutADeviceCallback() {
        val fixture = Fixture()
        fixture.controller.select(request("native-output-route-error"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()
        fixture.environment.outputRecoveryRequired = true

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertTrue(fixture.environment.operations.contains("reopen-output"))
        assertTrue(fixture.environment.operations.contains("recreate-runtime"))
        assertFalse(fixture.environment.outputRecoveryRequired)
    }

    @Test
    fun alreadyPausedFatalQuarantineDoesNotAdvanceOnlyTheControllerRevision() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("paused-fatal-revision"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesiredWithAudibleTransport(
            token = token,
            playWhenReady = false
        ) { previous -> previous.copy(playWhenReady = false) })
        fixture.scheduler.runNext()

        assertTrue(fixture.controller.injectFatalActorFaultForTest("synthetic paused route fatal"))
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })

        var transformInvoked = false
        assertFalse(fixture.controller.updateDesired(token) { previous ->
            transformInvoked = true
            previous
        })
        assertFalse(
            "A terminal controller must reject before observing adapter-owned revision state",
            transformInvoked
        )
    }

    @Test
    fun stopAfterFatalQuarantineCompletesSynchronouslyWithoutActorWork() {
        val fixture = Fixture()
        fixture.controller.select(request("fatal-before-recents"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.injectFatalActorFaultForTest("terminal native owner"))
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
        val tasksBefore = fixture.scheduler.activeTaskCount()
        val operationsBefore = fixture.environment.operations.toList()

        repeat(2) {
            val stop = fixture.controller.stopForTaskRemoval()
            assertTrue(stop.isComplete())
            assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
        }

        assertEquals(tasksBefore, fixture.scheduler.activeTaskCount())
        assertEquals(operationsBefore, fixture.environment.operations)
    }

    @Test
    fun taskRemovalCompletesWhileOutputRouteIsWaitingAndNextSelectionCanReopenIt() {
        val fixture = Fixture()
        fixture.controller.select(request("route-pending-stop"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.reopenOutputSucceeds = false

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        val operationsBeforeStop = fixture.environment.operations.size
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertEquals(0, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        val stopOperations = fixture.environment.operations.drop(operationsBeforeStop)
        val replacementIndex = stopOperations.indexOf("recreate-runtime")
        assertTrue("The joined native bank owner was not retired", replacementIndex >= 0)
        assertEquals("stop-join-runtime", stopOperations[replacementIndex + 1])
        assertFalse("Administrative STOP reopened physical output", stopOperations.contains("reopen-output"))
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })

        fixture.environment.reopenOutputSucceeds = true
        val snapshotsBeforeRestart = fixture.environment.snapshots.size
        val replacement = fixture.controller.select(request("route-after-task-removal"))
        fixture.scheduler.runNext()
        assertEquals(
            "operations=${fixture.environment.operations}; events=${fixture.events}",
            replacement.value,
            fixture.environment.lastSelected()?.selectionToken
        )
        assertTrue(
            "The replacement runtime received no exact empty/unassigned snapshot before reuse",
            fixture.environment.snapshots.drop(snapshotsBeforeRestart).any { snapshot ->
                snapshot.selectedBank == null && snapshot.banks.all { !it.assigned }
            }
        )
    }

    @Test
    fun joinedRouteStopCannotCompleteWhenNativeBankReplacementOrJoinFails() {
        for (failure in listOf("replacement", "join", "output")) {
            val fixture = Fixture()
            fixture.controller.select(request("joined-route-stop-$failure"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.environment.reopenOutputSucceeds = false
            fixture.controller.requestRouteRecovery()
            fixture.scheduler.runNext()
            if (failure == "replacement") {
                fixture.environment.recreateRuntimeSucceeds = false
            } else if (failure == "join") {
                fixture.environment.runtimeJoinFailure = IllegalStateException("replacement join failed")
            } else {
                fixture.environment.outputRetirementFailure = IllegalStateException("native consumer did not retire")
            }
            val operationsBeforeStop = fixture.environment.operations.size
            val stop = fixture.controller.stopForTaskRemoval()

            fixture.scheduler.runNext()

            assertEquals(failure, NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
            assertTrue(stop.isComplete())
            val operations = fixture.environment.operations.drop(operationsBeforeStop)
            assertTrue(operations.contains("recreate-runtime"))
            assertTrue("Even failed replacement must attempt a join", operations.contains("stop-join-runtime"))
            assertFalse(operations.contains("reopen-output"))
            assertEquals(0, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
            assertEquals(1, fixture.events.count { it is NativeEngineControllerEvent.FatalRecoveryRequired })
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
            val tasksAfterFailure = fixture.scheduler.activeTaskCount()
            assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE,
                fixture.controller.stopForTaskRemoval().status())
            assertEquals(tasksAfterFailure, fixture.scheduler.activeTaskCount())
        }
    }

    @Test
    fun completedTaskRemovalRetiresItsStreamAndCannotCarryAnOldSuccessorIntoTheNextOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("plan-before-stop"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("stale-successor-before-stop"))
        )
        fixture.scheduler.runSteps(2)
        assertNotNull(fixture.environment.snapshots.last().automaticSuccessor)
        fixture.environment.operations.clear()

        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runUntil(maximumSteps = 16) { stop.isComplete() }
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertFalse(fixture.environment.operations.contains("reopen-output"))

        val replacement = fixture.controller.select(request("one-song-after-stop"))
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        fixture.presentLatest()
        fixture.scheduler.runSteps(3)

        val joined = fixture.environment.operations.indexOf("stop-join-runtime")
        val flushed = fixture.environment.operations.indexOfFirst { it.startsWith("retire-output:") }
        val reopened = fixture.environment.operations.indexOf("reopen-output")
        val recreated = fixture.environment.operations.indexOf("recreate-runtime")
        assertTrue(fixture.environment.operations.toString(), joined in 0 until flushed)
        assertTrue(fixture.environment.operations.toString(), flushed in 0 until reopened)
        assertTrue(fixture.environment.operations.toString(), reopened in 0 until recreated)
        assertNull(fixture.environment.snapshots.last().automaticSuccessor)
        assertEquals(0, fixture.environment.decoderLeaseSnapshot().candidateDecoderCount)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().activeDecoderCount)
    }

    @Test
    fun routeChangeReportedAfterTaskRemovalIsRetainedUntilTheNextOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("stopped-before-route-change"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        fixture.environment.operations.clear()

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertFalse(
            "A stopped player should defer the physical reopen until it has a new owner",
            fixture.environment.operations.contains("reopen-output")
        )

        val replacement = fixture.controller.select(request("owner-after-stopped-route-change"))
        fixture.scheduler.runNext()

        assertTrue(fixture.environment.operations.contains("reopen-output"))
        assertTrue(fixture.environment.operations.contains("recreate-runtime"))
        assertEquals(replacement.value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
    }

    @Test
    fun routeFailureDuringStopSkipsDeadCallbackFlushAndReopensForNextOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("route-during-stop"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()
        fixture.environment.holdUnassignmentAcknowledgement = true

        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()
        assertFalse(stop.isComplete())

        fixture.environment.outputRecoveryRequired = true
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        assertFalse(stop.isComplete())

        fixture.environment.holdUnassignmentAcknowledgement = false
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertTrue(fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })

        fixture.environment.operations.clear()
        val replacement = fixture.controller.select(request("owner-after-stopped-route-error"))
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        assertTrue(fixture.environment.operations.contains("reopen-output"))
        assertTrue(fixture.environment.operations.contains("recreate-runtime"))
        assertFalse(fixture.environment.outputRecoveryRequired)
    }

    @Test
    fun restartAfterTaskRemovalReopensOnceAndRetriesRuntimeWithoutReopeningAgain() {
        val fixture = Fixture()
        fixture.controller.select(request("owner-before-restart-retry"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        fixture.environment.operations.clear()
        fixture.environment.recreateRuntimeSucceeds = false

        val replacement = fixture.controller.select(request("owner-after-restart-retry"))
        fixture.scheduler.runNext()

        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.Failure && it.operation == "route-recovery"
        })
        assertEquals(1, fixture.environment.operations.count { it == "reopen-output" })
        assertTrue(fixture.scheduler.scheduledDelays.contains(250L))

        fixture.environment.recreateRuntimeSucceeds = true
        fixture.scheduler.runNext()

        assertEquals(replacement.value, fixture.environment.lastSelected()?.selectionToken)
        assertEquals(1, fixture.environment.operations.count { it == "reopen-output" })
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
    }

    @Test
    fun rapidSelectionsDuringRouteRetryConflateToLatestOwnerAndDrainOldBank() {
        val fixture = Fixture()
        fixture.controller.select(request("route-burst-a"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.reopenOutputSucceeds = false
        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()

        fixture.controller.select(request("route-burst-b"))
        val latest = fixture.controller.select(request("route-burst-c"))
        fixture.scheduler.runNext()
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })

        fixture.environment.reopenOutputSucceeds = true
        fixture.scheduler.runUntil(maximumSteps = 24) {
            fixture.environment.lastSelected()?.selectionToken == latest.value
        }
        fixture.environment.present(
            requireNotNull(fixture.environment.lastSelected()),
            timelineFrame = 0L
        )
        fixture.scheduler.runNext()

        assertEquals("route-burst-c", fixture.ownerPublications().last().selection.songId)
        assertTrue(fixture.environment.snapshots.last().playing)
        fixture.scheduler.runSteps(16)
        assertEquals(
            "operations=${fixture.environment.operations}; " +
                "snapshots=${fixture.environment.snapshots.takeLast(8)}; events=${fixture.events}",
            1,
            fixture.environment.decoderLeaseSnapshot().totalDecoderCount
        )
    }

    @Test
    fun routeRecoveryUsesPublicCursorForUntouchedReplacementButNotAfterReplacementSeek() {
        listOf(false, true).forEach { seekReplacement ->
            val suffix = if (seekReplacement) "seek" else "inherit"
            val fixture = Fixture()
            val token = fixture.controller.select(request("replacement-route-$suffix"))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = msToFrameForTest(2_000L))

            val v1 = currentStems("replacement-route-$suffix-v1")
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    sourceMode = EngineSourceMode.STEMS,
                    stemLanes = v1,
                    stemRevision = "replacement-route-$suffix-v1",
                    stemControls = v1.associate { it.id to EngineStemControl() }
                )
            })
            fixture.scheduler.runNext()

            val v2 = currentStems("replacement-route-$suffix-v2")
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    stemLanes = v2,
                    stemRevision = "replacement-route-$suffix-v2",
                    stemControls = v2.associate { it.id to EngineStemControl() }
                )
            })
            fixture.scheduler.runNext()

            if (seekReplacement) {
                assertTrue(fixture.controller.updateDesired(token) { previous ->
                    previous.copy(
                        requestedPositionMs = 7_000L,
                        seekRevision = previous.seekRevision + 1L
                    )
                })
                fixture.scheduler.runNext()
            }

            val beforeRoute = fixture.environment.snapshots.last()
            val beforeRouteBank = requireNotNull(beforeRoute.selectedBank)
            assertEquals(
                if (seekReplacement) {
                    msToFrameForTest(7_000L)
                } else {
                    // An untouched same-token replacement starts immediately after the last
                    // callback-authored public frame, never back at the song origin.
                    msToFrameForTest(2_000L) + 1L
                },
                beforeRoute.banks[beforeRouteBank.nativeCode].firstTimelineFrame
            )

            fixture.controller.requestRouteRecovery()
            fixture.scheduler.runNext()

            val recovered = fixture.environment.snapshots.last()
            val recoveredBank = requireNotNull(recovered.selectedBank)
            assertEquals(
                "before=$beforeRoute; recovered=$recovered; " +
                    "operations=${fixture.environment.operations}; events=${fixture.events}",
                msToFrameForTest(if (seekReplacement) 7_000L else 2_000L),
                recovered.banks[recoveredBank.nativeCode].firstTimelineFrame
            )
            assertTrue(
                "Same-token rollback siblings diverged after route recovery: $recovered",
                recovered.banks.filter { intent ->
                    intent.ownership?.selectionToken == token.value
                }.all { intent ->
                    intent.firstTimelineFrame ==
                        msToFrameForTest(if (seekReplacement) 7_000L else 2_000L)
                }
            )
        }
    }

    @Test
    fun taskRemovalPausesBeforeJoinAndFlushesOnlyAfterProducerJoin() {
        val fixture = Fixture()
        fixture.controller.select(request("joined-flush"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()
        val generationBeforeStop = fixture.environment.outputGeneration

        val stop = fixture.controller.stopForTaskRemoval()
        assertEquals(generationBeforeStop, fixture.environment.outputGeneration)
        assertTrue(fixture.environment.operations.contains("immediate-silence:$generationBeforeStop"))
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertTrue(fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertTrue(fixture.environment.outputGeneration > generationBeforeStop)
    }

    @Test
    fun taskRemovalClearsTransientFocusSuspensionForNextAutoplayOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("focus-before-task-removal"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        fixture.controller.suspendForAudioFocusLoss()
        fixture.scheduler.runNext()
        assertFalse(fixture.environment.snapshots.last().playing)

        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())

        val replacement = fixture.controller.select(request("autoplay-after-focus-stop"))
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        fixture.presentLatest()

        assertEquals(replacement.value, fixture.environment.lastSelected()?.selectionToken)
        assertTrue(fixture.environment.snapshots.last().playing)
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
    }

    @Test
    fun lateTransportStateAfterTaskRemovalStopCannotRestartAnOwnerlessRuntime() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("owner-before-late-stop-update"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()

        val stop = fixture.controller.stopForTaskRemoval()
        assertTrue(
            fixture.controller.updateDesiredWithAudibleTransport(token, false) { desired ->
                desired.copy(playWhenReady = false)
            }
        )
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertFalse(fixture.environment.operations.contains("recreate-runtime"))
        assertEquals(0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun aDrainedStopCannotCompleteTheReceiptOfASecondStopStillInTheMailbox() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("owner-before-two-stops"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) { desired ->
            desired.copy(playWhenReady = false)
        })
        val first = fixture.controller.stopForTaskRemoval()
        var second: NativeEngineStopRequest? = null
        fixture.environment.onNextAcceptedSnapshot = {
            // This earlier DesiredState and STOP1 were drained together. The real controller
            // publishes the desired state before dispatching STOP1, while STOP2 stays queued.
            second = fixture.controller.stopForTaskRemoval()
        }

        fixture.scheduler.runNext()

        val queued = requireNotNull(second) { "The earlier drained command did not publish" }
        assertEquals(NativeEngineStopStatus.SUPERSEDED, first.status())
        assertFalse("STOP1 must not acknowledge STOP2 before its own entry is dispatched", queued.isComplete())
        assertFalse("Neither the superseded nor the undispatched STOP can publish completion",
            fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
        fixture.scheduler.runUntil { queued.isComplete() }
        assertEquals(NativeEngineStopStatus.COMPLETED, queued.status())
        assertEquals(listOf(queued.id), fixture.events.filterIsInstance<NativeEngineControllerEvent.StopCompleted>()
            .map { it.requestId })
        assertTrue(fixture.environment.snapshots.last().banks.all { !it.assigned })
        assertEquals(0, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)

        val replacement = fixture.controller.select(request("owner-after-two-stops", autoplay = false))
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.selectionToken == replacement.value
        }
        fixture.presentLatest()
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(replacement, fixture.ownerPublications().last().selection.token)
        assertEquals(1, fixture.environment.snapshots.last().banks.count { it.assigned })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun aStopSupersededDuringDecoderRetirementCannotPublishACompletedReceipt() {
        val fixture = Fixture()
        fixture.controller.select(request("owner-before-stop-retirement-replacement"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        var second: NativeEngineStopRequest? = null
        fixture.environment.expectCancellationBeforeJoin(fixture.environment.decoders.toList()) {
            second = fixture.controller.stopForTaskRemoval()
        }
        val first = fixture.controller.stopForTaskRemoval()

        fixture.scheduler.runNext()

        val queued = requireNotNull(second) { "STOP1 never reached decoder retirement" }
        assertEquals(NativeEngineStopStatus.SUPERSEDED, first.status())
        assertFalse(queued.isComplete())
        assertFalse("A superseded active STOP must not publish successful completion",
            fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
        fixture.scheduler.runUntil { queued.isComplete() }
        assertEquals(NativeEngineStopStatus.COMPLETED, queued.status())
        assertEquals(listOf(queued.id), fixture.events.filterIsInstance<NativeEngineControllerEvent.StopCompleted>()
            .map { it.requestId })
        assertTrue(fixture.environment.snapshots.last().banks.all { !it.assigned })
        assertEquals(0, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
    }

    @Test
    fun fatalDecoderRetirementCompletesTheQueuedStopBehindASupersededActiveStop() {
        val fixture = Fixture()
        fixture.controller.select(request("owner-before-fatal-stop-retirement"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        var second: NativeEngineStopRequest? = null
        fixture.environment.expectCancellationBeforeJoin(fixture.environment.decoders.toList()) {
            second = fixture.controller.stopForTaskRemoval()
            error("Synthetic decoder retirement failure after STOP2 admission")
        }
        val first = fixture.controller.stopForTaskRemoval()

        fixture.scheduler.runNext()

        val queued = requireNotNull(second) { "STOP1 never reached decoder retirement" }
        assertEquals(NativeEngineStopStatus.SUPERSEDED, first.status())
        assertEquals("Quarantine cannot leave an undispatchable STOP receipt pending",
            NativeEngineStopStatus.FATAL_QUARANTINE, queued.status())
        assertTrue(queued.isComplete())
        assertEquals(1, fixture.events.count { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
    }

    @Test
    fun unexpectedImmediateSilenceFailureStillPublishesFailClosedStop() {
        val fixture = Fixture()
        fixture.controller.select(request("failed-immediate-silence"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()
        fixture.environment.immediateSilenceFailure =
            IllegalStateException("synthetic immediate-silence failure")

        val failure = runCatching { fixture.controller.stopForTaskRemoval() }.exceptionOrNull()
        fixture.scheduler.runNext()

        assertTrue(failure is IllegalStateException)
        assertEquals("synthetic immediate-silence failure", failure?.message)
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertTrue(fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
    }

    @Test
    fun newerSelectionSupersedesStopCompletionWithoutPublishingStaleStopped() {
        val fixture = Fixture()
        fixture.controller.select(request("old"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val stop = fixture.controller.stopForTaskRemoval()
        val latest = fixture.controller.select(request("new"))
        fixture.scheduler.runNext()
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.SUPERSEDED, stop.status())
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
        assertEquals(latest.value, fixture.environment.lastSelected()?.selectionToken)
    }

    @Test
    fun selectionCannotEnterInsideTaskRemovalSilenceAndStopEnqueueBoundary() {
        val fixture = Fixture()
        fixture.controller.select(request("owner-before-blocked-stop"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val silenceEntered = CountDownLatch(1)
        val releaseSilence = CountDownLatch(1)
        fixture.environment.onImmediateSilence = {
            silenceEntered.countDown()
            check(releaseSilence.await(TEST_THREAD_WATCHDOG_MS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release the synthetic silence boundary"
            }
        }
        val stops = ConcurrentLinkedQueue<NativeEngineStopRequest>()
        val selections = ConcurrentLinkedQueue<EngineSelectionToken>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val selectionAttempted = CountDownLatch(1)
        val selectionFinished = CountDownLatch(1)
        val stopThread = thread(start = true, isDaemon = true, name = "task-removal-stop") {
            runCatching { stops += fixture.controller.stopForTaskRemoval() }
                .onFailure(failures::add)
        }
        val selectionThread = thread(start = false, isDaemon = true, name = "blocked-selection") {
            selectionAttempted.countDown()
            try {
                selections += fixture.controller.select(request("owner-after-blocked-stop"))
            } catch (caught: Throwable) {
                failures += caught
            } finally {
                selectionFinished.countDown()
            }
        }

        try {
            assertTrue(
                "Task-removal silence boundary was never entered",
                silenceEntered.await(TEST_THREAD_WATCHDOG_MS, TimeUnit.MILLISECONDS)
            )
            selectionThread.start()
            assertTrue(
                "Selection thread did not attempt the transaction",
                selectionAttempted.await(TEST_THREAD_WATCHDOG_MS, TimeUnit.MILLISECONDS)
            )
            assertFalse(
                "Selection crossed the STOP transaction while silence was blocked",
                selectionFinished.await(BLOCKED_TRANSACTION_OBSERVATION_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(selections.isEmpty())
        } finally {
            releaseSilence.countDown()
            stopThread.join(TEST_THREAD_WATCHDOG_MS)
            selectionThread.join(TEST_THREAD_WATCHDOG_MS)
            if (stopThread.isAlive) stopThread.interrupt()
            if (selectionThread.isAlive) selectionThread.interrupt()
            stopThread.join(TEST_THREAD_CLEANUP_MS)
            selectionThread.join(TEST_THREAD_CLEANUP_MS)
        }

        assertFalse("Task-removal stop thread survived cleanup", stopThread.isAlive)
        assertFalse("Selection thread survived cleanup", selectionThread.isAlive)
        assertTrue("Task-removal boundary thread failed: $failures", failures.isEmpty())

        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == selections.single().value
        }
        assertEquals(NativeEngineStopStatus.SUPERSEDED, stops.single().status())
        assertEquals(selections.single().value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }
}
