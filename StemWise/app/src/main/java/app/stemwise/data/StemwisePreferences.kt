package app.stemwise.data

import android.content.Context
import androidx.room.withTransaction
import app.stemwise.integration.PlaylistLink
import app.stemwise.integration.PlaylistLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaylistSettings(
    val link: PlaylistLink,
    val displayName: String,
    val lastSyncEpochMs: Long?,
    val lastSyncError: String?,
    val configured: Boolean
)

/** A UI projection of the one library-keyed Room playlist authority. */
class StemwisePreferences(
    context: Context,
    initialLibraryId: String? = null,
    private val database: LibraryDatabase = LibraryDatabase.get(context)
) {
    private val dao = database.libraryDao()
    private var activeLibraryId: String? = initialLibraryId
    private val mutablePlaylist = MutableStateFlow(emptyPlaylistSettings())
    val playlist: StateFlow<PlaylistSettings> = mutablePlaylist.asStateFlow()

    suspend fun activateLibraryFromRoot(libraryId: String, recoveredLink: PlaylistLink? = null) {
        require(libraryId.isNotBlank()) { "LIBRARY_ID_INVALID" }
        val row = dao.selectedPlaylist(libraryId)
        check(recoveredLink == null || row?.playlistId == recoveredLink.id) { "PLAYLIST_AUTHORITY_MISMATCH" }
        activeLibraryId = libraryId
        mutablePlaylist.value = row.toSettings()
    }

    suspend fun recordSyncSuccess(displayName: String, atEpochMs: Long) {
        val libraryId = checkNotNull(activeLibraryId) { "LIBRARY_NOT_SELECTED" }
        database.withTransaction {
            val row = checkNotNull(dao.selectedPlaylist(libraryId)) { "PLAYLIST_NOT_SELECTED" }
            dao.upsertSelectedPlaylist(row.copy(displayName = displayName,
                lastSyncEpochMs = atEpochMs, lastSyncError = null))
        }
        activateLibraryFromRoot(libraryId)
    }

    suspend fun recordSyncFailure(message: String) {
        val libraryId = checkNotNull(activeLibraryId) { "LIBRARY_NOT_SELECTED" }
        database.withTransaction {
            val row = checkNotNull(dao.selectedPlaylist(libraryId)) { "PLAYLIST_NOT_SELECTED" }
            dao.upsertSelectedPlaylist(row.copy(lastSyncError = message))
        }
        activateLibraryFromRoot(libraryId)
    }

    internal fun activeLibraryIdentity(): String? = activeLibraryId

    private fun SelectedPlaylistEntity?.toSettings(): PlaylistSettings {
        if (this == null) return emptyPlaylistSettings()
        val link = PlaylistLinks.parse(playlistUrl)
        check(link != null && link.id == playlistId) { "PLAYLIST_AUTHORITY_INVALID" }
        return PlaylistSettings(link, displayName, lastSyncEpochMs,
            lastSyncError, true)
    }

    private fun emptyPlaylistSettings() = PlaylistSettings(
        PlaylistLink("", ""), "No playlist selected", null, null, false)
}
