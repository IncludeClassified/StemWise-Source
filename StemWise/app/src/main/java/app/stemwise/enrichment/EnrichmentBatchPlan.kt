package app.stemwise.enrichment

import app.stemwise.data.CanonicalJson
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

internal enum class EnrichmentBatchRevisionDisposition { APPLY, DUPLICATE, SUPERSEDED, CONFLICT }

internal data class EnrichmentBatchRevision(
    val index: Int,
    val sourceName: String,
    val videoId: String,
    val generatedAt: String,
    val inputSha256: String,
    val originalSha256: String? = null
)

internal fun enrichmentRevisionIdentity(enrichment: JSONObject, coverSha256: String?): String =
    MessageDigest.getInstance("SHA-256").digest(
        (CanonicalJson.sha256(enrichment) + ":" + (coverSha256 ?: "none")).toByteArray(Charsets.US_ASCII)
    ).toHexString()

/** Complete bundles replace one another; only ambiguity at the newest valid revision blocks intake. */
internal fun planEnrichmentBatchRevisions(
    revisions: List<EnrichmentBatchRevision>
): Map<Int, EnrichmentBatchRevisionDisposition> {
    val disposition = revisions.associate { it.index to EnrichmentBatchRevisionDisposition.SUPERSEDED }.toMutableMap()
    revisions.groupBy(EnrichmentBatchRevision::videoId).values.forEach { sameVideo ->
        val newest = sameVideo.maxOf { Instant.parse(it.generatedAt) }
        val winners = sameVideo.filter { Instant.parse(it.generatedAt) == newest }
        if (winners.map(EnrichmentBatchRevision::inputSha256).distinct().size > 1 ||
            winners.mapNotNull(EnrichmentBatchRevision::originalSha256).distinct().size > 1) {
            winners.forEach { disposition[it.index] = EnrichmentBatchRevisionDisposition.CONFLICT }
        } else {
            val selected = winners.minWith(compareBy<EnrichmentBatchRevision> { it.sourceName.lowercase(Locale.ROOT) }
                .thenBy(EnrichmentBatchRevision::index))
            winners.forEach { disposition[it.index] = if (it.index == selected.index)
                EnrichmentBatchRevisionDisposition.APPLY else EnrichmentBatchRevisionDisposition.DUPLICATE }
        }
    }
    return disposition
}

internal fun enrichmentBatchApplicationOrder(
    revisions: List<EnrichmentBatchRevision>,
    dispositions: Map<Int, EnrichmentBatchRevisionDisposition>
): List<Int> = revisions
    .filter { dispositions[it.index] == EnrichmentBatchRevisionDisposition.APPLY }
    .sortedWith(
        compareBy<EnrichmentBatchRevision> { it.videoId }
            .thenBy { Instant.parse(it.generatedAt) }
            .thenBy { it.sourceName.lowercase(Locale.ROOT) }
            .thenBy(EnrichmentBatchRevision::inputSha256)
            .thenBy(EnrichmentBatchRevision::index)
    )
    .map(EnrichmentBatchRevision::index)
