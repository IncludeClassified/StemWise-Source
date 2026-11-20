package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaCodecResamplerHistoryTest {
    @Test fun onlyResampledPlaybackRetainsAnAbsolutePhaseAndRealHistory() {
        val playback = MediaCodecTrackDecoderConfig(retainResamplerHistory = true)
        for (rate in listOf(8_000, 8_001, 44_100, 96_000, 384_000)) {
            for ((position, anchor) in listOf(0L to 0L, 999_000L to 0L,
                2_000_000L to 1_000_000L, 2_001_000L to 1_000_000L,
                2_002_000L to 1_000_000L, 9_999_999L to 8_000_000L)) {
                assertEquals(anchor, playback.outputStartPositionUs(position, rate))
                assertEquals(0L, anchor * rate % 1_000_000L)
                assertEquals(position, MediaCodecTrackDecoderConfig().outputStartPositionUs(position, rate))
                assertEquals(position, playback.outputStartPositionUs(position, 48_000))
            }
        }
    }

    @Test fun unknownExtractorRateRetainsHistoryUntilThePcmRateIsKnown() {
        val playback = MediaCodecTrackDecoderConfig(retainResamplerHistory = true)
        assertEquals(1_000_000L, playback.outputStartPositionUs(2_001_000L, null))
        assertEquals(0L, playback.outputStartPositionUs(999_000L, null))
        assertEquals(2_001_000L, MediaCodecTrackDecoderConfig().outputStartPositionUs(2_001_000L, null))
        val timeline = NativeGaplessTimeline(10_000_000L, 0, 0)
        for ((rate, expected) in listOf(44_100 to 33_075L, 48_000 to 36_000L, 96_000 to 72_000L)) {
            assertEquals(expected, timeline.firstDecodedAbsoluteFrame(10_750_000L, rate, null))
        }
        assertNotEquals("A fallback rate would manufacture a persistent 2,925-frame codec offset",
            33_075L, timeline.absoluteFrameForPresentationTime(10_750_000L, 48_000))
    }

    @Test fun actualPcmRatePreservesGaplessZeroAndOpusPrerollRules() {
        val timeline = NativeGaplessTimeline(10_000_000L, 312, 0)
        assertEquals(312L, timeline.firstDecodedAbsoluteFrame(10_000_000L, 44_100, null))
        assertEquals(0L, timeline.firstDecodedAbsoluteFrame(10_000_000L, 48_000, 312L))
        assertEquals(36_000L, timeline.firstDecodedAbsoluteFrame(10_750_000L, 48_000, 312L))
    }

    @Test fun coldGaplessSeekFromTrackOriginKeepsTheCodecTrimAnchor() {
        val config = MediaCodecTrackDecoderConfig(retainResamplerHistory = true)
        val timeline = NativeGaplessTimeline(10_000_000L, 576, 0)
        for (requestedUs in listOf(0L, 11_000L, 1_000_000L, 1_999_000L)) {
            assertEquals(0L, config.outputStartPositionUs(requestedUs, 44_100))
            assertEquals("The same codec startup PCM must keep the same absolute frame",
                576L, timeline.firstDecodedAbsoluteFrame(10_000_000L, 44_100, null))
        }
    }
}
