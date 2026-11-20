package app.stemwise.playback

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlayerScrubOwnershipInstrumentationTest : LoopPlaybackFixture() {
    @Test fun metadataRebindRetiresTheExactScrubEvenAfterItsUiFlagClears() {
        val directory = File(application.cacheDir, "metadata-scrub-owner").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val audio = File(directory, "original.wav")
            writeSineWave(audio, 2_000L, 220.0)
            val song = Song(id = "metadata-scrub", videoId = "metadata-scrub", title = "Before",
                artist = "Fixture", durationMs = 2_000L, state = SongState.READY, accent = Color.Cyan,
                hasStems = false, artworkGlyph = "S", sourceHash = PlaybackContentFixture.sourceHash(audio),
            sourcePath = PlaybackContentFixture.publish(application, audio),
                sourceCodec = "pcm_s16le", sourceContainer = "wav")
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && !playback.state.value.loading }
            val sharedUiFlag = mutableStateOf(false)
            fun sink(current: Song, generation: Long) = OwnedPlayerActionSink(
                generation, playback.acquireControlLease(current.id), emptyArray(), emptyArray(), IntArray(0),
                playback, current, 2_000L, PlayerQueueProjection(listOf(current), listOf(current.id)),
                mutableLongStateOf(0L), sharedUiFlag, NativeFineWaveformOwner(), mutableStateOf(null),
                NativeMixerGestureOwner(), NativeLoopBoundaryGestureOwner(), scope,
                readFineWindowKey = { app.stemwise.ui.PlayerFineWaveformKey(
                    app.stemwise.ui.PlayerWaveformBinding(app.stemwise.ui.SongPresentationOwner.from(current),
                        PlaybackSourceMode.ORIGINAL, true, emptyList(), emptyMap()), null) },
                readFineWindow = { _, _ -> null }, expandPlayer = {}, collapsePlayer = {}, showSongInfo = {},
                selectPagerSong = { false }, changeTrack = { 0 }, skipArtwork = { false },
                changeShuffle = {}, selectSource = { false }, selectTool = { false }, onControlFailure = {})
            onMain {
                val old = sink(song, 1L)
                assertTrue(old.onScrubBegin(1L))
                val oldReceipt = checkNotNull(playback.activeScrubSessionId)
                val refreshed = song.copy(title = "After", mediaRevision = "metadata-2")
                playback.setPlaybackQueue(listOf(refreshed), snapshotReady = true)
                // Reproduces disposal/bind ordering: the outgoing View cancels after the UI reset.
                sharedUiFlag.value = false
                old.onScrubCancel(1L)
                assertNull(playback.activeScrubSessionId)
                val next = sink(refreshed, 2L)
                assertTrue(next.onScrubBegin(2L))
                val nextReceipt = checkNotNull(playback.activeScrubSessionId)
                assertNotEquals(oldReceipt, nextReceipt)
                old.retireScrub()
                old.onScrubCancel(1L)
                assertEquals(nextReceipt, playback.activeScrubSessionId)
                assertTrue(sharedUiFlag.value)
                assertFalse(old.onScrubPreview(1L, 1_800L))
                assertFalse(old.onScrubCommit(1L, 1_800L))
                assertEquals(nextReceipt, playback.activeScrubSessionId)
                assertTrue(next.onScrubCommit(2L, 500L))
                assertNull(playback.activeScrubSessionId)
                playback.play()
            }
            waitUntil(3_000L) { playback.state.value.positionMs > 650L }
            waitUntil(5_000L) { playback.state.value.ended }
        } finally {
            scope.cancel()
            // Retire the synthetic playback owner without closing the Library command gate.
            // The inherited @After must still prove and restore normal playback options.
            isolateAndCleanOwnedState()
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
