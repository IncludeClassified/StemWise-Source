package app.stemwise.ui.player.surface

import app.stemwise.ui.player.nativeplayer.FullPlayerCatalogSong

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.data.StemAssetEntity
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.playback.PlaybackContentFixture
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.requireDisposablePlaybackTarget
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.ui.PlayerScreen
import app.stemwise.ui.PlayerMotionPolicy
import app.stemwise.ui.interpolatePortraitPlayerLayout
import app.stemwise.ui.portraitPlayerLayout
import app.stemwise.ui.portraitPlayerToolMotionFrame
import app.stemwise.ui.portraitPlayerControlTopDp
import app.stemwise.ui.portraitPlayerControlStackHeightPx
import app.stemwise.ui.sourcePartIconRole
import app.stemwise.ui.design.StemwiseSeekerDesign
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.player.nativeplayer.*
import app.stemwise.ui.theme.StemwiseTheme
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Connected acceptance binds, draws, and drives the one real native production owner. */
internal class NativePlayerRenderingInstrumentationTest : NativePlayerSurfaceFixture() {
    private var releaseRenderingFixture: (() -> Unit)? = null

    @After
    fun releaseRenderingFixtureAfterTest() {
        val release = releaseRenderingFixture
        releaseRenderingFixture = null
        release?.invoke()
    }

