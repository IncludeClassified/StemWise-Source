package app.stemwise.enrichment

import app.stemwise.data.CanonicalJson
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.projectEnrichmentLyricTracks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEnrichmentV2ContractTest {
    @Test
    fun sharedPcCompanionContractCorpus() {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "fixtures/enrichment/companion-contract-v1-corpus.json"
        ))
        val cases = resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            .getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val document = enrichmentDocumentWithSyncedTranslation()
            val lyrics = document.getJSONObject("lyrics")
            lyrics.put("status", "partial").put("reason", "Missing IPA.")
            lyrics.remove("tokenizationVersion")
            val original = lyrics.getJSONArray("tracks").getJSONObject(0)
            original.put("language", "tr").put("format", case.getString("originalFormat"))
            if (case.getString("originalFormat") == "plain") {
                original.remove("lines")
                original.put("text", "Merhaba")
            } else {
                firstLine(document).remove("words")
            }
            val companion = lyrics.getJSONArray("tracks").getJSONObject(1)
            companion.put("kind", case.optString("kind", "translation"))
            companion.put("language", case.getString("language"))
                .put("format", case.getString("companionFormat"))
            if (case.getString("companionFormat") == "plain") {
                companion.remove("lines")
                companion.put("text", if (companion.getString("kind") == "transliteration") "salam" else "Hello")
            } else {
                translationLines(document).getJSONObject(0).put("endMs", case.getInt("endMs"))
            }
            val failure = runCatching { validateEnrichmentContract(document, hasCover = false) }
                .exceptionOrNull()
            assertEquals(case.getString("id"), case.getBoolean("valid"), failure == null)
            if (!case.getBoolean("valid")) assertEquals("LYRICS_INVALID", failure?.message)
        }
    }

    @Test
    fun sharedCorpusDefinesTheExactVersionedUtf16Ranges() {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "fixtures/enrichment/lyrics-tokenization-v2-corpus.json"
        ))
        val corpus = resource.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals(LYRICS_TOKENIZATION_VERSION, corpus.getString("tokenizationVersion"))
        val cases = corpus.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = case.getJSONArray("ranges").let { ranges ->
                (0 until ranges.length()).map { rangeIndex ->
                    ranges.getJSONArray(rangeIndex).let { range ->
                        Utf16WordRange(range.getInt(0), range.getInt(1))
                    }
                }
            }
            assertEquals(case.getString("id"), expected, displayWordUtf16Ranges(case.getString("text")))
        }
        val negativeCases = corpus.getJSONArray("negativeCases")
        for (index in 0 until negativeCases.length()) {
            val case = negativeCases.getJSONObject(index)
            val invalid = case.getJSONArray("ranges").let { ranges ->
                (0 until ranges.length()).map { rangeIndex ->
                    ranges.getJSONArray(rangeIndex).let { range ->
                        Utf16WordRange(range.getInt(0), range.getInt(1))
                    }
                }
            }
            assertTrue(
                case.getString("id"),
                invalid != displayWordUtf16Ranges(case.getString("text"))
            )
        }
    }

    @Test
    fun strictV2AcceptsCompleteWordsAndRejectsV1() {
        validateEnrichmentContract(enrichmentDocument(), hasCover = false)
        val v1 = JSONObject(enrichmentDocument().toString()).put("formatVersion", 1)
        assertContractFailure("SCHEMA_UNSUPPORTED", v1)

        val tiedStarts = enrichmentDocument().also { document ->
            firstTrackLines(document).put(
                JSONObject().put("startMs", 100).put("endMs", 800).put("text", "tie")
            )
        }
        validateEnrichmentContract(tiedStarts, hasCover = false)
        firstTrackLines(tiedStarts).getJSONObject(1).put("startMs", 99)
        assertContractFailure("LYRICS_INVALID", tiedStarts)
    }

    @Test
    fun generatorIdentityMustMatchTheCurrentPcRelease() {
        val wrongName = enrichmentDocument().also {
            it.getJSONObject("generator").put("name", "another-pc")
        }
        assertContractFailure("SCHEMA_UNSUPPORTED", wrongName)

        val wrongVersion = enrichmentDocument().also {
            it.getJSONObject("generator").put("version", "2.0.1")
        }
        assertContractFailure("SCHEMA_UNSUPPORTED", wrongVersion)
    }

    @Test
    fun syncedLinesRequireEndsAndWordsRequireTheExactTokenizationContract() {
        val missingEnd = enrichmentDocument().also { document ->
            firstLine(document).remove("endMs")
        }
        assertContractFailure("LYRICS_INVALID", missingEnd)

        val missingTokenization = enrichmentDocument().also { document ->
            document.getJSONObject("lyrics").remove("tokenizationVersion")
        }
        assertContractFailure("LYRICS_INVALID", missingTokenization)

        val partialCoverage = enrichmentDocument().also { document ->
            firstLine(document).getJSONArray("words").remove(1)
        }
        assertContractFailure("LYRICS_INVALID", partialCoverage)

        val publicPrecision = enrichmentDocument().also { document ->
            firstLine(document).put("precision", "word")
        }
        assertContractFailure("LYRICS_INVALID", publicPrecision)

        val lineFallback = enrichmentDocument().also { document ->
            firstLine(document).remove("words")
            document.getJSONObject("lyrics").remove("tokenizationVersion")
        }
        validateEnrichmentContract(lineFallback, hasCover = false)
    }

    @Test
    fun wordArraysAreOriginalOnlyAndRoomRoundTripKeepsAllCoordinates() {
        val translationWords = enrichmentDocument().also { document ->
            val tracks = document.getJSONObject("lyrics").getJSONArray("tracks")
            tracks.put(
                JSONObject(tracks.getJSONObject(0).toString())
                    .put("id", "translation")
                    .put("kind", "translation")
                    .put("basedOnTrackId", "original")
            )
        }
        assertContractFailure("LYRICS_INVALID", translationWords)

        val tracksJson = enrichmentDocument().getJSONObject("lyrics").getJSONArray("tracks")
        val projected = projectEnrichmentLyricTracks(listOf(tracksJson.getJSONObject(0)))
        val restored = projectEnrichmentLyricTracks((0 until tracksJson.length()).map(tracksJson::getJSONObject))
        assertEquals(projected, restored)
        assertEquals(2, restored.single().lines.single().words.size)
        assertEquals(3, restored.single().lines.single().words[1].startUtf16)
    }

    @Test
    fun syncedCompanionRejectsMismatchedOccurrenceCount() {
        val document = enrichmentDocumentWithSyncedTranslation()
        validateEnrichmentContract(document, hasCover = false)
        translationLines(document).put(
            JSONObject().put("startMs", 1_000).put("endMs", 1_200).put("text", "extra")
        )

        assertContractFailure("LYRICS_INVALID", document)
    }

    @Test
    fun syncedCompanionRejectsMismatchedOccurrenceInterval() {
        val document = enrichmentDocumentWithSyncedTranslation()
        validateEnrichmentContract(document, hasCover = false)
        translationLines(document).getJSONObject(0).put("startMs", 101)

        assertContractFailure("LYRICS_INVALID", document)
    }

    @Test
    fun ownerLyricsRequireExactlyOneOriginalTrack() {
        val document = enrichmentDocument()
        val tracks = document.getJSONObject("lyrics").getJSONArray("tracks")
        tracks.put(JSONObject(tracks.getJSONObject(0).toString()).put("id", "second-original"))

        assertContractFailure("LYRICS_INVALID", document)
    }

    @Test
    fun installedV2PreservesTheValidatedV2LyricsTreeSemantically() {
        val incoming = enrichmentDocument()
        val installed = installedDocument(incoming)
        assertEquals(2, installed.getInt("schemaVersion"))
        assertEquals(
            CanonicalJson.sha256(incoming.getJSONObject("lyrics")),
            CanonicalJson.sha256(installed.getJSONObject("lyrics"))
        )
    }

    @Test
    fun installedV2RejectsGeneratorIdentityDrift() {
        val installed = installedDocument(enrichmentDocument())
        val originalSha = "a".repeat(64)
        validateInstalledEnrichmentDocument(installed, "abcdefghijk", originalSha)

        listOf("name" to "another-pc", "version" to "2.0.1").forEach { (key, value) ->
            val invalid = JSONObject(installed.toString())
            invalid.getJSONObject("generator").put(key, value)
            val failure = runCatching {
                validateInstalledEnrichmentDocument(invalid, "abcdefghijk", originalSha)
            }.exceptionOrNull()
            assertEquals("SCHEMA_UNSUPPORTED", failure?.message)
        }
    }

    private fun installedDocument(incoming: JSONObject): JSONObject {
        val originalSha = "a".repeat(64)
        return createInstalledEnrichmentDocument(
            incoming = incoming,
            originalSha256 = originalSha,
            media = mapOf(
                SelectedLibraryContract.ORIGINAL to InstalledMediaIdentity(originalSha, 1L)
            ),
            contentRevision = 1L,
            payloadSha256 = "b".repeat(64),
            artwork = JSONObject()
                .put("kind", "album-cover")
                .put("source", JSONObject().put("name", "fixture")),
            artworkSha256 = "c".repeat(64),
            artworkBytes = 1
        )
    }

    private fun enrichmentDocument(): JSONObject = JSONObject()
        .put("formatVersion", ENRICHMENT_FORMAT_VERSION)
        .put("kind", "stemwise.song-enrichment")
        .put("source", JSONObject().put("provider", "youtube").put("videoId", "abcdefghijk"))
        .put("generatedAt", "2026-08-23T10:00:00Z")
        .put(
            "generator",
            JSONObject()
                .put("name", ENRICHMENT_GENERATOR_NAME)
                .put("version", ENRICHMENT_GENERATOR_VERSION)
        )
        .put(
            "lyrics",
            JSONObject()
                .put("status", "complete")
                .put("tokenizationVersion", LYRICS_TOKENIZATION_VERSION)
                .put(
                    "tracks",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "original")
                            .put("kind", "original")
                            .put("language", "en")
                            .put("format", "synced")
                            .put("source", JSONObject().put("name", "fixture"))
                            .put(
                                "lines",
                                JSONArray().put(
                                    JSONObject()
                                        .put("startMs", 100)
                                        .put("endMs", 1_000)
                                        .put("text", "go now")
                                        .put(
                                            "words",
                                            JSONArray()
                                                .put(word(0, 2, 100, 600))
                                                .put(word(3, 6, 500, 1_000))
                                        )
                                )
                            )
                    )
                )
        )

    private fun enrichmentDocumentWithSyncedTranslation(): JSONObject =
        enrichmentDocument().also { document ->
            document.getJSONObject("lyrics").getJSONArray("tracks").put(
                JSONObject()
                    .put("id", "translation")
                    .put("kind", "translation")
                    .put("language", "en")
                    .put("format", "synced")
                    .put("source", JSONObject().put("name", "fixture"))
                    .put("basedOnTrackId", "original")
                    .put(
                        "lines",
                        JSONArray().put(
                            JSONObject()
                                .put("startMs", 100)
                                .put("endMs", 1_000)
                                .put("text", "Go now")
                        )
                    )
            )
        }

    private fun word(
        startUtf16: Int,
        endUtf16: Int,
        startMs: Int,
        endMs: Int
    ): JSONObject = JSONObject()
        .put("startUtf16", startUtf16)
        .put("endUtf16", endUtf16)
        .put("startMs", startMs)
        .put("endMs", endMs)

    private fun firstLine(document: JSONObject): JSONObject =
        firstTrackLines(document).getJSONObject(0)

    private fun firstTrackLines(document: JSONObject): JSONArray = document
        .getJSONObject("lyrics")
        .getJSONArray("tracks")
        .getJSONObject(0)
        .getJSONArray("lines")

    private fun translationLines(document: JSONObject): JSONArray = document
        .getJSONObject("lyrics")
        .getJSONArray("tracks")
        .getJSONObject(1)
        .getJSONArray("lines")

    private fun assertContractFailure(code: String, document: JSONObject) {
        val error = runCatching {
            validateEnrichmentContract(document, hasCover = false)
        }.exceptionOrNull()
        assertTrue(error is EnrichmentImportException)
        assertEquals(code, (error as EnrichmentImportException).code)
    }
}
