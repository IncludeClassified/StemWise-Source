package app.stemwise.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BoundedTextMotionInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun actualOverflowMovesOnlyWhenMotionIsEnabledAndRetainsItsText() {
        val motion = mutableStateOf(true)
        val label = "Readable long title travels through the bounded viewport"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StemwiseTheme {
                CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(motion.value)) {
                    Text(label, maxLines = 1, fontSize = 20.sp,
                        modifier = Modifier.width(120.dp).testTag("bounded-label").stemwiseMarquee())
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        fun pixels(): IntArray {
            val image = compose.onNodeWithTag("bounded-label").captureToImage().toPixelMap()
            return IntArray(image.width * image.height) { index -> image[index % image.width, index / image.width].toArgb() }
        }
        val start = pixels()
        compose.mainClock.advanceTimeBy(4_000)
        assertFalse("Overflow did not move", start.contentEquals(pixels()))
        compose.runOnIdle { motion.value = false }
        compose.mainClock.advanceTimeByFrame()
        val still = pixels()
        compose.mainClock.advanceTimeBy(4_000)
        assertArrayEquals("Reduced motion moved the text", still, pixels())
        compose.onNodeWithText(label).assertExists()
        compose.mainClock.autoAdvance = true
    }
}
