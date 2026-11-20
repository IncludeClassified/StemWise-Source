package app.stemwise.playback

import app.stemwise.data.RootAuthority
import app.stemwise.model.Song
import app.stemwise.playback.engine.EngineSelectionToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class PartsPlaybackRestoreIntent(
    val root: RootAuthority,
    val song: Song,
    val selectionSerial: Long,
    val positionMs: Long,
    val playWhenReady: Boolean
)

internal interface PartsDeletionPlaybackSink {
    fun currentRoot(): RootAuthority?
    fun selectionSerial(): Long
    fun quiesce(root: RootAuthority, songId: String): PartsPlaybackRestoreIntent?
    fun quiesceLateSelection(root: RootAuthority, songId: String): PartsPlaybackRestoreIntent? = null
    fun retirementTokens(songId: String): Set<EngineSelectionToken>?
    fun retired(token: EngineSelectionToken): Boolean
    fun finished(songId: String) = Unit
    fun restoreOriginal(intent: PartsPlaybackRestoreIntent)
}

/** Blocks only the exact deleting package; a newer selection remains free to play. */
internal class PartsDeletionPlaybackOwner(
    private val sink: PartsDeletionPlaybackSink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val retirementTimeoutMs: Long = 10_000L,
    private val pollIntervalMs: Long = 16L
) {
    private val deleting = mutableSetOf<Pair<RootAuthority, String>>()
    fun blocks(root: RootAuthority?, songId: String): Boolean = root != null && root to songId in deleting

    suspend fun <T> delete(
        root: RootAuthority,
        songId: String,
        restoreAfterDeletion: Boolean = true,
        operation: suspend (onExactRootAdmitted: suspend () -> Unit) -> Result<T>
    ): Result<T> {
        var admitted = false
        var restore: PartsPlaybackRestoreIntent? = null
        try {
            return operation {
                withContext(dispatcher) {
                    check(sink.currentRoot() == root) { "LIBRARY_CHANGED" }
                    check(deleting.add(root to songId)) { "PART_DELETION_ALREADY_RUNNING" }
                    admitted = true
                    restore = sink.quiesce(root, songId)
                    val retired = withTimeoutOrNull(retirementTimeoutMs) {
                        var tokens = sink.retirementTokens(songId)
                        while (tokens == null || tokens.any { !sink.retired(it) }) {
                            check(sink.currentRoot() == root) { "LIBRARY_CHANGED" }
                            // A primed successor may have won its physical boundary before the
                            // first quiesce was observed. Retire that exact late public selection;
                            // the sink ignores unrelated owners and an already-pending STOP.
                            sink.quiesceLateSelection(root, songId)?.let { if (restore == null) restore = it }
                            delay(pollIntervalMs)
                            check(sink.currentRoot() == root) { "LIBRARY_CHANGED" }
                            tokens = sink.retirementTokens(songId)
                        }
                        true
                    }
                    check(retired == true) { "PART_CONSUMER_RETIREMENT_TIMEOUT" }
                    check(sink.currentRoot() == root) { "LIBRARY_CHANGED" }
                }
            }
        } finally {
            if (admitted) withContext(dispatcher + NonCancellable) {
                deleting.remove(root to songId)
                restore?.takeIf { restoreAfterDeletion && sink.currentRoot() == it.root && sink.selectionSerial() == it.selectionSerial }
                    ?.let(sink::restoreOriginal)
                if (sink.currentRoot() == root) sink.finished(songId)
            }
        }
    }
}
