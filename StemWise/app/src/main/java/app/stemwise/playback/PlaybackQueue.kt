package app.stemwise.playback

import java.util.Random

/** A pair is consumed once; a new song or another transport action starts a fresh pair. */
internal class PreviousPressTracker {
    private var songId: String? = null
    private var pressedAtMs = 0L

    fun press(currentSongId: String, nowMs: Long): Boolean {
        val skip = songId == currentSongId && nowMs - pressedAtMs in 0L..2_000L
        songId = if (skip) null else currentSongId
        pressedAtMs = nowMs
        return skip
    }

    fun reset() { songId = null }
}

internal fun previousRestartPositionMs(positionMs: Long, loopStartMs: Long?, loopEndMs: Long?): Long =
    if (loopStartMs != null && loopEndMs != null &&
        loopStartMs >= 0L && positionMs >= loopStartMs && positionMs < loopEndMs
    ) loopStartMs else 0L

enum class RepeatMode(val label: String) {
    OFF("Off"),
    ALL("All"),
    ONE("One");

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromLabel(value: String): RepeatMode =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: OFF
    }
}

enum class QueueTransition {
    EXPLICIT_NEXT,
    EXPLICIT_PREVIOUS,
    AUTOMATIC_END
}

sealed interface QueueDecision {
    data class Move(val mediaId: String, val index: Int) : QueueDecision
    data class Restart(val mediaId: String) : QueueDecision
    data object Stop : QueueDecision
    data object Boundary : QueueDecision
}

data class PlaybackQueueSnapshot(
    val order: List<String> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val shuffleSeed: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF
) {
    internal fun validate() {
        require(order.all(String::isNotBlank) && order.distinct().size == order.size &&
            (if (order.isEmpty()) currentIndex == -1 else currentIndex in order.indices)) {
            "PLAYBACK_QUEUE_SNAPSHOT_INVALID"
        }
    }

    val currentId: String?
        get() = order.getOrNull(currentIndex)
    val hasPrevious: Boolean
        get() = currentIndex > 0 || (repeatMode == RepeatMode.ALL && order.size > 1)
    val hasNext: Boolean
        get() = currentIndex in 0 until order.lastIndex ||
            (repeatMode == RepeatMode.ALL && order.size > 1)
}

/**
 * One deterministic queue authority shared by UI, Media3, notification and
 * headset commands. Shuffle is a stable seeded order, not a per-skip random
 * choice.
 */
class PlaybackQueueController(seed: Long = System.nanoTime()) {
    private var snapshot = PlaybackQueueSnapshot(shuffleSeed = seed)
    private var indexByMediaId: Map<String, Int> = emptyMap()

    fun snapshot(): PlaybackQueueSnapshot = snapshot

    /** Retains the saved permutation rather than rerunning shuffle over an already shuffled order. */
    fun restore(saved: PlaybackQueueSnapshot): PlaybackQueueSnapshot {
        saved.validate()
        return install(saved.copy(order = saved.order.toList()))
    }

    /** Constant-time membership against the same index used by [select]. */
    fun contains(mediaId: String): Boolean = indexByMediaId.containsKey(mediaId)

    /**
     * Installs one immutable queue snapshot and rebuilds its direct lookup only when the order
     * itself changes. Queue navigation therefore stays constant-time even when a local library is
     * much larger than the release pressure fixture; library size changes metadata memory, not
     * decoder or playback topology.
     */
    private fun install(
        next: PlaybackQueueSnapshot,
        preparedIndex: Map<String, Int>? = null
    ): PlaybackQueueSnapshot {
        if (next.order !== snapshot.order) {
            indexByMediaId = preparedIndex ?: index(next.order)
        }
        snapshot = next
        return next
    }

    private fun index(order: List<String>): Map<String, Int> = buildMap(order.size) {
        order.forEachIndexed { index, mediaId -> put(mediaId, index) }
    }

    fun clear(): PlaybackQueueSnapshot {
        return install(PlaybackQueueSnapshot(
            shuffleSeed = snapshot.shuffleSeed
        ))
    }

    fun updateLibrary(ids: List<String>): PlaybackQueueSnapshot {
        val distinct = ids.distinct()
        val incomingIds = distinct.toHashSet()
        val current = snapshot.currentId
        val retained = snapshot.order.filter(incomingIds::contains)
        val retainedIds = retained.toHashSet()
        val additions = distinct.filterNot(retainedIds::contains)
        val orderedAdditions = if (snapshot.shuffleEnabled) {
            additions.shuffled(Random(snapshot.shuffleSeed xor retained.size.toLong()))
        } else {
            additions
        }
        val nextOrder = if (snapshot.order.isEmpty()) {
            if (snapshot.shuffleEnabled) {
                distinct.shuffled(Random(snapshot.shuffleSeed))
            } else {
                distinct
            }
        } else {
            retained + orderedAdditions
        }
        val nextIndexByMediaId = index(nextOrder)
        val nextIndex = current?.let(nextIndexByMediaId::get)
            ?: if (nextOrder.isEmpty()) -1 else 0
        return install(
            snapshot.copy(order = nextOrder, currentIndex = nextIndex),
            nextIndexByMediaId
        )
    }

