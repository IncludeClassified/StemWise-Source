package app.stemwise.playback.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.analysis.projectImportedMetronomeTimeline
import app.stemwise.qualification.PlaybackPerformanceRunListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Default AudioTrack integration; journals/timestamps do not qualify physical onset. */
@RunWith(AndroidJUnit4::class)
@app.stemwise.qualification.ManagedAudioTest
class AudioTrackEngineInstrumentationTest {
    private var observedSpeakerId: Int? = null

    @Test
    fun slowedQuarterNoteGridDeliversOneCompleteClickPerBeatWithoutAccentCollisions() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        val rhythm = JSONObject(application.assets.open("demo_library/DEMOUI00001/song.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
            .getJSONObject("analysis").getJSONObject("rhythm")
        val timeline = projectImportedMetronomeTimeline(rhythm)
        assertEquals(listOf(4, 3), timeline.meterSegments.map { it.numerator })
        val startMs = 7_600L
        val captured = FloatArray(48_000 * 2 * 54 / 10)
        val capturedSamples = AtomicInteger()
        val tempo = .77f
        val gain = .35f
        val expectedBeats = timeline.beats.filter { it.timeMs >= startMs &&
            it.timeMs < startMs + captured.size / 2 / 48.0 * tempo }
        assertEquals(8, expectedBeats.size)
        assertEquals(3, expectedBeats.count { it.isDownbeat })
        withOutputEngineSource(FloatArray(48_000 * 12 * 2), sourceCapacityFrames = 1_048_576,
            sinkFactory = {
                val actual = AndroidAudioTrackSink()
                object : NativeAudioTrackFeeder.Sink by actual {
                    override fun write(pcm: ByteBuffer): Int {
                        val start = pcm.position()
                        val accepted = actual.write(pcm)
                        val offset = capturedSamples.get()
                        val count = minOf((accepted / 4).coerceAtLeast(0), captured.size - offset)
                        for (sample in 0 until count) captured[offset + sample] = pcm.getFloat(start + sample * 4)
                        capturedSamples.set(offset + count)
                        return accepted
                    }
                }
            }) { output, runtime, owner, initial ->
            val baseline = output.diagnostics()
            val command = initial.copy(banks = listOf(initial.banks[0].copy(firstTimelineFrame = startMs * 48L),
                initial.banks[1]), controls = initial.controls.copy(tempo = tempo,
                metronome = NativeRuntimeMetronomeControl(NativeRuntimeMetronomeMode.BEAT_GRID,
                    bpm = 128f * tempo, gain = gain, subdivision = 1, beatsPerBar = 4, beatUnitDenominator = 4)))
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publishBeatGrid(
                NativeRuntimeBeatGridUpdate(1L, NativeRuntimeBank.FIRST, owner,
                    LongArray(timeline.beats.size) { timeline.beats[it].timeMs * 48L },
                    BooleanArray(timeline.beats.size) { timeline.beats[it].isDownbeat })))
            assertTrue(runtime.start())
            val deadline = System.nanoTime() + 8_000_000_000L
            while (capturedSamples.get() < captured.size && System.nanoTime() < deadline) Thread.sleep(5L)
            assertEquals("Real AudioTrack writes did not complete the bounded PCM window", captured.size,
                capturedSamples.get())

            val onsets = mutableListOf<Int>()
            var silentFrames = 48_000
            for (frame in 0 until captured.size / 2) {
                assertEquals(captured[frame * 2], captured[frame * 2 + 1], 0f)
                if (kotlin.math.abs(captured[frame * 2]) > .000001f) {
                    if (silentFrames > 2_400) onsets.add(frame)
                    silentFrames = 0
                } else silentFrames++
            }
            assertEquals("Quarter notes must not add another base/subdivision click under an accent",
                expectedBeats.size, onsets.size)
            for (beat in onsets.indices) {
                val expectedOffset = kotlin.math.round((expectedBeats[beat].timeMs - expectedBeats[0].timeMs) *
                    48.0 / tempo).toInt()
                assertTrue("Click $beat moved or stuttered: $onsets",
                    kotlin.math.abs(onsets[beat] - onsets[0] - expectedOffset) <= 1)
                val expectedPeak = gain * if (expectedBeats[beat].isDownbeat) 1f else .86f
                assertEquals("Coincident voices changed click $beat's first sample", expectedPeak,
                    captured[onsets[beat] * 2], .00001f)
                val matchingBeat = expectedBeats.indexOfFirst { it.isDownbeat == expectedBeats[beat].isDownbeat }
                // Entire accepted 25 ms pulses must survive runtime/DSP/block delivery unchanged.
                for (frame in 0 until 1_200) assertEquals("Click $beat was interrupted at sample $frame",
                    captured[(onsets[matchingBeat] + frame) * 2],
                    captured[(onsets[beat] + frame) * 2], .00001f)
            }
            assertEquals(owner, runtime.diagnostics().presentedPosition?.ownership)
            assertHealthy(output.diagnostics(), baseline)
        }
    }

