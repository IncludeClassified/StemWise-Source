package app.stemwise.playback

import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.LastListeningSessionStore
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongMetronomeSettings
import app.stemwise.data.SongTrimRange
import app.stemwise.demo.uiDemoLibraryManifest
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.GoldenQaDocumentsProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class SongTrimPlaybackInstrumentationTest : LoopPlaybackFixture() {
    private val ownedSettingsIdentities = mutableListOf<SongPlaybackSettingsIdentity>()
    private val ownedPackageDirectories = mutableListOf<DocumentFile>()

    @Before fun prepareDisposableTrimRoot() = runBlocking {
        val roots = application.services.libraryRootManager
        if (!roots.state.value.ready) roots.initialize(application.services.preferences)
        if (!roots.state.value.ready) {
            val tree = GoldenQaDocumentsProvider.treeUri
            application.grantUriPermission(application.packageName, tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
        }
        assertTrue(roots.state.value.ready)
    }

    @After fun clearOwnedTrimRows() {
        playback.resetForInstrumentationIsolation(instrumentation)
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        ownedSettingsIdentities.forEach(store::clear)
        ownedPackageDirectories.forEach { check(it.delete()) { "Could not remove owned package ${it.uri}" } }
    }

    @Test fun trimPreviewAuditionsOutsideTheSavedWindowWithoutLoopingAndCancelRestoresSettings() {
        val (song, original) = fixtureSong("preview", 220.0)
        val (next, _) = fixtureSong("preview-next", 330.0)
        onMain { playback.setPlaybackQueue(listOf(song, next), snapshotReady = true) }
        selectPaused(song)
        applyTrim(song, SongTrimRange(1_000L, 3_000L))
        onMain {
            assertTrue(playback.setLoop(250L, 1_250L, lease(song)))
            assertTrue(playback.setRepeatMode("One"))
        }
        runBlocking { playback.savedSongTrim(song) }
        val identity = ownedSettingsIdentities.first { it.songId == song.id }
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        val saved = store.read(identity, song.durationMs)
        val sourceHash = PlaybackContentFixture.sourceHash(original)
        var preview: SongTrimPreviewLease? = null
        try {
            onMain { preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song))) }
            val editor = checkNotNull(preview)
            waitUntil(8_000L) { playback.songTrimPreviewIsReady(editor) }
            val listening = LastListeningSessionStore(File(application.filesDir, "last-listening-session.json"))
            waitUntil(8_000L) { listening.read()?.let { it.identity == identity && it.queue.repeatMode == RepeatMode.ONE } == true }
            val beforePreview = listening.read()
            assertWindow(0L, song.durationMs, song.durationMs)
            assertFalse(playback.state.value.playWhenReady)
            assertNull(playback.state.value.loopA)
            assertNull(playback.state.value.loopB)
            onMain {
                assertTrue(playback.previewSongTrimBoundary(editor, 5_000L))
                assertEquals(5_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
                assertTrue(playback.play())
            }
            waitUntil(8_000L) { playback.state.value.playing && playback.state.value.positionMs >= 5_000L }
            waitUntil(8_000L) { !playback.state.value.playWhenReady }
            assertEquals("Preview EOS must neither repeat nor advance", song.id, playback.state.value.songId)
            assertEquals(saved, store.read(identity, song.durationMs))
            assertEquals("A trim audition became listening history", beforePreview, listening.read())
            onMain { playback.cancelSongTrimPreview(editor) }
            preview = null
            waitUntil(8_000L) { !playback.state.value.loading && playback.state.value.trimStartMs == 1_000L &&
                playback.state.value.loopA == 250L && playback.state.value.loopB == 1_250L }
            assertWindow(1_000L, 2_000L, song.durationMs)
            assertFalse(playback.state.value.playWhenReady)
            assertEquals("One", playback.state.value.repeatMode)
            assertEquals(saved, store.read(identity, song.durationMs))
            assertEquals(sourceHash, PlaybackContentFixture.sourceHash(original))
            selectPaused(next)
            val selected = playback.adapter.snapshot().latestRequest?.selection?.token
            onMain {
                assertFalse(playback.previewSongTrimBoundary(editor, 1_000L))
                playback.cancelSongTrimPreview(editor)
            }
            assertEquals(selected, playback.adapter.snapshot().latestRequest?.selection?.token)
        } finally {
            onMain { preview?.let(playback::cancelSongTrimPreview) }
        }
    }

    @Test fun failedRetainedStemsDuringTrimReloadRetiresFocusAndDelayedResume() {
        val (song, original) = fixtureSong("restore-focus-failure", 220.0)
        selectPaused(song)
        val vocals = File(original.parentFile, "restore-vocals.wav")
        val bass = File(original.parentFile, "restore-bass.wav")
        writeSineWave(vocals, song.durationMs, 330.0)
        writeSineWave(bass, song.durationMs, 440.0)
        val stems = listOf(stemAsset("vocals", vocals, song.durationMs), stemAsset("bass", bass, song.durationMs),
            remainingAsset(bass, song.durationMs, 440.0))
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) { playback.activateStems(stems, lease(song)) } } }.getOrThrow()
        onMain { assertTrue(playback.play()) }
        waitUntil(8_000L) { playback.state.value.playing && playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        val old = checkNotNull(playback.adapter.snapshot().publication.committed).selection.token
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val before = playback.debug.beforeState
        var preview: SongTrimPreviewLease? = null
        playback.debug.beforeState = { snapshot ->
            before?.invoke(snapshot)
            if (snapshot.publication.committed?.selection?.let { it.songId == song.id && it.token != old } == true &&
                entered.complete(Unit)) withTimeout(8_000L) { release.await() }
        }
        try {
            onMain { preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song))) }
            runBlocking { withTimeout(8_000L) { entered.await() } }
            onMain {
                val committed = checkNotNull(playback.adapter.snapshot().publication.committed)
                val request = checkNotNull(committed.stems.desired)
                val callback = playback.adapter.javaClass.getDeclaredMethod("onControllerEvent",
                    app.stemwise.playback.engine.NativeEngineControllerEvent::class.java).apply { isAccessible = true }
                callback.invoke(playback.adapter, app.stemwise.playback.engine.NativeEngineControllerEvent.StemPackageFailed(
                    committed.selection.token, request.packageSignature, request.stemRevision, request.requestRevision,
                    true, false, "Retained decoder could not read its exact stem"))
            }
            release.complete(Unit)
            waitUntil(8_000L) { playback.state.value.notice?.title == "Settings could not open" }
            onMain {
                assertFalse(playback.state.value.playWhenReady)
                assertFalse(playback.playbackAudioFocusOwnedForTest())
                playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                assertFalse(playback.state.value.playWhenReady)
                assertFalse(playback.playbackAudioFocusOwnedForTest())
            }
        } finally {
            release.complete(Unit)
            onMain { playback.debug.beforeState = before; preview?.let(playback::cancelSongTrimPreview) }
        }
    }

    @Test fun terminalSessionSeekRetiresDeferredTrimAutoplayBeforePreviewPublication() =
        terminalSessionSeekRetiresDeferredTrimAutoplay(awaitAudible = true)

    @Test fun rapidTerminalSessionSeekRetiresDeferredTrimAutoplayBeforePreviewPublication() =
        terminalSessionSeekRetiresDeferredTrimAutoplay(awaitAudible = false)

    private fun terminalSessionSeekRetiresDeferredTrimAutoplay(awaitAudible: Boolean) {
        val (song, original) = fixtureSong("pending-preview-terminal", 220.0)
        selectPaused(song)
        val vocals = File(original.parentFile, "terminal-vocals.wav")
        val bass = File(original.parentFile, "terminal-bass.wav")
        writeSineWave(vocals, song.durationMs, 330.0); writeSineWave(bass, song.durationMs, 440.0)
        val stems = listOf(stemAsset("vocals", vocals, song.durationMs), stemAsset("bass", bass, song.durationMs),
            remainingAsset(bass, song.durationMs, 440.0))
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) { playback.activateStems(stems, lease(song)) } } }.getOrThrow()
        for ((mode, relative) in listOf("Off" to false, "One" to true, "All" to false)) {
            onMain { playback.setRepeatMode(mode); assertTrue(playback.play()) }
            waitUntil(8_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                val audible = if (awaitAudible) playback.nativeDiagnosticsSnapshotForTest()?.runtime?.audibleControl else null
                playback.state.value.playing && playback.state.value.sourceSwitchState is SourceSwitchState.Stems &&
                    (!awaitAudible || (audible?.ownership == committed?.owner?.ownership &&
                        audible?.actualSourceMode == app.stemwise.playback.engine.NativeRuntimeSourceMode.STEMS))
            }
            var preview: SongTrimPreviewLease? = null
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
            try {
                onMain {
                    preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song)))
                    val player = playback.media3Player()
                    if (relative) {
                        player.seekTo(song.durationMs - 1_000L)
                        player.seekForward()
                    } else player.seekTo(song.durationMs)
                    assertFalse(playback.state.value.playWhenReady)
                }
            } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
            try {
                waitUntil(10_000L) { !playback.state.value.loading && playback.state.value.ended &&
                    !playback.state.value.playWhenReady && playback.songTrimPreviewIsReady(checkNotNull(preview)) }
                assertEquals(song.durationMs, playback.state.value.positionMs)
                assertFalse(playback.playbackAudioFocusOwnedForTest())
                assertEquals(song.id, playback.state.value.songId)
                onMain { assertTrue(playback.play()) }
                waitUntil(8_000L) { playback.state.value.playing && !playback.state.value.ended }
                onMain { playback.pause() }
            } finally { onMain { preview?.let(playback::cancelSongTrimPreview) } }
            waitUntil(8_000L) { !playback.state.value.loading && playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        }
    }

    @Test fun replacedRemainingBeforeFirstFineHeaderReadIsRecoverableAndDoesNotPublishPeaks() {
        val (song, original) = fixtureSong("fine-replaced-remaining", 220.0)
        selectPaused(song)
        val vocals = File(original.parentFile, "fine-vocals.wav")
        val bass = File(original.parentFile, "fine-bass.wav")
        writeSineWave(vocals, song.durationMs, 330.0); writeSineWave(bass, song.durationMs, 440.0)
        val stems = listOf(stemAsset("vocals", vocals, song.durationMs), stemAsset("bass", bass, song.durationMs),
            remainingAsset(bass, song.durationMs, 440.0))
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) { playback.activateStems(stems, lease(song)) } } }.getOrThrow()
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        var identity: app.stemwise.playback.engine.SourceWaveformIdentity? = null
        onMain { identity = checkNotNull(playback.sourceWaveformIdentity()) }
        val expected = checkNotNull(identity)
        val remaining = expected.sources.single { it.directFloat32Audio }
        val uri = android.net.Uri.parse(remaining.location)
        val saved = checkNotNull(application.contentResolver.openInputStream(uri)).use { it.readBytes() }
        fun replace(bytes: ByteArray) = checkNotNull(application.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        try {
            replace(saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertNull(runBlocking { playback.readSourceWaveformWindowAbsolute(expected, 3_000L) })
            onMain { assertTrue(playback.pause()); assertTrue(playback.acquireControlLease(song.id) != null) }
        } finally { replace(saved) }
        var recovered: app.stemwise.playback.engine.SourceWaveformWindow? = null
        waitUntil(8_000L) { recovered = runBlocking { playback.readSourceWaveformWindowAbsolute(expected, 3_000L) }; recovered != null }
        assertTrue(checkNotNull(recovered).peaks.isNotEmpty())
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test fun repeatOneRestartsTheNonzeroTrimWithoutAdvancingTheQueue() = repeatTrim(stemsSelected = false)

    @Test fun repeatOneRestartsTheNonzeroTrimmedStemsWithoutAdvancingTheQueue() = repeatTrim(stemsSelected = true)

    @Test fun cancelTrimPreviewHonorsExplicitPauseAndPlayAcceptedAfterOpening() {
        val (song, _) = fixtureSong("preview-transport", 220.0)
        selectPaused(song)
        applyTrim(song, SongTrimRange(1_000L, 3_000L))
        for (playingAtOpen in listOf(true, false)) {
            onMain { assertTrue(if (playingAtOpen) playback.play() else playback.pause()) }
            var preview: SongTrimPreviewLease? = null
            try {
                onMain { preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song))) }
                val editor = checkNotNull(preview)
                waitUntil(8_000L) { playback.songTrimPreviewIsReady(editor) }
                val previewToken = playback.adapter.snapshot().latestRequest?.selection?.token
                onMain {
                    assertTrue(if (playingAtOpen) playback.pause() else playback.play())
                    playback.cancelSongTrimPreview(editor)
                }
                preview = null
                waitUntil(8_000L) {
                    val current = playback.adapter.snapshot()
                    val committed = current.publication.committed
                    !playback.state.value.loading && committed != null && committed.selection.token != previewToken &&
                        current.latestRequest?.selection?.token == committed.selection.token &&
                        committed.transport.playWhenReady == !playingAtOpen
                }
                assertWindow(1_000L, 2_000L, song.durationMs)
                assertEquals(!playingAtOpen, playback.state.value.playWhenReady)
                if (!playingAtOpen) waitUntil(8_000L) { playback.state.value.playing }
            } finally {
                onMain { preview?.let(playback::cancelSongTrimPreview) }
            }
        }
    }

    @Test fun unavailableCancelRetiresThePreviewAndPreservesSavedTrim() = unavailableTrimPreviewRestore(save = false)

    @Test fun unavailableSaveRetiresThePreviewAndPreservesTheNewlyCommittedTrim() = unavailableTrimPreviewRestore(save = true)

    private fun unavailableTrimPreviewRestore(save: Boolean) {
        val (song, _) = fixtureSong("preview-unavailable-$save", 220.0)
        val (next, _) = fixtureSong("preview-unavailable-next-$save", 330.0)
        onMain { playback.setPlaybackQueue(listOf(song, next), snapshotReady = true) }
        selectPaused(song)
        val originalTrim = SongTrimRange(1_000L, 3_000L)
        val savedTrim = if (save) SongTrimRange(1_500L, 3_500L) else originalTrim
        applyTrim(song, originalTrim)
        onMain { assertTrue(playback.setRepeatMode("One")) }
        val verifier = checkNotNull(playback.debug.verifyPlaybackSong)
        var preview: SongTrimPreviewLease? = null
        try {
            onMain { preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song))) }
            val editor = checkNotNull(preview)
            waitUntil(8_000L) { playback.songTrimPreviewIsReady(editor) }
            val identity = ownedSettingsIdentities.first { it.songId == song.id }
            val listening = LastListeningSessionStore(File(application.filesDir, "last-listening-session.json"))
            waitUntil(8_000L) { listening.read()?.let { it.identity == identity && it.queue.repeatMode == RepeatMode.ONE } == true }
            val beforePreview = listening.read()
            onMain {
                assertTrue(playback.previewSongTrimBoundary(editor, 4_000L))
            }
            waitUntil(8_000L) {
                playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs == 4_000L
            }
            onMain {
                playback.debug.verifyPlaybackSong = { candidate ->
                    if (candidate.id == song.id) throw java.io.IOException("Preview restore fixture unavailable")
                    verifier(candidate)
                }
            }
            if (save) {
                runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                    playback.setSongTrim(song, savedTrim, playback.songTrimPreviewControlLease(editor))
                } } }.getOrThrow()
            } else onMain { playback.cancelSongTrimPreview(editor) }
            preview = null
            runBlocking { withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            assertNull("Failed source restoration retained the full-source preview command owner",
                playback.adapter.snapshot().latestRequest)
            waitUntil(8_000L) {
                val current = playback.adapter.snapshot()
                current.lifecycle == app.stemwise.playback.engine.NativeAudioPlaybackSessionLifecycle.ACTIVE &&
                    current.latestRequest == null && current.publication.committed == null
            }
            assertFalse(playback.state.value.playWhenReady)
            assertEquals(song.id, playback.state.value.currentQueueSongId)
            assertEquals("One", playback.state.value.repeatMode)
            val failureNotice = checkNotNull(playback.state.value.notice)
            assertEquals(PlaybackNoticeCategory.SONG_SELECTION, failureNotice.category)
            assertEquals("Song unavailable", failureNotice.title)
            assertTrue(failureNotice.message.contains("storage access") && failureNotice.message.contains("reimport"))
            onMain { assertFalse("An unavailable preview remained playable", playback.play()) }
            assertEquals(savedTrim, SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
                .read(identity, song.durationMs)?.trim)
            assertEquals(beforePreview, listening.read())
            onMain { playback.debug.verifyPlaybackSong = verifier }
            selectPaused(song)
            assertWindow(savedTrim.startMs, savedTrim.endMs - savedTrim.startMs, song.durationMs)
        } finally {
            onMain {
                playback.debug.verifyPlaybackSong = verifier
                playback.setRepeatMode("Off")
                playback.pause()
                preview?.let(playback::cancelSongTrimPreview)
            }
        }
    }

    @Test fun repeatOneAndLoopConfiguredBeforeTrimSurviveTheReload() {
        val (song, _) = fixtureSong("repeat-before-trim", 220.0)
        val (next, _) = fixtureSong("repeat-before-trim-next", 330.0)
        onMain { playback.setPlaybackQueue(listOf(song, next), snapshotReady = true) }
        selectPaused(song)
        onMain {
            assertTrue(playback.setRepeatMode("One"))
            assertTrue(playback.setLoop(1_250L, 2_250L, lease(song)))
        }
        runBlocking { playback.savedSongTrim(song) }
        applyTrim(song, SongTrimRange(1_000L, 3_000L))
        assertRestoredLoopAndRepeat(song)
        onMain { assertTrue(playback.play()) }
        assertAudibleWraps(song)
        onMain { assertTrue(playback.setLoop(null, null, lease(song))) }
        assertAudibleWraps(song)
    }

    @Test fun trimPreviewSavePreservesEarlierLoopAndRepeatOneAndExplicitPlayIntent() {
        val (song, _) = fixtureSong("preview-save-repeat", 220.0)
        val (next, _) = fixtureSong("preview-save-repeat-next", 330.0)
        onMain { playback.setPlaybackQueue(listOf(song, next), snapshotReady = true) }
        selectPaused(song)
        onMain {
            assertTrue(playback.setRepeatMode("One"))
            assertTrue(playback.setLoop(1_250L, 2_250L, lease(song)))
        }
        runBlocking { playback.savedSongTrim(song) }
        var preview: SongTrimPreviewLease? = null
        try {
            onMain { preview = checkNotNull(playback.beginSongTrimPreview(song, lease(song))) }
            val editor = checkNotNull(preview)
            waitUntil(8_000L) { playback.songTrimPreviewIsReady(editor) }
            onMain {
                assertTrue(playback.previewSongTrimBoundary(editor, 1_500L))
                assertTrue(playback.play())
            }
            waitUntil(8_000L) { playback.state.value.playing }
            applyTrim(song, SongTrimRange(1_000L, 3_000L))
            preview = null
            assertRestoredLoopAndRepeat(song)
            assertTrue("Save lost the explicit Play accepted during preview", playback.state.value.playWhenReady)
            assertAudibleWraps(song)
            onMain { assertTrue(playback.setLoop(null, null, lease(song))) }
            assertAudibleWraps(song)
        } finally {
            onMain { preview?.let(playback::cancelSongTrimPreview) }
        }
    }

    private fun assertRestoredLoopAndRepeat(song: Song) {
        waitUntil(8_000L) { playback.state.value.loopA == 250L && playback.state.value.loopB == 1_250L }
        assertEquals("One", playback.state.value.repeatMode)
        val identity = ownedSettingsIdentities.first { it.songId == song.id }
        val saved = checkNotNull(SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
            .read(identity, song.durationMs))
        assertEquals(SongTrimRange(1_000L, 3_000L), saved.trim)
        assertEquals(1_250L, saved.loop.pointA)
        assertEquals(2_250L, saved.loop.pointB)
        assertTrue(saved.loop.enabled)
    }

    private fun repeatTrim(stemsSelected: Boolean) {
        val (song, _) = fixtureSong("repeat-trim", 220.0)
        val (next, _) = fixtureSong("repeat-next", 330.0)
        onMain { playback.setPlaybackQueue(listOf(song, next), snapshotReady = true) }
        selectPaused(song)
        applyTrim(song, SongTrimRange(1_000L, 3_000L))
        if (stemsSelected) {
            val directory = File(application.cacheDir, song.id)
            val vocals = File(directory, "vocals.wav")
            val bass = File(directory, "bass.wav")
            writeSineWave(vocals, song.durationMs, 330.0)
            writeSineWave(bass, song.durationMs, 440.0)
            val stems = listOf(stemAsset("vocals", vocals, song.durationMs),
                stemAsset("bass", bass, song.durationMs), remainingAsset(bass, song.durationMs, 440.0))
            runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                playback.activateStems(stems, lease(song))
            } } }.getOrThrow()
            waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        }
        onMain { assertTrue(playback.setRepeatMode("One")); assertTrue(playback.play()) }
        assertAudibleWraps(song, stemsSelected)
        assertWindow(1_000L, 2_000L, song.durationMs)
        onMain { assertTrue(playback.setRepeatMode("Off")) }
        waitUntil(8_000L) { playback.state.value.songId == next.id && !playback.state.value.loading }
    }

    private fun assertAudibleWraps(song: Song, stemsSelected: Boolean = false) {
        waitUntil(8_000L) { playback.state.value.playing && playback.state.value.positionMs >= 500L }
        var previous = playback.state.value.positionMs
        var minimumPosition = previous
        var maximumPosition = previous
        var wraps = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        while (wraps < 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(25L)
            val state = playback.state.value
            assertEquals("Repeat One advanced the queue", song.id, state.songId)
            assertEquals("One", state.repeatMode)
            if (stemsSelected) assertEquals(PlaybackSourceMode.STEMS, state.sourceMode)
            assertTrue("Repeat One stopped", state.playWhenReady)
            if (state.positionMs + 300L < previous) wraps++
            minimumPosition = minOf(minimumPosition, state.positionMs)
            maximumPosition = maxOf(maximumPosition, state.positionMs)
            previous = state.positionMs
        }
        if (wraps != 2) {
            val session = playback.adapter.snapshot()
            val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
            val nativeLoop = diagnostics?.controllerLoopProtocol
            val runtime = diagnostics?.runtime
            val output = diagnostics?.output
            assertEquals("The rendered trim must restart twice; publicPosition=$minimumPosition..$maximumPosition " +
                "final=${playback.state.value.positionMs}; publicLoop=${playback.state.value.loopA}..${playback.state.value.loopB}; " +
                "requestedLoop=${session.latestRequest?.controls?.loop}; desiredPosition=${session.latestRequest?.controls?.requestedPositionMs}; " +
                "presented=${session.publication.committed?.transport?.presentedPositionMs}; " +
                "nativeLoop=$nativeLoop; nativePresentation=${runtime?.presentation}; " +
                "output=${output?.streamState}/playing=${output?.playing}/effective=${output?.effectiveRenderPlaying}/" +
                "queued=${output?.queuedFrames}/accepted=${output?.totalFramesAccepted}/" +
                "lastPosition=${output?.lastPresentedTimelineFrame}; counters=${runtime?.counters}; " +
                "needOriginal=${runtime?.needOriginalData}; needStems=${runtime?.needStemData}; " +
                "loopPending=${runtime?.loopBoundaryPending}; lowWater=${runtime?.currentOutputQueueLowWater}; " +
                "readiness=${diagnostics?.nativeRenderReadiness}; endOrdering=${runtime?.endOrdering?.events?.takeLast(6)}", 2, wraps)
        }
    }

    @Test fun trimmedOriginalAndStemsShareSeekCoordinatesAndEndWithoutChangingMedia() {
        val (baseSong, original) = fixtureSong("playback", 220.0)
        val song = baseSong.copy(hasStems = true)
        val vocals = File(original.parentFile, "vocals.wav")
        val bass = File(original.parentFile, "bass.wav")
        writeSineWave(vocals, song.durationMs, 330.0)
        writeSineWave(bass, song.durationMs, 440.0)
        val stems = listOf(stemAsset("vocals", vocals, song.durationMs),
            stemAsset("bass", bass, song.durationMs), remainingAsset(bass, song.durationMs, 440.0))
        val sourceHashes = (listOf(checkNotNull(song.sourcePath)) + stems.map { it.localPath })
            .associateWith { PlaybackContentFixture.sourceHash(application, it) }
        selectPaused(song)
        applyTrim(song, SongTrimRange(1_000L, 3_000L))
        assertWindow(1_000L, 2_000L, song.durationMs)
        assertNull(playback.state.value.loopA)
        assertNull(playback.state.value.loopB)
        onMain { assertTrue(playback.seekTo(0L, lease(song))) }
        assertEquals(0L, playback.state.value.positionMs)
        assertEquals(1_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
        onMain { assertTrue(playback.seekTo(700L, lease(song))) }
        waitUntil(8_000L) {
            playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs == 1_700L
        }
        val activation = runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
            playback.activateStems(stems, lease(song))
        } } }
        activation.getOrThrow()
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        assertEquals(700L, playback.state.value.positionMs)
        onMain { assertTrue(playback.switchToOriginal(lease(song))) }
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Original }
        assertEquals(700L, playback.state.value.positionMs)
        onMain { assertTrue(playback.play()) }
        waitUntil(8_000L) {
            playback.state.value.playing && playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                playback.graphDiagnostics().outputRms > 0.001f
        }
        onMain { assertTrue(playback.pause()) }
        val reactivation = runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
            playback.activateStems(stems, lease(song))
        } } }
        reactivation.getOrThrow()
        onMain { assertTrue(playback.seekTo(0L, lease(song))); assertTrue(playback.play()) }
        waitUntil(8_000L) {
            playback.state.value.playing && playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.graphDiagnostics().outputRms > 0.001f
        }
        waitUntil(8_000L) { playback.state.value.ended }
        assertFalse(playback.state.value.playing)
        assertEquals(2_000L, playback.state.value.positionMs)
        assertWindow(1_000L, 2_000L, song.durationMs)
        onMain {
            val control = lease(song)
            assertTrue(playback.commitStemMix("vocals", .37f, muted = false, solo = false, controlLease = control))
            assertTrue(playback.setTempoAndPitch(1.25f, 3, control))
        }
        val retainedSet = checkNotNull(playback.state.value.loadedStemSetId)
        val retainedSignature = checkNotNull(playback.state.value.loadedStemSignature)
        val retainedMix = playback.state.value.stemMix
        assertEquals(.37f, checkNotNull(retainedMix["vocals"]).volume, 0f)
        applyTrim(song, null)
        assertWindow(0L, song.durationMs, song.durationMs)
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        assertEquals(retainedSet, playback.state.value.loadedStemSetId)
        assertEquals(retainedSignature, playback.state.value.loadedStemSignature)
        assertEquals(retainedMix, playback.state.value.stemMix)
        assertEquals(1.25f, playback.state.value.tempo, 0f)
        assertEquals(3, playback.state.value.semitoneShift)
        onMain { assertTrue(playback.seekTo(5_000L, lease(song))) }
        assertEquals(5_000L, playback.state.value.positionMs)
        onMain { assertTrue(playback.play()) }
        waitUntil(8_000L) {
            playback.state.value.playing && playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.graphDiagnostics().outputRms > 0.001f
        }
        assertEquals(retainedSet, playback.state.value.loadedStemSetId)
        assertEquals(retainedSignature, playback.state.value.loadedStemSignature)
        assertEquals(retainedMix, playback.state.value.stemMix)
        assertEquals(1.25f, playback.state.value.tempo, 0f)
        assertEquals(3, playback.state.value.semitoneShift)
        onMain { assertTrue(playback.pause()) }
        onMain { assertTrue(playback.seekTo(2_000L, lease(song))) }
        waitUntil(8_000L) {
            playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs == 2_000L
        }
        val oldToken = checkNotNull(playback.adapter.snapshot().publication.committed).selection.token
        val entered = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val previousBeforeState = playback.debug.beforeState
        onMain {
            playback.debug.beforeState = { snapshot ->
                previousBeforeState?.invoke(snapshot)
                val committed = snapshot.publication.committed
                if (committed != null && committed.selection.songId == song.id &&
                    committed.selection.token != oldToken && snapshot.latestRequest?.selection == committed.selection &&
                    entered.complete(committed.selection.token.value)) {
                    withTimeout(30_000L) { release.await() }
                }
            }
        }
        try {
            runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
                playback.setSongTrim(song, SongTrimRange(1_000L, 5_000L), lease(song))
            } } }.getOrThrow()
            val incomingToken = runBlocking { withTimeout(8_000L) { entered.await() } }
            onMain {
                assertTrue(playback.play())
                assertTrue(playback.switchToOriginal(lease(song)))
            }
            release.complete(Unit)
            waitUntil(8_000L) {
                val state = playback.state.value
                playback.adapter.snapshot().publication.committed?.selection?.token?.value == incomingToken &&
                    !state.loading && state.playing && state.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    playback.graphDiagnostics().outputRms > .001f
            }
            onMain {
                val requested = checkNotNull(playback.adapter.snapshot().latestRequest)
                assertEquals(incomingToken, requested.selection.token.value)
                assertEquals(app.stemwise.playback.engine.EngineSourceMode.ORIGINAL, requested.controls.sourceMode)
                assertTrue(requested.controls.playWhenReady)
                assertTrue(playback.pause())
            }
        } finally {
            release.complete(Unit)
            onMain { playback.debug.beforeState = previousBeforeState }
        }
        sourceHashes.forEach { (location, expected) ->
            assertEquals(expected, PlaybackContentFixture.sourceHash(application, location))
        }
    }

    @Test fun reselectAndIdenticalAudioReimportRestoreTrimWhileChangedAudioAndOtherSongsDoNotInheritIt() {
        val (song, original) = fixtureSong("identity", 220.0)
        val originalHash = PlaybackContentFixture.sourceHash(original)
        val (other, _) = fixtureSong("other", 330.0)
        val range = SongTrimRange(1_000L, 3_000L)
        selectPaused(song)
        applyTrim(song, range)
        val identity = ownedSettingsIdentities.first { it.songId == song.id }
        assertEquals(range, SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).read(identity, song.durationMs)?.trim)
        selectPaused(other)
        assertWindow(0L, other.durationMs, other.durationMs)
        val reimported = song.copy(title = "Reimported", audioRevision = "reimported-audio-revision",
            mediaRevision = "reimported-manifest-revision")
        selectPaused(reimported)
        assertWindow(1_000L, 2_000L, song.durationMs)
        assertEquals(range, runBlocking { playback.savedSongTrim(reimported) })
        val trimmedToken = playback.adapter.snapshot().publication.committed?.selection?.token
        onMain { assertTrue(playback.seekTo(2_000L, lease(reimported))) }
        waitUntil(8_000L) {
            val state = playback.state.value
            val committed = playback.adapter.snapshot().publication.committed
            state.songId == other.id && !state.loading && committed?.selection?.songId == other.id
        }
        assertWindow(0L, other.durationMs, other.durationMs)
        assertEquals(0L, playback.state.value.positionMs)
        assertFalse(playback.state.value.playing)
        assertFalse(playback.state.value.playWhenReady)
        assertFalse(playback.state.value.ended)
        val nextOwner = checkNotNull(playback.adapter.snapshot().publication.committed)
        assertTrue(nextOwner.selection.token != trimmedToken)
        assertEquals(other.sourceHash, nextOwner.selection.originalContentSha256)
        assertEquals(0L, nextOwner.transport.presentedPositionMs)
        val changedFile = File(original.parentFile, "changed-original.wav")
        writeSineWave(changedFile, song.durationMs, 440.0)
        val changed = reimported.copy(sourceHash = PlaybackContentFixture.sourceHash(changedFile),
            sourcePath = PlaybackContentFixture.publish(application, changedFile), audioRevision = "changed-audio")
        selectPaused(changed)
        assertWindow(0L, changed.durationMs, changed.durationMs)
        assertNull(runBlocking { playback.savedSongTrim(changed) })
        selectPaused(song)
        assertWindow(1_000L, 2_000L, song.durationMs)
        assertEquals(originalHash, PlaybackContentFixture.sourceHash(original))
        assertEquals(originalHash, PlaybackContentFixture.sourceHash(application, checkNotNull(song.sourcePath)))
    }

    @Test fun rapidSelectionsKeepTheLastSongsTrimWhenEarlierResolversCompleteLate() {
        val fixtures = listOf(fixtureSong("burst-a", 220.0), fixtureSong("burst-b", 330.0),
            fixtureSong("burst-c", 440.0))
        val songs = fixtures.map { it.first }
        val ranges = listOf(SongTrimRange(1_000L, 3_000L), SongTrimRange(2_000L, 5_000L),
            SongTrimRange(500L, 4_500L))
        songs.zip(ranges).forEachIndexed { index, (song, range) ->
            selectPaused(song)
            applyTrim(song, range)
            onMain {
                val control = lease(song)
                assertTrue(playback.setTempoAndPitch(1f + index * .1f, index, control))
                assertTrue(playback.setMetronome(false, 120f, false,
                    subdivision = listOf(1, 2, 4)[index], numerator = 3 + index, denominator = 4, controlLease = control))
                assertTrue(playback.setMetronomeVolume(.2f + index * .1f, control))
                assertTrue(playback.setLoop(500L + index * 100L, null, control))
            }
            runBlocking { playback.savedSongTrim(song) }
        }
        selectPaused(songs[0])
        val verifier = checkNotNull(playback.debug.verifyPlaybackSong)
        val entered = listOf(songs[1], songs[0]).associate { it.id to CompletableDeferred<Job>() }
        val release = entered.keys.associateWith { CompletableDeferred<Unit>() }
        onMain {
            playback.debug.verifyPlaybackSong = { song ->
                val verified = verifier(song)
                val gate = entered[song.id]
                if (gate != null && gate.complete(currentCoroutineContext().job)) {
                    withContext(NonCancellable) {
                        withTimeout(30_000L) { checkNotNull(release[song.id]).await() }
                    }
                }
                verified
            }
        }
        fun assertFinalOwner() {
            val final = songs[2]
            val range = ranges[2]
            assertEquals(final.id, playback.state.value.songId)
            assertEquals(final.id, playback.state.value.currentQueueSongId)
            assertWindow(range.startMs, range.endMs - range.startMs, final.durationMs)
            assertEquals(0L, playback.state.value.positionMs)
            assertFalse(playback.state.value.playing)
            assertEquals(1.2f, playback.state.value.tempo, 0f)
            assertEquals(2, playback.state.value.semitoneShift)
            assertEquals(700L, playback.state.value.loopA)
            assertNull(playback.state.value.loopB)
            assertFalse(playback.state.value.metronomeEnabled)
            assertEquals(.4f, playback.state.value.metronomeVolume, 0f)
            assertEquals(4, playback.state.value.metronomeSubdivision)
            assertEquals(5, playback.state.value.metronomeNumerator)
            val snapshot = playback.adapter.snapshot()
            val owner = checkNotNull(snapshot.publication.committed)
            assertEquals(final.id, owner.selection.songId)
            assertEquals(final.sourceHash, owner.selection.originalContentSha256)
            assertEquals(owner.selection, snapshot.latestRequest?.selection)
            assertEquals(range.startMs, owner.transport.presentedPositionMs)
            assertEquals(range.startMs, snapshot.latestRequest?.controls?.requestedPositionMs)
            val requested = checkNotNull(snapshot.latestRequest)
            assertEquals(1.2f, requested.controls.tempo, 0f)
            assertEquals(2, requested.controls.semitoneShift)
            assertNull(requested.controls.loop)
        }
        try {
            for (song in listOf(songs[1], songs[0])) {
                onMain { assertTrue(playback.selectSong(song, autoplay = false)) }
                runBlocking { withTimeout(8_000L) { checkNotNull(entered[song.id]).await() } }
            }
            selectPaused(songs[2])
            assertFinalOwner()
            for (song in listOf(songs[1], songs[0])) {
                checkNotNull(release[song.id]).complete(Unit)
                runBlocking { withTimeout(8_000L) { checkNotNull(entered[song.id]).await().join() } }
                onMain { assertFinalOwner() }
            }
            songs.zip(ranges).forEachIndexed { index, (song, range) ->
                assertEquals(range, runBlocking { playback.savedSongTrim(song) })
                val identity = ownedSettingsIdentities.first { it.songId == song.id }
                val saved = checkNotNull(SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
                    .read(identity, song.durationMs))
                assertEquals(1f + index * .1f, saved.tempo, 0f)
                assertEquals(index, saved.semitones)
                assertEquals(range.startMs + 500L + index * 100L, saved.loop.pointA)
                assertFalse(saved.loop.enabled)
                val metronome = checkNotNull(saved.metronome)
                assertFalse(metronome.enabled)
                assertEquals(.2f + index * .1f, metronome.volume, 0f)
            }
            fixtures.forEach { (song, original) ->
                assertEquals(song.sourceHash, PlaybackContentFixture.sourceHash(original))
                assertEquals(song.sourceHash, PlaybackContentFixture.sourceHash(application, checkNotNull(song.sourcePath)))
            }
        } finally {
            release.values.forEach { it.complete(Unit) }
            try {
                runBlocking { withTimeout(8_000L) {
                    entered.values.filter { it.isCompleted }.forEach { it.await().join() }
                } }
            } finally {
                onMain { playback.debug.verifyPlaybackSong = verifier }
            }
        }
    }

    @Test fun acceptedTuningSaveKeepsItsCapturedSongWhenNativeSuccessorCommitsBeforeTheWrite() {
        val (first, firstOriginal) = fixtureSong("save-handoff-a", 220.0, 10_000L)
        val (second, secondOriginal) = fixtureSong("save-handoff-b", 330.0, 30_000L)
        val priorPreparation = playback.nativeDiagnosticsSnapshotForTest()?.controllerPreparationStages
            ?.maxOfOrNull { it.sequence } ?: 0L
        onMain {
            checkNotNull(playback.startQueue(listOf(first, second), shuffle = false, autoplay = false))
            assertTrue(playback.selectSong(first, autoplay = false, positionMs = 9_000L))
        }
        waitUntil(8_000L) {
            val owner = playback.adapter.snapshot().publication.committed
            owner?.selection?.songId == first.id && owner.selection.startPositionMs == 9_000L &&
                owner.transport.presentedPositionMs == 9_000L && playback.state.value.songId == first.id &&
                !playback.state.value.loading &&
                playback.nativeDiagnosticsSnapshotForTest()?.runtime?.ownership?.committed == owner.owner.ownership
        }
        onMain { assertTrue(playback.setTempoAndPitch(1.25f, 3, lease(first), persist = false)) }
        runBlocking { playback.savedSongTrim(first) }
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        val firstIdentity = ownedSettingsIdentities.first { it.songId == first.id }
        val secondIdentity = ownedSettingsIdentities.first { it.songId == second.id }
        assertNull(store.read(firstIdentity, first.durationMs))
        waitUntil(8_000L) {
            playback.nativeDiagnosticsSnapshotForTest()?.controllerPreparationStages.orEmpty().any {
                it.sequence > priorPreparation && it.operation ==
                    app.stemwise.playback.engine.NativeControllerPreparationOperation.SUCCESSOR_PREWARM &&
                    it.stage == "original-primed"
            }
        }
        onMain { assertTrue(playback.play()) }
        var preparedSuccessor: app.stemwise.playback.engine.NativeRuntimeOwnership? = null
        waitUntil(8_000L) {
            val owner = playback.adapter.snapshot().publication.committed
            val automatic = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor
            val successor = automatic?.successorOwnership
            val ready = owner?.selection?.songId == first.id && automatic != null &&
                automatic.phase != app.stemwise.playback.engine.NativeRuntimeAutomaticSuccessorPhase.IDLE &&
                automatic.outgoingOwnership == owner.owner.ownership && successor != null &&
                successor != owner.owner.ownership && automatic.successorQueuedFrames > 0L &&
                automatic.firstFingerprintSequence > 0L && !automatic.cancellationDesired
            if (ready) preparedSuccessor = successor
            ready
        }
        val expectedSuccessor = checkNotNull(preparedSuccessor)
        val previousBeforeWrite = playback.debug.beforeSettingsWrite
        var hookCalls = 0
        try {
            onMain {
                val capturedLease = lease(first)
                playback.debug.beforeSettingsWrite = {
                    hookCalls++
                    playback.debug.beforeSettingsWrite = previousBeforeWrite
                    val next = runBlocking { withTimeout(12_000L) {
                        playback.adapter.state.first {
                            it.publication.committed?.let { owner ->
                                owner.selection.songId == second.id && owner.owner.ownership == expectedSuccessor
                            } == true
                        }
                    } }
                    assertEquals(expectedSuccessor, next.publication.committed?.owner?.ownership)
                }
                assertTrue(playback.commitTuningSettings(capturedLease))
            }
            assertEquals(1, hookCalls)
            runBlocking { playback.savedSongTrim(first); playback.savedSongTrim(second) }
            val savedFirst = checkNotNull(store.read(firstIdentity, first.durationMs))
            assertEquals(1.25f, savedFirst.tempo, 0f)
            assertEquals(3, savedFirst.semitones)
            assertNull(store.read(secondIdentity, second.durationMs))
            val native = playback.adapter.snapshot()
            assertEquals(second.id, native.publication.committed?.selection?.songId)
            assertEquals(expectedSuccessor, native.publication.committed?.owner?.ownership)
            val controls = checkNotNull(native.committedControls)
            assertEquals(1f, controls.tempo, 0f)
            assertEquals(0, controls.semitoneShift)
            assertEquals(first.sourceHash, PlaybackContentFixture.sourceHash(firstOriginal))
            assertEquals(second.sourceHash, PlaybackContentFixture.sourceHash(secondOriginal))
        } finally {
            onMain { playback.debug.beforeSettingsWrite = previousBeforeWrite }
        }
    }

    @Test fun permanentFocusLossWhileSavedLoopIsOpeningRestoresTheLoopWithoutResumingPlayback() =
        focusLossDuringSavedLoopRestore(AudioManager.AUDIOFOCUS_LOSS)

    @Test fun transientFocusLossWhileSavedLoopIsOpeningWaitsForGainWithoutRequestingFocusAgain() =
        focusLossDuringSavedLoopRestore(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

    private fun focusLossDuringSavedLoopRestore(focusChange: Int) {
        val (song, _) = fixtureSong("focus-loss", 220.0)
        selectPaused(song)
        onMain { assertTrue(playback.setLoop(1_000L, 3_000L, lease(song))) }
        runBlocking { playback.savedSongTrim(song) }
        val oldToken = checkNotNull(playback.adapter.snapshot().publication.committed).selection.token
        val entered = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val previousBeforeState = playback.debug.beforeState
        onMain {
            playback.debug.beforeState = { snapshot ->
                previousBeforeState?.invoke(snapshot)
                val committed = snapshot.publication.committed
                if (committed != null && committed.selection.songId == song.id &&
                    committed.selection.token != oldToken &&
                    snapshot.latestRequest?.selection == committed.selection &&
                    entered.complete(committed.selection.token.value)) {
                    withTimeout(30_000L) { release.await() }
                }
            }
        }
        try {
            onMain { assertTrue(playback.selectSong(song, autoplay = true)) }
            val incomingToken = runBlocking { withTimeout(8_000L) { entered.await() } }
            val focusRequestSequence = latestFocusRequestSequence()
            onMain {
                assertTrue(playback.playbackAudioFocusOwnedForTest())
                playback.debug.dispatchAudioFocusChange(focusChange)
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) assertFalse(playback.playbackAudioFocusOwnedForTest())
            }
            release.complete(Unit)
            waitUntil(8_000L) {
                val state = playback.state.value
                val owner = playback.adapter.snapshot().publication.committed
                owner?.selection?.token?.value == incomingToken && state.songId == song.id &&
                    state.loopA == 1_000L && state.loopB == 3_000L && !state.loading
            }
            onMain {
                val snapshot = playback.adapter.snapshot()
                val owner = checkNotNull(snapshot.publication.committed)
                assertEquals(incomingToken, owner.selection.token.value)
                assertEquals(1_000L, snapshot.committedControls?.loop?.startMs)
                assertEquals(3_000L, snapshot.committedControls?.loop?.endMs)
                assertFalse(playback.state.value.playing)
                assertEquals(focusRequestSequence, latestFocusRequestSequence())
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    assertFalse(checkNotNull(snapshot.latestRequest).controls.playWhenReady)
                    assertFalse(owner.transport.playWhenReady)
                    assertFalse(playback.state.value.playWhenReady)
                    assertFalse(playback.playbackAudioFocusOwnedForTest())
                } else {
                    assertTrue(checkNotNull(snapshot.latestRequest).controls.playWhenReady)
                    assertTrue(owner.transport.playWhenReady)
                    assertTrue(playback.state.value.playWhenReady)
                    assertEquals(AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS, playback.state.value.suppression)
                    playback.debug.dispatchAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                }
            }
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                waitUntil(8_000L) {
                    playback.adapter.snapshot().publication.committed?.selection?.token?.value == incomingToken &&
                        playback.state.value.playing && playback.graphDiagnostics().outputRms > .001f
                }
                assertEquals(AudioPlaybackSuppression.NONE, playback.state.value.suppression)
                assertEquals(focusRequestSequence, latestFocusRequestSequence())
                onMain { assertTrue(playback.pause()) }
            }
        } finally {
            release.complete(Unit)
            onMain { playback.debug.beforeState = previousBeforeState }
        }
    }

    private fun latestFocusRequestSequence(): Long = playback.playbackEventHistoryForTest()
        .filter { it.contains(":focus-request-result=") }
        .maxOfOrNull { it.substringBefore('@').toLong() } ?: 0L

    @Test fun songControlsRestoreTheirOnOffStatesAndCanonicalMixWithoutRetimingSavedLoops() {
        val (song, original) = installedRhythmFixtureSong()
        val (other, otherOriginal) = fixtureSong("controls-off", 330.0)
        val vocals = File(original.parentFile, "vocals.wav")
        val bass = File(original.parentFile, "bass.wav")
        writeSineWave(vocals, song.durationMs, 330.0)
        writeSineWave(bass, song.durationMs, 440.0)
        val stems = listOf(stemAsset("vocals", vocals, song.durationMs), stemAsset("bass", bass, song.durationMs),
            remainingAsset(bass, song.durationMs, 440.0))
        val sourceHashes = (listOf(checkNotNull(song.sourcePath), checkNotNull(other.sourcePath)) + stems.map { it.localPath })
            .associateWith { PlaybackContentFixture.sourceHash(application, it) }
        val timeline = runBlocking { application.services.analysisRepository.presentation(song.id, song.mediaRevision,
            checkNotNull(song.sourceHash)).timeline }
        assertTrue(timeline.beats.size > 2)
        selectPaused(song)
        applyTrim(song, SongTrimRange(500L, 6_000L))
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) { playback.activateStems(stems, lease(song)) } } }.getOrThrow()
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        val prepared = checkNotNull(playback.adapter.snapshot().publication.committed)
        val expectedToken = prepared.selection.token
        val expectedPackage = checkNotNull(prepared.stems.desired)
        onMain {
            assertTrue(playback.play())
        }
        waitUntil(8_000L) {
            val snapshot = playback.adapter.snapshot()
            val owner = snapshot.publication.committed
            val installed = owner?.stems?.installed
            owner != null && installed != null && owner.selection.token == expectedToken &&
                snapshot.latestRequest?.selection?.token == expectedToken &&
                owner.source.appliedMode == app.stemwise.playback.engine.EngineSourceMode.STEMS &&
                owner.source.appliedRevision == owner.source.desiredRevision &&
                owner.stems.phase == app.stemwise.playback.engine.PlaybackStemPackagePhase.INSTALLED &&
                installed.packageSignature == expectedPackage.packageSignature &&
                installed.requestRevision == expectedPackage.requestRevision &&
                playback.state.value.playing && playback.state.value.songId == song.id &&
                playback.state.value.loadedStemSignature == expectedPackage.packageSignature &&
                playback.state.value.stemMix.keys == stems.map { it.id }.toSet()
        }
        onMain {
            assertTrue(playback.pause())
            val control = lease(song)
            assertTrue("Vocals mixer rejected after exact native installation: state=${playback.state.value}; " +
                "session=${playback.adapter.snapshot()}",
                playback.commitStemMix("vocals", .31f, muted = false, solo = true, controlLease = control))
            assertTrue("Bass mixer rejected after exact native installation: state=${playback.state.value}; " +
                "session=${playback.adapter.snapshot()}",
                playback.commitStemMix("bass", .64f, muted = true, solo = false, controlLease = control))
            assertTrue(playback.setTempoAndPitch(1.25f, 3, control))
            assertTrue(playback.setMetronome(true, 128f, true, timeline.beats, timeline.meterSegments,
                subdivision = 2, numerator = 3, denominator = 4, controlLease = control))
            assertTrue(playback.setMetronomeVolume(.23f, control))
            assertTrue(playback.setLoop(1_000L, 2_500L, control))
        }
        runBlocking { playback.savedSongTrim(song) }
        selectPaused(other)
        onMain {
            val control = lease(other)
            assertTrue(playback.setTempoAndPitch(.75f, -2, control))
            assertTrue(playback.setLoop(null, null, control))
            assertTrue(playback.setMetronome(false, 120f, false,
                subdivision = 1, numerator = 4, denominator = 4, controlLease = control))
            assertTrue(playback.setMetronomeVolume(.8f, control))
        }
        runBlocking { playback.savedSongTrim(other) }
        selectPaused(song)
        waitUntil(8_000L) { playback.state.value.loopA == 1_000L && playback.state.value.loopB == 2_500L }
        assertEquals(1.25f, playback.state.value.tempo, 0f)
        assertEquals(3, playback.state.value.semitoneShift)
        assertTrue(playback.state.value.metronomeEnabled)
        assertEquals(.23f, playback.state.value.metronomeVolume, 0f)
        assertEquals(2, playback.state.value.metronomeSubdivision)
        assertEquals(3, playback.state.value.metronomeNumerator)
        val loop = checkNotNull(playback.adapter.snapshot().committedControls?.loop)
        assertEquals(1_500L, loop.startMs)
        assertEquals(3_000L, loop.endMs)
        val replacementStems = stems.map { it.copy(id = "reopened-${it.id}", separationSetId = "reopened-settings-set") }
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
            playback.activateStems(replacementStems, lease(song))
        } } }.getOrThrow()
        waitUntil(8_000L) { playback.state.value.sourceSwitchState is SourceSwitchState.Stems }
        assertEquals(StemMixState(.31f, false, true), playback.state.value.stemMix["reopened-vocals"])
        assertEquals(StemMixState(.64f, true, false), playback.state.value.stemMix["reopened-bass"])
        onMain { assertTrue(playback.seekTo(1_200L, lease(song))); assertTrue(playback.play()) }
        waitUntil(8_000L) {
            playback.state.value.playing && playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.graphDiagnostics().outputRms > .001f
        }
        onMain { assertTrue(playback.pause()) }
        applyTrim(song, SongTrimRange(4_000L, 7_000L))
        assertNull(playback.state.value.loopA)
        assertNull(playback.state.value.loopB)
        val identity = ownedSettingsIdentities.first { it.songId == song.id }
        runBlocking { playback.savedSongTrim(song) }
        val saved = checkNotNull(SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
            .read(identity, song.durationMs))
        assertEquals(1_500L, saved.loop.pointA)
        assertEquals(3_000L, saved.loop.pointB)
        assertTrue(saved.loop.enabled)
        assertEquals(StemMixState(.31f, false, true), saved.stemMix["vocals"])
        assertEquals(StemMixState(.64f, true, false), saved.stemMix["bass"])
        applyTrim(song, null)
        waitUntil(8_000L) { playback.state.value.loopA == 1_500L && playback.state.value.loopB == 3_000L }
        selectPaused(other)
        assertEquals(.75f, playback.state.value.tempo, 0f)
        assertEquals(-2, playback.state.value.semitoneShift)
        assertNull(playback.state.value.loopA)
        assertNull(playback.state.value.loopB)
        assertFalse(playback.state.value.metronomeEnabled)
        assertEquals(.8f, playback.state.value.metronomeVolume, 0f)
        assertEquals(PlaybackContentFixture.sourceHash(original), song.sourceHash)
        assertEquals(PlaybackContentFixture.sourceHash(otherOriginal), other.sourceHash)
        sourceHashes.forEach { (location, expected) ->
            assertEquals(expected, PlaybackContentFixture.sourceHash(application, location))
        }
    }

    @Test fun savedMetronomeReadFailureRetriesWhenVerifiedRhythmBecomesReadable() {
        val (song, original) = installedRhythmFixtureSong(publishManifest = false)
        val identity = ownedSettingsIdentities.first { it.songId == song.id }
        val store = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        store.update(identity, song.durationMs) { it.copy(metronome = SongMetronomeSettings(enabled = true)) }
        val packageDirectory = ownedPackageDirectories.last()
        val previousRevision = playback.songSettingsRevision.value
        onMain { playback.setPlaybackQueue(listOf(song), snapshotReady = true) }
        waitUntil(8_000L) { playback.songSettingsRevision.value > previousRevision &&
            playback.cachedSongPlaybackSettings(song)?.metronome?.enabled == true }
        selectPaused(song)
        waitUntil(8_000L) { application.services.analysisRepository.preparedPresentation(song)?.isFailure == true }
        assertFalse(playback.state.value.metronomeEnabled)
        assertTrue(store.read(identity, song.durationMs)?.metronome?.enabled == true)
        val manifest = checkNotNull(packageDirectory.createFile("application/json", "song.json"))
        checkNotNull(application.contentResolver.openOutputStream(manifest.uri, "w")).use {
            it.write(File(original.parentFile, "song.json").readBytes())
        }
        selectPaused(song)
        assertTrue(playback.state.value.metronomeEnabled)
        assertTrue(playback.state.value.metronomeSongSynced)
        assertTrue(checkNotNull(playback.adapter.snapshot().committedControls).metronome.beatGridMs.size > 1)
        runBlocking { playback.savedSongTrim(song) }
        assertTrue(store.read(identity, song.durationMs)?.metronome?.enabled == true)
    }

    @Test fun slowFirstSavedRhythmDoesNotDelayLaterQueueSettingsOrPublishDefaultsForAnUnreadableRow() {
        val songs = listOf(fixtureSong("slow-rhythm-first", 220.0).first,
            fixtureSong("unreadable-settings", 330.0).first, fixtureSong("ready-settings-last", 440.0).first)
        val settings = listOf(
            SongPlaybackSettings(tempo = .75f, metronome = SongMetronomeSettings(enabled = true)),
            SongPlaybackSettings(tempo = 1.25f),
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_000L), tempo = 1.5f, semitones = 5))
        val directory = File(application.filesDir, "song-playback-settings")
        val store = SongPlaybackSettingsStore(directory)
        songs.zip(settings).forEach { (song, saved) ->
            store.update(ownedSettingsIdentities.first { it.songId == song.id }, song.durationMs) { saved }
        }
        val unreadable = checkNotNull(directory.listFiles()?.singleOrNull { file ->
            file.extension == "json" && runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).optString("songId") == songs[1].id
            }.getOrDefault(false)
        })
        val validBytes = unreadable.readBytes()
        unreadable.writeText("{broken", Charsets.UTF_8)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val revision = playback.songSettingsRevision.value
        playback.debug.beforeMetronomeRead = { song ->
            if (song.id == songs[0].id) {
                entered.complete(Unit)
                withTimeout(8_000L) { release.await() }
            }
        }
        try {
            onMain { checkNotNull(playback.startQueue(songs, shuffle = false,
                autoplay = false, startId = songs.first().id)) }
            runBlocking { withTimeout(8_000L) { entered.await() } }
            waitUntil(8_000L) { playback.cachedSongPlaybackSettings(songs[2]) == settings[2] }
            assertEquals(settings[0], playback.cachedSongPlaybackSettings(songs[0]))
            assertEquals("A blocked rhythm must not delay another song's saved profile",
                settings[2], playback.cachedSongPlaybackSettings(songs[2]))
            assertNull("Unreadable settings must not become an authoritative default profile",
                playback.cachedSongPlaybackSettings(songs[1]))
            assertTrue("Ready profiles must notify UI while rhythm is still blocked",
                playback.songSettingsRevision.value > revision)
            assertEquals("{broken", unreadable.readText(Charsets.UTF_8))
        } finally {
            release.complete(Unit)
            playback.debug.beforeMetronomeRead = null
            unreadable.writeBytes(validBytes)
        }
    }

    @Test fun wholeQueueSettingsStayReadyThroughRapidManualAndShuffledSwitchesWithoutDiskRereads() {
        val songs = listOf(fixtureSong("hot-settings-a", 220.0).first,
            fixtureSong("hot-settings-b", 330.0).first, fixtureSong("hot-settings-c", 440.0).first)
        val expected = songs.zip(listOf(
            SongPlaybackSettings(tempo = .75f, semitones = -2),
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_000L), tempo = 1.25f, semitones = 3),
            SongPlaybackSettings(tempo = 1.5f, semitones = 5)
        )).associate { (song, settings) -> song.id to settings }
        val directory = File(application.filesDir, "song-playback-settings")
        val store = SongPlaybackSettingsStore(directory)
        songs.forEach { song ->
            val identity = ownedSettingsIdentities.first { it.songId == song.id }
            store.update(identity, song.durationMs) { expected.getValue(song.id) }
        }
        val replacement = fixtureSong("hot-settings-replacement", 550.0).first.let {
            it.copy(id = songs.first().id, videoId = songs.first().id)
        }
        onMain { playback.setPlaybackQueue(listOf(replacement), snapshotReady = true) }
        waitUntil(8_000L) { playback.cachedSongPlaybackSettings(replacement) != null }
        assertEquals(SongPlaybackSettings(), playback.cachedSongPlaybackSettings(replacement))
        onMain { checkNotNull(playback.startQueue(songs, shuffle = true, autoplay = false, startId = songs.first().id, seed = 17L)) }
        waitUntil(8_000L) { songs.all { playback.cachedSongPlaybackSettings(it) == expected.getValue(it.id) } }
        assertNull("Replacing the queue retires an Original identity outside its live membership",
            playback.cachedSongPlaybackSettings(replacement))
        waitUntil(8_000L) { playback.state.value.songId == songs.first().id && !playback.state.value.loading }
        onMain { assertTrue(playback.commitTuningSettings(lease(songs.first()))) }
        runBlocking { playback.savedSongTrim(songs.first()) }
        songs.forEach { song ->
            assertEquals("A completed write must retain every live queue identity",
                expected.getValue(song.id), playback.cachedSongPlaybackSettings(song))
        }
        assertNull(playback.cachedSongPlaybackSettings(replacement))

        // A forced settings read now fails. Switching must use the prepared exact-identity rows.
        val originals = directory.listFiles().orEmpty().filter { file ->
            file.extension == "json" && runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("songId") in expected }.getOrDefault(false)
        }.associateWith(File::readBytes)
        assertEquals(songs.size, originals.size)
        try {
            originals.keys.forEach { it.writeText("{broken", Charsets.UTF_8) }
            onMain {
                repeat(4) {
                    songs.forEach { song ->
                        assertEquals(expected.getValue(song.id), playback.cachedSongPlaybackSettings(song))
                        assertTrue(playback.selectSong(song, autoplay = false))
                    }
                }
            }
            fun assertLastSelection(id: String) {
                waitUntil(8_000L) {
                    val snapshot = playback.adapter.snapshot()
                    val owner = snapshot.publication.committed
                    playback.state.value.songId == id && !playback.state.value.loading &&
                        owner?.selection?.token == snapshot.latestRequest?.selection?.token
                }
                val settings = expected.getValue(id)
                assertEquals(settings.tempo, playback.state.value.tempo, 0f)
                assertEquals(settings.semitones, playback.state.value.semitoneShift)
                assertEquals(settings.trim?.startMs ?: 0L, playback.state.value.trimStartMs)
                assertFalse(playback.state.value.playing)
                assertNull(playback.state.value.notice)
            }
            assertLastSelection(songs.last().id)
            var finalId = songs.last().id
            onMain {
                assertTrue(playback.setRepeatMode("All"))
                val order = playback.state.value.queueOrder
                var index = order.indexOf(finalId)
                repeat(8) {
                    index = (index + 1) % order.size
                    finalId = order[index]
                    assertEquals(finalId, playback.nextTrack())
                    val song = songs.first { it.id == finalId }
                    assertEquals(expected.getValue(finalId), playback.cachedSongPlaybackSettings(song))
                }
            }
            assertLastSelection(finalId)
            val trimmedSong = songs[1]
            selectPaused(trimmedSong)
            onMain { assertTrue(playback.setTempoAndPitch(1.1f, -3, lease(trimmedSong))) }
            waitUntil(8_000L) { playback.state.value.notice?.title == "Settings not saved" }
            val refreshed = runCatching { runBlocking { playback.savedSongTrim(trimmedSong) } }
            assertTrue("A live pending edit must not hide an explicit durable read failure", refreshed.isFailure)
            assertNull("Pending controls cannot make an unreadable saved row authoritative",
                playback.cachedSongPlaybackSettings(trimmedSong))
            assertEquals(1.1f, playback.state.value.tempo, 0f)
            assertEquals(-3, playback.state.value.semitoneShift)
            assertEquals(1_000L, playback.state.value.trimStartMs)
            assertEquals(4_000L, playback.state.value.durationMs)
        } finally {
            originals.forEach { (file, bytes) -> file.writeBytes(bytes) }
        }
    }

    private fun installedRhythmFixtureSong(publishManifest: Boolean = true): Pair<Song, File> {
        val id = "SP" + UUID.randomUUID().toString().replace("-", "").take(9)
        val directory = File(application.cacheDir, "settings-$id").apply { check(mkdir()) }
        val original = File(directory, "original.opus")
        PlaybackContentFixture.own(application, original)
        application.assets.open("demo_library/DEMOUI00001/original.opus").use { input -> original.outputStream().use(input::copyTo) }
        val manifest = uiDemoLibraryManifest(application.assets.open("demo_library/DEMOUI00001/song.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }, "DEMOUI00001", id, 1)
        val manifestFile = File(directory, "song.json").apply { writeBytes(manifest) }
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        val packageDirectory = checkNotNull(root.rootDocument.createDirectory(id)).also(ownedPackageDirectories::add)
        fun publish(name: String, mimeType: String, bytes: ByteArray): DocumentFile {
            val document = checkNotNull(packageDirectory.createFile(mimeType, name))
            checkNotNull(application.contentResolver.openOutputStream(document.uri, "w")).use { it.write(bytes) }
            return document
        }
        val originalDocument = publish("original.opus", "audio/ogg", original.readBytes())
        if (publishManifest) publish("song.json", "application/json", manifest)
        publish("cover.png", "image/png", application.assets.open("demo_library/DEMOUI00001/cover.png").use { it.readBytes() })
        val song = Song(id = id, videoId = id, title = "Persistent controls", artist = "StemWise",
            durationMs = 12_000L, state = SongState.READY, accent = Color.Cyan, artworkGlyph = "S", hasStems = true,
            sourceHash = PlaybackContentFixture.sourceHash(original), sourcePath = PlaybackContentFixture.register(application, originalDocument.uri.toString()),
            sourceCodec = "opus", sourceContainer = "ogg", audioRevision = PlaybackContentFixture.sourceHash(original),
            mediaRevision = PlaybackContentFixture.sourceHash(manifestFile), bpm = 128f)
        ownedSettingsIdentities += SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id, checkNotNull(song.sourceHash))
        return song to original
    }

    private fun fixtureSong(label: String, frequency: Double, durationMs: Long = 6_000L): Pair<Song, File> {
        val id = "song-trim-$label-${UUID.randomUUID()}"
        val directory = File(application.cacheDir, id).apply { check(mkdir()) }
        val original = File(directory, "original.wav")
        writeSineWave(original, durationMs, frequency)
        val song = Song(id = id, videoId = id, title = label, artist = "StemWise",
            durationMs = durationMs, state = SongState.READY, accent = Color.Cyan, artworkGlyph = "S",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original), sourceCodec = "pcm_s16le",
            sourceContainer = "wav", audioRevision = id)
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        ownedSettingsIdentities += SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id, checkNotNull(song.sourceHash))
        return song to original
    }

    private fun selectPaused(song: Song) {
        val oldToken = playback.adapter.snapshot().latestRequest?.selection?.token
        onMain { assertTrue(playback.selectSong(song, autoplay = false)) }
        waitUntil(8_000L) {
            val state = playback.state.value
            val committed = playback.adapter.snapshot().publication.committed
            state.songId == song.id && !state.loading && !state.playing && committed != null &&
                committed.selection.token != oldToken
        }
    }

    private fun lease(song: Song): PlaybackControlLease = checkNotNull(playback.acquireControlLease(song.id))

    private fun applyTrim(song: Song, range: SongTrimRange?) {
        val oldToken = playback.adapter.snapshot().latestRequest?.selection?.token
        runBlocking { withTimeout(8_000L) { withContext(Dispatchers.Main) {
            playback.setSongTrim(song, range, lease(song))
        } } }.getOrThrow()
        try {
            waitUntil(8_000L) {
                val committed = playback.adapter.snapshot().publication.committed
                playback.state.value.songId == song.id && !playback.state.value.loading && committed != null &&
                    committed.selection.token != oldToken
            }
        } catch (failure: AssertionError) {
            val audioWorkerNames = Regex(
                "StemWise-NativeEngine|StemWiseAudioTrackFeeder|StemWise-(?:LoopPrep|Codec)-\\d+|" +
                    "StemWise-(?:PreparationRetirement|PcmProgress|ControlEvidence)"
            )
            val stacks = Thread.getAllStackTraces().entries
                .filter { audioWorkerNames.matches(it.key.name) }
                .sortedBy { it.key.name }
                .take(16)
                .joinToString("\n") { (thread, frames) ->
                    "${thread.name} ${thread.state}\n" + frames.take(32).joinToString("\n") { "  at $it" }
                }
            failure.addSuppressed(AssertionError("Audio workers at trim reload timeout:\n$stacks"))
            throw failure
        }
    }

    private fun assertWindow(startMs: Long, durationMs: Long, sourceDurationMs: Long) {
        val state = playback.state.value
        assertEquals(startMs, state.trimStartMs)
        assertEquals(durationMs, state.durationMs)
        assertEquals(sourceDurationMs, state.sourceDurationMs)
        val selection = checkNotNull(playback.adapter.snapshot().publication.committed).selection
        assertEquals(startMs, selection.playbackStartMs)
        assertEquals(startMs + durationMs, selection.playbackEndMs)
        assertEquals(sourceDurationMs, selection.durationMs)
    }
}
