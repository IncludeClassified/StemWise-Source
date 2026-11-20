package app.stemwise.playback

import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.stemwise.StemwiseApplication
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.DirectLoopEnduranceOracle
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class LoopRetargetInstrumentationTest : LoopPlaybackFixture() {
    @Test
    fun nativeHighResolutionFlacOriginalLoopsWithSpeedAndPitchChanges() {
        val directory = File(application.cacheDir, "native-flac-loop").apply { check(mkdirs()) }
        try {
            val audio = File(directory, "original.flac")
            instrumentation.context.assets.open("encoder/flac24-stereo-96000.flac").use { input ->
                audio.outputStream().use(input::copyTo)
            }
            val song = Song(id = "flac-loop", videoId = "flac-loop", title = "FLAC loop", artist = "StemWise",
                durationMs = 1_000L, state = SongState.READY, accent = Color.Cyan, hasStems = false,
                artworkGlyph = "F", sourceHash = PlaybackContentFixture.sourceHash(audio),
            sourcePath = PlaybackContentFixture.publish(application, audio),
                sourceCodec = "flac", sourceContainer = "flac")
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = false) }
            waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.loopAvailable }
            val lease = checkNotNull(playback.acquireControlLease(song.id))
            onMain {
                assertTrue(playback.setLoop(200L, 800L, lease))
                assertTrue(playback.seekTo(200L))
                playback.play()
            }
            for ((tempo, pitch) in listOf(1.5f to 7, .5f to -7)) {
                onMain { assertTrue(playback.setTempoAndPitch(tempo, pitch, lease)) }
                waitUntil(3_000L) {
                    val graph = playback.graphDiagnostics()
                    abs((graph.appliedTempo ?: Float.NaN) - tempo) < .001f &&
                        abs((graph.appliedPitchRatio ?: Float.NaN) - 2.0.pow(pitch / 12.0).toFloat()) < .001f
                }
                awaitDirectWraps(200L, 800L, minimumWraps = 3, timeoutMs = 8_000L)
                assertTrue(playback.state.value.playing)
                assertEquals("ORIGINAL", playback.nativeDiagnosticsSnapshotForTest()
                    ?.runtime?.audibleControl?.actualSourceMode?.name)
            }
        } finally {
            playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
            check(directory.deleteRecursively())
        }
    }

    @Test
    fun repeatSurvivesScrubbingSourceAndTempoChanges() {
        val baseline = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
        fun assertAudioHealth(label: String) {
            val output = checkNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
            if (output.processUnderrunCallbackCount != baseline.processUnderrunCallbackCount ||
                output.processCallbackDeadlineMissCount != baseline.processCallbackDeadlineMissCount ||
                output.processXRunCount != baseline.processXRunCount ||
                output.loop.processBoundaryMissCount != baseline.loop.processBoundaryMissCount
            ) {
                val native = checkNotNull(playback.nativeDiagnosticsSnapshotForTest())
                fail("Repeat audio fault at $label: $output; " +
                    "Original=${native.runtime.needOriginalData}; Stems=${native.runtime.needStemData}; " +
                    "boundary=${native.runtime.loopBoundaryPending}; lowWater=${native.runtime.currentOutputQueueLowWater}; " +
                    "cacheMask=${native.nativeRenderReadiness?.currentSongCacheCompleteLaneMask}; " +
                    "events=${playback.playbackEventHistoryForTest()}")
            }
        }
        val directory = File(application.cacheDir, "repeat-player-polish").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stem = File(directory, "stem.wav")
        val duration = 8_000L
        writeSineWave(original, duration, 220.0)
        writeSineWave(stem, duration, 330.0)
        val song = Song(
            id = "repeat-player-polish", videoId = "repeat-player-polish",
            title = "Repeat regression", artist = "StemWise", durationMs = duration,
            state = SongState.READY, accent = Color.Cyan, hasStems = true, artworkGlyph = "R",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le", sourceContainer = "wav"
        )
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) { playback.state.value.songId == song.id && playback.state.value.playing }
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        val stems = listOf("bass", "drums", "guitar", "remaining", "piano", "vocals")
            .map { id ->
                if (id == "remaining") remainingAsset(stem, duration, 330.0)
                else stemAsset(id, stem, duration)
            }
        for (source in listOf(PlaybackSourceMode.ORIGINAL, PlaybackSourceMode.STEMS)) {
            if (source == PlaybackSourceMode.STEMS) {
                val result = runBlocking {
                    try {
                        withTimeout(12_000L) {
                            withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                        }
                    } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                        val snapshot = playback.nativeDiagnosticsSnapshotForTest()
                        throw AssertionError("Repeat Stems activation: ${snapshot?.nativeRenderReadiness}; " +
                            "${snapshot?.controllerStemReadiness}; ${snapshot?.controllerLoopProtocol}", failure)
                    }
                }
                assertTrue(result.toString(), result.isSuccess)
                waitUntil(8_000L) { playback.state.value.sourceMode == source }
                assertAudioHealth("$source activation")
            }
            for (tempo in listOf(1f, 1.35f)) {
                for (repeatMode in listOf("One", "All")) {
                    onMain {
                        assertTrue(playback.setRepeatMode(repeatMode))
                        assertTrue(playback.setTempoAndPitch(tempo, 0, lease))
                        playback.play()
                        assertTrue(playback.seekTo(6_800L))
                    }
                    waitUntil(5_000L) { playback.state.value.positionMs >= 6_500L }
                    assertAudioHealth("$source/$tempo/$repeatMode seek")
                    onMain {
                        assertTrue(playback.beginScrub(lease))
                        playback.previewScrub(7_400L)
                        playback.commitScrub(7_500L)
                    }
                    waitUntil(6_000L) {
                        playback.state.value.positionMs in 50L..2_000L && playback.state.value.playing
                    }
                    assertAudioHealth("$source/$tempo/$repeatMode scrub and wrap")
                    assertEquals("$source $tempo $repeatMode", song.id, playback.state.value.songId)
                    assertEquals(repeatMode, playback.state.value.repeatMode)
                    val before = playback.state.value.positionMs
                    waitUntil(3_000L) { playback.state.value.positionMs > before + 100L }
                    assertAudioHealth("$source/$tempo/$repeatMode continuation")
                }
            }
        }
    }
}
