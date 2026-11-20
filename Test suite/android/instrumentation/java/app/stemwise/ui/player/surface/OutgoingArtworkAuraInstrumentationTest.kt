package app.stemwise.ui.player.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.stemwise.ui.player.nativeplayer.*
import org.junit.Assert.*
import org.junit.Test

internal class OutgoingArtworkAuraInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun lateControlAnchorMovesArtworkAuraAndHitBoundsTogether() {
        lateinit var surface: PlayerSurfaceView
        var root: android.widget.FrameLayout? = null
        lateinit var preparation: Pair<Any, kotlinx.coroutines.Job>
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val images = mutableListOf<Bitmap>()
        try {
            activityRule.scenario.onActivity { host ->
                val seed = completeOriginalLoopSnapshot()
                val snapshot = seed.copy(catalog = FullPlayerCatalogPacket(seed.catalog.revision + 1L,
                    seed.catalog.map { it.copy(artworkSource = "content://app.stemwise.artwork/anchor-${it.songId}") }),
                    transport = seed.transport.copy(playWhenReady = false),
                    timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
                val installed = qualifiedSurface(host, snapshot, RecordingSink())
                root = installed.first; surface = installed.second
                surface.updateAmbientMotionPolicy(false)
                surface.setControlBounds(48f, 1_800f, 1_152f, 2_720f)
                surface.updateRouteExpansionProgress(.999f)
                surface.updateRouteExpansionProgress(1f)
                preparation = deliver(surface, red)
            }
            awaitAuraPreparation(surface, preparation)
            activityRule.scenario.onActivity {
                fun paint(auraOnly: Boolean): Bitmap {
                    val pager = field(surface, "pagerDrawing").get(surface)
                    val method = pager.javaClass.getDeclaredMethod("drawPagerArtworkLayer", Canvas::class.java,
                        FullPlayerCatalogPacket::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                    return Bitmap.createBitmap(1_200, 2_800, Bitmap.Config.ARGB_8888).also {
                        images += it; method.invoke(pager, Canvas(it),
                            (field(surface, "visuals").get(surface) as FullPlayerSnapshot).catalog, auraOnly)
                    }
                }
                fun centerY(image: Bitmap): Double {
                    var weight = 0L; var moment = 0L
                    val row = IntArray(image.width)
                    for (y in 0 until image.height) {
                        image.getPixels(row, 0, image.width, 0, y, image.width, 1)
                        for (pixel in row) {
                            val alpha = Color.alpha(pixel)
                            weight += alpha; moment += alpha * y.toLong()
                        }
                    }
                    assertTrue("Artwork layer must contain actual pixels", weight > 0L)
                    return moment.toDouble() / weight
                }
                field(surface, "lastFrameNanos").setLong(surface, 1_000_000_000L)
                surface.artworkAuraMotion.freezeAt(1_000_000_000L, forceFrame = true)
                val oldBounds = checkNotNull(surface.semanticsGeometry()).artwork
                val oldArtworkCenter = centerY(paint(false))
                val oldAuraCenter = centerY(paint(true))
                surface.setControlBounds(48f, 2_000f, 1_152f, 2_720f)
                val newBounds = checkNotNull(surface.semanticsGeometry()).artwork
                val artworkDelta = centerY(paint(false)) - oldArtworkCenter
                assertTrue("The late anchor must genuinely move the painted artwork", artworkDelta > 20.0)
                assertEquals("Aura detached from its cover", artworkDelta, centerY(paint(true)) - oldAuraCenter, 1.0)
                assertEquals("The retained cover and its geometry must move in the same frame",
                    artworkDelta, (newBounds.top - oldBounds.top).toDouble(), 1.0)
            }
        } finally {
            activityRule.scenario.onActivity { root?.removeAllViews() }
            images.forEach(Bitmap::recycle)
            red.recycle()
        }
    }

    @Test fun rapidNonadjacentSelectionsAndRepeatWrapPaintOnlyTheCurrentOpaqueCover() {
        lateinit var surface: PlayerSurfaceView
        var root: android.widget.FrameLayout? = null
        val covers = listOf(Color.RED, Color.BLUE, Color.GREEN).map { color ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        try {
            activityRule.scenario.onActivity { host ->
                val seed = completeOriginalLoopSnapshot()
                val entry = seed.catalog.songAt(1)
                val songs = (0..4).map { index -> entry.copy(queueKey = 100L + index,
                    songId = "instant-$index", mediaRevision = "instant-media-$index", title = "Song $index",
                    artworkSource = "content://app.stemwise.artwork/instant-$index") }
                val catalog = FullPlayerCatalogPacket(30, songs)
                val queue = FullPlayerQueuePacket(30, songs.map { it.queueKey }.toLongArray())
                fun snapshot(index: Int, generation: Long) = seed.copy(
                    revision = 100L + generation,
                    owner = seed.owner.copy(generation = generation, songId = songs[index].songId,
                        mediaRevision = songs[index].mediaRevision),
                    visualSelectionSongId = songs[index].songId, catalog = catalog, queue = queue,
                    timeline = seed.timeline.copy(revision = 100L + generation, waveformRevision = 100L + generation),
                    selection = FullPlayerSelectionPacket(100L + generation, index),
                    waveform = FullPlayerWaveformPacket(100L + generation, songs[index].mediaRevision,
                        seed.waveform.copyPeaks(), seed.waveform.sections()),
                    transport = seed.transport.copy(playWhenReady = false),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
                val initial = snapshot(0, 1)
                val sink = RecordingSink()
                val installed = qualifiedSurface(host, initial, sink)
                root = installed.first; surface = installed.second
                surface.updateAmbientMotionPolicy(false)
                deliver(surface, covers[0])
                val pager = field(surface, "pagerDrawing").get(surface)
                val paint = pager.javaClass.getDeclaredMethod("drawPagerArtworkLayer", Canvas::class.java,
                    FullPlayerCatalogPacket::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                val frame = PlayerSurfaceView::class.java.getDeclaredMethod("doFrame", java.lang.Long.TYPE)
                    .apply { isAccessible = true }
                val image = Bitmap.createBitmap(1_200, 2_800, Bitmap.Config.ARGB_8888)
                try {
                    // Jump, interrupt with another jump, then Repeat All wrap. No intermediate owner
                    // may reappear or expose the background, even before the next vsync.
                    val start = System.nanoTime()
                    for ((step, target) in listOf(4, 2, 0).withIndex()) {
                        val next = snapshot(target, step + 2L)
                        assertNull(FullPlayerSnapshotValidator.validate(next))
                        assertEquals(FullPlayerAdoptionResult.APPLIED_NEW_OWNER,
                            surface.bind(next, sink, PlayerSurfacePositionProvider { 10_000L },
                                1_200, 2_800, host.resources.displayMetrics.density,
                                PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                        val cover = covers[(step + 1) % covers.size]
                        deliver(surface, cover)
                        for (elapsed in listOf(0L, 16L, 80L)) {
                            frame.invoke(surface, start + (step * 90L + elapsed) * 1_000_000L)
                            image.eraseColor(Color.TRANSPARENT)
                            paint.invoke(pager, Canvas(image), catalog, false)
                            val bounds = checkNotNull(surface.semanticsGeometry()).artwork
                            assertEquals("Retired/transparent cover after jump to $target at ${elapsed}ms",
                                cover.getPixel(16, 16), image.getPixel(
                                    ((bounds.left + bounds.right) * .5f).toInt(),
                                    ((bounds.top + bounds.bottom) * .5f).toInt()))
                        }
                    }
                } finally { image.recycle() }
            }
        } finally {
            activityRule.scenario.onActivity { root?.removeAllViews() }
            covers.forEach(Bitmap::recycle)
        }
    }

    @Test fun nextAndPreviousInvalidateTheMountedPagerBeforeAnAuthoritativeRebind() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        lateinit var surface: PlayerSurfaceView
        lateinit var artwork: android.view.View
        lateinit var identity: android.view.View
        var root: android.widget.FrameLayout? = null
        val navigation = mutableListOf<PlayerSurfaceTransportCommand>()
        val covers = listOf(Color.RED, Color.BLUE, Color.GREEN).map { color ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        }
        fun awaitDraw() {
            val drawn = java.util.concurrent.CountDownLatch(1)
            lateinit var listener: android.view.ViewTreeObserver.OnDrawListener
            instrumentation.runOnMainSync {
                val view = checkNotNull(root)
                listener = android.view.ViewTreeObserver.OnDrawListener { view.post { drawn.countDown() } }
                view.viewTreeObserver.addOnDrawListener(listener)
                view.invalidate()
            }
            try {
                assertTrue("Mounted pager did not complete a real draw",
                    drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                instrumentation.runOnMainSync { root?.viewTreeObserver?.removeOnDrawListener(listener) }
            }
        }
        try {
            activityRule.scenario.onActivity { host ->
                val seed = completeOriginalLoopSnapshot()
                val snapshot = seed.copy(
                    catalog = FullPlayerCatalogPacket(seed.catalog.revision + 1L,
                        seed.catalog.mapIndexed { index, song -> song.copy(title = "Song $index", artist = "Fixture",
                            artworkSource = "content://app.stemwise.artwork/dispatch-$index") }),
                    transport = seed.transport.copy(playWhenReady = false, canGoNext = true),
                    timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
                var selected = snapshot.selection.currentIndex
                val sink = object : FullPlayerActionSink by RecordingSink() {
                    override fun onNavigate(generation: Long, direction: PlayerSurfaceTransportCommand): Int {
                        navigation += direction
                        selected += if (direction == PlayerSurfaceTransportCommand.NEXT) 1 else -1
                        return selected
                    }
                }
                val installed = qualifiedSurface(host, snapshot, sink)
                root = installed.first; surface = installed.second
                surface.updateAmbientMotionPolicy(false)
                artwork = field(surface, "pagerArtworkRegion").get(surface) as android.view.View
                identity = field(surface, "pagerIdentityRegion").get(surface) as android.view.View
                @Suppress("UNCHECKED_CAST")
                val keys = field(surface, "artworkDisplayKeys").get(surface) as Array<PlayerSurfaceArtworkDisplayKey?>
                val setBitmap = PlayerSurfaceView::class.java.getDeclaredMethod("setArtworkBitmap", Integer.TYPE,
                    PlayerSurfaceArtworkDisplayKey::class.java, Bitmap::class.java).apply { isAccessible = true }
                for (slot in 0..2) {
                    setBitmap.invoke(surface, slot, requireNotNull(keys[slot]), covers[slot])
                    // Warm residents isolate selection publication from asynchronous cover arrival.
                    (field(surface, "artworkLoadProgress").get(surface) as FloatArray)[slot] = 1f
                    (field(surface, "artworkLoadStartedAtNanos").get(surface) as LongArray)[slot] = 0L
                    (field(surface, "artworkLoadTransitionActive").get(surface) as BooleanArray)[slot] = false
                }
            }
            awaitDraw()
            for (command in listOf(PlayerSurfaceTransportCommand.NEXT, PlayerSurfaceTransportCommand.PREVIOUS)) {
                instrumentation.runOnMainSync {
                    assertTrue("The fixture must exercise retained hardware display lists", surface.isHardwareAccelerated)
                    assertFalse("Artwork baseline must be clean before input", artwork.isDirty)
                    assertFalse("Identity baseline must be clean before input", identity.isDirty)
                    val geometry = checkNotNull(surface.semanticsGeometry())
                    tap(surface, if (command == PlayerSurfaceTransportCommand.NEXT) geometry.next else geometry.previous)
                    // A forced software draw would repaint even clean children and conceal this regression.
                    assertTrue("$command left the artwork display list clean", artwork.isDirty)
                    assertTrue("$command left the identity display list clean", identity.isDirty)
                }
                awaitDraw()
            }
            assertEquals(listOf(PlayerSurfaceTransportCommand.NEXT, PlayerSurfaceTransportCommand.PREVIOUS), navigation)
        } finally {
            activityRule.scenario.onActivity { root?.removeAllViews() }
            covers.forEach(Bitmap::recycle)
        }
    }

    private fun awaitAuraPreparation(surface: PlayerSurfaceView, preparation: Pair<Any, kotlinx.coroutines.Job>) {
        val published = java.util.concurrent.CountDownLatch(1)
        val completion = preparation.second.invokeOnCompletion { published.countDown() }
        try {
            assertTrue("Current aura preparation did not complete its publication",
                published.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { completion.dispose() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cache = field(surface, "artworkAuraCache").get(surface)
            fun cacheField(name: String) = cache.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(cache) as Array<*>
            val request = requireNotNull(cacheField("requests")[1])
            assertSame("Another raster request replaced the delivered cover", preparation.first, request)
            val identity = request.javaClass.getDeclaredField("identity").apply { isAccessible = true }.get(request)
            val revision = request.javaClass.getDeclaredField("revision").apply { isAccessible = true }.getLong(request)
            val keys = field(surface, "artworkDisplayKeys").get(surface) as Array<*>
            val revisions = field(surface, "artworkAuraPaletteRevision").get(surface) as LongArray
            assertEquals("Raster request no longer belongs to the visible artwork", keys[1], identity)
            assertEquals("Raster request no longer belongs to the visible palette", revisions[1], revision)
            assertNotNull("Current aura preparation completed without published textures", cacheField("ready")[1])
        }
    }

    @Test fun pausedDegradedAuraResumesAfterBriefVisibilityLossWithoutFurtherInput() {
        val resumed = java.util.concurrent.CountDownLatch(1)
        var root: android.widget.FrameLayout? = null
        var surface: PlayerSurfaceView? = null
        var listener: android.view.ViewTreeObserver.OnDrawListener? = null
        activityRule.scenario.onActivity { host ->
            val seed = completeOriginalLoopSnapshot()
            val snapshot = seed.copy(transport = seed.transport.copy(playWhenReady = false),
                timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                    activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
            val installed = qualifiedSurface(host, snapshot, RecordingSink())
            root = installed.first
            val view = installed.second
            surface = view
            view.updateAmbientMotionPolicy(true)
            val now = System.nanoTime()
            repeat(8) { view.artworkAuraMotion.update(now, now - 100_000_000L, true, true, true) }
            assertFalse(view.artworkAuraMotion.motionEnabled)
            view.visibility = android.view.View.INVISIBLE
            assertFalse("Hidden aura retained a frame callback", view.frameScheduledForQualification())
            listener = android.view.ViewTreeObserver.OnDrawListener {
                if (view.artworkAuraMotion.motionEnabled) resumed.countDown()
            }.also { view.viewTreeObserver.addOnDrawListener(it) }
            view.visibility = android.view.View.VISIBLE
        }
        try {
            assertTrue("Paused aura never resumed from its remaining degradation deadline",
                resumed.await(12, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            activityRule.scenario.onActivity {
                listener?.let { callback -> surface?.viewTreeObserver?.removeOnDrawListener(callback) }
                root?.removeAllViews()
            }
        }
    }

    private fun deliver(surface: PlayerSurfaceView, bitmap: Bitmap): Pair<Any, kotlinx.coroutines.Job> {
        @Suppress("UNCHECKED_CAST")
        val keys = field(surface, "artworkDisplayKeys").get(surface) as Array<PlayerSurfaceArtworkDisplayKey?>
        val key = requireNotNull(keys[1])
        PlayerSurfaceView::class.java.getDeclaredMethod("setArtworkBitmap", Integer.TYPE,
            PlayerSurfaceArtworkDisplayKey::class.java, Bitmap::class.java).apply { isAccessible = true }
            .invoke(surface, 1, key, bitmap)
        // The supplied fixture represents an already-visible decoded cover, not an arrival animation.
        (field(surface, "artworkLoadProgress").get(surface) as FloatArray)[1] = 1f
        (field(surface, "artworkLoadStartedAtNanos").get(surface) as LongArray)[1] = 0L
        (field(surface, "artworkLoadTransitionActive").get(surface) as BooleanArray)[1] = false
        val cache = field(surface, "artworkAuraCache").get(surface)
        val requests = cache.javaClass.getDeclaredField("requests").apply { isAccessible = true }.get(cache) as Array<*>
        val jobs = cache.javaClass.getDeclaredField("jobs").apply { isAccessible = true }.get(cache) as Array<*>
        return requireNotNull(requests[1]) to (requireNotNull(jobs[1]) as kotlinx.coroutines.Job)
    }

    private fun field(surface: PlayerSurfaceView, name: String) =
        PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }
}
