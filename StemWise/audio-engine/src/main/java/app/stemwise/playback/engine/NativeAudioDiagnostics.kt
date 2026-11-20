package app.stemwise.playback.engine

enum class NativeAudioStreamState(val rawValue: Int) {
    UNINITIALIZED(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_UNINITIALIZED),
    UNKNOWN(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_UNKNOWN),
    OPEN(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_OPEN),
    STARTED(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_STARTED),
    PAUSED(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_PAUSED),
    CLOSED(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_CLOSED),
    DISCONNECTED(NativePacketSchema.ENUM_NATIVE_AUDIO_STREAM_STATE_DISCONNECTED);

    companion object {
        fun fromRaw(rawValue: Int): NativeAudioStreamState =
            entries.firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
    }
}

enum class NativeAudioBackend(val rawValue: Int) {
    UNSPECIFIED(NativePacketSchema.ENUM_NATIVE_AUDIO_BACKEND_UNSPECIFIED),
    AUDIO_TRACK(NativePacketSchema.ENUM_NATIVE_AUDIO_BACKEND_AUDIO_TRACK),
    UNKNOWN(Int.MIN_VALUE);

    companion object {
        fun fromRaw(rawValue: Int): NativeAudioBackend =
            entries.firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
    }
}

enum class NativeAudioManualSelectionGateState(val rawValue: Int) {
    OPEN(NativePacketSchema.ENUM_NATIVE_AUDIO_MANUAL_SELECTION_GATE_STATE_OPEN),
    CLOSED(NativePacketSchema.ENUM_NATIVE_AUDIO_MANUAL_SELECTION_GATE_STATE_CLOSED),
    AUTHORIZED(NativePacketSchema.ENUM_NATIVE_AUDIO_MANUAL_SELECTION_GATE_STATE_AUTHORIZED),
    UNKNOWN(Int.MIN_VALUE);

    companion object {
        fun fromRaw(rawValue: Int): NativeAudioManualSelectionGateState =
            entries.firstOrNull { it.rawValue == rawValue } ?: UNKNOWN
    }
}

/** Bounded loop-command/callback evidence. Writers never log or allocate on the audio callback. */
data class NativeAudioLoopDiagnostics(
    val acceptedDemandCount: Long,
    val acceptedCaptureCount: Long,
    val latestDemandRevision: Long,
    val latestDemandAFrame: Long,
    val latestDemandBFrameExclusive: Long,
    val latestDemandPresentedFrame: Long,
    val latestDemandBoundaryArmed: Boolean,
    val latestDemandBoundaryCrossed: Boolean,
    val latestCaptureRevision: Long,
    val latestCaptureAFrame: Long,
    val latestCaptureBFrameExclusive: Long,
    val latestCapturePresentedFrame: Long,
    val successfulCaptureCutoverCount: Long,
    val latestCutoverCaptureRevision: Long,
    val latestCutoverCallbackSequence: Long,
    val latestCutoverInputLatencyMicros: Long,
    val processBoundaryMissCount: Long,
    val latestMissCaptureRevision: Long,
    val latestMissCallbackSequence: Long,
    val latestMissPresentedFrame: Long,
    val latestDemandGestureId: Long,
    val latestCaptureGestureId: Long,
    val latestCaptureInferred: Boolean,
    val latestCutoverGeneration: Long,
    val latestCutoverSelectionToken: Long,
    val latestCutoverAFrame: Long,
    val latestCutoverGestureId: Long
) {
    init {
        require(acceptedDemandCount >= 0L && acceptedCaptureCount >= 0L)
        require(latestDemandRevision >= 0L)
        require(latestDemandAFrame >= 0L && latestDemandBFrameExclusive >= 0L)
        require(latestCaptureRevision >= 0L)
        require(latestCaptureAFrame >= 0L && latestCaptureBFrameExclusive >= 0L)
        require(successfulCaptureCutoverCount >= 0L)
        require(latestCutoverCaptureRevision >= 0L && latestCutoverCallbackSequence >= 0L)
        require(latestCutoverInputLatencyMicros >= 0L)
        require(processBoundaryMissCount >= 0L)
        require(latestMissCaptureRevision >= 0L && latestMissCallbackSequence >= 0L)
        require(latestDemandGestureId >= 0L && latestCaptureGestureId >= 0L)
        require(latestCutoverGeneration >= 0L && latestCutoverSelectionToken >= 0L)
        require(latestCutoverAFrame >= 0L && latestCutoverGestureId >= 0L)
        require(latestDemandPresentedFrame >= -1L)
        require(latestCapturePresentedFrame >= -1L)
        require(latestMissPresentedFrame >= -1L)
    }
}

