package app.stemwise.playback.engine

import app.stemwise.playback.LoopPreparationState



enum class NativeAudioPlaybackSessionLifecycle {
    ACTIVE,
    STOPPING,
    /** Native safety quarantine is terminal for this process; Recents removal retires it. */
    RESTART_REQUIRED,
    CLOSED
}

data class NativeAudioPlaybackMetronomeSnapshot(
    val enabled: Boolean,
    val songSynced: Boolean,
    val bpm: Float,
    val volume: Float,
    val subdivision: Int,
    val numerator: Int,
    val denominator: Int,
    val beatGridMs: List<Long>,
    val downbeats: List<Boolean>,
    val meterSegments: List<EngineMeterSegment>
)

data class NativeAudioPlaybackControlSnapshot(
    val revision: Long,
    val playWhenReady: Boolean,
    val requestedPositionMs: Long,
    val seekRevision: Long,
    val sourceMode: EngineSourceMode,
    val stemLanes: List<EngineStemLane>,
    val stemControls: Map<String, EngineStemControl>,
    val tempo: Float,
    val semitoneShift: Int,
    val loop: EngineLoopConfig?,
    val metronome: NativeAudioPlaybackMetronomeSnapshot
)

data class NativeAudioPlaybackRequestedOwner(
    val selection: EngineSelection,
    val metadata: PlaybackPublicationMetadata,
    /** Null until the controller publishes the exact selected bank/generation. */
    val exactOwnership: NativeRuntimeOwnership?,
    val controls: NativeAudioPlaybackControlSnapshot
)

/**
 * Immutable authority for commands originating outside the session adapter.
 *
 * A song id is not an ownership boundary: selecting the same song again creates a different
 * native owner. The selection token is therefore the authority, while the remaining identity is
 * retained as an invariant/debugging aid. Every lease-bound mutation validates this complete
 * identity and performs the mutation while holding the same adapter gate, so validation cannot
 * race a later selection.
 */
data class NativeAudioPlaybackCommandLease(
    val token: EngineSelectionToken,
    val songId: String,
    val audioRevision: String
)

/** Silent desired controls for one exact prepared package; this is not audible-install proof. */
data class PausedPreparedStemMixLease(
    val command: NativeAudioPlaybackCommandLease,
    val ownership: NativeRuntimeOwnership,
    val sourceRevision: Long,
    val packageSignature: String,
    val stemRevision: String,
    val packageRequestRevision: Long,
    val laneIds: Set<String>
)

/** One package signature can legitimately be reused by a newer enrichment request. */
fun stemMixPackageRequestRevisionsMatch(
    installedRequestRevision: Long,
    desiredRequestRevision: Long,
    requestedRequestRevision: Long
): Boolean = installedRequestRevision > 0L &&
    installedRequestRevision == desiredRequestRevision &&
    installedRequestRevision == requestedRequestRevision

/** Immutable admission captured while the exact selection is serialized under the adapter gate. */
data class NativeAudioPlaybackSeekReceipt(
    val token: EngineSelectionToken,
    val seekRevision: Long,
    val positionMs: Long
)

/** Position, duration and command authority captured together, independent of facade collection. */
data class NativeAudioPlaybackRelativeSeek(
    val lease: NativeAudioPlaybackCommandLease,
    val positionMs: Long
)

/** Exact native owner observed inside the adapter gate for one accepted mutation. */
data class NativeAudioPlaybackOwnerReceipt(
    val token: EngineSelectionToken,
    val ownership: NativeRuntimeOwnership
) {
    init {
        require(ownership.selectionToken == token.value) {
            "An owner receipt must describe the exact selection token it carries"
        }
    }
}

/** Exact native/package owner observed for one accepted source-mixer mutation. */
data class NativeAudioPlaybackStemMixReceipt(
    val token: EngineSelectionToken,
    val ownership: NativeRuntimeOwnership,
    val packageSignature: String
) {
    init {
        require(ownership.selectionToken == token.value) {
            "A stem-mix receipt must describe the exact selection token it carries"
        }
        require(packageSignature.isNotBlank()) {
            "A stem-mix receipt must describe one installed package"
        }
    }
}

/** Immutable bridge snapshot. Only [publication.committed] may drive visible/session identity. */
data class NativeAudioPlaybackSessionState(
    val publication: PlaybackPublicationReducerState = PlaybackPublicationReducerState(),
    val latestRequest: NativeAudioPlaybackRequestedOwner? = null,
    /** Controls and the committed publication share one adapter-gated immutable snapshot. */
    val committedControls: NativeAudioPlaybackControlSnapshot? = null,
    val loopAvailable: Boolean = false,
    val loopPreparation: LoopPreparationState = LoopPreparationState(),
    val likelyNextIdentity: EngineAudioIdentity? = null,
    val automaticEndPlan: NativeEngineAutomaticEndPlan? = null,
    val lifecycle: NativeAudioPlaybackSessionLifecycle =
        NativeAudioPlaybackSessionLifecycle.ACTIVE,
    val terminalFailure: String? = null,
    /** Stable across ordinary position callbacks so facades can use a position-only fast path. */
    val structuralRevision: Long = 0L
)

sealed interface NativeAudioPlaybackSessionEffect {
    data class OutputAvailabilityChanged(val token: EngineSelectionToken, val available: Boolean,
        val diagnostic: String?) : NativeAudioPlaybackSessionEffect
    data class AudibleEnd(
        val token: EngineSelectionToken,
        val ownership: NativeRuntimeOwnership,
        val sequence: Long,

        val sourceChangePending: Boolean = false
    ) : NativeAudioPlaybackSessionEffect

