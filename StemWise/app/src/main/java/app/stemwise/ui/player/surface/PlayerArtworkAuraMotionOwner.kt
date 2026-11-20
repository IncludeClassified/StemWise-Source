package app.stemwise.ui.player.surface

import app.stemwise.ui.design.StemwiseSemanticMotion

/** Owns the retained aura clock and its one degradation wakeup, independently of playback. */
internal class PlayerArtworkAuraMotionOwner(
    private val requestFrame: () -> Unit,
    private val invalidateAura: () -> Unit,
    private val replaceWake: (Long) -> Unit,
    private val cancelWake: () -> Unit
) {
    var motionEnabled = false
        private set
    var frozenFrameNanos = 0L
        private set
    private var slowFrames = 0
    private var degradedUntilNanos = 0L
    private var lastFrameBucket = Long.MIN_VALUE
    private var visible = false

    fun show() { visible = true; requestFrame() }
    fun hide() { visible = false; cancelWake() }

    fun update(frameTimeNanos: Long, previousFrameNanos: Long,
        contentVisible: Boolean, motionAllowed: Boolean, hasSongs: Boolean): Boolean {
        if (!contentVisible) { freezeAt(frameTimeNanos); return false }
        if (!motionAllowed) {
            freezeAt(StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS, forceFrame = true)
            return false
        }
        if (frameTimeNanos < degradedUntilNanos) {
            freezeAt(frameTimeNanos)
            if (visible) replaceWake((degradedUntilNanos - frameTimeNanos + 999_999L) / 1_000_000L)
            return false
        }
        if (previousFrameNanos > 0L && frameTimeNanos - previousFrameNanos >
            maxOf(24_000_000L, PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS * 2L)) slowFrames++
        else slowFrames = 0
        if (slowFrames >= 8) {
            slowFrames = 0
            degradedUntilNanos = frameTimeNanos + DEGRADE_MS * 1_000_000L
            freezeAt(frameTimeNanos)
            if (visible) replaceWake(DEGRADE_MS)
            return false
        }
        motionEnabled = hasSongs
        if (motionEnabled) frozenFrameNanos = 0L
        if (!motionEnabled) return false
        val bucket = frameTimeNanos / PLAYER_SURFACE_AMBIENT_FRAME_INTERVAL_NANOS
        if (bucket == lastFrameBucket) return false
        lastFrameBucket = bucket
        return true
    }

    fun freezeAt(frameTimeNanos: Long, forceFrame: Boolean = false) {
        val frozen = frameTimeNanos.coerceAtLeast(1L)
        if (motionEnabled || frozenFrameNanos <= 0L || forceFrame && frozenFrameNanos != frozen) {
            frozenFrameNanos = frozen
            invalidateAura()
        }
        motionEnabled = false
    }

    private companion object { const val DEGRADE_MS = 10_000L }
}