/** Immutable control-thread snapshot. No diagnostic JNI work runs on the audio callback. */
/** One coherent callback publication; sequence zero means no snapshot was available. */
data class NativeAudioCallbackMixDiagnostics(
    val sequence: Long = 0L,
    val callbackSequence: Long = 0L,
    val generation: Long = 0L,
    val selectionToken: Long = 0L,
    val controlRevision: Long = 0L,
    val missingControlFrames: Long = 0L,
    val staleControlFrames: Long = 0L,
    val mixerRejectedFrames: Long = 0L,
    val invalidTagFrames: Long = 0L,
    val advanceFailures: Long = 0L,
    val safeOriginalFallbackFrames: Long = 0L,
    val mixerNonFiniteSamplesScrubbed: Long = 0L,
    val mixerLimiterActiveFrames: Long = 0L,
    val mixerLimiterSafetyClamps: Long = 0L,
    val finalNonFiniteSamplesScrubbed: Long = 0L,
    val finalSafetyClamps: Long = 0L,
) {
    init {
        require(sequence >= 0L)
        require(callbackSequence >= 0L)
        require(generation >= 0L)
        require(selectionToken >= 0L)
        require(controlRevision >= 0L)
        require(missingControlFrames >= 0L)
        require(staleControlFrames >= 0L)
        require(mixerRejectedFrames >= 0L)
        require(invalidTagFrames >= 0L)
        require(advanceFailures >= 0L)
        require(safeOriginalFallbackFrames >= 0L)
        require(mixerNonFiniteSamplesScrubbed >= 0L)
        require(mixerLimiterActiveFrames >= 0L)
        require(mixerLimiterSafetyClamps >= 0L)
        require(finalNonFiniteSamplesScrubbed >= 0L)
        require(finalSafetyClamps >= 0L)
        require(sequence == 0L || callbackSequence > 0L)
    }
}