    @Test
    fun persistent2048FrameBufferPreservesASourceShorterThanTheStartThreshold() {
        exerciseShortSource()
    }

    private fun exerciseShortSource() {
        withEngine(256) { output, runtime, owner, command, sink, sampleHeap ->
            val baseline = output.diagnostics()
            assertEquals(NativeAudioBackend.AUDIO_TRACK, baseline.backend)
            assertEquals(2_048, baseline.outputBufferSizeFrames)
            assertTrue(baseline.performanceMode in 0..1)
            assertEquals(0, baseline.xRunCount)
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            assertTrue(runtime.start())
            await("short-source physical presentation and exact owned end") {
                val state = runtime.diagnostics()
                state.audibleEnd?.ownership == owner && state.presentedPosition?.ownership == owner
            }
            val journal = output.presentationJournal(0)
            assertTrue(journal.coherent)
            assertEquals(0L, journal.overflowCount)
            assertEquals(0L, journal.invalidPublicationCount)
            val spans = journal.entries.filter { it.generation == owner.generation && it.selectionToken == owner.selectionToken }
            assertTrue("Real source PCM must be represented, not only terminal padding", spans.isNotEmpty())
            assertEquals(256L, spans.sumOf { it.pcmFrames })
            assertEquals(0L, spans.first().firstTimelineFrame)
            assertEquals(255L, spans.last().lastTimelineFrame)
            assertEquals(256L, spans.last().lastNextTimelineFrame)
            assertTrue(spans.all { it.continuityViolationCount == 0L })
            assertSpeakerRoute(sink)
            assertHealthy(output.diagnostics(), baseline)
            sampleHeap()
        }
    }

    @Test
    fun persistent2048FrameBufferStartsWarmsAndPhysicallyRestartsWithoutFaults() {
        exerciseRetainedResumes()
    }

