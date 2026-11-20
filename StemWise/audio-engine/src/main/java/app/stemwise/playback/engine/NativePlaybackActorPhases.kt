package app.stemwise.playback.engine

import java.util.Collections

/** Actor-owned phase data; only the existing controller actor mutates these records. */
internal data class SourceActivationEndpoint(val revision: Long, val mode: EngineSourceMode)
internal data class NativeSourcePublication(val revision: Long, val mode: NativeRuntimeSourceMode)

internal enum class InternalReplacementPurpose {
    STEM_RECOVERY,
    EARLY_SEEK
}

internal data class BankRecord(
    val bank: NativeRuntimeBank,
    val identity: EngineAudioIdentity,
    var ownership: NativeRuntimeOwnership,
    /** Decoder/LaneIngress epoch; managed streamed loops advance it without rebasing owner. */
    var ingressEpoch: Long = ownership.generation,
    var reservation: EngineSelectionReservation?,
    var selection: EngineSelection?,
    var desired: EngineDesiredState?,
    // The command actor is the sole writer, but qualification can copy decoder diagnostics
    // from another thread. Preserve lane insertion order while making each topology mutation
    // share one monitor with the diagnostic copy; iterating a live LinkedHashMap here used to
    // throw ConcurrentModificationException during real source-transition pressure.
    val decoders: MutableMap<Int, NativeEngineDecoder> =
        Collections.synchronizedMap(linkedMapOf()),
    var assigned: Boolean = false,
    var intentRevision: Long = 1L,
    var lastDesiredRevision: Long = 0L,
    /** Revision of the latest source-mode intent, independent of unrelated control updates. */
    var sourceIntentRevision: Long = 0L,
    /** Revision of the latest stem-package intent, independent of source/mixer/tune updates. */
    var stemIntentRevision: Long = 0L,
    var lastSeekRevision: Long = 0L,
    var originalPrimed: Boolean = false,
    /** Failed Original stays in this bank until replacement commits or exact retirement completes. */
    var originalRecoveryRequired: Boolean = false,
    /** Exact latest queue-policy revision permitted to cross the native drain boundary. */
    var automaticSuccessorRevision: Long = 0L,
    var stemSignature: String? = null,
    val sourceActivation: NativeSourceActivationState = NativeSourceActivationState(),
    var lastBeatGridFailureRevision: Long = 0L,
    var publishedBeatGrid: NativeRuntimeBeatGridUpdate? = null,
    var publishedBeatTiming: EngineMetronomeConfig? = null,
    var beatGridRuntimeIncarnation: Long = -1L,
    var physicalStemControls: Map<String, EngineStemControl> = emptyMap(),
    var lastSourceNotReadyRevision: Long = 0L,
    var stemAnchorFrame: Long? = null,
    var replacementPurpose: InternalReplacementPurpose? = null,
    var sourceTransitionTarget: NativeRuntimeOwnership? = null,
    /** This graph's Original began at zero and may own the contiguous all-song cache build. */
    var frameZeroCacheBuilderEligible: Boolean = false,
    /** Exact package physically represented by its declared stem lanes, never a partial package. */
    var stemPackage: StemPackageRequest? = null,
    /** Latest requested package transaction hosted by, or destined for, this bank. */
    var pendingStemPackage: StemPackageRequest? = null,
    var retainedPriorStemSignature: String? = null,
    var priorStemPackageStillRetained: Boolean = false,
    var stemReadyEventRevision: Long = 0L,
    /** Exact owner/package that already passed live native-reservoir activation coverage. */
    val stemReadiness: NativeStemReadinessOwner = NativeStemReadinessOwner(),
    val stemIdentity: NativeStemPackageIdentity = NativeStemPackageIdentity(),
    var lastFailedStemRequestRevision: Long = 0L,
    var stemCleanupRequired: Boolean = false,
    var stemCleanupDeadlineMs: Long? = null
)

internal data class ManagedLoopStableIdentity(
    val selectionToken: EngineSelectionToken,
    val enabled: Boolean,
    /**
     * Immutable decoded-head identity. A newly prepared cache for an otherwise identical A-B
     * range is a new native policy identity and must receive a fresh command revision. Owner
     * generation rebases intentionally leave this value unchanged.
     */
    val cacheRevision: Long = 0L,
    val songIdentityHash: Long = 0L,
    val originalContentHash: Long = 0L,
    val stemPackageHash: Long = 0L,
    val contentRevision: Long = 0L,
    val startTimelineFrame: Long = 0L,
    /** A/content-keyed decoded head ceiling; B is not part of this identity. */
    val endTimelineFrameExclusive: Long = 0L,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode =
        NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS,
    val requiredLaneMask: Int = 0,
    val declickFrames: Int = 0,
)

internal data class EffectiveManagedLoop(
    val startMs: Long,
    val boundaryMode: NativeRuntimeManagedLoopBoundaryMode
)

internal data class LoopPreparationLaneSource(
    val laneIndex: Int,
    val laneId: String,
    val location: String,
    val sourceAdmission: NativeSourceAdmission = NativeSourceAdmission()
)

internal enum class LoopPreparationPurpose { RANGE, REPEAT }

internal sealed interface LoopPreparationResult {
    data object Preparing : LoopPreparationResult
    data object Ready : LoopPreparationResult
    data class Failed(val cause: String, val retryAtMs: Long = Long.MAX_VALUE) : LoopPreparationResult
}

internal data class LoopPreparationRequest(
    val identity: ManagedLoopStableIdentity,
    val intent: NativeRuntimeManagedLoopIntent,
    val attempt: Int = 1,
    val result: LoopPreparationResult = LoopPreparationResult.Preparing
)

internal data class SeekPlan(
    val record: BankRecord,
    val tickets: Map<Int, NativeEngineDecoderSeekTicket>,
    val ownership: NativeRuntimeOwnership
)

/**
 * Actor-owned, fail-silent audio-route transaction.
 *
 * Android can report a removed Bluetooth sink before the replacement speaker route is ready.
 * A failed AudioTrack reopen in that interval is transient, not a corrupt-engine invariant. Keep the
 * joined runtime and empty output paused, conflate newer controls, and retry without poisoning
 * the process-lifetime playback owner.
 */
internal enum class RouteRecoveryPhase { REOPENING_OUTPUT, RETIRING_OUTPUT, REBUILDING_RUNTIME }

internal data class RouteRecoveryTransaction(
    var target: BankRecord?,
    var positionMs: Long,
    var phase: RouteRecoveryPhase = RouteRecoveryPhase.REOPENING_OUTPUT,
    var seekPlans: List<SeekPlan> = emptyList(),
    var lastReportedFailure: String? = null
)