data class NativeAudioDiagnostics(
    val streamGeneration: Long,
    val streamState: NativeAudioStreamState,
    val backend: NativeAudioBackend,
    val sampleRateHz: Int,
    val channelCount: Int,
    val framesPerBurst: Int,
    val outputBufferCapacityFrames: Int,
    val outputBufferSizeFrames: Int,
    val ringCapacityFrames: Int,
    val queuedFrames: Long,
    val totalFramesAccepted: Long,
    val renderedFrames: Long,
    val callbackCount: Long,
    val callbackFrames: Long,
    val xRunCount: Int,
    val underrunCallbackCount: Long,
    val underrunFrames: Long,
    val staleFramesRejected: Long,
    val producerBackpressureFrames: Long,
    val appliedGeneration: Long,
    val lastPresentedGeneration: Long,
    /** Raw requested transport state, intentionally independent of the callback render gate. */
    val playing: Boolean,
    val recoveryRequired: Boolean,
    val lastErrorCode: Int,
    val reopenCount: Long,
    val lastErrorMonotonicMillis: Long,
    val callbackDeadlineMissCount: Long,
    val maxCallbackDurationMicros: Long,
    val performanceMode: Int,
    /** Exact selection whose tagged final PCM was first consumed by the device callback. */
    val lastPresentedSelectionToken: Long,
    val lastPresentedFingerprintSequence: Long,
    val lastPresentedTimelineFrame: Long,
    val presentedOwnerSequence: Long,
    val lastOwnerFirstOutputFrame: Long,
    val lastOwnerFirstCallbackSequence: Long,
    val lastOwnerFirstPcmFrames: Int,
    val invalidOwnershipTagFrames: Long,
    /** Cumulative/current process-global native output-owner evidence. */
    val processConstructionCount: Long,
    val processLiveInstanceCount: Long,
    /** Actual callback consumption state after applying requested play and the manual gate. */
    val effectiveRenderPlaying: Boolean,
    val manualSelectionGateState: NativeAudioManualSelectionGateState,
    /** Non-zero only while the gate is bound to one exact replacement generation. */
    val manualSelectionAuthorizedGeneration: Long,
    val manualSelectionGateArmCount: Long,
    val manualSelectionGateAuthorizationCount: Long,
    val manualSelectionGateRejectedAuthorizationCount: Long,
    val manualSelectionGateOpenCount: Long,
    /** Exact callback-ticket ceiling captured immediately after the latest manual gate arm. */
    val manualSelectionGateLastArmCallbackSequence: Long,
    /** Newest valid owned span measured after output gain and endpoint transitions. */
    val pcmLevelSequence: Long,
    val pcmLevelGeneration: Long,
    val pcmLevelSelectionToken: Long,
    val pcmLevelFingerprintSequence: Long,
    val pcmLevelCallbackSequence: Long,
    val pcmLevelFrames: Long,
    /** Mean of the squared interleaved stereo samples; RMS is sqrt(meanSquare). */
    val pcmLevelMeanSquare: Float,
    val pcmLevelPeak: Float,
    /** Process-cumulative fault evidence; output-owner replacement cannot reset these totals. */
    val processInvalidOwnershipTagFrames: Long,
    val processUnderrunCallbackCount: Long,
    val processUnderrunFrames: Long,
    val processXRunCount: Long,
    val processCallbackDeadlineMissCount: Long,
    /** Interval between callback starts within one continuously running physical stream. */
    val callbackStartGapMicros: Long,
    val maximumCallbackStartGapMicros: Long,
    /** Coherent evidence for the newest real output underrun on this output owner. */
    val lastUnderrunSequence: Long,
    val lastUnderrunGenerationAtRenderDecision: Long,
    val lastUnderrunGenerationAtCompletion: Long,
    val lastUnderrunCallbackSequence: Long,
    val lastUnderrunRequestedFrames: Long,
    val lastUnderrunQueuedFramesAtRenderDecision: Long,
    val lastUnderrunPhysicalFrames: Long,
    val lastUnderrunValidPcmFrames: Long,
    val lastUnderrunMissingFrames: Long,
    val lastUnderrunCallbackStartGapMicros: Long,
    /** Latest callback-start gap over twice its nominal period at the time of this underrun. */
    val lastUnderrunLatestLateCallbackStartGapMicros: Long,
    val lastUnderrunLatestLateCallbackSequence: Long,
    val lastUnderrunManualArmCallbackCeiling: Long,
    val lastUnderrunAppliedCommitSequence: Long,
    val lastUnderrunRoleSwapAtCallbackStart: Boolean,
    val lastUnderrunRoleSwapDuringDrain: Boolean,
    /** True only for a callback proven to have observed OPEN before a concurrent manual arm. */
    val lastUnderrunWasManualArmBoundary: Boolean,
    /** Process-cumulative exact subset of underruns classified as manual-arm boundary callbacks. */
    val processManualArmBoundaryUnderrunCallbackCount: Long,
    val processManualArmBoundaryUnderrunFrames: Long,
    /** Coherent newest accepted final-PCM producer write on this output owner. */
    val successfulEnqueueSequence: Long,
    val lastSuccessfulEnqueueMonotonicMicros: Long,
    val lastSuccessfulEnqueueGeneration: Long,
    val lastSuccessfulEnqueueAcceptedFrames: Long,
    val lastSuccessfulEnqueueQueueAfterFrames: Long,
    val maximumSuccessfulEnqueueGapMicros: Long,
    /** Exact callback-start timestamp for the newest underrun. */
    val lastUnderrunMonotonicMicros: Long,
    /** False means producer publication overlapped the callback's single non-spinning read. */
    val lastUnderrunSuccessfulEnqueueSnapshotCoherent: Boolean,
    val lastUnderrunSuccessfulEnqueueSequence: Long,
    val lastUnderrunSuccessfulEnqueueGeneration: Long,
    val lastUnderrunSuccessfulEnqueueAgeMicros: Long,
    val lastUnderrunSuccessfulEnqueueAcceptedFrames: Long,
    val lastUnderrunSuccessfulEnqueueQueueAfterFrames: Long,
    val lastUnderrunMaximumSuccessfulEnqueueGapMicros: Long,
    /** Intentional callback fence at newest B while matching A is not yet claimable. */
    val loop: NativeAudioLoopDiagnostics,
    val callbackMix: NativeAudioCallbackMixDiagnostics = NativeAudioCallbackMixDiagnostics(),
    val physicalRunSequence: Long = 0L,
    val lastUnderrunPhysicalRunSequence: Long = 0L,
    val processIncompleteStreamObservationCount: Long = 0L,
    /** Coherent monotonic interval of this output owner's newest callback deadline miss; zero when unavailable. */
    val lastCallbackDeadlineMissStartMicros: Long = 0L,
    val lastCallbackDeadlineMissEndMicros: Long = 0L,
    /** Kotlin owns physical opening; a native-only packet cannot prove that it was never requested. */
    val physicalOpenAttempted: Boolean? = null
) {
    /** Accepted adapter configuration; unavailable AudioTrack-only concepts remain explicit. */
    val outputConfigurationObserved: Boolean
        get() = sampleRateHz > 0 && outputBufferSizeFrames > 0 &&
            outputBufferSizeFrames <= outputBufferCapacityFrames && xRunCount >= 0 &&
            when (backend) {
                NativeAudioBackend.AUDIO_TRACK -> framesPerBurst == -1 && performanceMode in 0..1
                else -> false
            }

    init {
        require(callbackStartGapMicros >= 0L && maximumCallbackStartGapMicros >= 0L)
        require(physicalRunSequence >= 0L && lastUnderrunPhysicalRunSequence >= 0L)
        require(lastUnderrunSequence >= 0L)
        require((lastCallbackDeadlineMissStartMicros == 0L && lastCallbackDeadlineMissEndMicros == 0L) ||
            (lastCallbackDeadlineMissStartMicros > 0L && lastCallbackDeadlineMissEndMicros > lastCallbackDeadlineMissStartMicros))
        if (lastUnderrunSequence == 0L) {
            require(lastUnderrunCallbackSequence == 0L)
            require(lastUnderrunRequestedFrames == 0L)
            require(lastUnderrunMissingFrames == 0L)
            require(!lastUnderrunWasManualArmBoundary)
        } else {
            require(lastUnderrunCallbackSequence > 0L)
            require(lastUnderrunRequestedFrames > 0L)
            require(lastUnderrunMissingFrames > 0L)
            require(lastUnderrunPhysicalFrames in 0L..lastUnderrunRequestedFrames)
            require(lastUnderrunValidPcmFrames in 0L..lastUnderrunPhysicalFrames)
            require(
                lastUnderrunMissingFrames ==
                    lastUnderrunRequestedFrames - lastUnderrunValidPcmFrames
            )
            require(
                lastUnderrunLatestLateCallbackSequence in
                    0L..lastUnderrunCallbackSequence
            )
            if (lastUnderrunWasManualArmBoundary) {
                require(lastUnderrunManualArmCallbackCeiling >= lastUnderrunCallbackSequence)
            }
        }
        require(processManualArmBoundaryUnderrunCallbackCount >= 0L)
        require(processManualArmBoundaryUnderrunFrames >= 0L)
        require(successfulEnqueueSequence >= 0L)
        require(lastSuccessfulEnqueueMonotonicMicros >= 0L)
        require(lastSuccessfulEnqueueGeneration >= 0L)
        require(lastSuccessfulEnqueueAcceptedFrames >= 0L)
        require(lastSuccessfulEnqueueQueueAfterFrames >= 0L)
        require(maximumSuccessfulEnqueueGapMicros >= 0L)
        if (successfulEnqueueSequence == 0L) {
            require(lastSuccessfulEnqueueMonotonicMicros == 0L)
            require(lastSuccessfulEnqueueGeneration == 0L)
            require(lastSuccessfulEnqueueAcceptedFrames == 0L)
            require(lastSuccessfulEnqueueQueueAfterFrames == 0L)
        } else {
            require(lastSuccessfulEnqueueMonotonicMicros > 0L)
            require(lastSuccessfulEnqueueGeneration > 0L)
            require(lastSuccessfulEnqueueAcceptedFrames > 0L)
        }
        require(lastUnderrunMonotonicMicros >= 0L)
        require(lastUnderrunSuccessfulEnqueueSequence >= 0L)
        require(lastUnderrunSuccessfulEnqueueGeneration >= 0L)
        require(lastUnderrunSuccessfulEnqueueAgeMicros >= 0L)
        require(lastUnderrunSuccessfulEnqueueAcceptedFrames >= 0L)
        require(lastUnderrunSuccessfulEnqueueQueueAfterFrames >= 0L)
        require(lastUnderrunMaximumSuccessfulEnqueueGapMicros >= 0L)
        if (lastUnderrunSequence == 0L) {
            require(lastUnderrunMonotonicMicros == 0L)
            require(!lastUnderrunSuccessfulEnqueueSnapshotCoherent)
            require(lastUnderrunSuccessfulEnqueueSequence == 0L)
        } else {
            require(lastUnderrunMonotonicMicros > 0L)
            if (lastUnderrunSuccessfulEnqueueSnapshotCoherent &&
                lastUnderrunSuccessfulEnqueueSequence > 0L) {
                require(lastUnderrunSuccessfulEnqueueGeneration > 0L)
                require(lastUnderrunSuccessfulEnqueueAcceptedFrames > 0L)
            }
        }
    }
}

