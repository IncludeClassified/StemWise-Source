package app.stemwise.enrichment

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutation coverage for nested schema rules that org.json's coercing accessors must not weaken.
 * The byte-identical device fixture is used so these checks exercise the production validator.
 */
class EnrichmentNestedSchemaParityTest {
    @Test
    fun canonicalSchemaKeepsTheNestedConstraintsCoveredByRuntimeMutations() {
        val definitions = JSONObject(schemaFile().readText()).getJSONObject("\$defs")
        val albumProperties = definitions.getJSONObject("album").getJSONObject("properties")
        assertEquals("string", albumProperties.getJSONObject("releaseDate").getString("type"))
        assertEquals(
            "^[0-9]{4}(?:-[0-9]{2}(?:-[0-9]{2})?)?$",
            albumProperties.getJSONObject("releaseDate").getString("pattern")
        )
        listOf("trackNumber", "trackTotal", "discNumber", "discTotal").forEach { field ->
            assertEquals("integer", albumProperties.getJSONObject(field).getString("type"))
            assertEquals(1, albumProperties.getJSONObject(field).getInt("minimum"))
        }
        assertEquals("array", albumProperties.getJSONObject("artists").getString("type"))
        assertFalse(albumProperties.getJSONObject("artists").optBoolean("uniqueItems", false))
        val albumTypes = albumProperties.getJSONObject("type").getJSONArray("enum")
        assertEquals(
            setOf("album", "single", "ep", "compilation", "soundtrack", "other"),
            (0 until albumTypes.length()).map(albumTypes::getString).toSet()
        )

        val identifierPatterns = mapOf(
            "isrc" to "^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$",
            "musicBrainzRecordingId" to null,
            "musicBrainzReleaseId" to null,
            "discogsReleaseId" to "^[1-9][0-9]*$",
            "wikidataId" to "^Q[1-9][0-9]*$"
        )
        val identifierProperties = definitions.getJSONObject("identifiers")
            .getJSONObject("properties")
        identifierPatterns.forEach { (field, pattern) ->
            val declaration = identifierProperties.getJSONObject(field)
            assertEquals("string", declaration.getString("type"))
            if (pattern == null) {
                assertEquals("uuid", declaration.getString("format"))
            } else {
                assertEquals(pattern, declaration.getString("pattern"))
            }
        }

        val linkType = definitions.getJSONObject("link").getJSONObject("properties")
            .getJSONObject("type")
        assertEquals("string", linkType.getString("type"))
        assertEquals("^[a-z0-9][a-z0-9._-]{0,63}$", linkType.getString("pattern"))

        val metadataProperties = definitions.getJSONObject("metadata").getJSONObject("properties")
        assertTrue(metadataProperties.getJSONObject("artists").getBoolean("uniqueItems"))
        assertTrue(metadataProperties.getJSONObject("genres").getBoolean("uniqueItems"))
        assertTrue(
            definitions.getJSONObject("credit").getJSONObject("properties")
                .getJSONObject("roles").getBoolean("uniqueItems")
        )
        assertTrue(
            definitions.getJSONObject("rhythm").getJSONObject("properties")
                .getJSONObject("alternativeBpms").getBoolean("uniqueItems")
        )

        val componentLanguages = definitions.getJSONObject("lyricTrack")
            .getJSONObject("properties").getJSONObject("componentLanguages")
        assertEquals("array", componentLanguages.getString("type"))
        assertEquals(2, componentLanguages.getInt("minItems"))
        assertTrue(componentLanguages.getBoolean("uniqueItems"))
        assertEquals("string", componentLanguages.getJSONObject("items").getString("type"))
        assertEquals(2, componentLanguages.getJSONObject("items").getInt("minLength"))
        val lyricTrackRules = definitions.getJSONObject("lyricTrack").getJSONArray("allOf")
        assertTrue(
            (0 until lyricTrackRules.length()).any { index ->
                lyricTrackRules.getJSONObject(index)
                    .optJSONObject("else")
                    ?.optJSONObject("not")
                    ?.optJSONArray("required")
                    ?.let { required ->
                        (0 until required.length()).any { required.getString(it) == "componentLanguages" }
                    } == true
            }
        )
    }

    @Test
    fun runtimeRejectsAlbumCoercionsAndPatternDrift() {
        listOf("2026", "2026-07", "2026-07-29").forEach { releaseDate ->
            val accepted = fixture()
            accepted.metadata().getJSONObject("album").put("releaseDate", releaseDate)
            validateEnrichmentContract(accepted, hasCover = true)
        }

        listOf(
            Mutation("album releaseDate number") { json ->
                json.metadata().getJSONObject("album").put("releaseDate", 20260729)
            },
            Mutation("album releaseDate pattern") { json ->
                json.metadata().getJSONObject("album").put("releaseDate", "2026-7-29")
            },
            Mutation("album artists scalar") { json ->
                json.metadata().getJSONObject("album").put("artists", "Fixture Artist")
            },
            Mutation("blank album type") { json ->
                json.metadata().getJSONObject("album").put("type", "")
            },
            Mutation("non-string album type") { json ->
                json.metadata().getJSONObject("album").put("type", 1)
            }
        ).forEach { mutation -> assertRejected(mutation, "MANIFEST_JSON_INVALID") }

        listOf("trackNumber", "trackTotal", "discNumber", "discTotal").forEach { field ->
            listOf<Any>("1", 1.5).forEach { invalidValue ->
                assertRejected(
                    Mutation("$field rejects ${invalidValue.javaClass.simpleName}") { json ->
                        json.metadata().getJSONObject("album").put(field, invalidValue)
                    },
                    "MANIFEST_JSON_INVALID"
                )
            }
        }
    }

