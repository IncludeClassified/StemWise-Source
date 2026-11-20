package app.stemwise.ui.player.surface

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlayerMixerGestureOwnerTest {
    @Test fun sliderZeroIsMutedAndRejectedPreviewRestoresAcceptedControls() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        owner.committedMixerVolumes[0] = .4f
        owner.liveMixerVolumes[0] = .4f
        assertTrue(owner.beginMixerPointer(1, 10, 0, 0f))
        assertEquals(0f, owner.liveMixerVolumes[0], 0f)
        assertTrue(owner.mixerMuted[0])
        host.acceptPreview = false
        owner.stageMixerPreviewsForPostFrameDrain(); host.drain()
        assertEquals(.4f, owner.liveMixerVolumes[0], 0f)
        assertFalse(owner.mixerMuted[0])
        assertEquals(-1, owner.mixerPointerIds[0])
        assertEquals(host.opened, host.cancelled)
    }

    @Test fun explicitMuteSupersedesQueuedPreviewAndUnchangedUpWithoutRetiringItsPointer() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        owner.committedMixerVolumes[0] = .4f
        owner.liveMixerVolumes[0] = .4f
        assertTrue(owner.beginMixerPointer(1, 10, 0, 80f))
        owner.stageMixerPreviewsForPostFrameDrain()
        owner.acceptMixerFlags(0, .4f, muted = true, solo = false)
        assertEquals(0f, owner.liveMixerVolumes[0], 0f)
        assertEquals(.4f, owner.committedMixerVolumes[0], 0f)
        assertEquals(10, owner.mixerPointerIds[0])
        host.drain()
        assertTrue(host.previews.isEmpty())
        owner.finishMixerPointer(0, 80f, false)
        assertEquals(0f, host.finishes.single().second, 0f)
        assertTrue(owner.committedMixerMuted[0])
        owner.acceptMixerFlags(0, .4f, muted = false, solo = false)
        assertEquals(.4f, owner.liveMixerVolumes[0], 0f)
    }

    @Test fun raisingMutedSliderThenCancellingRestoresMuteWithoutUndoingAnotherLanesSolo() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        owner.committedMixerVolumes[0] = .65f
        owner.committedMixerMuted[0] = true
        owner.mixerMuted[0] = true
        assertTrue(owner.beginMixerPointer(1, 10, 0, 30f))
        assertFalse(owner.mixerMuted[0])
        assertEquals(.3f, owner.liveMixerVolumes[0], 0f)
        owner.acceptMixerFlags(1, .8f, muted = false, solo = true)
        assertEquals(10, owner.mixerPointerIds[0])
        owner.finishMixerPointer(0, 30f, true)
        assertEquals(0f, owner.liveMixerVolumes[0], 0f)
        assertTrue(owner.mixerMuted[0])
        assertTrue(owner.mixerSolo[1])
    }

    @Test fun denseContinuousMovesKeepFeedbackBoundedAndCommitTheExactTerminalSample() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        assertTrue(owner.beginMixerPointer(1, 10, 0, 10f))
        repeat(1_000) { owner.updateMixerPointer(0, 10f + it % 80) }
        owner.finishMixerPointer(0, 73f, false)
        assertEquals(1, host.finishes.size)
        assertEquals(.73f, host.finishes.single().second, .0001f)
        assertTrue("Continuous input is limited to start/end feedback, actual=${host.ticks}", host.ticks in 1..2)
    }

    @Test fun replacementAndDetachedPointersCancelOnlyTheirCapturedHandlesAndLateDrainsStaySilent() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        assertTrue(owner.beginMixerPointer(1, 10, 0, 20f))
        assertFalse(owner.beginMixerPointer(1, 11, 0, 30f))
        assertTrue(owner.beginMixerPointer(1, 11, 1, 40f))
        owner.stageMixerPreviewsForPostFrameDrain()
        val captured = host.opened.toList()
        host.profile = profile().copy(owner = profile().owner.copy(generation = 2))
        owner.cancelMixerGestures(seal = false)
        host.drain()
        assertEquals(captured, host.cancelled)
        assertTrue(host.previews.isEmpty())
        assertTrue(host.finishes.isEmpty())
        assertTrue(owner.mixerPointerIds.all { it == -1 })
        assertEquals(0, owner.mixerPendingPreviewMask)
        assertEquals(0, owner.mixerDrainPreviewMask)
    }

    @Test fun frameBatchBorrowsTheSamePrimitiveBufferAndRejectingTerminalRestoresCommittedValue() {
        val host = Host()
        val owner = PlayerMixerGestureOwner(host)
        owner.committedMixerVolumes[0] = .4f
        assertTrue(owner.beginMixerPointer(1, 10, 0, 20f))
        owner.stageMixerPreviewsForPostFrameDrain(); host.drain()
        owner.updateMixerPointer(0, 60f)
        owner.stageMixerPreviewsForPostFrameDrain(); host.drain()
        assertEquals(listOf(.2f, .6f), host.previews)
        assertSame(host.buffers[0], host.buffers[1])
        host.acceptFinish = false
        owner.finishMixerPointer(0, 80f, false)
        assertEquals(.4f, owner.liveMixerVolumes[0], 0f)
        assertEquals(host.opened, host.cancelled)
        val count = host.previews.size
        host.drain()
        assertEquals(count, host.previews.size)
    }

    private class Host : PlayerMixerGestureHost {
        override var profile: PlayerSurfaceProfilePacket? = profile()
        override val liveSourceMode = PlayerSurfaceSourceMode.STEMS
        override val laneSliderBounds = FloatArray(PLAYER_SURFACE_MAX_MIXER_LANES * 4).apply {
            this[2] = 100f; this[6] = 100f
        }
        override val thumbRadiusPx = 0f
        var ticks = 0
        var acceptFinish = true
        var acceptPreview = true
        val opened = mutableListOf<PlayerSurfaceVolumeGesture>()
        val cancelled = mutableListOf<PlayerSurfaceVolumeGesture>()
        val finishes = mutableListOf<Pair<PlayerSurfaceVolumeGesture, Float>>()
        val previews = mutableListOf<Float>()
        val buffers = mutableListOf<FloatArray>()
        private val posted = ArrayDeque<Runnable>()
        override val sink = Proxy.newProxyInstance(FullPlayerActionSink::class.java.classLoader,
            arrayOf(FullPlayerActionSink::class.java)) { _, method, args ->
            when (method.name) {
                "onBeginVolume" -> PlayerSurfaceVolumeGesture(args!![0] as Long, args[1] as Int).also { opened += it }
                "onPreviewVolumeBatch" -> {
                    val mask = args!![1] as Int
                    val values = args[3] as FloatArray
                    buffers += values
                    for (lane in 0 until PLAYER_SURFACE_MAX_MIXER_LANES) if (mask and (1 shl lane) != 0) previews += values[lane]
                    acceptPreview
                }
                "onFinishVolume" -> { finishes += (args!![0] as PlayerSurfaceVolumeGesture) to (args[1] as Float); acceptFinish }
                "onCancelVolume" -> { cancelled += args!![0] as PlayerSurfaceVolumeGesture; null }
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> -1; else -> null }
            }
        } as FullPlayerActionSink
        override fun clearPendingArtworkTap() = Unit
        override fun scheduleFrame() = Unit
        override fun post(action: Runnable): Boolean { posted += action; return true }
        override fun hold(lane: Int, down: Boolean) = Unit
        override fun tick() { ticks++ }
        override fun disallowParentInterception() = Unit
        override fun releaseParentInterceptionIfIdle() = Unit
        override fun invalidateMixerRegion() = Unit
        override fun invalidateMixerRegionFromInput() = Unit
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
    }

    companion object {
        private fun profile() = PlayerSurfaceProfilePacket(1,
            PlayerSurfaceOwnerKey(1, "song", "media", "audio", "stems"),
            PlayerSurfaceQueuePacket(1, longArrayOf(1), 0),
            PlayerSurfaceTimelinePacket(1, 10_000, 100),
            PlayerSurfaceSourcePacket(1, PlayerSurfaceSourceMode.STEMS, PlayerSurfaceSourceMode.STEMS,
                PlayerSurfaceSourcePhase.SETTLED, true),
            PlayerSurfaceMixerPacket(1, 1, intArrayOf(1, 2), floatArrayOf(.4f, .8f), intArrayOf(0, 0)),
            PlayerSurfaceTransportPacket(1, true, PlayerSurfaceRepeatMode.OFF, false, true, true),
            PlayerSurfaceLayoutPacket(1, 100, 100, 1f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
    }
}
