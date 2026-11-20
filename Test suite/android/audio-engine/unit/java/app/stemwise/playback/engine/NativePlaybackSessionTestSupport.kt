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

private fun NativeAudioPlaybackSessionAdapter.select(
    request: NativeEngineSelectionRequest,
    metadata: PlaybackPublicationMetadata,
    requestTag: Long = 0L
): EngineSelectionToken = checkNotNull(trySelect(request, metadata, requestTag)) {
    "The native playback session requires a clean process restart"
}

internal open class NativePlaybackSessionTestSupport {

    protected class Harness {
        val engine = FakeEngine()
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val adapter = NativeAudioPlaybackSessionAdapter(
            engineFactory = { listener ->
                engine.listener = listener
                engine
            },
            effectListener = NativeAudioPlaybackSessionEffectListener(effects::add)
        )
    }

    protected class FakeEngine : NativeAudioPlaybackSessionEngine {
        lateinit var listener: NativeEngineControllerListener
        val selections = linkedMapOf<EngineSelectionToken, EngineSelection>()
        val metadata = linkedMapOf<EngineSelectionToken, NativeEngineMetadata>()
        var desired: EngineDesiredState? = null
        var lastDesiredRequest: EngineDesiredState? = null
        var explicitLoopState: EngineExplicitLoopState? = null
        var lastLikelyNext: NativeEngineQueueItem? = null
        var lastAutomaticEndPlan: NativeEngineAutomaticEndPlan? = null
        var automaticEndPlanCalls = 0
        var manualSelectionIntentAdmissionCalls = 0
        var manualSelectionIntentCompletionToken: EngineSelectionToken? = null
        var focusSuspends = 0
        var focusResumes = 0
        var routeRecoveries = 0
        var holdCalls = 0
        var releaseHoldCalls = 0
        var holdAction: (() -> Boolean)? = null
        val audibleTransportIntents = mutableListOf<Pair<EngineSelectionToken, Boolean>>()
        var combinedTransportUpdateCalls = 0
        var desiredUpdateCalls = 0
        var transportSeekLoopUpdates = 0
        var stemMixPreviewBegins = 0
        var closeCalls = 0
        var selectCalls = 0
        private var fatalQuarantined = false
        private var nextSelectionAdmission: NativeEngineSelectionAdmission? = null
        private var tokenSequence = 0L
        private var stopSequence = 0L

        override fun select(
            request: NativeEngineSelectionRequest,
            metadata: NativeEngineMetadata
        ): EngineSelectionToken {
            selectCalls++
            val token = EngineSelectionToken(++tokenSequence)
            val selection = EngineSelection(
                token,
                request.songId,
                request.audioRevision,
                request.originalLocation,
                request.durationMs,
                request.startPositionMs,
                request.autoplay,
                originalContentSha256 = request.originalContentSha256,
                playbackStartMs = request.playbackStartMs,
                playbackEndMs = request.playbackEndMs
            )
            selections[token] = selection
            this.metadata[token] = metadata
            desired = initialEngineDesiredState(selection, request.initialControls)
            explicitLoopState = EngineExplicitLoopState(token, 0L, null)
            return token
        }

        override fun selectAdmission(
            request: NativeEngineSelectionRequest,
            metadata: NativeEngineMetadata
        ): NativeEngineSelectionAdmission {
            val admission = nextSelectionAdmission ?: return super.selectAdmission(
                request,
                metadata
            )
            nextSelectionAdmission = null
            val selection = requireNotNull(selections[admission.token])
            require(selection.audioIdentity() == request.identity())
            require(selection.startPositionMs == request.startPositionMs)
            require(selection.autoplay == request.autoplay)
            this.metadata[admission.token] = metadata
            return admission
        }

        override fun updateDesired(
            token: EngineSelectionToken,
            transform: (EngineDesiredState) -> EngineDesiredState
        ): Boolean {
            val previous = desired?.takeIf { it.selection.token == token } ?: return false
            desiredUpdateCalls++
            val requested = transform(previous)
            lastDesiredRequest = requested
            desired = acceptedEngineDesiredState(previous, requested)
            return true
        }

        override fun updateDesiredIfRevision(
            token: EngineSelectionToken,
            expectedRevision: Long,
            transform: (EngineDesiredState) -> EngineDesiredState
        ): EngineDesiredState? {
            val previous = desired?.takeIf { it.selection.token == token } ?: return null
            if (previous.revision != expectedRevision) return null
            return if (updateDesired(token, transform)) desired else null
        }

