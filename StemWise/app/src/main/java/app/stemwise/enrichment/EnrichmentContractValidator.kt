package app.stemwise.enrichment

import app.stemwise.integration.MediaIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal const val LYRICS_TOKENIZATION_VERSION = "stemwise.display-words.v2"

/** YouTube identifies the library entry; an optional native marker binds its actual recording. */
internal fun validateEnrichmentSourceIdentity(source: JSONObject) {
    if (source.keys().asSequence().any { it !in setOf("provider", "videoId", "recording") }) {
        throw EnrichmentImportException("JSON_FIELD_UNKNOWN")
    }
    if (source.opt("provider") !is String || source.optString("provider") != "youtube" ||
        source.opt("videoId") !is String || !MediaIdentity.isYoutubeVideoId(source.optString("videoId"))) {
        throw EnrichmentImportException("YOUTUBE_VIDEO_ID_INVALID")
    }
    if (!source.has("recording")) return
    val recording = source.optJSONObject("recording")
        ?: throw EnrichmentImportException("SOURCE_BINDING_CONFLICT")
    fun integer(field: String): Long? = when (val value = recording.opt(field)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
    val trackId = integer("trackId")
    val path = recording.opt("file") as? String
    if (recording.keys().asSequence().toSet() != setOf(
            "recordingKey", "trackId", "file", "sha256", "byteCount", "durationMs") ||
        trackId == null || trackId !in 1L..MAX_RECORDING_TRACK_ID ||
        recording.opt("recordingKey") != "qobuz:$trackId" ||
        path.isNullOrBlank() || path.contains('\\') || path.contains(':') ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
        (recording.opt("sha256") as? String)?.matches(Regex("^[a-f0-9]{64}$")) != true ||
        integer("byteCount")?.let { it in 1..MAX_RECORDING_BYTES } != true ||
        integer("durationMs")?.let { it in 1..app.stemwise.MAX_ENRICHMENT_DURATION_MS } != true) {
        throw EnrichmentImportException("SOURCE_BINDING_CONFLICT")
    }
}

internal fun enrichmentOriginalPath(source: JSONObject, originalName: String): String {
    if (originalName !in app.stemwise.data.SelectedLibraryContract.originalFiles ||
        source.has("recording") && originalName != app.stemwise.data.SelectedLibraryContract.ORIGINAL_FLAC) {
        throw EnrichmentImportException("SOURCE_BINDING_CONFLICT")
    }
    return originalName
}

internal fun validateEnrichmentOriginalContainer(originalName: String, mime: String) {
    if (!app.stemwise.data.canonicalMediaMimeMatches(originalName, mime)) {
        throw EnrichmentImportException("ORIGINAL_CODEC_INVALID")
    }
}

internal fun validateEnrichmentOriginalBinding(
    source: JSONObject,
    originalName: String,
    original: InstalledMediaIdentity,
    durationMs: Long
) {
    validateEnrichmentSourceIdentity(source)
    enrichmentOriginalPath(source, originalName)
    source.optJSONObject("recording")?.let { recording ->
        if (recording.getString("sha256") != original.sha256 ||
            recording.getLong("byteCount") != original.byteLength ||
            kotlin.math.abs(recording.getLong("durationMs") - durationMs) > 1L) {
            throw EnrichmentImportException("SOURCE_BINDING_CONFLICT")
        }
    }
}

internal data class Utf16WordRange(val startUtf16: Int, val endUtf16: Int)
private data class DisplayGraphemeCluster(
    val text: String,
    val startUtf16: Int,
    val endUtf16: Int
)

/** Deterministic display-word tokenizer selected by [LYRICS_TOKENIZATION_VERSION]. */
internal fun displayWordUtf16Ranges(text: String): List<Utf16WordRange> {
    val clusters = displayGraphemeClusters(text)
    return buildList {
        var tokenStart: Int? = null
        var tokenEnd = 0
        var tokenScript: Set<String>? = null
        clusters.forEachIndexed { index, cluster ->
            val following = clusters.getOrNull(index + 1)
            if (
                cluster.text.singleOrNull()?.code?.let { it in INTERNAL_WORD_CONNECTORS } == true &&
                tokenStart != null && following?.isDisplayWord() == true
            ) {
                val followingScript = following.displayWordScript()
                if (tokenScript == null || followingScript == null || tokenScript.intersect(followingScript).isNotEmpty()) {
                    tokenEnd = cluster.endUtf16
                    return@forEachIndexed
                }
            }
            if (cluster.isDisplayWord()) {
                val currentScript = cluster.displayWordScript()
                if (
                    tokenStart != null && tokenScript != null && currentScript != null &&
                    tokenScript.intersect(currentScript).isEmpty()
                ) {
                    add(Utf16WordRange(tokenStart, tokenEnd))
                    tokenStart = null
                    tokenScript = null
                }
                if (tokenStart == null) tokenStart = cluster.startUtf16
                tokenEnd = cluster.endUtf16
                if (currentScript != null) tokenScript = tokenScript?.intersect(currentScript) ?: currentScript
                return@forEachIndexed
            }
            tokenStart?.let { add(Utf16WordRange(it, tokenEnd)) }
            tokenStart = null
            tokenScript = null
        }
        tokenStart?.let { add(Utf16WordRange(it, tokenEnd)) }
    }
}

private fun displayGraphemeClusters(text: String): List<DisplayGraphemeCluster> = buildList {
    var clusterStart = 0
    var previousCodePoint = -1
    var regionalIndicators = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)
        val regional = codePoint in 0x1f1e6..0x1f1ff
        val joinsPrevious = index > clusterStart && (
            isDisplayGraphemeExtend(codePoint) ||
                isDisplayGraphemePrepend(previousCodePoint) ||
                previousCodePoint in JOIN_CONTROLS || codePoint in JOIN_CONTROLS ||
                (regional && regionalIndicators % 2 == 1) ||
                (index - clusterStart == 1 && previousCodePoint == '\r'.code && codePoint == '\n'.code)
            )
        if (!joinsPrevious && index > clusterStart) {
            add(DisplayGraphemeCluster(text.substring(clusterStart, index), clusterStart, index))
            clusterStart = index
            regionalIndicators = 0
        }
        previousCodePoint = codePoint
        regionalIndicators = if (regional) regionalIndicators + 1 else 0
        index += width
    }
    if (clusterStart < text.length) {
        add(DisplayGraphemeCluster(text.substring(clusterStart), clusterStart, text.length))
    }
}

private fun DisplayGraphemeCluster.firstDisplayWordCodePoint(): Int? {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (isDisplayWordCodePoint(codePoint)) return codePoint
        index += Character.charCount(codePoint)
    }
    return null
}

private fun DisplayGraphemeCluster.isDisplayWord(): Boolean =
    firstDisplayWordCodePoint() != null

private fun DisplayGraphemeCluster.displayWordScript(): Set<String>? =
    firstDisplayWordCodePoint()?.let(LyricsUnicode::script)

private fun isDisplayWordCodePoint(codePoint: Int): Boolean = (LyricsUnicode.flags(codePoint) and 1) != 0

private fun isDisplayGraphemeExtend(codePoint: Int): Boolean =
    (LyricsUnicode.flags(codePoint) and 4) != 0 ||
        codePoint in 0xfe00..0xfe0f || codePoint in 0xe0100..0xe01ef ||
        codePoint in 0x1f3fb..0x1f3ff

private fun isDisplayGraphemePrepend(codePoint: Int): Boolean =
    codePoint in 0x0600..0x0605 || codePoint in 0x0890..0x0891 ||
        codePoint in 0x111c2..0x111c3 || codePoint in 0x11a84..0x11a89 ||
        codePoint in DISPLAY_GRAPHEME_PREPEND_SINGLETONS

