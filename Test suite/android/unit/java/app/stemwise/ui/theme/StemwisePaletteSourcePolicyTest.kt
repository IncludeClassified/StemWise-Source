package app.stemwise.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class StemwisePaletteSourcePolicyTest {
    @Test
    fun stemRolesPreserveColorsAndUnknownRolesWrapByIndex() {
        assertEquals(Color(0xFF09080D), StemwisePalette.pageGradientTop)
        assertEquals(Color(0xFF000000), StemwisePalette.pageGradientBottom)
        assertEquals(Color(0xFF171321), StemwisePalette.seekerLoopHandleFill)
        assertEquals(PrimaryPurple.copy(alpha = .24f), StemwisePalette.activeControlFill)
        assertEquals(PrimaryPurple.copy(alpha = .18f), StemwisePalette.selectedSurfaceFill)
        assertEquals(
            listOf(
                Color(0xFFFF4FA3),
                Color(0xFFFF9F43),
                Color(0xFF9B6CFF),
                Color(0xFF2DD4BF),
                Color(0xFF38BDF8),
                Color(0xFFFACC15)
            ),
            listOf("vocals", "drums", "bass", "guitar", "piano", "other")
                .mapIndexed { index, role -> StemwisePalette.stemColor(role, index) }
        )
        assertEquals(Color(0xFF9B6CFF), StemwisePalette.stemColor("unknown", 2))
        assertEquals(StemwisePalette.stemColor("vocals", 0), StemwisePalette.stemColor("VoCaLs", 5))
        assertEquals(StemwisePalette.stemColor("unknown", 1), StemwisePalette.stemColor("unknown", 7))
    }
}