    @Test
    fun runtimeRejectsIdentifierAndLinkTypeDrift() {
        val invalidPatterns = mapOf(
            "isrc" to "usswi2600001",
            "musicBrainzRecordingId" to "not-a-uuid",
            "musicBrainzReleaseId" to "00000000-0000-4000-8000-000000000002-extra",
            "discogsReleaseId" to "0123456",
            "wikidataId" to "Q0"
        )
        invalidPatterns.forEach { (field, invalidValue) ->
            assertRejected(
                Mutation("$field pattern") { json ->
                    json.metadata().getJSONObject("identifiers").put(field, invalidValue)
                },
                "MANIFEST_JSON_INVALID"
            )
            assertRejected(
                Mutation("$field string type") { json ->
                    json.metadata().getJSONObject("identifiers").put(field, 123)
                },
                "MANIFEST_JSON_INVALID"
            )
        }

        listOf<Any>(123, "YouTube", "-youtube", "a".repeat(65)).forEach { invalidType ->
            assertRejected(
                Mutation("link type ${invalidType.toString().take(16)}") { json ->
                    json.metadata().getJSONArray("links").getJSONObject(0)
                        .put("type", invalidType)
                },
                "MANIFEST_JSON_INVALID"
            )
        }
    }

    @Test
    fun runtimeEnforcesCanonicalUniqueItemArrays() {
        val caseDistinctArtists = fixture()
        caseDistinctArtists.metadata().getJSONArray("artists").put("stemwise fixture artist")
        validateEnrichmentContract(caseDistinctArtists, hasCover = true)

        listOf(
            Mutation("metadata artists") { json ->
                json.metadata().getJSONArray("artists").apply { put(getString(0)) }
            },
            Mutation("metadata genres") { json ->
                json.metadata().getJSONArray("genres").apply { put(getString(0)) }
            },
            Mutation("credit roles") { json ->
                json.metadata().getJSONArray("credits").getJSONObject(0)
                    .getJSONArray("roles").apply { put(getString(0)) }
            }
        ).forEach { mutation -> assertRejected(mutation, "MANIFEST_JSON_INVALID") }

        assertRejected(
            Mutation("alternative BPMs") { json ->
                json.getJSONObject("analysis").getJSONObject("rhythm")
                    .getJSONArray("alternativeBpms").put(64)
            },
            "ANALYSIS_STATUS_INVALID"
        )
    }

    @Test
    fun runtimeEnforcesComponentLanguageArrayShape() {
        val originalControl = fixture()
        validateEnrichmentContract(originalControl, hasCover = true)

        listOf(
            Mutation("componentLanguages array type") { json ->
                json.originalLyrics().put("componentLanguages", "en,tr")
            },
            Mutation("componentLanguages minItems") { json ->
                json.originalLyrics().put("componentLanguages", JSONArray().put("en"))
            },
            Mutation("componentLanguages uniqueItems") { json ->
                json.originalLyrics().put(
                    "componentLanguages",
                    JSONArray().put("en").put("en")
                )
            },
            Mutation("componentLanguages item type") { json ->
                json.originalLyrics().put(
                    "componentLanguages",
                    JSONArray().put("en").put(7)
                )
            },
            Mutation("componentLanguages item minLength") { json ->
                json.originalLyrics().put(
                    "componentLanguages",
                    JSONArray().put("e").put("tr")
                )
            }
        ).forEach { mutation -> assertRejected(mutation, "LYRICS_INVALID") }

        listOf(1, 2).forEach { trackIndex ->
            assertRejected(
                Mutation("componentLanguages on non-original track $trackIndex") { json ->
                    json.getJSONObject("lyrics").getJSONArray("tracks")
                        .getJSONObject(trackIndex)
                        .put("componentLanguages", JSONArray().put("en").put("tr"))
                },
                "LYRICS_INVALID"
            )
        }
    }

    @Test
    fun runtimeRejectsMalformedOptionalLyricsStrings() {
        listOf(
            Mutation("numeric reason") { json ->
                json.getJSONObject("lyrics").put("reason", 5)
            },
            Mutation("blank script") { json ->
                json.originalLyrics().put("script", "")
            },
            Mutation("numeric script") { json ->
                json.originalLyrics().put("script", 5)
            }
        ).forEach { mutation -> assertRejected(mutation, "LYRICS_INVALID") }
    }

    private fun assertRejected(mutation: Mutation, expectedCode: String) {
        val document = fixture()
        mutation.apply(document)
        val failure = runCatching {
            validateEnrichmentContract(document, hasCover = true)
        }.exceptionOrNull()
        assertTrue(mutation.label, failure is EnrichmentImportException)
        assertEquals(
            mutation.label,
            expectedCode,
            (failure as EnrichmentImportException).code
        )
    }

    private fun fixture(): JSONObject = JSONObject(fixtureFile().readText())

    private fun JSONObject.metadata(): JSONObject = getJSONObject("metadata")

    private fun JSONObject.originalLyrics(): JSONObject = getJSONObject("lyrics")
        .getJSONArray("tracks").getJSONObject(0)

    private fun fixtureFile(): File = listOf(
        File("src/development/assets/qa/enrichment/full-song-enrichment-v2.json"),
        File("app/src/development/assets/qa/enrichment/full-song-enrichment-v2.json")
    ).first(File::isFile)

    private fun schemaFile(): File = listOf(
        File("../../StemWise/contracts/enrichment/song-enrichment-v2.schema.json"),
        File("../StemWise/contracts/enrichment/song-enrichment-v2.schema.json")
    ).first(File::isFile)

    private data class Mutation(
        val label: String,
        val apply: (JSONObject) -> Unit
    )
}
