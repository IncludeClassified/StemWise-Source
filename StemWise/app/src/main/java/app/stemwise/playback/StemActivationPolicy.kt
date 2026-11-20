package app.stemwise.playback

import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.NativeAudioPlaybackCommandLease
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState
import app.stemwise.playback.engine.NativeAudioPlaybackSessionLifecycle
import app.stemwise.playback.engine.PlaybackStemPackagePhase

internal fun stemActivationSettlement(
    snapshot: NativeAudioPlaybackSessionState,
    lease: NativeAudioPlaybackCommandLease,
    sourceRevision: Long,
    packageSignature: String,
    stemRevision: String
): StemActivationSettlement? {
    if (snapshot.latestRequest?.selection?.token != lease.token) {
        return StemActivationSettlement.OwnerSuperseded
    }
    when (snapshot.lifecycle) {
        NativeAudioPlaybackSessionLifecycle.RESTART_REQUIRED ->
            return StemActivationSettlement.Failed("Playback must restart before the song parts can open")
        NativeAudioPlaybackSessionLifecycle.CLOSED ->
            return StemActivationSettlement.Failed("Playback closed before the song parts could open")
        NativeAudioPlaybackSessionLifecycle.STOPPING ->
            return StemActivationSettlement.OwnerSuperseded
        NativeAudioPlaybackSessionLifecycle.ACTIVE -> Unit
    }
    val committed = snapshot.publication.committed
        ?.takeIf { it.selection.token == lease.token }
        ?: return null
    if (committed.source.desiredRevision != sourceRevision) {
        return StemActivationSettlement.OwnerSuperseded
    }
    val desiredPackage = committed.stems.desired
    if (desiredPackage?.packageSignature != packageSignature ||
        desiredPackage.stemRevision != stemRevision
    ) {
        return StemActivationSettlement.Failed(
            "The selected stem package changed before it could open"
        )
    }
    committed.issue?.takeIf { issue ->
        issue.category == "source-mode" || issue.category == "stem-package" ||
            issue.category == "output-unavailable" ||
            issue.category.startsWith("decoder-")
    }?.let { issue ->
        return StemActivationSettlement.Failed(issue.cause)
    }
    if (committed.stems.phase == PlaybackStemPackagePhase.FAILED) {
        return StemActivationSettlement.Failed(
            committed.stems.failureCause ?: "The song parts could not be prepared"
        )
    }
    if (committed.source.desiredMode != EngineSourceMode.STEMS) {
        return StemActivationSettlement.OwnerSuperseded
    }
    val installedMatches = committed.stems.installed?.let { installed ->
        installed.packageSignature == packageSignature &&
            installed.stemRevision == stemRevision
    } == true
    return StemActivationSettlement.Ready.takeIf {
        stemActivationSourceIsReady(
            source = committed.source,
            phase = committed.stems.phase,
            playWhenReady = committed.transport.playWhenReady,
            sourceRevision = sourceRevision,
            installedMatches = installedMatches
        )
    }
}
