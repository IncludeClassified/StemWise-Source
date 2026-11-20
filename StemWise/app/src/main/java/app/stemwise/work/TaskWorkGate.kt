package app.stemwise.work

/**
 * Serializes durable-work handoffs against an explicit app-task removal.
 *
 * A lease represents the task generation in which an asynchronous request began. Closing the
 * gate invalidates every outstanding lease before WorkManager cancellation starts. Reopening the
 * app accepts new requests without making an old repository callback current again.
 */
internal class TaskWorkGate {
    internal class Lease internal constructor(val generation: Long)

    private val lock = Any()
    private var generation = 0L
    private var removed = false
    private var librarySwitching = false

    fun acquireLease(): Lease? = synchronized(lock) {
        if (removed || librarySwitching) null else Lease(generation)
    }

    fun runIfActive(action: () -> Unit): Boolean = synchronized(lock) {
        if (removed || librarySwitching) return@synchronized false
        action()
        true
    }

    fun runIfCurrent(lease: Lease, action: () -> Unit): Boolean = synchronized(lock) {
        if (removed || librarySwitching || lease.generation != generation) {
            return@synchronized false
        }
        action()
        true
    }

    fun isCurrent(lease: Lease): Boolean = synchronized(lock) {
        !removed && !librarySwitching && lease.generation == generation
    }

    /** Returns true only for the transition that invalidated the current generation. */
    fun closeForTaskRemoval(): Boolean = synchronized(lock) {
        if (removed) return@synchronized false
        removed = true
        generation += 1L
        true
    }

    fun openForTask() = synchronized(lock) {
        removed = false
    }

    /** Root replacement invalidates leases without changing Recents/task-removal ownership. */
    fun closeForLibrarySwitch() = synchronized(lock) {
        if (!librarySwitching) generation += 1L
        librarySwitching = true
    }

    fun openAfterLibrarySwitch() = synchronized(lock) {
        librarySwitching = false
    }

    internal fun isActiveForTest(): Boolean = synchronized(lock) {
        !removed && !librarySwitching
    }

}

/** One process-wide ordering point for every app-owned WorkManager enqueue. */
internal object AppTaskWorkGate {
    val instance = TaskWorkGate()
}