private val INTERNAL_WORD_CONNECTORS = setOf(
    '\''.code,
    0x02bc,
    0x2019,
    '-'.code,
    0x2010,
    0x2011
)
private val JOIN_CONTROLS = setOf(0x200c, 0x200d)
private val DISPLAY_GRAPHEME_PREPEND_SINGLETONS = setOf(
    0x06dd, 0x070f, 0x08e2, 0x0d4e, 0x110bd, 0x110cd,
    0x113d1, 0x1193f, 0x11941, 0x11a3a, 0x11d46, 0x11f02
)

/**
 * Pure, executable definition of the PC -> Android enrichment JSON contract.
 *
 * Keeping this free of Android framework calls lets the golden PC fixture run
 * through the exact validator used by [SongEnrichmentImporter] in local JVM
 * tests. Transport/container and PNG byte validation remain importer concerns.
 */
internal object EnrichmentContractValidator {
    fun validate(json: JSONObject, hasCover: Boolean) {
        val allowedRoot = setOf(
            "formatVersion", "kind", "source", "generatedAt", "generator",
            "metadata", "artwork", "lyrics", "analysis"
        )
        if (json.keys().asSequence().any { it !in allowedRoot }) fail("JSON_FIELD_UNKNOWN")
        if (
            !isEnrichmentFormatVersionTwo(json.opt("formatVersion")) ||
            json.optString("kind") != "stemwise.song-enrichment"
        ) fail("SCHEMA_UNSUPPORTED")
        val source = json.optJSONObject("source") ?: fail("YOUTUBE_VIDEO_ID_MISSING")
        validateEnrichmentSourceIdentity(source)
        if (!isInstalledUtcTimestamp(json.opt("generatedAt"))) fail("MANIFEST_JSON_INVALID")
        val generator = json.optJSONObject("generator") ?: fail("MANIFEST_JSON_INVALID")
        if (generator.keys().asSequence().any { it !in setOf("name", "version") }) {
            fail("JSON_FIELD_UNKNOWN")
        }
        if (
            generator.opt("name") !is String || generator.opt("version") !is String ||
            generator.optString("name").isBlank() || generator.optString("version").isBlank()
        ) {
            fail("MANIFEST_JSON_INVALID")
        }
        if (
            generator.optString("name") != ENRICHMENT_GENERATOR_NAME ||
            generator.optString("version") != ENRICHMENT_GENERATOR_VERSION
        ) fail("SCHEMA_UNSUPPORTED")
        val generatedSections = enrichmentGeneratedSections(json)
        if (generatedSections.isEmpty()) fail("ENRICHMENT_UPDATE_EMPTY")
        val artwork = generatedSections["artwork"]
        if ((artwork != null) != hasCover) fail("PNG_MISSING")
        artwork?.let {
            if (it.keys().asSequence().any { key -> key !in setOf("file", "kind", "source") }) {
                fail("JSON_FIELD_UNKNOWN")
            }
            if (it.optString("file") != "cover.png") fail("PNG_MISSING")
            if (it.optString("kind") !in ARTWORK_KINDS) fail("PNG_INVALID")
            validateSourceDescriptor(it.optJSONObject("source") ?: fail("PNG_INVALID"))
        }
        validateGeneratedGroups(json)
    }

    fun validateGeneratedGroups(json: JSONObject) {
        val generatedSections = enrichmentGeneratedSections(json)
        generatedSections["metadata"]?.let(::validateMetadata)
        val analysis = generatedSections["analysis"]
        val duration = analysis?.let {
            if (it.opt("status") !is String || it.optString("status") !in CONTENT_STATUSES) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            validateAnalysis(it).also { durationMs ->
                validateWaveform(
                    waveform = it.requiredObject("waveform", "WAVEFORM_INVALID"),
                    durationMs = durationMs
                )
            }
        } ?: -1L
        val nativeDuration = json.getJSONObject("source").optJSONObject("recording")?.getLong("durationMs")
        if (nativeDuration != null && duration > 0L && kotlin.math.abs(nativeDuration - duration) > 1L) {
            fail("TIMELINE_INVALID")
        }
        val timelineDuration = nativeDuration ?: duration
        generatedSections["lyrics"]?.let { validateLyrics(it, timelineDuration) }
        validateFiniteAndTimes(json, timelineDuration)
    }

