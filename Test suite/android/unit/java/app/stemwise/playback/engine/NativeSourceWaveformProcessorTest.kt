package app.stemwise.playback.engine

import app.stemwise.playback.assertSourceWaveformCoverage
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

@app.stemwise.qualification.ManagedAudioTest
class NativeSourceWaveformProcessorTest {
    private fun identity(gains: List<Float> = listOf(1f, 1f), duration: Long = 10L) =
        SourceWaveformIdentity("song", "audio", duration, NativeRuntimeOwnership(3L, 5L),
            NativeRuntimeSourceMode.STEMS, gains,
            listOf(SourceWaveformSource("/original.flac", "original-digest", "original", false)) +
                gains.indices.map { lane -> SourceWaveformSource("/lane-$lane.flac", "digest-$lane",
                    "lane-$lane", lane == gains.lastIndex) })

    @Test fun movingWindowReusesMixedOverlapAndReadsOnlyUncoveredSourceFrames() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val key = identity(duration = 30_000L)
        val calls = mutableListOf<Triple<String, Long, Int>>()
        val read: suspend (SourceWaveformSource, Long, Int) -> FloatArray? = { source, first, frames ->
            calls += Triple(source.normalizedRole, first, frames)
            FloatArray(frames * 2) {
                val frame = first + it / 2
                if (source.normalizedRole == "lane-0") when {
                    frame < 480_000L -> .5f
                    frame < 600_000L -> .25f
                    frame < 720_000L -> .75f
                    else -> .125f
                } else when {
                    frame < 480_000L -> -.25f
                    frame < 720_000L -> .25f
                    else -> -.125f
                }
            }
        }
        val first = requireNotNull(processor.window(key, 10_000L, frameCount = 1_440_000L, read = read))
        assertEquals(5_000L, first.startMs)
        assertEquals(15_000L, first.endMs)
        assertEquals(List(1_000) { .25f } + List(500) { .5f } + List(500) { 1f }, first.peaks)
        calls.clear()
        val shifted = requireNotNull(processor.window(key, 12_500L, frameCount = 1_440_000L, read = read))
        assertEquals(listOf(Triple("lane-0", 720_000L, 120_000), Triple("lane-1", 720_000L, 120_000)), calls)
        val expected = List(500) { .25f } + List(500) { .5f } + List(500) { 1f } + List(500) { 0f }
        assertEquals(7_500L, shifted.startMs)
        assertEquals(17_500L, shifted.endMs)
        assertEquals(expected, shifted.peaks)
        assertNotEquals(first.peaks, shifted.peaks)
        calls.clear()
        val replacement = requireNotNull(processor.window(key.copy(ownership = NativeRuntimeOwnership(3L, 6L)),
            12_500L, frameCount = 1_440_000L, read = read))
        assertEquals(960_000, calls.sumOf { it.third })
        assertEquals(expected, replacement.peaks)
    }

    @Test fun oppositeStemSamplesCancelBeforeEnergyAndSoloUsesItsActualSource() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val read: suspend (SourceWaveformSource, Long, Int) -> FloatArray? = { source, _, frames ->
            FloatArray(frames * 2) { if (source.normalizedRole == "lane-0") .5f else -.5f }
        }
        assertEquals(listOf(0f, 0f), processor.window(identity(), 5L, frameCount = 480L, read = read)?.peaks)
        assertEquals(listOf(.5f, .5f), processor.window(identity(listOf(1f, 0f)), 5L, frameCount = 480L, read = read)?.peaks)
        assertEquals(listOf(.25f, .25f), processor.window(identity(listOf(.5f, 0f)), 5L, frameCount = 480L, read = read)?.peaks)
    }

    @Test fun oppositeStereoChannelsRetainEnergyAndChannelsHaveEqualWeight() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val actual = processor.window(identity(listOf(1f)), 0L, frameCount = 480L) { source, _, frames ->
            assertEquals("lane-0", source.normalizedRole)
            FloatArray(frames * 2) { if (it % 2 == 0) .5f else -.5f }
        }
        assertEquals(listOf(.5f, .5f), actual?.peaks)
        val mono = processor.window(identity(listOf(.5f)), 0L, frameCount = 480L) { _, _, frames ->
            FloatArray(frames * 2) { if (it % 2 == 0) 1f else 0f }
        }
        assertEquals(sqrt(.125).toFloat(), requireNotNull(mono).peaks[0], .000001f)
    }

    @Test fun exactHeaderEndRetainsPartialFinalBucketAndVeryShortSources() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        var readFrames = 0
        val result = processor.window(identity(listOf(1f), duration = 21L), 21L, frameCount = 1_001L) { _, first, frames ->
            assertTrue(first + frames <= 1_001L)
            readFrames += frames
            FloatArray(frames * 2) { if (first + it / 2 >= 960L) .75f else .25f }
        }
        assertEquals(1_001, readFrames)
        assertEquals(listOf(.25f, .25f, .25f, .25f, .75f), result?.peaks)
        assertEquals(25L, result?.endMs)
        assertEquals(listOf(.5f), processor.window(identity(listOf(1f), duration = 1L), 0L,
            frameCount = 1L) { _, _, frames -> assertEquals(1, frames); floatArrayOf(.5f, -.5f) }?.peaks)
    }

    @Test fun eachActiveRoleDecodesOneWholeBoundedWindowAndExactCacheHitsDoNotDecodeAgain() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val key = identity(duration = 20_000L)
        val calls = mutableListOf<Triple<String, Long, Int>>()
        val result = processor.window(key, 7_503L, frameCount = 960_000L) { source, first, frames ->
            calls += Triple(source.normalizedRole, first, frames)
            FloatArray(frames * 2) { if (source.normalizedRole == "lane-0") .5f else -.5f }
        }
        assertEquals(listOf(Triple("lane-0", 120_000L, 480_000), Triple("lane-1", 120_000L, 480_000)), calls)
        assertEquals(key, result?.identity)
        assertEquals(2_000, result?.peaks?.size)
        assertTrue(requireNotNull(result).peaks.all { it == 0f })
        assertSame(result, processor.window(key, 7_503L, frameCount = 960_000L) { _, _, _ ->
            error("Exact cached source window must not decode again")
        })
        val soloIdentity = key.copy(stemGains = listOf(.5f, 0f))
        val solo = processor.window(soloIdentity, 7_503L,
            frameCount = 960_000L) { source, _, frames ->
            assertEquals("lane-0", source.normalizedRole); FloatArray(frames * 2) { .5f }
        }
        assertSourceWaveformCoverage(requireNotNull(solo), soloIdentity, 2_500L, 12_500L, 2_000)
        assertTrue(solo.peaks.all { it == .25f })
    }

    @Test fun knownWindowCoverageRejectsEmptyTruncatedAndWrongOwnerResults() {
        val expectedIdentity = identity(duration = 20_000L)
        val complete = SourceWaveformWindow(expectedIdentity, 2_500L, 12_500L, 5L, List(2_000) { 0f })
        assertThrows(AssertionError::class.java) {
            assertSourceWaveformCoverage(complete.copy(peaks = emptyList()), expectedIdentity, 2_500L, 12_500L, 2_000)
        }
        assertThrows(AssertionError::class.java) {
            assertSourceWaveformCoverage(complete.copy(peaks = complete.peaks.dropLast(1)),
                expectedIdentity, 2_500L, 12_500L, 2_000)
        }
        assertThrows(AssertionError::class.java) {
            assertSourceWaveformCoverage(complete.copy(identity = expectedIdentity.copy(
                ownership = NativeRuntimeOwnership(3L, 6L))), expectedIdentity, 2_500L, 12_500L, 2_000)
        }
    }

    @Test fun unavailableOrMalformedSamplesCannotPublishOrReplaceAValidWindow() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val key = identity(listOf(1f))
        val valid = processor.window(key, 0L, frameCount = 480L) { _, _, frames -> FloatArray(frames * 2) { .25f } }
        val replacement = key.copy(ownership = NativeRuntimeOwnership(3L, 6L))
        assertNull(processor.window(replacement, 0L, frameCount = 480L) { _, _, _ -> null })
        assertNull(processor.window(replacement, 0L, frameCount = 480L) { _, _, _ -> floatArrayOf(1f) })
        assertNull(processor.window(replacement, 0L, frameCount = 480L) { _, _, frames -> FloatArray(frames * 2) { Float.NaN } })
        assertSame(valid, processor.window(key, 0L, frameCount = 480L) { _, _, _ -> error("Cached window reread") })
        assertNotNull(processor.window(replacement, 0L, frameCount = 480L) { _, _, frames -> FloatArray(frames * 2) })
        var changedExtentRead = false
        processor.window(replacement, 0L, frameCount = 479L) { _, _, frames ->
            changedExtentRead = true; FloatArray(frames * 2)
        }
        assertTrue("Exact header extent is part of cache authority", changedExtentRead)
    }

    @Test fun cancellationAfterReadDoesNotPublishOrCacheTheCancelledRequest() = runBlocking {
        val processor = NativeSourceWaveformProcessor()
        val key = identity(listOf(1f))
        val child = launch {
            val context = currentCoroutineContext()
            processor.window(key, 0L, frameCount = 480L) { _, _, frames ->
                context.cancel(); FloatArray(frames * 2) { 1f }
            }
            fail("Cancelled processing returned a waveform")
        }
        child.join()
        var reads = 0
        val actual = processor.window(key, 0L, frameCount = 480L) { _, _, frames -> reads++; FloatArray(frames * 2) { .25f } }
        assertTrue(reads > 0)
        assertEquals(listOf(.25f, .25f), actual?.peaks)
    }

    @Test fun supersededOrCancelledIoCannotEvictTheLatestCompletedWindow() = runBlocking {
        for (cancelOld in listOf(false, true)) {
            val processor = NativeSourceWaveformProcessor()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val previous = identity(listOf(1f))
            val newest = previous.copy(stemGains = listOf(.5f))
            val outgoing = async(Dispatchers.Default) {
                processor.window(previous, 0L, frameCount = 480L) { _, _, frames ->
                    entered.countDown()
                    check(release.await(3L, TimeUnit.SECONDS)) { "Waveform read was not released" }
                    FloatArray(frames * 2) { .5f }
                }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(3L, TimeUnit.SECONDS) })
                val current = processor.window(newest, 0L, frameCount = 480L) { _, _, frames -> FloatArray(frames * 2) { .5f } }
                if (cancelOld) outgoing.cancel()
                release.countDown()
                withTimeout(3_000L) { outgoing.join() }
                if (cancelOld) assertTrue(outgoing.isCancelled) else assertNotNull(outgoing.await())
                assertSame(current, processor.window(newest, 0L, frameCount = 480L) { _, _, _ ->
                    error("Outgoing completion evicted the latest waveform")
                })
            } finally {
                release.countDown()
                withTimeout(3_000L) { outgoing.cancelAndJoin() }
            }
        }
    }

    @Test fun allMutedMixIsZeroWithoutReadingIrrelevantAudio() = runBlocking {
        val result = NativeSourceWaveformProcessor().window(identity(listOf(0f)), 0L,
            frameCount = 480L) { _, _, _ -> error("Silent source must not trigger decoding") }
        assertEquals(listOf(0f, 0f), result?.peaks)
    }
}
