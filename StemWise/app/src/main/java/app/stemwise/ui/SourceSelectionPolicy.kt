package app.stemwise.ui

import app.stemwise.model.StemSource
import app.stemwise.model.StemSourceCatalogue

/** The dialog uses the same source constraints as preparation, plus live availability. */
internal fun sourceSelectionIssue(
    selectedIds: Collection<String>,
    availableSources: List<StemSource>
): String? {
    StemSourceCatalogue.selectionIssue(selectedIds)?.let { return it }
    val availableIds = availableSources.mapTo(mutableSetOf()) { it.id }
    return if (selectedIds.any { it !in availableIds }) {
        "Some selected parts are unavailable. Choose another part."
    } else null
}

internal fun sourceSelectionCandidateIssue(
    id: String,
    selectedIds: List<String>,
    availableSources: List<StemSource>
): String? = if (id in selectedIds) null else {
    sourceSelectionIssue(selectedIds + id, availableSources)
}

/** A rejected addition preserves the choice; every checked part can still be removed. */
internal fun sourceSelectionAfterToggle(
    id: String,
    selectedIds: List<String>,
    availableSources: List<StemSource>
): List<String> = when {
    id in selectedIds -> selectedIds.filterNot { it == id }
    sourceSelectionCandidateIssue(id, selectedIds, availableSources) != null -> selectedIds
    else -> selectedIds + id
}
