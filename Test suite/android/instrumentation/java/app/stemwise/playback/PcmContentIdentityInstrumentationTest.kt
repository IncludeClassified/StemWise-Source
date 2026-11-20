package app.stemwise.playback

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.StemwiseApplication
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.playback.engine.NativeRuntimeAutomaticSuccessorPhase
import app.stemwise.playback.engine.NativeRuntimeOwnership
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves content identity, not only numeric ownership.
 *
 * Every fixture is a low-level, zero-mean two-frame PCM watermark. The render-payload FNV hash is
 * deterministic after the 240-frame production ramp settles, but the renderer may emit any block
 * length in 1..480 frames (the 10 ms quantum at 48 kHz). The production payload is the original stereo bus,
 * silent target/Remaining stereo buses, a silent click channel, and the settled unit envelope. The oracle contains both
 * watermark phases for every legal block length and first proves that no two watermark classes
 * share a hash.
 *
 * A sampled `(output fingerprint sequence, hash, ownership)` is accepted only after two identical
 * native reads and after the callback presentation journal contains that exact sequence under the
 * same token/generation. This joins known post-mixer content to physical callback consumption;
 * state/title/artwork agreement alone cannot pass this test.
 */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class PcmContentIdentityInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application =
        instrumentation.targetContext.applicationContext as StemwiseApplication
    private val playback
        get() = application.services.audioPlayback

    @get:Rule
    val disposablePlaybackTarget: org.junit.rules.TestRule = DisposablePlaybackTargetRule()

    @Before
    fun isolatePlaybackBeforeTest() {
        isolateAndCleanOwnedState()
        onMain { playback.setDiagnosticPayloadFingerprintsForTest(true) }
    }

    @After
    fun isolatePlaybackAfterTest() {
        try { isolateAndCleanOwnedState() } finally {
            onMain { playback.setDiagnosticPayloadFingerprintsForTest(false) }
        }
    }

    @Test
    fun committedManualRapidAndAutomaticOwnersPresentTheExpectedWatermark() {
        resetPlayback()
        val directory = File(application.cacheDir, "pcm-content-identity-v1").apply { mkdirs() }
        val watermarkSpecs = List(WATERMARK_CLASS_COUNT) { index ->
            WatermarkSpec(
                index = index,
                left = (900 + index * 233).toShort(),
                right = (-(1_400 + index * 317)).toShort()
            )
        }
        val hashSets = watermarkSpecs.associateWith(::stableFingerprintSet)
        assertDisjoint(hashSets)

        val fixtures = watermarkSpecs.map { spec ->
            watermarkedSong(
                directory = directory,
                id = "content-identity-${spec.index.toString().padStart(2, '0')}",
                durationMs = MANUAL_FIXTURE_DURATION_MS,
                spec = spec
            )
        }

        try {
            onMain {
                playback.setRepeatMode("Off")
                playback.setShuffle(false)
            }

            var baseline = proofBaseline()
            onMain {
                playback.startQueue(
                    songs = fixtures.map(WatermarkedSong::song),
                    shuffle = false,
                    autoplay = true,
                    seed = MANUAL_QUEUE_SEED
                )
            }
            assertPresentedIdentity(
                fixture = fixtures.first(),
                expectedHashes = hashSets.getValue(fixtures.first().spec),
                baseline = baseline,
                label = "manual class 0"
            )
            fixtures.drop(1).forEach { fixture ->
                baseline = proofBaseline()
                onMain { playback.selectSong(fixture.song, autoplay = true, positionMs = 0L) }
                assertPresentedIdentity(
                    fixture = fixture,
                    expectedHashes = hashSets.getValue(fixture.spec),
                    baseline = baseline,
                    label = "manual class ${fixture.spec.index}"
                )
            }

            val aliases = List(PRESSURE_SONG_COUNT) { index ->
                val fixture = fixtures[index % fixtures.size]
                val suffix = index.toString().padStart(3, '0')
                WatermarkedSong(
                    song = fixture.song.copy(
                        id = "content-pressure-$suffix",
                        videoId = "content-pressure-video-$suffix",
                        title = "Content Pressure $suffix",
                        artworkGlyph = (index % 10).toString(),
                        audioRevision = "content-pressure-$suffix-watermark-${fixture.spec.index}",
                        mediaRevision = "content-pressure-$suffix-metadata-v1"
                    ),
                    spec = fixture.spec
                )
            }
            assertEquals(PRESSURE_SONG_COUNT, aliases.map { it.song.id }.distinct().size)
            assertNotEquals(aliases[PRESSURE_SONG_COUNT - 2].spec, aliases.last().spec)

            baseline = proofBaseline()
            onMain {
                playback.startQueue(
                    songs = aliases.map(WatermarkedSong::song),
                    shuffle = false,
                    autoplay = true,
                    seed = PRESSURE_QUEUE_SEED
                )
            }
            assertPresentedIdentity(
                fixture = aliases.first(),
                expectedHashes = hashSets.getValue(aliases.first().spec),
                baseline = baseline,
                label = "400-id initial owner"
            )

            baseline = proofBaseline()
            onMain {
                aliases.drop(1).forEach { target ->
                    assertTrue("Rapid selection ${target.song.id} was rejected",
                        playback.selectSong(target.song, autoplay = true, positionMs = 0L))
                }
            }
            // Acceptance starts source resolution. Only the exact callback/PCM proof below
            // establishes completion, including the queue and adapter projections.
            val rapidProof = assertPresentedIdentity(
                fixture = aliases.last(),
                expectedHashes = hashSets.getValue(aliases.last().spec),
                baseline = baseline,
                label = "400-id rapid latest-wins",
                timeoutMs = RAPID_SETTLE_TIMEOUT_MS
            )
            val settled = playback.selectionPressureDiagnosticsForTest()
            assertEquals(aliases.last().song.id, settled.queueCurrentId)
            assertEquals(aliases.last().song.id, settled.latestRequestedSongId)
            assertEquals(rapidProof.ownership.selectionToken, settled.latestRequestedToken)
            assertEquals(PRESSURE_SONG_COUNT, playback.state.value.queueOrder.size)

            val automaticA = watermarkedSong(
                directory = directory,
                id = "content-automatic-a",
                durationMs = AUTOMATIC_OUTGOING_DURATION_MS,
                spec = watermarkSpecs[WATERMARK_CLASS_COUNT - 2]
            )
            val automaticB = watermarkedSong(
                directory = directory,
                id = "content-automatic-b",
                durationMs = AUTOMATIC_TARGET_DURATION_MS,
                spec = watermarkSpecs.last()
            )
            assertNotEquals(automaticA.spec, automaticB.spec)

            baseline = proofBaseline()
            onMain {
                playback.setRepeatMode("Off")
                playback.startQueue(
                    songs = listOf(automaticA.song, automaticB.song),
                    shuffle = false,
                    autoplay = true,
                    seed = AUTOMATIC_QUEUE_SEED
                )
            }
            val outgoingProof = assertPresentedIdentity(
                fixture = automaticA,
                expectedHashes = hashSets.getValue(automaticA.spec),
                baseline = baseline,
                label = "automatic outgoing A"
            )
            val armedSuccessor = awaitArmedAutomaticSuccessor(outgoingProof.ownership)
            val automaticBaseline = proofBaseline()
            val targetProof = assertPresentedIdentity(
                fixture = automaticB,
                expectedHashes = hashSets.getValue(automaticB.spec),
                baseline = automaticBaseline,
                label = "automatic likely-next B",
                expectedOwnership = armedSuccessor,
                timeoutMs = AUTOMATIC_TARGET_TIMEOUT_MS
            )
            assertEquals(armedSuccessor, targetProof.ownership)
            assertNotEquals(outgoingProof.ownership, targetProof.ownership)
        } finally {
            resetPlayback()
        }
    }

    private fun assertPresentedIdentity(
        fixture: WatermarkedSong,
        expectedHashes: Set<Long>,
        baseline: ProofBaseline,
        label: String,
        expectedOwnership: NativeRuntimeOwnership? = null,
        timeoutMs: Long = IDENTITY_TIMEOUT_MS
    ): PresentedIdentityProof {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous: FingerprintCandidate? = null
        var identicalReads = 0
        var pendingPresentation: FingerprintCandidate? = null
        var lastDiagnostic = "no native snapshot"
        while (SystemClock.elapsedRealtime() < deadline) {
            val native = playback.nativeDiagnosticsSnapshotForTest()
            val output = native?.output
            val owner = native?.runtime?.ownership?.committed
            val selection = playback.selectionPressureDiagnosticsForTest()
            if (native != null && output != null && owner != null) {
                val sequence = native.runtime.counters.lastOutputFingerprintSequence
                val hash = native.runtime.counters.lastRenderPayloadHash
                val candidate = FingerprintCandidate(owner, sequence, hash)
                val facadeAligned = playback.state.value.songId == fixture.song.id &&
                    playback.state.value.playing &&
                    !playback.state.value.loading &&
                    playback.graphMediaId() == fixture.song.id &&
                    playback.sessionMediaItem()?.mediaId == fixture.song.id &&
                    selection.committedSongId == fixture.song.id &&
                    selection.committedToken == owner.selectionToken &&
                    playback.primaryAudibleOwnershipEpochForTest() == owner.generation &&
                    native.runtime.ownership.requested == owner &&
                    native.runtime.ownership.accepted == owner &&
                    output.playing &&
                    output.lastPresentedGeneration == owner.generation &&
                    output.lastPresentedSelectionToken == owner.selectionToken &&
                    (expectedOwnership == null || owner == expectedOwnership)
                val knownStableContent = sequence > baseline.runtimeFingerprintSequence &&
                    hash in expectedHashes
                if (facadeAligned && knownStableContent) {
                    if (candidate == previous) {
                        identicalReads++
                    } else {
                        previous = candidate
                        identicalReads = 1
                    }
                    if (identicalReads >= REQUIRED_IDENTICAL_PAIR_READS &&
                        pendingPresentation == null
                    ) {
                        pendingPresentation = candidate
                    }
                } else {
                    previous = null
                    identicalReads = 0
                }
                val pending = pendingPresentation
                if (facadeAligned && pending?.ownership == owner &&
                    output.lastPresentedFingerprintSequence >= pending.sequence
                ) {
                    val journal = playback.presentationJournalForTest(
                        baseline.presentationJournalSequence
                    )
                    val span = journal.entries.firstOrNull { entry ->
                            entry.generation == pending.ownership.generation &&
                            entry.selectionToken == pending.ownership.selectionToken &&
                            pending.sequence in
                            entry.firstFingerprintSequence..entry.lastFingerprintSequence
                    }
                    if (journal.coherent && journal.overflowCount == 0L &&
                        journal.writerCollisionCount == 0L &&
                        journal.invalidPublicationCount == 0L && span != null
                    ) {
                        return PresentedIdentityProof(
                            ownership = pending.ownership,
                            fingerprintSequence = pending.sequence,
                            fingerprintHash = pending.hash,
                            presentationSpanSequence = span.sequence
                        )
                    }
                }
                lastDiagnostic = "song=${playback.state.value.songId}, graph=" +
                    "${playback.graphMediaId()}, session=${playback.sessionMediaItem()?.mediaId}, " +
                    "owner=$owner, expectedOwner=$expectedOwnership, committed=" +
                    "${selection.committedSongId}/${selection.committedToken}, seq=$sequence, " +
                    "baselineSeq=${baseline.runtimeFingerprintSequence}, hash=$hash, " +
                    "knownHash=${hash in expectedHashes}, presented=" +
                    "${output.lastPresentedGeneration}/${output.lastPresentedSelectionToken}/" +
                    "${output.lastPresentedFingerprintSequence}, pendingSeq=" +
                    pendingPresentation?.sequence
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "$label did not present its known PCM watermark: $lastDiagnostic; " +
                "current=${playback.instrumentationIsolationDiagnostics()}"
        )
    }

    private fun awaitArmedAutomaticSuccessor(
        outgoingOwnership: NativeRuntimeOwnership
    ): NativeRuntimeOwnership {
        val deadline = SystemClock.elapsedRealtime() + AUTOMATIC_ARM_TIMEOUT_MS
        var last = playback.nativeDiagnosticsSnapshotForTest()?.runtime?.automaticSuccessor
        while (SystemClock.elapsedRealtime() < deadline) {
            val automatic = playback.nativeDiagnosticsSnapshotForTest()
                ?.runtime
                ?.automaticSuccessor
            last = automatic ?: last
            val successor = automatic?.successorOwnership
            if (automatic != null &&
                automatic.phase != NativeRuntimeAutomaticSuccessorPhase.IDLE &&
                automatic.outgoingOwnership == outgoingOwnership &&
                successor != null && successor != outgoingOwnership &&
                automatic.successorQueuedFrames > 0L &&
                automatic.firstFingerprintSequence > 0L &&
                !automatic.cancellationDesired
            ) {
                return successor
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("Automatic likely-next B never armed physical PCM: $last")
    }

    private fun proofBaseline(): ProofBaseline {
        val native = playback.nativeDiagnosticsSnapshotForTest()
        val journal = playback.presentationJournalForTest(0L)
        return ProofBaseline(
            runtimeFingerprintSequence =
                native?.runtime?.counters?.lastOutputFingerprintSequence ?: 0L,
            presentationJournalSequence = journal.writeSequence
        )
    }

    private fun assertDisjoint(hashSets: Map<WatermarkSpec, Set<Long>>) {
        val owners = mutableMapOf<Long, WatermarkSpec>()
        hashSets.forEach { (spec, hashes) ->
            assertTrue("Watermark ${spec.index} produced no stable hashes", hashes.isNotEmpty())
            hashes.forEach { hash ->
                val previous = owners.putIfAbsent(hash, spec)
                assertTrue(
                    "Watermark hash collision between classes ${previous?.index} and ${spec.index}: " +
                        hash,
                    previous == null || previous == spec
                )
            }
        }
    }

    private fun stableFingerprintSet(spec: WatermarkSpec): Set<Long> = buildSet {
        repeat(WATERMARK_PERIOD_FRAMES) { startingPhase ->
            for (frameCount in 1..MAX_RENDER_BLOCK_FRAMES) {
                var hash = FNV_OFFSET
                repeat(frameCount) { frame ->
                    val invert = ((startingPhase + frame) and 1) != 0
                    val left = if (invert) -spec.left.toInt() else spec.left.toInt()
                    val right = if (invert) -spec.right.toInt() else spec.right.toInt()
                    hash = hashFingerprintValue(
                        hash,
                        quantizedPayloadSample(left.toFloat() / 32_768.0f)
                    )
                    hash = hashFingerprintValue(
                        hash,
                        quantizedPayloadSample(right.toFloat() / 32_768.0f)
                    )
                    repeat(SILENT_PAYLOAD_CHANNELS) {
                        hash = hashFingerprintValue(hash, 0.toShort())
                    }
                    hash = hashFingerprintValue(hash, quantizedPayloadSample(1.0f))
                }
                add(if (hash == 0L) FNV_OFFSET else hash)
            }
        }
    }

    private fun quantizedPayloadSample(sample: Float): Short {
        val scaled = sample.coerceIn(-1.0f, 1.0f) * 32_767.0f
        val rounded = if (scaled >= 0.0f) {
            (scaled + 0.5f).toInt()
        } else {
            (scaled - 0.5f).toInt()
        }
        return rounded.toShort()
    }

    private fun hashFingerprintValue(start: Long, value: Short): Long {
        val bits = value.toInt() and 0xffff
        var hash = start xor (bits and 0xff).toLong()
        hash *= FNV_PRIME
        hash = hash xor ((bits ushr 8) and 0xff).toLong()
        hash *= FNV_PRIME
        return hash
    }

    private fun watermarkedSong(
        directory: File,
        id: String,
        durationMs: Long,
        spec: WatermarkSpec
    ): WatermarkedSong {
        val file = File(directory, "$id.wav")
        writeWatermarkWave(file, durationMs, spec)
        return WatermarkedSong(
            song = Song(
                id = id,
                videoId = id,
                title = id,
                artist = "StemWise content identity fixture",
                durationMs = durationMs,
                state = SongState.READY,
                accent = Color.Cyan,
                artworkGlyph = spec.index.toString(16).uppercase(),
                sourceHash = PlaybackContentFixture.sourceHash(file),
            sourcePath = PlaybackContentFixture.publish(application, file),
                sourceCodec = "pcm_s16le",
                sourceContainer = "wav",
                audioRevision = "$id-watermark-${spec.index}",
                mediaRevision = "$id-metadata-v1"
            ),
            spec = spec
        )
    }

    private fun writeWatermarkWave(file: File, durationMs: Long, spec: WatermarkSpec) {
        val frameCount = (durationMs * SAMPLE_RATE / 1_000L).toInt()
        val dataSize = frameCount * CHANNEL_COUNT * Short.SIZE_BYTES
        if (file.isFile && file.length() == WAV_HEADER_BYTES + dataSize.toLong()) return
        DataOutputStream(FileOutputStream(file).buffered()).use { output ->
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataSize))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(CHANNEL_COUNT.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * CHANNEL_COUNT * Short.SIZE_BYTES))
            output.writeShort(
                java.lang.Short.reverseBytes((CHANNEL_COUNT * Short.SIZE_BYTES).toShort()).toInt()
            )
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataSize))
            repeat(frameCount) { frame ->
                val invert = (frame and 1) != 0
                val left = if (invert) (-spec.left.toInt()).toShort() else spec.left
                val right = if (invert) (-spec.right.toInt()).toShort() else spec.right
                output.writeShort(java.lang.Short.reverseBytes(left).toInt())
                output.writeShort(java.lang.Short.reverseBytes(right).toInt())
            }
        }
    }

    private fun isolateAndCleanOwnedState() {
        resetPlayback()
        val failures = mutableListOf<Throwable>()
        runCatching { PlaybackContentFixture.clear(application) }.exceptionOrNull()?.let(failures::add)
        runCatching {
            val directory = File(application.cacheDir, "pcm-content-identity-v1")
            check(!directory.exists() || directory.deleteRecursively()) {
                "Unable to delete PCM identity fixtures ${directory.absolutePath}"
            }
        }.exceptionOrNull()?.let(failures::add)
        if (failures.isNotEmpty()) {
            val failure = AssertionError("PCM identity isolation or cleanup failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun resetPlayback() {
        playback.resetForInstrumentationIsolation(instrumentation, qualificationDiagnostics = true)
        onMain {
            playback.setRepeatMode("Off")
            playback.setShuffle(false)
            playback.setPlaybackQueue(emptyList(), shuffle = false, snapshotReady = true)
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private data class WatermarkSpec(
        val index: Int,
        val left: Short,
        val right: Short
    )

    private data class WatermarkedSong(
        val song: Song,
        val spec: WatermarkSpec
    )

    private data class ProofBaseline(
        val runtimeFingerprintSequence: Long,
        val presentationJournalSequence: Long
    )

    private data class FingerprintCandidate(
        val ownership: NativeRuntimeOwnership,
        val sequence: Long,
        val hash: Long
    )

    private data class PresentedIdentityProof(
        val ownership: NativeRuntimeOwnership,
        val fingerprintSequence: Long,
        val fingerprintHash: Long,
        val presentationSpanSequence: Long
    )

    private companion object {
        const val WATERMARK_CLASS_COUNT = 16
        const val WATERMARK_PERIOD_FRAMES = 2
        const val MAX_RENDER_BLOCK_FRAMES = 480
        const val PRESSURE_SONG_COUNT = 400
        const val SAMPLE_RATE = 48_000
        const val CHANNEL_COUNT = 2
        const val SILENT_PAYLOAD_CHANNELS = (StemSourceCatalogue.MAX_TARGETS + 1) * CHANNEL_COUNT + 1
        const val WAV_HEADER_BYTES = 44L
        const val MANUAL_FIXTURE_DURATION_MS = 6_000L
        const val AUTOMATIC_OUTGOING_DURATION_MS = 1_803L
        const val AUTOMATIC_TARGET_DURATION_MS = 6_000L
        const val IDENTITY_TIMEOUT_MS = 2_500L
        const val RAPID_SETTLE_TIMEOUT_MS = 4_000L
        const val AUTOMATIC_ARM_TIMEOUT_MS = 1_200L
        const val AUTOMATIC_TARGET_TIMEOUT_MS = 4_000L
        const val POLL_MS = 2L
        const val REQUIRED_IDENTICAL_PAIR_READS = 2
        const val MANUAL_QUEUE_SEED = 0x4D414E55414CL
        const val PRESSURE_QUEUE_SEED = 0x5052455353555245L
        const val AUTOMATIC_QUEUE_SEED = 0x4155544F4D415449L
        const val FNV_OFFSET = -3_750_763_034_362_895_579L
        const val FNV_PRIME = 1_099_511_628_211L
    }
}
