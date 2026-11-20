package app.stemwise.playback.engine

private fun nativeTimelineFrameToUs(frame: Long): Long = try {
    Math.multiplyExact(frame, 1_000_000L) / NATIVE_ENGINE_SAMPLE_RATE_HZ
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("Timeline frames exceed microsecond space")
}

private enum class LoopWorkerPhase { PREPARING, RETIRING_WORKER, CANCELLING }
private data class LoopPreparationPlan(
    val intent: NativeRuntimeManagedLoopIntent,
    val sources: List<LoopPreparationLaneSource>,
    var begun: Boolean,
    val purpose: LoopPreparationPurpose,
    var sourceCursor: Int = 0,
    var worker: NativeEngineLoopPreparationDecoder? = null,
    var phase: LoopWorkerPhase = LoopWorkerPhase.PREPARING,
    var evidenceStage: String = "head-cache-admission",
    var retiringOutcome: NativeEngineLoopPreparationOutcome? = null,
    var retirementDeadlineMs: Long? = null,
    var retirementTimeoutReported: Boolean = false
)
private data class LoopIngressPlan(val request: NativeRuntimeLoopIngressRequest, val record: BankRecord)

internal interface LoopPreparationPort {
    fun beginLoopHeadPreparation(intent: NativeRuntimeManagedLoopIntent): NativeRuntimeLoopHeadBeginStatus
    fun abortLoopHeadPreparation(loopRevision: Long): NativeRuntimeLoopHeadAbortStatus
    fun tryOpenLoopHeadPreparationDecoder(spec: NativeEngineLoopPreparationSpec): NativeEngineLoopPreparationDecoder?
    fun releaseLoopHeadPreparationDecoder(decoder: NativeEngineLoopPreparationDecoder)
    fun publishManagedLoopIngressReady(evidence: NativeRuntimeManagedLoopIngressReady): NativeRuntimePublishStatus
    fun notifyIngressAvailable()
}

internal sealed interface LoopPreparationUpdate {
    data object Pending : LoopPreparationUpdate
    data object Reconcile : LoopPreparationUpdate
    data class Ready(val purpose: LoopPreparationPurpose, val intent: NativeRuntimeManagedLoopIntent) : LoopPreparationUpdate
    data class Failed(val purpose: LoopPreparationPurpose, val cause: String) : LoopPreparationUpdate
}

internal sealed interface LoopRefillUpdate {
    data object Pending : LoopRefillUpdate
    data object Rebase : LoopRefillUpdate
    data class Started(val ingressEpoch: Long) : LoopRefillUpdate
    data class Ready(val sequence: Long) : LoopRefillUpdate
    data class Failed(val cause: String) : LoopRefillUpdate
}

