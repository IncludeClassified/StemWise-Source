package app.stemwise.model

import java.util.Locale

/** The single role, order, and normalization contract used by every layer. */
object StemRoleContract {
    // Original is separate; every playable target plus Remaining has one native lane.
    const val maximumStemCount: Int = app.stemwise.MAX_PLAYABLE_STEM_COUNT

    private fun compact(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    private val sourceAliases = buildMap {
        StemSourceCatalogue.sources.forEach { source ->
            put(compact(source.id), source.id)
            put(compact(source.label), source.id)
        }
        put("remaining", "remaining")
    }

    fun normalize(value: String): String = compact(value).let { sourceAliases[it] ?: it }

    fun isPlayable(roles: Collection<String>): Boolean {
        val normalized = roles.map(::normalize)
        if (normalized.size !in 2..StemSourceCatalogue.MAX_TARGETS + 1 ||
            normalized.toSet().size != normalized.size || normalized.count { it == "remaining" } != 1
        ) return false
        return StemSourceCatalogue.selectionIssue(normalized.filterNot { it == "remaining" }) == null
    }

    /** The same order drives stored assets, native decoding, UI labels and control indices. */
    fun orderedPlayableRoles(roles: Collection<String>): List<String> {
        require(isPlayable(roles)) { "A source package requires distinct supported parts and Remaining" }
        val normalized = roles.map(::normalize).toSet()
        return StemSourceCatalogue.sources.map(StemSource::id).filter { it in normalized } + "remaining"
    }
}
