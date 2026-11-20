package app.stemwise.playback

import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.analysis.ImportedAnalysisRepository.PresentationIdentity
import app.stemwise.analysis.ImportedSongPresentation
import app.stemwise.data.LastListeningSession
import app.stemwise.data.LastListeningSessionStore
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongPlaybackSettingsIdentity
import app.stemwise.data.SongPlaybackSettingsStore
import app.stemwise.data.SongTrimRange
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.GoldenQaDocumentsProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real capture/restoration ordering within one process; this is not process-death qualification. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class LastListeningSessionPlaybackInstrumentationTest : LoopPlaybackFixture() {
    private val store get() = LastListeningSessionStore(File(application.filesDir, "last-listening-session.json"))
    private val ownedDirectories = mutableListOf<File>()
    private val ownedSettings = mutableListOf<SongPlaybackSettingsIdentity>()
    private var prior: LastListeningSession? = null
    private var priorCaptured = false

    @Before fun prepareRootAndPreservePriorRow() = runBlocking {
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
        awaitWriterRetirement()
        prior = store.read()
        priorCaptured = true
        store.clear()
    }

    @After fun retireWorkAndRestorePriorRow() {
        onMain { field("listeningRestoreAllowed").setBoolean(playback, false) }
        playback.resetForInstrumentationIsolation(instrumentation)
        awaitWriterRetirement()
        if (priorCaptured) prior?.let(store::write) ?: store.clear()
        val settings = SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings"))
        ownedSettings.forEach(settings::clear)
        ownedDirectories.forEach { check(it.deleteRecursively()) }
    }

    @Test fun committedCaptureRestoresPausedExactQueueAndSourceCursorUsingCurrentTrim() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val selected = songs[1]
        val identity = identity(selected)
        ownedSettings += identity
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(identity, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_000L), tempo = 1.25f, semitones = 2)
        }
        onMain {
            playback.setPlaybackQueue(songs, snapshotReady = true)
            assertTrue(playback.selectSong(selected, autoplay = false))
        }
        awaitPaused(selected)
        onMain {
            assertTrue(playback.setShuffle(true, 39L))
            assertTrue(playback.setRepeatMode("All"))
            assertTrue(playback.seekTo(1_700L))
            assertTrue(playback.pause())
        }
        awaitWriterRetirement()
        val saved = checkNotNull(store.read())
        assertEquals(identity, saved.identity)
        assertEquals(2_700L, saved.sourcePositionMs)
        assertEquals(PlaybackSourceMode.ORIGINAL, saved.sourceMode)
        assertEquals(RepeatMode.ALL, saved.queue.repeatMode)
        assertTrue(saved.queue.shuffleEnabled)

        armFreshSessionAdmission()
        onMain { playback.setPlaybackQueue(emptyList(), snapshotReady = false) }
        assertEquals(saved, store.read())
        assertNull(playback.adapter.snapshot().latestRequest)
        onMain { playback.setPlaybackQueue(songs, snapshotReady = false) }
        assertNull(playback.adapter.snapshot().latestRequest)
        onMain { playback.setPlaybackQueue(songs, snapshotReady = true) }
        awaitPaused(selected)
        assertEquals(saved.queue.order, playback.state.value.queueOrder)
        assertEquals(saved.queue.currentId, playback.state.value.currentQueueSongId)
        assertEquals("All", playback.state.value.repeatMode)
        assertTrue(playback.state.value.shuffleEnabled)
        assertEquals(1_700L, playback.state.value.positionMs)
        assertEquals(1.25f, playback.state.value.tempo)
        assertEquals(2, playback.state.value.semitoneShift)
        assertEquals(2_700L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test fun wrongRootOrAudioNeverRestoresAndDoesNotRewriteTheRetainedSession() {
        val candidate = song("identity")
        val validIdentity = identity(candidate)
        for (changedIdentity in listOf(validIdentity.copy(libraryId = "another-library"),
            validIdentity.copy(treeUri = "content://another/root"), validIdentity.copy(originalSha256 = "b".repeat(64)))) {
            armFreshSessionAdmission()
            val saved = LastListeningSession(changedIdentity, PlaybackQueueSnapshot(listOf(candidate.id), 0), 2_000L)
            store.write(saved)
            onMain { playback.setPlaybackQueue(listOf(candidate), snapshotReady = true) }
            waitUntil(8_000L) { onMainValue { !field("listeningRestoreAllowed").getBoolean(playback) } }
            assertNull(playback.adapter.snapshot().latestRequest)
            assertEquals(saved, store.read())
        }
    }

    @Test fun readyProjectedSongWaitsForItsVerifiedIdentityBeforeRestoringThePausedSession() {
        val songs = listOf(song("projected-saved"), song("projected-next"), song("projected-previous"))
        val selected = songs.first()
        armFreshSessionAdmission()
        val saved = LastListeningSession(identity(selected), PlaybackQueueSnapshot(
            listOf(songs[2].id, selected.id, songs[1].id), 1, shuffleEnabled = true,
            shuffleSeed = 39L, repeatMode = RepeatMode.ALL), 2_300L)
        store.write(saved)
        onMain { playback.setPlaybackQueue(songs.map(::projectedSong), snapshotReady = true) }
        awaitListeningReadRetirement()
        assertNull(playback.adapter.snapshot().latestRequest)
        assertEquals(saved, store.read())

        onMain { playback.setPlaybackQueue(songs, snapshotReady = true) }
        assertRestoredPausedSession(selected, saved)
    }

    @Test fun verifiedQueueUpdateDuringBlockedSavedReadRestoresTheCurrentExactSong() {
        val selected = song("blocked-projected-saved")
        val neighbor = song("blocked-projected-neighbor")
        armFreshSessionAdmission()
        val saved = LastListeningSession(identity(selected), PlaybackQueueSnapshot(
            listOf(neighbor.id, selected.id), 1, shuffleEnabled = true,
            shuffleSeed = 17L, repeatMode = RepeatMode.ALL), 2_100L)
        store.write(saved)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actualStore = persistence().listeningStore
        val blocker = Thread {
            synchronized(actualStore) {
                entered.countDown()
                check(release.await(20L, TimeUnit.SECONDS))
            }
        }
        blocker.start()
        try {
            assertTrue(entered.await(8L, TimeUnit.SECONDS))
            onMain { playback.setPlaybackQueue(listOf(projectedSong(selected), projectedSong(neighbor)), snapshotReady = true) }
            waitUntil(8_000L) {
                Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.state == Thread.State.BLOCKED && stack.any {
                        it.className == LastListeningSessionStore::class.java.name && it.methodName == "read"
                    }
                }
            }
            onMain { playback.setPlaybackQueue(listOf(selected, neighbor), snapshotReady = true) }
            assertNull(playback.adapter.snapshot().latestRequest)
        } finally {
            release.countDown()
            blocker.join(8_000L)
            assertFalse(blocker.isAlive)
        }
        assertRestoredPausedSession(selected, saved)
    }

    @Test fun explicitSelectionRetiresProjectedRestoreBeforeVerificationArrives() {
        val savedSong = song("deferred-saved")
        val explicit = song("deferred-explicit")
        armFreshSessionAdmission()
        store.write(LastListeningSession(identity(savedSong),
            PlaybackQueueSnapshot(listOf(savedSong.id, explicit.id), 0), 2_000L))
        onMain { playback.setPlaybackQueue(listOf(projectedSong(savedSong), projectedSong(explicit)), snapshotReady = true) }
        awaitListeningReadRetirement()
        assertNull(playback.adapter.snapshot().latestRequest)
        onMain { assertTrue(playback.selectSong(explicit, autoplay = false)) }
        awaitPaused(explicit)
        val explicitToken = checkNotNull(playback.adapter.snapshot().latestRequest).selection.token

        onMain { playback.setPlaybackQueue(listOf(savedSong, explicit), snapshotReady = true) }
        awaitListeningReadRetirement()
        assertEquals(explicitToken, playback.adapter.snapshot().latestRequest?.selection?.token)
        assertEquals(explicit.id, playback.state.value.songId)
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test fun rootClearRetiresProjectedRestoreWithoutChangingTheSavedSession() {
        val selected = song("root-cleared-projected")
        armFreshSessionAdmission()
        val saved = LastListeningSession(identity(selected), PlaybackQueueSnapshot(listOf(selected.id), 0), 2_000L)
        store.write(saved)
        onMain { playback.setPlaybackQueue(listOf(projectedSong(selected)), snapshotReady = true) }
        awaitListeningReadRetirement()
        assertNull(playback.adapter.snapshot().latestRequest)

        runBlocking { playback.clearLibrarySession() }
        onMain { playback.setPlaybackQueue(listOf(selected), snapshotReady = true) }
        awaitListeningReadRetirement()
        assertNull(playback.adapter.snapshot().latestRequest)
        assertNull(playback.state.value.songId)
        assertEquals(saved, store.read())
    }

    @Test fun explicitSelectionSupersedesBlockedStartupReadWithoutRevivingSavedSong() {
        val savedSong = song("saved")
        val explicit = song("explicit")
        armFreshSessionAdmission()
        store.write(LastListeningSession(identity(savedSong), PlaybackQueueSnapshot(listOf(savedSong.id, explicit.id), 0), 2_000L))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actualStore = persistence().listeningStore
        val blocker = Thread {
            synchronized(actualStore) {
                entered.countDown()
                check(release.await(20L, TimeUnit.SECONDS))
            }
        }
        blocker.start()
        try {
            assertTrue(entered.await(8L, TimeUnit.SECONDS))
            onMain { playback.setPlaybackQueue(listOf(savedSong, explicit), snapshotReady = true) }
            waitUntil(8_000L) {
                Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.state == Thread.State.BLOCKED && stack.any {
                        it.className == LastListeningSessionStore::class.java.name && it.methodName == "read"
                    }
                }
            }
            onMain { assertTrue(playback.selectSong(explicit, autoplay = false)) }
        } finally {
            release.countDown()
            blocker.join(8_000L)
            assertFalse(blocker.isAlive)
        }
        awaitPaused(explicit)
        assertEquals(explicit.id, playback.adapter.snapshot().latestRequest?.selection?.songId)
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test fun unavailableSavedStemsKeepThePriorSessionUntilAnExplicitSourceChoice() {
        val selected = song("missing-stems")
        armFreshSessionAdmission()
        val saved = LastListeningSession(identity(selected), PlaybackQueueSnapshot(listOf(selected.id), 0),
            2_000L, PlaybackSourceMode.STEMS)
        store.write(saved)
        onMain { playback.setPlaybackQueue(listOf(selected), snapshotReady = true) }
        waitUntil(8_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading &&
            playback.state.value.notice?.title == "Saved Stems could not open" }
        awaitWriterRetirement()
        assertFalse(playback.state.value.playWhenReady)
        assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
        assertEquals(saved, store.read())
        onMain {
            assertTrue(playback.switchToOriginal(checkNotNull(playback.acquireControlLease(selected.id))))
        }
        awaitWriterRetirement()
        assertEquals(PlaybackSourceMode.ORIGINAL, store.read()?.sourceMode)
        assertEquals(saved.identity, store.read()?.identity)
    }

    @Test fun unavailableSavedStemsRetainIndependentLoopAndTrimOnTheExactPausedOriginal() {
        for (enabled in listOf(false, true)) {
            val selected = song("missing-stems-loop-$enabled")
            val exact = identity(selected)
            ownedSettings += exact
            SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
                SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L),
                    loop = app.stemwise.data.SongLoopPreference(2_000L, if (enabled) 4_000L else null, enabled))
            }
            armFreshSessionAdmission()
            val saved = LastListeningSession(exact, PlaybackQueueSnapshot(listOf(selected.id), 0),
                2_500L, PlaybackSourceMode.STEMS)
            store.write(saved)
            onMain { playback.setPlaybackQueue(listOf(selected), snapshotReady = true) }
            waitUntil(10_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading &&
                playback.state.value.notice?.title == "Saved Stems could not open" && playback.state.value.loopA == 1_000L }
            assertEquals(if (enabled) 3_000L else null, playback.state.value.loopB)
            assertEquals(1_500L, playback.state.value.positionMs)
            assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
            assertFalse(playback.state.value.playWhenReady)
            assertFalse(playback.state.value.playing)
            awaitWriterRetirement()
            assertEquals(saved, store.read())
        }
    }

    @Test fun metadataRefreshDuringStartupResolutionRetainsSavedCursorSourceQueueAndPausedIntent() {
        val selected = song("restore-refresh")
        val neighbor = song("restore-neighbor")
        val exact = identity(selected)
        ownedSettings += exact
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L))
        }
        armFreshSessionAdmission()
        val saved = LastListeningSession(exact,
            PlaybackQueueSnapshot(listOf(neighbor.id, selected.id), 1, true, 17L, RepeatMode.ALL),
            3_700L, PlaybackSourceMode.STEMS)
        store.write(saved)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == selected.id && attempts.incrementAndGet() == 1) {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(10_000L) { release.await() }
            }
            checkNotNull(verify?.invoke(requested))
        }
        try {
            onMain { playback.setPlaybackQueue(listOf(selected, neighbor), snapshotReady = true) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
            onMain { playback.setPlaybackQueue(listOf(selected.copy(title = "Updated title"), neighbor), snapshotReady = true,
                shuffle = true) }
            waitUntil(10_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading &&
                playback.state.value.notice?.title == "Saved Stems could not open" }
            assertEquals(2_700L, playback.state.value.positionMs)
            assertEquals(3_700L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            assertEquals(saved.queue.order, playback.state.value.queueOrder)
            assertTrue(playback.state.value.shuffleEnabled)
            assertEquals("All", playback.state.value.repeatMode)
            assertFalse(playback.state.value.playWhenReady)
            assertFalse(playback.state.value.playing)
            awaitWriterRetirement()
            assertEquals(saved, store.read())
        } finally {
            release.complete(Unit)
            playback.debug.verifyPlaybackSong = verify
        }
    }

    @Test fun resolvingAutoplayWaitsForTheExistingTransientFocusGrant() {
        for (loss in listOf(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            val selected = song("focus-outgoing-$loss")
            val incoming = song("focus-incoming-$loss")
            onMain { playback.setPlaybackQueue(listOf(selected, incoming), true); playback.selectSong(selected, true) }
            waitUntil(8_000L) { playback.state.value.songId == selected.id && playback.state.value.playing }
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val verify = playback.debug.verifyPlaybackSong
            playback.debug.verifyPlaybackSong = { requested ->
                if (requested.id == incoming.id) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withTimeout(8_000L) { release.await() }
                }
                checkNotNull(verify?.invoke(requested))
            }
            val requestField = PlaybackPlatformLifecycle::class.java.getDeclaredField("focusAcquisition").apply { isAccessible = true }
            try {
                onMain { assertTrue(playback.load(incoming, true)) }
                runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
                val focus = onMainValue { requestField.get(playback.platform) }
                onMain { playback.handleAudioFocusChange(loss, "test") }
                release.complete(Unit)
                waitUntil(8_000L) { playback.state.value.songId == incoming.id && !playback.state.value.loading }
                assertTrue(onMainValue { focus === requestField.get(playback.platform) })
                assertFalse(playback.state.value.playing)
                assertTrue(playback.state.value.playWhenReady)
                onMain { playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN, "test") }
                waitUntil(8_000L) { playback.state.value.playing }
                onMain { playback.pause() }
            } finally { release.complete(Unit); playback.debug.verifyPlaybackSong = verify }
        }
    }

    @Test fun autoplayAfterFailedFocusReacquisitionRequestsANewGrant() {
        val selected = song("focus-failed-outgoing")
        val incoming = song("focus-failed-incoming")
        onMain { playback.setPlaybackQueue(listOf(selected, incoming), true); playback.selectSong(selected, true) }
        waitUntil(8_000L) { playback.state.value.songId == selected.id && playback.state.value.playing }
        onMain {
            playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, "test")
            PlaybackPlatformLifecycle::class.java.getDeclaredField("nextFocusResultForTest").apply { isAccessible = true }
                .set(playback.platform, android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            playback.previousOrRestart()
            assertFalse(playback.playbackAudioFocusOwnedForTest())
            assertTrue(playback.selectSong(incoming, true))
        }
        waitUntil(8_000L) { playback.state.value.songId == incoming.id && playback.state.value.playing }
        assertTrue(onMainValue { playback.playbackAudioFocusOwnedForTest() })
        onMain { playback.pause() }
    }

    @Test fun failedLibrarySelectionPublishesTheSameNextCursorAsBothSessionCommands() {
        val songs = listOf(song("navigation-a"), song("navigation-b"), song("navigation-c"))
        onMain { playback.setPlaybackQueue(songs, true); playback.setRepeatMode("Off"); playback.selectSong(songs[2], false) }
        awaitPaused(songs[2])
        val token = playback.adapter.snapshot().publication.committed?.selection?.token
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == songs[0].id) throw java.io.IOException("Selected source is temporarily unavailable")
            checkNotNull(verify?.invoke(requested))
        }
        try {
            onMain { assertTrue(playback.selectSong(songs[0], false)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            assertEquals(token, playback.adapter.snapshot().publication.committed?.selection?.token)
            assertEquals(songs[2].id, playback.state.value.songId)
            assertTrue(playback.state.value.canGoNext)
            waitUntil(5_000L) { onMainValue {
                val player = playback.media3Player()
                player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT) &&
                    player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) && player.hasNextMediaItem()
            } }
            onMain { playback.media3Player().seekToNextMediaItem() }
            awaitPaused(songs[1])
        } finally { playback.debug.verifyPlaybackSong = verify }
    }

    @Test fun failedReplacementPreservesThePendingOwnersSavedTrimmedLoop() {
        val warm = song("pending-loop-warm")
        val selected = song("pending-loop-owner")
        val missing = song("pending-loop-missing")
        val exact = identity(selected)
        ownedSettings += exact
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L),
                loop = app.stemwise.data.SongLoopPreference(2_000L, 4_000L, true))
        }
        onMain { playback.setPlaybackQueue(listOf(warm, selected, missing), true); playback.selectSong(warm, false) }
        awaitPaused(warm)
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == missing.id) throw java.io.IOException("Replacement source is unavailable")
            checkNotNull(verify?.invoke(requested))
        }
        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
        try {
            onMain { assertTrue(playback.selectSong(selected, false)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            val pendingToken = playback.adapter.snapshot().latestRequest?.selection?.token
            assertEquals(selected.id, playback.adapter.snapshot().latestRequest?.selection?.songId)
            onMain { assertTrue(playback.selectSong(missing, false)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            assertEquals(pendingToken, playback.adapter.snapshot().latestRequest?.selection?.token)
        } finally {
            playback.debug.verifyPlaybackSong = verify
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
        }
        waitUntil(10_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading &&
            playback.state.value.loopA == 1_000L && playback.state.value.loopB == 3_000L }
        assertFalse(playback.state.value.playWhenReady)
        onMain { assertTrue(playback.play()) }
        waitUntil(8_000L) { playback.state.value.playing }
        val nativeLoop = checkNotNull(playback.adapter.snapshot().latestRequest?.controls?.loop)
        assertEquals(2_000L, nativeLoop.startMs)
        assertEquals(4_000L, nativeLoop.endMs)
        onMain { playback.pause() }
    }

    @Test fun pausedSuccessorVerificationCannotInheritOutgoingDeferredAutoplay() {
        val warm = song("deferred-autoplay-warm")
        val outgoing = song("deferred-autoplay-owner")
        val incoming = song("deferred-autoplay-paused-successor")
        val exact = identity(outgoing)
        ownedSettings += exact
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, outgoing.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L),
                loop = app.stemwise.data.SongLoopPreference(2_000L, 4_000L, true))
        }
        // Keep B outside automatic prewarm until its explicit selection is resolved.
        onMain { playback.setPlaybackQueue(listOf(warm, outgoing), true); playback.selectSong(warm, false) }
        awaitPaused(warm)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == incoming.id) {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(20_000L) { release.await() }
            }
            checkNotNull(verify?.invoke(requested))
        }
        try {
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
            onMain { assertTrue(playback.selectSong(outgoing, true)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            val outgoingToken = checkNotNull(playback.adapter.snapshot().latestRequest?.selection?.token)
            assertEquals(outgoing.id, playback.adapter.snapshot().latestRequest?.selection?.songId)
            onMain { assertTrue(playback.selectSong(incoming, false)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
            waitUntil(10_000L) { onMainValue {
                val session = playback.adapter.snapshot()
                session.publication.committed?.selection?.token == outgoingToken &&
                    session.latestRequest?.controls?.loop?.startMs == 2_000L &&
                    session.latestRequest?.controls?.loop?.endMs == 4_000L &&
                    field("pendingSettingsRestore").get(playback) == null
            } }
            // A restored before the held B lookup returns. Its autoplay must remain token-local.
            release.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            waitUntil(10_000L) {
                playback.adapter.snapshot().publication.committed?.selection?.songId == incoming.id &&
                    playback.state.value.songId == incoming.id && !playback.state.value.loading
            }
            assertFalse(playback.state.value.playWhenReady)
            assertFalse(playback.state.value.playing)
            assertEquals(false, playback.adapter.snapshot().latestRequest?.controls?.playWhenReady)
        } finally {
            release.complete(Unit)
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
            try {
                runBlocking { kotlinx.coroutines.withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() } }
            } finally {
                playback.debug.verifyPlaybackSong = verify
                onMain { playback.pause() }
            }
        }
    }

    @Test fun playDuringPausedNextTargetsIncomingBeforeAndAfterOutgoingSettingsSettle() {
        for (settledFirst in listOf(false, true)) incomingTransportDuringSettingsRestore(settledFirst, "play")
    }

    @Test fun incomingPauseAndFocusRecoveryKeepOutgoingSettingsTokenLocal() {
        for (mode in listOf("pause", "focus-failed", "focus-delayed"))
            incomingTransportDuringSettingsRestore(false, mode)
    }

    @Test fun failedOrSupersededIncomingPlayPreservesOutgoingSettingsAndOwnerFences() {
        for (mode in listOf("failed", "superseded")) incomingTransportDuringSettingsRestore(false, mode)
    }

    private fun incomingTransportDuringSettingsRestore(settledFirst: Boolean, mode: String) {
        loopFailurePhase = "incoming-$mode/settled=$settledFirst/warm-owner"
        val warm = song("incoming-command-warm-$mode-$settledFirst")
        val outgoing = song("incoming-command-outgoing-$mode-$settledFirst")
        val incoming = song("incoming-command-target-$mode-$settledFirst")
        val replacement = song("incoming-command-replacement-$mode-$settledFirst")
        val exact = identity(outgoing).also(ownedSettings::add)
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, outgoing.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L),
                loop = app.stemwise.data.SongLoopPreference(2_000L, 4_000L, true))
        }
        onMain { playback.setPlaybackQueue(listOf(warm, outgoing), true); playback.selectSong(warm, false) }
        awaitPaused(warm)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == incoming.id) {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(20_000L) { release.await() }
                if (mode == "failed") throw java.io.IOException("Incoming source is unavailable")
            }
            checkNotNull(verify?.invoke(requested))
        }
        fun resolving() = (field("selectionResolution").get(playback) as PlaybackSelectionResolutionOwner).current
        fun awaitOutgoingSettings() = waitUntil(10_000L) { onMainValue {
            val current = playback.adapter.snapshot()
            current.publication.committed?.selection?.songId == outgoing.id &&
                current.latestRequest?.controls?.loop?.startMs == 2_000L &&
                current.latestRequest?.controls?.loop?.endMs == 4_000L &&
                field("pendingSettingsRestore").get(playback) == null
        } }
        try {
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()); playback.selectSong(outgoing, false) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { playback.awaitManualSelectionResolutionForTest() } }
            onMain {
                assertTrue(field("pendingSettingsRestore").get(playback) != null)
                playback.setPlaybackQueue(listOf(warm, outgoing, incoming), true)
            }
            waitUntil(5_000L) { onMainValue {
                val player = playback.media3Player()
                player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT) &&
                    player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) &&
                    player.hasNextMediaItem()
            } }
            onMain {
                playback.media3Player().seekToNextMediaItem()
                assertEquals(incoming.id, resolving()?.songId)
                assertEquals(false, resolving()?.autoplay)
            }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
            if (settledFirst) {
                onMain { playback.debug.releaseNativeActorForSelectionBurst() }
                awaitOutgoingSettings()
            }
            onMain {
                if (mode == "focus-failed") {
                    PlaybackPlatformLifecycle::class.java.getDeclaredField("nextFocusResultForTest").apply { isAccessible = true }
                        .set(playback.platform, android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                    playback.media3Player().play()
                    assertEquals(false, resolving()?.autoplay)
                    assertFalse(playback.playbackAudioFocusOwnedForTest())
                }
                if (mode == "focus-delayed") playback.debug.delayNextAudioFocusRequest()
                playback.media3Player().play()
                assertEquals(true, resolving()?.autoplay)
                assertTrue(playback.state.value.playWhenReady)
                assertEquals(false, playback.adapter.snapshot().latestRequest?.controls?.playWhenReady)
                if (mode == "pause") {
                    playback.media3Player().pause()
                    assertEquals(false, resolving()?.autoplay)
                    assertFalse(playback.state.value.playWhenReady)
                }
                playback.debug.releaseNativeActorForSelectionBurst()
            }
            awaitOutgoingSettings()
            assertEquals(mode != "pause", playback.state.value.playWhenReady)
            if (mode == "superseded") onMain { assertTrue(playback.selectSong(replacement, false)) }
            release.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() } }
            val expected = when (mode) { "failed" -> outgoing; "superseded" -> replacement; else -> incoming }
            loopFailurePhase = "incoming-$mode/settled=$settledFirst/target-publication"
            waitUntil(10_000L) { playback.state.value.songId == expected.id && !playback.state.value.loading }
            if (mode == "failed") {
                assertEquals(1_000L, playback.state.value.loopA)
                assertEquals(3_000L, playback.state.value.loopB)
                assertFalse(playback.state.value.playWhenReady)
                assertFalse(playback.playbackAudioFocusOwnedForTest())
            } else if (mode == "pause" || mode == "superseded") {
                assertFalse(playback.state.value.playWhenReady)
                assertFalse(playback.state.value.playing)
            } else {
                assertTrue(playback.state.value.playWhenReady)
                if (mode == "focus-delayed") {
                    assertFalse(playback.state.value.playing)
                    onMain { playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN, "test") }
                }
                waitUntil(8_000L) { playback.state.value.playing }
            }
        } finally {
            release.complete(Unit)
            onMain { playback.debug.releaseNativeActorForSelectionBurst() }
            try { runBlocking { kotlinx.coroutines.withTimeout(10_000L) { playback.awaitManualSelectionResolutionForTest() } } }
            finally { playback.debug.verifyPlaybackSong = verify; onMain { playback.pause() } }
        }
    }

    @Test fun pausedRouteRecoverySettlesTheSameOwnerSeekAndTuningWithoutPlay() {
        for (previouslyPlayed in listOf(false, true)) {
            val selected = song("paused-route-$previouslyPlayed")
            onMain { playback.setPlaybackQueue(listOf(selected), true); playback.selectSong(selected, previouslyPlayed) }
            if (previouslyPlayed) waitUntil(8_000L) { playback.state.value.playing } else awaitPaused(selected)
            onMain { playback.pause(); playback.seekTo(1_700L) }
            waitUntil(8_000L) { playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs == 1_700L }
            val before = checkNotNull(playback.adapter.snapshot().publication.committed).owner.ownership
            onMain { playback.adapter.requestRouteRecovery() }
            waitUntil(8_000L) { playback.adapter.snapshot().publication.committed?.owner?.ownership?.let { it != before } == true }
            onMain {
                assertTrue(playback.seekTo(2_300L))
                assertTrue(playback.setTempoAndPitch(1.25f, 0, checkNotNull(playback.acquireControlLease(selected.id))))
            }
            waitUntil(8_000L) {
                val current = playback.adapter.snapshot()
                current.latestRequest?.controls?.seekRevision == current.publication.committed?.transport?.completedSeekRevision &&
                    current.publication.committed?.transport?.presentedPositionMs == 2_300L
            }
            assertFalse(playback.state.value.playWhenReady)
            assertFalse(playback.state.value.playing)
            assertEquals(selected.id, playback.state.value.songId)
            onMain { assertTrue(playback.play()) }
            waitUntil(8_000L) { playback.state.value.playing }
            onMain { playback.pause() }
        }
    }

    @Test fun terminalRelativeSeekUsesTheLogicalTrimmedEndForSessionAndArtwork() {
        val selected = song("terminal-back")
        val exact = identity(selected).also(ownedSettings::add)
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_000L))
        }
        onMain { playback.setPlaybackQueue(listOf(selected), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        for (artwork in listOf(false, true)) {
            onMain {
                assertTrue(playback.seekTo(1_000L))
                assertTrue(playback.seekTo(4_000L))
                assertTrue(playback.state.value.ended)
                if (artwork) assertTrue(playback.seekByWithReceipt(-500L, checkNotNull(playback.acquireControlLease(selected.id))) != null)
                else assertTrue(playback.seekByForSession(-500L) != null)
            }
            waitUntil(8_000L) { playback.state.value.positionMs == 3_500L && !playback.state.value.ended }
            assertEquals(4_500L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            assertFalse(playback.state.value.playWhenReady)
        }
    }

    @Test fun relativeSeekSupersedesAPendingOwnersTerminalIntentUsingThatOwnersEnd() {
        val outgoing = song("pending-terminal-outgoing")
        val selected = song("pending-terminal-incoming")
        onMain { playback.setPlaybackQueue(listOf(outgoing, selected), true); playback.selectSong(outgoing, false) }
        awaitPaused(outgoing)
        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()); playback.selectSong(selected, false) }
        try {
            waitUntil(8_000L) { playback.adapter.snapshot().latestRequest?.selection?.songId == selected.id }
            onMain {
                assertTrue(playback.seekToForSession(selected.durationMs) != null)
                assertTrue(playback.seekByForSession(-500L) != null)
                assertEquals(5_500L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
                playback.debug.releaseNativeActorForSelectionBurst()
            }
            awaitPaused(selected)
            assertEquals(5_500L, playback.state.value.positionMs)
            assertFalse(playback.state.value.ended)
            assertFalse(playback.state.value.playWhenReady)
        } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
    }

    @Test fun trimTerminalSeeksKeepThePreviewUnderEveryRepeatMode() {
        val selected = song("preview-terminal")
        val neighbor = song("preview-neighbor")
        onMain { playback.setPlaybackQueue(listOf(selected, neighbor), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        val preview = onMainValue { checkNotNull(playback.beginSongTrimPreview(selected,
            checkNotNull(playback.acquireControlLease(selected.id)))) }
        waitUntil(8_000L) { onMainValue { playback.songTrimPreviewIsReady(preview) } }
        for (mode in listOf("Off", "One", "All")) {
            for (relative in listOf(false, true)) {
                onMain {
                    assertTrue(playback.setRepeatMode(mode))
                    assertTrue(playback.seekTo(1_000L))
                    if (relative) assertTrue(playback.seekByForSession(Long.MAX_VALUE) != null)
                    else assertTrue(playback.seekTo(selected.durationMs))
                    assertEquals(selected.id, playback.state.value.songId)
                    assertTrue(playback.songTrimPreviewControlLease(preview) != null)
                    assertTrue(playback.state.value.ended)
                    assertFalse(playback.state.value.playWhenReady)
                }
            }
        }
        onMain { playback.cancelSongTrimPreview(preview) }
    }

    @Test fun anOldPreviewEndCannotPauseANewerBoundaryAudition() {
        val selected = song("preview-old-eos")
        onMain { playback.setPlaybackQueue(listOf(selected), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        val preview = onMainValue { checkNotNull(playback.beginSongTrimPreview(selected,
            checkNotNull(playback.acquireControlLease(selected.id)))) }
        waitUntil(8_000L) { onMainValue { playback.songTrimPreviewIsReady(preview) } }
        onMain { assertTrue(playback.play()) }
        waitUntil(8_000L) { playback.state.value.playing }
        onMain {
            assertTrue(playback.debug.enqueueCurrentAudibleEnd())
            assertTrue(playback.previewSongTrimBoundary(preview, 2_000L))
        }
        instrumentation.waitForIdleSync()
        assertTrue(playback.state.value.playWhenReady)
        assertEquals(selected.id, playback.state.value.songId)
        assertTrue(onMainValue { playback.songTrimPreviewControlLease(preview) != null })
        onMain { playback.cancelSongTrimPreview(preview) }
    }

    @Test fun pausedScrubFlushesItsFinalCursorInsideTheCaptureThrottle() {
        val selected = song("paused-scrub-save")
        onMain { playback.setPlaybackQueue(listOf(selected), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        onMain {
            assertTrue(playback.pause())
            assertTrue(playback.beginScrub(checkNotNull(playback.acquireControlLease(selected.id)) ))
            playback.previewScrub(1_700L)
            playback.commitScrub(1_700L)
        }
        awaitWriterRetirement()
        assertEquals(1_700L, checkNotNull(store.read()).sourcePositionMs)
        assertFalse(playback.state.value.playWhenReady)
    }

    @Test fun sameProcessTaskReopenRestoresPausedWithoutResettingTheStartupLatch() {
        val selected = song("task-reopen")
        val neighbor = song("task-neighbor")
        onMain { playback.setPlaybackQueue(listOf(neighbor, selected), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        onMain {
            playback.setRepeatMode("All")
            playback.setShuffle(true, 61L)
            assertTrue(playback.seekTo(1_700L))
            assertTrue(playback.pause())
        }
        awaitWriterRetirement()
        val saved = checkNotNull(store.read())
        onMain { playback.stopForTaskRemoval(); playback.onAppTaskAvailable() }
        waitUntil(8_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading }
        assertEquals(1_700L, playback.state.value.positionMs)
        assertEquals(saved.queue.order, playback.state.value.queueOrder)
        assertTrue(playback.state.value.shuffleEnabled)
        assertEquals("All", playback.state.value.repeatMode)
        assertFalse(playback.state.value.playWhenReady)
        assertEquals(saved, store.read())
    }

    @Test fun previousDuringSameSongResolutionRestartsAtTheTrimStartAndKeepsItsSecondPress() {
        val previous = song("previous-neighbor")
        val selected = song("previous-resolve")
        val exact = identity(selected).also(ownedSettings::add)
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L))
        }
        onMain { playback.setPlaybackQueue(listOf(previous, selected), true); playback.selectSong(selected, false) }
        awaitPaused(selected)
        onMain { playback.seekTo(2_000L) }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val original = playback.debug.verifyPlaybackSong
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == selected.id && attempts.incrementAndGet() == 1) {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(8_000L) { release.await() }
            }
            checkNotNull(original?.invoke(requested))
        }
        try {
            onMain { playback.load(selected, false, forceReload = true) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
            onMain { assertEquals(selected.id, playback.previousOrRestart()) }
            waitUntil(8_000L) { !playback.state.value.loading && playback.state.value.positionMs == 0L }
            assertEquals(1_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            onMain { assertEquals(previous.id, playback.previousOrRestart()) }
            awaitPaused(previous)
        } finally { release.complete(Unit); playback.debug.verifyPlaybackSong = original }
    }

    @Test fun deletePartsDuringAnUncommittedTrimmedSelectionRetainsThePublicCursor() = runBlocking {
        val outgoing = song("parts-outgoing")
        val selected = song("parts-pending")
        val exact = identity(selected).also(ownedSettings::add)
        SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
            SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_000L))
        }
        onMain { playback.setPlaybackQueue(listOf(outgoing, selected), true); playback.selectSong(outgoing, false) }
        awaitPaused(outgoing)
        val root = checkNotNull(application.services.libraryRootManager.captureAuthority())
        onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()); playback.selectSong(selected, false) }
        try {
            waitUntil(8_000L) { playback.adapter.snapshot().latestRequest?.selection?.songId == selected.id }
            assertEquals(1_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            assertTrue(playback.adapter.snapshot().publication.committed?.selection?.songId != selected.id)
            kotlinx.coroutines.withTimeout(8_000L) {
                kotlinx.coroutines.coroutineScope {
                    val deletion = async(kotlinx.coroutines.Dispatchers.Default) {
                        playback.deletePartsPreservingPlayback(root, selected.id) { admit -> admit(); Result.success(Unit) }
                    }
                    while (playback.adapter.snapshot().latestRequest?.selection?.songId == selected.id) kotlinx.coroutines.delay(1L)
                    onMain { playback.debug.releaseNativeActorForSelectionBurst() }
                    deletion.await().getOrThrow()
                }
            }
            awaitPaused(selected)
            assertEquals(0L, playback.state.value.positionMs)
            assertEquals(1_000L, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
            assertFalse(playback.state.value.playWhenReady)
        } finally { onMain { playback.debug.releaseNativeActorForSelectionBurst() } }
    }

    @Test fun cancelledTrimEditorStillRebuildsAutomaticSuccessorsAfterTheDurableWrite() = runBlocking {
        val selected = song("trim-commit")
        val neighbor = song("trim-neighbor")
        val exact = identity(selected).also(ownedSettings::add)
        onMain {
            playback.setPlaybackQueue(listOf(neighbor, selected), true)
            playback.setRepeatMode("Off"); playback.setShuffle(false)
            playback.selectSong(selected, false)
        }
        awaitPaused(selected)
        val owner = persistence()
        val writer = owner.javaClass.getDeclaredField("settingsWriter").apply { isAccessible = true }
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val editor = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
        val before = owner.settingsRevision.value
        var durable: kotlinx.coroutines.Job? = null
        try {
            onMain {
                writer.set(owner, release)
                editor.launch { playback.setSongTrim(selected, SongTrimRange(1_000L, 5_000L), playback.acquireControlLease(selected.id)) }
                durable = writer.get(owner) as kotlinx.coroutines.Job
                assertTrue(durable !== release)
                editor.cancel()
                playback.selectSong(neighbor, false)
            }
            awaitPaused(neighbor)
            waitUntil(8_000L) { playback.adapter.snapshot().automaticEndPlan is app.stemwise.playback.engine.NativeEngineAutomaticEndPlan.NextOwner }
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(8_000L) { checkNotNull(durable).join() }
            assertTrue(owner.settingsRevision.value > before)
            assertEquals(SongTrimRange(1_000L, 5_000L), owner.cachedSettings(exact)?.getOrThrow()?.trim)
            waitUntil(8_000L) {
                val plan = playback.adapter.snapshot().automaticEndPlan as? app.stemwise.playback.engine.NativeEngineAutomaticEndPlan.NextOwner
                plan?.item?.identity?.let { it.songId == selected.id && it.playbackStartMs == 1_000L && it.playbackEndMs == 5_000L } == true
            }
            loopFailurePhase = "paused successor seek acknowledgement"
            onMain { assertTrue(playback.seekTo(neighbor.durationMs - 100L)) }
            waitUntil(8_000L) {
                playback.adapter.snapshot().publication.committed?.transport?.presentedPositionMs == neighbor.durationMs - 100L
            }
            loopFailurePhase = "automatic handoff after cancelled trim commit"
            onMain { assertTrue(playback.play()) }
            waitUntil(8_000L) { playback.state.value.songId == selected.id && playback.state.value.playing }
            assertEquals(1_000L, playback.adapter.snapshot().publication.committed?.selection?.playbackStartMs)
            assertEquals(4_000L, playback.state.value.durationMs)
        } finally {
            release.complete(Unit); editor.cancel()
            kotlinx.coroutines.withTimeout(8_000L) { durable?.join() }
        }
    }

    @Test fun equalPendingSettingsWritesKeepTheLatestIntentAcrossAnIndependentEdit() = runBlocking {
        val selected = song("settings-aba")
        val exact = identity(selected).also(ownedSettings::add)
        val owner = persistence()
        val writer = owner.javaClass.getDeclaredField("settingsWriter").apply { isAccessible = true }
        val before = kotlinx.coroutines.CompletableDeferred<Unit>()
        val third = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            onMain {
                writer.set(owner, before)
                val enabled = SongPlaybackSettings(metronome = app.stemwise.data.SongMetronomeSettings(enabled = true))
                owner.persistSettings(exact, selected.durationMs, enabled)
                owner.persistSettings(exact, selected.durationMs, enabled.copy(metronome = enabled.metronome!!.copy(enabled = false)))
                val second = writer.get(owner) as kotlinx.coroutines.Job
                val held = scope.launch {
                    second.join(); entered.complete(Unit)
                    kotlinx.coroutines.withTimeout(8_000L) { third.await() }
                }
                writer.set(owner, held)
                owner.persistSettings(exact, selected.durationMs, enabled)
            }
            before.complete(Unit)
            kotlinx.coroutines.withTimeout(8_000L) { entered.await() }
            onMain {
                val pending = checkNotNull(owner.cachedSettings(exact)).getOrThrow()
                assertTrue(checkNotNull(pending.metronome).enabled)
                owner.persistSettings(exact, selected.durationMs, pending.copy(metronome = pending.metronome.copy(volume = .25f)))
            }
            third.complete(Unit)
            kotlinx.coroutines.withTimeout(8_000L) { (writer.get(owner) as kotlinx.coroutines.Job).join() }
            val saved = checkNotNull(SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).read(exact, selected.durationMs))
            assertTrue(checkNotNull(saved.metronome).enabled)
            assertEquals(.25f, saved.metronome.volume)
        } finally {
            before.complete(Unit); third.complete(Unit)
            kotlinx.coroutines.withTimeout(8_000L) { (writer.get(owner) as? kotlinx.coroutines.Job)?.join() }
            scope.cancel()
        }
    }

    @Test fun thousandSongQueuePreloadsExactCompactPresentationsAndBoundsRawCacheAcrossRapidSelections() {
        val context = ScaleContext(application)
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context, app.stemwise.data.LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        val network = object : app.stemwise.network.AppNetworkAccess {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(app.stemwise.network.AppNetworkState(false, false, true))
            override fun currentState() = state.value
        }
        // Normal services construction retains every real root/repository/preparation owner.
        val services = try {
            onMainValue { app.stemwise.StemwiseServices(context, network, database) }
        } catch (failure: Throwable) {
            runCatching { database.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { context.destroy() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val playback = services.audioPlayback
        val roots = services.libraryRootManager
        val external = services.externalLibraryRepository
        val analysis = services.analysisRepository
        fun preferences(): PlaybackPreferencesPersistence =
            (field("preferencesPersistence\$delegate").get(playback) as Lazy<*>).value as PlaybackPreferencesPersistence
        val prefix = "Q" + UUID.randomUUID().toString().replace("-", "").take(5)
        val ids = List(1_000) { prefix + it.toString().padStart(5, '0') }
        val ownedIds = ids.toHashSet()
        val providerBase = File(application.noBackupFilesDir, "golden-enrichment-document-provider").canonicalFile
        check(providerBase.isDirectory || providerBase.mkdir())
        val primaryFolder = File(providerBase, "$prefix-primary").canonicalFile
        val alternateFolder = File(providerBase, "$prefix-alternate").canonicalFile
        check(primaryFolder.parentFile == providerBase && primaryFolder.mkdir())
        check(alternateFolder.parentFile == providerBase && alternateFolder.mkdir())
        fun tree(folder: File) = android.provider.DocumentsContract.buildTreeDocumentUri(
            GoldenQaDocumentsProvider.AUTHORITY, "root/${folder.name}")
        val primary = tree(primaryFolder)
        val alternate = tree(alternateFolder)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (uri in listOf(primary, alternate)) application.grantUriPermission(application.packageName, uri,
            flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val sharedOriginal = File(context.cacheDir, "shared-original.opus").apply {
            application.assets.open("demo_library/DEMOUI00001/original.opus").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
        }
        val template = application.assets.open("demo_library/DEMOUI00001/song.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cover = application.assets.open("demo_library/DEMOUI00001/cover.png").use { it.readBytes() }
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val sampledPeakHeap = java.util.concurrent.atomic.AtomicLong(heapBefore)
        fun sampleHeap() {
            val used = runtime.totalMemory() - runtime.freeMemory()
            sampledPeakHeap.updateAndGet { maxOf(it, used) }
        }
        fun providerCall(method: String, argument: String? = null) = checkNotNull(application.contentResolver.call(
            GoldenQaDocumentsProvider.treeUri, method, argument, null))
        fun compactProjectionKeys(): Set<PresentationIdentity> {
            val prepared = analysis.javaClass.getDeclaredField("preparations").apply { isAccessible = true }
            val lock = analysis.javaClass.getDeclaredField("preparationLock").apply { isAccessible = true }.get(analysis)
            return synchronized(lock) {
                (prepared.get(analysis) as Map<*, *>).keys.map {
                    it as PresentationIdentity
                }.toSet()
            }
        }
        fun assertRawCacheBounded() {
            val cache = app.stemwise.enrichment.installedEnrichmentVerificationCache
            val (count, bytes) = synchronized(cache) {
                val entries = cache.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(cache) as Map<*, *>
                val bytes = cache.javaClass.getDeclaredField("retainedBytes").apply { isAccessible = true }.getLong(cache)
                entries.size to bytes
            }
            assertTrue("Raw manifest/waveform reservation exceeded 64MiB: $bytes", bytes in 0L..(64L * 1024L * 1024L))
            // Each nonempty manifest reserves its bytes plus 8MiB for decoded waveforms.
            assertTrue("Raw graphs grew with the full compact queue: $count", count <= 7)
        }
        fun expectedProjectionKeys(queue: List<Song>): Set<PresentationIdentity> {
            val root = checkNotNull(roots.captureAuthority())
            return queue.map { PresentationIdentity(
                root, it.id, checkNotNull(it.sourceHash), it.mediaRevision) }.toSet()
        }
        fun awaitCompactQueue(queue: List<Song>): Map<String, ImportedSongPresentation> {
            val preparation = onMainValue { field("presentationPreloadJob").get(playback) as kotlinx.coroutines.Job }
            // This checkpoint owns 1,000 real manifest projections, like the large cold scan above.
            waitUntil(120_000L) { sampleHeap(); assertRawCacheBounded(); preparation.isCompleted }
            runBlocking { preparation.join() }
            assertFalse("Full queue projection preparation was cancelled", preparation.isCancelled)
            assertSame(preparation, onMainValue { field("presentationPreloadJob").get(playback) })
            val expected = expectedProjectionKeys(queue)
            assertEquals(1_000, expected.size)
            assertEquals(expected, compactProjectionKeys())
            return queue.associate { selected ->
                selected.id to checkNotNull(analysis.preparedPresentation(selected)).getOrThrow()
            }
        }
        fun assertPreparedReuse(queue: List<Song>, captured: Map<String, ImportedSongPresentation>) {
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            runBlocking {
                for (selected in queue) {
                    assertSame(captured.getValue(selected.id), analysis.presentation(
                        selected.id, selected.mediaRevision, checkNotNull(selected.sourceHash)))
                    assertRawCacheBounded()
                    sampleHeap()
                }
            }
            val reads = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            assertEquals("Prepared compact projections reopened provider documents", 0L, reads.getLong("openDocumentCalls"))
            assertEquals("Prepared compact projections reread provider bytes", 0L, reads.getLong("readDocumentBytes"))
        }
        val ownedFolders = mutableListOf<File>()
        providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "true")
        providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
        external.packageProjectionPreparedForTest = { sampleHeap() }
        try {
            for ((index, id) in ids.withIndex()) {
                val folder = File(primaryFolder, id).canonicalFile
                check(folder.parentFile == primaryFolder && folder.mkdir())
                ownedFolders += folder
                val original = File(folder, app.stemwise.data.SelectedLibraryContract.ORIGINAL)
                try { android.system.Os.link(sharedOriginal.absolutePath, original.absolutePath) }
                catch (error: android.system.ErrnoException) {
                    if (error.errno !in setOf(android.system.OsConstants.EXDEV, android.system.OsConstants.EPERM,
                            android.system.OsConstants.EACCES,
                            android.system.OsConstants.EOPNOTSUPP)) throw error
                    sharedOriginal.copyTo(original)
                }
                val manifest = org.json.JSONObject(template.replace("DEMOUI00001", id))
                manifest.getJSONObject("metadata").put("title", "Rich queue ${index.toString().padStart(4, '0')}")
                manifest.getJSONObject("install").put("generatedDigest", app.stemwise.enrichment.installedEnrichmentDigest(manifest))
                File(folder, app.stemwise.data.SelectedLibraryContract.ENRICHMENT).writeText(manifest.toString(), Charsets.UTF_8)
                File(folder, app.stemwise.data.SelectedLibraryContract.COVER).writeBytes(cover)
                sampleHeap()
            }
            runBlocking {
                services.librarySessionCoordinator.switchTo(roots.validateCandidate(primary).getOrThrow()).getOrThrow()
                kotlinx.coroutines.withTimeout(120_000L) { services.coldOpenCoordinator.awaitCompletion() }
            }
            val queue = external.songs.value.filter { it.id in ownedIds }.sortedBy { it.id }
            assertEquals(1_000, queue.size)
            assertEquals(1_000, queue.map { it.mediaRevision }.toSet().size)
            val scanBytes = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS).getLong("readDocumentBytes")
            assertTrue("The 1,000 real packages were not read through the provider", scanBytes > 0L)
            val root = checkNotNull(roots.captureHandle())
            val settings = SongPlaybackSettingsStore(File(context.filesDir, "song-playback-settings"))
            for (selected in listOf(queue.first(), queue[937])) {
                settings.update(SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), selected.id,
                    checkNotNull(selected.sourceHash)), selected.durationMs) { SongPlaybackSettings(tempo = 1.25f, semitones = 2) }
            }
            providerCall(GoldenQaDocumentsProvider.METHOD_RESET_METRICS)
            onMain { playback.setPlaybackQueue(queue, snapshotReady = true) }
            waitUntil(15_000L) { sampleHeap(); preferences().savedSettings.size == 1_000 }
            assertTrue(preferences().savedSettings.values.all { it.isSuccess })
            val preparedQueue = awaitCompactQueue(queue)
            val preparationReads = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            val primaryDocumentPrefix = android.provider.DocumentsContract.getTreeDocumentId(primary) + "/"
            val openedPreparationIds = preparationReads.getStringArrayList("openedDocumentIds").orEmpty()
                .filter { it.startsWith(primaryDocumentPrefix) }.toSet()
            assertTrue("Real queue projection reads were bypassed", openedPreparationIds.isNotEmpty())
            assertTrue("Queue projection preparation opened media or artwork", openedPreparationIds.all { it.endsWith("/song.json") })
            assertEquals("Queue projection preparation decoded playable media", 0L, preparationReads.getLong("readMediaBytes"))
            assertPreparedReuse(queue, preparedQueue)
            for (selected in listOf(queue.first(), queue[937], queue.first())) {
                onMain { assertTrue(playback.selectSong(selected, autoplay = false)) }
                waitUntil(15_000L) { sampleHeap(); playback.state.value.songId == selected.id && !playback.state.value.loading }
                assertEquals(1.25f, playback.state.value.tempo)
                assertEquals(2, playback.state.value.semitoneShift)
                assertEquals(expectedProjectionKeys(queue), compactProjectionKeys())
                queue.forEach { assertSame(preparedQueue.getValue(it.id), analysis.preparedPresentation(it)?.getOrThrow()) }
                val compact = runBlocking { analysis.presentation(selected.id, selected.mediaRevision, checkNotNull(selected.sourceHash)) }
                assertSame(preparedQueue.getValue(selected.id), compact)
                assertTrue(compact.detail.lyricTracks.isNotEmpty())
                assertTrue(compact.timeline.beats.isNotEmpty())
                assertTrue(compact.waveformPeaks.isNotEmpty())
                assertRawCacheBounded()
            }
            val selectedReads = providerCall(GoldenQaDocumentsProvider.METHOD_METRICS)
            repeat(3) {
                runBlocking {
                    services.librarySessionCoordinator.switchTo(roots.validateCandidate(alternate).getOrThrow()).getOrThrow()
                    kotlinx.coroutines.withTimeout(30_000L) { services.coldOpenCoordinator.awaitCompletion() }
                }
                assertTrue(preferences().savedSettings.isEmpty())
                assertTrue(compactProjectionKeys().isEmpty())
                assertTrue((field("savedMetronomes").get(playback) as Map<*, *>).isEmpty())
                assertRawCacheBounded()
                runBlocking {
                    services.librarySessionCoordinator.switchTo(roots.validateCandidate(primary).getOrThrow()).getOrThrow()
                    kotlinx.coroutines.withTimeout(120_000L) { services.coldOpenCoordinator.awaitCompletion() }
                }
                assertEquals(1_000, external.songs.value.size)
                val restoredQueue = external.songs.value
                onMain { playback.setPlaybackQueue(restoredQueue, snapshotReady = true) }
                waitUntil(15_000L) { sampleHeap(); preferences().savedSettings.size == 1_000 }
                assertTrue(preferences().savedSettings.values.all { it.isSuccess })
                awaitCompactQueue(restoredQueue)
            }
            android.util.Log.i("StemwiseQueueScale", "distinctPackages=1000 settings=${preferences().savedSettings.size}" +
                " compact=${compactProjectionKeys().size} scanReadBytes=$scanBytes preparedManifestCount=${openedPreparationIds.size}" +
                " selectedReadBytes=${selectedReads.getLong("readDocumentBytes")}" +
                " realLibraryRoundTrips=3 heapBefore=$heapBefore sampledPeakHeapBytes=${sampledPeakHeap.get()}")
        } finally {
            external.packageProjectionPreparedForTest = null
            providerCall(GoldenQaDocumentsProvider.METHOD_MEASURE_READS, "false")
            runBlocking {
                services.coldOpenCoordinator.cancelAndJoin()
                services.enrichmentImports.cancelForLibraryChange()
                external.retireScansForLibrarySwitch()
                services.songBridge.cancelForLibraryChange()
                playback.clearLibrarySession()
            }
            onMain { playback.release() }
            // Persistence deliberately survives scope cancellation; retire its exact local owner
            // before deleting this isolated graph's private storage.
            waitUntil(8_000L) {
                val owner = preferences()
                val lock = checkNotNull(owner.javaClass.getDeclaredField("listeningWriteLock").apply { isAccessible = true }.get(owner))
                val listeningDone = synchronized(lock) {
                    !owner.javaClass.getDeclaredField("listeningWriterActive").apply { isAccessible = true }.getBoolean(owner)
                }
                val settingsWrite = owner.javaClass.getDeclaredField("settingsWriter").apply { isAccessible = true }
                    .get(owner) as? kotlinx.coroutines.Job
                listeningDone && settingsWrite?.isCompleted != false
            }
            database.close()
            for (folder in ownedFolders) {
                check(folder.canonicalFile.parentFile == primaryFolder && folder.name in ownedIds)
                folder.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
                check(folder.delete())
            }
            for (folder in listOf(primaryFolder, alternateFolder)) {
                check(folder.canonicalFile.parentFile == providerBase)
                folder.listFiles().orEmpty().forEach { check(it.isFile && it.delete()) }
                check(folder.delete())
            }
            for (uri in listOf(primary, alternate)) {
                if (application.contentResolver.persistedUriPermissions.any { it.uri == uri }) {
                    application.contentResolver.releasePersistableUriPermission(uri, flags)
                }
                application.revokeUriPermission(uri, flags)
            }
            context.destroy()
        }
    }

    @Test fun corruptSavedStemsRestoreTheIndependentLoopAndCannotChangeANewerExplicitOwner() {
        for (replaceOwner in listOf(false, true)) {
            val selected = song("corrupt-stems-$replaceOwner").copy(hasStems = true, stemRevision = "corrupt-fixture")
            val replacement = song("corrupt-stems-replacement-$replaceOwner")
            val exact = identity(selected)
            ownedSettings += exact
            SongPlaybackSettingsStore(File(application.filesDir, "song-playback-settings")).update(exact, selected.durationMs) {
                SongPlaybackSettings(trim = SongTrimRange(1_000L, 5_500L),
                    loop = app.stemwise.data.SongLoopPreference(2_000L, 4_000L, true))
            }
            armFreshSessionAdmission()
            val saved = LastListeningSession(exact, PlaybackQueueSnapshot(listOf(selected.id, replacement.id), 0),
                2_500L, PlaybackSourceMode.STEMS)
            store.write(saved)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val verify = playback.debug.verifyPlaybackStems
            if (replaceOwner) playback.debug.verifyPlaybackStems = { _, _ ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withTimeout(10_000L) { release.await() }
                    throw java.io.IOException("Corrupt saved part after owner replacement")
                }
            }
            try {
                withCorruptStemInventory(selected) {
                    onMain { playback.setPlaybackQueue(listOf(selected, replacement), snapshotReady = true) }
                    if (replaceOwner) {
                        runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
                        onMain { assertTrue(playback.selectSong(replacement, autoplay = false)) }
                        release.complete(Unit)
                        awaitPaused(replacement)
                        assertNull(playback.state.value.loopA)
                        assertNull(playback.state.value.loopB)
                        assertTrue(playback.state.value.notice?.title != "Saved Stems could not open")
                        assertEquals(replacement.id, playback.adapter.snapshot().latestRequest?.selection?.songId)
                    } else {
                        waitUntil(10_000L) { playback.state.value.songId == selected.id && !playback.state.value.loading &&
                            playback.state.value.notice?.title == "Saved Stems could not open" && playback.state.value.loopA == 1_000L }
                        assertEquals(3_000L, playback.state.value.loopB)
                        assertEquals(1_500L, playback.state.value.positionMs)
                        awaitWriterRetirement()
                        assertEquals(saved, store.read())
                    }
                    assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
                    assertFalse(playback.state.value.playWhenReady)
                }
            } finally { release.complete(Unit); playback.debug.verifyPlaybackStems = verify }
        }
    }

    @Test fun changedAudioOrExplicitSelectionDuringStartupResolverCancelsTheOldRestoreIntent() {
        for (explicitSelection in listOf(false, true)) {
            val selected = song("superseded-restore-$explicitSelection")
            val other = song("replacement-audio-$explicitSelection", frequency = 330.0)
            val replacement = if (explicitSelection) other else other.copy(id = selected.id, videoId = selected.videoId)
            armFreshSessionAdmission()
            store.write(LastListeningSession(identity(selected), PlaybackQueueSnapshot(listOf(selected.id, other.id), 0),
                3_700L, PlaybackSourceMode.STEMS))
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            val verify = playback.debug.verifyPlaybackSong
            playback.debug.verifyPlaybackSong = { requested ->
                if (requested.id == selected.id && attempts.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withTimeout(10_000L) { release.await() }
                }
                checkNotNull(verify?.invoke(requested))
            }
            try {
                onMain { playback.setPlaybackQueue(listOf(selected, other), snapshotReady = true) }
                runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
                onMain {
                    if (explicitSelection) assertTrue(playback.selectSong(replacement, autoplay = false))
                    else playback.setPlaybackQueue(listOf(replacement, other), snapshotReady = true)
                }
                awaitPaused(replacement)
                assertEquals(0L, playback.state.value.positionMs)
                assertEquals(replacement.sourceHash, playback.adapter.snapshot().latestRequest?.selection?.originalContentSha256)
                assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
                assertFalse(playback.state.value.playWhenReady)
                assertTrue(playback.state.value.notice?.title != "Saved Stems could not open")
                awaitWriterRetirement()
                assertEquals(identity(replacement), store.read()?.identity)
                assertEquals(0L, store.read()?.sourcePositionMs)
            } finally { release.complete(Unit); playback.debug.verifyPlaybackSong = verify }
        }
    }

    @Test fun pausedStateCanPrecedeListeningCaptureButMainBarrierRetiresTheExactReplacement() {
        val selected = song("capture-publication-outgoing")
        val other = song("capture-publication-replacement", frequency = 330.0)
        val replacement = other.copy(id = selected.id, videoId = selected.videoId)
        armFreshSessionAdmission()
        val saved = LastListeningSession(identity(selected), PlaybackQueueSnapshot(listOf(selected.id, other.id), 0),
            3_700L, PlaybackSourceMode.STEMS)
        store.write(saved)
        val resolverEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolverRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val publicationEntered = CountDownLatch(1)
        val publicationRelease = CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val collectorFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val verify = playback.debug.verifyPlaybackSong
        playback.debug.verifyPlaybackSong = { requested ->
            if (requested.id == selected.id && attempts.incrementAndGet() == 1) {
                resolverEntered.complete(Unit)
                kotlinx.coroutines.withTimeout(20_000L) { resolverRelease.await() }
            }
            checkNotNull(verify?.invoke(requested))
        }
        val collectorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                collectorFailure.set(error)
            })
        val collector = collectorScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            playback.state.collect { state ->
                val snapshot = playback.adapter.snapshot()
                val committed = snapshot.publication.committed
                if (state.songId == replacement.id && !state.loading && !state.playing &&
                    state.selectionToken == committed?.selection?.token?.value &&
                    committed?.selection?.originalContentSha256 == replacement.sourceHash &&
                    publicationEntered.count > 0L) {
                    check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    publicationEntered.countDown()
                    check(publicationRelease.await(20L, TimeUnit.SECONDS))
                }
            }
        }
        try {
            onMain { playback.setPlaybackQueue(listOf(selected, other), snapshotReady = true) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { resolverEntered.await() } }
            // Posting lets the test observe the publishing turn while its real collector holds it.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                playback.setPlaybackQueue(listOf(replacement, other), snapshotReady = true)
            }
            assertTrue(publicationEntered.await(8L, TimeUnit.SECONDS))
            val snapshot = playback.adapter.snapshot()
            val committed = checkNotNull(snapshot.publication.committed)
            assertEquals(replacement.sourceHash, committed.selection.originalContentSha256)
            assertEquals(committed.selection.token, snapshot.latestRequest?.selection?.token)
            assertEquals(0L, playback.state.value.positionMs)
            assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
            assertFalse(playback.state.value.playWhenReady)
            // Writer-idle alone can precede capture of the state already visible to observers.
            awaitWriterRetirement()
            assertEquals(saved, store.read())
            val owner = persistence()
            val lock = checkNotNull(owner.javaClass.getDeclaredField("listeningWriteLock").apply { isAccessible = true }.get(owner))
            synchronized(lock) {
                assertNull(owner.javaClass.getDeclaredField("pendingListeningWrite").apply { isAccessible = true }.get(owner))
            }
            publicationRelease.countDown()
            onMain { assertEquals(committed.selection.token.value, playback.state.value.selectionToken) }
            awaitWriterRetirement()
            assertEquals(identity(replacement), store.read()?.identity)
            assertEquals(0L, store.read()?.sourcePositionMs)
            assertEquals(PlaybackSourceMode.ORIGINAL, store.read()?.sourceMode)
            assertTrue(playback.state.value.notice?.title != "Saved Stems could not open")
        } finally {
            publicationRelease.countDown()
            resolverRelease.complete(Unit)
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { collector.cancel(); collector.join() } }
            collectorScope.cancel()
            playback.debug.verifyPlaybackSong = verify
        }
        assertNull(collectorFailure.get())
    }

    @Test fun sameSongNewAudioCommitsItsPausedSessionInsideTheCaptureThrottleWindow() {
        val selected = song("capture-throttle-outgoing")
        val other = song("capture-throttle-replacement", frequency = 330.0)
        val replacement = other.copy(id = selected.id, videoId = selected.videoId)
        onMain {
            playback.setPlaybackQueue(listOf(selected), snapshotReady = true)
            assertTrue(playback.selectSong(selected, autoplay = false))
        }
        awaitPaused(selected)
        onMain { assertTrue(playback.pause()) }
        awaitWriterRetirement()
        assertEquals(identity(selected), store.read()?.identity)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val held = java.util.concurrent.atomic.AtomicBoolean()
        val beforeState = playback.debug.beforeState
        playback.debug.beforeState = { snapshot ->
            beforeState?.invoke(snapshot)
            val committed = snapshot.publication.committed
            if (committed != null && committed.selection.originalContentSha256 == replacement.sourceHash &&
                snapshot.latestRequest?.selection?.token == committed.selection.token && held.compareAndSet(false, true)) {
                entered.complete(Unit)
                kotlinx.coroutines.withTimeout(20_000L) { release.await() }
                // Fix the clock at the real committed-owner boundary, independently of decode latency.
                field("lastListeningCaptureAtMs").setLong(playback, android.os.SystemClock.elapsedRealtime())
            }
        }
        try {
            onMain { assertTrue(playback.selectSong(replacement, autoplay = false)) }
            runBlocking { kotlinx.coroutines.withTimeout(8_000L) { entered.await() } }
            val snapshot = playback.adapter.snapshot()
            val committed = checkNotNull(snapshot.publication.committed)
            assertEquals(replacement.sourceHash, committed.selection.originalContentSha256)
            assertEquals(committed.selection.token, snapshot.latestRequest?.selection?.token)
            assertFalse(committed.transport.playWhenReady)
            assertEquals(0L, committed.selection.sourcePositionMs(committed.transport.presentedPositionMs))
            assertEquals(identity(selected), store.read()?.identity)
            release.complete(Unit)
            waitUntil(8_000L) { onMainValue {
                playback.state.value.selectionToken == committed.selection.token.value &&
                    !playback.state.value.loading && !playback.state.value.playing
            } }
            awaitWriterRetirement()
            assertEquals(identity(replacement), store.read()?.identity)
            assertEquals(0L, store.read()?.sourcePositionMs)
            assertEquals(PlaybackSourceMode.ORIGINAL, store.read()?.sourceMode)
            assertEquals(0L, playback.state.value.positionMs)
            assertEquals(PlaybackSourceMode.ORIGINAL, playback.state.value.sourceMode)
            assertFalse(playback.state.value.playWhenReady)
        } finally {
            release.complete(Unit)
            onMain { playback.debug.beforeState = beforeState }
        }
    }

    /** Only the asynchronous inventory boundary is supplied; actual part hash verification and restore stay real. */
    @Suppress("UNCHECKED_CAST")
    private fun withCorruptStemInventory(song: Song, block: () -> Unit) {
        val repository = application.services.externalLibraryRepository
        val sets = repository.javaClass.getDeclaredField("mutableStemSets").apply { isAccessible = true }
            .get(repository) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<app.stemwise.data.SeparationSetEntity>>>
        val stems = repository.javaClass.getDeclaredField("mutableStems").apply { isAccessible = true }
            .get(repository) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<app.stemwise.data.StemAssetEntity>>>
        val setId = "${song.id}-corrupt-set"
        val set = app.stemwise.data.SeparationSetEntity(setId, song.id, "fixture-source", checkNotNull(song.sourceHash),
            "fixture", "fixture", "Fixture", null, "READY", true, 1L)
        val part = app.stemwise.data.StemAssetEntity("vocals", setId, "Vocals", "vocals", checkNotNull(song.sourcePath),
            "f".repeat(64), song.durationMs, "pcm_s16le", 48_000, 2, true)
        stems.value = stems.value + (setId to listOf(part))
        sets.value = sets.value + (song.id to listOf(set))
        try { block() } finally {
            sets.value = sets.value - song.id
            stems.value = stems.value - setId
        }
    }

    private fun armFreshSessionAdmission() {
        runBlocking { playback.clearLibrarySession() }
        waitUntil(8_000L) { playback.adapter.snapshot().latestRequest == null &&
            playback.adapter.snapshot().publication.committed == null }
        awaitWriterRetirement()
        // Only startup admission is reset; preparation, verification, queue and persistence stay real.
        onMain { field("listeningRestoreAllowed").setBoolean(playback, true) }
    }

    private fun awaitWriterRetirement() = waitUntil(8_000L) {
        val owner = persistence()
        val lock = checkNotNull(owner.javaClass.getDeclaredField("listeningWriteLock").apply { isAccessible = true }.get(owner))
        synchronized(lock) { !owner.javaClass.getDeclaredField("listeningWriterActive").apply { isAccessible = true }.getBoolean(owner) }
    }

    private fun awaitPaused(song: Song) = waitUntil(8_000L) {
        onMainValue {
            val state = playback.state.value
            val snapshot = playback.adapter.snapshot()
            val committed = snapshot.publication.committed?.selection
            val latest = snapshot.latestRequest?.selection
            // Finish the publishing/capture turn and bind readiness to this exact audio owner.
            committed != null && committed.songId == song.id &&
                committed.originalContentSha256 == song.sourceHash &&
                latest != null && latest.token == committed.token && latest.originalContentSha256 == song.sourceHash &&
                state.selectionToken == committed.token.value && state.songId == song.id &&
                !state.loading && !state.playing
        }
    }

    private fun projectedSong(song: Song) = song.copy(
        sourcePath = null, sourceHash = null, audioRevision = "", mediaRevision = ""
    )

    private fun awaitListeningReadRetirement() = waitUntil(8_000L) {
        onMainValue {
            val owner = field("listeningRestoreJob").get(playback) as? kotlinx.coroutines.Job
            if (owner != null && (owner.isCompleted || owner.isCancelled)) {
                throw AssertionError("Listening restore retained a retired owner: active=${owner.isActive}, " +
                    "completed=${owner.isCompleted}, cancelled=${owner.isCancelled}; " +
                    "allowed=${field("listeningRestoreAllowed").getBoolean(playback)}; " +
                    "rootReady=${application.services.libraryRootManager.state.value.ready}; " +
                    "libraryCommandsAllowed=${playback.librarySessionCommandsAllowedForTest()}")
            }
            owner == null
        }
    }

    private fun assertRestoredPausedSession(song: Song, saved: LastListeningSession) {
        awaitPaused(song)
        assertEquals(saved.queue.order, playback.state.value.queueOrder)
        assertEquals(saved.queue.currentId, playback.state.value.currentQueueSongId)
        assertEquals(saved.queue.shuffleEnabled, playback.state.value.shuffleEnabled)
        assertEquals(saved.queue.repeatMode.label, playback.state.value.repeatMode)
        assertEquals(saved.sourceMode, playback.state.value.sourceMode)
        assertEquals(saved.sourcePositionMs, playback.state.value.positionMs)
        assertEquals(saved.sourcePositionMs, playback.adapter.snapshot().latestRequest?.controls?.requestedPositionMs)
        assertEquals(saved.identity.originalSha256, playback.adapter.snapshot().latestRequest?.selection?.originalContentSha256)
        assertFalse(playback.state.value.playWhenReady)
    }

    private fun persistence(): PlaybackPreferencesPersistence =
        (field("preferencesPersistence\$delegate").get(playback) as Lazy<*>).value as PlaybackPreferencesPersistence

    private fun field(name: String) = AudioPlayback::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun <T> onMainValue(read: () -> T): T {
        var value: T? = null
        onMain { value = read() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    private fun identity(song: Song): SongPlaybackSettingsIdentity {
        val root = checkNotNull(application.services.libraryRootManager.captureHandle())
        return SongPlaybackSettingsIdentity(root.libraryId, root.treeUri.toString(), song.id, checkNotNull(song.sourceHash))
    }

    private class ScaleContext(base: android.content.Context) : android.content.ContextWrapper(base) {
        private val namespace = "queue-scale-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, namespace).apply { check(mkdir()) }
        private val files = File(directory, "files").apply { check(mkdir()) }
        private val cache = File(directory, "cache").apply { check(mkdir()) }
        private val noBackup = File(directory, "no-backup").apply { check(mkdir()) }
        private val preferences = linkedSetOf<String>()
        override fun getApplicationContext(): android.content.Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            baseContext.getSharedPreferences("$namespace-$name".also(preferences::add), mode)
        fun destroy() {
            preferences.forEach { check(baseContext.deleteSharedPreferences(it)) }
            check(directory.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile)
            check(directory.deleteRecursively())
        }
    }

    private fun song(label: String, frequency: Double = 220.0): Song {
        val id = "listening-$label-${UUID.randomUUID()}"
        val directory = File(application.cacheDir, id).apply { check(mkdir()) }.also(ownedDirectories::add)
        val original = File(directory, "original.wav")
        writeSineWave(original, 6_000L, frequency)
        return Song(id = id, videoId = id, title = label, artist = "StemWise", durationMs = 6_000L,
            state = SongState.READY, accent = Color.Cyan, artworkGlyph = "S",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original), sourceCodec = "pcm_s16le",
            sourceContainer = "wav", audioRevision = id)
    }
}
