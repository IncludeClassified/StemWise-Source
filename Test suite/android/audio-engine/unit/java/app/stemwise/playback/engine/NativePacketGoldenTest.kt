package app.stemwise.playback.engine

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Literal wire positions are independent from the generated offsets used by both producers. */
internal object NativePacketGoldenFixture {
    private val packets by lazy {
        val path = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "Test suite/fixtures/native-packets/current-protocol.txt") }
            .first { it.isFile }
        path.readLines(Charsets.UTF_8).associate { line ->
            val fields = line.split(' ')
            fields.first() to fields.drop(1).map(String::toLong).toLongArray()
        }.also { require(it.size == 14) }
    }
    fun packet(name: String): LongArray = requireNotNull(packets[name]).copyOf()
}

@app.stemwise.qualification.ManagedAudioTest
class NativePacketGoldenTest {
    private fun packet(name: String) = NativePacketGoldenFixture.packet(name)

    @Test
    fun selectedOriginalProducerMatchesThePacketConsumedByTheNativeDecoder() {
        val owner = NativeRuntimeOwnership(31L, 41L)
        val snapshot = NativeRuntimeCommandSnapshot(1L, 41L,
            banks = listOf(NativeRuntimeBankIntent(7L, owner, firstTimelineFrame = 240L,
                outputClockStartFrame = 480L, songIdentityHash = 11L, originalContentHash = 22L,
                cacheFrameUpperBound = 43_200_000L, playbackEndFrameExclusive = 90_001L), NativeRuntimeBankIntent.unassigned(8L)),
            selectedBank = NativeRuntimeBank.FIRST, playing = false,
            controls = NativeRuntimeControls(9L, sourceIntentRevision = 51L, stemIntentRevision = 52L,
                mixHeadroom = .75f, metronome = NativeRuntimeMetronomeControl(gain = .25f)))
        val (longs, floats) = NativeRuntimeCommandCodec.encode(snapshot)
        assertArrayEquals(packet("COMMAND_LONG"), longs)
        assertArrayEquals(packet("COMMAND_FLOAT_BITS"), floats.map { it.toRawBits().toLong() }.toLongArray())
    }

