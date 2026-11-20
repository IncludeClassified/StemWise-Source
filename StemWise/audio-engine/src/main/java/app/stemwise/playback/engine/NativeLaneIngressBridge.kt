package app.stemwise.playback.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class NativeSongBankRole { ACTIVE, CANDIDATE, LOOP_PREPARATION }

enum class NativeLaneEndOfStreamStatus {
    ACCEPTED,
    ALREADY_MARKED,
    STALE_GENERATION,
    BOUNDARY_MISMATCH,
    INVALID_ARGUMENT
}

internal data class NativeDecoderLease(
    val token: Long,
    val bankRole: NativeSongBankRole,
    val laneIndex: Int,
    val decoderId: String
) {
    init {
        require(token > 0L)
        require(laneIndex in 0 until NativeSongLaneIngressBank.LANE_COUNT)
        require(decoderId.isNotBlank())
    }
}

data class NativeDecoderLeaseSnapshot(
    val activeDecoderCount: Int,
    val candidateDecoderCount: Int,
    val loopPreparationDecoderCount: Int = 0
) {
    val totalDecoderCount: Int =
        activeDecoderCount + candidateDecoderCount + loopPreparationDecoderCount

    init {
        require(activeDecoderCount in 0..NativeSongLaneIngressBank.LANE_COUNT)
        require(candidateDecoderCount in 0..1)
        require(loopPreparationDecoderCount in 0..1)
        require(totalDecoderCount <= NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS)
    }
}

/**
 * Setup/control-thread lease gate for platform MediaCodec instances.
 *
 * Native storage is deliberately larger than decoder concurrency: both resident song banks own
 * bounded lane buffers, while the active bank may hold one decoder per declared music lane and
 * the candidate bank may hold exactly one, and one independent sequential loop-preparation lease
 * keeps the current Repeat head ready without displacing the candidate. Library size never changes
 * these fixed limits.
 */
internal class NativeDecoderLeaseCoordinator {
    private val activeLeases = arrayOfNulls<NativeDecoderLease>(
        NativeSongLaneIngressBank.LANE_COUNT
    )
    private var candidateLease: NativeDecoderLease? = null
    private var loopPreparationLease: NativeDecoderLease? = null
    private var nextToken = 0L

    @Synchronized
    fun tryAcquire(
        bankRole: NativeSongBankRole,
        laneIndex: Int,
        decoderId: String
    ): NativeDecoderLease? {
        require(laneIndex in 0 until NativeSongLaneIngressBank.LANE_COUNT)
        require(decoderId.isNotBlank())
        if (activeLeases.any { it?.decoderId == decoderId } ||
            candidateLease?.decoderId == decoderId ||
            loopPreparationLease?.decoderId == decoderId
        ) return null
        val activeCount = activeLeases.count { it != null }
        if (activeCount + (if (candidateLease == null) 0 else 1) +
            (if (loopPreparationLease == null) 0 else 1) >=
            MAXIMUM_OPEN_DECODERS
        ) return null
        when (bankRole) {
            NativeSongBankRole.ACTIVE -> if (activeLeases[laneIndex] != null) return null
            NativeSongBankRole.CANDIDATE -> if (candidateLease != null) return null
            NativeSongBankRole.LOOP_PREPARATION ->
                if (loopPreparationLease != null) return null
        }
        check(nextToken < Long.MAX_VALUE) { "Native decoder lease space exhausted" }
        val lease = NativeDecoderLease(
            token = ++nextToken,
            bankRole = bankRole,
            laneIndex = laneIndex,
            decoderId = decoderId
        )
        when (bankRole) {
            NativeSongBankRole.ACTIVE -> activeLeases[laneIndex] = lease
            NativeSongBankRole.CANDIDATE -> candidateLease = lease
            NativeSongBankRole.LOOP_PREPARATION -> loopPreparationLease = lease
        }
        return lease
    }

