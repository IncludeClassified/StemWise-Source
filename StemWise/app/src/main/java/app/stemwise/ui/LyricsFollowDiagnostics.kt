package app.stemwise.ui

internal data class LyricsFollowTraceEntry(
    val eventSequence: Long,
    val eventType: String,
    val modeBefore: String,
    val modeAfter: String,
    val emittedEffects: List<String>,
    val latchId: Long?,
    val candidateId: Long?,
    val actuatorToken: Long?,
    val diagnosticReason: String?
)

/** Bounded and body-free: lyric text and media identifiers never enter this trace. */
internal class LyricsFollowDiagnostics(private val capacity: Int = 256) {
    init {
        require(capacity > 0)
    }

    private var enabled = false

    fun setEnabled(value: Boolean) { enabled = value }

    private val entries = ArrayDeque<LyricsFollowTraceEntry>(capacity)

    fun record(
        event: LyricsViewportEvent,
        before: LyricsViewportEngineState,
        reduction: LyricsViewportReduction
    ) {
        if (!enabled) return
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(
            LyricsFollowTraceEntry(
                eventSequence = event.meta.sequence,
                eventType = event::class.simpleName ?: "ViewportEvent",
                modeBefore = before.mode::class.simpleName ?: "Unknown",
                modeAfter = reduction.state.mode::class.simpleName ?: "Unknown",
                emittedEffects = reduction.effects.map { it::class.simpleName ?: "Effect" },
                latchId = reduction.state.manualLatch?.id,
                candidateId = reduction.state.candidate?.id,
                actuatorToken = reduction.state.activeActuatorToken,
                diagnosticReason = when (event) {
                    is LyricsViewportEvent.AutoScrollCancelled -> event.reason.name
                    else -> null
                }
            )
        )
    }

    fun snapshot(): List<LyricsFollowTraceEntry> = entries.toList()
}
