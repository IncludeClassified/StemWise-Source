package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.analysis.ImportedAnalysisSummary
import app.stemwise.model.LyricTrack
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.TimedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSessionTransitionsTest {
    @Test
    fun playableStemsRetainAnExplicitUnverifiedTimingLabel() {
        assertEquals("Timing unverified", songInfoMetadataRows(song("parts").copy(hasStems = true))
            .single { it.first == "Stem timing" }.second)
        assertEquals(emptyList<Pair<String, String>>(), songInfoMetadataRows(song("original"))
            .filter { it.first == "Stem timing" })
    }

    @Test
    fun deletionConfirmationKeepsTheSongThatOpenedItWhenPlaybackAdvances() {
        val requested = song("requested")
        val newlyActive = song("newly-active")

        assertEquals(
            requested,
            playerDeletionTarget(requested.id, listOf(newlyActive, requested))
        )
        assertEquals(null, playerDeletionTarget("removed", listOf(newlyActive)))
    }

    @Test
    fun playerSeedsItsClockOnlyFromTheMatchingAudioOwner() {
        assertEquals(
            42_125L,
            authoritativeInitialPlayerPosition(
                visibleSongId = "song-a",
                audioSongId = "song-a",
                audioPositionMs = 42_125L
            )
        )
        assertEquals(
            0L,
            authoritativeInitialPlayerPosition(
                visibleSongId = "song-b",
                audioSongId = "song-a",
                audioPositionMs = 238_450L
            )
        )
        assertEquals(
            0L,
            authoritativeInitialPlayerPosition(
                visibleSongId = "song-a",
                audioSongId = "song-a",
                audioPositionMs = -5L
            )
        )
    }

    @Test
    fun songDetailsExposeMusicalAndImportedMetadataWithoutDuplicatingYear() {
        val rows = songInfoMetadataRows(
            Song(
                id = "song-info-rich",
                videoId = "VideoId456",
                title = "Rich details",
                artist = "StemWise",
                durationMs = 12_000L,
                state = SongState.READY,
                accent = Color.Magenta,
                artworkGlyph = "RD",
                album = "Fixture album",
                year = "2026",
                bpm = 128f,
                key = "C# Minor",
                meter = "4/4",
                additionalInfo = linkedMapOf(
                    "Release year" to "2026",
                    "Genres" to "Electronic, Test fixture",
                    "Credits" to "Fixture Composer — composer"
                )
            ),
            ImportedAnalysisSummary(
                status = "complete",
                tuningFrequencyHz = 440.2,
                tuningCentsFromA440 = .79,
                integratedLufs = -10.7,
                loudnessRangeLu = 6.2,
                truePeakDbtp = -.8,
                sectionCount = 4
            )
        )

        assertEquals(
            listOf(
                "Album" to "Fixture album",
                "Year" to "2026",
                "Tempo" to "128 BPM",
                "Key" to "C# Minor",
                "Meter" to "4/4",
                "Tuning" to "440.20 Hz · +0.79 cents from A440",
                "Loudness" to "-10.7 LUFS",
                "Loudness range" to "6.2 LU",
                "True peak" to "-0.8 dBTP",
                "Structure" to "4 sections",
                "Genres" to "Electronic, Test fixture",
                "Credits" to "Fixture Composer — composer"
            ),
            rows
        )
    }

    @Test
    fun companionLyricsUseTheValidatedOccurrenceIndexAcrossOverlaps() {
        val companion = LyricTrack(
            id = "translation",
            kind = "translation",
            format = "synced",
            lines = listOf(
                TimedLyricLine(0L, 1_000L, "First occurrence"),
                TimedLyricLine(500L, 1_500L, "Second occurrence")
            )
        )

        assertEquals(
            "First occurrence",
            companionTextForPrimaryLine(companion, primaryIndex = 0)
        )
        assertEquals(
            "Second occurrence",
            companionTextForPrimaryLine(companion, primaryIndex = 1)
        )
    }

    @Test
    fun plainIpaCompanionStaysUntimedAroundTimedOriginal() {
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            format = "plain",
            text = "first ipa\nsecond ipa\nthird ipa"
        )

        assertEquals(
            null,
            companionTextForPrimaryLine(
                track = ipa,
                primaryIndex = 1
            )
        )
    }

    @Test
    fun individuallySelectedAlignedPlainIpaInheritsOriginalBoundaries() {
        val original = LyricTrack(
            id = "original",
            kind = "original",
            format = "synced",
            lines = listOf(
                TimedLyricLine(120L, 900L, "First"),
                TimedLyricLine(900L, 1_700L, "Second"),
                TimedLyricLine(1_700L, 2_500L, "Third")
            )
        )
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            format = "plain",
            text = "fɜːst\nsɛkənd\nθɜːd",
            basedOnTrackId = "original",
        )

        assertEquals(
            listOf(
                TimedLyricLine(120L, 900L, "fɜːst"),
                TimedLyricLine(900L, 1_700L, "sɛkənd"),
                TimedLyricLine(1_700L, 2_500L, "θɜːd")
            ),
            effectiveTimedLyricsLines(ipa, listOf(original, ipa))
        )
    }

    @Test
    fun unalignedPlainCompanionStaysASeparateBlock() {
        val prose = LyricTrack(
            id = "notes",
            kind = "translation",
            format = "plain",
            text = "One paragraph only"
        )

        assertEquals(
            null,
            companionTextForPrimaryLine(
                track = prose,
                primaryIndex = 0
            )
        )
    }

    @Test
    fun plainCombinedLyricsAlwaysPlaceOriginalAboveIpaAndTranslation() {
        val translation = LyricTrack(
            id = "translation",
            kind = "translation",
            format = "plain",
            text = "Meaning"
        )
        val original = LyricTrack(
            id = "original",
            kind = "original",
            format = "plain",
            text = "Words"
        )
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            format = "plain",
            text = "wɜːdz",
        )

        assertEquals(
            listOf("original", "ipa", "translation"),
            plainLyricsDisplayOrder(listOf(translation, original, ipa)).map(LyricTrack::id)
        )
    }

    @Test
    fun originalIpaTranslationAndAllShareExactCueBoundaries() {
        val original = LyricTrack(
            id = "original",
            kind = "original",
            language = "en",
            format = "timed",
            lines = List(10) { index ->
                TimedLyricLine(
                    startMs = index * 1_800L,
                    endMs = (index + 1) * 1_800L,
                    text = "Original 1.${index + 1}"
                )
            }
        )
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            language = "en-fonipa",
            format = "synced",
            lines = original.lines.map { it.copy(text = "/${it.text.lowercase()}/") },
            basedOnTrackId = original.id,
        )
        val translation = LyricTrack(
            id = "translation",
            kind = "translation",
            language = "ar",
            format = "synced",
            lines = original.lines.map { it.copy(text = "Translation ${it.text}") },
            basedOnTrackId = original.id
        )
        val tracks = listOf(original, ipa, translation)
        val projectedByMode = linkedMapOf(
            "Original" to effectiveTimedLyricsLines(original, tracks),
            "IPA" to effectiveTimedLyricsLines(ipa, tracks),
            "Translation" to effectiveTimedLyricsLines(translation, tracks),
            "All" to original.lines
        )

        projectedByMode.forEach { (mode, lines) ->
            assertEquals("$mode before first cue", -1, findActiveLyricIndex(lines, -1L))
            assertEquals("$mode first cue start", 0, findActiveLyricIndex(lines, 0L))
            assertEquals("$mode before second cue", 0, findActiveLyricIndex(lines, 1_799L))
            assertEquals("$mode exact second cue", 1, findActiveLyricIndex(lines, 1_800L))
            assertEquals("$mode live 14.25-second cue", 7, findActiveLyricIndex(lines, 14_250L))
            assertEquals("$mode exact ninth cue", 8, findActiveLyricIndex(lines, 14_400L))
            assertEquals("$mode final end is exclusive", -1, findActiveLyricIndex(lines, 18_000L))
        }

        assertEquals("Original 1.8", projectedByMode.getValue("Original")[7].text)
        assertEquals("/original 1.8/", projectedByMode.getValue("IPA")[7].text)
        assertEquals(
            "Translation Original 1.8",
            projectedByMode.getValue("Translation")[7].text
        )
    }

    @Test
    fun lyricsPanelUsesLosslessTracksWhenFlattenedLyricFieldsAreEmpty() {
        val original = LyricTrack(
            id = "original",
            kind = "original",
            format = "timed",
            lines = listOf(TimedLyricLine(0L, 1_800L, "Words"))
        )
        val ipa = LyricTrack(
            id = "ipa",
            kind = "transliteration",
            format = "plain",
            text = "wurdz",
            basedOnTrackId = original.id,
        )
        val translation = LyricTrack(
            id = "translation",
            kind = "translation",
            format = "plain",
            text = "Meaning",
            basedOnTrackId = original.id
        )
        val tracks = listOf(original, ipa, translation)
        val song = Song(
            id = "tracks-only",
            videoId = "TracksOnly1",
            title = "Tracks only",
            artist = "StemWise",
            durationMs = 1_800L,
            state = SongState.READY,
            accent = Color.Cyan,
            artworkGlyph = "TO",
            lyricTracks = tracks
        )

        assertEquals(tracks, resolvedLyricTracks(song))
    }

    private fun song(id: String) = Song(
        id = id,
        videoId = id,
        title = id,
        artist = "StemWise",
        durationMs = 1_000L,
        state = SongState.READY,
        accent = Color.Magenta,
        artworkGlyph = id
    )
}
