package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import app.stemwise.model.Song
import app.stemwise.model.StemSource
import app.stemwise.playback.AudioPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun PlayerSourceCatalogueEffect(
    request: String?,
    attempt: Int,
    loadSources: suspend () -> List<StemSource>,
    isCurrentRequest: (String, Int) -> Boolean,
    onLoaded: (List<StemSource>, Pair<String, Int>) -> Unit,
    onFailed: (Pair<String, Int>) -> Unit
) {
    val latestLoadStemSources by rememberUpdatedState(loadSources)
    LaunchedEffect(request, attempt) {
        val targetId = request ?: return@LaunchedEffect
        val loadToken = targetId to attempt
        try {
            val sources = latestLoadStemSources()
            if (isCurrentRequest(targetId, loadToken.second)) {
                onLoaded(sources, loadToken)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrentRequest(targetId, loadToken.second)) {
                onFailed(loadToken)
            }
        }
    }
}

/** Parent/audio publication and native pager settlement share the same explicit-route owner. */
@Composable
internal fun PlayerSelectionSynchronization(
    song: Song,
    activeSongId: String,
    songs: List<Song>,
    availableSongIds: List<String>,
    audioLoading: Boolean,
    audioSongId: String?,
    audioPlayback: AudioPlayback,
    queueProjection: PlayerQueueProjection,
    trackState: PlayerVisualOwnerState,
    explicitTransport: ExplicitTransportMotionState,
    pagerSyncTarget: MutableState<String?>,
    nativeSemanticSongIdState: MutableState<String>,
    latestRequestedSongId: State<String>,
    settingsRevision: Long,
    settingsReady: (Song) -> Boolean,
    onCancelStemActivation: () -> Unit,
    onRequestedSongChanged: (Song) -> Unit
) {
    LaunchedEffect(song, availableSongIds, audioLoading, settingsRevision) {
        // A parent/session owner can arrive in the same apply cycle as a 3 -> 400 library/order
        // replacement. SideEffect refreshes the imperative projection too late for this coroutine
        // in that race; an unresolved lookup would update content ownership but leave the bounded
        // visual track on its old center forever. Refresh both halves of the transaction before
        // resolving the stable ID so every committed owner gets one idempotent visual snap.
        queueProjection.update(
            availableSongs = songs,
            queueOrder = audioPlayback.currentStateSnapshot().queueOrder
        )
        trackState.updateOrder(queueProjection.orderedSongIds)
        val desiredTransportId = explicitTransport.cursor.desiredSongId
        if (desiredTransportId != null && desiredTransportId !in queueProjection.orderedSongPageById) {
            explicitTransport.pagerJob?.cancel()
            explicitTransport.pagerJob = null
            explicitTransport.cursor.abandon()
            if (pagerSyncTarget.value == desiredTransportId) pagerSyncTarget.value = null
        } else if (desiredTransportId != null && song.id != desiredTransportId) {
            // An old committed owner can be published while the newest local selection is still
            // loading. It must not rewind either the visible ID or its pager route. A settled
            // different owner, however, is authoritative (external command or failed request).
            // A prewarmed selection can commit before the parent applies onSongChanged. In that
            // one-turn race loading is already false, but the committed audio ID proves that the
            // desired pager route won; treating the still-old parent song as authoritative would
            // snap artwork back while the new audio continues playing.
            if (explicitTransportStillAwaitingParent(
                    desiredSongId = desiredTransportId,
                    parentSongId = song.id,
                    audioLoading = audioLoading,
                    committedAudioSongId = audioSongId
                )
            ) {
                return@LaunchedEffect
            }
            explicitTransport.pagerJob?.cancel()
            explicitTransport.pagerJob = null
            explicitTransport.cursor.abandon()
            if (pagerSyncTarget.value == desiredTransportId) pagerSyncTarget.value = null
        }
        if (!settingsReady(song)) {
            onRequestedSongChanged(song)
            return@LaunchedEffect
        }
        // This parent/session song is authoritative only after the newer explicit-route checks
        // above. Reconcile external controls and natural Next here without ever rewinding a local
        // accepted pager target that is still waiting for its matching parent publication.
        nativeSemanticSongIdState.value = song.id
        if (activeSongId != song.id) onCancelStemActivation()
        val target = queueProjection.orderedSongPageById[song.id] ?: -1
        if (target >= 0 && trackState.settledSongId != song.id) {
            // A library/audio-session selection is already authoritative. Moving through a
            // large pager with animateScrollToPage exposes unrelated artwork; if a queue
            // refresh cancels that animation, an intermediate page can then be mistaken for
            // a user swipe. Hold the stable-ID guard and snap directly to the owner instead.
            pagerSyncTarget.value = song.id
            onRequestedSongChanged(song)
            val explicitRouteOwnsTarget =
                explicitTransport.cursor.desiredSongId == song.id &&
                    explicitTransport.pagerJob?.isActive == true
            if (!explicitRouteOwnsTarget) trackState.snapToSong(song.id)
            val settledSongId = trackState.settledSongId
            if (
                pagerSyncTarget.value == song.id &&
                pagerSyncTargetAfterSettling(song.id, settledSongId) == null
            ) {
                pagerSyncTarget.value = null
                if (explicitTransport.cursor.settle(song.id)) {
                    explicitTransport.pagerJob = null
                }
            }
        } else {
            onRequestedSongChanged(song)
            if (
                pagerSyncTarget.value == song.id &&
                pagerSyncTargetAfterSettling(
                    programmaticTargetId = song.id,
                    settledSongId = trackState.settledSongId
                ) == null
            ) {
                pagerSyncTarget.value = null
                if (explicitTransport.cursor.settle(song.id)) {
                    explicitTransport.pagerJob = null
                }
            }
        }
    }

    // Selection is dispatched at accepted release, never sampled from visual settlement. Native
    // pager physics owns motion; Compose only retires the matching stable-ID route.
    LaunchedEffect(trackState) {
        snapshotFlow { trackState.settledSongId }
            .distinctUntilChanged()
            .collect { selectedId ->
                if (
                    pagerSyncTarget.value == selectedId &&
                    latestRequestedSongId.value == selectedId &&
                    pagerSyncTargetAfterSettling(pagerSyncTarget.value, selectedId) == null
                ) {
                    pagerSyncTarget.value = null
                    if (explicitTransport.cursor.settle(selectedId)) {
                        explicitTransport.pagerJob = null
                    }
                }
            }
    }

}
