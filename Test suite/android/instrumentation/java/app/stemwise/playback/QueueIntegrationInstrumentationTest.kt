package app.stemwise.playback

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.session.SESSION_MEDIA_REVISION_KEY
import app.stemwise.playback.engine.NativeAudioPlaybackSessionLifecycle
import app.stemwise.qualification.isAndroidEmulator
import app.stemwise.qa.GoldenQaDocumentsProvider
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class QueueIntegrationInstrumentationTest {
    @Test fun sameOwnerOutputRecoveryClearsOnlyTheMatchingFailureNotice() {
        val source = File(application.cacheDir, "output-notice.wav")
        writeSilentWave(source, 8_000L)
        val song = song(0, source, 8_000L)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        waitForSong(song.id)
        val token = requireNotNull(playback.adapter.snapshot().latestRequest).selection.token
        onMain {
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .OutputAvailabilityChanged(token, false, "First physical open failed"))
            val failure = requireNotNull(playback.state.value.notice)
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .OutputAvailabilityChanged(app.stemwise.playback.engine.EngineSelectionToken(token.value + 1L), true, null))
            assertTrue(playback.state.value.notice === failure)
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .OutputAvailabilityChanged(token, true, null))
            assertNull(playback.state.value.notice)
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .OutputAvailabilityChanged(token, false, "Second physical open failed"))
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .LoopRejected(token, "An unrelated loop failure"))
            val other = requireNotNull(playback.state.value.notice)
            playback.handleAdapterEffect(app.stemwise.playback.engine.NativeAudioPlaybackSessionEffect
                .OutputAvailabilityChanged(token, true, null))
            assertTrue("Recovery must preserve a newer unrelated notice", playback.state.value.notice === other)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val playback
        get() = application.services.audioPlayback
    private val ownedSettingsSongs = linkedMapOf<SongPlaybackSettingsIdentity, Song>()

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @get:Rule
    val audioHealthAssertions = ErrorCollector()

    @Before
    fun isolatePlaybackBeforeTest() {
        runBlocking { withTimeout(30_000L) {
            application.workerInitialization.await()
            val roots = application.services.libraryRootManager
            if (!roots.state.value.ready) roots.initialize(application.services.preferences)
            if (!roots.state.value.ready) {
                val tree = GoldenQaDocumentsProvider.treeUri
                application.grantUriPermission(application.packageName, tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                application.services.librarySessionCoordinator.switchTo(
                    roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            }
            assertTrue(roots.state.value.ready)
        } }
        application.admitCurrentPlaybackLibrarySnapshot()
        isolateAndCleanOwnedState()
    }

    @After
    fun isolatePlaybackAfterTest() {
        isolateAndCleanOwnedState()
    }

    @Test
    fun fixtureCleanupClearsOnlyOwnedSettingsWhileSameCaseReselectionKeepsThem() {
        val source = File(application.cacheDir, "queue-settings-isolation.wav")
        writeSilentWave(source, 8_000L)
        val first = song(701, source, 8_000L)
        val second = song(702, source, 8_000L)
        val firstIdentity = ownedSettingsSongs.keys.single { it.songId == first.id }
        val secondIdentity = ownedSettingsSongs.keys.single { it.songId == second.id }
        val sentinel = firstIdentity.copy(songId = "queue-settings-sentinel-${System.nanoTime()}")
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        fun selectPaused(candidate: Song) {
            val oldToken = playback.adapter.snapshot().publication.committed?.selection?.token
            onMain { assertTrue(playback.selectSong(candidate, autoplay = false)) }
            waitUntil(12_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                playback.state.value.songId == candidate.id && !playback.state.value.loading &&
                    !playback.state.value.playing && committed != null && committed.selection.songId == candidate.id &&
                    committed.selection.token != oldToken
            }
        }
        try {
            val sentinelSettings = store.update(sentinel, first.durationMs) { it.copy(tempo = .75f, semitones = -2) }
            selectPaused(first)
            waitUntil(8_000L) { playback.state.value.loopAvailable }
            onMain {
                val lease = checkNotNull(playback.acquireControlLease(first.id))
                assertTrue(playback.setTempoAndPitch(1.25f, 3, lease))
                assertTrue(playback.setLoop(2_000L, 4_000L, lease))
            }
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(first) } }
            val authored = checkNotNull(store.read(firstIdentity, first.durationMs))
            assertEquals(1.25f, authored.tempo, 0f)
            assertEquals(3, authored.semitones)
            assertEquals(2_000L, authored.loop.pointA)
            assertEquals(4_000L, authored.loop.pointB)
            assertTrue(authored.loop.enabled)
            selectPaused(second)
            // Reusing the factory within this case must not erase an accepted preference.
            selectPaused(song(701, source, first.durationMs))
            waitUntil(8_000L) {
                playback.state.value.loopA == 2_000L && playback.state.value.loopB == 4_000L
            }
            assertEquals(1.25f, playback.state.value.tempo, 0f)
            assertEquals(3, playback.state.value.semitoneShift)
            val restored = checkNotNull(playback.adapter.snapshot().committedControls)
            assertEquals(1.25f, restored.tempo, 0f)
            assertEquals(3, restored.semitoneShift)
            assertEquals(2_000L, restored.loop?.startMs)
            assertEquals(4_000L, restored.loop?.endMs)
            assertEquals(first.sourceHash, PlaybackContentFixture.sourceHash(source))

            isolateAndCleanOwnedState()
            assertNull(store.read(firstIdentity, first.durationMs))
            assertNull(store.read(secondIdentity, second.durationMs))
            assertEquals(sentinelSettings, store.read(sentinel, first.durationMs))

            // A prior interrupted case may leave this exact synthetic row behind.
            store.update(firstIdentity, first.durationMs) { authored }
            writeSilentWave(source, first.durationMs)
            val recreated = song(701, source, first.durationMs)
            assertEquals(first.sourceHash, recreated.sourceHash)
            assertNull(store.read(firstIdentity, first.durationMs))
            selectPaused(recreated)
            assertEquals(1f, playback.state.value.tempo, 0f)
            assertEquals(0, playback.state.value.semitoneShift)
            assertNull(playback.state.value.loopA)
            assertNull(playback.state.value.loopB)
            val neutral = checkNotNull(playback.adapter.snapshot().committedControls)
            assertEquals(1f, neutral.tempo, 0f)
            assertEquals(0, neutral.semitoneShift)
            assertNull(neutral.loop)
            assertEquals(sentinelSettings, store.read(sentinel, first.durationMs))
        } finally {
            try {
                isolateAndCleanOwnedState()
            } finally {
                try { store.clear(firstIdentity) } finally { store.clear(sentinel) }
            }
        }
    }

    @Test
    fun verifiedEmptyLibraryRetiresTheSoleOwnerWhileStartupEmptyPreservesIt() {
        val source = File(application.cacheDir, "authoritative-empty.wav")
        writeSilentWave(source, 4_000L)
        val onlySong = song(0, source, 4_000L)
        onMain { playback.startQueue(listOf(onlySong), shuffle = false, autoplay = false) }
        waitForSong(onlySong.id)
        onMain {
            playback.setPlaybackQueue(emptyList(), snapshotReady = false)
            assertEquals(onlySong.id, playback.state.value.songId)
            assertEquals(onlySong.id, playback.graphMediaId())
            assertEquals(1, playback.media3Player().mediaItemCount)
        }
        onMain { playback.setPlaybackQueue(emptyList(), snapshotReady = true) }
        waitUntil(5_000L) { playback.state.value.songId == null }
        onMain {
            assertNull(playback.graphMediaId())
            assertEquals(0, playback.media3Player().mediaItemCount)
            assertNull(playback.media3Player().currentMediaItem)
            assertNull(playback.acquireControlLease(onlySong.id))
        }
    }

    @Test
    fun transientAudioFocusInterruptionPausesAndThenResumes() {
        Assume.assumeFalse(
            "Requires a physical-device audio focus arbiter",
            isAndroidEmulator()
        )
        val source = File(application.cacheDir, "audio-focus.wav")
        writeSilentWave(source, 4_000L)
        val focusSong = song(0, source, 4_000L)
        onMain {
            playback.startQueue(listOf(focusSong), shuffle = false, autoplay = false)
        }
        waitUntil(5_000L) {
            !playback.state.value.loading && playback.state.value.durationMs >= 3_900L
        }
        onMain { playback.toggle(focusSong) }
        waitUntil(5_000L) { playback.state.value.positionMs > 50L }
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = audioManager.requestAudioFocus(focusRequest)
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
            waitUntil(5_000L) { !playback.state.value.playing }
        } finally {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        waitUntil(5_000L) { playback.state.value.playing }
        val events = playback.playbackEventHistoryForTest()
        val loss = events.indexOfLast { "focus-change=${AudioManager.AUDIOFOCUS_LOSS_TRANSIENT} " in it }
        val gain = events.indexOfLast { "focus-change=${AudioManager.AUDIOFOCUS_GAIN} " in it }
        assertTrue("Focus history lost ordered loss/gain evidence: $events", loss >= 0 && gain > loss)
        assertTrue(events.any { "focus-gain-authorized=true" in it && "resumeToken=null" !in it })
        onMain { playback.pause() }
    }

    @Test
    fun delayedFocusOnPausedOwnerResumesNativeOutputOnlyAfterGain() {
        val source = File(application.cacheDir, "delayed-audio-focus.wav")
        writeSilentWave(source, 20_000L)
        val current = song(41, source, 20_000L)
        onMain { playback.startQueue(listOf(current), shuffle = false, autoplay = false) }
        waitUntil(8_000L) {
            playback.state.value.songId == current.id && !playback.state.value.loading
        }
        val framesBeforePlay = requireNotNull(
            playback.nativeDiagnosticsSnapshotForTest()?.output
        ).totalFramesAccepted
        onMain {
            playback.debug.delayNextAudioFocusRequest()
            assertTrue(playback.play())
            assertTrue(playback.state.value.playWhenReady)
            assertFalse(playback.state.value.playing)
            assertEquals(AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS,
                playback.state.value.suppression)
        }
        waitUntil(5_000L) {
            playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == false
        }
        onMain { playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN) }
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            playback.state.value.playing &&
                playback.state.value.suppression == AudioPlaybackSuppression.NONE &&
                playback.state.value.positionMs > 50L &&
                native?.output?.playing == true &&
                native.output.totalFramesAccepted > framesBeforePlay
        }
        assertTrue(playback.playbackEventHistoryForTest().any {
            "focus-gain-authorized=true" in it && "resumeToken=null" !in it
        })
        onMain { playback.pause() }
    }

    @Test
    fun inAppExternalAndAutomaticTransitionsShareTheMedia3Queue() {
        val source = File(application.cacheDir, "queue-transition.wav")
        writeSilentWave(source, 2_000L)
        val songs = (0 until 4).map { index -> song(index, source, 2_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitForSong("song-0")
        waitUntil(8_000L) { PlaybackService.isActiveForTest() }
        waitUntil(8_000L) { "song-1" in playback.preparedQueueMediaIds() }

        val cachedNextStartedAt = SystemClock.elapsedRealtime()
        onMain { assertEquals("song-1", playback.nextTrack()) }
        waitForSong("song-1")
        waitUntil(750L) { playback.state.value.playing }
        assertTrue(
            "Prepared next owner did not begin inside the local transition budget",
            SystemClock.elapsedRealtime() - cachedNextStartedAt <= 750L
        )
        waitUntil(8_000L) { "song-2" in playback.preparedQueueMediaIds() }

        onMain { playback.media3Player().seekToNextMediaItem() }
        waitForSong("song-2")

        onMain { playback.media3Player().play() }
        waitUntil(4_000L) { playback.state.value.playing }
        onMain { playback.seekTo(1_900L) }
        waitForSong("song-3")

        onMain {
            playback.setRepeatMode("Off")
            playback.seekTo(2_000L)
        }
        waitUntil(4_000L) {
            playback.state.value.songId == "song-3" &&
                playback.state.value.ended &&
                !playback.state.value.playWhenReady
        }

        onMain {
            playback.setRepeatMode("All")
            playback.media3Player().play()
            playback.seekTo(2_000L)
        }
        waitForSong("song-0")

        onMain {
            playback.selectSong(songs[1], autoplay = true)
            playback.setRepeatMode("One")
            playback.seekTo(2_000L)
        }
        waitUntil(4_000L) {
            playback.state.value.songId == "song-1" &&
                playback.state.value.positionMs < 1_900L
        }
        assertEquals(0L, playback.graphDiagnostics().requestedTransportPositionMs)
        onMain { playback.pause() }
    }

    @Test
    fun newlyPublishedLibrarySelectionRemainsTheQueueOwner() {
        val source = File(application.cacheDir, "selection-owner.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 4).map { index -> song(index, source, 8_000L) }

        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs.take(2), shuffle = false, autoplay = false)
            // Models a tap on a Room emission that Compose has rendered just before the
            // playback queue's LaunchedEffect receives that same expanded list.
            playback.selectSong(songs[2], autoplay = false)
            playback.setPlaybackQueue(songs, snapshotReady = true)
        }
        waitForSong("song-2")
        assertEquals("song-2", playback.graphMediaId())

        onMain { assertEquals("song-3", playback.nextTrack()) }
        waitForSong("song-3")
        onMain { assertEquals("song-2", playback.previousTrack()) }
        waitForSong("song-2")
        onMain { assertEquals("song-1", playback.previousTrack()) }
        waitForSong("song-1")
        onMain { playback.pause() }
    }

    @Test
    fun libraryPlayerAndMediaSessionShuffleRoutesShareOneCompleteQueue() {
        val source = File(application.cacheDir, "shuffle-entry-paths.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 16).map { index -> song(index, source, 8_000L) }
        val eligibleIds = songs.map(Song::id)

        // StemwiseApp's Library onShuffle calls this exact full-library entry point.
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(
                songs = songs,
                shuffle = true,
                autoplay = false,
                seed = 91L
            )
        }
        waitUntil(8_000L) {
            !playback.state.value.loading && playback.state.value.songId != null
        }
        val freshShuffle = playback.state.value.queueOrder
        assertEquals(eligibleIds.size, freshShuffle.size)
        assertEquals(eligibleIds.toSet(), freshShuffle.toSet())
        assertEquals(eligibleIds.size, freshShuffle.distinct().size)
        assertTrue(playback.state.value.shuffleEnabled)

        // PlayerScreen's shuffle button calls setShuffle on the existing queue. Model an
        // arbitrary library/pager jump first: its canonical index is not playback history.
        onMain {
            playback.startQueue(songs, shuffle = false, autoplay = false, seed = 123L)
        }
        waitForSong("song-0")
        onMain { playback.selectSong(songs[12], autoplay = false) }
        waitForSong("song-12")
        waitUntil(8_000L) { PlaybackService.isActiveForTest() }

        onMain { playback.setShuffle(true, seed = 123L) }
        val afterPlayerToggle = playback.state.value.queueOrder
        assertEquals("song-12", afterPlayerToggle.first())
        assertEquals("song-12", playback.state.value.songId)
        assertEquals(eligibleIds.toSet(), afterPlayerToggle.toSet())
        assertEquals(eligibleIds.size, afterPlayerToggle.distinct().size)

        // Bluetooth/notification shuffle is MediaSession -> AudioPlaybackSessionTransport ->
        // the same AudioPlayback.setShuffle and PlaybackQueueController path.
        onMain { playback.setShuffle(false) }
        assertEquals(eligibleIds, playback.state.value.queueOrder)
        onMain { playback.media3Player().setShuffleModeEnabled(true) }
        waitUntil(5_000L) { playback.state.value.shuffleEnabled }
        val afterSessionToggle = playback.state.value.queueOrder
        assertEquals("song-12", afterSessionToggle.first())
        assertEquals(eligibleIds.toSet(), afterSessionToggle.toSet())
        assertEquals(eligibleIds.size, afterSessionToggle.distinct().size)
        onMain {
            playback.setShuffle(false)
            playback.pause()
        }
    }

    @Test
    fun singleSongRepeatAllRestartsAtNaturalCompletion() {
        val source = File(application.cacheDir, "single-repeat-all.wav")
        writeSilentWave(source, 1_000L)
        val onlySong = song(0, source, 1_000L)
        onMain {
            playback.setRepeatMode("All")
            playback.startQueue(listOf(onlySong), shuffle = false, autoplay = true)
        }
        waitUntil(5_000L) {
            playback.state.value.songId == onlySong.id &&
                playback.state.value.positionMs >= 700L
        }
        waitUntil(5_000L) {
            playback.state.value.songId == onlySong.id &&
                playback.state.value.playWhenReady &&
                !playback.state.value.ended &&
                playback.state.value.positionMs < 500L
        }
        assertEquals(0L, playback.graphDiagnostics().requestedTransportPositionMs)
        onMain { playback.pause() }
    }

    @Test
    fun terminalRepeatOffDrainsNonBurstAlignedTailAndReplaysWithoutAudioFaults() {
        val exactFrames = 48_127L
        val sampleRate = 48_000
        val durationMs = (exactFrames * 1_000L + sampleRate - 1L) / sampleRate
        val source = File(application.cacheDir, "terminal-non-burst-aligned.wav")
        writeExactStereoWave(source, exactFrames.toInt(), sampleRate)
        val terminalSong = song(91, source, durationMs)
        val baselineNative = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
        val baselineOutput = requireNotNull(baselineNative.output)
        val baselineJournal = playback.presentationJournalForTest(0L)

        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(listOf(terminalSong), shuffle = false, autoplay = true)
        }

        var terminalToken = 0L
        var terminalGeneration = 0L
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val owner = native?.runtime?.ownership?.committed
            val output = native?.output
            if (owner != null &&
                playback.state.value.songId == terminalSong.id &&
                playback.state.value.playing &&
                output?.lastPresentedSelectionToken == owner.selectionToken &&
                output.lastPresentedGeneration == owner.generation
            ) {
                terminalToken = owner.selectionToken
                terminalGeneration = owner.generation
                true
            } else {
                false
            }
        }

        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val end = native?.runtime?.audibleEnd
            playback.state.value.songId == terminalSong.id &&
                playback.state.value.ended &&
                !playback.state.value.playWhenReady &&
                !playback.state.value.playing &&
                end?.ownership?.selectionToken == terminalToken &&
                end.ownership.generation == terminalGeneration &&
                native.output?.effectiveRenderPlaying == false
        }

        val endedNative = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
        val endedOutput = requireNotNull(endedNative.output)
        val endedPosition = requireNotNull(endedNative.runtime.presentedPosition)
        assertEquals(terminalToken, endedPosition.ownership.selectionToken)
        assertEquals(terminalGeneration, endedPosition.ownership.generation)
        assertEquals(exactFrames - 1L, endedPosition.lastTimelineFrame)
        assertEquals(exactFrames - 1L, endedOutput.lastPresentedTimelineFrame)

        val terminalJournal = playback.presentationJournalForTest(baselineJournal.writeSequence)
        assertTrue("Terminal journal was incoherent: $terminalJournal", terminalJournal.coherent)
        // Overflow is relative to the requested cursor, not a process-cumulative counter.
        // Earlier suite history may have wrapped; this exact terminal window must lose nothing.
        assertEquals(0L, terminalJournal.overflowCount)
        assertEquals(baselineJournal.writerCollisionCount, terminalJournal.writerCollisionCount)
        assertEquals(baselineJournal.invalidPublicationCount, terminalJournal.invalidPublicationCount)
        val terminalSpans = terminalJournal.entries.filter {
            it.selectionToken == terminalToken && it.generation == terminalGeneration
        }
        assertEquals(exactFrames, terminalSpans.sumOf { it.pcmFrames })
        // Poll actual consumption; output preparation and presentation advance independently.
        val finalSpan = terminalSpans.last()
        val finalCallbackFrames = terminalSpans.takeLastWhile {
            it.callbackSequence == finalSpan.callbackSequence
        }.sumOf { it.pcmFrames }
        assertTrue("Terminal callback published no owned PCM", finalCallbackFrames > 0L)
        assertEquals(terminalToken, endedOutput.pcmLevelSelectionToken)
        assertEquals(terminalGeneration, endedOutput.pcmLevelGeneration)
        assertEquals(finalSpan.callbackSequence, endedOutput.pcmLevelCallbackSequence)
        assertEquals(finalSpan.lastFingerprintSequence, endedOutput.pcmLevelFingerprintSequence)
        // A push block can be presented in several prefixes with the same callback sequence.
        // The level watermark and callbackPcmFrames describe the newest exact publication.
        assertTrue("Terminal level publication must contain real owned PCM",
            finalSpan.callbackPcmFrames in 1L..finalCallbackFrames)
        assertEquals(finalSpan.callbackPcmFrames, endedOutput.pcmLevelFrames)
        assertTrue(
            "Final watermark did not reach the callback: peak=${endedOutput.pcmLevelPeak}",
            endedOutput.pcmLevelPeak > 0.25f
        )
        assertTerminalAudioHealthUnchanged(baselineNative, endedNative, "natural terminal tail")

        SystemClock.sleep(300L)
        val stableNative = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
        val stableOutput = requireNotNull(stableNative.output)
        val lateJournal = playback.presentationJournalForTest(terminalJournal.writeSequence)
        assertTrue(lateJournal.entries.none {
            it.selectionToken == terminalToken && it.generation == terminalGeneration
        })
        assertTrue(!stableOutput.effectiveRenderPlaying)
        assertTerminalAudioHealthUnchanged(baselineNative, stableNative, "terminal hold")

        val replayJournalHead = lateJournal.writeSequence
        onMain { playback.play() }
        var replayToken = 0L
        var replayGeneration = 0L
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val owner = native?.runtime?.ownership?.committed
            val output = native?.output
            if (owner != null &&
                playback.state.value.songId == terminalSong.id &&
                playback.state.value.playing &&
                playback.state.value.playWhenReady &&
                !playback.state.value.ended &&
                playback.state.value.positionMs in 20L until durationMs &&
                owner.generation != terminalGeneration &&
                output?.effectiveRenderPlaying == true &&
                output.lastPresentedSelectionToken == owner.selectionToken &&
                output.lastPresentedGeneration == owner.generation
            ) {
                replayToken = owner.selectionToken
                replayGeneration = owner.generation
                true
            } else {
                false
            }
        }
        val replayJournal = playback.presentationJournalForTest(replayJournalHead)
        assertTrue(replayJournal.coherent)
        assertTrue(replayJournal.entries.any {
            it.selectionToken == replayToken && it.generation == replayGeneration
        })
        val replayNative = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
        assertTerminalAudioHealthUnchanged(baselineNative, replayNative, "same-song replay")
        Log.i(
            "StemwiseTerminalEos",
            JSONObject()
                .put("frames", exactFrames)
                .put("lastTimelineFrame", endedPosition.lastTimelineFrame)
                .put("tailFrames", finalCallbackFrames)
                .put("terminalGeneration", terminalGeneration)
                .put("replayGeneration", replayGeneration)
                .toString()
        )
        onMain { playback.pause() }
    }

    @Test
    fun hundredRapidTransitionsHaveOneNavigationEachAndBoundedMemory() {
        val source = File(application.cacheDir, "rapid-transition.wav")
        writeSilentWave(source, 2_000L)
        val songs = (0 until 100).map { index -> song(index, source, 2_000L) }
        var firstSongId: String? = null
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = true, autoplay = true, seed = 44L)
            firstSongId = playback.currentQueueSongId()
        }
        // Play intent can precede codec/cache allocation. Measure the 99 transitions from a live owner.
        waitForSong(requireNotNull(firstSongId))
        waitUntil(5_000L) { playback.state.value.playing && !playback.state.value.loading }
        val beforePssKb = Debug.getPss()
        val returnedIds = mutableListOf<String>()
        onMain {
            repeat(99) {
                returnedIds += requireNotNull(playback.nextTrack())
            }
        }
        assertEquals(99, returnedIds.size)
        assertEquals(99, returnedIds.distinct().size)
        val finalId = returnedIds.last()
        waitForSong(finalId)
        assertTrue(playback.state.value.playWhenReady)
        SystemClock.sleep(500L)
        val afterPssKb = Debug.getPss()
        val growthKb = afterPssKb - beforePssKb
        assertTrue("PSS grew by $growthKb KB", growthKb < 48 * 1_024)
        Log.i(
            "StemwiseQueueQualification",
            JSONObject()
                .put("rapidTransitions", 99)
                .put("uniqueDestinations", returnedIds.distinct().size)
                .put("finalSongId", finalId)
                .put("pssBeforeKb", beforePssKb)
                .put("pssAfterKb", afterPssKb)
                .put("pssGrowthKb", growthKb)
                .toString()
        )
        onMain { playback.pause() }
    }

    @Test
    fun blockedResolversAreCancelledWhileImmediateNavigationKeepsTheLatestTargetAndPauseIntent() = runBlocking {
        val source = File(application.cacheDir, "blocked-navigation.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 4).map { song(it, source, 8_000L) }
        onMain { playback.startQueue(songs, shuffle = false, autoplay = false) }
        waitForSong(songs[0].id)
        waitUntil(8_000L) { songs[1].id in playback.preparedQueueMediaIds() }
        val verify = checkNotNull(playback.debug.verifyPlaybackSong)
        val blocked = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            playback.debug.verifyPlaybackSong = { requested ->
                if (requested.id == songs[1].id) {
                    blocked.complete(Unit)
                    try { withTimeout(10_000L) { release.await() } }
                    finally { cancelled.complete(Unit) }
                }
                verify(requested)
            }
            onMain { assertTrue(playback.selectSong(songs[1], autoplay = true)) }
            withTimeout(10_000L) { blocked.await() }
            onMain {
                assertEquals(songs[0].id, playback.selectionPressureDiagnosticsForTest().committedSongId)
                playback.pause()
                assertEquals(songs[2].id, playback.nextTrack())
                assertEquals(songs[3].id, playback.nextTrack())
            }
            withTimeout(10_000L) { cancelled.await() }
            release.complete(Unit)
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
            waitForSong(songs[3].id)
            assertFalse(playback.state.value.playWhenReady)
            assertEquals(songs[3].id, playback.selectionPressureDiagnosticsForTest().latestRequestedSongId)
            onMain { playback.selectSong(songs[0], autoplay = false) }
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
            waitForSong(songs[0].id)
            playback.debug.verifyPlaybackSong = { requested ->
                check(requested.id != songs[1].id) { "Unavailable test source" }
                verify(requested)
            }
            onMain { assertEquals(songs[1].id, playback.nextTrack()) }
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
            onMain { assertEquals(songs[2].id, playback.nextTrack()) }
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
            waitForSong(songs[2].id)
            assertFalse(playback.state.value.playWhenReady)
        } finally {
            release.complete(Unit)
            playback.debug.verifyPlaybackSong = verify
        }
    }

    @Test
    fun rapidNextNextPreviousUsesThePendingOwnerAndLatestTransportIntent() {
        val source = File(application.cacheDir, "rapid-pending-navigation.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 5).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong("song-0")
        onMain { playback.seekTo(4_000L) }
        waitUntil(5_000L) { playback.state.value.positionMs >= 3_500L }

        onMain {
            assertEquals("song-1", playback.nextTrack())
            // First press restarts pending B, then a second press within two seconds returns to A.
            // The outgoing A position must never be used to restart or seek the wrong owner.
            assertEquals("song-1", playback.previousOrRestart())
            assertEquals("song-0", playback.previousOrRestart())
            assertEquals("song-1", playback.nextTrack())
            assertEquals("song-2", playback.nextTrack())
            // A new Next starts a fresh pair. Both presses address pending song-2, rather than A.
            assertEquals("song-2", playback.previousOrRestart())
            assertEquals("song-1", playback.previousOrRestart())
        }

        runBlocking { withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() } }
        waitForSong("song-1")
        assertEquals(0L, requireNotNull(playback.latestRequestedControlsForTest()).requestedPositionMs)
        val pressure = playback.selectionPressureDiagnosticsForTest()
        assertEquals("song-1", pressure.latestRequestedSongId)
        assertTrue(pressure.pendingSelectionCount <= 1)
        assertTrue(!playback.state.value.playWhenReady)
        assertEquals("song-1", playback.selectionPressureDiagnosticsForTest().queueCurrentId)
        onMain { playback.pause() }
    }

    @Test
    fun pendingOwnerControlsMutateOneTokenAndRestartItsRequestedPosition() {
        val source = File(application.cacheDir, "pending-owner-controls.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong("song-0")

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        try {
            onMain {
                playback.selectSong(songs[1], autoplay = false, positionMs = 4_000L)
            }
            runBlocking { withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                val selected = playback.selectionPressureDiagnosticsForTest()
                val token = requireNotNull(selected.latestRequestedToken)
                assertEquals("song-1", selected.latestRequestedSongId)
                assertTrue(selected.committedToken == null)

                playback.play()
                assertTrue(requireNotNull(playback.latestRequestedControlsForTest()).playWhenReady)
                playback.seekTo(5_000L)
                // Toggling the same pending song changes its desired transport; it must not
                // create a second selection token merely because public identity is hidden.
                playback.toggle(songs[1])
                val controlled = requireNotNull(playback.latestRequestedControlsForTest())
                assertEquals(5_000L, controlled.requestedPositionMs)
                assertTrue(!controlled.playWhenReady)
                assertEquals(token, playback.selectionPressureDiagnosticsForTest().latestRequestedToken)

                // Pending B is past the restart threshold, so restart seeks B itself to zero.
                assertEquals("song-1", playback.previousOrRestart())
                val restarted = requireNotNull(playback.latestRequestedControlsForTest())
                assertEquals(0L, restarted.requestedPositionMs)
                assertTrue(!restarted.playWhenReady)
                assertEquals(token, playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitForSong("song-1")
        assertTrue(!playback.state.value.playWhenReady)
        onMain { playback.pause() }
    }

    @Test
    fun removingOutgoingSongPreservesTheNewerLookupPositionAndPausedIntent() = runBlocking {
        val source = File(application.cacheDir, "outgoing-removal.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 2).map { song(it, source, 8_000L) }
        onMain { playback.startQueue(songs, shuffle = false, autoplay = true) }
        waitForSong(songs[0].id)
        waitUntil(8_000L) { songs[1].id in playback.preparedQueueMediaIds() }
        val verify = requireNotNull(playback.debug.verifyPlaybackSong)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        playback.debug.verifyPlaybackSong = { requested ->
            val verified = verify(requested)
            if (requested.id == songs[1].id) {
                entered.complete(Unit)
                withTimeout(10_000L) { release.await() }
            }
            verified
        }
        try {
            onMain { assertTrue(playback.selectSong(songs[1], autoplay = true, positionMs = 3_210L)) }
            withTimeout(10_000L) { entered.await() }
            onMain {
                playback.pause()
                playback.setPlaybackQueue(listOf(songs[1]), snapshotReady = true)
                assertNotNull(playback.selectionPressureDiagnosticsForTest().resolvingRequestSerial)
            }
            release.complete(Unit)
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
            waitForSong(songs[1].id)
            assertFalse(playback.state.value.playWhenReady)
            assertEquals(3_210L, playback.state.value.positionMs)
            assertEquals(listOf(songs[1].id), playback.state.value.queueOrder)
        } finally {
            release.complete(Unit)
            playback.debug.verifyPlaybackSong = verify
            withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
        }
    }

    @Test
    fun cancelledUnpublishedManualSelectionKeepsTheExactOutgoingOwner() = runBlocking {
        val source = File(application.cacheDir, "unpublished-owner-cancellation.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 2).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong(songs[0].id)
        // Finish existing prewarm before wrapping the shared verifier: the gate below belongs
        // to the next explicit request, after its actual fixture provider read has succeeded.
        waitUntil(8_000L) { songs[1].id in playback.preparedQueueMediaIds() }
        val verify = requireNotNull(playback.debug.verifyPlaybackSong)
        try {
            for (removeFromQueue in listOf(false, true)) {
                val validated = CompletableDeferred<Unit>()
                val continueResolution = CompletableDeferred<Unit>()
                playback.debug.verifyPlaybackSong = { requested ->
                    val verified = verify(requested)
                    if (requested.id == songs[1].id) {
                        validated.complete(Unit)
                        withTimeout(10_000L) { continueResolution.await() }
                    }
                    verified
                }
                try {
                    var outgoingToken: Long? = null
                    onMain {
                        playback.setPlaybackQueue(songs, snapshotReady = true)
                        outgoingToken = playback.selectionPressureDiagnosticsForTest().committedToken
                        assertTrue(playback.selectSong(songs[1], autoplay = false))
                    }
                    withTimeout(10_000L) { validated.await() }
                    onMain {
                        assertTrue(playback.selectionPressureDiagnosticsForTest().resolvingRequestSerial != null)
                        if (removeFromQueue) {
                            playback.setPlaybackQueue(listOf(songs[0]), snapshotReady = true)
                        } else {
                            playback.unload(songs[1].id)
                        }
                        val cancelled = playback.selectionPressureDiagnosticsForTest()
                        assertNull("Unpublished target retained admission after cancellation", cancelled.resolvingRequestSerial)
                        assertEquals(songs[0].id, cancelled.committedSongId)
                        assertEquals(outgoingToken, cancelled.committedToken)
                        assertFalse(playback.state.value.loading)
                    }
                    continueResolution.complete(Unit)
                    withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
                    onMain {
                        val settled = playback.selectionPressureDiagnosticsForTest()
                        assertEquals(songs[0].id, settled.latestRequestedSongId)
                        assertEquals(songs[0].id, settled.committedSongId)
                        assertEquals(outgoingToken, settled.committedToken)
                        assertEquals(songs[0].id, settled.queueCurrentId)
                        if (removeFromQueue) assertFalse(songs[1].id in playback.state.value.queueOrder)
                    }
                } finally {
                    continueResolution.complete(Unit)
                    playback.debug.verifyPlaybackSong = verify
                    withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() }
                }
            }
        } finally { playback.debug.verifyPlaybackSong = verify }
    }

    @Test
    fun unloadingThePendingSongCancelsItBeforeDecodeCanSurviveDeletion() {
        val source = File(application.cacheDir, "pending-owner-unload.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 2).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong("song-0")

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        try {
            onMain { assertTrue(playback.selectSong(songs[1], autoplay = false)) }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                val pending = playback.selectionPressureDiagnosticsForTest()
                assertEquals("song-1", pending.latestRequestedSongId)
                assertTrue(pending.committedSongId == null)
                playback.unload("song-1")
                assertTrue(playback.state.value.songId == null)
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitUntil(8_000L) {
            val selection = playback.selectionPressureDiagnosticsForTest()
            selection.latestRequestedSongId == null &&
                selection.committedSongId == null &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == false
        }
    }

    @Test
    fun pendingAutoplayProjectsPauseIntentAndToggleDoesNotReselect() {
        val source = File(application.cacheDir, "pending-autoplay-intent.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 2).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong("song-0")

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        var pendingToken: Long? = null
        try {
            onMain { assertTrue(playback.selectSong(songs[1], autoplay = true)) }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                pendingToken = checkNotNull(playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
            }
            waitUntil(3_000L) {
                playback.state.value.loading &&
                    playback.state.value.playWhenReady &&
                    !playback.state.value.playing
            }
            onMain {
                playback.toggle(songs[1])
                assertEquals(
                    pendingToken,
                    playback.selectionPressureDiagnosticsForTest().latestRequestedToken
                )
                assertTrue(!requireNotNull(playback.latestRequestedControlsForTest()).playWhenReady)
            }
            waitUntil(3_000L) { !playback.state.value.playWhenReady }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitForSong("song-1")
        assertTrue(!playback.state.value.playWhenReady)
        onMain { playback.pause() }
    }

    @Test
    fun removingThePendingOwnerSupersedesItWithCanonicalQueueMembership() {
        val source = File(application.cacheDir, "pending-owner-library-removal.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong(songs[0].id)

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        var removedToken: Long? = null
        try {
            onMain { assertTrue(playback.selectSong(songs[1], autoplay = true)) }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                removedToken = checkNotNull(playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
                playback.setPlaybackQueue(listOf(songs[0], songs[2]), shuffle = false, snapshotReady = true)
                assertTrue(songs[1].id !in playback.state.value.queueOrder)
            }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                val pressure = playback.selectionPressureDiagnosticsForTest()
                assertTrue(pressure.latestRequestedSongId in setOf(songs[0].id, songs[2].id))
                assertTrue(pressure.queueCurrentId in setOf(songs[0].id, songs[2].id))
                assertTrue(pressure.latestRequestedToken != removedToken)
                assertTrue(songs[1].id !in playback.state.value.queueOrder)
                assertTrue(playback.sessionMediaItem()?.mediaId in setOf(songs[0].id, songs[2].id))
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitUntil(12_000L) {
            val selectedId = playback.state.value.songId
            selectedId in setOf(songs[0].id, songs[2].id) &&
                playback.graphMediaId() == selectedId &&
                playback.sessionMediaItem()?.mediaId == selectedId
        }
        SystemClock.sleep(300L)
        assertTrue(playback.state.value.songId != songs[1].id)
        assertTrue(playback.graphMediaId() != songs[1].id)
        assertTrue(playback.sessionMediaItem()?.mediaId != songs[1].id)
        onMain { playback.pause() }
    }

    @Test
    fun failedQueueItemCanBeSkippedToTheNextValidSongWithIntentPreserved() {
        val source = File(application.cacheDir, "failed-selection-navigation-valid.wav")
        writeSilentWave(source, 8_000L)
        val missingSource = File(
            application.cacheDir,
            "failed-selection-navigation-missing-${System.nanoTime()}.wav"
        )
        // Publish a real SAF document first, then remove that exact document. Production rejects
        // raw private paths and the fixture publisher correctly rejects nonexistent source files;
        // deletion after publication preserves the intended provider-side missing-source oracle.
        writeSilentWave(missingSource, 8_001L)
        val missingSong = song(1, missingSource, 8_000L)
        assertTrue(
            "The synthetic missing-source document was not removed",
            DocumentsContract.deleteDocument(
                application.contentResolver,
                Uri.parse(requireNotNull(missingSong.sourcePath))
            )
        )
        val songs = listOf(
            song(0, source, 8_000L),
            missingSong,
            song(2, source, 8_000L)
        )
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitForSong(songs[0].id)
        waitUntil(8_000L) { playback.state.value.playing }
        val outgoingToken = checkNotNull(playback.selectionPressureDiagnosticsForTest().committedToken)

        onMain {
            assertEquals(songs[1], playback.startQueue(songs, shuffle = false,
                autoplay = true, startId = songs[1].id))
        }
        waitUntil(12_000L) {
            val pressure = playback.selectionPressureDiagnosticsForTest()
            !playback.state.value.loading &&
                playback.state.value.notice != null &&
                pressure.resolvingRequestSerial == null &&
                pressure.queueCurrentId == songs[0].id &&
                pressure.latestRequestedSongId == songs[0].id &&
                pressure.committedSongId == songs[0].id &&
                pressure.committedToken == outgoingToken
        }
        onMain {
            assertEquals("Preflight failure replaced the audible graph", songs[0].id, playback.graphMediaId())
            assertEquals("Preflight failure replaced the media session", songs[0].id, playback.sessionMediaItem()?.mediaId)
            assertTrue(playback.state.value.playing)
            assertTrue(
                "Failed autoplay selection dropped focus before immediate queue navigation",
                playback.playbackAudioFocusOwnedForTest()
            )
        }

        onMain { assertEquals(songs[2].id, playback.nextTrack()) }
        waitUntil(12_000L) {
            playback.state.value.songId == songs[2].id &&
                playback.state.value.playWhenReady &&
                playback.graphMediaId() == songs[2].id &&
                playback.sessionMediaItem()?.mediaId == songs[2].id
        }
        onMain { playback.pause() }
    }

    @Test fun failedDistantQueueStartCannotRedirectTheCommittedOwnersAutomaticAdvance() {
        val source = File(application.cacheDir, "failed-distant-selection.wav")
        val missing = File(application.cacheDir, "failed-distant-missing.wav")
        writeSilentWave(source, 30_000L)
        writeSilentWave(missing, 30_001L)
        val songs = (0 until 5).map { song(it, if (it == 3) missing else source, 30_000L) }
        assertTrue(DocumentsContract.deleteDocument(application.contentResolver, Uri.parse(requireNotNull(songs[3].sourcePath))))
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitForSong(songs[0].id)
        onMain { playback.startQueue(songs, shuffle = false, autoplay = true, startId = songs[3].id) }
        waitUntil(8_000L) { !playback.state.value.loading && playback.state.value.notice != null }
        assertEquals(songs[0].id, playback.selectionPressureDiagnosticsForTest().queueCurrentId)
        onMain { assertTrue(playback.debug.enqueueCurrentAudibleEnd()) }
        waitUntil(8_000L) { playback.selectionPressureDiagnosticsForTest().latestRequestedSongId != songs[0].id }
        assertEquals("Automatic fallback belongs to A, not the failed distant D",
            songs[1].id, playback.selectionPressureDiagnosticsForTest().latestRequestedSongId)
        waitForSong(songs[1].id)
        onMain { playback.startQueue(songs, shuffle = false, autoplay = true, startId = songs[3].id) }
        waitUntil(8_000L) { !playback.state.value.loading && playback.state.value.notice != null }
        onMain { assertEquals("Explicit Next may still skip the failed D", songs[4].id, playback.nextTrack()) }
        waitForSong(songs[4].id)
        onMain { playback.pause() }
    }

    @Test
    fun permanentDeletionRetiresAPrimedSuccessorBeforeProviderMutation() = runBlocking {
        val source = File(application.cacheDir, "permanent-delete-successor.wav")
        writeSilentWave(source, 3_000L)
        val songs = (0 until 3).map { song(it, source, 3_000L) }
        onMain { assertNotNull(playback.startQueue(songs, shuffle = false, autoplay = true)) }
        waitForSong(songs[0].id)
        waitUntil(8_000L) {
            playback.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor?.let {
                it.successorOwnership != null && it.successorQueuedFrames > 0L
            } == true
        }
        val staged = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor?.successorOwnership)
        val cursor = playback.presentationJournalForTest(Long.MAX_VALUE).writeSequence
        val root = checkNotNull(application.services.libraryRootManager.captureAuthority())
        val admitted = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val deletion = async {
            playback.withPermanentSongDeletion(root, songs[1].id) { onExactRootAdmitted ->
                onExactRootAdmitted()
                admitted.complete(Unit)
                finish.await()
                Result.success(Unit)
            }
        }
        try {
            withTimeout(12_000L) { admitted.await() }
            waitUntil(8_000L) {
                playback.state.value.ended || playback.state.value.songId == songs[2].id ||
                    playback.nativeDiagnosticsSnapshotForTest()?.runtime?.audibleEnd != null
            }
            assertFalse(playback.state.value.songId == songs[1].id)
            assertTrue(playback.presentationJournalForTest(cursor).entries.none {
                it.selectionToken == staged.selectionToken
            })
            finish.complete(Unit)
            assertTrue(withTimeout(5_000L) { deletion.await() }.isSuccess)
            assertFalse(songs[1].id in playback.state.value.queueOrder)
            assertTrue(songs[0].id in playback.state.value.queueOrder)
            assertTrue(songs[2].id in playback.state.value.queueOrder)
        } finally {
            finish.complete(Unit)
            deletion.cancelAndJoin()
        }
    }

    @Test
    fun pauseRetiresPlayDeferredBehindAnAdministrativeStop() {
        val source = File(application.cacheDir, "stopping-rebind-pause.wav")
        writeSilentWave(source, 8_000L)
        val song = song(0, source, 8_000L)
        onMain { assertNotNull(playback.startQueue(listOf(song), shuffle = false, autoplay = false)) }
        waitForSong(song.id)
        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        try {
            onMain {
                playback.unload(song.id)
                assertEquals(NativeAudioPlaybackSessionLifecycle.STOPPING, playback.adapter.snapshot().lifecycle)
                assertTrue(playback.toggleCurrentOrRebind(song))
                assertTrue(playback.pause())
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }
        waitUntil(12_000L) {
            playback.state.value.songId == song.id && !playback.state.value.loading &&
                playback.adapter.snapshot().lifecycle == NativeAudioPlaybackSessionLifecycle.ACTIVE
        }
        assertFalse(playback.state.value.playWhenReady)
        assertFalse(playback.state.value.playing)
        assertFalse(playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == true)
    }

    @Test
    fun independentTransportStartsANewPreviousPairButItsOwnRestartPreservesThePair() {
        val source = File(application.cacheDir, "previous-intervening-transport.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { song(it, source, 8_000L) }
        for (action in listOf("pause", "play", "seek", "scrub")) {
            onMain { assertNotNull(playback.startQueue(songs, shuffle = false, autoplay = false, startId = songs[1].id)) }
            waitForSong(songs[1].id)
            onMain {
                assertEquals(songs[1].id, playback.previousOrRestart())
                assertTrue(when (action) {
                    "pause" -> playback.pause()
                    "play" -> playback.play()
                    "scrub" -> playback.beginScrub(checkNotNull(playback.acquireControlLease(songs[1].id))).also {
                        if (it) playback.commitScrub(3_000L)
                    }
                    else -> playback.seekTo(3_000L)
                })
                assertEquals("$action must retire the first Previous press", songs[1].id, playback.previousOrRestart())
                assertEquals("The next consecutive Previous must still skip", songs[0].id, playback.previousOrRestart())
            }
            waitForSong(songs[0].id)
        }
    }

    @Test
    fun pauseAfterQueuedEosWinsForNextRepeatAndDormantAbNavigation() {
        val source = File(application.cacheDir, "queued-eos-pause-race.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { index -> song(index, source, 8_000L) }
        onMain {
            playback.setRepeatMode("Off")
            assertEquals(songs[0], playback.startQueue(songs, shuffle = false, autoplay = true))
        }
        waitForSong(songs[0].id)

        // Natural Next was already queued, but a newer Pause must be authoritative for B.
        onMain {
            assertTrue(playback.debug.enqueueCurrentAudibleEnd())
            playback.pause()
        }
        waitUntil(12_000L) {
            playback.state.value.songId == songs[1].id &&
                !playback.state.value.playWhenReady &&
                playback.state.value.positionMs < 150L
        }

        // Repeat One restarts the same token at zero without reviving transport after Pause.
        var repeatSeekRevision = 0L
        onMain {
            playback.setRepeatMode("One")
            repeatSeekRevision = checkNotNull(playback.seekToWithReceipt(3_000L,
                checkNotNull(playback.acquireControlLease(songs[1].id)))).seekRevision
            playback.play()
        }
        waitUntil(5_000L) {
            val committed = playback.adapter.snapshot().publication.committed
            playback.state.value.playWhenReady && committed?.selection?.songId == songs[1].id &&
                committed.transport.completedSeekRevision >= repeatSeekRevision
        }
        val repeatToken = playback.selectionPressureDiagnosticsForTest().latestRequestedToken
        onMain {
            // Inject EOS through queue policy after the seek is acknowledged: Repeat One must
            // restart B without relying on a proactive native Repeat cache or falling through to C.
            assertTrue(
                playback.debug.enqueueCurrentAudibleEnd()
            )
            playback.pause()
        }
        waitUntil(5_000L) {
            val pressure = playback.selectionPressureDiagnosticsForTest()
            pressure.latestRequestedToken == repeatToken &&
                playback.state.value.songId == songs[1].id &&
                !playback.state.value.playWhenReady &&
                playback.state.value.positionMs < 150L
        }
        val dormantLoopLease = checkNotNull(playback.acquireControlLease(songs[1].id))

        // Committing A-B while the cursor is already outside it deliberately leaves that range
        // dormant. Natural EOS therefore follows normal queue policy to C; the newer Pause must
        // still win and a dormant range must never be inherited by the next owner.
        var dormantSeekRevision = 0L
        onMain {
            playback.setRepeatMode("Off")
            dormantSeekRevision = checkNotNull(
                playback.seekToWithReceipt(7_500L, dormantLoopLease)
            ).seekRevision
        }
        waitUntil(5_000L) {
            val committed = playback.adapter.snapshot().publication.committed
            val preparation = playback.state.value.loopPreparation
            committed != null && committed.selection.token.value == repeatToken &&
                committed.transport.completedSeekRevision >= dormantSeekRevision &&
                preparation.canConfigure && preparation.selectionToken == repeatToken &&
                preparation.generation == committed.owner.ownership.generation
        }
        // Author the actual range through the same admission gate as the player, then require
        // its exact preparation instead of waiting for an unrelated current-cursor cache range.
        onMain { assertTrue(playback.setLoop(2_000L, 4_000L, dormantLoopLease)) }
        waitUntil(5_000L) {
            val state = playback.state.value
            state.loopA == 2_000L && state.loopB == 4_000L && state.loopAvailable
        }
        assertFalse(playback.state.value.playWhenReady)
        assertEquals("A dormant range must not relocate the paused cursor", 7_500L,
            playback.state.value.positionMs)
        onMain {
            playback.play()
            assertTrue(playback.debug.enqueueCurrentAudibleEnd())
            playback.pause()
        }
        waitUntil(5_000L) {
            val pressure = playback.selectionPressureDiagnosticsForTest()
            pressure.latestRequestedToken != null &&
                pressure.latestRequestedToken != repeatToken &&
                playback.state.value.songId == songs[2].id &&
                !playback.state.value.playWhenReady &&
                playback.state.value.positionMs < 150L &&
                playback.state.value.loopA == null &&
                playback.state.value.loopB == null
        }
    }

    @Test
    fun delayedSourcePendingEndCannotRestartAnOwnerSupersededByManualSelection() {
        val source = File(application.cacheDir, "queue-transition.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { song(it, source, 8_000L) }
        onMain { playback.startQueue(songs, shuffle=false, autoplay=false) }
        waitForSong(songs[0].id)
        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        var replacementToken: Long? = null
        try {
            onMain {
                assertTrue(playback.debug.enqueueCurrentAudibleEnd(sourceChangePending=true))
                assertTrue(playback.selectSong(songs[2], autoplay=false))
            }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                replacementToken=checkNotNull(playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
            }
            // Flush the actual posted EOS while replacement C is still pending on the held actor.
            instrumentation.waitForIdleSync()
            assertEquals(replacementToken, playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
            assertEquals(songs[2].id, playback.selectionPressureDiagnosticsForTest().latestRequestedSongId)
        } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
        waitForSong(songs[2].id)
        assertEquals(replacementToken, playback.selectionPressureDiagnosticsForTest().committedToken)
        assertEquals(replacementToken, playback.selectionPressureDiagnosticsForTest().latestRequestedToken)
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test
    fun actorHeldPendingSongReconcilesNewestMetadataArtworkAndAudioIdentity() {
        val sourceV1 = File(application.cacheDir, "pending-refresh-v1.wav")
        val sourceV2 = File(application.cacheDir, "pending-refresh-v2.wav")
        val artwork = File(application.cacheDir, "pending-refresh-artwork.png")
        writeSilentWave(sourceV1, 8_000L)
        writeSilentWave(sourceV2, 8_000L, amplitude = 12_000.0)
        assertFalse(sourceV1.readBytes().contentEquals(sourceV2.readBytes()))
        writeArtwork(artwork)
        val songs = (0 until 3).map { index -> song(index, sourceV1, 8_000L) }
        onMain {
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitForSong(songs[0].id)

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        var staleToken: Long? = null
        var newestToken: Long? = null
        try {
            onMain { playback.selectSong(songs[1], autoplay = false) }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                staleToken = playback.selectionPressureDiagnosticsForTest().latestRequestedToken
                val metadataRefresh = songs[1].copy(
                    title = "Pending metadata refresh",
                    mediaRevision = "pending-media-v2",
                    artworkSource = artwork.absolutePath
                )
                playback.setPlaybackQueue(
                    listOf(songs[0], metadataRefresh, songs[2]),
                    shuffle = false
                , snapshotReady = true)
                // Presentation-only enrichment updates the exact pending token in place.
                assertEquals(
                    staleToken,
                    playback.selectionPressureDiagnosticsForTest().latestRequestedToken
                )
            }
            waitUntil(8_000L) {
                playback.sessionMediaItem()?.mediaMetadata?.let { metadata ->
                    metadata.title?.toString() == "Pending metadata refresh" &&
                        metadata.extras?.getString(SESSION_MEDIA_REVISION_KEY) ==
                        "pending-media-v2" &&
                        metadata.artworkData?.isNotEmpty() == true
                } == true
            }

            onMain {
                val audioRefresh = songs[1].copy(
                    title = "Newest pending song",
                    mediaRevision = "pending-media-v3",
                    artworkSource = artwork.absolutePath,
                    sourceHash = PlaybackContentFixture.sourceHash(sourceV2),
            sourcePath = PlaybackContentFixture.publish(application, sourceV2),
                    audioRevision = "pending-audio-v2"
                )
                playback.setPlaybackQueue(
                    listOf(songs[0], audioRefresh, songs[2]),
                    shuffle = false
                , snapshotReady = true)
            }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                newestToken = playback.selectionPressureDiagnosticsForTest().latestRequestedToken
                assertTrue(newestToken != staleToken)
                assertEquals(songs[1].id, playback.sessionMediaItem()?.mediaId)
                assertEquals(
                    "Newest pending song",
                    playback.sessionMediaItem()?.mediaMetadata?.title?.toString()
                )
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitUntil(12_000L) {
            val pressure = playback.selectionPressureDiagnosticsForTest()
            val metadata = playback.sessionMediaItem()?.mediaMetadata
            pressure.latestRequestedToken == newestToken &&
                pressure.committedToken == newestToken &&
            pressure.committedToken != staleToken &&
                playback.state.value.songId == songs[1].id &&
                playback.graphMediaId() == songs[1].id &&
                metadata?.let {
                    it.title?.toString() == "Newest pending song" &&
                        it.extras?.getString(SESSION_MEDIA_REVISION_KEY) == "pending-media-v3" &&
                        it.artworkData?.isNotEmpty() == true
                } == true
        }
        onMain { playback.play() }
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val pcm = native?.output
            val audible = native?.runtime?.ownership?.committed
            pcm != null && audible != null && audible.selectionToken == newestToken &&
                pcm.pcmLevelSelectionToken == newestToken && pcm.pcmLevelGeneration == audible.generation &&
                pcm.pcmLevelFrames > 0 && pcm.pcmLevelMeanSquare > .02f && pcm.pcmLevelPeak > .2f
        }
        // The old input peaks below .062. Token/metadata replacement cannot satisfy this real PCM oracle.
        onMain { playback.pause() }
    }

    @Test
    fun preparedNextAudioSurvivesMetadataRefreshWhileAnyReverificationIsBlocked() = runBlocking {
        val source = File(application.cacheDir, "prewarm-metadata-owner.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 2).map { song(it, source, 8_000L) }
        onMain { playback.startQueue(songs, shuffle = false, autoplay = false) }
        waitForSong(songs[0].id)
        waitUntil(8_000L) { songs[1].id in playback.preparedQueueMediaIds() }
        val before = playback.playbackResourceDiagnosticsForTest()
        val verify = checkNotNull(playback.debug.verifyPlaybackSong)
        val release = CompletableDeferred<Unit>()
        try {
            playback.debug.verifyPlaybackSong = { requested ->
                val verified = verify(requested)
                if (requested.id == songs[1].id) withTimeout(5_000L) { release.await() }
                verified
            }
            onMain { playback.setPlaybackQueue(listOf(songs[0], songs[1].copy(
                title = "Prepared song renamed", mediaRevision = "metadata-only-v2")),
                shuffle = false, snapshotReady = true) }
            // Let the real control actor consume every queued plan update while verification is held.
            SystemClock.sleep(250L)
            assertTrue("Metadata refresh retired prepared next audio", songs[1].id in playback.preparedQueueMediaIds())
            assertEquals(before.cumulativeBankBeginSongCount,
                playback.playbackResourceDiagnosticsForTest().cumulativeBankBeginSongCount)
            release.complete(Unit)
            onMain { assertEquals(songs[1].id, playback.nextTrack()) }
            waitForSong(songs[1].id)
            assertEquals("Prepared song renamed", playback.sessionMediaItem()?.mediaMetadata?.title?.toString())
        } finally {
            release.complete(Unit)
            playback.debug.verifyPlaybackSong = verify
        }
    }

    @Test
    fun likelyNextArtworkIsResidentBeforeEosAndPublishesWithThePromotedOwner() {
        val source = File(application.cacheDir, "likely-next-artwork-source.wav")
        val artwork = File(application.cacheDir, "likely-next-artwork.png")
        writeSilentWave(source, 8_000L)
        writeArtwork(artwork)
        val current = song(0, source, 8_000L).copy(
            id = "artwork-current",
            mediaRevision = "artwork-current-v1"
        )
        val next = song(1, source, 8_000L).copy(
            id = "artwork-next",
            title = "Artwork next",
            artworkSource = artwork.absolutePath,
            mediaRevision = "artwork-next-v1"
        )
        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(listOf(current, next), shuffle = false, autoplay = false)
        }
        waitForSong(current.id)

        waitUntil(8_000L) {
            var cached = false
            onMain { cached = playback.hasCachedSessionArtworkForTest(next.id) }
            cached
        }
        onMain {
            assertTrue(playback.hasCachedSessionArtworkForTest(next.id))
            playback.play()
        }
        waitUntil(12_000L) {
            val metadata = playback.sessionMediaItem()?.mediaMetadata
            playback.state.value.songId == next.id &&
                playback.graphMediaId() == next.id &&
                metadata?.title?.toString() == next.title &&
                metadata.artworkData?.isNotEmpty() == true
        }
        onMain { playback.pause() }
    }

    @Test
    fun focusGainCannotResumeAStalePendingOwnerOrPublishPlayingWithoutProof() {
        val source = File(application.cacheDir, "pending-focus-authority.wav")
        writeSilentWave(source, 8_000L)
        val songs = (0 until 3).map { index -> song(index, source, 8_000L) }
        onMain { playback.startQueue(songs, shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.songId == songs[0].id && playback.state.value.playing
        }

        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        var finalToken: Long? = null
        try {
            onMain { playback.selectSong(songs[1], autoplay = true) }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                val pendingB = playback.selectionPressureDiagnosticsForTest()
                assertEquals(songs[1].id, pendingB.latestRequestedSongId)
                playback.debug.dispatchAudioFocusChange(
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                )
                assertTrue(!playback.state.value.playing)
                assertEquals(
                    AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS,
                    playback.state.value.suppression
                )

                // A newer durable Pause and owner C invalidate B's resume authority.
                playback.pause()
                playback.selectSong(songs[2], autoplay = false)
            }
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                finalToken = playback.selectionPressureDiagnosticsForTest().latestRequestedToken
                playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

                val finalPending = playback.selectionPressureDiagnosticsForTest()
                assertEquals(songs[2].id, finalPending.latestRequestedSongId)
                assertEquals(finalToken, finalPending.latestRequestedToken)
                assertTrue(!requireNotNull(playback.latestRequestedControlsForTest()).playWhenReady)
                assertTrue(!playback.state.value.playing)
                assertTrue(!playback.state.value.playWhenReady)
                assertEquals(AudioPlaybackSuppression.NONE, playback.state.value.suppression)
                assertEquals(songs[2].id, playback.sessionMediaItem()?.mediaId)
                assertFalse(playback.playbackAudioFocusOwnedForTest())
            }
        } finally {
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }

        waitUntil(12_000L) {
            val pressure = playback.selectionPressureDiagnosticsForTest()
            pressure.latestRequestedToken == finalToken &&
                pressure.committedToken == finalToken &&
                playback.state.value.songId == songs[2].id &&
                !playback.state.value.playWhenReady &&
                !playback.state.value.playing
        }
    }

    @Test
    fun transientThenPermanentFocusLossCannotPoisonAFreshUserPlay() {
        val source = File(application.cacheDir, "focus-loss-recovery.wav")
        writeSilentWave(source, 20_000L)
        val current = song(40, source, 20_000L)
        onMain { playback.startQueue(listOf(current), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.songId == current.id &&
                playback.state.value.playing &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == true
        }

        onMain {
            playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        }
        waitUntil(5_000L) {
            !playback.state.value.playing &&
                playback.state.value.suppression ==
                AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS
        }
        onMain {
            // Removing the direct URI takes the real asynchronous resolver path. Main cannot
            // resume its result between this load and the focus callback.
            playback.load(current.copy(sourcePath = null), autoplay = true)
            val resolvingIntent = AudioPlayback::class.java.getDeclaredMethod(
                "currentResolvingTransportIntent"
            ).apply { isAccessible = true }
            assertEquals(true, resolvingIntent.invoke(playback))
            playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(false, resolvingIntent.invoke(playback))
        }
        waitUntil(5_000L) {
            !playback.state.value.loading && !playback.state.value.playWhenReady &&
                playback.state.value.suppression == AudioPlaybackSuppression.NONE
        }

        val framesBeforeFreshPlay = requireNotNull(
            playback.nativeDiagnosticsSnapshotForTest()?.output
        ).totalFramesAccepted
        onMain { playback.play() }
        waitUntil(8_000L) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            playback.state.value.playWhenReady &&
                playback.state.value.playing &&
                native?.output?.playing == true &&
                native.output.totalFramesAccepted > framesBeforeFreshPlay
        }
        onMain { playback.pause() }
    }

    private fun song(index: Int, source: File, durationMs: Long): Song {
        val song = Song(
            id = "song-$index",
            videoId = "video-$index",
            title = "Song $index",
            artist = "Stemwise",
            durationMs = durationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = index.toString(),
            sourceHash = PlaybackContentFixture.sourceHash(source),
            sourcePath = PlaybackContentFixture.publish(application, source),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val identity = SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id,
            checkNotNull(song.sourceHash))
        if (identity !in ownedSettingsSongs) {
            ownedSettingsSongs[identity] = song
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
            SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).clear(identity)
            runBlocking { withTimeout(8_000L) { playback.savedSongTrim(song) } }
        }
        return song
    }

    private fun waitForSong(id: String) {
        waitUntil(12_000L) {
            playback.state.value.songId == id &&
                playback.graphMediaId() == id &&
                playback.sessionMediaItem()?.mediaId == id
        }
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        if (predicate()) return
        throw AssertionError(
            buildString {
                append("Timed out: state=")
                append(playback.state.value)
                append("; selection=")
                append(playback.selectionPressureDiagnosticsForTest())
                append("; lifecycle=")
                append(playback.mediaSessionLifecycleDiagnosticsForTest())
                append("; graphMediaId=")
                append(playback.graphMediaId())
                append("; transitionTarget=")
                append(playback.queueTransitionTargetSummaryForTest())
                append("; preparedQueue=")
                append(playback.preparedQueueMediaIds())
                append("; graph=")
                append(playback.graphDiagnostics())
                append("; resources=")
                append(playback.playbackResourceDiagnosticsForTest())
                append("; native=")
                append(playback.nativeDiagnosticsSnapshotForTest())
            }
        )
    }

    private fun isolateAndCleanOwnedState() {
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        runBlocking { withTimeout(12_000L) {
            ownedSettingsSongs.values.forEach { playback.savedSongTrim(it) }
        } }
        ownedSettingsSongs.keys.forEach(store::clear)
        ownedSettingsSongs.clear()
        PlaybackContentFixture.clear(application)
    }


    private fun onMain(block: () -> Unit) {
        val main = android.os.Looper.getMainLooper()
        val action = java.util.concurrent.FutureTask<Unit> { block() }
        check(android.os.Handler(main).post(action))
        try {
            action.get(20L, java.util.concurrent.TimeUnit.SECONDS)
        } catch (failure: java.util.concurrent.TimeoutException) {
            throw AssertionError("Main-thread action stalled:\n" +
                main.thread.stackTrace.joinToString("\n"), failure)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw requireNotNull(failure.cause)
        }
    }

    private fun assertTerminalAudioHealthUnchanged(
        before: app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot,
        after: app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot,
        label: String
    ) {
        val beforeOutput = requireNotNull(before.output)
        val afterOutput = requireNotNull(after.output)
        assertEquals("$label underrun callbacks", beforeOutput.processUnderrunCallbackCount,
            afterOutput.processUnderrunCallbackCount)
        assertEquals("$label underrun frames", beforeOutput.processUnderrunFrames,
            afterOutput.processUnderrunFrames)
        audioHealthAssertions.checkSucceeds {
            assertEquals("$label xruns", beforeOutput.processXRunCount, afterOutput.processXRunCount)
        }
        assertEquals("$label invalid ownership", beforeOutput.processInvalidOwnershipTagFrames,
            afterOutput.processInvalidOwnershipTagFrames)
        assertEquals("$label callback deadlines", beforeOutput.processCallbackDeadlineMissCount,
            afterOutput.processCallbackDeadlineMissCount)
        assertEquals("$label invalid renders", before.runtime.processOutputInvalidRenderResults,
            after.runtime.processOutputInvalidRenderResults)
        assertEquals("$label flush failures", before.runtime.processOutputFlushFailures,
            after.runtime.processOutputFlushFailures)
    }

    private fun writeExactStereoWave(file: File, frameCount: Int, sampleRate: Int) {
        PlaybackContentFixture.own(application, file)
        require(frameCount > 31 && sampleRate > 0)
        val channelCount = 2
        val bytesPerSample = 2
        val blockAlign = channelCount * bytesPerSample
        val dataSize = frameCount * blockAlign
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(channelCount.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(sampleRate))
            output.writeInt(Integer.reverseBytes(sampleRate * blockAlign))
            output.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            repeat(frameCount) { index ->
                val terminalWatermark = index >= frameCount - 31
                val amplitude = if (terminalWatermark) 24_000.0 else 4_000.0
                val sample =
                    (kotlin.math.sin(2.0 * Math.PI * 347.0 * index / sampleRate) * amplitude)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
                output.writeShort(java.lang.Short.reverseBytes((-sample).toShort()).toInt())
            }
        }
    }

    private fun writeSilentWave(file: File, durationMs: Long, amplitude: Double = 2_000.0) {
        PlaybackContentFixture.own(application, file)
        val sampleRate = 44_100
        val sampleCount = (durationMs * sampleRate / 1_000L).toInt()
        val dataSize = sampleCount * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeInt(Integer.reverseBytes(sampleRate))
            output.writeInt(Integer.reverseBytes(sampleRate * 2))
            output.writeShort(java.lang.Short.reverseBytes(2).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            repeat(sampleCount) { index ->
                val sample =
                    (kotlin.math.sin(2.0 * Math.PI * 220.0 * index / sampleRate) * amplitude)
                        .toInt()
                        .toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    private fun writeArtwork(file: File) {
        PlaybackContentFixture.own(application, file)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.rgb(40, 180, 220))
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

}
