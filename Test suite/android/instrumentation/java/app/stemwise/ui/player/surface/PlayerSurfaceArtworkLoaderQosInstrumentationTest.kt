package app.stemwise.ui.player.surface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled end-to-end proof of current/offscreen artwork scheduling and stale completion. */
@RunWith(AndroidJUnit4::class)
class PlayerSurfaceArtworkLoaderQosInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun newestCurrentPreemptsOldCurrentWhileStillOwnedCoverRestartsOffscreen() {
        val controlled = ControlledImageLoader(context)
        val loader = PlayerSurfaceArtworkLoader(context, imageLoader = controlled)
        val delivered = CopyOnWriteArrayList<PlayerSurfaceArtworkResult>()
        val a = request("song-a", File(context.cacheDir, "qos-a.jpg").absolutePath)
        val b = request("song-b", File(context.cacheDir, "qos-b.jpg").absolutePath)
        val c = request("song-c", File(context.cacheDir, "qos-c.jpg").absolutePath)
        var testFailure: Throwable? = null

        try {
            instrumentation.runOnMainSync {
                loader.request(PlayerSurfaceArtworkLoader.SLOT_CURRENT, a, delivered::add)
            }
            val firstA = controlled.awaitCall("qos-a.jpg", occurrence = 0)

            // Keep A physically owned by the lower page. Reusing the same key must not duplicate
            // its still-current decode before a newer current owner arrives.
            instrumentation.runOnMainSync {
                loader.request(PlayerSurfaceArtworkLoader.SLOT_LOWER, a, delivered::add)
            }
            assertEquals(1, controlled.callsFor("qos-a.jpg").size)

            instrumentation.runOnMainSync {
                repeat(398) { index ->
                    val suffix = index.toString().padStart(3, '0')
                    loader.request(
                        PlayerSurfaceArtworkLoader.SLOT_CURRENT,
                        request(
                            songId = "pressure-$suffix",
                            source = File(context.cacheDir, "qos-pressure-$suffix.jpg").absolutePath
                        ),
                        delivered::add
                    )
                }
                loader.request(PlayerSurfaceArtworkLoader.SLOT_CURRENT, b, delivered::add)
            }
            assertTrue(
                "The superseded current decode did not observe cancellation",
                firstA.cancelled.await(1, TimeUnit.SECONDS)
            )
            val restartedA = controlled.awaitCall("qos-a.jpg", occurrence = 1)
            assertEquals(
                "A second current decode bypassed the one-wide urgent permit",
                0,
                controlled.callsFor("qos-b.jpg").size
            )
            assertTrue(
                "Superseded rapid-current jobs reached Coil instead of conflating to B",
                controlled.callsContaining("qos-pressure-").isEmpty()
            )

            // The controlled first call deliberately finishes after cancellation, reproducing a
            // native decode that races out of runInterruptible. It must neither publish nor block
            // A's offscreen restart; B may wait only for this single predecessor to release the
            // one-wide current permit.
            assertEquals(0, delivered.size)
            assertNotSame(firstA.request.decoderDispatcher, restartedA.request.decoderDispatcher)
            firstA.complete(Color.RED)
            val currentB = controlled.awaitCall("qos-b.jpg", occurrence = 0)
            assertNotSame(restartedA.request.decoderDispatcher, currentB.request.decoderDispatcher)
            assertTrue(
                "The controlled late A call did not return normally",
                firstA.returnedNormally.await(1, TimeUnit.SECONDS)
            )
            assertTrue(
                "The controlled late A call did not exit",
                firstA.finished.await(1, TimeUnit.SECONDS)
            )
            drainMainThread()
            assertEquals("A cancelled current task published stale artwork", 0, delivered.size)
            assertTrue(controlled.callsContaining("qos-pressure-").isEmpty())

            restartedA.complete(Color.GREEN)
            currentB.complete(Color.BLUE)
            assertTrue(restartedA.returnedNormally.await(1, TimeUnit.SECONDS))
            assertTrue(currentB.returnedNormally.await(1, TimeUnit.SECONDS))
            assertTrue(restartedA.finished.await(1, TimeUnit.SECONDS))
            assertTrue(currentB.finished.await(1, TimeUnit.SECONDS))
            drainMainThread()
            assertEquals(
                setOf(
                    PlayerSurfaceArtworkLoader.SLOT_LOWER to "song-a",
                    PlayerSurfaceArtworkLoader.SLOT_CURRENT to "song-b"
                ),
                delivered.map { result -> result.slot to result.request.owner.songId }.toSet()
            )
            assertEquals(2, delivered.size)
            assertEquals(1, delivered.count { it.request.owner.songId == "song-a" })
            assertEquals(1, delivered.count { it.request.owner.songId == "song-b" })
            assertEquals(
                Color.GREEN,
                delivered.single { it.request.owner.songId == "song-a" }.bitmap.getPixel(0, 0)
            )
            assertEquals(
                Color.BLUE,
                delivered.single { it.request.owner.songId == "song-b" }.bitmap.getPixel(0, 0)
            )

            // Exercise the opposite direction explicitly: a same-key hidden C decode must cancel
            // and restart in the urgent lane when C becomes current, without waiting for itself.
            instrumentation.runOnMainSync {
                loader.request(PlayerSurfaceArtworkLoader.SLOT_LOWER, c, delivered::add)
            }
            val offscreenC = controlled.awaitCall("qos-c.jpg", occurrence = 0)
            instrumentation.runOnMainSync {
                loader.request(PlayerSurfaceArtworkLoader.SLOT_CURRENT, c, delivered::add)
                loader.cancelSlot(PlayerSurfaceArtworkLoader.SLOT_LOWER)
            }
            assertTrue(
                "Same-key C offscreen decode was not cancelled for promotion",
                offscreenC.cancelled.await(1, TimeUnit.SECONDS)
            )
            assertTrue(offscreenC.finished.await(1, TimeUnit.SECONDS))
            val currentC = controlled.awaitCall("qos-c.jpg", occurrence = 1)
            assertNotSame(offscreenC.request.decoderDispatcher, currentC.request.decoderDispatcher)
            currentC.complete(Color.YELLOW)
            assertTrue(currentC.returnedNormally.await(1, TimeUnit.SECONDS))
            assertTrue(currentC.finished.await(1, TimeUnit.SECONDS))
            drainMainThread()
            assertEquals(3, delivered.size)
            assertEquals(
                Color.YELLOW,
                delivered.single { it.request.owner.songId == "song-c" }.bitmap.getPixel(0, 0)
            )
        } catch (caught: Throwable) {
            testFailure = caught
            throw caught
        } finally {
            val failures = mutableListOf<Throwable>()
            runCatching {
                instrumentation.runOnMainSync { loader.detach() }
            }.exceptionOrNull()?.let(failures::add)
            runCatching(controlled::shutdown).exceptionOrNull()?.let(failures::add)
            runCatching(::drainMainThread).exceptionOrNull()?.let(failures::add)
            if (failures.isNotEmpty()) {
                val cleanupFailure = AssertionError("Artwork QoS test cleanup failed")
                failures.forEach(cleanupFailure::addSuppressed)
                if (testFailure == null) throw cleanupFailure
                testFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun request(songId: String, source: String): PlayerSurfaceArtworkRequest =
        PlayerSurfaceArtworkRequest(
            owner = PlayerSurfaceOwnerKey(
                generation = 7L,
                songId = songId,
                mediaRevision = "media-$songId",
                audioRevision = "audio-$songId",
                stemRevision = "stems-$songId"
            ),
            source = source,
            widthPx = 1_536,
            heightPx = 1_536
        )

    private fun drainMainThread() {
        instrumentation.runOnMainSync { }
        instrumentation.waitForIdleSync()
    }

    private class ControlledImageLoader(
        private val context: Context,
        private val delegate: ImageLoader = ImageLoader.Builder(context).build()
    ) : ImageLoader by delegate {
        private val callSerial = AtomicInteger()
        private val calls = CopyOnWriteArrayList<ControlledCall>()
        private val shutdown = AtomicBoolean()

        override suspend fun execute(request: ImageRequest): ImageResult {
            val source = request.data.toString()
            val ignoreCancellation = source.endsWith("qos-a.jpg") &&
                calls.none { call -> call.source.endsWith("qos-a.jpg") }
            val call = ControlledCall(
                serial = callSerial.incrementAndGet(),
                source = source,
                request = request,
                context = context,
                ignoreCancellation = ignoreCancellation
            )
            calls += call
            return call.awaitResult()
        }

        fun callsFor(suffix: String): List<ControlledCall> =
            calls.filter { call -> call.source.endsWith(suffix) }.sortedBy(ControlledCall::serial)

        fun callsContaining(fragment: String): List<ControlledCall> =
            calls.filter { call -> fragment in call.source }.sortedBy(ControlledCall::serial)

        fun awaitCall(suffix: String, occurrence: Int): ControlledCall {
            val deadline = SystemClock.elapsedRealtime() + 2_000L
            do {
                callsFor(suffix).getOrNull(occurrence)?.let { return it }
                SystemClock.sleep(5L)
            } while (SystemClock.elapsedRealtime() <= deadline)
            throw AssertionError("No controlled artwork call $suffix occurrence=$occurrence; calls=$calls")
        }

        override fun shutdown() {
            if (!shutdown.compareAndSet(false, true)) return
            val failures = mutableListOf<Throwable>()
            calls.forEach { call -> call.completeIfPending(Color.TRANSPARENT) }
            calls.forEach { call ->
                runCatching {
                    check(call.finished.await(1, TimeUnit.SECONDS)) {
                        "Controlled artwork call did not finish during cleanup: $call"
                    }
                }.exceptionOrNull()?.let(failures::add)
            }
            runCatching(delegate::shutdown).exceptionOrNull()?.let(failures::add)
            calls.forEach(ControlledCall::recycleBitmap)
            if (failures.isNotEmpty()) {
                val failure = AssertionError("Controlled artwork-loader cleanup failed")
                failures.forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private class ControlledCall(
        val serial: Int,
        val source: String,
        val request: ImageRequest,
        private val context: Context,
        private val ignoreCancellation: Boolean
    ) {
        private val result = CompletableDeferred<ImageResult>()
        private val producedBitmap = AtomicReference<Bitmap?>()
        val cancelled = CountDownLatch(1)
        val returnedNormally = CountDownLatch(1)
        val finished = CountDownLatch(1)

        suspend fun awaitResult(): ImageResult {
            return try {
                val value = try {
                    result.await()
                } catch (cancelledError: kotlinx.coroutines.CancellationException) {
                    cancelled.countDown()
                    if (!ignoreCancellation) throw cancelledError
                    withContext(NonCancellable) { result.await() }
                }
                returnedNormally.countDown()
                value
            } finally {
                finished.countDown()
            }
        }

        fun complete(color: Int) {
            assertTrue("Controlled call $serial completed twice", completeResult(color))
        }

        fun completeIfPending(color: Int) {
            if (!result.isCompleted) completeResult(color)
        }

        private fun completeResult(color: Int): Boolean {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
            }
            val completed = result.complete(
                SuccessResult(
                    drawable = BitmapDrawable(context.resources, bitmap),
                    request = request,
                    dataSource = DataSource.MEMORY,
                    memoryCacheKey = null,
                    diskCacheKey = request.diskCacheKey,
                    isSampled = false,
                    isPlaceholderCached = false
                )
            )
            if (completed) producedBitmap.set(bitmap) else bitmap.recycle()
            return completed
        }

        fun recycleBitmap() {
            producedBitmap.getAndSet(null)?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        override fun toString(): String = "ControlledCall($serial,$source)"
    }
}
