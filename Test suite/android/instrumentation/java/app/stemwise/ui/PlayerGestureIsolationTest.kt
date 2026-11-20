package app.stemwise.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Production gesture-policy coverage retained in the centralized AppAction inventory. */
@RunWith(AndroidJUnit4::class)
class PlayerGestureIsolationTest {
    @Test
    fun programmaticTransportSnapsVisualOwnershipWithoutPagerPhysics() = runBlocking {
        val owner = PlayerVisualOwnerState("song-a")
        owner.updateOrder(listOf("song-a", "song-b"))

        owner.snapToSong("song-b")

        assertEquals("song-b", owner.settledSongId)
    }

}
