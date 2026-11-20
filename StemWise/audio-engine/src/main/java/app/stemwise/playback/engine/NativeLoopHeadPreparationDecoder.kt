package app.stemwise.playback.engine

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class NativeEngineLoopPreparationSpec(
    val decoderId: String,
    val laneId: String,
    val laneIndex: Int,
    val location: String,
    val initialPositionUs: Long,
    val loopRevision: Long,
    val preparationGeneration: Long,
    val directFloat32Audio: Boolean = false,
    val sourceAdmission: NativeSourceAdmission = NativeSourceAdmission()
) {
    init {
        require(decoderId.isNotBlank() && laneId.isNotBlank() && location.isNotBlank())
        require(laneIndex in 0 until NativeSongLaneIngressBank.LANE_COUNT)
        require(initialPositionUs >= 0L)
        require(loopRevision > 0L && preparationGeneration > 0L)
    }
}

internal enum class NativeEngineLoopPreparationOutcome {
    RUNNING,
    LANE_COMPLETED,
    CACHE_COMMITTED,
    CANCELLED,
    FAILED
}

internal data class NativeEngineLoopPreparationSnapshot(
    val outcome: NativeEngineLoopPreparationOutcome,
    val sourceRateHz: Int?,
    val finalSourceFrameExclusive: Long,
    val cause: String?
)

internal interface NativeEngineLoopPreparationDecoder : AutoCloseable {
    val decoderId: String
    val laneIndex: Int
    fun start(): Boolean
    fun snapshot(): NativeEngineLoopPreparationSnapshot
    fun requestCancel()
    fun pollJoin(): Boolean
    fun cancelAndJoin(watchdogMs: Long): NativeEngineDecoderJoinStatus
    fun safeToRelease(): Boolean
}

/**
 * One dedicated sequential MediaCodec worker feeding bounded loop-head staging.
 * It never owns a song-bank lane and is always joined before its one preparation lease is reused.
 */
