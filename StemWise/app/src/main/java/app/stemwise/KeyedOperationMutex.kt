package app.stemwise

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A key remains owned until its holder and every admitted waiter have retired. */
internal class KeyedOperationMutex {
    private data class Entry(val mutex: Mutex, var users: Int)
    private val guard = Any()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val entry = synchronized(guard) {
            entries.getOrPut(key) { Entry(Mutex(), 0) }.also { it.users++ }
        }
        return try { entry.mutex.withLock { block() } }
        finally {
            synchronized(guard) {
                entry.users--
                if (entry.users == 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    internal fun activeKeyCount(): Int = synchronized(guard) { entries.size }
    internal fun isLocked(key: String): Boolean = synchronized(guard) { entries[key]?.mutex?.isLocked == true }
}
