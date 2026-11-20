package app.stemwise.data

import app.stemwise.model.LyricTrack
import app.stemwise.model.TimedLyricLine
import app.stemwise.model.TimedLyricWord
import app.stemwise.model.LyricTimingStatus
import org.json.JSONArray
import org.json.JSONObject

/** Pure projections shared by the SAF package scan and executable fixtures. */
internal data class EnrichmentMetadataProjection(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val additionalInfo: Map<String, String>,
    val roomAdditionalInfoJson: String
)

internal data class EnrichmentAnalysisProjection(
    val bpm: Double?,
    val meter: String?,
    val musicalKey: String?,
    val keyConfidence: Double?,
    val tuningCents: Double?,
    val integratedLufs: Double?,
    val loudnessRangeLu: Double?,
    val truePeakDbtp: Double?,
    val keyStatus: String?, val keyReason: String?,
    val tuningStatus: String?, val tuningReason: String?
)

internal const val CATALOG_PRIMARY_TEXT_MAX_CHARS = 512
internal const val CATALOG_SECONDARY_TEXT_MAX_CHARS = 256
internal const val CATALOG_TOKEN_MAX_CHARS = 64

/** Eight display fields stay small enough for list projections, without reopening song manifests. */
internal fun catalogAdditionalInfo(info: Map<String, String>): Map<String, String> =
    info.mapValues { (_, value) -> boundedCatalogText(value, 256).orEmpty() }

internal fun EnrichmentMetadataProjection.withOriginalAlbumAndYear(
    originalAlbum: String?, originalYear: String?
): EnrichmentMetadataProjection = copy(
    album = album.takeIf(String::isNotBlank) ?: ownerVisibleTextOrNull(originalAlbum).orEmpty(),
    year = year.takeIf(String::isNotBlank) ?: ownerVisibleTextOrNull(originalYear).orEmpty()
)

/** Keeps list/player catalog projections independent of manifest payload size. */
internal fun boundedCatalogText(value: String?, maximumChars: Int): String? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let { boundedDisplayText(it, maximumChars) }

internal fun projectEnrichmentAnalysis(analysis: JSONObject?): EnrichmentAnalysisProjection {
    val rhythm = analysis?.optJSONObject("rhythm")
    val bpm = rhythm?.optDouble("bpm", Double.NaN)?.takeIf(Double::isFinite)
    val meter = rhythm?.optJSONObject("meter")?.let { value ->
        val numerator = value.getInt("numerator")
        if (value.has("denominator")) "$numerator/${value.getInt("denominator")}" else "$numerator"
    }
    val importedKey = analysis?.optJSONObject("key")
    val musicalKey = importedKey?.optString("tonic")?.takeIf(String::isNotBlank)?.let { tonic ->
        importedKey.optString("mode").takeIf(String::isNotBlank)
            ?.let { "$tonic ${it.replaceFirstChar(Char::uppercase)}" } ?: tonic
    }
    val loudness = analysis?.optJSONObject("loudness")
    val tuning = analysis?.optJSONObject("tuning")
    return EnrichmentAnalysisProjection(
        bpm = bpm,
        meter = meter,
        musicalKey = musicalKey,
        keyConfidence = importedKey?.optDouble("confidence", Double.NaN)?.takeIf(Double::isFinite),
        tuningCents = analysis?.optJSONObject("tuning")?.optDouble("centsFromA440", Double.NaN)
            ?.takeIf(Double::isFinite),
        integratedLufs = loudness?.optDouble("integratedLufs", Double.NaN)
            ?.takeIf(Double::isFinite),
        loudnessRangeLu = loudness?.optDouble("loudnessRangeLu", Double.NaN)
            ?.takeIf(Double::isFinite),
        truePeakDbtp = loudness?.optDouble("truePeakDbtp", Double.NaN)
            ?.takeIf(Double::isFinite),
        keyStatus = importedKey?.optString("status")?.takeIf(String::isNotBlank),
        keyReason = importedKey?.optString("reason")?.takeIf(String::isNotBlank),
        tuningStatus = tuning?.optString("status")?.takeIf(String::isNotBlank),
        tuningReason = tuning?.optString("reason")?.takeIf(String::isNotBlank)
    )
}

internal fun projectEnrichmentMetadata(metadata: JSONObject?): EnrichmentMetadataProjection {
    val title = metadata?.ownerVisibleString("title") ?: "Unknown title"
    val artist = metadata?.optJSONArray("artists")?.let { artists ->
        (0 until artists.length()).mapNotNull { ownerVisibleTextOrNull(artists.opt(it)) }
            .distinct().joinToString(" · ").takeIf(String::isNotBlank)
    } ?: "Unknown Artist"
    val album = metadata?.optJSONObject("album")?.ownerVisibleString("title").orEmpty()
    val year = metadata?.optJSONObject("album")?.ownerVisibleString("releaseDate")
        ?.substringBefore('-').orEmpty()
    val additionalInfo = metadata?.toDisplayInformation().orEmpty()
    return EnrichmentMetadataProjection(
        title = title,
        artist = artist,
        album = album,
        year = year,
        additionalInfo = additionalInfo,
        roomAdditionalInfoJson = JSONObject(additionalInfo).toString()
    )
}

