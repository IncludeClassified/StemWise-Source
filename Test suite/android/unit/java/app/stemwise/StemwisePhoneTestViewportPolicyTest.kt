package app.stemwise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemwisePhoneTestViewportPolicyTest {
    @Test
    fun `audit tablet viewport keeps the exact S23 Ultra display ratio`() {
        val geometry = requireNotNull(
            stemwisePhoneTestViewportGeometry(
                enabled = true,
                availableWidthDp = 1_691f,
                availableHeightDp = 1_056f
            )
        )
        assertEquals(432f, geometry.widthDp, 0f)
        assertEquals(3_088f / 1_440f, geometry.heightDp / geometry.widthDp, .00001f)
        assertTrue(geometry.heightDp <= 1_056f)
    }

    @Test
    fun `phone and release builds keep the real device viewport`() {
        assertNull(stemwisePhoneTestViewportGeometry(false, 1_691f, 1_056f))
        assertNull(stemwisePhoneTestViewportGeometry(true, 432f, 926.4f))
    }

    @Test
    fun `short tablet scales the frame without changing its aspect`() {
        val geometry = requireNotNull(
            stemwisePhoneTestViewportGeometry(true, 1_000f, 800f)
        )
        assertEquals(800f, geometry.heightDp, .001f)
        assertEquals(3_088f / 1_440f, geometry.heightDp / geometry.widthDp, .00001f)
    }

}
