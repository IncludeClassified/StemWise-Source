package app.stemwise.ui

import androidx.compose.ui.graphics.Color
import app.stemwise.model.Song
import app.stemwise.model.SongState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    @Test
    fun `unicode caseless folding matches sigma forms and preserves authored spans`() {
        for ((first, second) in listOf("ΟΣ" to "ος", "σ" to "ς", "οσ" to "ος")) {
            for ((title, query) in listOf(first to second, second to first)) {
                assertTrue("$title must match $query",
                    librarySongSearchKey(fixture().copy(title = title)).matches(librarySearchQueryKey(query)))
                assertEquals(listOf(StemwiseSearchRange(2, 2 + title.length)),
                    stemwiseSearchRanges("🎵$title!", query))
            }
        }
        for ((title, query) in listOf("Straße" to "STRASSE", "oﬃce" to "OFFICE", "İ" to "i\u0307")) {
            assertTrue(librarySongSearchKey(fixture().copy(title = title)).matches(librarySearchQueryKey(query)))
            assertEquals(listOf(StemwiseSearchRange(0, title.length)), stemwiseSearchRanges(title, query))
        }
        assertTrue(stemwiseSearchRanges("ı", "i").isEmpty())
        assertTrue(stemwiseSearchRanges("İ", "ı").isEmpty())
        assertFalse(librarySearchQueryKey("ı") == librarySearchQueryKey("i"))
        assertEquals(listOf(StemwiseSearchRange(0, 1)), stemwiseSearchRanges("ß", "ss"))
        assertEquals(listOf(StemwiseSearchRange(2, 4)), stemwiseSearchRanges("🎵𐐀!", "𐐨"))
    }

    @Test fun queryOuterWhitespaceDoesNotChangeMatchesOrAuthoredHighlightOffsets() {
        for (query in listOf("Song", " Song ", "\nSong\t")) {
            assertEquals(listOf(StemwiseSearchRange(2, 6)), stemwiseSearchRanges("  Song title", query))
            assertEquals(librarySearchQueryKey("Song"), librarySearchQueryKey(query))
        }
        assertEquals(emptyList<StemwiseSearchRange>(), stemwiseSearchRanges("one two", "one  two"))
        assertEquals(listOf(StemwiseSearchRange(0, 8)), stemwiseSearchRanges("one  two", " one  two "))
    }

    @Test
    fun `Hangul canonical composition preserves authored highlight spans`() {
        for ((composed, decomposed) in listOf("가" to "가", "각" to "각", "가수" to "가수")) {
            for ((title, query) in listOf(composed to decomposed, decomposed to composed)) {
                assertTrue(librarySongSearchKey(fixture().copy(title = title)).matches(librarySearchQueryKey(query)))
                assertEquals(listOf(StemwiseSearchRange(0, title.length)),
                    stemwiseSearchHighlightPresentation(title, query).ranges)
            }
        }
        assertEquals(listOf(StemwiseSearchRange(2, 5)),
            stemwiseSearchHighlightPresentation("🎵각!", "각").ranges)
    }

    @Test
    fun `unfiltered rows do not traverse or sort hidden metadata`() {
        var traversals = 0
        val metadata = mapOf("Composer" to "Aurelia")
        val countedMetadata = object : Map<String, String> by metadata {
            override val entries: Set<Map.Entry<String, String>>
                get() {
                    traversals++
                    return metadata.entries
                }
        }
        val song = fixture().copy(additionalInfo = countedMetadata)
        assertEquals(null, librarySearchContext(song, ""))
        assertEquals(null, librarySearchContext(song, " \t"))
        assertEquals(0, traversals)
        assertEquals("Composer · Aurelia", librarySearchContext(song, "Aurelia")?.displayText)
        assertTrue(traversals > 0)
    }

    @Test
    fun `only a real search clear requests the hero`() {
        assertFalse(librarySearchWasCleared(previouslyHadText = false, currentQuery = ""))
        assertFalse(librarySearchWasCleared(previouslyHadText = false, currentQuery = "violet"))
        assertFalse(librarySearchWasCleared(previouslyHadText = true, currentQuery = "violet"))
        assertTrue(librarySearchWasCleared(previouslyHadText = true, currentQuery = ""))
    }

    @Test
    fun `nfc match maps back to the exact authored utf16 cluster`() {
        val authored = "Cafe\u0301 noir"
        assertEquals(
            listOf(StemwiseSearchRange(3, 5)),
            stemwiseSearchRanges(authored, "é")
        )
    }

    @Test
    fun `arabic query may omit tatweel and optional marks without changing indices`() {
        val authored = "مَـرْحَبًا"
        val ranges = stemwiseSearchRanges(authored, "مرحبا")
        assertEquals(listOf(StemwiseSearchRange(0, authored.length)), ranges)
        assertTrue(stemwiseSearchRanges(authored, "مَ").isNotEmpty())
    }

    @Test
    fun `cached comparison keys preserve latin and arabic search behavior`() {
        val song = fixture().copy(
            title = "Cafe\u0301 noir",
            artist = "مَـرْحَبًا",
            additionalInfo = mapOf("Producer" to "Amina Noor")
        )
        val key = librarySongSearchKey(song)

        assertTrue(key.matches(librarySearchQueryKey("É NOIR")))
        assertTrue(key.matches(librarySearchQueryKey("مرحبا")))
        assertTrue(key.matches(librarySearchQueryKey("مَ")))
        assertTrue(key.matches(librarySearchQueryKey("amina")))
        assertFalse(key.matches(librarySearchQueryKey("unrelated")))
    }

    @Test
    fun `cached search scales beyond the former 512 song ceiling`() {
        val keys = List(8_192) { index ->
            librarySongSearchKey(
                fixture().copy(
                    id = "song-$index",
                    title = if (index == 8_191) "Needle Finale" else "Library Song $index"
                )
            )
        }
        val query = librarySearchQueryKey("needle")

        assertEquals(1, keys.count { it.matches(query) })
    }

    @Test
    fun `search results publish only for the exact snapshot and query`() {
        val firstSnapshot = listOf(fixture())
        val equalReplacement = firstSnapshot.toList()
        val firstQuery = requireNotNull(librarySearchQueryKey("violet"))
        val result = LibrarySongSearchResult(firstSnapshot, firstQuery, firstSnapshot)

        assertEquals(firstSnapshot, libraryVisibleSearchSongs(firstSnapshot, firstQuery, result))
        assertTrue(
            libraryVisibleSearchSongs(equalReplacement, firstQuery, result).isEmpty()
        )
        assertTrue(
            libraryVisibleSearchSongs(
                firstSnapshot,
                requireNotNull(librarySearchQueryKey("circuit")),
                result
            ).isEmpty()
        )
    }

    @Test
    fun `hidden metadata participates and supplies a contextual result line`() {
        val song = fixture().copy(
            album = "Night Geometry",
            year = "2025",
            bpm = 128f,
            key = "C# Minor",
            meter = "7/8",
            additionalInfo = mapOf("Producer" to "Amina Noor")
        )
        val key = librarySongSearchKey(song)
        assertTrue(key.matches(librarySearchQueryKey("Amina")))
        assertEquals("Producer · Amina Noor", librarySearchContext(song, "Amina")?.displayText)
        assertEquals(
            listOf(StemwiseSearchRange(11, 16)),
            librarySearchContext(song, "Amina")?.displayRanges
        )
        assertTrue(key.matches(librarySearchQueryKey("128 BPM")))
        assertFalse(key.matches(librarySearchQueryKey("unrelated")))
    }

    @Test
    fun `hidden context is causal and never fabricated from its label`() {
        val song = fixture().copy(
            title = "Key Change in a New Year",
            artist = "Tempo Makers",
            album = "Meter Studies",
            year = "2025",
            bpm = 128f,
            key = "C major",
            meter = "7/8"
        )
        val key = librarySongSearchKey(song)

        listOf("key", "year", "tempo", "meter").forEach { visibleQuery ->
            assertTrue(key.matches(librarySearchQueryKey(visibleQuery)))
            assertEquals(null, librarySearchContext(song, visibleQuery))
        }
        val causal = librarySearchContext(song, "major")
        assertEquals("Key · C major", causal?.displayText)
        assertEquals(listOf(StemwiseSearchRange(8, 13)), causal?.displayRanges)
    }

    @Test
    fun `latin and arabic highlight only exact matched letters`() {
        val latin = stemwiseSearchHighlightPresentation("Starlight", "star")
        val arabic = stemwiseSearchHighlightPresentation("ليلة النجوم", "نجوم")

        assertEquals(listOf(StemwiseSearchRange(0, 4)), latin.ranges)
        assertEquals(StemwiseSearchHighlightMode.MATCHED_LETTERS, latin.mode)
        assertEquals(listOf(StemwiseSearchRange(7, 11)), arabic.ranges)
        assertEquals(
            StemwiseSearchHighlightMode.MATCHED_LETTERS,
            arabic.mode
        )
    }

    @Test
    fun `focus presentation applies to empty and populated search without moving columns`() {
        val idle = librarySearchFieldPresentation(focused = false)
        val focused = librarySearchFieldPresentation(focused = true)

        assertEquals(1f, idle.borderWidthDp, 0f)
        assertEquals(app.stemwise.ui.theme.OutlineStrong, idle.borderColor)
        assertFalse(idle.focusedShadow)
        assertEquals(1.5f, focused.borderWidthDp, 0f)
        assertEquals(app.stemwise.ui.theme.PrimaryPurple, focused.borderColor)
        assertTrue(focused.focusedShadow)
    }

    @Test
    fun `arabic scale one rows use 82dp bodies on an 86dp pitch`() {
        assertEquals(
            LibrarySearchRowGeometryDp(minimumHeightDp = 82f, pitchDp = 86f),
            librarySearchRowGeometryDp(fontScale = 1f, query = "نجوم", lineCount = 3)
        )
        assertEquals(
            LibrarySearchRowGeometryDp(minimumHeightDp = 76f, pitchDp = 76f),
            librarySearchRowGeometryDp(fontScale = 1f, query = "star", lineCount = 3)
        )
        assertEquals(
            LibrarySearchRowGeometryDp(minimumHeightDp = 114f, pitchDp = 114f),
            librarySearchRowGeometryDp(fontScale = 1.3f, query = "نجوم", lineCount = 3)
        )
    }

    private fun fixture() = Song(
        id = "search-fixture",
        videoId = "SEARCH00001",
        title = "Violet Circuit",
        artist = "StemWise",
        durationMs = 12_000L,
        state = SongState.READY,
        accent = Color(0xFFA970FF),
        artworkGlyph = "VC"
    )
}
