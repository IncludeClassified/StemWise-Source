package app.stemwise.enrichment

import app.stemwise.data.projectEnrichmentLyricTracks
import app.stemwise.model.LyricTimingStatus
import app.stemwise.ui.LyricLineTimingIndex
import app.stemwise.ui.findActiveLyricIndex
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RegionalLyricsContractTest {
    private fun fixture(): JSONObject = checkNotNull(javaClass.classLoader?.getResourceAsStream(
        "fixtures/enrichment/regional-lyrics.json"
    )).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun tracks(value: JSONObject) = value.getJSONObject("lyrics").getJSONArray("tracks").let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }

    private fun completeEstimates(): JSONObject {
        val document = fixture()
        val lyrics = document.getJSONObject("lyrics").put("status", "complete")
        lyrics.remove("reason")
        val original = tracks(document).single().put("language", "tr")
        val texts = listOf("Bir iki üç", "Olası tekrar", "Dört")
        val times = listOf(listOf(100L to 300L, 400L to 600L, 700L to 1000L),
            listOf(1100L to 1300L, 1400L to 1800L), listOf(2000L to 2300L))
        val lines = JSONArray()
        texts.zip(times).forEach { (text, intervals) ->
            fun timing(start: Long, end: Long) = JSONObject().put("status", "estimated")
                .put("startMs", start).put("endMs", end).put("basis", "Acoustic prediction; unverified")
            val words = JSONArray()
            displayWordUtf16Ranges(text).zip(intervals).forEach { (range, interval) ->
                words.put(JSONObject().put("startUtf16", range.startUtf16).put("endUtf16", range.endUtf16)
                    .put("timing", timing(interval.first, interval.second)))
            }
            lines.put(JSONObject().put("text", text).put("contentStatus", "admitted").put("occurrenceStatus", "supported")
                .put("timing", timing(intervals.first().first, intervals.last().second)).put("words", words))
        }
        original.put("lines", lines)
        listOf(Triple("translation", "en", listOf("One two three", "Possible repeat", "Four")),
            Triple("transliteration", "tr-fonipa", listOf("biɾ iki ytʃ", "olasɯ tekɾaɾ", "dœɾt"))).forEach { (kind, language, values) ->
            val companionLines = JSONArray()
            values.forEachIndexed { index, text ->
                val line = JSONObject(lines.getJSONObject(index).toString()).put("text", text)
                line.remove("words")
                line.getJSONObject("timing").put("basis", "Line correspondence inherited from Original")
                companionLines.put(line)
            }
            lyrics.getJSONArray("tracks").put(JSONObject().put("id", kind).put("kind", kind).put("language", language)
                .put("format", "regional").put("basedOnTrackId", "original")
                .put("source", JSONObject().put("name", "Reviewed fixture")).put("lines", companionLines))
        }
        return document
    }

    @Test
    fun completeEstimatedCompanionsSurviveImportInstallationAndProjectionWithoutSupportedSeek() {
        val incoming = completeEstimates()
        validateEnrichmentContract(incoming, hasCover = false)
        val media = InstalledMediaIdentity("a".repeat(64), 1234L)
        val installed = createInstalledEnrichmentDocument(incoming, media.sha256, mapOf("original.opus" to media),
            1L, "b".repeat(64), JSONObject().put("kind", "other").put("source", JSONObject().put("name", "Fixture")),
            "c".repeat(64), 100)
        val readback = JSONObject(installed.toString())
        validateInstalledEnrichmentDocument(readback, "a1B2c3D4e5F", media.sha256)
        val projected = projectEnrichmentLyricTracks(tracks(readback))
        assertEquals(3, projected.size)
        projected.forEach { track ->
            assertEquals(3, track.lines.size)
            track.lines.forEach { line ->
                assertEquals(LyricTimingStatus.ESTIMATED, line.timingStatus)
                assertEquals("Approximate timing", line.timingLabel)
                assertNull(line.supportedSeekMs)
            }
            assertEquals(1, LyricLineTimingIndex(track.lines).activeAt(1150))
        }
        assertEquals(listOf(3, 2, 1), projected.first().lines.map { it.words.size })
        assertTrue(projected.first().lines.flatMap { it.words }.all { it.timingStatus == LyricTimingStatus.ESTIMATED })
        assertEquals(listOf(100L, 1100L, 2000L), projected[1].lines.map { it.startMs })
    }

    @Test
    fun companionsCannotUpgradeEstimatesChangeIntervalsOrInventOccurrences() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { line -> line.getJSONObject("timing").put("status", "supported").remove("basis") },
            { line -> line.getJSONObject("timing").put("startMs", 101) },
            { line -> line.put("occurrenceStatus", "unknown") }
        )
        mutations.forEach { mutate ->
            val document = completeEstimates()
            mutate(tracks(document)[1].getJSONArray("lines").getJSONObject(0))
            assertEquals("LYRICS_INVALID", runCatching { validateEnrichmentContract(document, hasCover = false) }.exceptionOrNull()?.message)
        }
        val document = completeEstimates()
        tracks(document)[1].getJSONArray("lines").remove(2)
        assertEquals("LYRICS_INVALID", runCatching { validateEnrichmentContract(document, hasCover = false) }.exceptionOrNull()?.message)
    }

    @Test
    fun importInstallationReadbackAndProjectionPreserveEveryClaim() {
        val incoming = fixture()
        validateEnrichmentContract(incoming, hasCover = false)
        val original = InstalledMediaIdentity("a".repeat(64), 1234L)
        val installed = createInstalledEnrichmentDocument(incoming, original.sha256,
            mapOf("original.opus" to original), 1L, "b".repeat(64),
            JSONObject().put("kind", "other").put("source", JSONObject().put("name", "Fixture")),
            "c".repeat(64), 100)
        val readback = JSONObject(installed.toString())
        validateInstalledEnrichmentDocument(readback, "a1B2c3D4e5F", original.sha256)
        assertEquals(incoming.getJSONObject("lyrics").toString(), readback.getJSONObject("lyrics").toString())
        val before = projectEnrichmentLyricTracks(tracks(incoming)).single()
        val after = projectEnrichmentLyricTracks(tracks(readback)).single()
        assertEquals(before, after)
        assertEquals(listOf("One two three", "Possible repeat", "Four"), after.lines.map { it.text })
        assertEquals(listOf(LyricTimingStatus.SUPPORTED, LyricTimingStatus.ESTIMATED, LyricTimingStatus.NONE),
            after.lines.first().words.map { it.timingStatus })
        assertEquals("bounded-acoustic-proposal", after.lines.first().words[1].timingBasis)
        assertNull(after.lines.first().words[2].startMs)
        assertNull(after.lines.first().words[2].endMs)
        assertEquals("Offset unresolved", after.lines.first().words[2].timingReason)
        assertEquals("Approximate timing", after.lines.first().timingLabel)
        assertEquals(100L, after.lines.first().supportedSeekMs)
        assertNull(after.lines[1].supportedSeekMs)
        assertNull(after.lines[1].startMs)
        val index = LyricLineTimingIndex(after.lines)
        assertEquals(0, index.activeAt(100))
        assertEquals(-1, index.activeAt(1000))
        assertEquals(2, index.activeAt(2000))
        assertEquals(-1, index.activeAt(2300))
        for (position in 0L..2500L) assertEquals(index.activeAt(position), findActiveLyricIndex(after.lines, position))
    }

    @Test
    fun estimatedLineCanFollowButCannotBecomeAPreciseSeek() {
        val document = fixture()
        val line = tracks(document).single().getJSONArray("lines").getJSONObject(2)
        line.getJSONObject("timing").put("status", "estimated").put("basis", "acoustic-proposal")
        validateEnrichmentContract(document, hasCover = false)
        val projected = projectEnrichmentLyricTracks(tracks(document)).single().lines
        assertEquals(2, LyricLineTimingIndex(projected).activeAt(2100))
        assertEquals(LyricTimingStatus.ESTIMATED, projected[2].timingStatus)
        assertEquals("Approximate timing", projected[2].timingLabel)
        assertNull(projected[2].supportedSeekMs)
        assertEquals(2300L, projected[2].endMs)
    }

    @Test
    fun malformedOrUnownedPerformedTimingRejects() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("contentStatus", "disputed") },
            { it.put("occurrenceStatus", "unknown") },
            { it.getJSONObject("timing").put("status", "ordinary") },
            { it.getJSONArray("words").getJSONObject(1).getJSONObject("timing").remove("basis") },
            { it.getJSONArray("words").getJSONObject(2).getJSONObject("timing").put("startMs", 600) },
            { it.getJSONArray("words").getJSONObject(1).getJSONObject("timing").put("extra", true) },
            { it.getJSONArray("words").remove(1) },
            { it.getJSONArray("words").getJSONObject(1).getJSONObject("timing").put("endMs", 1900) }
        )
        val prior = fixture().toString()
        mutations.forEach { mutate ->
            val candidate = JSONObject(prior)
            mutate(tracks(candidate).single().getJSONArray("lines").getJSONObject(0))
            val result = runCatching { validateEnrichmentContract(candidate, hasCover = false) }
            assertTrue(result.exceptionOrNull()?.message == "LYRICS_INVALID")
        }
    }
}
