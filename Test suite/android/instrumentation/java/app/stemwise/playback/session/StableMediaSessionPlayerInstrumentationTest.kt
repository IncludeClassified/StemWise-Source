package app.stemwise.playback.session

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class StableMediaSessionPlayerInstrumentationTest {
    @Test
    fun completedPlayPauseCommandsExposeTheirCanonicalStateImmediately() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = RecordingTransport()
        instrumentation.runOnMainSync {
            val player = StableMediaSessionPlayer(transport)
            try {
                repeat(4) {
                    player.play()
                    assertTrue("Completed play must be visible before the next toggle", player.playWhenReady)
                    player.pause()
                    assertEquals(false, player.playWhenReady)
                }
            } finally {
                player.close()
            }
        }
    }

    @Test
    fun boundedArtworkAndRevisionReachTheStableFacadeMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val encodedArtwork = byteArrayOf(7, 11, 13, 17)
        val transport = RecordingTransport(
            MediaSessionSnapshot(
                media = SessionMediaDescription(
                    id = "artwork",
                    title = "Artwork",
                    artist = "StemWise",
                    revision = "manifest-r2",
                    artworkData = SessionArtworkData(encodedArtwork)
                ),
                durationMs = 20_000L
            )
        )
        var player: StableMediaSessionPlayer? = null
        var buildsAfterInitialMetadata = -1L

        try {
            instrumentation.runOnMainSync {
                val createdPlayer = StableMediaSessionPlayer(transport, instrumentation =
                    app.stemwise.playback.PlaybackInstrumentationRecorder().apply { enable() })
                player = createdPlayer
                assertEquals("manifest-r2", createdPlayer.mediaMetadata.extras
                    ?.getString(SESSION_MEDIA_REVISION_KEY))
                val publishedArtwork = createdPlayer.mediaMetadata.artworkData
                assertNotNull(publishedArtwork)
                assertTrue(requireNotNull(publishedArtwork).contentEquals(encodedArtwork))
                assertEquals(null, createdPlayer.mediaMetadata.artworkUri)
                buildsAfterInitialMetadata = createdPlayer.mediaMetadataBuildCountForTest()
                repeat(10_000) { index ->
                    transport.snapshots.value = transport.snapshots.value.copy(
                        positionMs = index.toLong()
                    )
                }
                assertEquals(buildsAfterInitialMetadata, createdPlayer.mediaMetadataBuildCountForTest())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val createdPlayer = requireNotNull(player)
                assertEquals(buildsAfterInitialMetadata, createdPlayer.mediaMetadataBuildCountForTest())
                assertTrue(createdPlayer.currentPosition >= 0L)
                assertEquals(buildsAfterInitialMetadata, createdPlayer.mediaMetadataBuildCountForTest())
            }
        } finally {
            player?.let { createdPlayer ->
                instrumentation.runOnMainSync { createdPlayer.close() }
            }
        }
    }

    @Test
    fun startupLoadingWithoutCommittedMediaIsAValidIdleSessionState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = RecordingTransport(
            MediaSessionSnapshot(
                media = null,
                loading = true
            )
        )
        var player: StableMediaSessionPlayer? = null

        try {
            instrumentation.runOnMainSync {
                val createdPlayer = StableMediaSessionPlayer(transport, instrumentation =
                    app.stemwise.playback.PlaybackInstrumentationRecorder().apply { enable() })
                player = createdPlayer
                assertEquals(Player.STATE_IDLE, createdPlayer.playbackState)
                assertEquals(false, createdPlayer.isLoading)
            }
        } finally {
            player?.let { createdPlayer ->
                instrumentation.runOnMainSync { createdPlayer.close() }
            }
        }
    }

    @Test
    fun emptyTimelineUsesIndexZeroAndRecoversToTheNextOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = RecordingTransport(MediaSessionSnapshot())
        var player: StableMediaSessionPlayer? = null

        try {
            instrumentation.runOnMainSync {
                val createdPlayer = StableMediaSessionPlayer(transport, instrumentation =
                    app.stemwise.playback.PlaybackInstrumentationRecorder().apply { enable() })
                player = createdPlayer
                assertTrue(createdPlayer.currentTimeline.isEmpty)
                assertEquals(0, createdPlayer.mediaItemCount)
                assertEquals(null, createdPlayer.currentMediaItem)
                assertEquals(0, createdPlayer.currentMediaItemIndex)
                assertEquals(0, createdPlayer.currentPeriodIndex)

                transport.snapshots.value = MediaSessionSnapshot(
                    media = SessionMediaDescription("recovered", "Recovered"),
                    durationMs = 20_000L,
                    positionMs = 1_250L
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val createdPlayer = requireNotNull(player)
                assertEquals("recovered", createdPlayer.currentMediaItem?.mediaId)
                assertEquals(1, createdPlayer.mediaItemCount)
                assertEquals(0, createdPlayer.currentMediaItemIndex)
                assertEquals(1_250L, createdPlayer.currentPosition)
            }
        } finally {
            player?.let { createdPlayer ->
                instrumentation.runOnMainSync { createdPlayer.close() }
            }
        }
    }

    @Test
    fun immediateRelativeCommandsRemainFourIndependentFiveSecondIntents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = RecordingTransport()
        var player: StableMediaSessionPlayer? = null

        try {
            instrumentation.runOnMainSync {
                val createdPlayer = StableMediaSessionPlayer(transport, instrumentation =
                    app.stemwise.playback.PlaybackInstrumentationRecorder().apply { enable() })
                player = createdPlayer
                assertEquals("seed", createdPlayer.currentMediaItem?.mediaId)

                createdPlayer.seekForward()
                createdPlayer.seekForward()
                createdPlayer.seekBack()
                createdPlayer.seekBack()

                assertEquals(
                    listOf(5_000L, 5_000L, -5_000L, -5_000L),
                    transport.relativeSeekDeltas
                )
                transport.snapshots.value = MediaSessionSnapshot(
                    media = SessionMediaDescription("longer", "Longer"),
                    durationMs = 60_000L
                )
                // The pending canonical update cannot drain inside this main-loop command burst.
                assertEquals("seed", createdPlayer.currentMediaItem?.mediaId)
                createdPlayer.seekTo(45_000L)
                assertEquals(listOf(45_000L), transport.absoluteSeekPositions)
            }
        } finally {
            player?.let { createdPlayer ->
                instrumentation.runOnMainSync { createdPlayer.close() }
            }
        }
    }

    @Test
    fun oneFacadeSurvivesTenThousandMainLooperSnapshotsWithOneConflatedEventBatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val transport = RecordingTransport()
        val eventBatches = AtomicInteger(0)
        var player: StableMediaSessionPlayer? = null
        var listener: Player.Listener? = null

        try {
            instrumentation.runOnMainSync {
                val createdPlayer = StableMediaSessionPlayer(transport, instrumentation =
                    app.stemwise.playback.PlaybackInstrumentationRecorder().apply { enable() })
                val createdListener = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        eventBatches.incrementAndGet()
                    }
                }
                player = createdPlayer
                listener = createdListener
                createdPlayer.addListener(createdListener)
                // Materialize the seed state so the later invalidation has a listener-visible
                // before/after diff instead of becoming SimpleBasePlayer's lazy initial state.
                assertEquals("seed", createdPlayer.currentMediaItem?.mediaId)
                assertEquals(5_000L, createdPlayer.seekBackIncrement)
                assertEquals(5_000L, createdPlayer.seekForwardIncrement)

                repeat(10_000) { index ->
                    transport.snapshots.value = MediaSessionSnapshot(
                        media = SessionMediaDescription(
                            id = "song-$index",
                            title = "Song $index",
                            artist = "StemWise"
                        ),
                        durationMs = 20_000L,
                        positionMs = index.toLong(),
                        canGoNext = index < 9_999,
                        canGoPrevious = index > 0
                    )
                    assertSame(createdPlayer, player)
                }
            }

            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                val createdPlayer = requireNotNull(player)
                assertEquals("song-9999", createdPlayer.currentMediaItem?.mediaId)
                assertEquals(9_999L, createdPlayer.currentPosition)
                assertEquals(1, eventBatches.get())

                createdPlayer.onTaskRemoved()
                createdPlayer.onTaskRemoved()
                assertEquals(1, transport.taskRemovalCalls)
            }
        } finally {
            player?.let { createdPlayer ->
                instrumentation.runOnMainSync {
                    listener?.let(createdPlayer::removeListener)
                    createdPlayer.close()
                }
                assertEquals(1, transport.closeCalls)
            }
        }
    }

    private class RecordingTransport(
        initialSnapshot: MediaSessionSnapshot = MediaSessionSnapshot(
                media = SessionMediaDescription("seed", "Seed"),
                durationMs = 20_000L
            )
    ) : MediaSessionTransport {
        override val snapshots = MutableStateFlow(initialSnapshot)
        var taskRemovalCalls = 0
        var closeCalls = 0
        val relativeSeekDeltas = mutableListOf<Long>()
        val absoluteSeekPositions = mutableListOf<Long>()

        override fun play() { snapshots.value = snapshots.value.copy(playWhenReady = true) }
        override fun pause() { snapshots.value = snapshots.value.copy(playing = false, playWhenReady = false) }
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) {
            absoluteSeekPositions += positionMs
        }
        override fun seekBy(deltaMs: Long) {
            relativeSeekDeltas += deltaMs
        }
        override fun next() = Unit
        override fun previous() = Unit
        override fun setRepeatMode(mode: SessionRepeatMode) = Unit
        override fun setShuffleEnabled(enabled: Boolean) = Unit

        override fun onTaskRemoved() {
            taskRemovalCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
