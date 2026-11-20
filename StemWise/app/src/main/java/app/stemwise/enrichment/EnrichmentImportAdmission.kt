package app.stemwise.enrichment

internal class StartupCommandQueue(private val lock: Any = Any()) {
    private var ready = false
    private var available = false
    private var draining = false
    private val commands = java.util.ArrayDeque<() -> Unit>()

    val failed: Boolean get() = synchronized(lock) { ready && !available }

    fun begin() = synchronized(lock) {
        check(!draining)
        ready = false
        available = false
    }

    fun submit(command: () -> Unit, dispatch: (() -> Unit) -> Unit) {
        val runNow = synchronized(lock) {
            if (!ready || draining) {
                commands.addLast(command)
                false
            } else {
                available
            }
        }
        if (runNow) dispatch(command)
    }

    fun complete(isAvailable: Boolean, dispatch: (() -> Unit) -> Unit) {
        synchronized(lock) {
            ready = true
            available = isAvailable
            if (!available) {
                commands.clear()
                return
            }
            draining = true
        }
        while (true) {
            val command = synchronized(lock) {
                if (commands.isEmpty()) {
                    draining = false
                    null
                } else {
                    commands.removeFirst()
                }
            } ?: return
            dispatch(command)
        }
    }
}

internal class TaskRemovalCommandQueue(private val lock: Any = Any()) {
    private var generation = 0L
    private var ready = true
    private var failed = false
    private var draining = false
    val needsRetry: Boolean get() = synchronized(lock) { failed }
    private val commands = java.util.ArrayDeque<() -> Unit>()

    fun begin(): Long = synchronized(lock) {
        generation++
        ready = false
        failed = false
        draining = false
        commands.clear()
        generation
    }

    fun submit(command: () -> Unit, dispatch: (() -> Unit) -> Unit) {
        val runNow = synchronized(lock) {
            when {
                failed || !ready || draining -> {
                    commands.addLast(command)
                    false
                }
                else -> true
            }
        }
        if (runNow) dispatch(command)
    }

    fun retry(): Long = synchronized(lock) {
        check(failed)
        generation++
        ready = false
        failed = false
        generation
    }

    fun complete(owner: Long, success: Boolean, dispatch: (() -> Unit) -> Unit) {
        synchronized(lock) {
            if (owner != generation) return
            if (!success) {
                failed = true
                return
            }
            draining = true
        }
        while (true) {
            val command = synchronized(lock) {
                if (owner != generation) return
                if (commands.isEmpty()) {
                    draining = false
                    ready = true
                    null
                } else {
                    commands.removeFirst()
                }
            } ?: return
            dispatch(command)
        }
    }
}
