package app.stemwise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class QualificationEvidenceTest {
    @get:Rule
    val composeRule = createStemwiseComposeRule()

    @Test
    fun unknownKeyKeepsThePitchValueWithoutASecondaryAnalysisLabel() {
        var receivedInvalidValue = false
        composeRule.setContent {
            StemwiseTheme {
                Box(Modifier.width(360.dp)) {
                    KeyPanel(
                        song = Song(
                            id = "unknown-key",
                            videoId = "UnknownKey1",
                            title = "Unknown key fixture",
                            artist = "Fixture artist",
                            durationMs = 60_000L,
                            state = SongState.READY,
                            accent = Color.Cyan,
                            artworkGlyph = "UK",
                            key = null
                        ),
                        pitch = 0,
                        onPitch = { receivedInvalidValue = true; true }
                    )
                }
            }
        }

        composeRule.onNodeWithText("0 st").assertExists()
        composeRule.onNodeWithText("Not analyzed").assertDoesNotExist()
        composeRule.onNodeWithText("Original key not analyzed").assertDoesNotExist()
        listOf("Key adjustment").forEach { description ->
            val bounds = composeRule.onNodeWithContentDescription(description)
                .getUnclippedBoundsInRoot()
            val touchHeight = bounds.bottom - bounds.top
            assertTrue(
                "$description should have a 48 dp touch height; measured $touchHeight",
                touchHeight >= 48.dp
            )
            assertTrue("$description should occupy most of a 360dp panel", bounds.right - bounds.left >= 290.dp)
            listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).forEach { invalid ->
                composeRule.onNodeWithContentDescription(description)
                    .performSemanticsAction(SemanticsActions.SetProgress) { action ->
                        assertFalse(action(invalid))
                    }
            }
        }
        assertFalse("Invalid accessibility progress reached playback", receivedInvalidValue)
        listOf("Reset key").forEach { description ->
            val bounds = composeRule.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()
            assertTrue("$description lost its accessible touch target", bounds.right - bounds.left >= 48.dp)
        }
    }
}
