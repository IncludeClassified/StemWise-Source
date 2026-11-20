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
import app.stemwise.playback.engine.NativeControllerPreparationOperation
import app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot
import app.stemwise.qa.DirectLoopEnduranceOracle
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
abstract class LoopPlaybackFixture {
    protected val qualificationDurationMs = 30_000L
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    protected val playback
        get() = application.services.audioPlayback
    protected var loopFailurePhase = "setup"

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @Before
    fun isolatePlaybackBeforeTest() {
        application.admitCurrentPlaybackLibrarySnapshot()
        isolateAndCleanOwnedState()
    }

    @After
    fun isolatePlaybackAfterTest() = isolateAndCleanOwnedState()

    protected fun isolateAndCleanOwnedState() {
        val failures = mutableListOf<Throwable>()
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        PlaybackContentFixture.clear(application)
        runCatching {
            application.getExternalFilesDir(null)?.let { externalDirectory ->
                val report = File(externalDirectory, "loop-qualification.json")
                check(!report.exists() || report.delete()) {
                    "Unable to delete loop evidence file ${report.absolutePath}"
                }
            }
        }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) {
            val failure = AssertionError("Loop playback isolation or cleanup failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    protected fun directLoopAdoptedForCurrentOwner(): Boolean {
        val diagnostics = playback.nativeDiagnosticsSnapshotForTest() ?: return false
        val position = diagnostics.runtime.presentedPosition ?: return false
        val readiness = diagnostics.nativeRenderReadiness ?: return false
        return readiness.ownership == position.ownership &&
            readiness.directAtomicPolicyGeneration == position.ownership.generation &&
            readiness.directAtomicPolicyPublishedRevision > 0L &&
            readiness.directAtomicPolicyPublishedRevision == diagnostics.output?.loop?.latestDemandRevision &&
            readiness.directAtomicPolicyAdoptedRevision ==
                readiness.directAtomicPolicyPublishedRevision &&
            readiness.directAtomicPolicyObservedRevision ==
                readiness.directAtomicPolicyPublishedRevision &&
            position.loopPolicyAdoptedRevision == readiness.directAtomicPolicyPublishedRevision
    }

    protected fun awaitDirectWraps(
        startMs: Long,
        endMs: Long,
        minimumWraps: Int,
        timeoutMs: Long,
        onExactAdoption: (Long) -> Unit = {}
    ): Int {
        require(startMs >= 0L && endMs > startMs && minimumWraps > 0)
        val startFrame = startMs * 48L
        val endFrame = endMs * 48L
        var oracle: DirectLoopEnduranceOracle? = null
        var stopped: AudioPlaybackState? = null
        var deadline = SystemClock.elapsedRealtime() + timeoutMs
        // Capture the current head without copying retained spans that this observation excludes.
        var cursor = playback.presentationJournalForTest(Long.MAX_VALUE).writeSequence
        while ((oracle?.wraps ?: 0L) < minimumWraps && SystemClock.elapsedRealtime() < deadline) {
            val state = playback.state.value
            if (!state.playing || !state.playWhenReady || state.ended || state.error != null) {
                stopped = state
                break
            }
            if (oracle == null) {
                val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
                val position = diagnostics?.runtime?.presentedPosition
                val readiness = diagnostics?.nativeRenderReadiness
                val exactPolicy = position != null && readiness != null &&
                    readiness.ownership == position.ownership &&
                    readiness.directAtomicPolicyGeneration == position.ownership.generation &&
                    readiness.directAtomicPolicyPublishedRevision > 0L &&
                    readiness.directAtomicPolicyAdoptedRevision ==
                        readiness.directAtomicPolicyPublishedRevision &&
                    readiness.directAtomicPolicyObservedRevision ==
                        readiness.directAtomicPolicyPublishedRevision &&
                    position.loopPolicyAdoptedRevision ==
                        readiness.directAtomicPolicyPublishedRevision && position.loopPolicyRevision > 0L
                val baseline = playback.presentationJournalForTest(cursor)
                cursor = baseline.writeSequence
                if (exactPolicy) {
                    oracle = DirectLoopEnduranceOracle(startFrame, endFrame,
                        requireNotNull(position).ownership,
                        position.loopPolicyRevision, baseline, state.tempo.toDouble())
                    // Admission is bounded separately; count endurance only after exact PCM adoption.
                    val adoptedAtMs = SystemClock.elapsedRealtime()
                    deadline = adoptedAtMs + timeoutMs
                    onExactAdoption(adoptedAtMs)
                }
            } else {
                check(state.tempo.toDouble() == oracle.sourceFramesPerPcmFrame) { "Endurance tempo changed during observation" }
                check(state.loopA == startMs && state.loopB == endMs) { "Endurance A/B intent changed during observation" }
                // Read every callback span at a UI-like cadence. Full decoder/runtime snapshots
                // at 500 Hz allocate enough diagnostic garbage to compete with the measured DSP.
                try {
                    oracle.consume(playback.presentationJournalForTest(oracle.cursor))
                } catch (failure: Throwable) {
                    try {
                        val native = playback.nativeDiagnosticsSnapshotForTest()
                        val sources = native?.controllerSourceControlPublications.orEmpty().takeLast(8).map {
                            "${it.ownership}:desired=${it.desiredRevision}/source=${it.requestedMode}->${it.nativeMode}/" +
                                "nativeControl=${it.nativeControlRevision}/sourceIntent=${it.nativeControls?.sourceIntentRevision}/" +
                                "stemRequest=${it.stemRequestRevision}"
                        }
                        failure.addSuppressed(AssertionError(
                            "Direct-loop oracle failure before cleanup: ${compactLoopFailureContext(native)}; " +
                                "pendingSeek=${native?.controllerPendingSeekOwnership}; " +
                                "selected=${native?.controllerSelectedDecoders?.map { "${it.decoderId}/${it.laneIndex}:${it.head}" }}; " +
                                "candidate=${native?.controllerCandidateDecoders?.map { "${it.decoderId}/${it.laneIndex}:${it.head}" }}; " +
                                "sourcePublications=$sources; preparation=${native?.controllerPreparationStages?.takeLast(16)}; " +
                                "state=${playback.state.value}; graph=${playback.graphDiagnostics()}; " +
                                "session=${playback.mediaSessionLifecycleDiagnosticsForTest()}"
                        ))
                    } catch (diagnosticFailure: Throwable) {
                        failure.addSuppressed(diagnosticFailure)
                    }
                    throw failure
                }
            }
            SystemClock.sleep(10L)
        }
        if ((oracle?.wraps ?: 0L) < minimumWraps || stopped != null) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            fail("Direct loop did not complete $minimumWraps exact wraps in ${timeoutMs}ms: " +
                "${compactLoopFailureContext(native)}; wraps=${oracle?.wraps} stopped=$stopped " +
                "state=${playback.state.value} native=$native")
        }
        return requireNotNull(oracle).wraps.toInt()
    }

