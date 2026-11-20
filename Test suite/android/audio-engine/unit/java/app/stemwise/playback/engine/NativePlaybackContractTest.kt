package app.stemwise.playback.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativePlaybackContractTest {
    @Test fun playbackWindowClampsTransportAndBindsSpeculativeIdentity() {
        val gate = EngineOwnerCommitGate()
        val selected = gate.request("cut", "v1", "/cut.ogg", 10_000L, 0L, true,
            playbackStartMs = 1_003L, playbackEndMs = 8_007L)
        assertEquals(1_003L, selected.startPositionMs)
        val store = EngineDesiredStateStore()
        assertEquals(1_003L, store.install(selected).requestedPositionMs)
        assertEquals(8_006L, store.update(selected.token) { it.copy(requestedPositionMs = 10_000L) }?.requestedPositionMs)
        assertEquals(1_003L, store.update(selected.token) { it.copy(requestedPositionMs = 0L) }?.requestedPositionMs)
        val identity = selected.audioIdentity()
        assertTrue(identity.stableIdentity() != identity.copy(playbackStartMs = 1_004L).stableIdentity())
        assertTrue(identity.stableIdentity() != identity.copy(playbackEndMs = 8_008L).stableIdentity())
        val reserved = gate.reserve(identity)
        val claimed = requireNotNull(gate.claim(reserved, 0L, true))
        assertEquals(identity, claimed.audioIdentity())
        assertEquals(1_003L, claimed.startPositionMs)
        for ((start, end) in listOf(-1L to 8_007L, 8_007L to 8_007L, 0L to 10_001L)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                identity.copy(playbackStartMs = start, playbackEndMs = end)
            }
        }
    }

    @Test fun sharedWaveformLevelsCannotBeMutatedByAnAppConsumer() {
        val levels = app.stemwise.ENRICHMENT_WAVEFORM_FRAME_DURATIONS_MS
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) {
            (levels as MutableSet<Long>).add(1L)
        }
        assertEquals(listOf(5L, 50L, 250L), levels.toList())
    }

    @Test
    fun endpointTransportRetainsRenderableFrameWithoutChangingLoopEndPolicy() {
        assertEquals(599L, nativeRenderablePositionMs(600.0, 600L))
        assertEquals(599L, nativeRenderablePositionMs(1_000.0, 600L))
        assertEquals(123L, nativeRenderablePositionMs(123.0, 600L))
        assertEquals(0L, nativeRenderablePositionMs(1.0, 1L))
        assertEquals(600L, app.stemwise.playback.playbackControlPositionMs(600.0, 600L))
    }
    @Test
    fun enginePositionAdmissionsPreserveMillisecondPrecision() {
        val selection = EngineOwnerCommitGate().request("quantum", "v1", "/song.ogg", 10_003L, 123L, true)
        assertEquals(123L, selection.startPositionMs)
        val store = EngineDesiredStateStore()
        assertEquals(123L, store.install(selection).requestedPositionMs)
        assertEquals(126L, store.update(selection.token) { it.copy(requestedPositionMs = 126L) }?.requestedPositionMs)
        // The exclusive song endpoint is not a renderable seek position. Only that endpoint
        // clamps to the preceding millisecond; ordinary transport retains its exact precision.
        assertEquals(10_002L, store.update(selection.token) { it.copy(requestedPositionMs = 10_003L) }?.requestedPositionMs)
    }

    @Test
    fun onlyFinalPcmForTheLatestSelectionCanCommitPublicOwnership() {
        val gate = EngineOwnerCommitGate()
        val first = gate.request("a", "rev-a", "/a.ogg", 10_000L, 0L, true)
        val second = gate.request("b", "rev-b", "/b.ogg", 10_000L, 0L, true)
        val last = gate.request("c", "rev-c", "/c.ogg", 10_000L, 0L, true)

        assertNull(gate.accept(evidence(first, "a", callbackSequence = 1L)))
        assertNull(gate.accept(evidence(second, "b", callbackSequence = 2L)))
        val commit = gate.accept(evidence(last, "c", callbackSequence = 3L))

        assertEquals(last, commit?.selection)
        assertEquals("c", commit?.evidence?.songId)
        assertNull(gate.accept(evidence(last, "c", callbackSequence = 4L)))
    }

    @Test
    fun repeatedSelectionOfTheSameSongStillRejectsTheOlderDecoder() {
        val gate = EngineOwnerCommitGate()
        val old = gate.request("same", "rev", "/same.ogg", 10_000L, 0L, true)
        val latest = gate.request("same", "rev", "/same.ogg", 10_000L, 4_000L, true)

        assertNull(gate.accept(evidence(old, "same", callbackSequence = 1L)))
        assertEquals(latest, gate.accept(evidence(latest, "same", callbackSequence = 2L))?.selection)
    }

    @Test
    fun mismatchedSongIdentityCannotCommitEvenWithTheLatestToken() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("expected", "rev", "/expected.ogg", 10_000L, 0L, true)

        assertNull(gate.accept(evidence(selection, "wrong", callbackSequence = 1L)))
        assertTrue(gate.isLatest(selection.token))
    }

    @Test
    fun stopInvalidatesEveryInFlightOwner() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)

        gate.invalidate()

        assertNull(gate.latest())
        assertFalse(gate.isLatest(selection.token))
        assertNull(gate.accept(evidence(selection, "song", callbackSequence = 1L)))
    }

    @Test
    fun fourHundredSongFourThousandRequestStormHasOneDeterministicWinner() {
        val gate = EngineOwnerCommitGate()
        var latest: EngineSelection? = null
        repeat(4_000) { index ->
            val songIndex = index % 400
            latest = gate.request(
                songId = "song-$songIndex",
                audioRevision = "rev-$songIndex",
                originalLocation = "/song-$songIndex.ogg",
                durationMs = 180_000L,
                startPositionMs = 0L,
                autoplay = true
            )
        }
        val winner = requireNotNull(latest)

        repeat(399) { index ->
            val stale = EngineSelectionToken(index + 1L)
            assertNull(
                gate.accept(
                    EngineAudibleOwnerEvidence(
                        token = stale,
                        songId = "song-${index % 400}",
                        callbackSequence = index + 1L,
                        firstOutputFrame = index * 256L,
                        finalPcmFrames = 256,
                        outputFingerprintSequence = index + 1L
                    )
                )
            )
        }
        assertEquals(winner, gate.accept(evidence(winner, winner.songId, 4_000L))?.selection)
    }

    @Test
    fun concurrentRequestsRemainUniqueAndLatestIsWellFormed() {
        val gate = EngineOwnerCommitGate()
        val executor = Executors.newFixedThreadPool(8) { task ->
            Thread(task, "owner-commit-gate-pressure").apply { isDaemon = true }
        }
        val start = CountDownLatch(1)
        val done = CountDownLatch(800)
        val tokens = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        repeat(800) { index ->
            executor.execute {
                try {
                    start.await()
                    val selection = gate.request(
                        "song-${index % 400}",
                        "rev-${index % 400}",
                        "/song-${index % 400}.ogg",
                        10_000L,
                        0L,
                        true
                    )
                    tokens += selection.token.value
                } catch (caught: Throwable) {
                    failures += caught
                } finally {
                    done.countDown()
                }
            }
        }

        var allTasksCompleted = false
        var executorTerminated = false
        try {
            start.countDown()
            allTasksCompleted = done.await(10L, TimeUnit.SECONDS)
        } finally {
            start.countDown()
            executor.shutdownNow()
            executorTerminated = executor.awaitTermination(2L, TimeUnit.SECONDS)
        }

        assertTrue("Concurrent request tasks did not complete", allTasksCompleted)
        assertTrue("Concurrent request executor did not terminate", executorTerminated)
        assertTrue("Concurrent request task failed: $failures", failures.isEmpty())
        assertEquals(800, tokens.size)
        assertNotNull(gate.latest())
        assertEquals(800L, gate.latest()?.token?.value)
    }

    @Test
    fun oneConflatedSnapshotRetainsOnlyTheLatestControlValues() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 200_000L, 0L, true)
        val store = EngineDesiredStateStore()
        store.install(selection)

        repeat(10_000) { index ->
            val position = (index * 17L) % selection.durationMs
            assertNotNull(
                store.update(selection.token) { previous ->
                    previous.copy(
                        requestedPositionMs = position,
                        seekRevision = previous.seekRevision + 1L,
                        tempo = .75f + (index % 76) / 100f,
                        semitoneShift = (index % 25) - 12
                    )
                }
            )
        }

        val result = requireNotNull(store.latest())
        assertEquals(10_001L, result.revision)
        assertEquals(10_000L, result.seekRevision)
        assertEquals((9_999 * 17L) % selection.durationMs, result.requestedPositionMs)
        assertEquals((9_999 % 25) - 12, result.semitoneShift)
    }

    @Test
    fun controlsScopedToAnOldSelectionCannotAffectTheNewSong() {
        val gate = EngineOwnerCommitGate()
        val old = gate.request("old", "rev-old", "/old.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        store.install(old)
        val latest = gate.request("latest", "rev-latest", "/latest.ogg", 10_000L, 0L, true)
        store.install(latest)

        assertNull(store.update(old.token) { it.copy(playWhenReady = false) })
        assertTrue(requireNotNull(store.latest()).playWhenReady)
        assertEquals(latest, store.latest()?.selection)
    }

    @Test
    fun automaticSuccessorStartsOriginalAndNeutralEvenForTheSameAudioIdentity() {
        val gate = EngineOwnerCommitGate()
        val outgoing = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        store.install(outgoing)
        store.update(outgoing.token) {
            it.copy(tempo = .5f, semitoneShift = 9)
        }
        val successor = outgoing.copy(token = EngineSelectionToken(outgoing.token.value + 1L))

        val installed = requireNotNull(store.installAutomaticSuccessor(outgoing.token, 0L) { successor })

        assertEquals(EngineSourceMode.ORIGINAL, installed.sourceMode)
        assertEquals(1f, installed.tempo)
        assertEquals(0, installed.semitoneShift)
        assertTrue(installed.stemLanes.isEmpty())
    }

    @Test
    fun staleDesiredRevisionIsRejectedWithoutInvokingItsTransform() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        val installed = store.install(selection)
        val updated = requireNotNull(
            store.updateIfRevision(selection.token, installed.revision) {
                it.copy(tempo = 1.1f)
            }
        )
        var staleTransformInvoked = false

        val rejected = store.updateIfRevision(selection.token, installed.revision) {
            staleTransformInvoked = true
            it.copy(tempo = 1.2f)
        }

        assertNull(rejected)
        assertFalse(staleTransformInvoked)
        assertEquals(updated, store.latest())
    }

    @Test
    fun stemsCannotBeSelectedWithoutAnyDeclaredPlayableLanes() {
        val gate = EngineOwnerCommitGate()
        val selection = gate.request("song", "rev", "/song.ogg", 10_000L, 0L, true)
        val store = EngineDesiredStateStore()
        store.install(selection)

        assertTrue(
            runCatching {
                store.update(selection.token) {
                    it.copy(sourceMode = EngineSourceMode.STEMS)
                }
            }.isFailure
        )
    }

    @Test
    fun speculativeReservationCannotCommitUntilExactClaim() {
        val gate = EngineOwnerCommitGate()
        val identity = EngineAudioIdentity("next", "rev", "/next.ogg", 20_000L)
        val reservation = gate.reserve(identity)
        val evidence = EngineAudibleOwnerEvidence(
            reservation.token,
            identity.songId,
            1L,
            0L,
            256,
            1L
        )

        assertNull(gate.accept(evidence))
        val claimed = requireNotNull(gate.claim(reservation, 0L, true))
        assertEquals(reservation.token, claimed.token)
        assertEquals(claimed, gate.accept(evidence)?.selection)
    }

    @Test
    fun explicitDifferentSelectionCancelsAndNeverReusesReservation() {
        val gate = EngineOwnerCommitGate()
        val reservation = gate.reserve(
            EngineAudioIdentity("next", "rev", "/next.ogg", 20_000L)
        )
        val selected = gate.request("other", "rev2", "/other.ogg", 20_000L, 0L, true)

        assertNull(gate.claim(reservation, 0L, true))
        assertTrue(selected.token.value > reservation.token.value)
        assertNull(gate.reservation())
    }

    @Test
    fun exactReservedSuccessorCommitsOnlyAtTheOutgoingAudibleBoundary() {
        val gate = EngineOwnerCommitGate()
        val outgoing = gate.request("outgoing", "rev-a", "/a.ogg", 20_000L, 0L, true)
        assertEquals(
            outgoing,
            gate.accept(evidence(outgoing, outgoing.songId, callbackSequence = 1L))?.selection
        )
        val identity = EngineAudioIdentity("successor", "rev-b", "/b.ogg", 30_000L)
        val reservation = gate.reserve(identity)
        val successorEvidence = evidence(reservation, identity.songId, callbackSequence = 2L)

        val commit = gate.acceptReservedAtAudibleBoundary(
            expected = reservation,
            expectedOutgoingToken = outgoing.token,
            evidence = successorEvidence,
            startPositionMs = 250L,
            autoplay = false
        )

        val successor = requireNotNull(commit).selection
        assertEquals(reservation.token, successor.token)
        assertEquals(identity, successor.audioIdentity())
        assertEquals(250L, successor.startPositionMs)
        assertFalse(successor.autoplay)
        assertEquals(successorEvidence, commit.evidence)
        assertEquals(successor, gate.latest())
        assertNull(gate.reservation())
        assertNull(
            gate.acceptReservedAtAudibleBoundary(
                reservation,
                outgoing.token,
                successorEvidence
            )
        )
    }

    @Test
    fun cancelledReservedSuccessorCannotCommitAtTheAudibleBoundary() {
        val gate = EngineOwnerCommitGate()
        val outgoing = committedSelection(gate, "outgoing", callbackSequence = 1L)
        val identity = EngineAudioIdentity("cancelled", "rev-c", "/cancelled.ogg", 20_000L)
        val reservation = gate.reserve(identity)

        assertTrue(gate.cancelReservation(reservation))
        assertNull(
            gate.acceptReservedAtAudibleBoundary(
                reservation,
                outgoing.token,
                evidence(reservation, identity.songId, callbackSequence = 2L)
            )
        )
        assertEquals(outgoing, gate.latest())
        assertNull(gate.reservation())
    }

    @Test
    fun manualSelectionWinsOverAnOlderReservedAutomaticSuccessor() {
        val gate = EngineOwnerCommitGate()
        val outgoing = committedSelection(gate, "outgoing", callbackSequence = 1L)
        val identity = EngineAudioIdentity("automatic", "rev-a", "/automatic.ogg", 20_000L)
        val reservation = gate.reserve(identity)
        val manual = gate.request("manual", "rev-m", "/manual.ogg", 20_000L, 0L, true)

        assertNull(
            gate.acceptReservedAtAudibleBoundary(
                reservation,
                outgoing.token,
                evidence(reservation, identity.songId, callbackSequence = 2L)
            )
        )
        assertEquals(manual, gate.latest())
        assertNull(gate.reservation())
    }

    @Test
    fun staleOutgoingBoundaryCannotConsumeTheLiveSuccessorReservation() {
        val gate = EngineOwnerCommitGate()
        val staleOutgoing = committedSelection(gate, "stale", callbackSequence = 1L)
        val currentOutgoing = committedSelection(gate, "current", callbackSequence = 2L)
        val identity = EngineAudioIdentity("successor", "rev-s", "/successor.ogg", 20_000L)
        val reservation = gate.reserve(identity)
        val successorEvidence = evidence(reservation, identity.songId, callbackSequence = 3L)

        assertNull(
            gate.acceptReservedAtAudibleBoundary(
                reservation,
                staleOutgoing.token,
                successorEvidence
            )
        )
        assertEquals(currentOutgoing, gate.latest())
        assertEquals(reservation, gate.reservation())
        assertEquals(
            reservation.token,
            gate.acceptReservedAtAudibleBoundary(
                reservation,
                currentOutgoing.token,
                successorEvidence
            )?.selection?.token
        )
    }

    @Test
    fun exactPhysicallyInstalledPausedSuccessorClaimsReservationWithoutAudibleEvidence() {
        val gate = EngineOwnerCommitGate()
        val outgoing = committedSelection(gate, "paused-outgoing", callbackSequence = 1L)
        val identity = EngineAudioIdentity(
            "paused-successor",
            "rev-paused-successor",
            "/paused-successor.ogg",
            20_000L
        )
        val reservation = gate.reserve(identity)

        val successor = requireNotNull(
            gate.acceptReservedPausedInstalled(
                expected = reservation,
                expectedOutgoingToken = outgoing.token,
                startPositionMs = 0L
            )
        )

        assertEquals(reservation.token, successor.token)
        assertEquals(identity, successor.audioIdentity())
        assertFalse(successor.autoplay)
        assertEquals(successor, gate.latest())
        assertNull(gate.reservation())
        assertNull(gate.acceptReservedPausedInstalled(reservation, outgoing.token))
    }

    @Test
    fun manualSelectionStillDefeatsPausedAutomaticInstallation() {
        val gate = EngineOwnerCommitGate()
        val outgoing = committedSelection(gate, "paused-old", callbackSequence = 1L)
        val identity = EngineAudioIdentity("paused-auto", "rev-auto", "/auto.ogg", 20_000L)
        val reservation = gate.reserve(identity)
        val manual = gate.request("paused-manual", "rev-manual", "/manual.ogg", 20_000L, 0L, false)

        assertNull(gate.acceptReservedPausedInstalled(reservation, outgoing.token))
        assertEquals(manual, gate.latest())
        assertNull(gate.reservation())
    }

    private fun evidence(
        selection: EngineSelection,
        songId: String,
        callbackSequence: Long
    ) = EngineAudibleOwnerEvidence(
        token = selection.token,
        songId = songId,
        callbackSequence = callbackSequence,
        firstOutputFrame = callbackSequence * 256L,
        finalPcmFrames = 256,
        outputFingerprintSequence = callbackSequence
    )

    private fun evidence(
        reservation: EngineSelectionReservation,
        songId: String,
        callbackSequence: Long
    ) = EngineAudibleOwnerEvidence(
        token = reservation.token,
        songId = songId,
        callbackSequence = callbackSequence,
        firstOutputFrame = callbackSequence * 256L,
        finalPcmFrames = 256,
        outputFingerprintSequence = callbackSequence
    )

    private fun committedSelection(
        gate: EngineOwnerCommitGate,
        songId: String,
        callbackSequence: Long
    ): EngineSelection {
        val selection = gate.request(
            songId,
            "rev-$songId",
            "/$songId.ogg",
            20_000L,
            0L,
            true
        )
        assertEquals(
            selection,
            gate.accept(evidence(selection, songId, callbackSequence))?.selection
        )
        return selection
    }
}