    fun validateLyrics(lyrics: JSONObject, durationMs: Long) {
        lyrics.requireOnly(
            setOf("status", "reason", "tracks", "tokenizationVersion"),
            "LYRICS_INVALID"
        )
        val status = lyrics.optString("status")
        if (status !in CONTENT_STATUSES) fail("LYRICS_INVALID")
        val reason = if (lyrics.has("reason")) {
            (lyrics.opt("reason") as? String)?.takeIf(String::isNotBlank)
                ?: fail("LYRICS_INVALID")
        } else null
        if (status == "complete" && reason != null) fail("LYRICS_INVALID")
        if (status != "complete" && reason == null) fail("LYRICS_INVALID")
        val tracks = lyrics.optJSONArray("tracks")?.objectsExact("LYRICS_INVALID")
            ?: fail("LYRICS_INVALID")
        if (status == "unavailable" && tracks.isNotEmpty()) fail("LYRICS_INVALID")
        if (status != "unavailable" && tracks.isEmpty()) fail("LYRICS_INVALID")
        val ids = tracks.map { it.optString("id") }
        if (ids.any { !TRACK_ID.matches(it) } || ids.size != ids.toSet().size) {
            fail("LYRICS_INVALID")
        }
        val originals = tracks.filter { it.optString("kind") == "original" }
            .map { it.getString("id") }
            .toSet()
        if (status != "unavailable" && originals.size != 1) fail("LYRICS_INVALID")
        if (tracks.count { it.optString("kind") == "translation" } > 1 ||
            tracks.count { it.optString("kind") == "transliteration" } > 1 ||
            tracks.any { it.optString("kind") == "translation" && it.optString("language") != "en" } ||
            tracks.any { it.optString("kind") == "transliteration" &&
                !it.optString("language").endsWith("-fonipa") }) fail("LYRICS_INVALID")
        var hasWords = false
        tracks.forEach { track ->
            track.requireOnly(
                setOf(
                    "id", "kind", "language", "format", "source", "basedOnTrackId",
                    "componentLanguages", "script", "text", "lines"
                ),
                "LYRICS_INVALID"
            )
            val kind = track.optString("kind")
            if (kind !in LYRIC_KINDS) fail("LYRICS_INVALID")
            if (
                track.opt("id") !is String || track.opt("kind") !is String ||
                track.opt("language") !is String || track.opt("format") !is String ||
                track.optString("language").isBlank()
            ) fail("LYRICS_INVALID")
            validateSourceDescriptor(track.optJSONObject("source") ?: fail("LYRICS_INVALID"))
            if (track.has("script")) {
                val script = track.opt("script") as? String ?: fail("LYRICS_INVALID")
                if (!SCRIPT.matches(script)) fail("LYRICS_INVALID")
            }
            track.optionalArray("componentLanguages", "LYRICS_INVALID")?.let { values ->
                val languages = values.strictStrings("LYRICS_INVALID")
                if (
                    kind != "original" || languages.size < 2 ||
                    languages.any {
                        it.isBlank() || it.codePointCount(0, it.length) < 2
                    } ||
                    languages.size != languages.toSet().size
                ) {
                    fail("LYRICS_INVALID")
                }
            }
            if (kind == "original" && track.has("basedOnTrackId")) fail("LYRICS_INVALID")
            if (kind != "original" && track.optString("basedOnTrackId") !in originals) {
                fail("LYRICS_INVALID")
            }
            when (track.optString("format")) {
                "plain" -> if (
                    track.opt("text") !is String || track.optString("text").isBlank() ||
                    track.has("lines")
                ) fail("LYRICS_INVALID")
                "synced" -> {
                    if (track.has("text")) fail("LYRICS_INVALID")
                    val lines = track.optJSONArray("lines")
                        ?.objectsExact("LYRICS_INVALID")
                        .orEmpty()
                    if (lines.isEmpty()) fail("LYRICS_INVALID")
                    var previous = -1L
                    lines.forEach { line ->
                        line.requireOnly(
                            setOf("startMs", "endMs", "text", "words"),
                            "LYRICS_INVALID"
                        )
                        val start = line.requiredInteger("startMs", "LYRICS_INVALID")
                        val end = line.requiredInteger("endMs", "LYRICS_INVALID")
                        val text = line.opt("text") as? String ?: fail("LYRICS_INVALID")
                        if (
                            start < 0L || (previous >= 0L && start < previous) ||
                            (durationMs > 0L && start >= durationMs) ||
                            end <= start || (durationMs > 0L && end > durationMs) ||
                            text.isBlank()
                        ) fail("LYRICS_INVALID")
                        line.optionalArray("words", "LYRICS_INVALID")?.let { values ->
                            if (kind != "original") fail("LYRICS_INVALID")
                            val words = values.objectsExact("LYRICS_INVALID")
                            val expectedRanges = displayWordUtf16Ranges(text)
                            if (words.isEmpty() || words.size != expectedRanges.size) {
                                fail("LYRICS_INVALID")
                            }
                            var previousWordStartMs = start
                            words.forEachIndexed { index, word ->
                                word.requireOnly(
                                    setOf("startUtf16", "endUtf16", "startMs", "endMs"),
                                    "LYRICS_INVALID"
                                )
                                val expected = expectedRanges[index]
                                val wordStartUtf16 = word.requiredInteger(
                                    "startUtf16",
                                    "LYRICS_INVALID"
                                )
                                val wordEndUtf16 = word.requiredInteger(
                                    "endUtf16",
                                    "LYRICS_INVALID"
                                )
                                val wordStartMs = word.requiredInteger("startMs", "LYRICS_INVALID")
                                val wordEndMs = word.requiredInteger("endMs", "LYRICS_INVALID")
                                if (
                                    wordStartUtf16 != expected.startUtf16.toLong() ||
                                    wordEndUtf16 != expected.endUtf16.toLong() ||
                                    wordStartMs < previousWordStartMs || wordEndMs <= wordStartMs ||
                                    wordEndMs > end
                                ) fail("LYRICS_INVALID")
                                previousWordStartMs = wordStartMs
                            }
                            hasWords = true
                        }
                        previous = start
                    }
                }
                "regional" -> {
                    if (track.has("text")) fail("LYRICS_INVALID")
                    hasWords = validateRegionalLines(track, durationMs) || hasWords
                }
                else -> fail("LYRICS_INVALID")
            }
        }
        val timedTracks = tracks.filter { it.optString("format") in setOf("synced", "regional") }
            .associateBy { it.getString("id") }
        fun placement(track: JSONObject, line: JSONObject): JSONObject =
            if (track.getString("format") == "regional") line.getJSONObject("timing")
            else JSONObject().put("status", "supported").put("startMs", line.getLong("startMs"))
                .put("endMs", line.getLong("endMs"))
        fun occurrence(track: JSONObject, line: JSONObject): String =
            if (track.getString("format") == "regional") line.getString("occurrenceStatus") else "supported"
        tracks.filter {
            it.optString("kind") != "original" && it.optString("format") in setOf("synced", "regional")
        }.forEach { track ->
            val original = timedTracks[track.getString("basedOnTrackId")] ?: fail("LYRICS_INVALID")
            val lines = track.getJSONArray("lines").objectsExact("LYRICS_INVALID")
            val originalLines = original.getJSONArray("lines").objectsExact("LYRICS_INVALID")
            if (lines.size != originalLines.size) fail("LYRICS_INVALID")
            lines.zip(originalLines).forEach { (line, originalLine) ->
                if (occurrence(track, line) == "supported" && occurrence(original, originalLine) != "supported") {
                    fail("LYRICS_INVALID")
                }
                val timing = placement(track, line)
                val source = placement(original, originalLine)
                if (timing.getString("status") != "none" &&
                    (source.getString("status") == "none" || timing.getLong("startMs") != source.getLong("startMs") ||
                        timing.getLong("endMs") != source.getLong("endMs") ||
                        timing.getString("status") == "supported" && source.getString("status") != "supported")) {
                    fail("LYRICS_INVALID")
                }
            }
        }
        if (status == "complete" && tracks.any { it.getString("format") == "regional" }) {
            tracks.forEach { track ->
                if (track.getString("format") !in setOf("synced", "regional")) fail("LYRICS_INVALID")
                track.getJSONArray("lines").objectsExact("LYRICS_INVALID").forEach { line ->
                    if (track.getString("kind") == "original" && !line.has("words")) fail("LYRICS_INVALID")
                    if (track.getString("format") == "regional" &&
                        (line.getString("contentStatus") != "admitted" || line.getString("occurrenceStatus") != "supported" ||
                            line.getJSONObject("timing").getString("status") == "none" ||
                            line.optJSONArray("words")?.objectsExact("LYRICS_INVALID")?.any {
                                it.getJSONObject("timing").getString("status") == "none"
                            } == true)) fail("LYRICS_INVALID")
                }
            }
        }
        if (
            hasWords || lyrics.has("tokenizationVersion")
        ) {
            if (lyrics.opt("tokenizationVersion") !is String ||
                lyrics.optString("tokenizationVersion") != LYRICS_TOKENIZATION_VERSION
            ) fail("LYRICS_INVALID")
        }
    }

    private fun validateRegionalTiming(value: JSONObject, durationMs: Long): Pair<Long, Long>? {
        val status = value.requiredString("status", "LYRICS_INVALID")
        val keys = when (status) {
            "supported" -> setOf("status", "startMs", "endMs")
            "estimated" -> setOf("status", "startMs", "endMs", "basis")
            "none" -> setOf("status", "reason")
            else -> fail("LYRICS_INVALID")
        }
        if (value.keys().asSequence().toSet() != keys) fail("LYRICS_INVALID")
        if (status == "none") {
            value.optionalNonBlankString("reason", "LYRICS_INVALID")
            return null
        }
        if (status == "estimated") value.optionalNonBlankString("basis", "LYRICS_INVALID")
        val start = value.requiredInteger("startMs", "LYRICS_INVALID")
        val end = value.requiredInteger("endMs", "LYRICS_INVALID")
        if (start < 0L || end <= start || end > MAX_ANALYSIS_DURATION_MS ||
            (durationMs > 0L && end > durationMs)) fail("LYRICS_INVALID")
        return start to end
    }

    private fun validateRegionalLines(track: JSONObject, durationMs: Long): Boolean {
        val lines = track.requiredArray("lines", "LYRICS_INVALID").objectsExact("LYRICS_INVALID")
        if (lines.isEmpty()) fail("LYRICS_INVALID")
        var previousStart = -1L
        var hasWords = false
        lines.forEach { line ->
            line.requireOnly(setOf("text", "contentStatus", "occurrenceStatus", "timing", "words"), "LYRICS_INVALID")
            val text = line.requiredString("text", "LYRICS_INVALID")
            val content = line.requiredString("contentStatus", "LYRICS_INVALID")
            val occurrence = line.requiredString("occurrenceStatus", "LYRICS_INVALID")
            if (text.isBlank() || content !in setOf("admitted", "source-only", "disputed") ||
                occurrence !in setOf("supported", "unknown")) fail("LYRICS_INVALID")
            val timing = line.requiredObject("timing", "LYRICS_INVALID")
            val interval = validateRegionalTiming(timing, durationMs)
            if (interval != null) {
                if (content != "admitted" || occurrence != "supported" || interval.first < previousStart) {
                    fail("LYRICS_INVALID")
                }
                previousStart = interval.first
            }
            line.optionalArray("words", "LYRICS_INVALID")?.let { values ->
                hasWords = true
                val words = values.objectsExact("LYRICS_INVALID")
                val ranges = displayWordUtf16Ranges(text)
                if (words.isEmpty() || words.size != ranges.size) fail("LYRICS_INVALID")
                var previousWordStart = interval?.first ?: -1L
                words.forEachIndexed { index, word ->
                    word.requireOnly(setOf("startUtf16", "endUtf16", "timing"), "LYRICS_INVALID")
                    if (word.requiredInteger("startUtf16", "LYRICS_INVALID") != ranges[index].startUtf16.toLong() ||
                        word.requiredInteger("endUtf16", "LYRICS_INVALID") != ranges[index].endUtf16.toLong()) {
                        fail("LYRICS_INVALID")
                    }
                    val wordTiming = word.requiredObject("timing", "LYRICS_INVALID")
                    val wordInterval = validateRegionalTiming(wordTiming, durationMs)
                    if (wordInterval != null) {
                        if (interval == null || wordInterval.first < previousWordStart || wordInterval.second > interval.second ||
                            (wordTiming.getString("status") == "supported" && timing.getString("status") != "supported")) {
                            fail("LYRICS_INVALID")
                        }
                        previousWordStart = wordInterval.first
                    }
                }
            }
        }
        return hasWords
    }

