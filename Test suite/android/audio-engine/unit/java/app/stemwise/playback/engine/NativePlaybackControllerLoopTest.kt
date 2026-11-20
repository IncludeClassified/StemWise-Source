package app.stemwise.playback.engine

import app.stemwise.playback.LoopPreparationPhase

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
internal class NativePlaybackControllerLoopTest : NativePlaybackControllerTestSupport() {
    @Test
    fun cancelledLatestRangeHeadReceivesANewNativeRevisionBeforeResuming() {
        val environment = FakeEnvironment(stuckDecoders = false, requireNativeStemReadiness = false,
            currentSongCacheSealedByDefault = false)
        environment.enforceLoopHeadRevisionOrder = true
        environment.holdLoopPreparation = true
        val owner = NativeRuntimeOwnership(1L, 1L)
        val identity = ManagedLoopStableIdentity(EngineSelectionToken(1L), enabled = true,
            songIdentityHash = 1L, originalContentHash = 2L, contentRevision = 1L,
            startTimelineFrame = 113_760L, endTimelineFrameExclusive = 305_760L,
            requiredLaneMask = 1, declickFrames = 240)
        val sources = listOf(LoopPreparationLaneSource(0, "original", "/paused-trim.wav"))
        val preparation = LoopPreparationOwner(environment, { 0L }, NativeControllerPreparationEvidence(), {})
        preparation.reconcile(LoopPreparationPurpose.RANGE, owner, identity, sources, runtimeStopped = false)
        preparation.advance(owner, runtimeStopped = false)
        val retiredRevision = checkNotNull(preparation.range).intent.revision
        assertTrue(preparation.cancel(abortNative = true, runtimeStopped = false))
        preparation.reconcile(LoopPreparationPurpose.RANGE, owner, identity, sources, runtimeStopped = false)
        environment.holdLoopPreparation = false
        repeat(3) { preparation.advance(owner, runtimeStopped = false) }

        val resumed = checkNotNull(preparation.range)
        assertTrue(resumed.intent.revision > retiredRevision)
        assertEquals(LoopPreparationResult.Ready, resumed.result)
    }

