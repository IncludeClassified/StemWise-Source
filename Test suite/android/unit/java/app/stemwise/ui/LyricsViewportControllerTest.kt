package app.stemwise.ui

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsViewportControllerTest {
    @Test
    fun boundaryKeyboardAndWheelIntentsHaveFiniteSessionsWithoutInventedMotion() = runBlocking {
        val owner = Job(coroutineContext[Job])
        val controller = LyricsViewportController(LazyListState(), CoroutineScope(coroutineContext + owner))
        controller.setDebugOverlayAttached(true)
        try {
            for (source in listOf(LyricsUserInputSource.DPad, LyricsUserInputSource.Keyboard,
                    LyricsUserInputSource.MouseWheel)) {
                controller.onUserScrollIntent(source)
                assertTrue(controller.debugState.value.directDragActive)
                controller.onDiscreteInputEnded()
                assertFalse(controller.debugState.value.directDragActive)
                assertTrue(controller.debugState.value.currentGesture?.totalTravelPx == null ||
                    controller.debugState.value.currentGesture?.totalTravelPx == 0f)
            }
            controller.onUserScrollIntent(LyricsUserInputSource.MouseWheel)
            withTimeout(2_000L) {
                while (controller.debugState.value.directDragActive) delay(10L)
            }
            assertFalse(controller.debugState.value.directDragActive)
        } finally { owner.cancelAndJoin() }
    }
}