    fun validateFiniteAndTimes(value: Any?, durationMs: Long, key: String? = null) {
        when (value) {
            is JSONObject -> value.keys().forEach { child ->
                validateFiniteAndTimes(value.get(child), durationMs, child)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                validateFiniteAndTimes(value.get(index), durationMs, key)
            }
            is Double -> if (!value.isFinite()) fail("TIMELINE_INVALID")
            is Float -> if (!value.isFinite()) fail("TIMELINE_INVALID")
            is Number -> if (key in TIMELINE_POSITION_KEYS) {
                if (value !is Long && value !is Int) fail("TIMELINE_INVALID")
                val time = value.toLong()
                if (time < 0L || (durationMs >= 0L && time > durationMs)) {
                    fail("TIMELINE_INVALID")
                }
            }
        }
    }

    private fun validateMetadata(metadata: JSONObject) {
        metadata.requireOnly(
            setOf(
                "matchStatus", "title", "artists", "album", "genres", "description",
                "explicit", "recordLabel", "copyright", "credits", "identifiers",
                "links", "sources"
            ),
            "JSON_FIELD_UNKNOWN"
        )
        val artists = metadata.optJSONArray("artists") ?: fail("MANIFEST_JSON_INVALID")
        val sources = metadata.optJSONArray("sources") ?: fail("MANIFEST_JSON_INVALID")
        val artistNames = artists.strictStrings("MANIFEST_JSON_INVALID")
        if (
            metadata.opt("matchStatus") !is String || metadata.opt("title") !is String ||
            metadata.optString("matchStatus") !in setOf("verified", "youtube-fallback") ||
            metadata.optString("title").isBlank() ||
            artistNames.isEmpty() || artistNames.any { it.isBlank() } ||
            artistNames.size != artistNames.toSet().size ||
            sources.length() == 0
        ) fail("MANIFEST_JSON_INVALID")
        sources.objectsExact("MANIFEST_JSON_INVALID").forEach(::validateSourceDescriptor)
        if (metadata.has("album") && metadata.optJSONObject("album") == null) {
            fail("MANIFEST_JSON_INVALID")
        }
        metadata.optJSONObject("album")?.let { album ->
            album.requireOnly(
                setOf(
                    "title", "type", "artists", "releaseDate", "trackNumber", "trackTotal",
                    "discNumber", "discTotal"
                ),
                "JSON_FIELD_UNKNOWN"
            )
            if (album.opt("title") !is String || album.optString("title").isBlank()) {
                fail("MANIFEST_JSON_INVALID")
            }
            if (album.has("type")) {
                val type = album.requiredString("type", "MANIFEST_JSON_INVALID")
                if (type !in ALBUM_TYPES) fail("MANIFEST_JSON_INVALID")
            }
            album.optionalArray("artists", "MANIFEST_JSON_INVALID")?.let { albumArtists ->
                val names = albumArtists.strictStrings("MANIFEST_JSON_INVALID")
                if (
                    names.isEmpty() || names.any { it.isBlank() }
                ) fail("MANIFEST_JSON_INVALID")
            }
            if (album.has("releaseDate")) {
                val releaseDate = album.requiredString("releaseDate", "MANIFEST_JSON_INVALID")
                if (!RELEASE_DATE.matches(releaseDate)) fail("MANIFEST_JSON_INVALID")
            }
            val albumIntegers = listOf(
                "trackNumber", "trackTotal", "discNumber", "discTotal"
            ).associateWith { key -> album.optionalInteger(key, "MANIFEST_JSON_INVALID") }
            if (albumIntegers.values.filterNotNull().any { it < 1L }) {
                fail("MANIFEST_JSON_INVALID")
            }
            val trackNumber = albumIntegers.getValue("trackNumber")
            val trackTotal = albumIntegers.getValue("trackTotal")
            if (trackNumber != null && trackTotal != null && trackNumber > trackTotal) {
                fail("MANIFEST_JSON_INVALID")
            }
            val discNumber = albumIntegers.getValue("discNumber")
            val discTotal = albumIntegers.getValue("discTotal")
            if (discNumber != null && discTotal != null && discNumber > discTotal) {
                fail("MANIFEST_JSON_INVALID")
            }
        }
        if (metadata.has("genres") && metadata.optJSONArray("genres") == null) {
            fail("MANIFEST_JSON_INVALID")
        }
        metadata.optJSONArray("genres")?.let { genres ->
            val values = genres.strictStrings("MANIFEST_JSON_INVALID")
            if (
                values.isEmpty() || values.any { it.isBlank() } ||
                values.size != values.toSet().size
            ) fail("MANIFEST_JSON_INVALID")
        }
        listOf("description", "recordLabel", "copyright").forEach { key ->
            if (metadata.has(key) && (
                    metadata.opt(key) !is String || metadata.optString(key).isBlank()
                )
            ) fail("MANIFEST_JSON_INVALID")
        }
        if (metadata.has("explicit") && metadata.opt("explicit") !is Boolean) {
            fail("MANIFEST_JSON_INVALID")
        }
        if (metadata.has("credits") && metadata.optJSONArray("credits") == null) {
            fail("MANIFEST_JSON_INVALID")
        }
        metadata.optJSONArray("credits")?.let { credits ->
            if (credits.length() == 0) fail("MANIFEST_JSON_INVALID")
            credits.objectsExact("MANIFEST_JSON_INVALID").forEach { credit ->
                credit.requireOnly(setOf("name", "roles"), "JSON_FIELD_UNKNOWN")
                val roles = credit.optJSONArray("roles") ?: fail("MANIFEST_JSON_INVALID")
                val roleNames = roles.strictStrings("MANIFEST_JSON_INVALID")
                if (
                    credit.opt("name") !is String || credit.optString("name").isBlank() ||
                    roleNames.isEmpty() || roleNames.any { it.isBlank() } ||
                    roleNames.size != roleNames.toSet().size
                ) fail("MANIFEST_JSON_INVALID")
            }
        }
        if (metadata.has("identifiers") && metadata.optJSONObject("identifiers") == null) {
            fail("MANIFEST_JSON_INVALID")
        }
        metadata.optJSONObject("identifiers")?.let { identifiers ->
            identifiers.requireOnly(IDENTIFIER_FIELDS, "JSON_FIELD_UNKNOWN")
            if (identifiers.length() == 0) fail("MANIFEST_JSON_INVALID")
            identifiers.keys().asSequence().forEach { key ->
                val value = identifiers.requiredString(key, "MANIFEST_JSON_INVALID")
                if (!IDENTIFIER_PATTERNS.getValue(key).matches(value)) {
                    fail("MANIFEST_JSON_INVALID")
                }
            }
        }
        if (metadata.has("links") && metadata.optJSONArray("links") == null) {
            fail("MANIFEST_JSON_INVALID")
        }
        metadata.optJSONArray("links")?.let { links ->
            if (links.length() == 0) fail("MANIFEST_JSON_INVALID")
            links.objectsExact("MANIFEST_JSON_INVALID").forEach { link ->
                link.requireOnly(setOf("type", "url"), "JSON_FIELD_UNKNOWN")
                val type = link.requiredString("type", "MANIFEST_JSON_INVALID")
                if (!LINK_TYPE.matches(type)) fail("MANIFEST_JSON_INVALID")
                validateHttpsUrl(link.requiredString("url", "MANIFEST_JSON_INVALID"))
            }
        }
    }

