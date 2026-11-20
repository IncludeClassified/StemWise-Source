package app.stemwise.playback.engine

import android.content.Context
import app.stemwise.playback.LoopPreparationState
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO
import app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serialized production boundary from the app/session model to the native controller.
 *
 * A selection is retained as a request immediately, but it cannot enter [publication.committed]
 * until an exact controller publication supplies both native ownership and callback/paused-install
 * proof. All controls are latest-value snapshots for the latest token; stale tokens and events are
 * rejected before they can alter native desired state or public identity.
 */
class NativeAudioPlaybackSessionAdapter internal constructor(
    engineFactory: (NativeEngineControllerListener) -> NativeAudioPlaybackSessionEngine,
    private val reducer: PlaybackPublicationReducer = PlaybackPublicationReducer(),
    private val effectListener: NativeAudioPlaybackSessionEffectListener =
        NativeAudioPlaybackSessionEffectListener.None
) : AutoCloseable {
    private data class SelectionIntent(
        var desired: EngineDesiredState,
        var metadata: PlaybackPublicationMetadata,
        val requestTag: Long = 0L,
        var exactOwnership: NativeRuntimeOwnership? = null,
        var transportIntentRevision: Long = 1L,
        var sourceIntentRevision: Long = 1L,
        var stemRequest: PlaybackStemPackageRequest? = null,
        var explicitLoopRevision: Long = 0L,
        var explicitLoop: EngineLoopConfig? = null,
        var cachedControls: NativeAudioPlaybackControlSnapshot? = null,
        var cachedTiming: EngineMetronomeConfig? = null
    ) {
        fun controls(onBuild: () -> Unit): NativeAudioPlaybackControlSnapshot {
            cachedControls?.takeIf { it.revision == desired.revision }?.let { return it }
            val timing = cachedControls?.metronome.takeIf {
                cachedTiming?.sameTimingAs(desired.metronome) == true
            }
            return desired.toControlSnapshot(explicitLoop, timing).also {
                cachedControls = it
                cachedTiming = desired.metronome
                onBuild()
            }
        }
    }

    private data class StopIntent(
        val requestId: NativeEngineStopRequestId,
        val selectionRequestSerial: Long
    )

    private data class ExactCommittedOwner(
        val intent: SelectionIntent,
        val committed: PlaybackCommittedPublication,
        val receipt: NativeAudioPlaybackOwnerReceipt
    )

    private var controlSnapshotBuildCount = 0L
    private val gate = Any()
    private val closeStarted = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(NativeAudioPlaybackSessionState())
    val state: StateFlow<NativeAudioPlaybackSessionState> = mutableState.asStateFlow()

    private val engine = engineFactory(NativeEngineControllerListener(::onControllerEvent))
    private val deferredTokenEvents = ArrayDeque<NativeEngineControllerEvent>()
    /**
     * Identities the adapter actually authorized as automatic successors for the current request.
     * Keep the history until a selection boundary: native may make an older exact hint audible
     * while a newer queue hint is waiting for this adapter gate.
     */
    private val automaticSuccessorAuthorizations = linkedSetOf<String>()
    private var intent: SelectionIntent? = null
    private var committedIntent: SelectionIntent? = null
    private var likelyNextIdentity: EngineAudioIdentity? = null
    private var automaticEndPlan: NativeEngineAutomaticEndPlan? = null
    private var lifecycle = NativeAudioPlaybackSessionLifecycle.ACTIVE
    private var terminalFailure: String? = null
    private var selectionRequestSerial = 0L
    private var pendingStop: StopIntent? = null
    private var closedEffectDelivered = false
    private var structuralRevision = 0L
    private var loopAvailable = false
    private var loopPreparation = LoopPreparationState()
    private var loopAvailabilityOwnerRefreshRequired = false
    private var pendingLoopAvailability:
        NativeEngineControllerEvent.LoopAvailabilityChanged? = null

    fun snapshot(): NativeAudioPlaybackSessionState = state.value

    /** Captures the exact latest logical owner; it may still be waiting for native commitment. */
    fun acquireCommandLease(expectedSongId: String? = null): NativeAudioPlaybackCommandLease? =
        synchronized(gate) {
            if (closeStarted.get() || lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                return@synchronized null
            }
            val selection = intent?.desired?.selection ?: return@synchronized null
            if (expectedSongId != null && selection.songId != expectedSongId) {
                return@synchronized null
            }
            selection.commandLease()
        }

    /** Read-only check used before facade work that cannot itself cross the adapter gate. */
    fun commandLeaseIsCurrent(lease: NativeAudioPlaybackCommandLease): Boolean =
        synchronized(gate) {
            lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE &&
                intent?.matches(lease) == true
        }

    /** Host-test evidence that position publication reuses the immutable control graph. */
    internal fun controlSnapshotBuildCountForTest(): Long = synchronized(gate) {
        controlSnapshotBuildCount
    }

    internal fun automaticSuccessorAuthorizationsForTest(): Set<String> = synchronized(gate) {
        automaticSuccessorAuthorizations.toSet()
    }

    /** Exact private preview revision for one still-current committed owner. */
    internal fun acceptedPreviewSeekRevisionForTest(token: EngineSelectionToken): Long? =
        synchronized(gate) {
            exactCommittedOwnerLocked(token)?.intent?.desired?.seekRevision
        }

    /** Fail-silent selection boundary used by the app facade after terminal native quarantine. */
    fun trySelect(
        request: NativeEngineSelectionRequest,
        metadata: PlaybackPublicationMetadata,
        requestTag: Long = 0L
    ): EngineSelectionToken? {
        require(metadata.songId == request.songId)
        require(metadata.audioRevision == request.audioRevision)
        require(requestTag >= 0L)
        val nativeMetadata = metadata.toNativeMetadata()
        return synchronized(gate) {
            check(lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED)
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                return@synchronized null
            }
            val normalizedRequest = request.copy(
                startPositionMs = nativeRenderablePositionMs(request.startPositionMs.toDouble(),
                    request.durationMs, request.playbackStartMs, request.playbackEndMs),
                initialControls = request.initialControls.detachedCopy()
            )
            val admission = engine.selectAdmission(normalizedRequest, nativeMetadata)
            val token = admission.token
            check(selectionRequestSerial < Long.MAX_VALUE) {
                "Session selection request serial space exhausted"
            }
            selectionRequestSerial += 1L
            val selection = EngineSelection(
                token = token,
                songId = request.songId,
                audioRevision = request.audioRevision,
                originalLocation = request.originalLocation,
                durationMs = request.durationMs,
                startPositionMs = normalizedRequest.startPositionMs,
                autoplay = request.autoplay,
                originalContentSha256 = request.originalContentSha256,
                playbackStartMs = request.playbackStartMs,
                playbackEndMs = request.playbackEndMs
            )
            val previousIntent = intent
            val alreadyCurrent =
                admission.kind == NativeEngineSelectionAdmissionKind.ALREADY_CURRENT
            val exactCommitted = reducer.snapshot().committed?.takeIf { committed ->
                alreadyCurrent && committed.selection == selection &&
                    committed.owner.ownership.selectionToken == token.value
            }
            val adoptedIntent = previousIntent
                ?.takeIf { alreadyCurrent && it.desired.selection == selection }
                ?.let { current ->
                    current.copy(
                        metadata = if (metadata.revision > current.metadata.revision) {
                            metadata
                        } else {
                            current.metadata
                        },
                        requestTag = requestTag,
                        exactOwnership = exactCommitted?.owner?.ownership
                            ?: current.exactOwnership
                    )
                }
                ?: run {
                    val desired = if (alreadyCurrent) {
                        automaticSuccessorDesiredState(selection)
                    } else {
                        initialEngineDesiredState(selection, normalizedRequest.initialControls)
                    }
                    SelectionIntent(
                        desired = desired,
                        metadata = metadata,
                        requestTag = requestTag,
                        exactOwnership = exactCommitted?.owner?.ownership,
                        stemRequest = desired.stemLanes.takeIf { it.isNotEmpty() }?.let {
                            stemPackageRequest(desired)
                        }
                    )
                }
            // An explicit selection is a silence/invalidation boundary, unlike an automatic
            // successor. Do this before publishing the latest request so no observer can combine
            // B's loading intent with A's now-silenced committed publication.
            if (!alreadyCurrent) {
                reducer.beginManualSelection()
                loopAvailable = false
                loopAvailabilityOwnerRefreshRequired = true
                pendingLoopAvailability = null
            }
            committedIntent = when {
                exactCommitted != null -> adoptedIntent
                !alreadyCurrent -> null
                else -> committedIntent
            }
            intent = adoptedIntent
            if (alreadyCurrent) {
                val identity = selection.audioIdentity()
                if (likelyNextIdentity == identity) likelyNextIdentity = null
                if ((automaticEndPlan as? NativeEngineAutomaticEndPlan.NextOwner)
                        ?.item?.identity == identity
                ) {
                    engine.setAutomaticEndPlan(null)
                    automaticEndPlan = null
                }
            }
            automaticSuccessorAuthorizations.clear()
            deferredTokenEvents.clear()
            lifecycle = NativeAudioPlaybackSessionLifecycle.ACTIVE
            publishStateLocked()
            token
        }
    }

    fun processRestartRequired(): Boolean = synchronized(gate) {
        lifecycle == NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED
    }

    fun updateMetadata(metadata: PlaybackPublicationMetadata): Boolean {
        synchronized(gate) {
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return false
            val latest = intent
            if (latest != null && metadata.matches(latest.desired.selection) &&
                metadata.revision > latest.metadata.revision
            ) {
                latest.metadata = metadata
            }
            engine.updateMetadata(metadata.toNativeMetadata())
            publishStateLocked()
            return true
        }
    }

    fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?) {
        synchronized(gate) {
            check(lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED)
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return
            if (plan == automaticEndPlan) return
            val item = when (plan) {
                is NativeEngineAutomaticEndPlan.NextOwner -> plan.item
                is NativeEngineAutomaticEndPlan.RestartCurrent, null -> null
            }
            val nextIdentity = item?.identity
            engine.setAutomaticEndPlan(plan)
            (plan as? NativeEngineAutomaticEndPlan.NextOwner)
                ?.item
                ?.identity
                ?.stableIdentity()
                ?.let(automaticSuccessorAuthorizations::add)
            automaticEndPlan = plan
            likelyNextIdentity = nextIdentity
            publishStateLocked()
        }
    }

    fun refreshAutomaticEndMetadata(resolve: (EngineAudioIdentity) -> NativeEngineMetadata?) {
        synchronized(gate) {
            fun refresh(item: NativeEngineQueueItem): NativeEngineQueueItem =
                resolve(item.identity)?.let { item.copy(metadata = it) } ?: item
            val refreshed = when (val plan = automaticEndPlan) {
                is NativeEngineAutomaticEndPlan.NextOwner -> plan.copy(item = refresh(plan.item))
                is NativeEngineAutomaticEndPlan.RestartCurrent -> plan.copy(fallbackNext = plan.fallbackNext?.let(::refresh))
                null -> null
            }
            setAutomaticEndPlan(refreshed)
        }
    }

    /** Revokes an old queue edge before an explicit song's source lookup can suspend. */
    fun supersedeAutomaticEndForManualSelection() {
        synchronized(gate) {
            check(lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED)
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return
            engine.beginManualSelectionIntentAdmission()
            engine.setAutomaticEndPlan(null)
            automaticEndPlan = null
            likelyNextIdentity = null
            automaticSuccessorAuthorizations.clear()
            publishStateLocked()
        }
    }

    /** Latest source lookup settled without creating a replacement owner. */
    fun completeManualSelectionIntentWithoutReplacement() {
        synchronized(gate) {
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return
            engine.completeManualSelectionIntentWithoutReplacement(
                intent?.desired?.selection?.token
            )
        }
    }

    fun setManualPrewarm(item: NativeEngineQueueItem?) {
        synchronized(gate) {
            check(lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED)
            val owner = intent?.desired?.selection
            engine.setManualPrewarm(
                if (item != null && owner != null) {
                    NativeEngineManualPrewarmRequest(owner.token, item)
                } else {
                    null
                }
            )
        }
    }

    fun play(): Boolean = mutateDesired(audiblePlayWhenReady = true) {
        it.copy(playWhenReady = true)
    }

    fun pause(): Boolean = mutateDesired(audiblePlayWhenReady = false) {
        it.copy(playWhenReady = false)
    }

    /** Applies transport intent only while [token] is the exact committed owner. */
    fun setPlayWhenReadyIfCurrent(
        token: EngineSelectionToken,
        playWhenReady: Boolean
    ): NativeAudioPlaybackOwnerReceipt? = mutateExactCommittedDesired(
        token = token,
        audiblePlayWhenReady = playWhenReady
    ) {
        it.copy(playWhenReady = playWhenReady)
    }

    fun resolveRelativeSeek(deltaMs: Long): NativeAudioPlaybackRelativeSeek? = synchronized(gate) {
        val lease = acquireCommandLease() ?: return@synchronized null
        val desired = requireNotNull(intent).desired
        val committed = reducer.snapshot().committed?.takeIf { it.selection.token == lease.token }
        val position = if (committed == null ||
            desired.seekRevision > committed.transport.completedSeekRevision
        ) desired.requestedPositionMs else committed.transport.presentedPositionMs
        val target = when {
            deltaMs > 0L && position > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
            deltaMs < 0L && position < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
            else -> position + deltaMs
        }.coerceIn(desired.selection.playbackStartMs, desired.selection.playbackEndMs)
        val loop = intent?.explicitLoop?.takeIf { position >= it.startMs && position < it.endMs }
        NativeAudioPlaybackRelativeSeek(lease, loop?.let { target.coerceIn(it.startMs, it.endMs - 1L) } ?: target)
    }

    fun seekToIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        positionMs: Long
    ): NativeAudioPlaybackSeekReceipt? {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val accepted = synchronized(gate) {
            val latest = intent?.takeIf { it.matches(lease) } ?: return@synchronized null
            val selection = latest.desired.selection
            val target = nativeRenderablePositionMs(positionMs.toDouble(), selection.durationMs,
                selection.playbackStartMs, selection.playbackEndMs)
            val accepted = mutateDesiredLocked(
                latest = latest,
                audiblePlayWhenReady = null,
                transform = { previous ->
                    check(previous.seekRevision < Long.MAX_VALUE) {
                        "Seek revision space exhausted"
                    }
                    previous.copy(
                        requestedPositionMs = target,
                        seekRevision = previous.seekRevision + 1L
                    )
                },
                effects = effects
            )
            if (accepted) NativeAudioPlaybackSeekReceipt(
                latest.desired.selection.token, latest.desired.seekRevision, latest.desired.requestedPositionMs
            ) else null
        }
        effects.forEach(effectListener::onEffect)
        return accepted
    }

    /**
     * Moves the exact current native owner while a scrub gesture is still in progress.
     *
     * Preview seeks deliberately remain below the publication boundary: they update the native
     * controller and this adapter's private desired-state baseline, but do not issue a reducer
     * transport request or replace [state]. The gesture's final [finishScrubWithReceipt] therefore
     * remains the single authoritative session/UI publication. Supplying the token makes a delayed
     * frame from an outgoing gesture harmless after a selection or lifecycle boundary.
     */
    fun previewSeekTo(token: EngineSelectionToken, positionMs: Long): Boolean {
        synchronized(gate) {
            val latest = exactCommittedOwnerLocked(token)?.intent ?: return false
            val previous = latest.desired
            check(previous.revision < Long.MAX_VALUE) { "Desired-state revision space exhausted" }
            check(previous.seekRevision < Long.MAX_VALUE) { "Seek revision space exhausted" }

            val selection = previous.selection
            val target = nativeRenderablePositionMs(positionMs.toDouble(), selection.durationMs,
                selection.playbackStartMs, selection.playbackEndMs)
            if (!reclassifyLoopForSeekLocked(latest, target)) return false
            val engineTransform: (EngineDesiredState) -> EngineDesiredState = { live ->
                check(live.seekRevision < Long.MAX_VALUE) { "Seek revision space exhausted" }
                live.copy(
                    requestedPositionMs = target,
                    seekRevision = live.seekRevision + 1L
                )
            }
            val accepted = engine.updateDesiredIfRevision(token, previous.revision, engineTransform)
                ?: return false
            latest.desired = accepted
            pendingLoopAvailability = null
            loopAvailabilityOwnerRefreshRequired = true
            publishLoopAvailabilityOnlyLocked(false)
            return true
        }
    }

    /**
     * Atomically ends a scrub for the still-current exact owner.
     *
     * Position, seek revision, and the restored play intent cross the controller in one combined
     * desired/transport update and cross the reducer in one transport request/state publication.
     */
    fun finishScrubWithReceipt(
        token: EngineSelectionToken,
        positionMs: Long,
        playWhenReady: Boolean
    ): NativeAudioPlaybackOwnerReceipt? = mutateExactCommittedDesired(
        token = token,
        audiblePlayWhenReady = playWhenReady
    ) { previous ->
        check(previous.seekRevision < Long.MAX_VALUE) { "Seek revision space exhausted" }
        previous.copy(
            playWhenReady = playWhenReady,
            requestedPositionMs = nativeRenderablePositionMs(positionMs.toDouble(), previous.selection.durationMs,
                previous.selection.playbackStartMs, previous.selection.playbackEndMs),
            seekRevision = previous.seekRevision + 1L
        )
    }

    fun setSourceModeIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        mode: EngineSourceMode
    ): Boolean = mutateDesired(lease = lease, retryFailedStems = mode == EngineSourceMode.STEMS) { previous ->
        if (mode == EngineSourceMode.STEMS && !StemRoleContract.isPlayable(
            previous.stemLanes.map(EngineStemLane::normalizedRole))
        ) return@mutateDesired null
        previous.copy(sourceMode = mode)
    }

    fun configureStemPackageIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        stemRevision: String,
        lanes: List<EngineStemLane>,
        selectStems: Boolean = false
    ): Boolean {
        require(stemRevision.isNotBlank())
        val canonical = canonicalStemLanes(lanes)
        return mutateDesired(lease = lease, retryFailedStems = selectStems) { previous ->
            val controls = canonical.associate { lane ->
                lane.id to (previous.stemControls[lane.id] ?: EngineStemControl())
            }
            previous.copy(
                sourceMode = if (selectStems) EngineSourceMode.STEMS else previous.sourceMode,
                stemLanes = canonical,
                stemRevision = stemRevision,
                stemControls = controls
            )
        }
    }

    fun setStemMixIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        controls: Map<String, EngineStemControl>
    ): Boolean = mutateDesired(lease = lease) { previous ->
        val expected = previous.stemLanes.mapTo(linkedSetOf(), EngineStemLane::id)
        require(StemRoleContract.isPlayable(previous.stemLanes.map(EngineStemLane::normalizedRole)) &&
            controls.keys == expected) {
            "A mixer snapshot must contain exactly the attached part lane ids"
        }
        previous.copy(stemControls = controls.toMap())
    }

    /** One low-frequency DOWN boundary for the allocation-free full-player preview lane. */
    fun beginFrameBoundedStemMixPreviewIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        laneIndex: Int
    ): EngineStemMixPreviewLease? = synchronized(gate) {
        val exact = exactCommittedOwnerLocked(lease.token) ?: return@synchronized null
        if (!exact.intent.matches(lease)) return@synchronized null
        val installed = exact.committed.stems.installed ?: return@synchronized null
        val desiredPackage = exact.committed.stems.desired ?: return@synchronized null
        val requestedPackage = exact.intent.stemRequest ?: return@synchronized null
        val desired = exact.intent.desired
        if (desired.sourceMode != EngineSourceMode.STEMS ||
            exact.committed.stems.phase != PlaybackStemPackagePhase.INSTALLED ||
            installed.packageSignature != desiredPackage.packageSignature ||
            installed.packageSignature != requestedPackage.packageSignature ||
            !stemMixPackageRequestRevisionsMatch(
                installed.requestRevision,
                desiredPackage.requestRevision,
                requestedPackage.requestRevision
            ) ||
            !StemRoleContract.isPlayable(desired.stemLanes.map(EngineStemLane::normalizedRole)) ||
            laneIndex !in desired.stemLanes.indices
        ) return@synchronized null
        engine.beginStemMixPreview(
            token = lease.token,
            ownership = exact.committed.owner.ownership,
            packageSignature = installed.packageSignature,
            packageRequestRevision = requestedPackage.requestRevision,
            laneIndex = laneIndex
        )
    }

    fun capturePausedPreparedStemMixLease(command: NativeAudioPlaybackCommandLease): PausedPreparedStemMixLease? =
        synchronized(gate) { pausedPreparedStemMixLeaseLocked(command) }

    fun pausedPreparedStemMixLeaseIsCurrent(lease: PausedPreparedStemMixLease): Boolean =
        synchronized(gate) { pausedPreparedStemMixLeaseLocked(lease.command) == lease }

    private fun pausedPreparedStemMixLeaseLocked(command: NativeAudioPlaybackCommandLease): PausedPreparedStemMixLease? {
        val exact = exactCommittedOwnerLocked(command.token) ?: return null
        if (!exact.intent.matches(command)) return null
        val desired = exact.intent.desired
        val committed = exact.committed
        val source = committed.source
        val prepared = committed.stems.desired ?: return null
        val requested = exact.intent.stemRequest ?: return null
        val laneIds = desired.stemLanes.mapTo(linkedSetOf(), EngineStemLane::id)
        if (desired.playWhenReady || committed.transport.playWhenReady ||
            desired.sourceMode != EngineSourceMode.STEMS || source.desiredMode != EngineSourceMode.STEMS ||
            committed.stems.phase != PlaybackStemPackagePhase.READY_AWAITING_AUDIBLE ||
            committed.issue != null || exact.intent.exactOwnership != committed.owner.ownership ||
            source.desiredRevision <= 0L || source.desiredRevision != exact.intent.sourceIntentRevision ||
            source.preparedRevision != source.desiredRevision || source.preparedMode != EngineSourceMode.STEMS ||
            prepared.packageSignature != requested.packageSignature || prepared.stemRevision != requested.stemRevision ||
            prepared.requestRevision != requested.requestRevision || requested.requestRevision <= 0L ||
            !StemRoleContract.isPlayable(desired.stemLanes.map(EngineStemLane::normalizedRole)) ||
            desired.stemControls.keys != laneIds) return null
        return PausedPreparedStemMixLease(command, committed.owner.ownership, source.desiredRevision,
            prepared.packageSignature, prepared.stemRevision, prepared.requestRevision,
            java.util.Collections.unmodifiableSet(laneIds))
    }

    fun commitPausedPreparedStemMix(lease: PausedPreparedStemMixLease, controls: Map<String, EngineStemControl>): Boolean {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val accepted = synchronized(gate) {
            if (pausedPreparedStemMixLeaseLocked(lease.command) != lease || controls.keys != lease.laneIds) false
            else mutateDesiredLocked(checkNotNull(intent), null, { it.copy(stemControls = controls.toMap()) }, effects)
        }
        effects.forEach(effectListener::onEffect)
        return accepted
    }

    /** MOVE/display boundary: deliberately bypasses [gate] and every desired-state/map path. */
    fun offerFrameBoundedStemMixPreviewBatch(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean = engine.offerStemMixPreviewBatch(
        lease,
        changedMask,
        gains
    )

    /** One terminal fence. The following exact map-backed commit remains authoritative. */
    fun endFrameBoundedStemMixPreview(lease: EngineStemMixPreviewLease): Boolean =
        engine.endStemMixPreview(lease)

    fun setTempoAndKeyIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        tempo: Float,
        semitoneShift: Int
    ): Boolean {
        require(
            tempo.isFinite() &&
                tempo in MINIMUM_PLAYBACK_TEMPO..MAXIMUM_PLAYBACK_TEMPO
        )
        require(semitoneShift in -12..12)
        return mutateDesired(lease = lease) {
            it.copy(tempo = tempo, semitoneShift = semitoneShift)
        }
    }

    fun retryLoopPreparation(lease: NativeAudioPlaybackCommandLease,
        preparation: LoopPreparationState): Boolean = synchronized(gate) {
        val exact = exactCommittedOwnerLocked(lease.token) ?: return@synchronized false
        val latest = exact.intent.takeIf { it.matches(lease) } ?: return@synchronized false
        val owner = latest.exactOwnership ?: return@synchronized false
        if (preparation.selectionToken != owner.selectionToken || preparation.generation != owner.generation ||
            preparation.requestRevision != latest.explicitLoopRevision || !preparation.retryable) return@synchronized false
        if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return@synchronized false
        engine.retryLoopPreparation(owner, preparation.requestRevision)
    }

    fun setLoopIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        loop: EngineLoopConfig?,
        publishPresentation: Boolean = true,
        inputEventAtMonotonicMicros: Long = 0L,
        heldBGestureId: Long = 0L,
        capturedBMs: Long = -1L,
        captureReferencePositionMs: Long = -1L
    ): Boolean = synchronized(gate) {
        require(inputEventAtMonotonicMicros >= 0L)
        require(heldBGestureId >= 0L)
        require(capturedBMs >= -1L)
        if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return@synchronized false
        val exact = exactCommittedOwnerLocked(lease.token) ?: return@synchronized false
        val latest = exact.intent.takeIf { it.matches(lease) } ?: return@synchronized false
        updateExplicitLoopLocked(
            latest = latest,
            ownership = exact.receipt.ownership,
            loop = loop,
            publishPresentation = publishPresentation,
            inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
            heldBGestureId = heldBGestureId,
            capturedBMs = capturedBMs,
            captureReferencePositionMs = captureReferencePositionMs
        )
    }

    /** Every seek path parks the retained range outside [A, B), including scrub previews. */
    private fun reclassifyLoopForSeekLocked(latest: SelectionIntent, target: Long): Boolean {
        val retained = latest.explicitLoop ?: return true
        val exact = exactCommittedOwnerLocked(latest.desired.selection.token) ?: return false
        return updateExplicitLoopLocked(
            latest = latest,
            ownership = exact.receipt.ownership,
            loop = retained.copy(
                parkUntilRangeEntry = target < retained.startMs || target >= retained.endMs
            ),
            publishPresentation = false,
            forTransportSeek = true
        )
    }

    /** Caller holds [gate]; A/B has one revision stream for touch edits and transport seeks. */
    private fun updateExplicitLoopLocked(
        latest: SelectionIntent,
        ownership: NativeRuntimeOwnership,
        loop: EngineLoopConfig?,
        publishPresentation: Boolean,
        inputEventAtMonotonicMicros: Long = 0L,
        heldBGestureId: Long = 0L,
        capturedBMs: Long = -1L,
        captureReferencePositionMs: Long = -1L,
        forTransportSeek: Boolean = false
    ): Boolean {
        val selection = latest.desired.selection
        val duration = selection.durationMs
        require(loop == null || loop.startMs >= selection.playbackStartMs && loop.endMs <= selection.playbackEndMs)
        require(captureReferencePositionMs < 0L || captureReferencePositionMs in selection.playbackStartMs..selection.playbackEndMs)
        require(capturedBMs < 0L || capturedBMs in selection.playbackStartMs..selection.playbackEndMs)
        val quantizedLoop = loop?.let {
            val range = app.stemwise.playback.validatedLoopRange(it.startMs, it.endMs, duration)
                ?: return false
            it.copy(startMs = range.startMs, endMs = range.endMs)
        }
        check(latest.explicitLoopRevision < Long.MAX_VALUE) { "Loop revision space exhausted" }
        val state = EngineExplicitLoopState(
            token = latest.desired.selection.token,
            revision = latest.explicitLoopRevision + 1L,
            config = quantizedLoop,
            ownership = ownership,
            inputEventAtMonotonicMicros = inputEventAtMonotonicMicros,
            heldBGestureId = heldBGestureId,
            capturedBMs = if (capturedBMs < 0L) capturedBMs else
                app.stemwise.playback.playbackControlPositionMs(capturedBMs.toDouble(), duration),
            captureReferencePositionMs = captureReferencePositionMs
        )
        val updated = if (forTransportSeek) {
            engine.updateExplicitLoopForTransportSeek(state)
        } else {
            engine.updateExplicitLoop(state)
        }
        if (!updated) return false
        latest.explicitLoopRevision = state.revision
        latest.explicitLoop = quantizedLoop
        // Loop edits do not change the immutable stem/metronome graph. Reuse it instead of
        // copying large beat grids on every pointer sample.
        latest.cachedControls = latest.cachedControls
            ?.takeIf { it.revision == latest.desired.revision }
            ?.copy(loop = quantizedLoop)
        if (publishPresentation) publishStateLocked()
        return true
    }

    /** Publishes the loop already accepted by native; it never creates another engine revision. */
    fun publishExplicitLoopPresentationIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        loop: EngineLoopConfig?
    ): Boolean = synchronized(gate) {
        if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return@synchronized false
        val exact = exactCommittedOwnerLocked(lease.token) ?: return@synchronized false
        val latest = exact.intent.takeIf { it.matches(lease) } ?: return@synchronized false
        if (latest.explicitLoop?.let { it.startMs to it.endMs } !=
            loop?.let { it.startMs to it.endMs }
        ) return@synchronized false
        publishStateLocked()
        true
    }

    /** Updates one owner's timing/profile while retaining its volume in the same gate. */
    fun setMetronomeProfileIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        config: EngineMetronomeConfig
    ): Boolean {
        val detached = config.detachedCopy()
        return mutateDesired(lease = lease) { previous ->
            previous.copy(
                metronome = detached.copy(volume = previous.metronome.volume)
            )
        }
    }

    fun setMetronomeVolumeIfCurrent(
        lease: NativeAudioPlaybackCommandLease,
        volume: Float
    ): Boolean {
        require(volume.isFinite() && volume in 0f..1f)
        return mutateDesired(lease = lease) { previous ->
            previous.copy(metronome = previous.metronome.copy(volume = volume))
        }
    }

    fun suspendForAudioFocusLoss() {
        synchronized(gate) {
            if (lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                engine.suspendForAudioFocusLoss()
            }
        }
    }

    fun resumeAfterAudioFocusGain() {
        synchronized(gate) {
            if (lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                engine.resumeAfterAudioFocusGain()
            }
        }
    }

    fun requestRouteRecovery() {
        synchronized(gate) {
            if (lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE) {
                engine.requestRouteRecovery()
                pendingLoopAvailability = null
                loopAvailabilityOwnerRefreshRequired = true
                publishLoopAvailabilityOnlyLocked(false)
            }
        }
    }

    internal fun injectFatalActorFaultForTest(diagnostic: String): Boolean = synchronized(gate) {
        lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE &&
            engine.injectFatalActorFaultForTest(diagnostic)
    }

    internal fun holdActorForSelectionBurstForTest(): Boolean {
        val initiallyActive = synchronized(gate) {
            lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE
        }
        if (!initiallyActive) return false

        // The controller hold waits for every actor task already ahead of its barrier. Such a task
        // may be publishing an event through onControllerEvent(), which also needs [gate]. Never
        // retain the adapter monitor across that blocking barrier or the test hook can deadlock the
        // actor it is trying to quiesce.
        if (!engine.holdActorForSelectionBurstForTest()) return false

        val stillActive = synchronized(gate) {
            lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE
        }
        if (!stillActive) engine.releaseActorForSelectionBurstForTest()
        return stillActive
    }

    internal fun releaseActorForSelectionBurstForTest() = synchronized(gate) {
        if (lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED) {
            engine.releaseActorForSelectionBurstForTest()
        }
    }

    internal fun loopBoundaryObservationForTest(): Pair<NativeRuntimePresentedPosition?, NativeAudioDiagnostics?>? =
        if (closeStarted.get()) null else engine.loopBoundaryObservationForTest()

    /** Read-only test observability; never creates or exposes a renderer/runtime owner. */
    fun selectionRetirementTokens(songId: String): Set<EngineSelectionToken>? = engine.selectionRetirementTokens(songId)
    fun isSelectionRetired(token: EngineSelectionToken): Boolean = engine.isSelectionRetired(token)

    internal fun setDiagnosticPayloadFingerprints(enabled: Boolean) {
        if (!closeStarted.get()) engine.setDiagnosticPayloadFingerprints(enabled)
    }

    internal fun setDetailedDiagnosticCapture(enabled: Boolean): Boolean =
        !closeStarted.get() && engine.setDetailedDiagnosticCapture(enabled)

    fun decoderWorkerDiagnostics(): NativeDecoderWorkerPoolDiagnostics = NativeDecoderWorkerPool.shared.diagnostics()

    fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot? =
        if (closeStarted.get()) null else engine.diagnosticsSnapshot()
    suspend fun readSourcePcmWindow(identity: SourceWaveformIdentity, source: SourceWaveformSource,
        first: Long, frames: Int): SourcePcmWindowRead =
        if (closeStarted.get()) SourcePcmWindowRead.Stale else engine.readSourcePcmWindow(identity, source, first, frames)
    fun sourceWaveformDiagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot? =
        if (closeStarted.get()) null else engine.sourceWaveformDiagnosticsSnapshot()

    fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot = synchronized(gate) {
        if (lifecycle == NativeAudioPlaybackSessionLifecycle.CLOSED) {
            NativeAudioPresentationJournalSnapshot.empty(afterSequence)
        } else {
            engine.presentationJournal(afterSequence)
        }
    }

    fun stop(): NativeEngineStopRequest {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val request = synchronized(gate) {
            check(lifecycle != NativeAudioPlaybackSessionLifecycle.CLOSED)
            val request = engine.stopForTaskRemoval()
            reducer.requestStop(request.id)
            pendingStop = StopIntent(request.id, selectionRequestSerial)
            if (request.status() == NativeEngineStopStatus.FATAL_QUARANTINE) {
                reduceLocked(
                    reducer.onControllerEvent(
                        NativeEngineControllerEvent.StopCompleted(request.id)
                    ),
                    effects
                )
                intent = null
                committedIntent = null
                likelyNextIdentity = null
                automaticEndPlan = null
                automaticSuccessorAuthorizations.clear()
                pendingStop = null
                lifecycle = NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED
            } else {
                lifecycle = NativeAudioPlaybackSessionLifecycle.STOPPING
                intent = null
                likelyNextIdentity = null
                automaticEndPlan = null
                automaticSuccessorAuthorizations.clear()
            }
            loopAvailable = false
            pendingLoopAvailability = null
            publishStateLocked()
            request
        }
        effects.forEach(effectListener::onEffect)
        return request
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        try {
            engine.close()
        } finally {
            val effect = synchronized(gate) {
                lifecycle = NativeAudioPlaybackSessionLifecycle.CLOSED
                loopAvailable = false
                pendingLoopAvailability = null
                publishStateLocked()
                if (closedEffectDelivered) null else {
                    closedEffectDelivered = true
                    NativeAudioPlaybackSessionEffect.Closed
                }
            }
            effect?.let(effectListener::onEffect)
        }
    }

    private fun mutateDesired(
        lease: NativeAudioPlaybackCommandLease? = null,
        audiblePlayWhenReady: Boolean? = null,
        retryFailedStems: Boolean = false,
        transform: (EngineDesiredState) -> EngineDesiredState?
    ): Boolean {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val accepted = synchronized(gate) {
            // STOPPING is an administrative ownership boundary, not a merely paused session.
            // Media3/AudioManager may deliver a final Pause or focus callback while Recents
            // teardown runs. Accepting it would queue work after STOP and can restart an empty
            // native runtime with no adapter owner.
            if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return@synchronized false
            val latest = intent ?: return@synchronized false
            if (lease != null && !latest.matches(lease)) return@synchronized false
            mutateDesiredLocked(latest, audiblePlayWhenReady, transform, effects, retryFailedStems)
        }
        effects.forEach(effectListener::onEffect)
        return accepted
    }

    private fun mutateExactCommittedDesired(
        token: EngineSelectionToken,
        audiblePlayWhenReady: Boolean? = null,
        transform: (EngineDesiredState) -> EngineDesiredState?
    ): NativeAudioPlaybackOwnerReceipt? {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        val receipt = synchronized(gate) {
            val exact = exactCommittedOwnerLocked(token) ?: return@synchronized null
            if (!mutateDesiredLocked(
                    exact.intent,
                    audiblePlayWhenReady,
                    transform,
                    effects
                )
            ) return@synchronized null
            exact.receipt
        }
        effects.forEach(effectListener::onEffect)
        return receipt
    }

    /** Publishes a terminal map commit only for the exact installed package and current owner. */
    fun commitStemMixIfCurrent(
        token: EngineSelectionToken,
        packageSignature: String,
        controls: Map<String, EngineStemControl>
    ): NativeAudioPlaybackStemMixReceipt? {
        if (packageSignature.isBlank()) return null
        val detachedControls = controls.toMap()
        return synchronized(gate) {
            val exact = exactCommittedOwnerLocked(token) ?: return@synchronized null
            val installed = exact.committed.stems.installed ?: return@synchronized null
            val desiredPackage = exact.committed.stems.desired ?: return@synchronized null
            val requestedPackage = exact.intent.stemRequest ?: return@synchronized null
            if (exact.committed.stems.phase != PlaybackStemPackagePhase.INSTALLED ||
                installed.packageSignature != packageSignature ||
                desiredPackage.packageSignature != packageSignature ||
                requestedPackage.packageSignature != packageSignature ||
                !stemMixPackageRequestRevisionsMatch(
                    installed.requestRevision,
                    desiredPackage.requestRevision,
                    requestedPackage.requestRevision
                )
            ) return@synchronized null

            val previous = exact.intent.desired
            val expectedIds = previous.stemLanes.mapTo(linkedSetOf(), EngineStemLane::id)
            if (!StemRoleContract.isPlayable(previous.stemLanes.map(EngineStemLane::normalizedRole)) ||
                detachedControls.keys != expectedIds
            ) return@synchronized null

            if (previous.stemControls != detachedControls) {
                check(previous.revision < Long.MAX_VALUE) {
                    "Desired-state revision space exhausted"
                }
                val engineTransform: (EngineDesiredState) -> EngineDesiredState = { live ->
                    live.copy(stemControls = detachedControls)
                }
                exact.intent.desired = engine.updateDesiredIfRevision(
                    token, previous.revision, engineTransform
                ) ?: return@synchronized null
            }

            val publicRevision = mutableState.value.latestRequest
                ?.takeIf { it.selection.token == token }
                ?.controls
                ?.revision
            if (publicRevision != exact.intent.desired.revision) publishStateLocked()
            NativeAudioPlaybackStemMixReceipt(
                token = token,
                ownership = exact.committed.owner.ownership,
                packageSignature = packageSignature
            )
        }
    }

    /** Caller must hold [gate]. */
    private fun mutateDesiredLocked(
        latest: SelectionIntent,
        audiblePlayWhenReady: Boolean?,
        transform: (EngineDesiredState) -> EngineDesiredState?,
        effects: MutableList<NativeAudioPlaybackSessionEffect>,
        retryFailedStems: Boolean = false
    ): Boolean {
        val previous = latest.desired
        var preview = transform(previous) ?: return false
        require(preview.selection == previous.selection && preview.revision == previous.revision)
        // An explicit audible-transport command is also a physical re-arm boundary. Loop-policy
        // retirement can finish the current output generation without changing playWhenReady;
        // dropping the equal value here leaves that playing owner permanently silent.
        if (preview.seekRevision != previous.seekRevision &&
            !reclassifyLoopForSeekLocked(latest, preview.requestedPositionMs)
        ) return false

        val stemsBeforeUpdate = reducer.snapshot().let { publication ->
            publication.pendingSelection
                ?.takeIf { it.selection.token == previous.selection.token }
                ?.stems
                ?: publication.committed
                    ?.takeIf { it.selection.token == previous.selection.token }
                    ?.stems
        }
        val retryFailedStemPackage = (previous.sourceMode != preview.sourceMode || retryFailedStems) &&
            preview.sourceMode == EngineSourceMode.STEMS &&
            stemsBeforeUpdate?.phase == PlaybackStemPackagePhase.FAILED &&
            latest.stemRequest?.let { failed ->
                stemsBeforeUpdate.desired?.let { desired ->
                    desired.packageSignature == failed.packageSignature &&
                        desired.stemRevision == failed.stemRevision &&
                        desired.requestRevision == failed.requestRevision
                }
            } == true

        if (retryFailedStemPackage) preview = preview.copy(
            stemRetryRevision = Math.addExact(previous.stemRetryRevision, 1L)
        )
        if (preview == previous && audiblePlayWhenReady == null) return true

        val engineTransform: (EngineDesiredState) -> EngineDesiredState = { preview }
        val next = if (audiblePlayWhenReady == null) {
            engine.updateDesiredIfRevision(
                previous.selection.token, previous.revision, engineTransform
            )
        } else {
            engine.updateDesiredWithAudibleTransportIfRevision(
                previous.selection.token, previous.revision, audiblePlayWhenReady, engineTransform
            )
        } ?: return false

        latest.desired = next
        if (previous.seekRevision != next.seekRevision) {
            loopAvailable = false
            loopAvailabilityOwnerRefreshRequired = true
            pendingLoopAvailability = null
        }
        if (previous.playWhenReady != next.playWhenReady ||
            previous.requestedPositionMs != next.requestedPositionMs ||
            previous.seekRevision != next.seekRevision
        ) {
            latest.transportIntentRevision = next.revision
            reduceLocked(
                reducer.requestTransport(
                    next.selection.token,
                    latest.transportIntentRevision,
                    next.playWhenReady,
                    next.requestedPositionMs
                ),
                effects
            )
        }
        if (previous.sourceMode != next.sourceMode) {
            latest.sourceIntentRevision = next.revision
            reduceLocked(
                reducer.requestSourceMode(
                    next.selection.token,
                    latest.sourceIntentRevision,
                    next.sourceMode
                ),
                effects
            )
        }
        if (previous.stemLanes != next.stemLanes ||
            previous.stemRevision != next.stemRevision || retryFailedStemPackage
        ) {
            latest.stemRequest = stemPackageRequest(next)
            reduceLocked(
                reducer.requestStemPackage(next.selection.token, requireNotNull(latest.stemRequest)),
                effects
            )
        }
        publishStateLocked()
        return true
    }

    /** Caller must hold [gate]. */
    private fun exactCommittedOwnerLocked(token: EngineSelectionToken): ExactCommittedOwner? {
        if (closeStarted.get() || lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE) return null
        val latest = intent?.takeIf { it.desired.selection.token == token } ?: return null
        val committed = reducer.snapshot().committed?.takeIf {
            it.selection.token == token && it.owner.ownership.selectionToken == token.value
        } ?: return null
        return ExactCommittedOwner(
            intent = latest,
            committed = committed,
            receipt = NativeAudioPlaybackOwnerReceipt(token, committed.owner.ownership)
        )
    }

    /** Caller must hold [gate]. */
    private fun SelectionIntent.matches(lease: NativeAudioPlaybackCommandLease): Boolean {
        val selection = desired.selection
        return selection.token == lease.token &&
            selection.songId == lease.songId &&
            selection.audioRevision == lease.audioRevision
    }

    private fun EngineSelection.commandLease(): NativeAudioPlaybackCommandLease =
        NativeAudioPlaybackCommandLease(
            token = token,
            songId = songId,
            audioRevision = audioRevision
        )

    private fun onControllerEvent(event: NativeEngineControllerEvent) {
        val effects = mutableListOf<NativeAudioPlaybackSessionEffect>()
        synchronized(gate) {
            when (event) {
                is NativeEngineControllerEvent.OwnerPublished ->
                    handleOwnerPublishedLocked(event, effects)
                is NativeEngineControllerEvent.SelectionFailed ->
                    handleSelectionFailedLocked(event, effects)
                is NativeEngineControllerEvent.OwnerTerminated -> {
                    val reduction = reducer.onControllerEvent(event)
                    if (reduction.effects.any { it is PlaybackPublicationEffect.OwnerTerminated }) {
                        committedIntent = committedIntent?.takeUnless {
                            it.desired.selection.token.value == event.ownership.selectionToken
                        }
                        intent = intent?.takeUnless {
                            it.desired.selection.token.value == event.ownership.selectionToken
                        }
                        loopAvailable = false
                        pendingLoopAvailability = pendingLoopAvailability?.takeUnless {
                            it.ownership.selectionToken == event.ownership.selectionToken
                        }
                        deferredTokenEvents.removeAll {
                            it.tokenOrNull()?.value == event.ownership.selectionToken
                        }
                    }
                    reduceLocked(reduction, effects)
                }
                is NativeEngineControllerEvent.LoopNotReady ->
                    handleLoopRejectedLocked(event, effects)
                is NativeEngineControllerEvent.LoopAvailabilityChanged ->
                    handleLoopAvailabilityChangedLocked(event)
                is NativeEngineControllerEvent.FatalRecoveryRequired ->
                    handleOutputUnavailableLocked(event, effects)
                is NativeEngineControllerEvent.MetadataUpdated -> {
                    val latest = intent
                    val committed = committedIntent
                    val mapped = runCatching {
                        PlaybackPublicationMetadataMapper.Default.map(event.metadata)
                    }.getOrNull()
                    if (latest != null && mapped != null &&
                        mapped.matches(latest.desired.selection) &&
                        mapped.revision > latest.metadata.revision
                    ) latest.metadata = mapped
                    if (committed != null && mapped != null &&
                        mapped.matches(committed.desired.selection) &&
                        mapped.revision > committed.metadata.revision
                    ) committed.metadata = mapped
                    reduceLocked(reducer.onControllerEvent(event), effects)
                }
                is NativeEngineControllerEvent.StopCompleted -> {
                    reduceLocked(reducer.onControllerEvent(event), effects)
                    val stop = pendingStop
                    if (stop?.requestId == event.requestId) {
                        if (stop.selectionRequestSerial == selectionRequestSerial) {
                            intent = null
                            committedIntent = null
                            likelyNextIdentity = null
                            automaticEndPlan = null
                            automaticSuccessorAuthorizations.clear()
                        }
                        pendingStop = null
                        if (lifecycle != NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED) {
                            lifecycle = NativeAudioPlaybackSessionLifecycle.ACTIVE
                        }
                    }
                }
                NativeEngineControllerEvent.Closed -> {
                    reduceLocked(reducer.onControllerEvent(event), effects)
                    lifecycle = NativeAudioPlaybackSessionLifecycle.CLOSED
                    pendingLoopAvailability = null
                }
                else -> handleTokenOrGlobalEventLocked(event, effects)
            }
            publishStateLocked(structuralChange = event !is NativeEngineControllerEvent.Position)
        }
        effects.forEach(effectListener::onEffect)
    }

    private fun handleOwnerPublishedLocked(
        event: NativeEngineControllerEvent.OwnerPublished,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        val publication = event.publication
        val reducerState = reducer.snapshot()
        val ownerChanged = reducerState.committed?.owner?.ownership != publication.ownership
        val committed = reducerState.committed
        if (committed?.selection == publication.selection) {
            reduceLocked(reducer.onControllerEvent(event), effects)
            val exactOwnership = reducer.snapshot().committed
                ?.takeIf { it.selection == publication.selection }
                ?.owner
                ?.ownership
                ?: return
            committedIntent?.takeIf {
                it.desired.selection == publication.selection
            }?.exactOwnership = exactOwnership
            intent?.takeIf {
                it.desired.selection == publication.selection
            }?.exactOwnership = exactOwnership
            if (ownerChanged && exactOwnership == publication.ownership) {
                loopAvailable = false
                replayPendingLoopAvailabilityLocked(exactOwnership)
            }
            return
        }

        val automaticSuccessorPublication =
            publication.kind == NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED ||
                publication.kind ==
                NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED
        if (automaticSuccessorPublication &&
            intent?.desired?.selection != publication.selection
        ) {
            when (publication.kind) {
                NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED -> {
                    val boundaryEvidence = publication.evidence ?: return
                    if (boundaryEvidence.token != publication.selection.token ||
                        boundaryEvidence.songId != publication.selection.songId
                    ) return
                }
                NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED -> {
                    if (publication.evidence != null || publication.selection.autoplay) return
                }
            }
            // The controller publication is already fenced to the exact reserved token,
            // generation, callback evidence, and metadata captured with that reservation. Do not
            // re-authorize it against the mutable likely-next hint: queue policy may update that
            // hint while this callback event is waiting to enter the adapter gate, after native
            // has already made the old exact successor audible.
            if (publication.selection.audioIdentity().stableIdentity() !in
                automaticSuccessorAuthorizations
            ) return
            val publishedMetadata = publication.metadata?.let {
                runCatching { PlaybackPublicationMetadataMapper.Default.map(it) }.getOrNull()
            }?.takeIf { it.matches(publication.selection) } ?: return
            check(selectionRequestSerial < Long.MAX_VALUE) {
                "Session selection request serial space exhausted"
            }
            selectionRequestSerial += 1L
            val successorDesired = automaticSuccessorDesiredState(publication.selection)
            intent = SelectionIntent(
                desired = successorDesired,
                metadata = publishedMetadata,
                exactOwnership = publication.ownership,
                stemRequest = successorDesired.stemLanes.takeIf { it.isNotEmpty() }?.let {
                    stemPackageRequest(successorDesired)
                }
            )
            if (likelyNextIdentity == publication.selection.audioIdentity()) {
                likelyNextIdentity = null
            }
            if ((automaticEndPlan as? NativeEngineAutomaticEndPlan.NextOwner)
                    ?.item?.identity == publication.selection.audioIdentity()
            ) {
                // The controller consumed this exact queue edge physically. Clearing only this
                // adapter mirror strands the old successor in the controller because a later null
                // policy publication is deduplicated against the already-cleared local value.
                engine.setAutomaticEndPlan(null)
                automaticEndPlan = null
            }
            automaticSuccessorAuthorizations.clear()
            deferredTokenEvents.clear()
        }

        val latest = intent ?: return
        if (publication.selection != latest.desired.selection ||
            publication.ownership.selectionToken != latest.desired.selection.token.value
        ) return
        latest.exactOwnership = publication.ownership

        val alreadyKnown = reducerState.pendingSelection?.selection?.token ==
            publication.selection.token ||
            reducerState.committed?.selection?.token == publication.selection.token
        if (!alreadyKnown) {
            val requested = reducer.requestSelection(
                PlaybackPublicationSelectionRequest(
                    publication.selection,
                    publication.ownership,
                    latest.metadata
                )
            )
            reduceLocked(requested, effects)
            if (requested.disposition != PlaybackPublicationDisposition.APPLIED) return
            replayDesiredIntoReducerLocked(latest, effects)
        }

        reduceLocked(reducer.onControllerEvent(event), effects)
        val committedPublication = reducer.snapshot().committed
        if (committedPublication?.selection == publication.selection &&
            committedPublication.owner.ownership == publication.ownership
        ) {
            committedIntent = latest
            if (ownerChanged) {
                loopAvailable = false
                replayPendingLoopAvailabilityLocked(publication.ownership)
            }
        }
        while (deferredTokenEvents.isNotEmpty()) {
            reduceLocked(reducer.onControllerEvent(deferredTokenEvents.removeFirst()), effects)
        }
    }

    private fun handleSelectionFailedLocked(
        event: NativeEngineControllerEvent.SelectionFailed,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        val failedRequestTag = intent?.takeIf {
            it.desired.selection.token == event.token
        }?.requestTag ?: 0L
        reduceLocked(reducer.onControllerEvent(event), effects)
        if (intent?.desired?.selection?.token != event.token) return
        val committed = reducer.snapshot().committed
        val fallback = committedIntent?.takeIf {
            committed != null && it.desired.selection == committed.selection
        }
        fallback?.exactOwnership = committed?.owner?.ownership
        intent = fallback
        pendingLoopAvailability = null
        deferredTokenEvents.clear()
        if (effects.none {
                it is NativeAudioPlaybackSessionEffect.SelectionFailed && it.token == event.token
            }
        ) {
            effects += NativeAudioPlaybackSessionEffect.SelectionFailed(
                event.token,
                event.cause,
                failedRequestTag
            )
        }
    }

    private fun handleLoopRejectedLocked(
        event: NativeEngineControllerEvent.LoopNotReady,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        val target = intent?.takeIf { it.desired.selection.token == event.token }
            ?: committedIntent?.takeIf { it.desired.selection.token == event.token }
        if (target?.explicitLoopRevision == event.loopRevision &&
            target.explicitLoop == event.loop
        ) {
            target.explicitLoop = null
            target.cachedControls = null
        }
        reduceLocked(reducer.onControllerEvent(event), effects)
    }

    private fun handleLoopAvailabilityChangedLocked(
        event: NativeEngineControllerEvent.LoopAvailabilityChanged
    ) {
        val latest = intent ?: return
        if (lifecycle != NativeAudioPlaybackSessionLifecycle.ACTIVE ||
            latest.desired.selection.token != event.token ||
            event.ownership.selectionToken != event.token.value
        ) return
        val committed = reducer.snapshot().committed
        if (loopAvailabilityOwnerRefreshRequired) {
            val committedGeneration = maxOf(
                latest.exactOwnership?.generation ?: 0L,
                committed?.takeIf { it.selection.token == event.token }
                    ?.owner?.ownership?.generation ?: 0L
            )
            val pending = pendingLoopAvailability
            if (event.ownership.generation > committedGeneration &&
                (pending == null ||
                    event.ownership.generation >= pending.ownership.generation)
            ) {
                pendingLoopAvailability = event
            }
            return
        }
        if (committed != null &&
            latest.exactOwnership == event.ownership &&
            committed.selection.token == event.token &&
            committed.owner.ownership == event.ownership
        ) {
            loopAvailable = event.available
            loopPreparation = event.preparation
        }
    }

    private fun replayPendingLoopAvailabilityLocked(ownership: NativeRuntimeOwnership) {
        val pending = pendingLoopAvailability
        if (pending == null) {
            loopAvailabilityOwnerRefreshRequired = false
            return
        }
        if (pending.token.value != ownership.selectionToken) {
            pendingLoopAvailability = null
            loopAvailabilityOwnerRefreshRequired = false
            return
        }
        when {
            pending.ownership == ownership -> {
                pendingLoopAvailability = null
                loopAvailabilityOwnerRefreshRequired = false
                handleLoopAvailabilityChangedLocked(pending)
            }
            pending.ownership.generation < ownership.generation -> {
                pendingLoopAvailability = null
                loopAvailabilityOwnerRefreshRequired = false
            }
            else -> loopAvailabilityOwnerRefreshRequired = true
        }
    }

    private fun handleOutputUnavailableLocked(
        event: NativeEngineControllerEvent.FatalRecoveryRequired,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        lifecycle = NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED
        terminalFailure = event.diagnostic
        loopAvailable = false
        pendingLoopAvailability = null
        fun pause(target: SelectionIntent?) {
            target ?: return
            if (target.desired.playWhenReady) {
                check(target.desired.revision < Long.MAX_VALUE) {
                    "Cannot settle a fatal output state after control revision exhaustion"
                }
                target.desired = target.desired.copy(
                    revision = target.desired.revision + 1L,
                    playWhenReady = false
                )
                if (target.transportIntentRevision < Long.MAX_VALUE) {
                    target.transportIntentRevision += 1L
                }
            }
        }
        val latest = intent
        pause(latest)
        if (committedIntent !== latest) pause(committedIntent)
        reduceLocked(reducer.onControllerEvent(event), effects)
    }

    private fun replayDesiredIntoReducerLocked(
        latest: SelectionIntent,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        val desired = latest.desired
        reduceLocked(
            reducer.requestTransport(
                desired.selection.token,
                latest.transportIntentRevision,
                desired.playWhenReady,
                desired.requestedPositionMs
            ),
            effects
        )
        reduceLocked(
            reducer.requestSourceMode(
                desired.selection.token,
                latest.sourceIntentRevision,
                desired.sourceMode
            ),
            effects
        )
        latest.stemRequest?.let {
            reduceLocked(reducer.requestStemPackage(desired.selection.token, it), effects)
        }
    }

    private fun handleTokenOrGlobalEventLocked(
        event: NativeEngineControllerEvent,
        effects: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        if (event is NativeEngineControllerEvent.OutputAvailabilityChanged) {
            // A failed opener cannot publish an owner; report its exact current request immediately.
            if (intent?.desired?.selection?.token == event.token) {
                reduceLocked(reducer.onControllerEvent(event), effects)
            }
            return
        }
        val token = event.tokenOrNull()
        if (token == null) {
            reduceLocked(reducer.onControllerEvent(event), effects)
            return
        }
        val state = reducer.snapshot()
        val known = state.pendingSelection?.selection?.token == token ||
            state.committed?.selection?.token == token
        if (known) {
            reduceLocked(reducer.onControllerEvent(event), effects)
        } else if (intent?.desired?.selection?.token == token) {
            if (event is NativeEngineControllerEvent.Position) {
                deferredTokenEvents.removeAll { it is NativeEngineControllerEvent.Position }
            }
            if (deferredTokenEvents.size == MAXIMUM_DEFERRED_TOKEN_EVENTS) {
                deferredTokenEvents.removeFirst()
            }
            deferredTokenEvents.addLast(event)
        }
    }

    private fun reduceLocked(
        reduction: PlaybackPublicationReduction,
        destination: MutableList<NativeAudioPlaybackSessionEffect>
    ) {
        reduction.effects.forEach { effect ->
            when (effect) {
                is PlaybackPublicationEffect.OutputAvailabilityChanged -> destination +=
                    NativeAudioPlaybackSessionEffect.OutputAvailabilityChanged(effect.token, effect.available, effect.diagnostic)
                is PlaybackPublicationEffect.AudibleEnd -> destination +=
                    NativeAudioPlaybackSessionEffect.AudibleEnd(
                        effect.token,
                        effect.ownership,
                        effect.sequence,

                        effect.sourceChangePending
                    )
                is PlaybackPublicationEffect.StopCompletion -> destination +=
                    NativeAudioPlaybackSessionEffect.StopCompleted(
                        effect.requestId,
                        effect.clearedPublication
                    )
                is PlaybackPublicationEffect.Diagnostic -> destination +=
                    NativeAudioPlaybackSessionEffect.Diagnostic(
                        effect.operation,
                        effect.diagnostic
                    )
                is PlaybackPublicationEffect.SelectionFailed -> destination +=
                    NativeAudioPlaybackSessionEffect.SelectionFailed(
                        effect.token,
                        effect.cause,
                        intent?.takeIf { it.desired.selection.token == effect.token }
                            ?.requestTag ?: 0L
                    )
                is PlaybackPublicationEffect.OwnerTerminated -> destination +=
                    NativeAudioPlaybackSessionEffect.OwnerTerminated(effect.ownership, effect.cause)
                is PlaybackPublicationEffect.LoopRejected -> destination +=
                    NativeAudioPlaybackSessionEffect.LoopRejected(
                        effect.token,
                        effect.cause
                    )
                is PlaybackPublicationEffect.OutputUnavailable -> destination +=
                    NativeAudioPlaybackSessionEffect.OutputUnavailable(effect.diagnostic)
                PlaybackPublicationEffect.Closed -> if (!closedEffectDelivered) {
                    closedEffectDelivered = true
                    destination += NativeAudioPlaybackSessionEffect.Closed
                }
            }
        }
    }

    private fun publishStateLocked(structuralChange: Boolean = true) {
        if (structuralChange) {
            check(structuralRevision < Long.MAX_VALUE) {
                "Session structural revision space exhausted"
            }
            structuralRevision += 1L
        }
        val latest = intent
        val publication = reducer.snapshot()
        val committed = publication.committed?.let { owner ->
            checkNotNull(committedIntent?.takeIf { it.desired.selection == owner.selection }) {
                "Committed controls must belong to the published selection"
            }
        }
        fun SelectionIntent.controlSnapshot(): NativeAudioPlaybackControlSnapshot = controls {
            controlSnapshotBuildCount += 1L
        }
        mutableState.value = NativeAudioPlaybackSessionState(
            publication = publication,
            latestRequest = latest?.let {
                NativeAudioPlaybackRequestedOwner(
                    selection = it.desired.selection,
                    metadata = it.metadata,
                    exactOwnership = it.exactOwnership,
                    controls = it.controlSnapshot()
                )
            },
            committedControls = committed?.controlSnapshot(),
            loopAvailable = loopAvailable,
            loopPreparation = loopPreparation.takeIf { loopAvailabilityOwnerRefreshRequired.not() &&
                it.selectionToken == latest?.exactOwnership?.selectionToken && it.generation == latest?.exactOwnership?.generation } ?: LoopPreparationState(),
            likelyNextIdentity = likelyNextIdentity,
            automaticEndPlan = automaticEndPlan,
            lifecycle = lifecycle,
            terminalFailure = terminalFailure,
            structuralRevision = structuralRevision
        )
    }

    /** Publishes only the fail-closed cache gate; scrub previews remain otherwise private. */
    private fun publishLoopAvailabilityOnlyLocked(available: Boolean) {
        if (loopAvailable == available) return
        loopAvailable = available
        check(structuralRevision < Long.MAX_VALUE) {
            "Session structural revision space exhausted"
        }
        structuralRevision += 1L
        mutableState.value = mutableState.value.copy(
            loopAvailable = available,
            structuralRevision = structuralRevision
        )
    }

    companion object {
        private const val MAXIMUM_DEFERRED_TOKEN_EVENTS = 32

        fun create(
            context: Context,
            effectListener: NativeAudioPlaybackSessionEffectListener =
                NativeAudioPlaybackSessionEffectListener.None,
            outputCapacityFrames: Int = NativeAudioBufferFormat.DEFAULT_CAPACITY_FRAMES,
            runtimeConfiguration: NativeRuntimeConfiguration = NativeRuntimeConfiguration(),
            packetReaderFactory: AudioPacketReaderFactory
        ): NativeAudioPlaybackSessionAdapter = NativeAudioPlaybackSessionAdapter(
            engineFactory = { listener ->
                ControllerNativeAudioPlaybackSessionEngine(
                    context,
                    listener,
                    outputCapacityFrames,
                    runtimeConfiguration,
                    packetReaderFactory = packetReaderFactory
                )
            },
            effectListener = effectListener
        )
    }
}

