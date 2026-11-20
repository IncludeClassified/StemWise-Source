package app.stemwise.playback

import app.stemwise.playback.engine.SourceWaveformWindow
import app.stemwise.playback.engine.SourceWaveformIdentity
import org.junit.Assert.assertEquals

internal fun assertSourceWaveformCoverage(
    window: SourceWaveformWindow,
    expectedIdentity: SourceWaveformIdentity,
    startMs: Long,
    endMs: Long,
    peakCount: Int
) {
    assertEquals("Waveform source identity", expectedIdentity, window.identity)
    assertEquals("Waveform interval start", startMs, window.startMs)
    assertEquals("Waveform interval end", endMs, window.endMs)
    assertEquals("Waveform bucket duration", 5L, window.frameDurationMs)
    assertEquals("Waveform bucket count", peakCount, window.peaks.size)
}
