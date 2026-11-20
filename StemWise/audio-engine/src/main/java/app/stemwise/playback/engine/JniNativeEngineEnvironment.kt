package app.stemwise.playback.engine

import android.content.Context
import app.stemwise.model.StemRoleContract
import app.stemwise.playback.metronomeClickAmplitude
import app.stemwise.playback.persistedStemPackageIdentity
import app.stemwise.playback.playbackStableDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

private data class NativeEngineResources(
    val output: NativeAudioOutput,
    val banks: List<NativeSongLaneIngressBank>,
    val loopPreparationWorkerPool: NativeDecoderWorkerPool,
    val runtime: NativePlaybackRuntime
)

internal fun <T> constructWithNativeRollback(
    construct: (register: (AutoCloseable) -> Unit) -> T
): T {
    val acquired = ArrayList<AutoCloseable>(5)
    try {
        return construct { acquired.add(it) }
    } catch (failure: Throwable) {
        acquired.asReversed().forEach { resource ->
            runCatching { resource.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}

private fun acquireNativeEngineResources(
    outputCapacityFrames: Int,
    configuration: NativeRuntimeConfiguration,
    outputSinkFactory: (() -> NativeAudioTrackFeeder.Sink)?
): NativeEngineResources = constructWithNativeRollback { register ->
        val output = NativeAudioOutput(outputCapacityFrames, 1L,
            deferPhysicalOpen = true, sinkFactory = outputSinkFactory)
            .also(register)
        val first = NativeSongLaneIngressBank().also(register)
        val second = NativeSongLaneIngressBank().also(register)
        val pool = NativeDecoderWorkerPool.dedicated("LoopPrep", workerCount = 1, queueCapacity = 1)
            .also(register)
        val runtime = NativePlaybackRuntime(output, first, second, configuration).also(register)
        NativeEngineResources(output, listOf(first, second), pool, runtime)
}

/** JNI/MediaCodec implementation. Tests replace the entire environment with deterministic fakes. */
internal class JniNativeEngineEnvironment(
    context: Context,
    outputCapacityFrames: Int = NativeAudioBufferFormat.DEFAULT_CAPACITY_FRAMES,
    runtimeConfiguration: NativeRuntimeConfiguration = NativeRuntimeConfiguration(),
    outputSinkFactory: (() -> NativeAudioTrackFeeder.Sink)? = null,
    private val packetReaderFactory: AudioPacketReaderFactory
) : NativeEngineEnvironment {
    private val applicationContext = context.applicationContext
    private val configuration = runtimeConfiguration.copy(
        currentSongCacheDirectory = applicationContext.cacheDir.absolutePath
    )
    private val resources = acquireNativeEngineResources(outputCapacityFrames, configuration, outputSinkFactory)
    private val output = resources.output
    override fun setDetailedDiagnosticCapture(enabled: Boolean) = output.setDetailedDiagnosticCapture(enabled)
    private val outputBoundaryGate = Any()
    private val banks = resources.banks
    private val leases = NativeDecoderLeaseCoordinator()
    private val loopPreparationWorkerPool = resources.loopPreparationWorkerPool
    @Volatile private var runtime = resources.runtime
    private var diagnosticPayloadFingerprints = false
    private var runtimeStopped = true
    private var closed = false
    @Volatile private var audibleControlProgressListener:
        ((NativeRuntimeAudibleControlEvidence) -> Unit)? = null
    private var audibleControlProgressWatcher: Thread? = null
    @Volatile private var pcmCacheProgressListener: (() -> Unit)? = null
    private var pcmCacheProgressWatcher: Thread? = null

    override val outputGeneration: Long
        @Synchronized get() = output.currentGeneration()

    @Synchronized
    override fun prepareSourceTransition(outgoing: NativeRuntimeOwnership, target: NativeRuntimeOwnership,
        pausedFallbackFrame: Long?): Long? = output.prepareSourceTransition(outgoing, target, pausedFallbackFrame)

    @Synchronized
    override fun cancelSourceTransition(target: NativeRuntimeOwnership) = output.cancelSourceTransition(target)

    override val requiredNativeStemRenderAheadFrames: Long = maxOf(
        NATIVE_ENGINE_MINIMUM_STEM_RENDER_AHEAD_FRAMES,
        (
            runtimeConfiguration.minimumOutputReserveMs.toLong() *
                NATIVE_ENGINE_SAMPLE_RATE_HZ + 999L
            ) / 1_000L
    )

    override suspend fun readPcmWindow(owner: LongArray, lane: Int, first: Long, frames: Int): SourcePcmWindowRead =
        runtime.readPcmWindow(owner, lane, first, frames)

    @Synchronized
    override fun startRuntime(): Boolean {
        check(!closed)
        val started = runtime.start()
        if (started) {
            runtimeStopped = false
            startAudibleControlProgressWatcher(runtime)
        }
        return started
    }

    override fun prepareOutput(): NativeOutputPreparationState = output.prepare()

    @Synchronized
    override fun publish(snapshot: NativeRuntimeCommandSnapshot): NativeRuntimePublishStatus =
        runtime.publish(snapshot)

    @Synchronized
    override fun publishStemGainPreview(
        publicationSequence: Long,
        coversOfferSequence: Long,
        ownership: NativeRuntimeOwnership,
        sourceIntentRevision: Long,
        stemIntentRevision: Long,
        canonicalControlFloorRevision: Long,
        activeMask: Int,
        gains: FloatArray
    ): NativeRuntimePublishStatus = runtime.publishStemGainPreview(
        publicationSequence,
        coversOfferSequence,
        ownership,
        sourceIntentRevision,
        stemIntentRevision,
        canonicalControlFloorRevision,
        activeMask,
        gains
    )

    @Synchronized
    override fun publishBeatGrid(
        update: NativeRuntimeBeatGridUpdate
    ): NativeRuntimePublishStatus = runtime.publishBeatGrid(update)

    @Synchronized
    override fun publishManagedLoopIntent(
        intent: NativeRuntimeManagedLoopIntent
    ): NativeRuntimePublishStatus = runtime.publishManagedLoopIntent(intent)

    override fun publishDirectLoopPolicy(
        revision: Long,
        selectionToken: Long,
        generation: Long,
        enabled: Boolean,
        aTimelineFrame: Long,
        bTimelineFrameExclusive: Long,
        capturedBTimelineFrameExclusive: Long,
        inputEventAtMonotonicMicros: Long,
        heldBGestureId: Long,
        boundaryArmedAtPublication: Boolean,
        boundaryCrossedAtPublication: Boolean,
        captureReferenceTimelineFrame: Long
    ): NativeRuntimePublishStatus = runtime.publishDirectLoopPolicy(
        revision,
        selectionToken,
        generation,
        enabled,
        aTimelineFrame,
        bTimelineFrameExclusive,
        capturedBTimelineFrameExclusive,
        inputEventAtMonotonicMicros,
        heldBGestureId,
        boundaryArmedAtPublication,
        boundaryCrossedAtPublication,
        captureReferenceTimelineFrame
    )

    @Synchronized
    override fun publishManagedLoopIngressReady(
        evidence: NativeRuntimeManagedLoopIngressReady
    ): NativeRuntimePublishStatus = runtime.publishManagedLoopIngressReady(evidence)

    @Synchronized
    override fun beginLoopHeadPreparation(
        intent: NativeRuntimeManagedLoopIntent
    ): NativeRuntimeLoopHeadBeginStatus = runtime.beginLoopHeadPreparation(intent)

    @Synchronized
    override fun abortLoopHeadPreparation(
        loopRevision: Long
    ): NativeRuntimeLoopHeadAbortStatus = runtime.abortLoopHeadPreparation(loopRevision)

    @Synchronized
    override fun isCurrentSongCacheRangeReady(intent: NativeRuntimeBankIntent, requiredLaneMask: Int,
        first: Long, end: Long): Boolean = runtime.pcmCacheRangeReady(intent, requiredLaneMask, first, end)

    @Synchronized
    override fun stemGainCallbackSettlementDiagnostics(): StemGainCallbackSettlementDiagnostics? =
        if (closed) null else runtime.stemGainCallbackSettlementDiagnostics()

    @Synchronized
    override fun loopProtocolSnapshot(): NativeRuntimeLoopProtocolSnapshot =
        runtime.loopProtocolSnapshot()

    @Synchronized
    override fun setDiagnosticPayloadFingerprints(enabled: Boolean) {
        diagnosticPayloadFingerprints = enabled
        runtime.setDiagnosticPayloadFingerprints(enabled)
    }

    @Synchronized
    override fun controlProgress(): NativeRuntimeControlObservation {
        val progress = runtime.controlProgress()
        // The rare drain boundary still needs the existing composite output/runtime fence.
        return if (progress.automaticSuccessor.phase == NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED)
            diagnostics() else progress
    }

    @Synchronized
    override fun diagnostics(): NativeRuntimeDiagnostics {
        val observedRuntime = runtime.diagnostics()
        // STAGING may persist for most of a song. Native publishes DRAIN_COMMITTED before exposing
        // its output commit, so this runtime-only read is a valid pre-boundary linearization point
        // and must not pay for a full output diagnostics query every 16 ms. The composite snapshot
        // below still fences STAGING because its later output read may cross that boundary.
        if (observedRuntime.automaticSuccessor.phase !=
            NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED
        ) return observedRuntime
        return awaitAutomaticSuccessorRuntimeCatchUp(
            observedRuntime,
            output.diagnostics()
        )
    }

    @Synchronized
    override fun renderReadinessDiagnostics(): NativeRuntimeRenderReadinessDiagnostics? =
        runtime.renderReadinessDiagnostics()

    @Synchronized
    override fun isCurrentSongCacheAllLanesSealed(ownership: NativeRuntimeOwnership): Boolean =
        runtime.controlProgress().readinessProgress?.let { it.ownership == ownership && it.cacheReady } == true

    @Synchronized
    override fun isFixedLoopHeadPrepared(ownership: NativeRuntimeOwnership): Boolean =
        runtime.controlProgress().readinessProgress?.let {
            it.ownership == ownership && it.directAtomicPolicyGeneration == ownership.generation && it.fixedLoopHeadPrepared
        } == true

    @Synchronized
    override fun isCurrentSongCacheLaneMaskSealed(ownership: NativeRuntimeOwnership, requiredLaneMask: Int): Boolean =
        runtime.controlProgress().readinessProgress?.let {
            requiredLaneMask != 0 && requiredLaneMask and NativeRuntimeManagedLoopIntent.ALL_LANES_MASK == requiredLaneMask &&
                it.ownership == ownership && it.cacheBound && it.completeLaneMask and requiredLaneMask == requiredLaneMask
        } == true

    @Synchronized
    override fun outputDiagnostics(): NativeAudioDiagnostics? = if (closed) null else output.diagnostics()

    @Synchronized
    override fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot {
        // Runtime first and output second is not, by itself, a linearizable pair: the output consumer
        // can present an automatic successor between those JNI reads. Pin the output observation,
        // then allow the higher-priority render worker to publish that irrevocable callback evidence
        // before returning the pair. This is a control-thread fence only; it never runs in audio.
        val observedRuntime = runtime.diagnostics()
        val observedOutput = output.diagnostics()
        val coherentRuntime = awaitAutomaticSuccessorRuntimeCatchUp(
            observedRuntime,
            observedOutput
        )
        return NativeEngineDiagnosticsSnapshot(
            runtime = coherentRuntime,
            output = observedOutput,
            decoderLeases = leases.snapshot(),
            dedicatedLoopPreparationWorkers = loopPreparationWorkerPool.diagnostics(),
            nativeRenderReadiness = runtime.renderReadinessDiagnostics(),
            stemGainCallbackSettlement = runtime.stemGainCallbackSettlementDiagnostics()
        )
    }

    private fun awaitAutomaticSuccessorRuntimeCatchUp(
        initialRuntime: NativeRuntimeDiagnostics,
        pinnedOutput: NativeAudioDiagnostics
    ): NativeRuntimeDiagnostics {
        var observedRuntime = initialRuntime
        if (!automaticBoundaryNeedsCatchUp(observedRuntime, pinnedOutput)) {
            return observedRuntime
        }

        // Presentation is irrevocable, so the render worker is the only legal next writer. Briefly
        // yield this lower-priority control thread while native completes its bounded idle polling
        // cycle. A real stalled worker remains visible after the fence and still fails qualification;
        // no evidence is synthesized and no test tolerance is introduced.
        val maximumFenceNanos = (
            configuration.idleSleepMicros.toLong() *
                AUTOMATIC_BOUNDARY_IDLE_CYCLES * TimeUnit.MICROSECONDS.toNanos(1L)
            ).coerceIn(
                AUTOMATIC_BOUNDARY_MINIMUM_FENCE_NANOS,
                AUTOMATIC_BOUNDARY_MAXIMUM_FENCE_NANOS
            )
        val deadline = System.nanoTime() + maximumFenceNanos
        do {
            LockSupport.parkNanos(AUTOMATIC_BOUNDARY_RECHECK_NANOS)
            observedRuntime = runtime.diagnostics()
        } while (
            automaticBoundaryNeedsCatchUp(observedRuntime, pinnedOutput) &&
            System.nanoTime() < deadline
        )
        return observedRuntime
    }

    private fun automaticBoundaryNeedsCatchUp(
        runtime: NativeRuntimeDiagnostics,
        output: NativeAudioDiagnostics
    ): Boolean = automaticSuccessorBoundaryNeedsRuntimeCatchUp(
        runtimeStatus = runtime.status,
        automatic = runtime.automaticSuccessor,
        outputAppliedGeneration = output.appliedGeneration,
        outputPresentedGeneration = output.lastPresentedGeneration,
        outputPresentedSelectionToken = output.lastPresentedSelectionToken,
        outputPresentedFingerprintSequence = output.lastPresentedFingerprintSequence
    )

    @Synchronized
    override fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot = output.presentationJournal(afterSequence)

    @Synchronized
    override fun hasAppliedUnassignment(
        bank: NativeRuntimeBank,
        exactRevision: Long
    ): Boolean = runtime.hasAppliedUnassignment(bank, exactRevision)

    @Synchronized
    override fun notifyIngressAvailable() = runtime.notifyIngressAvailable()

    @Synchronized
    override fun setPcmCacheProgressListener(listener: (() -> Unit)?) {
        pcmCacheProgressListener = listener
    }

    @Synchronized
    override fun setAudibleControlProgressListener(
        listener: ((NativeRuntimeAudibleControlEvidence) -> Unit)?
    ) {
        audibleControlProgressListener = listener
    }

    @Synchronized
    override fun openDecoder(
        bank: NativeRuntimeBank,
        bankRole: NativeSongBankRole,
        laneIndex: Int,
        spec: NativeEngineDecoderSpec
    ): NativeEngineDecoder {
        check(!closed)
        require(bankRole != NativeSongBankRole.LOOP_PREPARATION)
        val lease = requireNotNull(leases.tryAcquire(bankRole, laneIndex, spec.decoderId)) {
            "The admitted decoder lease budget is exhausted"
        }
        return try {
            JniNativeEngineDecoder(
                context = applicationContext,
                bank = banks[bank.nativeCode],
                laneIndex = laneIndex,
                initialLease = lease,
                leaseCoordinator = leases,
                spec = spec,
                onIngress = ::notifyIngressAvailable,
                packetReaderFactory = packetReaderFactory
            )
        } catch (failure: Throwable) {
            check(leases.release(lease)) { "Failed decoder construction leaked its lease" }
            throw failure
        }
    }

    @Synchronized
    override fun releaseDecoder(bank: NativeRuntimeBank, decoder: NativeEngineDecoder) {
        check(decoder.safeToReleaseLane()) {
            "A decoder lane may be released only after exact worker join"
        }
        // A decoder cancelled during MediaExtractor/codec open may never have configured a sink.
        // releaseLane(false) is then the correct no-native-storage result, not release success by
        // timeout or inference; the exact worker join above still gates lease release.
        banks[bank.nativeCode].releaseLane(decoder.laneIndex)
        decoder.close()
    }

    @Synchronized
    override fun tryOpenLoopHeadPreparationDecoder(
        spec: NativeEngineLoopPreparationSpec
    ): NativeEngineLoopPreparationDecoder? {
        check(!closed)
        val lease = leases.tryAcquire(
            NativeSongBankRole.LOOP_PREPARATION,
            spec.laneIndex,
            spec.decoderId
        ) ?: return null
        return try {
            JniNativeLoopHeadPreparationDecoder(
                context = applicationContext,
                runtime = runtime,
                lease = lease,
                leaseCoordinator = leases,
                spec = spec,
                workerPool = loopPreparationWorkerPool,
                packetReaderFactory = packetReaderFactory
            )
        } catch (failure: Throwable) {
            check(leases.release(lease)) { "Failed loop-head decoder construction leaked its lease" }
            throw failure
        }
    }

    @Synchronized
    override fun releaseLoopHeadPreparationDecoder(
        decoder: NativeEngineLoopPreparationDecoder
    ) {
        check(decoder.safeToRelease()) {
            "A loop-head decoder may be released only after exact worker join"
        }
        decoder.close()
    }

    @Synchronized
    override fun decoderLeaseSnapshot(): NativeDecoderLeaseSnapshot = leases.snapshot()

    override fun armManualSelectionGate(): Long {
        return synchronized(outputBoundaryGate) {
            output.armManualSelectionGateAndGetGeneration()
        }
    }

    @Synchronized
    override fun authorizeManualSelectionGate(generation: Long): Boolean =
        synchronized(outputBoundaryGate) {
            output.authorizeManualSelectionGate(generation)
        }

    @Synchronized
    override fun engageImmediateSilenceGate(): Long {
        // The render runtime still owns the producer ring here. Pausing is enough for synchronous
        // silence; STOP hard-flushes only after joining that sole producer, while route recovery
        // force-resets the rings during its joined reopen.
        return synchronized(outputBoundaryGate) {
            output.pause()
            output.setSessionSuspended(true)
            output.currentGeneration()
        }
    }

    @Synchronized
    override fun stopRuntimeAndJoin() {
        if (runtimeStopped) return
        runtime.requestStop()
        joinAudibleControlProgressWatcher()
        runtime.join()
        runtimeStopped = true
    }

    @Synchronized
    override fun needsOutputRecovery(): Boolean = output.needsRecovery()

    override val outputWarmPauseGraceMs: Long get() = 1_500L

    @Synchronized
    override fun setOutputSessionSuspended(suspended: Boolean) = synchronized(outputBoundaryGate) {
        output.setSessionSuspended(suspended)
    }

    @Synchronized
    override fun quiesceOutputIfPaused(): Boolean = synchronized(outputBoundaryGate) {
        output.quiesceIfPaused().also { settled ->
            if (settled && !runtimeStopped) runtime.notifyIngressAvailable()
        }
    }

    @Synchronized
    override fun reopenOutputForRouteChange(): Boolean {
        check(runtimeStopped) { "Stop and join the runtime before reopening the output" }
        return synchronized(outputBoundaryGate) {
            output.reopenForRouteChange()
        }
    }

    @Synchronized
    override fun recreateRuntime(): Boolean {
        check(runtimeStopped) { "Only a joined runtime may be replaced" }
        val previous = runtime
        val replacement = newRuntime()
        val inherited = try {
            replacement.inheritCompletedSongCache(previous)
        } catch (failure: Throwable) {
            runCatching { replacement.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        if (!inherited) {
            replacement.close()
            return false
        }
        runtime = replacement
        previous.close()
        val started = runtime.start()
        // A failed start remains a joined/stopped runtime and is safe to replace on a later route
        // retry. Marking it live here made a transient device-open failure unretryable.
        runtimeStopped = !started
        if (started) startAudibleControlProgressWatcher(runtime)
        return started
    }

    @Synchronized
    override fun retireOutputForStop(newGeneration: Long): Long? {
        check(runtimeStopped) { "Stop and join the runtime before retiring output" }
        return synchronized(outputBoundaryGate) { output.retireForStop(newGeneration) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stopRuntimeAndJoin()
        audibleControlProgressListener = null
        pcmCacheProgressListener = null
        runtime.close()
        check(leases.snapshot().totalDecoderCount == 0) {
            "All decoder workers must be joined and released before environment close"
        }
        banks.forEach(NativeSongLaneIngressBank::close)
        synchronized(outputBoundaryGate) {
            output.close()
        }
        loopPreparationWorkerPool.close()
        leases.clear()
        closed = true
    }

    private fun newRuntime() = NativePlaybackRuntime(
        output = output,
        firstBank = banks[0],
        secondBank = banks[1],
        configuration = configuration
    ).also { it.setDiagnosticPayloadFingerprints(diagnosticPayloadFingerprints) }

    private fun startAudibleControlProgressWatcher(watchedRuntime: NativePlaybackRuntime) {
        check(audibleControlProgressWatcher == null) {
            "An audible-control progress watcher is already active"
        }
        val watcher = Thread({
            var observedSequence = 0L
            while (true) {
                val nextSequence = watchedRuntime.awaitAudibleControlProgress(observedSequence)
                if (nextSequence <= 0L) break
                if (nextSequence > observedSequence) {
                    val evidence = watchedRuntime.pollAudibleControl(observedSequence)
                    observedSequence = evidence?.sequence ?: nextSequence
                    if (evidence != null) audibleControlProgressListener?.invoke(evidence)
                }
            }
        }, "StemWise-ControlEvidence").apply { isDaemon = true }
        audibleControlProgressWatcher = watcher
        watcher.start()
        check(pcmCacheProgressWatcher == null)
        pcmCacheProgressWatcher = Thread({
            var sequence = 0L
            while (true) {
                val next = watchedRuntime.awaitPcmCacheProgress(sequence)
                if (next <= 0L) break
                if (next > sequence) { sequence = next; pcmCacheProgressListener?.invoke() }
            }
        }, "StemWise-PcmProgress").apply { isDaemon = true; start() }

    }

    private fun joinAudibleControlProgressWatcher() {
        val watcher = audibleControlProgressWatcher ?: return
        check(Thread.currentThread() !== watcher) {
            "The audible-control progress watcher cannot join itself"
        }
        var interrupted = false
        while (watcher.isAlive) {
            try {
                watcher.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        audibleControlProgressWatcher = null
        pcmCacheProgressWatcher?.let { cacheWatcher ->
            check(Thread.currentThread() !== cacheWatcher)
            while (cacheWatcher.isAlive) {
                try { cacheWatcher.join() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        pcmCacheProgressWatcher = null
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val AUTOMATIC_BOUNDARY_IDLE_CYCLES = 8L
        const val AUTOMATIC_BOUNDARY_RECHECK_NANOS = 50_000L
        const val AUTOMATIC_BOUNDARY_MINIMUM_FENCE_NANOS = 2_000_000L
        const val AUTOMATIC_BOUNDARY_MAXIMUM_FENCE_NANOS = 20_000_000L
    }
}

private class JniNativeEngineDecoder(
    context: Context,
    private val bank: NativeSongLaneIngressBank,
    override val laneIndex: Int,
    initialLease: NativeDecoderLease,
    private val leaseCoordinator: NativeDecoderLeaseCoordinator,
    private val spec: NativeEngineDecoderSpec,
    private val onIngress: () -> Unit,
    packetReaderFactory: AudioPacketReaderFactory
) : NativeEngineDecoder {
    override val decoderId: String = spec.decoderId
    private val sink = AtomicReference<NativeLaneIngressSink?>(null)
    private val terminalFailure = AtomicReference<NativeEngineDecoderFailureSignal?>(null)
    private val terminalDecoderCause = AtomicReference<String?>(null)
    private val adapter: DecoderNativeLaneAdapter
    private val decoder: MediaCodecTrackDecoder
    private var lease: NativeDecoderLease? = initialLease
    private var cancellationRequested = false
    @Volatile private var joined = false
    private var retirement: NativePreparationRetirement? = null
    private var closed = false

    init {
        adapter = DecoderNativeLaneAdapter(
            decoderId = spec.decoderId,
            laneId = spec.laneId,
            generationBase = spec.generationBase,
            targetFactory = DecoderNativeLaneTargetFactory { format, initialGeneration ->
                val configured = bank.configureLane(
                    laneIndex = laneIndex,
                    sourceRateHz = format.sampleRateHz,
                    initialNativeGeneration = initialGeneration,
                    decoderGenerationBase = spec.generationBase
                )
                sink.set(configured)
                object : DecoderNativeLaneTarget {
                    override fun offer(block: NativePcmBlock): NativePcmSinkResult =
                        configured.offer(block).also { result ->
                            if (result.acceptedBytes > 0) onIngress()
                        }

                    override fun offerAtNativeGeneration(
                        block: NativePcmBlock,
                        nativeGeneration: Long
                    ): NativePcmSinkResult = configured.offerAtNativeGeneration(
                        block,
                        nativeGeneration
                    ).also { result ->
                        if (result.acceptedBytes > 0) onIngress()
                    }

                    override fun markEndOfStream(
                        decoderSeekGeneration: Long,
                        finalSourceFrameExclusive: Long
                    ): NativeLaneEndOfStreamStatus = configured.markEndOfStream(
                        decoderSeekGeneration,
                        finalSourceFrameExclusive
                    )

                    override fun markEndOfStreamAtNativeGeneration(
                        decoderSeekGeneration: Long,
                        nativeGeneration: Long,
                        finalSourceFrameExclusive: Long
                    ): NativeLaneEndOfStreamStatus = configured
                        .markEndOfStreamAtNativeGeneration(
                            nativeGeneration,
                            finalSourceFrameExclusive
                        )
                }
            },
            decoderFailureObserver = { failure, diagnostics ->
                terminalDecoderCause.compareAndSet(
                    null,
                    "${failure.code}: ${failure.message ?: "decoder failed"}; " +
                        "decoder=${spec.decoderId}; lane=${spec.laneId}; " +
                        "token=${spec.selectionToken.value}; nativeGeneration=${spec.generationBase}; " +
                        "seekGeneration=${diagnostics.activeSeekGeneration}; " +
                        failure.causeDiagnostic.orEmpty()
                )
            }
        )
        decoder = MediaCodecTrackDecoder(
            context = context,
            decoderId = spec.decoderId,
            laneId = spec.laneId,
            source = NativeMediaSource.fromLocation(spec.location, spec.directFloat32Audio)
                .withAdmission(spec.sourceAdmission),
            sink = adapter,
            listener = adapter,
            config = MediaCodecTrackDecoderConfig(initialPositionUs = spec.initialPositionUs,
                retainResamplerHistory = true),
            packetReaderFactory = packetReaderFactory
        )
    }

    override fun start(): Boolean = decoder.start()

    override fun setSinkRetryThrottled(throttled: Boolean) =
        decoder.setSinkRetryThrottled(throttled)

    override fun readableSourceFrames(): Int = bank.readableSourceFrames(laneIndex)

    override fun failure(): NativeEngineDecoderFailureSignal? {
        terminalFailure.get()?.let { return it }
        val diagnostics = adapter.diagnostics()
        val reason = diagnostics.failure ?: return null
        return NativeEngineDecoderFailureSignal(
            decoderId = decoderId,
            token = spec.selectionToken,
            generation = diagnostics.nativeGeneration,
            laneIndex = laneIndex,
            cause = diagnostics.failureDiagnostic ?: terminalDecoderCause.get()
                ?: "decoder adapter failed: $reason"
        ).also { terminalFailure.compareAndSet(null, it) }
    }

    override fun head(): NativeEngineDecoderHead = adapter.diagnostics().toNativeDecoderHead()

    override fun pipelineDiagnostics(): NativeEngineDecoderPipelineDiagnostics =
        NativeEngineDecoderPipelineDiagnostics(
            decoderId = decoderId,
            laneIndex = laneIndex,
            head = head(),
            decoder = decoder.diagnostics(),
            adapter = adapter.diagnostics(),
            nativeReadableSourceFrames = runCatching(::readableSourceFrames).getOrDefault(-1),
            nativeBankIdentity = bank.nativeIdentityForDiagnostics()
        )

    override fun requestSeek(
        positionUs: Long,
        targetNativeGeneration: Long
    ): NativeEngineDecoderSeekTicket {
        require(targetNativeGeneration > 0L)
        val local = decoder.seekToUs(positionUs)
        return NativeEngineDecoderSeekTicket(local, targetNativeGeneration)
    }

    override fun canAuthorizeSeek(ticket: NativeEngineDecoderSeekTicket): Boolean {
        val diagnostics = adapter.diagnostics()
        return diagnostics.state !in setOf(
            DecoderNativeLaneAdapterState.QUIESCING,
            DecoderNativeLaneAdapterState.JOINED,
            DecoderNativeLaneAdapterState.FAILED
        ) && ticket.decoderGeneration >= diagnostics.decoderSeekGeneration &&
            ticket.nativeGeneration > diagnostics.nativeGeneration
    }

    override fun advanceNativeGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean =
        sink.get()?.advanceNativeGeneration(ticket.nativeGeneration) ?: true

    override fun authorizeDecoderGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean =
        adapter.tryAuthorizeDecoderGeneration(ticket.decoderGeneration, ticket.nativeGeneration)

    @Synchronized
    override fun promoteCandidateLeaseToActive(): Boolean {
        val current = lease ?: return false
        if (current.bankRole == NativeSongBankRole.ACTIVE) return true
        if (!leaseCoordinator.release(current)) return false
        val promoted = leaseCoordinator.tryAcquire(
            NativeSongBankRole.ACTIVE,
            laneIndex,
            current.decoderId
        )
        if (promoted == null) {
            // Restore the candidate lease; losing accounting while the codec remains open is fatal.
            lease = leaseCoordinator.tryAcquire(
                NativeSongBankRole.CANDIDATE,
                laneIndex,
                current.decoderId
            )
            return false
        }
        lease = promoted
        return true
    }

    @Synchronized
    override fun requestCancel() {
        if (joined || cancellationRequested) return
        cancellationRequested = true
        retirement = NativePreparationRetirement(
            stop = {
                try { adapter.beginCancellation(); decoder.cancel() } finally { decoder.close() }
            },
            isJoined = { decoder.awaitClosed(100, TimeUnit.MILLISECONDS) },
            release = { adapter.confirmDecoderJoined(); joined = true }
        )
    }

    @Synchronized
    override fun awaitJoin(timeoutNanos: Long): NativeEngineDecoderJoinStatus {
        require(timeoutNanos >= 0L)
        if (joined) return NativeEngineDecoderJoinStatus.JOINED
        check(cancellationRequested) { "Request decoder cancellation before joining it" }
        val finished = try {
            requireNotNull(retirement).await(timeoutNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) return NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED
        return NativeEngineDecoderJoinStatus.JOINED
    }

    override fun safeToReleaseLane(): Boolean =
        joined && adapter.diagnostics().safeToReleaseLane

    @Synchronized
    override fun close() {
        if (closed) return
        check(safeToReleaseLane()) { "Join the decoder before releasing its lease" }
        lease?.let {
            check(leaseCoordinator.release(it)) { "Decoder lease ownership was lost" }
        }
        lease = null
        closed = true
    }

}
