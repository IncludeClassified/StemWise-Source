package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeLaneIngressInstrumentationTest {
    @Test
    fun productionJniAcceptsExactEosAndRejectsPostBoundaryPcm() {
        NativeSongLaneIngressBank(64, 32).use { bank ->
            val sink = bank.configureLane(0, 48_000, 10_000)
            val pcm = floatStereoBlock(frames = 5)
            val block = NativePcmBlock().reset(
                data = pcm,
                format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT),
                presentationTimeUs = 0,
                seekGeneration = 0,
                firstTimelineFrame = 0
            )

            assertEquals(pcm.remaining(), sink.offer(block).acceptedBytes)
            assertEquals(5, bank.readableSourceFrames(0))
            assertEquals(
                NativeLaneEndOfStreamStatus.BOUNDARY_MISMATCH,
                sink.markEndOfStream(0, 4)
            )
            assertEquals(
                NativeLaneEndOfStreamStatus.ACCEPTED,
                sink.markEndOfStream(0, 5)
            )
            assertEquals(
                NativeLaneEndOfStreamStatus.ALREADY_MARKED,
                sink.markEndOfStream(0, 5)
            )
            val failure = runCatching { sink.offer(block) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
    }

    @Test
    fun productionJniOwnsTwoIndependentSevenLaneBanks() {
        NativeSongLaneIngressBank(64, 32).use { active ->
            NativeSongLaneIngressBank(64, 32).use { candidate ->
                repeat(NativeSongLaneIngressBank.LANE_COUNT) { lane ->
                    active.configureLane(lane, 48_000, 20_000L + lane)
                    candidate.configureLane(lane, 48_000, 30_000L + lane)
                }
                assertTrue(active.nativeHandleForRenderRuntime() != 0L)
                assertTrue(candidate.nativeHandleForRenderRuntime() != 0L)
                assertTrue(
                    active.nativeHandleForRenderRuntime() !=
                        candidate.nativeHandleForRenderRuntime()
                )
            }
        }
    }

    private fun floatStereoBlock(frames: Int): ByteBuffer {
        val writable = ByteBuffer.allocateDirect(frames * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        repeat(frames) { frame ->
            writable.putFloat(frame * 2 * Float.SIZE_BYTES, frame / 10f)
            writable.putFloat((frame * 2 + 1) * Float.SIZE_BYTES, -frame / 10f)
        }
        return writable.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply {
            position(0)
            limit(capacity())
        }
    }
}
