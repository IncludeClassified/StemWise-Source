package app.stemwise.ui

import app.stemwise.model.Song
import java.text.Normalizer

internal fun librarySearchWasCleared(
    previouslyHadText: Boolean,
    currentQuery: String
): Boolean = previouslyHadText && currentQuery.isBlank()

internal data class StemwiseSearchRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0)
        require(endExclusive > start)
    }
}

internal data class LibrarySearchContext(
    val label: String,
    val value: String,
    val displayRanges: List<StemwiseSearchRange>
) {
    val displayText: String get() = "$label · $value"
}

private data class SearchProjection(
    val comparison: String,
    val originalStarts: IntArray,
    val originalEnds: IntArray
)

internal data class LibrarySongSearchKey(
    val comparisons: List<String>,
    val comparisonsWithoutOptionalArabicMarks: List<String>
)

internal data class LibrarySearchQueryKey(
    val comparison: String,
    val preservesOptionalArabicMarks: Boolean
)

internal data class LibrarySongSearchIndex(
    val snapshot: List<Song>,
    val keys: List<LibrarySongSearchKey>
)

internal data class LibrarySongSearchResult(
    val snapshot: List<Song>,
    val query: LibrarySearchQueryKey,
    val songs: List<Song>
)

internal fun libraryVisibleSearchSongs(
    snapshot: List<Song>,
    query: LibrarySearchQueryKey?,
    result: LibrarySongSearchResult?
): List<Song> = when {
    query == null -> snapshot
    result?.snapshot === snapshot && result.query == query -> result.songs
    else -> emptyList()
}

/**
 * NFC-aware search that keeps exact UTF-16 ranges in the authored display string.
 * Arabic tatweel and optional marks are ignored only when the query itself omits them.
 */
internal fun stemwiseSearchRanges(text: String, query: String): List<StemwiseSearchRange> {
    if (text.isEmpty() || query.isBlank()) return emptyList()
    val normalizedQuery = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
    val queryContainsOptionalArabicMark = normalizedQuery.codePoints().anyMatch {
        isArabicOptionalSearchCodePoint(it)
    }
    val source = searchProjection(text, ignoreOptionalArabicMarks = !queryContainsOptionalArabicMark)
    val target = searchProjection(query.trim(), ignoreOptionalArabicMarks = !queryContainsOptionalArabicMark)
        .comparison
    if (source.comparison.isEmpty() || target.isEmpty()) return emptyList()

    val ranges = ArrayList<StemwiseSearchRange>()
    var cursor = 0
    while (cursor <= source.comparison.length - target.length) {
        val match = source.comparison.indexOf(target, startIndex = cursor)
        if (match < 0) break
        val last = match + target.length - 1
        ranges += StemwiseSearchRange(
            start = source.originalStarts[match],
            endExclusive = source.originalEnds[last]
        )
        cursor = match + target.length
    }
    return ranges.distinct()
}

internal fun librarySongSearchKey(song: Song): LibrarySongSearchKey {
    val comparisons = librarySearchFields(song).map { (_, value) ->
        searchProjection(
            original = value,
            ignoreOptionalArabicMarks = false,
            mapOriginalRanges = false
        ).comparison
    }
    return LibrarySongSearchKey(
        comparisons = comparisons,
        comparisonsWithoutOptionalArabicMarks = comparisons.map(String::withoutOptionalArabicMarks)
    )
}

internal fun librarySearchQueryKey(query: String): LibrarySearchQueryKey? {
    if (query.isBlank()) return null
    val normalized = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
    val preservesOptionalArabicMarks = normalized.codePoints().anyMatch {
        isArabicOptionalSearchCodePoint(it)
    }
    val comparison = searchProjection(
        original = query.trim(),
        ignoreOptionalArabicMarks = !preservesOptionalArabicMarks,
        mapOriginalRanges = false
    ).comparison
    return comparison.takeIf(String::isNotEmpty)?.let {
        LibrarySearchQueryKey(it, preservesOptionalArabicMarks)
    }
}

internal fun LibrarySongSearchKey.matches(query: LibrarySearchQueryKey?): Boolean {
    if (query == null) return false
    val values = if (query.preservesOptionalArabicMarks) {
        comparisons
    } else {
        comparisonsWithoutOptionalArabicMarks
    }
    return values.any { value -> value.contains(query.comparison) }
}