    protected fun stemAsset(
        id: String,
        file: File,
        durationMs: Long = qualificationDurationMs
    ) = PlaybackContentFixture.stem(application, id, file, durationMs, "qualification-set")

    protected fun remainingAsset(tone: File, durationMs: Long, frequency: Double): StemAssetEntity {
        val remaining = File(tone.parentFile, "${tone.nameWithoutExtension}-remaining.wv")
        PlaybackContentFixture.writeRemainingTone(application, remaining, durationMs, frequency, 4_000.0 / 32_768.0)
        return stemAsset("remaining", remaining, durationMs).copy(
            codec = "wavpack", sampleRateHz = 48_000, channelCount = 2
        )
    }

    protected fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    protected fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(20L)
        }
        if (predicate()) return
        val native = playback.nativeDiagnosticsSnapshotForTest()
        val actorStacks = Thread.getAllStackTraces().filterKeys {
            it.name == "StemWise-NativeEngine" || it.name.startsWith("StemWise-PreparationRetirement")
        }.entries.joinToString("; ") { (thread, stack) ->
            "${thread.name}/${thread.state}: ${stack.take(16).joinToString(" <- ")}"
        }
        fail(
            "Timed out waiting for playback state: phase=$loopFailurePhase; ${compactLoopFailureContext(native)}; " +
                "actorStacks=$actorStacks; " +
                "state=${playback.state.value}; session=${playback.mediaSessionLifecycleDiagnosticsForTest()}; " +
                "native=$native; graph=${playback.graphDiagnostics()}"
        )
    }

    private fun compactLoopFailureContext(native: NativeEngineDiagnosticsSnapshot?): String {
        val controls = playback.latestRequestedControlsForTest()
        val ownership = native?.runtime?.ownership
        val readiness = native?.nativeRenderReadiness
        val protocol = native?.controllerLoopProtocol
        val head = protocol?.preparation
        val headStages = native?.controllerPreparationStages.orEmpty().filter {
            it.operation == NativeControllerPreparationOperation.LOOP_HEAD
        }
        val lastCommit = headStages.lastOrNull { it.stage == "cache-committed" }
        val source = native?.controllerSourceControlPublications?.lastOrNull {
            it.ownership == ownership?.requested
        }
        val bankKey = native?.runtime?.canonicalPcmIo?.let {
            "${it.ownership}/${it.songIdentityHash}/${it.originalContentHash}/${it.stemPackageHash}"
        } ?: "not-exposed-without-canonical-cache"
        return "loopContext(owner=$ownership, public=${native?.controllerPublicOwnership}, " +
            "seek=${controls?.requestedPositionMs}/${controls?.seekRevision}, " +
            "pendingSeek=${native?.controllerPendingSeekOwnership}, " +
            "ingress=${native?.controllerSelectedIngressEpoch}, request=${playback.state.value.loopPreparation}, " +
            "demand=${readiness?.directAtomicPolicyGeneration}/${readiness?.directAtomicPolicyPublishedRevision}, " +
            "render=${readiness?.renderStartTimelineFrame}, storage=${readiness?.cacheStorageStatus}, " +
            "bankKey=$bankKey, managedHeadKey=${protocol?.activeCache?.key}, " +
            "globalHeadKey=not-exposed, head=${head?.phase}/${head?.loopRevision}/" +
            "${head?.requiredLaneMask}/${head?.completedLaneMask}/end${head?.nextExpectedTimelineFrame}, " +
            "lastHeadCommit=$lastCommit, headStages=${headStages.takeLast(3)}, " +
            "source=${source?.nativeControlRevision}/${source?.desiredRevision}/" +
            "${source?.requestedMode}/${source?.nativeMode}, parts=${source?.stemRevision}/" +
            "${source?.stemRequestRevision}/${source?.stemLaneIds}, " +
            "physicalPackage=${native?.controllerStemReadiness?.stemPackageSignature})"
    }

    protected fun writeSineWave(file: File, durationMs: Long, frequency: Double) {
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
                    (kotlin.math.sin(2.0 * Math.PI * frequency * index / sampleRate) * 4_000.0)
                        .toInt()
                        .toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }
}
