package app.stemwise.playback.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Looper
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

enum class NativePcmEncoding(
    val androidEncoding: Int,
    val bytesPerSample: Int
) {
    PCM_16_BIT(AudioFormat.ENCODING_PCM_16BIT, Short.SIZE_BYTES),
    PCM_FLOAT(AudioFormat.ENCODING_PCM_FLOAT, Float.SIZE_BYTES);

    companion object {
        fun fromAndroidEncoding(value: Int): NativePcmEncoding? = when (value) {
            AudioFormat.ENCODING_DEFAULT,
            AudioFormat.ENCODING_PCM_16BIT -> PCM_16_BIT
            AudioFormat.ENCODING_PCM_FLOAT -> PCM_FLOAT
            else -> null
        }
    }
}

/** Complete interpretation of every byte offered to [NativePcmSink]. */
data class NativePcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: NativePcmEncoding
) {
    init {
        require(sampleRateHz in 8_000..384_000) { "Unsupported PCM sample rate" }
        require(channelCount in 1..2) {
            "The native engine accepts mono or stereo PCM; downmixing is not implicit"
        }
    }

    val bytesPerFrame: Int = channelCount * encoding.bytesPerSample
    val byteOrder: ByteOrder = ByteOrder.nativeOrder()
}

data class NativeAudioTrackInfo(
    val trackIndex: Int,
    val mimeType: String,
    val language: String?,
    val durationUs: Long?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val encoderDelayFrames: Int,
    val encoderPaddingFrames: Int
) {
    init {
        require(trackIndex >= 0)
        require(mimeType.startsWith("audio/", ignoreCase = true))
        require(encoderDelayFrames >= 0)
        require(encoderPaddingFrames >= 0)
    }
}

/**
 * A decoder-owned PCM view. [data] is read-only and valid only until [NativePcmSink.offer]
 * returns. The bytes have already been copied out of MediaCodec, so the codec output buffer is
 * never exposed to native/render code.
 *
 * [presentationTimeUs] is the normalized audible timeline timestamp of the first byte in [data].
 * It excludes encoder delay, starts at zero even for negative/offset container timestamps, and is
 * advanced by the exact accepted frame count when a sink partially accepts a block.
 * [firstTimelineFrame] is that same position in the exact decoded source-rate frame domain; EOS
 * accounting must use it instead of converting the microsecond timestamp back into frames.
 */
internal class NativePcmBlock internal constructor() {
    private lateinit var currentData: ByteBuffer
    private lateinit var currentFormat: NativePcmFormat
    private var currentPresentationTimeUs: Long = 0L
    private var currentFirstTimelineFrame: Long = 0L
    private var currentSeekGeneration: Long = 0L

    val data: ByteBuffer get() = currentData
    val format: NativePcmFormat get() = currentFormat
    val presentationTimeUs: Long get() = currentPresentationTimeUs
    /** Exact audible source-rate frame; never reconstructed from the rounded timestamp. */
    val firstTimelineFrame: Long get() = currentFirstTimelineFrame
    val seekGeneration: Long get() = currentSeekGeneration
    val frameCount: Int get() = data.remaining() / format.bytesPerFrame

    internal fun reset(
        data: ByteBuffer,
        format: NativePcmFormat,
        presentationTimeUs: Long,
        seekGeneration: Long,
        firstTimelineFrame: Long
    ): NativePcmBlock {
        require(data.isReadOnly)
        require(data.order() == format.byteOrder)
        require(data.remaining() > 0)
        require(data.remaining() % format.bytesPerFrame == 0)
        require(presentationTimeUs >= 0L)
        require(seekGeneration >= 0L)
        require(firstTimelineFrame >= 0L)
        currentData = data
        currentFormat = format
        currentPresentationTimeUs = presentationTimeUs
        currentFirstTimelineFrame = firstTimelineFrame
        currentSeekGeneration = seekGeneration
        return this
    }
}

/**
 * Result of one non-blocking sink offer. Zero or partial acceptance applies backpressure without
 * retaining more codec output. [retryDelayUs] is capped by decoder configuration.
 */
@JvmInline
internal value class NativePcmSinkResult private constructor(private val packed: Long) {
    val acceptedBytes: Int get() = (packed and UINT_MASK).toInt()
    val retryDelayUs: Long get() = (packed ushr Int.SIZE_BITS) and RETRY_MASK
    val waitsForSignal: Boolean get() = packed and WAIT_FOR_SIGNAL_FLAG != 0L
    val reportsPermanentStall: Boolean get() = packed and PERMANENT_STALL_FLAG != 0L

    companion object {
        const val DEFAULT_RETRY_DELAY_US = 1_000L
        private const val UINT_MASK = 0xffff_ffffL
        private const val RETRY_MASK = 0x3fff_ffffL
        private const val PERMANENT_STALL_FLAG = 1L shl 62
        private const val WAIT_FOR_SIGNAL_FLAG = Long.MIN_VALUE

        fun accepting(
            acceptedBytes: Int,
            retryDelayUs: Long = DEFAULT_RETRY_DELAY_US
        ): NativePcmSinkResult {
            require(acceptedBytes >= 0)
            require(retryDelayUs in 0L..RETRY_MASK)
            return NativePcmSinkResult(
                (retryDelayUs shl Int.SIZE_BITS) or acceptedBytes.toLong()
            )
        }

        fun all(block: NativePcmBlock) = accepting(block.data.remaining(), 0L)

        /** Normal paused/full-queue backpressure, resumed by signalSinkWritable(). */
        fun backpressuredUntilSignal(): NativePcmSinkResult =
            NativePcmSinkResult(WAIT_FOR_SIGNAL_FLAG)

        /** Explicit sink liveness failure; elapsed pause time never creates this result. */
        fun permanentlyStalled(): NativePcmSinkResult =
            NativePcmSinkResult(PERMANENT_STALL_FLAG)
    }
}

/** Called synchronously on the decoder worker. It must not retain [NativePcmBlock.data]. */
internal fun interface NativePcmSink {
    fun offer(block: NativePcmBlock): NativePcmSinkResult
}

enum class NativeDecoderState {
    IDLE,
    OPENING,
    DECODING,
    ENDED,
    CANCELLED,
    FAILED,
    CLOSED
}

enum class NativeDecoderErrorCode {
    WORKER_REJECTED,
    MAIN_THREAD_VIOLATION,
    SOURCE_OPEN_FAILED,
    NO_AUDIO_TRACK,
    UNSUPPORTED_CODEC,
    CODEC_CONFIGURATION_FAILED,
    CODEC_RUNTIME_FAILED,
    CODEC_STALLED,
    UNSUPPORTED_PCM_FORMAT,
    OUTPUT_BUFFER_TOO_LARGE,
    MALFORMED_PCM_BUFFER,
    TIMESTAMP_DISCONTINUITY,
    SINK_PROTOCOL_VIOLATION,
    SINK_FAILED,
    SINK_STALLED
}

/** One bounded terminal diagnostic, retained outside callback-rate logging. */
internal class NativeDecoderFailure(
    val code: NativeDecoderErrorCode,
    val decoderId: String,
    val laneId: String,
    message: String,
    cause: Throwable? = null
) : Exception(message) {
    val causeDiagnostic: String? = cause?.let { error ->
        buildString {
            var current: Throwable? = error
            repeat(3) {
                val detail = current ?: return@repeat
                append(detail.javaClass.name).append(": ").append(detail.message.orEmpty().take(512))
                if (detail is MediaCodec.CodecException) append(" [").append(detail.diagnosticInfo).append(']')
                detail.stackTrace.take(8).forEach { append("\n at ").append(it) }
                append('\n')
                current = detail.cause?.takeUnless { it === detail }
            }
        }.take(4096)
    }
}

internal data class NativeDecoderPrepared(
    val decoderId: String,
    val laneId: String,
    val source: String,
    val codecName: String,
    val track: NativeAudioTrackInfo,
    val trackStartPresentationTimeUs: Long
)

