package app.stemwise.data

import java.io.File
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.DataInputStream

internal data class OriginalAudioFacts(
    val channels: Int,
    val audibleFrames48k: Long,
    val mime: String,
    val sampleRateHz: Int,
    val audibleFramesNative: Long,
    val encodedFramesNative: Long,
    val preSkipFrames: Long,
    val endTrimFrames: Long,
    val initialGranuleOffset: Long
)

internal fun inspectOriginalAudio(file: File, checkpoint: () -> Unit = {}): OriginalAudioFacts =
    file.inputStream().use { inspectOriginalAudio(it, file.length(), checkpoint) }

internal fun inspectOriginalAudio(input: InputStream, maxBytes: Long, checkpoint: () -> Unit = {}): OriginalAudioFacts {
    val buffered = BufferedInputStream(input)
    buffered.mark(4)
    val signature = ByteArray(4)
    DataInputStream(buffered).readFully(signature)
    buffered.reset()
    if (signature.toString(Charsets.US_ASCII) == "OggS") {
        val opus = OggOpusInspector.inspect(buffered, maxBytes, checkpoint)
        return OriginalAudioFacts(opus.channels, opus.decodedAudibleFrameCount, "audio/opus", 48_000,
            opus.decodedAudibleFrameCount, opus.encodedFrameCount, opus.preSkipSamples.toLong(),
            opus.endTrimFrameCount, opus.initialGranuleOffset)
    }
    val flac = inspectPreparedSourceAudio(buffered, maxBytes, allowHighPrecisionFlac = true, checkpoint = checkpoint)
    check(flac.mime == "audio/flac" && flac.channels in 1..2 && flac.sampleRate in 8_000..384_000 &&
        flac.audibleFrames <= app.stemwise.MAX_ENRICHMENT_DURATION_MS * flac.sampleRate / 1_000L) { "ORIGINAL_CODEC_INVALID" }
    return OriginalAudioFacts(flac.channels, (flac.audibleFrames * 48_000L + flac.sampleRate - 1L) / flac.sampleRate,
        flac.mime, flac.sampleRate, flac.audibleFrames, flac.audibleFrames, 0L, 0L, 0L)
}