    private fun validateSourceDescriptor(source: JSONObject) {
        source.requireOnly(setOf("name", "url"), "JSON_FIELD_UNKNOWN")
        if (source.opt("name") !is String || source.optString("name").isBlank()) {
            fail("MANIFEST_JSON_INVALID")
        }
        if (source.has("url") && source.opt("url") !is String) fail("MANIFEST_JSON_INVALID")
        if (source.has("url")) validateHttpsUrl(source.optString("url"))
    }

    private fun validateHttpsUrl(value: String) {
        if (!isValidEnrichmentHttpsUrl(value)) {
            fail("MANIFEST_JSON_INVALID")
        }
    }

    private fun validateAnalysis(analysis: JSONObject): Long {
        analysis.requireOnly(
            setOf(
                "status", "durationMs", "rhythm", "key", "structure", "tuning",
                "loudness", "waveform", "diagnostics", "toolRuns"
            ),
            "JSON_FIELD_UNKNOWN"
        )
        val required = setOf(
            "status", "rhythm", "key", "structure", "tuning", "loudness",
            "waveform", "toolRuns"
        )
        if (required.any { !analysis.has(it) }) fail("ANALYSIS_STATUS_INVALID")
        listOf("rhythm", "key", "structure", "tuning", "loudness", "waveform")
            .forEach { analysis.requiredObject(it, "ANALYSIS_STATUS_INVALID") }
        analysis.requiredArray("toolRuns", "TOOL_RUN_INVALID")
        analysis.optionalObject("diagnostics", "ANALYSIS_STATUS_INVALID")
        val duration = analysis.optionalInteger("durationMs", "TIMELINE_INVALID") ?: -1L
        if (duration != -1L && duration !in 1..MAX_ANALYSIS_DURATION_MS) {
            fail("TIMELINE_INVALID")
        }
        if (
            analysis.getJSONObject("waveform").optString("status") != "unavailable" &&
            duration <= 0L
        ) fail("TIMELINE_INVALID")
        validateFeatureStatus(analysis.getJSONObject("rhythm"), RHYTHM_STATUSES)
        validateFeatureStatus(analysis.getJSONObject("key"), KEY_STATUSES)
        validateFeatureStatus(analysis.getJSONObject("structure"), CONTENT_STATUSES)
        validateFeatureStatus(analysis.getJSONObject("tuning"), KEY_STATUSES)
        validateFeatureStatus(analysis.getJSONObject("loudness"), CONTENT_STATUSES)
        validateFeatureStatus(analysis.getJSONObject("waveform"), CONTENT_STATUSES)
        validateAnalysisShapes(analysis)
        validateAnalysisCoherence(analysis, duration)
        validateTimedObjects(
            analysis.getJSONObject("rhythm").optJSONArray("beats"),
            "timeMs",
            duration,
            requireRanges = false
        )
        validateTimedObjects(
            analysis.getJSONObject("rhythm").optJSONArray("tempoSegments"),
            "startMs",
            duration,
            requireRanges = true
        )
        validateTimedObjects(
            analysis.getJSONObject("rhythm").optJSONArray("meterSegments"),
            "startMs",
            duration,
            requireRanges = true
        )
        validateTimedObjects(
            analysis.getJSONObject("structure").optJSONArray("sections"),
            "startMs",
            duration,
            requireRanges = true
        )
        validateDiagnosticTimeline(analysis, duration)
        val runs = analysis.getJSONArray("toolRuns").objectsExact("TOOL_RUN_INVALID")
        if (runs.size != EXPECTED_TOOL_RUNS.size) fail("TOOL_RUN_INVALID")
        val names = runs.map { it.optString("name") }
        if (names.toSet() != EXPECTED_TOOL_RUNS || names.size != names.toSet().size) {
            fail("TOOL_RUN_INVALID")
        }
        runs.forEach { run ->
            run.requireOnly(
                setOf("name", "status", "version", "model", "profile", "warnings", "reason"),
                "JSON_FIELD_UNKNOWN"
            )
            val name = run.requiredString("name", "TOOL_RUN_INVALID")
            val status = run.requiredString("status", "TOOL_RUN_INVALID")
            if (name !in EXPECTED_TOOL_RUNS || status !in TOOL_RUN_STATUSES) {
                fail("TOOL_RUN_INVALID")
            }
            listOf("version", "model", "profile", "reason").forEach { key ->
                run.optionalNonBlankString(key, "TOOL_RUN_INVALID")
            }
            run.optionalArray("warnings", "TOOL_RUN_INVALID")?.let { warnings ->
                val values = warnings.nonBlankStrings()
                if (
                    warnings.length() == 0 || !warnings.allStrings() ||
                    values.size != warnings.length() || values.toSet().size != warnings.length()
                ) fail("TOOL_RUN_INVALID")
            }
            if (status in setOf("complete", "failed") && !run.has("version")) {
                fail("TOOL_RUN_INVALID")
            }
            if (status != "complete" && !run.has("reason")) {
                fail("TOOL_RUN_INVALID")
            }
            if (status == "complete" && run.has("reason")) {
                fail("TOOL_RUN_INVALID")
            }
        }
        if (
            analysis.getString("status") == "complete" &&
            runs.any { it.getString("status") != "complete" }
        ) fail("ANALYSIS_STATUS_INVALID")
        return duration
    }