    @Test
    fun nativeKnownAnswersReachTheCurrentSemanticConsumers() {
        val output = NativeAudioDiagnosticsCodec.decode(packet("OUTPUT_DIAGNOSTICS"))
        assertEquals(501L, output.streamGeneration)
        assertEquals(NativeAudioStreamState.STARTED, output.streamState)
        assertEquals(NativeAudioBackend.AUDIO_TRACK, output.backend)
        assertEquals(48_000, output.sampleRateHz)
        assertEquals(0.25f, output.pcmLevelMeanSquare, 0f)
        assertEquals(0.5f, output.pcmLevelPeak, 0f)
        assertEquals(601L, output.loop.latestCutoverGestureId)
        assertEquals(6101L, output.callbackMix.sequence)
        assertEquals(6102L, output.callbackMix.callbackSequence)
        assertEquals(6103L, output.callbackMix.generation)
        assertEquals(6104L, output.callbackMix.selectionToken)
        assertEquals(6105L, output.callbackMix.controlRevision)
        assertEquals(6106L, output.callbackMix.missingControlFrames)
        assertEquals(6107L, output.callbackMix.staleControlFrames)
        assertEquals(6108L, output.callbackMix.mixerRejectedFrames)
        assertEquals(6109L, output.callbackMix.invalidTagFrames)
        assertEquals(6110L, output.callbackMix.advanceFailures)
        assertEquals(6111L, output.callbackMix.safeOriginalFallbackFrames)
        assertEquals(6112L, output.callbackMix.mixerNonFiniteSamplesScrubbed)
        assertEquals(6113L, output.callbackMix.mixerLimiterActiveFrames)
        assertEquals(6114L, output.callbackMix.mixerLimiterSafetyClamps)
        assertEquals(6115L, output.callbackMix.finalNonFiniteSamplesScrubbed)
        assertEquals(6117L, output.callbackMix.finalSafetyClamps)
        assertEquals(6201L, output.physicalRunSequence)
        assertEquals(6199L, output.lastUnderrunPhysicalRunSequence)
        assertEquals(6301L, output.lastCallbackDeadlineMissStartMicros)
        assertEquals(6321L, output.lastCallbackDeadlineMissEndMicros)

        val runtime = NativeRuntimeDiagnosticsCodec.decode(packet("RUNTIME_DIAGNOSTICS"))
        assertEquals(NativeRuntimeStatus.RUNNING, runtime.status)
        assertEquals(701L, runtime.counters.commandSnapshotsPublished)
        assertEquals(-7L, runtime.counters.lastRenderPayloadHash)
        assertEquals(NativeRuntimeOwnership(111L, 222L), runtime.ownership.requested)
        assertEquals(61L, runtime.banks[0].appliedIntentRevision)
        assertEquals(12L, runtime.banks[0].seekCount)
        assertEquals(2001L, runtime.counters.resourceOperationsCompleted)
        assertEquals(2002L, runtime.counters.resourceLastOperationMicros)
        assertEquals(2003L, runtime.counters.resourceMaximumOperationMicros)
        assertEquals(2004L, runtime.counters.resourceMaximumQueueAgeMicros)
        assertEquals(2005L, runtime.counters.refillDeadlineMisses)
        val canonical = requireNotNull(runtime.canonicalPcmIo)
        assertEquals(NativeRuntimeOwnership(102L, 103L), canonical.ownership)
        assertEquals(4L, canonical.readOperations)
        assertEquals(131_072L, canonical.readBytes)
        assertEquals(2_400_000L, canonical.lastReadByteOffset)
        assertEquals(32_768L, canonical.lastReadBytes)
        assertEquals(1L, canonical.admittedLaneCount)
        assertEquals(28_800_000L, canonical.admittedFrameUpperBound)
        assertEquals(NativeCacheStorageStatus.LIMIT_EXCEEDED, canonical.lastAdmissionStatus)
        assertEquals(2, runtime.endOrdering.events.size)
        assertEquals(listOf(71L, 73L), runtime.endOrdering.events.map { it.sequence })
        assertEquals(NativeRuntimeEndOrderingKind.PHYSICAL_DRAIN_OBSERVED, runtime.endOrdering.events.last().kind)
        assertEquals(4, runtime.endOrdering.missingKinds.size)
        assertTrue(!runtime.endOrdering.completeHistory)

        val compact = NativeRuntimeControlProgressCodec.decode(packet("CONTROL_PROGRESS"))
        assertEquals(runtime.ownership.requested, compact.ownership.requested)
        assertEquals(runtime.banks[0].appliedIntentRevision, compact.banks[0].appliedIntentRevision)
        assertEquals(NativeRuntimeOwnership(555L, 666L), compact.presentation?.ownership)
        assertEquals(163L, compact.presentedPosition?.lastTimelineFrame)
        assertEquals(92L, compact.presentedPosition?.loopPolicyRevision)
        assertEquals(95L, compact.audibleEnd?.directLoopPolicyRevision)
        assertEquals(21L, compact.audibleControl?.controlRevision)
        assertEquals(1.5f, requireNotNull(compact.audibleControl).actualTempo, 0f)
        assertEquals(3f, requireNotNull(compact.audibleControl).actualPitchSemitones, 0f)
        assertEquals(NativeRuntimeOwnership(777L, 888L), compact.automaticSuccessor.successorOwnership)
        val summary = requireNotNull(compact.readinessProgress)
        assertEquals(141L, summary.sequence)
        assertEquals(1, summary.failureLane)
        assertEquals(667L, summary.failureGeneration)
        assertEquals(NativeRuntimeLoopPreparationFailure(NativeRuntimeOwnership(555L, 666L), 12L,
            NativeRuntimeLoopPreparationFailureReason.PIPELINE), summary.loopPreparationFailure)
        assertTrue(summary.cacheReady)

        val ready = requireNotNull(NativeRuntimeRenderReadinessDiagnosticsCodec.decode(packet("RENDER_READINESS")))
        assertEquals(NativeRuntimeBank.SECOND, ready.bank)
        assertEquals(NativeRuntimeOwnership(51L, 61L), ready.ownership)
        assertEquals(NativeRuntimeLoopPreparationFailure(NativeRuntimeOwnership(51L, 61L), 17L,
            NativeRuntimeLoopPreparationFailureReason.LANES), ready.loopPreparationFailure)
        assertEquals(12_000L, ready.lanes[0].endTimelineFrameExclusive)
        assertEquals(91L, ready.lanes[9].ingressFutureGenerationDeferrals)
        assertTrue(ready.currentSongCacheBoundToSelectedOwner)
        assertEquals(NativeCacheStorageStatus.ADMITTED, ready.cacheStorageStatus)

        val loop = NativeRuntimeLoopProtocolCodec.decode(packet("LOOP_PROTOCOL"))
        assertEquals(29L, loop.state.latestRevision)
        assertEquals(8L, loop.counters.acceptedIntents)
        assertEquals(81L, loop.counters.originalEosAccepted)
        assertEquals(82L, loop.counters.originalEosRejected)
        val audible = requireNotNull(NativeRuntimeAudibleControlCodec.decode(packet("AUDIBLE_CONTROL")))
        assertEquals(1.5f, audible.actualTempo, 0f)
        assertEquals(3f, audible.actualPitchSemitones, 0f)
        assertEquals(NativeRuntimeOwnership(31L, 41L), audible.ownership)
        assertEquals(NativeRuntimeSourceMode.STEMS, audible.actualSourceMode)
        assertEquals(64L, audible.pcmFrames)

        val journal = NativeAudioPresentationJournalCodec.decode(
            packet("PRESENTATION_JOURNAL_HEADER") + packet("PRESENTATION_JOURNAL_ENTRY")
        )
        assertEquals(4, journal.capacity)
        assertEquals(31L, journal.entries.single().selectionToken)
        assertEquals(43L, journal.entries.single().lastTimelineFrame)
        assertEquals(4L, journal.entries.single().pcmFrames)
        assertEquals(30.0 * 1.35f.toDouble(), journal.entries.single().renderSourceStartFrame, 0.0)
        assertEquals(45.0, journal.entries.single().renderSourceEndFrameExclusive, 0.0)
        val oracle = app.stemwise.qa.DirectLoopEnduranceOracle(0L, 45L,
            NativeRuntimeOwnership(31L, 32L), 3L, NativeAudioPresentationJournalSnapshot.empty(0L), 1.35f.toDouble())
        oracle.consume(journal)
    }

