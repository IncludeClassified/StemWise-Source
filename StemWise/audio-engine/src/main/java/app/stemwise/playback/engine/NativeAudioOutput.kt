package app.stemwise.playback.engine

import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

internal enum class NativeOutputPreparationState { OPENING, UNAVAILABLE, READY }

/**
 * Sole owner of one physical output stream and two bounded multibus ring roles.
 *
 * The native playback runtime is the only PCM producer. This Kotlin owner manages lifecycle,
 * generation handoff, route recovery, diagnostics, and the opaque handle borrowed by that runtime.
 * The application and tests use the same persistent AudioTrack transport through the shared core.
 */
internal class NativeAudioOutput(
    capacityFrames: Int = NativeAudioBufferFormat.DEFAULT_CAPACITY_FRAMES,
    initialGeneration: Long = 1L,
    deferPhysicalOpen: Boolean = false,
    sinkFactory: (() -> NativeAudioTrackFeeder.Sink)? = null,
    diagnosticRecord: NativeAudioTrackDiagnosticRecord? = null,
    private val finalObservation: ((Result<NativeAudioDiagnostics>) -> Unit)? = null
) : AutoCloseable {
    private var nativeHandle: Long
    private var audioTrackFeeder: NativeAudioTrackFeeder? = null
    private var audioTrackFeederFactory: (() -> NativeAudioTrackFeeder)? = null

    private val loggedFeederFailure = AtomicReference<Throwable?>()
    private val detailedCapture = AtomicBoolean(diagnosticRecord != null)

    fun setDetailedDiagnosticCapture(enabled: Boolean) { detailedCapture.set(enabled) }
    private var preparationState = NativeOutputPreparationState.UNAVAILABLE
    private var retirementDeadlineNanos = 0L
    private var openingDeadlineNanos = 0L
    private var stopRetirementDeadlineNanos = 0L
    @Volatile private var physicalOpenAttempted = false

    init {
        NativeAudioBufferFormat.requireCapacity(capacityFrames)
        require(initialGeneration > 0L) { "Initial PCM generation must be positive" }
        nativeHandle = nativeCreate(capacityFrames, NATIVE_ENGINE_SAMPLE_RATE_HZ, initialGeneration)
        check(nativeHandle != 0L) { "Native audio output returned an invalid handle" }
        run {
            val handle = nativeHandle
            val engine = object : NativeAudioTrackFeeder.Engine {
                override fun pollControl(values: LongArray) = nativeTrackControl(handle, values)
                override fun awaitControlChange(afterSequence: Long): Boolean { nativeTrackAwaitControl(handle, afterSequence); return true }
                override fun wake() = nativeTrackWake(handle)
                override fun prepare(pcm: ByteBuffer, metadata: LongArray): Int =
                    nativeTrackPrepare(handle, pcm, metadata)
                override fun prepareWrite(pcm: ByteBuffer, frames: Int, transport: Boolean) {
                    check(nativeTrackPrepareWrite(handle, pcm, frames, transport)) { "Native output rejected PCM envelope preparation" }
                }
                override fun accept(sequence: Long, frames: Int): Boolean = nativeTrackAccept(handle, sequence, frames)
                override fun consumerActive(active: Boolean) = nativeTrackConsumerActive(handle, active)
                override fun discard() = nativeTrackDiscard(handle)
                override fun resetComplete(serial: Long) = nativeTrackResetComplete(handle, serial)
                override fun beginSourceTransition(serial: Long, generation: Long): Boolean =
                    nativeTrackBeginSourceTransition(handle, serial, generation)
                override fun prepareTransportSilence(kind: NativeAudioTrackFeeder.TransportSilenceKind): Long =
                    nativeTrackPrepareTransportSilence(handle, kind.ordinal)
                override fun acceptTransportSilence(sequence: Long, frames: Int): Boolean =
                    nativeTrackAcceptTransportSilence(handle, sequence, frames)
                override fun configure(instance: Long, configuration: NativeAudioTrackFeeder.Configuration) =
                    nativeTrackConfigure(handle, instance, IntArray(NativePacketSchema.TRACK_CONFIGURATION_COUNT).apply {
                        this[NativePacketSchema.TRACK_CONFIGURATION_RATE] = configuration.sampleRate
                        this[NativePacketSchema.TRACK_CONFIGURATION_CHANNELS] = configuration.channels
                        this[NativePacketSchema.TRACK_CONFIGURATION_ENCODING] = configuration.encoding
                        this[NativePacketSchema.TRACK_CONFIGURATION_BUFFER] = configuration.bufferFrames
                        this[NativePacketSchema.TRACK_CONFIGURATION_CAPACITY] = configuration.capacityFrames
                        this[NativePacketSchema.TRACK_CONFIGURATION_THRESHOLD] = configuration.startThresholdFrames
                        this[NativePacketSchema.TRACK_CONFIGURATION_PERFORMANCE] = configuration.performanceMode
                    })
                override fun observe(instance: Long, actual: IntArray, position: LongArray, complete: Boolean) =
                    nativeTrackObserve(handle, instance, actual, position, complete)
                override fun failed() {
                    val cause = audioTrackFeeder?.failureCause()
                    if (cause != null && loggedFeederFailure.getAndSet(cause) !== cause) {
                        // Failure diagnostics are optional; keep the exact feeder cause authoritative.
                        runCatching { Log.e("StemWiseAudioTrack", cause.stackTraceToString().take(3_000)) }
                    }
                    nativeTrackFailed(handle)
                }
            }
            val factory = { NativeAudioTrackFeeder(engine, sinkFactory ?: {
                AndroidAudioTrackSink(detailedCaptureRequested = detailedCapture::get)
            },
                record = diagnosticRecord) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } }
            audioTrackFeederFactory = factory
            if (!deferPhysicalOpen) try {
                check(start()) { "AudioTrack feeder initialization failed" }
            } catch (failure: Throwable) {
                audioTrackFeeder?.close()
                nativeClose(handle)
                nativeHandle = 0L
                throw failure
            }

        }
    }

    /** The actor requests opening; the feeder owns every blocking platform operation. */
    fun prepare(): NativeOutputPreparationState {
        if (stopRetirementDeadlineNanos != 0L) return NativeOutputPreparationState.UNAVAILABLE
        val factory = audioTrackFeederFactory ?: return NativeOutputPreparationState.READY
        val previous = audioTrackFeeder
        if (previous != null) {
            if (!previous.hasFailed) {
                val openingStarted = previous.sinkOpeningStartedNanos
                val overdue = if (openingStarted != 0L) System.nanoTime() - openingStarted >= 2_000_000_000L
                    else !previous.isInitialized && System.nanoTime() >= openingDeadlineNanos
                if (overdue && !previous.isReady && previous.sinkOpeningStartedNanos == openingStarted) {
                    previous.requestClose()
                    error("AudioTrack opener did not settle; native output must remain alive until retirement")
                }
                preparationState = if (previous.isReady) NativeOutputPreparationState.READY else NativeOutputPreparationState.OPENING
                return preparationState
            }
            if (preparationState != NativeOutputPreparationState.UNAVAILABLE) {
                preparationState = NativeOutputPreparationState.UNAVAILABLE
                retirementDeadlineNanos = System.nanoTime() + 2_000_000_000L
                return preparationState
            }
            if (!previous.isRetired) {
                check(System.nanoTime() < retirementDeadlineNanos) { "Failed AudioTrack feeder did not retire; native output must remain alive" }
                return preparationState
            }
            previous.close()
        }
        val feeder = factory()
        audioTrackFeeder = feeder
        preparationState = NativeOutputPreparationState.OPENING
        openingDeadlineNanos = System.nanoTime() + 2_000_000_000L
        physicalOpenAttempted = true
        feeder.begin()
        return preparationState
    }

    /** Standalone callers may await readiness; synchronous controls never use this wait. */
    fun start(): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        do {
            when (prepare()) {
                NativeOutputPreparationState.READY -> return true
                NativeOutputPreparationState.UNAVAILABLE -> return false
                NativeOutputPreparationState.OPENING -> LockSupport.parkNanos(500_000L)
            }
        } while (System.nanoTime() < deadline)
        error("AudioTrack feeder did not initialize")
    }

    @Synchronized
    fun currentGeneration(): Long = nativeCurrentGeneration(requireOpenHandle())

    /**
     * Attempts an empty generation invalidation without treating a pending callback role-swap
     * acknowledgement as a fatal JNI error. The native producer counter remains authoritative:
     * the render runtime also owns this output port and may advance it between Kotlin calls.
     */
    @Synchronized
    fun tryFlushToGeneration(newGeneration: Long): Boolean {
        val currentGeneration = currentGeneration()
        require(newGeneration > currentGeneration) {
            "PCM generations must advance monotonically " +
                "($newGeneration <= $currentGeneration)"
        }
        return nativeFlushToGeneration(requireOpenHandle(), newGeneration)
    }

    /** Returns the accepted generation, or null while the callback still owns the other ring. */
    @Synchronized
    fun tryFlush(): Long? {
        val currentGeneration = currentGeneration()
        check(currentGeneration < Long.MAX_VALUE) { "PCM generation space exhausted" }
        val nextGeneration = currentGeneration + 1L
        return nextGeneration.takeIf {
            nativeFlushToGeneration(requireOpenHandle(), nextGeneration)
        }
    }

    @Synchronized
    fun flush(): Long = tryFlush()
        ?: error(
            "Native output rejected generation flush " +
                "(native=${currentGeneration()})"
        )

    @Synchronized
    fun play() {
        nativeSetPlaying(requireOpenHandle(), true)
    }

    @Synchronized
    fun pause() {
        nativeSetPlaying(requireOpenHandle(), false)
    }

    @Synchronized
    fun setSessionSuspended(suspended: Boolean) = nativeSetSessionSuspended(requireOpenHandle(), suspended)

    /**
     * Pauses callbacks on the existing stream after [pause] has closed the logical render gate.
     * False means the asynchronous platform transition is not settled yet; callers may retry.
     */
    @Synchronized
    fun quiesceIfPaused(): Boolean = nativeQuiesceIfPaused(requireOpenHandle())

    /** Arms the manual-selection gate and captures its output generation in one handle lifetime. */
    @Synchronized
    fun armManualSelectionGateAndGetGeneration(): Long {
        val handle = requireOpenHandle()
        nativeArmManualSelectionGate(handle)
        return nativeCurrentGeneration(handle)
    }

    /**
     * Binds the currently closed gate to one exact non-empty replacement generation.
     *
     * The callback reopens only after it has adopted [generation] with PCM ready. A false result
     * means the gate was not in its unbound CLOSED state; callers must never infer success from a
     * newer producer generation or retry with a different generation without explicitly rearming.
     */
    @Synchronized
    fun authorizeManualSelectionGate(generation: Long): Boolean {
        require(generation > 0L) { "An authorized PCM generation must be positive" }
        return nativeAuthorizeManualSelectionGate(requireOpenHandle(), generation)
    }

    @Synchronized
    fun needsRecovery(): Boolean = nativeNeedsRecovery(requireOpenHandle())

    /**
     * Polls close/reopen acknowledgement before resetting the joined runtime's rings.
     *
     * The owning runtime producer must be stopped and joined before this call. Closing first makes
     * recovery independent of a disconnected callback that can no longer acknowledge an in-flight
     * staged commit. Refill the new generation before calling [play]. Returns false with
     * [NativeAudioDiagnostics.recoveryRequired] set if reopening fails.
     */
    fun reopenForRouteChange(): Boolean {
        if (prepare() != NativeOutputPreparationState.READY) return false
        return synchronized(this) {
            val currentGeneration = currentGeneration()
            check(currentGeneration < Long.MAX_VALUE) { "PCM generation space exhausted" }
            nativeReopen(requireOpenHandle(), currentGeneration + 1L)
        }
    }

    /** Joined producer only: retire the exact sink/consumer, then invalidate both native rings. */
    @Synchronized
    fun retireForStop(newGeneration: Long): Long? {
        val handle = requireOpenHandle()
        val current = nativeCurrentGeneration(handle)
        check(current < Long.MAX_VALUE) { "PCM generation space exhausted" }
        val generation = maxOf(newGeneration, current + 1L)
        audioTrackFeeder?.let { feeder ->
            feeder.requestClose()
            if (!feeder.isRetired) {
                if (stopRetirementDeadlineNanos == 0L) stopRetirementDeadlineNanos = System.nanoTime() + 2_000_000_000L
                check(System.nanoTime() < stopRetirementDeadlineNanos) {
                    "AudioTrack feeder did not retire; native output must remain alive"
                }
                return null
            }
            // The worker has exited; close verifies sink and native-consumer acknowledgements.
            feeder.close()
        }
        check(nativeRetireForStop(handle, generation)) {
            "Native output did not acknowledge its retired consumer before STOP"
        }
        audioTrackFeeder = null
        stopRetirementDeadlineNanos = 0L
        preparationState = NativeOutputPreparationState.UNAVAILABLE
        return generation
    }

    @Synchronized
    fun diagnostics(): NativeAudioDiagnostics =
        NativeAudioDiagnosticsCodec.decode(nativeGetDiagnostics(requireOpenHandle()))
            .copy(physicalOpenAttempted = physicalOpenAttempted)

    /**
     * Reads every valid owner span published after [afterSequence]. Each caller owns its cursor;
     * native keeps no global consumer state, so diagnostics and pressure tests cannot steal data
     * from one another.
     */
    @Synchronized
    fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot {
        require(afterSequence >= 0L)
        return NativeAudioPresentationJournalCodec.decode(
            nativeReadPresentationJournal(requireOpenHandle(), afterSequence)
        )
    }

    @Synchronized
    fun isClosed(): Boolean = nativeHandle == 0L

    /**
     * Borrows the in-process output handle for the native render runtime.
     *
     * The owning NativePlaybackEngine must stop and join that runtime before calling [close].
     * Exposing this only inside the playback-engine package keeps the opaque pointer out of UI,
     * service, decoder, and media-session code.
     */
    @Synchronized
    internal fun borrowNativeHandleForRuntime(): Long = requireOpenHandle()

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        while (retireForStop(0L) == null) LockSupport.parkNanos(500_000L)
        try {
            nativeFinalizeOutput(handle)
            finalObservation?.invoke(runCatching {
                NativeAudioDiagnosticsCodec.decode(nativeGetDiagnostics(handle))
            })
        } finally {
            synchronized(this) {
                audioTrackFeeder = null
                audioTrackFeederFactory = null
                nativeHandle = 0L
                nativeClose(handle)
            }
        }
    }

    private fun requireOpenHandle(): Long = nativeHandle.also { handle ->
        check(handle != 0L) { "Native audio output is closed" }
    }

    private external fun nativeCreate(
        capacityFrames: Int,
        requestedSampleRateHz: Int,
        initialGeneration: Long
    ): Long

    private external fun nativeFlushToGeneration(handle: Long, generation: Long): Boolean
    private external fun nativeCurrentGeneration(handle: Long): Long
    private external fun nativeSetPlaying(handle: Long, playing: Boolean)
    private external fun nativeQuiesceIfPaused(handle: Long): Boolean
    private external fun nativeArmManualSelectionGate(handle: Long)
    private external fun nativeAuthorizeManualSelectionGate(
        handle: Long,
        generation: Long
    ): Boolean
    private external fun nativeNeedsRecovery(handle: Long): Boolean
    private external fun nativeReopen(handle: Long, generation: Long): Boolean
    private external fun nativeRetireForStop(handle: Long, generation: Long): Boolean
    private external fun nativeGetDiagnostics(handle: Long): LongArray
    private external fun nativeFinalizeOutput(handle: Long)
    private external fun nativeReadPresentationJournal(
        handle: Long,
        afterSequence: Long
    ): LongArray
    private external fun nativeClose(handle: Long)
    private external fun nativeSetSessionSuspended(handle: Long, suspended: Boolean)
    @Synchronized
    fun prepareSourceTransition(outgoing: NativeRuntimeOwnership, target: NativeRuntimeOwnership,
        pausedFallbackFrame: Long?): Long? {
        check(outgoing.selectionToken == target.selectionToken && target.generation > outgoing.generation)
        return nativePrepareSourceTransition(nativeHandle, outgoing.generation, outgoing.selectionToken,
            target.generation, pausedFallbackFrame ?: -1L).takeIf { it >= 0L }
    }
    private external fun nativePrepareSourceTransition(handle: Long, outgoingGeneration: Long, token: Long,
        targetGeneration: Long, pausedFallbackFrame: Long): Long
    @Synchronized
    fun cancelSourceTransition(target: NativeRuntimeOwnership) {
        nativeCancelSourceTransition(nativeHandle, target.generation)
    }
    private external fun nativeCancelSourceTransition(handle: Long, targetGeneration: Long)
    private external fun nativeTrackBeginSourceTransition(handle: Long, serial: Long, generation: Long): Boolean
    private external fun nativeTrackPrepareTransportSilence(handle: Long, kind: Int): Long
    private external fun nativeTrackAcceptTransportSilence(handle: Long, sequence: Long, frames: Int): Boolean
    private external fun nativeTrackControl(handle: Long, values: LongArray)
    private external fun nativeTrackAwaitControl(handle: Long, afterSequence: Long)
    private external fun nativeTrackWake(handle: Long)
    private external fun nativeTrackPrepare(handle: Long, pcm: ByteBuffer, metadata: LongArray): Int
    private external fun nativeTrackAccept(handle: Long, sequence: Long, frames: Int): Boolean
    private external fun nativeTrackPrepareWrite(handle: Long, pcm: ByteBuffer, frames: Int, transport: Boolean): Boolean
    private external fun nativeTrackConsumerActive(handle: Long, active: Boolean)
    private external fun nativeTrackDiscard(handle: Long)
    private external fun nativeTrackResetComplete(handle: Long, serial: Long)
    private external fun nativeTrackConfigure(handle: Long, instance: Long, configuration: IntArray)
    private external fun nativeTrackObserve(handle: Long, instance: Long, actual: IntArray, position: LongArray, complete: Boolean)
    private external fun nativeTrackFailed(handle: Long)

    private companion object {
        init {
            System.loadLibrary("stemwise_audio_output")
        }
    }
}
