package app.stemwise.playback.engine

enum class NativeControllerPreparationOperation {
    ORIGINAL_OPEN, SUCCESSOR_PREWARM, STEM_PACKAGE, LOOP_HEAD
}

/** Controller monotonic-clock evidence, captured by setup/actor work, never an audio callback. */
data class NativeControllerPreparationStageDiagnostics(
    val sequence: Long,
    val ownership: NativeRuntimeOwnership?,
    val operation: NativeControllerPreparationOperation,
    val stage: String,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val cause: String?
)

/** One bounded owner for preparation clocks and their exact terminal receipts. */
internal class NativeControllerPreparationEvidence {
    private data class Key(val operation: NativeControllerPreparationOperation,
        val ownership: NativeRuntimeOwnership, val revision: Long)
    private data class Started(val sequence: Long, val atMs: Long, val stage: String)
    private val active = linkedMapOf<Key, Started>()
    private val completed = ArrayDeque<NativeControllerPreparationStageDiagnostics>()
    private var sequence = 0L

    @Synchronized fun begin(operation: NativeControllerPreparationOperation,
        ownership: NativeRuntimeOwnership, revision: Long, stage: String, nowMs: Long) {
        val key = Key(operation, ownership, revision)
        if (key in active) return
        val superseded = active.iterator()
        while (superseded.hasNext()) {
            val (prior, start) = superseded.next()
            if (prior.operation != operation || prior.ownership != ownership) continue
            superseded.remove()
            append(prior, start, "superseded", nowMs, "New preparation revision admitted")
        }
        check(active.size < MAXIMUM_STAGES) { "Preparation ownership exceeded its live operation bound" }
        active[key] = Started(++sequence, nowMs, stage)
    }

    @Synchronized fun finish(operation: NativeControllerPreparationOperation,
        token: Long, revision: Long, stage: String, nowMs: Long, cause: String? = null) {
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (key, start) = iterator.next()
            if (key.operation != operation || key.ownership.selectionToken != token || key.revision != revision) continue
            iterator.remove()
            append(key, start, stage, nowMs, cause)
        }
    }

    @Synchronized fun snapshot(nowMs: Long): List<NativeControllerPreparationStageDiagnostics> =
        (completed + active.map { (key, start) -> NativeControllerPreparationStageDiagnostics(
            start.sequence, key.ownership, key.operation, start.stage, start.atMs,
            (nowMs - start.atMs).coerceAtLeast(0L), null) }).sortedBy { it.sequence }.takeLast(MAXIMUM_STAGES)

    @Synchronized fun cancelOwnership(ownership: NativeRuntimeOwnership, nowMs: Long) {
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (key, start) = iterator.next()
            if (key.ownership != ownership) continue
            iterator.remove()
            append(key, start, "release-requested", nowMs, "Graph release requested")
        }
    }

    private fun append(key: Key, start: Started, stage: String, nowMs: Long, cause: String?) {
        completed.addLast(NativeControllerPreparationStageDiagnostics(start.sequence, key.ownership,
            key.operation, stage, start.atMs, (nowMs - start.atMs).coerceAtLeast(0L), cause?.take(512)))
        while (completed.size > MAXIMUM_STAGES) completed.removeFirst()
    }

    private companion object { const val MAXIMUM_STAGES = 32 }
}
