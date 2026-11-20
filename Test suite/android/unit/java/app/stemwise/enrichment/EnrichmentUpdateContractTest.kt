package app.stemwise.enrichment

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentUpdateContractTest {
    @Test
    fun duplicateCompleteSongsAreDeterministicAndConflictingAudioIsRejected() {
        val json = org.json.JSONObject().put("source", "same").put("generatedAt", "2026-07-29T10:01:00Z")
        val identity = enrichmentRevisionIdentity(json, "cover-hash")
        val first = EnrichmentBatchRevision(0, "a.zip", "abcdefghijk", "2026-07-29T10:01:00Z", identity,
            originalSha256 = "a".repeat(64))
        val copy = first.copy(index = 1, sourceName = "z.zip")
        for (order in listOf(listOf(first, copy), listOf(copy, first))) {
            val plan = planEnrichmentBatchRevisions(order)
            assertEquals(EnrichmentBatchRevisionDisposition.APPLY, plan[0])
            assertEquals(EnrichmentBatchRevisionDisposition.DUPLICATE, plan[1])
        }
        val differentAudio = first.copy(index = 2, originalSha256 = "b".repeat(64))
        assertEquals(mapOf(0 to EnrichmentBatchRevisionDisposition.CONFLICT,
            2 to EnrichmentBatchRevisionDisposition.CONFLICT),
            planEnrichmentBatchRevisions(listOf(first, differentAudio)))
    }

    @Test fun importPackageRequiresExactlyOneOriginal() {
        val base = setOf("song.json", "cover.png")
        assertEquals(null, classifyEnrichmentArchiveRoot(base))
        listOf("original.flac", "original.opus").forEach { original ->
            assertEquals(EnrichmentArchiveShape.SINGLE_SONG,
                classifyEnrichmentArchiveRoot(base + original))
        }
        assertEquals(null, classifyEnrichmentArchiveRoot(base + setOf("original.flac", "original.opus")))
        assertEquals(null, classifyEnrichmentArchiveRoot(base + "unrelated.flac"))
    }
    @Test
    fun packagedSchemaAllowsOneOrMoreGeneratedSectionsForUpdates() {
        val schemaFile = listOf(
            File("../../StemWise/contracts/enrichment/song-enrichment-v2.schema.json"),
            File("../StemWise/contracts/enrichment/song-enrichment-v2.schema.json")
        ).first(File::isFile)
        val schema = JSONObject(schemaFile.readText())
        val required = schema.getJSONArray("required").strings()
        assertEquals(
            setOf("formatVersion", "kind", "source", "generatedAt", "generator"),
            required
        )
        val generatedChoices = schema.getJSONArray("anyOf").objects()
            .map { it.getJSONArray("required").strings().single() }
            .toSet()
        assertEquals(setOf("metadata", "artwork", "lyrics", "analysis"), generatedChoices)
        assertEquals(
            setOf("file", "kind", "source"),
            schema.getJSONObject("\$defs")
                .getJSONObject("artwork")
                .getJSONArray("required")
                .strings()
        )
    }

    @Test
    fun archiveShapeIsDetectedFromContentsWithoutAUserMode() {
        assertEquals(null, classifyEnrichmentArchiveRoot(setOf("song.json")))
        assertEquals(
            null,
            classifyEnrichmentArchiveRoot(setOf("song.json", "cover.png"))
        )
        assertEquals(
            EnrichmentArchiveShape.BULK,
            classifyEnrichmentArchiveRoot(setOf("one.zip", "two.ZIP"))
        )
        assertEquals(null, classifyEnrichmentArchiveRoot(setOf("notes.txt")))
    }

    @Test
    fun revisionOrderingDistinguishesUnchangedNewerStaleAndConflict() {
        assertEquals(
            EnrichmentRevisionDecision.UNCHANGED,
            decideEnrichmentRevision(
                "enriched", "2026-07-29T10:00:00Z", "same",
                "2026-07-29T10:00:00Z", "same"
            )
        )
        assertEquals(
            EnrichmentRevisionDecision.APPLY,
            decideEnrichmentRevision(
                "enriched", "2026-07-29T10:00:00Z", "old",
                "2026-07-29T10:01:00Z", "new"
            )
        )
        assertEquals(
            EnrichmentRevisionDecision.STALE,
            decideEnrichmentRevision(
                "enriched", "2026-07-29T10:01:00Z", "new",
                "2026-07-29T10:00:00Z", "old"
            )
        )
        assertEquals(
            EnrichmentRevisionDecision.CONFLICT,
            decideEnrichmentRevision(
                "enriched", "2026-07-29T10:00:00Z", "one",
                "2026-07-29T10:00:00Z", "two"
            )
        )
    }

    @Test
    fun actualBatchPlanAppliesOnlyTheNewestCompleteBundleInEitherPickerOrder() {
        val older = EnrichmentBatchRevision(
            0, "older.zip", "abcdefghijk", "2026-07-29T10:01:00Z", "older"
        )
        val newer = EnrichmentBatchRevision(
            1, "newer.zip", "abcdefghijk", "2026-07-29T10:02:00Z", "newer"
        )
        assertEquals(
            mapOf(
                0 to EnrichmentBatchRevisionDisposition.SUPERSEDED,
                1 to EnrichmentBatchRevisionDisposition.APPLY
            ),
            planEnrichmentBatchRevisions(listOf(older, newer))
        )
        assertEquals(
            mapOf(
                1 to EnrichmentBatchRevisionDisposition.APPLY,
                0 to EnrichmentBatchRevisionDisposition.SUPERSEDED
            ),
            planEnrichmentBatchRevisions(listOf(newer, older))
        )
        val dispositions = planEnrichmentBatchRevisions(listOf(newer, older))
        assertEquals(
            listOf(1),
            enrichmentBatchApplicationOrder(listOf(newer, older), dispositions)
        )
    }

    @Test
    fun actualBatchPlanRejectsSameTimestampDifferentDigests() {
        val first = EnrichmentBatchRevision(
            0, "a.zip", "abcdefghijk", "2026-07-29T10:02:00Z", "digest-a"
        )
        val second = EnrichmentBatchRevision(
            1, "b.zip", "abcdefghijk", "2026-07-29T10:02:00Z", "digest-b"
        )
        val expected = mapOf(0 to EnrichmentBatchRevisionDisposition.CONFLICT,
            1 to EnrichmentBatchRevisionDisposition.CONFLICT)
        val actual = planEnrichmentBatchRevisions(listOf(first, second))
        assertEquals(expected, actual)
        assertThrows(AssertionError::class.java) { assertEquals(expected, emptyMap<Int, EnrichmentBatchRevisionDisposition>()) }
        assertThrows(AssertionError::class.java) { assertEquals(expected, actual - 1) }
    }

    @Test
    fun olderConflictsAreSupersededAndExactWinningDuplicatesCollapse() {
        val first = EnrichmentBatchRevision(
            0, "a.zip", "abcdefghijk", "2026-07-29T10:01:00Z", "digest-a"
        )
        val conflicting = EnrichmentBatchRevision(
            1, "b.zip", "abcdefghijk", "2026-07-29T10:01:00Z", "digest-b"
        )
        val later = EnrichmentBatchRevision(
            2, "c.zip", "abcdefghijk", "2026-07-29T10:02:00Z", "digest-c"
        )
        val plan = planEnrichmentBatchRevisions(listOf(later, first, conflicting))
        assertEquals(mapOf(2 to EnrichmentBatchRevisionDisposition.APPLY,
            0 to EnrichmentBatchRevisionDisposition.SUPERSEDED, 1 to EnrichmentBatchRevisionDisposition.SUPERSEDED), plan)

        val duplicate = first.copy(index = 3, sourceName = "copy.zip")
        val duplicatePlan = planEnrichmentBatchRevisions(listOf(duplicate, first))
        assertEquals(EnrichmentBatchRevisionDisposition.APPLY, duplicatePlan[0])
        assertEquals(EnrichmentBatchRevisionDisposition.DUPLICATE, duplicatePlan[3])
    }

    @Test
    fun equivalentInstantsSupersedeOldConflictsAndCollapseWinningDuplicates() {
        val first = EnrichmentBatchRevision(0, "a.zip", "abcdefghijk", "2026-07-29T10:01:00Z", "a")
        val later = first.copy(index = 2, generatedAt = "2026-07-29T10:02:00Z", inputSha256 = "later")
        listOf(".0Z", ".000Z", ".000000000Z").forEach { suffix ->
            val equivalent = first.copy(index = 1, sourceName = "b.zip",
                generatedAt = "2026-07-29T10:01:00$suffix")
            val conflicts = planEnrichmentBatchRevisions(listOf(later, first,
                equivalent.copy(inputSha256 = "conflicting")))
            assertEquals(mapOf(2 to EnrichmentBatchRevisionDisposition.APPLY,
                0 to EnrichmentBatchRevisionDisposition.SUPERSEDED, 1 to EnrichmentBatchRevisionDisposition.SUPERSEDED), conflicts)
            val duplicates = planEnrichmentBatchRevisions(listOf(equivalent, first))
            assertEquals(EnrichmentBatchRevisionDisposition.APPLY, duplicates[0])
            assertEquals(EnrichmentBatchRevisionDisposition.DUPLICATE, duplicates[1])
        }
    }

    @Test
    fun nullOrWrongTypeGeneratedSectionsAreNeverAcceptedAsUpdates() {
        listOf(JSONObject.NULL, "metadata", JSONArray()).forEach { invalid ->
            val failure = runCatching {
                enrichmentGeneratedSections(JSONObject().put("metadata", invalid))
            }.exceptionOrNull()
            assertTrue(failure is EnrichmentImportException)
            assertEquals("MANIFEST_JSON_INVALID", (failure as EnrichmentImportException).code)
        }
        val valid = enrichmentGeneratedSections(
            JSONObject().put("metadata", JSONObject().put("title", "Valid"))
        )
        assertEquals(setOf("metadata"), valid.keys)
    }

    @Test
    fun resultUsesOneCanonicalSuccessContract() {
        val result = EnrichmentImportResult(
            listOf(
                EnrichmentImportItem("one", "p1", "imported"),
                EnrichmentImportItem("two", "p2", "updated"),
                EnrichmentImportItem("three", "p3", "unchanged"),
                EnrichmentImportItem("four", null, "invalid")
            )
        )
        assertEquals(2, result.importedCount)
        assertEquals(1, result.updatedCount)
        assertEquals(1, result.unchangedCount)
        assertEquals(1, result.failedCount)
        assertTrue(result.items.take(3).all(EnrichmentImportItem::isSuccess))
        assertFalse(result.items.last().isSuccess)
    }

    @Test
    fun duplicateSelectionIsUnchangedOnlyAfterItsRepresentativeSucceeds() {
        val applied = duplicateEnrichmentImportItem(
            videoId = "abcdefghijk",
            sourceName = "copy.zip",
            representative = EnrichmentImportItem(
                videoId = "abcdefghijk",
                packageId = "package-one",
                status = "updated",
                sourceName = "original.zip"
            )
        )
        assertEquals(EnrichmentImportOutcome.UNCHANGED, applied.outcome)
        assertEquals("package-one", applied.packageId)
        assertEquals("copy.zip", applied.sourceName)

        val unmatched = duplicateEnrichmentImportItem(
            videoId = "abcdefghijk",
            sourceName = "copy.zip",
            representative = EnrichmentImportItem(
                videoId = "abcdefghijk",
                packageId = null,
                status = "unmatched",
                errorCode = "SONG_NOT_FOUND",
                sourceName = "original.zip"
            )
        )
        assertEquals(EnrichmentImportOutcome.UNMATCHED, unmatched.outcome)
        assertEquals("SONG_NOT_FOUND", unmatched.errorCode)
        assertEquals("copy.zip", unmatched.sourceName)

        val failed = duplicateEnrichmentImportItem(
            videoId = "abcdefghijk",
            sourceName = "copy.zip",
            representative = EnrichmentImportItem(
                videoId = "abcdefghijk",
                packageId = null,
                status = "invalid",
                errorCode = "EXTERNAL_COMMIT_FAILED",
                sourceName = "original.zip"
            )
        )
        assertEquals(EnrichmentImportOutcome.INVALID, failed.outcome)
        assertEquals("EXTERNAL_COMMIT_FAILED", failed.errorCode)

        val missing = duplicateEnrichmentImportItem(
            videoId = "abcdefghijk",
            sourceName = "copy.zip",
            representative = null
        )
        assertEquals(EnrichmentImportOutcome.INVALID, missing.outcome)
        assertEquals("ENRICHMENT_DUPLICATE_REPRESENTATIVE_MISSING", missing.errorCode)
    }

    private fun JSONArray.strings(): Set<String> = buildSet {
        repeat(length()) { index -> add(getString(index)) }
    }

    private fun JSONArray.objects(): List<JSONObject> = buildList {
        repeat(length()) { index -> add(getJSONObject(index)) }
    }

}
