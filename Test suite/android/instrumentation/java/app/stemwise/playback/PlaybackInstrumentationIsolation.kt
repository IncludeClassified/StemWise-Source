package app.stemwise.playback

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.data.ColdOpenState
import app.stemwise.data.LibraryProjectionAvailability
import app.stemwise.playback.engine.NativeRuntimeStatus
import app.stemwise.qualification.outputHasNeverOpened
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

private const val PLAYBACK_ISOLATION_TIMEOUT_MS = 8_000L
internal const val DISPOSABLE_PLAYBACK_TARGET_ARGUMENT = "stemwiseDisposableTarget"

/** Prevents accidental broad discovery from mutating an owner's singleton playback session. */
internal fun requireDisposablePlaybackTarget() {
    assumeTrue(
        "Real singleton playback instrumentation is BLOCKED on owner data; run only on a " +
            "disposable app-data target with -e $DISPOSABLE_PLAYBACK_TARGET_ARGUMENT true.",
        InstrumentationRegistry.getArguments()
            .getString(DISPOSABLE_PLAYBACK_TARGET_ARGUMENT) == "true"
    )
}

/** Admission wraps JUnit's before/after lifecycle, so a skipped target has no teardown. */
internal class DisposablePlaybackTargetRule(
    private val admit: () -> Unit = ::requireDisposablePlaybackTarget
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            admit()
            base.evaluate()
        }
    }
}

/** Admit the current real Library only for fixtures that own singleton audio playback. */
internal fun StemwiseApplication.admitCurrentPlaybackLibrarySnapshot() = runBlocking {
    withTimeout(30_000L) {
        workerInitialization.await()
        val roots = services.libraryRootManager
        val root = checkNotNull(roots.captureHandle()) { "Playback fixture has no current Library" }
        val coldOpen = services.coldOpenCoordinator
        while (true) {
            val admitted = withContext(Dispatchers.Main) {
                roots.requireSameLibrary(root)
                check(coldOpen.state.value !is ColdOpenState.Blocked) {
                    "Playback fixture Library admission failed: ${coldOpen.state.value}"
                }
                if (!coldOpen.isProducerAdmissionReady(root)) return@withContext false
                val snapshot = services.externalLibraryRepository.playbackSnapshot()
                roots.requireSameLibrary(root)
                val projectionReady = services.externalLibraryRepository.projectionAvailability.value in setOf(
                    LibraryProjectionAvailability.PROJECTED, LibraryProjectionAvailability.VERIFIED)
                if (!coldOpen.isProducerAdmissionReady(root) || !snapshot.snapshotReady || !projectionReady) false
                else {
                    services.audioPlayback.setPlaybackQueue(snapshot.songs, snapshotReady = snapshot.snapshotReady)
                    true
                }
            }
            if (admitted) return@withTimeout
            delay(20L)
        }
    }
}

/**
 * Returns the process-wide playback facade to a proved empty native state between instrumentation
 * cases. Public [AudioPlayback.state] deliberately hides an uncommitted selection, so cleanup must
 * use the adapter's synchronous latest/committed ownership view instead of `state.songId` alone.
 */
internal fun AudioPlayback.resetForInstrumentationIsolation(
    instrumentation: Instrumentation,
    timeoutMs: Long = PLAYBACK_ISOLATION_TIMEOUT_MS,
    qualificationDiagnostics: Boolean = false
) {
    instrumentation.runOnMainSync {
        // A failed assertion can bypass the normal release in a selection-pressure test. Releasing
        // is idempotent and must happen before STOP can be consumed by the native actor.
        debug.releaseNativeActorForSelectionBurst()
    }
    // JUnit invokes @After for one case and @Before for the next. If @After already proved the
    // native/output terminal state, issuing another asynchronous STOP here can return against that
    // old proof before the duplicate request is consumed, allowing it to race the next selection.
    if (playbackIsolationSettled()) {
        instrumentation.runOnMainSync { establishInstrumentationOptions(qualificationDiagnostics) }
        return
    }

    var serviceStopRequired = false
    instrumentation.runOnMainSync {
        val selection = selectionPressureDiagnosticsForTest()
        val selectedSongId = selection.latestRequestedSongId ?: selection.committedSongId
        if (selectedSongId != null) {
            unload(selectedSongId)
        } else {
            // Covers loading=true/songId=null, failed decode, and a pending-only publication.
            stopForTaskRemoval()
            instrumentation.targetContext.stopService(
                Intent(instrumentation.targetContext, PlaybackService::class.java)
            )
            serviceStopRequired = true
        }
    }

    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var settled = playbackIsolationSettled() &&
        (!serviceStopRequired || !PlaybackService.isActiveForTest())
    while (!settled && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(20L)
        settled = playbackIsolationSettled() &&
            (!serviceStopRequired || !PlaybackService.isActiveForTest())
    }
    if (!settled) {
        throw AssertionError(
            "Playback did not reach an isolated stopped state within ${timeoutMs}ms: " +
                instrumentationIsolationDiagnostics()
        )
    }
    instrumentation.runOnMainSync { establishInstrumentationOptions(qualificationDiagnostics) }
}

private fun AudioPlayback.establishInstrumentationOptions(qualificationDiagnostics: Boolean) {
    debug.enableInstrumentation()
    if (librarySessionCommandsAllowedForTest()) {
        // These commands also cancel a pending listening restore while the Library is admitted.
        check(setRepeatMode("Off")) { instrumentationIsolationDiagnostics() }
        check(setShuffle(false)) { instrumentationIsolationDiagnostics() }
    }
    setDiagnosticPayloadFingerprintsForTest(qualificationDiagnostics)
    check(state.value.repeatMode == "Off" && !state.value.shuffleEnabled) {
        instrumentationIsolationDiagnostics()
    }
}

internal fun AudioPlayback.instrumentationIsolationDiagnostics(): String = buildString {
    append("state=")
    append(state.value)
    append("; libraryCommandsAllowed=")
    append(librarySessionCommandsAllowedForTest())
    append("; selection=")
    append(selectionPressureDiagnosticsForTest())
    append("; graphMediaId=")
    append(graphMediaId())
    append("; transitionTarget=")
    append(queueTransitionTargetSummaryForTest())
    append("; graph=")
    append(graphDiagnostics())
    append("; resources=")
    append(playbackResourceDiagnosticsForTest())
    append("; native=")
    append(nativeDiagnosticsSnapshotForTest())
}

private fun AudioPlayback.playbackIsolationSettled(): Boolean {
    val selection = selectionPressureDiagnosticsForTest()
    val native = nativeDiagnosticsSnapshotForTest()
    val output = native?.output
    val resources = playbackResourceDiagnosticsForTest()
    return selection.latestRequestedToken == null &&
        selection.pendingToken == null &&
        selection.committedToken == null &&
        state.value.songId == null &&
        !state.value.loading &&
        resources.assignedNativeBankCount == 0 &&
        resources.totalDecoderLeaseCount == 0 &&
        resources.decoderWorkerRunningCount == 0 &&
        resources.decoderWorkerQueuedCount == 0 &&
        (native == null || native.runtime.status == NativeRuntimeStatus.STOPPED || native.outputHasNeverOpened()) &&
        (output == null ||
            (!output.playing && !output.effectiveRenderPlaying && output.queuedFrames == 0L))
}
