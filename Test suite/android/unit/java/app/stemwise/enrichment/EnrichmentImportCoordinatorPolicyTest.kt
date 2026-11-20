package app.stemwise.enrichment

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentImportCoordinatorPolicyTest {
    @Test
    fun failedStartupCanBeginAgainAndDrainCommandsOnlyAfterRecoverySucceeds() {
        val queue = StartupCommandQueue()
        val calls = mutableListOf<String>()
        queue.complete(false) { it() }
        queue.submit({ calls += "failed" }) { it() }
        assertTrue(queue.failed)
        queue.begin()
        queue.submit({ calls += "recovered" }) { it() }
        assertTrue(calls.isEmpty())
        queue.complete(true) { it() }
        assertEquals(listOf("recovered"), calls)
        assertFalse(queue.failed)
    }

    @Test
    fun fullyStagedBatchSurvivesValidationAndPartialApplicationProgress() {
        for (phase in listOf("Validating import", "Applying song", "Applying enrichment")) {
            assertTrue(enrichmentStagingJournalIsComplete(JSONObject()
                .put("stagingComplete", true).put("phase", phase)
                .put("completed", 1L).put("total", 2L), 2))
        }
        assertFalse(enrichmentStagingJournalIsComplete(JSONObject()
            .put("stagingComplete", false).put("phase", "staged"), 2))
    }

    @Test
    fun stagingCompletenessIsStrictAndIndependentOfProgress() {
        for (value in listOf(false, 1L, "true", JSONObject.NULL)) {
            assertFalse(enrichmentStagingJournalIsComplete(JSONObject()
                .put("stagingComplete", value).put("phase", "staged"), 3))
        }
        assertFalse(enrichmentStagingJournalIsComplete(JSONObject().put("stagingComplete", true), 0))
        assertFalse(enrichmentStagingJournalIsComplete(JSONObject().put("stagingComplete", true), 513))
        assertTrue(enrichmentStagingJournalIsComplete(JSONObject().put("stagingComplete", true), 512))
    }

    @Test
    fun stagingNamesUseOneExactNamespaceWithoutGrantingPathAuthority() {
        val uuid = "00000000-0000-4000-8000-000000000001"
        assertTrue(isEnrichmentStagingName(ENRICHMENT_STAGING_JOURNAL_NAME))
        for (extension in listOf("part", "json", "zip")) {
            assertTrue(isEnrichmentStagingName("$uuid-0.$extension"))
            assertTrue(isEnrichmentStagingName("$uuid-123.$extension"))
        }
        for (name in listOf("$uuid.json", "$uuid--1.zip", "$uuid-1.png", "$uuid-x.zip",
            "$uuid-1.ZIP", "00000000-0000-0000-0000-000000000001-1.zip",
            "../$uuid-1.zip", "$uuid-1.zip/child", "other-journal.json")) {
            assertFalse(name, isEnrichmentStagingName(name))
        }
    }

    @Test
    fun commandsAdmittedDuringStartupDrainStayBehindTheExistingQueue() {
        val queue = StartupCommandQueue()
        val order = mutableListOf<String>()
        lateinit var dispatch: (() -> Unit) -> Unit
        dispatch = { it() }
        queue.submit(
            command = {
                order += "first-start"
                queue.submit({ order += "third" }, dispatch)
                order += "first-end"
            },
            dispatch = dispatch
        )
        queue.submit({ order += "second" }, dispatch)

        queue.complete(isAvailable = true, dispatch = dispatch)
        queue.submit({ order += "fourth" }, dispatch)

        assertEquals(
            listOf("first-start", "first-end", "second", "third", "fourth"),
            order
        )
    }

    @Test
    fun taskRemovalDrainIsGenerationBoundAndFifo() {
        val queue = TaskRemovalCommandQueue()
        val order = mutableListOf<String>()
        lateinit var dispatch: (() -> Unit) -> Unit
        dispatch = { it() }
        val firstOwner = queue.begin()
        queue.submit({
            order += "first"
            queue.submit({ order += "third" }, dispatch)
        }, dispatch)
        queue.submit({ order += "second" }, dispatch)

        queue.complete(firstOwner, success = true, dispatch = dispatch)
        queue.submit({ order += "fourth" }, dispatch)
        assertEquals(listOf("first", "second", "third", "fourth"), order)

        val staleOwner = queue.begin()
        queue.submit({ order += "stale" }, dispatch)
        val currentOwner = queue.begin()
        queue.complete(staleOwner, success = true, dispatch = dispatch)
        queue.submit({ order += "failed" }, dispatch)
        queue.complete(currentOwner, success = false, dispatch = dispatch)
        queue.submit({ order += "after-failure" }, dispatch)
        assertEquals(listOf("first", "second", "third", "fourth"), order)
        val retryOwner = queue.retry()
        queue.complete(currentOwner, success = true, dispatch = dispatch)
        assertEquals(listOf("first", "second", "third", "fourth"), order)
        queue.complete(retryOwner, success = true, dispatch = dispatch)
        assertEquals(listOf("first", "second", "third", "fourth", "failed", "after-failure"), order)
    }

    @Test
    fun stagedSessionAuthorityIsExactAndLegacyRootlessJournalsFailClosed() {
        val root = decodeEnrichmentImportJournalRoot(
            JSONObject()
                .put("schemaVersion", 3)
                .put("libraryId", "library-a")
                .put("treeUri", "content://provider/tree/a")
                .put("rootEpoch", 7L)
        )

        assertEquals("library-a", root?.libraryId)
        assertEquals("content://provider/tree/a", root?.treeUri)
        assertEquals(7L, root?.epoch)
        assertNull(
            decodeEnrichmentImportJournalRoot(
                JSONObject().put("schemaVersion", 2).put("libraryId", "library-a")
            )
        )
        listOf(
            JSONObject()
                .put("schemaVersion", "3")
                .put("libraryId", "library-a")
                .put("treeUri", "content://provider/tree/a")
                .put("rootEpoch", 7L),
            JSONObject()
                .put("schemaVersion", 3)
                .put("libraryId", 123)
                .put("treeUri", "content://provider/tree/a")
                .put("rootEpoch", 7L),
            JSONObject()
                .put("schemaVersion", 3)
                .put("libraryId", "library-a")
                .put("treeUri", 123)
                .put("rootEpoch", 7L),
            JSONObject()
                .put("schemaVersion", 3)
                .put("libraryId", "library-a")
                .put("treeUri", "content://provider/tree/a")
                .put("rootEpoch", "7")
        ).forEach { malformed ->
            assertNull(decodeEnrichmentImportJournalRoot(malformed))
        }
    }

    @Test
    fun futureStagingJournalBlocksStartupAndCleanupInsteadOfBeingDowngraded() {
        assertFalse(
            enrichmentStagingJournalIsFuture(JSONObject().put("schemaVersion", 3L))
        )
        assertTrue(
            enrichmentStagingJournalIsFuture(JSONObject().put("schemaVersion", 4L))
        )
        assertFalse(
            enrichmentStagingJournalIsFuture(JSONObject().put("schemaVersion", "4"))
        )


    }

    @Test
    fun copyProgressCannotAuthorizeAnIncompleteBatch() {
        for (phase in listOf("copying", "staged", "awaiting_confirmation")) {
            assertFalse(enrichmentStagingJournalIsComplete(JSONObject()
                .put("phase", phase).put("completed", 3L).put("total", 3L), 3))
            assertFalse(enrichmentStagingJournalIsComplete(JSONObject()
                .put("stagingComplete", false).put("phase", phase)
                .put("completed", 3L).put("total", 3L), 3))
        }
    }
}
