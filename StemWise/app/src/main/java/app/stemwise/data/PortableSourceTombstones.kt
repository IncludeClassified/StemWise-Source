package app.stemwise.data

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_PORTABLE_TOMBSTONES = 16_384
private const val PORTABLE_SOURCE_BINDING_SCHEMA_VERSION = 1L
private const val PORTABLE_SOURCE_BINDING_KIND = "stemwise.source-tombstones"
private val PORTABLE_SOURCE_BINDING_ROOT_KEYS =
    setOf("schemaVersion", "kind", "libraryId", "revision", "entries")
private val PORTABLE_SOURCE_BINDING_ENTRY_KEYS =
    setOf("sourceId", "packageId", "state", "updatedAtEpochMs")

internal data class PortableBlockedAuthority(
    val revision: Long,
    val bindings: List<SourceBindingEntity>
)

internal fun decodePortableBlockedAuthority(
        libraryId: String,
        value: JSONObject
    ): PortableBlockedAuthority {
        check(value.keys().asSequence().toSet() == PORTABLE_SOURCE_BINDING_ROOT_KEYS) {
            "SOURCE_BINDING_AUTHORITY_INVALID"
        }
        val revision = exactSourceBindingLong(value.opt("revision"))
        check(
            exactSourceBindingLong(value.opt("schemaVersion")) ==
                PORTABLE_SOURCE_BINDING_SCHEMA_VERSION &&
                value.opt("kind") == PORTABLE_SOURCE_BINDING_KIND &&
                value.opt("libraryId") == libraryId && revision != null && revision >= 0L
        ) { "SOURCE_BINDING_AUTHORITY_INVALID" }
        val entries = value.optJSONArray("entries")
            ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
        check(entries.length() <= MAX_PORTABLE_TOMBSTONES) {
            "SOURCE_BINDING_AUTHORITY_INVALID"
        }
        val bindings = (0 until entries.length()).map { index ->
            val entry = entries.optJSONObject(index)
                ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
            check(entry.keys().asSequence().toSet() == PORTABLE_SOURCE_BINDING_ENTRY_KEYS) {
                "SOURCE_BINDING_AUTHORITY_INVALID"
            }
            val sourceId = entry.opt("sourceId") as? String
                ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
            val packageId = entry.opt("packageId") as? String
                ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
            val state = entry.opt("state") as? String
                ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
            val updatedAt = exactSourceBindingLong(entry.opt("updatedAtEpochMs"))
                ?: error("SOURCE_BINDING_AUTHORITY_INVALID")
            check(state in PORTABLE_SOURCE_BINDING_STATES && updatedAt > 0L) {
                "SOURCE_BINDING_AUTHORITY_INVALID"
            }
            SourceBindingEntity(
                libraryId,
                sourceId,
                packageId,
                state,
                "portable-tombstone",
                updatedAt
            ).also { validateSourceBindingEntity(it, libraryId) }
        }.sortedBy(SourceBindingEntity::sourceId)
        check(bindings.map(SourceBindingEntity::sourceId).distinct().size == bindings.size) {
            "SOURCE_BINDING_AUTHORITY_INVALID"
        }
        return PortableBlockedAuthority(checkNotNull(revision), bindings)
    }


internal fun encodePortableBlockedAuthority(
    libraryId: String, revision: Long, bindings: List<SourceBindingEntity>
): ByteArray {
    check(bindings.size <= MAX_PORTABLE_TOMBSTONES) { "SOURCE_BINDING_TOMBSTONE_CAPACITY_EXCEEDED" }
    check(revision >= 0L && bindings.map { it.sourceId }.distinct().size == bindings.size) {
        "SOURCE_BINDING_AUTHORITY_INVALID"
    }
    bindings.forEach {
        validateSourceBindingEntity(it, libraryId)
        check(it.state == "DELETED") { "SOURCE_BINDING_AUTHORITY_INVALID" }
    }
    val bytes = JSONObject()
        .put("schemaVersion", PORTABLE_SOURCE_BINDING_SCHEMA_VERSION)
        .put("kind", PORTABLE_SOURCE_BINDING_KIND)
        .put("libraryId", libraryId)
        .put("revision", revision)
        .put("entries", JSONArray().apply {
            bindings.forEach { binding ->
                put(JSONObject()
                    .put("sourceId", binding.sourceId)
                    .put("packageId", binding.packageId)
                    .put("state", binding.state)
                    .put("updatedAtEpochMs", binding.updatedAtEpochMs))
            }
        })
        .toString()
        .toByteArray(Charsets.UTF_8)
    check(bytes.size <= MAX_PORTABLE_SOURCE_BINDING_BYTES) {
        "SOURCE_BINDING_AUTHORITY_INVALID"
    }
    return bytes
}

private fun exactSourceBindingLong(value: Any?): Long? = when (value) {
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    else -> null
}

/** Only an exact prefix of a complete selected-root generation is an interrupted write. */
internal fun selectPortableBlockedAuthority(
    libraryId: String,
    copies: List<ByteArray>,
    intended: ByteArray? = null
): PortableBlockedAuthority? {
    val parsed = copies.map { bytes -> bytes to runCatching {
        decodePortableBlockedAuthority(libraryId, app.stemwise.enrichment.parseStrictManifestJson(bytes))
    } }
    val intendedAuthority = intended?.let {
        decodePortableBlockedAuthority(libraryId, app.stemwise.enrichment.parseStrictManifestJson(it))
    }
    val complete = parsed.mapNotNull { (bytes, result) -> result.getOrNull()?.let { bytes to it } } +
        listOfNotNull(intended?.let { it to checkNotNull(intendedAuthority) })
    parsed.filter { it.second.isFailure }.forEach { (bytes, _) ->
        check(interruptedReservedAuthorityWriteIsRecoverable(bytes, complete.map { it.first }, true)) {
            "SOURCE_BINDING_AUTHORITY_INVALID"
        }
    }
    if (complete.isEmpty()) return null
    val revision = complete.maxOf { it.second.revision }
    val winners = complete.filter { it.second.revision == revision }.map { it.second }
    check(winners.all { it.bindings == winners.first().bindings }) { "SOURCE_BINDING_AUTHORITY_INVALID" }
    return winners.first()
}
