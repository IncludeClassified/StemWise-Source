package app.stemwise.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class LoopCommitActivationPolicyTest {
    @Test
    fun editingCompleteRangeAroundOutsidePlayheadParksUntilRealRangeEntry() {
        val edited = LoopRange(2_000L, 5_000L)

        assertTrue(editedLoopShouldParkUntilRangeEntry(edited, 1_999L))
        assertFalse(editedLoopShouldParkUntilRangeEntry(edited, 5_000L))
        assertTrue(editedLoopShouldParkUntilRangeEntry(edited, 5_001L))
        assertFalse(editedLoopShouldParkUntilRangeEntry(edited, 2_000L))
        assertFalse(editedLoopShouldParkUntilRangeEntry(edited, 4_999L))
    }

    @Test
    fun completingIncompletePointAAtBActivatesImmediately() {
        val completed = LoopRange(2_000L, 5_000L)

        assertFalse(
            editedLoopShouldParkUntilRangeEntry(
                nextRange = completed,
                transportPositionMs = completed.endMs
            )
        )
    }

    @Test
    fun directCompleteRangeOutsidePlayheadParksEvenWithoutPreviousRange() {
        val completed = LoopRange(2_000L, 5_000L)

        assertTrue(
            editedLoopShouldParkUntilRangeEntry(
                nextRange = completed,
                transportPositionMs = 7_500L
            )
        )
    }

    @Test
    fun clearingOrReturningToIncompleteDraftNeverPublishesDormantPolicy() {
        assertFalse(editedLoopShouldParkUntilRangeEntry(null, 9_000L))
    }

}
