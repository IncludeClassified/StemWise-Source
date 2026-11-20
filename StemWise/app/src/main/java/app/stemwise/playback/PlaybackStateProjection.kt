package app.stemwise.playback

import android.media.AudioManager
import androidx.media3.common.Player
import app.stemwise.analysis.MeterSegment
import app.stemwise.playback.engine.EngineMeterSegment
import app.stemwise.playback.engine.EngineSelectionToken
import app.stemwise.playback.engine.EngineSourceMode
import app.stemwise.playback.engine.NativeRuntimeOwnership
import app.stemwise.playback.engine.PlaybackCommittedPublication
import app.stemwise.playback.engine.PlaybackPublicationSourceState
import app.stemwise.playback.engine.PlaybackStemPackagePhase
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

internal sealed interface StemActivationSettlement {
    data object Ready : StemActivationSettlement
    data object OwnerSuperseded : StemActivationSettlement
    data class Failed(val cause: String) : StemActivationSettlement
}

internal suspend fun awaitStemActivationTerminalSettlement(
    settlements: Flow<StemActivationSettlement?>
): StemActivationSettlement = requireNotNull(settlements.first { it != null })

internal fun completeStemActivation(
    settlement: StemActivationSettlement,
    rollbackExplicitFailure: () -> Unit
): Result<Unit> = when (settlement) {
    StemActivationSettlement.Ready -> Result.success(Unit)
    // Selection supersession is successful cancellation, not a user-visible playback failure.
    StemActivationSettlement.OwnerSuperseded -> Result.success(Unit)
    is StemActivationSettlement.Failed -> {
        rollbackExplicitFailure()
        Result.failure(IllegalStateException(settlement.cause))
    }
}

/** Only an unverified reopening emission is transient; a completed empty library retires its owner. */
internal fun emptyPlaybackQueueIsTransient(
    hasCurrentOwner: Boolean,
    explicitUnloadPending: Boolean,
    snapshotReady: Boolean
): Boolean = !snapshotReady && hasCurrentOwner && !explicitUnloadPending

/** Preserves contract-valid meter gaps; invalid/overlapping entries are omitted, never stretched. */
internal fun projectEngineMeterSegments(
    segments: List<MeterSegment>
): List<EngineMeterSegment> = buildList {
    segments
        .filter { segment ->
            segment.startMs >= 0L && segment.endMs > segment.startMs &&
                segment.numerator in 1..32 &&
                segment.denominator in setOf(1, 2, 4, 8, 16, 32)
        }
        .distinctBy(MeterSegment::startMs)
        .sortedBy(MeterSegment::startMs)
        .forEach { segment ->
            if (lastOrNull()?.endMs?.let { previousEnd -> segment.startMs < previousEnd } == true) {
                return@forEach
            }
            add(
                EngineMeterSegment(
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    numerator = segment.numerator,
                    denominator = segment.denominator
                )
            )
        }
}


/**
 * Adapter publications must not retain a transient focus marker after the controller-owned focus
 * gate has reopened. Retaining it can combine a newly audible native publication with the previous
 * suppressed public state and terminate MediaSession observation.
 */
internal fun projectedPlaybackSuppression(
    focusSuspended: Boolean,
    previousSuppression: AudioPlaybackSuppression
): AudioPlaybackSuppression = when {
    focusSuspended -> AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
    previousSuppression == AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS ->
        AudioPlaybackSuppression.NONE
    else -> previousSuppression
}

/** One atomic public/session transport predicate; a suppressed state is never playing. */
internal fun projectedPlaybackIsPlaying(
    playWhenReady: Boolean,
    awaitingFocusResumeProof: Boolean,
    ended: Boolean,
    error: String?,
    suppression: AudioPlaybackSuppression
): Boolean = playWhenReady &&
    !awaitingFocusResumeProof &&
    !ended &&
    error == null &&
    suppression == AudioPlaybackSuppression.NONE

internal data class TokenBoundTerminalSeek(
    val ownerToken: EngineSelectionToken,
    val positionMs: Long
)

internal enum class TerminalSeekResolution {
    WAIT_FOR_OWNER,
    EXECUTE,
    CANCEL
}

/** A terminal seek belongs to one immutable selection and can never migrate to a newer request. */
internal fun terminalSeekResolution(
    request: TokenBoundTerminalSeek,
    latestRequestedToken: EngineSelectionToken?,
    committedToken: EngineSelectionToken?
): TerminalSeekResolution = when {
    latestRequestedToken != request.ownerToken -> TerminalSeekResolution.CANCEL
    committedToken == request.ownerToken -> TerminalSeekResolution.EXECUTE
    else -> TerminalSeekResolution.WAIT_FOR_OWNER
}

