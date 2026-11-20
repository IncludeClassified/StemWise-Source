package app.stemwise.playback

import app.stemwise.analysis.MeterSegment
import app.stemwise.playback.engine.EngineSelection
import app.stemwise.playback.engine.EngineSelectionToken
import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.EngineStemLane
import app.stemwise.playback.engine.NativeEngineAutomaticEndPlan
import app.stemwise.playback.engine.NativeEngineMetadata
import app.stemwise.playback.engine.NativeEngineQueueItem
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.playback.engine.PlaybackPublicationSourceState
import app.stemwise.playback.engine.PlaybackStemPackagePhase
import app.stemwise.playback.engine.audioIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeAudioPlaybackFacadePolicyTest {
    @Test fun veryShortTrimUsesOrdinaryRepeatNavigationInsteadOfAnInvalidNativeLoop() {
        val short = app.stemwise.playback.engine.EngineAudioIdentity("trim", "audio", "/song.flac",
            10_000L, playbackStartMs = 1_000L, playbackEndMs = 1_001L)
        assertNull(automaticEndPlanForDecision(QueueDecision.Restart("trim"), short) { null })
        val supported = short.copy(playbackEndMs = 1_100L)
        assertTrue(automaticEndPlanForDecision(QueueDecision.Restart("trim"), supported) { null }
            is NativeEngineAutomaticEndPlan.RestartCurrent)
    }

    @Test fun trimmedPublicClockMapsToTheSameAbsoluteSourceForEveryTransportEntry() {
        val selected = EngineSelection(EngineSelectionToken(12L), "trim", "audio", "/song.flac",
            10_000L, 1_003L, false, playbackStartMs = 1_003L, playbackEndMs = 5_007L)
        assertEquals(4_004L, selected.playbackDurationMs)
        assertEquals(1_003L, selected.sourcePositionMs(0L))
        assertEquals(5_007L, selected.sourcePositionMs(4_004L))
        assertEquals(1_003L, selected.sourcePositionMs(-1L))
        assertEquals(5_007L, selected.sourcePositionMs(Long.MAX_VALUE))
        assertEquals(0L, selected.publicPositionMs(1_003L))
        assertEquals(997L, selected.publicPositionMs(2_000L))
        assertEquals(4_004L, selected.publicPositionMs(10_000L))
        assertEquals(0L, selected.publicPositionMs(0L))
    }

    @Test
    fun unresolvedCurrentSongShowsActionableTypedNoticeWithoutDamagingAudibleOwner() = runBlocking {
        for (failure in listOf(null, SecurityException("revoked"), java.io.IOException("provider failed"),
                kotlinx.coroutines.CancellationException("cancelled"))) {
            val before = AudioPlaybackState(songId = "healthy-a", playing = true,
                playWhenReady = true, positionMs = 12_345L, durationMs = 60_000L, loading = true)
            var state = before
            runCatching {
                resolveCurrentManualSelection<String>(
                    resolve = { if (failure != null) throw failure else null },
                    isCurrent = { true },
                    onUnavailable = { state = unresolvedSelectionState(state, songSelectionNotice(it)) }
                )
            }.onFailure { assertSame(failure, it) }
            assertEquals(before.copy(loading = false, notice = state.notice), state)
            if (failure is kotlinx.coroutines.CancellationException) {
                assertNull(state.notice)
            } else {
                assertEquals(PlaybackNoticeCategory.SONG_SELECTION, state.notice?.category)
                assertEquals("Song unavailable", state.notice?.title)
                assertTrue(requireNotNull(state.notice).message.contains("reimport"))
                if (failure is SecurityException) assertTrue(requireNotNull(state.notice).message.contains("Grant access"))
            }
        }
        assertNull(unresolvedSelectionState(AudioPlaybackState(loading = true), null).notice)
    }

    @Test
    fun lateUnavailableBDoesNotOverwriteCOrPublishAnError() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var serial = 1L
        var state = AudioPlaybackState(songId = "a", playing = true, loading = true)
        val b = async {
            resolveCurrentManualSelection<String>(
                resolve = { entered.complete(Unit); release.await(); throw SecurityException("late B") },
                isCurrent = { serial == 1L },
                onUnavailable = { state = unresolvedSelectionState(state, songSelectionNotice(it)) }
            )
        }
        entered.await()
        serial = 2L
        state = AudioPlaybackState(songId = "c", playing = true)
        val c = state
        release.complete(Unit)
        assertNull(b.await())
        assertSame(c, state)
        assertNull(state.notice)
    }

    @Test
    fun appliedControlsRequireExactNativePublicationAndCallbackEvidence() {
        val owner = NativeRuntimeOwnership(11L, 2L)
        val evidence = app.stemwise.playback.engine.NativeRuntimeAudibleControlEvidence(
            1L, owner, 7L, app.stemwise.playback.engine.NativeRuntimeSourceMode.ORIGINAL,
            3L, 4L, 5L, 64L)
        val publication = app.stemwise.playback.engine.NativeSourceControlPublicationDiagnostics(
            7L, owner, 11L, 4L, EngineSourceMode.ORIGINAL,
            app.stemwise.playback.engine.NativeRuntimeSourceMode.ORIGINAL,
            null, null, null)
        assertNull(callbackControlPublication(owner, evidence, emptyList()))
        assertNull(callbackControlPublication(owner, null, listOf(publication)))
        assertNull(callbackControlPublication(owner, evidence.copy(actualSourceMode =
            app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS), listOf(publication)))
        assertNull(callbackControlPublication(owner, evidence.copy(controlRevision = 8L), listOf(publication)))
        assertNull(callbackControlPublication(NativeRuntimeOwnership(11L, 3L), evidence, listOf(publication)))
        assertSame(publication, callbackControlPublication(owner, evidence, listOf(publication)))

    }

    @Test
    fun numericalApplicationUsesTheExactCallbackTupleIndependentlyOfLateMixRevision() {
        val owner = NativeRuntimeOwnership(11L, 2L)
        val oldPcmUnderNewMix = app.stemwise.playback.engine.NativeRuntimeAudibleControlEvidence(
            1L, owner, 8L, app.stemwise.playback.engine.NativeRuntimeSourceMode.ORIGINAL,
            3L, 4L, 5L, 64L, actualTempo = 1f, actualPitchSemitones = 0f)
        assertSame(oldPcmUnderNewMix, callbackTuningEvidence(owner, oldPcmUnderNewMix))
        assertEquals(1f, requireNotNull(callbackTuningEvidence(owner, oldPcmUnderNewMix)).actualTempo, 0f)
        assertNull(callbackTuningEvidence(owner, oldPcmUnderNewMix.copy(actualTempo = 0f)))
        assertNull(callbackTuningEvidence(NativeRuntimeOwnership(11L, 3L), oldPcmUnderNewMix))
        val processed = oldPcmUnderNewMix.copy(sequence = 2L, actualTempo = 1.5f, actualPitchSemitones = 3f)
        assertEquals(1.5f, requireNotNull(callbackTuningEvidence(owner, processed)).actualTempo, 0f)
        assertEquals(3f, requireNotNull(callbackTuningEvidence(owner, processed)).actualPitchSemitones, 0f)
    }

    @Test
    fun transientEmptyLibraryProjectionCannotUnloadTheCurrentSong() {
        assertTrue(emptyPlaybackQueueIsTransient(hasCurrentOwner = true, explicitUnloadPending = false, snapshotReady = false))
        assertFalse(emptyPlaybackQueueIsTransient(hasCurrentOwner = false, explicitUnloadPending = false, snapshotReady = false))
        assertFalse(emptyPlaybackQueueIsTransient(hasCurrentOwner = true, explicitUnloadPending = true, snapshotReady = false))
        assertFalse(emptyPlaybackQueueIsTransient(hasCurrentOwner = true, explicitUnloadPending = false, snapshotReady = true))
    }

    @Test
    fun loopBoundaryReleasePublishesOnlyItsAcceptedRangeAndRejectsAnOldBoundary() {
        val owner = app.stemwise.ui.NativeLoopBoundaryGestureOwner()
        val start = app.stemwise.ui.player.surface.FullPlayerLoopBoundary.START
        val end = app.stemwise.ui.player.surface.FullPlayerLoopBoundary.END
        assertTrue(owner.begin(start, 1_000L, 4_000L, 10_000L))
        owner.accept(start, 2_000L, 4_000L)
        assertNull(owner.end(end))
        assertEquals(2_000L to 4_000L, owner.end(start))
        assertNull(owner.end(start))
        assertTrue(owner.begin(end, 2_000L, 4_000L, 10_000L))
        owner.accept(start, 9_000L, 10_000L)
        assertEquals(2_000L to 4_000L, owner.end(end))
    }

    @Test
    fun slowStemActivationRemainsSelectedUntilItBecomesReady() = runBlocking {
        val settlements = MutableStateFlow<StemActivationSettlement?>(null)
        var source = EngineSourceMode.STEMS
        val activation = async {
            completeStemActivation(awaitStemActivationTerminalSettlement(settlements)) {
                source = EngineSourceMode.ORIGINAL
            }
        }

        delay(2_600L)
        assertFalse(activation.isCompleted)
        assertEquals(EngineSourceMode.STEMS, source)

        settlements.value = StemActivationSettlement.Ready
        assertTrue(withTimeout(1_000L) { activation.await() }.isSuccess)
        assertEquals(EngineSourceMode.STEMS, source)
    }

    @Test
    fun stemActivationRequiresExactSourceRevisionAndMode() {
        val stale = PlaybackPublicationSourceState(
            desiredRevision = 12L,
            desiredMode = EngineSourceMode.STEMS,
            preparedRevision = 11L,
            preparedMode = EngineSourceMode.STEMS,
            appliedRevision = 11L,
            appliedMode = EngineSourceMode.STEMS
        )
        assertFalse(stemActivationSourceIsReady(
            stale,
            PlaybackStemPackagePhase.INSTALLED,
            playWhenReady = true,
            sourceRevision = 12L,
            installedMatches = true
        ))
        assertFalse(stemActivationSourceIsReady(
            stale,
            PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
            playWhenReady = false,
            sourceRevision = 12L,
            installedMatches = false
        ))

        assertTrue(stemActivationSourceIsReady(
            stale.copy(appliedRevision = 12L),
            PlaybackStemPackagePhase.INSTALLED,
            playWhenReady = true,
            sourceRevision = 12L,
            installedMatches = true
        ))
        assertTrue(stemActivationSourceIsReady(
            stale.copy(preparedRevision = 12L),
            PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
            playWhenReady = false,
            sourceRevision = 12L,
            installedMatches = false
        ))
        for (installedMatches in listOf(false, true)) {
            assertEquals(installedMatches, stemActivationSourceIsReady(
                stale.copy(preparedRevision = 12L, appliedMode = EngineSourceMode.ORIGINAL),
                PlaybackStemPackagePhase.INSTALLED,
                playWhenReady = false,
                sourceRevision = 12L,
                installedMatches = installedMatches
            ))
        }
        assertFalse(stemActivationSourceIsReady(
            stale.copy(appliedRevision = 12L, appliedMode = EngineSourceMode.ORIGINAL),
            PlaybackStemPackagePhase.INSTALLED,
            playWhenReady = true,
            sourceRevision = 12L,
            installedMatches = true
        ))
        assertFalse(stemActivationSourceIsReady(
            stale.copy(preparedRevision = 12L, preparedMode = EngineSourceMode.ORIGINAL),
            PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
            playWhenReady = false,
            sourceRevision = 12L,
            installedMatches = false
        ))
    }

    @Test
    fun explicitStemActivationFailureStillRollsBackToOriginal() {
        var source = EngineSourceMode.STEMS

        val activation = completeStemActivation(StemActivationSettlement.Failed("unreadable")) {
            source = EngineSourceMode.ORIGINAL
        }

        assertTrue(activation.isFailure)
        assertEquals("unreadable", activation.exceptionOrNull()?.message)
        assertEquals(EngineSourceMode.ORIGINAL, source)
    }

    @Test
    fun supersededStemActivationIsBenignAndDoesNotRollbackTheNewOwner() {
        var rolledBack = false

        val activation = completeStemActivation(StemActivationSettlement.OwnerSuperseded) {
            rolledBack = true
        }

        assertTrue(activation.isSuccess)
        assertFalse(rolledBack)
    }

    @Test
    fun stemPreviewPackageAuthorityIncludesRequestRevisionNotOnlySignature() {
        assertTrue(
            stemMixPreviewPackageAuthorityMatches(
                installedSignature = "six-v1[same]",
                installedRequestRevision = 41L,
                desiredSignature = "six-v1[same]",
                desiredRequestRevision = 41L,
                authoritySignature = "six-v1[same]",
                authorityRequestRevision = 41L
            )
        )
        assertFalse(
            "A same-signature enrichment replacement must invalidate the old preview lease",
            stemMixPreviewPackageAuthorityMatches(
                installedSignature = "six-v1[same]",
                installedRequestRevision = 41L,
                desiredSignature = "six-v1[same]",
                desiredRequestRevision = 42L,
                authoritySignature = "six-v1[same]",
                authorityRequestRevision = 41L
            )
        )
        assertFalse(
            stemMixPreviewPackageAuthorityMatches(
                installedSignature = "six-v1[same]",
                installedRequestRevision = 42L,
                desiredSignature = "six-v1[same]",
                desiredRequestRevision = 42L,
                authoritySignature = "six-v1[same]",
                authorityRequestRevision = 41L
            )
        )
    }

    @Test
    fun routeTopologyGateIgnoresOnlyObservedNoOpsAndFailsSafeWithoutForgetting() {
        val gate = AudioOutputTopologyRecoveryGate(setOf(11, 22))

        // AudioManager's registration inventory describes the same topology in arbitrary order.
        assertFalse(gate.shouldRecover(setOf(22, 11)))

        // Real additions/removals recover once; a duplicate callback for the settled set is a no-op.
        assertTrue(gate.shouldRecover(setOf(11, 22, 33)))
        assertFalse(gate.shouldRecover(setOf(33, 22, 11)))
        assertTrue(gate.shouldRecover(setOf(11, 22)))

        // A failed/unusable query recovers conservatively and retains the last usable topology.
        assertTrue(gate.shouldRecover(null))
        assertTrue(gate.shouldRecover(emptySet()))
        assertFalse(gate.shouldRecover(setOf(22, 11)))

        // If the pre-runtime sample failed, the first usable inventory must recover and seed state.
        val unavailableBaseline = AudioOutputTopologyRecoveryGate(null)
        assertTrue(unavailableBaseline.shouldRecover(setOf(11)))
        assertFalse(unavailableBaseline.shouldRecover(setOf(11)))
    }

    @Test
    fun adapterProjectionCannotPublishPlayingBesideStaleFocusSuppression() {
        val resumedSuppression = projectedPlaybackSuppression(
            focusSuspended = false,
            previousSuppression = AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
        )
        assertEquals(AudioPlaybackSuppression.NONE, resumedSuppression)
        assertTrue(
            projectedPlaybackIsPlaying(
                playWhenReady = true,
                awaitingFocusResumeProof = false,
                ended = false,
                error = null,
                suppression = resumedSuppression
            )
        )

        val interruptedSuppression = projectedPlaybackSuppression(
            focusSuspended = true,
            previousSuppression = AudioPlaybackSuppression.NONE
        )
        assertEquals(
            AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS,
            interruptedSuppression
        )
        assertFalse(
            projectedPlaybackIsPlaying(
                playWhenReady = true,
                awaitingFocusResumeProof = false,
                ended = false,
                error = null,
                suppression = interruptedSuppression
            )
        )

        assertFalse(projectedPlaybackIsPlaying(true, true, false, null, resumedSuppression))
        assertFalse(projectedPlaybackIsPlaying(false, false, false, null, resumedSuppression))
        assertFalse(projectedPlaybackIsPlaying(true, false, true, null, resumedSuppression))
        assertFalse(projectedPlaybackIsPlaying(true, false, false, "decode failed", resumedSuppression))
        listOf(
            AudioPlaybackSuppression.UNSUITABLE_AUDIO_ROUTE,
            AudioPlaybackSuppression.UNSUITABLE_AUDIO_OUTPUT,
            AudioPlaybackSuppression.SCRUBBING
        ).forEach { nonFocusSuppression ->
            assertEquals(
                nonFocusSuppression,
                projectedPlaybackSuppression(false, nonFocusSuppression)
            )
            assertFalse(
                projectedPlaybackIsPlaying(
                    playWhenReady = true,
                    awaitingFocusResumeProof = false,
                    ended = false,
                    error = null,
                    suppression = nonFocusSuppression
                )
            )
        }
    }

    @Test
    fun scrubCommandsRequireTheExactStillLatestCommittedOwner() {
        assertTrue(
            scrubOwnerMatches(
                scrubOwnerToken = 7L,
                scrubSongId = "song-a",
                latestOwnerToken = 7L,
                committedOwnerToken = 7L,
                visibleSongId = "song-a"
            )
        )
        assertFalse(scrubOwnerMatches(7L, "song-a", 8L, 7L, "song-a"))
        assertFalse(scrubOwnerMatches(7L, "song-a", 7L, 8L, "song-a"))
        assertFalse(scrubOwnerMatches(7L, "song-a", 7L, 7L, "song-b"))
        assertFalse(scrubOwnerMatches(7L, "song-a", null, 7L, "song-a"))
    }

    @Test
    fun nativeScrubPreviewNeverExecutesExclusiveEndNavigation() {
        assertEquals(0L, nativeScrubPreviewTarget(-50L, 60_000L))
        assertEquals(12_345L, nativeScrubPreviewTarget(12_345L, 60_000L))
        assertEquals(59_999L, nativeScrubPreviewTarget(60_000L, 60_000L))
        assertEquals(59_999L, nativeScrubPreviewTarget(Long.MAX_VALUE, 60_000L))
        assertEquals(0L, nativeScrubPreviewTarget(1L, 0L))
        assertEquals(0L, nativeScrubPreviewTarget(1L, 1L))
    }

    @Test
    fun sourceSwitchRewindsOnlyAnActuallyEndedOwner() {
        assertFalse(sourceSwitchRequiresRewind(ended = false))
        assertTrue(sourceSwitchRequiresRewind(ended = true))
    }

    @Test
    fun decoderBankScrubPreviewsAreTimeBoundedWhileTheTerminalTargetRemainsIndependent() {
        assertTrue(scrubPreviewDispatchAllowed(null, candidateDispatchAtMs = 1_000L))
        assertFalse(scrubPreviewDispatchAllowed(1_000L, candidateDispatchAtMs = 1_063L))
        assertTrue(scrubPreviewDispatchAllowed(1_000L, candidateDispatchAtMs = 1_064L))
        assertTrue(
            "A monotonic-clock reset must not permanently suppress previews",
            scrubPreviewDispatchAllowed(1_000L, candidateDispatchAtMs = 999L)
        )
    }

    @Test
    fun nativeProjectionRetainsOnlySameOwnerIncompletePointA() {
        assertEquals(1_000L, retainedIncompleteLoopStart(1_000L, null, ownerChanged = false))
        assertNull(retainedIncompleteLoopStart(1_000L, null, ownerChanged = true))
        assertNull(retainedIncompleteLoopStart(1_000L, 2_000L, ownerChanged = false))
        assertNull(retainedIncompleteLoopStart(null, null, ownerChanged = false))
    }

    private val selection = EngineSelection(
        token = EngineSelectionToken(9L),
        songId = "song-a",
        audioRevision = "audio-r3",
        originalLocation = "/library/song-a/original.opus",
        durationMs = 242_147L,
        startPositionMs = 0L,
        autoplay = true
    )

    @Test
    fun sameAudioMetadataUpdateRequiresTheExactLatestAdapterIdentity() {
        assertTrue(
            sameAudioSelection(
                selection,
                "song-a",
                "audio-r3",
                "/library/song-a/original.opus",
                242_147L
            )
        )
        assertFalse(
            sameAudioSelection(
                selection,
                "song-a",
                "audio-r4",
                "/library/song-a/original.opus",
                242_147L
            )
        )
        assertFalse(
            sameAudioSelection(
                selection,
                "song-a",
                "audio-r3",
                "/library/song-a/replaced.opus",
                242_147L
            )
        )
    }

    @Test
    fun opaqueRevisionCannotHideChangedOriginalContentAtTheSameLocation() {
        val bound = selection.copy(originalContentSha256 = "1".repeat(64))
        assertTrue(sameAudioSelection(bound, bound.songId, bound.audioRevision,
            bound.originalLocation, bound.durationMs, "1".repeat(64)))
        assertFalse(sameAudioSelection(bound, bound.songId, bound.audioRevision,
            bound.originalLocation, bound.durationMs, "2".repeat(64)))
        assertFalse(sameAudioSelection(bound, bound.songId, bound.audioRevision,
            bound.originalLocation, bound.durationMs, null))
    }

    @Test
    fun pausedTargetRetainsOutgoingFocusUntilItsOwnerCommit() {
        assertEquals(
            SelectionFocusAction.RETAIN_THROUGH_PENDING,
            selectionFocusAction(autoplay = false)
        )
        assertFalse(ownerCommitShouldAbandonFocus(playWhenReady = true))
        assertTrue(ownerCommitShouldAbandonFocus(playWhenReady = false))
    }

    @Test
    fun failedAutoplaySelectionHandsHeldFocusToAnImmediateQueueSkipOnly() {
        assertTrue(failedSelectionMayHandOffFocus(false, true, true))
        assertFalse(failedSelectionMayHandOffFocus(true, true, true))
        assertFalse(failedSelectionMayHandOffFocus(false, false, true))
        assertFalse(failedSelectionMayHandOffFocus(false, true, false))
    }

    @Test
    fun lateManualSelectionFailureCannotMutateANewerFacadeRequest() {
        assertTrue(selectionFailureBelongsToLatestRequest(7L, 7L))
        assertTrue(selectionFailureBelongsToLatestRequest(0L, 8L))
        assertFalse(selectionFailureBelongsToLatestRequest(7L, 8L))
    }

    @Test
    fun metronomeMeterProjectionPreservesExplicitGapsAndDropsInvalidOverlap() {
        val projected = projectEngineMeterSegments(
            listOf(
                MeterSegment(0L, 1_000L, 3, 4),
                MeterSegment(1_500L, 2_500L, 5, 8),
                MeterSegment(2_400L, 3_000L, 7, 8),
                MeterSegment(4_000L, 3_900L, 4, 4)
            )
        )

        assertEquals(2, projected.size)
        assertEquals(0L, projected[0].startMs)
        assertEquals(1_000L, projected[0].endMs)
        assertEquals(1_500L, projected[1].startMs)
        assertEquals(2_500L, projected[1].endMs)
        assertTrue(projected[0].endMs < projected[1].startMs)
    }

    @Test
    fun outgoingAudibleEndCannotAdvanceQueueWhileANewerTokenIsPending() {
        val outgoing = EngineSelectionToken(10L)
        val pending = EngineSelectionToken(11L)
        val outgoingOwner = NativeRuntimeOwnership(outgoing.value, 100L)

        assertTrue(
            audibleEndMayNavigate(
                committedToken = outgoing,
                latestRequestedToken = outgoing,
                committedOwnership = outgoingOwner,
                effectToken = outgoing,
                effectOwnership = outgoingOwner
            )
        )
        assertFalse(
            audibleEndMayNavigate(
                committedToken = outgoing,
                latestRequestedToken = pending,
                committedOwnership = outgoingOwner,
                effectToken = outgoing,
                effectOwnership = outgoingOwner
            )
        )
        assertFalse(
            audibleEndMayNavigate(
                committedToken = outgoing,
                latestRequestedToken = outgoing,
                committedOwnership = NativeRuntimeOwnership(outgoing.value, 101L),
                effectToken = outgoing,
                effectOwnership = outgoingOwner
            )
        )


    }

    @Test fun queuedAudibleEndCannotStopAnAcceptedSameTokenSeekBeforeItsAcknowledgement() {
        val replay = app.stemwise.playback.engine.PublicationReplayDriver()
        try {
            val token = replay.selectSession("last")
            replay.presentSession()
            replay.postSessionEnd()
            val posted = replay.takePostedSessionEnd()
            val originalOwner = checkNotNull(replay.sessionSnapshot().publication.committed).owner.ownership
            val seek = checkNotNull(replay.seekSession(5_000L))
            val pending = replay.sessionSnapshot()
            assertEquals(token, seek.token)
            assertEquals(originalOwner, checkNotNull(pending.publication.committed).owner.ownership)
            assertTrue(seek.seekRevision > pending.publication.committed!!.transport.completedSeekRevision)
            val queue = PlaybackQueueController(1L).apply { start(listOf("last"), "last", false) }
            var visible = AudioPlaybackState(songId = "last", playing = true, playWhenReady = true,
                positionMs = seek.positionMs, durationMs = 60_000L)
            var navigationCalls = 0
            dispatchAudibleEnd(pending, posted, rewindForSourceChange = { false }, onEnd = { committed ->
                navigationCalls++
                assertEquals(QueueDecision.Stop, queue.preview(QueueTransition.AUTOMATIC_END, "last"))
                replay.pauseSession()
                visible = endedAtDurationState(visible, committed.selection.durationMs)
            })
            assertEquals(0, navigationCalls)
            assertEquals(0, replay.sessionPauseCalls())
            assertFalse(visible.ended)
            assertTrue(checkNotNull(replay.sessionSnapshot().latestRequest).controls.playWhenReady)
            assertEquals("last", queue.snapshot().currentId)
            replay.presentSession(completedSeekRevision = seek.seekRevision)
            val completed = checkNotNull(replay.sessionSnapshot().publication.committed)
            assertEquals(seek.seekRevision, completed.transport.completedSeekRevision)
            assertEquals(5_000L, completed.transport.presentedPositionMs)
            assertNotEquals(originalOwner, completed.owner.ownership)
            var oldEndAfterCompletion = 0
            dispatchAudibleEnd(replay.sessionSnapshot(), posted, { false }, { oldEndAfterCompletion++ })
            assertEquals(0, oldEndAfterCompletion)
        } finally { replay.closeSession() }
    }

    @Test fun ordinaryReducerEndStillReachesRepeatOffStop() {
        val replay = app.stemwise.playback.engine.PublicationReplayDriver()
        try {
            replay.selectSession("last")
            replay.presentSession()
            replay.postSessionEnd()
            val queue = PlaybackQueueController(1L).apply { start(listOf("last"), "last", false) }
            var visible = AudioPlaybackState(songId = "last", playing = true, playWhenReady = true)
            var navigationCalls = 0
            dispatchAudibleEnd(replay.sessionSnapshot(), replay.takePostedSessionEnd(), { false }, { committed ->
                navigationCalls++
                assertEquals(QueueDecision.Stop, queue.preview(QueueTransition.AUTOMATIC_END, "last"))
                replay.pauseSession()
                visible = endedAtDurationState(visible, committed.selection.durationMs)
            })
            assertEquals(1, navigationCalls)
            assertEquals(1, replay.sessionPauseCalls())
            assertTrue(visible.ended)
            assertEquals(60_000L, visible.positionMs)
        } finally { replay.closeSession() }
    }

    @Test fun explicitTerminalSeekStillExecutesWhileAnEarlierPositionSeekIsPending() {
        val replay = app.stemwise.playback.engine.PublicationReplayDriver()
        try {
            val token = replay.selectSession("last")
            replay.presentSession()
            checkNotNull(replay.seekSession(5_000L))
            val snapshot = replay.sessionSnapshot()
            assertEquals(TerminalSeekResolution.EXECUTE,
                terminalSeekResolution(TokenBoundTerminalSeek(token, 60_000L),
                    snapshot.latestRequest?.selection?.token, snapshot.publication.committed?.selection?.token))
            val queue = PlaybackQueueController(1L).apply { start(listOf("last"), "last", false) }
            assertEquals(QueueDecision.Stop, queue.preview(QueueTransition.AUTOMATIC_END, "last"))
            assertTrue(replay.pauseSession())
            val ended = endedAtDurationState(AudioPlaybackState(songId = "last"), 60_000L)
            assertTrue(ended.ended)
            assertEquals(1, replay.sessionPauseCalls())
        } finally { replay.closeSession() }
    }

    @Test
    fun activeScrubSuppressesSameTokenEndAcrossNativeGenerationRace() {
        val token = EngineSelectionToken(12L)
        val staleMainOwnership = NativeRuntimeOwnership(token.value, 40L)
        val newerAdapterOwnership = NativeRuntimeOwnership(token.value, 41L)

        assertTrue(
            scrubEndIsSuppressed(
                activeScrubToken = token,
                retainedToken = token,
                retainedOwnership = staleMainOwnership,
                effectToken = token,
                effectOwnership = newerAdapterOwnership
            )
        )
        assertTrue(
            scrubEndIsSuppressed(
                activeScrubToken = null,
                retainedToken = token,
                retainedOwnership = newerAdapterOwnership,
                effectToken = token,
                effectOwnership = newerAdapterOwnership
            )
        )
        assertFalse(
            scrubEndIsSuppressed(
                activeScrubToken = null,
                retainedToken = token,
                retainedOwnership = staleMainOwnership,
                effectToken = token,
                effectOwnership = newerAdapterOwnership
            )
        )
    }

    @Test
    fun automaticEndCannotAdvancePastAnUnresolvedOrPendingSelection() {
        val outgoing = EngineSelectionToken(20L)
        val pending = EngineSelectionToken(21L)

        assertFalse(
            automaticEndOwnerIsCurrent(
                selectionResolutionPending = true,
                committedToken = outgoing,
                latestRequestedToken = outgoing,
                requestedOwnerToken = outgoing
            )
        )
        assertFalse(
            automaticEndOwnerIsCurrent(
                selectionResolutionPending = false,
                committedToken = outgoing,
                latestRequestedToken = pending,
                requestedOwnerToken = outgoing
            )
        )
        assertTrue(
            automaticEndOwnerIsCurrent(
                selectionResolutionPending = false,
                committedToken = pending,
                latestRequestedToken = pending,
                requestedOwnerToken = pending
            )
        )
        assertFalse(
            automaticEndQueueOwnerIsCurrent(
                queueCurrentSongId = "pending-song",
                committedSongId = "outgoing-song"
            )
        )
        assertTrue(
            automaticEndQueueOwnerIsCurrent(
                queueCurrentSongId = "pending-song",
                committedSongId = "pending-song"
            )
        )


    }

    @Test
    fun exactTerminalSeekWaitsForItsPendingOwnerThenExecutesExactlyOnce() {
        val outgoing = EngineSelectionToken(30L)
        val pending = EngineSelectionToken(31L)
        val request = TokenBoundTerminalSeek(pending, positionMs = 60_000L)

        assertEquals(
            TerminalSeekResolution.WAIT_FOR_OWNER,
            terminalSeekResolution(request, latestRequestedToken = pending, committedToken = outgoing)
        )
        assertEquals(
            TerminalSeekResolution.EXECUTE,
            terminalSeekResolution(request, latestRequestedToken = pending, committedToken = pending)
        )
    }

    @Test
    fun terminalSeekIsCancelledByANewerSelection() {
        val requested = EngineSelectionToken(40L)
        val newer = EngineSelectionToken(41L)
        val request = TokenBoundTerminalSeek(requested, positionMs = 60_000L)

        assertEquals(
            TerminalSeekResolution.CANCEL,
            terminalSeekResolution(request, latestRequestedToken = newer, committedToken = requested)
        )
    }

    @Test
    fun repeatOffTerminalStopRetainsDurationAndClearsTransportIntent() {
        val queue = PlaybackQueueController().apply {
            start(listOf("final"), startId = "final", shuffle = false)
            setRepeatMode(RepeatMode.OFF)
        }
        assertEquals(
            QueueDecision.Stop,
            queue.decide(QueueTransition.AUTOMATIC_END)
        )

        val stopped = endedAtDurationState(
            AudioPlaybackState(
                songId = "final",
                playing = true,
                playWhenReady = true,
                positionMs = 59_999L,
                durationMs = 60_000L
            ),
            durationMs = 60_000L
        )
        assertEquals(60_000L, stopped.positionMs)
        assertFalse(stopped.playing)
        assertFalse(stopped.playWhenReady)
        assertTrue(stopped.ended)
    }

    @Test
    fun automaticEndPolicySeparatesNextOwnerFromSameOwnerRestart() {
        val next = queueItem("song-b")
        assertEquals(
            NativeEngineAutomaticEndPlan.NextOwner(next),
            automaticEndPlanForDecision(
                QueueDecision.Move("song-b", 1),
                currentIdentity = selection.audioIdentity()
            ) { next }
        )
        assertEquals(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                selection.audioIdentity(),
                selection.durationMs
            ),
            automaticEndPlanForDecision(
                QueueDecision.Restart("song-a"),
                currentIdentity = selection.audioIdentity()
            ) { error("Restart Current must not resolve or allocate a next owner") }
        )
        assertNull(automaticEndPlanForDecision(
            QueueDecision.Stop, selection.audioIdentity()
        ) { error("Stop must not resolve a next owner") })
        assertNull(automaticEndPlanForDecision(
            QueueDecision.Boundary, selection.audioIdentity()
        ) { error("Boundary must not resolve a next owner") })
    }

    @Test
    fun repeatOnePrewarmsNaturalSuccessorFallbackWhileSingleItemRepeatAllDoesNot() {
        val repeatOne = PlaybackQueueController().apply {
            start(listOf("song-a", "song-b"), startId = "song-a", shuffle = false)
            setRepeatMode(RepeatMode.ONE)
        }
        val repeatIdentity = selection.audioIdentity().copy(songId = "song-a")
        val fallback = queueItem("song-b")
        assertEquals(
            QueueDecision.Move("song-b", 1),
            repeatOne.previewAutomaticEndIgnoringRepeatOne()
        )
        assertEquals(
            NativeEngineAutomaticEndPlan.RestartCurrent(
                identity = repeatIdentity,
                metadataDurationHintMs = repeatIdentity.durationMs,
                fallbackNext = fallback
            ),
            automaticEndPlanForDecision(
                repeatOne.previewAutomaticEnd(),
                currentIdentity = repeatIdentity,
                fallbackNext = fallback
            ) { error("Repeat One restart must use its explicitly resolved fallback") }
        )

        val singleRepeatAll = PlaybackQueueController().apply {
            start(listOf("only"), shuffle = false)
            setRepeatMode(RepeatMode.ALL)
        }
        val onlyIdentity = selection.audioIdentity().copy(songId = "only")
        assertTrue(
            automaticEndPlanForDecision(
                singleRepeatAll.previewAutomaticEnd(),
                currentIdentity = onlyIdentity
            ) { error("Single-item Repeat All must not resolve a different owner") } is
                NativeEngineAutomaticEndPlan.RestartCurrent
        )
    }

    @Test
    fun selectedSixAssetsOwnStemRevisionInsteadOfTheSongRowsLegacyRevision() {
        val first = derivedStemPackageIdentity("song-a", lanes(contentSuffix = "v1"))
        val repeated = derivedStemPackageIdentity("song-a", lanes(contentSuffix = "v1"))
        val replacement = derivedStemPackageIdentity("song-a", lanes(contentSuffix = "v2"))

        assertEquals(first, repeated)
        assertNotEquals(first.packageSignature, replacement.packageSignature)
        assertNotEquals(first.stemRevision, replacement.stemRevision)
        assertTrue(first.stemRevision.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(
            persistedStemPackageIdentity(first.packageSignature),
            persistedStemPackageIdentity(replacement.packageSignature)
        )
        assertNotEquals(first.packageSignature, persistedStemPackageIdentity(first.packageSignature))
        assertTrue(persistedStemPackageIdentity(first.packageSignature).matches(Regex("[0-9a-f]{64}")))

    }

    @Test
    fun loopRejectionClearsOnlyActiveLoopAndPreservesPlayingTransport() {
        val before = AudioPlaybackState(
            songId = "song-a",
            playing = true,
            playWhenReady = true,
            positionMs = 2_000L,
            durationMs = 60_000L,
            loopA = 1_000L,
            loopB = 5_000L
        )

        val after = loopRejectedState(before, "Loop head not ready")

        assertTrue(after.playing)
        assertTrue(after.playWhenReady)
        assertEquals("song-a", after.songId)
        assertEquals(2_000L, after.positionMs)
        assertNull(after.loopA)
        assertNull(after.loopB)
        assertEquals("Loop head not ready", after.notice?.message)
        assertEquals(PlaybackNoticeCategory.LOOP, after.notice?.category)
    }

    @Test
    fun positionOnlyUpdatesReuseTheExactSessionMediaItem() {
        val cache = SessionMediaItemCache<Any>()
        val identity = SessionMediaIdentity(ownerToken = 42L, metadataRevision = 7L)
        var builds = 0
        fun item(positionMs: Long): Any = cache.getOrCreate(identity) {
            builds++
            Any()
        }.also { assertTrue(positionMs >= 0L) }

        val atStart = item(0L)
        val afterManyPositionCallbacks = item(59_999L)
        assertSame(atStart, afterManyPositionCallbacks)
        assertEquals(1, builds)

        val afterMetadataChange = cache.getOrCreate(identity.copy(metadataRevision = 8L)) {
            builds++
            Any()
        }
        assertNotEquals(atStart, afterMetadataChange)
        assertEquals(2, builds)
    }

    @Test
    fun seekProjectionRequiresExactOwnerAndCompletedRevision() {
        // Position/callback proximity is deliberately irrelevant, including paused and short loops.
        assertFalse(nativeSeekRevisionAcknowledged(7L, 7L, 3L, 2L))
        assertFalse(nativeSeekRevisionAcknowledged(7L, 8L, 3L, 3L))
        assertFalse(nativeSeekRevisionAcknowledged(7L, 7L, 0L, 3L))
        assertTrue(nativeSeekRevisionAcknowledged(7L, 7L, 3L, 3L))
        assertTrue(nativeSeekRevisionAcknowledged(7L, 7L, 3L, 4L))
    }

    @Test
    fun onlyExactPausedPreparedOriginalMaySettleWithoutAudibleEvidence() {
        assertTrue(
            pausedPreparedOriginalMatches(
                playWhenReady = false,
                desiredRevision = 12L,
                desiredSourceMode = EngineSourceMode.ORIGINAL,
                preparedRevision = 12L,
                preparedSourceMode = EngineSourceMode.ORIGINAL
            )
        )
        assertFalse(
            pausedPreparedOriginalMatches(
                false,
                12L,
                EngineSourceMode.ORIGINAL,
                11L,
                EngineSourceMode.ORIGINAL
            )
        )
        assertFalse(
            pausedPreparedOriginalMatches(
                true,
                12L,
                EngineSourceMode.ORIGINAL,
                12L,
                EngineSourceMode.ORIGINAL
            )
        )
        assertFalse(
            pausedPreparedOriginalMatches(
                false,
                12L,
                EngineSourceMode.STEMS,
                12L,
                EngineSourceMode.ORIGINAL
            )
        )
    }

    @Test
    fun playResumeCannotChangeAnExactlyPreparedSourceBeforeAudibleEvidence() {
        val stems = PreparedSourceResumeProof(
            ownerToken = 41L,
            desiredRevision = 12L,
            mode = EngineSourceMode.STEMS,
            stemPackageSignature = "package-a",
            stemRevision = "stem-r7"
        )
        assertTrue(
            preparedSourceResumeProofMatches(
                proof = stems,
                ownerToken = 41L,
                desiredRevision = 12L,
                desiredSourceMode = EngineSourceMode.STEMS,
                preparedRevision = 12L,
                preparedSourceMode = EngineSourceMode.STEMS,
                stemPhase = PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
                installedStemPackageMatchesDesired = false,
                desiredPackageSignature = "package-a",
                desiredStemRevision = "stem-r7",
                issueCategory = null
            )
        )

        val original = PreparedSourceResumeProof(
            ownerToken = 41L,
            desiredRevision = 13L,
            mode = EngineSourceMode.ORIGINAL
        )
        assertTrue(
            preparedSourceResumeProofMatches(
                proof = original,
                ownerToken = 41L,
                desiredRevision = 13L,
                desiredSourceMode = EngineSourceMode.ORIGINAL,
                preparedRevision = 13L,
                preparedSourceMode = EngineSourceMode.ORIGINAL,
                stemPhase = PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
                installedStemPackageMatchesDesired = false,
                desiredPackageSignature = "package-a",
                desiredStemRevision = "stem-r7",
                issueCategory = null
            )
        )

        assertFalse(
            preparedSourceResumeProofMatches(
                proof = stems,
                ownerToken = 41L,
                desiredRevision = 12L,
                desiredSourceMode = EngineSourceMode.STEMS,
                preparedRevision = 12L,
                preparedSourceMode = EngineSourceMode.STEMS,
                stemPhase = PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
                installedStemPackageMatchesDesired = false,
                desiredPackageSignature = "replacement-package",
                desiredStemRevision = "stem-r7",
                issueCategory = null
            )
        )
        assertFalse(
            preparedSourceResumeProofMatches(
                proof = stems,
                ownerToken = 42L,
                desiredRevision = 12L,
                desiredSourceMode = EngineSourceMode.STEMS,
                preparedRevision = 12L,
                preparedSourceMode = EngineSourceMode.STEMS,
                stemPhase = PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
                installedStemPackageMatchesDesired = false,
                desiredPackageSignature = "package-a",
                desiredStemRevision = "stem-r7",
                issueCategory = null
            )
        )
    }

    private fun lanes(contentSuffix: String): List<EngineStemLane> =
        listOf("vocals", "drums", "bass", "guitar", "piano", "remaining").map { role ->
            EngineStemLane(
                id = "lane-$role",
                normalizedRole = role,
                location = "/song-a/$role.opus",
                contentSha256 = "sha-$role-$contentSuffix"
            )
        }

    private fun queueItem(songId: String): NativeEngineQueueItem {
        val identity = selection.audioIdentity().copy(
            songId = songId,
            audioRevision = "revision-$songId",
            originalLocation = "/library/$songId/original.opus"
        )
        return NativeEngineQueueItem(
            identity,
            NativeEngineMetadata(songId, identity.audioRevision, 1L)
        )
    }

}
