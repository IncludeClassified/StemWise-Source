package app.stemwise.quality

import app.stemwise.playback.engine.*
import app.stemwise.quality.runner.DeterministicTraceMinimizer
import app.stemwise.ui.player.nativeplayer.*
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Generated commands exercise the actual publication reducer and UI store. */
@app.stemwise.qualification.ManagedAudioTest
class CurrentPublicationTraceTest {
    private enum class Publication { OLDER, SAME_IDENTICAL, SAME_CONFLICT, NEWER }
    private enum class Transport { PLAY, PAUSE }
    private enum class Completion { SUCCESS, FAILURE }
    private enum class Song { A, B, C }
    private sealed interface TraceAction {
        data class Transport(val command: CurrentPublicationTraceTest.Transport) : TraceAction
        data class SelectSong(val song: Song) : TraceAction
        data class CompleteCurrent(val result: Completion) : TraceAction
        data object CompleteStale : TraceAction
        data class PublishUi(val relation: Publication) : TraceAction
    }

    @Test fun droppingARealTransportCommandFailsAndShrinksTheProductionTrace() {
        val minimized = DeterministicTraceMinimizer.minimize(trace(7), "COMMAND_STATE") {
            if (failure(it) == null) failure(it, dropTransport = true) else null
        }
        assertTrue(minimized.size <= 2)
        assertEquals("COMMAND_STATE", failure(minimized, dropTransport = true))
        assertNull(failure(minimized))
    }

    @Test
    fun generatedPublicationTracesExerciseUpdatesReplacementAndRejections() {
        val observed = mutableSetOf<FullPlayerAdoptionResult>()
        repeat(20) { seed -> assertNull("seed=$seed", failure(trace(seed), observed = observed)) }
        assertTrue(observed.toString(), observed.containsAll(listOf(
            FullPlayerAdoptionResult.APPLIED_INITIAL, FullPlayerAdoptionResult.APPLIED_UPDATE,
            FullPlayerAdoptionResult.APPLIED_NEW_OWNER, FullPlayerAdoptionResult.UNCHANGED,
            FullPlayerAdoptionResult.REJECTED_STALE_REVISION,
            FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION
        )))
    }

    @Test
    fun brokenRealUpdateFailsAndShrinksTheSameProductionTrace() {
        val minimized = DeterministicTraceMinimizer.minimize(trace(7), "PUBLICATION_OUTCOME") {
            if (failure(it) == null) failure(it, dropUpdate = true) else null
        }
        assertTrue(minimized.size <= 2)
        assertEquals("PUBLICATION_OUTCOME", failure(minimized, dropUpdate = true))
        assertNull(failure(minimized))
    }

    @Test
    fun admittingAnOldPublicationFailsAndShrinksTheSameProductionTrace() {
        val minimized = DeterministicTraceMinimizer.minimize(trace(7), "PUBLICATION_OUTCOME") {
            if (failure(it) == null) failure(it, admitOld = true) else null
        }
        assertTrue(minimized.size <= 2)
        assertEquals("PUBLICATION_OUTCOME", failure(minimized, admitOld = true))
        assertNull(failure(minimized))
    }

    private fun trace(seed: Int): List<TraceAction> = buildList {
        fun publish(relation: Publication) = add(TraceAction.PublishUi(relation))
        add(TraceAction.Transport(Transport.PLAY))
        publish(Publication.NEWER)
        publish(Publication.SAME_IDENTICAL)
        add(TraceAction.SelectSong(Song.B))
        add(TraceAction.SelectSong(Song.C))
        add(TraceAction.CompleteStale)
        add(TraceAction.CompleteCurrent(Completion.SUCCESS))
        publish(Publication.NEWER)
        publish(Publication.OLDER)
        publish(Publication.SAME_CONFLICT)
        val random = Random(seed)
        repeat(60) {
            when (random.nextInt(4)) {
                0 -> add(TraceAction.Transport(
                    if (random.nextBoolean()) Transport.PLAY else Transport.PAUSE))
                1 -> {
                    add(TraceAction.SelectSong(Song.entries[random.nextInt(3)]))
                    add(TraceAction.CompleteCurrent(Completion.SUCCESS))
                }
                else -> publish(Publication.entries[random.nextInt(4)])
            }
        }
    }