data class NativeDecoderDiagnostics(
    val decoderId: String,
    val laneId: String,
    val source: String,
    val state: NativeDecoderState,
    val workerThreadName: String?,
    val codecName: String?,
    val selectedTrack: NativeAudioTrackInfo?,
    val outputFormat: NativePcmFormat?,
    val requestedSeekGeneration: Long,
    val activeSeekGeneration: Long,
    val inputBufferCount: Long,
    val inputBytes: Long,
    val outputBufferCount: Long,
    val copiedOutputBytes: Long,
    val deliveredBytes: Long,
    val discardedLeadingFrames: Long,
    val discardedPaddingFrames: Long,
    val sinkRetryCount: Long,
    val sinkSignalParkCount: Long,
    val outputFormatChangeCount: Long,
    val seekCount: Long,
    val timestampCorrectionCount: Long,
    val maximumTimestampCorrectionFrames: Long,
    val codecTimestampOffsetFrames: Long?,
    val extractorStartPresentationTimeUs: Long?,
    val firstCodecOutputPresentationTimeUs: Long?,
    val decodedAbsoluteFirstFrame: Long?,
    val decodedAbsoluteEndFrameExclusive: Long?,
    val lastPresentationTimeUs: Long?,
    val lastErrorCode: NativeDecoderErrorCode?,
    val lastErrorMessage: String?
)

internal interface NativeMediaCodecDecoderListener {
    fun onPrepared(prepared: NativeDecoderPrepared) = Unit
    fun onOutputFormatChanged(format: NativePcmFormat, seekGeneration: Long) = Unit
    fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) = Unit
    fun onCancelled(seekGeneration: Long) = Unit
    fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) = Unit

    data object None : NativeMediaCodecDecoderListener
}

internal data class MediaCodecTrackDecoderConfig(
    val initialPositionUs: Long = 0L,
    val seekPrerollUs: Long = 250_000L,
    val codecPollTimeoutUs: Long = 5_000L,
    val codecProgressTimeoutMs: Long = 15_000L,
    val initialCopyBufferBytes: Int = 64 * 1024,
    val maximumCodecOutputBufferBytes: Int = 4 * 1024 * 1024,
    val maximumGaplessTrimFrames: Int = 131_072,
    val maximumSinkRetryDelayUs: Long = 20_000L,
    val retainResamplerHistory: Boolean = false
) {
    init {
        require(initialPositionUs >= 0L)
        require(seekPrerollUs in 0L..2_000_000L)
        require(codecPollTimeoutUs in 500L..50_000L)
        require(codecProgressTimeoutMs in 100L..60_000L)
        require(initialCopyBufferBytes in 4 * 1024..maximumCodecOutputBufferBytes)
        require(maximumCodecOutputBufferBytes in initialCopyBufferBytes..16 * 1024 * 1024)
        require(maximumGaplessTrimFrames in 0..1_048_576)
        require(maximumSinkRetryDelayUs in 100L..100_000L)
    }

    fun outputStartPositionUs(positionUs: Long, sampleRateHz: Int?): Long =
        if (!retainResamplerHistory || sampleRateHz == 48_000) positionUs
        // Integer-second origins preserve every supported integer-rate SRC phase; the preceding
        // second supplies real finite-filter history before the exact native requested cursor.
        else (positionUs / 1_000_000L - 1L).coerceAtLeast(0L) * 1_000_000L
}

/** Worker-owned clock; intentional sink/EOS parking rebases rather than spending the deadline. */
internal class NativeCodecProgressDeadline(timeoutMs: Long) {
    private val timeoutNanos = timeoutMs * 1_000_000L
    private var lastProgressNanos = 0L

    fun reset(nowNanos: Long) { lastProgressNanos = nowNanos }
    fun expired(nowNanos: Long): Boolean = nowNanos - lastProgressNanos >= timeoutNanos
}

/**
 * Fixed-size worker pool for long-lived synchronous MediaCodec pumps. Its queue is bounded too,
 * so creating more decoders than the engine's fixed song-bank topology fails deterministically.
 */
data class NativeDecoderWorkerPoolDiagnostics(
    val runningTaskCount: Int,
    val queuedTaskCount: Int,
    val liveWorkerCount: Int,
    val completedTaskCount: Long
)

internal class NativeDecoderWorkerPool private constructor(
    private val executor: ThreadPoolExecutor,
    private val owned: Boolean
) : Closeable {
    fun submit(task: Runnable): Future<*> = executor.submit(task)

    /**
     * Removes cancelled tasks that never reached executor ownership.
     *
     * [ThreadPoolExecutor] retains a cancelled queued [Future] until a worker dequeues it. A
     * one-worker/one-slot pool can therefore reject the next exact decoder even after that cancelled
     * decoder has synchronously joined. Resource owners call this only after [MediaCodecTrackDecoder]
     * has proved its worker closed, so no live task is removed or inferred complete.
     */
    fun purgeCancelledTasks() = executor.purge()

    fun diagnostics(): NativeDecoderWorkerPoolDiagnostics = NativeDecoderWorkerPoolDiagnostics(
        runningTaskCount = executor.activeCount,
        queuedTaskCount = executor.queue.size,
        liveWorkerCount = executor.poolSize,
        completedTaskCount = executor.completedTaskCount
    )

    override fun close() {
        if (owned) executor.shutdownNow()
    }

    companion object {
        // Each admitted decoder runs a persistent pump, so queued pumps cannot borrow busy
        // workers. Threads start lazily and retire when idle; the lease budget limits concurrency.
        private const val SHARED_WORKERS = NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS
        private const val SHARED_QUEUE_CAPACITY = NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS
        private val threadSequence = AtomicInteger(0)

        val shared: NativeDecoderWorkerPool by lazy {
            create(
                workerCount = SHARED_WORKERS,
                queueCapacity = SHARED_QUEUE_CAPACITY,
                namePrefix = "StemWise-Codec",
                owned = false
            )
        }

        fun dedicated(
            name: String,
            workerCount: Int = 1,
            queueCapacity: Int = workerCount
        ): NativeDecoderWorkerPool {
            require(name.isNotBlank())
            return create(workerCount, queueCapacity, "StemWise-$name", owned = true)
        }

        private fun create(
            workerCount: Int,
            queueCapacity: Int,
            namePrefix: String,
            owned: Boolean
        ): NativeDecoderWorkerPool {
            require(workerCount in 1..NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS)
            require(queueCapacity in 1..32)
            val executor = ThreadPoolExecutor(
                workerCount,
                workerCount,
                30L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(queueCapacity),
                { runnable ->
                    Thread(
                        runnable,
                        "$namePrefix-${threadSequence.incrementAndGet()}"
                    ).apply { isDaemon = true }
                },
                ThreadPoolExecutor.AbortPolicy()
            ).apply { allowCoreThreadTimeOut(true) }
            return NativeDecoderWorkerPool(executor, owned)
        }
    }
}

/**
 * One-track MediaExtractor/MediaCodec PCM pump for the native engine.
 *
 * The class has exactly one worker task, one copied codec buffer, and one
 * bounded delivery block. Seeking is latest-generation-wins. It never invokes callbacks on Main and
 * never performs per-sample JNI/callback work.
 */