    fun start(
        ids: List<String>,
        startId: String? = null,
        shuffle: Boolean,
        seed: Long = snapshot.shuffleSeed
    ): PlaybackQueueSnapshot {
        val distinct = ids.distinct()
        val order = if (shuffle) distinct.shuffled(Random(seed)) else distinct
        val nextIndexByMediaId = index(order)
        val requestedIndex = startId?.let(nextIndexByMediaId::get) ?: -1
        return install(snapshot.copy(
            order = order,
            currentIndex = when {
                requestedIndex >= 0 -> requestedIndex
                order.isNotEmpty() -> 0
                else -> -1
            },
            shuffleEnabled = shuffle,
            shuffleSeed = seed
        ), nextIndexByMediaId)
    }

    fun select(mediaId: String): PlaybackQueueSnapshot {
        val index = indexByMediaId[mediaId]
        if (index != null) install(snapshot.copy(currentIndex = index))
        return snapshot
    }

    fun setShuffle(enabled: Boolean, seed: Long = snapshot.shuffleSeed): PlaybackQueueSnapshot {
        if (enabled == snapshot.shuffleEnabled) return snapshot
        val current = snapshot.currentId
        val nextOrder = if (enabled) {
            // currentIndex is a location, not playback history: a library tap can jump directly
            // from item 0 to item 300. Treating 0..299 as a played prefix would make those songs
            // unreachable under Repeat Off. Rebase the current owner at the front and permute
            // every other eligible ID exactly once. This is the same complete seeded-order model
            // used by a fresh Library shuffle and by MediaSession's setShuffle route.
            if (current == null) {
                snapshot.order.shuffled(Random(seed))
            } else {
                listOf(current) + snapshot.order
                    .asSequence()
                    .filterNot { it == current }
                    .toList()
                    .shuffled(Random(seed))
            }
        } else {
            // The library order will be supplied immediately after this call.
            snapshot.order
        }
        val nextIndexByMediaId = if (nextOrder === snapshot.order) {
            indexByMediaId
        } else {
            index(nextOrder)
        }
        return install(snapshot.copy(
            order = nextOrder,
            currentIndex = current?.let(nextIndexByMediaId::get) ?: -1,
            shuffleEnabled = enabled,
            shuffleSeed = seed
        ), nextIndexByMediaId)
    }

    fun setSequentialOrder(libraryIds: List<String>): PlaybackQueueSnapshot {
        if (snapshot.shuffleEnabled) return snapshot
        val current = snapshot.currentId
        val order = libraryIds.distinct()
        val nextIndexByMediaId = index(order)
        return install(snapshot.copy(
            order = order,
            currentIndex = current?.let(nextIndexByMediaId::get)
                ?: if (order.isEmpty()) -1 else 0
        ), nextIndexByMediaId)
    }

    fun setRepeatMode(mode: RepeatMode): PlaybackQueueSnapshot {
        return install(snapshot.copy(repeatMode = mode))
    }

    /**
     * Returns the exact queue decision without advancing [snapshot].
     *
     * Automatic-end prewarming must use this preview instead of independently guessing the next
     * index: Repeat One, terminal Stop, and Repeat All wrapping are queue
     * policy, not decoder policy.
     */
    fun preview(transition: QueueTransition, fromId: String? = snapshot.currentId): QueueDecision =
        decisionFor(transition, currentId = fromId)

    fun previewAutomaticEnd(): QueueDecision = preview(QueueTransition.AUTOMATIC_END)

    fun previewAutomaticEndIgnoringRepeatOne(): QueueDecision =
        decisionFor(
            QueueTransition.AUTOMATIC_END,
            honorRepeatOne = false
        )

    fun decide(transition: QueueTransition): QueueDecision {
        val decision = decisionFor(transition)
        if (decision is QueueDecision.Move) {
            install(snapshot.copy(currentIndex = decision.index))
        }
        return decision
    }

    private fun decisionFor(
        transition: QueueTransition,
        honorRepeatOne: Boolean = true,
        currentId: String? = snapshot.currentId
    ): QueueDecision {
        val current = currentId?.takeIf(indexByMediaId::containsKey) ?: return QueueDecision.Stop
        if (transition == QueueTransition.AUTOMATIC_END && honorRepeatOne &&
            snapshot.repeatMode == RepeatMode.ONE
        ) {
            return QueueDecision.Restart(current)
        }
        if (
            transition == QueueTransition.AUTOMATIC_END &&
            snapshot.repeatMode == RepeatMode.ALL &&
            snapshot.order.size == 1
        ) {
            // Repeat All repeats the queue, even when that queue contains only
            // the current item. Restarting in place retains the native runtime owner;
            // explicit navigation remains a boundary because there is no other
            // destination.
            return QueueDecision.Restart(current)
        }
        val direction = when (transition) {
            QueueTransition.EXPLICIT_PREVIOUS -> -1
            QueueTransition.EXPLICIT_NEXT,
            QueueTransition.AUTOMATIC_END -> 1
        }
        val candidate = indexByMediaId.getValue(current) + direction
        val targetIndex = when {
            candidate in snapshot.order.indices -> candidate
            snapshot.repeatMode == RepeatMode.ALL && snapshot.order.size > 1 ->
                if (direction > 0) 0 else snapshot.order.lastIndex
            else -> return if (transition == QueueTransition.AUTOMATIC_END) {
                QueueDecision.Stop
            } else {
                QueueDecision.Boundary
            }
        }
        return QueueDecision.Move(snapshot.order[targetIndex], targetIndex)
    }

}