    private fun failure(
        actions: List<TraceAction>,
        admitOld: Boolean = false,
        dropUpdate: Boolean = false,
        dropTransport: Boolean = false,
        observed: MutableSet<FullPlayerAdoptionResult> = mutableSetOf()
    ): String? {
        val reducer = PublicationReplayDriver()
        val store = FullPlayerStateStore()
        val catalog = FullPlayerTestFixtures.catalog()
        val queue = FullPlayerTestFixtures.queue(catalog)
        var token = 1L
        var revision = 100L
        var pending: EngineSelection? = null
        var superseded: EngineSelection? = null
        var expectedSong = 0
        var expectedPlaying = false
        fun selection(index: Int) = EngineSelection(EngineSelectionToken(token++),
            "song-$index", "audio-song-$index", "/song-$index.flac", 60_000L, 0L, false)
        fun request(song: EngineSelection) = reducer.requestSelection(song)
        fun commit(song: EngineSelection) = reducer.commitPaused(song)
        selection(0).also { request(it); commit(it) }
        fun packet(): FullPlayerSnapshot {
            val committed = requireNotNull(reducer.snapshot().committed)
            return FullPlayerTestFixtures.snapshot(
                selectedIndex = committed.selection.songId.removePrefix("song-").toInt(),
                generation = committed.owner.ownership.generation,
                baseRevision = revision, catalog = catalog, queue = queue
            ).let { it.copy(transport = it.transport.copy(playWhenReady = committed.transport.playWhenReady)) }
        }
        var produced = packet()
        var visible = produced
        observed += store.adopt(produced).also { check(it == FullPlayerAdoptionResult.APPLIED_INITIAL) }
        for (action in actions) {
            when (action) {
                is TraceAction.Transport -> {
                    val committed = requireNotNull(reducer.snapshot().committed)
                    expectedPlaying = action.command == Transport.PLAY
                    if (!dropTransport) reducer.requestTransport(committed.selection.token,
                        committed.transport.desiredRevision + 1L, expectedPlaying, 0L)
                    revision += 100L
                    produced = packet()
                }
                is TraceAction.SelectSong -> {
                    superseded = pending ?: superseded
                    pending = selection(action.song.ordinal).also(::request)
                }
                TraceAction.CompleteStale -> superseded?.let(::commit)
                is TraceAction.CompleteCurrent -> pending?.let { song ->
                    if (action.result == Completion.SUCCESS) {
                        commit(song)
                        expectedSong = song.songId.removePrefix("song-").toInt()
                        expectedPlaying = false
                        revision += 100L
                        produced = packet()
                    }
                    pending = null
                }
                is TraceAction.PublishUi -> {
                    val candidate = when (action.relation) {
                        Publication.NEWER -> produced
                        Publication.SAME_IDENTICAL -> visible
                        Publication.OLDER -> visible.copy(revision = visible.revision - 1L)
                        Publication.SAME_CONFLICT -> visible.copy(
                            transport = visible.transport.copy(playWhenReady = !visible.transport.playWhenReady))
                    }
                    val expected = when (action.relation) {
                        Publication.NEWER -> when {
                            produced === visible -> FullPlayerAdoptionResult.UNCHANGED
                            produced.owner.generation != visible.owner.generation -> FullPlayerAdoptionResult.APPLIED_NEW_OWNER
                            else -> FullPlayerAdoptionResult.APPLIED_UPDATE
                        }
                        Publication.SAME_IDENTICAL -> FullPlayerAdoptionResult.UNCHANGED
                        Publication.OLDER -> FullPlayerAdoptionResult.REJECTED_STALE_REVISION
                        Publication.SAME_CONFLICT -> FullPlayerAdoptionResult.REJECTED_REVISION_COLLISION
                    }
                    val supplied = when {
                        admitOld && action.relation == Publication.OLDER ->
                            candidate.copy(revision = visible.revision + 1L)
                        dropUpdate && expected == FullPlayerAdoptionResult.APPLIED_UPDATE -> visible
                        else -> candidate
                    }
                    val result = store.adopt(supplied)
                    observed += result
                    if (result != expected) return "PUBLICATION_OUTCOME"
                    if (expected == FullPlayerAdoptionResult.APPLIED_UPDATE ||
                        expected == FullPlayerAdoptionResult.APPLIED_NEW_OWNER) visible = candidate
                    if (store.snapshot !== visible) return "PUBLICATION_STATE"
                }
            }
            // Independent command oracle, separate from the application's publication state.
            val committed = requireNotNull(reducer.snapshot().committed)
            if (committed.selection.songId != "song-$expectedSong" ||
                committed.transport.playWhenReady != expectedPlaying) return "COMMAND_STATE"
        }
        return null
    }
}