    data class StopCompleted(
        val requestId: NativeEngineStopRequestId,
        val clearedPublication: Boolean
    ) : NativeAudioPlaybackSessionEffect

    data class Diagnostic(val operation: String, val diagnostic: String) :
        NativeAudioPlaybackSessionEffect

    data class OwnerTerminated(
        val ownership: NativeRuntimeOwnership,
        val cause: String
    ) : NativeAudioPlaybackSessionEffect

    data class SelectionFailed(
        val token: EngineSelectionToken,
        val cause: String,
        /** Opaque facade request serial; zero is reserved for adapter-owned selections. */
        val requestTag: Long = 0L
    ) : NativeAudioPlaybackSessionEffect

    data class LoopRejected(
        val token: EngineSelectionToken,
        val cause: String
    ) : NativeAudioPlaybackSessionEffect

    data class OutputUnavailable(val diagnostic: String) : NativeAudioPlaybackSessionEffect

    data object Closed : NativeAudioPlaybackSessionEffect
}

fun interface NativeAudioPlaybackSessionEffectListener {
    fun onEffect(effect: NativeAudioPlaybackSessionEffect)

    companion object {
        val None = NativeAudioPlaybackSessionEffectListener { }
    }
}

internal enum class NativeEngineSelectionAdmissionKind {
    NEW,
    CLAIMED_PREWARM,
    ALREADY_CURRENT
}

internal data class NativeEngineSelectionAdmission(
    val token: EngineSelectionToken,
    val kind: NativeEngineSelectionAdmissionKind
)

internal data class NativeEngineManualPrewarmRequest(
    val ownerToken: EngineSelectionToken,
    val item: NativeEngineQueueItem
)

/** Narrow test seam around the one production [NativePlaybackEngineController]. */
internal interface NativeAudioPlaybackSessionEngine : AutoCloseable {
    /** Closes the existing manual gate before a new song's source lookup may suspend. */
    fun beginManualSelectionIntentAdmission() = Unit

    /** Rebinds that gate to the still-current owner when lookup produces no replacement. */
    fun completeManualSelectionIntentWithoutReplacement(token: EngineSelectionToken?) = Unit

    fun select(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata
    ): EngineSelectionToken

    fun selectAdmission(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata
    ): NativeEngineSelectionAdmission = NativeEngineSelectionAdmission(
        select(request, metadata),
        NativeEngineSelectionAdmissionKind.NEW
    )

    fun updateDesired(
        token: EngineSelectionToken,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): Boolean

    fun updateDesiredIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState?

    fun updateDesiredWithAudibleTransportIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        playWhenReady: Boolean,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState?

    fun updateExplicitLoop(state: EngineExplicitLoopState): Boolean

    /** Parks the outgoing physical policy while one retained A/B range follows an exact seek. */
    fun updateExplicitLoopForTransportSeek(state: EngineExplicitLoopState): Boolean =
        updateExplicitLoop(state)

    fun beginStemMixPreview(
        token: EngineSelectionToken,
        ownership: NativeRuntimeOwnership,
        packageSignature: String,
        packageRequestRevision: Long,
        laneIndex: Int
    ): EngineStemMixPreviewLease? = null

    fun offerStemMixPreviewBatch(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean = false

    fun endStemMixPreview(lease: EngineStemMixPreviewLease): Boolean = false

    fun updateMetadata(metadata: NativeEngineMetadata)
    fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?)
    fun setManualPrewarm(request: NativeEngineManualPrewarmRequest?) = Unit
    fun suspendForAudioFocusLoss()
    fun resumeAfterAudioFocusGain()
    fun retryLoopPreparation(ownership: NativeRuntimeOwnership, requestRevision: Long? = null): Boolean = false
    fun requestRouteRecovery()
    fun stopForTaskRemoval(): NativeEngineStopRequest
    fun selectionRetirementTokens(songId: String): Set<EngineSelectionToken>? = null
    fun isSelectionRetired(token: EngineSelectionToken): Boolean = false
    /** Instrumentation-only actor fault; production engines must route it through quarantine. */
    fun injectFatalActorFaultForTest(diagnostic: String): Boolean = false
    /** Instrumentation-only deterministic command-confluence gate. */
    fun holdActorForSelectionBurstForTest(): Boolean = false
    fun releaseActorForSelectionBurstForTest() = Unit
    fun loopBoundaryObservationForTest(): Pair<NativeRuntimePresentedPosition?, NativeAudioDiagnostics?>? = null
    /** Optional only for fake engines; production joins the runtime and sole output snapshots. */
    fun setDiagnosticPayloadFingerprints(enabled: Boolean) = Unit
    fun setDetailedDiagnosticCapture(enabled: Boolean): Boolean = false
    fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot? = null
    suspend fun readSourcePcmWindow(identity: SourceWaveformIdentity, source: SourceWaveformSource,
        first: Long, frames: Int): SourcePcmWindowRead = SourcePcmWindowRead.Unsupported
    fun sourceWaveformDiagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot? = diagnosticsSnapshot()
    /** Independent-cursor journal; fake engines have no native callback and return an empty head. */
    fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot =
        NativeAudioPresentationJournalSnapshot.empty(afterSequence)
}
