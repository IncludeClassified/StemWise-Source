package app.stemwise.playback


import androidx.media3.common.C
import app.stemwise.data.StemAssetEntity
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.engine.EngineSelection
import app.stemwise.playback.engine.EngineSelectionToken
import app.stemwise.playback.engine.EngineStemLane
import app.stemwise.playback.engine.NativeRuntimeOwnership

/** One user-facing skip interval shared by UI gestures and every Android media surface. */
internal const val STANDARD_SEEK_INCREMENT_MS = 5_000L

enum class PlaybackNoticeCategory { SONG_SELECTION, LOOP }

data class PlaybackNotice(
    val title: String,
    val message: String,
    val category: PlaybackNoticeCategory
)

internal fun songSelectionNotice(cause: Throwable? = null): PlaybackNotice? =
    if (cause is kotlinx.coroutines.CancellationException) null else PlaybackNotice(
    title = "Song unavailable",
    message = if (cause is SecurityException) {
        "Access to this song was revoked. Grant access again or reimport the song."
    } else {
        "This song's Original audio could not be opened. Check its storage access or reimport the song."
    },
    category = PlaybackNoticeCategory.SONG_SELECTION
)

internal fun unresolvedSelectionState(
    current: AudioPlaybackState,
    notice: PlaybackNotice?
): AudioPlaybackState = current.copy(loading = false, error = null, notice = notice)

data class AudioPlaybackState(
    val songId: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val ended: Boolean = false,
    val error: String? = null,
    /** User-visible recoverable notice that must not falsify audible transport/session state. */
    val notice: PlaybackNotice? = null,
    val suppression: AudioPlaybackSuppression = AudioPlaybackSuppression.NONE,
    val sourceMode: PlaybackSourceMode = PlaybackSourceMode.ORIGINAL,
    val sourceSwitchState: SourceSwitchState = SourceSwitchState.Original,
    val tempo: Float = 1f,
    val semitoneShift: Int = 0,
    val loopA: Long? = null,
    val loopB: Long? = null,
    /** Exact selected-owner proof that the native cache contains every declared music lane. */
    val loopAvailable: Boolean = false,
    val loopPreparation: LoopPreparationState = LoopPreparationState(),
    val metronomeEnabled: Boolean = false,
    val metronomeSongSynced: Boolean = false,
    val metronomeBpm: Float = 120f,
    val metronomeVolume: Float = 1f,
    val metronomeSubdivision: Int = 1,
    val metronomeNumerator: Int = 4,
    val metronomeDenominator: Int = 4,
    val stemMix: Map<String, StemMixState> = emptyMap(),
    /** Canonical source-presentation package: exact paused-prepared or audibly installed. */
    val loadedStemSetId: String? = null,
    val loadedStemSignature: String? = null,
    val loadedStemAssets: List<StemAssetEntity> = emptyList(),
    val repeatMode: String = "Off",
    val shuffleEnabled: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
    val queueOrder: List<String> = emptyList(),
    /** Accepted queue intent; it may lead callback-owned songId during a decoder handoff. */
    val currentQueueSongId: String? = null,
    /** Original source clock is retained for editing; public transport starts at the saved trim. */
    val sourceDurationMs: Long = durationMs,
    val trimStartMs: Long = 0L,
    /** Exact native selection owning these projected controls; song IDs can be selected again. */
    val selectionToken: Long? = null
)

internal val EngineSelection.playbackDurationMs: Long
    get() = playbackEndMs - playbackStartMs

internal fun EngineSelection.sourcePositionMs(positionMs: Long): Long =
    playbackStartMs + positionMs.coerceIn(0L, playbackDurationMs)

internal fun EngineSelection.publicPositionMs(sourcePositionMs: Long): Long =
    (sourcePositionMs - playbackStartMs).coerceIn(0L, playbackDurationMs)

data class AudioSessionState(
    val songId: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val repeatMode: String = "Off",
    val shuffleEnabled: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false
)

enum class PlaybackSourceMode { ORIGINAL, STEMS }

enum class AudioPlaybackSuppression {
    NONE,
    TRANSIENT_AUDIO_FOCUS_LOSS,
    UNSUITABLE_AUDIO_ROUTE,
    UNSUITABLE_AUDIO_OUTPUT,
    SCRUBBING
}