internal class ControllerNativeAudioPlaybackSessionEngine(
    private val controller: NativePlaybackEngineController
) : NativeAudioPlaybackSessionEngine {
    constructor(
        context: Context,
        listener: NativeEngineControllerListener,
        outputCapacityFrames: Int,
        runtimeConfiguration: NativeRuntimeConfiguration,
        packetReaderFactory: AudioPacketReaderFactory
    ) : this(NativePlaybackEngineController(
        environment = JniNativeEngineEnvironment(
            context,
            outputCapacityFrames,
            runtimeConfiguration,
            packetReaderFactory = packetReaderFactory
        ),
        listener = listener
    ))

    override fun beginManualSelectionIntentAdmission() =
        controller.beginManualSelectionIntentAdmission()

    override fun completeManualSelectionIntentWithoutReplacement(
        token: EngineSelectionToken?
    ) = controller.completeManualSelectionIntentWithoutReplacement(token)

    override fun select(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata
    ) = controller.select(request, metadata)

    override fun selectAdmission(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata
    ) = controller.selectAdmission(request, metadata)

    override fun updateDesired(
        token: EngineSelectionToken,
        transform: (EngineDesiredState) -> EngineDesiredState
    ) = controller.updateDesired(token, transform)

    override fun updateDesiredIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        transform: (EngineDesiredState) -> EngineDesiredState
    ) = controller.updateDesiredIfRevision(token, expectedRevision, transform)

    override fun updateDesiredWithAudibleTransportIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        playWhenReady: Boolean,
        transform: (EngineDesiredState) -> EngineDesiredState
    ) = controller.updateDesiredWithAudibleTransportIfRevision(
        token,
        expectedRevision,
        playWhenReady,
        transform
    )

    override fun retryLoopPreparation(ownership: NativeRuntimeOwnership, requestRevision: Long?): Boolean =
        controller.retryLoopPreparation(ownership, requestRevision)

    override fun updateExplicitLoop(state: EngineExplicitLoopState): Boolean =
        controller.updateExplicitLoop(state)

    override fun updateExplicitLoopForTransportSeek(
        state: EngineExplicitLoopState
    ): Boolean = controller.updateExplicitLoopForTransportSeek(state)

    override fun beginStemMixPreview(
        token: EngineSelectionToken,
        ownership: NativeRuntimeOwnership,
        packageSignature: String,
        packageRequestRevision: Long,
        laneIndex: Int
    ): EngineStemMixPreviewLease? = controller.beginStemMixPreview(
        token,
        ownership,
        packageSignature,
        packageRequestRevision,
        laneIndex
    )

    override fun offerStemMixPreviewBatch(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean = controller.offerStemMixPreviewBatch(
        lease,
        changedMask,
        gains
    )

    override fun endStemMixPreview(lease: EngineStemMixPreviewLease): Boolean =
        controller.endStemMixPreview(lease)

    override fun updateMetadata(metadata: NativeEngineMetadata) =
        controller.updateMetadata(metadata)

    override fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?) =
        controller.setAutomaticEndPlan(plan)
    override fun setManualPrewarm(request: NativeEngineManualPrewarmRequest?) =
        controller.setManualPrewarm(request)
    override fun suspendForAudioFocusLoss() = controller.suspendForAudioFocusLoss()
    override fun resumeAfterAudioFocusGain() = controller.resumeAfterAudioFocusGain()
    override fun requestRouteRecovery() = controller.requestRouteRecovery()
    override fun stopForTaskRemoval() = controller.stopForTaskRemoval()
    override fun selectionRetirementTokens(songId: String): Set<EngineSelectionToken>? =
        controller.selectionRetirementTokens(songId)
    override fun isSelectionRetired(token: EngineSelectionToken): Boolean = controller.isSelectionRetired(token)
    override fun injectFatalActorFaultForTest(diagnostic: String): Boolean =
        controller.injectFatalActorFaultForTest(diagnostic)
    override fun holdActorForSelectionBurstForTest(): Boolean =
        controller.holdActorForSelectionBurstForTest()
    override fun releaseActorForSelectionBurstForTest() =
        controller.releaseActorForSelectionBurstForTest()
    override fun loopBoundaryObservationForTest(): Pair<NativeRuntimePresentedPosition?, NativeAudioDiagnostics?> =
        controller.loopBoundaryObservationForTest()
    override fun setDiagnosticPayloadFingerprints(enabled: Boolean) = controller.setDiagnosticPayloadFingerprints(enabled)
    override fun setDetailedDiagnosticCapture(enabled: Boolean): Boolean = controller.setDetailedDiagnosticCapture(enabled)

    override fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot =
        controller.diagnosticsSnapshot()
    override suspend fun readSourcePcmWindow(identity: SourceWaveformIdentity, source: SourceWaveformSource,
        first: Long, frames: Int): SourcePcmWindowRead = controller.readSourcePcmWindow(identity, source, first, frames)
    override fun sourceWaveformDiagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot =
        controller.sourceWaveformDiagnosticsSnapshot()
    override fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot = controller.presentationJournal(afterSequence)
    override fun close() = controller.close()
}

