package app.stemwise.playback.engine

import app.stemwise.model.StemRoleContract
import app.stemwise.playback.StemPresentationPackageAuthority
import app.stemwise.playback.projectedSourcePresentation
import app.stemwise.playback.projectedStemPresentationValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

import app.stemwise.playback.engine.NativePlaybackSessionTestSupport.Companion.TEST_THREAD_WATCHDOG_MS
import app.stemwise.playback.engine.NativePlaybackSessionTestSupport.Companion.TEST_THREAD_CLEANUP_MS

private fun NativeAudioPlaybackSessionAdapter.select(
    request: NativeEngineSelectionRequest,
    metadata: PlaybackPublicationMetadata,
    requestTag: Long = 0L
): EngineSelectionToken = checkNotNull(trySelect(request, metadata, requestTag)) {
    "The native playback session requires a clean process restart"
}


@app.stemwise.qualification.ManagedAudioTest
internal class NativePlaybackSessionOwnershipTest : NativePlaybackSessionTestSupport() {
    @Test
    fun fatalBeforeFirstOwnerPublishesAnIndependentTerminalReasonAndCannotRevive() {
        val harness = Harness()
        harness.adapter.select(request("never-public"), metadata("never-public", 1L))
        assertNull(harness.adapter.snapshot().publication.committed)
        harness.engine.emit(NativeEngineControllerEvent.FatalRecoveryRequired("early native fault"))
        val state = harness.adapter.snapshot()
        assertEquals(NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED, state.lifecycle)
        assertEquals("early native fault", state.terminalFailure)
        assertFalse(requireNotNull(state.latestRequest).controls.playWhenReady)
        assertFalse(harness.adapter.play())
        assertEquals(1, harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.OutputUnavailable>().size)
    }

    @Test
    fun fourHundredRapidSelectionsPublishOnlyTheLatestExactCallbackOwner() {
        val harness = Harness()
        val tokens = (0 until 400).map { index ->
            harness.adapter.select(request("song-$index"), metadata("song-$index", 1L))
        }

        assertEquals(400, harness.engine.selections.size)
        assertNull(harness.adapter.snapshot().publication.committed)
        assertEquals(tokens.last(), harness.adapter.snapshot().latestRequest?.selection?.token)
        assertNull(harness.adapter.snapshot().latestRequest?.exactOwnership)

        tokens.dropLast(1).forEachIndexed { index, token ->
            harness.engine.publishOwner(token, generation = index + 10L, callbackSequence = index + 1L)
        }
        assertNull(harness.adapter.snapshot().publication.committed)

        val exactOwnership = harness.engine.publishOwner(
            tokens.last(),
            generation = 9_999L,
            callbackSequence = 40_000L
        )
        val state = harness.adapter.snapshot()
        val committed = requireNotNull(state.publication.committed)

        assertEquals("song-399", committed.metadata.songId)
        assertEquals(tokens.last(), committed.selection.token)
        assertEquals(exactOwnership, committed.owner.ownership)
        assertEquals(40_000L, committed.owner.callbackSequence)
        assertEquals(exactOwnership, state.latestRequest?.exactOwnership)
    }

    @Test
    fun requestAndEarlyEventsRemainNonPublicUntilExactOwnerPublication() {
        val harness = Harness()
        val token = harness.adapter.select(request("pending"), metadata("pending", 1L))

        harness.engine.emit(NativeEngineControllerEvent.Position(token, 12_345L, 8L))
        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(token, 1L, EngineSourceMode.ORIGINAL)
        )
        assertNull(harness.adapter.snapshot().publication.committed)
        assertNull(harness.adapter.snapshot().latestRequest?.exactOwnership)

        val ownership = harness.engine.publishOwner(token, 88L, 9L)
        val committed = requireNotNull(harness.adapter.snapshot().publication.committed)

