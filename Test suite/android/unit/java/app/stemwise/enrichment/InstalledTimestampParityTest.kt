package app.stemwise.enrichment

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledTimestampParityTest {
    @Test
    fun `shared timestamp corpus has exact Android acceptance parity`() {
        val corpus = JSONObject(timestampCorpusFile().readText(Charsets.UTF_8))
        val positive = corpus.getJSONArray("positive")
        repeat(positive.length()) { index ->
            val value = positive.getString(index)
            assertTrue(value, isInstalledUtcTimestamp(value))
        }
        val negative = corpus.getJSONArray("negative")
        repeat(negative.length()) { index ->
            val testCase = negative.getJSONObject(index)
            assertFalse(
                testCase.getString("name"),
                isInstalledUtcTimestamp(testCase.get("value"))
            )
        }
    }

    @Test
    fun `installed schema exposes the same lexical timestamp boundary`() {
        val corpus = JSONObject(timestampCorpusFile().readText(Charsets.UTF_8))
        val schema = JSONObject(schemaFile().readText(Charsets.UTF_8))
        val generatedAt = schema.getJSONObject("properties").getJSONObject("generatedAt")
        val pattern = Regex(generatedAt.getString("pattern"))
        assertTrue(generatedAt.getString("format") == "date-time")
        val positive = corpus.getJSONArray("positive")
        repeat(positive.length()) { index ->
            assertTrue(pattern.matches(positive.getString(index)))
        }
        val negative = corpus.getJSONArray("negative")
        repeat(negative.length()) { index ->
            val testCase = negative.getJSONObject(index)
            if (
                testCase.getString("name") !in
                setOf(
                    "non-leap-century",
                    "non-leap-year",
                    "impossible-date",
                    "wrong-json-type"
                )
            ) {
                assertFalse(pattern.matches(testCase.getString("value")))
            }
        }
    }

    @Test
    fun `external runtime and schema share the installed timestamp corpus`() {
        val corpus = JSONObject(timestampCorpusFile().readText(Charsets.UTF_8))
        val installedGeneratedAt = JSONObject(schemaFile().readText(Charsets.UTF_8))
            .getJSONObject("properties")
            .getJSONObject("generatedAt")
        val externalGeneratedAt = JSONObject(externalSchemaFile().readText(Charsets.UTF_8))
            .getJSONObject("properties")
            .getJSONObject("generatedAt")
        assertEquals(installedGeneratedAt.toString(), externalGeneratedAt.toString())

        val knownGood = JSONObject(knownGoodFixtureFile().readText(Charsets.UTF_8))
        validateEnrichmentContract(JSONObject(knownGood.toString()), hasCover = true)

        val positive = corpus.getJSONArray("positive")
        repeat(positive.length()) { index ->
            val value = positive.getString(index)
            val payload = JSONObject(knownGood.toString()).put("generatedAt", value)
            validateEnrichmentContract(payload, hasCover = true)
        }
        val negative = corpus.getJSONArray("negative")
        repeat(negative.length()) { index ->
            val testCase = negative.getJSONObject(index)
            val payload = JSONObject(knownGood.toString())
                .put("generatedAt", testCase.get("value"))
            val failure = runCatching {
                validateEnrichmentContract(payload, hasCover = true)
            }.exceptionOrNull()
            assertTrue(testCase.getString("name"), failure is EnrichmentImportException)
            assertEquals(
                testCase.getString("name"),
                "MANIFEST_JSON_INVALID",
                (failure as EnrichmentImportException).code
            )
        }
    }

    private fun timestampCorpusFile(): File = listOf(
        File("../Test suite/android/fixtures/schemas/installed-enrichment-v2-timestamp-corpus.json"),
        File("../../Test suite/android/fixtures/schemas/installed-enrichment-v2-timestamp-corpus.json")
    ).firstOrNull(File::isFile) ?: error("TIMESTAMP_CORPUS_MISSING")

    private fun schemaFile(): File = listOf(
        File("../StemWise/contracts/enrichment/installed-enrichment-v2.schema.json"),
        File("../../StemWise/contracts/enrichment/installed-enrichment-v2.schema.json")
    ).firstOrNull(File::isFile) ?: error("INSTALLED_SCHEMA_MISSING")

    private fun externalSchemaFile(): File = listOf(
        File("../StemWise/contracts/enrichment/song-enrichment-v2.schema.json"),
        File("../../StemWise/contracts/enrichment/song-enrichment-v2.schema.json")
    ).firstOrNull(File::isFile) ?: error("EXTERNAL_SCHEMA_MISSING")

    private fun knownGoodFixtureFile(): File = listOf(
        File("app/src/development/assets/qa/enrichment/full-song-enrichment-v2.json"),
        File("src/development/assets/qa/enrichment/full-song-enrichment-v2.json")
    ).firstOrNull(File::isFile) ?: error("KNOWN_GOOD_ENRICHMENT_FIXTURE_MISSING")
}
