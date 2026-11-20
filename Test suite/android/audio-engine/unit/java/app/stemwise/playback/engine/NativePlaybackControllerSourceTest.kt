package app.stemwise.playback.engine

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
internal class NativePlaybackControllerSourceTest : NativePlaybackControllerTestSupport() {
    @Test
    fun recoveryPublicationAndFailedReplacementRespectLateEofAndExactRetirement() {
        for (replacementFails in listOf(false, true)) {
            for (eofBeforeReplacementOutcome in listOf(false, true)) {
                lateinit var fixture: Fixture
                val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
                val adapter = NativeAudioPlaybackSessionAdapter(
                    engineFactory = { listener ->
                        fixture = Fixture(onEvent = listener::onEvent)
                        ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
                    }, effectListener = NativeAudioPlaybackSessionEffectListener(effects::add))
                val song = request("ordered-original-recovery")
                val token = requireNotNull(adapter.trySelect(song,
                    PlaybackPublicationMetadata(song.songId, song.audioRevision, 1L)))
                fixture.scheduler.runNext()
                fixture.presentLatest(timelineFrame = 24_000L)
                val outgoing = requireNotNull(adapter.snapshot().publication.committed).owner.ownership
                val original = fixture.environment.decoders.single()
                val lease = requireNotNull(adapter.acquireCommandLease())
                fixture.environment.heldUnassignmentAcknowledgementBanks += original.bank
                original.failAsync("Original lost before its queued final callback")
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil(maximumSteps = 20) {
                    fixture.environment.lastSelected()?.let { it != outgoing } == true
                }
                val replacement = requireNotNull(fixture.environment.lastSelected())
                val decoder = fixture.environment.decoders.single { it !== original && !it.released }
                org.junit.Assert.assertSame("Recovery retains the exact Original source admission",
                    original.spec.sourceAdmission, decoder.spec.sourceAdmission)
                assertEquals(token.value, replacement.selectionToken)
                assertTrue(replacement.generation > outgoing.generation)
                assertEquals("Prepared replacement cannot claim presentation", outgoing,
                    adapter.snapshot().publication.committed?.owner?.ownership)
                assertFalse(original.cancelAttempted)
                if (eofBeforeReplacementOutcome) {
                    fixture.environment.end(outgoing)
                    fixture.controller.signalNativeProgress()
                    fixture.scheduler.runNext()
                }
                if (replacementFails) {
                    fixture.environment.heldUnassignmentAcknowledgementBanks += decoder.bank
                    decoder.failAsync("Replacement failed before its first presented frame")
                    fixture.controller.signalNativeProgress()
                    fixture.scheduler.runUntil(maximumSteps = 20) {
                        effects.any { it is NativeAudioPlaybackSessionEffect.OwnerTerminated }
                    }
                    assertNull(adapter.snapshot().publication.committed)
                    assertNull(adapter.snapshot().latestRequest)
                    assertFalse(adapter.commandLeaseIsCurrent(lease))
                    assertEquals(listOf(outgoing), effects
                        .filterIsInstance<NativeAudioPlaybackSessionEffect.OwnerTerminated>()
                        .map { it.ownership })
                    assertFalse(decoder.cancelAttempted)
                } else {
                    fixture.presentLatest(timelineFrame = 24_000L)
                    assertEquals(replacement, adapter.snapshot().publication.committed?.owner?.ownership)
                    assertTrue(adapter.commandLeaseIsCurrent(lease))
                }
                if (!eofBeforeReplacementOutcome) {
                    fixture.environment.end(outgoing)
                    fixture.controller.signalNativeProgress()
                    fixture.scheduler.runNext()
                }
                assertEquals(if (replacementFails) null else replacement,
                    adapter.snapshot().publication.committed?.owner?.ownership)
                assertFalse("Native unassignment acknowledgement still owns outgoing lanes", original.released)
                original.joinWatchdogExpired = true
                fixture.environment.heldUnassignmentAcknowledgementBanks.clear()
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil(maximumSteps = 20) { original.cancelAttempted }
                assertFalse("Cancellation is not worker retirement", original.released)
                assertEquals(if (replacementFails) null else replacement,
                    adapter.snapshot().publication.committed?.owner?.ownership)
                original.joinWatchdogExpired = false
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil(maximumSteps = 20) { original.released && (!replacementFails || decoder.released) }
                assertEquals(if (replacementFails) null else replacement,
                    adapter.snapshot().publication.committed?.owner?.ownership)
                assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
            }
        }
    }

