package app.stemwise.ui

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.PlaybackQueueController
import app.stemwise.playback.PlaybackSourceMode
import app.stemwise.playback.RepeatMode
import app.stemwise.playback.engine.NativeAudioPlaybackSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class PlayerSheetMotionSourcePolicyTest {
    @Test
    fun `settle rejects velocity outside either anchor while retaining in-range velocity and reduced motion`() {
        val spring = spring<Float>(dampingRatio = .82f, stiffness = 420f)
        val bounded = boundedPlayerSheetSettleSpec(spring, { 0f }, { 500f }).vectorize(Float.VectorConverter)
        val reference = spring.vectorize(Float.VectorConverter)
        fun vector(value: Float) = Float.VectorConverter.convertToVector(value)
        for ((anchor, velocity) in listOf(0f to -10_000f, 500f to 10_000f)) {
            for (timeMs in 0L..640L step 16L) {
                assertEquals(anchor, bounded.getValueFromNanos(timeMs * 1_000_000L,
                    vector(anchor), vector(anchor), vector(velocity)).value, 0f)
            }
        }
        for (target in listOf(0f, 500f)) {
            for (velocity in listOf(-10_000f, 10_000f)) {
                for (timeMs in 0L..640L step 16L) {
                    val position = bounded.getValueFromNanos(timeMs * 1_000_000L,
                        vector(250f), vector(target), vector(velocity)).value
                    assertTrue("Settling escaped its actual anchors: $position", position in 0f..500f)
                }
            }
        }
        val initial = vector(250f)
        val target = vector(0f)
        val velocity = vector(-200f)
        val frame = 16_000_000L
        assertEquals(reference.getValueFromNanos(frame, initial, target, velocity).value,
            bounded.getValueFromNanos(frame, initial, target, velocity).value, 0f)
        assertEquals(reference.getVelocityFromNanos(frame, initial, target, velocity).value,
            bounded.getVelocityFromNanos(frame, initial, target, velocity).value, 0f)
        assertEquals(reference.getDurationNanos(initial, target, velocity),
            bounded.getDurationNanos(initial, target, velocity))
        val reduced = boundedPlayerSheetSettleSpec(snap(), { 0f }, { 500f }).vectorize(Float.VectorConverter)
        assertEquals(0f, reduced.getValueFromNanos(0L, initial, target, velocity).value, 0f)
    }

    @Test
    fun `library reserves the whole compact player immediately when playback starts`() {
        listOf(false, true).forEach { hasActiveSession ->
            val shell = playerShellLayout(hasActiveSession)
            listOf(480f, 772f, 960f).forEach { viewportHeight ->
                val libraryBottom = viewportHeight - shell.pageBottomReserveDp
                val overlayTop = viewportHeight - shell.sheetPeekHeightDp
                assertTrue("Library rows must remain above the bottom overlay",
                    libraryBottom <= overlayTop)
            }
        }
    }

    @Test
    fun `fresh process authorities start without song or playback state`() {
        val playback = AudioPlaybackState()
        assertNull(playback.songId)
        assertFalse(playback.loading)
        assertFalse(playback.playing)
        assertFalse(playback.playWhenReady)
        assertEquals(0L, playback.positionMs)
        assertEquals(0L, playback.durationMs)
        assertFalse(playback.ended)
        assertEquals(PlaybackSourceMode.ORIGINAL, playback.sourceMode)
        assertNull(playback.loopA)
        assertNull(playback.loopB)
        assertTrue(playback.stemMix.isEmpty())
        assertEquals("Off", playback.repeatMode)
        assertFalse(playback.shuffleEnabled)
        assertTrue(playback.queueOrder.isEmpty())
        assertNull(playback.currentQueueSongId)

        val queue = PlaybackQueueController(seed = 1L).snapshot()
        assertTrue(queue.order.isEmpty())
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.currentId)
        assertFalse(queue.shuffleEnabled)
        assertEquals(RepeatMode.OFF, queue.repeatMode)

        val session = NativeAudioPlaybackSessionState()
        assertNull(session.publication.committed)
        assertNull(session.latestRequest)
        assertFalse(session.loopAvailable)
        assertNull(session.likelyNextIdentity)
        assertNull(session.automaticEndPlan)
    }

}
