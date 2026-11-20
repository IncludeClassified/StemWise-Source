package app.stemwise.integration

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RemainingRenderCommandTest {
    @Test fun subtractsDecodedFinalTargetsWithoutNormalizationOrTrimming() {
        val original = File("active-original48.f32")
        val targets = listOf(File("part-1.flac"), File("part-2.flac"))
        val command = remainingWavPackEncodeCommand(File("ffmpeg"), original, targets, File("remaining.wv"), 9000)
        assertEquals(listOf(original.absolutePath) + targets.map(File::getAbsolutePath),
            command.indices.filter { command[it] == "-i" }.map { command[it + 1] })
        val filter = command[command.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("val(0)-val(2)-val(4)|val(1)-val(3)-val(5)"))
        assertFalse(filter.contains("amix"))
        assertFalse(filter.contains("atrim"))
        assertFalse(command.contains("-shortest"))
        assertTrue(filter.contains("sample_fmts=dbl"))
        assertEquals("wavpack", command[command.indexOf("-c:a") + 1])
        assertEquals("fltp", command[command.indexOf("-sample_fmt") + 1])
        assertEquals("0", command[command.indexOf("-compression_level") + 1])
        assertEquals("wv", command[command.lastIndexOf("-f") + 1])
        assertFalse(command.contains("-b:a"))
    }

    @Test fun rejectsMissingTargetsAndUnboundedRequests() {
        for (targets in listOf(emptyList(), List(17) { File("part-$it.flac") })) {
            assertTrue(runCatching {
                remainingWavPackEncodeCommand(File("ffmpeg"), File("original.opus"), targets, File("remaining.wv"), 1)
            }.isFailure)
        }
    }

    @Test fun canonicalNativeOperandIsDemuxedAsStereoFloat48WithoutAnotherResampler() {
        val original = File("active-original48.f32")
        val command = remainingWavPackEncodeCommand(File("ffmpeg"), original,
            listOf(File("part-1.flac")), File("remaining.wv"), 9000)
        val firstInput = command.indexOf("-i")
        assertEquals(listOf("-f", "f32le", "-ar", "48000", "-ac", "2"),
            command.subList(firstInput - 6, firstInput))
        assertEquals(original.absolutePath, command[firstInput + 1])
        val filter = command[command.indexOf("-filter_complex") + 1]
        assertTrue(filter.contains("[0:a:0]aformat=sample_fmts=dbl:sample_rates=48000:channel_layouts=stereo[a0]"))
        assertFalse(filter.contains("pan="))
        assertTrue(filter.contains("[1:a:0]aformat=sample_fmts=dbl:sample_rates=48000:channel_layouts=stereo[a1]"))
    }

    @Test fun outputBudgetIncludesBothCanonicalOriginalAndRemaining() {
        val canonicalBytes = 48_001L * 2 * 4
        assertEquals(9000L, remainingOutputByteLimit(48_001L, canonicalBytes + 9000L))
        assertEquals("PREPARATION_SCRATCH_PEAK_EXCEEDED", assertThrows(IllegalStateException::class.java) {
            remainingOutputByteLimit(48_001L, canonicalBytes)
        }.message)
    }
}
