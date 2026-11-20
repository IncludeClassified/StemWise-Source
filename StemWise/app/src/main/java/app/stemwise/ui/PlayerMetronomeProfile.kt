package app.stemwise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.stemwise.analysis.BeatEvent
import app.stemwise.analysis.MeterSegment
import app.stemwise.playback.AudioPlayback
import app.stemwise.playback.PlaybackControlLease

@Composable
internal fun PlayerMetronomeProfile(
    audioPlayback: AudioPlayback,
    isCurrentAudio: Boolean,
    controlLease: PlaybackControlLease?,
    contentLoaded: Boolean,
    metronomeAvailable: Boolean,
    effectiveMetronomeBpm: Float,
    metronomeSubdivision: Int,
    metronomeNumerator: Int,
    metronomeDenominator: Int,
    beatEvents: List<BeatEvent>,
    meterSegments: List<MeterSegment>
) {
    LaunchedEffect(
        isCurrentAudio,
        effectiveMetronomeBpm,
        metronomeSubdivision,
        metronomeNumerator,
        metronomeDenominator,
        beatEvents,
        meterSegments,
        controlLease?.native,
        metronomeAvailable,
        contentLoaded
    ) {
        val effectLease = controlLease ?: return@LaunchedEffect
        if (!isCurrentAudio || !contentLoaded) return@LaunchedEffect
        audioPlayback.setMetronome(
            enabled = metronomeAvailable && audioPlayback.currentStateSnapshot().metronomeEnabled,
            bpm = effectiveMetronomeBpm,
            songSynced = metronomeAvailable,
            beatEvents = beatEvents,
            meterSegments = meterSegments,
            subdivision = metronomeSubdivision,
            numerator = metronomeNumerator,
            denominator = metronomeDenominator,
            controlLease = effectLease,
            persist = false
        )
    }

}
