package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeRuntimeRetirementInstrumentationTest {
    @Test fun capturedLoopGestureReturnsStoppedAfterRuntimeReplacementWithoutAJavaException() {
        NativeAudioOutput(deferPhysicalOpen = true,
            sinkFactory = { error("Retirement publication must not open physical output") }).use { output ->
            NativeSongLaneIngressBank().use { first ->
                NativeSongLaneIngressBank().use { second ->
                    val captured = CountDownLatch(1)
                    val resume = CountDownLatch(1)
                    val worker = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "LoopRetirementPublication").apply { isDaemon = true }
                    }
                    val backend = object : NativePlaybackRuntimeBackend by NativePlaybackRuntimeJniBackend {
                        override fun publishDirectLoopPolicy(handle: Long, revision: Long, selectionToken: Long,
                            generation: Long, enabled: Boolean, aTimelineFrame: Long,
                            bTimelineFrameExclusive: Long, capturedBTimelineFrameExclusive: Long,
                            inputEventAtMonotonicMicros: Long, heldBGestureId: Long,
                            boundaryArmedAtPublication: Boolean, boundaryCrossedAtPublication: Boolean,
                            captureReferenceTimelineFrame: Long): Int {
                            // Gate after the real Kotlin handle capture, before native registry lookup.
                            captured.countDown()
                            check(resume.await(5L, TimeUnit.SECONDS))
                            return NativePlaybackRuntimeJniBackend.publishDirectLoopPolicy(handle, revision,
                                selectionToken, generation, enabled, aTimelineFrame, bTimelineFrameExclusive,
                                capturedBTimelineFrameExclusive, inputEventAtMonotonicMicros, heldBGestureId,
                                boundaryArmedAtPublication, boundaryCrossedAtPublication, captureReferenceTimelineFrame)
                        }
                    }
                    val retired = NativePlaybackRuntime(output.borrowNativeHandleForRuntime(),
                        first.nativeHandleForRenderRuntime(), second.nativeHandleForRenderRuntime(),
                        NativeRuntimeConfiguration(), backend)
                    val publication = worker.submit<NativeRuntimePublishStatus> {
                        retired.publishDirectLoopPolicy(1L, 2L, 3L, true, 0L, 48_000L,
                            0L, 0L, 0L, false, false)
                    }
                    try {
                        assertTrue(retired.start())
                        assertTrue("Gesture never captured the old handle", captured.await(5L, TimeUnit.SECONDS))
                        // Same ownership boundary as route recovery: joined runtime is replaced and retired.
                        retired.requestStop()
                        retired.join()
                        assertEquals(NativeRuntimeStatus.STOPPED, retired.diagnostics().status)
                        NativePlaybackRuntime(output, first, second).use { replacement ->
                            assertTrue(replacement.inheritCompletedSongCache(retired))
                            retired.close()
                            resume.countDown()
                            assertEquals(NativeRuntimePublishStatus.STOPPED, publication.get(5L, TimeUnit.SECONDS))
                            assertEquals(NativeRuntimePublishStatus.INVALID_SNAPSHOT,
                                replacement.publishDirectLoopPolicy(2L, 2L, 3L, true, 0L, 48_000L,
                                    0L, 0L, 0L, false, false))
                        }
                        assertThrows(IllegalStateException::class.java) {
                            NativePlaybackRuntimeJniBackend.publishDirectLoopPolicy(Long.MAX_VALUE,
                                1L, 2L, 3L, true, 0L, 48_000L, 0L, 0L, 0L, false, false, -1L)
                        }
                    } finally {
                        resume.countDown()
                        publication.cancel(true)
                        worker.shutdownNow()
                        assertTrue("Publication worker failed to retire", worker.awaitTermination(5L, TimeUnit.SECONDS))
                        retired.close()
                    }
                }
            }
        }
    }
}
