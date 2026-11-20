package app.stemwise.bridge

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class BridgeServiceTier(val id: String, val name: String, val description: String)

data class BridgeModelOption(
    val id: String,
    val label: String,
    val efforts: List<String>,
    val defaultEffort: String?,
    val serviceTiers: List<BridgeServiceTier>,
    val defaultServiceTier: String?,
    val isDefault: Boolean = false
)

data class BridgeSongSubmission(val link: String, val model: String, val effort: String, val serviceTier: String)

data class BridgeModelCatalog(val models: List<BridgeModelOption>) {
    fun validate(submission: BridgeSongSubmission) {
        bridgeVideoId(submission.link)
        val model = models.singleOrNull { it.id == submission.model }
        require(model != null) { "Choose a model from the current PC catalog" }
        require(submission.effort.isNotBlank() && submission.effort in model.efforts) {
            "Choose an intelligence level advertised for this model"
        }
        require(submission.serviceTier.isEmpty() || model.serviceTiers.any { it.id == submission.serviceTier }) {
            "Choose Native default or a speed advertised for this model"
        }
    }
}

internal suspend fun fetchBridgeModelCatalog(
    api: suspend (method: String, path: String, body: JSONObject?) -> JSONObject
): BridgeModelCatalog {
    val models = mutableListOf<BridgeModelOption>()
    val ids = mutableSetOf<String>()
    val modelIds = mutableSetOf<String>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    repeat(16) {
        val path = "/v2/codex/models" + (cursor?.let { "?cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val page = api("GET", path, null)
        val rows = page.get("data") as? JSONArray ?: error("BRIDGE_MODEL_CATALOG_INVALID")
        check(rows.length() <= 100) { "BRIDGE_MODEL_CATALOG_INVALID" }
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val id = row.catalogString("id")
            val modelId = row.catalogString("model")
            check(ids.add(id) && modelIds.add(modelId)) { "BRIDGE_MODEL_CATALOG_DUPLICATE" }
            val effortsKnown = row.has("supportedReasoningEfforts") && !row.isNull("supportedReasoningEfforts")
            val efforts = if (!effortsKnown) emptyList() else {
                row.catalogArray("supportedReasoningEfforts").let { options ->
                    List(options.length()) { position ->
                        options.getJSONObject(position).also { it.catalogOptionalString("description", allowBlank = true) }
                            .catalogString("reasoningEffort")
                    }
                }
            }
            check(efforts.distinct().size == efforts.size) { "BRIDGE_MODEL_CATALOG_DUPLICATE" }
            val tiersKnown = row.has("serviceTiers") && !row.isNull("serviceTiers")
            val tiers = if (!tiersKnown) emptyList() else {
                row.catalogArray("serviceTiers").let { options ->
                    List(options.length()) { position ->
                        options.getJSONObject(position).let {
                            BridgeServiceTier(it.catalogString("id"), it.catalogString("name"),
                                it.catalogString("description", allowBlank = true))
                        }
                    }
                }
            }
            check(tiers.map { it.id }.distinct().size == tiers.size) { "BRIDGE_MODEL_CATALOG_DUPLICATE" }
            val defaultEffort = row.catalogOptionalString("defaultReasoningEffort").takeIf { effortsKnown }
            val defaultTier = row.catalogOptionalString("defaultServiceTier").takeIf { tiersKnown }
            check(defaultEffort == null || defaultEffort in efforts) { "BRIDGE_MODEL_CATALOG_INVALID_DEFAULT" }
            check(defaultTier == null || tiers.any { it.id == defaultTier }) { "BRIDGE_MODEL_CATALOG_INVALID_DEFAULT" }
            val option = BridgeModelOption(modelId, row.catalogOptionalString("displayName") ?: modelId,
                efforts, defaultEffort, tiers, defaultTier, row.catalogBoolean("isDefault"))
            if (!row.catalogBoolean("hidden")) models += option
        }
        check(page.has("nextCursor")) { "BRIDGE_MODEL_CATALOG_INVALID" }
        if (page.isNull("nextCursor")) return BridgeModelCatalog(models.toList())
        val next = page.catalogString("nextCursor", allowBlank = true)
        if (next.isEmpty()) return BridgeModelCatalog(models.toList())
        check(next.length <= 4096 && cursors.add(next) && rows.length() > 0) { "BRIDGE_MODEL_CATALOG_CURSOR_INVALID" }
        cursor = next
    }
    error("BRIDGE_MODEL_CATALOG_PAGE_LIMIT")
}

private fun JSONObject.catalogString(key: String, allowBlank: Boolean = false): String {
    val value = get(key)
    check(value is String && (allowBlank || value.isNotBlank())) { "BRIDGE_MODEL_CATALOG_INVALID" }
    return value
}

private fun JSONObject.catalogOptionalString(key: String, allowBlank: Boolean = false): String? =
    if (!has(key) || isNull(key)) null else catalogString(key, allowBlank)

private fun JSONObject.catalogBoolean(key: String): Boolean {
    if (!has(key)) return false
    val value = get(key)
    check(value is Boolean) { "BRIDGE_MODEL_CATALOG_INVALID" }
    return value
}

private fun JSONObject.catalogArray(key: String): JSONArray {
    val value = get(key)
    check(value is JSONArray && value.length() <= 100) { "BRIDGE_MODEL_CATALOG_INVALID" }
    return value
}