    @Test
    fun identityTightensTowardTimelineWithoutMovingArtworkAndClampsAtLargeTextToolEndpoint() {
        activityRule.scenario.onActivity { host ->
            val density = host.resources.displayMetrics.density
            val width = (490f * density).toInt()
            val height = (978f * density).toInt()
            for ((scale, detail) in listOf(.8f to 0f, 2f to 296f)) {
                val seed = completeOriginalLoopSnapshot()
                val snapshot = if (detail > 0f) seed else seed.copy(
                    timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
                val loop = if (detail > 0f) 1f else 0f
                val frame = portraitPlayerToolMotionFrame(490f, 978f, density, detail, 0f,
                    loopClearanceProgress = loop, fontScale = scale)
                val layout = frame.layout
                val route = app.stemwise.ui.portraitPlayerLayout(490f, 978f, PlaybackSourceMode.ORIGINAL,
                    panelExpanded = false, fontScale = scale)
                val surface = PlayerSurfaceView(host)
                host.setContentView(surface)
                surface.updateRouteBaseMetrics(1f, 1f, route.headerHeightDp * density,
                    route.headerGapDp * density, route.identityHeightDp * density, route.identityGapDp * density,
                    route.compactChrome, route.listSpacingDp * density, scale,
                    route.seekerClockTopClearanceDp * density, route.seekerTrackHeightDp * density,
                    route.transportHeightDp * density, route.transportPlaySizeDp * density)
                surface.bind(snapshot, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                    width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                surface.measure(exactly(width), exactly(height)); surface.layout(0, 0, width, height)
                val top = portraitPlayerControlTopDp(layout, false) * density
                assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = snapshot.owner.generation, selectionEpoch = 1L,
                    selectedTool = snapshot.toolDetail.selectedTool, durationMs = 0,
                    expandedArtworkSizePx = layout.artworkSizeDp * density, mixerCardHeightPx = 0f, mixerSpacingPx = 0f,
                    listSpacingPx = layout.listSpacingDp * density, seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                    transportHeightPx = layout.transportHeightDp * density,
                    controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density,
                        top + portraitPlayerControlStackHeightPx(frame, density, false, loop)),
                    detailViewportHeightPx = frame.detailHeightPx.toFloat())))
                val geometry = requireNotNull(surface.semanticsGeometry())
                val anchored = PlayerSurfaceView::class.java.getDeclaredMethod("expandedVisualProfile")
                    .apply { isAccessible = true }.invoke(surface) as PlayerSurfaceExpandedVisualProfile
                assertEquals("Artwork moved with identity", anchored.artworkTopDp * density, geometry.artwork.top, .01f)
                assertEquals("Timeline moved with identity", top + layout.seekerClockTopClearanceDp * density,
                    geometry.seeker.top, .01f)
                val displacement = geometry.identity.top - geometry.artwork.bottom - 10f * density
                if (detail == 0f) {
                    assertEquals(24f * density, displacement, .01f)
                    assertEquals("Ordinary closed artwork anchor changed", 107.5f * density,
                        geometry.artwork.top, .01f)
                }
                else assertTrue(displacement >= -.01f && displacement <= 24f * density + .01f)
                assertTrue("Identity crossed its timeline clearance", geometry.identity.bottom <= top - 16f * density + .01f)
                assertTrue("Identity crossed the artwork", geometry.identity.top >= geometry.artwork.bottom + 2f * density - .01f)
                assertTrue("Artwork crossed source controls at scale=$scale detail=$detail: art=${geometry.artwork}, source=${geometry.original}, layout=$layout", geometry.artwork.top >= geometry.original.bottom + 2f * density - .01f)
                val drawing = PlayerSurfaceView::class.java.getDeclaredField("pagerDrawing").apply { isAccessible = true }.get(surface)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    drawing.javaClass.getDeclaredMethod("drawPagerIdentity", Canvas::class.java,
                        FullPlayerCatalogPacket::class.java).apply { isAccessible = true }
                        .invoke(drawing, Canvas(bitmap), snapshot.catalog)
                    for (region in listOf(geometry.title, geometry.artist)) {
                        var ink = 0
                        for (y in region.top.toInt() until region.bottom.toInt())
                            for (x in region.left.toInt() until region.right.toInt())
                                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) ink++
                        assertTrue("Identity text did not paint inside its shifted semantic bounds", ink > 10)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test
    fun largeTextMixerLabelsStayInsideTheirRegionForTopAndPartiallyScrolledRows() {
        activityRule.scenario.onActivity { host ->
            val snapshot = completeStemsMixerSnapshot()
            val (root, surface) = qualifiedSurface(host, snapshot, RecordingSink())
            val density = host.resources.displayMetrics.density
            fun field(name: String) = PlayerSurfaceView::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(surface)
            surface.updateRouteBaseMetrics(1f, 0f, 60f * density, 18f, 72f, 18f,
                false, 12f, 2f, 12f * density, 120f * density, 96f, 68f)
            assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                generation = snapshot.owner.generation, selectionEpoch = 1L, selectedTool = PlayerSurfaceTool.NONE,
                durationMs = 0, expandedArtworkSizePx = 180f * density, mixerCardHeightPx = 48f * density,
                mixerSpacingPx = 2f * density, listSpacingPx = 12f, seekerTrackHeightPx = 120f * density,
                transportHeightPx = 96f, controlBounds = PlayerSurfaceBounds(48f, 760f, 1152f, 2720f),
                detailViewportHeightPx = 0f)))
            val region = field("mixerRegion") as View
            val drawing = checkNotNull(field("mixerDrawing"))
            val paint = drawing.javaClass.getDeclaredMethod("drawMixerValueLabels", Canvas::class.java,
                Float::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            try {
                for (offset in listOf(0f, 12f * density)) {
                    surface.scrollMixerBy(snapshot.owner.generation, offset)
                    val volume = checkNotNull(surface.semanticsGeometry()).mixerLaneAt(0).volume
                    down(surface, volume)
                    val now = SystemClock.uptimeMillis()
                    dispatch(surface, now, now + 32L, MotionEvent.ACTION_MOVE, centerX(volume) + 32f * density, centerY(volume))
                    // Settle the existing held envelope without an asynchronous frame harness.
                    ((field("mixerValueLabels") as Array<*>)[0] as NativeSelectedStateEnvelope).sample(true, 0L, true)
                    val label = Bitmap.createBitmap(1200, 2800, Bitmap.Config.ARGB_8888)
                    try {
                        paint.invoke(drawing, Canvas(label), 1f, snapshot.sourceMixer.mixer.laneCount, false)
                        val pixels = IntArray(label.width * label.height)
                        label.getPixels(pixels, 0, label.width, 0, 0, label.width, label.height)
                        val top = pixels.indexOfFirst { android.graphics.Color.alpha(it) != 0 } / label.width
                        val bottom = pixels.indexOfLast { android.graphics.Color.alpha(it) != 0 } / label.width
                        assertTrue("Held label has no actual pixels", pixels.any { android.graphics.Color.alpha(it) != 0 })
                        assertTrue("Label clipped at scroll=$offset: $top..$bottom outside ${region.top}..${region.bottom}",
                            top >= region.top && bottom < region.bottom)
                    } finally { label.recycle(); cancel(surface, volume) }
                }
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun unmountedSelectionAndSemanticFeedbackUseTheMountedOwner() {
        activityRule.scenario.onActivity { host ->
            val seed = completeOriginalLoopSnapshot()
            var accepted = false
            var dispatches = 0
            val recording = RecordingSink()
            val actions = object : FullPlayerActionSink by recording {
                override fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean {
                    dispatches++
                    return accepted
                }
            }
            val bridge = PlayerSurfaceHostBridge()
            val (root, surface) = qualifiedSurface(host, seed, actions)
            fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
                .apply { isAccessible = true }.get(owner)
            val loader = requireNotNull(field(surface, "artworkLoader"))
            val priority = requireNotNull(field(loader, "priorityGate"))
            val bloom = field(surface, "buttonBloom") as NativePressBloomState
            val frame = NativePressBloomFrame()
            val generation = seed.owner.generation
            val semantics = app.stemwise.ui.OwnedPlayerSemanticsSink(generation, actions, bridge,
                app.stemwise.ui.PlayerQueueProjection(emptyList(), emptyList()),
                androidx.compose.runtime.mutableStateOf(seed.owner.songId),
                androidx.compose.runtime.mutableLongStateOf(0L), 120_000L, emptyList())
            try {
                // Latest unmounted intent survives until a real bound View accepts it.
                assertTrue(bridge.prioritizeSelectionArtwork("first", "first-media", "content://fixture/art/first"))
                assertTrue(bridge.prioritizeSelectionArtwork("latest", "latest-media", "content://fixture/art/latest"))
                assertFalse(bridge.applyPendingSelectionArtwork())
                bridge.attach(surface)
                assertTrue(bridge.applyPendingSelectionArtwork())
                val key = requireNotNull(field(priority, "key"))
                assertEquals("latest-media", field(key, "mediaRevision"))
                assertTrue(field(key, "physicalSource").toString().endsWith("/latest"))
                assertFalse("An applied target must not replay", bridge.applyPendingSelectionArtwork())
                bridge.detach(surface)
                bridge.prioritizeSelectionArtwork("cancelled", "cancelled-media", "content://fixture/art/cancelled")
                bridge.restoreSelectionArtworkPriority()
                bridge.attach(surface)
                assertFalse("Cancelled unmounted intent survived", bridge.applyPendingSelectionArtwork())

                bloom.reset()
                assertFalse(semantics.onTransport(generation, PlayerSurfaceTransportCommand.PLAY_PAUSE))
                bloom.sample(SystemClock.uptimeMillis(), frame)
                assertFalse("Rejected action painted feedback", frame.visible)
                assertEquals(1, dispatches)
                accepted = true
                assertTrue(semantics.onTransport(generation, PlayerSurfaceTransportCommand.PLAY_PAUSE))
                bloom.sample(SystemClock.uptimeMillis(), frame)
                assertTrue("Accepted semantic action never reached retained feedback", frame.visible)
                assertEquals("Feedback dispatched the action twice", 2, dispatches)
                bloom.reset()
                assertFalse(semantics.onTransport(generation - 1L, PlayerSurfaceTransportCommand.PLAY_PAUSE))
                assertEquals(2, dispatches)
                assertFalse(bridge.pulseSemanticFeedback(generation - 1L,
                    PlayerSurfaceSemanticFeedbackTarget.Transport(PlayerSurfaceTransportCommand.PLAY_PAUSE)))
                val disabled = seed.copy(revision = seed.revision + 1L,
                    transport = seed.transport.copy(revision = seed.transport.revision + 1L, canGoNext = false))
                surface.bind(disabled, actions, PlayerSurfacePositionProvider { 0L }, 1_200, 2_800,
                    host.resources.displayMetrics.density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                assertFalse(bridge.pulseSemanticFeedback(generation,
                    PlayerSurfaceSemanticFeedbackTarget.Transport(PlayerSurfaceTransportCommand.NEXT)))
                down(surface, requireNotNull(surface.semanticsGeometry()).playPause)
                assertFalse("Feedback replaced a live pointer owner", bridge.pulseSemanticFeedback(generation,
                    PlayerSurfaceSemanticFeedbackTarget.Transport(PlayerSurfaceTransportCommand.PLAY_PAUSE)))
                cancel(surface, requireNotNull(surface.semanticsGeometry()).playPause)
                assertEquals(2, dispatches)
                bridge.detach(surface)
                assertTrue("A feedback detach race cannot undo an already accepted action",
                    semantics.onTransport(generation, PlayerSurfaceTransportCommand.PLAY_PAUSE))
                assertEquals(3, dispatches)
                bloom.sample(SystemClock.uptimeMillis(), frame)
                assertFalse(frame.visible)
            } finally { bridge.detach(surface); root.removeAllViews() }
        }
    }

    @Test
    fun stationaryPausedScrubDoesNotRetainAFrameOwner() {
        activityRule.scenario.onActivity { host ->
            val seed = completeOriginalLoopSnapshot()
            val snapshot = seed.copy(transport = seed.transport.copy(playWhenReady = false),
                timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                    activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
            val (root, surface) = qualifiedSurface(host, snapshot, RecordingSink())
            val frame = PlayerSurfaceView::class.java.getDeclaredMethod("doFrame", java.lang.Long.TYPE).apply { isAccessible = true }
            val callback = PlayerSurfaceView::class.java.getDeclaredField("frameCallback").apply { isAccessible = true }
                .get(surface) as Choreographer.FrameCallback
            val seeker = requireNotNull(surface.semanticsGeometry()).seeker
            try {
                surface.updateAmbientMotionPolicy(false)
                down(surface, seeker)
                val start = System.nanoTime()
                for (elapsed in listOf(0L, 1_000_000_000L, 2_000_000_000L)) {
                    Choreographer.getInstance().removeFrameCallback(callback)
                    frame.invoke(surface, start + elapsed)
                }
                assertFalse("A settled paused pointer kept requesting VSYNC", surface.frameScheduledForQualification())
                cancel(surface, seeker)
            } finally { Choreographer.getInstance().removeFrameCallback(callback); root.removeAllViews() }
        }
    }

    @Test
    fun rendererRetainsTheExactImmutablePayloadAcrossTransportFineAndSourceUpdates() {
        activityRule.scenario.onActivity { host ->
            val seed = completeStemsMixerSnapshot()
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, seed, sink)
            val density = host.resources.displayMetrics.density
            val visualField = PlayerSurfaceView::class.java.getDeclaredField("visuals").apply { isAccessible = true }
            val stateField = PlayerSurfaceView::class.java.getDeclaredField("fullPlayerState").apply { isAccessible = true }
            val geometryField = PlayerSurfaceView::class.java.getDeclaredField("waveformGeometry").apply { isAccessible = true }
            fun retained() = visualField.get(surface) as FullPlayerSnapshot
            fun bind(snapshot: FullPlayerSnapshot) = surface.bind(snapshot, sink,
                PlayerSurfacePositionProvider { 1_255L }, 1_200, 2_800, density,
                PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            try {
                assertSame(seed, retained())
                val transport = seed.copy(revision = seed.revision + 10L,
                    transport = seed.transport.copy(revision = seed.transport.revision + 1L,
                        shuffleEnabled = !seed.transport.shuffleEnabled))
                assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE, bind(transport))
                assertSame(seed, retained())
                assertSame(transport, (stateField.get(surface) as FullPlayerStateStore).snapshot)
                val fine = transport.copy(revision = transport.revision + 10L,
                    fineWindow = FullPlayerFineWindowPacket(transport.revision + 1L,
                        seed.owner.mediaRevision, seed.sourceMixer.source.revision,
                        seed.sourceMixer.mixer.revision, 1_250L, 5L, floatArrayOf(.2f, .5f, .8f, .4f)))
                assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE, bind(fine))
                assertSame(fine, retained())
                assertSame(seed.catalog, retained().catalog)
                assertSame(seed.waveform, retained().waveform)
                assertSame(fine.fineWindow, retained().fineWindow)
                val geometry = geometryField.get(surface) as PlayerSurfaceWaveformGeometry
                assertEquals(.5f, geometry.amplitude(retained(), 1_255L, fine.timeline.durationMs), .0001f)
                assertEquals(.62f, geometry.amplitude(retained(), 1_257L, fine.timeline.durationMs), .0001f)
                val source = FullPlayerSourceMixerPacket(fine.revision + 1L,
                    fine.sourceMixer.source, fine.sourceMixer.mixer,
                    fine.sourceMixer.lanes().mapIndexed { index, lane ->
                        if (index == 0) lane.copy(label = "Updated lead") else lane
                    })
                val topology = fine.copy(revision = fine.revision + 10L, sourceMixer = source)
                assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE, bind(topology))
                assertSame(topology, retained())
                assertSame(source, retained().sourceMixer)
                assertSame(seed.catalog, retained().catalog)
                assertSame(seed.waveform, retained().waveform)
                assertSame(fine.fineWindow, retained().fineWindow)
                assertEquals("Updated lead", retained().sourceMixer.laneAt(0).label)
                val newer = topology.copy(revision = topology.revision + 10L,
                    owner = topology.owner.copy(generation = topology.owner.generation + 1L))
                assertEquals(FullPlayerAdoptionResult.APPLIED_NEW_OWNER, bind(newer))
                val accepted = retained()
                assertFalse(bind(topology) == FullPlayerAdoptionResult.APPLIED_UPDATE)
                assertSame(accepted, retained())
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun missingArtworkDrawsItsStableGlyphAtMiniAndFullSizes() {
        activityRule.scenario.onActivity { host ->
            val surface = PlayerSurfaceView(host)
            surface.updateRouteBaseMetrics(1f, 1f, 60f, 0f, 72f, 0f, false, 12f, 1f, 12f, 100f, 96f, 68f)
            val pager = PlayerSurfaceView::class.java.getDeclaredField("pagerDrawing")
                .apply { isAccessible = true }.get(surface)
            val draw = pager.javaClass.getDeclaredMethod(
                "drawArtwork", Canvas::class.java, FullPlayerCatalogSong::class.java,
                Integer.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE,
                java.lang.Float.TYPE, java.lang.Float.TYPE
            ).apply { isAccessible = true }
            val item = FullPlayerCatalogSong(1L, "missing-art", "missing-1", "Title", "Artist", null, 0xffa78bfa.toInt())
            for (size in listOf(48, 256)) {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                try {
                    draw.invoke(pager, Canvas(bitmap), item, 1, 0f, 0f, size.toFloat(), size.toFloat())
                    var bright = 0
                    for (y in size / 4 until size * 3 / 4) for (x in size / 4 until size * 3 / 4) {
                        val color = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(color) > 120 && android.graphics.Color.green(color) > 120) bright++
                    }
                    assertTrue("Stable missing-artwork glyph is invisible at $size", bright > 4)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test
    fun pausedAmbientOwnerSurvivesConsecutiveFramesInsideTheSameCadenceBucket() {
        activityRule.scenario.onActivity { host ->
            val seed = completeOriginalLoopSnapshot()
            val snapshot = seed.copy(transport = seed.transport.copy(playWhenReady = false),
                timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                    activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
            val (root, surface) = qualifiedSurface(host, snapshot, RecordingSink())
            val frame = PlayerSurfaceView::class.java.getDeclaredMethod("doFrame", java.lang.Long.TYPE).apply { isAccessible = true }
            val callback = PlayerSurfaceView::class.java.getDeclaredField("frameCallback").apply { isAccessible = true }
                .get(surface) as Choreographer.FrameCallback
            val bucket = PlayerArtworkAuraMotionOwner::class.java.getDeclaredField("lastFrameBucket").apply { isAccessible = true }
            try {
                surface.updateAmbientMotionPolicy(true)
                val buckets = mutableSetOf<Long>()
                val start = System.nanoTime()
                repeat(16) { index ->
                    Choreographer.getInstance().removeFrameCallback(callback)
                    frame.invoke(surface, start + index * 8_333_333L)
                    assertTrue("Paused ambient scheduling stopped at frame $index", surface.frameScheduledForQualification())
                    buckets += bucket.getLong(surface.artworkAuraMotion)
                }
                assertTrue("Fake 120Hz clock never crossed ambient deadlines", buckets.size >= 3)
                surface.updateAmbientMotionPolicy(false)
                Choreographer.getInstance().removeFrameCallback(callback)
                frame.invoke(surface, start + 150_000_000L)
                assertFalse("Reduced motion retained an idle frame owner", surface.frameScheduledForQualification())
            } finally {
                Choreographer.getInstance().removeFrameCallback(callback)
                root.removeAllViews()
            }
        }
    }

    @Test
    fun transientSongReadinessKeepsButtonPixelsContinuous() {
        activityRule.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val surface = PlayerSurfaceView(host)
            val density = host.resources.displayMetrics.density
            val initial = completeOriginalLoopSnapshot().let { snapshot ->
                snapshot.copy(toolDetail = snapshot.toolDetail.copy(
                    selectedTool = PlayerSurfaceTool.NONE,
                    activity = snapshot.toolDetail.activity.copy(loopActive = false),
                    detail = FullPlayerDetailState.None))
            }
            val sink = RecordingSink()
            fun bind(snapshot: FullPlayerSnapshot) {
                val adoption = surface.bind(snapshot, sink, PlayerSurfacePositionProvider { 10_000L },
                    1_200, 2_800, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                assertTrue("Fixture snapshot was rejected: $adoption",
                    adoption == FullPlayerAdoptionResult.APPLIED_INITIAL ||
                        adoption == FullPlayerAdoptionResult.APPLIED_NEW_OWNER)
            }
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = 60f * density, expandedHeaderGapPx = 18f,
                expandedIdentityHeightPx = 72f, expandedIdentityGapPx = 18f,
                compactChrome = false, listSpacingPx = 12f, fontScale = 1f,
                seekerClockTopClearancePx = 12f * density, seekerTrackHeightPx = 120f * density,
                transportHeightPx = 96f, transportPlaySizePx = 68f)
            bind(initial)
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            root.addView(surface, ViewGroup.LayoutParams(1_200, 2_800))
            surface.measure(exactly(1_200), exactly(2_800))
            surface.layout(0, 0, 1_200, 2_800)
            val bounds = requireNotNull(surface.semanticsGeometry()).loopTool
            val before = draw(surface, 1_200, 2_800)
            val pending = initial.copy(revision = initial.revision + 1L,
                owner = initial.owner.copy(generation = initial.owner.generation + 1L),
                toolDetail = initial.toolDetail.copy(revision = initial.toolDetail.revision + 1L,
                    activity = initial.toolDetail.activity.copy(loopAvailable = false)))
            bind(pending)
            val after = draw(surface, 1_200, 2_800)
            assertFalse("A transient readiness loss abruptly repainted the Loop icon",
                regionDiffers(before, after, bounds))
            val now = SystemClock.uptimeMillis()
            val rejectedDown = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                centerX(bounds), centerY(bounds), 0)
            try {
                assertFalse("Unavailable tool accepted DOWN", surface.dispatchTouchEvent(rejectedDown))
            } finally {
                rejectedDown.recycle()
            }
            assertTrue("Visual continuity cannot enable an unavailable action", sink.tools.isEmpty())
            before.recycle()
            after.recycle()
            root.removeAllViews()
        }
    }

    @Test
    fun artworkPaletteSubscriptionSurvivesTemporaryDetachAndReattach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val firstCover = File.createTempFile("palette-first-", ".png", context.cacheDir)
        val secondCover = File.createTempFile("palette-second-", ".png", context.cacheDir)
        listOf(firstCover to android.graphics.Color.RED, secondCover to android.graphics.Color.BLUE)
            .forEach { (file, color) ->
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
            }
        val firstDelivery = CountDownLatch(1)
        val secondDelivery = CountDownLatch(1)
        lateinit var root: FrameLayout
        lateinit var surface: PlayerSurfaceView
        var initialized = false
        val seed = completeStemsMixerSnapshot()
        fun snapshot(cover: File, revision: Long) = seed.copy(
            revision = revision,
            catalog = FullPlayerCatalogPacket(revision, seed.catalog.songs().map {
                if (it.songId == seed.owner.songId) it.copy(artworkSource = cover.absolutePath) else it
            })
        )
        fun bind(cover: File, revision: Long) {
            surface.bind(
                snapshot = snapshot(cover, revision),
                actionSink = RecordingSink(),
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = 400,
                heightPx = 800,
                density = context.resources.displayMetrics.density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
        }
        try {
            activityRule.scenario.onActivity { host ->
                root = FrameLayout(host)
                host.setContentView(root)
                surface = PlayerSurfaceView(host)
                initialized = true
                surface.setArtworkPaletteListener { _, _, _, source, _, _, _, _ ->
                    when {
                        source.endsWith(firstCover.name) -> firstDelivery.countDown()
                        source.endsWith(secondCover.name) -> secondDelivery.countDown()
                    }
                }
                bind(firstCover, 100L)
                root.addView(surface, ViewGroup.LayoutParams(400, 800))
            }
            assertTrue("Initial cover must publish a palette", firstDelivery.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                root.removeView(surface)
                assertFalse(surface.isAttachedToWindow)
                bind(secondCover, 101L)
                root.addView(surface, ViewGroup.LayoutParams(400, 800))
                assertTrue(surface.isAttachedToWindow)
            }
            assertTrue("The original subscription must receive the replacement cover after reattach",
                secondDelivery.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                if (initialized) {
                    surface.setArtworkPaletteListener(null)
                    root.removeAllViews()
                }
            }
            firstCover.delete()
            secondCover.delete()
        }
    }

    @Test
    fun rejectedOrdinaryABMovesRetryOnFrameBeforeUpWithoutAnotherMotionEvent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val width = 1_200
        val height = 2_800
        val density = context.resources.displayMetrics.density
        lateinit var host: ComponentActivity
        lateinit var root: FrameLayout
        activityRule.scenario.onActivity { host = it }

        instrumentation.runOnMainSync {
            root = FrameLayout(host)
            host.setContentView(root)
        }

        listOf(FullPlayerLoopBoundary.START, FullPlayerLoopBoundary.END).forEach { boundary ->
            val sink = RecordingSink(rejectFirstOrdinaryMove = true)
            lateinit var surface: PlayerSurfaceView
            lateinit var gesture: PointerGesture
            var targetX = 0f
            var targetY = 0f

            instrumentation.runOnMainSync {
                surface = PlayerSurfaceView(context)
                surface.updateRouteBaseMetrics(
                    expansionProgress = 1f,
                    artworkAlpha = 1f,
                    expandedHeaderHeightPx = 60f * density,
                    expandedHeaderGapPx = 18f,
                    expandedIdentityHeightPx = 72f,
                    expandedIdentityGapPx = 18f,
                    compactChrome = false,
                    listSpacingPx = 12f,
                    fontScale = 1f,
                    seekerClockTopClearancePx = 12f * density,
                    seekerTrackHeightPx = 120f * density,
                    transportHeightPx = 96f,
                    transportPlaySizePx = 68f
                )
                surface.bind(
                    snapshot = completeStemsLoopSnapshot(),
                    actionSink = sink,
                    positionProvider = PlayerSurfacePositionProvider { 10_000L },
                    widthPx = width,
                    heightPx = height,
                    density = density,
                    direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
                )
                surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
                root.addView(surface, ViewGroup.LayoutParams(width, height))
                surface.measure(exactly(width), exactly(height))
                surface.layout(0, 0, width, height)
            }
            // Visibility aggregation occurs at traversal; DOWN must start on a visible owner.
            awaitNextFrame(instrumentation)
            instrumentation.runOnMainSync {
                val geometry = requireNotNull(surface.semanticsGeometry())
                val initialProgress = if (boundary == FullPlayerLoopBoundary.START) {
                    5_000f / 60_000f
                } else {
                    15_000f / 60_000f
                }
                val initialX = trackX(geometry, initialProgress, density)
                targetX = trackX(geometry, if (boundary == FullPlayerLoopBoundary.START) {
                    7_000f / 60_000f
                } else {
                    13_000f / 60_000f
                }, density)
                targetY = loopMotorCenterY(geometry, density)
                gesture = beginDrag(surface, initialX, targetY)
                moveDrag(surface, gesture, targetX, targetY, 1L)
                assertEquals("$boundary did not publish its first MOVE", 1, sink.loopBoundaryMoves.size)
                assertTrue("Fixture must test ordinary rejection, not an accepted B capture",
                    sink.loopBoundaryMoves.single().capturedBMs < 0L)
            }

            // No MOVE or UP occurs here. The retained DOWN owner must retry its newest tuple at VSYNC.
            awaitNextFrame(instrumentation)

            instrumentation.runOnMainSync {
                assertEquals("$boundary did not converge on the next frame", 2, sink.loopBoundaryMoves.size)
                val rejected = sink.loopBoundaryMoves[0]
                val accepted = sink.loopBoundaryMoves[1]
                assertEquals(boundary, rejected.boundary)
                assertEquals(rejected.copy(sinkAtMicros = accepted.sinkAtMicros), accepted)
                endDrag(surface, gesture, targetX, targetY, 2L)
                root.removeView(surface)
            }
        }
    }

    @Test
    fun approvedNativeRolesRenderAndRemainActionable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sink = RecordingSink()
        lateinit var surface: PlayerSurfaceView
        lateinit var geometry: PlayerSurfaceSemanticsGeometry
        lateinit var idleBitmap: Bitmap
        val width = 1_200
        // Match the accepted portrait-phone layout fixture. The old 1,800 px canvas could not
        // contain the six-lane mixer endpoint and correctly caused semantics to withdraw.
        val height = 2_800
        val density = context.resources.displayMetrics.density
        lateinit var host: ComponentActivity
        activityRule.scenario.onActivity { host = it }

        val retainedBitmaps = mutableListOf<Bitmap>()
        var attachedSurface: PlayerSurfaceView? = null
        fun capture(): Bitmap = draw(surface, width, height).also(retainedBitmaps::add)
        fun recycle(vararg bitmaps: Bitmap) {
            bitmaps.forEach { it.recycle(); retainedBitmaps.remove(it) }
        }
        releaseRenderingFixture = {
            try {
                retainedBitmaps.forEach(Bitmap::recycle)
            } finally {
                instrumentation.runOnMainSync {
                    attachedSurface?.let { (it.parent as? ViewGroup)?.removeView(it) }
                }
            }
        }
        instrumentation.runOnMainSync {
            surface = PlayerSurfaceView(context)
            attachedSurface = surface
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f,
                artworkAlpha = 1f,
                // Stems owns the production 60 dp header. A legacy 620 px fixture value shields
                // the first mixer rows on high-density devices even though their geometry is valid.
                expandedHeaderHeightPx = 60f * density,
                expandedHeaderGapPx = 18f,
                expandedIdentityHeightPx = 72f,
                expandedIdentityGapPx = 18f,
                compactChrome = false,
                listSpacingPx = 12f,
                fontScale = 1f,
                seekerClockTopClearancePx = 12f * density,
                // The production bridge supplies pixels. Keep the complete 96 dp A/B motor
                // inside this fixture's seeker envelope at the device's actual density.
                seekerTrackHeightPx = 120f * density,
                transportHeightPx = 96f,
                transportPlaySizePx = 68f
            )
            val snapshot = completeStemsLoopSnapshot()
            surface.bind(
                snapshot = snapshot,
                actionSink = sink,
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = width,
                heightPx = height,
                density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            // The production Compose bridge may publish this envelope before Android's first
            // View layout. It must be retained without trying to space transport controls at 0dp.
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            host.addContentView(surface, ViewGroup.LayoutParams(width, height))
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            settleToolGeometry(surface, snapshot, width, height, density)
            geometry = requireNotNull(surface.semanticsGeometry())
            idleBitmap = capture()
        }

        assertEquals("Opening Loop keeps the current six-lane mixer", 6, geometry.mixerLaneCount)
        assertTrue("Retained mixer controls remain actionable", geometry.mixerLaneAt(0).volume.height > 0f)
        assertTrue("Bound PlayerSurfaceView output was blank", hasVisiblePixels(idleBitmap))
        awaitNextFrame(instrumentation)
        var hiddenFrame = 0L
        instrumentation.runOnMainSync {
            surface.visibility = View.INVISIBLE
            surface.onVisibilityAggregated(false)
            hiddenFrame = surface.frameTimeNanosForQualification()
            assertFalse(
                "Hidden native surface retained a frame callback",
                surface.frameScheduledForQualification()
            )
        }
        awaitNextFrame(instrumentation)
        assertEquals(
            "Hidden native surface kept owning VSYNC",
            hiddenFrame,
            surface.frameTimeNanosForQualification()
        )
        instrumentation.runOnMainSync {
            surface.visibility = View.VISIBLE
            surface.onVisibilityAggregated(true)
            assertTrue(
                "Visible native surface did not resume its frame callback",
                surface.frameScheduledForQualification()
            )
        }
        awaitNextFrame(instrumentation)
        assertTrue(
            "Visible native surface did not resume VSYNC",
            surface.frameTimeNanosForQualification() > hiddenFrame
        )
        try {
            val background = idleBitmap.getPixel(0, 0)
            listOf(
                geometry.original,
                geometry.stems,
                geometry.playPause,
                geometry.seeker,
                geometry.loopTool,
                geometry.metronomeTool
            ).forEach { region ->
                assertTrue("A real native role region was blank: $region", regionHasInk(idleBitmap, region, background))
            }
        } finally { recycle(idleBitmap) }

        // Accepted Up owns the bloom. Capture before its first VSYNC so the simultaneous
        // play-to-pause glyph change cannot masquerade as feedback pixels.
        lateinit var acceptedBeforeBloom: Bitmap
        lateinit var acceptedBloom: Bitmap
        instrumentation.runOnMainSync {
            down(surface, geometry.playPause)
            assertEquals(0, sink.transport.count {
                it == PlayerSurfaceTransportCommand.PLAY_PAUSE
            })
            up(surface, geometry.playPause, 280L)
            assertEquals(1, sink.transport.count {
                it == PlayerSurfaceTransportCommand.PLAY_PAUSE
            })
            acceptedBeforeBloom = capture()
        }
        awaitNextFrame(instrumentation)
        instrumentation.runOnMainSync { acceptedBloom = capture() }
        try {
            assertTrue(
                "Accepted play/pause aura produced no pixels",
                regionDiffers(acceptedBeforeBloom, acceptedBloom, geometry.playPause)
            )
        } finally { recycle(acceptedBeforeBloom, acceptedBloom) }

        // Queue-end availability gates the real native hit motor, not only accessibility.
        instrumentation.runOnMainSync { assertRejectedDown(surface, geometry.next) }
        assertTrue("Disabled Next reached the navigation sink", sink.navigation.isEmpty())

        // Source selection is the real optimistic Original-to-Stems product boundary.
        instrumentation.runOnMainSync {
            for (source in listOf(geometry.original, geometry.stems)) {
                down(surface, source)
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now - 32L, now, MotionEvent.ACTION_UP, -20f, -20f)
            }
            assertTrue("Source dispatched after release outside its complete physical owner", sink.sources.isEmpty())
            tap(surface, geometry.original)
            tap(surface, geometry.stems)
        }
        assertEquals(
            listOf(PlayerSurfaceSourceMode.ORIGINAL, PlayerSurfaceSourceMode.STEMS),
            sink.sources
        )

        // A/B owns DOWN immediately. Its paired endpoint bodies may grow together, but cannot
        // restyle the playback cursor before horizontal/Fine admission.
        val aDownX = trackX(geometry, 5_000f / 60_000f, density)
        val aDownY = loopMotorCenterY(geometry, density)
        lateinit var aDownIdle: Bitmap
        lateinit var aDownHeld: Bitmap
        lateinit var aDownGesture: PointerGesture
        instrumentation.runOnMainSync {
            aDownIdle = capture()
            aDownGesture = beginDrag(surface, aDownX, aDownY)
        }
        awaitNextFrame(instrumentation)
        awaitNextFrame(instrumentation)
        instrumentation.runOnMainSync { aDownHeld = capture() }
        assertEquals("A DOWN must target the loop, not the playback cursor", FullPlayerLoopBoundary.START, sink.lastLoopBoundaryBegin)
        val idlePlayheadX = trackX(geometry, 10_000f / 60_000f, density)
        val playheadRadius = StemwiseSeekerDesign.PLAYHEAD_HELD_DIAMETER_DP * density * .5f
        val playheadY = seekerRailY(geometry, density)
        val cursorBounds = PlayerSurfaceBounds(
            idlePlayheadX - playheadRadius, playheadY - playheadRadius,
            idlePlayheadX + playheadRadius, playheadY + playheadRadius
        )
        // Boundary mode removes the ordinary vertical guide and uses a cached cursor bitmap.
        // Compare the light cursor body rather than counting those intentional purple changes.
        fun cursorBodyArea(bitmap: Bitmap): Int {
            var area = 0
            for (y in cursorBounds.top.toInt() until cursorBounds.bottom.toInt()) {
                for (x in cursorBounds.left.toInt() until cursorBounds.right.toInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 200 &&
                        android.graphics.Color.red(pixel) > 200 &&
                        android.graphics.Color.green(pixel) > 200 &&
                        android.graphics.Color.blue(pixel) > 200) area++
                }
            }
            return area
        }
        try {
            val idleCursorArea = cursorBodyArea(aDownIdle)
            val boundaryCursorArea = cursorBodyArea(aDownHeld)
            assertTrue("Playback cursor disappeared before A/B DOWN", idleCursorArea > 0)
            assertTrue("A/B DOWN changed the playback cursor size: $idleCursorArea -> $boundaryCursorArea",
                boundaryCursorArea in (idleCursorArea * .9f).toInt()..(idleCursorArea * 1.1f).toInt())
        } finally { recycle(aDownIdle, aDownHeld) }
        instrumentation.runOnMainSync { endDrag(surface, aDownGesture, aDownX, aDownY, 64L) }

        // Normal scrub publishes and paints its movable feedback on the same MOVE.
        lateinit var normalIdle: Bitmap
        lateinit var normalMove: Bitmap
        lateinit var normalGesture: PointerGesture
        val normalStartX = trackX(geometry, .55f, density)
        val normalEndX = trackX(geometry, .72f, density)
        val seekerCenterY = (geometry.seeker.top + geometry.seeker.bottom) * .5f
        val normalCommitsBefore = sink.scrubCommits
        instrumentation.runOnMainSync {
            normalIdle = capture()
            normalGesture = beginDrag(surface, normalStartX, seekerCenterY)
            moveDrag(surface, normalGesture, normalEndX, seekerCenterY, 32L)
        }
        awaitNextFrame(instrumentation)
        instrumentation.runOnMainSync { normalMove = capture() }
        val normalPreview = requireNotNull(sink.scrubPreviewPositions.lastOrNull())
        try {
            assertChangesNearX(
                "Normal seeker MOVE aura",
                normalIdle,
                normalMove,
                geometry.seeker,
                trackX(geometry, normalPreview / 60_000f, density),
                24f * density
            )
        } finally { recycle(normalIdle, normalMove) }
        assertEquals("Normal scrub committed before UP", normalCommitsBefore, sink.scrubCommits)
        instrumentation.runOnMainSync {
            endDrag(surface, normalGesture, normalEndX, seekerCenterY, 64L)
        }
        assertEquals(normalCommitsBefore + 1, sink.scrubCommits)

        // Start-handle A/B manipulation paints at its callback-proven geometry before UP.
        lateinit var loopIdle: Bitmap
        lateinit var loopMove: Bitmap
        lateinit var loopGesture: PointerGesture
        val aX = trackX(geometry, 5_000f / 60_000f, density)
        val loopY = loopMotorCenterY(geometry, density)
        val loopEndsBefore = sink.loopBoundaryEnds
        instrumentation.runOnMainSync {
            loopIdle = capture()
            loopGesture = beginDrag(surface, aX, loopY)
            moveDrag(surface, loopGesture, aX + 80f, loopY, 32L)
        }
        awaitNextFrame(instrumentation)
        instrumentation.runOnMainSync { loopMove = capture() }
        assertTrue("A/B boundary begin was not dispatched", sink.loopBoundaryBegins > 0)
        val loopMovePosition = requireNotNull(sink.loopBoundaryMoves.lastOrNull())
        try {
            assertChangesNearX(
                "Loop A MOVE aura",
                loopIdle,
                loopMove,
                geometry.seeker,
                trackX(geometry, loopMovePosition.startMs / 60_000f, density),
                24f * density
            )
        } finally { recycle(loopIdle, loopMove) }
        assertEquals("A/B boundary released before UP", loopEndsBefore, sink.loopBoundaryEnds)
        instrumentation.runOnMainSync { endDrag(surface, loopGesture, aX + 80f, loopY, 64L) }
        assertEquals(loopEndsBefore + 1, sink.loopBoundaryEnds)

        // A vertical contact enters Fine while held; its real MOVE pixels precede commit.
        lateinit var fineIdle: Bitmap
        lateinit var fineMove: Bitmap
        lateinit var fineGesture: PointerGesture
        val fineStartX = trackX(geometry, .58f, density)
        val fineEndX = trackX(geometry, .60f, density)
        val fineCommitsBefore = sink.scrubCommits
        instrumentation.runOnMainSync {
            fineIdle = capture()
            fineGesture = beginDrag(surface, fineStartX, seekerCenterY)
            moveDrag(surface, fineGesture, fineEndX, geometry.seeker.top - 140f, 32L)
        }
        awaitNextFrame(instrumentation)
        instrumentation.runOnMainSync { fineMove = capture() }
        assertTrue("Fine mode never engaged during MOVE", sink.fineStates.lastOrNull() == true)
        try {
            assertTrue(
                "Fine seeker MOVE produced no pixels",
                regionDiffers(fineIdle, fineMove, geometry.seeker)
            )
        } finally { recycle(fineIdle, fineMove) }
        assertEquals("Fine scrub committed before UP", fineCommitsBefore, sink.scrubCommits)
        instrumentation.runOnMainSync {
            endDrag(surface, fineGesture, fineEndX, geometry.seeker.top - 140f, 64L)
        }
        assertEquals(fineCommitsBefore + 1, sink.scrubCommits)
        assertTrue("Fine mode never returned after UP", sink.fineStates.lastOrNull() == false)

        // Close the loop detail through its real owner, publish the resulting owner snapshot,
        // then prove the retained mixer and remaining tool owners are single-action.
        lateinit var mixerGeometry: PlayerSurfaceSemanticsGeometry
        instrumentation.runOnMainSync {
            tap(surface, geometry.loopTool)
            val mixerSnapshot = completeStemsMixerSnapshot()
            val mixerFrame = portraitPlayerToolMotionFrame(
                width / density, height / density, density, 0f, 1f,
                loopClearanceProgress = 1f
            )
            val mixerLayout = mixerFrame.layout
            val mixerTop = portraitPlayerControlTopDp(mixerLayout, true) * density
            val mixerBottom = mixerTop + portraitPlayerControlStackHeightPx(mixerFrame, density, true, 1f)
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = mixerLayout.headerHeightDp * density,
                expandedHeaderGapPx = mixerLayout.headerGapDp * density,
                expandedIdentityHeightPx = mixerLayout.identityHeightDp * density,
                expandedIdentityGapPx = mixerLayout.identityGapDp * density,
                compactChrome = mixerLayout.compactChrome,
                listSpacingPx = mixerLayout.listSpacingDp * density, fontScale = 1f,
                seekerClockTopClearancePx = mixerLayout.seekerClockTopClearanceDp * density,
                seekerTrackHeightPx = mixerLayout.seekerTrackHeightDp * density,
                transportHeightPx = mixerLayout.transportHeightDp * density,
                transportPlaySizePx = mixerLayout.transportPlaySizeDp * density
            )
            surface.bind(
                snapshot = mixerSnapshot,
                actionSink = sink,
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = width,
                heightPx = height,
                density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            assertTrue(
                "Native tool endpoint rejected the mixer owner",
                surface.retargetToolMotion(
                    PlayerSurfaceToolMotionTarget(
                        generation = mixerSnapshot.owner.generation,
                        selectionEpoch = 2L,
                        selectedTool = PlayerSurfaceTool.NONE,
                        durationMs = 0,
                        expandedArtworkSizePx = mixerLayout.artworkSizeDp * density,
                        mixerCardHeightPx = mixerLayout.mixerCardHeightDp * density,
                        mixerSpacingPx = mixerLayout.mixerSpacingDp * density,
                        listSpacingPx = mixerLayout.listSpacingDp * density,
                        seekerTrackHeightPx = mixerLayout.seekerTrackHeightDp * density,
                        transportHeightPx = mixerLayout.transportHeightDp * density,
                        controlBounds = PlayerSurfaceBounds(48f, mixerTop, 1_152f, mixerBottom),
                        detailViewportHeightPx = 0f
                    )
                )
            )
            mixerGeometry = requireNotNull(surface.semanticsGeometry())
            assertEquals(6, mixerGeometry.mixerLaneCount)
            tap(surface, mixerGeometry.mixerLaneAt(0).mute)
            tap(surface, mixerGeometry.mixerLaneAt(0).solo)
            tap(surface, mixerGeometry.metronomeTool)
        }
        assertTrue(sink.mixerCommits.any { it.muted })
        assertTrue(sink.mixerCommits.any { it.solo })
        assertEquals(1, sink.tools.count { it == PlayerSurfaceTool.LOOP })
        assertEquals(1, sink.tools.count { it == PlayerSurfaceTool.METRONOME })

        // A cancel terminates the same real pointer owner without publishing another action.
        val playCountBeforeCancel = sink.transport.size
        instrumentation.runOnMainSync {
            down(surface, mixerGeometry.playPause)
            cancel(surface, mixerGeometry.playPause)
        }
        assertEquals(playCountBeforeCancel, sink.transport.size)

        // Horizontal paging owns travel that begins on expanded Play/Pause. It must commit one
        // adjacent page without leaking a playback toggle from the original button owner.
        val playCountBeforePagerTransfer =
            sink.transport.count { it == PlayerSurfaceTransportCommand.PLAY_PAUSE }
        val pagerCommitsBeforeTransfer = sink.pagerCommits.size
        val playCenterX = centerX(mixerGeometry.playPause)
        val playCenterY = centerY(mixerGeometry.playPause)
        instrumentation.runOnMainSync {
            val gesture = beginDrag(surface, playCenterX, playCenterY)
            moveDrag(surface, gesture, playCenterX - width * .34f, playCenterY, 32L)
            endDrag(surface, gesture, playCenterX - width * .40f, playCenterY, 64L)
        }
        assertEquals(
            "Expanded Play/Pause leaked a playback command while transferring to the pager",
            playCountBeforePagerTransfer,
            sink.transport.count { it == PlayerSurfaceTransportCommand.PLAY_PAUSE }
        )
        assertEquals(pagerCommitsBeforeTransfer + 1, sink.pagerCommits.size)
        assertTrue("Expanded Play/Pause drag skipped more than one song", sink.pagerCommits.last() in 0..2)
        assertTrue("Expanded Play/Pause drag did not select an adjacent song", sink.pagerCommits.last() != 1)
    }
}
