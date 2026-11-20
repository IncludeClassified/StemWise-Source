package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class FrameConflatedPublicationTest {
    @Test
    fun hundredsOfNativeClockOffersRequestOneUiFrameAndDrainTheNewestValue() {
        val publication = FrameConflatedPublication<Long>()
        var requestedFrames = 0

        repeat(500) { index ->
            if (publication.offer(index.toLong())) requestedFrames++
        }

        assertEquals(1, requestedFrames)
        assertTrue(publication.frameScheduledForTest())
        assertEquals(499L, publication.drain())
        assertFalse(publication.frameScheduledForTest())
        assertNull(publication.drain())
    }

    @Test
    fun structuralPublicationCanDiscardStalePositionWithoutPostingAnotherFrame() {
        val publication = FrameConflatedPublication<String>()

        assertTrue(publication.offer("old-owner@120"))
        publication.discardPending()
        assertFalse(publication.offer("new-owner@0"))

        assertEquals("new-owner@0", publication.drain())
    }

    @Test
    fun terminalDrainCancelsPendingPreviewAndNextGestureSchedulesFreshFrame() {
        val publication = FrameConflatedPublication<String>()

        assertTrue(publication.offer("stale-move"))
        assertEquals("stale-move", publication.drain())
        assertFalse(publication.frameScheduledForTest())

        assertTrue(publication.offer("next-gesture"))
        assertEquals("next-gesture", publication.drain())
        assertFalse(publication.frameScheduledForTest())
    }
}