internal class MediaCodecTrackDecoder(
    context: Context,
    val decoderId: String,
    val laneId: String,
    private val source: NativeMediaSource,
    private val sink: NativePcmSink,
    private val listener: NativeMediaCodecDecoderListener = NativeMediaCodecDecoderListener.None,
    private val config: MediaCodecTrackDecoderConfig = MediaCodecTrackDecoderConfig(),
    private val workerPool: NativeDecoderWorkerPool = NativeDecoderWorkerPool.shared,
    /** Test-only barrier after executor ownership is published but before codec work begins. */
    private val workerStartedHook: (() -> Unit)? = null,
    private val packetReaderFactory: AudioPacketReaderFactory
) : Closeable {
    private val applicationContext = context.applicationContext
    private val state = AtomicReference(NativeDecoderState.IDLE)
    private val stopRequested = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val requestedSeek = AtomicReference(SeekRequest(0L, config.initialPositionUs))
    private val finished = CountDownLatch(1)
    private val finishedSignalled = AtomicBoolean(false)
    private val cancellationNotified = AtomicBoolean(false)
    private val future = AtomicReference<Future<*>?>(null)
    private val workerThread = AtomicReference<Thread?>(null)
    private val sinkRetryThrottled = AtomicBoolean(false)
    private val activeSourceOpen = AtomicReference<CancellationSignal?>(null)
    /**
     * Serializes executor entry with cancellation before [runWorker] publishes its thread.
     *
     * A Future can already be running while [workerThread] is still null. Treating that null as
     * proof that cancellation completed lets [awaitClosed] return while MediaCodec teardown still
     * occupies a shared worker. Rapid selections can then exhaust the worker budget even though
     * the decoder-lease accounting claims they were joined. A task may therefore be completed
     * early only after this gate has prevented its wrapper from entering codec work.
     */
    private val workerLifecycleGate = Any()
    private var workerTaskStarted = false
    private var cancelledBeforeWorkerStart = false

    private val activeGeneration = AtomicLong(-1L)
    private val inputBufferCount = AtomicLong(0L)
    private val inputBytes = AtomicLong(0L)
    private val outputBufferCount = AtomicLong(0L)
    private val copiedOutputBytes = AtomicLong(0L)
    private val deliveredBytes = AtomicLong(0L)
    private val discardedLeadingFrames = AtomicLong(0L)
    private val discardedPaddingFrames = AtomicLong(0L)
    private val sinkRetryCount = AtomicLong(0L)
    private val sinkSignalParkCount = AtomicLong(0L)
    private val outputFormatChangeCount = AtomicLong(0L)
    private val seekCount = AtomicLong(0L)
    private val timestampCorrectionCount = AtomicLong(0L)
    private val maximumTimestampCorrectionFrames = AtomicLong(0L)

    @Volatile private var workerThreadName: String? = null
    @Volatile private var codecName: String? = null
    @Volatile private var selectedTrack: NativeAudioTrackInfo? = null
    private var requiresFloatPcm = false
    @Volatile private var currentOutputFormat: NativePcmFormat? = null
    @Volatile private var lastPresentationTimeUs: Long? = null
    @Volatile private var codecTimestampOffsetFrames: Long? = null
    @Volatile private var extractorStartPresentationTimeUs: Long? = null
    @Volatile private var firstCodecOutputPresentationTimeUs: Long? = null
    @Volatile private var decodedAbsoluteFirstFrame: Long? = null
    @Volatile private var decodedAbsoluteEndFrameExclusive: Long? = null
    @Volatile private var lastErrorCode: NativeDecoderErrorCode? = null
    @Volatile private var lastErrorMessage: String? = null

    init {
        require(decoderId.isNotBlank())
        require(laneId.isNotBlank())
    }

    /** Starts asynchronously. False means this instance had already been started or closed. */
    fun start(): Boolean {
        if (!state.compareAndSet(NativeDecoderState.IDLE, NativeDecoderState.OPENING)) return false
        return try {
            val submitted = workerPool.submit(Runnable {
                val shouldRun = synchronized(workerLifecycleGate) {
                    if (cancelledBeforeWorkerStart) {
                        false
                    } else {
                        workerTaskStarted = true
                        true
                    }
                }
                if (shouldRun) {
                    workerStartedHook?.invoke()
                    runWorker()
                } else {
                    signalFinished()
                }
            })
            synchronized(workerLifecycleGate) {
                future.set(submitted)
                if (state.get() == NativeDecoderState.CLOSED || stopRequested.get()) {
                    stopWorkerTaskLocked(submitted)
                }
            }
            true
        } catch (error: RejectedExecutionException) {
            fail(failure(
                NativeDecoderErrorCode.WORKER_REJECTED,
                "The bounded decoder worker pool is full", error
            ))
            signalFinished()
            false
        }
    }

    /**
     * Publishes a latest-wins seek and returns its monotonic generation. The target is on the
     * audible, gapless timeline: zero is the first frame after encoder delay.
     */
    fun seekToUs(positionUs: Long): Long {
        require(positionUs >= 0L)
        check(
            state.get() == NativeDecoderState.IDLE ||
                state.get() == NativeDecoderState.OPENING ||
            state.get() == NativeDecoderState.DECODING ||
                state.get() == NativeDecoderState.ENDED
        ) { "A terminal decoder cannot seek" }
        val next = nextGeneration()
        requestedSeek.set(SeekRequest(next, positionUs))
        workerThread.get()?.let(LockSupport::unpark)
        return next
    }

    /** Cancels decoder work, invalidating any in-flight block with a new generation. */
    fun cancel(): Long {
        while (true) {
            val previous = state.get()
            if (previous == NativeDecoderState.CLOSED || previous == NativeDecoderState.CANCELLED) {
                return generation.get()
            }
            if (previous == NativeDecoderState.FAILED) {
                return generation.get()
            }
            if (state.compareAndSet(previous, NativeDecoderState.CANCELLED)) break
        }
        val invalidation = nextGeneration()
        stopRequested.set(true)
        stopWorkerTask(notifyCancellationGeneration = invalidation)
        return invalidation
    }

    /** Idempotent and non-blocking; [awaitClosed] is available to tests/resource owners. */
    override fun close() {
        while (true) {
            val previous = state.get()
            if (previous == NativeDecoderState.CLOSED) return
            if (state.compareAndSet(previous, NativeDecoderState.CLOSED)) {
                if (previous == NativeDecoderState.OPENING ||
                    previous == NativeDecoderState.DECODING ||
                    previous == NativeDecoderState.IDLE
                ) {
                    nextGeneration()
                }
                stopRequested.set(true)
                stopWorkerTask()
                return
            }
        }
    }

    fun awaitClosed(timeout: Long, unit: TimeUnit): Boolean {
        val joined = finished.await(timeout, unit)
        if (joined) workerPool.purgeCancelledTasks()
        return joined
    }

    /** Wakes a sink that previously returned backpressuredUntilSignal(). */
    fun signalSinkWritable() {
        workerThread.get()?.let(LockSupport::unpark)
    }

    /** Throttles full-ingress retries while playback is physically quiesced. */
    fun setSinkRetryThrottled(throttled: Boolean) {
        sinkRetryThrottled.set(throttled)
        if (!throttled) workerThread.get()?.let(LockSupport::unpark)
    }

    fun diagnostics(): NativeDecoderDiagnostics = NativeDecoderDiagnostics(
        decoderId = decoderId,
        laneId = laneId,
        source = source.diagnosticName,
        state = state.get(),
        workerThreadName = workerThreadName,
        codecName = codecName,
        selectedTrack = selectedTrack,
        outputFormat = currentOutputFormat,
        requestedSeekGeneration = generation.get(),
        activeSeekGeneration = activeGeneration.get(),
        inputBufferCount = inputBufferCount.get(),
        inputBytes = inputBytes.get(),
        outputBufferCount = outputBufferCount.get(),
        copiedOutputBytes = copiedOutputBytes.get(),
        deliveredBytes = deliveredBytes.get(),
        discardedLeadingFrames = discardedLeadingFrames.get(),
        discardedPaddingFrames = discardedPaddingFrames.get(),
        sinkRetryCount = sinkRetryCount.get(),
        sinkSignalParkCount = sinkSignalParkCount.get(),
        outputFormatChangeCount = outputFormatChangeCount.get(),
        seekCount = seekCount.get(),
        timestampCorrectionCount = timestampCorrectionCount.get(),
        maximumTimestampCorrectionFrames = maximumTimestampCorrectionFrames.get(),
        codecTimestampOffsetFrames = codecTimestampOffsetFrames,
        extractorStartPresentationTimeUs = extractorStartPresentationTimeUs,
        firstCodecOutputPresentationTimeUs = firstCodecOutputPresentationTimeUs,
        decodedAbsoluteFirstFrame = decodedAbsoluteFirstFrame,
        decodedAbsoluteEndFrameExclusive = decodedAbsoluteEndFrameExclusive,
        lastPresentationTimeUs = lastPresentationTimeUs,
        lastErrorCode = lastErrorCode,
        lastErrorMessage = lastErrorMessage
    )

    private fun stopWorkerTask(notifyCancellationGeneration: Long? = null) {
        synchronized(workerLifecycleGate) {
            stopWorkerTaskLocked(
                submitted = future.get(),
                notifyCancellationGeneration = notifyCancellationGeneration
            )
        }
    }

    /** Must be called with [workerLifecycleGate] held. */
    private fun stopWorkerTaskLocked(
        submitted: Future<*>?,
        notifyCancellationGeneration: Long? = null
    ) {
        if (workerTaskStarted) {
            cancelActiveSourceOpen()
            // runWorker checks stopRequested before opening its extractor. If it has not yet
            // published workerThread, no wake is needed; otherwise wake a parked sink/EOS pump.
            workerThread.get()?.let(LockSupport::unpark)
            return
        }
        cancelledBeforeWorkerStart = true
        // The wrapper rechecks cancelledBeforeWorkerStart under this same gate, so even a Future
        // that has crossed ExecutorService dispatch cannot enter MediaCodec work after this point.
        submitted?.cancel(false)
        notifyCancellationGeneration?.let(::notifyCancelled)
        signalFinished()
    }

    private fun cancelActiveSourceOpen() {
        runCatching { activeSourceOpen.getAndSet(null)?.cancel() }
    }

    private fun runWorker() {
        val thread = Thread.currentThread()
        workerThread.set(thread)
        workerThreadName = thread.name
        var codec: MediaCodec? = null
        var openedExtractor: NativeTrackExtractor? = null
        var floatWave: WavPackReader? = null
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                throw failure(
                    NativeDecoderErrorCode.MAIN_THREAD_VIOLATION,
                    "Media decoding cannot run on the main thread"
                )
            }
            if (stopRequested.get()) return

            if (source is NativeMediaSource.Float32Audio) {
                floatWave = openFloatAudio(source)
                val facts = floatWave.facts
                val track = NativeAudioTrackInfo(0, facts.mime, null,
                    framesToMicroseconds(facts.audibleFrames, facts.sampleRate),
                    facts.sampleRate, facts.channels, 0, 0)
                source.sourceAdmission().admitTrack(track)
                selectedTrack = track
                codecName = "native-wavpack-float32"
                if (state.compareAndSet(NativeDecoderState.OPENING, NativeDecoderState.DECODING)) {
                    safeListener { onPrepared(NativeDecoderPrepared(
                        decoderId, laneId, source.diagnosticName, requireNotNull(codecName), track, 0L)) }
                }
                pumpFloatAudio(floatWave)
                return
            }

            val sourceOpen = CancellationSignal()
            check(activeSourceOpen.compareAndSet(null, sourceOpen))
            if (stopRequested.get()) return
            val platformExtractor = MediaExtractor()
            openedExtractor = PlatformTrackExtractor(platformExtractor)
            if (stopRequested.get()) return
            try {
                source.withDescriptor(applicationContext, sourceOpen) { sourceDescriptor ->
                    if (!stopRequested.get()) {
                        source.setDataSource(platformExtractor, sourceDescriptor)
                        val flacTrack = (0 until platformExtractor.trackCount).any { index ->
                            val mime = platformExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                            mime == "audio/raw" || mime == "audio/flac"
                        }
                        if (flacTrack) {
                            // MediaExtractor may group multiple FLAC frames; the float codec consumes one per input.
                            val flac = packetReaderFactory.openFlac(sourceDescriptor) {
                                if (stopRequested.get()) throw InterruptedException()
                            }
                            if (flac != null) {
                                requireNotNull(openedExtractor).release()
                                openedExtractor = flac
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                throw failure(
                    NativeDecoderErrorCode.SOURCE_OPEN_FAILED,
                    "The redacted audio source could not be opened",
                    error
                )
            } finally {
                activeSourceOpen.compareAndSet(sourceOpen, null)
            }
            if (stopRequested.get()) return
            val extractor = requireNotNull(openedExtractor)
            val audioTracks = readAudioTracks(extractor)
            if (stopRequested.get()) return
            if (audioTracks.isEmpty()) {
                throw failure(
                    NativeDecoderErrorCode.NO_AUDIO_TRACK,
                    "The source contains no extractor-readable audio track"
                )
            }
            val track = audioTracks.first()
            if (track.encoderDelayFrames > config.maximumGaplessTrimFrames ||
                track.encoderPaddingFrames > config.maximumGaplessTrimFrames
            ) {
                throw failure(
                    NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                    "Gapless trim metadata exceeds the decoder's fixed bound"
                )
            }
            source.sourceAdmission().admitTrack(track)
            selectedTrack = track
            extractor.selectTrack(track.trackIndex)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val trackStartPtsUs = extractor.sampleTime.takeIf { it != -1L } ?: 0L
            val timeline = NativeGaplessTimeline(
                trackStartPresentationTimeUs = trackStartPtsUs,
                encoderDelayFrames = track.encoderDelayFrames,
                // Android codecs receive the extractor's original gapless metadata and own
                // end-padding removal. The duration boundary below is an independent safety net
                // for Opus extractors that expose an audible duration but no padding key.
                encoderPaddingFrames = 0
            )

            val codecFormat = extractor.getTrackFormat(track.trackIndex)
            var opusCodecDelayFrames: Long? = null
            val seekPrerollUs = if (track.mimeType == "audio/opus") {
                val delay = codecFormat.getByteBuffer("csd-1")?.duplicate()?.order(ByteOrder.nativeOrder())
                if (delay == null || delay.remaining() != Long.SIZE_BYTES) {
                    throw failure(NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                        "Opus codec delay metadata must contain one native-order 64-bit duration")
                }
                val delayNanos = delay.long
                if (delayNanos < 0L || delayNanos >
                    config.maximumGaplessTrimFrames.toLong() * 1_000_000_000L / 48_000L) {
                    throw failure(NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                        "Opus codec delay exceeds the decoder's fixed trim bound")
                }
                opusCodecDelayFrames = opusCodecDelayFrames(delayNanos)
                val advertised = codecFormat.getByteBuffer("csd-2")?.duplicate()?.order(ByteOrder.nativeOrder())
                if (advertised == null || advertised.remaining() != Long.SIZE_BYTES) {
                    throw failure(NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                        "Opus seek pre-roll metadata must contain one native-order 64-bit duration")
                }
                val advertisedNanos = advertised.long
                if (advertisedNanos < 0L) {
                    throw failure(NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                        "Opus seek pre-roll metadata exceeds the supported duration domain")
                }
                // One frame clock owns initial delay and seek preroll, including cold nonzero opens.
                codecFormat.setByteBuffer("csd-1", ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).putLong(0, 0L))
                codecFormat.setByteBuffer("csd-2", ByteBuffer.allocate(Long.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).putLong(0, 0L))
                val advertisedMicros = advertisedNanos / 1_000L + if (advertisedNanos % 1_000L != 0L) 1L else 0L
                maxOf(config.seekPrerollUs, advertisedMicros)
            } else config.seekPrerollUs
            requiresFloatPcm = track.mimeType == "audio/flac" ||
                codecFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            if (requiresFloatPcm) {
                // The default PCM16 output discards the low bits of 24-bit originals.
                codecFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }
            val decoder = createDecoder(track.mimeType, codecFormat)
            codec = decoder
            codecName = decoder.name
            try {
                decoder.configure(codecFormat, null, null, 0)
                decoder.start()
            } catch (error: Throwable) {
                throw failure(
                    NativeDecoderErrorCode.CODEC_CONFIGURATION_FAILED,
                    "The platform decoder rejected the selected audio format",
                    error
                )
            }

            if (state.compareAndSet(NativeDecoderState.OPENING, NativeDecoderState.DECODING)) {
                safeListener {
                    onPrepared(
                        NativeDecoderPrepared(
                            decoderId = decoderId,
                            laneId = laneId,
                            source = source.diagnosticName,
                            codecName = requireNotNull(codecName),
                            track = track,
                            trackStartPresentationTimeUs = trackStartPtsUs
                        )
                    )
                }
            }
            if (stopRequested.get()) return
            pump(extractor, decoder, track, timeline, seekPrerollUs, opusCodecDelayFrames)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (known: NativeDecoderFailure) {
            if (!stopRequested.get() && state.get() != NativeDecoderState.CLOSED) fail(known)
        } catch (error: Throwable) {
            if (!stopRequested.get() && state.get() != NativeDecoderState.CLOSED) {
                fail(failure(
                    NativeDecoderErrorCode.CODEC_RUNTIME_FAILED,
                    "The platform audio decoder stopped unexpectedly", error
                ))
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            cancelActiveSourceOpen()
            runCatching { openedExtractor?.release() }
            runCatching { floatWave?.close() }
            workerThread.set(null)
            if (state.get() == NativeDecoderState.CANCELLED) {
                notifyCancelled(generation.get())
            }
            signalFinished()
        }
    }

    private fun openFloatAudio(source: NativeMediaSource.Float32Audio): WavPackReader {
        val signal = CancellationSignal()
        check(activeSourceOpen.compareAndSet(null, signal))
        var stream: FileInputStream? = null
        var openedReader: WavPackReader? = null
        try {
            if (stopRequested.get()) throw InterruptedException()
            return source.withDescriptor(applicationContext, signal) { descriptor ->
                val input = ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(descriptor.fileDescriptor))
                stream = input
                val offset = descriptor.startOffset
                val length = descriptor.declaredLength.takeIf { it >= 0L }
                    ?: (input.channel.size() - offset)
                val checkpoint = {
                    signal.throwIfCanceled()
                    if (stopRequested.get()) throw InterruptedException()
                }
                val facts = source.sourceAdmission().floatAudioFacts {
                    inspectWavPackSlice(input.channel, offset, length, checkpoint)
                }
                val reader = WavPackReader(input.fd, offset, length, facts, checkpoint).also {
                    try { input.close() } catch (error: Throwable) { it.close(); throw error }
                }
                openedReader = reader
                reader
            }
        } catch (cancelled: InterruptedException) {
            runCatching { openedReader?.close() }
            runCatching { stream?.close() }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { openedReader?.close() }
            runCatching { stream?.close() }
            throw failure(NativeDecoderErrorCode.SOURCE_OPEN_FAILED,
                "The bounded float audio source could not be opened", error)
        } finally {
            activeSourceOpen.compareAndSet(signal, null)
        }
    }

    private fun pumpFloatAudio(reader: WavPackReader) {
        val format = NativePcmFormat(48_000, 2, NativePcmEncoding.PCM_FLOAT)
        val blockBytes = (minOf(config.initialCopyBufferBytes, 65536 * 8) / format.bytesPerFrame) * format.bytesPerFrame
        val copy = BoundedDirectBuffer(blockBytes, config.maximumCodecOutputBufferBytes)
        val reusablePending = PendingPcm()
        val sinkBlock = NativePcmBlock()
        var pending: PendingPcm? = null
        var activeSeek: SeekRequest? = null
        var nextFrame = 0L
        var ended = false
        while (!stopRequested.get()) {
            val newest = requestedSeek.get()
            if (activeSeek?.generation != newest.generation) {
                pending = null
                reusablePending.clear()
                nextFrame = reader.seekFrame(newest.positionUs)
                activeSeek = newest
                activeGeneration.set(newest.generation)
                lastPresentationTimeUs = null
                ended = false
                state.compareAndSet(NativeDecoderState.ENDED, NativeDecoderState.DECODING)
                if (newest.generation > 0L || newest.positionUs > 0L) seekCount.incrementAndGet()
                publishOutputFormat(format, newest.generation)
            }
            val seek = requireNotNull(activeSeek)
            if (ended) {
                LockSupport.park()
                if (Thread.interrupted() && stopRequested.get()) throw InterruptedException()
                continue
            }
            pending?.let { block ->
                pending = offerPending(block, copy, seek, sinkBlock)
                if (pending != null) continue
                if (block.endOfStream && requestedSeek.get().generation == seek.generation) {
                    ended = true
                    finishEndOfStream(seek.generation)
                    continue
                }
            }
            if (requestedSeek.get().generation != seek.generation) continue
            val frames = try {
                reader.readFrames(nextFrame, copy.writable(blockBytes), blockBytes / 8)
            } catch (cancelled: InterruptedException) {
                throw cancelled
            } catch (error: Throwable) {
                throw failure(NativeDecoderErrorCode.MALFORMED_PCM_BUFFER,
                    "Float audio contains a truncated or nonfinite PCM frame", error)
            }
            if (frames == 0) throw failure(NativeDecoderErrorCode.MALFORMED_PCM_BUFFER,
                "Float audio ended without its final frame")
            copy.flip(frames * 8)
            copiedOutputBytes.addAndGet(frames * 8L)
            outputBufferCount.incrementAndGet()
            reusablePending.reset(0, 0, frames * 8, nextFrame, format, seek.generation,
                nextFrame + frames == reader.facts.audibleFrames)
            nextFrame += frames
            pending = reusablePending
        }
    }

    private fun pump(
        extractor: NativeTrackExtractor,
        codec: MediaCodec,
        track: NativeAudioTrackInfo,
        timeline: NativeGaplessTimeline,
        seekPrerollUs: Long,
        opusCodecDelayFrames: Long?
    ) {
        val codecCopy = BoundedDirectBuffer(
            config.initialCopyBufferBytes,
            config.maximumCodecOutputBufferBytes
        )
        val reusablePending = PendingPcm()
        var pending: PendingPcm? = null
        val progress = NativeCodecProgressDeadline(config.codecProgressTimeoutMs)
        val bufferInfo = MediaCodec.BufferInfo()
        var activeSeek: SeekRequest? = null
        var inputEnded = false
        var outputEnded = false
        var lastInputPtsUs = 0L
        var pcmFormat: NativePcmFormat? = null
        val reusableSinkBlock = NativePcmBlock()

        var nextDecodedAbsoluteFrame: Long? = null

        while (!stopRequested.get()) {
            val newestSeek = requestedSeek.get()
            if (outputEnded && activeSeek?.generation == newestSeek.generation) {
                // Keep the configured extractor/codec resident for repeat and seek-after-EOS.
                // seekToUs(), cancel(), and close() all unpark this worker.
                LockSupport.park()
                if (Thread.interrupted() && stopRequested.get()) throw InterruptedException()
                continue
            }
            if (activeSeek?.generation != newestSeek.generation) {
                if (activeSeek != null) codec.flush()
                val sampleRateHint = track.sampleRateHz ?: DEFAULT_SEEK_SAMPLE_RATE_HZ
                val extractorTargetUs = timeline.extractorPositionForTimelineUs(
                    config.outputStartPositionUs(newestSeek.positionUs, track.sampleRateHz),
                    sampleRateHint
                )
                // MP3 byte/TOC seeks assign estimated timestamps, not exact PCM ordinals.
                // Decode from origin so both cold and flushed seeks keep the same sample clock.
                val extractorSeekAnchorUs = if (track.mimeType == "audio/mpeg") 0L else saturatingSubtract(
                    extractorTargetUs.coerceAtLeast(0L),
                    seekPrerollUs
                ).coerceAtLeast(0L)
                extractor.seekTo(
                    extractorSeekAnchorUs,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                )
                pending = null
                reusablePending.clear()
                inputEnded = false
                outputEnded = false
                nextDecodedAbsoluteFrame = null
                val extractorStartPtsUs = extractor.sampleTime
                    .takeIf { it != -1L }
                    ?: extractorTargetUs
                lastInputPtsUs = extractorStartPtsUs
                extractorStartPresentationTimeUs = extractorStartPtsUs
                firstCodecOutputPresentationTimeUs = null
                decodedAbsoluteFirstFrame = null
                decodedAbsoluteEndFrameExclusive = null
                if (opusCodecDelayFrames != null) codecTimestampOffsetFrames = null
                lastPresentationTimeUs = null
                progress.reset(System.nanoTime())
                activeSeek = newestSeek
                activeGeneration.set(newestSeek.generation)
                state.compareAndSet(NativeDecoderState.ENDED, NativeDecoderState.DECODING)
                if (newestSeek.generation > 0L || newestSeek.positionUs > 0L) {
                    seekCount.incrementAndGet()
                }
            }
            val seek = requireNotNull(activeSeek)

            pending?.let { block ->
                val stillPending = offerPending(
                    block,
                    codecCopy,
                    seek,
                    reusableSinkBlock
                )
                // A paused/full sink owns this delay; codec polling did not stall.
                progress.reset(System.nanoTime())
                pending = stillPending
                if (stillPending != null) continue
                if (block.endOfStream) {
                    if (requestedSeek.get().generation == seek.generation) {
                        outputEnded = true
                        finishEndOfStream(seek.generation)
                    }
                    continue
                }
            }

            if (progress.expired(System.nanoTime())) {
                throw failure(NativeDecoderErrorCode.CODEC_STALLED,
                    "The platform decoder made no input/output progress within ${config.codecProgressTimeoutMs}ms")
            }

            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(config.codecPollTimeoutUs)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                        ?: throw failure(
                            NativeDecoderErrorCode.CODEC_RUNTIME_FAILED,
                            "The platform decoder returned no input buffer"
                        )
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            lastInputPtsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                        progress.reset(System.nanoTime())
                    } else {
                        val sampleTimeUs = extractor.sampleTime
                        val inputFlags = try {
                            codecInputFlagsForExtractorSample(extractor.sampleFlags)
                        } catch (error: IllegalArgumentException) {
                            throw failure(
                                NativeDecoderErrorCode.UNSUPPORTED_CODEC,
                                "Encrypted audio requires a secure decoder input path",
                                error
                            )
                        }
                        lastInputPtsUs = sampleTimeUs
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            size,
                            sampleTimeUs,
                            inputFlags
                        )
                        if (size > 0) progress.reset(System.nanoTime())
                        inputBufferCount.incrementAndGet()
                        inputBytes.addAndGet(size.toLong())
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, config.codecPollTimeoutUs)
            when {
                outputIndex >= 0 -> {
                    outputBufferCount.incrementAndGet()
                    val codecConfig =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if ((bufferInfo.size > 0 && !codecConfig) || eos) progress.reset(System.nanoTime())
                    if (bufferInfo.size > 0 && !codecConfig) {
                        val format = pcmFormat ?: readPcmFormat(codec.outputFormat).also {
                            pcmFormat = it
                            publishOutputFormat(it, seek.generation)
                        }
                        copyCodecOutput(codec, outputIndex, bufferInfo, codecCopy)
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (requestedSeek.get().generation != seek.generation) continue
                        if (firstCodecOutputPresentationTimeUs == null) {
                            firstCodecOutputPresentationTimeUs = bufferInfo.presentationTimeUs
                        }
                        if (codecTimestampOffsetFrames == null) {
                            codecTimestampOffsetFrames = saturatingSubtract(
                                timeline.absoluteFrameForPresentationTime(
                                    bufferInfo.presentationTimeUs,
                                    format.sampleRateHz
                                ),
                                timeline.firstDecodedAbsoluteFrame(
                                    requireNotNull(extractorStartPresentationTimeUs),
                                    format.sampleRateHz,
                                    opusCodecDelayFrames
                                )
                            )
                        }
                        nextDecodedAbsoluteFrame = prepareDelivery(
                            bufferInfo = bufferInfo,
                            format = format,
                            timeline = timeline,
                            track = track,
                            seek = seek,
                            decodedAbsoluteFirstFrame = nextDecodedAbsoluteFrame,
                            timestampOffsetFrames = requireNotNull(codecTimestampOffsetFrames),
                            endOfStream = eos,
                            pendingOut = reusablePending
                        )
                        pending = reusablePending.takeIf { it.byteCount > 0 }
                        if (eos && pending == null) {
                            outputEnded = true
                            finishEndOfStream(seek.generation)
                        }
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (eos && requestedSeek.get().generation == seek.generation) {
                            outputEnded = true
                            finishEndOfStream(seek.generation)
                        }
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val changed = readPcmFormat(codec.outputFormat)
                    pcmFormat = changed
                    publishOutputFormat(changed, seek.generation)
                }
            }
        }
    }

    private fun prepareDelivery(
        bufferInfo: MediaCodec.BufferInfo,
        format: NativePcmFormat,
        timeline: NativeGaplessTimeline,
        track: NativeAudioTrackInfo,
        seek: SeekRequest,
        decodedAbsoluteFirstFrame: Long?,
        timestampOffsetFrames: Long,
        endOfStream: Boolean,
        pendingOut: PendingPcm
    ): Long {
        pendingOut.clear()
        val bytesPerFrame = format.bytesPerFrame
        if (bufferInfo.size % bytesPerFrame != 0) {
            throw failure(
                NativeDecoderErrorCode.MALFORMED_PCM_BUFFER,
                "The platform decoder produced a partial PCM frame"
            )
        }
        val decodedFrames = bufferInfo.size / bytesPerFrame
        val timestampFrame = saturatingSubtract(
            timeline.absoluteFrameForPresentationTime(
                bufferInfo.presentationTimeUs,
                format.sampleRateHz
            ),
            timestampOffsetFrames
        )
        // After the first buffer in a generation, decoded frame counts are a more stable PCM
        // clock than compressed-packet PTS. MP3 and Opus extractors/codecs can quantize adjacent
        // output timestamps differently by more than one frame. The original timestamp still
        // anchors every seek generation; subsequent buffers advance by exact decoded frames.
        val absoluteFirstFrame = decodedAbsoluteFirstFrame ?: timestampFrame
        if (this.decodedAbsoluteFirstFrame == null) this.decodedAbsoluteFirstFrame = absoluteFirstFrame
        decodedAbsoluteEndFrameExclusive = saturatingAdd(absoluteFirstFrame, decodedFrames.toLong())
        if (decodedAbsoluteFirstFrame != null && timestampFrame != decodedAbsoluteFirstFrame) {
            timestampCorrectionCount.incrementAndGet()
            updateMaximum(
                maximumTimestampCorrectionFrames,
                absoluteDifference(timestampFrame, decodedAbsoluteFirstFrame)
            )
        }
        val targetAbsoluteFrame = timeline.absoluteFrameForTimelinePosition(
            config.outputStartPositionUs(seek.positionUs, format.sampleRateHz),
            format.sampleRateHz
        )
        val leadingFrames = (targetAbsoluteFrame - absoluteFirstFrame)
            .coerceIn(0L, decodedFrames.toLong())
            .toInt()
        if (leadingFrames > 0) discardedLeadingFrames.addAndGet(leadingFrames.toLong())
        val framesAfterLeadingTrim = decodedFrames - leadingFrames
        val eligibleStartFrame = absoluteFirstFrame + leadingFrames
        // Only Opus supplies the independent container endpoint needed by its trim path.
        // General extractor durations (notably unindexed VBR MP3) can be estimates.
        val audibleEndAbsoluteFrame = track.durationUs?.takeIf { track.mimeType == "audio/opus" }?.let { durationUs ->
            saturatingSubtract(
                microsecondsToFramesNearest(durationUs, format.sampleRateHz),
                track.encoderPaddingFrames.toLong()
            )
        }
        val eligibleFrames = if (audibleEndAbsoluteFrame == null) {
            framesAfterLeadingTrim
        } else {
            min(
                framesAfterLeadingTrim,
                (audibleEndAbsoluteFrame - eligibleStartFrame)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
        val framesPastDuration = framesAfterLeadingTrim - eligibleFrames
        if (framesPastDuration > 0) {
            discardedPaddingFrames.addAndGet(framesPastDuration.toLong())
        }
        if (eligibleFrames > 0) {
            val eligibleBytes = eligibleFrames * bytesPerFrame
            val firstTimelineFrame = eligibleStartFrame - timeline.encoderDelayFrames
            pendingOut.reset(
                bufferStartByte = leadingFrames * bytesPerFrame,
                byteOffset = 0,
                byteCount = eligibleBytes,
                firstTimelineFrame = firstTimelineFrame.coerceAtLeast(0L),
                format = format,
                generation = seek.generation,
                endOfStream = endOfStream
            )
        }
        return saturatingAdd(absoluteFirstFrame, decodedFrames.toLong())
    }

    private fun offerPending(
        pending: PendingPcm,
        codecCopy: BoundedDirectBuffer,
        activeSeek: SeekRequest,
        reusableBlock: NativePcmBlock
    ): PendingPcm? {
        if (requestedSeek.get().generation != activeSeek.generation ||
            pending.generation != activeSeek.generation
        ) {
            return null
        }
        val format = pending.format
        val consumedFrames = pending.byteOffset / format.bytesPerFrame
        val firstOfferedTimelineFrame = saturatingAdd(
            pending.firstTimelineFrame,
            consumedFrames.toLong()
        )
        val ptsUs = framesToMicroseconds(firstOfferedTimelineFrame, format.sampleRateHz)
        // The decode loop drains pending bytes before reusing this owned codec copy.
        // Keep trim displacement separate from consumed bytes so partial-offer PTS stays exact.
        val view = codecCopy.readOnlyWindow(
            pending.bufferStartByte + pending.byteOffset,
            pending.byteCount
        )
        val block = reusableBlock.reset(
            data = view,
            format = format,
            presentationTimeUs = ptsUs,
            seekGeneration = pending.generation,
            firstTimelineFrame = firstOfferedTimelineFrame
        )
        val result = try {
            sink.offer(block)
        } catch (error: Throwable) {
            throw failure(
                NativeDecoderErrorCode.SINK_FAILED,
                "The PCM sink rejected decoder output",
                error
            )
        }
        if (result.acceptedBytes > pending.byteCount ||
            result.acceptedBytes % format.bytesPerFrame != 0 ||
            (result.acceptedBytes > 0 &&
                (result.waitsForSignal || result.reportsPermanentStall))
        ) {
            throw failure(
                NativeDecoderErrorCode.SINK_PROTOCOL_VIOLATION,
                "The PCM sink returned an invalid partial acceptance"
            )
        }
        if (requestedSeek.get().generation != activeSeek.generation) return null
        if (result.reportsPermanentStall) {
            throw failure(
                NativeDecoderErrorCode.SINK_STALLED,
                "The PCM sink explicitly reported a permanent liveness failure"
            )
        }
        if (result.acceptedBytes > 0) {
            deliveredBytes.addAndGet(result.acceptedBytes.toLong())
            val acceptedFrames = result.acceptedBytes / format.bytesPerFrame
            lastPresentationTimeUs = framesToMicroseconds(
                saturatingAdd(
                    firstOfferedTimelineFrame,
                    (acceptedFrames - 1).toLong()
                ),
                format.sampleRateHz
            )
            val remaining = pending.byteCount - result.acceptedBytes
            if (remaining == 0) return null
            pending.byteOffset += result.acceptedBytes
            pending.byteCount = remaining
            return pending
        }

        sinkRetryCount.incrementAndGet()
        if (result.waitsForSignal) {
            sinkSignalParkCount.incrementAndGet()
            LockSupport.park()
        } else {
            val delayUs = if (result.acceptedBytes == 0 && sinkRetryThrottled.get()) {
                config.maximumSinkRetryDelayUs
            } else {
                result.retryDelayUs
                    .coerceAtLeast(100L)
                    .coerceAtMost(config.maximumSinkRetryDelayUs)
            }
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(delayUs))
        }
        if (Thread.interrupted()) throw InterruptedException()
        return pending
    }

    private fun copyCodecOutput(
        codec: MediaCodec,
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        destination: BoundedDirectBuffer
    ) {
        if (info.size > config.maximumCodecOutputBufferBytes) {
            codec.releaseOutputBuffer(outputIndex, false)
            throw failure(
                NativeDecoderErrorCode.OUTPUT_BUFFER_TOO_LARGE,
                "The platform decoder exceeded the fixed PCM block bound"
            )
        }
        val output = codec.getOutputBuffer(outputIndex)
        if (output == null || info.offset < 0 || info.size < 0 ||
            info.offset.toLong() + info.size > output.capacity().toLong()
        ) {
            codec.releaseOutputBuffer(outputIndex, false)
            throw failure(
                NativeDecoderErrorCode.CODEC_RUNTIME_FAILED,
                "The platform decoder returned an invalid output buffer"
            )
        }
        val sourceView = output.apply {
            position(info.offset)
            limit(info.offset + info.size)
        }
        destination.copyFrom(sourceView, info.size)
        copiedOutputBytes.addAndGet(info.size.toLong())
    }

    private fun publishOutputFormat(format: NativePcmFormat, seekGeneration: Long) {
        source.sourceAdmission().admitPcmFormat(format)
        currentOutputFormat = format
        outputFormatChangeCount.incrementAndGet()
        safeListener { onOutputFormatChanged(format, seekGeneration) }
    }

    private fun finishEndOfStream(seekGeneration: Long) {
        if (state.compareAndSet(NativeDecoderState.DECODING, NativeDecoderState.ENDED)) {
            safeListener {
                onEndOfStream(seekGeneration, lastPresentationTimeUs)
            }
        }
    }

    private fun readAudioTracks(extractor: NativeTrackExtractor): List<NativeAudioTrackInfo> =
        buildList {
            repeat(extractor.trackCount) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: return@repeat
                if (!mime.startsWith("audio/", ignoreCase = true)) return@repeat
                add(
                    NativeAudioTrackInfo(
                        trackIndex = index,
                        mimeType = mime,
                        language = format.stringOrNull(MediaFormat.KEY_LANGUAGE),
                        durationUs = format.longOrNull(MediaFormat.KEY_DURATION),
                        sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                        encoderDelayFrames = format.intOrNull(MediaFormat.KEY_ENCODER_DELAY)
                            ?.coerceAtLeast(0) ?: 0,
                        encoderPaddingFrames = format.intOrNull(MediaFormat.KEY_ENCODER_PADDING)
                            ?.coerceAtLeast(0) ?: 0
                    )
                )
            }
        }

    private fun createDecoder(mime: String, format: MediaFormat): MediaCodec {
        return try {
            val codecName = if (mime == "audio/flac" && requiresFloatPcm) {
                // Vendor FLAC can echo float output while still returning PCM16 bytes.
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    !info.isEncoder && info.isSoftwareOnly && !info.isVendor &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        info.getCapabilitiesForType(mime).isFormatSupported(format)
                }?.name ?: throw IllegalStateException("No software FLAC decoder supports float PCM")
            } else runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            }.getOrNull()
            if (codecName != null) MediaCodec.createByCodecName(codecName)
            else MediaCodec.createDecoderByType(mime)
        } catch (error: Throwable) {
            throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_CODEC,
                "No platform decoder supports the selected audio codec",
                error
            )
        }
    }

    private fun readPcmFormat(format: MediaFormat): NativePcmFormat {
        val mime = format.stringOrNull(MediaFormat.KEY_MIME)
        if (mime != null && !mime.equals(MediaFormat.MIMETYPE_AUDIO_RAW, ignoreCase = true)) {
            throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "The platform decoder did not expose raw PCM"
            )
        }
        val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
            ?: throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "Decoded PCM has no sample rate"
            )
        val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
            ?: throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "Decoded PCM has no channel count"
            )
        if (channels !in 1..2) {
            throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "Decoded PCM has more than two channels; no implicit downmix is allowed"
            )
        }
        val androidEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)
            ?: AudioFormat.ENCODING_PCM_16BIT
        val encoding = NativePcmEncoding.fromAndroidEncoding(androidEncoding)
            ?: throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "The decoder produced PCM other than signed 16-bit or float"
            )
        if (requiresFloatPcm && encoding != NativePcmEncoding.PCM_FLOAT) {
            throw failure(NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "The high-precision decoder did not honor float PCM output")
        }
        return try {
            NativePcmFormat(sampleRate, channels, encoding)
        } catch (error: IllegalArgumentException) {
            throw failure(
                NativeDecoderErrorCode.UNSUPPORTED_PCM_FORMAT,
                "The decoded PCM sample rate or channel count is unsupported",
                error
            )
        }
    }

    private fun nextGeneration(): Long = generation.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "Decoder seek generation space exhausted" }
        current + 1L
    }

    private fun failure(code: NativeDecoderErrorCode, message: String, cause: Throwable? = null) =
        NativeDecoderFailure(code, decoderId, laneId, message, cause)

    private fun fail(code: NativeDecoderErrorCode, message: String) = fail(failure(code, message))

    private fun fail(failure: NativeDecoderFailure) {
        while (true) {
            val previous = state.get()
            if (previous == NativeDecoderState.CLOSED ||
                previous == NativeDecoderState.CANCELLED ||
                previous == NativeDecoderState.FAILED
            ) return
            if (state.compareAndSet(previous, NativeDecoderState.FAILED)) break
        }
        lastErrorCode = failure.code
        lastErrorMessage = listOfNotNull(failure.message, failure.causeDiagnostic).joinToString("\n")
        safeListener { onError(failure, diagnostics()) }
    }

    private fun notifyCancelled(seekGeneration: Long) {
        if (cancellationNotified.compareAndSet(false, true)) {
            safeListener { onCancelled(seekGeneration) }
        }
    }

    private inline fun safeListener(callback: NativeMediaCodecDecoderListener.() -> Unit) {
        runCatching { listener.callback() }
    }

    private fun signalFinished() {
        if (finishedSignalled.compareAndSet(false, true)) finished.countDown()
    }

    private data class SeekRequest(val generation: Long, val positionUs: Long)

    private class PendingPcm {
        var bufferStartByte: Int = 0
        var byteOffset: Int = 0
        var byteCount: Int = 0
        var firstTimelineFrame: Long = 0L
        lateinit var format: NativePcmFormat
        var generation: Long = 0L
        var endOfStream: Boolean = false

        fun reset(
            bufferStartByte: Int,
            byteOffset: Int,
            byteCount: Int,
            firstTimelineFrame: Long,
            format: NativePcmFormat,
            generation: Long,
            endOfStream: Boolean
        ) {
            this.bufferStartByte = bufferStartByte
            this.byteOffset = byteOffset
            this.byteCount = byteCount
            this.firstTimelineFrame = firstTimelineFrame
            this.format = format
            this.generation = generation
            this.endOfStream = endOfStream
        }

        fun clear() {
            bufferStartByte = 0
            byteOffset = 0
            byteCount = 0
            endOfStream = false
        }
    }

    companion object {
        private const val DEFAULT_SEEK_SAMPLE_RATE_HZ = 48_000
    }
}

