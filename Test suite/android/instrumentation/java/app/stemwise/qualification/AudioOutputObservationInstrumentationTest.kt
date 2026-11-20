package app.stemwise.qualification

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.playback.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class AudioOutputObservationInstrumentationTest {
    @Test fun unopenedOutputIsDistinctFromMissingObservationAfterOpenDemandOrFailure() {
        NativeAudioOutput(capacityFrames = NativeAudioBufferFormat.MIN_CAPACITY_FRAMES,
            deferPhysicalOpen = true).use { output ->
            NativeSongLaneIngressBank(256, 256).use { first ->
                NativeSongLaneIngressBank(256, 256).use { second ->
                    NativePlaybackRuntime(output, first, second).use { runtime ->
                        val cold = NativeEngineDiagnosticsSnapshot(runtime.diagnostics(), output.diagnostics())
                        assertEquals(-1, cold.output!!.xRunCount)
                        assertTrue(cold.outputHasNeverOpened())
                        assertTrue(runtime.start())
                        val prepared = NativeEngineDiagnosticsSnapshot(runtime.diagnostics(), output.diagnostics())
                        assertTrue("Logical preparation must not invent a physical opening", prepared.outputHasNeverOpened())
                        val required = listOf(
                            cold.copy(output = null),
                            cold.copy(controllerFatalQuarantined = true),
                            cold.copy(output = cold.output.copy(physicalOpenAttempted = null)),
                            cold.copy(output = cold.output.copy(physicalOpenAttempted = true)),
                            cold.copy(output = cold.output.copy(streamState = NativeAudioStreamState.OPEN)),
                            cold.copy(output = cold.output.copy(sampleRateHz = 48_000, channelCount = 2,
                                outputBufferSizeFrames = 2_048, outputBufferCapacityFrames = 2_048)),
                            cold.copy(output = cold.output.copy(recoveryRequired = true)),
                            cold.copy(output = cold.output.copy(lastErrorCode = -1)),
                            cold.copy(output = cold.output.copy(callbackFrames = 1L)),
                            cold.copy(output = cold.output.copy(physicalRunSequence = 1L)))
                        for ((index, snapshot) in required.withIndex()) {
                            assertFalse("Unknown, demanded, opened or failed output must require observation: $index",
                                snapshot.outputHasNeverOpened())
                            assertFalse("The missing observation must remain unavailable: $index",
                                (snapshot.output?.xRunCount ?: -1) >= 0)
                        }
                        assertTrue(output.start())
                        val opened = NativeEngineDiagnosticsSnapshot(runtime.diagnostics(), output.diagnostics())
                        assertFalse(opened.outputHasNeverOpened())
                        assertTrue(requireNotNull(opened.output).xRunCount >= 0)
                        assertFalse("A lost observation after real opening must fail closed",
                            opened.copy(output = opened.output.copy(xRunCount = -1)).outputHasNeverOpened())
                    }
                }
            }
        }
    }
}
