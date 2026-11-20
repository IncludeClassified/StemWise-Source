package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshot
import app.stemwise.ui.player.nativeplayer.FullPlayerDetailState
import app.stemwise.ui.player.nativeplayer.FullPlayerSourceMixerPacket

/**
 * Snapshot-only work classification for the native player surface.
 *
 * The caller remains responsible for requiring the same projected width/height/density/direction
 * tuple before using a narrow plan. A layout-tuple change always needs the full projection even
 * when the snapshot component diff itself is narrow.
 *
 * This planner deliberately uses the same publication identities as [FullPlayerSnapshot]: stable
 * class packets must be the exact accepted objects, while data packets must be value-identical.
 * It never turns a stale, equal-revision, colliding, or multi-component publication into a fast
 * path; those updates fall back to the ordinary validating/projection path.
 */
internal sealed interface PlayerSurfaceSnapshotUpdatePlan {
    data object TransportOnly : PlayerSurfaceSnapshotUpdatePlan

    data object TimelineOnly : PlayerSurfaceSnapshotUpdatePlan

    data object FineWindowOnly : PlayerSurfaceSnapshotUpdatePlan

    data object ToolDetailOnly : PlayerSurfaceSnapshotUpdatePlan

    data class SourceMixerOnly(
        val work: PlayerSurfaceSourceMixerUpdateWork
    ) : PlayerSurfaceSnapshotUpdatePlan

    data object Full : PlayerSurfaceSnapshotUpdatePlan
}

internal enum class PlayerSurfaceSourceMixerUpdateWork {
    /**
     * Only source chrome/evidence changed while the presented mode and mixer package stayed put.
     * This includes phase, audible-mode, and availability evidence because none changes layout.
     */
    PHASE_ONLY,

    /** Volume/mute/solo state changed inside the same source and exact mixer topology. */
    MIXER_STATE_ONLY,

    /** Presented mode, package topology, or the lane visual package changed atomically. */
    PRESENTATION_OR_TOPOLOGY
}

internal object PlayerSurfaceSnapshotUpdatePlanner {
    fun classify(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): PlayerSurfaceSnapshotUpdatePlan {
        // Every narrow update requires the same owner and structural content.
        if (next.revision <= previous.revision ||
            previous.owner != next.owner ||
            previous.visualSelectionSongId != next.visualSelectionSongId ||
            previous.catalog !== next.catalog ||
            previous.queue !== next.queue ||
            previous.selection != next.selection ||
            previous.waveform !== next.waveform ||
            previous.status != next.status
        ) return PlayerSurfaceSnapshotUpdatePlan.Full

        if (sameExceptFineWindow(previous, next) && next.fineWindow !== previous.fineWindow) {
            val previousWindow = previous.fineWindow
            val nextWindow = next.fineWindow
            val validWindowTransition = nextWindow == null ||
                (nextWindow.mediaRevision == next.owner.mediaRevision &&
                    (previousWindow == null || nextWindow.revision > previousWindow.revision))
            return if (validWindowTransition) {
                PlayerSurfaceSnapshotUpdatePlan.FineWindowOnly
            } else {
                PlayerSurfaceSnapshotUpdatePlan.Full
            }
        }

        if (sameExceptTimeline(previous, next) && next.timeline != previous.timeline) {
            return if (
                next.timeline.revision > previous.timeline.revision &&
                next.timeline.durationMs == previous.timeline.durationMs &&
                next.timeline.waveformRevision == previous.timeline.waveformRevision
            ) {
                PlayerSurfaceSnapshotUpdatePlan.TimelineOnly
            } else {
                PlayerSurfaceSnapshotUpdatePlan.Full
            }
        }

        if (sameExceptToolDetail(previous, next) && next.toolDetail != previous.toolDetail) {
            return if (next.toolDetail.revision > previous.toolDetail.revision) {
                PlayerSurfaceSnapshotUpdatePlan.ToolDetailOnly
            } else {
                PlayerSurfaceSnapshotUpdatePlan.Full
            }
        }

        val transportCandidate = sameExceptTransport(previous, next) &&
            next.transport != previous.transport
        if (transportCandidate) {
            return if (next.transport.revision > previous.transport.revision) {
                PlayerSurfaceSnapshotUpdatePlan.TransportOnly
            } else {
                PlayerSurfaceSnapshotUpdatePlan.Full
            }
        }

        if (!sameExceptSourceMixer(previous, next) || next.sourceMixer === previous.sourceMixer) {
            return PlayerSurfaceSnapshotUpdatePlan.Full
        }
        return classifySourceMixer(previous.sourceMixer, next.sourceMixer)
    }

    private fun classifySourceMixer(
        previous: FullPlayerSourceMixerPacket,
        next: FullPlayerSourceMixerPacket
    ): PlayerSurfaceSnapshotUpdatePlan {
        if (
            next.revision <= previous.revision ||
            next.source.revision < previous.source.revision ||
            next.mixer.revision < previous.mixer.revision ||
            next.mixer.packageRevision < previous.mixer.packageRevision
        ) {
            return PlayerSurfaceSnapshotUpdatePlan.Full
        }

        val sourceChanged = previous.source != next.source
        val mixerChanged = !sameMixerState(previous, next)
        // Nested packets retain their own immutable revision identities even though the enclosing
        // source/mixer packet also advances. Never let its newer wrapper hide a nested collision.
        if (
            (sourceChanged && next.source.revision <= previous.source.revision) ||
            (mixerChanged && next.mixer.revision <= previous.mixer.revision)
        ) return PlayerSurfaceSnapshotUpdatePlan.Full

        val presentationOrTopologyChanged =
            previous.source.presentedMode != next.source.presentedMode ||
                !sameMixerTopologyAndVisuals(previous, next)
        if (presentationOrTopologyChanged) {
            return PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                PlayerSurfaceSourceMixerUpdateWork.PRESENTATION_OR_TOPOLOGY
            )
        }

