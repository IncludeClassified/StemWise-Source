package app.stemwise.ui.player.surface

import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.design.StemwiseMotionEvent

internal interface PlayerToolMotionHost {
    val generation: Long?
    val sourceMotionPending: Boolean
    val sourceMotionActive: Boolean
    val controlsValid: Boolean
    fun targetEquals(target: PlayerSurfaceToolMotionTarget): Boolean
    fun captureRendered()
    fun copyGeometryTarget(target: PlayerSurfaceToolMotionTarget)
    fun configureRigid()
    fun applyEndpoint(target: PlayerSurfaceToolMotionTarget)
    fun started(generation: Long, epoch: Long)
    fun scheduleFrame()
}

/** Sole tool/panel clock. The host retains layout and drawing geometry, never a second clock. */
internal class PlayerToolMotionOwner(
    private val host: PlayerToolMotionHost,
    private val frameClock: () -> Long
) {
    var toolMotionInitialized = false
    var toolMotionActive = false
    var toolMotionGeneration = Long.MIN_VALUE
    var toolMotionEpoch = Long.MIN_VALUE
    var toolMotionSelectedTool = PlayerSurfaceTool.NONE
    var toolMotionStartFrameNanos = 0L
    var toolMotionEndFrameNanos = 0L
    var toolMotionDurationNanos = 0L
    var toolMotionPanelTransition = PlayerSurfacePanelTransition.NONE
    var toolMotionPanelContentStartFrameNanos = 0L
    var toolMotionPanelContentDurationNanos = 0L
    var lastToolMotionPanelContentEasedFraction = 1f
    var lastToolMotionFrameNanos = 0L

    fun retarget(target: PlayerSurfaceToolMotionTarget): Boolean {
        if (host.generation != target.generation) return false
        val durationMs = if ((host.sourceMotionPending || host.sourceMotionActive) && target.durationMs > 0) {
            StemwiseSemanticMotion.token(StemwiseMotionEvent.PLAYER_SOURCE_CHANGE).durationMs
        } else target.durationMs
        if (
            toolMotionInitialized &&
            target.generation == toolMotionGeneration &&
            target.selectionEpoch < toolMotionEpoch
        ) return false

        val sameEpoch = !host.sourceMotionPending && toolMotionInitialized &&
            target.generation == toolMotionGeneration &&
            target.selectionEpoch == toolMotionEpoch
        if (sameEpoch && toolMotionActive && target.durationMs <= 0 &&
            (target.panelTransition == PlayerSurfacePanelTransition.NONE || target.panelContentDurationMs <= 0)
        ) {
            copyTarget(target)
            endpoint(target)
            return true
        }
        if (sameEpoch && host.targetEquals(target)) return true

        if (sameEpoch && toolMotionActive) {
            if (toolMotionStartFrameNanos == 0L) {
                host.captureRendered()
                copyTarget(target)
                host.configureRigid()
                toolMotionDurationNanos = durationMs.toLong() * 1_000_000L
                host.scheduleFrame()
                return true
            }
            val now = lastToolMotionFrameNanos.takeIf { it > 0L } ?: frameClock()
            val remainingNanos = (toolMotionEndFrameNanos - now).coerceAtLeast(0L)
            host.captureRendered()
            copyTarget(target)
            host.configureRigid()
            toolMotionStartFrameNanos = now
            toolMotionDurationNanos = remainingNanos
            toolMotionEndFrameNanos = now + remainingNanos
            host.scheduleFrame()
            return true
        }
        if (sameEpoch) {
            copyTarget(target)
            endpoint(target)
            return true
        }

        val firstForOwner = !toolMotionInitialized || target.generation != toolMotionGeneration
        toolMotionInitialized = true
        toolMotionGeneration = target.generation
        toolMotionEpoch = target.selectionEpoch
        toolMotionSelectedTool = target.selectedTool
        host.captureRendered()
        copyTarget(target)
        host.configureRigid()
        toolMotionPanelContentStartFrameNanos = 0L
        lastToolMotionPanelContentEasedFraction =
            if (target.panelTransition == PlayerSurfacePanelTransition.NONE) 1f else 0f
        host.started(target.generation, target.selectionEpoch)

        val panelContentMotionEnabled =
            target.panelTransition != PlayerSurfacePanelTransition.NONE &&
                target.panelContentDurationMs > 0
        if (
            firstForOwner ||
            !host.controlsValid ||
            (target.durationMs <= 0 && !panelContentMotionEnabled)
        ) {
            endpoint(target)
            return true
        }

        toolMotionDurationNanos = durationMs.toLong() * 1_000_000L
        toolMotionStartFrameNanos = 0L
        toolMotionEndFrameNanos = 0L
        toolMotionPanelContentStartFrameNanos = 0L
        lastToolMotionFrameNanos = 0L
        toolMotionActive = true
        host.scheduleFrame()
        return true
    }

    var rawFraction = 1f
        private set

    private fun copyTarget(target: PlayerSurfaceToolMotionTarget) {
        toolMotionSelectedTool = target.selectedTool
        toolMotionPanelTransition = target.panelTransition
        toolMotionPanelContentDurationNanos = target.panelContentDurationMs.toLong() * 1_000_000L
        host.copyGeometryTarget(target)
    }

    private fun endpoint(target: PlayerSurfaceToolMotionTarget) {
        finishClock()
        host.applyEndpoint(target)
    }

    fun advance(frameTimeNanos: Long): Boolean {
        if (!toolMotionActive || host.generation != toolMotionGeneration) return false
        if (toolMotionStartFrameNanos == 0L) {
            toolMotionStartFrameNanos = frameTimeNanos
            toolMotionEndFrameNanos = frameTimeNanos + toolMotionDurationNanos
            toolMotionPanelContentStartFrameNanos = frameTimeNanos
        }
        lastToolMotionFrameNanos = frameTimeNanos
        rawFraction = if (toolMotionDurationNanos <= 0L) 1f else
            ((frameTimeNanos - toolMotionStartFrameNanos).toDouble() / toolMotionDurationNanos.toDouble())
                .toFloat().coerceIn(0f, 1f)
        lastToolMotionPanelContentEasedFraction = playerSurfacePanelContentProgress(
            frameTimeNanos - toolMotionPanelContentStartFrameNanos,
            toolMotionPanelContentDurationNanos, toolMotionPanelTransition)
        return true
    }

    fun finishClock() {
        toolMotionActive = false
        toolMotionStartFrameNanos = 0L
        toolMotionEndFrameNanos = 0L
        toolMotionDurationNanos = 0L
        toolMotionPanelContentStartFrameNanos = 0L
        lastToolMotionPanelContentEasedFraction = 1f
        lastToolMotionFrameNanos = 0L
        rawFraction = 1f
    }

    fun adoptGeneration(generation: Long) {
        finishClock()
        toolMotionGeneration = generation
        toolMotionEpoch = Long.MIN_VALUE
    }

    fun reset() {
        finishClock()
        toolMotionInitialized = false
        toolMotionGeneration = Long.MIN_VALUE
        toolMotionEpoch = Long.MIN_VALUE
        toolMotionSelectedTool = PlayerSurfaceTool.NONE
        toolMotionPanelTransition = PlayerSurfacePanelTransition.NONE
        toolMotionPanelContentDurationNanos = 0L
    }
}
