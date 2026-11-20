package app.stemwise.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import app.stemwise.ui.player.surface.playerSurfaceArtworkAuraProfileCornerRadiusDp
import app.stemwise.ui.player.surface.playerSurfaceArtworkAuraRasterPaddingDp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class ArtworkAuraThumbnailEdgeInstrumentationTest {
    @Test
    fun paletteCacheReusesTheExactArtworkIdentityWithoutReadingItsBitmapAgain() {
        val key = "palette-reuse:" + java.util.UUID.randomUUID()
        val first = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        first.eraseColor(Color.GREEN)
        val palette = ArtworkAuraPaletteCache.deriveAndPut(key, first)
        first.recycle()
        assertEquals(palette, ArtworkAuraPaletteCache.deriveAndPut(key, first))
        val changed = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        changed.eraseColor(Color.RED)
        try {
            val replacement = ArtworkAuraPaletteCache.deriveAndPut(key + ":new-revision", changed)
            org.junit.Assert.assertNotEquals(palette, replacement)
        } finally { changed.recycle() }
    }

    @Test
    fun livePaletteSubscriptionSurvivesAnEarlierEqualKeyBeingReleased() {
        val keyCharacters = "palette-observer-${System.nanoTime()}".toCharArray()
        fun firstSubscription() = ArtworkAuraPaletteCache.observe(String(keyCharacters))
        val original = firstSubscription()
        val retainedKey = String(keyCharacters)
        val retainedSubscription = ArtworkAuraPaletteCache.observe(retainedKey)
        assertSame(original, retainedSubscription)

        // Reopening a view retains an equal key, while the previous view's key can be collected.
        repeat(3) {
            System.gc()
            Thread.sleep(30L)
        }
        ArtworkAuraPaletteCache.put(retainedKey, MissingArtworkAuraPalette)
        assertEquals(MissingArtworkAuraPalette, retainedSubscription.value)
    }

    @Test
    fun composeOuterBlurNeverTouchesARetainedTextureBoundaryAcrossAllBandsAndEndpoints() {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        listOf(
            AuraCase("thumbnail", contentSizeDp = 52f, cornerRadiusDp = 10f, baseReachDp = 5f),
            AuraCase("near", contentSizeDp = 184f, cornerRadiusDp = 18f, baseReachDp = 18f),
            AuraCase("far", contentSizeDp = 184f, cornerRadiusDp = 18f, baseReachDp = 56f)
        ).forEach { case ->
            (-1..2).forEach { blurOffsetDp ->
                val endpointReachDp = case.baseReachDp + blurOffsetDp
                val paddingPx = artworkAuraRetainedPaddingDp(case.baseReachDp) * density
                val logicalEdgePx = ceil(case.contentSizeDp * density + paddingPx * 2f).toInt()
                val sizing = artworkAuraTextureSizing(logicalEdgePx, logicalEdgePx)
                val scaledPaddingPx = paddingPx * sizing.cacheScale
                assertOuterBlurPerimeterTransparent(
                    label = "Compose ${case.label} offset $blurOffsetDp",
                    contentSizePx = sizing.bitmapWidthPx - scaledPaddingPx * 2f,
                    cornerRadiusPx = case.cornerRadiusDp * density * sizing.cacheScale,
                    blurRadiusPx = artworkAuraMaskBlurRadiusDp(endpointReachDp) *
                        density * sizing.cacheScale,
                    rasterPaddingPx = scaledPaddingPx,
                    bitmapEdgePx = sizing.bitmapWidthPx
                )
            }
        }
    }

    @Test
    fun nativeOuterBlurNeverTouchesItsUsedCacheBoundaryAcrossProfilesBandsAndEndpoints() {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val contentSizePx = 112f * density
        listOf(18f, 56f).forEach { baseReachDp ->
            repeat(2) { profileIndex ->
                val cornerRadiusPx =
                    playerSurfaceArtworkAuraProfileCornerRadiusDp(profileIndex) * density
                (-1..2).forEach { blurOffsetDp ->
                    val endpointReachDp = baseReachDp + blurOffsetDp
                    val rasterPaddingPx =
                        playerSurfaceArtworkAuraRasterPaddingDp(endpointReachDp) * density
                    val logicalEdgePx = contentSizePx + rasterPaddingPx * 2f
                    val cacheScale = minOf(1f, 512f / logicalEdgePx.coerceAtLeast(1f))
                    assertOuterBlurPerimeterTransparent(
                        label = "Native reach $baseReachDp profile $profileIndex offset $blurOffsetDp",
                        contentSizePx = contentSizePx * cacheScale,
                        cornerRadiusPx = cornerRadiusPx * cacheScale,
                        blurRadiusPx = artworkAuraMaskBlurRadiusDp(endpointReachDp) *
                            density * cacheScale,
                        rasterPaddingPx = rasterPaddingPx * cacheScale
                    )
                }
            }
        }
    }

    private fun assertOuterBlurPerimeterTransparent(
        label: String,
        contentSizePx: Float,
        cornerRadiusPx: Float,
        blurRadiusPx: Float,
        rasterPaddingPx: Float,
        bitmapEdgePx: Int = ceil(contentSizePx + rasterPaddingPx * 2f).toInt()
    ) {
        val edgePx = bitmapEdgePx
        val bitmap = Bitmap.createBitmap(edgePx, edgePx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val center = edgePx * .5f
            val halfContent = contentSizePx * .5f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.OUTER)
            }
            canvas.drawRoundRect(
                RectF(
                    center - halfContent,
                    center - halfContent,
                    center + halfContent,
                    center + halfContent
                ),
                cornerRadiusPx,
                cornerRadiusPx,
                paint
            )

            val perimeterAlpha = buildList {
                for (coordinate in 0 until edgePx) {
                    add(Color.alpha(bitmap.getPixel(coordinate, 0)))
                    add(Color.alpha(bitmap.getPixel(coordinate, edgePx - 1)))
                    add(Color.alpha(bitmap.getPixel(0, coordinate)))
                    add(Color.alpha(bitmap.getPixel(edgePx - 1, coordinate)))
                }
            }
            val contentLeft = center - halfContent
            val contentRight = center + halfContent
            val outsideAlpha = buildList {
                for (y in 0 until edgePx) {
                    for (x in 0 until edgePx) {
                        if (x < contentLeft || x > contentRight || y < contentLeft || y > contentRight) {
                            add(Color.alpha(bitmap.getPixel(x, y)))
                        }
                    }
                }
            }
            assertEquals(
                "$label clipped against the retained texture boundary",
                0,
                perimeterAlpha.maxOrNull()
            )
            assertTrue("$label emitted no visible outer aura", outsideAlpha.any { it > 0 })
            assertTrue(
                "$label lost its graduated falloff",
                outsideAlpha.asSequence().filter { it > 0 }.distinct().take(2).count() == 2
            )
        } finally {
            bitmap.recycle()
        }
    }

    private data class AuraCase(
        val label: String,
        val contentSizeDp: Float,
        val cornerRadiusDp: Float,
        val baseReachDp: Float
    )
}
