package app.stemwise.playback.engine

/** Lifecycle visible to the controller without exposing decoder or JNI implementation details. */
enum class DecoderNativeLaneAdapterState {
    WAITING_FOR_FORMAT,
    STREAMING,
    ENDED,
    QUIESCING,
    JOINED,
    FAILED
}

enum class DecoderNativeLaneAdapterFailure {
    PREPARED_IDENTITY_MISMATCH,
    LANE_CONFIGURATION_FAILED,
    OUTPUT_FORMAT_CHANGED,
    INVALID_SINK_RESULT,
    SOURCE_FRAME_SPACE_EXHAUSTED,
    END_OF_STREAM_WITH_PENDING_PCM,
    END_OF_STREAM_WITHOUT_FORMAT,
    END_OF_STREAM_REJECTED,
    DECODER_FAILED
}

data class DecoderNativeLaneAdapterDiagnostics(
    val state: DecoderNativeLaneAdapterState,
    val configuredFormat: NativePcmFormat?,
    val decoderSeekGeneration: Long,
    val nativeGeneration: Long,
    val firstAcceptedSourceFrame: Long?,
    val acceptedSourceFrameExclusive: Long?,
    val acceptedFrames: Long,
    val offerCalls: Long,
    val discardedStaleOffers: Long,
    val waitingFutureOffers: Long,
    val ignoredCallbacks: Long,
    val discontinuities: Long,
    val endOfStreamStatus: NativeLaneEndOfStreamStatus?,
    val decoderTerminalCallbackSeen: Boolean,
    val safeToReleaseLane: Boolean,
    val failure: DecoderNativeLaneAdapterFailure?,
    val failureDiagnostic: String? = null
)

internal fun DecoderNativeLaneAdapterDiagnostics.toNativeDecoderHead(): NativeEngineDecoderHead {
    val format = configuredFormat ?: return NativeEngineDecoderHead(false, 0L, null, null,
        0L, nativeGeneration)
    fun floor48k(frame: Long): Long = try {
        Math.multiplyExact(frame, NATIVE_ENGINE_SAMPLE_RATE_HZ.toLong()) / format.sampleRateHz
    } catch (_: ArithmeticException) { Long.MAX_VALUE }
    val acceptedEos = endOfStreamStatus == NativeLaneEndOfStreamStatus.ACCEPTED ||
        endOfStreamStatus == NativeLaneEndOfStreamStatus.ALREADY_MARKED
    val first = firstAcceptedSourceFrame?.let(::floor48k)
    // Native SRC flushes through the ceil boundary only after it accepts this exact EOS.
    val end = acceptedSourceFrameExclusive?.let {
        if (acceptedEos) sourceBoundaryToNativeTimelineCeil(it, format.sampleRateHz) else floor48k(it)
    }
    return NativeEngineDecoderHead(
        configured = true,
        acceptedTimelineFrames48k = if (acceptedEos && first != null && end != null && discontinuities == 0L)
            (end - first).coerceAtLeast(0L) else floor48k(acceptedFrames),
        firstTimelineFrame48k = first,
        acceptedTimelineFrameExclusive48k = end,
        discontinuities = discontinuities,
        nativeGeneration = nativeGeneration,
        endOfStreamTimelineFrameExclusive48k = if (acceptedEos) end ?: 0L else null
    )
}

/**
 * Narrow testable port over one configured native lane. It deliberately has no generation-advance
 * operation: only the controller/runtime may publish an atomic whole-song generation boundary.
 */
internal interface DecoderNativeLaneTarget {
    fun offer(block: NativePcmBlock): NativePcmSinkResult
    fun offerAtNativeGeneration(
        block: NativePcmBlock,
        nativeGeneration: Long
    ): NativePcmSinkResult = offer(block)
    fun markEndOfStream(
        decoderSeekGeneration: Long,
        finalSourceFrameExclusive: Long
    ): NativeLaneEndOfStreamStatus
    fun markEndOfStreamAtNativeGeneration(
        decoderSeekGeneration: Long,
        nativeGeneration: Long,
        finalSourceFrameExclusive: Long
    ): NativeLaneEndOfStreamStatus = markEndOfStream(
        decoderSeekGeneration,
        finalSourceFrameExclusive
    )
}