internal fun projectEnrichmentLyricTracks(tracks: List<JSONObject>): List<LyricTrack> =
    tracks.map { track ->
        LyricTrack(
            id = track.getString("id"),
            kind = track.getString("kind"),
            language = track.optString("language").takeIf(String::isNotBlank),
            format = track.getString("format"),
            text = track.optString("text").takeIf(String::isNotBlank),
            lines = track.optJSONArray("lines")?.let { lines ->
                (0 until lines.length()).mapNotNull(lines::optJSONObject).map { line ->
                    val timing = line.optJSONObject("timing") ?: line
                    TimedLyricLine(
                        startMs = timing.optionalLyricTime("startMs"),
                        endMs = timing.optionalLyricTime("endMs"),
                        text = line.getString("text"),
                        timingStatus = timing.lyricTimingStatus(),
                        timingBasis = timing.optString("basis").takeIf(String::isNotBlank),
                        timingReason = timing.optString("reason").takeIf(String::isNotBlank),
                        contentStatus = line.optString("contentStatus", "admitted"),
                        occurrenceStatus = line.optString("occurrenceStatus", "supported"),
                        words = line.optJSONArray("words")?.let { words ->
                            (0 until words.length()).mapNotNull(words::optJSONObject).map { word ->
                                val wordTiming = word.optJSONObject("timing") ?: word
                                TimedLyricWord(
                                    startUtf16 = word.getInt("startUtf16"),
                                    endUtf16 = word.getInt("endUtf16"),
                                    startMs = wordTiming.optionalLyricTime("startMs"),
                                    endMs = wordTiming.optionalLyricTime("endMs"),
                                    timingStatus = wordTiming.lyricTimingStatus(),
                                    timingBasis = wordTiming.optString("basis").takeIf(String::isNotBlank),
                                    timingReason = wordTiming.optString("reason").takeIf(String::isNotBlank)
                                )
                            }
                        }.orEmpty()
                    )
                }
            }.orEmpty(),
            basedOnTrackId = track.optString("basedOnTrackId").takeIf(String::isNotBlank),
            componentLanguages = track.optJSONArray("componentLanguages")?.let { values ->
                (0 until values.length()).map(values::getString)
            }.orEmpty(),
            script = track.optString("script").takeIf(String::isNotBlank)
        )
    }

private fun JSONObject.optionalLyricTime(key: String): Long? = if (has(key)) getLong(key) else null

private fun JSONObject.lyricTimingStatus(): LyricTimingStatus = when (optString("status", "supported")) {
    "supported" -> LyricTimingStatus.SUPPORTED
    "estimated" -> LyricTimingStatus.ESTIMATED
    "none" -> LyricTimingStatus.NONE
    else -> error("LYRICS_INVALID")
}

internal fun JSONObject.toDisplayInformation(): Map<String, String> = buildMap {
    optString("description").takeIf(String::isNotBlank)?.let { put("Description", it) }
    optJSONArray("genres")?.let { genres ->
        (0 until genres.length()).mapNotNull {
            genres.optString(it).takeIf(String::isNotBlank)
        }.joinToString(", ").takeIf(String::isNotBlank)?.let { put("Genres", it) }
    }
    optString("recordLabel").takeIf(String::isNotBlank)?.let { put("Label", it) }
    optString("copyright").takeIf(String::isNotBlank)?.let { put("Copyright", it) }
    optJSONArray("credits")?.let { credits ->
        (0 until credits.length()).mapNotNull(credits::optJSONObject)
            .joinToString("\n") { credit ->
                val roles = credit.optJSONArray("roles")?.let { values ->
                    (0 until values.length()).mapNotNull {
                        values.optString(it).takeIf(String::isNotBlank)
                    }.joinToString(", ")
                }.orEmpty()
                if (roles.isBlank()) credit.optString("name")
                else "${credit.optString("name")} — $roles"
            }.takeIf(String::isNotBlank)?.let { put("Credits", it) }
    }
    optJSONObject("identifiers")?.let { identifiers ->
        identifiers.keys().asSequence().sorted().mapNotNull { key ->
            identifiers.optString(key).takeIf(String::isNotBlank)
                ?.let { "${displayIdentifierLabel(key)}: $it" }
        }.joinToString("\n").takeIf(String::isNotBlank)?.let { put("Identifiers", it) }
    }
    val projectedLinks = optJSONArray("links")?.let { links ->
        (0 until links.length()).mapNotNull(links::optJSONObject).mapNotNull { link ->
            val url = link.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = link.optString("type").takeIf(String::isNotBlank) ?: "Link"
            "${displayLinkLabel(type)}: $url"
        }
    }.orEmpty()
    projectedLinks.joinToString("\n").takeIf(String::isNotBlank)?.let { put("Links", it) }
}

internal fun displayLinkLabel(key: String): String = when (
    key.lowercase().replace(Regex("[^a-z0-9]"), "")
) {
    "youtube" -> "YouTube"
    "soundcloud" -> "SoundCloud"
    "bandcamp" -> "Bandcamp"
    "spotify" -> "Spotify"
    "applemusic" -> "Apple Music"
    else -> key
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .trim()
        .replaceFirstChar(Char::uppercase)
}

internal fun displayIdentifierLabel(key: String): String = when (
    key.lowercase().replace(Regex("[^a-z0-9]"), "")
) {
    "isrc" -> "ISRC"
    "musicbrainzrecordingid" -> "MusicBrainz recording"
    "musicbrainzreleaseid" -> "MusicBrainz release"
    "discogsreleaseid" -> "Discogs release"
    "wikidataid" -> "Wikidata"
    else -> key
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .trim()
        .replaceFirstChar(Char::uppercase)
}

/** Reads the current display-ready Room representation. */
internal fun parseRoomAdditionalInfo(json: String): Map<String, String> = runCatching {
    val value = JSONObject(json)
    val keys = value.keys().asSequence().toList()
    buildMap {
        keys.forEach { key ->
            (value.opt(key) as? String)?.let { put(key, it) }
        }
    }
}.getOrDefault(emptyMap())
