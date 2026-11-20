package app.stemwise.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackHandoffTest {
    @Test
    fun stalePriorSongPositionResetsToTheActiveSongOrigin() {
        assertEquals(0L, playableHandoffPosition(242_147L, durationMs = 1_200L))
        assertEquals(1_199L, playableHandoffPosition(1_199L, durationMs = 1_200L))
        assertEquals(0L, playableHandoffPosition(-50L, durationMs = 1_200L))
    }

    @Test
    fun stemControlsUpdateOnlyTheRequestedLaneState() {
        val original = linkedMapOf(
            "vocals" to StemMixState(volume = .82f),
            "drums" to StemMixState(volume = .75f)
        )
        val muted = updatedStemMixState(original, "vocals", .82f, muted = true, solo = false)

        assertTrue(muted !== original)
        assertTrue(requireNotNull(muted["vocals"]).muted)
        assertEquals(.75f, requireNotNull(muted["drums"]).volume, .0001f)
        assertSame(original, updatedStemMixState(original, "missing", 0f, true, false))
    }

}
