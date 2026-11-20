package app.stemwise.playback

import app.stemwise.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlaybackInstrumentationRecorderTest {
    @Test
    fun optInReachesExistingChildrenWithoutCombiningTheirLifetimeCounters() {
        val recorder = PlaybackInstrumentationRecorder()
        val first = recorder.child()
        first.increment("snapshots")
        recorder.enable()
        first.increment("snapshots")
        val replacement = recorder.child()
        replacement.increment("snapshots")
        replacement.increment("snapshots")
        val development = BuildConfig.BUILD_TYPE == "development"
        assertEquals(if (development) 1L else 0L, first.count("snapshots"))
        assertEquals(if (development) 2L else 0L, replacement.count("snapshots"))
        assertEquals(0L, recorder.count("snapshots"))
    }

    @Test
    fun accountingAndRawOwnerRetentionRequireExplicitOptIn() {
        val recorder = PlaybackInstrumentationRecorder()
        val owner = object {
            override fun toString(): String = error("Owner labels must only format on demand")
        }
        repeat(10_000) {
            recorder.increment("positions")
            recorder.remember("owner", owner)
        }
        assertFalse(recorder.enabled)
        assertEquals(0L, recorder.count("positions"))
        assertNull(recorder.observation("owner"))
        recorder.enable()
        recorder.increment("positions")
        recorder.remember("owner", owner)
        recorder.enable()
        if (BuildConfig.BUILD_TYPE == "development") {
            assertTrue(recorder.enabled)
            assertEquals(1L, recorder.count("positions"))
            assertSame(owner, recorder.observation("owner"))
        } else {
            assertFalse(recorder.enabled)
            assertEquals(0L, recorder.count("positions"))
            assertNull(recorder.observation("owner"))
        }
    }
}
