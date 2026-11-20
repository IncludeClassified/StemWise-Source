package app.stemwise.playback.engine

import android.content.Context
import app.stemwise.audioengine.BuildConfig
import app.stemwise.playback.LoopPreparationPhase
import app.stemwise.playback.LoopPreparationState
import app.stemwise.model.StemRoleContract
import app.stemwise.MAX_ENRICHMENT_DURATION_MS
import app.stemwise.MAX_NATIVE_BEAT_GRID_POINTS
import app.stemwise.playback.metronomeClickAmplitude
import app.stemwise.playback.persistedStemPackageIdentity
import app.stemwise.playback.playbackStableDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

const val NATIVE_ENGINE_SAMPLE_RATE_HZ = 48_000
/** At least one full pipeline block must cover the live cursor before first Stem activation. */
internal const val NATIVE_ENGINE_MINIMUM_STEM_RENDER_AHEAD_FRAMES = 1_024L

internal fun nativeTerminalEventIdentity(value: String): String =
    playbackStableDigest("native-terminal-event-v1|$value")

/**
 * True only for the one callback boundary at which device output has already presented the
 * reserved automatic successor but the render worker's ownership publication still describes the
 * outgoing owner. A diagnostics caller must not combine those two individually valid samples into
 * one impossible cross-component snapshot.
 */
internal fun automaticSuccessorBoundaryNeedsRuntimeCatchUp(
    runtimeStatus: NativeRuntimeStatus,
    automatic: NativeRuntimeAutomaticSuccessorDiagnostics,
    outputAppliedGeneration: Long,
    outputPresentedGeneration: Long,
    outputPresentedSelectionToken: Long,
    outputPresentedFingerprintSequence: Long
): Boolean {
    val phase = automatic.phase
    if (runtimeStatus != NativeRuntimeStatus.RUNNING ||
        (phase != NativeRuntimeAutomaticSuccessorPhase.STAGING &&
            phase != NativeRuntimeAutomaticSuccessorPhase.DRAIN_COMMITTED)
    ) return false
    val successor = automatic.successorOwnership ?: return false
    return outputAppliedGeneration == successor.generation &&
        outputPresentedGeneration == successor.generation &&
        outputPresentedSelectionToken == successor.selectionToken &&
        outputPresentedFingerprintSequence > 0L
}

