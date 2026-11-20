package app.stemwise.playback.engine

/** One immutable decoder-head sample used to explain the controller's Stems readiness gate. */
data class NativeStemLaneReadinessDiagnostics(
    val laneIndex: Int,
    val decoderId: String?,
    val configured: Boolean,
    val acceptedTimelineFrames48k: Long,
    val firstTimelineFrame48k: Long?,
    val acceptedTimelineFrameExclusive48k: Long?,
    val discontinuities: Long,
    val hasMinimumAcceptedFrames: Boolean,
    val coversAnchor: Boolean,
    val coversRequiredEnd: Boolean,
    val ready: Boolean
)

/**
 * Actor-published copy of the exact lane samples used by [NativePlaybackEngineController]'s
 * most recent selected-owner Stems readiness decision. Decoder workers never mutate this object.
 */
data class NativeStemReadinessDiagnostics(
    val actorTurn: Long,
    val controlRevision: Long,
    val selectionToken: Long?,
    val ownership: NativeRuntimeOwnership?,
    val desiredRevision: Long?,
    val desiredSourceMode: EngineSourceMode?,
    val sourceIntentRevision: Long,
    val stemPackageSignature: String?,
    val stemPackageRequestRevision: Long?,
    val pendingStemRequestRevision: Long?,
    val stemReadyEventRevision: Long,
    val anchorFrame48k: Long?,
    val requiredEndFrame48k: Long?,
    val ready: Boolean,
    val failureReasons: List<String>,
    val lanes: List<NativeStemLaneReadinessDiagnostics>
)

/** Latest bounded actor publications that map desired source intent to native control revisions. */
data class NativeSourceControlPublicationDiagnostics(
    val nativeControlRevision: Long,
    val ownership: NativeRuntimeOwnership,
    val selectionToken: Long,
    val desiredRevision: Long,
    val requestedMode: EngineSourceMode,
    val nativeMode: NativeRuntimeSourceMode,
    val stemPackageSignature: String?,
    val stemRevision: String?,
    val stemRequestRevision: Long?,
    val nativeControls: NativeRuntimeControls? = null,
    val stemLaneIds: List<String> = emptyList()
)

/** Read-only decoder/adapter evidence used to diagnose an actor-visible ingress stall. */
data class NativeEngineDecoderPipelineDiagnostics(
    val decoderId: String,
    val laneIndex: Int,
    val head: NativeEngineDecoderHead,
    val decoder: NativeDecoderDiagnostics? = null,
    val adapter: DecoderNativeLaneAdapterDiagnostics? = null,
    /** JNI's live view of the decoder bank; -1 means its published native lane disappeared. */
    val nativeReadableSourceFrames: Int? = null,
    val nativeBankIdentity: Long? = null
)

/**
 * One read-only qualification view of the native renderer and its sole device-output owner.
 *
 * Production snapshots always include [output]. Deterministic controller fakes may omit it while
 * continuing to exercise runtime ownership and reducer behavior without constructing AudioTrack.
 */
data class NativeEngineDiagnosticsSnapshot(
    val runtime: NativeRuntimeDiagnostics,
    val output: NativeAudioDiagnostics?,
    val retainedMetadataTokenCount: Int = 0,
    val decoderLeases: NativeDecoderLeaseSnapshot? = null,
    /** Separate from the shared decoder pool; no total-worker claim may omit this owner. */
    val dedicatedLoopPreparationWorkers: NativeDecoderWorkerPoolDiagnostics? = null,
    /** Actor-level evidence used to qualify fatal fail-closed behavior without another renderer. */
    val controllerActorTurnCount: Long = 0L,
    /** Same-stream callback pauses completed after the short foreground-resume grace. */
    val controllerOutputQuiesceCount: Long = 0L,
    val controllerOutputPhysicallyQuiesced: Boolean = false,
    val controllerFatalTransitionCount: Long = 0L,
    val controllerFatalQuarantined: Boolean = false,
    /** Successful public false-to-true silence-arm edges across manual selection bursts. */
    val manualSelectionSilenceEdgeCount: Long = 0L,
    /** Exact synchronous silence-generation captures; one per armed burst, never per tap. */
    val manualSelectionSilenceGenerationCaptureCount: Long = 0L,
    /** Semantic automatic-end plan publications accepted by the controller, including retries. */
    val controllerAutomaticEndPlanRequestCount: Long = 0L,
    /** Thread-safe exact managed-loop protocol evidence for qualification/instrumentation. */
    val controllerLoopProtocol: NativeRuntimeLoopProtocolSnapshot? = null,
    /** Exact actor fences explaining why a native streamed-loop ingress is or is not serviceable. */
    val controllerPublicOwnership: NativeRuntimeOwnership? = null,
    val controllerPublishedLoopOwnership: NativeRuntimeOwnership? = null,
    val controllerPublishedLoopAvailable: Boolean = false,
    /** Saved actor input and scheduling decision; fresh native diagnostics use a different clock. */
    val controllerObservedRenderReadiness: NativeRuntimeRenderReadinessDiagnostics? = null,
    val controllerCacheReadinessPending: Boolean? = null,
    val controllerNextPollDelayMs: Long? = null,
    val controllerPendingSeekOwnership: NativeRuntimeOwnership? = null,
    val controllerSelectedIngressEpoch: Long = 0L,
    val controllerLoopIngressPlanSequence: Long = 0L,
    val controllerLoopIngressPlanEpoch: Long = 0L,
    val controllerSelectedDecoders: List<NativeEngineDecoderPipelineDiagnostics> = emptyList(),
    val controllerCandidateDecoders: List<NativeEngineDecoderPipelineDiagnostics> = emptyList(),
    val nativeRenderReadiness: NativeRuntimeRenderReadinessDiagnostics? = null,
    val controllerStemReadiness: NativeStemReadinessDiagnostics? = null,
    val controllerSourceControlPublications: List<NativeSourceControlPublicationDiagnostics> =
        emptyList(),
    /** Exact gain overlay and PCM proven by one coherent native output callback. */
    val stemGainCallbackSettlement: StemGainCallbackSettlementDiagnostics? = null,
    val controllerPreparationStages: List<NativeControllerPreparationStageDiagnostics> = emptyList()
)