internal object NativeAudioDiagnosticsCodec {
    private const val SCHEMA_VERSION = NativePacketSchema.OUTPUT_DIAGNOSTICS_VERSION
    private const val FIELD_COUNT = NativePacketSchema.OUTPUT_DIAGNOSTICS_COUNT

    fun decode(fields: LongArray): NativeAudioDiagnostics {
        require(fields.size == FIELD_COUNT) {
            "Expected $FIELD_COUNT native diagnostic fields, received ${fields.size}"
        }
        require(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native diagnostic schema ${fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_SCHEMA]}"
        }
        fun boolean(index: Int, label: String): Boolean {
            require(fields[index] == 0L || fields[index] == 1L) {
                "$label must be encoded as zero or one"
            }
            return fields[index] == 1L
        }
        require(NativeAudioStreamState.entries.any { it.rawValue.toLong() == fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_STATE] }) { "Unknown stream state" }
        require(NativePacketSchema.isNativeAudioBackend(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND])) { "Unknown output backend" }
        require(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_STATE] in 0L..2L) { "Unknown manual selection gate" }
        return NativeAudioDiagnostics(
            // BEGIN GENERATED output-diagnostics
            streamGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_GENERATION],
            streamState = NativeAudioStreamState.fromRaw(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_STATE].nativePacketInt()),
            backend = NativeAudioBackend.fromRaw(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND].nativePacketInt()),
            sampleRateHz = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_SAMPLE_RATE_HZ].nativePacketInt(),
            channelCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CHANNEL_COUNT].nativePacketInt(),
            framesPerBurst = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_FRAMES_PER_BURST].nativePacketInt(),
            outputBufferCapacityFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_OUTPUT_BUFFER_CAPACITY_FRAMES].nativePacketInt(),
            outputBufferSizeFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_OUTPUT_BUFFER_SIZE_FRAMES].nativePacketInt(),
            ringCapacityFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_RING_CAPACITY_FRAMES].nativePacketInt(),
            queuedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_QUEUED_FRAMES],
            totalFramesAccepted = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_TOTAL_FRAMES_ACCEPTED],
            renderedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_RENDERED_FRAMES],
            callbackCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_COUNT],
            callbackFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_FRAMES],
            xRunCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_X_RUN_COUNT].nativePacketInt(),
            underrunCallbackCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_CALLBACK_COUNT],
            underrunFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_FRAMES],
            staleFramesRejected = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_STALE_FRAMES_REJECTED],
            producerBackpressureFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PRODUCER_BACKPRESSURE_FRAMES],
            appliedGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_APPLIED_GENERATION],
            lastPresentedGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_PRESENTED_GENERATION],
            playing = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PLAYING].nativePacketBoolean(),
            recoveryRequired = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_RECOVERY_REQUIRED].nativePacketBoolean(),
            lastErrorCode = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_ERROR_CODE].nativePacketInt(),
            reopenCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_REOPEN_COUNT],
            lastErrorMonotonicMillis = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_ERROR_MONOTONIC_MILLIS],
            callbackDeadlineMissCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_DEADLINE_MISS_COUNT],
            maxCallbackDurationMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MAX_CALLBACK_DURATION_MICROS],
            performanceMode = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PERFORMANCE_MODE].nativePacketInt(),
            lastPresentedSelectionToken = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_PRESENTED_SELECTION_TOKEN],
            lastPresentedFingerprintSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_PRESENTED_FINGERPRINT_SEQUENCE],
            lastPresentedTimelineFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_PRESENTED_TIMELINE_FRAME],
            presentedOwnerSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PRESENTED_OWNER_SEQUENCE],
            lastOwnerFirstOutputFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_OWNER_FIRST_OUTPUT_FRAME],
            lastOwnerFirstCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_OWNER_FIRST_CALLBACK_SEQUENCE],
            lastOwnerFirstPcmFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_OWNER_FIRST_PCM_FRAMES].nativePacketInt(),
            invalidOwnershipTagFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_INVALID_OWNERSHIP_TAG_FRAMES],
            processConstructionCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_CONSTRUCTION_COUNT].also { require(it >= 0L) },
            processLiveInstanceCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_LIVE_INSTANCE_COUNT].also { require(it >= 0L) },
            effectiveRenderPlaying = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_EFFECTIVE_RENDER_PLAYING].nativePacketBoolean(),
            manualSelectionGateState = NativeAudioManualSelectionGateState.fromRaw(fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_STATE].nativePacketInt()),
            manualSelectionAuthorizedGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_AUTHORIZED_GENERATION].also { require(it >= 0L) },
            manualSelectionGateArmCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_ARM_COUNT].also { require(it >= 0L) },
            manualSelectionGateAuthorizationCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_AUTHORIZATION_COUNT].also { require(it >= 0L) },
            manualSelectionGateRejectedAuthorizationCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_REJECTED_AUTHORIZATION_COUNT].also { require(it >= 0L) },
            manualSelectionGateOpenCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_OPEN_COUNT].also { require(it >= 0L) },
            manualSelectionGateLastArmCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_LAST_ARM_CALLBACK_SEQUENCE].also { require(it >= 0L) },
            pcmLevelSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_SEQUENCE].also { require(it >= 0L) },
            pcmLevelGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_GENERATION].also { require(it >= 0L) },
            pcmLevelSelectionToken = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_SELECTION_TOKEN].also { require(it >= 0L) },
            pcmLevelFingerprintSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_FINGERPRINT_SEQUENCE].also { require(it >= 0L) },
            pcmLevelCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_CALLBACK_SEQUENCE].also { require(it >= 0L) },
            pcmLevelFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_FRAMES].also { require(it >= 0L) },
            pcmLevelMeanSquare = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_MEAN_SQUARE].nativePacketFloat().also { require(it.isFinite() && it >= 0f) },
            pcmLevelPeak = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_PEAK].nativePacketFloat().also { require(it.isFinite() && it >= 0f) },
            processInvalidOwnershipTagFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_INVALID_OWNERSHIP_TAG_FRAMES].also { require(it >= 0L) },
            processUnderrunCallbackCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_CALLBACK_COUNT].also { require(it >= 0L) },
            processUnderrunFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_UNDERRUN_FRAMES].also { require(it >= 0L) },
            processXRunCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_XRUN_COUNT].also { require(it >= 0L) },
            processCallbackDeadlineMissCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_CALLBACK_DEADLINE_MISS_COUNT].also { require(it >= 0L) },
            callbackStartGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_START_GAP_MICROS].also { require(it >= 0L) },
            maximumCallbackStartGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MAXIMUM_CALLBACK_START_GAP_MICROS].also { require(it >= 0L) },
            physicalRunSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PHYSICAL_RUN_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunPhysicalRunSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_PHYSICAL_RUN_SEQUENCE].also { require(it >= 0L) },
            processIncompleteStreamObservationCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_INCOMPLETE_STREAM_OBSERVATION_COUNT].also { require(it >= 0L) },
            lastCallbackDeadlineMissStartMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_START_MICROS],
            lastCallbackDeadlineMissEndMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_END_MICROS],
            lastUnderrunSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunGenerationAtRenderDecision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_GENERATION_AT_RENDER_DECISION].also { require(it >= 0L) },
            lastUnderrunGenerationAtCompletion = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_GENERATION_AT_COMPLETION].also { require(it >= 0L) },
            lastUnderrunCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_CALLBACK_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunRequestedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_REQUESTED_FRAMES].also { require(it >= 0L) },
            lastUnderrunQueuedFramesAtRenderDecision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_QUEUED_FRAMES_AT_RENDER_DECISION].also { require(it >= 0L) },
            lastUnderrunPhysicalFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_PHYSICAL_FRAMES].also { require(it >= 0L) },
            lastUnderrunValidPcmFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_VALID_PCM_FRAMES].also { require(it >= 0L) },
            lastUnderrunMissingFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_MISSING_FRAMES].also { require(it >= 0L) },
            lastUnderrunCallbackStartGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_CALLBACK_START_GAP_MICROS].also { require(it >= 0L) },
            lastUnderrunLatestLateCallbackStartGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_LATEST_LATE_CALLBACK_START_GAP_MICROS].also { require(it >= 0L) },
            lastUnderrunLatestLateCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_LATEST_LATE_CALLBACK_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunManualArmCallbackCeiling = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_MANUAL_ARM_CALLBACK_CEILING].also { require(it >= 0L) },
            lastUnderrunAppliedCommitSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_APPLIED_COMMIT_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunRoleSwapAtCallbackStart = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_EVIDENCE_ROLE_SWAP_AT_CALLBACK_START, "underrun callback-start swap"),
            lastUnderrunRoleSwapDuringDrain = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_EVIDENCE_ROLE_SWAP_DURING_DRAIN, "underrun drain swap"),
            lastUnderrunWasManualArmBoundary = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_EVIDENCE_MANUAL_ARM_BOUNDARY, "manual-arm boundary underrun"),
            processManualArmBoundaryUnderrunCallbackCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_CALLBACK_COUNT].also { require(it >= 0L) },
            processManualArmBoundaryUnderrunFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_MANUAL_ARM_BOUNDARY_UNDERRUN_FRAMES].also { require(it >= 0L) },
            successfulEnqueueSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_SUCCESSFUL_ENQUEUE_SEQUENCE].also { require(it >= 0L) },
            lastSuccessfulEnqueueMonotonicMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_SUCCESSFUL_ENQUEUE_MONOTONIC_MICROS].also { require(it >= 0L) },
            lastSuccessfulEnqueueGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_SUCCESSFUL_ENQUEUE_GENERATION].also { require(it >= 0L) },
            lastSuccessfulEnqueueAcceptedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES].also { require(it >= 0L) },
            lastSuccessfulEnqueueQueueAfterFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES].also { require(it >= 0L) },
            maximumSuccessfulEnqueueGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS].also { require(it >= 0L) },
            lastUnderrunMonotonicMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_MONOTONIC_MICROS].also { require(it >= 0L) },
            lastUnderrunSuccessfulEnqueueSnapshotCoherent = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_UNDERRUN_EVIDENCE_SUCCESSFUL_ENQUEUE_SNAPSHOT_COHERENT, "underrun successful-enqueue snapshot coherence"),
            lastUnderrunSuccessfulEnqueueSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_SEQUENCE].also { require(it >= 0L) },
            lastUnderrunSuccessfulEnqueueGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_GENERATION].also { require(it >= 0L) },
            lastUnderrunSuccessfulEnqueueAgeMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_AGE_MICROS].also { require(it >= 0L) },
            lastUnderrunSuccessfulEnqueueAcceptedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_ACCEPTED_FRAMES].also { require(it >= 0L) },
            lastUnderrunSuccessfulEnqueueQueueAfterFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_SUCCESSFUL_ENQUEUE_QUEUE_AFTER_FRAMES].also { require(it >= 0L) },
            lastUnderrunMaximumSuccessfulEnqueueGapMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_UNDERRUN_MAXIMUM_SUCCESSFUL_ENQUEUE_GAP_MICROS].also { require(it >= 0L) },
            // END GENERATED output-diagnostics
            callbackMix = NativeAudioCallbackMixDiagnostics(
                // BEGIN GENERATED callback-mix
                sequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SEQUENCE],
                callbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_CALLBACK_SEQUENCE],
                generation = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_GENERATION],
                selectionToken = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SELECTION_TOKEN],
                controlRevision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_CONTROL_REVISION],
                missingControlFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MISSING_CONTROL_FRAMES],
                staleControlFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_STALE_CONTROL_FRAMES],
                mixerRejectedFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_REJECTED_FRAMES],
                invalidTagFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_INVALID_TAG_FRAMES],
                advanceFailures = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_ADVANCE_FAILURES],
                safeOriginalFallbackFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SAFE_ORIGINAL_FALLBACK_FRAMES],
                mixerNonFiniteSamplesScrubbed = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_NON_FINITE_SAMPLES_SCRUBBED],
                mixerLimiterActiveFrames = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_LIMITER_ACTIVE_FRAMES],
                mixerLimiterSafetyClamps = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_MIXER_LIMITER_SAFETY_CLAMPS],
                finalNonFiniteSamplesScrubbed = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_FINAL_NON_FINITE_SAMPLES_SCRUBBED],
                finalSafetyClamps = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_FINAL_SAFETY_CLAMPS],
                // END GENERATED callback-mix
            ),
            loop = NativeAudioLoopDiagnostics(
                // BEGIN GENERATED output-loop
                acceptedDemandCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_ACCEPTED_DEMAND_COUNT],
                acceptedCaptureCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_ACCEPTED_CAPTURE_COUNT],
                latestDemandRevision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_DEMAND_REVISION],
                latestDemandAFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_DEMAND_AFRAME],
                latestDemandBFrameExclusive = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_DEMAND_BFRAME_EXCLUSIVE],
                latestDemandPresentedFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_DEMAND_PRESENTED_FRAME],
                latestDemandBoundaryArmed = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_ARMED, "loop demand armed"),
                latestDemandBoundaryCrossed = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_LOOP_DEMAND_LATEST_DEMAND_BOUNDARY_CROSSED, "loop demand crossed"),
                latestCaptureRevision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CAPTURE_REVISION],
                latestCaptureAFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CAPTURE_AFRAME],
                latestCaptureBFrameExclusive = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CAPTURE_BFRAME_EXCLUSIVE],
                latestCapturePresentedFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CAPTURE_PRESENTED_FRAME],
                successfulCaptureCutoverCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_SUCCESSFUL_CAPTURE_CUTOVER_COUNT],
                latestCutoverCaptureRevision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_CAPTURE_REVISION],
                latestCutoverCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_CALLBACK_SEQUENCE],
                latestCutoverInputLatencyMicros = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_INPUT_LATENCY_MICROS],
                processBoundaryMissCount = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_BOUNDARY_MISS_COUNT],
                latestMissCaptureRevision = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_MISS_CAPTURE_REVISION],
                latestMissCallbackSequence = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_MISS_CALLBACK_SEQUENCE],
                latestMissPresentedFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_MISS_PRESENTED_FRAME],
                latestDemandGestureId = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_DEMAND_GESTURE_ID],
                latestCaptureGestureId = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CAPTURE_GESTURE_ID],
                latestCaptureInferred = boolean(NativePacketSchema.OUTPUT_DIAGNOSTICS_LOOP_DEMAND_LATEST_CAPTURE_INFERRED, "loop capture inferred"),
                latestCutoverGeneration = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_GENERATION],
                latestCutoverSelectionToken = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_SELECTION_TOKEN],
                latestCutoverAFrame = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_AFRAME],
                latestCutoverGestureId = fields[NativePacketSchema.OUTPUT_DIAGNOSTICS_LATEST_CUTOVER_GESTURE_ID],
                // END GENERATED output-loop
            )
        )
    }
}

