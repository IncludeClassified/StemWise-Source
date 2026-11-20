package app.stemwise.data

import kotlinx.coroutines.sync.Mutex
import app.stemwise.KeyedOperationMutex

/**
 * Serializes file/database mutations for one song across bundle import,
 * analysis, separation, source replacement, and deletion.
 */
class SongWorkCoordinator {
    private val locks = KeyedOperationMutex()
    /** One process-wide lane for upload, part preparation, and source deletion. */
    internal val preparationLane = Mutex()

    suspend fun <T> withSong(songId: String, block: suspend () -> T): T =
        locks.withKey(songId, block)
}
