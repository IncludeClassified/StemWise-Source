package app.stemwise.bridge

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeModelOptionsTest {
    @Test fun catalogUsesWireModelIdentityAndOnlyAdvertisedCapabilities() = runBlocking {
        val catalog = fetchBridgeModelCatalog { method, path, body ->
            assertEquals("GET", method)
            assertEquals("/v2/codex/models", path)
            assertNull(body)
            page(model())
        }
        assertEquals(listOf(BridgeModelOption("wire-model", "Visible model", listOf("low", "high"), "high",
            listOf(BridgeServiceTier("regular", "Regular speed", "Normal processing"),
                BridgeServiceTier("express", "Express speed", "Faster processing")), "regular", true)), catalog.models)
        catalog.validate(submission())
        for (invalid in listOf(submission().copy(model = "catalog-row"), submission().copy(model = "Visible model"),
            submission().copy(effort = "medium"), submission().copy(effort = "HIGH"),
            submission().copy(serviceTier = "fast"), submission().copy(serviceTier = " "),
            submission().copy(link = "https://example.com/watch?v=abcdefghijk"))) {
            rejected { catalog.validate(invalid) }
        }
    }

    @Test fun nativeDefaultIsExplicitEvenWhenOnlyFastIsAdvertised() = runBlocking {
        val row = model().put("serviceTiers", JSONArray().put(JSONObject().put("id", "priority")
            .put("name", "Fast").put("description", "Faster processing")))
            .put("defaultServiceTier", JSONObject.NULL)
        val catalog = fetchBridgeModelCatalog { _, _, _ -> page(row) }
        catalog.validate(submission().copy(serviceTier = ""))
        catalog.validate(submission().copy(serviceTier = "priority"))
        for (invalid in listOf("standard", "fast", " ")) {
            rejected { catalog.validate(submission().copy(serviceTier = invalid)) }
        }
    }

    @Test fun paginationEncodesCursorAndDoesNotOfferHiddenModels() = runBlocking {
        val paths = mutableListOf<String>()
        val catalog = fetchBridgeModelCatalog { _, path, _ ->
            paths += path
            when (paths.size) {
                1 -> page(model().put("hidden", true), cursor = "next /+?&=")
                else -> page(model("second").put("displayName", "Second model").put("isDefault", false))
            }
        }
        assertEquals(listOf("/v2/codex/models", "/v2/codex/models?cursor=next+%2F%2B%3F%26%3D"), paths)
        assertEquals(listOf("second"), catalog.models.map { it.id })
        assertFalse(catalog.models.single().isDefault)
        rejected { catalog.validate(submission()) }
    }

    @Test fun absentAndNullSpeedsAllowOnlyExplicitNativeDefault() = runBlocking {
        for (tiers in listOf<Any?>(null, JSONObject.NULL, JSONArray())) {
            val row = model().put("defaultServiceTier", JSONObject.NULL)
            if (tiers == null) row.remove("serviceTiers") else row.put("serviceTiers", tiers)
            val catalog = fetchBridgeModelCatalog { _, _, _ -> page(row) }
            assertEquals(emptyList<BridgeServiceTier>(), catalog.models.single().serviceTiers)
            catalog.validate(submission().copy(serviceTier = ""))
            for (speed in listOf("regular", "standard", "fast")) {
                rejected { catalog.validate(submission().copy(serviceTier = speed)) }
            }
        }
    }

    @Test fun optionalDefaultsNeverInventOptions() = runBlocking {
        val row = model().also {
            it.remove("displayName")
            it.remove("defaultReasoningEffort")
            it.remove("defaultServiceTier")
            it.remove("isDefault")
        }
        val catalog = fetchBridgeModelCatalog { _, _, _ -> page(row) }
        val option = catalog.models.single()
        assertEquals("wire-model", option.label)
        assertNull(option.defaultEffort)
        assertNull(option.defaultServiceTier)
        assertFalse(option.isDefault)
        catalog.validate(submission())
    }

    @Test fun unknownSpeedDefaultIsOnlyContextAndDoesNotHideOtherModels() = runBlocking {
        for (tiers in listOf<Any?>(null, JSONObject.NULL)) {
            val unknown = model().put("defaultServiceTier", "context-only")
            if (tiers == null) unknown.remove("serviceTiers") else unknown.put("serviceTiers", tiers)
            val catalog = fetchBridgeModelCatalog { _, _, _ -> page(unknown, model("available")) }
            assertEquals(listOf("wire-model", "available"), catalog.models.map { it.id })
            assertEquals(emptyList<BridgeServiceTier>(), catalog.models.first().serviceTiers)
            assertNull(catalog.models.first().defaultServiceTier)
            catalog.validate(submission().copy(serviceTier = ""))
            for (speed in listOf("context-only", "regular")) {
                rejected { catalog.validate(submission().copy(serviceTier = speed)) }
            }
            catalog.validate(submission().copy(model = "available"))
        }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model().put("serviceTiers", JSONArray())) } }
    }

    @Test fun malformedCatalogFieldsAndUnadvertisedDefaultsFailClosed() = runBlocking {
        val malformed = listOf(
            model().put("model", 7), model().put("id", ""), model().put("hidden", "false"),
            model().put("isDefault", "true"), model().put("displayName", 4),
            model().put("supportedReasoningEfforts", "high"),
            model().put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", 2))),
            model().put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "high")
                .put("description", 9))),
            model().put("serviceTiers", "fast"),
            model().put("serviceTiers", JSONArray().put(JSONObject().put("id", "regular").put("name", "Regular"))),
            model().put("defaultReasoningEffort", "unknown"), model().put("defaultServiceTier", "unknown")
        )
        for (row in malformed) rejected { fetchBridgeModelCatalog { _, _, _ -> page(row) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> JSONObject().put("data", JSONArray()) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> JSONObject().put("data", "models").put("nextCursor", JSONObject.NULL) } }
    }

    @Test fun unknownEffortDefaultIsOnlyContextAndDoesNotHideOtherModels() = runBlocking {
        for (efforts in listOf<Any?>(null, JSONObject.NULL)) {
            val unknown = model().put("defaultReasoningEffort", "context-only")
            if (efforts == null) unknown.remove("supportedReasoningEfforts") else unknown.put("supportedReasoningEfforts", efforts)
            val catalog = fetchBridgeModelCatalog { _, _, _ -> page(unknown, model("available")) }
            assertEquals(listOf("wire-model", "available"), catalog.models.map { it.id })
            assertEquals(emptyList<String>(), catalog.models.first().efforts)
            assertNull(catalog.models.first().defaultEffort)
            for (effort in listOf("context-only", "high", "")) {
                rejected { catalog.validate(submission().copy(effort = effort)) }
            }
            catalog.validate(submission().copy(model = "available"))
        }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model().put("supportedReasoningEfforts", JSONArray())) } }
    }

    @Test fun emptyCursorEndsBothPopulatedAndEmptyCatalogsWithoutAnotherRequest() = runBlocking {
        for (rows in listOf(listOf(model()), emptyList())) {
            var calls = 0
            val catalog = fetchBridgeModelCatalog { _, path, _ ->
                calls++
                assertEquals("/v2/codex/models", path)
                page(*rows.toTypedArray(), cursor = "")
            }
            assertEquals(1, calls)
            assertEquals(if (rows.isEmpty()) emptyList<String>() else listOf("wire-model"), catalog.models.map { it.id })
            if (rows.isNotEmpty()) catalog.validate(submission())
        }
    }

    @Test fun opaqueWhitespaceCursorIsEncodedWithoutChangingItsIdentity() = runBlocking {
        val paths = mutableListOf<String>()
        val catalog = fetchBridgeModelCatalog { _, path, _ ->
            paths += path
            if (paths.size == 1) page(model(), cursor = " \t ") else page(cursor = "")
        }
        assertEquals(listOf("/v2/codex/models", "/v2/codex/models?cursor=+%09+"), paths)
        catalog.validate(submission())
    }

    @Test fun duplicateRowsEffortsAndSpeedsFailClosed() = runBlocking {
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model(), model()) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model(), model("other").put("id", "catalog-row")) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model().put("supportedReasoningEfforts", JSONArray()
            .put(JSONObject().put("reasoningEffort", "high")).put(JSONObject().put("reasoningEffort", "high")))) } }
        val row = model()
        row.getJSONArray("serviceTiers").put(JSONObject().put("id", "regular").put("name", "Another").put("description", ""))
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(row) } }
        var calls = 0
        rejected { fetchBridgeModelCatalog { _, _, _ -> calls++; page(model(), cursor = if (calls == 1) "next" else null) } }
        assertEquals(2, calls)
    }

    @Test fun emptyCatalogHasNoSubmissionAuthority() = runBlocking {
        val catalog = fetchBridgeModelCatalog { _, _, _ -> page() }
        assertEquals(emptyList<BridgeModelOption>(), catalog.models)
        rejected { catalog.validate(submission()) }
        rejected { fetchBridgeModelCatalog { _, _, _ -> throw IllegalStateException("PC unavailable") } }
    }

    @Test fun cursorLoopsEmptyProgressAndOversizedPagesAreBounded() = runBlocking {
        var calls = 0
        rejected { fetchBridgeModelCatalog { _, _, _ -> calls++; page(model("model-$calls"), cursor = "same") } }
        assertEquals(2, calls)
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(cursor = "next") } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model(), cursor = "x".repeat(4097)) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(*Array(101) { model("model-$it") }) } }
        rejected { fetchBridgeModelCatalog { _, _, _ -> page(model()).put("nextCursor", 5) } }
    }

    @Test fun advancingCatalogStillStopsAtSixteenPages() = runBlocking {
        var calls = 0
        rejected { fetchBridgeModelCatalog { _, _, _ -> calls++; page(model("model-$calls"), cursor = "page-$calls") } }
        assertEquals(16, calls)
    }

    private fun model(identity: String = "wire-model"): JSONObject = JSONObject()
        .put("id", if (identity == "wire-model") "catalog-row" else "row-$identity").put("model", identity)
        .put("displayName", "Visible model").put("isDefault", true).put("hidden", false)
        .put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "low"))
            .put(JSONObject().put("reasoningEffort", "high")))
        .put("defaultReasoningEffort", "high")
        .put("serviceTiers", JSONArray().put(JSONObject().put("id", "regular").put("name", "Regular speed")
            .put("description", "Normal processing")).put(JSONObject().put("id", "express")
            .put("name", "Express speed").put("description", "Faster processing")))
        .put("defaultServiceTier", "regular")

    private fun page(vararg rows: JSONObject, cursor: String? = null): JSONObject = JSONObject()
        .put("data", JSONArray().also { array -> rows.forEach { array.put(it) } })
        .put("nextCursor", cursor ?: JSONObject.NULL)

    private fun submission() = BridgeSongSubmission("https://youtu.be/abcdefghijk", "wire-model", "high", "regular")

    private suspend fun rejected(block: suspend () -> Unit) {
        assertNotNull("Expected the catalog or selection to be rejected", runCatching { block() }.exceptionOrNull())
    }
}
