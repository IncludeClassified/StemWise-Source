package app.stemwise.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

class OggOpusInspectorTest {
    @Test fun originalFileInspectionForwardsCancellationAtEachPage() {
        val file = File.createTempFile("original-inspection-", ".opus",
            projectRoot().parentFile.resolve("Test suite/work").apply { mkdirs() })
        try {
            file.writeBytes(stream())
            var pages = 0
            assertEquals(648L, inspectOriginalAudio(file) { pages++ }.audibleFrames48k)
            assertEquals(3, pages)
            pages = 0
            val cancellation = java.util.concurrent.CancellationException("Original inspection cancelled")
            assertSame(cancellation, assertThrows(java.util.concurrent.CancellationException::class.java) {
                inspectOriginalAudio(file) {
                    if (++pages == 2) throw cancellation
                }
            })
            assertEquals(2, pages)
        } finally { check(file.delete()) }
    }

    @Test fun realEncodedFixtureAndPackagedOriginalRetainTheirTimelineFacts() {
        val fixture = realFixture()
        val facts = inspect(fixture)
        assertEquals(2, facts.channels)
        assertEquals(312, facts.preSkipSamples)
        assertEquals(16_800L, facts.decodedAudibleFrameCount)
        assertEquals(350_000L, facts.durationUs)
        assertEquals(facts.decodedAudibleFrameCount,
            facts.encodedFrameCount - facts.preSkipSamples - facts.endTrimFrameCount)
        val original = projectRoot().resolve("app/src/development/assets/demo_library/DEMOUI00001/original.opus")
        assertTrue(OggOpusInspector.inspect(original).decodedAudibleFrameCount > 0L)
    }