    @Test
    fun pausedNonzeroTrimWithRepeatOneDoesNotReuseACancelledHeadRevision() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.enforceLoopHeadRevisionOrder = true
        fixture.environment.exposePreparedLoopRange = true
        fixture.environment.holdLoopPreparation = true
        val trimmed = request("repeat-before-paused-trim", autoplay = false).copy(
            durationMs = 12_000L, playbackStartMs = 2_370L,
            playbackEndMs = 9_580L, startPositionMs = 2_370L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(trimmed.identity(), trimmed.durationMs))
        val token = fixture.controller.select(trimmed)
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.present(owner, msToFrameForTest(2_370L))
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(2_370L),
            cacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.environment.loopPreparationSpecs.isNotEmpty() }
        fixture.environment.holdLoopPreparation = false
        repeat(24) { if (fixture.scheduler.activeTaskCount() > 0) fixture.scheduler.runNext() }

        assertFalse("Repeat and loop availability reused a retired cache revision: ${fixture.events}",
            fixture.events.any { it is NativeEngineControllerEvent.Failure })
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertTrue(fixture.environment.managedLoopIntents.any { it.enabled && it.ownership == owner })
        assertEquals(token.value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun changedOriginalShaReopensWithNewBankAndLoopCacheAuthorityDespiteOpaqueRevision() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val source = request("same-path-changed-sha", autoplay = false)
            .copy(audioRevision = "opaque-current", durationMs = 12_000L, playbackEndMs = 12_000L, startPositionMs = 2_000L)
        fun prepare(hash: String): Pair<NativeRuntimeBankIntent, NativeRuntimeManagedLoopContentKey> {
            val token = fixture.controller.select(source.copy(originalContentSha256 = hash))
            fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == token.value }
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.installPaused(owner)
            fixture.environment.present(owner, msToFrameForTest(2_000L))
            fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(2_000L),
                cacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil {
                fixture.environment.latestPreparationIntent?.ownership == owner &&
                    fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable
            }
            val snapshot = fixture.environment.snapshots.last()
            val bank = snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode]
            return bank to requireNotNull(fixture.environment.latestPreparationIntent?.cacheKey)
        }
        val (firstBank, firstLoop) = prepare("1".repeat(64))
        val firstDecoder = fixture.environment.decoders.last()
        val (replacementBank, replacementLoop) = prepare("2".repeat(64))
        assertNotEquals(firstBank.ownership, replacementBank.ownership)
        org.junit.Assert.assertNotSame(firstDecoder.spec.sourceAdmission,
            fixture.environment.decoders.last().spec.sourceAdmission)
        assertNotEquals("Changed bytes cannot inherit the prior canonical PCM cache",
            firstBank.originalContentHash, replacementBank.originalContentHash)
        assertEquals(firstBank.originalContentHash, firstLoop.originalContentHash)
        assertEquals(replacementBank.originalContentHash, replacementLoop.originalContentHash)
        assertNotEquals(firstLoop.originalContentHash, replacementLoop.originalContentHash)
        assertNotEquals(firstLoop.contentRevision, replacementLoop.contentRevision)
    }

    @Test
    fun nonzeroOwnerWithoutCanonicalCachePreparesBoundedLoopHead() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("nonzero-without-canonical", autoplay = false)
            .copy(durationMs = 12_000L, playbackEndMs = 12_000L, startPositionMs = 2_000L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        assertEquals(msToFrameForTest(2_000L), fixture.environment.snapshots.last()
            .banks[requireNotNull(fixture.environment.snapshots.last().selectedBank).nativeCode].firstTimelineFrame)
        fixture.environment.installPaused(owner)
        fixture.environment.present(owner, msToFrameForTest(2_000L))
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(2_000L),
            cacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(1_999_000L, fixture.environment.loopPreparationSpecs.first().initialPositionUs)
        org.junit.Assert.assertSame("Loop opens must retain the exact Original byte/format admission",
            fixture.environment.decoders.single().spec.sourceAdmission,
            fixture.environment.loopPreparationSpecs.first().sourceAdmission)
        assertEquals(owner, fixture.environment.latestPreparationIntent?.ownership)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 4_000L)))
        fixture.scheduler.runUntil { fixture.environment.directLoopPolicies.lastOrNull()?.enabled == true }
        assertEquals(owner, fixture.environment.directLoopPolicies.last().ownership)
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
    }

    @Test
    fun pausedNonzeroOwnerWithOnlyAnAdmittedPrefixPreparesItsMissingLoopRange() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("paused-admitted-prefix", autoplay = false)
            .copy(durationMs = 8_000L, playbackEndMs = 8_000L, startPositionMs = 7_500L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L until 6_144L
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(7_550L),
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        val original = fixture.environment.decoders.single()
        original.setAcceptedCoverage(msToFrameForTest(7_500L), msToFrameForTest(8_000L))
        original.exactEndOfStreamFrame = msToFrameForTest(8_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(8)
        assertEquals("An admitted prefix cannot fill a sought gap without a frame-zero producer",
            1, fixture.environment.loopPreparationSpecs.size)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(7_499_000L, fixture.environment.loopPreparationSpecs.single().initialPositionUs)
        assertEquals(owner, fixture.environment.latestPreparationIntent?.ownership)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 4_000L,
            parkUntilRangeEntry = true)))
        fixture.scheduler.runUntil {
            fixture.environment.latestPreparationIntent?.cacheKey?.startTimelineFrame == msToFrameForTest(2_000L) &&
                fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable
        }
        assertEquals(listOf(7_499_000L, 1_999_000L),
            fixture.environment.loopPreparationSpecs.map { it.initialPositionUs })
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(original.requestedSeekTargets.isEmpty())
        assertFalse(original.cancelAttempted)
        assertTrue(fixture.environment.snapshots.none { it.playing })
        assertNull(fixture.controller.diagnosticsSnapshot().runtime.presentedPosition)
    }

    @Test
    fun boundedHeadPreparesRequestedStemsBeforeTheyBecomeAudible() {
        for (storage in listOf(NativeCacheStorageStatus.NOT_REQUESTED, NativeCacheStorageStatus.ADMITTED)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.environment.exposePreparedLoopRange = true
            val token = fixture.controller.select(request("bounded-source-edge-$storage")
                .copy(startPositionMs = 1_000L))
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.presentLatestWithAudibleControl(msToFrameForTest(1_000L))
            assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeApplied>()
                .any { it.sourceMode == EngineSourceMode.ORIGINAL })
            assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
            fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(1_000L),
                cacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil {
                fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable &&
                    fixture.environment.openLoopPreparationCount == 0
            }
            assertEquals(1, fixture.environment.latestPreparationIntent?.requiredLaneMask)
            assertTrue(fixture.setLoop(token, EngineLoopConfig(1_000L, 2_000L)))
            fixture.scheduler.runNext()
            assertTrue(fixture.setLoop(token, null))
            val stems = currentStems("bounded-source-edge-$storage")
            assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
                sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemRevision = "bounded-parts-$storage",
                stemControls = stems.associate { it.id to EngineStemControl() }) })
            fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(1_000L),
                cacheStorageStatus = storage)
            fixture.scheduler.runUntil {
                fixture.environment.snapshots.last().controls.sourceMode == NativeRuntimeSourceMode.STEMS
            }
            var preparationTurns = 0
            while (fixture.environment.latestPreparationIntent?.requiredLaneMask != 0x7f &&
                fixture.scheduler.activeTaskCount() > 0 && preparationTurns++ < 32) {
                fixture.scheduler.runNext()
            }
            assertEquals("$storage: preparing the source must not wait for its audible acknowledgement",
                0x7f, fixture.environment.latestPreparationIntent?.requiredLaneMask)
            fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 0 }
            val prepared = requireNotNull(fixture.environment.latestPreparationIntent)
            assertEquals(owner, prepared.ownership)
            assertEquals((0..6).toSet(), fixture.environment.loopPreparationSpecs
                .filter { it.loopRevision == prepared.cacheKey?.loopRevision }.map { it.laneIndex }.toSet())
            assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeApplied>()
                .any { it.sourceMode == EngineSourceMode.STEMS })
            assertEquals(NativeRuntimeSourceMode.STEMS, fixture.environment.snapshots.last().controls.sourceMode)
            fixture.environment.presentAudibleControl(owner,
                fixture.environment.snapshots.last().controls.revision, NativeRuntimeSourceMode.STEMS)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil {
                fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeApplied>()
                    .any { it.sourceMode == EngineSourceMode.STEMS }
            }
            assertEquals(owner, fixture.environment.lastSelected())
            assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
        }
    }

    @Test
    fun failedRequestedStemsHeadPreservesTheOriginalOwnerAndReportsTheMissingRange() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("failed-bounded-stems")
            .copy(startPositionMs = 1_000L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.presentLatestWithAudibleControl(msToFrameForTest(1_000L))
        val original = fixture.environment.decoders.single()
        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(1_000L),
            cacheStorageStatus = NativeCacheStorageStatus.NOT_REQUESTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable &&
                fixture.environment.openLoopPreparationCount == 0
        }
        val originalHead = fixture.environment.residentCacheRange
        assertNotNull(originalHead)
        fixture.environment.loopPreparationBeginStatus = NativeRuntimeLoopHeadBeginStatus.INSUFFICIENT_SPACE
        val stems = currentStems("failed-bounded-stems")
        assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
            sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
            stemRevision = "failed-bounded-parts",
            stemControls = stems.associate { it.id to EngineStemControl() }) })
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(1_000L),
            cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .lastOrNull()?.preparation?.phase == LoopPreparationPhase.FAILED
        }
        val result = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
        assertFalse(result.available)
        assertTrue(result.preparation.retryable)
        assertNotNull(result.preparation.reason)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(originalHead, fixture.environment.residentCacheRange)
        assertEquals(owner, fixture.environment.residentCacheOwner)
        assertFalse(original.cancelAttempted)
        assertTrue(original.requestedSeekTargets.isEmpty())
        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeApplied>()
            .any { it.sourceMode == EngineSourceMode.STEMS })
    }

    @Test
    fun frameZeroOwnerWaitsForCanonicalAdmissionWithoutDuplicateLoopHead() {
        for (storage in listOf(NativeCacheStorageStatus.NOT_REQUESTED, NativeCacheStorageStatus.ADMITTED)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.environment.exposePreparedLoopRange = true
            fixture.controller.select(request("framezero-awaiting-canonical", autoplay = false))
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.installPaused(owner)
            fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                cacheCanGrow = storage == NativeCacheStorageStatus.ADMITTED, cacheStorageStatus = storage)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runSteps(8)
            assertTrue(fixture.environment.loopPreparationSpecs.isEmpty())
            assertFalse(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
            assertEquals(owner, fixture.environment.lastSelected())
        }
    }

    @Test
    fun nonzeroOwnerKeepsWaitingForItsRetainedAdmittedFrameZeroProducer() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("retained-admitted-builder", autoplay = false))
        fixture.scheduler.runNext()
        val builderOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(builderOwner)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == builderOwner }
        val builderDecoder = fixture.environment.decoders.single()
        assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
            requestedPositionMs = 7_500L, seekRevision = previous.seekRevision + 1L) })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builderOwner }
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(7_500L),
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(8)
        assertEquals(owner, fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
        assertTrue(fixture.environment.loopPreparationSpecs.isEmpty())
        assertFalse(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertTrue(builderDecoder.requestedSeekTargets.isEmpty())
        assertFalse(builderDecoder.cancelAttempted)
        assertFalse(builderDecoder.released)
    }

    @Test
    fun retainedBuilderThatCannotGrowDoesNotStrandPausedMissingRange() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("retained-nongrowing-builder", autoplay = false))
        fixture.scheduler.runNext()
        val builderOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(builderOwner)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == builderOwner }
        val builderDecoder = fixture.environment.decoders.single()
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            requestedPositionMs = 7_500L, seekRevision = it.seekRevision + 1L) })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builderOwner }
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(7_500L),
            cacheCanGrow = false, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.controller.signalNativeProgress()
        var turns = 0
        while (fixture.environment.loopPreparationSpecs.isEmpty() &&
            fixture.scheduler.activeTaskCount() > 0 && turns++ < 32) fixture.scheduler.runNext()
        assertEquals("A retained decoder is not proof that the admitted cache can grow",
            1, fixture.environment.loopPreparationSpecs.size)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(owner, fixture.environment.latestPreparationIntent?.ownership)
        assertEquals(7_499_000L, fixture.environment.loopPreparationSpecs.single().initialPositionUs)
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(builderDecoder.requestedSeekTargets.isEmpty())
        assertFalse(builderDecoder.cancelAttempted)
        assertFalse(builderDecoder.released)
    }

    @Test
    fun originalFrameZeroIntentDoesNotPromiseMissingRequiredStemPrefixes() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("late-required-stem-prefix", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == owner }
        val stems = currentStems("late-required-stem-prefix")
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            sourceMode = EngineSourceMode.STEMS, stemLanes = stems, stemRevision = "late-prefix",
            stemControls = stems.associate { lane -> lane.id to EngineStemControl() }) })
        fixture.scheduler.runUntil { fixture.environment.decoders.size == 7 }
        val original = fixture.environment.decoders.single { it.laneIndex == 0 }
        original.setAcceptedCoverage(0L, msToFrameForTest(8_000L))
        fixture.environment.decoders.filter { it.laneIndex != 0 }.forEach {
            it.setAcceptedCoverage(480L, msToFrameForTest(8_000L))
        }
        fixture.environment.sealCurrentSongOriginalFor(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.controller.signalNativeProgress()
        var turns = 0
        while (fixture.environment.latestPreparationIntent?.requiredLaneMask != 0x7f &&
            fixture.scheduler.activeTaskCount() > 0 && turns++ < 32) fixture.scheduler.runNext()
        assertEquals("Original's frame-zero bank intent cannot fill missing Stems frames 0..479",
            0x7f, fixture.environment.latestPreparationIntent?.requiredLaneMask)
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        val prepared = requireNotNull(fixture.environment.latestPreparationIntent)
        assertEquals(owner, prepared.ownership)
        assertEquals(0L, prepared.cacheKey?.startTimelineFrame)
        assertEquals((0..6).toSet(), fixture.environment.loopPreparationSpecs
            .filter { it.loopRevision == prepared.cacheKey?.loopRevision }.map { it.laneIndex }.toSet())
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted || it.requestedSeekTargets.isNotEmpty() })
        assertEquals(0L, original.head().firstTimelineFrame48k)
    }

    @Test
    fun nonzeroOwnerUsesItsAlreadyCoveredAdmittedRangeWithoutDuplicatePreparation() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        fixture.controller.select(request("covered-admitted-prefix", autoplay = false)
            .copy(startPositionMs = 7_500L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L until msToFrameForTest(8_000L)
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(7_500L),
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertTrue(fixture.environment.loopPreparationSpecs.isEmpty())
        assertEquals(owner, fixture.environment.lastSelected())
    }

    @Test
    fun admittedPrefixRangePreparationFailurePreservesThePausedOwnerAndAcceptedPrefix() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.loopPreparationBeginStatus = NativeRuntimeLoopHeadBeginStatus.INSUFFICIENT_SPACE
        fixture.controller.select(request("admitted-prefix-range-failure", autoplay = false)
            .copy(startPositionMs = 7_500L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        val prefix = 0L until 6_144L
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = prefix
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(7_500L),
            cacheCanGrow = true, cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .lastOrNull()?.preparation?.phase == LoopPreparationPhase.FAILED
        }
        val preparation = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
        assertFalse(preparation.available)
        assertTrue(preparation.preparation.retryable)
        assertTrue(preparation.preparation.canConfigure)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(prefix, fixture.environment.residentCacheRange)
        assertEquals(owner, fixture.environment.residentCacheOwner)
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
        assertTrue(fixture.environment.snapshots.none { it.playing })
    }

    @Test
    fun rejectedWholeSongStoragePreparesTheRequestedLateRangeWithoutADurationCeiling() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        val token = fixture.controller.select(request("long-bounded-head", autoplay = false)
            .copy(durationMs = 900_000L, playbackEndMs = 900_000L, startPositionMs = 840_000L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.present(owner, msToFrameForTest(840_000L))
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(840_000L),
            cacheStorageStatus = NativeCacheStorageStatus.LIMIT_EXCEEDED)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(839_999_000L, fixture.environment.loopPreparationSpecs.first().initialPositionUs)
        val opens = fixture.environment.openedDecoderCount
        val managed = fixture.environment.managedLoopIntents.size
        assertTrue(fixture.setLoop(token, EngineLoopConfig(840_000L, 840_100L)))
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.directLoopPolicies.last().enabled)
        assertEquals(managed, fixture.environment.managedLoopIntents.size)
        assertEquals(opens, fixture.environment.openedDecoderCount)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(840_000L, 845_000L)))
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .lastOrNull()?.available == true && fixture.environment.loopPreparationSpecs.size > 1
        }
        val prepared = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
        assertEquals(LoopPreparationPhase.READY, prepared.preparation.phase)
        assertTrue(requireNotNull(fixture.environment.latestPreparationIntent).let {
            it.boundaryMode == NativeRuntimeManagedLoopBoundaryMode.RETAINED_WINDOW &&
                requireNotNull(it.cacheKey).startTimelineFrame == msToFrameForTest(840_000L) &&
                requireNotNull(it.cacheKey).endTimelineFrameExclusive >= msToFrameForTest(845_000L)
        })
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
    }

    @Test
    fun retainedWindowSpaceFailureIsExplicitAndRetryKeepsTheCommittedOwner() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        fixture.environment.loopPreparationBeginStatus = NativeRuntimeLoopHeadBeginStatus.INSUFFICIENT_SPACE
        fixture.controller.select(request("window-no-space", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheStorageStatus = NativeCacheStorageStatus.INSUFFICIENT_SPACE)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .lastOrNull()?.preparation?.phase == LoopPreparationPhase.FAILED
        }
        val failed = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
        assertTrue(failed.preparation.retryable)
        assertTrue(requireNotNull(failed.preparation.reason).contains("free storage"))
        assertEquals(owner, fixture.environment.lastSelected())
        fixture.environment.loopPreparationBeginStatus = NativeRuntimeLoopHeadBeginStatus.ACCEPTED
        assertTrue(fixture.controller.retryLoopPreparation(owner))
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
    }

    @Test
    fun boundedHeadFailureRetriesFreshRevisionWithoutRetiringThePlayingOwner() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.exposePreparedLoopRange = true
        fixture.environment.failLoopPreparationCount = 1
        val token = fixture.controller.select(request("retry-bounded-head", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L,
            cacheStorageStatus = NativeCacheStorageStatus.INSUFFICIENT_SPACE)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .lastOrNull()?.preparation?.phase == LoopPreparationPhase.FAILED
        }
        val failed = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
        assertTrue(failed.preparation.retryable)
        val failedRevision = fixture.environment.loopPreparationSpecs.last().loopRevision
        assertTrue(fixture.controller.retryLoopPreparation(owner))
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertTrue(fixture.environment.loopPreparationSpecs.last().loopRevision > failedRevision)
        assertEquals(owner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.decoders.none { it.cancelAttempted })
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 100L)))
        fixture.scheduler.runNext()
        assertEquals(owner, fixture.environment.directLoopPolicies.last().ownership)
    }

    @Test fun nativeLoopAllocationFailureSettlesOnlyItsExactPolicyAndRetryOwnsAFreshPolicy() {
        for (reason in listOf(NativeRuntimeLoopPreparationFailureReason.PIPELINE,
            NativeRuntimeLoopPreparationFailureReason.LANES)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("loop-allocation-$reason"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val owner = requireNotNull(fixture.environment.lastSelected())
            assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 100L)))
            fixture.scheduler.runSteps(2)
            val revision = fixture.environment.directLoopPolicies.last().revision
            fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                loopPreparationFailure = NativeRuntimeLoopPreparationFailure(owner, revision + 1L, reason))
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runSteps(2)
            assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .last().preparation.phase == LoopPreparationPhase.FAILED)
            fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                loopPreparationFailure = NativeRuntimeLoopPreparationFailure(owner, revision, reason))
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil {
                fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                    .lastOrNull()?.preparation?.phase == LoopPreparationPhase.FAILED
            }
            val failure = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>().last()
            assertTrue(failure.preparation.retryable)
            assertTrue(requireNotNull(failure.preparation.reason).contains(
                if (reason == NativeRuntimeLoopPreparationFailureReason.PIPELINE) "pipeline" else "lanes"))
            assertFalse(failure.available)
            assertEquals(owner, fixture.environment.lastSelected())
            assertTrue(fixture.controller.retryLoopPreparation(owner))
            fixture.scheduler.runUntil { fixture.environment.directLoopPolicies.last().revision > revision }
            fixture.environment.setNativeStemRenderReadiness(owner, 0L)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
            assertEquals(owner, fixture.environment.lastSelected())
        }
    }

    @Test
    fun stopRetiresBanksWhenLoopWorkerJoinsDuringTheSameActorTurn() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.holdLoopPreparation = true
        val song = request("stop-loop-join-race")
        fixture.controller.select(song)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            song.identity(), song.durationMs, fallbackNext = queueItem("stop-loop-next")))
        fixture.scheduler.runUntil {
            fixture.environment.openLoopPreparationCount == 1 &&
                fixture.environment.decoderLeaseSnapshot().candidateDecoderCount == 1
        }
        val leases = fixture.environment.decoderLeaseSnapshot()
        assertEquals(1, leases.activeDecoderCount)
        assertEquals(1, leases.candidateDecoderCount)
        assertEquals(1, leases.loopPreparationDecoderCount)
        assertEquals(3, leases.totalDecoderCount)
        // STOP first sees a live worker; ordinary managed-loop polling observes its join later in the turn.
        fixture.environment.loopPreparationPendingJoinPolls = 1
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runUntil(maximumSteps = 20) { stop.status() != null }

        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertEquals(0, fixture.environment.openLoopPreparationCount)
        assertEquals("STOP acknowledged while its bank decoders still owned leases", 0,
            fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertTrue(fixture.environment.decoders.all { it.released })
        assertTrue(fixture.environment.snapshots.last().banks.none { it.assigned })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun manualPrewarmClaimResumesAfterOutgoingLoopWorkerRetires() {
        for (autoplay in listOf(false, true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            fixture.environment.holdLoopPreparation = true
            val outgoingRequest = request("deferred-claim-outgoing-$autoplay")
            val incomingRequest = request("deferred-claim-incoming-$autoplay", autoplay)
            val outgoingToken = fixture.controller.select(outgoingRequest)
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
                outgoingRequest.identity(), outgoingRequest.durationMs,
                fallbackNext = queueItem(incomingRequest.songId)))
            fixture.scheduler.runUntil {
                fixture.environment.openLoopPreparationCount == 1 &&
                    fixture.environment.decoderLeaseSnapshot().candidateDecoderCount == 1
            }
            val prewarm = fixture.environment.decoders.single { it.spec.selectionToken != outgoingToken }
            fixture.environment.loopPreparationJoinWatchdog = true
            val admission = try {
                fixture.controller.selectAdmission(incomingRequest).also { admission ->
                    assertEquals(NativeEngineSelectionAdmissionKind.CLAIMED_PREWARM, admission.kind)
                    assertEquals(prewarm.spec.selectionToken, admission.token)
                    fixture.scheduler.runNext()
                    assertEquals(outgoing, fixture.environment.lastSelected())
                    assertTrue(fixture.environment.loopPreparationCancelRequestCount > 0)
                    assertFalse(prewarm.cancelAttempted)
                }
            } finally {
                fixture.environment.loopPreparationJoinWatchdog = false
            }

            // Retirement alone must resume the claimed request, without another user command.
            fixture.scheduler.runUntil(maximumSteps = 32) {
                fixture.environment.lastSelected()?.selectionToken == admission.token.value
            }
            val incoming = requireNotNull(fixture.environment.lastSelected())
            assertFalse(prewarm.cancelAttempted)
            assertEquals(2, fixture.environment.decoders.size)
            if (autoplay) fixture.environment.present(incoming, 0L)
            else fixture.environment.installPaused(incoming)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 32) {
                fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == incoming &&
                    fixture.environment.decoders.first { it.spec.selectionToken == outgoingToken }.released
            }
            assertEquals(admission.token, fixture.ownerPublications().last().selection.token)
            assertTrue(fixture.environment.manualSelectionGateAuthorizations.contains(incoming.generation))
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test
    fun completedManagedLoopStopAllowsTheNextManualOwnerToRestart() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("completed-loop-stop-seed"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val song = request("completed-loop-stop")
        val looping = fixture.controller.select(song)
        fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == looping.value }
        fixture.presentLatest()
        assertEquals(looping, fixture.ownerPublications().last().selection.token)
        assertEquals(1L, fixture.controller.diagnosticsSnapshot().manualSelectionSilenceEdgeCount)
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            song.identity(), song.durationMs))
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        val retainedIntent = fixture.environment.managedLoopIntents.last()
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runUntil(maximumSteps = 20) { stop.status() != null }
        assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
        assertEquals(NativeRuntimeStatus.STOPPED, fixture.environment.diagnostics().status)
        assertEquals(0, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertTrue("STOP must reproduce the retained enabled protocol",
            fixture.environment.managedLoopIntents.last().enabled)
        assertEquals(NativeRuntimePublishStatus.STOPPED, fixture.environment.publishManagedLoopIntent(
            NativeRuntimeManagedLoopIntent.disabled(retainedIntent.revision + 1L, retainedIntent.ownership)))

        val next = fixture.controller.select(request("after-completed-loop-stop"))
        fixture.scheduler.runNext()
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        fixture.scheduler.runUntil(maximumSteps = 20) {
            fixture.environment.lastSelected()?.selectionToken == next.value
        }
        assertEquals(NativeRuntimeStatus.RUNNING, fixture.environment.diagnostics().status)
        fixture.presentLatest()
        assertEquals(next, fixture.ownerPublications().last().selection.token)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun unexpectedRuntimeStopStillFailsClosedWhenRetiringALiveManagedLoop() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("unexpected-loop-stop-seed"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val song = request("unexpected-loop-stop")
        val looping = fixture.controller.select(song)
        fixture.scheduler.runUntil { fixture.environment.lastSelected()?.selectionToken == looping.value }
        fixture.presentLatest()
        assertEquals(looping, fixture.ownerPublications().last().selection.token)
        assertEquals(1L, fixture.controller.diagnosticsSnapshot().manualSelectionSilenceEdgeCount)
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            song.identity(), song.durationMs))
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        // Native stopped without the controller completing its owned STOP transaction.
        fixture.environment.stopRuntimeAndJoin()
        fixture.controller.select(request("after-unexpected-loop-stop"))
        fixture.scheduler.runNext()
        val fatal = fixture.events.filterIsInstance<NativeEngineControllerEvent.FatalRecoveryRequired>().single()
        assertTrue(fatal.diagnostic.contains("Could not retire the outgoing managed-loop intent"))
        assertEquals(2, fixture.ownerPublications().size)
    }

    @Test
    fun manualSelectionRetiresBothBanksAfterAsyncLoopCancellation() {
        for (supersedePending in listOf(false, true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            val song = request("manual-loop-cancel-$supersedePending")
            val token = fixture.controller.select(song)
            fixture.scheduler.runNext()
            fixture.presentLatest()
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(requestedPositionMs = 4_000L, seekRevision = previous.seekRevision + 1L)
            })
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = msToFrameForTest(4_000L))
            assertEquals(2, fixture.environment.snapshots.last().banks.count { it.assigned })
            val outgoingDecoders = fixture.environment.decoders.filter { !it.released }
            assertEquals(2, outgoingDecoders.size)
            fixture.environment.holdLoopPreparation = true
            fixture.environment.loopPreparationJoinWatchdog = true
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
                song.identity(), song.durationMs))
            fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }

            val pending = fixture.controller.select(request("after-async-cancel", autoplay = false))
            fixture.scheduler.runNext()
            assertEquals(1, fixture.environment.openLoopPreparationCount)
            assertEquals(1, fixture.environment.loopPreparationCancelRequestCount)
            assertFalse(fixture.environment.decoders.any {
                it.spec.decoderId.startsWith("${pending.value}-")
            })
            val latest = if (supersedePending) {
                fixture.controller.select(request("latest-after-async-cancel", autoplay = false)).also {
                    fixture.scheduler.runNext()
                }
            } else pending
            fixture.environment.loopPreparationJoinWatchdog = false
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 40) {
                fixture.environment.lastSelected()?.selectionToken == latest.value
            }
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.installPaused(owner)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 20) {
                fixture.ownerPublications().lastOrNull()?.selection?.token == latest
            }
            assertFalse(fixture.environment.snapshots.last().playing)
            assertTrue(outgoingDecoders.all { it.released })
            assertEquals(0, fixture.environment.openLoopPreparationCount)
            assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
            assertFalse(fixture.environment.managedLoopIntents.last().enabled)
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
            if (supersedePending) assertFalse(fixture.environment.decoders.any {
                it.spec.decoderId.startsWith("${pending.value}-")
            })
        }
    }

    @Test
    fun laterSelectionSurvivesStopWhileTheLoopWorkerIsRetiring() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.holdLoopPreparation = true
        val song = request("stop-loop-later-owner")
        fixture.controller.select(song)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            song.identity(), song.durationMs))
        fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }
        fixture.environment.loopPreparationJoinWatchdog = true
        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()
        assertNull(stop.status())

        val newer = fixture.controller.select(request("new-after-pending-loop-stop"))
        fixture.environment.loopPreparationJoinWatchdog = false
        fixture.environment.loopPreparationPendingJoinPolls = 1
        fixture.scheduler.runUntil(maximumSteps = 20) {
            fixture.environment.lastSelected()?.selectionToken == newer.value
        }

        assertEquals(NativeEngineStopStatus.SUPERSEDED, stop.status())
        assertEquals(0, fixture.environment.openLoopPreparationCount)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().totalDecoderCount)
        assertTrue(fixture.environment.decoders.filter { !it.released }
            .all { it.spec.location == "/new-after-pending-loop-stop.ogg" })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StopCompleted })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun pausedActiveLoopWithTemporarilyUnavailableProofSettlesFromNativeProgress() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("unavailable-loop-range", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = true)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L..95_999L
        fixture.environment.holdCacheRangeProof = true
        assertTrue(fixture.setLoop(token, EngineLoopConfig(1_000L, 2_000L, parkUntilRangeEntry = false)))
        fixture.scheduler.runNext()
        assertFalse(fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        val openedDecoders = fixture.environment.openedDecoderCount
        val readinessReads = fixture.environment.renderReadinessDiagnosticReads
        fixture.scheduler.runSteps(12)
        assertEquals(1, fixture.environment.quiesceOutputCallCount)
        assertEquals("Only the pending paused readiness observer remains", 1, fixture.scheduler.activeTaskCount())
        assertTrue(fixture.environment.renderReadinessDiagnosticReads > readinessReads)
        assertEquals(openedDecoders, fixture.environment.openedDecoderCount)
        assertTrue(fixture.environment.loopPreparationSpecs.isEmpty())
        val pending = fixture.controller.diagnosticsSnapshot()
        assertFalse(pending.controllerPublishedLoopAvailable)
        assertEquals(LoopPreparationPhase.PREPARING,
            fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
                .last { it.ownership == owner }.preparation.phase)
        assertEquals(48_000L to 49_024L, fixture.environment.cacheRangeQueries.last())
        fixture.environment.releaseCacheRangeProof()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertEquals(95_999L to 96_000L, fixture.environment.cacheRangeQueries.last())
        assertEquals(0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun pausedDecodedPrefixEnablesLoopWithoutWholeEofAndEvictionRefillOrStaleOwnerChangesProof() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("bounded-loop-prefix", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 0L..8_191L
        fixture.environment.setNativeStemRenderReadiness(owner, 0L, cacheCanGrow = true)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        assertFalse(fixture.environment.isCurrentSongCacheLaneMaskSealed(owner, 1))
        assertEquals(0L to 1_024L, fixture.environment.cacheRangeQueries.last())
        fixture.scheduler.runUntil(12) {
            fixture.environment.quiesceOutputCallCount == 1 && fixture.scheduler.activeTaskCount() == 0
        }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        fixture.environment.residentCacheRange = null
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { !fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        fixture.environment.residentCacheRange = 0L..8_191L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
        fixture.environment.residentCacheOwner = owner.copy(generation = owner.generation + 1L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { !fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable }
    }

    @Test
    fun loopStarterAtExactSongEndQueriesLastActualFrameInsteadOfAnEmptyRange() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("bounded-loop-eof", autoplay = false)
            .copy(durationMs = 1_000L, playbackEndMs = 1_000L, startPositionMs = 1_000L))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.decoders.single().setAcceptedCoverage(47_952L, 48_000L)
        fixture.environment.decoders.single().exactEndOfStreamFrame = 48_000L
        fixture.environment.installPaused(owner)
        fixture.environment.present(owner, 48_000L, extendDecoderCoverage = false)
        fixture.environment.residentCacheOwner = owner
        fixture.environment.residentCacheRange = 47_999L..47_999L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue("queries=${fixture.environment.cacheRangeQueries}, events=${fixture.events}",
            fixture.controller.diagnosticsSnapshot().controllerPublishedLoopAvailable)
        assertEquals(47_999L to 48_000L, fixture.environment.cacheRangeQueries.last())
    }

    @Test
    fun deferredSeekRetiresUnrelatedPrewarmAfterAsyncLoopWorkerRetirement() {
        for (playAfterSeek in listOf(false, true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            val song = request("seek-after-loop-retirement-$playAfterSeek")
            val token = fixture.controller.select(song)
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(
                queueItem("prewarmed-next-$playAfterSeek")))
            fixture.scheduler.runUntil { fixture.environment.decoders.size == 2 }
            val prewarm = fixture.environment.decoders.single { it.spec.selectionToken != token }
            fixture.environment.holdLoopPreparation = true
            fixture.environment.setNativeStemRenderReadiness(outgoing, 0L,
                cacheStorageStatus = NativeCacheStorageStatus.INSUFFICIENT_SPACE)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }
            fixture.environment.loopPreparationJoinWatchdog = true
            assertTrue(fixture.controller.updateDesired(token) { it.copy(
                requestedPositionMs = 2_000L, seekRevision = it.seekRevision + 1L,
                playWhenReady = false) })
            fixture.scheduler.runNext()
            if (playAfterSeek) {
                assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = true) })
                fixture.scheduler.runNext()
            }
            assertEquals(1, fixture.environment.loopPreparationCancelRequestCount)
            assertEquals(outgoing, fixture.environment.lastSelected())
            assertFalse(prewarm.cancelAttempted)
            // Worker retirement, without another user command, must unblock this same owner's seek.
            fixture.environment.loopPreparationJoinWatchdog = false
            var turns = 0
            while (fixture.environment.lastSelected() == outgoing &&
                fixture.scheduler.activeTaskCount() > 0 && turns++ < 32) fixture.scheduler.runNext()
            val sought = requireNotNull(fixture.environment.lastSelected())
            assertNotEquals("An unrelated prewarm cannot prevent retrying the command that retires it",
                outgoing, sought)
            assertTrue(prewarm.cancelAttempted && prewarm.released)
            val snapshot = fixture.environment.snapshots.last()
            assertEquals(msToFrameForTest(2_000L),
                snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
            assertEquals(playAfterSeek, snapshot.playing)
            if (playAfterSeek) fixture.environment.present(sought, msToFrameForTest(2_000L))
            else fixture.environment.installPaused(sought)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == sought }
            assertEquals(token.value, sought.selectionToken)
        }
    }

    @Test
    fun blockedLoopCloseDoesNotBlockControlsAndStopWaitsForRealRetirement() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val closeGate = CountDownLatch(1)
        fixture.environment.holdLoopPreparation = true
        fixture.environment.loopPreparationCloseGate = closeGate
        val song = request("slow-loop-close")
        val token = fixture.controller.select(song)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            song.identity(), song.durationMs))
        fixture.scheduler.runUntil(maximumSteps = 40) { fixture.environment.openLoopPreparationCount == 1 }
        var actor: Thread? = null
        try {
            fixture.controller.setAutomaticEndPlan(null)
            val done = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            actor = thread {
                try {
                    fixture.scheduler.runNext()
                    assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
                    fixture.scheduler.runNext()
                    assertTrue(fixture.controller.updateDesired(token) { it.copy(
                        requestedPositionMs = 5_000L, seekRevision = it.seekRevision + 1L) })
                    fixture.scheduler.runNext()
                    assertTrue(fixture.controller.updateDesired(token) { it.copy(
                        sourceMode = EngineSourceMode.STEMS,
                        stemLanes = currentStems("slow-loop-close"), stemRevision = "slow-close-v1") })
                    fixture.scheduler.runNext()
                    fixture.controller.select(request("new-during-retirement"))
                    fixture.scheduler.runNext()
                } catch (failure: Throwable) { failures += failure }
                finally { done.countDown() }
            }
            assertTrue(fixture.environment.loopPreparationCloseEntered.await(1, TimeUnit.SECONDS))
            assertTrue("Control actor blocked on codec close", done.await(500, TimeUnit.MILLISECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(1, fixture.environment.loopPreparationCancelRequestCount)
            assertEquals(1, fixture.environment.openLoopPreparationCount)
            val stop = fixture.controller.stopForTaskRemoval()
            fixture.scheduler.runNextImmediate()
            assertNull("STOP cannot acknowledge a live loop worker", stop.status())
            assertEquals(1, fixture.environment.openLoopPreparationCount)
            closeGate.countDown()
            fixture.scheduler.runUntil(maximumSteps = 100) { stop.status() != null }
            assertEquals(NativeEngineStopStatus.COMPLETED, stop.status())
            assertEquals(0, fixture.environment.openLoopPreparationCount)
        } finally {
            closeGate.countDown()
            actor?.join(1_000L)
            assertFalse(actor?.isAlive == true)
        }
    }

    @Test
    fun replacementSelectionRetiresTheOutgoingManagedRepeatBeforeOpeningTheShortOwner() {
        for (playing in listOf(false, true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            val original = request("repeat-outgoing", autoplay = playing)
            fixture.controller.select(original)
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
                original.identity(), original.durationMs))
            fixture.scheduler.runUntil(maximumSteps = 30) {
                fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
            }
            val outgoing = fixture.environment.managedLoopIntents.last()
            val before = fixture.environment.managedLoopIntents.size
            fixture.environment.holdLoopPreparation = true
            val replacement = fixture.controller.select(request("repeat-short", autoplay = playing)
                .copy(durationMs = 100L, playbackEndMs = 100L))
            fixture.scheduler.runNext()
            val retired = fixture.environment.managedLoopIntents.drop(before).first()
            assertFalse(retired.enabled)
            assertEquals(outgoing.ownership, retired.ownership)
            assertTrue(retired.revision > outgoing.revision)
            assertFalse(fixture.environment.managedLoopIntents.drop(before).any {
                it.enabled && it.ownership == outgoing.ownership
            })
            fixture.scheduler.runSteps(4)
            fixture.presentLatest()
            assertTrue(fixture.ownerPublications().any { it.selection.token == replacement })
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test
    fun successorMetadataRefreshReusesThePreparedDecoderAndRetainsArtwork() {
        val fixture = Fixture()
        fixture.controller.select(request("art-current"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val next = queueItem("art-next")
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(next))
        fixture.scheduler.runSteps(2)
        val prepared = fixture.environment.decoders.single { !it.released && it.spec.location == next.identity.originalLocation }
        val count = fixture.environment.decoders.size
        val ready = next.metadata.copy(revision = next.metadata.revision + 1L, payloadKey = "resident-artwork")
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(next.copy(metadata = ready)))
        fixture.scheduler.runSteps(2)
        assertEquals(count, fixture.environment.decoders.size)
        assertFalse(prepared.released)
        assertEquals(ready, fixture.controller.retainedMetadataForTest()[prepared.spec.selectionToken.value])
    }

    @Test
    fun rejectedDirectLoopPublicationDoesNotCommitKotlinLoopState() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-reject"))
        fixture.scheduler.runNext()
        val managedBefore = fixture.environment.managedLoopIntents.size
        fixture.environment.directLoopPolicyStatus = NativeRuntimePublishStatus.STALE_REVISION

        assertFalse(fixture.setLoop(token, EngineLoopConfig(500L, 2_000L)))
        assertEquals(1, fixture.environment.directLoopPolicyPublicationCount)
        assertEquals(managedBefore, fixture.environment.managedLoopIntents.size)

        fixture.environment.directLoopPolicyStatus = NativeRuntimePublishStatus.ACCEPTED
        assertTrue(fixture.setLoop(token, EngineLoopConfig(500L, 2_000L), 123_000L))
    }

    @Test
    fun capturedBCrossingCannotChangeItsLoopStart() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-crossing-owner"))
        fixture.scheduler.runNext()
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_003L, 3_007L)))
        val publications = fixture.environment.directLoopPolicyPublicationCount

        assertFalse(fixture.setLoop(
            token, EngineLoopConfig(2_000L, 2_500L),
            heldBGestureId = 9L, capturedBMs = 2_500L
        ))
        assertEquals(publications, fixture.environment.directLoopPolicyPublicationCount)
        assertTrue(fixture.setLoop(
            token, EngineLoopConfig(2_003L, 2_500L),
            heldBGestureId = 9L, capturedBMs = 2_500L
        ))
        // Ordinary A/B edits remain supported; they do not claim a B-only crossing.
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 2_500L)))
    }

    @Test
    fun heldBParkTransitionCannotInventAnotherCrossingAfterTheNativeCursorWrapped() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("held-b-wrapped-before-publication"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 134_367L)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_782L, 6_500L), heldBGestureId = 9L))
        assertTrue(fixture.environment.directLoopPolicies.last().boundaryArmedAtPublication)

        // The published UI cursor can still be 6009ms while the native owner is back at 2799ms.
        // Its outside-range classification carries no newly latched B crossing from the gesture.
        assertTrue(fixture.setLoop(token,
            EngineLoopConfig(2_782L, 5_806L, parkUntilRangeEntry = true), heldBGestureId = 9L))
        val held = fixture.environment.directLoopPolicies.last()
        assertFalse("A held gesture must not turn stale park status into an explicit capture",
            held.boundaryCrossedAtPublication)
        assertEquals(0L, held.capturedBTimelineFrameExclusive)
        assertFalse("Dormant publication must retain normal arming semantics", held.boundaryArmedAtPublication)

        assertTrue(fixture.setLoop(token,
            EngineLoopConfig(2_782L, 5_806L, parkUntilRangeEntry = true), heldBGestureId = 9L,
            capturedBMs = 5_806L, captureReferencePositionMs = 6_009L))
        val explicitHeld = fixture.environment.directLoopPolicies.last()
        assertTrue(explicitHeld.boundaryCrossedAtPublication)
        assertTrue(explicitHeld.boundaryArmedAtPublication)
        assertEquals(msToFrameForTest(5_806L), explicitHeld.capturedBTimelineFrameExclusive)
        assertEquals(msToFrameForTest(6_009L), explicitHeld.captureReferenceTimelineFrame)

        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_782L, 6_500L)))
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_782L, 5_806L, parkUntilRangeEntry = true)))
        val ordinary = fixture.environment.directLoopPolicies.last()
        assertTrue("Ordinary non-gesture edits retain their crossing inference", ordinary.boundaryCrossedAtPublication)
        assertEquals(msToFrameForTest(5_806L), ordinary.capturedBTimelineFrameExclusive)
    }

    @Test
    fun seekRebindsExplicitLoopDemandBeforeTheCacheReadinessPoll() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("direct-loop-seek-rebind"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.sealCurrentSongCacheFor(outgoing)

        assertTrue(fixture.setLoop(token, EngineLoopConfig(500L, 2_000L), 123_000L))
        val initialPublication = requireNotNull(
            fixture.environment.directLoopPolicies.lastOrNull()
        )
        assertEquals(outgoing, initialPublication.ownership)
        assertEquals(123_000L, initialPublication.inputEventAtMonotonicMicros)
        assertTrue(initialPublication.boundaryArmedAtPublication)
        assertFalse(initialPublication.boundaryCrossedAtPublication)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 1_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected() != outgoing
        }
        val rebound = requireNotNull(fixture.environment.lastSelected())
        assertEquals(rebound, fixture.environment.directLoopPolicies.last().ownership)
        val publicationCount = fixture.environment.directLoopPolicies.size
        fixture.environment.sealCurrentSongCacheFor(rebound)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(2)
        assertEquals(publicationCount, fixture.environment.directLoopPolicies.size)
        val reboundPublication = fixture.environment.directLoopPolicies.last()
        assertTrue(reboundPublication.enabled)
        assertEquals(msToFrameForTest(500L), reboundPublication.aTimelineFrame)
        assertEquals(msToFrameForTest(2_000L), reboundPublication.bTimelineFrameExclusive)
        assertEquals(initialPublication.revision + 1L, reboundPublication.revision)
        assertEquals(0L, reboundPublication.inputEventAtMonotonicMicros)
        assertTrue(reboundPublication.boundaryArmedAtPublication)
        assertFalse(reboundPublication.boundaryCrossedAtPublication)
    }

    @Test
    fun seekRebindKeepsDormantExplicitLoopUnarmed() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("dormant-loop-seek-rebind"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val outgoing = requireNotNull(fixture.environment.lastSelected())

        assertTrue(fixture.setLoop(
            token,
            EngineLoopConfig(500L, 2_000L, parkUntilRangeEntry = true)
        ))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 3_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.directLoopPolicies.lastOrNull()?.ownership != outgoing
        }

        assertFalse(fixture.environment.directLoopPolicies.last().boundaryArmedAtPublication)
    }

    @Test
    fun transportSeekDisarmsOutgoingLoopThenRebindsForTheExactTarget() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("direct-loop-exact-seek"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val firstOwner = requireNotNull(fixture.environment.lastSelected())

        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 4_000L)))
        assertTrue(fixture.reclassifyLoopForSeek(
            token,
            EngineLoopConfig(2_000L, 4_000L, parkUntilRangeEntry = true)
        ))
        val outgoingOutside = fixture.environment.directLoopPolicies.last()
        assertEquals(firstOwner, outgoingOutside.ownership)
        assertFalse(outgoingOutside.boundaryArmedAtPublication)
        assertFalse(outgoingOutside.boundaryCrossedAtPublication)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 6_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.directLoopPolicies.lastOrNull()?.ownership != firstOwner
        }
        val outsideOwner = fixture.environment.directLoopPolicies.last()
        assertFalse(outsideOwner.boundaryArmedAtPublication)

        assertTrue(fixture.reclassifyLoopForSeek(
            token,
            EngineLoopConfig(2_000L, 4_000L, parkUntilRangeEntry = false)
        ))
        val outgoingInside = fixture.environment.directLoopPolicies.last()
        assertEquals(outsideOwner.ownership, outgoingInside.ownership)
        assertFalse(outgoingInside.boundaryArmedAtPublication)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 2_500L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil {
            fixture.environment.directLoopPolicies.lastOrNull()?.ownership !=
                outgoingInside.ownership
        }
        assertTrue(fixture.environment.directLoopPolicies.last().boundaryArmedAtPublication)
    }

    @Test
    fun prewarmOlderThanRepeatedActiveSeeksIsRebasedBeforeClaimAndPresents() {
        val fixture = Fixture()
        val a = fixture.controller.select(request("a"), metadata("a", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)
        assertEquals(a, fixture.ownerPublications().single().selection.token)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("b"))
        )
        fixture.scheduler.runSteps(2)
        val prewarm = fixture.environment.decoders.single { it.spec.decoderId.startsWith("2-") }
        val prewarmEpoch = prewarm.spec.generationBase

        repeat(3) { index ->
            assertTrue(fixture.controller.updateDesired(a) { previous ->
                previous.copy(
                    requestedPositionMs = (index + 1L) * 1_000L,
                    seekRevision = previous.seekRevision + 1L
                )
            })
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = (index + 1L) * 48_000L)
        }
        val outputAfterSeeks = fixture.environment.outputGeneration
        assertTrue(outputAfterSeeks > prewarmEpoch)

        val b = fixture.controller.select(request("b"), metadata("b", 2L))
        fixture.scheduler.runNext()
        val bOwnership = requireNotNull(fixture.environment.lastSelected())

        assertEquals(2L, b.value)
        assertTrue(bOwnership.generation > outputAfterSeeks)
        assertTrue(prewarm.requestedSeekTargets.contains(bOwnership.generation))
        fixture.environment.present(bOwnership, timelineFrame = 0L)
        fixture.scheduler.runNext()
        assertEquals(b, fixture.ownerPublications().last().selection.token)
    }

    @Test
    fun explicitAbLoopKeepsAutomaticSuccessorPreparedForDormantPastEnd() {
        val fixture = Fixture()
        val current = fixture.controller.select(
            request("loop-vs-successor-a"),
            metadata("loop-vs-successor-a", 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("loop-vs-successor-b"))
        )
        fixture.scheduler.runSteps(2)
        val preparedSuccessor = requireNotNull(
            fixture.environment.snapshots.last().automaticSuccessor
        )

        val owner = requireNotNull(fixture.environment.lastSelected())
        val managedBefore = fixture.environment.managedLoopIntents.size
        val directBefore = fixture.environment.directLoopPolicyPublicationCount
        assertTrue(fixture.setLoop(current, EngineLoopConfig(500L, 1_500L)))
        val direct = fixture.environment.directLoopPolicies.last()
        assertTrue(direct.enabled)
        assertEquals(owner, direct.ownership)
        assertEquals(msToFrameForTest(500L), direct.aTimelineFrame)
        assertEquals(msToFrameForTest(1_500L), direct.bTimelineFrameExclusive)
        assertEquals(directBefore + 1, fixture.environment.directLoopPolicyPublicationCount)
        fixture.scheduler.runNext()
        assertEquals(
            preparedSuccessor,
            fixture.environment.snapshots.last().automaticSuccessor
        )
        assertTrue(fixture.environment.snapshots.last().playing)
        assertEquals(managedBefore, fixture.environment.managedLoopIntents.size)
    }

    @Test
    fun restartCurrentKeepsNextDormantBeforeExactCacheAndAbLoopOverridesIt() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val first = fixture.controller.select(request("repeat"), metadata("repeat", 1L))
        val stems = currentStems("repeat")
        assertTrue(fixture.controller.updateDesired(first) {
            it.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "repeat-stems",
                stemControls = stems.associate { lane -> lane.id to EngineStemControl() },
                tempo = 1.4f,
                semitoneShift = -3,
                metronome = EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 123f,
                    volume = .8f,
                    beatGridMs = longArrayOf(0L, 488L, 976L),
                    downbeats = booleanArrayOf(true, false, false)
                )
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 47_999L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        assertEquals(7, fixture.environment.decoders.count { !it.released })

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                request("repeat").identity(),
                request("repeat").durationMs,
                fallbackNext = queueItem("repeat-next")
            )
        )
        fixture.scheduler.runSteps(2)

        val hidden = fixture.environment.managedLoopIntents.last()
        assertFalse(hidden.enabled)
        val preparation = requireNotNull(fixture.environment.latestPreparationIntent)
        assertEquals(0L, requireNotNull(preparation.cacheKey).startTimelineFrame)
        assertEquals(
            NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES,
            requireNotNull(preparation.cacheKey).endTimelineFrameExclusive
        )
        assertEquals(NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS, preparation.boundaryMode)
        assertNull(fixture.environment.snapshots.last().automaticSuccessor)
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.role == NativeSongBankRole.CANDIDATE
        })
        assertEquals(1, fixture.environment.openLoopPreparationCount)
        assertEquals(9, fixture.environment.decoders.count { !it.released } +
            fixture.environment.openLoopPreparationCount)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(listOf(first), fixture.ownerPublications().map { it.selection.token })
        assertEquals(owner, fixture.environment.beatGridUpdates.last().ownership)
        assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID,
            fixture.environment.snapshots.last().controls.metronome.mode)
        assertEquals(1.4f, fixture.environment.snapshots.last().controls.tempo)
        assertEquals(-3f, fixture.environment.snapshots.last().controls.pitchSemitones)
        val preparationCount = fixture.environment.loopPreparationSpecs.size
        val directBefore = fixture.environment.directLoopPolicyPublicationCount
        assertTrue(fixture.setLoop(first, EngineLoopConfig(1_000L, 5_000L)))
        val explicit = fixture.environment.directLoopPolicies.last()
        assertTrue(explicit.enabled)
        assertEquals(msToFrameForTest(1_000L), explicit.aTimelineFrame)
        assertEquals(msToFrameForTest(5_000L), explicit.bTimelineFrameExclusive)
        assertEquals(directBefore + 1, fixture.environment.directLoopPolicyPublicationCount)
        fixture.scheduler.runNext()
        assertFalse(fixture.environment.managedLoopIntents.last().enabled)
        assertEquals(0, fixture.environment.openLoopPreparationCount)
        assertEquals(preparationCount, fixture.environment.loopPreparationSpecs.size)
        assertEquals(owner, fixture.environment.lastSelected())

        fixture.controller.setAutomaticEndPlan(null)
        assertTrue(fixture.setLoop(first, null))
        fixture.scheduler.runNext()
        assertFalse(fixture.environment.directLoopPolicies.last().enabled)
        assertEquals(directBefore + 2, fixture.environment.directLoopPolicyPublicationCount)
        assertFalse(fixture.environment.managedLoopIntents.last().enabled)
        assertNull(fixture.environment.snapshots.last().automaticSuccessor)
    }

    @Test
    fun sameIdentityNextOwnerCannotAllocateTheOldRepeatBank() {
        val fixture = Fixture()
        fixture.controller.select(request("same-owner"), metadata("same-owner", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("same-owner"))
        )
        fixture.scheduler.runSteps(3)

        assertEquals(1, fixture.environment.decoders.count { !it.released })
        assertTrue(fixture.environment.decoders.none {
            !it.released && it.role == NativeSongBankRole.CANDIDATE
        })
        assertTrue(fixture.environment.snapshots.none { it.automaticSuccessor != null })
        assertEquals(1, fixture.ownerPublications().size)
    }

    @Test
    fun restartCurrentRetainsAlreadyStagedNextOwnerButDisarmsItAfterRepeatInstall() {
        val fixture = Fixture()
        val currentRequest = request("restart-after-next")
        fixture.controller.select(currentRequest, metadata("restart-after-next", 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(queueItem("discarded-next"))
        )
        fixture.scheduler.runSteps(2)
        assertNotNull(fixture.environment.snapshots.last().automaticSuccessor)
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.role == NativeSongBankRole.CANDIDATE
        })

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                currentRequest.identity(),
                currentRequest.durationMs,
                fallbackNext = queueItem("discarded-next")
            )
        )
        fixture.scheduler.runSteps(5)

        assertNull(fixture.environment.snapshots.last().automaticSuccessor)
        assertTrue(fixture.environment.decoders.any {
            !it.released && it.role == NativeSongBankRole.CANDIDATE
        })
        assertTrue(fixture.environment.managedLoopIntents.last().enabled)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(1, fixture.ownerPublications().size)
    }

    @Test
    fun repeatOffRetiresUnfinishedHeadWithoutChangingThePlayingOwner() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val current = request("repeat-cancel")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = fixture.environment.lastSelected()
        val repeat = NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        fixture.controller.setAutomaticEndPlan(repeat)
        fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }
        val cancelledKey = requireNotNull(fixture.environment.latestPreparationIntent?.cacheKey)
        val opens = fixture.environment.loopPreparationSpecs.size

        fixture.controller.setAutomaticEndPlan(null)
        fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 0 }
        assertTrue(fixture.environment.loopPreparationCancelRequestCount > 0)
        assertTrue(fixture.environment.abortedLoopRevisions.contains(cancelledKey.loopRevision))
        assertEquals(opens, fixture.environment.loopPreparationSpecs.size)
        assertEquals(owner, fixture.environment.lastSelected())
        assertFalse(fixture.environment.managedLoopIntents.last().enabled)

        fixture.environment.holdLoopPreparation = false
        fixture.controller.setAutomaticEndPlan(repeat)
        fixture.scheduler.runUntil { fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true }
        assertNotEquals(cancelledKey, fixture.environment.managedLoopIntents.last().cacheKey)
        assertEquals(owner, fixture.environment.lastSelected())
    }

    @Test
    fun repeatOnDuringUnfinishedHeadRetirementResumesAfterTheWorkerJoins() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val current = request("repeat-resume-during-retirement")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = fixture.environment.lastSelected()
        val repeat = NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        fixture.controller.setAutomaticEndPlan(repeat)
        fixture.scheduler.runUntil { fixture.environment.openLoopPreparationCount == 1 }
        val cancelledKey = requireNotNull(fixture.environment.latestPreparationIntent?.cacheKey)
        val opens = fixture.environment.loopPreparationSpecs.size

        fixture.environment.loopPreparationJoinWatchdog = true
        try {
            fixture.controller.setAutomaticEndPlan(null)
            fixture.scheduler.runNext()
            assertEquals(1, fixture.environment.openLoopPreparationCount)
            assertTrue(fixture.environment.loopPreparationCancelRequestCount > 0)
            assertFalse(fixture.environment.managedLoopIntents.last().enabled)

            fixture.controller.setAutomaticEndPlan(repeat)
            fixture.scheduler.runSteps(3)
            assertEquals(opens, fixture.environment.loopPreparationSpecs.size)
            assertEquals(owner, fixture.environment.lastSelected())
            assertFalse(fixture.environment.managedLoopIntents.last().enabled)
        } finally {
            fixture.environment.loopPreparationJoinWatchdog = false
            fixture.environment.holdLoopPreparation = false
        }
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        assertTrue(fixture.environment.abortedLoopRevisions.contains(cancelledKey.loopRevision))
        assertNotEquals(cancelledKey, fixture.environment.managedLoopIntents.last().cacheKey)
        assertEquals(0, fixture.environment.openLoopPreparationCount)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(1, fixture.ownerPublications().size)
    }

    @Test
    fun repeatCacheSurvivesOffOnOwnerRebindAndDirectAbWithoutRedecode() {
        val fixture = Fixture()
        val current = request("repeat-cache-reuse")
        val token = fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                current.identity(),
                current.durationMs,
                fallbackNext = queueItem("repeat-cache-fallback")
            )
        )
        fixture.scheduler.runUntil {
            fixture.environment.latestPreparationIntent?.boundaryMode ==
                NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS &&
                fixture.environment.openLoopPreparationCount == 0 &&
                fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        val preparedRepeat = requireNotNull(fixture.environment.latestPreparationIntent)
        val repeatKey = requireNotNull(preparedRepeat.cacheKey)
        val repeatDecodeCount = fixture.environment.loopPreparationSpecs.count {
            it.loopRevision == repeatKey.loopRevision
        }
        val enabledFirst = fixture.environment.managedLoopIntents.last()
        assertEquals(repeatKey, enabledFirst.cacheKey)
        assertNotEquals(repeatKey.loopRevision, enabledFirst.revision)

        fixture.controller.setAutomaticEndPlan(null)
        fixture.scheduler.runNext()
        val disabled = fixture.environment.managedLoopIntents.last()
        assertFalse(disabled.enabled)
        assertTrue(disabled.revision > enabledFirst.revision)

        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                current.identity(),
                current.durationMs,
                fallbackNext = queueItem("repeat-cache-fallback")
            )
        )
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        val enabledAgain = fixture.environment.managedLoopIntents.last()
        assertEquals(repeatKey, enabledAgain.cacheKey)
        assertTrue(enabledAgain.revision > disabled.revision)
        assertEquals(repeatDecodeCount, fixture.environment.loopPreparationSpecs.count {
            it.loopRevision == repeatKey.loopRevision
        })

        val ownerBeforeRebind = requireNotNull(fixture.environment.lastSelected())
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 1_500L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val ownerAfterRebind = requireNotNull(fixture.environment.lastSelected())
        val rebound = fixture.environment.managedLoopIntents.last()
        assertTrue(ownerAfterRebind.generation > ownerBeforeRebind.generation)
        assertEquals(enabledAgain.revision, rebound.revision)
        assertEquals(ownerAfterRebind, rebound.ownership)
        assertEquals(repeatKey, rebound.cacheKey)
        assertEquals(repeatDecodeCount, fixture.environment.loopPreparationSpecs.count {
            it.loopRevision == repeatKey.loopRevision
        })
        fixture.presentLatest(timelineFrame = msToFrameForTest(1_500L))

        val managedBeforeAb = fixture.environment.managedLoopIntents.size
        val directBeforeAb = fixture.environment.directLoopPolicyPublicationCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(500L, 2_500L)))
        val direct = fixture.environment.directLoopPolicies.last()
        assertTrue(direct.enabled)
        assertEquals(ownerAfterRebind, direct.ownership)
        assertEquals(msToFrameForTest(500L), direct.aTimelineFrame)
        assertEquals(msToFrameForTest(2_500L), direct.bTimelineFrameExclusive)
        assertEquals(directBeforeAb + 1, fixture.environment.directLoopPolicyPublicationCount)
        fixture.scheduler.runNext()
        assertEquals(managedBeforeAb, fixture.environment.managedLoopIntents.size)
        assertEquals(rebound, fixture.environment.managedLoopIntents.last())
        assertEquals(repeatDecodeCount, fixture.environment.loopPreparationSpecs.count {
            it.loopRevision == repeatKey.loopRevision
        })
        assertFalse(fixture.environment.abortedLoopRevisions.contains(repeatKey.loopRevision))

        assertTrue(fixture.setLoop(token, null))
        fixture.scheduler.runNext()
        val restoredRepeat = fixture.environment.managedLoopIntents.last()
        assertFalse(fixture.environment.directLoopPolicies.last().enabled)
        assertEquals(directBeforeAb + 2, fixture.environment.directLoopPolicyPublicationCount)
        assertEquals(repeatKey, restoredRepeat.cacheKey)
        assertEquals(repeatDecodeCount, fixture.environment.loopPreparationSpecs.count {
            it.loopRevision == repeatKey.loopRevision
        })
    }

    @Test
    fun transientRepeatPrewarmFailureRetriesWithBoundedBackoffAndThenStops() {
        val fixture = Fixture()
        fixture.environment.failLoopPreparationCount = 1
        val current = request("repeat-prewarm-retry")
        fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatestWithAudibleControl()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        )

        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.events.any { event ->
                event is NativeEngineControllerEvent.Failure &&
                    event.operation == "repeat-prewarm"
            }
        }
        val firstFailureAtMs = fixture.scheduler.monotonicTimeMs()
        fixture.scheduler.runUntil(maximumSteps = 48) {
            fixture.environment.loopPreparationSpecs
                .map(NativeEngineLoopPreparationSpec::loopRevision)
                .distinct()
                .size >= 2 && fixture.environment.openLoopPreparationCount == 0
        }
        val revisions = fixture.environment.loopPreparationSpecs
            .map(NativeEngineLoopPreparationSpec::loopRevision)
            .distinct()
        assertEquals(2, revisions.size)
        assertTrue(revisions[1] > revisions[0])
        assertTrue(
            "Repeat retry ran before its 500ms backoff elapsed: ${fixture.scheduler.scheduledDelays}",
            fixture.scheduler.monotonicTimeMs() - firstFailureAtMs >= 500L
        )

        val settledPreparationCount = fixture.environment.loopPreparationSpecs.size
        fixture.scheduler.runSteps(4)
        assertEquals(settledPreparationCount, fixture.environment.loopPreparationSpecs.size)

        val permanentlyFailing = Fixture()
        permanentlyFailing.environment.failLoopPreparationCount = 10
        val permanentlyFailingSong = request("repeat-prewarm-bounded-failure")
        permanentlyFailing.controller.select(
            permanentlyFailingSong,
            metadata(permanentlyFailingSong.songId, 1L)
        )
        permanentlyFailing.scheduler.runNext()
        permanentlyFailing.presentLatestWithAudibleControl()
        permanentlyFailing.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                permanentlyFailingSong.identity(),
                permanentlyFailingSong.durationMs
            )
        )
        permanentlyFailing.scheduler.runUntil(maximumSteps = 192) {
            permanentlyFailing.environment.loopPreparationSpecs
                .map(NativeEngineLoopPreparationSpec::loopRevision)
                .distinct()
                .size == 3 && permanentlyFailing.environment.openLoopPreparationCount == 0
        }
        val boundedAttemptCount = permanentlyFailing.environment.loopPreparationSpecs.size
        permanentlyFailing.scheduler.runSteps(96)
        assertEquals(
            "A permanently corrupt proactive Repeat head exceeded its three-attempt ceiling",
            boundedAttemptCount,
            permanentlyFailing.environment.loopPreparationSpecs.size
        )
        assertEquals(
            3,
            permanentlyFailing.environment.loopPreparationSpecs
                .map(NativeEngineLoopPreparationSpec::loopRevision)
                .distinct()
                .size
        )
    }

    @Test
    fun latePhysicalStemFailureDowngradesRepeatToFreshOriginalOnlyCache() {
        val fixture = Fixture()
        val current = request("repeat-stem-loss")
        val token = fixture.controller.select(current, metadata(current.songId, 1L))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stems = currentStems("repeat-stem-loss")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "repeat-stem-loss-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        )
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil {
            fixture.environment.latestPreparationIntent?.requiredLaneMask ==
                0x7f &&
                fixture.environment.openLoopPreparationCount == 0
        }
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let {
                it.enabled && it.requiredLaneMask == 0x7f
            } == true
        }
        val allLaneKey = requireNotNull(
            fixture.environment.managedLoopIntents.last().cacheKey
        )
        fixture.environment.holdLoopPreparation = true
        fixture.environment.decoders.single {
            !it.released && it.laneIndex == 3
        }.failAsync("late physical stem loss")

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        val downgradedPreparation = requireNotNull(fixture.environment.latestPreparationIntent)
        val downgradedKey = requireNotNull(downgradedPreparation.cacheKey)
        assertEquals(NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
            downgradedPreparation.requiredLaneMask)
        assertTrue(downgradedKey.loopRevision > allLaneKey.loopRevision)
        assertFalse(fixture.environment.managedLoopIntents.last().enabled)
        assertEquals(1, fixture.environment.openLoopPreparationCount)

        fixture.environment.holdLoopPreparation = false
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let {
                it.enabled &&
                    it.boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS &&
                    it.requiredLaneMask == NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK &&
                    it.cacheKey == downgradedKey
            } == true
        }
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
    }

    @Test
    fun actorInvariantFailureSilencesStopsAndQuarantinesWithoutRepeatedFailureTurns() {
        val fixture = Fixture()
        fixture.environment.throwDiagnosticsOnce = true
        fixture.controller.select(request("actor-fatal"))

        fixture.scheduler.runNext()

        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.Failure })
        val silence = fixture.environment.operations.indexOfFirst {
            it.startsWith("immediate-silence:")
        }
        val stop = fixture.environment.operations.indexOf("stop-join-runtime")
        assertTrue(fixture.environment.operations.toString(), silence in 0 until stop)
        assertTrue(fixture.environment.decoders.all { it.cancelAttempted && it.released })
        assertEquals(0, fixture.scheduler.activeTaskCount())
    }

    @Test
    fun originalOnlyCacheMakesLoopAvailableWithoutAStemPackage() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.controller.select(request("original-loop-cache"))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(owner, 0L)
        fixture.scheduler.runNext()

        fixture.environment.sealCurrentSongOriginalFor(owner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue(fixture.events.filterIsInstance<
            NativeEngineControllerEvent.LoopAvailabilityChanged
        >().any { it.ownership == owner && it.available })
    }

    @Test
    fun armedLoopKeepsReadinessRequestsUntilTheExactOwnersHeadPublicationArrives() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("asynchronous-loop-head"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val first = requireNotNull(fixture.environment.lastSelected())
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 10_000L)))
        fixture.environment.fixedLoopHeadReady = false
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            requestedPositionMs = 5_000L, seekRevision = it.seekRevision + 1L) })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != first }
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(5_000L),
            cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        fixture.environment.present(owner, msToFrameForTest(5_000L))
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.controller.diagnosticsSnapshot().controllerObservedRenderReadiness?.ownership == owner &&
                fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == owner
        }
        // Owner publication can still invalidate the first observation. Let those initial
        // requests finish before testing a later asynchronous physical-head publication.
        fixture.scheduler.runSteps(32)
        val settled = fixture.controller.diagnosticsSnapshot()
        assertEquals(owner, settled.controllerPublicOwnership)
        val observed = requireNotNull(settled.controllerObservedRenderReadiness)
        assertEquals(owner, observed.ownership)
        assertEquals(fixture.environment.snapshots.last().controls.revision, observed.controlRevision)
        assertTrue(observed.currentSongCacheBoundToSelectedOwner)
        assertTrue(observed.currentSongCacheCompleteLaneMask and 1 != 0)
        assertFalse(settled.controllerPublishedLoopAvailable)
        val summary = requireNotNull(fixture.environment.controlProgress().readinessProgress)
        assertEquals(observed.sequence, summary.sequence)
        assertEquals(observed.assigned, summary.assigned)
        assertEquals(observed.ownership, summary.ownership)
        assertEquals(observed.controlRevision, summary.controlRevision)
        assertEquals(observed.currentSongCacheBoundToSelectedOwner, summary.cacheBound)
        assertEquals(observed.currentSongCacheCanGrow, summary.cacheCanGrow)
        assertEquals(observed.currentSongCacheCompleteLaneMask, summary.completeLaneMask)
        assertEquals(observed.cacheStorageStatus, summary.storageStatus)
        assertEquals(observed.requiredSongLaneMask, summary.requiredLaneMask)
        assertEquals(observed.fixedLoopHeadPrepared, summary.fixedLoopHeadPrepared)
        assertEquals(observed.directAtomicPolicyGeneration, summary.directAtomicPolicyGeneration)
        assertEquals(0L, summary.failureGeneration)
        fun available() = fixture.events.filterIsInstance<NativeEngineControllerEvent.LoopAvailabilityChanged>()
            .lastOrNull { it.ownership == owner }?.available == true
        val requests = fixture.environment.renderReadinessDiagnosticReads
        // The cheap summary cannot change until a request lets native publish its new snapshot.
        fixture.environment.onNextReadinessRequest = {
            fixture.environment.fixedLoopHeadReady = true
            fixture.environment.setNativeStemRenderReadiness(owner, msToFrameForTest(5_000L),
                cacheStorageStatus = NativeCacheStorageStatus.ADMITTED)
        }
        var turns = 0
        // A diagnostic snapshot itself requests readiness; use published events while waiting.
        while (!available() && fixture.scheduler.activeTaskCount() > 0 && turns++ < 32)
            fixture.scheduler.runNext()
        assertTrue("Unresolved explicit-loop readiness must keep requesting the exact owner's snapshot",
            fixture.environment.renderReadinessDiagnosticReads > requests)
        assertTrue(available())
        assertEquals(owner, fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
        assertTrue(fixture.environment.loopPreparationSpecs.isEmpty())
        fixture.scheduler.runSteps(32)
        val settledRequests = fixture.environment.renderReadinessDiagnosticReads
        fixture.scheduler.runSteps(32)
        assertEquals("A ready loop must not retain a periodic detail sampler", settledRequests,
            fixture.environment.renderReadinessDiagnosticReads)
    }

    @Test
    fun seekWithAnArmedLoopWaitsForPreparedHeadBeforeEnablingLoopEditing() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("loop-head-after-seek"))
        fixture.scheduler.runNext()
        val first = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(first, 0L)
        fixture.scheduler.runNext()
        assertTrue(fixture.setLoop(token, EngineLoopConfig(2_000L, 10_000L)))
        fixture.environment.fixedLoopHeadReady = false
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 5_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != first }
        val sought = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(sought, msToFrameForTest(5_000L))
        fixture.scheduler.runNext()
        fun availability() = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.LoopAvailabilityChanged
        >().last { it.ownership == sought }.available
        assertFalse("A sealed cache is insufficient while the new owner's A head is preparing", availability())
        fixture.environment.fixedLoopHeadReady = true
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(availability())
        // Normal cycle preparation must not flicker an already enabled control.
        fixture.environment.fixedLoopHeadReady = false
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(availability())
    }

    @Test
    fun exactConsumerCacheProofTransfersStemAndLoopAuthorityThenReleasesBuilder() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val stems = currentStems("sealed-cache")
        val token = fixture.controller.select(request("sealed-cache"))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "sealed-cache-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val builderOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(builderOwner, 0L)
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
        val builderDecoders = fixture.environment.decoders.filter { !it.released }

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 10_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builderOwner }
        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(consumerOwner, msToFrameForTest(10_000L))
        fixture.scheduler.runNext()
        val directBeforeSeal = fixture.environment.directLoopPolicyPublicationCount
        val checksBeforeUnsealedMove = fixture.environment.currentSongCacheSealCheckCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 4_000L)))
        assertEquals(directBeforeSeal + 1, fixture.environment.directLoopPolicyPublicationCount)
        assertEquals(
            checksBeforeUnsealedMove,
            fixture.environment.currentSongCacheSealCheckCount
        )
        fixture.environment.sealCurrentSongCacheFor(consumerOwner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { builderDecoders.all { it.released } }

        assertEquals(
            listOf(false, true),
            fixture.events.filterIsInstance<
                NativeEngineControllerEvent.LoopAvailabilityChanged
            >().filter { it.ownership == consumerOwner }.map { it.available }
        )
        assertEquals(NativeRuntimeSourceMode.STEMS,
            fixture.environment.snapshots.last().controls.sourceMode)
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "sealed-cache-v1"
        })
        val managedBefore = fixture.environment.managedLoopIntents.size
        val preparationsBefore = fixture.environment.openLoopPreparationCount
        val directBefore = fixture.environment.directLoopPolicyPublicationCount
        val sealChecksBefore = fixture.environment.currentSongCacheSealCheckCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 4_000L)))
        assertEquals(sealChecksBefore, fixture.environment.currentSongCacheSealCheckCount)
        fixture.scheduler.runSteps(2)
        val sealChecksBeforeSecondMove = fixture.environment.currentSongCacheSealCheckCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 5_000L)))
        assertEquals(
            sealChecksBeforeSecondMove,
            fixture.environment.currentSongCacheSealCheckCount
        )
        fixture.scheduler.runSteps(2)
        val sealChecksBeforeThirdMove = fixture.environment.currentSongCacheSealCheckCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(1_000L, 5_000L)))
        assertEquals(
            sealChecksBeforeThirdMove,
            fixture.environment.currentSongCacheSealCheckCount
        )
        fixture.scheduler.runSteps(2)
        assertEquals(directBefore + 3, fixture.environment.directLoopPolicyPublicationCount)
        assertEquals(managedBefore, fixture.environment.managedLoopIntents.size)
        assertEquals(preparationsBefore, fixture.environment.openLoopPreparationCount)
        fixture.environment.unsealCurrentSongCacheFor(consumerOwner)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(tempo = 1.01f)
        })
        fixture.scheduler.runSteps(2)
        assertFalse(fixture.environment.managedLoopIntents.drop(managedBefore).any { it.enabled })
        assertEquals(preparationsBefore, fixture.environment.openLoopPreparationCount)
        // Losing physical cache proof can replace the owner; its retained demand is rebound
        // immediately, while native still fences physical PCM on that owner's sealed cache.
        assertEquals(fixture.environment.lastSelected(), fixture.environment.directLoopPolicies.last().ownership)
        val beforeClear = fixture.environment.directLoopPolicyPublicationCount
        assertTrue(fixture.setLoop(token, null))
        fixture.scheduler.runSteps(2)
        assertEquals(beforeClear + 1, fixture.environment.directLoopPolicyPublicationCount)
        assertFalse(fixture.environment.managedLoopIntents.drop(managedBefore).any { it.enabled })
        val checksBeforeReenable = fixture.environment.currentSongCacheSealCheckCount
        assertTrue(fixture.setLoop(token, EngineLoopConfig(1_000L, 5_000L)))
        assertEquals(
            checksBeforeReenable,
            fixture.environment.currentSongCacheSealCheckCount
        )
        assertEquals(beforeClear + 2, fixture.environment.directLoopPolicyPublicationCount)
    }

    @Test
    fun preparedStemRepeatSurvivesSeekReadinessFallback() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val current = request("repeat-stem-seek")
        val token = fixture.controller.select(current)
        fixture.scheduler.runNext()
        fixture.presentLatestWithAudibleControl()
        val stems = currentStems("repeat-stem-seek")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "repeat-stem-seek-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        )
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let {
                it.enabled && it.requiredLaneMask == 0x7f
            } == true
        }
        val prepared = requireNotNull(fixture.environment.managedLoopIntents.last().cacheKey)
        fixture.environment.sealCurrentSongCacheFor(requireNotNull(fixture.environment.lastSelected()))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 160_000L, seekRevision = previous.seekRevision + 1L,
                tempo = 1.35f)
        })
        fixture.scheduler.runSteps(3)
        fixture.presentLatest()
        fixture.scheduler.runSteps(3)
        val rebound = fixture.environment.managedLoopIntents.last()
        assertTrue("Seek readiness fallback disabled the prepared stem Repeat", rebound.enabled)
        assertEquals(prepared, rebound.cacheKey)
        assertEquals(0x7f, rebound.requiredLaneMask)
    }

    @Test
    fun preparedRepeatSurvivesEarlySeekWhileTheSongCacheBuilds() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val current = request("repeat-early-seek")
        val token = fixture.controller.select(current)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        )
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.enabled == true
        }
        val prepared = requireNotNull(fixture.environment.managedLoopIntents.last().cacheKey)
        val builder = requireNotNull(fixture.environment.lastSelected())
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 160_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builder }
        val consumer = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(consumer, msToFrameForTest(160_000L))
        fixture.scheduler.runSteps(3)
        val rebound = fixture.environment.managedLoopIntents.last()
        assertTrue("Independent Repeat head was disabled by the unfinished song cache", rebound.enabled)
        assertEquals(consumer, rebound.ownership)
        assertEquals(prepared, rebound.cacheKey)
        assertFalse(fixture.environment.abortedLoopRevisions.contains(prepared.loopRevision))
    }

    @Test
    fun nearEndNextOwnerPreemptsUnsealedBuilderButRepeatOneRetainsItAtPlaybackTempo() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val current = request("builder-deadline")
        val stems = currentStems("builder-deadline")
        val token = fixture.controller.select(current)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = stems,
                stemRevision = "builder-deadline-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val builderOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(builderOwner, 0L)
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
        val builderDecoders = fixture.environment.decoders.filter { !it.released }

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 160_000L,
                seekRevision = previous.seekRevision + 1L,
                tempo = 2f
            )
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != builderOwner }
        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(consumerOwner, msToFrameForTest(160_000L))
        fixture.scheduler.runNext()

        val next = queueItem("deadline-next")
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                current.identity(),
                current.durationMs,
                fallbackNext = next
            )
        )
        fixture.scheduler.runSteps(2)
        assertTrue(builderDecoders.none { it.released })
        assertFalse(fixture.environment.decoders.any {
            !it.released && it.spec.location == next.identity.originalLocation
        })

        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(next))
        try {
            fixture.scheduler.runUntil(maximumSteps = 16) {
                fixture.environment.decoders.any {
                    !it.released && it.spec.location == next.identity.originalLocation
                }
            }
        } catch (failure: AssertionError) {
            throw AssertionError("Next-owner builder preemption failed; events=${fixture.events}; " +
                "last=${fixture.environment.snapshots.lastOrNull()}", failure)
        }
        assertTrue(builderDecoders.all { it.released })
        assertEquals(9, fixture.environment.openedDecoderCount)
        assertTrue(fixture.environment.maximumOpenDecoderCount <= NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS)
    }

    @Test
    fun selectableRepeatIncludesExactlyItsOriginalAndDeclaredParts() {
        val fixture = Fixture()
        val selection = request("dynamic-repeat")
        val token = fixture.controller.select(selection)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stems = listOf("lead-vocals", "backing-vocals", "remaining").map { role ->
            EngineStemLane(role, role, "/repeat-$role.flac", "sha-$role")
        }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemRevision = "dynamic-repeat", stemControls = stems.associate { it.id to EngineStemControl() })
        })
        fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            selection.identity(), selection.durationMs))
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let {
                it.enabled && it.requiredLaneMask == 0b1111
            } == true
        }
        assertEquals(0b1111, requireNotNull(fixture.environment.latestPreparationIntent).requiredLaneMask)
        assertEquals(setOf(0, 1, 2, 3), fixture.environment.loopPreparationSpecs.map { it.laneIndex }.toSet())
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun streamedRepeatDefersFutureIngressUntilInPlaceSeekOwnerIsPresented() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        fixture.environment.holdLoopPreparation = true
        // A nonzero cold start exercises streaming without the frame-zero cache builder.
        val selection = request("repeat-seek-ingress-fence").copy(startPositionMs = 1_000L)
        val token = fixture.controller.select(selection)
        val stems = currentStems("repeat-seek-ingress-fence")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "repeat-seek-ingress-fence",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(selection.identity(), selection.durationMs)
        )
        fixture.environment.holdLoopPreparation = false
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let { intent ->
                intent.enabled && intent.boundaryMode ==
                    NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS &&
                    intent.requiredLaneMask == 0x7f
            } == true
        }

        val ownerBeforeSeek = requireNotNull(fixture.environment.lastSelected())
        val targetSeekMs = 17_000L
        val targetSeekFrame = msToFrameForTest(targetSeekMs)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = targetSeekMs,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()

        val reboundOwner = requireNotNull(fixture.environment.lastSelected())
        assertEquals(ownerBeforeSeek.selectionToken, reboundOwner.selectionToken)
        assertTrue(reboundOwner.generation > ownerBeforeSeek.generation)
        // Repeat may also prewarm its next Original on the other bank. Only this owner
        // participates in the seek/ingress fence under test.
        val liveDecoders = fixture.environment.decoders.filter {
            !it.released && it.bank == fixture.environment.snapshots.last().selectedBank
        }
        assertEquals(7, liveDecoders.size)
        assertTrue(liveDecoders.all { decoder ->
            decoder.requestedSeekTargets.lastOrNull() == reboundOwner.generation &&
                decoder.head().firstTimelineFrame48k == targetSeekFrame - 48L
        })
        assertEquals(
            targetSeekFrame,
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness?.anchorFrame48k
        )
        val seekCountsBeforeLoopRequest = liveDecoders.associateWith {
            it.requestedSeekTargets.size
        }

        val loopIntent = fixture.environment.managedLoopIntents.last()
        val loopBodyFrame = NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES
        val futureIngressEpoch = reboundOwner.generation + 50L
        fixture.environment.loopIngressRequest = NativeRuntimeLoopIngressRequest(
            sequence = 92L,
            loopRevision = loopIntent.revision,
            targetCycle = 1L,
            ownership = reboundOwner,
            ingressEpoch = futureIngressEpoch,
            targetTimelineFrame = loopBodyFrame,
            requiredLaneMask = loopIntent.requiredLaneMask,
            activation = NativeRuntimeLoopCycleActivation.COLD_AT_LOOP_START,
            requiredEndTimelineFrameExclusive = loopBodyFrame + 4_800L
        )

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue("Loop ingress must remain queued while the seek owner is unpresented",
            fixture.environment.managedLoopReady.isEmpty())
        assertTrue(liveDecoders.all { decoder ->
            decoder.requestedSeekTargets.size == seekCountsBeforeLoopRequest.getValue(decoder) &&
                decoder.head().firstTimelineFrame48k == targetSeekFrame - 48L
        })
        assertEquals(
            targetSeekFrame,
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness?.anchorFrame48k
        )

        fixture.presentLatest(timelineFrame = targetSeekFrame)

        val ready = fixture.environment.managedLoopReady.single()
        assertEquals(reboundOwner, ready.ownership)
        assertEquals(futureIngressEpoch, ready.ingressEpoch)
        assertEquals(loopBodyFrame - 48L, ready.firstTimelineFrame)
        assertEquals(loopBodyFrame, fixture.environment.loopIngressRequest?.targetTimelineFrame)
        assertTrue(liveDecoders.all { decoder ->
            decoder.requestedSeekTargets.size ==
                seekCountsBeforeLoopRequest.getValue(decoder) + 1 &&
                decoder.requestedSeekTargets.last() == futureIngressEpoch &&
                decoder.head().firstTimelineFrame48k == loopBodyFrame - 48L
        })
    }

    @Test
    fun streamedRepeatPublishesEosPaddingOnlyForTheExactFutureStemEpoch() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val token = fixture.controller.select(request("streamed-eos-stem"))
        val stems = currentStems("streamed-eos-stem")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "streamed-eos-stem",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                request("streamed-eos-stem").identity(),
                request("streamed-eos-stem").durationMs
            )
        )
        fixture.environment.holdLoopPreparation = false
        fixture.scheduler.runUntil {
            fixture.environment.managedLoopIntents.lastOrNull()?.let {
                it.enabled &&
                    it.boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
            } == true
        }
        val intent = fixture.environment.managedLoopIntents.last()
        assertEquals(0x7f, intent.requiredLaneMask)
        val owner = intent.ownership
        val targetFrame = NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES
        val ingressEpoch = owner.generation + 50L
        fixture.environment.loopIngressRequest = NativeRuntimeLoopIngressRequest(
            sequence = 91L,
            loopRevision = intent.revision,
            targetCycle = 1L,
            ownership = owner,
            ingressEpoch = ingressEpoch,
            targetTimelineFrame = targetFrame,
            requiredLaneMask = intent.requiredLaneMask,
            activation = NativeRuntimeLoopCycleActivation.WARM_CURRENT_PASS,
            requiredEndTimelineFrameExclusive = targetFrame + 4_800L
        )
        val shortStem = fixture.environment.decoders.single { it.laneIndex == 1 }
        shortStem.exposePcmHead = false
        shortStem.exactEndOfStreamFrame = 0L
        shortStem.reportedNativeGenerationOverride = owner.generation

        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.environment.managedLoopReady.isEmpty())

        shortStem.reportedNativeGenerationOverride = null
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val ready = fixture.environment.managedLoopReady.single()
        assertEquals(ingressEpoch, ready.ingressEpoch)
        assertEquals(intent.requiredLaneMask, ready.readyLaneMask)
        assertEquals(1 shl 1, ready.eosPaddedLaneMask)
        assertEquals(targetFrame - 48L, ready.firstTimelineFrame)
        assertEquals(targetFrame, fixture.environment.loopIngressRequest?.targetTimelineFrame)
        assertTrue(ready.endTimelineFrameExclusive >= targetFrame + 4_800L)
    }

    @Test
    fun taskRemovalStuckLoopPreparationCompletesFatalAndRetainsNinthLease() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val current = request("stuck-loop-preparation")
        fixture.controller.select(current)
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(current.identity(), current.durationMs)
        )
        fixture.scheduler.runUntil {
            fixture.environment.openLoopPreparationCount == 1
        }
        fixture.environment.loopPreparationJoinWatchdog = true
        fixture.environment.operations.clear()

        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNextImmediate()

        assertNull(stop.status())
        fixture.scheduler.advanceTimeBy(NATIVE_DECODER_JOIN_WATCHDOG_MS)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
        assertEquals(1, fixture.environment.openLoopPreparationCount)
        assertEquals(1, fixture.environment.decoderLeaseSnapshot().loopPreparationDecoderCount)
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertFalse(fixture.environment.operations.contains("close-environment"))
        assertFalse(fixture.environment.operations.any { it.startsWith("retire-output:") })
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired &&
                it.diagnostic.contains("loop-head MediaCodec worker")
        })
    }

    @Test
    fun residentSeekRetainsDecoderPrefixWithoutMovingTheNativeRequestedCursor() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("resident-prefix", autoplay = false)
            .copy(startPositionMs = 1_000L))
        fixture.scheduler.runNext()
        val original = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(original)
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 1_010L, seekRevision = it.seekRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != original }
        val sought = requireNotNull(fixture.environment.lastSelected())
        val decoder = fixture.environment.decoders.single { !it.released }
        assertEquals(msToFrameForTest(1_009L), decoder.head().firstTimelineFrame48k)
        val snapshot = fixture.environment.snapshots.last()
        assertEquals(msToFrameForTest(1_010L), snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
        fixture.environment.installPaused(sought)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == sought }
        assertFalse(fixture.environment.snapshots.last().playing)
    }

    @Test
    fun futureSeekEndRemainsDeliverableAfterItsPresentationBecomesPublic() {
        for (paused in listOf(false, true)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("future-seek-end-$paused"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val oldOwner = requireNotNull(fixture.environment.lastSelected())
            assertTrue(fixture.controller.updateDesired(token) {
                it.copy(requestedPositionMs = 179_900L, seekRevision = it.seekRevision + 1L,
                    playWhenReady = !paused)
            })
            fixture.scheduler.runUntil { fixture.environment.lastSelected() != oldOwner }
            val newOwner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.end(newOwner)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.AudibleEnd })
            fixture.environment.present(newOwner, msToFrameForTest(179_999L))
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runSteps(3)
            assertEquals(1, fixture.events.filterIsInstance<NativeEngineControllerEvent.AudibleEnd>()
                .count { it.ownership == newOwner })
        }
    }

    @Test
    fun clearedLoopFromBeforeSeekCannotSuppressTheNewOwnersAudibleEnd() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("repeat-after-clear-seek"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val oldOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(fixture.setLoop(token, null))
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(requestedPositionMs = 5_000L, seekRevision = it.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val newOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(newOwner.generation > oldOwner.generation)
        fixture.environment.end(newOwner, directLoopPolicyRevision = 0L)
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.AudibleEnd && it.ownership == newOwner
        })
    }

    @Test
    fun audibleEndFromOlderDirectLoopPolicyIsDiscarded() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stale-loop-end"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())

        fixture.environment.end(owner)
        assertTrue(fixture.setLoop(token, EngineLoopConfig(0L, 4_000L)))
        fixture.scheduler.runNext()

        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.AudibleEnd })

        assertTrue(fixture.setLoop(token, null))
        val disabledRevision = fixture.environment.directLoopPolicies.last().revision
        fixture.environment.end(owner, disabledRevision)
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.AudibleEnd })
    }
}
