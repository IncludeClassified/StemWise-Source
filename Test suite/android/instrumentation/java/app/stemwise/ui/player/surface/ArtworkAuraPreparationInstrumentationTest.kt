package app.stemwise.ui.player.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.ui.ArtworkAuraPaletteCache
import app.stemwise.ui.MissingArtworkAuraPalette
import app.stemwise.ui.artworkAuraRasterDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext

internal class ArtworkAuraPreparationInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun failedHardwarePaletteRemainsReplaceableByNativeSoftwarePixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "failed-hardware-${UUID.randomUUID()}"
        val software = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val hardware = checkNotNull(software.copy(Bitmap.Config.HARDWARE, false))
        try {
            activityRule.scenario.onActivity {
                assertEquals(MissingArtworkAuraPalette,
                    ArtworkAuraPaletteCache.deriveAndPut(key, BitmapDrawable(context.resources, hardware)))
                assertNull("Failed extraction poisoned the exact cover identity", ArtworkAuraPaletteCache.get(key))
                val actual = ArtworkAuraPaletteCache.deriveAndPut(key, software)
                assertTrue(actual.dominant.green > actual.dominant.red)
                assertEquals(actual, ArtworkAuraPaletteCache.get(key))
            }
        } finally { hardware.recycle(); software.recycle() }
    }

    @Test fun replacementAndDetachDiscardPendingRastersWhileDrawingKeepsReadyPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstReady = CountDownLatch(1)
        val bothReady = CountDownLatch(2)
        val publications = AtomicInteger()
        lateinit var cache: PlayerArtworkAuraCache
        instrumentation.runOnMainSync {
            cache = PlayerArtworkAuraCache(1f) {
                publications.incrementAndGet(); firstReady.countDown(); bothReady.countDown()
            }
            cache.prepare(1, "green", 1, intArrayOf(Color.GREEN, Color.GREEN, Color.GREEN))
        }
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        var baseline: Bitmap? = null
        var barrierSubmitted = false
        try {
            assertTrue("Initial aura was not prepared", firstReady.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { baseline = render(cache) }
            artworkAuraRasterDispatcher.dispatch(EmptyCoroutineContext, Runnable {
                workerEntered.countDown()
                try { check(releaseWorker.await(5, TimeUnit.SECONDS)) }
                finally { workerFinished.countDown() }
            })
            barrierSubmitted = true
            try {
                assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    repeat(100) { cache.prepare(1, "red-$it", it.toLong(), intArrayOf(Color.RED, Color.RED, Color.RED)) }
                    val pending = render(cache)
                    try { assertTrue("Drawing lost its previous ready texture", checkNotNull(baseline).sameAs(pending)) }
                    finally { pending.recycle() }
                    cache.recycle()
                    cache.prepare(1, "blue-after-reattach", 1, intArrayOf(Color.BLUE, Color.BLUE, Color.BLUE))
                }
            } finally { releaseWorker.countDown() }
            assertTrue("Replacement aura was not prepared", bothReady.await(5, TimeUnit.SECONDS))
            assertEquals("A superseded/detached raster became public", 2, publications.get())
            instrumentation.runOnMainSync {
                val actual = render(cache)
                try {
                    var red = 0L; var blue = 0L
                    for (y in 0 until actual.height) for (x in 0 until actual.width) {
                        val pixel = actual.getPixel(x, y)
                        red += Color.red(pixel); blue += Color.blue(pixel)
                    }
                    assertTrue("Fresh slot did not use its actual blue palette", blue > red)
                } finally { actual.recycle() }
            }
        } finally {
            releaseWorker.countDown()
            if (barrierSubmitted) assertTrue("Blocked worker did not retire", workerFinished.await(5, TimeUnit.SECONDS))
            baseline?.recycle()
            instrumentation.runOnMainSync { cache.recycle() }
        }
    }

    @Test fun retainedAuraDrawSeparatesOpacityFromLuminanceWithoutPreparingMoreTextures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val publications = AtomicInteger()
        lateinit var cache: PlayerArtworkAuraCache
        instrumentation.runOnMainSync {
            cache = PlayerArtworkAuraCache(1f) { publications.incrementAndGet(); ready.countDown() }
            cache.prepare(1, "independent-channels", 1, intArrayOf(Color.rgb(20, 80, 120),
                Color.rgb(20, 80, 120), Color.rgb(20, 80, 120)))
        }
        try {
            assertTrue("Aura texture was not published", ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val textures = PlayerArtworkAuraCache::class.java.getDeclaredField("ready")
                    .apply { isAccessible = true }.get(cache) as Array<*>
                val retained = textures[1]
                val images = mutableListOf<Bitmap>()
                fun draw(opacity: Float, luminance: Float) = Bitmap.createBitmap(256, 256,
                    Bitmap.Config.ARGB_8888).also {
                    images += it
                    cache.drawPrecomposedArtworkAuraBitmaps(Canvas(it), 1, 128f, 128f,
                        112f, 112f, 12f, opacity, luminance, 1f)
                }
                fun alpha(image: Bitmap): Long = (0 until image.height).sumOf { y ->
                    (0 until image.width).sumOf { x -> Color.alpha(image.getPixel(x, y)).toLong() } }
                fun rgb(image: Bitmap): Long = (0 until image.height).sumOf { y ->
                    (0 until image.width).sumOf { x -> val pixel = image.getPixel(x, y)
                        (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)).toLong() } }
                try {
                    val baseline = draw(0f, 0f)
                    val dim = draw(0f, -.1f)
                    val bright = draw(0f, .1f)
                    assertTrue("Fixture did not draw real halo pixels", alpha(baseline) > 0L)
                    assertEquals("Luminance changed opacity", alpha(dim), alpha(bright))
                    assertTrue("The real retained draw ignored luminance", rgb(dim) < rgb(baseline) && rgb(baseline) < rgb(bright))
                    val lowerOpacity = draw(-.1f, 0f)
                    assertTrue("Opacity was not independent of luminance", alpha(lowerOpacity) < alpha(baseline))
                    val reset = draw(0f, 0f)
                    assertTrue("Luminance leaked into the next draw", baseline.sameAs(reset))
                    repeat(20) { cache.drawPrecomposedArtworkAuraBitmaps(Canvas(reset), 1,
                        128f, 128f, 112f, 112f, 12f, 0f, 0f, 1f) }
                    assertSame("Live draws replaced ready raster storage", retained, textures[1])
                    assertEquals("Live draws triggered raster work", 1, publications.get())
                } finally { images.forEach(Bitmap::recycle) }
            }
        } finally { instrumentation.runOnMainSync { cache.recycle() } }
    }

    private fun render(cache: PlayerArtworkAuraCache): Bitmap =
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also {
            cache.drawPrecomposedArtworkAuraBitmaps(Canvas(it), 1, 128f, 128f, 112f, 112f,
                12f, 0f, 0f, 1f)
        }
}