    @Test fun providerStreamRetainsGainAndDoesNotTakeCloseOwnership() {
        val bytes = stream(head = head().also { it[17] = 1 })
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val facts = OggOpusInspector.inspect(input, bytes.size.toLong())
        assertEquals(648L, facts.decodedAudibleFrameCount)
        assertEquals(256, facts.outputGainQ8)
        assertFalse(closed)
        assertEquals("ASSET_SIZE_MISMATCH", runCatching {
            OggOpusInspector.inspect(ByteArrayInputStream(bytes), bytes.size.toLong() - 1)
        }.exceptionOrNull()?.message)
        assertThrows(java.io.EOFException::class.java) { OggOpusInspector.inspect(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }
    }

    @Test fun embeddedIdentificationAndMissingMandatoryHeadersAreRejected() {
        rejected("embedded head", stream(head = byteArrayOf(42) + head()))
        rejected("no tags", page(0, 2, 0, head()) + page(1, 4, 960, SILENCE))
        rejected("forged tag length", stream(tags = TAGS.copyOf().also { it[8] = 127 }))
        rejected("tag and audio share page", page(0, 2, 0, head()) +
            page(1, 0, 0, TAGS + SILENCE, byteArrayOf(TAGS.size.toByte(), SILENCE.size.toByte())) +
            page(2, 4, 960, SILENCE))
    }

    @Test fun crcBosEosSerialSequenceAndReservedFlagsAreEnforced() {
        val valid = pages(stream())
        rejected("bad crc", valid.reduce(ByteArray::plus).also { it[it.lastIndex] = 0 })
        for ((name, index, mutate) in listOf<Triple<String, Int, (ByteArray) -> Unit>>(
            Triple("missing BOS", 0) { it[5] = 0 },
            Triple("missing EOS", 2) { it[5] = 0 },
            Triple("unexpected BOS", 2) { it[5] = 6 },
            Triple("unknown flag", 2) { it[5] = 12 },
            Triple("serial swap", 2) { it[14] = 9 },
            Triple("sequence gap", 2) { it[18] = 4 },
            Triple("continued without packet", 2) { it[5] = 5 }
        )) {
            val changed = valid.map(ByteArray::clone).toMutableList()
            mutate(changed[index]); checksum(changed[index])
            rejected(name, changed.reduce(ByteArray::plus))
        }
        rejected("multiplex", valid[0] + page(0, 2, 0, head(), serial = 2) + valid.drop(1).reduce(ByteArray::plus))
        rejected("chain", stream() + stream(serial = 2))
        rejected("trailing page", stream() + page(3, 0, 1920, SILENCE))
    }

    @Test fun identificationVersionChannelsAndMappingAreValidated() {
        for ((index, value) in listOf(8 to 16, 9 to 0, 9 to 3, 18 to 1)) {
            rejected("head field $index=$value", stream(head = head().also { it[index] = value.toByte() }))
        }
        val mapped = head().also { it[9] = 3; it[18] = 1 } + byteArrayOf(2, 1, 0, 2, 1)
        assertEquals(3, inspect(stream(head = mapped)).channels)
        rejected("invalid map index", stream(head = mapped.copyOf().also { it[23] = 3 }))
        assertEquals(2, inspect(stream(head = head().also { it[8] = 2 } + byteArrayOf(0))).channels)
    }

    @Test fun continuedCommentPacketsAreParsedWithoutRetainingTheirBody() {
        val tags = TAGS.copyOf(70_000) // legal trailing metadata padding
        val bytes = page(0, 2, 0, head()) +
            page(1, 0, -1, tags.copyOfRange(0, 65_025), ByteArray(255) { 255.toByte() }) +
            page(2, 1, 0, tags.copyOfRange(65_025, tags.size)) + page(3, 4, 960, SILENCE)
        assertEquals(648L, inspect(bytes).decodedAudibleFrameCount)
        val changed = pages(bytes).map(ByteArray::clone).toMutableList()
        changed[2][5] = 0; checksum(changed[2])
        rejected("missing continuation", changed.reduce(ByteArray::plus))
        rejected("unterminated tags", pages(bytes).take(2).reduce(ByteArray::plus))
    }

    @Test fun audioGranulesCannotInventDurationOrMoveBackwards() {
        val first = page(0, 2, 0, head()) + page(1, 0, 0, TAGS)
        rejected("invented samples", first + page(2, 0, 960, SILENCE) + page(3, 4, 5000, SILENCE))
        rejected("backward", first + page(2, 0, 960, SILENCE) + page(3, 4, 959, SILENCE))
        rejected("empty audio", first + page(2, 4, 960, byteArrayOf()))
        assertEquals(1296L, inspect(first + page(2, 0, 48_960, SILENCE) +
            page(3, 4, 49_608, SILENCE)).decodedAudibleFrameCount)
    }

    @Test fun nativeClockRetainsPreskipEndTrimAndAbsoluteGranuleOrigin() {
        val first = page(0, 2, 0, head()) + page(1, 0, 0, TAGS)
        val bytes = first + page(2, 0, 48_960, SILENCE) + page(3, 4, 49_608, SILENCE)
        val facts = inspect(bytes)
        assertEquals(1_920L, facts.encodedFrameCount)
        assertEquals(312, facts.preSkipSamples)
        assertEquals(312L, facts.endTrimFrameCount)
        assertEquals(48_000L, facts.initialGranuleOffset)
        assertEquals(1_296L, facts.decodedAudibleFrameCount)
        val original = inspectOriginalAudio(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(48_000, original.sampleRateHz)
        assertEquals(1_296L, original.audibleFramesNative)
        assertEquals(facts.encodedFrameCount, original.encodedFramesNative)
        assertEquals(312L, original.preSkipFrames)
        assertEquals(312L, original.endTrimFrames)
        assertEquals(48_000L, original.initialGranuleOffset)
    }

    private fun rejected(label: String, bytes: ByteArray) {
        val failure = runCatching { inspect(bytes) }.exceptionOrNull()
        assertNotNull("Accepted $label", failure)
        assertEquals(label, "MEDIA_UNSUPPORTED", failure?.message)
    }
    private fun inspect(bytes: ByteArray) = OggOpusInspector.inspect(ByteArrayInputStream(bytes), bytes.size.toLong())
    private fun stream(head: ByteArray = head(), tags: ByteArray = TAGS, serial: Int = 1) =
        page(0, 2, 0, head, serial = serial) + page(1, 0, 0, tags, serial = serial) +
            page(2, 4, 960, SILENCE, serial = serial)

    private fun head() = ByteArray(19).also {
        "OpusHead".toByteArray().copyInto(it); it[8] = 1; it[9] = 2
        it[10] = 0x38; it[11] = 1; it[12] = 0x80.toByte(); it[13] = 0xbb.toByte()
    }
    private fun page(sequence: Int, flags: Int, granule: Long, body: ByteArray,
                     lace: ByteArray = (List(body.size / 255) { 255.toByte() } + (body.size % 255).toByte()).toByteArray(),
                     serial: Int = 1): ByteArray {
        require(lace.size <= 255)
        return ByteArray(27 + lace.size + body.size).also {
            "OggS".toByteArray().copyInto(it); it[5] = flags.toByte()
            repeat(8) { b -> it[6 + b] = (granule ushr (b * 8)).toByte() }
            repeat(4) { b -> it[14 + b] = (serial ushr (b * 8)).toByte(); it[18 + b] = (sequence ushr (b * 8)).toByte() }
            it[26] = lace.size.toByte(); lace.copyInto(it, 27); body.copyInto(it, 27 + lace.size)
            checksum(it)
        }
    }
    // Independent bit-at-a-time Ogg polynomial, including the zeroed checksum field.
    private fun checksum(page: ByteArray) {
        page.fill(0, 22, 26)
        var crc = 0
        for (byte in page) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        repeat(4) { page[22 + it] = (crc ushr (it * 8)).toByte() }
    }
    private fun pages(bytes: ByteArray): List<ByteArray> {
        val output = mutableListOf<ByteArray>(); var offset = 0
        while (offset < bytes.size) {
            val count = bytes[offset + 26].toInt() and 255
            val length = 27 + count + (0 until count).sumOf { bytes[offset + 27 + it].toInt() and 255 }
            output += bytes.copyOfRange(offset, offset + length); offset += length
        }
        return output
    }
    private fun realFixture() = Base64.getMimeDecoder().decode(projectRoot().parentFile.resolve(
        "Test suite/android/instrumentation/assets/decoder/canonical-opus.ogg.base64").readText())
    private fun projectRoot() = generateSequence<File>(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { it.resolve("app/src/main").isDirectory }
    companion object {
        private val SILENCE = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        private val TAGS = "OpusTags".toByteArray() + ByteArray(8)
    }
}