internal fun interface DecoderNativeLaneTargetFactory {
    fun configure(
        format: NativePcmFormat,
        initialNativeGeneration: Long
    ): DecoderNativeLaneTarget
}

/**
 * One decoder-worker adapter for one native song lane.
 *
 * The controller must atomically advance the whole native song bank before calling
 * [authorizeDecoderGeneration]. This adapter only validates the decoder's local generation and
 * maps it through [generationBase]; it never advances a lane independently.
 *
 * Shutdown is intentionally two-phase: call [beginCancellation], cancel and join the decoder, then
 * call [confirmDecoderJoined]. Native lane storage may be released only when diagnostics reports
 * [DecoderNativeLaneAdapterDiagnostics.safeToReleaseLane].
 */
internal class DecoderNativeLaneAdapter internal constructor(
    private val decoderId: String,
    private val laneId: String,
    private val generationBase: Long,
    private val targetFactory: DecoderNativeLaneTargetFactory,
    private val decoderFailureObserver: (
        NativeDecoderFailure,
        NativeDecoderDiagnostics
    ) -> Unit = { _, _ -> }
) : NativePcmSink, NativeMediaCodecDecoderListener {
    private var state = DecoderNativeLaneAdapterState.WAITING_FOR_FORMAT
    private var target: DecoderNativeLaneTarget? = null
    private var stableFormat: NativePcmFormat? = null
    private var authorizedDecoderGeneration = 0L
    private var mappedNativeGeneration = generationBase
    private var firstAcceptedSourceFrame: Long? = null
    private var acceptedSourceFrameExclusive: Long? = null
    private var generationAcceptedFrames = 0L
    private var offerCalls = 0L
    private var staleOffers = 0L
    private var futureOffers = 0L
    private var ignoredCallbacks = 0L
    private var discontinuities = 0L
    private var eosStatus: NativeLaneEndOfStreamStatus? = null
    private var partialPcmPending = false
    private var pendingFormatGeneration = -1L
    private var pendingFormat: NativePcmFormat? = null
    private var pendingEndGeneration = -1L
    private var cancellationBegun = false
    private var decoderTerminalCallbackSeen = false
    private var failure: DecoderNativeLaneAdapterFailure? = null
    private var failureDiagnostic: String? = null

    init {
        require(decoderId.isNotBlank())
        require(laneId.isNotBlank())
        require(generationBase > 0L)
    }

    /**
     * Records a generation already published atomically by the native whole-song controller.
     * Replays deferred format/EOS callbacks only after that publication; it cannot initiate
     * a one-lane ownership transition.
     */
    @Synchronized
    fun authorizeDecoderGeneration(decoderSeekGeneration: Long): Long {
        return authorizeDecoderGeneration(
            decoderSeekGeneration,
            mapGeneration(decoderSeekGeneration)
        )
    }

    /**
     * Preferred controller path: maps a decoder-local seek to one explicit global output epoch.
     * This avoids independent bank bases reusing an epoch after another bank has sought ahead.
     */
    @Synchronized
    fun authorizeDecoderGeneration(
        decoderSeekGeneration: Long,
        nativeGeneration: Long
    ): Long {
        require(decoderSeekGeneration >= authorizedDecoderGeneration) {
            "Decoder generations cannot move backwards"
        }
        require(nativeGeneration >= mappedNativeGeneration) {
            "Native generations cannot move backwards"
        }
        check(
            state != DecoderNativeLaneAdapterState.QUIESCING &&
                state != DecoderNativeLaneAdapterState.JOINED &&
                state != DecoderNativeLaneAdapterState.FAILED
        ) { "A terminal decoder lane cannot authorize another generation" }
        if (decoderSeekGeneration == authorizedDecoderGeneration) {
            require(nativeGeneration == mappedNativeGeneration) {
                "One decoder generation cannot be remapped after authorization"
            }
            return nativeGeneration
        }
        authorizedDecoderGeneration = decoderSeekGeneration
        mappedNativeGeneration = nativeGeneration
        firstAcceptedSourceFrame = null
        acceptedSourceFrameExclusive = null
        generationAcceptedFrames = 0L
        partialPcmPending = false
        eosStatus = null
        state = if (target == null) {
            DecoderNativeLaneAdapterState.WAITING_FOR_FORMAT
        } else {
            DecoderNativeLaneAdapterState.STREAMING
        }
        if (pendingFormatGeneration <= decoderSeekGeneration) {
            val format = pendingFormat
            val formatGeneration = pendingFormatGeneration
            pendingFormat = null
            pendingFormatGeneration = -1L
            if (format != null && formatGeneration == decoderSeekGeneration) {
                onOutputFormatChanged(format, decoderSeekGeneration)
            }
        }
        if (pendingEndGeneration <= decoderSeekGeneration) {
            val ended = pendingEndGeneration == decoderSeekGeneration
            pendingEndGeneration = -1L
            if (ended) onEndOfStream(decoderSeekGeneration, null)
        }
        return nativeGeneration
    }

    @Synchronized
    fun tryAuthorizeDecoderGeneration(decoderSeekGeneration: Long, nativeGeneration: Long): Boolean {
        if (callbacksAreTerminal() || decoderSeekGeneration < authorizedDecoderGeneration ||
            nativeGeneration < mappedNativeGeneration ||
            (decoderSeekGeneration == authorizedDecoderGeneration && nativeGeneration != mappedNativeGeneration)
        ) return false
        authorizeDecoderGeneration(decoderSeekGeneration, nativeGeneration)
        return state != DecoderNativeLaneAdapterState.FAILED
    }

    @Synchronized
    override fun offer(block: NativePcmBlock): NativePcmSinkResult {
        offerCalls = saturatingIncrement(offerCalls)
        if (state == DecoderNativeLaneAdapterState.QUIESCING ||
            state == DecoderNativeLaneAdapterState.JOINED
        ) {
            staleOffers = saturatingIncrement(staleOffers)
            return NativePcmSinkResult.all(block)
        }
        if (state == DecoderNativeLaneAdapterState.FAILED) {
            return NativePcmSinkResult.permanentlyStalled()
        }
        if (block.seekGeneration < authorizedDecoderGeneration ||
            (state == DecoderNativeLaneAdapterState.ENDED &&
                block.seekGeneration == authorizedDecoderGeneration)
        ) {
            staleOffers = saturatingIncrement(staleOffers)
            return NativePcmSinkResult.all(block)
        }
        if (block.seekGeneration > authorizedDecoderGeneration) {
            // The controller has not yet published this generation for every lane. Keep the exact
            // block pending in the decoder instead of leaking one lane into newer ownership.
            futureOffers = saturatingIncrement(futureOffers)
            return NativePcmSinkResult.accepting(0, FUTURE_GENERATION_RETRY_US)
        }
        if (!ensureConfigured(block.format) || stableFormat != block.format) {
            fail(DecoderNativeLaneAdapterFailure.OUTPUT_FORMAT_CHANGED)
            return NativePcmSinkResult.permanentlyStalled()
        }

        val configuredTarget = requireNotNull(target)
        val result = try {
            configuredTarget.offerAtNativeGeneration(block, mappedNativeGeneration)
        } catch (error: Throwable) {
            fail(DecoderNativeLaneAdapterFailure.INVALID_SINK_RESULT, "offer", error)
            return NativePcmSinkResult.permanentlyStalled()
        }
        if (!isValidResult(result, block)) {
            fail(DecoderNativeLaneAdapterFailure.INVALID_SINK_RESULT)
            return NativePcmSinkResult.permanentlyStalled()
        }

        val acceptedFrames = result.acceptedBytes / block.format.bytesPerFrame
        partialPcmPending = result.acceptedBytes != block.data.remaining()
        if (acceptedFrames == 0) return result
        val acceptedEnd = try {
            Math.addExact(block.firstTimelineFrame, acceptedFrames.toLong())
        } catch (_: ArithmeticException) {
            fail(DecoderNativeLaneAdapterFailure.SOURCE_FRAME_SPACE_EXHAUSTED)
            return NativePcmSinkResult.permanentlyStalled()
        }
        val previousEnd = acceptedSourceFrameExclusive
        if (previousEnd != null && previousEnd != block.firstTimelineFrame) {
            discontinuities = saturatingIncrement(discontinuities)
        }
        if (firstAcceptedSourceFrame == null) {
            firstAcceptedSourceFrame = block.firstTimelineFrame
        }
        // Mirrors the native producer's overlap rule: a wholly repeated prefix cannot move the
        // exclusive boundary backwards, while a gap or a newly queued suffix ends at acceptedEnd.
        acceptedSourceFrameExclusive = maxOf(previousEnd ?: acceptedEnd, acceptedEnd)
        generationAcceptedFrames = saturatingAdd(
            generationAcceptedFrames,
            acceptedFrames.toLong()
        )
        state = DecoderNativeLaneAdapterState.STREAMING
        return result
    }

    @Synchronized
    override fun onPrepared(prepared: NativeDecoderPrepared) {
        if (callbacksAreTerminal()) return ignoreCallback()
        if (prepared.decoderId != decoderId || prepared.laneId != laneId) {
            fail(DecoderNativeLaneAdapterFailure.PREPARED_IDENTITY_MISMATCH)
        }
    }

    @Synchronized
    override fun onOutputFormatChanged(format: NativePcmFormat, seekGeneration: Long) {
        if (callbacksAreTerminal() || seekGeneration < authorizedDecoderGeneration) {
            return ignoreCallback()
        }
        if (seekGeneration > authorizedDecoderGeneration) {
            // A seek beyond EOS may produce no PCM offer to replay this format after authorization.
            if (seekGeneration >= pendingFormatGeneration) {
                pendingFormatGeneration = seekGeneration
                pendingFormat = format
            }
            return ignoreCallback()
        }
        ensureConfigured(format)
        if (stableFormat != null && stableFormat != format) {
            fail(DecoderNativeLaneAdapterFailure.OUTPUT_FORMAT_CHANGED)
        }
    }

    @Synchronized
    override fun onEndOfStream(seekGeneration: Long, finalPresentationTimeUs: Long?) {
        if (callbacksAreTerminal() || seekGeneration < authorizedDecoderGeneration) {
            return ignoreCallback()
        }
        if (seekGeneration > authorizedDecoderGeneration) {
            // The codec parks at EOS and will not repeat this callback when the actor catches up.
            pendingEndGeneration = maxOf(pendingEndGeneration, seekGeneration)
            return
        }
        if (partialPcmPending) {
            fail(DecoderNativeLaneAdapterFailure.END_OF_STREAM_WITH_PENDING_PCM)
            return
        }
        val configuredTarget = target
        if (configuredTarget == null) {
            fail(DecoderNativeLaneAdapterFailure.END_OF_STREAM_WITHOUT_FORMAT)
            return
        }
        val exactBoundary = acceptedSourceFrameExclusive ?: 0L
        val status = try {
            configuredTarget.markEndOfStreamAtNativeGeneration(
                seekGeneration,
                mappedNativeGeneration,
                exactBoundary
            )
        } catch (error: Throwable) {
            fail(DecoderNativeLaneAdapterFailure.END_OF_STREAM_REJECTED, "end-of-stream", error)
            return
        }
        eosStatus = status
        when (status) {
            NativeLaneEndOfStreamStatus.ACCEPTED,
            NativeLaneEndOfStreamStatus.ALREADY_MARKED -> {
                state = DecoderNativeLaneAdapterState.ENDED
            }
            NativeLaneEndOfStreamStatus.STALE_GENERATION -> ignoreCallback()
            NativeLaneEndOfStreamStatus.BOUNDARY_MISMATCH,
            NativeLaneEndOfStreamStatus.INVALID_ARGUMENT -> {
                fail(DecoderNativeLaneAdapterFailure.END_OF_STREAM_REJECTED)
            }
        }
    }

    @Synchronized
    override fun onCancelled(seekGeneration: Long) {
        @Suppress("UNUSED_VARIABLE")
        val terminalGeneration = seekGeneration
        decoderTerminalCallbackSeen = true
        if (state == DecoderNativeLaneAdapterState.JOINED ||
            state == DecoderNativeLaneAdapterState.FAILED
        ) {
            ignoreCallback()
            return
        }
        if (state != DecoderNativeLaneAdapterState.JOINED &&
            state != DecoderNativeLaneAdapterState.FAILED
        ) {
            state = DecoderNativeLaneAdapterState.QUIESCING
        }
    }

    @Synchronized
    override fun onError(
        failure: NativeDecoderFailure,
        diagnostics: NativeDecoderDiagnostics
    ) {
        decoderTerminalCallbackSeen = true
        if (state == DecoderNativeLaneAdapterState.QUIESCING ||
            state == DecoderNativeLaneAdapterState.JOINED ||
            state == DecoderNativeLaneAdapterState.FAILED
        ) {
            ignoreCallback()
            return
        }
        fail(DecoderNativeLaneAdapterFailure.DECODER_FAILED)
        decoderFailureObserver(failure, diagnostics)
    }

    /** Stops accepting current work before the controller cancels its decoder. */
    @Synchronized
    fun beginCancellation(): Boolean {
        if (state == DecoderNativeLaneAdapterState.JOINED) return false
        val changed = !cancellationBegun
        cancellationBegun = true
        if (state != DecoderNativeLaneAdapterState.FAILED) {
            state = DecoderNativeLaneAdapterState.QUIESCING
        }
        return changed
    }

    /** Called only after MediaCodecTrackDecoder.awaitClosed() confirms the worker has exited. */
    @Synchronized
    fun confirmDecoderJoined() {
        check(cancellationBegun) { "Begin cancellation before confirming decoder join" }
        state = DecoderNativeLaneAdapterState.JOINED
    }

    @Synchronized
    fun diagnostics(): DecoderNativeLaneAdapterDiagnostics =
        DecoderNativeLaneAdapterDiagnostics(
            state = state,
            configuredFormat = stableFormat,
            decoderSeekGeneration = authorizedDecoderGeneration,
            nativeGeneration = mappedNativeGeneration,
            firstAcceptedSourceFrame = firstAcceptedSourceFrame,
            acceptedSourceFrameExclusive = acceptedSourceFrameExclusive,
            acceptedFrames = generationAcceptedFrames,
            offerCalls = offerCalls,
            discardedStaleOffers = staleOffers,
            waitingFutureOffers = futureOffers,
            ignoredCallbacks = ignoredCallbacks,
            discontinuities = discontinuities,
            endOfStreamStatus = eosStatus,
            decoderTerminalCallbackSeen = decoderTerminalCallbackSeen,
            safeToReleaseLane = state == DecoderNativeLaneAdapterState.JOINED,
            failure = failure,
            failureDiagnostic = failureDiagnostic
        )

    private fun ensureConfigured(format: NativePcmFormat): Boolean {
        val existing = stableFormat
        if (existing != null) return existing == format
        if (callbacksAreTerminal()) return false
        return try {
            target = targetFactory.configure(format, mappedNativeGeneration)
            stableFormat = format
            state = DecoderNativeLaneAdapterState.STREAMING
            true
        } catch (error: Throwable) {
            fail(DecoderNativeLaneAdapterFailure.LANE_CONFIGURATION_FAILED, "configure", error)
            false
        }
    }

    private fun callbacksAreTerminal(): Boolean =
        state == DecoderNativeLaneAdapterState.QUIESCING ||
            state == DecoderNativeLaneAdapterState.JOINED ||
            state == DecoderNativeLaneAdapterState.FAILED

    private fun ignoreCallback() {
        ignoredCallbacks = saturatingIncrement(ignoredCallbacks)
    }

    private fun fail(reason: DecoderNativeLaneAdapterFailure, operation: String? = null,
        cause: Throwable? = null) {
        if (failure == null) {
            failure = reason
            failureDiagnostic = cause?.let {
                val diagnostic = NativeDecoderFailure(NativeDecoderErrorCode.SINK_FAILED,
                    decoderId, laneId, "Native lane $operation failed", it)
                "${diagnostic.message}\n${diagnostic.causeDiagnostic.orEmpty()}"
            }
        }
        state = DecoderNativeLaneAdapterState.FAILED
    }

    private fun mapGeneration(decoderSeekGeneration: Long): Long = try {
        Math.addExact(generationBase, decoderSeekGeneration)
    } catch (_: ArithmeticException) {
        throw IllegalStateException("Native lane generation space exhausted")
    }

    private fun isValidResult(
        result: NativePcmSinkResult,
        block: NativePcmBlock
    ): Boolean = result.acceptedBytes <= block.data.remaining() &&
        result.acceptedBytes % block.format.bytesPerFrame == 0 &&
        !(result.acceptedBytes > 0 &&
            (result.waitsForSignal || result.reportsPermanentStall))

    companion object {
        private const val FUTURE_GENERATION_RETRY_US = 500L

        fun forNativeBank(
            decoderId: String,
            laneId: String,
            bank: NativeSongLaneIngressBank,
            laneIndex: Int,
            generationBase: Long
        ): DecoderNativeLaneAdapter = DecoderNativeLaneAdapter(
            decoderId = decoderId,
            laneId = laneId,
            generationBase = generationBase,
            targetFactory = DecoderNativeLaneTargetFactory { format, initialNativeGeneration ->
                val sink = bank.configureLane(
                    laneIndex = laneIndex,
                    sourceRateHz = format.sampleRateHz,
                    initialNativeGeneration = initialNativeGeneration,
                    decoderGenerationBase = generationBase
                )
                object : DecoderNativeLaneTarget {
                    override fun offer(block: NativePcmBlock): NativePcmSinkResult =
                        sink.offer(block)

                    override fun offerAtNativeGeneration(
                        block: NativePcmBlock,
                        nativeGeneration: Long
                    ): NativePcmSinkResult = sink.offerAtNativeGeneration(
                        block,
                        nativeGeneration
                    )

                    override fun markEndOfStream(
                        decoderSeekGeneration: Long,
                        finalSourceFrameExclusive: Long
                    ): NativeLaneEndOfStreamStatus = sink.markEndOfStream(
                        decoderSeekGeneration,
                        finalSourceFrameExclusive
                    )

                    override fun markEndOfStreamAtNativeGeneration(
                        decoderSeekGeneration: Long,
                        nativeGeneration: Long,
                        finalSourceFrameExclusive: Long
                    ): NativeLaneEndOfStreamStatus = sink.markEndOfStreamAtNativeGeneration(
                        nativeGeneration,
                        finalSourceFrameExclusive
                    )
                }
            }
        )
    }
}

private fun saturatingIncrement(value: Long): Long =
    if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

private fun saturatingAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
