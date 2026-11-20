package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkAuraPalettePolicyTest {
    @Test
    fun `artwork edge can fully own the aura hue`() {
        val base = Color(1f, 0f, 1f, 1f)
        val hostileGreen = Color(0f, 1f, 0f, 1f)

        val constrained = compatibleArtworkAuraColor(base, hostileGreen, influence = 1f)

        assertEquals(0f, constrained.red, .002f)
        assertEquals(1f, constrained.green, .002f)
        assertEquals(0f, constrained.blue, .002f)
        assertEquals(1f, constrained.alpha, .0001f)
    }

    @Test
    fun `shared emission floor preserves vivid edges and lifts dark ones without desaturation`() {
        val emitted = artworkAuraEmissionColor(Color(.10f, .90f, .10f, 1f))
        val dark = artworkAuraEmissionColor(Color(.10f, .20f, .30f, 1f))

        assertEquals(.10f, emitted.red, .005f)
        assertEquals(.90f, emitted.green, .005f)
        assertEquals(.10f, emitted.blue, .005f)
        assertEquals(1f, emitted.alpha, .0001f)
        assertEquals(.1833f, dark.red, .005f)
        assertEquals(.3667f, dark.green, .005f)
        assertEquals(.55f, dark.blue, .005f)
    }

    @Test
    fun `palette sampling has a fixed budget and includes exact boundaries`() {
        for ((width, height) in listOf(1 to 1, 24 to 24, 4096 to 2160, 100000 to 1)) {
            val visited = mutableListOf<Pair<Int, Int>>()
            val pixels = sampleArtworkPalettePixels(width, height) { x, y ->
                visited += x to y
                x xor y
            }
            assertEquals(24 * 24, pixels.size)
            assertEquals(24 * 24, visited.size)
            assertEquals(0 to 0, visited.first())
            assertEquals((width - 1) to (height - 1), visited.last())
            assertTrue(visited.all { (x, y) -> x in 0 until width && y in 0 until height })
        }
    }

}
