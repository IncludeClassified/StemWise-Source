package app.stemwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.ui.design.LocalStemwiseMotion
import app.stemwise.ui.design.StemwiseMotionEvent
import app.stemwise.ui.design.StemwiseMotionSpec
import app.stemwise.ui.design.StemwiseSemanticMotion
import app.stemwise.ui.theme.StemwiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArtworkRevisionMotionInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun cachedRevisionInterpolatesPaletteAndPropagationWithinItsToken() = verifyRevision(false)

    @Test fun reducedMotionRevisionAppliesTheCachedPaletteWithoutMovement() = verifyRevision(true)

    private fun verifyRevision(reducedMotion: Boolean) {
        val id = java.util.UUID.randomUUID().toString()
        val original = Song(id, "abcdefghijk", "Artwork", "Artist", 1_000L,
            SongState.READY, Color.Green, artworkGlyph = "", mediaRevision = "first")
        val samePalette = original.copy(mediaRevision = "second")
        val redPalette = original.copy(mediaRevision = "third")
        val green = ArtworkAuraPalette(Color.Green, Color.Green, Color.Green)
        val red = ArtworkAuraPalette(Color.Red, Color.Red, Color.Red)
        ArtworkAuraPaletteCache.put(artworkAuraPaletteKey(original), green)
        ArtworkAuraPaletteCache.put(artworkAuraPaletteKey(samePalette), green)
        ArtworkAuraPaletteCache.put(artworkAuraPaletteKey(redPalette), red)
        val song = mutableStateOf(original)
        compose.setContent {
            StemwiseTheme {
                CompositionLocalProvider(LocalStemwiseMotion provides StemwiseMotionSpec(!reducedMotion, false)) {
                    Column {
                        Box(Modifier.size(264.dp).background(Color.Black).testTag("revision-artwork"),
                            contentAlignment = Alignment.Center) {
                            Artwork(song.value, 112.dp, auraMotionEnabled = false)
                        }
                        // Warm the second immutable texture before freezing frames; decoding is not under test.
                        Box(Modifier.size(264.dp).background(Color.Black).testTag("warm-artwork"),
                            contentAlignment = Alignment.Center) {
                            Artwork(redPalette, 112.dp, auraMotionEnabled = false)
                        }
                    }
                }
            }
        }
        fun energy(tag: String = "revision-artwork"): Pair<Float, Float> {
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            val halfExcluded = pixels.width * 66f / 264f
            var redSum = 0f
            var greenSum = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (kotlin.math.abs(x - pixels.width / 2f) <= halfExcluded &&
                    kotlin.math.abs(y - pixels.height / 2f) <= halfExcluded) continue
                redSum += pixels[x, y].red
                greenSum += pixels[x, y].green
            }
            return redSum to greenSum
        }
        compose.waitUntil(5_000L) { energy().second > 1f && energy("warm-artwork").first > 1f }
        val target = energy("warm-artwork")
        val duration = StemwiseSemanticMotion.token(StemwiseMotionEvent.ARTWORK_CHANGE).durationMs.toLong()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { song.value = samePalette }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val earlyPropagation = energy().second
            compose.mainClock.advanceTimeBy(duration)
            val finalPropagation = energy().second
            if (reducedMotion) assertEquals(finalPropagation, earlyPropagation, .01f)
            else assertTrue("Aura propagation jumped to its endpoint ($earlyPropagation -> $finalPropagation)",
                finalPropagation > earlyPropagation)

            compose.runOnIdle { song.value = redPalette }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val early = energy()
            compose.mainClock.advanceTimeBy(duration / 2)
            val middle = energy()
            compose.mainClock.advanceTimeBy(duration / 2 + 32L)
            val final = energy()
            assertEquals("Cached palette did not reach its independently rendered endpoint: $final",
                target.first / (target.first + target.second), final.first / (final.first + final.second), .005f)
            if (reducedMotion) {
                assertEquals(final.first, early.first, .01f)
                assertEquals(final.second, early.second, .01f)
            } else {
                assertTrue("Cached palette did not interpolate: early=$early middle=$middle final=$final",
                    middle.first > early.first && middle.first < final.first && middle.second > final.second)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
