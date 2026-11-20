package app.stemwise.playback

import app.stemwise.playback.engine.NativeAudioPlaybackSeekReceipt
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState

/** Qualification only: distances are source/output frames at proved unity tempo. */
internal data class QueuedLoopBoundaryWindow(
    val minimumPrefix: Long,
    val queuedPrefix: Long,
    private val acceptedAhead: Long,
    private val audioTrack: Boolean
) {
    fun contains(framesUntilBoundary: Long): Boolean =
        (!audioTrack || acceptedAhead >= 0L) &&
            framesUntilBoundary > minimumPrefix && framesUntilBoundary <= queuedPrefix
}

internal fun queuedLoopBoundaryWindow(
    audioTrack: Boolean,
    acceptedAhead: Long,
    queuedFrames: Long,
    outputBufferFrames: Long,
    framesPerBurst: Long,
    preparedBlockFrames: Long,
    snapshotAgeFrames: Long
): QueuedLoopBoundaryWindow {
    require(snapshotAgeFrames >= 0L && preparedBlockFrames > 0L)
    // The accepted prefix excludes one immutable held block. Keep the complete 10 ms burst ahead of B.
    val minimum = if (audioTrack) {
        acceptedAhead.coerceAtLeast(outputBufferFrames) + preparedBlockFrames + 480L + snapshotAgeFrames
    } else outputBufferFrames.coerceAtLeast(framesPerBurst)
    // Ring and accepted counters are separate reads; reserve one moving block and observation age.
    val queued = if (audioTrack) {
        acceptedAhead + queuedFrames - preparedBlockFrames - snapshotAgeFrames
    } else queuedFrames
    return QueuedLoopBoundaryWindow(minimum, queued, acceptedAhead, audioTrack)
}

/** A requested position or a newer generation cannot acknowledge this exact seek. */
internal fun seekReceiptIsCompleted(
    receipt: NativeAudioPlaybackSeekReceipt,
    session: NativeAudioPlaybackSessionState
): Boolean {
    val latest = session.latestRequest ?: return false
    val committed = session.publication.committed ?: return false
    return latest.selection.token == receipt.token && latest.controls.seekRevision == receipt.seekRevision &&
        committed.selection.token == receipt.token && committed.transport.completedSeekRevision == receipt.seekRevision
}