    @Synchronized
    fun release(lease: NativeDecoderLease): Boolean = when (lease.bankRole) {
        NativeSongBankRole.ACTIVE -> if (activeLeases[lease.laneIndex] == lease) {
            activeLeases[lease.laneIndex] = null
            true
        } else {
            false
        }
        NativeSongBankRole.CANDIDATE -> if (candidateLease == lease) {
            candidateLease = null
            true
        } else {
            false
        }
        NativeSongBankRole.LOOP_PREPARATION -> if (loopPreparationLease == lease) {
            loopPreparationLease = null
            true
        } else {
            false
        }
    }

    @Synchronized
    fun snapshot(): NativeDecoderLeaseSnapshot = NativeDecoderLeaseSnapshot(
        activeDecoderCount = activeLeases.count { it != null },
        candidateDecoderCount = if (candidateLease == null) 0 else 1,
        loopPreparationDecoderCount = if (loopPreparationLease == null) 0 else 1
    )

    @Synchronized
    fun clear() {
        activeLeases.fill(null)
        candidateLease = null
        loopPreparationLease = null
    }

    companion object {
        const val MAXIMUM_OPEN_DECODERS = app.stemwise.MAX_OPEN_AUDIO_DECODERS
    }
}

internal interface NativeLaneIngressBackend {
    fun createSongBank(queueCapacityFrames: Int, maximumInputQuantumFrames: Int): Long
    fun configureLane(
        bankHandle: Long,
        laneIndex: Int,
        sourceRateHz: Int,
        initialGeneration: Long
    ): Boolean
    fun offer(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long,
        presentationTimeUs: Long,
        encodingCode: Int,
        channelCount: Int,
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int
    ): Long
    fun advanceGeneration(bankHandle: Long, laneIndex: Int, generation: Long): Boolean
    fun markDiscontinuity(bankHandle: Long, laneIndex: Int, generation: Long): Boolean
    fun markEndOfStream(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long,
        finalSourceFrameExclusive: Long
    ): Int
    fun readableSourceFrames(bankHandle: Long, laneIndex: Int): Int
    fun pullOriginal48kForPreparation(
        bankHandle: Long, generation: Long, output: ByteBuffer, metadata: LongArray
    ): Int = error("Native preparation readback is unavailable")
    fun releaseLane(bankHandle: Long, laneIndex: Int)
    fun closeSongBank(bankHandle: Long)
}

/**
 * One of two resident native song banks. Each bank owns a bounded-capacity lane array:
 * Original followed by the declared stem lanes. This object owns buffers, not decoder leases.
 */
