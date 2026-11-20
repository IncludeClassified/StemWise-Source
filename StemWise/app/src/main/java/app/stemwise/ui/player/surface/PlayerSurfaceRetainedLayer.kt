package app.stemwise.ui.player.surface

import android.graphics.Canvas
import android.graphics.RenderNode

/**
 * A small retained hardware layer used only for player pixels that do not change every frame.
 *
 * The supported API baseline provides RenderNode; software Canvas callers retain their direct path.
 */
internal interface PlayerSurfaceRetainedLayer {
    /** Returns true only when a changed node size requires a fresh recording. */
    fun setBounds(left: Int, top: Int, right: Int, bottom: Int): Boolean

    fun hasRecording(): Boolean

    fun beginRecording(): Canvas

    fun endRecording()

    fun draw(canvas: Canvas)

    fun discard()
}

internal fun createPlayerSurfaceRetainedLayer(name: String): PlayerSurfaceRetainedLayer =
    PlayerSurfaceRenderNodeLayer(name)

internal fun playerSurfaceRetainedLayerSizeChanged(
    currentLeft: Int,
    currentTop: Int,
    currentRight: Int,
    currentBottom: Int,
    nextLeft: Int,
    nextTop: Int,
    nextRight: Int,
    nextBottom: Int
): Boolean = nextRight - nextLeft != currentRight - currentLeft ||
    nextBottom - nextTop != currentBottom - currentTop

private class PlayerSurfaceRenderNodeLayer(name: String) : PlayerSurfaceRetainedLayer {
    private val node = RenderNode(name).apply {
        // A display list alone still replays every path on RenderThread. The compositing layer turns
        // stable chrome into one retained texture until this node is recorded again.
        setUseCompositingLayer(true, null)
    }
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        require(right > left && bottom > top)
        if (
            this.left == left && this.top == top && this.right == right && this.bottom == bottom
        ) return false
        val sizeChanged = playerSurfaceRetainedLayerSizeChanged(
            currentLeft = this.left,
            currentTop = this.top,
            currentRight = this.right,
            currentBottom = this.bottom,
            nextLeft = left,
            nextTop = top,
            nextRight = right,
            nextBottom = bottom
        )
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        node.setPosition(left, top, right, bottom)
        // RenderNode translates an existing display list with its position. The recording uses
        // node-local coordinates, so a position-only move must not redraw stable chrome.
        return sizeChanged
    }

    override fun hasRecording(): Boolean = node.hasDisplayList()

    override fun beginRecording(): Canvas = node.beginRecording()

    override fun endRecording() {
        node.endRecording()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRenderNode(node)
    }

    override fun discard() {
        node.discardDisplayList()
    }
}
