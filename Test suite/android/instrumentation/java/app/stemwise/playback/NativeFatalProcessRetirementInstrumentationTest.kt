package app.stemwise.playback

import android.app.ActivityManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.debug.ProcessRetirementProbeActivity
import app.stemwise.model.Song
import app.stemwise.model.SongState
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two-process regression that must be orchestrated by an explicit external runner.
 *
 * Phase A intentionally dies before JUnit can return. Phase B must run in a separate
 * instrumentation invocation without an intervening force-stop and proves clean native playback.
 * Phase B owns cleanup for both phases because successful Phase A cannot execute a `finally` block.
 */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeFatalProcessRetirementInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val playback get() = application.services.audioPlayback
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun fatalThenRecentsRemovalRetiresProcess() {
        assumeTrue(
            "Run only through the dedicated fatal-retirement verifier on a disposable target",
            arguments.getString(ARG_QUALIFICATION) == "true" &&
                arguments.getString(ARG_DISPOSABLE_TARGET) == "true"
        )
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)?.takeIf(String::isNotBlank))
        application.admitCurrentPlaybackLibrarySnapshot()
        playback.resetForInstrumentationIsolation(instrumentation)
        val source = File(application.cacheDir, PHASE_A_AUDIO_FILE)
        writeWave(source, 20_000L)
        val song = song("fatal-retirement-owner", source)
        val activityManager =
            application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        application.startActivity(
            Intent(application, ProcessRetirementProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        waitUntil(5_000L) { activityManager.appTasks.isNotEmpty() }
        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.playing &&
                playback.nativeDiagnosticsSnapshotForTest()?.output?.playing == true
        }

        // A service-token controller owns a real Android service binding. Keep it held through
        // removal: releasing it in the client would conceal a session stranded until onDestroy.
        val controllerFuture = if (arguments.getString(ARG_BOUND_CONTROLLER) == "false") null else
            MediaController.Builder(application,
                SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
        try {
            controllerFuture?.get(10L, TimeUnit.SECONDS)?.let { controller ->
                onMain { assertTrue("Phase A must hold the service binding before quarantine", controller.isConnected) }
            }
            val diagnostic = "Fatal Recents retirement qualification $runId"
            var accepted = false
            onMain { accepted = playback.debug.injectNativeActorFatalFault(diagnostic) }
            assertTrue(accepted)
            waitUntil(8_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                playback.processRestartRequiredAfterTaskRemoval() &&
                    native?.controllerFatalQuarantined == true &&
                    native.output?.playing == false
            }

            writeMarker(runId, Process.myPid())
            val task = activityManager.appTasks.maxByOrNull { it.taskInfo.taskId }
            assertTrue("StemWise task was not present for fatal Recents removal", task != null)
            onMain { requireNotNull(task).finishAndRemoveTask() }

            // Successful behavior kills this process after service and durable-work cleanup. Returning
            // normally would make an instrumentation abort indistinguishable from a passing test.
            SystemClock.sleep(8_000L)
            throw AssertionError(SENTINEL_PROCESS_DID_NOT_RETIRE)
        } finally {
            // Success retires this process; only a failing run reaches client-side release.
            controllerFuture?.let { onMain { MediaController.releaseFuture(it) } }
        }
    }

    @Test
    fun freshProcessAfterFatalRetirementCanPlay() {
        assumeTrue(
            "Run only through the dedicated fatal-retirement verifier on a disposable target",
            arguments.getString(ARG_QUALIFICATION) == "true" &&
                arguments.getString(ARG_DISPOSABLE_TARGET) == "true"
        )
        val runId = requireNotNull(arguments.getString(ARG_RUN_ID)?.takeIf(String::isNotBlank))
        var failure: Throwable? = null
        try {
            val marker = markerFile(runId)
            assertTrue("Phase-A retirement marker is missing", marker.isFile)
            val lines = marker.readLines()
            assertTrue("Phase-A retirement marker is malformed: $lines", lines.size >= 2)
            assertTrue("Phase-A run id does not match", lines[0] == runId)
            val priorPid = lines[1].toInt()
            assertNotEquals("Phase B reused the poisoned process", priorPid, Process.myPid())
            assertFalse(playback.processRestartRequiredAfterTaskRemoval())
            assertFalse(
                playback.nativeDiagnosticsSnapshotForTest()?.controllerFatalQuarantined == true
            )

            application.admitCurrentPlaybackLibrarySnapshot()
            val source = File(application.cacheDir, PHASE_B_AUDIO_FILE)
            writeWave(source, 20_000L)
            val song = song("fresh-after-fatal-retirement", source)
            val framesBefore = playback.nativeDiagnosticsSnapshotForTest()
                ?.output
                ?.totalFramesAccepted
                ?: 0L
            onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
            waitUntil(10_000L) {
                val native = playback.nativeDiagnosticsSnapshotForTest()
                playback.state.value.songId == song.id &&
                    playback.state.value.playWhenReady &&
                    playback.state.value.playing &&
                    playback.state.value.positionMs > 100L &&
                    PlaybackService.isActiveForTest() &&
                    native?.output?.playing == true &&
                    native.output.totalFramesAccepted > framesBefore
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val cleanupFailure = cleanupPhaseB(runId)
            if (failure == null && cleanupFailure != null) throw cleanupFailure
            if (cleanupFailure != null) failure?.addSuppressed(cleanupFailure)
        }
    }

    private fun cleanupPhaseB(runId: String): Throwable? {
        val failures = mutableListOf<Throwable>()
        runCatching {
            onMain {
                playback.pause()
                playback.state.value.songId?.let(playback::unload)
            }
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            playback.resetForInstrumentationIsolation(instrumentation)
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            runBlocking { playback.clearLibrarySession() }
        }.exceptionOrNull()?.let(failures::add)
        listOf(PHASE_A_AUDIO_FILE, PHASE_B_AUDIO_FILE).forEach { name ->
            runCatching {
                val file = File(application.cacheDir, name)
                check(!file.exists() || file.delete()) {
                    "Unable to delete fatal-retirement fixture ${file.absolutePath}"
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        runCatching {
            PlaybackContentFixture.clear(application)
        }.exceptionOrNull()?.let(failures::add)
        runCatching {
            val marker = markerFile(runId)
            check(!marker.exists() || marker.delete()) {
                "Unable to delete fatal-retirement marker ${marker.absolutePath}"
            }
            marker.parentFile
                ?.takeIf { directory ->
                    directory.isDirectory && directory.list()?.isEmpty() == true
                }
                ?.let { directory ->
                    check(directory.delete()) {
                        "Unable to delete empty fatal-retirement directory ${directory.absolutePath}"
                    }
                }
        }.exceptionOrNull()?.let(failures::add)
        if (failures.isEmpty()) return null
        return AssertionError("Fatal-retirement phase-B cleanup failed").apply {
            failures.forEach(::addSuppressed)
        }
    }

    private fun markerFile(runId: String): File = File(
        requireNotNull(application.getExternalFilesDir("qualification")),
        "native-fatal-retirement-$runId.txt"
    )

    private fun writeMarker(runId: String, pid: Int) {
        val marker = markerFile(runId)
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { output ->
            output.write("$runId\n$pid\n".toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun song(id: String, source: File) = Song(
        id = id,
        videoId = id,
        title = "Fatal retirement test",
        artist = "StemWise",
        durationMs = 20_000L,
        state = SongState.READY,
        accent = Color.Cyan,
        artworkGlyph = "F",
        sourceHash = PlaybackContentFixture.sourceHash(source),
            sourcePath = PlaybackContentFixture.publish(application, source),
        sourceCodec = "pcm_s16le",
        sourceContainer = "wav"
    )

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        assertTrue(
            "Condition did not settle: ${playback.instrumentationIsolationDiagnostics()}",
            predicate()
        )
    }

    private fun writeWave(file: File, durationMs: Long) {
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
                val sample = (
                    kotlin.math.sin(2.0 * Math.PI * 220.0 * index / sampleRate) * 2_000.0
                    ).toInt().toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    companion object {
        private const val ARG_QUALIFICATION = "stemwiseFatalRetirementQualification"
        private const val ARG_RUN_ID = "stemwiseFatalRetirementRunId"
        private const val ARG_DISPOSABLE_TARGET = "stemwiseDisposableTarget"
        private const val ARG_BOUND_CONTROLLER = "stemwiseFatalRetirementBoundController"
        private const val PHASE_A_AUDIO_FILE = "fatal-retirement-audio.wav"
        private const val PHASE_B_AUDIO_FILE = "fatal-retirement-fresh-audio.wav"
        internal const val SENTINEL_PROCESS_DID_NOT_RETIRE =
            "FATAL_RECENTS_PROCESS_RETIREMENT_DID_NOT_OCCUR"
    }
}
