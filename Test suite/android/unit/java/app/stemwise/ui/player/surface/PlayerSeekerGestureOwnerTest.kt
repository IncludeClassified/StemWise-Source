package app.stemwise.ui.player.surface

import app.stemwise.ui.PrecisionScrubMode
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlayerSeekerGestureOwnerTest {
    @Test fun trimHandleRetainsFreeMillisecondPlacementWithoutLoopMinimumOrSeeking() {
        val host = Host().apply { minimumBoundaryDurationMs = 1L }
        val owner = PlayerSeekerGestureOwner(host)
        assertTrue(owner.beginScrubPointer(7, 10f, 10f, 0L, FullPlayerLoopBoundary.START))
        owner.scrubSensitivityMsPerPx = 1.0
        assertTrue(owner.applyScrubMovement(host.profile!!, 9_999f))
        assertEquals(14_999L, owner.scrubPreviewLoopStartMs)
        assertEquals(15_000L, owner.scrubPreviewLoopEndMs)
        assertEquals(14_999L, host.loopSamples.last().startMs)
        assertTrue(host.scrubCommands.isEmpty())
        owner.cancelScrubGesture(commit = false)
    }

    @Test fun rejectedEndpointAdmissionNeverFallsThroughToOrdinaryScrub() {
        val host = Host().apply { acceptLoopBegin = false }
        val owner = PlayerSeekerGestureOwner(host)
        assertFalse(owner.beginScrubPointer(7, 10f, 10f, 0, FullPlayerLoopBoundary.END))
        assertEquals(-1, owner.scrubPointerId)
        assertTrue(host.scrubCommands.isEmpty())
        assertEquals(0, host.ticks)
    }

    @Test fun rejectedEndpointMoveKeepsThePointerAndCancelRetriesThenEndsTheExactLocalRange() {
        val host = Host().apply { acceptLoopMove = false }
        val owner = PlayerSeekerGestureOwner(host)
        assertTrue(owner.beginScrubPointer(7, 10f, 10f, 0, FullPlayerLoopBoundary.END))
        assertTrue(host.parentDisallowed)
        owner.scrubSensitivityMsPerPx = 100.0
        assertTrue(owner.applyScrubMovement(host.profile!!, 1f))
        assertEquals(7, owner.scrubPointerId)
        assertEquals(15_100L, owner.scrubPreviewLoopEndMs)
        assertNotNull(owner.scrubPendingLoopMove)
        host.acceptLoopMove = true
        owner.cancelScrubGesture(commit = false)
        assertEquals(listOf(15_100L, 15_100L), host.loopMoves)
        assertEquals(listOf(15_100L), host.loopEnds)
        assertTrue(host.scrubCommands.isEmpty())
        assertEquals(-1, owner.scrubPointerId)
    }

    @Test fun rejectedEndpointSamplesProduceNoSuccessFeedback() {
        val host = Host().apply { acceptLoopMove = false }
        val owner = PlayerSeekerGestureOwner(host)
        owner.beginScrubPointer(7, 10f, 10f, 0, FullPlayerLoopBoundary.END)
        val admittedTicks = host.ticks
        owner.scrubSensitivityMsPerPx = 1.0
        repeat(1_000) { owner.applyScrubMovement(host.profile!!, 1f) }
        assertEquals("Rejected previews must not vibrate", admittedTicks, host.ticks)
        owner.cancelScrubGesture(commit = false)
    }

    @Test fun denseAcceptedScrubKeepsFeedbackBoundedAndCommitsItsExactFinalValue() {
        val host = Host()
        val owner = PlayerSeekerGestureOwner(host)
        owner.beginScrubPointer(7, 10f, 10f, 0, null)
        assertTrue(owner.startScrubSession(PrecisionScrubMode.FAST_TRAVERSE))
        owner.scrubSensitivityMsPerPx = 1.0
        repeat(1_000) { owner.applyScrubMovement(host.profile!!, 1f) }
        owner.finishScrubPointer(10f, 10f, 1_000, false)
        assertEquals(11_000L, host.scrubCommands.last { it.first == "onScrubCommit" }.second)
        assertTrue("Continuous feedback is limited to start/end, actual=${host.ticks}", host.ticks in 1..2)
    }

    @Test fun ownerReplacementCannotSendTheOutgoingTerminalSampleToTheNewSink() {
        val host = Host()
        val owner = PlayerSeekerGestureOwner(host)
        owner.beginScrubPointer(7, 10f, 10f, 0, null)
        owner.startScrubSession(PrecisionScrubMode.FAST_TRAVERSE)
        host.profile = host.profile!!.copy(owner = host.profile!!.owner.copy(generation = 2))
        owner.finishScrubPointer(70f, 10f, 16, false)
        assertEquals(listOf("onScrubBegin", "onScrubCancel"), host.scrubCommands.map { it.first })
        assertEquals(1L, host.cancelledGenerations.single())
        assertEquals(-1, owner.scrubPointerId)
    }

    @Test fun fineAdmissionAndExitBothConsumeTheirTriggeringHorizontalTravel() {
        val host = Host()
        val owner = PlayerSeekerGestureOwner(host)
        owner.beginScrubPointer(7, 17f, 0f, 0, null)
        owner.updateScrubPointer(27f, -60f, 16)
        assertEquals(PrecisionScrubMode.FINE, owner.scrubMode)
        val finePosition = owner.scrubPreviewPositionMs
        assertTrue(finePosition > 10_000L)
        owner.updateScrubPointer(28f, -20f, 32)
        assertEquals(PrecisionScrubMode.FAST_TRAVERSE, owner.scrubMode)
        assertTrue(owner.scrubPreviewPositionMs > finePosition)
        owner.cancelScrubGesture(commit = false)
        assertEquals("onScrubCancel", host.scrubCommands.last().first)
    }

    @Test fun terminalUpAppliesItsHorizontalAndFineEntrySampleBeforeSeekCommit() {
        val host = Host()
        val owner = PlayerSeekerGestureOwner(host)
        assertTrue(owner.beginScrubPointer(7, 17f, 0f, 100, null))
        owner.updateScrubPointer(27f, 0f, 116)
        val lastMovePosition = owner.scrubPreviewPositionMs
        host.scrubCommands.clear()

        // There is no MOVE at this final X/Y: Android can coalesce it into ACTION_UP.
        owner.finishScrubPointer(47f, -60f, 1_234, false)

        assertEquals(listOf("onScrubPreview", "onScrubCommit"), host.scrubCommands.map { it.first })
        val terminalPosition = host.scrubCommands.last().second
        assertTrue("The terminal horizontal sample was dropped", terminalPosition > lastMovePosition)
        assertEquals(terminalPosition, host.scrubCommands.first().second)
        assertTrue("The terminal Y sample never entered Fine", host.fineTransitions.first())
        assertFalse("The completed Fine gesture remained active", host.fineTransitions.last())
        assertEquals(-1, owner.scrubPointerId)
    }

    @Test fun terminalUpMovesEitherLoopEndpointBeforeReleaseWithItsExactTimestamp() {
        for (boundary in FullPlayerLoopBoundary.entries) {
            val host = Host()
            val owner = PlayerSeekerGestureOwner(host)
            assertTrue(owner.beginScrubPointer(7, 10f, 10f, 100, boundary))
            owner.updateScrubPointer(11f, 10f, 116)
            val lastMovePosition = owner.scrubPreviewPositionMs
            host.loopSamples.clear()

            owner.finishScrubPointer(31f, -50f, 1_234, false)

            assertEquals(listOf("move", "end"), host.loopSamples.map { it.kind })
            val terminalMove = host.loopSamples.first()
            val terminalEnd = host.loopSamples.last()
            val terminalPosition = if (boundary == FullPlayerLoopBoundary.START)
                terminalMove.startMs else terminalMove.endMs
            assertTrue("Terminal $boundary coordinate was dropped", terminalPosition > lastMovePosition)
            assertEquals(terminalMove.startMs, terminalEnd.startMs)
            assertEquals(terminalMove.endMs, terminalEnd.endMs)
            assertEquals(listOf(1_234_000L, 1_234_000L), host.loopSamples.map { it.inputAtMicros })
            assertTrue("Terminal $boundary Y never entered Fine", host.fineTransitions.first())
            assertFalse(host.fineTransitions.last())
            assertTrue("Endpoint UP fell through to an ordinary seek", host.scrubCommands.isEmpty())
            assertEquals(-1, owner.scrubPointerId)
        }
    }

    private data class LoopSample(val kind: String, val startMs: Long, val endMs: Long, val inputAtMicros: Long)

    private class Host : PlayerSeekerGestureHost {
        override var profile: PlayerSurfaceProfilePacket? = profile()
        override val density = 1f
        override val fineEntryThreshold = 40f
        override val fineExitThreshold = 25f
        override val seekerTrackLeft = 0f
        override val seekerTrackRight = 100f
        override val touchSlop = 8f
        override var minimumBoundaryDurationMs = 100L
        override var scrubFineReleaseActive = false
        override var scrubFineReleaseBoundary: FullPlayerLoopBoundary? = null
        var ticks = 0
        var parentDisallowed = false
        var acceptLoopBegin = true
        var acceptLoopMove = true
        val loopMoves = mutableListOf<Long>()
        val loopEnds = mutableListOf<Long>()
        val loopSamples = mutableListOf<LoopSample>()
        val fineTransitions = mutableListOf<Boolean>()
        val scrubCommands = mutableListOf<Pair<String, Long>>()
        val cancelledGenerations = mutableListOf<Long>()
        override val sink = Proxy.newProxyInstance(FullPlayerActionSink::class.java.classLoader,
            arrayOf(FullPlayerActionSink::class.java)) { _, method, args ->
            when (method.name) {
                "onLoopBoundaryBegin" -> acceptLoopBegin
                "onLoopBoundaryMove" -> {
                    loopMoves += args!![3] as Long
                    loopSamples += LoopSample("move", args[2] as Long, args[3] as Long, args[5] as Long)
                    acceptLoopMove
                }
                "onLoopBoundaryEnd" -> {
                    loopEnds += args!![3] as Long
                    loopSamples += LoopSample("end", args[2] as Long, args[3] as Long, args[5] as Long)
                    true
                }
                "onFineModeChanged" -> { fineTransitions += args!![1] as Boolean; null }
                "onScrubBegin" -> { scrubCommands += method.name to 0L; true }
                "onScrubPreview", "onScrubCommit" -> { scrubCommands += method.name to (args!![1] as Long); true }
                "onScrubCancel" -> { scrubCommands += method.name to 0L; cancelledGenerations += args!![0] as Long; null }
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Integer.TYPE -> -1; else -> null }
            }
        } as FullPlayerActionSink
        override fun frameNanos() = 1_000_000_000L
        override fun uptimeMillis() = 1_000L
        override fun clearPendingArtworkTap() = Unit
        override fun renderedPositionMs(current: PlayerSurfaceProfilePacket) = current.timeline.positionMs
        override fun clearFineReleaseVisual() { scrubFineReleaseActive = false; scrubFineReleaseBoundary = null }
        override fun animateScrubGrowthTo(target: Float, nowMs: Long, boundary: FullPlayerLoopBoundary?) = Unit
        override fun scheduleFrame() = Unit
        override fun animateWaveformMorphTo(target: Float) = Unit
        override fun invalidateTimelineRegion() = Unit
        override fun currentWaveformMorph(nowNanos: Long) = 0f
        override fun hardResetWaveformMorph() = Unit
        override fun tick() { ticks++ }
        override fun holdLoopHandle(boundary: FullPlayerLoopBoundary?) = Unit
        override fun releaseLoopHandle() = Unit
        override fun holdScrub(down: Boolean) = Unit
        override fun disallowParentInterception() { parentDisallowed = true }
    }

    companion object {
        private fun profile() = PlayerSurfaceProfilePacket(1,
            PlayerSurfaceOwnerKey(1, "song", "media", "audio", "stems"),
            PlayerSurfaceQueuePacket(1, longArrayOf(1), 0),
            PlayerSurfaceTimelinePacket(1, 60_000, 10_000, loopStartMs = 5_000, loopEndMs = 15_000),
            PlayerSurfaceSourcePacket(1, PlayerSurfaceSourceMode.ORIGINAL, PlayerSurfaceSourceMode.ORIGINAL,
                PlayerSurfaceSourcePhase.SETTLED, false),
            PlayerSurfaceMixerPacket(1, 1, intArrayOf(), floatArrayOf(), intArrayOf()),
            PlayerSurfaceTransportPacket(1, true, PlayerSurfaceRepeatMode.OFF, false, true, true),
            PlayerSurfaceLayoutPacket(1, 100, 100, 1f, PlayerSurfaceLayoutDirection.LEFT_TO_RIGHT))
    }
}
