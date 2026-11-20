package app.stemwise.playback.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch

@app.stemwise.qualification.ManagedAudioTest
class NativePlaybackRuntimeBridgeTest {
    @Test fun delayedOptionalWaveformCacheRetiresItsTicketAndReportsRetryableDelay() = kotlinx.coroutines.runBlocking {
        val backend = FakeBackend()
        runtime(backend).use { runtime ->
            assertEquals(SourcePcmWindowRead.Delayed,
                runtime.readPcmWindow(longArrayOf(17L, 19L, 23L, 29L, 31L), 0, 0L, 64))
            assertEquals(listOf(41L), backend.windowCancellations)
            backend.windowSamples = floatArrayOf(.25f, -.5f)
            assertArrayEquals(backend.windowSamples,
                (runtime.readPcmWindow(longArrayOf(17L, 19L, 23L, 29L, 31L), 0, 0L, 64) as SourcePcmWindowRead.Ready).samples, 0f)
        }
    }

    @Test fun boundedWaveformReadForwardsExactIdentityReturnsOnlyCopiedPrefixAndCancelsItsTicket() = kotlinx.coroutines.runBlocking {
        val backend = FakeBackend().apply { windowSamples = floatArrayOf(.25f, -.5f, .75f, -1f) }
        runtime(backend).use { runtime ->
            val owner = longArrayOf(17L, 19L, Long.MIN_VALUE, 29L, 31L)
            val actual = runtime.readPcmWindow(owner, 3, 8_192L, 100)
            assertArrayEquals(backend.windowSamples, (actual as SourcePcmWindowRead.Ready).samples, 0f)
            assertArrayEquals(owner, backend.windowOwner)
            assertEquals(listOf(3L, 8_192L, 100L), backend.windowBounds)
            assertEquals(listOf(41L), backend.windowCancellations)
        }
    }