    @Test
    fun persistentPauseCpuCostAndRealSuspensionRestartRemainObservable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = requireNotNull(context.getSystemService(android.os.PowerManager::class.java))
        fun temperature(): Int = requireNotNull(context.registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)))
            .getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        withOutputEngineSource(audioTrackEngineSamples(48_000)) { output, runtime, owner, initial ->
            val baseline = output.diagnostics()
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(initial))
            assertTrue(runtime.start())
            await("persistent source before idle measurement") {
                runtime.diagnostics().presentedPosition?.ownership == owner
            }
            val paused = initial.copy(revision = 2L, playing = false)
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(paused))
            await("logical pause on a running physical stream") {
                !output.diagnostics().playing && output.diagnostics().streamState == NativeAudioStreamState.STARTED
            }
            val pauseBoundary = output.diagnostics()
            val drainedQueue = pauseBoundary.callbackFrames + pauseBoundary.outputBufferCapacityFrames +
                NativeAudioTrackFeeder.BLOCK_FRAMES
            // Accepted transport silence must displace the queued source tail before measuring pause.
            await("accepted source tail drains before idle measurement") {
                output.diagnostics().callbackFrames >= drainedQueue
            }
            val source = runtime.diagnostics().presentedPosition
            val warmFrames = output.diagnostics().callbackFrames
            val thermalBefore = power.currentThermalStatus
            val temperatureBefore = temperature()
            val warmCpuBefore = android.os.Process.getElapsedCpuTime()
            val warmBefore = System.nanoTime()
            Thread.sleep(10_000)
            val warmNanos = System.nanoTime() - warmBefore
            val warmCpuMs = android.os.Process.getElapsedCpuTime() - warmCpuBefore
            assertEquals(source, runtime.diagnostics().presentedPosition)
            assertTrue(output.diagnostics().callbackFrames > warmFrames)
            await("real physical suspension acknowledgement") { output.quiesceIfPaused() }
            val stoppedFrames = output.diagnostics().callbackFrames
            val stoppedCpuBefore = android.os.Process.getElapsedCpuTime()
            val stoppedBefore = System.nanoTime()
            Thread.sleep(10_000)
            val stoppedNanos = System.nanoTime() - stoppedBefore
            val stoppedCpuMs = android.os.Process.getElapsedCpuTime() - stoppedCpuBefore
            assertEquals(stoppedFrames, output.diagnostics().callbackFrames)
            Log.i("StemWisePersistentCost", "warmNanos=$warmNanos warmProcessCpuMs=$warmCpuMs " +
                "suspendedNanos=$stoppedNanos suspendedProcessCpuMs=$stoppedCpuMs " +
                "thermalBefore=$thermalBefore thermalAfter=${power.currentThermalStatus} " +
                "batteryTemperatureBefore=$temperatureBefore batteryTemperatureAfter=${temperature()} " +
                "batteryEnergyQualified=false usbConnectedComparison=true")
            val resumedAt = System.nanoTime()
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(paused.copy(revision = 3L, playing = true)))
            await("source progress after true physical suspension") {
                runtime.diagnostics().presentedPosition?.let {
                    it.ownership == owner && it.lastTimelineFrame > requireNotNull(source).lastTimelineFrame
                } == true
            }
            Log.i("StemWisePersistentCost", "restartPresentedEstimateMs=${(System.nanoTime() - resumedAt) / 1e6} " +
                "absolutePhysicalResponseQualified=false")
            val unselected = NativeRuntimeCommandSnapshot(4L, owner.generation + 1L,
                listOf(NativeRuntimeBankIntent.unassigned(2L), NativeRuntimeBankIntent.unassigned(2L)),
                selectedBank = null, playing = false, controls = NativeRuntimeControls(4L))
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(unselected))
            await("clearing the selection ends the persistent physical session") {
                val actual = output.diagnostics()
                actual.appliedGeneration == unselected.outputGeneration &&
                    actual.streamState == NativeAudioStreamState.PAUSED && !actual.playing
            }
            val unselectedFrames = output.diagnostics().callbackFrames
            Thread.sleep(100)
            assertEquals("No song cannot leave an unbounded silent session", unselectedFrames,
                output.diagnostics().callbackFrames)
            assertHealthy(output.diagnostics(), baseline)
        }
    }

    private fun exerciseRetainedResumes() {
        withEngine(48_000) { output, runtime, owner, initial, sink, sampleHeap ->
            val baseline = output.diagnostics()
            assertEquals(2_048, baseline.outputBufferSizeFrames)
            val bufferFrames = baseline.outputBufferSizeFrames
            assertEquals(512, sink.configuration.startThresholdFrames)
            var command = initial
            val freshStartedAt = System.nanoTime()
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            assertTrue(runtime.start())
            await("candidate's real source owner") {
                runtime.diagnostics().presentedPosition?.let { it.ownership == owner && it.lastTimelineFrame >= 2_400L } == true
            }
            assertSpeakerRoute(sink)
            sampleHeap()
            Log.i("StemWiseAudioTrackEngine", "phase=fresh bufferFrames=$bufferFrames " +
                "sourcePresentedEstimateMs=${(System.nanoTime() - freshStartedAt) / 1e6}")
            val initialRun = output.diagnostics().physicalRunSequence
            repeat(3) { resume ->
                command = command.copy(revision = command.revision + 1, playing = false)
                assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
                await("Logical pause leaves the same physical sink running") {
                    val actual = output.diagnostics()
                    !actual.playing && actual.streamState == NativeAudioStreamState.STARTED
                }
                // The accepted source tail can fill the actual platform buffer.
                Thread.sleep(maxOf(30L, (bufferFrames * 1_000L + 47_999L) / 48_000L + 10L))
                val pausedSource = requireNotNull(runtime.diagnostics().presentedPosition)
                val sinkFrames = output.diagnostics().callbackFrames
                Thread.sleep(100)
                assertEquals("Intentional silence cannot advance source", pausedSource,
                    runtime.diagnostics().presentedPosition)
                assertTrue("The physical sink still accepts transport silence",
                    output.diagnostics().callbackFrames > sinkFrames)
                assertEquals(initialRun, output.diagnostics().physicalRunSequence)
                val paused = requireNotNull(runtime.diagnostics().presentedPosition)
                assertEquals(owner, paused.ownership)
                command = command.copy(revision = command.revision + 1, playing = true)
                val resumedAt = System.nanoTime()
                assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
                await("same-owner retained continuation after resume") {
                    runtime.diagnostics().presentedPosition?.let {
                        it.ownership == owner && it.lastTimelineFrame > paused.lastTimelineFrame + 512L
                    } == true
                }
                assertSpeakerRoute(sink)
                assertHealthy(output.diagnostics(), baseline)
                sampleHeap()
                Log.i("StemWiseAudioTrackEngine", "phase=resume-$resume bufferFrames=$bufferFrames " +
                    "sourcePresentedEstimateMs=${(System.nanoTime() - resumedAt) / 1e6}")
            }
            command = command.copy(revision = command.revision + 1, playing = false)
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            await("true physical suspension") { !output.diagnostics().playing && output.quiesceIfPaused() }
            val suspendedSource = requireNotNull(runtime.diagnostics().presentedPosition)
            val suspendedCallbacks = output.diagnostics().callbackFrames
            Thread.sleep(100L)
            assertEquals(suspendedCallbacks, output.diagnostics().callbackFrames)
            assertEquals(suspendedSource, runtime.diagnostics().presentedPosition)
            assertHealthy(output.diagnostics(), baseline)
            sampleHeap()
            command = command.copy(revision = command.revision + 1, playing = true)
            val restartedAt = System.nanoTime()
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            await("same-owner source after true physical restart") {
                runtime.diagnostics().presentedPosition?.let {
                    it.ownership == owner && it.lastTimelineFrame > suspendedSource.lastTimelineFrame + 512L
                } == true
            }
            assertSpeakerRoute(sink)
            assertHealthy(output.diagnostics(), baseline)
            sampleHeap()
            Log.i("StemWiseAudioTrackEngine", "phase=physical-restart bufferFrames=$bufferFrames " +
                "sourcePresentedEstimateMs=${(System.nanoTime() - restartedAt) / 1e6}")
            command = command.copy(revision = command.revision + 1, playing = false)
            assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
            await("final candidate pause") { !output.diagnostics().playing && output.quiesceIfPaused() }
            val journal = output.presentationJournal(0)
            assertTrue(journal.coherent)
            assertEquals(0L, journal.overflowCount)
            assertEquals(0L, journal.invalidPublicationCount)
            assertTrue(journal.entries.isNotEmpty())
            assertTrue(journal.entries.all { it.generation == owner.generation &&
                it.selectionToken == owner.selectionToken && it.continuityViolationCount == 0L })
            assertHealthy(output.diagnostics(), baseline)
        }
    }

    @Test
    fun newerGenerationSupersedesAParkedSinkRecreationWithoutReleasingTheOldOwner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val plays = AtomicInteger(0)
        val writes = AtomicInteger(0)
        val opens = AtomicInteger(0)
        val controlledSink = object : NativeAudioTrackFeeder.Sink {
            override val configuration = NativeAudioTrackFeeder.Configuration(48_000, 2, 4, 512, 512, 512, 1, -1)
            override fun write(pcm: ByteBuffer): Int { writes.incrementAndGet(); return 0 }
            override fun play() { plays.incrementAndGet() }
            override fun pause() = Unit
            override fun observe(actual: IntArray, position: LongArray) {
                actual[0] = NativeAudioStreamState.PAUSED.rawValue
                actual[1] = 512; actual[2] = 512; actual[3] = 48_000; actual[4] = 1; actual[5] = 0
                position[0] = 0L
            }
            override fun close() = Unit
        }
        val owner = NativeRuntimeOwnership(8_901L, 3L)
        try {
            withOutputEngineSource(FloatArray(4_096 * 2) { .01f },
                sinkFactory = {
                    if (opens.incrementAndGet() == 2) {
                        entered.countDown()
                        check(release.await(2L, TimeUnit.SECONDS)) { "Controlled sink recreation was not released" }
                    }
                    controlledSink
                }, ownership = owner) { output, runtime, _, initial ->
                assertFalse("Generation 2 requires physical replacement acknowledgement", output.reopenForRouteChange())
                assertTrue("Feeder must enter the controlled platform recreation", entered.await(2L, TimeUnit.SECONDS))
                try {
                    assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(initial))
                    assertTrue(runtime.start())
                    await("Actual Runtime stages generation 3 PCM behind the parked recreation") {
                        val native = runtime.diagnostics()
                        output.currentGeneration() == 3L && output.diagnostics().queuedFrames > 0L &&
                            native.counters.outputHandoffCommitRetries > 0L &&
                            native.counters.outputHandoffCommits == 0L
                    }
                    val stopped = NativeRuntimeCommandSnapshot(2L, 4L,
                        listOf(NativeRuntimeBankIntent.unassigned(2L), NativeRuntimeBankIntent.unassigned(2L)),
                        selectedBank = null, playing = false, controls = NativeRuntimeControls(2L))
                    assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(stopped))
                    assertFalse("New generation 4 shares the still-unacknowledged sink fence", output.tryFlushToGeneration(4L))
                    release.countDown()
                    await("The newest generation 4 completes after the shared physical recreation acknowledgement") {
                        val actual = output.diagnostics()
                        val native = runtime.diagnostics()
                        actual.streamGeneration == 4L && actual.appliedGeneration == 4L &&
                            actual.queuedFrames == 0L && !actual.playing &&
                            native.ownership.requested == null && native.counters.outputFlushes > 0L
                    }
                    assertThrows(IllegalArgumentException::class.java) { output.tryFlushToGeneration(2L) }
                    assertEquals(4L, output.currentGeneration())
                    assertEquals("No superseded prefill may start", 0, plays.get())
                    assertEquals("No superseded source PCM may reach the sink", 0, writes.get())
                    assertEquals(0L, runtime.diagnostics().processOutputFlushFailures)
                    assertFalse(output.diagnostics().recoveryRequired)
                } finally { release.countDown() }
            }
        } finally { release.countDown() }
    }

    private fun withEngine(frames: Int,
        exercise: (NativeAudioOutput, NativePlaybackRuntime,
        NativeRuntimeOwnership, NativeRuntimeCommandSnapshot, AndroidAudioTrackSink, () -> Unit) -> Unit) {
        val samples = audioTrackEngineSamples(frames)
        repeat(25) { cycle ->
            val heapBaseline = android.os.Debug.getNativeHeapAllocatedSize()
            var sampledHeapPeak = heapBaseline
            val sampleHeap = { sampledHeapPeak = maxOf(sampledHeapPeak, android.os.Debug.getNativeHeapAllocatedSize()) }
            var actualSink: AndroidAudioTrackSink? = null
            var ownedOutput: NativeAudioOutput? = null
            var ownedOwner: NativeRuntimeOwnership? = null
            var baseline: NativeAudioDiagnostics? = null
            var finalObservation: Result<NativeAudioDiagnostics>? = null
            var finalJournal: NativeAudioPresentationJournalSnapshot? = null
            var retirementStartedAt = 0L
            try {
                withOutputEngineSource(samples,
                    sinkFactory = {
                    AndroidAudioTrackSink().also { actualSink = it }
                }, finalObservation = { result ->
                    finalObservation = result
                    finalJournal = requireNotNull(ownedOutput).presentationJournal(0L)
                }) { output, runtime, owner, command ->
                    ownedOutput = output
                    ownedOwner = owner
                    baseline = output.diagnostics()
                    sampleHeap()
                    assertEquals(2_048, requireNotNull(baseline).outputBufferSizeFrames)
                    try {
                        exercise(output, runtime, owner, command, requireNotNull(actualSink), sampleHeap)
                    } catch (failure: Throwable) {
                        val snapshot = try {
                            val native = runtime.diagnostics()
                            "cycle=$cycle sourceFrames=$frames owner=$owner " +
                                "configuration=${actualSink?.configuration} routeId=${actualSink?.routedDevice?.id} " +
                                "output=${output.diagnostics()} runtimeCounters=${native.counters} " +
                                "ownership=${native.ownership} presentation=${native.presentation} " +
                                "position=${native.presentedPosition} audibleEnd=${native.audibleEnd}"
                        } catch (diagnosticFailure: Throwable) {
                            "cycle=$cycle sourceFrames=$frames diagnosticsUnavailable=$diagnosticFailure"
                        }
                        // Android truncates a single log line; capture this one snapshot before use{} closes it.
                        snapshot.chunked(3_000).forEachIndexed { part, text ->
                            Log.e("StemWiseAudioTrackFailure", "cycle=$cycle part=$part $text")
                        }
                        throw failure
                    }
                    val actual = output.diagnostics()
                    sampleHeap()
                    val spans = output.presentationJournal(0).entries
                    Log.i("StemWiseAudioTrackEngine", "cycle=$cycle sourceFrames=$frames " +
                        "case=${if (frames == 256) "fresh-short" else "fresh-and-three-retained-resumes"} " +
                        "backend=${actual.backend} configuration=${actualSink?.configuration} " +
                        "routeId=${actualSink?.routedDevice?.id} routeType=${actualSink?.routedDevice?.type} " +
                        "acceptedOutputFrames=${actual.callbackFrames} presentedSourceFrames=${spans.sumOf { it.pcmFrames }} " +
                        "firstSourceFrame=${spans.firstOrNull()?.firstTimelineFrame} " +
                        "lastSourceFrame=${spans.lastOrNull()?.lastTimelineFrame} rawUnderruns=${actual.xRunCount}")
                    assertEquals(0L, runtime.diagnostics().processOutputInvalidRenderResults)
                    assertEquals(0L, runtime.diagnostics().processOutputFlushFailures)
                    retirementStartedAt = System.nanoTime()
                }
                assertTrue("Runtime/output retirement exceeded 2000ms", retirementStartedAt > 0L &&
                    System.nanoTime() - retirementStartedAt <= 2_000_000_000L)
                assertHealthy(requireNotNull(finalObservation) { "Final output observation is missing" }.getOrThrow(),
                    requireNotNull(baseline))
                assertSourcePrefix(requireNotNull(finalJournal), requireNotNull(ownedOwner))
                val retainedHeap = android.os.Debug.getNativeHeapAllocatedSize()
                val finalOutput = requireNotNull(finalObservation).getOrThrow()
                val initialOutput = requireNotNull(baseline)
                Log.i("StemWiseAudioTrackMemory", "cycle=$cycle sourceFrames=$frames " +
                    "nativeHeapBaselineBytes=$heapBaseline sampledNativeHeapPeakBytes=$sampledHeapPeak " +
                    "retainedNativeHeapBytes=$retainedHeap retainedDeltaBytes=${retainedHeap - heapBaseline} " +
                    "processXRunDelta=${finalOutput.processXRunCount - initialOutput.processXRunCount} " +
                    "processUnderrunFramesDelta=${finalOutput.processUnderrunFrames - initialOutput.processUnderrunFrames} " +
                    "processUnderrunCallbacksDelta=${finalOutput.processUnderrunCallbackCount - initialOutput.processUnderrunCallbackCount} " +
                    "processDeadlineMissDelta=${finalOutput.processCallbackDeadlineMissCount - initialOutput.processCallbackDeadlineMissCount} " +
                    "wholeProcessNativeHeap=true sampledPeakOnly=true")
            } catch (failure: Throwable) {
                throw AssertionError("AudioTrack cycle=$cycle sourceFrames=$frames failed", failure)
            }
        }
    }

    private fun assertSpeakerRoute(sink: AndroidAudioTrackSink) {
        val route = requireNotNull(sink.routedDevice) { "Actual running route is unavailable" }
        val audio = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.media.AudioManager::class.java))
        assertTrue("Qualification route must be an advertised built-in output",
            audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { it.id == route.id && it.type == route.type })
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route.type)
        observedSpeakerId?.let { assertEquals("The measured speaker route changed", it, route.id) }
            ?: run { observedSpeakerId = route.id }
    }

    private fun assertSourcePrefix(journal: NativeAudioPresentationJournalSnapshot, owner: NativeRuntimeOwnership) {
        assertTrue(journal.coherent)
        assertFalse(journal.sequenceExhausted)
        assertEquals(0L, journal.overflowCount)
        assertEquals(0L, journal.invalidPublicationCount)
        assertEquals(0L, journal.writerCollisionCount)
        assertTrue("Final source prefix is unavailable", journal.entries.isNotEmpty())
        var nextFrame = 0L
        for (span in journal.entries) {
            assertEquals(owner.generation, span.generation)
            assertEquals(owner.selectionToken, span.selectionToken)
            assertEquals(0L, span.continuityViolationCount)
            assertEquals("Source prefix was skipped or replayed", nextFrame, span.firstTimelineFrame)
            assertEquals(span.pcmFrames, span.lastTimelineFrame - span.firstTimelineFrame + 1L)
            assertEquals(span.lastTimelineFrame + 1L, span.lastNextTimelineFrame)
            nextFrame = span.lastNextTimelineFrame
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, predicate())
    }
}