/** Mirrors native LaneIngress's exclusive-boundary ceil conversion exactly. */
internal fun sourceBoundaryToNativeTimelineCeil(sourceFrameExclusive: Long, sourceRateHz: Int): Long {
    require(sourceFrameExclusive >= 0L && sourceRateHz > 0)
    return try {
        Math.addExact(
            Math.multiplyExact(sourceFrameExclusive, NATIVE_ENGINE_SAMPLE_RATE_HZ.toLong()),
            sourceRateHz.toLong() - 1L
        ) / sourceRateHz
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

data class NativeEngineSelectionRequest(
    val songId: String,
    val audioRevision: String,
    val originalLocation: String,
    val durationMs: Long,
    val startPositionMs: Long = 0L,
    val autoplay: Boolean = true,
    val forceReload: Boolean = false,
    val originalContentSha256: String? = null,
    val playbackStartMs: Long = 0L,
    val playbackEndMs: Long = durationMs,
    val initialControls: NativeEngineInitialControls = NativeEngineInitialControls()
) {
    init {
        require(songId.isNotBlank())
        require(audioRevision.isNotBlank())
        require(originalLocation.isNotBlank())
        require(durationMs > 0L)
        require(playbackStartMs >= 0L && playbackStartMs < playbackEndMs && playbackEndMs <= durationMs)
        require(startPositionMs in 0L..durationMs)
    }

    fun identity() = EngineAudioIdentity(
        songId = songId,
        audioRevision = audioRevision,
        originalLocation = originalLocation,
        durationMs = durationMs,
        originalContentSha256 = originalContentSha256,
        playbackStartMs = playbackStartMs,
        playbackEndMs = playbackEndMs
    )
}

/** Opaque library/session metadata. It never enters decoder or native command identity. */
data class NativeEngineMetadata(
    val songId: String,
    val audioRevision: String,
    val revision: Long,
    val payloadKey: String? = null
) {
    init {
        require(songId.isNotBlank())
        require(audioRevision.isNotBlank())
        require(revision > 0L)
    }
}

data class NativeEngineQueueItem(
    val identity: EngineAudioIdentity,
    val metadata: NativeEngineMetadata
) {
    init {
        require(metadata.songId == identity.songId)
        require(metadata.audioRevision == identity.audioRevision)
    }
}

/**
 * Queue policy resolved by the facade for the current owner's natural end.
 *
 * [NextOwner] may use the spare song bank. [RestartCurrent] deliberately keeps the current owner
 * and its resident Original/stem graph. Its duration is metadata/UI validation only; the native
 * Natural-end repeat resolves the explicit playback end, or decoded Original EOS when untrimmed.
 */
sealed interface NativeEngineAutomaticEndPlan {
    data class NextOwner(val item: NativeEngineQueueItem) : NativeEngineAutomaticEndPlan

    data class RestartCurrent(
        val identity: EngineAudioIdentity,
        val metadataDurationHintMs: Long,
        /** Natural queue successor retained dormant for instant re-arm when Repeat is disabled. */
        val fallbackNext: NativeEngineQueueItem? = null
    ) : NativeEngineAutomaticEndPlan {
        init {
            require(metadataDurationHintMs in 100L..identity.durationMs)
            require(fallbackNext?.identity != identity)
        }
    }
}

enum class NativeEngineOwnerPublicationKind {
    CALLBACK_PRESENTED,
    /** Dormant queue successor promoted only by native drain-boundary callback evidence. */
    AUTOMATIC_SUCCESSOR_PRESENTED,
    /**
     * The device callback already adopted the successor ring, then Pause closed the render gate
     * before successor PCM could produce presentation evidence. Exact native ownership, not a
     * fabricated audible callback, authorizes this administrative paused publication.
     */
    AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED,
    PAUSED_NONEMPTY_INSTALLED
}

@JvmInline
value class NativeEngineStopRequestId(val value: Long) {
    init { require(value > 0L) }
}

enum class NativeEngineStopStatus { COMPLETED, SUPERSEDED, FATAL_QUARANTINE }

class NativeEngineStopRequest internal constructor(
    val id: NativeEngineStopRequestId,
    internal val selectionEpoch: Long
) {
    private val completed = CountDownLatch(1)
    private val result = AtomicReference<NativeEngineStopStatus?>(null)

    fun isComplete(): Boolean = completed.count == 0L
    fun status(): NativeEngineStopStatus? = result.get()

    fun await(): NativeEngineStopStatus {
        var interrupted = false
        while (true) {
            try {
                completed.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return requireNotNull(result.get())
    }

    internal fun complete(status: NativeEngineStopStatus) {
        if (result.compareAndSet(null, status)) completed.countDown()
    }
}

internal data class NativeEngineOwnerPublication(
    val selection: EngineSelection,
    /** Exact selected bank/generation that produced this publication. */
    val ownership: NativeRuntimeOwnership,
    val metadata: NativeEngineMetadata?,
    val kind: NativeEngineOwnerPublicationKind,
    val evidence: EngineAudibleOwnerEvidence?,
    val completedSeekRevision: Long = 0L
) {
    init {
        require(ownership.selectionToken == selection.token.value)
        val pausedInstall = kind == NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED ||
            kind == NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED
        require(pausedInstall == (evidence == null))
    }
}

internal sealed interface NativeEngineControllerEvent {
    data class OutputAvailabilityChanged(val token: EngineSelectionToken, val available: Boolean,
        val diagnostic: String? = null) : NativeEngineControllerEvent
    data class OwnerPublished(val publication: NativeEngineOwnerPublication) :
        NativeEngineControllerEvent

    data class MetadataUpdated(val metadata: NativeEngineMetadata) :
        NativeEngineControllerEvent

    data class Position(
        val token: EngineSelectionToken,
        val positionMs: Long,
        val callbackSequence: Long,
        val ownership: NativeRuntimeOwnership? = null
    ) : NativeEngineControllerEvent

    data class AudibleEnd(
        val token: EngineSelectionToken,
        val endSequence: Long,
        val ownership: NativeRuntimeOwnership,
        /** False means native Repeat was not installed, so the facade must apply queue Repeat. */

        /** The explicit source change owns an EOS emitted by the graph it is replacing. */
        val sourceChangePending: Boolean = false
    ) : NativeEngineControllerEvent

    data class SourceModeApplied(
        val token: EngineSelectionToken,
        val desiredRevision: Long,
        val sourceMode: EngineSourceMode
    ) : NativeEngineControllerEvent

    /** The paused runtime accepted the exact source command; no audible callback exists yet. */
    data class SourceModeReadyAwaitingAudible(
        val token: EngineSelectionToken,
        val desiredRevision: Long,
        val sourceMode: EngineSourceMode
    ) : NativeEngineControllerEvent

    data class SourceModeNotReady(
        val token: EngineSelectionToken,
        val desiredRevision: Long,
        val requestedMode: EngineSourceMode,
        val reason: String
    ) : NativeEngineControllerEvent

    data class StemPackagePreparing(
        val token: EngineSelectionToken,
        val packageSignature: String,
        val stemRevision: String,
        val requestRevision: Long,
        val replacingPackageSignature: String?
    ) : NativeEngineControllerEvent

    /**
     * All declared music lanes cover one common anchor. Readiness alone is deliberately not an
     * audible/install acknowledgement; only exact settled callback evidence can install it.
     */
    data class StemPackageReadyAwaitingAudible(
        val token: EngineSelectionToken,
        val packageSignature: String,
        val stemRevision: String,
        val requestRevision: Long,
        val retainedPriorPackage: Boolean
    ) : NativeEngineControllerEvent

    /** Published only after one exact settled native callback proves this package was audible. */
    data class StemPackageInstalled(
        val token: EngineSelectionToken,
        val packageSignature: String,
        val stemRevision: String,
        val requestRevision: Long,
        val callbackSequence: Long
    ) : NativeEngineControllerEvent

    data class StemPackageFailed(
        val token: EngineSelectionToken,
        val packageSignature: String,
        val stemRevision: String,
        val requestRevision: Long,
        val recoverable: Boolean,
        val retainedPriorPackage: Boolean,
        val cause: String
    ) : NativeEngineControllerEvent

    data class DecoderFailed(
        val token: EngineSelectionToken,
        val generation: Long,
        val laneIndex: Int,
        val cause: String,
        val recoverable: Boolean
    ) : NativeEngineControllerEvent

    /** No decoder replacement can preserve this exact committed owner. */
    data class OwnerTerminated(
        val ownership: NativeRuntimeOwnership,
        val cause: String
    ) : NativeEngineControllerEvent

    /** The requested original never became an owner; any older committed owner is retained. */
    data class SelectionFailed(
        val token: EngineSelectionToken,
        val cause: String
    ) : NativeEngineControllerEvent

    data class LoopNotReady(
        val token: EngineSelectionToken,
        val loopRevision: Long,
        val loop: EngineLoopConfig,
        val reason: String
    ) : NativeEngineControllerEvent

    data class LoopAvailabilityChanged(
        val token: EngineSelectionToken,
        val ownership: NativeRuntimeOwnership,
        val available: Boolean,
        val preparation: LoopPreparationState = LoopPreparationState(
            if (available) LoopPreparationPhase.READY else LoopPreparationPhase.PREPARING,
            ownership.selectionToken, ownership.generation, canConfigure = true)
    ) : NativeEngineControllerEvent

    data class BeatGridNotReady(
        val token: EngineSelectionToken,
        val desiredRevision: Long,
        val reason: String
    ) : NativeEngineControllerEvent

    data class Failure(val operation: String, val diagnostic: String) :
        NativeEngineControllerEvent

    data class FatalRecoveryRequired(val diagnostic: String) :
        NativeEngineControllerEvent

    data class StopCompleted(val requestId: NativeEngineStopRequestId) :
        NativeEngineControllerEvent
    data object Closed : NativeEngineControllerEvent
}

internal fun interface NativeEngineControllerListener {
    fun onEvent(event: NativeEngineControllerEvent)

    companion object {
        val None = NativeEngineControllerListener { }
    }
}

internal data class NativeEngineDecoderSpec(
    val decoderId: String,
    val laneId: String,
    val location: String,
    val initialPositionUs: Long,
    val generationBase: Long,
    val selectionToken: EngineSelectionToken,
    val directFloat32Audio: Boolean = false,
    val sourceAdmission: NativeSourceAdmission = NativeSourceAdmission()
) {
    init {
        require(decoderId.isNotBlank())
        require(laneId.isNotBlank())
        require(location.isNotBlank())
        require(initialPositionUs >= 0L)
        require(generationBase > 0L)
    }
}

internal data class NativeEngineDecoderSeekTicket(
    val decoderGeneration: Long,
    val nativeGeneration: Long
) {
    init {
        require(decoderGeneration >= 0L)
        require(nativeGeneration > 0L)
    }
}

data class NativeEngineDecoderHead(
    val configured: Boolean,
    /** Monotonic accepted count for this generation; unlike queue depth it survives runtime drain. */
    val acceptedTimelineFrames48k: Long,
    val firstTimelineFrame48k: Long?,
    val acceptedTimelineFrameExclusive48k: Long?,
    val discontinuities: Long,
    /** Exact native ingress epoch currently authorized for this decoder head. */
    val nativeGeneration: Long = 0L,
    /** Exact accepted EOS boundary for this generation, or null until native accepted EOS. */
    val endOfStreamTimelineFrameExclusive48k: Long? = null
) {
    init {
        require(acceptedTimelineFrames48k >= 0L)
        require(firstTimelineFrame48k == null || firstTimelineFrame48k >= 0L)
        require(acceptedTimelineFrameExclusive48k == null ||
            acceptedTimelineFrameExclusive48k >= 0L)
        require(discontinuities >= 0L)
        require(nativeGeneration >= 0L)
        require(endOfStreamTimelineFrameExclusive48k == null ||
            endOfStreamTimelineFrameExclusive48k >= 0L)
    }
}

internal data class NativeEngineDecoderFailureSignal(
    val decoderId: String,
    val token: EngineSelectionToken,
    val generation: Long,
    val laneIndex: Int,
    val cause: String
) {
    init {
        require(decoderId.isNotBlank())
        require(generation > 0L)
        require(laneIndex in 0 until NativeSongLaneIngressBank.LANE_COUNT)
        require(cause.isNotBlank())
    }
}

internal enum class NativeEngineDecoderJoinStatus { JOINED, WATCHDOG_EXPIRED }

/** One MediaCodec worker plus its generation-gated adapter and lease. */
internal interface NativeEngineDecoder : AutoCloseable {
    val decoderId: String
    val laneIndex: Int
    fun start(): Boolean
    fun readableSourceFrames(): Int
    fun head(): NativeEngineDecoderHead
    fun pipelineDiagnostics(): NativeEngineDecoderPipelineDiagnostics =
        NativeEngineDecoderPipelineDiagnostics(
            decoderId = decoderId,
            laneIndex = laneIndex,
            head = head()
        )
    /** Actor-polled immutable terminal message; null until the decoder/adapter fails. */
    fun failure(): NativeEngineDecoderFailureSignal?
    fun requestSeek(
        positionUs: Long,
        targetNativeGeneration: Long
    ): NativeEngineDecoderSeekTicket
    fun canAuthorizeSeek(ticket: NativeEngineDecoderSeekTicket): Boolean
    fun advanceNativeGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean
    fun authorizeDecoderGeneration(ticket: NativeEngineDecoderSeekTicket): Boolean
    fun promoteCandidateLeaseToActive(): Boolean
    fun setSinkRetryThrottled(throttled: Boolean) = Unit
    /** Starts teardown without waiting so every lane in a bank can stop concurrently. */
    fun requestCancel()
    /** Waits only; [timeoutNanos] belongs to the caller's shared group deadline. */
    fun awaitJoin(timeoutNanos: Long): NativeEngineDecoderJoinStatus
    fun cancelAndJoin(watchdogMs: Long): NativeEngineDecoderJoinStatus {
        require(watchdogMs > 0L)
        requestCancel()
        return awaitJoin(TimeUnit.MILLISECONDS.toNanos(watchdogMs))
    }
    fun safeToReleaseLane(): Boolean
}

internal enum class NativeEngineWholeBankAdvanceStatus {
    COMMITTED,
    FAIL_STOP_REBUILD_REQUIRED
}

/**
 * Controller-only two-phase whole-bank generation boundary.
 *
 * Every adapter ticket is validated before JNI mutation. Native lane generations then advance in
 * canonical lane order, but no decoder adapter is authorized until every configured lane accepts.
 * Native generations cannot roll back. A native rejection or asynchronous decoder failure during
 * final authorization requires fail-stop/rebuild of the bank, including any already-authorized lanes.
 * The controller keeps its output boundary fenced until the replacement owner is ready.
 */
internal class NativeEngineWholeBankGenerationCoordinator {
    fun commit(
        tickets: Map<Int, Pair<NativeEngineDecoder, NativeEngineDecoderSeekTicket>>
    ): NativeEngineWholeBankAdvanceStatus {
        require(tickets.isNotEmpty())
        require(tickets.keys.all { it in 0 until NativeSongLaneIngressBank.LANE_COUNT })
        require(tickets.values.map { it.second.nativeGeneration }.toSet().size == 1)
        val ordered = tickets.toSortedMap().values
        if (ordered.any { (decoder, ticket) -> !decoder.canAuthorizeSeek(ticket) }) {
            return NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED
        }
        for ((decoder, ticket) in ordered) {
            if (!decoder.advanceNativeGeneration(ticket)) {
                return NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED
            }
        }
        // Decoder failure is asynchronous, including after every native lane has advanced.
        for ((decoder, ticket) in ordered) {
            if (!decoder.authorizeDecoderGeneration(ticket)) {
                return NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED
            }
        }
        return NativeEngineWholeBankAdvanceStatus.COMMITTED
    }
}

/**
 * Narrow owning port used by the command actor. The JNI implementation below owns one 48 kHz
 * output, exactly two bounded-capacity banks, the replaceable render runtime, and decoder leases.
 */
internal interface NativeEngineEnvironment : AutoCloseable, LoopPreparationPort {
    val outputGeneration: Long
    fun prepareSourceTransition(outgoing: NativeRuntimeOwnership, target: NativeRuntimeOwnership,
        pausedFallbackFrame: Long?): Long?
    fun cancelSourceTransition(target: NativeRuntimeOwnership)
    /** Zero disables the production-only live-reservoir gate used by deterministic fakes. */
    val requiredNativeStemRenderAheadFrames: Long get() = 0L
    suspend fun readPcmWindow(owner: LongArray, lane: Int, first: Long, frames: Int): SourcePcmWindowRead = SourcePcmWindowRead.Unsupported
    fun startRuntime(): Boolean
    fun prepareOutput(): NativeOutputPreparationState = NativeOutputPreparationState.READY
    fun publish(snapshot: NativeRuntimeCommandSnapshot): NativeRuntimePublishStatus
    fun publishStemGainPreview(
        publicationSequence: Long,
        coversOfferSequence: Long,
        ownership: NativeRuntimeOwnership,
        sourceIntentRevision: Long,
        stemIntentRevision: Long,
        canonicalControlFloorRevision: Long,
        activeMask: Int,
        gains: FloatArray
    ): NativeRuntimePublishStatus
    fun publishBeatGrid(update: NativeRuntimeBeatGridUpdate): NativeRuntimePublishStatus
    fun publishManagedLoopIntent(
        intent: NativeRuntimeManagedLoopIntent
    ): NativeRuntimePublishStatus
    /** Caller-thread A-B publication; it must not wait for the command actor or cache work. */
    fun publishDirectLoopPolicy(
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
        captureReferenceTimelineFrame: Long = -1L
    ): NativeRuntimePublishStatus = NativeRuntimePublishStatus.ACCEPTED
    fun isCurrentSongCacheRangeReady(intent: NativeRuntimeBankIntent, requiredLaneMask: Int,
        first: Long, end: Long): Boolean = false
    fun loopProtocolSnapshot(): NativeRuntimeLoopProtocolSnapshot
    fun setDiagnosticPayloadFingerprints(enabled: Boolean) = Unit
    fun setDetailedDiagnosticCapture(enabled: Boolean) = Unit
    fun controlProgress(): NativeRuntimeControlObservation = diagnostics()
    fun diagnostics(): NativeRuntimeDiagnostics
    fun stemGainCallbackSettlementDiagnostics(): StemGainCallbackSettlementDiagnostics? = null
    fun renderReadinessDiagnostics(): NativeRuntimeRenderReadinessDiagnostics? = null
    fun outputDiagnostics(): NativeAudioDiagnostics? = null
    /** Exact native proof only; false keeps cache-backed Stems/Loop fail-closed. */
    fun isCurrentSongCacheAllLanesSealed(ownership: NativeRuntimeOwnership): Boolean = false
    fun isFixedLoopHeadPrepared(ownership: NativeRuntimeOwnership): Boolean = false
    /** Exact native proof for the lanes the currently audible source actually renders. */
    fun isCurrentSongCacheLaneMaskSealed(
        ownership: NativeRuntimeOwnership,
        requiredLaneMask: Int
    ): Boolean = requiredLaneMask != 0 &&
        requiredLaneMask and NativeRuntimeManagedLoopIntent.ALL_LANES_MASK == requiredLaneMask &&
        isCurrentSongCacheAllLanesSealed(ownership)
    fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot =
        NativeEngineDiagnosticsSnapshot(
            runtime = diagnostics(),
            output = null,
            decoderLeases = decoderLeaseSnapshot(),
            nativeRenderReadiness = renderReadinessDiagnostics()
        )
    fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot =
        NativeAudioPresentationJournalSnapshot.empty(afterSequence)
    fun hasAppliedUnassignment(bank: NativeRuntimeBank, exactRevision: Long): Boolean
    /** Production may install one blocking render-worker evidence wake; deterministic fakes omit it. */
    fun setPcmCacheProgressListener(listener: (() -> Unit)?) = Unit
    fun setAudibleControlProgressListener(
        listener: ((NativeRuntimeAudibleControlEvidence) -> Unit)?
    ) = Unit
    fun openDecoder(
        bank: NativeRuntimeBank,
        bankRole: NativeSongBankRole,
        laneIndex: Int,
        spec: NativeEngineDecoderSpec
    ): NativeEngineDecoder
    fun releaseDecoder(bank: NativeRuntimeBank, decoder: NativeEngineDecoder)
    fun decoderLeaseSnapshot(): NativeDecoderLeaseSnapshot
    /**
     * Closes the callback's dedicated manual-selection render gate without changing runtime
     * transport state. Returns the output generation observed atomically with that arm.
     */
    fun armManualSelectionGate(): Long
    /** Authorizes one exact non-empty generation; the callback opens only after adopting it. */
    fun authorizeManualSelectionGate(generation: Long): Boolean
    /** Pauses callback consumption synchronously; buffer invalidation waits for producer join. */
    fun engageImmediateSilenceGate(): Long
    fun stopRuntimeAndJoin()
    /** Cheap atomic output-health probe; avoids a full diagnostics JNI read on every actor poll. */
    fun needsOutputRecovery(): Boolean = false
    val outputWarmPauseGraceMs: Long get() = 1_500L
    fun setOutputSessionSuspended(suspended: Boolean) = Unit
    /** Settles a logical pause by pausing callbacks on the same already-open device stream. */
    fun quiesceOutputIfPaused(): Boolean = true
    fun reopenOutputForRouteChange(): Boolean
    fun recreateRuntime(): Boolean
    fun retireOutputForStop(newGeneration: Long): Long?
}

internal interface NativeEngineActorScheduler : AutoCloseable {
    fun execute(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): NativeEngineScheduledTask
    fun monotonicTimeMs(): Long
}

internal fun interface NativeEngineScheduledTask {
    fun cancel(): Boolean
}

private class SingleThreadNativeEngineActorScheduler : NativeEngineActorScheduler {
    private val actorThread = AtomicReference<Thread?>()
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "StemWise-NativeEngine").apply {
            isDaemon = true
            actorThread.set(this)
        }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }

    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun schedule(delayMs: Long, task: () -> Unit): NativeEngineScheduledTask {
        require(delayMs >= 0L)
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return NativeEngineScheduledTask { future.cancel(false) }
    }

    override fun monotonicTimeMs(): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    override fun close() {
        check(Thread.currentThread() !== actorThread.get()) {
            "The native engine scheduler cannot join itself"
        }
        executor.shutdown()
        var interrupted = false
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * Serialized Kotlin owner for native playback setup/control work.
 *
 * Public calls only update bounded/conflated stores and wake the actor. The actor is the sole code
 * that touches runtime, output, banks, and decoder handles. Library queue size therefore changes
 * metadata memory only; physical resources remain two banks and the admitted shared decoder pool plus one loop-preparation worker.
 */
internal class NativePlaybackEngineController internal constructor(
    private val environment: NativeEngineEnvironment,
    private val scheduler: NativeEngineActorScheduler =
        SingleThreadNativeEngineActorScheduler(),
    private val listener: NativeEngineControllerListener = NativeEngineControllerListener.None
) : AutoCloseable {
    @Volatile internal var automaticPromotionBeforeInstallForTest: (() -> Unit)? = null
    @Volatile internal var seekAdmissionBeforeGateArmForTest: (() -> Unit)? = null

    private data class AutomaticEndPlanSnapshot(
        val revision: Long,
        val plan: NativeEngineAutomaticEndPlan?
    )

    private data class ManualPrewarmSnapshot(
        val revision: Long,
        val request: NativeEngineManualPrewarmRequest?
    )

    private data class AudibleTransportIntent(
        val token: EngineSelectionToken,
        val playWhenReady: Boolean
    )

    /** Caller-thread timestamp retained until the actor can bind the request to a native epoch. */
    private data class LocalStartRequest(
        val token: EngineSelectionToken,
        val requestedAtMs: Long
    )

    /** Exact actor-owned owner that must produce callback presentation before its deadline. */
    private data class LocalStartSettlement(
        val ownership: NativeRuntimeOwnership,
        val deadlineAtMs: Long
    )

    private data class ManualSelectionInvalidation(
        val token: EngineSelectionToken,
        val silenceGeneration: Long
    )

    private enum class ManualSelectionSilencePhase {
        CLOSED,
        AUTHORIZED
    }

    private data class ManualSelectionSilenceBurst(
        /** Null only while the synchronous selector is allocating the first replacement token. */
        val latestToken: EngineSelectionToken?,
        val silenceGeneration: Long,
        val phase: ManualSelectionSilencePhase
    ) {
        init {
            require(silenceGeneration > 0L)
        }
    }

    private val preparationEvidence = NativeControllerPreparationEvidence()
    private val ownerGate = EngineOwnerCommitGate()
    private val desiredStore = EngineDesiredStateStore()
    private val explicitLoopState = AtomicReference<EngineExplicitLoopState?>(null)
    /** Process-controller monotonic order fences concurrent MOVE callers before JNI. */
    private val directLoopPolicyRevision = AtomicLong(0L)
    private val mailbox = EngineCommandMailbox()
    private val stemMixPreviewMailbox = EngineStemMixPreviewMailbox()
    private val metadataByToken = ConcurrentHashMap<Long, NativeEngineMetadata>()
    /**
     * Cross-thread mirror of the actor-owned public selection.
     *
     * Metadata may arrive while A is still public and B is already the latest requested owner.
     * Keeping this exact selection (rather than only its token or song id) lets the caller bind an
     * update to both live identities without retaining a song-scoped cache.
     */
    private val publicSelectionSnapshot = AtomicReference<EngineSelection?>(null)
    private val automaticEndPlanUpdate = AtomicReference<AutomaticEndPlanSnapshot?>(null)
    private val requestedAutomaticEndPlan = AtomicReference<AutomaticEndPlanSnapshot?>(null)
    private val manualPrewarmUpdate = AtomicReference<ManualPrewarmSnapshot?>(null)
    private val requestedManualPrewarm = AtomicReference(ManualPrewarmSnapshot(0L, null))
    private val automaticEndPlanRequestCount = AtomicLong(0L)
    private val queueSequence = AtomicLong(0L)
    private val routeRecoveryRequested = AtomicBoolean(false)
    private val fatalFaultForTest = if (BuildConfig.BUILD_TYPE == "development") AtomicReference<String?>(null) else null
    private val actorQueued = AtomicBoolean(false)
    /** Conflates VSYNC mixer publications without waking the engine-wide health/polling turn. */
    private val stemMixPreviewActorQueued = AtomicBoolean(false)
    /** Instrumentation-only confluence gate; production remains permanently released. */
    private val actorHeldForSelectionBurstForTest = if (BuildConfig.BUILD_TYPE == "development") AtomicBoolean(false) else null
    private val actorTurnCount = AtomicLong(0L)
    private val fatalTransitionCount = AtomicLong(0L)
    @Volatile private var publishedStemReadiness: NativeStemReadinessDiagnostics? = null
    @Volatile private var publishedSourceControlPublications:
        List<NativeSourceControlPublicationDiagnostics> = emptyList()
    private val schedulingGate = Any()
    private var delayedPoll: NativeEngineScheduledTask? = null
    private val closeRequested = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val selectionEpoch = AtomicLong(0L)
    private val stopSequence = AtomicLong(0L)
    private val pendingStop = AtomicReference<NativeEngineStopRequest?>(null)
    /**
     * A burst of explicit selections owns one synchronous silence edge. It remains armed until the
     * exact latest non-empty generation is committed, so 400/4,000 conflated taps cannot hammer
     * JNI or briefly reopen the outgoing owner between actor turns.
     */
    private val manualSelectionSilenceBurst =
        AtomicReference<ManualSelectionSilenceBurst?>(null)
    private val manualSelectionSilenceArmGate = Any()
    private val manualSelectionSilenceEdgeCount = AtomicLong(0L)
    private val manualSelectionSilenceGenerationCaptureCount = AtomicLong(0L)
    private val manualSelectionInvalidation =
        AtomicReference<ManualSelectionInvalidation?>(null)
    private val localStartRequest = AtomicReference<LocalStartRequest?>(null)

    // Actor-owned state below this point.
    private val bankIntents = arrayOf(
        NativeRuntimeBankIntent.unassigned(1L),
        NativeRuntimeBankIntent.unassigned(1L)
    )
    private val releases = NativeBankRetirementOwner(environment, NATIVE_DECODER_JOIN_WATCHDOG_MS)
    private val generationCoordinator = NativeEngineWholeBankGenerationCoordinator()
    private var initialized = false
    private var runtimeIncarnation = 0L
    @Volatile private var runtimeStopped = true
    private var initializationFailed = false
    private var outputPreparation = NativeOutputPreparationState.READY
    private var nextOutputAttemptMs = 0L
    private var outputFailureToken: EngineSelectionToken? = null
    private var closing = false
    private var stopping = false
    private var focusSuspended = false
    private var audibleTransportIntent: AudibleTransportIntent? = null
    private var localStartSettlement: LocalStartSettlement? = null
    private var active: BankRecord? = null
    private var candidate: BankRecord? = null
    /** Same-song frame-zero graph retained while an early-seek Original is the audible owner. */
    private var cacheBuilder: BankRecord? = null
    private var selected: BankRecord? = null
    private var pendingPublication: BankRecord? = null
    private var pendingSeekOwnership: NativeRuntimeOwnership? = null
    private var deferredDesired: EngineDesiredState? = null
    private var automaticEndPlan = AutomaticEndPlanSnapshot(0L, null)
    private var manualPrewarm = ManualPrewarmSnapshot(0L, null)
    private var commandRevision = 0L
    private var controlRevision = 0L
    private var lastControlOwnership: NativeRuntimeOwnership? = null
    private var lastControlSourceIntentRevision = 0L
    private var lastControlStemIntentRevision = 0L
    private var lastRuntimeControls: NativeRuntimeControls? = null
    private val stemMixPreviewSnapshot = EngineStemMixPreviewSnapshot()
    private var actorStemMixPreviewAuthority: EngineStemMixPreviewAuthority? = null
    private var actorStemMixPreviewMask = 0
    private val actorStemMixPreviewGains =
        FloatArray(ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) { 1f }
    private var actorStemMixPreviewSequence = 0L
    /** Native publications also cover zero-mask retirement, so this is distinct from offer seq. */
    private var nativeStemMixPreviewPublicationSequence = 0L
    private val loopPreparation = LoopPreparationOwner(environment, scheduler::monotonicTimeMs, preparationEvidence) {
        emitFailure("loop-preparation", it)
    }
    private val cacheAdmission = NativeLoopCacheAdmission(environment, MINIMUM_PRIMED_TIMELINE_FRAMES, MANAGED_LOOP_DECLICK_FRAMES)
    private var loopRevision = 0L
    /** Immutable decoded-head revision; deliberately independent from loop policy commands. */
    private var beatGridRevision = 0L
    private var generationCursor = environment.outputGeneration
    /**
     * Producer-side output epoch accepted by the native latest-command mailbox.
     *
     * [NativeEngineEnvironment.outputGeneration] is the physical output's asynchronously applied
     * high-water mark. A command accepted earlier in this same actor turn can therefore be newer
     * than that observation. Retaining the accepted producer epoch prevents an assigned prewarm
     * from being selected with an ownership generation that the native command contract has
     * already retired, even before the render worker consumes the intervening empty snapshot.
     */
    private var acceptedCommandOutputGeneration = environment.outputGeneration
    /** Last transport bit accepted by native; used only to arm one non-extending warm grace. */
    private var lastAcceptedOutputPlaying: Boolean? = null
    private var outputWarmPauseDeadlineMs: Long? = null
    private var outputPhysicallyQuiesced = false
    private var outputQuiesceCount = 0L
    private val controlProgressObserver = NativeControlProgressObserver()
    private val latestNativeRenderReadiness: NativeRuntimeRenderReadinessDiagnostics?
        get() = controlProgressObserver.readiness
    private var lastCacheReadinessPending: Boolean? = null
    private var publicSelection: EngineSelection? = null
    private var publicOwnership: NativeRuntimeOwnership? = null
    private var publishedLoopAvailabilityOwnership: NativeRuntimeOwnership? = null
    private var publishedLoopAvailable = false
    private var publishedLoopPreparation = LoopPreparationState()
    private val loopRetryRequest = AtomicReference<Pair<NativeRuntimeOwnership, Long?>?>(null)
    private var actorExplicitLoopState: EngineExplicitLoopState? = null
    private var lastPublishedMetadataRevision = 0L
    private var suppressedLoopToken: EngineSelectionToken? = null
    private var suppressedLoopRevision: Long = -1L
    private var suppressedAutomaticEndPlanRevision: Long = -1L
    private var managedLoopStableIdentity: ManagedLoopStableIdentity? = null
    private var managedLoopIntent: NativeRuntimeManagedLoopIntent? = null
    private val automaticEndPolicy = NativeAutomaticEndPolicy()
    /** Queue Repeat One becomes authoritative only after exact native cache/install evidence. */
    private var repeatOneEffective = false
    private var lastLoopIngressReadySequence = 0L
    private var routeAwaitingOwnership: NativeRuntimeOwnership? = null
    private var routeRecoveryTransaction: RouteRecoveryTransaction? = null
    /** True when the persistent output object has no usable stream and must reopen before decode. */
    private var routeOutputNeedsReopen = false
    private var physicalReopenBeforePlay = false
    private var unselectedOutputGeneration = 0L
    /**
     * One logical selected-to-empty transition owns exactly one output generation. Bank-release
     * acknowledgements may arrive as several command revisions, including after native output has
     * already adopted the empty generation; none of those revisions may manufacture a second
     * invalidation epoch. The next accepted selected snapshot closes this interval.
     */
    private var unselectedEpochOpen = false
    @Volatile private var fatalQuarantine = false
    private var activeStop: NativeEngineStopRequest? = null
    private val handledDecoderFailures = linkedSetOf<String>()
    private val preparingStemEvents = linkedSetOf<String>()
    private val readyStemEvents = linkedSetOf<String>()
    private val installedStemEvents = linkedSetOf<String>()
    private val failedStemEvents = linkedSetOf<String>()
    private val sourceControlPublications = ArrayDeque<SourceControlPublication>()
    private val pendingAudibleControlEvidence =
        AtomicReference<NativeRuntimeAudibleControlEvidence?>(null)

    private data class ControllerDiagnostics(
        val actorTurn: Long = 0L,
        val metadataCount: Int = 0,
        val outputQuiesceCount: Long = 0L,
        val outputPhysicallyQuiesced: Boolean = false,
        val publicOwnership: NativeRuntimeOwnership? = null,
        val loopOwnership: NativeRuntimeOwnership? = null,
        val loopAvailable: Boolean = false,
        val observedReadiness: NativeRuntimeRenderReadinessDiagnostics? = null,
        val cachePending: Boolean? = null,
        val nextPollDelayMs: Long? = null,
        val pendingSeek: NativeRuntimeOwnership? = null,
        val ingressEpoch: Long = 0L,
        val loopIngressSequence: Long = 0L,
        val loopIngressEpoch: Long = 0L,
        val decoders: List<NativeEngineDecoder> = emptyList(),
        val stemReadiness: NativeStemReadinessDiagnostics? = null,
        val sourceControls: List<NativeSourceControlPublicationDiagnostics> = emptyList(),
        val candidateDecoders: List<NativeEngineDecoder> = emptyList()
    )
    @Volatile private var controllerDiagnostics = ControllerDiagnostics()

    internal fun publishedDecoderOwnersForTest(): List<NativeEngineDecoder> = controllerDiagnostics.decoders

    private data class ResourceOwnershipPublication(
        val knownTokenHighWater: Long,
        val songsByOwnedToken: Map<Long, String>,
        val automaticPlanRevision: Long,
        val manualPrewarmRevision: Long,
        val authoritative: Boolean
    )
    @Volatile private var resourceOwnershipPublication: ResourceOwnershipPublication? = null

    /** Exact source consumers, including outgoing banks whose unassignment/join is pending. */
    fun selectionRetirementTokens(songId: String): Set<EngineSelectionToken>? {
        val observed = resourceOwnershipPublication?.takeIf { it.authoritative } ?: return null
        if ((ownerGate.latest()?.token?.value ?: 0L) > observed.knownTokenHighWater ||
            (requestedAutomaticEndPlan.get()?.revision ?: 0L) > observed.automaticPlanRevision ||
            requestedManualPrewarm.get().revision > observed.manualPrewarmRevision) return null
        return observed.songsByOwnedToken.filterValues { it == songId }.keys
            .mapTo(linkedSetOf(), ::EngineSelectionToken)
    }

    fun isSelectionRetired(token: EngineSelectionToken): Boolean {
        val observed = resourceOwnershipPublication ?: return false
        return observed.authoritative && token.value <= observed.knownTokenHighWater &&
            token.value !in observed.songsByOwnedToken
    }

    private fun publishResourceOwnershipOnActor() {
        val previous = resourceOwnershipPublication
        val latest = ownerGate.latest()
        val authoritative = initialized && !fatalQuarantine &&
            (loopPreparation.preparingOwnership?.selectionToken?.let { token ->
                token == active?.ownership?.selectionToken || token == candidate?.ownership?.selectionToken ||
                    token == cacheBuilder?.ownership?.selectionToken || token == latest?.token?.value ||
                    releases.records.any { it.ownership.selectionToken == token }
            } != false)
        fun represented(record: BankRecord?): Boolean = record == null ||
            previous?.songsByOwnedToken?.get(record.ownership.selectionToken) == record.identity.songId
        if (previous != null && previous.authoritative == authoritative &&
            previous.automaticPlanRevision == automaticEndPlan.revision &&
            previous.manualPrewarmRevision == manualPrewarm.revision &&
            represented(active) && represented(candidate) && represented(cacheBuilder) &&
            releases.records.all(::represented) &&
            (latest == null || previous.songsByOwnedToken[latest.token.value] == latest.songId) &&
            previous.songsByOwnedToken.keys.all { token ->
                token == active?.ownership?.selectionToken || token == candidate?.ownership?.selectionToken ||
                    token == cacheBuilder?.ownership?.selectionToken || token == latest?.token?.value ||
                    releases.records.any { it.ownership.selectionToken == token }
            }) return
        val owned = buildMap {
            sequenceOf(active, candidate, cacheBuilder).filterNotNull().forEach {
                put(it.ownership.selectionToken, it.identity.songId)
            }
            releases.records.forEach { put(it.ownership.selectionToken, it.identity.songId) }
            // A selected request may still be queued behind retirement of the outgoing bank.
            ownerGate.latest()?.let { put(it.token.value, it.songId) }
        }
        val highWater = maxOf(previous?.knownTokenHighWater ?: 0L, owned.keys.maxOrNull() ?: 0L)
        if (previous == null || previous.knownTokenHighWater != highWater || previous.songsByOwnedToken != owned ||
            previous.automaticPlanRevision != automaticEndPlan.revision ||
            previous.manualPrewarmRevision != manualPrewarm.revision || previous.authoritative != authoritative) {
            resourceOwnershipPublication = ResourceOwnershipPublication(highWater, owned,
                automaticEndPlan.revision, manualPrewarm.revision, authoritative)
        }
    }

    private fun publishControllerDiagnosticsOnActor(nextPollDelayMs: Long? = null) {
        fun decoderSnapshot(record: BankRecord?, previous: List<NativeEngineDecoder>): List<NativeEngineDecoder> {
            val decoders = record?.decoders ?: return emptyList()
            return if (decoders.size == previous.size && decoders.values.withIndex().all { (index, decoder) ->
                previous[index] === decoder }) previous else decoders.values.toList()
        }
        publishResourceOwnershipOnActor()
        controllerDiagnostics = ControllerDiagnostics(
            actorTurnCount.get(), metadataByToken.size, outputQuiesceCount,
            outputPhysicallyQuiesced, publicOwnership, publishedLoopAvailabilityOwnership,
            publishedLoopAvailable, latestNativeRenderReadiness, lastCacheReadinessPending,
            nextPollDelayMs, pendingSeekOwnership, selected?.ingressEpoch ?: 0L,
            loopPreparation.refillRequest?.sequence ?: 0L, loopPreparation.refillRequest?.ingressEpoch ?: 0L,
            decoderSnapshot(selected, controllerDiagnostics.decoders), publishedStemReadiness,
            publishedSourceControlPublications, decoderSnapshot(candidate, controllerDiagnostics.candidateDecoders)
        )
    }

    init {
        environment.setAudibleControlProgressListener(::enqueueAudibleControlEvidence)
        environment.setPcmCacheProgressListener(::signalNativeProgress)
        kick()
    }

    /**
     * Read-only qualification snapshot from the one native runtime.
     *
     * This copies native counters, callback evidence and decoder diagnostics, and requests a
     * worker readiness publication. It does not mutate ownership or create another output,
     * but is not a passive high-frequency performance observer; use the presentation journal
     * for continuous callback-span checks.
     */
    fun setDiagnosticPayloadFingerprints(enabled: Boolean) = environment.setDiagnosticPayloadFingerprints(enabled)

    fun setDetailedDiagnosticCapture(enabled: Boolean): Boolean {
        if (closeCompleted.count == 0L) return false
        environment.setDetailedDiagnosticCapture(enabled)
        return true
    }

    fun diagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot {
        val controller = controllerDiagnostics
        return environment.diagnosticsSnapshot().copy(
            retainedMetadataTokenCount = controller.metadataCount,
            controllerActorTurnCount = controller.actorTurn,
            controllerOutputQuiesceCount = controller.outputQuiesceCount,
            controllerOutputPhysicallyQuiesced = controller.outputPhysicallyQuiesced,
            controllerFatalTransitionCount = fatalTransitionCount.get(),
            controllerFatalQuarantined = fatalQuarantine,
            manualSelectionSilenceEdgeCount = manualSelectionSilenceEdgeCount.get(),
            manualSelectionSilenceGenerationCaptureCount =
                manualSelectionSilenceGenerationCaptureCount.get(),
            controllerAutomaticEndPlanRequestCount = automaticEndPlanRequestCount.get(),
            controllerLoopProtocol = environment.loopProtocolSnapshot(),
            controllerPublicOwnership = controller.publicOwnership,
            controllerPublishedLoopOwnership = controller.loopOwnership,
            controllerPublishedLoopAvailable = controller.loopAvailable,
            controllerObservedRenderReadiness = controller.observedReadiness,
            controllerCacheReadinessPending = controller.cachePending,
            controllerNextPollDelayMs = controller.nextPollDelayMs,
            controllerPendingSeekOwnership = controller.pendingSeek,
            controllerSelectedIngressEpoch = controller.ingressEpoch,
            controllerLoopIngressPlanSequence = controller.loopIngressSequence,
            controllerLoopIngressPlanEpoch = controller.loopIngressEpoch,
            controllerSelectedDecoders = controller.decoders.map { it.pipelineDiagnostics() },
            controllerCandidateDecoders = controller.candidateDecoders.map { it.pipelineDiagnostics() },
            controllerStemReadiness = controller.stemReadiness,
            controllerSourceControlPublications = controller.sourceControls,
            controllerPreparationStages = preparationEvidence.snapshot(scheduler.monotonicTimeMs())
        )
    }

    fun sourceWaveformDiagnosticsSnapshot(): NativeEngineDiagnosticsSnapshot = NativeEngineDiagnosticsSnapshot(
        runtime = environment.diagnostics(), output = null,
        controllerSourceControlPublications = controllerDiagnostics.sourceControls,
        stemGainCallbackSettlement = environment.stemGainCallbackSettlementDiagnostics()
    )

    /** Privileged observer: no readiness request, actor turn or decoder-history construction. */
    internal fun loopBoundaryObservationForTest(): Pair<NativeRuntimePresentedPosition?, NativeAudioDiagnostics?> =
        environment.controlProgress().presentedPosition to environment.outputDiagnostics()

    suspend fun readSourcePcmWindow(identity: SourceWaveformIdentity, source: SourceWaveformSource,
        first: Long, frames: Int): SourcePcmWindowRead {
        val diagnostics = controllerDiagnostics
        if (diagnostics.publicOwnership != identity.ownership) return SourcePcmWindowRead.Stale
        val lane = identity.sources.indexOf(source).takeIf { it >= 0 } ?: return SourcePcmWindowRead.Unsupported
        val signature = diagnostics.sourceControls.lastOrNull { it.ownership == identity.ownership }
            ?.stemPackageSignature ?: return SourcePcmWindowRead.Unsupported
        val owner = longArrayOf(identity.ownership.selectionToken, identity.ownership.generation,
            stableRawHash("song-v1", identity.songId),
            stableRawHash("original-v1", identity.audioRevision, identity.sources.first().location,
                identity.sources.first().contentIdentity),
            if (signature.isEmpty()) 0L else stableRawHash("stems-v1", signature))
        val result = environment.readPcmWindow(owner, lane, first, frames)
        return if (controllerDiagnostics.publicOwnership == identity.ownership) result else SourcePcmWindowRead.Stale
    }

    /** Independent-cursor callback evidence; does not enter or delay the command actor. */
    fun presentationJournal(
        afterSequence: Long
    ): NativeAudioPresentationJournalSnapshot = environment.presentationJournal(afterSequence)

    /**
     * Instrumentation-only fault injection at the production controller boundary. The fault is
     * consumed by the native actor and enters the same quarantine handler as real actor failures.
     */
    internal fun injectFatalActorFaultForTest(diagnostic: String): Boolean {
        require(diagnostic.isNotBlank())
        if (closeRequested.get() || fatalQuarantine) return false
        val accepted = fatalFaultForTest?.compareAndSet(null, diagnostic) ?: return false
        if (accepted) kick()
        return accepted
    }

    /**
     * Holds the command actor after every already-running turn has quiesced. Public selection
     * calls remain nonblocking and continue conflating into their normal one-slot stores. This is
     * deliberately narrower than a fake scheduler: instrumentation still drives the production
     * controller, JNI silence gate, mailbox, decoder/runtime topology, and release logic.
     */
    internal fun holdActorForSelectionBurstForTest(): Boolean {
        if (closeRequested.get() || fatalQuarantine) return false
        if (actorHeldForSelectionBurstForTest?.compareAndSet(false, true) != true) return false
        synchronized(schedulingGate) {
            cancelDelayedPollIfPossibleLocked()
        }
        val quiesced = CountDownLatch(1)
        return try {
            scheduler.execute { quiesced.countDown() }
            var interrupted = false
            var reachedBarrier = false
            while (!reachedBarrier) {
                try {
                    reachedBarrier = quiesced.await(5L, TimeUnit.SECONDS)
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            if (!reachedBarrier || closeRequested.get() || fatalQuarantine) {
                actorHeldForSelectionBurstForTest?.set(false)
                kick()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            actorHeldForSelectionBurstForTest?.set(false)
            kick()
            false
        }
    }

    internal fun releaseActorForSelectionBurstForTest() {
        if (actorHeldForSelectionBurstForTest?.compareAndSet(true, false) == true &&
            !closeRequested.get()
        ) {
            kick()
        }
    }

    /**
     * Synchronous pre-lookup boundary for an explicit song selection.
     *
     * Source resolution may suspend while a native automatic successor is already committed.
     * Reuse the one manual render gate here so the output coordinator's paired drain fence is
     * visible before that suspension. A burst already waiting for its exact owner is reused.
     */
    fun beginManualSelectionIntentAdmission() {
        synchronized(manualSelectionSilenceArmGate) {
            if (closeRequested.get() || fatalQuarantine) return
            if (ownerGate.latest() != null || manualSelectionSilenceBurst.get() != null) {
                armManualSelectionSilenceBurstLocked()
            }
        }
    }

    /**
     * Source lookup found no replacement (including an already-current selection). Bind the
     * closed burst back to the exact latest owner; its normal accepted-PCM evidence reopens it.
     * A null/stale token cannot release the fence.
     */
    fun completeManualSelectionIntentWithoutReplacement(token: EngineSelectionToken?) {
        synchronized(manualSelectionSilenceArmGate) {
            if (closeRequested.get() || fatalQuarantine || token == null ||
                !ownerGate.isLatest(token)
            ) return
            val burst = manualSelectionSilenceBurst.get()?.takeIf {
                it.phase == ManualSelectionSilencePhase.CLOSED
            } ?: return
            if (burst.latestToken != token) {
                advanceManualSelectionSilenceBurstLocked(token, burst.silenceGeneration)
            }
            kick()
        }
    }

    /** Claims a matching prewarm token when possible; otherwise allocates a fresh request token. */
    fun select(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata? = null
    ): EngineSelectionToken = selectAdmission(request, metadata).token

    fun selectAdmission(
        request: NativeEngineSelectionRequest,
        metadata: NativeEngineMetadata? = null
    ): NativeEngineSelectionAdmission {
        val initialControls = request.initialControls.detachedCopy()
        stemMixPreviewMailbox.clear()
        return synchronized(manualSelectionSilenceArmGate) {
            check(!closeRequested.get()) { "The native playback controller is closing" }
            val identity = request.identity()
            publicSelectionSnapshot.get()?.takeIf { current ->
                !request.forceReload && current.audioIdentity() == identity &&
                    current.startPositionMs == nativeRenderablePositionMs(request.startPositionMs.toDouble(),
                        request.durationMs, request.playbackStartMs, request.playbackEndMs) &&
                    current.autoplay == request.autoplay &&
                    ownerGate.latest()?.token == current.token
            }?.let { current ->
                metadata?.let { retainNewerMetadata(current.token, it) }
                manualSelectionSilenceBurst.get()?.takeIf {
                    it.phase == ManualSelectionSilencePhase.CLOSED
                }?.let { burst ->
                    if (burst.latestToken != current.token) {
                        advanceManualSelectionSilenceBurstLocked(
                            current.token,
                            burst.silenceGeneration
                        )
                    }
                    kick()
                }
                return@synchronized NativeEngineSelectionAdmission(
                    current.token,
                    NativeEngineSelectionAdmissionKind.ALREADY_CURRENT
                )
            }
            val replacingSelection = ownerGate.latest() != null
            // A latest target can fail after it already retired the outgoing owner. Its render gate
            // deliberately remains armed; the next valid selection adopts that same burst instead of
            // opening a brief audible gap or spending another physical arm edge.
            val protectedBoundary = replacingSelection || manualSelectionSilenceBurst.get() != null
            // Arm before mutating ownerGate so an unexpected JNI gate failure cannot strand a latest
            // token that was never queued. This lock also serializes the exact-generation release in
            // the actor, so a concurrent C request cannot be overtaken by authorization for B.
            val armedSilenceGeneration = if (protectedBoundary) {
                armManualSelectionSilenceBurstLocked()
            } else {
                0L
            }
            val reserved = ownerGate.reservation()?.takeIf { it.identity == identity }
            val selection = reserved?.let {
                ownerGate.claim(it, request.startPositionMs, request.autoplay)
            } ?: ownerGate.request(
                songId = request.songId,
                audioRevision = request.audioRevision,
                originalLocation = request.originalLocation,
                durationMs = request.durationMs,
                startPositionMs = request.startPositionMs,
                autoplay = request.autoplay,
                playbackStartMs = request.playbackStartMs,
                playbackEndMs = request.playbackEndMs,
                originalContentSha256 = request.originalContentSha256
            )
            val silenceGeneration = if (protectedBoundary) {
                advanceManualSelectionSilenceBurstLocked(
                    selection.token,
                    armedSilenceGeneration
                )
            } else {
                0L
            }
            metadata?.also {
                require(it.songId == selection.songId && it.audioRevision == selection.audioRevision)
                retainNewerMetadata(selection.token, it)
                val publicToken = publicSelectionSnapshot.get()?.token?.value ?: 0L
                metadataByToken.keys.removeIf { token ->
                    token != selection.token.value && token != publicToken
                }
            }
            selectionEpoch.updateAndGet { current -> increment(current, "selection epoch") }
            if (protectedBoundary) {
                manualSelectionInvalidation.set(
                    ManualSelectionInvalidation(selection.token, silenceGeneration)
                )
            }
            val desired = desiredStore.install(selection, initialControls)
            explicitLoopState.set(EngineExplicitLoopState(selection.token, 0L, null))
            localStartRequest.set(
                selection.takeIf(EngineSelection::autoplay)?.let {
                    LocalStartRequest(it.token, scheduler.monotonicTimeMs())
                }
            )
            mailbox.offerDesired(desired)
            kick()
            NativeEngineSelectionAdmission(
                selection.token,
                if (reserved == null) {
                    NativeEngineSelectionAdmissionKind.NEW
                } else {
                    NativeEngineSelectionAdmissionKind.CLAIMED_PREWARM
                }
            )
        }
    }

    /** One physical render-gate arm/generation capture per arbitrarily large tap burst. */
    private fun armManualSelectionSilenceBurstLocked(): Long {
        manualSelectionSilenceBurst.get()?.takeIf {
            it.phase == ManualSelectionSilencePhase.CLOSED
        }?.let { return it.silenceGeneration }
        // AUTHORIZED is not equivalent to OPEN: the callback might not yet have adopted that
        // generation. Re-arm to revoke the older authorization before a later selector can commit.
        val generation = environment.armManualSelectionGate()
        check(generation > 0L) { "Manual-selection gate returned no output generation" }
        manualSelectionSilenceEdgeCount.incrementAndGet()
        manualSelectionSilenceGenerationCaptureCount.incrementAndGet()
        val armed = ManualSelectionSilenceBurst(
            latestToken = null,
            silenceGeneration = generation,
            phase = ManualSelectionSilencePhase.CLOSED
        )
        manualSelectionSilenceBurst.set(armed)
        return generation
    }

    private fun advanceManualSelectionSilenceBurstLocked(
        token: EngineSelectionToken,
        expectedGeneration: Long
    ): Long {
        val current = requireNotNull(manualSelectionSilenceBurst.get())
        check(current.phase == ManualSelectionSilencePhase.CLOSED)
        check(current.silenceGeneration >= expectedGeneration)
        manualSelectionSilenceBurst.set(current.copy(latestToken = token))
        return current.silenceGeneration
    }

    /**
     * Turns an active same-owner seek into one explicit callback discontinuity.
     *
     * A seek advances every decoder and native-render generation. The old output ring is therefore
     * no longer refilled while MediaCodec positions the new generation; allowing the callback to
     * drain that small ring would report a real underrun and could expose a pop. Reuse the exact
     * manual-selection gate without invalidating the logical owner: it fades closed before the
     * decoder generation changes and reopens only after non-empty PCM for this same token's new
     * generation has entered native output.
     */
    private fun armInPlaceSeekBoundary(record: BankRecord) {
        // [active] is the public owner, not necessarily the selected physical graph: a clean
        // same-token stem replacement remains a candidate until exact callback evidence. Protect
        // whichever assigned graph native is actually selecting.
        if (record !== selected || !record.assigned) return
        val token = record.selection?.token ?: return
        synchronized(manualSelectionSilenceArmGate) {
            if (!ownerGate.isLatest(token) || record !== selected || !record.assigned) {
                return@synchronized
            }
            val capturedGeneration = armManualSelectionSilenceBurstLocked()
            advanceManualSelectionSilenceBurstLocked(token, capturedGeneration)
        }
    }

    /** Stale-token transforms are rejected before the actor or JNI sees them. */
    fun updateDesired(
        token: EngineSelectionToken,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): Boolean = admitDesiredMutation(token, null, null, transform) != null

    /** Exact-revision control admission used by the adapter to reject stale commands safely. */
    fun updateDesiredIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? = admitDesiredMutation(token, expectedRevision, null, transform)

    fun retryLoopPreparation(ownership: NativeRuntimeOwnership, requestRevision: Long? = null): Boolean {
        if (closeRequested.get() || fatalQuarantine) return false
        loopRetryRequest.set(ownership to requestRevision)
        kick()
        return true
    }

    /** Dedicated A-B lane: never republishes transport, source, queue, or aggregate controls. */
    fun updateExplicitLoop(state: EngineExplicitLoopState): Boolean =
        updateExplicitLoop(state, forTransportSeek = false)

    /**
     * Makes the outgoing generation dormant before an exact seek changes physical ownership.
     * The retained config records whether the target is inside A/B; the new generation rebinds it.
     */
    fun updateExplicitLoopForTransportSeek(
        state: EngineExplicitLoopState
    ): Boolean {
        require(state.inputEventAtMonotonicMicros == 0L)
        require(state.heldBGestureId == 0L)
        require(state.capturedBMs < 0L)
        return updateExplicitLoop(state, forTransportSeek = true)
    }

    private fun updateExplicitLoop(
        state: EngineExplicitLoopState,
        forTransportSeek: Boolean
    ): Boolean =
        synchronized(manualSelectionSilenceArmGate) {
            if (closeRequested.get() || fatalQuarantine || !ownerGate.isLatest(state.token)) {
                return@synchronized false
            }
            val current = explicitLoopState.get() ?: return@synchronized false
            val selection = ownerGate.latest() ?: return@synchronized false
            if (state.config?.let {
                    it.startMs < selection.playbackStartMs || it.endMs > selection.playbackEndMs
                } == true) return@synchronized false
            val ownership = state.ownership ?: return@synchronized false
            if (current.token != state.token || state.revision != current.revision + 1L) {
                return@synchronized false
            }
            // A captured B crossing belongs to the existing A head. Changing A in that same
            // command invalidates its continuation; ordinary A/B edits carry no B capture.
            if (state.capturedBMs >= 0L && state.config?.startMs != current.config?.startMs) {
                return@synchronized false
            }
            val publicationRevision = directLoopPolicyRevision.incrementAndGet()
            check(publicationRevision > 0L) { "Direct loop policy revision space exhausted" }
            val direct = state.config
            // B and the cursor can cross without ever producing an exact B == cursor sample. The
            // crossing is armed even when playback overtakes a stationary or right-moving B; the
            // native callback latches it until one A jump.
            // Held gestures own their crossing edge; parked UI state can lag a native wrap.
            val crossedB = !forTransportSeek && state.heldBGestureId == 0L && current.config?.let { previous ->
                direct != null && !previous.parkUntilRangeEntry &&
                    direct.parkUntilRangeEntry && direct.startMs == previous.startMs
            } == true
            val capturedBFrameExclusive = when {
                state.capturedBMs >= 0L -> msToFrame(state.capturedBMs)
                crossedB -> direct?.let { msToFrame(it.endMs) } ?: 0L
                else -> 0L
            }
            if (environment.publishDirectLoopPolicy(
                    revision = publicationRevision,
                    selectionToken = state.token.value,
                    generation = ownership.generation,
                    enabled = direct != null,
                    aTimelineFrame = direct?.let { msToFrame(it.startMs) } ?: 0L,
                    bTimelineFrameExclusive = direct?.let { msToFrame(it.endMs) } ?: 0L,
                    capturedBTimelineFrameExclusive = capturedBFrameExclusive,
                    inputEventAtMonotonicMicros = state.inputEventAtMonotonicMicros,
                    heldBGestureId = state.heldBGestureId,
                    boundaryArmedAtPublication = !forTransportSeek && direct != null &&
                        (!direct.parkUntilRangeEntry || capturedBFrameExclusive != 0L),
                    boundaryCrossedAtPublication = !forTransportSeek &&
                        capturedBFrameExclusive != 0L,
                    captureReferenceTimelineFrame = if (state.captureReferencePositionMs >= 0L) {
                        msToFrame(state.captureReferencePositionMs)
                    } else -1L
                ) != NativeRuntimePublishStatus.ACCEPTED
            ) {
                return@synchronized false
            }
            // Automatic promotion can replace the token on the actor. An accepted old-token native
            // policy is harmless (render owner fencing rejects it), but must not enter Kotlin state.
            val acceptedState = state.copy(nativePolicyRevision = publicationRevision)
            if (!ownerGate.isLatest(state.token) ||
                !explicitLoopState.compareAndSet(current, acceptedState)
            ) {
                return@synchronized false
            }
            mailbox.offerExplicitLoop(acceptedState)
            kick()
            true
        }

    /**
     * Atomically queues the latest token state and the transport intent that must also govern an
     * older outgoing bank until handoff. This closes the Pause/noisy race without making a pending
     * selection publicly audible before callback proof.
     */
    fun updateDesiredWithAudibleTransport(
        token: EngineSelectionToken,
        playWhenReady: Boolean,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): Boolean = admitDesiredMutation(token, null, playWhenReady, transform) != null

    fun updateDesiredWithAudibleTransportIfRevision(
        token: EngineSelectionToken,
        expectedRevision: Long,
        playWhenReady: Boolean,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? = admitDesiredMutation(token, expectedRevision, playWhenReady, transform)

    private fun admitDesiredMutation(
        token: EngineSelectionToken,
        expectedRevision: Long?,
        audiblePlayWhenReady: Boolean?,
        transform: (EngineDesiredState) -> EngineDesiredState
    ): EngineDesiredState? {
        if (closeRequested.get() || fatalQuarantine) return null
        val previous = desiredStore.latest()?.takeIf { it.selection.token == token } ?: return null
        if (expectedRevision != null && previous.revision != expectedRevision) return null
        val preview = transform(previous)
        val seekChanged = preview.seekRevision != previous.seekRevision
        fun commit(): EngineDesiredState? {
            if (closeRequested.get() || fatalQuarantine) return null
            return desiredStore.updateAndPublishIfRevision(token, previous.revision, { preview }) { updated ->
                if (seekChanged) {
                    if (BuildConfig.BUILD_TYPE == "development") seekAdmissionBeforeGateArmForTest?.invoke()
                    if (!runtimeStopped && ownerGate.isLatest(previous.selection.token)) {
                        val generation = armManualSelectionSilenceBurstLocked()
                        advanceManualSelectionSilenceBurstLocked(previous.selection.token, generation)
                    }
                }
                updateLocalStartRequest(updated)
                mailbox.offerDesired(updated)
                audiblePlayWhenReady?.let { mailbox.offerAudibleTransport(token, it) }
                kick()
            }
        }
        // The actor drains and promotes under this gate. No other command can expose an
        // accepted seek before its callback fence is armed; ordinary controls keep their fast path.
        return if (seekChanged) synchronized(manualSelectionSilenceArmGate) { commit() } else commit()
    }

    /** Keeps one original request timestamp; unrelated control updates cannot extend the bound. */
    private fun updateLocalStartRequest(updated: EngineDesiredState) {
        val token = updated.selection.token
        localStartRequest.updateAndGet { current ->
            when {
                !updated.playWhenReady || publicSelectionSnapshot.get()?.token == token ->
                    current?.takeUnless { it.token == token }
                current?.token == token -> current
                else -> LocalStartRequest(token, scheduler.monotonicTimeMs())
            }
        }
    }

    /** Gesture-rate ownership is opened once; MOVE never enters desired-state or command locks. */
    fun beginStemMixPreview(
        token: EngineSelectionToken,
        ownership: NativeRuntimeOwnership,
        packageSignature: String,
        packageRequestRevision: Long,
        laneIndex: Int
    ): EngineStemMixPreviewLease? {
        check(!closeRequested.get()) { "The native playback controller is closing" }
        if (fatalQuarantine) return null
        val desired = desiredStore.latest()?.takeIf { it.selection.token == token } ?: return null
        if (laneIndex !in desired.stemLanes.indices ||
            !StemRoleContract.isPlayable(desired.stemLanes.map(EngineStemLane::normalizedRole))
        ) return null
        val lease = stemMixPreviewMailbox.begin(
            token,
            ownership,
            packageSignature,
            packageRequestRevision,
            laneIndex
        ) ?: return null
        scheduleStemMixPreviewActor()
        return lease
    }

    /** Fixed primitive publication followed by one conflated task on the existing engine actor. */
    fun offerStemMixPreviewBatch(
        lease: EngineStemMixPreviewLease,
        changedMask: Int,
        gains: FloatArray
    ): Boolean {
        if (closeRequested.get() || fatalQuarantine) return false
        val accepted = stemMixPreviewMailbox.offer(
            lease,
            changedMask,
            gains
        )
        if (accepted) scheduleStemMixPreviewActor()
        return accepted
    }

    /** Fences the exact lane before its ordinary terminal desired-state commit is submitted. */
    fun endStemMixPreview(lease: EngineStemMixPreviewLease): Boolean {
        val ended = stemMixPreviewMailbox.end(lease)
        if (!closeRequested.get() && !fatalQuarantine) scheduleStemMixPreviewActor()
        return ended
    }

    /**
     * Enrichment/artwork/title changes bind only to an exact public or latest-requested audio
     * identity. Public changes publish in place; pending changes travel with that pending token.
     * Neither path creates a selection token, decoder, seek, or native command.
     */
    fun updateMetadata(metadata: NativeEngineMetadata) {
        check(!closeRequested.get()) { "The native playback controller is closing" }
        val latest = ownerGate.latest()
        val public = publicSelectionSnapshot.get()
        sequenceOf(latest, public)
            .filterNotNull()
            .distinctBy { it.token }
            .filter {
                it.songId == metadata.songId && it.audioRevision == metadata.audioRevision
            }
            .forEach { retainNewerMetadata(it.token, metadata) }
        kick()
    }

    private fun retainNewerMetadata(
        token: EngineSelectionToken,
        metadata: NativeEngineMetadata
    ) {
        metadataByToken.compute(token.value) { _, previous ->
            if (previous == null || metadata.revision > previous.revision) metadata else previous
        }
    }

    /** Immutable focused-test evidence for the bounded, token-owned metadata contract. */
    internal fun retainedMetadataForTest(): Map<Long, NativeEngineMetadata> =
        metadataByToken.toMap()

    internal fun retainedHistoryIdentityLengthsForTest(): List<Int> = buildList {
        handledDecoderFailures.mapTo(this, String::length)
        preparingStemEvents.mapTo(this, String::length)
        readyStemEvents.mapTo(this, String::length)
        installedStemEvents.mapTo(this, String::length)
        failedStemEvents.mapTo(this, String::length)
        sourceControlPublications.mapNotNullTo(this) { it.stemPackageIdentity?.length }
    }

    /** PlaybackQueueController supplies its already-resolved natural-end policy. */
    @Synchronized
    fun setAutomaticEndPlan(plan: NativeEngineAutomaticEndPlan?) {
        check(!closeRequested.get()) { "The native playback controller is closing" }
        val previous = requestedAutomaticEndPlan.get()
        if (previous?.plan == plan) return
        val audioChanged = !sameAutomaticEndAudioDecision(previous?.plan, plan)
        val revision = if (!audioChanged && previous != null) previous.revision else {
            queueSequence.updateAndGet { current ->
                check(current < Long.MAX_VALUE) { "Queue revision space exhausted" }
                current + 1L
            }
        }
        val snapshot = AutomaticEndPlanSnapshot(revision, plan)
        requestedAutomaticEndPlan.set(snapshot)
        automaticEndPlanUpdate.set(snapshot)
        if (audioChanged) automaticEndPlanRequestCount.updateAndGet { current ->
            increment(current, "automatic-end plan request")
        }
        kick()
    }

    fun setManualPrewarm(request: NativeEngineManualPrewarmRequest?) {
        check(!closeRequested.get()) { "The native playback controller is closing" }
        if (requestedManualPrewarm.get().request == request) return
        val revision = queueSequence.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Queue revision space exhausted" }
            current + 1L
        }
        val snapshot = ManualPrewarmSnapshot(revision, request)
        requestedManualPrewarm.set(snapshot)
        manualPrewarmUpdate.set(snapshot)
        kick()
    }

    fun suspendForAudioFocusLoss() {
        mailbox.offerLifecycle(EngineLifecycleAction.SUSPEND_FOR_AUDIO_FOCUS)
        kick()
    }

    fun resumeAfterAudioFocusGain() {
        mailbox.offerLifecycle(EngineLifecycleAction.RESUME_AFTER_AUDIO_FOCUS)
        kick()
    }

    fun requestRouteRecovery() {
        check(!closeRequested.get()) { "The native playback controller is closing" }
        routeRecoveryRequested.set(true)
        kick()
    }

    /** Full Recents/task swipe contract: unassign, exact ack, cancel/join, runtime join, flush. */
    fun stopForTaskRemoval(): NativeEngineStopRequest {
        stemMixPreviewMailbox.clear()
        return synchronized(manualSelectionSilenceArmGate) {
            check(!closeRequested.get()) { "The native playback controller is closing" }
            val request = NativeEngineStopRequest(
                NativeEngineStopRequestId(
                    stopSequence.updateAndGet { current -> increment(current, "stop request") }
                ),
                selectionEpoch.get()
            )
            pendingStop.getAndSet(request)?.complete(NativeEngineStopStatus.SUPERSEDED)
            if (fatalQuarantine) {
                // The actor and native runtime are deliberately terminal after an unsafe failure;
                // pretending to enqueue STOP would leave the caller waiting for an actor that can
                // never consume it. Physical silence was engaged by enterFatalQuarantine().
                pendingStop.compareAndSet(request, null)
                request.complete(NativeEngineStopStatus.FATAL_QUARANTINE)
                return@synchronized request
            }
            try {
                environment.engageImmediateSilenceGate()
            } finally {
                // STOP and Select share this gate. A selection concurrent with Recents removal is
                // therefore ordered wholly before STOP or wholly after it; STOP can no longer
                // clear a newer DesiredState that was inserted inside its silence/enqueue gap.
                mailbox.offerStop(request)
                kick()
            }
            request
        }
    }

    /** Tests and decoder callbacks may request an immediate evidence/ack poll. */
    fun signalNativeProgress() = kick()

    private fun enqueueAudibleControlEvidence(evidence: NativeRuntimeAudibleControlEvidence) {
        pendingAudibleControlEvidence.set(evidence)
        kick()
    }

    override fun close() {
        stemMixPreviewMailbox.clear()
        actorHeldForSelectionBurstForTest?.set(false)
        if (fatalQuarantine) closeCompleted.countDown()
        if (closeRequested.compareAndSet(false, true)) {
            mailbox.offerLifecycle(EngineLifecycleAction.CLOSE)
            kick()
        }
        var interrupted = false
        while (true) {
            try {
                closeCompleted.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        sourceLeases.clear()
        scheduler.close()
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun kick() {
        if (actorHeldForSelectionBurstForTest?.get() == true && !closeRequested.get()) return
        synchronized(schedulingGate) {
            cancelDelayedPollIfPossibleLocked()
        }
        if (!actorQueued.compareAndSet(false, true)) return
        val task = {
            actorQueued.set(false)
            if (actorHeldForSelectionBurstForTest?.get() != true || closeRequested.get()) runActorTurn()
        }
        scheduler.execute(task)
    }

    /**
     * Mixer MOVE is already conflated at VSYNC. Queue one lightweight task on the same serialized
     * actor instead of turning a held thumb into a 1 ms engine-wide diagnostics/render poll.
     */
    private fun scheduleStemMixPreviewActor() {
        if (closeRequested.get() || fatalQuarantine ||
            actorHeldForSelectionBurstForTest?.get() == true ||
            !stemMixPreviewActorQueued.compareAndSet(false, true)
        ) return
        scheduler.execute {
            // Release before reading so a newer publication arriving during this turn can enqueue
            // exactly one successor. The single-thread scheduler still serializes all actor state.
            stemMixPreviewActorQueued.set(false)
            runStemMixPreviewActorTurn()
        }
    }

    private fun runStemMixPreviewActorTurn() {
        if (closeRequested.get() || fatalQuarantine || closing || stopping || runtimeStopped ||
            !initialized
        ) return
        // A canonical command always wins the same serialized actor. In particular, ACTION_UP
        // queues its exact desired value before retiring the lane. Publishing a preview-only
        // restoration while that command is waiting would briefly expose the older canonical
        // gain; the normal actor turn will drain the command and reconcile the terminal fence.
        if (mailbox.hasPendingWork()) return
        try {
            val changed = reconcileStemMixPreviewOnActor(refreshReadiness = false)
            if (changed && selected?.assigned == true) {
                publishStemMixPreviewOnActor()
            }
        } catch (failure: Throwable) {
            enterFatalQuarantine(
                "Native engine mixer-preview actor failed: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        } finally {
            publishControllerDiagnosticsOnActor()
        }
    }

    /** Must be called under [schedulingGate]; ownership transfers only after cancellation wins. */
    private fun cancelDelayedPollIfPossibleLocked() {
        val pending = delayedPoll ?: return
        if (!pending.cancel()) {
            // The callback has already started. Keep its marker and actorQueued ownership intact so
            // it can consume the command that caused this attempted preemption.
            return
        }
        delayedPoll = null
        actorQueued.set(false)
    }

    private fun schedulePoll(delayMs: Long) {
        require(delayMs > 0L)
        synchronized(schedulingGate) {
            if (delayedPoll != null || fatalQuarantine ||
                actorHeldForSelectionBurstForTest?.get() == true ||
                !actorQueued.compareAndSet(false, true)
            ) return
            lateinit var scheduled: NativeEngineScheduledTask
            try {
                scheduled = scheduler.schedule(delayMs) {
                    val ownsWake = synchronized(schedulingGate) {
                        if (delayedPoll !== scheduled) {
                            false
                        } else {
                            delayedPoll = null
                            true
                        }
                    }
                    if (ownsWake) {
                        actorQueued.set(false)
                        if (actorHeldForSelectionBurstForTest?.get() != true || closeRequested.get()) {
                            runActorTurn()
                        }
                    }
                }
                delayedPoll = scheduled
            } catch (failure: Throwable) {
                actorQueued.set(false)
                throw failure
            }
        }
    }

    private fun runActorTurn() {
        actorTurnCount.incrementAndGet()
        if (fatalQuarantine) {
            if (closeRequested.get()) closeCompleted.countDown()
            return
        }
        if (closing && closeCompleted.count == 0L) return
        var pollDelay: Long? = null
        try {
            if (!initialized) {
                if (closeRequested.get()) {
                    pendingStop.getAndSet(null)?.complete(NativeEngineStopStatus.SUPERSEDED)
                    environment.close()
                    listener.onEvent(NativeEngineControllerEvent.Closed)
                    closeCompleted.countDown()
                    return
                }
                if (ownerGate.latest() == null && !mailbox.hasPendingWork()) return
            }
            fatalFaultForTest?.getAndSet(null)?.let { diagnostic ->
                enterFatalQuarantine(diagnostic)
                return
            }
            val automaticEndPlanChanged = automaticEndPlanUpdate.getAndSet(null)?.takeIf {
                it.revision >= automaticEndPlan.revision
            }?.let { update ->
                val audioChanged = update.revision != automaticEndPlan.revision
                automaticEndPlan = update
                if (audioChanged) suppressedAutomaticEndPlanRevision = -1L
                val item = when (val plan = update.plan) {
                    is NativeEngineAutomaticEndPlan.NextOwner -> plan.item
                    is NativeEngineAutomaticEndPlan.RestartCurrent -> plan.fallbackNext
                    null -> null
                }
                if (item != null) sequenceOf(active, candidate, cacheBuilder).filterNotNull()
                    .filter { it.identity == item.identity }.forEach { record ->
                        val token = record.selection?.token ?: record.reservation?.token
                        token?.let { retainNewerMetadata(it, item.metadata) }
                    }
                audioChanged
            } == true
            manualPrewarmUpdate.getAndSet(null)?.takeIf {
                it.revision > manualPrewarm.revision
            }?.let { manualPrewarm = it }
            // select() publishes the latest manual-boundary invalidation and its desired state
            // under manualSelectionSilenceArmGate. Consume both as one snapshot under that same
            // gate: otherwise a selector can land between getAndSet(null) and mailbox.drain(),
            // letting this turn install C and the next turn retire C with no desired state left to
            // restore it. That strands playback silent behind the armed callback gate.
            val (manualInvalidation, mailboxEntries) =
                synchronized(manualSelectionSilenceArmGate) {
                    val invalidation = manualSelectionInvalidation.getAndSet(null)
                    invalidation to mailbox.drain()
                }
            manualInvalidation?.takeIf {
                ownerGate.isLatest(it.token)
            }?.let(::invalidateOutgoingForManualSelectionOnActor)
            if (fatalQuarantine) return
            for (entry in mailboxEntries) {
                when (entry) {
                    is EngineMailboxEntry.DesiredState -> acceptDesiredOnActor(entry.state)
                    is EngineMailboxEntry.AudibleTransport -> handleAudibleTransportOnActor(entry)
                    is EngineMailboxEntry.ExplicitLoop -> handleExplicitLoopOnActor(entry.state)
                    is EngineMailboxEntry.Stop -> if (!entry.request.isComplete()) {
                        beginStop(closeAfter = false, request = entry.request)
                    }
                    is EngineMailboxEntry.Lifecycle -> handleLifecycleOnActor(entry.action)
                }
                if (fatalQuarantine) return
            }
            if (!initialized && ownerGate.latest() != null && !stopping && !closing) {
                if (initializationFailed && !hasPhysicalPlayDemand()) return
                if (!initializeOnActor()) {
                    reportOutputUnavailable()
                    initializationFailed = true
                    if (hasPhysicalPlayDemand()) schedulePoll(ROUTE_RECOVERY_RETRY_INTERVAL_MS)
                    return
                }
                initializationFailed = false
            }
            if (hasPhysicalPlayDemand() && physicalReopenBeforePlay) {
                physicalReopenBeforePlay = false
                routeOutputNeedsReopen = true
                routeRecoveryRequested.set(true)
            }
            if (hasPhysicalPlayDemand() && routeRecoveryTransaction?.phase != RouteRecoveryPhase.RETIRING_OUTPUT &&
                scheduler.monotonicTimeMs() >= nextOutputAttemptMs) {
                val state = environment.prepareOutput()
                if (state == NativeOutputPreparationState.UNAVAILABLE) {
                    reportOutputUnavailable()
                }
                if (state == NativeOutputPreparationState.READY) {
                    if (outputFailureToken != null) {
                        outputFailureToken = null
                        ownerGate.latest()?.token?.let { listener.onEvent(
                            NativeEngineControllerEvent.OutputAvailabilityChanged(it, true)) }
                    }
                }
                outputPreparation = state
                nextOutputAttemptMs = scheduler.monotonicTimeMs() +
                    if (state == NativeOutputPreparationState.UNAVAILABLE) ROUTE_RECOVERY_RETRY_INTERVAL_MS else 0L
            }
            val stemMixPreviewChanged = reconcileStemMixPreviewOnActor(refreshReadiness = true)
            if (stemMixPreviewChanged && !runtimeStopped && !stopping && !closing &&
                selected?.assigned == true
            ) {
                publishStemMixPreviewOnActor()
            }
            if (automaticEndPlanChanged) {
                selected?.takeIf { it.assigned && it.bank !in releases }?.let { record ->
                    record.desired?.let { desired -> reconcileManagedLoopOnActor(record, desired) }
                }
            }
            if (fatalQuarantine) return
            if (!runtimeStopped) pollRuntimeOnActor()
            // Presentation and source-control proof can be published by the same callback. Rebase
            // public ownership first; otherwise the watcher evidence is rejected against the old
            // generation and its one-shot sequence is consumed permanently.
            pendingAudibleControlEvidence.getAndSet(null)?.let { evidence ->
                if (evidence.sequence > controlProgressObserver.audibleControlSequence) {
                    handleAudibleControlEvidence(evidence)
                }
            }
            if (fatalQuarantine) return
            reconcileLocalStartSettlementOnActor()
            if (fatalQuarantine) return
            drainReleaseAcksOnActor()
            if (fatalQuarantine) return
            reconcileOriginalRecoveryOnActor()
            if (fatalQuarantine) return
            publishInPlaceMetadataOnActor()
            val newRouteRecoveryRequest = routeRecoveryRequested.getAndSet(false)
            val nativeOutputNeedsRecovery = !runtimeStopped && !physicalReopenBeforePlay &&
                environment.needsOutputRecovery()
            if ((newRouteRecoveryRequest || nativeOutputNeedsRecovery) &&
                (stopping || runtimeStopped) && !closing
            ) {
                // A Samsung sink callback/error can arrive while Recents STOP is still waiting for
                // unassignment, or just after it joined the runtime. Do not consume and forget that
                // topology change: defer its close-first reopen until the next desired owner.
                routeOutputNeedsReopen = true
            }
            deferredDesired?.takeUnless { ownerGate.isLatest(it.selection.token) }?.let {
                // Route/runtime recovery is allowed to follow a real Select-after-STOP, but never
                // a stale control snapshot whose owner was invalidated by completed STOP.
                deferredDesired = null
            }
            if (!hasPhysicalPlayDemand() && routeOutputNeedsReopen &&
                routeRecoveryTransaction?.phase != RouteRecoveryPhase.RETIRING_OUTPUT &&
                deferredDesired != null && selected == null &&
                routeRecoveryTransaction?.target == null &&
                sequenceOf(active, candidate, cacheBuilder).filterNotNull().all {
                    it.bank in releases
                } &&
                (physicalReopenBeforePlay || routeRecoveryTransaction != null ||
                    !environment.needsOutputRecovery())
            ) {
                // Paused ownership does not require a sink, including when first initialization
                // has already started the empty runtime after a cold STOP. Retain the exact bank
                // unassignments; the later Play owns physical route recovery.
                environment.setOutputSessionSuspended(true)
                physicalReopenBeforePlay = true
                routeOutputNeedsReopen = false
                routeRecoveryTransaction?.phase = RouteRecoveryPhase.REBUILDING_RUNTIME
            }
            val deferredOwnerNeedsOutput = routeOutputNeedsReopen && deferredDesired != null
            val deferredOwnerNeedsRuntime = runtimeStopped && deferredDesired != null
            if (((newRouteRecoveryRequest && !runtimeStopped) || nativeOutputNeedsRecovery ||
                    routeRecoveryTransaction != null ||
                    deferredOwnerNeedsOutput || deferredOwnerNeedsRuntime) && !closing && !stopping
            ) {
                if (routeRecoveryTransaction?.phase == RouteRecoveryPhase.RETIRING_OUTPUT ||
                    (hasPhysicalPlayDemand() && outputPreparation == NativeOutputPreparationState.READY) ||
                    (!hasPhysicalPlayDemand() && routeRecoveryTransaction != null) ||
                    (!routeOutputNeedsReopen && routeRecoveryTransaction?.phase != RouteRecoveryPhase.REOPENING_OUTPUT))
                    recoverRouteOnActor()
            }
            if (fatalQuarantine) return
            if (!stopping && !closing) {
                deferredDesired?.let { desired ->
                    if (canProcessDeferredDesired()) {
                        deferredDesired = null
                        processDesiredOnActor(desired)
                    }
                }
                if (fatalQuarantine) return
                ensureLikelyNextPrewarmOnActor()
                if (fatalQuarantine) return
            }
            // Loop polling may finish cancellation after STOP returned before retiring its banks.
            if (stopping) continueStopRetirement()
            finishStopOrCloseIfReady()
            if (fatalQuarantine) return
            reconcileOutputQuiescenceOnActor()
            pruneTokenMetadataOnActor()
            pollDelay = nextPollDelayMs()
        } catch (failure: Throwable) {
            enterFatalQuarantine(
                "Native engine actor failed: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        } finally {
            publishControllerDiagnosticsOnActor(pollDelay)
        }
        pollDelay?.let(::schedulePoll)
    }

    private fun hasPhysicalPlayDemand(): Boolean = !stopping && !closing && !focusSuspended &&
        desiredStore.latest()?.let { ownerGate.isLatest(it.selection.token) && it.playWhenReady } == true

    private fun reportOutputUnavailable() {
        val token = ownerGate.latest()?.token ?: return
        if (outputFailureToken == token) return
        outputFailureToken = token
        listener.onEvent(NativeEngineControllerEvent.OutputAvailabilityChanged(token, false,
            "Playback output is unavailable; retrying while playback is requested"))
    }

    private fun initializeOnActor(): Boolean {
        if (!environment.startRuntime()) return false
        runtimeIncarnation = increment(runtimeIncarnation, "native runtime incarnation")
        initialized = true
        runtimeStopped = false
        // Output is already empty/paused at its construction generation. Publishing an equal
        // unselected epoch would ask native to perform a non-increasing hard flush.
        return true
    }

    private fun acceptDesiredOnActor(desired: EngineDesiredState) {
        if (!ownerGate.isLatest(desired.selection.token)) return
        if (desired.playWhenReady && physicalReopenBeforePlay) routeOutputNeedsReopen = true
        if (actorExplicitLoopState?.token != desired.selection.token) {
            actorExplicitLoopState = EngineExplicitLoopState(desired.selection.token, 0L, null)
            suppressedLoopToken = null
            suppressedLoopRevision = -1L
        }
        if (stopping || closing || runtimeStopped || releases.size == 2 ||
            manualSelectionInvalidation.get() != null ||
            routeRecoveryTransaction != null || routeOutputNeedsReopen
        ) {
            deferredDesired = desired
            routeRecoveryTransaction?.let { recovery ->
                val targetToken = recovery.target?.selection?.token
                val ownerChanged = targetToken != null && targetToken != desired.selection.token
                val seekChanged = targetToken != null && !ownerChanged &&
                    recovery.target?.desired?.seekRevision != desired.seekRevision
                if (targetToken == null) {
                    // No decoder graph exists yet (ordinary restart after Recents STOP). Conflate
                    // the newest cursor without forcing a healthy paused output to reopen.
                    recovery.positionMs = desired.requestedPositionMs
                } else if (ownerChanged || seekChanged) {
                    // A new owner or Seek invalidates exact decoder generations. Ordinary
                    // Pause/tempo/key/mix changes stay conflated and apply after the cached route
                    // plan succeeds; letting every button force another reopen can starve recovery.
                    recovery.target = selected?.takeIf {
                        it.assigned && it.bank !in releases &&
                            it.selection?.token == desired.selection.token
                    }
                    recovery.positionMs = desired.requestedPositionMs
                    if (recovery.phase != RouteRecoveryPhase.RETIRING_OUTPUT)
                        recovery.phase = RouteRecoveryPhase.REOPENING_OUTPUT
                    recovery.seekPlans = emptyList()
                    routeOutputNeedsReopen = true
                }
            }
            return
        }
        processDesiredOnActor(desired)
    }

    private fun handleExplicitLoopOnActor(state: EngineExplicitLoopState) {
        if (!ownerGate.isLatest(state.token)) return
        val current = actorExplicitLoopState
        if (current == null || current.token != state.token || state.revision <= current.revision) {
            return
        }
        actorExplicitLoopState = state
        if (suppressedLoopToken == state.token && suppressedLoopRevision < state.revision) {
            suppressedLoopToken = null
            suppressedLoopRevision = -1L
        }
        selected?.takeIf {
            it.assigned && it.bank !in releases && it.selection?.token == state.token
        }?.let {
            // One command publication atomically removes any queued automatic successor before
            // the native direct-loop protocol may claim the shared inactive output ring. The
            // accepted publication calls reconcileManagedLoopOnActor for this exact owner.
            publishSnapshotOnActor()
        }
    }

    /**
     * Explicit/random selection is a discontinuous owner boundary, unlike automatic successor
     * promotion. The public call already closed the callback render gate; this actor step now
     * retires every outgoing graph before opening the latest request. A failed target therefore
     * remains silent and can never resurrect the previous owner.
     */
    private fun invalidateOutgoingForManualSelectionOnActor(
        invalidation: ManualSelectionInvalidation
    ) {
        generationCursor = maxOf(generationCursor, invalidation.silenceGeneration)
        if (!cancelPreparationOnActor(abortNative = true)) {
            // Retirement is asynchronous; never lose this boundary or overwrite a newer selector.
            manualSelectionInvalidation.compareAndSet(null, invalidation)
            return
        }
        // A joined runtime has no live protocol left to retire before recreation.
        if (runtimeStopped) resetManagedLoopRuntimeAdoption()
        managedLoopIntent?.takeIf { it.enabled }?.let { outgoing ->
            loopRevision = increment(loopRevision, "managed loop command revision")
            val disabled = NativeRuntimeManagedLoopIntent.disabled(loopRevision, outgoing.ownership)
            check(environment.publishManagedLoopIntent(disabled) == NativeRuntimePublishStatus.ACCEPTED) {
                "Could not retire the outgoing managed-loop intent"
            }
            managedLoopIntent = disabled
        }
        managedLoopStableIdentity = null
        loopPreparation.clearRequests()
        repeatOneEffective = false
        pendingPublication = null
        pendingSeekOwnership = null
        setPublicSelectionOnActor(null)
        publicOwnership = null
        selected = null
        publishedStemReadiness = null

        val retainedPrewarm = candidate?.takeIf { record ->
            record.reservation?.token == invalidation.token && record.bank !in releases
        }
        sequenceOf(active, candidate, cacheBuilder).filterNotNull().distinct().forEach { record ->
            if (record !== retainedPrewarm && record.bank !in releases) beginRelease(record)
        }
        retainedPrewarm?.automaticSuccessorRevision = 0L
    }

    private fun processDesiredOnActor(desired: EngineDesiredState) {
        if (!ownerGate.isLatest(desired.selection.token)) return
        if (runtimeStopped) {
            // A desired command should normally be deferred by acceptDesiredOnActor. Keep this
            // fallback fail-silent as well: a one-shot runtime start after Recents STOP is no more
            // fatal than the same transient start failure during a route transaction.
            deferredDesired = desired
            return
        }

        // A same-token internal replacement can be the selected physical graph while the older
        // public graph remains [active] until callback proof. Once Seek diverges their revisions,
        // every later desired tuple must follow that selected graph. Before a Seek, retaining the
        // active-first path lets stem-package supersession update both graphs as one transaction.
        val selectedReplacement = candidate?.takeIf {
            it.replacementPurpose != null &&
                (it === selected ||
                    it.replacementPurpose == InternalReplacementPurpose.EARLY_SEEK) &&
                it.selection?.token == desired.selection.token && it.bank !in releases
        }
        val activeForToken = active?.takeIf {
            it.selection?.token == desired.selection.token && it.bank !in releases
        }
        val existing = selectedReplacement?.takeIf {
            desired.seekRevision != activeForToken?.lastSeekRevision
        } ?: sequenceOf(active, candidate).filterNotNull().firstOrNull {
            it.bank !in releases && it.selection?.token == desired.selection.token
        }
        if (existing != null) {
            applyDesiredToRecord(existing, desired)
            return
        }

        val prewarmed = candidate?.takeIf {
            it.identity == desired.selection.audioIdentity() &&
                it.reservation?.token == desired.selection.token &&
                it.bank !in releases
        }
        if (prewarmed != null) {
            prewarmed.reservation = null
            prewarmed.selection = desired.selection
            setRecordDesired(prewarmed, desired)
            prewarmed.lastSeekRevision = desired.seekRevision
            prewarmed.frameZeroCacheBuilderEligible =
                desired.requestedPositionMs == 0L
            if (desired.requestedPositionMs != prewarmed.identity.playbackStartMs) {
                restartExistingBank(prewarmed, desired)
                return
            }
            if (active == null || prewarmed.originalPrimed ||
                prewarmed.decoders[0]?.head()?.let(::originalHeadReady) == true
            ) {
                assignAndSelect(
                    prewarmed,
                    msToFrame(desired.requestedPositionMs)
                )
            }
            return
        }

        val sameActiveAudio = active?.takeIf {
            it.identity == desired.selection.audioIdentity() && it.bank !in releases
        }
        if (sameActiveAudio != null) {
            restartExistingBank(sameActiveAudio, desired)
            return
        }

        candidate?.let { stale ->
            if (stale.bank !in releases) beginRelease(stale)
            deferredDesired = desired
            return
        }
        if (releases.size == 2 || freeBank() == null) {
            deferredDesired = desired
            return
        }
        openSelectedCandidate(desired)
    }

    private fun openSelectedCandidate(desired: EngineDesiredState) {
        if (environment.decoderLeaseSnapshot().candidateDecoderCount != 0) {
            deferredDesired = desired
            return
        }
        val bank = requireNotNull(freeBank())
        val ownership = NativeRuntimeOwnership(
            desired.selection.token.value,
            allocateGeneration()
        )
        val record = BankRecord(
            bank = bank,
            identity = desired.selection.audioIdentity(),
            ownership = ownership,
            reservation = null,
            selection = desired.selection,
            desired = desired,
            lastDesiredRevision = desired.revision,
            sourceIntentRevision = desired.sourceIntentRevision,
            stemIntentRevision = desired.stemIntentRevision,
            lastSeekRevision = desired.seekRevision,
            frameZeroCacheBuilderEligible = desired.requestedPositionMs == 0L
        )
        try {
            record.decoders[NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX] = openDecoder(
                record,
                NativeSongBankRole.CANDIDATE,
                NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX,
                laneId = "original",
                location = desired.selection.originalLocation,
                // Cover source-frame quantization; native trims this prefix at the requested cursor.
                initialPositionMs = (desired.requestedPositionMs - 1L).coerceAtLeast(0L)
            )
        } catch (failure: Throwable) {
            settleFailedSelection(
                desired,
                failure.message?.takeIf(String::isNotBlank)
                    ?: "Original decoder failed to open"
            )
            return
        }
        candidate = record
        if (active == null) {
            assignAndSelect(record, msToFrame(desired.requestedPositionMs))
        }
    }

    /**
     * Binds a requested local start to the exact selected native generation. The actor's normal
     * transition poll is shortened to the remaining interval, so expiry cannot drift beyond the
     * fixed bound and no second scheduler or retry lane is introduced.
     */
    private fun reconcileLocalStartSettlementOnActor() {
        val request = localStartRequest.get() ?: run {
            localStartSettlement = null
            return
        }
        val desired = desiredStore.latest()?.takeIf {
            it.selection.token == request.token && it.playWhenReady &&
                ownerGate.isLatest(request.token)
        } ?: run {
            clearLocalStartRequest(request.token)
            localStartSettlement = null
            return
        }
        if (publicSelectionSnapshot.get()?.token == request.token) {
            clearLocalStartRequest(request.token)
            localStartSettlement = null
            return
        }
        val record = selected?.takeIf {
            it.assigned && it.bank !in releases &&
                it.selection?.token == request.token
        } ?: run {
            // Release/route recovery can temporarily leave the latest request without a bank. Its
            // original timestamp is retained and will expire immediately once an exact generation
            // exists; the bounded decoder and route teardown watchdogs remain authoritative first.
            localStartSettlement = null
            return
        }
        val deadlineAtMs = if (request.requestedAtMs >
            Long.MAX_VALUE - LOCAL_START_SETTLEMENT_TIMEOUT_MS
        ) {
            Long.MAX_VALUE
        } else {
            request.requestedAtMs + LOCAL_START_SETTLEMENT_TIMEOUT_MS
        }
        val settlement = LocalStartSettlement(record.ownership, deadlineAtMs)
        localStartSettlement = settlement
        if (scheduler.monotonicTimeMs() < deadlineAtMs) return

        // Revalidate every mutable authority immediately before teardown. A stale timer can never
        // fail a newer token or an in-flight generation rebase.
        val exactRequest = localStartRequest.get()
        val exactDesired = desiredStore.latest()
        if (exactRequest != request || exactDesired != desired || selected !== record ||
            record.ownership != settlement.ownership || !ownerGate.isLatest(request.token)
        ) return
        clearLocalStartRequest(request.token)
        localStartSettlement = null
        if (pendingPublication === record) pendingPublication = null
        if (pendingSeekOwnership == record.ownership) pendingSeekOwnership = null
        beginRelease(record)
        if (!fatalQuarantine) {
            settleFailedSelection(desired, LOCAL_START_SETTLEMENT_FAILURE)
        }
    }

    private fun completeLocalStartSettlementOnActor(ownership: NativeRuntimeOwnership) {
        localStartSettlement = localStartSettlement?.takeUnless { it.ownership == ownership }
        localStartRequest.get()?.takeIf {
            it.token.value == ownership.selectionToken
        }?.let { localStartRequest.compareAndSet(it, null) }
    }

    private fun clearLocalStartRequest(token: EngineSelectionToken) {
        while (true) {
            val current = localStartRequest.get()?.takeIf { it.token == token } ?: return
            if (localStartRequest.compareAndSet(current, null)) return
        }
    }

    private fun settleFailedSelection(
        desired: EngineDesiredState,
        cause: String,
        terminatedOwner: NativeRuntimeOwnership? = null
    ) {
        val failedToken = desired.selection.token
        clearLocalStartRequest(failedToken)
        localStartSettlement = localStartSettlement?.takeUnless {
            it.ownership.selectionToken == failedToken.value
        }
        // A concurrent C request supersedes this B failure. Conditional clears refuse to overwrite
        // C; a genuine latest failure deliberately leaves no owner instead of reviving outgoing A.
        val ownerCleared = ownerGate.restoreAfterFailedSelection(failedToken, committed = null)
        val desiredCleared = ownerCleared &&
            desiredStore.restoreAfterFailedSelection(failedToken, committed = null)
        if (ownerCleared && desiredCleared) {
            deferredDesired = deferredDesired?.takeUnless {
                it.selection.token == failedToken
            }
            audibleTransportIntent = audibleTransportIntent?.takeUnless { it.token == failedToken }
            selected = null
        }
        metadataByToken.remove(failedToken.value)
        // A seek/recovery can fail before its new generation is published. Terminate the retained
        // public owner of this logical selection, never a distinct healthy outgoing selection.
        val deadPublicOwner = terminatedOwner?.let {
            publicOwnership?.takeIf { owner -> owner.selectionToken == failedToken.value }
        }
        if (deadPublicOwner != null) {
            publicOwnership = null
            setPublicSelectionOnActor(null)
            pendingPublication = pendingPublication?.takeUnless {
                it.ownership.selectionToken == failedToken.value
            }
            pendingSeekOwnership = pendingSeekOwnership?.takeUnless {
                it.selectionToken == failedToken.value
            }
            sequenceOf(active, candidate, cacheBuilder).filterNotNull().distinct().filter {
                it.ownership.selectionToken == failedToken.value && it.bank !in releases
            }.forEach(::beginRelease)
        }
        listener.onEvent(if (deadPublicOwner == null) {
            NativeEngineControllerEvent.SelectionFailed(failedToken, cause)
        } else {
            NativeEngineControllerEvent.OwnerTerminated(deadPublicOwner, cause)
        })
    }

    private fun assignAndSelect(
        record: BankRecord,
        firstTimelineFrame: Long,
        forceRebase: Boolean = false
    ) {
        val firstTimelineMs = frameToMs(firstTimelineFrame)
        val acceptedOutputHighWater = maxOf(
            acceptedCommandOutputGeneration,
            environment.outputGeneration
        )
        val assigned = bankIntents[record.bank.nativeCode]
        val anchorChanged = assigned.assigned && assigned.ownership == record.ownership &&
            (assigned.firstTimelineFrame != firstTimelineFrame || assigned.outputClockStartFrame != 0L)
        val rebase = if (forceRebase || anchorChanged || record.automaticSuccessorRevision != 0L ||
            record.ownership.generation <= acceptedOutputHighWater
        ) {
            prepareSeek(record, firstTimelineMs)
        } else {
            null
        }
        record.automaticSuccessorRevision = 0L
        if (rebase != null) {
            record.ownership = NativeRuntimeOwnership(
                record.ownership.selectionToken,
                rebase.ownership.generation
            )
            record.ingressEpoch = rebase.ownership.generation
        }
        record.assigned = true
        record.intentRevision = nextBankRevision(record.bank)
        bankIntents[record.bank.nativeCode] = assignedBankIntent(
            record = record,
            revision = record.intentRevision,
            // Decoder preroll may use milliseconds; source replacement keeps the exact fenced sink cursor.
            firstTimelineFrame = firstTimelineFrame,
            outputClockStartFrame = 0L
        )
        selected = record
        pendingPublication = record
        pendingSeekOwnership = null
        suppressedLoopToken = null
        publishSnapshotOnActor()
        if (rebase != null && !authorizeSeek(rebase.copy(ownership = record.ownership))) {
            record.desired?.let { handleSeekFailStop(record, it) }
            return
        }
        publishBeatGridIfPresent(record)
    }

    private fun restartExistingBank(record: BankRecord, desired: EngineDesiredState) {
        val seek = prepareSeek(record, desired.requestedPositionMs)
        record.selection = desired.selection
        setRecordDesired(record, desired)
        record.lastSeekRevision = desired.seekRevision
        record.ownership = NativeRuntimeOwnership(
            desired.selection.token.value,
            seek.ownership.generation
        )
        record.ingressEpoch = seek.ownership.generation
        val adjusted = seek.copy(ownership = record.ownership)
        record.assigned = true
        record.intentRevision = nextBankRevision(record.bank)
        bankIntents[record.bank.nativeCode] = assignedBankIntent(
            record,
            record.intentRevision,
            msToFrame(desired.requestedPositionMs),
            0L
        )
        selected = record
        pendingPublication = record
        pendingSeekOwnership = null
        publishSnapshotOnActor()
        if (!authorizeSeek(adjusted)) handleSeekFailStop(record, desired)
        publishBeatGridIfPresent(record)
    }

    private fun applyDesiredToRecord(record: BankRecord, desired: EngineDesiredState) {
        if (desired.revision <= record.lastDesiredRevision) return
        val seekChanged = desired.seekRevision != record.lastSeekRevision
        if (seekChanged && record === active && shouldStageEarlySeekReplacement(record, desired)) {
            armInPlaceSeekBoundary(record)
            if (!cancelPreparationOnActor(abortNative = true)) {
                deferredDesired = desired
                return
            }
            loopPreparation.clearRefill()
            val staleCandidate = candidate
            if (staleCandidate != null) {
                if (staleCandidate.bank !in releases) beginRelease(staleCandidate)
                deferredDesired = desired
                return
            }
            if (freeBank() == null) {
                deferredDesired = desired
                return
            }
            if (openEarlySeekReplacement(record, desired)) return
            // Cache building is an optimization. If the spare Original cannot open, preserve the
            // user's seek through the established in-place path and abandon this build attempt.
            record.frameZeroCacheBuilderEligible = false
        }
        setRecordDesired(record, desired)
        // A priming assignment drains SRC history; only coverage-gated adoption may select it.
        if (!record.assigned || (record.replacementPurpose == InternalReplacementPurpose.EARLY_SEEK &&
                record !== selected)) {
            if (seekChanged) {
                val seek = prepareSeek(record, desired.requestedPositionMs)
                record.ownership = seek.ownership
                record.ingressEpoch = seek.ownership.generation
                record.lastSeekRevision = desired.seekRevision
                if (record.assigned) {
                    record.intentRevision = nextBankRevision(record.bank)
                    bankIntents[record.bank.nativeCode] = assignedBankIntent(
                        record, record.intentRevision, msToFrame(desired.requestedPositionMs), 0L
                    )
                    publishSnapshotOnActor()
                }
                if (!authorizeSeek(seek)) handleSeekFailStop(record, desired)
                record.originalPrimed = false
            }
            return
        }
        val stagedSameTokenReplacement = candidate?.takeIf {
            it.replacementPurpose != null && selected === it &&
                it.selection?.token == desired.selection.token && it.bank !in releases
        }
        if (stagedSameTokenReplacement === record) {
            // Stem-package supersession is still reconciled against the outgoing physical package
            // until the clean Original replacement commits. The replacement remains the sole
            // desired/seek target; the outgoing decoder graph is deliberately left untouched.
            active?.takeIf {
                it !== record && it.selection?.token == desired.selection.token &&
                    it.bank !in releases
            }?.let { outgoing ->
                reconcileStemPackageOnActor(outgoing, desired)
            }
        }
        if (!seekChanged && stagedSameTokenReplacement != null && record === active) {
            // A package revision/control update must not retarget native selection back to the
            // outgoing bank while its callback-proven Original fallback is already staged.
            setRecordDesired(stagedSameTokenReplacement, desired)
            refreshAssignedContentIdentity(stagedSameTokenReplacement)
            reconcileStemPackageOnActor(record, desired)
            selected = stagedSameTokenReplacement
            publishSnapshotOnActor()
            publishBeatGridIfPresent(stagedSameTokenReplacement)
            return
        }
        if (seekChanged) {
            armInPlaceSeekBoundary(record)
            val seek = prepareSeek(record, desired.requestedPositionMs)
            record.ownership = seek.ownership
            record.ingressEpoch = seek.ownership.generation
            record.lastSeekRevision = desired.seekRevision
            record.intentRevision = nextBankRevision(record.bank)
            bankIntents[record.bank.nativeCode] = assignedBankIntent(
                record,
                record.intentRevision,
                msToFrame(desired.requestedPositionMs),
                0L
            )
            selected = record
            pendingSeekOwnership = record.ownership
            publishSnapshotOnActor()
            if (!authorizeSeek(seek)) handleSeekFailStop(record, desired)
        } else {
            refreshAssignedContentIdentity(record)
            selected = record
            publishSnapshotOnActor()
        }
        publishBeatGridIfPresent(record)
        if (record === active && record.bank !in releases) {
            reconcileStemPackageOnActor(record, desired)
        }
    }

    private fun shouldStageEarlySeekReplacement(
        record: BankRecord,
        desired: EngineDesiredState
    ): Boolean = cacheBuilder == null && record.frameZeroCacheBuilderEligible &&
        record.replacementPurpose == null &&
        !environment.isCurrentSongCacheLaneMaskSealed(
            record.ownership,
            cachePreservationLaneMask(record, desired)
        )

    private fun cachePreservationLaneMask(record: BankRecord, desired: EngineDesiredState): Int =
        cacheAdmission.preservationMask(record, bankIntents[record.bank.nativeCode],
            musicLaneMaskForStemCount(stemPackageRequest(record, desired)?.canonicalLanes?.size ?: 0))

    private fun openEarlySeekReplacement(
        builder: BankRecord,
        desired: EngineDesiredState
    ): Boolean {
        val bank = requireNotNull(freeBank())
        val replacement = BankRecord(
            bank = bank,
            identity = builder.identity,
            ownership = NativeRuntimeOwnership(
                desired.selection.token.value,
                allocateGeneration()
            ),
            reservation = null,
            selection = desired.selection,
            desired = desired,
            lastDesiredRevision = desired.revision,
            sourceIntentRevision = desired.sourceIntentRevision,
            stemIntentRevision = desired.stemIntentRevision,
            lastSeekRevision = desired.seekRevision,
            stemAnchorFrame = msToFrame(desired.requestedPositionMs),
            replacementPurpose = InternalReplacementPurpose.EARLY_SEEK
        )
        try {
            replacement.decoders[NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX] = openDecoder(
                replacement,
                NativeSongBankRole.CANDIDATE,
                NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX,
                "original",
                desired.selection.originalLocation,
                desired.requestedPositionMs
            )
        } catch (failure: Throwable) {
            emitFailure(
                "early-seek-cache-builder",
                (failure.message?.takeIf(String::isNotBlank)
                    ?: "Seek replacement Original failed to open") +
                    "; falling back to an in-place seek"
            )
            return false
        }
        candidate = replacement
        // Let native SRC drain retained history before coverage can authorize selecting this bank.
        replacement.assigned = true
        replacement.intentRevision = nextBankRevision(bank)
        bankIntents[bank.nativeCode] = assignedBankIntent(replacement, replacement.intentRevision,
            msToFrame(desired.requestedPositionMs), 0L)
        publishSnapshotOnActor()
        return true
    }

    private fun setRecordDesired(record: BankRecord, desired: EngineDesiredState) {
        if (desired.revision < record.lastDesiredRevision) {
            // The outgoing public graph can still run low-frequency package reconciliation while a
            // same-token replacement owns a newer Seek/control revision. Never let that older
            // graph roll the selected replacement's cursor or transport state backward.
            return
        }
        val previous = record.desired
        val physicalLanes = record.stemPackage?.canonicalLanes
        if (physicalLanes != null && previous != null &&
            canonicalEngineStemLanes(previous.stemLanes) == physicalLanes
        ) record.physicalStemControls = physicalLanes.associate { lane ->
            lane.id to (previous.stemControls[lane.id] ?: EngineStemControl())
        }
        if (physicalLanes != null && canonicalEngineStemLanes(desired.stemLanes) == physicalLanes) {
            record.physicalStemControls = physicalLanes.associate { lane ->
                lane.id to (desired.stemControls[lane.id] ?: EngineStemControl())
            }
        }
        if (record.stemIntentRevision == 0L ||
            previous?.selection?.token != desired.selection.token
        ) {
            record.stemIntentRevision = desired.stemIntentRevision
        } else if (desired.stemIntentRevision > record.stemIntentRevision) {
            // Package identity/retry has its own transaction revision. Source, mixer, tempo, key,
            // loop, and metronome updates must not manufacture a new physical stem request.
            record.stemIntentRevision = desired.stemIntentRevision
        }
        record.desired = desired
        record.lastDesiredRevision = desired.revision
        // Source edge provenance is captured before the mailbox conflates intermediate snapshots.
        // Inferring it from [previous] here loses ORIGINAL->STEMS->ORIGINAL bursts when the actor
        // observes only the final ORIGINAL snapshot, leaving public state opposite audible native.
        check(desired.sourceIntentRevision >= record.sourceIntentRevision ||
            previous?.selection?.token != desired.selection.token
        ) { "Source intent revision moved backwards for one selection owner" }
        record.sourceIntentRevision = desired.sourceIntentRevision
    }

    /** Decoder future blocks park first; native expected generation publishes before authorization. */
    private fun prepareSeek(record: BankRecord, positionMs: Long): SeekPlan {
        check(record.decoders.isNotEmpty())
        if (loopPreparation.ownsRefill(record)) loopPreparation.clearRefill()
        val targetFrame = msToFrame(positionMs)
        record.stemAnchorFrame = targetFrame
        record.frameZeroCacheBuilderEligible = targetFrame == 0L
        val targetGeneration = allocateGeneration()
        val tickets = record.decoders.mapValues { (_, decoder) ->
            // Decoder source frames round up, while SRC timeline tags round down. Retain the
            // same real prefix as a cold open; native still seeks to the exact requested frame.
            decoder.requestSeek(msToUs((positionMs - 1L).coerceAtLeast(0L)), targetGeneration)
        }
        val generations = tickets.values.map { it.nativeGeneration }.toSet()
        check(generations.size == 1) {
            "All configured lanes must map one seek to the same whole-song generation"
        }
        val generation = generations.single()
        check(generation == targetGeneration)
        check(generation > record.ingressEpoch) {
            "A seek must advance the decoder ingress epoch"
        }
        generationCursor = maxOf(generationCursor, generation)
        // Source readiness and audibility acknowledgements are physical-generation evidence.
        // A seek/reopen must earn both again even when logical source/package intent is unchanged.
        record.sourceActivation.retirePhysicalEvidence()
        return SeekPlan(
            record = record,
            tickets = tickets,
            ownership = NativeRuntimeOwnership(record.ownership.selectionToken, generation)
        )
    }

    private fun authorizeSeek(plan: SeekPlan): Boolean {
        val batch = plan.tickets.mapValues { (lane, ticket) ->
            requireNotNull(plan.record.decoders[lane]) to ticket
        }
        return when (generationCoordinator.commit(batch)) {
            NativeEngineWholeBankAdvanceStatus.COMMITTED -> {
                environment.notifyIngressAvailable()
                true
            }
            NativeEngineWholeBankAdvanceStatus.FAIL_STOP_REBUILD_REQUIRED -> false
        }
    }

    private fun handleSeekFailStop(record: BankRecord, desired: EngineDesiredState) {
        emitFailure(
            "seek-generation",
            "A lane rejected the whole-bank generation; rebuilding the partially advanced decoder bank"
        )
        pendingSeekOwnership = null
        if (record === active && candidate == null && freeBank() != null) {
            // The committed output ring remains audible while a clean Original is prepared in the
            // second bank. The broken bank is unassigned only after replacement callback evidence.
            openSelectedCandidate(desired)
        } else {
            beginRelease(record)
            deferredDesired = desired
        }
    }

    private fun handleLifecycleOnActor(action: EngineLifecycleAction) {
        when (action) {
            EngineLifecycleAction.SUSPEND_FOR_AUDIO_FOCUS -> {
                focusSuspended = true
                environment.setOutputSessionSuspended(true)
                if (!runtimeStopped) {
                    publishSnapshotOnActor()
                    // Focus loss requires physical retirement now, independently of the idle grace.
                    outputWarmPauseDeadlineMs = scheduler.monotonicTimeMs()
                }
            }
            EngineLifecycleAction.RESUME_AFTER_AUDIO_FOCUS -> {
                focusSuspended = false
                environment.setOutputSessionSuspended(false)
                if (!runtimeStopped) publishSnapshotOnActor()
            }
            EngineLifecycleAction.CLOSE -> beginStop(closeAfter = true)
        }
    }

    private fun handleAudibleTransportOnActor(entry: EngineMailboxEntry.AudibleTransport) {
        if (!ownerGate.isLatest(entry.token)) return
        audibleTransportIntent = AudibleTransportIntent(entry.token, entry.playWhenReady)
        if (!runtimeStopped && selected != null) publishSnapshotOnActor()
    }

    private fun beginStop(closeAfter: Boolean, request: NativeEngineStopRequest? = null) {
        closing = closing || closeAfter
        stopping = true
        localStartRequest.set(null)
        localStartSettlement = null
        // Audio-focus suspension belongs to the outgoing app/session lifetime. Administrative
        // STOP abandons focus, so carrying this bit into a later same-process owner can leave its
        // autoplay permanently inaudible when a synchronous focus grant emits no GAIN callback.
        focusSuspended = false
        if (!closeAfter) activeStop = checkNotNull(request)
        deferredDesired = null
        continueStopRetirement()
    }

    private fun continueStopRetirement() {
        if (!cancelPreparationOnActor(abortNative = true)) {
            if (!fatalQuarantine && loopPreparation.retirementTimeoutReported) {
                enterFatalQuarantine(
                    "A loop-head MediaCodec worker exceeded the teardown watchdog during STOP",
                    silenceAlreadyEngaged = activeStop != null
                )
            }
            return
        }
        loopPreparation.clearRefill()
        pendingPublication = null
        pendingSeekOwnership = null
        setPublicSelectionOnActor(null)
        publicOwnership = null
        selected = null
        publishedStemReadiness = null
        routeAwaitingOwnership = null
        routeRecoveryTransaction = null
        if (!runtimeStopped) {
            active?.takeIf { it.bank !in releases }?.let(::beginRelease)
            candidate?.takeIf { it.bank !in releases }?.let(::beginRelease)
            cacheBuilder?.takeIf { it.bank !in releases }?.let(::beginRelease)
        } else if (!releaseStoppedBanksForAdministrativeStop()) {
            return
        }
    }

    /**
     * A route transaction has already joined native rendering, so no callback can still consume a
     * bank. STOP may therefore cancel/join and release those decoders directly instead of waiting
     * forever for an unassignment acknowledgement from a runtime that is deliberately stopped.
     */
    private fun releaseStoppedBanksForAdministrativeStop(): Boolean {
        check(runtimeStopped)
        val records = sequenceOf(active, candidate, cacheBuilder)
            .filterNotNull()
            .distinct()
            .toList()
        records.forEach { record ->
            record.assigned = false
            record.intentRevision = nextBankRevision(record.bank)
            bankIntents[record.bank.nativeCode] = NativeRuntimeBankIntent.unassigned(
                record.intentRevision
            )
        }
        val (joined, stuck) = cancelAndJoinDecoderGroup(
            records.flatMap { it.decoders.values }
        )
        val joinedSet = joined.toHashSet()
        records.forEach { record ->
            record.decoders.values.toList().filter(joinedSet::contains).forEach { decoder ->
                check(decoder.safeToReleaseLane())
                environment.releaseDecoder(record.bank, decoder)
                record.decoders.remove(decoder.laneIndex)
            }
        }
        if (stuck.isNotEmpty()) {
            enterFatalQuarantine(
                "${stuck.size} MediaCodec worker(s) exceeded the teardown watchdog after " +
                    "audio-route loss; their lanes were retained",
                silenceAlreadyEngaged = true
            )
            return false
        }
        if (records.isNotEmpty()) {
            // A joined runtime cannot consume an unassignment command. Retire its actual native
            // banks by replacing the runtime, retaining the same output without reopening it.
            // Recreation may start a replacement before failing; require a join on every exit.
            runtimeStopped = false
            try {
                check(environment.recreateRuntime()) {
                    "Could not retire the joined native banks during administrative STOP"
                }
            } finally {
                environment.stopRuntimeAndJoin()
                runtimeStopped = true
            }
        }
        releases.clearAfterRuntimeJoined()
        active = null
        candidate = null
        cacheBuilder = null
        return true
    }

    private fun beginRelease(record: BankRecord) {
        if (record.bank in releases) return
        record.sourceTransitionTarget?.let { environment.cancelSourceTransition(it) }
        environment.cancelSourceTransition(record.ownership)
        preparationEvidence.cancelOwnership(record.ownership, scheduler.monotonicTimeMs())
        if (loopPreparation.preparingOwnership == record.ownership) {
            cancelPreparationOnActor(abortNative = true)
        }
        if (loopPreparation.ownsRefill(record)) loopPreparation.clearRefill()
        record.assigned = false
        record.intentRevision = nextBankRevision(record.bank)
        bankIntents[record.bank.nativeCode] = NativeRuntimeBankIntent.unassigned(
            record.intentRevision
        )
        if (selected === record) {
            // The selected replacement spent a newer output epoch. Its outgoing sibling cannot
            // be selected again at its old generation; the caller's recovery owns the next seek.
            selected = null
        }
        if (selected == null && !unselectedEpochOpen) {
            unselectedOutputGeneration = allocateGeneration()
            unselectedEpochOpen = true
        }
        releases.begin(record, record.intentRevision)
        if (!runtimeStopped) publishSnapshotOnActor()
    }

    private fun drainReleaseAcksOnActor() {
        if (runtimeStopped) return
        releases.poll(scheduler.monotonicTimeMs(), preparationRetired = { record ->
            loopPreparation.preparingOwnership != record.ownership ||
                cancelPreparationOnActor(abortNative = true)
        }) { record, result ->
            when (result) {
                NativeBankDecoderRetirement.Result.Pending -> Unit
                is NativeBankDecoderRetirement.Result.TimedOut -> quarantineAfterJoinTimeout(record, result.retained)
                NativeBankDecoderRetirement.Result.Retired -> finishRelease(record)
            }
        }
    }

    private fun finishRelease(record: BankRecord) {
        val releasedCacheBuilder = cacheBuilder === record
        if (active === record) active = null
        if (candidate === record) candidate = null
        if (cacheBuilder === record) cacheBuilder = null

        // The newly audible Original held the single candidate lease until all old workers
        // were gone. Only now can it become active and open its declared stem workers.
        active?.takeIf { it.bank !in releases }?.let { promoted ->
            promoted.decoders[0]?.let { original ->
                check(original.promoteCandidateLeaseToActive()) {
                    "Candidate Original could not acquire the active lane lease"
                }
            }
            promoted.desired?.let {
                if (!releasedCacheBuilder || !nextOwnerNeedsSpareBank(promoted)) {
                    reconcileStemPackageOnActor(
                        promoted,
                        it,
                        completedCacheBuilder = record.takeIf { releasedCacheBuilder }
                    )
                }
                if (releasedCacheBuilder) reconcileManagedLoopOnActor(promoted, it)
            }
        }
        check(environment.decoderLeaseSnapshot().totalDecoderCount <=
            NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS)
    }

    /** Cancels the whole decoder group first, then spends one bounded deadline joining it. */
    private fun cancelAndJoinDecoderGroup(
        decoders: Collection<NativeEngineDecoder>
    ): Pair<List<NativeEngineDecoder>, List<NativeEngineDecoder>> {
        val pending = decoders.toList()
        pending.forEach(NativeEngineDecoder::requestCancel)
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(NATIVE_DECODER_JOIN_WATCHDOG_MS)
        val joined = ArrayList<NativeEngineDecoder>(pending.size)
        val stuck = ArrayList<NativeEngineDecoder>()
        pending.forEach { decoder ->
            val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            when (decoder.awaitJoin(remainingNanos)) {
                NativeEngineDecoderJoinStatus.JOINED -> joined += decoder
                NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED -> stuck += decoder
            }
        }
        return joined to stuck
    }

    private fun quarantineAfterJoinTimeout(
        record: BankRecord,
        stuck: List<NativeEngineDecoder>
    ) {
        enterFatalQuarantine(
            "${stuck.size} MediaCodec worker(s) exceeded the teardown watchdog in " +
                "${record.bank}; output was silenced and their lanes/leases were retained"
        )
    }

    private fun pollRuntimeOnActor() {
        pollDecoderFailuresOnActor()
        if (fatalQuarantine) return
        val diagnostics = environment.controlProgress()
        finishFailedStemCleanupOnActor(diagnostics)
        if (fatalQuarantine) return
        val readinessPending = pendingStemPreparation() || pendingOwnerReadiness() || pendingCacheReadiness()
        if (controlProgressObserver.needsReadiness(diagnostics, scheduler.monotonicTimeMs(), readinessPending,
                selected?.ownership, selected?.ingressEpoch ?: 0L)) {
            controlProgressObserver.observe(environment.renderReadinessDiagnostics(), scheduler.monotonicTimeMs())
        }
        latestNativeRenderReadiness?.let { readiness ->
            val record = selected?.takeIf { it.ownership == readiness.ownership &&
                it.bank !in releases } ?: return@let
            val failed = readiness.lanes.firstOrNull {
                it.ingressFailureGeneration != 0L &&
                    it.ingressFailureGeneration == record.ingressEpoch &&
                    (it.laneIndex == NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX ||
                        (!record.stemCleanupRequired && record.stemPackage != null))
            } ?: return@let
            val cause = "Native ingress failed for lane ${failed.laneIndex}"
            if (failed.laneIndex != NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX) {
                val key = nativeTerminalEventIdentity("native:${record.ownership}:${record.ingressEpoch}:${failed.laneIndex}:${record.stemPackage?.requestRevision}")
                if (rememberBounded(handledDecoderFailures, key)) {
                    record.selection?.let { selection ->
                        listener.onEvent(NativeEngineControllerEvent.DecoderFailed(
                            selection.token, record.ownership.generation, failed.laneIndex,
                            cause, recoverable = true
                        ))
                    }
                    (record.stemPackage ?: record.pendingStemPackage)?.let { request ->
                        emitStemPackageFailed(request, recoverable = true,
                            retainedPriorPackage = false, cause = cause)
                        record.lastFailedStemRequestRevision = request.requestRevision
                    }
                    record.pendingStemPackage = null
                    record.stemPackage = null
                    record.stemSignature = null
                    record.stemCleanupRequired = record.decoders.keys.any {
                        it != NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX
                    }
                    if (!detachFailedStemPackageOnActor(record)) {
                        beginRelease(record)
                        record.desired?.let { settleFailedSelection(it, cause, record.ownership) }
                    }
                }
                return
            }
            record.selection?.let { selection ->
                listener.onEvent(NativeEngineControllerEvent.DecoderFailed(
                    selection.token, record.ownership.generation, failed.laneIndex,
                    cause, recoverable = false
                ))
            }
            beginRelease(record)
            record.desired?.let { settleFailedSelection(it, cause, record.ownership) }
            return
        }
        authorizeManualSelectionSilenceBurstIfReady(diagnostics)
        commitAppliedPausedAutomaticSuccessor(diagnostics)
        diagnostics.presentation?.takeIf { controlProgressObserver.admitPresentation(it.sequence) }?.let {
            handlePresentationEvidence(it)
        }
        diagnostics.presentedPosition?.takeIf { controlProgressObserver.admitPosition(it.sequence) }?.let {
            if (it.ownership == publicOwnership) {
                publicSelection?.let { selection ->
                    listener.onEvent(
                        NativeEngineControllerEvent.Position(
                            selection.token,
                            frameToMs(it.lastTimelineFrame),
                            it.callbackSequence,
                            it.ownership
                        )
                    )
                }
            }
        }
        val pending = pendingPublication
        if (pending != null && selected === pending && !effectivePlaying(pending)) {
            val ownership = pending.ownership
            val progress = diagnostics.ownership
            if (progress.accepted == ownership && progress.committed == ownership &&
                progress.acceptedFirstFingerprintSequence > 0L
            ) {
                if (pending.replacementPurpose != null) {
                    commitInternalReplacement(pending)
                } else {
                    publishPausedInstalledOwner(pending)
                }
            }
        }
        pendingSeekOwnership?.let { expected ->
            val progress = diagnostics.ownership
            val record = selected?.takeIf { it.ownership == expected }
            val exactPresentation = diagnostics.presentation?.takeIf { evidence ->
                evidence.ownership == expected
            }
            val exactAudibleEvidence = if (record != null && exactPresentation != null) {
                record.selection?.let { selection -> ownerEvidence(selection, exactPresentation) }
            } else {
                null
            }
            val exactOwnerCommitted = progress.accepted == expected &&
                progress.committed == expected &&
                progress.acceptedFirstFingerprintSequence > 0L
            val pausedInstalled = record != null && !effectivePlaying(record) &&
                exactOwnerCommitted
            val playingPresented = record != null && effectivePlaying(record) &&
                exactOwnerCommitted && exactAudibleEvidence != null
            if (pausedInstalled || playingPresented) {
                pendingSeekOwnership = null
                publishOwnershipRebase(
                    requireNotNull(record),
                    if (playingPresented) {
                        NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED
                    } else {
                        NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED
                    },
                    exactAudibleEvidence
                )
            }
        }
        routeAwaitingOwnership?.let { expected ->
            val progress = diagnostics.ownership
            if (progress.accepted == expected && progress.committed == expected &&
                progress.acceptedFirstFingerprintSequence > 0L
            ) {
                selected?.takeIf { it.ownership == expected }?.let { record ->
                    publishOwnershipRebase(
                        record,
                        NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
                        null
                    )
                }
                routeAwaitingOwnership = null
                // A Pause/Play or focus change may have arrived while the replacement route was
                // filling. Resume from the latest intent, never the stale pre-reopen state.
                publishSnapshotOnActor()
            }
        }
        diagnostics.audibleControl?.takeIf {
            it.sequence > controlProgressObserver.audibleControlSequence
        }?.let(::handleAudibleControlEvidence)
        sequenceOf(active, candidate, cacheBuilder).filterNotNull().distinct().forEach { record ->
            val original = record.decoders[0]
            val head = original?.head()
            if (head != null && head.configured && !originalHeadReady(head) &&
                head.nativeGeneration == record.ingressEpoch &&
                head.endOfStreamTimelineFrameExclusive48k != null && record.bank !in releases
            ) {
                // The retained frame-zero builder is optional after a sought owner takes over.
                // Its empty source retires that builder, not the newer public selection.
                val failedSelection = record.desired.takeUnless { record === cacheBuilder }
                settleLikelyNextPrewarmFailure(record)
                beginRelease(record)
                failedSelection?.let { settleFailedSelection(it, "Original ended without complete playable PCM", record.ownership) }
                return@forEach
            }
            if (!record.originalPrimed && original != null &&
                originalHeadReady(requireNotNull(head))
            ) {
                record.originalPrimed = true
                preparationEvidence.finish(NativeControllerPreparationOperation.SUCCESSOR_PREWARM,
                    record.ownership.selectionToken, runtimeIncarnation, "original-primed", scheduler.monotonicTimeMs())
            }
            val stemReady = record.stemPackage != null && stemsReady(record)
            if (needsReadyStemPublication(record) && stemReady) {
                publishSnapshotOnActor()
            }
            emitStemReadyIfCovered(record)
        }
        reconcileAutomaticSuccessorOnActor()
        candidate?.takeIf { record ->
            record.selection != null && record !== selected && record.originalPrimed &&
                record.bank !in releases && ownerGate.isLatest(
                    requireNotNull(record.selection).token
                )
        }?.let { record ->
            var rebaseRecovery = false
            val firstTimelineFrame = if (record.replacementPurpose != null) {
                replacementAssignmentFrame(record) ?: run {
                    // A slow clean-Original decoder can fill its unassigned ingress behind the
                    // live cursor. It cannot decode farther until native drains that bank. Re-seek
                    // the configured decoder and stage its fresh generation; the outgoing ring
                    // remains audible until the ordinary non-empty handoff commits.
                    if (record.replacementPurpose != InternalReplacementPurpose.STEM_RECOVERY ||
                        record.assigned || pendingSeekOwnership != null
                    ) return@let
                    val outgoing = active?.takeIf {
                        it !== record && it.bank !in releases &&
                            it.selection?.token == record.selection?.token &&
                            it.identity == record.identity &&
                            it.lastSeekRevision == record.lastSeekRevision
                    } ?: return@let
                    if (effectivePlaying(outgoing) && diagnostics.presentedPosition?.ownership != outgoing.ownership)
                        return@let
                    record.sourceTransitionTarget = record.ownership
                    val continuation = environment.prepareSourceTransition(outgoing.ownership, record.ownership,
                        if (effectivePlaying(outgoing)) null else msToFrame(outgoing.desired?.requestedPositionMs
                            ?: requireNotNull(outgoing.selection).startPositionMs)) ?: return@let
                    rebaseRecovery = true
                    continuation
                }
            } else {
                msToFrame(requireNotNull(record.desired).requestedPositionMs)
            }
            assignAndSelect(record, firstTimelineFrame, forceRebase = rebaseRecovery)
        }
        active?.desired?.let { desired -> reconcileStemPackageOnActor(requireNotNull(active), desired) }
        cacheBuilder?.desired?.let { desired ->
            reconcileStemPackageOnActor(requireNotNull(cacheBuilder), desired)
        }
        pollManagedLoopOnActor()
        publishLoopAvailabilityOnActor()
        // Snapshot groups are individually coherent, but a read can cross a seek between
        // presentation and EOS. Admit paused ownership first and consume the end only once
        // its exact owner is public; a still-future seek retains its existing transition poll.
        diagnostics.audibleEnd?.takeIf { it.ownership == publicOwnership &&
            controlProgressObserver.admitEnd(it.sequence) }?.let {
            handleAudibleEnd(it)
        }
    }

    private fun publishLoopAvailabilityOnActor() {
        val selection = publicSelection
        val ownership = publicOwnership
        if (selection == null || ownership == null) {
            publishedLoopAvailabilityOwnership = null
            publishedLoopAvailable = false
            return
        }
        val exactCurrentOwner = active?.takeIf { record ->
            selected === record && record.selection == selection && record.ownership == ownership &&
                pendingPublication == null && pendingSeekOwnership == null &&
                routeAwaitingOwnership == null
        }
        val config = actorExplicitLoopState?.takeIf { it.token == selection.token }?.config
        val retry = loopRetryRequest.getAndSet(null)
        if (retry?.first == ownership && (retry.second == null || retry.second == (actorExplicitLoopState?.revision ?: 0L)) && exactCurrentOwner != null) {
            loopPreparation.clear(LoopPreparationPurpose.RANGE)
            rebindExplicitLoopPolicyOnActor(exactCurrentOwner, force = true)
            val revision = nextBankRevision(exactCurrentOwner.bank)
            exactCurrentOwner.intentRevision = revision
            bankIntents[exactCurrentOwner.bank.nativeCode] = bankIntents[exactCurrentOwner.bank.nativeCode].copy(revision = revision)
            publishSnapshotOnActor()
        }
        if (exactCurrentOwner != null) reconcileBoundedLoopOnActor(exactCurrentOwner, config)
        val needsInitialHead = explicitLoopState.get()?.let {
            it.token == selection.token && it.config?.parkUntilRangeEntry == false
        } == true && !(publishedLoopAvailabilityOwnership == ownership && publishedLoopAvailable)
        val rangeAvailable = exactCurrentOwner != null && !stopping && !closing &&
            !routeRecoveryRequested.get() && routeRecoveryTransaction == null &&
            !routeOutputNeedsReopen &&
            (!needsInitialHead || environment.isFixedLoopHeadPrepared(ownership)) &&
            currentSongCacheRangeReady(requireNotNull(exactCurrentOwner),
                actorExplicitLoopState?.takeIf { it.token == selection.token }?.config)
        val nativeLoopFailure = latestNativeRenderReadiness?.loopPreparationFailure?.takeIf {
            it.ownership == ownership && it.revision == actorExplicitLoopState?.nativePolicyRevision
        }?.let { when (it.reason) {
            NativeRuntimeLoopPreparationFailureReason.PIPELINE -> "the audio pipeline"
            NativeRuntimeLoopPreparationFailureReason.LANES -> "the audio lanes"
            NativeRuntimeLoopPreparationFailureReason.NONE -> null
        } }?.let { "Loop preparation failed while allocating $it; retry the loop" }
        val available = nativeLoopFailure == null && rangeAvailable && (config == null || (exactCurrentOwner != null &&
            environment.isCurrentSongCacheRangeReady(bankIntents[exactCurrentOwner.bank.nativeCode],
                loopHeadLaneMask(exactCurrentOwner), msToFrame(config.endMs) - 1L, msToFrame(config.endMs))))
        val currentBoundedFailure = nativeLoopFailure ?: (loopPreparation.range?.takeIf { it.intent.ownership == ownership }
            ?.result as? LoopPreparationResult.Failed)?.cause
        val phase = when {
            available -> LoopPreparationPhase.READY
            currentBoundedFailure != null -> LoopPreparationPhase.FAILED
            else -> LoopPreparationPhase.PREPARING
        }
        val preparation = LoopPreparationState(phase, ownership.selectionToken, ownership.generation,
            actorExplicitLoopState?.revision ?: 0L,
            when (phase) {
                LoopPreparationPhase.FAILED -> currentBoundedFailure
                else -> null
            }, phase == LoopPreparationPhase.FAILED,
            canConfigure = exactCurrentOwner != null && !stopping && !closing && !routeRecoveryRequested.get())
        if (publishedLoopAvailabilityOwnership == ownership &&
            publishedLoopAvailable == available && publishedLoopPreparation == preparation
        ) return
        publishedLoopAvailabilityOwnership = ownership
        publishedLoopAvailable = available
        publishedLoopPreparation = preparation
        listener.onEvent(
            NativeEngineControllerEvent.LoopAvailabilityChanged(
                token = selection.token,
                ownership = ownership,
                available = available,
                preparation = preparation
            )
        )
    }

    private fun currentSongCacheRangeReady(record: BankRecord, config: EngineLoopConfig?): Boolean =
        cacheAdmission.rangeReady(record, bankIntents[record.bank.nativeCode], loopHeadLaneMask(record),
            currentTimelineFrame(record), config)

    /**
     * Authorizes the callback gate after native has accepted non-empty PCM for the exact latest
     * manual owner. Do not wait for runtime committed ownership here: a playing owner's commit is
     * callback-presentation evidence, and the still-closed gate intentionally prevents that
     * evidence. The output retains this exact generation authorization until its callback adopts
     * the non-empty role. Selection and authorization share one lock, so if C supersedes B, B can
     * no longer pass this check even when its stale acceptance arrives first.
     */
    private fun authorizeManualSelectionSilenceBurstIfReady(
        diagnostics: NativeRuntimeControlObservation
    ) {
        val record = selected ?: return
        val selection = record.selection ?: return
        val pendingReplacement = candidate
        // An equal/older recovery needs this exact seek's presentation as its live anchor. Only a
        // replacement that owns a newer seek may keep the selected generation behind the gate.
        if (pendingReplacement !== record && pendingReplacement?.replacementPurpose != null &&
            pendingReplacement.selection?.token == selection.token &&
            pendingReplacement.bank !in releases &&
            (pendingSeekOwnership != record.ownership ||
                pendingReplacement.lastSeekRevision > record.lastSeekRevision)
        ) return
        val ownership = record.ownership
        val progress = diagnostics.ownership
        if (progress.requested != ownership || progress.accepted != ownership ||
            progress.acceptedFirstFingerprintSequence <= 0L
        ) return

        synchronized(manualSelectionSilenceArmGate) {
            val burst = manualSelectionSilenceBurst.get() ?: return@synchronized
            if (burst.phase != ManualSelectionSilencePhase.CLOSED ||
                burst.latestToken != selection.token ||
                !ownerGate.isLatest(selection.token) ||
                selected !== record || record.ownership != ownership
            ) return@synchronized
            if (environment.authorizeManualSelectionGate(ownership.generation)) {
                // Keep the authorization represented until another selector or route boundary.
                // Accepted PCM is not callback-adoption evidence: the decoder can still fail or
                // stall before the role swap. A later selector must re-arm and revoke this exact
                // generation instead of inheriting a gate that can never open for it.
                manualSelectionSilenceBurst.set(
                    burst.copy(phase = ManualSelectionSilencePhase.AUTHORIZED)
                )
            }
        }
    }

    private fun handleAudibleControlEvidence(
        evidence: NativeRuntimeAudibleControlEvidence
    ) {
        val record = selected?.takeIf { it.ownership == evidence.ownership } ?: return
        if (publicOwnership != evidence.ownership) {
            // Callback proof can arrive before its owner publication. Keep it for the rebase;
            // advancing the watermark here would permanently lose this one-shot acknowledgement.
            pendingAudibleControlEvidence.compareAndSet(null, evidence)
            return
        }
        controlProgressObserver.observeAudibleControl(evidence.sequence)
        val publication = sourceControlPublications.firstOrNull {
            it.nativeControlRevision == evidence.controlRevision &&
                it.ownership == evidence.ownership
        } ?: return
        if (publication.nativeMode != evidence.actualSourceMode) return
        val desired = record.desired ?: return
        if (desired.selection.token != publication.token ||
            desired.sourceMode != publication.requestedMode ||
            record.sourceIntentRevision != publication.desiredRevision ||
            publication.nativeMode.toEngineSourceMode() != publication.requestedMode
        ) return

        if (record.sourceActivation.observeAudible(publicOwnership, evidence, publication,
                SourceActivationEndpoint(record.sourceIntentRevision, desired.sourceMode))) {
            listener.onEvent(
                NativeEngineControllerEvent.SourceModeApplied(
                    publication.token,
                    publication.desiredRevision,
                    publication.requestedMode
                )
            )
        }

        if (evidence.actualSourceMode != NativeRuntimeSourceMode.STEMS) return
        val packageIdentity = publication.stemPackageIdentity ?: return
        val stemRevision = requireNotNull(publication.stemRevision)
        val requestRevision = requireNotNull(publication.stemRequestRevision)
        val physical = record.stemPackage ?: return
        if (persistedStemPackageIdentity(physical.signature) != packageIdentity ||
            physical.stemRevision != stemRevision ||
            physical.requestRevision != requestRevision ||
            record.stemReadyEventRevision != requestRevision
        ) return
        val signature = physical.signature
        val eventKey = stemEventKey(physical)
        if (!rememberBounded(installedStemEvents, eventKey)) return
        listener.onEvent(
            NativeEngineControllerEvent.StemPackageInstalled(
                token = publication.token,
                packageSignature = signature,
                stemRevision = stemRevision,
                requestRevision = requestRevision,
                callbackSequence = evidence.callbackSequence
            )
        )
        if (record.pendingStemPackage?.signature == signature) {
            record.pendingStemPackage = null
        }
        record.retainedPriorStemSignature = null
        record.priorStemPackageStillRetained = false
    }

    private fun NativeRuntimeSourceMode.toEngineSourceMode(): EngineSourceMode = when (this) {
        NativeRuntimeSourceMode.ORIGINAL -> EngineSourceMode.ORIGINAL
        NativeRuntimeSourceMode.STEMS -> EngineSourceMode.STEMS
    }

    /** MediaCodec callbacks only publish immutable terminal state; all policy runs on this actor. */
    private fun pollDecoderFailuresOnActor() {
        val records = listOfNotNull(active, candidate, cacheBuilder).distinct()
        for (record in records) {
            if (record.bank in releases) continue
            for ((laneIndex, decoder) in record.decoders.toMap()) {
                val failure = decoder.failure() ?: continue
                val key = nativeTerminalEventIdentity(
                    "${failure.decoderId}:${failure.generation}:${failure.cause}"
                )
                if (!rememberBounded(handledDecoderFailures, key)) continue
                val expectedToken = record.selection?.token ?: record.reservation?.token
                if (failure.token != expectedToken ||
                    failure.generation != record.ingressEpoch ||
                    failure.laneIndex != laneIndex ||
                    failure.decoderId != decoder.decoderId
                ) {
                    // A delayed terminal callback from an old seek/owner has no authority over the
                    // actor's current package transaction.
                    continue
                }

                listener.onEvent(
                    NativeEngineControllerEvent.DecoderFailed(
                        token = failure.token,
                        generation = failure.generation,
                        laneIndex = failure.laneIndex,
                        cause = failure.cause,
                        recoverable = true
                    )
                )

                val failedPhysicalPackage = if (laneIndex in 1..StemRoleContract.maximumStemCount) {
                    record.stemPackage
                } else {
                    null
                }
                val affectedRequest = failedPhysicalPackage ?: record.pendingStemPackage
                    ?: record.stemPackage
                affectedRequest?.let { request ->
                    emitStemPackageFailed(
                        request,
                        recoverable = true,
                        retainedPriorPackage = record !== active &&
                            active?.stemPackage != null,
                        cause = "${failure.decoderId} failed: ${failure.cause}"
                    )
                    record.lastFailedStemRequestRevision = request.requestRevision
                    if (record.pendingStemPackage == request) {
                        record.pendingStemPackage = null
                    }
                }

                // Any terminal lane invalidates the coherent package. Surviving stems must never
                // mix with missing or stale lanes from another package.
                record.stemPackage = null
                record.stemSignature = null
                record.stemCleanupRequired = record.decoders.keys.any { it in 1..StemRoleContract.maximumStemCount }
                if (record === candidate) {
                    if (laneIndex == NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX &&
                        record.replacementPurpose == InternalReplacementPurpose.STEM_RECOVERY &&
                        active?.originalRecoveryRequired == true
                    ) {
                        val failedOwner = active
                        beginRelease(record)
                        failedOwner?.let(::beginRelease)
                        record.desired?.let { settleFailedSelection(it,
                            "Replacement Original failed: ${failure.cause}", failedOwner?.ownership) }
                        continue
                    }
                    if (laneIndex == NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX &&
                        record.replacementPurpose == InternalReplacementPurpose.EARLY_SEEK
                    ) {
                        val failedDesired = record.desired
                        active?.frameZeroCacheBuilderEligible = false
                        beginRelease(record)
                        if (failedDesired != null) deferredDesired = failedDesired
                        continue
                    }
                    if (laneIndex == NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX &&
                        record.selection != null
                    ) {
                        // A foreground Original may configure and start, then fail before it can
                        // publish any ownership evidence (for example a missing/corrupt local
                        // file). Releasing that silent candidate without failing its exact request
                        // strands the adapter in loading forever with no native owner.
                        val failedDesired = record.desired
                        beginRelease(record)
                        failedDesired?.let { desired ->
                            settleFailedSelection(
                                desired,
                                "${failure.decoderId} failed: ${failure.cause}"
                            )
                        }
                        continue
                    }
                    if (laneIndex == 0 && record.selection == null && record.reservation != null) {
                        // A MediaCodec can configure successfully and then fail asynchronously.
                        // Quarantine that exact likely-next audio just like a synchronous open
                        // failure, otherwise the release acknowledgement would trigger a poll-loop
                        // reopen storm against the same corrupt local file.
                        settleLikelyNextPrewarmFailure(record, failure.cause)
                    }
                    affectedRequest?.let { failed ->
                        active?.takeIf { it.pendingStemPackage?.signature == failed.signature }
                            ?.apply {
                                pendingStemPackage = null
                                lastFailedStemRequestRevision = failed.requestRevision
                            }
                    }
                    beginRelease(record)
                    continue
                }

                if (record === cacheBuilder) {
                    beginRelease(record)
                    continue
                }

                if (laneIndex == NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX) {
                    record.originalRecoveryRequired = true
                    // Speculative work cannot occupy the sole bank needed by foreground recovery.
                    candidate?.takeIf { it.selection == null }?.let { speculative ->
                        speculative.reservation?.let(ownerGate::cancelReservation)
                        beginRelease(speculative)
                    }
                    cacheBuilder?.takeIf { it !== record }?.let(::beginRelease)
                }
                if (laneIndex != NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX &&
                    detachFailedStemPackageOnActor(record)) continue
                publishSnapshotOnActor()
                val stillRequested = record.pendingStemPackage?.takeIf {
                    it.requestRevision != record.lastFailedStemRequestRevision
                }
                if (candidate == null && freeBank() != null) {
                    openStemRecoveryCandidate(record, stillRequested)
                }
            }
        }
    }

    private fun emitStemPackagePreparing(
        record: BankRecord,
        request: StemPackageRequest,
        replacing: String?
    ) {
        val key = stemEventKey(request)
        if (!rememberBounded(preparingStemEvents, key)) return
        preparationEvidence.begin(NativeControllerPreparationOperation.STEM_PACKAGE, record.ownership,
            request.requestRevision, "decoder-coverage", scheduler.monotonicTimeMs())
        listener.onEvent(
            NativeEngineControllerEvent.StemPackagePreparing(
                token = request.token,
                packageSignature = request.signature,
                stemRevision = request.stemRevision,
                requestRevision = request.requestRevision,
                replacingPackageSignature = replacing
            )
        )
        record.pendingStemPackage = request
    }

    private fun emitStemReadyIfCovered(record: BankRecord) {
        if (record !== active || record.bank in releases) return
        val physical = record.stemPackage ?: return
        val pending = record.pendingStemPackage ?: physical
        if (pending.signature != physical.signature || record.stemReadyEventRevision == pending.requestRevision) return
        if (!stemsReady(record)) return
        // Bank-local readiness survives deduplication of the request's public event.
        record.stemReadyEventRevision = pending.requestRevision
        val key = stemEventKey(pending)
        if (rememberBounded(readyStemEvents, key)) {
            preparationEvidence.finish(NativeControllerPreparationOperation.STEM_PACKAGE, pending.token.value,
                pending.requestRevision, "ready-awaiting-audible", scheduler.monotonicTimeMs())
            listener.onEvent(
                NativeEngineControllerEvent.StemPackageReadyAwaitingAudible(
                    token = pending.token,
                    packageSignature = pending.signature,
                    stemRevision = pending.stemRevision,
                    requestRevision = pending.requestRevision,
                    retainedPriorPackage = record.priorStemPackageStillRetained
                )
            )
        }
        if (needsReadyStemPublication(record)) {
            // Cache sealing can race the earlier poll probe. Publish before this ready event
            // lets a physically paused actor stop polling with its old Original command.
            publishSnapshotOnActor()
        }
    }

    private fun needsReadyStemPublication(record: BankRecord): Boolean {
        val desired = record.desired ?: return false
        return record === selected && desired.sourceMode == EngineSourceMode.STEMS &&
            ((record.sourceActivation.published?.revision ?: 0L) < desired.revision ||
                record.sourceActivation.published?.mode != NativeRuntimeSourceMode.STEMS)
    }

    private fun emitStemPackageFailed(
        request: StemPackageRequest,
        recoverable: Boolean,
        retainedPriorPackage: Boolean,
        cause: String
    ) = emitStemPackageFailed(
        token = request.token,
        signature = request.signature,
        stemRevision = request.stemRevision,
        requestRevision = request.requestRevision,
        recoverable = recoverable,
        retainedPriorPackage = retainedPriorPackage,
        cause = cause
    )

    private fun emitStemPackageFailed(
        token: EngineSelectionToken,
        signature: String,
        stemRevision: String,
        requestRevision: Long,
        recoverable: Boolean,
        retainedPriorPackage: Boolean,
        cause: String
    ) {
        val key = stemEventKey(token, signature, requestRevision)
        if (!rememberBounded(failedStemEvents, key)) return
        preparationEvidence.finish(NativeControllerPreparationOperation.STEM_PACKAGE, token.value,
            requestRevision, "failed", scheduler.monotonicTimeMs(), cause)
        listener.onEvent(
            NativeEngineControllerEvent.StemPackageFailed(
                token = token,
                packageSignature = signature,
                stemRevision = stemRevision,
                requestRevision = requestRevision,
                recoverable = recoverable,
                retainedPriorPackage = retainedPriorPackage,
                cause = cause
            )
        )
    }

    private fun stemEventKey(request: StemPackageRequest): String =
        stemEventKey(request.token, request.signature, request.requestRevision)

    private fun stemEventKey(
        token: EngineSelectionToken,
        signature: String,
        requestRevision: Long
    ): String = nativeTerminalEventIdentity("${token.value}:$signature:$requestRevision")

    private fun rememberBounded(keys: LinkedHashSet<String>, key: String): Boolean {
        if (!keys.add(key)) return false
        if (keys.size > MAXIMUM_REMEMBERED_TERMINAL_EVENTS) {
            val oldest = keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    private fun handlePresentationEvidence(presentation: NativeRuntimePresentationEvidence) {
        candidate?.takeIf { successor ->
            successor.selection == null && successor.assigned &&
                successor.automaticSuccessorRevision > 0L &&
                successor.ownership == presentation.ownership
        }?.let { successor ->
            if (commitAutomaticSuccessor(successor, presentation)) return
        }
        val pending = pendingPublication
        if (pending != null && pending.ownership == presentation.ownership &&
            selected === pending
        ) {
            if (pending.replacementPurpose != null) {
                val selection = pending.selection ?: return
                val evidence = ownerEvidence(selection, presentation) ?: return
                commitInternalReplacement(pending, evidence)
                return
            }
            val selection = pending.selection ?: return
            if (!ownerGate.isLatest(selection.token)) return
            val evidence = ownerEvidence(selection, presentation) ?: return
            val commit = ownerGate.accept(evidence) ?: return
            publishOwner(commit.selection, pending, NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                evidence)
            return
        }
        val record = selected?.takeIf { it.ownership == presentation.ownership } ?: return
        val selection = record.selection ?: return
        val evidence = ownerEvidence(selection, presentation) ?: return
        if (pendingSeekOwnership == presentation.ownership) {
            pendingSeekOwnership = null
            publishOwnershipRebase(
                record,
                NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                evidence
            )
        } else if (routeAwaitingOwnership == presentation.ownership) {
            // The route remains paused until the full ownership progress tuple settles below, but
            // this exact callback evidence can already advance the public generation.
            publishOwnershipRebase(
                record,
                NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED,
                evidence
            )
        }
    }

    private fun commitAutomaticSuccessor(
        successor: BankRecord,
        presentation: NativeRuntimePresentationEvidence
    ): Boolean {
        val reservation = successor.reservation ?: return false
        val outgoingSelection = publicSelection ?: return false
        val outgoing = active?.takeIf {
            it.selection == outgoingSelection && it.ownership == publicOwnership && it !== successor
        } ?: return false
        // The exact reservation and successor revision were authorized before native opened its
        // drain. Queue policy may publish a newer hint while the callback presentation is waiting
        // for this actor turn; once successor PCM is physically audible, that mutable hint cannot
        // veto the already-completed boundary. Manual selection/cancellation still wins by
        // clearing the owner-gate reservation itself.
        if (presentation.pcmFrames <= 0L || presentation.pcmFrames > Int.MAX_VALUE.toLong()) {
            return false
        }

        val evidence = EngineAudibleOwnerEvidence(
            token = reservation.token,
            songId = successor.identity.songId,
            callbackSequence = presentation.callbackSequence,
            firstOutputFrame = presentation.firstOutputFrame,
            finalPcmFrames = presentation.pcmFrames.toInt(),
            outputFingerprintSequence = presentation.fingerprintSequence
        )
        val desired = synchronized(manualSelectionSilenceArmGate) {
            val installed = desiredStore.installAutomaticSuccessor(outgoingSelection.token, outgoing.lastSeekRevision) { playing ->
                val committed = ownerGate.acceptReservedAtAudibleBoundary(
                    expected = reservation, expectedOutgoingToken = outgoingSelection.token,
                    evidence = evidence, autoplay = playing
                ) ?: return@installAutomaticSuccessor null
                if (BuildConfig.BUILD_TYPE == "development") automaticPromotionBeforeInstallForTest?.invoke()
                committed.selection
            } ?: return false
            val successorLoop = EngineExplicitLoopState(installed.selection.token, 0L, null)
            explicitLoopState.set(successorLoop)
            actorExplicitLoopState = successorLoop
            selectionEpoch.updateAndGet { current -> increment(current, "selection epoch") }
            installed
        }
        successor.reservation = null
        successor.selection = desired.selection
        successor.desired = desired
        successor.lastDesiredRevision = desired.revision
        successor.sourceIntentRevision = desired.sourceIntentRevision
        successor.stemIntentRevision = desired.stemIntentRevision
        successor.lastSeekRevision = desired.seekRevision
        successor.frameZeroCacheBuilderEligible =
            desired.selection.startPositionMs == 0L
        successor.automaticSuccessorRevision = 0L
        selected = successor
        audibleTransportIntent = AudibleTransportIntent(
            desired.selection.token,
            desired.playWhenReady
        )
        publishOwner(
            desired.selection,
            successor,
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PRESENTED,
            evidence
        )
        check(active === successor && outgoing !== successor)
        return true
    }

    /**
     * Completes the one legal evidence-free automatic promotion. The output callback has already
     * adopted the successor role, but Pause prevented it from emitting successor PCM. Native's
     * exact requested/accepted/committed tuple is therefore authoritative; audible evidence must
     * remain absent.
     */
    private fun commitAppliedPausedAutomaticSuccessor(
        diagnostics: NativeRuntimeControlObservation
    ): Boolean {
        val plan = diagnostics.automaticSuccessor
        if (plan.phase !=
            NativeRuntimeAutomaticSuccessorPhase.APPLIED_PAUSED_AWAITING_SELECTION ||
            plan.cancellationStatus != NativeRuntimeDrainCancelStatus.SUCCESSOR_ACTIVE ||
            plan.successorQueuedFrames <= 0L || plan.firstFingerprintSequence <= 0L
        ) return false
        val outgoingOwnership = plan.outgoingOwnership ?: return false
        val successorOwnership = plan.successorOwnership ?: return false
        val progress = diagnostics.ownership
        if (progress.requested != successorOwnership ||
            progress.accepted != successorOwnership ||
            progress.committed != successorOwnership
        ) return false

        val successor = candidate?.takeIf {
            it.selection == null && it.assigned && it.reservation != null &&
                it.automaticSuccessorRevision > 0L && it.ownership == successorOwnership
        } ?: return false
        val reservation = successor.reservation ?: return false
        val outgoingSelection = publicSelection ?: return false
        val outgoing = active?.takeIf {
            it.selection == outgoingSelection && it.ownership == publicOwnership &&
                it.ownership == outgoingOwnership && it !== successor && !effectivePlaying(it)
        } ?: return false
        val desired = synchronized(manualSelectionSilenceArmGate) {
            val installed = desiredStore.installAutomaticSuccessor(outgoingSelection.token, outgoing.lastSeekRevision) {
                val committed = ownerGate.acceptReservedPausedInstalled(
                    expected = reservation, expectedOutgoingToken = outgoingSelection.token
                ) ?: return@installAutomaticSuccessor null
                if (BuildConfig.BUILD_TYPE == "development") automaticPromotionBeforeInstallForTest?.invoke()
                committed
            } ?: return false
            val successorLoop = EngineExplicitLoopState(installed.selection.token, 0L, null)
            explicitLoopState.set(successorLoop)
            actorExplicitLoopState = successorLoop
            selectionEpoch.updateAndGet { current -> increment(current, "selection epoch") }
            installed
        }
        val selection = desired.selection
        successor.reservation = null
        successor.selection = selection
        successor.desired = desired
        successor.lastDesiredRevision = desired.revision
        successor.sourceIntentRevision = desired.sourceIntentRevision
        successor.stemIntentRevision = desired.stemIntentRevision
        successor.lastSeekRevision = desired.seekRevision
        successor.frameZeroCacheBuilderEligible =
            desired.selection.startPositionMs == 0L
        successor.automaticSuccessorRevision = 0L
        selected = successor
        audibleTransportIntent = AudibleTransportIntent(selection.token, desired.playWhenReady)
        publishOwner(
            selection,
            successor,
            NativeEngineOwnerPublicationKind.AUTOMATIC_SUCCESSOR_PAUSED_INSTALLED,
            null
        )
        check(active === successor && outgoing !== successor)
        return true
    }

    private fun ownerEvidence(
        selection: EngineSelection,
        presentation: NativeRuntimePresentationEvidence
    ): EngineAudibleOwnerEvidence? {
        if (presentation.pcmFrames <= 0L ||
            presentation.pcmFrames > Int.MAX_VALUE.toLong()
        ) return null
        return EngineAudibleOwnerEvidence(
            token = selection.token,
            songId = selection.songId,
            callbackSequence = presentation.callbackSequence,
            firstOutputFrame = presentation.firstOutputFrame,
            finalPcmFrames = presentation.pcmFrames.toInt(),
            outputFingerprintSequence = presentation.fingerprintSequence
        )
    }

    private fun publishPausedInstalledOwner(record: BankRecord) {
        val selection = record.selection ?: return
        val accepted = ownerGate.acceptPausedInstalled(selection.token, selection.songId) ?: return
        publishOwner(
            accepted,
            record,
            NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED,
            null
        )
    }

    private fun commitInternalReplacement(
        record: BankRecord,
        evidence: EngineAudibleOwnerEvidence? = null
    ) {
        val previous = active ?: return
        if (previous === record || record.selection?.token != publicSelection?.token) return
        publishOwnershipRebase(
            record,
            if (evidence == null) {
                NativeEngineOwnerPublicationKind.PAUSED_NONEMPTY_INSTALLED
            } else {
                NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED
            },
            evidence
        )
        pendingPublication = null
        if (pendingSeekOwnership == record.ownership) pendingSeekOwnership = null
        val purpose = record.replacementPurpose ?: return
        record.replacementPurpose = null
        record.priorStemPackageStillRetained = false
        active = record
        if (candidate === record) candidate = null
        if (purpose == InternalReplacementPurpose.EARLY_SEEK) {
            check(cacheBuilder == null)
            cacheBuilder = previous
            publishSnapshotOnActor()
        } else {
            beginRelease(previous)
        }
    }

    private fun publishOwnershipRebase(
        record: BankRecord,
        kind: NativeEngineOwnerPublicationKind,
        evidence: EngineAudibleOwnerEvidence?
    ) {
        val selection = record.selection ?: return
        if (publicSelection != selection || publicOwnership == record.ownership) return
        publicOwnership = record.ownership
        listener.onEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection = selection,
                    ownership = record.ownership,
                    metadata = metadataByToken[selection.token.value],
                    kind = kind,
                    evidence = evidence,
                    completedSeekRevision = record.lastSeekRevision
                )
            )
        )
        // Rebase clears the retired owner's source evidence in the publication reducer. A
        // paused seek may already have prepared this owner's source before its install arrived.
        if (!effectivePlaying(record) && (record.sourceActivation.prepared?.revision ?: 0L) > 0L &&
            (record.sourceActivation.prepared?.revision ?: 0L) == record.sourceIntentRevision &&
            record.sourceActivation.published?.mode?.toEngineSourceMode() == record.sourceActivation.prepared?.mode &&
            record.sourceActivation.prepared?.mode == record.desired?.sourceMode
        ) {
            listener.onEvent(NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
                selection.token, (record.sourceActivation.prepared?.revision ?: 0L),
                requireNotNull(record.sourceActivation.prepared?.mode)
            ))
        }
    }

    private fun publishOwner(
        selection: EngineSelection,
        record: BankRecord,
        kind: NativeEngineOwnerPublicationKind,
        evidence: EngineAudibleOwnerEvidence?
    ) {
        if (kind == NativeEngineOwnerPublicationKind.CALLBACK_PRESENTED && evidence != null) {
            completeLocalStartSettlementOnActor(record.ownership)
        }
        val previous = active
        val ownerChanged = publicSelection != selection
        setPublicSelectionOnActor(selection)
        publicOwnership = record.ownership
        pendingPublication = null
        active = record
        if (candidate === record) candidate = null
        val selectedMetadata = metadataByToken[selection.token.value]
        if (ownerChanged) {
            lastPublishedMetadataRevision = selectedMetadata?.revision ?: 0L
        }
        listener.onEvent(
            NativeEngineControllerEvent.OwnerPublished(
                NativeEngineOwnerPublication(
                    selection,
                    record.ownership,
                    selectedMetadata,
                    kind,
                    evidence,
                    record.lastSeekRevision
                )
            )
        )
        if (previous != null && previous !== record) {
            beginRelease(previous)
        } else {
            record.decoders[0]?.let { original ->
                check(original.promoteCandidateLeaseToActive())
            }
            record.desired?.let { reconcileStemPackageOnActor(record, it) }
            publishSnapshotOnActor()
        }
    }

    /**
     * Reconciles one exact declared package without ever letting a partial set become the mixer
     * source. With an existing package, the single candidate lease can only prepare an
     * Original fallback in the spare bank. The old bank remains selected until callback evidence
     * commits that fallback; only after exact unassignment may the replacement lanes open.
     */
    private fun reconcileStemPackageOnActor(
        record: BankRecord,
        desired: EngineDesiredState,
        completedCacheBuilder: BankRecord? = null
    ) {
        // No new codec may open while either bank is awaiting exact unassignment. In the
        // replacement path the outgoing leases are intentionally still live until that ack.
        if ((record !== active && record !== cacheBuilder) ||
            releases.isNotEmpty() || stopping || closing
        ) return
        if (record.stemCleanupRequired) {
            if (detachFailedStemPackageOnActor(record)) return
        }
        if (desired.stemLanes.isEmpty()) return

        val request = stemPackageRequest(record, desired)
        if (request == null) {
            val signature = rawStemPackageSignature(desired.stemLanes)
            emitStemPackageFailed(
                token = desired.selection.token,
                signature = signature,
                stemRevision = desired.stemRevision.ifBlank { signature },
                requestRevision = record.stemIntentRevision,
                recoverable = true,
                retainedPriorPackage = record.stemPackage != null,
                cause = "Sources must form one valid disjoint selection with Remaining"
            )
            record.lastFailedStemRequestRevision = record.stemIntentRevision
            return
        }

        if (record === active) {
            val builder = matchingCacheBuilder(record, request)
            val completedBuilder = completedCacheBuilder?.takeIf { completed ->
                completed.identity == record.identity &&
                    completed.selection?.token == request.token &&
                    (completed.stemPackage ?: completed.pendingStemPackage
                        ?: completed.desired?.let { stemPackageRequest(completed, it) })
                        ?.signature == request.signature
            }
            val pending = record.pendingStemPackage
            if ((builder != null || completedBuilder != null) && pending != null &&
                pending.signature == request.signature &&
                pending.stemRevision == request.stemRevision &&
                pending.requestRevision == request.requestRevision &&
                environment.isCurrentSongCacheAllLanesSealed(record.ownership)
            ) {
                // Native's owner-bound cache key already includes this record's exact song,
                // Original, and requested stem-package hashes. The builder may have been
                // preempted after native rebound its completed cache, so cache authority must not
                // depend on the old controller record still existing.
                record.stemPackage = request
                record.stemSignature = request.signature
                record.pendingStemPackage = request
                record.stemAnchorFrame = 0L
                record.stemCleanupRequired = false
                emitStemReadyIfCovered(record)
                publishSnapshotOnActor()
                builder?.let(::beginRelease)
                return
            }
            if (builder != null) {
                record.pendingStemPackage = request
                // A newer source edge preempts even a builder that still remembers Stems.
                // Inherited Stems seeks keep building; published PCM survives live recovery.
                if (desired.sourceMode == EngineSourceMode.STEMS &&
                    (builder.desired?.sourceMode != EngineSourceMode.STEMS ||
                        desired.sourceIntentRevision > builder.sourceIntentRevision)) beginRelease(builder)
                return
            }
            if (completedBuilder != null && desired.sourceMode == EngineSourceMode.STEMS &&
                (completedBuilder.desired?.sourceMode != EngineSourceMode.STEMS ||
                    desired.sourceIntentRevision > completedBuilder.sourceIntentRevision)) {
                record.pendingStemPackage = request
                if (candidate == null && freeBank() != null) openStemRecoveryCandidate(record, request)
                return
            }
            cacheBuilder?.takeIf {
                it.assigned && it.bank !in releases &&
                    it.selection?.token == request.token && it.identity == record.identity
            }?.let { retainedBuilder ->
                if (desired.sourceMode == EngineSourceMode.STEMS) {
                    record.pendingStemPackage = request
                    beginRelease(retainedBuilder)
                } else if (retainedBuilder.frameZeroCacheBuilderEligible &&
                    retainedBuilder.stemPackage == null &&
                    retainedBuilder.decoders.keys ==
                    setOf(NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX)
                ) {
                    setRecordDesired(retainedBuilder, desired)
                    refreshAssignedContentIdentity(retainedBuilder)
                    reconcileStemPackageOnActor(retainedBuilder, desired)
                    record.pendingStemPackage = request
                } else {
                    beginRelease(retainedBuilder)
                }
                return
            }
        }

        // A newer same-token package supersedes an Original fallback before it has consumed any
        // replacement stems. The fallback remains reusable; no output generation is spent here.
        candidate?.takeIf {
            it.replacementPurpose == InternalReplacementPurpose.STEM_RECOVERY &&
                it.selection?.token == request.token
        }?.let {
            val superseded = it.pendingStemPackage
            if (superseded != null && superseded.signature != request.signature) {
                emitStemPackageFailed(
                    superseded,
                    recoverable = true,
                    retainedPriorPackage = it.priorStemPackageStillRetained,
                    cause = "Superseded by stem-package request ${request.requestRevision}"
                )
            }
            it.pendingStemPackage = request
            setRecordDesired(it, desired)
            // A clean replacement owns this cold attach after its callback commits.
            if (record.stemPackage == null) return
        }

        val physical = record.stemPackage
        // Unavailable readiness cannot retire an admitted identity; hard failures clear it above.
        if (physical?.signature == request.signature) {
            if (physical.token == request.token &&
                physical.stemRevision == request.stemRevision &&
                physical.requestRevision == request.requestRevision
            ) {
                // The exact package is already installed. Do not resurrect an acknowledged
                // transaction as pending after an unrelated desired-state revision.
                emitStemReadyIfCovered(record)
                return
            }
            // Revision-only enrichment changes are metadata. Keep the exact decoder graph and
            // output generation; correlate the new request to the already primed physical set.
            record.stemPackage = request
            record.stemSignature = request.signature
            record.pendingStemPackage = request
            emitStemPackagePreparing(record, request, replacing = null)
            emitStemReadyIfCovered(record)
            return
        }

        val previousPending = record.pendingStemPackage
        if (previousPending?.signature != request.signature ||
            previousPending.requestRevision != request.requestRevision
        ) {
            if (previousPending != null && previousPending.signature != request.signature &&
                previousPending.signature != physical?.signature
            ) {
                emitStemPackageFailed(
                    previousPending,
                    recoverable = true,
                    retainedPriorPackage = record.priorStemPackageStillRetained || physical != null,
                    cause = "Superseded by stem-package request ${request.requestRevision}"
                )
            }
            record.pendingStemPackage = request
            record.retainedPriorStemSignature = physical?.signature
            record.priorStemPackageStillRetained = physical != null
            emitStemPackagePreparing(record, request, physical?.signature)
        }
        if (record.lastFailedStemRequestRevision == request.requestRevision) return

        val configuredStemLanes = record.decoders.keys.count { it in 1..StemRoleContract.maximumStemCount }
        if (configuredStemLanes == 0 && physical == null && !record.stemCleanupRequired &&
            record.lastFailedStemRequestRevision == 0L
        ) {
            attachStemPackageToOriginalBank(record, request)
            return
        }

        // Failed packages can leave old multibus PCM queued under this generation. Even after
        // optional lanes detach, reattachment needs the existing acknowledged new-owner boundary
        // so newer package controls cannot reinterpret those retired buses.
        if (candidate == null && freeBank() != null) {
            openStemRecoveryCandidate(record, request)
        } else if (candidate?.replacementPurpose == null && releases.isEmpty()) {
            candidate?.let(::beginRelease)
        }
    }

    private fun attachStemPackageToOriginalBank(
        record: BankRecord,
        request: StemPackageRequest
    ) {
        check(record === active || record === cacheBuilder)
        check(record.decoders.keys == setOf(NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX))
        if (record === selected && record.assigned &&
            environment.renderReadinessDiagnostics()?.ownership?.let { it != record.ownership } == true
        ) return // The seek must expose this owner's cache before choosing a stem decoder anchor.
        // A sealed Original can survive a nonzero seek before Stems are attached. Its new stem
        // lanes must still fill the cache from zero, not begin at the audible seek position.
        val anchorFrame = if (record.frameZeroCacheBuilderEligible ||
            environment.isCurrentSongCacheLaneMaskSealed(
                record.ownership,
                NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK
            )
        ) 0L else {
            record.stemAnchorFrame ?: currentTimelineFrame(record)
        }
        val anchorMs = frameToMs(anchorFrame)
        record.stemAnchorFrame = msToFrame(anchorMs)
        for ((index, stem) in request.canonicalLanes.withIndex()) {
            val laneIndex = index + NativeSongLaneIngressBank.FIRST_STEM_LANE_INDEX
            try {
                record.decoders[laneIndex] = openDecoder(
                    record,
                    NativeSongBankRole.ACTIVE,
                    laneIndex,
                    stem.normalizedRole,
                    stem.location,
                    anchorMs,
                    contentSha256 = stem.contentSha256
                )
            } catch (failure: Throwable) {
                record.stemPackage = null
                record.stemSignature = null
                record.stemCleanupRequired = record.decoders.keys.any { it in 1..StemRoleContract.maximumStemCount }
                record.lastFailedStemRequestRevision = request.requestRevision
                emitStemPackageFailed(
                    request,
                    recoverable = true,
                    retainedPriorPackage = record.priorStemPackageStillRetained,
                    cause = "${stem.normalizedRole} could not open: " +
                        (failure.message ?: failure::class.java.simpleName)
                )
                if (detachFailedStemPackageOnActor(record)) return
                if (record.stemCleanupRequired && candidate == null && freeBank() != null) {
                    openStemRecoveryCandidate(record, pendingRequest = null)
                }
                publishSnapshotOnActor()
                return
            }
        }
        record.stemPackage = request
        record.stemSignature = request.signature
        record.stemCleanupRequired = false
        refreshAssignedContentIdentity(record)
        publishSnapshotOnActor()
        emitStemReadyIfCovered(record)
    }

    private fun matchingCacheBuilder(
        consumer: BankRecord,
        request: StemPackageRequest
    ): BankRecord? = cacheBuilder?.takeIf { builder ->
        builder.assigned && builder.bank !in releases &&
            builder.selection?.token == request.token && builder.identity == consumer.identity &&
            (builder.stemPackage ?: builder.pendingStemPackage
                ?: builder.desired?.let { stemPackageRequest(builder, it) })?.signature ==
                request.signature
    }

    /** Native detaches every optional lane before their producers or ingress storage can retire. */
    private fun detachFailedStemPackageOnActor(record: BankRecord): Boolean {
        if (!record.stemCleanupRequired || record !== active || selected !== record ||
            !record.assigned || record.bank in releases || record.originalRecoveryRequired
        ) return false
        if ((loopPreparation.preparingOwnership == record.ownership) &&
            !cancelPreparationOnActor(abortNative = true)
        ) return true
        val priorRevision = bankIntents[record.bank.nativeCode].revision
        refreshAssignedContentIdentity(record)
        if (bankIntents[record.bank.nativeCode].revision != priorRevision ||
            record.sourceActivation.published?.mode != NativeRuntimeSourceMode.ORIGINAL
        ) publishSnapshotOnActor()
        return true
    }

    private fun finishFailedStemCleanupOnActor(diagnostics: NativeRuntimeControlObservation) {
        val record = active?.takeIf { it.stemCleanupRequired && it === selected && it.assigned &&
            it.bank !in releases && !it.originalRecoveryRequired } ?: return
        val intent = bankIntents[record.bank.nativeCode]
        val applied = diagnostics.banks[record.bank.nativeCode]
        if (intent.ownership != record.ownership || intent.stemPackageHash != 0L || intent.stemLaneCount != 0 ||
            !applied.assigned || applied.appliedIntentRevision != intent.revision
        ) return
        val optional = record.decoders.values.toList().filter {
            it.laneIndex != NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX
        }
        if (record.stemCleanupDeadlineMs == null) {
            record.stemCleanupDeadlineMs = scheduler.monotonicTimeMs() + NATIVE_DECODER_JOIN_WATCHDOG_MS
            optional.forEach(NativeEngineDecoder::requestCancel)
        }
        val (joined, stuck) = optional.partition {
            it.awaitJoin(0L) == NativeEngineDecoderJoinStatus.JOINED
        }
        joined.forEach { decoder ->
            check(decoder.safeToReleaseLane())
            environment.releaseDecoder(record.bank, decoder)
            record.decoders.remove(decoder.laneIndex)
        }
        if (stuck.isNotEmpty()) {
            if (scheduler.monotonicTimeMs() >= requireNotNull(record.stemCleanupDeadlineMs)) {
                quarantineAfterJoinTimeout(record, stuck)
            }
            return
        }
        record.stemCleanupRequired = false
        record.stemCleanupDeadlineMs = null
        record.stemAnchorFrame = null
        record.desired?.let { reconcileStemPackageOnActor(record, it) }
    }

    private fun reconcileOriginalRecoveryOnActor() {
        if (runtimeStopped || stopping || closing || candidate != null) return
        val failed = active?.takeIf {
            it.originalRecoveryRequired && it.bank !in releases &&
                it.selection?.token == ownerGate.latest()?.token
        } ?: return
        if (freeBank() == null) return
        openStemRecoveryCandidate(failed, failed.pendingStemPackage?.takeIf {
            it.requestRevision != failed.lastFailedStemRequestRevision
        })
    }

    private fun openStemRecoveryCandidate(
        failed: BankRecord,
        pendingRequest: StemPackageRequest?
    ) {
        val desired = failed.desired ?: return
        val bank = freeBank() ?: return
        val replacement = BankRecord(
            bank = bank,
            identity = failed.identity,
            ownership = NativeRuntimeOwnership(
                desired.selection.token.value,
                allocateGeneration()
            ),
            reservation = null,
            selection = desired.selection,
            desired = desired,
            lastDesiredRevision = desired.revision,
            sourceIntentRevision = desired.sourceIntentRevision,
            stemIntentRevision = pendingRequest?.requestRevision
                ?: failed.stemIntentRevision,
            lastSeekRevision = desired.seekRevision,
            replacementPurpose = InternalReplacementPurpose.STEM_RECOVERY,
            pendingStemPackage = pendingRequest,
            retainedPriorStemSignature = failed.stemPackage?.signature
                ?: failed.retainedPriorStemSignature,
            priorStemPackageStillRetained = failed.stemPackage != null ||
                failed.priorStemPackageStillRetained,
            lastFailedStemRequestRevision = if (pendingRequest == null) {
                failed.lastFailedStemRequestRevision
            } else {
                0L
            }
        )
        try {
            replacement.decoders[0] = openDecoder(
                replacement,
                NativeSongBankRole.CANDIDATE,
                0,
                "original",
                desired.selection.originalLocation,
                frameToMs(currentTimelineFrame(failed))
            )
        } catch (failure: Throwable) {
            if (failed.originalRecoveryRequired) {
                beginRelease(failed)
                settleFailedSelection(desired, "Replacement Original could not open: " +
                    (failure.message ?: failure::class.java.simpleName), failed.ownership)
                return
            }
            pendingRequest?.let {
                emitStemPackageFailed(
                    it,
                    recoverable = true,
                    retainedPriorPackage = failed.stemPackage != null,
                    cause = "Clean Original fallback could not open: " +
                        (failure.message ?: failure::class.java.simpleName)
                )
                failed.lastFailedStemRequestRevision = it.requestRevision
            }
            return
        }
        candidate = replacement
    }

    private fun handleAudibleEnd(event: NativeRuntimeAudibleEndEvent) {
        val delivery = synchronized(manualSelectionSilenceArmGate) {
            val selection = publicSelection?.takeIf { publicOwnership == event.ownership }
                ?: return@synchronized null
            val loopState = explicitLoopState.get()?.takeIf { it.token == selection.token }
            // Clear belongs to the generation where it was published. A later seek has no
            // native direct-loop policy until one is installed there; requiring the retired
            // Clear revision silently discards its EOS and strands Repeat at the final frame.
            // An enabled loop still fences EOS while its new generation is being rebound.
            val expectedPolicyRevision = loopState?.takeIf {
                it.config != null || it.ownership == event.ownership
            }?.nativePolicyRevision ?: 0L
            if (event.directLoopPolicyRevision != expectedPolicyRevision) return@synchronized null
            val record = selected?.takeIf { it.ownership == event.ownership }
            // Source/package admission updates the desired store before its mailbox turn. EOS can
            // arrive in that narrow window, so the latest exact-token intent—not the actor's stale
            // record—must claim the outgoing graph instead of letting queue Stop win.
            val desired = desiredStore.latest()?.takeIf {
                it.selection.token == selection.token
            } ?: record?.desired
            val sourceChangePending = record != null && desired != null &&
                explicitSourceChangePendingAtEnd(
                    playWhenReady = (
                        audibleTransportIntent?.takeIf { it.token == selection.token }
                            ?.playWhenReady ?: desired.playWhenReady
                        ) && !focusSuspended,
                    sourceIntentRevision = desired.sourceIntentRevision,
                    lastAppliedSourceRevision = (record.sourceActivation.applied?.revision ?: 0L),
                    desiredSourceMode = desired.sourceMode,
                    lastAppliedSourceMode = record.sourceActivation.applied?.mode
                )
            NativeEngineControllerEvent.AudibleEnd(
                    selection.token,
                    event.sequence,
                    event.ownership,

                    sourceChangePending
                )
            // Queue/repeat/shuffle policy remains owned by PlaybackQueueController. The likely-next
            // Original stays prewarmed, but only one explicit adapter decision may claim/select it.
        }
        delivery?.let(listener::onEvent)
    }

    private fun publishInPlaceMetadataOnActor() {
        val selection = publicSelection ?: return
        val update = metadataByToken[selection.token.value] ?: return
        if (update.audioRevision != selection.audioRevision ||
            update.revision <= lastPublishedMetadataRevision
        ) return
        lastPublishedMetadataRevision = update.revision
        listener.onEvent(NativeEngineControllerEvent.MetadataUpdated(update))
    }

    private fun setPublicSelectionOnActor(selection: EngineSelection?) {
        publicSelection = selection
        publicSelectionSnapshot.set(selection)
    }

    private fun pruneTokenMetadataOnActor() {
        if (metadataByToken.isEmpty()) return
        val retained = linkedSetOf<Long>()
        desiredStore.latest()?.selection?.token?.value?.let(retained::add)
        ownerGate.latest()?.token?.value?.let(retained::add)
        publicSelection?.token?.value?.let(retained::add)
        sequenceOf(active, candidate, cacheBuilder).filterNotNull().distinct().forEach { record ->
            record.selection?.token?.value?.let(retained::add)
            record.reservation?.token?.value?.let(retained::add)
        }
        releases.records.forEach { record ->
            record.selection?.token?.value?.let(retained::add)
            record.reservation?.token?.value?.let(retained::add)
        }
        metadataByToken.keys.removeIf { it !in retained }
    }

    private fun settleLikelyNextPrewarmFailure(record: BankRecord,
        cause: String = "Original ended without playable PCM") {
        val reservation = record.reservation?.takeIf { record.selection == null } ?: return
        automaticEndPolicy.failed(record.identity, runtimeIncarnation)
        preparationEvidence.finish(NativeControllerPreparationOperation.SUCCESSOR_PREWARM,
            record.ownership.selectionToken, runtimeIncarnation, "failed", scheduler.monotonicTimeMs(), cause)
        ownerGate.cancelReservation(reservation)
    }

    private fun ensureLikelyNextPrewarmOnActor() {
        if (runtimeStopped || stopping || closing || releases.isNotEmpty() ||
            active?.originalRecoveryRequired == true) return
        if (preemptCacheBuilderForNextOwnerDeadline()) return
        val likely = retainedLikelyNextItemOnActor()
            // A same-identity owner is a restart, never permission to allocate a second bank.
            ?.takeUnless { it.identity == selected?.identity }
        if (likely == null) {
            candidate?.takeIf { it.selection == null && it.bank !in releases }?.let { stale ->
                stale.reservation?.let(ownerGate::cancelReservation)
                beginRelease(stale)
            }
            return
        }
        val existing = candidate
        if (existing?.selection != null) return
        if (existing?.identity == likely.identity) {
            existing.reservation?.let { retainNewerMetadata(it.token, likely.metadata) }
            return
        }
        if (existing != null) {
            existing.reservation?.let(ownerGate::cancelReservation)
            beginRelease(existing)
            return
        }
        if (!automaticEndPolicy.mayPrewarm(likely.identity, runtimeIncarnation)) return
        val bank = freeBank() ?: return
        val reservation = ownerGate.reserve(likely.identity)
        retainNewerMetadata(reservation.token, likely.metadata)
        val record = BankRecord(
            bank = bank,
            identity = likely.identity,
            ownership = NativeRuntimeOwnership(
                reservation.token.value,
                allocateGeneration()
            ),
            reservation = reservation,
            selection = null,
            desired = null
        )
        preparationEvidence.begin(NativeControllerPreparationOperation.SUCCESSOR_PREWARM,
            record.ownership, runtimeIncarnation, "original-coverage", scheduler.monotonicTimeMs())
        try {
            record.decoders[0] = openDecoder(
                record,
                NativeSongBankRole.CANDIDATE,
                0,
                "original",
                likely.identity.originalLocation,
                (likely.identity.playbackStartMs - 1L).coerceAtLeast(0L)
            )
        } catch (failure: Throwable) {
            ownerGate.cancelReservation(reservation)
            automaticEndPolicy.failed(likely.identity, runtimeIncarnation)
            preparationEvidence.finish(NativeControllerPreparationOperation.SUCCESSOR_PREWARM,
                record.ownership.selectionToken, runtimeIncarnation, "failed", scheduler.monotonicTimeMs(), failure.message)
            emitFailure("prewarm", failure.message ?: "Likely-next Original failed to open")
            return
        }
        automaticEndPolicy.succeeded(likely.identity, runtimeIncarnation)
        candidate = record
    }

    private fun preemptCacheBuilderForNextOwnerDeadline(): Boolean {
        val builder = cacheBuilder?.takeIf { it.assigned && it.bank !in releases }
            ?: return false
        val record = active ?: return false
        if (!nextOwnerNeedsSpareBank(record)) return false
        beginRelease(builder)
        return true
    }

    private fun nextOwnerNeedsSpareBank(record: BankRecord): Boolean {
        val next = (automaticEndPlan.plan as? NativeEngineAutomaticEndPlan.NextOwner)?.item
            ?: return false
        if (record !== selected || !record.assigned || record.ownership != publicOwnership ||
            record.identity == next.identity || !effectivePlaying(record)
        ) return false
        if (environment.isCurrentSongCacheAllLanesSealed(record.ownership)) return false
        val position = environment.controlProgress().presentedPosition?.takeIf {
            it.ownership == record.ownership
        } ?: return false
        val desired = record.desired ?: return false
        val remainingMediaMs = (record.identity.playbackEndMs -
            frameToMs(position.lastTimelineFrame)).coerceAtLeast(0L)
        if (remainingMediaMs.toDouble() / desired.tempo.toDouble() >
            LOCAL_START_SETTLEMENT_TIMEOUT_MS.toDouble()
        ) return false
        return true
    }

    private fun retainedLikelyNextItemOnActor(): NativeEngineQueueItem? =
        automaticEndPolicy.retainedItem(automaticEndPlan.plan, manualPrewarm.request,
            ownerGate.latest()?.token)

    private fun effectiveAutomaticSuccessorItemOnActor(): NativeEngineQueueItem? =
        automaticEndPolicy.successor(automaticEndPlan.plan)

    /**
     * Assigns a primed likely-next decoder to the spare native pipeline without selecting it.
     * Public/latest ownership remains the outgoing record until exact callback boundary evidence.
     */
    private fun reconcileAutomaticSuccessorOnActor() {
        if (runtimeStopped || stopping || closing || releases.isNotEmpty()) return
        val outgoing = selected?.takeIf {
            it === active && it.assigned
        } ?: return
        if (outgoing.desired == null) return
        val dormantCandidate = candidate?.takeIf {
            it.selection == null && it.reservation != null && it.originalPrimed &&
                it.bank !in releases
        }
        val successor = dormantCandidate?.takeIf {
            it.identity == effectiveAutomaticSuccessorItemOnActor()?.identity &&
                it.identity != outgoing.identity
        }
        // Keep the next owner prepared under A/B; native alone knows when that loop is dormant
        // past B and parks the physical drain until then.
        val authorized = successor != null && outgoing.ownership == publicOwnership &&
            effectivePlaying(outgoing) &&
            automaticEndPlan.revision > 0L

        if (!authorized) {
            (successor ?: dormantCandidate)
                ?.takeIf { it.automaticSuccessorRevision != 0L }
                ?.let {
                val rebase = prepareSeek(it, it.identity.playbackStartMs)
                it.ownership = rebase.ownership
                it.ingressEpoch = rebase.ownership.generation
                it.originalPrimed = false
                it.automaticSuccessorRevision = 0L
                it.intentRevision = nextBankRevision(it.bank)
                bankIntents[it.bank.nativeCode] = assignedBankIntent(
                    record = it,
                    revision = it.intentRevision,
                    firstTimelineFrame = msToFrame(it.identity.playbackStartMs),
                    outputClockStartFrame = 0L
                )
                publishSnapshotOnActor()
                if (!authorizeSeek(rebase)) beginRelease(it)
            }
            return
        }
        val exact = requireNotNull(successor)
        // Native's drain-stage open deliberately advances the output coordinator's producer
        // high-water generation to this exact successor while the outgoing ring remains audible.
        // Once the candidate command is assigned, comparing it to that high-water mark would
        // mistake its own physical stage for a stale prewarm and re-seek it on every actor poll.
        // Only an as-yet unassigned candidate can be stale relative to the output high-water.
        if (exact.ownership.generation <= outgoing.ownership.generation ||
            (!exact.assigned && exact.ownership.generation <= environment.outputGeneration)
        ) {
            val rebase = prepareSeek(exact, exact.identity.playbackStartMs)
            exact.ownership = rebase.ownership
            exact.ingressEpoch = rebase.ownership.generation
            exact.originalPrimed = false
            exact.automaticSuccessorRevision = 0L
            exact.assigned = true
            exact.intentRevision = nextBankRevision(exact.bank)
            bankIntents[exact.bank.nativeCode] = assignedBankIntent(
                record = exact,
                revision = exact.intentRevision,
                firstTimelineFrame = msToFrame(exact.identity.playbackStartMs),
                outputClockStartFrame = 0L
            )
            publishSnapshotOnActor()
            if (!authorizeSeek(rebase)) beginRelease(exact)
            return
        }
        var changed = false
        if (!exact.assigned) {
            exact.assigned = true
            exact.intentRevision = nextBankRevision(exact.bank)
            bankIntents[exact.bank.nativeCode] = assignedBankIntent(
                record = exact,
                revision = exact.intentRevision,
                firstTimelineFrame = msToFrame(exact.identity.playbackStartMs),
                outputClockStartFrame = 0L
            )
            changed = true
        }
        if (exact.automaticSuccessorRevision != automaticEndPlan.revision) {
            exact.automaticSuccessorRevision = automaticEndPlan.revision
            changed = true
        }
        if (changed) publishSnapshotOnActor()
    }

    private fun recoverRouteOnActor() {
        var recovery = routeRecoveryTransaction
        if (recovery == null) {
            if (runtimeStopped && !routeOutputNeedsReopen && deferredDesired == null) return
            if (!runtimeStopped) {
                if (!cancelPreparationOnActor(abortNative = true)) {
                    routeRecoveryRequested.set(true)
                    return
                }
                loopPreparation.clearRefill()
                val target = selected?.takeIf { it.assigned && it.bank !in releases }
                val publicRecord = sequenceOf(active, candidate).filterNotNull().firstOrNull {
                    it.assigned && it.bank !in releases && it.ownership == publicOwnership
                }
                val positionMs = environment.controlProgress().presentedPosition
                    // The public owner may still be the old generation while an in-place Seek or
                    // replacement generation is waiting for presentation. Applying that old
                    // cursor to a pending Seek would silently undo the user's latest request. A
                    // same-token stem-recovery replacement is the one exception: it deliberately
                    // shadows the outgoing audible graph and must inherit that public cursor.
                    ?.takeIf { evidence ->
                        evidence.ownership == target?.ownership ||
                            (target?.replacementPurpose ==
                                InternalReplacementPurpose.STEM_RECOVERY &&
                                publicRecord != null &&
                                evidence.ownership == publicOwnership &&
                                evidence.ownership.selectionToken ==
                                target.ownership.selectionToken &&
                                target.identity == publicRecord.identity &&
                                target.lastSeekRevision == publicRecord.lastSeekRevision)
                    }
                    ?.let { frameToMs(it.lastTimelineFrame) }
                    ?: target?.desired?.requestedPositionMs
                    ?: deferredDesired?.requestedPositionMs
                    ?: 0L

                // Required dead-callback-safe order: render worker join, close-first AudioTrack reopen,
                // then a fresh runtime. Decoder queues remain allocated while the route settles.
                generationCursor = maxOf(
                    generationCursor,
                    environment.engageImmediateSilenceGate()
                )
                environment.stopRuntimeAndJoin()
                runtimeStopped = true
                routeOutputNeedsReopen = true
                recovery = RouteRecoveryTransaction(target, positionMs)
                routeRecoveryTransaction = recovery
            } else {
                recovery = RouteRecoveryTransaction(
                    target = selected?.takeIf { it.assigned && it.bank !in releases },
                    positionMs = deferredDesired?.requestedPositionMs
                        ?: selected?.desired?.requestedPositionMs
                        ?: 0L,
                    phase = if (routeOutputNeedsReopen) RouteRecoveryPhase.REOPENING_OUTPUT
                        else RouteRecoveryPhase.REBUILDING_RUNTIME
                )
                routeRecoveryTransaction = recovery
            }
        }

        if (recovery.phase == RouteRecoveryPhase.REOPENING_OUTPUT ||
            recovery.phase == RouteRecoveryPhase.RETIRING_OUTPUT || routeOutputNeedsReopen) {
            val outputReopened = if (recovery.phase == RouteRecoveryPhase.RETIRING_OUTPUT || !hasPhysicalPlayDemand()) {
                // A paused owner needs the exact consumer fence, not a replacement physical sink.
                environment.setOutputSessionSuspended(true)
                // requestClose is irreversible: newer selection/Play still owes this exact fence.
                recovery.phase = RouteRecoveryPhase.RETIRING_OUTPUT
                val retired = environment.retireOutputForStop(allocateGeneration())
                if (retired == null) return
                generationCursor = maxOf(generationCursor, retired)
                physicalReopenBeforePlay = true
                true
            } else synchronized(manualSelectionSilenceArmGate) {
                environment.reopenOutputForRouteChange().also { reopened ->
                    if (reopened) {
                        // Reopen installs a fresh empty/paused stream and clears the native manual
                        // gate. A later selector arms only this replacement stream.
                        manualSelectionSilenceBurst.set(null)
                    }
                }
            }
            // Native force-resets to the attempted generation before opening the stream. Even a
            // failed open therefore advances the physical high-water mark.
            generationCursor = maxOf(generationCursor, environment.outputGeneration)
            if (!outputReopened) {
                reportRouteRecoveryFailureOnce(
                    recovery,
                    "The 48 kHz output route is not ready; retrying while playback stays silent"
                )
                routeOutputNeedsReopen = true
                return
            }
            routeOutputNeedsReopen = false
            recovery.phase = RouteRecoveryPhase.REBUILDING_RUNTIME
            recovery.lastReportedFailure = null

            recovery.seekPlans = sequenceOf(active, candidate, cacheBuilder)
                .filterNotNull()
                .distinct()
                .mapNotNull { record ->
                    val pendingReleaseRevision = releases.unassignmentRevision(record.bank)
                    if (pendingReleaseRevision != null) {
                        // Retirement waits for one exact unassignment revision. Recreating the
                        // runtime must replay that revision, not mint N+1 and wait forever for an
                        // acknowledgement native can no longer produce for N.
                        record.intentRevision = pendingReleaseRevision
                        bankIntents[record.bank.nativeCode] =
                            NativeRuntimeBankIntent.unassigned(record.intentRevision)
                        return@mapNotNull null
                    }
                    if (!record.assigned) return@mapNotNull null
                    record.intentRevision = nextBankRevision(record.bank)
                    val target = recovery.target
                    val sameTokenReplacementSibling = target != null && record !== target &&
                        (target.replacementPurpose == InternalReplacementPurpose.STEM_RECOVERY ||
                            record.replacementPurpose ==
                                InternalReplacementPurpose.STEM_RECOVERY) &&
                        record.identity == target.identity &&
                        record.selection?.token == target.selection?.token
                    val retainedPosition = if (record === target || sameTokenReplacementSibling) {
                        recovery.positionMs
                    } else {
                        record.identity.playbackStartMs
                    }
                    val seekPosition = if (record === cacheBuilder) 0L else nativeRenderablePositionMs(
                        retainedPosition.toDouble(), record.identity.durationMs,
                        record.identity.playbackStartMs, record.identity.playbackEndMs)
                    if (seekPosition != 0L) record.frameZeroCacheBuilderEligible = false
                    val plan = prepareSeek(record, seekPosition)
                    record.ownership = if (record === recovery.target &&
                        plan.ownership.generation < environment.outputGeneration
                    ) {
                        throw IllegalStateException(
                            "Route generation moved beyond selected decoder mapping"
                        )
                    } else {
                        plan.ownership
                    }
                    record.ingressEpoch = record.ownership.generation
                    bankIntents[record.bank.nativeCode] = assignedBankIntent(
                        record,
                        record.intentRevision,
                        msToFrame(seekPosition),
                        0L
                    )
                    plan.copy(ownership = record.ownership)
                }
                .toList()
        }

        if (recovery.target == null &&
            (!unselectedEpochOpen || unselectedOutputGeneration <= environment.outputGeneration)
        ) {
            // A fresh runtime must first receive one explicit empty command newer than the
            // physical output, whether this is a close-first route reopen or an ordinary restart
            // after administrative STOP. Otherwise the next owner can be rejected before decode.
            unselectedOutputGeneration = allocateGeneration()
            unselectedEpochOpen = true
        }

        if (!environment.recreateRuntime()) {
            if (environment.needsOutputRecovery()) {
                routeOutputNeedsReopen = true
                recovery.phase = RouteRecoveryPhase.REOPENING_OUTPUT
            }
            reportRouteRecoveryFailureOnce(
                recovery,
                "The native render runtime could not restart after the route change; retrying"
            )
            return
        }
        runtimeIncarnation = increment(runtimeIncarnation, "native runtime incarnation")
        runtimeStopped = false
        // The replacement runtime owns a fresh fixed loop cache/protocol. Stable user identity and
        // revision remain unchanged, but the sibling intent and sequential cache head must replay.
        resetManagedLoopRuntimeAdoption()
        // End/refill counters are runtime-local. Presentation/position sequences belong to the
        // persistent output and must remain monotonic across route recreation.
        controlProgressObserver.resetEndSequence()
        selected = recovery.target?.takeIf { it.assigned && it.bank !in releases }
        routeAwaitingOwnership = selected?.ownership
        // Publish even when a newer manual owner invalidated the old selection during recovery.
        // The fresh runtime still needs the exact unassigned bank revisions so release acks can
        // drain and the conflated latest owner can claim a bank.
        publishSnapshotOnActor(forcePlaying = false)
        recovery.seekPlans.forEach { plan ->
            if (!authorizeSeek(plan)) {
                val desired = plan.record.desired?.let { previous ->
                    desiredStore.latest()?.takeIf {
                        it.selection.token == previous.selection.token &&
                            it.seekRevision > previous.seekRevision
                    } ?: previous.copy(requestedPositionMs = frameToMs(
                        bankIntents[plan.record.bank.nativeCode].firstTimelineFrame))
                }
                if (desired != null) handleSeekFailStop(plan.record, desired)
                else beginRelease(plan.record)
            } else {
                publishBeatGridIfPresent(plan.record)
            }
        }
        routeRecoveryTransaction = null
    }

    private fun reportRouteRecoveryFailureOnce(
        recovery: RouteRecoveryTransaction,
        diagnostic: String
    ) {
        if (recovery.lastReportedFailure == diagnostic) return
        recovery.lastReportedFailure = diagnostic
        emitFailure("route-recovery", diagnostic)
    }

    private fun resetManagedLoopRuntimeAdoption() {
        // Render-readiness sequences are owned by one runtime instance and restart from zero when
        // that instance is recreated for an audio-route change. Keeping the prior high-water mark
        // would permanently reject every proof from the fresh runtime and strand a requested
        // Stems source on Original after Bluetooth/USB route recovery.
        controlProgressObserver.invalidateReadiness()
        managedLoopIntent = null
        loopPreparation.clearRequests()
        repeatOneEffective = false
        loopPreparation.clearRefill()
        lastLoopIngressReadySequence = 0L
    }

    private fun finishStopOrCloseIfReady() {
        if (fatalQuarantine) return
        if (!stopping || releases.isNotEmpty() || loopPreparation.preparing) return
        if (!runtimeStopped) {
            environment.stopRuntimeAndJoin()
            runtimeStopped = true
        }
        // Runtime retirement alone cannot retire queued output or a failed Java consumer.
        // This exact sink/consumer fence runs even when route recovery already joined the runtime.
        val stoppedGeneration = maxOf(generationCursor, environment.outputGeneration)
        check(stoppedGeneration < Long.MAX_VALUE) { "PCM generation space exhausted" }
        generationCursor = environment.retireOutputForStop(stoppedGeneration + 1L) ?: return
        physicalReopenBeforePlay = true
        routeOutputNeedsReopen = true
        active = null
        candidate = null
        cacheBuilder = null
        selected = null
        stopping = false
        activeStop?.let { request ->
            val status = synchronized(manualSelectionSilenceArmGate) {
                // select() increments the epoch and installs owner/desired state under this same
                // lock. Keep the comparison and conditional clears indivisible: otherwise a new
                // C can finish between the equality read and these clears, then be erased by a
                // stale STOP while its callback gate remains closed.
                // Another STOP may also supersede this receipt while its decoders retire.
                val completionStatus = if (!request.isComplete() && selectionEpoch.get() == request.selectionEpoch) {
                    desiredStore.clear()
                    ownerGate.invalidate()
                    // Queue policy belongs to the stopped session just as strictly as desired
                    // controls do. Retaining a consumed NextOwner here can stage its old candidate
                    // beside an unrelated one-song queue after a same-process restart.
                    automaticEndPlanUpdate.set(null)
                    val clearedPlan = AutomaticEndPlanSnapshot(
                        queueSequence.updateAndGet { current ->
                            increment(current, "automatic-end plan stop clear")
                        },
                        null
                    )
                    requestedAutomaticEndPlan.set(clearedPlan)
                    automaticEndPlan = clearedPlan
                    manualPrewarmUpdate.set(null)
                    val clearedPrewarm = ManualPrewarmSnapshot(
                        queueSequence.updateAndGet { current ->
                            increment(current, "manual prewarm stop clear")
                        },
                        null
                    )
                    requestedManualPrewarm.set(clearedPrewarm)
                    manualPrewarm = clearedPrewarm
                    suppressedAutomaticEndPlanRevision = -1L
                    // A final transport/focus command can already be in the mailbox immediately
                    // after STOP. It is not a new selection. Retaining that deferred snapshot
                    // would recreate the deliberately joined runtime with no public owner.
                    deferredDesired = null
                    audibleTransportIntent = null
                    NativeEngineStopStatus.COMPLETED
                } else {
                    NativeEngineStopStatus.SUPERSEDED
                }
                request.complete(completionStatus)
                completionStatus
            }
            pendingStop.compareAndSet(request, null)
            if (status == NativeEngineStopStatus.COMPLETED) {
                listener.onEvent(NativeEngineControllerEvent.StopCompleted(request.id))
            }
        }
        activeStop = null
        if (closing) {
            environment.close()
            listener.onEvent(NativeEngineControllerEvent.Closed)
            closeCompleted.countDown()
        }
    }

    private fun reconcileStemMixPreviewOnActor(refreshReadiness: Boolean): Boolean {
        val authority = stemMixPreviewMailbox.authority()
        val record = selected?.takeIf { it.assigned && it.bank !in releases }
        val desired = record?.desired
        val authorityValid = authority != null && record != null && desired != null &&
            record.ownership == authority.ownership &&
            publicOwnership == authority.ownership &&
            desired.selection.token == authority.token &&
            desired.sourceMode == EngineSourceMode.STEMS &&
            record.stemPackage?.signature == authority.packageSignature &&
            record.stemIntentRevision == authority.packageRequestRevision &&
            if (refreshReadiness) {
                stemsReady(record)
            } else {
                cachedStemMixPreviewReady(record, desired, authority)
            }
        if (!authorityValid) {
            if (authority != null) stemMixPreviewMailbox.clear()
            val changed = actorStemMixPreviewMask != 0
            // If readiness vanished for the same exact package, retain its authority just long
            // enough to publish a zero-mask native retirement. Owner/package changes are retired
            // by the canonical control/reset boundary and must not be rebound to the new graph.
            actorStemMixPreviewAuthority = authority?.takeIf {
                changed && record?.ownership == it.ownership &&
                    record.stemPackage?.signature == it.packageSignature &&
                    record.stemIntentRevision == it.packageRequestRevision
            }
            actorStemMixPreviewMask = 0
            if (!changed) {
                actorStemMixPreviewSequence = 0L
            }
            return changed
        }
        val exactAuthority = requireNotNull(authority)
        var changed = false
        if (actorStemMixPreviewAuthority !== exactAuthority) {
            // Authority replacement is also a native graph fence (owner, package request, or
            // selection changed). Never reinterpret the previous authority's live mask as a
            // zero-mask publication for the replacement: the replacement has not offered a value
            // yet, so doing so would publish coversOfferSequence=0 and native must reject it.
            // The canonical owner/package revision retires the prior overlay independently.
            actorStemMixPreviewAuthority = exactAuthority
            actorStemMixPreviewMask = 0
            actorStemMixPreviewSequence = 0L
        }
        val activeMask = stemMixPreviewMailbox.activeMask(exactAuthority)
        val retiredMask = actorStemMixPreviewMask and activeMask.inv()
        if (retiredMask != 0) {
            actorStemMixPreviewMask = actorStemMixPreviewMask and activeMask
            changed = true
        }
        if (stemMixPreviewMailbox.readLatestInto(stemMixPreviewSnapshot) &&
            stemMixPreviewSnapshot.authority === exactAuthority
        ) {
            var lane = 0
            while (lane < ENGINE_STEM_MIX_PREVIEW_LANE_COUNT) {
                val bit = 1 shl lane
                if (stemMixPreviewSnapshot.changedMask and bit != 0 &&
                    activeMask and bit != 0 &&
                    stemMixPreviewSnapshot.laneEpochs[lane] ==
                    stemMixPreviewMailbox.activeLaneEpoch(exactAuthority, lane)
                ) {
                    val gain = stemMixPreviewSnapshot.gains[lane]
                    if (actorStemMixPreviewMask and bit == 0 ||
                        actorStemMixPreviewGains[lane].toRawBits() != gain.toRawBits()
                    ) {
                        actorStemMixPreviewGains[lane] = gain
                        actorStemMixPreviewMask = actorStemMixPreviewMask or bit
                        changed = true
                    }
                }
                lane++
            }
            // A fast DOWN/MOVE/UP can retire its lane before this actor ever reads the offer. The
            // canonical terminal command still supersedes it, so publish the resulting full
            // zero/remaining-mask state through the exact offer sequence.
            actorStemMixPreviewSequence = stemMixPreviewSnapshot.sequence
            changed = true
        }
        return changed
    }

    /**
     * A preview task may reuse only the exact readiness proof produced by the normal health actor.
     * Owner, generation, package, source intent, or decoder failure invalidates that proof on the
     * normal actor before another source/control publication can become authoritative.
     */
    private fun cachedStemMixPreviewReady(
        record: BankRecord,
        desired: EngineDesiredState,
        authority: EngineStemMixPreviewAuthority
    ): Boolean = record.ownership == authority.ownership &&
        record.stemPackage?.signature == authority.packageSignature &&
        record.stemIntentRevision == authority.packageRequestRevision &&
        cachedStemSourceReady(record, desired)

    private fun cachedStemSourceReady(
        record: BankRecord,
        desired: EngineDesiredState
    ): Boolean {
        if (cacheBackedStemsReady(record) && desired.sourceMode == EngineSourceMode.STEMS &&
            stemPackageRequest(record, desired)?.signature == record.stemPackage?.signature
        ) return true
        val readiness = publishedStemReadiness ?: return false
        val packageSignature = record.stemPackage?.signature ?: return false
        val nativeReady = environment.requiredNativeStemRenderAheadFrames <= 0L ||
            (record.stemReadiness.readyGeneration == record.ownership.generation &&
                record.stemReadiness.readyPackageSignature == packageSignature)
        return readiness.ready && readiness.selectionToken == desired.selection.token.value &&
            readiness.ownership == record.ownership &&
            readiness.stemPackageSignature == packageSignature &&
            readiness.sourceIntentRevision == record.sourceIntentRevision &&
            readiness.desiredSourceMode == EngineSourceMode.STEMS &&
            nativeReady
    }

    private fun publishSnapshotOnActor(forcePlaying: Boolean? = null) {
        if (runtimeStopped) return
        val selectedRecord = selected?.takeIf { it.assigned }
        publishedStemReadiness = publishedStemReadiness?.takeIf { readiness ->
            selectedRecord != null &&
                readiness.selectionToken == selectedRecord.selection?.token?.value &&
                readiness.ownership == selectedRecord.ownership
        }
        if (routeAwaitingOwnership != null && selectedRecord != null &&
            routeAwaitingOwnership != selectedRecord.ownership
        ) {
            // Route recovery is an output transaction, not permission to pin one obsolete decoder
            // generation. A Seek or newer selected graph becomes the exact owner that must settle
            // before the route can resume.
            routeAwaitingOwnership = selectedRecord.ownership
        }
        val desired = selectedRecord?.desired
        val controls = buildControls(selectedRecord, desired)
        val playing = forcePlaying ?: (selectedRecord?.let(::effectivePlaying) == true &&
            routeAwaitingOwnership == null)
        val automaticSuccessor = candidate?.takeIf { successor ->
            playing && selectedRecord != null && selectedRecord === active &&
                successor !== selectedRecord &&
                successor.assigned && successor.selection == null &&
                successor.reservation != null && successor.automaticSuccessorRevision > 0L &&
                successor.identity == effectiveAutomaticSuccessorItemOnActor()?.identity &&
                successor.identity != selectedRecord.identity &&
                successor.ownership.generation > selectedRecord.ownership.generation
        }?.let { successor ->
            NativeRuntimeAutomaticSuccessorIntent(
                revision = successor.automaticSuccessorRevision,
                outgoingOwnership = requireNotNull(selectedRecord).ownership,
                successorBank = successor.bank,
                successorOwnership = successor.ownership,
                inheritOutgoingControls = false
            )
        }
        val outputGeneration = selectedRecord?.ownership?.generation
            ?: unselectedOutputGeneration.takeIf { it > 0L }
            ?: error("An unselected snapshot requires a deliberately newer invalidation epoch")
        val snapshot = NativeRuntimeCommandSnapshot(
            revision = nextCommandRevision(),
            outputGeneration = outputGeneration,
            banks = bankIntents.toList(),
            selectedBank = selectedRecord?.bank,
            playing = playing,
            controls = controls,
            // Command-v2 loop authority is intentionally retired after managed-loop adoption.
            automaticSuccessor = automaticSuccessor
        )
        val publishStatus = environment.publish(snapshot)
        when (publishStatus) {
            NativeRuntimePublishStatus.ACCEPTED -> {
                updateOutputWarmStateOnAcceptedSnapshot(selectedRecord, playing)
                acceptedCommandOutputGeneration = maxOf(
                    acceptedCommandOutputGeneration,
                    snapshot.outputGeneration
                )
                if (selectedRecord != null) unselectedEpochOpen = false
                if (selectedRecord != null && desired != null) {
                    reconcileManagedLoopOnActor(selectedRecord, desired)
                    val sourcePublication = rememberSourceControlPublication(selectedRecord, desired, controls)
                    check(selectedRecord.sourceActivation.publish(publishStatus, sourcePublication, desired.revision))
                    if (selectedRecord.sourceActivation.prepare(selectedRecord.ownership, playing)) {
                        listener.onEvent(
                            NativeEngineControllerEvent.SourceModeReadyAwaitingAudible(
                                desired.selection.token,
                                selectedRecord.sourceIntentRevision,
                                desired.sourceMode
                            )
                        )
                    }
                    if (controls.sourceMode.toEngineSourceMode() != desired.sourceMode &&
                        publicSelection?.token == desired.selection.token &&
                        selectedRecord.lastSourceNotReadyRevision < selectedRecord.sourceIntentRevision
                    ) {
                        selectedRecord.lastSourceNotReadyRevision = selectedRecord.sourceIntentRevision
                        listener.onEvent(
                            NativeEngineControllerEvent.SourceModeNotReady(
                                desired.selection.token,
                                selectedRecord.sourceIntentRevision,
                                desired.sourceMode,
                                "The requested source is not yet contiguous in every required lane"
                            )
                        )
                    }
                }
            }
            NativeRuntimePublishStatus.STALE_REVISION,
            NativeRuntimePublishStatus.INVALID_SNAPSHOT,
            NativeRuntimePublishStatus.STOPPED -> throw IllegalStateException(
                commandRejectionDiagnostic(publishStatus, snapshot)
            )
        }
    }

    /**
     * Publishes one complete selected-owner gain overlay without constructing a transport command.
     * Kotlin has already validated authority and lane epochs; native publicationSequence provides
     * the remaining latest-only ordering fence for active and zero-mask retirement states.
     */
    private fun publishStemMixPreviewOnActor() {
        val authority = actorStemMixPreviewAuthority ?: return
        // DOWN alone owns no native value. This also makes authority replacement fail closed if a
        // future reconciliation path accidentally reports a state change before its first offer.
        if (actorStemMixPreviewSequence <= 0L) return
        val record = selected?.takeIf { it.assigned && it.bank !in releases } ?: return
        val desired = record.desired ?: return
        val canonical = lastRuntimeControls ?: return
        if (record.ownership != authority.ownership ||
            desired.selection.token != authority.token ||
            record.stemPackage?.signature != authority.packageSignature ||
            record.stemIntentRevision != authority.packageRequestRevision ||
            canonical.sourceIntentRevision != record.sourceIntentRevision ||
            canonical.stemIntentRevision != record.stemIntentRevision ||
            (actorStemMixPreviewMask != 0 &&
                (desired.sourceMode != EngineSourceMode.STEMS ||
                    canonical.sourceMode != NativeRuntimeSourceMode.STEMS))
        ) return

        nativeStemMixPreviewPublicationSequence = increment(
            nativeStemMixPreviewPublicationSequence,
            "native stem-mix preview publication"
        )
        val controlFloor = canonical.revision
        val status = environment.publishStemGainPreview(
            publicationSequence = nativeStemMixPreviewPublicationSequence,
            coversOfferSequence = actorStemMixPreviewSequence,
            ownership = authority.ownership,
            sourceIntentRevision = record.sourceIntentRevision,
            stemIntentRevision = record.stemIntentRevision,
            canonicalControlFloorRevision = controlFloor,
            activeMask = actorStemMixPreviewMask,
            gains = actorStemMixPreviewGains
        )
        if (status != NativeRuntimePublishStatus.ACCEPTED) {
            throw IllegalStateException(
                "Native stem-gain preview publication failed status=$status " +
                    "publication=$nativeStemMixPreviewPublicationSequence " +
                    "covers=$actorStemMixPreviewSequence owner=${authority.ownership}"
            )
        }
    }

    private fun commandRejectionDiagnostic(
        status: NativeRuntimePublishStatus,
        snapshot: NativeRuntimeCommandSnapshot
    ): String =
        buildString {
            append("Native runtime command publication failed status=")
            append(status)
            append(" revision=")
            append(snapshot.revision)
            append(" outputGeneration=")
            append(snapshot.outputGeneration)
            append(" acceptedOutputHighWater=")
            append(acceptedCommandOutputGeneration)
            append(" appliedOutputGeneration=")
            append(environment.outputGeneration)
            append(" selectedBank=")
            append(snapshot.selectedBank)
            append(" selectedOwnership=")
            append(snapshot.selectedOwnership)
            append(" bankOwnerships=")
            append(snapshot.banks.map { intent ->
                if (intent.assigned) intent.ownership else null
            })
        }

    private fun rememberSourceControlPublication(
        record: BankRecord,
        desired: EngineDesiredState,
        controls: NativeRuntimeControls
    ): SourceControlPublication {
        val packageRequest = record.stemPackage?.takeIf { physical ->
            controls.sourceMode == NativeRuntimeSourceMode.STEMS &&
                stemsReady(record)
        }?.let { physical ->
            // A revision-only enrichment update retains the same PCM graph. Correlate this native
            // control publication to the newly resolved package request before reconciliation
            // updates the record, while source-only changes keep the existing package revision.
            stemPackageRequest(record, desired)?.takeIf {
                it.signature == physical.signature
            } ?: physical
        }
        val publication = SourceControlPublication(
            nativeControlRevision = controls.revision,
            ownership = record.ownership,
            token = desired.selection.token,
            desiredRevision = record.sourceIntentRevision,
            requestedMode = desired.sourceMode,
            nativeMode = controls.sourceMode,
            stemPackageIdentity = packageRequest?.signature?.let(::persistedStemPackageIdentity),
            stemRevision = packageRequest?.stemRevision,
            stemRequestRevision = packageRequest?.requestRevision
        )
        if (sourceControlPublications.lastOrNull() == publication) return publication
        sourceControlPublications.addLast(publication)
        while (sourceControlPublications.size > MAXIMUM_SOURCE_CONTROL_PUBLICATIONS) {
            sourceControlPublications.removeFirst()
        }
        publishedSourceControlPublications = (
            publishedSourceControlPublications + NativeSourceControlPublicationDiagnostics(
                nativeControlRevision = publication.nativeControlRevision,
                ownership = publication.ownership,
                selectionToken = publication.token.value,
                desiredRevision = publication.desiredRevision,
                requestedMode = publication.requestedMode,
                nativeMode = publication.nativeMode,
                stemPackageSignature = packageRequest?.signature,
                stemRevision = publication.stemRevision,
                stemRequestRevision = publication.stemRequestRevision,
                nativeControls = controls,
                stemLaneIds = record.stemPackage?.canonicalLanes.orEmpty().map(EngineStemLane::id)
            )
        ).takeLast(MAXIMUM_DIAGNOSTIC_SOURCE_CONTROL_PUBLICATIONS)
        return publication
    }

    private fun buildControls(
        record: BankRecord?,
        desired: EngineDesiredState?
    ): NativeRuntimeControls {
        val ownership = if (record != null && desired != null) record.ownership else null
        val sourceIntentRevision = if (ownership == null) 0L else requireNotNull(record).sourceIntentRevision
        val stemIntentRevision = if (ownership == null) 0L else requireNotNull(record).stemIntentRevision
        check(
            ownership == null || (sourceIntentRevision > 0L && stemIntentRevision > 0L)
        ) { "A selected native owner requires positive source and stem intent revisions" }
        val draft = if (record == null || desired == null) {
            NativeRuntimeControls(revision = 1L)
        } else {
            val canonical = record.stemPackage?.canonicalLanes
            val controlsForPackage = if (canonicalEngineStemLanes(desired.stemLanes) == canonical) {
                desired.stemControls
            } else record.physicalStemControls
            val allStemDecodersReady = record.stemPackage != null && stemsReady(record)
            val sourceMode = if (desired.sourceMode == EngineSourceMode.STEMS &&
                canonical != null && allStemDecodersReady
            ) NativeRuntimeSourceMode.STEMS else NativeRuntimeSourceMode.ORIGINAL
            val stemControls = List(NativeRuntimeControls.STEM_COUNT) { index ->
                val lane = canonical?.getOrNull(index)
                if (lane == null) NativeRuntimeStemControl(gain = 0f, muted = true) else {
                    val control = controlsForPackage[lane.id] ?: EngineStemControl()
                    NativeRuntimeStemControl(control.gain, control.muted, control.solo)
                }
            }
            val metronome = desired.metronome
            NativeRuntimeControls(
                revision = 1L,
                sourceIntentRevision = sourceIntentRevision,
                stemIntentRevision = stemIntentRevision,
                sourceMode = sourceMode,
                stems = stemControls,
                metronome = NativeRuntimeMetronomeControl(
                    mode = when {
                        !metronome.enabled || !nativeBeatGridSupported(metronome) -> NativeRuntimeMetronomeMode.OFF
                        metronome.songSynced && metronome.beatGridMs.size >= 2 ->
                            NativeRuntimeMetronomeMode.BEAT_GRID
                        else -> NativeRuntimeMetronomeMode.FREE_BPM
                    },
                    bpm = metronome.bpm,
                    gain = metronomeClickAmplitude(metronome.volume),
                    subdivision = metronome.subdivision,
                    beatsPerBar = metronome.numerator,
                    beatUnitDenominator = metronome.denominator
                ),
                tempo = desired.tempo,
                pitchSemitones = desired.semitoneShift.toFloat()
            )
        }
        val previous = lastRuntimeControls
        if (previous != null && lastControlOwnership == ownership &&
            lastControlSourceIntentRevision == sourceIntentRevision &&
            lastControlStemIntentRevision == stemIntentRevision &&
            previous.copy(revision = 1L) == draft
        ) {
            return previous
        }

        controlRevision = increment(controlRevision, "control revision")
        return draft.copy(revision = controlRevision).also { controls ->
            lastControlOwnership = ownership
            lastControlSourceIntentRevision = sourceIntentRevision
            lastControlStemIntentRevision = stemIntentRevision
            lastRuntimeControls = controls
        }
    }

    private fun reconcileManagedLoopOnActor(record: BankRecord, desired: EngineDesiredState) {
        if (!record.assigned || selected !== record || record.bank in releases) return
        if (cacheBuilder?.let { it.bank in releases } == true) return
        val physicalStems = physicalStemPackageForLoop(record)

        val explicit = actorExplicitLoopState?.takeIf {
            it.token == desired.selection.token && it.config != null
        }
        if (explicit != null) {
            // A/B is exclusively owned by the atomic touch lane. Never revive its retired
            // decoder-head protocol if cache ownership changes after the caller-thread check.
            if (loopPreparation.purpose != LoopPreparationPurpose.RANGE && !cancelPreparationOnActor(abortNative = true)) return
            reconcileBoundedLoopOnActor(record, requireNotNull(explicit.config))
            repeatOneEffective = false
            // Rebind demand with the seek command. Native still requires this exact owner's
            // exact cache range before preparing PCM; waiting for an actor readiness poll here starts
            // the loop head too late for a B capture immediately after the seek is presented.
            rebindExplicitLoopPolicyOnActor(record)
            return
        }
        // Repeat preparation is not allowed to occupy or synchronously block the interactive A/B
        // decoder while Repeat One is Off.
        if (repeatOneDesired(record, desired)) {
            reconcileRepeatPrewarmOnActor(record, physicalStems)
        } else if (loopPreparation.repeat?.let { it.result != LoopPreparationResult.Ready } == true) {
            loopPreparation.clear(LoopPreparationPurpose.REPEAT)
            cancelPreparationOnActor(abortNative = true)
        }
        val intentTemplate = when {
            repeatOneDesired(record, desired) -> loopPreparation.repeat
                ?.takeIf { it.result == LoopPreparationResult.Ready }?.intent
            else -> null
        }
        publishManagedLoopPolicyOnActor(record, desired, intentTemplate)
    }

    private fun rebindExplicitLoopPolicyOnActor(record: BankRecord, force: Boolean = false) {
        var rejected: NativeRuntimePublishStatus? = null
        synchronized(manualSelectionSilenceArmGate) {
            val state = actorExplicitLoopState?.takeIf {
                it.token == record.selection?.token && it.config != null
            } ?: return@synchronized
            if (!force && state.ownership == record.ownership) return@synchronized
            // The caller publication lane uses this same lock. Re-reading here prevents a seek
            // rebase from overwriting a newer finger sample with the old owner's last A/B.
            if (explicitLoopState.get() != state) {
                kick()
                return@synchronized
            }
            val direct = requireNotNull(state.config)
            val publicationRevision = directLoopPolicyRevision.incrementAndGet()
            check(publicationRevision > 0L) { "Direct loop policy revision space exhausted" }
            val status = environment.publishDirectLoopPolicy(
                revision = publicationRevision,
                selectionToken = state.token.value,
                generation = record.ownership.generation,
                enabled = true,
                aTimelineFrame = msToFrame(direct.startMs),
                bTimelineFrameExclusive = msToFrame(direct.endMs),
                capturedBTimelineFrameExclusive = 0L,
                inputEventAtMonotonicMicros = 0L,
                heldBGestureId = 0L,
                boundaryArmedAtPublication = !direct.parkUntilRangeEntry,
                boundaryCrossedAtPublication = false
            )
            if (status != NativeRuntimePublishStatus.ACCEPTED) {
                rejected = status
                return@synchronized
            }
            val rebound = state.copy(
                ownership = record.ownership,
                nativePolicyRevision = publicationRevision
            )
            explicitLoopState.set(rebound)
            actorExplicitLoopState = rebound
        }
        rejected?.let { emitFailure("direct-loop-seek-rebind", "Native rejected direct loop owner rebind: $it") }
    }

    private fun loopRequiredLaneMask(record: BankRecord): Int {
        val audibleSource = record.sourceActivation.applied?.mode ?: when (
            record.sourceActivation.published?.mode
        ) {
            NativeRuntimeSourceMode.STEMS -> EngineSourceMode.STEMS
            else -> EngineSourceMode.ORIGINAL
        }
        return if (audibleSource == EngineSourceMode.STEMS) {
            musicLaneMaskForStemCount(record.stemPackage?.canonicalLanes?.size ?: 0)
        } else {
            NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK
        }
    }

    private fun physicalStemPackageForLoop(record: BankRecord): StemPackageRequest? =
        record.stemPackage?.takeIf {
            // A seek can briefly publish Original while the same stem package rebinds. Its
            // independent Repeat head remains usable; do not discard and decode it again.
            (record.desired?.sourceMode == EngineSourceMode.STEMS ||
                loopRequiredLaneMask(record) != NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK) &&
                (record.decoders.keys.containsAll((0..it.canonicalLanes.size).toList()) || cacheBackedStemsReady(record))
        }

    // Prepare the requested physical source before waiting for its audible acknowledgement.
    private fun loopHeadLaneMask(record: BankRecord): Int = musicLaneMaskForStemCount(
        physicalStemPackageForLoop(record)?.canonicalLanes?.size ?: 0)

    private fun reconcileBoundedLoopOnActor(record: BankRecord, config: EngineLoopConfig?) {
        val readiness = latestNativeRenderReadiness?.takeIf { it.ownership == record.ownership } ?: return
        if (publicOwnership != record.ownership) return
        val existing = loopPreparation.range?.intent?.takeIf { it.ownership == record.ownership }
        val decision = cacheAdmission.decide(record, publicOwnership, loopHeadLaneMask(record),
            readiness, bankIntents.toList(),
            listOfNotNull(record, cacheBuilder).filter { it.bank !in releases },
            currentTimelineFrame(record), config, existing)
        if (decision !is NativeLoopCacheAdmission.Decision.Prepare) return
        val physicalStems = physicalStemPackageForLoop(record)
        val identity = cacheAdmission.identity(record, EffectiveManagedLoop(decision.startMs, decision.boundaryMode),
            physicalStems).copy(endTimelineFrameExclusive = decision.endFrameExclusive)
        if (existing == null) loopPreparation.clear(LoopPreparationPurpose.RANGE)
        loopPreparation.reconcile(LoopPreparationPurpose.RANGE, record.ownership, identity,
            loopPreparationSources(record, physicalStems), runtimeStopped)
    }

    private fun reconcileRepeatPrewarmOnActor(
        record: BankRecord,
        physicalStems: StemPackageRequest?
    ) {
        if (publicOwnership != record.ownership || publicSelection != record.selection) return
        val identity = cacheAdmission.identity(record, EffectiveManagedLoop(record.identity.playbackStartMs,
            NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS), physicalStems)
        loopPreparation.reconcile(LoopPreparationPurpose.REPEAT, record.ownership, identity,
            loopPreparationSources(record, physicalStems), runtimeStopped)
    }

    private fun publishManagedLoopPolicyOnActor(
        record: BankRecord,
        desired: EngineDesiredState,
        template: NativeRuntimeManagedLoopIntent?
    ) {
        val identity = template?.let { enabled ->
            val key = requireNotNull(enabled.cacheKey)
            ManagedLoopStableIdentity(
                selectionToken = desired.selection.token,
                enabled = true,
                cacheRevision = key.loopRevision,
                songIdentityHash = key.songIdentityHash,
                originalContentHash = key.originalContentHash,
                stemPackageHash = key.stemPackageHash,
                contentRevision = key.contentRevision,
                startTimelineFrame = key.startTimelineFrame,
                endTimelineFrameExclusive = key.endTimelineFrameExclusive,
                boundaryMode = enabled.boundaryMode,
                requiredLaneMask = enabled.requiredLaneMask,
                declickFrames = enabled.declickFrames,
            )
        } ?: ManagedLoopStableIdentity(desired.selection.token, enabled = false)
        if (identity != managedLoopStableIdentity) {
            managedLoopStableIdentity = identity
            loopRevision = increment(loopRevision, "managed loop command revision")
            loopPreparation.clearRefill()
            lastLoopIngressReadySequence = 0L
            if (template?.boundaryMode != NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS) {
                repeatOneEffective = false
            }
        }
        check(loopRevision > 0L)
        val intent = template?.copy(
            revision = loopRevision,
            ownership = record.ownership,
        ) ?: NativeRuntimeManagedLoopIntent.disabled(loopRevision, record.ownership)
        if (managedLoopIntent == intent) return
        when (environment.publishManagedLoopIntent(intent)) {
            NativeRuntimePublishStatus.ACCEPTED -> managedLoopIntent = intent
            NativeRuntimePublishStatus.STALE_REVISION ->
                failManagedLoop(record, desired, "Native managed-loop revision was stale")
            NativeRuntimePublishStatus.INVALID_SNAPSHOT ->
                failManagedLoop(record, desired, "Native managed-loop identity was rejected")
            NativeRuntimePublishStatus.STOPPED ->
                failManagedLoop(record, desired, "Native runtime stopped during loop publication")
        }
    }

    private fun loopPreparationSources(
        record: BankRecord,
        physicalStems: StemPackageRequest?
    ): List<LoopPreparationLaneSource> = buildList {
        add(
            LoopPreparationLaneSource(
                laneIndex = NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX,
                laneId = "original",
                location = record.identity.originalLocation,
                sourceAdmission = sourceLease(record, "original", record.identity.originalLocation)
            )
        )
        physicalStems?.canonicalLanes?.forEachIndexed { index, stem ->
            add(
                LoopPreparationLaneSource(
                    laneIndex = index + NativeSongLaneIngressBank.FIRST_STEM_LANE_INDEX,
                    laneId = stem.normalizedRole,
                    location = stem.location,
                    sourceAdmission = sourceLease(record, stem.normalizedRole, stem.location, stem.contentSha256)
                )
            )
        }
    }

    private fun pollManagedLoopOnActor() {
        selected?.takeIf { record ->
            record.assigned && record.bank !in releases &&
                actorExplicitLoopState?.let { state ->
                    state.token == record.selection?.token && state.config != null &&
                        state.ownership != record.ownership
                } == true
        }?.let { record ->
            record.desired?.let { desired -> reconcileManagedLoopOnActor(record, desired) }
        }
        if (loopPreparation.repeatRetryDue()) {
            selected?.takeIf { it.assigned && it.bank !in releases }?.let { record ->
                record.desired?.takeIf { repeatOneDesired(record, it) }?.let {
                    reconcileRepeatPrewarmOnActor(record, physicalStemPackageForLoop(record))
                }
            }
        }
        advanceLoopPreparationOnActor()
        if (fatalQuarantine) return
        val intent = managedLoopIntent?.takeIf { it.enabled }
        if (intent == null) {
            repeatOneEffective = false
            return
        }
        val record = selected?.takeIf {
            it.assigned && it.ownership == intent.ownership && it.bank !in releases
        } ?: return
        val desired = record.desired ?: return
        if (effectiveManagedLoop(record, desired) == null) return
        val protocol = environment.loopProtocolSnapshot()
        val repeatEffectiveNow = intent.boundaryMode ==
            NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS &&
            repeatOneDesired(record, desired) && protocol.adopted &&
            protocol.state.latestRevision == intent.revision &&
            protocol.state.ownership == intent.ownership &&
            protocol.state.boundaryMode == NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS &&
            protocol.state.cacheAccepted && protocol.state.renderInstalled
        if (repeatOneEffective != repeatEffectiveNow) {
            repeatOneEffective = repeatEffectiveNow
            // The dormant next owner is retained throughout. Only the exact native install edge
            // changes whether its drain handoff is armed.
            reconcileAutomaticSuccessorOnActor()
        }
        val request = protocol.ingressRequest?.takeIf {
            it.sequence > lastLoopIngressReadySequence &&
                it.loopRevision == intent.revision && it.ownership == intent.ownership
        }
        if (request != null && pendingSeekOwnership != request.ownership &&
            loopPreparation.refillRequest?.sequence != request.sequence
        ) {
            // A streamed loop's future body seek parks every decoder on its next ingress epoch.
            // Keep the exact in-place user seek intact until its output generation is either
            // callback-presented or installed paused; the latest loop request remains observable
            // and is retried on the first poll after pendingSeekOwnership clears.
            beginLoopIngressPreparation(record, desired, request)
        }
        when (val update = loopPreparation.pollRefill(record, intent)) {
            is LoopRefillUpdate.Ready -> lastLoopIngressReadySequence = update.sequence
            is LoopRefillUpdate.Failed -> failManagedLoop(record, desired, update.cause)
            else -> Unit
        }
    }

    private fun advanceLoopPreparationOnActor() {
        val record = selected?.takeIf { it.bank !in releases && it.desired != null }
        when (val update = loopPreparation.advance(record?.ownership, runtimeStopped)) {
            LoopPreparationUpdate.Pending -> Unit
            LoopPreparationUpdate.Reconcile -> if (!stopping && !closing && record != null) {
                reconcileManagedLoopOnActor(record, requireNotNull(record.desired))
            }
            is LoopPreparationUpdate.Ready -> if (record != null) {
                if (update.purpose == LoopPreparationPurpose.RANGE) rebindExplicitLoopPolicyOnActor(record, force = true)
                reconcileManagedLoopOnActor(record, requireNotNull(record.desired))
            }
            is LoopPreparationUpdate.Failed -> {
                if (update.purpose == LoopPreparationPurpose.RANGE) emitFailure("loop-preparation", update.cause)
                else {
                    repeatOneEffective = false
                    emitFailure("repeat-prewarm", update.cause)
                    if (record != null) reconcileManagedLoopOnActor(record, requireNotNull(record.desired))
                }
            }
        }
    }

    private fun beginLoopIngressPreparation(record: BankRecord, desired: EngineDesiredState,
        request: NativeRuntimeLoopIngressRequest) {
        when (val update = loopPreparation.beginRefill(record, request, generationCoordinator)) {
            LoopRefillUpdate.Rebase -> {
                val template = managedLoopIntent?.takeIf { it.enabled } ?: return
                managedLoopStableIdentity = null
                publishManagedLoopPolicyOnActor(record, desired, template)
            }
            is LoopRefillUpdate.Started -> generationCursor = maxOf(generationCursor, update.ingressEpoch)
            is LoopRefillUpdate.Failed -> failManagedLoop(record, desired, update.cause)
            else -> Unit
        }
    }

    private fun cancelPreparationOnActor(abortNative: Boolean): Boolean =
        loopPreparation.cancel(abortNative, runtimeStopped) && !fatalQuarantine

    private fun enterFatalQuarantine(
        diagnostic: String,
        silenceAlreadyEngaged: Boolean = false
    ) {
        if (fatalQuarantine) return
        fatalQuarantine = true
        localStartRequest.set(null)
        localStartSettlement = null
        fatalTransitionCount.incrementAndGet()
        synchronized(schedulingGate) {
            delayedPoll?.cancel()
            delayedPoll = null
        }
        if (!silenceAlreadyEngaged) runCatching {
            generationCursor = maxOf(generationCursor, environment.engageImmediateSilenceGate())
        }
        val preparationDiagnostic = tearDownLoopPreparationForFatal()
        var retirementDiagnostic: String? = null
        if (!runtimeStopped) {
            val failure = runCatching { environment.stopRuntimeAndJoin() }.exceptionOrNull()
            runtimeStopped = failure == null
            if (failure != null) retirementDiagnostic = "native runtime join failed"
        }
        val records = sequenceOf(active, candidate, cacheBuilder)
            .filterNotNull().distinct().toList()
        // Request every cancellation before joining any worker, then release only after both
        // borrowers (decoder and runtime) have demonstrably stopped.
        val joined = runCatching {
            cancelAndJoinDecoderGroup(records.flatMap { it.decoders.values })
        }.getOrElse {
            retirementDiagnostic = "decoder cancellation or join failed"
            emptyList<NativeEngineDecoder>() to emptyList()
        }.first.toSet()
        if (runtimeStopped) records.forEach { record ->
            record.decoders.values.toList().filter(joined::contains).forEach { decoder ->
                runCatching {
                    check(decoder.safeToReleaseLane())
                    environment.releaseDecoder(record.bank, decoder)
                    record.decoders.remove(decoder.laneIndex)
                }.exceptionOrNull()?.let { retirementDiagnostic = "decoder release failed" }
            }
        }
        // Desired state is adapter-owned at this boundary. Mutating the shared store here before
        // the synchronous fatal event acquires the adapter gate creates a real race in which a
        // Play/Seek observes controller revision N+1 while the adapter still owns N. Physical
        // silence is already guaranteed above; the adapter settles its public model when it handles
        // FatalRecoveryRequired. Once quarantined, public update methods reject before invoking a
        // transform, so the deliberately frozen store can never trigger revision divergence.
        val paused = desiredStore.latest()?.let { desired ->
            if (desired.playWhenReady) desired.copy(playWhenReady = false) else desired
        }
        sequenceOf(active, candidate, cacheBuilder).filterNotNull().distinct().forEach { record ->
            val desired = record.desired
            record.desired = when {
                paused != null && desired?.selection?.token == paused.selection.token -> paused
                desired?.playWhenReady == true -> desired.copy(playWhenReady = false)
                else -> desired
            }
        }
        audibleTransportIntent = null
        routeAwaitingOwnership = null
        loopPreparation.clearRefill()
        publishedStemReadiness = null
        stopping = false
        deferredDesired = null
        listener.onEvent(
            NativeEngineControllerEvent.FatalRecoveryRequired(
                listOfNotNull(diagnostic, preparationDiagnostic, retirementDiagnostic)
                    .joinToString("; ")
            )
        )
        synchronized(manualSelectionSilenceArmGate) {
            activeStop?.complete(NativeEngineStopStatus.FATAL_QUARANTINE)
            pendingStop.getAndSet(null)?.complete(NativeEngineStopStatus.FATAL_QUARANTINE)
            activeStop = null
        }
        if (closing || closeRequested.get()) closeCompleted.countDown()
    }

    private fun tearDownLoopPreparationForFatal(): String? = loopPreparation.tearDownForFatal(runtimeStopped)

    private fun failManagedLoop(record: BankRecord, desired: EngineDesiredState, reason: String) {
        // A closing worker still owns its decoder/ingress lease. Retain the plan until the normal
        // bounded cancellation path proves it can release that owner.
        cancelPreparationOnActor(abortNative = true)
        loopPreparation.clearRefill()
        if (record.selection?.token == desired.selection.token) {
            if (actorExplicitLoopState?.let {
                    it.token == desired.selection.token && it.config != null
                } == true
            ) {
                suppressLoopAndReport(desired, reason)
            } else if ((automaticEndPlan.plan as?
                    NativeEngineAutomaticEndPlan.RestartCurrent)?.identity == record.identity
            ) {
                suppressedAutomaticEndPlanRevision = automaticEndPlan.revision
            }
        }
    }

    /** Resolves the Repeat One loop. Explicit A/B is owned only by the direct atomic lane. */
    private fun effectiveManagedLoop(
        record: BankRecord,
        desired: EngineDesiredState
    ): EffectiveManagedLoop? {
        if (!repeatOneDesired(record, desired)) return null
        return EffectiveManagedLoop(
            startMs = record.identity.playbackStartMs,
            boundaryMode = NativeRuntimeManagedLoopBoundaryMode.ORIGINAL_EOS
        )
    }

    private fun repeatOneDesired(record: BankRecord, desired: EngineDesiredState): Boolean {
        val restart = automaticEndPlan.plan as?
            NativeEngineAutomaticEndPlan.RestartCurrent ?: return false
        if (automaticEndPlan.revision == suppressedAutomaticEndPlanRevision ||
            restart.identity != record.identity || desired.selection.audioIdentity() != record.identity
        ) return false
        return true
    }

    private fun suppressLoopAndReport(desired: EngineDesiredState, reason: String) {
        val state = actorExplicitLoopState?.takeIf { it.token == desired.selection.token }
            ?: return
        if (suppressedLoopToken == desired.selection.token &&
            suppressedLoopRevision == state.revision
        ) return
        suppressedLoopToken = desired.selection.token
        suppressedLoopRevision = state.revision
        state.config?.let {
            listener.onEvent(
                NativeEngineControllerEvent.LoopNotReady(
                    desired.selection.token,
                    state.revision,
                    it,
                    reason
                )
            )
        }
    }

    private fun publishBeatGridIfPresent(record: BankRecord) {
        val desired = record.desired ?: return
        if (record.beatGridRuntimeIncarnation == runtimeIncarnation &&
            record.publishedBeatGrid?.ownership == record.ownership &&
            record.publishedBeatTiming?.sameTimingAs(desired.metronome) == true
        ) return
        if (!nativeBeatGridSupported(desired.metronome)) {
            if (record.lastBeatGridFailureRevision < desired.revision) {
                record.lastBeatGridFailureRevision = desired.revision
                listener.onEvent(NativeEngineControllerEvent.BeatGridNotReady(
                    desired.selection.token, desired.revision,
                    "Song beat grid exceeds native metronome capacity or timeline"
                ))
            }
            return
        }
        val grid = desired.metronome.beatGridMs
        val frames = if (grid.size >= 2) LongArray(grid.size) { msToFrame(grid[it]) } else LongArray(0)
        val accents = if (grid.size >= 2) resolvedBeatAccents(desired.metronome) else BooleanArray(0)
        val previous = record.publishedBeatGrid
        if (record.beatGridRuntimeIncarnation == runtimeIncarnation &&
            previous?.ownership == record.ownership && previous.beatFrames.contentEquals(frames) &&
            previous.beatAccents.contentEquals(accents)
        ) {
            record.publishedBeatTiming = desired.metronome
            return
        }
        if (previous == null && frames.isEmpty()) return
        beatGridRevision = increment(beatGridRevision, "beat-grid revision")
        val update = NativeRuntimeBeatGridUpdate(
            beatGridRevision, record.bank, record.ownership, frames, accents
        )
        if (environment.publishBeatGrid(update) != NativeRuntimePublishStatus.ACCEPTED) {
            if (record.lastBeatGridFailureRevision < desired.revision) {
                record.lastBeatGridFailureRevision = desired.revision
                listener.onEvent(
                    NativeEngineControllerEvent.BeatGridNotReady(
                        desired.selection.token,
                        desired.revision,
                        "Native runtime rejected the exact beat-event grid"
                    )
                )
            }
            emitFailure("beat-grid", "Native runtime rejected the beat grid")
        } else {
            record.publishedBeatGrid = update
            record.publishedBeatTiming = desired.metronome
            record.beatGridRuntimeIncarnation = runtimeIncarnation
        }
    }

    private fun nativeBeatGridSupported(config: EngineMetronomeConfig): Boolean =
        config.beatGridMs.size <= MAX_NATIVE_BEAT_GRID_POINTS &&
            (config.beatGridMs.isEmpty() || config.beatGridMs.first() >= 0L &&
                config.beatGridMs.last() <= MAX_ENRICHMENT_DURATION_MS)

    /**
     * Imported accents are authoritative when any beat is explicitly marked. If a provider emits
     * no accents, meter segments deterministically derive them without changing beat timestamps.
     * An all-false result is still explicit: native must not invent modulo accents for authored
     * song-sync data.
     */
    private fun resolvedBeatAccents(config: EngineMetronomeConfig): BooleanArray {
        if (config.downbeats.any { it } || config.meterSegments.isEmpty()) {
            return config.downbeats.copyOf()
        }
        val accents = BooleanArray(config.beatGridMs.size)
        config.meterSegments.forEach { segment ->
            var beatIndex = config.beatGridMs.indexOfFirst { time -> time >= segment.startMs }
            if (beatIndex < 0) return@forEach
            var beatInSegment = 0
            while (beatIndex < config.beatGridMs.size &&
                config.beatGridMs[beatIndex] < segment.endMs
            ) {
                accents[beatIndex] = beatInSegment % segment.numerator == 0
                beatInSegment++
                beatIndex++
            }
        }
        return accents
    }

    private data class SourceKey(val identity: EngineAudioIdentity?, val location: String,
        val sha256: String?, val directFloat32: Boolean)
    private val sourceLeases = mutableMapOf<SourceKey, NativeSourceAdmission>()

    private fun sourceLease(record: BankRecord, laneId: String, location: String,
        contentSha256: String? = null): NativeSourceAdmission {
        val original = laneId == "original"
        val hash = if (original) record.identity.originalContentSha256 else
            requireNotNull(contentSha256) { "A stem source requires its exact admitted package hash" }
        val key = SourceKey(record.identity.takeIf { original }, location, hash, laneId == "remaining")
        return sourceLeases.getOrPut(key) { NativeSourceAdmission(hash) }
    }

    private fun openDecoder(
        record: BankRecord,
        role: NativeSongBankRole,
        laneIndex: Int,
        laneId: String,
        location: String,
        initialPositionMs: Long,
        contentSha256: String? = null
    ): NativeEngineDecoder {
        if (laneIndex == 0) preparationEvidence.begin(NativeControllerPreparationOperation.ORIGINAL_OPEN,
            record.ownership, record.ownership.generation, "opening-decoder", scheduler.monotonicTimeMs())
        try {
            val decoder = environment.openDecoder(
                record.bank,
                role,
                laneIndex,
                NativeEngineDecoderSpec(
                    decoderId = "${record.ownership.selectionToken}-${record.bank.name}-$laneIndex",
                    laneId = laneId,
                    location = location,
                    initialPositionUs = msToUs(initialPositionMs),
                    generationBase = record.ownership.generation,
                    selectionToken = EngineSelectionToken(record.ownership.selectionToken),
                    directFloat32Audio = laneId == "remaining",
                    sourceAdmission = sourceLease(record, laneId, location, contentSha256)
                )
            )
            decoder.setSinkRetryThrottled(outputPhysicallyQuiesced)
            val started = try {
                decoder.start()
            } catch (failure: Throwable) {
                cleanupSynchronouslyFailedDecoder(record.bank, decoder)
                throw failure
            }
            if (!started) {
                cleanupSynchronouslyFailedDecoder(record.bank, decoder)
                error("MediaCodec worker did not start")
            }
            val leaseSnapshot = environment.decoderLeaseSnapshot()
            check(leaseSnapshot.totalDecoderCount <= NativeDecoderLeaseCoordinator.MAXIMUM_OPEN_DECODERS) {
                "Decoder lease budget exceeded: $leaseSnapshot"
            }
            if (laneIndex == 0) preparationEvidence.finish(NativeControllerPreparationOperation.ORIGINAL_OPEN,
                record.ownership.selectionToken, record.ownership.generation, "decoder-started", scheduler.monotonicTimeMs())
            return decoder
        } catch (failure: Throwable) {
            if (laneIndex == 0) preparationEvidence.finish(NativeControllerPreparationOperation.ORIGINAL_OPEN,
                record.ownership.selectionToken, record.ownership.generation, "failed", scheduler.monotonicTimeMs(), failure.message)
            throw failure
        }
    }

    private fun cleanupSynchronouslyFailedDecoder(
        bank: NativeRuntimeBank,
        decoder: NativeEngineDecoder
    ) {
        when (decoder.cancelAndJoin(NATIVE_DECODER_JOIN_WATCHDOG_MS)) {
            NativeEngineDecoderJoinStatus.JOINED -> {
                check(decoder.safeToReleaseLane())
                environment.releaseDecoder(bank, decoder)
            }
            NativeEngineDecoderJoinStatus.WATCHDOG_EXPIRED -> {
                throw IllegalStateException(
                    "A synchronously failed decoder exceeded the teardown watchdog"
                )
            }
        }
    }

    private fun stemPackageRequest(record: BankRecord, desired: EngineDesiredState): StemPackageRequest? =
        record.stemIdentity.resolve(desired.selection.token, desired.stemLanes,
            desired.stemRevision, record.stemIntentRevision)

    private fun rawStemPackageSignature(stems: List<EngineStemLane>): String =
        stems.joinToString(prefix = "invalid[", postfix = "]", separator = "|") {
            "${it.normalizedRole}:${it.contentSha256}:${it.location}"
        }.ifBlank { "invalid-empty" }

    private fun cacheBackedStemsReady(record: BankRecord): Boolean =
        record === selected && record.stemPackage != null &&
            environment.isCurrentSongCacheLaneMaskSealed(record.ownership,
                musicLaneMaskForStemCount(requireNotNull(record.stemPackage).canonicalLanes.size))

    private fun stemsReady(record: BankRecord): Boolean {
        if (cacheBackedStemsReady(record)) return true
        val packageRequest = record.stemPackage
        val nativeReadiness = latestNativeRenderReadiness
        // Rejected optional expansion streams parts at the live render horizon, while the
        // exact sealed Original remains authoritative even if its sought decoder starts later.
        val streamedParts = nativeReadiness?.takeIf {
            it.assigned && it.bank == record.bank && it.ownership == record.ownership &&
                it.cacheStorageStatus.failed && it.currentSongCacheBoundToSelectedOwner &&
                it.currentSongCacheCompleteLaneMask and NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK != 0
        }
        val anchor = streamedParts?.renderStartTimelineFrame ?: record.stemAnchorFrame
        val stemCount = packageRequest?.canonicalLanes?.size ?: 0
        val probe = record.stemReadiness
        probe.begin()
        val heads = probe.heads
        for (lane in 0..stemCount) heads[lane] = record.decoders[lane]?.head()
        // Exact EOF or an authored playback end can bound the real PCM runway. Native must still
        // prove its full logical horizon below, including safe padding outside the playback view.
        val matchingEof = if (packageRequest != null && anchor != null) {
            heads[0]?.endOfStreamTimelineFrameExclusive48k?.takeIf { end ->
                end > anchor && (0..stemCount).all { lane ->
                    val head = heads[lane]
                    head != null && head.configured && head.nativeGeneration == record.ingressEpoch &&
                        head.endOfStreamTimelineFrameExclusive48k == end &&
                        head.acceptedTimelineFrameExclusive48k == end &&
                        head.firstTimelineFrame48k?.let { it <= anchor } == true &&
                        head.discontinuities == 0L
                }
            }
        } else null
        val requiredEnd = anchor?.let { value ->
            try {
                Math.addExact(value, MINIMUM_PRIMED_TIMELINE_FRAMES).let { fullEnd ->
                    val physicalEnd = matchingEof?.let { minOf(fullEnd, it) } ?: fullEnd
                    if (record.identity.playbackEndMs < record.identity.durationMs) {
                        minOf(physicalEnd, msToFrame(record.identity.playbackEndMs))
                    } else physicalEnd
                }
            } catch (_: ArithmeticException) {
                null
            }
        }
        val minimumAcceptedFrames = if (anchor != null && requiredEnd != null) {
            requiredEnd - anchor
        } else MINIMUM_PRIMED_TIMELINE_FRAMES
        val originalCachedRangeReady = streamedParts != null && packageRequest != null && anchor != null && requiredEnd != null &&
            requiredEnd > anchor && environment.isCurrentSongCacheRangeReady(
                bankIntents[record.bank.nativeCode], NativeRuntimeManagedLoopIntent.ORIGINAL_LANE_MASK,
                anchor, requiredEnd)
        val nativeLanes = nativeReadiness?.lanes.orEmpty()
        val nativeStemCount = nativeLanes.count { it.laneIndex in 1..stemCount }
        val requiredNativeAheadFrames = environment.requiredNativeStemRenderAheadFrames
        val nativeAlreadyReady = record.stemReadiness.readyGeneration == record.ownership.generation &&
            record.stemReadiness.readyPackageSignature == packageRequest?.signature
        val nativeObservedReady = nativeReadiness?.let { native ->
                native.sequence > 0L && native.assigned && native.bank == record.bank &&
                    native.ownership == record.ownership &&
                    native.requiredSongLaneMask == musicLaneMaskForStemCount(stemCount) &&
                    nativeStemCount == stemCount &&
                    nativeLanes.all { lane ->
                        lane.laneIndex !in 1..stemCount || (lane.generation == record.ownership.generation &&
                            lane.availableFromRenderStart >=
                            requiredNativeAheadFrames)
                    }
            } == true
        if (nativeObservedReady && packageRequest != null) {
            record.stemReadiness.readyGeneration = record.ownership.generation
            record.stemReadiness.readyPackageSignature = packageRequest.signature
        }
        val nativeReady = requiredNativeAheadFrames <= 0L ||
            nativeAlreadyReady || nativeObservedReady
        var decoderReady = packageRequest != null && anchor != null && requiredEnd != null && requiredEnd > anchor
        for (lane in 0..stemCount) {
            val head = heads[lane]
            val configured = head?.configured == true && head.nativeGeneration == record.ingressEpoch
            val minimum = head != null && head.acceptedTimelineFrames48k >= minimumAcceptedFrames
            val starts = head?.firstTimelineFrame48k?.let { anchor != null && it <= anchor } == true
            val ends = head?.acceptedTimelineFrameExclusive48k?.let { requiredEnd != null && it >= requiredEnd } == true
            val continuous = head != null && head.discontinuities == 0L
            val flags = if (lane == 0 && originalCachedRangeReady) 31 else (if (configured) 1 else 0) or (if (minimum) 2 else 0) or
                (if (starts) 4 else 0) or (if (ends) 8 else 0) or (if (continuous) 16 else 0)
            probe.observeLane(lane, record.decoders[lane]?.decoderId, flags)
            if (flags != 31) decoderReady = false
        }
        probe.observeNative(nativeReady, nativeReadiness, stemCount)
        val ready = decoderReady && nativeReady
        if (record !== selected) return ready
        val prior = publishedStemReadiness
        if (!probe.changed && prior != null && prior.ownership == record.ownership &&
            prior.selectionToken == record.selection?.token?.value && prior.ready == ready &&
            prior.controlRevision == controlRevision && prior.desiredRevision == record.desired?.revision &&
            prior.desiredSourceMode == record.desired?.sourceMode && prior.sourceIntentRevision == record.sourceIntentRevision &&
            prior.stemPackageSignature == packageRequest?.signature && prior.stemPackageRequestRevision == packageRequest?.requestRevision &&
            prior.pendingStemRequestRevision == record.pendingStemPackage?.requestRevision &&
            prior.stemReadyEventRevision == record.stemReadyEventRevision && prior.anchorFrame48k == anchor &&
            prior.requiredEndFrame48k == requiredEnd) return ready
        val lanes = (0..stemCount).map { laneIndex ->
            val decoder = record.decoders[laneIndex]
            val head = heads[laneIndex]
            val cached = laneIndex == 0 && originalCachedRangeReady
            val hasMinimum = cached || head != null &&
                head.acceptedTimelineFrames48k >= minimumAcceptedFrames
            val coversAnchor = cached || head?.firstTimelineFrame48k != null && anchor != null &&
                head.firstTimelineFrame48k <= anchor
            val coversRequiredEnd = cached || head?.acceptedTimelineFrameExclusive48k != null &&
                requiredEnd != null && head.acceptedTimelineFrameExclusive48k >= requiredEnd
            NativeStemLaneReadinessDiagnostics(
                laneIndex = laneIndex,
                decoderId = decoder?.decoderId,
                configured = head?.configured == true && head.nativeGeneration == record.ingressEpoch,
                acceptedTimelineFrames48k = head?.acceptedTimelineFrames48k ?: 0L,
                firstTimelineFrame48k = head?.firstTimelineFrame48k,
                acceptedTimelineFrameExclusive48k =
                    head?.acceptedTimelineFrameExclusive48k,
                discontinuities = head?.discontinuities ?: 0L,
                hasMinimumAcceptedFrames = hasMinimum,
                coversAnchor = coversAnchor,
                coversRequiredEnd = coversRequiredEnd,
                ready = cached || head?.configured == true && head.nativeGeneration == record.ingressEpoch && hasMinimum && coversAnchor &&
                    coversRequiredEnd && head.discontinuities == 0L
            )
        }
        val nativeStemLanes = nativeLanes.filter { it.laneIndex in 1..stemCount }
        val failureReasons = buildList {
            if (packageRequest == null) add("no-physical-package")
            if (anchor == null) add("no-anchor")
            if (anchor != null && requiredEnd == null) add("anchor-overflow")
            lanes.filter { it.decoderId == null }.forEach { add("lane-${it.laneIndex}-missing") }
            lanes.filter { it.decoderId != null && !it.configured }
                .forEach { add("lane-${it.laneIndex}-unconfigured") }
            lanes.filter { it.decoderId != null && !it.hasMinimumAcceptedFrames }
                .forEach { add("lane-${it.laneIndex}-short") }
            lanes.filter { it.decoderId != null && !it.coversAnchor }
                .forEach { add("lane-${it.laneIndex}-starts-after-anchor") }
            lanes.filter { it.decoderId != null && !it.coversRequiredEnd }
                .forEach { add("lane-${it.laneIndex}-ends-before-required") }
            lanes.filter { it.discontinuities != 0L }
                .forEach { add("lane-${it.laneIndex}-discontinuities-${it.discontinuities}") }
            if (requiredNativeAheadFrames > 0L && !nativeReady) {
                when {
                    nativeReadiness == null || nativeReadiness.sequence <= 0L ->
                        add("native-render-readiness-unobserved")
                    !nativeReadiness.assigned -> add("native-render-unassigned")
                    nativeReadiness.bank != record.bank -> add("native-render-bank-mismatch")
                    nativeReadiness.ownership != record.ownership ->
                        add("native-render-owner-mismatch")
                    nativeStemLanes.size != stemCount ||
                        nativeReadiness.requiredSongLaneMask != musicLaneMaskForStemCount(stemCount) ->
                        add("native-render-stem-lanes-missing")
                    else -> nativeStemLanes.filter { lane ->
                        lane.generation != record.ownership.generation ||
                            lane.availableFromRenderStart <
                            requiredNativeAheadFrames
                    }.forEach { lane ->
                        add(
                            "native-lane-${lane.laneIndex}-ahead-${lane.availableFromRenderStart}"
                        )
                    }
                }
            }
        }
        if (record === selected) {
            publishedStemReadiness = NativeStemReadinessDiagnostics(
                actorTurn = actorTurnCount.get(),
                controlRevision = controlRevision,
                selectionToken = record.selection?.token?.value,
                ownership = record.ownership,
                desiredRevision = record.desired?.revision,
                desiredSourceMode = record.desired?.sourceMode,
                sourceIntentRevision = record.sourceIntentRevision,
                stemPackageSignature = packageRequest?.signature,
                stemPackageRequestRevision = packageRequest?.requestRevision,
                pendingStemRequestRevision = record.pendingStemPackage?.requestRevision,
                stemReadyEventRevision = record.stemReadyEventRevision,
                anchorFrame48k = anchor,
                requiredEndFrame48k = requiredEnd,
                ready = ready,
                failureReasons = failureReasons,
                lanes = lanes
            )
        }
        return ready
    }

    private fun effectivePlaying(record: BankRecord): Boolean {
        val latestTransport = audibleTransportIntent?.takeIf { ownerGate.isLatest(it.token) }
        return (latestTransport?.playWhenReady ?: record.desired?.playWhenReady) == true &&
            !focusSuspended && !physicalReopenBeforePlay && !routeOutputNeedsReopen
    }

    private fun currentTimelineFrame(record: BankRecord): Long =
        environment.controlProgress().presentedPosition
            ?.takeIf { it.ownership == record.ownership }
            ?.lastTimelineFrame
            ?: msToFrame(record.desired?.requestedPositionMs ?: 0L)

    /**
     * Returns the exact next outgoing frame only after the clean replacement decoder covers that
     * point with a primed runway. A replacement decoder starts while the outgoing bank remains
     * audible, so its first accepted frame is deliberately behind the live cursor by selection
     * time. Assigning either the song's original start or that stale decoder head would create a
     * discontinuity or an audible rewind.
     */
    private fun originalHeadReady(head: NativeEngineDecoderHead): Boolean {
        if (!head.configured || head.acceptedTimelineFrames48k <= 0L) return false
        val first = head.firstTimelineFrame48k ?: return false
        val end = head.acceptedTimelineFrameExclusive48k ?: return false
        if (end <= first) return false
        if (head.endOfStreamTimelineFrameExclusive48k?.let { it != end } == true) return false
        return head.acceptedTimelineFrames48k >= MINIMUM_PRIMED_TIMELINE_FRAMES ||
            head.endOfStreamTimelineFrameExclusive48k?.let { it == end } == true
    }

    private fun replacementAssignmentFrame(record: BankRecord): Long? {
        val selection = record.selection ?: return null
        val original = record.decoders[NativeSongLaneIngressBank.ORIGINAL_LANE_INDEX]
            ?: return null
        val head = original.head()
        val firstAccepted = head.firstTimelineFrame48k ?: return null
        val acceptedExclusive = head.acceptedTimelineFrameExclusive48k ?: return null
        if (acceptedExclusive <= firstAccepted) return null
        if (record.replacementPurpose == InternalReplacementPurpose.EARLY_SEEK) {
            // Codec/SRC quantization can put the first real sample after the requested frame.
            val assignmentFrame = maxOf(firstAccepted, msToFrame(record.desired?.requestedPositionMs
                ?: selection.startPositionMs))
            if (selection.playbackEndMs < selection.durationMs &&
                assignmentFrame >= msToFrame(selection.playbackEndMs)) return null
            if (assignmentFrame >= acceptedExclusive) return null
            val minimumRequiredExclusive = try {
                Math.addExact(assignmentFrame, MINIMUM_PRIMED_TIMELINE_FRAMES)
            } catch (_: ArithmeticException) {
                return null
            }
            val requiredExclusive = head.endOfStreamTimelineFrameExclusive48k?.let { endOfStream ->
                minOf(minimumRequiredExclusive, endOfStream)
            } ?: minimumRequiredExclusive
            return assignmentFrame.takeIf { acceptedExclusive >= requiredExclusive }
        }
        val outgoing = active?.takeIf { current ->
            current !== record && current.bank !in releases &&
                current.selection?.token == selection.token &&
                current.identity == record.identity
        }
        val requestedFrame = msToFrame(record.desired?.requestedPositionMs
            ?: selection.startPositionMs)
        val outgoingPresentation = outgoing?.let { current ->
            environment.controlProgress().presentedPosition
                ?.takeIf { it.ownership == current.ownership }
        }
        if (outgoing != null && effectivePlaying(outgoing) && outgoingPresentation == null) {
            // A playing replacement has no safe fallback cursor. Wait for callback-owned evidence
            // instead of silently rewinding to the last requested transport position.
            return null
        }
        val liveNextFrame = outgoing?.let { current ->
            record.sourceTransitionTarget = record.ownership
            environment.prepareSourceTransition(current.ownership, record.ownership,
                if (effectivePlaying(current)) null else msToFrame(current.desired?.requestedPositionMs
                    ?: selection.startPositionMs)) ?: return null
        }
        if (liveNextFrame != null && firstAccepted > liveNextFrame) return null
        val assignmentFrame = maxOf(firstAccepted, liveNextFrame ?: requestedFrame)
        if (selection.playbackEndMs < selection.durationMs &&
            assignmentFrame >= msToFrame(selection.playbackEndMs)) return null
        if (assignmentFrame >= acceptedExclusive) return null
        val minimumRequiredExclusive = try {
            Math.addExact(assignmentFrame, MINIMUM_PRIMED_TIMELINE_FRAMES)
        } catch (_: ArithmeticException) {
            return null
        }
        val requiredExclusive = head.endOfStreamTimelineFrameExclusive48k?.let { endOfStream ->
            minOf(minimumRequiredExclusive, endOfStream)
        } ?: minimumRequiredExclusive
        return assignmentFrame.takeIf { acceptedExclusive >= requiredExclusive }
    }

    // Retrying the live owner must be able to reach the seek that retires an unrelated prewarm.
    private fun canProcessDeferredDesired(): Boolean {
        val desired = deferredDesired ?: return false
        return !stopping && !closing && !runtimeStopped && !routeOutputNeedsReopen &&
            routeRecoveryTransaction == null && releases.size < 2 &&
            manualSelectionInvalidation.get() == null &&
            (candidate == null || candidate?.selection?.token == desired.selection.token ||
                candidate?.reservation?.token == desired.selection.token ||
                active?.let { it.selection?.token == desired.selection.token && it.bank !in releases } == true)
    }

    private fun freeBank(): NativeRuntimeBank? = NativeRuntimeBank.entries.firstOrNull { bank ->
        active?.bank != bank && candidate?.bank != bank && cacheBuilder?.bank != bank &&
            bank !in releases
    }

    private fun nextBankRevision(bank: NativeRuntimeBank): Long =
        increment(bankIntents[bank.nativeCode].revision, "bank intent revision")

    /** Republishes content identity without changing the bank's exact owner or timeline anchor. */
    private fun refreshAssignedContentIdentity(record: BankRecord) {
        val current = bankIntents[record.bank.nativeCode]
        check(current.assigned && current.ownership == record.ownership)
        val intended = assignedBankIntent(
            record,
            current.revision,
            current.firstTimelineFrame,
            current.outputClockStartFrame
        )
        if (intended == current) return
        val revision = nextBankRevision(record.bank)
        record.intentRevision = revision
        bankIntents[record.bank.nativeCode] = intended.copy(revision = revision)
    }

    private fun assignedBankIntent(
        record: BankRecord,
        revision: Long,
        firstTimelineFrame: Long,
        outputClockStartFrame: Long
    ): NativeRuntimeBankIntent {
        val intendedStemPackage = if (record.stemCleanupRequired) null else {
            record.stemPackage ?: record.desired?.takeIf { record.lastFailedStemRequestRevision == 0L }
                ?.let { stemPackageRequest(record, it) }?.takeUnless {
                it.requestRevision == record.lastFailedStemRequestRevision
            }
        }
        return NativeRuntimeBankIntent(
            revision = revision,
            ownership = record.ownership,
            firstTimelineFrame = firstTimelineFrame,
            outputClockStartFrame = outputClockStartFrame,
            songIdentityHash = stableRawHash("song-v1", record.identity.songId),
            originalContentHash = stableRawHash(
                "original-v1",
                record.identity.audioRevision,
                record.identity.originalLocation,
                record.identity.originalContentSha256 ?: record.identity.audioRevision
            ),
            stemPackageHash = intendedStemPackage?.signature?.let {
                stableRawHash("stems-v1", it)
            } ?: 0L,
            stemLaneCount = intendedStemPackage?.canonicalLanes?.size ?: 0,
            playbackEndFrameExclusive = if (record.identity.playbackEndMs < record.identity.durationMs) {
                msToFrame(record.identity.playbackEndMs)
            } else 0L,
            cacheFrameUpperBound = try {
                Math.addExact(Math.multiplyExact(record.identity.durationMs, 48L), 2_048L)
            } catch (_: ArithmeticException) { Long.MAX_VALUE }
        )
    }

    private fun allocateGeneration(): Long {
        generationCursor = increment(
            maxOf(
                generationCursor,
                acceptedCommandOutputGeneration,
                environment.outputGeneration
            ),
            "native generation"
        )
        return generationCursor
    }

    private fun nextCommandRevision(): Long {
        commandRevision = increment(commandRevision, "command revision")
        return commandRevision
    }

    private fun nextPollDelayMs(): Long? {
        if (fatalQuarantine || closeCompleted.count == 0L) return null
        val normalDelay = if (mailbox.hasPendingWork() || automaticEndPlanUpdate.get() != null ||
            manualPrewarmUpdate.get() != null || routeRecoveryRequested.get()
        ) {
            IMMEDIATE_RECHECK_INTERVAL_MS
        } else if (hasPhysicalPlayDemand() && outputPreparation != NativeOutputPreparationState.READY) {
            if (outputPreparation == NativeOutputPreparationState.OPENING) TRANSITION_POLL_INTERVAL_MS else ROUTE_RECOVERY_RETRY_INTERVAL_MS
        } else if (routeRecoveryTransaction != null) {
            if (hasPhysicalPlayDemand() || deferredDesired != null || routeRecoveryTransaction?.target != null)
                ROUTE_RECOVERY_RETRY_INTERVAL_MS else null
        } else if (exactSourceApplicationEvidencePending()) {
            // The callback has to consume the output reserve and finish the native source
            // crossfade before it can publish one whole-callback source tuple. Once the exact safe
            // tuple is armed, a normal 20 ms playing poll is needless user-visible latency. Poll
            // only this bounded acknowledgement window at 10 ms; cold/not-ready Stems never enters
            // it, and ownership/reversal/source-revision changes invalidate it immediately.
            IMMEDIATE_RECHECK_INTERVAL_MS
        } else if (releases.isNotEmpty() || routeAwaitingOwnership != null ||
            pendingPublication != null || pendingSeekOwnership != null ||
            manualSelectionInvalidation.get() != null ||
            active?.stemCleanupRequired == true ||
            loopPreparation.preparing || loopPreparation.refillRequest != null ||
            stopping || closing || deferredDesired != null ||
            candidate?.selection != null || pendingStemPreparation() || pendingOwnerReadiness()
        ) {
            TRANSITION_POLL_INTERVAL_MS
        } else if (selected?.let(::effectivePlaying) == true) {
            PLAYING_POLL_INTERVAL_MS
        } else if (selected != null || candidate != null) {
            if (outputPhysicallyQuiesced && !pendingCacheReadiness()) null else PAUSED_HEALTH_POLL_INTERVAL_MS
        } else {
            null
        }
        var delay = normalDelay?.coerceAtLeast(IMMEDIATE_RECHECK_INTERVAL_MS)
        val now = scheduler.monotonicTimeMs()
        localStartSettlement?.let { settlement ->
            val remaining = (settlement.deadlineAtMs - now).coerceAtLeast(1L)
            delay = delay?.let { minOf(it, remaining) } ?: remaining
        }
        outputWarmPauseDeadlineMs?.let { deadline ->
            val remaining = (deadline - now).coerceAtLeast(1L)
            delay = delay?.let { minOf(it, remaining) } ?: remaining
        }
        // Polling has a floor; an already-running deadline must still fire on time.
        return delay
    }

    private fun updateOutputWarmStateOnAcceptedSnapshot(
        selectedRecord: BankRecord?,
        playing: Boolean
    ) {
        if (selectedRecord == null || runtimeStopped || stopping || closing) {
            lastAcceptedOutputPlaying = null
            outputWarmPauseDeadlineMs = null
            if (outputPhysicallyQuiesced) setDecoderSinkRetryThrottled(false)
            outputPhysicallyQuiesced = false
            return
        }
        if (playing) {
            if (lastAcceptedOutputPlaying != true || outputPhysicallyQuiesced) {
                setDecoderSinkRetryThrottled(false)
            }
            lastAcceptedOutputPlaying = true
            outputWarmPauseDeadlineMs = null
            outputPhysicallyQuiesced = false
            return
        }
        if (lastAcceptedOutputPlaying != false && !outputPhysicallyQuiesced) {
            outputWarmPauseDeadlineMs = scheduler.monotonicTimeMs() +
                if (focusSuspended) 0L else environment.outputWarmPauseGraceMs
        }
        lastAcceptedOutputPlaying = false
    }

    private fun reconcileOutputQuiescenceOnActor() {
        val record = selected?.takeIf { it.assigned && it.bank !in releases }
        if (runtimeStopped || stopping || closing || record == null || effectivePlaying(record)) {
            outputWarmPauseDeadlineMs = null
            if (record == null || effectivePlaying(record)) {
                if (outputPhysicallyQuiesced) setDecoderSinkRetryThrottled(false)
                outputPhysicallyQuiesced = false
            }
            return
        }
        val deadline = outputWarmPauseDeadlineMs ?: return
        val now = scheduler.monotonicTimeMs()
        if (now < deadline) return
        if (environment.quiesceOutputIfPaused()) {
            outputWarmPauseDeadlineMs = null
            if (!outputPhysicallyQuiesced) outputQuiesceCount++
            outputPhysicallyQuiesced = true
            setDecoderSinkRetryThrottled(true)
        } else {
            outputWarmPauseDeadlineMs = now + OUTPUT_QUIESCE_RETRY_INTERVAL_MS
        }
    }

    private fun exactSourceApplicationEvidencePending(): Boolean {
        if (runtimeStopped || stopping || closing) return false
        val record = selected?.takeIf {
            it.assigned && it.bank !in releases && it.ownership == publicOwnership
        } ?: return false
        // Paused source readiness is published separately; no audible callback can settle here.
        if (!effectivePlaying(record)) return false
        val desired = record.desired?.takeIf {
            it.selection.token == publicSelection?.token
        } ?: return false
        if ((record.sourceActivation.applied?.revision ?: 0L) >= record.sourceIntentRevision &&
            record.sourceActivation.applied?.mode == desired.sourceMode
        ) return false
        return sourceControlPublications.any { publication ->
            publication.ownership == record.ownership &&
                publication.token == desired.selection.token &&
                publication.desiredRevision == record.sourceIntentRevision &&
                publication.requestedMode == desired.sourceMode &&
                publication.nativeMode.toEngineSourceMode() == desired.sourceMode
        }
    }

    private fun pendingStemPreparation(): Boolean {
        val record = active?.takeIf { it.assigned && it.bank !in releases } ?: return false
        val pending = record.pendingStemPackage ?: return false
        // Physical output pause suppresses idle polling, not an explicitly requested decoder job.
        // Stop observing as soon as that exact job is ready or failed; resume owns audible proof.
        return pending.requestRevision != record.stemReadyEventRevision &&
            pending.requestRevision != record.lastFailedStemRequestRevision
    }

    private fun pendingOwnerReadiness(): Boolean {
        val observed = latestNativeRenderReadiness ?: return false
        val record = selected?.takeIf { it.assigned && it.bank !in releases } ?: return false
        // JNI requests a worker snapshot and returns the previous one. A paused seek must observe
        // the new generation before going idle, even after its transport ownership has committed.
        return observed.ownership != record.ownership
    }

    private fun pendingCacheReadiness(): Boolean {
        val observed = latestNativeRenderReadiness
        val record = selected?.takeIf { it.assigned && it.bank !in releases }
        val explicitLoop = record != null && actorExplicitLoopState?.let {
            it.token == record.selection?.token && it.config != null
        } == true
        val intent = record?.let { bankIntents[it.bank.nativeCode] }
        val terminalFailure = record != null && observed != null && observed.ownership == record.ownership &&
            observed.bank == record.bank && observed.cacheStorageStatus.failed
        // Explicit head preparation also needs fresh worker snapshots: the cheap summary cannot
        // announce its completion until a request causes native to publish those details.
        val explicitReadinessPending = explicitLoop && publishedLoopAvailabilityOwnership == record?.ownership &&
            publishedLoopPreparation.phase == LoopPreparationPhase.PREPARING
        // A complete or non-growing cache cannot settle an unresolved physical head. Ordinary
        // cache requests still retire on exact-owner readiness or terminal storage failure.
        val pending = record != null && intent != null && intent.ownership == record.ownership &&
            (explicitReadinessPending || (!explicitLoop && intent.cacheFrameUpperBound > 0L && !terminalFailure &&
                !(publishedLoopAvailabilityOwnership == record.ownership && publishedLoopAvailable)))
        lastCacheReadinessPending = pending
        return pending
    }

    private fun setDecoderSinkRetryThrottled(throttled: Boolean) {
        listOfNotNull(active, candidate, cacheBuilder, selected)
            .distinctBy { it.bank }
            .forEach { record ->
                record.decoders.values.forEach { decoder ->
                    decoder.setSinkRetryThrottled(throttled)
                }
            }
    }

    private fun emitFailure(operation: String, diagnostic: String) {
        listener.onEvent(NativeEngineControllerEvent.Failure(operation, diagnostic))
    }

    private companion object {
        const val IMMEDIATE_RECHECK_INTERVAL_MS = 10L
        const val ROUTE_RECOVERY_RETRY_INTERVAL_MS = 250L
        const val TRANSITION_POLL_INTERVAL_MS = 10L
        const val PLAYING_POLL_INTERVAL_MS = 20L
        const val PAUSED_HEALTH_POLL_INTERVAL_MS = 250L
        const val OUTPUT_QUIESCE_RETRY_INTERVAL_MS = 5L
        const val LOCAL_START_SETTLEMENT_TIMEOUT_MS = 15_000L
        const val LOCAL_START_SETTLEMENT_FAILURE =
            "Playback could not start within 15 seconds."
        const val MINIMUM_PRIMED_TIMELINE_FRAMES = 1_024L
        const val MANAGED_LOOP_DECLICK_FRAMES = 240
        const val MAXIMUM_REMEMBERED_TERMINAL_EVENTS = 256
        const val MAXIMUM_SOURCE_CONTROL_PUBLICATIONS = 256
        const val MAXIMUM_DIAGNOSTIC_SOURCE_CONTROL_PUBLICATIONS = 8

        fun increment(value: Long, label: String): Long {
            check(value < Long.MAX_VALUE) { "$label space exhausted" }
            return value + 1L
        }
    }
}

/** Only a post-install explicit source change can claim the outgoing graph's terminal callback. */
internal fun explicitSourceChangePendingAtEnd(
    playWhenReady: Boolean,
    sourceIntentRevision: Long,
    lastAppliedSourceRevision: Long,
    desiredSourceMode: EngineSourceMode,
    lastAppliedSourceMode: EngineSourceMode?
): Boolean = playWhenReady &&
    lastAppliedSourceRevision > 0L &&
    (sourceIntentRevision > lastAppliedSourceRevision ||
        desiredSourceMode != lastAppliedSourceMode)
