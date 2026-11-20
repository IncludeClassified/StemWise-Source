package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.*
import app.stemwise.bridge.BridgeUiState
import app.stemwise.integration.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SystemStatusPresentationInstrumentationTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test fun disconnectThenReconnectStartsMaskedUntilTokenIsExplicitlyRevealedAgain() {
        fun assertRenderedToken(expected: String) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                assertTrue(it(layouts))
            }
            org.junit.Assert.assertEquals(expected, layouts.single().layoutInput.text.text)
        }
        val connection = mutableStateOf(MvsepConnectionState(MvsepConnectionStatus.READY, tokenStored = true))
        composeRule.setContent { StemwiseTheme {
            SettingsScreen(playlistSettings = PlaylistSettings(checkNotNull(PlaylistLinks.parse(
                "https://music.youtube.com/playlist?list=PL1234567890")), "Fixture", null, null, true),
                mvsepState = connection.value, bridgeState = BridgeUiState(),
                onSaveBridgeConnection = { Result.success(Unit) }, onEnrollBridge = { Result.success(null) },
                onRefreshBridge = { Result.success(Unit) }, onWakeBridgePc = { Result.success(Unit) },
                libraryRootState = LibraryRootState(), storageRefreshState = LibraryStorageRefreshState(),
                onChooseLibrary = {}, onRefreshStorage = {}, onUpdatePlaylistTools = { Result.success(Unit) },
                onSaveMvsepToken = { Result.success(Unit) }, onRetryMvsep = { Result.success(Unit) },
                onRemoveMvsepToken = {
                    connection.value = MvsepConnectionState(MvsepConnectionStatus.NOT_CONFIGURED)
                    Result.success(Unit)
                })
        } }
        composeRule.onNodeWithText("Edit MVSEP connection").performScrollTo().performClick()
        composeRule.onNodeWithText("Show token").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("old-visible-fixture-token")
        composeRule.onNodeWithText("Disconnect", substring = false).performScrollTo().performClick()
        composeRule.onAllNodes(hasText("Disconnect") and hasClickAction()).onLast().performClick()
        composeRule.onNodeWithText("Connect MVSEP", substring = false).performScrollTo().performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.runOnUiThread {
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("fixture", "replacement-fixture-token"))
        }
        composeRule.onNodeWithText("Paste", substring = false).performClick()
        composeRule.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertRenderedToken("\u2022".repeat("replacement-fixture-token".length))
        composeRule.onNodeWithText("Show token").assertExists().performClick()
        composeRule.onNodeWithText("Hide token").assertExists()
        assertRenderedToken("replacement-fixture-token")
    }

    @Test
    fun connectionBadgesKeepFullMinimumGeometryWithoutTouchingTheLeadingIcon() {
        val states = listOf("Not connected", "Checking", "Retry")
        composeRule.setContent {
            val physicalDensity = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(physicalDensity, fontScale = 1.29f)
            ) {
                StemwiseTheme {
                    // Exercise the badges independently at a compact width and enlarged font.
                    // Connection cards themselves now always use the full content width.
                    Column(Modifier.width(152.dp)) {
                        states.forEach { state ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .testTag("leading-$state")
                                        .size(28.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.testTag("badge-$state")) {
                                    SystemConnectionStatusBadge(
                                        state = state,
                                        canonicalScale = true,
                                        statusVisual = systemConnectionStatusVisual(kindFor(state))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        states.forEach { state ->
            val badge = composeRule.onNodeWithTag("badge-$state", useUnmergedTree = true)
            badge.assertWidthIsAtLeast(systemConnectionStatusBadgeMinimumWidthDp(kindFor(state)).dp)
            val iconBounds = composeRule.onNodeWithTag("leading-$state")
                .fetchSemanticsNode().boundsInRoot
            val badgeBounds = badge.fetchSemanticsNode().boundsInRoot
            assertTrue("$state badge overlapped its icon", iconBounds.right <= badgeBounds.left)
        }
    }

    @Test
    fun readyBadgeRetainsTheExactDesignerGeometryAtCanonicalScale() {
        composeRule.setContent {
            StemwiseTheme {
                Box(Modifier.testTag("badge-ready")) {
                    SystemConnectionStatusBadge(
                        state = "Ready",
                        canonicalScale = true,
                        statusVisual = systemConnectionStatusVisual(SystemConnectionStatusVisualKind.READY)
                    )
                }
            }
        }

        composeRule.onNodeWithTag("badge-ready", useUnmergedTree = true)
            .assertWidthIsEqualTo(SYSTEM_CONNECTION_READY_PILL_WIDTH_DP.dp)
            .assertHeightIsEqualTo(SYSTEM_CONNECTION_READY_PILL_HEIGHT_DP.dp)
    }

    @Test
    fun storageTimestampIsVisibleAndPublishesItsRefreshStateForAccessibility() {
        val presentation = StorageLastUpdatedPresentation(
            visibleText = "Last updated: Aug 16, 2026, 3:42 PM",
            accessibilityText =
                "Storage totals last updated Aug 16, 2026, 3:42 PM; refresh in progress"
        )
        composeRule.setContent {
            StemwiseTheme {
                StorageLastUpdatedLabel(presentation, announceChanges = true)
            }
        }

        composeRule.onNodeWithText(presentation.visibleText, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription(presentation.accessibilityText).assertExists()
    }
    private fun kindFor(label: String): SystemConnectionStatusVisualKind = when (label) {
        "Ready" -> SystemConnectionStatusVisualKind.READY
        "Retry" -> SystemConnectionStatusVisualKind.ERROR
        "Checking", "Waiting", "Verify" -> SystemConnectionStatusVisualKind.PENDING
        else -> SystemConnectionStatusVisualKind.NEUTRAL
    }

}
