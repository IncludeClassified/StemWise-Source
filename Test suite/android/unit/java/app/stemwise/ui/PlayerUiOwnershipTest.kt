package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.stemwise.analysis.WaveformWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@app.stemwise.qualification.ManagedAudioTest
class PlayerUiOwnershipTest {
    private fun fineKey(songId: String = "song") = PlayerFineWaveformKey(
        PlayerWaveformBinding(SongPresentationOwner(songId, "media", "audio", "stems", "source"),
            app.stemwise.playback.PlaybackSourceMode.ORIGINAL, true, emptyList(), emptyMap()), null)

    @Test
    fun unexpectedFineReaderFailureIsNotSilenced() {
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking {
                NativeFineWaveformOwner().request(this, 1L, { fineKey() }, { error("programming defect") }) { }
                    .join()
            }
        }
        assertEquals("programming defect", failure.message)
    }

    @Test
    fun fineReadFailureClearsOnlyItsRequestAndAllowsTheNextRead() = runBlocking {
        val owner = NativeFineWaveformOwner()
        for (failure in listOf(IllegalStateException("LIBRARY_CHANGED"), java.io.IOException("provider"))) {
            val valid = WaveformWindow(15L, 20L, 5L, listOf(.7f))
            val ready = CompletableDeferred<PlayerFineWaveformWindow>()
            var reads = 0
            var cleared = false
            val job = owner.request(this, 3L, { fineKey() }, {
                if (reads++ == 0) throw failure
                valid
            }) { window ->
                if (window == null) cleared = true else ready.complete(window)
            }
            try {
                assertEquals(valid, withTimeout(2_000L) { ready.await() }.window)
                assertTrue(cleared)
                assertEquals(2, reads)
            } finally {
                owner.cancel()
                job.join()
            }
            assertEquals(Long.MIN_VALUE, owner.requestedBucket)
        }
        val cancelled = owner.request(this, 4L, { fineKey() }, { throw CancellationException("gone") }) { }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(Long.MIN_VALUE, owner.requestedBucket)
    }

    @Test
    fun fineSourceReplacementDiscardsAnOldReadAndNeverPublishesItUnderTheNewBinding() = runBlocking {
        val owner = NativeFineWaveformOwner()
        var current = fineKey()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<PlayerFineWaveformWindow>()
        val published = mutableListOf<PlayerFineWaveformWindow>()
        val job = owner.request(this, 2L, { current }, { key ->
            if (key.binding.owner.songId == "song") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                WaveformWindow(0L, 5L, 5L, listOf(.9f))
            } else WaveformWindow(0L, 5L, 5L, listOf(.2f))
        }) { value -> if (value != null) { published += value; ready.complete(value) } }
        try {
            withTimeout(2_000L) { started.await() }
            current = fineKey("replacement")
            release.complete(Unit)
            assertEquals("replacement", withTimeout(2_000L) { ready.await() }.binding.owner.songId)
            assertTrue(published.all { it.binding == current.binding && it.window.peaks == listOf(.2f) })
        } finally {
            release.complete(Unit)
            owner.cancel()
            job.join()
        }
    }

    @Test
    fun heldFineGestureWithdrawsStemsWhileAuthorityIsMissingAndReadsTheReplacementOwner() = runBlocking {
        val owner = NativeFineWaveformOwner()
        val native = app.stemwise.playback.engine.SourceWaveformIdentity("song", "audio", 10L,
            app.stemwise.playback.engine.NativeRuntimeOwnership(1L, 1L),
            app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS, listOf(1f), listOf(
                app.stemwise.playback.engine.SourceWaveformSource("original.flac", "original-hash", "original", false),
                app.stemwise.playback.engine.SourceWaveformSource("remaining.wv", "remaining-hash", "remaining", true)))
        var current = fineKey().copy(binding = fineKey().binding.copy(
            sourceMode = app.stemwise.playback.PlaybackSourceMode.STEMS), native = native)
        val first = CompletableDeferred<Unit>()
        val withdrawn = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        var reads = 0
        val job = owner.request(this, 0L, { current }, { key ->
            reads++
            WaveformWindow(0L, 5L, 5L,
                listOf(if (key.native?.ownership?.generation == 1L) .8f else .2f))
        }) { value ->
            when {
                value == null && first.isCompleted -> withdrawn.complete(Unit)
                value?.window?.peaks == listOf(.8f) -> first.complete(Unit)
                value?.window?.peaks == listOf(.2f) -> replacement.complete(Unit)
            }
        }
        try {
            withTimeout(2_000L) { first.await() }
            current = current.copy(native = null)
            withTimeout(2_000L) { withdrawn.await() }
            assertEquals(1, reads)
            current = current.copy(native = native.copy(
                ownership = app.stemwise.playback.engine.NativeRuntimeOwnership(1L, 2L)))
            withTimeout(2_000L) { replacement.await() }
            assertEquals(2, reads)
        } finally {
            owner.cancel()
            job.join()
        }
    }

    @Test
    fun playerErrorHeadingDescribesTheOperationThatFailed() {
        assertEquals("Source unavailable", PlayerControlFailure.source("no stems").heading)
        assertEquals("Control unavailable", PlayerControlFailure.control("not ready").heading)
        assertEquals("Playback stopped", playbackFailureHeading(fatalTransport = true))
        assertEquals("Playback error", playbackFailureHeading(fatalTransport = false))
    }

    @Test
    fun oldStemCompletionCannotPublishAcrossStemsOriginalStemsReversal() {
        val gate = LatestStemActivationGate()

        val firstStems = gate.issue()
        gate.invalidate() // Original supersedes the first preparation.
        val secondStems = gate.issue()

        assertFalse(gate.owns(firstStems))
        assertTrue(gate.owns(secondStems))
    }

    @Test
    fun oneStemFailureProducesOnlyOneVisibleError() {
        assertNull(
            nonDuplicatePlaybackError(
                primary = "Native stem runtime timed out",
                candidate = "  native stem runtime timed out  "
            )
        )
        assertEquals(
            "Decoder failed",
            nonDuplicatePlaybackError(
                primary = "Native stem runtime timed out",
                candidate = "Decoder failed"
            )
        )
    }

    @Test
    fun prewarmedCommitCannotMakeTheOldParentRewindTheAcceptedPagerOwner() {
        assertTrue(
            explicitTransportStillAwaitingParent(
                desiredSongId = "B",
                parentSongId = "A",
                audioLoading = false,
                committedAudioSongId = "B"
            )
        )
        assertTrue(
            explicitTransportStillAwaitingParent(
                desiredSongId = "B",
                parentSongId = "A",
                audioLoading = true,
                committedAudioSongId = "A"
            )
        )
        assertFalse(
            explicitTransportStillAwaitingParent(
                desiredSongId = "B",
                parentSongId = "A",
                audioLoading = false,
                committedAudioSongId = "C"
            )
        )
        assertFalse(
            explicitTransportStillAwaitingParent(
                desiredSongId = "B",
                parentSongId = "B",
                audioLoading = false,
                committedAudioSongId = "B"
            )
        )
    }

    @Test
    fun playerTransportDispatchesOnlyThroughTheAuthoritativeQueueCommands() {
        val calls = mutableListOf<String>()

        assertEquals(
            "B",
            dispatchPlayerTransport(
                direction = TrackChangeDirection.NEXT,
                nextTrack = { calls += "next"; "B" },
                previousOrRestart = { calls += "previous-or-restart"; "A" }
            )
        )
        assertEquals(
            "A",
            dispatchPlayerTransport(
                direction = TrackChangeDirection.PREVIOUS,
                nextTrack = { calls += "next"; "B" },
                previousOrRestart = { calls += "previous-or-restart"; "A" }
            )
        )
        assertEquals(listOf("next", "previous-or-restart"), calls)
    }

    @Test
    fun previousRestartIsOnlyAConfirmedSameOwnerPreviousResult() {
        assertTrue(
            playerTransportRestartedCurrent(
                TrackChangeDirection.PREVIOUS,
                songIdBeforeCommand = "A",
                resultingSongId = "A"
            )
        )
        assertFalse(
            playerTransportRestartedCurrent(
                TrackChangeDirection.PREVIOUS,
                songIdBeforeCommand = "A",
                resultingSongId = "B"
            )
        )
        assertFalse(
            playerTransportRestartedCurrent(
                TrackChangeDirection.NEXT,
                songIdBeforeCommand = "A",
                resultingSongId = "A"
            )
        )
    }

    @Test
    fun rapidAuthoritativeResultsDriveOnlyTheLatestVisualPagerOwner() {
        val pages = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3)
        val cursor = ExplicitTransportCommandCursor()

        fun present(authoritativeId: String): PlayerTransportVisualTarget {
            val target = requireNotNull(playerTransportVisualTarget(authoritativeId, pages))
            cursor.issue(target.songId)
            return target
        }

        // AudioPlayback returns B, C, B while the pager can remain physically settled on A.
        assertEquals(PlayerTransportVisualTarget(index = 1, songId = "B"), present("B"))
        assertEquals(PlayerTransportVisualTarget(index = 2, songId = "C"), present("C"))
        assertEquals(PlayerTransportVisualTarget(index = 1, songId = "B"), present("B"))
        assertEquals("B", cursor.desiredSongId)
    }

    @Test
    fun unknownAuthoritativeIdNeverFallsBackToAnAdjacentLibrarySong() {
        val pages = mapOf("A" to 0, "B" to 1, "C" to 2)

        assertNull(playerTransportVisualTarget(null, pages))
        assertNull(playerTransportVisualTarget("not-in-visible-pager", pages))
    }

    @Test
    fun stalePagerCompletionCannotClearANewerExplicitTransportOwner() {
        val cursor = ExplicitTransportCommandCursor()
        val bToken = cursor.issue("B")
        val cToken = cursor.issue("C")

        assertFalse(cursor.owns(bToken, "B"))
        assertTrue(cursor.owns(cToken, "C"))
        assertFalse(cursor.settle("B"))
        assertEquals("C", cursor.desiredSongId)
        assertTrue(cursor.settle("C"))
        assertNull(cursor.desiredSongId)
    }

}
