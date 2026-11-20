package app.stemwise.data

import android.util.AtomicFile
import app.stemwise.model.StemRoleContract
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.playback.MAXIMUM_PLAYBACK_TEMPO
import app.stemwise.playback.METRONOME_BEAT_UNITS
import app.stemwise.playback.MINIMUM_PLAYBACK_TEMPO
import app.stemwise.playback.PlaybackLoopValidation
import app.stemwise.playback.StemMixState
import java.io.File
import org.json.JSONObject

internal val SONG_PLAYBACK_SETTINGS_SHA256_PATTERN = Regex("[a-f0-9]{64}")

internal data class SongPlaybackSettingsIdentity(
    val libraryId: String,
    val treeUri: String,
    val songId: String,
    val originalSha256: String
) {
    init {
        require(libraryId.isNotBlank() && treeUri.isNotBlank() && songId.isNotBlank()) {
            "SONG_PLAYBACK_SETTINGS_IDENTITY_INVALID"
        }
        require(SONG_PLAYBACK_SETTINGS_SHA256_PATTERN.matches(originalSha256)) { "SONG_PLAYBACK_SETTINGS_IDENTITY_INVALID" }
    }
}

internal data class SongTrimRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0L && endMs > startMs) { "SONG_TRIM_RANGE_INVALID" }
    }
}

/** Points use the immutable Original clock, including when an applied trim excludes them. */
internal data class SongLoopPreference(
    val pointA: Long? = null,
    val pointB: Long? = null,
    val enabled: Boolean = false
) {
    init {
        require(pointA == null || pointA >= 0L) { "SONG_PLAYBACK_LOOP_INVALID" }
        require(pointB == null || pointA != null && PlaybackLoopValidation.accepts(pointA, pointB)) {
            "SONG_PLAYBACK_LOOP_INVALID"
        }
        require(!enabled || pointA != null && pointB != null) { "SONG_PLAYBACK_LOOP_INVALID" }
    }
}

internal data class SongMetronomeSettings(
    val enabled: Boolean = false,
    val volume: Float = 1f,
    val subdivision: Int = 1,
    val numerator: Int = 4,
    val denominator: Int = 4
) {
    init {
        require(volume.isFinite() && volume in 0f..1f && subdivision in setOf(1, 2, 4) &&
            numerator in 1..32 && denominator in METRONOME_BEAT_UNITS) { "SONG_PLAYBACK_METRONOME_INVALID" }
    }
}

internal data class SongPlaybackSettings(
    val trim: SongTrimRange? = null,
    val loop: SongLoopPreference = SongLoopPreference(),
    val tempo: Float = 1f,
    val semitones: Int = 0,
    val metronome: SongMetronomeSettings? = null,
    val stemMix: Map<String, StemMixState> = emptyMap()
) {
    init {
        require(tempo.isFinite() && tempo in MINIMUM_PLAYBACK_TEMPO..MAXIMUM_PLAYBACK_TEMPO &&
            semitones in -12..12) { "SONG_PLAYBACK_TUNING_INVALID" }
        require(stemMix.all { (role, mix) ->
            role == StemRoleContract.normalize(role) &&
                (role == "remaining" || StemSourceCatalogue.sources.any { it.id == role }) &&
                mix.volume.isFinite() && mix.volume in 0f..1f
        }) { "SONG_PLAYBACK_MIX_INVALID" }
    }

    internal fun validateDuration(durationMs: Long) {
        require(durationMs > 0L && (trim == null || trim.endMs <= durationMs) &&
            (loop.pointA == null || loop.pointA <= durationMs) &&
            (loop.pointB == null || loop.pointB <= durationMs)) { "SONG_PLAYBACK_SETTINGS_RANGE_INVALID" }
    }
}

