package app.stemwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseAcceptedIconButton
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.player.nativeplayer.FullPlayerStatusKind
import app.stemwise.ui.player.nativeplayer.FullPlayerStatusPacket
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerVisualPolicyInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun resolvedThemeKeepsOledSystemBarsDarkRegardlessOfXmlSpelling() {
        val application = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val themed = android.view.ContextThemeWrapper(application, app.stemwise.R.style.Theme_Stemwise)
        val values = themed.obtainStyledAttributes(intArrayOf(android.R.attr.statusBarColor,
            android.R.attr.navigationBarColor, android.R.attr.windowLightStatusBar,
            android.R.attr.windowLightNavigationBar))
        try {
            assertEquals(android.graphics.Color.BLACK, values.getColor(0, android.graphics.Color.MAGENTA))
            assertEquals(android.graphics.Color.BLACK, values.getColor(1, android.graphics.Color.MAGENTA))
            assertFalse(values.getBoolean(2, true))
            assertFalse(values.getBoolean(3, true))
        } finally { values.recycle() }
    }

    @Test fun composeAuraDetachAndReplacementPreserveRenderOwnedBitmaps() {
        val visible = mutableStateOf(true)
        val textures = java.util.concurrent.CopyOnWriteArrayList<android.graphics.Bitmap>()
        ArtworkAuraTextureGenerationDiagnostics.textureObserverForTest = { textures.add(it); Unit }
        try {
            compose.setContent {
                StemwiseTheme {
                    if (visible.value) ArtworkAuraLayer(MissingArtworkAuraPalette,
                        compact = true, phaseOrdinal = 0, cornerRadius = 8.dp,
                        ambientMotionEnabled = false, modifier = Modifier.size(64.dp).testTag("retained-aura"))
                }
            }
            compose.waitUntil(5_000L) { textures.isNotEmpty() }
            compose.onNodeWithTag("retained-aura").captureToImage()
            val retained = textures.toList()
            compose.runOnIdle { visible.value = false }
            compose.runOnIdle { retained.forEach { assertFalse("Detached aura recycled render-owned texture", it.isRecycled) } }
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(5_000L) { textures.size > retained.size }
            compose.onNodeWithTag("retained-aura").captureToImage()
            retained.forEach { assertFalse("Replacement recycled a retained display-list bitmap", it.isRecycled) }
        } finally { ArtworkAuraTextureGenerationDiagnostics.textureObserverForTest = null }
    }

    @Test fun playbackStatusHasOneProgressOwnerAndErrorHasOneAssertiveAnnouncement() {
        val error = mutableStateOf(false)
        compose.setContent {
            StemwiseTheme {
                if (error.value) PlaybackErrorCard(FullPlayerStatusPacket(2, FullPlayerStatusKind.ERROR,
                    "Playback stopped", "The current source is unavailable"))
                else PlaybackStatusCard(FullPlayerStatusPacket(1, FullPlayerStatusKind.PROGRESS,
                    "Preparing playback", "Reading imported audio", .42f))
            }
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(.42f, 0f..1f)), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("42%", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion),
            useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { error.value = true }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,
            LiveRegionMode.Assertive), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Playback stopped").assertIsDisplayed()
        compose.onNodeWithText("The current source is unavailable").assertIsDisplayed()
    }

    @Test fun songDetailsKeepsSafeFocusPhysicalActionsAndResetsCopyForTheNewIdentity() {
        val song = mutableStateOf(app.stemwise.model.Song(id = "details-owner", videoId = "Details0001",
            title = "Song detail owner", artist = "Fixture artist", durationMs = 60_000L,
            state = app.stemwise.model.SongState.READY, accent = Color.Cyan, hasStems = true, artworkGlyph = "D"))
        val large = mutableStateOf(false)
        val opened = mutableStateOf(false)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,
                    if (large.value) 2f else 1f)) {
                StemwiseTheme {
                    app.stemwise.ui.design.StemwiseButton(onClick = { opened.value = true },
                        modifier = Modifier.testTag("open-song-details")) { Text("Show song details") }
                    if (opened.value) SongInfoDialog(song.value, onDismiss = { opened.value = false },
                        onCopyVideoId = {}, onDeleteSong = {})
                }
            }
        }
        // Material buttons admit input focus in keyboard mode. Open the real dialog through
        // actual navigation/activation keys; a touch-mode request is not a safe-focus test.
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(5_000L) { compose.runOnIdle { compose.activity.hasWindowFocus() } }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val opener = compose.onNodeWithTag("open-song-details")
        compose.waitUntil(5_000L) {
            opener.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Song details"),
            useUnmergedTree = true).assertIsDisplayed()
        val closeAction = compose.onNodeWithText("Close")
        compose.waitUntil(5_000L) {
            closeAction.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        closeAction.assertIsFocused()
        val close = compose.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot
        val stems = compose.onNodeWithText("Delete stems only").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText("Delete song with everything").fetchSemanticsNode().boundsInRoot
        assertEquals("Dialog actions must share one full-width column", close.left, stems.left, 1f)
        assertEquals("Dialog actions must share one full-width column", close.right, stems.right, 1f)
        assertTrue("Dialog actions must stack vertically", stems.top >= close.bottom && delete.top >= stems.bottom)
        val copy = compose.onNodeWithContentDescription("Copy YouTube video ID")
        val density = compose.activity.resources.displayMetrics.density
        // Dialog-open motion scales the actual bounds. Observe its geometry endpoint before
        // comparing the accepted physical size; do not assert an arbitrary animation sample.
        compose.waitUntil(5_000L) {
            val current = copy.fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(current.width - 60f * density) <= 1f &&
                kotlin.math.abs(current.height - 48f * density) <= 1f
        }
        val bounds = copy.fetchSemanticsNode().boundsInRoot
        assertEquals(60f * density, bounds.width, 1f)
        assertEquals(48f * density, bounds.height, 1f)
        copy.performClick()
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copied"))
        compose.runOnIdle { song.value = song.value.copy(videoId = "Details0002"); large.value = true }
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ready to copy"))
        compose.waitUntil(5_000L) {
            val currentClose = compose.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot
            val currentStems = compose.onNodeWithText("Delete stems only").fetchSemanticsNode().boundsInRoot
            val currentDelete = compose.onNodeWithText("Delete song with everything").fetchSemanticsNode().boundsInRoot
            currentStems.top >= currentClose.bottom && currentDelete.top >= currentStems.bottom
        }
        val largeClose = compose.onNodeWithText("Close").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val largeStems = compose.onNodeWithText("Delete stems only").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val largeDelete = compose.onNodeWithText("Delete song with everything").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Large text did not reflow actions", largeStems.top >= largeClose.bottom && largeDelete.top >= largeStems.bottom)
        compose.onAllNodesWithText("Library folder").assertCountEquals(0)
        compose.onAllNodesWithText("Audio").assertCountEquals(0)
    }

    @Test fun acceptedComposeFeedbackOwnsOneFullBodyAndSurvivesLaterDisablement() {
        val enabled = mutableStateOf(true)
        var accept = false
        var calls = 0
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                StemwiseTheme {
                    CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(true, false)) {
                        Column(Modifier.background(Color.Black)) {
                            StemwiseAcceptedIconButton(onClick = { calls++; if (accept) enabled.value = false; accept },
                                enabled = enabled.value, selected = true, modifier = Modifier.size(64.dp).testTag("accepted-owner")) { Text("A") }
                            StemwiseAcceptedIconButton(onClick = { false }, enabled = enabled.value, selected = true,
                                modifier = Modifier.size(64.dp).testTag("disabled-reference")) { Text("A") }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(500L)
            fun pixels(tag: String): IntArray {
                val image = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                return IntArray(image.width * image.height) { index -> image[index % image.width,
                    index / image.width].let { color -> android.graphics.Color.argb(
                        (color.alpha * 255).toInt(), (color.red * 255).toInt(),
                        (color.green * 255).toInt(), (color.blue * 255).toInt()) } }
            }
            val owner = compose.onNodeWithTag("accepted-owner")
            val baseline = pixels("accepted-owner")
            owner.performTouchInput { down(center) }
            compose.mainClock.advanceTimeByFrame()
            assertArrayEquals("Raw DOWN started ordinary button feedback", baseline, pixels("accepted-owner"))
            owner.performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(1, calls)
            assertArrayEquals("Rejected callback painted feedback", baseline, pixels("accepted-owner"))
            compose.runOnIdle { accept = true }
            owner.performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            assertEquals(2, calls)
            val flash = pixels("accepted-owner")
            val reference = pixels("disabled-reference")
            val edge = kotlin.math.sqrt(flash.size.toDouble()).toInt()
            val changed = flash.indices.filter { flash[it] != reference[it] }
            assertTrue("Accepted feedback vanished on disable", changed.isNotEmpty())
            fun luminance(pixel: Int) = android.graphics.Color.red(pixel) +
                android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)
            assertTrue("Accepted response dimmed persistent selected glyph pixels", flash.indices.all {
                luminance(flash[it]) + 3 >= luminance(reference[it]) })
            assertTrue("Feedback only covered the child glyph", changed.minOf { it % edge } < edge / 4 &&
                changed.maxOf { it % edge } > edge * 3 / 4)
            compose.mainClock.advanceTimeBy(300L)
            assertArrayEquals("A second feedback lifecycle survived the brief accepted response",
                pixels("disabled-reference"), pixels("accepted-owner"))
            assertEquals(2, calls)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
