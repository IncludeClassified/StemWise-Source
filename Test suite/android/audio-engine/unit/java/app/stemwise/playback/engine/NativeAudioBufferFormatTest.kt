package app.stemwise.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NativeAudioBufferFormatTest {
    @Test fun capacityMustHoldACompleteProductionPushBlock() {
        for (frames in listOf(0, 256, NativePacketSchema.TRACK_BLOCK_FRAMES - 1,
            NativeAudioBufferFormat.MAX_CAPACITY_FRAMES + 1)) {
            assertTrue(runCatching { NativeAudioBufferFormat.requireCapacity(frames) }.isFailure)
        }
        NativeAudioBufferFormat.requireCapacity(NativePacketSchema.TRACK_BLOCK_FRAMES)
        NativeAudioBufferFormat.requireCapacity(NativeAudioBufferFormat.DEFAULT_CAPACITY_FRAMES)
    }

    @Test
    fun audioTrackConfigurationRequiresActualCountersAndExplicitUnavailableFields() {
        val raw = NativePacketGoldenFixture.packet("AUDIO_TRACK_DIAGNOSTICS")
        assertTrue(NativeAudioDiagnosticsCodec.decode(raw).outputConfigurationObserved)
        val normalMode = raw.copyOf().also { it[NativePacketSchema.OUTPUT_DIAGNOSTICS_PERFORMANCE_MODE] = 0L }
        assertTrue(NativeAudioDiagnosticsCodec.decode(normalMode).outputConfigurationObserved)
        assertEquals(0, NativeAudioDiagnosticsCodec.decode(normalMode).performanceMode)
        for ((field, value) in listOf(
            NativePacketSchema.OUTPUT_DIAGNOSTICS_X_RUN_COUNT to -1L,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_FRAMES_PER_BURST to 0L,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_PERFORMANCE_MODE to 12L,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_PERFORMANCE_MODE to 2L,
            NativePacketSchema.OUTPUT_DIAGNOSTICS_SAMPLE_RATE_HZ to 0L
        )) {
            val unavailable = raw.copyOf().also { it[field] = value }
            assertFalse("unavailable or invented AudioTrack output field $field",
                NativeAudioDiagnosticsCodec.decode(unavailable).outputConfigurationObserved)
        }
        val unsupportedBackend = raw.copyOf().also { it[NativePacketSchema.OUTPUT_DIAGNOSTICS_BACKEND] = 2L }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            NativeAudioDiagnosticsCodec.decode(unsupportedBackend)
        }
    }

    @Test
    fun diagnosticCodecPreservesCountersAndRecoveryStatus() {
        val raw = LongArray(NativePacketSchema.OUTPUT_DIAGNOSTICS_COUNT)
        raw[0] = NativePacketSchema.OUTPUT_DIAGNOSTICS_VERSION
        raw[1] = 9
        raw[2] = NativeAudioStreamState.DISCONNECTED.rawValue.toLong()
        raw[3] = NativeAudioBackend.AUDIO_TRACK.rawValue.toLong()
        raw[4] = 48_000
        raw[5] = 2
        raw[9] = 16_384
        raw[10] = 256
        raw[11] = 1_024
        raw[12] = 768
        raw[15] = 3
        raw[16] = 2
        raw[17] = 256
        raw[20] = 9
        raw[22] = 1
        raw[23] = 1
        raw[24] = -899
        raw[30] = 701
        raw[31] = 9_001
        raw[32] = 24_000
        raw[33] = 3
        raw[34] = 8_192
        raw[35] = 17
        raw[36] = 192
        raw[38] = 17
        raw[39] = 2
        raw[40] = 0
        raw[41] = NativeAudioManualSelectionGateState.AUTHORIZED.rawValue.toLong()
        raw[42] = 10
        raw[43] = 4
        raw[44] = 3
        raw[45] = 1
        raw[46] = 2
        raw[47] = 123
        raw[48] = 41
        raw[49] = 10
        raw[50] = 701
        raw[51] = 9_101
        raw[52] = 211
        raw[53] = 192
        raw[54] = 0.0625f.toBits().toLong()
        raw[55] = 0.75f.toBits().toLong()
        raw[56] = 101
        raw[57] = 102
        raw[58] = 103
        raw[59] = 104
        raw[60] = 105
        raw[61] = 2_000
        raw[62] = 64_000
        raw[63] = 7
        raw[64] = 10
        raw[65] = 10
        raw[66] = 211
        raw[67] = 96
        raw[68] = 0
        raw[69] = 0
        raw[70] = 0
        raw[71] = 96
        raw[72] = 2_000
        raw[73] = 64_000
        raw[74] = 200
        raw[75] = 211
        raw[76] = 9
        raw[77] = 0
        raw[78] = 0
        raw[79] = 1
        raw[80] = 1
        raw[81] = 96
        raw[82] = 42
        raw[83] = 1_000_000
        raw[84] = 10
        raw[85] = 96
        raw[86] = 1_152
        raw[87] = 5_000
        raw[88] = 1_004_000
        raw[89] = 1
        raw[90] = 42
        raw[91] = 10
        raw[92] = 4_000
        raw[93] = 96
        raw[94] = 96
        raw[95] = 5_000
        raw[96] = 40
        raw[97] = 5
        raw[98] = 77
        raw[99] = 48_000
        raw[100] = 96_000
        raw[101] = 95_000
        raw[102] = 1
        raw[103] = 0
        raw[104] = 72
        raw[105] = 48_000
        raw[106] = 72_000
        raw[107] = 71_950
        raw[108] = 4
        raw[109] = 70
        raw[110] = 211
        raw[111] = 6_200
        raw[112] = 0
        raw[113] = 0
        raw[114] = 0
        raw[115] = -1
        raw[116] = 41
        raw[117] = 41
        raw[118] = 1
        raw[119] = 7
        raw[120] = 11
        raw[121] = 24_000
        raw[122] = 41
        raw[139] = 18
        raw[140] = 17

        val diagnostics = NativeAudioDiagnosticsCodec.decode(raw)

        assertEquals(9L, diagnostics.streamGeneration)
        assertEquals(NativeAudioStreamState.DISCONNECTED, diagnostics.streamState)
        assertEquals(NativeAudioBackend.AUDIO_TRACK, diagnostics.backend)
        assertEquals(768L, diagnostics.renderedFrames)
        assertEquals(2L, diagnostics.underrunCallbackCount)
        assertEquals(256L, diagnostics.underrunFrames)
        assertTrue(diagnostics.playing)
        assertFalse(diagnostics.effectiveRenderPlaying)
        assertTrue(diagnostics.recoveryRequired)
        assertEquals(-899, diagnostics.lastErrorCode)
        assertEquals(701L, diagnostics.lastPresentedSelectionToken)
        assertEquals(9_001L, diagnostics.lastPresentedFingerprintSequence)
        assertEquals(24_000L, diagnostics.lastPresentedTimelineFrame)
        assertEquals(3L, diagnostics.presentedOwnerSequence)
        assertEquals(8_192L, diagnostics.lastOwnerFirstOutputFrame)
        assertEquals(17L, diagnostics.lastOwnerFirstCallbackSequence)
        assertEquals(192, diagnostics.lastOwnerFirstPcmFrames)
        assertEquals(0L, diagnostics.invalidOwnershipTagFrames)
        assertEquals(17L, diagnostics.processConstructionCount)
        assertEquals(2L, diagnostics.processLiveInstanceCount)
        assertEquals(
            NativeAudioManualSelectionGateState.AUTHORIZED,
            diagnostics.manualSelectionGateState
        )
        assertEquals(10L, diagnostics.manualSelectionAuthorizedGeneration)
        assertEquals(4L, diagnostics.manualSelectionGateArmCount)
        assertEquals(3L, diagnostics.manualSelectionGateAuthorizationCount)
        assertEquals(1L, diagnostics.manualSelectionGateRejectedAuthorizationCount)
        assertEquals(2L, diagnostics.manualSelectionGateOpenCount)
        assertEquals(123L, diagnostics.manualSelectionGateLastArmCallbackSequence)
        assertEquals(41L, diagnostics.pcmLevelSequence)
        assertEquals(10L, diagnostics.pcmLevelGeneration)
        assertEquals(701L, diagnostics.pcmLevelSelectionToken)
        assertEquals(9_101L, diagnostics.pcmLevelFingerprintSequence)
        assertEquals(211L, diagnostics.pcmLevelCallbackSequence)
        assertEquals(192L, diagnostics.pcmLevelFrames)
        assertEquals(0.0625f, diagnostics.pcmLevelMeanSquare)
        assertEquals(0.75f, diagnostics.pcmLevelPeak)
        assertEquals(101L, diagnostics.processInvalidOwnershipTagFrames)
        assertEquals(102L, diagnostics.processUnderrunCallbackCount)
        assertEquals(103L, diagnostics.processUnderrunFrames)
        assertEquals(104L, diagnostics.processXRunCount)
        assertEquals(105L, diagnostics.processCallbackDeadlineMissCount)
        assertEquals(2_000L, diagnostics.callbackStartGapMicros)
        assertEquals(64_000L, diagnostics.maximumCallbackStartGapMicros)
        assertEquals(18L, diagnostics.physicalRunSequence)
        assertEquals(17L, diagnostics.lastUnderrunPhysicalRunSequence)
        assertEquals(7L, diagnostics.lastUnderrunSequence)
        assertEquals(10L, diagnostics.lastUnderrunGenerationAtRenderDecision)
        assertEquals(10L, diagnostics.lastUnderrunGenerationAtCompletion)
        assertEquals(211L, diagnostics.lastUnderrunCallbackSequence)
        assertEquals(96L, diagnostics.lastUnderrunRequestedFrames)
        assertEquals(0L, diagnostics.lastUnderrunQueuedFramesAtRenderDecision)
        assertEquals(0L, diagnostics.lastUnderrunPhysicalFrames)
        assertEquals(0L, diagnostics.lastUnderrunValidPcmFrames)
        assertEquals(96L, diagnostics.lastUnderrunMissingFrames)
        assertEquals(2_000L, diagnostics.lastUnderrunCallbackStartGapMicros)
        assertEquals(64_000L, diagnostics.lastUnderrunLatestLateCallbackStartGapMicros)
        assertEquals(200L, diagnostics.lastUnderrunLatestLateCallbackSequence)
        assertEquals(211L, diagnostics.lastUnderrunManualArmCallbackCeiling)
        assertEquals(9L, diagnostics.lastUnderrunAppliedCommitSequence)
        assertFalse(diagnostics.lastUnderrunRoleSwapAtCallbackStart)
        assertFalse(diagnostics.lastUnderrunRoleSwapDuringDrain)
        assertTrue(diagnostics.lastUnderrunWasManualArmBoundary)
        assertEquals(1L, diagnostics.processManualArmBoundaryUnderrunCallbackCount)
        assertEquals(96L, diagnostics.processManualArmBoundaryUnderrunFrames)
        assertEquals(42L, diagnostics.successfulEnqueueSequence)
        assertEquals(1_000_000L, diagnostics.lastSuccessfulEnqueueMonotonicMicros)
        assertEquals(10L, diagnostics.lastSuccessfulEnqueueGeneration)
        assertEquals(96L, diagnostics.lastSuccessfulEnqueueAcceptedFrames)
        assertEquals(1_152L, diagnostics.lastSuccessfulEnqueueQueueAfterFrames)
        assertEquals(5_000L, diagnostics.maximumSuccessfulEnqueueGapMicros)
        assertEquals(1_004_000L, diagnostics.lastUnderrunMonotonicMicros)
        assertTrue(diagnostics.lastUnderrunSuccessfulEnqueueSnapshotCoherent)
        assertEquals(42L, diagnostics.lastUnderrunSuccessfulEnqueueSequence)
        assertEquals(10L, diagnostics.lastUnderrunSuccessfulEnqueueGeneration)
        assertEquals(4_000L, diagnostics.lastUnderrunSuccessfulEnqueueAgeMicros)
        assertEquals(96L, diagnostics.lastUnderrunSuccessfulEnqueueAcceptedFrames)
        assertEquals(96L, diagnostics.lastUnderrunSuccessfulEnqueueQueueAfterFrames)
        assertEquals(5_000L, diagnostics.lastUnderrunMaximumSuccessfulEnqueueGapMicros)
        assertEquals(40L, diagnostics.loop.acceptedDemandCount)
        assertEquals(5L, diagnostics.loop.acceptedCaptureCount)
        assertEquals(77L, diagnostics.loop.latestDemandRevision)
        assertTrue(diagnostics.loop.latestDemandBoundaryArmed)
        assertFalse(diagnostics.loop.latestDemandBoundaryCrossed)
        assertEquals(72L, diagnostics.loop.latestCaptureRevision)
        assertEquals(4L, diagnostics.loop.successfulCaptureCutoverCount)
        assertEquals(6_200L, diagnostics.loop.latestCutoverInputLatencyMicros)
        assertEquals(0L, diagnostics.loop.processBoundaryMissCount)
        assertEquals(-1L, diagnostics.loop.latestMissPresentedFrame)
        assertEquals(41L, diagnostics.loop.latestDemandGestureId)
        assertEquals(41L, diagnostics.loop.latestCaptureGestureId)
        assertTrue(diagnostics.loop.latestCaptureInferred)
        assertEquals(41L, diagnostics.loop.latestCutoverGestureId)
        assertFalse(diagnostics.streamState == NativeAudioStreamState.STARTED)
    }

    @Test
    fun diagnosticCodecRejectsNegativeCallbackMixCounters() {
        for (index in NativePacketSchema.OUTPUT_DIAGNOSTICS_CALLBACK_MIX_SEQUENCE until NativePacketSchema.OUTPUT_DIAGNOSTICS_COUNT) {
            val raw = NativePacketGoldenFixture.packet("OUTPUT_DIAGNOSTICS")
            raw[index] = -1L
            org.junit.Assert.assertTrue("negative callback or physical-run counter $index admitted",
                runCatching { NativeAudioDiagnosticsCodec.decode(raw) }.isFailure)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagnosticCodecRejectsUnknownSchema() {
        NativeAudioDiagnosticsCodec.decode(NativePacketGoldenFixture.packet("OUTPUT_DIAGNOSTICS").also {
            it[0] = NativePacketSchema.OUTPUT_DIAGNOSTICS_VERSION + 1L
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagnosticCodecRejectsTruncatedCurrentPayload() {
        NativeAudioDiagnosticsCodec.decode(NativePacketGoldenFixture.packet("OUTPUT_DIAGNOSTICS")
            .copyOf(NativePacketSchema.OUTPUT_DIAGNOSTICS_COUNT - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun diagnosticCodecRejectsNegativeProcessFaultTotal() {
        NativeAudioDiagnosticsCodec.decode(NativePacketGoldenFixture.packet("OUTPUT_DIAGNOSTICS").also {
            it[NativePacketSchema.OUTPUT_DIAGNOSTICS_PROCESS_INVALID_OWNERSHIP_TAG_FRAMES] = -1L
        })
    }

    @Test
    fun futureJournalCursorPreservesHeadMetadataAndTheNextIncrementalSpan() {
        val header = longArrayOf(4, 4, Long.MAX_VALUE, 7, 4, 0, 0, 0, 0, 1, 0, 20)
        val head = NativeAudioPresentationJournalCodec.decode(header)

        assertEquals(Long.MAX_VALUE, head.requestedAfterSequence)
        assertEquals(7L, head.writeSequence)
        assertEquals(7L, head.consumedThroughSequence)
        assertEquals(4L, head.oldestAvailableSequence)
        assertEquals(0L, head.overflowCount)
        assertEquals(0L, head.writerCollisionCount)
        assertEquals(0L, head.invalidPublicationCount)
        assertFalse(head.sequenceExhausted)
        assertTrue(head.coherent)
        assertTrue(head.entries.isEmpty())

        val next = NativeAudioPresentationJournalCodec.decode(longArrayOf(
            4, 4, head.writeSequence, 8, 5, 0, 0, 0, 0, 1, 1, 20,
            8, 16, 106, 1, 1, 8, 4, 0, 0, 40, 43, 0, 0, 0, 0, 0, 0, 0, -1, 0
        ))
        assertEquals(8L, next.entries.single().sequence)
        assertEquals(8L, next.entries.single().callbackSequence)
        assertEquals(8L, next.consumedThroughSequence)
        assertEquals(5L, next.oldestAvailableSequence)
        assertTrue(next.coherent)

        val failed = NativeAudioPresentationJournalCodec.decode(header.copyOf().apply {
            this[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_INVALID_PUBLICATIONS] = 1L
            this[NativePacketSchema.PRESENTATION_JOURNAL_HEADER_COHERENT] = 0L
        })
        assertEquals(1L, failed.invalidPublicationCount)
        assertFalse(failed.coherent)
        assertTrue(failed.entries.isEmpty())
    }

    @Test
    fun presentationJournalCodecPreservesEveryContiguousOwnerSpan() {
        val raw = longArrayOf(
            4, 4, 5, 7, 4, 0, 0, 0, 0, 1, 2, 20,
            6, 16, 106, 1_006, 1_106, 206, 192, 306, 306, 4_006, 4_197, 0, 0, 0, 0, 0, 0, 0, -1, 0,
            7, 17, 107, 1_007, 1_107, 207, 128, 307, 307, 5_007, 5_134, 0, 0, 0, 0, 0, 0, 0, -1, 0
        )

        val snapshot = NativeAudioPresentationJournalCodec.decode(raw)

        assertEquals(4, snapshot.capacity)
        assertEquals(5L, snapshot.requestedAfterSequence)
        assertEquals(7L, snapshot.writeSequence)
        assertEquals(4L, snapshot.oldestAvailableSequence)
        assertEquals(0L, snapshot.overflowCount)
        assertEquals(0L, snapshot.writerCollisionCount)
        assertEquals(0L, snapshot.invalidPublicationCount)
        assertTrue(snapshot.coherent)
        assertFalse(snapshot.sequenceExhausted)
        assertEquals(listOf(6L, 7L), snapshot.entries.map { it.sequence })
        assertEquals(listOf(106L, 107L), snapshot.entries.map { it.selectionToken })
        assertEquals(listOf(306L, 307L), snapshot.entries.map { it.lastLoopPolicyRevision })
        assertEquals(listOf(4_006L, 5_007L), snapshot.entries.map { it.firstTimelineFrame })
        assertEquals(listOf(4_197L, 5_134L), snapshot.entries.map { it.lastTimelineFrame })
        assertTrue(snapshot.entries.all { it.continuityViolationCount == 0L })
        assertEquals(7L, snapshot.consumedThroughSequence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun presentationJournalCodecRejectsContinuityViolationsAtFrameCount() {
        NativeAudioPresentationJournalCodec.decode(
            longArrayOf(
                4, 1, 0, 1, 1, 0, 0, 0, 0, 1, 1, 20,
                1, 1, 1, 1, 1, 1, 2, 0, 0, 0, 1, 2, 0, 0, 0, 0, 0, 0, -1, 0
            )
        )
    }

    @Test
    fun presentationJournalCodecReportsExactReaderOverflowAfterWrap() {
        val raw = longArrayOf(
            4, 4, 1, 7, 4, 2, 0, 0, 0, 1, 4, 20,
            4, 14, 104, 1_004, 1_104, 204, 192, 304, 304, 4_004, 4_195, 0, 0, 0, 0, 0, 0, 0, -1, 0,
            5, 15, 105, 1_005, 1_105, 205, 192, 305, 305, 5_005, 5_196, 0, 0, 0, 0, 0, 0, 0, -1, 0,
            6, 16, 106, 1_006, 1_106, 206, 192, 306, 306, 6_006, 6_197, 0, 0, 0, 0, 0, 0, 0, -1, 0,
            7, 17, 107, 1_007, 1_107, 207, 192, 307, 307, 7_007, 7_198, 0, 0, 0, 0, 0, 0, 0, -1, 0
        )

        val snapshot = NativeAudioPresentationJournalCodec.decode(raw)

        assertEquals(2L, snapshot.overflowCount)
        assertEquals(4L, snapshot.entries.first().sequence)
        assertEquals(7L, snapshot.entries.last().sequence)
        assertEquals(7L, snapshot.consumedThroughSequence)
    }
}
