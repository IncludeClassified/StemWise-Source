package app.stemwise.playback.engine

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated 512-frame acoustic markers; electrical PCM, guard silence and absolute onset are separate claims. */
@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(31)
@app.stemwise.qualification.ManagedAudioTest
@app.stemwise.qualification.OptionalAcousticTest
class AudioTrackAcousticInstrumentationTest {
    private lateinit var speaker: AudioDeviceInfo
    private lateinit var inputDevice: AudioDeviceInfo

    @Test fun builtInInputIdsAndConfigurationChangesAreValidatedWithoutCapture() {
        checkInputClock()
        checkConfigurationSnapshots()
    }

    @Test
    fun actualSpeakerCapturePreservesTheEnginePrefixAndFollowingSequence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
        assertTrue("Acoustic observation needs an audible, unchanged media volume",
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
        speaker = requireNotNull(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) { "Built-in speaker unavailable" }
        inputDevice = requireNotNull(audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }) { "Built-in microphone unavailable" }
        checkConfigurationSnapshots()
        checkInputClock()
        checkSequenceDetector()
        val packet = packet(TEST_SEED)
        val source = packet.samples()
        val stereo = FloatArray(source.size * 2) { source[it / 2] }
        val expectedBackend = NativeAudioBackend.AUDIO_TRACK
        val sink = AtomicReference<AndroidAudioTrackSink>()
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var primaryFailure: Throwable? = null
        try {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            val calibration = calibrate(audio, packet)
            val calibratedSpeaker = requireNotNull(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .singleOrNull { it.id == calibration.outputRouteId }) { "Calibrated speaker is unavailable" }
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, calibratedSpeaker.type)
            var ownedOutput: NativeAudioOutput? = null
            var finalObservation: Result<NativeAudioDiagnostics>? = null
            withOutputEngineSource(stereo,
                sourceCapacityFrames = 131_072,
                sinkFactory = {
                AndroidAudioTrackSink(preferredDevice = calibratedSpeaker).also(sink::set)
            }, finalObservation = { finalObservation = it }) { output, runtime, owner, command ->
                ownedOutput = output
                fun observeRoute(): Int {
                    val route = requireNotNull(sink.get()?.routedDevice) { "Active Java output route is unavailable" }
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, route.type)
                    assertEquals(calibration.outputRouteId, route.id)
                    return route.type
                }
                val recorder = Microphone(audio)
                var acceptedBefore = 0L
                var acceptedAfter = 0L
                var outputDevice = -1
                var observation: JSONObject? = null
                var captureFailure: Throwable? = null
                try {
                    recorder.start()
                    await("microphone lead-in before the accepted Play command") { recorder.frames.get() >= RATE / 10 }
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, recorder.record.routedDevice?.type)
                    assertEquals("The calibrated microphone must remain exact", calibration.inputRouteId, recorder.record.routedDevice?.id)
                    assertEquals(calibration.captureSource, recorder.record.audioSource)
                    assertEquals(calibration.volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    recorder.assertCaptureReady()
                    val baseline = output.diagnostics()
                    assertEquals(expectedBackend, baseline.backend)
                    assertEquals(0L, baseline.processIncompleteStreamObservationCount)
                    acceptedBefore = System.nanoTime()
                    assertEquals(NativeRuntimePublishStatus.ACCEPTED, runtime.publish(command))
                    acceptedAfter = System.nanoTime()
                    assertTrue(runtime.start())
                    await("actual selected output route") {
                        sink.get()?.routedDevice != null
                    }
                    outputDevice = observeRoute()
                    assertEquals("The measured output route must remain the built-in speaker",
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, outputDevice)
                    await("the exact finite source reaches its owned output end") {
                        assertEquals("The speaker route changed during the source",
                            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, observeRoute())
                        assertEquals(calibration.inputRouteId, recorder.record.routedDevice?.id)
                        assertEquals(calibration.volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        runtime.diagnostics().audibleEnd?.ownership == owner
                    }
                    recorder.snapshotConfiguration("after-play")
                    assertEquals(NativeRuntimePublishStatus.ACCEPTED,
                        runtime.publish(command.copy(revision = 2L, playing = false)))
                    await("selected physical output acknowledges pause") { output.quiesceIfPaused() }
                    awaitCompleteCapture(recorder) {
                        assertEquals(calibration.inputRouteId, recorder.record.routedDevice?.id)
                        assertEquals(calibration.volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    }
                    assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, recorder.record.routedDevice?.type)
                    val captureSource = recorder.record.audioSource
                    assertEquals(calibration.captureSource, captureSource)
                    recorder.close()
                    recorder.failure.get()?.let { throw AssertionError("AudioRecord capture failed", it) }
                    val captured = FloatArray(recorder.frames.get()) { recorder.pcm[it] / 32768f }
                    val health = output.diagnostics()
                    observation = JSONObject().put("outputRouteType", outputDevice)
                        .put("backend", health.backend.name).put("outputRouteId", calibration.outputRouteId)
                        .put("signalKind", SIGNAL_KIND)
                        .put("claim", "isolated 512-frame marker prefix/order and whole terminal-word preservation")
                        .put("settlingGuardFrames", SETTLING_GUARD_FRAMES)
                        .put("guardWaveformQualified", false)
                        .put("waveformFidelityQualified", false)
                        .put("acousticPathCalibration", calibration.metadata)
                        .put("inputRouteType", AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        .put("captureSource", captureSource).put("source", signalLevels(source))
                        .put("capture", signalLevels(captured))
                        .put("acquisition", recorder.acquisitionMetadata())
                        .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        .put("musicMaxVolume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                        .put("commandAcceptedBeforeNanos", acceptedBefore).put("commandAcceptedAfterNanos", acceptedAfter)
                        .put("inputTimestamps", JSONArray().also { timestamps -> recorder.clocks.forEach { clock ->
                            timestamps.put(JSONObject().put("frame", clock.frame).put("nanos", clock.nanos)
                                .put("callNanos", clock.callNanos))
                        } })
                        .put("rawXRunCount", health.xRunCount).put("processXRunCount", health.processXRunCount)
                        .put("processUnderrunFrames", health.processUnderrunFrames)
                        .put("processInvalidOwnershipTagFrames", health.processInvalidOwnershipTagFrames)
                        .put("processCallbackDeadlineMissCount", health.processCallbackDeadlineMissCount)
                        .put("processIncompleteStreamObservationCount", health.processIncompleteStreamObservationCount)
                    Log.i(TAG, observation.toString())
                    check(signalLevels(captured).getDouble("clippingFraction") == 0.0) { "Clipped engine capture" }
                    check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == calibration.volume) { "Calibrated volume changed" }
                    val match = match(packet, captured)
                    val clocks = recorder.clocks
                    val anchor = clocks.firstOrNull()
                    val residual = anchor?.let { first -> clocks.maxOf { clock ->
                        abs(clock.nanos - first.nanos - (clock.frame - first.frame) * 1_000_000_000L / RATE) + clock.callNanos
                    } }
                    val estimatedMicOnset = anchor?.let { (it.nanos +
                        (match.firstFrame - it.frame) * 1_000_000_000L / RATE - acceptedAfter) / 1e6 }
                    Log.i(TAG, JSONObject().put("outputRouteType", outputDevice)
                        .put("signalKind", SIGNAL_KIND)
                        .put("inputRouteType", AudioDeviceInfo.TYPE_BUILTIN_MIC)
                        .put("sourceFrames", source.size).put("captureFrames", captured.size)
                        .put("minimumMatchedToneFraction", match.minimum).put("decodedSymbols", match.symbols)
                        .put("symbolFrames", SYMBOL_FRAMES).put("captureSource", captureSource)
                        .put("estimatedCommandToMicSignalMs", estimatedMicOnset ?: JSONObject.NULL)
                        .put("timestampFitResidualMs", residual?.div(1e6) ?: JSONObject.NULL)
                        .put("commandAcceptanceBracketNanos", acceptedAfter - acceptedBefore)
                        .put("alignmentGridFrames", 1).put("fixedSymbolClock", true)
                        .put("absoluteOnsetQualified", false).put("waveformFidelityQualified", false)
                        .put("capturePathCalibration", "fresh-independent-static-sequence-controls")
                        .put("stereoIsolationQualified", false).toString())
                    assertEquals(0, health.xRunCount)
                    assertEquals(baseline.processXRunCount, health.processXRunCount)
                    assertEquals(baseline.processUnderrunFrames, health.processUnderrunFrames)
                    assertEquals(baseline.processInvalidOwnershipTagFrames, health.processInvalidOwnershipTagFrames)
                    assertEquals(baseline.processCallbackDeadlineMissCount, health.processCallbackDeadlineMissCount)
                    assertEquals(0L, health.processIncompleteStreamObservationCount)
                } catch (failure: Throwable) {
                    captureFailure = failure
                    runCatching { recorder.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    if (recorder.isRetired()) {
                        try {
                        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                            ?.takeIf { it.isNotBlank() }?.let(::File)
                            ?: requireNotNull(context.externalMediaDirs.firstOrNull { it != null })
                        check(directory.isDirectory || directory.mkdirs()) { "Cannot create acoustic failure output: $directory" }
                        val retained = File(directory, "engine-acoustic-sequence-failure.wav")
                        recorder.writeFailure(retained)
                        val failureObservation = observation ?: JSONObject().put("signalKind", SIGNAL_KIND)
                            .put("observationAvailable", false)
                        failureObservation.put("acquisition", recorder.acquisitionMetadata())
                        failureObservation.put("inputTimestamps", JSONArray().also { timestamps -> recorder.clocks.forEach {
                            timestamps.put(JSONObject().put("frame", it.frame).put("nanos", it.nanos).put("callNanos", it.callNanos))
                        } })
                        File(directory, "engine-acoustic-sequence-failure.json").writeText(
                            failureObservation.put("failure", failure.toString()).toString(), Charsets.UTF_8)
                        Log.e(TAG, "Acoustic capture failed; bounded mono PCM retained at ${retained.absolutePath}; " +
                            "speakerType=$outputDevice commandBracket=$acceptedBefore..$acceptedAfter", failure)
                        } catch (retentionFailure: Throwable) { failure.addSuppressed(retentionFailure) }
                    }
                    throw failure
                } finally {
                    runCatching { recorder.close() }.exceptionOrNull()?.let { cleanupFailure ->
                        captureFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
            }
            val finalHealth = requireNotNull(finalObservation) { "Missing retired output observation" }.getOrThrow()
            assertTrue("Measured output was not closed", requireNotNull(ownedOutput).isClosed())
            assertEquals(expectedBackend, finalHealth.backend)
            Log.i(TAG, JSONObject().put("phase", "retired-output").put("backend", finalHealth.backend.name)
                .put("rawXRunCount", finalHealth.xRunCount).put("processXRunCount", finalHealth.processXRunCount)
                .put("processIncompleteStreamObservationCount", finalHealth.processIncompleteStreamObservationCount)
                .put("outputBufferFrames", finalHealth.outputBufferSizeFrames).toString())
        }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val restoreFailure = runCatching {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                await("restoring the original media volume") {
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume
                }
            }.exceptionOrNull()
            if (restoreFailure != null) {
                primaryFailure?.addSuppressed(restoreFailure) ?: throw restoreFailure
            }
        }
    }

    private data class Clock(val frame: Long, val nanos: Long, val callNanos: Long)
    private data class ClockIntegrity(val samples: Int, val progressingSamples: Int, val repeatedSamples: Int,
        val maximumAbsoluteExcessNanos: Double, val limitNanos: Double, val failure: String?)
    private data class InputFormat(val rate: Int, val channels: Int, val encoding: Int, val mask: Int, val indexMask: Int)
    private data class InputConfiguration(val deviceId: Int?, val deviceType: Int?, val source: Int,
        val silenced: Boolean, val client: InputFormat, val hardware: InputFormat)
    private data class InputEffect(val name: String, val implementor: String, val type: String, val uuid: String)
    private class RecordingSnapshot(val input: InputConfiguration, val sessionId: Int, val actualSource: Int,
        effects: List<InputEffect>, clientEffects: List<InputEffect>) {
        val effects = effects.toList()
        val clientEffects = clientEffects.toList()
    }
    private class InputWarmup {
        val clocks = ArrayList<Clock>(32)
        var resets = 0
            private set
        var integrity: ClockIntegrity? = null
            private set
        fun reset() { if (clocks.isNotEmpty()) resets++; clocks.clear() }
        fun observe(clock: Clock): Boolean {
            check(clocks.size < 1024) { "Bounded input warm-up timestamp capacity exceeded" }
            clocks += clock
            val observed = validateInputClock(clocks)
            integrity = observed
            if (observed.failure != null && observed.failure != "fewer than two progressing valid timestamps") {
                reset()
                if (clock.frame >= 0L && clock.nanos > 0L && clock.callNanos >= 0L) clocks += clock
                return false
            }
            return observed.failure == null && clock.frame - clocks.first().frame >= RATE / 5 &&
                clock.nanos - clocks.first().nanos >= 200_000_000L
        }
    }

    private fun signalLevels(samples: FloatArray): JSONObject = JSONObject()
        .put("frames", samples.size)
        .put("rms", if (samples.isEmpty()) 0.0 else sqrt(samples.sumOf { it.toDouble() * it } / samples.size))
        .put("peak", samples.maxOfOrNull { abs(it) } ?: 0f)
        .put("clippingFraction", if (samples.isEmpty()) 0.0 else
            samples.count { abs(it) >= 32767f / 32768f }.toDouble() / samples.size)

    private data class ControlCapture(val microphone: Microphone, val samples: FloatArray, val metadata: JSONObject)

    private data class Calibration(val volume: Int, val captureSource: Int, val inputRouteId: Int, val outputRouteId: Int, val metadata: JSONObject)

    private fun calibrate(audio: AudioManager, reference: TonePacket): Calibration {
        val training = packet(TRAINING_SEED)
        val attempts = JSONArray()
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var last: ControlCapture? = null
        var volume = -1
        var captureSource = -1
        val controls = JSONArray()
        fun verify(label: String, played: TonePacket, channel: Int? = null, shouldMatch: Boolean,
            failOnMismatch: Boolean = true): Boolean {
            val captured = captureStatic(audio, played.samples(), label, channel, volume, captureSource)
            last = captured
            val own = runCatching { match(played, captured.samples) }
            val original = if (played === reference) own else runCatching { match(reference, captured.samples) }
            val accepted = captured.metadata.getJSONObject("capture").getDouble("clippingFraction") == 0.0 &&
                captured.metadata.getInt("rawStaticUnderruns") == 0 && own.isSuccess &&
                (if (shouldMatch) original.isSuccess else detectorRejection(original.exceptionOrNull()))
            controls.put(JSONObject().put("case", label).put("volume", volume)
                .put("expectedMatch", shouldMatch).put("passed", accepted)
                .put("ownDecodedSymbols", own.getOrNull()?.symbols ?: JSONObject.NULL)
                .put("ownMinimumToneEnergyFraction", own.getOrNull()?.minimum ?: JSONObject.NULL)
                .put("ownError", own.exceptionOrNull()?.message ?: JSONObject.NULL)
                .put("originalError", original.exceptionOrNull()?.message ?: JSONObject.NULL))
            if (!accepted && failOnMismatch) {
                retainControl(captured, JSONObject().put("attempts", attempts).put("controls", controls))
                error("Physical sequence control $label failed; acoustic path remains unqualified")
            }
            return accepted
        }
        val levels = listOf(2, 6, 10, 14, maximum).filter { it in 1..maximum }.distinct()
        for (level in levels) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            await("actual static control volume $level") { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == level }
            val captured = captureStatic(audio, training.samples(), "independent-seed-A", null, level, null)
            last = captured
            val verdict = runCatching {
                check(captured.metadata.getJSONObject("capture").getDouble("clippingFraction") == 0.0) { "Clipped static control" }
                check(captured.metadata.getInt("rawStaticUnderruns") == 0) { "Static control underrun" }
                match(training, captured.samples)
            }
            val attempt = JSONObject().put("volume", level).put("qualified", false).put("seedAQualified", verdict.isSuccess)
                .put("acquisition", captured.metadata.getJSONObject("acquisition"))
                .put("minimumMatchedToneFraction", verdict.getOrNull()?.minimum ?: JSONObject.NULL)
                .put("error", verdict.exceptionOrNull()?.message ?: JSONObject.NULL)
            attempts.put(attempt)
            if (verdict.isSuccess) {
                volume = level
                captureSource = captured.metadata.getInt("captureSource")
                val positive = verify("independent-seed-B", reference, shouldMatch = true, failOnMismatch = false) &&
                    verify("left-only", reference, 0, true, failOnMismatch = false) &&
                    verify("right-only", reference, 1, true, failOnMismatch = false)
                attempt.put("qualified", positive)
                if (positive) break
                volume = -1
                captureSource = -1
            }
        }
        if (volume < 0) {
            last?.let { retainControl(it, JSONObject().put("attempts", attempts).put("controls", controls)) }
            error("Fresh independent and individual-speaker controls remain unqualified at volumes $levels")
        }
        mutations(reference).forEach { (label, played) -> verify(label, played, shouldMatch = false) }
        check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volume) { "Static control volume changed" }
        return Calibration(volume, captureSource, inputDevice.id, speaker.id, JSONObject()
            .put("scope", "static exact tone-symbol prefix/order controls only; not streaming qualification")
            .put("signalKind", SIGNAL_KIND).put("trainingSeed", TRAINING_SEED).put("testSeed", TEST_SEED)
            .put("symbolFrames", SYMBOL_FRAMES).put("minimumMatchedToneFraction", MINIMUM_TONE_FRACTION)
            .put("markerCount", reference.tones.size).put("settlingGuardFrames", SETTLING_GUARD_FRAMES)
            .put("guardWaveformQualified", false)
            .put("dominanceEnergyScope", "fixed tone carriers and two guard channels after a fixed 750Hz high-pass")
            .put("terminalWordHz", JSONArray(listOf(FREQUENCIES.last(), TERMINAL_END_FREQUENCY)))
            .put("terminalHalfFrames", SYMBOL_FRAMES / 2)
            .put("minimumToneRms", MINIMUM_TONE_RMS).put("modelFitted", false)
            .put("inputRouteId", inputDevice.id).put("outputRouteId", speaker.id).put("captureSource", captureSource)
            .put("volume", volume).put("attempts", attempts).put("controls", controls)
            .put("testedPrefixDeletionDuplicationFrames", 512).put("testedReorderedSegmentFrames", 1024)
            .put("testedTerminalDeletionDuplicationFrames", 512)
            .put("waveformFidelityQualified", false).put("absoluteOnsetQualified", false)
            .put("stereoIsolationQualified", false))
    }

    private data class SpatializationObservation(val nanos: Long, val level: Int, val enabled: Boolean,
        val available: Boolean, val canBeSpatialized: Boolean) {
        fun json(): JSONObject = JSONObject().put("nanos", nanos).put("immersiveAudioLevel", level)
            .put("enabled", enabled).put("available", available).put("canBeSpatialized", canBeSpatialized)
    }

    private fun captureStatic(audio: AudioManager, reference: FloatArray, label: String, channel: Int?,
        expectedVolume: Int, expectedCaptureSource: Int?): ControlCapture {
        val stereo = FloatArray(reference.size * 2) { index ->
            if (channel == null || index % 2 == channel) reference[index / 2] else 0f
        }
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
        fun spatializerObservation(actualAttributes: AudioAttributes, actualFormat: AudioFormat): SpatializationObservation? = if (android.os.Build.VERSION.SDK_INT >= 32) {
            audio.spatializer.let { SpatializationObservation(System.nanoTime(), it.immersiveAudioLevel,
                it.isEnabled, it.isAvailable, it.canBeSpatialized(actualAttributes, actualFormat)) }
        } else null
        val microphone = Microphone(audio)
        val track = try { AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(stereo.size * Float.SIZE_BYTES).build()
        } catch (failure: Throwable) {
            runCatching { microphone.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        var primaryFailure: Throwable? = null
        try {
            check(track.setPreferredDevice(speaker)) { "Static speaker selection rejected" }
            check(track.sampleRate == RATE && track.channelCount == 2 && track.audioFormat == AudioFormat.ENCODING_PCM_FLOAT)
            check(track.write(stereo, 0, stereo.size, AudioTrack.WRITE_BLOCKING) == stereo.size) { "Static control was not fully loaded" }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Static control did not initialize after its full write" }
            val attributesBefore = track.audioAttributes
            var spatializerBefore: SpatializationObservation? = null
            microphone.start { spatializerBefore = spatializerObservation(attributesBefore, track.format) }
            await("static control microphone lead-in") { microphone.frames.get() >= RATE / 10 }
            val input = requireNotNull(microphone.record.routedDevice)
            check(input.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && input.id == inputDevice.id) { "Static control left the selected microphone" }
            check(expectedCaptureSource == null || microphone.record.audioSource == expectedCaptureSource) { "Calibration capture source changed" }
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expectedVolume) { "Calibration volume changed" }
            microphone.assertCaptureReady()
            val playBefore = System.nanoTime()
            track.play()
            val playAfter = System.nanoTime()
            await("static built-in speaker route") { track.routedDevice != null }
            val output = requireNotNull(track.routedDevice)
            check(output.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && output.id == speaker.id) { "Static control left the selected speaker" }
            await("complete static control playback") {
                check(track.routedDevice?.id == output.id && microphone.record.routedDevice?.id == input.id) { "Calibration route changed" }
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expectedVolume) { "Calibration volume changed" }
                (track.playbackHeadPosition.toLong() and 0xffff_ffffL) >= reference.size
            }
            val attributesAfter = track.audioAttributes
            val spatializerAfter = spatializerObservation(attributesAfter, track.format)
            microphone.snapshotConfiguration("after-play")
            awaitCompleteCapture(microphone) {
                check(microphone.record.routedDevice?.id == input.id) { "Calibration input route changed" }
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == expectedVolume) { "Calibration volume changed" }
            }
            val captureSource = microphone.record.audioSource
            check(expectedCaptureSource == null || captureSource == expectedCaptureSource) { "Calibration capture source changed" }
            microphone.close()
            microphone.failure.get()?.let { throw AssertionError("Calibration capture failed", it) }
            val captured = FloatArray(microphone.frames.get()) { microphone.pcm[it] / 32768f }
            val metadata = JSONObject().put("case", label).put("mode", "MODE_STATIC")
                .put("signalKind", SIGNAL_KIND)
                .put("outputRouteId", output.id).put("outputRouteType", output.type)
                .put("inputRouteId", input.id).put("inputRouteType", input.type).put("captureSource", captureSource)
                .put("sampleRateHz", RATE).put("source", signalLevels(reference)).put("capture", signalLevels(captured))
                .put("acquisition", microphone.acquisitionMetadata())
                .put("outputProcessing", JSONObject().put("audioSessionId", track.audioSessionId)
                    .put("usage", attributesAfter.usage).put("contentType", attributesAfter.contentType)
                    .put("spatializationBehaviorBefore", if (android.os.Build.VERSION.SDK_INT >= 32) attributesBefore.spatializationBehavior else JSONObject.NULL)
                    .put("spatializationBehaviorAfter", if (android.os.Build.VERSION.SDK_INT >= 32) attributesAfter.spatializationBehavior else JSONObject.NULL)
                    .put("beforePlay", spatializerBefore?.json() ?: JSONObject.NULL)
                    .put("afterPlay", spatializerAfter?.json() ?: JSONObject.NULL))
                .put("outputChannels", track.channelCount).put("outputEncoding", track.audioFormat)
                .put("staticBufferFrames", track.bufferSizeInFrames).put("staticCapacityFrames", track.bufferCapacityInFrames)
                .put("noiseBeforePlay", signalLevels(captured.copyOfRange(0, minOf(RATE / 10, captured.size))))
                .put("musicVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("playBeforeNanos", playBefore).put("playAfterNanos", playAfter)
                .put("rawStaticUnderruns", track.underrunCount)
                .put("inputTimestamps", JSONArray().also { clocks -> microphone.clocks.forEach {
                    clocks.put(JSONObject().put("frame", it.frame).put("nanos", it.nanos).put("callNanos", it.callNanos))
                } })
            return ControlCapture(microphone, captured, metadata)
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { microphone.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            if (microphone.isRetired()) {
                runCatching {
                    retainControl(ControlCapture(microphone, FloatArray(0), JSONObject()
                        .put("case", label).put("signalKind", SIGNAL_KIND)
                        .put("acquisition", microphone.acquisitionMetadata())
                        .put("inputTimestamps", JSONArray().also { timestamps -> microphone.clocks.forEach {
                            timestamps.put(JSONObject().put("frame", it.frame).put("nanos", it.nanos).put("callNanos", it.callNanos))
                        } })),
                        JSONObject().put("acquisitionFailure", failure.toString()))
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            for (cleanup in listOf({ microphone.close() }, { track.stop() }, { track.release() })) {
                runCatching(cleanup).exceptionOrNull()?.let { failure ->
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                }
            }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun retainControl(capture: ControlCapture, report: JSONObject, name: String = "acoustic-calibration-failure") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: requireNotNull(context.externalMediaDirs.firstOrNull { it != null })
        check(directory.isDirectory || directory.mkdirs())
        capture.microphone.writeFailure(File(directory, "$name.wav"))
        File(directory, "$name.json").writeText(capture.metadata.put("controlReport", report).toString(), Charsets.UTF_8)
    }

    private inner class Microphone(audio: AudioManager) : AutoCloseable {
        val pcm = ShortArray(RATE * 3)
        val frames = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val clocks = ArrayList<Clock>(1024)
        private var clockIntegrity: ClockIntegrity? = null
        private var clockSampleOverflow = false
        private val preRollPcm = ShortArray(1024)
        private val readinessRequestNanos = AtomicLong()
        private val captureArmed = AtomicBoolean(false)
        private val configurationReady = AtomicBoolean(false)
        private val configurationRevision = AtomicLong()
        private var observedInputConfiguration: InputConfiguration? = null
        private var armedInputConfiguration: InputConfiguration? = null
        private val discardedReadFrames = AtomicLong()
        private val warmup = InputWarmup()
        @Volatile private var captureOriginFrames = -1L
        private var captureArmedNanos = -1L
        private var armClock: Clock? = null
        private var readinessStartedNanos = -1L
        private val readOffsets = IntArray(8192)
        private val readCounts = IntArray(readOffsets.size)
        private val readStartedNanos = LongArray(readOffsets.size)
        private val readEndedNanos = LongArray(readOffsets.size)
        private var readReceiptCount = 0
        private var readReceiptOverflow = false
        private val configurationLock = Any()
        private val configurations = arrayOfNulls<RecordingSnapshot>(64)
        private val configurationNanos = LongArray(configurations.size)
        private val configurationFrames = IntArray(configurations.size)
        private val configurationPhases = arrayOfNulls<String>(configurations.size)
        private var configurationCount = 0
        private var configurationOverflow = false
        private var acceptConfigurations = true
        private var callbackRegistered = false
        private var callbackDetached = false
        private var callbackDrained = false
        private val callbackExecutor = InstrumentationRegistry.getInstrumentation().targetContext.mainExecutor
        private var nativeBufferFrames = -1
        private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                saveConfiguration("callback", configs.firstOrNull { it.clientAudioSessionId == sessionId })
            }
        }
        private val sessionId: Int
        private val requestedSource: Int
        private val running = AtomicBoolean(false)
        private var started = false
        private var closed = false
        val record: AudioRecord
        private val reader: Thread
        init {
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "48kHz mono capture is unavailable: $minimum" }
            val source = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            requestedSource = source
            record = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, pcm.size * Short.SIZE_BYTES)).build()
            if (record.state != AudioRecord.STATE_INITIALIZED || record.sampleRate != RATE) {
                record.release()
                error("48kHz capture did not initialize")
            }
            if (!record.setPreferredDevice(inputDevice)) {
                record.release()
                error("Microphone selection rejected")
            }
            nativeBufferFrames = record.bufferSizeInFrames
            sessionId = record.audioSessionId
            if (nativeBufferFrames < pcm.size) {
                record.release()
                error("AudioRecord did not accept the complete bounded observation buffer: $nativeBufferFrames")
            }
            reader = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val stamp = AudioTimestamp()
                    var warmConfigurationRevision = -1L
                    while (running.get() && frames.get() < pcm.size) {
                        if (!captureArmed.get()) {
                            val count = record.read(preRollPcm, 0, preRollPcm.size, AudioRecord.READ_NON_BLOCKING)
                            check(count >= 0) { "AudioRecord pre-roll read failed: $count" }
                            if (count == 0) { Thread.sleep(2); continue }
                            val discarded = discardedReadFrames.addAndGet(count.toLong())
                            val currentConfigurationRevision = configurationRevision.get()
                            if (currentConfigurationRevision != warmConfigurationRevision) {
                                warmup.reset()
                                warmConfigurationRevision = currentConfigurationRevision
                            }
                            val requested = readinessRequestNanos.get()
                            if (requested == 0L || !configurationReady.get()) { warmup.reset(); continue }
                            val before = System.nanoTime()
                            if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) continue
                            val clock = Clock(stamp.framePosition, stamp.nanoTime, System.nanoTime() - before)
                            if (clock.nanos < requested) continue
                            if (warmup.observe(clock)) synchronized(configurationLock) {
                                if (configurationReady.get() && configurationRevision.get() == warmConfigurationRevision) {
                                    // Only this reader commits the boundary; no tested output has been commanded yet.
                                    captureOriginFrames = discarded
                                    armClock = clock
                                    armedInputConfiguration = requireNotNull(observedInputConfiguration)
                                    captureArmedNanos = System.nanoTime()
                                    readReceiptCount = 0
                                    clocks.clear()
                                    captureArmed.set(true)
                                }
                            }
                            continue
                        }
                        val offset = frames.get()
                        if (readReceiptCount == readOffsets.size) {
                            readReceiptOverflow = true
                            error("Bounded microphone read receipt capacity exceeded")
                        }
                        val receipt = readReceiptCount
                        readOffsets[receipt] = offset
                        readCounts[receipt] = Int.MIN_VALUE
                        readStartedNanos[receipt] = System.nanoTime()
                        val count = try {
                            record.read(pcm, offset, minOf(1024, pcm.size - offset), AudioRecord.READ_NON_BLOCKING)
                                .also { readCounts[receipt] = it }
                        } finally {
                            readEndedNanos[receipt] = System.nanoTime()
                            readReceiptCount++
                        }
                        check(count >= 0) { "AudioRecord read failed: $count" }
                        if (count == 0) { Thread.sleep(2); continue }
                        frames.addAndGet(count)
                        val before = System.nanoTime()
                        if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                            if (clocks.size == 1024) {
                                clockSampleOverflow = true
                                error("Bounded microphone timestamp capacity exceeded")
                            }
                            clocks += Clock(stamp.framePosition - captureOriginFrames, stamp.nanoTime, System.nanoTime() - before)
                        }
                    }
                } catch (cause: Throwable) { failure.compareAndSet(null, cause) }
            }, "StemWiseAcousticCapture").apply { isDaemon = true }
        }
        private fun saveConfiguration(phase: String, configuration: AudioRecordingConfiguration?) {
            synchronized(configurationLock) {
                if (!acceptConfigurations) return
                val receivedNanos = System.nanoTime()
                val receivedFrames = frames.get()
                val snapshot = configuration?.let(::recordingSnapshot)
                val observed = snapshot?.input
                observedInputConfiguration = observed
                val ready = observed?.deviceId == inputDevice.id &&
                    inputConfigurationReady(observed, requestedSource, armedInputConfiguration)
                configurationReady.set(ready)
                configurationRevision.incrementAndGet()
                if (!ready && captureArmed.get() && running.get() && frames.get() < pcm.size)
                    failure.compareAndSet(null, IllegalStateException("Armed microphone device/format changed or configuration became unavailable, silenced or incompatible"))
                if (configurationCount == configurations.size) {
                    configurationOverflow = true
                    failure.compareAndSet(null, IllegalStateException("Bounded recording configuration capacity exceeded"))
                    return
                }
                val index = configurationCount++
                configurations[index] = snapshot
                configurationNanos[index] = receivedNanos
                configurationFrames[index] = receivedFrames
                configurationPhases[index] = phase
            }
        }
        fun snapshotConfiguration(phase: String) {
            saveConfiguration(phase, record.activeRecordingConfiguration)
            failure.get()?.let { throw AssertionError("Microphone acquisition failed", it) }
        }
        fun assertCaptureReady() {
            failure.get()?.let { throw AssertionError("Microphone acquisition failed", it) }
            check(captureArmed.get() && configurationReady.get()) { "Microphone measurement has not been armed with a valid configuration" }
        }
        fun start(beforeArm: () -> Unit = {}) {
            readinessStartedNanos = System.nanoTime()
            val deadline = readinessStartedNanos + 5_000_000_000L
            try {
                record.registerAudioRecordingCallback(callbackExecutor, recordingCallback)
                callbackRegistered = true
                record.startRecording()
                started = true
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                running.set(true); reader.start()
                while (System.nanoTime() < deadline) {
                    failure.get()?.let { throw AssertionError("Microphone pre-roll failed", it) }
                    val configuration = record.activeRecordingConfiguration
                    val route = record.routedDevice
                    if (configuration != null && inputConfiguration(configuration).deviceId == inputDevice.id &&
                        inputConfigurationReady(inputConfiguration(configuration), requestedSource) &&
                        route?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && route.id == inputDevice.id) {
                        saveConfiguration("before-arm", configuration)
                        if (configurationReady.get()) {
                            beforeArm()
                            readinessRequestNanos.set(System.nanoTime())
                            break
                        }
                    }
                    Thread.sleep(5)
                }
                check(readinessRequestNanos.get() > 0L) { "Microphone readiness configuration timed out before arming" }
                while (!captureArmed.get() && System.nanoTime() < deadline) {
                    failure.get()?.let { throw AssertionError("Microphone pre-roll failed", it) }
                    Thread.sleep(2)
                }
                check(captureArmed.get() && System.nanoTime() <= deadline) { "Continuous 200ms input pre-roll timed out before arming" }
                assertCaptureReady()
            } catch (cause: Throwable) {
                failure.compareAndSet(null, cause)
                runCatching { close() }.exceptionOrNull()?.let(cause::addSuppressed)
                throw cause
            }
        }
        override fun close() {
            if (closed) return
            running.set(false)
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let { error ->
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure?.addSuppressed(error)
                }
            }
            if (started) cleanup { saveConfiguration("before-stop", record.activeRecordingConfiguration) }
            if (started) cleanup { record.stop() }
            if (started) reader.join(2000)
            if (callbackRegistered) {
                cleanup {
                    record.unregisterAudioRecordingCallback(recordingCallback)
                    callbackRegistered = false
                    callbackDetached = true
                    val drained = CountDownLatch(1)
                    callbackExecutor.execute { drained.countDown() }
                    check(drained.await(2, TimeUnit.SECONDS)) { "Recording configuration callback queue did not drain" }
                    callbackDrained = true
                }
            } else if (!callbackDetached) { callbackDetached = true; callbackDrained = true }
            synchronized(configurationLock) { acceptConfigurations = false }
            check(!reader.isAlive) { "Microphone reader did not retire; AudioRecord remains owned by that reader" }
            cleanup {
                val observed = validateInputClock(clocks)
                val integrity = if (clockSampleOverflow) observed.copy(failure = observed.failure ?: "timestamp sample capacity exceeded") else observed
                clockIntegrity = integrity
                check(integrity.failure == null) { "Acoustic acquisition clock is unqualified: ${integrity.failure}" }
            }
            cleanup {
                var copiedFrames = 0
                var previousEnd = 0L
                for (index in 0 until readReceiptCount) {
                    check(readOffsets[index] == copiedFrames && readStartedNanos[index] >= previousEnd &&
                        readEndedNanos[index] >= readStartedNanos[index]) { "Microphone read receipt sequence is inconsistent" }
                    if (readCounts[index] > 0) {
                        check(readCounts[index] <= minOf(1024, pcm.size - copiedFrames)) { "Microphone read exceeded its requested range" }
                        copiedFrames += readCounts[index]
                    } else check(readCounts[index] == 0 || failure.get() != null) { "Failed microphone read has no failure state" }
                    previousEnd = readEndedNanos[index]
                }
                check(copiedFrames == frames.get()) { "Microphone read receipts do not account for retained PCM" }
            }
            cleanup { record.release(); closed = true }
            cleanupFailure?.let { throw it }
        }
        fun isRetired(): Boolean = closed && !reader.isAlive
        fun acquisitionMetadata(): JSONObject {
            check(closed && !reader.isAlive && !acceptConfigurations) { "Acquisition diagnostics require retired owners" }
            fun boundFormat(value: InputFormat) = JSONObject().put("sampleRateHz", value.rate)
                .put("encoding", value.encoding).put("channelMask", value.mask).put("channelIndexMask", value.indexMask)
                .put("channelCount", value.channels)
            val configurationEvents = JSONArray()
            for (index in 0 until configurationCount) {
                val value = configurations[index]
                val event = JSONObject().put("phase", configurationPhases[index]).put("nanos", configurationNanos[index])
                    .put("readFrames", configurationFrames[index]).put("available", value != null)
                if (value != null) {
                    fun effects(values: List<InputEffect>) = JSONArray().also { result ->
                        values.forEach { effect -> result.put(JSONObject().put("name", effect.name)
                            .put("implementor", effect.implementor).put("type", effect.type).put("uuid", effect.uuid)) }
                    }
                    event.put("source", value.input.source).put("actualSource", value.actualSource).put("sessionId", value.sessionId)
                        .put("inputRouteId", value.input.deviceId ?: JSONObject.NULL)
                        .put("inputRouteType", value.input.deviceType ?: JSONObject.NULL)
                        .put("silenced", value.input.silenced).put("actualFormat", boundFormat(value.input.hardware))
                        .put("clientFormat", boundFormat(value.input.client)).put("effects", effects(value.effects))
                        .put("clientEffects", effects(value.clientEffects))
                }
                configurationEvents.put(event)
            }
            val integrity = clockIntegrity
            return JSONObject().put("requestedBufferFrames", pcm.size).put("actualBufferFrames", nativeBufferFrames)
                .put("inputPreRoll", JSONObject().put("discardedReadFrames", discardedReadFrames.get())
                    .put("readinessStartedNanos", readinessStartedNanos)
                    .put("readinessRequestedNanos", readinessRequestNanos.get())
                    .put("measurementArmed", captureArmed.get()).put("captureArmedNanos", captureArmedNanos)
                    .put("captureOriginReadFrames", captureOriginFrames)
                    .put("timestampFrameNormalization", "rawGlobalFrameMinusExactDiscardedReadFrames")
                    .put("armRawGlobalFrame", armClock?.frame ?: JSONObject.NULL)
                    .put("armRawGlobalNanos", armClock?.nanos ?: JSONObject.NULL)
                    .put("armedInputConfiguration", armedInputConfiguration?.let { bound -> JSONObject()
                        .put("inputRouteId", bound.deviceId).put("inputRouteType", bound.deviceType)
                        .put("source", bound.source).put("silenced", bound.silenced)
                        .put("actualFormat", boundFormat(bound.hardware)).put("clientFormat", boundFormat(bound.client)) } ?: JSONObject.NULL)
                    .put("continuousWarmupRequiredNanos", 200_000_000L).put("warmupResets", warmup.resets)
                    .put("lastWarmupClockFailure", warmup.integrity?.failure ?: JSONObject.NULL)
                    .put("lastWarmupMaximumAbsoluteExcessNanos", warmup.integrity?.maximumAbsoluteExcessNanos ?: JSONObject.NULL)
                    .put("warmupClocks", JSONArray().also { stamps -> warmup.clocks.forEach { clock ->
                        stamps.put(JSONObject().put("rawGlobalFrame", clock.frame).put("nanos", clock.nanos).put("callNanos", clock.callNanos))
                    } }))
                .put("inputClockIntegrity", JSONObject().put("evaluated", integrity != null)
                    .put("sampleCount", integrity?.samples ?: clocks.size)
                    .put("sampleOverflow", clockSampleOverflow)
                    .put("progressingSampleCount", integrity?.progressingSamples ?: JSONObject.NULL)
                    .put("repeatedSampleCount", integrity?.repeatedSamples ?: JSONObject.NULL)
                    .put("maximumAbsoluteExcessNanos", integrity?.maximumAbsoluteExcessNanos ?: JSONObject.NULL)
                    .put("limitNanos", SYMBOL_FRAMES * 1_000_000_000.0 / RATE)
                    .put("qualified", integrity != null && integrity.failure == null && !clockSampleOverflow)
                    .put("failure", integrity?.failure ?: JSONObject.NULL))
                .put("readReceiptCapacity", readOffsets.size).put("readReceiptOverflow", readReceiptOverflow)
                .put("readReceipts", JSONArray().also { receipts ->
                    for (index in 0 until readReceiptCount) receipts.put(JSONObject()
                        .put("offset", readOffsets[index]).put("count", readCounts[index])
                        .put("beforeNanos", readStartedNanos[index]).put("afterNanos", readEndedNanos[index]))
                }).put("configurationCapacity", configurations.size).put("configurationOverflow", configurationOverflow)
                .put("configurationCallbackDetached", callbackDetached)
                .put("configurationMainExecutorDrained", callbackDrained)
                .put("configurationEvidenceComplete", callbackDetached && callbackDrained && !configurationOverflow)
                .put("configurationEvents", configurationEvents)
        }
        fun writeFailure(file: File) {
            val size = frames.get() * 2
            val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size)
            repeat(frames.get()) { bytes.putShort(pcm[it]) }
            file.writeBytes(bytes.array())
        }
    }

    private data class TonePacket(val tones: IntArray,
        val starts: IntArray = IntArray(tones.size) { it * (SYMBOL_FRAMES + SETTLING_GUARD_FRAMES) },
        val frames: Int = tones.size * (SYMBOL_FRAMES + SETTLING_GUARD_FRAMES)) {
        init {
            require(tones.isNotEmpty() && starts.size == tones.size && starts.first() >= 0)
            require((1 until starts.size).all { starts[it] >= starts[it - 1] + SYMBOL_FRAMES })
            require(starts.last() + SYMBOL_FRAMES <= frames)
        }
        fun samples(): FloatArray = FloatArray(frames).also { samples ->
            for (symbol in tones.indices) for (frame in 0 until SYMBOL_FRAMES) {
                val tone = tones[symbol]
                val frequency = if (tone == FREQUENCIES.lastIndex && frame >= SYMBOL_FRAMES / 2)
                    TERMINAL_END_FREQUENCY else FREQUENCIES[tone]
                samples[starts[symbol] + frame] = (.1 * sin(2 * PI * frequency * frame / RATE)).toFloat()
            }
        }
    }

    private data class Match(val firstFrame: Int, val minimum: Double, val symbols: Int)

    /** Noncoherent receiver for isolated markers and adjacent complete terminal-word duplication. */
    private class ToneObservation(private val captured: FloatArray) {
        private val projectedFrequencies = FREQUENCIES + TERMINAL_END_FREQUENCY + GUARD_FREQUENCIES
        private val real = Array(projectedFrequencies.size) { DoubleArray(captured.size + 1) }
        private val imaginary = Array(projectedFrequencies.size) { DoubleArray(captured.size + 1) }
        init {
            // Reject sub-bass with one fixed filter; never fit a response to the measured room.
            val omega = 2 * PI * GUARD_FREQUENCIES.first() / RATE
            val alpha = sin(omega) / sqrt(2.0)
            val b0 = (1 + cos(omega)) / (2 * (1 + alpha))
            val a1 = -2 * cos(omega) / (1 + alpha)
            val a2 = (1 - alpha) / (1 + alpha)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (frame in captured.indices) {
                val input = captured[frame].toDouble()
                check(input.isFinite()) { "Non-finite acoustic capture" }
                val value = b0 * (input - 2 * x1 + x2) - a1 * y1 - a2 * y2
                x2 = x1; x1 = input; y2 = y1; y1 = value
                for (tone in projectedFrequencies.indices) {
                    val angle = 2 * PI * projectedFrequencies[tone] * frame / RATE
                    real[tone][frame + 1] = real[tone][frame] + value * cos(angle)
                    imaginary[tone][frame + 1] = imaginary[tone][frame] + value * sin(angle)
                }
            }
        }
        fun fraction(offset: Int, tone: Int): Double {
            if (tone == FREQUENCIES.lastIndex) return minOf(
                carrierFraction(offset, SYMBOL_FRAMES / 2, tone),
                carrierFraction(offset + SYMBOL_FRAMES / 2, SYMBOL_FRAMES / 2, FREQUENCIES.size))
            return carrierFraction(offset, SYMBOL_FRAMES, tone)
        }
        private fun carrierFraction(offset: Int, frames: Int, tone: Int): Double {
            var energy = 0.0
            var toneEnergy = 0.0
            for (channel in projectedFrequencies.indices) {
                val re = real[channel][offset + frames] - real[channel][offset]
                val im = imaginary[channel][offset + frames] - imaginary[channel][offset]
                val channelEnergy = 2 * (re * re + im * im) / frames
                energy += channelEnergy
                if (channel == tone) toneEnergy = channelEnergy
            }
            if (toneEnergy < frames * MINIMUM_TONE_RMS * MINIMUM_TONE_RMS || energy <= 0.0) return 0.0
            return (toneEnergy / energy).coerceIn(0.0, 1.0)
        }
        fun identifiableTone(offset: Int): Int {
            var identified = -1
            for (tone in FREQUENCIES.indices) if (fraction(offset, tone) >= MINIMUM_TONE_FRACTION) {
                if (identified >= 0) return -1
                identified = tone
            }
            return identified
        }
        fun identifiableCarrier(offset: Int): Boolean = projectedFrequencies.indices.any {
            carrierFraction(offset, SYMBOL_FRAMES, it) >= MINIMUM_TONE_FRACTION
        }
    }

    private fun match(expected: TonePacket, captured: FloatArray): Match {
        require(expected.tones.isNotEmpty() && expected.tones.all { it in FREQUENCIES.indices })
        require(captured.size <= RATE * 3) { "Acoustic observation exceeded the bounded three-second extent" }
        val frames = expected.frames
        check(captured.size >= frames) { "Acoustic sequence missing complete extent" }
        val observation = ToneObservation(captured)
        val deadline = System.nanoTime() + 5_000_000_000L
        var best = -1
        var bestMinimum = 0.0
        var firstValid = -1
        for (offset in 0..captured.size - frames) {
            if (offset % 128 == 0) check(System.nanoTime() < deadline) { "Bounded acoustic sequence deadline" }
            if (firstValid >= 0 && offset - firstValid >= SYMBOL_FRAMES) break
            if (observation.fraction(offset + expected.starts.first(), expected.tones.first()) < MINIMUM_TONE_FRACTION ||
                observation.fraction(offset + expected.starts.last(), expected.tones.last()) < MINIMUM_TONE_FRACTION) continue
            var minimum = 1.0
            for (symbol in expected.tones.indices) {
                val position = offset + expected.starts[symbol]
                val fraction = observation.fraction(position, expected.tones[symbol])
                if (fraction < MINIMUM_TONE_FRACTION || observation.identifiableTone(position) != expected.tones[symbol]) {
                    minimum = 0.0
                    break
                }
                minimum = minOf(minimum, fraction)
            }
            if (minimum < MINIMUM_TONE_FRACTION) continue
            if (firstValid < 0) firstValid = offset
            if (minimum > bestMinimum) { best = offset; bestMinimum = minimum }
        }
        check(best >= 0) { "Acoustic sequence prefix/order/extent is missing or ambiguous" }
        for (symbol in expected.tones.indices) {
            val guardEnd = expected.starts.getOrNull(symbol + 1) ?: frames
            if (guardEnd - expected.starts[symbol] - SYMBOL_FRAMES >= SYMBOL_FRAMES)
                check(!observation.identifiableCarrier(best + guardEnd - SYMBOL_FRAMES)) {
                    "Acoustic sequence did not settle by the fixed guard end after marker $symbol"
                }
            val next = expected.starts[symbol] + SYMBOL_FRAMES
            if (expected.tones[symbol] == FREQUENCIES.lastIndex &&
                expected.starts.getOrNull(symbol + 1) != next) {
                check(best + next + SYMBOL_FRAMES <= captured.size) { "Acoustic sequence lacks the terminal lookahead" }
                check(observation.fraction(best + next, FREQUENCIES.lastIndex) < MINIMUM_TONE_FRACTION) {
                    "Acoustic sequence has an adjacent extra 512-frame terminal word"
                }
            }
        }
        // The earliest complete packet owns alignment; delayed sound inside settling guards is not sample proof.
        for (offset in 0..captured.size - SYMBOL_FRAMES) {
            if (offset % 128 == 0) check(System.nanoTime() < deadline) { "Bounded acoustic sequence deadline" }
            if (offset + SYMBOL_FRAMES <= best + expected.starts.first() || offset >= best + frames) {
                val extra = observation.identifiableTone(offset)
                check(extra < 0) { "Acoustic sequence has earlier or extra tone ${FREQUENCIES[extra]}Hz at $offset, packet=$best..${best + frames}" }
            }
        }
        return Match(best, bestMinimum, expected.tones.size)
    }

    private fun packet(seed: Int): TonePacket {
        var state = seed
        val tones = IntArray(8) { symbol ->
            when (symbol) {
                0 -> 4
                7 -> 5
                else -> {
                    state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
                    state and 3
                }
            }
        }
        // The specified 1024-frame swap must alter the transmitted code in either seed.
        for (symbol in 2..5) tones[symbol] = symbol - 2
        return TonePacket(tones)
    }

    private fun mutations(reference: TonePacket): List<Pair<String, TonePacket>> {
        val tones = reference.tones
        val reordered = tones.copyOf()
        reordered[2] = tones[4]
        reordered[4] = tones[2]
        check(!reordered.contentEquals(tones))
        return listOf(
            "missing-512-prefix" to TonePacket(tones.copyOfRange(1, tones.size),
                reference.starts.drop(1).map { it - SYMBOL_FRAMES }.toIntArray(), reference.frames - SYMBOL_FRAMES),
            "duplicated-512-prefix" to TonePacket(intArrayOf(tones.first()) + tones,
                intArrayOf(reference.starts.first()) + reference.starts.map { it + SYMBOL_FRAMES }.toIntArray(),
                reference.frames + SYMBOL_FRAMES),
            "wrong-code" to packet(TRAINING_SEED),
            "reordered-1024-frame-segments" to reference.copy(tones = reordered),
            "missing-512-terminal" to TonePacket(tones.copyOf(tones.size - 1),
                reference.starts.copyOf(tones.size - 1), reference.frames - SYMBOL_FRAMES),
            "extra-512-terminal" to TonePacket(tones + tones.last(),
                reference.starts + (reference.starts.last() + SYMBOL_FRAMES), reference.frames + SYMBOL_FRAMES))
    }

    private fun detectorRejection(failure: Throwable?): Boolean = failure is IllegalStateException &&
        failure.message.orEmpty().startsWith("Acoustic sequence")

    private fun checkSequenceDetector() {
        val reference = packet(TEST_SEED)
        val exactEnd = TonePacket(intArrayOf(4, 5), intArrayOf(0, SYMBOL_FRAMES), SYMBOL_FRAMES * 2)
        check(detectorRejection(runCatching { match(exactEnd, exactEnd.samples()) }.exceptionOrNull())) {
            "An absent terminal observation became a passing measurement"
        }
        check(!reference.tones.contentEquals(packet(TRAINING_SEED).tones))
        check(reference.tones.first() == 4 && reference.tones.last() == 5)
        check(reference.tones.sliceArray(1 until reference.tones.lastIndex).all { it in 0..3 })
        // The reference channel is specified independently of any spectral observation or fitted capture.
        fun knownCapture(packet: TonePacket, lead: Int, gain: Float, interference: Boolean): FloatArray {
            val input = packet.samples()
            var random = 0x31415926
            return FloatArray(lead + input.size + 2048) { frame ->
                val sourceFrame = frame - lead
                fun delayed(delay: Int): Float = if (sourceFrame - delay in input.indices) input[sourceFrame - delay] else 0f
                random = random xor (random shl 13); random = random xor (random ushr 17); random = random xor (random shl 5)
                val noise = if (interference) .0005 * (random.toDouble() / Int.MAX_VALUE) else 0.0
                val music = if (interference) .001 * sin(2 * PI * 705 * frame / RATE) else 0.0
                val vibration = if (interference) .001 * sin(2 * PI * 95 * frame / RATE) else 0.0
                (.01 + gain * (.7 * delayed(0) - .18 * delayed(3) + .08 * delayed(10) +
                    .03 * delayed(128) + .01 * delayed(700)) + noise + music + vibration).toFloat()
            }
        }
        fun withBass(capture: FloatArray): FloatArray = FloatArray(capture.size) { frame ->
            (capture[frame] + .12 * sin(2 * PI * 187.5 * frame / RATE) +
                .15 * sin(2 * PI * 281.25 * frame / RATE) + .1 * sin(2 * PI * 562.5 * frame / RATE)).toFloat()
        }
        for (lead in listOf(1024, 1028)) for (gain in listOf(.5f, 1f))
            match(reference, knownCapture(reference, lead, gain, true))
        match(reference, withBass(knownCapture(reference, 1028, .5f, true)))
        fun delayedRoom(packet: TonePacket): FloatArray {
            val input = packet.samples()
            return FloatArray(1028 + input.size + 8192) { frame ->
                fun delayed(delay: Int): Float = input.getOrNull(frame - 1028 - delay) ?: 0f
                .8f * delayed(0) + .65f * delayed(1024) + .5f * delayed(3072) + .25f * delayed(6144)
            }
        }
        val dense = reference.copy(starts = IntArray(reference.tones.size) { it * SYMBOL_FRAMES },
            frames = reference.tones.size * SYMBOL_FRAMES)
        check(detectorRejection(runCatching { match(dense, delayedRoom(dense)) }.exceptionOrNull())) {
            "Dense tones unexpectedly qualified in the independently specified delayed channel"
        }
        match(reference, delayedRoom(reference))
        val wrongEnd = reference.samples().also { samples ->
            val start = reference.starts.last()
            val firstHalf = samples.copyOfRange(start, start + SYMBOL_FRAMES / 2)
            samples.copyInto(samples, start, start + SYMBOL_FRAMES / 2, start + SYMBOL_FRAMES)
            firstHalf.copyInto(samples, start + SYMBOL_FRAMES / 2)
        }
        check(detectorRejection(runCatching { match(reference, FloatArray(1028) + wrongEnd + FloatArray(2048)) }.exceptionOrNull())) {
            "Detector accepted a reversed terminal word"
        }
        for ((label, played) in mutations(reference)) {
            val original = reference.samples()
            val actual = played.samples()
            val terminal = reference.starts.last()
            when (label) {
                "missing-512-prefix" -> check(actual.contentEquals(original.copyOfRange(SYMBOL_FRAMES, original.size)))
                "duplicated-512-prefix" -> check(actual.contentEquals(original.copyOfRange(0, SYMBOL_FRAMES) + original))
                "missing-512-terminal" -> check(actual.contentEquals(original.copyOfRange(0, terminal) +
                    original.copyOfRange(terminal + SYMBOL_FRAMES, original.size)))
                "extra-512-terminal" -> check(actual.contentEquals(original.copyOfRange(0, terminal + SYMBOL_FRAMES) +
                    original.copyOfRange(terminal, terminal + SYMBOL_FRAMES) + original.copyOfRange(terminal + SYMBOL_FRAMES, original.size)))
                "reordered-1024-frame-segments" -> {
                    val first = reference.starts[2]; val second = reference.starts[4]
                    val block = original.copyOfRange(first, first + 1024)
                    original.copyInto(original, first, second, second + 1024)
                    block.copyInto(original, second)
                    check(actual.contentEquals(original))
                }
            }
            for (capture in listOf(knownCapture(played, 1028, .5f, true), withBass(knownCapture(played, 1028, .5f, true)))) {
                match(played, capture)
                check(detectorRejection(runCatching { match(reference, capture) }.exceptionOrNull())) {
                    "Detector accepted the original after $label"
                }
            }
        }
        val silence = FloatArray(reference.frames + 2048)
        check(detectorRejection(runCatching { match(reference, silence) }.exceptionOrNull())) { "Detector accepted silence" }
        val ambiguous = reference.samples().also { samples ->
            for (frame in samples.indices) samples[frame] += (.1 * sin(2 * PI * GUARD_FREQUENCIES.last() * frame / RATE)).toFloat()
        }
        check(detectorRejection(runCatching { match(reference, FloatArray(1028) + ambiguous + FloatArray(2048)) }.exceptionOrNull())) {
            "Detector accepted equal-energy competing audio"
        }
        val missingBody = reference.samples().also { it.fill(0f, reference.starts[2], reference.starts[2] + SYMBOL_FRAMES) }
        check(detectorRejection(runCatching { match(reference, FloatArray(1028) + missingBody + FloatArray(2048)) }.exceptionOrNull())) {
            "Detector accepted a missing interior symbol"
        }
        check(detectorRejection(runCatching { match(reference, withBass(FloatArray(1028) + ambiguous + FloatArray(2048))) }.exceptionOrNull())) {
            "Sub-bass exclusion hid equal-energy in-band competition"
        }
        check(detectorRejection(runCatching { match(reference, withBass(FloatArray(1028) + missingBody + FloatArray(2048))) }.exceptionOrNull())) {
            "Sub-bass exclusion invented a missing interior symbol"
        }
        check(detectorRejection(runCatching { match(reference, withBass(silence)) }.exceptionOrNull())) {
            "Sub-bass alone was accepted as a coded packet"
        }
        val quiet = reference.samples().also { samples -> for (frame in samples.indices) samples[frame] *= .005f }
        check(detectorRejection(runCatching { match(reference, withBass(FloatArray(1028) + quiet + FloatArray(2048))) }.exceptionOrNull())) {
            "Sub-bass exclusion admitted a carrier below the fixed minimum tone amplitude"
        }
        val strongTail = knownCapture(reference, 1028, .5f, true).also { samples ->
            val terminalEnd = 1028 + reference.starts.last() + SYMBOL_FRAMES
            val extraWord = TonePacket(intArrayOf(FREQUENCIES.lastIndex)).samples()
            for (frame in terminalEnd until terminalEnd + SYMBOL_FRAMES)
                samples[frame] += .2f * extraWord[frame - terminalEnd]
        }
        check(detectorRejection(runCatching { match(reference, strongTail) }.exceptionOrNull())) {
            "Detector accepted an extra identifiable complete terminal word"
        }
        val unsettled = knownCapture(reference, 1028, .5f, true).also { samples ->
            val end = 1028 + reference.starts[1]
            for (frame in end - SYMBOL_FRAMES until end)
                samples[frame] += (.02 * sin(2 * PI * GUARD_FREQUENCIES.last() * frame / RATE)).toFloat()
        }
        check(detectorRejection(runCatching { match(reference, unsettled) }.exceptionOrNull())) {
            "Detector accepted an identifiable carrier at a fixed guard end"
        }
        val ringdown = knownCapture(reference, 1028, .5f, true).also { samples ->
            val terminalEnd = 1028 + reference.starts.last() + SYMBOL_FRAMES
            for (frame in terminalEnd until samples.size)
                samples[frame] += (.02 * kotlin.math.exp(-(frame - terminalEnd) / 512.0) *
                    sin(2 * PI * TERMINAL_END_FREQUENCY * (frame - terminalEnd) / RATE)).toFloat()
        }
        match(reference, ringdown)
        val own = reference.samples()
        val other = packet(TRAINING_SEED).samples()
        val mixedPackets = FloatArray(own.size) { own[it] + other[it] }
        check(detectorRejection(runCatching { match(reference, FloatArray(1028) + mixedPackets + FloatArray(2048)) }.exceptionOrNull())) {
            "Detector accepted indistinguishable competing packet codes"
        }
        val lateCapture = knownCapture(reference, 1028, .5f, true)
        match(reference, FloatArray(RATE * 3 - lateCapture.size) + lateCapture)
    }

    private fun checkInputClock() {
        val client = InputFormat(RATE, 1, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_MONO, 0)
        val actual = InputConfiguration(14, AudioDeviceInfo.TYPE_BUILTIN_MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            false, client, client.copy(rate = 16_000))
        check(inputConfigurationReady(actual, actual.source))
        check(inputConfigurationReady(actual.copy(deviceId = 315), actual.source))
        check(!inputConfigurationReady(actual.copy(deviceId = 315), actual.source, actual))
        check(inputConfigurationReady(actual.copy(source = MediaRecorder.AudioSource.MIC), MediaRecorder.AudioSource.MIC))
        check(inputConfigurationReady(actual.copy(hardware = actual.hardware.copy(rate = 8_000)), actual.source))
        listOf(
            null, actual.copy(deviceId = null, deviceType = null), actual.copy(deviceId = 0),
            actual.copy(deviceType = AudioDeviceInfo.TYPE_USB_DEVICE), actual.copy(silenced = true),
            actual.copy(source = MediaRecorder.AudioSource.MIC), actual.copy(client = client.copy(rate = 16_000)),
            actual.copy(client = client.copy(channels = 2)), actual.copy(client = client.copy(encoding = AudioFormat.ENCODING_PCM_FLOAT)),
            actual.copy(hardware = actual.hardware.copy(rate = 6_000)), actual.copy(hardware = actual.hardware.copy(channels = 2)),
            actual.copy(hardware = actual.hardware.copy(encoding = AudioFormat.ENCODING_PCM_FLOAT))
        ).forEach { check(!inputConfigurationReady(it, actual.source)) { "Placeholder or unsupported input configuration was admitted" } }
        check(inputConfigurationReady(actual, actual.source, actual))
        check(!inputConfigurationReady(actual.copy(hardware = actual.hardware.copy(rate = 48_000)), actual.source, actual))
        check(!inputConfigurationReady(actual.copy(hardware = actual.hardware.copy(indexMask = 1)), actual.source, actual))
        val nominal = listOf(Clock(0L, 1_000_000_000L, 100L), Clock(960L, 1_020_000_000L, 100L),
            Clock(1920L, 1_040_000_000L, 100L))
        val nominalResult = validateInputClock(nominal)
        check(nominalResult.failure == null && nominalResult.progressingSamples == 3 &&
            nominalResult.maximumAbsoluteExcessNanos == 0.0)
        check(validateInputClock(listOf(nominal[0], nominal[1].copy(nanos = 1_020_500_000L), nominal[2])).failure == null)
        val repeated = validateInputClock(listOf(nominal[0], nominal[0].copy(callNanos = 200L), nominal[1]))
        check(repeated.failure == null && repeated.progressingSamples == 2 && repeated.repeatedSamples == 1)
        val missing = listOf(emptyList(), listOf(nominal[0]), listOf(nominal[0], nominal[0]))
        missing.forEach { check(validateInputClock(it).failure != null) { "Unavailable input clock was accepted" } }
        listOf(
            listOf(nominal[1], nominal[0]),
            listOf(nominal[0], nominal[0].copy(nanos = 1_000_000_001L)),
            listOf(nominal[0], nominal[1].copy(nanos = 1_000_000_000L)),
            listOf(nominal[0], nominal[1].copy(frame = -1L)),
            listOf(nominal[0], nominal[1].copy(callNanos = -1L)),
            listOf(nominal[0], nominal[1].copy(nanos = 0L))
        ).forEach { check(validateInputClock(it).failure != null) { "Invalid/backward input clock was accepted" } }
        // A full symbol of excess time defeats the requested 512-frame temporal sequence claim.
        val gap = validateInputClock(listOf(nominal[0], Clock(1536L, 1_042_666_667L, 100L)))
        check(gap.failure != null && gap.maximumAbsoluteExcessNanos >= gap.limitNanos)
        check(validateInputClock(listOf(nominal[0], Clock(1536L, 1_042_666_666L, 100L))).failure == null)
        val compressed = validateInputClock(listOf(nominal[0], Clock(1536L, 1_021_333_333L, 100L)))
        check(compressed.failure != null && compressed.maximumAbsoluteExcessNanos >= compressed.limitNanos)
        val warm = InputWarmup()
        for (step in 0..9) check(!warm.observe(Clock(step * 960L, 2_000_000_000L + step * 20_000_000L, 100L)))
        check(!warm.observe(Clock(10 * 960L, 2_400_000_000L, 100L)))
        check(warm.resets == 1)
        for (step in 1..9) check(!warm.observe(Clock((10 + step) * 960L, 2_400_000_000L + step * 20_000_000L, 100L)))
        check(warm.observe(Clock(20 * 960L, 2_600_000_000L, 100L)))
        warm.reset()
        check(!warm.observe(Clock(20 * 960L, 2_600_000_000L, 100L)))
        check(!warm.observe(Clock(20 * 960L, 2_600_000_000L, 100L)))
        check(warm.resets == 2)
    }

    private fun checkConfigurationSnapshots() {
        val format = InputFormat(RATE, 1, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.CHANNEL_IN_MONO, 0)
        val received = InputConfiguration(14, AudioDeviceInfo.TYPE_BUILTIN_MIC, MediaRecorder.AudioSource.MIC,
            false, format, format.copy(rate = 16_000))
        var current = received
        val effect = InputEffect("fixture", "fixture", "type", "uuid")
        val effects = arrayListOf(effect)
        val clientEffects = arrayListOf(effect)
        val snapshot = RecordingSnapshot(current, 77, MediaRecorder.AudioSource.MIC, effects, clientEffects)
        current = current.copy(deviceId = null, hardware = format)
        effects.clear()
        clientEffects[0] = effect.copy(name = "later")
        check(snapshot.input == received && snapshot.input != current && snapshot.input.deviceId == 14)
        check(snapshot.sessionId == 77 && snapshot.actualSource == MediaRecorder.AudioSource.MIC)
        check(snapshot.effects == listOf(effect) && snapshot.clientEffects == listOf(effect))
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }

    private fun awaitCompleteCapture(microphone: Microphone, guard: () -> Unit = {}) {
        // Playback-head completion does not bound the downstream speaker/microphone delay.
        await("the complete bounded three-second microphone observation") {
            guard()
            microphone.failure.get()?.let { throw AssertionError("AudioRecord capture failed", it) }
            microphone.frames.get() == microphone.pcm.size
        }
    }

    private companion object {
        fun recordingSnapshot(value: AudioRecordingConfiguration): RecordingSnapshot {
            fun effects(values: List<android.media.audiofx.AudioEffect.Descriptor>) = values.map { effect ->
                InputEffect(effect.name, effect.implementor, effect.type.toString(), effect.uuid.toString())
            }
            return RecordingSnapshot(inputConfiguration(value), value.clientAudioSessionId, value.audioSource,
                effects(value.effects), effects(value.clientEffects))
        }

        fun inputConfiguration(value: AudioRecordingConfiguration): InputConfiguration {
            fun format(value: AudioFormat) = InputFormat(value.sampleRate, value.channelCount, value.encoding,
                value.channelMask, value.channelIndexMask)
            val device = value.audioDevice
            return InputConfiguration(device?.id, device?.type, value.clientAudioSource, value.isClientSilenced,
                format(value.clientFormat), format(value.format))
        }

        fun inputConfigurationReady(value: InputConfiguration?, source: Int, armed: InputConfiguration? = null): Boolean =
            value != null && value.deviceId != null && value.deviceId > 0 && value.deviceType == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                value.source == source && !value.silenced && value.client.rate == RATE && value.client.channels == 1 &&
                value.client.encoding == AudioFormat.ENCODING_PCM_16BIT && value.hardware.channels == 1 &&
                value.hardware.encoding == AudioFormat.ENCODING_PCM_16BIT && value.hardware.rate / 2.0 > GUARD_FREQUENCIES.max() &&
                (armed == null || value == armed)

        fun validateInputClock(clocks: List<Clock>): ClockIntegrity {
            val limit = SYMBOL_FRAMES * 1_000_000_000.0 / RATE
            var previous: Clock? = null
            var progressing = 0
            var repeated = 0
            var maximumExcess = 0.0
            var failure: String? = null
            for (clock in clocks) {
                if (clock.frame < 0L || clock.nanos <= 0L || clock.callNanos < 0L) {
                    if (failure == null) failure = "invalid timestamp fields"
                    continue
                }
                val before = previous
                if (before != null) {
                    if (clock.frame == before.frame && clock.nanos == before.nanos) { repeated++; continue }
                    if (clock.frame <= before.frame || clock.nanos <= before.nanos) {
                        if (failure == null) failure = "backward/reset timestamp or inconsistent duplicate frame"
                        continue
                    }
                    val excess = abs((clock.nanos - before.nanos).toDouble() -
                        (clock.frame - before.frame) * 1_000_000_000.0 / RATE)
                    maximumExcess = maxOf(maximumExcess, excess)
                    if (excess >= limit && failure == null) failure = "timestamp discontinuity reaches one 512-frame symbol"
                }
                previous = clock
                progressing++
            }
            if (progressing < 2 && failure == null) failure = "fewer than two progressing valid timestamps"
            return ClockIntegrity(clocks.size, progressing, repeated, maximumExcess, limit, failure)
        }

        const val TRAINING_SEED = 0x5a17b31
        const val TEST_SEED = 0x17a91b3
        const val RATE = 48_000
        const val SYMBOL_FRAMES = 512
        const val SETTLING_GUARD_FRAMES = 12_288
        // A decoded carrier must dominate the fixed code/guard channels by 4:1, above 32 PCM16 LSB RMS.
        const val MINIMUM_TONE_FRACTION = .8
        const val MINIMUM_TONE_RMS = 32.0 / 32768.0
        val FREQUENCIES = doubleArrayOf(1125.0, 1500.0, 1875.0, 2250.0, 2625.0, 3000.0)
        const val TERMINAL_END_FREQUENCY = 3375.0
        val GUARD_FREQUENCIES = doubleArrayOf(750.0, 3750.0)
        const val SIGNAL_KIND = "fixed-fsk-eight-512-frame-markers-256ms-settling-two-tone-terminal"
        const val TAG = "StemwiseAcousticCapture"
    }
}
