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
internal abstract class NativePlayerSurfaceFixture {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    internal companion object {
        fun geometryActionSink(): FullPlayerActionSink = RecordingSink()
    }









    protected fun qualifiedSurface(host: ComponentActivity, snapshot: FullPlayerSnapshot,
        sink: FullPlayerActionSink, width: Int = 1_200, height: Int = 2_800): Pair<FrameLayout, PlayerSurfaceView> {
        val root = FrameLayout(host)
        host.setContentView(root)
        val surface = PlayerSurfaceView(host)
        val density = host.resources.displayMetrics.density
        surface.bind(snapshot, sink, PlayerSurfacePositionProvider { 10_000L },
            width, height, density, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT)
        root.addView(surface, ViewGroup.LayoutParams(width, height))
        surface.measure(exactly(width), exactly(height))
        surface.layout(0, 0, width, height)
        settleToolGeometry(surface, snapshot, width, height, density)
        return root to surface
    }

    protected fun settleToolGeometry(surface: PlayerSurfaceView, snapshot: FullPlayerSnapshot,
        width: Int, height: Int, density: Float) {
        val stems = snapshot.sourceMixer.source.presentedMode == PlayerSurfaceSourceMode.STEMS
        val tool = snapshot.toolDetail.selectedTool
        // Production supplies the stable closed route; the native tool owner overrides only
        // its moving artwork and controls. Keep identity metrics in the same dp/pixel contract.
        val route = portraitPlayerLayout(width / density, height / density, PlaybackSourceMode.ORIGINAL,
            panelExpanded = false, mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)
        surface.updateRouteBaseMetrics(1f, if (stems) 0f else 1f,
            route.headerHeightDp * density, route.headerGapDp * density,
            route.identityHeightDp * density, route.identityGapDp * density,
            route.compactChrome, route.listSpacingDp * density, route.fontScale,
            route.seekerClockTopClearanceDp * density, route.seekerTrackHeightDp * density,
            route.transportHeightDp * density, route.transportPlaySizeDp * density)
        val detail = when (tool) {
            PlayerSurfaceTool.LOOP -> app.stemwise.ui.PLAYER_DETAIL_LOOP
            PlayerSurfaceTool.TUNE -> app.stemwise.ui.PLAYER_DETAIL_TUNE
            PlayerSurfaceTool.METRONOME -> app.stemwise.ui.PLAYER_DETAIL_METRONOME
            else -> null
        }
        val clearance = if (snapshot.timeline.loopStartMs >= 0L) 1f else 0f
        val detailHeight = app.stemwise.ui.portraitPlayerDetailViewportHeightDp(width / density, height / density,
            if (stems) PlaybackSourceMode.STEMS else PlaybackSourceMode.ORIGINAL, detail,
            loopClearanceProgress = clearance, mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)
        val frame = portraitPlayerToolMotionFrame(width / density, height / density, density, detailHeight,
            if (stems) 1f else 0f, loopClearanceProgress = clearance,
            mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)
        val layout = frame.layout
        val top = portraitPlayerControlTopDp(layout, stems) * density
        assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
            generation = snapshot.owner.generation, selectionEpoch = 0L,
            selectedTool = tool, durationMs = 0, expandedArtworkSizePx = layout.artworkSizeDp * density,
            mixerCardHeightPx = layout.mixerCardHeightDp * density,
            mixerSpacingPx = layout.mixerSpacingDp * density, listSpacingPx = layout.listSpacingDp * density,
            seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
            transportHeightPx = layout.transportHeightDp * density,
            controlBounds = PlayerSurfaceBounds(48f, top, width - 48f,
                top + portraitPlayerControlStackHeightPx(frame, density, stems,
                    loopClearanceProgress = clearance,
                    mixerLaneCount = snapshot.sourceMixer.mixer.laneCount)),
            detailViewportHeightPx = frame.detailHeightPx.toFloat())))
    }









































    protected fun awaitPlayback(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10L)
        }
        assertTrue("Timed out waiting for real playback", predicate())
    }

    protected fun loopProofStemAsset(
        id: String,
        file: File,
        durationMs: Long
    ) = PlaybackContentFixture.stem(InstrumentationRegistry.getInstrumentation().targetContext,
        id, file, durationMs, "motion-loop-latency-set")

    protected fun writeSineWave(file: File, durationMs: Long, frequency: Double) {
        val sampleRate = 44_100
        val sampleCount = (durationMs * sampleRate / 1_000L).toInt()
        val dataSize = sampleCount * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeInt(Integer.reverseBytes(sampleRate))
            output.writeInt(Integer.reverseBytes(sampleRate * 2))
            output.writeShort(java.lang.Short.reverseBytes(2).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            repeat(sampleCount) { index ->
                val sample = (kotlin.math.sin(
                    2.0 * Math.PI * frequency * index / sampleRate
                ) * 4_000.0).toInt().toShort()
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    protected fun completeStemsLoopSnapshot(): FullPlayerSnapshot = connectedStemsLoopSnapshot()

    protected fun completeStemsMixerSnapshot(): FullPlayerSnapshot {
        val loopSnapshot = completeStemsLoopSnapshot()
        return loopSnapshot.copy(
            revision = loopSnapshot.revision + 1L,
            toolDetail = loopSnapshot.toolDetail.copy(
                revision = loopSnapshot.toolDetail.revision + 1L,
                selectedTool = PlayerSurfaceTool.NONE,
                activity = loopSnapshot.toolDetail.activity.copy(loopActive = false),
                detail = FullPlayerDetailState.None
            )
        )
    }

    protected fun completeOriginalLoopSnapshot(): FullPlayerSnapshot = connectedOriginalLoopSnapshot()

    protected fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    protected fun awaitGestureDeadline(deadlineNanos: Long) {
        while (true) {
            val remaining = deadlineNanos - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            val wholeMillis = remaining / 1_000_000L
            if (wholeMillis > 0L) SystemClock.sleep(wholeMillis) else Thread.yield()
        }
    }

    protected fun awaitNextFrame(instrumentation: android.app.Instrumentation) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback { frame.countDown() }
        }
        assertTrue("Native owner did not produce its scheduled VSYNC", frame.await(2L, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    protected fun draw(surface: PlayerSurfaceView, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            surface.draw(Canvas(bitmap))
        }

    protected fun centerX(bounds: PlayerSurfaceBounds): Float = (bounds.left + bounds.right) * .5f
    protected fun centerY(bounds: PlayerSurfaceBounds): Float = (bounds.top + bounds.bottom) * .5f

    protected fun tap(surface: PlayerSurfaceView, bounds: PlayerSurfaceBounds) {
        down(surface, bounds)
        up(surface, bounds, 32L)
    }

    protected fun down(surface: PlayerSurfaceView, bounds: PlayerSurfaceBounds) {
        val now = SystemClock.uptimeMillis()
        dispatch(surface, now, now, MotionEvent.ACTION_DOWN, centerX(bounds), centerY(bounds))
    }

    protected fun up(surface: PlayerSurfaceView, bounds: PlayerSurfaceBounds, heldMs: Long) {
        val now = SystemClock.uptimeMillis()
        dispatch(surface, now - heldMs, now, MotionEvent.ACTION_UP, centerX(bounds), centerY(bounds))
    }

    protected fun cancel(surface: PlayerSurfaceView, bounds: PlayerSurfaceBounds) {
        val now = SystemClock.uptimeMillis()
        dispatch(surface, now, now, MotionEvent.ACTION_CANCEL, centerX(bounds), centerY(bounds))
    }

    protected fun assertRejectedDown(surface: PlayerSurfaceView, bounds: PlayerSurfaceBounds) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_DOWN,
            centerX(bounds),
            centerY(bounds),
            0
        ).also { event ->
            assertFalse("Disabled transport unexpectedly acquired touch ownership", surface.dispatchTouchEvent(event))
            event.recycle()
        }
    }

    protected data class PointerGesture(val downTime: Long)

    protected fun beginWindowDrag(
        instrumentation: android.app.Instrumentation,
        screenOrigin: IntArray,
        startX: Float,
        startY: Float
    ): PointerGesture {
        activityRule.scenario.onActivity { host ->
            assertTrue("Loop test window lost input focus", host.hasWindowFocus())
        }
        cancelWindowGesture(instrumentation)
        val downAt = SystemClock.uptimeMillis()
        sendWindowPointer(
            instrumentation,
            screenOrigin,
            downAt,
            downAt,
            MotionEvent.ACTION_DOWN,
            startX,
            startY
        )
        return PointerGesture(downAt)
    }

    protected fun moveWindowDrag(
        instrumentation: android.app.Instrumentation,
        gesture: PointerGesture,
        screenOrigin: IntArray,
        x: Float,
        y: Float
    ) {
        sendWindowPointer(
            instrumentation,
            screenOrigin,
            gesture.downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_MOVE,
            x,
            y
        )
    }

    protected fun endWindowDrag(
        instrumentation: android.app.Instrumentation,
        gesture: PointerGesture,
        screenOrigin: IntArray,
        x: Float,
        y: Float
    ) {
        sendWindowPointer(
            instrumentation,
            screenOrigin,
            gesture.downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x,
            y
        )
    }

    protected fun sendWindowPointer(
        instrumentation: android.app.Instrumentation,
        screenOrigin: IntArray,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ) {
        require(screenOrigin.size >= 2)
        MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            screenOrigin[0] + x,
            screenOrigin[1] + y,
            0
        ).also { event ->
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                // Use one injector for the entire gesture: Android tracks its pointer
                // stream by injection identity. The shell connection avoids the app-UID
                // permission lookup; delivery still goes through InputDispatcher.
                assertTrue("Window pointer injection failed",
                    instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
    }

    protected fun cancelWindowGesture(instrumentation: android.app.Instrumentation) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            // A failed assertion must not leave the shared synthetic device's pointer down.
            // CANCEL may return false when no gesture exists; the subsequent DOWN is checked.
            instrumentation.uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }

    protected fun beginDrag(
        surface: PlayerSurfaceView,
        startX: Float,
        startY: Float
    ): PointerGesture {
        val downAt = SystemClock.uptimeMillis()
        dispatch(surface, downAt, downAt, MotionEvent.ACTION_DOWN, startX, startY)
        return PointerGesture(downAt)
    }

    protected fun moveDrag(
        surface: PlayerSurfaceView,
        gesture: PointerGesture,
        x: Float,
        y: Float,
        elapsedMs: Long
    ) {
        dispatch(surface, gesture.downTime, gesture.downTime + elapsedMs, MotionEvent.ACTION_MOVE, x, y)
    }

    protected fun moveBatchedDrag(
        surface: PlayerSurfaceView,
        gesture: PointerGesture,
        historicalX: Float,
        currentX: Float,
        y: Float,
        historicalElapsedMs: Long,
        currentElapsedMs: Long
    ) {
        MotionEvent.obtain(
            gesture.downTime,
            gesture.downTime + historicalElapsedMs,
            MotionEvent.ACTION_MOVE,
            historicalX,
            y,
            0
        ).also { event ->
            event.addBatch(
                gesture.downTime + currentElapsedMs,
                currentX,
                y,
                1f,
                1f,
                0
            )
            assertTrue("PlayerSurfaceView rejected batched ACTION_MOVE", surface.dispatchTouchEvent(event))
            event.recycle()
        }
    }

    protected fun endDrag(
        surface: PlayerSurfaceView,
        gesture: PointerGesture,
        x: Float,
        y: Float,
        elapsedMs: Long
    ) {
        dispatch(surface, gesture.downTime, gesture.downTime + elapsedMs, MotionEvent.ACTION_UP, x, y)
    }

    protected fun dispatch(
        surface: PlayerSurfaceView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ) {
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { event ->
            assertTrue("PlayerSurfaceView rejected ${MotionEvent.actionToString(action)}", surface.dispatchTouchEvent(event))
            event.recycle()
        }
    }

    protected fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0) return true
                x += 4
            }
            y += 4
        }
        return false
    }

    protected fun regionHasColorPixels(bitmap: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) != 0 &&
                    android.graphics.Color.red(pixel) +
                        android.graphics.Color.green(pixel) +
                        android.graphics.Color.blue(pixel) > 48
                ) {
                    return true
                }
                x += 2
            }
            y += 2
        }
        return false
    }

    protected fun regionHasInk(
        bitmap: Bitmap,
        bounds: PlayerSurfaceBounds,
        background: Int
    ): Boolean {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 != 0 && pixel != background) return true
                x++
            }
            y++
        }
        return false
    }

    protected fun regionDiffers(
        first: Bitmap,
        second: Bitmap,
        bounds: PlayerSurfaceBounds
    ): Boolean {
        val left = bounds.left.toInt().coerceIn(0, first.width - 1)
        val top = bounds.top.toInt().coerceIn(0, first.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, first.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, first.height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) return true
                x++
            }
            y++
        }
        return false
    }

    protected fun trackX(
        geometry: PlayerSurfaceSemanticsGeometry,
        fraction: Float,
        density: Float
    ): Float = playerSurfaceSeekerPositionX(
        fraction,
        playerSurfaceSeekerTrackStartX(
            geometry.seeker.left,
            geometry.seeker.right,
            PLAYER_SURFACE_SEEKER_RAIL_ENDPOINT_INSET_DP * density
        ),
        playerSurfaceSeekerTrackEndX(
            geometry.seeker.left,
            geometry.seeker.right,
            PLAYER_SURFACE_SEEKER_RAIL_ENDPOINT_INSET_DP * density
        )
    )

    protected fun loopMotorCenterY(
        geometry: PlayerSurfaceSemanticsGeometry,
        density: Float
    ): Float {
        // Target the visible motor below the rail, not the old rail/playback-cursor lane.
        val railY = seekerRailY(geometry, density)
        val handleTop = railY + (2f + StemwiseSeekerDesign.LOOP_GUIDE_NORMAL_HEIGHT_DP) * density
        return handleTop + StemwiseSeekerDesign.LOOP_HANDLE_IDLE_HEIGHT_DP * density * .5f
    }

    protected fun seekerRailY(geometry: PlayerSurfaceSemanticsGeometry, density: Float): Float =
        geometry.seeker.top + if (geometry.mixerLaneCount > 0) {
            38f * density
        } else {
            minOf(24f * density, maxOf(18f * density, geometry.seeker.height * .38f))
        }

    protected fun assertChangesNearX(
        label: String,
        first: Bitmap,
        second: Bitmap,
        bounds: PlayerSurfaceBounds,
        expectedX: Float,
        radiusPx: Float
    ) {
        val left = (expectedX - radiusPx).toInt().coerceIn(bounds.left.toInt(), first.width - 1)
        val right = (expectedX + radiusPx).toInt().coerceIn(left + 1, bounds.right.toInt())
        val top = bounds.top.toInt().coerceIn(0, first.height - 1)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, first.height)
        var changed = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
            }
        }
        assertTrue("$label did not track the authoritative MOVE coordinate ($changed pixels)", changed >= 16)
    }

    protected data class MixerCommit(val laneIndex: Int, val muted: Boolean, val solo: Boolean, val volume: Float)

    protected data class LoopBoundaryMove(
        val boundary: FullPlayerLoopBoundary,
        val startMs: Long,
        val endMs: Long,
        val transportPositionMs: Long,
        val inputAtMicros: Long,
        val sinkAtMicros: Long,
        val heldBGestureId: Long,
        val capturedBMs: Long
    )

    protected class RecordingSink(
        private val rejectFirstCapturedMove: Boolean = false,
        private val rejectFirstOrdinaryMove: Boolean = false,
        private val publishLoopMove: (
            (
                startMs: Long,
                endMs: Long,
                transportPositionMs: Long,
                inputAtMicros: Long,
                heldBGestureId: Long,
                capturedBMs: Long
            ) -> Boolean
        )? = null
    ) : FullPlayerActionSink {
        val sources = mutableListOf<PlayerSurfaceSourceMode>()
        val transport = mutableListOf<PlayerSurfaceTransportCommand>()
        val pagerCommits = mutableListOf<Int>()
        val pagerSongIds = mutableListOf<String>()
        val navigation = mutableListOf<PlayerSurfaceTransportCommand>()
        val skipDeltas = mutableListOf<Long>()
        val tools = mutableListOf<PlayerSurfaceTool>()
        val fineStates = mutableListOf<Boolean>()
        val mixerCommits = mutableListOf<MixerCommit>()
        val mixerBegins = mutableListOf<Int>()
        var scrubBegins = 0
        val scrubPreviewPositions = mutableListOf<Long>()
        var scrubCommits = 0
        var loopBoundaryBegins = 0
        var lastLoopBoundaryBegin: FullPlayerLoopBoundary? = null
        val loopBoundaryMoves = mutableListOf<LoopBoundaryMove>()
        private var acceptedLoopRange: Pair<Long, Long>? = null
        private var loopGestureSerial = 0L
        private var heldBGestureId = 0L
        private var rejectedCapturedMove = false
        private var rejectedOrdinaryMove = false
        val loopBoundaryMoveBoundaries: List<FullPlayerLoopBoundary>
            get() = loopBoundaryMoves.map(LoopBoundaryMove::boundary)
        var loopBoundaryEnds = 0

        override fun onOpenPlayer(generation: Long): Boolean = true
        override fun onCollapsePlayer(generation: Long): Boolean = true
        override fun onShowSongInfo(generation: Long): Boolean = true
        override fun onPagerPreview(
            generation: Long,
            queueIndex: Int,
            offsetFraction: Float
        ): Boolean = true
        override fun onPagerCommit(generation: Long, queueIndex: Int): Boolean {
            pagerCommits += queueIndex
            return true
        }
        override fun onPagerCommit(generation: Long, queueIndex: Int, songId: String): Boolean {
            pagerSongIds += songId
            return onPagerCommit(generation, queueIndex)
        }
        override fun onPagerCancel(generation: Long) = Unit
        override fun onNavigate(
            generation: Long,
            direction: PlayerSurfaceTransportCommand
        ): Int {
            navigation += direction
            return 0
        }
        override fun onArtworkSkip(generation: Long, deltaMs: Long): Boolean {
            skipDeltas += deltaMs
            return true
        }

        override fun onSource(generation: Long, target: PlayerSurfaceSourceMode): Boolean {
            sources += target
            return true
        }

        override fun onTransport(
            generation: Long,
            command: PlayerSurfaceTransportCommand
        ): Boolean {
            transport += command
            return true
        }

        override fun onTool(generation: Long, tool: PlayerSurfaceTool): Boolean {
            tools += tool
            return true
        }

        override fun onScrubBegin(generation: Long): Boolean {
            scrubBegins++
            return true
        }

        override fun onScrubPreview(generation: Long, positionMs: Long): Boolean {
            scrubPreviewPositions += positionMs
            return true
        }

        override fun onScrubCommit(generation: Long, positionMs: Long): Boolean {
            scrubCommits++
            return true
        }

        override fun onScrubCancel(generation: Long) = Unit

        override fun onFineModeChanged(
            generation: Long,
            active: Boolean,
            centerPositionMs: Long
        ) {
            fineStates += active
        }

        override fun onLoopBoundaryBegin(
            generation: Long,
            boundary: FullPlayerLoopBoundary,
            originalStartMs: Long,
            originalEndMs: Long
        ): Boolean {
            loopBoundaryBegins++
            lastLoopBoundaryBegin = boundary
            acceptedLoopRange = originalStartMs to originalEndMs
            heldBGestureId = if (boundary == FullPlayerLoopBoundary.END) {
                ++loopGestureSerial
            } else {
                0L
            }
            return true
        }

        override fun onLoopBoundaryMove(
            generation: Long,
            boundary: FullPlayerLoopBoundary,
            startMs: Long,
            endMs: Long,
            transportPositionMs: Long,
            inputEventAtMonotonicMicros: Long,
            capturedBMs: Long
        ): Boolean {
            loopBoundaryMoves += LoopBoundaryMove(
                boundary,
                startMs,
                endMs,
                transportPositionMs,
                inputEventAtMonotonicMicros,
                SystemClock.uptimeMillis() * 1_000L,
                heldBGestureId,
                capturedBMs
            )
            val rejectCaptured = rejectFirstCapturedMove && capturedBMs >= 0L &&
                !rejectedCapturedMove
            val rejectOrdinary = rejectFirstOrdinaryMove && capturedBMs < 0L &&
                !rejectedOrdinaryMove
            if (rejectCaptured) rejectedCapturedMove = true
            if (rejectOrdinary) rejectedOrdinaryMove = true
            val reject = rejectCaptured || rejectOrdinary
            val accepted = !reject && (publishLoopMove?.invoke(
                    startMs,
                    endMs,
                    transportPositionMs,
                    inputEventAtMonotonicMicros,
                    heldBGestureId,
                    capturedBMs
                ) ?: true)
            if (accepted) acceptedLoopRange = startMs to endMs
            return accepted
        }

        override fun onLoopBoundaryEnd(
            generation: Long,
            boundary: FullPlayerLoopBoundary,
            startMs: Long,
            endMs: Long,
            transportPositionMs: Long,
            inputEventAtMonotonicMicros: Long
        ): Boolean {
            loopBoundaryEnds++
            val requested = startMs to endMs
            val accepted = acceptedLoopRange
            val terminalAccepted = requested == accepted ||
                (publishLoopMove?.invoke(
                    startMs,
                    endMs,
                    transportPositionMs,
                    inputEventAtMonotonicMicros,
                    heldBGestureId,
                    -1L
                ) ?: true)
            if (terminalAccepted) acceptedLoopRange = requested
            acceptedLoopRange = null
            heldBGestureId = 0L
            return terminalAccepted
        }


        private val mixerValues = mutableMapOf<Int, MixerCommit>()
        private val mixerHandles = mutableMapOf<Int, PlayerSurfaceVolumeGesture>()
        val mixerCancellations = mutableListOf<PlayerSurfaceVolumeGesture>()
        val mixerPreviews = mutableListOf<Pair<PlayerSurfaceVolumeGesture, Float>>()

        override fun onBeginVolume(generation: Long, laneIndex: Int): PlayerSurfaceVolumeGesture? {
            if (laneIndex in mixerHandles) return null
            mixerBegins += laneIndex
            return PlayerSurfaceVolumeGesture(generation, laneIndex).also { mixerHandles[laneIndex] = it }
        }

        override fun onPreviewVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
            if (mixerHandles[gesture.laneIndex] !== gesture) return false
            val value = mixerValues[gesture.laneIndex] ?: MixerCommit(gesture.laneIndex, false, false, 1f)
            mixerValues[gesture.laneIndex] = value.copy(
                volume = app.stemwise.ui.stemSliderStoredVolume(volume, value.volume), muted = volume <= 0f)
            mixerPreviews += gesture to volume
            return true
        }

        override fun onFinishVolume(gesture: PlayerSurfaceVolumeGesture, volume: Float): Boolean {
            if (mixerHandles[gesture.laneIndex] !== gesture) return false
            val value = mixerValues[gesture.laneIndex] ?: MixerCommit(gesture.laneIndex, false, false, 1f)
            // Record the slider request; the real playback fixture verifies retained native gain.
            val committed = value.copy(volume = volume, muted = volume <= 0f)
            mixerCommits += committed
            mixerValues[gesture.laneIndex] = committed.copy(volume = app.stemwise.ui.stemSliderStoredVolume(volume, value.volume))
            mixerHandles.remove(gesture.laneIndex)
            return true
        }

        override fun onSetMuted(generation: Long, laneIndex: Int, muted: Boolean): Boolean {
            val value = mixerValues[laneIndex] ?: MixerCommit(laneIndex, false, false, 1f)
            val committed = value.copy(muted = muted)
            mixerCommits += committed
            mixerValues[laneIndex] = committed
            return true
        }

        override fun onSetSolo(generation: Long, laneIndex: Int, solo: Boolean): Boolean {
            val value = mixerValues[laneIndex] ?: MixerCommit(laneIndex, false, false, 1f)
            val committed = value.copy(solo = solo)
            mixerCommits += committed
            mixerValues[laneIndex] = committed
            return true
        }

        override fun onCancelVolume(gesture: PlayerSurfaceVolumeGesture) {
            if (mixerHandles[gesture.laneIndex] !== gesture) return
            mixerHandles.remove(gesture.laneIndex)
            mixerCancellations += gesture
        }

    }
}