    @Test
    fun deadlineMissIntervalsRejectPartialReversedAndNegativePackets() {
        val start = NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_START_MICROS
        val end = NativePacketSchema.OUTPUT_DIAGNOSTICS_LAST_CALLBACK_DEADLINE_MISS_END_MICROS
        val absent = packet("OUTPUT_DIAGNOSTICS").apply { this[start] = 0L; this[end] = 0L }
        assertEquals(0L, NativeAudioDiagnosticsCodec.decode(absent).lastCallbackDeadlineMissStartMicros)
        for ((first, last) in listOf(0L to 6321L, 6301L to 0L, -1L to 6321L, 6301L to -1L,
            6301L to 6301L, 6321L to 6301L)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                NativeAudioDiagnosticsCodec.decode(packet("OUTPUT_DIAGNOSTICS").apply {
                    this[start] = first; this[end] = last
                })
            }
        }
    }

    @Test
    fun audioTrackKnownAnswerPreservesBackendAndRealUnderflowObservations() {
        val output = NativeAudioDiagnosticsCodec.decode(packet("AUDIO_TRACK_DIAGNOSTICS"))
        assertEquals(NativeAudioBackend.AUDIO_TRACK, output.backend)
        assertEquals(-1, output.framesPerBurst)
        assertEquals(1, output.performanceMode)
        assertEquals(512, output.outputBufferSizeFrames)
        assertEquals(7, output.xRunCount)
        assertEquals(19L, output.processXRunCount)
        assertEquals(5L, output.processIncompleteStreamObservationCount)
        assertTrue(output.outputConfigurationObserved)
    }

    @Test
    fun detailedMappingsKeepDistinctCountersSignedHashesAndRejectionRules() {
        val outputPacket = packet("OUTPUT_DIAGNOSTICS").apply {
            this[11] = 101L // independent literal ABI slots: accepted/rendered/callback frames
            this[12] = 102L
            this[14] = 103L
        }
        val output = NativeAudioDiagnosticsCodec.decode(outputPacket)
        assertEquals(101L, output.totalFramesAccepted)
        assertEquals(102L, output.renderedFrames)
        assertEquals(103L, output.callbackFrames)
        val runtimePacket = packet("RUNTIME_DIAGNOSTICS").apply {
            this[2] = 801L
            this[3] = 802L
            this[4] = 803L
            this[26] = Long.MIN_VALUE // fingerprint hashes preserve all bits
        }
        val counters = NativeRuntimeDiagnosticsCodec.decode(runtimePacket).counters
        assertEquals(801L, counters.commandSnapshotsPublished)
        assertEquals(802L, counters.commandSnapshotsConsumed)
        assertEquals(803L, counters.commandSnapshotsSuperseded)
        assertEquals(Long.MIN_VALUE, counters.lastRenderPayloadHash)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeDiagnosticsCodec.decode(runtimePacket.copyOf().apply { this[2] = -1L })
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NativeAudioDiagnosticsCodec.decode(outputPacket.copyOf().apply { this[40] = -1L })
        }
        val loopFailure = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NativeRuntimeLoopProtocolCodec.decode(packet("LOOP_PROTOCOL").apply {
                this[NativePacketSchema.LOOP_PROTOCOL_ACCEPTED_INTENTS] = -1L
            })
        }
        assertEquals("accepted loop intents cannot be negative", loopFailure.message)
    }

    @Test
    fun managedInputProducersMatchTheSameIndependentNativeKnownAnswers() {
        val intent = NativeRuntimeManagedLoopIntent(
            revision = 21L, ownership = NativeRuntimeOwnership(31L, 41L), enabled = true,
            cacheKey = NativeRuntimeManagedLoopContentKey(411L, 412L, 413L, 51L, 21L, 100L, 192_100L),
            requiredLaneMask = 1, declickFrames = 96
        )
        assertArrayEquals(packet("LOOP_INTENT"), NativeRuntimeManagedLoopIntentCodec.encode(intent))
        val ready = NativeRuntimeManagedLoopIngressReady(
            requestSequence = 11L, loopRevision = 21L, targetCycle = 2L,
            ownership = NativeRuntimeOwnership(31L, 41L), ingressEpoch = 42L,
            readyLaneMask = 3, eosPaddedLaneMask = 2, firstTimelineFrame = 100L,
            endTimelineFrameExclusive = 400L
        )
        assertArrayEquals(packet("LOOP_INGRESS_READY"), NativeRuntimeManagedLoopIngressReadyCodec.encode(ready))
    }

    @Test
    fun everyFixedOutputRejectsWrongWidthsSchemasAndUnknownCodes() {
        val decoders: List<Pair<String, (LongArray) -> Any?>> = listOf(
            "OUTPUT_DIAGNOSTICS" to NativeAudioDiagnosticsCodec::decode,
            "RUNTIME_DIAGNOSTICS" to NativeRuntimeDiagnosticsCodec::decode,
            "CONTROL_PROGRESS" to NativeRuntimeControlProgressCodec::decode,
            "RENDER_READINESS" to NativeRuntimeRenderReadinessDiagnosticsCodec::decode,
            "LOOP_PROTOCOL" to NativeRuntimeLoopProtocolCodec::decode,
            "AUDIBLE_CONTROL" to NativeRuntimeAudibleControlCodec::decode
        )
        decoders.forEach { (name, decode) ->
            val values = packet(name)
            for (delta in listOf(-1, 1)) rejects("$name width $delta") { decode(values.copyOf(values.size + delta)) }
            rejects("$name schema") { decode(values.copyOf().apply { this[0] += 1L }) }
        }
        val enums = mapOf(
            "OUTPUT_DIAGNOSTICS" to listOf(NativePacketSchema.OUTPUT_DIAGNOSTICS_STREAM_STATE,
                NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND, NativePacketSchema.OUTPUT_DIAGNOSTICS_MANUAL_SELECTION_GATE_STATE),
            "RUNTIME_DIAGNOSTICS" to listOf(NativePacketSchema.RUNTIME_DIAGNOSTICS_STATUS, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_PHASE, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUTOMATIC_CANCELLATION_STATUS),
            "CONTROL_PROGRESS" to listOf(NativePacketSchema.CONTROL_PROGRESS_STATUS, NativePacketSchema.CONTROL_PROGRESS_AUDIBLE_CONTROL_ACTUAL_SOURCE_MODE, NativePacketSchema.CONTROL_PROGRESS_AUTOMATIC_PHASE, NativePacketSchema.CONTROL_PROGRESS_AUTOMATIC_CANCELLATION_STATUS, NativePacketSchema.CONTROL_PROGRESS_READINESS_STORAGE_STATUS),
            "RENDER_READINESS" to listOf(3, 7, 10, NativePacketSchema.RENDER_READINESS_CACHE_STORAGE_STATUS),
            "LOOP_PROTOCOL" to listOf(3, 4, 5, 74, 112, 113, 114, 115, 116, 117, 118, 121, 129),
            "AUDIBLE_CONTROL" to listOf(5)
        )
        decoders.forEach { (name, decode) ->
            enums.getValue(name).forEach { index ->
                rejects("$name unknown code at $index") { decode(packet(name).apply { this[index] = 999L }) }
            }
        }
        val journal = packet("PRESENTATION_JOURNAL_HEADER") + packet("PRESENTATION_JOURNAL_ENTRY")
        for (delta in listOf(-1, 1)) rejects("journal width") {
            NativeAudioPresentationJournalCodec.decode(journal.copyOf(journal.size + delta))
        }
        rejects("journal flag") { NativeAudioPresentationJournalCodec.decode(journal.copyOf().apply { this[9] = 2L }) }
    }

    @Volatile private var measuredObservation: NativeRuntimeControlObservation? = null

    @Test
    fun compactControlPacketDecodingAllocatesLessThanDetailedTelemetry() {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocation = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val thread = Thread.currentThread().id
        val full = packet("RUNTIME_DIAGNOSTICS")
        val compact = packet("CONTROL_PROGRESS")
        repeat(512) {
            measuredObservation = NativeRuntimeDiagnosticsCodec.decode(full)
            measuredObservation = NativeRuntimeControlProgressCodec.decode(compact)
        }
        fun measure(decode: () -> NativeRuntimeControlObservation): Long {
            val before = allocation.invoke(bean, thread) as Long
            repeat(4_096) { measuredObservation = decode() }
            return (allocation.invoke(bean, thread) as Long) - before
        }
        val detailedBytes = measure { NativeRuntimeDiagnosticsCodec.decode(full) }
        val compactBytes = measure { NativeRuntimeControlProgressCodec.decode(compact) }
        println("host decoder allocated-bytes-per-packet detailed=${detailedBytes / 4_096L} compact=${compactBytes / 4_096L}")
        assertTrue("Compact decode must avoid full counters/history allocation", compactBytes < detailedBytes)
        measuredObservation = null
    }

    private fun rejects(label: String, action: () -> Any?) {
        assertTrue("accepted invalid $label", runCatching(action).isFailure)
    }
}