    private fun validateAnalysisShapes(analysis: JSONObject) {
        val rhythm = analysis.getJSONObject("rhythm")
        rhythm.requireOnly(
            setOf(
                "status", "reason", "bpm", "alternativeBpms", "meter", "beats",
                "tempoSegments", "meterSegments"
            ),
            "JSON_FIELD_UNKNOWN"
        )
        rhythm.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        rhythm.optionalNumber("bpm", "ANALYSIS_STATUS_INVALID")?.let {
            if (it <= 0.0 || it > 600.0) fail("ANALYSIS_STATUS_INVALID")
        }
        rhythm.optionalArray("alternativeBpms", "ANALYSIS_STATUS_INVALID")?.let { values ->
            if (values.length() == 0) fail("ANALYSIS_STATUS_INVALID")
            values.strictNumbers("ANALYSIS_STATUS_INVALID").also { bpms ->
                if (bpms.size != bpms.toSet().size) fail("ANALYSIS_STATUS_INVALID")
            }
        }?.forEach {
            if (it <= 0.0 || it > 600.0) fail("ANALYSIS_STATUS_INVALID")
        }
        rhythm.optionalObject("meter", "ANALYSIS_STATUS_INVALID")?.let { meter ->
            meter.requireOnly(setOf("numerator", "denominator"), "JSON_FIELD_UNKNOWN")
            validateMeter(meter)
        }
        rhythm.optionalArray("beats", "TIMELINE_INVALID")?.also {
            if (it.length() == 0) fail("TIMELINE_INVALID")
        }?.objectsExact("TIMELINE_INVALID")?.forEach { beat ->
            beat.requireOnly(
                setOf("timeMs", "isDownbeat", "beatInBar", "barIndex"),
                "JSON_FIELD_UNKNOWN"
            )
            beat.requiredInteger("timeMs", "TIMELINE_INVALID")
            if (beat.opt("isDownbeat") !is Boolean) fail("TIMELINE_INVALID")
            if (beat.optionalInteger("beatInBar", "TIMELINE_INVALID")?.let { it <= 0L } == true) {
                fail("TIMELINE_INVALID")
            }
            if (beat.optionalInteger("barIndex", "TIMELINE_INVALID")?.let { it < 0L } == true) {
                fail("TIMELINE_INVALID")
            }
        }
        rhythm.optionalArray("tempoSegments", "TIMELINE_INVALID")?.also {
            if (it.length() == 0) fail("TIMELINE_INVALID")
        }?.objectsExact("TIMELINE_INVALID")
            ?.forEach { segment ->
                segment.requireOnly(setOf("startMs", "endMs", "bpm"), "JSON_FIELD_UNKNOWN")
                segment.requiredInteger("startMs", "TIMELINE_INVALID")
                segment.requiredInteger("endMs", "TIMELINE_INVALID")
                val bpm = segment.requiredNumber("bpm", "ANALYSIS_STATUS_INVALID")
                if (bpm <= 0.0 || bpm > 600.0) {
                    fail("ANALYSIS_STATUS_INVALID")
                }
            }
        rhythm.optionalArray("meterSegments", "TIMELINE_INVALID")?.also {
            if (it.length() == 0) fail("TIMELINE_INVALID")
        }?.objectsExact("TIMELINE_INVALID")
            ?.forEach { segment ->
                segment.requireOnly(
                    setOf("startMs", "endMs", "numerator", "denominator"),
                    "JSON_FIELD_UNKNOWN"
                )
                segment.requiredInteger("startMs", "TIMELINE_INVALID")
                segment.requiredInteger("endMs", "TIMELINE_INVALID")
                validateMeter(segment)
            }

        val key = analysis.getJSONObject("key")
        key.requireOnly(
            setOf("status", "reason", "tonic", "mode", "confidence", "alternatives"),
            "JSON_FIELD_UNKNOWN"
        )
        key.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        key.optionalNonBlankString("tonic", "ANALYSIS_STATUS_INVALID")
        key.optionalNonBlankString("mode", "ANALYSIS_STATUS_INVALID")
        validateTonicMode(key, optional = true)
        key.optionalArray("alternatives", "ANALYSIS_STATUS_INVALID")?.also {
            if (it.length() == 0) fail("ANALYSIS_STATUS_INVALID")
        }?.objectsExact("ANALYSIS_STATUS_INVALID")
            ?.forEach { alternative ->
                alternative.requireOnly(setOf("tonic", "mode", "confidence"), "JSON_FIELD_UNKNOWN")
                validateTonicMode(alternative, optional = false)
            }

        val structure = analysis.getJSONObject("structure")
        structure.requireOnly(setOf("status", "reason", "sections"), "JSON_FIELD_UNKNOWN")
        structure.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        structure.optionalArray("sections", "TIMELINE_INVALID")?.also {
            if (it.length() == 0) fail("TIMELINE_INVALID")
        }?.objectsExact("TIMELINE_INVALID")?.forEach { section ->
            section.requireOnly(
                setOf("startMs", "endMs", "label", "confidence"),
                "JSON_FIELD_UNKNOWN"
            )
            section.requiredInteger("startMs", "TIMELINE_INVALID")
            section.requiredInteger("endMs", "TIMELINE_INVALID")
            if (section.requiredString("label", "ANALYSIS_STATUS_INVALID").isBlank()) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            validateConfidence(section, "confidence")
        }

        val tuning = analysis.getJSONObject("tuning")
        tuning.requireOnly(
            setOf("status", "reason", "frequencyHz", "centsFromA440", "confidence"),
            "JSON_FIELD_UNKNOWN"
        )
        tuning.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        tuning.optionalNumber("frequencyHz", "ANALYSIS_STATUS_INVALID")?.let {
            if (it <= 0.0) fail("ANALYSIS_STATUS_INVALID")
        }
        tuning.optionalNumber("centsFromA440", "ANALYSIS_STATUS_INVALID")
        validateConfidence(tuning, "confidence")

        val loudness = analysis.getJSONObject("loudness")
        loudness.requireOnly(
            setOf(
                "status", "reason", "integratedLufs", "loudnessRangeLu", "truePeakDbtp",
                "descriptors"
            ),
            "JSON_FIELD_UNKNOWN"
        )
        loudness.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        listOf("integratedLufs", "loudnessRangeLu", "truePeakDbtp").forEach { key ->
            loudness.optionalNumber(key, "ANALYSIS_STATUS_INVALID")
        }
        loudness.optionalArray("descriptors", "ANALYSIS_STATUS_INVALID")?.also {
            if (it.length() == 0) fail("ANALYSIS_STATUS_INVALID")
        }?.objectsExact("ANALYSIS_STATUS_INVALID")
            ?.forEach { descriptor ->
                descriptor.requireOnly(setOf("id", "value", "unit"), "JSON_FIELD_UNKNOWN")
                if (
                    descriptor.requiredString("id", "ANALYSIS_STATUS_INVALID").isBlank() ||
                    descriptor.requiredString("unit", "ANALYSIS_STATUS_INVALID").isBlank()
                ) fail("ANALYSIS_STATUS_INVALID")
                descriptor.requiredNumber("value", "ANALYSIS_STATUS_INVALID")
            }

        analysis.optionalObject("diagnostics", "ANALYSIS_STATUS_INVALID")?.let { diagnostics ->
            diagnostics.requireOnly(setOf("allInOneRhythm"), "JSON_FIELD_UNKNOWN")
            val allInOne = diagnostics.requiredObject("allInOneRhythm", "ANALYSIS_STATUS_INVALID")
            allInOne.requireOnly(
                setOf("status", "reason", "bpm", "beatsMs", "downbeatsMs"),
                "JSON_FIELD_UNKNOWN"
            )
            val diagnosticStatus = allInOne.requiredString("status", "ANALYSIS_STATUS_INVALID")
            if (diagnosticStatus !in setOf("complete", "partial")) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            allInOne.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
            if (diagnosticStatus == "partial" && !allInOne.has("reason")) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            if (diagnosticStatus == "complete" && allInOne.has("reason")) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            allInOne.optionalNumber("bpm", "ANALYSIS_STATUS_INVALID")?.let {
                if (it <= 0.0 || it > 600.0) fail("ANALYSIS_STATUS_INVALID")
            }
            listOf("beatsMs", "downbeatsMs").forEach { key ->
                allInOne.optionalArray(key, "TIMELINE_INVALID")?.let { values ->
                    if (values.length() == 0) fail("TIMELINE_INVALID")
                    values.strictIntegers("TIMELINE_INVALID")
                }
            }
        }
    }

