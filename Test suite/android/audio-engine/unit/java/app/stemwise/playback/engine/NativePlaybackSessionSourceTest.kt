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
internal class NativePlaybackSessionSourceTest : NativePlaybackSessionTestSupport() {
    @Test
    fun pausedPreparedStemsPresentationAndLaneOrderAreIndependentFromPlayPause() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("paused-stems-presentation", autoplay = false),
            metadata("paused-stems-presentation", 1L)
        )
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, generation = 700L, callbackSequence = 1L)
        val lanes = stems("paused-stems-presentation")
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease = lease,
                stemRevision = "paused-stems-r1",
                lanes = lanes,
                selectStems = true
            )
        )
        var committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val request = requireNotNull(committed.stems.desired)
        val sourceRevision = committed.source.desiredRevision
        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
                token,
                sourceRevision,
                EngineSourceMode.STEMS
            )
        )
        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                token,
                request.packageSignature,
                request.stemRevision,
                request.requestRevision,
                retainedPriorPackage = false
            )
        )

        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertFalse(committed.transport.playWhenReady)
        assertEquals(EngineSourceMode.STEMS, committed.source.desiredMode)
        assertEquals(sourceRevision, committed.source.preparedRevision)
        assertEquals(EngineSourceMode.STEMS, committed.source.preparedMode)
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, committed.stems.phase)
        assertNull(committed.stems.installed)
        val pausedProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = false,
            installedStemPackageMatchesDesired = false,
            previousResumeProof = null
        )
        assertEquals(EngineSourceMode.STEMS, pausedProjection.mode)
        assertEquals(EngineSourceMode.STEMS, pausedProjection.preparedMode)
        assertEquals(
            StemPresentationPackageAuthority.PREPARED,
            pausedProjection.stemPackageAuthority
        )
        val canonicalLaneOrder = lanes.map(EngineStemLane::id)
        val pausedLaneOrder = projectedStemPresentationValue(
            pausedProjection.stemPackageAuthority,
            installed = null,
            prepared = canonicalLaneOrder
        )
        assertSame(canonicalLaneOrder, pausedLaneOrder)
        val sourceBeforeTransport = committed.source
        val stemsBeforeTransport = committed.stems

        assertTrue(harness.adapter.play())
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertTrue(committed.transport.playWhenReady)
        assertEquals(sourceBeforeTransport, committed.source)
        assertEquals(stemsBeforeTransport, committed.stems)
        val playingProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = false,
            installedStemPackageMatchesDesired = false,
            previousResumeProof = pausedProjection.resumeProof
        )
        assertEquals(EngineSourceMode.STEMS, playingProjection.mode)
        assertEquals(
            StemPresentationPackageAuthority.PREPARED,
            playingProjection.stemPackageAuthority
        )
        assertSame(
            canonicalLaneOrder,
            projectedStemPresentationValue(
                playingProjection.stemPackageAuthority,
                installed = null,
                prepared = canonicalLaneOrder
            )
        )

        assertTrue(harness.adapter.pause())
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertFalse(committed.transport.playWhenReady)
        assertEquals(sourceBeforeTransport, committed.source)
        assertEquals(stemsBeforeTransport, committed.stems)
        val pausedAgainProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = false,
            installedStemPackageMatchesDesired = false,
            previousResumeProof = playingProjection.resumeProof
        )
        assertEquals(pausedProjection.mode, pausedAgainProjection.mode)
        assertEquals(
            pausedProjection.stemPackageAuthority,
            pausedAgainProjection.stemPackageAuthority
        )

        assertTrue(harness.adapter.play())
        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(
                token,
                sourceRevision,
                EngineSourceMode.STEMS
            )
        )
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val awaitingInstallProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = false,
            installedStemPackageMatchesDesired = false,
            previousResumeProof = pausedAgainProjection.resumeProof
        )
        assertEquals(EngineSourceMode.STEMS, awaitingInstallProjection.mode)
        assertEquals(
            StemPresentationPackageAuthority.PREPARED,
            awaitingInstallProjection.stemPackageAuthority
        )
        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageInstalled(
                token,
                request.packageSignature,
                request.stemRevision,
                request.requestRevision,
                callbackSequence = 2L
            )
        )
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val installedProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = true,
            installedStemPackageMatchesDesired = true,
            previousResumeProof = awaitingInstallProjection.resumeProof
        )
        assertEquals(EngineSourceMode.STEMS, installedProjection.mode)
        assertNull(installedProjection.preparedMode)
        assertEquals(
            StemPresentationPackageAuthority.INSTALLED,
            installedProjection.stemPackageAuthority
        )
        val installedLaneOrder = canonicalLaneOrder.toList()
        assertEquals(
            pausedLaneOrder,
            projectedStemPresentationValue(
                installedProjection.stemPackageAuthority,
                installed = installedLaneOrder,
                prepared = canonicalLaneOrder
            )
        )

        // Reusing the installed package cannot require another package-ready event.
        assertTrue(harness.adapter.pause())
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.ORIGINAL))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        harness.engine.emit(NativeEngineControllerEvent.SourceModeApplied(
            token, committed.source.desiredRevision, EngineSourceMode.ORIGINAL
        ))
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        harness.engine.emit(NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
            token, committed.source.desiredRevision, EngineSourceMode.STEMS
        ))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(PlaybackStemPackagePhase.INSTALLED, committed.stems.phase)
        val hotPausedProjection = projectedSourcePresentation(
            committed = committed,
            requestedStemCandidateMatches = true,
            installedStemCandidateAvailable = true,
            installedStemPackageMatchesDesired = true,
            previousResumeProof = null
        )
        assertEquals(EngineSourceMode.STEMS, hotPausedProjection.mode)
        assertNotNull(hotPausedProjection.resumeProof)
        assertEquals(pausedLaneOrder, projectedStemPresentationValue(
            hotPausedProjection.stemPackageAuthority,
            installed = installedLaneOrder,
            prepared = canonicalLaneOrder
        ))
        assertTrue(harness.adapter.play())
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        for (installedMatches in listOf(false, true)) {
            val hotPlayingProjection = projectedSourcePresentation(
                committed = committed,
                requestedStemCandidateMatches = true,
                installedStemCandidateAvailable = true,
                installedStemPackageMatchesDesired = installedMatches,
                previousResumeProof = hotPausedProjection.resumeProof
            )
            assertEquals(
                if (installedMatches) EngineSourceMode.STEMS else EngineSourceMode.ORIGINAL,
                hotPlayingProjection.mode
            )
        }
    }

    @Test
    fun seekRebasedReadyStemsRetainPreparedPresentationBeforeInstallAcknowledgement() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("seek-rebased-stems", autoplay = false),
            metadata("seek-rebased-stems", 1L)
        )
        harness.engine.publishOwner(token, generation = 710L, callbackSequence = 1L)
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        val canonicalLanes = stems("seek-rebased-stems")
        assertTrue(harness.adapter.configureStemPackageIfCurrent(
            lease, "seek-rebased-r1", canonicalLanes, selectStems = true
        ))
        var committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val packageRequest = requireNotNull(committed.stems.desired)
        val sourceRevision = committed.source.desiredRevision
        harness.engine.emit(NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
            token, sourceRevision, EngineSourceMode.STEMS
        ))
        harness.engine.emit(NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
            token, packageRequest.packageSignature, packageRequest.stemRevision,
            packageRequest.requestRevision, retainedPriorPackage = false
        ))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val pausedProjection = projectedSourcePresentation(
            committed, true, false, false, previousResumeProof = null
        )
        assertEquals(StemPresentationPackageAuthority.PREPARED, pausedProjection.stemPackageAuthority)
        assertNotNull(pausedProjection.resumeProof)

        val seek = requireNotNull(harness.adapter.seekToIfCurrent(lease, 5_000L))
        harness.engine.emit(NativeEngineControllerEvent.OwnerPublished(NativeEngineOwnerPublication(
            selection = requireNotNull(harness.engine.selections[token]),
            ownership = NativeRuntimeOwnership(token.value, 711L),
            metadata = null,
            kind = NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            evidence = null,
            completedSeekRevision = seek.seekRevision
        )))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(711L, committed.owner.ownership.generation)
        assertEquals(seek.seekRevision, committed.transport.completedSeekRevision)
        assertEquals(5_000L, committed.transport.presentedPositionMs)
        assertEquals(packageRequest, committed.stems.desired)
        val rebasedProjection = projectedSourcePresentation(
            committed, true, false, false, previousResumeProof = pausedProjection.resumeProof
        )
        assertNull(rebasedProjection.resumeProof)
        assertEquals(StemPresentationPackageAuthority.NONE, rebasedProjection.stemPackageAuthority)

        assertTrue(harness.adapter.play())
        // Source evidence arrives before the separate exact package-install acknowledgement.
        harness.engine.emit(NativeEngineControllerEvent.SourceModeApplied(
            token, sourceRevision, EngineSourceMode.STEMS
        ))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertTrue(committed.transport.playWhenReady)
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, committed.stems.phase)
        assertEquals(packageRequest, committed.stems.desired)
        assertNull(committed.stems.installed)
        val projection = projectedSourcePresentation(
            committed, true, false, false, previousResumeProof = rebasedProjection.resumeProof
        )
        assertEquals(EngineSourceMode.STEMS, projection.mode)
        assertEquals(EngineSourceMode.STEMS, projection.preparedMode)
        assertEquals(StemPresentationPackageAuthority.PREPARED, projection.stemPackageAuthority)
        assertSame(canonicalLanes, projectedStemPresentationValue(
            projection.stemPackageAuthority, installed = null, prepared = canonicalLanes
        ))

        val rejectedStates = listOf(
            committed.copy(source = committed.source.copy(
                desiredMode = EngineSourceMode.ORIGINAL,
                preparedMode = EngineSourceMode.ORIGINAL,
                appliedMode = EngineSourceMode.ORIGINAL
            )) to true,
            committed.copy(stems = committed.stems.copy(desired = packageRequest.copy(
                packageSignature = "different-package"
            ))) to false,
            committed.copy(issue = PlaybackPublicationIssue(
                category = "source-mode", cause = "source unavailable",
                recoverable = true, token = token
            )) to true,
            committed.copy(issue = PlaybackPublicationIssue(
                category = "stem-package", cause = "package unavailable",
                recoverable = true, token = token
            )) to true,
            committed.copy(source = committed.source.copy(
                desiredRevision = sourceRevision + 1L
            )) to true,
            committed.copy(source = committed.source.copy(
                appliedMode = EngineSourceMode.ORIGINAL
            )) to true,
            committed.copy(stems = committed.stems.copy(
                phase = PlaybackStemPackagePhase.PREPARING
            )) to true
        )
        for ((rejected, candidateMatches) in rejectedStates) {
            val rejectedProjection = projectedSourcePresentation(
                rejected, candidateMatches, false, false, previousResumeProof = null
            )
            assertEquals(StemPresentationPackageAuthority.NONE, rejectedProjection.stemPackageAuthority)
            assertNull(projectedStemPresentationValue(
                rejectedProjection.stemPackageAuthority, installed = null, prepared = canonicalLanes
            ))
        }
        harness.engine.emit(NativeEngineControllerEvent.StemPackageInstalled(
            token, packageRequest.packageSignature, packageRequest.stemRevision,
            packageRequest.requestRevision, callbackSequence = 2L
        ))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(PlaybackStemPackagePhase.INSTALLED, committed.stems.phase)
        assertNotNull(committed.stems.installed)
        val installedProjection = projectedSourcePresentation(
            committed, true, true, true, previousResumeProof = projection.resumeProof
        )
        assertEquals(StemPresentationPackageAuthority.INSTALLED, installedProjection.stemPackageAuthority)
        assertNull(installedProjection.preparedMode)
    }

    @Test
    fun exactStemCommitRejectsOutgoingOwnerMissingLaneAndPackageReplacementGap() {
        val harness = Harness()
        val first = harness.adapter.select(request("stem-first"), metadata("stem-first", 1L))
        harness.engine.publishOwner(first, generation = 151L, callbackSequence = 1L)
        val firstLanes = stems("stem-first")
        val firstSignature = installStemPackage(harness, first, firstLanes, "stem-first-r1")
        val firstMix = requireNotNull(harness.adapter.snapshot().latestRequest).controls.stemControls

        assertNull(
            harness.adapter.commitStemMixIfCurrent(
                token = first,
                packageSignature = firstSignature,
                controls = firstMix - firstLanes.first().id
            )
        )

        val replacementLanes = stems("stem-replacement")
        val firstLease = requireNotNull(harness.adapter.acquireCommandLease())
        assertEquals(first, firstLease.token)
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease = firstLease,
                stemRevision = "stem-first-r2",
                lanes = replacementLanes,
                selectStems = true
            )
        )
        val replacementRequest = requireNotNull(
            harness.adapter.snapshot().publication.committed?.stems?.desired
        )
        val replacementMix = requireNotNull(harness.adapter.snapshot().latestRequest)
            .controls
            .stemControls
        assertNull(
            "The retained installed package must not accept controls after desired replacement",
            harness.adapter.commitStemMixIfCurrent(first, firstSignature, firstMix)
        )
        assertNull(
            "The replacement is not controllable before exact installed proof",
            harness.adapter.commitStemMixIfCurrent(
                first,
                replacementRequest.packageSignature,
                replacementMix
            )
        )

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageInstalled(
                first,
                replacementRequest.packageSignature,
                replacementRequest.stemRevision,
                replacementRequest.requestRevision,
                callbackSequence = 10_000L
            )
        )
        assertNotNull(
            harness.adapter.commitStemMixIfCurrent(
                first,
                replacementRequest.packageSignature,
                replacementMix
            )
        )

        val second = harness.adapter.select(request("stem-second"), metadata("stem-second", 1L))
        harness.engine.publishOwner(second, generation = 152L, callbackSequence = 3L)
        assertNull(
            harness.adapter.commitStemMixIfCurrent(
                first,
                replacementRequest.packageSignature,
                replacementMix
            )
        )
        assertEquals(second, harness.adapter.snapshot().latestRequest?.selection?.token)
    }

    @Test
    fun frameBoundedStemPreviewAdmitsOnlyOneExactPackageRequestRevision() {
        assertTrue(stemMixPackageRequestRevisionsMatch(7L, 7L, 7L))
        assertFalse(stemMixPackageRequestRevisionsMatch(7L, 8L, 7L))
        assertFalse(stemMixPackageRequestRevisionsMatch(7L, 7L, 8L))
        assertFalse(stemMixPackageRequestRevisionsMatch(0L, 0L, 0L))

        val harness = Harness()
        val request = request("stem-preview-revision")
        val token = harness.adapter.select(request, metadata(request.songId, 1L))
        val ownership = harness.engine.publishOwner(token, generation = 153L, callbackSequence = 1L)
        val lanes = stems(request.songId)
        val packageSignature = installStemPackage(
            harness,
            token,
            lanes,
            "stem-preview-revision-r1"
        )
        val installed = requireNotNull(
            harness.adapter.snapshot().publication.committed?.stems?.installed
        )
        val lease = requireNotNull(
            harness.adapter.beginFrameBoundedStemMixPreviewIfCurrent(
                NativeAudioPlaybackCommandLease(
                    token = token,
                    songId = request.songId,
                    audioRevision = request.audioRevision
                ),
                laneIndex = 3
            )
        )

        assertEquals(ownership, lease.authority.ownership)
        assertEquals(packageSignature, lease.authority.packageSignature)
        assertEquals(installed.requestRevision, lease.authority.packageRequestRevision)
        assertEquals(3, lease.laneIndex)
        assertEquals(1, harness.engine.stemMixPreviewBegins)
    }

    @Test
    fun rapidSourceReversalAcceptsOnlyTheLatestStableSourceIntentRevision() {
        val harness = Harness()
        val token = harness.adapter.select(request("reverse"), metadata("reverse", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease,
                "stem-r1",
                stems("reverse"),
                selectStems = true
            )
        )
        harness.engine.publishOwner(token, 101L, 1L)
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.ORIGINAL))
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS))

        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(token, 2L, EngineSourceMode.STEMS)
        )
        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(token, 3L, EngineSourceMode.ORIGINAL)
        )
        var source = requireNotNull(harness.adapter.snapshot().publication.committed).source
        assertEquals(4L, source.desiredRevision)
        assertEquals(0L, source.appliedRevision)

        harness.engine.emit(
            NativeEngineControllerEvent.SourceModeApplied(token, 4L, EngineSourceMode.STEMS)
        )
        source = requireNotNull(harness.adapter.snapshot().publication.committed).source
        assertEquals(4L, source.appliedRevision)
        assertEquals(EngineSourceMode.STEMS, source.appliedMode)
    }

    @Test
    fun failedStemPackageCanReturnToOriginalAndRetryTheSameSixFilesWithFreshAuthority() {
        val harness = Harness()
        val token = harness.adapter.select(request("stem-retry"), metadata("stem-retry", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, 110L, 1L)
        val lanes = stems("stem-retry")
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease,
                "stem-r1",
                lanes,
                selectStems = true
            )
        )
        val failedRequest = requireNotNull(
            harness.adapter.snapshot().publication.committed?.stems?.desired
        )

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageFailed(
                token = token,
                packageSignature = failedRequest.packageSignature,
                stemRevision = failedRequest.stemRevision,
                requestRevision = failedRequest.requestRevision,
                recoverable = true,
                retainedPriorPackage = false,
                cause = "synthetic unreadable local stem"
            )
        )
        var committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(PlaybackStemPackagePhase.FAILED, committed.stems.phase)
        assertEquals("stem-package", committed.issue?.category)

        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.ORIGINAL))
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        assertEquals(EngineSourceMode.ORIGINAL, committed.source.desiredMode)
        assertNull(committed.issue)

        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease,
                "stem-r1",
                lanes,
                selectStems = true
            )
        )
        committed = requireNotNull(harness.adapter.snapshot().publication.committed)
        val retriedRequest = requireNotNull(committed.stems.desired)
        assertEquals(EngineSourceMode.STEMS, committed.source.desiredMode)
        assertEquals(PlaybackStemPackagePhase.REQUESTED, committed.stems.phase)
        assertEquals(failedRequest.packageSignature, retriedRequest.packageSignature)
        assertTrue(retriedRequest.requestRevision > failedRequest.requestRevision)
        assertNull(committed.issue)

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                token = token,
                packageSignature = failedRequest.packageSignature,
                stemRevision = failedRequest.stemRevision,
                requestRevision = failedRequest.requestRevision,
                retainedPriorPackage = false
            )
        )
        assertEquals(
            PlaybackStemPackagePhase.REQUESTED,
            harness.adapter.snapshot().publication.committed?.stems?.phase
        )
        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                token = token,
                packageSignature = retriedRequest.packageSignature,
                stemRevision = retriedRequest.stemRevision,
                requestRevision = retriedRequest.requestRevision,
                retainedPriorPackage = false
            )
        )
        assertEquals(
            PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE,
            harness.adapter.snapshot().publication.committed?.stems?.phase
        )
    }

    @Test
    fun oneExplicitStemsActionRetriesAnInstalledPackageThatFailedLater() {
        val harness = Harness()
        val token = harness.adapter.select(request("late-retry"), metadata("late-retry", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, 110L, 1L)
        assertTrue(harness.adapter.configureStemPackageIfCurrent(lease, "stem-r1", stems("late-retry"), true))
        val first = requireNotNull(harness.adapter.snapshot().publication.committed?.stems?.desired)
        harness.engine.emit(NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(token,
            first.packageSignature, first.stemRevision, first.requestRevision, false))
        harness.engine.emit(NativeEngineControllerEvent.StemPackageInstalled(token,
            first.packageSignature, first.stemRevision, first.requestRevision, 91L))
        assertEquals(PlaybackStemPackagePhase.INSTALLED, harness.adapter.snapshot().publication.committed?.stems?.phase)
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS))
        assertEquals(first, harness.adapter.snapshot().publication.committed?.stems?.desired)
        harness.engine.emit(NativeEngineControllerEvent.StemPackageFailed(token,
            first.packageSignature, first.stemRevision, first.requestRevision, true, false, "late decoder read"))
        assertEquals(PlaybackStemPackagePhase.FAILED, harness.adapter.snapshot().publication.committed?.stems?.phase)
        assertTrue(harness.adapter.setSourceModeIfCurrent(lease, EngineSourceMode.STEMS))
        val retried = requireNotNull(harness.adapter.snapshot().publication.committed?.stems?.desired)
        assertEquals(first.packageSignature, retried.packageSignature)
        assertTrue(retried.requestRevision > first.requestRevision)
        assertEquals(PlaybackStemPackagePhase.REQUESTED, harness.adapter.snapshot().publication.committed?.stems?.phase)
        assertNull(harness.adapter.snapshot().publication.committed?.issue)
        harness.engine.emit(NativeEngineControllerEvent.StemPackageFailed(token,
            first.packageSignature, first.stemRevision, first.requestRevision, true, false, "stale failure"))
        assertEquals(retried, harness.adapter.snapshot().publication.committed?.stems?.desired)
    }

    @Test
    fun stemReadinessAndWrongEventsNeverFabricateAnInstalledPackage() {
        val harness = Harness()
        val token = harness.adapter.select(request("stems"), metadata("stems", 1L))
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        harness.engine.publishOwner(token, 102L, 1L)
        assertTrue(harness.adapter.configureStemPackageIfCurrent(lease, "stem-r2", stems("stems")))
        val request = requireNotNull(
            harness.adapter.snapshot().publication.committed?.stems?.desired
        )

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackagePreparing(
                token,
                request.packageSignature,
                request.stemRevision,
                request.requestRevision,
                replacingPackageSignature = null
            )
        )
        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                token,
                request.packageSignature,
                request.stemRevision,
                request.requestRevision,
                retainedPriorPackage = false
            )
        )
        var stems = requireNotNull(harness.adapter.snapshot().publication.committed).stems
        assertEquals(PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE, stems.phase)
        assertNull(stems.installed)

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageInstalled(
                token,
                request.packageSignature + "-stale",
                request.stemRevision,
                request.requestRevision,
                callbackSequence = 90L
            )
        )
        stems = requireNotNull(harness.adapter.snapshot().publication.committed).stems
        assertNull(stems.installed)

        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageInstalled(
                token,
                request.packageSignature,
                request.stemRevision,
                request.requestRevision,
                callbackSequence = 91L
            )
        )
        stems = requireNotNull(harness.adapter.snapshot().publication.committed).stems
        assertEquals(PlaybackStemPackagePhase.INSTALLED, stems.phase)
        assertEquals(91L, stems.installed?.callbackSequence)
    }

    @Test
    fun fiveHundredPreviewSeeksStayNativeOnlyUntilOneExactPublicSeek() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("preview-pressure"),
            metadata("preview-pressure", 1L)
        )
        harness.engine.publishOwner(token, generation = 401L, callbackSequence = 1L)
        val publicStateBefore = harness.adapter.snapshot()
        val publicControlsBefore = requireNotNull(publicStateBefore.latestRequest).controls
        val publicTransportBefore = requireNotNull(publicStateBefore.publication.committed).transport
        val structuralRevisionBefore = publicStateBefore.structuralRevision
        val controlBuildsBefore = harness.adapter.controlSnapshotBuildCountForTest()
        assertEquals(0L, harness.adapter.acceptedPreviewSeekRevisionForTest(token))

        repeat(500) { index ->
            assertTrue(harness.adapter.previewSeekTo(token, (index + 1L) * 100L))
        }

        assertEquals(50_000L, requireNotNull(harness.engine.desired).requestedPositionMs)
        assertEquals(500L, harness.engine.desired?.seekRevision)
        assertEquals(500L, harness.adapter.acceptedPreviewSeekRevisionForTest(token))
        assertSame(publicStateBefore, harness.adapter.snapshot())
        assertSame(publicControlsBefore, harness.adapter.snapshot().latestRequest?.controls)
        assertSame(publicTransportBefore, harness.adapter.snapshot().publication.committed?.transport)
        assertEquals(structuralRevisionBefore, harness.adapter.snapshot().structuralRevision)
        assertEquals(controlBuildsBefore, harness.adapter.controlSnapshotBuildCountForTest())

        val combinedUpdatesBefore = harness.engine.combinedTransportUpdateCalls
        assertNotNull(
            harness.adapter.finishScrubWithReceipt(token, 12_345L, playWhenReady = false)
        )

        val published = harness.adapter.snapshot()
        val controls = requireNotNull(published.latestRequest).controls
        val transport = requireNotNull(published.publication.committed).transport
        assertEquals(501L, harness.adapter.acceptedPreviewSeekRevisionForTest(token))
        assertEquals(12_345L, requireNotNull(harness.engine.desired).requestedPositionMs)
        assertEquals(501L, harness.engine.desired?.seekRevision)
        assertFalse(requireNotNull(harness.engine.desired).playWhenReady)
        assertEquals(12_345L, controls.requestedPositionMs)
        assertEquals(501L, controls.seekRevision)
        assertFalse(controls.playWhenReady)
        assertEquals(12_345L, transport.requestedPositionMs)
        assertFalse(transport.playWhenReady)
        assertEquals(502L, transport.desiredRevision)
        assertEquals(structuralRevisionBefore + 1L, published.structuralRevision)
        assertEquals(controlBuildsBefore + 1L, harness.adapter.controlSnapshotBuildCountForTest())
        assertEquals(combinedUpdatesBefore + 1, harness.engine.combinedTransportUpdateCalls)
        assertEquals(token to false, harness.engine.audibleTransportIntents.last())
    }

    @Test
    fun previewSeekRejectsAdapterControllerRevisionDriftWithoutThrowing() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("preview-revision-drift"),
            metadata("preview-revision-drift", 1L)
        )
        harness.engine.publishOwner(token, generation = 402L, callbackSequence = 1L)
        val adapterRevision = requireNotNull(harness.adapter.snapshot().latestRequest)
            .controls
            .revision
        val diverged = requireNotNull(harness.engine.desired).copy(
            revision = adapterRevision + 1L
        )
        harness.engine.desired = diverged

        assertFalse(harness.adapter.previewSeekTo(token, 12_000L))
        assertEquals(diverged, harness.engine.desired)
        assertEquals(0L, harness.adapter.acceptedPreviewSeekRevisionForTest(token))
    }

    @Test
    fun equalAudibleIntentRearmsPhysicalTransportWhileNativePreviewMoves() {
        val harness = Harness()
        val token = harness.adapter.select(
            request("audible-preview", autoplay = true),
            metadata("audible-preview", 1L)
        )
        harness.engine.publishOwner(token, generation = 405L, callbackSequence = 1L)
        val publicBefore = harness.adapter.snapshot()
        val combinedBefore = harness.engine.combinedTransportUpdateCalls

        val receipt = requireNotNull(
            harness.adapter.setPlayWhenReadyIfCurrent(token, playWhenReady = true)
        )
        assertEquals(token, receipt.token)
        assertEquals(combinedBefore + 1, harness.engine.combinedTransportUpdateCalls)
        assertEquals(token to true, harness.engine.audibleTransportIntents.last())
        assertTrue(requireNotNull(harness.engine.desired).playWhenReady)

        assertTrue(harness.adapter.previewSeekTo(token, 24_000L))
        assertEquals(24_000L, requireNotNull(harness.engine.desired).requestedPositionMs)
        assertTrue(requireNotNull(harness.engine.desired).playWhenReady)
        assertEquals(
            requireNotNull(publicBefore.latestRequest).controls.revision + 1L,
            requireNotNull(harness.adapter.snapshot().latestRequest).controls.revision
        )
    }

    @Test
    fun previewSeekRejectsStaleTokenAndNonActiveLifecycleWithoutMovingNativeTarget() {
        val harness = Harness()
        val outgoing = harness.adapter.select(request("outgoing"), metadata("outgoing", 1L))
        val current = harness.adapter.select(request("current"), metadata("current", 1L))
        val initialDesired = requireNotNull(harness.engine.desired)

        assertFalse(harness.adapter.previewSeekTo(outgoing, 11_000L))
        assertEquals(initialDesired, harness.engine.desired)

        harness.adapter.stop()
        assertFalse(harness.adapter.previewSeekTo(current, 22_000L))
        assertEquals(initialDesired, harness.engine.desired)

        harness.adapter.close()
        assertFalse(harness.adapter.previewSeekTo(current, 33_000L))
        assertEquals(initialDesired, harness.engine.desired)
    }
}
