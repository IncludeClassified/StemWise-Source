package app.stemwise.playback.engine

/** Actor-owned observation lifecycle; no independent sampler or playback authority. */
internal class NativeControlProgressObserver {
    private sealed interface Details {
        data object Empty : Details
        data class Missing(val refreshAtMs: Long) : Details
        data class Observed(val value: NativeRuntimeRenderReadinessDiagnostics, val refreshAtMs: Long) : Details
    }
    private var details: Details = Details.Empty
    val readiness: NativeRuntimeRenderReadinessDiagnostics?
        get() = (details as? Details.Observed)?.value
    private var presentationSequence = 0L
    private var positionSequence = 0L
    private var endSequence = 0L
    var audibleControlSequence = 0L
        private set

    fun invalidateReadiness() { details = Details.Empty }
    fun resetEndSequence() { endSequence = 0L }
    fun needsReadiness(progress: NativeRuntimeControlObservation, nowMs: Long, urgent: Boolean,
        owner: NativeRuntimeOwnership?, ingressEpoch: Long): Boolean {
        val summary = progress.readinessProgress
        when (val current = details) {
            Details.Empty -> return true
            is Details.Missing -> return urgent || (summary?.sequence ?: 0L) != 0L || nowMs >= current.refreshAtMs
            is Details.Observed -> Unit
        }
        val observed = details as Details.Observed
        val changedContract = summary != null && summary.sequence != 0L &&
            (summary.ownership != observed.value.ownership || summary.controlRevision != observed.value.controlRevision ||
                summary.cacheBound != observed.value.currentSongCacheBoundToSelectedOwner ||
                summary.cacheCanGrow != observed.value.currentSongCacheCanGrow ||
                summary.completeLaneMask != observed.value.currentSongCacheCompleteLaneMask ||
                summary.storageStatus != observed.value.cacheStorageStatus ||
                summary.requiredLaneMask != observed.value.requiredSongLaneMask ||
                summary.fixedLoopHeadPrepared != observed.value.fixedLoopHeadPrepared ||
                summary.loopPreparationFailure != observed.value.loopPreparationFailure ||
                summary.directAtomicPolicyGeneration != observed.value.directAtomicPolicyGeneration)
        val exactFailure = summary?.ownership == owner && summary?.failureGeneration == ingressEpoch && ingressEpoch != 0L
        return urgent || changedContract || exactFailure ||
            (nowMs >= observed.refreshAtMs && (summary == null || summary.sequence != observed.value.sequence))
    }
    fun observe(value: NativeRuntimeRenderReadinessDiagnostics?, nowMs: Long) {
        if (value == null) details = Details.Missing(nowMs + 250L)
        else if (value.sequence >= (readiness?.sequence ?: 0L)) details = Details.Observed(value, nowMs + 250L)
    }
    fun admitPresentation(sequence: Long): Boolean = (sequence > presentationSequence).also { if (it) presentationSequence = sequence }
    fun admitPosition(sequence: Long): Boolean = (sequence > positionSequence).also { if (it) positionSequence = sequence }
    fun admitEnd(sequence: Long): Boolean = (sequence > endSequence).also { if (it) endSequence = sequence }
    fun observeAudibleControl(sequence: Long) { audibleControlSequence = maxOf(audibleControlSequence, sequence) }
}
