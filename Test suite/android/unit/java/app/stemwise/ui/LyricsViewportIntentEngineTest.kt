package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LyricsViewportIntentEngineTest {
    private var sequence = 0L
    private var nowNanos = 0L

    @Test
    fun pausedLoopLyricSeekRepositionsToTheEffectiveCueEvenWhenTimingDidNotChange() {
        val lines = listOf(
            app.stemwise.model.TimedLyricLine(1_000L, 2_000L, "Loop cue"),
            app.stemwise.model.TimedLyricLine(5_000L, 6_000L, "Tapped cue")
        )
        val timing = LyricLineTimingIndex(lines)
        val synchronizer = LyricPlaybackSynchronizer()
        synchronizer.replaceTrack("track", lines, 1_000L)
        val effective = loopAwareLyricSeekTarget(5_000L, 1_000L, 2_000L, 10_000L)
        assertEquals(1_000L, effective)
        assertNull(synchronizer.updatePosition(effective))
        val engine = followingEngine(activeIndex = 1)
        engine.reduce(LyricsViewportEvent.ContextReposition(timing.activeAt(effective),
            LyricsContextRepositionReason.LyricTapSeek, meta()))
        assertEquals(0, engine.state.activeIndex)
        assertEquals(1, timing.activeAt(5_000L))
    }

    @Test
    fun `rewinds and conflated cue jumps remain discontinuities across same track refresh`() {
        val lines = List(8) { index ->
            app.stemwise.model.TimedLyricLine(index * 1000L, (index + 1) * 1000L, "cue $index")
        }
        val synchronizer = LyricPlaybackSynchronizer()
        synchronizer.replaceTrack("song", lines, 0L)
        assertEquals(LyricsActiveIndexChangeCause.OrdinaryPlayback,
            synchronizer.updatePosition(1_000L)?.cause)
        assertEquals(LyricsActiveIndexChangeCause.PositionDiscontinuity,
            synchronizer.updatePosition(7_000L)?.cause)
        assertEquals(LyricsActiveIndexChangeCause.PositionDiscontinuity,
            synchronizer.replaceTrack("song", lines, 2_000L).cause)
        assertEquals(LyricsActiveIndexChangeCause.OrdinaryPlayback,
            synchronizer.updatePosition(3_000L)?.cause)
    }

    @Test
    fun `new playback destinations replace an in flight context scroll`() {
        val engine = followingEngine()
        val reason = LyricsContextRepositionReason.PlaybackSeekCommitted
        engine.reduce(LyricsViewportEvent.ContextReposition(127, reason, meta()))
        engine.reduce(LyricsViewportEvent.AutoScrollStarted(
            31L, LyricsAutoScrollRequest.ContextPosition(127, reason), meta()
        ))

        val latest = engine.reduce(LyricsViewportEvent.ActiveIndexChanged(
            127, 3333, LyricsActiveIndexChangeCause.OrdinaryPlayback, meta()
        ))
        assertEquals(3333, latest.state.activeIndex)
        assertEquals(listOf(LyricsViewportEffect.ContextPosition(3333, reason)), latest.effects)
    }

    @Test
    fun `layout reflow restarts only the owned unfinished context destination`() {
        val engine = followingEngine()
        val reason = LyricsContextRepositionReason.PlaybackSeekCommitted
        engine.reduce(LyricsViewportEvent.ContextReposition(255, reason, meta()))
        engine.reduce(LyricsViewportEvent.AutoScrollStarted(
            41L, LyricsAutoScrollRequest.ContextPosition(255, reason), meta()
        ))
        val changed = engine.reduce(LyricsViewportEvent.LayoutEpochChanged(meta(layoutEpoch = 1L)))
        assertEquals(listOf(
            LyricsViewportEffect.CancelOwnedScroll(LyricsAutoScrollCancelReason.LayoutChanged),
            LyricsViewportEffect.ContextPosition(255, reason)
        ), changed.effects)
        assertNull(changed.state.manualLatch)
    }

    @Test
    fun `pointer tap freezes then restores follow without a latch`() {
        val engine = followingEngine()
        val down = engine.reduce(LyricsViewportEvent.PointerDown(7L, meta()))

        assertTrue(down.state.mode is LyricsViewportMode.PointerHold)
        assertTrue(down.effects.single() is LyricsViewportEffect.CancelOwnedScroll)
        assertNull(down.state.manualLatch)

        val up = engine.reduce(LyricsViewportEvent.PointerUpOrCancel(7L, meta()))
        assertTrue(up.state.mode is LyricsViewportMode.Following)
        assertNull(up.state.manualLatch)
        assertTrue(up.effects.isEmpty())
    }

    @Test
    fun `recognized two pixel drag owns viewport even with tiny travel`() {
        val engine = followingEngine()
        engine.reduce(LyricsViewportEvent.DirectDragStarted(1L, LyricsUserInputSource.Touch, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(1L, LyricsUserInputSource.Touch, 2f, meta()))
        val ended = engine.reduce(LyricsViewportEvent.DirectDragEnded(1L, meta()))

        assertTrue(ended.state.mode is LyricsViewportMode.Detached)
        assertNotNull(ended.state.manualLatch)
        assertEquals(2f, ended.state.manualLatch!!.totalTravelPx, 0f)
    }

    @Test
    fun `same gesture returning to zero displacement stays detached`() {
        val engine = followingEngine()
        engine.reduce(LyricsViewportEvent.DirectDragStarted(1L, LyricsUserInputSource.Touch, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(1L, LyricsUserInputSource.Touch, 80f, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(1L, LyricsUserInputSource.Touch, -80f, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        engine.reduce(LyricsViewportEvent.DirectDragEnded(1L, meta()))

        repeat(4) {
            advance(200)
            engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        }

        assertTrue(engine.state.mode is LyricsViewportMode.Detached)
        assertEquals(0f, engine.state.manualLatch!!.netDisplacementPx, 0f)
        assertTrue(engine.state.manualLatch!!.totalTravelPx >= 160f)
    }

    @Test
    fun `later clean return reacquires with zero movement`() {
        val engine = followingEngine()
        detachAt(engine, gestureId = 1L, activeY = 720f)

        engine.reduce(LyricsViewportEvent.DirectDragStarted(2L, LyricsUserInputSource.Touch, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(2L, LyricsUserInputSource.Touch, -260f, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        engine.reduce(LyricsViewportEvent.DirectDragEnded(2L, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        advance(130)
        val candidate = engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))

        assertTrue(candidate.state.mode is LyricsViewportMode.ReacquireCandidate)
        assertTrue(candidate.effects.isEmpty())

        nowNanos += 159_999_999L
        val early = engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        assertTrue(early.state.mode is LyricsViewportMode.ReacquireCandidate)
        assertTrue(early.effects.isEmpty())
        nowNanos += 1L
        val soft = engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        assertTrue(soft.state.mode is LyricsViewportMode.SoftFollowing)
        assertTrue("implicit handoff must move zero pixels", soft.effects.isEmpty())
    }

    @Test
    fun `playback catches an ahead viewport without moving at handoff`() {
        val engine = followingEngine(activeY = 420f, activeIndex = 2)
        detachAt(engine, gestureId = 1L, activeY = 210f)

        engine.reduce(
            LyricsViewportEvent.ActiveIndexChanged(
                previousIndex = 2,
                newIndex = 3,
                cause = LyricsActiveIndexChangeCause.OrdinaryPlayback,
                meta = meta()
            )
        )
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f, activeIndex = 3), meta()))
        advance(130)
        val candidate = engine.reduce(
            LyricsViewportEvent.GeometryChanged(snapshot(420f, activeIndex = 3), meta())
        )

        assertTrue(candidate.state.mode is LyricsViewportMode.ReacquireCandidate)
        assertTrue(candidate.effects.isEmpty())
        advance(170)
        val soft = engine.reduce(
            LyricsViewportEvent.GeometryChanged(snapshot(420f, activeIndex = 3), meta())
        )
        assertTrue(soft.state.mode is LyricsViewportMode.SoftFollowing)
        assertTrue(soft.effects.isEmpty())
    }

    @Test fun ordinarySilentGapRetainsCatchUpEvidenceWithoutHighlightingSilence() {
        val synchronizer = LyricPlaybackSynchronizer()
        val lines = List(4) { index -> app.stemwise.model.TimedLyricLine(index * 2_000L,
            index * 2_000L + 1_000L, "cue $index") }
        synchronizer.replaceTrack("gapped", lines, 4_000L)
        val engine = followingEngine(activeY = 420f, activeIndex = 2)
        detachAt(engine, gestureId = 1L, activeY = 210f)
        fun position(time: Long) {
            val update = requireNotNull(synchronizer.updatePosition(time))
            engine.reduce(LyricsViewportEvent.ActiveIndexChanged(update.previousIndex, update.newIndex, update.cause, meta()))
        }
        position(5_500L)
        assertEquals(-1, engine.state.activeIndex)
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f, activeIndex = 3), meta()))
        position(6_000L)
        assertTrue(engine.state.pendingPlaybackCatchUp)
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f, activeIndex = 3), meta()))
        advance(130)
        assertTrue(engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f, 3), meta())).state.mode is LyricsViewportMode.ReacquireCandidate)
        advance(170)
        val caught = engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f, 3), meta()))
        assertTrue(caught.state.mode is LyricsViewportMode.SoftFollowing)
        assertTrue(caught.effects.isEmpty())
    }

    @Test fun rewindingInsideSilenceCannotReuseOrdinaryCatchUpContinuity() {
        val synchronizer = LyricPlaybackSynchronizer()
        val lines = List(4) { index -> app.stemwise.model.TimedLyricLine(index * 2_000L,
            index * 2_000L + 1_000L, "cue $index") }
        synchronizer.replaceTrack("gapped", lines, 4_000L)
        assertEquals(-1, requireNotNull(synchronizer.updatePosition(5_500L)).newIndex)
        assertNull(synchronizer.updatePosition(5_100L))
        assertEquals(LyricsActiveIndexChangeCause.PositionDiscontinuity,
            requireNotNull(synchronizer.updatePosition(6_000L)).cause)
    }

    @Test
    fun `layout epoch rejects an existing candidate`() {
        val engine = followingEngine()
        detachAt(engine, 1L, 720f)
        engine.reduce(LyricsViewportEvent.DirectDragStarted(2L, LyricsUserInputSource.Touch, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(2L, LyricsUserInputSource.Touch, -260f, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        engine.reduce(LyricsViewportEvent.DirectDragEnded(2L, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        advance(130)
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        assertTrue(engine.state.mode is LyricsViewportMode.ReacquireCandidate)

        val changed = engine.reduce(
            LyricsViewportEvent.LayoutEpochChanged(meta(layoutEpoch = 1L))
        )
        assertTrue(changed.state.mode is LyricsViewportMode.Detached)
        assertNull(changed.state.candidate)
        assertTrue(changed.effects.single() is LyricsViewportEffect.CancelOwnedScroll)
    }

    @Test
    fun `stale actuator completion cannot restore follow`() {
        val engine = followingEngine()
        val context = engine.reduce(
            LyricsViewportEvent.ContextReposition(
                5,
                LyricsContextRepositionReason.LyricTapSeek,
                meta()
            )
        )
        val request = context.effects.single() as LyricsViewportEffect.ContextPosition
        val actuatorRequest = LyricsAutoScrollRequest.ContextPosition(
            request.activeIndex,
            request.reason
        )
        engine.reduce(LyricsViewportEvent.AutoScrollStarted(11L, actuatorRequest, meta()))
        engine.reduce(LyricsViewportEvent.PointerDown(9L, meta()))
        val late = engine.reduce(
            LyricsViewportEvent.AutoScrollCompleted(11L, actuatorRequest, meta())
        )

        assertTrue(late.state.mode is LyricsViewportMode.PointerHold)
        assertNull(late.state.activeActuatorToken)
    }

    @Test
    fun `time and stable proximity alone never clear a manual latch`() {
        val engine = followingEngine()
        detachAt(engine, 1L, 420f)

        repeat(100) {
            advance(50)
            val reduction = engine.reduce(
                LyricsViewportEvent.GeometryChanged(snapshot(420f), meta())
            )
            assertFalse(reduction.state.mode is LyricsViewportMode.Following)
            assertNotNull(reduction.state.manualLatch)
            assertTrue(reduction.effects.isEmpty())
        }
    }

    @Test
    fun `following recovers an offscreen line without fabricating user ownership`() {
        val engine = followingEngine()
        val changed = engine.reduce(
            LyricsViewportEvent.ActiveIndexChanged(
                previousIndex = 5,
                newIndex = 12,
                cause = LyricsActiveIndexChangeCause.OrdinaryPlayback,
                meta = meta()
            )
        )

        assertTrue(changed.state.mode is LyricsViewportMode.ContextPositioning)
        assertNull(changed.state.manualLatch)
        assertTrue(changed.effects.any { it is LyricsViewportEffect.ContextPosition })
    }

    @Test
    fun `context scroll cancelled by a tap restores its prior follow mode`() {
        val engine = followingEngine()
        val context = LyricsAutoScrollRequest.ContextPosition(
            5,
            LyricsContextRepositionReason.LyricTapSeek
        )
        engine.reduce(LyricsViewportEvent.AutoScrollStarted(21L, context, meta()))
        engine.reduce(
            LyricsViewportEvent.AutoScrollCancelled(
                21L,
                LyricsAutoScrollCancelReason.PointerDown,
                meta()
            )
        )
        engine.reduce(LyricsViewportEvent.PointerDown(4L, meta()))
        val released = engine.reduce(LyricsViewportEvent.PointerUpOrCancel(4L, meta()))

        assertTrue(released.state.mode is LyricsViewportMode.Following)
        assertNull(released.state.manualLatch)
    }

    @Test
    fun `event replay is deterministic`() {
        val events = listOf(
            LyricsViewportEvent.ActiveIndexChanged(
                -1,
                5,
                LyricsActiveIndexChangeCause.OrdinaryPlayback,
                meta()
            ),
            LyricsViewportEvent.GeometryChanged(snapshot(720f), meta()),
            LyricsViewportEvent.DirectDragStarted(1L, LyricsUserInputSource.Touch, meta()),
            LyricsViewportEvent.UserScrollDelta(1L, LyricsUserInputSource.Touch, -90f, meta()),
            LyricsViewportEvent.DirectDragEnded(1L, meta()),
            LyricsViewportEvent.GeometryChanged(snapshot(630f), meta())
        )
        val first = LyricsViewportIntentEngine()
        val second = LyricsViewportIntentEngine()

        val firstReplay = events.map(first::reduce)
        val secondReplay = events.map(second::reduce)

        assertEquals(firstReplay, secondReplay)
    }

    @Test
    fun `ambiguous randomized events preserve a manual latch`() {
        val engine = followingEngine(activeY = 720f)
        detachAt(engine, 1L, 720f)
        val random = Random(91)

        repeat(500) {
            advance(random.nextLong(1L, 40L))
            val reduction = when (random.nextInt(4)) {
                0 -> engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(720f), meta()))
                1 -> engine.reduce(
                    LyricsViewportEvent.ActiveIndexChanged(
                        5,
                        5,
                        LyricsActiveIndexChangeCause.OrdinaryPlayback,
                        meta()
                    )
                )
                2 -> {
                    engine.reduce(LyricsViewportEvent.PointerDown(200L + it, meta()))
                    engine.reduce(LyricsViewportEvent.PointerUpOrCancel(200L + it, meta()))
                }
                else -> engine.reduce(LyricsViewportEvent.LayoutEpochChanged(meta()))
            }
            assertFalse(reduction.state.mode is LyricsViewportMode.Following)
            assertNotNull(reduction.state.manualLatch)
            assertNull(reduction.state.activeActuatorToken)
            assertTrue(reduction.effects.none { effect -> effect is LyricsViewportEffect.FollowBy })
        }
    }

    @Test
    fun `lifecycle stop and resume preserve manual ownership`() {
        val engine = followingEngine(activeY = 720f)
        detachAt(engine, 1L, 720f)

        engine.reduce(LyricsViewportEvent.LifecycleChanged(false, meta()))
        val resumed = engine.reduce(LyricsViewportEvent.LifecycleChanged(true, meta()))

        assertTrue(resumed.state.mode is LyricsViewportMode.Detached)
        assertNotNull(resumed.state.manualLatch)
        assertNull(resumed.state.activeActuatorToken)
    }

    @Test
    fun `screen hiding rejects a pending candidate without clearing ownership`() {
        val engine = followingEngine()
        detachAt(engine, 1L, 720f)
        engine.reduce(LyricsViewportEvent.DirectDragStarted(2L, LyricsUserInputSource.Touch, meta()))
        engine.reduce(LyricsViewportEvent.UserScrollDelta(2L, LyricsUserInputSource.Touch, -260f, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        engine.reduce(LyricsViewportEvent.DirectDragEnded(2L, meta()))
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        advance(130)
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(420f), meta()))
        assertTrue(engine.state.mode is LyricsViewportMode.ReacquireCandidate)

        val hidden = engine.reduce(LyricsViewportEvent.ScreenVisibilityChanged(false, meta()))

        assertTrue(hidden.state.mode is LyricsViewportMode.Detached)
        assertNotNull(hidden.state.manualLatch)
        assertNull(hidden.state.candidate)
    }

    private fun followingEngine(
        activeY: Float = 420f,
        activeIndex: Int = 5
    ): LyricsViewportIntentEngine = LyricsViewportIntentEngine().also { engine ->
        engine.reduce(
            LyricsViewportEvent.ActiveIndexChanged(
                -1,
                activeIndex,
                LyricsActiveIndexChangeCause.OrdinaryPlayback,
                meta()
            )
        )
        engine.reduce(
            LyricsViewportEvent.GeometryChanged(snapshot(activeY, activeIndex), meta())
        )
    }

    private fun detachAt(
        engine: LyricsViewportIntentEngine,
        gestureId: Long,
        activeY: Float
    ) {
        engine.reduce(
            LyricsViewportEvent.DirectDragStarted(
                gestureId,
                LyricsUserInputSource.Touch,
                meta()
            )
        )
        engine.reduce(
            LyricsViewportEvent.UserScrollDelta(
                gestureId,
                LyricsUserInputSource.Touch,
                100f,
                meta()
            )
        )
        engine.reduce(LyricsViewportEvent.GeometryChanged(snapshot(activeY), meta()))
        engine.reduce(LyricsViewportEvent.DirectDragEnded(gestureId, meta()))
        assertTrue(engine.state.mode is LyricsViewportMode.Detached)
    }

    private fun snapshot(activeY: Float, activeIndex: Int = 5): LyricsViewportSnapshot {
        val item = VisibleLyricGeometry(
            index = activeIndex,
            key = "line-$activeIndex",
            offsetPx = (activeY - 30f).toInt(),
            sizePx = 60,
            focusYPx = activeY,
            visibleFraction = 1f
        )
        return LyricsViewportSnapshot(
            sequence = sequence,
            capturedAtNanos = nowNanos,
            layoutEpoch = 0L,
            viewportStartPx = 0,
            viewportEndPx = 1_000,
            viewportHeightPx = 1_000,
            firstVisibleIndex = activeIndex,
            firstVisibleOffsetPx = 0,
            lastVisibleIndex = activeIndex,
            totalItemsCount = 20,
            isScrollInProgress = false,
            canScrollBackward = true,
            canScrollForward = true,
            visibleItems = listOf(item),
            anchor = LyricsScrollAnchor(
                itemKey = item.key,
                itemIndex = activeIndex,
                itemFocusOffsetFromPreferredPx = activeY - 420f,
                firstVisibleIndex = activeIndex,
                firstVisibleOffsetPx = 0
            ),
            medianVisibleLineHeightPx = 60f,
            preferredYPx = 420f,
            followTopPx = 180f,
            followBottomPx = 680f,
            captureTopPx = 350f,
            captureBottomPx = 520f,
            preferredSeamTopPx = 375f,
            preferredSeamBottomPx = 465f
        )
    }

    private fun meta(layoutEpoch: Long = 0L): LyricsEventMeta = LyricsEventMeta(
        sequence = ++sequence,
        monotonicTimeNanos = nowNanos,
        trackEpoch = 0L,
        layoutEpoch = layoutEpoch
    )

    private fun advance(ms: Long) {
        nowNanos += ms * 1_000_000L
    }
}
