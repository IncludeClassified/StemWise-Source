package app.stemwise.data

import org.erdtman.jcs.JsonCanonicalizer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * RFC 8785 / JSON Canonicalization Scheme boundary used by every package digest.
 *
 * Callers must construct the exact normative projection before passing it here;
 * canonicalization defines serialization, not projection membership or array
 * semantics.
 */
object CanonicalJson {
    fun encode(value: JSONObject): ByteArray {
        requireIJsonStrings(value)
        return JsonCanonicalizer(value.toString()).encodedUTF8
    }

    fun encode(rawJson: String): ByteArray {
        // Check literal code units before any parser can replace an isolated surrogate, then
        // inspect decoded JSON strings so escaped forms such as "\\uDEAD" are covered too.
        requireValidUtf16(rawJson)
        requireIJsonStrings(JSONTokener(rawJson).nextValue())
        return JsonCanonicalizer(rawJson).encodedUTF8
    }

    fun sha256(value: JSONObject): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encode(value))
            .toHexString()

    private fun requireIJsonStrings(root: Any?) {
        val pending = ArrayDeque<Any>()
        if (root != null) pending.add(root)
        val visitedContainers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        while (pending.isNotEmpty()) {
            when (val value = pending.removeLast()) {
                is String -> requireValidUtf16(value)
                is Byte, is Short, is Int, is Long -> require((value as Number).toLong() in
                    -9_007_199_254_740_991L..9_007_199_254_740_991L) { "I-JSON integer exceeds exact range" }
                is Number -> require(value.toDouble().isFinite()) { "I-JSON number must be finite" }
                is JSONObject -> {
                    if (!visitedContainers.add(value)) continue
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        requireValidUtf16(key)
                        value.opt(key)?.let(pending::add)
                    }
                }
                is JSONArray -> {
                    if (!visitedContainers.add(value)) continue
                    for (index in 0 until value.length()) {
                        value.opt(index)?.let(pending::add)
                    }
                }
            }
        }
    }

    private fun requireValidUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        "I-JSON strings must not contain an unpaired high surrogate at index $index"
                    }
                    index += 2
                }
                current.isLowSurrogate() -> throw IllegalArgumentException(
                    "I-JSON strings must not contain an unpaired low surrogate at index $index"
                )
                else -> index++
            }
        }
    }
}
