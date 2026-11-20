package app.stemwise.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlayerSurfaceGenerationTrackerTest {
    @Test
    fun ownerThenFirstMatchingLeaseIsOneGenerationHandoff() {
        val allocator = SequentialGenerationAllocator()
        val tracker = PlayerSurfaceGenerationTracker(allocator::next)

        val outgoing = tracker.resolve(owner("song-a"), matchingLeaseEpoch = 10L)
        val ownerArrived = tracker.resolve(owner("song-b"), matchingLeaseEpoch = null)
        val firstMatchingLease = tracker.resolve(owner("song-b"), matchingLeaseEpoch = 20L)

        assertNotEquals(outgoing, ownerArrived)
        assertEquals(ownerArrived, firstMatchingLease)
        assertEquals(2, allocator.allocationCount)
    }

    @Test
    fun ownerAndItsLeaseArrivingTogetherStillAllocateOnce() {
        val allocator = SequentialGenerationAllocator()
        val tracker = PlayerSurfaceGenerationTracker(allocator::next)

        val outgoing = tracker.resolve(owner("song-a"), matchingLeaseEpoch = 10L)
        val incoming = tracker.resolve(owner("song-b"), matchingLeaseEpoch = 20L)
        val duplicate = tracker.resolve(owner("song-b"), matchingLeaseEpoch = 20L)

        assertNotEquals(outgoing, incoming)
        assertEquals(incoming, duplicate)
        assertEquals(2, allocator.allocationCount)
    }

    @Test
    fun sameOwnerLeaseEpochRolloverAllocatesReloadGeneration() {
        val allocator = SequentialGenerationAllocator()
        val tracker = PlayerSurfaceGenerationTracker(allocator::next)
        val owner = owner("song-a")

        val initial = tracker.resolve(owner, matchingLeaseEpoch = 10L)
        val transientNull = tracker.resolve(owner, matchingLeaseEpoch = null)
        val sameLease = tracker.resolve(owner, matchingLeaseEpoch = 10L)
        val reloaded = tracker.resolve(owner, matchingLeaseEpoch = 11L)

        assertEquals(initial, transientNull)
        assertEquals(initial, sameLease)
        assertNotEquals(initial, reloaded)
        assertEquals(2, allocator.allocationCount)
    }

    @Test
    fun firstLeaseAfterInitiallyLeaselessOwnerDoesNotCreateSecondGeneration() {
        val allocator = SequentialGenerationAllocator()
        val tracker = PlayerSurfaceGenerationTracker(allocator::next)
        val owner = owner("song-a")

        val ownerOnly = tracker.resolve(owner, matchingLeaseEpoch = null)
        val firstLease = tracker.resolve(owner, matchingLeaseEpoch = 10L)
        val reload = tracker.resolve(owner, matchingLeaseEpoch = 11L)

        assertEquals(ownerOnly, firstLease)
        assertNotEquals(firstLease, reload)
        assertEquals(2, allocator.allocationCount)
    }

    @Test
    fun presentationRevisionChangeAlwaysAllocatesNewOwnerGeneration() {
        val allocator = SequentialGenerationAllocator()
        val tracker = PlayerSurfaceGenerationTracker(allocator::next)

        val initial = tracker.resolve(owner("song-a", mediaRevision = "media-1"), 10L)
        val revisedOwner = tracker.resolve(owner("song-a", mediaRevision = "media-2"), null)
        val revisedLease = tracker.resolve(owner("song-a", mediaRevision = "media-2"), 11L)

        assertNotEquals(initial, revisedOwner)
        assertEquals(revisedOwner, revisedLease)
        assertEquals(2, allocator.allocationCount)
    }

    private fun owner(
        songId: String,
        mediaRevision: String = "media"
    ) = SongPresentationOwner(
        songId = songId,
        mediaRevision = mediaRevision,
        audioRevision = "audio-$songId",
        stemRevision = "stems-$songId",
        sourceHash = "hash-$songId"
    )

    private class SequentialGenerationAllocator {
        var allocationCount = 0
            private set

        fun next(): Long {
            allocationCount++
            return allocationCount.toLong()
        }
    }
}