sealed interface SourceSwitchState {
    data object Original : SourceSwitchState
    data class PreparingStems(val targetSetId: String?) : SourceSwitchState
    data object ReturningOriginal : SourceSwitchState
    data class Stems(val activeSetId: String?) : SourceSwitchState
    data class Failed(
        val retainedSource: PlaybackSourceMode,
        val message: String
    ) : SourceSwitchState
}

data class StemMixState(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false
)

internal data class StemPreparationDiagnostics(
    val currentPrewarmBuildCount: Long,
    val foregroundBuildCount: Long,
    val joinedPrewarmCount: Long,
    val lastActivationTotalMs: Long
)

internal data class PlaybackResourceDiagnostics(
    val queueSongCount: Int,
    /** False means the native evidence snapshot was unavailable; zeroes must not count as proof. */
    val evidenceAvailable: Boolean,
    /** Process-cumulative construction evidence exposes replacement churn or past duplication. */
    val nativeRuntimeConstructionCount: Long,
    /** Actual process-global live native-runtime count, not a Kotlin-wrapper inference. */
    val nativeRuntimeCount: Int,
    /** Process-cumulative construction evidence for the sole native AudioTrack output owner. */
    val nativeOutputConstructionCount: Long,
    /** Actual process-global live native output-owner count. */
    val nativeOutputStreamCount: Int,
    val assignedNativeBankCount: Int,
    val activeDecoderLeaseCount: Int,
    val candidateDecoderLeaseCount: Int,
    val loopPreparationDecoderLeaseCount: Int,
    val totalDecoderLeaseCount: Int,
    val decoderWorkerRunningCount: Int,
    val decoderWorkerQueuedCount: Int,
    val cumulativeBankBeginSongCount: Long
)

/** Read-only latest-wins evidence for adversarial manual-selection instrumentation. */
internal data class SelectionPressureDiagnostics(
    val requestSerial: Long,
    val resolvingRequestSerial: Long?,
    val queueCurrentId: String?,
    val latestRequestedSongId: String?,
    val latestRequestedToken: Long?,
    val committedSongId: String?,
    val committedToken: Long?,
    val pendingSongId: String?,
    val pendingToken: Long?
) {
    val pendingSelectionCount: Int get() = if (pendingToken == null) 0 else 1
}

internal fun playableHandoffPosition(positionMs: Long, durationMs: Long): Long {
    if (durationMs <= 0L || durationMs == C.TIME_UNSET) return 0L
    return playbackControlPositionMs(positionMs.toDouble(), durationMs).takeIf { it < durationMs } ?: 0L
}

internal fun updatedStemMixState(
    current: Map<String, StemMixState>,
    stemId: String,
    volume: Float,
    muted: Boolean,
    solo: Boolean
): Map<String, StemMixState> {
    if (stemId !in current) return current
    return current + (
        stemId to StemMixState(
            volume = volume.coerceIn(0f, 1f),
            muted = muted,
            solo = solo
        )
    )
}

internal fun sameAudioSelection(
    selection: EngineSelection?,
    songId: String,
    audioRevision: String,
    originalLocation: String,
    durationMs: Long,
    originalContentSha256: String? = null
): Boolean = selection != null &&
    selection.songId == songId &&
    selection.audioRevision == audioRevision &&
    selection.originalLocation == originalLocation &&
    selection.durationMs == durationMs &&
    selection.originalContentSha256 == originalContentSha256

/**
 * An end callback may drive queue policy only while it still belongs to both the audible owner
 * and the newest user request. This keeps an outgoing A end from skipping B while B is pending.
 */
internal fun audibleEndMayNavigate(
    committedToken: EngineSelectionToken?,
    latestRequestedToken: EngineSelectionToken?,
    committedOwnership: NativeRuntimeOwnership?,
    effectToken: EngineSelectionToken,
    effectOwnership: NativeRuntimeOwnership
): Boolean = committedToken == effectToken &&
    latestRequestedToken == committedToken &&
    committedOwnership == effectOwnership

/** Main-delivered effects must recheck the adapter, whose state can advance while they are queued. */
internal fun dispatchAudibleEnd(
    snapshot: app.stemwise.playback.engine.NativeAudioPlaybackSessionState,
    effect: app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect.AudibleEnd,
    rewindForSourceChange: (app.stemwise.playback.engine.PlaybackCommittedPublication) -> Boolean,
    onEnd: (app.stemwise.playback.engine.PlaybackCommittedPublication) -> Unit
) {
    val committed = snapshot.publication.committed ?: return
    if (!audibleEndMayNavigate(
            committedToken = committed.selection.token,
            latestRequestedToken = snapshot.latestRequest?.selection?.token,
            committedOwnership = committed.owner.ownership,
            effectToken = effect.token,
            effectOwnership = effect.ownership
        )) return
    // Accepted seeks retain this native owner until their exact completion publication.
    if (checkNotNull(snapshot.latestRequest).controls.seekRevision > committed.transport.completedSeekRevision) return
    if (effect.sourceChangePending && rewindForSourceChange(committed)) return
    onEnd(committed)
}

