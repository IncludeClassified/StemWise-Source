package app.stemwise.ui

internal fun String.requiredSubstringAfter(marker: String): String {
    check(contains(marker)) { "Missing source boundary: $marker" }
    return substringAfter(marker)
}

internal fun String.requiredSubstringBefore(marker: String): String {
    check(contains(marker)) { "Missing source boundary: $marker" }
    return substringBefore(marker)
}

internal fun String.requiredSourceRegion(startMarker: String, endMarker: String): String {
    val start = indexOf(startMarker)
    check(start >= 0 && indexOf(startMarker, start + startMarker.length) < 0) { "Missing or ambiguous start: $startMarker" }
    val end = indexOf(endMarker, start + startMarker.length)
    check(end > start && indexOf(endMarker, end + endMarker.length) < 0) { "Missing or ambiguous end: $endMarker" }
    return substring(start + startMarker.length, end)
}
