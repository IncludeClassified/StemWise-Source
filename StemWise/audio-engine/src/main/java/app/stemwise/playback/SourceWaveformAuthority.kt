package app.stemwise.playback

import app.stemwise.playback.engine.*

/** Selects actual canonical music authority; intent cannot borrow a previous callback's shape. */
fun sourceWaveformIdentityCandidate(
    session: NativeAudioPlaybackSessionState,
    native: NativeEngineDiagnosticsSnapshot,
    pausedPrepared: PausedPreparedStemMixLease?
): SourceWaveformIdentity? {
    val committed = session.publication.committed ?: return null
    val latest = session.latestRequest ?: return null
    val owner = committed.owner.ownership
    if (latest.selection != committed.selection || latest.exactOwnership != owner ||
        native.runtime.ownership.committed != owner || native.runtime.ownership.requested != owner ||
        committed.issue != null || session.terminalFailure != null) return null
    val selection = committed.selection
    val desired = latest.controls
    val source = if (desired.sourceMode == EngineSourceMode.STEMS) NativeRuntimeSourceMode.STEMS
        else NativeRuntimeSourceMode.ORIGINAL
    val lanes = if (source == NativeRuntimeSourceMode.STEMS)
        canonicalEngineStemLanes(desired.stemLanes) ?: return null else emptyList()
    val anySolo = lanes.any { desired.stemControls[it.id]?.solo == true }
    val gains = lanes.map { lane ->
        val control = desired.stemControls[lane.id] ?: return null
        if (control.muted || anySolo && !control.solo) 0f else control.gain
    }
    if (source != NativeRuntimeSourceMode.STEMS) return null
    val preparedMatches = pausedPrepared != null && pausedPrepared.ownership == owner &&
        !desired.playWhenReady && !committed.transport.playWhenReady &&
        source == NativeRuntimeSourceMode.STEMS &&
        pausedPrepared.packageSignature == engineStemPackageSignature(lanes)
    if (!preparedMatches) {
        val callback = native.runtime.audibleControl ?: return null
        val publication = callbackControlPublication(owner, callback,
            native.controllerSourceControlPublications) ?: return null
        val newest = native.controllerSourceControlPublications.lastOrNull { it.ownership == owner }
            ?: return null
        if (publication.nativeControlRevision != newest.nativeControlRevision ||
            callback.actualSourceMode != source) return null
        val applied = publication.nativeControls ?: return null
        if (source == NativeRuntimeSourceMode.STEMS) {
            if (publication.stemPackageSignature != engineStemPackageSignature(lanes) ||
                publication.stemLaneIds != lanes.map(EngineStemLane::id)) return null
            val appliedSolo = applied.stems.take(lanes.size).any { it.soloed }
            val appliedGains = applied.stems.take(lanes.size).map {
                if (it.muted || appliedSolo && !it.soloed) 0f else it.gain
            }
            if (appliedGains != gains) return null
            val overlay = native.stemGainCallbackSettlement
            if (overlay != null && overlay.ownerToken == owner.selectionToken &&
                overlay.ownerGeneration == owner.generation && overlay.activeMask != 0 &&
                overlay.callbackSequence >= callback.callbackSequence) return null
        }
    }
    val sources = listOf(SourceWaveformSource(selection.originalLocation,
        selection.originalContentSha256 ?: selection.audioRevision, "original", false)) +
        lanes.map { lane -> SourceWaveformSource(lane.location, lane.contentSha256,
            lane.normalizedRole, app.stemwise.model.StemRoleContract.normalize(lane.normalizedRole) == "remaining") }
    return SourceWaveformIdentity(selection.songId, selection.audioRevision, selection.durationMs,
        owner, source, java.util.Collections.unmodifiableList(gains), java.util.Collections.unmodifiableList(sources))
}
