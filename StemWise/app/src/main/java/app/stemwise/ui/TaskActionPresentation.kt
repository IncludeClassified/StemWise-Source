package app.stemwise.ui

import app.stemwise.data.PlaylistSyncStatus

internal fun playlistSyncActive(status: PlaylistSyncStatus): Boolean =
    status.running || status.queued

internal fun playlistSyncActionLabel(status: PlaylistSyncStatus): String = when {
    status.running -> "Syncing"
    status.queued && status.waitingForNetwork -> "Waiting"
    status.queued -> "Queued"
    else -> "Sync"
}