/** One local row per library/tree/song; each atomic delta preserves the other settings sections. */
internal class SongPlaybackSettingsStore(private val directory: File) {
    @Synchronized
    fun read(identity: SongPlaybackSettingsIdentity, durationMs: Long): SongPlaybackSettings? {
        require(durationMs > 0L) { "SONG_PLAYBACK_SETTINGS_DURATION_INVALID" }
        val stored = current(identity) ?: return null
        if (stored.first != identity) return null
        try { stored.second.validateDuration(durationMs) }
        catch (error: Exception) { throw AtomicJournalFormatException("SONG_PLAYBACK_SETTINGS_RANGE_INVALID", error) }
        return stored.second
    }

    @Synchronized
    fun update(
        identity: SongPlaybackSettingsIdentity,
        durationMs: Long,
        delta: (SongPlaybackSettings) -> SongPlaybackSettings
    ): SongPlaybackSettings {
        val changed = delta(read(identity, durationMs) ?: SongPlaybackSettings())
        changed.validateDuration(durationMs)
        val expected = changed.copy(
            trim = changed.trim?.takeUnless { it.startMs == 0L && it.endMs == durationMs },
            metronome = changed.metronome?.let { if (it.volume == 0f) it.copy(volume = 0f) else it },
            stemMix = changed.stemMix.mapValues { (_, mix) -> if (mix.volume == 0f) mix.copy(volume = 0f) else mix }
        )
        val value = identityJson(identity).put("version", 1).put("originalSha256", identity.originalSha256)
            .put("settings", settingsJson(expected))
        check(directory.isDirectory || directory.mkdirs()) { "SONG_PLAYBACK_SETTINGS_UNAVAILABLE" }
        val atomic = AtomicFile(file(identity))
        val output = atomic.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        val saved = read(identity, durationMs)
        check(saved == expected) { "SONG_PLAYBACK_SETTINGS_SAVE_UNCONFIRMED" }
        return checkNotNull(saved)
    }

    /** A stale audio identity cannot erase the replacement audio's settings. */
    @Synchronized
    fun clear(identity: SongPlaybackSettingsIdentity) {
        val stored = current(identity) ?: return
        if (stored.first != identity) return
        deleteAtomicJournal(file(identity), "SONG_PLAYBACK_SETTINGS_CLEAR_UNCONFIRMED")
    }

    /** Explicit song-scoped recovery preserves malformed bytes and never replaces a transiently unreadable row. */
    @Synchronized
    fun resetMalformed(identity: SongPlaybackSettingsIdentity, durationMs: Long): Boolean {
        try { read(identity, durationMs); return false }
        catch (malformed: AtomicJournalFormatException) {
            quarantineMalformedPlaybackRecord(file(identity))
            return true
        }
    }

    private fun current(identity: SongPlaybackSettingsIdentity): Pair<SongPlaybackSettingsIdentity, SongPlaybackSettings>? {
        val value = readAtomicJournal(file(identity), "SONG_PLAYBACK_SETTINGS_UNAVAILABLE")?.value ?: return null
        return try { parseCurrent(identity, value) }
        catch (error: Exception) { throw AtomicJournalFormatException("SONG_PLAYBACK_SETTINGS_RECORD_INVALID", error) }
    }

    private fun parseCurrent(identity: SongPlaybackSettingsIdentity, value: JSONObject): Pair<SongPlaybackSettingsIdentity, SongPlaybackSettings> {
        requireSettingsKeys(value, setOf("version", "libraryId", "treeUri", "songId", "originalSha256", "settings"))
        check(exactAtomicJournalLong(value.opt("version")) == 1L &&
            value.opt("libraryId") == identity.libraryId && value.opt("treeUri") == identity.treeUri &&
            value.opt("songId") == identity.songId) { "SONG_PLAYBACK_SETTINGS_RECORD_INVALID" }
        val storedIdentity = identity.copy(originalSha256 = value.opt("originalSha256") as? String
            ?: error("SONG_PLAYBACK_SETTINGS_RECORD_INVALID"))
        return storedIdentity to parseSettings(value.getJSONObject("settings"))
    }

    private fun file(identity: SongPlaybackSettingsIdentity): File =
        File(directory, "${CanonicalJson.sha256(identityJson(identity))}.json")

    private fun identityJson(identity: SongPlaybackSettingsIdentity): JSONObject = JSONObject()
        .put("libraryId", identity.libraryId).put("treeUri", identity.treeUri).put("songId", identity.songId)
}