/** The extractor rounds integer Opus pre-skip down to nanoseconds; recover its nearest frame. */
internal fun opusCodecDelayFrames(delayNanos: Long): Long {
    require(delayNanos >= 0L)
    return Math.addExact(Math.multiplyExact(delayNanos, 48_000L), 500_000_000L) / 1_000_000_000L
}

/** Pure frame-domain mapping used for gapless trim, seeks, and host tests. */
internal data class NativeGaplessTimeline(
    val trackStartPresentationTimeUs: Long,
    val encoderDelayFrames: Int,
    val encoderPaddingFrames: Int
) {
    init {
        require(encoderDelayFrames >= 0)
        require(encoderPaddingFrames >= 0)
    }

    fun absoluteFrameForPresentationTime(presentationTimeUs: Long, sampleRateHz: Int): Long =
        microsecondsToFramesNearest(
            saturatingSubtract(presentationTimeUs, trackStartPresentationTimeUs),
            sampleRateHz
        )

    fun absoluteFrameForTimelinePosition(positionUs: Long, sampleRateHz: Int): Long =
        saturatingAdd(
            encoderDelayFrames.toLong(),
            microsecondsToFramesCeil(positionUs, sampleRateHz)
        )

    fun firstDecodedAbsoluteFrame(
        extractorStartPresentationTimeUs: Long,
        pcmSampleRateHz: Int,
        opusCodecDelayFrames: Long?
    ): Long = if (opusCodecDelayFrames != null && extractorStartPresentationTimeUs == trackStartPresentationTimeUs) {
        // The extractor clamps the first Opus packet's negative audible origin to zero.
        encoderDelayFrames.toLong() - opusCodecDelayFrames
    } else if (extractorStartPresentationTimeUs == trackStartPresentationTimeUs) encoderDelayFrames.toLong()
    else absoluteFrameForPresentationTime(extractorStartPresentationTimeUs, pcmSampleRateHz)

    fun extractorPositionForTimelineUs(positionUs: Long, sampleRateHz: Int): Long =
        saturatingAdd(
            trackStartPresentationTimeUs,
            framesToMicroseconds(
                absoluteFrameForTimelinePosition(positionUs, sampleRateHz),
                sampleRateHz
            )
        )
}

