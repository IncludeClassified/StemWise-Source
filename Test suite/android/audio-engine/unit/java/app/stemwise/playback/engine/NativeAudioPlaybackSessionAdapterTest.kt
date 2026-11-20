package app.stemwise.playback.engine

import app.stemwise.model.StemRoleContract
import app.stemwise.playback.StemPresentationPackageAuthority
import app.stemwise.playback.projectedSourcePresentation
import app.stemwise.playback.projectedStemPresentationValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
internal class NativeAudioPlaybackSessionAdapterTest : NativePlaybackSessionTestSupport() {
    @Test fun firstSelectionAdmitsInitialControlsWithoutPostSelectionMutationsAndOwnsTiming() {
        val harness = Harness()
        val beats = longArrayOf(100L, 600L, 1_100L)
        val downbeats = booleanArrayOf(true, false, false)
        val meters = mutableListOf(EngineMeterSegment(0L, 2_000L, 3, 4))
        val initial = NativeEngineInitialControls(1.25f, -3,
            EngineMetronomeConfig(true, true, 120f, .37f, 2, 3, 4, beats, downbeats, meters))
        val token = harness.adapter.select(request("initial", autoplay = false).copy(initialControls = initial),
            metadata("initial", 1L))
        val first = requireNotNull(harness.engine.desired)
        assertEquals(1L, first.revision)
        assertEquals(1.25f, first.tempo, 0f)
        assertEquals(-3, first.semitoneShift)
        assertEquals(initial.metronome, first.metronome)
        assertEquals(0, harness.engine.desiredUpdateCalls)
        assertEquals(1, harness.engine.selectCalls)
        beats[0] = 200L
        downbeats[0] = false
        meters.clear()
        assertEquals(100L, first.metronome.beatGridMs[0])
        assertTrue(first.metronome.downbeats[0])
        assertEquals(1, first.metronome.meterSegments.size)
        first.metronome.beatGridMs[0] = 300L
        val projected = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        assertEquals(1.25f, projected.tempo, 0f)
        assertEquals(-3, projected.semitoneShift)
        assertEquals(listOf(100L, 600L, 1_100L), projected.metronome.beatGridMs)
        assertTrue(projected.metronome.downbeats[0])
        val lease = requireNotNull(harness.adapter.acquireCommandLease("initial"))
        assertEquals(token, lease.token)
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, .75f, 2))
        harness.adapter.select(request("next-initial"), metadata("next-initial", 1L))
        assertFalse(harness.adapter.setTempoAndKeyIfCurrent(lease, 1.5f, 4))
        val neutral = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        assertEquals(1f, neutral.tempo, 0f)
        assertEquals(0, neutral.semitoneShift)
        assertFalse(neutral.metronome.enabled)
    }

    @Test fun malformedInitialTimingCannotReplaceTheCurrentSelection() {
        val harness = Harness()
        val token = harness.adapter.select(request("kept-initial"), metadata("kept-initial", 1L))
        val beats = longArrayOf(0L, 500L)
        val controls = NativeEngineInitialControls(metronome = EngineMetronomeConfig(
            beatGridMs = beats, downbeats = booleanArrayOf(true, false)))
        beats[1] = 0L
        assertThrows(IllegalArgumentException::class.java) {
            harness.adapter.trySelect(request("rejected-initial").copy(initialControls = controls),
                metadata("rejected-initial", 1L))
        }
        assertEquals(1, harness.engine.selectCalls)
        assertEquals(token, harness.adapter.snapshot().latestRequest?.selection?.token)
        assertThrows(IllegalArgumentException::class.java) { NativeEngineInitialControls(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { NativeEngineInitialControls(semitoneShift = 13) }
    }

    @Test fun trimmedSelectionAndAllSeekIngressKeepAbsolutePositionsInsideTheWindow() {
        val harness = Harness()
        val request = request("trimmed", autoplay = false).copy(playbackStartMs = 10_000L, playbackEndMs = 40_000L)
        val token = harness.adapter.select(request, metadata("trimmed", 1L))
        val selected = checkNotNull(harness.adapter.snapshot().latestRequest)
        assertEquals(60_000L, selected.selection.durationMs)
        assertEquals(10_000L, selected.selection.startPositionMs)
        assertEquals(10_000L, selected.selection.playbackStartMs)
        assertEquals(40_000L, selected.selection.playbackEndMs)
        assertEquals(10_000L, selected.controls.requestedPositionMs)
        assertEquals(selected.selection, harness.engine.desired?.selection)
        harness.engine.publishOwner(token, 10L, 1L)
        assertEquals(10_000L, harness.adapter.snapshot().publication.committed?.transport?.presentedPositionMs)
        val lease = checkNotNull(harness.adapter.acquireCommandLease())
        assertEquals(10_000L, harness.adapter.resolveRelativeSeek(Long.MIN_VALUE)?.positionMs)
        assertEquals(40_000L, harness.adapter.resolveRelativeSeek(Long.MAX_VALUE)?.positionMs)
        assertEquals(10_000L, harness.adapter.seekToIfCurrent(lease, 0L)?.positionMs)
        assertEquals(10_000L, harness.engine.lastDesiredRequest?.requestedPositionMs)
        assertEquals(39_999L, harness.adapter.seekToIfCurrent(lease, Long.MAX_VALUE)?.positionMs)
        assertEquals(39_999L, harness.engine.lastDesiredRequest?.requestedPositionMs)
        assertTrue(harness.adapter.previewSeekTo(token, 0L))
        assertEquals(10_000L, harness.engine.lastDesiredRequest?.requestedPositionMs)
        assertNotNull(harness.adapter.finishScrubWithReceipt(token, 60_000L, false))
        assertEquals(39_999L, harness.engine.lastDesiredRequest?.requestedPositionMs)
        assertEquals(39_999L, harness.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
    }

    @Test fun trimmedLoopRejectsOutsideBoundsWithoutChangingTheCurrentLoopOrTiming() {
        val harness = Harness()
        val token = harness.adapter.select(request("trim-loop").copy(
            playbackStartMs = 10_000L, playbackEndMs = 40_000L), metadata("trim-loop", 1L))
        harness.engine.publishOwner(token, 10L, 1L)
        val lease = checkNotNull(harness.adapter.acquireCommandLease())
        val grid = longArrayOf(9_000L, 10_000L, 11_000L)
        assertTrue(harness.adapter.setMetronomeProfileIfCurrent(lease,
            EngineMetronomeConfig(beatGridMs = grid, downbeats = booleanArrayOf(true, false, false))))
        val loop = EngineLoopConfig(12_000L, 20_000L)
        assertTrue(harness.adapter.setLoopIfCurrent(lease, loop))
        val accepted = harness.engine.explicitLoopState
        val controls = harness.adapter.snapshot().latestRequest?.controls
        for (invalid in listOf(EngineLoopConfig(9_999L, 20_000L), EngineLoopConfig(12_000L, 40_001L))) {
            assertThrows(IllegalArgumentException::class.java) { harness.adapter.setLoopIfCurrent(lease, invalid) }
            assertEquals(accepted, harness.engine.explicitLoopState)
            assertEquals(controls, harness.adapter.snapshot().latestRequest?.controls)
        }
        assertEquals(grid.toList(), controls?.metronome?.beatGridMs)
        assertTrue(harness.adapter.setLoopIfCurrent(lease, EngineLoopConfig(10_000L, 40_000L)))
        assertEquals(10_000L, harness.engine.explicitLoopState?.config?.startMs)
        assertEquals(40_000L, harness.engine.explicitLoopState?.config?.endMs)
    }

    @Test fun automaticSuccessorRetainsItsWindowAndRejectsAnUnadvertisedWindow() {
        val harness = Harness()
        val current = request("trim-outgoing").copy(playbackStartMs = 2_000L, playbackEndMs = 55_000L)
        val token = harness.adapter.select(current, metadata("trim-outgoing", 1L))
        harness.engine.publishOwner(token, 10L, 1L)
        val next = request("trim-successor").copy(playbackStartMs = 8_000L, playbackEndMs = 30_000L)
        val nextMetadata = metadata("trim-successor", 1L)
        val nativeMetadata = NativeEngineMetadata(next.songId, next.audioRevision, nextMetadata.revision, nextMetadata.payloadKey)
        val item = NativeEngineQueueItem(next.identity(), nativeMetadata)
        harness.adapter.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.RestartCurrent(
            current.identity(), current.playbackEndMs, fallbackNext = item))
        val repeat = harness.engine.lastAutomaticEndPlan as NativeEngineAutomaticEndPlan.RestartCurrent
        assertEquals(2_000L, repeat.identity.playbackStartMs)
        assertEquals(55_000L, repeat.identity.playbackEndMs)
        assertEquals(next.identity(), repeat.fallbackNext?.identity)
        harness.adapter.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(item))
        val successor = EngineSelection(EngineSelectionToken(2L), next.songId, next.audioRevision,
            next.originalLocation, next.durationMs, next.playbackStartMs, next.autoplay,
            originalContentSha256 = next.originalContentSha256,
            playbackStartMs = next.playbackStartMs, playbackEndMs = next.playbackEndMs)
        harness.engine.publishExternalOwner(successor.copy(playbackEndMs = 31_000L), nativeMetadata,
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED, 11L, 2L)
        assertEquals(token, harness.adapter.snapshot().latestRequest?.selection?.token)
        harness.engine.publishExternalOwner(successor, nativeMetadata,
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED, 12L, 3L)
        val promoted = harness.adapter.snapshot()
        assertEquals(successor, promoted.publication.committed?.selection)
        assertEquals(successor, promoted.latestRequest?.selection)
        assertEquals(8_000L, promoted.committedControls?.requestedPositionMs)
        assertEquals(8_000L, promoted.publication.committed?.transport?.presentedPositionMs)
        assertEquals(EngineSourceMode.ORIGINAL, promoted.committedControls?.sourceMode)
        assertEquals(1f, checkNotNull(promoted.committedControls).tempo, 0f)
        assertNull(promoted.committedControls.loop)
    }

    @Test fun pausedPreparedMixChangesDesiredControlsWithoutInventingInstallation() {
        val harness = Harness()
        val lease = preparedPausedMix(harness, "paused-mix")
        val before = checkNotNull(harness.adapter.snapshot().latestRequest).controls
        val mix = before.stemControls.mapValues { (id, _) -> EngineStemControl(.4f, muted = id == "paused-mix-vocals", solo = id == "paused-mix-bass") }
        assertTrue(mix.values.any { it.muted })
        assertTrue(mix.values.any { it.solo })
        assertTrue(harness.adapter.commitPausedPreparedStemMix(lease, mix))
        val after = checkNotNull(harness.adapter.snapshot().latestRequest).controls
        assertEquals(before.copy(revision = after.revision, stemControls = mix), after)
        assertFalse(after.playWhenReady)
        val committed = checkNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, committed.stems.phase)
        assertNull(committed.stems.installed)
        assertEquals(EngineSourceMode.ORIGINAL, committed.source.appliedMode)
        assertNull(harness.adapter.beginFrameBoundedStemMixPreviewIfCurrent(lease.command, 0))
        assertEquals(0, harness.engine.stemMixPreviewBegins)
        assertTrue(harness.adapter.pausedPreparedStemMixLeaseIsCurrent(lease))
        for (bad in listOf(lease.copy(sourceRevision = lease.sourceRevision + 1L),
            lease.copy(packageSignature = "wrong"), lease.copy(stemRevision = "wrong"),
            lease.copy(packageRequestRevision = lease.packageRequestRevision + 1L),
            lease.copy(ownership = lease.ownership.copy(generation = lease.ownership.generation + 1L)),
            lease.copy(laneIds = setOf("foreign")))) {
            assertFalse(harness.adapter.commitPausedPreparedStemMix(bad, mix))
            assertEquals(after, harness.adapter.snapshot().latestRequest?.controls)
        }
        assertFalse(harness.adapter.commitPausedPreparedStemMix(lease, mix - mix.keys.first()))
        assertEquals(after, harness.adapter.snapshot().latestRequest?.controls)
    }

    @Test fun pausedPreparedMixRejectsPlayPackageReplacementAndAnotherOwner() {
        for (change in listOf("play", "package", "owner")) {
            val harness = Harness()
            val lease = preparedPausedMix(harness, "stale-paused-$change")
            val mix = checkNotNull(harness.adapter.snapshot().latestRequest).controls.stemControls
            when (change) {
                "play" -> assertTrue(harness.adapter.play())
                "package" -> assertTrue(harness.adapter.configureStemPackageIfCurrent(lease.command,
                    "replacement-revision", stems("stale-paused-$change"), true))
                "owner" -> {
                    val next = harness.adapter.select(request("replacement", autoplay = false), metadata("replacement", 1L))
                    harness.engine.publishOwner(next, 101L, 2L)
                }
            }
            val beforeRejected = harness.adapter.snapshot().latestRequest?.controls
            assertFalse(harness.adapter.pausedPreparedStemMixLeaseIsCurrent(lease))
            assertFalse(harness.adapter.commitPausedPreparedStemMix(lease, mix))
            assertEquals(beforeRejected, harness.adapter.snapshot().latestRequest?.controls)
        }
    }

    private fun preparedPausedMix(harness: Harness, song: String): PausedPreparedStemMixLease {
        val token = harness.adapter.select(request(song, autoplay = false), metadata(song, 1L))
        harness.engine.publishOwner(token, 100L, 1L)
        val command = checkNotNull(harness.adapter.acquireCommandLease())
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(command, 1.25f, 3))
        assertTrue(harness.adapter.setLoopIfCurrent(command, EngineLoopConfig(2_000L, 5_000L)))
        assertTrue(harness.adapter.configureStemPackageIfCurrent(command, "paused-stems", stems(song), true))
        val committed = checkNotNull(harness.adapter.snapshot().publication.committed)
        val requested = checkNotNull(committed.stems.desired)
        harness.engine.emit(NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(token,
            requested.packageSignature, requested.stemRevision, requested.requestRevision, false))
        harness.engine.emit(NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(token,
            committed.source.desiredRevision, EngineSourceMode.STEMS))
        return checkNotNull(harness.adapter.capturePausedPreparedStemMixLease(command))
    }

    @Test
    fun conflatedOwnerPublicationsRetainTheirControlsMetadataAndStopBoundary() = runBlocking {
        val harness = Harness()
        val received = Channel<NativeAudioPlaybackSessionState>(Channel.UNLIMITED)
        val resumeCollector = Channel<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            harness.adapter.state.collect { state ->
                received.send(state)
                resumeCollector.receive()
            }
        }
        try {
            received.receive() // Hold the actual StateFlow collector while A -> B -> C completes.
            repeat(3) { index ->
                val song = "owner-$index"
                val token = harness.adapter.select(request(song), metadata(song, 1L))
                harness.engine.publishOwner(token, 10L + index, 1L + index)
                assertTrue(harness.adapter.setTempoAndKeyIfCurrent(
                    requireNotNull(harness.adapter.acquireCommandLease()), 1f + index * .1f, index))
            }
            harness.adapter.updateMetadata(metadata("owner-2", 2L))
            resumeCollector.send(Unit)
            val last = withTimeout(TEST_THREAD_WATCHDOG_MS) { received.receive() }
            assertEquals("owner-2", last.publication.committed?.selection?.songId)
            assertEquals(2L, last.publication.committed?.metadata?.revision)
            assertEquals(1.2f, requireNotNull(last.committedControls).tempo, 0f)
            assertEquals(2, last.committedControls.semitoneShift)
            assertSame(last.latestRequest?.controls, last.committedControls)
            val stop = harness.adapter.stop()
            assertSame(last.committedControls, harness.adapter.snapshot().committedControls)
            harness.engine.completeStop(stop)
            assertNull(harness.adapter.snapshot().committedControls)
            // Previously received immutable ownership cannot change after a later STOP.
            assertEquals("owner-2", last.publication.committed?.selection?.songId)
            assertEquals(1.2f, last.committedControls.tempo, 0f)
        } finally {
            collector.cancel()
            received.close()
            resumeCollector.close()
        }
    }

    @Test
    fun relativeSeekCapturesCurrentOwnerPositionAndRejectsAHandoffBeforeApplication() {
        val harness = Harness()
        val first = harness.adapter.select(request("A"), metadata("A", 1L))
        harness.engine.publishOwner(first, 10L, 1L)
        harness.engine.emit(NativeEngineControllerEvent.Position(first, 60_000L, 2L))
        val staleFacade = harness.adapter.snapshot()
        val second = harness.adapter.select(request("B"), metadata("B", 2L))
        harness.engine.publishOwner(second, 11L, 3L)
        // No facade observation between A's 60s snapshot and B's relative command.
        assertEquals(60_000L, staleFacade.publication.committed?.transport?.presentedPositionMs)
        val admission = requireNotNull(harness.adapter.resolveRelativeSeek(5_000L))
        assertEquals(second, admission.lease.token)
        assertEquals(5_000L, admission.positionMs)
        assertNotNull(harness.adapter.seekToIfCurrent(admission.lease, admission.positionMs))
        val next = requireNotNull(harness.adapter.resolveRelativeSeek(5_000L))
        assertEquals(10_000L, next.positionMs)
        assertNotNull(harness.adapter.seekToIfCurrent(next.lease, next.positionMs))
        assertEquals(5_000L, harness.adapter.resolveRelativeSeek(-5_000L)?.positionMs)
        val third = harness.adapter.select(request("C"), metadata("C", 3L))
        assertNull(harness.adapter.seekToIfCurrent(next.lease, next.positionMs))
        assertEquals(third, harness.engine.desired?.selection?.token)
        assertEquals(0L, harness.engine.desired?.requestedPositionMs)
        assertEquals(0L, harness.adapter.resolveRelativeSeek(Long.MIN_VALUE)?.positionMs)
        assertEquals(harness.engine.desired?.selection?.durationMs,
            harness.adapter.resolveRelativeSeek(Long.MAX_VALUE)?.positionMs)
    }

    @Test
    fun controlEditsReuseOwnedMaximumTimingUntilItIsReplaced() {
        val harness = Harness()
        harness.adapter.select(request("timing"), metadata("timing", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val grid = LongArray(72_001) { it * 100L }
        val accents = BooleanArray(grid.size) { it % 4 == 0 }
        assertTrue(harness.adapter.setMetronomeProfileIfCurrent(lease,
            EngineMetronomeConfig(beatGridMs = grid, downbeats = accents)))
        val timing = requireNotNull(harness.adapter.snapshot().latestRequest).controls.metronome
        grid[0] = 99L
        accents[0] = false
        repeat(20) { index ->
            assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, 1f + index * .01f, index % 12))
            assertTrue(harness.adapter.setMetronomeVolumeIfCurrent(lease, index * .01f))
            val next = requireNotNull(harness.adapter.snapshot().latestRequest).controls.metronome
            assertSame(timing.beatGridMs, next.beatGridMs)
            assertSame(timing.downbeats, next.downbeats)
            assertEquals(0L, next.beatGridMs.first())
            assertTrue(next.downbeats.first())
        }
        assertTrue(harness.adapter.setMetronomeProfileIfCurrent(lease,
            EngineMetronomeConfig(beatGridMs = longArrayOf(10, 20), downbeats = booleanArrayOf(true, false))))
        assertEquals(listOf(10L, 20L), requireNotNull(harness.adapter.snapshot().latestRequest).controls.metronome.beatGridMs)
    }

    @Test
    fun seekAdmissionRetainsItsOwnTokenRevisionAndClampedTargetAcrossLaterCommands() {
        val harness = Harness()
        val token = harness.adapter.select(request("seek-receipt"), metadata("seek-receipt", 1L))
        // Admission is valid before a native generation/publication exists.
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val receipt = requireNotNull(harness.adapter.seekToIfCurrent(lease, Long.MAX_VALUE))
        assertEquals(token, receipt.token)
        assertEquals(1L, receipt.seekRevision)
        assertEquals(requireNotNull(harness.engine.desired).selection.durationMs - 1L, receipt.positionMs)
        assertEquals(requireNotNull(harness.engine.desired).requestedPositionMs, receipt.positionMs)
        assertEquals(requireNotNull(harness.engine.desired).revision,
            requireNotNull(harness.adapter.snapshot().latestRequest).controls.revision)
        val later = requireNotNull(harness.adapter.seekToIfCurrent(lease, -1L))
        assertEquals(2L, later.seekRevision)
        assertEquals(0L, later.positionMs)
        harness.adapter.select(request("next"), metadata("next", 2L))
        assertTrue(harness.adapter.seekToIfCurrent(lease, 500L) == null)
        assertEquals(token, receipt.token)
        assertEquals(1L, receipt.seekRevision)
        assertTrue(receipt.positionMs > 0L)
    }

    @Test
    fun actorHoldDoesNotRetainAdapterGateWhileAnActorCallbackPublishes() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("hold-callback"),
            metadata("hold-callback", 1L)
        )
        harness.engine.publishOwner(token, generation = 11L, callbackSequence = 1L)
        val callbackStarted = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        var callbackThread: Thread? = null
        harness.engine.holdAction = {
            callbackThread = Thread({
                callbackStarted.countDown()
                try {
                    harness.engine.emit(
                        NativeEngineControllerEvent.Position(
                            token = token,
                            positionMs = 321L,
                            callbackSequence = 7L
                        )
                    )
                } finally {
                    callbackFinished.countDown()
                }
            }, "StemWise-NativeEngine-hold-regression").apply {
                isDaemon = true
                start()
            }
            callbackStarted.await(1L, TimeUnit.SECONDS) &&
                callbackFinished.await(1L, TimeUnit.SECONDS)
        }

        var held = false
        try {
            held = harness.adapter.holdActorForSelectionBurstForTest()
            assertTrue("Actor callback could not enter the adapter while hold awaited its barrier", held)
            assertTrue(callbackFinished.await(1L, TimeUnit.SECONDS))
        } finally {
            if (held) harness.adapter.releaseActorForSelectionBurstForTest()
            callbackThread?.let { callback ->
                callback.join(TEST_THREAD_WATCHDOG_MS)
                if (callback.isAlive) {
                    callback.interrupt()
                    callback.join(TEST_THREAD_CLEANUP_MS)
                }
            }
        }

        assertFalse(requireNotNull(callbackThread).isAlive)
        assertEquals(
            321L,
            harness.adapter.snapshot().publication.committed?.transport?.presentedPositionMs
        )
        assertEquals(1, harness.engine.holdCalls)
        assertEquals(1, harness.engine.releaseHoldCalls)
    }

    @Test
    fun actorHoldIsReleasedWhenTheSessionStopsBeingActiveDuringItsBarrier() {
        val harness = Harness()
        harness.engine.holdAction = {
            harness.engine.emit(NativeEngineControllerEvent.Closed)
            true
        }

        assertFalse(harness.adapter.holdActorForSelectionBurstForTest())
        assertEquals(NativeAudioPlaybackSessionLifecycle.CLOSED, harness.adapter.snapshot().lifecycle)
        assertEquals(1, harness.engine.holdCalls)
        assertEquals(1, harness.engine.releaseHoldCalls)
    }

    @Test
    fun controlsTranslateToOneConflatedDesiredSnapshotAndStableIntentRevisions() {
        val harness = Harness()
        val token = harness.adapter.select(request("controls"), metadata("controls", 1L))
        harness.engine.publishOwner(token, 100L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val lanes = stems("controls")

        assertFalse(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS))
        assertRejected {
            harness.adapter.setStemMixIfCurrent(
                lease,
                mapOf("not-attached" to EngineStemControl())
            )
        }
        assertTrue(harness.adapter.pause())
        assertTrue(harness.adapter.seekToIfCurrent(lease, 55_000L) != null)
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease,
                "stem-r1",
                lanes,
                selectStems = true
            )
        )
        val mix = lanes.associate { lane ->
            lane.id to EngineStemControl(
                gain = if (StemRoleContract.normalize(lane.normalizedRole) == "vocals") .33f else .74f,
                solo = StemRoleContract.normalize(lane.normalizedRole) == "guitar"
            )
        }
        assertEquals(setOf(.33f, .74f), mix.values.map { it.gain }.toSet())
        assertTrue(mix.values.any { it.solo })
        assertTrue(harness.adapter.setStemMixIfCurrent(lease, mix))
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, .5f, 12))
        assertTrue(
            harness.adapter.setLoopIfCurrent(
                lease,
                EngineLoopConfig(1_000L, 12_000L),
                inputEventAtMonotonicMicros = 123_000L
            )
        )
        val beatGrid = longArrayOf(500L, 1_000L, 1_500L)
        val accents = booleanArrayOf(true, false, false)
        assertTrue(
            harness.adapter.setMetronomeProfileIfCurrent(
                lease,
                EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 140f,
                    subdivision = 2,
                    numerator = 6,
                    denominator = 8,
                    beatGridMs = beatGrid,
                    downbeats = accents
                )
            )
        )
        assertTrue(harness.adapter.setMetronomeVolumeIfCurrent(lease, .9f))
        beatGrid[0] = 99_999L
        accents[0] = false
        assertTrue(harness.adapter.play())

        val desired = requireNotNull(harness.engine.desired)
        assertEquals(token, desired.selection.token)
        assertTrue(desired.playWhenReady)
        assertEquals(55_000L, desired.requestedPositionMs)
        assertEquals(1L, desired.seekRevision)
        assertEquals(EngineSourceMode.STEMS, desired.sourceMode)
        assertEquals(listOf("vocals", "drums", "bass", "guitar", "piano", "remaining"), desired.stemLanes.map { it.normalizedRole })
        assertEquals(mix, desired.stemControls)
        assertEquals(.5f, desired.tempo)
        assertEquals(12, desired.semitoneShift)
        assertEquals(EngineLoopConfig(1_000L, 12_000L), harness.engine.explicitLoopState?.config)
        assertEquals(123_000L, harness.engine.explicitLoopState?.inputEventAtMonotonicMicros)
        assertEquals(500L, desired.metronome.beatGridMs.first())
        assertTrue(desired.metronome.downbeats.first())
        assertEquals(.9f, desired.metronome.volume)

        var committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(9L, committed.transport.desiredRevision)
        assertEquals(4L, committed.source.desiredRevision)
        assertEquals(4L, committed.stems.desired?.requestRevision)

        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, 2f, 12))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(4L, committed.source.desiredRevision)
        assertEquals(9L, committed.transport.desiredRevision)
        assertRejected { harness.adapter.setTempoAndKeyIfCurrent(lease, .499f, 12) }
        assertRejected { harness.adapter.setTempoAndKeyIfCurrent(lease, 2.001f, 12) }
        assertRejected { harness.adapter.setTempoAndKeyIfCurrent(lease, 2f, 13) }
    }

    @Test
    fun oneCommandLeaseAtomicallyRejectsEveryControlAfterSameSongReload() {
        val harness = Harness()
        val firstToken = harness.adapter.select(
            request("same-song"),
            metadata("same-song", 1L)
        )
        val firstLease = requireNotNull(harness.adapter.acquireCommandLease("same-song"))
        assertEquals(firstToken, firstLease.token)
        assertTrue(harness.adapter.commandLeaseIsCurrent(firstLease))
        assertFalse(
            harness.adapter.commandLeaseIsCurrent(firstLease.copy(songId = "forged-profile"))
        )
        assertFalse(
            harness.adapter.commandLeaseIsCurrent(firstLease.copy(audioRevision = "forged-audio"))
        )
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(firstLease, 1.25f, 2))

        // Reloading the exact same id/revision is a new playback owner. This is the case that a
        // song-id guard cannot distinguish and a retained UI callback must never be allowed to hit.
        val secondToken = harness.adapter.select(
            request("same-song"),
            metadata("same-song", 2L)
        )
        harness.engine.publishOwner(secondToken, 100L, 1L)
        val secondLease = requireNotNull(harness.adapter.acquireCommandLease("same-song"))
        assertFalse(firstToken == secondToken)
        assertEquals(secondToken, secondLease.token)
        assertFalse(harness.adapter.commandLeaseIsCurrent(firstLease))
        assertTrue(harness.adapter.commandLeaseIsCurrent(secondLease))

        val lanes = stems("same-song")
        val mix = lanes.associate { lane ->
            lane.id to EngineStemControl(gain = .61f, muted = false, solo = false)
        }
        val metronome = EngineMetronomeConfig(
            enabled = true,
            songSynced = true,
            bpm = 133f,
            volume = .8f,
            subdivision = 2,
            numerator = 7,
            denominator = 8,
            beatGridMs = longArrayOf(500L, 1_000L),
            downbeats = booleanArrayOf(true, false)
        )
        val beforeStaleCallbacks = requireNotNull(harness.engine.desired)

        assertTrue(harness.adapter.seekToIfCurrent(firstLease, 12_345L) == null)
        assertFalse(
            harness.adapter.configureStemPackageIfCurrent(
                firstLease,
                stemRevision = "stale-stems",
                lanes = lanes,
                selectStems = true
            )
        )
        assertFalse(harness.adapter.setSourceModeIfCurrent(firstLease, EngineSourceMode.ORIGINAL))
        assertFalse(harness.adapter.setStemMixIfCurrent(firstLease, mix))
        assertFalse(harness.adapter.setTempoAndKeyIfCurrent(firstLease, 1.35f, 5))
        assertFalse(
            harness.adapter.setLoopIfCurrent(firstLease, EngineLoopConfig(1_000L, 6_000L))
        )
        assertFalse(harness.adapter.setMetronomeProfileIfCurrent(firstLease, metronome))
        assertFalse(harness.adapter.setMetronomeVolumeIfCurrent(firstLease, .25f))
        assertEquals(beforeStaleCallbacks, harness.engine.desired)

        // The complete control family remains available to the exact replacement owner.
        assertTrue(harness.adapter.seekToIfCurrent(secondLease, 23_456L) != null)
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                secondLease,
                stemRevision = "current-stems",
                lanes = lanes,
                selectStems = true
            )
        )
        assertTrue(harness.adapter.setSourceModeIfCurrent(secondLease, EngineSourceMode.ORIGINAL))
        assertTrue(harness.adapter.setStemMixIfCurrent(secondLease, mix))
        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(secondLease, 1.35f, 5))
        assertTrue(
            harness.adapter.setLoopIfCurrent(secondLease, EngineLoopConfig(1_000L, 6_000L))
        )
        assertTrue(harness.adapter.setMetronomeProfileIfCurrent(secondLease, metronome))
        assertTrue(harness.adapter.setMetronomeVolumeIfCurrent(secondLease, .25f))

        val accepted = requireNotNull(harness.engine.desired)
        assertEquals(secondToken, accepted.selection.token)
        assertEquals(23_456L, accepted.requestedPositionMs)
        assertEquals(EngineSourceMode.ORIGINAL, accepted.sourceMode)
        assertEquals(mix, accepted.stemControls)
        assertEquals(1.35f, accepted.tempo)
        assertEquals(5, accepted.semitoneShift)
        assertEquals(
            EngineLoopConfig(1_000L, 6_000L),
            harness.engine.explicitLoopState?.config
        )
        assertTrue(accepted.metronome.enabled)
        assertEquals(.25f, accepted.metronome.volume)
    }

    @Test
    fun durablePauseTargetsPendingTokenSoTheOutgoingBankCannotKeepPlaying() {
        val harness = Harness()
        val a = harness.adapter.select(request("a"), metadata("a", 1L))
        harness.engine.publishOwner(a, 10L, 1L)
        val b = harness.adapter.select(request("b"), metadata("b", 1L))

        assertTrue(harness.adapter.pause())

        assertEquals(b to false, harness.engine.audibleTransportIntents.single())
        assertFalse(requireNotNull(harness.adapter.snapshot().latestRequest).controls.playWhenReady)
    }

    @Test
    fun loopRejectionClearsActiveControlWithoutFalsifyingTransport() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop"), metadata("loop", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, 10L, 1L)
        assertTrue(harness.adapter.setLoopIfCurrent(lease, EngineLoopConfig(1_000L, 5_000L)))

        harness.engine.emit(
            NativeEngineControllerEvent.LoopNotReady(
                token,
                1L,
                EngineLoopConfig(1_000L, 5_000L),
                "Loop cache was not ready"
            )
        )

        val state = harness.adapter.snapshot()
        assertNull(state.latestRequest?.controls?.loop)
        assertTrue(requireNotNull(state.publication.committed).transport.playWhenReady)
        assertNull(state.publication.committed.issue)
        assertEquals(
            listOf("Loop cache was not ready"),
            harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.LoopRejected>()
                .map { it.cause }
        )
    }

    @Test
    fun loopMovesUseOnlyTheIndependentLoopRevision() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-isolation"), metadata("loop-isolation", 1L))
        harness.engine.publishOwner(token, 100L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val desiredBefore = requireNotNull(harness.engine.desired)
        val desiredUpdatesBefore = harness.engine.desiredUpdateCalls

        repeat(40) { index ->
            assertTrue(
                harness.adapter.setLoopIfCurrent(
                    lease,
                    EngineLoopConfig(1_000L + index, 5_000L + index)
                )
            )
        }

        assertEquals(desiredBefore, harness.engine.desired)
        assertEquals(desiredUpdatesBefore, harness.engine.desiredUpdateCalls)
        assertEquals(token, harness.engine.explicitLoopState?.token)
        assertEquals(40L, harness.engine.explicitLoopState?.revision)
        assertEquals(
            EngineLoopConfig(1_039L, 5_039L),
            harness.engine.explicitLoopState?.config
        )
    }

    @Test
    fun loopGesturePublishesPresentationOnceWithoutAnotherEngineRevision() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-presentation"), metadata("loop-presentation", 1L))
        harness.engine.publishOwner(token, 10L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val structuralBefore = harness.adapter.snapshot().structuralRevision

        repeat(12) { index ->
            assertTrue(
                harness.adapter.setLoopIfCurrent(
                    lease,
                    EngineLoopConfig(1_000L + index, 5_000L + index, parkUntilRangeEntry = false),
                    publishPresentation = false
                )
            )
        }

        assertEquals(structuralBefore, harness.adapter.snapshot().structuralRevision)
        assertEquals(12L, harness.engine.explicitLoopState?.revision)
        val finalLoop = EngineLoopConfig(1_011L, 5_011L, parkUntilRangeEntry = true)
        assertTrue(harness.adapter.publishExplicitLoopPresentationIfCurrent(lease, finalLoop))
        assertEquals(structuralBefore + 1L, harness.adapter.snapshot().structuralRevision)
        assertEquals(12L, harness.engine.explicitLoopState?.revision)
        assertEquals(
            finalLoop.copy(parkUntilRangeEntry = false),
            harness.adapter.snapshot().latestRequest?.controls?.loop
        )
    }

    @Test
    fun loopMovesReuseTheImmutableControlGraph() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-graph"), metadata("loop-graph", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, generation = 11L, callbackSequence = 1L)
        assertTrue(
            harness.adapter.setMetronomeProfileIfCurrent(
                lease,
                EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 120f,
                    beatGridMs = LongArray(20_000) { it * 25L },
                    downbeats = BooleanArray(20_000) { it % 16 == 0 }
                )
            )
        )
        val before = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        val buildsBefore = harness.adapter.controlSnapshotBuildCountForTest()

        repeat(40) { index ->
            assertTrue(
                harness.adapter.setLoopIfCurrent(
                    lease,
                    EngineLoopConfig(1_000L + index, 5_000L + index)
                )
            )
        }

        val after = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        assertEquals(EngineLoopConfig(1_039L, 5_039L), after.loop)
        assertEquals(buildsBefore, harness.adapter.controlSnapshotBuildCountForTest())
        assertSame(before.stemLanes, after.stemLanes)
        assertSame(before.stemControls, after.stemControls)
        assertSame(before.metronome.beatGridMs, after.metronome.beatGridMs)
        assertSame(before.metronome.downbeats, after.metronome.downbeats)
        assertSame(before.metronome.meterSegments, after.metronome.meterSegments)
    }

    @Test
    fun loopEndpointCommitPreservesTheAudiblePlayingTransport() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-handle"), metadata("loop-handle", 1L))
        harness.engine.publishOwner(token, 12L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease("loop-handle"))

        assertTrue(
            harness.adapter.setLoopIfCurrent(
                lease,
                EngineLoopConfig(2_000L, 8_000L, parkUntilRangeEntry = true)
            )
        )

        assertTrue(requireNotNull(harness.engine.desired).playWhenReady)
        assertTrue(requireNotNull(harness.adapter.snapshot().latestRequest).controls.playWhenReady)
        assertTrue(requireNotNull(harness.adapter.snapshot().publication.committed).transport.playWhenReady)
        assertTrue(harness.engine.audibleTransportIntents.isEmpty())
    }

    @Test
    fun retainedLoopIsReclassifiedBeforeEveryExactTransportSeek() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-seek"), metadata("loop-seek", 1L))
        harness.engine.publishOwner(token, 12L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease("loop-seek"))

        assertTrue(
            harness.adapter.setLoopIfCurrent(
                lease,
                EngineLoopConfig(2_000L, 4_000L)
            )
        )
        assertTrue(harness.adapter.seekToIfCurrent(lease, 6_000L) != null)
        assertEquals(
            EngineLoopConfig(2_000L, 4_000L, parkUntilRangeEntry = true),
            harness.engine.explicitLoopState?.config
        )
        assertEquals(1, harness.engine.transportSeekLoopUpdates)

        assertTrue(harness.adapter.seekToIfCurrent(lease, 2_500L) != null)
        assertEquals(
            EngineLoopConfig(2_000L, 4_000L, parkUntilRangeEntry = false),
            harness.engine.explicitLoopState?.config
        )
        assertEquals(2, harness.engine.transportSeekLoopUpdates)
        assertEquals(2_500L, harness.engine.desired?.requestedPositionMs)
    }

    @Test fun relativeSeeksStayWithinAnActiveLoopButCanApproachADormantLoop() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-relative"), metadata("loop-relative", 1L))
        harness.engine.publishOwner(token, 12L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        assertTrue(harness.adapter.setLoopIfCurrent(lease, EngineLoopConfig(2_000L, 4_000L)))
        assertNotNull(harness.adapter.seekToIfCurrent(lease, 3_000L))
        assertEquals(3_999L, harness.adapter.resolveRelativeSeek(5_000L)?.positionMs)
        assertEquals(2_000L, harness.adapter.resolveRelativeSeek(-5_000L)?.positionMs)
        val forward = requireNotNull(harness.adapter.resolveRelativeSeek(5_000L))
        assertNotNull(harness.adapter.seekToIfCurrent(forward.lease, forward.positionMs))
        assertEquals(EngineLoopConfig(2_000L, 4_000L), harness.engine.explicitLoopState?.config)
        assertNotNull(harness.adapter.seekToIfCurrent(lease, 6_000L))
        assertEquals(11_000L, harness.adapter.resolveRelativeSeek(5_000L)?.positionMs)
        assertEquals(3_000L, harness.adapter.resolveRelativeSeek(-3_000L)?.positionMs)
    }

    @Test
    fun scrubOutsideRetainsTheLoopAndReentryRearmsItIncludingTheExclusiveEnd() {
        val harness = Harness()
        val token = harness.adapter.select(request("loop-scrub"), metadata("loop-scrub", 1L))
        harness.engine.publishOwner(token, 12L, 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease("loop-scrub"))
        assertTrue(harness.adapter.setLoopIfCurrent(lease, EngineLoopConfig(2_000L, 4_000L)))
        for (target in listOf(1_000L, 4_000L, 6_000L)) {
            assertTrue(harness.adapter.previewSeekTo(token, target))
            assertEquals(EngineLoopConfig(2_000L, 4_000L, true), harness.engine.explicitLoopState?.config)
            assertNotNull(harness.adapter.finishScrubWithReceipt(token, target, true))
            assertEquals(EngineLoopConfig(2_000L, 4_000L, true), harness.engine.explicitLoopState?.config)
        }
        assertTrue(harness.adapter.previewSeekTo(token, 2_500L))
        assertEquals(EngineLoopConfig(2_000L, 4_000L, false), harness.engine.explicitLoopState?.config)
        assertNotNull(harness.adapter.finishScrubWithReceipt(token, 2_500L, true))
        assertEquals(2_500L, harness.engine.desired?.requestedPositionMs)
    }

    @Test
    fun fatalOutputFailureSettlesRequestedAndCommittedTransportAndEmitsOneTypedEffect() {
        val harness = Harness()
        val token = harness.adapter.select(request("fatal-output"), metadata("fatal-output", 1L))
        harness.engine.publishOwner(token, 12L, 1L)

        harness.engine.emit(
            NativeEngineControllerEvent.FatalRecoveryRequired("Synthetic output route failure")
        )

        val state = harness.adapter.snapshot()
        assertEquals(
            NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED,
            state.lifecycle
        )
        assertTrue(harness.adapter.processRestartRequired())
        assertFalse(requireNotNull(state.latestRequest).controls.playWhenReady)
        val committed = requireNotNull(state.publication.committed)
        assertFalse(committed.transport.playWhenReady)
        assertEquals("output-unavailable", committed.issue?.category)
        assertFalse(requireNotNull(committed.issue).recoverable)
        assertEquals(
            listOf("Synthetic output route failure"),
            harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.OutputUnavailable>()
                .map { it.diagnostic }
        )

        val selectionsBefore = harness.engine.selections.size
        assertNull(
            harness.adapter.trySelect(
                request("rejected-after-fatal"),
                metadata("rejected-after-fatal", 1L)
            )
        )
        assertEquals(selectionsBefore, harness.engine.selections.size)
        assertFalse(harness.adapter.play())

        val stop = harness.adapter.stop()
        assertTrue(stop.isComplete())
        assertEquals(NativeEngineStopStatus.FATAL_QUARANTINE, stop.status())
        val stopped = harness.adapter.snapshot()
        assertEquals(NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED, stopped.lifecycle)
        assertNull(stopped.latestRequest)
        assertNull(stopped.publication.committed)
    }

    @Test fun physicalOpenFailureAndRecoveryAreImmediateBeforeOwnerPublicationAndRejectStaleRequests() {
        val harness = Harness()
        val first = harness.adapter.select(request("unopened"), metadata("unopened", 1L))
        harness.engine.emit(NativeEngineControllerEvent.OutputAvailabilityChanged(first, false, "Open failed"))
        harness.engine.emit(NativeEngineControllerEvent.OutputAvailabilityChanged(first, true))
        assertNull(harness.adapter.snapshot().publication.committed)
        assertEquals(listOf(false, true), harness.effects
            .filterIsInstance<NativeAudioPlaybackSessionEffect.OutputAvailabilityChanged>().map { it.available })
        harness.adapter.select(request("replacement"), metadata("replacement", 1L))
        harness.engine.emit(NativeEngineControllerEvent.OutputAvailabilityChanged(first, false, "Stale open"))
        assertEquals(2, harness.effects.filterIsInstance<NativeAudioPlaybackSessionEffect.OutputAvailabilityChanged>().size)
    }

    @Test
    fun successorMetadataRefreshRetainsItsExactAudioIdentity() {
        val harness = Harness()
        harness.adapter.select(request("metadata-current"), metadata("metadata-current", 1L))
        val next = request("metadata-next")
        val before = NativeEngineQueueItem(next.identity(), NativeEngineMetadata(
            next.songId, next.audioRevision, 1L, "without-artwork"))
        harness.adapter.setAutomaticEndPlan(NativeEngineAutomaticEndPlan.NextOwner(before))
        val ready = before.metadata.copy(revision = 2L, payloadKey = "resident-artwork")
        harness.adapter.refreshAutomaticEndMetadata { identity ->
            if (identity == before.identity) ready else null
        }
        val refreshed = requireNotNull(harness.engine.lastLikelyNext)
        assertSame(before.identity, refreshed.identity)
        assertEquals(ready, refreshed.metadata)
        assertEquals(2, harness.engine.automaticEndPlanCalls)
        harness.adapter.refreshAutomaticEndMetadata { ready }
        assertEquals(2, harness.engine.automaticEndPlanCalls)
    }

    @Test
    fun identicalAutomaticEndPlanIsDeduped() {
        val harness = Harness()
        val currentRequest = request("deduped-repeat-current")
        harness.adapter.select(currentRequest, metadata("deduped-repeat-current", 1L))
        val plan = NativeEngineAutomaticEndPlan.RestartCurrent(
            currentRequest.identity(),
            currentRequest.durationMs
        )

        harness.adapter.setAutomaticEndPlan(plan)
        harness.adapter.setAutomaticEndPlan(plan)
        harness.adapter.setAutomaticEndPlan(plan.copy())
        assertEquals(1, harness.engine.automaticEndPlanCalls)
    }

    @Test
    fun likelyNextStopRouteAndReleaseRemainBoundedAndLatestWins() {
        val harness = Harness()
        val effects = harness.effects
        val first = harness.adapter.select(request("first"), metadata("first", 1L))
        harness.engine.publishOwner(first, 300L, 1L)

        repeat(400) { index ->
            val request = request("next-$index")
            harness.adapter.setAutomaticEndPlan(
                NativeEngineAutomaticEndPlan.NextOwner(
                    NativeEngineQueueItem(
                        request.identity(),
                        metadata("next-$index", 1L).toNative()
                    )
                )
            )
        }
        assertEquals("next-399", harness.adapter.snapshot().likelyNextIdentity?.songId)
        assertEquals("next-399", harness.engine.lastLikelyNext?.identity?.songId)

        harness.adapter.suspendForAudioFocusLoss()
        harness.adapter.resumeAfterAudioFocusGain()
        harness.adapter.requestRouteRecovery()
        assertEquals(1, harness.engine.focusSuspends)
        assertEquals(1, harness.engine.focusResumes)
        assertEquals(1, harness.engine.routeRecoveries)

        val stop = harness.adapter.stop()
        assertEquals(NativeAudioPlaybackSessionLifecycle.STOPPING, harness.adapter.snapshot().lifecycle)
        assertNull(harness.adapter.trySelect(
            request("replacement"),
            metadata("replacement", 1L)
        ))
        harness.engine.completeStop(stop)
        val replacement = harness.adapter.select(
            request("replacement"),
            metadata("replacement", 1L)
        )
        assertEquals("replacement", harness.adapter.snapshot().latestRequest?.selection?.songId)
        assertNull(harness.adapter.snapshot().publication.committed)
        assertTrue(
            effects.filterIsInstance<NativeAudioPlaybackSessionEffect.StopCompleted>()
                .single().clearedPublication
        )

        harness.engine.publishOwner(replacement, 301L, 2L)
        assertEquals(
            "replacement",
            harness.adapter.snapshot().publication.committed?.metadata?.songId
        )
        harness.adapter.close()
        harness.adapter.close()
        assertEquals(NativeAudioPlaybackSessionLifecycle.CLOSED, harness.adapter.snapshot().lifecycle)
        assertEquals(1, harness.engine.closeCalls)
        assertEquals(1, effects.count { it == NativeAudioPlaybackSessionEffect.Closed })
    }

    @Test
    fun tenThousandPositionEventsReuseOneLargeImmutableControlSnapshot() {
        val harness = Harness()
        val token = harness.adapter.select(request("position-cache"), metadata("position-cache", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val beatGrid = LongArray(20_000) { it * 25L }
        val accents = BooleanArray(beatGrid.size) { it % 16 == 0 }
        assertTrue(
            harness.adapter.setMetronomeProfileIfCurrent(
                lease,
                EngineMetronomeConfig(
                    enabled = true,
                    songSynced = true,
                    bpm = 150f,
                    beatGridMs = beatGrid,
                    downbeats = accents
                )
            )
        )
        harness.engine.publishOwner(token, 400L, 1L)
        val controlsBefore = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        val buildsBefore = harness.adapter.controlSnapshotBuildCountForTest()
        val structuralRevisionBefore = harness.adapter.snapshot().structuralRevision

        repeat(10_000) { index ->
            harness.engine.emit(
                NativeEngineControllerEvent.Position(
                    token = token,
                    positionMs = index.toLong(),
                    callbackSequence = index + 2L
                )
            )
        }

        val controlsAfter = requireNotNull(harness.adapter.snapshot().latestRequest).controls
        assertSame(controlsBefore, controlsAfter)
        assertEquals(buildsBefore, harness.adapter.controlSnapshotBuildCountForTest())
        assertEquals(structuralRevisionBefore, harness.adapter.snapshot().structuralRevision)
        assertEquals(9_999L,
            harness.adapter.snapshot().publication.committed?.transport?.presentedPositionMs)

        assertTrue(harness.adapter.setTempoAndKeyIfCurrent(lease, 1.1f, 0))
        assertEquals(buildsBefore + 1L, harness.adapter.controlSnapshotBuildCountForTest())
        assertEquals(structuralRevisionBefore + 1L,
            harness.adapter.snapshot().structuralRevision)
        assertTrue(controlsAfter !== harness.adapter.snapshot().latestRequest?.controls)
    }

    @Test
    fun scrubReceiptsCaptureTheExactCommittedGenerationAtAcquireAndFinishBoundaries() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("receipt-generation"),
            metadata("receipt-generation", 1L)
        )
        val firstOwnership = harness.engine.publishOwner(
            token,
            generation = 430L,
            callbackSequence = 1L
        )

        val acquired = requireNotNull(
            harness.adapter.setPlayWhenReadyIfCurrent(token, playWhenReady = false)
        )
        assertEquals(token, acquired.token)
        assertEquals(firstOwnership, acquired.ownership)

        val rebasedOwnership = harness.engine.publishOwner(
            token,
            generation = 431L,
            callbackSequence = 2L
        )
        val finished = requireNotNull(
            harness.adapter.finishScrubWithReceipt(
                token = token,
                positionMs = 23_456L,
                playWhenReady = true
            )
        )

        assertEquals(token, finished.token)
        assertEquals(rebasedOwnership, finished.ownership)
        assertTrue(finished != acquired)
        assertEquals(23_456L, harness.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
        assertTrue(requireNotNull(harness.adapter.snapshot().latestRequest).controls.playWhenReady)

        // A delayed older same-token owner cannot replace the exact receipt from reducer authority.
        harness.engine.publishOwner(token, generation = 430L, callbackSequence = 3L)
        val reacquired = requireNotNull(
            harness.adapter.setPlayWhenReadyIfCurrent(token, playWhenReady = false)
        )
        assertEquals(rebasedOwnership, reacquired.ownership)
        assertEquals(rebasedOwnership, harness.adapter.snapshot().latestRequest?.exactOwnership)
    }
}