internal class NativeSongLaneIngressBank(
    queueCapacityFrames: Int = DEFAULT_QUEUE_CAPACITY_FRAMES,
    maximumInputQuantumFrames: Int = DEFAULT_MAXIMUM_INPUT_QUANTUM_FRAMES,
    private val backend: NativeLaneIngressBackend = NativeLaneIngressJniBackend
) : AutoCloseable {
    private val validatedQueueCapacityFrames = queueCapacityFrames.also {
        require(it in MINIMUM_QUEUE_CAPACITY_FRAMES..MAXIMUM_QUEUE_CAPACITY_FRAMES)
    }
    private val validatedInputQuantumFrames = maximumInputQuantumFrames.also {
        require(it in 1..validatedQueueCapacityFrames)
    }
    private val handle = AtomicLong(
        backend.createSongBank(
            validatedQueueCapacityFrames,
            validatedInputQuantumFrames
        ).also {
            check(it != 0L) { "Native lane ingress returned an invalid song-bank handle" }
        }
    )
    private val sinks = arrayOfNulls<NativeLaneIngressSink>(LANE_COUNT)

    @Synchronized
    fun configureLane(
        laneIndex: Int,
        sourceRateHz: Int,
        generationBase: Long
    ): NativeLaneIngressSink = configureLane(
        laneIndex = laneIndex,
        sourceRateHz = sourceRateHz,
        initialNativeGeneration = generationBase,
        decoderGenerationBase = generationBase
    )

    /**
     * Configures a lane after a possible seek-during-open race. [initialNativeGeneration] is the
     * whole-song generation the controller already published; [decoderGenerationBase] preserves
     * the stable mapping from this decoder's local seek generation to native ownership.
     */
    @Synchronized
    fun configureLane(
        laneIndex: Int,
        sourceRateHz: Int,
        initialNativeGeneration: Long,
        decoderGenerationBase: Long
    ): NativeLaneIngressSink {
        require(laneIndex in 0 until LANE_COUNT)
        require(sourceRateHz in 8_000..384_000)
        require(initialNativeGeneration > 0L)
        require(decoderGenerationBase > 0L)
        require(initialNativeGeneration >= decoderGenerationBase)
        check(sinks[laneIndex] == null) { "Native song lane $laneIndex is already configured" }
        val openHandle = requireOpenHandle()
        check(backend.configureLane(
            openHandle,
            laneIndex,
            sourceRateHz,
            initialNativeGeneration
        )) {
            "Native song lane $laneIndex rejected configuration"
        }
        return NativeLaneIngressSink(
            bankHandle = openHandle,
            laneIndex = laneIndex,
            sourceRateHz = sourceRateHz,
            decoderGenerationBase = decoderGenerationBase,
            backend = backend
        ).also { sinks[laneIndex] = it }
    }

    /** Caller first cancels/joins the lane's decoder worker, then releases its native storage. */
    @Synchronized
    fun releaseLane(laneIndex: Int): Boolean {
        require(laneIndex in 0 until LANE_COUNT)
        val sink = sinks[laneIndex] ?: return false
        sink.invalidate()
        sinks[laneIndex] = null
        backend.releaseLane(requireOpenHandle(), laneIndex)
        return true
    }

    @Synchronized
    fun readableSourceFrames(laneIndex: Int): Int {
        require(laneIndex in 0 until LANE_COUNT)
        return backend.readableSourceFrames(requireOpenHandle(), laneIndex).also {
            check(it >= 0) { "Native lane returned an invalid readable-frame count" }
        }
    }

    internal fun nativeHandleForRenderRuntime(): Long = requireOpenHandle()

    /** One preparation worker owns both decoder offers and pulls; no render runtime uses this bank. */
    internal fun pullOriginal48kForPreparation(
        generation: Long, output: ByteBuffer, metadata: LongArray
    ): Int {
        require(generation > 0L && output.isDirect && !output.isReadOnly)
        require(output.order() == ByteOrder.nativeOrder() && output.capacity() >= 8 && output.capacity() % 8 == 0)
        require(metadata.size == 3)
        return backend.pullOriginal48kForPreparation(requireOpenHandle(), generation, output, metadata).also {
            check(it in 0..output.capacity() / 8 && metadata[0] >= 0L && metadata[2] >= 0L) {
                "Native preparation readback returned invalid frames"
            }
        }
    }

    internal fun nativeIdentityForDiagnostics(): Long =
        requireOpenHandle() and 0x00ff_ffff_ffff_ffffL

    @Synchronized
    override fun close() {
        val openHandle = handle.getAndSet(0L)
        if (openHandle == 0L) return
        // Runtime shutdown must cancel and join all decoder workers before this call. Invalidating
        // first makes accidental later decoder offers fail locally instead of entering JNI.
        sinks.forEach { it?.invalidate() }
        sinks.fill(null)
        backend.closeSongBank(openHandle)
    }

    private fun requireOpenHandle(): Long = handle.get().also {
        check(it != 0L) { "Native song lane ingress bank is closed" }
    }

    companion object {
        const val LANE_COUNT = app.stemwise.MAX_NATIVE_MUSIC_LANES
        const val ORIGINAL_LANE_INDEX = 0
        const val FIRST_STEM_LANE_INDEX = 1
        const val MINIMUM_QUEUE_CAPACITY_FRAMES = 64
        const val MAXIMUM_QUEUE_CAPACITY_FRAMES = 1 shl 20
        const val DEFAULT_QUEUE_CAPACITY_FRAMES = 16_384
        const val DEFAULT_MAXIMUM_INPUT_QUANTUM_FRAMES = 4_096
    }
}