internal class JniNativeLoopHeadPreparationDecoder(
    context: Context,
    private val runtime: NativePlaybackRuntime,
    private val lease: NativeDecoderLease,
    private val leaseCoordinator: NativeDecoderLeaseCoordinator,
    private val spec: NativeEngineLoopPreparationSpec,
    workerPool: NativeDecoderWorkerPool,
    packetReaderFactory: AudioPacketReaderFactory
) : NativeEngineLoopPreparationDecoder, NativePcmSink, NativeMediaCodecDecoderListener {
    override val decoderId: String = spec.decoderId
    override val laneIndex: Int = spec.laneIndex

    private val outcome = AtomicReference(NativeEngineLoopPreparationOutcome.RUNNING)
    private val failureCause = AtomicReference<String?>(null)
    private val configuredFormat = AtomicReference<NativePcmFormat?>(null)
    private val decoder: MediaCodecTrackDecoder
    @Volatile private var acceptedSourceFrameExclusive = 0L
    @Volatile private var joined = false
    private var retirement: NativePreparationRetirement? = null
    @Volatile private var closed = false

    init {
        decoder = MediaCodecTrackDecoder(
            context = context,
            decoderId = spec.decoderId,
            laneId = spec.laneId,
            source = NativeMediaSource.fromLocation(spec.location, spec.directFloat32Audio)
                .withAdmission(spec.sourceAdmission),
            sink = this,
            listener = this,
            config = MediaCodecTrackDecoderConfig(initialPositionUs = spec.initialPositionUs,
                retainResamplerHistory = true),
            workerPool = workerPool,
            packetReaderFactory = packetReaderFactory
        )
    }

    override fun start(): Boolean = decoder.start()

    override fun onOutputFormatChanged(format: NativePcmFormat, seekGeneration: Long) {
        if (outcome.get() != NativeEngineLoopPreparationOutcome.RUNNING) return
        val previous = configuredFormat.get()
        if (previous != null && previous != format) {
            fail("Loop-head decoder output format changed from $previous to $format")
            return
        }
        when (runtime.configureLoopHeadPreparationLane(
            loopRevision = spec.loopRevision,
            laneIndex = spec.laneIndex,
            sourceRateHz = format.sampleRateHz,
            preparationGeneration = spec.preparationGeneration
        )) {
            NativeRuntimeLoopHeadConfigureStatus.ACCEPTED,
            NativeRuntimeLoopHeadConfigureStatus.ALREADY_CONFIGURED -> configuredFormat.set(format)
            else -> fail("Native loop-head lane configuration was rejected")
        }
    }

    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        if (outcome.get() != NativeEngineLoopPreparationOutcome.RUNNING) {
            return NativePcmSinkResult.all(block)
        }
        val format = configuredFormat.get()
        if (format == null || format != block.format) {
            fail("Loop-head PCM arrived without its exact configured format")
            return NativePcmSinkResult.permanentlyStalled()
        }
        val offered = try {
            runtime.offerLoopHeadPreparationPcm(
                loopRevision = spec.loopRevision,
                laneIndex = spec.laneIndex,
                preparationGeneration = spec.preparationGeneration,
                block = block
            )
        } catch (failure: Throwable) {
            fail(failure.message ?: "Loop-head JNI offer failed")
            return NativePcmSinkResult.permanentlyStalled()
        }
        if (offered.inputFramesAccepted > block.frameCount) {
            fail("Native loop-head offer accepted beyond the decoder block")
            return NativePcmSinkResult.permanentlyStalled()
        }
        val acceptedBytes = try {
            Math.multiplyExact(offered.inputFramesAccepted, format.bytesPerFrame)
        } catch (_: ArithmeticException) {
            fail("Loop-head accepted-byte count overflowed")
            return NativePcmSinkResult.permanentlyStalled()
        }
        if (offered.inputFramesAccepted > 0) {
            acceptedSourceFrameExclusive = maxOf(
                acceptedSourceFrameExclusive,
                block.firstTimelineFrame + offered.inputFramesAccepted.toLong()
            )
        }
        return when (offered.status) {
            NativeRuntimeLoopHeadOfferStatus.ACCEPTED,
            NativeRuntimeLoopHeadOfferStatus.PARTIAL ->
                NativePcmSinkResult.accepting(acceptedBytes)
            NativeRuntimeLoopHeadOfferStatus.BACKPRESSURED ->
                NativePcmSinkResult.accepting(acceptedBytes)
            NativeRuntimeLoopHeadOfferStatus.LANE_COMPLETED -> {
                complete(NativeEngineLoopPreparationOutcome.LANE_COMPLETED)
                NativePcmSinkResult.accepting(acceptedBytes, 0L)
            }
            NativeRuntimeLoopHeadOfferStatus.CACHE_COMMITTED -> {
                complete(NativeEngineLoopPreparationOutcome.CACHE_COMMITTED)
                NativePcmSinkResult.accepting(acceptedBytes, 0L)
            }
            else -> {
                fail("Native loop-head offer failed with ${offered.status}")
                NativePcmSinkResult.permanentlyStalled()
            }
        }
    }

    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
        while (outcome.get() == NativeEngineLoopPreparationOutcome.RUNNING) {
            val status = try {
                runtime.markLoopHeadPreparationEndOfStream(
                    loopRevision = spec.loopRevision,
                    laneIndex = spec.laneIndex,
                    preparationGeneration = spec.preparationGeneration,
                    finalSourceFrameExclusive = acceptedSourceFrameExclusive
                )
            } catch (failure: Throwable) {
                fail(failure.message ?: "Loop-head end-of-stream publication failed")
                return
            }
            when (status) {
                NativeRuntimeLoopHeadEndStatus.PADDING_PENDING -> continue
                NativeRuntimeLoopHeadEndStatus.LANE_COMPLETED,
                NativeRuntimeLoopHeadEndStatus.ALREADY_COMPLETED ->
                    complete(NativeEngineLoopPreparationOutcome.LANE_COMPLETED)
                NativeRuntimeLoopHeadEndStatus.CACHE_COMMITTED ->
                    complete(NativeEngineLoopPreparationOutcome.CACHE_COMMITTED)
                else -> fail("Native loop-head end-of-stream failed with $status")
            }
        }
    }

    override fun onCancelled(seekGeneration: Long) {
        outcome.compareAndSet(
            NativeEngineLoopPreparationOutcome.RUNNING,
            NativeEngineLoopPreparationOutcome.CANCELLED
        )
    }

    override fun onError(failure: NativeDecoderFailure, diagnostics: NativeDecoderDiagnostics) {
        fail("${failure.code}: ${failure.message ?: "loop-head decoder failed"}")
    }

    override fun snapshot(): NativeEngineLoopPreparationSnapshot {
        return NativeEngineLoopPreparationSnapshot(
            outcome = outcome.get(),
            sourceRateHz = configuredFormat.get()?.sampleRateHz,
            finalSourceFrameExclusive = acceptedSourceFrameExclusive,
            cause = failureCause.get()
        )
    }

    @Synchronized
    override fun requestCancel() {
        outcome.compareAndSet(
            NativeEngineLoopPreparationOutcome.RUNNING,
            NativeEngineLoopPreparationOutcome.CANCELLED
        )
        if (retirement == null) {
            retirement = NativePreparationRetirement(
                stop = { try { decoder.cancel() } finally { decoder.close() } },
                isJoined = { decoder.awaitClosed(100, TimeUnit.MILLISECONDS) },
                release = { joined = true }
            )
        }
    }

    override fun pollJoin(): Boolean = retirement?.await(0L, TimeUnit.NANOSECONDS) == true

    override fun cancelAndJoin(watchdogMs: Long): NativeEngineDecoderJoinStatus {
        require(watchdogMs > 0L)
        if (joined) return NativeEngineDecoderJoinStatus.JOINED
        requestCancel()
        return if (requireNotNull(retirement).await(watchdogMs, TimeUnit.MILLISECONDS)) {
            NativeEngineDecoderJoinStatus.JOINED
        } else NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED
    }

    override fun safeToRelease(): Boolean = joined

    @Synchronized
    override fun close() {
        if (closed) return
        check(joined) { "Join the loop-head decoder before releasing its lease" }
        check(leaseCoordinator.release(lease)) { "Loop-head decoder lease ownership was lost" }
        closed = true
    }

    private fun complete(completed: NativeEngineLoopPreparationOutcome) {
        if (outcome.compareAndSet(NativeEngineLoopPreparationOutcome.RUNNING, completed)) {
            requestCancel()
        }
    }

    private fun fail(cause: String) {
        failureCause.compareAndSet(null, cause)
        if (outcome.compareAndSet(
                NativeEngineLoopPreparationOutcome.RUNNING,
                NativeEngineLoopPreparationOutcome.FAILED
            )) {
            requestCancel()
        }
    }
}
