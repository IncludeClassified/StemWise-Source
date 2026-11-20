package app.stemwise.ui

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import app.stemwise.model.Song
import app.stemwise.model.SongState
import androidx.compose.ui.graphics.Color
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.design.StemwiseTextButton
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stemwise.ui.player.surface.*
import app.stemwise.ui.theme.StemwiseTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerPersistentTransportInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun lyricsModalWithdrawsBothTransportAndNativeInputUntilItsExitCompletes() {
        val visible = mutableStateOf(false)
        val position = mutableStateOf(30_000L)
        val transport = MutableStateFlow(PlayerTransportUiState(previousEnabled = true, canGoNext = true))
        val commands = mutableListOf<PlayerSurfaceTransportCommand>()
        var nativeTaps = 0
        var underlayMounts = 0
        var underlayDisposals = 0
        var modalPresent = false
        val sink = object : FullPlayerActionSink by NativePlayerSurfaceFixture.geometryActionSink() {
            override fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean {
                commands += command
                return true
            }
        }
        val song = Song("modal-lyrics", "modal-lyrics", "No lyrics fixture", "StemWise", 120_000L,
            SongState.READY, Color.Cyan, artworkGlyph = "L")
        compose.setContent {
            StemwiseTheme {
                CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(true, false)) {
                    PlayerLyricsModalHost(visible.value, { visible.value = false }, song, 7L, position,
                        onPresenceChanged = { modalPresent = it }, modifier = Modifier.testTag("lyrics-modal-host"),
                        transport = { PlayerOverlayTransport(7L, 120_000L, position, transport, sink) },
                        onSeek = { _, _ -> true }) {
                        DisposableEffect(Unit) {
                            underlayMounts++
                            onDispose { underlayDisposals++ }
                        }
                        AndroidView(factory = { context -> View(context).apply {
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            setOnTouchListener { _, event ->
                                if (event.actionMasked == MotionEvent.ACTION_UP) nativeTaps++
                                true
                            }
                        } }, modifier = Modifier.fillMaxSize())
                        PlayerTransportViewport(transport = {
                            PlayerOverlayTransport(7L, 120_000L, position, transport, sink)
                        }) { bodyModifier ->
                            Box(bodyModifier) { StemwiseTextButton(onClick = {}) { Text("Original") } }
                        }
                    }
                }
            }
        }
        compose.onAllNodesWithContentDescription("Play").assertCountEquals(1)
        compose.onNodeWithTag("lyrics-modal-host").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, nativeTaps) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible.value = true }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.onAllNodesWithContentDescription("Play").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Playback position").assertCountEquals(1)
            compose.onNodeWithText("Original").assertDoesNotExist()
            compose.onNodeWithTag("expanded_lyrics_sheet").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("Play").performClick()
            compose.runOnIdle {
                assertTrue(modalPresent)
                assertEquals(1, nativeTaps)
                assertEquals(listOf(PlayerSurfaceTransportCommand.PLAY_PAUSE), commands)
            }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(80L)
            compose.onNodeWithText("Original").assertDoesNotExist()
            compose.onNodeWithTag("lyrics-modal-host").performTouchInput { click(center) }
            compose.runOnIdle { assertTrue(modalPresent); assertEquals(1, nativeTaps) }
            compose.mainClock.advanceTimeBy(1_000L)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText("Original").assertExists()
        compose.onAllNodesWithContentDescription("Play").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Playback position").assertCountEquals(1)
        compose.onNodeWithTag("lyrics-modal-host").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, nativeTaps)
            assertEquals(false, modalPresent)
            assertEquals(1, underlayMounts)
            assertEquals(0, underlayDisposals)
        }
    }

    @Test fun shortLargeTextViewportKeepsOneTransportWhileTheBodyScrolls() {
        val commands = mutableListOf<PlayerSurfaceTransportCommand>()
        val seeks = mutableListOf<Long>()
        val sink = object : FullPlayerActionSink by NativePlayerSurfaceFixture.geometryActionSink() {
            override fun onTransport(generation: Long, command: PlayerSurfaceTransportCommand): Boolean {
                commands += command
                return true
            }
            override fun onScrubBegin(generation: Long) = true
            override fun onScrubPreview(generation: Long, positionMs: Long) = true
            override fun onScrubCommit(generation: Long, positionMs: Long): Boolean {
                seeks += positionMs
                return true
            }
        }
        val transport = MutableStateFlow(PlayerTransportUiState(previousEnabled = true, canGoNext = true))
        val position = mutableStateOf(30_000L)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                StemwiseTheme {
                    Box(Modifier.size(360.dp, 320.dp)) {
                        PlayerTransportViewport(transport = {
                            PlayerOverlayTransport(7L, 120_000L, position, transport, sink)
                        }) { bodyModifier ->
                            Column(bodyModifier.verticalScroll(rememberScrollState()).testTag("transport-body")) {
                                Text("First control")
                                Box(Modifier.height(900.dp))
                                Text("Final control")
                            }
                        }
                    }
                }
            }
        }
        val play = compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        val before = play.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("transport-body").performTouchInput { swipeUp() }
        assertEquals(before, play.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed().performClick()
        play.performClick()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Playback position").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(60_000f)) }
        compose.runOnIdle {
            assertEquals(listOf(PlayerSurfaceTransportCommand.PREVIOUS,
                PlayerSurfaceTransportCommand.PLAY_PAUSE, PlayerSurfaceTransportCommand.NEXT), commands)
            assertEquals(listOf(60_000L), seeks)
        }
    }
}
