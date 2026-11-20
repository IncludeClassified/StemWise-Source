package app.stemwise.data

import app.stemwise.network.AppNetworkState
import app.stemwise.network.AppOwnedNetworkPolicy
import app.stemwise.network.UnmeteredNetworkRequiredException

internal data class PlaylistSyncFailureHandling(
    val status: PlaylistSyncStatus,
    val persistentError: String?
)

/** A policy wait remains retryable state and must never become a durable playlist failure. */
internal fun playlistSyncFailureHandling(
    error: Throwable,
    displayMessage: String
): PlaylistSyncFailureHandling = if (error is UnmeteredNetworkRequiredException) {
    PlaylistSyncFailureHandling(
        status = PlaylistSyncStatus(waitingForNetwork = true),
        persistentError = null
    )
} else {
    PlaylistSyncFailureHandling(
        status = PlaylistSyncStatus(error = displayMessage),
        persistentError = displayMessage
    )
}

/** A typed wait clears when Android permits the current network again. */
internal fun projectPlaylistSyncStatusForNetwork(
    status: PlaylistSyncStatus,
    networkState: AppNetworkState
): PlaylistSyncStatus = if (status.waitingForNetwork && AppOwnedNetworkPolicy.allowsNetwork(networkState)) {
    status.copy(waitingForNetwork = false)
} else status