    @Test fun cancellingOrClosingAPendingWaveformReadCannotPublishOrStartAReplacement() = kotlinx.coroutines.runBlocking {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val owner = longArrayOf(17L, 19L, 23L, 29L, 31L)
        val child = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            assertEquals(SourcePcmWindowRead.Stale, runtime.readPcmWindow(owner, 0, 0L, 64))
        }
        child.cancel()
        child.join()
        assertEquals(listOf(41L), backend.windowCancellations)
        backend.onWindowPoll = { runtime.close() }
        assertEquals(SourcePcmWindowRead.Stale, runtime.readPcmWindow(owner, 0, 0L, 64))
        backend.onWindowPoll = { error("A closed owner cannot begin another native read") }
        assertEquals(SourcePcmWindowRead.Stale, runtime.readPcmWindow(owner, 0, 0L, 64))
        assertEquals(2, backend.windowBegins)
    }

    @Test fun optionalCacheDistinguishesUnsupportedStaleAndCorruptReceipts() = kotlinx.coroutines.runBlocking {
        val owner = longArrayOf(17L, 19L, 23L, 29L, 31L)
        for ((status, expected) in listOf(
            -1 to SourcePcmWindowRead.Unsupported, -3 to SourcePcmWindowRead.Stale,
            -2 to SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_READ_FAILED"),
            65 to SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_RECEIPT_INVALID"))) {
            val backend = FakeBackend().apply { windowStatus = status }
            runtime(backend).use { runtime ->
                assertEquals(expected, runtime.readPcmWindow(owner, 0, 0L, 64))
                assertEquals(listOf(41L), backend.windowCancellations)
            }
        }
        val unsupported = FakeBackend().apply { windowTicket = 0L }
        runtime(unsupported).use { runtime ->
            assertEquals(SourcePcmWindowRead.Unsupported, runtime.readPcmWindow(owner, 0, 0L, 64))
            assertTrue(unsupported.windowCancellations.isEmpty())
        }
        val corrupt = FakeBackend().apply { windowSamples = floatArrayOf(Float.NaN, 0f) }
        runtime(corrupt).use { runtime ->
            assertEquals(SourcePcmWindowRead.Corrupt("SOURCE_WINDOW_CACHE_NONFINITE"),
                runtime.readPcmWindow(owner, 0, 0L, 64))
        }
    }

    @Test
    fun pcmRangeProofForwardsExactOwnerContentMaskAndExclusiveBounds() {
        val backend = FakeBackend()
        runtime(backend).use { runtime ->
            val intent = NativeRuntimeBankIntent(1L, NativeRuntimeOwnership(17L, 19L),
                songIdentityHash = Long.MIN_VALUE, originalContentHash = 29L, cacheFrameUpperBound = 9_216L)
            assertTrue(runtime.pcmCacheRangeReady(intent, 1, 8_192L, 9_216L))
            assertArrayEquals(longArrayOf(17L, 19L, Long.MIN_VALUE, 29L, 0L), backend.lastRangeOwner)
            assertArrayEquals(longArrayOf(1L, 8_192L, 9_216L), backend.lastRangeBounds)
            for ((mask, first, end) in listOf(Triple(0, 0L, 1L), Triple(1 shl 20, 0L, 1L),
                Triple(1, -1L, 1L), Triple(1, 1L, 1L))) {
                assertFails<IllegalArgumentException> { runtime.pcmCacheRangeReady(intent, mask, first, end) }
            }
        }
    }

    @Test
    fun stemCallbackNativeKnownAnswerUsesTheNormalRuntimeConsumerAndRejectsMalformedPackets() {
        val values = NativePacketGoldenFixture.packet("STEM_GAIN_CALLBACK")
        val backend = FakeBackend().apply { stemGainCallbackValues = values }
        runtime(backend).use { runtime ->
            val decoded = requireNotNull(runtime.stemGainCallbackSettlementDiagnostics())
            assertEquals(11L, decoded.settlementSequence)
            assertEquals(31L, decoded.ownerToken)
            assertEquals(41L, decoded.ownerGeneration)
            assertEquals(0.5f.toRawBits(), decoded.gainBits[0])
            assertEquals(64L, decoded.taggedFrameCount)
            assertEquals(12345L, decoded.callbackObservedAtNs)
            for (delta in listOf(-1, 1)) {
                backend.stemGainCallbackValues = values.copyOf(values.size + delta)
                assertTrue(runCatching { runtime.stemGainCallbackSettlementDiagnostics() }.isFailure)
            }
            backend.stemGainCallbackValues = values.copyOf().apply { this[0] += 1L }
            assertTrue(runCatching { runtime.stemGainCallbackSettlementDiagnostics() }.isFailure)
            backend.stemGainCallbackValues = values.copyOf().apply { this[1] = 2L }
            assertTrue(runCatching { runtime.stemGainCallbackSettlementDiagnostics() }.isFailure)
        }
    }

    @Test
    fun nativeUnsignedGainWordsPreserveZeroSignsAndRejectInvalidValues() {
        val backend = FakeBackend()
        runtime(backend).use { runtime ->
            for (gain in floatArrayOf(0f, -0f, 1f, 2f)) {
                val bits = gain.toRawBits()
                for (word in setOf(bits.toLong(), bits.toLong() and 0xffff_ffffL)) {
                    backend.stemGainCallbackValues = NativePacketGoldenFixture.packet("STEM_GAIN_CALLBACK").apply {
                        this[NativePacketSchema.STEM_GAIN_CALLBACK_GAIN_BITS_BASE] = word
                    }
                    assertEquals(bits, requireNotNull(runtime.stemGainCallbackSettlementDiagnostics()).gainBits[0])
                }
            }
            for (word in longArrayOf(Long.MIN_VALUE, 0x1_0000_0000L,
                Float.NaN.toRawBits().toLong(), Float.POSITIVE_INFINITY.toRawBits().toLong())) {
                backend.stemGainCallbackValues = NativePacketGoldenFixture.packet("STEM_GAIN_CALLBACK").apply {
                    this[NativePacketSchema.STEM_GAIN_CALLBACK_GAIN_BITS_BASE] = word
                }
                assertTrue(runCatching { runtime.stemGainCallbackSettlementDiagnostics() }.isFailure)
            }
        }
    }

    @Test
    fun loopProtocolAcceptsAllNativeLanesAndTheCompletionSentinelButRejectsOverflow() {
        val values = FakeBackend().loopProtocolValues
        for (lane in 0L..NativeSongLaneIngressBank.LANE_COUNT.toLong()) {
            values[NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_LANE] = lane
            assertEquals(lane.toInt(), NativeRuntimeLoopProtocolCodec.decode(values).preparation.activeLane)
        }
        values[NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_LANE] = NativeSongLaneIngressBank.LANE_COUNT + 1L
        assertTrue(runCatching { NativeRuntimeLoopProtocolCodec.decode(values) }.isFailure)
        values[NativePacketSchema.LOOP_PROTOCOL_PREPARATION_ACTIVE_LANE] = -1L
        assertTrue(runCatching { NativeRuntimeLoopProtocolCodec.decode(values) }.isFailure)
    }

    @Test
    fun compactControlReadPreservesExactProofWithoutReadingFullDiagnostics() {
        val backend = FakeBackend()
        val proof = longArrayOf(801L, 104L, 204L, 901L, 1L, 902L, 903L, 904L, 64L)
        proof.copyInto(backend.values, NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_SEQUENCE)
        backend.values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS] = 1.5f.toRawBits().toLong()
        backend.values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS] = 3f.toRawBits().toLong()
        runtime(backend).use { runtime ->
            val expected = NativeRuntimeDiagnosticsCodec.decode(backend.values).audibleControl
            assertEquals(expected, runtime.pollAudibleControl(800L))
            assertNull(runtime.pollAudibleControl(801L))
            assertEquals(0, backend.fullDiagnosticReads)
        }
        for (bad in listOf(longArrayOf(1L), longArrayOf(2L, *proof),
            longArrayOf(1L, *proof).apply { this[5] = 4294967296L })) {
            assertTrue(runCatching { NativeRuntimeAudibleControlCodec.decode(bad) }.isFailure)
        }
        assertNull(NativeRuntimeAudibleControlCodec.decode(LongArray(NativeRuntimeAudibleControlCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeAudibleControlCodec.SCHEMA_VERSION
        }))
        for (field in listOf(NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS,
            NativePacketSchema.AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS)) {
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -99f, 99f)) {
                val malformed = NativePacketGoldenFixture.packet("AUDIBLE_CONTROL").apply { this[field] = invalid.toRawBits().toLong() }
                assertTrue(runCatching { NativeRuntimeAudibleControlCodec.decode(malformed) }.isFailure)
            }
        }
    }

    @Test
    fun currentSongCacheReadinessRequiresExactSelectedBindingCompleteMaskAndAbi() {
        val values = LongArray(NativeRuntimeRenderReadinessDiagnosticsCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeRenderReadinessDiagnosticsCodec.SCHEMA_VERSION
            this[1] = 1L
            this[2] = 1L
            this[3] = NativeRuntimeBank.SECOND.nativeCode.toLong()
            this[4] = 41L
            this[5] = 7L
            this[148] = 0L
            this[149] = 0x7fL
            this[158] = 0x7fL
        }
        val backend = FakeBackend().apply { renderReadinessValues = values }

        runtime(backend).use { runtime ->
            assertFalse(requireNotNull(runtime.renderReadinessDiagnostics())
                .currentSongCacheReadyForSelectedOwner)
            values[148] = 1L
            values[149] = 0x3fL
            values[157] = 1L
            assertTrue(requireNotNull(runtime.renderReadinessDiagnostics()).currentSongCacheCanGrow)
            assertFalse(requireNotNull(runtime.renderReadinessDiagnostics())
                .currentSongCacheReadyForSelectedOwner)
            values[149] = 0x7fL
            values[157] = 0L
            val ready = requireNotNull(runtime.renderReadinessDiagnostics())
            assertTrue(ready.currentSongCacheBoundToSelectedOwner)
            assertEquals(0x7f, ready.currentSongCacheCompleteLaneMask)
            assertTrue(ready.currentSongCacheReadyForSelectedOwner)
            assertFalse(ready.currentSongCacheCanGrow)
            assertFalse(ready.fixedLoopHeadPrepared)
            values[160] = 1L
            assertTrue(requireNotNull(runtime.renderReadinessDiagnostics()).fixedLoopHeadPrepared)

            // A two-part package needs Original and two stems, not unused ABI capacity.
            values[149] = 0x7L
            values[158] = 0x7L
            assertTrue(requireNotNull(runtime.renderReadinessDiagnostics())
                .currentSongCacheReadyForSelectedOwner)
            values[158] = 0L
            assertFalse(requireNotNull(runtime.renderReadinessDiagnostics())
                .currentSongCacheReadyForSelectedOwner)
            values[159] = NativeCacheStorageStatus.INSUFFICIENT_SPACE.ordinal.toLong()
            assertEquals(NativeCacheStorageStatus.INSUFFICIENT_SPACE,
                requireNotNull(runtime.renderReadinessDiagnostics()).cacheStorageStatus)
        }

        assertFails<IllegalArgumentException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.copyOf(values.size - 1))
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[0]-- })
        }
        assertFails<IllegalStateException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[148] = 2L })
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[149] = 1L shl NativeSongLaneIngressBank.LANE_COUNT })
        }
        assertFails<IllegalStateException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[157] = 2L })
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[159] = 1L shl 32 })
        }
        assertFails<IllegalStateException> {
            NativeRuntimeRenderReadinessDiagnosticsCodec.decode(values.clone().apply { this[160] = 2L })
        }
    }

    @Test
    fun stemGainCallbackDiagnosticsPreserveExactFloatBitsAndWholeCallbackBounds() {
        val gainBits = intArrayOf(
            1.0f.toRawBits(),
            0.75f.toRawBits(),
            0.5f.toRawBits(),
            0.25f.toRawBits(),
            0.0f.toRawBits(),
            (-0.0f).toRawBits(),
            *IntArray(NativeRuntimeControls.STEM_COUNT - 6) { ((it + 7) / 10f).toRawBits() }
        )
        val backend = FakeBackend().apply {
            stemGainCallbackValues = LongArray(NativePacketSchema.STEM_GAIN_CALLBACK_COUNT).also { fields ->
                fields[0] = NativePacketSchema.STEM_GAIN_CALLBACK_VERSION
                fields[1] = 1L
                fields[2] = 41L
                fields[3] = 7L
                fields[4] = 11L
                fields[5] = 29L
                fields[6] = 31L
                fields[7] = (1L shl (NativeRuntimeControls.STEM_COUNT - 1)) or 0b10_1111L
                gainBits.forEachIndexed { lane, bits -> fields[8 + lane] = bits.toLong() }
                fields[17] = 101L
                fields[18] = 4_800L
                fields[19] = 4_991L
                fields[20] = 192L
                fields[21] = 191L
                fields[22] = 9_123_456_789L
            }
        }

        runtime(backend).use { runtime ->
            val diagnostics = requireNotNull(runtime.stemGainCallbackSettlementDiagnostics())
            assertEquals(41L, diagnostics.settlementSequence)
            assertEquals(7L, diagnostics.ownerGeneration)
            assertEquals(11L, diagnostics.ownerToken)
            assertEquals(29L, diagnostics.offeredSequence)
            assertEquals(31L, diagnostics.publishedSequence)
            assertEquals((1 shl (NativeRuntimeControls.STEM_COUNT - 1)) or 0b10_1111, diagnostics.activeMask)
            assertArrayEquals(gainBits, diagnostics.gainBits)
            assertEquals(101L, diagnostics.callbackSequence)
            assertEquals(4_800L, diagnostics.firstTaggedFrame)
            assertEquals(4_991L, diagnostics.lastTaggedFrame)
            assertEquals(192L, diagnostics.taggedFrameCount)
            assertEquals(191L, diagnostics.nonZeroPcmFrameCount)
            assertEquals(9_123_456_789L, diagnostics.callbackObservedAtNs)
            assertTrue(diagnostics.coherent)
        }
    }

    @Test
    fun runtimeControlContractRejectsTempoOutsideProductWindow() {
        assertFails<IllegalArgumentException> {
            NativeRuntimeControls(revision = 1L, tempo = .499f)
        }
        assertEquals(.5f, NativeRuntimeControls(revision = 1L, tempo = .5f).tempo, 0f)
        assertEquals(2f, NativeRuntimeControls(revision = 1L, tempo = 2f).tempo, 0f)
        assertFails<IllegalArgumentException> {
            NativeRuntimeControls(revision = 1L, tempo = 2.001f)
        }
    }

    @Test
    fun runtimeConfigurationBoundsIngressWorkAndDefinesTimeReserveFloor() {
        val defaults = NativeRuntimeConfiguration()
        assertEquals(50, defaults.minimumOutputReserveMs)
        assertEquals(4, defaults.maximumPullsPerLanePerCycle)

        assertFails<IllegalArgumentException> {
            NativeRuntimeConfiguration(minimumOutputReserveMs = 3)
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeConfiguration(minimumOutputReserveMs = 65)
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeConfiguration(maximumPullsPerLanePerCycle = 0)
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeConfiguration(maximumPullsPerLanePerCycle = 9)
        }

        assertEquals(4, NativeRuntimeConfiguration(minimumOutputReserveMs = 4).minimumOutputReserveMs)
        assertEquals(64, NativeRuntimeConfiguration(minimumOutputReserveMs = 64).minimumOutputReserveMs)
        assertEquals(1, NativeRuntimeConfiguration(maximumPullsPerLanePerCycle = 1).maximumPullsPerLanePerCycle)
        assertEquals(8, NativeRuntimeConfiguration(maximumPullsPerLanePerCycle = 8).maximumPullsPerLanePerCycle)
    }

    @Test
    fun taggedArm64PointerBitsRemainValidOpaqueJniHandles() {
        val backend = FakeBackend().apply { createdHandle = Long.MIN_VALUE + 99L }

        NativePlaybackRuntime(
            outputHandle = Long.MIN_VALUE + 11L,
            firstBankHandle = Long.MIN_VALUE + 12L,
            secondBankHandle = Long.MIN_VALUE + 13L,
            configuration = NativeRuntimeConfiguration(),
            backend = backend
        ).use { runtime ->
            assertTrue(runtime.start())
        }

        assertEquals(listOf("create", "start", "close"), backend.events)
    }

    @Test
    fun metronomeV2RejectsInvalidMeterDenominatorAndAccentShapes() {
        assertFails<IllegalArgumentException> {
            NativeRuntimeMetronomeControl(beatsPerBar = 33)
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeMetronomeControl(beatUnitDenominator = 3)
        }
        val maximum = NativeRuntimeMetronomeControl(
            beatsPerBar = 32,
            beatUnitDenominator = 32
        )
        assertEquals(32, maximum.beatsPerBar)
        assertEquals(32, maximum.beatUnitDenominator)

        val owner = NativeRuntimeOwnership(1L, 2L)
        assertFails<IllegalArgumentException> {
            NativeRuntimeBeatGridUpdate(
                revision = 1L,
                bank = NativeRuntimeBank.FIRST,
                ownership = owner,
                beatFrames = longArrayOf(0L, 1L),
                beatAccents = booleanArrayOf(true)
            )
        }
        val backend = FakeBackend()
        runtime(backend).use { runtime ->
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publishBeatGrid(
                NativeRuntimeBeatGridUpdate(1L, NativeRuntimeBank.FIRST, owner,
                    longArrayOf(), booleanArrayOf())))
        }
        assertArrayEquals(longArrayOf(), backend.lastBeatFrames)
        assertArrayEquals(byteArrayOf(), backend.lastBeatAccentFlags)
    }

    @Test
    fun completeSnapshotCodecPreservesExactPositiveLongOwnersAndEveryControl() {
        val owner = NativeRuntimeOwnership(Long.MAX_VALUE - 2L, Long.MAX_VALUE - 1L)
        val controls = NativeRuntimeControls(
            revision = Long.MAX_VALUE - 3L,
            sourceIntentRevision = 701L,
            stemIntentRevision = 702L,
            sourceMode = NativeRuntimeSourceMode.STEMS,
            stems = List(NativeRuntimeControls.STEM_COUNT) { index ->
                NativeRuntimeStemControl(
                    gain = (index + 1) / 10f,
                    muted = index % 2 == 0,
                    soloed = index == 5 || index == NativeRuntimeControls.STEM_COUNT - 1
                )
            },
            musicGain = 1.5f,
            mixHeadroom = .75f,
            sourceTransitionFrames = 101,
            laneRampFrames = 102,
            masterRampFrames = 103,
            defaultDeclickFrames = 104,
            metronome = NativeRuntimeMetronomeControl(
                NativeRuntimeMetronomeMode.BEAT_GRID,
                bpm = 222f,
                gain = .8f,
                subdivision = 4,
                beatsPerBar = 32,
                beatUnitDenominator = 8
            ),
            tempo = .75f,
            pitchSemitones = -12f
        )
        val snapshot = NativeRuntimeCommandSnapshot(
            revision = Long.MAX_VALUE - 4L,
            outputGeneration = owner.generation,
            banks = listOf(
                NativeRuntimeBankIntent.unassigned(901L),
                assignedBank(
                    revision = 902L,
                    ownership = owner,
                    firstTimelineFrame = Long.MAX_VALUE - 5L,
                    outputClockStartFrame = Long.MAX_VALUE - 6L,
                    stemLaneCount = NativeRuntimeControls.STEM_COUNT
                ).copy(cacheFrameUpperBound = 28_802_048L)
            ),
            selectedBank = NativeRuntimeBank.SECOND,
            playing = true,
            controls = controls,
        )

        val (longs, floats) = NativeRuntimeCommandCodec.encode(snapshot)

        assertEquals(NativeRuntimeCommandCodec.LONG_COUNT, longs.size)
        assertEquals(NativeRuntimeCommandCodec.FLOAT_COUNT, floats.size)
        assertEquals(NativeRuntimeCommandCodec.SCHEMA_VERSION, longs[0])
        assertEquals(snapshot.revision, longs[1])
        assertEquals(owner.generation, longs[2])
        assertEquals(901L, longs[3])
        assertEquals(0L, longs[4])
        assertEquals(902L, longs[9])
        assertEquals(1L, longs[10])
        assertEquals(owner.selectionToken, longs[11])
        assertEquals(owner.generation, longs[12])
        assertEquals(Long.MAX_VALUE - 5L, longs[13])
        assertEquals(Long.MAX_VALUE - 6L, longs[14])
        assertEquals(1L, longs[15])
        assertEquals(NativeRuntimeBank.SECOND.nativeCode.toLong(), longs[16])
        assertEquals(owner.selectionToken, longs[17])
        assertEquals(owner.generation, longs[18])
        assertEquals(1L, longs[19])
        assertEquals(controls.revision, longs[20])
        assertEquals(NativeRuntimeSourceMode.STEMS.nativeCode.toLong(), longs[21])
        assertEquals(101L, longs[22])
        assertEquals(102L, longs[23])
        assertEquals(103L, longs[24])
        assertEquals(104L, longs[25])
        assertEquals(1L, longs[26])
        assertEquals(0L, longs[27])
        assertEquals(0L, longs[36])
        assertEquals(1L, longs[37])
        assertEquals(1L, longs[42])
        assertEquals(1L, longs[43])
        assertEquals(NativeRuntimeMetronomeMode.BEAT_GRID.nativeCode.toLong(), longs[44])
        assertEquals(4L, longs[45])
        assertEquals(32L, longs[46])
        assertEquals(8L, longs[47])
        assertArrayEquals(LongArray(8), longs.copyOfRange(48, 56))
        assertEquals(701L, longs[56])
        assertEquals(702L, longs[57])
        assertEquals(0L, longs[64])
        assertEquals(NativeRuntimeControls.STEM_COUNT.toLong(), longs[65])
        assertEquals(0L, longs[66])
        assertEquals(28_802_048L, longs[67])
        repeat(NativeRuntimeControls.STEM_COUNT) { assertEquals((it + 1) / 10f, floats[it], 0f) }
        assertArrayEquals(
            floatArrayOf(1.5f, .75f, 222f, .8f, .75f, -12f),
            floats.copyOfRange(NativeRuntimeControls.STEM_COUNT, NativeRuntimeCommandCodec.FLOAT_COUNT),
            0f
        )
    }

    @Test
    fun automaticSuccessorIntentEncodesExactOutgoingAndSpareBankFence() {
        val outgoing = NativeRuntimeOwnership(41L, 50L)
        val successor = NativeRuntimeOwnership(42L, 51L)
        val snapshot = NativeRuntimeCommandSnapshot(
            revision = 9L,
            outputGeneration = outgoing.generation,
            banks = listOf(
                assignedBank(3L, outgoing),
                assignedBank(4L, successor)
            ),
            selectedBank = NativeRuntimeBank.FIRST,
            playing = true,
            controls = selectedControls(7L),
            automaticSuccessor = NativeRuntimeAutomaticSuccessorIntent(
                revision = 88L,
                outgoingOwnership = outgoing,
                successorBank = NativeRuntimeBank.SECOND,
                successorOwnership = successor
            )
        )

        val (longs, _) = NativeRuntimeCommandCodec.encode(snapshot)
        assertArrayEquals(
            longArrayOf(1L, 88L, 41L, 50L, 1L, 42L, 51L, 0L),
            longs.copyOfRange(NativePacketSchema.COMMAND_LONG_SUCCESSOR_PRESENT,
                NativePacketSchema.COMMAND_LONG_SUCCESSOR_INHERIT + 1)
        )
    }

    @Test
    fun automaticSuccessorIntentRejectsPausedLoopingOrNonExactSpareState() {
        val outgoing = NativeRuntimeOwnership(41L, 50L)
        val successor = NativeRuntimeOwnership(42L, 51L)
        val intent = NativeRuntimeAutomaticSuccessorIntent(
            revision = 88L,
            outgoingOwnership = outgoing,
            successorBank = NativeRuntimeBank.SECOND,
            successorOwnership = successor
        )
        val banks = listOf(
            assignedBank(3L, outgoing),
            assignedBank(4L, successor)
        )

        assertFails<IllegalArgumentException> {
            NativeRuntimeCommandSnapshot(
                revision = 9L,
                outputGeneration = outgoing.generation,
                banks = banks,
                selectedBank = NativeRuntimeBank.FIRST,
                playing = false,
                controls = selectedControls(7L),
                automaticSuccessor = intent
            )
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeCommandSnapshot(
                revision = 9L,
                outputGeneration = outgoing.generation,
                banks = listOf(
                    assignedBank(3L, outgoing),
                    assignedBank(4L, NativeRuntimeOwnership(99L, 51L))
                ),
                selectedBank = NativeRuntimeBank.FIRST,
                playing = true,
                controls = selectedControls(7L),
                automaticSuccessor = intent
            )
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeAutomaticSuccessorIntent(
                revision = 89L,
                outgoingOwnership = outgoing,
                successorBank = NativeRuntimeBank.SECOND,
                successorOwnership = NativeRuntimeOwnership(43L, 50L)
            )
        }
    }

    @Test
    fun invalidCrossFieldOwnershipFailsBeforeTheBackendCanSeeIt() {
        val owner = NativeRuntimeOwnership(10L, 20L)
        val selectedControls = selectedControls(1L)
        assertFails<IllegalArgumentException> {
            NativeRuntimeCommandSnapshot(
                revision = 1L,
                outputGeneration = 20L,
                banks = listOf(
                    NativeRuntimeBankIntent.unassigned(1L),
                    NativeRuntimeBankIntent.unassigned(1L)
                ),
                selectedBank = NativeRuntimeBank.FIRST,
                playing = true,
                controls = selectedControls
            )
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeCommandSnapshot(
                revision = 1L,
                outputGeneration = 20L,
                banks = listOf(
                    assignedBank(1L, owner),
                    NativeRuntimeBankIntent.unassigned(1L)
                ),
                selectedBank = null,
                playing = true,
                controls = NativeRuntimeControls(1L)
            )
        }
        assertFails<IllegalArgumentException> {
            NativeRuntimeCommandSnapshot(
                revision = 1L,
                outputGeneration = 19L,
                banks = listOf(
                    assignedBank(1L, owner),
                    NativeRuntimeBankIntent.unassigned(1L)
                ),
                selectedBank = NativeRuntimeBank.FIRST,
                playing = false,
                controls = selectedControls
            )
        }
    }

    @Test
    fun commandSnapshotEnforcesExactSelectionFenceMatrix() {
        val owner = NativeRuntimeOwnership(10L, 20L)
        val banks = listOf(
            assignedBank(1L, owner),
            NativeRuntimeBankIntent.unassigned(1L)
        )

        val unselected = NativeRuntimeCommandSnapshot(
            revision = 1L,
            outputGeneration = 21L,
            banks = listOf(
                NativeRuntimeBankIntent.unassigned(2L),
                NativeRuntimeBankIntent.unassigned(2L)
            ),
            selectedBank = null,
            playing = false,
            controls = NativeRuntimeControls(1L)
        )
        assertEquals(0L, unselected.controls.sourceIntentRevision)
        assertEquals(0L, unselected.controls.stemIntentRevision)

        val selected = NativeRuntimeCommandSnapshot(
            revision = 2L,
            outputGeneration = owner.generation,
            banks = banks,
            selectedBank = NativeRuntimeBank.FIRST,
            playing = false,
            controls = selectedControls(2L, sourceRevision = 7L, stemRevision = 9L)
        )
        assertEquals(7L, selected.controls.sourceIntentRevision)
        assertEquals(9L, selected.controls.stemIntentRevision)

        listOf(
            NativeRuntimeControls(3L),
            NativeRuntimeControls(3L, sourceIntentRevision = 7L),
            NativeRuntimeControls(3L, stemIntentRevision = 9L)
        ).forEach { malformedControls ->
            assertFails<IllegalArgumentException> {
                NativeRuntimeCommandSnapshot(
                    revision = 3L,
                    outputGeneration = owner.generation,
                    banks = banks,
                    selectedBank = NativeRuntimeBank.FIRST,
                    playing = false,
                    controls = malformedControls
                )
            }
        }

        assertFails<IllegalArgumentException> {
            unselected.copy(controls = selectedControls(4L))
        }
        assertFails<IllegalArgumentException> {
            unselected.copy(
                playing = true,
                controls = NativeRuntimeControls(4L)
            )
        }
    }

    @Test
    fun wrapperForwardsOneCompletePublicationAndClosesExactlyOnce() {
        val backend = FakeBackend()
        val runtime = NativePlaybackRuntime(
            outputHandle = 11L,
            firstBankHandle = 12L,
            secondBankHandle = 13L,
            configuration = NativeRuntimeConfiguration(),
            backend = backend
        )
        val owner = NativeRuntimeOwnership(55L, 66L)
        val command = NativeRuntimeCommandSnapshot(
            revision = 99L,
            outputGeneration = owner.generation,
            banks = listOf(
                assignedBank(100L, owner, 123L, 456L),
                NativeRuntimeBankIntent.unassigned(101L)
            ),
            selectedBank = NativeRuntimeBank.FIRST,
            playing = true,
            controls = selectedControls(77L)
        )

        assertTrue(runtime.start())
        assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
        runtime.notifyIngressAvailable()
        runtime.requestStop()
        runtime.join()
        runtime.close()
        runtime.close()

        assertEquals(listOf("create", "start", "publish", "notify", "stop", "join", "close"), backend.events)
        assertEquals(1, backend.closeCalls)
        assertArrayEquals(NativeRuntimeCommandCodec.encode(command).first, backend.lastLongs)
        assertArrayEquals(NativeRuntimeCommandCodec.encode(command).second, backend.lastFloats, 0f)
        assertTrue(runtime.isClosed())
        assertFails<IllegalStateException> { runtime.start() }
    }

    @Test
    fun runtimeForwardsTheExactCurrentSongCacheDirectory() {
        val backend = FakeBackend()
        val directory = "C:/stemwise-current-song-cache"
        NativePlaybackRuntime(
            outputHandle = 11L,
            firstBankHandle = 12L,
            secondBankHandle = 13L,
            configuration = NativeRuntimeConfiguration(
                currentSongCacheDirectory = directory
            ),
            backend = backend
        ).close()

        assertEquals(directory, backend.lastConfiguration?.currentSongCacheDirectory)
    }

    @Test
    fun gainPreviewForwardsEveryExactFenceAndTheMaximumGainTupleToBackend() {
        val backend = FakeBackend()
        val gains = FloatArray(NativeRuntimeControls.STEM_COUNT) { (it + 1) / 10f }
        val owner = NativeRuntimeOwnership(55L, 66L)
        NativePlaybackRuntime(
            outputHandle = 11L,
            firstBankHandle = 12L,
            secondBankHandle = 13L,
            configuration = NativeRuntimeConfiguration(),
            backend = backend
        ).use { runtime ->
            assertTrue(runtime.start())
            assertEquals(
                NativeRuntimePublishStatus.ACCEPTED,
                runtime.publishStemGainPreview(
                    publicationSequence = 17L,
                    coversOfferSequence = 23L,
                    ownership = owner,
                    sourceIntentRevision = 29L,
                    stemIntentRevision = 31L,
                    canonicalControlFloorRevision = 37L,
                    activeMask = (1 shl (NativeRuntimeControls.STEM_COUNT - 1)) or 0b100101,
                    gains = gains
                )
            )
        }

        assertEquals(17L, backend.lastPreviewPublicationSequence)
        assertEquals(23L, backend.lastPreviewCoversOfferSequence)
        assertEquals(owner.selectionToken, backend.lastPreviewSelectionToken)
        assertEquals(owner.generation, backend.lastPreviewGeneration)
        assertEquals(29L, backend.lastPreviewSourceIntentRevision)
        assertEquals(31L, backend.lastPreviewStemIntentRevision)
        assertEquals(37L, backend.lastPreviewCanonicalControlFloorRevision)
        assertEquals((1 shl (NativeRuntimeControls.STEM_COUNT - 1)) or 0b100101, backend.lastPreviewActiveMask)
        assertArrayEquals(gains, backend.lastPreviewGains, 0f)
        assertEquals(listOf("create", "start", "preview", "close"), backend.events)
    }

    private fun selectedControls(
        revision: Long,
        sourceRevision: Long = 1L,
        stemRevision: Long = 1L
    ) = NativeRuntimeControls(
        revision = revision,
        sourceIntentRevision = sourceRevision,
        stemIntentRevision = stemRevision
    )

    private fun assignedBank(
        revision: Long,
        ownership: NativeRuntimeOwnership,
        firstTimelineFrame: Long = 0L,
        outputClockStartFrame: Long = 0L,
        stemLaneCount: Int = 0
    ) = NativeRuntimeBankIntent(
        revision = revision,
        ownership = ownership,
        firstTimelineFrame = firstTimelineFrame,
        outputClockStartFrame = outputClockStartFrame,
        songIdentityHash = ownership.selectionToken,
        originalContentHash = ownership.generation,
        stemPackageHash = if (stemLaneCount > 0) 1L else 0L,
        stemLaneCount = stemLaneCount,
        cacheFrameUpperBound = firstTimelineFrame + minOf(1_048_576L, Long.MAX_VALUE - firstTimelineFrame)
    )

    @Test
    fun exactUnassignmentAckCannotBeConfusedWithOlderOrReassignedState() {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        backend.values[53] = 500L
        backend.values[54] = 0L

        assertTrue(runtime.hasAppliedUnassignment(NativeRuntimeBank.FIRST, 500L))
        assertFalse(runtime.hasAppliedUnassignment(NativeRuntimeBank.FIRST, 499L))

        backend.values[54] = 1L
        assertFalse(runtime.hasAppliedUnassignment(NativeRuntimeBank.FIRST, 500L))
        runtime.close()
    }

    @Test
    fun diagnosticsDecodeCallbackEvidencePositionEventsAndRawFingerprintBits() {
        val values = emptyDiagnostics()
        values[1] = NativeRuntimeStatus.RUNNING.ordinal.toLong()
        (2..25).forEach { values[it] = (it - 1).toLong() }
        values[26] = Long.MIN_VALUE + 123L
        values[27] = 101L
        values[28] = 201L
        values[29] = 102L
        values[30] = 202L
        values[31] = 103L
        values[32] = 203L
        values[33] = 301L
        values[34] = 401L
        values[35] = 204L
        values[36] = 104L
        values[37] = 302L
        values[38] = 1_000L
        values[39] = 2_000L
        values[40] = 3_000L
        values[41] = 256L
        values[42] = 402L
        values[43] = 204L
        values[44] = 104L
        values[45] = 303L
        values[46] = 1_255L
        values[47] = 2_255L
        values[48] = 3_001L
        values[49] = 256L
        values[50] = 601L
        values[51] = 104L
        values[52] = 204L
        values[53] = 701L
        values[54] = 0L
        values[55] = 2L
        values[56] = 3L
        values[57] = 4L
        values[58] = 5L
        values[59] = 6L
        values[60] = 702L
        values[61] = 1L
        values[62] = 7L
        values[63] = 8L
        values[64] = 9L
        values[65] = 10L
        values[66] = 11L
        values[67] = 801L
        values[68] = 104L
        values[69] = 204L
        values[70] = 901L
        values[71] = NativeRuntimeSourceMode.STEMS.nativeCode.toLong()
        values[72] = 902L
        values[73] = 903L
        values[74] = 904L
        values[75] = 64L
        values[76] = 17L
        values[77] = 2L
        values[78] = NativeRuntimeAutomaticSuccessorPhase
            .APPLIED_PAUSED_AWAITING_SELECTION.nativeCode.toLong()
        values[79] = NativeRuntimeDrainCancelStatus.SUCCESSOR_ACTIVE.nativeCode.toLong()
        values[80] = 1_001L
        values[81] = 1_002L
        values[82] = 105L
        values[83] = 205L
        values[84] = 106L
        values[85] = 206L
        values[86] = 384L
        values[87] = 1_003L
        values[88] = 0L
        values[89] = 1L
        values[90] = 1L
        values[91] = 1L
        values[92] = 1L
        values[93] = -16L
        values[94] = 0L
        values[95] = 12L
        values[96] = 13L
        values[97] = 14L
        values[98] = 2L
        values[99] = 203L
        values[100] = 96L
        values[101] = 99_000L
        values[102] = 7L
        values[103] = 100_000L
        values[104] = 203L
        values[105] = 8L
        values[106] = 101_000L
        values[107] = 203L
        values[108] = 9L
        values[109] = 102_000L
        values[110] = 203L
        values[111] = 1_234L
        values[112] = 4_321L
        values[113] = 5_678L
        values[114] = 321L
        values[115] = 4_642L

        val decoded = NativeRuntimeDiagnosticsCodec.decode(values)

        assertEquals(NativeRuntimeStatus.RUNNING, decoded.status)
        assertEquals(Long.MIN_VALUE + 123L, decoded.counters.lastRenderPayloadHash)
        assertEquals(NativeRuntimeOwnership(103L, 203L), decoded.ownership.committed)
        assertEquals(104L, decoded.presentation?.ownership?.selectionToken)
        assertEquals(1_000L, decoded.presentation?.firstTimelineFrame)
        assertEquals(1_255L, decoded.presentedPosition?.lastTimelineFrame)
        assertEquals(5_678L, decoded.presentedPosition?.loopPolicyRevision)
        assertEquals(1_234L, decoded.presentedPosition?.loopPolicyAdoptedRevision)
        assertEquals(4_321L, decoded.presentedPosition?.loopPolicyAdoptionLatencyMicros)
        assertEquals(
            321L,
            decoded.presentedPosition?.loopPolicyInputToAtomicPublicationLatencyMicros
        )
        assertEquals(
            4_642L,
            decoded.presentedPosition?.loopPolicyInputToCallbackAdoptionLatencyMicros
        )
        assertEquals(601L, decoded.audibleEnd?.sequence)
        assertEquals(NativeRuntimeBankDiagnostics(701L, false, 2L, 3L, 4L, 5L, 6L), decoded.banks[0])
        assertEquals(NativeRuntimeBankDiagnostics(702L, true, 7L, 8L, 9L, 10L, 11L), decoded.banks[1])
        assertEquals(801L, decoded.audibleControl?.sequence)
        assertEquals(NativeRuntimeOwnership(104L, 204L), decoded.audibleControl?.ownership)
        assertEquals(901L, decoded.audibleControl?.controlRevision)
        assertEquals(NativeRuntimeSourceMode.STEMS, decoded.audibleControl?.actualSourceMode)
        assertEquals(902L, decoded.audibleControl?.firstFingerprintSequence)
        assertEquals(903L, decoded.audibleControl?.lastFingerprintSequence)
        assertEquals(904L, decoded.audibleControl?.callbackSequence)
        assertEquals(64L, decoded.audibleControl?.pcmFrames)
        assertEquals(17L, decoded.processConstructionCount)
        assertEquals(2L, decoded.processLiveInstanceCount)
        assertEquals(
            NativeRuntimeAutomaticSuccessorPhase.APPLIED_PAUSED_AWAITING_SELECTION,
            decoded.automaticSuccessor.phase
        )
        assertEquals(
            NativeRuntimeDrainCancelStatus.SUCCESSOR_ACTIVE,
            decoded.automaticSuccessor.cancellationStatus
        )
        assertEquals(NativeRuntimeOwnership(105L, 205L),
            decoded.automaticSuccessor.outgoingOwnership)
        assertEquals(NativeRuntimeOwnership(106L, 206L),
            decoded.automaticSuccessor.successorOwnership)
        assertEquals(384L, decoded.automaticSuccessor.successorQueuedFrames)
        assertEquals(1_003L, decoded.automaticSuccessor.firstFingerprintSequence)
        assertFalse(decoded.automaticSuccessor.cancellationDesired)
        assertTrue(decoded.automaticSuccessor.successorEndObserved)
        assertTrue(decoded.renderWorkerStarted)
        assertTrue(decoded.renderWorkerAudioPriorityRequested)
        assertTrue(decoded.renderWorkerAudioPriorityApplied)
        assertEquals(-16, decoded.renderWorkerNiceValue)
        assertEquals(0, decoded.renderWorkerPriorityErrorCode)
        assertEquals(12L, decoded.counters.outputFlushRetries)
        assertEquals(13L, decoded.processOutputInvalidRenderResults)
        assertEquals(14L, decoded.processOutputFlushFailures)
        assertEquals(
            NativeRuntimeCurrentOutputQueueLowWater(2L, 203L, 96L, 99_000L),
            decoded.currentOutputQueueLowWater
        )
        assertEquals(
            NativeRuntimeOutputWaitReasonDiagnostics(
                lowWaterEvidenceSequence = 7L,
                lastMonotonicMicros = 100_000L,
                lastGeneration = 203L
            ),
            decoded.needOriginalData
        )
        assertEquals(
            NativeRuntimeOutputWaitReasonDiagnostics(
                lowWaterEvidenceSequence = 8L,
                lastMonotonicMicros = 101_000L,
                lastGeneration = 203L
            ),
            decoded.needStemData
        )
        assertEquals(
            NativeRuntimeOutputWaitReasonDiagnostics(
                lowWaterEvidenceSequence = 9L,
                lastMonotonicMicros = 102_000L,
                lastGeneration = 203L
            ),
            decoded.loopBoundaryPending
        )
    }

    @Test
    fun diagnosticsRejectsPreQueueLowWaterGatedWaitReasonSchema() {
        val values = emptyDiagnostics().apply { this[0] = 9L }

        assertFails<IllegalArgumentException> {
            NativeRuntimeDiagnosticsCodec.decode(values)
        }
    }

    @Test
    fun diagnosticsRejectsPayloadMissingProcessFaultCounter() {
        val values = LongArray(NativeRuntimeDiagnosticsCodec.VALUE_COUNT - 1).apply {
            this[0] = NativeRuntimeDiagnosticsCodec.SCHEMA_VERSION
        }

        assertFails<IllegalArgumentException> {
            NativeRuntimeDiagnosticsCodec.decode(values)
        }
    }

    @Test
    fun diagnosticsRejectsNegativeFlushRetryCounter() {
        val values = emptyDiagnostics().apply { this[95] = -1L }

        assertFails<IllegalArgumentException> {
            NativeRuntimeDiagnosticsCodec.decode(values)
        }
    }

    @Test
    fun diagnosticsRejectsNegativeProcessFaultCounters() {
        listOf(96, 97).forEach { index ->
            val values = emptyDiagnostics().apply { this[index] = -1L }

            assertFails<IllegalArgumentException> {
                NativeRuntimeDiagnosticsCodec.decode(values)
            }
        }
    }

    @Test
    fun diagnosticsRejectsIncompleteQueueLowWaterWaitReasonEvidence() {
        listOf(102, 105, 108).forEach { sequenceIndex ->
            val timestampIndex = sequenceIndex + 1
            val generationIndex = sequenceIndex + 2
            listOf(
                Triple(1L, 0L, 203L),
                Triple(1L, 100_000L, 0L),
                Triple(0L, 100_000L, 0L),
                Triple(0L, 0L, 203L)
            ).forEach { (sequence, timestamp, generation) ->
                val values = emptyDiagnostics().apply {
                    this[sequenceIndex] = sequence
                    this[timestampIndex] = timestamp
                    this[generationIndex] = generation
                }

                assertFails<IllegalArgumentException> {
                    NativeRuntimeDiagnosticsCodec.decode(values)
                }
            }
        }
    }

    @Test
    fun eventPollingIsSequenceBasedAndBeatGridIsCopiedSynchronouslyByContract() {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        backend.values[50] = 10L
        backend.values[51] = 111L
        backend.values[52] = 222L
        backend.values[67] = 11L
        backend.values[68] = 111L
        backend.values[69] = 222L
        backend.values[70] = 12L
        backend.values[71] = NativeRuntimeSourceMode.ORIGINAL.nativeCode.toLong()
        backend.values[72] = 13L
        backend.values[73] = 14L
        backend.values[74] = 15L
        backend.values[75] = 64L

        assertNotNull(runtime.pollAudibleEnd(9L))
        assertNull(runtime.pollAudibleEnd(10L))
        assertNotNull(runtime.pollAudibleControl(10L))
        assertNull(runtime.pollAudibleControl(11L))

        val frames = longArrayOf(0L, 24_000L, 48_000L)
        val accents = booleanArrayOf(false, true, false)
        assertEquals(
            NativeRuntimePublishStatus.ACCEPTED,
            runtime.publishBeatGrid(
                NativeRuntimeBeatGridUpdate(
                    revision = Long.MAX_VALUE,
                    bank = NativeRuntimeBank.SECOND,
                    ownership = NativeRuntimeOwnership(Long.MAX_VALUE - 1L, Long.MAX_VALUE - 2L),
                    beatFrames = frames,
                    beatAccents = accents
                )
            )
        )
        frames[1] = 1L
        accents[1] = false
        assertArrayEquals(longArrayOf(0L, 24_000L, 48_000L), backend.lastBeatFrames)
        assertArrayEquals(byteArrayOf(0, 1, 0), backend.lastBeatAccentFlags)
        runtime.close()
    }

    @Test
    fun originalEosIntentCodecUsesAConstantRunwayAndRejectsMetadataSizedKeys() {
        val owner = NativeRuntimeOwnership(7L, 8L)
        val start = 48_000L
        val key = NativeRuntimeManagedLoopContentKey(
            songIdentityHash = 11L,
            originalContentHash = 12L,
            stemPackageHash = 0L,
            contentRevision = 13L,
            loopRevision = 14L,
            startTimelineFrame = start,
            endTimelineFrameExclusive = start +
                NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES
        )
        val intent = NativeRuntimeManagedLoopIntent(
            revision = key.loopRevision,
            ownership = owner,
            enabled = true,
            cacheKey = key,
            requiredLaneMask = NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
            boundaryMode = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
        )

        val encoded = NativeRuntimeManagedLoopIntentCodec.encode(intent)
        assertEquals(15, encoded.size)
        assertEquals(5L, encoded[0])
        assertEquals(
            NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS.nativeCode.toLong(),
            encoded[14]
        )
        assertFails<IllegalArgumentException> {
            intent.copy(
                cacheKey = key.copy(endTimelineFrameExclusive = key.endTimelineFrameExclusive - 1L)
            )
        }
    }

    @Test
    fun managedLoopSiblingAbiPreservesExactIdentityIngressAndProtocolEvidence() {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val owner = NativeRuntimeOwnership(Long.MAX_VALUE - 10L, Long.MAX_VALUE - 9L)
        val key = NativeRuntimeManagedLoopContentKey(
            songIdentityHash = Long.MIN_VALUE + 1L,
            originalContentHash = Long.MIN_VALUE + 2L,
            stemPackageHash = 0L,
            contentRevision = Long.MAX_VALUE - 8L,
            loopRevision = Long.MAX_VALUE - 7L,
            startTimelineFrame = 48_000L,
            endTimelineFrameExclusive = 240_000L
        )
        val intent = NativeRuntimeManagedLoopIntent(
            revision = key.loopRevision,
            ownership = owner,
            enabled = true,
            cacheKey = key,
            requiredLaneMask = NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
            declickFrames = 240,
            boundaryMode = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
        )
        val ready = NativeRuntimeManagedLoopIngressReady(
            requestSequence = 9L,
            loopRevision = intent.revision,
            targetCycle = 4L,
            ownership = owner,
            ingressEpoch = owner.generation + 1L,
            readyLaneMask = NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
            firstTimelineFrame = 240_000L,
            endTimelineFrameExclusive = 248_192L
        )

        assertEquals(NativeRuntimePublishStatus.ACCEPTED,
            runtime.publishManagedLoopIntent(intent))
        assertArrayEquals(
            longArrayOf(
                NativeRuntimeManagedLoopIntentCodec.SCHEMA_VERSION,
                intent.revision, 1L, owner.selectionToken, owner.generation,
                key.songIdentityHash, key.originalContentHash, key.stemPackageHash,
                key.contentRevision, key.loopRevision, key.startTimelineFrame,
                key.endTimelineFrameExclusive, 1L, 240L,
                NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS.nativeCode.toLong()
            ),
            backend.lastManagedLoopIntent
        )
        assertEquals(NativeRuntimeLoopHeadBeginStatus.ACCEPTED,
            runtime.beginLoopHeadPreparation(intent))
        assertArrayEquals(backend.lastManagedLoopIntent, backend.lastLoopHeadBeginIntent)
        assertEquals(NativeRuntimePublishStatus.ACCEPTED,
            runtime.publishManagedLoopIngressReady(ready))
        assertArrayEquals(
            longArrayOf(
                NativeRuntimeManagedLoopIngressReadyCodec.SCHEMA_VERSION,
                9L, intent.revision, 4L, owner.selectionToken, owner.generation,
                owner.generation + 1L, 1L, 0L, 240_000L, 248_192L
            ),
            backend.lastManagedLoopReady
        )
        assertEquals(
            NativeRuntimeLoopHeadConfigureStatus.ACCEPTED,
            runtime.configureLoopHeadPreparationLane(intent.revision, 0, 48_000, 22L)
        )
        val pcmFormat = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_16_BIT)
        val pcmData = java.nio.ByteBuffer.allocateDirect(16)
            .order(pcmFormat.byteOrder)
            .asReadOnlyBuffer()
            .order(pcmFormat.byteOrder)
        val offered = runtime.offerLoopHeadPreparationPcm(
            loopRevision = intent.revision,
            laneIndex = 0,
            preparationGeneration = 22L,
            block = NativePcmBlock().reset(
                data = pcmData,
                format = pcmFormat,
                presentationTimeUs = 1_000_000L,
                seekGeneration = 22L,
                firstTimelineFrame = 48_000L
            )
        )
        assertEquals(NativeRuntimeLoopHeadOfferStatus.ACCEPTED, offered.status)
        assertEquals(4, offered.inputFramesAccepted)
        assertEquals(
            NativeRuntimeLoopHeadEndStatus.LANE_COMPLETED,
            runtime.markLoopHeadPreparationEndOfStream(intent.revision, 0, 22L, 48_004L)
        )
        backend.loopHeadEndStatus = 13
        assertEquals(
            NativeRuntimeLoopHeadEndStatus.PADDING_PENDING,
            runtime.markLoopHeadPreparationEndOfStream(intent.revision, 0, 22L, 48_004L)
        )
        assertEquals(NativeRuntimeLoopHeadAbortStatus.ACCEPTED,
            runtime.abortLoopHeadPreparation(intent.revision))

        backend.loopProtocolValues.apply {
            this[1] = 1L
            this[2] = NativeRuntimeBank.SECOND.nativeCode.toLong()
            this[3] = NativeRuntimeLoopCycleMode.STREAMED.nativeCode.toLong()
            this[4] = NativeRuntimeLoopCyclePhase.AWAITING_RENDER_COMMIT.nativeCode.toLong()
            this[5] = NativeRuntimeLoopCycleActivation.WARM_CURRENT_PASS.nativeCode.toLong()
            this[6] = intent.revision
            this[7] = owner.selectionToken
            this[8] = owner.generation
            this[9] = 3L
            this[10] = owner.generation
            this[11] = owner.generation + 1L
            this[12] = Long.MIN_VALUE + 3L
            (13..18).forEach { this[it] = 1L }
            this[19] = 1L
            this[20] = 9L
            this[21] = intent.revision
            this[22] = 4L
            this[23] = owner.selectionToken
            this[24] = owner.generation
            this[25] = owner.generation + 1L
            this[26] = 240_000L
            this[27] = 1L
            this[28] = NativeRuntimeLoopCycleActivation.WARM_CURRENT_PASS.nativeCode.toLong()
            this[29] = 1L
            this[30] = 10L
            this[31] = NativeRuntimeLoopDirectiveAction.WRAP_STREAMED.nativeCode.toLong()
            this[32] = intent.revision
            this[33] = 4L
            this[34] = owner.selectionToken
            this[35] = owner.generation
            this[36] = owner.generation + 1L
            this[37] = key.startTimelineFrame
            this[38] = 480_000L
            this[39] = 240_000L
            this[40] = Long.MIN_VALUE + 4L
            this[41] = 1L
            this[42] = 240L
            this[43] = 1L
            this[44] = 11L
            this[45] = intent.revision
            this[46] = 4L
            this[47] = owner.selectionToken
            this[48] = owner.generation
            this[49] = 88L
            this[50] = 1L
            this[51] = 1L
            this[52] = key.songIdentityHash
            this[53] = key.originalContentHash
            this[54] = key.stemPackageHash
            this[55] = key.contentRevision
            this[56] = key.loopRevision
            this[57] = key.startTimelineFrame
            this[58] = key.endTimelineFrameExclusive
            this[59] = 1L
            this[60] = 1L
            this[61] = NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES
            this[62] = 0L
            this[63] = Long.MIN_VALUE + 5L
            (64..73).forEach { this[it] = Long.MIN_VALUE + it }
            this[74] = NativeRuntimeLoopHeadPreparationPhase.COMMITTED.nativeCode.toLong()
            this[75] = intent.revision
            this[76] = 1L
            this[77] = 1L
            this[78] = 7L
            this[79] = 48_000L
            this[80] = 22L
            this[81] = 240_000L
            (82..111).forEach { this[it] = (it - 81).toLong() }
            // Current closed native codes: rebound, key mismatch, missing lanes, stale
            // observation, invalid boundary, stale sequence, superseded physical policy.
            listOf(3L, 4L, 7L, 7L, 8L, 2L, 7L).forEachIndexed { index, code -> this[112 + index] = code }
            this[119] = 90L
            this[120] = 91L
            this[121] = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS.nativeCode.toLong()
            this[122] = 1L
            this[123] = 480_000L
            this[124] = 244_800L
            this[125] = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS.nativeCode.toLong()
            this[126] = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS.nativeCode.toLong()
            this[127] = 1L
            this[128] = key.endTimelineFrameExclusive
            this[129] = 2L
            this[130] = 31L
            this[131] = 32L
        }

        val protocol = runtime.loopProtocolSnapshot()
        assertTrue(protocol.adopted)
        assertEquals(NativeRuntimeBank.SECOND, protocol.bank)
        assertEquals(NativeRuntimeLoopCycleMode.STREAMED, protocol.state.mode)
        assertEquals(owner, protocol.state.ownership)
        assertTrue(protocol.state.boundaryResolved)
        assertEquals(480_000L,
            protocol.state.resolvedLoopEndTimelineFrameExclusive)
        assertEquals(ready.ingressEpoch, protocol.ingressRequest?.ingressEpoch)
        assertEquals(244_800L, protocol.ingressRequest?.requiredEndTimelineFrameExclusive)
        assertEquals(NativeRuntimeLoopDirectiveAction.WRAP_STREAMED,
            protocol.renderDirective?.action)
        assertEquals(88L, protocol.audibleEvidence?.callbackSequence)
        assertEquals(key, protocol.activeCache?.key)
        assertFalse(requireNotNull(protocol.activeCache).cacheOnly)
        assertEquals(NativeRuntimeManagedLoopIntent.HEAD_CACHE_CAPACITY_FRAMES,
            requireNotNull(protocol.activeCache).frameCount.toLong())
        assertEquals(NativeSongLaneIngressBank.LANE_COUNT, requireNotNull(protocol.activeCache).laneChecksums.size)
        assertEquals(Long.MIN_VALUE + 73L, requireNotNull(protocol.activeCache).laneChecksums[9])
        assertEquals(NativeRuntimeLoopHeadPreparationPhase.COMMITTED,
            protocol.preparation.phase)
        assertEquals(30L, protocol.counters.rejectedCallbackProofs)
        assertEquals(31L, protocol.counters.originalEosAccepted)
        assertEquals(32L, protocol.counters.originalEosRejected)
        assertEquals(90L, protocol.positionEvidenceSequence)
        assertEquals(91L, protocol.lastOutputLoopEvidenceSequence)
        assertEquals(3, protocol.lastSubmitStatus)
        assertEquals(4, protocol.lastCacheStatus)
        assertEquals(7, protocol.lastIngressStatus)
        assertEquals(7, protocol.lastPositionStatus)
        assertEquals(8, protocol.lastBoundaryStatus)
        assertEquals(2, protocol.lastRenderCommitStatus)
        assertEquals(7, protocol.lastCallbackStatus)
        runtime.close()
    }

    @Test
    fun managedLoopIngressReadyAbiCarriesOnlyNonOriginalEosPaddingBits() {
        val owner = NativeRuntimeOwnership(41L, 42L)
        val evidence = NativeRuntimeManagedLoopIngressReady(
            requestSequence = 1L,
            loopRevision = 2L,
            targetCycle = 3L,
            ownership = owner,
            ingressEpoch = 43L,
            readyLaneMask = 0b11,
            eosPaddedLaneMask = 0b10,
            firstTimelineFrame = 192_000L,
            endTimelineFrameExclusive = 196_800L
        )
        assertArrayEquals(
            longArrayOf(
                2L, 1L, 2L, 3L, 41L, 42L, 43L, 0b11L, 0b10L, 192_000L, 196_800L
            ),
            NativeRuntimeManagedLoopIngressReadyCodec.encode(evidence)
        )
        assertFails<IllegalArgumentException> {
            evidence.copy(eosPaddedLaneMask = NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK)
        }
        assertFails<IllegalArgumentException> {
            evidence.copy(readyLaneMask = 0b1, eosPaddedLaneMask = 0b10)
        }
    }

    @Test
    fun loopProtocolCodecAcceptsZeroEpochOnlyForNonRenderingClearDirective() {
        val values = LongArray(NativeRuntimeLoopProtocolCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeLoopProtocolCodec.SCHEMA_VERSION
            this[29] = 1L
            this[30] = 1L
            this[31] = NativeRuntimeLoopDirectiveAction.CLEAR.nativeCode.toLong()
            this[32] = 2L
            this[34] = 3L
            this[35] = 4L
            this[36] = 0L
        }

        val clear = requireNotNull(NativeRuntimeLoopProtocolCodec.decode(values).renderDirective)
        assertEquals(NativeRuntimeLoopDirectiveAction.CLEAR, clear.action)
        assertEquals(0L, clear.ingressEpoch)

        listOf(
            NativeRuntimeLoopDirectiveAction.INSTALL_WARM_CACHE_ONLY,
            NativeRuntimeLoopDirectiveAction.ACTIVATE_COLD_CACHE_ONLY,
            NativeRuntimeLoopDirectiveAction.WRAP_CACHE_ONLY,
            NativeRuntimeLoopDirectiveAction.DEADLINE_FALLBACK
        ).forEach { action ->
            values[31] = action.nativeCode.toLong()
            assertFails<IllegalArgumentException> {
                NativeRuntimeLoopProtocolCodec.decode(values)
            }
        }
    }

    private fun runtime(backend: FakeBackend) = NativePlaybackRuntime(
        outputHandle = 11L,
        firstBankHandle = 12L,
        secondBankHandle = 13L,
        configuration = NativeRuntimeConfiguration(),
        backend = backend
    )

    private fun emptyDiagnostics() = LongArray(NativeRuntimeDiagnosticsCodec.VALUE_COUNT).apply {
        this[0] = NativeRuntimeDiagnosticsCodec.SCHEMA_VERSION
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull(failure)
        assertTrue(
            "Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.simpleName}",
            failure is T
        )
    }

    private class FakeBackend : NativePlaybackRuntimeBackend {
        var loopHeadEndStatus = NativeRuntimeLoopHeadEndStatus.LANE_COMPLETED.ordinal
        var windowTicket = 41L
        var windowStatus: Int? = null
        var windowSamples: FloatArray? = null
        var windowOwner: LongArray? = null
        var windowBounds: List<Long>? = null
        var windowBegins = 0
        var onWindowPoll: (() -> Unit)? = null
        val windowCancellations = mutableListOf<Long>()
        override fun beginPcmWindowRead(handle: Long, owner: LongArray, lane: Int, first: Long, frames: Int): Long {
            windowBegins++
            windowOwner = owner.copyOf()
            windowBounds = listOf(lane.toLong(), first, frames.toLong())
            return windowTicket
        }
        override fun pollPcmWindowRead(handle: Long, ticket: Long, pcm: java.nio.ByteBuffer, frames: Int): Int {
            onWindowPoll?.invoke()
            windowStatus?.let { return it }
            val samples = windowSamples ?: return 0
            pcm.asFloatBuffer().put(samples)
            return samples.size / 2
        }
        override fun cancelPcmWindowRead(handle: Long, ticket: Long) { windowCancellations += ticket }

        val events = mutableListOf<String>()
        val values = LongArray(NativeRuntimeDiagnosticsCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeDiagnosticsCodec.SCHEMA_VERSION
        }
        var lastRangeOwner = LongArray(0)
        var lastRangeBounds = LongArray(0)
        override fun pcmCacheRangeReady(handle: Long, owner: LongArray, requiredLaneMask: Int,
            first: Long, end: Long): Boolean {
            lastRangeOwner = owner.copyOf()
            lastRangeBounds = longArrayOf(requiredLaneMask.toLong(), first, end)
            return true
        }
        var stemGainCallbackValues = LongArray(0)
        var renderReadinessValues = LongArray(0)
        var lastLongs = longArrayOf()
        var lastFloats = floatArrayOf()
        var lastPreviewPublicationSequence = 0L
        var lastPreviewCoversOfferSequence = 0L
        var lastPreviewSelectionToken = 0L
        var lastPreviewGeneration = 0L
        var lastPreviewSourceIntentRevision = 0L
        var lastPreviewStemIntentRevision = 0L
        var lastPreviewCanonicalControlFloorRevision = 0L
        var lastPreviewActiveMask = 0
        var lastPreviewGains = floatArrayOf()
        var lastBeatFrames = longArrayOf()
        var lastBeatAccentFlags: ByteArray? = null
        var lastManagedLoopIntent = longArrayOf()
        var lastManagedLoopReady = longArrayOf()
        var lastLoopHeadBeginIntent = longArrayOf()
        val loopProtocolValues = LongArray(NativeRuntimeLoopProtocolCodec.VALUE_COUNT).apply {
            this[0] = NativeRuntimeLoopProtocolCodec.SCHEMA_VERSION
        }
        var closeCalls = 0
        var createdHandle = 99L
        var lastConfiguration: NativeRuntimeConfiguration? = null

        override fun create(
            outputHandle: Long,
            firstBankHandle: Long,
            secondBankHandle: Long,
            configuration: NativeRuntimeConfiguration
        ): Long {
            events += "create"
            lastConfiguration = configuration
            return createdHandle
        }

        override fun start(handle: Long): Boolean {
            events += "start"
            return true
        }

        override fun requestStop(handle: Long) {
            events += "stop"
        }

        override fun join(handle: Long) {
            events += "join"
        }

        override fun publishCommand(handle: Long, longs: LongArray, floats: FloatArray): Int {
            events += "publish"
            lastLongs = longs.clone()
            lastFloats = floats.clone()
            return NativeRuntimePublishStatus.ACCEPTED.ordinal
        }

        override fun publishStemGainPreview(
            handle: Long,
            publicationSequence: Long,
            coversOfferSequence: Long,
            selectionToken: Long,
            generation: Long,
            sourceIntentRevision: Long,
            stemIntentRevision: Long,
            canonicalControlFloorRevision: Long,
            activeMask: Int,
            gains: FloatArray
        ): Int {
            events += "preview"
            lastPreviewPublicationSequence = publicationSequence
            lastPreviewCoversOfferSequence = coversOfferSequence
            lastPreviewSelectionToken = selectionToken
            lastPreviewGeneration = generation
            lastPreviewSourceIntentRevision = sourceIntentRevision
            lastPreviewStemIntentRevision = stemIntentRevision
            lastPreviewCanonicalControlFloorRevision = canonicalControlFloorRevision
            lastPreviewActiveMask = activeMask
            lastPreviewGains = gains.clone()
            return NativeRuntimePublishStatus.ACCEPTED.ordinal
        }

        override fun publishBeatGrid(
            handle: Long,
            schemaVersion: Long,
            revision: Long,
            bank: Int,
            selectionToken: Long,
            generation: Long,
            beatFrames: LongArray,
            accentFlags: ByteArray
        ): Int {
            assertEquals(NativeRuntimeBeatGridCodec.SCHEMA_VERSION, schemaVersion)
            lastBeatFrames = beatFrames.clone()
            lastBeatAccentFlags = accentFlags.clone()
            return NativeRuntimePublishStatus.ACCEPTED.ordinal
        }

        override fun publishLoopCycleIntent(handle: Long, encodedIntent: LongArray): Int {
            lastManagedLoopIntent = encodedIntent.clone()
            return NativeRuntimePublishStatus.ACCEPTED.ordinal
        }

        override fun publishLoopIngressReady(handle: Long, encodedEvidence: LongArray): Int {
            lastManagedLoopReady = encodedEvidence.clone()
            return NativeRuntimePublishStatus.ACCEPTED.ordinal
        }

        override fun beginLoopHeadPreparation(handle: Long, encodedIntent: LongArray): Int {
            lastLoopHeadBeginIntent = encodedIntent.clone()
            return NativeRuntimeLoopHeadBeginStatus.ACCEPTED.ordinal
        }

        override fun configureLoopHeadPreparationLane(
            handle: Long,
            schemaVersion: Long,
            loopRevision: Long,
            laneIndex: Int,
            sourceRateHz: Int,
            preparationGeneration: Long
        ): Int = NativeRuntimeLoopHeadConfigureStatus.ACCEPTED.ordinal

        override fun offerLoopHeadPreparationPcm(
            handle: Long,
            schemaVersion: Long,
            loopRevision: Long,
            laneIndex: Int,
            preparationGeneration: Long,
            presentationTimeUs: Long,
            sourceRateHz: Int,
            encodingCode: Int,
            channelCount: Int,
            directBuffer: java.nio.ByteBuffer,
            byteOffset: Int,
            frameCount: Int
        ): Long =
            (NativeRuntimeLoopHeadOfferStatus.ACCEPTED.ordinal.toLong() shl Int.SIZE_BITS) or
                frameCount.toLong()

        override fun markLoopHeadPreparationEndOfStream(
            handle: Long,
            schemaVersion: Long,
            loopRevision: Long,
            laneIndex: Int,
            preparationGeneration: Long,
            finalSourceFrameExclusive: Long
        ): Int = loopHeadEndStatus

        override fun abortLoopHeadPreparation(
            handle: Long,
            schemaVersion: Long,
            loopRevision: Long
        ): Int = NativeRuntimeLoopHeadAbortStatus.ACCEPTED.ordinal

        override fun loopProtocolSnapshot(handle: Long): LongArray = loopProtocolValues.clone()

        override fun notifyIngressAvailable(handle: Long) {
            events += "notify"
        }

        var fullDiagnosticReads = 0
        override fun audibleControlEvidence(handle: Long): LongArray =
            longArrayOf(NativeRuntimeAudibleControlCodec.SCHEMA_VERSION,
                *values.copyOfRange(NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_SEQUENCE,
                    NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_PCM_FRAMES + 1),
                values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_TEMPO_BITS],
                values[NativePacketSchema.RUNTIME_DIAGNOSTICS_AUDIBLE_CONTROL_ACTUAL_PITCH_SEMITONES_BITS])
        override fun diagnostics(handle: Long): LongArray {
            fullDiagnosticReads++
            return values.clone()
        }

        override fun stemGainCallbackSettlementDiagnostics(handle: Long): LongArray =
            stemGainCallbackValues.clone()

        override fun renderReadinessDiagnostics(handle: Long): LongArray =
            renderReadinessValues.clone()

        override fun close(handle: Long) {
            events += "close"
            closeCalls++
        }
    }
}
