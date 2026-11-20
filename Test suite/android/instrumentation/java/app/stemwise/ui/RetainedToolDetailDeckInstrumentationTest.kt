package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.stemwise.ui.player.surface.PlayerSurfaceHostBridge
import app.stemwise.ui.player.surface.PlayerSurfaceTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class RetainedToolDetailDeckInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun loopTuneLoopKeepsTheSameCompositionAndOnlyTheVisibleWorkOwner() {
        var selected by mutableStateOf(PLAYER_DETAIL_LOOP)
        val identities = mutableMapOf<String, Any>()
        val active = mutableSetOf<String>()
        val disposed = mutableListOf<String>()
        val bridge = PlayerSurfaceHostBridge()
        compose.setContent {
            PortraitRetainedToolDetailDeck(1L, selected, null,
                if (selected == PLAYER_DETAIL_LOOP) PlayerSurfaceTool.LOOP else PlayerSurfaceTool.TUNE,
                300, 300, true, 1L, bridge, Modifier.fillMaxSize()) { tool, visible ->
                val identity = remember { Any() }
                SideEffect { identities[tool] = identity }
                DisposableEffect(Unit) { onDispose { disposed += tool } }
                DisposableEffect(visible) {
                    if (visible) active += tool
                    onDispose { active -= tool }
                }
                Box(Modifier.fillMaxSize())
            }
        }
        lateinit var original: Any
        compose.runOnIdle {
            original = identities.getValue(PLAYER_DETAIL_LOOP)
            assertEquals(setOf(PLAYER_DETAIL_LOOP), active)
            selected = PLAYER_DETAIL_TUNE
        }
        compose.runOnIdle {
            assertEquals(setOf(PLAYER_DETAIL_TUNE), active)
            assertSame(original, identities.getValue(PLAYER_DETAIL_LOOP))
            selected = PLAYER_DETAIL_LOOP
        }
        compose.runOnIdle {
            assertSame(original, identities.getValue(PLAYER_DETAIL_LOOP))
            assertEquals(setOf(PLAYER_DETAIL_LOOP), active)
            assertEquals(emptyList<String>(), disposed)
        }
    }
}
