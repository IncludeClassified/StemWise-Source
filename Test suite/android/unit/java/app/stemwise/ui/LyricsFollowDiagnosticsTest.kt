package app.stemwise.ui

import org.junit.Assert.*
import org.junit.Test

class LyricsFollowDiagnosticsTest {
    @Test fun tracingIsOffByDefaultAndTheEnabledTailRetainsCancellationReasons() {
        val diagnostics = LyricsFollowDiagnostics(capacity = 2)
        val engine = LyricsViewportIntentEngine(LyricsFollowConfig())
        val untraced = LyricsViewportIntentEngine(LyricsFollowConfig())
        fun deliver(sequence: Long, cancelled: Boolean = false) {
            val meta = LyricsEventMeta(sequence, sequence * 1_000_000L, 1L, 1L)
            val event = if (cancelled) LyricsViewportEvent.AutoScrollCancelled(7L,
                LyricsAutoScrollCancelReason.LayoutChanged, meta) else LyricsViewportEvent.TrackChanged(meta)
            val before = engine.state
            val reduction = engine.reduce(event)
            diagnostics.record(event, before, reduction)
            assertEquals(untraced.reduce(event), reduction)
        }
        deliver(1)
        assertTrue(diagnostics.snapshot().isEmpty())
        diagnostics.setEnabled(true)
        deliver(2)
        deliver(3)
        deliver(4, cancelled = true)
        assertEquals(listOf(3L, 4L), diagnostics.snapshot().map { it.eventSequence })
        assertEquals("LayoutChanged", diagnostics.snapshot().last().diagnosticReason)
        diagnostics.setEnabled(false)
        deliver(5)
        assertEquals(listOf(3L, 4L), diagnostics.snapshot().map { it.eventSequence })
    }
}