    @Test
    fun terminalCommittedOwnerRetiresAdapterLeaseAndSameSongRetryGetsFreshToken() {
        lateinit var fixture: Fixture
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val adapter = NativeAudioPlaybackSessionAdapter(
            engineFactory = { listener ->
                fixture = Fixture(onEvent = listener::onEvent)
                ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
            },
            effectListener = NativeAudioPlaybackSessionEffectListener(effects::add)
        )
        val song = request("terminal-retry")
        val metadata = PlaybackPublicationMetadata(song.songId, song.audioRevision, 1L, "Song")
        val token = requireNotNull(adapter.trySelect(song, metadata))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(adapter.snapshot().publication.committed).owner.ownership
        val lease = requireNotNull(adapter.acquireCommandLease())
        fixture.environment.failNextOpenAtLane(0)
        fixture.environment.decoders.single { !it.released }.failAsync("Original lost")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 20) {
            effects.any { it is NativeAudioPlaybackSessionEffect.OwnerTerminated }
        }
        assertEquals(listOf(owner), effects.filterIsInstance<NativeAudioPlaybackSessionEffect.OwnerTerminated>()
            .map { it.ownership })
        assertNull(adapter.snapshot().publication.committed)
        assertNull(adapter.snapshot().latestRequest)
        assertFalse(adapter.commandLeaseIsCurrent(lease))
        assertNull(adapter.acquireCommandLease())
        val retry = requireNotNull(adapter.trySelect(song, metadata))
        assertNotEquals(token, retry)
        fixture.scheduler.runUntil(maximumSteps = 20) {
            fixture.environment.lastSelected()?.selectionToken == retry.value
        }
        fixture.presentLatest()
        assertEquals(retry, adapter.snapshot().publication.committed?.selection?.token)
    }

    @Test
    fun zeroPcmEosRetiresThePublishedOwnerAcrossSeekAndRecovery() {
        for (scenario in listOf("seek", "early-seek", "recovery")) {
            lateinit var fixture: Fixture
            val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
            val adapter = NativeAudioPlaybackSessionAdapter(
                engineFactory = { listener ->
                    fixture = Fixture(currentSongCacheSealedByDefault = scenario != "early-seek",
                        onEvent = listener::onEvent)
                    ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
                }, effectListener = NativeAudioPlaybackSessionEffectListener(effects::add))
            val song = request("zero-pcm-$scenario")
            val publication = PlaybackPublicationMetadata(song.songId, song.audioRevision, 1L, "Song")
            val token = requireNotNull(adapter.trySelect(song, publication))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 24_000L)
            val owner = requireNotNull(adapter.snapshot().publication.committed).owner.ownership
            val lease = requireNotNull(adapter.acquireCommandLease())
            val original = fixture.environment.decoders.single { !it.released }
            val empty = if (scenario == "recovery") {
                fixture.environment.newDecodersExposePcmHead = false
                original.failAsync("Original decoder lost its source")
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil(maximumSteps = 20) {
                    fixture.environment.decoders.any { it !== original && !it.released }
                }
                fixture.environment.decoders.single { it !== original && !it.released }
            } else if (scenario == "early-seek") {
                fixture.environment.newDecodersExposePcmHead = false
                assertNotNull(adapter.seekToIfCurrent(lease, 2_000L))
                fixture.scheduler.runUntil(maximumSteps = 20) {
                    fixture.environment.decoders.any { it !== original && !it.released }
                }
                fixture.environment.decoders.single { it !== original && !it.released }
            } else {
                original.exposePcmHead = false
                assertNotNull(adapter.seekToIfCurrent(lease, 2_000L))
                original
            }
            empty.exactEndOfStreamFrame = 0L
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 20) { empty.cancelAttempted }
            assertEquals(listOf(owner), effects.filterIsInstance<NativeAudioPlaybackSessionEffect.OwnerTerminated>()
                .map { it.ownership })
            assertNull(adapter.snapshot().publication.committed)
            assertNull(adapter.snapshot().publication.pendingSelection)
            assertNull(adapter.snapshot().latestRequest)
            assertFalse(adapter.commandLeaseIsCurrent(lease))
            assertNotEquals(token, requireNotNull(adapter.trySelect(song, publication)))
        }
    }

    @Test
    fun emptyRetainedCacheBuilderCannotTerminateTheNewerPublishedSeekOwner() {
        lateinit var fixture: Fixture
        val adapter = NativeAudioPlaybackSessionAdapter(engineFactory = { listener ->
            fixture = Fixture(currentSongCacheSealedByDefault = false, onEvent = listener::onEvent)
            ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
        })
        val song = request("old-empty-builder")
        val token = requireNotNull(adapter.trySelect(song,
            PlaybackPublicationMetadata(song.songId, song.audioRevision, 1L, "Song")))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val old = fixture.environment.decoders.single()
        val lease = requireNotNull(adapter.acquireCommandLease())
        assertNotNull(adapter.seekToIfCurrent(lease, 2_000L))
        fixture.scheduler.runUntil(maximumSteps = 20) { fixture.environment.lastSelected()?.generation != old.spec.generationBase }
        fixture.presentLatest(timelineFrame = 96_000L)
        val current = requireNotNull(adapter.snapshot().publication.committed).owner.ownership
        assertNotEquals(old.spec.generationBase, current.generation)
        old.exposePcmHead = false
        old.exactEndOfStreamFrame = 0L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 20) { old.cancelAttempted }
        assertEquals(current, adapter.snapshot().publication.committed?.owner?.ownership)
        assertEquals(token, adapter.acquireCommandLease()?.token)
    }

    @Test
    fun emptyPrewarmPreservesHealthyOwnerAndFailedManualSelectionStaysInvalidated() {
        for (prewarm in listOf(false, true)) {
            lateinit var fixture: Fixture
            val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
            val adapter = NativeAudioPlaybackSessionAdapter(engineFactory = { listener ->
                fixture = Fixture(onEvent = listener::onEvent)
                ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
            }, effectListener = NativeAudioPlaybackSessionEffectListener(effects::add))
            val a = request("healthy-outgoing-$prewarm")
            val token = requireNotNull(adapter.trySelect(a, PlaybackPublicationMetadata(a.songId, a.audioRevision, 1L, "A")))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val owner = requireNotNull(adapter.snapshot().publication.committed).owner.ownership
            fixture.environment.newDecodersExposePcmHead = false
            if (prewarm) {
                fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(queueItem("empty-prewarm")))
            } else {
                val b = request("empty-pending")
                assertNotNull(adapter.trySelect(b, PlaybackPublicationMetadata(b.songId, b.audioRevision, 1L, "B")))
            }
            fixture.scheduler.runUntil(maximumSteps = 20) { fixture.environment.decoders.size == 2 }
            val empty = fixture.environment.decoders.last()
            empty.exactEndOfStreamFrame = 0L
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 20) { empty.cancelAttempted }
            if (prewarm) {
                assertEquals(owner, adapter.snapshot().publication.committed?.owner?.ownership)
                assertEquals(token, adapter.snapshot().latestRequest?.selection?.token)
                assertEquals(token, adapter.acquireCommandLease()?.token)
                assertNotNull(adapter.setPlayWhenReadyIfCurrent(token, false))
                fixture.scheduler.runNext()
                assertFalse(fixture.environment.snapshots.last().playing)
            } else {
                // Explicit selection is an intentional silence/invalidation boundary. An empty
                // replacement must not invent retirement evidence for the already-invalidated A.
                assertNull(adapter.snapshot().publication.committed)
                assertNull(adapter.acquireCommandLease())
            }
            assertTrue(effects.none { it is NativeAudioPlaybackSessionEffect.OwnerTerminated })
        }
    }

    @Test
    fun emptyOutgoingOriginalCannotDiscardANewerSelection() {
        lateinit var fixture: Fixture
        val adapter = NativeAudioPlaybackSessionAdapter(engineFactory = { listener ->
            fixture = Fixture(onEvent = listener::onEvent)
            ControllerNativeAudioPlaybackSessionEngine(fixture.controller)
        })
        val a = request("empty-outgoing")
        adapter.trySelect(a, PlaybackPublicationMetadata(a.songId, a.audioRevision, 1L, "A"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val old = fixture.environment.decoders.single()
        val b = request("newer-b")
        val next = requireNotNull(adapter.trySelect(b, PlaybackPublicationMetadata(b.songId, b.audioRevision, 1L, "B")))
        old.exposePcmHead = false
        old.exactEndOfStreamFrame = 0L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 20) { old.cancelAttempted }
        assertEquals(next, adapter.snapshot().latestRequest?.selection?.token)
        assertNull(adapter.snapshot().publication.committed)
        fixture.scheduler.runUntil(maximumSteps = 20) { fixture.environment.lastSelected()?.selectionToken == next.value }
        fixture.presentLatest()
        assertEquals(next, adapter.snapshot().publication.committed?.selection?.token)
    }

    @Test
    fun sourceReadinessEventsStayWithTheSourceIntentAcrossTempoAndMixEdits() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("source-revision", autoplay = false))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("source-revision")
        assertTrue(fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
            stemLanes = stems, stemRevision = "source-revision-v1",
            stemControls = stems.associate { lane -> lane.id to EngineStemControl() }) })
        fixture.scheduler.runNext()
        val pending = fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeNotReady>()
            .single { it.requestedMode == EngineSourceMode.STEMS }
        assertTrue(fixture.controller.updateDesired(token) { it.copy(tempo = 1.25f) })
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesired(token) { it.copy(
            stemControls = it.stemControls.mapValues { (_, control) -> control.copy(gain = 0.8f) }) })
        fixture.scheduler.runNext()
        assertEquals(listOf(pending), fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeNotReady>()
            .filter { it.requestedMode == EngineSourceMode.STEMS })
        fixture.environment.setNativeStemRenderReadiness(owner, 0L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible &&
            it.sourceMode == EngineSourceMode.STEMS && it.desiredRevision == pending.desiredRevision })
    }

    @Test
    fun foregroundOriginalFailureRetiresPrewarmAndRecoversOrSettlesTerminally() {
        for (reopenFails in listOf(false, true)) {
            val fixture = Fixture()
            fixture.controller.select(request("failed-original"), metadata("failed-original", 1L))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 24_000L)
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            fixture.controller.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(queueItem("prewarm-b")))
            fixture.scheduler.runSteps(2)
            val failed = fixture.environment.decoders.single { !it.released && it.spec.location == "/failed-original.ogg" }
            val prewarm = fixture.environment.decoders.single { !it.released && it.spec.location == "/prewarm-b.ogg" }
            if (reopenFails) fixture.environment.failNextOpenAtLane(0)
            failed.failAsync("foreground Original failed while spare was prewarming")
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 20) {
                fixture.events.any { it is NativeEngineControllerEvent.OwnerTerminated } ||
                    fixture.environment.decoders.any { !it.released && it !== failed && it.spec.location == "/failed-original.ogg" }
            }
            assertTrue(prewarm.cancelAttempted)
            assertTrue(prewarm.released)
            if (reopenFails) {
                assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
                assertEquals(listOf(outgoing), fixture.events.filterIsInstance<NativeEngineControllerEvent.OwnerTerminated>()
                    .map { it.ownership })
                fixture.scheduler.runUntil(maximumSteps = 20) { failed.released }
                assertTrue(failed.released)
                fixture.controller.select(request("healthy-next"), metadata("healthy-next", 2L))
                fixture.scheduler.runSteps(4)
                fixture.presentLatest()
                assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.OwnerPublished>()
                    .any { it.publication.selection.songId == "healthy-next" })
            } else {
                fixture.scheduler.runUntil(maximumSteps = 12) {
                    fixture.environment.lastSelected()?.let { it.selectionToken == outgoing.selectionToken && it.generation > outgoing.generation } == true
                }
                fixture.presentLatest(timelineFrame = 24_000L)
                assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
                fixture.scheduler.runUntil(maximumSteps = 12) { failed.released }
            }
        }
    }

    @Test
    fun optionalCacheStorageShortageKeepsStreamingSourcesAvailable() {
        for (status in listOf(NativeCacheStorageStatus.INSUFFICIENT_SPACE, NativeCacheStorageStatus.IO_FAILURE)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            val token = fixture.controller.select(request("storage-shortage"))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val owner = requireNotNull(fixture.environment.lastSelected())
            val original = fixture.environment.decoders.single()
            fixture.environment.setNativeStemRenderReadiness(owner, 0L,
                cacheStorageStatus = status)
            val stems = currentStems("storage")
            assertTrue(fixture.controller.updateDesired(token) { previous -> previous.copy(
                sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemControls = stems.associate { it.id to EngineStemControl() }) })
            fixture.scheduler.runNext()
            assertEquals(7, fixture.environment.decoders.count { !it.released })
            assertFalse(original.released)
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageFailed })
            assertEquals(status,
                fixture.environment.renderReadinessDiagnostics()?.cacheStorageStatus)
        }
    }

    @Test
    fun explicitPlayingSourceChangeClaimsTheOutgoingGraphEnd() {
        assertTrue(
            explicitSourceChangePendingAtEnd(
                playWhenReady = true,
                sourceIntentRevision = 8L,
                lastAppliedSourceRevision = 5L,
                desiredSourceMode = EngineSourceMode.STEMS,
                lastAppliedSourceMode = EngineSourceMode.ORIGINAL
            )
        )
        assertFalse(
            explicitSourceChangePendingAtEnd(
                playWhenReady = false,
                sourceIntentRevision = 8L,
                lastAppliedSourceRevision = 5L,
                desiredSourceMode = EngineSourceMode.STEMS,
                lastAppliedSourceMode = EngineSourceMode.ORIGINAL
            )
        )
        assertFalse(
            explicitSourceChangePendingAtEnd(
                playWhenReady = true,
                sourceIntentRevision = 1L,
                lastAppliedSourceRevision = 0L,
                desiredSourceMode = EngineSourceMode.ORIGINAL,
                lastAppliedSourceMode = null
            )
        )
    }

    @Test
    fun quiescedOutputObservesColdStemAndCacheReadinessThenReturnsToIdleWithoutAudibleEvidence() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("quiesced-cold-stems", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.environment.sealCurrentSongOriginalFor(owner)
        fixture.scheduler.runUntil(maximumSteps = 12) {
            fixture.environment.quiesceOutputCallCount == 1 && fixture.scheduler.activeTaskCount() == 0
        }
        assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
        val stems = currentStems("quiesced-cold-stems")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemRevision = "quiesced-cold-r1",
                stemControls = stems.associate { it.id to EngineStemControl() })
        })
        fixture.scheduler.runNext()
        assertTrue("Pending cold stems lost their readiness observer", fixture.scheduler.activeTaskCount() > 0)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible })
        fixture.environment.setNativeStemRenderReadiness(owner, renderStartTimelineFrame = 0L)
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.events.any { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible }
        }
        assertEquals(true, fixture.controller.diagnosticsSnapshot().controllerCacheReadinessPending)
        assertTrue("Ready render data cannot substitute for the pending stem cache",
            fixture.scheduler.activeTaskCount() > 0)
        fixture.environment.sealCurrentSongCacheFor(owner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 8) { fixture.scheduler.activeTaskCount() == 0 }
        assertEquals(NativeRuntimeSourceMode.STEMS, fixture.environment.snapshots.last().controls.sourceMode)
        assertEquals(1, fixture.events.count { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })
        assertFalse(fixture.environment.snapshots.last().playing)
        assertEquals("Ready paused source must not spin waiting for an inaudible callback", 0,
            fixture.scheduler.activeTaskCount())
    }

    @Test
    fun exactSafeSourceApplicationUsesTenMillisecondPollingOnlyUntilCallbackProof() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("source-ack-poll"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())

        // Prove the initial ORIGINAL publication so this test starts from ordinary playing
        // cadence rather than conflating the initial owner callback with the STEMS edge below.
        val originalSnapshot = fixture.environment.snapshots.last()
        fixture.environment.presentAudibleControl(
            owner,
            originalSnapshot.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil(maximumSteps = 4) {
            fixture.scheduler.scheduledDelays.lastOrNull() == 20L
        }

        val stems = currentStems("source-ack-poll")
        val coldPollStart = fixture.scheduler.scheduledDelays.size
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "source-ack-poll-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()

        assertEquals(
            NativeRuntimeSourceMode.ORIGINAL,
            fixture.environment.snapshots.last().controls.sourceMode
        )
        assertTrue(
            "Cold/not-ready STEMS entered the 1ms source-ack loop: " +
                fixture.scheduler.scheduledDelays.drop(coldPollStart),
            fixture.scheduler.scheduledDelays.drop(coldPollStart).none { it == 1L }
        )

        fixture.environment.setNativeStemRenderReadiness(
            owner = owner,
            renderStartTimelineFrame = 240_000L
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val stemSnapshot = fixture.environment.snapshots.last()

        assertEquals(NativeRuntimeSourceMode.STEMS, stemSnapshot.controls.sourceMode)
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.STEMS
        })
        assertEquals(10L, fixture.scheduler.scheduledDelays.last())

        fixture.environment.presentAudibleControl(
            owner,
            stemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.STEMS
        })
        assertNotEquals(10L, fixture.scheduler.scheduledDelays.last())
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.scheduler.scheduledDelays.lastOrNull() == 20L
        }
        assertEquals(20L, fixture.scheduler.scheduledDelays.last())
    }

    @Test
    fun inPlaceSeekRequiresFreshSourceApplicationEvidence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("source-seek-proof"))
        val stems = currentStems("source-seek-proof")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "source-seek-proof-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        fixture.scheduler.runUntil {
            fixture.environment.snapshots.last().controls.sourceMode ==
                NativeRuntimeSourceMode.STEMS
        }
        val firstOwner = requireNotNull(fixture.environment.lastSelected())
        val firstControls = fixture.environment.snapshots.last().controls
        fixture.environment.presentAudibleControl(
            firstOwner,
            firstControls.revision,
            NativeRuntimeSourceMode.STEMS
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.STEMS
        })

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 2_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val reboundOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(reboundOwner.generation > firstOwner.generation)
        val reboundControls = fixture.environment.snapshots.last().controls
        assertEquals(NativeRuntimeSourceMode.STEMS, reboundControls.sourceMode)
        // One physical callback may publish presentation and applied-control proof together.
        // Queue its watcher wake before the actor observes either fact to reproduce that ordering.
        fixture.environment.present(reboundOwner, timelineFrame = 96_000L)
        fixture.environment.presentAudibleControl(
            reboundOwner,
            reboundControls.revision,
            NativeRuntimeSourceMode.STEMS
        )
        fixture.environment.emitAudibleControlProgress()
        fixture.scheduler.runNext()

        assertEquals(2, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.STEMS
        })

        // A coherent ownership tuple can advance while the presentation sequence is unchanged.
        // Its cached source proof must be applied after the fallback ownership-rebase path too.
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 3_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val nextOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.presentWithoutSequenceAdvance(nextOwner, timelineFrame = 144_000L)
        fixture.environment.presentAudibleControl(
            nextOwner,
            fixture.environment.snapshots.last().controls.revision,
            NativeRuntimeSourceMode.STEMS
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(3, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.STEMS
        })

        // The event-only watcher can precede ownership progress by an entire actor turn.
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 4_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val delayedOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.presentAudibleControl(
            delayedOwner,
            fixture.environment.snapshots.last().controls.revision,
            NativeRuntimeSourceMode.STEMS
        )
        fixture.environment.emitAudibleControlProgress()
        fixture.scheduler.runNext()
        assertEquals(3, fixture.events.count { it is NativeEngineControllerEvent.SourceModeApplied })
        fixture.environment.presentWithoutSequenceAdvance(delayedOwner, timelineFrame = 192_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(4, fixture.events.count { it is NativeEngineControllerEvent.SourceModeApplied })
    }

    @Test
    fun renderWorkerEvidenceWakePublishesExactSourceWithoutWaitingForPollDeadline() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("source-event-wake"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val owner = requireNotNull(fixture.environment.lastSelected())
        val snapshot = fixture.environment.snapshots.last()

        fixture.environment.presentAudibleControl(
            owner = owner,
            controlRevision = snapshot.controls.revision,
            sourceMode = NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.environment.emitAudibleControlProgress()
        fixture.scheduler.runNext()

        val applied = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.SourceModeApplied
        >().filter { it.token == token }
        assertEquals(1, applied.size)
        assertEquals(EngineSourceMode.ORIGINAL, applied.single().sourceMode)
        assertTrue(fixture.scheduler.cancelledDelayedTasks >= 1)
    }

    @Test
    fun manualInvalidationAndDesiredMailboxDrainAreAtomicAgainstANewerSelector() {
        val fixture = Fixture()
        fixture.environment.holdLoopPreparation = true
        val outgoing = request("atomic-outgoing")
        fixture.controller.select(
            outgoing,
            metadata(outgoing.songId, 1L)
        )
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.controller.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.RestartCurrent(outgoing.identity(), outgoing.durationMs)
        )
        fixture.scheduler.runUntil {
            fixture.environment.openLoopPreparationCount == 1
        }

        fixture.controller.select(request("atomic-b"))
        var latest: EngineSelectionToken? = null
        fixture.environment.onAbortLoopPreparationOnce = {
            latest = fixture.controller.select(request("atomic-c"))
        }
        fixture.scheduler.runNext()
        val latestToken = requireNotNull(latest)
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.environment.lastSelected()?.selectionToken == latestToken.value
        }
        // Keep polling after C first appears: the old split intake selected C for one turn, then a
        // delayed invalidation retired it permanently on the next turn.
        fixture.scheduler.runSteps(3)

        assertEquals(latestToken.value, fixture.environment.lastSelected()?.selectionToken)
        assertFalse(fixture.events.filterIsInstance<NativeEngineControllerEvent.SelectionFailed>()
            .any { it.token == latestToken })
    }

    @Test
    fun acceptedInPlaceSeekCannotBeVetoedByAnOlderUnselectedStemReplacement() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("seek-with-stem-replacement"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 24_000L)

        val v1 = currentStems("seek-with-stem-replacement-v1")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = v1,
                stemRevision = "seek-with-stem-replacement-v1",
                stemControls = v1.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val outgoing = requireNotNull(fixture.environment.lastSelected())

        fixture.environment.manualSelectionGateAuthorizations.clear()
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 7_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val rebased = requireNotNull(fixture.environment.lastSelected())
        assertTrue(rebased.generation > outgoing.generation)

        val v2 = currentStems("seek-with-stem-replacement-v2")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = v2,
                stemRevision = "seek-with-stem-replacement-v2",
                stemControls = v2.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        assertEquals(rebased, fixture.environment.lastSelected())
        assertEquals(1, fixture.environment.decoders.count {
            !it.released && it.role == NativeSongBankRole.CANDIDATE && it.laneIndex == 0
        })

        fixture.environment.acceptNonEmptyWithoutPresentation(rebased)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(
            listOf(rebased.generation),
            fixture.environment.manualSelectionGateAuthorizations
        )
    }

    @Test
    fun manualSwipePreviewUsesAndClaimsTheSingleExistingCandidate() {
        val fixture = Fixture()
        val current = fixture.controller.select(request("manual-preview-a"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        fixture.controller.setManualPrewarm(
            NativeEngineManualPrewarmRequest(current, queueItem("manual-preview-b"))
        )
        fixture.scheduler.runSteps(2)
        val previewDecoder = fixture.environment.decoders.single {
            it.spec.decoderId.startsWith("2-")
        }

        val admission = fixture.controller.selectAdmission(
            request("manual-preview-b"),
            metadata("manual-preview-b", 2L)
        )
        assertEquals(NativeEngineSelectionAdmissionKind.CLAIMED_PREWARM, admission.kind)
        assertEquals(2L, admission.token.value)
        fixture.scheduler.runNext()

        assertTrue(fixture.environment.decoders.contains(previewDecoder))
        assertEquals(2, fixture.environment.decoders.size)
    }

    @Test
    fun rapidPreviewRetainsNextOriginalAgainstLatestPendingOwner() {
        val fixture = Fixture()
        fixture.controller.select(request("rapid-preview-a"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val pending = fixture.controller.select(request("rapid-preview-b"))
        fixture.scheduler.runNext()
        fixture.controller.setManualPrewarm(
            NativeEngineManualPrewarmRequest(pending, queueItem("rapid-preview-c"))
        )
        fixture.presentLatest()
        fixture.scheduler.runSteps(4)

        assertTrue(fixture.environment.decoders.any {
            it.spec.location == "/rapid-preview-c.ogg" && it.laneIndex == 0
        })
    }

    @Test
    fun routeRuntimeRecreationPreservesOutputEvidenceFencesAndAcceptsFreshStemReadinessFromZero() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val current = request("route-stem-readiness")
        val token = fixture.controller.select(current)
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        val firstOwner = requireNotNull(fixture.environment.lastSelected())
        val originalSnapshot = fixture.environment.snapshots.last()
        fixture.environment.presentAudibleControl(
            firstOwner,
            originalSnapshot.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL,
            callbackSequence = 11L
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.sourceMode == EngineSourceMode.ORIGINAL
        })

        val stems = currentStems("route-ready")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "route-ready-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        // Leave a high readiness sequence in the first runtime so accepting sequence 1 from the
        // replacement is observable rather than accidentally passing on monotonic fake state.
        repeat(4) {
            fixture.environment.setNativeStemRenderReadiness(firstOwner, 96_000L)
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
        }
        assertEquals(
            NativeRuntimeSourceMode.STEMS,
            fixture.environment.snapshots.last().controls.sourceMode
        )
        val ownerPublicationsBeforeRoute = fixture.ownerPublications().size
        val positionsBeforeRoute = fixture.events.count {
            it is NativeEngineControllerEvent.Position
        }
        val sourceApplicationsBeforeRoute = fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied
        }

        fixture.controller.requestRouteRecovery()
        fixture.scheduler.runNext()
        val routeOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(routeOwner.generation > firstOwner.generation)
        assertEquals(
            NativeRuntimeSourceMode.ORIGINAL,
            fixture.environment.snapshots.last().controls.sourceMode
        )

        // The native runtime is new, but these tuples belong to the persistent output. Poll the
        // unchanged evidence once more and prove none of it is replayed as a controller event.
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(ownerPublicationsBeforeRoute, fixture.ownerPublications().size)
        assertEquals(positionsBeforeRoute, fixture.events.count {
            it is NativeEngineControllerEvent.Position
        })
        assertEquals(sourceApplicationsBeforeRoute, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied
        })

        fixture.environment.setNativeStemRenderReadiness(routeOwner, 96_000L)
        assertEquals(
            1L,
            requireNotNull(fixture.environment.renderReadinessDiagnostics()).sequence
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(
            NativeRuntimeSourceMode.STEMS,
            fixture.environment.snapshots.last().controls.sourceMode
        )
        val readiness = requireNotNull(
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness
        )
        assertEquals(routeOwner, readiness.ownership)
        assertTrue(readiness.ready)

        val routedStemSnapshot = fixture.environment.snapshots.last()
        fixture.environment.present(routeOwner, timelineFrame = 96_512L)
        fixture.environment.presentAudibleControl(
            routeOwner,
            routedStemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 12L
        )
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertEquals(ownerPublicationsBeforeRoute + 1, fixture.ownerPublications().size)
        assertEquals(positionsBeforeRoute + 1, fixture.events.count {
            it is NativeEngineControllerEvent.Position
        })
        assertEquals(sourceApplicationsBeforeRoute + 1, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied
        })
        val routedApplication = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.SourceModeApplied
        >().last()
        assertEquals(token, routedApplication.token)
        assertEquals(EngineSourceMode.STEMS, routedApplication.sourceMode)

        // A second poll exposes the same N+1 output tuples and must remain event-idempotent.
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(ownerPublicationsBeforeRoute + 1, fixture.ownerPublications().size)
        assertEquals(positionsBeforeRoute + 1, fixture.events.count {
            it is NativeEngineControllerEvent.Position
        })
        assertEquals(sourceApplicationsBeforeRoute + 1, fixture.events.count {
            it is NativeEngineControllerEvent.SourceModeApplied
        })
    }

    @Test
    fun stemsUseMonotonicAnchorCoverageAfterOriginalPlayedAndNeverFalseAck() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stems"))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = currentStems(),
                stemControls = currentStems().associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.environment.present(
            requireNotNull(fixture.environment.lastSelected()),
            timelineFrame = 240_000L
        )
        fixture.scheduler.runSteps(2)

        assertEquals(7, fixture.environment.maximumOpenDecoderCount)
        assertEquals(0, fixture.environment.decoders.minOf { it.readableSourceFrames() })
        assertTrue(
            fixture.environment.snapshots.any {
                it.controls.sourceMode == NativeRuntimeSourceMode.STEMS
            }
        )
        assertFalse(
            fixture.events.any {
                it is NativeEngineControllerEvent.SourceModeNotReady &&
                    it.requestedMode == EngineSourceMode.STEMS
            }
        )
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.SourceModeApplied })
    }

    @Test
    fun explicitStemsPreemptIncompleteCacheBuilderAndPrepareAtTheLivePosition() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("post-seek-stems"))
        fixture.scheduler.runNext()
        fixture.presentLatest()

        val originalOwner = requireNotNull(fixture.environment.lastSelected())
        val targetMs = 4_000L
        val targetFrame = msToFrameForTest(targetMs)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = targetMs,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val seekOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(seekOwner.generation > originalOwner.generation)
        fixture.environment.present(seekOwner, targetFrame)
        fixture.scheduler.runNext()

        val selectedBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val consumerOriginal = fixture.environment.decoders.single {
            !it.released && it.bank == selectedBank && it.laneIndex == 0
        }
        val builderBank = NativeRuntimeBank.entries.single { it != selectedBank }
        val builderDecoders = fixture.environment.decoders.filter {
            !it.released && it.bank == builderBank
        }
        assertEquals(1, builderDecoders.size)
        assertEquals(targetFrame, consumerOriginal.head().firstTimelineFrame48k)
        val seekCountBeforeActivation = consumerOriginal.requestedSeekTargets.size

        val stems = currentStems("post-seek-stems")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "post-seek-stems-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runUntil {
            builderDecoders.all { it.released } &&
                fixture.environment.decoders.count {
                    !it.released && it.bank == selectedBank
                } == 7
        }

        val settledSnapshot = fixture.environment.snapshots.last()
        assertEquals(selectedBank, settledSnapshot.selectedBank)
        assertEquals(seekOwner, settledSnapshot.selectedOwnership)
        assertTrue(settledSnapshot.playing)
        assertEquals(NativeRuntimeSourceMode.STEMS, settledSnapshot.controls.sourceMode)
        assertEquals(seekCountBeforeActivation, consumerOriginal.requestedSeekTargets.size)
        assertEquals(targetFrame, consumerOriginal.head().firstTimelineFrame48k)
        val selectedDecoders = fixture.environment.decoders.filter {
            !it.released && it.bank == selectedBank
        }
        assertEquals((0..6).toSet(), selectedDecoders.map { it.laneIndex }.toSet())
        assertTrue(selectedDecoders.filter { it.laneIndex in 1..6 }.all {
            it.spec.initialPositionUs == targetMs * 1_000L
        })
        assertTrue(builderDecoders.all { it.released })
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 8)
    }

    @Test
    fun stemAttachDuringStagedEarlySeekPublishesTheRetainedBuildersPhysicalPackage() {
        assertEarlySeekStemInstallation(readyBeforeSeek = false)
    }

    @Test
    fun readyStemsBeforeEarlySeekStillInstallOnTheReplacementOwnerWithoutAnotherRequest() {
        assertEarlySeekStemInstallation(readyBeforeSeek = true)
    }

    private fun assertEarlySeekStemInstallation(readyBeforeSeek: Boolean) {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("staged-seek-stems"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val originalOwner = requireNotNull(fixture.environment.lastSelected())
        val originalBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val originalIntent = fixture.environment.snapshots.last().banks[originalBank.nativeCode]
        val stems = currentStems("staged-seek-stems")
        fun attachStems() {
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                    stemRevision = "staged-seek-stems-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() })
            })
            fixture.scheduler.runNext()
        }
        if (readyBeforeSeek) {
            attachStems()
            fixture.scheduler.runUntil {
                fixture.events.any { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible }
            }
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })
        }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 4_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val seekOwner = requireNotNull(fixture.environment.lastSelected())
        assertTrue(seekOwner.generation > originalOwner.generation)

        // The replacement is selected but has not earned its first callback yet.
        if (!readyBeforeSeek) attachStems()
        val attached = fixture.environment.snapshots.last()
        val builderIntent = attached.banks[originalBank.nativeCode]
        assertEquals(originalOwner, builderIntent.ownership)
        assertEquals(originalIntent.firstTimelineFrame, builderIntent.firstTimelineFrame)
        assertEquals(originalIntent.outputClockStartFrame, builderIntent.outputClockStartFrame)
        assertEquals(originalIntent.originalContentHash, builderIntent.originalContentHash)
        assertTrue(builderIntent.revision > originalIntent.revision)
        assertNotEquals(0L, builderIntent.stemPackageHash)
        assertEquals(stems.size, builderIntent.stemLaneCount)
        assertEquals(seekOwner, attached.selectedOwnership)
        assertTrue(attached.playing)
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, attached.controls.sourceMode)
        assertEquals((0..stems.size).toSet(), fixture.environment.decoders.filter {
            !it.released && it.bank == originalBank
        }.map { it.laneIndex }.toSet())
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })

        fixture.environment.sealCurrentSongCacheFor(seekOwner)
        fixture.environment.present(seekOwner, msToFrameForTest(4_000L))
        fixture.scheduler.runNext()
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.snapshots.last().controls.sourceMode == NativeRuntimeSourceMode.STEMS
        }
        val prepared = fixture.environment.snapshots.last()
        assertEquals(seekOwner, prepared.selectedOwnership)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })
        fixture.environment.presentAudibleControl(seekOwner, prepared.controls.revision,
            NativeRuntimeSourceMode.STEMS, callbackSequence = 91L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled }
        }
        val installed = fixture.events.filterIsInstance<NativeEngineControllerEvent.StemPackageInstalled>().single()
        assertEquals(token, installed.token)
        assertEquals("staged-seek-stems-v1", installed.stemRevision)
        assertEquals(91L, installed.callbackSequence)
        assertEquals(seekOwner, fixture.environment.lastSelected())
        val ready = fixture.events.filterIsInstance<NativeEngineControllerEvent.StemPackageReadyAwaitingAudible>().single {
            it.packageSignature == installed.packageSignature && it.requestRevision == installed.requestRevision
        }
        assertEquals(ready.packageSignature, installed.packageSignature)
        assertEquals(ready.requestRevision, installed.requestRevision)
    }

    @Test
    fun admittedCacheBackedPackageKeepsOriginalLoopOwnerAcrossUnavailableReadinessAndFocus() {
        for (hardFailure in listOf("decoder", "native-ingress")) {
            val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
            val stems = currentStems("cache-backed-focus")
            val token = fixture.controller.select(request("cache-backed-focus-$hardFailure"))
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                    stemRevision = "cache-backed-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() })
            })
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val builderDecoders = fixture.environment.decoders.filter { !it.released }
            assertEquals(7, builderDecoders.size)
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.ORIGINAL,
                    requestedPositionMs = 2_000L, seekRevision = previous.seekRevision + 1L)
            })
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.sealCurrentSongCacheFor(owner)
            fixture.presentLatest(timelineFrame = 96_000L)
            fixture.scheduler.runUntil { builderDecoders.all { it.released } }
            val original = fixture.environment.decoders.single { !it.released }
            assertEquals(0, original.laneIndex)
            assertEquals(6, fixture.environment.snapshots.last().banks.single {
                it.ownership == owner
            }.stemLaneCount)
            assertTrue(fixture.setLoop(token, EngineLoopConfig(2_003L, 3_007L)))
            fixture.scheduler.runNext()
            val opened = fixture.environment.decoders.size
            val readyEvents = fixture.events.count { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible }

            fixture.controller.suspendForAudioFocusLoss()
            fixture.scheduler.runNext()
            assertTrue(fixture.controller.diagnosticsSnapshot().controllerOutputPhysicallyQuiesced)
            fixture.environment.unsealCurrentSongCacheFor(owner)
            fixture.controller.resumeAfterAudioFocusGain()
            fixture.scheduler.runSteps(2)
            assertEquals("Unavailable cache readiness reopened the admitted graph", opened,
                fixture.environment.decoders.size)
            assertEquals(owner, fixture.environment.lastSelected())
            assertEquals(owner, fixture.ownerPublications().last().ownership)
            assertFalse(original.cancelAttempted)
            val policy = fixture.environment.directLoopPolicies.last()
            assertEquals(owner, policy.ownership)
            assertTrue(policy.enabled)
            assertEquals(96_144L, policy.aTimelineFrame)
            assertEquals(144_336L, policy.bTimelineFrameExclusive)

            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(stemRevision = "cache-backed-v2")
            })
            fixture.scheduler.runSteps(2)
            assertEquals("Metadata revision reopened the same admitted package", opened,
                fixture.environment.decoders.size)
            assertEquals(owner, fixture.environment.lastSelected())
            assertEquals(readyEvents, fixture.events.count {
                it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
            })
            assertEquals(NativeRuntimeSourceMode.ORIGINAL, fixture.environment.snapshots.last().controls.sourceMode)

            if (hardFailure == "decoder") {
                original.failAsync("Original decoder failed after cache readiness disappeared")
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil { fixture.environment.lastSelected() != owner }
                assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.DecoderFailed>().any {
                    it.generation == owner.generation && it.laneIndex == 0
                })
                fixture.presentLatest(timelineFrame = 96_000L)
                fixture.scheduler.runUntil { original.released }
                assertTrue(requireNotNull(fixture.environment.lastSelected()).generation > owner.generation)
            } else {
                fixture.environment.setNativeStemRenderReadiness(owner, 96_000L,
                    ingressFailureGeneration = owner.generation)
                fixture.controller.signalNativeProgress()
                fixture.scheduler.runUntil { original.released }
                assertTrue(fixture.events.filterIsInstance<NativeEngineControllerEvent.DecoderFailed>().any {
                    it.generation == owner.generation && it.laneIndex == 0 && !it.recoverable
                })
                assertNull(fixture.controller.diagnosticsSnapshot().controllerPublicOwnership)
            }
        }
    }

    @Test
    fun explicitStemsUseLiveRecoveryInsteadOfWaitingForAnUnsealedMatchingBuilder() {
        assertExplicitStemsRecoverFromUnsealedMatchingBuilder(builderInitiallyStems = false)
    }

    @Test
    fun explicitStemsPreemptAnUnsealedBuilderRetainingItsPriorStemsIntent() {
        assertExplicitStemsRecoverFromUnsealedMatchingBuilder(builderInitiallyStems = true)
    }

    private fun assertExplicitStemsRecoverFromUnsealedMatchingBuilder(builderInitiallyStems: Boolean) {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val stems = currentStems("live-matching-stems")
        val token = fixture.controller.select(request("live-matching-stems"))
        if (builderInitiallyStems) {
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                    stemRevision = "live-matching-stems-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() })
            })
        }
        fixture.scheduler.runNext()
        fixture.presentLatest()
        if (builderInitiallyStems) {
            fixture.scheduler.runUntil {
                fixture.environment.snapshots.last().controls.sourceMode == NativeRuntimeSourceMode.STEMS
            }
            fixture.presentLatestWithAudibleControl()
            assertEquals(7, fixture.environment.decoders.count { !it.released })
        }
        val installedBeforeSeek = fixture.events.count { it is NativeEngineControllerEvent.StemPackageInstalled }
        if (builderInitiallyStems) assertEquals(1, installedBeforeSeek)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.ORIGINAL,
                requestedPositionMs = 4_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val seekOwner = requireNotNull(fixture.environment.lastSelected())
        val seekBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val liveOriginal = fixture.environment.decoders.single { !it.released && it.bank == seekBank }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemRevision = "live-matching-stems-v1",
                stemControls = stems.associate { it.id to EngineStemControl() })
        })
        fixture.scheduler.runNext()
        val builderDecoders = fixture.environment.decoders.filter { !it.released && it.bank != seekBank }
        assertEquals(7, builderDecoders.size)
        assertEquals(6, fixture.environment.snapshots.last().banks.single {
            it.ownership != seekOwner
        }.stemLaneCount)
        fixture.environment.present(seekOwner, msToFrameForTest(4_000L))
        fixture.scheduler.runNext()

        // No cache sealing signal is supplied: foreground activation must use the live suffix.
        fixture.scheduler.runUntil(maximumSteps = 40) {
            builderDecoders.all { it.released } && fixture.environment.decoders.any {
                !it.released && it.laneIndex == 0 && it !== liveOriginal
            }
        }
        val replacement = fixture.environment.decoders.single {
            !it.released && it.laneIndex == 0 && it !== liveOriginal
        }
        assertTrue(replacement.spec.initialPositionUs >= 4_000_000L)
        assertFalse("The audible Original must survive until replacement callback proof", liveOriginal.cancelAttempted)
        assertTrue("Parts must attach to the recovered graph after callback proof",
            fixture.environment.decoders.none { !it.released && it.bank == seekBank && it.laneIndex != 0 })
        assertEquals(seekOwner, fixture.ownerPublications().last().ownership)
        fixture.scheduler.runUntil {
            fixture.environment.lastSelected()?.generation != seekOwner.generation
        }
        val recoveredOwner = requireNotNull(fixture.environment.lastSelected())
        assertEquals(token.value, recoveredOwner.selectionToken)
        assertTrue(recoveredOwner.generation > seekOwner.generation)
        fixture.environment.present(recoveredOwner, msToFrameForTest(4_020L))
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 40) {
            liveOriginal.released && fixture.environment.snapshots.last().controls.sourceMode ==
                NativeRuntimeSourceMode.STEMS
        }
        val liveParts = fixture.environment.decoders.filter { !it.released && it.laneIndex != 0 }
        assertEquals(6, liveParts.size)
        assertTrue(liveParts.all { it.spec.initialPositionUs >= 4_000_000L })
        assertEquals(installedBeforeSeek,
            fixture.events.count { it is NativeEngineControllerEvent.StemPackageInstalled })
        val prepared = fixture.environment.snapshots.last()
        fun latestSourceApplied() = fixture.events.filterIsInstance<NativeEngineControllerEvent.SourceModeApplied>()
            .any { it.token == token && it.desiredRevision == prepared.controls.sourceIntentRevision &&
                it.sourceMode == EngineSourceMode.STEMS }
        assertFalse(latestSourceApplied())
        fixture.environment.presentAudibleControl(recoveredOwner, prepared.controls.revision,
            NativeRuntimeSourceMode.STEMS, callbackSequence = 92L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { latestSourceApplied() }
        val installed = fixture.events.filterIsInstance<NativeEngineControllerEvent.StemPackageInstalled>()
        if (builderInitiallyStems) {
            assertEquals("Reapplying one package must not duplicate its installation event",
                installedBeforeSeek, installed.size)
        } else {
            val newInstallation = installed.drop(installedBeforeSeek).single()
            assertEquals(token, newInstallation.token)
            assertEquals("live-matching-stems-v1", newInstallation.stemRevision)
            assertEquals(92L, newInstallation.callbackSequence)
        }
        assertEquals(recoveredOwner, fixture.environment.lastSelected())
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 8)
    }

    @Test
    fun pausedSeekAfterRapidSourceChurnAndCacheBuilderReleaseRebasesSelectedOwner() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val stems = currentStems("r3-seek")
        val token = fixture.controller.select(request("r3-seek"))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "r3-seek-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
        val builderDecoders = fixture.environment.decoders.filter { !it.released }
        val builderBank = builderDecoders.map { it.bank }.toSet().single()
        // Foreground preemption may request retirement; keep its exact native ACK pending here.
        fixture.environment.heldUnassignmentAcknowledgementBanks += builderBank

        repeat(8) { index ->
            assertTrue("Source churn index=$index; events=${fixture.events}", fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.ORIGINAL)
            })
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(tempo = listOf(.5f, 1f, 1.35f, 2f)[index % 4])
            })
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    requestedPositionMs = (index * 431L) % 4_500L,
                    seekRevision = previous.seekRevision + 1L
                )
            })
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.present(owner, msToFrameForTest((index * 431L) % 4_500L))
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS)
            })
            fixture.scheduler.runSteps(2)
        }

        val consumerOwner = requireNotNull(fixture.environment.lastSelected())
        val selectedDecoder = fixture.environment.decoders.single {
            !it.released && it !in builderDecoders
        }
        val seeksBeforeFinal = selectedDecoder.requestedSeekTargets.size
        var pauseAccepted = false
        var seekAccepted = false
        fixture.environment.expectCancellationBeforeJoin(builderDecoders) {
            pauseAccepted = fixture.controller.updateDesiredWithAudibleTransport(
                token,
                playWhenReady = false
            ) { previous -> previous.copy(playWhenReady = false) }
            seekAccepted = fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    requestedPositionMs = 0L,
                    seekRevision = previous.seekRevision + 1L
                )
            }
        }
        fixture.environment.heldUnassignmentAcknowledgementBanks -= builderBank
        fixture.environment.sealCurrentSongCacheFor(consumerOwner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil(maximumSteps = 24) { builderDecoders.all { it.released } }
        assertTrue(pauseAccepted)
        assertTrue(seekAccepted)
        fixture.scheduler.runUntil(maximumSteps = 8) {
            fixture.environment.lastSelected()?.generation?.let { it > consumerOwner.generation } == true
        }

        val selectedAfterSeek = requireNotNull(fixture.environment.lastSelected())
        assertTrue(selectedAfterSeek.generation > consumerOwner.generation)
        assertEquals(seeksBeforeFinal + 1, selectedDecoder.requestedSeekTargets.size)
        // A seek advances the retained decoder's head, not its construction-time spec.
        assertEquals(listOf(selectedDecoder), fixture.environment.decoders.filter { !it.released })
        assertEquals(selectedAfterSeek.generation, selectedDecoder.head().nativeGeneration)
        assertEquals(0L, selectedDecoder.head().firstTimelineFrame48k)

        fixture.environment.sealCurrentSongCacheFor(selectedAfterSeek)
        fixture.environment.installPaused(selectedAfterSeek)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.ownerPublications().any { it.ownership == selectedAfterSeek })
        assertTrue(fixture.events.filterIsInstance<
            NativeEngineControllerEvent.LoopAvailabilityChanged
        >().any { it.ownership == selectedAfterSeek && it.available })
    }

    @Test
    fun seekUsesExactAdmittedCapacityRatherThanRejectedOptionalIntent() {
        for ((capacity, staleOwner) in listOf(1L to false, 7L to false, 1L to true)) {
            val fixture = Fixture(currentSongCacheSealedByDefault = false)
            val token = fixture.controller.select(request("admitted-seek-$capacity-$staleOwner"))
            val stems = currentStems("admitted-seek")
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(stemLanes = stems, stemRevision = "admitted-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() })
            })
            fixture.scheduler.runNext()
            fixture.presentLatest()
            fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
            val owner = requireNotNull(fixture.environment.lastSelected())
            val before = fixture.environment.decoders.filter { !it.released }
            val intent = fixture.environment.snapshots.last().banks.single { it.ownership == owner }
            fixture.environment.sealCurrentSongOriginalFor(owner)
            fixture.environment.canonicalPcmIo = NativeRuntimeCanonicalPcmIoDiagnostics(
                ownership = if (staleOwner) owner.copy(generation = owner.generation + 100L) else owner,
                songIdentityHash = intent.songIdentityHash, originalContentHash = intent.originalContentHash,
                stemPackageHash = intent.stemPackageHash, readOperations = 0L, readBytes = 0L,
                lastReadByteOffset = 0L, lastReadBytes = 0L, fileBytes = 0L,
                reservedBytes = 0L, peakFileBytes = 0L, admittedLaneCount = capacity,
                admittedFrameUpperBound = 480_000L,
                lastAdmissionStatus = NativeCacheStorageStatus.INSUFFICIENT_SPACE
            )
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(requestedPositionMs = 2_000L, seekRevision = previous.seekRevision + 1L)
            })
            fixture.scheduler.runUntil { fixture.environment.lastSelected() != owner }
            if (capacity == 1L && !staleOwner) {
                assertEquals("Rejected optional capacity must not retain a frame-zero builder", 7,
                    fixture.environment.decoders.count { !it.released })
                assertTrue(before.all { it.requestedSeekTargets.isNotEmpty() })
            } else {
                assertEquals(8, fixture.environment.decoders.count { !it.released })
                assertTrue(before.all { it.requestedSeekTargets.isEmpty() })
            }
        }
    }

    @Test
    fun stemsAttachedAfterCachedSeekFillFromZeroAndUseSealedCacheReadiness() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("cached-seek-stems"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val initialOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.sealCurrentSongOriginalFor(initialOwner)
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(requestedPositionMs = 5_000L, seekRevision = previous.seekRevision + 1L)
        })
        fixture.scheduler.runNext()
        val seekOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.sealCurrentSongOriginalFor(seekOwner)
        fixture.presentLatest(timelineFrame = 240_000L)
        val stems = currentStems("cached-seek")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemControls = stems.associate { it.id to EngineStemControl() })
        })
        fixture.scheduler.runNext()
        val stemDecoders = fixture.environment.decoders.filter { !it.released && it.laneIndex in 1..6 }
        assertEquals(6, stemDecoders.size)
        assertEquals(setOf(0L), stemDecoders.map { it.spec.initialPositionUs }.toSet())
        fixture.environment.sealCurrentSongCacheFor(seekOwner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.environment.snapshots.last().controls.sourceMode == NativeRuntimeSourceMode.STEMS
        }
        assertEquals(7, fixture.environment.decoders.count { !it.released })
    }

    @Test
    fun rejectedLateStemCacheUsesLiveHorizonAndExactOriginalProofAfterSeek() {
        for (playing in listOf(true, false)) {
            val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
            val token = fixture.controller.select(request("streamed-seek-stems-$playing", autoplay = playing))
            fixture.scheduler.runNext()
            fixture.presentLatest()
            val initial = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.sealCurrentSongOriginalFor(initial)
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(requestedPositionMs = 5_000L, seekRevision = previous.seekRevision + 1L)
            })
            fixture.scheduler.runNext()
            val owner = requireNotNull(fixture.environment.lastSelected())
            fixture.environment.sealCurrentSongOriginalFor(owner)
            fixture.presentLatest(timelineFrame = 240_000L)
            val original = fixture.environment.decoders.single { !it.released && it.laneIndex == 0 }
            val stems = currentStems("streamed-seek")
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                    stemRevision = "streamed-v1", stemControls = stems.associate { it.id to EngineStemControl() })
            })
            fixture.scheduler.runNext()
            val live = fixture.environment.decoders.filter { !it.released }
            original.setAcceptedCoverage(245_000L, 253_192L)
            original.exactEndOfStreamFrame = null
            live.filter { it.laneIndex != 0 }.forEach {
                it.setAcceptedCoverage(0L, 251_072L)
                it.exactEndOfStreamFrame = null
            }
            fixture.environment.setNativeStemRenderReadiness(owner, 242_880L,
                cacheStorageStatus = NativeCacheStorageStatus.INSUFFICIENT_SPACE)
            fixture.environment.holdCacheRangeProof = true
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runNext()
            assertFalse("A cache summary cannot replace exact Original range proof",
                requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness).ready)
            fixture.environment.releaseCacheRangeProof()
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil {
                fixture.environment.snapshots.last().controls.sourceMode == NativeRuntimeSourceMode.STEMS
            }
            val readiness = requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness)
            assertTrue(readiness.ready)
            assertEquals(242_880L, readiness.anchorFrame48k)
            assertEquals(owner, fixture.environment.lastSelected())
            assertEquals(7, live.size)
            assertFalse(original.cancelAttempted)
        }
    }

    @Test
    fun lateInitialSixStemAttachStillBuildsContiguouslyFromFrameZero() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("late-stems"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val generationBeforeAttach = fixture.environment.outputGeneration
        val stems = currentStems("v1")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "stem-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()

        val stemDecoders = fixture.environment.decoders.filter { it.laneIndex in 1..6 }
        assertEquals(6, stemDecoders.size)
        assertEquals(setOf(0L), stemDecoders.map { it.spec.initialPositionUs }.toSet())
        assertEquals(generationBeforeAttach, fixture.environment.outputGeneration)
        val preparing = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackagePreparing
        >().single { it.stemRevision == "stem-v1" }
        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "stem-v1" }
        assertEquals(token, preparing.token)
        assertEquals(preparing.packageSignature, ready.packageSignature)
        assertEquals(preparing.requestRevision, ready.requestRevision)
        assertFalse(ready.retainedPriorPackage)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.SourceModeApplied })
    }

    @Test
    fun diagnosticsCopiesSelectedDecoderTopologyBeforeConcurrentStemAttach() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("diagnostic-topology"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        val original = fixture.environment.decoders.single { it.laneIndex == 0 }
        val diagnosticHeadEntered = CountDownLatch(1)
        val releaseDiagnosticHead = CountDownLatch(1)
        original.blockNextHead(diagnosticHeadEntered, releaseDiagnosticHead)

        var snapshotResult: Result<NativeEngineDiagnosticsSnapshot>? = null
        val reader = thread(name = "native-diagnostic-topology-reader", isDaemon = true) {
            snapshotResult = runCatching { fixture.controller.diagnosticsSnapshot() }
        }
        try {
            assertTrue(
                "The diagnostic reader never reached the selected decoder",
                diagnosticHeadEntered.await(TEST_THREAD_WATCHDOG_MS, TimeUnit.MILLISECONDS)
            )

            val stems = currentStems("diagnostic")
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    sourceMode = EngineSourceMode.STEMS,
                    stemLanes = stems,
                    stemRevision = "diagnostic-stems-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() }
                )
            })
            fixture.scheduler.runUntil(maximumSteps = 8) {
                fixture.environment.decoders.count { it.laneIndex in 1..6 } == 6
            }
        } finally {
            releaseDiagnosticHead.countDown()
            reader.join(TEST_THREAD_WATCHDOG_MS)
            if (reader.isAlive) {
                reader.interrupt()
                reader.join(TEST_THREAD_CLEANUP_MS)
            }
        }

        assertFalse("The diagnostic reader did not finish", reader.isAlive)
        val duringAttach = requireNotNull(snapshotResult).getOrThrow()
        assertEquals(listOf(0), duringAttach.controllerSelectedDecoders.map { it.laneIndex })
        assertEquals(
            (0..6).toList(),
            fixture.controller.diagnosticsSnapshot().controllerSelectedDecoders.map { it.laneIndex }
        )
    }

    @Test
    fun stemsWaitForExactLiveNativeReservoirCoverageBeforeReadyOrSelection() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("native-stem-readiness"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("native-ready")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "native-ready-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()

        assertEquals(NativeRuntimeSourceMode.ORIGINAL, fixture.environment.snapshots.last().controls.sourceMode)
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        })

        fixture.environment.setNativeStemRenderReadiness(
            owner = owner,
            renderStartTimelineFrame = 240_000L,
            shortLane = 4,
            shortLaneAvailableFrames = 2_303L
        )
        fixture.scheduler.runNext()

        assertEquals(NativeRuntimeSourceMode.ORIGINAL, fixture.environment.snapshots.last().controls.sourceMode)
        val shortDiagnostics = requireNotNull(
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness
        )
        assertFalse(shortDiagnostics.ready)
        assertTrue(shortDiagnostics.failureReasons.contains("native-lane-4-ahead-2303"))

        fixture.environment.setNativeStemRenderReadiness(
            owner = owner,
            renderStartTimelineFrame = 240_000L,
            // Effective renderer coverage may come from an installed all-lane loop cache even
            // after the raw reservoirs have deliberately discarded that cached interval. The
            // controller must consume the native renderer's cache-aware availability proof, not
            // try to reconstruct it from the raw reservoir interval.
            rawFirstTimelineFrame = 288_000L,
            rawEndTimelineFrameExclusive = 419_072L
        )
        fixture.scheduler.runNext()

        assertEquals(NativeRuntimeSourceMode.STEMS, fixture.environment.snapshots.last().controls.sourceMode)
        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "native-ready-v1"
        })
        assertTrue(requireNotNull(
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness
        ).ready)
    }

    @Test
    fun matchingFiniteEofTailsBecomeStemsReadyWithFullNativeRenderabilityProof() {
        val fixture = finiteStemTailFixture("matching-eof-tail")
        val owner = requireNotNull(fixture.environment.lastSelected())

        fixture.scheduler.runNext()

        val readiness = requireNotNull(fixture.controller.diagnosticsSnapshot().controllerStemReadiness)
        assertEquals(47_280L, readiness.anchorFrame48k)
        assertTrue("Complete 720-frame EOF tails should qualify; $readiness", readiness.ready)
        assertEquals(NativeRuntimeSourceMode.STEMS, fixture.environment.snapshots.last().controls.sourceMode)
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(48_000L, readiness.requiredEndFrame48k)
        assertEquals(1, fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible>().size)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun selectablePackagesRequireEveryDeclaredLaneAndIgnoreUnusedCapacity() {
        val sourceSets = listOf(
            listOf("vocals", "remaining"),
            listOf("lead-vocals", "backing-vocals", "drums", "bass", "acoustic-guitar", "electric-guitar",
                "harp", "mandolin", "remaining")
        )
        sourceSets.forEach { roles ->
            val fixture = Fixture(requireNativeStemReadiness = true,
                currentSongCacheSealedByDefault = false)
            val token = fixture.controller.select(request("dynamic-${roles.size}"))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 240_000L)
            val owner = requireNotNull(fixture.environment.lastSelected())
            val stems = roles.reversed().map { role ->
                EngineStemLane(role, role, if (role == "remaining") "/remaining.wav" else "/dynamic-$role.flac", "sha-$role")
            }
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                    stemRevision = "dynamic-${roles.size}",
                    stemControls = stems.associate {
                        it.id to EngineStemControl(gain = if (it.id == "remaining") .37f else 1f)
                    })
            })
            fixture.scheduler.runNext()
            assertEquals((0..roles.size).toList(), fixture.controller.diagnosticsSnapshot()
                .controllerSelectedDecoders.map { it.laneIndex })
            assertEquals(roles.size, fixture.environment.snapshots.last().banks
                [requireNotNull(fixture.environment.snapshots.last().selectedBank).nativeCode].stemLaneCount)
            assertTrue(fixture.environment.decoders.single { it.laneIndex == roles.size }.spec.directFloat32Audio)
            assertTrue(fixture.environment.decoders.filter { it.laneIndex != roles.size }
                .all { !it.spec.directFloat32Audio })

            fixture.environment.setNativeStemRenderReadiness(owner, 240_000L,
                shortLane = roles.size, shortLaneAvailableFrames = 2_303L)
            fixture.scheduler.runNext()
            assertEquals(NativeRuntimeSourceMode.ORIGINAL,
                fixture.environment.snapshots.last().controls.sourceMode)
            assertFalse(requireNotNull(fixture.controller.diagnosticsSnapshot()
                .controllerStemReadiness).ready)

            fixture.environment.setNativeStemRenderReadiness(owner, 240_000L)
            fixture.scheduler.runNext()
            val controls = fixture.environment.snapshots.last().controls
            assertEquals(NativeRuntimeSourceMode.STEMS, controls.sourceMode)
            assertEquals(.37f, controls.stems[roles.size - 1].gain, 0f)
            assertTrue(controls.stems.drop(roles.size).all { it.gain == 0f && it.muted && !it.soloed })
            assertEquals(1, fixture.events.filterIsInstance<
                NativeEngineControllerEvent.StemPackageReadyAwaitingAudible>().size)

            val packageEvent = fixture.events.filterIsInstance<
                NativeEngineControllerEvent.StemPackageReadyAwaitingAudible>().single()
            assertNull(fixture.controller.beginStemMixPreview(token, owner,
                packageEvent.packageSignature, packageEvent.requestRevision, laneIndex = roles.size))
            val lease = requireNotNull(fixture.controller.beginStemMixPreview(token, owner,
                packageEvent.packageSignature, packageEvent.requestRevision, laneIndex = roles.size - 1))
            val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }
            gains[roles.size - 1] = .19f
            assertTrue(fixture.controller.offerStemMixPreviewBatch(lease,
                1 shl (roles.size - 1), gains))
            fixture.scheduler.runNext()
            assertEquals(1 shl (roles.size - 1), fixture.environment.stemGainPreviews.last().activeMask)
            assertEquals(.19f, fixture.environment.stemGainPreviews.last().gains[roles.size - 1], 0f)
            assertTrue(fixture.controller.endStemMixPreview(lease))
            fixture.scheduler.runNext()
            assertEquals(0, fixture.environment.stemGainPreviews.last().activeMask)
            assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        }
    }

    @Test
    fun frameBoundedStemPreviewPublishesLatestOverlayThenRestoresCanonicalOnEnd() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-overlay"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-overlay")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-overlay-v1",
                stemControls = stems.mapIndexed { index, stem ->
                    stem.id to EngineStemControl(muted = index == 0, solo = index == 1)
                }.toMap()
            )
        })
        fixture.scheduler.runNext()
        val packageSignature = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-overlay-v1" }.packageSignature
        val lease = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageSignature,
                fixture.environment.snapshots.last().controls.stemIntentRevision,
                laneIndex = 0
            )
        )
        fixture.scheduler.runNext()

        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }
        gains[0] = .23f
        val commandCountBeforePreview = fixture.environment.snapshots.size
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1, gains))
        fixture.scheduler.runNext()
        assertEquals(commandCountBeforePreview, fixture.environment.snapshots.size)
        assertEquals(.23f, fixture.environment.stemGainPreviews.last().gains[0])
        assertEquals(1, fixture.environment.stemGainPreviews.last().activeMask)
        assertTrue(fixture.environment.snapshots.last().controls.stems[0].muted)
        assertTrue(fixture.environment.snapshots.last().controls.stems[1].soloed)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemControls = previous.stemControls.mapValues { (_, control) ->
                control.copy(muted = false, solo = false)
            })
        })
        fixture.scheduler.runUntil {
            fixture.environment.snapshots.last().controls.stems.take(stems.size).all { !it.muted && !it.soloed }
        }
        gains[0] = .41f
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1, gains))
        fixture.scheduler.runNext()
        assertEquals(.41f, fixture.environment.stemGainPreviews.last().gains[0])
        assertTrue(fixture.environment.snapshots.last().controls.stems.take(stems.size).all { !it.muted && !it.soloed })

        assertTrue(fixture.controller.endStemMixPreview(lease))
        fixture.scheduler.runNext()
        assertEquals(0, fixture.environment.stemGainPreviews.last().activeMask)
        assertEquals(1f, fixture.environment.snapshots.last().controls.stems[0].gain)
    }

    @Test
    fun sameSignaturePackageRevisionReplacementNeverPublishesAZeroOfferFence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-authority-replacement"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-authority-replacement")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-authority-replacement-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val firstPackage = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-authority-replacement-v1" }
        val first = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                firstPackage.packageSignature,
                firstPackage.requestRevision,
                laneIndex = 0
            )
        )
        fixture.scheduler.runNextImmediate()
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }.also {
            it[0] = .31f
        }
        assertTrue(fixture.controller.offerStemMixPreviewBatch(first, 1, gains))
        fixture.scheduler.runNextImmediate()
        assertEquals(1L, fixture.environment.stemGainPreviews.last().coversOfferSequence)

        val nextRequestRevision = firstPackage.requestRevision + 1L
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemRevision = "preview-authority-replacement-v2")
        })
        // Replace the mailbox authority before the queued canonical actor can consume the package
        // request. The package signature is intentionally unchanged; the request revision is not.
        val replacement = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                firstPackage.packageSignature,
                nextRequestRevision,
                laneIndex = 0
            )
        )
        val previewCountBeforeReplacementActor = fixture.environment.stemGainPreviews.size
        fixture.scheduler.runNextImmediate()

        assertEquals(
            previewCountBeforeReplacementActor,
            fixture.environment.stemGainPreviews.size
        )
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertFalse(fixture.controller.offerStemMixPreviewBatch(first, 1, gains))

        gains[0] = .62f
        assertTrue(fixture.controller.offerStemMixPreviewBatch(replacement, 1, gains))
        fixture.scheduler.runNextImmediate()
        val replacementPreview = fixture.environment.stemGainPreviews.last()
        assertEquals(2L, replacementPreview.coversOfferSequence)
        assertEquals(nextRequestRevision, replacementPreview.stemIntentRevision)
        assertEquals(1, replacementPreview.activeMask)
        assertTrue(fixture.controller.endStemMixPreview(replacement))
        fixture.scheduler.runNextImmediate()
        assertEquals(0, fixture.environment.stemGainPreviews.last().activeMask)
    }

    @Test
    fun frameBoundedStemPreviewUsesLightActorAndOwnsGainOnly() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-light-actor"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-light-actor")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-light-actor-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val packageSignature = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-light-actor-v1" }.packageSignature
        val readinessReadsBefore = fixture.environment.renderReadinessDiagnosticReads
        val scheduledDelayStart = fixture.scheduler.scheduledDelays.size
        val lease = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageSignature,
                fixture.environment.snapshots.last().controls.stemIntentRevision,
                laneIndex = 0
            )
        )
        fixture.scheduler.runNextImmediate()

        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }.also {
            it[0] = .28f
        }
        val commandCountBeforePreview = fixture.environment.snapshots.size
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1, gains))
        fixture.scheduler.runNextImmediate()
        assertEquals(commandCountBeforePreview, fixture.environment.snapshots.size)
        assertEquals(.28f, fixture.environment.stemGainPreviews.last().gains[0])
        assertEquals(
            readinessReadsBefore,
            fixture.environment.renderReadinessDiagnosticReads
        )
        assertTrue(fixture.scheduler.scheduledDelays.drop(scheduledDelayStart).none { it == 1L })

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemControls = previous.stemControls +
                    (stems[0].id to EngineStemControl(gain = 1f, muted = true, solo = true))
            )
        })
        fixture.scheduler.runNextImmediate()
        val canonical = fixture.environment.snapshots.last().controls.stems[0]
        assertEquals(1f, canonical.gain)
        assertTrue(canonical.muted)
        assertTrue(canonical.soloed)
        assertEquals(.28f, fixture.environment.stemGainPreviews.last().gains[0])
        assertTrue(fixture.controller.endStemMixPreview(lease))
    }

    @Test
    fun terminalDesiredStateFencesAnUnconsumedPreviewWithoutRevisionDivergence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-terminal"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-terminal")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-terminal-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val packageSignature = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-terminal-v1" }.packageSignature
        val lease = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageSignature,
                fixture.environment.snapshots.last().controls.stemIntentRevision,
                laneIndex = 2
            )
        )
        fixture.scheduler.runNext()
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }.also {
            it[2] = .1f
        }
        val terminalSnapshotStart = fixture.environment.snapshots.size
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1 shl 2, gains))
        // ACTION_UP may be newer than the last display drain. Its exact preview must supersede the
        // unconsumed MOVE before canonical commit and lane retirement.
        gains[2] = .67f
        assertTrue(fixture.controller.offerStemMixPreviewBatch(lease, 1 shl 2, gains))
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemControls = previous.stemControls +
                    (stems[2].id to EngineStemControl(gain = .67f))
            )
        })
        assertTrue(fixture.controller.endStemMixPreview(lease))

        fixture.scheduler.runUntil(maximumSteps = 6) {
            fixture.environment.snapshots.last().controls.stems[2].gain == .67f &&
                fixture.environment.stemGainPreviews.lastOrNull()?.activeMask == 0
        }
        val terminalCommand = fixture.environment.snapshots.last()
        val retirement = fixture.environment.stemGainPreviews.last()
        assertEquals(.67f, terminalCommand.controls.stems[2].gain)
        assertTrue(
            fixture.environment.snapshots.drop(terminalSnapshotStart).all {
                it.controls.stems[2].gain == .67f
            }
        )
        assertEquals(0, retirement.activeMask)
        assertEquals(2L, retirement.coversOfferSequence)
        assertEquals(terminalCommand.controls.revision, retirement.canonicalControlFloorRevision)
        assertEquals(owner, retirement.ownership)
        assertEquals(
            terminalCommand.controls.sourceIntentRevision,
            retirement.sourceIntentRevision
        )
        assertEquals(
            terminalCommand.controls.stemIntentRevision,
            retirement.stemIntentRevision
        )
    }

    @Test
    fun endingOnePreviewLaneKeepsAnotherLaneLive() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("preview-multitouch"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("preview-multitouch")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "preview-multitouch-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val packageSignature = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "preview-multitouch-v1" }.packageSignature
        val first = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageSignature,
                fixture.environment.snapshots.last().controls.stemIntentRevision,
                laneIndex = 0
            )
        )
        val second = requireNotNull(
            fixture.controller.beginStemMixPreview(
                token,
                owner,
                packageSignature,
                fixture.environment.snapshots.last().controls.stemIntentRevision,
                laneIndex = 1
            )
        )
        fixture.scheduler.runNext()
        val gains = FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }.also {
            it[0] = .31f
            it[1] = .79f
        }
        assertTrue(fixture.controller.offerStemMixPreviewBatch(first, 0b11, gains))
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.endStemMixPreview(first))
        fixture.scheduler.runNext()

        val overlay = fixture.environment.stemGainPreviews.last()
        assertEquals(0b10, overlay.activeMask)
        assertEquals(.79f, overlay.gains[1])
        assertTrue(fixture.controller.endStemMixPreview(second))
    }

    @Test
    fun sourceModeAckRequiresExactCallbackAndRejectsPreReversalEvidence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("source-reversal"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val beforeReversal = fixture.environment.snapshots.last()
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.SourceModeApplied })

        val stems = currentStems("reversal")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "reversal-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.ORIGINAL)
        })
        fixture.scheduler.runNext()
        val afterReversal = fixture.environment.snapshots.last()
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, beforeReversal.controls.sourceMode)
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, afterReversal.controls.sourceMode)

        fixture.environment.presentAudibleControl(
            owner,
            beforeReversal.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.scheduler.runNext()
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.SourceModeApplied })

        fixture.environment.presentAudibleControl(
            owner,
            afterReversal.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.scheduler.runNext()
        val applied = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.SourceModeApplied
        >().single()
        assertEquals(token, applied.token)
        assertEquals(EngineSourceMode.ORIGINAL, applied.sourceMode)
        assertTrue(applied.desiredRevision > 1L)
    }

    @Test
    fun conflatedSourceReversalsPublishTheFinalEdgeRevisionEvenWhenModeReturnsToOriginal() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("source-conflated-reversal"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stems = currentStems("conflated-reversal")

        repeat(4) { index ->
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(
                    sourceMode = if (index % 2 == 0) {
                        EngineSourceMode.STEMS
                    } else {
                        EngineSourceMode.ORIGINAL
                    },
                    stemLanes = stems,
                    stemRevision = "conflated-reversal-v1",
                    stemControls = stems.associate { it.id to EngineStemControl() }
                )
            })
        }

        // One actor turn sees only the conflated final ORIGINAL snapshot. Its source edge must still
        // carry revision 5, rather than being inferred as the actor's already-observed revision 1.
        fixture.scheduler.runNext()
        val finalSnapshot = fixture.environment.snapshots.last()
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, finalSnapshot.controls.sourceMode)
        fixture.environment.presentAudibleControl(
            owner,
            finalSnapshot.controls.revision,
            NativeRuntimeSourceMode.ORIGINAL
        )
        fixture.scheduler.runNext()

        val applied = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.SourceModeApplied
        >().single()
        assertEquals(token, applied.token)
        assertEquals(5L, applied.desiredRevision)
        assertEquals(EngineSourceMode.ORIGINAL, applied.sourceMode)
    }

    @Test
    fun stemPackageInstallsExactlyOnceOnlyAfterItsSettledCallbackEvidence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("audible-stems"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 240_000L)
        val stems = currentStems("audible")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "audible-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "audible-v1" }
        val owner = requireNotNull(fixture.environment.lastSelected())
        val stemSnapshot = fixture.environment.snapshots.last {
            it.controls.sourceMode == NativeRuntimeSourceMode.STEMS
        }
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })

        fixture.environment.presentAudibleControl(
            owner,
            stemSnapshot.controls.revision + 1_000L,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 41L
        )
        fixture.scheduler.runNext()
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })

        fixture.environment.presentAudibleControl(
            owner,
            stemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 42L
        )
        fixture.scheduler.runNext()
        val installed = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageInstalled
        >().single()
        assertEquals(token, installed.token)
        assertEquals(ready.packageSignature, installed.packageSignature)
        assertEquals(ready.requestRevision, installed.requestRevision)
        assertEquals(42L, installed.callbackSequence)

        fixture.environment.presentAudibleControl(
            owner,
            stemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 43L
        )
        fixture.scheduler.runNext()
        assertEquals(1, fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageInstalled
        })
    }

    @Test
    fun samePhysicalPackageRevisionUpdateOpensNothingAndSpendsNoGeneration() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stem-metadata"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stems = currentStems("stable")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = stems, stemRevision = "metadata-v1")
        })
        fixture.scheduler.runNext()
        val generation = fixture.environment.outputGeneration
        val opened = fixture.environment.openedDecoderCount

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemRevision = "metadata-v2")
        })
        fixture.scheduler.runNext()

        assertEquals(generation, fixture.environment.outputGeneration)
        assertEquals(opened, fixture.environment.openedDecoderCount)
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "metadata-v2"
        })
    }

    @Test
    fun samePhysicalPackageRevisionPublishesAndInstallsTheCurrentRequest() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("stem-metadata-audible"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        val stems = currentStems("stable-audible")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "metadata-audible-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        val firstStemSnapshot = fixture.environment.snapshots.last {
            it.controls.sourceMode == NativeRuntimeSourceMode.STEMS
        }
        fixture.environment.presentAudibleControl(
            owner,
            firstStemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 51L
        )
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageInstalled &&
                it.stemRevision == "metadata-audible-v1"
        })

        val generation = fixture.environment.outputGeneration
        val opened = fixture.environment.openedDecoderCount
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemRevision = "metadata-audible-v2")
        })
        fixture.scheduler.runNext()

        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "metadata-audible-v2" }
        val secondStemSnapshot = fixture.environment.snapshots.last()
        assertEquals(NativeRuntimeSourceMode.STEMS, secondStemSnapshot.controls.sourceMode)
        fixture.environment.presentAudibleControl(
            owner,
            secondStemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 52L
        )
        fixture.scheduler.runNext()

        assertEquals(generation, fixture.environment.outputGeneration)
        assertEquals(opened, fixture.environment.openedDecoderCount)
        val publication = fixture.controller.diagnosticsSnapshot()
            .controllerSourceControlPublications
            .single { it.nativeControlRevision == secondStemSnapshot.controls.revision }
        assertEquals(ready.packageSignature, publication.stemPackageSignature)
        assertEquals(ready.stemRevision, publication.stemRevision)
        assertEquals(ready.requestRevision, publication.stemRequestRevision)
        val installed = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageInstalled
        >().single { it.stemRevision == "metadata-audible-v2" }
        assertEquals(ready.packageSignature, installed.packageSignature)
        assertEquals(ready.requestRevision, installed.requestRevision)
        assertEquals(52L, installed.callbackSequence)
    }

    @Test
    fun sourceOnlyToggleKeepsAndInstallsThePreparedPackageRequestRevision() {
        val fixture = Fixture(currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("prepared-source-toggle"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        val stems = currentStems("prepared-source-toggle")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = stems,
                stemRevision = "prepared-source-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val prepared = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "prepared-source-v1" }

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS)
        })
        fixture.scheduler.runNext()

        val stemSnapshot = fixture.environment.snapshots.last()
        assertEquals(NativeRuntimeSourceMode.STEMS, stemSnapshot.controls.sourceMode)
        val diagnostics = fixture.controller.diagnosticsSnapshot()
        val readiness = requireNotNull(diagnostics.controllerStemReadiness)
        val publication = diagnostics.controllerSourceControlPublications.single {
            it.nativeControlRevision == stemSnapshot.controls.revision
        }
        assertEquals(prepared.requestRevision, publication.stemRequestRevision)
        assertEquals(prepared.requestRevision, readiness.stemPackageRequestRevision)
        assertEquals(prepared.requestRevision, readiness.pendingStemRequestRevision)
        assertEquals(prepared.requestRevision, readiness.stemReadyEventRevision)

        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.presentAudibleControl(
            owner,
            stemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 61L
        )
        fixture.scheduler.runNext()

        val installed = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageInstalled
        >().single { it.stemRevision == "prepared-source-v1" }
        assertEquals(prepared.requestRevision, installed.requestRevision)
        assertEquals(61L, installed.callbackSequence)

        val preparingCount = fixture.events.count {
            it is NativeEngineControllerEvent.StemPackagePreparing
        }
        val readyCount = fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        }
        val installedCount = fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageInstalled
        }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(tempo = .95f)
        })
        fixture.scheduler.runNext()

        val afterControl = requireNotNull(
            fixture.controller.diagnosticsSnapshot().controllerStemReadiness
        )
        assertEquals(prepared.requestRevision, afterControl.stemPackageRequestRevision)
        assertNull(afterControl.pendingStemRequestRevision)
        assertEquals(prepared.requestRevision, afterControl.stemReadyEventRevision)
        assertEquals(preparingCount, fixture.events.count {
            it is NativeEngineControllerEvent.StemPackagePreparing
        })
        assertEquals(readyCount, fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        })
        assertEquals(installedCount, fixture.events.count {
            it is NativeEngineControllerEvent.StemPackageInstalled
        })
    }

    @Test
    fun maximumStemLocationsUseFixedHistoryIdentitiesAndRetainLateControlCorrelation() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("bounded-stem-history"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        val maximumLocation = "/" + "漢".repeat(4_095)
        val stems = currentStems("bounded-stem-history").map { lane ->
            lane.copy(location = maximumLocation)
        }

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "bounded-stem-history-v1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "bounded-stem-history-v1" }
        assertTrue(ready.packageSignature.length > 24_000)
        val firstStemSnapshot = fixture.environment.snapshots.last {
            it.controls.sourceMode == NativeRuntimeSourceMode.STEMS
        }

        repeat(12) { index ->
            assertTrue(fixture.controller.updateDesired(token) { previous ->
                previous.copy(tempo = 1f + ((index + 1) / 100f))
            })
            fixture.scheduler.runNext()
        }

        val diagnostics = fixture.controller.diagnosticsSnapshot()
            .controllerSourceControlPublications
        assertEquals(8, diagnostics.size)
        assertTrue(diagnostics.none {
            it.nativeControlRevision == firstStemSnapshot.controls.revision
        })
        assertTrue(diagnostics.all { it.stemPackageSignature == ready.packageSignature })
        val retainedLengths = fixture.controller.retainedHistoryIdentityLengthsForTest()
        assertTrue(retainedLengths.isNotEmpty())
        assertTrue(retainedLengths.all { it == 64 })
        assertNotEquals(
            nativeTerminalEventIdentity(ready.packageSignature + "a"),
            nativeTerminalEventIdentity(ready.packageSignature + "b")
        )

        fixture.environment.presentAudibleControl(
            requireNotNull(fixture.environment.lastSelected()),
            firstStemSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 71L
        )
        fixture.scheduler.runNext()

        val installed = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageInstalled
        >().single { it.stemRevision == "bounded-stem-history-v1" }
        assertEquals(ready.packageSignature, installed.packageSignature)
        assertEquals(71L, installed.callbackSequence)
        assertTrue(fixture.controller.retainedHistoryIdentityLengthsForTest().all { it == 64 })
    }

    @Test
    fun cacheSealingBetweenReadinessChecksStillPublishesPausedStems() {
        val fixture = Fixture(requireNativeStemReadiness = true, currentSongCacheSealedByDefault = false)
        val token = fixture.controller.select(request("paused-cache-race", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.scheduler.runNext()
        val stems = currentStems("paused-cache-race")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(sourceMode = EngineSourceMode.STEMS, stemLanes = stems,
                stemRevision = "race-r1", stemControls = stems.associate { it.id to EngineStemControl() })
        })
        fixture.scheduler.runNext()
        assertEquals(NativeRuntimeSourceMode.ORIGINAL, fixture.environment.snapshots.last().controls.sourceMode)
        // Native finishes its cache after the first readiness probe in this actor turn.
        fixture.environment.sealCacheAfterNextCheck = true
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible })
        assertEquals(NativeRuntimeSourceMode.STEMS, fixture.environment.snapshots.last().controls.sourceMode)
        assertFalse(fixture.environment.snapshots.last().playing)
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.SourceModeApplied })
    }

    @Test
    fun pausedStemPreparationPublishesNativeControlsWithoutFabricatingAudibleEvidence() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("paused-stems", autoplay = false))
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(owner)
        fixture.scheduler.runNext()
        val stems = currentStems("paused-stems")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "paused-stems-r1",
                stemControls = stems.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()

        val preparedSnapshot = fixture.environment.snapshots.last()
        assertFalse(preparedSnapshot.playing)
        assertEquals(owner, preparedSnapshot.selectedOwnership)
        assertEquals(NativeRuntimeSourceMode.STEMS, preparedSnapshot.controls.sourceMode)
        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "paused-stems-r1" }
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.SourceModeApplied })
        assertTrue(fixture.events.none { it is NativeEngineControllerEvent.StemPackageInstalled })
        assertNull(fixture.environment.diagnostics().audibleControl)

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(playWhenReady = true)
        })
        fixture.scheduler.runNext()
        val playingSnapshot = fixture.environment.snapshots.last()
        assertTrue(playingSnapshot.playing)
        assertEquals(preparedSnapshot.controls.revision, playingSnapshot.controls.revision)
        fixture.environment.presentAudibleControl(
            owner,
            playingSnapshot.controls.revision,
            NativeRuntimeSourceMode.STEMS,
            callbackSequence = 71L
        )
        fixture.scheduler.runNext()

        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.SourceModeApplied &&
                it.token == token && it.sourceMode == EngineSourceMode.STEMS
        })
        val installed = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageInstalled
        >().single { it.stemRevision == "paused-stems-r1" }
        assertEquals(ready.packageSignature, installed.packageSignature)
        assertEquals(ready.requestRevision, installed.requestRevision)
        assertEquals(71L, installed.callbackSequence)
    }

    @Test
    fun nonCanonicalRoleAliasFailsBeforeAnyStemDecoderOpens() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("canonical-only"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val invalid = currentStems("invalid").map {
            if (it.normalizedRole == "vocals") it.copy(normalizedRole = "voice") else it
        }
        val openedBefore = fixture.environment.openedDecoderCount

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = invalid, stemRevision = "invalid-alias")
        })
        fixture.scheduler.runNext()

        assertEquals(openedBefore, fixture.environment.openedDecoderCount)
        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageFailed &&
                it.stemRevision == "invalid-alias" && it.recoverable
        })
    }

    @Test
    fun replacementKeepsOutgoingPhysicalRoleOrderAndControlsWhileNewBankIsUnready() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("role-replacement"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val v1 = listOf("vocals", "bass", "remaining").map { role ->
            EngineStemLane("old-$role", role, "/old-$role.flac", "old-hash-$role")
        }
        val controls = mapOf(
            "old-vocals" to EngineStemControl(gain = .25f, solo = true),
            "old-bass" to EngineStemControl(gain = .75f, muted = true),
            "old-remaining" to EngineStemControl(gain = .5f)
        )
        fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
            stemLanes = v1, stemRevision = "old", stemControls = controls) }
        fixture.scheduler.runNext()
        val outgoing = requireNotNull(fixture.environment.lastSelected())
        val installed = fixture.environment.snapshots.last().controls.stems
        val firstReplacementPublication = fixture.environment.snapshots.size
        fixture.environment.newDecodersExposePcmHead = false
        val v2 = listOf("piano", "remaining").map { role ->
            EngineStemLane("new-$role", role, "/new-$role.flac", "new-hash-$role")
        }
        fixture.controller.updateDesired(token) { it.copy(stemLanes = v2, stemRevision = "new",
            stemControls = v2.associate { lane -> lane.id to EngineStemControl(gain = .9f) }) }
        fixture.scheduler.runSteps(6)
        val outgoingPublications = fixture.environment.snapshots.drop(firstReplacementPublication)
            .filter { it.selectedOwnership == outgoing }
        assertTrue(outgoingPublications.isNotEmpty())
        outgoingPublications.forEach { assertEquals(installed, it.controls.stems) }
        val outgoingMappings = fixture.controller.diagnosticsSnapshot().controllerSourceControlPublications
            .filter { it.ownership == outgoing && it.stemLaneIds.isNotEmpty() }
        assertTrue(outgoingMappings.isNotEmpty())
        outgoingMappings.forEach { assertEquals(listOf("old-vocals", "old-bass", "old-remaining"), it.stemLaneIds) }
        assertTrue(fixture.environment.decoders.filter { it.spec.location.startsWith("/old-") }
            .all { !it.released })
    }

    @Test
    fun packageReplacementKeepsV1UntilOriginalFallbackEvidenceThenBuildsOnlyV2() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("replace"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)
        val v1 = currentStems("v1")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = v1,
                stemRevision = "stem-v1",
                stemControls = v1.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val v1Decoders = fixture.environment.decoders.filter { it.laneIndex in 1..6 }
        assertEquals(6, v1Decoders.size)
        val oldOwnership = requireNotNull(fixture.environment.lastSelected())

        val v2 = currentStems("v2")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = v2,
                stemRevision = "stem-v2",
                stemControls = v2.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil(maximumSteps = 16) {
            fixture.events.any {
                it is NativeEngineControllerEvent.StemPackagePreparing &&
                    it.stemRevision == "stem-v2"
            }
        }

        assertTrue(v1Decoders.none { it.released })
        assertTrue(fixture.environment.snapshots.any {
            it.selectedOwnership == oldOwnership &&
                it.controls.sourceMode == NativeRuntimeSourceMode.STEMS
        })
        assertEquals(8, fixture.environment.maximumOpenDecoderCount)
        val preparing = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackagePreparing
        >().single { it.stemRevision == "stem-v2" }
        assertNotNull(preparing.replacingPackageSignature)
        val fallback = requireNotNull(fixture.environment.lastSelected())

        fixture.environment.present(fallback, timelineFrame = 96_000L)
        fixture.scheduler.runNext()
        fixture.scheduler.runUntil(maximumSteps = 32) {
            fixture.events.any {
                it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                    it.stemRevision == "stem-v2"
            }
        }

        assertTrue(v1Decoders.all { it.released })
        val v2Decoders = fixture.environment.decoders.filter {
            it.laneIndex in 1..6 && it.spec.location.startsWith("/v2-")
        }
        val liveStems = fixture.environment.decoders.filter {
            !it.released && it.laneIndex in 1..6
        }
        assertEquals(6, v2Decoders.size)
        assertTrue(liveStems.isEmpty() || liveStems.size == 6)
        assertTrue(liveStems.all { it.spec.location.startsWith("/v2-") })
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
        val ready = fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible
        >().single { it.stemRevision == "stem-v2" }
        assertFalse(ready.retainedPriorPackage)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageInstalled })
    }

    @Test
    fun packageReplacementWaitsForTheFeederAndUsesItsFinalAcceptedNextCursor() {
        for (nextFrame in listOf(98_561L, 32_003L)) {
            val fixture = Fixture()
            val token = fixture.controller.select(request("accepted-replacement-$nextFrame"))
            fixture.scheduler.runNext()
            fixture.presentLatest(timelineFrame = 96_512L)
            val outgoing = requireNotNull(fixture.environment.lastSelected())
            val first = currentStems("accepted-v1")
            fixture.controller.updateDesired(token) { it.copy(sourceMode = EngineSourceMode.STEMS,
                stemLanes = first, stemRevision = "accepted-v1",
                stemControls = first.associate { lane -> lane.id to EngineStemControl() }) }
            fixture.scheduler.runNext()
            fixture.environment.sourceTransitionReady = false
            val second = currentStems("accepted-v2")
            fixture.controller.updateDesired(token) { it.copy(stemLanes = second, stemRevision = "accepted-v2",
                stemControls = second.associate { lane -> lane.id to EngineStemControl() }) }
            fixture.scheduler.runSteps(6)
            assertEquals("No replacement can precede the sole feeder's final receipt", outgoing,
                fixture.environment.lastSelected())
            fixture.environment.sourceTransitionNextFrame = nextFrame
            fixture.environment.sourceTransitionReady = true
            fixture.controller.signalNativeProgress()
            fixture.scheduler.runUntil(maximumSteps = 24) { fixture.environment.lastSelected() != outgoing }
            val snapshot = fixture.environment.snapshots.last()
            assertEquals(nextFrame, snapshot.banks[requireNotNull(snapshot.selectedBank).nativeCode].firstTimelineFrame)
            assertTrue(fixture.environment.sourceTransitionRequests.all { it.first == outgoing &&
                it.second.selectionToken == outgoing.selectionToken && it.second.generation > outgoing.generation })
        }
    }

    @Test
    fun seekDuringSelectedSameTokenStemFallbackTargetsOnlyThatGraphAndCannotPingPong() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("replacement-seek"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 96_000L)

        val v1 = currentStems("replacement-seek-v1")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = v1,
                stemRevision = "replacement-seek-v1",
                stemControls = v1.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val outgoingGraph = fixture.environment.decoders.filter {
            !it.released && it.role == NativeSongBankRole.ACTIVE
        }
        assertEquals(7, outgoingGraph.size)

        val v2 = currentStems("replacement-seek-v2")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                stemLanes = v2,
                stemRevision = "replacement-seek-v2",
                stemControls = v2.associate { it.id to EngineStemControl() }
            )
        })
        fixture.scheduler.runNext()
        val stagedOwnership = requireNotNull(fixture.environment.lastSelected())
        val stagedBank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val fallbackDecoder = fixture.environment.decoders.single {
            !it.released && it.bank == stagedBank && it.role == NativeSongBankRole.CANDIDATE &&
                it.laneIndex == 0
        }
        assertEquals(token.value, stagedOwnership.selectionToken)
        fixture.environment.operations.clear()
        val snapshotsBeforeSeek = fixture.environment.snapshots.size

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(
                requestedPositionMs = 7_000L,
                seekRevision = previous.seekRevision + 1L
            )
        })
        fixture.scheduler.runNext()
        val rebasedFallback = requireNotNull(fixture.environment.lastSelected())

        assertEquals(token.value, rebasedFallback.selectionToken)
        assertTrue(rebasedFallback.generation > stagedOwnership.generation)
        assertEquals(stagedBank, fixture.environment.snapshots.last().selectedBank)
        assertEquals(listOf(rebasedFallback.generation), fallbackDecoder.requestedSeekTargets)
        assertTrue(outgoingGraph.all { it.requestedSeekTargets.isEmpty() })
        assertEquals(1, fixture.environment.operations.count {
            it.startsWith("decoder-seek:${token.value}:")
        })
        assertTrue(fixture.environment.manualSelectionGateArmed)

        fixture.presentLatest(timelineFrame = 336_000L)
        fixture.scheduler.runSteps(3)

        assertEquals(rebasedFallback, fixture.ownerPublications().last().ownership)
        assertTrue(outgoingGraph.all { it.released })
        assertFalse(fallbackDecoder.released)
        assertEquals(NativeSongBankRole.ACTIVE, fallbackDecoder.role)
        val selectionsAfterSeek = fixture.environment.snapshots.drop(snapshotsBeforeSeek)
            .mapNotNull { it.selectedOwnership }
        assertTrue(selectionsAfterSeek.isNotEmpty())
        assertTrue(selectionsAfterSeek.toString(), selectionsAfterSeek.all {
            it == rebasedFallback
        })
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
    }

    @Test
    fun partialOpenFailureWaitsForExactDetachBeforeReleasingOnlyStemsAndRetrying() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("open-failure"))
        fixture.scheduler.runNext()
        fixture.presentLatest(timelineFrame = 48_000L)
        val owner = requireNotNull(fixture.environment.lastSelected())
        val bank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val original = fixture.environment.decoders.single { it.laneIndex == 0 }
        fixture.environment.appliedBankIntentRevisionOverrides[bank] =
            fixture.environment.snapshots.last().banks[bank.nativeCode].revision
        fixture.environment.failNextOpenAtLane(3)
        val stems = currentStems("retry")

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = stems, stemRevision = "stem-retry")
        })
        fixture.scheduler.runNext()
        val failedPartial = fixture.environment.decoders.filter { it.laneIndex in 1..2 }
        assertEquals(2, failedPartial.size)
        val detach = fixture.environment.snapshots.last().banks[bank.nativeCode]
        assertEquals(owner, detach.ownership)
        assertEquals(0L, detach.stemPackageHash)
        assertEquals(0, detach.stemLaneCount)
        assertTrue(failedPartial.none { it.cancelAttempted || it.released })
        assertFalse(original.cancelAttempted)
        assertTrue(original.requestedSeekTargets.isEmpty())
        fixture.environment.appliedBankIntentRevisionOverrides[bank] = detach.revision + 1L
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue("A different revision authorized optional-lane release",
            failedPartial.none { it.cancelAttempted || it.released })
        fixture.environment.appliedBankIntentRevisionOverrides.remove(bank)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue(failedPartial.all { it.cancelAttempted && it.released })
        assertEquals(owner, fixture.environment.lastSelected())
        assertEquals(listOf(original), fixture.environment.decoders.filterNot { it.released })
        assertFalse(original.cancelAttempted)
        assertTrue(original.requestedSeekTargets.isEmpty())
        assertEquals(1, fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageFailed
        >().count { it.stemRevision == "stem-retry" })
        assertFalse(fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "stem-retry"
        })

        val constructionsBeforeControls = fixture.environment.decoders.size
        for (control in listOf<(EngineDesiredState) -> EngineDesiredState>(
            { it.copy(tempo = 1.25f) }, { it.copy(semitoneShift = 2) },
            { it.copy(playWhenReady = false) }, { it.copy(stemControls = it.stemControls.mapValues { (_, value) -> value.copy(gain = .5f) }) }
        )) {
            assertTrue(fixture.controller.updateDesired(token, control))
            fixture.scheduler.runNext()
            assertEquals(constructionsBeforeControls, fixture.environment.decoders.size)
            assertEquals(0, fixture.environment.snapshots.last().banks[bank.nativeCode].stemLaneCount)
        }
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemRevision = "stem-retry", stemRetryRevision = previous.stemRetryRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != owner }
        val retryOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(retryOwner, 0L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(3)
        assertTrue(fixture.events.toString(), fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "stem-retry"
        })
        assertEquals(token.value, retryOwner.selectionToken)
        assertTrue(retryOwner.generation > owner.generation)
        assertTrue(original.released)
        assertTrue(original.requestedSeekTargets.isEmpty())
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
    }

    @Test
    fun pausedFailedPackageReattachmentCannotReuseQueuedBusOwnership() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("paused-package-failure", autoplay = false))
        fixture.scheduler.runNext()
        val originalOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.installPaused(originalOwner)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(stemLanes = currentStems("queued-A"), stemRevision = "queued-A", sourceMode = EngineSourceMode.STEMS)
        })
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 7 }
        fixture.environment.decoders.single { it.laneIndex == 4 }.failAsync("queued package A decoder failed")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil { fixture.environment.decoders.count { !it.released } == 1 }
        assertEquals(originalOwner, fixture.environment.lastSelected())
        val beforeReplacement = fixture.environment.snapshots.size
        assertTrue(fixture.controller.updateDesired(token) {
            it.copy(stemLanes = currentStems("replacement-B"), stemRevision = "replacement-B",
                sourceMode = EngineSourceMode.STEMS)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != originalOwner }
        val replacement = requireNotNull(fixture.environment.lastSelected())
        assertEquals(originalOwner.selectionToken, replacement.selectionToken)
        assertTrue(replacement.generation > originalOwner.generation)
        assertTrue("Queued A buses cannot acquire B controls on A's old generation",
            fixture.environment.snapshots.drop(beforeReplacement).all { snapshot ->
                snapshot.banks.none { bank -> bank.ownership == originalOwner && bank.stemLaneCount > 0 }
            })
        fixture.environment.installPaused(replacement)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runUntil {
            fixture.controller.diagnosticsSnapshot().controllerPublicOwnership == replacement
        }
        assertFalse(fixture.environment.snapshots.last().playing)
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
    }

    @Test
    fun asyncStemFailureDetachesInPlaceWithoutReopeningOrSeekingTheAudibleOriginal() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("async-failure"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        val stems = currentStems("async")
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = stems, stemRevision = "stem-async")
        })
        fixture.scheduler.runNext()
        val owner = requireNotNull(fixture.environment.lastSelected())
        val bank = requireNotNull(fixture.environment.snapshots.last().selectedBank)
        val original = fixture.environment.decoders.single { it.laneIndex == 0 }
        val optional = fixture.environment.decoders.filter { it.laneIndex > 0 }
        fixture.environment.appliedBankIntentRevisionOverrides[bank] =
            fixture.environment.snapshots.last().banks[bank.nativeCode].revision
        optional.single { it.laneIndex == 4 }.failAsync("synthetic codec crash")
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        val detach = fixture.environment.snapshots.last().banks[bank.nativeCode]
        assertEquals(0, detach.stemLaneCount)
        assertEquals(0L, detach.stemPackageHash)
        assertEquals(owner, detach.ownership)
        assertEquals(7, fixture.environment.decoders.size)
        assertTrue(optional.none { it.cancelAttempted || it.released })
        fixture.environment.present(owner, timelineFrame = 48_000L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(optional.none { it.cancelAttempted || it.released })
        assertFalse(original.cancelAttempted)
        fixture.environment.appliedBankIntentRevisionOverrides.remove(bank)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertEquals(1, fixture.events.filterIsInstance<
            NativeEngineControllerEvent.DecoderFailed
        >().count { it.laneIndex == 4 })
        assertEquals(1, fixture.events.filterIsInstance<
            NativeEngineControllerEvent.StemPackageFailed
        >().count { it.stemRevision == "stem-async" })
        assertTrue(optional.all { it.cancelAttempted && it.released })
        assertEquals(listOf(original), fixture.environment.decoders.filterNot { it.released })
        assertEquals(owner, fixture.environment.lastSelected())
        assertFalse(original.cancelAttempted)
        assertTrue(original.requestedSeekTargets.isEmpty())
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })

        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemRevision = "stem-async", stemRetryRevision = previous.stemRetryRevision + 1L)
        })
        fixture.scheduler.runUntil { fixture.environment.lastSelected() != owner }
        val retryOwner = requireNotNull(fixture.environment.lastSelected())
        fixture.environment.present(retryOwner, 0L)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runSteps(3)
        assertTrue(fixture.events.toString(), fixture.events.any {
            it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible &&
                it.stemRevision == "stem-async" && it.requestRevision > 2L
        })
        assertEquals(token.value, retryOwner.selectionToken)
        assertTrue(retryOwner.generation > owner.generation)
        assertTrue(fixture.environment.maximumOpenDecoderCount <= 9)
    }

    @Test
    fun failedOptionalDecoderJoinRetainsItsUnsafeLaneAndFailsExplicitly() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("failed-stem-join"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            previous.copy(stemLanes = currentStems("failed-stem-join"), stemRevision = "failed-stem-join")
        })
        fixture.scheduler.runNext()
        val failed = fixture.environment.decoders.single { it.laneIndex == 4 }
        failed.joinWatchdogExpired = true
        failed.failAsync("synthetic codec crash")
        fixture.events.clear()
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(failed.cancelAttempted)
        assertFalse("A decoder that did not join lost its native lane", failed.released)
        assertTrue("Optional retirement must never wait on the command actor", failed.joinTimeouts.all { it == 0L })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertTrue(fixture.controller.updateDesired(token) { it.copy(playWhenReady = false) })
        fixture.scheduler.runNext()
        assertFalse(fixture.environment.snapshots.last().playing)
        assertFalse(failed.released)
        fixture.scheduler.advanceTimeBy(NATIVE_DECODER_JOIN_WATCHDOG_MS)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()
        assertTrue(fixture.events.any { it is NativeEngineControllerEvent.FatalRecoveryRequired })
        assertFalse(fixture.events.any { it is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible })
    }

    @Test
    fun metronomeAndStemGainUseSharedNonBoostedMappings() {
        val fixture = Fixture()
        val token = fixture.controller.select(request("gain"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        assertTrue(fixture.controller.updateDesired(token) { previous ->
            val stems = currentStems()
            previous.copy(
                sourceMode = EngineSourceMode.STEMS,
                stemLanes = stems,
                stemRevision = "gain-v1",
                stemControls = stems.associate { lane ->
                    lane.id to EngineStemControl(gain = if (lane.id == "vocals") 1f else .82f)
                },
                metronome = EngineMetronomeConfig(enabled = true, volume = 1f)
            )
        })
        fixture.scheduler.runNext()
        val controls = fixture.environment.snapshots.last().controls

        assertEquals(NativeRuntimeSourceMode.STEMS, controls.sourceMode)
        assertEquals(1f, controls.metronome.gain, .0001f)
        assertEquals(1f, controls.stems[0].gain, .0001f)
        assertEquals(.82f, controls.stems[1].gain, .0001f)
    }

    @Test
    fun taskRemovalStuckDecoderSilencesAndNeverReleasesItsLane() {
        val fixture = Fixture(stuckDecoders = true)
        fixture.controller.select(request("stuck"))
        fixture.scheduler.runNext()
        fixture.presentLatest()
        fixture.environment.operations.clear()

        val stop = fixture.controller.stopForTaskRemoval()
        fixture.scheduler.runNext()

        assertNull(stop.status())
        assertTrue(fixture.environment.decoders.single().cancelAttempted)
        assertEquals(0, fixture.environment.releasedDecoderCount)
        fixture.scheduler.advanceTimeBy(NATIVE_DECODER_JOIN_WATCHDOG_MS)
        fixture.controller.signalNativeProgress()
        fixture.scheduler.runNext()

        assertTrue(fixture.events.any {
            it is NativeEngineControllerEvent.FatalRecoveryRequired
        })
        assertTrue(fixture.environment.operations.contains("stop-join-runtime"))
        assertTrue(fixture.environment.operations.any { it.startsWith("immediate-silence:") })
        assertEquals(0, fixture.environment.releasedDecoderCount)
        assertTrue(fixture.environment.decoders.single().cancelAttempted)
        assertFalse(fixture.environment.decoders.single().safeToReleaseLane())
        assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
    }
}
