package app.stemwise.playback.engine

data class NativeRuntimeCanonicalPcmIoDiagnostics(
    val ownership: NativeRuntimeOwnership,
    val songIdentityHash: Long,
    val originalContentHash: Long,
    val stemPackageHash: Long,
    val readOperations: Long,
    val readBytes: Long,
    val lastReadByteOffset: Long,
    val lastReadBytes: Long,
    val fileBytes: Long,
    val reservedBytes: Long,
    val peakFileBytes: Long,
    val admittedLaneCount: Long,
    val admittedFrameUpperBound: Long,
    val lastAdmissionStatus: NativeCacheStorageStatus
)

enum class NativeRuntimeEndOrderingKind {
    COMMAND_PUBLISHED, COMMAND_CONSUMED, PIPELINE_END_OBSERVED,
    OUTPUT_PLAYING_APPLIED, PHYSICAL_DRAIN_OBSERVED, AUDIBLE_END_PUBLISHED
}

data class NativeRuntimeEndOrderingEvent(
    val kind: NativeRuntimeEndOrderingKind,
    val sequence: Long,
    val workerStateSequence: Long,
    val commandRevision: Long,
    val ownership: NativeRuntimeOwnership?,
    val desiredPlaying: Boolean,
    val outputPlaying: Boolean,
    val pipelineEndObserved: Boolean,
    val commandUnread: Boolean,
    val pendingOutputFrames: Long,
    val queuedOutputFrames: Long,
    val terminalPositionValid: Boolean,
    val terminalAfterFrame: Boolean
)

/** Latest per-kind observations share one native clock; omitted intermediate events are unknown. */
data class NativeRuntimeEndOrderingDiagnostics(val events: List<NativeRuntimeEndOrderingEvent>) {
    init {
        require(events.size <= NativeRuntimeEndOrderingKind.entries.size)
        require(events.map { it.kind }.distinct().size == events.size)
        require(events.all { it.sequence > 0L })
        require(events.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
    }
    val completeHistory: Boolean get() = false
    val missingKinds: Set<NativeRuntimeEndOrderingKind>
        get() = NativeRuntimeEndOrderingKind.entries.toSet() - events.map { it.kind }.toSet()
}
