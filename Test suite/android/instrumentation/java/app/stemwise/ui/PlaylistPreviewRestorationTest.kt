package app.stemwise.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaylistPreviewRestorationTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test
    fun suspendedPreviewRestartsAfterSavedStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val result = Channel<Unit>(Channel.UNLIMITED)
        restoration.setContent {
            var busyOfferId by rememberPlaylistPreviewBusyOfferId()
            var candidateReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (busyOfferId == 7L) return@LaunchedEffect
                busyOfferId = 7L
                started.incrementAndGet()
                try {
                    result.receive()
                    candidateReady = true
                } finally {
                    busyOfferId = null
                    if (!candidateReady) cancelled.incrementAndGet()
                }
            }
            Text(if (candidateReady) "Ready to confirm" else "Checking playlist details")
        }
        try {
            composeRule.waitUntil { started.get() == 1 }
            restoration.emulateSavedInstanceStateRestore()
            composeRule.waitUntil { started.get() == 2 }
            composeRule.runOnIdle {
                assertEquals(1, cancelled.get())
                check(result.trySend(Unit).isSuccess)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Ready to confirm").assertExists()
        } finally {
            result.cancel()
        }
    }
}
