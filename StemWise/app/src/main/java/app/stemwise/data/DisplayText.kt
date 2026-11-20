package app.stemwise.data

import java.text.BreakIterator
import java.util.Locale

internal fun firstDisplayGrapheme(value: String): String {
    if (value.isEmpty()) return value
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    return value.substring(0, boundaries.following(0))
}

/** Keep the existing UTF-16 character ceiling without cutting a surrogate pair or combining cluster. */
internal fun boundedDisplayText(value: String, maximumCharacters: Int): String {
    require(maximumCharacters >= 0)
    if (value.length <= maximumCharacters) return value
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    val end = if (boundaries.isBoundary(maximumCharacters)) maximumCharacters else boundaries.preceding(maximumCharacters)
    return value.substring(0, maxOf(0, end))
}