        assertEquals(ownership, committed.owner.ownership)
        assertEquals(9L, committed.owner.callbackSequence)
        assertEquals(12_345L, committed.transport.presentedPositionMs)
        assertEquals(8L, committed.transport.positionCallbackSequence)
        assertEquals(1L, committed.source.appliedRevision)
        assertEquals(EngineSourceMode.ORIGINAL, committed.source.appliedMode)
    }

    @Test
    fun exactLoopAvailabilityIsOwnerFencedAndTransitionsFailClosed() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-cache"), metadata("loop-cache", 1L))
        val owner = harness.engine.publishOwner(token, generation = 80L, callbackSequence = 1L)

        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                NativeRuntimeOwnership(token.value, 81L),
                available = true
            )
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(token, owner, available = true)
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        assertTrue(harness.adapter.seekToIfCurrent(lease, 12_000L) != null)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(token, owner, available = true)
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        val seekOwner = NativeRuntimeOwnership(token.value, 82L)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(token, seekOwner, available = true)
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        assertEquals(
            seekOwner,
            harness.engine.publishOwner(token, generation = 82L, callbackSequence = 2L)
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        assertTrue(harness.adapter.seekToIfCurrent(lease, 13_000L) != null)
        val staleRapidSeekOwner = NativeRuntimeOwnership(token.value, 83L)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                staleRapidSeekOwner,
                available = true
            )
        )
        assertTrue(harness.adapter.seekToIfCurrent(lease, 14_000L) != null)
        val rapidSeekOwner = NativeRuntimeOwnership(token.value, 84L)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                rapidSeekOwner,
                available = true
            )
        )
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                staleRapidSeekOwner,
                available = true
            )
        )
        harness.engine.publishOwner(token, generation = 83L, callbackSequence = 3L)
        assertFalse(harness.adapter.snapshot().loopAvailable)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                staleRapidSeekOwner,
                available = true
            )
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        assertEquals(
            rapidSeekOwner,
            harness.engine.publishOwner(token, generation = 84L, callbackSequence = 4L)
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        harness.adapter.requestRouteRecovery()
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token,
                rapidSeekOwner,
                available = true
            )
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        val routeOwner = harness.engine.publishOwner(token, generation = 85L, callbackSequence = 5L)
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(token, routeOwner, available = true)
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        val replacement = harness.adapter.select(
            request("loop-cache-next"),
            metadata("loop-cache-next", 1L)
        )
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(token, routeOwner, available = true)
        )
        assertFalse(harness.adapter.snapshot().loopAvailable)
        val replacementOwner = harness.engine.publishOwner(
            replacement,
            generation = 86L,
            callbackSequence = 6L
        )
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                replacement,
                replacementOwner,
                available = true
            )
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        harness.engine.emit(NativeEngineControllerEvent.FatalRecoveryRequired("fatal"))
        assertFalse(harness.adapter.snapshot().loopAvailable)
    }

    @Test
    fun sameAudioMetadataPreservesControlsButDifferentSongStartsNeutral() {
        val harness = Harness()
        val first = harness.adapter.select(request("first"), metadata("first", 1L))
        harness.engine.publishOwner(first, generation = 100L, callbackSequence = 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, .8f, 7))
        assertTrue(harness.adapter.setLoopIfCurrent(lease, EngineLoopConfig(1_000L, 5_000L)))
        assertTrue(
            harness.adapter.setMetronomeProfileIfCurrent(
                lease,
                EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 137f,
                    beatGridMs = longArrayOf(0L, 438L, 876L),
                    downbeats = booleanArrayOf(true, false, false)
                )
            )
        )

        assertTrue(harness.adapter.updateMetadata(metadata("first", 2L)))
        val sameAudio = requireNotNull(harness.adapter.snapshot().latestRequest)
        assertEquals(first, sameAudio.selection.token)
        assertEquals(.8f, sameAudio.controls.tempo)
        assertEquals(7, sameAudio.controls.semitoneShift)
        assertEquals(EngineLoopConfig(1_000L, 5_000L), sameAudio.controls.loop)
        assertEquals(listOf(0L, 438L, 876L), sameAudio.controls.metronome.beatGridMs)

        val second = harness.adapter.select(request("second"), metadata("second", 1L))
        val differentSong = requireNotNull(harness.adapter.snapshot().latestRequest)
        assertEquals(second, differentSong.selection.token)
        assertEquals(1f, differentSong.controls.tempo)
        assertEquals(0, differentSong.controls.semitoneShift)
        assertNull(differentSong.controls.loop)
        assertFalse(differentSong.controls.metronome.enabled)
        assertFalse(differentSong.controls.metronome.songSynced)
        assertTrue(differentSong.controls.metronome.beatGridMs.isEmpty())
        assertTrue(differentSong.controls.metronome.downbeats.isEmpty())
        assertEquals(EngineSourceMode.ORIGINAL, differentSong.controls.sourceMode)
        assertTrue(differentSong.controls.stemLanes.isEmpty())
    }

    @Test
    fun staleOwnerAndControlEventsCannotReplaceTheCommittedLatestIdentity() {
        val harness = Harness()
        val first = harness.adapter.select(request("first"), metadata("first", 1L))
        harness.engine.publishOwner(first, 200L, 1L)
        val latest = harness.adapter.select(request("latest"), metadata("latest", 1L))
        harness.engine.publishOwner(latest, 201L, 2L)
        val latestOwnership = requireNotNull(
            harness.adapter.snapshot().publication.committed
        ).owner.ownership
        harness.engine.emit(
            NativeEngineControllerEvent.LoopAvailabilityChanged(latest, latestOwnership, available = true)
        )
        assertTrue(harness.adapter.snapshot().loopAvailable)

        harness.engine.emit(NativeEngineControllerEvent.Position(first, 50_000L, 9_999L))
        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(first, 1L, EngineSourceMode.ORIGINAL)
        )
        harness.engine.publishOwner(first, 202L, 3L)

        val committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals("latest", committed.metadata.songId)
        assertEquals(latest, committed.selection.token)
        assertEquals(0L, committed.transport.presentedPositionMs)
        assertEquals(201L, committed.owner.ownership.generation)
        assertTrue("A stale owner publication must preserve the current owner's readiness",
            harness.adapter.snapshot().loopAvailable)
    }

    @Test
    fun manualSelectionInvalidatesOutgoingAndRejectsItsLateRebaseAndEnd() {
        val harness = Harness()
        val a = harness.adapter.select(request("a"), metadata("a", 1L))
        val oldOwner = harness.engine.publishOwner(a, 10L, 1L)
        val b = harness.adapter.select(request("b"), metadata("b", 1L))

        assertNull(harness.adapter.snapshot().publication.committed)
        assertEquals(b, harness.adapter.snapshot().latestRequest?.selection?.token)

        harness.engine.publishOwner(a, 11L, 2L)
        val state = harness.adapter.snapshot()
        assertNull(state.publication.committed)
        assertEquals(b, state.latestRequest?.selection?.token)

        harness.engine.emit(NativeEngineControllerEvent.AudibleEnd(a, 1L, oldOwner))
        assertTrue(
            harness.effects.none { it is NativeAudioPlaybackSessionEffect.AudibleEnd }
        )
        harness.engine.emit(
            NativeEngineControllerEvent.AudibleEnd(
                a,
                2L,
                NativeRuntimeOwnership(a.value, 11L)
            )
        )
        assertTrue(harness.effects.none { it is NativeAudioPlaybackSessionEffect.AudibleEnd })
    }

    @Test
    fun failedManualSelectionRemainsSilentAndNeverRestoresOutgoingOwner() {
        val harness = Harness()
        val a = harness.adapter.select(request("a"), metadata("a", 1L))
        harness.engine.publishOwner(a, 10L, 1L)
        val b = harness.adapter.select(
            request("broken"),
            metadata("broken", 1L),
            requestTag = 73L
        )

        assertNull(harness.adapter.snapshot().publication.committed)

        harness.engine.emit(
            NativeEngineControllerEvent.SelectionFailed(b, "Broken local original")
        )

        val state = harness.adapter.snapshot()
        assertNull(state.latestRequest)
        assertNull(state.publication.committed)
        assertNull(state.committedControls)
        assertNull(state.publication.pendingSelection)
        assertEquals(
            listOf("Broken local original"),
            harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.SelectionFailed>()
                .map { it.cause }
        )
        assertEquals(
            listOf(73L),
            harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.SelectionFailed>()
                .map { it.requestTag }
        )
    }

    @Test
    fun pendingOwnerWithoutALoopAcceptsItsExactTransportSeek() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("pending-seek").copy(startPositionMs = 4_000L),
            metadata("pending-seek", 1L)
        )
        val lease = requireNotNull(harness.adapter.acquireCommandLease("pending-seek"))

        assertTrue(harness.adapter.seekToIfCurrent(lease, 5_000L) != null)
        assertEquals(token, harness.engine.desired?.selection?.token)
        assertEquals(5_000L, harness.engine.desired?.requestedPositionMs)
        assertEquals(0, harness.engine.transportSeekLoopUpdates)
    }

    @Test
    fun unknownLikelyNextStaysDormantUntilExactAutomaticSuccessorEvidence() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("outgoing"), metadata("outgoing", 1L))
        harness.engine.publishOwner(outgoing, 20L, 1L)
        val successorRequest = request("successor")
        val successorMetadata = metadata("successor", 1L)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(successorRequest.identity(), successorMetadata.toNative())
            )
        )

        val dormant = harness.adapter.snapshot()
        assertEquals(outgoing, dormant.latestRequest?.selection?.token)
        assertEquals(outgoing, dormant.publication.committed?.selection?.token)
        assertNull(dormant.publication.pendingSelection)
        assertEquals(successorRequest.identity(), dormant.likelyNextIdentity)

        val successor = selection(EngineSelectionToken(2L), successorRequest)
        val ownership = harness.engine.publishExternalOwner(
            selection = successor,
            metadata = successorMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 21L,
            callbackSequence = 2L
        )

        val promoted = harness.adapter.snapshot()
        assertEquals(promoted.latestRequest?.controls, promoted.committedControls)
        assertEquals(1f, requireNotNull(promoted.committedControls).tempo, 0f)
        assertEquals(successor, promoted.latestRequest?.selection)
        assertEquals(ownership, promoted.latestRequest?.exactOwnership)
        assertEquals(successor, promoted.publication.committed?.selection)
        assertEquals(ownership, promoted.publication.committed?.owner?.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            promoted.publication.committed?.owner?.kind
        )
        assertNull(promoted.publication.pendingSelection)
        assertNull(promoted.likelyNextIdentity)
        assertNull(harness.engine.lastAutomaticEndPlan)
        assertEquals(2, harness.engine.automaticEndPlanCalls)
        assertFalse(harness.engine.selections.containsKey(successor.token))
    }

    @Test
    fun alreadyReducedAutomaticSuccessorIsAdoptedWithoutClearingItsCommit() {
        val harness = Harness()
        val outgoing = harness.adapter.select(
            request("adopt-reduced-outgoing"),
            metadata("adopt-reduced-outgoing", 1L)
        )
        harness.engine.publishOwner(outgoing, generation = 22L, callbackSequence = 1L)
        val targetRequest = request("adopt-reduced-target")
        val targetMetadata = metadata("adopt-reduced-target", 1L)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(targetRequest.identity(), targetMetadata.toNative())
            )
        )
        val target = selection(EngineSelectionToken(2L), targetRequest)
        val ownership = harness.engine.publishExternalOwner(
            selection = target,
            metadata = targetMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 23L,
            callbackSequence = 2L
        )
        harness.engine.stageAlreadyCurrent(target, targetMetadata.toNative())
        val physicalSelectionsBefore = harness.engine.selectCalls

        val adopted = harness.adapter.select(targetRequest, targetMetadata, requestTag = 41L)

        val state = harness.adapter.snapshot()
        assertEquals(target.token, adopted)
        assertEquals(physicalSelectionsBefore, harness.engine.selectCalls)
        assertEquals(target, state.latestRequest?.selection)
        assertEquals(ownership, state.latestRequest?.exactOwnership)
        assertEquals(target, state.publication.committed?.selection)
        assertEquals(ownership, state.publication.committed?.owner?.ownership)
        assertNull(state.publication.pendingSelection)
    }

    @Test
    fun queuedAutomaticPublicationBindsAfterAlreadyCurrentAdmissionWithoutSilencingOutgoing() {
        val harness = Harness()
        val outgoing = harness.adapter.select(
            request("adopt-queued-outgoing"),
            metadata("adopt-queued-outgoing", 1L)
        )
        harness.engine.publishOwner(outgoing, generation = 24L, callbackSequence = 1L)
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(
            requireNotNull(harness.adapter.acquireCommandLease()), 1.3f, 3))
        val targetRequest = request("adopt-queued-target")
        val targetMetadata = metadata("adopt-queued-target", 1L)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(targetRequest.identity(), targetMetadata.toNative())
            )
        )
        val target = selection(EngineSelectionToken(2L), targetRequest)
        harness.engine.stageAlreadyCurrent(target, targetMetadata.toNative())
        val physicalSelectionsBefore = harness.engine.selectCalls

        val adopted = harness.adapter.select(targetRequest, targetMetadata, requestTag = 42L)

        val awaitingPublication = harness.adapter.snapshot()
        assertEquals(target.token, adopted)
        assertEquals(physicalSelectionsBefore, harness.engine.selectCalls)
        assertEquals(outgoing, awaitingPublication.publication.committed?.selection?.token)
        assertEquals(1.3f, requireNotNull(awaitingPublication.committedControls).tempo, 0f)
        assertEquals(3, awaitingPublication.committedControls.semitoneShift)
        assertEquals(target, awaitingPublication.latestRequest?.selection)
        assertNull(awaitingPublication.publication.pendingSelection)
        assertNull(awaitingPublication.automaticEndPlan)
        assertNull(awaitingPublication.likelyNextIdentity)

        val ownership = harness.engine.publishExternalOwner(
            selection = target,
            metadata = targetMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 25L,
            callbackSequence = 2L
        )

        val committed = harness.adapter.snapshot()
        assertEquals(target, committed.latestRequest?.selection)
        assertEquals(ownership, committed.latestRequest?.exactOwnership)
        assertEquals(target, committed.publication.committed?.selection)
        assertEquals(ownership, committed.publication.committed?.owner?.ownership)
        assertNull(committed.publication.pendingSelection)
        assertNotNull(
            harness.adapter.setPlayWhenReadyIfCurrent(target.token, playWhenReady = true)
        )
    }

    @Test
    fun distinctSongsSharingAudioRevisionPromoteOnlyTheExactAutomaticSuccessor() {
        val harness = Harness()
        val sharedRevision = "shared-canonical-audio-v1"
        val outgoingRequest = NativeEngineSelectionRequest(
            songId = "canonical-a",
            audioRevision = sharedRevision,
            originalLocation = "/songs/canonical-a.flac",
            durationMs = 60_000L
        )
        val successorRequest = NativeEngineSelectionRequest(
            songId = "canonical-b",
            audioRevision = sharedRevision,
            originalLocation = "/songs/canonical-b.flac",
            durationMs = 61_000L
        )
        val outgoingMetadata = PlaybackPublicationMetadata(
            songId = outgoingRequest.songId,
            audioRevision = sharedRevision,
            revision = 1L,
            payloadKey = "ui:canonical-a"
        )
        val successorMetadata = PlaybackPublicationMetadata(
            songId = successorRequest.songId,
            audioRevision = sharedRevision,
            revision = 2L,
            payloadKey = "ui:canonical-b"
        )
        val outgoing = harness.adapter.select(outgoingRequest, outgoingMetadata)
        harness.engine.publishOwner(outgoing, generation = 70L, callbackSequence = 1L)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(successorRequest.identity(), successorMetadata.toNative())
            )
        )

        val dormant = harness.adapter.snapshot()
        assertNotEquals(outgoingRequest.identity(), successorRequest.identity())
        assertEquals(sharedRevision, dormant.latestRequest?.selection?.audioRevision)
        assertEquals(successorRequest.identity(), dormant.likelyNextIdentity)

        val successor = selection(EngineSelectionToken(2L), successorRequest)
        val ownership = harness.engine.publishExternalOwner(
            selection = successor,
            metadata = successorMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 71L,
            callbackSequence = 2L
        )

        val promoted = harness.adapter.snapshot()
        assertEquals(successor, promoted.latestRequest?.selection)
        assertEquals(successor, promoted.publication.committed?.selection)
        assertEquals(successorMetadata, promoted.publication.committed?.metadata)
        assertEquals(ownership, promoted.publication.committed?.owner?.ownership)
        assertNull(promoted.likelyNextIdentity)
        assertFalse(harness.engine.selections.containsKey(successor.token))

        // A late automatic publication for the retired owner cannot exploit the shared revision
        // to consume or replace the already committed canonical-b publication.
        harness.engine.publishExternalOwner(
            selection = selection(EngineSelectionToken(3L), outgoingRequest),
            metadata = outgoingMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 72L,
            callbackSequence = 3L
        )
        val afterLateOutgoing = harness.adapter.snapshot()
        assertEquals(successor, afterLateOutgoing.latestRequest?.selection)
        assertEquals(successor, afterLateOutgoing.publication.committed?.selection)
        assertEquals(ownership, afterLateOutgoing.publication.committed?.owner?.ownership)
    }

    @Test
    fun restartCurrentPlanCrossesAdapterWithoutCreatingLikelyNextOrNewOwnerAuthority() {
        val harness = Harness()
        val currentRequest = request("repeat-current")
        val current = harness.adapter.select(currentRequest, metadata("repeat-current", 1L))
        harness.engine.publishOwner(current, 20L, 1L)
        val plan = NativeEngineAutomaticEndPlan.RestartCurrent(
            currentRequest.identity(),
            currentRequest.durationMs
        )

        harness.adapter.setAutomaticEndPlan(plan)

        val dormant = harness.adapter.snapshot()
        assertEquals(plan, harness.engine.lastAutomaticEndPlan)
        assertEquals(plan, dormant.automaticEndPlan)
        assertNull(dormant.likelyNextIdentity)
        assertEquals(current, dormant.latestRequest?.selection?.token)
        assertEquals(current, dormant.publication.committed?.selection?.token)

        harness.engine.publishExternalOwner(
            selection = selection(EngineSelectionToken(2L), currentRequest),
            metadata = metadata("repeat-current", 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 21L,
            callbackSequence = 2L
        )
        assertEquals(current, harness.adapter.snapshot().publication.committed?.selection?.token)
    }

    @Test
    fun manualSelectionResolutionRevokesStagedAutomaticSuccessorBeforeIo() {
        val harness = Harness()
        val outgoingRequest = request("manual-resolution-a")
        val outgoing = harness.adapter.select(
            outgoingRequest,
            metadata("manual-resolution-a", 1L)
        )
        harness.engine.publishOwner(outgoing, 30L, 1L)
        val successorRequest = request("manual-resolution-b")
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    successorRequest.identity(),
                    metadata("manual-resolution-b", 1L).toNative()
                )
            )
        )
        assertTrue(harness.adapter.automaticSuccessorAuthorizationsForTest().isNotEmpty())

        harness.adapter.supersedeAutomaticEndForManualSelection()

        assertEquals(1, harness.engine.manualSelectionIntentAdmissionCalls)
        assertTrue(harness.adapter.automaticSuccessorAuthorizationsForTest().isEmpty())
        assertNull(harness.adapter.snapshot().automaticEndPlan)
        assertNull(harness.adapter.snapshot().likelyNextIdentity)
        assertNull(harness.engine.lastAutomaticEndPlan)

        harness.engine.publishExternalOwner(
            selection = selection(EngineSelectionToken(2L), successorRequest),
            metadata = metadata("manual-resolution-b", 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 31L,
            callbackSequence = 2L
        )
        assertEquals(outgoing, harness.adapter.snapshot().publication.committed?.selection?.token)
    }

    @Test
    fun ordinaryAndMismatchedUnknownOwnersCannotPromoteDormantLikelyNext() {
        fun assertRejectedUnknownOwner(
            likelySongId: String,
            publishedSongId: String,
            kind: NativeEngineOwnerPublicationKind
        ) {
            val harness = Harness()
            val outgoing = harness.adapter.select(request("outgoing"), metadata("outgoing", 1L))
            harness.engine.publishOwner(outgoing, 30L, 1L)
            val likelyRequest = request(likelySongId)
            harness.adapter.setAutomaticEndPlan(
                NativeEngineAutomaticEndPlan.NextOwner(
                    NativeEngineQueueItem(
                        likelyRequest.identity(),
                        metadata(likelySongId, 1L).toNative()
                    )
                )
            )
            val publishedRequest = request(publishedSongId)
            harness.engine.publishExternalOwner(
                selection = selection(EngineSelectionToken(2L), publishedRequest),
                metadata = metadata(publishedSongId, 1L).toNative(),
                kind = kind,
                generation = 31L,
                callbackSequence = 2L
            )

            val state = harness.adapter.snapshot()
            assertEquals(outgoing, state.latestRequest?.selection?.token)
            assertEquals(outgoing, state.publication.committed?.selection?.token)
            assertNull(state.publication.pendingSelection)
            assertEquals(likelyRequest.identity(), state.likelyNextIdentity)
        }

        assertRejectedUnknownOwner(
            likelySongId = "ordinary",
            publishedSongId = "ordinary",
            kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED
        )
        assertRejectedUnknownOwner(
            likelySongId = "expected",
            publishedSongId = "mismatched",
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED
        )

    }

    @Test
    fun exactAutomaticPublicationWinsOverAQueueHintThatChangedAfterCallbackHandoff() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("outgoing"), metadata("outgoing", 1L))
        harness.engine.publishOwner(outgoing, 40L, 1L)
        val audibleSuccessor = request("audible-successor")
        val newerHint = request("newer-hint")
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    audibleSuccessor.identity(),
                    metadata("audible-successor", 1L).toNative()
                )
            )
        )

        // Native has already made audibleSuccessor physical owner, but its serialized callback
        // publication is still waiting for the adapter gate while queue policy advances the hint.
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(newerHint.identity(), metadata("newer-hint", 1L).toNative())
            )
        )
        val exactOwnership = harness.engine.publishExternalOwner(
            selection = selection(EngineSelectionToken(2L), audibleSuccessor),
            metadata = metadata("audible-successor", 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 41L,
            callbackSequence = 2L
        )

        val state = harness.adapter.snapshot()
        assertEquals("audible-successor", state.latestRequest?.selection?.songId)
        assertEquals("audible-successor", state.publication.committed?.selection?.songId)
        assertEquals(exactOwnership, state.publication.committed?.owner?.ownership)
        assertEquals(newerHint.identity(), state.likelyNextIdentity)
    }

    @Test
    fun maximumQueueRetainsFixedAutomaticAuthorizationsUntilDelayedCommitAndStop() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("bounded-outgoing"), metadata("bounded-outgoing", 1L))
        harness.engine.publishOwner(outgoing, 42L, 1L)
        fun maximumLocation(index: Int): String =
            "/" + "漢".repeat(4_091) + index.toString().padStart(4, '0')
        fun successorRequest(index: Int): NativeEngineSelectionRequest {
            val songId = "bounded-next-$index"
            return request(songId).copy(originalLocation = maximumLocation(index))
        }

        val delayed = successorRequest(0)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(delayed.identity(), metadata(delayed.songId, 1L).toNative())
            )
        )
        var latest = delayed
        for (index in 1 until 8_192) {
            latest = successorRequest(index)
            harness.adapter.setAutomaticEndPlan(
                NativeEngineAutomaticEndPlan.NextOwner(
                    NativeEngineQueueItem(latest.identity(), metadata(latest.songId, 1L).toNative())
                )
            )
        }

        val retained = harness.adapter.automaticSuccessorAuthorizationsForTest()
        assertEquals(8_192, retained.size)
        assertTrue(retained.all { it.length == 64 })
        assertNotEquals(delayed.identity().stableIdentity(), latest.identity().stableIdentity())

        harness.engine.publishExternalOwner(
            selection = selection(EngineSelectionToken(2L), delayed),
            metadata = metadata(delayed.songId, 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 43L,
            callbackSequence = 2L
        )
        assertEquals(delayed.songId, harness.adapter.snapshot().publication.committed?.selection?.songId)
        assertTrue(harness.adapter.automaticSuccessorAuthorizationsForTest().isEmpty())

        val afterCommit = request("after-bounded-commit")
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(afterCommit.identity(), metadata(afterCommit.songId, 1L).toNative())
            )
        )
        assertEquals(1, harness.adapter.automaticSuccessorAuthorizationsForTest().size)
        val stop = harness.adapter.stop()
        harness.engine.completeStop(stop)
        assertTrue(harness.adapter.automaticSuccessorAuthorizationsForTest().isEmpty())
    }

    @Test
    fun pauseAtAutomaticBoundaryCannotBeOverwrittenBySuccessorAutoplay() {
        val harness = Harness()
        val outgoing = harness.adapter.select(
            request("pause-outgoing"),
            metadata("pause-outgoing", 1L)
        )
        harness.engine.publishOwner(outgoing, 45L, 1L)
        val pausedTarget = request("pause-target", autoplay = false)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    pausedTarget.identity(),
                    metadata("pause-target", 1L).toNative()
                )
            )
        )
        assertTrue(harness.adapter.pause())

        val successor = selection(EngineSelectionToken(2L), pausedTarget)
        harness.engine.publishExternalOwner(
            selection = successor,
            metadata = metadata("pause-target", 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 46L,
            callbackSequence = 2L
        )

        val state = harness.adapter.snapshot()
        assertEquals(successor.token, state.latestRequest?.selection?.token)
        assertFalse(requireNotNull(state.latestRequest).controls.playWhenReady)
        assertFalse(requireNotNull(state.publication.committed).transport.playWhenReady)
        assertEquals(outgoing to false, harness.engine.audibleTransportIntents.last())
    }

    @Test
    fun physicallyAdoptedPausedAutomaticSuccessorPublishesWithoutFakeCallbackEvidence() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("paused-outgoing"), metadata("paused-outgoing", 1L))
        harness.engine.publishOwner(outgoing, 50L, 1L)
        val targetRequest = request("paused-target", autoplay = false)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    targetRequest.identity(),
                    metadata("paused-target", 1L).toNative()
                )
            )
        )
        assertTrue(harness.adapter.pause())

        val target = selection(EngineSelectionToken(2L), targetRequest)
        val ownership = harness.engine.publishExternalOwner(
            selection = target,
            metadata = metadata("paused-target", 1L).toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED,
            generation = 51L,
            callbackSequence = 2L,
            evidence = null
        )

        val committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(target, committed.selection)
        assertEquals(ownership, committed.owner.ownership)
        assertEquals(
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED,
            committed.owner.kind
        )
        assertNull(committed.owner.callbackSequence)
        assertFalse(committed.transport.playWhenReady)
    }

    @Test
    fun mismatchedAutomaticSuccessorEvidenceCannotConsumeOrExposeDormantLikelyNext() {
        fun assertRejectedEvidence(evidence: (EngineSelection) -> EngineAudibleOwnerEvidence) {
            val harness = Harness()
            val outgoing = harness.adapter.select(request("outgoing"), metadata("outgoing", 1L))
            harness.engine.publishOwner(outgoing, 50L, 1L)
            val successorRequest = request("successor")
            val successorMetadata = metadata("successor", 1L)
            harness.adapter.setAutomaticEndPlan(
                NativeEngineAutomaticEndPlan.NextOwner(
                    NativeEngineQueueItem(successorRequest.identity(), successorMetadata.toNative())
                )
            )
            val successor = selection(EngineSelectionToken(2L), successorRequest)
            harness.engine.publishExternalOwner(
                selection = successor,
                metadata = successorMetadata.toNative(),
                kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
                generation = 51L,
                callbackSequence = 2L,
                evidence = evidence(successor)
            )

            val state = harness.adapter.snapshot()
            assertEquals(outgoing, state.latestRequest?.selection?.token)
            assertEquals(outgoing, state.publication.committed?.selection?.token)
            assertNull(state.publication.pendingSelection)
            assertEquals(successorRequest.identity(), state.likelyNextIdentity)
        }

        assertRejectedEvidence { successor ->
            EngineAudibleOwnerEvidence(
                token = successor.token,
                songId = "wrong-song",
                callbackSequence = 2L,
                firstOutputFrame = 512L,
                finalPcmFrames = 256,
                outputFingerprintSequence = 2L
            )
        }
        assertRejectedEvidence { successor ->
            EngineAudibleOwnerEvidence(
                token = EngineSelectionToken(successor.token.value + 1L),
                songId = successor.songId,
                callbackSequence = 2L,
                firstOutputFrame = 512L,
                finalPcmFrames = 256,
                outputFingerprintSequence = 2L
            )
        }
    }

    @Test
    fun taskRemovalRejectsLateTransportAndQueueUpdatesUntilAnExplicitSelection() {
        val harness = Harness()
        val current = harness.adapter.select(request("task-owner"), metadata("task-owner", 1L))
        harness.engine.publishOwner(current, 302L, 1L)
        val desiredUpdatesBeforeStop = harness.engine.desiredUpdateCalls
        val automaticPlanCallsBeforeStop = harness.engine.automaticEndPlanCalls

        val stop = harness.adapter.stop()
        assertEquals(NativeAudioPlaybackSessionLifecycle.STOPPING, harness.adapter.snapshot().lifecycle)
        assertFalse(harness.adapter.pause())
        assertFalse(harness.adapter.play())
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(
                    request("late-next").identity(),
                    metadata("late-next", 1L).toNative()
                )
            )
        )
        assertEquals(desiredUpdatesBeforeStop, harness.engine.desiredUpdateCalls)
        assertEquals(automaticPlanCallsBeforeStop, harness.engine.automaticEndPlanCalls)

        harness.engine.completeStop(stop)
        val replacement = harness.adapter.select(
            request("task-replacement"),
            metadata("task-replacement", 1L)
        )
        assertTrue(harness.adapter.pause())
        assertEquals(replacement, harness.engine.desired?.selection?.token)
    }

    @Test
    fun scrubOwnerDoesNotStartASelectionThatWasPaused() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("silent-preview", autoplay = false),
            metadata("silent-preview", 1L)
        )
        harness.engine.publishOwner(token, generation = 406L, callbackSequence = 1L)

        assertNotNull(harness.adapter.setPlayWhenReadyIfCurrent(token, playWhenReady = false))
        assertTrue(harness.adapter.previewSeekTo(token, 12_000L))
        assertFalse(requireNotNull(harness.engine.desired).playWhenReady)
        assertEquals(12_000L, requireNotNull(harness.engine.desired).requestedPositionMs)
    }

    @Test
    fun exactScrubOperationsCannotAffectAnAutomaticallyPromotedOwner() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("scrub-outgoing"), metadata("scrub-outgoing", 1L))
        harness.engine.publishOwner(outgoing, generation = 410L, callbackSequence = 1L)
        assertNotNull(harness.adapter.setPlayWhenReadyIfCurrent(outgoing, playWhenReady = false))
        val successorRequest = request("scrub-successor")
        val successorMetadata = metadata("scrub-successor", 1L)
        harness.adapter.setAutomaticEndPlan(
            NativeEngineAutomaticEndPlan.NextOwner(
                NativeEngineQueueItem(successorRequest.identity(), successorMetadata.toNative())
            )
        )
        val successor = selection(EngineSelectionToken(2L), successorRequest)
        harness.engine.publishExternalOwner(
            selection = successor,
            metadata = successorMetadata.toNative(),
            kind = NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            generation = 411L,
            callbackSequence = 2L
        )
        harness.engine.desired = automaticSuccessorDesiredState(successor)
        val nativeBeforeRejectedWork = harness.engine.desired
        val publicBeforeRejectedWork = harness.adapter.snapshot()
        val combinedUpdatesBefore = harness.engine.combinedTransportUpdateCalls

        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(outgoing, playWhenReady = false))
        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(outgoing, playWhenReady = true))
        assertFalse(harness.adapter.previewSeekTo(outgoing, 21_000L))
        assertNull(
            harness.adapter.finishScrubWithReceipt(outgoing, 22_000L, playWhenReady = true)
        )

        assertEquals(nativeBeforeRejectedWork, harness.engine.desired)
        assertSame(publicBeforeRejectedWork, harness.adapter.snapshot())
        assertEquals(combinedUpdatesBefore, harness.engine.combinedTransportUpdateCalls)
        assertEquals(successor.token, harness.adapter.snapshot().latestRequest?.selection?.token)
        assertEquals(successor.token, harness.adapter.snapshot().publication.committed?.selection?.token)
        assertNotNull(harness.adapter.setPlayWhenReadyIfCurrent(successor.token, playWhenReady = true))
        assertTrue(requireNotNull(harness.adapter.snapshot().latestRequest).controls.playWhenReady)
    }

    @Test
    fun pendingManualOwnerCannotUseExactScrubOperationsBeforeItCommits() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("manual-outgoing"), metadata("manual-outgoing", 1L))
        harness.engine.publishOwner(outgoing, generation = 420L, callbackSequence = 1L)
        val pending = harness.adapter.select(request("manual-pending"), metadata("manual-pending", 1L))
        val desiredBefore = harness.engine.desired

        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(outgoing, playWhenReady = false))
        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(outgoing, playWhenReady = true))
        assertFalse(harness.adapter.previewSeekTo(outgoing, 10_000L))
        assertNull(
            harness.adapter.finishScrubWithReceipt(outgoing, 10_000L, playWhenReady = false)
        )
        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(pending, playWhenReady = false))
        assertNull(harness.adapter.setPlayWhenReadyIfCurrent(pending, playWhenReady = true))
        assertFalse(harness.adapter.previewSeekTo(pending, 20_000L))
        assertNull(
            harness.adapter.finishScrubWithReceipt(pending, 20_000L, playWhenReady = true)
        )

        assertEquals(desiredBefore, harness.engine.desired)
        assertEquals(pending, harness.adapter.snapshot().latestRequest?.selection?.token)
        assertNull(harness.adapter.snapshot().publication.committed)
    }

    @Test
    fun scrubReceiptRejectsMismatchedTokenOwnership() {
        val token = EngineSelectionToken(440L)
        assertRejected {
            NativeAudioPlaybackOwnerReceipt(
                token = token,
                ownership = NativeRuntimeOwnership(token.value + 1L, generation = 1L)
            )
        }
    }
}
