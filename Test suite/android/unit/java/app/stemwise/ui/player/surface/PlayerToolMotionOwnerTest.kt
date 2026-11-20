package app.stemwise.ui.player.surface

import org.junit.Assert.*
import org.junit.Test

class PlayerToolMotionOwnerTest {
    @Test fun sameEpochGeometryRetargetKeepsItsOriginalDeadlineAndRejectsOlderCommands() {
        val host = Host()
        val owner = PlayerToolMotionOwner(host) { 1_500_000_000L }
        assertTrue(owner.retarget(target(0)))
        assertFalse(owner.toolMotionActive)
        assertTrue(owner.retarget(target(1)))
        assertTrue(owner.advance(1_000_000_000L))
        assertTrue(owner.advance(1_500_000_000L))
        assertEquals(.5f, owner.rawFraction, 0f)
        assertTrue(owner.retarget(target(1).copy(expandedArtworkSizePx = 250f)))
        assertEquals(2_000_000_000L, owner.toolMotionEndFrameNanos)
        assertEquals(500_000_000L, owner.toolMotionDurationNanos)
        val commands = host.targets.size
        assertFalse(owner.retarget(target(0)))
        assertEquals(commands, host.targets.size)
        assertEquals(listOf(1L to 0L, 1L to 1L), host.started)
    }

    @Test fun ownerReplacementAndDetachRetireTheClockBeforeAnyLateFrameCanPublish() {
        val host = Host()
        val owner = PlayerToolMotionOwner(host) { 1_000_000_000L }
        owner.retarget(target(0)); owner.retarget(target(1))
        owner.advance(1_000_000_000L)
        host.generation = 2
        assertFalse(owner.advance(1_500_000_000L))
        assertFalse(owner.retarget(target(2)))
        owner.adoptGeneration(2)
        assertFalse(owner.toolMotionActive)
        assertTrue(owner.retarget(target(0).copy(generation = 2)))
        owner.reset()
        assertFalse(owner.advance(3_000_000_000L))
        assertFalse(owner.toolMotionInitialized)
    }

    private class Host : PlayerToolMotionHost {
        override var generation: Long? = 1
        override val sourceMotionPending = false
        override val sourceMotionActive = false
        override val controlsValid = true
        val targets = mutableListOf<PlayerSurfaceToolMotionTarget>()
        val started = mutableListOf<Pair<Long, Long>>()
        override fun targetEquals(target: PlayerSurfaceToolMotionTarget) = targets.lastOrNull() == target
        override fun captureRendered() = Unit
        override fun copyGeometryTarget(target: PlayerSurfaceToolMotionTarget) { targets += target }
        override fun configureRigid() = Unit
        override fun applyEndpoint(target: PlayerSurfaceToolMotionTarget) = Unit
        override fun started(generation: Long, epoch: Long) { started += generation to epoch }
        override fun scheduleFrame() = Unit
    }
    companion object {
        private fun target(epoch: Long) = PlayerSurfaceToolMotionTarget(
            1, epoch, PlayerSurfaceTool.LOOP, 1_000,
            expandedArtworkSizePx = 300f, mixerCardHeightPx = 0f, mixerSpacingPx = 0f,
            listSpacingPx = 12f, seekerTrackHeightPx = 60f, transportHeightPx = 72f,
            controlBounds = PlayerSurfaceBounds(0f, 100f, 500f, 700f), detailViewportHeightPx = 100f)
    }
}
