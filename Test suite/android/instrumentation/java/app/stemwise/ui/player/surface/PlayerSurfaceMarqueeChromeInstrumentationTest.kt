package app.stemwise.ui.player.surface

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogPacket
import app.stemwise.ui.player.nativeplayer.FullPlayerDetailState
import app.stemwise.ui.player.nativeplayer.FullPlayerSnapshotValidator
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Real compositor readback: a software draw or accessibility-tree query can hide this defect. */
internal class PlayerSurfaceMarqueeChromeInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun pausedMarqueePreservesStaticChromeInsideAndroidView() = assertMarqueeChrome(interop = true)

    @Test fun pausedMarqueePreservesStaticChromeInsideFrameLayout() = assertMarqueeChrome(interop = false)

    private fun assertMarqueeChrome(interop: Boolean) {
        assertTrue("This regression requires system animations so the real marquee runs", ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val probe = MarqueeFrames()
        var surface: PlayerSurfaceView? = null
        var baseline: Bitmap? = null
        try {
            activityRule.scenario.onActivity { host ->
                val seed = completeOriginalLoopSnapshot()
                val snapshot = seed.copy(
                    catalog = FullPlayerCatalogPacket(seed.catalog.revision + 1L, seed.catalog.map {
                        it.copy(title = "نور الهدى عبد الرحمن — Live orchestral recording in Cairo / Đặng Thị Ngọc Hân (Deluxe Anniversary Edition)",
                            artist = "Aleksandra Wiśniewska-Kowalczyk & Đặng Thị Ngọc Hân", artworkSource = null)
                    }),
                    transport = seed.transport.copy(playWhenReady = false, canGoNext = true),
                    timeline = seed.timeline.copy(positionMs = 0L, loopStartMs = -1L, loopEndMs = -1L),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None)
                )
                check(FullPlayerSnapshotValidator.validate(snapshot) == null)
                fun createSurface(): PlayerSurfaceView = PlayerSurfaceView(host).also { view ->
                    surface = view
                    view.viewTreeObserver.addOnDrawListener(probe.listener(view))
                    view.doOnLayout {
                        // Native child relayout needs the completed first View.layout boundary.
                        view.post {
                            view.bind(snapshot, RecordingSink(), PlayerSurfacePositionProvider { 0L },
                                view.width, view.height, host.resources.displayMetrics.density,
                                PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                            settleToolGeometry(view, snapshot, view.width, view.height, host.resources.displayMetrics.density)
                            view.updateAmbientMotionPolicy(false)
                            probe.configured = true
                        }
                    }
                }
                if (interop) {
                    host.setContent {
                        StemwiseTheme {
                            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                                AndroidView(factory = { createSurface() }, modifier = Modifier.width(320.dp).fillMaxHeight())
                            }
                        }
                    }
                } else {
                    val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }
                    host.setContentView(root)
                    root.addView(createSurface(), FrameLayout.LayoutParams(
                        (320f * host.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            probe.awaitFrameAt(0L)
            val view = checkNotNull(surface)
            var startedAt = 0L
            instrumentation.runOnMainSync {
                startedAt = PlayerSurfaceView::class.java.getDeclaredField("identityMarqueeStartedAtMs")
                    .apply { isAccessible = true }.getLong(view)
                assertTrue("The first real draw must have started the marquee clock", startedAt > 0L)
            }
            probe.awaitFrameAt(startedAt + 300L)
            instrumentation.waitForIdleSync()
            lateinit var crops: List<Pair<String, Rect>>
            lateinit var identity: Rect
            instrumentation.runOnMainSync {
                assertTrue("Fixture must exercise hardware rendering", view.isHardwareAccelerated)
                val geometry = checkNotNull(view.semanticsGeometry())
                val location = IntArray(2).also(view::getLocationOnScreen)
                fun rect(bounds: PlayerSurfaceBounds): Rect = Rect(
                    kotlin.math.floor(bounds.left).toInt() + location[0],
                    kotlin.math.floor(bounds.top).toInt() + location[1],
                    kotlin.math.ceil(bounds.right).toInt() + location[0],
                    kotlin.math.ceil(bounds.bottom).toInt() + location[1])
                val play = geometry.playPause
                val halfGlyph = 12f * view.resources.displayMetrics.density
                val playGlyph = PlayerSurfaceBounds(centerX(play) - halfGlyph, centerY(play) - halfGlyph,
                    centerX(play) + halfGlyph, centerY(play) + halfGlyph)
                crops = listOf("collapse" to geometry.collapse, "song info" to geometry.info,
                    "Original source" to geometry.original, "seeker" to geometry.seeker,
                    "play glyph" to playGlyph, "Loop tool" to geometry.loopTool).map { (name, bounds) -> name to rect(bounds) }
                identity = rect(geometry.identity)
            }
            baseline = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            fun qualifiedBaseline(image: Bitmap): List<Int> {
                assertTrue("Baseline was captured after the marquee's opening pause",
                    SystemClock.uptimeMillis() - startedAt < 900L)
                return crops.map { (name, rect) ->
                    val ink = brightPixels(image, rect)
                    assertTrue("$name needs independently visible baseline pixels ($ink in $rect, image=${image.width}x${image.height}, interop=$interop)", ink > 8)
                    if (name == "collapse") {
                        assertTrue("Baseline is launch/background pixels rather than a sparse collapse arrow ($ink/${rect.width() * rect.height()})",
                            ink * 4L < rect.width().toLong() * rect.height())
                    }
                    ink
                }
            }
            val firstInk = qualifiedBaseline(checkNotNull(baseline))
            // A committed frame may still precede display presentation. Qualify a second stable
            // compositor readback while the real title is still in its opening pause.
            probe.awaitFrameAt(maxOf(startedAt + 450L, SystemClock.uptimeMillis() + 32L))
            val second = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val baselineInk: List<Int>
            try {
                baselineInk = qualifiedBaseline(second)
                crops.forEachIndexed { index, (name, _) ->
                    assertTrue("$name baseline did not stabilize across presented frames (${firstInk[index]}/${baselineInk[index]})",
                        kotlin.math.abs(firstInk[index] - baselineInk[index]) * 20L <= firstInk[index])
                }
            } catch (failure: Throwable) {
                second.recycle()
                throw failure
            }
            baseline?.recycle()
            baseline = second
            val initial = second
            var identityMoved = false
            // No input, semantic-tree query, forced draw or layout mutation after the baseline.
            for (elapsed in listOf(1_250L, 2_000L, 3_000L)) {
                probe.awaitFrameAt(startedAt + elapsed)
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    crops.forEachIndexed { index, (name, rect) ->
                        val retained = brightPixels(image, rect)
                        assertTrue("$name disappeared during native marquee at ${elapsed}ms ($retained/${baselineInk[index]} bright pixels, interop=$interop)",
                            retained * 5L >= baselineInk[index] * 4L)
                    }
                    identityMoved = identityMoved || differentPixels(initial, image, identity) > 8
                } finally { image.recycle() }
            }
            assertTrue("The sampled real frames did not actually move the identity", identityMoved)
        } finally {
            baseline?.recycle()
            activityRule.scenario.onActivity { host ->
                surface?.let { view -> probe.drawListener?.let(view.viewTreeObserver::removeOnDrawListener) }
                host.setContentView(FrameLayout(host))
            }
        }
    }

    private class MarqueeFrames {
        var configured = false
        var drawListener: ViewTreeObserver.OnDrawListener? = null
        private val deadline = AtomicLong(Long.MAX_VALUE)
        private val completed = Semaphore(0)

        fun listener(view: PlayerSurfaceView) = ViewTreeObserver.OnDrawListener {
            val target = deadline.get()
            if (configured && SystemClock.uptimeMillis() >= target && deadline.compareAndSet(target, Long.MAX_VALUE)) {
                // Unlike View.post, this waits for HWUI to render and submit the frame.
                view.viewTreeObserver.registerFrameCommitCallback { completed.release() }
            }
        }.also { drawListener = it }

        fun awaitFrameAt(uptimeMs: Long) {
            deadline.set(uptimeMs)
            assertTrue("Native marquee stopped producing frames before deadline $uptimeMs",
                completed.tryAcquire(5, TimeUnit.SECONDS))
        }
    }

    private fun brightPixels(image: Bitmap, rect: Rect): Int {
        check(rect.left >= 0 && rect.top >= 0 && rect.right <= image.width && rect.bottom <= image.height)
        var count = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val pixel = image.getPixel(x, y)
            if ((Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel)) / 10 >= 128) count++
        }
        return count
    }

    private fun differentPixels(first: Bitmap, second: Bitmap, rect: Rect): Int {
        var count = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) count++
        }
        return count
    }
}
