package app.stemwise.qualification

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.playback.EndedSourceSwitchInstrumentationTest
import app.stemwise.playback.LoopPlaybackInstrumentationTest
import app.stemwise.playback.LoopRetargetInstrumentationTest
import app.stemwise.playback.LoopSourceHandoffInstrumentationTest
import app.stemwise.playback.MediaSessionLifecycleInstrumentationTest
import app.stemwise.playback.PcmContentIdentityInstrumentationTest
import app.stemwise.playback.PlayerScrubOwnershipInstrumentationTest
import app.stemwise.playback.PlaybackContentFixture
import app.stemwise.playback.QueueIntegrationInstrumentationTest
import app.stemwise.playback.RuntimePlaybackControlsInstrumentationTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Explicit singleton/global playback partition for a disposable target profile only.
 *
 * These children use synthetic media but exercise the application's real [StemwiseApplication]
 * playback singleton and deliberately mutate its process-wide queue and native owners. Therefore
 * this suite is BLOCKED on an owner profile and must run only against a disposable app-data
 * target. UI and fake-session coverage belongs to [AppActionSurfaceSuite] on the disposable
 * rehearsal target. The wrapper skips unless selected explicitly, preventing duplicate broad
 * discovery. The runner must also supply `-e stemwiseDisposableTarget true`; without that
 * acknowledgement the suite is an assumption/blocked result and never passes.
 *
 * Run with:
 * `adb shell am instrument -w -e class app.stemwise.qualification.UnifiedPlayerPressureSuite`
 * ` -e stemwiseDisposableTarget true`
 * ` -e listener app.stemwise.qualification.ProcessAudioHealthRunListener`
 * ` app.stemwise.development.test/`
 * `app.stemwise.qualification.DevelopmentStandardInstrumentationRunner`
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    PcmContentIdentityInstrumentationTest::class,
    PlayerScrubOwnershipInstrumentationTest::class,
    QueueIntegrationInstrumentationTest::class,
    EndedSourceSwitchInstrumentationTest::class,
    RuntimePlaybackControlsInstrumentationTest::class,
    LoopPlaybackInstrumentationTest::class,
    LoopRetargetInstrumentationTest::class,
    LoopSourceHandoffInstrumentationTest::class,
    MediaSessionLifecycleInstrumentationTest::class
)
class UnifiedPlayerPressureSuite {
    companion object {
        private const val AUDIO_HEALTH_TAG = "StemwiseSuiteAudioHealth"
        private const val AUDIO_HEALTH_SCHEMA_VERSION = 1
        private var startingAudioHealth: ProcessAudioHealth? = null
        private var suiteExecutionActive = false
        private val observedAudioHealthViolations = linkedSetOf<String>()
        private var firstFailureEvents: String? = null

        internal fun recordAudioHealthViolations(test: String, violations: List<String>) {
            if (violations.isNotEmpty() && firstFailureEvents == null) {
                firstFailureEvents = runCatching {
                    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                        as StemwiseApplication
                    "$test: " + app.services.audioPlayback.playbackEventHistoryForTest().joinToString(" | ")
                }.getOrElse { "history unavailable: ${it::class.java.simpleName}" }
            }
            violations.forEach { observedAudioHealthViolations += "$test: $it" }
        }

        /** Captured before any child class so one later reset cannot hide an earlier audio fault. */
        @BeforeClass
        @JvmStatic
        fun captureProcessAudioHealthBaseline() {
            StandardInstrumentationTargetGuard.requireCurrentTarget()
            val explicitlySelected = isExplicitlySelected(SUITE_CLASS_NAME)
            assumeTrue(
                "UnifiedPlayerPressureSuite must be selected explicitly with -e class; " +
                    "its children are independently discoverable.",
                explicitlySelected
            )
            assumeTrue(
                "UnifiedPlayerPressureSuite is BLOCKED on owner data; rerun only on a " +
                    "disposable app-data target with -e $DISPOSABLE_TARGET_ARGUMENT true.",
                InstrumentationRegistry.getArguments()
                    .getString(DISPOSABLE_TARGET_ARGUMENT) == "true"
            )
            FocusedSuiteMethodRunListener.begin(InstrumentationRegistry.getArguments(), SUITE_CLASS_NAME)
            suiteExecutionActive = true
            observedAudioHealthViolations.clear()
            firstFailureEvents = null
            PlaybackContentFixture.clear(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            )
            startingAudioHealth = captureProcessAudioHealth()
        }

        /**
         * One machine-readable, process-wide audio-health verdict. Process totals remain monotonic across
         * every output reopen and runtime replacement performed by the child tests.
         */
        @AfterClass
        @JvmStatic
        fun verifyProcessAudioHealthLedger() {
            if (!suiteExecutionActive) return
            val baseline = startingAudioHealth
            if (baseline == null) {
                failWithMarker("suite audio-health baseline was not captured")
            }
            val final = runCatching { captureProcessAudioHealth() }.getOrElse { failure ->
                failWithMarker(
                    "final suite audio-health snapshot failed: " +
                        (failure.message ?: failure::class.java.simpleName),
                    baseline = baseline
                )
            }
            val delta = final.deltaFrom(baseline)
            val violations = buildList {
                addAll(observedAudioHealthViolations)
                addAll(FocusedSuiteMethodRunListener.violations())
                if (baseline.xRunObservationRequired && !baseline.xRunObservationAvailable ||
                    final.xRunObservationRequired && !final.xRunObservationAvailable) {
                    add(
                        "xrun observation unavailable " +
                            "${baseline.xRunObservationAvailable}->" +
                            final.xRunObservationAvailable
                    )
                }
                baseline.nonZeroEntries().forEach { (name, value) ->
                    add("baseline $name=$value")
                }
                final.decreasesFrom(baseline).forEach { (name, values) ->
                    add("$name decreased ${values.first}->${values.second}")
                }
                delta.nonZeroEntries().forEach { (name, value) ->
                    add("$name delta=$value")
                }
            }
            val passed = violations.isEmpty()
            val marker = JSONObject()
                .put("schemaVersion", AUDIO_HEALTH_SCHEMA_VERSION)
                .put("status", if (passed) "PASS" else "FAIL")
                .put("baselineRequiredZero", true)
                .put("faultDeltasRequiredZero", true)
                .put(
                    "xRunObservationAvailable",
                    baseline.xRunObservationAvailable && final.xRunObservationAvailable
                )
                .put("xRunObservationRequired", baseline.xRunObservationRequired || final.xRunObservationRequired)
                .put("baseline", baseline.toJson())
                .put("final", final.toJson())
                .put("delta", delta.toJson())
                .put("violations", violations.joinToString("; "))
            if (passed) {
                Log.i(AUDIO_HEALTH_TAG, marker.toString())
            } else {
                Log.e(AUDIO_HEALTH_TAG, marker.toString())
                throw AssertionError(
                    "Unified player suite failed its checks: " +
                        violations.joinToString() + "\nFirst failure playback events: $firstFailureEvents"
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanDisposablePlaybackSession() {
            if (!suiteExecutionActive) return
            clearDisposablePlaybackSessionState()
            PlaybackContentFixture.clear(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            )
        }

        private fun captureProcessAudioHealth(): ProcessAudioHealth {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as StemwiseApplication
            val snapshot = requireNotNull(
                application.services.audioPlayback.nativeDiagnosticsSnapshotForTest()
            ) { "native runtime diagnostics unavailable" }
            val output = requireNotNull(snapshot.output) {
                "native output diagnostics unavailable"
            }
            return ProcessAudioHealth(
                invalidOwnershipTagFrames = output.processInvalidOwnershipTagFrames,
                underrunCallbackCount = output.processUnderrunCallbackCount,
                underrunFrames = output.processUnderrunFrames,
                xRunCount = output.processXRunCount,
                callbackDeadlineMissCount = output.processCallbackDeadlineMissCount,
                outputInvalidRenderResults =
                    snapshot.runtime.processOutputInvalidRenderResults,
                outputFlushFailures = snapshot.runtime.processOutputFlushFailures,
                incompleteStreamObservations = output.processIncompleteStreamObservationCount,
                xRunObservationAvailable = output.xRunCount >= 0,
                xRunObservationRequired = !snapshot.outputHasNeverOpened()
            )
        }

        private fun isExplicitlySelected(className: String): Boolean =
            InstrumentationRegistry.getArguments()
                .getString("class")
                ?.split(',')
                ?.map { value -> value.trim().substringBefore('#') }
                ?.any { value -> value == className } == true

        private fun clearDisposablePlaybackSessionState() {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as StemwiseApplication
            val playback = application.services.audioPlayback
            runBlocking { playback.clearLibrarySession() }
        }

        private fun failWithMarker(
            reason: String,
            baseline: ProcessAudioHealth? = null
        ): Nothing {
            val marker = JSONObject()
                .put("schemaVersion", AUDIO_HEALTH_SCHEMA_VERSION)
                .put("status", "FAIL")
                .put("baselineRequiredZero", true)
                .put("faultDeltasRequiredZero", true)
                .put("xRunObservationAvailable", baseline?.xRunObservationAvailable == true)
                .put("reason", reason)
            if (baseline != null) marker.put("baseline", baseline.toJson())
            Log.e(AUDIO_HEALTH_TAG, marker.toString())
            throw AssertionError(reason)
        }

        private const val SUITE_CLASS_NAME =
            "app.stemwise.qualification.UnifiedPlayerPressureSuite"
        private const val DISPOSABLE_TARGET_ARGUMENT = "stemwiseDisposableTarget"
    }

    private data class ProcessAudioHealth(
        val invalidOwnershipTagFrames: Long,
        val underrunCallbackCount: Long,
        val underrunFrames: Long,
        val xRunCount: Long,
        val callbackDeadlineMissCount: Long,
        val outputInvalidRenderResults: Long,
        val outputFlushFailures: Long,
        val incompleteStreamObservations: Long,
        val xRunObservationAvailable: Boolean,
        val xRunObservationRequired: Boolean
    ) {
        fun deltaFrom(baseline: ProcessAudioHealth) = ProcessAudioHealth(
            invalidOwnershipTagFrames - baseline.invalidOwnershipTagFrames,
            underrunCallbackCount - baseline.underrunCallbackCount,
            underrunFrames - baseline.underrunFrames,
            xRunCount - baseline.xRunCount,
            callbackDeadlineMissCount - baseline.callbackDeadlineMissCount,
            outputInvalidRenderResults - baseline.outputInvalidRenderResults,
            outputFlushFailures - baseline.outputFlushFailures,
            incompleteStreamObservations - baseline.incompleteStreamObservations,
            xRunObservationAvailable && baseline.xRunObservationAvailable,
            xRunObservationRequired || baseline.xRunObservationRequired
        )

        fun nonZeroEntries(): List<Pair<String, Long>> = entries().filter { it.second != 0L }

        fun decreasesFrom(baseline: ProcessAudioHealth): List<Pair<String, Pair<Long, Long>>> {
            val before = baseline.entries().toMap()
            return entries().mapNotNull { (name, value) ->
                val startingValue = requireNotNull(before[name])
                if (value < startingValue) name to (startingValue to value) else null
            }
        }

        fun toJson(): JSONObject = JSONObject().also { json ->
            entries().forEach { (name, value) -> json.put(name, value) }
            json.put("xRunObservationAvailable", xRunObservationAvailable)
            json.put("xRunObservationRequired", xRunObservationRequired)
        }

        private fun entries(): List<Pair<String, Long>> = listOf(
            "invalidOwnershipTagFrames" to invalidOwnershipTagFrames,
            "underrunCallbackCount" to underrunCallbackCount,
            "underrunFrames" to underrunFrames,
            "xRunCount" to xRunCount,
            "callbackDeadlineMissCount" to callbackDeadlineMissCount,
            "outputInvalidRenderResults" to outputInvalidRenderResults,
            "outputFlushFailures" to outputFlushFailures,
            "incompleteStreamObservations" to incompleteStreamObservations
        )
    }
}
