package app.stemwise.playback

import android.media.AudioManager
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.stemwise.StemwiseApplication
import app.stemwise.data.StemAssetEntity
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.data.parseCustomSourceManifest
import app.stemwise.demo.UiDemoLibraryDocumentsProvider
import app.stemwise.playback.engine.NativeAudioBackend
import app.stemwise.playback.engine.NativeAudioDiagnostics
import app.stemwise.playback.engine.NativeAudioPlaybackSeekReceipt
import app.stemwise.model.Song
import app.stemwise.model.SongState
import app.stemwise.qa.DirectLoopEnduranceOracle
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

internal fun consumerAdoptionBudgetMicros(output: NativeAudioDiagnostics): Long {
    if (output.backend != NativeAudioBackend.AUDIO_TRACK) return 10_000L
    require(output.sampleRateHz > 0)
    // Reliable push playback preserves one held block before adopting the next policy.
    val pendingBlockMicros =
        (app.stemwise.playback.engine.NativeAudioTrackFeeder.BLOCK_FRAMES.toLong() * 1_000_000L +
            output.sampleRateHz - 1L) / output.sampleRateHz
    return 10_000L + pendingBlockMicros
}

@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class LoopPlaybackInstrumentationTest : LoopPlaybackFixture() {
    @Test
    fun inputTimestampedLoopPoliciesPreserveAudioWithinTheOutputServiceBudget() {
        val atomicDurationMs = 30_000L
        val methodStartedAt = SystemClock.elapsedRealtime()
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "loop-qualification").apply { mkdirs() }
        val original = File(directory, "original.wav")
        val stemOne = File(directory, "stem-one.wav")
        val stemTwo = File(directory, "stem-two.wav")
        writeSineWave(original, atomicDurationMs, 220.0)
        writeSineWave(stemOne, atomicDurationMs, 330.0)
        writeSineWave(stemTwo, atomicDurationMs, 440.0)
        val song = Song(
            id = "atomic-loop-latency",
            videoId = "atomic-loop-latency",
            title = "Atomic loop latency",
            artist = "Stemwise",
            durationMs = atomicDurationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "A",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, atomicDurationMs),
            stemAsset("drums", stemTwo, atomicDurationMs),
            stemAsset("guitar", stemOne, atomicDurationMs),
            remainingAsset(stemTwo, atomicDurationMs, 440.0),
            stemAsset("piano", stemOne, atomicDurationMs),
            stemAsset("vocals", stemTwo, atomicDurationMs)
        )

        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.songId == song.id && playback.state.value.playing
        }
        val lease = checkNotNull(playback.acquireControlLease(song.id))
        onMain { playback.pause() }
        waitUntil(4_000L) { !playback.state.value.playing }
        val cachePreparationStartedAt = SystemClock.elapsedRealtime()
        val cachePreparationDeadline = cachePreparationStartedAt + 60_000L
        val activation = try {
            runBlocking {
                withTimeout(
                    (cachePreparationDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                ) {
                    withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Stem activation exceeded the bounded cache-preparation window; " +
                    "elapsed=${SystemClock.elapsedRealtime() - cachePreparationStartedAt}ms " +
                    "state=${playback.state.value} " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()} " +
                    "graph=${playback.graphDiagnostics()}",
                failure
            )
        }
        assertTrue(
            "All-lane cache preparation failed: $activation; " +
                "state=${playback.state.value}; " +
                "native=${playback.nativeDiagnosticsSnapshotForTest()}; " +
                "graph=${playback.graphDiagnostics()}",
            activation.isSuccess
        )
        val activationCompletedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < cachePreparationDeadline) {
            val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
            if (playback.state.value.loopAvailable &&
                diagnostics?.nativeRenderReadiness?.currentSongCacheReadyForSelectedOwner == true) {
                break
            }
            SystemClock.sleep(10L)
        }
        val preparedDiagnostics = playback.nativeDiagnosticsSnapshotForTest()
        assertTrue(
            "All-lane cache did not seal within 60000ms; " +
                "state=${playback.state.value}; native=$preparedDiagnostics; " +
                "graph=${playback.graphDiagnostics()}",
            playback.state.value.loopAvailable &&
                preparedDiagnostics?.nativeRenderReadiness
                    ?.currentSongCacheReadyForSelectedOwner == true
        )
        println(
            "Atomic loop setup: activation=${activationCompletedAt - cachePreparationStartedAt}ms " +
                "cacheSeal=${SystemClock.elapsedRealtime() - cachePreparationStartedAt}ms"
        )
        onMain {
            playback.seekTo(4_000L)
            playback.play()
        }
        waitUntil(8_000L) {
            playback.state.value.let {
                it.songId == song.id && !it.loading && it.playing &&
                    it.positionMs in 4_000L..12_000L && it.loopAvailable
            }
        }

        data class PresentedSample(
            val generation: Long,
            val selectionToken: Long,
            val fingerprintSequence: Long,
            val callbackSequence: Long,
            val allocatedCallbackCount: Long,
            val snapshotReturnedAtMicros: Long,
            val timelineFrame: Long,
            val outputFrame: Long,
            val pcmFrames: Long,
            val outputBufferFrames: Long,
            val emittedLoopRevision: Long,
            val adoptedLoopRevision: Long,
            val inputToAtomicMicros: Long,
            val inputToCallbackMicros: Long
        )

        fun presentedSample(): PresentedSample {
            val observation = requireNotNull(playback.debug.loopBoundaryObservation())
            val position = requireNotNull(observation.first)
            val output = requireNotNull(observation.second)
            return PresentedSample(
                generation = position.ownership.generation,
                selectionToken = position.ownership.selectionToken,
                fingerprintSequence = position.fingerprintSequence,
                callbackSequence = position.callbackSequence,
                allocatedCallbackCount = output.callbackCount,
                snapshotReturnedAtMicros = System.nanoTime() / 1_000L,
                timelineFrame = position.lastTimelineFrame,
                outputFrame = position.lastOutputFrame,
                pcmFrames = position.pcmFrames,
                outputBufferFrames = output.outputBufferSizeFrames.toLong(),
                emittedLoopRevision = position.loopPolicyRevision,
                adoptedLoopRevision = position.loopPolicyAdoptedRevision,
                inputToAtomicMicros =
                    position.loopPolicyInputToAtomicPublicationLatencyMicros,
                inputToCallbackMicros =
                    position.loopPolicyInputToCallbackAdoptionLatencyMicros
            )
        }

        fun assertTransportRunning(
            label: String,
            expectedOwner: PresentedSample,
            expectedSourceMode: PlaybackSourceMode,
            baselineUnderruns: Long,
            baselineXRuns: Long
        ) {
            val state = playback.state.value
            val observation = playback.debug.loopBoundaryObservation()
            val output = observation?.second
            val owner = observation?.first?.ownership
            val healthy = state.playWhenReady && state.playing && !state.ended && state.error == null &&
                    state.sourceMode == expectedSourceMode &&
                    when (expectedSourceMode) {
                        PlaybackSourceMode.ORIGINAL ->
                            state.sourceSwitchState is SourceSwitchState.Original
                        PlaybackSourceMode.STEMS ->
                            state.sourceSwitchState is SourceSwitchState.Stems
                    } &&
                    owner?.let {
                        it.generation == expectedOwner.generation &&
                            it.selectionToken == expectedOwner.selectionToken
                    } == true &&
                    output?.playing == true && output.effectiveRenderPlaying &&
                    output.processUnderrunCallbackCount == baselineUnderruns &&
                    output.processXRunCount == baselineXRuns
            if (!healthy) fail("$label interrupted transport or changed owner: " +
                "state=$state owner=$owner output=$output")
        }

        var nextBoundaryPollMs = 10L
        var placementSamples = 0L
        var placementMissingSnapshots = 0L
        var placementRevisionMismatches = 0L
        var placementStoppedOutputs = 0L
        var placementNegativeAcceptedAhead = 0L
        var placementInsideImmutablePrefix = 0L
        var placementBoundaryNotQueued = 0L
        var placementObservedRevision = -1L
        var placementBoundsRevision = -1L
        var placementFramesUntilBoundary = Long.MIN_VALUE
        var placementSnapshotAgeFrames = -1L
        var placementAcceptedAhead = Long.MIN_VALUE
        var placementMinimumPrefix = Long.MIN_VALUE
        var placementQueuedPrefix = Long.MIN_VALUE
        var placementMainRejections = 0L
        val nearBoundaryHistory = LongArray(16 * 8)
        var nearBoundaryCount = 0L
        fun queuedBoundarySample(
            outgoingBMs: Long,
            outgoingPhysicalRevision: Long
        ): PresentedSample? {
            nextBoundaryPollMs = 10L
            val snapshotStartedAtMicros = System.nanoTime() / 1_000L
            val observation = playback.debug.loopBoundaryObservation()
            val position = observation?.first
            val output = observation?.second
            placementSamples++
            if (position == null || output == null) placementMissingSnapshots++
            if (position != null) {
                placementObservedRevision = position.loopPolicyRevision
                if (position.loopPolicyRevision != outgoingPhysicalRevision) placementRevisionMismatches++
            }
            if (output != null && !output.effectiveRenderPlaying) placementStoppedOutputs++
            if (position != null && output != null &&
                position.loopPolicyRevision == outgoingPhysicalRevision &&
                output.effectiveRenderPlaying) {
                val boundaryFrame = outgoingBMs * 48L
                val framesUntilBoundary = boundaryFrame - position.lastTimelineFrame
                val snapshotAgeFrames =
                    ((System.nanoTime() / 1_000L - snapshotStartedAtMicros) * 48L + 999L) / 1_000L
                val acceptedAhead = output.callbackFrames - position.lastOutputFrame - 1L
                val preparedBlock = app.stemwise.playback.engine.NativeAudioTrackFeeder.BLOCK_FRAMES.toLong()
                val audioTrack = output.backend == NativeAudioBackend.AUDIO_TRACK
                val window = queuedLoopBoundaryWindow(audioTrack, acceptedAhead, output.queuedFrames,
                    output.outputBufferSizeFrames.toLong(), output.framesPerBurst.toLong(), preparedBlock, snapshotAgeFrames)
                val minimumPrefix = window.minimumPrefix
                val queuedPrefix = window.queuedPrefix
                placementBoundsRevision = position.loopPolicyRevision
                placementFramesUntilBoundary = framesUntilBoundary
                placementSnapshotAgeFrames = snapshotAgeFrames
                placementAcceptedAhead = acceptedAhead
                placementMinimumPrefix = minimumPrefix
                placementQueuedPrefix = queuedPrefix
                if (framesUntilBoundary in 0L..(queuedPrefix + 2_048L)) {
                    val offset = (nearBoundaryCount % 16L).toInt() * 8
                    nearBoundaryHistory[offset] = position.lastTimelineFrame
                    nearBoundaryHistory[offset + 1] = position.lastOutputFrame
                    nearBoundaryHistory[offset + 2] = output.callbackFrames
                    nearBoundaryHistory[offset + 3] = framesUntilBoundary
                    nearBoundaryHistory[offset + 4] = minimumPrefix
                    nearBoundaryHistory[offset + 5] = queuedPrefix
                    nearBoundaryHistory[offset + 6] = position.callbackSequence
                    nearBoundaryHistory[offset + 7] = snapshotAgeFrames
                    nearBoundaryCount++
                }
                if (audioTrack && acceptedAhead < 0L) placementNegativeAcceptedAhead++
                if (framesUntilBoundary <= minimumPrefix) placementInsideImmutablePrefix++
                if (framesUntilBoundary > queuedPrefix) placementBoundaryNotQueued++
                // A fixed 10 ms sleep can skip the residual queued-B window and its UI recheck.
                if (framesUntilBoundary > minimumPrefix) {
                    nextBoundaryPollMs = ((framesUntilBoundary - queuedPrefix) / 48L)
                        .coerceIn(1L, 10L)
                }
                if (window.contains(framesUntilBoundary)) {
                    return PresentedSample(
                        generation = position.ownership.generation,
                        selectionToken = position.ownership.selectionToken,
                        fingerprintSequence = position.fingerprintSequence,
                        callbackSequence = position.callbackSequence,
                        allocatedCallbackCount = output.callbackCount,
                        snapshotReturnedAtMicros = System.nanoTime() / 1_000L,
                        timelineFrame = position.lastTimelineFrame,
                        outputFrame = position.lastOutputFrame,
                        pcmFrames = position.pcmFrames,
                        outputBufferFrames = output.outputBufferSizeFrames.toLong(),
                        emittedLoopRevision = position.loopPolicyRevision,
                        adoptedLoopRevision = position.loopPolicyAdoptedRevision,
                        inputToAtomicMicros =
                            position.loopPolicyInputToAtomicPublicationLatencyMicros,
                        inputToCallbackMicros =
                            position.loopPolicyInputToCallbackAdoptionLatencyMicros
                    )
                }
            }
            return null
        }

        fun awaitQueuedBoundary(
            outgoingBMs: Long,
            outgoingPhysicalRevision: Long,
            deadline: Long
        ): PresentedSample {
            placementSamples = 0L
            placementMissingSnapshots = 0L
            placementRevisionMismatches = 0L
            placementStoppedOutputs = 0L
            placementNegativeAcceptedAhead = 0L
            placementInsideImmutablePrefix = 0L
            placementBoundaryNotQueued = 0L
            placementObservedRevision = -1L
            placementBoundsRevision = -1L
            placementFramesUntilBoundary = Long.MIN_VALUE
            placementSnapshotAgeFrames = -1L
            placementAcceptedAhead = Long.MIN_VALUE
            placementMinimumPrefix = Long.MIN_VALUE
            placementQueuedPrefix = Long.MIN_VALUE
            nearBoundaryCount = 0L
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue("Boundary approach stopped logical transport", playback.state.value.let {
                    it.playWhenReady && it.playing && !it.ended && it.error == null
                })
                queuedBoundarySample(outgoingBMs, outgoingPhysicalRevision)?.let { return it }
                SystemClock.sleep(nextBoundaryPollMs)
            }
            throw AssertionError(
                "Could not place the live callback before an already-queued B=$outgoingBMs; " +
                    "expectedPhysicalRevision=$outgoingPhysicalRevision observedPhysicalRevision=$placementObservedRevision " +
                    "boundsPhysicalRevision=$placementBoundsRevision framesUntilB=$placementFramesUntilBoundary " +
                    "snapshotAgeFrames=$placementSnapshotAgeFrames acceptedAhead=$placementAcceptedAhead " +
                    "minimumPrefix=$placementMinimumPrefix queuedPrefix=$placementQueuedPrefix " +
                    "samples=$placementSamples missing=$placementMissingSnapshots revisionMismatch=$placementRevisionMismatches " +
                    "stopped=$placementStoppedOutputs negativeAcceptedAhead=$placementNegativeAcceptedAhead " +
                    "insideImmutablePrefix=$placementInsideImmutablePrefix boundaryNotQueued=$placementBoundaryNotQueued; " +
                    "mainRejections=$placementMainRejections nearB[timeline,output,accepted,until,min,max,callback,age]=" +
                    (maxOf(0L, nearBoundaryCount - 16L) until nearBoundaryCount).joinToString { sample ->
                        val offset = (sample % 16L).toInt() * 8
                        (0 until 8).joinToString(prefix = "[", postfix = "]") { field ->
                            nearBoundaryHistory[offset + field].toString()
                        }
                    } + "; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}"
            )
        }

        fun burstAndMeasure(
            label: String,
            outgoingBMs: Long,
            updates: List<Pair<Long, Long>>,
            expectedSourceMode: PlaybackSourceMode,
            lateBContraction: Boolean = false
        ): Pair<Long, Long> {
            val qualifiedDiagnostics = requireNotNull(playback.adapter.sourceWaveformDiagnosticsSnapshot())
            val qualifiedOutput = requireNotNull(playback.debug.loopBoundaryObservation()?.second)
            val adoptionBudgetMicros = consumerAdoptionBudgetMicros(qualifiedOutput)
            assertTrue("$label boundary placement requires actual 1x playback",
                qualifiedDiagnostics.runtime.audibleControl?.actualTempo == 1f)
            if (qualifiedOutput.backend == NativeAudioBackend.AUDIO_TRACK) {
                // The measured adapter buffer differs from the 512-frame source block/threshold.
                assertTrue("$label AudioTrack configuration differs from the measured buffer: $qualifiedOutput",
                    qualifiedOutput.outputConfigurationObserved && qualifiedOutput.sampleRateHz == 48_000 &&
                        qualifiedOutput.channelCount == 2 && qualifiedOutput.outputBufferSizeFrames ==
                            app.stemwise.playback.engine.AndroidAudioTrackSink.DEFAULT_BUFFER_FRAMES)
            } else {
                assertTrue(
                    "$label device FIFO exceeds the bounded two-burst policy: $qualifiedOutput",
                    qualifiedOutput.framesPerBurst > 0 && qualifiedOutput.outputBufferSizeFrames.toLong() in
                        1L..(qualifiedOutput.framesPerBurst.toLong() * 2L)
                )
            }
            if (qualifiedOutput.backend != NativeAudioBackend.AUDIO_TRACK) assertTrue(
                "$label device FIFO exceeds 10.67ms: $qualifiedOutput",
                qualifiedOutput.outputBufferSizeFrames.toLong() * 1_000_000L <=
                    qualifiedOutput.sampleRateHz.toLong() * 10_670L
            )
            val previousSample = presentedSample()
            val previousRevision = previousSample.adoptedLoopRevision
            val outgoingPhysicalRevision = previousSample.emittedLoopRevision
            require(outgoingPhysicalRevision > 0L)
            val journalBaseline = playback.presentationJournalForTest(0L)
            val journalAfterSequence = (journalBaseline.writeSequence - 1L).coerceAtLeast(0L)
            val placementDeadline = SystemClock.elapsedRealtime() + 4_000L
            placementMainRejections = 0L
            var queuedBoundary = awaitQueuedBoundary(outgoingBMs, outgoingPhysicalRevision, placementDeadline)
            val baselineOutput = requireNotNull(playback.debug.loopBoundaryObservation()?.second)
            val baselineUnderruns = baselineOutput.processUnderrunCallbackCount
            val baselineXRuns = baselineOutput.processXRunCount
            val expectedRevision = previousRevision + updates.size
            var expected = updates.last()
            var scheduledCutoverFrame = outgoingBMs * 48L
            var expectedContinuationFrame = scheduledCutoverFrame
            var lateBFramesUntilBoundary = 0L
            var lateBObservedCallbackSequence = 0L
            var lateBAllocatedCallbackCeiling = 0L
            var lateBSnapshotReturnedAtMicros = 0L
            var lateBInputEventMicros = 0L
            val commandDurationsMicros = LongArray(updates.size)
            var published = false
            while (!published && SystemClock.elapsedRealtime() < placementDeadline) {
                onMain {
                    val confirmed = queuedBoundarySample(outgoingBMs, outgoingPhysicalRevision)
                        ?: run { placementMainRejections++; return@onMain }
                    queuedBoundary = confirmed
                    val root = application.services.libraryRootManager.state.value
                    val debug = playback.debug
                    val previousBeforeWrite = debug.beforeSettingsWrite
                    var settingsWriteCount = 0
                    debug.beforeSettingsWrite = {
                        settingsWriteCount++
                        previousBeforeWrite?.invoke()
                    }
                    try {
                        val burstStartedAtMicros = System.nanoTime() / 1_000L
                        android.os.Trace.beginSection("StemWise.loop.$label")
                        try {
                            updates.forEachIndexed { index, requested ->
                                var transportFrame = queuedBoundary.timelineFrame
                                val effective = if (lateBContraction && index == updates.lastIndex) {
                                    val beforeFinal = presentedSample()
                                    lateBObservedCallbackSequence = beforeFinal.callbackSequence
                                    lateBAllocatedCallbackCeiling = beforeFinal.allocatedCallbackCount
                                    lateBSnapshotReturnedAtMicros = beforeFinal.snapshotReturnedAtMicros
                                    require(lateBAllocatedCallbackCeiling >= lateBObservedCallbackSequence) {
                                        "Core allocation counter precedes its observed presentation: $beforeFinal"
                                    }
                                    // One snapshot: another full JNI read can age a boundary only 1 ms ahead
                                    // before this test even publishes its timestamped input.
                                    val bufferFrames = beforeFinal.outputBufferFrames
                                    val newBFrame =
                                        ((beforeFinal.timelineFrame + bufferFrames / 2L) / 48L) * 48L
                                    lateBFramesUntilBoundary = newBFrame - beforeFinal.timelineFrame
                                    require(lateBFramesUntilBoundary in 1L until bufferFrames) {
                                        "Late B was not inside the current output buffer: " +
                                            "delta=$lateBFramesUntilBoundary buffer=$bufferFrames"
                                    }
                                    val newBMs = newBFrame / 48L
                                    require(newBMs < outgoingBMs && newBMs - requested.first >= 100L) {
                                        "Late B contraction is invalid: A=${requested.first} B=$newBMs " +
                                            "outgoingB=$outgoingBMs"
                                    }
                                    scheduledCutoverFrame = newBFrame
                                    expectedContinuationFrame = requested.first * 48L
                                    transportFrame = beforeFinal.timelineFrame
                                    requested.first to newBMs
                                } else {
                                    requested
                                }
                                if (index == updates.lastIndex) {
                                    expected = effective
                                }
                                val inputEventMicros = SystemClock.uptimeMillis() * 1_000L
                                if (lateBContraction && index == updates.lastIndex) lateBInputEventMicros = inputEventMicros
                                val commandStartedAtMicros = System.nanoTime() / 1_000L
                                val accepted = playback.setLoop(
                                    effective.first,
                                    effective.second,
                                    lease,
                                    transportFrame / 48L,
                                    publishPresentation = false,
                                    inputEventAtMonotonicMicros = inputEventMicros,
                                    // A held B can cross the callback between sampling and publication.
                                    // Match PlayerScreen so native capture retains that crossing.
                                    heldBGestureId = if (lateBContraction) 1L else 0L
                                )
                                commandDurationsMicros[index] = System.nanoTime() / 1_000L - commandStartedAtMicros
                                assertTrue(accepted)
                                assertTrue("Loop burst stopped playback", playback.state.value.playing)
                            }
                        } finally { android.os.Trace.endSection() }
                        val burstElapsedMicros = System.nanoTime() / 1_000L - burstStartedAtMicros
                        assertTrue("$label command burst exceeded its 10 ms placement budget; " +
                            "elapsedUs=$burstElapsedMicros commandsUs=${commandDurationsMicros.contentToString()} " +
                            "settingsWrites=$settingsWriteCount rootReady=${root.ready} " +
                            "libraryId=${root.libraryId} tree=${root.treeUri} epoch=${root.epoch}",
                            burstElapsedMicros < 10_000L)
                        assertEquals("$label MOVE samples queued song settings writes", 0, settingsWriteCount)
                        // UP publishes the accepted endpoints once, separately from MOVE placement.
                        assertTrue("$label terminal presentation was rejected",
                            playback.publishLoopPresentation(expected.first, expected.second, lease))
                        assertEquals("$label terminal settings write count", if (root.ready) 1 else 0,
                            settingsWriteCount)
                        published = true
                    } finally {
                        debug.beforeSettingsWrite = previousBeforeWrite
                    }
                }
                if (!published) queuedBoundary = awaitQueuedBoundary(outgoingBMs, outgoingPhysicalRevision, placementDeadline)
            }
            assertTrue("$label could not retain a queued-B placement until main-thread input", published)

            // Poll slowly enough to avoid repeatedly waking readiness work. The journal proves
            // every intervening frame; native timestamps separate publication from consumer adoption.
            if (lateBContraction) SystemClock.sleep(8L)
            val cutoverDeadline = SystemClock.elapsedRealtime() + 4_000L
            var adopted: PresentedSample? = null
            var firstEmission: PresentedSample? = null
            var lastOutgoing = queuedBoundary
            while (SystemClock.elapsedRealtime() < cutoverDeadline &&
                (adopted == null || firstEmission == null)
            ) {
                assertTransportRunning(
                    "$label policy cutover",
                    queuedBoundary,
                    expectedSourceMode,
                    baselineUnderruns,
                    baselineXRuns
                )
                val sample = presentedSample()
                if (adopted == null && sample.adoptedLoopRevision == expectedRevision) {
                    adopted = sample
                }
                if (firstEmission == null &&
                    sample.adoptedLoopRevision == expectedRevision &&
                    sample.emittedLoopRevision > 0L &&
                    sample.emittedLoopRevision != outgoingPhysicalRevision
                ) {
                    firstEmission = sample
                } else if (firstEmission == null &&
                    sample.emittedLoopRevision == outgoingPhysicalRevision
                ) {
                    val withinBoundary = sample.timelineFrame >= lastOutgoing.timelineFrame &&
                        (sample.timelineFrame < scheduledCutoverFrame ||
                            lateBContraction && sample.callbackSequence in
                                lateBObservedCallbackSequence..lateBAllocatedCallbackCeiling + 1L)
                    assertTrue(
                        "$label sought early or escaped cutover B/C=$scheduledCutoverFrame; " +
                            "previous=$lastOutgoing current=$sample" +
                            if (withinBoundary) "" else
                                "; loop=${playback.nativeDiagnosticsSnapshotForTest()?.output?.loop}",
                        withinBoundary
                    )
                    if (sample.callbackSequence > lastOutgoing.callbackSequence) {
                        lastOutgoing = sample
                    }
                }
                SystemClock.sleep(10L)
            }
            val exactAdoption = requireNotNull(adopted) {
                "$label callback did not adopt exact final revision $expectedRevision; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}"
            }
            val exactFirstEmission = requireNotNull(firstEmission) {
                "$label callback did not emit PCM stamped with exact final revision " +
                    "$expectedRevision; native=${playback.nativeDiagnosticsSnapshotForTest()}"
            }
            assertTrue(
                "$label input-to-atomic publication was " +
                    "${exactAdoption.inputToAtomicMicros}us",
                exactAdoption.inputToAtomicMicros in 1L until 10_000L
            )
            assertTrue(
                "$label input-to-callback adoption was " +
                    "${exactAdoption.inputToCallbackMicros}us (budget=${adoptionBudgetMicros}us); " +
                    "atomic=${exactAdoption.inputToAtomicMicros}us; " +
                    "loop=${playback.nativeDiagnosticsSnapshotForTest()?.output?.loop}",
                exactAdoption.inputToCallbackMicros in 1L until adoptionBudgetMicros
            )
            val journal = playback.presentationJournalForTest(journalAfterSequence)
            assertTrue("$label presentation journal was incoherent: $journal", journal.coherent)
            assertEquals("$label presentation journal overflowed", 0L, journal.overflowCount)
            assertEquals(
                "$label presentation journal dropped a callback span",
                journalBaseline.writerCollisionCount,
                journal.writerCollisionCount
            )
            assertEquals(
                "$label presentation journal rejected a callback span",
                journalBaseline.invalidPublicationCount,
                journal.invalidPublicationCount
            )
            fun matches(sample: PresentedSample, index: Int): Boolean =
                journal.entries[index].let { span ->
                    span.generation == sample.generation &&
                        span.selectionToken == sample.selectionToken &&
                    span.lastFingerprintSequence == sample.fingerprintSequence &&
                    span.callbackSequence == sample.callbackSequence &&
                    span.callbackPcmFrames == sample.pcmFrames &&
                    span.lastLoopPolicyRevision == sample.emittedLoopRevision &&
                    span.lastTimelineFrame == sample.timelineFrame
                }
            val outgoingIndex = journal.entries.indices.firstOrNull {
                matches(lastOutgoing, it)
            } ?: -1
            val firstEmissionIndex = journal.entries.indices.firstOrNull {
                matches(exactFirstEmission, it)
            } ?: -1
            assertTrue(
                "$label did not retain the sampled outgoing callback in its native journal",
                outgoingIndex >= 0
            )
            assertTrue(
                "$label did not retain the sampled new callback after the outgoing callback",
                firstEmissionIndex > outgoingIndex
            )
            val crossedSpans = journal.entries.subList(outgoingIndex + 1, firstEmissionIndex + 1)
            val lateBInFlightIndex = if (lateBContraction) crossedSpans.indexOfLast {
                it.lastLoopPolicyRevision == outgoingPhysicalRevision &&
                    it.lastTimelineFrame >= scheduledCutoverFrame
            } else -1
            val lateBStaleCallbackSequence = when {
                lateBInFlightIndex >= 0 -> crossedSpans[lateBInFlightIndex].callbackSequence
                lateBContraction && lastOutgoing.timelineFrame >= scheduledCutoverFrame -> lastOutgoing.callbackSequence
                else -> 0L
            }
            if (lateBStaleCallbackSequence != 0L) {
                // Accepted tails and a held push block can already be immutable before input.
                // Only tickets beyond the same-snapshot Core ceiling use the one-callback allowance.
                val allocationContext = "observed=$lateBObservedCallbackSequence allocated=$lateBAllocatedCallbackCeiling " +
                    "latestStale=$lateBStaleCallbackSequence snapshotReturnedUs=$lateBSnapshotReturnedAtMicros inputUs=$lateBInputEventMicros"
                assertTrue(
                    "$label journal found more than the sole allowed post-input stale callback; $allocationContext",
                    lateBStaleCallbackSequence in lateBObservedCallbackSequence..lateBAllocatedCallbackCeiling + 1L
                )
                assertTrue(
                    "$label journal retained a second post-input stale callback; $allocationContext spans=$crossedSpans",
                    crossedSpans.filter { it.lastLoopPolicyRevision == outgoingPhysicalRevision &&
                        it.lastTimelineFrame >= scheduledCutoverFrame }.all {
                        it.callbackSequence in lateBObservedCallbackSequence..lateBAllocatedCallbackCeiling + 1L
                    }
                )
            }
            val crossedLateBOnPotentiallyInFlightCallback =
                lateBContraction &&
                    (lastOutgoing.timelineFrame >= scheduledCutoverFrame || lateBInFlightIndex >= 0)
            if (lateBContraction && lastOutgoing.timelineFrame >= scheduledCutoverFrame) {
                assertTrue(
                    "$label sampled more than the allowed post-input stale callback: $lastOutgoing",
                    lastOutgoing.callbackSequence in lateBObservedCallbackSequence..lateBAllocatedCallbackCeiling + 1L
                )
                assertEquals(
                    "$label retained a continuity fault in the allowed late-B callback",
                    0L,
                    journal.entries[outgoingIndex].continuityViolationCount
                )
            }
            assertTrue(
                "$label changed owner while crossing B/C=$scheduledCutoverFrame: $crossedSpans",
                crossedSpans.all {
                    it.generation == queuedBoundary.generation &&
                        it.selectionToken == queuedBoundary.selectionToken
                }
            )
            assertTrue(
                "$label journal hid an interior continuity violation: $crossedSpans",
                crossedSpans.all { it.continuityViolationCount == 0L }
            )
            val emittedFrames = crossedSpans.sumOf { it.pcmFrames }
            assertEquals(
                "$label journal did not cover every physical output frame across B/C",
                exactFirstEmission.outputFrame - lastOutgoing.outputFrame,
                emittedFrames
            )
            val outgoingFrames = if (lateBInFlightIndex >= 0) {
                crossedSpans.take(lateBInFlightIndex + 1).sumOf { it.pcmFrames }
            } else {
                (scheduledCutoverFrame - lastOutgoing.timelineFrame - 1L).coerceAtLeast(0L)
            }
            var framesThroughSpan = 0L
            var boundarySpanCount = 0
            crossedSpans.forEachIndexed { index, span ->
                val framesBeforeSpan = framesThroughSpan
                framesThroughSpan += span.pcmFrames
                assertTrue(
                    "$label emitted one span across B/C=$scheduledCutoverFrame: " +
                        "outgoingFrames=$outgoingFrames spans=$crossedSpans",
                    framesThroughSpan <= outgoingFrames || framesBeforeSpan >= outgoingFrames
                )
                if (framesThroughSpan <= outgoingFrames) {
                    boundarySpanCount = index + 1
                    assertEquals(outgoingPhysicalRevision, span.firstLoopPolicyRevision)
                    assertEquals(outgoingPhysicalRevision, span.lastLoopPolicyRevision)
                    assertEquals(
                        lastOutgoing.timelineFrame + framesBeforeSpan + 1L,
                        span.firstTimelineFrame
                    )
                    assertEquals(
                        lastOutgoing.timelineFrame + framesThroughSpan,
                        span.lastTimelineFrame
                    )
                }
            }
            assertTrue(
                "$label journal had no exact span edge at B/C=$scheduledCutoverFrame: " +
                    "outgoingFrames=$outgoingFrames spans=$crossedSpans",
                outgoingFrames == 0L || framesThroughSpan >= outgoingFrames &&
                    crossedSpans.take(boundarySpanCount).sumOf { it.pcmFrames } == outgoingFrames
            )
            val continuationFrames = emittedFrames - outgoingFrames
            val finalAFrame = expected.first * 48L
            val finalBFrame = expected.second * 48L
            val finalLoopFrames = finalBFrame - finalAFrame
            assertTrue(
                "$label produced no final-policy PCM after B/C=$scheduledCutoverFrame",
                continuationFrames > 0L
            )
            assertTrue(
                "$label continuation $expectedContinuationFrame is outside final A/B=$expected",
                expectedContinuationFrame in finalAFrame until finalBFrame
            )
            val finalPhysicalRevision = exactFirstEmission.emittedLoopRevision
            val finalSpans = crossedSpans.drop(boundarySpanCount)
            assertTrue("$label retained no final-policy journal span", finalSpans.isNotEmpty())
            var expectedFirstTimelineFrame = expectedContinuationFrame
            finalSpans.forEach { span ->
                assertEquals(finalPhysicalRevision, span.firstLoopPolicyRevision)
                assertEquals(finalPhysicalRevision, span.lastLoopPolicyRevision)
                assertEquals(expectedFirstTimelineFrame, span.firstTimelineFrame)
                val expectedLastTimelineFrame = finalAFrame +
                    ((expectedFirstTimelineFrame - finalAFrame + span.pcmFrames - 1L) %
                        finalLoopFrames)
                assertEquals(expectedLastTimelineFrame, span.lastTimelineFrame)
                expectedFirstTimelineFrame = finalAFrame +
                    ((expectedLastTimelineFrame - finalAFrame + 1L) % finalLoopFrames)
            }
            val expectedFinalTimelineFrame = finalAFrame +
                ((expectedContinuationFrame - finalAFrame + continuationFrames - 1L) %
                    finalLoopFrames)
            assertTrue(
                "$label did not cut over exactly at B/C=$scheduledCutoverFrame to " +
                    "continuation=$expectedContinuationFrame; lastOutgoing=$lastOutgoing " +
                    "first=$exactFirstEmission native=${playback.nativeDiagnosticsSnapshotForTest()}",
                (lastOutgoing.timelineFrame < scheduledCutoverFrame ||
                    crossedLateBOnPotentiallyInFlightCallback) &&
                    exactFirstEmission.timelineFrame == expectedFinalTimelineFrame &&
                    exactFirstEmission.generation == queuedBoundary.generation &&
                    exactFirstEmission.selectionToken == queuedBoundary.selectionToken
            )
            val followingDeadline = SystemClock.elapsedRealtime() + 1_000L
            var following = exactFirstEmission
            while (following.callbackSequence <= exactFirstEmission.callbackSequence &&
                SystemClock.elapsedRealtime() < followingDeadline
            ) {
                following = presentedSample()
                SystemClock.sleep(10L)
            }
            assertTrue(
                "$label did not retain final revision $expectedRevision on the next callback: " +
                    following,
                following.callbackSequence > exactFirstEmission.callbackSequence &&
                    following.emittedLoopRevision == exactFirstEmission.emittedLoopRevision &&
                    following.generation == exactFirstEmission.generation &&
                    following.selectionToken == exactFirstEmission.selectionToken
            )
            if (lateBStaleCallbackSequence != 0L) {
                // A push callback can be presented in several prefixes. Check the first distinct
                // callback, not the next journal publication from the same prepared block.
                val followingJournal = playback.presentationJournalForTest(journalAfterSequence)
                assertTrue("$label following journal was incoherent", followingJournal.coherent)
                assertEquals(0L, followingJournal.overflowCount)
                assertEquals(journalBaseline.writerCollisionCount, followingJournal.writerCollisionCount)
                assertEquals(journalBaseline.invalidPublicationCount, followingJournal.invalidPublicationCount)
                val subsequentSpans = followingJournal.entries.filter {
                    it.sequence > journal.entries[outgoingIndex].sequence && it.callbackSequence > lateBStaleCallbackSequence
                }
                assertTrue("$label retained no distinct callback after the allowed stale callback", subsequentSpans.isNotEmpty())
                assertEquals("$label skipped the callback immediately after the allowed stale callback",
                    lateBStaleCallbackSequence + 1L, subsequentSpans.first().callbackSequence)
                assertTrue("$label did not retain the exact final policy after the sole stale callback: $subsequentSpans",
                    subsequentSpans.all { it.generation == queuedBoundary.generation &&
                        it.selectionToken == queuedBoundary.selectionToken &&
                        it.firstLoopPolicyRevision == finalPhysicalRevision && it.lastLoopPolicyRevision == finalPhysicalRevision &&
                        it.continuityViolationCount == 0L })
            }
            if (lateBContraction) assertTrue(
                "$label did not publish B inside the current output buffer",
                lateBFramesUntilBoundary > 0L
            )

            assertTrue(
                "Callback adopted revision ${exactAdoption.adoptedLoopRevision}, " +
                    "expected final burst revision $expectedRevision",
                exactAdoption.adoptedLoopRevision == expectedRevision
            )
            assertTrue("Loop burst did not retain exact final A/B", playback.state.value.let {
                it.loopA == expected.first && it.loopB == expected.second
            })
            val controls = playback.graphDiagnostics()
            assertTrue(
                "Loop burst did not retain exact requested A/B: $controls",
                controls.requestedLoopStartMs == expected.first &&
                    controls.requestedLoopEndMs == expected.second
            )
            assertTrue("Loop burst stopped playback", playback.state.value.let {
                it.playWhenReady && it.playing && !it.ended && it.error == null
            })
            assertTransportRunning(
                "$label final",
                queuedBoundary,
                expectedSourceMode,
                baselineUnderruns,
                baselineXRuns
            )
            return exactAdoption.inputToAtomicMicros to exactAdoption.inputToCallbackMicros
        }

        fun proveCallerPositionWinsAheadCallback() {
            val before = presentedSample()
            val previousPhysicalRevision = before.emittedLoopRevision
            val callbackPositionMs = before.timelineFrame / 48L
            val newBMs = callbackPositionMs - 100L
            val newAMs = newBMs - 400L
            val callerPositionMs = (newAMs + newBMs) / 2L
            require(newAMs >= 0L && newBMs - newAMs >= 100L)
            require(callerPositionMs in newAMs..newBMs)
            require(before.timelineFrame > newBMs * 48L)
            val baselineOutput = requireNotNull(playback.debug.loopBoundaryObservation()?.second)
            val adoptionBudgetMicros = consumerAdoptionBudgetMicros(baselineOutput)
            val expectedRevision = before.adoptedLoopRevision + 1L
            val journalBaseline = playback.presentationJournalForTest(0L)

            onMain {
                assertTrue(
                    playback.setLoop(
                        newAMs,
                        newBMs,
                        lease,
                        callerPositionMs,
                        inputEventAtMonotonicMicros = SystemClock.uptimeMillis() * 1_000L
                    )
                )
            }

            val deadline = SystemClock.elapsedRealtime() + 4_000L
            var adopted: PresentedSample? = null
            var firstEmission: PresentedSample? = null
            while (SystemClock.elapsedRealtime() < deadline &&
                (adopted == null || firstEmission == null)
            ) {
                assertTransportRunning(
                    "caller-inside/callback-ahead contraction",
                    before,
                    PlaybackSourceMode.STEMS,
                    baselineOutput.processUnderrunCallbackCount,
                    baselineOutput.processXRunCount
                )
                val sample = presentedSample()
                if (adopted == null && sample.adoptedLoopRevision == expectedRevision) {
                    adopted = sample
                }
                if (firstEmission == null &&
                    sample.adoptedLoopRevision == expectedRevision &&
                    sample.emittedLoopRevision > 0L &&
                    sample.emittedLoopRevision != previousPhysicalRevision
                ) {
                    firstEmission = sample
                }
                SystemClock.sleep(10L)
            }

            val exactAdoption = requireNotNull(adopted) {
                "Callback did not adopt caller-inside revision $expectedRevision; " +
                    "native=${playback.nativeDiagnosticsSnapshotForTest()}"
            }
            val exactFirstEmission = requireNotNull(firstEmission) {
                "Caller-inside revision $expectedRevision stayed dormant behind the ahead " +
                    "callback cursor; native=${playback.nativeDiagnosticsSnapshotForTest()}"
            }
            assertTrue(
                "Caller-inside input-to-atomic publication was " +
                    "${exactAdoption.inputToAtomicMicros}us",
                exactAdoption.inputToAtomicMicros in 1L until 10_000L
            )
            assertTrue(
                "Caller-inside input-to-callback adoption was " +
                    "${exactAdoption.inputToCallbackMicros}us (budget=${adoptionBudgetMicros}us); " +
                    "atomic=${exactAdoption.inputToAtomicMicros}us; " +
                    "loop=${playback.nativeDiagnosticsSnapshotForTest()?.output?.loop}",
                exactAdoption.inputToCallbackMicros in 1L until adoptionBudgetMicros
            )
            val journal = playback.presentationJournalForTest(journalBaseline.writeSequence)
            assertTrue(
                "Caller-inside presentation journal was incoherent: $journal",
                journal.coherent
            )
            assertEquals("Caller-inside presentation journal overflowed", 0L, journal.overflowCount)
            assertEquals(
                "Caller-inside presentation journal dropped a callback span",
                journalBaseline.writerCollisionCount,
                journal.writerCollisionCount
            )
            assertEquals(
                "Caller-inside presentation journal rejected a callback span",
                journalBaseline.invalidPublicationCount,
                journal.invalidPublicationCount
            )
            val firstNewSpan = journal.entries.firstOrNull {
                it.generation == exactFirstEmission.generation &&
                    it.selectionToken == exactFirstEmission.selectionToken &&
                    it.firstLoopPolicyRevision == exactFirstEmission.emittedLoopRevision &&
                    it.lastLoopPolicyRevision == exactFirstEmission.emittedLoopRevision
            }
            assertTrue(
                "Callback-ahead contraction did not continue exactly at A=$newAMs; " +
                    "before=$before first=$exactFirstEmission journal=$journal",
                firstNewSpan?.let {
                    it.firstTimelineFrame == newAMs * 48L &&
                        it.continuityViolationCount == 0L
                } == true
            )
            assertTrue("Caller-inside contraction did not retain exact A/B", playback.state.value.let {
                it.loopA == newAMs && it.loopB == newBMs
            })
            assertTransportRunning(
                "caller-inside/callback-ahead final",
                before,
                PlaybackSourceMode.STEMS,
                baselineOutput.processUnderrunCallbackCount,
                baselineOutput.processXRunCount
            )
        }

        onMain {
            assertTrue("Could not return to Original before A qualification", playback.switchToOriginal(lease))
        }
        waitUntil(4_000L) {
            playback.state.value.let {
                it.sourceMode == PlaybackSourceMode.ORIGINAL &&
                    it.sourceSwitchState is SourceSwitchState.Original
            }
        }
        // Let the replacement owner present PCM before pausing; a paused new owner has no
        // callback position yet, even after its logical source switch is acknowledged.
        waitUntil(4_000L) {
            playback.nativeDiagnosticsSnapshotForTest()?.runtime?.presentedPosition != null
        }
        onMain { playback.pause() }
        waitUntil(2_000L) { !playback.state.value.playing }
        val pausedCursorMs = presentedSample().timelineFrame / 48L
        val seedA = (pausedCursorMs - 1_000L).coerceAtLeast(1_000L)
        val seedB = (pausedCursorMs + 2_000L).coerceAtMost(atomicDurationMs - 2_000L)
        assertTrue("Insufficient dynamic loop range at $pausedCursorMs", seedB - seedA >= 2_500L)
        val seedPreviousRevision = checkNotNull(
            playback.nativeDiagnosticsSnapshotForTest()?.nativeRenderReadiness
        ).directAtomicPolicyPublishedRevision
        onMain { assertTrue(playback.setLoop(seedA, seedB, lease)) }
        onMain { playback.play() }
        waitUntil(6_000L) {
            val diagnostics = playback.nativeDiagnosticsSnapshotForTest()
            diagnostics?.nativeRenderReadiness?.directAtomicPolicyPublishedRevision
                ?.let { it > seedPreviousRevision } == true &&
                directLoopAdoptedForCurrentOwner() &&
                diagnostics.runtime.presentedPosition?.loopPolicyRevision?.let { it > 0L } == true &&
                diagnostics.output?.effectiveRenderPlaying == true &&
                playback.state.value.let { it.playWhenReady && it.playing }
        }

        val aLatenciesMicros = burstAndMeasure(
            label = "A-ending burst",
            outgoingBMs = seedB,
            updates = listOf(
                seedA to seedB + 100L,
                seedA + 100L to seedB + 100L,
                seedA + 100L to seedB + 200L,
                seedA + 200L to seedB + 200L,
                seedA + 200L to seedB + 300L,
                seedA + 300L to seedB + 300L
            ),
            expectedSourceMode = PlaybackSourceMode.ORIGINAL
        )
        val stemsReactivation = runBlocking {
            withTimeout(8_000L) {
                withContext(Dispatchers.Main) { playback.activateStems(stems, lease) }
            }
        }
        assertTrue("Could not restore Stems before B qualification: $stemsReactivation", stemsReactivation.isSuccess)
        waitUntil(4_000L) {
            playback.state.value.let {
                it.sourceMode == PlaybackSourceMode.STEMS &&
                    it.sourceSwitchState is SourceSwitchState.Stems && it.playing
            }
        }
        val bLatenciesMicros = burstAndMeasure(
            label = "late B-contraction burst",
            outgoingBMs = seedB + 300L,
            // Contract B with A fixed so the prepared A continuation remains applicable.
            // Expansion continues from the old B and is exercised by the preceding burst;
            // reversing that preparation immediately before this crossing tests a different path.
            updates = listOf(
                seedA + 300L to seedB + 299L,
                seedA + 300L to seedB + 298L,
                seedA + 300L to seedB + 297L,
                seedA + 300L to seedB + 296L,
                seedA + 300L to seedB + 295L,
                seedA + 300L to seedB + 294L
            ),
            expectedSourceMode = PlaybackSourceMode.STEMS,
            lateBContraction = true
        )
        proveCallerPositionWinsAheadCallback()
        println(
            "A/B input latency: " +
                "A(atomic=${aLatenciesMicros.first}us callback=${aLatenciesMicros.second}us) " +
                "B(atomic=${bLatenciesMicros.first}us callback=${bLatenciesMicros.second}us)"
        )
        println("Atomic loop qualification total=${SystemClock.elapsedRealtime() - methodStartedAt}ms")
        assertTrue("Loop movement stopped playback", playback.state.value.playing)
    }

    @Test
    fun naturalDirectLoopCyclesNeverStopTransportOrEscapeTheRange() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "loop-qualification").apply { mkdirs() }
        val original = File(directory, "natural-direct-original.wav")
        val stemOne = File(directory, "natural-direct-stem-one.wav")
        val stemTwo = File(directory, "natural-direct-stem-two.wav")
        val durationMs = 12_000L
        writeSineWave(original, durationMs, 247.0)
        writeSineWave(stemOne, durationMs, 367.0)
        writeSineWave(stemTwo, durationMs, 439.0)
        val song = Song(
            id = "natural-direct-loop",
            videoId = "natural-direct-loop",
            title = "Natural direct loop",
            artist = "Stemwise",
            durationMs = durationMs,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "N",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, durationMs),
            stemAsset("drums", stemTwo, durationMs),
            stemAsset("guitar", stemOne, durationMs),
            remainingAsset(stemTwo, durationMs, 439.0),
            stemAsset("piano", stemOne, durationMs),
            stemAsset("vocals", stemTwo, durationMs)
        )

        onMain { playback.startQueue(listOf(song), shuffle = false, autoplay = true) }
        waitUntil(8_000L) {
            playback.state.value.songId == song.id &&
                !playback.state.value.loading &&
                playback.state.value.playWhenReady &&
                playback.state.value.positionMs > 50L
        }
        val controlLease = checkNotNull(playback.acquireControlLease(song.id))
        onMain { playback.pause() }
        waitUntil(4_000L) { !playback.state.value.playing }
        val activation = runBlocking {
            withTimeout(8_000L) {
                withContext(Dispatchers.Main) { playback.activateStems(stems, controlLease) }
            }
        }
        assertTrue("Could not prepare the natural direct-loop fixture: $activation", activation.isSuccess)
        waitUntil(8_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.state.value.loopAvailable
        }
        onMain { assertTrue(playback.switchToOriginal(controlLease)) }
        waitUntil(4_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                playback.state.value.loopAvailable
        }
        val loopStartMs = 2_000L
        val loopEndMs = 8_000L
        lateinit var seekReceipt: NativeAudioPlaybackSeekReceipt
        onMain {
            playback.setTempoAndPitch(2f, 0, controlLease)
            seekReceipt = checkNotNull(playback.seekToWithReceipt(loopStartMs, controlLease))
        }
        // The displayed seek position is optimistic; bind loop setup to this completed receipt.
        waitUntil(4_000L) {
            val session = playback.adapter.snapshot()
            val latest = session.latestRequest
            val committed = session.publication.committed
            if (latest == null || committed == null || latest.selection.token != seekReceipt.token ||
                latest.controls.seekRevision != seekReceipt.seekRevision ||
                committed.selection.token != seekReceipt.token ||
                committed.transport.completedSeekRevision != seekReceipt.seekRevision
            ) false else {
                val owner = committed.owner.ownership
                val native = playback.nativeDiagnosticsSnapshotForTest()
                val ownership = native?.runtime?.ownership
                native != null && ownership != null && latest.exactOwnership == owner &&
                    ownership.requested == owner && ownership.accepted == owner &&
                    ownership.committed == owner && ownership.acceptedFirstFingerprintSequence > 0L &&
                    native.controllerPublicOwnership == owner &&
                    native.controllerPendingSeekOwnership == null &&
                    native.nativeRenderReadiness?.ownership == owner &&
                    native.output?.playing == false && !playback.state.value.playing &&
                    playback.state.value.loopAvailable &&
                    playback.state.value.positionMs in loopStartMs..(loopStartMs + 250L)
            }
        }
        onMain {
            assertTrue(playback.setLoop(loopStartMs, loopEndMs, controlLease))
            playback.play()
        }
        waitUntil(8_000L) { directLoopAdoptedForCurrentOwner() }
        awaitDirectWraps(loopStartMs, loopEndMs, minimumWraps = 3, timeoutMs = 12_000L)
        assertTrue(playback.state.value.playing && playback.state.value.error == null)
    }

    @Test
    fun explicitSeekOutsideLoopContinuesAndExactRangeReentryRearms() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .forEach { it.finish() }
            playback.state.value.songId?.let(playback::unload)
        }
        val directory = File(application.cacheDir, "loop-qualification").apply { mkdirs() }
        val original = File(directory, "outside-reentry-original.wav")
        val successorOriginal = File(directory, "outside-reentry-successor.wav")
        val stemOne = File(directory, "outside-reentry-stem-one.wav")
        val stemTwo = File(directory, "outside-reentry-stem-two.wav")
        writeSineWave(original, 12_000L, 293.0)
        writeSineWave(successorOriginal, 12_000L, 317.0)
        writeSineWave(stemOne, 12_000L, 367.0)
        writeSineWave(stemTwo, 12_000L, 439.0)
        val song = Song(
            id = "outside-loop-reentry",
            videoId = "outside-loop-reentry",
            title = "Outside loop reentry",
            artist = "Stemwise",
            durationMs = 12_000L,
            state = SongState.READY,
            accent = Color.Cyan,
            hasStems = true,
            artworkGlyph = "R",
            sourceHash = PlaybackContentFixture.sourceHash(original),
            sourcePath = PlaybackContentFixture.publish(application, original),
            sourceCodec = "pcm_s16le",
            sourceContainer = "wav"
        )
        val stems = listOf(
            stemAsset("bass", stemOne, 12_000L),
            stemAsset("drums", stemTwo, 12_000L),
            stemAsset("guitar", stemOne, 12_000L),
            remainingAsset(stemTwo, 12_000L, 439.0),
            stemAsset("piano", stemOne, 12_000L),
            stemAsset("vocals", stemTwo, 12_000L)
        )
        val successor = song.copy(
            id = "outside-loop-successor",
            videoId = "outside-loop-successor",
            title = "Outside loop successor",
            sourceHash = PlaybackContentFixture.sourceHash(successorOriginal),
            sourcePath = PlaybackContentFixture.publish(application, successorOriginal)
        )
        val pointA = 2_000L
        val pointB = 4_000L

        onMain {
            playback.setRepeatMode("Off")
            playback.startQueue(listOf(song, successor), shuffle = false, autoplay = true)
        }
        waitUntil(8_000L) {
            playback.state.value.songId == song.id &&
                playback.state.value.playing &&
                playback.state.value.positionMs > 50L
        }
        val controlLease = checkNotNull(playback.acquireControlLease(song.id))
        val activation = runBlocking {
            withTimeout(8_000L) {
                withContext(Dispatchers.Main) { playback.activateStems(stems, controlLease) }
            }
        }
        assertTrue("Could not prepare the seven-lane loop fixture: $activation", activation.isSuccess)
        waitUntil(8_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.STEMS &&
                playback.state.value.loopAvailable
        }
        onMain { assertTrue(playback.switchToOriginal(controlLease)) }
        waitUntil(4_000L) {
            playback.state.value.sourceMode == PlaybackSourceMode.ORIGINAL &&
                playback.state.value.sourceSwitchState is SourceSwitchState.Original &&
                playback.state.value.loopAvailable
        }
        onMain { playback.seekTo(pointA) }
        waitUntil(4_000L) {
            playback.state.value.positionMs in pointA..(pointA + 500L) &&
                playback.state.value.loopAvailable
        }
        onMain { assertTrue(playback.setLoop(pointA, pointB, controlLease)) }
        waitUntil(8_000L) {
            playback.state.value.loopA == pointA && playback.state.value.loopB == pointB &&
                directLoopAdoptedForCurrentOwner()
        }
        // A seek before A must not be interpreted as a cold request to jump back to A. It keeps
        // playing forward with the configured range dormant, then re-arms upon genuine entry.
        onMain { playback.seekTo(500L) }
        waitUntil(1_200L) {
            val state = playback.state.value
            state.positionMs in 650L until pointA && state.playing &&
                state.loopA == pointA && state.loopB == pointB
        }
        waitUntil(3_500L) {
            val state = playback.state.value
            state.positionMs in pointA until pointB &&
                directLoopAdoptedForCurrentOwner()
        }

        // The same contract applies after B: transport stays outside and advances until the user
        // explicitly moves back into the retained A/B range.
        onMain { playback.seekTo(6_000L) }
        waitUntil(1_200L) {
            val state = playback.state.value
            state.positionMs in 6_150L..8_000L && state.playing &&
                state.loopA == pointA && state.loopB == pointB
        }
        onMain { playback.seekTo(2_500L) }
        waitUntil(3_000L) {
            playback.state.value.positionMs in pointA until pointB &&
                directLoopAdoptedForCurrentOwner()
        }
        var previousPosition = playback.state.value.positionMs
        var wrapped = false
        var stopped: AudioPlaybackState? = null
        val wrapDeadline = SystemClock.elapsedRealtime() + 3_500L
        while (!wrapped && SystemClock.elapsedRealtime() < wrapDeadline) {
            val current = playback.state.value
            if (!current.playing || !current.playWhenReady || current.error != null) {
                stopped = current
                break
            }
            wrapped = previousPosition >= pointB - 250L && current.positionMs <= pointA + 350L
            previousPosition = current.positionMs
            SystemClock.sleep(10L)
        }
        assertTrue(
            "Re-entered direct loop did not audibly wrap B to A: stopped=$stopped, " +
                "state=${playback.state.value}, native=${playback.nativeDiagnosticsSnapshotForTest()}",
            wrapped && stopped == null
        )
        assertTrue(playback.state.value.loopA == pointA && playback.state.value.loopB == pointB)

        // A retained physical loop must become dormant immediately when an explicit seek lands
        // after B. Near EOS it must neither re-arm nor fence the natural successor transition.
        val nearEndB = 10_500L
        onMain {
            val accepted = playback.setLoop(pointA, nearEndB, controlLease)
            if (!accepted) fail("Could not retarget the loop after reentry: state=${playback.state.value}, " +
                "native=${playback.nativeDiagnosticsSnapshotForTest()}")
            playback.seekTo(11_000L)
        }
        waitUntil(4_000L) {
            playback.state.value.let { state ->
                state.songId == song.id && state.positionMs >= 11_000L && state.playing
            }
        }
        waitUntil(6_000L) {
            playback.state.value.let { state ->
                state.songId == successor.id && state.playing && state.error == null
            }
        }
    }
}