internal fun assertHealthy(actual: NativeAudioDiagnostics, baseline: NativeAudioDiagnostics) {
    assertEquals(baseline.backend, actual.backend)
    assertEquals(48_000, actual.sampleRateHz)
    assertEquals(2, actual.channelCount)
    assertTrue("Output configuration must remain observed", actual.outputConfigurationObserved)
    assertEquals(NativeAudioBackend.AUDIO_TRACK, actual.backend)
    assertEquals(baseline.outputBufferSizeFrames, actual.outputBufferSizeFrames)
    assertTrue(baseline.performanceMode in 0..1)
    assertEquals(baseline.performanceMode, actual.performanceMode)
    assertEquals("Real output underruns remain strict", 0, actual.xRunCount)
    assertEquals(baseline.processXRunCount, actual.processXRunCount)
    assertEquals(baseline.processIncompleteStreamObservationCount, actual.processIncompleteStreamObservationCount)
    assertEquals(baseline.processUnderrunFrames, actual.processUnderrunFrames)
    assertEquals(baseline.processUnderrunCallbackCount, actual.processUnderrunCallbackCount)
    assertEquals(baseline.processInvalidOwnershipTagFrames, actual.processInvalidOwnershipTagFrames)
    assertEquals(baseline.processCallbackDeadlineMissCount, actual.processCallbackDeadlineMissCount)
    assertEquals(baseline.loop.processBoundaryMissCount, actual.loop.processBoundaryMissCount)
    assertEquals(baseline.callbackMix.missingControlFrames, actual.callbackMix.missingControlFrames)
    assertEquals(baseline.callbackMix.staleControlFrames, actual.callbackMix.staleControlFrames)
    assertFalse(actual.recoveryRequired)
}

