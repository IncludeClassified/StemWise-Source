package app.stemwise.ui

import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseMotionEasing
import app.stemwise.ui.design.StemwiseSemanticMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterminateProgressMotionPolicyTest {
    @Test
    fun `progress change resolves through the explicit standard contract`() {
        val token = StemwiseSemanticMotion.token(StemwiseMotionEvent.PROGRESS_CHANGE)
        assertEquals(200, token.durationMs)
        assertEquals(StemwiseMotionEasing.STANDARD, token.easing)
    }

}
