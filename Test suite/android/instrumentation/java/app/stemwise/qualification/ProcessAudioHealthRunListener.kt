package app.stemwise.qualification

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.playback.engine.NativeEngineDiagnosticsSnapshot
import app.stemwise.playback.engine.NativeAudioDiagnostics
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/**
 * Test-only diagnostic listener for locating the first process-cumulative native audio fault.
 *
 * Enable only for [UnifiedPlayerPressureSuite] with
 * `-e listener app.stemwise.qualification.ProcessAudioHealthRunListener`. Its snapshots observe
 * the application's real playback singleton used by every child in that disposable-profile suite.
 * Do not attach it to direct, test-created native outputs: it cannot prove that scope. Capture is
 * mandatory, and nonzero/decreasing fault counters or unavailable xrun observation fail closed.
 * The suite ledger remains the process-wide release authority.
 */
class ProcessAudioHealthRunListener : RunListener() {
    private var baseline: AudioHealth? = null
    private var lastOutput: NativeAudioDiagnostics? = null
    private var activeTest: String? = null

    override fun testStarted(description: Description) {
        activeTest = description.displayName
        baseline = capture("START", description.displayName)
        emit(
            event = "START",
            test = description.displayName,
            before = baseline,
            after = baseline
        )
    }

    override fun testFailure(failure: Failure) {
        // Freeze context at the first failure; the suite's own assertion survives Android XML formatting.
        UnifiedPlayerPressureSuite.recordAudioHealthViolations(
            failure.description.displayName, listOf("test failure: ${failure.exception::class.java.simpleName}")
        )
        val after = capture("FAILURE", failure.description.displayName)
        emit(
            event = "FAILURE",
            test = failure.description.displayName,
            before = baseline,
            after = after,
            detail = failure.exception::class.java.simpleName
        )
    }

    override fun testAssumptionFailure(failure: Failure) {
        val after = capture("ASSUMPTION", failure.description.displayName)
        emit(
            event = "ASSUMPTION",
            test = failure.description.displayName,
            before = baseline,
            after = after,
            detail = failure.exception::class.java.simpleName
        )
    }

    override fun testFinished(description: Description) {
        val after = capture("FINISH", description.displayName)
        val violations = after.violationsFrom(baseline, lastOutput)
        emit(
            event = "FINISH",
            test = description.displayName,
            before = baseline,
            after = after,
            violations = violations
        )
        baseline = after
        activeTest = null
        // Throwing from a listener aborts the remaining children of the current JUnit class.
        // Keep the verdict sticky in the suite owner so every test still runs and no observed
        // fault (including a transient counter decrease) can disappear from the final failure.
        UnifiedPlayerPressureSuite.recordAudioHealthViolations(description.displayName, violations)
    }

    override fun testRunFinished(result: org.junit.runner.Result) {
        val test = activeTest ?: "<none>"
        val after = capture("RUN_FINISH", test)
        emit(
            event = "RUN_FINISH",
            test = test,
            before = baseline,
            after = after,
            detail = "run=${result.runCount},failures=${result.failureCount},ignored=${result.ignoreCount}"
        )
    }

    private fun capture(event: String, test: String): AudioHealth = try {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as StemwiseApplication
        val snapshot = requireNotNull(
            application.services.audioPlayback.nativeDiagnosticsSnapshotForTest()
        ) { "native runtime diagnostics unavailable" }
        lastOutput = snapshot.output
        AudioHealth.from(snapshot)
    } catch (cause: Throwable) {
        val reason = cause.message ?: cause::class.java.simpleName
        Log.e(
            TAG,
            JSONObject()
                .put("schemaVersion", 1)
                .put("event", "CAPTURE_FAILURE")
                .put("requestedEvent", event)
                .put("test", test)
                .put("captureRequired", true)
                .put("reason", reason)
                .toString(),
            cause
        )
        throw AssertionError(
            "Required process audio-health capture failed for $event/$test: $reason"
        ).apply { initCause(cause) }
    }