/** Non-retaining synchronous sink passed directly to one [MediaCodecTrackDecoder]. */
internal class NativeLaneIngressSink internal constructor(
    private val bankHandle: Long,
    private val laneIndex: Int,
    private val sourceRateHz: Int,
    private val decoderGenerationBase: Long,
    private val backend: NativeLaneIngressBackend
) : NativePcmSink {
    private val valid = AtomicBoolean(true)

    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(block.format.sampleRateHz == sourceRateHz) {
            "A decoder output-rate change requires a replacement native lane"
        }
        require(block.data.isDirect) { "Native lane ingress requires direct decoder PCM" }
        require(block.data.isReadOnly) { "Decoder PCM must remain a read-only transient view" }
        require(block.data.order() == ByteOrder.nativeOrder()) {
            "Decoder PCM must use native byte order"
        }
        val mappedGeneration = try {
            Math.addExact(decoderGenerationBase, block.seekGeneration)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Native lane generation space exhausted")
        }
        return offerAtNativeGeneration(block, mappedGeneration)
    }

    /** Controller-authorized explicit global epoch; decoder-local generations never escape JNI. */
    internal fun offerAtNativeGeneration(
        block: NativePcmBlock,
        nativeGeneration: Long
    ): NativePcmSinkResult {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(nativeGeneration > 0L)
        require(block.format.sampleRateHz == sourceRateHz)
        require(block.data.isDirect && block.data.isReadOnly)
        require(block.data.order() == ByteOrder.nativeOrder())
        val packed = backend.offer(
            bankHandle = bankHandle,
            laneIndex = laneIndex,
            generation = nativeGeneration,
            presentationTimeUs = block.presentationTimeUs,
            encodingCode = when (block.format.encoding) {
                NativePcmEncoding.PCM_16_BIT -> ENCODING_SIGNED_16
                NativePcmEncoding.PCM_FLOAT -> ENCODING_FLOAT_32
            },
            channelCount = block.format.channelCount,
            directBuffer = block.data,
            byteOffset = block.data.position(),
            frameCount = block.frameCount
        )
        val status = (packed ushr Int.SIZE_BITS).toInt()
        val acceptedFramesLong = packed and 0xffff_ffffL
        check(acceptedFramesLong <= block.frameCount.toLong()) {
            "Native lane accepted more PCM frames than offered"
        }
        val acceptedBytes = Math.multiplyExact(
            acceptedFramesLong.toInt(),
            block.format.bytesPerFrame
        )
        return when (status) {
            STATUS_ACCEPTED, STATUS_STALE_GENERATION -> {
                check(acceptedFramesLong == block.frameCount.toLong()) {
                    "Native lane reported a non-complete terminal offer"
                }
                NativePcmSinkResult.accepting(acceptedBytes, 0L)
            }
            STATUS_PARTIAL -> {
                check(acceptedBytes in 1 until block.data.remaining()) {
                    "Native lane returned an invalid partial offer"
                }
                NativePcmSinkResult.accepting(acceptedBytes, RETRY_DELAY_US)
            }
            STATUS_BACKPRESSURED -> {
                check(acceptedFramesLong == 0L) {
                    "Backpressure cannot accept a hidden PCM prefix"
                }
                // Polling is intentionally bounded. The native render worker never enters JNI to
                // wake Java, and the output consumer never touches this ingress queue.
                NativePcmSinkResult.accepting(0, RETRY_DELAY_US)
            }
            STATUS_AFTER_END_OF_STREAM -> error(
                "Decoder PCM arrived after the lane's final audible boundary"
            )
            else -> error("Native lane ingress rejected a decoder PCM contract")
        }
    }

    fun advanceDecoderGeneration(decoderSeekGeneration: Long): Boolean {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(decoderSeekGeneration >= 0L)
        val mapped = try {
            Math.addExact(decoderGenerationBase, decoderSeekGeneration)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Native lane generation space exhausted")
        }
        return advanceNativeGeneration(mapped)
    }

    internal fun advanceNativeGeneration(nativeGeneration: Long): Boolean {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(nativeGeneration > 0L)
        return backend.advanceGeneration(bankHandle, laneIndex, nativeGeneration)
    }

    fun markDiscontinuity(decoderSeekGeneration: Long): Boolean {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(decoderSeekGeneration >= 0L)
        val mapped = try {
            Math.addExact(decoderGenerationBase, decoderSeekGeneration)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Native lane generation space exhausted")
        }
        return backend.markDiscontinuity(bankHandle, laneIndex, mapped)
    }

    /**
     * Called on the decoder worker after its final PCM suffix was fully accepted by [offer]. The
     * boundary is the exact exclusive audible frame in this lane's decoded source-rate domain.
     * A partial/backpressured final suffix must be retried before this method is called.
     */
    fun markEndOfStream(
        decoderSeekGeneration: Long,
        finalAudibleSourceFrameExclusive: Long
    ): NativeLaneEndOfStreamStatus {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(decoderSeekGeneration >= 0L)
        require(finalAudibleSourceFrameExclusive >= 0L)
        val mapped = try {
            Math.addExact(decoderGenerationBase, decoderSeekGeneration)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Native lane generation space exhausted")
        }
        return markEndOfStreamAtNativeGeneration(mapped, finalAudibleSourceFrameExclusive)
    }

    internal fun markEndOfStreamAtNativeGeneration(
        nativeGeneration: Long,
        finalAudibleSourceFrameExclusive: Long
    ): NativeLaneEndOfStreamStatus {
        check(valid.get()) { "Native lane ingress sink is no longer configured" }
        require(nativeGeneration > 0L)
        require(finalAudibleSourceFrameExclusive >= 0L)
        return when (backend.markEndOfStream(
            bankHandle,
            laneIndex,
            nativeGeneration,
            finalAudibleSourceFrameExclusive
        )) {
            0 -> NativeLaneEndOfStreamStatus.ACCEPTED
            1 -> NativeLaneEndOfStreamStatus.ALREADY_MARKED
            2 -> NativeLaneEndOfStreamStatus.STALE_GENERATION
            3 -> NativeLaneEndOfStreamStatus.BOUNDARY_MISMATCH
            else -> NativeLaneEndOfStreamStatus.INVALID_ARGUMENT
        }
    }

    internal fun invalidate() {
        valid.set(false)
    }

    companion object {
        internal const val STATUS_ACCEPTED = 0
        internal const val STATUS_PARTIAL = 1
        internal const val STATUS_BACKPRESSURED = 2
        internal const val STATUS_STALE_GENERATION = 3
        internal const val STATUS_AFTER_END_OF_STREAM = 5
        internal const val ENCODING_SIGNED_16 = 1
        internal const val ENCODING_FLOAT_32 = 2
        private const val RETRY_DELAY_US = 500L
    }
}