private fun PlaybackPublicationMetadata.toNativeMetadata() = NativeEngineMetadata(
    songId = songId,
    audioRevision = audioRevision,
    revision = revision,
    payloadKey = payloadKey
)

private fun PlaybackPublicationMetadata.matches(selection: EngineSelection): Boolean =
    songId == selection.songId && audioRevision == selection.audioRevision

private fun EngineDesiredState.toControlSnapshot(
    loop: EngineLoopConfig?, retainedTiming: NativeAudioPlaybackMetronomeSnapshot?
) =
    NativeAudioPlaybackControlSnapshot(
    revision = revision,
    playWhenReady = playWhenReady,
    requestedPositionMs = requestedPositionMs,
    seekRevision = seekRevision,
    sourceMode = sourceMode,
    stemLanes = stemLanes.toList(),
    stemControls = stemControls.toMap(),
    tempo = tempo,
    semitoneShift = semitoneShift,
    loop = loop,
    metronome = NativeAudioPlaybackMetronomeSnapshot(
        enabled = metronome.enabled,
        songSynced = metronome.songSynced,
        bpm = metronome.bpm,
        volume = metronome.volume,
        subdivision = metronome.subdivision,
        numerator = metronome.numerator,
        denominator = metronome.denominator,
        beatGridMs = retainedTiming?.beatGridMs ?: metronome.beatGridMs.toList(),
        downbeats = retainedTiming?.downbeats ?: metronome.downbeats.toList(),
        meterSegments = retainedTiming?.meterSegments ?: metronome.meterSegments.toList()
    )
)