/** Automatic-end policy requires one exact current owner and no unresolved newer selection. */
internal fun automaticEndOwnerIsCurrent(
    selectionResolutionPending: Boolean,
    committedToken: EngineSelectionToken?,
    latestRequestedToken: EngineSelectionToken?,
    requestedOwnerToken: EngineSelectionToken
): Boolean = !selectionResolutionPending &&
    committedToken == requestedOwnerToken &&
    latestRequestedToken == requestedOwnerToken

/** Queue identity must still describe the same committed owner used to decide automatic end. */
internal fun automaticEndQueueOwnerIsCurrent(
    queueCurrentSongId: String?,
    committedSongId: String?
): Boolean = queueCurrentSongId != null && queueCurrentSongId == committedSongId

/**
 * A scrub owns every native generation published for its immutable selection token.
 *
 * The main-thread snapshot may still describe generation G1 when the adapter has already
 * committed same-token generation G2. Token-wide suppression while the gesture is active closes
 * that delivery race; after the terminal mutation only the exact ownership receipt is retained.
 */
internal fun scrubEndIsSuppressed(
    activeScrubToken: EngineSelectionToken?,
    retainedToken: EngineSelectionToken?,
    retainedOwnership: NativeRuntimeOwnership?,
    effectToken: EngineSelectionToken,
    effectOwnership: NativeRuntimeOwnership
): Boolean = activeScrubToken == effectToken ||
    (retainedToken == effectToken && retainedOwnership == effectOwnership)

/**
 * Distinguishes AudioManager's registration inventory from a physical output-topology change.
 *
 * Android publishes the complete connected-device list through onAudioDevicesAdded immediately
 * after callback registration. The native runtime is created against a topology sampled immediately
 * before that registration, so rebuilding for an identical inventory only interrupts startup. Each
 * later callback is reconciled against the complete topology because callback arrays are deltas.
 * An unavailable/empty query fails safe without discarding the last usable topology.
 */

internal const val SCRUB_ENGINE_PREVIEW_MIN_INTERVAL_MS = 64L

internal fun scrubPreviewDispatchAllowed(
    previousDispatchAtMs: Long?,
    candidateDispatchAtMs: Long,
    minimumIntervalMs: Long = SCRUB_ENGINE_PREVIEW_MIN_INTERVAL_MS
): Boolean {
    if (previousDispatchAtMs == null) return true
    if (candidateDispatchAtMs < previousDispatchAtMs) return true
    return candidateDispatchAtMs - previousDispatchAtMs >= minimumIntervalMs.coerceAtLeast(1L)
}

/** A scrub preview/terminal command may affect only the exact still-current playback owner. */
internal fun scrubOwnerMatches(
    scrubOwnerToken: Long,
    scrubSongId: String,
    latestOwnerToken: Long?,
    committedOwnerToken: Long?,
    visibleSongId: String?
): Boolean = scrubOwnerToken == latestOwnerToken &&
    scrubOwnerToken == committedOwnerToken &&
    scrubSongId == visibleSongId

/** Native preview never reaches exclusive EOS; only the exact terminal commit owns navigation. */
internal fun nativeScrubPreviewTarget(
    requestedPositionMs: Long,
    durationMs: Long
): Long {
    val lastPlayableMs = (durationMs.coerceAtLeast(1L) - 1L)
    return playbackControlPositionMs(requestedPositionMs.toDouble(), durationMs)
        .coerceAtMost(lastPlayableMs)
}

/**
 * The native engine accepts only a complete A-B range, while the UI must retain a freshly chosen
 * A marker until B is chosen. A same-owner structural publication with no engine loop therefore
 * preserves only that exact incomplete state; clears and owner changes still remain authoritative.
 */
internal fun retainedIncompleteLoopStart(
    previousA: Long?,
    previousB: Long?,
    ownerChanged: Boolean
): Long? = previousA.takeIf { !ownerChanged && previousB == null }

/** Repeat Off at the terminal queue boundary retains the exact exclusive EOS position. */
internal fun endedAtDurationState(
    state: AudioPlaybackState,
    durationMs: Long
): AudioPlaybackState = state.copy(
    positionMs = durationMs,
    playing = false,
    playWhenReady = false,
    ended = true
)

/** Only completion from the same logical owner can retire its admitted seek. */
internal fun nativeSeekRevisionAcknowledged(
    requestedOwnerToken: Long,
    presentedOwnerToken: Long,
    requestedRevision: Long,
    completedRevision: Long
): Boolean = requestedOwnerToken == presentedOwnerToken && requestedRevision > 0L &&
    completedRevision >= requestedRevision

/** Only a terminal owner restarts when its source changes. */
internal fun sourceSwitchRequiresRewind(
    ended: Boolean
): Boolean = ended