        override fun updateDesiredWithAudibleTransportIfRevision(
            token: EngineSelectionToken,
            expectedRevision: Long,
            playWhenReady: Boolean,
            transform: (EngineDesiredState) -> EngineDesiredState
        ): EngineDesiredState? {
            combinedTransportUpdateCalls++
            val updated = updateDesiredIfRevision(token, expectedRevision, transform)
            if (updated != null) audibleTransportIntents += token to playWhenReady
            return updated
        }

        override fun updateExplicitLoop(state: EngineExplicitLoopState): Boolean {
            val previous = explicitLoopState ?: return false
            if (previous.token != state.token || state.revision != previous.revision + 1L) {
                return false
            }
            explicitLoopState = state
            return true
        }

        override fun updateExplicitLoopForTransportSeek(
            state: EngineExplicitLoopState
        ): Boolean {
            transportSeekLoopUpdates++
            return updateExplicitLoop(state)
        }

        override fun updateMetadata(metadata: NativeEngineMetadata) {
            selections.keys.lastOrNull {
                selections[it]?.songId == metadata.songId &&
                    selections[it]?.audioRevision == metadata.audioRevision
            }?.let { this.metadata[it] = metadata }
            listener.onEvent(NativeEngineControllerEvent.MetadataUpdated(metadata))
        }

        override fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?) {
            automaticEndPlanCalls++
            lastAutomaticEndPlan = plan
            lastLikelyNext = (plan as? NativeEngineAutomaticEndPlan.NextOwner)?.item
        }

        override fun beginManualSelectionIntentAdmission() {
            manualSelectionIntentAdmissionCalls++
        }

        override fun completeManualSelectionIntentWithoutReplacement(
            token: EngineSelectionToken?
        ) {
            manualSelectionIntentCompletionToken = token
        }

        override fun suspendForAudioFocusLoss() {
            focusSuspends++
        }

        override fun resumeAfterAudioFocusGain() {
            focusResumes++
        }

        override fun requestRouteRecovery() {
            routeRecoveries++
        }

        override fun beginStemMixPreview(
            token: EngineSelectionToken,
            ownership: NativeRuntimeOwnership,
            packageSignature: String,
            packageRequestRevision: Long,
            laneIndex: Int
        ): EngineStemMixPreviewLease {
            stemMixPreviewBegins++
            val authority = EngineStemMixPreviewAuthority(
                id = stemMixPreviewBegins.toLong(),
                token = token,
                ownership = ownership,
                packageSignature = packageSignature,
                packageRequestRevision = packageRequestRevision
            )
            return EngineStemMixPreviewLease(
                authority = authority,
                laneIndex = laneIndex,
                laneEpoch = stemMixPreviewBegins.toLong()
            )
        }

        override fun stopForTaskRemoval(): NativeEngineStopRequest = NativeEngineStopRequest(
            NativeEngineStopRequestId(++stopSequence),
            tokenSequence
        ).also { request ->
            if (fatalQuarantined) request.complete(NativeEngineStopStatus.FATAL_QUARANTINE)
        }

        override fun holdActorForSelectionBurstForTest(): Boolean {
            holdCalls++
            return holdAction?.invoke() ?: false
        }

        override fun releaseActorForSelectionBurstForTest() {
            releaseHoldCalls++
        }

        override fun close() {
            closeCalls++
            listener.onEvent(NativeEngineControllerEvent.Closed)
        }

        fun emit(event: NativeEngineControllerEvent) {
            if (event is NativeEngineControllerEvent.FatalRecoveryRequired) {
                fatalQuarantined = true
            }
            listener.onEvent(event)
        }

        fun stageAlreadyCurrent(selection: EngineSelection, metadata: NativeEngineMetadata) {
            selections[selection.token] = selection
            this.metadata[selection.token] = metadata
            desired = automaticSuccessorDesiredState(selection)
            tokenSequence = maxOf(tokenSequence, selection.token.value)
            nextSelectionAdmission = NativeEngineSelectionAdmission(
                selection.token,
                NativeEngineSelectionAdmissionKind.ALREADY_CURRENT
            )
        }

        fun publishOwner(
            token: EngineSelectionToken,
            generation: Long,
            callbackSequence: Long
        ): NativeRuntimeOwnership {
            val selection = requireNotNull(selections[token])
            val ownership = NativeRuntimeOwnership(token.value, generation)
            listener.onEvent(
                NativeEngineControllerEvent.OwnerPublished(
                    NativeEngineOwnerPublication(
                        selection = selection,
                        ownership = ownership,
                        metadata = metadata[token],
                        kind = NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                        evidence = EngineAudibleOwnerEvidence(
                            token,
                            selection.songId,
                            callbackSequence,
                            firstOutputFrame = 0L,
                            finalPcmFrames = 192,
                            outputFingerprintSequence = callbackSequence
                        )
                    )
                )
            )
            return ownership
        }