internal fun withOutputEngineSource(
    samples: FloatArray,
    sinkFactory: () -> NativeAudioTrackFeeder.Sink = ::AndroidAudioTrackSink,
    ownership: NativeRuntimeOwnership = NativeRuntimeOwnership(8_901L, 2L),
    diagnosticRecord: NativeAudioTrackDiagnosticRecord? = null,
    finalObservation: ((Result<NativeAudioDiagnostics>) -> Unit)? = null,
    sourceCapacityFrames: Int = 65_536,
    exercise: (NativeAudioOutput, NativePlaybackRuntime, NativeRuntimeOwnership, NativeRuntimeCommandSnapshot) -> Unit
) {
        require(samples.isNotEmpty() && samples.size % 2 == 0 && samples.size / 2 <= sourceCapacityFrames)
        val frames = samples.size / 2
        var finalBaseline: NativeAudioDiagnostics? = null
        PlaybackPerformanceRunListener.beginOwnedOutputObservation()
        NativeAudioOutput(16_384, 1L,
            sinkFactory = sinkFactory,
            diagnosticRecord = diagnosticRecord,
            finalObservation = { result ->
                PlaybackPerformanceRunListener.recordOwnedOutputObservation(result)
                finalObservation?.invoke(result)
                assertHealthy(result.getOrThrow(), requireNotNull(finalBaseline))
            }).use { output ->
            finalBaseline = output.diagnostics()
            NativeSongLaneIngressBank(sourceCapacityFrames, 256).use { first ->
                NativeSongLaneIngressBank(256, 256).use { second ->
                    val owner = ownership
                    val sink = first.configureLane(0, 48_000, owner.generation)
                    val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
                    var firstFrame = 0
                    while (firstFrame < frames) {
                        val count = minOf(256, frames - firstFrame)
                        val pcm = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
                        repeat(count) { offset ->
                            pcm.putFloat(samples[(firstFrame + offset) * 2])
                                .putFloat(samples[(firstFrame + offset) * 2 + 1])
                        }
                        pcm.flip()
                        assertEquals(count * 8, sink.offer(NativePcmBlock().reset(pcm.asReadOnlyBuffer().order(ByteOrder.nativeOrder()), format,
                            firstFrame * 1_000_000L / 48_000, 0L, firstFrame.toLong())).acceptedBytes)
                        firstFrame += count
                    }
                    assertEquals(NativeLaneEndOfStreamStatus.ACCEPTED, sink.markEndOfStream(0L, frames.toLong()))
                    val identity = NativeRuntimeBankIntent(1L, owner, songIdentityHash = 8_901L,
                        originalContentHash = 8_902L, cacheFrameUpperBound = frames.toLong())
                    val command = NativeRuntimeCommandSnapshot(1L, owner.generation,
                        listOf(identity, NativeRuntimeBankIntent.unassigned(1L)), NativeRuntimeBank.FIRST,
                        playing = true, controls = NativeRuntimeControls(1L,
                            sourceIntentRevision = 1L, stemIntentRevision = 1L))
                    val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                    NativePlaybackRuntime(output, first, second,
                        NativeRuntimeConfiguration(currentSongCacheDirectory = cache.absolutePath)).use { runtime ->
                        runtime.setDiagnosticPayloadFingerprints(true)
                        try { exercise(output, runtime, owner, command) }
                        catch (failure: Throwable) {
                            val observed = output.diagnostics()
                            failure.addSuppressed(IllegalStateException("Output before cleanup: " +
                                "state=${observed.streamState} playing=${observed.playing} " +
                                "effectivePlaying=${observed.effectiveRenderPlaying} callbacks=${observed.callbackCount} " +
                                "rawUnderruns=${observed.xRunCount} queued=${observed.queuedFrames} " +
                                "recovery=${observed.recoveryRequired} error=${observed.lastErrorCode}"))
                            throw failure
                        }
                        finally { runtime.requestStop(); runtime.join() }
                    }
                }
            }
        }
    }

internal fun audioTrackEngineSamples(frames: Int): FloatArray = FloatArray(frames * 2) { index ->
    val sample = .005f + ((index / 2) % 127) * .00001f
    if (index % 2 == 0) sample else -sample
}