    private fun validateAnalysisCoherence(analysis: JSONObject, durationMs: Long) {
        val overallStatus = analysis.getString("status")
        if (overallStatus != "unavailable" && durationMs <= 0L) {
            fail("ANALYSIS_STATUS_INVALID")
        }

        val rhythm = analysis.getJSONObject("rhythm")
        val rhythmStatus = rhythm.getString("status")
        val rhythmData = setOf(
            "bpm", "alternativeBpms", "meter", "beats", "tempoSegments", "meterSegments"
        )
        when (rhythmStatus) {
            "complete" -> if (
                !rhythm.has("bpm") || rhythm.optJSONArray("beats")?.length()?.let { it > 0 } != true
            ) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            "partial" -> if (rhythmData.none(rhythm::has)) fail("ANALYSIS_STATUS_INVALID")
            "unavailable" -> if (rhythmData.any(rhythm::has)) fail("ANALYSIS_STATUS_INVALID")
        }

        val key = analysis.getJSONObject("key")
        val keyStatus = key.getString("status")
        val keyData = setOf("tonic", "mode", "confidence", "alternatives")
        if (keyStatus in setOf("complete", "uncertain")) {
            if (!key.has("tonic") || !key.has("mode")) fail("ANALYSIS_STATUS_INVALID")
        } else if (keyData.any(key::has)) {
            fail("ANALYSIS_STATUS_INVALID")
        }

        val structure = analysis.getJSONObject("structure")
        val structureStatus = structure.getString("status")
        if (structureStatus == "unavailable") {
            if (structure.has("sections")) fail("ANALYSIS_STATUS_INVALID")
        } else if (structure.optJSONArray("sections")?.length()?.let { it > 0 } != true) {
            fail("ANALYSIS_STATUS_INVALID")
        }

        val tuning = analysis.getJSONObject("tuning")
        val tuningStatus = tuning.getString("status")
        val tuningData = setOf("frequencyHz", "centsFromA440", "confidence")
        if (tuningStatus in setOf("complete", "uncertain")) {
            if (!tuning.has("frequencyHz") || !tuning.has("centsFromA440")) {
                fail("ANALYSIS_STATUS_INVALID")
            }
        } else if (tuningData.any(tuning::has)) {
            fail("ANALYSIS_STATUS_INVALID")
        }

        val loudness = analysis.getJSONObject("loudness")
        val loudnessStatus = loudness.getString("status")
        val loudnessData = setOf(
            "integratedLufs", "loudnessRangeLu", "truePeakDbtp", "descriptors"
        )
        when (loudnessStatus) {
            "complete" -> if (
                !loudness.has("integratedLufs") || !loudness.has("loudnessRangeLu")
            ) fail("ANALYSIS_STATUS_INVALID")
            "partial" -> if (loudnessData.none(loudness::has)) fail("ANALYSIS_STATUS_INVALID")
            "unavailable" -> if (loudnessData.any(loudness::has)) {
                fail("ANALYSIS_STATUS_INVALID")
            }
        }

        analysis.optJSONObject("diagnostics")?.getJSONObject("allInOneRhythm")?.let { diagnostic ->
            val status = diagnostic.getString("status")
            val usable = diagnostic.has("bpm") || diagnostic.optJSONArray("beatsMs")?.length() != null ||
                diagnostic.optJSONArray("downbeatsMs")?.length() != null
            if (
                status == "complete" &&
                (
                    !diagnostic.has("bpm") ||
                    diagnostic.optJSONArray("beatsMs")?.length()?.let { it > 0 } != true
                )
            ) fail("ANALYSIS_STATUS_INVALID")
            if (status == "partial" && !usable) fail("ANALYSIS_STATUS_INVALID")
        }

        val featureStatuses = listOf(
            rhythmStatus,
            keyStatus,
            structureStatus,
            tuningStatus,
            loudnessStatus,
            analysis.getJSONObject("waveform").getString("status")
        )
        val usable = listOf(
            rhythmStatus in setOf("complete", "partial"),
            keyStatus in setOf("complete", "uncertain"),
            structureStatus in setOf("complete", "partial"),
            tuningStatus in setOf("complete", "uncertain"),
            loudnessStatus in setOf("complete", "partial"),
            featureStatuses.last() in setOf("complete", "partial")
        )
        when (overallStatus) {
            "complete" -> if (featureStatuses.any { it != "complete" }) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            "partial" -> if (usable.none { it } || featureStatuses.all { it == "complete" }) {
                fail("ANALYSIS_STATUS_INVALID")
            }
            "unavailable" -> if (usable.any { it }) fail("ANALYSIS_STATUS_INVALID")
        }
    }

    private fun validateDiagnosticTimeline(analysis: JSONObject, durationMs: Long) {
        val diagnostic = analysis.optJSONObject("diagnostics")
            ?.getJSONObject("allInOneRhythm") ?: return
        val beats = validateTimedValues(diagnostic.optJSONArray("beatsMs"), durationMs)
        val downbeats = validateTimedValues(diagnostic.optJSONArray("downbeatsMs"), durationMs)
        val beatMembership = beats.toHashSet()
        if (downbeats.any { it !in beatMembership }) fail("TIMELINE_INVALID")
    }

    private fun validateTimedValues(values: JSONArray?, durationMs: Long): List<Long> {
        if (values == null) return emptyList()
        if (durationMs <= 0L) fail("TIMELINE_INVALID")
        var previous = -1L
        return values.strictIntegers("TIMELINE_INVALID").onEach { time ->
            if (time < 0L || time >= durationMs || time <= previous) fail("TIMELINE_INVALID")
            previous = time
        }
    }

    private fun validateMeter(meter: JSONObject) {
        val numerator = meter.requiredInteger("numerator", "ANALYSIS_STATUS_INVALID")
        val denominator = meter.optionalInteger("denominator", "ANALYSIS_STATUS_INVALID")
        if (numerator !in 1L..32L) fail("ANALYSIS_STATUS_INVALID")
        if (denominator != null && denominator !in setOf(1L, 2L, 4L, 8L, 16L, 32L)) {
            fail("ANALYSIS_STATUS_INVALID")
        }
    }

    private fun validateTonicMode(value: JSONObject, optional: Boolean) {
        val tonic = if (value.has("tonic")) {
            value.requiredString("tonic", "ANALYSIS_STATUS_INVALID")
        } else ""
        val mode = if (value.has("mode")) {
            value.requiredString("mode", "ANALYSIS_STATUS_INVALID")
        } else ""
        if ((!optional || tonic.isNotBlank()) && app.stemwise.model.tonicPitchClass(tonic) == null) {
            fail("ANALYSIS_STATUS_INVALID")
        }
        if ((!optional || mode.isNotBlank()) && mode !in setOf("major", "minor")) {
            fail("ANALYSIS_STATUS_INVALID")
        }
        validateConfidence(value, "confidence")
    }

    private fun validateConfidence(value: JSONObject, key: String) {
        if (!value.has(key)) return
        val confidence = value.requiredNumber(key, "ANALYSIS_STATUS_INVALID")
        if (confidence !in 0.0..1.0) {
            fail("ANALYSIS_STATUS_INVALID")
        }
    }

    private fun validateFeatureStatus(feature: JSONObject, allowed: Set<String>) {
        val status = feature.requiredString("status", "ANALYSIS_STATUS_INVALID")
        if (status !in allowed) fail("ANALYSIS_STATUS_INVALID")
        feature.optionalNonBlankString("reason", "ANALYSIS_STATUS_INVALID")
        if (status != "complete" && !feature.has("reason")) {
            fail("ANALYSIS_STATUS_INVALID")
        }
        if (status == "complete" && feature.has("reason")) {
            fail("ANALYSIS_STATUS_INVALID")
        }
    }

    private fun validateTimedObjects(
        values: JSONArray?,
        timeKey: String,
        durationMs: Long,
        requireRanges: Boolean
    ) {
        if (values == null) return
        if (durationMs <= 0L) fail("TIMELINE_INVALID")
        var previousTime = -1L
        var previousEnd = -1L
        values.objectsExact("TIMELINE_INVALID").forEach { value ->
            val time = value.requiredInteger(timeKey, "TIMELINE_INVALID")
            if (time < 0L || time < previousTime || time >= durationMs) fail("TIMELINE_INVALID")
            if (requireRanges) {
                val end = value.requiredInteger("endMs", "TIMELINE_INVALID")
                if (end <= time || end > durationMs || time < previousEnd) {
                    fail("TIMELINE_INVALID")
                }
                previousEnd = end
            } else if (time == previousTime) {
                fail("TIMELINE_INVALID")
            }
            previousTime = time
        }
    }

