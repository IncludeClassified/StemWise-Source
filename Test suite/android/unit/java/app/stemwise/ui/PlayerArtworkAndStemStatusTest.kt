package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.stemwise.playback.PreviousPressTracker
import app.stemwise.playback.previousRestartPositionMs
import app.stemwise.ui.player.nativeplayer.FullPlayerWaveformPacket
import app.stemwise.ui.player.surface.*
import org.junit.Assert.*
import org.junit.Test

class PlayerArtworkAndStemStatusTest {
    @Test
    fun previousUsesTwoSecondPairsAndRestartsOnlyInsideACompleteLoop() {
        val presses = PreviousPressTracker()
        assertFalse(presses.press("song", 1_000L))
        assertTrue(presses.press("song", 3_000L))
        assertFalse(presses.press("song", 3_001L))
        assertFalse(presses.press("song", 5_002L))
        assertFalse(presses.press("other", 5_100L))
        presses.reset()
        assertFalse(presses.press("other", 5_200L))
        assertFalse(presses.press("other", 5_000L)) // A clock discontinuity is not a second press.

        assertEquals(10_000L, previousRestartPositionMs(10_000L, 10_000L, 12_000L))
        assertEquals(10_000L, previousRestartPositionMs(11_999L, 10_000L, 12_000L))
        assertEquals(0L, previousRestartPositionMs(12_000L, 10_000L, 12_000L))
        assertEquals(0L, previousRestartPositionMs(9_999L, 10_000L, 12_000L))
        assertEquals(0L, previousRestartPositionMs(11_000L, 10_000L, null))
    }

    @Test
    fun importedBeatPulseTracksSourcePositionAndAbstainsWithoutBeats() {
        val beats = longArrayOf(1_000L, 1_500L, 2_000L)
        val packet = FullPlayerWaveformPacket(1L, "cover", floatArrayOf(), emptyList(), beats)
        beats[0] = 0L // Publication is immutable.
        assertEquals(0f, packet.beatPulseAt(999L)!!, .0001f)
        assertEquals(1f, packet.beatPulseAt(1_000L)!!, .0001f)
        assertEquals(.25f, packet.beatPulseAt(1_090L)!!, .0001f)
        assertEquals(0f, packet.beatPulseAt(1_180L)!!, .0001f)
        assertEquals(1f, packet.beatPulseAt(2_000L)!!, .0001f)
        assertEquals(1f, packet.beatPulseAt(1_000L)!!, .0001f) // Loop/seek back.
        assertEquals(0f, packet.beatPulseAt(9_000L)!!, .0001f)
        assertNull(FullPlayerWaveformPacket(2L, "cover", floatArrayOf(), emptyList()).beatPulseAt(1_000L))
        assertThrows(IllegalArgumentException::class.java) {
            FullPlayerWaveformPacket(3L, "cover", floatArrayOf(), emptyList(), longArrayOf(500L, 500L))
        }
    }

    @Test
    fun dominantCoverColorOwnsTheWholeGradientWithoutAveragingOpposingHues() {
        val red = 0xFFCC2222.toInt()
        val blue = 0xFF2222CC.toInt()
        val dominant = dominantArtworkColor(IntArray(100) { if (it < 60) red else blue })
        assertEquals(Color(red), dominant)
        assertEquals(Color(blue), dominantArtworkColor(intArrayOf(0x00FF0000, blue)))
        assertEquals(MissingArtworkAuraPalette.dominant, dominantArtworkColor(intArrayOf(0)))
        val gradient = playerArtworkBackgroundColors(ArtworkAuraPalette(Color.Blue, Color.Green, Color.Yellow, dominant))
        assertTrue(gradient.all { it.red > it.blue && it.alpha == 1f })
        assertTrue(gradient.zipWithNext().all { (a, b) -> a.luminance() > b.luminance() })
        assertTrue(gradient.all { (1.05f / (it.luminance() + .05f)) >= 4.5f })
    }

    @Test
    fun fullHeightBackdropRetainsItsHueAndReadableSecondaryText() {
        for (dominant in listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color(.1f, .02f, .06f))) {
            val gradient = playerArtworkBackgroundColors(ArtworkAuraPalette(dominant, dominant, dominant, dominant))
            val top = gradient.first()
            val bottom = gradient.last()
            assertTrue(maxOf(bottom.red, bottom.green, bottom.blue) >=
                maxOf(top.red, top.green, top.blue) * .7f)
            assertTrue(gradient.all {
                (app.stemwise.ui.theme.TextSecondary.luminance() + .05f) / (it.luminance() + .05f) >= 4.5f
            })
        }
    }

    @Test
    fun stemsDistinguishDownloadFromEnginePreparationAndReadiness() {
        val missing = PlayerSurfaceSourcePacket(1L, PlayerSurfaceSourceMode.ORIGINAL,
            PlayerSurfaceSourceMode.ORIGINAL, PlayerSurfaceSourcePhase.SETTLED, false)
        val downloading = missing.copy(stemsDownloading = true)
        val ready = downloading.copy(stemsAvailable = true)
        val preparing = ready.copy(phase = PlayerSurfaceSourcePhase.PREPARING)
        assertEquals(PlayerSurfaceStemsStatus.DOWNLOAD, playerSurfaceStemsStatus(missing))
        assertEquals(PlayerSurfaceStemsStatus.DOWNLOADING, playerSurfaceStemsStatus(downloading))
        assertEquals(PlayerSurfaceStemsStatus.READY, playerSurfaceStemsStatus(ready))
        assertEquals(PlayerSurfaceStemsStatus.FAILED,
            playerSurfaceStemsStatus(missing.copy(stemsPreparationFailed = true)))
        assertEquals(PlayerSurfaceStemsStatus.READY,
            playerSurfaceStemsStatus(ready.copy(stemsPreparationFailed = true)))
        val failed = ready.copy(phase = PlayerSurfaceSourcePhase.FAILED)
        assertEquals(PlayerSurfaceStemsStatus.FAILED, playerSurfaceStemsStatus(failed))
        assertTrue(playerSurfaceStemsActionEnabled(failed))
        assertEquals(PlayerSurfaceSourceMode.ORIGINAL, PlayerSurfaceSourceSemantics.from(failed).selectedMode)
        assertEquals(PlayerSurfaceStemsStatus.SWITCHING,
            playerSurfaceStemsStatus(ready.copy(phase = PlayerSurfaceSourcePhase.RETURNING)))
        assertEquals(PlayerSurfaceStemsStatus.SWITCHING, playerSurfaceStemsStatus(preparing))
        assertEquals(PlayerSurfaceStemsStatus.PREPARING,
            playerSurfaceStemsStatus(missing.copy(stemsAvailabilitySettled = false)))
        assertTrue(playerSurfaceStemsActionEnabled(missing))
        assertTrue(playerSurfaceStemsActionEnabled(downloading))
        assertFalse(playerSurfaceStemsActionEnabled(preparing))
        assertTrue(PlayerSurfaceSourceSemantics.from(downloading).stemsLabel.contains("Activity"))
        assertEquals("Stems ready", PlayerSurfaceSourceSemantics.from(ready).stemsLabel)
    }
}