/** One exact valid, non-empty owner span consumed by the device callback. */
data class NativeAudioPresentationSpan(
    val sequence: Long,
    val generation: Long,
    val selectionToken: Long,
    val firstFingerprintSequence: Long,
    val lastFingerprintSequence: Long,
    val callbackSequence: Long,
    val pcmFrames: Long,
    val firstLoopPolicyRevision: Long,
    val lastLoopPolicyRevision: Long,
    val firstTimelineFrame: Long,
    val lastTimelineFrame: Long,
    val continuityViolationCount: Long,
    val renderSourceStartFrame: Double = 0.0,
    val renderSourceEndFrameExclusive: Double = 0.0,
    val renderFrameCount: Long = 0L,
    val firstRenderFrameOffset: Long = 0L,
    val mappingFirstRenderFrameOffset: Long = 0L,
    val actualTempo: Float = 0f,
    val lastNextTimelineFrame: Long = -1L,
    val callbackPcmFrames: Long = 0L
) {
    init {
        require(sequence > 0L && generation > 0L && selectionToken > 0L)
        require(firstFingerprintSequence > 0L &&
            lastFingerprintSequence >= firstFingerprintSequence)
        require(callbackSequence > 0L && pcmFrames > 0L)
        require(firstLoopPolicyRevision >= 0L && lastLoopPolicyRevision >= 0L)
        require(firstTimelineFrame >= 0L && lastTimelineFrame >= 0L)
        require(continuityViolationCount >= 0L && continuityViolationCount < pcmFrames)
        require(renderSourceStartFrame.isFinite() && renderSourceEndFrameExclusive.isFinite())
        require(renderFrameCount in 0L..0xffff_ffffL && firstRenderFrameOffset in 0L..0xffff_ffffL &&
            mappingFirstRenderFrameOffset in 0L..0xffff_ffffL)
        require(actualTempo.isFinite() && lastNextTimelineFrame >= -1L)
        require(callbackPcmFrames == 0L || callbackPcmFrames >= pcmFrames)
    }
}