    private fun emit(
        event: String,
        test: String,
        before: AudioHealth?,
        after: AudioHealth?,
        detail: String? = null,
        violations: List<String>? = null
    ) {
        val marker = JSONObject()
            .put("schemaVersion", 1)
            .put("event", event)
            .put("test", test)
            .put("captureRequired", true)
            .put("coverageScope", "APPLICATION_PLAYBACK_SINGLETON_ONLY")
            .put(
                "status",
                when {
                    violations == null -> "OBSERVED"
                    violations.isEmpty() -> "PASS"
                    else -> "FAIL"
                }
            )
            .put("before", before?.toJson())
            .put("after", after?.toJson())
            .put("delta", after?.deltaFrom(before)?.toJson())
        if (detail != null) marker.put("detail", detail)
        if (violations != null) marker.put("violations", violations.joinToString("; "))
        if (violations?.isNotEmpty() == true) {
            Log.e(TAG, marker.toString())
        } else {
            Log.i(TAG, marker.toString())
        }
    }

    private data class AudioHealth(
        val underrunCallbacks: Long,
        val underrunFrames: Long,
        val invalidOwnershipTagFrames: Long,
        val xRuns: Long,
        val callbackDeadlineMisses: Long,
        val invalidRenderResults: Long,
        val outputFlushFailures: Long,
        val loopBoundaryMisses: Long,
        val incompleteStreamObservations: Long,
        val xRunObservationAvailable: Boolean,
        val xRunObservationRequired: Boolean,
        val lastUnderrunSequence: Long,
        val lastUnderrunCallbackSequence: Long,
        val lastUnderrunGenerationAtRenderDecision: Long,
        val lastUnderrunGenerationAtCompletion: Long,
        val lastUnderrunRequestedFrames: Long,
        val lastUnderrunQueuedFramesAtRenderDecision: Long,
        val lastUnderrunPhysicalFrames: Long,
        val lastUnderrunValidPcmFrames: Long,
        val lastUnderrunMissingFrames: Long,
        val lastUnderrunCallbackStartGapMicros: Long,
        val physicalRunSequence: Long,
        val lastUnderrunPhysicalRunSequence: Long,
        val missingControlFrames: Long,
        val staleControlFrames: Long,
        val lastUnderrunWasManualArmBoundary: Boolean,
        val processManualArmBoundaryUnderrunCallbacks: Long,
        val processManualArmBoundaryUnderrunFrames: Long,
        val lastSuccessfulEnqueueGeneration: Long,
        val lastUnderrunSuccessfulEnqueueSequence: Long,
        val lastUnderrunSuccessfulEnqueueSnapshotCoherent: Boolean,
        val lastSuccessfulEnqueueAgeMicros: Long,
        val lastSuccessfulEnqueueQueueAfterFrames: Long,
        val lastUnderrunMaximumSuccessfulEnqueueGapMicros: Long
    ) {
        fun deltaFrom(before: AudioHealth?): AudioHealth? {
            if (before == null) return null
            return copy(
                underrunCallbacks = underrunCallbacks - before.underrunCallbacks,
                underrunFrames = underrunFrames - before.underrunFrames,
                invalidOwnershipTagFrames =
                    invalidOwnershipTagFrames - before.invalidOwnershipTagFrames,
                xRuns = xRuns - before.xRuns,
                callbackDeadlineMisses =
                    callbackDeadlineMisses - before.callbackDeadlineMisses,
                invalidRenderResults = invalidRenderResults - before.invalidRenderResults,
                outputFlushFailures = outputFlushFailures - before.outputFlushFailures,
                loopBoundaryMisses = loopBoundaryMisses - before.loopBoundaryMisses,
                incompleteStreamObservations = incompleteStreamObservations - before.incompleteStreamObservations,
                xRunObservationAvailable =
                    xRunObservationAvailable && before.xRunObservationAvailable,
                xRunObservationRequired = xRunObservationRequired || before.xRunObservationRequired,
                processManualArmBoundaryUnderrunCallbacks =
                    processManualArmBoundaryUnderrunCallbacks -
                        before.processManualArmBoundaryUnderrunCallbacks,
                processManualArmBoundaryUnderrunFrames =
                    processManualArmBoundaryUnderrunFrames -
                        before.processManualArmBoundaryUnderrunFrames
            )
        }

        fun violationsFrom(before: AudioHealth?, output: NativeAudioDiagnostics?): List<String> = buildList {
            if (before == null) {
                add("baseline snapshot unavailable")
                return@buildList
            }
            if (before.xRunObservationRequired && !before.xRunObservationAvailable ||
                xRunObservationRequired && !xRunObservationAvailable) {
                add(
                    "xrun observation unavailable " +
                        "${before.xRunObservationAvailable}->$xRunObservationAvailable"
                )
            }
            before.faultCounters().forEach { (name, value) ->
                if (value != 0L) add("baseline $name=$value")
            }
            val starting = before.faultCounters().toMap()
            faultCounters().forEach { (name, value) ->
                val baselineValue = requireNotNull(starting[name])
                if (value < baselineValue) add("$name decreased $baselineValue->$value")
                val delta = value - baselineValue
                if (delta != 0L) {
                    // Preserve fault-specific evidence in the suite assertion/XML. Broad device
                    // logcat is deliberately discarded, and a later test can replace this output.
                    add("$name delta=$delta; maxCallbackUs=${output?.maxCallbackDurationMicros}" +
                        "; lastDeadlineMissStartUs=${output?.lastCallbackDeadlineMissStartMicros}" +
                        "; lastDeadlineMissEndUs=${output?.lastCallbackDeadlineMissEndMicros}" +
                        "; maxStartGapUs=${output?.maximumCallbackStartGapMicros}; loop=${output?.loop}")
                    if (name == "underrunCallbacks") {
                        add("Captured underrun: ${toJson()}")
                    }
                }
            }
        }

        private fun faultCounters(): List<Pair<String, Long>> = listOf(
            "underrunCallbacks" to underrunCallbacks,
            "underrunFrames" to underrunFrames,
            "invalidOwnershipTagFrames" to invalidOwnershipTagFrames,
            "xRuns" to xRuns,
            "callbackDeadlineMisses" to callbackDeadlineMisses,
            "invalidRenderResults" to invalidRenderResults,
            "outputFlushFailures" to outputFlushFailures,
            "loopBoundaryMisses" to loopBoundaryMisses,
            "incompleteStreamObservations" to incompleteStreamObservations
        )

        fun toJson(): JSONObject = JSONObject()
            .put("underrunCallbacks", underrunCallbacks)
            .put("underrunFrames", underrunFrames)
            .put("invalidOwnershipTagFrames", invalidOwnershipTagFrames)
            .put("xRuns", xRuns)
            .put("callbackDeadlineMisses", callbackDeadlineMisses)
            .put("invalidRenderResults", invalidRenderResults)
            .put("outputFlushFailures", outputFlushFailures)
            .put("loopBoundaryMisses", loopBoundaryMisses)
            .put("incompleteStreamObservations", incompleteStreamObservations)
            .put("xRunObservationAvailable", xRunObservationAvailable)
            .put("xRunObservationRequired", xRunObservationRequired)
            .put("lastUnderrunSequence", lastUnderrunSequence)
            .put("lastUnderrunCallbackSequence", lastUnderrunCallbackSequence)
            .put("lastUnderrunGenerationAtRenderDecision", lastUnderrunGenerationAtRenderDecision)
            .put("lastUnderrunGenerationAtCompletion", lastUnderrunGenerationAtCompletion)
            .put("lastUnderrunRequestedFrames", lastUnderrunRequestedFrames)
            .put("lastUnderrunQueuedFramesAtRenderDecision", lastUnderrunQueuedFramesAtRenderDecision)
            .put("lastUnderrunPhysicalFrames", lastUnderrunPhysicalFrames)
            .put("lastUnderrunValidPcmFrames", lastUnderrunValidPcmFrames)
            .put("lastUnderrunMissingFrames", lastUnderrunMissingFrames)
            .put("lastUnderrunCallbackStartGapMicros", lastUnderrunCallbackStartGapMicros)
            .put("physicalRunSequence", physicalRunSequence)
            .put("lastUnderrunPhysicalRunSequence", lastUnderrunPhysicalRunSequence)
            .put("missingControlFrames", missingControlFrames)
            .put("staleControlFrames", staleControlFrames)
            .put("lastUnderrunWasManualArmBoundary", lastUnderrunWasManualArmBoundary)
            .put(
                "processManualArmBoundaryUnderrunCallbacks",
                processManualArmBoundaryUnderrunCallbacks
            )
            .put("processManualArmBoundaryUnderrunFrames", processManualArmBoundaryUnderrunFrames)
            .put("lastSuccessfulEnqueueGeneration", lastSuccessfulEnqueueGeneration)
            .put("lastUnderrunSuccessfulEnqueueSequence", lastUnderrunSuccessfulEnqueueSequence)
            .put("lastUnderrunSuccessfulEnqueueSnapshotCoherent", lastUnderrunSuccessfulEnqueueSnapshotCoherent)
            .put("lastSuccessfulEnqueueAgeMicros", lastSuccessfulEnqueueAgeMicros)
            .put("lastSuccessfulEnqueueQueueAfterFrames", lastSuccessfulEnqueueQueueAfterFrames)
            .put(
                "lastUnderrunMaximumSuccessfulEnqueueGapMicros",
                lastUnderrunMaximumSuccessfulEnqueueGapMicros
            )

        companion object {
            fun from(snapshot: NativeEngineDiagnosticsSnapshot): AudioHealth {
                val output = requireNotNull(snapshot.output)
                return AudioHealth(
                    underrunCallbacks = output.processUnderrunCallbackCount,
                    underrunFrames = output.processUnderrunFrames,
                    invalidOwnershipTagFrames = output.processInvalidOwnershipTagFrames,
                    xRuns = output.processXRunCount,
                    callbackDeadlineMisses = output.processCallbackDeadlineMissCount,
                    invalidRenderResults = snapshot.runtime.processOutputInvalidRenderResults,
                    outputFlushFailures = snapshot.runtime.processOutputFlushFailures,
                    loopBoundaryMisses = output.loop.processBoundaryMissCount,
                    incompleteStreamObservations = output.processIncompleteStreamObservationCount,
                    xRunObservationAvailable = output.xRunCount >= 0L,
                    xRunObservationRequired = !snapshot.outputHasNeverOpened(),
                    lastUnderrunSequence = output.lastUnderrunSequence,
                    lastUnderrunCallbackSequence = output.lastUnderrunCallbackSequence,
                    lastUnderrunGenerationAtRenderDecision =
                        output.lastUnderrunGenerationAtRenderDecision,
                    lastUnderrunGenerationAtCompletion =
                        output.lastUnderrunGenerationAtCompletion,
                    lastUnderrunRequestedFrames = output.lastUnderrunRequestedFrames,
                    lastUnderrunQueuedFramesAtRenderDecision =
                        output.lastUnderrunQueuedFramesAtRenderDecision,
                    lastUnderrunPhysicalFrames = output.lastUnderrunPhysicalFrames,
                    lastUnderrunValidPcmFrames = output.lastUnderrunValidPcmFrames,
                    lastUnderrunMissingFrames = output.lastUnderrunMissingFrames,
                    lastUnderrunCallbackStartGapMicros =
                        output.lastUnderrunCallbackStartGapMicros,
                    physicalRunSequence = output.physicalRunSequence,
                    lastUnderrunPhysicalRunSequence = output.lastUnderrunPhysicalRunSequence,
                    missingControlFrames = output.callbackMix.missingControlFrames,
                    staleControlFrames = output.callbackMix.staleControlFrames,
                    lastUnderrunWasManualArmBoundary =
                        output.lastUnderrunWasManualArmBoundary,
                    processManualArmBoundaryUnderrunCallbacks =
                        output.processManualArmBoundaryUnderrunCallbackCount,
                    processManualArmBoundaryUnderrunFrames =
                        output.processManualArmBoundaryUnderrunFrames,
                    lastSuccessfulEnqueueGeneration = output.lastUnderrunSuccessfulEnqueueGeneration,
                    lastUnderrunSuccessfulEnqueueSequence = output.lastUnderrunSuccessfulEnqueueSequence,
                    lastUnderrunSuccessfulEnqueueSnapshotCoherent =
                        output.lastUnderrunSuccessfulEnqueueSnapshotCoherent,
                    lastSuccessfulEnqueueAgeMicros =
                        output.lastUnderrunSuccessfulEnqueueAgeMicros,
                    lastSuccessfulEnqueueQueueAfterFrames =
                        output.lastUnderrunSuccessfulEnqueueQueueAfterFrames,
                    lastUnderrunMaximumSuccessfulEnqueueGapMicros =
                        output.lastUnderrunMaximumSuccessfulEnqueueGapMicros
                )
            }
        }
    }

    private companion object {
        const val TAG = "StemwisePerTestAudio"
    }
}
