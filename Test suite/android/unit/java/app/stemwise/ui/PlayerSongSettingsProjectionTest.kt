package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.data.SongLoopPreference
import app.stemwise.data.SongMetronomeSettings
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.SongTrimRange
import app.stemwise.data.StemAssetEntity
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.StemMixState
import org.junit.Assert.*
import org.junit.Test

class PlayerSongSettingsProjectionTest {
    @Test fun lateCacheForAnIntermediateSelectionCannotAdvanceTheStillCurrentRequestedSong() {
        val a = song("a")
        val b = song("b")
        val c = song("c")
        val settings = SongPlaybackSettings(tempo = .75f, semitones = -3)
        assertSame(a, preparedPlayerSong(b, null, a, settings))
        // B finishes after the cursor has moved to C: only C's readiness is relevant.
        assertSame(a, preparedPlayerSong(c, null, a, settings))
        assertSame(c, preparedPlayerSong(c, settings, a, settings))
    }

    @Test fun unreadFirstOpenOrInvalidatedPriorRootDoesNotInventDefaultSettings() {
        val old = song("same")
        val replacement = old.copy(sourceHash = "b".repeat(64), audioRevision = "new-original")
        assertNull(preparedPlayerSong(replacement, null, null, null))
        assertNull(preparedPlayerSong(replacement, null, old, null))
        assertSame(old, preparedPlayerSong(replacement, null, old, SongPlaybackSettings()))
        // A verified empty row is ready and legitimately displays defaults.
        assertSame(replacement, preparedPlayerSong(replacement, SongPlaybackSettings(), old, null))
    }

    @Test fun pendingSongUsesItsCachedTrimLoopTuneAndMetronomeInsteadOfOutgoingValues() {
        val saved = SongPlaybackSettings(trim = SongTrimRange(1_000L, 6_000L),
            loop = SongLoopPreference(2_000L, 4_000L, true), tempo = .75f, semitones = -5,
            metronome = SongMetronomeSettings(true, .2f, 2, 5, 8))
        val outgoing = AudioPlaybackState(songId = "outgoing", tempo = 2f, semitoneShift = 12,
            durationMs = 800L, trimStartMs = 20_000L, metronomeVolume = .9f)
        val projected = projectPlayerSongSettings(song("pending"), outgoing, false, saved)
        assertEquals(10_000L, projected.sourceDurationMs)
        assertEquals(1_000L, projected.trimStartMs)
        assertEquals(5_000L, projected.durationMs)
        assertEquals(1_000L, projected.loopA)
        assertEquals(3_000L, projected.loopB)
        assertEquals(.75f, projected.tempo, 0f)
        assertEquals(-5, projected.semitones)
        assertEquals(saved.metronome, projected.metronome)
    }

    @Test fun exactCurrentOwnerRemainsTheControlAuthorityEvenWhenSavedIntentDiffers() {
        val audio = AudioPlaybackState(songId = "current", durationMs = 3_000L,
            sourceDurationMs = 10_000L, trimStartMs = 2_000L, loopA = 600L, loopB = 2_200L,
            tempo = 1.35f, semitoneShift = 4, metronomeEnabled = false, metronomeVolume = .6f,
            metronomeSubdivision = 4, metronomeNumerator = 7, metronomeDenominator = 8)
        val saved = SongPlaybackSettings(trim = SongTrimRange(1_000L, 8_000L), tempo = .5f,
            semitones = -12, metronome = SongMetronomeSettings(true, .2f, 2, 5, 4))
        val projected = projectPlayerSongSettings(song("current"), audio, true, saved)
        assertEquals(2_000L, projected.trimStartMs)
        assertEquals(3_000L, projected.durationMs)
        assertEquals(600L, projected.loopA)
        assertEquals(2_200L, projected.loopB)
        assertEquals(1.35f, projected.tempo, 0f)
        assertEquals(4, projected.semitones)
        assertEquals(SongMetronomeSettings(false, .6f, 4, 7, 8), projected.metronome)
    }

    @Test fun aLoopOutsideTheAppliedTrimIsNotRebasedIntoAFabricatedRange() {
        val saved = SongPlaybackSettings(trim = SongTrimRange(1_000L, 6_000L),
            loop = SongLoopPreference(500L, 4_000L, true))
        val projected = projectPlayerSongSettings(song("trimmed"), AudioPlaybackState(), false, saved)
        assertNull(projected.loopA)
        assertNull(projected.loopB)
        assertEquals(SongLoopPreference(500L, 4_000L, true), saved.loop)
    }

    @Test fun disabledSavedLoopDoesNotLookEnabledWhileItsOwnerIsPreparing() {
        val saved = SongPlaybackSettings(loop = SongLoopPreference(1_000L, 3_000L, false))
        val projected = projectPlayerSongSettings(song("paused-loop"), AudioPlaybackState(), false, saved)
        assertEquals(1_000L, projected.loopA)
        assertNull(projected.loopB)
        assertEquals(3_000L, saved.loop.pointB)
    }

    @Test fun savedCanonicalRoleMixSurvivesNewAssetIdsWhileExactCurrentAssetMixWins() {
        val assets = listOf(asset("new-vocals-id", "vocals"), asset("new-bass-id", "bass"))
        val saved = mapOf("vocals" to StemMixState(.25f, true, true))
        val pending = playerSettingsStemRows(assets, emptyMap(), saved)
        assertEquals(.25f, pending[0].volume, 0f)
        assertTrue(pending[0].muted)
        assertTrue(pending[0].solo)
        assertEquals(1f, pending[1].volume, 0f)
        val current = playerSettingsStemRows(assets,
            mapOf("new-vocals-id" to StemMixState(.8f, false, false)), saved)
        assertEquals(.8f, current[0].volume, 0f)
        assertFalse(current[0].muted)
        assertFalse(current[0].solo)
    }

    private fun song(id: String) = Song(id, id, id, "Artist", 10_000L, SongState.READY,
        Color.Cyan, artworkGlyph = "S", sourceHash = "a".repeat(64))

    private fun asset(id: String, role: String) = StemAssetEntity(id, "set", role, role,
        "content://parts/$id", "a".repeat(64), 10_000L, "flac", 48_000, 2, true)
}