/** Independent cursor read from the fixed native callback presentation journal. */
data class NativeAudioPresentationJournalSnapshot(
    val capacity: Int,
    val requestedAfterSequence: Long,
    val writeSequence: Long,
    val oldestAvailableSequence: Long,
    val overflowCount: Long,
    val writerCollisionCount: Long,
    val invalidPublicationCount: Long,
    val sequenceExhausted: Boolean,
    val coherent: Boolean,
    val entries: List<NativeAudioPresentationSpan>
) {
    init {
        require(capacity > 0)
        require(requestedAfterSequence >= 0L && writeSequence >= 0L)
        require(oldestAvailableSequence >= 0L && overflowCount >= 0L)
        require(writerCollisionCount >= 0L)
        require(invalidPublicationCount >= 0L)
        require(entries.size <= capacity)
    }

    /** Exact native head represented by this immutable read, after consuming all [entries]. */
    val consumedThroughSequence: Long
        get() = entries.lastOrNull()?.sequence ?: minOf(requestedAfterSequence, writeSequence)

    companion object {
        fun empty(afterSequence: Long): NativeAudioPresentationJournalSnapshot {
            require(afterSequence >= 0L)
            return NativeAudioPresentationJournalSnapshot(
                capacity = 1,
                requestedAfterSequence = afterSequence,
                writeSequence = 0L,
                oldestAvailableSequence = 0L,
                overflowCount = 0L,
                writerCollisionCount = 0L,
                invalidPublicationCount = 0L,
                sequenceExhausted = false,
                coherent = true,
                entries = emptyList()
            )
        }
    }
}

