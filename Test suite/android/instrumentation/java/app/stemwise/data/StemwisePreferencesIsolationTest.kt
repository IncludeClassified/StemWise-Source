package app.stemwise.data

import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StemwisePreferencesIsolationTest {
    @Test fun selectionIsAtomicDurableAndScopedWhileProjectionCanBeCleared() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "selected-playlist-${UUID.randomUUID()}.db"
        var database = LibraryDatabase.buildFreshProjectionDatabase(context, name)
        try {
            val old = SelectedPlaylistEntity("library-a", "PL1234567890",
                "https://music.youtube.com/playlist?list=PL1234567890", "First", 123L, null)
            val next = old.copy(playlistId = "PL0987654321",
                playlistUrl = "https://music.youtube.com/playlist?list=PL0987654321", displayName = "Next")
            val dao = database.libraryDao()
            dao.upsertSelectedPlaylist(old)
            val projection = StemwisePreferences(context, database = database)
            projection.activateLibraryFromRoot(old.libraryId)
            assertEquals(old.playlistId, projection.playlist.value.link.id)
            val failure = runCatching { database.withTransaction {
                dao.upsertSelectedPlaylist(next)
                error("INTERRUPTED_BEFORE_COMMIT")
            } }.exceptionOrNull()
            assertEquals("INTERRUPTED_BEFORE_COMMIT", failure?.message)
            assertEquals(old, dao.selectedPlaylist(old.libraryId))
            assertEquals(old.playlistId, projection.playlist.value.link.id)
            database.withTransaction { dao.upsertSelectedPlaylist(next) }
            projection.activateLibraryFromRoot("library-b")
            assertFalse(projection.playlist.value.configured)
            dao.clearActiveLibraryProjection()
            database.close()
            database = LibraryDatabase.buildFreshProjectionDatabase(context, name)
            val restarted = StemwisePreferences(context, database = database)
            restarted.activateLibraryFromRoot(old.libraryId)
            assertEquals(next.playlistId, restarted.playlist.value.link.id)
            assertEquals(next.displayName, restarted.playlist.value.displayName)
            assertEquals(next, database.libraryDao().selectedPlaylist(old.libraryId))
        } finally {
            database.close()
            check(context.deleteDatabase(name))
        }
    }
}