private fun canonicalStemLanes(lanes: List<EngineStemLane>): List<EngineStemLane> =
    requireNotNull(canonicalEngineStemLanes(lanes)) { "A source package requires distinct supported parts and Remaining" }

private fun stemPackageRequest(desired: EngineDesiredState): PlaybackStemPackageRequest {
    val canonical = canonicalStemLanes(desired.stemLanes)
    val signature = engineStemPackageSignature(canonical)
    return PlaybackStemPackageRequest(signature, desired.stemRevision, desired.revision)
}

private fun NativeEngineControllerEvent.tokenOrNull(): EngineSelectionToken? = when (this) {
    is NativeEngineControllerEvent.OutputAvailabilityChanged -> token
    is NativeEngineControllerEvent.Position -> token
    is NativeEngineControllerEvent.AudibleEnd -> token
    is NativeEngineControllerEvent.SourceModeApplied -> token
    is NativeEngineControllerEvent.SourceModeReadyAwaitingAudible -> token
    is NativeEngineControllerEvent.SourceModeNotReady -> token
    is NativeEngineControllerEvent.StemPackagePreparing -> token
    is NativeEngineControllerEvent.StemPackageReadyAwaitingAudible -> token
    is NativeEngineControllerEvent.StemPackageInstalled -> token
    is NativeEngineControllerEvent.StemPackageFailed -> token
    is NativeEngineControllerEvent.DecoderFailed -> token
    is NativeEngineControllerEvent.SelectionFailed -> token
    is NativeEngineControllerEvent.OwnerTerminated -> EngineSelectionToken(ownership.selectionToken)
    is NativeEngineControllerEvent.LoopNotReady -> token
    is NativeEngineControllerEvent.LoopAvailabilityChanged -> token
    is NativeEngineControllerEvent.BeatGridNotReady -> token
    is NativeEngineControllerEvent.OwnerPublished,
    is NativeEngineControllerEvent.MetadataUpdated,
    is NativeEngineControllerEvent.Failure,
    is NativeEngineControllerEvent.FatalRecoveryRequired,
    is NativeEngineControllerEvent.StopCompleted,
    NativeEngineControllerEvent.Closed -> null
}
