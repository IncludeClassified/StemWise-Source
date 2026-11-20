package app.stemwise.playback

import androidx.compose.ui.graphics.Color
import app.stemwise.data.RootAuthority
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.engine.EngineSelectionToken
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PartsDeletionPlaybackOwnerTest {
    @Test fun physicallyAdoptedSuccessorIsRetiredIfItBecomesSelectedDuringTheFence() = runBlocking {
        val sink = Sink(true).apply { restoreIntentAvailable = false }
        val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined, 2_000, 1)
        var mutated = false
        val deletion = async {
            owner.delete(root, "A", restoreAfterDeletion = false) { admitted ->
                admitted()
                mutated = true
                Result.success(Unit)
            }
        }
        yield()
        assertTrue(owner.blocks(root, "A"))
        assertFalse(mutated)
        assertEquals(0, sink.lateQuiesceCount)
        sink.lateSelected = true
        withTimeout(2_000) { sink.lateQuiesced.await() }
        assertEquals(1, sink.lateQuiesceCount)
        assertFalse(mutated)
        assertNull(sink.quiesceLateSelection(root, "A"))
        assertEquals("A pending STOP must not be issued twice", 1, sink.lateQuiesceCount)
        sink.retired = true
        assertTrue(withTimeout(2_000) { deletion.await() }.isSuccess)
        assertTrue(mutated)
        assertTrue(sink.restored.isEmpty())
        assertFalse(owner.blocks(root, "A"))
    }

    @Test fun permanentDeletionRetiresExactConsumersWithoutRestoringEvenAfterFailure() = runBlocking {
        for (succeeds in listOf(false, true)) {
            val sink = Sink(true)
            val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined, 2_000, 1)
            var mutated = false
            val operation = async {
                owner.delete(root, "A", restoreAfterDeletion = false) { admitted ->
                    admitted()
                    mutated = true
                    if (succeeds) Result.success(Unit) else Result.failure(IllegalStateException("PROVIDER_WRITE_FAILED"))
                }
            }
            yield()
            assertTrue(owner.blocks(root, "A"))
            assertFalse(owner.blocks(root, "B"))
            assertFalse(mutated)
            sink.retired = true
            assertEquals(succeeds, withTimeout(2_000) { operation.await() }.isSuccess)
            assertTrue(mutated)
            assertTrue(sink.restored.isEmpty())
            assertFalse(owner.blocks(root, "A"))
        }
    }

    @Test fun providerMutationWaitsForEveryExactRetirementAndRestoresPositionAndPlayIntent() = runBlocking {
        for (playing in listOf(false, true)) {
            val sink = Sink(playing)
            val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined, 2_000, 1)
            var mutated = false
            val operation = async {
                owner.delete(root, "A") { admitted ->
                    admitted()
                    assertTrue(sink.retired)
                    mutated = true
                    Result.success(Unit)
                }
            }
            yield()
            assertTrue(owner.blocks(root, "A"))
            assertFalse(owner.blocks(root, "B"))
            assertFalse(mutated)
            // A stop request by itself is not permission to delete. The exact lease retires here.
            sink.retired = true
            assertTrue(withTimeout(2_000) { operation.await() }.isSuccess)
            assertTrue(mutated)
            assertFalse(owner.blocks(root, "A"))
            assertEquals(1, sink.restored.size)
            assertEquals(4_321L, sink.restored.single().positionMs)
            assertEquals(playing, sink.restored.single().playWhenReady)
        }
    }

    @Test fun failureRestoresOriginalButNewSelectionOrRootCannotBeOverriddenByLateCompletion() = runBlocking {
        for (replacement in listOf("none", "selection", "root")) {
            val sink = Sink(true).apply { retired = true }
            val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined)
            val failure = IllegalStateException("PROVIDER_WRITE_FAILED")
            val result = owner.delete(root, "A") { admitted ->
                admitted()
                when (replacement) {
                    "selection" -> sink.serial++
                    "root" -> sink.current = root.copy(epoch = 2)
                }
                Result.failure<Unit>(failure)
            }
            assertSame(failure, result.exceptionOrNull())
            assertEquals(if (replacement == "none") 1 else 0, sink.restored.size)
            assertFalse(owner.blocks(root, "A"))
            assertEquals(if (replacement == "root") emptyList<String>() else listOf("A"), sink.finishedSongs)
        }
    }

    @Test fun uncapturedOrUnretiredOwnerTimesOutWithoutDeletingAndReleasesItsAdmission() = runBlocking {
        for (captured in listOf(false, true)) {
            val sink = Sink(false).apply { this.captured = captured }
            val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined, 30, 1)
            var mutated = false
            try {
                owner.delete(root, "A") { admitted -> admitted(); mutated = true; Result.success(Unit) }
                fail("Unproven retirement must not permit deletion")
            } catch (error: IllegalStateException) { assertEquals("PART_CONSUMER_RETIREMENT_TIMEOUT", error.message) }
            assertFalse(mutated)
            assertFalse(owner.blocks(root, "A"))
            assertEquals(1, sink.restored.size)
        }
    }

    @Test fun externalCancellationPropagatesAndCannotRestoreOverAReplacementIntent() = runBlocking {
        val sink = Sink(false).apply { retired = true }
        val owner = PartsDeletionPlaybackOwner(sink, Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val operation = launch {
            owner.delete<Unit>(root, "A") { admitted ->
                admitted(); entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(2_000) { entered.await() }
        sink.serial++
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertFalse(owner.blocks(root, "A"))
        assertTrue(sink.restored.isEmpty())
    }

    private class Sink(private val playing: Boolean) : PartsDeletionPlaybackSink {
        var current = root
        var serial = 7L
        var captured = true
        var retired = false
        var restoreIntentAvailable = true
        var lateSelected = false
        var lateStopping = false
        var lateQuiesceCount = 0
        val lateQuiesced = CompletableDeferred<Unit>()
        val restored = mutableListOf<PartsPlaybackRestoreIntent>()
        val finishedSongs = mutableListOf<String>()
        override fun currentRoot() = current
        override fun selectionSerial() = serial
        override fun quiesce(root: RootAuthority, songId: String) =
            PartsPlaybackRestoreIntent(root, song, serial, 4_321, playing).takeIf { restoreIntentAvailable }
        override fun quiesceLateSelection(root: RootAuthority, songId: String): PartsPlaybackRestoreIntent? {
            if (!lateSelected || lateStopping || songId != song.id) return null
            lateStopping = true
            lateQuiesceCount++
            lateQuiesced.complete(Unit)
            return PartsPlaybackRestoreIntent(root, song, serial, 4_321, playing)
        }
        override fun retirementTokens(songId: String) = if (captured) setOf(EngineSelectionToken(41)) else null
        override fun retired(token: EngineSelectionToken): Boolean { assertEquals(EngineSelectionToken(41), token); return retired }
        override fun restoreOriginal(intent: PartsPlaybackRestoreIntent) { restored += intent }
        override fun finished(songId: String) { finishedSongs += songId }
    }
    companion object {
        private val root = RootAuthority("library", "content://library", 1)
        private val song = Song(id = "A", videoId = "DELETION001", title = "Song", artist = "Artist",
            durationMs = 10_000, state = SongState.READY, accent = Color.Red, artworkGlyph = "A")
    }
}
