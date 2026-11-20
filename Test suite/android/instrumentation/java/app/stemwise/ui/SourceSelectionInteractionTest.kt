package app.stemwise.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SourceSelectionInteractionTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun unavailableSelectedPartsRequireExplicitRemovalBeforePreparing() {
        val available = mutableStateOf(StemSourceCatalogue.sources)
        var submitted = emptyList<String>()
        compose.setContent { StemwiseTheme {
            SourceSelectionDialog("song", available.value, false, null, {}, {
                submitted = it; true
            }, {}, initialSelection = listOf("vocals", "piano"))
        } }
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.runOnIdle { available.value = available.value.filterNot { it.id == "piano" } }
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.onNodeWithText("Prepare parts").assertIsNotEnabled()
        compose.onNodeWithTag("source_selection_remove_piano").performClick()
        compose.onNodeWithText("1 of 8 selected").assertIsDisplayed()
        compose.onNodeWithText("Prepare parts").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("vocals"), submitted) }
    }

    @Test fun lateInitialChoicesLoadOnceAndRetryKeepsTheEditedDraft() {
        val loading = mutableStateOf(true)
        val initial = mutableStateOf(emptyList<String>())
        val error = mutableStateOf<String?>(null)
        var submitted = emptyList<String>()
        compose.setContent { StemwiseTheme {
            SourceSelectionDialog("song", StemSourceCatalogue.sources, loading.value, error.value, {}, {
                submitted = it; true
            }, {}, initialSelection = initial.value)
        } }
        compose.runOnIdle { initial.value = listOf("piano"); loading.value = false }
        compose.onNodeWithText("1 of 8 selected").assertIsDisplayed()
        compose.onNodeWithTag("source_selection_search").performTextInput("All vocals")
        compose.onNodeWithTag("source_selection_vocals").performClick()
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.runOnIdle { loading.value = true; initial.value = listOf("bass") }
        compose.runOnIdle { loading.value = false; error.value = "Temporary catalogue failure" }
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.onNodeWithText("Prepare parts").assertIsNotEnabled()
        compose.runOnIdle { error.value = null }
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.onNodeWithText("Prepare parts").performClick()
        compose.runOnIdle { assertEquals(listOf("piano", "vocals"), submitted) }
    }

    @Test fun largeTextAndLongTitleKeepPrepareVisibleAtTheFinalPart() {
        val title = "A long imported song title ".repeat(100)
        var requests = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                StemwiseTheme {
                    SourceSelectionDialog("song", StemSourceCatalogue.sources, false, null, {}, {
                        requests++; true
                    }, {}, songTitle = title)
                }
            }
        }
        compose.onNodeWithText("Prepare parts").assertIsDisplayed()
        compose.onNodeWithTag("source_selection_list").performScrollToKey("crowd")
        compose.onNodeWithTag("source_selection_crowd").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
        val prepare = compose.onNodeWithText("Prepare parts").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot
        assertEquals(prepare.left, back.left, 1f)
        assertEquals(prepare.right, back.right, 1f)
        assertTrue("Picker actions must use one vertical column", back.top >= prepare.bottom)
        compose.onNodeWithText("Prepare parts").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun searchKeepsHiddenChoicesAndPrepareCommitsOnlyOnce() {
        var requests = 0
        var selection = emptyList<String>()
        compose.setContent { StemwiseTheme {
            SourceSelectionDialog("song", StemSourceCatalogue.sources, false, null, {}, {
                requests++; selection = it; true
            }, {}, initialSelection = emptyList(), songTitle = "Target song")
        } }
        compose.onNodeWithText("Target song").assertIsDisplayed()
        compose.onNodeWithTag("source_selection_search").performTextInput("All vocals")
        compose.onNodeWithTag("source_selection_vocals").performClick()
        compose.onNodeWithTag("source_selection_search").performTextClearance()
        compose.onNodeWithTag("source_selection_search").performTextInput("Piano")
        compose.onNodeWithTag("source_selection_piano").performClick()
        compose.onNodeWithText("2 of 8 selected").assertIsDisplayed()
        compose.onNodeWithText("Prepare parts").performClick()
        compose.onNodeWithText("Prepare parts").performClick()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(listOf("vocals", "piano"), selection) }
    }

    @Test fun backNeverStartsPreparation() {
        var requests = 0
        var dismissals = 0
        compose.setContent { StemwiseTheme {
            SourceSelectionDialog("song", StemSourceCatalogue.sources, false, null, {}, {
                requests++; true
            }, { dismissals++ })
        } }
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(0, requests); assertEquals(1, dismissals) }
    }
}
