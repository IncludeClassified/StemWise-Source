package app.stemwise.enrichment

import app.stemwise.data.RootAuthority
import java.security.MessageDigest
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/** Owns the admitted graph; consumer snapshots share strings, never mutable JSON or PCM arrays. */
internal class VerifiedInstalledEnrichment internal constructor(document: JSONObject, val sha256: String) {
    private val admitted = snapshotObject(document)
    val document: JSONObject get() = snapshotObject(admitted)
    val analysis: JSONObject? get() = admitted.optJSONObject("analysis")?.let(::snapshotObject)
    val detail: JSONObject get() = JSONObject().also { projection ->
        for (key in listOf("metadata", "lyrics")) admitted.opt(key)?.let { projection.put(key, snapshotValue(it)) }
    }
    private val waveformLevels = mutableMapOf<Long, List<Float>>()
    private var waveformBytes = 0L

    @Synchronized
    fun waveform(frameDurationMs: Long, decode: () -> FloatArray): List<Float> {
        require(frameDurationMs == 5L || frameDurationMs == 50L)
        waveformLevels[frameDurationMs]?.let { return it }
        val decoded = decode()
        val bytes = decoded.size.toLong() * 4L
        check(bytes <= MAX_WAVEFORM_BYTES - waveformBytes) { "WAVEFORM_RETENTION_LIMIT" }
        return Collections.unmodifiableList(decoded.copyOf().asList()).also {
            waveformBytes += bytes
            waveformLevels[frameDurationMs] = it
        }
    }

    companion object { private const val MAX_WAVEFORM_BYTES = 8L * 1024L * 1024L }
}

private fun snapshotValue(value: Any): Any = when (value) {
    is JSONObject -> snapshotObject(value)
    is JSONArray -> JSONArray().also { copy ->
        for (index in 0 until value.length()) copy.put(snapshotValue(value.get(index)))
    }
    else -> value
}

private fun snapshotObject(value: JSONObject): JSONObject = JSONObject().also { copy ->
    value.keys().forEach { name -> copy.put(name, snapshotValue(value.get(name))) }
}

internal class InstalledEnrichmentVerificationCache {
    private data class Key(val root: RootAuthority, val songId: String, val sourceSha256: String, val sha256: String)
    private data class Entry(val value: VerifiedInstalledEnrichment, val reservedBytes: Long)
    private val entries = LinkedHashMap<Key, Entry>(4, 0.75f, true)
    private var retainedBytes = 0L

    @Synchronized
    fun find(root: RootAuthority, songId: String, sourceSha256: String, sha256: String): VerifiedInstalledEnrichment? =
        entries[Key(root, songId, sourceSha256, sha256)]?.value

    @Synchronized
    fun verify(
        bytes: ByteArray,
        root: RootAuthority,
        songId: String,
        sourceSha256: String,
        expectedRevision: String? = null,
        decode: (ByteArray) -> JSONObject = ::parseStrictManifestJson
    ): VerifiedInstalledEnrichment {
        check(bytes.size <= ENRICHMENT_ARTIFACT_MAX_BYTES) { "MANIFEST_SIZE_LIMIT" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(expectedRevision == null || sha256 == expectedRevision) { "MANIFEST_REVISION_CHANGED" }
        find(root, songId, sourceSha256, sha256)?.let { return it }
        val document = runCatching { decode(bytes) }.getOrElse { error("MANIFEST_JSON_INVALID") }
        validateInstalledEnrichmentDocument(document, songId, sourceSha256)
        return VerifiedInstalledEnrichment(document, sha256).also {
            // Reserve each owner's maximum decoded waveform budget as well as its input bytes.
            val reservation = bytes.size.toLong() + 8L * 1024L * 1024L
            while (retainedBytes + reservation > 64L * 1024L * 1024L && entries.isNotEmpty()) {
                val oldest = entries.entries.iterator()
                retainedBytes -= oldest.next().value.reservedBytes
                oldest.remove()
            }
            entries[Key(root, songId, sourceSha256, sha256)] = Entry(it, reservation)
            retainedBytes += reservation
        }
    }
}

internal val installedEnrichmentVerificationCache = InstalledEnrichmentVerificationCache()
