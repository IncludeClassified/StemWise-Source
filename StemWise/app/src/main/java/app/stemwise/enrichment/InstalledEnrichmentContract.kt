package app.stemwise.enrichment

import app.stemwise.data.CanonicalJson
import app.stemwise.data.SelectedLibraryContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant


internal data class InstalledMediaIdentity(val sha256: String, val byteLength: Long)

internal fun isInstalledUtcTimestamp(value: Any?): Boolean {
    val timestamp = value as? String ?: return false
    if (!INSTALLED_UTC_TIMESTAMP.matches(timestamp)) return false
    return runCatching { Instant.parse(timestamp) }.isSuccess
}

/** Builds the only JSON document allowed beside the canonical original and optional stems. */
internal fun createInstalledEnrichmentDocument(
    incoming: JSONObject,
    originalSha256: String,
    media: Map<String, InstalledMediaIdentity>,
    contentRevision: Long,
    payloadSha256: String,
    artwork: JSONObject,
    artworkSha256: String,
    artworkBytes: Int
): JSONObject {
    val originalPath = enrichmentOriginalPath(incoming.getJSONObject("source"),
        media.keys.singleOrNull() ?: error("ASSET_MISSING"))
    check(media[originalPath]?.sha256 == originalSha256) {
        "ENRICHMENT_CORE_MISMATCH"
    }
    val output = JSONObject()
        .put("schemaVersion", INSTALLED_ENRICHMENT_SCHEMA_VERSION)
        .put("kind", INSTALLED_ENRICHMENT_KIND)
        .put("source", JSONObject(incoming.getJSONObject("source").toString()))
        .put("generatedAt", incoming.getString("generatedAt"))
        .put("generator", JSONObject(incoming.getJSONObject("generator").toString()))
        .put(
            "media",
            JSONObject().put(
                "files",
                JSONArray().apply {
                    listOf(originalPath).forEach { path ->
                        val identity = checkNotNull(media[path])
                        check(SHA256.matches(identity.sha256) && identity.byteLength > 0L) {
                            "ENRICHMENT_CORE_MISMATCH"
                        }
                        put(
                            JSONObject()
                                .put("path", path)
                                .put("byteLength", identity.byteLength)
                                .put("sha256", identity.sha256)
                        )
                    }
                }
            )
        )
    listOf("metadata", "lyrics", "analysis").forEach { key ->
        incoming.optJSONObject(key)?.let { output.put(key, JSONObject(it.toString())) }
    }
    check(SHA256.matches(artworkSha256) && artworkBytes > 0) {
        "ARTWORK_NEEDS_REIMPORT"
    }
    output.put(
        "artwork",
        JSONObject()
            .put("delivery", "selected-root")
            .put("file", SelectedLibraryContract.COVER)
            .put("mimeType", "image/png")
            .put("byteLength", artworkBytes)
            .put("sha256", artworkSha256)
            .put("kind", artwork.getString("kind"))
            .put("source", JSONObject(artwork.getJSONObject("source").toString()))
    )
    val install = JSONObject()
        .put("contractVersion", INSTALLED_ENRICHMENT_CONTRACT)
        .put("originalSha256", originalSha256)
        .put("contentRevision", contentRevision)
        .put("payloadSha256", payloadSha256)
    output.put("install", install)
    install.put("generatedDigest", installedEnrichmentDigest(output))
    return output
}