    private fun validateWaveform(waveform: JSONObject?, durationMs: Long) {
        if (waveform == null) return
        waveform.requireOnly(
            setOf("status", "reason", "encoding", "levels"),
            "JSON_FIELD_UNKNOWN"
        )
        val status = waveform.requiredString("status", "WAVEFORM_INVALID")
        waveform.optionalNonBlankString("reason", "WAVEFORM_INVALID")
        if (status == "unavailable") {
            if (waveform.has("encoding") || waveform.has("levels")) fail("WAVEFORM_INVALID")
            return
        }
        if (status !in setOf("complete", "partial")) fail("WAVEFORM_INVALID")
        if (
            waveform.requiredString("encoding", "WAVEFORM_INVALID") !=
            "s16le-min-max-rms-base64-v1"
        ) {
            fail("WAVEFORM_INVALID")
        }
        val levels = waveform.requiredArray("levels", "WAVEFORM_INVALID")
        val durations = mutableSetOf<Long>()
        for (index in 0 until levels.length()) {
            val level = levels.optJSONObject(index) ?: fail("WAVEFORM_INVALID")
            level.requireOnly(
                setOf("frameDurationMs", "frameCount", "data"),
                "JSON_FIELD_UNKNOWN"
            )
            val frames = level.requiredInteger("frameCount", "WAVEFORM_INVALID")
            val frameMs = level.requiredInteger("frameDurationMs", "WAVEFORM_INVALID")
            if (frames <= 0L || frameMs !in WAVEFORM_LEVELS || frames > Int.MAX_VALUE / 6) {
                fail("WAVEFORM_INVALID")
            }
            val expectedFrames = (durationMs + frameMs - 1L) / frameMs
            if (frames != expectedFrames) fail("WAVEFORM_INVALID")
            if (!durations.add(frameMs)) fail("WAVEFORM_INVALID")
            val data = level.requiredString("data", "WAVEFORM_INVALID")
            // Six bytes per frame always occupy eight unpadded Base64 characters.
            // The strict decoder checks the alphabet without a second regex scan.
            if (data.length.toLong() != frames * 8L) fail("WAVEFORM_INVALID")
            val decoded = runCatching { Base64.getDecoder().decode(data) }
                .getOrElse { fail("WAVEFORM_INVALID") }
            if (decoded.size.toLong() != frames * 6L) fail("WAVEFORM_INVALID")
        }
        if (status == "complete" && durations != WAVEFORM_LEVELS) {
            fail("WAVEFORM_INVALID")
        }
        if (status == "partial" && durations.size !in 1..2) fail("WAVEFORM_INVALID")
    }

    private fun JSONArray.objectsExact(code: String): List<JSONObject> = buildList {
        for (index in 0 until length()) {
            add(optJSONObject(index) ?: fail(code))
        }
    }

    private fun JSONArray.strictNumbers(code: String): List<Double> = buildList {
        for (index in 0 until length()) {
            val value = opt(index)
            if (value !is Number || !value.toDouble().isFinite()) fail(code)
            add(value.toDouble())
        }
    }

    private fun JSONArray.strictIntegers(code: String): List<Long> = buildList {
        for (index in 0 until length()) {
            add(strictIntegerValue(opt(index), code))
        }
    }

    private fun JSONArray.strictStrings(code: String): List<String> = buildList {
        for (index in 0 until length()) {
            add(opt(index) as? String ?: fail(code))
        }
    }

    private fun JSONArray.nonBlankStrings(): List<String> = buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) add(value)
        }
    }

    private fun JSONArray.allStrings(): Boolean =
        (0 until length()).all { index -> opt(index) is String }

    private fun JSONObject.requireOnly(allowed: Set<String>, code: String) {
        if (keys().asSequence().any { it !in allowed }) fail(code)
    }

    private fun JSONObject.requiredObject(key: String, code: String): JSONObject =
        opt(key) as? JSONObject ?: fail(code)

    private fun JSONObject.optionalObject(key: String, code: String): JSONObject? {
        if (!has(key)) return null
        return opt(key) as? JSONObject ?: fail(code)
    }

    private fun JSONObject.requiredArray(key: String, code: String): JSONArray =
        opt(key) as? JSONArray ?: fail(code)

    private fun JSONObject.optionalArray(key: String, code: String): JSONArray? {
        if (!has(key)) return null
        return opt(key) as? JSONArray ?: fail(code)
    }

    private fun JSONObject.requiredString(key: String, code: String): String =
        (opt(key) as? String) ?: fail(code)

    private fun JSONObject.optionalNonBlankString(key: String, code: String): String? {
        if (!has(key)) return null
        val value = opt(key) as? String ?: fail(code)
        if (value.isBlank()) fail(code)
        return value
    }

    private fun JSONObject.requiredNumber(key: String, code: String): Double =
        optionalNumber(key, code) ?: fail(code)

    private fun JSONObject.optionalNumber(key: String, code: String): Double? {
        if (!has(key)) return null
        val value = opt(key)
        if (value !is Number || !value.toDouble().isFinite()) fail(code)
        return value.toDouble()
    }

    private fun JSONObject.requiredInteger(key: String, code: String): Long =
        optionalInteger(key, code) ?: fail(code)

    private fun JSONObject.optionalInteger(key: String, code: String): Long? {
        if (!has(key)) return null
        return strictIntegerValue(opt(key), code)
    }

    private fun strictIntegerValue(value: Any?, code: String): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> fail(code)
    }

    private fun fail(code: String): Nothing = throw EnrichmentImportException(code)

    private const val MAX_ANALYSIS_DURATION_MS = app.stemwise.MAX_ENRICHMENT_DURATION_MS
    private val TRACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val LINK_TYPE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val RELEASE_DATE = Regex("^[0-9]{4}(?:-[0-9]{2}(?:-[0-9]{2})?)?$")
    private val SCRIPT = Regex("^[A-Z][a-z]{3}$")
    private val ARTWORK_KINDS = setOf(
        "album-cover", "single-cover", "youtube-thumbnail", "other"
    )
    private val ALBUM_TYPES = setOf(
        "album", "single", "ep", "compilation", "soundtrack", "other"
    )
    private val IDENTIFIER_FIELDS = setOf(
        "isrc", "musicBrainzRecordingId", "musicBrainzReleaseId",
        "discogsReleaseId", "wikidataId"
    )
    private val IDENTIFIER_PATTERNS = mapOf(
        "isrc" to Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$"),
        "musicBrainzRecordingId" to Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        ),
        "musicBrainzReleaseId" to Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        ),
        "discogsReleaseId" to Regex("^[1-9][0-9]*$"),
        "wikidataId" to Regex("^Q[1-9][0-9]*$")
    )
    private val TIMELINE_POSITION_KEYS = setOf("startMs", "endMs", "timeMs", "beatsMs", "downbeatsMs")
    private val CONTENT_STATUSES = setOf("complete", "partial", "unavailable")
    private val RHYTHM_STATUSES = setOf("complete", "partial", "unavailable")
    private val KEY_STATUSES = setOf("complete", "uncertain", "unsupported", "unavailable")
    private val TOOL_RUN_STATUSES = setOf("complete", "failed", "skipped")
    private val LYRIC_KINDS = setOf("original", "translation", "transliteration")
    private val WAVEFORM_LEVELS = app.stemwise.ENRICHMENT_WAVEFORM_FRAME_DURATIONS_MS
    private val EXPECTED_TOOL_RUNS = setOf(
        "beat-this-full", "stemwise-rhythm-normalizer", "s-key",
        "all-in-one", "essentia", "stemwise-waveform"
    )
}

internal fun validateEnrichmentContract(json: JSONObject, hasCover: Boolean) {
    EnrichmentContractValidator.validate(json, hasCover)
}

/** JSON contract versions are integers; org.json's string/double coercions are not accepted. */
internal fun isEnrichmentFormatVersionTwo(value: Any?): Boolean = when (value) {
    is Byte -> value.toLong() == ENRICHMENT_FORMAT_VERSION.toLong()
    is Short -> value.toLong() == ENRICHMENT_FORMAT_VERSION.toLong()
    is Int -> value.toLong() == ENRICHMENT_FORMAT_VERSION.toLong()
    is Long -> value == ENRICHMENT_FORMAT_VERSION.toLong()
    else -> false
}
