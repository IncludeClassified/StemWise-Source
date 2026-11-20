package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.ErrorCollector

@app.stemwise.qualification.ManagedAudioTest
class JniPacketValidationTest {
    @get:Rule val failures = ErrorCollector()
    private fun rejects(body: () -> Unit) { failures.checkSucceeds { assertThrows(IllegalArgumentException::class.java, body) } }

    private fun audio() = LongArray(NativePacketSchema.OUTPUT_DIAGNOSTICS_COUNT).apply {
        this[0] = NativePacketSchema.OUTPUT_DIAGNOSTICS_VERSION
    }
    private fun runtime() = LongArray(NativeRuntimeDiagnosticsCodec.VALUE_COUNT).apply {
        this[0] = NativeRuntimeDiagnosticsCodec.SCHEMA_VERSION
    }

    @Test
    fun currentEmptyPacketsDecodeButNarrowingCannotAliasAValidValue() {
        assertEquals(0, NativeAudioDiagnosticsCodec.decode(audio()).sampleRateHz)
        NativeRuntimeDiagnosticsCodec.decode(runtime())
        for (index in listOf(
            NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_STATE,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_SAMPLE_RATE_HZ,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_CHANNEL_COUNT,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_FRAMES_PER_BURST,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_OUTPUT_BUFFER_CAPACITY_FRAMES,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_OUTPUT_BUFFER_SIZE_FRAMES,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_RING_CAPACITY_FRAMES,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_X_RUN_COUNT,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_ERROR_CODE,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_PERFORMANCE_MODE,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_OWNER_FIRST_PCM_FRAMES
        )) {
            val packet = audio().apply { this[index] = 1L shl 32 }
            rejects { NativeAudioDiagnosticsCodec.decode(packet) }
        }
        for (index in listOf(NativePacketSchema.RUNTIME_DIAGNOSTICS_STATUS,
            NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_PHASE,
            NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_CANCELLATION_STATUS)) {
            val packet = runtime().apply { this[index] = 1L shl 32 }
            rejects { NativeRuntimeDiagnosticsCodec.decode(packet) }
        }
    }

    @Test
    fun booleansEnumsFiniteFloatBitsAndExactArrayWidthsAreRequired() {
        for (index in listOf(NativePacketSchema.OUTPUT_DIAGNOSTICS_PLAYING,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_RECOVERY_REQUIRED,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_EFFECTIVE_RENDER_PLAYING)) {
            rejects { NativeAudioDiagnosticsCodec.decode(audio().apply { this[index] = 2 }) }
        }
        for (index in listOf(NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_STATE,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_STATE)) {
            rejects { NativeAudioDiagnosticsCodec.decode(audio().apply { this[index] = 999 }) }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejects { NativeAudioDiagnosticsCodec.decode(audio().apply { this[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_PEAK] = value.toRawBits().toLong() }) }
        }
        rejects { NativeAudioDiagnosticsCodec.decode(audio().apply { this[NativePacketSchema.OUTPUT_DIAGNOSTICS_PCM_LEVEL_PEAK] = 1L shl 32 }) }
        for (delta in listOf(-1, 1)) {
            rejects { NativeAudioDiagnosticsCodec.decode(audio().copyOf(audio().size + delta)) }
            rejects { NativeRuntimeDiagnosticsCodec.decode(runtime().copyOf(runtime().size + delta)) }
        }
    }

    @Test
    fun canonicalAdmissionEvidenceRejectsInvalidCapacityAndUnknownStatus() {
        val known = NativePacketGoldenFixture.packet("RUNTIME_DIAGNOSTICS")
        for ((field, value) in listOf(
            NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_ADMITTED_LANES to 19L,
            NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_ADMITTED_LANES to -1L,
            NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_ADMITTED_FRAME_BOUND to -1L,
            NativePacketSchema.RUNTIME_DIAGNOSTICS_CANONICAL_LAST_ADMISSION_STATUS to 99L
        )) {
            rejects { NativeRuntimeDiagnosticsCodec.decode(known.copyOf().apply { this[field] = value }) }
        }
    }

    @Test
    fun presentationJournalRejectsAliasedCapacityAndNoncanonicalFlags() {
        fun packet() = LongArray(NativePacketSchema.PRESENTATION_JOURNAL_HEADER).apply {
            this[0] = NativePacketSchema.PRESENTATION_JOURNAL_VERSION; this[1] = 1
            this[11] = NativePacketSchema.PRESENTATION_JOURNAL_ENTRY.toLong()
        }
        assertEquals(1, NativeAudioPresentationJournalCodec.decode(packet()).capacity)
        for (index in listOf(1, 8, 9, 10)) {
            val value = if (index in listOf(8, 9)) 2 else (1L shl 32) + 1
            rejects { NativeAudioPresentationJournalCodec.decode(packet().apply { this[index] = value }) }
        }
    }
}