internal fun validateInstalledEnrichmentDocument(
    value: JSONObject,
    expectedVideoId: String,
    expectedOriginalSha256: String
) {
    check(SelectedLibraryContract.isVideoId(expectedVideoId)) { "YOUTUBE_VIDEO_ID_MISSING" }
    val allowed = setOf(
        "schemaVersion", "kind", "source", "generatedAt", "generator", "media", "install",
        "metadata", "artwork", "lyrics", "analysis"
    )
    val required = setOf(
        "schemaVersion", "kind", "source", "generatedAt", "generator", "media", "artwork",
        "install"
    )
    check(value.keys().asSequence().toSet().containsAll(required)) { "SCHEMA_UNSUPPORTED" }
    check(value.keys().asSequence().all { it in allowed }) { "SCHEMA_UNSUPPORTED" }
    check(
        value.exactInteger("schemaVersion") == INSTALLED_ENRICHMENT_SCHEMA_VERSION.toLong() &&
            value.opt("kind") is String && value.getString("kind") == INSTALLED_ENRICHMENT_KIND
    ) { "SCHEMA_UNSUPPORTED" }
    val source = value.optJSONObject("source") ?: error("YOUTUBE_VIDEO_ID_MISSING")
    validateEnrichmentSourceIdentity(source)
    check(
        source.getString("videoId") == expectedVideoId
    ) { "SOURCE_BINDING_CONFLICT" }
    val generatedAt = value.opt("generatedAt")
    check(isInstalledUtcTimestamp(generatedAt)) {
        "SCHEMA_UNSUPPORTED"
    }
    val generator = value.optJSONObject("generator") ?: error("SCHEMA_UNSUPPORTED")
    check(
        generator.keys().asSequence().toSet() == setOf("name", "version") &&
            generator.opt("name") is String &&
            generator.getString("name") == ENRICHMENT_GENERATOR_NAME &&
            generator.opt("version") is String &&
            generator.getString("version") == ENRICHMENT_GENERATOR_VERSION
    ) { "SCHEMA_UNSUPPORTED" }
    listOf("metadata", "lyrics", "analysis").forEach { group ->
        if (value.has(group)) check(value.opt(group) is JSONObject) { "SCHEMA_UNSUPPORTED" }
    }
    val install = value.optJSONObject("install") ?: error("SCHEMA_UNSUPPORTED")
    val media = installedMediaIdentities(value)
    check(
        install.keys().asSequence().toSet() == setOf(
            "contractVersion", "originalSha256", "contentRevision",
            "payloadSha256", "generatedDigest"
        ) &&
        install.opt("contractVersion") is String &&
            install.getString("contractVersion") == INSTALLED_ENRICHMENT_CONTRACT &&
            install.opt("originalSha256") is String &&
            install.getString("originalSha256") == expectedOriginalSha256 &&
            media.values.single().sha256 == expectedOriginalSha256 &&
            install.exactInteger("contentRevision")?.let { it >= 1L } == true &&
            install.opt("payloadSha256") is String &&
            SHA256.matches(install.getString("payloadSha256")) &&
            install.opt("generatedDigest") is String &&
            SHA256.matches(install.getString("generatedDigest"))
    ) { "ENRICHMENT_CORE_MISMATCH" }
    source.optJSONObject("recording")?.let { recording ->
        val original = media.getValue(SelectedLibraryContract.ORIGINAL_FLAC)
        check(recording.getString("sha256") == original.sha256 &&
            recording.getLong("byteCount") == original.byteLength) { "SOURCE_BINDING_CONFLICT" }
    }
    check(install.optString("generatedDigest") == installedEnrichmentDigest(value)) {
        "GENERATED_DIGEST_INVALID"
    }
    val artwork = value.optJSONObject("artwork") ?: error("ARTWORK_NEEDS_REIMPORT")
    check(
        artwork.keys().asSequence().toSet() == setOf(
            "delivery", "file", "mimeType", "byteLength", "sha256", "kind", "source"
        ) &&
        artwork.opt("delivery") is String &&
            artwork.getString("delivery") == "selected-root" &&
            artwork.opt("file") is String &&
            artwork.getString("file") == SelectedLibraryContract.COVER &&
            artwork.opt("mimeType") is String &&
            artwork.getString("mimeType") == "image/png" &&
            artwork.exactInteger("byteLength")?.let { it > 0L } == true &&
            artwork.opt("sha256") is String &&
            SHA256.matches(artwork.getString("sha256")) &&
            artwork.nonBlankString("kind") in ARTWORK_KINDS &&
            artwork.optJSONObject("source")?.validClosedSourceDescriptor() == true
    ) { "ARTWORK_NEEDS_REIMPORT" }
    EnrichmentContractValidator.validateGeneratedGroups(value)
}

internal fun installedMediaIdentities(
    value: JSONObject
): Map<String, InstalledMediaIdentity> {
    val media = value.optJSONObject("media") ?: error("SCHEMA_UNSUPPORTED")
    check(media.keys().asSequence().toSet() == setOf("files")) { "SCHEMA_UNSUPPORTED" }
    val files = media.optJSONArray("files") ?: error("SCHEMA_UNSUPPORTED")
    check(files.length() == 1) { "ASSET_MISSING" }
    val item = files.optJSONObject(0) ?: error("SCHEMA_UNSUPPORTED")
    check(
        item.keys().asSequence().toSet() == setOf("path", "byteLength", "sha256") &&
            item.opt("path") is String &&
            item.exactInteger("byteLength")?.let { it > 0L } == true &&
            item.opt("sha256") is String && SHA256.matches(item.getString("sha256"))
    ) { "ENRICHMENT_CORE_MISMATCH" }
    val originalPath = enrichmentOriginalPath(value.getJSONObject("source"), item.getString("path"))
    return mapOf(originalPath to InstalledMediaIdentity(
            sha256 = item.getString("sha256"),
            byteLength = item.getLong("byteLength")
        ))
}

internal fun installedEnrichmentDigest(value: JSONObject): String {
    val copy = JSONObject(value, value.keys().asSequence().toList().toTypedArray())
    value.optJSONObject("install")?.let { install ->
        copy.put("install", JSONObject(install, install.keys().asSequence()
            .filterNot { it == "generatedDigest" }.toList().toTypedArray()))
    }
    return CanonicalJson.sha256(copy)
}

private val SHA256 = Regex("^[a-f0-9]{64}$")
private val INSTALLED_UTC_TIMESTAMP = Regex(CANONICAL_UTC_TIMESTAMP_PATTERN)
private val ARTWORK_KINDS = setOf("album-cover", "single-cover", "youtube-thumbnail", "other")

private fun JSONObject.exactInteger(name: String): Long? = when (val value = opt(name)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

private fun JSONObject.nonBlankString(name: String): String? =
    (opt(name) as? String)?.takeIf(String::isNotBlank)

private fun JSONObject.validClosedSourceDescriptor(): Boolean {
    val keys = keys().asSequence().toSet()
    if (keys !in setOf(setOf("name"), setOf("name", "url"))) return false
    if (nonBlankString("name") == null) return false
    if (!has("url")) return true
    val url = nonBlankString("url") ?: return false
    return isValidEnrichmentHttpsUrl(url)
}

/** Optional attribution is absent or a usable credential-free HTTPS URL. */
internal fun isValidEnrichmentHttpsUrl(value: String): Boolean = runCatching {
    val parsed = URI(value)
    value.isNotBlank() && parsed.scheme == "https" && !parsed.host.isNullOrBlank() &&
        parsed.userInfo == null && (parsed.port == -1 || parsed.port in 1..65535)
}.getOrDefault(false)
