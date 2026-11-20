package app.stemwise.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.R
import app.stemwise.StemwiseApplication
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.session.MAXIMUM_SESSION_ARTWORK_BYTES
import app.stemwise.playback.session.SESSION_MEDIA_REVISION_KEY
import app.stemwise.playback.engine.EngineLoopConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class MediaSessionLifecycleInstrumentationTest {
    @Test fun retiredPlatformFocusCallbacksCannotAffectANewAcquisition() {
        val changes = mutableListOf<Int>()
        val lifecycle = PlaybackPlatformLifecycle(application, android.os.Handler(android.os.Looper.getMainLooper()),
            changes::add, {})
        val requestField = PlaybackPlatformLifecycle::class.java.getDeclaredField("focusAcquisition").apply { isAccessible = true }
        fun listener(): android.media.AudioManager.OnAudioFocusChangeListener {
            val request = checkNotNull(requestField.get(lifecycle))
            return request.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(request) as android.media.AudioManager.OnAudioFocusChangeListener
        }
        try {
            onMain {
                lifecycle.delayNextFocusRequestForTest()
                lifecycle.requestFocus()
                val old = listener()
                lifecycle.abandonFocus()
                lifecycle.delayNextFocusRequestForTest()
                lifecycle.requestFocus()
                val current = listener()
                assertTrue(old !== current)
                for (change in listOf(android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    android.media.AudioManager.AUDIOFOCUS_GAIN)) old.onAudioFocusChange(change)
                assertTrue(changes.isEmpty())
                current.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
                current.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                assertEquals(listOf(android.media.AudioManager.AUDIOFOCUS_GAIN,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT), changes)
                lifecycle.abandonFocus()
                current.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
                assertEquals(2, changes.size)
            }
        } finally { onMain { lifecycle.close() } }
    }

    @Test fun backgroundControllerPlayPromotesBeforeFocusAndStaysSilentUntilPermission() {
        val source = File(application.cacheDir, "background-focus-order.wav")
        writeWave(source, 12_000L)
        val song = song(0, source, 12_000L)
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
        waitUntil(8_000L) { PlaybackService.isActiveForTest() && playback.state.value.songId == song.id &&
            !playback.state.value.loading }
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        try {
            val controller = future.get(10L, TimeUnit.SECONDS)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            var framesBeforePlay = 0L
            onMain {
                playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
                PlaybackService.releaseDeniedPlaybackFocus()
                assertTrue("Paused preparation retains its notification service", PlaybackService.isForegroundForTest())
                framesBeforePlay = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output).callbackFrames
                playback.debug.delayNextAudioFocusRequest()
                controller.play()
            }
            waitUntil(5_000L) { playback.state.value.playWhenReady &&
                playback.state.value.suppression == AudioPlaybackSuppression.TRANSIENT_AUDIO_FOCUS_LOSS }
            onMain {
                assertTrue("Foreground promotion must precede focus", PlaybackService.isForegroundForTest())
                assertTrue(playback.playbackEventHistoryForTest().any { it.contains("focus-foreground=true") })
                assertFalse(playback.state.value.playing)
                assertEquals("A delayed focus grant cannot emit audio", framesBeforePlay,
                    requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output).callbackFrames)
                // Cancel the delayed request, then exercise a real platform grant through the controller.
                controller.pause()
                playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
                controller.play()
            }
            waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.playing &&
                playback.state.value.positionMs > 0L }
            onMain { assertTrue(PlaybackService.isForegroundForTest()); controller.pause() }
            waitUntil(5_000L) { !playback.state.value.playWhenReady && !playback.state.value.playing }
            onMain {
                playback.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
                PlaybackService.releaseDeniedPlaybackFocus()
                assertTrue("A paused owner retains its foreground notification", PlaybackService.isForegroundForTest())
                controller.play()
            }
            waitUntil(8_000L) { playback.state.value.playing && PlaybackService.isForegroundForTest() }
            onMain { controller.pause() }
        } finally { onMain { MediaController.releaseFuture(future) } }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val playback
        get() = application.services.audioPlayback

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @Before
    fun clearPlaybackBeforeTest() {
        application.admitCurrentPlaybackLibrarySnapshot()
        isolateAndCleanOwnedState()
    }

    @After
    fun clearPlaybackAfterTest() = isolateAndCleanOwnedState()

    @Test
    fun pausedCoalescedMetadataAndLoopChangesReachTheSessionAtTheSamePosition() {
        val source = File(application.cacheDir, "media-session-coalesced.wav")
        writeWave(source, 12_000L)
        val original = song(0, source, 12_000L)
        onMain { playback.startQueue(listOf(original), shuffle = false, autoplay = false) }
        waitUntil(8_000L) { PlaybackService.isActiveForTest() && playback.state.value.songId == original.id &&
            !playback.state.value.loading }
        val future = MediaController.Builder(application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val arm = AtomicBoolean(true)
        try {
            val controller = future.get(10L, TimeUnit.SECONDS)
            waitUntil(5_000L) { onMainValue { controller.mediaMetadata.title?.toString() == original.title } }
            val position = playback.state.value.positionMs
            val revision = checkNotNull(playback.adapter.snapshot().publication.committed).metadata.revision
            val lease = checkNotNull(playback.adapter.acquireCommandLease())
            onMain {
                playback.debug.beforeState = {
                    if (arm.compareAndSet(true, false)) {
                        held.complete(Unit)
                        withTimeout(8_000L) { release.await() }
                    }
                }
                assertTrue(playback.adapter.setLoopIfCurrent(lease, EngineLoopConfig(0L, 4_000L)))
            }
            runBlocking { withTimeout(5_000L) { held.await() } }
            val refreshed = original.copy(title = "Coalesced paused title", mediaRevision = "coalesced-paused-r2")
            onMain {
                playback.setPlaybackQueue(listOf(refreshed), snapshotReady = true)
                assertTrue(playback.adapter.setLoopIfCurrent(lease, EngineLoopConfig(0L, 5_000L)))
            }
            waitUntil(5_000L) {
                val snapshot = playback.adapter.snapshot()
                (snapshot.publication.committed?.metadata?.revision ?: 0L) > revision &&
                    snapshot.committedControls?.loop?.endMs == 5_000L
            }
            assertEquals(position, playback.state.value.positionMs)
            assertEquals(original.title, onMainValue { controller.mediaMetadata.title?.toString() })
            release.complete(Unit)
            waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                playback.state.value.loopB == 5_000L &&
                    onMainValue { controller.mediaMetadata.title?.toString() == refreshed.title }
            }
            assertEquals(position, playback.state.value.positionMs)
            assertFalse(playback.state.value.playing)
        } finally {
            release.complete(Unit)
            onMain { playback.debug.beforeState = null; MediaController.releaseFuture(future) }
        }
    }

    @Test
    fun externalControllerUsesTheSameTransportMetadataQueueRepeatAndShuffle() {
        @Suppress("DEPRECATION")
        val serviceInfo = application.packageManager.getServiceInfo(
            ComponentName(application, PlaybackService::class.java),
            0
        )
        assertTrue("PlaybackService must be discoverable by external media clients", serviceInfo.exported)

        val source = File(application.cacheDir, "media-session-controller.wav")
        val artwork = File(application.cacheDir, "media-session-controller.png")
        writeWave(source, 30_000L)
        writeArtwork(artwork)
        val songs = (0 until 3).map { index -> song(index, source, 30_000L, artwork) }
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(songs, shuffle = false, autoplay = false)
        }
        waitUntil(8_000L) {
            PlaybackService.isActiveForTest() &&
                !playback.state.value.loading &&
                playback.state.value.songId == "external-song-0"
        }

        val token = SessionToken(
            application,
            ComponentName(application, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(application, token).buildAsync()
        try {
            val controller = controllerFuture.get(10L, TimeUnit.SECONDS)
            SystemClock.sleep(750L)
            val initialExternalState = onMainValue {
                listOf(
                    controller.isConnected.toString(),
                    controller.currentMediaItem?.mediaId,
                    controller.mediaMetadata.title?.toString(),
                    controller.currentTimeline.windowCount.toString(),
                    controller.mediaItemCount.toString()
                )
            }
            assertEquals("Controller snapshot: $initialExternalState", "true", initialExternalState[0])
            assertEquals(
                "Controller snapshot: $initialExternalState",
                "external-song-0",
                initialExternalState[1]
            )
            assertEquals(
                "Controller snapshot: $initialExternalState",
                "External Song 0",
                initialExternalState[2]
            )
            listOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_METADATA
            ).forEach { command ->
                assertTrue(
                    "External command $command was not advertised",
                    onMainValue { controller.isCommandAvailable(command) }
                )
            }

            onMain { controller.play() }
            waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                playback.state.value.playWhenReady && playback.state.value.positionMs > 50L
            }
            waitUntil(8_000L) {
                onMainValue {
                    val encodedArtwork = controller.mediaMetadata.artworkData
                    controller.mediaMetadata.artworkUri == null &&
                        encodedArtwork != null &&
                        encodedArtwork.size in 1..MAXIMUM_SESSION_ARTWORK_BYTES
                } && playbackNotification()?.let { notification ->
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                        "External Song 0" &&
                        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                        "StemWise" &&
                        notification.getLargeIcon() != null &&
                        notification.contentIntent != null &&
                        notification.actions?.size == 3
                } == true
            }
            val positionOnlyCacheCounts = requireNotNull(
                PlaybackService.metadataCacheBuildCountsForTest()
            )
            SystemClock.sleep(750L)
            assertEquals(
                "Position-only playback rebuilt immutable session artwork/metadata",
                positionOnlyCacheCounts,
                PlaybackService.metadataCacheBuildCountsForTest()
            )
            val initialArtworkData = requireNotNull(
                onMainValue { controller.mediaMetadata.artworkData?.copyOf() }
            )
            val initialNotificationColor = requireNotNull(
                playbackNotification()?.largeIconCenterColor()
            )

            // Replacing bytes at the same private path must invalidate session metadata through
            // mediaRevision. SystemUI cannot open app-private file:// URIs, so both controller and
            // notification must receive a bounded app-owned payload instead.
            writeArtwork(artwork, android.graphics.Color.rgb(118, 80, 255))
            val artworkOnlyRefresh = songs.first().copy(
                mediaRevision = "media-session-same-uri-replacement"
            )
            onMain { playback.setPlaybackQueue(listOf(artworkOnlyRefresh) + songs.drop(1), snapshotReady = true) }
            waitUntil(8_000L) {
                onMainValue {
                    val metadata = controller.mediaMetadata
                    val replacement = metadata.artworkData
                    metadata.extras?.getString(SESSION_MEDIA_REVISION_KEY) ==
                        artworkOnlyRefresh.mediaRevision &&
                        metadata.artworkUri == null &&
                        replacement != null &&
                        !replacement.contentEquals(initialArtworkData)
                } && playbackNotification()?.let { notification ->
                    notification.getLargeIcon() != null &&
                        notification.largeIconCenterColor()?.let { replacementColor ->
                            colorDistance(initialNotificationColor, replacementColor) > 100
                        } == true
                } == true
            }

            // Metadata can change without changing the audible owner or AudioPlaybackState. The
            // stable Player event must refresh controllers and Media3's notification without a
            // manual NotificationManager enqueue.
            val refreshedSong = artworkOnlyRefresh.copy(
                title = "External Song 0 Refreshed",
                artist = "StemWise Refreshed",
                mediaRevision = "media-session-presentation-refreshed"
            )
            onMain { playback.setPlaybackQueue(listOf(refreshedSong) + songs.drop(1), snapshotReady = true) }
            waitUntil(8_000L) {
                onMainValue {
                        controller.currentMediaItem?.mediaId == refreshedSong.id &&
                        controller.mediaMetadata.title?.toString() == refreshedSong.title &&
                        controller.mediaMetadata.artist?.toString() == refreshedSong.artist &&
                        controller.mediaMetadata.artworkUri == null &&
                        controller.mediaMetadata.artworkData != null
                } && playbackNotification()?.let { notification ->
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                        refreshedSong.title &&
                        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                        refreshedSong.artist &&
                        notification.getLargeIcon() != null
                } == true
            }
            onMain { controller.pause() }
            waitUntil(5_000L) { !playback.state.value.playWhenReady }
            val positionBeforeForward = playback.state.value.positionMs
            onMain { controller.seekForward() }
            waitUntil(5_000L) {
                playback.state.value.positionMs in
                    (positionBeforeForward + STANDARD_SEEK_INCREMENT_MS - 100L)..
                        (positionBeforeForward + STANDARD_SEEK_INCREMENT_MS + 100L)
            }
            onMain { controller.seekBack() }
            waitUntil(5_000L) {
                playback.state.value.positionMs in
                    (positionBeforeForward - 100L).coerceAtLeast(0L)..
                        (positionBeforeForward + 100L)
            }
            onMain { controller.seekTo(12_345L) }
            waitUntil(5_000L) {
                playback.state.value.songId == "external-song-0" &&
                    playback.state.value.positionMs in 12_000L..13_000L
            }

            onMain { controller.seekToNextMediaItem() }
            waitUntil(8_000L) {
                playback.state.value.songId == "external-song-1" &&
                    onMainValue {
                        controller.mediaMetadata.title?.toString() == "External Song 1"
                    }
            }
            onMain {
                controller.seekToPreviousMediaItem()
                controller.seekToPreviousMediaItem()
            }
            waitUntil(8_000L) { playback.state.value.songId == "external-song-0" }

            onMain { controller.repeatMode = Player.REPEAT_MODE_ONE }
            waitUntil(5_000L) {
                playback.state.value.repeatMode == "One" &&
                    onMainValue { controller.repeatMode == Player.REPEAT_MODE_ONE }
            }
            val orderBeforeSessionShuffle = playback.state.value.queueOrder
            val sessionShuffleCurrent = requireNotNull(playback.state.value.songId)
            onMain { controller.shuffleModeEnabled = true }
            waitUntil(5_000L) {
                playback.state.value.shuffleEnabled &&
                    onMainValue { controller.shuffleModeEnabled }
            }
            val orderAfterSessionShuffle = playback.state.value.queueOrder
            assertEquals(
                "MediaSession shuffle must anchor the exact current owner at the front",
                sessionShuffleCurrent,
                orderAfterSessionShuffle.firstOrNull()
            )
            assertEquals(
                "MediaSession shuffle lost or duplicated shared queue members",
                orderBeforeSessionShuffle.toSet(),
                orderAfterSessionShuffle.toSet()
            )
            assertEquals(orderBeforeSessionShuffle.size, orderAfterSessionShuffle.size)
            assertEquals(orderAfterSessionShuffle.size, orderAfterSessionShuffle.distinct().size)
            assertEquals(sessionShuffleCurrent, playback.state.value.songId)
            onMain { controller.pause() }
            waitUntil(5_000L) { !playback.state.value.playWhenReady }

            val unloadedSongId = playback.state.value.songId
            assertTrue(unloadedSongId != null)
            onMain {
                playback.unload(unloadedSongId!!)
                assertEquals(
                    "Unload must hide a still-pending native stop owner from MediaSession",
                    null,
                    playback.sessionMediaItem()
                )
                assertEquals(null, playback.sessionTransportOwnerKey())
            }
            waitUntil(
                5_000L,
                {
                    playback.mediaSessionLifecycleDiagnosticsForTest() +
                        "\nfacade=" + onMainValue {
                            PlaybackService.sessionFacadeDiagnosticsForTest()
                        } +
                        "\ncontroller=" + onMainValue {
                            "connected=${controller.isConnected}," +
                                "timelineEmpty=${controller.currentTimeline.isEmpty}," +
                                "windows=${controller.currentTimeline.windowCount}," +
                                "periods=${controller.currentTimeline.periodCount}," +
                                "count=${controller.mediaItemCount}," +
                                "item=${controller.currentMediaItem?.mediaId}," +
                                "itemIndex=${controller.currentMediaItemIndex}," +
                                "periodIndex=${controller.currentPeriodIndex}," +
                                "position=${controller.currentPosition}," +
                                "duration=${controller.duration}," +
                                "state=${controller.playbackState}"
                        }
                }
            ) {
                onMainValue {
                    controller.isConnected &&
                        controller.currentTimeline.isEmpty &&
                        controller.mediaItemCount == 0 &&
                        controller.currentMediaItem == null &&
                        controller.currentMediaItemIndex == 0 &&
                        controller.currentPeriodIndex == 0 &&
                        controller.currentPosition == 0L &&
                        controller.duration == C.TIME_UNSET
                }
            }
            // Let MediaSession's queued PlayerInfoChangedHandler consume the empty
            // snapshot. A negative empty-state index used to crash the app here.
            SystemClock.sleep(250L)
            assertTrue(onMainValue { controller.isConnected })

            onMain {
                playback.startQueue(
                    songs = listOf(songs.last()),
                    shuffle = false,
                    autoplay = false
                )
            }
            waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                playback.state.value.songId == "external-song-2" &&
                    onMainValue {
                        controller.isConnected &&
                            controller.currentMediaItem?.mediaId == "external-song-2" &&
                            controller.mediaItemCount == 1
                    }
            }
        } finally {
            try {
                onMain { MediaController.releaseFuture(controllerFuture) }
            } finally {
                onMain {
                    playback.setShuffle(false)
                    playback.setRepeatMode("Off")
                    playback.pause()
                }
            }
        }
    }

    @Test
    fun fourHundredRapidSelectionsSettleOneCoherentExternalOwner() {
        val source = File(application.cacheDir, "media-session-selection-pressure.wav")
        val artwork = File(application.cacheDir, "media-session-selection-pressure.png")
        writeWave(source, 30_000L)
        writeArtwork(artwork)
        val songs = (0 until 400).map { index -> song(index, source, 30_000L, artwork) }
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitUntil(8_000L) {
            PlaybackService.isActiveForTest() &&
                playback.state.value.songId == songs.first().id &&
                playback.state.value.playWhenReady
        }

        val token = SessionToken(
            application,
            ComponentName(application, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(application, token).buildAsync()
        try {
            val controller = controllerFuture.get(10L, TimeUnit.SECONDS)
            val stablePlayer = onMainValue { playback.media3Player() }
            val finalSong = songs.last()
            // This is the same paced request shape as the release pressure gate. The verifier's
            // package-specific logcat assertion rejects any NotificationService enqueue shedding.
            songs.drop(1).forEach { target ->
                onMain { playback.selectSong(target, autoplay = true) }
                SystemClock.sleep(8L)
            }
            waitUntil(
                12_000L,
                {
                    playback.mediaSessionLifecycleDiagnosticsForTest() +
                        "\nfacade=" + onMainValue {
                            PlaybackService.sessionFacadeDiagnosticsForTest()
                        } +
                        "\ncontroller=" + onMainValue {
                            "item=${controller.currentMediaItem?.mediaId}," +
                                "title=${controller.mediaMetadata.title}," +
                                "state=${controller.playbackState}"
                        } +
                        "\nnotification=" + playbackNotification()?.let { notification ->
                            "title=${notification.extras.getCharSequence(Notification.EXTRA_TITLE)}," +
                                "hasLargeIcon=${notification.getLargeIcon() != null}," +
                                "actions=${notification.actions?.size}"
                        }
                }
            ) {
                playback.state.value.songId == finalSong.id &&
                    playback.graphMediaId() == finalSong.id &&
                    onMainValue {
                        controller.currentMediaItem?.mediaId == finalSong.id &&
                            controller.mediaMetadata.title?.toString() == finalSong.title
                    } && playbackNotification()?.let { notification ->
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ==
                        finalSong.title &&
                        notification.getLargeIcon() != null
                } == true
            }
            assertSame(stablePlayer, onMainValue { playback.media3Player() })
            assertEquals(400, playback.state.value.queueOrder.size)
        } finally {
            try {
                onMain { MediaController.releaseFuture(controllerFuture) }
            } finally {
                onMain { playback.pause() }
            }
        }
    }

    @Test
    fun externalCommandsRemainAvailableAcrossActorHeldPendingOwners() {
        val source = File(application.cacheDir, "media-session-pending-owner.wav")
        writeWave(source, 30_000L)
        val songs = (0 until 3).map { index -> song(index, source, 30_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitUntil(8_000L) {
            playback.state.value.songId == songs.first().id &&
                playback.state.value.playWhenReady
        }

        val token = SessionToken(
            application,
            ComponentName(application, PlaybackService::class.java)
        )
        val controllerFuture = MediaController.Builder(application, token).buildAsync()
        try {
            val controller = controllerFuture.get(10L, TimeUnit.SECONDS)
            onMain { assertTrue(playback.debug.holdNativeActorForSelectionBurst()) }
            onMain { controller.seekToNextMediaItem() }
            waitUntil(
                5_000L,
                {
                    playback.mediaSessionLifecycleDiagnosticsForTest() +
                        "\nfacade=" + onMainValue {
                            PlaybackService.sessionFacadeDiagnosticsForTest()
                        } +
                        "\ncontroller=" + onMainValue {
                            "item=${controller.currentMediaItem?.mediaId}," +
                                "count=${controller.mediaItemCount}," +
                                "state=${controller.playbackState}," +
                                "commands=${controller.availableCommands}"
                        }
                }
            ) {
                val pressure = playback.selectionPressureDiagnosticsForTest()
                pressure.latestRequestedSongId == songs[1].id &&
                    pressure.committedSongId == null &&
                    playback.state.value.loading &&
                    playback.state.value.playWhenReady &&
                    onMainValue {
                        controller.currentMediaItem?.mediaId == songs[1].id &&
                            controller.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) &&
                            controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) &&
                            controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) &&
                            controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    }
            }
            val pendingBToken = requireNotNull(
                playback.selectionPressureDiagnosticsForTest().latestRequestedToken
            )

            onMain {
                // Pause must mutate pending B itself. It must not select the visually outgoing A.
                controller.pause()
                controller.seekForward()
                controller.seekForward()
            }
            waitUntil(5_000L) {
                val pressure = playback.selectionPressureDiagnosticsForTest()
                val controls = playback.latestRequestedControlsForTest()
                pressure.latestRequestedToken == pendingBToken &&
                    pressure.latestRequestedSongId == songs[1].id &&
                    controls?.let {
                        !it.playWhenReady && it.requestedPositionMs == 10_000L
                    } == true
            }

            onMain { controller.seekToNextMediaItem() }
            waitUntil(
                5_000L,
                {
                    playback.mediaSessionLifecycleDiagnosticsForTest() +
                        "\nfacade=" + onMainValue {
                            PlaybackService.sessionFacadeDiagnosticsForTest()
                        } +
                        "\ncontroller=" + onMainValue {
                            "item=${controller.currentMediaItem?.mediaId}," +
                                "count=${controller.mediaItemCount}," +
                                "state=${controller.playbackState}," +
                                "commands=${controller.availableCommands}"
                        }
                }
            ) {
                val pressure = playback.selectionPressureDiagnosticsForTest()
                pressure.latestRequestedSongId == songs[2].id &&
                    pressure.latestRequestedToken != pendingBToken &&
                    pressure.committedSongId == null &&
                    onMainValue {
                        controller.currentMediaItem?.mediaId == songs[2].id &&
                            controller.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
                    }
            }
            val pendingCToken = requireNotNull(
                playback.selectionPressureDiagnosticsForTest().latestRequestedToken
            )
            onMain { controller.seekForward() }
            waitUntil(5_000L) {
                val pressure = playback.selectionPressureDiagnosticsForTest()
                val controls = playback.latestRequestedControlsForTest()
                pressure.latestRequestedToken == pendingCToken &&
                    pressure.latestRequestedSongId == songs[2].id &&
                    controls?.let {
                        !it.playWhenReady && it.requestedPositionMs == 5_000L
                    } == true
            }
        } finally {
            try {
                onMain { playback.debug.releaseNativeActorForSelectionBurst() }
            } finally {
                onMain { MediaController.releaseFuture(controllerFuture) }
            }
        }

        waitUntil(12_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            playback.state.value.songId == songs[2].id &&
                !playback.state.value.playWhenReady &&
                playback.state.value.positionMs in 4_900L..5_100L
        }
    }

    @Test
    fun platformMediaKeysUseTheSameTransportAndQueueAsBluetoothControls() {
        val source = File(application.cacheDir, "platform-media-keys.wav")
        writeWave(source, 30_000L)
        val songs = (0 until 3).map { index -> song(index, source, 30_000L) }
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            playback.state.value.songId == "external-song-0" &&
                playback.state.value.positionMs > 100L
        }

        sendMediaKey("KEYCODE_MEDIA_PAUSE")
        waitUntil(5_000L) { !playback.state.value.playWhenReady }
        sendMediaKey("KEYCODE_MEDIA_PLAY")
        waitUntil(5_000L) { playback.state.value.playWhenReady }

        sendMediaKey("KEYCODE_MEDIA_PLAY_PAUSE")
        waitUntil(5_000L) { !playback.state.value.playWhenReady }
        sendMediaKey("KEYCODE_MEDIA_PLAY_PAUSE")
        waitUntil(5_000L) { playback.state.value.playWhenReady }
        sendMediaKey("KEYCODE_HEADSETHOOK")
        waitUntil(5_000L) { !playback.state.value.playWhenReady }
        sendMediaKey("KEYCODE_HEADSETHOOK")
        waitUntil(5_000L) { playback.state.value.playWhenReady }

        onMain { playback.pause() }
        waitUntil(5_000L) { !playback.state.value.playWhenReady }
        onMain { playback.seekTo(12_000L) }
        waitUntil(5_000L) { playback.state.value.positionMs in 11_900L..12_100L }
        sendMediaKey("KEYCODE_MEDIA_FAST_FORWARD")
        waitUntil(5_000L) { playback.state.value.positionMs in 16_900L..17_100L }
        sendMediaKey("KEYCODE_MEDIA_REWIND")
        waitUntil(5_000L) { playback.state.value.positionMs in 11_900L..12_100L }

        sendMediaKey("KEYCODE_MEDIA_PLAY")
        waitUntil(5_000L) { playback.state.value.playWhenReady }
        sendMediaKey("KEYCODE_MEDIA_NEXT")
        waitUntil(
            timeoutMs = 8_000L,
            timeoutDiagnostic = playback::mediaSessionLifecycleDiagnosticsForTest
        ) { playback.state.value.songId == "external-song-1" }
        sendMediaKey("KEYCODE_MEDIA_PREVIOUS")
        sendMediaKey("KEYCODE_MEDIA_PREVIOUS")
        waitUntil(
            timeoutMs = 8_000L,
            timeoutDiagnostic = playback::mediaSessionLifecycleDiagnosticsForTest
        ) { playback.state.value.songId == "external-song-0" }
        sendMediaKey("KEYCODE_MEDIA_STOP")
        waitUntil(5_000L) {
            !playback.state.value.playWhenReady &&
                playback.state.value.positionMs < 100L
        }
    }

    @Test
    fun notificationButtonsDrivePreviousPlayPauseAndNextOnThePrimaryOwner() {
        val source = File(application.cacheDir, "notification-buttons.wav")
        val artwork = File(application.cacheDir, "notification-buttons.png")
        writeWave(source, 30_000L)
        writeArtwork(artwork)
        val songs = (0 until 3).map { index -> song(index, source, 30_000L, artwork) }
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.startQueue(songs, shuffle = false, autoplay = true)
        }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            playback.state.value.songId == songs.first().id &&
                playback.state.value.playing &&
                playbackNotification()?.actions?.let { actions ->
                    actions.size == 3 &&
                        actions[1].getIcon().resId == R.drawable.media3_icon_pause
                } == true
        }

        val playingActions = requireNotNull(playbackNotification()?.actions)
        assertTrue(playingActions.all { it.actionIntent != null })
        assertEquals(R.drawable.media3_icon_previous, playingActions[0].getIcon().resId)
        assertEquals(R.drawable.media3_icon_pause, playingActions[1].getIcon().resId)
        assertEquals(R.drawable.media3_icon_next, playingActions[2].getIcon().resId)
        playingActions[1].actionIntent.send()
        waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            !playback.state.value.playWhenReady &&
                playbackNotification()?.actions?.getOrNull(1)?.getIcon()?.resId ==
                R.drawable.media3_icon_play
        }

        val pausedActions = requireNotNull(playbackNotification()?.actions)
        assertEquals(3, pausedActions.size)
        assertEquals(R.drawable.media3_icon_previous, pausedActions[0].getIcon().resId)
        assertEquals(R.drawable.media3_icon_play, pausedActions[1].getIcon().resId)
        assertEquals(R.drawable.media3_icon_next, pausedActions[2].getIcon().resId)
        pausedActions[1].actionIntent.send()
        waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            playback.state.value.playWhenReady &&
                playbackNotification()?.actions?.getOrNull(1)?.getIcon()?.resId ==
                R.drawable.media3_icon_pause
        }

        requireNotNull(playbackNotification()?.actions)[2].actionIntent.send()
        waitUntil(8_000L) { playback.state.value.songId == songs[1].id }
        val previous = requireNotNull(playbackNotification()?.actions)[0].actionIntent
        previous.send()
        previous.send()
        waitUntil(8_000L) { playback.state.value.songId == songs.first().id }
    }

    @Test
    fun stopAcrossPhysicalPauseBoundaryNeverStrandsTheNextOwner() {
        val source = File(application.cacheDir, "notification-buttons.wav")
        writeWave(source, 30_000L)
        val activeSong = song(0, source, 30_000L)
        // Straddle the 1.5 s warm-pause grace, including callbacks already physically paused.
        for (pauseAgeMs in listOf(0L, 1_480L, 1_505L, 1_550L, 1_800L)) {
            onMain { playback.startQueue(listOf(activeSong), shuffle = false, autoplay = true) }
            waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                playback.state.value.playing && playback.state.value.positionMs > 50L
            }
            onMain { playback.pause() }
            waitUntil(5_000L) { !playback.state.value.playWhenReady }
            SystemClock.sleep(pauseAgeMs)
            playback.resetForInstrumentationIsolation(instrumentation)
        }
        onMain { playback.startQueue(listOf(activeSong), shuffle = false, autoplay = true) }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            playback.state.value.playing && playback.state.value.positionMs > 100L
        }
    }

    @Test
    fun becomingNoisyIsADurablePauseAndNeverAnAutomaticResume() {
        onMain { playback.setDiagnosticPayloadFingerprintsForTest(true) }
        val source = File(application.cacheDir, "becoming-noisy.wav")
        writeWave(source, 30_000L)
        repeat(6) { attempt ->
            val activeSong = song(88 + attempt, source, 30_000L)
            onMain { playback.startQueue(listOf(activeSong), shuffle = false, autoplay = true) }
            waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                PlaybackService.isActiveForTest() && playback.state.value.playing &&
                    playback.state.value.songId == activeSong.id && playback.state.value.positionMs > 100L
            }
            val owner = checkNotNull(playback.adapter.snapshot().publication.committed).owner.ownership
            onMain {
                assertTrue("The active service did not accept the noisy-route callback",
                    PlaybackService.pauseForNoisyRouteForTest())
            }
            var pausedNative = playback.nativeDiagnosticsSnapshotForTest()
            waitUntil(5_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val acknowledged = !playback.state.value.playWhenReady && !playback.state.value.playing &&
                    playback.state.value.suppression == AudioPlaybackSuppression.NONE &&
                    native?.runtime?.presentedPosition?.ownership == owner &&
                    native.output?.playing == false && native.output.effectiveRenderPlaying == false
                if (acknowledged) pausedNative = native
                acknowledged
            }
            val output = checkNotNull(checkNotNull(pausedNative).output)
            val head = playback.presentationJournalForTest(Long.MAX_VALUE).writeSequence
            val before = playback.presentationJournalForTest((head - 1L).coerceAtLeast(0L))
            assertTrue(before.coherent && before.overflowCount == 0L && !before.sequenceExhausted)
            val beforeSpan = checkNotNull(before.entries.lastOrNull())
            assertEquals(owner.generation, beforeSpan.generation)
            assertEquals(owner.selectionToken, beforeSpan.selectionToken)
            SystemClock.sleep(500L)
            val after = playback.presentationJournalForTest(before.writeSequence)
            assertTrue(after.coherent && after.overflowCount == 0L && !after.sequenceExhausted)
            assertEquals(before.writerCollisionCount, after.writerCollisionCount)
            assertEquals(before.invalidPublicationCount, after.invalidPublicationCount)
            assertTrue(after.entries.all { it.generation == owner.generation && it.selectionToken == owner.selectionToken })
            val tailFrames = after.entries.sumOf { it.pcmFrames }
            assertTrue("Noisy-route pause attempt=$attempt consumed $tailFrames source frames after acknowledgement; " +
                "device buffer=${output.outputBufferSizeFrames} frames",
                tailFrames <= output.outputBufferSizeFrames && tailFrames * 1000L <= 50L * output.sampleRateHz)
            val afterOutput = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
            assertFalse(afterOutput.playing)
            assertFalse(afterOutput.effectiveRenderPlaying)
            assertFalse(playback.state.value.playWhenReady)
            val sourcePositionMs = (after.entries.lastOrNull() ?: beforeSpan).lastTimelineFrame * 1000L / output.sampleRateHz
            // UI intent is immediate; its independently scheduled cursor must catch up before measuring UI drift.
            waitUntil(2_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                !playback.state.value.playWhenReady && !playback.state.value.playing &&
                    kotlin.math.abs(playback.state.value.positionMs - sourcePositionMs) <= 50L
            }
            val settledPosition = playback.state.value.positionMs
            SystemClock.sleep(500L)
            assertFalse(playback.state.value.playWhenReady)
            assertTrue("Settled noisy-route cursor moved: $settledPosition -> ${playback.state.value.positionMs}",
                kotlin.math.abs(playback.state.value.positionMs - settledPosition) <= 50L)
        }
    }

    @Test
    fun unexpectedServiceDestructionSilencesPlaybackAndUiPlayRecreatesTheSession() {
        val source = File(application.cacheDir, "service-destruction.wav")
        writeWave(source, 30_000L)
        val selectedSong = song(89, source, 30_000L)
        onMain {
            playback.startQueue(
                listOf(selectedSong),
                shuffle = false,
                autoplay = true
            )
        }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isActiveForTest() &&
                playback.state.value.playing &&
                playback.state.value.positionMs > 100L &&
                playbackNotification() != null
        }

        assertTrue(onMainValue {
            application.stopService(Intent(application, PlaybackService::class.java))
        })

        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            !PlaybackService.isActiveForTest() &&
                !playback.state.value.playWhenReady &&
                !playback.state.value.playing &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.playing != true &&
                !onMainValue(playback::playbackAudioFocusOwnedForTest) &&
                playbackNotification() == null
        }
        assertTrue(onMainValue { playback.toggleCurrentOrRebind(selectedSong) })
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isActiveForTest() && playback.state.value.playing &&
                PlaybackService.isPlaybackWakeLockHeldForTest() &&
                onMainValue(playback::playbackAudioFocusOwnedForTest) && playbackNotification() != null
        }
        val recreatedPlayer = onMainValue(playback::media3Player)
        assertTrue(onMainValue(playback::play))
        assertTrue(onMainValue { playback.media3Player() === recreatedPlayer })
    }

    @Test
    fun pauseNearEndKeepsPostedNotificationAfterPhysicalPauseGrace() {
        val source = File(application.cacheDir, "paused-near-end-retention.wav")
        writeWave(source, 12_000L)
        val selected = song(91, source, 12_000L)
        onMain { playback.startQueue(listOf(selected), shuffle = false, autoplay = false) }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isActiveForTest() && playback.state.value.songId == selected.id &&
                !playback.state.value.loading
        }
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        try {
            val controller = future.get(10L, TimeUnit.SECONDS)
            onMain { controller.seekTo(11_000L); controller.play() }
            waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                playback.state.value.playing && playback.state.value.positionMs >= 11_050L
            }
            onMain { controller.pause() }
            waitUntil(5_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                !playback.state.value.playing && !playback.state.value.playWhenReady &&
                    playbackNotification()?.actions?.getOrNull(1)?.getIcon()?.resId == R.drawable.media3_icon_play &&
                    onMainValue { !controller.playWhenReady && controller.playbackState == Player.STATE_READY }
            }
            val pausedOwner = onMainValue { playback.sessionTransportOwnerKey() }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            val pausedAt = SystemClock.elapsedRealtime()
            do {
                val age = SystemClock.elapsedRealtime() - pausedAt
                val controllerRetained = onMainValue {
                    controller.currentMediaItem?.mediaId == selected.id &&
                        controller.playbackState == Player.STATE_READY && !controller.playWhenReady && !controller.isPlaying
                }
                val posted = playbackNotification()
                val state = playback.state.value
                if (!controllerRetained || posted?.actions?.getOrNull(1)?.getIcon()?.resId != R.drawable.media3_icon_play ||
                    !PlaybackService.isForegroundForTest() || state.songId != selected.id || state.playing ||
                    state.playWhenReady || state.ended || onMainValue { playback.sessionTransportOwnerKey() } != pausedOwner
                ) {
                    throw AssertionError("Near-end paused notification lost after ${age}ms; " +
                        "controllerRetained=$controllerRetained, notification=${posted != null}, state=$state; " +
                        playback.mediaSessionLifecycleDiagnosticsForTest())
                }
                SystemClock.sleep(500L)
            } while (SystemClock.elapsedRealtime() - pausedAt <= 5_000L)
        } finally { onMain { MediaController.releaseFuture(future) } }
    }

    @Test
    fun boundControllerDisconnectsOnRemovalAndHealthyReopenOwnsANewSession() {
        val source = File(application.cacheDir, "bound-task-removal.wav")
        writeWave(source, 30_000L)
        val first = song(91, source, 30_000L)
        val replacement = song(92, source, 30_000L)
        onMain { assertEquals(first.id, playback.startQueue(listOf(first), shuffle = false, autoplay = false)?.id) }
        waitUntil(8_000L) { PlaybackService.isActiveForTest() && !playback.state.value.loading }
        val future = MediaController.Builder(application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
        try {
            val controller = future.get(10L, TimeUnit.SECONDS)
            val retiredPlayer = onMainValue { PlaybackService.stablePlayerForTest() }
            onMain {
                assertTrue(controller.isConnected)
                PlaybackService.onAppTaskRemoved(application, restartAlreadyRequired = false)
                assertTrue("Session retirement precedes Android service destruction",
                    PlaybackService.stablePlayerForTest() == null)
                PlaybackService.onAppTaskAvailable()
                assertEquals(replacement.id,
                    playback.startQueue(listOf(replacement), shuffle = false, autoplay = false)?.id)
            }
            waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
                onMainValue { !controller.isConnected } && PlaybackService.isActiveForTest() &&
                    onMainValue { PlaybackService.stablePlayerForTest()?.let { it !== retiredPlayer } == true } &&
                    playback.state.value.songId == replacement.id && !playback.state.value.loading
            }
            assertFalse(playback.processRestartRequiredAfterTaskRemoval())
            val replacementFuture = MediaController.Builder(application,
                SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
            try {
                val current = replacementFuture.get(10L, TimeUnit.SECONDS)
                onMain {
                    assertTrue(current.isConnected)
                    assertEquals(replacement.id, current.currentMediaItem?.mediaId)
                    current.play()
                }
                waitUntil(8_000L) { playback.state.value.songId == replacement.id && playback.state.value.playing }
            } finally { onMain { MediaController.releaseFuture(replacementFuture) } }
        } finally { onMain { MediaController.releaseFuture(future) } }
    }

    @Test
    fun pausedOwnerKeepsNotificationUntilTheTaskIsExplicitlyRemoved() {
        onMain { application.stopService(Intent(application, PlaybackService::class.java)) }
        waitUntil(8_000L) { !PlaybackService.isActiveForTest() }
        val source = File(application.cacheDir, "paused-task-removal.wav")
        writeWave(source, 30_000L)
        onMain { playback.startQueue(listOf(song(90, source, 30_000L)), shuffle = false, autoplay = false) }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isActiveForTest() && !playback.state.value.loading && playbackNotification() != null
        }
        val pausedOwner = onMainValue { playback.sessionTransportOwnerKey() }
        val pausedCursor = playback.state.value.positionMs
        // Cross Android's real background-service reclamation boundary before this song ever
        // plays; Media3's grace period after Play would otherwise hide the missing ownership.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        val idleResult = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cmd activity make-uid-idle ${application.packageName}")
        ).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        assertTrue("Could not force the disposable UID idle: $idleResult", idleResult.isEmpty())
        waitUntil(4_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isForegroundForTest()
        }
        SystemClock.sleep(800L)
        assertEquals(pausedOwner, onMainValue { playback.sessionTransportOwnerKey() })
        assertEquals(pausedCursor, playback.state.value.positionMs)
        assertFalse(playback.state.value.playing)
        assertTrue(playbackNotification() != null)
        assertFalse(PlaybackService.isPlaybackWakeLockHeldForTest())
        assertFalse(onMainValue(playback::playbackAudioFocusOwnedForTest))
        onMain { playback.play() }
        waitUntil(4_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            PlaybackService.isPlaybackWakeLockHeldForTest() && playback.state.value.playing
        }
        onMain { playback.pause() }
        waitUntil(4_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            !PlaybackService.isPlaybackWakeLockHeldForTest() && !playback.state.value.playing
        }
        onMain { PlaybackService.onAppTaskRemoved(application, restartAlreadyRequired = false) }
        waitUntil(8_000L, playback::mediaSessionLifecycleDiagnosticsForTest) {
            !PlaybackService.isActiveForTest() && playback.state.value.songId == null &&
                !PlaybackService.isPlaybackWakeLockHeldForTest() &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.playing != true &&
                playbackNotification() == null
        }
    }

    private fun song(
        index: Int,
        source: File,
        durationMs: Long,
        artwork: File? = null
    ) = Song(
        id = "external-song-$index",
        videoId = "external-video-$index",
        title = "External Song $index",
        artist = "StemWise",
        durationMs = durationMs,
        state = SongState.READY,
        accent = Color.Cyan,
        artworkGlyph = index.toString(),
        artworkSource = artwork?.absolutePath,
        sourceHash = PlaybackContentFixture.sourceHash(source),
            sourcePath = PlaybackContentFixture.publish(application, source),
        sourceCodec = "pcm_s16le",
        sourceContainer = "wav"
    )

    private fun waitUntil(
        timeoutMs: Long,
        timeoutDiagnostic: (() -> String)? = null,
        predicate: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        if (predicate()) return
        val diagnostic = buildString {
            val controls = playback.latestRequestedControlsForTest()
            val native = playback.nativeDiagnosticsSnapshotForTest()
            append("\nrequestedSeek=${controls?.requestedPositionMs}/${controls?.seekRevision}, ")
            append("pendingSeek=${native?.controllerPendingSeekOwnership}, ")
            append("transport=${playback.adapter.snapshot().publication.committed?.transport}, ")
            append("renderCursor=${native?.nativeRenderReadiness?.renderStartTimelineFrame}, ")
            append("decoderHeads=${native?.controllerSelectedDecoders?.map { it.head }}")
            runCatching {
                val engine = app.stemwise.playback.engine.NativeAudioPlaybackSessionAdapter::class.java
                    .getDeclaredField("engine").apply { isAccessible = true }.get(playback.adapter)
                val controller = app.stemwise.playback.engine.ControllerNativeAudioPlaybackSessionEngine::class.java
                    .getDeclaredField("controller").apply { isAccessible = true }.get(engine)
                val banks = app.stemwise.playback.engine.NativePlaybackEngineController::class.java
                    .getDeclaredField("bankIntents").apply { isAccessible = true }.get(controller) as Array<*>
                append("\nbankIntents=${banks.toList()}")
                append(", appliedBanks=${native?.runtime?.banks}")
                append(", loop=${native?.controllerLoopProtocol}")
                append(", readiness=${native?.nativeRenderReadiness}")
            }.exceptionOrNull()?.let { append("\nbankDiagnosticFailure=$it") }
            timeoutDiagnostic?.invoke()?.let { append("\n$it") }
            append("\n")
            append(PlaybackService.sessionFacadeDiagnosticsForTest())
            append("\nnotificationActions=")
            append(playbackNotification()?.actions?.map { it.getIcon().resId })
        }
        throw AssertionError("Timed out: ${playback.state.value}$diagnostic")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun isolateAndCleanOwnedState() {
        clearPlaybackOwner()
        PlaybackContentFixture.clear(application)
    }

    private fun clearPlaybackOwner() {
        playback.resetForInstrumentationIsolation(instrumentation)
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
        }
    }

    private fun sendMediaKey(keyName: String) {
        val command = "input keyevent $keyName"
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        assertTrue("Media-key command reported an error: $keyName: $output", output.isEmpty())
    }

    private fun playbackNotification(): Notification? {
        val manager = application.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        return manager.activeNotifications
            .firstOrNull { status ->
                status.packageName == application.packageName &&
                    status.notification.category == Notification.CATEGORY_TRANSPORT
            }
            ?.notification
    }

    private fun Notification.largeIconCenterColor(): Int? {
        val drawable = getLargeIcon()?.loadDrawable(application) ?: return null
        val width = maxOf(1, drawable.intrinsicWidth)
        val height = maxOf(1, drawable.intrinsicHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.getPixel(width / 2, height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
            kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
            kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))

    private fun <T> onMainValue(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun writeWave(file: File, durationMs: Long) {
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
                    (kotlin.math.sin(2.0 * Math.PI * 220.0 * index / sampleRate) * 2_000.0)
                        .toInt()
                        .toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    private fun writeArtwork(
        file: File,
        color: Int = android.graphics.Color.rgb(22, 214, 170)
    ) {
        PlaybackContentFixture.own(application, file)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()
    }

}
