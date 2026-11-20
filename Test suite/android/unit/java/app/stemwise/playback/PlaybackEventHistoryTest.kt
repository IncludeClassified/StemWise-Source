package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackEventHistoryTest {
    @Test fun verboseEventsRequireAnExplicitCaptureAndStoppingRetainsItsBoundedSnapshot() {
        val history = PlaybackEventHistory()
        history.record(1L, "ordinary-playback")
        assertEquals(emptyList<String>(), history.snapshot())
        history.setCaptureEnabled(true)
        history.record(2L, "requested-capture")
        val retained = history.snapshot()
        history.setCaptureEnabled(false)
        history.record(3L, "ordinary-playback")
        assertEquals(retained, history.snapshot())
        assertEquals(1, retained.size)
    }

    @Test
    fun historyIsBoundedOrderedAndSnapshotsSurviveEviction() {
        val history = PlaybackEventHistory().apply { setCaptureEnabled(true) }
        history.record(10L, "focus-loss", requestedGeneration = 7L, requestedToken = 9L,
            focusSuspended = true, resumeToken = 9L)
        val retained = history.snapshot()
        repeat(100) { history.record(11L + it, "event-$it") }
        val current = history.snapshot()
        assertEquals(listOf("1@10:focus-loss requested=9/7 committed=0/0" +
            " focus=false/false suspended=true resumeToken=9"), retained)
        assertEquals(64, current.size)
        assertEquals("38@47:event-36", current.first().substringBefore(" requested="))
        assertEquals("101@110:event-99", current.last().substringBefore(" requested="))
    }
}
