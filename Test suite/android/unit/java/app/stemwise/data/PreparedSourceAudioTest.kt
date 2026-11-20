package app.stemwise.data

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PreparedSourceAudioTest {
    @Test fun originalFlacKeepsItsNativePrecisionAndMapsOnlyThePreparationTimeline() {
        fun flac(rate: Int, channels: Int, bits: Int, frames: Long): ByteArray =
            ByteArray(42).apply {
                "fLaC".toByteArray().copyInto(this)
                this[4] = 0x80.toByte(); this[7] = 34
                val packed = (rate.toLong() shl 44) or ((channels - 1L) shl 41) or
                    ((bits - 1L) shl 36) or frames
                repeat(8) { this[18 + it] = (packed ushr ((7 - it) * 8)).toByte() }
            }
        val native = flac(96_000, 2, 24, 96_001)
        val facts = inspectPreparedSourceAudio(ByteArrayInputStream(native), native.size.toLong(), true)
        assertEquals(24, facts.bitsPerSample)
        assertEquals(96_000, facts.sampleRate)
        assertEquals(96_001L, facts.audibleFrames)
        val original = inspectOriginalAudio(ByteArrayInputStream(native), native.size.toLong())
        assertEquals(2, original.channels)
        assertEquals("audio/flac", original.mime)
        assertEquals(96_000, original.sampleRateHz)
        assertEquals(48_001L, original.audibleFrames48k)
        assertEquals(96_001L, original.audibleFramesNative)
        assertEquals(96_001L, original.encodedFramesNative)
        assertEquals(0L, original.preSkipFrames)
        assertEquals(0L, original.endTrimFrames)
        assertEquals(0L, original.initialGranuleOffset)
        assertThrows(IllegalStateException::class.java) {
            inspectPreparedSourceAudio(ByteArrayInputStream(native), native.size.toLong())
        }
        listOf(flac(48_000, 6, 24, 48_000), flac(96_000, 2, 32, 96_000),
            flac(96_000, 2, 24, 0), flac(48_000, 2, 16, 48_000L * 7_201)).forEach { bytes ->
            assertThrows(IllegalStateException::class.java) {
                inspectOriginalAudio(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }
    }

    @Test fun losslessFloatWavPackRequiresACompleteContiguousStereoTimeline() {
        fun block(index: Int, frames: Int, flags: Int = 0x05001883): ByteArray =
            ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("wvpk".toByteArray()); putInt(32); putShort(0x410); putShort(0)
                putInt(4); putInt(index); putInt(frames); putInt(flags); putInt(0); putLong(0)
            }.array()
        val valid = block(0, 2) + block(2, 2)
        val facts = inspectPreparedSourceAudio(ByteArrayInputStream(valid), valid.size.toLong())
        assertEquals(4L, facts.audibleFrames)
        assertEquals("audio/wavpack", facts.mime)
        assertEquals(48_000, facts.sampleRate)
        assertEquals(2, facts.channels)
        assertEquals(32, facts.bitsPerSample)
        assertTrue(facts.isFloat)
        var payloadReads = 0
        val zeroSkip = object : ByteArrayInputStream(valid) {
            override fun skip(count: Long) = 0L
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 8) payloadReads++
                return super.read(bytes, offset, length)
            }
        }
        assertEquals(facts, inspectPreparedSourceAudio(zeroSkip, valid.size.toLong()))
        assertEquals(2, payloadReads)
        val ffmpeg = valid.copyOf().apply { this[40 + 12] = 0 }
        assertEquals(4L, inspectPreparedSourceAudio(ByteArrayInputStream(ffmpeg), ffmpeg.size.toLong()).audibleFrames)
        val invalid = listOf(
            valid.copyOf(valid.size - 1), block(0, 2),
            block(0, 2) + block(3, 2), block(0, 2) + block(2, 1),
            block(0, 2, 0x0500188b) + block(2, 2), // hybrid needs a correction file
            block(0, 2, 0x05001803) + block(2, 2), // integer
            block(0, 2, 0x05001887) + block(2, 2), // mono
            block(0, 2, 0x05003883) + block(2, 2)  // shifted/inexact sample width
        )
        invalid.forEach { bytes -> assertThrows(IllegalStateException::class.java) {
            inspectPreparedSourceAudio(ByteArrayInputStream(bytes), bytes.size.toLong())
        } }
    }

    @Test fun floatWavePreservesExactFrameCountAndSeekablePayload() {
        val bytes = wave()
        val facts = inspectPreparedSourceAudio(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(2L, facts.audibleFrames)
        assertEquals(44L, facts.dataOffset)
        assertEquals(16L, facts.dataByteLength)
        assertTrue(facts.isFloat)
        assertEquals(32, facts.bitsPerSample)
        assertEquals(48_000, facts.sampleRate)
        assertEquals(2, facts.channels)
    }

    @Test fun refusesIntegerWaveWrongRateAndTruncatedOrPartialFrames() {
        val valid = wave()
        val invalid = listOf(
            valid.copyOf().apply { this[20] = 1 },
            valid.copyOf().apply { this[24] = 1 },
            valid.copyOf().apply { this[40] = 15 },
            valid.copyOf(valid.size - 1),
            valid.copyOf().apply { this[4] = 1 }
        )
        invalid.forEach { bytes ->
            assertThrows(IllegalStateException::class.java) {
                inspectPreparedSourceAudio(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }
    }

    @Test fun mediaContractRejectsLossyTargetsAndIntegerOrClippedRangeResidualFormats() {
        val target = CustomSourceAsset("vocals", "Vocals", "part-1.flac", "a".repeat(64), 100, 2)
        val residual = target.copy(sourceId = "remaining", label = "Remaining", fileName = "remaining.wv")
        val flac = PreparedSourceAudioFacts(48_000, 2, 16, false, 2)
        val floating = PreparedSourceAudioFacts(48_000, 2, 32, true, 2, mime = "audio/wavpack")
        validateCustomSourceAudioAsset(target, target.sha256, 100, flac)
        validateCustomSourceAudioAsset(residual, residual.sha256, 100, floating)
        listOf(
            { validateCustomSourceAudioAsset(target, target.sha256, 100, floating) },
            { validateCustomSourceAudioAsset(residual, residual.sha256, 100, flac) },
            { validateCustomSourceAudioAsset(residual, residual.sha256, 100, floating.copy(bitsPerSample = 16)) },
            { validateCustomSourceAudioAsset(residual, residual.sha256, 100, floating.copy(mime = "audio/wav")) },
            { validateCustomSourceAudioAsset(target, target.sha256, 100, flac.copy(audibleFrames = 3)) }
        ).forEach { assertThrows(IllegalStateException::class.java) { it() } }
    }

    private fun wave(): ByteArray = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(52); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(3); putShort(2); putInt(48_000); putInt(384_000); putShort(8); putShort(32)
        put("data".toByteArray()); putInt(16)
        putFloat(8.5f); putFloat(-8.5f); putFloat(0f); putFloat(1f)
    }.array()
}
