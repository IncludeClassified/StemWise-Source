package app.stemwise.enrichment

import android.content.Context
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.SelectedSongSnapshot
import app.stemwise.data.measureProviderDocument
import org.json.JSONObject

/** Raw identity only: damaged sidecars remain damaged and are never approved as a rollback pair. */
internal suspend fun enrichmentPreparationTargets(
    context: Context,
    song: SelectedSongSnapshot,
    originalSha256: String,
    checkpoint: suspend () -> Unit
): JSONObject {
    val original = checkNotNull(song.original) { "RECOVERY_PENDING" }
    val result = JSONObject().put("originalDocumentId", original.documentId)
        .put("originalSha256", originalSha256)
    for ((prefix, name) in listOf("json" to SelectedLibraryContract.ENRICHMENT, "cover" to SelectedLibraryContract.COVER)) {
        val target = song.file(name)
        val measured = target?.let {
            measureProviderDocument(context, it.uri, it.sizeBytes, requireNonEmpty = false, checkpoint = checkpoint)
        }
        result.put("${prefix}DocumentId", target?.documentId.orEmpty())
            .put("${prefix}Sha256", measured?.sha256.orEmpty())
    }
    return result
}

internal fun requireUnchangedEnrichmentPreparation(expected: JSONObject, current: JSONObject) {
    check(expected.length() == current.length() && current.keys().asSequence().all { key ->
        (expected.opt(key) as? String) == current.getString(key)
    }) { "RECOVERY_PENDING" }
}
