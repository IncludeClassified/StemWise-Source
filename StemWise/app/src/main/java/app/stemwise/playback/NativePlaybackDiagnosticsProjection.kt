package app.stemwise.playback

/** Current native output counters and final PCM evidence; frequency is not synthesized. */
internal data class NativePlaybackDiagnostics(
    val renderedFrames: Long,
    val discontinuities: Long,
    val invalidSamples: Long,
    val requestedTransportPositionMs: Long = 0L,
    val requestedLoopStartMs: Long? = null,
    val requestedLoopEndMs: Long? = null,
    val requestedTempo: Float = 1f,
    val requestedSemitoneShift: Int = 0,
    val appliedTempo: Float? = null,
    val appliedPitchRatio: Float? = null,
    val appliedControlRevision: Long = 0L,
    val appliedStemGains: Map<String, Float> = emptyMap(),
    val stemControlRevision: Long = 0L,
    val requestedStemGains: Map<String, Float> = emptyMap(),
    val outputFingerprintSequence: Long = 0L,
    val outputFingerprintFrames: Int = 0,
    val outputSampleRateHz: Int = 0,
    val outputRms: Float = 0f,
    val outputPeak: Float = 0f,
    val requestedAudibleOwnershipEpoch: Long = 0L,
    val appliedAudibleOwnershipEpoch: Long = 0L,
    val audibleOwnershipMarkerSequence: Long = 0L,
    val latestAudibleOwnershipFingerprint: PcmOutputFingerprint? = null,
    /** Raw native final-pipeline FNV-1a bit pattern. */
    val renderPayloadHash: Long = 0L
)

internal data class PcmOutputFingerprint(
    val sequence: Long = 0L,
    val frames: Int = 0,
    val sampleRateHz: Int = 0,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val audibleOwnershipEpoch: Long = 0L
)

/** Numerical DSP application belongs to queued PCM, independently of late source/gain controls. */
internal fun callbackTuningEvidence(
    owner: app.stemwise.playback.engine.NativeRuntimeOwnership?,
    evidence: app.stemwise.playback.engine.NativeRuntimeAudibleControlEvidence?
): app.stemwise.playback.engine.NativeRuntimeAudibleControlEvidence? =
    evidence?.takeIf { it.ownership == owner && it.actualTempo > 0f }