private object NativeLaneIngressJniBackend : NativeLaneIngressBackend {
    override external fun createSongBank(
        queueCapacityFrames: Int,
        maximumInputQuantumFrames: Int
    ): Long
    override external fun configureLane(
        bankHandle: Long,
        laneIndex: Int,
        sourceRateHz: Int,
        initialGeneration: Long
    ): Boolean
    override external fun offer(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long,
        presentationTimeUs: Long,
        encodingCode: Int,
        channelCount: Int,
        directBuffer: ByteBuffer,
        byteOffset: Int,
        frameCount: Int
    ): Long
    override external fun advanceGeneration(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long
    ): Boolean
    override external fun markDiscontinuity(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long
    ): Boolean
    override external fun markEndOfStream(
        bankHandle: Long,
        laneIndex: Int,
        generation: Long,
        finalSourceFrameExclusive: Long
    ): Int
    override external fun readableSourceFrames(bankHandle: Long, laneIndex: Int): Int
    override external fun pullOriginal48kForPreparation(
        bankHandle: Long, generation: Long, output: ByteBuffer, metadata: LongArray
    ): Int
    override external fun releaseLane(bankHandle: Long, laneIndex: Int)
    override external fun closeSongBank(bankHandle: Long)

    init {
        System.loadLibrary("stemwise_audio_output")
    }
}