        return when {
            sourceChanged && !mixerChanged &&
                next.source.revision > previous.source.revision ->
                PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                    PlayerSurfaceSourceMixerUpdateWork.PHASE_ONLY
                )

            !sourceChanged && mixerChanged &&
                next.mixer.revision > previous.mixer.revision ->
                PlayerSurfaceSnapshotUpdatePlan.SourceMixerOnly(
                    PlayerSurfaceSourceMixerUpdateWork.MIXER_STATE_ONLY
                )

            else -> PlayerSurfaceSnapshotUpdatePlan.Full
        }
    }

    private fun sameExceptTransport(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        previous.timeline == next.timeline &&
            previous.fineWindow === next.fineWindow &&
            previous.sourceMixer === next.sourceMixer &&
            previous.toolDetail == next.toolDetail

    private fun sameExceptFineWindow(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        previous.timeline == next.timeline &&
            previous.sourceMixer === next.sourceMixer &&
            previous.transport == next.transport &&
            previous.toolDetail == next.toolDetail

    /** Loop edits rebuild this derived packet even though no independent tool state changed. */
    private fun sameExceptTimeline(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        previous.fineWindow === next.fineWindow &&
            previous.sourceMixer === next.sourceMixer &&
            previous.transport == next.transport &&
            timelineDerivedToolDetailMatches(previous, next)

    private fun timelineDerivedToolDetailMatches(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean {
        val previousTool = previous.toolDetail
        val nextTool = next.toolDetail
        if (previousTool == nextTool) {
            if (previousTool.activity.loopActive != previous.timeline.hasCompleteLoop() ||
                nextTool.activity.loopActive != next.timeline.hasCompleteLoop()
            ) return false
            val detail = previousTool.detail
            return detail !is FullPlayerDetailState.Loop ||
                (detail.matches(previous.timeline) && detail.matches(next.timeline))
        }
        if (nextTool.revision <= previousTool.revision ||
            nextTool.selectedTool != previousTool.selectedTool ||
            nextTool.activity != previousTool.activity.copy(
                loopActive = nextTool.activity.loopActive
            ) ||
            previousTool.activity.loopActive != previous.timeline.hasCompleteLoop() ||
            nextTool.activity.loopActive != next.timeline.hasCompleteLoop()
        ) return false

        val previousDetail = previousTool.detail
        val nextDetail = nextTool.detail
        return if (previousDetail is FullPlayerDetailState.Loop) {
            nextDetail is FullPlayerDetailState.Loop &&
                previousDetail.matches(previous.timeline) &&
                nextDetail.matches(next.timeline)
        } else {
            previousDetail == nextDetail
        }
    }

    private fun PlayerSurfaceTimelinePacket.hasCompleteLoop(): Boolean =
        loopStartMs >= 0L && loopEndMs > loopStartMs

    private fun FullPlayerDetailState.Loop.matches(
        timeline: PlayerSurfaceTimelinePacket
    ): Boolean = durationMs == timeline.durationMs &&
        loopStartMs == timeline.loopStartMs.takeIf { it >= 0L } &&
        loopEndMs == timeline.loopEndMs.takeIf { it >= 0L }

    private fun sameExceptToolDetail(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        previous.timeline == next.timeline &&
            previous.fineWindow === next.fineWindow &&
            previous.sourceMixer === next.sourceMixer &&
            previous.transport == next.transport

    private fun sameExceptSourceMixer(
        previous: FullPlayerSnapshot,
        next: FullPlayerSnapshot
    ): Boolean =
        previous.timeline == next.timeline &&
            previous.fineWindow === next.fineWindow &&
            previous.transport == next.transport &&
            previous.toolDetail == next.toolDetail

    private fun sameMixerTopologyAndVisuals(
        previous: FullPlayerSourceMixerPacket,
        next: FullPlayerSourceMixerPacket
    ): Boolean {
        if (
            previous.mixer.packageRevision != next.mixer.packageRevision ||
            previous.laneCount != next.laneCount
        ) return false
        var lane = 0
        while (lane < previous.laneCount) {
            if (
                previous.mixer.laneKeyAt(lane) != next.mixer.laneKeyAt(lane) ||
                previous.laneAt(lane) != next.laneAt(lane)
            ) return false
            lane++
        }
        return true
    }

    private fun sameMixerState(
        previous: FullPlayerSourceMixerPacket,
        next: FullPlayerSourceMixerPacket
    ): Boolean {
        if (previous.mixer.laneCount != next.mixer.laneCount) return false
        var lane = 0
        while (lane < previous.mixer.laneCount) {
            if (
                previous.mixer.laneKeyAt(lane) != next.mixer.laneKeyAt(lane) ||
                previous.mixer.volumeAt(lane).toRawBits() !=
                next.mixer.volumeAt(lane).toRawBits() ||
                previous.mixer.mutedAt(lane) != next.mixer.mutedAt(lane) ||
                previous.mixer.soloAt(lane) != next.mixer.soloAt(lane)
            ) return false
            lane++
        }
        return true
    }
}