/** The command actor calls this owner; only its exact joined worker can release a cache lease. */
internal class LoopPreparationOwner(
    private val environment: LoopPreparationPort,
    private val now: () -> Long,
    private val evidence: NativeControllerPreparationEvidence,
    private val retirementDelayed: (String) -> Unit
) {
    var range: LoopPreparationRequest? = null
        private set
    var repeat: LoopPreparationRequest? = null
        private set
    private var plan: LoopPreparationPlan? = null
    private var refill: LoopIngressPlan? = null
    private var generation = 0L
    private var cacheRevision = 0L

    val preparing: Boolean get() = plan != null
    val preparingOwnership: NativeRuntimeOwnership? get() = plan?.intent?.ownership
    val purpose: LoopPreparationPurpose? get() = plan?.purpose
    val retirementTimeoutReported: Boolean get() = plan?.retirementTimeoutReported == true
    val refillRequest: NativeRuntimeLoopIngressRequest? get() = refill?.request

    fun ownsRefill(record: BankRecord): Boolean = refill?.record === record
    fun clearRefill() { refill = null }

    fun beginRefill(record: BankRecord, request: NativeRuntimeLoopIngressRequest,
        coordinator: NativeEngineWholeBankGenerationCoordinator): LoopRefillUpdate {
        if (request.ingressEpoch < record.ingressEpoch) return LoopRefillUpdate.Rebase
        val required = (0 until NativeSongLaneIngressBank.LANE_COUNT).filter {
            request.requiredLaneMask and (1 shl it) != 0
        }
        if (required.any { record.decoders[it] == null })
            return LoopRefillUpdate.Failed("A required loop lane has no active decoder")
        if (request.ingressEpoch > record.ingressEpoch) {
            val tickets = required.associateWith { lane ->
                requireNotNull(record.decoders[lane]).requestSeek(
                    (nativeTimelineFrameToUs(request.targetTimelineFrame) - 1_000L).coerceAtLeast(0L), request.ingressEpoch)
            }
            val batch = tickets.mapValues { (lane, ticket) -> requireNotNull(record.decoders[lane]) to ticket }
            if (coordinator.commit(batch) != NativeEngineWholeBankAdvanceStatus.COMMITTED)
                return LoopRefillUpdate.Failed("A loop lane rejected the exact future ingress epoch")
            record.ingressEpoch = request.ingressEpoch
            if (required.any { it in 1..app.stemwise.model.StemRoleContract.maximumStemCount })
                record.stemAnchorFrame = request.targetTimelineFrame
            environment.notifyIngressAvailable()
        }
        // Equality retains an already-authorized seek until its exact decoder coverage arrives.
        refill = LoopIngressPlan(request, record)
        return LoopRefillUpdate.Started(request.ingressEpoch)
    }

    fun pollRefill(record: BankRecord, intent: NativeRuntimeManagedLoopIntent): LoopRefillUpdate {
        val pending = refill ?: return LoopRefillUpdate.Pending
        if (pending.request.loopRevision != intent.revision || pending.record !== record) {
            refill = null
            return LoopRefillUpdate.Pending
        }
        val request = pending.request
        val requiredEnd = request.requiredEndTimelineFrameExclusive.takeIf { it > request.targetTimelineFrame }
            ?: minOf(requireNotNull(intent.cacheKey).endTimelineFrameExclusive,
                request.targetTimelineFrame + MINIMUM_BODY_PRIME_FRAMES)
        val required = (0 until NativeSongLaneIngressBank.LANE_COUNT).filter {
            request.requiredLaneMask and (1 shl it) != 0
        }
        val heads = required.associateWith { pending.record.decoders[it]?.head() ?: return LoopRefillUpdate.Pending }
        if (heads.values.any { !it.configured || it.nativeGeneration != request.ingressEpoch }) return LoopRefillUpdate.Pending
        var paddedMask = 0
        var commonFirst: Long? = null
        var commonEnd: Long? = null
        for (lane in required) {
            val head = requireNotNull(heads[lane])
            val eos = head.endOfStreamTimelineFrameExclusive48k
            if (lane != 0 && eos != null && eos < requiredEnd) {
                if (eos > request.targetTimelineFrame) {
                    val first = head.firstTimelineFrame48k ?: return LoopRefillUpdate.Pending
                    val end = head.acceptedTimelineFrameExclusive48k ?: return LoopRefillUpdate.Pending
                    if (first > request.targetTimelineFrame || end != eos)
                        return LoopRefillUpdate.Pending
                }
                paddedMask = paddedMask or (1 shl lane)
                continue
            }
            val first = head.firstTimelineFrame48k ?: return LoopRefillUpdate.Pending
            val end = head.acceptedTimelineFrameExclusive48k ?: return LoopRefillUpdate.Pending
            if (first > request.targetTimelineFrame || end < requiredEnd) return LoopRefillUpdate.Pending
            commonFirst = commonFirst?.let { maxOf(it, first) } ?: first
            commonEnd = commonEnd?.let { minOf(it, end) } ?: end
        }
        val first = commonFirst ?: return LoopRefillUpdate.Pending
        val end = commonEnd ?: return LoopRefillUpdate.Pending
        val evidence = NativeRuntimeManagedLoopIngressReady(request.sequence, request.loopRevision,
            request.targetCycle, request.ownership, request.ingressEpoch, request.requiredLaneMask,
            paddedMask, first, end)
        return when (environment.publishManagedLoopIngressReady(evidence)) {
            NativeRuntimePublishStatus.ACCEPTED -> {
                refill = null
                LoopRefillUpdate.Ready(request.sequence)
            }
            NativeRuntimePublishStatus.STALE_REVISION,
            NativeRuntimePublishStatus.INVALID_SNAPSHOT -> LoopRefillUpdate.Failed("Native loop ingress acknowledgement was rejected")
            NativeRuntimePublishStatus.STOPPED -> LoopRefillUpdate.Failed("Native runtime stopped during loop ingress acknowledgement")
        }
    }
    fun clear(purpose: LoopPreparationPurpose) {
        when (purpose) {
            LoopPreparationPurpose.RANGE -> range = null
            LoopPreparationPurpose.REPEAT -> repeat = null
        }
    }
    fun clearRequests() {
        check(plan == null) { "A preparation worker still owns its exact lease" }
        range = null
        repeat = null
        refill = null
    }
    fun repeatRetryDue(): Boolean = !preparing && (repeat?.result as? LoopPreparationResult.Failed)?.let {
        now() >= it.retryAtMs
    } == true

    private fun request(purpose: LoopPreparationPurpose): LoopPreparationRequest? = when (purpose) {
        LoopPreparationPurpose.RANGE -> range
        LoopPreparationPurpose.REPEAT -> repeat
    }
    private fun setRequest(purpose: LoopPreparationPurpose, request: LoopPreparationRequest) {
        when (purpose) {
            LoopPreparationPurpose.RANGE -> range = request
            LoopPreparationPurpose.REPEAT -> repeat = request
        }
    }

    fun reconcile(purpose: LoopPreparationPurpose, ownership: NativeRuntimeOwnership,
        identity: ManagedLoopStableIdentity, sources: List<LoopPreparationLaneSource>, runtimeStopped: Boolean) {
        val current = request(purpose)
        var request = if (current == null || current.identity != identity) {
            if (!cancel(abortNative = true, runtimeStopped)) return
            LoopPreparationRequest(identity, intent(ownership, identity)).also { setRequest(purpose, it) }
        } else current
        if (request.result == LoopPreparationResult.Ready) return
        val failed = request.result as? LoopPreparationResult.Failed
        if (failed != null) {
            if (purpose == LoopPreparationPurpose.RANGE || request.attempt >= MAXIMUM_REPEAT_ATTEMPTS ||
                now() < failed.retryAtMs) return
            request = LoopPreparationRequest(identity, intent(ownership, identity), request.attempt + 1)
            setRequest(purpose, request)
        }
        if (plan != null) return
        // Another purpose may have consumed a newer revision while this request was pending.
        // Native cache revisions remain monotonic even after that preparation is aborted.
        if (request.intent.revision < cacheRevision) {
            request = request.copy(intent = intent(ownership, identity))
            setRequest(purpose, request)
        }
        val key = requireNotNull(request.intent.cacheKey)
        evidence.begin(NativeControllerPreparationOperation.LOOP_HEAD, request.intent.ownership,
            key.loopRevision, "head-cache-admission", now())
        plan = LoopPreparationPlan(request.intent, sources, begun = false, purpose = purpose)
    }

    private fun intent(ownership: NativeRuntimeOwnership, identity: ManagedLoopStableIdentity): NativeRuntimeManagedLoopIntent {
        check(cacheRevision < Long.MAX_VALUE) { "Loop cache revision space exhausted" }
        cacheRevision += 1L
        val key = NativeRuntimeManagedLoopContentKey(identity.songIdentityHash, identity.originalContentHash,
            identity.stemPackageHash, identity.contentRevision, cacheRevision,
            identity.startTimelineFrame, identity.endTimelineFrameExclusive)
        return NativeRuntimeManagedLoopIntent(cacheRevision, ownership, true, key,
            identity.requiredLaneMask, identity.declickFrames, identity.boundaryMode)
    }

    fun advance(ownership: NativeRuntimeOwnership?, runtimeStopped: Boolean): LoopPreparationUpdate {
        val plan = plan ?: return LoopPreparationUpdate.Pending
        if (plan.phase == LoopWorkerPhase.CANCELLING) {
            return if (cancel(abortNative = true, runtimeStopped)) LoopPreparationUpdate.Reconcile
                else LoopPreparationUpdate.Pending
        }
        val request = request(plan.purpose) ?: run {
            cancel(abortNative = true, runtimeStopped)
            return LoopPreparationUpdate.Pending
        }
        if (request.intent.cacheKey != plan.intent.cacheKey) {
            val worker = plan.worker
            if (worker != null) {
                if (!joinAndRelease(plan, worker)) return LoopPreparationUpdate.Pending
                plan.worker = null
            }
            finish(plan, abortNative = true, runtimeStopped)
            return LoopPreparationUpdate.Reconcile
        }
        if (ownership?.selectionToken != request.intent.ownership.selectionToken) return LoopPreparationUpdate.Pending
        if (!plan.begun) {
            when (environment.beginLoopHeadPreparation(plan.intent)) {
                NativeRuntimeLoopHeadBeginStatus.ACCEPTED -> plan.begun = true
                NativeRuntimeLoopHeadBeginStatus.READERS_ACTIVE -> {
                    recordStage(plan, "waiting-for-head-readers")
                    return LoopPreparationUpdate.Pending
                }
                NativeRuntimeLoopHeadBeginStatus.INSUFFICIENT_SPACE -> return fail(plan,
                    "Not enough free storage for the requested loop's retained PCM; free space and retry", runtimeStopped)
                NativeRuntimeLoopHeadBeginStatus.STOPPED -> return fail(plan,
                    "Native runtime stopped before loop-head preparation", runtimeStopped)
                else -> return fail(plan, "Native loop-head cache rejected preparation", runtimeStopped)
            }
        }
        val worker = plan.worker
        if (worker != null) {
            when (val outcome = plan.retiringOutcome ?: worker.snapshot().outcome) {
                NativeEngineLoopPreparationOutcome.RUNNING -> return LoopPreparationUpdate.Pending
                NativeEngineLoopPreparationOutcome.LANE_COMPLETED,
                NativeEngineLoopPreparationOutcome.CACHE_COMMITTED -> {
                    if (!joinAndRelease(plan, worker)) return LoopPreparationUpdate.Pending
                    plan.worker = null
                    plan.sourceCursor += 1
                    if (outcome == NativeEngineLoopPreparationOutcome.CACHE_COMMITTED) {
                        check(plan.sourceCursor == plan.sources.size) { "Native loop cache committed before every requested lane" }
                        evidence.finish(NativeControllerPreparationOperation.LOOP_HEAD,
                            plan.intent.ownership.selectionToken, requireNotNull(plan.intent.cacheKey).loopRevision,
                            "cache-committed", now())
                        setRequest(plan.purpose, request.copy(result = LoopPreparationResult.Ready))
                        finish(plan, abortNative = false, runtimeStopped)
                        return LoopPreparationUpdate.Ready(plan.purpose, plan.intent)
                    }
                }
                NativeEngineLoopPreparationOutcome.FAILED,
                NativeEngineLoopPreparationOutcome.CANCELLED -> {
                    val cause = worker.snapshot().cause ?: "Loop-head decoder ended before its lane completed"
                    if (!joinAndRelease(plan, worker)) return LoopPreparationUpdate.Pending
                    plan.worker = null
                    return fail(plan, cause, runtimeStopped)
                }
            }
        }
        if (plan.sourceCursor >= plan.sources.size) return fail(plan,
            "Loop-head preparation ended without a cache commit", runtimeStopped)
        val source = plan.sources[plan.sourceCursor]
        check(generation < Long.MAX_VALUE) { "Loop preparation generation space exhausted" }
        generation += 1L
        val key = requireNotNull(plan.intent.cacheKey)
        val opened = environment.tryOpenLoopHeadPreparationDecoder(NativeEngineLoopPreparationSpec(
            decoderId = "loop-${key.loopRevision}-${source.laneIndex}-$generation",
            laneId = "loop-${source.laneId}", laneIndex = source.laneIndex, location = source.location,
            // Retain real decoder/SRC preroll while the cache still begins at exact authored A.
            initialPositionUs = (nativeTimelineFrameToUs(key.startTimelineFrame) - 1_000L).coerceAtLeast(0L),
            loopRevision = key.loopRevision, preparationGeneration = generation,
            directFloat32Audio = source.laneId == "remaining", sourceAdmission = source.sourceAdmission)) ?: run {
                recordStage(plan, "waiting-for-decoder-lease")
                return LoopPreparationUpdate.Pending
            }
        recordStage(plan, "decoder-coverage")
        plan.worker = opened
        plan.phase = LoopWorkerPhase.PREPARING
        if (!opened.start()) {
            plan.retiringOutcome = NativeEngineLoopPreparationOutcome.FAILED
            if (!joinAndRelease(plan, opened)) return LoopPreparationUpdate.Pending
            plan.worker = null
            return fail(plan, "Loop-head MediaCodec worker did not start", runtimeStopped)
        }
        return LoopPreparationUpdate.Pending
    }

    private fun recordStage(plan: LoopPreparationPlan, stage: String) {
        if (plan.evidenceStage == stage) return
        val revision = requireNotNull(plan.intent.cacheKey).loopRevision
        val timestamp = now()
        evidence.finish(NativeControllerPreparationOperation.LOOP_HEAD, plan.intent.ownership.selectionToken,
            revision, plan.evidenceStage, timestamp)
        evidence.begin(NativeControllerPreparationOperation.LOOP_HEAD, plan.intent.ownership, revision, stage, timestamp)
        plan.evidenceStage = stage
    }

    private fun fail(plan: LoopPreparationPlan, cause: String, runtimeStopped: Boolean): LoopPreparationUpdate {
        finish(plan, abortNative = true, runtimeStopped)
        evidence.finish(NativeControllerPreparationOperation.LOOP_HEAD, plan.intent.ownership.selectionToken,
            requireNotNull(plan.intent.cacheKey).loopRevision, "failed", now(), cause)
        request(plan.purpose)?.takeIf { it.intent.cacheKey == plan.intent.cacheKey }?.let { request ->
            val retryAt = if (plan.purpose == LoopPreparationPurpose.RANGE) Long.MAX_VALUE else try {
                Math.addExact(now(), REPEAT_RETRY_DELAY_MS * request.attempt)
            } catch (_: ArithmeticException) { Long.MAX_VALUE }
            setRequest(plan.purpose, request.copy(result = LoopPreparationResult.Failed(cause, retryAt)))
        }
        return LoopPreparationUpdate.Failed(plan.purpose, cause)
    }

    fun cancel(abortNative: Boolean, runtimeStopped: Boolean): Boolean {
        val plan = plan ?: return true
        evidence.finish(NativeControllerPreparationOperation.LOOP_HEAD, plan.intent.ownership.selectionToken,
            requireNotNull(plan.intent.cacheKey).loopRevision, "cancel-requested", now(),
            "Loop preparation cancellation requested")
        plan.phase = LoopWorkerPhase.CANCELLING
        plan.worker?.let { worker ->
            if (!joinAndRelease(plan, worker)) return false
            plan.worker = null
        }
        finish(plan, abortNative, runtimeStopped)
        if (plan.begun) request(plan.purpose)?.takeIf {
            it.result == LoopPreparationResult.Preparing && it.intent.cacheKey == plan.intent.cacheKey
        }?.let {
            // An accepted revision cannot be begun again, including when it was the newest one.
            setRequest(plan.purpose, it.copy(intent = intent(plan.intent.ownership, it.identity)))
        }
        return true
    }

    private fun finish(plan: LoopPreparationPlan, abortNative: Boolean, runtimeStopped: Boolean) {
        check(this.plan === plan && plan.worker == null) { "Preparation cleanup requires its exact retired worker" }
        if (abortNative && plan.begun && !runtimeStopped)
            environment.abortLoopHeadPreparation(requireNotNull(plan.intent.cacheKey).loopRevision)
        this.plan = null
    }

    private fun joinAndRelease(plan: LoopPreparationPlan, worker: NativeEngineLoopPreparationDecoder): Boolean {
        if (plan.retirementDeadlineMs == null) {
            if (plan.phase != LoopWorkerPhase.CANCELLING) plan.phase = LoopWorkerPhase.RETIRING_WORKER
            plan.retirementDeadlineMs = now() + NATIVE_DECODER_JOIN_WATCHDOG_MS
            if (plan.retiringOutcome == null) plan.retiringOutcome = worker.snapshot().outcome
            worker.requestCancel()
        }
        if (worker.pollJoin()) {
            check(worker.safeToRelease())
            environment.releaseLoopHeadPreparationDecoder(worker)
            plan.retirementDeadlineMs = null
            plan.retiringOutcome = null
            plan.retirementTimeoutReported = false
            if (plan.phase != LoopWorkerPhase.CANCELLING) plan.phase = LoopWorkerPhase.PREPARING
            return true
        }
        if (!plan.retirementTimeoutReported && now() >= requireNotNull(plan.retirementDeadlineMs)) {
            plan.retirementTimeoutReported = true
            retirementDelayed("A loop-head decoder is still closing; playback and the last valid loop were retained")
        }
        return false
    }

    fun tearDownForFatal(runtimeStopped: Boolean): String? {
        val plan = plan ?: return null
        val worker = plan.worker
        if (worker != null) {
            when (worker.cancelAndJoin(NATIVE_DECODER_JOIN_WATCHDOG_MS)) {
                NativeEngineDecoderJoinStatus.JOINED -> {
                    if (!worker.safeToRelease()) return "joined loop-preparation worker did not become release-safe"
                    val released = runCatching { environment.releaseLoopHeadPreparationDecoder(worker) }.exceptionOrNull()
                    if (released != null) return "loop-preparation lease release failed: " +
                        (released.message ?: released::class.java.simpleName)
                    plan.worker = null
                }
                NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED -> return "loop-preparation worker exceeded the teardown watchdog; " +
                    "its dedicated decoder lease remains quarantined"
            }
        }
        return runCatching { finish(plan, abortNative = true, runtimeStopped) }.exceptionOrNull()?.let {
            "loop-preparation cache abort failed: " + (it.message ?: it::class.java.simpleName)
        }
    }

    private companion object {
        const val MAXIMUM_REPEAT_ATTEMPTS = 3
        const val REPEAT_RETRY_DELAY_MS = 500L
        const val MINIMUM_BODY_PRIME_FRAMES = 4_800L
    }
}