internal fun librarySearchContext(song: Song, query: String): LibrarySearchContext? {
    if (query.isBlank()) return null
    return libraryContextFields(song).firstNotNullOfOrNull { (label, value) ->
        val valueRanges = stemwiseSearchRanges(value, query)
        valueRanges.takeIf(List<StemwiseSearchRange>::isNotEmpty)?.let { causalRanges ->
            val displayOffset = "$label · ".length
            LibrarySearchContext(
                label = label,
                value = value,
                displayRanges = causalRanges.map { range ->
                    StemwiseSearchRange(
                        start = displayOffset + range.start,
                        endExclusive = displayOffset + range.endExclusive
                    )
                }
            )
        }
    }
}

private fun librarySearchFields(song: Song): List<Pair<String, String>> = buildList {
    add("Title" to song.title)
    add("Artist" to song.artist)
    add("Album" to song.album)
    addAll(libraryContextFields(song))
}

private fun libraryContextFields(song: Song): List<Pair<String, String>> = buildList {
    if (song.year.isNotBlank()) add("Year" to song.year)
    song.bpm?.let { add("Tempo" to "${it.toInt()} BPM") }
    song.key?.takeIf(String::isNotBlank)?.let { add("Key" to qualifiedAnalysisValue(it, song.keyStatus)) }
    song.meter?.takeIf(String::isNotBlank)?.let { add("Meter" to it) }
    song.additionalInfo.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) add(key to value)
    }
}

private fun searchProjection(
    original: String,
    ignoreOptionalArabicMarks: Boolean,
    mapOriginalRanges: Boolean = true
): SearchProjection {
    val comparison = StringBuilder()
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var offset = 0
    while (offset < original.length) {
        val clusterStart = offset
        offset += Character.charCount(original.codePointAt(offset))
        while (offset < original.length) {
            val codePoint = original.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type != Character.NON_SPACING_MARK.toInt() &&
                type != Character.COMBINING_SPACING_MARK.toInt() &&
                type != Character.ENCLOSING_MARK.toInt()
            ) {
                // Canonical Hangul composition joins L/V/T letters, not combining marks.
                if (codePoint !in 0x1161..0x1175 && codePoint !in 0x11A8..0x11C2) break
                val prefix = Normalizer.normalize(original.substring(clusterStart, offset), Normalizer.Form.NFC)
                val next = String(Character.toChars(codePoint))
                if (Normalizer.normalize(prefix + next, Normalizer.Form.NFC) == prefix + next) break
            }
            offset += Character.charCount(codePoint)
        }
        val clusterEnd = offset
        val normalized = Normalizer.normalize(SearchCaseFold.fold(Normalizer.normalize(
            original.substring(clusterStart, clusterEnd), Normalizer.Form.NFC
        )), Normalizer.Form.NFC)
        var normalizedOffset = 0
        while (normalizedOffset < normalized.length) {
            val codePoint = normalized.codePointAt(normalizedOffset)
            val count = Character.charCount(codePoint)
            if (!(ignoreOptionalArabicMarks && isArabicOptionalSearchCodePoint(codePoint))) {
                comparison.appendCodePoint(codePoint)
                if (mapOriginalRanges) {
                    repeat(count) {
                        starts += clusterStart
                        ends += clusterEnd
                    }
                }
            }
            normalizedOffset += count
        }
    }
    return SearchProjection(
        comparison = comparison.toString(),
        originalStarts = starts.toIntArray(),
        originalEnds = ends.toIntArray()
    )
}

private fun String.withoutOptionalArabicMarks(): String {
    if (codePoints().noneMatch(::isArabicOptionalSearchCodePoint)) return this
    return buildString(length) {
        var offset = 0
        while (offset < this@withoutOptionalArabicMarks.length) {
            val codePoint = this@withoutOptionalArabicMarks.codePointAt(offset)
            if (!isArabicOptionalSearchCodePoint(codePoint)) appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
    }
}

private fun isArabicOptionalSearchCodePoint(codePoint: Int): Boolean =
    codePoint == 0x0640 ||
        codePoint in 0x0610..0x061A ||
        codePoint in 0x064B..0x065F ||
        codePoint == 0x0670 ||
        codePoint in 0x06D6..0x06ED