/** Extractor flags and codec flags have different meanings for bits 1 and 2. */
internal fun codecInputFlagsForExtractorSample(sampleFlags: Int): Int {
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
    val sync = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        MediaCodec.BUFFER_FLAG_KEY_FRAME
    } else 0
    val partial = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
        MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    } else 0
    return sync or partial
}

internal fun microsecondsToFramesCeil(microseconds: Long, sampleRateHz: Int): Long {
    require(microseconds >= 0L)
    require(sampleRateHz > 0)
    val wholeSeconds = microseconds / MICROS_PER_SECOND
    val remainderUs = microseconds % MICROS_PER_SECOND
    val wholeFrames = saturatingMultiply(wholeSeconds, sampleRateHz.toLong())
    val remainderNumerator = remainderUs * sampleRateHz
    val remainderFrames =
        (remainderNumerator + MICROS_PER_SECOND - 1L) / MICROS_PER_SECOND
    return saturatingAdd(wholeFrames, remainderFrames)
}

internal fun microsecondsToFramesNearest(microseconds: Long, sampleRateHz: Int): Long {
    require(sampleRateHz > 0)
    val wholeSeconds = microseconds / MICROS_PER_SECOND
    val remainderUs = microseconds % MICROS_PER_SECOND
    val wholeFrames = saturatingMultiply(wholeSeconds, sampleRateHz.toLong())
    val remainderNumerator = remainderUs * sampleRateHz
    val roundedRemainder = if (remainderNumerator >= 0L) {
        (remainderNumerator + MICROS_PER_SECOND / 2L) / MICROS_PER_SECOND
    } else {
        (remainderNumerator - MICROS_PER_SECOND / 2L) / MICROS_PER_SECOND
    }
    return saturatingAdd(wholeFrames, roundedRemainder)
}