        fun publishExternalOwner(
            selection: EngineSelection,
            metadata: NativeEngineMetadata?,
            kind: NativeEngineOwnerPublicationKind,
            generation: Long,
            callbackSequence: Long,
            evidence: EngineAudibleOwnerEvidence? = EngineAudibleOwnerEvidence(
                token = selection.token,
                songId = selection.songId,
                callbackSequence = callbackSequence,
                firstOutputFrame = callbackSequence * 256L,
                finalPcmFrames = 256,
                outputFingerprintSequence = callbackSequence
            )
        ): NativeRuntimeOwnership {
            val ownership = NativeRuntimeOwnership(selection.token.value, generation)
            listener.onEvent(
                NativeEngineControllerEvent.OwnerPublished(
                    NativeEngineOwnerPublication(
                        selection = selection,
                        ownership = ownership,
                        metadata = metadata,
                        kind = kind,
                        evidence = evidence
                    )
                )
            )
            return ownership
        }

        fun completeStop(request: NativeEngineStopRequest) {
            request.complete(NativeEngineStopStatus.COMPLETED)
            listener.onEvent(NativeEngineControllerEvent.StopCompleted(request.id))
        }
    }

    protected fun request(songId: String, autoplay: Boolean = true) =
        NativeEngineSelectionRequest(
            songId = songId,
            audioRevision = "audio-$songId",
            originalLocation = "/songs/$songId.flac",
            durationMs = 60_000L,
            autoplay = autoplay
        )

    protected fun installStemPackage(
        harness: Harness,
        token: EngineSelectionToken,
        lanes: List<EngineStemLane>,
        stemRevision: String
    ): String {
        val lease = requireNotNull(harness.adapter.acquireCommandLease())
        assertEquals(token, lease.token)
        assertTrue(
            harness.adapter.configureStemPackageIfCurrent(
                lease = lease,
                stemRevision = stemRevision,
                lanes = lanes,
                selectStems = true
            )
        )
        val request = requireNotNull(
            harness.adapter.snapshot().publication.committed?.stems?.desired
        )
        harness.engine.emit(
            NativeEngineControllerEvent.StemPackageInstalled(
                token = token,
                packageSignature = request.packageSignature,
                stemRevision = request.stemRevision,
                requestRevision = request.requestRevision,
                callbackSequence = request.requestRevision + 100L
            )
        )
        assertEquals(
            PlaybackStemPackagePhase.INSTALLED,
            harness.adapter.snapshot().publication.committed?.stems?.phase
        )
        return request.packageSignature
    }

    protected fun selection(
        token: EngineSelectionToken,
        request: NativeEngineSelectionRequest
    ) = EngineSelection(
        token = token,
        songId = request.songId,
        audioRevision = request.audioRevision,
        originalLocation = request.originalLocation,
        durationMs = request.durationMs,
        startPositionMs = nativeRenderablePositionMs(request.startPositionMs.toDouble(), request.durationMs,
            request.playbackStartMs, request.playbackEndMs),
        autoplay = request.autoplay,
        originalContentSha256 = request.originalContentSha256,
        playbackStartMs = request.playbackStartMs,
        playbackEndMs = request.playbackEndMs
    )

    protected fun metadata(songId: String, revision: Long) = PlaybackPublicationMetadata(
        songId = songId,
        audioRevision = "audio-$songId",
        revision = revision,
        payloadKey = "ui:$songId:$revision"
    )

    protected fun PlaybackPublicationMetadata.toNative() = NativeEngineMetadata(
        songId,
        audioRevision,
        revision,
        payloadKey
    )

    protected fun stems(songId: String): List<EngineStemLane> =
        listOf("vocals", "drums", "bass", "guitar", "piano", "remaining").reversed().mapIndexed { index, role ->
            EngineStemLane(
                id = "$songId-$role",
                normalizedRole = role.uppercase(),
                location = "/stems/$songId/$role.flac",
                contentSha256 = "sha-$songId-$role"
            )
        }

    protected fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    companion object {
        const val TEST_THREAD_WATCHDOG_MS = 1_000L
        const val TEST_THREAD_CLEANUP_MS = 1_000L
    }
}
