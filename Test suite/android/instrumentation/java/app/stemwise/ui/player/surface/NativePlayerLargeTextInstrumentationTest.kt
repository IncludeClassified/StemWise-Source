package app.stemwise.ui.player.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.ui.player.nativeplayer.*
import app.stemwise.ui.portraitPlayerControlStackHeightPx
import app.stemwise.ui.portraitPlayerControlTopDp
import app.stemwise.ui.portraitPlayerLayout
import app.stemwise.ui.portraitPlayerToolMotionFrame
import org.junit.Assert.*
import org.junit.Test

internal class NativePlayerLargeTextInstrumentationTest : NativePlayerSurfaceFixture() {
    @Test fun sourceAndToolNamesPaintCompletelyInsideDisjointTargetsAtNormalAndDoubleText() {
        activityRule.scenario.onActivity { host ->
            val density = host.resources.displayMetrics.density
            for (widthDp in listOf(320f, 432f)) for (scale in listOf(1f, 2f)) {
                val seed = completeOriginalLoopSnapshot()
                val snapshot = seed.copy(timeline = seed.timeline.copy(loopStartMs = -1L, loopEndMs = -1L),
                    toolDetail = seed.toolDetail.copy(selectedTool = PlayerSurfaceTool.NONE,
                        activity = seed.toolDetail.activity.copy(loopActive = false), detail = FullPlayerDetailState.None))
                val width = (widthDp * density).toInt()
                val height = (900f * density).toInt()
                val sink = RecordingSink()
                val (root, surface) = qualifiedSurface(host, snapshot, sink, width, height)
                val route = portraitPlayerLayout(widthDp, 900f, PlaybackSourceMode.ORIGINAL,
                    panelExpanded = false, fontScale = scale)
                val frame = portraitPlayerToolMotionFrame(widthDp, 900f, density, 0f, 0f, fontScale = scale)
                val layout = frame.layout
                surface.updateRouteBaseMetrics(1f, 1f, route.headerHeightDp * density,
                    route.headerGapDp * density, route.identityHeightDp * density, route.identityGapDp * density,
                    route.compactChrome, route.listSpacingDp * density, scale,
                    route.seekerClockTopClearanceDp * density, route.seekerTrackHeightDp * density,
                    route.transportHeightDp * density, route.transportPlaySizeDp * density)
                val top = portraitPlayerControlTopDp(layout, false) * density
                assertTrue(surface.retargetToolMotion(PlayerSurfaceToolMotionTarget(
                    generation = snapshot.owner.generation, selectionEpoch = 1L, selectedTool = PlayerSurfaceTool.NONE,
                    durationMs = 0, expandedArtworkSizePx = layout.artworkSizeDp * density,
                    mixerCardHeightPx = 0f, mixerSpacingPx = 0f, listSpacingPx = layout.listSpacingDp * density,
                    seekerTrackHeightPx = layout.seekerTrackHeightDp * density,
                    transportHeightPx = layout.transportHeightDp * density,
                    controlBounds = PlayerSurfaceBounds(24f * density, top, width - 24f * density,
                        top + portraitPlayerControlStackHeightPx(frame, density, false, 0f)), detailViewportHeightPx = 0f)))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = TextCanvas(bitmap)
                    surface.draw(canvas)
                    val geometry = checkNotNull(surface.semanticsGeometry())
                    val names = listOf("Original", "Loop", "Key", "Lyrics", "Metronome")
                    val bounds = listOf(geometry.original, geometry.loopTool, geometry.tuneTool,
                        geometry.lyricsTool, geometry.metronomeTool)
                    names.zip(bounds).forEach { (name, box) ->
                        val painted = canvas.text.singleOrNull { it.value == name }
                        assertNotNull("$widthDp dp/$scale: full $name was not painted", painted)
                        painted!!
                        assertTrue("$name escaped its target: $painted / $box",
                            painted.left >= box.left - 1f && painted.right <= box.right + 1f &&
                                painted.top >= box.top - 1f && painted.bottom <= box.bottom + 1f)
                        assertTrue("$name lost its touch target", box.width >= 48f * density && box.height >= 48f * density)
                    }
                    val tools = bounds.drop(1)
                    for (a in tools.indices) for (b in a + 1 until tools.size) {
                        assertFalse("Tool targets overlap", tools[a].left < tools[b].right &&
                            tools[b].left < tools[a].right && tools[a].top < tools[b].bottom && tools[b].top < tools[a].bottom)
                    }
                    tap(surface, geometry.tuneTool)
                    tap(surface, geometry.metronomeTool)
                    assertEquals(listOf(PlayerSurfaceTool.TUNE, PlayerSurfaceTool.METRONOME), sink.tools)
                } finally { bitmap.recycle(); root.removeAllViews() }
            }
        }
    }

    private data class TextRun(val value: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
    private class TextCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val text = mutableListOf<TextRun>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val width = paint.measureText(text)
            val left = x - when (paint.textAlign) { Paint.Align.CENTER -> width / 2f; Paint.Align.RIGHT -> width; else -> 0f }
            this.text += TextRun(text, left, y + paint.ascent(), left + width, y + paint.descent())
            super.drawText(text, x, y, paint)
        }
    }
}
