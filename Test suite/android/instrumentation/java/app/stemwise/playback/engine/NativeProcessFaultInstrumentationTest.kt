package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.qa.requirePreservedProcessFaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Intentionally advances process-cumulative fault evidence; never belongs to a health suite. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeProcessFaultInstrumentationTest {
    @Test
    fun processFaultEvidenceSurvivesRuntimeReplacement() {
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("class") == EXACT_METHOD &&
            arguments.getString("stemwiseIntentionalNativeFault") == "true" &&
            arguments.getString("stemwiseDisposableTarget") == "true" &&
            arguments.getString("listener").isNullOrBlank() &&
            arguments.getString("filter").isNullOrBlank()) {
            "Intentional native faults require their sole exact method in a separate disposable instrumentation invocation"
        }
        NativeAudioOutput(
            capacityFrames = 1_024,

            initialGeneration = 1L
        ).use { output ->
            lateinit var first: NativeRuntimeDiagnostics
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        val baseline = runtime.diagnostics()
                        // Advance the real output behind the stopped runtime, then submit an older
                        // unload epoch. Native flush rejects it and records one controlled hard fault.
                        val deadline = System.nanoTime() + 2_000_000_000L
                        while (!output.tryFlushToGeneration(3L)) {
                            check(System.nanoTime() < deadline) { "AudioTrack did not acknowledge the controlled flush" }
                            Thread.sleep(1)
                        }
                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(
                            NativeRuntimeCommandSnapshot(revision = 1L, outputGeneration = 2L,
                                banks = listOf(NativeRuntimeBankIntent.unassigned(1L),
                                    NativeRuntimeBankIntent.unassigned(1L)),
                                selectedBank = null, playing = false,
                                controls = NativeRuntimeControls(revision = 1L))))
                        assertTrue(runtime.start())
                        try {
                            first = awaitDiagnostics(runtime) {
                                it.processOutputFlushFailures == baseline.processOutputFlushFailures + 1L
                            }
                            assertTrue(first.processOutputFlushFailures > 0L)
                        } finally {
                            runtime.requestStop()
                            runtime.join()
                        }
                    }
                }
            }
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { replacement ->
                        val second = replacement.diagnostics()
                        assertEquals(
                            first.processConstructionCount + 1L,
                            second.processConstructionCount
                        )
                        assertEquals(first.processLiveInstanceCount, second.processLiveInstanceCount)
                        requirePreservedProcessFaults(first.processOutputInvalidRenderResults,
                            first.processOutputFlushFailures, second.processOutputInvalidRenderResults,
                            second.processOutputFlushFailures)
                    }
                }
            }
        }
    }

    private fun awaitDiagnostics(runtime: NativePlaybackRuntime,
        predicate: (NativeRuntimeDiagnostics) -> Boolean): NativeRuntimeDiagnostics {
        val deadline = System.nanoTime() + 3_000_000_000L
        var latest = runtime.diagnostics()
        while (!predicate(latest) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
            latest = runtime.diagnostics()
        }
        assertTrue("Native runtime did not reach the expected state: $latest", predicate(latest))
        return latest
    }

    companion object {
        const val EXACT_METHOD = "app.stemwise.playback.engine.NativeProcessFaultInstrumentationTest#processFaultEvidenceSurvivesRuntimeReplacement"
    }
}
