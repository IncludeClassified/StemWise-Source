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
internal class NativePlayerGestureInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun openToolMixerMotorsRemainTouchableBelowTheVisibleHeader() {
        for (tool in listOf(PlayerSurfaceTool.LOOP, PlayerSurfaceTool.METRONOME)) {
            activityRule.scenario.onActivity { host ->
                val base = nineLaneStemsSnapshot()
                val snapshot = base.copy(revision = base.revision + 1L,
                    toolDetail = base.toolDetail.copy(revision = base.toolDetail.revision + 1L,
                        selectedTool = tool, activity = base.toolDetail.activity.copy(loopActive = true),
                        detail = if (tool == PlayerSurfaceTool.LOOP) FullPlayerDetailState.Loop(
                            base.timeline.durationMs, base.timeline.loopStartMs, base.timeline.loopEndMs)
                        else FullPlayerDetailState.Metronome(false, 120f, 1, 4, 4, .6f, true)))
                val sink = RecordingSink()
                val (root, surface) = qualifiedSurface(host, snapshot, sink)
                try {
                    val density = host.resources.displayMetrics.density
                    // Production retains closed-route metrics while the native tool clock owns
                    // its compact endpoint. Reserved header space is not a visible hit motor.
                    val route = app.stemwise.ui.portraitPlayerLayout(1_200 / density, 2_800 / density,
                        PlaybackSourceMode.STEMS, panelExpanded = false, mixerLaneCount = 9)
                    surface.updateRouteBaseMetrics(1f, 0f, route.headerHeightDp * density,
                        route.headerGapDp * density, route.identityHeightDp * density,
                        route.identityGapDp * density, route.compactChrome, route.listSpacingDp * density,
                        1f, route.seekerClockTopClearanceDp * density, route.seekerTrackHeightDp * density,
                        route.transportHeightDp * density, route.transportPlaySizeDp * density)
                    settleToolGeometry(surface, snapshot, 1_200, 2_800, density)
                    val mute = checkNotNull(surface.semanticsGeometry()).mixerLaneAt(0).mute
                    val y = maxOf(mute.top, 60f * density) + density
                    assertTrue("Fixture must expose a real motor below the visible header", y < mute.bottom)
                    assertTrue("Fixture must cross the old reserved-header shield", y < route.headerHeightDp * density)
                    tap(surface, PlayerSurfaceBounds(mute.left, y - density * .5f,
                        mute.right, y + density * .5f))
                    assertEquals("$tool discarded physical input on a visible mixer motor", 1, sink.mixerCommits.size)
                    assertTrue(sink.mixerCommits.single().muted)
                } finally { root.removeAllViews() }
            }
        }
    }

    private fun nineLaneStemsSnapshot(): FullPlayerSnapshot {
        val base = completeStemsMixerSnapshot()
        val roles = listOf(FullPlayerStemRole.VOCALS, FullPlayerStemRole.DRUMS, FullPlayerStemRole.BASS,
            FullPlayerStemRole.GUITAR, FullPlayerStemRole.PIANO, FullPlayerStemRole.STRINGS,
            FullPlayerStemRole.BRASS, FullPlayerStemRole.WOODWIND, FullPlayerStemRole.REMAINING)
        return base.copy(revision = 30L, sourceMixer = FullPlayerSourceMixerPacket(21L,
            base.sourceMixer.source, PlayerSurfaceMixerPacket(20L, 20L, IntArray(9) { it + 1 },
                FloatArray(9) { .8f }, IntArray(9)), roles.mapIndexed { index, role ->
                FullPlayerMixerLaneVisual(index + 1, role, role.name, 0xFF44CC88.toInt())
            }))
    }

    @Test fun mixerFlingContinuesAfterLiftAndANewTouchStopsItWithoutChangingGain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val surfaceRef = AtomicReference<PlayerSurfaceView>()
        val rootRef = AtomicReference<FrameLayout>()
        val releasedOffset = AtomicReference<Float>()
        val continued = CountDownLatch(1)
        val sink = RecordingSink()
        try {
            activityRule.scenario.onActivity { host ->
                assertTrue("Fling qualification requires motion enabled", android.animation.ValueAnimator.areAnimatorsEnabled())
                val snapshot = nineLaneStemsSnapshot()
                val density = host.resources.displayMetrics.density
                val (root, surface) = qualifiedSurface(host, snapshot, sink,
                    width = (360f * density).toInt(), height = (800f * density).toInt())
                rootRef.set(root)
                surfaceRef.set(surface)
                surface.setMixerScrollListener { geometry ->
                    val baseline = releasedOffset.get()
                    if (baseline != null && checkNotNull(geometry).mixerScrollOffsetPx > baseline + 2f) continued.countDown()
                }
                val geometry = checkNotNull(surface.semanticsGeometry())
                assertTrue("Fling fixture must contain overflowing rows", geometry.mixerScrollMaximumPx > 0f)
                val slider = geometry.mixerLaneAt(0).volume
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(slider), centerY(slider))
                dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, centerX(slider), centerY(slider) - 20f)
                dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP, centerX(slider), centerY(slider) - 40f)
                releasedOffset.set(checkNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx)
            }
            assertTrue("Mixer stopped at ACTION_UP instead of decelerating", continued.await(3L, TimeUnit.SECONDS))
            var interruptedOffset = 0f
            activityRule.scenario.onActivity {
                val surface = surfaceRef.get()
                val viewport = checkNotNull(surface.semanticsGeometry()).mixerViewport
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(viewport), centerY(viewport))
                interruptedOffset = checkNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx
            }
            SystemClock.sleep(100L)
            instrumentation.waitForIdleSync()
            activityRule.scenario.onActivity {
                val surface = surfaceRef.get()
                val viewport = checkNotNull(surface.semanticsGeometry()).mixerViewport
                assertEquals(interruptedOffset, checkNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx, .001f)
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_CANCEL, centerX(viewport), centerY(viewport))
                assertTrue(sink.mixerBegins.isEmpty())
                assertTrue(sink.mixerCommits.isEmpty())
            }
            var heldGainOffset = 0f
            var heldGainX = 0f
            var heldGainY = 0f
            activityRule.scenario.onActivity {
                val surface = surfaceRef.get()
                surface.scrollMixerBy(nineLaneStemsSnapshot().owner.generation, -Float.MAX_VALUE)
                val geometry = checkNotNull(surface.semanticsGeometry())
                val gain = geometry.mixerLaneAt(0).volume
                val scroll = geometry.mixerLaneAt(1).volume
                val down = SystemClock.uptimeMillis()
                heldGainX = centerX(gain) + 60f
                heldGainY = centerY(gain)
                dispatch(surface, down, down, MotionEvent.ACTION_DOWN, centerX(gain), heldGainY)
                dispatch(surface, down, down + 16L, MotionEvent.ACTION_MOVE, heldGainX, heldGainY)
                assertEquals(listOf(0), sink.mixerBegins)
                fun secondPointer(action: Int, time: Long, scrollY: Float) {
                    val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                        id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }
                    val coordinates = arrayOf(
                        MotionEvent.PointerCoords().apply { x = heldGainX; y = heldGainY; pressure = 1f; size = 1f },
                        MotionEvent.PointerCoords().apply { x = centerX(scroll); y = scrollY; pressure = 1f; size = 1f })
                    val indexedAction = if (action == MotionEvent.ACTION_MOVE) action else
                        action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    val event = MotionEvent.obtain(down, time, indexedAction, 2, properties, coordinates,
                        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                secondPointer(MotionEvent.ACTION_POINTER_DOWN, down + 32L, centerY(scroll))
                secondPointer(MotionEvent.ACTION_MOVE, down + 48L, centerY(scroll) - 40f)
                secondPointer(MotionEvent.ACTION_POINTER_UP, down + 64L, centerY(scroll) - 80f)
                heldGainOffset = checkNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx
                assertTrue("The second finger must actually scroll before lifting", heldGainOffset > 0f)
            }
            SystemClock.sleep(100L)
            instrumentation.waitForIdleSync()
            activityRule.scenario.onActivity {
                val surface = surfaceRef.get()
                assertEquals("A lifted scroll finger must not fling beneath a still-held gain owner",
                    heldGainOffset, checkNotNull(surface.semanticsGeometry()).mixerScrollOffsetPx, .001f)
                assertEquals(listOf(0), sink.mixerBegins)
                assertTrue("The held gain contact must remain uncommitted", sink.mixerCommits.isEmpty())
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_CANCEL, heldGainX, heldGainY)
            }
        } finally {
            activityRule.scenario.onActivity { rootRef.get()?.removeAllViews() }
        }
    }

    @Test
    fun externalTransportWithdrawsNativeSeekAndTransportButLeavesToolsInteractive() {
        activityRule.scenario.onActivity { host ->
            val snapshot = completeOriginalLoopSnapshot()
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, snapshot, sink)
            try {
                val geometry = checkNotNull(surface.semanticsGeometry())
                surface.setExternalTransport(true)
                for (target in listOf(geometry.playPause, geometry.seeker)) {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 16L, action, centerX(target), centerY(target), 0)
                        try { surface.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                assertTrue(sink.transport.isEmpty())
                assertEquals(0, sink.scrubBegins)
                tap(surface, geometry.tuneTool)
                assertEquals(listOf(PlayerSurfaceTool.TUNE), sink.tools)
                surface.setExternalTransport(false)
                tap(surface, geometry.playPause)
                assertEquals(listOf(PlayerSurfaceTransportCommand.PLAY_PAUSE), sink.transport)
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun eightPartsAndRemainingStayReachableWithoutMovingTransportOrAdmittingGainOnScroll() {
        activityRule.scenario.onActivity { host ->
            val snapshot = nineLaneStemsSnapshot()
            val sink = RecordingSink()
            val density = host.resources.displayMetrics.density
            val (root, surface) = qualifiedSurface(host, snapshot, sink,
                width = (360f * density).toInt(), height = (800f * density).toInt())
            try {
                val before = checkNotNull(surface.semanticsGeometry())
                assertTrue("Remaining fixture must contain overflowing rows", before.mixerScrollMaximumPx > 0f)
                assertEquals(9, before.mixerLaneCount)
                assertEquals(0f, before.mixerLaneAt(8).volume.height, 0f)
                val start = before.mixerLaneAt(2).volume
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(start), centerY(start))
                dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, centerX(start), centerY(start) - 80f)
                dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP, centerX(start), centerY(start) - 80f)
                assertTrue("Vertical scroll admitted a gain owner", sink.mixerBegins.isEmpty())
                assertTrue(surface.scrollMixerBy(snapshot.owner.generation, Float.MAX_VALUE))
                val after = checkNotNull(surface.semanticsGeometry())
                assertEquals(before.seeker, after.seeker)
                assertEquals(before.playPause, after.playPause)
                assertEquals(before.previous, after.previous)
                assertEquals(before.next, after.next)
                assertEquals(after.mixerScrollMaximumPx, after.mixerScrollOffsetPx, .001f)
                val remaining = after.mixerLaneAt(8)
                assertTrue(remaining.volume.height >= 48f * host.resources.displayMetrics.density)
                tap(surface, remaining.mute)
                tap(surface, remaining.solo)
                val down = now + 100L
                dispatch(surface, down, down, MotionEvent.ACTION_DOWN, centerX(remaining.volume), centerY(remaining.volume))
                dispatch(surface, down, down + 16L, MotionEvent.ACTION_MOVE, remaining.volume.left, centerY(remaining.volume))
                dispatch(surface, down, down + 32L, MotionEvent.ACTION_UP, remaining.volume.left, centerY(remaining.volume))
                assertEquals(listOf(8), sink.mixerBegins)
                assertTrue(sink.mixerCommits.all { it.laneIndex == 8 })
                assertEquals(3, sink.mixerCommits.size)
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun offscreenLoopMotorsRemainTouchableAtTheirPaintedPositionsDuringFineExit() {
        activityRule.scenario.onActivity { host ->
            val base = completeOriginalLoopSnapshot()
            val snapshot = base.copy(revision = base.revision + 1L, timeline = base.timeline.copy(
                revision = base.timeline.revision + 1L, durationMs = 180_000L, positionMs = 90_000L,
                loopStartMs = 10_000L, loopEndMs = 150_000L), toolDetail = base.toolDetail.copy(
                    revision = base.toolDetail.revision + 1L,
                    detail = FullPlayerDetailState.Loop(180_000L, 10_000L, 150_000L)))
            assertEquals(null, FullPlayerSnapshotValidator.validate(snapshot))
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, snapshot, sink)
            fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            try {
                val gestures = field(surface, "seekerGestures").get(surface)
                field(gestures, "scrubPreviewPositionMs").setLong(gestures, 90_000L)
                field(gestures, "scrubPreviewLoopStartMs").setLong(gestures, 10_000L)
                field(gestures, "scrubPreviewLoopEndMs").setLong(gestures, 150_000L)
                field(surface, "scrubFineReleaseActive").setBoolean(surface, true)
                field(surface, "scrubFineReleaseBoundary").set(surface, FullPlayerLoopBoundary.START)
                val left = field(surface, "seekerTrackLeft").getFloat(surface)
                val right = field(surface, "seekerTrackRight").getFloat(surface)
                val rail = PlayerSurfaceView::class.java.getDeclaredMethod("seekerRailY").apply { isAccessible = true }
                val top = PlayerSurfaceView::class.java.getDeclaredMethod("loopEndpointHandleTop",
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
                val density = host.resources.displayMetrics.density
                for (fine in listOf(.9f, .5f, .1f, 0f)) {
                    field(surface, "waveformMorph").setFloat(surface, fine)
                    val y = (top.invoke(surface, rail.invoke(surface) as Float, fine) as Float) +
                        StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP * density * .5f
                    // Long-track A/B are outside the Fine viewport: paint lerps each coarse point
                    // to its nearest rail edge, rather than toward its off-screen waveform point.
                    val a = left + (right - left) * (10_000f / 180_000f) * (1f - fine)
                    val b = right - (right - left) * (30_000f / 180_000f) * (1f - fine)
                    assertEquals(FullPlayerLoopBoundary.START, surface.loopBoundaryGestureTargetForQualification(a, y))
                    assertEquals(FullPlayerLoopBoundary.END, surface.loopBoundaryGestureTargetForQualification(b, y))
                }
                field(surface, "waveformMorph").setFloat(surface, 1f)
                val y = (top.invoke(surface, rail.invoke(surface) as Float, 1f) as Float) +
                    StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP * density * .5f
                assertEquals(null, surface.loopBoundaryGestureTargetForQualification(left, y))
                assertEquals(null, surface.loopBoundaryGestureTargetForQualification(right, y))
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun authoritativePagerCatchUpPreservesTheSettlingSpringAndAHeldSecondSwipe() {
        for (holdSecondSwipe in listOf(false, true)) activityRule.scenario.onActivity { host ->
            val base = completeOriginalLoopSnapshot()
            val initial = base.copy(revision = base.revision + 1L,
                timeline = base.timeline.copy(revision = base.timeline.revision + 1L,
                    loopStartMs = -1L, loopEndMs = -1L), toolDetail = base.toolDetail.copy(
                revision = base.toolDetail.revision + 1L, selectedTool = PlayerSurfaceTool.NONE,
                detail = FullPlayerDetailState.None, activity = base.toolDetail.activity.copy(loopActive = false)))
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, initial, sink)
            try {
                val artwork = checkNotNull(surface.semanticsGeometry()).artwork
                val x = centerX(artwork)
                val y = centerY(artwork)
                val distance = artwork.width * .65f
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, x, y)
                dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, x - distance, y)
                dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP, x - distance, y)
                assertEquals(listOf(2), sink.pagerCommits)
                if (holdSecondSwipe) {
                    dispatch(surface, now + 40L, now + 40L, MotionEvent.ACTION_DOWN, x, y)
                    dispatch(surface, now + 40L, now + 56L, MotionEvent.ACTION_MOVE, x + distance * .3f, y)
                }
                fun field(name: String) = PlayerSurfaceView::class.java.getDeclaredField(name).apply { isAccessible = true }
                val beforeOffset = field("pagerOffsetFraction").getFloat(surface)
                val beforeVelocity = field("pagerVelocityFraction").getFloat(surface)
                val gestures = field("pagerGestures").get(surface) as PlayerPagerGestureOwner
                val beforePointer = gestures.pointerId
                val next = initial.catalog.songAt(2)
                val nextWaveform = FullPlayerWaveformPacket(initial.waveform.revision + 1L,
                    next.mediaRevision, initial.waveform.copyPeaks(), initial.waveform.sections())
                val authoritative = initial.copy(revision = initial.revision + 1L,
                    owner = initial.owner.copy(generation = initial.owner.generation + 1L,
                        songId = next.songId, mediaRevision = next.mediaRevision,
                        audioRevision = "connected-audio-after"),
                    visualSelectionSongId = next.songId,
                    waveform = nextWaveform,
                    timeline = initial.timeline.copy(revision = initial.timeline.revision + 1L,
                        waveformRevision = nextWaveform.revision),
                    selection = initial.selection.copy(revision = initial.selection.revision + 1L, currentIndex = 2))
                assertEquals(FullPlayerAdoptionResult.APPLIED_NEW_OWNER, surface.bind(authoritative, sink,
                    PlayerSurfacePositionProvider { 10_000L }, 1_200, 2_800,
                    host.resources.displayMetrics.density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                assertEquals(beforeOffset, field("pagerOffsetFraction").getFloat(surface), 0f)
                assertEquals(beforeVelocity, field("pagerVelocityFraction").getFloat(surface), 0f)
                assertEquals(beforePointer, gestures.pointerId)
                assertEquals(listOf(2), sink.pagerCommits)
                if (holdSecondSwipe) {
                    assertTrue(beforePointer != MotionEvent.INVALID_POINTER_ID)
                    dispatch(surface, now + 40L, now + 72L, MotionEvent.ACTION_UP, x + distance, y)
                    assertEquals(listOf(2, 1), sink.pagerCommits)
                }
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun soloToggleCannotOverwriteAcceptedMuteWithAnUnadmittedFaderPreview() {
        activityRule.scenario.onActivity { host ->
            val seed = completeStemsMixerSnapshot()
            val mixer = seed.sourceMixer.mixer
            val initial = seed.copy(sourceMixer = FullPlayerSourceMixerPacket(
                seed.sourceMixer.revision + 1L, seed.sourceMixer.source,
                PlayerSurfaceMixerPacket(mixer.revision + 1L, mixer.packageRevision,
                    IntArray(mixer.laneCount, mixer::laneKeyAt),
                    FloatArray(mixer.laneCount) { if (it == 0) .4f else mixer.volumeAt(it) },
                    IntArray(mixer.laneCount) { if (it == 0) PlayerSurfaceMixerPacket.FLAG_MUTED else 0 }),
                seed.sourceMixer.lanes()))
            lateinit var surface: PlayerSurfaceView
            var toggles = 0
            val sink = object : FullPlayerActionSink by RecordingSink() {
                override fun onPreviewVolumeBatch(generation: Long, changedMask: Int,
                    gestures: Array<PlayerSurfaceVolumeGesture?>, volumes: FloatArray): Boolean =
                    error("The held preview must remain unadmitted until the explicit toggle supersedes it")

                override fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean {
                    toggles++
                    // Same synchronous accepted-slot readback used by OwnedPlayerActionSink.
                    assertTrue(surface.acceptMixerFlags(generation, laneIndex, .4f, true, solo))
                    return true
                }
            }
            val fixture = qualifiedSurface(host, initial, sink)
            surface = fixture.second
            try {
                val lane = checkNotNull(surface.semanticsGeometry()).mixerLaneAt(0)
                val owner = PlayerSurfaceView::class.java.getDeclaredField("mixerGestures")
                    .apply { isAccessible = true }.get(surface) as PlayerMixerGestureOwner
                val down = SystemClock.uptimeMillis()
                val x = lane.volume.right - 2f
                val y = centerY(lane.volume)
                dispatch(surface, down, down, MotionEvent.ACTION_DOWN, centerX(lane.volume), y)
                dispatch(surface, down, down + 16L, MotionEvent.ACTION_MOVE, x, y)
                assertFalse("Optimistic positive fader input must visibly unmute", owner.mixerMuted[0])
                assertTrue("The accepted slot remains muted before preview admission", owner.committedMixerMuted[0])
                owner.stageMixerPreviewsForPostFrameDrain()
                assertEquals(1, owner.mixerDrainPreviewMask)
                fun secondPointer(action: Int, time: Long) {
                    val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                        id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f },
                        MotionEvent.PointerCoords().apply { this.x = centerX(lane.solo); this.y = centerY(lane.solo); pressure = 1f; size = 1f })
                    val event = MotionEvent.obtain(down, time, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(surface.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                secondPointer(MotionEvent.ACTION_POINTER_DOWN, down + 32L)
                secondPointer(MotionEvent.ACTION_POINTER_UP, down + 48L)
                assertEquals(1, toggles)
                assertTrue("Solo must preserve the exact accepted mute readback", owner.mixerMuted[0])
                assertTrue(owner.committedMixerMuted[0])
                assertTrue(owner.mixerSolo[0])
                assertEquals(.4f, owner.committedMixerVolumes[0], 0f)
                assertEquals(0f, owner.liveMixerVolumes[0], 0f)
                assertEquals(0, owner.mixerDrainPreviewMask)
                assertEquals(0, owner.mixerPointerIds[0])
                dispatch(surface, down, down + 64L, MotionEvent.ACTION_CANCEL, x, y)
                assertTrue(owner.mixerMuted[0])
                assertEquals(0f, owner.liveMixerVolumes[0], 0f)
            } finally { fixture.first.removeAllViews() }
        }
    }

    @Test
    fun heldFaderSurvivesMuteAndSoloOnTheSameAndAnotherLane() {
        for (toggleLane in listOf(0, 1)) activityRule.scenario.onActivity { host ->
            val initial = completeStemsMixerSnapshot()
            val sink = RecordingSink()
            val (root, surface) = qualifiedSurface(host, initial, sink)
            try {
                val geometry = checkNotNull(surface.semanticsGeometry())
                val slider = geometry.mixerLaneAt(0).volume
                val toggles = geometry.mixerLaneAt(toggleLane)
                val down = SystemClock.uptimeMillis()
                var time = down
                var heldX = centerX(slider)
                val heldY = centerY(slider)
                fun two(action: Int, target: PlayerSurfaceBounds) {
                    val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                        id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = heldX; y = heldY; pressure = 1f; size = 1f },
                        MotionEvent.PointerCoords().apply { x = centerX(target); y = centerY(target); pressure = 1f; size = 1f })
                    time += 16L
                    val event = MotionEvent.obtain(down, time, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(surface.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                dispatch(surface, down, time, MotionEvent.ACTION_DOWN, heldX, heldY)
                heldX = slider.right - 2f
                dispatch(surface, down, ++time, MotionEvent.ACTION_MOVE, heldX, heldY)
                assertEquals(listOf(0), sink.mixerBegins)
                two(MotionEvent.ACTION_POINTER_DOWN, toggles.mute)
                two(MotionEvent.ACTION_POINTER_UP, toggles.mute)
                two(MotionEvent.ACTION_POINTER_DOWN, toggles.solo)
                two(MotionEvent.ACTION_POINTER_UP, toggles.solo)
                assertEquals("Toggle ended the held fader", 2, sink.mixerCommits.size)
                assertTrue(sink.mixerCancellations.isEmpty())
                heldX = slider.left
                dispatch(surface, down, ++time, MotionEvent.ACTION_MOVE, heldX, heldY)
                dispatch(surface, down, ++time, MotionEvent.ACTION_UP, heldX, heldY)
                assertEquals(3, sink.mixerCommits.size)
                val gain = sink.mixerCommits.last()
                assertEquals(0, gain.laneIndex)
                assertEquals(0f, gain.volume, .001f)
                assertTrue("Zero slider requests mute independently of the earlier glyph toggle", gain.muted)
                assertEquals(toggleLane == 0, gain.solo)
                assertTrue(sink.mixerCancellations.isEmpty())
                assertEquals(listOf(0), sink.mixerBegins)
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun mixerTerminalCoordinatesCompleteGainAndBothScrollPathsWithoutButtonActions() {
        for (gesture in listOf("gain-up-only", "scroll-up-only", "scroll-move-up")) {
            activityRule.scenario.onActivity { host ->
                val initial = if (gesture == "gain-up-only") completeStemsMixerSnapshot() else nineLaneStemsSnapshot()
                val sink = RecordingSink()
                val density = host.resources.displayMetrics.density
                val (root, surface) = if (gesture == "gain-up-only") qualifiedSurface(host, initial, sink)
                    else qualifiedSurface(host, initial, sink,
                        width = (360f * density).toInt(), height = (800f * density).toInt())
                try {
                    val geometry = checkNotNull(surface.semanticsGeometry())
                    if (gesture != "gain-up-only") assertTrue("Terminal scroll fixture must overflow",
                        geometry.mixerScrollMaximumPx > 0f)
                    val slider = geometry.mixerLaneAt(0).volume
                    val x = centerX(slider)
                    val y = centerY(slider)
                    val now = SystemClock.uptimeMillis()
                    val scrollField = PlayerSurfaceView::class.java.getDeclaredField("mixerScrollOffsetPx")
                        .apply { isAccessible = true }
                    dispatch(surface, now, now, MotionEvent.ACTION_DOWN, x, y)
                    var intermediateScroll = 0f
                    if (gesture == "scroll-move-up") {
                        dispatch(surface, now, now + 16L, MotionEvent.ACTION_MOVE, x, y - 20f * density)
                        intermediateScroll = scrollField.getFloat(surface)
                        assertTrue(intermediateScroll > 0f)
                    }
                    dispatch(surface, now, now + 32L, MotionEvent.ACTION_UP,
                        if (gesture == "gain-up-only") slider.right else x,
                        if (gesture == "gain-up-only") y else y - 40f * density)
                    if (gesture == "gain-up-only") {
                        val commit = sink.mixerCommits.single()
                        assertEquals(1f, commit.volume, 0.0001f)
                        assertFalse(commit.muted)
                        assertFalse(commit.solo)
                    } else {
                        assertTrue(scrollField.getFloat(surface) > intermediateScroll)
                        assertTrue(sink.mixerCommits.isEmpty())
                    }
                } finally { root.removeAllViews() }
            }
        }
    }

    @Test
    fun scrubReplacementClosesItsCapturedOwnerAndRejectedBindCannotReplaceDelegates() {
        activityRule.scenario.onActivity { host ->
            val initial = completeOriginalLoopSnapshot()
            val cancelledA = mutableListOf<Long>()
            val cancelledB = mutableListOf<Long>()
            var scrubbing = false
            val sinkA = object : FullPlayerActionSink by RecordingSink() {
                override fun onScrubBegin(generation: Long): Boolean {
                    check(generation == initial.owner.generation)
                    scrubbing = true
                    return true
                }
                override fun onScrubCancel(generation: Long) {
                    check(generation == initial.owner.generation)
                    cancelledA += generation
                    scrubbing = false
                }
            }
            val sinkB = object : FullPlayerActionSink by RecordingSink() {
                override fun onScrubCancel(generation: Long) { cancelledB += generation }
            }
            val (root, surface) = qualifiedSurface(host, initial, sinkA)
            try {
                val rail = checkNotNull(surface.semanticsGeometry()).seeker
                val now = SystemClock.uptimeMillis()
                dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(rail), centerY(rail))
                dispatch(surface, now, now + 20L, MotionEvent.ACTION_MOVE, centerX(rail) + 80f, centerY(rail))
                assertTrue("Fixture did not start an actual scrub", scrubbing)
                val replacement = initial.copy(revision = initial.revision + 1L,
                    owner = initial.owner.copy(generation = initial.owner.generation + 1L))
                assertEquals(FullPlayerAdoptionResult.APPLIED_NEW_OWNER, surface.bind(replacement, sinkB,
                    PlayerSurfacePositionProvider { 20_000L }, 1_200, 2_800,
                    host.resources.displayMetrics.density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                assertEquals(listOf(initial.owner.generation), cancelledA)
                assertTrue(cancelledB.isEmpty())
                assertFalse(scrubbing)
                val rejected = surface.bind(initial, sinkA, PlayerSurfacePositionProvider { 0L },
                    1_200, 2_800, host.resources.displayMetrics.density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
                assertFalse(rejected == FullPlayerAdoptionResult.APPLIED_NEW_OWNER)
                val field = PlayerSurfaceView::class.java.getDeclaredField("sink").apply { isAccessible = true }
                assertTrue("Rejected snapshot replaced the live callback authority", field.get(surface) === sinkB)
            } finally { root.removeAllViews() }
        }
    }

    @Test
    fun mixerReplacementCancelsCapturedLeaseOnceAndStaleReleaseCannotWriteToNewOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (replacePackage in listOf(false, true)) {
            val initial = completeStemsMixerSnapshot()
            val begins = mutableListOf<Pair<String, Long>>()
            val previews = mutableListOf<Triple<String, Long, Float>>()
            val commits = mutableListOf<Pair<String, Long>>()
            val cancellations = mutableListOf<Pair<String, Long>>()
            fun sink(label: String) = object : FullPlayerActionSink by RecordingSink() {
                override fun onBeginVolume(generation: Long, laneIndex: Int): PlayerSurfaceVolumeGesture? {
                    assertEquals(0, laneIndex)
                    begins += label to generation
                    return PlayerSurfaceVolumeGesture(generation, laneIndex)
                }
                override fun onPreviewVolumeBatch(generation: Long, changedMask: Int,
                    gestures: Array<PlayerSurfaceVolumeGesture?>, volumes: FloatArray): Boolean {
                    assertEquals(1, changedMask)
                    previews += Triple(label, generation, volumes[0])
                    return true
                }
                override fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
                    assertEquals(0, gesture.laneIndex)
                    commits += label to gesture.generation
                    return true
                }
                override fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture) {
                    assertEquals(0, gesture.laneIndex)
                    cancellations += label to gesture.generation
                }
            }
            val oldSink = sink("old")
            val newSink = sink("new")
            val source = initial.sourceMixer
            val mixer = source.mixer
            val rebound = initial.copy(revision = initial.revision + 1L,
                status = initial.status.copy(revision = initial.status.revision + 1L))
            val replacement = if (replacePackage) rebound.copy(
                revision = rebound.revision + 1L,
                sourceMixer = FullPlayerSourceMixerPacket(source.revision + 1L, source.source,
                    PlayerSurfaceMixerPacket(mixer.revision + 1L, mixer.packageRevision + 1L,
                        IntArray(mixer.laneCount, mixer::laneKeyAt),
                        FloatArray(mixer.laneCount, mixer::volumeAt), IntArray(mixer.laneCount)),
                    source.lanes())
            ) else rebound.copy(revision = rebound.revision + 1L,
                owner = initial.owner.copy(generation = initial.owner.generation + 1L))
            lateinit var surface: PlayerSurfaceView
            lateinit var slider: PlayerSurfaceBounds
            var root: FrameLayout? = null
            var downAt = 0L
            try {
                activityRule.scenario.onActivity { host ->
                    val fixture = qualifiedSurface(host, initial, oldSink)
                    root = fixture.first
                    surface = fixture.second
                    slider = requireNotNull(surface.semanticsGeometry()).mixerLaneAt(0).volume
                    downAt = SystemClock.uptimeMillis()
                    dispatch(surface, downAt, downAt, MotionEvent.ACTION_DOWN, centerX(slider), centerY(slider))
                    dispatch(surface, downAt, downAt + 16L, MotionEvent.ACTION_MOVE, slider.left, centerY(slider))
                    assertEquals(listOf("old" to initial.owner.generation), begins)
                }
                awaitNextFrame(instrumentation)
                var acceptedPreviewCount = 0
                activityRule.scenario.onActivity { host ->
                    assertTrue("The real frame drain did not publish the held mixer preview", previews.isNotEmpty())
                    assertTrue(previews.all { it.first == "old" && it.second == initial.owner.generation })
                    assertEquals(0f, previews.last().third, 0f)
                    acceptedPreviewCount = previews.size
                    assertEquals(FullPlayerAdoptionResult.APPLIED_UPDATE,
                        surface.bind(rebound, newSink, PlayerSurfacePositionProvider { 10_000L },
                            1_200, 2_800, host.resources.displayMetrics.density,
                            PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                    assertTrue("Same-owner publication cancelled the live mixer lease", cancellations.isEmpty())
                    dispatch(surface, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        centerX(slider), centerY(slider))
                }
                awaitNextFrame(instrumentation)
                activityRule.scenario.onActivity { host ->
                    assertTrue("Same-owner rebind dropped its held preview", previews.size > acceptedPreviewCount)
                    assertTrue(previews.all { it.first == "old" && it.second == initial.owner.generation })
                    dispatch(surface, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        centerX(slider), centerY(slider))
                    assertEquals("Same-owner rebind redirected the terminal commit",
                        listOf("old" to initial.owner.generation), commits)
                    assertTrue(cancellations.isEmpty())
                    // A second old-sink lease is the one the replacement must cancel exactly once.
                    assertEquals(FullPlayerAdoptionResult.UNCHANGED,
                        surface.bind(rebound, oldSink, PlayerSurfacePositionProvider { 10_000L },
                            1_200, 2_800, host.resources.displayMetrics.density,
                            PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                    downAt = SystemClock.uptimeMillis()
                    dispatch(surface, downAt, downAt, MotionEvent.ACTION_DOWN, centerX(slider), centerY(slider))
                    dispatch(surface, downAt, downAt + 16L, MotionEvent.ACTION_MOVE, slider.left, centerY(slider))
                    assertEquals(listOf("old" to initial.owner.generation, "old" to initial.owner.generation), begins)
                    acceptedPreviewCount = previews.size
                    // Leave another preview queued while replacing its exact owner/package authority.
                    dispatch(surface, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        centerX(slider), centerY(slider))
                    assertEquals(if (replacePackage) FullPlayerAdoptionResult.APPLIED_UPDATE
                        else FullPlayerAdoptionResult.APPLIED_NEW_OWNER,
                        surface.bind(replacement, newSink, PlayerSurfacePositionProvider { 10_000L },
                            1_200, 2_800, host.resources.displayMetrics.density,
                            PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                    settleToolGeometry(surface, replacement, 1_200, 2_800, host.resources.displayMetrics.density)
                    assertEquals(listOf("old" to initial.owner.generation), cancellations)
                    assertEquals(if (replacePackage) FullPlayerAdoptionResult.REJECTED_STALE_REVISION
                        else FullPlayerAdoptionResult.REJECTED_STALE_OWNER,
                        surface.bind(rebound, oldSink, PlayerSurfacePositionProvider { 10_000L },
                            1_200, 2_800, host.resources.displayMetrics.density,
                            PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
                    dispatch(surface, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                        slider.right, centerY(slider))
                    dispatch(surface, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        slider.right, centerY(slider))
                }
                awaitNextFrame(instrumentation)
                activityRule.scenario.onActivity {
                    assertEquals("Replacement cancelled the captured lease more than once",
                        listOf("old" to initial.owner.generation), cancellations)
                    assertEquals("A stale frame reached the replacement sink", acceptedPreviewCount, previews.size)
                    assertEquals("Stale UP committed mixer state",
                        listOf("old" to initial.owner.generation), commits)
                    assertEquals(listOf("old" to initial.owner.generation, "old" to initial.owner.generation), begins)
                    tap(surface, requireNotNull(surface.semanticsGeometry()).mixerLaneAt(0).volume)
                    assertEquals(listOf("old" to initial.owner.generation, "old" to initial.owner.generation,
                        "new" to replacement.owner.generation), begins)
                    assertEquals(listOf("old" to initial.owner.generation,
                        "new" to replacement.owner.generation), commits)
                    assertEquals(listOf("old" to initial.owner.generation), cancellations)
                }
            } finally {
                activityRule.scenario.onActivity { root?.removeAllViews() }
            }
        }
    }

    @Test
    fun stemsBackgroundDoubleTapSeeksWithoutSongHeaderAndDoesNotTakeMixerControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val width = (432f * density).toInt()
        val height = (860f * density).toInt()
        instrumentation.runOnMainSync {
            val snapshot = completeStemsMixerSnapshot()
            val sink = RecordingSink()
            val surface = PlayerSurfaceView(context)
            val clearance = if (snapshot.timeline.loopStartMs >= 0L) 1f else 0f
            val frame = portraitPlayerToolMotionFrame(432f, 860f, density, 0f, 1f,
                loopClearanceProgress = clearance,
                mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)
            val layout = frame.layout
            surface.bind(snapshot, sink, PlayerSurfacePositionProvider { 10_000L }, width, height,
                density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
            surface.measure(exactly(width), exactly(height))
            surface.layout(0, 0, width, height)
            surface.updateRouteBaseMetrics(1f, 1f, layout.headerHeightDp * density,
                layout.headerGapDp * density, layout.identityHeightDp * density,
                layout.identityGapDp * density, layout.compactChrome, layout.listSpacingDp * density,
                1f, layout.seekerClockTopClearanceDp * density, layout.seekerTrackHeightDp * density,
                layout.transportHeightDp * density, layout.transportPlaySizeDp * density)
            val top = portraitPlayerControlTopDp(layout, true) * density
            surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                generation = snapshot.owner.generation, selectionEpoch = 1L,
                selectedTool = PlayerSurfaceTool.NONE, durationMs = 0, expandedArtworkSizePx = layout.artworkSizeDp * density,
                mixerCardHeightPx = layout.mixerCardHeightDp * density,
                mixerSpacingPx = layout.mixerSpacingDp * density, listSpacingPx = layout.listSpacingDp * density,
                seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                transportHeightPx = layout.transportHeightDp * density,
                controlBounds = PlayerSurfaceBounds(20f * density, top, width - 20f * density,
                    top + portraitPlayerControlStackHeightPx(frame, density, true,
                        loopClearanceProgress = clearance,
                        mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)), detailViewportHeightPx = 0f
            ))
            val geometry = requireNotNull(surface.semanticsGeometry())
            assertEquals(0f, geometry.artwork.height, .001f)
            val artworkAlpha = PlayerSurfaceView::class.java.getDeclaredField("artworkAlpha").apply {
                isAccessible = true
            }.getFloat(surface)
            assertEquals("Settled Stems must seek with no visible artwork", 0f, artworkAlpha, .001f)
            assertTrue("Stems retains the current song identity below its workspace", geometry.identity.height > 0f)
            val gapY = (geometry.mixerLaneAt(0).volume.bottom + geometry.mixerLaneAt(1).volume.top) * .5f
            fun target(fraction: Float): PlayerSurfaceBounds {
                val x = width * fraction
                return PlayerSurfaceBounds(x - 1f, gapY - 1f, x + 1f, gapY + 1f)
            }
            val left = target(.15f)
            val right = target(.85f)
            tap(surface, left)
            assertTrue(sink.skipDeltas.isEmpty())
            tap(surface, left)
            assertEquals(listOf(-5_000L), sink.skipDeltas)
            tap(surface, right)
            tap(surface, right)
            assertEquals(listOf(-5_000L, 5_000L), sink.skipDeltas)
            tap(surface, left)
            tap(surface, geometry.mixerLaneAt(0).mute)
            tap(surface, left)
            assertEquals(1, sink.mixerCommits.size)
            assertEquals(listOf(-5_000L, 5_000L), sink.skipDeltas)
            cancel(surface, left)
            tap(surface, left)
            assertEquals(listOf(-5_000L, 5_000L), sink.skipDeltas)
            val vertical = beginDrag(surface, 2f, centerY(left))
            endDrag(surface, vertical, 2f, centerY(left) + 30f * density, 32L)
            tap(surface, left)
            assertEquals("A vertical drag must clear the tap pair", listOf(-5_000L, 5_000L), sink.skipDeltas)
            assertTrue(sink.mixerBegins.isEmpty())
            val rowY = centerY(geometry.mixerLaneAt(0).volume)
            val leftX = geometry.mixerViewport.left * .5f
            val rightX = (geometry.mixerViewport.right + width) * .5f
            val leftGutter = PlayerSurfaceBounds(leftX - 1f, rowY - 1f, leftX + 1f, rowY + 1f)
            val rightGutter = PlayerSurfaceBounds(rightX - 1f, rowY - 1f, rightX + 1f, rowY + 1f)
            tap(surface, leftGutter)
            tap(surface, leftGutter)
            tap(surface, rightGutter)
            tap(surface, rightGutter)
            assertEquals(listOf(-5_000L, 5_000L, -5_000L, 5_000L), sink.skipDeltas)
            assertEquals("Seek gutters changed a mix control", 1, sink.mixerCommits.size)
        }
    }

    @Test
    fun releasingALoopHandleShrinksFromItsCurrentSize() {
        activityRule.scenario.onActivity { host ->
            val surface = PlayerSurfaceView(host)
            val animate = PlayerSurfaceView::class.java.getDeclaredMethod("animateScrubGrowthTo",
                Float::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                FullPlayerLoopBoundary::class.java).apply { isAccessible = true }
            val sample = PlayerSurfaceView::class.java.getDeclaredMethod("currentScrubGrowth",
                Long::class.javaPrimitiveType).apply { isAccessible = true }
            val now = SystemClock.uptimeMillis()
            animate.invoke(surface, 1f, now, FullPlayerLoopBoundary.END)
            val held = sample.invoke(surface, now + 500L) as Float
            assertEquals(1f, held, .001f)
            animate.invoke(surface, 0f, now + 500L, FullPlayerLoopBoundary.END)
            assertEquals("UP snapped the handle to zero", held,
                sample.invoke(surface, now + 500L) as Float, .001f)
            val shrinking = sample.invoke(surface, now + 560L) as Float
            assertTrue("Release must retain a shrinking tail", shrinking > 0f && shrinking < held)
            assertEquals(0f, sample.invoke(surface, now + 1_000L) as Float, .001f)
        }
    }

    @Test
    fun selectedSourceDoesNotFlashWhenScrubbingTemporarilyDisablesItsAction() {
        activityRule.scenario.onActivity { host ->
            val surface = PlayerSurfaceView(host)
            val draw = PlayerSurfaceView::class.java.getDeclaredMethod(
                "drawSourceButton", Canvas::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                PlayerSurfaceIcon::class.java, String::class.java, Float::class.javaPrimitiveType,
                PlayerSurfaceStemsStatus::class.java
            ).apply { isAccessible = true }
            fun render(enabled: Boolean) = Bitmap.createBitmap(700, 240, Bitmap.Config.ARGB_8888).also {
                draw.invoke(surface, Canvas(it), 40f, 40f, 660f, 200f,
                    true, enabled, PlayerSurfaceIcon.STEMS, "STEMS", 1f, PlayerSurfaceStemsStatus.READY)
            }
            val enabled = render(true)
            val temporarilyDisabled = render(false)
            try {
                assertTrue("Selected source changed pixels during a temporary input gate",
                    enabled.sameAs(temporarilyDisabled))
            } finally {
                enabled.recycle()
                temporarilyDisabled.recycle()
            }
        }
    }

    @Test
    fun heldToolPointerCannotSelectAToolForAReplacementOwner() {
        activityRule.scenario.onActivity { host ->
            val root = FrameLayout(host)
            host.setContentView(root)
            val surface = PlayerSurfaceView(host)
            val sink = RecordingSink()
            val density = host.resources.displayMetrics.density
            val initial = completeOriginalLoopSnapshot()
            fun bind(snapshot: FullPlayerSnapshot) {
                surface.bind(
                    snapshot = snapshot,
                    actionSink = sink,
                    positionProvider = PlayerSurfacePositionProvider { 10_000L },
                    widthPx = 1_200,
                    heightPx = 2_800,
                    density = density,
                    direction = PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT
                )
            }
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
            bind(initial)
            surface.setControlBounds(48f, 760f, 1_152f, 2_720f)
            root.addView(surface, ViewGroup.LayoutParams(1_200, 2_800))
            surface.measure(exactly(1_200), exactly(2_800))
            surface.layout(0, 0, 1_200, 2_800)
            try {
                val target = requireNotNull(surface.semanticsGeometry()).tuneTool
                down(surface, target)
                assertTrue("Fixture must acquire the real child action target",
                    (0 until surface.childCount).any { surface.getChildAt(it).isPressed })
                bind(initial.copy(
                    revision = initial.revision + 1L,
                    owner = initial.owner.copy(generation = initial.owner.generation + 1L)
                ))
                up(surface, target, 20L)
                assertTrue("The old DOWN must not become a tool action for the new owner", sink.tools.isEmpty())
                tap(surface, requireNotNull(surface.semanticsGeometry()).tuneTool)
                assertEquals(listOf(PlayerSurfaceTool.TUNE), sink.tools)
            } finally {
                root.removeAllViews()
            }
        }
    }
}
