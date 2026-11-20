package app.stemwise.data

/** A reserved sibling is torn only when it is an exact strict prefix of authenticated bytes. */
internal fun interruptedReservedAuthorityWriteIsRecoverable(
    damaged: ByteArray,
    validGenerations: Collection<ByteArray>,
    authorityAuthenticated: Boolean
): Boolean = authorityAuthenticated && validGenerations.any { generation ->
    damaged.size < generation.size && damaged.indices.all { damaged[it] == generation[it] }
}

/**
 * Parses every portable authority copy before any one copy can be promoted over another.
 * Formats without a self-identifying envelope cannot safely classify an invalid peer as torn.
 */
internal fun <T> parsePortableAuthorityCopies(
    copies: Map<String, ByteArray>,
    errorCode: String,
    canRecoverInvalidCopy: (ByteArray, List<T>) -> Boolean = { _, _ -> false },
    parse: (ByteArray) -> T
): List<Pair<ByteArray, T>> {
    val parsed = copies.values.map { bytes -> bytes to runCatching { parse(bytes) } }
    val valid = parsed.mapNotNull { (bytes, result) ->
        result.getOrNull()?.let { bytes to it }
    }
    parsed.filter { it.second.isFailure }.forEach { (bytes, _) ->
        check(canRecoverInvalidCopy(bytes, valid.map { it.second })) { errorCode }
    }
    check(copies.isEmpty() || valid.isNotEmpty()) { errorCode }
    return valid
}
