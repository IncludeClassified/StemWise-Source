package app.stemwise

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Bounds allocation before any contract-specific object graph is materialized. */
internal fun readStrictJsonObject(bytes: ByteArray, maximumBytes: Long): JSONObject {
    require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "JSON_SIZE_INVALID" }
    require(!(bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
        bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte())) { "JSON_INVALID" }
    val decoded = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("JSON_UTF8_INVALID", error)
    }
    var nodes = 0
    fun boundedString(value: String, waveformData: Boolean = false): String {
        val maximum = if (waveformData)
            MAX_WAVEFORM_DATA_CHARACTERS else 8 * 1024 * 1024
        require(value.length <= maximum) { "JSON_STRING_LIMIT" }
        return value
    }
    fun read(reader: JsonReader, depth: Int, path: Int = 0): Any {
        require(depth <= 32 && ++nodes <= 1_000_000) { "JSON_RESOURCE_LIMIT" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { output ->
                reader.beginObject()
                val names = hashSetOf<String>()
                while (reader.hasNext()) {
                    val name = boundedString(reader.nextName())
                    require(names.size < 500_000 && names.add(name)) { "JSON_OBJECT_INVALID" }
                    val childPath = when {
                        path == 0 && name == "analysis" -> 1
                        path == 1 && name == "waveform" -> 2
                        path == 2 && name == "levels" -> 3
                        path == 4 && name == "data" -> 5
                        else -> -1
                    }
                    output.put(name, read(reader, depth + 1, childPath))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { output ->
                reader.beginArray()
                while (reader.hasNext()) {
                    require(output.length() < 500_000) { "JSON_ARRAY_LIMIT" }
                    output.put(read(reader, depth + 1, if (path == 3) 4 else -1))
                }
                reader.endArray()
            }
            JsonToken.STRING -> boundedString(reader.nextString(), path == 5)
            JsonToken.NUMBER -> reader.nextString().let { raw ->
                require(raw.length <= 128) { "JSON_NUMBER_LIMIT" }
                if (raw.matches(Regex("-?[0-9]+"))) {
                    val integer = raw.toLongOrNull() ?: error("JSON_INTEGER_UNSAFE")
                    require(integer in -9_007_199_254_740_991L..9_007_199_254_740_991L) { "JSON_INTEGER_UNSAFE" }
                    integer
                } else raw.toDoubleOrNull()?.takeIf(Double::isFinite)
                    ?: error("JSON_NUMBER_INVALID")
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> error("JSON_INVALID")
        }
    }
    return JsonReader(StringReader(decoded)).use { reader ->
        reader.isLenient = false
        val value = read(reader, 0)
        require(value is JSONObject && reader.peek() == JsonToken.END_DOCUMENT) { "JSON_INVALID" }
        value
    }
}