internal object NativeAudioPresentationJournalCodec {
    private const val SCHEMA_VERSION = NativePacketSchema.PRESENTATION_JOURNAL_VERSION
    private const val HEADER_FIELD_COUNT = NativePacketSchema.PRESENTATION_JOURNAL_HEADER
    private const val ENTRY_FIELD_COUNT = NativePacketSchema.PRESENTATION_JOURNAL_ENTRY

    fun decode(fields: LongArray): NativeAudioPresentationJournalSnapshot {
        require(fields.size >= HEADER_FIELD_COUNT) {
            "Expected at least $HEADER_FIELD_COUNT presentation-journal fields, " +
                "received ${fields.size}"
        }
        require(fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_SCHEMA] == SCHEMA_VERSION) {
            "Unsupported native presentation-journal schema ${fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_SCHEMA]}"
        }
        val capacity = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_CAPACITY].nativePacketInt().also { require(it > 0) }
        val requestedAfter = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_REQUESTED_AFTER].also { require(it >= 0L) }
        val writeSequence = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_WRITE_SEQUENCE].also { require(it >= 0L) }
        val oldest = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_OLDEST_SEQUENCE].also { require(it >= 0L) }
        val overflow = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_OVERFLOW_COUNT].also { require(it >= 0L) }
        val writerCollisions = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_WRITER_COLLISIONS].also { require(it >= 0L) }
        val invalidPublications = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_INVALID_PUBLICATIONS].also { require(it >= 0L) }
        val sequenceExhausted = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_SEQUENCE_EXHAUSTED].nativePacketBoolean()
        val coherent = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_COHERENT].nativePacketBoolean()
        val count = fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_ENTRY_COUNT].nativePacketInt().also { require(it in 0..capacity) }
        require(fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_ENTRY_WIDTH] == ENTRY_FIELD_COUNT.toLong()) {
            "Unsupported presentation-journal entry width ${fields[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_ENTRY_WIDTH]}"
        }
        require(fields.size.toLong() == HEADER_FIELD_COUNT.toLong() + count.toLong() * ENTRY_FIELD_COUNT) {
            "Presentation-journal count/size mismatch: count=$count fields=${fields.size}"
        }
        val entries = List(count) { index ->
            val offset = HEADER_FIELD_COUNT + index * ENTRY_FIELD_COUNT
            NativeAudioPresentationSpan(
                sequence = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_SEQUENCE],
                generation = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_GENERATION],
                selectionToken = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_TOKEN],
                firstFingerprintSequence = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_FIRST_FINGERPRINT],
                lastFingerprintSequence = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_LAST_FINGERPRINT],
                callbackSequence = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_CALLBACK_SEQUENCE],
                pcmFrames = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_PCM_FRAMES],
                firstLoopPolicyRevision = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_FIRST_LOOP_REVISION],
                lastLoopPolicyRevision = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_LAST_LOOP_REVISION],
                firstTimelineFrame = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_FIRST_TIMELINE_FRAME],
                lastTimelineFrame = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_LAST_TIMELINE_FRAME],
                continuityViolationCount = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_CONTINUITY_VIOLATIONS],
                renderSourceStartFrame = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_RENDER_SOURCE_START_BITS].nativePacketDouble(),
                renderSourceEndFrameExclusive = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_RENDER_SOURCE_END_BITS].nativePacketDouble(),
                renderFrameCount = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_RENDER_FRAME_COUNT],
                firstRenderFrameOffset = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_FIRST_RENDER_FRAME_OFFSET],
                mappingFirstRenderFrameOffset = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_MAPPING_FIRST_RENDER_FRAME_OFFSET],
                actualTempo = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_ACTUAL_TEMPO_BITS].nativePacketFloat(),
                lastNextTimelineFrame = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_LAST_NEXT_TIMELINE_FRAME],
                callbackPcmFrames = fields[offset + NativePacketSchema.PRESENTATION_JOURNAL_ENTRY_CALLBACK_PCM_FRAMES]
            )
        }
        entries.zipWithNext().forEach { (first, second) ->
            require(second.sequence == first.sequence + 1L) {
                "Presentation-journal entries are not contiguous"
            }
            require(second.callbackSequence >= first.callbackSequence) {
                "Presentation-journal callback sequence moved backward"
            }
        }
        if (entries.isNotEmpty()) {
            require(entries.first().sequence == maxOf(requestedAfter + 1L, oldest))
            require(entries.last().sequence == writeSequence)
        } else if (coherent && requestedAfter < writeSequence) {
            error("A coherent non-empty journal range returned no entries")
        }
        val expectedOldest = when {
            writeSequence == 0L -> 0L
            writeSequence > capacity.toLong() -> writeSequence - capacity.toLong() + 1L
            else -> 1L
        }
        require(oldest == expectedOldest) {
            "Invalid presentation-journal oldest sequence $oldest; expected $expectedOldest"
        }
        val expectedOverflow = if (writeSequence != 0L && requestedAfter < writeSequence) {
            (oldest - (requestedAfter + 1L)).coerceAtLeast(0L)
        } else 0L
        require(overflow == expectedOverflow) {
            "Invalid presentation-journal overflow $overflow; expected $expectedOverflow"
        }
        require((writerCollisions == 0L && invalidPublications == 0L) || !coherent) {
            "A dropped presentation-journal publication cannot be reported coherent"
        }
        return NativeAudioPresentationJournalSnapshot(
            capacity = capacity,
            requestedAfterSequence = requestedAfter,
            writeSequence = writeSequence,
            oldestAvailableSequence = oldest,
            overflowCount = overflow,
            writerCollisionCount = writerCollisions,
            invalidPublicationCount = invalidPublications,
            sequenceExhausted = sequenceExhausted,
            coherent = coherent,
            entries = entries
        )
    }
}
