package app.stemwise.ui.player.surface

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
import app.stemwise.playback.consumerAdoptionBudgetMicros
import app.stemwise.playback.requireDisposablePlaybackTarget
import app.stemwise.playback.resetForInstrumentationIsolation
import app.stemwise.playback.admitCurrentPlaybackLibrarySnapshot
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
internal class DesignSystemNativePlayerInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test
    fun pausedZeroGainMixerSettlesWithoutAmbientFrames() {
        lateinit var surface: PlayerSurfaceView
        val sink = RecordingSink()
        activityRule.scenario.onActivity { host ->
            val seed = completeStemsMixerSnapshot()
            surface = qualifiedSurface(host, seed.copy(transport = seed.transport.copy(playWhenReady = false)), sink).second
            assertEquals(seed.sourceMixer.mixer.laneCount, requireNotNull(surface.semanticsGeometry()).mixerLaneCount)
            surface.updateAmbientMotionPolicy(false)
            val lane = requireNotNull(surface.semanticsGeometry()).mixerLaneAt(0)
            val now = SystemClock.uptimeMillis()
            dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(lane.volume), centerY(lane.volume))
            dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, lane.volume.left, centerY(lane.volume))
            dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP, lane.volume.left, centerY(lane.volume))
            assertEquals(0f, sink.mixerCommits.last().volume, 0f)
            assertTrue(sink.mixerCommits.last().muted)
            tap(surface, lane.mute)
            assertFalse(sink.mixerCommits.last().muted)
            tap(surface, lane.mute)
            assertTrue(sink.mixerCommits.last().muted)
        }
        SystemClock.sleep(650L)
        awaitNextFrame(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        activityRule.scenario.onActivity {
            draw(surface, surface.width, surface.height).recycle()
            val states = PlayerSurfaceView::class.java.getDeclaredField("mixerSelectedStates")
                .apply { isAccessible = true }.get(surface) as Array<*>
            val muteVisual = states[0] as NativeSelectedStateEnvelope
            assertEquals("Visual silence did not reach its endpoint", 1f, muteVisual.value, .001f)
            assertFalse("Zero-gain mixer region kept its selected transition alive",
                muteVisual.needsAnotherFrame(SystemClock.uptimeMillis()))
            assertFalse("Zero-gain envelope kept scheduling frames", surface.frameScheduledForQualification())
        }
    }

    @Test
    fun programmaticMixerSelectionReachesItsEndpointWithoutOtherInput() {
        lateinit var surface: PlayerSurfaceView
        lateinit var seed: FullPlayerSnapshot
        val sink = RecordingSink()
        activityRule.scenario.onActivity { host ->
            val initial = completeStemsMixerSnapshot()
            seed = initial.copy(transport = initial.transport.copy(playWhenReady = false))
            surface = qualifiedSurface(host, seed, sink).second
            assertEquals(seed.sourceMixer.mixer.laneCount, requireNotNull(surface.semanticsGeometry()).mixerLaneCount)
            surface.updateAmbientMotionPolicy(false)
            draw(surface, surface.width, surface.height).recycle()
        }
        SystemClock.sleep(650L)
        activityRule.scenario.onActivity { host ->
            val source = seed.sourceMixer
            val mixer = source.mixer
            val next = seed.copy(revision = seed.revision + 1L, sourceMixer = FullPlayerSourceMixerPacket(
                revision = source.revision + 1L, source = source.source, lanes = source.lanes(),
                mixer = PlayerSurfaceMixerPacket(mixer.revision + 1L, mixer.packageRevision,
                    IntArray(mixer.laneCount, mixer::laneKeyAt), FloatArray(mixer.laneCount) { 1f },
                    IntArray(mixer.laneCount) { if (it == 0) PlayerSurfaceMixerPacket.FLAG_MUTED else 0 })))
            surface.bind(next, sink, PlayerSurfacePositionProvider { 10_000L }, 1_200, 2_800,
                host.resources.displayMetrics.density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
        }
        val selected = PlayerSurfaceView::class.java.getDeclaredField("mixerSelectedStates")
            .apply { isAccessible = true }
        val value = AtomicReference(0f)
        val deadline = SystemClock.uptimeMillis() + 2_000L
        do {
            activityRule.scenario.onActivity {
                val states = selected.get(surface) as Array<*>
                value.set((states[0] as NativeSelectedStateEnvelope).value)
            }
            if (value.get() < .999f) SystemClock.sleep(20L)
        } while (value.get() < .999f && SystemClock.uptimeMillis() < deadline)
        assertEquals("A programmatic mute stopped before its visible endpoint", 1f, value.get(), .001f)
    }

    @Test
    fun reducedMotionFreezesDecorativeMiniPhaseWhilePositionStillChangesTheFill() {
        activityRule.scenario.onActivity { host ->
            val snapshot = completeOriginalLoopSnapshot()
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, snapshot, sink)
            val density = host.resources.displayMetrics.density
            surface.updateRouteBaseMetrics(0f, 1f, 60f * density, 18f, 72f, 18f,
                false, 12f, 1f, 12f * density, 120f * density, 96f, 68f)
            surface.updateAmbientMotionPolicy(false)
            val pager = PlayerSurfaceView::class.java.getDeclaredField("pagerDrawing")
                .apply { isAccessible = true }.get(surface)
            val draw = pager.javaClass.getDeclaredMethod("drawMiniPlayerBody", Canvas::class.java)
                .apply { isAccessible = true }
            fun render() = Bitmap.createBitmap(1_200, 300, Bitmap.Config.ARGB_8888).also { draw.invoke(pager, Canvas(it)) }
            val first = render()
            var later: Bitmap? = null
            var advanced: Bitmap? = null
            try {
                SystemClock.sleep(64L)
                later = render()
                assertTrue("Reduced motion still advances decorative phase", first.sameAs(later))
                surface.bind(snapshot, sink, PlayerSurfacePositionProvider { 20_000L }, 1_200, 2_800,
                    density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                advanced = render()
                assertFalse("Essential playback fill stopped advancing", later.sameAs(advanced))
            } finally {
                first.recycle(); later?.recycle(); advanced?.recycle(); root.removeAllViews()
            }
        }
    }

    @Test
    fun largeMixerScrollsOnlyItsRowsAndSeparatesVerticalScrollFromHorizontalGain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val width = (432f * density).toInt()
        val height = (860f * density).toInt()
        instrumentation.runOnMainSync {
            val seed = completeStemsMixerSnapshot()
            val sourceIds = listOf("lead-vocals", "backing-vocals", "bass", "drums", "guitar",
                "piano", "organ", "synth", "remaining")
            assertEquals(PLAYER_SURFACE_MAX_MIXER_LANES, sourceIds.size)
            val lanes = sourceIds.mapIndexed { index, id -> FullPlayerMixerLaneVisual(index + 1,
                sourcePartIconRole(id), if (id == "remaining") "Remaining" else
                    requireNotNull(StemSourceCatalogue.source(id)).label, -1) }
            val snapshot = seed.copy(revision = 300L, sourceMixer = FullPlayerSourceMixerPacket(
                revision = 300L, source = seed.sourceMixer.source.copy(revision = 300L),
                mixer = PlayerSurfaceMixerPacket(300L, 300L, IntArray(lanes.size) { it + 1 },
                    FloatArray(lanes.size) { 1f }, IntArray(lanes.size)), lanes = lanes), timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L))
            val sink = RecordingSink()
            val surface = PlayerSurfaceView(context)
            val frame = portraitPlayerToolMotionFrame(432f, 860f, density, 0f, 1f,
                mixerLaneCount = lanes.size)
            val layout = frame.layout
            surface.bind(snapshot, sink, PlayerSurfacePositionProvider { 10_000L }, width, height,
                density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            surface.updateRouteBaseMetrics(1f, 0f, layout.headerHeightDp * density,
                layout.headerGapDp * density, layout.identityHeightDp * density,
                layout.identityGapDp * density, layout.compactChrome, layout.listSpacingDp * density,
                1f, layout.seekerClockTopClearanceDp * density, layout.seekerTrackHeightDp * density,
                layout.transportHeightDp * density, layout.transportPlaySizeDp * density)
            val top = portraitPlayerControlTopDp(layout, true) * density
            surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                generation = snapshot.owner.generation, selectionEpoch = 1L,
                selectedTool = PlayerSurfaceTool.NONE, durationMs = 0,
                expandedArtworkSizePx = layout.artworkSizeDp * density,
                mixerCardHeightPx = layout.mixerCardHeightDp * density,
                mixerSpacingPx = layout.mixerSpacingDp * density, listSpacingPx = layout.listSpacingDp * density,
                seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                transportHeightPx = layout.transportHeightDp * density,
                controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density,
                    top + portraitPlayerControlStackHeightPx(frame, density, true, mixerLaneCount = lanes.size)),
                detailViewportHeightPx = 0f
            ))
            val before = requireNotNull(surface.semanticsGeometry())
            assertEquals(lanes.size, before.mixerLaneCount)
            assertEquals(0f, before.mixerLaneAt(lanes.lastIndex).volume.height, .001f)
            val first = before.mixerLaneAt(0).volume
            val x = (first.left + first.right) / 2f
            val y = (first.top + first.bottom) / 2f
            val vertical = beginDrag(surface, x, y)
            moveDrag(surface, vertical, x + density, y - 100f * density, 20L)
            endDrag(surface, vertical, x + density, y - 100f * density, 40L)
            val scrolled = requireNotNull(surface.semanticsGeometry())
            assertTrue(scrolled.mixerScrollOffsetPx > 0f)
            assertTrue("Vertical scroll admitted a gain gesture", sink.mixerBegins.isEmpty())
            assertTrue("Vertical scroll changed mix controls", sink.mixerCommits.isEmpty())
            assertEquals(before.seeker, scrolled.seeker)
            assertEquals(before.playPause, scrolled.playPause)
            assertEquals(before.original, scrolled.original)
            assertTrue(surface.scrollMixerBy(snapshot.owner.generation, scrolled.mixerScrollMaximumPx))
            val lastGeometry = requireNotNull(surface.semanticsGeometry())
            val last = lastGeometry.mixerLaneAt(lanes.lastIndex).volume
            assertTrue("Last admitted part is unreachable", last.height > 0f)
            assertEquals(0f, lastGeometry.mixerLaneAt(0).volume.height, .001f)
            assertFalse(surface.scrollMixerBy(snapshot.owner.generation + 1L, -100f))
            assertEquals(lastGeometry.mixerScrollOffsetPx,
                requireNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx, 0f)
            val horizontalX = (last.left + last.right) / 2f
            val horizontalY = (last.top + last.bottom) / 2f
            val horizontal = beginDrag(surface, horizontalX, horizontalY)
            moveDrag(surface, horizontal, horizontalX + 30f * density, horizontalY, 20L)
            endDrag(surface, horizontal, horizontalX + 30f * density, horizontalY, 40L)
            assertEquals(listOf(lanes.lastIndex), sink.mixerBegins)
            assertEquals(1, sink.mixerCommits.size)
            assertEquals(lanes.lastIndex, sink.mixerCommits.single().laneIndex)
            assertTrue(sink.mixerCommits.single().volume in 0f..1f)
            assertTrue(sink.mixerCommits.single().volume != 1f)
            assertEquals(lastGeometry.mixerScrollOffsetPx,
                requireNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx, 0f)

            // The highest lane must retain its own buttons after the list moves. Toggling a
            // button must preserve its gain and the other toggle, without admitting gain input.
            val lastLane = requireNotNull(surface.semanticsGeometry()).mixerLaneAt(lanes.lastIndex)
            val lastVolume = sink.mixerCommits.single().volume
            tap(surface, lastLane.mute)
            assertEquals(MixerCommit(lanes.lastIndex, true, false, lastVolume), sink.mixerCommits.last())
            tap(surface, lastLane.mute)
            assertEquals(MixerCommit(lanes.lastIndex, false, false, lastVolume), sink.mixerCommits.last())
            tap(surface, lastLane.solo)
            assertEquals(MixerCommit(lanes.lastIndex, false, true, lastVolume), sink.mixerCommits.last())
            assertEquals(4, sink.mixerCommits.size)
            assertEquals(listOf(lanes.lastIndex), sink.mixerBegins)

            // A vertical drag can begin on either button or in a row gap. It must scroll only,
            // never toggle that button or change a gain when the finger is released.
            listOf(true, false).forEach { fromMute ->
                surface.scrollMixerBy(snapshot.owner.generation, lastGeometry.mixerScrollMaximumPx)
                val button = requireNotNull(surface.semanticsGeometry()).mixerLaneAt(lanes.lastIndex).let {
                    if (fromMute) it.mute else it.solo
                }
                val offset = requireNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx
                val drag = beginDrag(surface, centerX(button), centerY(button))
                moveDrag(surface, drag, centerX(button), centerY(button) + 100f * density, 20L)
                endDrag(surface, drag, centerX(button), centerY(button) + 100f * density, 40L)
                assertTrue(requireNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx < offset)
                assertEquals(4, sink.mixerCommits.size)
                assertEquals(listOf(lanes.lastIndex), sink.mixerBegins)
            }
            assertTrue(surface.scrollMixerBy(snapshot.owner.generation, -lastGeometry.mixerScrollMaximumPx))
            val gapGeometry = requireNotNull(surface.semanticsGeometry())
            val gapY = (gapGeometry.mixerLaneAt(0).volume.bottom +
                gapGeometry.mixerLaneAt(1).volume.top) * .5f
            assertTrue(gapY > gapGeometry.mixerLaneAt(0).volume.bottom)
            assertTrue(gapY < gapGeometry.mixerLaneAt(1).volume.top)
            val gapDrag = beginDrag(surface, x, gapY)
            moveDrag(surface, gapDrag, x, gapY - 100f * density, 20L)
            endDrag(surface, gapDrag, x, gapY - 100f * density, 40L)
            val afterGap = requireNotNull(surface.semanticsGeometry())
            assertTrue(afterGap.mixerScrollOffsetPx > 0f)
            assertEquals(4, sink.mixerCommits.size)
            assertEquals(listOf(lanes.lastIndex), sink.mixerBegins)
            assertEquals(before.seeker, afterGap.seeker)
            assertEquals(before.playPause, afterGap.playPause)
            assertEquals(before.original, afterGap.original)
        }
    }

    @Test
    fun stemIconOwnsMuteAndVolumeThumbExpandsOnlyWhileHeld() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var surface: PlayerSurfaceView
        lateinit var geometry: PlayerSurfaceSemanticsGeometry
        lateinit var idle: Bitmap
        val sink = RecordingSink()
        activityRule.scenario.onActivity { host ->
            val density = host.resources.displayMetrics.density
            surface = PlayerSurfaceView(host)
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = 60f * density, expandedHeaderGapPx = 18f,
                expandedIdentityHeightPx = 72f, expandedIdentityGapPx = 18f,
                compactChrome = false, listSpacingPx = 12f, fontScale = 1f,
                seekerClockTopClearancePx = 12f * density,
                seekerTrackHeightPx = 120f * density, transportHeightPx = 96f,
                transportPlaySizePx = 68f
            )
            surface.bind(completeStemsMixerSnapshot(), sink, PlayerSurfacePositionProvider { 10_000L },
                1200, 2800, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            host.addContentView(surface, ViewGroup.LayoutParams(1200, 2800))
            surface.setControlBounds(48f, 760f, 1152f, 2720f)
            surface.measure(exactly(1200), exactly(2800))
            surface.layout(0, 0, 1200, 2800)
            surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                generation = 1L, selectionEpoch = 1L, selectedTool = PlayerSurfaceTool.NONE,
                durationMs = 0, expandedArtworkSizePx = 180f * density,
                mixerCardHeightPx = 52f * density, mixerSpacingPx = 4f * density,
                listSpacingPx = 12f, seekerTrackHeightPx = 120f * density,
                transportHeightPx = 96f, controlBounds = PlayerSurfaceBounds(48f, 760f, 1152f, 2720f),
                detailViewportHeightPx = 0f
            ))
            geometry = requireNotNull(surface.semanticsGeometry())
            assertEquals(6, geometry.mixerLaneCount)
            val lane = geometry.mixerLaneAt(0)
            assertEquals(48f + 24f * density, geometry.mixerViewport.left, .1f)
            assertEquals(geometry.mixerViewport.left + 31f * density, centerX(lane.mute), .1f)
            assertTrue(lane.mute.right < lane.volume.left && lane.volume.right < lane.solo.left)
            tap(surface, lane.mute)
            assertTrue(sink.mixerCommits.last().muted)
            tap(surface, lane.mute)
            assertFalse(sink.mixerCommits.last().muted)
            idle = draw(surface, 1200, 2800)
            down(surface, lane.volume)
        }
        fun whiteArea(bitmap: Bitmap): Int {
            val bounds = geometry.mixerLaneAt(0).volume
            var count = 0
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(color) > 235 &&
                        android.graphics.Color.green(color) > 235 &&
                        android.graphics.Color.blue(color) > 235) count++
                }
            }
            return count
        }
        SystemClock.sleep(420L)
        awaitNextFrame(instrumentation)
        activityRule.scenario.onActivity { host ->
            val held = draw(surface, 1200, 2800)
            assertTrue("Stem volume thumb did not expand on DOWN", whiteArea(held) > whiteArea(idle) * 1.5f)
            up(surface, geometry.mixerLaneAt(0).volume, 240L)
            held.recycle()
        }
        SystemClock.sleep(420L)
        awaitNextFrame(instrumentation)
        activityRule.scenario.onActivity { host ->
            val released = draw(surface, 1200, 2800)
            assertTrue("Stem thumb did not return after UP: ${whiteArea(idle)} -> ${whiteArea(released)}",
                whiteArea(released) <= whiteArea(idle) * 1.1f)
            released.recycle()
            idle.recycle()
            val lane = geometry.mixerLaneAt(0)
            val now = SystemClock.uptimeMillis()
            dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(lane.volume), centerY(lane.volume))
            dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, lane.volume.left, centerY(lane.volume))
            dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP, lane.volume.left, centerY(lane.volume))
            assertEquals(0f, sink.mixerCommits.last().volume, 0f)
            // Zero slider requests mute and draws the same selected glyph state.
            assertTrue(sink.mixerCommits.last().muted)
            draw(surface, 1200, 2800).recycle()
        }
        SystemClock.sleep(420L)
        awaitNextFrame(instrumentation)
        activityRule.scenario.onActivity { host ->
            val muted = draw(surface, 1200, 2800)
            java.io.File(host.cacheDir, "touch-stems-zero.png").outputStream().use {
                muted.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            val bounds = geometry.mixerLaneAt(0).mute
            var visibleGrayPixels = 0
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    val c = muted.getPixel(x, y)
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    if (r > 100 && kotlin.math.abs(r - g) < 20 && kotlin.math.abs(r - b) < 20) visibleGrayPixels++
                }
            }
            assertTrue("Muted stem glyph should remain visibly gray", visibleGrayPixels > 20)
            muted.recycle()
        }
    }

    @Test
    fun fineWaveformOmitsOutOfSongStrokes() {
        activityRule.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val surface = PlayerSurfaceView(host)
            val density = host.resources.displayMetrics.density
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = 60f * density, expandedHeaderGapPx = 18f,
                expandedIdentityHeightPx = 72f, expandedIdentityGapPx = 18f,
                compactChrome = false, listSpacingPx = 12f, fontScale = 1f,
                seekerClockTopClearancePx = 12f * density,
                seekerTrackHeightPx = 120f * density, transportHeightPx = 96f,
                transportPlaySizePx = 68f
            )
            val snapshot = completeOriginalLoopSnapshot()
            surface.bind(
                snapshot, RecordingSink(), PlayerSurfacePositionProvider { 0L },
                1200, 2800, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            root.addView(surface, ViewGroup.LayoutParams(1200, 2800))
            surface.setControlBounds(48f, 760f, 1152f, 2720f)
            surface.measure(exactly(1200), exactly(2800))
            surface.layout(0, 0, 1200, 2800)
            assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                generation = 1L, selectionEpoch = 1L, selectedTool = PlayerSurfaceTool.NONE,
                durationMs = 0, expandedArtworkSizePx = 180f * density,
                mixerCardHeightPx = 0f, mixerSpacingPx = 0f, listSpacingPx = 12f,
                seekerTrackHeightPx = 120f * density, transportHeightPx = 96f,
                controlBounds = PlayerSurfaceBounds(48f, 900f, 1152f, 2720f),
                detailViewportHeightPx = 0f
            )))
            fun field(name: String) = PlayerSurfaceView::class.java.getDeclaredField(name)
                .apply { isAccessible = true }
            val update = PlayerSurfaceView::class.java.getDeclaredMethod(
                "updateFineWaveformGeometry", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
            try {
                val left = field("seekerTrackLeft").getFloat(surface)
                val right = field("seekerTrackRight").getFloat(surface)
                val centerX = (left + right) * .5f
                for (atStart in listOf(true, false)) {
                    update.invoke(surface, if (atStart) 0L else 60_000L, 60_000L)
                    val geometry = field("waveformGeometry").get(surface) as PlayerSurfaceWaveformGeometry
                    val count = geometry.visiblePointCount
                    val all = geometry.barCount * 4
                    val points = geometry.points
                    assertTrue("Song edge must omit half of the strokes: $count/$all", count in 4 until all)
                    for (index in 0 until count step 4) {
                        assertTrue("An out-of-song stroke was submitted", if (atStart) points[index] >= centerX else points[index] <= centerX)
                        assertTrue(points[index + 3] > points[index + 1])
                    }
                }
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun pagerLocksItsAxisAndCommitsTheDisplayedAdjacentSongId() {
        activityRule.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val surface = PlayerSurfaceView(host)
            val sink = RecordingSink()
            val density = host.resources.displayMetrics.density
            surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = 60f * density, expandedHeaderGapPx = 18f,
                expandedIdentityHeightPx = 72f, expandedIdentityGapPx = 18f,
                compactChrome = false, listSpacingPx = 12f, fontScale = 1f,
                seekerClockTopClearancePx = 12f * density,
                seekerTrackHeightPx = 120f * density, transportHeightPx = 96f,
                transportPlaySizePx = 68f
            )
            surface.bind(
                snapshot = completeOriginalLoopSnapshot(), actionSink = sink,
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = 1_200, heightPx = 2_800, density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            root.addView(surface, ViewGroup.LayoutParams(1_200, 2_800))
            surface.measure(exactly(1_200), exactly(2_800))
            surface.layout(0, 0, 1_200, 2_800)
            try {
                assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = 1L, selectionEpoch = 1L, selectedTool = PlayerSurfaceTool.NONE,
                    durationMs = 0, expandedArtworkSizePx = 180f * density,
                    mixerCardHeightPx = 0f, mixerSpacingPx = 0f, listSpacingPx = 12f,
                    seekerTrackHeightPx = 120f * density, transportHeightPx = 96f,
                    controlBounds = PlayerSurfaceBounds(48f, 900f, 1_152f, 2_720f),
                    detailViewportHeightPx = 0f
                )))
                val art = requireNotNull(surface.semanticsGeometry()).artwork
                val x = centerX(art)
                val y = centerY(art)
                val vertical = beginDrag(surface, x, y)
                moveDrag(surface, vertical, x - 10f, y + 100f, 100L)
                moveDrag(surface, vertical, x - 500f, y + 100f, 200L)
                dispatch(surface, vertical.downTime, vertical.downTime + 300L,
                    MotionEvent.ACTION_UP, x - 500f, y + 100f)
                assertTrue("A vertical gesture cannot later become a song swipe", sink.pagerCommits.isEmpty())

                val horizontal = beginDrag(surface, x, y)
                moveDrag(surface, horizontal, x - 200f, y + 10f, 100L)
                moveDrag(surface, horizontal, x - 500f, y + 100f, 200L)
                dispatch(surface, horizontal.downTime, horizontal.downTime + 300L,
                    MotionEvent.ACTION_UP, x - 500f, y + 100f)
                assertEquals(listOf(2), sink.pagerCommits)
                assertEquals(listOf("connected-after-song"), sink.pagerSongIds)
            } finally {
                root.removeAllViews()
            }
        }
    }

    @Test
    fun bottomLyricsSwipeOpensFromBothScreenEdges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var surface: PlayerSurfaceView
        val sink = RecordingSink()
        activityRule.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            surface = PlayerSurfaceView(host)
            val density = host.resources.displayMetrics.density
            val initial = completeOriginalLoopSnapshot()
            val snapshot = initial.copy(toolDetail = initial.toolDetail.copy(
                activity = initial.toolDetail.activity.copy(lyricsAvailable = true)
            ))
            surface.bind(
                snapshot = snapshot,
                actionSink = sink,
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = 1_200,
                heightPx = 2_800,
                density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
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
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            root.addView(surface, ViewGroup.LayoutParams(1_200, 2_800))
            surface.measure(exactly(1_200), exactly(2_800))
            surface.layout(0, 0, 1_200, 2_800)
        }
        awaitNextFrame(instrumentation)
        activityRule.scenario.onActivity { host ->
            val density = host.resources.displayMetrics.density
            try {
                val visibleBounds = Rect()
                assertTrue(surface.getLocalVisibleRect(visibleBounds))
                for (x in listOf(visibleBounds.left + 2f, visibleBounds.right - 2f)) {
                    val startY = visibleBounds.bottom - 70f * density
                    val gesture = beginDrag(surface, x, startY)
                    val endY = startY - 80f * density
                    moveDrag(surface, gesture, x, endY, 100L)
                    dispatch(surface, gesture.downTime, gesture.downTime + 200L,
                        MotionEvent.ACTION_UP, x, endY)
                }
                assertEquals(listOf(PlayerSurfaceTool.LYRICS, PlayerSurfaceTool.LYRICS), sink.tools)
                assertEquals(0, sink.scrubBegins)
            } finally {
                (surface.parent as? ViewGroup)?.removeView(surface)
            }
        }
    }

    @Test
    fun sourceChangeKeepsTheTimelineStillUntilFullSizeMixerControlsAppear() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val width = (432f * density).toInt()
        val height = (860f * density).toInt()
        val stems = completeStemsMixerSnapshot()
        val originalSeed = completeOriginalLoopSnapshot()
        val original = originalSeed.copy(
            timeline = originalSeed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
            toolDetail = stems.toolDetail
        )
        val incoming = original.copy(revision = 100L, sourceMixer = FullPlayerSourceMixerPacket(
            revision = 100L, source = stems.sourceMixer.source.copy(revision = 100L),
            mixer = PlayerSurfaceMixerPacket(100L, 100L,
                IntArray(6, stems.sourceMixer.mixer::laneKeyAt),
                FloatArray(6, stems.sourceMixer.mixer::volumeAt), IntArray(6)),
            lanes = stems.sourceMixer.lanes()))
        instrumentation.runOnMainSync {
            val surface = PlayerSurfaceView(context)
            val closed = portraitPlayerToolMotionFrame(432f, 860f, density, 0f, 0f)
            val opened = portraitPlayerToolMotionFrame(432f, 860f, density, 0f, 1f)
            fun base(progress: Float) {
                val layout = interpolatePortraitPlayerLayout(closed.layout, opened.layout,
                    PlayerMotionPolicy.sourceLayoutProgress(progress))
                surface.updateRouteBaseMetrics(1f, PlayerMotionPolicy.artworkAlpha(1f, progress),
                    layout.headerHeightDp * density, layout.headerGapDp * density,
                    layout.identityHeightDp * density, layout.identityGapDp * density,
                    layout.compactChrome, layout.listSpacingDp * density, 1f,
                    layout.seekerClockTopClearanceDp * density, layout.seekerTrackHeightDp * density,
                    layout.transportHeightDp * density, layout.transportPlaySizeDp * density)
            }
            fun target(stemsVisible: Boolean, epoch: Long, duration: Int) {
                val frame = if (stemsVisible) opened else closed
                val layout = frame.layout
                val top = portraitPlayerControlTopDp(layout, stemsVisible) * density
                surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = original.owner.generation, selectionEpoch = epoch,
                    selectedTool = PlayerSurfaceTool.NONE, durationMs = duration,
                    expandedArtworkSizePx = layout.artworkSizeDp * density,
                    mixerCardHeightPx = layout.mixerCardHeightDp * density,
                    mixerSpacingPx = layout.mixerSpacingDp * density,
                    listSpacingPx = layout.listSpacingDp * density,
                    seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                    transportHeightPx = layout.transportHeightDp * density,
                    controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density,
                        top + portraitPlayerControlStackHeightPx(frame, density, stemsVisible)),
                    detailViewportHeightPx = 0f
                ))
            }
            surface.bind(original, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            base(0f)
            target(false, 1L, 0)
            val initialGeometry = requireNotNull(surface.semanticsGeometry())
            val initialTop = initialGeometry.seeker.top
            surface.bind(incoming, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            target(true, 2L, 780)
            val advance = PlayerSurfaceView::class.java.getDeclaredMethod("advanceToolMotion", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val start = System.nanoTime()
            val duration = StemwiseSemanticMotion.token(StemwiseMotionEvent.PLAYER_SOURCE_CHANGE).durationMs * 1_000_000L
            advance.invoke(surface, start)
            for (progress in listOf(.2f, .5f, .65f, 1f)) {
                base(progress)
                advance.invoke(surface, start + (duration * progress).toLong())
                val geometry = requireNotNull(surface.semanticsGeometry())
                if (progress <= .65f) assertEquals("Mixer insertion moved the timeline before resize", initialTop, geometry.seeker.top, 1f)
                if (progress == .2f) assertEquals("Fading artwork changed size", initialGeometry.artwork.width, geometry.artwork.width, 1f)
                if (progress == .5f) {
                    assertEquals(6, geometry.mixerLaneCount)
                    assertEquals(minOf(app.stemwise.ui.STEMS_ROW_HEIGHT_DP,
                        opened.layout.mixerCardHeightDp) * density, geometry.mixerLaneAt(0).volume.height, 1f)
                }
                val bitmap = draw(surface, width, height)
                assertTrue("Source transition rendered an empty frame", hasVisiblePixels(bitmap))
                bitmap.recycle()
            }
            val outgoing = original.copy(revision = 200L, sourceMixer = FullPlayerSourceMixerPacket(
                200L, original.sourceMixer.source.copy(revision = 200L),
                PlayerSurfaceMixerPacket(200L, 200L, IntArray(0), FloatArray(0), IntArray(0)),
                emptyList()))
            // Compose's endpoint may precede the independent native source publication.
            target(false, 3L, 780)
            surface.bind(outgoing, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            target(false, 3L, 780)
            val reverseStart = start + duration + 1L
            advance.invoke(surface, reverseStart)
            for (progress in listOf(.8f, .5f)) {
                base(progress)
                advance.invoke(surface, reverseStart + (duration * (1f - progress)).toLong())
                val bitmap = draw(surface, width, height)
                assertTrue("Reverse source transition rendered an empty frame", hasVisiblePixels(bitmap))
                bitmap.recycle()
            }
            val retainedMixer = PlayerSurfaceView::class.java.getDeclaredField("outgoingMixerPicture")
                .apply { isAccessible = true }
            val sourceMoving = PlayerSurfaceView::class.java.getDeclaredField("sourceMotionActive")
                .apply { isAccessible = true }
            val mediaAlpha = PlayerSurfaceView::class.java.getDeclaredField("artworkAlpha")
                .apply { isAccessible = true }
            assertTrue("Reverse phase did not retain outgoing mixer pixels", retainedMixer.get(surface) != null)
            assertTrue(sourceMoving.getBoolean(surface))
            base(0f)
            // Accessibility motion changes do not create a new selection epoch or source target.
            target(false, 3L, 0)
            assertFalse("Zero-duration retarget left the source clock running", sourceMoving.getBoolean(surface))
            assertTrue("Zero-duration retarget retained outgoing mixer pixels", retainedMixer.get(surface) == null)
            assertEquals("Original media did not reach its visible endpoint", 1f, mediaAlpha.getFloat(surface), 0f)
            val restored = requireNotNull(surface.semanticsGeometry())
            assertEquals(0, restored.mixerLaneCount)
            assertEquals(initialTop, restored.seeker.top, 1f)
            assertEquals(initialGeometry.artwork.width, restored.artwork.width, 1f)
        }
    }

    @Test
    fun collapsingStemsExportsOnlyVisibleMiniGeometryAndRestoresAllSixLanes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val width = (411.43f * density).toInt()
        val height = (835.43f * density).toInt()
        val frame = portraitPlayerToolMotionFrame(width / density, height / density, density, 0f, 1f)
        val layout = frame.layout
        instrumentation.runOnMainSync {
            val surface = PlayerSurfaceView(context)
            val seeded = completeStemsMixerSnapshot()
            val snapshot = seeded.copy(timeline = seeded.timeline.copy(loopStartMs = -1L, loopEndMs = -1L))
            surface.bind(snapshot, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            for ((epoch, progress) in listOf(1f, 0f, 1f).withIndex()) {
                surface.updateRouteBaseMetrics(progress, 1f,
                    layout.headerHeightDp * density, layout.headerGapDp * density,
                    layout.identityHeightDp * density, layout.identityGapDp * density,
                    layout.compactChrome, layout.listSpacingDp * density, 1f,
                    layout.seekerClockTopClearanceDp * density, layout.seekerTrackHeightDp * density,
                    layout.transportHeightDp * density, layout.transportPlaySizeDp * density)
                val top = if (progress == 0f) (68f + layout.listSpacingDp) * density
                    else portraitPlayerControlTopDp(layout, true) * density
                val bottom = top + portraitPlayerControlStackHeightPx(frame, density, true, 0f)
                surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = snapshot.owner.generation, selectionEpoch = epoch.toLong(),
                    selectedTool = PlayerSurfaceTool.NONE, durationMs = 0,
                    expandedArtworkSizePx = layout.artworkSizeDp * density,
                    mixerCardHeightPx = layout.mixerCardHeightDp * density,
                    mixerSpacingPx = layout.mixerSpacingDp * density,
                    listSpacingPx = layout.listSpacingDp * density,
                    seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                    transportHeightPx = layout.transportHeightDp * density,
                    controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density, bottom),
                    detailViewportHeightPx = 0f
                ))
                val geometry = requireNotNull(surface.semanticsGeometry()) {
                    "Visible player semantics were withdrawn at route progress $progress"
                }
                val expectedLanes = if (progress == 0f) 0 else 6
                assertEquals(expectedLanes, surface.semanticsMixerLaneCount())
                assertEquals(expectedLanes, geometry.mixerLaneCount)
                if (progress == 0f) {
                    assertTrue(geometry.identity.width > 0f)
                    assertTrue(geometry.artwork.width > 0f)
                    assertTrue(geometry.miniPlayPause.width >= 48f * density)
                    assertEquals(0f, geometry.metronomeTool.height, 0f)
                }
            }
        }
    }

    @Test
    fun compactToolMetricsRemainInsideTheirComposeReservationAfterRouteRebind() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val width = (411.43f * density).toInt()
        val height = (835.43f * density).toInt()
        val closed = portraitPlayerLayout(411.43f, 835.43f, PlaybackSourceMode.ORIGINAL, false)
        instrumentation.runOnMainSync {
            val surface = PlayerSurfaceView(context)
            fun publishClosedRoute() = surface.updateRouteBaseMetrics(
                expansionProgress = 1f, artworkAlpha = 1f,
                expandedHeaderHeightPx = closed.headerHeightDp * density,
                expandedHeaderGapPx = closed.headerGapDp * density,
                expandedIdentityHeightPx = closed.identityHeightDp * density,
                expandedIdentityGapPx = closed.identityGapDp * density,
                compactChrome = closed.compactChrome,
                listSpacingPx = closed.listSpacingDp * density, fontScale = 1f,
                seekerClockTopClearancePx = closed.seekerClockTopClearanceDp * density,
                seekerTrackHeightPx = closed.seekerTrackHeightDp * density,
                transportHeightPx = closed.transportHeightDp * density,
                transportPlaySizePx = closed.transportPlaySizeDp * density
            )
            publishClosedRoute()
            val snapshot = completeOriginalLoopSnapshot()
            surface.bind(snapshot, RecordingSink(), PlayerSurfacePositionProvider { 10_000L },
                width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            for ((index, detailHeight) in listOf(176f, 244f, 0f).withIndex()) {
                val frame = portraitPlayerToolMotionFrame(411.43f, 835.43f, density,
                    detailHeight, 0f, loopClearanceProgress = 1f)
                val layout = frame.layout
                val top = portraitPlayerControlTopDp(layout, false) * density
                val bottom = top + portraitPlayerControlStackHeightPx(frame, density, false, 1f)
                surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = snapshot.owner.generation, selectionEpoch = index.toLong(),
                    selectedTool = PlayerSurfaceTool.TUNE, durationMs = 0,
                    expandedArtworkSizePx = layout.artworkSizeDp * density,
                    mixerCardHeightPx = 0f, mixerSpacingPx = 0f,
                    listSpacingPx = layout.listSpacingDp * density,
                    seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                    transportHeightPx = layout.transportHeightDp * density,
                    controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density, bottom),
                    detailViewportHeightPx = frame.detailHeightPx.toFloat()
                ))
                publishClosedRoute()
                val geometry = requireNotNull(surface.semanticsGeometry())
                assertTrue("Native tools escaped their Compose reservation for $detailHeight",
                    geometry.metronomeTool.bottom <= bottom + 2f)
                assertEquals(layout.seekerTrackHeightDp * density,
                    geometry.seeker.height, 1f)
            }
        }
    }

    @Test
    fun bothLoopBoundaryMotionEventsReachSinkWithinTenMilliseconds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sink = RecordingSink()
        val width = 1_200
        val height = 2_800
        val density = context.resources.displayMetrics.density
        val expectedInputMicros = mutableListOf<Long>()
        lateinit var surface: PlayerSurfaceView

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
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            val geometry = requireNotNull(surface.semanticsGeometry())
            val startX = trackX(geometry, 5_000f / 60_000f, density)
            val endX = trackX(geometry, 15_000f / 60_000f, density)
            val y = loopMotorCenterY(geometry, density)
            val startGesture = beginDrag(surface, startX, y)
            var startCurrentEventTime = SystemClock.uptimeMillis()
            while (startCurrentEventTime <= startGesture.downTime) {
                startCurrentEventTime = SystemClock.uptimeMillis()
            }
            val startCurrentElapsedMs = startCurrentEventTime - startGesture.downTime
            val startHistoricalElapsedMs = startCurrentElapsedMs - 1L
            expectedInputMicros += startCurrentEventTime * 1_000L
            moveBatchedDrag(
                surface,
                startGesture,
                startX + 45f,
                startX + 90f,
                y,
                startHistoricalElapsedMs,
                startCurrentElapsedMs
            )
            endDrag(surface, startGesture, startX + 90f, y, startCurrentElapsedMs)
            val endGesture = beginDrag(surface, endX, y)
            var endEventTime = SystemClock.uptimeMillis()
            while (endEventTime <= endGesture.downTime) {
                endEventTime = SystemClock.uptimeMillis()
            }
            val endElapsedMs = endEventTime - endGesture.downTime
            expectedInputMicros += (endGesture.downTime + endElapsedMs) * 1_000L
            moveDrag(
                surface,
                endGesture,
                endX - 90f,
                y,
                endElapsedMs
            )
            endDrag(surface, endGesture, endX - 90f, y, endElapsedMs)
            assertEquals("B movement must preserve A", 5_000L, sink.loopBoundaryMoves.last().startMs)
        }

        assertEquals(
            listOf(FullPlayerLoopBoundary.START, FullPlayerLoopBoundary.END),
            sink.loopBoundaryMoveBoundaries
        )
        assertEquals(
            "A/B MotionEvent timestamps were not forwarded exactly",
            expectedInputMicros,
            sink.loopBoundaryMoves.map(LoopBoundaryMove::inputAtMicros)
        )
        sink.loopBoundaryMoves.forEach { move ->
            val latencyMicros = move.sinkAtMicros - move.inputAtMicros
            assertTrue(
                "${move.boundary} MotionEvent-to-sink latency was ${latencyMicros}us",
                latencyMicros in 0L until 10_000L
            )
        }
    }

    @Test
    fun rejectedBatchedBCrossingIsRetriedWithoutAnotherFingerCrossing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sink = RecordingSink(rejectFirstCapturedMove = true)
        val width = 1_200
        val height = 2_800
        val density = context.resources.displayMetrics.density
        var cursorMs = 10_000L
        lateinit var surface: PlayerSurfaceView

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
                positionProvider = PlayerSurfacePositionProvider { cursorMs },
                widthPx = width,
                heightPx = height,
                density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            val geometry = requireNotNull(surface.semanticsGeometry())
            val endX = trackX(geometry, 15_000f / 60_000f, density)
            val crossedX = trackX(geometry, 9_000f / 60_000f, density)
            val y = loopMotorCenterY(geometry, density)
            val gesture = beginDrag(surface, endX, y)
            moveBatchedDrag(surface, gesture, crossedX, endX, y, 1L, 2L)
            cursorMs = 5_000L
            moveDrag(surface, gesture, endX, y, 3L)
            endDrag(surface, gesture, endX, y, 4L)
        }

        assertEquals(2, sink.loopBoundaryMoves.size)
        val rejected = sink.loopBoundaryMoves[0]
        val retried = sink.loopBoundaryMoves[1]
        assertTrue(rejected.capturedBMs >= 0L)
        assertEquals(rejected.capturedBMs, retried.capturedBMs)
        assertEquals(rejected.inputAtMicros, retried.inputAtMicros)
        assertEquals(10_000L, retried.transportPositionMs)
    }

    @app.stemwise.qualification.ManagedAudioTest

    @Test
    fun realHeldBTracksCursorAndEveryFastCrossingWrapsWithinTheOutputServiceBudget() {
        // Complete the crossings before reporting timing failures, so a slow emulator input
        // delivery cannot conceal a later PCM boundary miss. Atomic publication stays below 10 ms.
        val timingFailures = mutableListOf<String>()
        fun checkTiming(message: String, accepted: Boolean) {
            if (!accepted) {
                timingFailures += message
                // Retain every failure outside the timed input path. A bounded assertion
                // excerpt must not hide a later native violation behind delivery delays.
                Log.w("StemwiseLoopProof", "timingFailure=$message")
            }
        }
        requireDisposablePlaybackTarget()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as StemwiseApplication
        val playback = application.services.audioPlayback
        lateinit var host: ComponentActivity
        lateinit var root: FrameLayout
        lateinit var phaseLabel: TextView
        activityRule.scenario.onActivity { host = it }
        instrumentation.runOnMainSync {
            // This visible gesture fixture must retain a real window even if the phone locks
            // between qualification runs. The keyguard itself stays locked.
            root = FrameLayout(host).apply {
                setBackgroundColor(android.graphics.Color.rgb(53, 16, 70))
            }
            phaseLabel = TextView(host).apply {
                text = "Preparing real loop audio…"
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.rgb(53, 16, 70))
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(24, 18, 24, 18)
            }
            root.addView(
                phaseLabel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            host.setContentView(root)
        }
        instrumentation.waitForIdleSync()
        awaitNextFrame(instrumentation)
        val preparationScreen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertTrue(
            "Loop qualification preparation screen rendered as black",
            regionHasColorPixels(
                preparationScreen,
                Rect(
                    preparationScreen.width / 4,
                    preparationScreen.height / 4,
                    preparationScreen.width * 3 / 4,
                    preparationScreen.height * 3 / 4
                )
            )
        )

        val directory = File(application.cacheDir, "visible-loop-motion-latency").apply {
            check(isDirectory || mkdirs())
        }
        application.admitCurrentPlaybackLibrarySnapshot()
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        try {
            val durationMs = 45_000L
            val original = File(directory, "original.wav")
            val stemOne = File(directory, "stem-one.wav")
            val stemTwo = File(directory, "stem-two.wav")
            writeSineWave(original, durationMs, 220.0)
            writeSineWave(stemOne, durationMs, 330.0)
            writeSineWave(stemTwo, durationMs, 440.0)
            val song = Song(
                id = "visible-loop-motion-latency",
                videoId = "visible-loop-motion-latency",
                title = "Visible B stress",
                artist = "Stemwise qualification",
                durationMs = durationMs,
                state = SongState.READY,
                accent = Color.Cyan,
                hasStems = true,
                artworkGlyph = "B",
                sourcePath = PlaybackContentFixture.publish(application, original),
                sourceCodec = "pcm_s16le",
                sourceContainer = "wav"
            )
            val remaining = File(stemTwo.parentFile, "remaining.wv")
            PlaybackContentFixture.writeRemainingTone(application, remaining, durationMs, 330.0, 0.06)
            val stems = listOf(
                loopProofStemAsset("bass", stemOne, durationMs),
                loopProofStemAsset("drums", stemTwo, durationMs),
                loopProofStemAsset("guitar", stemOne, durationMs),
                loopProofStemAsset("remaining", remaining, durationMs),
                loopProofStemAsset("piano", stemOne, durationMs),
                loopProofStemAsset("vocals", stemTwo, durationMs)
            )

            instrumentation.runOnMainSync {
                playback.startQueue(listOf(song), shuffle = false, autoplay = true)
            }
            awaitPlayback(8_000L) {
                playback.state.value.songId == song.id && playback.state.value.playing
            }
            val lease = checkNotNull(playback.acquireControlLease(song.id))
            val activation = runBlocking {
                withTimeout(60_000L) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                }
            }
            assertTrue("Could not prepare six-stem loop owner: $activation", activation.isSuccess)
            awaitPlayback(60_000L) {
                playback.state.value.loopAvailable &&
                    playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                    playback.nativeDiagnosticsSnapshotForTest()?.nativeRenderReadiness
                        ?.currentSongCacheReadyForSelectedOwner == true
            }

            var loopA = 0L
            var minimumB = 0L
            var expandedB = 0L
            var beforeSeed = 0L
            var seedAccepted = false
            instrumentation.runOnMainSync {
                val presented = requireNotNull(
                    playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
                )
                val cursorMs = presented.lastTimelineFrame / 48L
                assertTrue(
                    "Playback reached $cursorMs ms before visible loop setup",
                    cursorMs < durationMs - 12_000L
                )
                loopA = (cursorMs - 25L).coerceAtLeast(0L)
                minimumB = loopA + 100L
                expandedB = loopA + 10_000L
                beforeSeed = presented.loopPolicyAdoptedRevision
                seedAccepted = playback.setLoop(
                    loopA,
                    expandedB,
                    lease,
                    cursorMs,
                    publishPresentation = false,
                    inputEventAtMonotonicMicros = SystemClock.uptimeMillis() * 1_000L
                )
            }
            assertTrue("Could not install the initial wide loop", seedAccepted)
            val seedRevision = requireNotNull(playback.nativeDiagnosticsSnapshotForTest()?.output)
                .loop.latestDemandRevision
            assertTrue("The accepted seed must advance the native revision", seedRevision > beforeSeed)
            awaitPlayback(4_000L) {
                playback.nativeDiagnosticsSnapshotForTest()
                    ?.runtime?.presentedPosition?.let { position ->
                        position.loopPolicyAdoptedRevision == seedRevision &&
                            position.lastTimelineFrame in loopA * 48L until expandedB * 48L
                    } == true
            }
            val seedDiagnostics = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
            val seedPosition = requireNotNull(seedDiagnostics.runtime.presentedPosition)
            val expectedOwner = seedPosition.ownership
            val baselineOutput = requireNotNull(seedDiagnostics.output)
            val baselineLoop = baselineOutput.loop
            val adoptionBudgetMicros = consumerAdoptionBudgetMicros(baselineOutput)
            var maximumAdoptionMicros = 0L
            var maximumInputMicros = 0L
            data class VisibleCapture(
                val ordinal: Long,
                val revision: Long,
                val gestureId: Long,
                val inputAtMicros: Long,
                val aMs: Long,
                val bMs: Long,
                val cursorMs: Long,
                val inputDeliveryLatencyMicros: Long,
                val beforeCallback: Long,
                val beforeTimelineFrame: Long,
                val journalAfterSequence: Long,
                val captureCountBefore: Long,
                val cutoverCountBefore: Long,
                val missCountBefore: Long
            )

            val acceptedCommands = AtomicLong()
            val latestAcceptedRevision = AtomicLong(seedRevision)
            val latestInputDeliveryLatencyMicros = AtomicLong()
            val captureSerial = AtomicLong()
            val pendingCaptures = ConcurrentLinkedQueue<VisibleCapture>()
            val rejectedCommand = AtomicReference<String?>(null)
            val inputBaseline = AtomicReference(seedDiagnostics)
            val sink = RecordingSink {
                    startMs,
                    endMs,
                    transportPositionMs,
                    inputAtMicros,
                    heldBGestureId,
                    capturedBMs ->
                val deliveredAtMicros = SystemClock.uptimeMillis() * 1_000L
                val inputDeliveryLatencyMicros =
                    if (inputAtMicros != 0L && deliveredAtMicros >= inputAtMicros) {
                        deliveredAtMicros - inputAtMicros
                    } else {
                        0L
                    }
                if (inputDeliveryLatencyMicros >= 8_000L) {
                    android.os.Trace.beginSection("StemWise.inputDelayUs=$inputDeliveryLatencyMicros")
                    android.os.Trace.endSection()
                    Log.i("StemwiseLoopProof", "lateInput eventUs=$inputAtMicros " +
                        "deliveredUs=$deliveredAtMicros delayUs=$inputDeliveryLatencyMicros")
                }
                latestInputDeliveryLatencyMicros.set(inputDeliveryLatencyMicros)
                // The previous accepted/settled observation supplies the baseline without asking
                // the render worker for readiness inside the measured input-to-publication path.
                val before = inputBaseline.get()
                val beforeLoop = requireNotNull(before.output).loop
                val beforePosition = requireNotNull(before.runtime.presentedPosition)
                assertEquals(expectedOwner, beforePosition.ownership)
                assertEquals("Input baseline omitted an accepted revision",
                    latestAcceptedRevision.get(), beforeLoop.latestDemandRevision)
                assertTrue("The previous capture must be validated before another input",
                    pendingCaptures.isEmpty())
                assertEquals("The previous capture must complete before another input",
                    beforeLoop.acceptedCaptureCount, beforeLoop.successfulCaptureCutoverCount)
                if (beforeLoop.acceptedCaptureCount > 0L) {
                    assertTrue("The previous cutover must have a later presented callback",
                        beforePosition.callbackSequence > beforeLoop.latestCutoverCallbackSequence)
                }
                // Native capture can precede the display cursor; retain every admitted edge.
                val journalAfterSequence =
                    playback.presentationJournalForTest(Long.MAX_VALUE).writeSequence
                val accepted = playback.setLoop(
                    startMs,
                    endMs,
                    lease,
                    transportPositionMs,
                    publishPresentation = false,
                    inputEventAtMonotonicMicros = inputAtMicros,
                    heldBGestureId = heldBGestureId,
                    capturedBMs = capturedBMs
                )
                if (accepted) {
                    val revision = seedRevision + acceptedCommands.incrementAndGet()
                    latestAcceptedRevision.set(revision)
                    val after = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
                    val afterLoop = requireNotNull(after.output).loop
                    assertEquals("Accepted B revision was not published", revision, afterLoop.latestDemandRevision)
                    val captureDelta = afterLoop.acceptedCaptureCount - beforeLoop.acceptedCaptureCount
                    assertTrue("One B sample admitted $captureDelta captures", captureDelta in 0L..1L)
                    if (capturedBMs >= 0L && captureDelta == 0L) {
                        val priorCaptureStillInFlight = beforeLoop.latestCaptureRevision > 0L &&
                            beforeLoop.latestCaptureGestureId == heldBGestureId &&
                            beforeLoop.latestCaptureAFrame == startMs * 48L &&
                            (beforeLoop.successfulCaptureCutoverCount < beforeLoop.acceptedCaptureCount ||
                                (beforeLoop.latestCutoverCaptureRevision == beforeLoop.latestCaptureRevision &&
                                    beforePosition.callbackSequence <= beforeLoop.latestCutoverCallbackSequence))
                        assertTrue("Visible B crossing was neither captured nor owned by an unpresented prior capture",
                            priorCaptureStillInFlight)
                    }
                    if (captureDelta == 1L) {
                        assertEquals(revision, afterLoop.latestCaptureRevision)
                        assertEquals(heldBGestureId, afterLoop.latestCaptureGestureId)
                        assertEquals(startMs * 48L, afterLoop.latestCaptureAFrame)
                        assertEquals((capturedBMs.takeIf { it >= 0L } ?: endMs) * 48L,
                            afterLoop.latestCaptureBFrameExclusive)
                        if (capturedBMs < 0L && afterLoop.latestCapturePresentedFrame < endMs * 48L) {
                            throw AssertionError("Native capture had no crossed input or actual source cursor: " +
                                "A=$startMs B=$endMs inputCursor=$transportPositionMs capturedB=$capturedBMs " +
                                "nativeFrame=${afterLoop.latestCapturePresentedFrame} " +
                                "inferred=${afterLoop.latestCaptureInferred} " +
                                "crossed=${afterLoop.latestDemandBoundaryCrossed} " +
                                "armed=${afterLoop.latestDemandBoundaryArmed} " +
                                "before=${before.controllerLoopProtocol}")
                        }
                        pendingCaptures.add(
                            VisibleCapture(
                                ordinal = captureSerial.incrementAndGet(),
                                revision = revision,
                                gestureId = heldBGestureId,
                                inputAtMicros = inputAtMicros,
                                aMs = startMs,
                                bMs = capturedBMs.takeIf { it >= 0L } ?: endMs,
                                cursorMs = transportPositionMs,
                                inputDeliveryLatencyMicros = inputDeliveryLatencyMicros,
                                beforeCallback = beforePosition.callbackSequence,
                                beforeTimelineFrame = beforePosition.lastTimelineFrame,
                                journalAfterSequence = journalAfterSequence,
                                captureCountBefore = beforeLoop.acceptedCaptureCount,
                                cutoverCountBefore = beforeLoop.successfulCaptureCutoverCount,
                                missCountBefore = beforeLoop.processBoundaryMissCount
                            )
                        )
                    }
                    inputBaseline.set(after)
                } else {
                    rejectedCommand.compareAndSet(
                        null,
                        "Rejected B update A=$startMs B=$endMs cursor=$transportPositionMs"
                    )
                }
                accepted
            }

            fun assertPlaybackRunning(label: String) {
                val state = playback.state.value
                if (!(state.playWhenReady && state.playing && !state.ended &&
                    state.error == null && state.sourceMode == PlaybackSourceMode.STEMS)) {
                    throw AssertionError("$label interrupted playback: $state")
                }
            }

            fun awaitRevision(revision: Long, label: String) {
                val deadline = SystemClock.elapsedRealtime() + 1_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    assertPlaybackRunning(label)
                    val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
                    val position = diagnostics?.runtime?.presentedPosition
                    if (position != null && position.loopPolicyAdoptedRevision == revision) {
                        assertEquals("$label adopted the wrong owner", expectedOwner, position.ownership)
                        val inputDeliveryLatencyMicros =
                            latestInputDeliveryLatencyMicros.get()
                        checkTiming(
                            "$label Android input delivery stalled for " +
                                "${inputDeliveryLatencyMicros}us",
                            inputDeliveryLatencyMicros in 0L until 10_000L
                        )
                        checkTiming(
                            "$label input-to-atomic latency was " +
                                "${position.loopPolicyInputToAtomicPublicationLatencyMicros}us",
                            position.loopPolicyInputToAtomicPublicationLatencyMicros in
                                0L until 10_000L
                        )
                        maximumAdoptionMicros = maxOf(maximumAdoptionMicros,
                            position.loopPolicyAdoptionLatencyMicros)
                        maximumInputMicros = maxOf(maximumInputMicros, inputDeliveryLatencyMicros)
                        val atomicToCallbackLatencyMicros =
                            position.loopPolicyInputToCallbackAdoptionLatencyMicros -
                                position.loopPolicyInputToAtomicPublicationLatencyMicros
                        checkTiming(
                            "$label revision=$revision native admission-to-callback latency was " +
                                "${position.loopPolicyAdoptionLatencyMicros}us",
                            position.loopPolicyAdoptionLatencyMicros in 0L until adoptionBudgetMicros
                        )
                        checkTiming(
                            "$label atomic-to-callback latency was " +
                                "${atomicToCallbackLatencyMicros}us; end-to-end=" +
                                "${position.loopPolicyInputToCallbackAdoptionLatencyMicros}us " +
                                "(input=${inputDeliveryLatencyMicros}us, " +
                                "atomic=${position.loopPolicyInputToAtomicPublicationLatencyMicros}us, " +
                                "callbackGap=${diagnostics.output?.callbackStartGapMicros}us, " +
                                "maxCallbackGap=${diagnostics.output?.maximumCallbackStartGapMicros}us, " +
                                "burst=${diagnostics.output?.framesPerBurst}, " +
                                "deviceBuffer=${diagnostics.output?.outputBufferSizeFrames})",
                            atomicToCallbackLatencyMicros in 0L until adoptionBudgetMicros
                        )
                        inputBaseline.set(diagnostics)
                        return
                    }
                    SystemClock.sleep(1L)
                }
                throw AssertionError(
                    "$label revision $revision was not physically adopted; " +
                        "native=${playback.nativeDiagnosticsSnapshotForTest()}"
                )
            }

            fun awaitCapture(capture: VisibleCapture) {
                val deadline = SystemClock.elapsedRealtime() + 1_000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    assertPlaybackRunning("B capture ${capture.ordinal}")
                    val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
                    val position = diagnostics?.runtime?.presentedPosition
                    val loop = diagnostics?.output?.loop
                    if (loop != null &&
                        (loop.processBoundaryMissCount != capture.missCountBefore ||
                            loop.latestMissCaptureRevision == capture.revision)
                    ) {
                        throw AssertionError(
                            "B capture ${capture.ordinal} emitted through B=${capture.bMs}ms; " +
                                "loop=$loop position=$position"
                        )
                    }
                    if (loop != null && position != null &&
                        loop.successfulCaptureCutoverCount ==
                            capture.cutoverCountBefore + 1L
                    ) {
                        if (position.callbackSequence <= loop.latestCutoverCallbackSequence ||
                            position.loopPolicyAdoptedRevision != capture.revision
                        ) {
                            SystemClock.sleep(1L)
                            continue
                        }
                        assertEquals(
                            "B capture ${capture.ordinal} count",
                            capture.captureCountBefore + 1L,
                            loop.acceptedCaptureCount
                        )
                        assertEquals(capture.revision, loop.latestCaptureRevision)
                        assertEquals(capture.gestureId, loop.latestCaptureGestureId)
                        assertEquals(capture.aMs * 48L, loop.latestCaptureAFrame)
                        assertEquals(capture.bMs * 48L, loop.latestCaptureBFrameExclusive)
                        assertEquals(capture.revision, loop.latestCutoverCaptureRevision)
                        assertEquals(capture.gestureId, loop.latestCutoverGestureId)
                        assertEquals(expectedOwner.generation, loop.latestCutoverGeneration)
                        assertEquals(
                            expectedOwner.selectionToken,
                            loop.latestCutoverSelectionToken
                        )
                        assertEquals(capture.aMs * 48L, loop.latestCutoverAFrame)
                        checkTiming(
                            "B capture ${capture.ordinal} Android input delivery stalled for " +
                                "${capture.inputDeliveryLatencyMicros}us",
                            capture.inputDeliveryLatencyMicros in 0L until 10_000L
                        )
                        checkTiming(
                            "B capture ${capture.ordinal} cut over in " +
                                "${loop.latestCutoverInputLatencyMicros}us " +
                                "(cursorMs=${capture.cursorMs}, beforeFrame=${capture.beforeTimelineFrame}, " +
                                "publicationFrame=${loop.latestCapturePresentedFrame}, " +
                                "aMs=${capture.aMs}, bMs=${capture.bMs}, " +
                                "inferred=${loop.latestCaptureInferred})",
                            loop.latestCutoverInputLatencyMicros in 0L until adoptionBudgetMicros
                        )
                        assertTrue(
                            "B capture ${capture.ordinal} reused an old callback",
                            loop.latestCutoverCallbackSequence > capture.beforeCallback &&
                                position.callbackSequence >= loop.latestCutoverCallbackSequence
                        )
                        assertEquals(expectedOwner, position.ownership)
                        assertEquals(capture.revision, position.loopPolicyAdoptedRevision)
                        // The observer can arrive several callbacks after A. Check the actual
                        // cutover callback instead of requiring the live cursor to remain at A.
                        val journal = playback.presentationJournalForTest(capture.journalAfterSequence)
                        assertTrue("B capture journal was incoherent", journal.coherent)
                        assertEquals("B capture journal lost spans", 0L, journal.overflowCount)
                        val cutoverSpans = journal.entries.filter {
                            it.callbackSequence == loop.latestCutoverCallbackSequence
                        }
                        assertTrue("B capture journal omitted cutover", cutoverSpans.isNotEmpty())
                        cutoverSpans.forEach { span ->
                            assertEquals(expectedOwner.generation, span.generation)
                            assertEquals(expectedOwner.selectionToken, span.selectionToken)
                            assertEquals("B capture PCM continuity failed", 0L, span.continuityViolationCount)
                        }
                        assertTrue(
                            "B capture ${capture.ordinal} did not present exact A=${capture.aMs}ms: $cutoverSpans",
                            cutoverSpans.any { it.firstTimelineFrame == capture.aMs * 48L }
                        )
                        Log.i(
                            "StemwiseLoopProof",
                                "capture=${capture.ordinal} rev=${capture.revision} " +
                                "gesture=${capture.gestureId} A=${capture.aMs} B=${capture.bMs} " +
                                "cursor=${capture.cursorMs} " +
                                "beforeFrame=${capture.beforeTimelineFrame} " +
                                "publicationFrame=${loop.latestCapturePresentedFrame} " +
                                "deliveryUs=${capture.inputDeliveryLatencyMicros} " +
                                "cutoverCb=${loop.latestCutoverCallbackSequence} " +
                                "latencyUs=${loop.latestCutoverInputLatencyMicros}"
                        )
                        inputBaseline.set(diagnostics)
                        return
                    }
                    SystemClock.sleep(1L)
                }
                throw AssertionError(
                    "B capture ${capture.ordinal} did not cut over; expected=$capture " +
                        "native=${playback.nativeDiagnosticsSnapshotForTest()}"
                )
            }

            fun setPhase(label: String) {
                instrumentation.runOnMainSync { phaseLabel.text = label }
                awaitNextFrame(instrumentation)
            }

            lateinit var surface: PlayerSurfaceView
            lateinit var geometry: PlayerSurfaceSemanticsGeometry
            val density = host.resources.displayMetrics.density
            var viewWidth = 0
            var viewHeight = 0
            instrumentation.runOnMainSync {
                viewWidth = root.width
                viewHeight = root.height
                assertTrue("Qualification host has no layout", viewWidth > 0 && viewHeight > 0)
                val fixture = completeStemsLoopSnapshot()
                val loopDetail = fixture.toolDetail.detail as FullPlayerDetailState.Loop
                val snapshot = fixture.copy(
                    owner = fixture.owner.copy(generation = expectedOwner.generation),
                    timeline = fixture.timeline.copy(
                        durationMs = durationMs,
                        loopStartMs = loopA,
                        loopEndMs = expandedB
                    ),
                    toolDetail = fixture.toolDetail.copy(
                        detail = loopDetail.copy(
                            durationMs = durationMs,
                            loopStartMs = loopA,
                            loopEndMs = expandedB
                        )
                    )
                )
                surface = PlayerSurfaceView(host).apply {
                    setBackgroundColor(android.graphics.Color.rgb(7, 5, 11))
                    updateRouteBaseMetrics(
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
                    bind(
                        snapshot = snapshot,
                        actionSink = sink,
                        positionProvider = PlayerSurfacePositionProvider {
                            playback.currentStateSnapshot().positionMs.coerceIn(0L, durationMs)
                        },
                        widthPx = viewWidth,
                        heightPx = viewHeight,
                        density = density,
                        direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
                    )
                    val horizontalInset = 16f * density
                    setControlBounds(
                        horizontalInset,
                        viewHeight * .27f,
                        viewWidth - horizontalInset,
                        viewHeight - 24f * density
                    )
                }
                root.removeAllViews()
                root.addView(
                    surface,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                root.addView(
                    phaseLabel,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP
                    )
                )
                phaseLabel.apply {
                    setTextColor(android.graphics.Color.rgb(255, 167, 229))
                    setBackgroundColor(android.graphics.Color.argb(220, 29, 11, 43))
                    textSize = 15f
                }
            }
            instrumentation.waitForIdleSync()
            awaitNextFrame(instrumentation)
            awaitNextFrame(instrumentation)

            val visibleRect = Rect()
            val screenOrigin = IntArray(2)
            instrumentation.runOnMainSync {
                geometry = requireNotNull(surface.semanticsGeometry())
                assertTrue("Loop surface is detached", surface.isAttachedToWindow)
                assertTrue("Loop surface is not shown", surface.isShown)
                assertTrue("Loop surface is not laid out", surface.isLaidOut)
                assertTrue("Loop activity does not own focus", surface.hasWindowFocus())
                assertTrue(
                    "Loop surface has no global visible rectangle",
                    surface.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
                )
                surface.getLocationOnScreen(screenOrigin)
                val bScreenX = screenOrigin[0] + trackX(
                    geometry,
                    expandedB.toFloat() / durationMs.toFloat(),
                    density
                ).toInt()
                val bScreenY = screenOrigin[1] +
                    loopMotorCenterY(geometry, density).toInt()
                assertTrue(
                    "B is outside the visible window: B=($bScreenX,$bScreenY) rect=$visibleRect",
                    visibleRect.contains(bScreenX, bScreenY)
                )
            }
            val firstScreen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val seekerScreenRect = Rect(
                (screenOrigin[0] + geometry.seeker.left).toInt(),
                (screenOrigin[1] + geometry.seeker.top).toInt(),
                (screenOrigin[0] + geometry.seeker.right).toInt(),
                (screenOrigin[1] + geometry.seeker.bottom).toInt()
            )
            assertTrue(
                "Attached loop seeker rendered as black",
                regionHasColorPixels(firstScreen, seekerScreenRect)
            )

            setPhase("Closing B onto A · 10 seconds → 100 ms")
            var fingerX = trackX(
                geometry,
                expandedB.toFloat() / durationMs.toFloat(),
                density
            )
            val fingerY = loopMotorCenterY(geometry, density)
            val beginsBefore = sink.loopBoundaryBegins
            val collapseGesture = beginWindowDrag(
                instrumentation,
                screenOrigin,
                fingerX,
                fingerY
            )
            assertEquals(beginsBefore + 1, sink.loopBoundaryBegins)
            assertEquals(FullPlayerLoopBoundary.END, sink.lastLoopBoundaryBegin)

            val collapseMovesBefore = sink.loopBoundaryMoves.size
            val collapseSteps = 120
            val collapseStartNanos = SystemClock.elapsedRealtimeNanos()
            var lastCollapsedB = expandedB
            var collapseCaptures = 0
            repeat(collapseSteps) { index ->
                val deadline = collapseStartNanos +
                    (index + 1L) * 2_000_000_000L / collapseSteps
                awaitGestureDeadline(deadline)
                fingerX = trackX(
                    geometry,
                    // The live gesture is speed-sensitive and relative. The final overshoot
                    // tests its exact minimum clamp; absolute finger X is not a time command.
                    if (index == collapseSteps - 1) 0f else
                        (expandedB - (expandedB - minimumB) *
                            (index + 1L) / collapseSteps).toFloat() / durationMs.toFloat(),
                    density
                )
                val movesBefore = sink.loopBoundaryMoves.size
                moveWindowDrag(
                    instrumentation,
                    collapseGesture,
                    screenOrigin,
                    fingerX,
                    fingerY
                )
                rejectedCommand.get()?.let { throw AssertionError(it) }
                assertEquals(
                    "Collapse MOVE ${index + 1} did not visibly publish B",
                    movesBefore + 1,
                    sink.loopBoundaryMoves.size
                )
                val move = sink.loopBoundaryMoves.last()
                assertEquals(FullPlayerLoopBoundary.END, move.boundary)
                assertTrue(
                    "Collapse moved B forward from $lastCollapsedB to ${move.endMs}",
                    move.endMs <= lastCollapsedB
                )
                lastCollapsedB = move.endMs
                val capture = pendingCaptures.poll()
                if (capture != null) {
                    awaitCapture(capture)
                    collapseCaptures++
                } else {
                    awaitRevision(latestAcceptedRevision.get(), "collapse ${index + 1}")
                }
            }
            assertEquals(
                "Collapse did not publish every visible sample",
                collapseSteps,
                sink.loopBoundaryMoves.size - collapseMovesBefore
            )
            assertEquals("B did not reach the exact 100ms minimum", minimumB, lastCollapsedB)
            assertTrue("B never crossed the live cursor while closing", collapseCaptures > 0)
            val minimumRevision = latestAcceptedRevision.get()
            awaitRevision(minimumRevision, "100ms minimum")
            val minimumPosition = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition
            )
            var minimumPhysicalRevision = minimumPosition.loopPolicyRevision
            assertTrue("100ms minimum has no physical revision", minimumPhysicalRevision != 0L)
            Log.i(
                "StemwiseLoopProof",
                "collapse moves=$collapseSteps captures=$collapseCaptures finalB=$lastCollapsedB"
            )

            val minimumWrapTarget = 200
            setPhase("100 ms A–B hold · $minimumWrapTarget physical wraps")
            val stationaryMovesBefore = sink.loopBoundaryMoves.size
            val holdEndNanos = SystemClock.elapsedRealtimeNanos() + 30_000_000_000L
            val holdJournalBaseline = playback.presentationJournalForTest(0L)
            var holdJournalSequence = holdJournalBaseline.writeSequence
            var previousFrame = -1L
            var previousPhysicalRevision = 0L
            var minimumPhysicalTransitions = 0
            var minimumWraps = 0
            while (minimumWraps < minimumWrapTarget &&
                SystemClock.elapsedRealtimeNanos() < holdEndNanos
            ) {
                moveWindowDrag(
                    instrumentation,
                    collapseGesture,
                    screenOrigin,
                    fingerX,
                    fingerY
                )
                assertPlaybackRunning("100ms hold")
                val diagnostics = requireNotNull(playback.nativeDiagnosticsSnapshotForTest())
                val position = requireNotNull(diagnostics.runtime.presentedPosition)
                val output = requireNotNull(diagnostics.output)
                assertEquals(expectedOwner, position.ownership)
                assertEquals(minimumRevision, position.loopPolicyAdoptedRevision)
                if (position.loopPolicyRevision != minimumPhysicalRevision) {
                    assertTrue(
                        "100ms physical revision regressed from $minimumPhysicalRevision to " +
                            "${position.loopPolicyRevision}",
                        position.loopPolicyRevision > minimumPhysicalRevision
                    )
                    minimumPhysicalRevision = position.loopPolicyRevision
                    minimumPhysicalTransitions++
                    assertTrue(
                        "100ms physical loop kept rebuilding",
                        minimumPhysicalTransitions <= 1
                    )
                }
                assertTrue(
                    "100ms hold emitted PCM at or beyond B",
                    position.lastTimelineFrame < minimumB * 48L
                )
                // A 96-frame route exposes only a 4 ms start window; 8 ms polling can miss
                // most wraps. Count the exact callback spans, retaining every integrity gate.
                val journal = playback.presentationJournalForTest(holdJournalSequence)
                assertTrue("100ms journal was incoherent", journal.coherent)
                assertEquals("100ms journal lost callback spans", 0L, journal.overflowCount)
                assertEquals(holdJournalBaseline.writerCollisionCount, journal.writerCollisionCount)
                assertEquals(holdJournalBaseline.invalidPublicationCount, journal.invalidPublicationCount)
                for (span in journal.entries) {
                    assertEquals(expectedOwner.generation, span.generation)
                    assertEquals(expectedOwner.selectionToken, span.selectionToken)
                    if (span.continuityViolationCount != 0L) {
                        throw AssertionError("100ms PCM continuity failed: $span; " +
                            "native=${playback.nativeDiagnosticsSnapshotForTest()}")
                    }
                    assertTrue(span.firstTimelineFrame in loopA * 48L until minimumB * 48L)
                    assertTrue(span.lastTimelineFrame in loopA * 48L until minimumB * 48L)
                    if (previousPhysicalRevision == span.firstLoopPolicyRevision &&
                        previousFrame >= 0L && span.firstTimelineFrame < previousFrame
                    ) {
                        assertEquals("100ms wrap missed exact A", loopA * 48L, span.firstTimelineFrame)
                        minimumWraps++
                    }
                    if (span.firstLoopPolicyRevision == span.lastLoopPolicyRevision &&
                        span.lastTimelineFrame < span.firstTimelineFrame
                    ) minimumWraps++
                    previousFrame = span.lastTimelineFrame
                    previousPhysicalRevision = span.lastLoopPolicyRevision
                }
                holdJournalSequence = journal.writeSequence
                assertEquals(
                    "100ms hold recorded a boundary miss",
                    baselineLoop.processBoundaryMissCount,
                    output.loop.processBoundaryMissCount
                )
                assertTrue("100ms hold stopped callback rendering", output.effectiveRenderPlaying)
                assertTrue("100ms hold emitted silence", output.pcmLevelPeak > 0f)
                SystemClock.sleep(8L)
            }
            assertTrue(
                "100ms loop completed only $minimumWraps visible physical wraps",
                minimumWraps >= minimumWrapTarget
            )
            Log.i(
                "StemwiseLoopProof",
                "minimumHold wraps=$minimumWraps physicalTransitions=$minimumPhysicalTransitions " +
                    "physicalRevision=$minimumPhysicalRevision"
            )
            assertTrue(
                "Stationary 100ms B unexpectedly published a new range",
                sink.loopBoundaryMoves.size == stationaryMovesBefore
            )
            val minimumScreen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(
                "The on-screen B handle did not visibly close onto A",
                regionDiffers(
                    firstScreen,
                    minimumScreen,
                    PlayerSurfaceBounds(
                        seekerScreenRect.left.toFloat(),
                        seekerScreenRect.top.toFloat(),
                        seekerScreenRect.right.toFloat(),
                        seekerScreenRect.bottom.toFloat()
                    )
                )
            )
            assertChangesNearX(
                label = "Minimum B handle",
                first = firstScreen,
                second = minimumScreen,
                bounds = PlayerSurfaceBounds(
                    seekerScreenRect.left.toFloat(),
                    seekerScreenRect.top.toFloat(),
                    seekerScreenRect.right.toFloat(),
                    seekerScreenRect.bottom.toFloat()
                ),
                expectedX = screenOrigin[0] + trackX(
                    geometry, minimumB.toFloat() / durationMs.toFloat(), density),
                radiusPx = 16f * density
            )

            val minimumReleaseCaptureCount =
                baselineLoop.acceptedCaptureCount + captureSerial.get()
            val minimumReleaseCutoverCount =
                baselineLoop.successfulCaptureCutoverCount + captureSerial.get()
            setPhase("Release B at 100 ms · tiny loop stays live")
            endWindowDrag(
                instrumentation,
                collapseGesture,
                screenOrigin,
                fingerX,
                fingerY
            )
            assertEquals(beginsBefore + 1, sink.loopBoundaryBegins)
            assertEquals("Minimum B was not released exactly once", 1, sink.loopBoundaryEnds)
            assertPlaybackRunning("minimum B release")
            SystemClock.sleep(350L)
            val afterMinimumRelease = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()
            )
            val afterMinimumReleasePosition = requireNotNull(
                afterMinimumRelease.runtime.presentedPosition
            )
            val afterMinimumReleaseOutput = requireNotNull(afterMinimumRelease.output)
            assertEquals(expectedOwner, afterMinimumReleasePosition.ownership)
            assertEquals(minimumRevision, afterMinimumReleasePosition.loopPolicyAdoptedRevision)
            assertEquals(minimumPhysicalRevision, afterMinimumReleasePosition.loopPolicyRevision)
            assertTrue(
                "Released 100ms loop emitted through B",
                afterMinimumReleasePosition.lastTimelineFrame < minimumB * 48L
            )
            assertEquals(
                "Releasing minimum B created a hidden capture",
                minimumReleaseCaptureCount,
                afterMinimumReleaseOutput.loop.acceptedCaptureCount
            )
            assertEquals(
                "Releasing minimum B created a hidden cutover",
                minimumReleaseCutoverCount,
                afterMinimumReleaseOutput.loop.successfulCaptureCutoverCount
            )
            assertTrue(
                "Released 100ms loop stopped rendering",
                afterMinimumReleaseOutput.effectiveRenderPlaying
            )
            assertTrue("Released 100ms loop emitted silence", afterMinimumReleaseOutput.pcmLevelPeak > 0f)

            setPhase("Reacquire overlapped B · expand far forward")
            val previousFingerX = fingerX
            var nearestBHitX = Float.NaN
            instrumentation.runOnMainSync {
                var candidateX = geometry.seeker.left
                var nearestDistance = Float.POSITIVE_INFINITY
                while (candidateX <= geometry.seeker.right) {
                    if (surface.loopBoundaryGestureTargetForQualification(
                            candidateX,
                            fingerY
                        ) == FullPlayerLoopBoundary.END
                    ) {
                        val distance = abs(candidateX - previousFingerX)
                        if (distance < nearestDistance) {
                            nearestDistance = distance
                            nearestBHitX = candidateX
                        }
                    }
                    candidateX += 1f
                }
            }
            assertTrue("Released minimum B has no touchable hit region", nearestBHitX.isFinite())
            fingerX = nearestBHitX
            Log.i(
                "StemwiseLoopProof",
                "reacquireB previousX=$previousFingerX hitX=$fingerX " +
                    "shiftPx=${abs(fingerX - previousFingerX)}"
            )
            val windowGesture = beginWindowDrag(
                instrumentation,
                screenOrigin,
                fingerX,
                fingerY
            )
            assertEquals(
                "Overlapped B could not be reacquired",
                beginsBefore + 2,
                sink.loopBoundaryBegins
            )
            assertEquals(FullPlayerLoopBoundary.END, sink.lastLoopBoundaryBegin)
            assertEquals("Reacquiring B emitted an early release", 1, sink.loopBoundaryEnds)
            SystemClock.sleep(250L)

            setPhase("Expanding B by 9.9 seconds · 2 seconds")
            val expansionMovesBefore = sink.loopBoundaryMoves.size
            var lastExpandedB = minimumB
            val expansionSteps = 120
            val expansionStartNanos = SystemClock.elapsedRealtimeNanos()
            repeat(expansionSteps) { index ->
                val deadline = expansionStartNanos +
                    (index + 1L) * 2_000_000_000L / expansionSteps
                awaitGestureDeadline(deadline)
                fingerX = trackX(
                    geometry,
                    (minimumB + (expandedB - minimumB) *
                        (index + 1L) / expansionSteps).toFloat() / durationMs.toFloat(),
                    density
                )
                val movesBefore = sink.loopBoundaryMoves.size
                moveWindowDrag(
                    instrumentation,
                    windowGesture,
                    screenOrigin,
                    fingerX,
                    fingerY
                )
                rejectedCommand.get()?.let { throw AssertionError(it) }
                assertEquals(
                    "Expansion MOVE ${index + 1} did not visibly publish B",
                    movesBefore + 1,
                    sink.loopBoundaryMoves.size
                )
                val move = sink.loopBoundaryMoves.last()
                assertEquals(FullPlayerLoopBoundary.END, move.boundary)
                assertTrue(
                    "Expansion moved B backwards from $lastExpandedB to ${move.endMs}",
                    move.endMs >= lastExpandedB
                )
                assertTrue("Expansion unexpectedly crossed B", move.capturedBMs < 0L)
                lastExpandedB = move.endMs
                awaitRevision(latestAcceptedRevision.get(), "expansion ${index + 1}")
            }
            assertEquals(
                "Expansion did not publish every visible sample",
                expansionSteps,
                sink.loopBoundaryMoves.size - expansionMovesBefore
            )
            assertTrue(
                "B expanded only ${lastExpandedB - loopA}ms",
                lastExpandedB - loopA >= 8_000L
            )
            val expandedScreen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(
                "The on-screen seeker did not change while B expanded",
                regionDiffers(
                    minimumScreen,
                    expandedScreen,
                    PlayerSurfaceBounds(
                        seekerScreenRect.left.toFloat(),
                        seekerScreenRect.top.toFloat(),
                        seekerScreenRect.right.toFloat(),
                        seekerScreenRect.bottom.toFloat()
                    )
                )
            )
            assertChangesNearX(
                label = "Expanded B handle",
                first = minimumScreen,
                second = expandedScreen,
                bounds = PlayerSurfaceBounds(
                    seekerScreenRect.left.toFloat(),
                    seekerScreenRect.top.toFloat(),
                    seekerScreenRect.right.toFloat(),
                    seekerScreenRect.bottom.toFloat()
                ),
                expectedX = screenOrigin[0] + fingerX,
                radiusPx = 16f * density
            )

            fun runVisibleSweeps(
                label: String,
                targetCaptures: Int,
                periodNanos: Long,
                stepPx: Float,
                maximumMoves: Int
            ) {
                setPhase("$label · B crossing live cursor")
                var direction = -1f
                var captures = 0
                var moves = 0
                var acceptedMoves = 0
                var consecutiveNoOps = 0
                var maximumConsecutiveNoOps = 0
                var postCaptureMoves = 0
                val startNanos = SystemClock.elapsedRealtimeNanos()
                while (captures < targetCaptures && moves < maximumMoves) {
                    val deadline = startNanos + (moves + 1L) * periodNanos
                    awaitGestureDeadline(deadline)
                    if (postCaptureMoves == 0 && direction > 0f) {
                        val current = sink.loopBoundaryMoves.last()
                        if (current.endMs >= current.transportPositionMs + 4_000L) {
                            direction = -1f
                        }
                    }
                    var nextX = (fingerX + direction * stepPx).coerceIn(
                        geometry.seeker.left + 1f,
                        geometry.seeker.right - 1f
                    )
                    if (nextX == fingerX) {
                        direction = -direction
                        postCaptureMoves = 0
                        nextX = (fingerX + direction * stepPx).coerceIn(
                            geometry.seeker.left + 1f,
                            geometry.seeker.right - 1f
                        )
                    }
                    fingerX = nextX
                    val movesBefore = sink.loopBoundaryMoves.size
                    moveWindowDrag(
                        instrumentation,
                        windowGesture,
                        screenOrigin,
                        fingerX,
                        fingerY
                    )
                    rejectedCommand.get()?.let { throw AssertionError(it) }
                    val publishedMoves = sink.loopBoundaryMoves.size - movesBefore
                    assertTrue(
                        "$label MOVE $moves published $publishedMoves B values",
                        publishedMoves in 0..1
                    )
                    if (publishedMoves == 0) {
                        consecutiveNoOps++
                        maximumConsecutiveNoOps = maxOf(
                            maximumConsecutiveNoOps,
                            consecutiveNoOps
                        )
                        assertTrue(
                            "$label stopped moving B for $consecutiveNoOps consecutive samples",
                            consecutiveNoOps <= 4
                        )
                        assertTrue(
                            "$label reset playback without a visible B update: $pendingCaptures",
                            pendingCaptures.isEmpty()
                        )
                        direction = -direction
                        postCaptureMoves = 0
                        assertPlaybackRunning("$label no-op MOVE $moves")
                        moves++
                        continue
                    }
                    acceptedMoves++
                    consecutiveNoOps = 0
                    val move = sink.loopBoundaryMoves.last()
                    val capture = pendingCaptures.poll()
                    if (capture != null) {
                        awaitCapture(capture)
                        captures++
                        postCaptureMoves = 12
                        direction = -1f
                    }
                    if (postCaptureMoves > 0) {
                        postCaptureMoves--
                        if (postCaptureMoves == 0) direction = 1f
                    }
                    assertPlaybackRunning("$label MOVE $moves")
                    moves++
                }
                assertEquals(
                    "$label produced only $captures/$targetCaptures visible captures in " +
                        "$moves moves",
                    targetCaptures,
                    captures
                )
                assertTrue(
                    "$label accepted only $acceptedMoves/$moves visible B samples",
                    acceptedMoves * 100 >= moves * 85
                )
                awaitRevision(latestAcceptedRevision.get(), "$label final B")
                Log.i(
                    "StemwiseLoopProof",
                    "$label moves=$moves accepted=$acceptedMoves " +
                        "maxConsecutiveNoOps=$maximumConsecutiveNoOps " +
                        "captures=$captures finalB=" +
                        "${sink.loopBoundaryMoves.last().endMs}"
                )
            }

            runVisibleSweeps(
                label = "Medium sweep",
                targetCaptures = 4,
                periodNanos = 1_000_000_000L / 60L,
                stepPx = 1.5f * density,
                maximumMoves = 1_400
            )
            runVisibleSweeps(
                label = "Fast sweep",
                targetCaptures = 4,
                periodNanos = 1_000_000_000L / 120L,
                stepPx = 4f * density,
                maximumMoves = 800
            )
            assertTrue("A capture remained unverified: $pendingCaptures", pendingCaptures.isEmpty())

            val expectedCaptureCount =
                baselineLoop.acceptedCaptureCount + captureSerial.get()
            val expectedCutoverCount =
                baselineLoop.successfulCaptureCutoverCount + captureSerial.get()
            val beforeReleaseLoop = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()?.output
            ).loop
            Log.i(
                "StemwiseLoopProof",
                "beforeRelease demands=${beforeReleaseLoop.acceptedDemandCount} " +
                    "captures=${beforeReleaseLoop.acceptedCaptureCount} " +
                    "cutovers=${beforeReleaseLoop.successfulCaptureCutoverCount} " +
                    "latestCaptureRev=${beforeReleaseLoop.latestCaptureRevision} " +
                    "latestCutoverRev=${beforeReleaseLoop.latestCutoverCaptureRevision} " +
                    "latestGesture=${beforeReleaseLoop.latestCutoverGestureId}"
            )
            assertEquals(
                "A hidden capture appeared before B release",
                expectedCaptureCount,
                beforeReleaseLoop.acceptedCaptureCount
            )
            assertEquals(
                "A duplicate/hidden cutover appeared before B release",
                expectedCutoverCount,
                beforeReleaseLoop.successfulCaptureCutoverCount
            )
            endWindowDrag(
                instrumentation,
                windowGesture,
                screenOrigin,
                fingerX,
                fingerY
            )
            assertEquals(
                "Visible test did not perform exactly the close/reacquire B gestures",
                beginsBefore + 2,
                sink.loopBoundaryBegins
            )
            assertEquals("Visible test did not release both B gestures", 2, sink.loopBoundaryEnds)
            assertPlaybackRunning("visible B release")

            val finalOutput = requireNotNull(
                playback.nativeDiagnosticsSnapshotForTest()?.output
            )
            Log.i(
                "StemwiseLoopProof",
                "afterRelease demands=${finalOutput.loop.acceptedDemandCount} " +
                    "captures=${finalOutput.loop.acceptedCaptureCount} " +
                    "cutovers=${finalOutput.loop.successfulCaptureCutoverCount} " +
                    "latestCaptureRev=${finalOutput.loop.latestCaptureRevision} " +
                    "latestCutoverRev=${finalOutput.loop.latestCutoverCaptureRevision} " +
                    "latestGesture=${finalOutput.loop.latestCutoverGestureId}"
            )
            assertEquals(baselineOutput.streamGeneration, finalOutput.streamGeneration)
            assertEquals(baselineOutput.reopenCount, finalOutput.reopenCount)
            assertEquals(baselineOutput.underrunCallbackCount, finalOutput.underrunCallbackCount)
            assertEquals(baselineOutput.underrunFrames, finalOutput.underrunFrames)
            assertEquals(
                baselineOutput.processUnderrunCallbackCount,
                finalOutput.processUnderrunCallbackCount
            )
            assertEquals(
                baselineOutput.processUnderrunFrames,
                finalOutput.processUnderrunFrames
            )
            assertEquals(
                baselineOutput.processConstructionCount,
                finalOutput.processConstructionCount
            )
            assertEquals(
                baselineOutput.processLiveInstanceCount,
                finalOutput.processLiveInstanceCount
            )
            assertEquals(baselineOutput.lastErrorCode, finalOutput.lastErrorCode)
            assertTrue("Loop stress required output recovery", !finalOutput.recoveryRequired)
            assertEquals(
                "Visible B stress recorded a boundary miss",
                baselineLoop.processBoundaryMissCount,
                finalOutput.loop.processBoundaryMissCount
            )
            assertEquals(
                "Visible crossings did not each cut over once",
                expectedCutoverCount,
                finalOutput.loop.successfulCaptureCutoverCount
            )
            assertEquals(
                "B release created an unrequested capture",
                expectedCaptureCount,
                finalOutput.loop.acceptedCaptureCount
            )
            Log.i("StemwiseLoopProof", "route burst=${finalOutput.framesPerBurst} buffer=${finalOutput.outputBufferSizeFrames} " +
                "maxCallbackGapUs=${finalOutput.maximumCallbackStartGapMicros} " +
                "maxAdoptionUs=$maximumAdoptionMicros maxInputUs=$maximumInputMicros")
            Log.i("StemwiseLoopProof", "continuity=PASS captures=${captureSerial.get()} " +
                "timingFailures=${timingFailures.size} details=${timingFailures.take(32)}")
            // Check route timing after all PCM continuity assertions have run.
            assertEquals(baselineOutput.xRunCount, finalOutput.xRunCount)
            assertEquals(baselineOutput.processXRunCount, finalOutput.processXRunCount)
            assertEquals(
                baselineOutput.callbackDeadlineMissCount,
                finalOutput.callbackDeadlineMissCount
            )
            assertEquals(
                baselineOutput.processCallbackDeadlineMissCount,
                finalOutput.processCallbackDeadlineMissCount
            )
            assertTrue("${timingFailures.size} timing observations failed (input/publication <10,000us; " +
                "consumer <$adoptionBudgetMicros us): " +
                timingFailures.take(32).joinToString(), timingFailures.isEmpty())
            setPhase(
                "PASS · ${captureSerial.get()} visible B crossings · " +
                    "all cutovers within the output service budget"
            )
            SystemClock.sleep(1_000L)
        } finally {
            try {
                cancelWindowGesture(instrumentation)
            } finally {
                playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
                PlaybackContentFixture.clear(application)
                check(!directory.exists() || directory.deleteRecursively())
            }
        }
    }

    @Test
    fun originalLoopMotorKeepsOneCoordinateSpaceThroughToolMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sink = RecordingSink()
        val width = 1_200
        val height = 2_800
        val density = context.resources.displayMetrics.density
        val startBounds = PlayerSurfaceBounds(48f, 620f, 1_152f, 2_400f)
        val targetBounds = PlayerSurfaceBounds(48f, 860f, 1_152f, 2_640f)
        val settled = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        var initialMotorY = 0f
        lateinit var gesture: PointerGesture
        var aX = 0f
        var motorY = 0f
        val boundaryBeginsBefore = sink.loopBoundaryBegins
        val boundaryMovesBefore = sink.loopBoundaryMoves.size
        val boundaryEndsBefore = sink.loopBoundaryEnds
        val scrubBeginsBefore = sink.scrubBegins
        val playCommandsBefore = sink.transport.count {
            it == PlayerSurfaceTransportCommand.PLAY_PAUSE
        }
        lateinit var surface: PlayerSurfaceView
        lateinit var geometry: PlayerSurfaceSemanticsGeometry
        lateinit var host: ComponentActivity
        activityRule.scenario.onActivity { host = it }

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
            val snapshot = completeOriginalLoopSnapshot()
            surface.bind(
                snapshot = snapshot,
                actionSink = sink,
                positionProvider = PlayerSurfacePositionProvider { 10_000L },
                widthPx = width,
                heightPx = height,
                density = density,
                direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
            )
            surface.setControlBounds(
                startBounds.left,
                startBounds.top,
                startBounds.right,
                startBounds.bottom
            )
            surface.setToolMotionListener(object : PlayerSurfaceToolMotionListener {
                override fun onToolMotionStarted(
                    generation: Long,
                    selectionEpoch: Long,
                    currentControlTopPx: Float,
                    currentControlBottomPx: Float
                ) {
                }

                override fun onToolMotionFrame(
                    generation: Long,
                    selectionEpoch: Long,
                    panelContentEasedFraction: Float,
                    currentControlTopPx: Float,
                    currentControlBottomPx: Float
                ) {
                    val translation = currentControlTopPx - startBounds.top
                    if (selectionEpoch == 2L && acquired.count > 0L &&
                        translation > 0f && translation < targetBounds.top - startBounds.top) {
                        // Acquire during the actual intermediate frame. A later test-thread hop
                        // can miss the whole animation on a busy device and test its endpoint.
                        geometry = requireNotNull(surface.semanticsGeometry())
                        aX = trackX(geometry, 5_000f / 60_000f, density)
                        motorY = initialMotorY + translation
                        gesture = beginDrag(surface, aX, motorY)
                        acquired.countDown()
                    }
                }

                override fun onToolMotionSettled(generation: Long, selectionEpoch: Long) {
                    if (selectionEpoch == 2L) settled.countDown()
                }
            })
            host.addContentView(surface, ViewGroup.LayoutParams(width, height))
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            assertTrue(
                surface.retargetToolMotion(
                    PlayerSurfaceToolMotionTarget(
                        generation = snapshot.owner.generation,
                        selectionEpoch = 1L,
                        selectedTool = PlayerSurfaceTool.NONE,
                        durationMs = 0,
                        expandedArtworkSizePx = 220f * density,
                        mixerCardHeightPx = 0f,
                        mixerSpacingPx = 0f,
                        listSpacingPx = 12f,
                        seekerTrackHeightPx = 120f * density,
                        transportHeightPx = 96f,
                        controlBounds = startBounds,
                        detailViewportHeightPx = 0f
                    )
                )
            )
            initialMotorY = loopMotorCenterY(requireNotNull(surface.semanticsGeometry()), density)
            assertTrue(
                surface.retargetToolMotion(
                    PlayerSurfaceToolMotionTarget(
                        generation = snapshot.owner.generation,
                        selectionEpoch = 2L,
                        selectedTool = PlayerSurfaceTool.LOOP,
                        durationMs = 800,
                        expandedArtworkSizePx = 220f * density,
                        mixerCardHeightPx = 0f,
                        mixerSpacingPx = 0f,
                        listSpacingPx = 12f,
                        seekerTrackHeightPx = 120f * density,
                        transportHeightPx = 96f,
                        controlBounds = targetBounds,
                        detailViewportHeightPx = 240f
                    )
                )
            )
        }
        assertTrue("Tool motion never exposed an intermediate input frame", acquired.await(2L, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertEquals("Intermediate A DOWN at $aX/$motorY did not own the loop; scrubs=${sink.scrubBegins}",
                boundaryBeginsBefore + 1, sink.loopBoundaryBegins)
        }

        assertTrue("Tool motion did not settle after the motor acquired DOWN", settled.await(2L, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            moveDrag(surface, gesture, aX + 90f, motorY, 220L)
            assertEquals("Moving A must preserve B through Fine animation cleanup",
                15_000L, sink.loopBoundaryMoves.last().endMs)
            moveDrag(surface, gesture, aX + 90f, motorY - 160f, 252L)
            moveDrag(surface, gesture, aX + 90f, motorY - 160f, 1_252L)
            val fineStart = sink.loopBoundaryMoves.last().startMs
            repeat(40) { step ->
                moveDrag(surface, gesture, aX + 90f + (step + 1) * .5f * density,
                    motorY - 160f, 1_252L + (step + 1) * 16L)
            }
            val slowPosition = sink.loopBoundaryMoves.last().startMs
            assertTrue("Tiny Fine events must accumulate into 1–3 ms, got ${slowPosition - fineStart}",
                slowPosition - fineStart in 1L..3L)
            moveDrag(surface, gesture, aX + 90f + 120f * density, motorY - 160f, 1_924L)
            val fastPosition = sink.loopBoundaryMoves.last().startMs
            assertEquals(15_000L, sink.loopBoundaryMoves.last().endMs)
            assertTrue("Fast Fine motion must remain in milliseconds, got ${fastPosition - slowPosition}",
                fastPosition - slowPosition in 190L..201L)
            endDrag(surface, gesture, aX + 90f + 120f * density, motorY - 160f, 1_940L)
        }

        assertEquals(boundaryBeginsBefore + 1, sink.loopBoundaryBegins)
        assertTrue(sink.loopBoundaryMoves.size > boundaryMovesBefore)
        assertEquals(boundaryEndsBefore + 1, sink.loopBoundaryEnds)
        assertEquals("A/B drag leaked into the playback scrub owner", scrubBeginsBefore, sink.scrubBegins)
        assertEquals(
            "A/B drag leaked a Play/Pause command while tool motion settled",
            playCommandsBefore,
            sink.transport.count { it == PlayerSurfaceTransportCommand.PLAY_PAUSE }
        )
        assertTrue("A/B motor never entered Fine", sink.fineStates.contains(true))
        val firstFine = sink.fineStates.indexOf(true)
        assertTrue("A/B motor never exited Fine", sink.fineStates.drop(firstFine + 1).contains(false))
    }
}
