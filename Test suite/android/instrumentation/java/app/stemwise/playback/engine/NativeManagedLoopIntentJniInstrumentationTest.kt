package app.stemwise.playback.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device proof that Kotlin and production JNI share one strict managed-loop schema-v5 contract. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class NativeManagedLoopIntentJniInstrumentationTest {
    @Test
    fun schemaV5AcceptsOnlyOriginalEosAndRejectsOldMalformedOrTruncatedPayloads() {
        NativeAudioOutput(
            capacityFrames = 1_024,

            initialGeneration = 1L
        ).use { output ->
            NativeSongLaneIngressBank(256, 256).use { firstBank ->
                NativeSongLaneIngressBank(256, 256).use { secondBank ->
                    NativePlaybackRuntime(output, firstBank, secondBank).use { runtime ->
                        val backend = runtimePrivateField(runtime, "backend") as
                            NativePlaybackRuntimeBackend
                        val handle = (runtimePrivateField(runtime, "handle") as AtomicLong).get()
                        val owner = NativeRuntimeOwnership(71L, 81L)
                        val key = NativeRuntimeManagedLoopContentKey(
                            songIdentityHash = 11L,
                            originalContentHash = 12L,
                            stemPackageHash = 0L,
                            contentRevision = 13L,
                            loopRevision = 14L,
                            startTimelineFrame = 48_000L,
                            endTimelineFrameExclusive = 240_000L
                        )
                        val encoded = NativeRuntimeManagedLoopIntentCodec.encode(
                            NativeRuntimeManagedLoopIntent(
                                revision = 1L,
                                ownership = owner,
                                enabled = true,
                                cacheKey = key,
                                requiredLaneMask =
                                    NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
                                boundaryMode = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
                            )
                        )

                        assertEquals(5L, encoded[0])
                        assertEquals(15, encoded.size)
                        assertEquals(1L, encoded[14])
                        assertEquals(
                            NativeRuntimePublishStatus.ACCEPTED.ordinal,
                            backend.publishLoopCycleIntent(handle, encoded)
                        )

                        assertInvalidJniPayload {
                            backend.publishLoopCycleIntent(
                                handle,
                                encoded.clone().also { it[14] = 0L }
                            )
                        }
                        assertInvalidJniPayload {
                            backend.publishLoopCycleIntent(
                                handle,
                                encoded.clone().also { it[0] = 3L }
                            )
                        }
                        assertInvalidJniPayload {
                            backend.publishLoopCycleIntent(handle, encoded.copyOf(14))
                        }
                        assertEquals(NativeRuntimePublishStatus.ACCEPTED.ordinal,
                            backend.publishLoopCycleIntent(handle, encoded.clone().also { it[1] = 2L }))

                    }
                }
            }
        }
    }

    private fun runtimePrivateField(runtime: NativePlaybackRuntime, name: String): Any {
        val field = NativePlaybackRuntime::class.java.getDeclaredField(name)
        field.isAccessible = true
        return requireNotNull(field.get(runtime))
    }

    private fun assertInvalidJniPayload(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Production JNI must reject malformed managed-loop payload", rejected)
    }
}