private fun settingsJson(settings: SongPlaybackSettings): JSONObject = JSONObject()
    .put("trim", settings.trim?.let { JSONObject().put("startMs", it.startMs).put("endMs", it.endMs) } ?: JSONObject.NULL)
    .put("loop", JSONObject().put("pointA", settings.loop.pointA ?: JSONObject.NULL)
        .put("pointB", settings.loop.pointB ?: JSONObject.NULL).put("enabled", settings.loop.enabled))
    .put("tempo", settings.tempo).put("semitones", settings.semitones)
    .put("metronome", settings.metronome?.let { JSONObject().put("enabled", it.enabled).put("volume", it.volume)
        .put("subdivision", it.subdivision).put("numerator", it.numerator)
        .put("denominator", it.denominator) } ?: JSONObject.NULL)
    .put("stemMix", JSONObject().apply {
        settings.stemMix.forEach { (role, mix) ->
            put(role, JSONObject().put("volume", mix.volume).put("muted", mix.muted).put("solo", mix.solo))
        }
    })

private fun parseSettings(value: JSONObject): SongPlaybackSettings {
    requireSettingsKeys(value, setOf("trim", "loop", "tempo", "semitones", "metronome", "stemMix"))
    val trim = if (value.opt("trim") === JSONObject.NULL) null else value.getJSONObject("trim").let {
        requireSettingsKeys(it, setOf("startMs", "endMs"))
        SongTrimRange(settingsLong(it.opt("startMs")), settingsLong(it.opt("endMs")))
    }
    val loop = value.getJSONObject("loop").let {
        requireSettingsKeys(it, setOf("pointA", "pointB", "enabled"))
        SongLoopPreference(settingsOptionalLong(it.opt("pointA")), settingsOptionalLong(it.opt("pointB")),
            settingsBoolean(it.opt("enabled")))
    }
    val metronome = if (value.opt("metronome") === JSONObject.NULL) null else value.getJSONObject("metronome").let {
        requireSettingsKeys(it, setOf("enabled", "volume", "subdivision", "numerator", "denominator"))
        SongMetronomeSettings(settingsBoolean(it.opt("enabled")), settingsFloat(it.opt("volume")),
            settingsInt(it.opt("subdivision")), settingsInt(it.opt("numerator")), settingsInt(it.opt("denominator")))
    }
    val mix = value.getJSONObject("stemMix").let { stems ->
        stems.keys().asSequence().associateWith { role ->
            stems.getJSONObject(role).let {
                requireSettingsKeys(it, setOf("volume", "muted", "solo"))
                StemMixState(settingsFloat(it.opt("volume")), settingsBoolean(it.opt("muted")), settingsBoolean(it.opt("solo")))
            }
        }
    }
    return SongPlaybackSettings(trim, loop, settingsFloat(value.opt("tempo")), settingsInt(value.opt("semitones")), metronome, mix)
}

private fun requireSettingsKeys(value: JSONObject, keys: Set<String>) {
    check(value.keys().asSequence().toSet() == keys) { "SONG_PLAYBACK_SETTINGS_RECORD_INVALID" }
}

private fun settingsLong(value: Any?): Long = exactAtomicJournalLong(value) ?: error("SONG_PLAYBACK_SETTINGS_RECORD_INVALID")
private fun settingsOptionalLong(value: Any?): Long? = if (value === JSONObject.NULL) null else settingsLong(value)
private fun settingsInt(value: Any?): Int = settingsLong(value).also {
    check(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "SONG_PLAYBACK_SETTINGS_RECORD_INVALID" }
}.toInt()
private fun settingsBoolean(value: Any?): Boolean = value as? Boolean ?: error("SONG_PLAYBACK_SETTINGS_RECORD_INVALID")
private fun settingsFloat(value: Any?): Float = (value as? Number)?.toFloat()
    ?.takeIf(Float::isFinite) ?: error("SONG_PLAYBACK_SETTINGS_RECORD_INVALID")