internal enum class SelectionFocusAction { REQUEST_FOR_AUTOPLAY, RETAIN_THROUGH_PENDING }

/** A paused manual target never starts output; the facade releases focus after invalidation. */
internal fun selectionFocusAction(autoplay: Boolean): SelectionFocusAction = if (autoplay) {
    SelectionFocusAction.REQUEST_FOR_AUTOPLAY
} else {
    SelectionFocusAction.RETAIN_THROUGH_PENDING
}

internal fun ownerCommitShouldAbandonFocus(playWhenReady: Boolean): Boolean = !playWhenReady

/** A failed autoplay owner may hand its already-held focus directly to an immediate queue skip. */
internal fun failedSelectionMayHandOffFocus(
    hasCommittedOwner: Boolean,
    latestTransportIntent: Boolean?,
    audioFocusHeld: Boolean
): Boolean = !hasCommittedOwner && latestTransportIntent == true && audioFocusHeld

/** Adapter-owned failures are untagged; a manual failure may mutate only its exact facade request. */
internal fun selectionFailureBelongsToLatestRequest(
    failedRequestSerial: Long,
    latestRequestSerial: Long
): Boolean = failedRequestSerial == 0L || failedRequestSerial == latestRequestSerial

/** Loop rejection is a recoverable control result, not a transport or owner failure. */
internal fun loopRejectedState(
    current: AudioPlaybackState,
    cause: String
): AudioPlaybackState = current.copy(
    loopA = null,
    loopB = null,
    notice = PlaybackNotice(
        title = "Loop unavailable",
        message = cause.takeIf(String::isNotBlank)
            ?: "The loop was disabled because it was not ready.",
        category = PlaybackNoticeCategory.LOOP
    )
)

internal data class SessionMediaIdentity(
    val ownerToken: Long,
    val metadataRevision: Long
)

/** Small identity cache: position and transport changes cannot rebuild immutable media metadata. */
internal class SessionMediaItemCache<T : Any> {
    private var identity: SessionMediaIdentity? = null
    private var item: T? = null

    fun getOrCreate(identity: SessionMediaIdentity, create: () -> T): T {
        if (this.identity == identity) return requireNotNull(item)
        return create().also { created ->
            this.identity = identity
            item = created
        }
    }

    fun clear() {
        identity = null
        item = null
    }
}

/**
 * Captures endpoint-edit activation provenance at the command boundary.
 *
 * A complete range committed while transport is strictly outside [A, B] is dormant until real
 * range entry. Exact B is the activation boundary: touching it must lock the edited range now.
 */
internal fun editedLoopShouldParkUntilRangeEntry(
    nextRange: LoopRange?,
    transportPositionMs: Long
): Boolean = nextRange != null &&
    (transportPositionMs < nextRange.startMs || transportPositionMs > nextRange.endMs)

/**
 * Defensive normalization for low-frequency loop commands.
 *
 * The UI disables B until A exists, but accessibility, stale callbacks, and future clients can
 * still deliver a B-only command. Treat that first boundary as A instead of clearing an existing
 * loop or publishing an impossible B-without-A state. A crossed/too-short second boundary remains
 * an A-only draft and is never silently swapped into a surprising range.
 */
internal data class NormalizedLoopDraft(
    val pointA: Long?,
    val pointB: Long?
)

internal fun normalizedLoopDraft(
    pointA: Long?,
    pointB: Long?,
    durationMs: Long
): NormalizedLoopDraft {
    val duration = durationMs.coerceAtLeast(0L)
    return when {
        pointA != null -> NormalizedLoopDraft(
            pointA = playbackControlPositionMs(pointA.toDouble(), duration),
            pointB = pointB?.let { playbackControlPositionMs(it.toDouble(), duration) }
        )
        pointB != null -> NormalizedLoopDraft(
            pointA = playbackControlPositionMs(pointB.toDouble(), duration),
            pointB = null
        )
        else -> NormalizedLoopDraft(pointA = null, pointB = null)
    }
}
