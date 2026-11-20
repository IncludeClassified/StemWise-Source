package app.stemwise.playback.engine


internal enum class EngineLifecycleAction {
    SUSPEND_FOR_AUDIO_FOCUS,
    RESUME_AFTER_AUDIO_FOCUS,
    CLOSE
}

internal sealed interface EngineMailboxEntry {
    val sequence: Long

    data class DesiredState(
        override val sequence: Long,
        val state: EngineDesiredState
    ) : EngineMailboxEntry

    data class Lifecycle(
        override val sequence: Long,
        val action: EngineLifecycleAction
    ) : EngineMailboxEntry

    data class Stop(
        override val sequence: Long,
        val request: NativeEngineStopRequest
    ) : EngineMailboxEntry

    /** Latest durable Play/Pause intent for the requested token, including an outgoing owner. */
    data class AudibleTransport(
        override val sequence: Long,
        val token: EngineSelectionToken,
        val playWhenReady: Boolean
    ) : EngineMailboxEntry

    data class ExplicitLoop(
        override val sequence: Long,
        val state: EngineExplicitLoopState
    ) : EngineMailboxEntry
}

/**
 * Bounded multi-producer mailbox consumed by the one playback command actor.
 *
 * Desired transport/control state is conflated to one immutable snapshot. One unconsumed STOP
 * retains its exact receipt; each idempotent lifecycle action also has at most one entry, so a
 * stalled actor cannot accumulate an unbounded focus/stop queue. Repeated equal actions retain only the latest causal sequence;
 * distinct actions remain ordered. A Select-after-Stop restarts while a Stop-after-Select cannot
 * be accidentally overtaken by conflation.
 */
internal class EngineCommandMailbox {
    private var nextSequence = 0L
    private var desired: EngineMailboxEntry.DesiredState? = null
    private var audibleTransport: EngineMailboxEntry.AudibleTransport? = null
    private var explicitLoop: EngineMailboxEntry.ExplicitLoop? = null
    private var stop: EngineMailboxEntry.Stop? = null
    private val lifecycle = ArrayDeque<EngineMailboxEntry.Lifecycle>(
        EngineLifecycleAction.entries.size
    )
    private var closeSequence: Long? = null

    @Synchronized
    fun offerDesired(state: EngineDesiredState): Long {
        check(closeSequence == null) { "A closed engine mailbox cannot accept desired state" }
        val sequence = allocateSequence()
        desired = EngineMailboxEntry.DesiredState(sequence, state)
        return sequence
    }

    @Synchronized
    fun offerStop(request: NativeEngineStopRequest): Long {
        check(closeSequence == null) { "A closed engine mailbox cannot accept STOP" }
        val sequence = allocateSequence()
        stop = EngineMailboxEntry.Stop(sequence, request)
        return sequence
    }

    @Synchronized
    fun offerLifecycle(action: EngineLifecycleAction): Long {
        closeSequence?.let { closedAt ->
            check(action == EngineLifecycleAction.CLOSE) {
                "A closed engine mailbox cannot accept lifecycle work"
            }
            return closedAt
        }
        val sequence = allocateSequence()
        // Every lifecycle action is idempotent before the actor observes it. Keeping the newest
        // equal boundary preserves its ordering relative to the conflated desired-state lane.
        lifecycle.removeAll { it.action == action }
        lifecycle.addLast(EngineMailboxEntry.Lifecycle(sequence, action))
        check(lifecycle.size <= EngineLifecycleAction.entries.size)
        if (action == EngineLifecycleAction.CLOSE) closeSequence = sequence
        return sequence
    }

    @Synchronized
    fun offerAudibleTransport(token: EngineSelectionToken, playWhenReady: Boolean): Long {
        check(closeSequence == null) { "A closed engine mailbox cannot accept transport work" }
        val sequence = allocateSequence()
        audibleTransport = EngineMailboxEntry.AudibleTransport(sequence, token, playWhenReady)
        return sequence
    }

    @Synchronized
    fun offerExplicitLoop(state: EngineExplicitLoopState): Long {
        check(closeSequence == null) { "A closed engine mailbox cannot accept loop work" }
        val sequence = allocateSequence()
        explicitLoop = EngineMailboxEntry.ExplicitLoop(sequence, state)
        return sequence
    }

    /** Returns every lossless boundary and at most one desired snapshot in causal order. */
    @Synchronized
    fun drain(): List<EngineMailboxEntry> {
        val entries = ArrayList<EngineMailboxEntry>()
        desired?.let(entries::add)
        desired = null
        audibleTransport?.let(entries::add)
        audibleTransport = null
        explicitLoop?.let(entries::add)
        explicitLoop = null
        stop?.let(entries::add)
        stop = null
        while (lifecycle.isNotEmpty()) entries += lifecycle.removeFirst()
        entries.sortBy(EngineMailboxEntry::sequence)
        return entries
    }

    @Synchronized
    fun hasPendingWork(): Boolean = desired != null || audibleTransport != null ||
        explicitLoop != null || stop != null || lifecycle.isNotEmpty()

    private fun allocateSequence(): Long {
        check(nextSequence < Long.MAX_VALUE) { "Engine command sequence space exhausted" }
        return ++nextSequence
    }
}
