package app.stemwise.playback.session

import org.junit.Assert.*
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class MediaSessionPublicationCadenceTest {
    @Test fun pauseCannotBeCoalescedBehindAnUnpublishedLoadingOwner() {
        val a = MediaSessionSnapshot(media = SessionMediaDescription("A", "A"), loading = true,
            playWhenReady = true)
        val b = a.copy(media = SessionMediaDescription("B", "B"))
        val cadence = MediaSessionPublicationCadence(0L)
        assertTrue(requireNotNull(cadence.offer(a, b, 10L)) > 0L)
        assertEquals(0L, cadence.offer(a, b.copy(playWhenReady = false), 20L))
        assertNull(cadence.pending)
        assertNull(cadence.drain(250L))
    }

    @Test fun latestEqualityCancelsOlderDelayedPublicationWithOrWithoutForce() {
        val a = MediaSessionSnapshot(media = SessionMediaDescription("song", "Song"), positionMs = 100L, playing = true, playWhenReady = true)
        val b = a.copy(positionMs = 110L)
        for (forced in listOf(false, true)) {
            val cadence = MediaSessionPublicationCadence(0L)
            assertTrue(requireNotNull(cadence.offer(a, b, 10L)) > 0L)
            assertEquals(b, cadence.pending)
            assertNull(cadence.offer(a, a, 11L, forced))
            assertNull(cadence.drain(1_000L))
        }
    }

    @Test fun forwardPositionsCoalesceAndImmediateDiscontinuityRetiresThePendingSlot() {
        val a = MediaSessionSnapshot(media = SessionMediaDescription("song", "Song"), positionMs = 100L, playing = true, playWhenReady = true)
        val cadence = MediaSessionPublicationCadence(0L)
        val b = a.copy(positionMs = 110L)
        val c = a.copy(positionMs = 120L)
        assertTrue(requireNotNull(cadence.offer(a, b, 10L)) > 0L)
        assertTrue(requireNotNull(cadence.offer(a, c, 20L)) > 0L)
        assertEquals(c, cadence.drain(500L))
        assertNull(cadence.drain(501L))
        cadence.offer(c, c.copy(positionMs = 130L), 510L)
        val seek = a.copy(positionMs = 1L)
        assertEquals(0L, cadence.offer(c, seek, 511L, true))
        assertNull(cadence.drain(1_000L))
    }
}
