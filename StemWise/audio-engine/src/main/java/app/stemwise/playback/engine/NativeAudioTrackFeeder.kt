package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** One serialized consumer owns prefill, partial writes and the platform queue. */
internal class NativeAudioTrackFeeder(
    private val engine: Engine,
    private val sinkFactory: () -> Sink,
    private val record: NativeAudioTrackDiagnosticRecord? = null,
    private val park: (Long) -> Unit = LockSupport::parkNanos,
    private val allocateBuffer: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
    private val setAudioPriority: () -> Unit
) : AutoCloseable {
    enum class TransportSilenceKind { LOGICAL_PAUSE, SOURCE_TRANSITION }

    internal interface Engine {
        /** Current generated TRACK_CONTROL packet; one width for every output policy. */
        fun pollControl(values: LongArray)
        fun awaitControlChange(afterSequence: Long): Boolean = false
        fun wake() {}
        /** Returns frames; metadata is [sequence, terminal]. No block is represented by zero. */
        fun prepare(pcm: ByteBuffer, metadata: LongArray): Int
        fun prepareWrite(pcm: ByteBuffer, frames: Int, transport: Boolean) {}
        fun accept(sequence: Long, frames: Int): Boolean
        fun beginSourceTransition(serial: Long, generation: Long): Boolean
        fun prepareTransportSilence(kind: TransportSilenceKind): Long
        fun acceptTransportSilence(sequence: Long, frames: Int): Boolean
        fun consumerActive(active: Boolean)
        fun discard()
        fun resetComplete(serial: Long)
        fun configure(instance: Long, configuration: Configuration)
        /** State/buffer/capacity/rate/performance/underruns; head/timestamp-frame/nanos/submitted. */
        fun observe(instance: Long, actual: IntArray, position: LongArray, complete: Boolean)
        fun failed()
    }

    internal data class Configuration(
        val sampleRate: Int,
        val channels: Int,
        val encoding: Int,
        val bufferFrames: Int,
        val capacityFrames: Int,
        val startThresholdFrames: Int,
        val performanceMode: Int,
        val framesPerBurst: Int
    )

    internal interface Sink : AutoCloseable {
        val configuration: Configuration
        /** Nonblocking byte count. The ByteBuffer position advances exactly as AudioTrack does. */
        fun write(pcm: ByteBuffer): Int
        fun play()
        fun pause()
        /** Negative raw underruns or timestamp frame means unavailable, never a clean zero. */
        fun observe(actual: IntArray, position: LongArray)
    }

    private val closing = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    @Volatile private var nativeConsumerRetired = true
    @Volatile private var sinkRetired = true
    private val thread = Thread(::feed, "StemWiseAudioTrackFeeder").apply { isDaemon = true }

    fun begin() = thread.start()

    @Volatile var sinkOpeningStartedNanos: Long = 0L
        private set
    val isInitialized: Boolean get() = ready.count == 0L
    val isReady: Boolean get() = isInitialized && failure == null && sinkOpeningStartedNanos == 0L
    val isRetired: Boolean get() = !thread.isAlive

    fun start() {
        begin()
        check(ready.await(RETIRE_MILLIS, TimeUnit.MILLISECONDS)) { "AudioTrack feeder did not initialize" }
        failure?.let { throw IllegalStateException("AudioTrack feeder initialization failed", it) }
    }

    fun requestClose() {
        closing.set(true)
        engine.wake()
        LockSupport.unpark(thread)
    }

    override fun close() {
        requestClose()
        thread.join(RETIRE_MILLIS)
        check(!thread.isAlive) { "AudioTrack feeder did not retire; native output must remain alive" }
        check(sinkRetired) { "AudioTrack sink did not acknowledge retirement; native output must remain alive" }
        if (!nativeConsumerRetired) throw IllegalStateException(
            "AudioTrack native consumer did not acknowledge retirement; native output must remain alive", failure)
    }

    val hasFailed: Boolean get() = failure != null

    internal fun failureCause(): Throwable? = failure

    private fun feed() {
        try { feedWithBuffers() }
        catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            try { engine.failed() } catch (reporting: Throwable) {
                if (failure !== reporting) failure?.addSuppressed(reporting)
            }
        } finally { ready.countDown() }
    }

    private fun feedWithBuffers() {
        val pcm = allocateBuffer(BLOCK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.nativeOrder())
        val control = LongArray(NativePacketSchema.TRACK_CONTROL_COUNT)
            .also { it[NativePacketSchema.TRACK_CONTROL_GENERATION] = -1L }
        // Separate immutable zero storage can never overwrite a held source suffix.
        val transportPcm = allocateBuffer(BLOCK_FRAMES * BYTES_PER_FRAME)
            .order(ByteOrder.nativeOrder()).apply { limit(0) }
        val writePcm = allocateBuffer(BLOCK_FRAMES * BYTES_PER_FRAME).order(ByteOrder.nativeOrder())
        val preparedControl = LongArray(NativePacketSchema.TRACK_CONTROL_COUNT)
        var preparedBuffer: ByteBuffer? = null
        var preparedPosition = -1
        var preparedSequence = 0L
        val metadata = LongArray(NativePacketSchema.TRACK_METADATA_COUNT)
        val actual = IntArray(NativePacketSchema.TRACK_ACTUAL_COUNT)
        val position = LongArray(NativePacketSchema.TRACK_POSITION_COUNT)
        var sink: Sink? = null
        var instance = 0L
        var active = false
        var playing = false
        var submitted = 0L
        var resetSerial = 0L
        var firstControlPacket = true
        var transportSequence = 0L
        var transportKind: TransportSilenceKind? = null
        var pcmKind = NativeAudioTrackDiagnosticRecord.PcmKind.NONE
        var recordStarted = false
        var headObservationInstance = -1L
        var rawObservationInstance = -1L
        var headFrames = -1L
        var headSampleNanos = -1L
        var headResetSerial = -1L
        var timestampFrame = -1L
        var timestampNanos = -1L
        var rawUnderruns = -1L
        var rawSampleNanos = -1L
        pcm.limit(0)

        fun recordFailure(error: Throwable) {
            val original = failure
            if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
            try { engine.failed() } catch (reporting: Throwable) {
                if (failure !== reporting) failure?.addSuppressed(reporting)
            }
        }

        fun activateConsumer() {
            // Activation may take effect before its JNI acknowledgement throws.
            nativeConsumerRetired = false
            engine.consumerActive(true)
            active = true
        }

        fun event(operation: NativeAudioTrackDiagnosticRecord.Operation,
            phase: NativeAudioTrackDiagnosticRecord.Phase,
            reason: NativeAudioTrackDiagnosticRecord.ParkReason = NativeAudioTrackDiagnosticRecord.ParkReason.NONE,
            requestedFrames: Long = -1L, resultFrames: Long = -1L, rawResult: Long = -1L) {
            if (!recordStarted) return
            val target = record ?: return
            val now = target.now()
            val headValid = headFrames >= 0L && headFrames <= submitted && headSampleNanos >= 0L &&
                now >= headSampleNanos && headObservationInstance == instance && headResetSerial == resetSerial
            target.append(now, operation, phase, reason, instance, resetSerial, control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL],
                control[NativePacketSchema.TRACK_CONTROL_GENERATION], metadata[NativePacketSchema.TRACK_METADATA_SEQUENCE],
                if (operation == NativeAudioTrackDiagnosticRecord.Operation.PREPARE) 0L else (pcm.remaining() / BYTES_PER_FRAME).toLong(),
                submitted, requestedFrames, resultFrames,
                rawResult, playing, control[NativePacketSchema.TRACK_CONTROL_PLAYING] != 0L, active, headFrames, headSampleNanos,
                if (headSampleNanos >= 0L) now - headSampleNanos else -1L, headValid,
                headResetSerial, timestampFrame, timestampNanos, rawUnderruns, rawSampleNanos,
                rawUnderruns >= 0L && rawObservationInstance == instance, pcmKind,
                (transportPcm.remaining() / BYTES_PER_FRAME).toLong())
        }

        fun serviceWait(reason: NativeAudioTrackDiagnosticRecord.ParkReason) {
            event(NativeAudioTrackDiagnosticRecord.Operation.PARK, NativeAudioTrackDiagnosticRecord.Phase.ENTER, reason)
            // Only a zero write with held PCM proves backpressure; the sampled head can lag consumption.
            val queued = if (playing && reason == NativeAudioTrackDiagnosticRecord.ParkReason.ZERO_PROGRESS)
                sink!!.configuration.bufferFrames.toLong() else 0L
            val minimumWait = if (reason == NativeAudioTrackDiagnosticRecord.ParkReason.EXHAUSTED)
                MAXIMUM_SERVICE_NANOS else SERVICE_NANOS
            val waitNanos = ((queued - BLOCK_FRAMES).coerceAtLeast(0L) * 1_000_000_000L / SAMPLE_RATE)
                .coerceIn(minimumWait, MAXIMUM_SERVICE_NANOS)
            try { park(waitNanos) }
            finally { event(NativeAudioTrackDiagnosticRecord.Operation.PARK, NativeAudioTrackDiagnosticRecord.Phase.EXIT, reason) }
        }

        fun publishObservation(complete: Boolean) {
            actual.fill(-1)
            position.fill(-1L)
            val observationEnteredNanos = if (recordStarted) record!!.now() else -1L
            event(NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE, NativeAudioTrackDiagnosticRecord.Phase.ENTER)
            var observed = false
            try {
                sink?.observe(actual, position)
                observed = true
            } finally {
                if (complete && (playing || !nativeConsumerRetired)) actual[NativePacketSchema.TRACK_ACTUAL_UNDERRUNS] = -1
                headObservationInstance = instance
                headFrames = position[NativePacketSchema.TRACK_POSITION_HEAD]
                headResetSerial = control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL]
                if (recordStarted) {
                    val sampleTime = record!!.now()
                    rawObservationInstance = instance
                    headSampleNanos = observationEnteredNanos
                    timestampFrame = position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_FRAME]
                    timestampNanos = position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_NANOS]
                    rawUnderruns = actual[NativePacketSchema.TRACK_ACTUAL_UNDERRUNS].toLong()
                    rawSampleNanos = sampleTime
                }
                event(NativeAudioTrackDiagnosticRecord.Operation.PLATFORM_OBSERVE,
                    NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = if (observed) 1L else 0L)
                position[NativePacketSchema.TRACK_POSITION_SUBMITTED] = submitted
                // A timestamp before play cannot authorize presentation of prefilled source PCM.
                if (!playing) { position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_FRAME] = -1L; position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_NANOS] = -1L }
                timestampFrame = position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_FRAME]
                timestampNanos = position[NativePacketSchema.TRACK_POSITION_TIMESTAMP_NANOS]
                event(NativeAudioTrackDiagnosticRecord.Operation.NATIVE_PUBLICATION,
                    NativeAudioTrackDiagnosticRecord.Phase.ENTER, rawResult = if (complete) 1L else 0L)
                try { engine.observe(instance, actual, position, complete) }
                finally { event(NativeAudioTrackDiagnosticRecord.Operation.NATIVE_PUBLICATION,
                    NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = if (complete) 1L else 0L) }
            }
        }

        fun writePending(buffer: ByteBuffer, transport: Boolean): Int {
            if (!buffer.hasRemaining()) return 0
            val before = buffer.position()
            val available = buffer.remaining()
            event(NativeAudioTrackDiagnosticRecord.Operation.WRITE, NativeAudioTrackDiagnosticRecord.Phase.ENTER,
                requestedFrames = (available / BYTES_PER_FRAME).toLong())
            val sequence = if (transport) transportSequence else metadata[NativePacketSchema.TRACK_METADATA_SEQUENCE]
            if (preparedBuffer !== buffer || preparedPosition != before || preparedSequence != sequence ||
                !preparedControl.contentEquals(control)) {
                writePcm.clear()
                try { writePcm.put(buffer) } finally { buffer.position(before) }
                writePcm.flip()
                engine.prepareWrite(writePcm, available / BYTES_PER_FRAME, transport)
                preparedBuffer = buffer
                preparedPosition = before
                preparedSequence = sequence
                control.copyInto(preparedControl)
            }
            var bytes = Int.MIN_VALUE
            try { bytes = sink!!.write(writePcm) }
            finally { event(NativeAudioTrackDiagnosticRecord.Operation.WRITE, NativeAudioTrackDiagnosticRecord.Phase.EXIT,
                requestedFrames = (available / BYTES_PER_FRAME).toLong(),
                resultFrames = if (bytes >= 0 && bytes % BYTES_PER_FRAME == 0) (bytes / BYTES_PER_FRAME).toLong() else -1L,
                rawResult = bytes.toLong()) }
            check(bytes >= 0 && bytes <= available && bytes % BYTES_PER_FRAME == 0 &&
                writePcm.position() == bytes) { "Invalid AudioTrack write receipt: $bytes" }
            if (bytes == 0) return 0
            // An accepted prefix advances native envelope state; prepare its exact suffix again.
            preparedBuffer = null
            buffer.position(before + bytes)
            val operation = if (transport) NativeAudioTrackDiagnosticRecord.Operation.TRANSPORT_ACCEPT
                else NativeAudioTrackDiagnosticRecord.Operation.ACCEPT
            event(operation, NativeAudioTrackDiagnosticRecord.Phase.ENTER,
                requestedFrames = (bytes / BYTES_PER_FRAME).toLong())
            var accepted = false
            var acceptedFrames = 0
            try {
                accepted = if (transport) engine.acceptTransportSilence(transportSequence, bytes / BYTES_PER_FRAME)
                    else engine.accept(metadata[NativePacketSchema.TRACK_METADATA_SEQUENCE], bytes / BYTES_PER_FRAME)
                if (accepted) { submitted += bytes / BYTES_PER_FRAME; acceptedFrames = bytes / BYTES_PER_FRAME }
            } finally { event(operation, NativeAudioTrackDiagnosticRecord.Phase.EXIT,
                requestedFrames = (bytes / BYTES_PER_FRAME).toLong(), resultFrames = acceptedFrames.toLong(),
                rawResult = if (accepted) 1L else 0L) }
            check(accepted) { "Native output rejected the exact accepted PCM prefix" }
            return acceptedFrames
        }

        fun pauseConsumer() {
            var pauseFailure: Throwable? = null
            try {
                if (playing) {
                    event(NativeAudioTrackDiagnosticRecord.Operation.PAUSE, NativeAudioTrackDiagnosticRecord.Phase.ENTER)
                    try { sink!!.pause(); playing = false }
                    finally { event(NativeAudioTrackDiagnosticRecord.Operation.PAUSE, NativeAudioTrackDiagnosticRecord.Phase.EXIT) }
                }
            } catch (error: Throwable) { pauseFailure = error }
            if (active || !nativeConsumerRetired) {
                event(NativeAudioTrackDiagnosticRecord.Operation.CONSUMER, NativeAudioTrackDiagnosticRecord.Phase.ENTER, rawResult = 0L)
                try { engine.consumerActive(false); active = false; nativeConsumerRetired = true }
                catch (error: Throwable) {
                    if (pauseFailure == null) pauseFailure = error else if (pauseFailure !== error) pauseFailure.addSuppressed(error)
                }
                finally { event(NativeAudioTrackDiagnosticRecord.Operation.CONSUMER, NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = 0L) }
            }
            pauseFailure?.let { throw it }
        }

        fun openSink() {
            sinkOpeningStartedNanos = System.nanoTime()
            sink = sinkFactory()
            sinkRetired = false
            instance = nextTrackInstance.incrementAndGet()
            check(instance > 0L) { "AudioTrack instance identity space exhausted" }
            val configuration = sink!!.configuration
            check(configuration.sampleRate == SAMPLE_RATE && configuration.channels == CHANNELS &&
                configuration.bufferFrames in BLOCK_FRAMES..MAXIMUM_BUFFER_FRAMES &&
                configuration.bufferFrames <= configuration.capacityFrames &&
                configuration.startThresholdFrames in 1..configuration.bufferFrames &&
                configuration.performanceMode in 0..1) {
                "AudioTrack did not select a supported format/buffer/threshold/performance mode"
            }
            engine.configure(instance, configuration)
            publishObservation(false)
            sinkOpeningStartedNanos = 0L
        }

        try {
            record?.begin()
            recordStarted = record != null
            setAudioPriority()
            openSink()
            ready.countDown()
            while (!closing.get()) {
                engine.pollControl(control)
                check(control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL] >= resetSerial) { "AudioTrack reset serial moved backwards" }
                if (firstControlPacket) {
                    firstControlPacket = false
                    // A fresh feeder inherits the monotonic identity of a retired request only.
                    // An action/target still pending in this exact snapshot must execute below.
                    if (control[NativePacketSchema.TRACK_CONTROL_RESET_ACTION] == 0L &&
                        control[NativePacketSchema.TRACK_CONTROL_SOURCE_TARGET] == 0L) {
                        resetSerial = control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL]
                    }
                }
                pcmKind = NativeAudioTrackDiagnosticRecord.PcmKind.NONE
                if (control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL] != resetSerial) {
                    if (control[NativePacketSchema.TRACK_CONTROL_RESET_ACTION] == 0L) {
                        resetSerial = control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL]
                        continue
                    }
                    if (control[NativePacketSchema.TRACK_CONTROL_RESET_ACTION] == 2L) {
                        check(control[NativePacketSchema.TRACK_CONTROL_SOURCE_TARGET] > 0L) { "A source transition requires its exact target generation" }
                        if (!active) activateConsumer()
                        event(NativeAudioTrackDiagnosticRecord.Operation.SOURCE_TRANSITION,
                            NativeAudioTrackDiagnosticRecord.Phase.ENTER, rawResult = control[NativePacketSchema.TRACK_CONTROL_SOURCE_TARGET])
                        var accepted = false
                        try {
                            accepted = engine.beginSourceTransition(control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL], control[NativePacketSchema.TRACK_CONTROL_SOURCE_TARGET])
                            if (accepted) {
                                // Native retains the old source authority until replacement commits.
                                // This copied suffix is no longer eligible for a future Java write.
                                pcm.limit(0)
                                metadata.fill(0L)
                                transportPcm.limit(0)
                                transportSequence = 0L
                                transportKind = null
                                resetSerial = control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL]
                            }
                        } finally { event(NativeAudioTrackDiagnosticRecord.Operation.SOURCE_TRANSITION,
                            NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = if (accepted) 1L else 0L) }
                        if (!accepted) {
                            publishObservation(false)
                            serviceWait(NativeAudioTrackDiagnosticRecord.ParkReason.CURRENT)
                        } else if (control[NativePacketSchema.TRACK_CONTROL_SESSION_ACTIVE] == 0L) pauseConsumer()
                        continue
                    }
                    pauseConsumer()
                    event(NativeAudioTrackDiagnosticRecord.Operation.DISCARD, NativeAudioTrackDiagnosticRecord.Phase.ENTER)
                    try { engine.discard() }
                    finally { event(NativeAudioTrackDiagnosticRecord.Operation.DISCARD, NativeAudioTrackDiagnosticRecord.Phase.EXIT) }
                    pcm.limit(0)
                    transportPcm.limit(0)
                    transportSequence = 0L
                    transportKind = null
                    check(control[NativePacketSchema.TRACK_CONTROL_RESET_ACTION] == 1L) { "Invalid AudioTrack reset action" }
                    publishObservation(true)
                    sink!!.close()
                    sinkRetired = true
                    sink = null
                    submitted = 0L
                    openSink()
                    resetSerial = control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL]
                    event(NativeAudioTrackDiagnosticRecord.Operation.RESET_COMPLETE, NativeAudioTrackDiagnosticRecord.Phase.ENTER)
                    try { engine.resetComplete(resetSerial) }
                    finally { event(NativeAudioTrackDiagnosticRecord.Operation.RESET_COMPLETE, NativeAudioTrackDiagnosticRecord.Phase.EXIT) }
                    continue
                }
                val sessionActive = control[NativePacketSchema.TRACK_CONTROL_SESSION_ACTIVE] != 0L
                if (!sessionActive) {
                    pauseConsumer()
                    publishObservation(false)
                    event(NativeAudioTrackDiagnosticRecord.Operation.PARK, NativeAudioTrackDiagnosticRecord.Phase.ENTER,
                        NativeAudioTrackDiagnosticRecord.ParkReason.IDLE)
                    if (!closing.get() && !engine.awaitControlChange(control[NativePacketSchema.TRACK_CONTROL_WAKE_SEQUENCE])) park(SERVICE_NANOS)
                    event(NativeAudioTrackDiagnosticRecord.Operation.PARK, NativeAudioTrackDiagnosticRecord.Phase.EXIT,
                        NativeAudioTrackDiagnosticRecord.ParkReason.IDLE)
                    continue
                }
                if (!active) {
                    event(NativeAudioTrackDiagnosticRecord.Operation.CONSUMER, NativeAudioTrackDiagnosticRecord.Phase.ENTER, rawResult = 1L)
                    try { activateConsumer() }
                    finally { event(NativeAudioTrackDiagnosticRecord.Operation.CONSUMER, NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = 1L) }
                }
                val transition = control[NativePacketSchema.TRACK_CONTROL_SOURCE_TARGET] != 0L
                val transport = (transition || control[NativePacketSchema.TRACK_CONTROL_PLAYING] == 0L)
                val acceptedFrames: Int
                if (transport) {
                    val kind = if (transition) TransportSilenceKind.SOURCE_TRANSITION else TransportSilenceKind.LOGICAL_PAUSE
                    pcmKind = if (transition) NativeAudioTrackDiagnosticRecord.PcmKind.SOURCE_TRANSITION_SILENCE
                        else NativeAudioTrackDiagnosticRecord.PcmKind.LOGICAL_PAUSE_SILENCE
                    if (transportKind != kind) { transportPcm.limit(0); transportSequence = 0L }
                    if (!transportPcm.hasRemaining()) {
                        event(NativeAudioTrackDiagnosticRecord.Operation.TRANSPORT_PREPARE,
                            NativeAudioTrackDiagnosticRecord.Phase.ENTER, requestedFrames = BLOCK_FRAMES.toLong())
                        var sequence = 0L
                        try { sequence = engine.prepareTransportSilence(kind) }
                        finally { event(NativeAudioTrackDiagnosticRecord.Operation.TRANSPORT_PREPARE,
                            NativeAudioTrackDiagnosticRecord.Phase.EXIT, rawResult = sequence) }
                        check(sequence >= 0L) { "Invalid transport-silence authorization" }
                        transportSequence = sequence
                        transportKind = kind
                        if (sequence > 0L) transportPcm.clear() else transportPcm.limit(0)
                    }
                    acceptedFrames = writePending(transportPcm, true)
                } else {
                    // Unaccepted silence is replaceable; accepted silence remains in the sink ledger.
                    transportPcm.limit(0)
                    transportSequence = 0L
                    transportKind = null
                    pcmKind = NativeAudioTrackDiagnosticRecord.PcmKind.SOURCE_PREPARED
                    if (!pcm.hasRemaining()) {
                        pcm.clear()
                        metadata.fill(0L)
                        event(NativeAudioTrackDiagnosticRecord.Operation.PREPARE, NativeAudioTrackDiagnosticRecord.Phase.ENTER,
                            requestedFrames = BLOCK_FRAMES.toLong())
                        var frames = -1
                        try { frames = engine.prepare(pcm, metadata) }
                        finally { event(NativeAudioTrackDiagnosticRecord.Operation.PREPARE, NativeAudioTrackDiagnosticRecord.Phase.EXIT,
                            requestedFrames = BLOCK_FRAMES.toLong(), resultFrames = frames.toLong()) }
                        check(frames in 0..BLOCK_FRAMES) { "Invalid native prepared frame count: $frames" }
                        check(frames == 0 || metadata[NativePacketSchema.TRACK_METADATA_SEQUENCE] > 0L) { "Prepared PCM has no exact sequence" }
                        pcm.position(0)
                        pcm.limit(frames * BYTES_PER_FRAME)
                    }
                    acceptedFrames = writePending(pcm, false)
                }
                // A paused playback head can advance while this feeder sleeps.
                publishObservation(false)
                if (!playing) {
                    val queued = if (headObservationInstance == instance && headResetSerial == resetSerial &&
                        headFrames in 0L..submitted) submitted - headFrames else -1L
                    if (queued >= sink!!.configuration.bufferFrames) {
                        // Recheck after the write so cancelled prefill cannot start a stale owner.
                        engine.pollControl(control)
                        val mayStart = control[NativePacketSchema.TRACK_CONTROL_SESSION_ACTIVE] != 0L
                        if (mayStart && control[NativePacketSchema.TRACK_CONTROL_RESET_SERIAL] == resetSerial && !closing.get()) {
                            event(NativeAudioTrackDiagnosticRecord.Operation.PLAY, NativeAudioTrackDiagnosticRecord.Phase.ENTER)
                            try { sink.play(); playing = true }
                            finally { event(NativeAudioTrackDiagnosticRecord.Operation.PLAY, NativeAudioTrackDiagnosticRecord.Phase.EXIT) }
                            // Play can spend the device reserve before returning. Re-poll the exact
                            // controls and refill before potentially blocking platform observations.
                            continue
                        }
                    }
                }
                val reason = when {
                    acceptedFrames == 0 && !(if (transport) transportPcm.hasRemaining() else pcm.hasRemaining()) -> NativeAudioTrackDiagnosticRecord.ParkReason.EXHAUSTED
                    acceptedFrames == 0 -> NativeAudioTrackDiagnosticRecord.ParkReason.ZERO_PROGRESS
                    else -> NativeAudioTrackDiagnosticRecord.ParkReason.CURRENT
                }
                serviceWait(reason)
            }
        } catch (error: Throwable) {
            recordFailure(error)
        } finally {
            ready.countDown()
            try { pauseConsumer() } catch (error: Throwable) { recordFailure(error) }
            if (nativeConsumerRetired) try { engine.discard() } catch (error: Throwable) { recordFailure(error) }
            try { if (sink != null) publishObservation(true) } catch (error: Throwable) { recordFailure(error) }
            try { sink?.close(); sinkRetired = true } catch (error: Throwable) { recordFailure(error) }
            finally { if (recordStarted) record!!.finish() }
        }
    }

    internal companion object {
        private val nextTrackInstance = AtomicLong(0L)
        const val SAMPLE_RATE = NativePacketSchema.TRACK_SAMPLE_RATE
        const val CHANNELS = NativePacketSchema.TRACK_CHANNELS
        const val BLOCK_FRAMES = NativePacketSchema.TRACK_BLOCK_FRAMES
        const val MAXIMUM_BUFFER_FRAMES = NativePacketSchema.TRACK_MAXIMUM_BUFFER_FRAMES // At most 100 ms of queued output at 48 kHz.
        const val BYTES_PER_FRAME = CHANNELS * Float.SIZE_BYTES
        private const val SERVICE_NANOS = 500_000L
        private const val MAXIMUM_SERVICE_NANOS = 5_000_000L
        private const val RETIRE_MILLIS = 2_000L
    }
}
