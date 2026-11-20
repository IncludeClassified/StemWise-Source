package app.stemwise.ui

import app.stemwise.data.SongLoopPreference
import app.stemwise.data.SongMetronomeSettings
import app.stemwise.data.SongPlaybackSettings
import app.stemwise.data.StemAssetEntity
import app.stemwise.model.Song
import app.stemwise.model.Stem
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.AudioPlaybackState
import app.stemwise.playback.StemMixState
import app.stemwise.playback.normalizedMetronomeBeatUnit
import app.stemwise.playback.normalizedMetronomeSubdivision
import app.stemwise.ui.theme.StemwisePalette
import java.util.Locale

/** A cache miss keeps the last coherent profile; it never manufactures a new default profile. */
internal fun preparedPlayerSong(
    requested: Song,
    requestedSettings: SongPlaybackSettings?,
    previous: Song?,
    previousSettings: SongPlaybackSettings?
): Song? = if (requestedSettings != null) requested else previous?.takeIf { previousSettings != null }

internal data class PlayerSongSettingsProjection(
    val sourceDurationMs: Long,
    val trimStartMs: Long,
    val durationMs: Long,
    val loopA: Long?,
    val loopB: Long?,
    val tempo: Float,
    val semitones: Int,
    val metronome: SongMetronomeSettings
)

/** Cached values describe saved intent. Only the exact current owner supplies live control state. */
internal fun projectPlayerSongSettings(
    song: Song,
    audio: AudioPlaybackState,
    currentOwner: Boolean,
    saved: SongPlaybackSettings
): PlayerSongSettingsProjection {
    val sourceDuration = if (currentOwner) audio.sourceDurationMs.takeIf { it > 0L } ?: song.durationMs else song.durationMs
    val start = if (currentOwner) audio.trimStartMs else saved.trim?.startMs ?: 0L
    val cachedEnd = saved.trim?.endMs ?: sourceDuration
    val duration = effectiveTimelineDuration(currentOwner, audio.durationMs,
        if (currentOwner) sourceDuration - start else cachedEnd - start)
    val end = start + duration
    val loop = saved.loop.takeIf {
        (it.pointA == null || it.pointA in start..end) && (it.pointB == null || it.pointB in start..end)
    } ?: SongLoopPreference()
    val metronome = if (currentOwner) SongMetronomeSettings(audio.metronomeEnabled, audio.metronomeVolume,
        normalizedMetronomeSubdivision(audio.metronomeSubdivision), audio.metronomeNumerator.coerceIn(1, 32),
        normalizedMetronomeBeatUnit(audio.metronomeDenominator))
    else saved.metronome ?: SongMetronomeSettings(
        numerator = (song.meter?.substringBefore('/')?.toIntOrNull() ?: 4).coerceIn(1, 32),
        denominator = normalizedMetronomeBeatUnit(song.meter?.substringAfter('/')?.toIntOrNull() ?: 4))
    return PlayerSongSettingsProjection(sourceDuration, start, end - start,
        if (currentOwner) audio.loopA else loop.pointA?.minus(start),
        if (currentOwner) audio.loopB else loop.pointB?.takeIf { loop.enabled }?.minus(start),
        if (currentOwner) audio.tempo else saved.tempo,
        if (currentOwner) audio.semitoneShift else saved.semitones, metronome)
}

internal fun playerSettingsStemRows(
    assets: List<StemAssetEntity>,
    currentMix: Map<String, StemMixState>,
    savedMix: Map<String, StemMixState>
): List<Stem> = assets.mapIndexed { index, asset ->
    val capability = asset.normalizedCapability?.takeIf(String::isNotBlank)
        ?: asset.providerLabel.lowercase(Locale.ROOT)
    val mix = currentMix[asset.id] ?: savedMix[StemRoleContract.normalize(capability)] ?: StemMixState()
    Stem(capability, asset.providerLabel.replaceFirstChar { it.titlecase() },
        StemwisePalette.stemColor(capability, index), mix.volume, mix.muted, mix.solo)
}
