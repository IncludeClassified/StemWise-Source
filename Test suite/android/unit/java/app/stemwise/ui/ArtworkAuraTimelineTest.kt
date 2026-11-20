package app.stemwise.ui

import app.stemwise.ui.design.StemwiseSemanticMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkAuraTimelineTest {
    @Test
    fun temporarySuppressionFreezesTheRenderedPhaseAndExcludesThePausedInterval() {
        val timeline = ArtworkAuraTimeline()
        timeline.configure(enabled = true, reducedMotion = false, nowNanos = 10_000_000_000L)
        timeline.advance(11_000_000_000L)
        timeline.configure(enabled = false, reducedMotion = false, nowNanos = 12_000_000_000L)
        assertEquals(11_000_000_000L, timeline.frameNanos)
        timeline.advance(19_000_000_000L)
        assertEquals(11_000_000_000L, timeline.frameNanos)

        timeline.configure(enabled = true, reducedMotion = false, nowNanos = 20_000_000_000L)
        assertEquals(11_000_000_000L, timeline.frameNanos)
        // The second retained band shares this owner and must not restart its sampling origin.
        timeline.configure(enabled = true, reducedMotion = false, nowNanos = 20_100_000_000L)
        timeline.advance(20_200_000_000L)
        assertEquals(11_200_000_000L, timeline.frameNanos)
    }

    @Test
    fun reducedMotionSelectsItsStaticFrameEvenWhileTemporarilySuppressed() {
        val timeline = ArtworkAuraTimeline()
        timeline.configure(enabled = true, reducedMotion = false, nowNanos = 10_000_000_000L)
        timeline.advance(11_000_000_000L)
        timeline.configure(enabled = false, reducedMotion = false, nowNanos = 12_000_000_000L)
        timeline.configure(enabled = false, reducedMotion = true, nowNanos = 13_000_000_000L)
        timeline.advance(14_000_000_000L)
        assertEquals(StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS, timeline.frameNanos)

        timeline.configure(enabled = true, reducedMotion = false, nowNanos = 20_000_000_000L)
        assertEquals(StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS, timeline.frameNanos)
        timeline.advance(20_200_000_000L)
        assertEquals(StemwiseSemanticMotion.ARTWORK_AURA_STATIC_MID_FRAME_NANOS + 200_000_000L,
            timeline.frameNanos)
    }
}