internal fun framesToMicroseconds(frames: Long, sampleRateHz: Int): Long {
    require(sampleRateHz > 0)
    val wholeSeconds = frames / sampleRateHz
    val remainderFrames = frames % sampleRateHz
    return saturatingAdd(
        saturatingMultiply(wholeSeconds, MICROS_PER_SECOND),
        remainderFrames * MICROS_PER_SECOND / sampleRateHz
    )
}

private class BoundedDirectBuffer(
    initialCapacity: Int,
    private val maximumCapacity: Int
) {
    private var buffer = ByteBuffer.allocateDirect(initialCapacity).order(ByteOrder.nativeOrder())
    private var readOnlyView = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())

    fun writable(requiredBytes: Int): ByteBuffer {
        ensureCapacity(requiredBytes)
        return buffer.apply { clear() }
    }

    fun copyFrom(source: ByteBuffer, byteCount: Int) {
        val destination = writable(byteCount)
        destination.put(source)
        flip(byteCount)
    }

    fun flip(byteCount: Int) {
        buffer.position(0)
        buffer.limit(byteCount)
    }

    fun readOnlyWindow(byteOffset: Int, byteCount: Int): ByteBuffer {
        require(byteOffset >= 0 && byteCount >= 0)
        require(byteOffset.toLong() + byteCount <= buffer.limit().toLong())
        return readOnlyView.apply {
            position(0)
            limit(buffer.limit())
            position(byteOffset)
            limit(byteOffset + byteCount)
        }
    }

    private fun ensureCapacity(requiredBytes: Int) {
        if (requiredBytes <= buffer.capacity()) return
        if (requiredBytes > maximumCapacity) {
            throw IllegalStateException("Bounded PCM copy capacity exceeded")
        }
        var capacity = buffer.capacity()
        while (capacity < requiredBytes) {
            capacity = min(maximumCapacity, capacity * 2)
        }
        buffer = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())
        readOnlyView = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

private fun MediaFormat.stringOrNull(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

private fun saturatingAdd(left: Long, right: Long): Long = when {
    right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

private fun saturatingSubtract(left: Long, right: Long): Long = when {
    right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
    right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
    else -> left - right
}

private fun saturatingMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (_: ArithmeticException) {
    if ((left < 0L) xor (right < 0L)) Long.MIN_VALUE else Long.MAX_VALUE
}

private fun absoluteDifference(left: Long, right: Long): Long {
    val difference = saturatingSubtract(left, right)
    return when {
        difference == Long.MIN_VALUE -> Long.MAX_VALUE
        difference < 0L -> -difference
        else -> difference
    }
}

private fun updateMaximum(target: AtomicLong, candidate: Long) {
    var previous = target.get()
    while (candidate > previous && !target.compareAndSet(previous, candidate)) {
        previous = target.get()
    }
}

private const val MICROS_PER_SECOND = 1_000_000L
