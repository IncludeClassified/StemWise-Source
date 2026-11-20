package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolate intentional fault counters from the healthy-output test process. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class AudioTrackPauseFailureInstrumentationTest {
    @Test
    fun throwingPlatformPauseStillRetiresTheRealNativeConsumerBeforeDeletion() {
        val sinkReleased = AtomicBoolean(false)
        val pauseAttempts = AtomicInteger(0)
        var finalObservation: NativeAudioDiagnostics? = null
        var baseline: NativeAudioDiagnostics? = null
        lateinit var output: NativeAudioOutput
        output = NativeAudioOutput(16_384, 1L,
            sinkFactory = {
                val actual = AndroidAudioTrackSink()
                object : NativeAudioTrackFeeder.Sink by actual {
                    override fun pause() {
                        pauseAttempts.incrementAndGet()
                        throw IllegalStateException("Injected platform pause failure")
                    }
                    override fun close() { actual.close(); sinkReleased.set(true) }
                }
            }, finalObservation = { result ->
                val observed = result.getOrThrow()
                assertFalse("The native handle must remain live through its final observation", output.isClosed())
                assertTrue("The actual sink must retire before native deletion", sinkReleased.get())
                assertTrue("Pause fault was not exercised: backend=${observed.backend}, state=${observed.streamState}",
                    pauseAttempts.get() > 0)
                assertEquals(-1, observed.xRunCount)
                assertEquals(requireNotNull(baseline).processIncompleteStreamObservationCount + 1L,
                    observed.processIncompleteStreamObservationCount)
                assertTrue("The injected physical failure must remain a failure", observed.recoveryRequired)
                assertFalse(observed.effectiveRenderPlaying)
                finalObservation = observed
            })
        var primaryFailure: Throwable? = null
        try {
            baseline = output.diagnostics()
            NativeSongLaneIngressBank(8_192, 256).use { first ->
                NativeSongLaneIngressBank(256, 256).use { second ->
                    val owner = NativeRuntimeOwnership(8_905L, 2L)
                    val lane = first.configureLane(0, 48_000, owner.generation)
                    val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
                    val samples = audioTrackEngineSamples(4_096)
                    repeat(16) { block ->
                        val pcm = ByteBuffer.allocateDirect(256 * 8).order(ByteOrder.nativeOrder())
                        repeat(256) { frame ->
                            val offset = (block * 256 + frame) * 2
                            pcm.putFloat(samples[offset]).putFloat(samples[offset + 1])
                        }
                        pcm.flip()
                        assertEquals(256 * 8, lane.offer(NativePcmBlock().reset(pcm.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), format,
                            block * 256L * 1_000_000L / 48_000L, 0L, block * 256L)).acceptedBytes)
                    }
                    assertEquals(NativeLaneEndOfStreamStatus.ACCEPTED, lane.markEndOfStream(0L, 4_096L))
                    val bank = NativeRuntimeBankIntent(1L, owner, songIdentityHash = 8_905L,
                        originalContentHash = 8_906L, cacheFrameUpperBound = 4_096L)
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    NativePlaybackRuntime(output, first, second,
                        NativeRuntimeConfiguration(currentSongCacheDirectory = context.cacheDir.absolutePath)).use { runtime ->
                        val command = NativeRuntimeCommandSnapshot(1L, owner.generation,
                            listOf(bank, NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST,
                            playing = true, controls = NativeRuntimeControls(1L, sourceIntentRevision = 1L, stemIntentRevision = 1L))
                        assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
                        assertTrue(runtime.start())
                        try {
                            await("real source reaches the actual AudioTrack before injected cleanup") {
                                runtime.diagnostics().presentedPosition?.ownership == owner
                            }
                            assertHealthy(output.diagnostics(), requireNotNull(baseline))
                        } finally { runtime.requestStop(); runtime.join() }
                    }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { output.close() } catch (failure: Throwable) {
                primaryFailure?.addSuppressed(failure) ?: throw failure
            }
        }
        assertTrue(output.isClosed())
        assertTrue(sinkReleased.get())
        assertTrue(pauseAttempts.get() > 0)
        assertNotNull("The process survived real native deletion after the injected pause failure", finalObservation)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }

}
